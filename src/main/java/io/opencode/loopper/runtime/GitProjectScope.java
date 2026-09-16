package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Separates a registered project's paths from the checkout-wide Git branch and index. */
public record GitProjectScope(Path project, Path repository, String prefix) {
    public static GitProjectScope require(SafeProcessRunner runner, Path input) {
        return require(new GitEvidenceProcess(runner), input);
    }

    public static GitProjectScope require(GitEvidenceProcess git, Path input) {
        try {
            Path project = input.toRealPath();
            var result = git.run(project, Duration.ofSeconds(3), List.of("rev-parse", "--show-toplevel"));
            result.requireSuccess(List.of("rev-parse"));
            Path repository = Path.of(result.output().strip()).toRealPath();
            if (!project.startsWith(repository) || !repository.equals(checkoutRoot(project))) {
                throw new TaskFailure("GIT_PROJECT_SCOPE_MISMATCH", "项目目录与所属 Git 仓库不匹配，请检查目录和 Git 配置");
            }
            String relative = repository.relativize(project).toString().replace('\\', '/');
            return new GitProjectScope(project, repository, relative.isEmpty() ? "" : relative + "/");
        } catch (TaskFailure failure) { throw failure; }
        catch (Exception failure) {
            throw new TaskFailure("GIT_PROJECT_SCOPE_UNAVAILABLE", "无法确认项目所属的 Git 仓库，请检查目录和访问权限");
        }
    }

    /** Filesystem-only lease key discovery; safe before short persistence transactions. */
    public static Path checkoutRoot(Path input) throws java.io.IOException {
        Path project = input.toRealPath();
        for (Path current = project; current != null; current = current.getParent()) {
            if (Files.exists(current.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return current;
        }
        return project;
    }

    public static void requireNoSiblingChanges(SafeProcessRunner runner, Path input, String baseline) {
        try {
            if (!checkoutRoot(input).equals(input.toRealPath())) {
                require(runner, input).requireContainedChanges(runner, baseline, null);
            }
        } catch (TaskFailure failure) { throw failure; }
        catch (java.io.IOException failure) {
            throw new TaskFailure("GIT_PROJECT_SCOPE_UNAVAILABLE", "无法确认项目目录的 Git 边界");
        }
    }

    public boolean nested() { return !prefix.isEmpty(); }

    public String projectPath(String repositoryPath) {
        if (!repositoryPath.startsWith(prefix) || repositoryPath.equals(prefix)
                || Path.of(repositoryPath).isAbsolute()
                || java.util.Arrays.asList(repositoryPath.split("/")).contains("..")) {
            throw new TaskFailure("GIT_PROJECT_OUTSIDE_CHANGES",
                    "同一 Git 仓库的项目目录外存在变更，请先在对应模块处理；本任务不会提交、暂存或恢复这些文件");
        }
        return repositoryPath.substring(prefix.length());
    }

    /** Rejects sibling changes before branch/checkpoint/publication operations can touch them. */
    public void requireContainedChanges(SafeProcessRunner runner, String baseline, String target) {
        if (!nested()) return;
        var git = new GitEvidenceProcess(runner);
        var args = new java.util.ArrayList<>(List.of("diff", "--no-renames", "--name-only", "-z", baseline));
        if (target != null) args.add(target);
        args.add("--");
        requirePaths(git.read(repository, args.toArray(String[]::new)));
        if (target == null) {
            requirePaths(git.read(repository, "diff", "--cached", "--no-renames", "--name-only", "-z", "HEAD", "--"));
            requirePaths(git.read(repository, "ls-files", "--others", "--exclude-standard", "-z"));
        }
    }

    private void requirePaths(String output) {
        for (String path : output.split("\u0000", -1)) if (!path.isEmpty()) projectPath(path);
    }

    public String projectTree(SafeProcessRunner runner, String tree) {
        return nested() ? new GitEvidenceProcess(runner).read(repository, "rev-parse", "--verify",
                tree + ":" + prefix.substring(0, prefix.length() - 1)).strip() : tree;
    }
}
