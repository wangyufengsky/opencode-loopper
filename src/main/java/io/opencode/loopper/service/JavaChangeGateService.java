package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageJavaBaselineRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.DirectWorkspaceBaselineManager;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Immutable per-Stage production-Java hash gate for Git and Direct workspaces. */
@Service
public class JavaChangeGateService {
    private static final Duration DIFF_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_SNAPSHOT_BYTES = 16L * 1024L * 1024L;
    private final LoopperMapper mapper;
    private final SafeProcessRunner runner;
    private final DirectWorkspaceBaselineManager directBaselines;
    private final ObjectMapper json;

    public JavaChangeGateService(LoopperMapper mapper, SafeProcessRunner runner,
                                 DirectWorkspaceBaselineManager directBaselines, ObjectMapper json) {
        this.mapper = mapper;
        this.runner = runner;
        this.directBaselines = directBaselines;
        this.json = json;
    }

    /** Performs file/process I/O before the small insert transaction owned by MyBatis. */
    public StageJavaBaselineRow captureIfAbsent(TaskRow task, StageRow stage) {
        StageJavaBaselineRow existing = mapper.findStageJavaBaseline(stage.id()).orElse(null);
        if (existing != null) return existing;
        Snapshot snapshot = snapshot(task);
        StageJavaBaselineRow row = new StageJavaBaselineRow(stage.id(), task.id(), snapshot.json(),
                snapshot.sha256(), Instant.now().toString());
        mapper.insertStageJavaBaseline(row);
        return mapper.findStageJavaBaseline(stage.id()).orElseThrow(() ->
                new TaskFailure("JAVA_STAGE_BASELINE_MISSING", "Stage Java baseline could not be persisted"));
    }

    public ChangeSet changesSinceStageStart(TaskRow task, StageRow stage) {
        StageJavaBaselineRow row = mapper.findStageJavaBaseline(stage.id()).orElseThrow(() ->
                new TaskFailure("JAVA_STAGE_BASELINE_MISSING",
                        "Production Java baseline was not captured before the Stage started"));
        Map<String, String> before;
        try { before = json.readValue(row.snapshotJson(), new TypeReference<Map<String, String>>() { }); }
        catch (Exception failure) {
            throw new TaskFailure("JAVA_STAGE_BASELINE_INVALID", "Stored Stage Java baseline cannot be read");
        }
        Snapshot after = snapshot(task);
        List<String> changed = after.hashes().entrySet().stream()
                .filter(entry -> !entry.getValue().equals(before.get(entry.getKey())))
                .map(Map.Entry::getKey).toList();
        return new ChangeSet(changed, before, after.hashes(), row.snapshotSha256(), after.sha256());
    }

