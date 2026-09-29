package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.template.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Shared fixed-version evidence algorithm; callers own source selection, persistence and process supervision. */
public final class GitReviewReader {
    private final GitEvidenceProcess git;
    public GitReviewReader(GitEvidenceProcess git) { this.git = git; }
    public SnapshotReview.Snapshot collect(Path repository, String sourceSha, String prefix, SnapshotReview.Mode mode,
            TemplateDateRange dates, String version, String scopeIdentity, String capturedAt) {
        String target = sourceSha, baseline = null; boolean anomaly = false;
        if (mode == SnapshotReview.Mode.DATE_INCREMENTAL) {
            List<SnapshotDateSelection.Commit> commits = new ArrayList<>();
            for (int skip = 0; ; skip += 500) {
                var lines = git.read(repository, "log", "--first-parent", "--format=%H %ct", "--max-count=500", "--skip=" + skip, sourceSha, "--").lines().toList();
                if (lines.isEmpty()) break;
                for (String line : lines) {
                    var fields = line.split(" ");
                    commits.add(new SnapshotDateSelection.Commit(fields[0], Instant.ofEpochSecond(Long.parseLong(fields[1]))));
                    if (commits.size() > 100000) throw failure("SNAPSHOT_HISTORY_LIMIT", "主线历史超过采集上限，请选择全面审查");
                }
            }
            try {
                var selected = SnapshotDateSelection.select(commits, dates);
                baseline = selected.baseline(); target = selected.target(); anomaly = selected.nonMonotonic();
            } catch (IllegalArgumentException missing) { throw failure("SNAPSHOT_BOUNDARY_MISSING", missing.getMessage()); }
        }
        String targetTree = scopedTree(repository, prefix, target);
        String baselineTree = baseline == null ? null : scopedTree(repository, prefix, baseline);
        boolean same = targetTree.equals(baselineTree);
        List<SnapshotReview.File> files = new ArrayList<>(manifest(repository, target, targetTree));
        if (baseline != null && !baseline.equals(target)) files.addAll(manifest(repository, baseline, baselineTree));
        boolean lightweight = SnapshotReviewLightweightPolicy.applies(version);
        List<SnapshotReview.Unit> units = same ? List.of() : units(repository, baseline, target, files, lightweight, "3".equals(version), baselineTree, targetTree);
        return new SnapshotReview.Snapshot(sourceSha, baseline, target, baselineTree, targetTree,
                capturedAt, baseline == null ? null : dates.startInclusive().toString(),
                baseline == null ? null : dates.endExclusive().toString(), baseline == null ? "FROZEN_BRANCH_TIP" : "FIRST_PARENT_COMMITTER_TIME",
                anomaly, same, List.copyOf(files), units, "3".equals(version)
                        ? scopeIdentity : null);
    }
    private List<SnapshotReview.File> manifest(Path repository, String sha, String tree) {
        return GitSnapshotInventory.parse(git.read(repository, "ls-tree", "-r", "-z", "-l", "--full-tree", tree)).stream()
                .map(f -> new SnapshotReview.File(sha, f.path(), f.blobSha(), f.mode(), f.sizeBytes(),
                        f.limitation() != null ? f.limitation() : excluded(f.path()))).toList();
    }
    private static String excluded(String path) {
        for (String part : path.split("/")) if (Set.of("node_modules", "vendor", "target", "dist", ".cache").contains(part))
            return "第三方依赖或构建产物未逐行审查";
        return null;
    }
    private List<SnapshotReview.Unit> units(Path repository, String baseline, String target, List<SnapshotReview.File> files, boolean lightweight, boolean compact, String baselineTree, String targetTree) {
        Map<String, SnapshotReview.File> targetFiles = new LinkedHashMap<>(), beforeFiles = new LinkedHashMap<>();
        files.forEach(f -> { if (f.version().equals(target)) targetFiles.put(f.path(), f); else beforeFiles.put(f.path(), f); });
        List<String[]> changes = new ArrayList<>();
        if (baseline == null) targetFiles.keySet().forEach(path -> changes.add(new String[]{"FULL", path, path}));
        else {
            String[] fields = git.read(repository, "--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv", "--name-status", "-z", "--find-renames", baselineTree, targetTree, "--").split("\u0000");
            for (int i = 0; i < fields.length;) {
                String change = fields[i++], before = fields[i++], after = before;
                if (change.startsWith("R") || change.startsWith("C")) after = fields[i++];
                changes.add(new String[]{change, before, after});
            }
        }
        List<SnapshotReview.Unit> result = new ArrayList<>(); long size = 0;
        for (String[] change : changes) {
            var f = targetFiles.get(change[2]); var old = beforeFiles.get(change[1]);
            String limitation = f != null ? f.limitation() : old == null ? "文件无法定位" : old.limitation();
            if (old != null && old.limitation() != null) limitation = old.limitation();
            String text = "";
            if (limitation == null) {
                text = baseline == null ? git.read(repository, "cat-file", "blob", f.blob())
                        : git.read(repository, "--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv", "--unified=8", baselineTree, targetTree, "--", change[1], change[2]);
                if (text.indexOf('\u0000') >= 0 || text.indexOf('\uFFFD') >= 0 || text.startsWith("Binary files") || text.contains("\nBinary files")) {
                    text = ""; limitation = "二进制或非 UTF-8 内容未作为文本审查";
                }
            }
            size += text.length();
            if (size > 64000000) throw failure("SNAPSHOT_EVIDENCE_LIMIT", "审查代码超过完整证据容量，未生成完整审查报告");
            String id = hash(target + "\n" + change[1] + "\n" + change[2]);
            var compiled = lightweight ? SnapshotReviewUnits.compact(id, change, text, limitation) : SnapshotReviewUnits.compile(id, change, text, limitation);
            if (compact) {
                compiled = SnapshotReviewInitialEvidence.compile(compiled, text, old, f);
                Map<String, String> blobs = new HashMap<>();
                for (var ref : compiled.stream().flatMap(u -> u.initialEvidence().stream()).toList())
                    if (!blobs.containsKey(ref.blob())) blobs.put(ref.blob(), baseline == null ? text : git.read(repository, "cat-file", "blob", ref.blob()));
                compiled = SnapshotReviewInitialEvidence.verified(compiled, blobs);
            }
            result.addAll(compiled);
        }
        return List.copyOf(result);
    }
    private String scopedTree(Path repository, String prefix, String commit) {
        if (prefix.isEmpty()) return git.read(repository, "rev-parse", commit + "^{tree}").strip();
        String path = prefix.substring(0, prefix.length() - 1);
        String entry = git.read(repository, "--literal-pathspecs", "ls-tree", "-z", commit, "--", path);
        // A module not yet present at a date boundary has an empty tree, never the entire repository.
        if (entry.isEmpty()) return git.read(repository, "mktree").strip();
        String[] fields = entry.substring(0, entry.indexOf('\t')).split(" ");
        if (fields.length != 3 || !fields[1].equals("tree"))
            throw failure("TEMPLATE_SNAPSHOT_SCOPE_INVALID", "冻结版本中的项目路径不是目录，请检查所选分支和日期范围");
        return fields[2];
    }
    private static String hash(String value) { return io.opencode.loopper.runtime.DurableCommandProtocol.hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static TaskFailure failure(String code, String message) { return new TaskFailure(code, message); }
}
