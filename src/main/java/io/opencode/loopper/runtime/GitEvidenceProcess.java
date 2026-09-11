package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Bounded Git data transport. stderr can never contaminate NUL-separated evidence. */
@Component
public final class GitEvidenceProcess {
    private static final int OUTPUT_LIMIT = 4_000_000;
    private static final Map<String, String> ENVIRONMENT = Map.of(
            "GIT_TERMINAL_PROMPT", "0", "GIT_OPTIONAL_LOCKS", "0", "LC_ALL", "C", "GIT_NO_REPLACE_OBJECTS", "1");
    private final SafeProcessRunner runner;

    public GitEvidenceProcess(SafeProcessRunner runner) { this.runner = runner; }

    public String read(Path directory, String... arguments) {
        Result result = run(directory, Duration.ofSeconds(60), List.of(arguments));
        if (result.exitCode() != 0) throw new TaskFailure("TEMPLATE_GIT_FAILED", "Git 证据读取失败，请检查分支、仓库和访问权限");
        return result.output();
    }

    public Result run(Path directory, Duration timeout, List<String> arguments) {
        List<String> argv = new ArrayList<>(List.of("git", "-c", "core.safecrlf=false", "-c", "color.ui=false",
                "-c", "core.quotePath=false", "-c", "core.hooksPath=" + nullDevice(),
                "-c", "protocol.ext.allow=never", "-c", "mailmap.file=" + nullDevice(), "-c", "mailmap.blob="));
        argv.addAll(arguments);
        var resolution = runner.resolve(directory, argv);
        ProcessScope scope = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(resolution.argv()).directory(directory.toFile());
            builder.environment().keySet().removeIf(key -> key.startsWith("GIT_") && !key.equals("GIT_SSH_COMMAND"));
            builder.environment().putAll(ENVIRONMENT);
            scope = new ProcessScope(builder.start());
            return collect(scope, timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TaskFailure("TEMPLATE_GIT_INTERRUPTED", "Git 证据读取已中断");
        } catch (IOException failure) {
            throw new TaskFailure("TEMPLATE_GIT_UNAVAILABLE", "无法运行 Git，请检查本机 Git 安装和仓库权限");
        } finally {
            if (scope != null && !scope.stopConfirmed()) throw new TaskFailure("TEMPLATE_GIT_STOP_UNCONFIRMED", "无法确认 Git 进程已停止，执行保持阻断");
        }
    }

    private Result collect(ProcessScope scope, Duration timeout) throws InterruptedException, IOException {
        Process process = scope.process;
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var exceeded = new AtomicBoolean();
        Thread stdout = drain(process.getInputStream(), output, exceeded, scope);
        Thread stderr = drain(process.getErrorStream(), errors, exceeded, scope);
        process.getOutputStream().close();
        boolean done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!done) scope.stop();
        stdout.join(2000);
        stderr.join(2000);
        if (!done) throw new TaskFailure("TEMPLATE_GIT_TIMEOUT", "Git 操作超时，请检查仓库连接后重试");
        if (exceeded.get() || stdout.isAlive() || stderr.isAlive()) {
            throw new TaskFailure("TEMPLATE_GIT_EVIDENCE_LIMIT", "单次 Git 证据超过读取上限，未生成完整报告");
        }
        // Diagnostics are deliberately not returned: Git errors may contain credential-bearing URLs.
        return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private static Thread drain(InputStream input, ByteArrayOutputStream output, AtomicBoolean exceeded, ProcessScope scope) {
        return Thread.ofVirtual().name("template-git-drain").start(() -> {
            try (input) {
                byte[] bytes = new byte[8192];
                int read;
                while ((read = input.read(bytes)) != -1) {
                    if (output.size() + read > OUTPUT_LIMIT) {
                        exceeded.set(true);
                        scope.stop();
                        break;
                    }
                    output.write(bytes, 0, read);
                }
            } catch (IOException failure) { exceeded.set(true); }
        });
    }

    static final class ProcessScope {
        final Process process;
        // Keep handles observed by timeout/output-cap kills even after the parent exits and children reparent.
        final java.util.Set<ProcessHandle> children = java.util.concurrent.ConcurrentHashMap.newKeySet();
        ProcessScope(Process process) { this.process = process; }
        void stop() {
            process.descendants().forEach(children::add);
            children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
        }
        boolean stopConfirmed() {
            stop();
            boolean interrupted = Thread.interrupted();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) && System.nanoTime() < deadline) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) { interrupted = true; }
                }
                return !process.isAlive() && children.stream().noneMatch(ProcessHandle::isAlive);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static String nullDevice() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "NUL" : "/dev/null";
    }

    public record Result(int exitCode, String output) { }
}
