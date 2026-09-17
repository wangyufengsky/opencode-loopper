package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** The frozen historical per-commit and contributor workflow, separate from version review planning. */
@Service
public class TemplateHistoryReviewBatches {
    private final LoopperMapper mapper;
    private final TemplateTaskStateService states;
    private final TemplateRunEvidenceService evidence;
    private final TemplateReportArtifactService artifacts;
    private final TemplateBatchStore batchStore;
    private final TemplateBatchExecution batches;
    private final ObjectProvider<TaskService> tasks;
    private final ObjectMapper json;
    private final TemplateBatchAutomaticRetries retries;
    public TemplateHistoryReviewBatches(LoopperMapper mapper, TemplateTaskStateService states, TemplateRunEvidenceService evidence,
            TemplateReportArtifactService artifacts, TemplateBatchStore batchStore, TemplateBatchExecution batches,
            ObjectProvider<TaskService> tasks, ObjectMapper json, TemplateBatchAutomaticRetries retries) {
        this.mapper=mapper; this.states=states; this.evidence=evidence; this.artifacts=artifacts;
        this.batchStore=batchStore; this.batches=batches; this.tasks=tasks; this.json=json;
        this.retries=retries;
    }
    public void analyze(TaskRow task, AttemptRow attempt, TemplateTaskContractFactory.Frozen contract) {
        if (contract.spec().limits().timeoutsEnabled() && Duration.between(Instant.parse(attempt.createdAt()), Instant.now()).toSeconds() > contract.spec().limits().attemptTimeoutSeconds()) {
            states.waiting(task.id(), "TEMPLATE_ATTEMPT_TIMEOUT", "报告分析已达到本轮时限，停止确认后可重新发起任务"); return;
        }
        var run = evidence.require(task.id());
        var snapshot = evidence.read(run);
        var units = TemplateAnalysisPartitioner.units(snapshot);
        var inputBatches = TemplateAnalysisPartitioner.batches(units);
        var people = run.templateId().equals("CONTRIBUTION_REPORT")
                ? TemplateContributionFacts.people(snapshot).stream().filter(person -> !person.author().robot() && person.effectiveLines() > 0).toList()
                : List.<TemplateContributionFacts.Person>of();
        batchStore.plan(task.id(), inputBatches.size(), people.size());
        String feedback = feedback(task.id(), run.repairRound());
        var reviews = new ArrayList<TemplateAnalysis.UnitReview>();
        var reviewRows = new ArrayList<TemplateTaskBatchRow>();
        for (int index = 0; index < inputBatches.size(); index++) {
            var input = new TemplateBatchExecution.Input(inputBatches.get(index), null, List.of(), feedback);
            var batch = batch(attempt, index, "REVIEW", input, run);
            reviewRows.add(batch);
        }
        if (!advanceWindow(reviewRows, contract)) return;
        for (var row : reviewRows) reviews.addAll(json.readValue(row.outputJson(), TemplateAnalysis.BatchCandidate.class).reviews());
        var contributors = new ArrayList<TemplateAnalysis.ContributorCandidate>();
        var contributorRows = new ArrayList<TemplateTaskBatchRow>();
        if (run.templateId().equals("CONTRIBUTION_REPORT")) {
            for (int index = 0; index < people.size(); index++) {
                var person = people.get(index);
                var ownUnits = units.stream().filter(unit -> person.evidenceIds().contains(unit.evidenceId())).toList();
                var ownIds = ownUnits.stream().map(TemplateAnalysis.Unit::id).collect(java.util.stream.Collectors.toSet());
                var ownReviews = reviews.stream().filter(review -> ownIds.contains(review.unitId())).toList();
                var input = new TemplateBatchExecution.Input(ownUnits, person, ownReviews, feedback);
                var batch = batch(attempt, index, "CONTRIBUTOR", input, run);
                contributorRows.add(batch);
            }
        }
        if (!advanceWindow(contributorRows, contract)) return;
        for (var row : contributorRows) contributors.add(json.readValue(row.outputJson(), TemplateAnalysis.ContributorCandidate.class));
        artifacts.publish(attempt, new TemplateAnalysis.Accepted(List.copyOf(reviews), List.copyOf(contributors)));
        states.completeStage(attempt, "所有提交、证据片段与贡献者均已完整覆盖；评分由服务端计算；报告文件已校验");
    }

    private TemplateTaskBatchRow batch(AttemptRow attempt, int ordinal, String purpose,
                                       TemplateBatchExecution.Input input, TemplateTaskRunRow run) {
        String value = json.writeValueAsString(input);
        String hash = TemplateGitEvidenceCollector.hash(run.snapshotSha256() + "\n" + run.contractJson() + "\n" + purpose + "\n" + value);
        var row = batchStore.create(attempt, ordinal, purpose, value, hash);
        // Older task-wide stops also stopped batches that never created a session.
        if (row.state().equals("STOPPED") && row.sessionId() == null)
            return batchStore.retry(row, row.version(), true);
        return row;
    }

    private boolean advanceWindow(List<TemplateTaskBatchRow> rows, TemplateTaskContractFactory.Frozen contract) {
        if (rows.stream().allMatch(row -> row.state().equals("VALIDATED"))) return true;
        var window = retries.prepare(rows);
        var selected = TemplateBatchWindow.select(window.rows(), TemplateTaskBatchRow::state, contract.analysisConcurrency());
        for (var row : selected) {
            if (!states.task(row.taskId()).state().equals("RUNNING")) return false;
            validated(row, contract);
        }
        if (selected.isEmpty() && !window.retryPending() && !rows.isEmpty()) {
            states.waiting(rows.getFirst().taskId(), "TEMPLATE_BATCHES_FAILED",
                    "本轮独立批次已执行完毕，请选择失败批次重新触发；全部必需结果完成后继续汇总");
        }
        return false;
    }

    private boolean validated(TemplateTaskBatchRow batch, TemplateTaskContractFactory.Frozen contract) {
        if (batch.state().equals("VALIDATED")) return true;
        if (batch.state().equals("FAILED")) {
            states.waiting(batch.taskId(), "TEMPLATE_CONTENT_INVALID", "结构化报告未通过证据校验；有剩余额度时按冻结合同自动返修，耗尽后等待处理");
            return false;
        }
        if (Set.of("PREPARED", "CREATING", "DISPATCHING").contains(batch.state())
                && tasks.getObject().guardNextModelCall(batch.taskId(), "TEMPLATE_ANALYSIS").blocked()) return false;
        batches.advance(batch, contract);
        return false;
    }

    private String feedback(String taskId, int round) {
        if (round == 0) return "";
        String corrections = "修复上轮结构、覆盖或证据不足；不得改变评分标准。\n"
                + String.join("\n", batchStore.candidateRepairErrors(taskId));
        String value = mapper.listJudgeRuns(taskId).stream().filter(row -> row.reason() != null && !"PASS".equals(row.verdict()))
                .map(JudgeRunRow::reason).reduce(corrections, (left, right) -> left + "\n" + right);
        return value.substring(0, Math.min(12_000, value.length()));
    }

}
