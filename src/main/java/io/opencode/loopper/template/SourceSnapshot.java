package io.opencode.loopper.template;

/** Planned identity and completed byte persistence are separate crash-recoverable checkpoints. */
public record SourceSnapshot(String manifestSha256, int fileCount, int targetCount, boolean ready) { }
