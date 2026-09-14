package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.service.assist.AssistDocumentParser;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Bounded parsing and immutable original bytes. All methods run outside database transactions. */
@Component
public final class DocumentTemplateStorage {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final long MAX_BATCH_BYTES = 50L * 1024 * 1024;
    private final Path root;
    private volatile Path canonicalRoot;
    private final AssistDocumentParser parser;
    private final ObjectMapper json;
    private final ExecutorService parsers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), Thread.ofPlatform().daemon().name("requirement-document-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public DocumentTemplateStorage(LoopperProperties properties, AssistDocumentParser parser, ObjectMapper json) {
        this.root = properties.getDataDir().toAbsolutePath().normalize().resolve("document-templates");
        this.parser = parser; this.json = json;
    }

    public List<Prepared> prepare(List<Incoming> files) {
        if (files == null || files.isEmpty() || files.size() > 10)
            throw bad("DOCUMENT_TEMPLATE_FILES_REQUIRED", "请上传 1–10 份需求文档");
        long total = 0;
        for (Incoming file : files) {
            if (file == null || file.bytes() == null || file.bytes().length == 0 || file.bytes().length > MAX_FILE_BYTES)
                throw bad("DOCUMENT_TEMPLATE_FILE_SIZE", "文档不能为空，单文件最多 20 MiB");
            total += file.bytes().length;
        }
        if (total > MAX_BATCH_BYTES) throw bad("DOCUMENT_TEMPLATE_BATCH_SIZE", "本次文档总大小超过 50 MiB");
        List<Prepared> result = new ArrayList<>();
        for (Incoming file : files) result.add(prepare(file));
        return List.copyOf(result);
    }

    private Prepared prepare(Incoming file) {
        String name = safeName(file.filename());
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("docx", "md", "markdown", "pdf").contains(extension))
            throw bad("DOCUMENT_TEMPLATE_FORMAT", "仅支持 DOCX、Markdown 和文本 PDF；旧 DOC 或扫描件请先转换");
        byte[] bytes = file.bytes().clone();
        Future<AssistDocumentParser.Document> job;
        try { job = parsers.submit(() -> parser.parse(name, bytes)); }
        catch (RejectedExecutionException full) { throw bad("DOCUMENT_TEMPLATE_PARSE_BUSY", "文档解析繁忙，请稍后重试"); }
        try {
            var document = job.get(15, TimeUnit.SECONDS);
            return new Prepared(name, bytes, hash(bytes), hash(json.writeValueAsBytes(document)), document);
        } catch (InterruptedException interrupted) {
            job.cancel(true); Thread.currentThread().interrupt();
            throw bad("DOCUMENT_TEMPLATE_PARSE_INTERRUPTED", "文档解析已中断，请重试同一次上传");
        } catch (TimeoutException timeout) {
            job.cancel(true); throw bad("DOCUMENT_TEMPLATE_PARSE_TIMEOUT", "文档解析超过 15 秒，请拆分文档后重试");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof BadRequestException known) throw known;
            throw bad("DOCUMENT_TEMPLATE_PARSE_FAILED", "文档无法解析，请检查格式和完整性");
        }
    }

    public String relativePath(String runId, String fileId) {
        return uuid(runId) + "/" + uuid(fileId) + ".original";
    }

    public void save(String relativePath, byte[] bytes, String expectedHash) {
        if (!hash(bytes).equals(expectedHash)) throw bad("DOCUMENT_TEMPLATE_CONTENT_CHANGED", "上传文档内容发生变化");
        try {
            Path target = resolve(relativePath, true);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { read(relativePath, expectedHash); return; }
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            ownerOnly(temporary, false);
            try {
                Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                resolve(relativePath, false);
                // The destination belongs to the durable upload id. Never replace an existing snapshot.
                Files.move(temporary, target);
            } finally { Files.deleteIfExists(temporary); }
            read(relativePath, expectedHash);
        } catch (IOException failure) { throw bad("DOCUMENT_TEMPLATE_STORAGE_FAILED", "需求原文件保存失败，请重试同一次上传"); }
    }

    public byte[] read(String relativePath, String expectedHash) {
        try {
            Path target = resolve(relativePath, false);
            try (SeekableByteChannel channel = Files.newByteChannel(target,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                long length = channel.size();
                if (length == 0 || length > MAX_FILE_BYTES) throw new IOException("size");
                ByteBuffer buffer = ByteBuffer.allocate((int) length);
                while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("short read");
                if (channel.read(ByteBuffer.allocate(1)) != -1) throw new IOException("changed");
                byte[] result = buffer.array();
                if (!hash(result).equals(expectedHash)) throw bad("DOCUMENT_TEMPLATE_CONTENT_CHANGED", "冻结文档校验失败，不能使用已改变的内容");
                return result;
            }
        } catch (IOException failure) { throw bad("DOCUMENT_TEMPLATE_STORAGE_FAILED", "冻结文档不可读取，请检查数据目录"); }
    }

    public Path runtimeDirectory(String runId) {
        try {
            Path base = canonicalBase(true), directory = base.resolve(uuid(runId));
            if (Files.isSymbolicLink(directory)) throw new IOException("symbolic link");
            if (!Files.exists(directory)) { Files.createDirectory(directory); ownerOnly(directory, true); }
            if (!directory.toRealPath().startsWith(base)) throw new IOException("containment");
            return directory.toRealPath();
        } catch (IOException failure) { throw bad("DOCUMENT_TEMPLATE_STORAGE_FAILED", "模板受管目录不可用"); }
    }

    private Path resolve(String relative, boolean create) throws IOException {
        if (relative == null || !relative.matches("[a-f0-9-]{36}/[a-f0-9-]{36}\\.original"))
            throw bad("DOCUMENT_TEMPLATE_PATH_INVALID", "文档存储引用无效");
        Path base = canonicalBase(create);
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) throw new IOException("containment");
        Path parent = target.getParent();
        if (Files.isSymbolicLink(parent)) throw new IOException("symbolic link");
        if (create && !Files.exists(parent)) { Files.createDirectory(parent); ownerOnly(parent, true); }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || !parent.toRealPath().startsWith(base))
            throw new IOException("directory");
        if (Files.isSymbolicLink(target)) throw new IOException("symbolic link");
        return target;
    }

    private synchronized Path canonicalBase(boolean create) throws IOException {
        // Resolve system aliases such as macOS /var once, while rejecting a redirected data/container directory.
        if (Files.isSymbolicLink(root.getParent()) || Files.isSymbolicLink(root)) throw new IOException("symbolic link");
        if (create && !Files.exists(root)) { Files.createDirectories(root); ownerOnly(root, true); }
        Path actual = root.toRealPath();
        if (canonicalRoot == null) canonicalRoot = actual;
        else if (!canonicalRoot.equals(actual)) throw new IOException("data directory changed");
        return canonicalRoot;
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.chars().anyMatch(Character::isISOControl))
            throw bad("DOCUMENT_TEMPLATE_FILENAME", "需求文档文件名无效");
        return name;
    }
    private static String uuid(String value) {
        try { if (UUID.fromString(value).toString().equals(value)) return value; }
        catch (RuntimeException invalid) { /* deterministic boundary below */ }
        throw bad("DOCUMENT_TEMPLATE_PATH_INVALID", "文档身份无效");
    }
    private static void ownerOnly(Path path, boolean directory) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); }
        catch (UnsupportedOperationException windows) { /* Windows inherits its data-directory ACL. */ }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static BadRequestException bad(String code, String message) { return new BadRequestException(code, message); }
    @PreDestroy public void close() { parsers.shutdownNow(); }
    public record Incoming(String filename, byte[] bytes) { }
    public record Prepared(String filename, byte[] bytes, String sha256, String representationSha256,
                           AssistDocumentParser.Document document) { }
}
