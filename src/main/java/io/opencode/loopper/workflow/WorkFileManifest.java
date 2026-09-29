package io.opencode.loopper.workflow;

import java.util.List;

/** File identities are independent of export paths. Consumers pin the complete manifest hash. */
public record WorkFileManifest(int version, String projectId, String producerType, String producerId,
                               String sha256, List<Entry> files) {
    public WorkFileManifest { files = List.copyOf(files); }
    public record Entry(String id, String name, String mediaType, String sha256, long sizeBytes) { }
}
