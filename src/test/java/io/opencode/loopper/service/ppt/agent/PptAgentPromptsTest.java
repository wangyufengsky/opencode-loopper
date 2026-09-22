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
}
