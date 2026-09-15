package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.SnapshotReview;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Exact task/attempt/batch and managed-generation fence shared by all snapshot review tools. */
@Service
public class SnapshotReviewAccess {
    private final TemplateBatchStore batches;
    private final LoopperMapper tasks;
    private final InternalMcpRuntimeAccess runtime;
    private final ObjectMapper json;
    public SnapshotReviewAccess(TemplateBatchStore batches, LoopperMapper tasks, InternalMcpRuntimeAccess runtime, ObjectMapper json) {
        this.batches = batches; this.tasks = tasks; this.runtime = runtime; this.json = json;
    }
    public TemplateTaskBatchRow require(String id) {
        var row = batches.require(id);
        if (!SnapshotReview.batch(row.purpose()) || row.sessionId() == null || row.creationPlanJson() == null
                || !Set.of("DISPATCHING", "RUNNING").contains(row.state())) throw invalid();
        batches.requireRunning(row.taskId(), row.attemptId());
        var plan = json.readValue(row.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        var session = tasks.findSession(row.sessionId()).orElseThrow(SnapshotReviewAccess::invalid);
        var active = runtime.current().orElseThrow(SnapshotReviewAccess::invalid);
        if (plan.profile() != OpenCodeClient.SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS || !plan.managed()
                || !active.generation().equals(plan.runtimeGenerationId()) || !active.serverName().equals(plan.internalMcpServer())
                || !row.taskId().equals(session.taskId()) || !row.attemptId().equals(session.attemptId()) || session.externalSessionId() == null)
            throw invalid();
        return row;
    }
    private static ConflictException invalid() { return new ConflictException("SNAPSHOT_READ_SCOPE_INVALID", "读取不属于当前代码审查会话、任务或运行代次"); }
}
