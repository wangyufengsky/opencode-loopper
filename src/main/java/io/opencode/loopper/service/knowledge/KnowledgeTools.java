package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.KnowledgeRows.*;
import io.opencode.loopper.service.KnowledgeEventHub;
import io.opencode.loopper.service.assist.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Knowledge-only tool adapter: exact owner identity, bounded reads, durable call and citation receipts. */
@Service
public class KnowledgeTools {
    private final KnowledgeMapper mapper;
    private final KnowledgeSources sources;
    private final KnowledgeReadOperations reads;
    private final ObjectMapper json;
    private final KnowledgeEventHub events;
    private final AssistScopeService scopes;
    public KnowledgeTools(KnowledgeMapper mapper, KnowledgeSources sources, KnowledgeReadOperations reads,
            ObjectMapper json, KnowledgeEventHub events, AssistScopeService scopes) {
        this.mapper = mapper; this.sources = sources; this.reads = reads;
        this.json = json; this.events = events; this.scopes = scopes;
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterruptedCalls() { mapper.interruptedCalls(); }
    public Map<String,Object> call(AssistScopeService.Scope scope, String name, Map<String,Object> args) {
        var conversation = owner(scope); var turn = active(conversation.id()); String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        mapper.startCall(new Call(id, conversation.id(), turn.id(), name, "RUNNING", callDetail(args), now, now)); events.publish(conversation.id(), "tool");
        try {
            Map<String,Object> result = execute(conversation, turn.id(), name, args);
            scopes.authorize(string(args, "scope"), name);
            if (!active(conversation.id()).id().equals(turn.id())) throw KnowledgeSources.bad("问答回合已变化，结果已丢弃");
            if (name.equals("read_knowledge_sources")) result = batchCitations(conversation, turn, result);
            if (Set.of("read_knowledge_source", "browse_knowledge_source", "inspect_knowledge_project").contains(name)
                    || name.contains("knowledge_git") || name.equals("query_database_readonly") || name.equals("inspect_database_schema")) result = citation(conversation, turn, result);
            mapper.finishCall(id, "SUCCEEDED", callDetail(args), Instant.now().toString()); return result;
        } catch (RuntimeException failure) {
            mapper.finishCall(id, "FAILED", failure instanceof AssistFailure ? failure.getMessage() : "资料读取失败，请检查来源", Instant.now().toString()); throw failure;
        } finally { events.publish(conversation.id(), "tool"); }
    }
    private Conversation owner(AssistScopeService.Scope scope) {
        return mapper.remote(scope.externalSessionId()).filter(c -> c.projectId().equals(scope.projectId()) && c.state().equals("RUNNING"))
                .orElseThrow(() -> KnowledgeSources.bad("知识库会话授权已失效"));
    }
    private Turn active(String id) { return mapper.active(id).filter(t -> Set.of("SENDING", "UNKNOWN", "RUNNING").contains(t.state())).orElseThrow(() -> KnowledgeSources.bad("当前回合已结束或正在停止")); }
    private Map<String,Object> execute(Conversation conversation, String turnId, String name, Map<String,Object> args) {
        if (name.equals("list_knowledge_evidence")) return KnowledgeEvidence.list(mapper, conversation.id(), args);
        if (name.equals("read_knowledge_evidence")) return KnowledgeEvidence.read(mapper, conversation.id(), args);
        return reads.read(name.contains("knowledge_git") ? conversation.id() : "mcp:" + conversation.id() + ":" + turnId,
                new KnowledgeSources.Selection(sources.frozen(conversation), sources.connections(conversation)), name, args);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> batchCitations(Conversation conversation, Turn turn, Map<String,Object> batch) {
        var output = new LinkedHashMap<>(batch); var items = new ArrayList<Map<String,Object>>();
        for (var item : (List<Map<String,Object>>)batch.get("items")) {
            var row = new LinkedHashMap<>(item);
            if (row.get("result") instanceof Map<?,?> body) row.put("result", citation(conversation, turn, (Map<String,Object>)body));
            items.add(row);
        }
        output.put("items", items); return output;
    }
    private Map<String,Object> citation(Conversation conversation, Turn turn, Map<String,Object> body) {
        if (mapper.citationCount(turn.id()) >= 100) return uncited(body);
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        if (Set.of("CODE", "DOCUMENT").contains(body.get("kind"))) {
            String previous = mapper.previousFileSha(turn.id(), Objects.toString(body.get("sourceId")), Objects.toString(body.get("path"), ""));
            if (previous != null && !previous.equals(body.get("sha256"))) {
                body = new LinkedHashMap<>(body); body.put("changedSinceEarlierRead", true); body.put("previousSha256", previous);
                body.put("changeNotice", "同一回合内文件已变化；这是重新读取后的独立版本，请勿混用旧证据");
            }
        }
        String encoded = AssistRedaction.text(json.writeValueAsString(body));
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) throw KnowledgeSources.bad("证据超过保存上限，请缩小查询范围");
        if (mapper.cite(new Citation(id, conversation.id(), turn.id(), Objects.toString(body.get("kind")), Objects.toString(body.get("sourceId")),
                Objects.toString(body.get("name")), Objects.toString(body.get("location")), Objects.toString(body.get("sha256")), encoded, now)) != 1) {
            if (active(conversation.id()).id().equals(turn.id()) && mapper.citationCount(turn.id()) >= 100) return uncited(body);
            throw KnowledgeSources.bad("会话已停止，未保存迟到引用");
        }
        var result = new LinkedHashMap<>(body); result.put("citationId", id); result.put("citationLink", "knowledge:" + id); result.put("collectedAt", now); return result;
    }
    private static Map<String,Object> uncited(Map<String,Object> body) {
        var result = new LinkedHashMap<>(body);
        result.put("citationStatus", "LIMIT_REACHED");
        result.put("citationNotice", "本轮已保存 100 条引用，本次仍返回实际读取内容，请继续查清问题；仅已保存的 ID 可用于 knowledge: 引用，其余内容可注明真实路径和行号。");
        return result;
    }
    private static String callDetail(Map<String,Object> args) {
        String value = List.of("path", "query", "author", "table").stream().map(k -> Objects.toString(args.get(k), "")).filter(v -> !v.isBlank()).findFirst().orElse("");
        return AssistRedaction.text(value.substring(0, Math.min(value.length(), 160)));
    }
    private static String string(Map<String,Object> args, String key) { return args.get(key) instanceof String text ? text : null; }
}
