package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.persistence.PptAgentRows.Run;
import io.opencode.loopper.runtime.PptAgentProfile;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class PptAgentPromptsTest {
    private final ObjectMapper json=new ObjectMapper();
    private Run run(String phase,String context) {
        return new Run("run","document","key","sha","制作中文培训稿","{\"kind\":\"DOCUMENT\"}",0,phase,"{}","/tmp",
                context,"PREPARED","","",null,null,null,"message",null,null,0,0,null,null,null,null,"now","now",0);
    }
    @Test void automaticRequestUsesItsOwnFrozenWorkflowAndPlainUserResponseContract() {
        var run=run("BRIEFING","{\"generationAuthorization\":{\"generationId\":\"g\",\"attempt\":0,\"step\":\"PLANNING\",\"mode\":\"CREATE\"}}");
        var prompt=PptAgentPrompts.build(run,List.of(),json);
        assertThat(prompt.agent()).isEqualTo(PptAgentProfile.AGENT);
        assertThat(prompt.system()).contains(PptAgentProfile.AUTOMATIC_PROMPT,"普通中文2–4句","已授权自动导出","程序正在准备预览和下载")
                .doesNotContain("不得替用户选定方向","用户点击开始制作后才","用户明确要求导出时才");
        assertThat(prompt.text()).isEqualTo(run.userText());
    }
    @Test void manualRequestKeepsOriginalDirectionAndProductionApprovalBoundaries() {
        var prompt=PptAgentPrompts.build(run("DESIGN","{}"),List.of(),json);
        assertThat(prompt.system()).contains(PptAgentProfile.PROMPT,"不得替用户选定方向","用户点击开始制作后才","用户明确要求导出时才")
                .doesNotContain("服务端冻结的本次自动生成授权");
    }
    @Test void newInitialDesignRequiresDialogueAndExplicitConfirmationBeforeAnyPlan() {
        var request = run("BRIEFING", "{\"generationAuthorization\":{\"generationId\":\"g\",\"attempt\":0,\"step\":\"PLANNING\",\"mode\":\"CREATE\"},\"requirementsProtocol\":\"DIALOGUE_CONFIRMATION_V1\"}");
        var prompt = PptAgentPrompts.build(request, List.of(), json);
        assertThat(prompt.system()).contains("当前需求状态=CLARIFYING", "至少完成一轮 CLARIFICATION", "已有答案不重复问", "kind=REQUIREMENTS_CONFIRMATION", "用户点击确认动作");
        var question = new io.opencode.loopper.persistence.PptAgentRows.Question("q", "run", "document", "10页商务汇报", "[]",
                "ANSWERED", "可以，但改成8页", "key", "sha", "now", 1, PptRequirements.CONFIRMATION, false);
        var rejected = PptAgentPrompts.build(request, List.of(question), json);
        assertThat(rejected.text()).contains("用户明确确认需求：false", "改成8页").doesNotContain("已确认的回答");
        assertThat(rejected.system()).contains("当前需求状态=CLARIFYING");
    }
}
