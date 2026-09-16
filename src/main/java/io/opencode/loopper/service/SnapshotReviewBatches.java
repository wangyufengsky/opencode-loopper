package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Scheduling and transport reuse with immutable snapshot-specific inputs and validated, scope-bound result reuse. */
@Service
public class SnapshotReviewBatches {
    private final TemplateBatchStore store;
    private final TemplateBatchExecution execution;
    private final TemplateTaskMapper batches;
    private final TemplateTaskStateService states;
    private final ObjectProvider<TaskService> tasks;
    private final ObjectMapper json;
    private final SnapshotReviewStore snapshots;
    public SnapshotReviewBatches(TemplateBatchStore store, TemplateBatchExecution execution, TemplateTaskMapper batches,
            TemplateTaskStateService states, ObjectProvider<TaskService> tasks, ObjectMapper json, SnapshotReviewStore snapshots) {
        this.store = store; this.execution = execution; this.batches = batches; this.states = states; this.tasks = tasks; this.json = json; this.snapshots = snapshots;
    }
    public TemplateTaskBatchRow create(AttemptRow attempt, int ordinal, String purpose, SnapshotReview.Input input) {
        String value = json.writeValueAsString(new TemplateBatchExecution.Input(List.of(), null, List.of(), "", input));
        String digest = TemplateGitEvidenceCollector.hash(attempt.taskId() + "\n" + value);
        var previous = batches.findBatchOrdinal(attempt.taskId(), attempt.id(), purpose, ordinal).orElse(null);
        if (previous != null && !previous.inputSha256().equals(digest)) throw new ConflictException("SNAPSHOT_BATCH_CHANGED", "冻结批次输入发生变化");
        var row = previous == null ? store.create(attempt, ordinal, purpose, value, digest) : previous;
        if (row.state().equals("STOPPED") && row.sessionId() == null) return store.retry(row, row.version(), true);
        return row;
    }
    @org.springframework.transaction.annotation.Transactional
    public TemplateTaskBatchRow supplement(AttemptRow attempt, int ordinal, SnapshotReview.Input input) {
        boolean exists = batches.findBatchOrdinal(attempt.taskId(), attempt.id(), "SNAPSHOT_SUPPLEMENT", ordinal).isPresent();
        var row = create(attempt, ordinal, "SNAPSHOT_SUPPLEMENT", input);
        if (!exists) snapshots.supplement(attempt.taskId(), input, input.objective());
        return row;
    }
    public List<TemplateTaskBatchRow> rows(String taskId, AttemptRow attempt) {
        Map<String, TemplateTaskBatchRow> current = new LinkedHashMap<>();
        for (var row : batches.batches(taskId, attempt.id())) current.merge(row.purpose() + ":" + row.ordinal(), row,
                (left, right) -> left.generation() > right.generation() ? left : right);
        return List.copyOf(current.values());
    }
    public boolean advance(String taskId, List<TemplateTaskBatchRow> rows, TemplateTaskContractFactory.Frozen contract, boolean waitOnFailure) {
        if (rows.stream().allMatch(row -> row.state().equals("VALIDATED"))) return true;
        var selected = TemplateBatchWindow.select(rows, TemplateTaskBatchRow::state, contract.analysisConcurrency());
        for (var row : selected) {
            if (!states.task(taskId).state().equals("RUNNING")) return false;
            if (Set.of("PREPARED", "CREATING", "DISPATCHING").contains(row.state())
                    && tasks.getObject().guardNextModelCall(taskId, "SNAPSHOT_CODE_REVIEW").blocked()) return false;
            execution.advance(row, contract);
        }
        if (waitOnFailure && selected.isEmpty() && !rows.isEmpty()) states.waiting(taskId, "TEMPLATE_BATCHES_FAILED",
                "本轮独立批次已结束，请选择失败批次重试；成功结果与冻结版本保持不变");
        return false;
    }
    public <T> T output(TemplateTaskBatchRow row, Class<T> type) {
        if (!row.state().equals("VALIDATED") || row.outputJson() == null) throw new ConflictException("SNAPSHOT_DEPENDENCY_PENDING", "依赖批次尚未完成");
        return json.readValue(row.outputJson(), type);
    }
    public SnapshotReview.Input input(TemplateTaskBatchRow row) { return json.readValue(row.inputJson(), TemplateBatchExecution.Input.class).snapshot(); }
    public TemplateTaskBatchRow require(String id) { return store.require(id); }
}
