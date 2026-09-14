package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** A single local worker per intake owns remote I/O through return, including cancellation and restart recovery. */
@Service
public final class DocumentTemplateCoordinator {
    private final DocumentTemplateMapper runs;
    private final DocumentTemplateModelMapper models;
    private final DocumentModelExecution execution;
    private final DocumentTemplateControl control;
    private final DocumentAnalysisFlow analysis;
    private final ObjectProvider<DocumentDevelopmentFlow> development;
    private final ObjectMapper json;
    private final DocumentProgressEvents events;
    private final ConcurrentHashMap<String, Thread> workers = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(4);
    private volatile boolean closing;
    private String cursor = "";
    public DocumentTemplateCoordinator(DocumentTemplateMapper runs, DocumentTemplateModelMapper models,
            DocumentModelExecution execution, DocumentTemplateControl control, DocumentAnalysisFlow analysis,
            ObjectProvider<DocumentDevelopmentFlow> development, ObjectMapper json, DocumentProgressEvents events) {
        this.runs = runs; this.models = models; this.execution = execution; this.control = control;
        this.analysis = analysis; this.development = development; this.json = json;
        this.events = events;
    }
    @Scheduled(fixedDelayString = "${loopper.monitor-delay:2s}")
    void poll() {
        if (closing) return;
        var page = runs.activeAfter(cursor);
        for (String id : page) dispatch(id);
        cursor = page.size() == 100 ? page.getLast() : "";
    }
    public void dispatch(String id) {
        if (closing || !slots.tryAcquire()) return;
        var thread = Thread.ofVirtual().unstarted(() -> {
            try { checkpoint(id); }
            finally { workers.remove(id, Thread.currentThread()); slots.release(); }
        });
        if (workers.putIfAbsent(id, thread) == null) thread.start(); else slots.release();
    }
    void checkpoint(String id) {
        try { advance(id); control.healthy(id); }
        catch (RuntimeException failure) {
            var run = runs.find(id).orElseThrow();
            if (DocumentTemplateState.valueOf(run.state()).terminal() || run.state().equals("WAITING_INPUT") || run.state().equals("STOPPING")) return;
            var contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
            if (!(failure instanceof BadRequestException) && control.retryable(id, contract.sessionErrorLimit())) return;
            String code = failure instanceof SessionFailure typed ? typed.code()
                    : failure instanceof BadRequestException typed ? typed.code()
                    : failure instanceof ConflictException typed ? typed.code() : "DOCUMENT_EXECUTION_INTERRUPTED";
            String message = failure instanceof BadRequestException ? failure.getMessage()
                    : "本次处理未完成，正在核对并停止活动会话；确认停止后可从冻结输入恢复。请检查运行环境与模型配置。";
            if (run.templateId().equals("REQUIREMENT_DEVELOPMENT")
                    && java.util.Set.of("DESIGNING", "EXECUTING", "REPORTING").contains(run.state()))
                control.waitForDevelopment(id, code);
            else control.requestStop(id, false, code, message);
        }
        finally { events.publish(id); }
    }
    void advance(String id) {
        var run = runs.find(id).orElseThrow(() -> new NotFoundException("需求模板任务不存在"));
        if (DocumentTemplateState.valueOf(run.state()).terminal()) return;
        if (run.state().equals("WAITING_INPUT")) {
            if (run.templateId().equals("REQUIREMENT_DEVELOPMENT")) development.getObject().observe(run);
            return;
        }
        if (run.state().equals("STOPPING")) { stop(run); return; }
        var contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
        boolean developmentPhase = run.templateId().equals("REQUIREMENT_DEVELOPMENT")
                && java.util.Set.of("DESIGNING", "EXECUTING", "REPORTING").contains(run.state());
        if (developmentPhase) development.getObject().advance(run, contract);
        else { control.budget(run, contract); analysis.advance(run, contract); }
    }
    private void stop(DocumentTemplateRunRow run) {
        // The preceding checkpoint has returned; an interrupt alone never permits this path to overlap it.
        for (var model : models.active(run.id())) if (!execution.stop(model.id())) return;
        if (!models.active(run.id()).isEmpty()) return;
        if (run.designerId() != null || run.taskId() != null) {
            if (!development.getObject().stop(run, "CANCELLED".equals(control.intent(run.id()).stopTarget()))) return;
        }
        control.stopped(run.id());
    }
    @PreDestroy
    void close() {
        closing = true;
        workers.values().forEach(Thread::interrupt);
    }
}
