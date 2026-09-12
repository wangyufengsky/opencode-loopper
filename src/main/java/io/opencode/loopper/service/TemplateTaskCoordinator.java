package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Resumable fixed-template driver. Only one local worker per task may cross an external I/O boundary. */
@Service
public final class TemplateTaskCoordinator {
    private final LoopperMapper mapper;
    private final TemplateTaskMapper templates;
    private final TemplateTaskStateService states;
    private final TemplateRunEvidenceService evidence;
    private final TemplateReportArtifactService artifacts;
    private final TemplateBatchStore batchStore;
    private final TemplateBatchExecution batches;
    private final ObjectProvider<TaskService> tasks;
    private final ObjectMapper json;
    private final TemplateGitCaptureGuard gitGuard;
    private final ConcurrentHashMap<String, Thread> workers = new ConcurrentHashMap<>();
    private volatile boolean closing;

    TemplateTaskCoordinator(LoopperMapper mapper, TemplateTaskMapper templates, TemplateTaskStateService states,
            TemplateRunEvidenceService evidence, TemplateReportArtifactService artifacts, TemplateBatchStore batchStore,
            TemplateBatchExecution batches, ObjectProvider<TaskService> tasks, ObjectMapper json, TemplateGitCaptureGuard gitGuard) {
        this.mapper = mapper; this.templates = templates; this.states = states; this.evidence = evidence;
        this.gitGuard = gitGuard; this.artifacts = artifacts; this.batchStore = batchStore; this.batches = batches; this.tasks = tasks; this.json = json;
    }

    public TaskRow start(String taskId) {
        TaskRow current = states.task(taskId);
        if (!current.state().equals("PENDING_START")) {
            if (!TaskState.valueOf(current.state()).terminal()) dispatch(taskId);
            return current;
        }
        var task = states.start(taskId, evidence.contract(taskId));
        dispatch(taskId);
        return task;
    }

    @Scheduled(fixedDelayString = "${loopper.monitor-delay:2s}")
    void poll() {
        if (closing) return;
        for (String taskId : templates.activeTaskIds()) {
            if (!states.task(taskId).state().equals("STOPPING")) dispatch(taskId);
        }
    }

    public void dispatch(String taskId) {
        if (closing) return;
        Thread thread = Thread.ofVirtual().unstarted(() -> {
            try { executeCheckpoint(taskId); }
            finally { workers.remove(taskId, Thread.currentThread()); }
        });
        if (workers.putIfAbsent(taskId, thread) == null) thread.start();
    }

    void executeCheckpoint(String taskId) {
        try { advance(taskId); }
        catch (RuntimeException failure) { failed(taskId, failure); }
    }

    /** One bounded checkpoint per pass; exposed package-locally for deterministic integration tests. */
    void advance(String taskId) {
        var task = states.task(taskId);
        if (task.state().equals("STOPPING")) return;
        if (task.state().equals("WAITING_INPUT")) { repairIfEligible(task); return; }
        if (task.state().equals("AWAITING_DECISION")) { tasks.getObject().completeTemplateReport(taskId); return; }
        if (Set.of("QUEUED", "PREPARING", "READY").contains(task.state())) { states.prepare(taskId); return; }
        if (task.state().equals("JUDGING")) {
            var attempt = mapper.latestAttempt(mapper.listStages(taskId).get(1).id()).orElseThrow();
            tasks.getObject().launchRequiredJudges(task, attempt);
            return;
        }
        if (!task.state().equals("RUNNING")) return;
        var contract = evidence.contract(taskId);
        var cycle = mapper.activeTaskExecutionCycle(taskId).orElseThrow();
        if (Duration.between(Instant.parse(cycle.startedAt()), Instant.now()).toSeconds() > contract.spec().limits().maxDurationSeconds()) {
            states.waiting(taskId, "TEMPLATE_DURATION_EXHAUSTED", "已达到本轮执行时限，请查看已生成证据后重新发起任务"); return;
        }
        if (!mapper.listStages(taskId).getFirst().state().equals("SUCCEEDED")) {
            var attempt = states.attempt(taskId, 0);
            var snapshot = evidence.freeze(taskId);
            states.completeStage(attempt, "已冻结 " + snapshot.commits().size() + " 个提交");
            return;
        }
        analyze(task, states.attempt(taskId, 1), contract);
    }

