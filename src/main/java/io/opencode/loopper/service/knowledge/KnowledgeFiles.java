package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.assist.AssistFailure;
import io.opencode.loopper.service.assist.AssistFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Local reads are bounded to the selected project or explicitly registered document root. */
public final class KnowledgeFiles {
    public static final Set<String> DOCUMENTS = Set.of("md", "markdown", "docx", "xlsx", "pptx", "pdf");
    private static final Set<String> EXCLUDED = Set.of("node_modules", "target", "dist", "build", "vendor",
            "coverage", "__pycache__", "credentials", "secrets");
    private KnowledgeFiles() { }
    private static final KnowledgeDirectoryPages PAGES = new KnowledgeDirectoryPages();
    private record DirectoryScope(Path root, Path start, String rootIdentity, String startIdentity, boolean documents, String query, Object options) { }
    public static String extension(String name) { return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT); }
    public static boolean allowed(Path relative) {
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.startsWith(".") || EXCLUDED.contains(name) || name.endsWith(".key") || name.endsWith(".pem")
                    || name.endsWith(".p12") || name.endsWith(".jks") || name.matches("(credentials|secrets?)[._-].*") || name.matches("id_(rsa|ed25519|dsa|ecdsa)(\\.pub)?")) return false;
        }
        return true;
    }
    public static Path directory(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) throw denied();
        try {
            if (!Path.of(value).isAbsolute()) throw denied();
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (path.getParent() == null || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw denied();
            for (Path p = path; p != null; p = p.getParent()) {
                if (Files.isSymbolicLink(p)) throw denied();
                if (p.getFileName() != null && !allowed(p.getFileName())) throw denied();
            }
            return path.toRealPath();
        } catch (IOException | InvalidPathException e) { throw denied(); }
    }
    public static Path resolve(String root, String relative) {
        Path base = directory(root);
        String normalized = relative(relative);
        if (normalized.isEmpty()) return base;
        return AssistFiles.resolve(base, normalized);
    }
    /** Accept harmless dot segments while rejecting every parent traversal before normalization. */
    public static String relative(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() > 1024 || value.contains("\\") || value.contains(":")) throw pathInput();
        try {
            Path input = Path.of(value);
            if (input.isAbsolute()) throw pathInput();
            for (Path part : input) if (part.toString().equals("..")) throw pathInput();
            Path normalized = input.normalize();
            if (normalized.toString().isEmpty()) return "";
            if (!allowed(normalized)) throw denied();
            return normalized.toString().replace('\\', '/');
        } catch (InvalidPathException failure) { throw pathInput(); }
    }
    private static AssistFailure pathInput() {
        return new AssistFailure("KNOWLEDGE_PATH_INVALID", "path 应为 sourceId 对应来源内的相对路径，如 src/App.java 或 ./src/App.java；浏览根目录用空字符串或 .。不要传绝对路径、重复来源目录前缀或包含 .. 的路径");
    }
    public static byte[] read(Path root, String relative, int limit) {
        if (relative == null || relative.isBlank()) throw denied();
        Path path = Path.of(relative);
        if (path.isAbsolute() || !path.normalize().equals(path) || !allowed(path)) throw denied();
        List<DirectoryStream<Path>> opened = new ArrayList<>();
        try {
            var stream = Files.newDirectoryStream(root); opened.add(stream);
            if (!(stream instanceof SecureDirectoryStream<Path> directory)) return checkedRead(root, path, limit);
            if (!root.toRealPath().equals(root.toAbsolutePath().normalize())) throw denied();
            for (int i=0; i<path.getNameCount()-1; i++) { directory = directory.newDirectoryStream(path.getName(i), LinkOption.NOFOLLOW_LINKS); opened.add(directory); }
            try (var channel = directory.newByteChannel(path.getFileName(), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    var input = java.nio.channels.Channels.newInputStream(channel)) {
                if (channel.size() > limit) throw new AssistFailure("KNOWLEDGE_SIZE_LIMIT", "文件超过读取上限，请缩小范围");
                byte[] bytes = input.readNBytes(limit + 1); if (bytes.length > limit) throw new AssistFailure("KNOWLEDGE_SIZE_LIMIT", "文件超过读取上限"); return bytes;
            }
        } catch (IOException failure) { throw new AssistFailure("KNOWLEDGE_READ_FAILED", "资料读取失败或路径发生变化，请重新读取"); }
        finally { for (int i=opened.size()-1;i>=0;i--) try { opened.get(i).close(); } catch (IOException ignored) { } }
    }
    private static byte[] checkedRead(Path root, Path relative, int limit) throws IOException {
        Path file = root.resolve(relative); var before = identities(root, relative);
        byte[] bytes = AssistFiles.read(file, limit);
        if (!before.equals(identities(root, relative))) throw new AssistFailure("KNOWLEDGE_SOURCE_CHANGED", "读取期间资料或目录发生变化，请重新读取");
        return bytes;
    }
    private static List<String> identities(Path root, Path relative) throws IOException {
        var result = new ArrayList<String>(); Path path = root;
        if (!root.toRealPath().equals(root.toAbsolutePath().normalize())) throw denied();
        var paths = new ArrayList<Path>(); paths.add(root); for (Path part : relative) { path = path.resolve(part); paths.add(path); }
        for (Path current : paths) {
            var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory() && !attributes.isRegularFile()) throw denied();
            result.add(current + ":" + attributes.fileKey() + ":" + attributes.size() + ":" + attributes.lastModifiedTime());
        }
        return result;
    }
    public static String text(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) throw denied();
            return text;
        } catch (java.nio.charset.CharacterCodingException e) { throw new AssistFailure("KNOWLEDGE_BINARY", "此文件不是可读取的 UTF-8 文本"); }
    }
    public record Entry(String path, String name, boolean directory, long bytes) { }
    public record Listing(List<Entry> items, String nextCursor, boolean incomplete, String detail,
            @com.fasterxml.jackson.annotation.JsonIgnore String resumeCursor) {
        public Listing(List<Entry> items, String nextCursor, boolean incomplete, String detail) { this(items, nextCursor, incomplete, detail, null); }
    }
    public static Listing list(String root, String relative, boolean documents, String query, String cursor, boolean recursive) {
        return list(root, relative, documents, query, cursor, new KnowledgeBrowseOptions(recursive ? 12 : 1, "all", List.of(), "", false));
    }
    public static Listing list(String root, String relative, boolean documents, String query, String cursor, KnowledgeBrowseOptions options) {
        Path base = directory(root), start = resolve(root, relative);
        DirectoryScope scope;
        try { scope = new DirectoryScope(base, start, identity(base), identity(start), documents,
                Objects.toString(query, "").toLowerCase(Locale.ROOT), options); }
        catch (IOException unavailable) { throw denied(); }
        return PAGES.page(scope, cursor, () -> scan(base, start, documents, query, options));
    }
    private static String identity(Path path) throws IOException {
        var a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.isSymbolicLink() || !a.isDirectory() && !a.isRegularFile()) throw denied();
        return a.fileKey() + ":" + a.creationTime();
    }
    private static Listing scan(Path base, Path start, boolean documents, String query, KnowledgeBrowseOptions options) {
        List<Entry> entries = new ArrayList<>(); int[] visited = {0}; boolean[] incomplete = {false};
        long deadline = System.nanoTime() + 2_000_000_000L;
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        try {
            Files.walkFileTree(start, Set.of(), options.depth(), new SimpleFileVisitor<>() {
                private boolean stop() { return ++visited[0] > 4096 || System.nanoTime() > deadline; }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (stop()) { incomplete[0] = true; return FileVisitResult.TERMINATE; }
                    if (!dir.equals(start) && !allowed(base.relativize(dir))) return FileVisitResult.SKIP_SUBTREE;
                    if (!dir.equals(start) && options.includeIntermediate()) add(dir, attrs);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (stop()) { incomplete[0] = true; return FileVisitResult.TERMINATE; }
                    add(file, attrs); return FileVisitResult.CONTINUE;
                }
                private void add(Path file, BasicFileAttributes attrs) {
                    Path rel = base.relativize(file); String name = rel.toString().replace('\\', '/');
                    if (!allowed(rel) || attrs.isSymbolicLink() || !attrs.isRegularFile() && !attrs.isDirectory()) return;
                    if (options.depth() > 1 && attrs.isDirectory() && start.relativize(file).getNameCount() >= options.depth()) incomplete[0] = true;
                    if (documents && !attrs.isDirectory() && !DOCUMENTS.contains(extension(name))) return;
                    if (name.toLowerCase(Locale.ROOT).contains(needle) && options.matches(name, attrs.isDirectory())) entries.add(new Entry(name, file.getFileName().toString(), attrs.isDirectory(), attrs.size()));
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException e) { incomplete[0] = true; return FileVisitResult.CONTINUE; }
            });
        } catch (IOException e) { throw new AssistFailure("KNOWLEDGE_DIRECTORY_UNAVAILABLE", "资料目录无法读取，请检查目录及权限"); }
        return new Listing(entries, null, incomplete[0],
                incomplete[0] ? "目录扫描不完整，请缩小到子目录继续浏览" : "");
    }
    public static AssistFailure denied() { return new AssistFailure("KNOWLEDGE_PATH_DENIED", "资料路径不可用、越界或包含受保护目录，请选择普通目录"); }
}
