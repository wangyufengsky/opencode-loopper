package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class DesignerDecompositionCandidateCoordinatorTest {
    private final MachineCandidateSubmission submissions = mock(MachineCandidateSubmission.class);
    private final CandidateRuntimeBindingService bindings = mock(CandidateRuntimeBindingService.class);
    private final DesignerDecompositionCandidateCoordinator coordinator =
            new DesignerDecompositionCandidateCoordinator(submissions, Optional.of(bindings),
                    new DesignerDecompositionLegacyCandidateAdapter(
                            new AiOutputExtractor(new ObjectMapper()), new ObjectMapper()));

    @Test
    void opensManagedMcpRunWithStableOwnerDerivedContract() {
        TaskDecompositionRow owner = owner(4, "managed-session");
        DesignRequirementRevisionRow revision = revision(3);
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "managed-session", Path.of("/tmp/project"), "generation-7", "loopper_internal");
        when(bindings.bind(remote, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP))
                .thenReturn(new CandidateRuntimeBindingService.Binding(
                        remote.id(), "generation-7", "MANAGED"));
        when(submissions.open(any())).thenAnswer(invocation -> snapshot(
                invocation.getArgument(0, MachineCandidateSubmission.OpenCommand.class),
                MachineCandidateRunState.OPEN, 0, 0));

        MachineCandidateSubmission.RunSnapshot opened = coordinator.open(
                owner, revision, remote, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);

        ArgumentCaptor<MachineCandidateSubmission.OpenCommand> command =
                ArgumentCaptor.forClass(MachineCandidateSubmission.OpenCommand.class);
        verify(submissions).open(command.capture());
        assertThat(command.getValue()).satisfies(value -> {
            assertThat(value.runId()).isEqualTo(
                    coordinator.runId(owner.id(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP));
            assertThat(value.designerSessionId()).isEqualTo(owner.designerSessionId());
            assertThat(value.owner()).isEqualTo(
                    MachineCandidateSubmission.CandidateOwner.taskDecomposition(owner.id()));
            assertThat(value.candidateKind()).isEqualTo(MachineCandidateKind.DECOMPOSITION_PLAN_V2);
            assertThat(value.workflowStep()).isEqualTo("PLANNING");
            assertThat(value.sourceRevision()).isEqualTo(revision.revision());
            assertThat(value.ownerVersion()).isEqualTo(owner.version());
            assertThat(value.submissionChannel())
                    .isEqualTo(MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
            assertThat(value.contractVersion()).isEqualTo("DECOMPOSITION_PLAN_V2");
            assertThat(value.runtimeGenerationId()).isEqualTo("generation-7");
            assertThat(value.externalSessionId()).isEqualTo(remote.id());
            assertThat(value.maxAttempts()).isEqualTo(5);
        });
        assertThat(opened.runId()).isEqualTo(command.getValue().runId());
    }

    @Test
    void recoveryPrefersFreshLegacyGenerationAndReturnsItsSafeTerminalResponse() {
        TaskDecompositionRow owner = owner(8, "legacy-session");
        String internalId = coordinator.runId(
                owner.id(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        String legacyId = coordinator.runId(
                owner.id(), MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY);
        MachineCandidateSubmission.RunSnapshot closedInternal = snapshot(internalId,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                MachineCandidateRunState.CLOSED, "managed-session", 0, 1);
        MachineCandidateSubmission.RunSnapshot acceptedLegacy = snapshot(legacyId,
                MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY,
                MachineCandidateRunState.ACCEPTED, "legacy-session", 1, 1);
        MachineCandidateSubmission.SubmissionResult terminal = new MachineCandidateSubmission.SubmissionResult(
                legacyId, MachineCandidateOutcome.ACCEPTED, MachineCandidateRunState.ACCEPTED,
                1, 4, false, List.of(), "a".repeat(64), 1, "{\"outcome\":\"ACCEPTED\"}");
        when(submissions.find(legacyId)).thenReturn(Optional.of(acceptedLegacy));
        when(submissions.find(internalId)).thenReturn(Optional.of(closedInternal));
        when(submissions.terminal(legacyId)).thenReturn(Optional.of(terminal));

        assertThat(coordinator.find(owner.id())).contains(acceptedLegacy);
        assertThat(coordinator.terminal(owner.id())).contains(terminal);
        verify(submissions, never()).terminal(internalId);
    }

    @Test
    void legacyTextIsExtractedThenSubmittedThroughTheSameMachinePort() {
        TaskDecompositionRow owner = owner(2, "legacy-session");
        String runId = coordinator.runId(
                owner.id(), MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY);
        MachineCandidateSubmission.RunSnapshot open = snapshot(runId,
                MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY,
                MachineCandidateRunState.OPEN, "legacy-session", 1, 1);
        when(submissions.find(runId)).thenReturn(Optional.of(open));
        MachineCandidateSubmission.SubmissionResult rejected = new MachineCandidateSubmission.SubmissionResult(
                runId, MachineCandidateOutcome.REJECTED, MachineCandidateRunState.OPEN,
                2, 3, true, List.of(new MachineCandidateSubmission.Problem(
                        "WORK_PACKAGE_FIELD_REQUIRED", "/workPackages/0/title", "title is required")),
                null, 2, "{\"outcome\":\"REJECTED\"}");
        when(submissions.submit(any())).thenReturn(rejected);

        MachineCandidateSubmission.SubmissionResult result = coordinator.submitLegacy(owner.id(), """
                explanation
                <!-- TASK_DECOMPOSITION_PLAN_JSON_START -->
                {"outcome":"READY","normalizedGoal":"goal","globalConstraints":[],
                 "workPackages":[],"coverage":[],"designGaps":[],"reason":null}
                <!-- TASK_DECOMPOSITION_PLAN_JSON_END -->
                """);

        ArgumentCaptor<MachineCandidateSubmission.SubmitCommand> command =
                ArgumentCaptor.forClass(MachineCandidateSubmission.SubmitCommand.class);
        verify(submissions).submit(command.capture());
        assertThat(command.getValue()).satisfies(value -> {
            assertThat(value.runId()).isEqualTo(runId);
            assertThat(value.idempotencyKey()).isEqualTo("legacy-session:2");
            assertThat(value.candidateJson()).isEqualTo("{\"outcome\":\"READY\",\"normalizedGoal\":\"goal\",\"globalConstraints\":[],\"workPackages\":[],\"coverage\":[],\"designGaps\":[],\"reason\":null}");
            assertThat(value.expectedSubmissionRevision()).isEqualTo(1);
            assertThat(value.submissionChannel())
                    .isEqualTo(MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY);
        });
        assertThat(result).isEqualTo(rejected);
    }

    @Test
    void legacyFinalEnvelopeIsMechanicallyAdaptedBeforeGenericPolicySubmission() {
        TaskDecompositionRow owner = owner(2, "legacy-session");
        String runId = coordinator.runId(
                owner.id(), MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY);
        MachineCandidateSubmission.RunSnapshot open = snapshot(runId,
                MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY,
                MachineCandidateRunState.OPEN, "legacy-session", 0, 1);
        when(submissions.find(runId)).thenReturn(Optional.of(open));
        when(submissions.submit(any())).thenReturn(new MachineCandidateSubmission.SubmissionResult(
                runId, MachineCandidateOutcome.ACCEPTED, MachineCandidateRunState.ACCEPTED,
                1, 0, false, List.of(), "a".repeat(64), 1, "{\"outcome\":\"ACCEPTED\"}"));

        coordinator.submitLegacy(owner.id(), """
                <!-- TASK_DECOMPOSITION_JSON_START -->
                {"status":"DECOMPOSED","normalizedGoal":"goal","globalConstraints":[],
                 "workPackages":[
                  {"id":"WP-1","title":"first","objective":"first objective","scopeIn":[],"scopeOut":[],
                   "dependencies":[],"deliverables":["first output"],"acceptanceIntent":["first accepted"],
                   "requirementRefs":["RQ-1"]},
                  {"id":"WP-2","title":"second","objective":"second objective","scopeIn":[],"scopeOut":[],
                   "dependencies":["WP-1"],"deliverables":["second output"],
                   "acceptanceIntent":["second accepted"],"requirementRefs":["RQ-2"]}],
                 "designGaps":[],"reason":null}
                <!-- TASK_DECOMPOSITION_JSON_END -->
                """);

        ArgumentCaptor<MachineCandidateSubmission.SubmitCommand> command =
                ArgumentCaptor.forClass(MachineCandidateSubmission.SubmitCommand.class);
        verify(submissions).submit(command.capture());
        assertThat(command.getValue().candidateJson()).isEqualTo("""
                {"outcome":"READY","normalizedGoal":"goal","globalConstraints":[],"workPackages":[{"title":"first","objective":"first objective","scopeIn":[],"scopeOut":[],"deliverables":["first output"],"acceptanceIntent":["first accepted"],"dependsOn":[]},{"title":"second","objective":"second objective","scopeIn":[],"scopeOut":[],"deliverables":["second output"],"acceptanceIntent":["second accepted"],"dependsOn":["WP-1"]}],"coverage":[{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE","targetIndex":0},{"requirementRef":"RQ-2","targetType":"WORK_PACKAGE","targetIndex":1}],"designGaps":[],"reason":null}""");
    }

    private MachineCandidateSubmission.RunSnapshot snapshot(
            MachineCandidateSubmission.OpenCommand command, MachineCandidateRunState state,
            int attempts, long version) {
        return new MachineCandidateSubmission.RunSnapshot(command.runId(), command.designerSessionId(),
                command.owner(), command.candidateKind(), command.workflowStep(), command.sourceRevision(),
                command.ownerVersion(), command.submissionChannel(), command.contractVersion(),
                command.runtimeGenerationId(), command.externalSessionId(), state, command.maxAttempts(),
                attempts, null, version);
    }

    private MachineCandidateSubmission.RunSnapshot snapshot(
            String runId, MachineCandidateSubmission.SubmissionChannel channel,
            MachineCandidateRunState state, String externalSessionId, int attempts, long version) {
        return new MachineCandidateSubmission.RunSnapshot(runId, "designer",
                MachineCandidateSubmission.CandidateOwner.taskDecomposition("decomposition"),
                MachineCandidateKind.DECOMPOSITION_PLAN_V2, "PLANNING", 3, 2, channel,
                "DECOMPOSITION_PLAN_V2", "generation", externalSessionId, state, 5, attempts, null, version);
    }

    private TaskDecompositionRow owner(long version, String externalSessionId) {
        return new TaskDecompositionRow("decomposition", "designer", "revision", "RUNNING",
                null, null, "[]", "{}", externalSessionId, "RUNNING", 0, 0, 7,
                null, null, "now", "now", version, "PLANNING", null, 0,
                "TEXT_MARKER", null, false, "TEXT_MARKER", null, false,
                null, 0, 0, false);
    }

    private DesignRequirementRevisionRow revision(int revision) {
        return new DesignRequirementRevisionRow("revision", "designer", revision, "message",
                "requirement", "[{\"id\":\"RQ-1\",\"text\":\"requirement\"}]", 7,
                "ACTIVE", 1, 96, "now", "now", 0);
    }
}
