package io.opencode.loopper.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Captures a bounded, deterministic source manifest before any Reviewer remote I/O. */
@Component
final class ReviewerReportSourceManifestCapture {
    private static final int MAX_FILES = 8_192;
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 16_000_000;
    private static final long MAX_LINES = 1_000_000;
    private static final Set<String> EXCLUDED_SEGMENTS = Set.of(
            ".git", ".idea", ".gradle", ".venv", "venv", "data", "target", "build", "dist",
            "node_modules");

    private final int maxFiles;
    private final long maxTotalBytes;

    ReviewerReportSourceManifestCapture() {
        this(MAX_FILES, MAX_TOTAL_BYTES);
    }

    ReviewerReportSourceManifestCapture(int maxFiles, long maxTotalBytes) {
        if (maxFiles < 1 || maxTotalBytes < 1) throw new IllegalArgumentException("Manifest bounds are required");
        this.maxFiles = maxFiles;
        this.maxTotalBytes = maxTotalBytes;
    }

    List<ReviewerReportCompilation.SourceFile> capture(Path inputRoot) {
        if (inputRoot == null) throw invalid("Reviewer source root is required");
        try {
            Path root = inputRoot.toAbsolutePath().normalize().toRealPath();
            List<ReviewerReportCompilation.SourceFile> result = new ArrayList<>();
            long[] totalBytes = {0};
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root) && excluded(root.relativize(directory))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes before) throws IOException {
                    Path relativePath = root.relativize(file);
                    if (!before.isRegularFile() || Files.isSymbolicLink(file) || excluded(relativePath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = relativePath.toString().replace('\\', '/');
                    if (relative.isBlank() || relative.length() > 1_024 || before.size() < 1
                            || before.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                    byte[] content = Files.readAllBytes(file);
                    BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
                    if (!sameIdentity(before, after)) {
                        throw new SnapshotChangedException();
                    }
                    String text;
                    try {
                        text = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(content)).toString();
                    } catch (Exception binary) {
                        return FileVisitResult.CONTINUE;
                    }
                    long lines = text.lines().count();
                    if (lines < 1 || lines > MAX_LINES) return FileVisitResult.CONTINUE;
                    totalBytes[0] += content.length;
                    if (result.size() >= maxFiles || totalBytes[0] > maxTotalBytes) {
                        throw new ManifestLimitException();
                    }
                    result.add(new ReviewerReportCompilation.SourceFile(
                            relative, content.length, lines, sha256(content)));
                    return FileVisitResult.CONTINUE;
                }
            });
            result.sort(Comparator.comparing(ReviewerReportCompilation.SourceFile::path));
            return List.copyOf(result);
        } catch (ManifestLimitException limit) {
            throw new ConflictException("REVIEWER_SOURCE_SNAPSHOT_TOO_LARGE",
                    "Reviewer source manifest exceeds its bounded file or byte limit");
        } catch (SnapshotChangedException changed) {
            throw new ConflictException("REVIEWER_SOURCE_SNAPSHOT_CHANGED",
                    "Reviewer source changed while its immutable manifest was captured");
        } catch (IOException failure) {
            throw invalid("Reviewer source manifest could not be captured");
        }
    }

    static boolean sameIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        return before != null && after != null && before.isRegularFile() && after.isRegularFile()
                && before.size() == after.size()
                && before.lastModifiedTime().compareTo(after.lastModifiedTime()) == 0
                && Objects.equals(before.fileKey(), after.fileKey());
    }

    private static boolean excluded(Path relative) {
        for (Path segment : relative) {
            String value = segment.toString();
            if (EXCLUDED_SEGMENTS.contains(value) || ".env".equals(value)
                    || value.startsWith(".env.") && !".env.example".equals(value)) return true;
        }
        return false;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static ConflictException invalid(String detail) {
        return new ConflictException("REVIEWER_SOURCE_SNAPSHOT_INVALID", detail);
    }

    private static final class ManifestLimitException extends IOException { }
    private static final class SnapshotChangedException extends IOException { }
}
