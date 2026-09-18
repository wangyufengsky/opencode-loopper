package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.KnowledgeMapper;
import io.opencode.loopper.persistence.KnowledgeResearchMapper;
import io.opencode.loopper.persistence.KnowledgeResearchMapper.Round;
import io.opencode.loopper.persistence.KnowledgeRows.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient.*;
import io.opencode.loopper.runtime.KnowledgeSessionPolicy;
import io.opencode.loopper.service.assist.AssistRedaction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Same-session self-review and Todo continuation, with exact recovery and no blind resend. */
@Service
public final class KnowledgeResearch {
    private final KnowledgeResearchMapper mapper;
    private final KnowledgeMapper turns;
    private final OpenCodeClient remote;
    private final ObjectMapper json;
    public KnowledgeResearch(KnowledgeResearchMapper mapper, KnowledgeMapper turns, OpenCodeClient remote, ObjectMapper json) {
        this.mapper = mapper; this.turns = turns; this.remote = remote; this.json = json;
    }
    public String messageId(Turn turn) { var round = mapper.latest(turn.id()); return round == null ? turn.messageId() : round.messageId(); }
    /** True while a prepared or uncertain continuation must be dispatched/reconciled before polling output. */
    public boolean advance(OpenCodeSession session, Turn turn) {
        var round = mapper.latest(turn.id());
        if (round == null || Set.of("RUNNING", "COMPLETED").contains(round.state())) return false;
        var node = json.readTree(round.requestJson());
        var request = new PromptRequest(node.path("text").asText(), node.path("system").asText(), "build",
                new ResponseFormat.Text(), round.messageId(), List.of());
        if (round.state().equals("PREPARED")) {
            if (!change(round, "SENDING")) return true;
            var sending = mapper.latest(turn.id());
            if (!active(turn.id())) return true;
            try { remote.promptAsync(session, request); change(sending, "RUNNING"); }
            catch (RuntimeException uncertain) { change(sending, "UNKNOWN"); }
        } else {
            var found = remote.findPromptMessage(session, request, round.requestSha());
            if (found.supported() && found.exists() && round.requestSha().equals(found.verifiedRequestSha256())) change(round, "RUNNING");
        }
        return true;
    }
    /** The initial answer always receives one self-review. Explicit unfinished Todos continue after that. */
    public boolean continueAfterAnswer(OpenCodeSession session, Turn turn) {
        var previous = mapper.latest(turn.id());
        if (previous != null && !previous.state().equals("COMPLETED")) {
            if (!previous.state().equals("RUNNING") || !change(previous, "COMPLETED")) return true;
            previous = mapper.latest(turn.id());
        }
        var todos = remote.sessionTodoSnapshot(session);
        if (todos == null) throw new IllegalStateException("Research Todo status unavailable");
        var unfinished = todos.todos().stream().filter(t -> !Set.of("completed", "cancelled").contains(Objects.toString(t.status(), "").toLowerCase(Locale.ROOT))).toList();
        if (previous != null && unfinished.isEmpty() && !todos.truncated()) return false;
        String pending = unfinished.isEmpty() ? "" : "仍未完成的调查项：\n" + String.join("\n", unfinished.stream().limit(100)
                .map(t -> "- " + AssistRedaction.text(t.content())).toList());
        if (todos.truncated()) pending += "\n待办列表截断，请整理本轮待办并完成相关调查，不能将缺失部分视为已完成。";
        var original = json.readTree(turn.requestJson());
        int ordinal = previous == null ? 1 : previous.ordinal() + 1;
        String messageId = "msg_loopper_knowledge_check_" + UUID.randomUUID().toString().replace("-", "");
        var request = new PromptRequest(KnowledgePrompts.review(turn.userText(), pending), original.path("system").asText(),
                "build", new ResponseFormat.Text(), messageId, List.of());
        String now = Instant.now().toString();
        mapper.prepare(new Round(turn.id(), ordinal, messageId, "PREPARED", json.writeValueAsString(request),
                OpenCodeClient.promptRequestSha256(request), turn.thinking(), now, now, 0), turn.version(), ordinal - 1);
        return true;
    }
    public String thinking(Turn turn, SessionTranscript transcript) {
        String current = KnowledgeThinking.text(transcript); var round = mapper.latest(turn.id());
        String value = round == null || round.thinkingPrefix().isBlank() ? current : round.thinkingPrefix() + (current.isBlank() ? "" : "\n\n" + current);
        return value.length() <= 64000 ? value : value.substring(0, 63970) + "\n思考内容已达到保存上限";
    }
    public void nativeCalls(Turn turn, SessionTranscript transcript) {
        String now = Instant.now().toString();
        for (var part : transcript.parts()) {
            if (!part.type().equals("TOOL") || !KnowledgeSessionPolicy.NATIVE_TOOLS.contains(part.label())) continue;
            String id = UUID.nameUUIDFromBytes((turn.id() + ":" + part.id()).getBytes(StandardCharsets.UTF_8)).toString();
            String state = switch (Objects.toString(part.status(), "").toLowerCase(Locale.ROOT)) {
                case "completed", "success" -> "SUCCEEDED"; case "error", "failed" -> "FAILED"; default -> "RUNNING";
            };
            mapper.nativeCall(new Call(id, turn.conversationId(), turn.id(), part.label(), state, "项目只读调查", now, now));
        }
    }
    private boolean active(String turn) { return turns.turn(turn).map(t -> t.state().equals("RUNNING")).orElse(false); }
    private boolean change(Round row, String next) { return mapper.state(row, next, Instant.now().toString()) == 1; }
}
