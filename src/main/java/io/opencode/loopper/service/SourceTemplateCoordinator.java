package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.SourceTemplateContract;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** One local worker owns the full I/O interval. Cancel waits for that worker to return. */
@Service
public final class SourceTemplateCoordinator {
    private final SourceTemplateMapper runs;
    private final SourceTemplateModelMapper models;
    private final SourceModelExecution execution;
    private final SourceTemplateControl control;
    private final List<SourceExecutionFlow> flows;
    private final SourceProgressEvents events;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, Thread> workers = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(4);
    private volatile boolean closing;
    private String cursor = "";
    public SourceTemplateCoordinator(SourceTemplateMapper runs, SourceTemplateModelMapper models,
            SourceModelExecution execution, SourceTemplateControl control, List<SourceExecutionFlow> flows,
            SourceProgressEvents events, ObjectMapper json) {
        this.runs = runs; this.models = models; this.execution = execution; this.control = control;
        this.flows = flows; this.events = events; this.json = json;
    }
    @Scheduled(fixedDelayString = "${loopper.monitor-delay:2s}")
    void poll() {
        if (closing) return;
        var page = runs.activeAfter(cursor); page.forEach(this::dispatch);
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
            if (SourceTemplateState.valueOf(run.state()).terminal() || List.of("STOPPING", "WAITING_INPUT").contains(run.state())) return;
            var contract = json.readValue(run.contractJson(), SourceTemplateContract.class);
            if (!(failure instanceof BadRequestException) && control.retryable(id, contract.sessionErrorLimit())) return;
            String code = failure instanceof BadRequestException typed ? typed.code()
                    : failure instanceof ConflictException typed ? typed.code()
                    : failure instanceof SessionFailure typed ? typed.code() : "SOURCE_EXECUTION_INTERRUPTED";
            String message = failure instanceof BadRequestException || failure instanceof ConflictException ? failure.getMessage()
                    : "处理未完成，正在核对停止状态；确认停止后可从冻结输入恢复，请检查模型与运行环境";
            control.requestStop(id, false, code, message);
        } finally { events.publish(id); }
    }
    void advance(String id) {
        var run = runs.find(id).orElseThrow();
        if (SourceTemplateState.valueOf(run.state()).terminal() || List.of("PENDING_START", "WAITING_INPUT").contains(run.state())) return;
        if (run.state().equals("STOPPING")) {
            for (var model : models.active(id)) if (!execution.stop(model.id())) return;
            if (!models.active(id).isEmpty()
                    || (run.designerId() != null || run.taskId() != null) && !flow(run).stop(run)) return;
            control.stopped(id); return;
        }
        var contract = json.readValue(run.contractJson(), SourceTemplateContract.class);
        control.budget(run, contract); flow(run).advance(run, contract);
    }
    private SourceExecutionFlow flow(SourceTemplateRunRow run) {
        return flows.stream().filter(f -> f.supports(run.templateId())).findFirst()
                .orElseThrow(() -> new BadRequestException("SOURCE_FLOW_UNAVAILABLE", "该模板执行器不可用，请检查服务版本"));
    }
    @PreDestroy
    void close() { closing = true; workers.values().forEach(Thread::interrupt); }
}
