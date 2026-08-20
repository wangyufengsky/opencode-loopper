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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesignerActivityServiceTest {
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final OpenCodeClient openCode = mock(OpenCodeClient.class);
    private final DesignerActivityService activities = new DesignerActivityService(mapper, projects, openCode);

    @Test
    void exposesDesignerThinkingOutputAndToolCallsWithBoundedContent() {
        DesignerSessionRow session = session("DESIGNING", "designer-remote");
        OpenCodeClient.OpenCodeSession remote = remote("designer-remote");
        when(mapper.findDesignerSession(session.id())).thenReturn(Optional.of(session));
        when(projects.get(session.projectId())).thenReturn(project());
        when(openCode.sessionStatus(remote)).thenReturn(new OpenCodeClient.SessionStatus("BUSY", "streaming"));
        when(openCode.sessionTranscript(remote)).thenReturn(new OpenCodeClient.SessionTranscript(List.of(
                new OpenCodeClient.SessionPart("thinking", "THINKING", "Thinking", "正在检查画像", "RUNNING"),
                new OpenCodeClient.SessionPart("tool", "TOOL", "gitlab_search",
                        "{\"query\":\"profile\"}\n返回 2 条", "COMPLETED"),
                new OpenCodeClient.SessionPart("output", "OUTPUT", "assistant", "设计稿已生成", null))));

        DesignerActivityService.View view = activities.activity(session.id());

        assertThat(view.actor()).isEqualTo("DESIGNER");
        assertThat(view.connected()).isTrue();
        assertThat(view.parts()).extracting(DesignerActivityService.Part::type)
                .containsExactly("THINKING", "TOOL", "OUTPUT");
        assertThat(view.parts().get(1).content()).contains("profile", "返回 2 条");
    }

    @Test
    void structuredRolesExposeOnlyToolActivityAndTheAuthoritativeStep() {
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
            assertThat(part.type()).isEqualTo("TOOL");
            assertThat(part.label()).isEqualTo("jira_search");
        });
        assertThat(view.parts()).noneMatch(part -> part.content().contains("intent"));
    }

    private DesignerSessionRow session(String phase, String remoteId) {
        return new DesignerSessionRow("designer-1", "project-1", "RUNNING", "READ_ONLY", "now", "now", 0,
                remoteId, "RUNNING", "draft-1", phase, 0, 0);
    }

    private ProjectRow project() {
        return new ProjectRow("project-1", "Project", "/tmp", "", "now", "now", 1, 0);
    }

    private OpenCodeClient.OpenCodeSession remote(String id) {
        return new OpenCodeClient.OpenCodeSession(id, Path.of("/tmp"));
    }
}
