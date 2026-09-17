package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions bind every stop proof to the frozen batch, Session and exact request. */
@Service
public class TemplateBatchRecoveryStore {
    static final long GRACE_SECONDS = 60;
    private final TemplateBatchStore batches;
    private final TemplateTaskMapper templates;
    private final TemplateBatchRecoveryMapper recovery;
    private final TemplateCandidateSubmissionMapper receipts;

    public TemplateBatchRecoveryStore(TemplateBatchStore batches, TemplateTaskMapper templates,
            TemplateBatchRecoveryMapper recovery, TemplateCandidateSubmissionMapper receipts) {
        this.batches = batches; this.templates = templates; this.recovery = recovery; this.receipts = receipts;
    }

    @Transactional
    public TemplateBatchRecoveryMapper.Recovery accepted(TemplateTaskBatchRow expected) {
        current(expected);
        var receipt = receipts.acceptedReceipt(expected.id()).orElseThrow();
        insert(expected, "FINALIZE", "accepted:" + expected.id(), Instant.parse(receipt.createdAt()).plusSeconds(GRACE_SECONDS));
        return recovery.find(expected.id()).orElseThrow();
    }

    @Transactional
    public void request(String taskId, String batchId, String action, long version, String commandId) {
        if (action == null || !java.util.Set.of("FINALIZE", "STOP").contains(action) || commandId == null
                || !commandId.matches("[a-zA-Z0-9-]{16,80}"))
            throw new BadRequestException("TEMPLATE_RECOVERY_REQUEST_INVALID", "恢复请求无效，请刷新后重试");
        var row = batches.require(batchId);
        if (!row.taskId().equals(taskId)) throw new NotFoundException("批次不属于当前任务");
        var prior = recovery.findCommand(commandId);
        if (prior.isPresent()) {
            var old = prior.get();
            if (!old.batchId().equals(batchId) || !old.action().equals(action) || old.expectedVersion() != version) throw conflict();
            return;
        }
        if (row.version() != version) throw conflict();
        current(row, action.equals("STOP"));
        boolean accepted = receipts.accepted(batchId).isPresent();
        if (accepted != action.equals("FINALIZE")) throw new ConflictException("TEMPLATE_RECOVERY_ACTION_CHANGED",
                accepted ? "结果已接受，请选择结束会话并收尾" : "尚未接受有效结果，请选择停止此批次");
        var previous = recovery.find(batchId).orElse(null);
        if (previous != null && !previous.action().equals(action)) throw conflict();
        Instant now = Instant.now();
        insert(row, action, commandId, now);
        recovery.expedite(batchId, now.toString());
        if (recovery.insertCommand(new TemplateBatchRecoveryMapper.Command(commandId, batchId, action, version, now.toString())) != 1) throw conflict();
    }

    private void insert(TemplateTaskBatchRow row, String action, String command, Instant due) {
        recovery.insert(new TemplateBatchRecoveryMapper.Recovery(row.id(), row.sessionId(), row.promptSha256(),
                action, command, Instant.now().toString(), due.toString(), null, null, null, null));
    }

    @Transactional
    public void attempted(TemplateTaskBatchRow row, String error) {
        current(row); requireIntent(row);
        recovery.attempted(row.id(), Instant.now().toString(), error);
    }

    @Transactional
    public void proof(TemplateTaskBatchRow row, CandidateSessionTerminationProof proof) {
        current(row); requireIntent(row);
        if (recovery.find(row.id()).orElseThrow().proof() == null
                && recovery.proof(row.id(), proof.name(), Instant.now().toString()) != 1) throw conflict();
    }

    @Transactional
    public TemplateTaskBatchRow finish(TemplateTaskBatchRow row, String validated) {
        current(row);
        var intent = requireIntent(row);
        if (!CandidateSessionTerminationProof.persisted(intent.proof())) throw conflict();
        if (intent.action().equals("FINALIZE")) {
            if (receipts.accepted(row.id()).isEmpty() || validated == null) throw conflict();
            return batches.validated(row, validated, false);
        }
        return batches.candidateFailed(row, "TEMPLATE_BATCH_MANUALLY_STOPPED", "该批次已确认停止，可在其余批次结束后单独重试");
    }

    public TemplateBatchRecoveryMapper.Recovery requireIntent(TemplateTaskBatchRow row) {
        var intent = recovery.find(row.id()).orElseThrow();
        if (!Objects.equals(row.sessionId(), intent.sessionId()) || !Objects.equals(row.promptSha256(), intent.promptSha256())) throw conflict();
        return intent;
    }

    public void current(TemplateTaskBatchRow expected) {
        current(expected, false);
    }

    private void current(TemplateTaskBatchRow expected, boolean stop) {
        if (stop) batches.requireStoppable(expected.taskId(), expected.attemptId());
        else batches.requireRunning(expected.taskId(), expected.attemptId());
        var row = batches.require(expected.id());
        var latest = templates.findBatchOrdinal(row.taskId(), row.attemptId(), row.purpose(), row.ordinal()).orElseThrow();
        if (!(row.state().equals("RUNNING") || stop && row.state().equals("STOPPING")) || row.sessionId() == null || row.promptSha256() == null
                || row.version() != expected.version() || !latest.id().equals(row.id())) throw conflict();
    }

    private static ConflictException conflict() {
        return new ConflictException("TEMPLATE_RECOVERY_CONFLICT", "批次或恢复请求已变化，请刷新后重试");
    }
}
