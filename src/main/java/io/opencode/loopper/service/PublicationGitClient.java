package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded Git inspection/mutation adapter and remote URL policy for Task publication. */
final class PublicationGitClient {
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern SCP_REMOTE = Pattern.compile("^(?:[^@/]+@)?([^:/]+):(.+)$");
    private final SafeProcessRunner runner;
    private final LoopperProperties properties;

    PublicationGitClient(SafeProcessRunner runner, LoopperProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    String required(Path directory, List<String> argv, String code) {
        String output = allowEmpty(directory, argv, code);
        if (output.isBlank()) throw new ConflictException(code, "Git 命令没有返回所需信息");
        return output.strip();
    }

    String allowEmpty(Path directory, List<String> argv, String code) {
        ProcessResult result = runner.run(directory, argv, READ_TIMEOUT);
        if (result.timedOut()) throw new ConflictException(code, "Git 状态检查超时");
        if (result.outputTruncated()) throw new ConflictException(code, "Git 状态输出过大，已停止操作");
        if (result.exitCode() != 0) throw new ConflictException(code, scrub(result.output()));
        return result.output() == null ? "" : result.output().stripTrailing();
    }

    String optional(Path directory, List<String> argv) {
        try {
            ProcessResult result = runner.run(directory, argv, READ_TIMEOUT);
            if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0
                    || result.output() == null || result.output().isBlank()) return null;
            return result.output().strip();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    void run(Path directory, List<String> argv, Duration timeout, String code, String message) {
        ProcessResult result = runner.run(directory, argv, timeout);
        if (result.timedOut()) throw new ConflictException(code, message + "：命令超时");
        if (result.outputTruncated()) throw new ConflictException(code, message + "：命令输出过大");
        if (result.exitCode() != 0) {
            String detail = scrub(result.output());
            throw new ConflictException(code, detail.isBlank() ? message : message + "：" + detail);
        }
    }

    void requireExactRepository(Path workspace) {
        String top = required(workspace, List.of("git", "rev-parse", "--show-toplevel"),
                "GIT_REPOSITORY_UNAVAILABLE");
        try {
            if (!Path.of(top).toRealPath().equals(workspace.toRealPath())) {
                throw new ConflictException("TASK_REPOSITORY_MISMATCH", "任务执行目录不是当前 Git 仓库根目录");
            }
        } catch (ConflictException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ConflictException("TASK_REPOSITORY_UNAVAILABLE", "无法确认任务 Git 仓库边界");
        }
    }

    List<String> targetBranches(Path workspace, String remoteName, String sourceBranch) {
        String output = allowEmpty(workspace,
                List.of("git", "for-each-ref", "--format=%(refname:short)", "refs/remotes/" + remoteName + "/"),
                "GIT_REMOTE_BRANCHES_FAILED");
        Set<String> branches = new LinkedHashSet<>();
        String prefix = remoteName + "/";
        for (String line : output.lines().toList()) {
            String value = line.strip();
            if (!value.startsWith(prefix)) continue;
            value = value.substring(prefix.length());
            if (!value.isBlank() && !value.equals("HEAD") && !value.equals(sourceBranch)) branches.add(value);
        }
        List<String> result = new ArrayList<>(branches);
        result.sort(Comparator.comparingInt(this::branchPriority).thenComparing(String::compareTo));
        return List.copyOf(result);
    }

    String preferredTarget(Path workspace, Path projectRoot, String sourceBranch,
                           String remoteName, List<String> branches) {
        if (branches.isEmpty()) return null;
        if (sourceBranch != null && branches.contains(sourceBranch)) return sourceBranch;
        String projectBranch = optional(projectRoot, List.of("git", "branch", "--show-current"));
        if (projectBranch != null && branches.contains(projectBranch)) return projectBranch;
        String remoteHead = optional(workspace,
                List.of("git", "symbolic-ref", "--short", "refs/remotes/" + remoteName + "/HEAD"));
        if (remoteHead != null && remoteHead.startsWith(remoteName + "/")) {
            String candidate = remoteHead.substring(remoteName.length() + 1);
            if (branches.contains(candidate)) return candidate;
        }
        return branches.getFirst();
    }

    String preferredRemote(Path workspace) {
        String output = allowEmpty(workspace, List.of("git", "remote"), "GIT_REMOTE_UNAVAILABLE");
        List<String> remotes = output.lines().map(String::strip).filter(value -> !value.isBlank()).toList();
        if (remotes.contains("origin")) return "origin";
        if (remotes.isEmpty()) return null;
        if (remotes.size() == 1) return remotes.getFirst();
        throw new ConflictException("GIT_REMOTE_AMBIGUOUS",
                "仓库存在多个 Git remote 且没有 origin，无法确定发布目标");
    }

    String normalizedBranch(String value) {
        String branch = singleLine(value, 250, "MERGE_TARGET_INVALID", "请选择有效的目标分支");
        ProcessResult result = runner.run(Path.of("."),
                List.of("git", "check-ref-format", "--branch", branch), READ_TIMEOUT);
        if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) {
            throw new BadRequestException("MERGE_TARGET_INVALID", "请选择有效的目标分支");
        }
        return branch;
    }

    RemoteRepository remoteRepository(String raw) {
        if (raw == null || raw.isBlank()) return RemoteRepository.unknown();
        String host;
        String path;
        String scheme = null;
        try {
            if (raw.contains("://")) {
                URI uri = URI.create(raw);
                host = uri.getHost();
                path = uri.getPath();
                if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                    scheme = uri.getScheme().toLowerCase(Locale.ROOT);
                }
            } else {
                Matcher matcher = SCP_REMOTE.matcher(raw);
                if (!matcher.matches()) return RemoteRepository.unknown();
                host = matcher.group(1);
                path = matcher.group(2);
            }
        } catch (RuntimeException invalid) {
            return RemoteRepository.unknown();
        }
        if (host == null || host.isBlank() || path == null || path.isBlank()) return RemoteRepository.unknown();
        path = path.replaceFirst("^/+", "").replaceFirst("\\.git/?$", "");
        if (path.isBlank()) return RemoteRepository.unknown();
        String lowerHost = host.toLowerCase(Locale.ROOT);
        String configuredGitLabHost = properties.getPublication().getGitlab().getHost();
        String provider = configuredGitLabHost != null && lowerHost.equalsIgnoreCase(configuredGitLabHost.strip())
                ? "GITLAB" : lowerHost.contains("github") ? "GITHUB"
                : lowerHost.contains("gitlab") ? "GITLAB" : "UNKNOWN";
        if (configuredHttpWebHost(lowerHost)) scheme = "http";
        else if (scheme == null) scheme = "https";
        return new RemoteRepository(provider, scheme + "://" + host + "/" + path, lowerHost, path);
    }

    String query(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    String scrub(String value) {
        if (value == null) return "";
        String scrubbed = value.replaceAll("(?i)(https?://)[^/@\\s]+@", "$1***@").strip();
        return scrubbed.substring(0, Math.min(scrubbed.length(), 1_200));
    }

    String safeMessage(Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? "任务发布状态不可用" : failure.getMessage();
        return scrub(message);
    }

    private int branchPriority(String branch) {
        return switch (branch) {
            case "main" -> 0;
            case "master" -> 1;
            case "develop" -> 2;
            default -> 10;
        };
    }

    private boolean configuredHttpWebHost(String lowerHost) {
        return properties.getPublication().getHttpWebHosts().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .anyMatch(lowerHost::equals);
    }

    private String singleLine(String value, int max, String code, String message) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max
                || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new BadRequestException(code, message);
        }
        return normalized;
    }

    record RemoteRepository(String provider, String webBase, String host, String projectPath) {
        static RemoteRepository unknown() {
            return new RemoteRepository("UNKNOWN", null, null, null);
        }
    }
}
