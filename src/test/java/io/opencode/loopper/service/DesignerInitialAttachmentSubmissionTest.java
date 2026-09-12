package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignerInitialAttachmentSubmissionTest {
    private final DesignerSessionService sessions = mock(DesignerSessionService.class);
    private final DesignerAttachmentContext attachments = mock(DesignerAttachmentContext.class);
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final DesignerAttachmentCommandService commands = new DesignerAttachmentCommandService(sessions,
            mock(LoopDraftService.class), mock(DesignerSessionRuntimeControl.class), attachments, mapper);
    private final DesignerAttachmentContext.PreparedUpload upload = new DesignerAttachmentContext.PreparedUpload(List.of());
    private final DesignerSessionRow session = new DesignerSessionRow("s", "p", "PENDING_HANDOFF", "READ_ONLY",
            "now", "now", 0, null, "PENDING", "d", "ROUTING", 0, 0);
    private final DesignerMessageRow message = new DesignerMessageRow("m", "s", 1, "USER", "context",
            "PERSISTED", "now", "USER");

    @Test
    void retryDuringInitialCreationCannotCreateASecondDesigner() throws Exception {
        when(attachments.prepare(List.of())).thenReturn(upload);
        when(attachments.publishedMessageRetry(anyString(), any(), any(), anyString(), any())).thenReturn(Optional.empty());
        when(mapper.findLatestDesignerSessionByDraft("d")).thenReturn(Optional.empty());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(sessions.create(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return session;
        });
        when(sessions.messages("s")).thenReturn(List.of(message));
        when(sessions.get("s")).thenReturn(session);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> commands.create("p", "d", "context", "submission", List.of()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> commands.create("p", "d", "context", "submission", List.of()))
                        .isInstanceOfSatisfying(ConflictException.class,
                                failure -> assertThat(failure.code()).isEqualTo("ATTACHMENT_INITIAL_SUBMISSION_BUSY"));
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(session);
        }
        verify(sessions).create(anyString(), anyString(), anyString(), any());
    }

    @Test
    void incompletePriorCreationRequiresRecoveryInsteadOfAnotherDispatch() {
        when(attachments.prepare(List.of())).thenReturn(upload);
        when(attachments.publishedMessageRetry(anyString(), any(), any(), anyString(), any())).thenReturn(Optional.empty());
        when(mapper.findLatestDesignerSessionByDraft("d")).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> commands.create("p", "d", "context", "submission", List.of()))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ATTACHMENT_INITIAL_SUBMISSION_INCOMPLETE"));
        verify(sessions, never()).create(anyString(), anyString(), anyString(), any());
    }

    @Test
    void publishedExactRetryReturnsItsExistingDesigner() {
        when(attachments.prepare(List.of())).thenReturn(upload);
        when(attachments.publishedMessageRetry(anyString(), any(), any(), anyString(), any())).thenReturn(Optional.of(message));
        when(sessions.get("s")).thenReturn(session);
        assertThat(commands.create("p", "d", "context", "submission", List.of())).isEqualTo(session);
        verify(sessions, never()).create(anyString(), anyString(), anyString(), any());
        verify(mapper, never()).findLatestDesignerSessionByDraft(anyString());
    }
}
