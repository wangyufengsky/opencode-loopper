package io.opencode.loopper.verification;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stores opaque verifier binaries below LOOPPER_DATA_DIR. Persistence code only
 * receives the returned relative path, hash, size, and metadata; it never needs
 * to put a screenshot or trace blob in SQLite.
 */
@Component
public class BinaryArtifactStore {
    private final Path root;

    @Autowired
    public BinaryArtifactStore(LoopperProperties properties) { this(properties.getDataDir()); }
    BinaryArtifactStore(Path dataDir) { this.root = dataDir.toAbsolutePath().normalize().resolve("artifacts"); }

    public ArtifactReference write(String kind, String mediaType, byte[] content, Map<String, ?> metadata) {
        if (content == null) throw new TaskFailure("ARTIFACT_CONTENT_MISSING", "Verifier artifact content is missing");
        String extension = "image/png".equals(mediaType) ? ".png" : "application/zip".equals(mediaType) ? ".zip" : ".bin";
        String filename = Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + extension;
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) throw new TaskFailure("ARTIFACT_PATH_ESCAPE", "Artifact path escaped its data directory");
        try {
            Files.createDirectories(root);
            Files.write(target, content);
            return reference(kind, mediaType, target, metadata);
        } catch (IOException failure) {
            throw new TaskFailure("ARTIFACT_WRITE_FAILED", "Unable to persist verifier artifact: " + failure.getMessage());
        }
    }

    public Path reserve(String mediaType) {
        String extension = "application/zip".equals(mediaType) ? ".zip" : ".bin";
        String filename = Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + extension;
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) throw new TaskFailure("ARTIFACT_PATH_ESCAPE", "Artifact path escaped its data directory");
        try { Files.createDirectories(root); }
        catch (IOException failure) { throw new TaskFailure("ARTIFACT_WRITE_FAILED", "Unable to create artifact directory: " + failure.getMessage()); }
        return target;
    }

    public ArtifactReference finalizeReserved(String kind, String mediaType, Path target, Map<String, ?> metadata) {
        if (target == null || !target.toAbsolutePath().normalize().startsWith(root)) {
            throw new TaskFailure("ARTIFACT_PATH_ESCAPE", "Artifact path escaped its data directory");
        }
        if (!Files.isRegularFile(target)) throw new TaskFailure("ARTIFACT_MISSING", "Verifier did not create its expected artifact");
        return reference(kind, mediaType, target, metadata);
    }

    private ArtifactReference reference(String kind, String mediaType, Path target, Map<String, ?> metadata) {
        try {
            Path normalized = target.toAbsolutePath().normalize();
            long size = Files.size(normalized);
            String hash = sha256(Files.readAllBytes(normalized));
            Map<String, Object> safeMetadata = new LinkedHashMap<>();
            if (metadata != null) safeMetadata.putAll(metadata);
            return new ArtifactReference(kind, mediaType, Path.of("artifacts").resolve(root.relativize(normalized)).toString(), hash, size, Map.copyOf(safeMetadata));
        } catch (IOException failure) {
            throw new TaskFailure("ARTIFACT_READ_FAILED", "Unable to read verifier artifact: " + failure.getMessage());
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder encoded = new StringBuilder(64);
            for (byte value : digest) encoded.append(String.format("%02x", value));
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record ArtifactReference(String kind, String mediaType, String relativePath, String sha256,
                                    long sizeBytes, Map<String, Object> metadata) {
        public Map<String, Object> evidence() {
            return Map.of("kind", kind, "mediaType", mediaType, "relativePath", relativePath,
                    "sha256", sha256, "sizeBytes", sizeBytes, "metadata", metadata);
        }
    }
}
