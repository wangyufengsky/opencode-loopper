package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Source Git owns ignore and tracked-file semantics; private Git owns snapshot objects and indexes. */
final class WorkspaceSnapshotFiles {
    private final SafeProcessRunner runner;

    WorkspaceSnapshotFiles(SafeProcessRunner runner) { this.runner = runner; }

    void capture(Path root, Path gitDir, Map<String, String> environment, Path dataDirectory,
                 Duration timeout, String failureCode) throws IOException {
        List<String> source = sourceFiles(root, dataDirectory, timeout, failureCode);
        if (source == null) {
            requireSuccess(runner.run(root, privateGit(root, gitDir, pathArguments("add", dataDirectory, root)),
                    timeout, environment), failureCode);
            return;
        }
        // Preserve tracked files even when they match an ignore rule. Do not alter the source index.
        List<String> present = source.stream().filter(path -> Files.exists(root.resolve(path), LinkOption.NOFOLLOW_LINKS)).toList();
        requireSuccess(runner.run(root, privateGit(root, gitDir, List.of("read-tree", "--empty")),
                timeout, environment), failureCode);
        if (present.isEmpty()) return;
        Path pathspec = Files.createTempFile(gitDir, "snapshot-paths-", ".nul");
        try {
            Files.writeString(pathspec, String.join("\0", present) + "\0");
            requireSuccess(runner.run(root, privateGit(root, gitDir, List.of("--literal-pathspecs", "add", "-f",
                    "--pathspec-from-file=" + pathspec, "--pathspec-file-nul")), timeout, environment), failureCode);
        } finally { Files.deleteIfExists(pathspec); }
    }

    ProcessResult untracked(Path root, Path gitDir, Map<String, String> environment, Path dataDirectory,
                            Duration timeout, String failureCode) {
        List<String> source = sourceFiles(root, dataDirectory, timeout, failureCode);
        if (source == null) return runner.run(root, privateGit(root, gitDir,
                pathArguments("ls-files", dataDirectory, root)), timeout, environment);
        ProcessResult tracked = runner.run(root, privateGit(root, gitDir, List.of("ls-files", "--cached", "-z")),
                timeout, environment);
        requireSuccess(tracked, failureCode);
        var paths = new LinkedHashSet<>(source);
        paths.removeAll(nulPaths(tracked.output(), failureCode));
        paths.removeIf(path -> !Files.exists(root.resolve(path), LinkOption.NOFOLLOW_LINKS));
        return new ProcessResult(0, paths.isEmpty() ? "" : String.join("\0", paths) + "\0", false);
    }

    private List<String> sourceFiles(Path root, Path dataDirectory, Duration timeout, String failureCode) {
        ProcessResult repository = runner.run(root, List.of("git", "rev-parse", "--is-inside-work-tree"), timeout);
        if (repository.timedOut() || repository.outputTruncated()) requireSuccess(repository, failureCode);
        if (repository.exitCode() != 0 || !"true".equals(repository.output().trim())) return null;
        var command = new ArrayList<>(List.of("git", "-c", "core.safecrlf=false", "ls-files",
                "--cached", "--others", "--exclude-standard", "-z", "--"));
        command.addAll(pathspecs(root, dataDirectory));
        ProcessResult result = runner.run(root, command, timeout);
        requireSuccess(result, failureCode);
        return nulPaths(result.output(), failureCode);
    }

    private List<String> nulPaths(String output, String failureCode) {
        if (!output.isEmpty() && !output.endsWith("\0")) {
            throw new TaskFailure(failureCode, "Workspace file list was not NUL terminated");
        }
        var paths = new LinkedHashSet<String>();
        for (String path : output.split("\\x00", -1)) {
            if (path.isEmpty()) continue;
            if (Path.of(path).isAbsolute() || Arrays.asList(path.split("/")).contains("..")) {
                throw new TaskFailure(failureCode, "Workspace file list escaped the project directory");
            }
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    private List<String> pathArguments(String operation, Path dataDirectory, Path root) {
        var args = new ArrayList<>(operation.equals("add") ? List.of("add", "-A", "--")
                : List.of("ls-files", "-z", "--others", "--exclude-standard", "--"));
        args.addAll(pathspecs(root, dataDirectory));
        return args;
    }

    private List<String> pathspecs(Path root, Path dataDirectory) {
        var paths = new ArrayList<>(List.of(".", ":(exclude).git", ":(exclude).git/**"));
        if (dataDirectory.startsWith(root)) {
            String relative = root.relativize(dataDirectory).toString().replace('\\', '/');
            paths.add(":(exclude,literal)" + relative);
            paths.add(":(exclude,glob)" + escapeGlob(relative) + "/**");
        }
        return paths;
    }

    private String escapeGlob(String path) {
        return path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\[");
    }

    private List<String> privateGit(Path root, Path gitDir, List<String> args) {
        var command = new ArrayList<>(List.of("git", "-c", "core.safecrlf=false",
                "--git-dir=" + gitDir, "--work-tree=" + root));
        command.addAll(args);
        return command;
    }

    private void requireSuccess(ProcessResult result, String code) {
        if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) {
            String output = result.output();
            throw new TaskFailure(code, "Unable to enumerate or index workspace files: "
                    + output.substring(0, Math.min(output.length(), 2_000)));
        }
    }
}
