package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Validates source selection and code receipts; independent review decides semantic coverage. */
@Component
public final class DirectDocumentAssessmentValidation {
    private final DocumentTemplateMapper documents;
    private final DocumentAssessmentValidation code;
    public DirectDocumentAssessmentValidation(DocumentTemplateMapper documents, DocumentAssessmentValidation code) {
        this.documents = documents; this.code = code;
    }
    public DirectDocumentAssessment.Candidate assessment(DocumentTemplateModelRow model, DocumentModelInput input,
                                                         DirectDocumentAssessment.Candidate candidate) {
        require(candidate != null && candidate.entries() != null && candidate.entries().size() <= 256
                && candidate.skippedSections() != null && candidate.skippedSections().size() <= 2048, "评审条目或未适用章节缺失、超限");
        var covered = new HashSet<DirectDocumentAssessment.Source>();
        for (var entry : candidate.entries()) {
            require(entry != null, "评审条目缺失"); text(entry.title(), 300); text(entry.statement(), 12000);
            require(entry.sources() != null && !entry.sources().isEmpty() && entry.sources().size() <= 64, "每项结论必须选择原文来源");
            require(entry.issues() != null && entry.issues().size() <= 32, "待澄清事项缺失或超限");
            entry.issues().forEach(value -> text(value, 2000));
            require(entry.assessment() != null && entry.assessment().requirementKey() != null
                    && entry.assessment().requirementKey().matches("RQ-[1-9][0-9]{0,7}"), "请使用本批稳定的 RQ 数字编号");
            int number = Integer.parseInt(entry.assessment().requirementKey().substring(3));
            require(number > model.ordinal() * 256 && number <= (model.ordinal() + 1) * 256, "条目编号不在本批分配范围内");
            for (var ref : entry.sources()) { source(model, input, ref, true); covered.add(ref); }
        }
        for (var skipped : candidate.skippedSections()) {
            require(skipped != null, "未适用章节缺失"); text(skipped.reason(), 2000);
            source(model, input, skipped.source(), true);
            require(covered.add(skipped.source()), "无需为已引用的章节重复登记未适用说明");
        }
        require(covered.containsAll(assigned(input)), "本批仍有未说明的原文章节，请继续检查或明确无法判断");
        code.assessment(model, convertedInput(model, input, candidate), converted(candidate));
        return candidate;
    }
    public DirectDocumentAssessment.Review review(DocumentTemplateModelRow model, DocumentModelInput input,
                                                   DirectDocumentAssessment.Review review) {
        var original = input.directAssessment();
        require(original != null && review != null && Objects.equals(input.snapshotSha(), review.snapshotSha()), "复核快照或输入不匹配");
        var expected = new HashSet<>(assigned(input));
        original.entries().forEach(entry -> expected.addAll(entry.sources()));
        original.skippedSections().forEach(skipped -> expected.add(skipped.source()));
        require(review.checkedSections() != null && new HashSet<>(review.checkedSections()).equals(expected)
                && review.checkedSections().size() == expected.size(), "独立复核须逐章检查原文，包括未适用说明和可能遗漏的要求");
        expected.forEach(ref -> source(model, input, ref, true));
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
            if (correction.source() != null) source(model, input, correction.source(), true);
        }
        if (review.approved()) code.review(model, convertedInput(model, input, original), new RequirementCodeAssessment.Review(
                review.snapshotSha(), true, review.reviewedRequirementKeys(), review.reviewedFindingKeys(), List.of()));
        return review;
    }
    public DocumentRequirements.Candidate requirements(DocumentTemplateModelRow model, DocumentModelInput input, DirectDocumentAssessment.Candidate candidate) {
        return new DocumentRequirements.Candidate(candidate.entries().stream().map(entry -> new DocumentRequirements.Requirement(
                entry.assessment().requirementKey(), entry.title(), "原文评审", DocumentRequirements.Kind.FUNCTION, entry.statement(),
                entry.sources().stream().map(ref -> {
                    var section = source(model, input, ref, false);
                    return new DocumentRequirements.Source(ref.fileId(), ref.section(), section.content());
                }).toList(), List.of(), entry.issues())).toList(), List.of());
    }
    public RequirementCodeAssessment.Candidate converted(DirectDocumentAssessment.Candidate candidate) {
        return new RequirementCodeAssessment.Candidate(candidate.snapshotSha(), candidate.entries().stream()
                .map(DirectDocumentAssessment.Entry::assessment).toList(), candidate.findings(), candidate.limitations());
    }
    private DocumentModelInput convertedInput(DocumentTemplateModelRow model, DocumentModelInput input, DirectDocumentAssessment.Candidate candidate) {
        return new DocumentModelInput(input.sections(), requirements(model, input, candidate), null,
                input.snapshotSha(), converted(candidate), null);
    }
    private DocumentTemplateMapper.Section source(DocumentTemplateModelRow model, DocumentModelInput input, DirectDocumentAssessment.Source ref, boolean read) {
        require(ref != null && input.sourceRevision() > 0, "原文版本或位置缺失");
        var page = documents.sourceSections(model.runId(), input.sourceRevision(), ref.fileId(), ref.section(), 1);
        require(page.size() == 1 && page.getFirst().ordinal() == ref.section(), "引用不属于本次冻结原文版本");
        var section = page.getFirst(); require(DocumentModelStore.hash(section.content()).equals(section.sha256()), "原文内容哈希不符");
        if (read) require(documents.sourceRead(model.externalSessionId(), model.runId(), input.sourceRevision(), ref.fileId(), ref.section(), section.sha256()),
                "须由当前角色实际读取引用原文，其他角色的读取不构成本次证据");
        return section;
    }
    private static Set<DirectDocumentAssessment.Source> assigned(DocumentModelInput input) {
        require(input.sections() != null && !input.sections().isEmpty(), "分配的原文章节缺失");
        return input.sections().stream().map(ref -> new DirectDocumentAssessment.Source(ref.fileId(), ref.section())).collect(java.util.stream.Collectors.toSet());
    }
    private static void exact(List<String> values, Set<String> expected) {
        require(values != null && values.size() == expected.size() && new HashSet<>(values).equals(expected), "复核条目或问题集合不完整");
    }
    private static void text(String value, int max) { require(value != null && !value.isBlank() && value.length() <= max, "说明缺失或超限"); }
    private static void require(boolean value, String message) { if (!value) throw new BadRequestException("DOCUMENT_DIRECT_ASSESSMENT_INVALID", message); }
}
