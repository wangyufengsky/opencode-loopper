package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable external-I/O intent. A restart cannot silently overlap an unproven previous Git process. */
@Service
public final class TemplateGitCaptureGuard {
    private final LoopperMapper mapper;
    private final TransactionTemplate transactions;
    TemplateGitCaptureGuard(LoopperMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper; this.transactions = new TransactionTemplate(manager);
    }
    public <T> T capture(String taskId, Supplier<T> operation) {
        String intent = UUID.randomUUID().toString();
        transactions.executeWithoutResult(ignored -> {
            if (!stopped(taskId)) throw new TaskFailure("TEMPLATE_GIT_STOP_UNCONFIRMED", "上次 Git 采集被进程重启中断，无法证明其已停止；保留目录和租约，不重叠执行");
            if (!mapper.findTask(taskId).orElseThrow().state().equals("RUNNING")) throw new ConflictException("TEMPLATE_TASK_CHANGED", "任务状态已改变");
            persist(intent, taskId, "TEMPLATE_GIT_CAPTURE_INTENT", intent);
        });
        boolean confirmed = true;
        try { return operation.get(); }
        catch (TaskFailure failure) {
            if (failure.code().equals("TEMPLATE_GIT_STOP_UNCONFIRMED")) confirmed = false;
            throw failure;
        } finally {
            if (confirmed) transactions.executeWithoutResult(ignored -> persist(UUID.randomUUID().toString(), taskId, "TEMPLATE_GIT_CAPTURE_STOPPED", intent));
        }
    }
    public boolean stopped(String taskId) {
        var rows = mapper.listTaskArtifacts(taskId);
        var proven = rows.stream().filter(row -> row.kind().equals("TEMPLATE_GIT_CAPTURE_STOPPED")).map(TaskArtifactRow::content).collect(java.util.stream.Collectors.toSet());
        return rows.stream().filter(row -> row.kind().equals("TEMPLATE_GIT_CAPTURE_INTENT")).allMatch(row -> proven.contains(row.id()));
    }
    private void persist(String id, String taskId, String kind, String intent) {
        mapper.insertTaskArtifact(new TaskArtifactRow(id, taskId, null, null, kind, "git-process-proof", "text/plain", intent, "{}", Instant.now().toString()));
    }
}
