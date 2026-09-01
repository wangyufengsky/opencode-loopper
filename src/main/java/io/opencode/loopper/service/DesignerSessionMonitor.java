package io.opencode.loopper.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls actual OpenCode Designer sessions without participating in the task execution monitor. */
@Component
class DesignerSessionMonitor {
    private final DesignerSessionService designerSessions;
    private final DesignerAutoModeService autoMode;
    private final AnalysisReportService reports;
    private final DesignerTerminationService terminations;
    private final DesignerRequirementReplacementRecovery replacements;

    DesignerSessionMonitor(DesignerSessionService designerSessions, DesignerAutoModeService autoMode,
                           AnalysisReportService reports, DesignerTerminationService terminations,
                           DesignerRequirementReplacementRecovery replacements) {
        this.designerSessions = designerSessions;
        this.autoMode = autoMode;
        this.reports = reports;
        this.terminations = terminations;
        this.replacements = replacements;
    }

    @Scheduled(fixedDelayString = "${loopper.designer-monitor-delay:750ms}")
    void poll() {
        terminations.recoverInternalCancellations();
        replacements.recover();
        designerSessions.pollActiveHandoffs();
        for (AnalysisReportService.PollResult result : reports.pollActive()) {
            if (result.ready()) designerSessions.completeReadOnlyReport(result.designerSessionId());
            else designerSessions.failReadOnlyReport(result.designerSessionId(), result.errorCode(), result.errorDetail());
        }
        autoMode.pollActive();
    }
}
