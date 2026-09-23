package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Content-addressed immutable bytes; DB records the expected identities before these external writes. */
@Component
public final class SourceSnapshotStorage {
    private final Path base;
    public SourceSnapshotStorage(LoopperProperties properties) {
        Path configured = properties.getDataDir().toAbsolutePath().normalize().resolve("source-templates");
        // Resolve the configured storage ancestor once (including macOS /tmp and /var aliases).
        // After binding, every child is checked against this canonical root and cannot be a symlink.
        Path ancestor = configured;
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
        try {
            if (ancestor == null) throw invalid();
            base = ancestor.toRealPath().resolve(ancestor.relativize(configured));
        } catch (IOException unavailable) { throw invalid(); }
    }
    public Path directory(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,80}")) throw invalid();
        Path target = base.resolve(id);
        safe(target);
        try { Files.createDirectories(target); safe(target); return target.toRealPath(); }
        catch (IOException failure) { throw invalid(); }
    }
    public void write(String id, Map<String, byte[]> contents) {
        Path objects = directory(id).resolve("objects");
        try {
            safe(objects); Files.createDirectories(objects);
            for (var entry : contents.entrySet()) {
                String hash = entry.getKey(); byte[] bytes = entry.getValue();
                if (!SourceTreeCapture.hash(bytes).equals(hash)) throw invalid();
                Path file = object(id, hash);
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { read(id, hash); continue; }
                Path temporary = Files.createTempFile(objects, "capture-", ".tmp");
                try {
                    Files.write(temporary, bytes); safe(file);
                    try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file); }
                } finally { Files.deleteIfExists(temporary); }
                read(id, hash);
            }
        } catch (IOException failure) { throw invalid(); }
    }
    public String read(String id, String hash) {
        Path file = object(id, hash);
        try {
            safe(file);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > SourceTreeCapture.MAX_FILE_BYTES) throw invalid();
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(SourceTreeCapture.MAX_FILE_BYTES + 1);
            }
            if (!SourceTreeCapture.hash(bytes).equals(hash)) throw invalid();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException failure) { throw invalid(); }
    }
    private Path object(String id, String hash) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,80}") || hash == null || !hash.matches("[a-f0-9]{64}")) throw invalid();
        Path path = base.resolve(id).resolve("objects").resolve(hash);
        safe(path); return path;
    }
    private static void safe(Path target) {
        for (Path part = target; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw invalid();
    }
    private static ConflictException invalid() {
        return new ConflictException("SOURCE_SNAPSHOT_STORAGE_INVALID", "冻结源码存储不可用或校验失败，保留原记录，请检查运行目录");
    }
}
