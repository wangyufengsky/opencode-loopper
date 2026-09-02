package io.opencode.loopper.runtime;

import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.domain.SessionFailure;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Private, non-enumerable capabilities for server-selected immutable attachment snapshots. */
public final class OpenCodeAttachmentResources {
    public static final String URI_TEMPLATE = "loopper-attachment://snapshot/{grant}";
    static final String URI_PREFIX = "loopper-attachment://snapshot/";
    private static final Set<String> TEXT_MEDIA = Set.of("text/plain", "text/csv", "application/json", "application/xml");
    private static final Set<String> BLOB_MEDIA = Set.of("application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp");
    private static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;
    private static final long TTL_NANOS = Duration.ofMinutes(15).toNanos();
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private final InternalMcpRuntimeAccess access;
    private final LongSupplier clock;
    private final Map<String, Grant> grants = new HashMap<>();
    private final Map<String, Batch> batches = new HashMap<>();

    public OpenCodeAttachmentResources(InternalMcpRuntimeAccess access) { this(access, System::nanoTime); }
    OpenCodeAttachmentResources(InternalMcpRuntimeAccess access, LongSupplier clock) { this.access = access; this.clock = clock; }

    public synchronized List<Map<String, Object>> prepare(String sessionId, String generation,
            String serverName, List<OpenCodeClient.FilePart> files) {
        var current = access.current().orElse(null);
        if (current == null || !current.generation().equals(generation) || !current.serverName().equals(serverName)) {
            throw failure("ATTACHMENT_MCP_REQUIRED", "Attachments require the current managed MCP runtime");
        }
        prune();
        requireRead(sessionId);
        if (files.isEmpty() || files.size() > 24 || batches.size() >= 1024 && !batches.containsKey(sessionId)) {
            throw failure("ATTACHMENT_MCP_CAPACITY", "Attachment resource capacity exceeded");
        }
        List<Snapshot> snapshots = files.stream().map(OpenCodeAttachmentResources::snapshot).toList();
        long requested = snapshots.stream().mapToLong(Snapshot::size).sum();
        long retained = grants.values().stream().filter(g -> !g.sessionId.equals(sessionId)).mapToLong(g -> g.snapshot.size).sum();
        if (requested + retained > MAX_CACHE_BYTES) {
            throw failure("ATTACHMENT_MCP_CAPACITY", "Attachment resource capacity exceeded; no content was truncated");
        }
        revoke(sessionId);
        List<Map<String, Object>> parts = new ArrayList<>();
        List<CompletableFuture<Void>> receipts = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            var file = files.get(i);
            String uri = URI_PREFIX + UUID.randomUUID();
            var receipt = new CompletableFuture<Void>();
            grants.put(uri, new Grant(sessionId, generation, clock.getAsLong() + TTL_NANOS, snapshots.get(i), receipt));
            receipts.add(receipt);
            parts.add(Map.of("type", "file", "mime", snapshots.get(i).mime, "filename", file.filename(), "url", uri,
                    "source", Map.of("type", "resource", "clientName", serverName, "uri", uri,
                            "text", Map.of("value", file.filename(), "start", 0, "end", file.filename().length()))));
        }
        batches.put(sessionId, new Batch(generation, CompletableFuture.allOf(receipts.toArray(CompletableFuture[]::new)),
                new CompletableFuture<>()));
        return List.copyOf(parts);
    }

    public synchronized McpSchema.ReadResourceResult read(String uri) {
        prune();
        Grant grant = grants.get(uri);
        if (grant == null) throw failure("ATTACHMENT_MCP_RESOURCE_UNAVAILABLE", "Attachment resource unavailable");
        Snapshot snapshot = grant.snapshot;
        McpSchema.ResourceContents content = snapshot.text != null
                ? new McpSchema.TextResourceContents(uri, snapshot.mime, snapshot.text, null)
                : new McpSchema.BlobResourceContents(uri, snapshot.mime, snapshot.blob, null);
        grant.receipt.complete(null);
        return new McpSchema.ReadResourceResult(List.of(content), null);
    }

    /** A read receipt is necessary but not sufficient: verify OpenCode's persisted expanded user message too. */
    void verifyDelivery(String sessionId, BooleanSupplier verifiedMessage) {
        try {
            awaitRead(sessionId, READ_TIMEOUT);
            long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
            while (!verifiedMessage.getAsBoolean()) {
                if (System.nanoTime() >= deadline) throw unread();
                TimeUnit.MILLISECONDS.sleep(100);
            }
            synchronized (this) {
                Batch batch = batches.get(sessionId);
                if (batch == null) throw unread();
                batch.delivered.complete(null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            rejectDelivery(sessionId);
            throw unread();
        } catch (RuntimeException failure) {
            rejectDelivery(sessionId);
            throw failure;
        }
    }

    public void awaitDelivery(String sessionId) {
        Batch batch;
        synchronized (this) { batch = batches.get(sessionId); }
        if (batch != null) await(batch.delivered, READ_TIMEOUT);
    }

    void awaitRead(String sessionId, Duration timeout) {
        Batch batch;
        synchronized (this) { batch = batches.get(sessionId); }
        if (batch != null) await(batch.read, timeout);
    }

    public synchronized void requireRead(String sessionId) {
        Batch batch = batches.get(sessionId);
        if (batch != null && (!batch.read.isDone() || batch.read.isCompletedExceptionally())) throw unread();
    }

    public synchronized void revoke(String sessionId) {
        grants.entrySet().removeIf(entry -> entry.getValue().sessionId.equals(sessionId));
        Batch batch = batches.remove(sessionId);
        if (batch != null) {
            batch.read.completeExceptionally(unread());
            batch.delivered.completeExceptionally(unread());
        }
    }

    private synchronized void rejectDelivery(String sessionId) {
        Batch batch = batches.get(sessionId);
        if (batch != null) batch.delivered.completeExceptionally(unread());
    }

    private void prune() {
        String current = access.current().map(InternalMcpCredentialProvider.Credentials::generation).orElse("");
        long now = clock.getAsLong();
        grants.entrySet().removeIf(entry -> {
            Grant grant = entry.getValue();
            if (grant.expires > now && grant.generation.equals(current)) return false;
            grant.receipt.completeExceptionally(unread());
            return true;
        });
        batches.entrySet().removeIf(entry -> {
            Batch batch = entry.getValue();
            if (batch.generation.equals(current)) return false;
            batch.read.completeExceptionally(unread());
            batch.delivered.completeExceptionally(unread());
            return true;
        });
    }

    private static void await(CompletableFuture<Void> future, Duration timeout) {
        try { future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unread(); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) { throw unread(); }
    }

    private static Snapshot snapshot(OpenCodeClient.FilePart file) {
        boolean text = TEXT_MEDIA.contains(file.mediaType());
        if (!text && !BLOB_MEDIA.contains(file.mediaType())) {
            throw failure("ATTACHMENT_MCP_MEDIA_UNSUPPORTED", "Attachment resource media type is unsupported; Office requires extracted text");
        }
        int limit = text ? 128 * 1024 : 10 * 1024 * 1024;
        try {
            Path path = Path.of(file.managedUri());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            byte[] bytes;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(limit + 1); }
            if (bytes.length > limit) throw failure("ATTACHMENT_MCP_TOO_LARGE",
                    text ? "Attachment text exceeds 128 KiB; no truncation was performed" : "Attachment binary exceeds MCP's 10 MiB limit");
            if (!sha256(bytes).equals(file.sha256())) throw failure("ATTACHMENT_MCP_HASH_MISMATCH", "Attachment SHA-256 does not match its frozen snapshot");
            if (bytes.length == 0) throw failure("ATTACHMENT_MCP_EMPTY", "Attachment resource is empty");
            return text ? new Snapshot("text/plain", StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), null, bytes.length)
                    : new Snapshot(file.mediaType(), null, Base64.getEncoder().encodeToString(bytes), bytes.length);
        } catch (IOException failure) {
            throw failure("ATTACHMENT_MCP_READ_FAILED", "Attachment snapshot cannot be read or is not valid UTF-8 text");
        }
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static SessionFailure unread() { return failure("ATTACHMENT_MCP_NOT_READ", "Required attachment resource has not been read and verified"); }
    private static SessionFailure failure(String code, String detail) { return new SessionFailure(code, code + ": " + detail); }
    private record Snapshot(String mime, String text, String blob, int size) { }
    private record Grant(String sessionId, String generation, long expires, Snapshot snapshot, CompletableFuture<Void> receipt) { }
    private record Batch(String generation, CompletableFuture<Void> read, CompletableFuture<Void> delivered) { }
}
