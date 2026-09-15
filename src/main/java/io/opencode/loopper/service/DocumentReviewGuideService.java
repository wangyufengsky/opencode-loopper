package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DocumentTemplateMapper;
import io.opencode.loopper.template.DocumentModelInput;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Original-document task navigation and read-only preflight share the production submission policy. */
@Service
public class DocumentReviewGuideService {
    private final DocumentModelAccess access;
    private final DocumentTemplateCandidatePolicy policy;
    private final DocumentTemplateMapper documents;
    private final MachineCandidateSubmission submissions;
    private final ObjectMapper json;
    public DocumentReviewGuideService(DocumentModelAccess access, DocumentTemplateCandidatePolicy policy,
            DocumentTemplateMapper documents, MachineCandidateSubmission submissions, ObjectMapper json) {
        this.access = access; this.policy = policy; this.documents = documents; this.submissions = submissions; this.json = json;
    }
    public Map<String, Object> work(String id, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new BadRequestException("DOCUMENT_PAGE_INVALID", "分页范围无效，每页最多 100 条");
        var model = access.require(id, true);
        var input = policy.input(model);
        if (input.sourceRevision() < 1) throw new BadRequestException("DOCUMENT_SOURCE_REQUIRED", "该工具仅适用于原文直读评审");
        var sections = documents.workSections(model.runId(), input.sourceRevision(), model.externalSessionId(),
                json.writeValueAsString(input.sections()), offset, limit + 1);
        var page = sections.stream().limit(limit).toList();
        var entries = input.directAssessment() == null ? List.of() : input.directAssessment().entries();
        var requirements = entries.stream().skip(offset).limit(limit).map(value -> {
            var entry = (io.opencode.loopper.template.DirectDocumentAssessment.Entry) value;
            return Map.of("key", entry.assessment().requirementKey(), "title", entry.title(), "sources", entry.sources());
        }).toList();
        access.require(id, true);
        return Map.of("batchOrdinal", model.ordinal() + 1, "sourceRevision", input.sourceRevision(),
                "sections", page, "nextOffset", sections.size() > limit ? page.getLast().position() + 1 : -1,
                "existingRequirements", requirements, "requirementTotal", entries.size(),
                "assignedSectionTotal", input.sections().size(), "instruction", List.of(
                        "章节目录是冻结原文索引，不是预先编译的需求清单。已读只证明读取，不证明语义完整。",
                        "逐项判断原文要求，将来源放入 entries.sources；只有不含要求的章节才填写 skippedSections 及理由。",
                        "查看文档目录：list_requirement_documents / list_document_sections；正文：read_document_section。",
                        "查看冻结代码目录：list_requirement_code；检索：search_requirement_code；读取：read_requirement_code。",
                        "已形成条目为空时，在阅读原文和代码后形成 entries；独立复核以完整冻结输入为准。"));
    }
    public Map<String, Object> check(String id, Map<String, Object> candidate) {
        var model = access.require(id, true);
        String body = json.writeValueAsString(candidate);
        if (body.length() > 512 * 1024) throw new BadRequestException("DOCUMENT_CANDIDATE_TOO_LARGE", "预检候选超过大小限制，请按冻结批次提交");
        var run = submissions.find(id).orElseThrow();
        var decision = policy.evaluate(new CandidatePolicy.Context(id, run.scope(), run.owner(), run.candidateKind(),
                run.workflowStep(), run.sourceRevision(), run.ownerVersion(), run.contractVersion(), run.maxAttempts(), run.attemptsUsed()), body);
        access.require(id, true);
        return Map.of("valid", decision.accepted(), "problems", decision.problems(), "diagnosticsComplete", decision.diagnosticsComplete(),
                "expectedSubmissionRevision", run.version(), "accepted", false,
                "action", decision.accepted() ? "SUBMIT_CANDIDATE" : "READ_CONTRACT_AND_FIX",
                "notice", "只读预检未接受候选、未消耗提交次数；请用提交工具正式提交。相同失败不要重复预检，应先按字段问题修正。");
    }
}
