package io.opencode.loopper.template;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Portable user-facing names; ordinal ownership is allocated by the persistent bundle store. */
public record TemplateReportNames(String type, String project, String startDate, String endDate, long sequence) {
    public TemplateReportNames {
        type = safe(type, 72);
        project = safe(project, 96);
        startDate = LocalDate.parse(startDate).format(DateTimeFormatter.BASIC_ISO_DATE);
        endDate = LocalDate.parse(endDate).format(DateTimeFormatter.BASIC_ISO_DATE);
        if (sequence < 1) throw new IllegalArgumentException("报告序号无效");
    }

    public static TemplateReportNames of(TemplateTaskDefinition type, String project, TemplateGitEvidence evidence, long sequence) {
        return new TemplateReportNames(type == TemplateTaskDefinition.CODE_REVIEW ? "代码审查" : "项目贡献周报",
                project, evidence.startDate(), evidence.endDate(), sequence);
    }

    public String folder() { return name(type, sequence); }
    public String main() { return folder() + ".md"; }
    public String details() { return name(type + "明细", sequence); }
    public String child(String kind, int ordinal) { return details() + "/" + name(safe(kind, 72), ordinal) + ".md"; }
    public String namespace() { return (type + "_" + project + "_" + startDate + "-" + endDate).toLowerCase(Locale.ROOT); }
    private String name(String kind, long ordinal) {
        return kind + "_" + project + "_" + startDate + "-" + endDate + "_" + String.format(Locale.ROOT, "%03d", ordinal);
    }

    public static String link(String from, String to) {
        Path parent = Path.of(from).getParent();
        String path = (parent == null ? Path.of(to) : parent.relativize(Path.of(to))).toString().replace('\\', '/');
        return java.util.Arrays.stream(path.split("/"))
                .map(part -> java.net.URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private static String safe(String value, int maxBytes) {
        if (value == null) value = "未命名";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cc}\\p{Cf}<>:\"/\\\\|?*\\s]+", "-").replaceAll("^[. -]+|[. -]+$", "");
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int code : normalized.codePoints().toArray()) {
            String character = new String(Character.toChars(code));
            int length = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length > maxBytes) break;
            result.append(character); bytes += length;
        }
        String safe = result.toString().replaceAll("[. -]+$", "");
        return safe.isEmpty() ? "未命名" : safe;
    }
}
