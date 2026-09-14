package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Cancellation reuses the owners' durable termination sagas; an acknowledgement or missing writer row is not a stop proof. */
@Service
public final class DocumentDevelopmentStop {
    private final TaskService tasks;
    private final DesignerTerminationService designers;
    private final RollingPackagePlanGenerationService generations;
    private final DocumentSupplementMapper supplements;
    private final RollingPackagePlanService plans;
    private final DocumentSupplementDesign supplementDesigns;
    public DocumentDevelopmentStop(TaskService tasks, DesignerTerminationService designers, RollingPackagePlanGenerationService generations,
            DocumentSupplementMapper supplements, RollingPackagePlanService plans, DocumentSupplementDesign supplementDesigns) {
        this.tasks = tasks; this.designers = designers;
        this.generations = generations; this.supplements = supplements; this.plans = plans;
        this.supplementDesigns = supplementDesigns;
    }
    public boolean stop(DocumentTemplateRunRow run, boolean cancel) {
        if (run.taskId() != null) {
            if (!generations.stopDocumentTask(run.taskId())) return false;
            if (!cancel) {
                var pending = supplements.pending(run.id()).orElse(null);
                if (pending != null && pending.planId() == null) {
                    plans.safeContext(run.taskId(), pending.baseTaskVersion(), false, RollingPackageCommandPolicy.Command.REPLAN);
                    return true;
                }
            }
            var task = tasks.get(run.taskId());
            if (cancel) {
                if (task.state().equals("STOPPING")) task = tasks.continueCancellation(task.id());
                else if (task.state().equals("AWAITING_DECISION")) task = tasks.cancelDecision(task.id());
                else if (!Set.of("CANCELLED", "COMPLETED", "SUPERSEDED", "FAILED", "SUCCEEDED").contains(task.state())) task = tasks.cancel(task.id());
                if (!Set.of("CANCELLED", "COMPLETED", "SUPERSEDED", "FAILED", "SUCCEEDED").contains(task.state())) return false;
            } else {
                task = tasks.pause(task.id());
                if (!Set.of("PAUSED", "WAITING_INPUT", "AWAITING_DECISION", "CANCELLED", "COMPLETED").contains(task.state())) return false;
            }
            if (!tasks.writersStopped(task.id())) return false;
        }
        if (run.designerId() != null) {
            // A recoverable template wait is projected without cancelling its Designer. Explicit cancellation owns this path.
            if (!cancel) return supplementDesigns.stopForAnalysis(run);
            if (!designers.stop(run.designerId(), false).complete()) return false;
        }
        return true;
    }
}
