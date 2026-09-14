package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Resolves repository identity using the existing frozen checkpoint contract for later package design. */
@Service
public final class DocumentDevelopmentDirectory {
    private final LoopperMapper domain;
    private final ObjectProvider<TaskWorkspaceCheckpointService> checkpoints;
    public DocumentDevelopmentDirectory(LoopperMapper domain, ObjectProvider<TaskWorkspaceCheckpointService> checkpoints) {
        this.domain = domain; this.checkpoints = checkpoints;
    }
    public String root(AssistMapper.Owner owner) {
        if (owner.taskId() == null) return domain.findProject(owner.projectId()).orElseThrow().rootPath();
        var task = domain.findTask(owner.taskId()).orElseThrow();
        if (owner.designerId() == null) return task.worktreePath();
        var fact = domain.listPackageFactSnapshots(task.id()).stream().reduce((left, right) -> right).orElse(null);
        if (fact == null) return task.worktreePath();
        var checkpoint = domain.findTaskWorkspaceCheckpoint(fact.checkpointId()).orElseThrow();
        if (!checkpoint.taskId().equals(task.id())) throw new ConflictException("DOCUMENT_CHECKPOINT_SCOPE_INVALID", "设计快照不属于当前执行任务");
        return checkpoints.getObject().designSnapshot(task, checkpoint).toString();
    }
    public String planRoot(String session, String taskId) {
        var plan = domain.documentPlanSession(session).orElseThrow(() -> new ConflictException("DOCUMENT_PLAN_SCOPE_MISSING", "规划会话没有冻结需求来源"));
        var task = domain.findTask(taskId).orElseThrow();
        var checkpoint = domain.findTaskWorkspaceCheckpoint(plan.baseCheckpointId()).orElseThrow();
        var pack = domain.findTaskPackageRun(plan.basePackageRunId()).orElseThrow();
        if (!plan.taskId().equals(taskId) || !checkpoint.taskId().equals(taskId) || task.version() != plan.baseTaskVersion()
                || pack.version() != plan.basePackageVersion())
            throw new ConflictException("DOCUMENT_PLAN_SCOPE_CHANGED", "规划读取的任务或事实点已变化");
        return checkpoints.getObject().designSnapshot(task, checkpoint).toString();
    }
}
