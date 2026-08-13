package io.opencode.loopper.persistence;

/** One independent read-only compilation of one immutable Designer revision. */
public record LoopSpecCompilationRow(
        String id, String designerSessionId, int designRevision, String state,
        String externalSessionId, String externalSessionState, int repairCount,
        String sourceDesignMessageId, long sourceDraftVersion,
        String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version) { }
