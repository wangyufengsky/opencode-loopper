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
    private final KnowledgeReader reader;
    private final DatabaseQueryService databases;
    private final ObjectMapper json;
    private final KnowledgeEventHub events;
    private final AssistScopeService scopes;
    public KnowledgeTools(KnowledgeMapper mapper, KnowledgeSources sources, KnowledgeReader reader,
            DatabaseQueryService databases, ObjectMapper json, KnowledgeEventHub events, AssistScopeService scopes) {
        this.mapper = mapper; this.sources = sources; this.reader = reader; this.databases = databases; this.json = json; this.events = events; this.scopes = scopes;
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterruptedCalls() { mapper.interruptedCalls(); }
    public Map<String,Object> call(AssistScopeService.Scope scope, String name, Map<String,Object> args) {
        var conversation = owner(scope); var turn = active(conversation.id()); String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        mapper.startCall(new Call(id, conversation.id(), turn.id(), name, "RUNNING", "", now, now)); events.publish(conversation.id(), "tool");
        try {
            Map<String,Object> result = execute(conversation, name, args);
            scopes.authorize(string(args, "scope"), name);
            if (!active(conversation.id()).id().equals(turn.id())) throw KnowledgeSources.bad("问答回合已变化，结果已丢弃");
            if (name.equals("read_knowledge_source") && !Objects.toString(result.get("text"), "").isEmpty()
                    || name.equals("query_database_readonly") || name.equals("inspect_database_schema")) result = citation(conversation, turn, result);
            mapper.finishCall(id, "SUCCEEDED", "", Instant.now().toString()); return result;
        } catch (RuntimeException failure) {
            mapper.finishCall(id, "FAILED", failure instanceof AssistFailure ? failure.getMessage() : "资料读取失败，请检查来源", Instant.now().toString()); throw failure;
        } finally { events.publish(conversation.id(), "tool"); }
    }
    private Conversation owner(AssistScopeService.Scope scope) {
        return mapper.remote(scope.externalSessionId()).filter(c -> c.projectId().equals(scope.projectId()) && c.state().equals("RUNNING"))
                .orElseThrow(() -> KnowledgeSources.bad("知识库会话授权已失效"));
    }
    private Turn active(String id) { return mapper.active(id).filter(t -> Set.of("SENDING", "UNKNOWN", "RUNNING").contains(t.state())).orElseThrow(() -> KnowledgeSources.bad("当前回合已结束或正在停止")); }
    private Map<String,Object> execute(Conversation conversation, String name, Map<String,Object> args) {
        if (name.equals("list_knowledge_sources")) return Map.of("sources", sources.frozenViews(conversation));
        if (name.equals("list_database_connections")) return Map.of("connections", sources.connections(conversation).stream()
                .map(c -> Map.of("id", c.id(), "name", c.name(), "type", c.config().type(), "schemas", c.config().schemas(), "version", c.version())).toList());
        if (name.equals("query_database_readonly") || name.equals("inspect_database_schema")) return database(conversation, name, args);
        var source = sources.frozen(conversation).stream().filter(s -> s.id().equals(string(args, "sourceId"))).findFirst().orElseThrow(() -> KnowledgeSources.bad("资料不属于当前会话"));
        return switch (name) {
            case "browse_knowledge_source" -> json.convertValue(reader.browse(source, string(args, "path"), string(args, "query"), string(args, "cursor")), new tools.jackson.core.type.TypeReference<>() { });
            case "search_knowledge" -> reader.search(source, string(args, "path"), string(args, "query"), string(args, "cursor"));
            case "read_knowledge_source" -> reader.read(source, string(args, "path"), number(args, "section", -1), number(args, "startLine", 1), string(args, "expectedSha"), number(args, "offset", 0));
            default -> throw KnowledgeSources.bad("此工具不属于知识问答权限");
        };
    }
    private Map<String,Object> database(Conversation conversation, String name, Map<String,Object> args) {
        var bound = sources.connections(conversation).stream().filter(c -> c.id().equals(string(args, "connectionId"))).findFirst().orElseThrow(() -> KnowledgeSources.bad("数据库未授权给当前会话"));
        var output = name.equals("query_database_readonly") ? databases.query(bound, string(args, "sql"))
                : databases.inspect(bound, string(args, "schema"), string(args, "table"), string(args, "kind"), number(args, "offset", 0));
        var result = new LinkedHashMap<>(output); result.put("kind", "DATABASE"); result.put("sourceId", "database:" + bound.id()); result.put("name", bound.name());
        result.put("location", name.equals("query_database_readonly") ? "只读查询" : Objects.toString(string(args, "schema"), "") + "." + Objects.toString(string(args, "table"), "结构"));
        result.put("sql", name.equals("query_database_readonly") ? string(args, "sql") : ""); result.put("configurationVersion", bound.version());
        result.put("sha256", AssistFiles.sha(json.writeValueAsBytes(output))); return result;
    }
    private Map<String,Object> citation(Conversation conversation, Turn turn, Map<String,Object> body) {
        if (mapper.citationCount(turn.id()) >= 100) throw KnowledgeSources.bad("本轮已保存 100 条证据，请据此回答或在下一轮继续检索");
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        if (!"DATABASE".equals(body.get("kind"))) {
            String previous = mapper.previousFileSha(turn.id(), Objects.toString(body.get("sourceId")), Objects.toString(body.get("path"), ""));
            if (previous != null && !previous.equals(body.get("sha256"))) {
                body = new LinkedHashMap<>(body); body.put("changedSinceEarlierRead", true); body.put("previousSha256", previous);
                body.put("changeNotice", "同一回合内文件已变化；这是重新读取后的独立版本，请勿混用旧证据");
            }
        }
        String encoded = AssistRedaction.text(json.writeValueAsString(body));
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) throw KnowledgeSources.bad("证据超过保存上限，请缩小查询范围");
        if (mapper.cite(new Citation(id, conversation.id(), turn.id(), Objects.toString(body.get("kind")), Objects.toString(body.get("sourceId")),
                Objects.toString(body.get("name")), Objects.toString(body.get("location")), Objects.toString(body.get("sha256")), encoded, now)) != 1)
            throw KnowledgeSources.bad("会话已停止，未保存迟到引用");
        var result = new LinkedHashMap<>(body); result.put("citationId", id); result.put("citationLink", "knowledge:" + id); result.put("collectedAt", now); return result;
    }
    private static String string(Map<String,Object> args, String key) { return args.get(key) instanceof String text ? text : null; }
    private static int number(Map<String,Object> args, String key, int fallback) { return args.get(key) instanceof Number n ? n.intValue() : fallback; }
}
