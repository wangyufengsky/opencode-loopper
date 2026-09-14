package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Persisted intent precedes stopping; UI acknowledgements are replayable independently of execution. */
@Service
public class DocumentTemplateControl {
    private final DocumentControlMapper controls;
    private final DocumentTemplateAdmission admission;
    private final DocumentTemplateMapper runs;
    private final DocumentTemplateModelMapper models;
    private final DocumentModelStore store;
    private final ObjectMapper json;
    private final LoopperMapper tasks;
    private final WorkspaceLeaseReconciliationService leases;
    public DocumentTemplateControl(DocumentControlMapper controls, DocumentTemplateAdmission admission,
            DocumentTemplateMapper runs, DocumentTemplateModelMapper models, DocumentModelStore store, ObjectMapper json,
            LoopperMapper tasks, WorkspaceLeaseReconciliationService leases) {
        this.controls = controls; this.admission = admission; this.runs = runs; this.models = models; this.store = store; this.json = json;
        this.tasks = tasks; this.leases = leases;
    }
    @Transactional
    public DocumentTemplateRunRow command(String id, String action, Command request) {
        if (request == null || request.requestKey() == null || !request.requestKey().matches("[A-Za-z0-9_-]{16,100}")
                || request.expectedVersion() < 0 || !java.util.Set.of("cancel", "resume", "archive", "unarchive").contains(action))
            throw new BadRequestException("DOCUMENT_COMMAND_INVALID", "操作参数无效，请刷新后重试");
        var run = admission.require(id);
        String digest = DocumentModelStore.hash(json.writeValueAsString(Map.of("action", action, "request", request)));
        var replay = controls.command(id, request.requestKey());
        if (replay.isPresent()) {
            if (!replay.get().requestSha256().equals(digest)) throw changed();
            return run;
        }
        if (run.version() != request.expectedVersion()) throw changed();
        switch (action) {
            case "cancel" -> requestStop(id, true, "DOCUMENT_CANCEL_REQUESTED", "已请求取消，等待全部活动会话与执行停止证明");
            case "resume" -> resume(run);
            default -> {
                if (!DocumentTemplateState.valueOf(run.state()).terminal())
                    throw new BadRequestException("DOCUMENT_ARCHIVE_ACTIVE", "请先完成或取消需求任务，再归档");
                if (action.equals("archive") && run.taskId() != null) {
                    var task = tasks.findTask(run.taskId()).orElseThrow(DocumentTemplateControl::changed);
                    if (!TaskState.valueOf(task.state()).terminal() || leases.ownsActiveLease(task.id()))
                        throw new ConflictException("DOCUMENT_LINKED_TASK_NOT_ARCHIVABLE", "开发任务尚有结果处置或目录占用，请先在开发执行详情中处理，再归档");
                }
                if (runs.archive(id, run.version(), action.equals("archive") ? 1 : 0, Instant.now().toString()) != 1) throw changed();
                if (action.equals("unarchive")) controls.restoreLinkedTask(id);
                else if (run.taskId() != null) tasks.archiveTask(run.taskId(), Instant.now().toString());
            }
        }
        var result = admission.require(id);
        if (controls.insertCommand(new DocumentControlMapper.Command(id, request.requestKey(), digest, action,
                result.version(), Instant.now().toString())) != 1) throw changed();
        return result;
    }
    @Transactional
    public void waitForDevelopment(String id, String code) {
        var run = admission.require(id);
        if (!run.templateId().equals("REQUIREMENT_DEVELOPMENT")
                || !java.util.Set.of("DESIGNING", "EXECUTING", "REPORTING").contains(run.state())) return;
        // This pauses orchestration only. The existing owners retain their lifecycle and any blocking lease.
        admission.transition(run, DocumentTemplateState.WAITING_INPUT, LifecycleEvent.REQUIRE_INPUT, code,
                "需求开发协调暂未完成，请检查关联设计或执行后恢复；当前执行状态和目录占用仍由开发任务管理。");
    }
    @Transactional
    public void requestStop(String id, boolean cancel, String code, String message) {
        var run = admission.require(id);
        if (DocumentTemplateState.valueOf(run.state()).terminal()) return;
        controls.initialize(id);
        var control = controls.find(id).orElseThrow();
        if ("CANCELLED".equals(control.stopTarget()) || run.state().equals("STOPPING") && !cancel) return;
        String resume = run.state().equals("STOPPING") ? control.resumeState()
                : run.state().equals("WAITING_INPUT") ? run.resumeState() : run.state();
        if (controls.stop(id, control.version(), cancel ? "CANCELLED" : "WAITING_INPUT", resume, code, message) != 1) throw changed();
        if (!run.state().equals("STOPPING")) admission.transition(run, DocumentTemplateState.STOPPING, LifecycleEvent.CANCEL, code, message, resume);
        else if (runs.touch(id, run.version(), code, message, Instant.now().toString()) != 1) throw changed();
    }
    @Transactional
    public void stopped(String id) {
        var run = admission.require(id); var control = controls.find(id).orElseThrow();
        if (!run.state().equals("STOPPING") || !models.active(id).isEmpty() || control.stopTarget() == null) throw changed();
        boolean cancel = control.stopTarget().equals("CANCELLED");
        admission.transition(run, cancel ? DocumentTemplateState.CANCELLED : DocumentTemplateState.WAITING_INPUT,
                cancel ? LifecycleEvent.ABORT : LifecycleEvent.REQUIRE_INPUT, control.errorCode(), control.errorMessage(), control.resumeState());
    }
    private void resume(DocumentTemplateRunRow run) {
        if (!run.state().equals("WAITING_INPUT") || run.resumeState() == null || !models.active(run.id()).isEmpty()) throw changed();
        controls.initialize(run.id()); var control = controls.find(run.id()).orElseThrow();
        var contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
        if (control.recoveries() >= contract.maxTaskAttempts())
            throw new BadRequestException("DOCUMENT_RECOVERY_EXHAUSTED", "本次需求任务的恢复预算已耗尽，请保留已有报告后重新发起");
        boolean ownerRecovery = run.templateId().equals("REQUIREMENT_DEVELOPMENT")
                && java.util.Set.of("DESIGNING", "EXECUTING", "REPORTING").contains(run.resumeState());
        if (!ownerRecovery) { budget(run, contract); store.recover(run.id(), contract); }
        if (controls.recover(run.id(), control.version()) != 1) throw changed();
        var next = DocumentTemplateState.valueOf(run.resumeState());
        admission.transition(run, next, resumeEvent(next), null, null);
    }
    public void budget(DocumentTemplateRunRow run, DocumentTemplateService.Contract contract) {
        if (models.elapsedMillis(run.id(), Instant.now().toString()) >= contract.maxDurationSeconds() * 1000)
            throw new BadRequestException("DOCUMENT_DURATION_EXHAUSTED", "本次文档分析总时限已耗尽，已保存已完成范围和证据");
    }
    public void healthy(String id) { controls.clearError(id); }
    public boolean retryable(String id, int limit) {
        controls.initialize(id); controls.error(id);
        return controls.find(id).orElseThrow().consecutiveErrors() < Math.max(1, limit);
    }
    public DocumentControlMapper.Control intent(String id) { return controls.find(id).orElseThrow(); }
    private static LifecycleEvent resumeEvent(DocumentTemplateState state) {
        return switch (state) {
            case PREPARING -> LifecycleEvent.PREPARE;
            case ANALYZING -> LifecycleEvent.ANALYZE_DOCUMENT_REQUIREMENTS;
            case REVIEWING -> LifecycleEvent.REVIEW_DOCUMENT_REQUIREMENTS;
            case DESIGNING -> LifecycleEvent.DESIGN_DOCUMENT_REQUIREMENTS;
            case EXECUTING -> LifecycleEvent.EXECUTE_DOCUMENT_REQUIREMENTS;
            case ASSESSING -> LifecycleEvent.ASSESS_REQUIREMENT_CODE;
            case VERIFYING -> LifecycleEvent.VERIFY_REQUIREMENT_ASSESSMENT;
            case REPORTING -> LifecycleEvent.RENDER_REQUIREMENT_REPORT;
            default -> throw changed();
        };
    }
    private static ConflictException changed() { return new ConflictException("DOCUMENT_COMMAND_CONFLICT", "任务版本或操作身份已变化，请刷新后重试"); }
    public record Command(String requestKey, long expectedVersion) { }
}
