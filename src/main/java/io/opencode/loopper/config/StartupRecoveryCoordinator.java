package io.opencode.loopper.config;

import io.opencode.loopper.service.AutomationService;
import io.opencode.loopper.service.LocalSyncConflictService;
import io.opencode.loopper.service.StageWorkspaceBaselineService;
import io.opencode.loopper.service.TaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Runs durable restart recovery once the application is ready. */
@Component
@ConditionalOnProperty(name = "loopper.startup-recovery.enabled", havingValue = "true", matchIfMissing = true)
public class StartupRecoveryCoordinator {
    private final LocalSyncConflictService localSyncConflicts;
    private final StageWorkspaceBaselineService stageWorkspaceBaselines;
    private final TaskService tasks;
    private final AutomationService automations;

    public StartupRecoveryCoordinator(LocalSyncConflictService localSyncConflicts,
                                      StageWorkspaceBaselineService stageWorkspaceBaselines,
                                      TaskService tasks, AutomationService automations) {
        this.localSyncConflicts = localSyncConflicts;
        this.stageWorkspaceBaselines = stageWorkspaceBaselines;
        this.tasks = tasks;
        this.automations = automations;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        stageWorkspaceBaselines.cleanupOrphans();
        localSyncConflicts.recoverInterruptedApplications();
        tasks.recoverAfterRestart();
        automations.recoverAfterRestart();
    }
}
