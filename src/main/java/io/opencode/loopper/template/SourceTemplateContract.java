package io.opencode.loopper.template;

/** Frozen at confirmation; defaults may change only for later runs. */
public record SourceTemplateContract(String version, String model, boolean timeoutEnabled,
        long maxDurationSeconds, long attemptTimeoutSeconds, int maxTaskAttempts, int maxStageAttempts,
        int sessionErrorLimit, int analysisConcurrency) { }
