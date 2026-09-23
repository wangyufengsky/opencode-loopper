package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.service.assist.AssistRedaction;
import java.util.List;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import static io.opencode.loopper.runtime.OpenCodeClient.*;

/** Knowledge polling shares one HTTP message snapshot across all projections and question ownership checks. */
final class OpenCodeKnowledgeObservation {
    private OpenCodeKnowledgeObservation() { }
    static KnowledgeObservation read(OpenCodeResponseParser parser, JsonNode all, String message, String session,
            boolean interactive, Supplier<JsonNode> statusRead, Supplier<JsonNode> questionRead) {
        var current = OpenCodeDesignMessageFilter.filter(all, message);
        List<PendingQuestion> questions = List.of();
        if (interactive) {
            var body = OpenCodeDesignMessageFilter.interactions(fetch(questionRead, "OPENCODE_QUESTION_LIST_FAILED"), current, message);
            questions = parser.questions(body, session);
            if (questions == null) throw new SessionFailure("OPENCODE_QUESTION_INVALID_RESPONSE", "OpenCode did not return a pending question list");
        }
        var status = questions.isEmpty() ? status(parser, current, session, message, fetch(statusRead, "OPENCODE_STATUS_FAILED")) : new SessionStatus("RUNNING");
        SessionTranscript transcript = null;
        try { transcript = OpenCodeDesignMessageFilter.transcript(all, message, parser); } catch (RuntimeException unavailable) { /* Keep persisted monitoring data. */ }
        var latest = parser.latestAssistantAfterUser(current);
        var result = status.completed() && questions.isEmpty() && latest != null ? parser.result(latest) : null;
        List<UsageRecord> usage = List.of();
        try { usage = parser.usage(all); } catch (RuntimeException unavailable) { /* Monitoring cannot block answer completion. */ }
        return new KnowledgeObservation(status, AssistRedaction.text(parser.liveOutput(current)), transcript, result, questions, usage);
    }
    private static JsonNode fetch(Supplier<JsonNode> request, String code) {
        try { return request.get(); } catch (SessionFailure safe) { throw safe; }
        catch (RuntimeException failure) { throw new SessionFailure(code, failure.getMessage()); }
    }
    private static SessionStatus status(OpenCodeResponseParser parser, JsonNode current, String session, String message, JsonNode body) {
        var entry = body == null ? null : body.get(session);
        if (entry == null || entry.isNull()) return parser.messageStatus(current);
        String state = entry.isTextual() ? entry.asText() : entry.path("status").asText(null);
        if (state == null || state.isBlank()) state = entry.path("type").asText(null);
        if (state == null || state.isBlank()) return new SessionStatus("UNKNOWN");
        String detail = entry.path("message").asText(null);
        if (detail == null || detail.isBlank()) detail = entry.path("action").path("message").asText(null);
        var status = new SessionStatus(state, detail);
        return message != null && status.completed() ? parser.messageStatus(current) : status;
    }
}
