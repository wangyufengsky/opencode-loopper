package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.persistence.PptAgentActivityMapper;
import io.opencode.loopper.persistence.PptAgentActivityMapper.Snapshot;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient.*;
import io.opencode.loopper.service.assist.AssistRedaction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Provider-exposed activity only: no inferred thinking and no workflow authority. */
@Service
public class PptAgentActivity {
    private static final int LIMIT = 64000;
    private final PptAgentActivityMapper mapper;
    private final ObjectMapper json;
    public PptAgentActivity(PptAgentActivityMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }
    public record Call(String id, String tool, String state, String detail) { }
    public record View(String thinking, List<Call> calls) {
        static final View EMPTY = new View("", List.of());
    }
    public void capture(OpenCodeClient client, OpenCodeSession remote, Run run) {
        try { save(run, client.sessionTranscript(remote)); }
        catch (RuntimeException unavailable) { /* Projection failure must not stop production or erase saved activity. */ }
    }
    void save(Run run, SessionTranscript transcript) {
        if (transcript == null || transcript.parts().isEmpty()) return;
        var previous = mapper.get(run.id());
        String prefix = previous == null ? "" : previous.messageId().equals(run.messageId())
                ? previous.thinkingPrefix() : previous.thinking();
        String current = thinking(transcript);
        String thinking = current.isBlank() && previous != null ? previous.thinking() : bound(prefix + (prefix.isBlank() || current.isBlank() ? "" : "\n\n") + current);
        var calls = new LinkedHashMap<String, Call>();
        if (previous != null) read(previous).calls().forEach(call -> calls.put(call.id(), call));
        for (var part : transcript.parts()) {
            if (!"TOOL".equals(part.type())) continue;
            String tool = Objects.toString(part.label(), "");
            int start = tool.lastIndexOf("ppt_");
            if (start < 0) continue;
            tool = tool.substring(start);
            if (!tool.matches("ppt_[a-z_]{1,60}")) continue;
            String id = UUID.nameUUIDFromBytes((run.messageId() + ":" + part.id()).getBytes(StandardCharsets.UTF_8)).toString();
            String state = switch (Objects.toString(part.status(), "").toLowerCase(Locale.ROOT)) {
                case "completed", "success" -> "SUCCEEDED";
                case "error", "failed" -> "FAILED";
                default -> "RUNNING";
            };
            calls.put(id, new Call(id, tool, state, ""));
        }
        var recent = calls.values().stream().skip(Math.max(0, calls.size() - 30)).toList();
        var next = new Snapshot(run.id(), run.messageId(), prefix, thinking, json.writeValueAsString(recent));
        if (!next.equals(previous)) mapper.save(next, run.version());
    }
    public Map<String, View> views(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var result = new HashMap<String, View>();
        mapper.forRuns(ids).forEach(row -> result.put(row.runId(), read(row))); return result;
    }
    private View read(Snapshot row) { return new View(row.thinking(), json.readValue(row.callsJson(), new TypeReference<List<Call>>() { })); }
    private static String thinking(SessionTranscript transcript) {
        var result = new StringBuilder();
        for (var part : transcript.parts()) {
            if (!"THINKING".equals(part.type()) || part.content() == null || part.content().isBlank()) continue;
            if (!result.isEmpty()) result.append("\n\n");
            if (result.length() > LIMIT) break;
            String text = AssistRedaction.text(part.content());
            result.append(text, 0, Math.min(text.length(), LIMIT + 1 - result.length()));
            if (result.length() > LIMIT) break;
        }
        return bound(result.toString());
    }
    private static String bound(String text) { return text.length() <= LIMIT ? text : text.substring(0, LIMIT - 24) + "\n\n（思考内容较长，已达到保存上限）"; }
}
