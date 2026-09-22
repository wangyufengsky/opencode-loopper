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

/** Signed per-run capability injected only into the outbound HTTP body, never the durable prompt. */
@Component
public class PptRuntimeSupport {
    private final PptAgentMapper mapper;
    private final InternalMcpRuntimeAccess runtime;
    public PptRuntimeSupport(PptAgentMapper mapper, InternalMcpRuntimeAccess runtime) {
        this.mapper = mapper; this.runtime = runtime;
    }
    void enrich(String session, Map<String, Object> body, OpenCodeClient.SessionProfile profile) {
        if (profile != OpenCodeClient.SessionProfile.PPT_AGENT) return;
        var run = mapper.session(session).orElseThrow(PptRuntimeSupport::denied);
        if (!Set.of("SENDING", "UNKNOWN", "RUNNING").contains(run.state())) throw denied();
        String scope = grant(run);
        body.put("agent", PptAgentProfile.AGENT);
        body.put("system", Objects.toString(body.get("system"), "")
                + "\nPPT 工具调用身份：documentId=" + run.documentId() + "; runId=" + run.id()
                + "; scope=" + scope + "。scope 仅供调用，不得复制到正文、文件或最终回答。"
                + "所有 PPT 工具的具体参数放在 args 对象中；先查询能力，不能猜测对象操作参数。");
    }
    public String grant(Run run) {
        var active = current(run);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(run.id().getBytes(StandardCharsets.UTF_8));
        return "lpp_" + encoded + "." + signature(active, run);
    }
    public Run authorize(String token, String runId, String documentId) {
        if (token == null || token.length() > 512 || runId == null || documentId == null) throw denied();
        var run = mapper.run(runId).orElseThrow(PptRuntimeSupport::denied);
        if (!run.documentId().equals(documentId)) throw denied();
        String expected = grant(run);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) throw denied();
        return run;
    }
    public void validateGeneration(Run run) { current(run); }
    public boolean isCurrentGeneration(String generation) {
        return runtime.current().map(active -> active.generation().equals(generation)).orElse(false);
    }
    private InternalMcpCredentialProvider.Credentials current(Run run) {
        var active = runtime.current().orElseThrow(PptRuntimeSupport::denied);
        if (run.externalSessionId() == null || !active.generation().equals(run.generation())) throw denied();
        return active;
    }
    private String signature(InternalMcpCredentialProvider.Credentials active, Run run) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(active.bearerToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String value = active.generation() + ":" + run.id() + ":" + run.documentId() + ":" + run.externalSessionId() + ":" + run.messageId();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("PPT capability signing unavailable"); }
    }
    private static SessionFailure denied() { return new SessionFailure("PPT_SCOPE_DENIED", "PPT 工具授权不属于当前作品、会话或运行环境，请停止后由系统恢复"); }
}
