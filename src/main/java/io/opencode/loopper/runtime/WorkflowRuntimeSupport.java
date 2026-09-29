package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowModelMapper.Launch;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

/** A model can address only its signed attempt. Grants exist in outbound messages, never in SQLite prompts. */
@Component
public final class WorkflowRuntimeSupport {
    private static final String MARKER="[Loopper 流程节点工具身份]";
    private final WorkflowModelMapper models;
    private final WorkflowExecutionMapper nodes;
    private final InternalMcpRuntimeAccess runtime;
    private final ConfiguredRoleRuntime roles;
    private final tools.jackson.databind.ObjectMapper json;
    public WorkflowRuntimeSupport(WorkflowModelMapper models, WorkflowExecutionMapper nodes, InternalMcpRuntimeAccess runtime,
            ConfiguredRoleRuntime roles, tools.jackson.databind.ObjectMapper json) {
        this.models=models; this.nodes=nodes; this.runtime=runtime; this.roles=roles; this.json=json;
    }
    void enrich(String session, Map<String,Object> body, OpenCodeClient.SessionProfile profile) {
        if (!WorkflowModelProfile.contains(profile)) return;
        var row=models.session(session).orElseThrow(WorkflowRuntimeSupport::denied);
        if (!Set.of("DISPATCHING","RUNNING").contains(row.state()) || row.suspended()
                || !message(row).equals(body.get("messageID")) || !(body.get("parts") instanceof List<?> original)) throw denied();
        var parts=new ArrayList<Object>(original);
        parts.add(Map.of("type","text","synthetic",true,"text",notice(row)));
        body.put("parts",parts);
    }
    JsonNode verifyIdentityNotice(String session, OpenCodeClient.PromptRequest expected, JsonNode body) {
        var row=models.session(session).orElseThrow(WorkflowRuntimeSupport::denied);
        var parts=body==null?null:body.get("parts");
        if (!message(row).equals(expected.messageId()) || parts==null || !parts.isArray()
                || parts.size()!=expected.files().size()+2) throw changedMessage();
        var last=parts.get(parts.size()-1);
        if (!last.path("type").asText().equals("text") || !last.path("synthetic").isBoolean()
                || !last.path("synthetic").asBoolean() || !last.path("text").asText().equals(notice(row))) throw changedMessage();
        ObjectNode result=((ObjectNode)body).deepCopy();
        ((ArrayNode)result.get("parts")).remove(parts.size()-1);
        return result;
    }
    public String grant(Launch row) {
        var attempt=nodes.attempt(row.attemptId()).orElseThrow(WorkflowRuntimeSupport::denied);
        var active=runtime.current().orElseThrow(WorkflowRuntimeSupport::denied);
        if (attempt.externalSessionId()==null || row.creationPlanJson()==null) throw denied();
        // The plan is immutable, and the attested Session binding is registered by the transport.
        var plan=json.readTree(row.creationPlanJson());
        if (!active.generation().equals(plan.path("runtimeGenerationId").asText())
                || !active.serverName().equals(plan.path("internalMcpServer").asText())) throw denied();
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(row.attemptId().getBytes(StandardCharsets.UTF_8));
        String material=active.generation()+"\n"+row.requirementId()+"\n"+row.attemptId()+"\n"+attempt.externalSessionId()+"\n"+message(row);
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(active.bearerToken().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return "lpw_"+encoded+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    public Launch authorize(String scope, String attemptId, String tool) {
        if (scope==null || scope.length()>512 || attemptId==null || !WorkflowModelProfile.TOOLS.contains(tool)) throw denied();
        var row=models.find(attemptId).orElseThrow(WorkflowRuntimeSupport::denied);
        if (!MessageDigest.isEqual(grant(row).getBytes(StandardCharsets.UTF_8),scope.getBytes(StandardCharsets.UTF_8))) throw denied();
        var attempt=nodes.attempt(attemptId).orElseThrow(WorkflowRuntimeSupport::denied);
        roles.requireFrozenInternalTool(attempt.externalSessionId(),runtime.current().orElseThrow(WorkflowRuntimeSupport::denied).serverName(),tool);
        return row;
    }
    private String notice(Launch row) {
        return MARKER+"\nattemptId="+row.attemptId()+"\nscope="+grant(row)
                +"\n仅将本通知的 attemptId 和 scope 用于当前节点的 MCP 工具；不得复制到交付物或最终回答。";
    }
    private static String message(Launch row) { return "msg_"+row.attemptId().replace("-",""); }
    private static SessionFailure changedMessage() { return new SessionFailure("OPENCODE_PROMPT_LOOKUP_INVALID_RESPONSE","远端请求的节点身份与原始请求不一致，已停止投递并保留现场"); }
    private static SessionFailure denied() { return new SessionFailure("WORKFLOW_SCOPE_DENIED","流程工具身份不属于当前尝试、会话或运行环境，请保留现场并恢复原运行"); }
}
