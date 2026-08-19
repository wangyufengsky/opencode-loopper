package io.opencode.loopper.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls actual OpenCode Designer sessions without participating in the task execution monitor. */
@Component
class DesignerSessionMonitor {
    private final DesignerSessionService designerSessions;
    private final DesignerAutoModeService autoMode;
    private final AnalysisReportService reports;

    DesignerSessionMonitor(DesignerSessionService designerSessions, DesignerAutoModeService autoMode,
                           AnalysisReportService reports) {
        this.designerSessions = designerSessions;
        this.autoMode = autoMode;
        this.reports = reports;
    }

    @Scheduled(fixedDelayString = "${loopper.designer-monitor-delay:750ms}")
    void poll() {
        designerSessions.pollActiveHandoffs();
        for (AnalysisReportService.PollResult result : reports.pollActive()) {
            if (result.ready()) designerSessions.completeReadOnlyReport(result.designerSessionId());
            else designerSessions.failReadOnlyReport(result.designerSessionId(), result.errorCode(), result.errorDetail());
        }
        autoMode.pollActive();
    }
}
