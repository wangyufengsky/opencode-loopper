package io.opencode.loopper.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed report shells are frozen with the task, independent of model text and future resource edits. */
public final class TemplateReportLayout {
    public static final String VERSION = "REPORT_LAYOUT_V3";
    public static final String LEGACY_VERSION = "REPORT_LAYOUT_V2";
    private static final Pattern SLOT = Pattern.compile("\\{\\{([a-z]+)}}");
    private static final Map<String, String> LEGACY_RESOURCES = Map.of("review", "code-review-v2.md",
            "total", "contribution-report-v2.md", "personal", "personal-contribution-v2.md");
    private static final Map<String, String> RESOURCES = Map.of("review", "code-review-v3.md",
            "total", "contribution-report-v3.md", "personal", "personal-contribution-v3.md",
            "detail", "report-detail-v3.md");
    public static final String HISTORY_VERSION = "HISTORY_REPORT_LAYOUT_V1";
    private TemplateReportLayout() { }

    public static Frozen freeze() { return freeze(VERSION, RESOURCES); }
    public static Frozen freezeHistory() {
        var resources = new LinkedHashMap<>(RESOURCES);
        resources.put("review", "history-review-v1.md");
        return freeze(HISTORY_VERSION, resources);
    }
    public static Frozen freezeV2() { return freeze(LEGACY_VERSION, LEGACY_RESOURCES); }
    private static Frozen freeze(String version, Map<String, String> resources) {
        Map<String, String> templates = new LinkedHashMap<>();
        resources.keySet().stream().sorted().forEach(key -> {
            try (var input = TemplateReportLayout.class.getResourceAsStream("/report-templates/" + resources.get(key))) {
                if (input == null) throw new IllegalStateException("内置报告模板缺失");
                templates.put(key, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException failure) { throw new IllegalStateException("内置报告模板无法读取", failure); }
        });
        return new Frozen(version, templates, digest(templates));
    }

    public record Frozen(String version, Map<String, String> templates, String sha256) {
        public Frozen {
            templates = Map.copyOf(templates);
            var resources = (VERSION.equals(version) || HISTORY_VERSION.equals(version)) ? RESOURCES : LEGACY_VERSION.equals(version) ? LEGACY_RESOURCES : Map.<String, String>of();
            if (resources.isEmpty() || !templates.keySet().equals(resources.keySet()) || !digest(templates).equals(sha256)) {
                throw new IllegalArgumentException("冻结报告模板版本或校验和不一致");
            }
        }

        public boolean hierarchical() { return VERSION.equals(version) || HISTORY_VERSION.equals(version); }

        public String render(String kind, Map<String, String> content) {
            String template = templates.get(kind);
            if (template == null) throw new IllegalArgumentException("报告模板类型不存在");
            Matcher matcher = SLOT.matcher(template);
            StringBuilder output = new StringBuilder();
            var used = new java.util.HashSet<String>();
            while (matcher.find()) {
                String key = matcher.group(1);
                if (!content.containsKey(key)) throw new IllegalArgumentException("报告章节未完整填充：" + key);
                used.add(key);
                matcher.appendReplacement(output, Matcher.quoteReplacement(content.get(key)));
            }
            matcher.appendTail(output);
            if (!used.equals(content.keySet())) throw new IllegalArgumentException("报告包含模板之外的章节");
            return output.toString();
        }
    }

    private static String digest(Map<String, String> templates) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            templates.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
