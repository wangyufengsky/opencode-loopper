package io.opencode.loopper.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls actual OpenCode Designer sessions without participating in the task execution monitor. */
@Component
class DesignerSessionMonitor {
    private final DesignerSessionService designerSessions;

    DesignerSessionMonitor(DesignerSessionService designerSessions) { this.designerSessions = designerSessions; }

    @Scheduled(fixedDelayString = "${loopper.designer-monitor-delay:750ms}")
    void poll() { designerSessions.pollActiveHandoffs(); }
}
