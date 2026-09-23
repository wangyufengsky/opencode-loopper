package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.template.SourceManifest;
import io.opencode.loopper.template.SourceTemplateParameters;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/** Bounded current-directory snapshot. Model input is paged later, never a truncated prefix of this inventory. */
@Component
public final class SourceTreeCapture {
    public static final int MAX_FILES = 50_000;
    public static final int MAX_FILE_BYTES = 2_000_000;
    public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private final LoopperProperties properties;
    public SourceTreeCapture(LoopperProperties properties) { this.properties = properties; }

    public Capture capture(SourceTemplateParameters parameters, boolean unitTests) {
        Path root = SourcePathPolicy.root(parameters.projectRoot());
        Path target = SourcePathPolicy.resolve(root, parameters.sourcePath(), true);
        var first = collect(root, target, unitTests, false);
        var second = collect(root, target, unitTests, true);
        if (!first.manifest().sha256().equals(second.manifest().sha256()))
            throw new ConflictException("SOURCE_SNAPSHOT_UNSTABLE", "采集期间源码发生变化，请停止修改后重新检查");
        return second;
    }
    private Capture collect(Path root, Path target, boolean unitTests, boolean keepBytes) {
        var files = new TreeMap<String, SourceManifest.File>();
        var contents = new HashMap<String, byte[]>();
        Path data = properties.getDataDir().toAbsolutePath().normalize();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        long[] total = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    checkLimits(files.size(), deadline);
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    String path = SourcePathPolicy.relative(root, dir);
                    String excluded = dir.startsWith(data) ? "Loopper 运行数据" : SourcePathPolicy.exclusion(path, true);
                    if (excluded == null) return FileVisitResult.CONTINUE;
                    add(files, new SourceManifest.File(path + "/", overlaps(dir, target), 0, null, excluded));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    checkLimits(files.size(), deadline);
                    String path = SourcePathPolicy.relative(root, file);
                    boolean selected = file.startsWith(target);
                    String excluded = attrs.isSymbolicLink() ? "符号链接未展开"
                            : !attrs.isRegularFile() ? "非普通文件" : SourcePathPolicy.exclusion(path, false);
                    if (excluded == null && unitTests && selected && SourcePathPolicy.testPath(path)) excluded = "已有测试作为上下文读取";
                    if (excluded == null && unitTests && selected && !SourcePathPolicy.behaviorSource(path)) excluded = "配置与文档仅作为只读上下文";
                    boolean contextTest = excluded != null && (excluded.equals("已有测试作为上下文读取") || excluded.equals("配置与文档仅作为只读上下文"));
                    if (attrs.size() > MAX_FILE_BYTES && (excluded == null || contextTest)) excluded = "文件超过源码读取上限";
                    byte[] bytes = null;
                    if (excluded == null || contextTest && attrs.size() <= MAX_FILE_BYTES) {
                        SourcePathPolicy.requireContained(root, file);
                        bytes = read(file);
                        total[0] += bytes.length;
                        if (total[0] > MAX_TOTAL_BYTES) throw limit("项目可读源码超过 64 MiB，请登记更小的项目范围");
                        if (!isText(bytes)) { excluded = "无法解析为 UTF-8 文本"; bytes = null; }
                        else if (new String(bytes, StandardCharsets.UTF_8).isBlank()) excluded = "空源码文件，没有可描述或测试的行为";
                    }
                    String sha = bytes == null ? null : hash(bytes);
                    add(files, new SourceManifest.File(path, selected, attrs.size(), sha, excluded));
                    if (keepBytes && bytes != null) contents.put(sha, bytes);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    checkLimits(files.size(), deadline);
                    add(files, new SourceManifest.File(SourcePathPolicy.relative(root, file), file.startsWith(target),
                            0, null, "文件不可读取，请检查权限"));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            throw new BadRequestException("SOURCE_SNAPSHOT_READ_FAILED", "无法完整读取项目目录，请检查权限后重试");
        }
        String selected = SourcePathPolicy.relative(root, target);
        var values = List.copyOf(files.values());
        String fingerprint = fingerprint(selected, values);
        return new Capture(new SourceManifest(root.toString(), selected, fingerprint, values), Map.copyOf(contents));
    }
    private static boolean overlaps(Path directory, Path target) { return directory.startsWith(target) || target.startsWith(directory); }
    private static void add(Map<String, SourceManifest.File> files, SourceManifest.File file) {
        if (files.size() >= MAX_FILES) throw limit("项目目录超过 50000 项，请登记更小的项目范围");
        files.put(file.path(), file);
    }
    private static void checkLimits(int count, long deadline) {
        if (Thread.currentThread().isInterrupted()) throw limit("源码采集已中断，可从原发起记录重试");
        if (count >= MAX_FILES || System.nanoTime() > deadline) throw limit("源码采集达到目录或时间上限，请缩小项目范围");
    }
    private static byte[] read(Path file) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] result = input.readNBytes(MAX_FILE_BYTES + 1);
            if (result.length > MAX_FILE_BYTES) throw limit("采集期间文件超过源码读取上限");
            return result;
        }
    }
    static boolean unresolved(String exclusion) {
        return exclusion != null && (exclusion.startsWith("无法解析") || exclusion.startsWith("文件不可读取")
                || exclusion.equals("文件超过源码读取上限"));
    }
    private static boolean isText(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return false;
        try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)); return true; }
        catch (CharacterCodingException invalid) { return false; }
    }
    public static String fingerprint(String source, List<SourceManifest.File> files) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            update(digest, source);
            for (var file : files) {
                update(digest, file.path()); update(digest, Boolean.toString(file.target()));
                update(digest, Long.toString(file.sizeBytes())); update(digest, file.sha256()); update(digest, file.exclusion());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static void update(MessageDigest digest, String value) {
        digest.update(Objects.toString(value, "").getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static BadRequestException limit(String message) { return new BadRequestException("SOURCE_SNAPSHOT_LIMIT", message); }
    public record Capture(SourceManifest manifest, Map<String, byte[]> contents) { }
}
