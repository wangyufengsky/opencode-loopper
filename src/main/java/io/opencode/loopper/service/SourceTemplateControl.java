package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Versioned actions persist stop intent before attempting any external cancellation. */
@Service
public class SourceTemplateControl {
    private final SourceTemplateAdmission admission;
    private final SourceTemplateMapper runs;
    private final SourceTemplateModelMapper models;
    private final SourceControlMapper controls;
    private final SourceModelStore store;
    private final ObjectMapper json;
    private final LoopperMapper tasks;
    private final WorkspaceLeaseReconciliationService leases;
    public SourceTemplateControl(SourceTemplateAdmission admission, SourceTemplateMapper runs, SourceTemplateModelMapper models,
            SourceControlMapper controls, SourceModelStore store, ObjectMapper json, LoopperMapper tasks, WorkspaceLeaseReconciliationService leases) {
        this.admission = admission; this.runs = runs; this.models = models; this.controls = controls; this.store = store; this.json = json;
        this.tasks = tasks; this.leases = leases;
    }
    @Transactional
    public SourceTemplateRunRow command(String id, String action, Command command) {
        if (command == null) throw invalid();
        SourceTemplateAdmission.validateCommand(new SourceTemplateRequests.Command(command.requestKey(), command.expectedVersion()));
        if (!Set.of("cancel", "resume", "retry", "archive", "unarchive").contains(action)) throw invalid();
        String digest = DocumentModelStore.hash(action + json.writeValueAsString(command));
        var run = admission.require(id);
        var replay = runs.command(id, command.requestKey());
        if (replay.isPresent()) {
            if (!replay.get().equals(digest)) throw SourceTemplateAdmission.conflict();
            return run;
        }
        if (run.version() != command.expectedVersion()) throw SourceTemplateAdmission.conflict();
        switch (action) {
            case "cancel" -> requestStop(id, true, "SOURCE_CANCEL_REQUESTED", "等待全部模型和关联执行停止后取消");
            case "resume", "retry" -> resume(run, command.modelIds(), action.equals("retry"));
            default -> {
                if (!SourceTemplateState.valueOf(run.state()).terminal() || action.equals("archive") && !archivable(run))
                    throw new BadRequestException("SOURCE_ARCHIVE_UNAVAILABLE", "任务尚未完成处置，请先处理关联执行");
                if (runs.archive(id, run.version(), action.equals("archive") ? 1 : 0, Instant.now().toString()) != 1)
                    throw SourceTemplateAdmission.conflict();
                if (run.taskId() != null) {
                    if (action.equals("archive")) tasks.archiveTask(run.taskId(), Instant.now().toString());
                    else tasks.restoreTask(run.taskId());
                }
            }
        }
        if (runs.commandRecord(id, command.requestKey(), digest, Instant.now().toString()) != 1) throw SourceTemplateAdmission.conflict();
        return admission.require(id);
    }
    @Transactional
    public void requestStop(String id, boolean cancel, String code, String message) {
        var run = admission.require(id);
        if (SourceTemplateState.valueOf(run.state()).terminal()) return;
        if (run.state().equals("STOPPING") && ("CANCELLED".equals(run.stopTarget()) || !cancel)) return;
        String resume = Set.of("STOPPING", "WAITING_INPUT").contains(run.state()) ? run.resumeState() : run.state();
        if (runs.stopIntent(id, run.version(), cancel ? "CANCELLED" : "WAITING_INPUT", resume, code, message,
                Instant.now().toString()) != 1) throw SourceTemplateAdmission.conflict();
        if (!run.state().equals("STOPPING"))
            admission.transition(admission.require(id), SourceTemplateState.STOPPING, resume, code, message);
    }
    @Transactional
    public void stopped(String id) {
        var run = admission.require(id);
        if (!run.state().equals("STOPPING") || !models.active(id).isEmpty() || run.stopTarget() == null) throw SourceTemplateAdmission.conflict();
        admission.transition(run, "CANCELLED".equals(run.stopTarget()) ? SourceTemplateState.CANCELLED : SourceTemplateState.WAITING_INPUT,
                run.resumeState(), run.waitingReasonCode(), run.waitingMessage());
    }
    private void resume(SourceTemplateRunRow run, List<String> selected, boolean selective) {
        if (!run.state().equals("WAITING_INPUT") || run.resumeState() == null || !models.active(run.id()).isEmpty())
            throw SourceTemplateAdmission.conflict();
        var contract = json.readValue(run.contractJson(), SourceTemplateContract.class);
        budget(run, contract);
        controls.initialize(run.id());
        if (controls.recover(run.id(), contract.maxTaskAttempts()) != 1)
            throw new BadRequestException("SOURCE_RECOVERY_BUDGET_EXHAUSTED", "恢复预算已耗尽，原输入和已完成结果仍保留");
        var next = admission.transition(run, SourceTemplateState.valueOf(run.resumeState()), null, null, null);
        if (Set.of("WRITING", "REVIEWING").contains(next.state())) {
            int generation = models.progress(run.id()).orElseThrow().generation();
            String kind = next.state().equals("WRITING") ? "SOURCE_DETAILED_DESIGN_V1" : "SOURCE_DESIGN_REVIEW_V1";
            var failed = models.current(run.id(), kind, generation).stream()
                    .filter(m -> Set.of("FAILED", "STOPPED").contains(m.state())).toList();
            if (selective && (selected == null || selected.isEmpty()
                    || !failed.stream().map(SourceTemplateModelRow::id).toList().containsAll(selected))) throw invalid();
            for (var model : failed) if (!selective || selected.contains(model.id())) store.retry(model.id(), model.version(), null);
        } else if (selective) throw invalid();
    }
    public void budget(SourceTemplateRunRow run, SourceTemplateContract contract) {
        if (contract.timeoutEnabled() && models.elapsedMillis(run.id(), Instant.now().toString()) >= contract.maxDurationSeconds() * 1000)
            throw new BadRequestException("SOURCE_DURATION_EXHAUSTED", "本次执行总预算已耗尽，未完成项已保留");
    }
    public boolean retryable(String id, int limit) {
        controls.initialize(id); controls.error(id); return controls.errors(id) < Math.max(1, limit);
    }
    public void healthy(String id) { controls.healthy(id); }
    public boolean archivable(SourceTemplateRunRow run) {
        return SourceTemplateState.valueOf(run.state()).terminal() && (run.taskId() == null
                || tasks.findTask(run.taskId()).filter(t -> TaskState.valueOf(t.state()).terminal()).isPresent() && !leases.ownsActiveLease(run.taskId()));
    }
    public boolean resumable(SourceTemplateRunRow run) {
        var contract = json.readValue(run.contractJson(), SourceTemplateContract.class);
        return run.archived() == 0 && run.state().equals("WAITING_INPUT") && run.resumeState() != null
                && models.active(run.id()).isEmpty() && controls.recoveries(run.id()) < contract.maxTaskAttempts()
                && (!contract.timeoutEnabled() || models.elapsedMillis(run.id(), Instant.now().toString()) < contract.maxDurationSeconds() * 1000);
    }
    private static BadRequestException invalid() { return new BadRequestException("SOURCE_COMMAND_INVALID", "操作或重试范围无效，请刷新后重试"); }
    public record Command(String requestKey, long expectedVersion, List<String> modelIds) { }
}
