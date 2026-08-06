package io.opencode.loopper.verification;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.BinaryArtifactRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Moves BROWSER verifier references from deterministic evidence into the
 * relational audit trail.  SQLite keeps only location and integrity metadata;
 * the screenshot and trace themselves remain under the managed data directory.
 */
@Component
public class BinaryArtifactPersistenceService {
    private final LoopperMapper mapper;
    private final ObjectMapper json;

    public BinaryArtifactPersistenceService(LoopperMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public void persistBrowserArtifacts(String taskId, String attemptId, String executionSessionId,
                                        String verificationResultId, VerifierOutcome outcome) {
        if (!"BROWSER".equals(outcome.type())) return;
        Object rawArtifacts = outcome.evidence().get("artifacts");
        if (!(rawArtifacts instanceof List<?> artifacts) || artifacts.isEmpty()) {
            throw new TaskFailure("BROWSER_ARTIFACT_MISSING", "BROWSER verification did not produce screenshot and trace references");
        }
        List<BinaryArtifactStore.ArtifactReference> references = new ArrayList<>();
        for (Object rawArtifact : artifacts) {
            BinaryArtifactStore.ArtifactReference reference = reference(rawArtifact);
            if (!"BROWSER_SCREENSHOT".equals(reference.kind()) && !"BROWSER_TRACE".equals(reference.kind())) {
                throw new TaskFailure("BROWSER_ARTIFACT_INVALID", "BROWSER verification emitted an unsupported artifact kind");
            }
            if (("BROWSER_SCREENSHOT".equals(reference.kind()) && !"image/png".equals(reference.mediaType()))
                    || ("BROWSER_TRACE".equals(reference.kind()) && !"application/zip".equals(reference.mediaType()))) {
                throw new TaskFailure("BROWSER_ARTIFACT_INVALID", "BROWSER artifact media type does not match its kind");
            }
            safeRelativePath(reference.relativePath());
            safeSha256(reference.sha256());
            references.add(reference);
        }
        if (references.size() != 2 || !references.stream().map(BinaryArtifactStore.ArtifactReference::kind)
                .collect(java.util.stream.Collectors.toSet()).equals(Set.of("BROWSER_SCREENSHOT", "BROWSER_TRACE"))) {
            throw new TaskFailure("BROWSER_ARTIFACT_MISSING", "BROWSER verification must produce one screenshot and one trace reference");
        }
        String createdAt = Instant.now().toString();
        List<BinaryArtifactRow> rows = references.stream().map(reference -> new BinaryArtifactRow(UUID.randomUUID().toString(), taskId, attemptId,
                    executionSessionId, verificationResultId, reference.kind(), reference.mediaType(),
                    safeRelativePath(reference.relativePath()), safeSha256(reference.sha256()), reference.sizeBytes(),
                    write(reference.metadata()), createdAt)).toList();
        rows.forEach(mapper::insertBinaryArtifact);
    }

    private BinaryArtifactStore.ArtifactReference reference(Object rawArtifact) {
        if (!(rawArtifact instanceof Map<?, ?> map)) {
            throw new TaskFailure("BROWSER_ARTIFACT_INVALID", "BROWSER artifact evidence must be an object");
        }
        Object metadata = map.get("metadata");
        if (!(metadata instanceof Map<?, ?> rawMetadata)) {
            throw new TaskFailure("BROWSER_ARTIFACT_INVALID", "BROWSER artifact metadata must be an object");
        }
        Map<String, Object> typedMetadata = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMetadata.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new TaskFailure("BROWSER_ARTIFACT_INVALID", "BROWSER artifact metadata keys must be strings");
            }
            typedMetadata.put(key, entry.getValue());
        }
        Object size = map.get("sizeBytes");
        if (!(size instanceof Number number) || number.longValue() < 0) {
            throw new TaskFailure("BROWSER_ARTIFACT_INVALID", "BROWSER artifact size must be a non-negative number");
        }
        return new BinaryArtifactStore.ArtifactReference(requiredString(map, "kind"), requiredString(map, "mediaType"),
                requiredString(map, "relativePath"), requiredString(map, "sha256"), number.longValue(), Map.copyOf(typedMetadata));
    }

    private String safeRelativePath(String value) {
        try {
            Path path = Path.of(value).normalize();
            if (path.isAbsolute() || path.startsWith("..") || path.getNameCount() < 2 || !"artifacts".equals(path.getName(0).toString())) {
                throw new TaskFailure("BROWSER_ARTIFACT_INVALID", "BROWSER artifact path must stay below artifacts/");
            }
            return path.toString();
        } catch (RuntimeException invalid) {
            if (invalid instanceof TaskFailure failure) throw failure;
            throw new TaskFailure("BROWSER_ARTIFACT_INVALID", "BROWSER artifact path is invalid");
        }
    }

    private String safeSha256(String value) {
        if (!value.matches("[0-9a-fA-F]{64}")) {
            throw new TaskFailure("BROWSER_ARTIFACT_INVALID", "BROWSER artifact SHA-256 is invalid");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private String requiredString(Map<?, ?> map, String field) {
        Object value = map.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new TaskFailure("BROWSER_ARTIFACT_INVALID", "BROWSER artifact " + field + " is required");
        }
        return text;
    }

    private String write(Map<String, Object> metadata) {
        try {
            return json.writeValueAsString(metadata);
        } catch (JacksonException failure) {
            throw new TaskFailure("BROWSER_ARTIFACT_INVALID", "BROWSER artifact metadata cannot be serialized");
        }
    }
}
