package io.opencode.loopper.service;

import static io.opencode.loopper.template.RequirementCodeAssessment.*;
import io.opencode.loopper.persistence.DocumentCodeMapper;
import io.opencode.loopper.persistence.DocumentTemplateModelRow;
import io.opencode.loopper.template.DocumentModelInput;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Validates exact read receipts and complete decisions; semantic correctness is independently reviewed. */
@Component
public final class DocumentAssessmentValidation {
    private final DocumentCodeMapper code;
    public DocumentAssessmentValidation(DocumentCodeMapper code) { this.code = code; }

    public Candidate assessment(DocumentTemplateModelRow model, DocumentModelInput input, Candidate candidate) {
        require(candidate != null && Objects.equals(input.snapshotSha(), candidate.snapshotSha()), "代码快照不属于冻结输入");
        var keys = keys(input);
        require(candidate.items() != null && candidate.items().size() == keys.size(), "每项需求必须恰有一个结论");
        Set<String> seen = new HashSet<>();
        for (var item : candidate.items()) {
            require(item != null && keys.contains(item.requirementKey()) && seen.add(item.requirementKey())
                    && item.conclusion() != null, "需求结论缺失、重复或引用错误");
            text(item.rationale(), 8000); text(item.testSourceCoverage(), 4000); strings(item.limitations(), 32, 2000);
            strings(item.checkedPaths(), 256, 1024);
            for (var path : item.checkedPaths()) require(code.file(model.runId(), path).isPresent(), "检查路径不在冻结代码树中");
            references(model, item.evidence());
            if (item.conclusion() != Conclusion.UNDETERMINED) {
                require(!item.evidence().isEmpty(), "确定性结论必须附实际读取过的代码证据；证据不足请选择无法判断");
            }
            if (item.conclusion() == Conclusion.NOT_IMPLEMENTED) {
                require(!item.checkedPaths().isEmpty(), "未实现结论须给出已检查范围");
                text(item.missingEntryEvidence(), 4000);
            }
            if (item.conclusion() != Conclusion.UNDETERMINED) {
                var requirement = input.requirements().requirements().stream()
                        .filter(req -> req.key().equals(item.requirementKey())).findFirst().orElseThrow();
                require(requirement.issues().isEmpty(), "存在未澄清需求时不能给出确定性实现结论");
            }
        }
        strings(candidate.limitations(), 64, 2000);
        require(candidate.findings() != null && candidate.findings().size() <= 256, "问题明细缺失或超限");
        Set<String> findingKeys = new HashSet<>(), roots = new HashSet<>();
        for (var finding : candidate.findings()) {
            require(finding != null && findingKeys.add(finding.key()) && finding.kind() != null
                    && finding.severity() != null, "问题编号重复或类型缺失");
            text(finding.key(), 64); text(finding.rootCauseKey(), 128); text(finding.title(), 300);
            text(finding.trigger(), 4000); text(finding.impact(), 4000); text(finding.recommendation(), 4000);
            strings(finding.requirementKeys(), 256, 32);
            require(!finding.requirementKeys().isEmpty() && keys.containsAll(finding.requirementKeys()), "问题必须关联本批需求");
            require(roots.add(finding.rootCauseKey()), "同根因问题应合并并保留全部需求引用");
            references(model, finding.evidence());
            require(finding.kind() != FindingKind.DEFECT || !finding.evidence().isEmpty(), "确认缺陷必须有代码证据");
        }
        return candidate;
    }

    public Review review(DocumentTemplateModelRow model, DocumentModelInput input, Review review) {
        require(input.assessment() != null && review != null && Objects.equals(input.snapshotSha(), review.snapshotSha()),
                "独立复核快照或输入缺失");
        exact(review.reviewedRequirementKeys(), keys(input), "独立复核必须覆盖所有需求结论");
        var findings = input.assessment().findings().stream().map(Finding::key).collect(Collectors.toSet());
        exact(review.reviewedFindingKeys(), findings, "独立复核必须覆盖所有问题");
        require(review.corrections() != null && review.corrections().size() <= 256, "复核修正清单缺失或超限");
        for (var correction : review.corrections()) {
            require(correction != null && (correction.requirementKey() != null || correction.findingKey() != null), "修正必须指明需求或问题");
            require(correction.requirementKey() == null || keys(input).contains(correction.requirementKey()), "复核引用了未知需求");
            require(correction.findingKey() == null || findings.contains(correction.findingKey()), "复核引用了未知问题");
            text(correction.detail(), 4000);
        }
        require(review.approved() == review.corrections().isEmpty(), "存在修正项不能批准评审结果");
        if (review.approved()) {
            // Reused references must be independently read by this review role before it approves them.
            for (var item : input.assessment().items()) references(model, item.evidence());
            for (var finding : input.assessment().findings()) references(model, finding.evidence());
        }
        return review;
    }

    private void references(DocumentTemplateModelRow model, List<CodeReference> refs) {
        require(refs != null && refs.size() <= 64, "代码引用清单缺失或超限");
        for (var ref : refs) {
            require(ref != null && ref.startLine() > 0 && ref.endLine() >= ref.startLine(), "代码行号无效");
            text(ref.quote(), 8000);
            var receipt = code.evidence(model.id(), ref.path(), ref.startLine(), ref.endLine())
                    .orElseThrow(() -> invalid("引用未由本次冻结读取证据支持"));
            require(receipt.blobSha().equals(ref.blobSha()), "代码引用内容身份发生变化");
            var lines = receipt.content().split("\n", -1);
            int first = ref.startLine() - receipt.startLine(), last = ref.endLine() - receipt.startLine();
            require(last < lines.length && String.join("\n", Arrays.copyOfRange(lines, first, last + 1)).contains(ref.quote()),
                    "摘录必须逐字存在于指定冻结代码行");
        }
    }
    private static Set<String> keys(DocumentModelInput input) {
        require(input.requirements() != null, "冻结需求缺失");
        return input.requirements().requirements().stream().map(req -> req.key()).collect(Collectors.toSet());
    }
    private static void exact(List<String> actual, Set<String> expected, String message) {
        require(actual != null && actual.size() == expected.size() && new HashSet<>(actual).equals(expected), message);
    }
    private static void strings(List<String> values, int max, int length) {
        require(values != null && values.size() <= max, "清单缺失或超限"); values.forEach(value -> text(value, length));
    }
    private static void text(String value, int max) { require(value != null && !value.isBlank() && value.length() <= max, "说明不能为空或超限"); }
    private static void require(boolean condition, String message) { if (!condition) throw invalid(message); }
    private static BadRequestException invalid(String message) { return new BadRequestException("DOCUMENT_ASSESSMENT_INVALID", message); }
}
