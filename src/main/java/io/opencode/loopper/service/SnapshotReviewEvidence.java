package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.TemplateTaskMapper;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.template.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Builds bounded version-tree evidence outside transactions, never checks out or executes project code. */
@Service
public class SnapshotReviewEvidence {
    private final TemplateGitSnapshotService repositories;
    private final TemplateTaskMapper tasks;
    private final SnapshotReviewStore store;
    private final GitEvidenceProcess git;
    private final TemplateGitCaptureGuard guard;
    private final tools.jackson.databind.ObjectMapper json;
    public SnapshotReviewEvidence(TemplateGitSnapshotService repositories, TemplateTaskMapper tasks, SnapshotReviewStore store,
                                  GitEvidenceProcess git, TemplateGitCaptureGuard guard, tools.jackson.databind.ObjectMapper json) {
        this.repositories = repositories; this.tasks = tasks; this.store = store; this.git = git; this.guard = guard; this.json = json;
    }
    public Path repository(String taskId) { return repositories.workspace(taskId).resolve("repository.git"); }
    public SnapshotReview.Snapshot freeze(String taskId) {
        if (store.require(taskId).snapshotJson() != null) return store.snapshot(taskId);
        return guard.capture(taskId, () -> collect(taskId));
    }
    private SnapshotReview.Snapshot collect(String taskId) {
        var run = tasks.findRun(taskId).orElseThrow();
        // Project id is authoritative in the frozen contract.
        var contract = json.readTree(run.contractJson());
        String projectId = contract.path("spec").path("projectId").asText();
        var source = repositories.freeze(taskId, projectId,
                new ProjectBranchService.Branch(run.branchId(), run.branchLabel(), run.branchRef(), run.remoteName()));
        store.source(taskId, source.head());
        var mode = SnapshotReview.Mode.valueOf(store.require(taskId).mode());
        String target = source.head(), baseline = null; boolean anomaly = false;
        var dates = TemplateDateRange.parse(run.startDate(), run.endDate(), Clock.systemUTC());
        if (mode == SnapshotReview.Mode.DATE_INCREMENTAL) {
            List<SnapshotDateSelection.Commit> commits = new ArrayList<>();
            for (int skip = 0; ; skip += 500) {
                var lines = git.read(source.repository(), "log", "--first-parent", "--format=%H %ct", "--max-count=500", "--skip=" + skip, source.head(), "--").lines().toList();
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
        String targetTree = scopedTree(source, target);
        String baselineTree = baseline == null ? null : scopedTree(source, baseline);
        boolean same = targetTree.equals(baselineTree);
        List<SnapshotReview.File> files = new ArrayList<>(manifest(taskId, target, targetTree));
        if (baseline != null && !baseline.equals(target)) files.addAll(manifest(taskId, baseline, baselineTree));
        boolean lightweight = SnapshotReviewLightweightPolicy.applies(contract.path("definition").path("version").asText());
        List<SnapshotReview.Unit> units = same ? List.of() : units(taskId, baseline, target, files, lightweight, "3".equals(contract.path("definition").path("version").asText()), baselineTree, targetTree);
        return store.freeze(taskId, new SnapshotReview.Snapshot(source.head(), baseline, target, baselineTree, targetTree,
                Instant.now().toString(), baseline == null ? null : dates.startInclusive().toString(),
                baseline == null ? null : dates.endExclusive().toString(), baseline == null ? "FROZEN_BRANCH_TIP" : "FIRST_PARENT_COMMITTER_TIME",
                anomaly, same, List.copyOf(files), units, "3".equals(contract.path("definition").path("version").asText())
                        ? TemplateGitEvidenceCollector.hash(projectId + "\n" + source.projectRoot() + "\n" + source.projectPrefix()) : null));
    }
    private List<SnapshotReview.File> manifest(String task, String sha, String tree) {
        return DocumentCodeSnapshotService.manifest(task, git.read(repository(task), "ls-tree", "-r", "-z", "-l", "--full-tree", tree)).stream()
                .map(f -> new SnapshotReview.File(sha, f.path(), f.blobSha(), f.mode(), f.sizeBytes(),
                        f.limitation() != null ? f.limitation() : excluded(f.path()))).toList();
    }
    private static String excluded(String path) {
        for (String part : path.split("/")) if (Set.of("node_modules", "vendor", "target", "dist", ".cache").contains(part))
            return "第三方依赖或构建产物未逐行审查";
        return null;
    }
    private List<SnapshotReview.Unit> units(String task, String baseline, String target, List<SnapshotReview.File> files, boolean lightweight, boolean compact, String baselineTree, String targetTree) {
        Map<String, SnapshotReview.File> targetFiles = new LinkedHashMap<>(), beforeFiles = new LinkedHashMap<>();
        files.forEach(f -> { if (f.version().equals(target)) targetFiles.put(f.path(), f); else beforeFiles.put(f.path(), f); });
        List<String[]> changes = new ArrayList<>();
        if (baseline == null) targetFiles.keySet().forEach(path -> changes.add(new String[]{"FULL", path, path}));
        else {
            String[] fields = git.read(repository(task), "--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv", "--name-status", "-z", "--find-renames", baselineTree, targetTree, "--").split("\u0000");
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
                text = baseline == null ? git.read(repository(task), "cat-file", "blob", f.blob())
                        : git.read(repository(task), "--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv", "--unified=8", baselineTree, targetTree, "--", change[1], change[2]);
                if (text.indexOf('\u0000') >= 0 || text.indexOf('\uFFFD') >= 0 || text.startsWith("Binary files") || text.contains("\nBinary files")) {
                    text = ""; limitation = "二进制或非 UTF-8 内容未作为文本审查";
                }
            }
            size += text.length();
            if (size > 64000000) throw failure("SNAPSHOT_EVIDENCE_LIMIT", "审查代码超过完整证据容量，未生成完整审查报告");
            String id = TemplateGitEvidenceCollector.hash(target + "\n" + change[1] + "\n" + change[2]);
            var compiled = lightweight ? SnapshotReviewUnits.compact(id, change, text, limitation) : SnapshotReviewUnits.compile(id, change, text, limitation);
            if (compact) {
                compiled = SnapshotReviewInitialEvidence.compile(compiled, text, old, f);
                Map<String, String> blobs = new HashMap<>();
                for (var ref : compiled.stream().flatMap(u -> u.initialEvidence().stream()).toList())
                    if (!blobs.containsKey(ref.blob())) blobs.put(ref.blob(), baseline == null ? text : git.read(repository(task), "cat-file", "blob", ref.blob()));
                compiled = SnapshotReviewInitialEvidence.verified(compiled, blobs);
            }
            result.addAll(compiled);
        }
        return List.copyOf(result);
    }
    private String scopedTree(TemplateGitSnapshotService.Snapshot source, String commit) {
        if (source.projectPrefix().isEmpty()) return git.read(source.repository(), "rev-parse", commit + "^{tree}").strip();
        String path = source.projectPrefix().substring(0, source.projectPrefix().length() - 1);
        String entry = git.read(source.repository(), "--literal-pathspecs", "ls-tree", "-z", commit, "--", path);
        // A module not yet present at a date boundary has an empty tree, never the entire repository.
        if (entry.isEmpty()) return git.read(source.repository(), "mktree").strip();
        String[] fields = entry.substring(0, entry.indexOf('\t')).split(" ");
        if (fields.length != 3 || !fields[1].equals("tree"))
            throw failure("TEMPLATE_SNAPSHOT_SCOPE_INVALID", "冻结版本中的项目路径不是目录，请检查所选分支和日期范围");
        return fields[2];
    }
    private static TaskFailure failure(String code, String message) { return new TaskFailure(code, message); }
}
