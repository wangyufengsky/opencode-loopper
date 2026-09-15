package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Windowed source-to-code analysis followed by independent source-and-code verification. */
@Service
public final class DirectDocumentReviewWorkflow {
    private final DocumentTemplateMapper documents;
    private final DocumentTemplateModelMapper models;
    private final DocumentModelStore store;
    private final DocumentModelExecution execution;
    private final DocumentTemplateAdmission admission;
    private final DocumentAssessmentMapper progress;
    private final DirectDocumentReviewLedger ledger;
    private final ObjectMapper json;
    public DirectDocumentReviewWorkflow(DocumentTemplateMapper documents, DocumentTemplateModelMapper models,
            DocumentModelStore store, DocumentModelExecution execution, DocumentTemplateAdmission admission,
            DocumentAssessmentMapper progress, DirectDocumentReviewLedger ledger, ObjectMapper json) {
        this.documents = documents; this.models = models; this.store = store; this.execution = execution;
        this.admission = admission; this.progress = progress; this.ledger = ledger; this.json = json;
    }
    public boolean advance(DocumentTemplateRunRow run, DocumentTemplateService.Contract contract) {
        if (run.requirementRevision() > 0) return true;
        progress.createProgress(run.id()); var round = progress.progress(run.id()).orElseThrow();
        var plan = plan(run);
        boolean review = run.state().equals("VERIFYING");
        var kind = review ? MachineCandidateKind.DOCUMENT_CODE_REVIEW_V2 : MachineCandidateKind.DOCUMENT_CODE_ASSESSMENT_V2;
        var pending = new ArrayList<Work>();
        for (int i = 0; i < plan.size(); i++) {
            var model = models.exact(run.id(), kind.name(), i, round.round()).orElse(null);
            if (model != null && model.state().equals("VALIDATED")) continue;
            if (model != null && TemplateBatchState.valueOf(model.state()).terminal()) {
                if (!"3".equals(contract.version()))
                    throw new BadRequestException("DOCUMENT_MODEL_REQUIRES_RECOVERY", "原文评审批次已停止，请从冻结输入恢复");
                // Keep this failure while the window drains the remaining independent batches.
            }
            pending.add(new Work(i, model));
        }
        if (!pending.isEmpty()) {
            for (var work : TemplateBatchWindow.select(pending, Work::state, contract.analysisConcurrency())) {
                if (work.model() != null) { execution.advance(work.model().id(), contract); continue; }
                var input = plan.get(work.ordinal());
                DirectDocumentAssessment.Candidate candidate = null; DirectDocumentAssessment.Review feedback = null;
                if (review) candidate = candidate(run.id(), work.ordinal(), round.round());
                else if (round.round() > 0) {
                    candidate = candidate(run.id(), work.ordinal(), round.round() - 1);
                    feedback = json.readValue(models.exact(run.id(), MachineCandidateKind.DOCUMENT_CODE_REVIEW_V2.name(),
                            work.ordinal(), round.round() - 1).orElseThrow().outputJson(), DirectDocumentAssessment.Review.class);
                }
                store.create(run.id(), kind, work.ordinal(), round.round(), new DocumentModelInput(input.sections(), null, null,
                        input.snapshotSha(), null, null, List.of(), candidate, feedback, run.sourceRevision(), "3".equals(run.templateVersion()) ? 1 : 0));
            }
            return false;
        }
        if (!review) {
            admission.transition(run, DocumentTemplateState.VERIFYING, LifecycleEvent.VERIFY_REQUIREMENT_ASSESSMENT, null, null);
            return false;
        }
        boolean repair = false;
        for (int i = 0; i < plan.size(); i++) {
            var result = models.exact(run.id(), kind.name(), i, round.round()).orElseThrow();
            repair |= !json.readValue(result.outputJson(), DirectDocumentAssessment.Review.class).approved();
        }
        if (repair) {
            if (round.round() + 1 >= contract.maxStageAttempts()) throw new BadRequestException("DOCUMENT_ASSESSMENT_REPAIR_EXHAUSTED",
                    "原文或代码结论复核仍需修正，已保留全部结果和未完成范围，请恢复后继续");
            ledger.nextRound(run, round); return false;
        }
        return ledger.publish(run, round.round(), plan.size());
    }
    private DirectDocumentAssessment.Candidate candidate(String run, int ordinal, int round) {
        return json.readValue(models.exact(run, MachineCandidateKind.DOCUMENT_CODE_ASSESSMENT_V2.name(), ordinal, round)
                .orElseThrow().outputJson(), DirectDocumentAssessment.Candidate.class);
    }
    List<DocumentModelInput> plan(DocumentTemplateRunRow run) {
        String snapshot = json.readValue(run.snapshotJson(), DocumentCodeSnapshotStore.Snapshot.class).sha();
        var result = new ArrayList<DocumentModelInput>();
        var refs = new ArrayList<DocumentModelInput.SectionRef>(); int characters = 0;
        for (var file : documents.sourceFiles(run.id(), run.sourceRevision())) {
            int offset = 0;
            while (true) {
                var page = documents.sections(file.id(), offset, 100);
                for (var section : page) {
                    if (!refs.isEmpty() && (characters + section.characters() > 48000 || refs.size() >= 256)) {
                        result.add(input(refs, snapshot, run.sourceRevision())); refs.clear(); characters = 0;
                    }
                    refs.add(new DocumentModelInput.SectionRef(file.id(), section.ordinal(), section.sha256()));
                    characters += section.characters();
                }
                if (page.size() < 100) break; offset = page.getLast().ordinal() + 1;
            }
        }
        if (!refs.isEmpty()) result.add(input(refs, snapshot, run.sourceRevision()));
        if (result.isEmpty()) throw new BadRequestException("DOCUMENT_SOURCE_EMPTY", "没有可读取的冻结原文");
        return List.copyOf(result);
    }
    private static DocumentModelInput input(List<DocumentModelInput.SectionRef> refs, String sha, int revision) {
        return new DocumentModelInput(List.copyOf(refs), null, null, sha, null, null, List.of(), null, null, revision);
    }
    private record Work(int ordinal, DocumentTemplateModelRow model) {
        String state() { return model == null ? "PREPARED" : model.state(); }
    }
}
