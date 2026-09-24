package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.PptAgentMapper;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/** Signed per-run capability injected only into the outbound HTTP body, never the durable prompt. */
@Component
public class PptRuntimeSupport {
    @org.springframework.beans.factory.annotation.Autowired(required = false) private ConfiguredRoleRuntime roles;
    private static final Set<String> ACTIVE_STATES = Set.of("SENDING", "UNKNOWN", "RUNNING");
    private static final String NOTICE_MARKER = "[Loopper PPT 本轮工具身份通知]";
    private final PptAgentMapper mapper;
    private final InternalMcpRuntimeAccess runtime;
    public PptRuntimeSupport(PptAgentMapper mapper, InternalMcpRuntimeAccess runtime) {
        this.mapper = mapper; this.runtime = runtime;
    }
    void enrich(String session, Map<String, Object> body, OpenCodeClient.SessionProfile profile) {
        if (profile != OpenCodeClient.SessionProfile.PPT_AGENT) return;
        var run = mapper.session(session).orElseThrow(PptRuntimeSupport::denied);
        if (!ACTIVE_STATES.contains(run.state())) throw denied();
        if (!Objects.equals(body.get("messageID"), run.messageId()) || !(body.get("parts") instanceof List<?> original)) throw denied();
        String scope = grant(run);
        var parts = new ArrayList<Object>(original);
        parts.add(Map.of("type", "text", "synthetic", true, "text", identityNotice(run, scope)));
        body.put("parts", parts);
        body.put("agent", PptAgentProfile.AGENT);
        body.put("system", Objects.toString(body.get("system"), "")
                + "\n本轮 PPT 工具调用身份（messageId=" + run.messageId() + "）：documentId=" + run.documentId() + "; runId=" + run.id()
                + "; scope=" + scope + "。scope 仅供调用，不得复制到正文、文件或最终回答。"
                + "回答问题后的每轮调用凭证都会更新，历史消息与工具调用中的 scope 均已失效；只使用当前用户消息末尾的本轮工具身份通知，不复制上轮参数中的 scope。"
                + "若收到 PPT_SCOPE_EXPIRED，请重新读取本轮工具身份通知并用当前 scope 重交原操作，不猜测凭证，不因已确认需求而跳过保存。"
                + "所有 PPT 工具的具体参数放在 args 对象中；先查询能力，不能猜测对象操作参数。");
    }
    private String identityNotice(Run run, String scope) {
        return NOTICE_MARKER + "\nmessageId=" + run.messageId() + "\ndocumentId=" + run.documentId()
                + "\nrunId=" + run.id() + "\nscope=" + scope
                + "\n这是程序为当前轮次自动附加的调用身份。后续所有 PPT 工具均使用这里的 documentId、runId、scope；历史调用中的 scope 已失效。"
                + "保持原工具参数和幂等键，仅将已过期身份替换为本通知的当前身份；不得把 scope 写入正文、作品或最终回答。";
    }
    /** Only a managed PPT profile may call this; ordinary prompt and attachment validation follows unchanged. */
    JsonNode verifyIdentityNotice(String session, OpenCodeClient.PromptRequest expected, JsonNode body) {
        if (body == null || !body.isObject()) return body;
        var parts = body.get("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) return body;
        if (parts.size() == expected.files().size() + 1) return body; // A legacy business text can itself begin with the marker.
        var last = parts.get(parts.size() - 1);
        if (!last.path("text").asText("").startsWith(NOTICE_MARKER)) return body; // Pre-notice requests keep exact legacy recovery.
        var run = mapper.session(session).orElseThrow(PptRuntimeSupport::denied);
        if (!Objects.equals(run.messageId(), expected.messageId()) || !Objects.equals(run.externalSessionId(), session)
                || !last.path("type").asText().equals("text") || !last.path("synthetic").isBoolean()
                || !last.path("synthetic").asBoolean() || !last.path("text").isTextual()
                || !last.path("text").asText().equals(identityNotice(run, grant(run)))) throw invalidNotice();
        ObjectNode normalized = ((ObjectNode) body).deepCopy();
        ((ArrayNode) normalized.get("parts")).remove(parts.size() - 1);
        return normalized;
    }
    private static SessionFailure invalidNotice() {
        return new SessionFailure("OPENCODE_PROMPT_LOOKUP_INVALID_RESPONSE", "PPT prompt lookup returned a different current-round tool identity notice");
    }
    public String grant(Run run) {
        return token(current(run), run, run.messageId());
    }
    private String token(InternalMcpCredentialProvider.Credentials active, Run run, String messageId) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(run.id().getBytes(StandardCharsets.UTF_8));
        return "lpp_" + encoded + "." + signature(active, run, messageId);
    }
    public Run authorize(String token, String runId, String documentId) {
        if (token == null || token.length() > 512 || runId == null || documentId == null) throw denied();
        var run = mapper.run(runId).orElseThrow(PptRuntimeSupport::denied);
        if (!run.documentId().equals(documentId)) throw denied();
        var active = current(run);
        if (matches(token(active, run, run.messageId()), token)) return run;
        if (ACTIVE_STATES.contains(run.state())) {
            for (String previous : mapper.previousPromptMessageIds(run.id(), run.round())) {
                if (matches(token(active, run, previous), token)) {
                    current(run); // A retired runtime never becomes a correctable identity mismatch.
                    throw expired();
                }
            }
        }
        throw denied();
    }
    public void validateGeneration(Run run) { current(run); }
    public List<String> allowedTools(Run run, List<String> tools) {
        return roles == null ? tools : roles.allowedInternalTools(run.externalSessionId(), current(run).serverName(), tools);
    }
    public void requireTool(Run run, String tool) {
        if (!allowedTools(run, List.of(tool)).contains(tool)) throw new SessionFailure("PPT_ROLE_TOOL_DENIED", "当前角色配置未授权此工具，请使用已开放的工具或创建采用新配置的运行");
    }
    public boolean isCurrentGeneration(String generation) {
        return runtime.current().map(active -> active.generation().equals(generation)).orElse(false);
    }
    private InternalMcpCredentialProvider.Credentials current(Run run) {
        var active = runtime.current().orElseThrow(PptRuntimeSupport::denied);
        if (run.externalSessionId() == null || !active.generation().equals(run.generation())) throw denied();
        return active;
    }
    private boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
    private String signature(InternalMcpCredentialProvider.Credentials active, Run run, String messageId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(active.bearerToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String value = active.generation() + ":" + run.id() + ":" + run.documentId() + ":" + run.externalSessionId() + ":" + messageId;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("PPT capability signing unavailable"); }
    }
    private static SessionFailure expired() { return new SessionFailure("PPT_SCOPE_EXPIRED", "回答问题后调用凭证已更新，请从当前用户消息末尾的本轮工具身份通知取工具身份，不能复制历史调用 scope；本次操作未执行，请使用当前凭证重交"); }
    private static SessionFailure denied() { return new SessionFailure("PPT_SCOPE_DENIED", "PPT 工具授权不属于当前作品、会话或运行环境，请停止后由系统恢复"); }
}
