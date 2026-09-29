package io.opencode.loopper.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Server-owned, content-addressed bytes. Business adapters persist identities before calling write. */
public final class ImmutableContentStore {
    private final Path base;
    public ImmutableContentStore(Path configured) {
        Path absolute = configured.toAbsolutePath().normalize();
        Path ancestor = absolute;
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
        try {
            if (ancestor == null) throw invalid();
            // Bind /tmp and /var aliases once; do not follow subsequently substituted child links.
            base = ancestor.toRealPath().resolve(ancestor.relativize(absolute));
        } catch (IOException failure) { throw invalid(); }
    }
    public Path directory(String owner) {
        outsideTransaction();
        Path directory = bucket(owner);
        try { safe(directory); Files.createDirectories(directory); safe(directory); return directory.toRealPath(); }
        catch (IOException failure) { throw invalid(); }
    }
    /** Resolves a server-owned location without creating it, so an intent can be committed first. */
    public Path location(String owner) { outsideTransaction(); return bucket(owner); }
    /** Only absence permits copying again. A present damaged object remains an explicit failure. */
    public java.util.Optional<byte[]> find(String owner, String sha256, long expectedBytes, int maximumBytes) {
        outsideTransaction();
        requireSize(expectedBytes, maximumBytes);
        if (!Files.exists(object(owner, sha256), LinkOption.NOFOLLOW_LINKS)) return java.util.Optional.empty();
        return java.util.Optional.of(read(owner, sha256, expectedBytes, maximumBytes));
    }
    /** Never overwrite an existing object, including a damaged object. Retry verifies the same bytes. */
    public void write(String owner, String sha256, byte[] bytes, int maximumBytes) {
        outsideTransaction();
        if (bytes == null || maximumBytes < 0 || maximumBytes == Integer.MAX_VALUE
                || bytes.length > maximumBytes || !hash(bytes).equals(sha256)) throw invalid();
        Path file = object(owner, sha256);
        try {
            Path objects = directory(owner).resolve("objects");
            safe(objects); Files.createDirectories(objects); safe(objects);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                Path temporary = Files.createTempFile(objects, "content-", ".pending");
                try {
                    Files.write(temporary, bytes, LinkOption.NOFOLLOW_LINKS);
                    safe(file);
                    try { Files.createLink(file, temporary); }
                    catch (FileAlreadyExistsException raced) { /* Verify the winning object below. */ }
                    catch (UnsupportedOperationException | FileSystemException unsupported) {
                        // A plain no-replace move is intentional: ATOMIC_MOVE may replace a target.
                        try { Files.move(temporary, file); }
                        catch (FileAlreadyExistsException raced) { /* Verify below. */ }
                    }
                } finally { Files.deleteIfExists(temporary); }
            }
            read(owner, sha256, bytes.length, maximumBytes);
        } catch (IOException failure) { throw invalid(); }
    }
    public byte[] read(String owner, String sha256, long expectedBytes, int maximumBytes) {
        outsideTransaction();
        requireSize(expectedBytes, maximumBytes);
        Path file = object(owner, sha256);
        try {
            safe(file);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw invalid();
            long size = Files.size(file);
            if (size > maximumBytes || expectedBytes >= 0 && size != expectedBytes) throw invalid();
            byte[] bytes;
            try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(maximumBytes + 1);
            }
            if (bytes.length > maximumBytes || expectedBytes >= 0 && bytes.length != expectedBytes
                    || !hash(bytes).equals(sha256)) throw invalid();
            return bytes;
        } catch (IOException failure) { throw invalid(); }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Path bucket(String owner) {
        if (owner == null || !owner.matches("[a-zA-Z0-9-]{1,80}")) throw invalid();
        Path directory = base.resolve(owner); safe(directory); return directory;
    }
    private Path object(String owner, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw invalid();
        Path file = bucket(owner).resolve("objects").resolve(hash); safe(file); return file;
    }
    private static void safe(Path path) {
        for (Path part = path; part != null; part = part.getParent()) if (Files.isSymbolicLink(part)) throw invalid();
    }
    private static void requireSize(long expectedBytes, int maximumBytes) {
        if (maximumBytes < 0 || maximumBytes == Integer.MAX_VALUE || expectedBytes < -1 || expectedBytes > maximumBytes) throw invalid();
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Immutable content I/O cannot run inside a database transaction");
    }
    private static StorageFailure invalid() { return new StorageFailure(); }
    public static final class StorageFailure extends RuntimeException {
        private StorageFailure() { super("Immutable content is missing, changed, too large or outside its storage boundary"); }
    }
}
