package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Git 2.30 recursive merge in disposable task-owned files; returns the synthetic pre-resolution tree. */
final class TemplateGitMergeBaseline {
    private final GitEvidenceProcess git;

    TemplateGitMergeBaseline(GitEvidenceProcess git) { this.git = git; }

    String reconstruct(Path repository, List<String> parents) {
        Path scratch = null;
        boolean stopped = true;
        try {
            scratch = Files.createTempDirectory(repository.getParent(), "merge-baseline-").toRealPath();
            Path home = Files.createDirectory(scratch.resolve("config"));
            Path worktree = Files.createDirectory(scratch.resolve("worktree"));
            // Git 2.30 predates GIT_CONFIG_GLOBAL. Give only these isolated subprocesses an empty config home.
            Map<String, String> environment = Map.of("HOME", home.toString(), "XDG_CONFIG_HOME", home.toString(),
                    "GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", home.resolve("absent").toString(),
                    "GIT_DIR", repository.toString(), "GIT_WORK_TREE", worktree.toString(),
                    "GIT_INDEX_FILE", scratch.resolve("index").toString());
            read(repository, environment, List.of("read-tree", "--reset", "-u", "--no-recurse-submodules", parents.getFirst()));
            var bases = run(repository, environment, List.of("merge-base", "--all", parents.get(0), parents.get(1)));
            if (bases.exitCode() != 1) bases.requireSuccess(List.of("merge-base"));
            List<String> merge = new ArrayList<>(List.of("merge-recursive"));
            if (bases.exitCode() == 0) merge.addAll(bases.output().lines().filter(value -> !value.isBlank()).toList());
            merge.addAll(List.of("--", parents.get(0), parents.get(1)));
            var result = run(repository, environment, merge);
            if (result.exitCode() != 0 && result.exitCode() != 1) result.requireSuccess(merge);
            if (result.exitCode() == 1) stageConflicts(repository, environment);
            return read(repository, environment, List.of("write-tree")).strip();
        } catch (TaskFailure failure) {
            stopped = !failure.code().equals("TEMPLATE_GIT_STOP_UNCONFIRMED");
            throw failure;
        } catch (IOException failure) {
            throw new TaskFailure("TEMPLATE_MERGE_BASELINE_IO", "无法准备合并证据的临时目录，请检查任务目录与磁盘空间");
        } finally {
            // An unconfirmed process may still own these files. Its capture guard keeps the task blocked.
            if (stopped && scratch != null) removeOwnedScratch(scratch);
        }
    }

    private void stageConflicts(Path repository, Map<String, String> environment) {
        var paths = new LinkedHashSet<String>();
        for (String entry : read(repository, environment, List.of("ls-files", "--unmerged", "-z")).split("\u0000")) {
            int tab = entry.indexOf('\t');
            if (tab >= 0) paths.add(entry.substring(tab + 1));
        }
        if (paths.isEmpty()) throw new TaskFailure("TEMPLATE_MERGE_BASELINE_INVALID", "Git 合并未完成且没有可验证的冲突证据，未生成完整报告");
        // Rename/delete conflicts can also materialize auxiliary files; retain them in the synthetic tree.
        for (String path : read(repository, environment, List.of("ls-files", "--others", "-z")).split("\u0000")) {
            if (!path.isEmpty()) paths.add(path);
        }
        for (String path : paths) read(repository, environment, List.of("add", "-f", "--", path));
    }

    private String read(Path repository, Map<String, String> environment, List<String> arguments) {
        var result = run(repository, environment, arguments);
        result.requireSuccess(arguments);
        return result.output();
    }

    private GitEvidenceProcess.Result run(Path repository, Map<String, String> environment, List<String> arguments) {
        List<String> command = new ArrayList<>(List.of("-c", "core.bare=false", "-c", "core.symlinks=false",
                "-c", "core.autocrlf=false", "-c", "core.fsmonitor=false", "-c", "submodule.recurse=false",
                "-c", "merge.renormalize=false", "-c", "merge.conflictStyle=merge", "-c", "rerere.enabled=false"));
        command.addAll(arguments);
        return git.run(repository, Duration.ofSeconds(60), command, environment);
    }

    private void removeOwnedScratch(Path scratch) {
        try (var paths = Files.walk(scratch)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        } catch (IOException failure) {
            throw new TaskFailure("TEMPLATE_MERGE_CLEANUP_FAILED", "合并证据已停止，但临时文件清理失败，请检查任务目录权限");
        }
    }
}
