package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.domain.SessionFailure;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PackageBehaviorPreparationTest {
    final ObjectMapper json = new ObjectMapper();
    final LoopperMapper mapper = mock(LoopperMapper.class);
    final DesignerConversationCoordinator conversations = mock(DesignerConversationCoordinator.class);
    final OpenCodeClient client = mock(OpenCodeClient.class);
    final DesignerPackageCandidateOrchestrator candidates = mock(DesignerPackageCandidateOrchestrator.class);
    final DesignerAttachmentContext attachments = mock(DesignerAttachmentContext.class);
    final DesignWorkPackageRow owner = mock(DesignWorkPackageRow.class);
    final DesignDiscussionRevisionRow discussion = mock(DesignDiscussionRevisionRow.class);
    final AtomicReference<PackageBehaviorPreparationRow> stored = new AtomicReference<>();
    final OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession("designer-remote", Path.of("/tmp/behavior"));
    final OpenCodeClient.OpenCodeSession reviewRemote = new OpenCodeClient.OpenCodeSession("independent-review", remote.worktree());
    final String original = PackageBehaviorSourceReviewTest.ORIGINAL;
    final Map<String, String> phases = new HashMap<>();
    PackageBehaviorPreparation service() { return new PackageBehaviorPreparation(mapper, conversations, client, candidates, attachments, json); }
    @BeforeEach void setup() {
        when(owner.id()).thenReturn("owner"); when(owner.designerSessionId()).thenReturn("designer"); when(owner.packageId()).thenReturn("WP-1");
        when(owner.requirementRevisionId()).thenReturn("requirement"); when(owner.state()).thenReturn("DESIGNING"); when(discussion.revision()).thenReturn(1);
        when(conversations.behaviorV1(remote.id())).thenReturn(true);
        when(mapper.findDesignWorkPackage("owner")).thenReturn(Optional.of(owner));
        var designer = mock(DesignerSessionRow.class); when(designer.state()).thenReturn("RUNNING"); when(mapper.findDesignerSession("designer")).thenReturn(Optional.of(designer));
        var requirement = mock(DesignRequirementRevisionRow.class); when(requirement.requirementText()).thenReturn(original); when(requirement.revision()).thenReturn(1);
        when(mapper.findDesignRequirementRevision("requirement")).thenReturn(Optional.of(requirement));
        when(mapper.findBehaviorPreparation("owner", 1)).thenAnswer(c -> Optional.ofNullable(stored.get()));
        when(mapper.insertBehaviorPreparation(any())).thenAnswer(c -> stored.compareAndSet(null, c.getArgument(0)) ? 1 : 0);
        when(mapper.transitionBehavior(any(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class)))
            .thenAnswer(c -> {
                var old = stored.get(); PackageBehaviorPreparationRow expected = c.getArgument(0);
                if (old.version() != expected.version() || !old.state().equals(expected.state())) return 0;
                stored.set(new PackageBehaviorPreparationRow(old.id(), old.designWorkPackageId(), old.discussionRevision(), old.remoteId(), old.requirementSha256(),
                        old.basePrompt(), old.contextJson(), old.promptVersion(), c.getArgument(1), coalesce(c.getArgument(2), old.extraction()), coalesce(c.getArgument(3), old.reviewRemoteId()),
                        coalesce(c.getArgument(4), old.reviewJson()), coalesce(c.getArgument(5), old.bookJson()), coalesce(c.getArgument(6), old.bookSha256()), c.getArgument(7), old.createdAt(), old.version() + 1)); return 1;
            });
        doAnswer(c -> { OpenCodeClient.OpenCodeSession s = c.getArgument(0); phases.put(s.id(), c.getArgument(1)); return null; }).when(conversations).begin(any(), anyString());
        when(mapper.designerTurnForRemote(anyString())).thenAnswer(c -> { var turn = mock(DesignerConversationTurnRow.class); when(turn.phase()).thenReturn(phases.get(c.getArgument(0))); when(turn.state()).thenReturn("SENT"); return Optional.of(turn); });
        var parent = mock(DesignerConversationRow.class); when(parent.rootPath()).thenReturn(remote.worktree().toString());
        when(parent.modelJson()).thenReturn(json.writeValueAsString(new OpenCodeClient.OpenCodeModel("test", "model", null)));
        when(mapper.designerConversationForRemote(remote.id())).thenReturn(Optional.of(parent));
        when(conversations.acquire(eq("designer"), startsWith("BEHAVIOR_REVIEW:"), any(), any(), eq(false), eq(false), eq(false), eq(false))).thenReturn(reviewRemote);
        when(conversations.remote(eq(reviewRemote.id()), any())).thenReturn(reviewRemote);
        when(attachments.packagePrompt(anyString(), anyString(), anyString())).thenAnswer(c -> OpenCodeClient.PromptRequest.text(c.getArgument(2)));
        when(client.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("COMPLETED"));
        when(client.sessionOutput(remote)).thenReturn(json.writeValueAsString(PackageBehaviorPrompts.example()));
        when(client.sessionOutput(reviewRemote)).thenAnswer(c -> json.writeValueAsString(PackageBehaviorSourceReviewTest.review(original, stored.get().extraction(), stored.get().contextJson())));
        when(candidates.open(eq(owner), eq(remote), anyString())).thenReturn(new DesignerPackageCandidateOrchestrator.Start(remote, null, "submit using frozen book"));
    }
    @Test void exactlyOneExtractionAndIndependentReviewSurviveCoordinatorRestart() {
        assertThat(service().start(owner, discussion, remote, original, "base")).isTrue();
        assertThat(service().start(owner, discussion, remote, original, "base")).isTrue();
        var budget = new AtomicInteger();
        assertThat(service().poll(owner, discussion, remote, false, () -> { budget.incrementAndGet(); return true; })).isTrue();
        assertThat(stored.get().state()).isEqualTo("REVIEWING"); verifyNoInteractions(candidates);
        service().poll(owner, discussion, remote, false, () -> { budget.incrementAndGet(); return true; });
        assertThat(stored.get().state()).isEqualTo("DISPATCHED"); assertThat(stored.get().bookSha256()).hasSize(64);
        assertThat(service().poll(owner, discussion, remote, false, () -> { throw new AssertionError("duplicate budget"); })).isFalse();
        assertThat(budget.get()).isEqualTo(2); // review and design; initial extraction was charged by the host.
        verify(conversations, times(1)).send(eq(reviewRemote), any()); verify(conversations, times(2)).send(eq(remote), any());
        verify(candidates, times(1)).open(eq(owner), eq(remote), anyString());
    }
    @Test void unknownReviewDispatchNeverReservesOrSendsAgain() {
        service().start(owner, discussion, remote, original, "base"); var budget = new AtomicInteger();
        assertThatThrownBy(() -> service().poll(owner, discussion, remote, false, () -> { budget.incrementAndGet(); throw new IllegalStateException("crash"); })).isInstanceOf(IllegalStateException.class);
        assertThat(stored.get().state()).isEqualTo("REVIEW_DISPATCHING");
        assertThatThrownBy(() -> service().poll(owner, discussion, remote, false, () -> { budget.incrementAndGet(); return true; })).isInstanceOf(ConflictException.class);
        assertThat(budget.get()).isEqualTo(1); verifyNoInteractions(candidates);
    }
    @Test void unconfirmedRemoteStopKeepsReviewActiveAndPreventsDesign() {
        service().start(owner, discussion, remote, original, "base"); service().poll(owner, discussion, remote, false, () -> true);
        doThrow(new SessionFailure("ABORT_UNCONFIRMED", "false")).when(client).abortWithConfirmation(reviewRemote);
        assertThatThrownBy(() -> service().poll(owner, discussion, remote, true, () -> true)).isInstanceOf(SessionFailure.class);
        assertThat(stored.get().state()).isEqualTo("REVIEWING"); verifyNoInteractions(candidates);
    }
    @Test void invalidReviewPreservesMaterialAndStopsWithoutSecondReview() {
        service().start(owner, discussion, remote, original, "base"); service().poll(owner, discussion, remote, false, () -> true);
        when(client.sessionOutput(reviewRemote)).thenReturn("cannot confirm source");
        assertThatThrownBy(() -> service().poll(owner, discussion, remote, false, () -> true)).isInstanceOf(SessionFailure.class).hasMessageContaining("尚未确认");
        assertThat(stored.get().state()).isEqualTo("UNCONFIRMED"); assertThat(stored.get().extraction()).contains("CANCEL");
        assertThatThrownBy(() -> service().start(owner, discussion, remote, original, "base")).isInstanceOf(SessionFailure.class);
        verifyNoInteractions(candidates);
    }
    @Test void stoppedOwnerAndExhaustedBudgetDoNotDispatchReview() {
        service().start(owner, discussion, remote, original, "base");
        service().poll(owner, discussion, remote, false, () -> false);
        assertThat(stored.get().state()).isEqualTo("FAILED"); verifyNoInteractions(candidates);
        verify(conversations, never()).acquire(anyString(), anyString(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
    }
    @Test void simpleHistoricalAndInteractiveQuestionRevisionsKeepExistingCallBudget() {
        assertThat(service().start(owner, discussion, remote, "新增固定值测试", "base")).isFalse();
        when(conversations.behaviorV1(remote.id())).thenReturn(false); assertThat(service().start(owner, discussion, remote, original, "base")).isFalse();
        when(conversations.behaviorV1(remote.id())).thenReturn(true); when(discussion.questionRequired()).thenReturn(true);
        assertThat(service().start(owner, discussion, remote, original, "base")).isFalse(); verifyNoInteractions(candidates);
    }
    @Test void explicitUnresolvedBusinessChoiceStopsBeforeAnyPreparationPromptAndRetainsReason() {
        String unresolved = "并发失败时采用重试还是直接失败尚未决定。";
        assertThatThrownBy(() -> service().start(owner, discussion, remote, unresolved, "base"))
                .isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code()).isEqualTo("PACKAGE_GAP_BUSINESS_DECISION"));
        assertThat(stored.get().state()).isEqualTo("UNCONFIRMED");
        assertThatThrownBy(() -> service().start(owner, discussion, remote, unresolved, "base"))
                .isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code()).isEqualTo("PACKAGE_GAP_BUSINESS_DECISION"));
        verify(conversations, never()).send(any(), any()); verifyNoInteractions(candidates);
    }
    @Test void sourceChangeBeforeResumeCannotDispatchReview() {
        service().start(owner, discussion, remote, original, "base");
        var changed = mock(DesignRequirementRevisionRow.class); when(changed.requirementText()).thenReturn("changed source");
        when(mapper.findDesignRequirementRevision("requirement")).thenReturn(Optional.of(changed));
        assertThatThrownBy(() -> service().poll(owner, discussion, remote, false, () -> { throw new AssertionError("budget must not be charged"); }))
                .isInstanceOf(ConflictException.class).hasMessageContaining("哈希");
        verify(conversations, never()).send(eq(reviewRemote), any()); verifyNoInteractions(candidates);
    }
    private static String coalesce(String next, String old) { return next == null ? old : next; }
}
