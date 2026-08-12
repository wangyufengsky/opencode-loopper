package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Runs an argv vector only. Callers never pass shell snippets to this boundary. */
@Component
public class SafeProcessRunner {
    static {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            // Force the JDK's strict quoting/validation path when CreateProcess launches a .cmd/.bat file.
            System.setProperty("jdk.lang.Process.allowAmbiguousCommands", "false");
        }
    }

    private final ExecutableResolver executableResolver;

    public SafeProcessRunner() {
        this(new ExecutableResolver());
    }

    public SafeProcessRunner(ExecutableResolver executableResolver) {
        this.executableResolver = executableResolver;
    }

    public ExecutableResolver.Resolution resolve(Path directory, List<String> argv) {
        validateArguments(argv);
        return executableResolver.resolve(directory, argv);
    }

    public ProcessResult run(Path directory, List<String> argv, Duration timeout) {
        return run(directory, argv, timeout, Map.of());
    }

    /** Runs with a narrowly supplied environment overlay, for example non-interactive Git network access. */
    public ProcessResult run(Path directory, List<String> argv, Duration timeout, Map<String, String> environment) {
        validateArguments(argv);
        if (environment == null || environment.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new TaskFailure("PROCESS_ENVIRONMENT_INVALID", "Process environment entries must have non-empty names and values");
        }
        ExecutableResolver.Resolution resolution = executableResolver.resolve(directory, argv, environment);
        try {
            ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(resolution.argv()))
                    .directory(directory.toFile())
                    .redirectErrorStream(true);
            builder.environment().putAll(environment);
            Process process = builder.start();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            CappedOutputStream capped = new CappedOutputStream(captured, 1_000_000, () -> {
                terminateTree(process);
                closeProcessStreams(process);
            });
            Thread drainer = Thread.ofVirtual().name("loopper-process-drain").start(() -> {
                try (var input = process.getInputStream(); OutputStream output = capped) {
                    input.transferTo(output);
                } catch (IOException ignored) {
                    // The process may have been deliberately cancelled or killed after a timeout.
                }
            });
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateTree(process);
                closeProcessStreams(process);
                process.waitFor(500, TimeUnit.MILLISECONDS);
                drainer.join(1_000);
                if (drainer.isAlive()) {
                    capped.markTruncated();
                    closeProcessStreams(process);
                    drainer.join(1_000);
                }
                return new ProcessResult(-1, captured.toString(StandardCharsets.UTF_8), true, capped.truncated());
            }
            drainer.join(1_000);
            if (drainer.isAlive()) {
                capped.markTruncated();
                closeProcessStreams(process);
                drainer.join(1_000);
            }
            return new ProcessResult(process.exitValue(), captured.toString(StandardCharsets.UTF_8), false, capped.truncated());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskFailure("PROCESS_INTERRUPTED", "Process execution was interrupted");
        } catch (IOException e) {
            throw new TaskFailure("PROCESS_START_FAILED", "Unable to start process: " + e.getMessage());
        }
    }

    /** Starts a bounded, directly-addressable process for a stage-managed verification runtime. */
    public ManagedProcess startManaged(Path directory, List<String> argv, Map<String, String> environment) {
        validateArguments(argv);
        if (environment == null || environment.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new TaskFailure("PROCESS_ENVIRONMENT_INVALID", "Process environment entries must have non-empty names and values");
        }
        ExecutableResolver.Resolution resolution = executableResolver.resolve(directory, argv, environment);
        try {
            ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(resolution.argv()))
                    .directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().putAll(environment);
            Process process = builder.start();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            CappedOutputStream capped = new CappedOutputStream(captured, 1_000_000, () -> {
                terminateTree(process);
                closeProcessStreams(process);
            });
            Thread drainer = Thread.ofVirtual().name("loopper-managed-process-drain").start(() -> {
                try (var input = process.getInputStream(); OutputStream output = capped) {
                    input.transferTo(output);
                } catch (IOException ignored) {
                    // Normal when the managed process is stopped or exceeds its evidence budget.
                }
            });
            return new ManagedProcess(process, List.copyOf(resolution.argv()), captured, capped, drainer);
        } catch (IOException failure) {
            throw new TaskFailure("PROCESS_START_FAILED", "Unable to start process: " + failure.getMessage());
        }
    }

    private static void validateArguments(List<String> argv) {
        if (argv == null || argv.isEmpty() || argv.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new TaskFailure("PROCESS_ARGUMENT_INVALID", "Process verifier requires a non-empty argv vector");
        }
    }

    private static void terminateTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (ProcessHandle descendant : descendants.reversed()) {
            if (descendant.isAlive()) descendant.destroyForcibly();
        }
        if (process.isAlive()) process.destroyForcibly();
    }

    public static final class ManagedProcess {
        private final Process process;
        private final List<String> resolvedArgv;
        private final ByteArrayOutputStream captured;
        private final CappedOutputStream capped;
        private final Thread drainer;

        private ManagedProcess(Process process, List<String> resolvedArgv, ByteArrayOutputStream captured,
                               CappedOutputStream capped, Thread drainer) {
            this.process = process;
            this.resolvedArgv = resolvedArgv;
            this.captured = captured;
            this.capped = capped;
            this.drainer = drainer;
        }

        public long pid() { return process.pid(); }
        public Instant startInstant() { return process.info().startInstant().orElse(null); }
        public List<String> resolvedArgv() { return resolvedArgv; }
        public boolean alive() { return process.isAlive(); }
        public Integer exitCode() { return process.isAlive() ? null : process.exitValue(); }
        public boolean outputTruncated() { return capped.truncated(); }
        public synchronized String output() { return captured.toString(StandardCharsets.UTF_8); }

        public boolean stop(Duration timeout) {
            long millis = Math.max(1, timeout.toMillis());
            try {
                List<ProcessHandle> descendants = process.descendants().toList();
                for (ProcessHandle descendant : descendants.reversed()) if (descendant.isAlive()) descendant.destroy();
                if (process.isAlive()) process.destroy();
                process.waitFor(millis, TimeUnit.MILLISECONDS);
                if (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
                    for (ProcessHandle descendant : descendants.reversed()) if (descendant.isAlive()) descendant.destroyForcibly();
                    if (process.isAlive()) process.destroyForcibly();
                    process.waitFor(Math.min(millis, 2_000), TimeUnit.MILLISECONDS);
                }
                closeProcessStreams(process);
                drainer.join(Math.min(millis, 2_000));
                return !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static void closeProcessStreams(Process process) {
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }

    /** Continue consuming a noisy child after the evidence cap is reached, avoiding pipe backpressure. */
    private static final class CappedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate;
        private final int limit;
        private final Runnable limitExceeded;
        private long written;
        private volatile boolean truncated;
        private boolean limitActionInvoked;
        private CappedOutputStream(ByteArrayOutputStream delegate, int limit, Runnable limitExceeded) {
            this.delegate = delegate;
            this.limit = limit;
            this.limitExceeded = limitExceeded;
        }
        @Override public void write(int value) {
            if (written++ < limit) delegate.write(value);
            else markTruncated();
        }
        @Override public void write(byte[] bytes, int offset, int length) {
            int remaining = (int) Math.max(0, limit - written);
            int accepted = Math.min(remaining, length);
            if (accepted > 0) delegate.write(bytes, offset, accepted);
            if (accepted < length) markTruncated();
            written += length;
        }
        private synchronized void markTruncated() {
            truncated = true;
            if (!limitActionInvoked) {
                limitActionInvoked = true;
                limitExceeded.run();
            }
        }
        private boolean truncated() { return truncated; }
    }
}
