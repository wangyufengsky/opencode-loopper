package io.opencode.loopper.persistence;

/** Persisted PPT identities. Filesystem storage keys never belong in public projections. */
public final class PptRows {
    private PptRows() { }
    public record Document(String id, String title, String projectId, String model, String phase,
            long revision, long version, boolean archived, String createDigest, String createdAt, String updatedAt) { }
    public record Revision(String documentId, long revision, String deckJson, String planJson, String reason, String createdAt) { }
    public record Receipt(String documentId, String requestKey, String digest, String resultJson, String createdAt) { }
    public record Resource(String id, String documentId, String kind, String name, String mediaType,
            String storageKey, String sha256, long bytes, String state, String detail, String bodyJson,
            String requestKey, String digest, String createdAt) { }
    public record Job(String id, String documentId, String kind, long revision, String slideId,
            String state, int completed, int total, String detail, String requestKey, String digest,
            String createdAt, String updatedAt, long version) { }
    public record Artifact(String id, String documentId, String jobId, long revision, String slideId,
            String name, String mediaType, String storageKey, String sha256, long bytes, String createdAt) { }
}
