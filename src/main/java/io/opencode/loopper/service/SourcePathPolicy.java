package io.opencode.loopper.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.Set;

/** Canonical project containment and explicit source exclusions, shared by preview, capture and reads. */
public final class SourcePathPolicy {
    private static final Set<String> OMIT = Set.of(".git", "node_modules", "target", "dist", "build",
            ".gradle", ".idea", ".vscode", "__pycache__", ".venv", "venv", "coverage", ".next", "vendor");
    private static final Set<String> EXTENSIONS = Set.of("java", "kt", "kts", "scala", "groovy", "js", "jsx",
            "ts", "tsx", "vue", "svelte", "py", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs", "fs",
            "swift", "m", "mm", "rb", "php", "sql", "xml", "json", "yaml", "yml", "properties", "toml",
            "ini", "conf", "cfg", "gradle", "md", "markdown", "txt", "html", "css", "scss", "less", "sh", "bat", "cmd");
    private SourcePathPolicy() { }
    public static Path root(String projectRoot) {
        try {
            Path root = Path.of(projectRoot).toRealPath();
            if (!Files.isDirectory(root)) throw new IOException();
            return root;
        } catch (IOException | RuntimeException invalid) { throw invalid("项目目录不可用，请检查项目路径"); }
    }
    public static Path resolve(Path root, String input, boolean existing) {
        if (input == null || input.isBlank() || input.length() > 2048 || input.chars().anyMatch(Character::isISOControl))
            throw invalid("请输入项目内的源码文件或目录");
        try {
            Path value = Path.of(input.strip());
            for (Path part : value) if (part.toString().equals("..")) throw invalid("路径不能包含上级跳转");
            Path target = (value.isAbsolute() ? value : root.resolve(value)).toAbsolutePath().normalize();
            if (!target.startsWith(root)) throw invalid("源码和测试路径必须位于所选项目内");
            requireContained(root, target);
            if (existing && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw invalid("路径不存在，请检查输入");
            return target;
        } catch (InvalidPathException invalid) { throw invalid("路径格式无效，请重新输入"); }
    }
    public static void requireContained(Path root, Path target) {
        if (!target.normalize().startsWith(root)) throw invalid("路径超出项目范围");
        for (Path part = target; part != null && !part.equals(root); part = part.getParent())
            if (Files.isSymbolicLink(part)) throw invalid("路径包含符号链接，请选择普通文件或目录");
    }
    public static String relative(Path root, Path target) {
        String value = root.relativize(target).toString().replace('\\', '/');
        return value.isEmpty() ? "." : value;
    }
    public static String exclusion(String relative, boolean directory) {
        if (DocumentCodeSnapshotService.protectedPath(relative)) return "受保护文件不提供读取";
        for (String part : relative.split("/")) {
            if (Set.of(".codex", ".gnupg", ".kube").contains(part.toLowerCase(Locale.ROOT))) return "受保护文件不提供读取";
            if (OMIT.contains(part.toLowerCase(Locale.ROOT))) return "依赖、构建或工具生成内容";
            if (part.equalsIgnoreCase("generated") || part.equalsIgnoreCase("generated-sources")) return "自动生成的源码";
        }
        if (directory) return null;
        String name = relative.substring(relative.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.equals("package-lock.json") || name.equals("yarn.lock") || name.equals("pnpm-lock.yaml")
                || name.equals("cargo.lock") || name.equals("poetry.lock")) return "依赖锁定清单";
        if (name.equals("dockerfile") || name.equals("makefile") || name.equals("gradlew") || name.equals("mvnw")) return null;
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        return EXTENSIONS.contains(extension) ? null : "非支持的文本源码或配置文件";
    }
    public static boolean testPath(String relative) {
        String value = "/" + relative.toLowerCase(Locale.ROOT);
        return value.contains("/src/test/") || value.contains("/src/androidtest/") || value.contains("/__tests__/")
                || value.contains("/tests/") || value.contains("/test/") || value.matches(".*\\.(spec|test)\\.[^/]+$");
    }
    public static boolean behaviorSource(String path) {
        return path.toLowerCase(Locale.ROOT).matches(".*\\.(java|kt|scala|groovy|js|jsx|ts|tsx|vue|svelte|py|go|rs|c|h|cc|cpp|hpp|cs|fs|swift|m|mm|rb|php|sql)$");
    }
    private static BadRequestException invalid(String message) {
        return new BadRequestException("SOURCE_PATH_INVALID", message);
    }
}
