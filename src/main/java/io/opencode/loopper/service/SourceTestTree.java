package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Private hashes include ignored and sensitive files without exposing their contents to any model. */
final class SourceTestTree {
    private static final Set<String> CACHES = Set.of("__pycache__", ".pytest_cache", ".mypy_cache");
    private SourceTestTree() { }
    static Map<String, File> scan(Path root, Path data) {
        var result = new TreeMap<String, File>(); long[] bytes = {0};
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private void budget() {
                    if (result.size() >= 50000 || bytes[0] > 512L * 1024 * 1024 || System.nanoTime() > deadline || Thread.currentThread().isInterrupted())
                        throw failure("测试范围检查超出文件、读取量或时间上限");
                }
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    budget();
                    if (!directory.equals(root) && (generated(root, directory) || directory.startsWith(data)))
                        return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    budget(); String relative = SourcePathPolicy.relative(root, path);
                    if (attributes.isSymbolicLink()) {
                        String value = Files.readSymbolicLink(path).toString();
                        result.put(relative, new File("LINK", value.length(), DocumentModelStore.hash(value)));
                    } else if (attributes.isRegularFile()) {
                        MessageDigest digest;
                        try { digest = MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
                        long length = 0;
                        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                            byte[] block = new byte[16384]; int size;
                            while ((size = stream.read(block)) >= 0) { length += size; bytes[0] += size; digest.update(block, 0, size); budget(); }
                        }
                        result.put(relative, new File("FILE", length, HexFormat.of().formatHex(digest.digest())));
                    } else result.put(relative, new File("SPECIAL", attributes.size(), ""));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException unavailable) { throw failure("无法完整检查工作区文件，请确认文件权限与停止状态"); }
        return Collections.unmodifiableMap(result);
    }
    private static boolean generated(Path root, Path directory) {
        String name = directory.getFileName().toString();
        if (directory.equals(root.resolve(".git"))) return true;
        if (CACHES.contains(name)) return true;
        // A folder called target/build inside source or tests is still a source change.
        Path parent = directory.getParent();
        return switch (name) {
            case "target" -> Files.isRegularFile(parent.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS);
            case "build", ".gradle" -> Files.isRegularFile(parent.resolve("build.gradle"), LinkOption.NOFOLLOW_LINKS)
                    || Files.isRegularFile(parent.resolve("build.gradle.kts"), LinkOption.NOFOLLOW_LINKS);
            case "dist", "coverage", ".next", "node_modules" -> Files.isRegularFile(parent.resolve("package.json"), LinkOption.NOFOLLOW_LINKS);
            case ".venv", "venv" -> Files.isRegularFile(parent.resolve("pyproject.toml"), LinkOption.NOFOLLOW_LINKS)
                    || Files.isRegularFile(parent.resolve("pytest.ini"), LinkOption.NOFOLLOW_LINKS);
            default -> false;
        };
    }
    static TaskFailure failure(String message) { return new TaskFailure("SOURCE_TEST_SCOPE_UNVERIFIED", message); }
    record File(String kind, long size, String sha256) { }
}
