package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Resolves the explicitly selected export directory without creating files at confirmation time. */
@Component
public final class TemplateDocumentPaths {
    private static final Set<String> PROTECTED = Set.of(".git", ".ssh", ".aws", ".gnupg", ".codex", ".env");

    public String resolve(String projectRoot, String input) {
        if (input == null || input.isBlank()) return null;
        try {
            if (input.length() > 2048 || input.chars().anyMatch(Character::isISOControl)) throw new IOException();
            Path value = Path.of(input.strip());
            for (Path part : value) {
                if (part.toString().equals("..")) throw new IOException();
            }
            Path root = Path.of(projectRoot).toRealPath();
            Path target = (value.isAbsolute() ? value : root.resolve(value)).toAbsolutePath().normalize();
            // Canonicalize the existing ancestor once; all subsequent writes recheck the frozen absolute path.
            requireSafeDirectory(target);
            Path ancestor = target;
            while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
            if (ancestor == null || Files.isSymbolicLink(ancestor)) throw new IOException();
            Path canonical = ancestor.toRealPath().resolve(ancestor.relativize(target)).normalize();
            requireSafeDirectory(canonical);
            if (!value.isAbsolute() && !canonical.startsWith(root)) throw new IOException();
            return canonical.toString();
        } catch (IOException | RuntimeException invalid) {
            throw new BadRequestException("TEMPLATE_DOCUMENT_PATH_INVALID", "文档路径无效，请选择普通目录，避开符号链接、敏感目录和上级路径");
        }
    }

    public static Path reportDirectory(String outputPath, String taskId, String attemptId, Path workspace) {
        return outputPath == null || outputPath.isBlank() ? workspace.resolve("reports").resolve(attemptId)
                : Path.of(outputPath).resolve("template-" + taskId).resolve(attemptId);
    }

    public static void requireSafeDirectory(Path target) {
        for (Path part : target) {
            if (PROTECTED.contains(part.toString().toLowerCase(Locale.ROOT))) {
                throw new TaskFailure("TEMPLATE_REPORT_PATH_INVALID", "文档路径不能位于敏感目录");
            }
        }
        for (Path part = target; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new TaskFailure("TEMPLATE_REPORT_SYMLINK", "文档目录包含符号链接，已停止写入");
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(part, LinkOption.NOFOLLOW_LINKS)) {
                throw new TaskFailure("TEMPLATE_REPORT_PATH_INVALID", "文档路径被文件占用，请选择目录");
            }
        }
    }
}
