package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Bounded extraction batches, independent source reviews, and recoverable semantic correction rounds. */
@Service
public final class DocumentRequirementWorkflow {
    private final DocumentTemplateMapper documents;
    private final DocumentTemplateModelMapper models;
    private final DocumentRequirementMapper requirements;
    private final DocumentModelStore store;
    private final DocumentModelExecution execution;
    private final DocumentTemplateAdmission admission;
    private final DocumentRequirementLedger ledger;
    private final ObjectMapper json;
    private final DocumentRequirementClarifications clarifications;
    public DocumentRequirementWorkflow(DocumentTemplateMapper documents, DocumentTemplateModelMapper models,
            DocumentRequirementMapper requirements, DocumentModelStore store, DocumentModelExecution execution,
            DocumentTemplateAdmission admission, DocumentRequirementLedger ledger, ObjectMapper json, DocumentRequirementClarifications clarifications) {
        this.documents = documents; this.models = models; this.requirements = requirements; this.store = store;
        this.execution = execution; this.admission = admission; this.ledger = ledger; this.json = json; this.clarifications = clarifications;
    }
    public boolean advance(DocumentTemplateRunRow run, DocumentTemplateService.Contract contract) {
        var batches = plan(run.id()); int round = clarifications.round(run.id());
        for (int ordinal = 0; ordinal < batches.size(); ordinal++) {
            if (requirements.batch(run.id(), ordinal, round).isPresent()) continue;
            advanceBatch(run, ordinal, batches.get(ordinal), contract, round);
            return false;
        }
        return ledger.publish(run.id(), round, batches.size());
    }
    private void advanceBatch(DocumentTemplateRunRow run, int ordinal, List<DocumentModelInput.SectionRef> sections,
            DocumentTemplateService.Contract contract, int round) {
        int firstGeneration = Math.multiplyExact(round - 1, contract.maxStageAttempts());
        var answers = clarifications.answers(run.id(), round);
        var extraction = models.latest(run.id(), MachineCandidateKind.DOCUMENT_REQUIREMENTS_V1.name(), ordinal).orElse(null);
        if (extraction != null && extraction.generation() < firstGeneration) extraction = null;
        if (extraction == null) {
            if (!run.state().equals("ANALYZING")) {
                admission.transition(run, DocumentTemplateState.ANALYZING, LifecycleEvent.ANALYZE_DOCUMENT_REQUIREMENTS, null, null);
                return;
            }
            store.create(run.id(), MachineCandidateKind.DOCUMENT_REQUIREMENTS_V1, ordinal, firstGeneration,
                    new DocumentModelInput(sections, null, null, null, null, null, answers));
            return;
        }
        if (!extraction.state().equals("VALIDATED")) { advanceModel(extraction, contract); return; }
        if (run.state().equals("ANALYZING")) {
            admission.transition(run, DocumentTemplateState.REVIEWING, LifecycleEvent.REVIEW_DOCUMENT_REQUIREMENTS, null, null);
            return;
        }
        var review = models.exact(run.id(), MachineCandidateKind.DOCUMENT_REQUIREMENT_REVIEW_V1.name(), ordinal,
                extraction.generation()).orElse(null);
        if (review == null) {
            store.create(run.id(), MachineCandidateKind.DOCUMENT_REQUIREMENT_REVIEW_V1, ordinal, extraction.generation(),
                    new DocumentModelInput(sections, candidate(extraction), null, null, null, null, answers));
            return;
        }
        if (!review.state().equals("VALIDATED")) { advanceModel(review, contract); return; }
        var verdict = json.readValue(review.outputJson(), DocumentRequirements.Review.class);
        if (verdict.approved()) { ledger.accept(extraction.id(), review.id(), round); return; }
        if (extraction.generation() - firstGeneration + 1 >= contract.maxStageAttempts())
            throw new BadRequestException("DOCUMENT_REQUIREMENT_REPAIR_EXHAUSTED", "独立复核仍发现需求遗漏或误读，本批修正预算已耗尽，请检查待处理事项后恢复");
        store.create(run.id(), MachineCandidateKind.DOCUMENT_REQUIREMENTS_V1, ordinal, extraction.generation() + 1,
                new DocumentModelInput(sections, candidate(extraction), verdict, null, null, null, answers));
        admission.transition(admission.require(run.id()), DocumentTemplateState.ANALYZING,
                LifecycleEvent.ANALYZE_DOCUMENT_REQUIREMENTS, null, null);
    }
    private void advanceModel(DocumentTemplateModelRow row, DocumentTemplateService.Contract contract) {
        if (TemplateBatchState.valueOf(row.state()).terminal())
            throw new BadRequestException("DOCUMENT_MODEL_REQUIRES_RECOVERY", "分析批次已停止，需要从冻结输入恢复");
        execution.advance(row.id(), contract);
    }
    private DocumentRequirements.Candidate candidate(DocumentTemplateModelRow row) {
        return json.readValue(row.outputJson(), DocumentRequirements.Candidate.class);
    }
    public List<List<DocumentModelInput.SectionRef>> plan(String runId) {
        var result = new ArrayList<List<DocumentModelInput.SectionRef>>();
        var batch = new ArrayList<DocumentModelInput.SectionRef>(); int characters = 0;
        for (var file : documents.files(runId)) {
            int offset = 0;
            while (true) {
                var page = documents.sections(file.id(), offset, 100);
                for (var section : page) {
                    if (!batch.isEmpty() && (batch.size() == 6 || characters + section.characters() > 48000)) {
                        result.add(List.copyOf(batch)); batch.clear(); characters = 0;
                    }
                    batch.add(new DocumentModelInput.SectionRef(file.id(), section.ordinal(), section.sha256()));
                    characters += section.characters();
                }
                if (page.size() < 100) break;
                offset = page.getLast().ordinal() + 1;
            }
        }
        if (!batch.isEmpty()) result.add(List.copyOf(batch));
        if (result.isEmpty()) throw new BadRequestException("DOCUMENT_SOURCE_EMPTY", "没有可分析的冻结文档分段");
        return List.copyOf(result);
    }
}
