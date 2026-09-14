package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.DocumentModelInput;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** User business answers create a new analysis revision; no existing requirement or accepted evidence is edited. */
@Service
public class DocumentRequirementClarifications {
    private final DocumentClarificationMapper clarifications;
    private final DocumentTemplateAdmission admission;
    private final DocumentTemplateModelMapper models;
    private final DocumentRequirementMapper requirements;
    private final DocumentTemplateControl controls;
    private final ObjectMapper json;
    private final DocumentSupplementMapper supplements;
    private final LoopperMapper domain;
    public DocumentRequirementClarifications(DocumentClarificationMapper clarifications, DocumentTemplateAdmission admission,
            DocumentTemplateModelMapper models, DocumentRequirementMapper requirements, DocumentTemplateControl controls, ObjectMapper json,
            DocumentSupplementMapper supplements, LoopperMapper domain) {
        this.clarifications = clarifications; this.admission = admission; this.models = models;
        this.requirements = requirements; this.controls = controls; this.json = json;
        this.supplements = supplements; this.domain = domain;
    }
    @Transactional
    public DocumentTemplateRunRow submit(String runId, Request request) {
        validate(request); var run = admission.require(runId);
        String digest = DocumentModelStore.hash(json.writeValueAsString(request));
        var replay = clarifications.request(runId, request.requestKey());
        if (replay.isPresent()) {
            if (!replay.get().requestSha256().equals(digest)) throw conflict(); return run;
        }
        if (run.version() != request.expectedVersion() || run.requirementRevision() != request.requirementRevision()
                || !run.state().equals("WAITING_INPUT") || !"DESIGNING".equals(run.resumeState())
                || !run.templateId().equals("REQUIREMENT_DEVELOPMENT") || !answerable(run)
                || !models.active(runId).isEmpty()) throw conflict();
        var contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class); controls.budget(run, contract);
        if (run.requirementRevision() >= contract.maxTaskAttempts())
            throw new BadRequestException("DOCUMENT_CLARIFICATION_BUDGET_EXHAUSTED", "需求澄清轮次预算已耗尽，请保留已有清单后重新发起");
        var answers = new ArrayList<>(answers(runId, run.requirementRevision()));
        for (var answer : request.answers()) {
            var requirement = requirements.item(runId, run.requirementRevision(), answer.requirementKey()).orElseThrow(DocumentRequirementClarifications::conflict);
            var issues = List.of(json.readValue(requirement.issuesJson(), String[].class));
            if (issues.isEmpty()) throw new BadRequestException("DOCUMENT_CLARIFICATION_NO_ISSUE", "此入口只处理需求清单中的业务待决；范围变更需要修订设计");
            answers.add(new DocumentModelInput.Clarification(run.requirementRevision(), answer.requirementKey(), requirement.statement(), issues, answer.answer()));
        }
        String encoded = json.writeValueAsString(answers);
        if (encoded.length() > 64000) throw new BadRequestException("DOCUMENT_CLARIFICATION_TOO_LARGE", "澄清记录超过本次处理上限，请拆分需求任务");
        int next = run.requirementRevision() + 1;
        if (clarifications.insert(new DocumentClarificationMapper.Revision(runId, next, run.requirementRevision(), request.requestKey(), digest,
                encoded, Instant.now().toString())) != 1) throw conflict();
        return admission.transition(run, DocumentTemplateState.ANALYZING, LifecycleEvent.ANALYZE_DOCUMENT_REQUIREMENTS, null, null);
    }
    private boolean answerable(DocumentTemplateRunRow run) {
        if (run.designerId() == null && run.taskId() == null) return true;
        var pending = supplements.pending(run.id()).orElse(null);
        return pending != null && pending.planId() == null && pending.uploadReady() == 1
                && (run.taskId() == null || domain.findTask(run.taskId()).map(task -> task.version() == pending.baseTaskVersion()).orElse(false));
    }
    public int round(String runId) { return clarifications.latest(runId).map(DocumentClarificationMapper.Revision::revision).orElse(1); }
    public List<DocumentModelInput.Clarification> answers(String runId, int revision) {
        var run = admission.require(runId);
        if (revision < 1 || revision > run.requirementRevision() + 1) throw conflict();
        return clarifications.revision(runId, revision).map(value -> List.of(json.readValue(value.answersJson(), DocumentModelInput.Clarification[].class)))
                .orElse(List.of());
    }
    private static void validate(Request request) {
        if (request == null || request.requestKey() == null || !request.requestKey().matches("[A-Za-z0-9_-]{16,100}")
                || request.expectedVersion() < 0 || request.requirementRevision() < 1 || request.answers() == null
                || request.answers().isEmpty() || request.answers().size() > 20)
            throw new BadRequestException("DOCUMENT_CLARIFICATION_INVALID", "请提交本版需求的业务回答，每次最多 20 项");
        var keys = new HashSet<String>();
        for (var answer : request.answers()) if (answer == null || answer.requirementKey() == null
                || !answer.requirementKey().matches("RQ-[1-9][0-9]*") || !keys.add(answer.requirementKey())
                || answer.answer() == null || answer.answer().isBlank() || answer.answer().length() > 4000)
            throw new BadRequestException("DOCUMENT_CLARIFICATION_INVALID", "回答需要对应唯一需求编号，且每项不超过 4000 字符");
    }
    private static ConflictException conflict() { return new ConflictException("DOCUMENT_CLARIFICATION_CONFLICT", "需求或处理状态已变化，请刷新待处理项后重试；关联设计后的问题请在设计详情处理"); }
    public record Request(String requestKey, long expectedVersion, int requirementRevision, List<Answer> answers) { }
    public record Answer(String requirementKey, String answer) { }
}
