package io.opencode.loopper.template;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The model supplies supported ordinal levels; only this policy computes scores and ranking. */
public final class ContributionScore {
    public static final String VERSION = "CONTRIBUTION_SCORE_V1";
    public static final String FORMULA = "30×ln(1+L)/ln(1+Lmax)+25×价值/4+15×难度/4+20×质量/4+10×维护/4";
    public static final List<Dimension> DIMENSIONS = List.of(
            new Dimension("value", "代码价值", 25, List.of("无已确认语义贡献", "局部改进", "完整功能或修复", "跨模块能力", "有证据的关键路径改善")),
            new Dimension("difficulty", "必要技术难度", 15, List.of("机械变更", "简单局部逻辑", "多项逻辑约束", "跨模块兼容、事务或恢复", "并发、协议或复杂算法约束")),
            new Dimension("quality", "质量与验证证据", 20, List.of("已确认严重缺陷", "具体实现或验证缺口", "合理的基本实现", "相关针对性验证", "正常、边界及回归覆盖")),
            new Dimension("maintenance", "工程维护", 10, List.of("无可确认维护贡献", "命名或说明改善", "文档、配置或可维护性改善", "去重或复用", "经验证的公共能力")));

    private ContributionScore() { }

    public static List<Ranked> rank(List<Input> inputs) {
        Set<String> identities = new HashSet<>();
        for (Input input : inputs) {
            if (!identities.add(input.identity())) throw new IllegalArgumentException("贡献者身份重复");
        }
        double maximum = inputs.stream().filter(input -> !input.robot())
                .mapToDouble(Input::effectiveLines).max().orElse(0);
        List<Ranked> sorted = inputs.stream().map(input -> score(input, maximum))
                .sorted(Comparator.comparing(Ranked::robot).thenComparing(Ranked::total, Comparator.reverseOrder())
                        .thenComparing(Ranked::identity)).toList();
        List<Ranked> result = new ArrayList<>();
        BigDecimal previous = null;
        int index = 0, rank = 0;
        for (Ranked row : sorted) {
            if (!row.robot()) {
                index++;
                if (previous == null || previous.compareTo(row.total()) != 0) rank = index;
                previous = row.total();
            }
            result.add(new Ranked(row.identity(), row.displayName(), row.robot(), row.robot() ? null : rank,
                    row.quantity(), row.value(), row.difficulty(), row.quality(), row.maintenance(), row.total()));
        }
        return List.copyOf(result);
    }

    private static Ranked score(Input input, double maximum) {
        double quantity = maximum == 0 || input.robot() ? 0 : 30 * StrictMath.log1p(input.effectiveLines()) / StrictMath.log1p(maximum);
        // A generated-only or otherwise non-semantic contribution cannot gain subjective points.
        boolean semantic = input.effectiveLines() > 0 && !input.robot();
        double value = semantic ? input.value().level() * 25d / 4 : 0;
        double difficulty = semantic ? input.difficulty().level() * 15d / 4 : 0;
        double quality = semantic ? input.quality().level() * 20d / 4 : 0;
        double maintenance = semantic ? input.maintenance().level() * 10d / 4 : 0;
        return new Ranked(input.identity(), input.displayName(), input.robot(), null, rounded(quantity), rounded(value),
                rounded(difficulty), rounded(quality), rounded(maintenance),
                rounded(quantity + value + difficulty + quality + maintenance));
    }

    private static BigDecimal rounded(double value) { return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP); }

    public record Dimension(String id, String title, int weight, List<String> levels) { }
    public record Assessment(int level, String reason, List<String> evidenceIds) {
        public Assessment {
            if (level < 0 || level > 4 || reason == null || reason.isBlank() || reason.length() > 4000
                    || evidenceIds == null || evidenceIds.isEmpty() || evidenceIds.size() > 100
                    || evidenceIds.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("评分必须提供 0–4 等级、理由和有效证据编号");
            }
            evidenceIds = List.copyOf(evidenceIds);
        }
    }
    public record Input(String identity, String displayName, boolean robot, double effectiveLines,
                        Assessment value, Assessment difficulty, Assessment quality, Assessment maintenance) {
        public Input {
            if (identity == null || identity.isBlank() || displayName == null || !Double.isFinite(effectiveLines)
                    || effectiveLines < 0 || value == null || difficulty == null || quality == null || maintenance == null) {
                throw new IllegalArgumentException("贡献评分输入不完整");
            }
        }
    }
    public record Ranked(String identity, String displayName, boolean robot, Integer rank,
                         BigDecimal quantity, BigDecimal value, BigDecimal difficulty,
                         BigDecimal quality, BigDecimal maintenance, BigDecimal total) { }
}
