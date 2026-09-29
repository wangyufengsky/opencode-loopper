package io.opencode.loopper.runtime;

/** Server-frozen commit metadata. A retry uses these exact fields, including the original timestamp. */
public record GitCommitIntent(String tree, String parent, String message, String authorName,
                              String authorEmail, String createdAt) { }
