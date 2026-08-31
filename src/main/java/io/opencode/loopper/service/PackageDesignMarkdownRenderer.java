package io.opencode.loopper.service;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Canonical human-reviewable Markdown adapter for one validated package-design candidate. */
final class PackageDesignMarkdownRenderer {
    String render(PackageDesignCandidateDocument candidate) {
        Map<String, String> factTitles = factTitles(candidate);
        Map<String, String> stageTitles = new LinkedHashMap<>();
        candidate.stages().forEach(stage -> stageTitles.put(key(stage.key()), stage.title()));
        StringBuilder out = new StringBuilder("## 目标与范围\n\n");
        for (PackageDesignCandidateDocument.Requirement requirement : candidate.requirements()) {
            out.append("- ").append(inline(requirement.statement())).append('\n');
        }
        out.append("\n## 影响与交付\n\n| 类型 | 相对路径或符号 | 说明 |\n| --- | --- | --- |\n");
        for (PackageDesignCandidateDocument.Deliverable deliverable : candidate.deliverables()) {
            out.append("| ").append("SCOPE".equals(deliverable.kind()) ? "范围" : "交付")
                    .append(" | ").append(cell(deliverable.target())).append(" | ")
                    .append(cell(deliverable.description())).append(" |\n");
        }
        out.append("\n## 验收场景\n\n| 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        for (PackageDesignCandidateDocument.Scenario scenario : candidate.scenarios()) {
            out.append("| ").append(cell(scenario.title())).append(" | ").append(cell(scenario.precondition()))
                    .append(" | ").append(cell(scenario.action())).append(" | ")
                    .append(cell(scenario.observableResult())).append(" | ").append(cell(scenario.invariant()))
                    .append(" |\n");
        }
        if (!candidate.reviews().isEmpty()) {
            out.append("\n## 人工评审项\n\n| 评审项 | 判断标准 | 仅人工原因 |\n| --- | --- | --- |\n");
            for (PackageDesignCandidateDocument.Review review : candidate.reviews()) {
                out.append("| ").append(cell(review.title())).append(" | ").append(cell(review.criteria()))
                        .append(" | ").append(cell(review.humanOnlyReason())).append(" |\n");
            }
        }
        out.append("\n## 验收约束\n\n");
        for (PackageDesignCandidateDocument.Requirement requirement : candidate.requirements()) {
            out.append("- ").append(inline(requirement.statement())).append('\n');
        }
        out.append("\n## 阶段与依赖\n\n| 阶段 | 目标 | 负责路径 | 包含场景/评审/交付 | 前置阶段 |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        for (PackageDesignCandidateDocument.Stage stage : candidate.stages()) {
            String includes = stage.includes().stream().map(ref -> factTitles.get(key(ref)))
                    .collect(java.util.stream.Collectors.joining("；"));
            String dependencies = stage.dependencies().stream().map(ref -> stageTitles.get(key(ref)))
                    .collect(java.util.stream.Collectors.joining("；"));
            out.append("| ").append(cell(stage.title())).append(" | ").append(cell(stage.objective()))
                    .append(" |  | ").append(cell(includes)).append(" | ")
                    .append(cell(dependencies.isBlank() ? "无" : dependencies)).append(" |\n");
        }
        return out.toString();
    }

    private static Map<String, String> factTitles(PackageDesignCandidateDocument value) {
        Map<String, String> result = new LinkedHashMap<>();
        value.scenarios().forEach(item -> result.put(key(item.key()), item.title()));
        value.deliverables().forEach(item -> result.put(key(item.key()), item.target()));
        value.reviews().forEach(item -> result.put(key(item.key()), item.title()));
        return result;
    }

    private static String key(String value) {
        return inline(value).toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }

    private static String inline(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .replaceAll("\\s+", " ");
    }

    private static String cell(String value) { return inline(value).replace("|", "\\|"); }
}
