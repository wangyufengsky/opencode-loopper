package io.opencode.loopper.service.knowledge;

import java.util.*;
import java.util.regex.Pattern;

/** Explicit bounded directory projection; globs match source-relative paths, never authorize them. */
public record KnowledgeBrowseOptions(int depth, String entryType, List<String> extensions, String pathPattern, boolean includeIntermediate) {
    public KnowledgeBrowseOptions {
        if (depth < 1 || depth > 12 || !Set.of("all", "files", "directories").contains(entryType)) throw KnowledgeSources.bad("depth 为 1–12；entryType 为 all/files/directories");
        if (extensions.size() > 20 || extensions.stream().anyMatch(e -> !e.matches("[a-zA-Z0-9]{1,16}"))) throw KnowledgeSources.bad("extensions 最多 20 个扩展名，不含点号");
        extensions = extensions.stream().map(e -> e.toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        if (pathPattern.length() > 200 || pathPattern.contains("..") || pathPattern.startsWith("/") || pathPattern.contains("\\")) throw KnowledgeSources.bad("pathPattern 必须是来源内模式，支持 *、**、?");
    }
    public static KnowledgeBrowseOptions from(Map<String,Object> args) {
        Object recursive = args.get("recursive"), depth = args.get("depth"), extensions = args.get("extensions");
        if (recursive != null && !(recursive instanceof Boolean) || depth != null && (!(depth instanceof Number n) || n.doubleValue() != n.intValue())) throw KnowledgeSources.bad("recursive 必须是布尔值，depth 必须是整数");
        if (extensions != null && (!(extensions instanceof List<?> values) || values.stream().anyMatch(v -> !(v instanceof String)))) throw KnowledgeSources.bad("extensions 必须是扩展名列表");
        boolean implicit = !Objects.toString(args.get("query"), "").isBlank();
        int requested = depth instanceof Number n ? n.intValue() : (recursive instanceof Boolean b ? b : implicit) ? 12 : 1;
        if (Boolean.FALSE.equals(recursive) && requested > 1) throw KnowledgeSources.bad("recursive=false 时 depth 只能为 1");
        return new KnowledgeBrowseOptions(requested, Objects.toString(args.get("entryType"), "all"),
                extensions instanceof List<?> values ? values.stream().map(String.class::cast).toList() : List.of(), Objects.toString(args.get("pathPattern"), ""), true);
    }
    public boolean matches(String path, boolean directory) {
        if (entryType.equals("files") && directory || entryType.equals("directories") && !directory) return false;
        if (!extensions.isEmpty() && (directory || !extensions.contains(KnowledgeFiles.extension(path)))) return false;
        if (pathPattern.isEmpty()) return true;
        var regex = new StringBuilder("^");
        for (int i = 0; i < pathPattern.length(); i++) {
            char c = pathPattern.charAt(i);
            if (c == '*' && i + 1 < pathPattern.length() && pathPattern.charAt(i + 1) == '*') {
                i++; if (i + 1 < pathPattern.length() && pathPattern.charAt(i + 1) == '/') { i++; regex.append("(?:.*/)?"); } else regex.append(".*");
            } else if (c == '*') regex.append("[^/]*"); else if (c == '?') regex.append("[^/]"); else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return path.matches(regex.append('$').toString());
    }
}
