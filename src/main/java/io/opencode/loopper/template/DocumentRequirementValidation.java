package io.opencode.loopper.template;

import static io.opencode.loopper.template.DocumentRequirements.*;
import io.opencode.loopper.service.BadRequestException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Checks source identity and closed coverage. Semantic completeness still requires an independent review. */
public final class DocumentRequirementValidation {
    private DocumentRequirementValidation() { }

    public static Candidate extraction(List<SourceSection> expected, Candidate candidate) {
        var source = sources(expected);
        require(candidate != null && candidate.requirements() != null && candidate.coverage() != null, "候选必须包含需求和分段覆盖清单");
        require(candidate.requirements().size() <= 256, "单批需求超过 256 项，请按原分段拆分处理");
        Set<String> keys = new HashSet<>();
        for (var requirement : candidate.requirements()) {
            require(requirement != null && requirement.key() != null && requirement.key().matches("RQ-[1-9][0-9]{0,5}")
                    && keys.add(requirement.key()), "需求编号无效或重复");
            text(requirement.title(), 300, "需求标题"); text(requirement.group(), 300, "功能组");
            text(requirement.statement(), 12000, "需求正文");
            require(requirement.kind() != null, "需求类型缺失");
            validateSources(source, requirement.sources());
            texts(requirement.acceptance(), 32, 4000, "验收场景");
            texts(requirement.issues(), 32, 2000, "待澄清项");
        }
        coverage(source, keys, candidate.coverage());
        Map<String, Requirement> byKey = candidate.requirements().stream()
                .collect(Collectors.toMap(Requirement::key, Function.identity()));
        for (var item : candidate.coverage()) {
            for (String key : item.requirementKeys()) {
                require(byKey.get(key).sources().stream().anyMatch(ref -> ref.fileId().equals(item.fileId())
                        && ref.section() == item.section()), "分段覆盖必须由该需求的真实原文引用支持："
                                + key + " 在 " + identity(item.fileId(), item.section()) + " 的 coverage 中，但 sources 缺少该分段");
            }
        }
        for (var requirement : candidate.requirements()) {
            for (var ref : requirement.sources()) {
                require(candidate.coverage().stream().anyMatch(item -> Objects.equals(item.fileId(), ref.fileId())
                        && item.section() == ref.section() && item.requirementKeys().contains(requirement.key())),
                        "需求原文引用缺少对应分段归属：" + requirement.key() + " 引用了 "
                                + identity(ref.fileId(), ref.section()) + "，须在该段 REQUIREMENT coverage 中关联此编号");
            }
        }
        return candidate;
    }

    public static Review review(List<SourceSection> expected, Candidate extraction, Review review) {
        extraction(expected, extraction);
        var source = sources(expected);
        var keys = extraction.requirements().stream().map(Requirement::key).collect(Collectors.toSet());
        require(review != null && review.reviewedRequirementKeys() != null && review.corrections() != null,
                "独立复核必须包含已复核需求和修正清单");
        require(review.reviewedRequirementKeys().size() == keys.size()
                && new HashSet<>(review.reviewedRequirementKeys()).equals(keys), "独立复核必须恰好覆盖全部需求");
        coverage(source, keys, review.coverage());
        require(review.corrections().size() <= 256, "复核修正清单超限");
        for (var correction : review.corrections()) {
            require(correction != null && (correction.requirementKey() == null || keys.contains(correction.requirementKey())),
                    "修正引用了不存在的需求");
            require(correction.category() != null && Set.of("OMISSION", "UNSUPPORTED", "WRONG_MERGE", "CONFLICT", "INTERPRETATION").contains(correction.category()),
                    "复核问题类型无效");
            text(correction.detail(), 4000, "修正说明"); validateSources(source, correction.sources());
        }
        require(review.approved() == review.corrections().isEmpty(), "复核存在修正项时不能批准需求清单");
        return review;
    }

    public static void validateSources(Map<String, SourceSection> source, List<Source> refs) {
        require(refs != null && !refs.isEmpty() && refs.size() <= 64, "必须提供 1–64 个冻结原文引用");
        for (var ref : refs) {
            require(ref != null, "原文引用缺失");
            var section = source.get(identity(ref.fileId(), ref.section()));
            require(section != null, "原文引用不属于当前冻结批次");
            text(ref.quote(), 4000, "原文摘录");
            require(section.text().contains(ref.quote()), "原文摘录必须逐字存在于引用分段，不得改写："
                    + identity(ref.fileId(), ref.section()) + "；请引用解码后的原文，勿把 JSON 转义反斜杠当成原文");
        }
    }

    private static void coverage(Map<String, SourceSection> source, Set<String> keys, List<Coverage> coverage) {
        require(coverage != null && coverage.size() == source.size(), "每个输入分段必须恰好有一条处理归属");
        Set<String> seen = new HashSet<>(), covered = new HashSet<>();
        for (var item : coverage) {
            require(item != null && source.containsKey(identity(item.fileId(), item.section()))
                    && seen.add(identity(item.fileId(), item.section())), "覆盖清单包含重复或未知分段");
            require(item.disposition() != null && item.requirementKeys() != null
                    && keys.containsAll(item.requirementKeys()), "分段归属引用了不存在的需求");
            require(new HashSet<>(item.requirementKeys()).size() == item.requirementKeys().size(), "分段需求引用重复");
            require((item.disposition() == Disposition.REQUIREMENT) == !item.requirementKeys().isEmpty(),
                    "需求分段必须关联需求；背景和局限不得携带需求归属：" + identity(item.fileId(), item.section()));
            text(item.reason(), 2000, "分段归属说明"); covered.addAll(item.requirementKeys());
        }
        require(covered.equals(keys), "存在未关联任何分段的需求");
    }
    private static Map<String, SourceSection> sources(List<SourceSection> expected) {
        require(expected != null && !expected.isEmpty(), "冻结输入分段缺失");
        return expected.stream().collect(Collectors.toMap(s -> identity(s.fileId(), s.section()), Function.identity()));
    }
    private static String identity(String fileId, int section) { return fileId + ":" + section; }
    private static void texts(List<String> values, int maximum, int length, String label) {
        require(values != null && values.size() <= maximum, label + "清单缺失或超限");
        values.forEach(value -> text(value, length, label));
    }
    private static void text(String value, int length, String label) {
        require(value != null && !value.isBlank() && value.length() <= length, label + "不能为空或超限");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new BadRequestException("DOCUMENT_REQUIREMENT_CANDIDATE_INVALID", message);
    }
}
