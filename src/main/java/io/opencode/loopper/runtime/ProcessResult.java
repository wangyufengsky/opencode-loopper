package io.opencode.loopper.runtime;

public record ProcessResult(int exitCode, String output, boolean timedOut, boolean outputTruncated) {
    public ProcessResult(int exitCode, String output, boolean timedOut) {
        this(exitCode, output, timedOut, false);
    }
}
