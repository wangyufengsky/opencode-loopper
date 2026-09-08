package io.opencode.loopper.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads declared output locations without promoting source references to writable deliverables. */
final class DocumentOutputPathResolver {
    private static final Pattern PATH = Pattern.compile(
            "`([^`\\r\\n]+\\.(?:md|markdown|docx))`|(?<![\\p{L}\\p{N}_./-])([\\p{L}\\p{N}_./-]+\\.(?:md|markdown|docx))(?![\\w./-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTPUT = Pattern.compile(
            "输出|交付|目标文件|文件名|保存为|写入|产出|output|deliverable|save\\s+as|target\\s+(?:file|path)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE = Pattern.compile(
            "参考|引用|来源|输入|参照|阅读|读取|reference|source|input|read\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTERNATIVE = Pattern.compile("如用户|若用户|偏好|例如|可选|alternative|e\\.g\\.", Pattern.CASE_INSENSITIVE);

    private DocumentOutputPathResolver() { }

    static String resolve(String markdown, boolean docx) {
        Set<String> explicit = new LinkedHashSet<>();
        Set<String> unspecified = new LinkedHashSet<>();
        boolean outputSection = false;
        for (String line : markdown.lines().toList()) {
            if (line.stripLeading().startsWith("#")) outputSection = OUTPUT.matcher(line).find();
            var paths = PATH.matcher(line);
            int previousEnd = 0;
            while (paths.find()) {
                String path = paths.group(1) == null ? paths.group(2) : paths.group(1).trim();
                String context = line.substring(previousEnd, paths.start());
                previousEnd = paths.end();
                if (ALTERNATIVE.matcher(context).find()) continue;
                int output = lastMatch(OUTPUT, context);
                int reference = lastMatch(REFERENCE, context);
                if (reference > output) continue;
                boolean declared = output >= 0 || outputSection;
                if (!declared && path.equalsIgnoreCase("README.md")) continue;
                if (declared) explicit.add(path);
                else unspecified.add(path);
            }
        }
        Set<String> candidates = explicit.isEmpty() ? unspecified : explicit;
        if (candidates.size() > 1) throw new BadRequestException("DOCUMENT_OUTPUT_PATH_AMBIGUOUS",
                "需求包含多个可能的文档输出路径，请明确唯一的最终交付文件，并将其他文件标为参考资料。");
        String target = candidates.isEmpty() ? (docx ? "output/document.docx" : "output/document.md")
                : candidates.iterator().next();
        String lower = target.toLowerCase(java.util.Locale.ROOT);
        if (docx ? !lower.endsWith(".docx") : !(lower.endsWith(".md") || lower.endsWith(".markdown"))) {
            throw new BadRequestException("DOCUMENT_OUTPUT_FORMAT_MISMATCH", "最终交付文件的扩展名与已确认的文档格式不一致，请统一后重新确认需求。");
        }
        if (target.startsWith("/") || target.contains("\\") || target.contains(":")
                || java.util.Arrays.asList(target.split("/")).contains("..")) {
            throw new BadRequestException("DOCUMENT_OUTPUT_PATH_INVALID", "最终交付文件必须使用项目内的相对路径，不能包含上级目录或绝对路径。");
        }
        return target;
    }

    private static int lastMatch(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        int last = -1;
        while (matcher.find()) last = matcher.start();
        return last;
    }
}
