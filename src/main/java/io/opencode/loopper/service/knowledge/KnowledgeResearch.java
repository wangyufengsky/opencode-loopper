package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.KnowledgeMapper;
import io.opencode.loopper.persistence.KnowledgeResearchMapper;
import io.opencode.loopper.persistence.KnowledgeResearchMapper.Round;
import io.opencode.loopper.persistence.KnowledgeRows.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient.*;
import io.opencode.loopper.runtime.KnowledgeSessionPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Native investigation records and exact recovery of previously persisted continuations. */
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
    /** Finish a legacy continuation if present; never create another prompt after an answer. */
    public boolean completeExistingRound(Turn turn) {
        var round = mapper.latest(turn.id());
        return round == null || round.state().equals("COMPLETED")
                || (round.state().equals("RUNNING") && change(round, "COMPLETED"));
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
