package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskProfileRouterRunRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesignerActivityServiceTest {
    @TempDir Path projectRoot;

    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final OpenCodeClient openCode = mock(OpenCodeClient.class);
    private final ModelTokenUsageProjectionService tokenUsage = mock(ModelTokenUsageProjectionService.class);
    private final DesignerActivityService activities = new DesignerActivityService(mapper, projects, openCode, tokenUsage);

    @Test
    void exposesOnlyTheLatestDesignerActivityFragment() {
        DesignerSessionRow session = session("DESIGNING", "designer-remote");
        OpenCodeClient.OpenCodeSession remote = remote("designer-remote");
        when(mapper.findDesignerSession(session.id())).thenReturn(Optional.of(session));
        when(projects.get(session.projectId())).thenReturn(project());
        when(openCode.sessionStatus(remote)).thenReturn(new OpenCodeClient.SessionStatus("BUSY", "streaming"));
        List<OpenCodeClient.UsageRecord> usage = List.of(new OpenCodeClient.UsageRecord(
                "message-1", "provider", "model", 120L, 30L, 150L, null, null, true));
        when(openCode.sessionTranscript(remote)).thenReturn(new OpenCodeClient.SessionTranscript(List.of(
                new OpenCodeClient.SessionPart("thinking", "THINKING", "Thinking", "正在检查画像", "RUNNING"),
                new OpenCodeClient.SessionPart("tool", "TOOL", "gitlab_search",
                        "{\"query\":\"profile\"}\n返回 2 条", "COMPLETED"),
                new OpenCodeClient.SessionPart("output", "OUTPUT", "assistant", "设计稿已生成", null)), usage));
        when(tokenUsage.observeDesigner(session.id(), root(), remote.id(), usage, false))
                .thenReturn(new ModelTokenUsageProjectionService.UsageView(150L, 0, "now"));

        DesignerActivityService.View view = activities.activity(session.id());

        assertThat(view.actor()).isEqualTo("DESIGNER");
        assertThat(view.connected()).isTrue();
        assertThat(view.usage().totalTokens()).isEqualTo(150L);
        assertThat(view.parts()).singleElement().satisfies(part -> {
            assertThat(part.type()).isEqualTo("OUTPUT");
            assertThat(part.content()).isEqualTo("设计稿已生成");
        });
    }

    @Test
    void routerExposesSafeLiveFragmentsButHidesTheMachineObject() {
        DesignerSessionRow session = session("ROUTING", null);
        TaskProfileRouterRunRow router = new TaskProfileRouterRunRow("router-1", session.id(), "RUNNING", "req",
                "[]", "router-remote", "RUNNING", "JSON_SCHEMA", null, null, null, "now", "now", 0);
        OpenCodeClient.OpenCodeSession remote = remote("router-remote");
        when(mapper.findDesignerSession(session.id())).thenReturn(Optional.of(session));
        when(mapper.findLatestTaskProfileRouterRun(session.id())).thenReturn(Optional.of(router));
        when(projects.get(session.projectId())).thenReturn(project());
        when(openCode.sessionStatus(remote)).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));
        when(openCode.sessionTranscript(remote)).thenReturn(new OpenCodeClient.SessionTranscript(List.of(
                new OpenCodeClient.SessionPart("thinking", "THINKING", "Thinking", "hidden", "RUNNING"),
                new OpenCodeClient.SessionPart("tool", "TOOL", "jira_search", "{\"key\":\"LOOP-1\"}", "COMPLETED"),
                new OpenCodeClient.SessionPart("output", "OUTPUT", "assistant", "{\"intent\":\"secret\"}", null))));

        DesignerActivityService.View view = activities.activity(session.id());

        assertThat(view.actor()).isEqualTo("ROUTER");
        assertThat(view.parts()).singleElement().satisfies(part -> {
            assertThat(part.type()).isEqualTo("OUTPUT");
            assertThat(part.content()).isEqualTo("正在整理任务设置识别结果…");
        });
        assertThat(view.parts()).noneMatch(part -> part.content().contains("intent"));
    }

    @Test
    void routerExposesBoundedPlainThinkingAndTokenProjection() {
        DesignerSessionRow session = session("ROUTING", null);
        TaskProfileRouterRunRow router = new TaskProfileRouterRunRow("router-safe", session.id(), "RUNNING", "req",
                "[]", "router-remote", "RUNNING", "TEXT_MARKER", null, null, null, "now", "now", 0);
        OpenCodeClient.OpenCodeSession remote = remote("router-remote");
        when(mapper.findDesignerSession(session.id())).thenReturn(Optional.of(session));
        when(mapper.findLatestTaskProfileRouterRun(session.id())).thenReturn(Optional.of(router));
        when(projects.get(session.projectId())).thenReturn(project());
        when(openCode.sessionStatus(remote)).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));
        List<OpenCodeClient.UsageRecord> usage = List.of(new OpenCodeClient.UsageRecord(
                "message-2", "provider", "model", 200L, 40L, 240L, null, null, true));
        when(openCode.sessionTranscript(remote)).thenReturn(new OpenCodeClient.SessionTranscript(List.of(
                new OpenCodeClient.SessionPart("thinking", "THINKING", "Thinking",
                        "正在核对 Maven 模块和测试框架", "RUNNING")), usage));
        when(tokenUsage.observeDesigner(session.id(), root(), remote.id(), usage, false))
                .thenReturn(new ModelTokenUsageProjectionService.UsageView(240L, 90L, "now"));

        DesignerActivityService.View view = activities.activity(session.id());

        assertThat(view.parts()).singleElement().satisfies(part -> {
            assertThat(part.type()).isEqualTo("THINKING");
            assertThat(part.content()).isEqualTo("正在核对 Maven 模块和测试框架");
        });
        assertThat(view.usage().totalTokens()).isEqualTo(240L);
    }

    @Test
    void routerHidesTheOpenCodeStepLimitControlNotice() {
        DesignerSessionRow session = session("ROUTING", null);
        TaskProfileRouterRunRow router = new TaskProfileRouterRunRow("router-step-limit", session.id(), "RUNNING", "req",
                "[]", "router-remote", "RUNNING", "TEXT_MARKER", null, null, null, "now", "now", 0);
        OpenCodeClient.OpenCodeSession remote = remote("router-remote");
        when(mapper.findDesignerSession(session.id())).thenReturn(Optional.of(session));
        when(mapper.findLatestTaskProfileRouterRun(session.id())).thenReturn(Optional.of(router));
        when(projects.get(session.projectId())).thenReturn(project());
        when(openCode.sessionStatus(remote)).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));
        when(openCode.sessionTranscript(remote)).thenReturn(new OpenCodeClient.SessionTranscript(List.of(
                new OpenCodeClient.SessionPart("output", "OUTPUT", "assistant", """
                        CRITICAL - MAXIMUM STEPS REACHED
                        The maximum number of steps allowed for this task has been reached. Tools are disabled until next user input.
                        Respond with text only.
                        """, null))));

        DesignerActivityService.View view = activities.activity(session.id());

        assertThat(view.parts()).singleElement().satisfies(part -> {
            assertThat(part.content()).isEqualTo("正在整理任务设置识别结果…");
            assertThat(part.content()).doesNotContain("MAXIMUM STEPS", "Tools are disabled");
        });
    }

    @Test
    void activeRerouteWinsOverTheCompletedRequirementDesignerProjection() {
        DesignerSessionRow session = session("DISCUSSING_REQUIREMENT", "completed-designer");
        TaskProfileRouterRunRow router = new TaskProfileRouterRunRow("router-2", session.id(), "RUNNING", "req",
                "[]", "router-reroute", "RUNNING", "TEXT_MARKER", null, null, null, "now", "now", 0);
        OpenCodeClient.OpenCodeSession remote = remote("router-reroute");
        when(mapper.findDesignerSession(session.id())).thenReturn(Optional.of(session));
        when(mapper.findLatestTaskProfileRouterRun(session.id())).thenReturn(Optional.of(router));
        when(projects.get(session.projectId())).thenReturn(project());
        when(openCode.sessionStatus(remote)).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));
        when(openCode.sessionTranscript(remote)).thenReturn(new OpenCodeClient.SessionTranscript(List.of(
                new OpenCodeClient.SessionPart("tool", "TOOL", "mcp_search", "正在复核完整需求", "RUNNING"))));

        DesignerActivityService.View view = activities.activity(session.id());

        assertThat(view.actor()).isEqualTo("ROUTER");
        assertThat(view.parts()).singleElement()
                .satisfies(part -> assertThat(part.label()).isEqualTo("mcp_search"));
    }

    private DesignerSessionRow session(String phase, String remoteId) {
        return new DesignerSessionRow("designer-1", "project-1", "RUNNING", "READ_ONLY", "now", "now", 0,
                remoteId, "RUNNING", "draft-1", phase, 0, 0);
    }

    private ProjectRow project() {
        return new ProjectRow("project-1", "Project", root().toString(), "", "now", "now", 1, 0);
    }

    private OpenCodeClient.OpenCodeSession remote(String id) {
        return new OpenCodeClient.OpenCodeSession(id, root());
    }

    private Path root() {
        return projectRoot.toAbsolutePath().normalize();
    }
}
