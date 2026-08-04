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
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Runs an argv vector only. Callers never pass shell snippets to this boundary. */
@Component
public class SafeProcessRunner {
    public ProcessResult run(Path directory, List<String> argv, Duration timeout) {
        if (argv == null || argv.isEmpty() || argv.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new TaskFailure("PROCESS_ARGUMENT_INVALID", "Process verifier requires a non-empty argv vector");
        }
        try {
            Process process = new ProcessBuilder(new ArrayList<>(argv))
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread drainer = Thread.ofVirtual().name("loopper-process-drain").start(() -> {
                try (var input = process.getInputStream(); OutputStream output = new CappedOutputStream(captured, 1_000_000)) {
                    input.transferTo(output);
                } catch (IOException ignored) {
                    // The process may have been deliberately cancelled or killed after a timeout.
                }
            });
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                drainer.join(1_000);
                return new ProcessResult(-1, captured.toString(StandardCharsets.UTF_8), true);
            }
            drainer.join(1_000);
            return new ProcessResult(process.exitValue(), captured.toString(StandardCharsets.UTF_8), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskFailure("PROCESS_INTERRUPTED", "Process execution was interrupted");
        } catch (IOException e) {
            throw new TaskFailure("PROCESS_START_FAILED", "Unable to start process: " + e.getMessage());
        }
    }

    /** Continue consuming a noisy child after the evidence cap is reached, avoiding pipe backpressure. */
    private static final class CappedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate;
        private final int limit;
        private int written;
        private CappedOutputStream(ByteArrayOutputStream delegate, int limit) { this.delegate = delegate; this.limit = limit; }
        @Override public void write(int value) { if (written++ < limit) delegate.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) {
            int remaining = Math.max(0, limit - written);
            int accepted = Math.min(remaining, length);
            if (accepted > 0) delegate.write(bytes, offset, accepted);
            written += length;
        }
    }
}
