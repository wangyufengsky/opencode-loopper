package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignDiscussionRevisionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DesignerQuestionHistoryTest {
    @Test void frozenHistoryReconstructsPersistedQuestionsOptionsAndAnswersWithoutRemoteReads() {
        var mapper = mock(LoopperMapper.class); var remote = mock(OpenCodeClient.class);
        String decisions = """
                [{"questionId":"q1","answeredAt":"2026-09-02T00:00:00Z",
                  "questions":[{"header":"范围","question":"保留哪些角色？","multiple":false,"custom":true,
                    "options":[{"label":"用户与设计师","description":"保留原始讨论"}]}],
                  "answers":[["用户与设计师"]]}]
                """;
        var round = new DesignDiscussionRevisionRow("round", "source-designer", null, "REQUIREMENT", null,
                2, "COMPLETED", "user", "system-snapshot", "frozen", decisions, true, true, 0,
                null, null, null, "now", "now", 0);
        when(mapper.listDesignDiscussionRevisions("source-designer")).thenReturn(List.of(round));
        var service = new DesignerQuestionSupport(mapper, remote, JsonMapper.builder().build());
        var history = service.history("source-designer");
        assertThat(history).singleElement().satisfies(entry -> {
            assertThat(entry.designMessageId()).isEqualTo("system-snapshot");
            assertThat(entry.discussionRevision()).isEqualTo(2);
            assertThat(entry.questions()).singleElement().satisfies(question -> {
                assertThat(question.question()).isEqualTo("保留哪些角色？");
                assertThat(question.options().getFirst().description()).isEqualTo("保留原始讨论");
                assertThat(question.answers()).containsExactly("用户与设计师");
            });
        });
        assertThat(service.history("source-designer")).isEqualTo(history);
        verifyNoInteractions(remote);
    }
}
