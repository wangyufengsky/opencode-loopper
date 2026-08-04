package io.opencode.loopper.runtime;

public record ProcessResult(int exitCode, String output, boolean timedOut) { }
