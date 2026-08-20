package io.opencode.loopper.service;

import java.nio.file.Files;
import java.nio.file.Path;

/** Fail-closed path policy for persisted local-sync sessions and source-repository files. */
final class LocalSyncPathPolicy {
    private final Path sessionsRoot;

    LocalSyncPathPolicy(Path dataDir) {
        this.sessionsRoot = dataDir.toAbsolutePath().normalize().resolve("local-sync-conflicts");
    }

    Path sessionDirectory(String sessionId) {
        if (sessionId == null || !sessionId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("invalid session id");
        }
        return sessionsRoot.resolve(sessionId);
    }

    String safeRelative(Path root, String raw) {
        Path resolved = safeResolve(root, raw);
        return root.toAbsolutePath().normalize().relativize(resolved).toString().replace('\\', '/');
    }

    Path safeResolve(Path root, String relative) {
        validateRelative(relative);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new BadRequestException("LOCAL_SYNC_PATH_ESCAPE", "路径越出源项目根目录");
        }
        Path cursor = normalizedRoot;
        for (Path part : normalizedRoot.relativize(resolved)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new BadRequestException("LOCAL_SYNC_PATH_ESCAPE", "路径不能经过符号链接：" + relative);
            }
        }
        return resolved;
    }

    void validateRelative(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("LOCAL_SYNC_PATH_INVALID", "文件路径不能为空");
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException failure) {
            throw new BadRequestException("LOCAL_SYNC_PATH_INVALID", "文件路径无效");
        }
        boolean gitMetadata = false;
        for (Path part : path) {
            if (".git".equalsIgnoreCase(part.toString())) gitMetadata = true;
        }
        if (path.isAbsolute() || path.normalize().startsWith("..") || path.toString().indexOf('\0') >= 0
                || gitMetadata) {
            throw new BadRequestException("LOCAL_SYNC_PATH_ESCAPE", "路径必须位于源项目内且不能进入 .git");
        }
    }
}
