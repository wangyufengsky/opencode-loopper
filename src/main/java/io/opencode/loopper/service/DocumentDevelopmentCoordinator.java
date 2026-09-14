package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Owns template policy and projection. Executable owners retain their lifecycle, leases and user recovery boundaries. */
@Service
public final class DocumentDevelopmentCoordinator implements DocumentDevelopmentFlow {
    private final DocumentDevelopmentPlanner planner;
    private final DocumentDevelopmentExecution execution;
    private final DocumentDevelopmentReport reports;
    private final DocumentDevelopmentStop stopping;
    private final DocumentTemplateAdmission admission;
    private final LoopperMapper domain;
    private final ObjectMapper json;
    private final DocumentSupplementReplan supplements;
    public DocumentDevelopmentCoordinator(DocumentDevelopmentPlanner planner, DocumentDevelopmentExecution execution,
            DocumentDevelopmentReport reports, DocumentDevelopmentStop stopping, DocumentTemplateAdmission admission,
            LoopperMapper domain, ObjectMapper json, DocumentSupplementReplan supplements) {
        this.planner = planner; this.execution = execution; this.reports = reports; this.stopping = stopping;
        this.admission = admission; this.domain = domain; this.json = json;
        this.supplements = supplements;
    }
    @Override public void advance(DocumentTemplateRunRow proposed, DocumentTemplateService.Contract contract) {
        var run = admission.require(proposed.id());
        if (run.version() != proposed.version()) return;
        if (!run.templateId().equals("REQUIREMENT_DEVELOPMENT") || !contract.autoDevelopment()
                || !contract.executionPolicy().equals("CURRENT_DIRECTORY")
                || !run.contractJson().equals(json.writeValueAsString(contract)))
            throw new ConflictException("DOCUMENT_DEVELOPMENT_AUTHORIZATION_CHANGED", "本次需求开发缺少冻结自动推进授权");
        try {
            var step = switch (run.state()) {
                case "DESIGNING" -> supplements.advance(run) ? DocumentDevelopmentPlanner.Step.progress() : planner.advance(run, contract);
                case "EXECUTING" -> execution.advance(run);
                case "REPORTING" -> {
                    reports.render(run);
                    admission.transition(admission.require(run.id()), DocumentTemplateState.COMPLETED, LifecycleEvent.COMPLETE, null, null);
                    yield DocumentDevelopmentPlanner.Step.progress();
                }
                default -> DocumentDevelopmentPlanner.Step.progress();
            };
            if (step.waiting()) waitForOwner(run, "DOCUMENT_DEVELOPMENT_WAIT", step.message());
        } catch (RuntimeException failure) {
            String code = failure instanceof BadRequestException typed ? typed.code()
                    : failure instanceof ConflictException typed ? typed.code() : "DOCUMENT_DEVELOPMENT_INTERRUPTED";
            String detail = failure instanceof BadRequestException || failure instanceof ConflictException
                    ? failure.getMessage() : "开发协调未完成，请检查关联设计或执行的状态后恢复；原执行引擎仍持有停止和租约管理职责。";
            waitForOwner(run, code, detail);
        }
    }
    private void waitForOwner(DocumentTemplateRunRow observed, String code, String message) {
        var current = admission.require(observed.id());
        if (current.version() != observed.version() || !Set.of("DESIGNING", "EXECUTING", "REPORTING").contains(current.state())) return;
        admission.transition(current, DocumentTemplateState.WAITING_INPUT, LifecycleEvent.REQUIRE_INPUT, code, message);
    }
    @Override public void observe(DocumentTemplateRunRow run) {
        if (!"DOCUMENT_DEVELOPMENT_WAIT".equals(run.waitingReasonCode())) return;
        boolean resumed = false;
        if ("DESIGNING".equals(run.resumeState()) && run.designerId() != null) {
            var owner = domain.findDesignerSession(run.designerId()).orElseThrow();
            resumed = !DocumentDevelopmentPlanner.waiting(owner);
        } else if ("EXECUTING".equals(run.resumeState()) && run.taskId() != null) {
            var owner = domain.findTask(run.taskId()).orElseThrow();
            resumed = !Set.of("WAITING_INPUT", "PAUSED", "STOPPING", "CANCELLED", "SUPERSEDED", "FAILED").contains(owner.state());
            if (!resumed && owner.state().equals("WAITING_INPUT")) {
                var pack = domain.currentTaskPackageRun(owner.id()).orElse(null);
                resumed = pack != null && Set.of("DESIGN_REVIEW", "EXECUTION_READY").contains(pack.state());
            }
        }
        if (resumed && run.designerId() != null) {
            var designer = domain.findDesignerSession(run.designerId()).orElseThrow();
            resumed = !DocumentDevelopmentPlanner.waiting(designer);
        }
        if (resumed) admission.transition(run, DocumentTemplateState.valueOf(run.resumeState()),
                run.resumeState().equals("DESIGNING") ? LifecycleEvent.DESIGN_DOCUMENT_REQUIREMENTS : LifecycleEvent.EXECUTE_DOCUMENT_REQUIREMENTS, null, null);
    }
    @Override public boolean stop(DocumentTemplateRunRow run, boolean cancel) { return stopping.stop(run, cancel); }
}
