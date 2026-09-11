package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TemplateTaskMapper;
import io.opencode.loopper.persistence.TemplateTaskRunRow;
import io.opencode.loopper.template.TemplateDateRange;
import io.opencode.loopper.template.TemplateGitEvidence;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Git work finishes outside SQLite transactions; publication rechecks the live owner and frozen run version. */
@Service
public final class TemplateRunEvidenceService {
    private final TemplateTaskMapper templates;
    private final LoopperMapper mapper;
    private final TemplateGitSnapshotService snapshots;
    private final TemplateGitEvidenceCollector collector;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final TemplateGitCaptureGuard guard;

    TemplateRunEvidenceService(TemplateTaskMapper templates, LoopperMapper mapper, TemplateGitSnapshotService snapshots,
                                TemplateGitEvidenceCollector collector, ObjectMapper json, PlatformTransactionManager transactionManager, TemplateGitCaptureGuard guard) {
        this.templates = templates; this.mapper = mapper; this.snapshots = snapshots;
        this.guard = guard; this.collector = collector; this.json = json; this.transactions = new TransactionTemplate(transactionManager);
    }

    public TemplateGitEvidence freeze(String taskId) {
        var run = require(taskId);
        if (run.snapshotJson() != null) return read(run);
        var task = mapper.findTask(taskId).orElseThrow();
        var branch = new ProjectBranchService.Branch(run.branchId(), run.branchLabel(), run.branchRef(), run.remoteName());
        var evidence = guard.capture(taskId, () -> {
            var repository = snapshots.freeze(task.id(), task.projectId(), branch);
            return collector.collect(repository, branch.id(), TemplateDateRange.parse(run.startDate(), run.endDate(), Clock.systemUTC()));
        });
        String text = json.writeValueAsString(evidence), digest = TemplateGitEvidenceCollector.hash(text);
        transactions.executeWithoutResult(ignored -> {
            var currentTask = mapper.findTask(taskId).orElseThrow();
            var current = require(taskId);
            if (!currentTask.state().equals("RUNNING") || currentTask.version() != task.version() || current.version() != run.version()) {
                throw new ConflictException("TEMPLATE_SNAPSHOT_OWNER_CHANGED", "Git 采集期间任务状态发生变化，旧结果未被接受");
            }
            var frozen = new TemplateTaskRunRow(run.taskId(), run.requestKey(), run.requestSha256(), run.templateId(), run.templateVersion(),
                    run.branchId(), run.branchLabel(), run.branchRef(), run.remoteName(), run.startDate(), run.endDate(), run.contractJson(),
                    text, digest, run.repairRound(), run.bypassCache(), run.createdAt(), Instant.now().toString(), run.version());
            if (templates.freezeSnapshot(frozen) != 1) throw new ConflictException("TEMPLATE_SNAPSHOT_CONFLICT", "Git 快照已被其他执行轮次更新");
        });
        return evidence;
    }

    public TemplateGitEvidence read(TemplateTaskRunRow run) {
        if (run.snapshotJson() == null || !TemplateGitEvidenceCollector.hash(run.snapshotJson()).equals(run.snapshotSha256())) {
            throw new ConflictException("TEMPLATE_SNAPSHOT_INVALID", "冻结 Git 证据不存在或校验失败");
        }
        return json.readValue(run.snapshotJson(), TemplateGitEvidence.class);
    }

    public TemplateTaskContractFactory.Frozen contract(String taskId) {
        return json.readValue(require(taskId).contractJson(), TemplateTaskContractFactory.Frozen.class);
    }
    public TemplateTaskRunRow require(String taskId) { return templates.findRun(taskId).orElseThrow(() -> new NotFoundException("模板任务不存在")); }
}
