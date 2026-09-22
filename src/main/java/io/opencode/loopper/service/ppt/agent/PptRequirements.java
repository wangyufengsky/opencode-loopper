package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.persistence.PptAgentRows.Question;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import io.opencode.loopper.service.ppt.PptSupport;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Frozen requirements protocol and explicit human acceptance, independent of model completion. */
public final class PptRequirements {
    public static final String PROTOCOL = "DIALOGUE_CONFIRMATION_V1";
    public static final String CLARIFICATION = "CLARIFICATION";
    public static final String CONFIRMATION = "REQUIREMENTS_CONFIRMATION";
    private static final Set<String> DESIGN_WRITES = Set.of("ppt_submit_plan", "ppt_apply_operations", "ppt_render_preview", "ppt_export");
    private PptRequirements() { }

    static void freeze(ObjectNode context, PptAgentWorkflowGate.Authorization authorization) {
        if (authorization != null && authorization.mode().equals("CREATE") && authorization.step().equals("PLANNING"))
            context.put("requirementsProtocol", PROTOCOL);
    }
    static boolean enabled(Run run, ObjectMapper json) {
        return PROTOCOL.equals(json.readTree(run.contextJson()).path("requirementsProtocol").asText());
    }
    static String questionKind(JsonNode args, Run run, List<Question> questions, ObjectMapper json) {
        var field = args.get("kind");
        if (field != null && !field.isTextual()) throw PptAgentService.bad("问题 kind 必须为 CLARIFICATION 或 REQUIREMENTS_CONFIRMATION");
        String kind = field == null ? CLARIFICATION : field.asText();
        if (!Set.of(CLARIFICATION, CONFIRMATION).contains(kind)) throw PptAgentService.bad("问题 kind 必须为 CLARIFICATION 或 REQUIREMENTS_CONFIRMATION");
        if (CONFIRMATION.equals(kind)) {
            if (!enabled(run, json)) throw PptAgentService.bad("当前请求没有首次需求确认协议；请使用普通问题");
            if (!hasDialogue(run, questions, json))
                throw PptSupport.bad("PPT_REQUIREMENTS_DIALOGUE_REQUIRED", "请先围绕内容重点或设计偏好提出关键问题并等待用户回答，再汇总需求确认；已有信息不要重复询问");
        }
        return kind;
    }
    static Boolean replyDecision(Question question, Boolean confirmed) {
        if (CONFIRMATION.equals(question.kind())) return Boolean.TRUE.equals(confirmed);
        if (confirmed != null) throw PptAgentService.bad("普通问题不能确认制作需求，请回答当前问题");
        return null;
    }
    static String state(Run run, List<Question> questions, ObjectMapper json) {
        if (!enabled(run, json)) return "NOT_REQUIRED";
        if (questions.isEmpty()) return "CLARIFYING";
        var latest = questions.getLast();
        if (!CONFIRMATION.equals(latest.kind())) return "CLARIFYING";
        if (!hasDialogue(run, questions, json)) return "CLARIFYING";
        if (latest.state().equals("PENDING")) return "AWAITING_CONFIRMATION";
        return latest.state().equals("ANSWERED") && Boolean.TRUE.equals(latest.confirmed()) ? "CONFIRMED" : "CLARIFYING";
    }
    public static void requireConfirmed(Run run, List<Question> questions, ObjectMapper json) {
        String state = state(run, questions, json);
        if (!permits("ppt_submit_plan", state))
            throw PptSupport.bad("PPT_REQUIREMENTS_CONFIRMATION_REQUIRED", "尚未完成需求确认。请通过 ppt_request_input 逐轮澄清内容与设计要求，汇总后以 kind=REQUIREMENTS_CONFIRMATION 请求用户明确确认；确认前不得提交方案或制作页面");
    }
    static boolean permits(String tool, String requirementsState) {
        return !DESIGN_WRITES.contains(tool) || Set.of("NOT_REQUIRED", "CONFIRMED").contains(requirementsState);
    }
    static void validateWrite(String tool, Run run, List<Question> questions, ObjectMapper json) {
        if (DESIGN_WRITES.contains(tool)) requireConfirmed(run, questions, json);
    }
    private static boolean hasDialogue(Run run, List<Question> questions, ObjectMapper json) {
        if (questions.stream().anyMatch(q -> CLARIFICATION.equals(q.kind()) && q.state().equals("ANSWERED"))) return true;
        // This server-owned snapshot contains answers from the same automatic authorization across stopped runs.
        var inherited = json.readTree(run.contextJson()).path("generationAnswers");
        return inherited.isArray() && !inherited.isEmpty();
    }
    static String guidance(Run run, List<Question> questions, ObjectMapper json) {
        if (!enabled(run, json)) return "";
        String state = state(run, questions, json);
        return "\n服务端需求确认协议=" + PROTOCOL + "，当前需求状态=" + state + "。"
                + "首次设计前必须先与用户多轮沟通：先消化需求与获准资料，每轮只问一个影响内容重点或设计效果的关键问题，给出易选建议；已有答案不重复问。"
                + "至少完成一轮 CLARIFICATION 问答；恢复时 generationAnswers 中已保存的问答可沿用，不重复询问，但仍需重新汇总并明确确认本次需求。"
                + "可推断的偏好提出默认建议让用户补充，不让用户填写长表单。"
                + "内容和设计要求充分后，调用 ppt_request_input(kind=REQUIREMENTS_CONFIRMATION)，prompt 汇总目的、受众、重点、页数/时长、视觉风格、资料范围及数据缺口，然后停止等待。"
                + "只有用户点击确认动作且状态为 CONFIRMED 后才开始第一轮设计，提交完整方案，随后自动制作与导出。"
                + "普通回答、肯定语气、模型自行选择、工具成功或用户最初点击生成都不等于需求确认。"
                + "用户补充/修改需求后继续澄清并重新汇总确认，旧确认不可套用；确认后的首次方案无需另做方向或制作确认。";
    }
}
