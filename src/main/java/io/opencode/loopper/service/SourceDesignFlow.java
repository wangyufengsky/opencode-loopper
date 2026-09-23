package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Independent batches continue after proved failures; failed batches never become successful coverage. */
@Service
public final class SourceDesignFlow {
    private final SourceDesignPlan plan;
    private final SourceTemplateAdmission admission;
    private final SourceTemplateModelMapper models;
    private final SourceModelStore store;
    private final SourceModelExecution execution;
    private final SourceTemplateControl control;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public SourceDesignFlow(SourceDesignPlan plan, SourceTemplateAdmission admission, SourceTemplateModelMapper models,
            SourceModelStore store, SourceModelExecution execution, SourceTemplateControl control,
            TransactionTemplate transactions, ObjectMapper json) {
        this.plan = plan; this.admission = admission; this.models = models; this.store = store;
        this.execution = execution; this.control = control; this.transactions = transactions; this.json = json;
    }
    public void advance(SourceTemplateRunRow run, SourceTemplateContract contract) {
        var inventory = plan.prepare(run);
        int generation = models.progress(run.id()).orElseThrow().generation();
        boolean writing = run.state().equals("WRITING");
        String kind = writing ? "SOURCE_DETAILED_DESIGN_V1" : "SOURCE_DESIGN_REVIEW_V1";
        var rows = models.current(run.id(), kind, generation);
        if (rows.size() != inventory.batches().size()) throw SourceTemplateAdmission.conflict();
        for (var row : TemplateBatchWindow.select(rows, SourceTemplateModelRow::state, contract.analysisConcurrency())) {
            try { execution.advance(row.id(), contract); }
            catch (SessionFailure failure) {
                if (!failure.code().equals("SOURCE_MODEL_TIMEOUT")) throw failure;
                if (!execution.stop(row.id())) return;
            }
        }
        rows = models.current(run.id(), kind, generation);
        if (rows.stream().anyMatch(row -> !TemplateBatchState.valueOf(row.state()).terminal())) return;
        if (rows.stream().anyMatch(row -> !row.state().equals("VALIDATED"))) {
            control.requestStop(run.id(), false, "SOURCE_BATCH_FAILED", "部分批次未完成，可选择失败批次在原预算内重试；成功结果已保留");
            return;
        }
        if (writing) beginReview(run, rows);
        else completeReview(run, rows);
    }
    private void beginReview(SourceTemplateRunRow run, List<SourceTemplateModelRow> drafts) {
        transactions.executeWithoutResult(ignored -> {
            String context = DocumentModelStore.hash(json.writeValueAsString(drafts.stream()
                    .map(d -> List.of(d.ordinal(), d.outputSha256())).toList()));
            for (var draft : drafts) {
                var input = json.readValue(draft.inputJson(), SourceDesign.Input.class);
                var reviewInput = new SourceDesign.Input(input.manifestSha256(), input.paths(), input.requirements(),
                        draft.id(), draft.outputSha256(), "跨模块复核上下文：" + context);
                var previous = models.exact(run.id(), "SOURCE_DESIGN_REVIEW_V1", draft.ordinal(), draft.generation());
                if (previous.isEmpty()) store.create(run.id(), MachineCandidateKind.SOURCE_DESIGN_REVIEW_V1,
                        draft.ordinal(), draft.generation(), reviewInput);
                else if (!previous.get().inputSha256().equals(DocumentModelStore.hash(json.writeValueAsString(reviewInput))))
                    store.retry(previous.get().id(), previous.get().version(), reviewInput);
            }
            admission.transition(admission.require(run.id()), SourceTemplateState.REVIEWING, null, null, null);
        });
    }
    private void completeReview(SourceTemplateRunRow run, List<SourceTemplateModelRow> reviews) {
        var rejected = reviews.stream().filter(r -> !json.readValue(r.outputJson(), SourceDesign.Review.class).verdict().equals("PASS")).toList();
        if (rejected.isEmpty()) {
            admission.transition(admission.require(run.id()), SourceTemplateState.REPORTING, null, null, null);
            return;
        }
        transactions.executeWithoutResult(ignored -> {
            for (var review : rejected) {
                var input = json.readValue(review.inputJson(), SourceDesign.Input.class);
                var draft = store.require(input.draftModelId());
                store.retry(draft.id(), draft.version(), new SourceDesign.Input(input.manifestSha256(), input.paths(),
                        input.requirements(), null, null, review.outputJson()));
            }
            admission.transition(admission.require(run.id()), SourceTemplateState.WRITING, null, null, null);
        });
    }
}
