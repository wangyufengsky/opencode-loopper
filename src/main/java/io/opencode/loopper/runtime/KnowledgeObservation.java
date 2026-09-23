package io.opencode.loopper.runtime;

import java.util.List;
import static io.opencode.loopper.runtime.OpenCodeClient.*;

/** One observation of the exact knowledge message, with independently optional monitoring data. */
public record KnowledgeObservation(SessionStatus status, String output, SessionTranscript transcript,
        SessionResult result, List<PendingQuestion> questions, List<UsageRecord> usage) {
    static KnowledgeObservation read(OpenCodeClient client, OpenCodeSession session, boolean interactive) {
        var questions = interactive ? client.pendingQuestions(session) : List.<PendingQuestion>of();
        var status = questions.isEmpty() ? client.sessionStatus(session) : new SessionStatus("RUNNING"); String output = client.sessionLiveOutput(session);
        SessionTranscript transcript = null; List<UsageRecord> usage = List.of();
        try { transcript = client.sessionTranscript(session); } catch (RuntimeException unavailable) { /* Keep saved activity. */ }
        try { usage = client.sessionUsage(session); } catch (RuntimeException unavailable) { /* Unknown usage stays unknown. */ }
        var result = status.completed() && questions.isEmpty() ? client.sessionResult(session) : null;
        return new KnowledgeObservation(status, output, transcript, result, questions, usage);
    }
}
