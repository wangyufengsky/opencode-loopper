package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;
import io.opencode.loopper.template.ContributionScore;
import io.opencode.loopper.template.TemplateAnalysis;
import io.opencode.loopper.template.TemplateAnalysis.Unit;
import io.opencode.loopper.template.TemplateContributionFacts.Person;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Frozen, bounded JSON-text prompts. Source text never supplies instructions. */
@Component
public final class TemplateAnalysisPromptFactory {
    public static final String START = "<!-- TEMPLATE_ANALYSIS_JSON_START -->";
    public static final String END = "<!-- TEMPLATE_ANALYSIS_JSON_END -->";
    private static String textTransport() { return RolePromptResources.read("prompt.v1.TemplateAnalysisPromptFactory.block01"); }
    private static String rules() { return RolePromptResources.read("prompt.v1.TemplateAnalysisPromptFactory.block02"); }
    private final ObjectMapper json;

    public TemplateAnalysisPromptFactory(ObjectMapper json) { this.json = json; }

    public static String continuation(String batchId, String server, long revision) {
        return (RolePromptResources.read("prompt.v1.TemplateAnalysisPromptFactory.block03.segment0")
                + String.format("%s", (Object) (server))
                + RolePromptResources.read("prompt.v1.TemplateAnalysisPromptFactory.block03.segment1")
                + String.format("%s", (Object) (batchId))
                + RolePromptResources.read("prompt.v1.TemplateAnalysisPromptFactory.block03.segment2")
                + String.format("%d", (Object) (revision))
                + RolePromptResources.read("prompt.v1.TemplateAnalysisPromptFactory.block03.segment3"));
    }

    public String internal(String evidencePrompt, String batchId, String toolName) {
        return evidencePrompt.replace(textTransport(), "") + "\n" + ("结果必须调用 "
                + String.format("%s", (Object) (toolName))
                + RolePromptResources.read("prompt.v1.TemplateAnalysisPromptFactory.block04.segment1")
                + String.format("%s", (Object) (batchId))
                + RolePromptResources.read("prompt.v1.TemplateAnalysisPromptFactory.block04.segment2"));
    }

    public String review(List<Unit> units, String feedback) {
        StringBuilder prompt = new StringBuilder(rules() + textTransport()).append(RolePromptResources.read("prompt.v1.TemplateAnalysisPromptFactory.block05"));
        for (Unit unit : units) {
            prompt.append("\n冻结证据：").append(json.writeValueAsString(Map.of(
                    "unitId", unit.id(), "commitSha", unit.commitSha(), "evidenceId", unit.evidenceId(), "path", unit.path(),
                    "disposition", unit.disposition(), "patch", unit.patch()))).append("\n");
            if (!unit.id().endsWith(":0") && !unit.lines().isEmpty()) {
                prompt.append("该切片可引用的真实行号范围（不得从 1 重新计数）：").append(json.writeValueAsString(locations(unit))).append("\n");
            }
        }
        return bounded(prompt.append(feedback(feedback)).toString());
    }

    public String contributor(Person person, List<TemplateAnalysis.UnitReview> reviews, List<Unit> units, String feedback) {
        StringBuilder prompt = new StringBuilder(rules() + textTransport()).append((RolePromptResources.read("prompt.v1.TemplateAnalysisPromptFactory.block06.segment0")
                + String.format("%s", (Object) (json.writeValueAsString(person.author().identity())))
                + RolePromptResources.read("prompt.v1.TemplateAnalysisPromptFactory.block06.segment1")));
        prompt.append("\n内置标准：").append(json.writeValueAsString(ContributionScore.DIMENSIONS));
        prompt.append("\n程序确定的贡献身份和数量：").append(json.writeValueAsString(person));
        prompt.append("\n返回对象的 identity 字段必须逐字复制以下 JSON 字符串，不得使用姓名、邮箱、提交 SHA 或自行改写：")
                .append(json.writeValueAsString(person.author().identity()));
        prompt.append("\n本人提交文件清单（归属权威）：").append(json.writeValueAsString(units.stream()
                .map(unit -> Map.of("commitSha", unit.commitSha(), "evidenceId", unit.evidenceId(), "path", unit.path())).distinct().toList()));
        Map<String, Unit> index = units.stream().collect(java.util.stream.Collectors.toMap(Unit::id, unit -> unit));
        for (var review : reviews) {
            Unit unit = index.get(review.unitId());
            if (unit != null && person.evidenceIds().contains(unit.evidenceId())) {
                prompt.append("\n事实：").append(json.writeValueAsString(Map.of("commitSha", unit.commitSha(),
                        "evidenceId", unit.evidenceId(), "path", unit.path(), "analysis", review)));
            }
        }
        return bounded(prompt.append(feedback(feedback)).toString());
    }

    static Map<String, List<String>> locations(Unit unit) {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        for (var side : TemplateAnalysis.Side.values()) {
            var numbers = unit.lines().stream().filter(line -> line.side() == side).mapToInt(TemplateAnalysis.SourceLine::line).distinct().sorted().toArray();
            var ranges = new java.util.ArrayList<String>();
            for (int i = 0; i < numbers.length;) {
                int start = numbers[i], end = start;
                while (++i < numbers.length && numbers[i] == end + 1) end = numbers[i];
                ranges.add(start == end ? Integer.toString(start) : start + "-" + end);
            }
            result.put(side.name(), List.copyOf(ranges));
        }
        return result;
    }
    private static String feedback(String feedback) { return feedback == null || feedback.isBlank() ? "" : "\n本轮须修复的问题（标准保持原样）：\n" + feedback; }
    private static String bounded(String value) {
        if (value.length() > 180_000) throw new BadRequestException("TEMPLATE_PROMPT_LIMIT", "证据超过单次完整分析容量，请缩小范围后重试");
        return value;
    }
}
