package io.opencode.loopper.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Legacy filesystem adapter; the deterministic compiler itself consumes only immutable source facts. */
final class ReviewerReportLiveSourceAdapter {
    private static final Set<String> EXCLUDED_SEGMENTS = Set.of(
            ".git", ".idea", "data", "target", "build", "dist", "node_modules");

    List<ReviewerReportCompilation.SourceFile> capture(
            Path root, List<ReviewerReportCompilation.Finding> findings) {
        Path managedRoot = root.toAbsolutePath().normalize();
        Set<String> paths = new LinkedHashSet<>();
        if (findings != null) findings.forEach(finding -> {
            if (finding != null && finding.path() != null) paths.add(finding.path());
        });
        List<ReviewerReportCompilation.SourceFile> result = new ArrayList<>();
        for (String relative : paths) {
            try {
                if (!safeLexical(relative)) continue;
                Path file = managedRoot.resolve(relative).normalize();
                if (!file.startsWith(managedRoot) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)) continue;
                Path realRoot = managedRoot.toRealPath();
                Path realFile = file.toRealPath();
                if (!realFile.startsWith(realRoot) || excluded(realRoot.relativize(realFile))) continue;
                long size = Files.size(realFile);
                if (size > 16_000_000) continue;
                long lines;
                try (var stream = Files.lines(realFile, StandardCharsets.UTF_8)) {
                    lines = stream.limit(1_000_001).count();
                }
                if (lines < 1 || lines > 1_000_000) continue;
                result.add(new ReviewerReportCompilation.SourceFile(relative, size, lines,
                        sha256(Files.readAllBytes(realFile))));
            } catch (Exception ignored) { }
        }
        return List.copyOf(result);
    }

    private static boolean safeLexical(String relative) {
        if (relative == null || relative.isBlank() || relative.startsWith("/") || relative.indexOf('\\') >= 0
                || relative.matches("^[A-Za-z]:.*")) return false;
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private static boolean excluded(Path relative) {
        for (Path segment : relative) {
            String value = segment.toString();
            if (EXCLUDED_SEGMENTS.contains(value) || ".env".equals(value)
                    || (value.startsWith(".env.") && !".env.example".equals(value))) return true;
        }
        return false;
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
