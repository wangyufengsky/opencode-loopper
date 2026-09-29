package io.opencode.loopper.service;

import io.opencode.loopper.template.*;
import java.util.*;

/** Validates source selection and code receipts; independent review decides semantic coverage. */
public final class DirectDocumentAssessmentRules {
    public record Context(int ordinal,String snapshotSha,int interactionVersion,List<DirectDocumentAssessment.Source> sections) { }
    public record Section(String content) { }
    @FunctionalInterface public interface Sources { Section read(DirectDocumentAssessment.Source source,boolean requireRead); }
    private final Sources sources;
    private final DocumentAssessmentRules code;
    public DirectDocumentAssessmentRules(Sources sources,DocumentAssessmentRules code) {this.sources=sources;this.code=code;}
    public DirectDocumentAssessment.Candidate assessment(Context input,
                                                         DirectDocumentAssessment.Candidate candidate) {
        require(candidate != null && candidate.entries() != null && candidate.entries().size() <= 256
                && candidate.skippedSections() != null && candidate.skippedSections().size() <= 2048, "评审条目或未适用章节缺失、超限");
        if (candidate.snapshotSha() == null && input.interactionVersion() >= 1)
            candidate = new DirectDocumentAssessment.Candidate(input.snapshotSha(), candidate.entries(), candidate.findings(),
                    candidate.skippedSections(), candidate.limitations());
        if (!Objects.equals(input.snapshotSha(), candidate.snapshotSha()))
            throw new DocumentCandidateProblem("/candidate/snapshotSha", "快照不匹配；新交互合同请填 null，由服务端绑定，历史合同须使用冻结输入 SHA。");
        var covered = new HashSet<DirectDocumentAssessment.Source>();
        for (var entry : candidate.entries()) {
            require(entry != null, "评审条目缺失"); text(entry.title(), 300); text(entry.statement(), 12000);
            require(entry.sources() != null && !entry.sources().isEmpty() && entry.sources().size() <= 64, "每项结论必须选择原文来源");
            require(entry.issues() != null && entry.issues().size() <= 32, "待澄清事项缺失或超限");
            entry.issues().forEach(value -> text(value, 2000));
            require(entry.assessment() != null && entry.assessment().requirementKey() != null
                    && entry.assessment().requirementKey().matches("RQ-[1-9][0-9]{0,7}"), "请使用本批稳定的 RQ 数字编号");
            int number = Integer.parseInt(entry.assessment().requirementKey().substring(3));
            require(number > input.ordinal() * 256 && number <= (input.ordinal() + 1) * 256, "条目编号不在本批分配范围内");
            for (var ref : entry.sources()) { source(input, ref, true); covered.add(ref); }
        }
        for (var skipped : candidate.skippedSections()) {
            require(skipped != null, "未适用章节缺失"); text(skipped.reason(), 2000);
            source(input, skipped.source(), true);
            require(covered.add(skipped.source()), "无需为已引用的章节重复登记未适用说明");
        }
        var missing = assigned(input).stream().filter(ref -> !covered.contains(ref))
                .sorted(java.util.Comparator.comparing(DirectDocumentAssessment.Source::fileId).thenComparingInt(DirectDocumentAssessment.Source::section)).toList();
        if (!missing.isEmpty()) throw new DocumentCandidateProblem("/candidate/entries",
                "本批未覆盖原文章节：" + missing + "。请读取并纳入 entries.sources；确无要求的章节在 skippedSections 说明，不能为过校验跳过需求。");
        for (int i = 0; i < candidate.entries().size(); i++) {
            var entry = candidate.entries().get(i);
            if (!entry.issues().isEmpty() && entry.assessment().conclusion() != RequirementCodeAssessment.Conclusion.UNDETERMINED)
                throw new DocumentCandidateProblem("/candidate/entries/" + i + "/issues",
                        "issues 表示业务规则未澄清，非空时结论必须为 UNDETERMINED；代码证据缺口应写 assessment.limitations 或 VALIDATION_GAP，不能无依据删除业务歧义。");
        }
        code.assessment(convertedInput(input, candidate), converted(candidate));
        return candidate;
    }
    public DirectDocumentAssessment.Review review(Context input, DirectDocumentAssessment.Candidate original,
                                                   DirectDocumentAssessment.Review review) {
        if (review != null && review.snapshotSha() == null && input.interactionVersion() >= 1)
            review = new DirectDocumentAssessment.Review(input.snapshotSha(), review.approved(), review.reviewedRequirementKeys(),
                    review.reviewedFindingKeys(), review.checkedSections(), review.corrections());
        require(original != null && review != null && Objects.equals(input.snapshotSha(), review.snapshotSha()), "复核快照或输入不匹配");
        var expected = new HashSet<>(assigned(input));
        original.entries().forEach(entry -> expected.addAll(entry.sources()));
        original.skippedSections().forEach(skipped -> expected.add(skipped.source()));
        require(review.checkedSections() != null && new HashSet<>(review.checkedSections()).equals(expected)
                && review.checkedSections().size() == expected.size(), "独立复核须逐章检查原文，包括未适用说明和可能遗漏的要求");
        expected.forEach(ref -> source(input, ref, true));
        var keys = original.entries().stream().map(entry -> entry.assessment().requirementKey()).collect(java.util.stream.Collectors.toSet());
        var findings = original.findings().stream().map(RequirementCodeAssessment.Finding::key).collect(java.util.stream.Collectors.toSet());
        exact(review.reviewedRequirementKeys(), keys); exact(review.reviewedFindingKeys(), findings);
        require(review.corrections() != null && review.corrections().size() <= 256
                && review.approved() == review.corrections().isEmpty(), "存在修正项不能批准");
        for (var correction : review.corrections()) {
            require(correction != null, "复核修正缺失"); text(correction.detail(), 4000);
            require(correction.requirementKey() == null || keys.contains(correction.requirementKey()), "修正引用未知条目");
            require(correction.findingKey() == null || findings.contains(correction.findingKey()), "修正引用未知问题");
            require(correction.requirementKey() != null || correction.findingKey() != null || correction.source() != null, "遗漏修正须指明原文位置");
            if (correction.source() != null) source(input, correction.source(), true);
        }
        if (review.approved()) code.review(convertedInput(input, original), new RequirementCodeAssessment.Review(
                review.snapshotSha(), true, review.reviewedRequirementKeys(), review.reviewedFindingKeys(), List.of()));
        return review;
    }
    public DocumentRequirements.Candidate requirements(Context input, DirectDocumentAssessment.Candidate candidate) {
        return new DocumentRequirements.Candidate(candidate.entries().stream().map(entry -> new DocumentRequirements.Requirement(
                entry.assessment().requirementKey(), entry.title(), "原文评审", DocumentRequirements.Kind.FUNCTION, entry.statement(),
                entry.sources().stream().map(ref -> {
                    var section = source(input, ref, false);
                    return new DocumentRequirements.Source(ref.fileId(), ref.section(), section.content());
                }).toList(), List.of(), entry.issues())).toList(), List.of());
    }
    public RequirementCodeAssessment.Candidate converted(DirectDocumentAssessment.Candidate candidate) {
        return new RequirementCodeAssessment.Candidate(candidate.snapshotSha(), candidate.entries().stream()
                .map(DirectDocumentAssessment.Entry::assessment).toList(), candidate.findings(), candidate.limitations());
    }
    private DocumentAssessmentRules.Context convertedInput(Context input, DirectDocumentAssessment.Candidate candidate) {
        return new DocumentAssessmentRules.Context(input.snapshotSha(),requirements(input,candidate),converted(candidate),true);
    }
    private Section source(Context input,DirectDocumentAssessment.Source ref,boolean read) {
        require(ref!=null,"原文位置缺失");return sources.read(ref,read);
    }
    private static Set<DirectDocumentAssessment.Source> assigned(Context input) {
        require(input.sections()!=null&&!input.sections().isEmpty(),"分配的原文章节缺失");
        return new HashSet<>(input.sections());
    }
    private static void exact(List<String> values, Set<String> expected) {
        require(values != null && values.size() == expected.size() && new HashSet<>(values).equals(expected), "复核条目或问题集合不完整");
    }
    private static void text(String value, int max) { require(value != null && !value.isBlank() && value.length() <= max, "说明缺失或超限"); }
    private static void require(boolean value, String message) { if (!value) throw new BadRequestException("DOCUMENT_DIRECT_ASSESSMENT_INVALID", message); }
}