    private void analyze(TaskRow task, AttemptRow attempt, TemplateTaskContractFactory.Frozen contract) {
        if (Duration.between(Instant.parse(attempt.createdAt()), Instant.now()).toSeconds() > contract.spec().limits().attemptTimeoutSeconds()) {
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
        for (int index = 0; index < inputBatches.size(); index++) {
            var input = new TemplateBatchExecution.Input(inputBatches.get(index), null, List.of(), feedback);
            var batch = batch(attempt, index, "REVIEW", input, run);
            if (!validated(batch, contract)) return;
            reviews.addAll(json.readValue(batch.outputJson(), TemplateAnalysis.BatchCandidate.class).reviews());
        }
        var contributors = new ArrayList<TemplateAnalysis.ContributorCandidate>();
        if (run.templateId().equals("CONTRIBUTION_REPORT")) {
            for (int index = 0; index < people.size(); index++) {
                var person = people.get(index);
                var ownUnits = units.stream().filter(unit -> person.evidenceIds().contains(unit.evidenceId())).toList();
                var ownIds = ownUnits.stream().map(TemplateAnalysis.Unit::id).collect(java.util.stream.Collectors.toSet());
                var ownReviews = reviews.stream().filter(review -> ownIds.contains(review.unitId())).toList();
                var input = new TemplateBatchExecution.Input(ownUnits, person, ownReviews, feedback);
                var batch = batch(attempt, index, "CONTRIBUTOR", input, run);
                if (!validated(batch, contract)) return;
                contributors.add(json.readValue(batch.outputJson(), TemplateAnalysis.ContributorCandidate.class));
            }
        }
        artifacts.publish(attempt, new TemplateAnalysis.Accepted(List.copyOf(reviews), List.copyOf(contributors)));
        states.completeStage(attempt, "所有提交、证据片段与贡献者均已完整覆盖；评分由服务端计算；报告文件已校验");
    }

    private TemplateTaskBatchRow batch(AttemptRow attempt, int ordinal, String purpose,
                                       TemplateBatchExecution.Input input, TemplateTaskRunRow run) {
        String value = json.writeValueAsString(input);
        String hash = TemplateGitEvidenceCollector.hash(run.snapshotSha256() + "\n" + run.contractJson() + "\n" + purpose + "\n" + value);
        return batchStore.create(attempt, ordinal, purpose, value, hash);
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

    private void repairIfEligible(TaskRow task) {
        String code = TaskWaitingInputPolicy.reasonCode(task, mapper);
        if (Set.of("TEMPLATE_CONTENT_INVALID", "JUDGE_CONFLICT", "JUDGE_REVIEW_NOT_APPROVED").contains(code == null ? "" : code)) {
            if (states.repair(task.id())) return;
        }
        // Budget, elapsed time, transport ambiguity and exhausted repairs never authorize a fresh model call.
        stopBatches(task.id());
    }

    private String feedback(String taskId, int round) {
        if (round == 0) return "";
        String corrections = "修复上轮结构、覆盖或证据不足；不得改变评分标准。\n"
                + String.join("\n", templates.candidateRepairErrors(taskId));
        String value = mapper.listJudgeRuns(taskId).stream().filter(row -> row.reason() != null && !"PASS".equals(row.verdict()))
                .map(JudgeRunRow::reason).reduce(corrections, (left, right) -> left + "\n" + right);
        return value.substring(0, Math.min(12_000, value.length()));
    }

    private void failed(String taskId, RuntimeException failure) {
        TaskRow task = states.task(taskId);
        if (task.state().equals("STOPPING") || TaskState.valueOf(task.state()).terminal()) return;
        String code = failure instanceof TaskFailure typed ? typed.code() : failure instanceof SessionFailure session ? session.code() : "TEMPLATE_EXECUTION_INTERRUPTED";
        String message = failure instanceof TaskFailure ? failure.getMessage() : "执行检查未完成，请查看审计证据并检查运行环境";
        states.waiting(taskId, code, message);
    }

    /** Called before generic cancellation: a cancelled Future alone is not proof its I/O has returned. */
    public boolean stopBeforeCancellation(String taskId) {
        states.requestStop(taskId);
        Thread worker = workers.get(taskId);
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            return false;
        }
        return gitGuard.stopped(taskId) && stopBatches(taskId);
    }

    private boolean stopBatches(String taskId) {
        boolean stopped = true;
        for (var attempt : mapper.listAttempts(taskId)) {
            for (var batch : templates.batches(taskId, attempt.id())) {
                try { if (!batches.stop(batch)) stopped = false; }
                catch (RuntimeException unavailable) { stopped = false; }
            }
        }
        return stopped;
    }

    void deleteBeforeAttempts(String taskId) {
        templates.deleteReportBundlesForTask(taskId);
        templates.deletePlanForTask(taskId);
        templates.deleteBatchesForTask(taskId);
        templates.deleteRunForTask(taskId);
    }

    @PreDestroy
    void close() {
        closing = true;
        workers.values().forEach(Thread::interrupt);
    }
}
