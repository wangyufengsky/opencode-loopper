package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.AcceptanceBindingSource;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AcceptanceClosedChoiceCandidateCoordinatorTest {
    @Test
    void featureFlagDefaultsOffUntilPackagedRealModelToolUseIsProven() {
        assertThat(new LoopperProperties().getInternalCandidate().isAcceptanceClosedChoiceV7Enabled()).isFalse();
    }

    @Test
    void uniqueOptimumRemainsServerDirectWithZeroCandidateRun() {
        CapturingSubmissions submissions = new CapturingSubmissions();
        var coordinator = coordinator(false, submissions);
        var routing = routing(resolved(), false);

        var decision = coordinator.decide(serverPlanning(), routing);

        assertThat(decision.action())
                .isEqualTo(AcceptanceClosedChoiceCandidateCoordinator.Action.SERVER_DIRECT);
        assertThat(decision.reasonCode()).isEqualTo("ACCEPTANCE_UNIQUE_OPTIMUM_SERVER_DIRECT");
        assertThat(submissions.opened).isNull();
    }

    @Test
    void exactTrueTieUsesLegacyJsonWhenTheFeatureFlagIsDisabled() {
        var coordinator = coordinator(false, new CapturingSubmissions());

        var decision = coordinator.decide(planning(), routing(trueTie(), true));

        assertThat(decision.action())
                .isEqualTo(AcceptanceClosedChoiceCandidateCoordinator.Action.LEGACY_JSON);
        assertThat(decision.reasonCode()).isEqualTo("ACCEPTANCE_CANDIDATE_FEATURE_DISABLED");
    }

    @Test
    void disabledFeatureLeavesNonEnumerableV7PlanningOnThePreviousJsonCompilerPath() {
        var coordinator = coordinator(false, new CapturingSubmissions());
        var unresolved = new DesignerAcceptanceFastPathResolver.Resolution(
                DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER,
                trueTie().groupHints(), List.of(1), List.of(), Map.of(), List.of(), 0,
                List.of("UNRESOLVED_FACTS:[1]"), List.of());

        var decision = coordinator.decide(planning(), routing(unresolved, true));

        assertThat(decision.action())
                .isEqualTo(AcceptanceClosedChoiceCandidateCoordinator.Action.LEGACY_JSON);
        assertThat(decision.reasonCode()).isEqualTo("ACCEPTANCE_CANDIDATE_FEATURE_DISABLED");
    }

    @Test
    void aTrueTieWithoutPersistedCompilerRoutingCannotOpenTheInternalLoop() {
        DesignAcceptancePlanningRow unrouted = new DesignAcceptancePlanningRow(
                "cmp", "session", "WP-1", 3, DesignerAcceptancePlanning.CONTRACT_VERSION_V7,
                "a".repeat(64), "EXTRACTED", AcceptanceBindingSource.UNDECIDED.name(), "{}", "{}",
                null, "{}", null, null, "created", "updated", 2);

        var decision = coordinator(true, new CapturingSubmissions()).decide(unrouted, routing(trueTie(), true));

        assertThat(decision.action()).isEqualTo(AcceptanceClosedChoiceCandidateCoordinator.Action.WAITING_INPUT);
        assertThat(decision.reasonCode()).isEqualTo("ACCEPTANCE_CANDIDATE_ROUTE_NOT_PERSISTED");
    }

    @Test
    void exactTrueTieOpensOneInternalMcpRunWithTwoAttemptsWhenEnabled() {
        CapturingSubmissions submissions = new CapturingSubmissions();
        var coordinator = coordinator(true, submissions);
        LoopSpecCompilationRow compilation = compilation();
        var routing = routing(trueTie(), true);

        var opened = coordinator.open(new AcceptanceClosedChoiceCandidateCoordinator.OpenRequest(
                "candidate-run", compilation, planning(), routing,
                "generation-7", "remote-7"));

        assertThat(opened.runId()).isEqualTo("candidate-run");
        assertThat(AcceptanceClosedChoiceCandidateCoordinator.SESSION_PROFILE)
                .isEqualTo(OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS);
        assertThat(submissions.opened).satisfies(command -> {
            assertThat(command.candidateKind()).isEqualTo(MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7);
            assertThat(command.scope()).isEqualTo(
                    MachineCandidateSubmission.CandidateScope.designerSession("session"));
            assertThat(command.owner()).isEqualTo(
                    MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("cmp"));
            assertThat(command.workflowStep()).isEqualTo("ACCEPTANCE_CLOSED_CHOICE_V7");
            assertThat(command.contractVersion()).isEqualTo("ACCEPTANCE_CLOSED_CHOICE_V7");
            assertThat(command.sourceRevision()).isEqualTo(3);
            assertThat(command.ownerVersion()).isEqualTo(4);
            assertThat(command.submissionChannel())
                    .isEqualTo(MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
            assertThat(command.maxAttempts()).isEqualTo(2);
        });
    }

    @Test
    void unresolvedFactsAndNonExhaustiveOrSafetyGapsWaitForHumanInput() {
        var coordinator = coordinator(true, new CapturingSubmissions());
        var unresolved = new DesignerAcceptanceFastPathResolver.Resolution(
                DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER,
                trueTie().groupHints(), List.of(1), List.of(0), Map.of(0, List.of(0, 1)),
                List.of(List.of(0), List.of(1)), 2,
                List.of("UNRESOLVED_FACTS:[1]", "AMBIGUOUS_CAPABILITY:0"), List.of());
        var incomplete = new DesignerAcceptanceFastPathResolver.Resolution(
                DesignerAcceptanceFastPathResolver.Outcome.DESIGN_INCOMPLETE,
                List.of(), List.of(), List.of(), Map.of(), List.of(), 0,
                List.of("CAPABILITY_SOLVER_NON_EXHAUSTIVE"),
                List.of(new DesignerSemanticContracts.DesignGap(
                        DesignerSemanticContracts.DesignGapCode.AMBIGUOUS_ACCEPTANCE_INTENT,
                        "未完成权威穷举")));

        assertThat(coordinator.decide(planning(), routing(unresolved, true)).action())
                .isEqualTo(AcceptanceClosedChoiceCandidateCoordinator.Action.WAITING_INPUT);
        assertThat(coordinator.decide(planning(), routing(incomplete, false)).action())
                .isEqualTo(AcceptanceClosedChoiceCandidateCoordinator.Action.WAITING_INPUT);
        assertThatThrownBy(() -> coordinator.open(new AcceptanceClosedChoiceCandidateCoordinator.OpenRequest(
                "bad-run", compilation(), planning(), routing(unresolved, true),
                "generation-7", "remote-7")))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("ACCEPTANCE_CANDIDATE_NOT_ELIGIBLE"));
    }

    @Test
    void persistedTieCanOpenFreshLegacyFallbackAfterTheRoutingFlagTurnsOff() {
        CapturingSubmissions submissions = new CapturingSubmissions();
        CandidateRuntimeBindingService bindings = mock(CandidateRuntimeBindingService.class);
        when(bindings.bind(any(), any())).thenReturn(
                new CandidateRuntimeBindingService.Binding("remote-7", "external-generation", "EXTERNAL"));
        LoopperProperties properties = new LoopperProperties();
        properties.getInternalCandidate().setAcceptanceClosedChoiceV7Enabled(false);
        var coordinator = new AcceptanceClosedChoiceCandidateCoordinator(
                submissions, properties, Optional.of(bindings), new tools.jackson.databind.ObjectMapper());

        coordinator.openLegacy(compilation(), planning(), routing(trueTie(), true),
                new OpenCodeClient.OpenCodeSession("remote-7", java.nio.file.Path.of(".")));

        assertThat(submissions.opened.submissionChannel())
                .isEqualTo(MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY);
    }

    @Test
    void findsTheExactFrozenInternalRunInsteadOfReDerivingItsIdentity() {
        MachineCandidateSubmission submissions = mock(MachineCandidateSubmission.class);
        AcceptanceCandidateInternalLaunchStore launches = mock(AcceptanceCandidateInternalLaunchStore.class);
        AcceptanceCandidateInternalLaunchRow launch = mock(AcceptanceCandidateInternalLaunchRow.class);
        MachineCandidateSubmission.RunSnapshot exact = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(launch.candidateRunId()).thenReturn("frozen-run");
        when(launches.findForCompilation("cmp")).thenReturn(Optional.of(launch));
        when(submissions.find("frozen-run")).thenReturn(Optional.of(exact));
        LoopperProperties properties = new LoopperProperties();
        var coordinator = new AcceptanceClosedChoiceCandidateCoordinator(
                submissions, properties, Optional.empty(), Optional.of(launches),
                new tools.jackson.databind.ObjectMapper());

        assertThat(coordinator.find("cmp")).containsSame(exact);
    }

    private AcceptanceClosedChoiceCandidateCoordinator coordinator(
            boolean enabled, MachineCandidateSubmission submissions) {
        LoopperProperties properties = new LoopperProperties();
        properties.getInternalCandidate().setAcceptanceClosedChoiceV7Enabled(enabled);
        return new AcceptanceClosedChoiceCandidateCoordinator(submissions, properties);
    }

    private DesignerAcceptanceWorkflow.RoutingResult routing(
            DesignerAcceptanceFastPathResolver.Resolution resolution, boolean compilerRequired) {
        return new DesignerAcceptanceWorkflow.RoutingResult(
                resolution, resolution.outcome() == DesignerAcceptanceFastPathResolver.Outcome.RESOLVED,
                compilerRequired);
    }

    private DesignerAcceptanceFastPathResolver.Resolution resolved() {
        return new DesignerAcceptanceFastPathResolver.Resolution(
                DesignerAcceptanceFastPathResolver.Outcome.RESOLVED,
                List.of(new DesignerSemanticContracts.AcceptanceGroupHint(
                        "实现", "实现行为", List.of(0), List.of())),
                List.of(), List.of(), Map.of(), List.of(), 0,
                List.of("COMPILER_AVOIDED_UNIQUE_OPTIMUM"), List.of());
    }

    private DesignerAcceptanceFastPathResolver.Resolution trueTie() {
        return new DesignerAcceptanceFastPathResolver.Resolution(
                DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER,
                List.of(new DesignerSemanticContracts.AcceptanceGroupHint(
                        "实现", "实现行为", List.of(0), List.of())),
                List.of(), List.of(0), Map.of(0, List.of(0, 1)),
                List.of(List.of(0), List.of(1)), 2,
                List.of("AMBIGUOUS_CAPABILITY:0"), List.of());
    }

    private DesignAcceptancePlanningRow planning() {
        return new DesignAcceptancePlanningRow("cmp", "session", "WP-1", 3,
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7, "a".repeat(64),
                "EXTRACTED", AcceptanceBindingSource.AI_DISAMBIGUATION_V6.name(), "{}", "{}", "{}", "{}",
                null, null, "created", "updated", 2);
    }

    private DesignAcceptancePlanningRow serverPlanning() {
        DesignAcceptancePlanningRow row = planning();
        return new DesignAcceptancePlanningRow(row.compilationId(), row.designerSessionId(), row.workPackageId(),
                row.designRevision(), row.contractVersion(), row.designSha256(), row.state(),
                AcceptanceBindingSource.SERVER_STAGE_HINTS.name(), row.factsJson(), row.capabilitiesJson(),
                row.bindingJson(), row.diagnosticsJson(), row.errorCode(), row.errorDetail(), row.createdAt(),
                row.updatedAt(), row.version());
    }

    private LoopSpecCompilationRow compilation() {
        return new LoopSpecCompilationRow("cmp", "session", 3, "RUNNING", "remote-7", "RUNNING",
                0, "message", 1, null, null, "created", "updated", 4,
                "WP-1", 0, null, "PLANNING", null, 0,
                "TEXT_MARKER", null, false, "TEXT_MARKER", null, false,
                null, 0, 0, false);
    }

    private static final class CapturingSubmissions implements MachineCandidateSubmission {
        private OpenCommand opened;

        @Override
        public RunSnapshot open(OpenCommand command) {
            opened = command;
            return new RunSnapshot(command.runId(), command.scope(), command.owner(),
                    command.candidateKind(), command.workflowStep(), command.sourceRevision(),
                    command.ownerVersion(), command.submissionChannel(), command.contractVersion(),
                    command.runtimeGenerationId(), command.externalSessionId(),
                    io.opencode.loopper.domain.MachineCandidateRunState.OPEN,
                    command.maxAttempts(), 0, null, 0);
        }

        @Override public SubmissionResult submit(SubmitCommand command) { throw new UnsupportedOperationException(); }
        @Override public RunSnapshot close(CloseCommand command) { throw new UnsupportedOperationException(); }
        @Override public Optional<RunSnapshot> find(String runId) { return Optional.empty(); }
        @Override public Optional<SubmissionResult> terminal(String runId) { return Optional.empty(); }
    }
}
