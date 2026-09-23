package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.persistence.PptAgentMapper;
import io.opencode.loopper.runtime.PptRuntimeSupport;
import io.opencode.loopper.runtime.PptAgentProfile;
import io.opencode.loopper.service.assist.AssistRedaction;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A strict per-call envelope; dispatch is independent of generic code-role candidate protocols. */
@Service
public class PptAgentTools {
    private static final Set<String> WRITES = Set.of("ppt_request_input", "ppt_submit_plan", "ppt_apply_operations", "ppt_render_preview", "ppt_export");
    private static final Set<String> MANAGED_OUTPUTS=Set.of("ppt_render_preview","ppt_export");
    private final PptRuntimeSupport runtime;
    private final PptAgentAuthority authority;
    private final PptAgentWorkspace workspace;
    private final PptAgentMapper mapper;
    private final PptAgentToolWrites writes;
    private final ObjectMapper json;
    public PptAgentTools(PptRuntimeSupport runtime, PptAgentAuthority authority, PptAgentWorkspace workspace,
            PptAgentMapper mapper, PptAgentToolWrites writes, ObjectMapper json) {
        this.runtime = runtime; this.authority = authority; this.workspace = workspace;
        this.mapper = mapper; this.writes = writes; this.json = json;
    }
    public Object call(String tool, Map<String, Object> arguments) {
        if (!PptAgentProfile.TOOLS.contains(tool)) throw PptAgentService.bad("未知 PPT 工具");
        var envelope = json.valueToTree(arguments == null ? Map.of() : arguments);
        if (!envelope.isObject()) throw PptAgentService.bad("PPT 调用必须是对象");
        for (String key : envelope.propertyNames()) if (!Set.of("scope", "runId", "documentId", "args").contains(key)) throw PptAgentService.bad("PPT 调用含未知参数：" + key);
        for (String key : List.of("scope", "runId", "documentId"))
            if (!envelope.path(key).isTextual() || envelope.path(key).asText().isBlank()) throw PptAgentService.bad("缺少文字参数：" + key);
        var node = envelope.get("args");
        if (node == null || !node.isObject()) throw PptAgentService.bad("args 必须是 JSON 对象");
        if (node.has("agentScope") || node.has("agentRunId") || node.has("scope")) throw PptAgentService.bad("args 不得提供服务端身份或 scope");
        String encoded = json.writeValueAsString(node);
        if (encoded.length() > 500000 || !encoded.equals(AssistRedaction.text(encoded))) throw PptAgentService.bad("工具参数过大或包含内部凭证");
        var run = runtime.authorize(envelope.path("scope").asText(), envelope.path("runId").asText(), envelope.path("documentId").asText());
        boolean discussion = PptDiscussionTranscript.PROTOCOL.equals(json.readTree(run.contextJson()).path("pptDiscussionProtocol").asText());
        if (discussion && WRITES.contains(tool)) throw io.opencode.loopper.service.ppt.PptSupport.bad(
                "PPT_DISCUSSION_READ_ONLY", "需求讨论阶段不能写入方案或页面；点击“确认需求并执行”后才能制作");
        String key = node.path("idempotencyKey").asText(); String sha = PptAgentService.hash(PptAgentJson.canonical(node, json));
        if (WRITES.contains(tool)) {
            PptAgentService.key(key);
            var old = mapper.receipt(run.id(), key);
            if (old.isPresent()) return writes.replay(old.get(), tool, sha); // Exact receipt replay remains available after stop.
        }
        authority.validate(run, tool);
        PptRequirements.validateWrite(tool, run, mapper.questions(run.id()), json);
        var automatic=json.readTree(run.contextJson()).get("generationAuthorization");
        if(automatic!=null&&MANAGED_OUTPUTS.contains(tool))throw io.opencode.loopper.service.ppt.PptSupport.bad("PPT_OUTPUT_MANAGED",
                "本次自动生成的预览和导出由服务端统一处理。请完成保存和布局检查后结束本轮，程序将生成同版本预览与 PPTX，不要重复请求输出作业");
        if(tool.equals("ppt_submit_plan")&&automatic!=null&&automatic.path("mode").asText().equals("CREATE"))PptAutomaticPlan.validate(node.get("plan"));
        if (mapper.pending(run.id()).isPresent() && WRITES.contains(tool)) throw PptAgentPersistence.conflict("正在等待用户输入，请结束本轮");
        if (tool.equals("ppt_request_input")) return writes.question(run, node, key, sha);
        if(tool.equals("ppt_get_context")&&node.hasNonNull("source"))return PptAgentPayloads.read(run,node,mapper,json);
        ObjectNode args = ((ObjectNode) node).deepCopy();
        args.set("agentScope", json.readTree(run.scopeJson())); args.put("agentRunId", run.id());
        Object result = workspace.invoke(run.documentId(), tool, args, () -> {
            authority.validate(run, tool);
            PptRequirements.validateWrite(tool, run, mapper.questions(run.id()), json);
        });
        // A rendering or parsing operation may finish after the user stops. Never publish a fresh receipt in that case.
        authority.validate(run, tool);
        if (tool.equals("ppt_get_capabilities")) {
            var questions = mapper.questions(run.id());
            String requirementsState = PptRequirements.state(run, questions, json);
            result = Map.of("capabilities", result, "phase", run.phase(),
                    "generationAuthorization",automatic==null?json.nullNode():automatic,
                    "requirementsState", requirementsState,
                    "allowedTools", (discussion?PptAgentProfile.DISCUSSION_TOOLS:PptAgentProfile.TOOLS).stream().filter(t -> PptAgentAuthority.allowed(t, run.phase())
                            && PptRequirements.permits(t, requirementsState)
                            && (automatic==null||!MANAGED_OUTPUTS.contains(t))).toList());
        }
        if(PptAgentPayloads.PROTOCOL.equals(json.readTree(run.contextJson()).path("pptToolProtocol").asText()))
            result=PptAgentPayloads.compact(tool,node,json.valueToTree(result),json);
        String response = json.writeValueAsString(result);
        Object safe = json.readTree(AssistRedaction.text(response));
        return WRITES.contains(tool) ? writes.save(run, key, tool, sha, safe) : safe;
    }
}
