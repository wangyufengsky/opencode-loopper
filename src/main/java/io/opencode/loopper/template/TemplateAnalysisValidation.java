package io.opencode.loopper.template;

import io.opencode.loopper.template.TemplateAnalysis.BatchCandidate;
import io.opencode.loopper.template.TemplateAnalysis.ContributorCandidate;
import io.opencode.loopper.template.TemplateAnalysis.SourceLine;
import io.opencode.loopper.template.TemplateAnalysis.Unit;
import io.opencode.loopper.template.TemplateAnalysis.UnitReview;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Structural, attribution and source-address verification before independent semantic review. */
public final class TemplateAnalysisValidation {
    private TemplateAnalysisValidation() { }

    public static List<UnitReview> batch(List<Unit> expected, BatchCandidate candidate) {
        Map<String, Unit> units = expected.stream().collect(Collectors.toMap(Unit::id, Function.identity()));
        if (candidate == null || candidate.reviews() == null || candidate.reviews().size() != units.size()) {
            throw invalid("报告必须逐项覆盖本批全部证据");
        }
        Set<String> seen = new HashSet<>();
        for (UnitReview review : candidate.reviews()) {
            if (review == null || !seen.add(review.unitId()) || !units.containsKey(review.unitId())) throw invalid("报告包含重复或未知证据编号");
            text(review.summary(), 4000, "提交分析");
            if (review.findings() == null || review.findings().size() > 32 || review.limitations() == null || review.limitations().size() > 32) {
                throw invalid("问题和局限说明格式无效");
            }
            for (var finding : review.findings()) {
                if (finding == null || finding.severity() == null || finding.side() == null
                        || !units.get(review.unitId()).lines().contains(new SourceLine(finding.side(), finding.line()))) {
                    throw invalid("问题位置必须属于当前提交、当前文件和已读取的变更行");
                }
                text(finding.title(), 300, "问题标题"); text(finding.detail(), 4000, "问题证据");
                text(finding.recommendation(), 2000, "修复建议");
            }
            review.limitations().forEach(value -> text(value, 2000, "局限说明"));
        }
        return List.copyOf(candidate.reviews());
    }

    public static ContributorCandidate contributor(String identity, Set<String> evidenceIds, ContributorCandidate candidate) {
        if (candidate == null || !identity.equals(candidate.identity())) throw invalid("贡献评分身份不匹配");
        if (candidate.value() == null || candidate.difficulty() == null || candidate.quality() == null || candidate.maintenance() == null) {
            throw invalid("贡献评分必须包含全部四个维度");
        }
        text(candidate.summary(), 6000, "个人贡献概述");
        for (var assessment : List.of(candidate.value(), candidate.difficulty(), candidate.quality(), candidate.maintenance())) {
            if (!evidenceIds.containsAll(assessment.evidenceIds())) throw invalid("评分引用了其他贡献者或不存在的证据");
        }
        return candidate;
    }

    private static void text(String value, int maximum, String title) {
        if (value == null || value.isBlank() || value.length() > maximum) throw invalid(title + "不能为空或超过长度限制");
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
