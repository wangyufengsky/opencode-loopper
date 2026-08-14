package io.opencode.loopper.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic liveness repair for exceptional terminal holders with queued waiters. */
@Component
class WorkspaceLeaseReconciliationMonitor {
    private final TaskService tasks;

    WorkspaceLeaseReconciliationMonitor(TaskService tasks) {
        this.tasks = tasks;
    }

    @Scheduled(fixedDelayString = "${loopper.workspace-lease-reconcile-delay:10s}")
    void poll() {
        tasks.reconcileTerminalWorkspaceLeasesWithWaiters();
    }
}
