package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.PackageDesignEvidenceRow;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Reads only bounded explicitly scoped local files, before model dispatch and outside candidate validation/transactions. */
@Component
final class PackageDesignEvidencePreparation {
    static final int MAX_FILES = 16;
    static final int MAX_FILE_BYTES = 4096;
    static final int MAX_TOTAL_BYTES = 16384;
    record FileEvidence(String sourceRef, String path, String status, String sha256, String excerpt) { }
    record Snapshot(String requirementSourceRef, String requirementSha256, List<FileEvidence> files, boolean complete) { }
    private final LoopperMapper mapper;
    private final ObjectMapper json;

    PackageDesignEvidencePreparation(LoopperMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }

    PackageDesignEvidenceRow freeze(String runId, DesignWorkPackageRow owner, Path root) {
        var existing = mapper.findPackageDesignEvidence(runId);
        if (existing.isPresent()) return existing.get();
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Evidence I/O must precede transactions");
        var requirement = mapper.findDesignRequirementRevision(owner.requirementRevisionId())
                .orElseThrow(() -> new ConflictException("CANDIDATE_REQUIREMENT_MISSING", "冻结需求不存在"));
        if (!owner.designerSessionId().equals(requirement.designerSessionId())) throw new ConflictException("CANDIDATE_SOURCE_MISMATCH", "证据来源不属于当前会话");
        var paths = json.readTree(owner.scopeInJson() == null ? "[]" : owner.scopeInJson());
        if (!paths.isArray()) throw new ConflictException("CANDIDATE_PACKAGE_SNAPSHOT_INVALID", "冻结范围不是数组");
        List<String> scope = new ArrayList<>();
        for (var path : paths) scope.add(path.asText());
        Snapshot snapshot = prepare(root, requirement.id(), requirement.requirementText(), scope);
        String encoded = json.writeValueAsString(snapshot);
        var row = new PackageDesignEvidenceRow(runId, PackageDesignGapAssessment.VERSION, snapshot.requirementSha256(),
                encoded, hash(encoded.getBytes(StandardCharsets.UTF_8)), Instant.now().toString());
        mapper.insertPackageDesignEvidence(row);
        return mapper.findPackageDesignEvidence(runId).orElseThrow(() -> new ConflictException("PACKAGE_EVIDENCE_NOT_FROZEN", "证据未能冻结"));
    }

    static Snapshot prepare(Path root, String requirementId, String requirement, List<String> scope) {
        List<FileEvidence> files = new ArrayList<>();
        boolean complete = scope.size() <= MAX_FILES;
        int used = 0;
        for (String item : scope.stream().limit(MAX_FILES).toList()) {
            FileEvidence fact = used >= MAX_TOTAL_BYTES ? unknown("repository:" + files.size(), safePath(item), "OVER_TOTAL_LIMIT")
                    : read(root, item, files.size());
            used += fact.excerpt() == null ? 0 : fact.excerpt().getBytes(StandardCharsets.UTF_8).length;
            if (used > MAX_TOTAL_BYTES) fact = unknown(fact.sourceRef(), fact.path(), "OVER_TOTAL_LIMIT");
            files.add(fact);
            complete &= "READ".equals(fact.status());
        }
        return new Snapshot("requirement:" + requirementId, hash(requirement.getBytes(StandardCharsets.UTF_8)), List.copyOf(files), complete);
    }

    private static FileEvidence read(Path root, String relative, int index) {
        String ref = "repository:" + index;
        if (!boundedPath(relative) || relative.contains("*")) return unknown(ref, safePath(relative), "OUTSIDE_BOUNDED_SCOPE");
        try {
            Path base = root.toRealPath();
            Path target = base.resolve(relative).normalize();
            if (Path.of(relative).isAbsolute() || !target.startsWith(base)) return unknown(ref, relative, "OUTSIDE_BOUNDED_SCOPE");
            // Refuse symlinks at every component; a missing/read-denied path proves no absence of a capability.
            Path cursor = base;
            for (Path part : base.relativize(target)) {
                String name = part.toString().toLowerCase(java.util.Locale.ROOT);
                if (name.equals(".git") || (name.equals(".env") || name.startsWith(".env.")) && !name.equals(".env.example")) {
                    return unknown(ref, relative, "READ_PERMISSION_DENIED");
                }
                cursor = cursor.resolve(part);
                if (Files.isSymbolicLink(cursor)) return unknown(ref, relative, "SYMLINK_NOT_READ");
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return unknown(ref, relative, "UNCONFIRMED");
            byte[] bytes;
            try (var stream = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(MAX_FILE_BYTES + 1); }
            if (bytes.length > MAX_FILE_BYTES) return unknown(ref, relative, "OVER_BYTE_LIMIT");
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return new FileEvidence(ref, relative, "READ", hash(bytes), text);
        } catch (IOException | java.nio.file.InvalidPathException failure) { return unknown(ref, relative, "UNCONFIRMED"); }
    }

    private static FileEvidence unknown(String ref, String path, String status) { return new FileEvidence(ref, path, status, null, null); }
    private static boolean boundedPath(String value) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length <= 512
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static String safePath(String value) { return boundedPath(value) ? value : "[scope entry omitted]"; }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
