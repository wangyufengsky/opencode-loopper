package io.opencode.loopper.persistence;

/** Append-only, independently recoverable snapshot of one human/Designer discussion round. */
public record DesignDiscussionRevisionRow(
        String id, String designerSessionId, Integer requirementRevision,
        String scopeKey, String workPackageId, int revision, String state,
        String sourceMessageId, String designMessageId, String snapshotMarkdown,
        String decisionLogJson, boolean questionRequired, boolean questionAnswered,
        int questionRetryCount, String candidateCompilationId,
        String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version) { }
