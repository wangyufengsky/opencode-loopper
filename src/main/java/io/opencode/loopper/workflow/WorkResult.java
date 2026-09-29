package io.opencode.loopper.workflow;

import java.util.Objects;

/** Immutable accepted output. Acceptance is not execution completion or permission to consume it. */
public record WorkResult(Reference reference, String producerRunId, String producerType, String producerId,
                         String kind, long sourceRevision, long submissionRevision,
                         String contentType, String content, String acceptedAt) {
    public WorkResult {
        Objects.requireNonNull(reference);
        Objects.requireNonNull(producerRunId);
        Objects.requireNonNull(producerType);
        Objects.requireNonNull(producerId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(contentType);
        Objects.requireNonNull(content);
        Objects.requireNonNull(acceptedAt);
        if (sourceRevision < 0 || submissionRevision < 1) throw new IllegalArgumentException("Invalid result revision");
    }

    /** A consumer pins both identity and bytes; a later producer run cannot replace this input. */
    public record Reference(String id, String sha256) {
        public Reference {
            if (id == null || id.isBlank() || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid work result reference");
        }
    }

    public Binding bind(String inputName) {
        return new Binding(inputName, producerRunId, producerType, producerId, kind, reference);
    }

    /** A named input has one exact producer and version, independent of future graph edits. */
    public record Binding(String inputName, String producerRunId, String producerType, String producerId,
                          String kind, Reference reference) {
        public Binding {
            if (inputName == null || !inputName.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}"))
                throw new IllegalArgumentException("Invalid work input name");
            Objects.requireNonNull(producerRunId);
            Objects.requireNonNull(producerType);
            Objects.requireNonNull(producerId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(reference);
        }
    }
}
