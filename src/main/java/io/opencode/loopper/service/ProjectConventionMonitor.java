package io.opencode.loopper.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls read-only AGENTS.md generation independently of task execution. */
@Component
class ProjectConventionMonitor {
    private final ProjectConventionService conventions;

    ProjectConventionMonitor(ProjectConventionService conventions) { this.conventions = conventions; }

    @Scheduled(fixedDelayString = "${loopper.designer-monitor-delay:750ms}")
    void poll() { conventions.pollActiveGenerations(); }
}
