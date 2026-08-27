package io.opencode.loopper.service;

import org.springframework.stereotype.Service;

/** Local-UI facade over the reusable Designer termination protocol. */
@Service
public final class DesignerStopService {
    private final DesignerTerminationService termination;

    public DesignerStopService(DesignerTerminationService termination) {
        this.termination = termination;
    }

    public Result stop(String sessionId) {
        DesignerTerminationService.Result result = termination.stop(sessionId, true);
        return new Result(result.stopStatus(), result.archived(), result.stoppedSessions(),
                result.failedSessions(), result.pendingFinalizations());
    }

    public record Result(String stopStatus, boolean archived, int stoppedSessions,
                         int failedSessions, int pendingFinalizations) { }
}
