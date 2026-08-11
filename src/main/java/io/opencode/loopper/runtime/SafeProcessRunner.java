package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Runs an argv vector only. Callers never pass shell snippets to this boundary. */
@Component
public class SafeProcessRunner {
    public ProcessResult run(Path directory, List<String> argv, Duration timeout) {
        return run(directory, argv, timeout, Map.of());
    }

    /** Runs with a narrowly supplied environment overlay, for example non-interactive Git network access. */
    public ProcessResult run(Path directory, List<String> argv, Duration timeout, Map<String, String> environment) {
        if (argv == null || argv.isEmpty() || argv.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new TaskFailure("PROCESS_ARGUMENT_INVALID", "Process verifier requires a non-empty argv vector");
        }
        if (environment == null || environment.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new TaskFailure("PROCESS_ENVIRONMENT_INVALID", "Process environment entries must have non-empty names and values");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(argv))
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

    private static void terminateTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (ProcessHandle descendant : descendants.reversed()) {
            if (descendant.isAlive()) descendant.destroyForcibly();
        }
        if (process.isAlive()) process.destroyForcibly();
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
