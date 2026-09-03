package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class LegacyJudgeTransportTest {
    @TempDir Path worktree;

    @ParameterizedTest
    @CsvSource({"REQUIREMENT,JSON_SCHEMA", "RISK,JSON_SCHEMA", "REQUIREMENT,TEXT_MARKER", "RISK,TEXT_MARKER"})
    void finalizerPreservesJudgeStepExemptionAndFrozenReviewEvidence(String role, String mode) {
        var mapper = mock(LoopperMapper.class);
        var openCode = mock(OpenCodeClient.class);
        var audit = mock(AiOutputAuditService.class);
        var evidence = mock(TaskEvidenceService.class);
        var attachments = mock(DesignerAttachmentContext.class);
        var transport = new LegacyJudgeTransport(mapper, openCode, audit, evidence, attachments,
                mock(TaskEventService.class), mock(AiOutputExtractor.class),
                mock(LifecycleTransitionService.class), new tools.jackson.databind.ObjectMapper());
        var task = mock(TaskRow.class);
        when(task.id()).thenReturn("task");
        when(task.worktreePath()).thenReturn(worktree.toString());
        var judge = mock(JudgeRunRow.class);
        when(judge.id()).thenReturn("judge");
        when(judge.role()).thenReturn(role);
        when(judge.responseMode()).thenReturn(mode);
        when(audit.claimToolLoopRecovery(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        var source = mock(TaskEvidenceService.JudgeCandidateSource.class);
        when(source.prompt()).thenReturn("frozen review evidence");
        when(evidence.frozenLegacyJudgeSource(task, judge))
                .thenReturn(new TaskEvidenceService.FrozenJudgeSource(source, "a".repeat(64)));
        var finalizer = new OpenCodeClient.OpenCodeSession("finalizer", worktree);
        when(openCode.createSession(any(), anyString(), any(), any())).thenReturn(finalizer);
        when(attachments.withContext(any(), any())).thenAnswer(call -> call.getArgument(1));

        assertThat(transport.recoverToolLoop(task, judge, new OpenCodeClient.OpenCodeSession("old", worktree),
                new SessionFailure("OPENCODE_MACHINE_TOOL_LOOP", "repeated tool call"), null)).isTrue();

        verify(openCode).createSession(eq(worktree), anyString(), any(), eq(OpenCodeClient.SessionProfile.JUDGE_FINALIZER_NO_TOOLS));
        var request = ArgumentCaptor.forClass(OpenCodeClient.PromptRequest.class);
        verify(openCode).promptAsync(eq(finalizer), request.capture());
        assertThat(request.getValue().text()).contains("frozen review evidence", "Do not call built-in tools");
        assertThat(request.getValue().agent()).isNull();
    }
}
