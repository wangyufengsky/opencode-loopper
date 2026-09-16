package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** V2 never spends model sessions on planning, empty-result review, or recursive relationship expansion. */
@Service
public class SnapshotReviewLightweightCoordinator {
    private final SnapshotReviewStore store;
    private final SnapshotReviewEvidence evidence;
    private final TemplateTaskStateService states;
    private final SnapshotReviewBatches batches;
    private final LoopperMapper tasks;
    private final SnapshotReviewReports reports;

    private final SnapshotReviewReuse reuse;
    public SnapshotReviewLightweightCoordinator(SnapshotReviewStore store, SnapshotReviewEvidence evidence,
            TemplateTaskStateService states, SnapshotReviewBatches batches, LoopperMapper tasks, SnapshotReviewReports reports, SnapshotReviewReuse reuse) {
        this.store = store; this.evidence = evidence; this.states = states;
        this.batches = batches; this.tasks = tasks; this.reports = reports; this.reuse = reuse;
    }

    public void advance(String taskId, TemplateTaskContractFactory.Frozen contract) {
        var stages = tasks.listStages(taskId);
        if (!stages.get(0).state().equals("SUCCEEDED")) {
            var attempt = states.attempt(taskId, 0); var snapshot = evidence.freeze(taskId);
            states.completeStage(attempt, "已冻结代码证据，共 " + snapshot.units().size() + " 个片段"); return;
        }
        var snapshot = store.snapshot(taskId);
        if (!stages.get(1).state().equals("SUCCEEDED")) {
            var attempt = states.attempt(taskId, 1);
            var plan = SnapshotReviewLightweightPolicy.plan(snapshot.units());
            store.plan(taskId, plan, "程序按代码容量分批，无模型规划和递归补充");
            states.completeStage(attempt, "已安排 " + plan.groups().size() + " 个分析批次，仅候选问题需要复核"); return;
        }
        var analysis = stages.get(2).state().equals("SUCCEEDED") ? tasks.latestAttempt(stages.get(2).id()).orElseThrow() : states.attempt(taskId, 2);
        analyze(taskId, snapshot, analysis, states.attempt(taskId, 3), contract);
    }

    private void analyze(String taskId, Snapshot snapshot, AttemptRow analysisAttempt, AttemptRow reviewAttempt,
                         TemplateTaskContractFactory.Frozen contract) {
        var plan = store.plan(taskId);
        var analyses = new ArrayList<TemplateTaskBatchRow>(); var reviews = new ArrayList<TemplateTaskBatchRow>();
        for (int i = 0; i < plan.groups().size(); i++) {
            var input = SnapshotReviewLightweightPolicy.analysis(snapshot.units(), plan.groups().get(i));
            var row = batches.create(analysisAttempt, i, "SNAPSHOT_ANALYSIS", input);
            row = reuse.consider(row, input, snapshot, contract);
            analyses.add(row);
            if (row.state().equals("VALIDATED")) {
                var result = batches.output(row, Analysis.class);
                if (!result.findings().isEmpty()) reviews.add(batches.create(reviewAttempt, i, "SNAPSHOT_REVIEW",
                        SnapshotReviewLightweightPolicy.review(row.id(), batches.input(row), result)));
            }
        }
        var active = new ArrayList<>(analyses); active.addAll(reviews);
        boolean done = batches.advance(taskId, active, contract, true);
        boolean analyzed = analyses.stream().allMatch(row -> row.state().equals("VALIDATED"));
        if (analyzed && !tasks.findStage(analysisAttempt.stageId()).orElseThrow().state().equals("SUCCEEDED"))
            states.completeStage(analysisAttempt, "固定分析批次已完成，无问题结论不另开复核会话");
        if (!done || !analyzed) return;
        reports.publishLightweight(taskId, reviewAttempt, snapshot, plan, analyses, reviews);
        states.completeStage(reviewAttempt, "候选问题复核与报告保存完成；无问题结论未经独立复核");
    }
}
