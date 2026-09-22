package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.PptAgentMapper;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PptRuntimeSupportTest {
    private final PptAgentMapper mapper = mock(PptAgentMapper.class);
    private final InternalMcpRuntimeAccess runtime = new InternalMcpRuntimeAccess();
    private final PptRuntimeSupport support = new PptRuntimeSupport(mapper, runtime);
    private final InternalMcpCredentialProvider.Credentials credentials = new InternalMcpCredentialProvider(() -> 8080).issue();
    @BeforeEach void activate() { runtime.activate(credentials); }
    private Run run(String id, String document, String session, int round, String state) {
        return new Run(id, document, "request-key", "sha", "制作", "{}", 0, "BRIEFING", "{}", "/ppt", "{}", state,
                "", "", "{}", session, credentials.generation(), "msg_" + id + "_" + round, "{}", "sha", 1, round,
                null, null, null, null, "now", "now", 0);
    }
    private Run current(String state) {
        var run = run("run", "document", "session", 2, state);
        when(mapper.run(run.id())).thenReturn(Optional.of(run)); return run;
    }
    private void denied(String token, Run run) {
        assertThatThrownBy(() -> support.authorize(token, run.id(), run.documentId()))
                .isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code()).isEqualTo("PPT_SCOPE_DENIED"));
    }
    @Test void currentRoundTokenIsAcceptedWithoutReadingHistoricalPromptBodies() {
        var run = current("RUNNING");
        assertThat(support.authorize(support.grant(run), run.id(), run.documentId())).isEqualTo(run);
        verify(mapper, never()).previousPromptMessageIds(anyString(), anyInt());
    }
    @Test void onlyAnActuallyDispatchedPreviousRoundGetsCorrectableExpiredIdentityWithoutAnyNewToken() {
        var run = current("RUNNING"); var previous = run("run", "document", "session", 1, "RUNNING");
        String oldToken = support.grant(previous), newToken = support.grant(run);
        when(mapper.previousPromptMessageIds(run.id(), run.round())).thenReturn(List.of(previous.messageId(), "msg_run_0"));
        assertThatThrownBy(() -> support.authorize(oldToken, run.id(), run.documentId()))
                .isInstanceOfSatisfying(SessionFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PPT_SCOPE_EXPIRED");
                    assertThat(failure.getMessage()).contains("本轮工具身份通知", "本次操作未执行", "不能复制历史调用 scope")
                            .doesNotContain(oldToken, newToken, credentials.bearerToken());
                });
        assertThat(support.authorize(newToken, run.id(), run.documentId())).isEqualTo(run);
        verify(mapper).previousPromptMessageIds(run.id(), 2);
    }
    @Test void randomCrossScopeAndUnrecordedOrFutureRoundTokensRemainDenied() {
        var run = current("RUNNING");
        when(mapper.previousPromptMessageIds(run.id(), run.round())).thenReturn(List.of("msg_run_0"));
        denied("lpp_random.invalid", run);
        denied(support.grant(run("other-run", "document", "session", 0, "RUNNING")), run);
        denied(support.grant(run("run", "other-document", "session", 0, "RUNNING")), run);
        denied(support.grant(run("run", "document", "other-session", 0, "RUNNING")), run);
        denied(support.grant(run("run", "document", "session", 1, "RUNNING")), run);
        denied(support.grant(run("run", "document", "session", 3, "RUNNING")), run);
        assertThatThrownBy(() -> support.authorize(support.grant(run), run.id(), "other-document"))
                .isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code()).isEqualTo("PPT_SCOPE_DENIED"));
    }
    @Test void stoppedWaitingAndRetiredScopesDoNotOfferRetryAndExistingReceiptReplayIsPreserved() {
        String previous = support.grant(run("run", "document", "session", 0, "RUNNING"));
        for (String state : List.of("WAITING_INPUT", "STOPPING", "STOPPED", "COMPLETED", "FAILED")) {
            var run = current(state); denied(previous, run);
            // The downstream authority still rejects fresh writes; exact stored receipts can be replayed.
            assertThat(support.authorize(support.grant(run), run.id(), run.documentId())).isEqualTo(run);
        }
        verify(mapper, never()).previousPromptMessageIds(anyString(), anyInt());
        var run = current("RUNNING"); String token = support.grant(run);
        runtime.activate(new InternalMcpCredentialProvider(() -> 8080).issue());
        denied(token, run); denied(previous, run);
    }
    @Test void outboundPromptNamesTheCurrentMessageAndExplainsScopeRotationWithoutChangingDurableInput() {
        var run = current("SENDING"); when(mapper.session(run.externalSessionId())).thenReturn(Optional.of(run));
        String durable = "冻结工作流";
        var request = new OpenCodeClient.PromptRequest("用户确认后的原始业务请求", durable, PptAgentProfile.AGENT,
                new OpenCodeClient.ResponseFormat.Text(), run.messageId(), List.of());
        String digest = OpenCodeClient.promptRequestSha256(request);
        var outbound = OpenCodePromptBody.encode(request, OpenCodeClient.SessionProfile.PPT_AGENT, true, null, List.of());
        support.enrich(run.externalSessionId(), outbound, OpenCodeClient.SessionProfile.PPT_AGENT);
        assertThat(outbound.get("system").toString()).contains(durable, run.messageId(), support.grant(run), "历史消息与工具调用中的 scope 均已失效", "本轮工具身份通知")
                .doesNotContain(credentials.bearerToken());
        var parts = (List<?>) outbound.get("parts");
        assertThat(parts).hasSize(2); assertThat(parts.getFirst()).isEqualTo(Map.of("type", "text", "text", request.text()));
        assertThat(parts.getLast()).isInstanceOfSatisfying(Map.class, notice -> {
            assertThat(notice.get("synthetic")).isEqualTo(true);
            assertThat(notice.get("text").toString()).contains(run.messageId(), support.grant(run), "本轮工具身份通知");
        });
        assertThat(request.system()).isEqualTo(durable); assertThat(request.text()).isEqualTo("用户确认后的原始业务请求");
        assertThat(OpenCodeClient.promptRequestSha256(request)).isEqualTo(digest);
        assertThat(outbound.get("agent")).isEqualTo(PptAgentProfile.AGENT);
        var otherRole = new HashMap<String, Object>(Map.of("system", durable));
        support.enrich(run.externalSessionId(), otherRole, OpenCodeClient.SessionProfile.KNOWLEDGE_READ_ONLY);
        assertThat(otherRole).containsExactlyEntriesOf(Map.of("system", durable));
    }
}
