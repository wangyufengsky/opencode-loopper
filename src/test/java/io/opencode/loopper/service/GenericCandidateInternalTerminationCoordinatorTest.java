package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenericCandidateInternalTerminationCoordinatorTest {

    @Test
    void creatingWithoutAttestedRemoteStaysDisconnectedAndNeverInventsTerminalProof() {
        GenericCandidateInternalTerminationIntentStore intents =
                mock(GenericCandidateInternalTerminationIntentStore.class);
        GenericCandidateInternalLaunchService launches = mock(GenericCandidateInternalLaunchService.class);
        GenericCandidateInternalLaunchCleanupLedger cleanup =
                mock(GenericCandidateInternalLaunchCleanupLedger.class);
        GenericCandidateInternalTerminationSettlementService settlement =
                mock(GenericCandidateInternalTerminationSettlementService.class);
        CandidatePromptDispatchService prompts = mock(CandidatePromptDispatchService.class);
        OpenCodeClient openCode = mock(OpenCodeClient.class);
        LoopperProperties properties = new LoopperProperties();
        GenericCandidateInternalTerminationCoordinator coordinator =
                new GenericCandidateInternalTerminationCoordinator(
                        intents, launches, cleanup, settlement, prompts, openCode, properties);
        GenericCandidateInternalTerminationIntentRow requested = intent("REQUESTED", null, null);
        GenericCandidateInternalTerminationIntentRow disconnected = intent(
                "DISCONNECTED", "OPENCODE_CREATE_RESULT_UNKNOWN", null);
        when(intents.require("termination")).thenReturn(requested);
        when(launches.require("launch")).thenReturn(launch("CREATING"));
        when(cleanup.list("launch")).thenReturn(List.of());
        when(intents.disconnected(requested, "OPENCODE_CREATE_RESULT_UNKNOWN",
                "No attested remote is available for termination"))
                .thenReturn(disconnected);

        GenericCandidateInternalTerminationCoordinator.Result result = coordinator.advance("termination");

        assertThat(result.status()).isEqualTo(
                GenericCandidateInternalTerminationCoordinator.Status.DISCONNECTED);
        assertThat(result.intent().state()).isEqualTo("DISCONNECTED");
        assertThat(result.intent().lastErrorCode()).isEqualTo("OPENCODE_CREATE_RESULT_UNKNOWN");
        assertThat(launch("CREATING").terminationProof()).isNull();
        verify(settlement, never()).finishAfterCleanup(anyString());
        verify(settlement, never()).finishSettled(anyString(), anyString());
        verify(openCode, never()).abortWithConfirmation(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void normalCompletionIntentTargetsDedicatedCompletedTerminal() {
        GenericCandidateInternalLaunchService launches = mock(GenericCandidateInternalLaunchService.class);
        GenericCandidateInternalTerminationIntentStore intents =
                mock(GenericCandidateInternalTerminationIntentStore.class);
        GenericCandidateInternalTerminationPreparer preparer =
                new GenericCandidateInternalTerminationPreparer(launches, intents);
        when(launches.require("launch")).thenReturn(launch("SETTLED"));
        when(intents.createIdempotent(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GenericCandidateInternalTerminationIntentRow prepared = preparer.prepare(
                new GenericCandidateInternalTerminationPreparer.PrepareCommand(
                        "launch", GenericCandidateInternalTerminationPreparer.IntentKind.RUN_COMPLETED,
                        "RUN_ALREADY_TERMINAL"));

        assertThat(prepared.intentKind()).isEqualTo("RUN_COMPLETED");
        assertThat(prepared.targetLaunchState()).isEqualTo("COMPLETED");
        assertThat(prepared.anchorOwnerVersion()).isEqualTo(1);
    }

    private GenericCandidateInternalTerminationIntentRow intent(
            String state, String errorCode, String readyAt) {
        return new GenericCandidateInternalTerminationIntentRow(
                "termination", "launch", "run", "PROTOCOL_FAILURE", "FAILED_STOPPED", state,
                "reason", false, false, 1, readyAt, null, errorCode, null, "created", "updated", 0);
    }

    private GenericCandidateInternalLaunchRow launch(String state) {
        return new GenericCandidateInternalLaunchRow(
                "launch", "run", "REVIEWER_REPORT_V1", "designer", null, null,
                "ANALYSIS_REPORT", "report", "report", null, null, "REVIEWER_REPORT_V1", 7,
                "REVIEWER_REPORT_V1", 3, state, 0,
                "SETTLED".equals(state) ? 1L : null,
                "SETTLED".equals(state) ? "settled-at" : null,
                "candidate_launch_id:launch", "/tmp/project", "generation", true,
                "loopper_internal_generic", "a".repeat(64), null, null, null,
                "REVIEWER_CANDIDATE_READ_ONLY", "[]", "b".repeat(64), "c".repeat(64),
                "R".repeat(43), "LOCAL_REQUEST_ATTESTED", "worker", "claim", "expires", 1,
                true, "dispatch-at", "SETTLED".equals(state) ? "remote" : null,
                "SETTLED".equals(state) ? "attested-at" : null,
                null, null, null, null, null, "created", "updated", 0);
    }
}
