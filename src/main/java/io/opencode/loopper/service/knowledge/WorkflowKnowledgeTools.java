package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.WorkflowKnowledgeMapper;
import io.opencode.loopper.service.assist.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Business roles search independently. Citations are immutable assist receipts, scoped to their session. */
@Service
public final class WorkflowKnowledgeTools {
    private final WorkflowKnowledgeAccess access;
    private final WorkflowKnowledgeBindings bindings;
    private final WorkflowKnowledgeMapper mapper;
    private final KnowledgeReadOperations reads;
    private final KnowledgeGit git;
    private final ObjectMapper json;
    public WorkflowKnowledgeTools(WorkflowKnowledgeAccess access, WorkflowKnowledgeBindings bindings,
            WorkflowKnowledgeMapper mapper, KnowledgeReadOperations reads, KnowledgeGit git, ObjectMapper json) {
        this.access = access; this.bindings = bindings; this.mapper = mapper; this.reads = reads; this.git = git; this.json = json;
    }
    public Map<String,Object> call(AssistScopeService.Scope scope, String tool, Map<String,Object> args, String receipt) {
        var binding = access.require(scope);
        if (tool.equals("list_knowledge_evidence")) return listEvidence(scope.externalSessionId(), args);
        if (tool.equals("read_knowledge_evidence")) return evidence(scope.externalSessionId(), args);
        var selected = new ArrayList<>(bindings.sources(binding));
        if (scope.taskId() != null) selected.add(new KnowledgeSources.Bound("task-code", "CODE", "当前任务代码",
                scope.directory().toString(), null, "READY", "已授权任务工作区；引用标记实际读取版本", 0));
        String saved = mapper.git(scope.externalSessionId());
        if (saved == null) {
            var root = selected.stream().filter(s -> s.id().equals("code")).findFirst();
            var source = root.isEmpty() ? null : git.source(root.get().path());
            mapper.freezeGit(scope.externalSessionId(), json.writeValueAsString(source));
            saved = mapper.git(scope.externalSessionId());
        }
        var repository = json.readValue(saved, KnowledgeSources.Bound.class);
        if (repository != null) selected.add(repository);
        // Reviewers can search documents/code/history independently; no live SQL or metadata scans.
        var connections = (WorkflowKnowledgePolicy.evidenceOnly(scope.profile()) || access.evidenceOnly(scope)) ? List.<DatabaseConnectionService.Bound>of() : scope.connections();
        var result = reads.read("workflow:" + scope.externalSessionId(), new KnowledgeSources.Selection(selected, connections), tool, args);
        access.require(scope);
        var body = new LinkedHashMap<>(result);
        body.put("evidenceUse", "补充项目证据；不替代任务冻结输入、目标版本或正式验收。搜索命中须读取原文后引用。");
        if (tool.contains("knowledge_git") || Set.of("read_knowledge_source", "read_knowledge_sources", "browse_knowledge_source", "inspect_knowledge_project").contains(tool)) {
            body.put("citationId", "call:" + receipt); body.put("collectedAt", Instant.now().toString());
            body.put("sourceAuthorizationFrozenAt", binding.frozenAt());
            if (tool.equals("read_knowledge_sources")) citeBatch(body, receipt);
        }
        return body;
    }
    public boolean completeNodeEvidence(AssistScopeService.Scope scope,String id,String content,String now) {
        return access.completeNodeEvidence(scope,id,content,now);
    }
    @SuppressWarnings("unchecked")
    private static void citeBatch(Map<String,Object> body, String receipt) {
        var items = new ArrayList<Map<String,Object>>();
        for (var item : (List<Map<String,Object>>)body.get("items")) {
            var row = new LinkedHashMap<>(item);
            if (item.get("result") instanceof Map<?,?> result) {
                var saved = new LinkedHashMap<>((Map<String,Object>)result); saved.put("citationId", "call:" + receipt);
                saved.put("evidenceItem", item.get("index")); saved.put("collectedAt", body.get("collectedAt")); row.put("result", saved);
            }
            items.add(row);
        }
        body.put("items", items);
    }
    private Map<String,Object> listEvidence(String session, Map<String,Object> args) {
        String cursor = Objects.toString(args.get("cursor"), "");
        var page = cursor.isBlank() ? null : io.opencode.loopper.service.PageCursor.decode(cursor);
        if (page != null && mapper.evidence(session, page.id()) == null) throw KnowledgeSources.bad("证据游标不属于当前角色会话");
        var rows = mapper.evidencePage(session, page == null ? "" : page.value(), page == null ? "" : page.id());
        var result = new LinkedHashMap<String,Object>();
        result.put("items", rows.stream().limit(50).map(r -> Map.of("reference", "call:" + r.id(), "tool", r.toolName(), "collectedAt", r.createdAt())).toList());
        result.put("nextCursor", rows.size() > 50 ? new io.opencode.loopper.service.PageCursor(rows.get(49).createdAt(), rows.get(49).id()).encode() : null); return result;
    }
    private Map<String,Object> evidence(String session, Map<String,Object> args) {
        String reference = Objects.toString(args.get("reference"), "");
        if (!reference.matches("call:[a-f0-9-]{36}")) throw KnowledgeSources.bad("请使用本角色实际读取返回的 call: 引用");
        String saved = mapper.evidence(session, reference.substring(5));
        if (saved == null) throw KnowledgeSources.bad("引用不存在或属于其他角色会话");
        Object requested = args.getOrDefault("offset", 0);
        if (!(requested instanceof Number n) || n.doubleValue() != n.intValue()) throw KnowledgeSources.bad("引用读取位置必须是整数");
        int offset = n.intValue();
        if (offset < 0 || offset > saved.length()) throw KnowledgeSources.bad("引用读取位置无效");
        int end = Math.min(saved.length(), offset + 12000);
        if (end < saved.length() && end > offset && Character.isHighSurrogate(saved.charAt(end - 1))) end--;
        return Map.of("reference", reference, "content", saved.substring(offset, end), "nextOffset", end < saved.length() ? end : -1, "historical", true, "collectedAt", json.readTree(saved).path("collectedAt").asText(""));
    }
}
