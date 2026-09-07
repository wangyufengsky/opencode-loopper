package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.domain.SessionFailure;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PackageSemanticPreparationTest {
    final LoopperMapper mapper = mock(LoopperMapper.class);
    final DesignerConversationCoordinator conversations = mock(DesignerConversationCoordinator.class);
    final OpenCodeClient remote = mock(OpenCodeClient.class);
    final DesignerPackageCandidateOrchestrator candidates = mock(DesignerPackageCandidateOrchestrator.class);
    final DesignerAttachmentContext attachments = mock(DesignerAttachmentContext.class);
    final AtomicReference<PackageSemanticPreparationRow> stored = new AtomicReference<>();
    final DesignWorkPackageRow owner = mock(DesignWorkPackageRow.class);
    final DesignDiscussionRevisionRow discussion = mock(DesignDiscussionRevisionRow.class);
    final OpenCodeClient.OpenCodeSession session = new OpenCodeClient.OpenCodeSession("remote", Path.of("/tmp/qualification"));
    final String original = "账户启用且租户启用才允许；否则拒绝，审计幂等。";
    PackageSemanticPreparation service() { return new PackageSemanticPreparation(mapper, conversations, remote, candidates, attachments, new ObjectMapper()); }

    @BeforeEach void setup() {
        when(owner.id()).thenReturn("owner"); when(owner.designerSessionId()).thenReturn("designer");
        when(owner.packageId()).thenReturn("WP-1"); when(owner.requirementRevisionId()).thenReturn("requirement");
        when(discussion.revision()).thenReturn(1); when(conversations.packageV2("remote")).thenReturn(true);
        var requirement = mock(DesignRequirementRevisionRow.class); when(requirement.requirementText()).thenReturn(original);
        when(mapper.findDesignRequirementRevision("requirement")).thenReturn(Optional.of(requirement));
        when(mapper.findPackageSemanticPreparation("owner", 1)).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(mapper.insertPackageSemanticPreparation(any())).thenAnswer(call -> stored.compareAndSet(null, call.getArgument(0)) ? 1 : 0);
        when(mapper.transitionPackageSemanticPreparation(anyString(), anyLong(), anyString(), anyString(), nullable(String.class), nullable(String.class)))
                .thenAnswer(call -> {
                    var old = stored.get();
                    if (old.version() != (long) call.getArgument(1) || !old.state().equals(call.getArgument(2))) return 0;
                    stored.set(new PackageSemanticPreparationRow(old.id(), old.designWorkPackageId(), old.discussionRevision(), old.remoteId(),
                            old.requirementSha256(), old.promptVersion(), old.reasonsJson(), old.basePrompt(), call.getArgument(3),
                            call.getArgument(4) == null ? old.material() : call.getArgument(4), call.getArgument(5), old.createdAt(), old.version()+1));
                    return 1;
                });
        when(attachments.packagePrompt(anyString(), anyString(), anyString())).thenAnswer(call -> OpenCodeClient.PromptRequest.text(call.getArgument(2)));
        var turn = mock(DesignerConversationTurnRow.class); when(turn.phase()).thenReturn("PACKAGE_SEMANTICS"); when(turn.state()).thenReturn("SENT");
        when(mapper.designerTurnForRemote("remote")).thenReturn(Optional.of(turn));
        when(remote.sessionStatus(session)).thenReturn(new OpenCodeClient.SessionStatus("COMPLETED"));
        when(remote.sessionOutput(session)).thenReturn("{\"branches\":[\"允许\",\"拒绝\"],\"sourceRefs\":[\"REQ-L001\"],\"unresolved\":[],\"requiredEvidence\":[]}");
        when(candidates.open(eq(owner), eq(session), anyString())).thenReturn(new DesignerPackageCandidateOrchestrator.Start(session, null, "candidate prompt"));
    }
    @Test void simpleAndHistoricalOrQuestionRoundsDoNotGainExtraTurn() {
        assertThat(service().start(owner, discussion, session, "新增固定返回值的测试。", "base")).isFalse();
        assertThat(PackageSemanticPreparation.reasons("新增固定返回值的测试，无需幂等或补偿。")).isEmpty();
        when(conversations.packageV2("remote")).thenReturn(false);
        assertThat(service().start(owner, discussion, session, original, "base")).isFalse();
        when(conversations.packageV2("remote")).thenReturn(true); when(discussion.questionRequired()).thenReturn(true);
        assertThat(service().start(owner, discussion, session, original, "base")).isFalse();
        verify(conversations, never()).send(any(), any());
    }
    @Test void restartedCoordinatorResumesOnceAndPersistsOriginalAndMaterial() {
        assertThat(service().start(owner, discussion, session, original, "base")).isTrue();
        assertThat(service().start(owner, discussion, session, original, "base")).isTrue();
        verify(conversations, times(1)).begin(session, "PACKAGE_SEMANTICS");
        assertThat(service().poll(owner, discussion, session, false, () -> true)).isTrue();
        assertThat(stored.get().state()).isEqualTo("DISPATCHED");
        assertThat(stored.get().material()).contains("允许", "拒绝");
        assertThat(service().poll(owner, discussion, session, false, () -> true)).isFalse();
        verify(conversations, times(1)).begin(session, "PACKAGE_DESIGN"); verify(candidates, times(1)).open(any(), any(), any());
    }
    @Test void uncertainDispatchAndUnconfirmedStopNeverCreateOverlappingDesign() {
        service().start(owner, discussion, session, original, "base");
        var unknown = mock(DesignerConversationTurnRow.class); when(unknown.phase()).thenReturn("PACKAGE_SEMANTICS"); when(unknown.state()).thenReturn("UNKNOWN");
        when(mapper.designerTurnForRemote("remote")).thenReturn(Optional.of(unknown));
        assertThat(service().poll(owner, discussion, session, false, () -> true)).isTrue();
        doThrow(new SessionFailure("ABORT_UNCONFIRMED", "false acknowledgment")).when(remote).abortWithConfirmation(session);
        assertThatThrownBy(() -> service().poll(owner, discussion, session, true, () -> true)).isInstanceOf(SessionFailure.class);
        assertThat(stored.get().state()).isEqualTo("RUNNING"); verifyNoInteractions(candidates);
    }
    @Test void interruptionAfterDispatchIntentNeverChargesBudgetAgain() {
        service().start(owner, discussion, session, original, "base");
        var charged = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> service().poll(owner, discussion, session, false, () -> {
            charged.incrementAndGet(); throw new IllegalStateException("crash after reservation");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(stored.get().state()).isEqualTo("DISPATCHING");
        assertThatThrownBy(() -> service().poll(owner, discussion, session, false, () -> {
            charged.incrementAndGet(); return true;
        })).isInstanceOf(ConflictException.class);
        assertThat(charged.get()).isEqualTo(1); verifyNoInteractions(candidates);
    }

    @Test void utf8OverflowIsNotSilentlyTruncatedIntoAValidOutline() {
        assertThat(PackageSemanticPreparation.material("中".repeat(11000))).contains("未截断逻辑");
        assertThat(PackageSemanticPreparation.material("有效分支")).isEqualTo("有效分支");
    }
}