    private Snapshot snapshot(TaskRow task) {
        if (task.worktreePath() == null || task.worktreePath().isBlank()
                || task.baselineCommit() == null || task.baselineCommit().isBlank()) {
            throw new TaskFailure("JAVA_CHANGE_BASELINE_UNAVAILABLE",
                    "Task execution directory and baseline are required for the Java gate");
        }
        Path root;
        try { root = Path.of(task.worktreePath()).toRealPath(); }
        catch (IOException failure) {
            throw new TaskFailure("WORKTREE_UNAVAILABLE", "Task execution directory is unavailable");
        }
        DiffEvidence diff = diff(root, task.baselineCommit());
        TreeMap<String, String> hashes = new TreeMap<>();
        long totalBytes = 0;
        for (String relative : diff.currentPaths()) {
            if (!productionJava(relative)) continue;
            Path file = managedFile(root, relative);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (before.size() < 0 || before.size() > MAX_SNAPSHOT_BYTES
                        || totalBytes + before.size() > MAX_SNAPSHOT_BYTES) {
                    throw new TaskFailure("JAVA_CHANGE_SCAN_LIMIT_EXCEEDED",
                            "Production Java snapshot exceeds the 16 MiB safety budget");
                }
                byte[] content = Files.readAllBytes(file);
                BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (content.length != before.size() || before.size() != after.size()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
                    throw new TaskFailure("JAVA_CHANGE_SCAN_UNSTABLE",
                            "Production Java file changed while its Stage snapshot was captured: " + relative);
                }
                totalBytes += content.length;
                hashes.put(relative, sha256(content));
            } catch (TaskFailure failure) {
                throw failure;
            } catch (IOException failure) {
                throw new TaskFailure("JAVA_CHANGE_SCAN_FAILED",
                        "Unable to read production Java file " + relative + ": " + failure.getMessage());
            }
        }
        try {
            String encoded = json.writeValueAsString(hashes);
            return new Snapshot(Map.copyOf(hashes), encoded,
                    sha256(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new TaskFailure("JAVA_CHANGE_SCAN_FAILED", "Unable to encode production Java snapshot");
        }
    }

    private DiffEvidence diff(Path root, String baseline) {
        ProcessResult tracked;
        ProcessResult untracked;
        if (directBaselines.isDirect(baseline)) {
            DirectWorkspaceBaselineManager.DiffResult result = directBaselines.diff(root, baseline, DIFF_TIMEOUT);
            tracked = result.tracked();
            untracked = result.untracked();
        } else {
            tracked = runner.run(root, List.of("git", "diff", "--find-renames", "--name-status", "-z", baseline),
                    DIFF_TIMEOUT);
            untracked = runner.run(root, List.of("git", "ls-files", "-z", "--others", "--exclude-standard"),
                    DIFF_TIMEOUT);
        }
        requireDiffResult(tracked, "tracked");
        requireDiffResult(untracked, "untracked");
        List<String> paths = new ArrayList<>();
        String[] fields = tracked.output().split("\\x00", -1);
        int index = 0;
        while (index < fields.length && !fields[index].isEmpty()) {
            String status = fields[index++];
            boolean rename = status.charAt(0) == 'R' || status.charAt(0) == 'C';
            if (index >= fields.length) invalidDiff();
            String first = fields[index++];
            if (rename) {
                if (index >= fields.length) invalidDiff();
                String target = fields[index++];
                if (status.charAt(0) != 'D') paths.add(target);
            } else if (status.charAt(0) != 'D') {
                paths.add(first);
            }
        }
        for (String path : untracked.output().split("\\x00", -1)) if (!path.isEmpty()) paths.add(path);
        return new DiffEvidence(paths.stream().distinct().sorted().toList());
    }

    private void requireDiffResult(ProcessResult result, String kind) {
        if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) {
            throw new TaskFailure("JAVA_CHANGE_SCAN_FAILED",
                    "Unable to inspect " + kind + " production Java changes");
        }
    }

    private void invalidDiff() {
        throw new TaskFailure("JAVA_CHANGE_SCAN_FAILED", "Git returned malformed Java change evidence");
    }

    private Path managedFile(Path root, String relative) {
        try {
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root) || Files.isSymbolicLink(candidate)) {
                throw new TaskFailure("JAVA_CHANGE_PATH_INVALID",
                        "Production Java path escaped the task workspace: " + relative);
            }
            return candidate;
        } catch (RuntimeException failure) {
            if (failure instanceof TaskFailure taskFailure) throw taskFailure;
            throw new TaskFailure("JAVA_CHANGE_PATH_INVALID", "Production Java path is invalid");
        }
    }

    static boolean productionJava(String input) {
        if (input == null) return false;
        String path = input.replace('\\', '/').replaceAll("^\\./+", "");
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".java")) return false;
        if (lower.matches("(^|.*/)(target|build)(/.*|$)")) return false;
        return !lower.matches("(^|.*/)src/(test|it|integrationtest|functionaltest|testfixtures)(/.*|$)");
    }

    private String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private record Snapshot(Map<String, String> hashes, String json, String sha256) { }
    private record DiffEvidence(List<String> currentPaths) { }
    public record ChangeSet(List<String> changedPaths, Map<String, String> beforeHashes,
                            Map<String, String> afterHashes, String beforeSha256, String afterSha256) {
        public ChangeSet { changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths); }
        public boolean changed() { return !changedPaths.isEmpty(); }
    }
}
