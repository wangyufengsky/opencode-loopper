package io.opencode.loopper.persistence;

public final class PptGenerationRows {
    private PptGenerationRows() { }
    public record Generation(String id, String documentId, String idempotencyKey, String inputSha, String prompt, String mode, String scopeJson,
            long sourceRevision, long dispatchRevision, String state, String step, int attempt,
            String agentKey, String runId, String jobId, String previewJobId, Long outputRevision, String detail, long version,
            String createdAt, String updatedAt) { }
    public record Request(String documentId, String idempotencyKey, String inputSha, String generationId,
            String kind, String createdAt) { }
}
