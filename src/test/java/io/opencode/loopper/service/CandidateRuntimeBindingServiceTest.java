package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.OpenCodeSessionRuntimeBindingRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.runtime.InternalMcpCredentialProvider;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CandidateRuntimeBindingServiceTest {
    private static final MachineCandidateSubmission.SubmissionChannel INTERNAL =
            MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP;
    private static final MachineCandidateSubmission.SubmissionChannel LEGACY =
            MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY;

    @Test
    void bindsAndGuardsOneManagedSessionAgainstTheActivePrivateMcpGeneration() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
        InternalMcpCredentialProvider.Credentials credentials =
                new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials);
        access.connected(credentials.generation());
        OpenCodeSessionRuntimeBindingRow stored = new OpenCodeSessionRuntimeBindingRow(
                "remote-1", credentials.generation(), "MANAGED",
                "a".repeat(64), credentials.serverName(), "now");
        when(mapper.findOpenCodeSessionRuntimeBinding("remote-1"))
                .thenReturn(Optional.of(stored));
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);

        CandidateRuntimeBindingService.Binding binding = service.bind(
                new OpenCodeClient.OpenCodeSession("remote-1", Path.of("/tmp/project"),
                        credentials.generation(), credentials.serverName()), INTERNAL);

        assertThat(binding.runtimeGenerationId()).isEqualTo(credentials.generation());
        assertThat(stored.endpointFingerprint()).hasSize(64)
                .doesNotContain(credentials.bearerToken());
        when(mapper.findTaskDecomposition("dec-1")).thenReturn(Optional.of(decomposition(4)));
        when(mapper.findDesignRequirementRevision("rev-1")).thenReturn(Optional.of(requirement(2)));

        service.validate(run("remote-1", credentials.generation(), 4, 2, INTERNAL), INTERNAL);

        InternalMcpCredentialProvider.Credentials replacement =
                new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(replacement);
        access.connected(replacement.generation());
        assertThatThrownBy(() -> service.validate(
                run("remote-1", credentials.generation(), 4, 2, INTERNAL), INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_RUNTIME_GENERATION_STALE"));
    }

    @Test
    void externalSessionsAreAvailableOnlyToTheExplicitLegacyAdapter() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
        when(mapper.findOpenCodeSessionRuntimeBinding("external-1")).thenReturn(Optional.empty());
        when(mapper.insertOpenCodeSessionRuntimeBinding(any())).thenReturn(1);
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);

        CandidateRuntimeBindingService.Binding binding = service.bind(
                new OpenCodeClient.OpenCodeSession("external-1", Path.of("/tmp/project")), LEGACY);
        ArgumentCaptor<OpenCodeSessionRuntimeBindingRow> inserted =
                ArgumentCaptor.forClass(OpenCodeSessionRuntimeBindingRow.class);
        verify(mapper).insertOpenCodeSessionRuntimeBinding(inserted.capture());
        assertThat(inserted.getValue().ownershipMode()).isEqualTo("EXTERNAL");

        when(mapper.findOpenCodeSessionRuntimeBinding("external-1"))
                .thenReturn(Optional.of(inserted.getValue()));
        when(mapper.findTaskDecomposition("dec-1")).thenReturn(Optional.of(decomposition(4)));
        when(mapper.findDesignRequirementRevision("rev-1")).thenReturn(Optional.of(requirement(2)));
        service.validate(run("external-1", binding.runtimeGenerationId(), 4, 2, LEGACY), LEGACY);

        assertThatThrownBy(() -> service.bind(
                new OpenCodeClient.OpenCodeSession("external-2", Path.of("/tmp/project")), INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_MANAGED_RUNTIME_REQUIRED"));
    }

    @Test
    void legacyAdapterReusesTheExternalEndpointBindingPersistedWhenHttpCreatedTheSession() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
        OpenCodeSessionRuntimeBindingRow endpointBinding = new OpenCodeSessionRuntimeBindingRow(
                "external-http", "external-" + "b".repeat(64), "EXTERNAL",
                "b".repeat(64), null, "now");
        when(mapper.findOpenCodeSessionRuntimeBinding("external-http"))
                .thenReturn(Optional.of(endpointBinding));
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);

        CandidateRuntimeBindingService.Binding binding = service.bind(
                new OpenCodeClient.OpenCodeSession("external-http", Path.of("/tmp/project")), LEGACY);

        assertThat(binding.runtimeGenerationId()).isEqualTo(endpointBinding.runtimeGenerationId());
        assertThat(binding.ownershipMode()).isEqualTo("EXTERNAL");
        verify(mapper, never()).insertOpenCodeSessionRuntimeBinding(any());
    }

    @Test
    void packageDesignGuardFreezesOwnerDesignRevisionAndRemoteSession() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
        OpenCodeSessionRuntimeBindingRow binding = new OpenCodeSessionRuntimeBindingRow(
                "package-remote", "external-" + "c".repeat(64), "EXTERNAL",
                "c".repeat(64), null, "now");
        when(mapper.findOpenCodeSessionRuntimeBinding("package-remote")).thenReturn(Optional.of(binding));
        DesignWorkPackageRow owner = mock(DesignWorkPackageRow.class);
        when(owner.designerSessionId()).thenReturn("designer-1");
        when(owner.version()).thenReturn(4L);
        when(owner.designRevision()).thenReturn(2);
        when(owner.designerExternalSessionId()).thenReturn("package-remote");
        when(owner.state()).thenReturn("DESIGNING");
        when(mapper.findDesignWorkPackage("wp-1")).thenReturn(Optional.of(owner));
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);
        MachineCandidateSubmission.RunSnapshot run = new MachineCandidateSubmission.RunSnapshot(
                "package-run", MachineCandidateSubmission.CandidateScope.designerSession("designer-1"),
                MachineCandidateSubmission.CandidateOwnerRef.designWorkPackage("wp-1"),
                MachineCandidateKind.PACKAGE_DESIGN_V1, "PACKAGE_DESIGN", 3, 4,
                LEGACY, "PACKAGE_DESIGN_V1", binding.runtimeGenerationId(), "package-remote",
                MachineCandidateRunState.OPEN, 3, 0, null, 0);

        service.validate(run, LEGACY);

        when(owner.state()).thenReturn("REVIEWING");
        assertThatThrownBy(() -> service.validate(run, LEGACY))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_STATE_INVALID"));
        when(owner.state()).thenReturn("QUESTIONING");
        when(owner.designerExternalSessionId()).thenReturn("replacement-remote");
        assertThatThrownBy(() -> service.validate(run, LEGACY))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_SESSION_STALE"));
        when(owner.designerExternalSessionId()).thenReturn("package-remote");
        when(owner.designRevision()).thenReturn(3);
        assertThatThrownBy(() -> service.validate(run, LEGACY))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_SOURCE_REVISION_STALE"));
    }

    @Test
    void acceptanceTerminalGuardAllowsDirectAndDisconnectedStopProofStepsButRejectsExtraDrift() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
        InternalMcpCredentialProvider.Credentials credentials =
                new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials);
        access.connected(credentials.generation());
        OpenCodeSessionRuntimeBindingRow binding = new OpenCodeSessionRuntimeBindingRow(
                "acceptance-remote", credentials.generation(), "MANAGED",
                "d".repeat(64), credentials.serverName(), "now");
        when(mapper.findOpenCodeSessionRuntimeBinding("acceptance-remote"))
                .thenReturn(Optional.of(binding));
        LoopSpecCompilationRow owner = mock(LoopSpecCompilationRow.class);
        when(owner.designerSessionId()).thenReturn("designer-1");
        when(owner.designRevision()).thenReturn(3);
        when(owner.state()).thenReturn("RUNNING");
        when(owner.externalSessionId()).thenReturn("acceptance-remote");
        when(mapper.findLoopSpecCompilation("compilation-1")).thenReturn(Optional.of(owner));
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);
        MachineCandidateSubmission.RunSnapshot accepted = acceptanceRun(
                credentials.generation(), MachineCandidateRunState.ACCEPTED);

        when(owner.version()).thenReturn(8L);
        when(owner.externalSessionState()).thenReturn("RUNNING");
        service.validate(accepted, INTERNAL);

        InternalMcpCredentialProvider.Credentials replacement =
                new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(replacement);
        access.connected(replacement.generation());
        assertThatThrownBy(() -> service.validate(accepted, INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_RUNTIME_GENERATION_STALE"));

        when(owner.version()).thenReturn(9L);
        when(owner.externalSessionState()).thenReturn("ABORT_ACKNOWLEDGED");
        service.validate(accepted, INTERNAL);

        when(owner.version()).thenReturn(10L);
        assertThatThrownBy(() -> service.validate(accepted, INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_REVISION_STALE"));

        when(owner.lastErrorCode()).thenReturn("ACCEPTANCE_CANDIDATE_STOP_UNCONFIRMED");
        service.validate(accepted, INTERNAL);

        when(owner.version()).thenReturn(11L);
        assertThatThrownBy(() -> service.validate(accepted, INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_REVISION_STALE"));
    }

    @Test
    void waitingInputGuardDoesNotBorrowTheAcceptedWriterVersionStep() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
        InternalMcpCredentialProvider.Credentials credentials =
                new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials);
        access.connected(credentials.generation());
        OpenCodeSessionRuntimeBindingRow binding = new OpenCodeSessionRuntimeBindingRow(
                "acceptance-remote", credentials.generation(), "MANAGED",
                "e".repeat(64), credentials.serverName(), "now");
        when(mapper.findOpenCodeSessionRuntimeBinding("acceptance-remote"))
                .thenReturn(Optional.of(binding));
        LoopSpecCompilationRow owner = mock(LoopSpecCompilationRow.class);
        when(owner.designerSessionId()).thenReturn("designer-1");
        when(owner.designRevision()).thenReturn(3);
        when(owner.state()).thenReturn("RUNNING");
        when(owner.externalSessionId()).thenReturn("acceptance-remote");
        when(mapper.findLoopSpecCompilation("compilation-1")).thenReturn(Optional.of(owner));
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);
        MachineCandidateSubmission.RunSnapshot waiting = acceptanceRun(
                credentials.generation(), MachineCandidateRunState.WAITING_INPUT);

        when(owner.version()).thenReturn(7L);
        when(owner.externalSessionState()).thenReturn("RUNNING");
        service.validate(waiting, INTERNAL);

        when(owner.version()).thenReturn(8L);
        when(owner.externalSessionState()).thenReturn("REMOTE_COMPLETED");
        service.validate(waiting, INTERNAL);

        when(owner.version()).thenReturn(9L);
        assertThatThrownBy(() -> service.validate(waiting, INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_REVISION_STALE"));
    }

    @Test
    void openAcceptanceRunAllowsOnlyItsExactDisconnectedCheckpoint() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
        InternalMcpCredentialProvider.Credentials credentials =
                new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials);
        access.connected(credentials.generation());
        when(mapper.findOpenCodeSessionRuntimeBinding("acceptance-remote")).thenReturn(Optional.of(
                new OpenCodeSessionRuntimeBindingRow("acceptance-remote", credentials.generation(), "MANAGED",
                        "d".repeat(64), credentials.serverName(), "now")));
        LoopSpecCompilationRow owner = mock(LoopSpecCompilationRow.class);
        when(owner.designerSessionId()).thenReturn("designer-1");
        when(owner.designRevision()).thenReturn(3);
        when(owner.state()).thenReturn("RUNNING");
        when(owner.externalSessionId()).thenReturn("acceptance-remote");
        when(owner.externalSessionState()).thenReturn("DISCONNECTED");
        when(owner.lastErrorCode()).thenReturn("OPENCODE_ACCEPTANCE_CANDIDATE_STATUS_UNCONFIRMED");
        when(mapper.findLoopSpecCompilation("compilation-1")).thenReturn(Optional.of(owner));
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);
        MachineCandidateSubmission.RunSnapshot open = acceptanceRun(
                credentials.generation(), MachineCandidateRunState.OPEN);

        when(owner.version()).thenReturn(8L);
        service.validate(open, INTERNAL);

        when(owner.version()).thenReturn(9L);
        assertThatThrownBy(() -> service.validate(open, INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_REVISION_STALE"));
    }

    @Test
    void acceptedProofAllowsOnlyTheTwoExactServerCompilationCheckpoints() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
        InternalMcpCredentialProvider.Credentials credentials =
                new InternalMcpCredentialProvider(() -> 18083).issue();
        OpenCodeSessionRuntimeBindingRow binding = new OpenCodeSessionRuntimeBindingRow(
                "acceptance-remote", credentials.generation(), "MANAGED",
                "f".repeat(64), credentials.serverName(), "now");
        when(mapper.findOpenCodeSessionRuntimeBinding("acceptance-remote"))
                .thenReturn(Optional.of(binding));
        LoopSpecCompilationRow owner = mock(LoopSpecCompilationRow.class);
        when(owner.designerSessionId()).thenReturn("designer-1");
        when(owner.designRevision()).thenReturn(3);
        when(owner.externalSessionId()).thenReturn("acceptance-remote");
        when(owner.externalSessionState()).thenReturn("ABORT_ACKNOWLEDGED");
        when(owner.state()).thenReturn("RUNNING");
        when(owner.workflowStep()).thenReturn("SERVER_COMPILING");
        when(owner.planningJson()).thenReturn("{\"status\":\"READY\"}");
        when(mapper.findLoopSpecCompilation("compilation-1")).thenReturn(Optional.of(owner));
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);
        MachineCandidateSubmission.RunSnapshot accepted = acceptanceRun(
                credentials.generation(), MachineCandidateRunState.ACCEPTED);

        when(owner.version()).thenReturn(10L);
        when(owner.serverCompiled()).thenReturn(false);
        service.validate(accepted, INTERNAL);

        when(owner.version()).thenReturn(11L);
        when(owner.serverCompiled()).thenReturn(true);
        when(owner.semanticPlanJson()).thenReturn("{\"status\":\"READY\"}");
        service.validate(accepted, INTERNAL);

        when(owner.version()).thenReturn(12L);
        assertThatThrownBy(() -> service.validate(accepted, INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_REVISION_STALE"));
    }

    private MachineCandidateSubmission.RunSnapshot run(
            String externalSessionId, String generation, long ownerVersion, long sourceRevision,
            MachineCandidateSubmission.SubmissionChannel channel) {
        return new MachineCandidateSubmission.RunSnapshot("run-1",
                MachineCandidateSubmission.CandidateScope.designerSession("designer-1"),
                MachineCandidateSubmission.CandidateOwnerRef.taskDecomposition("dec-1"),
                MachineCandidateKind.DECOMPOSITION_PLAN_V2, "PLANNING", sourceRevision, ownerVersion,
                channel, "DECOMPOSITION_PLAN_V2", generation, externalSessionId,
                MachineCandidateRunState.OPEN, 5, 0, null, 0);
    }

    private MachineCandidateSubmission.RunSnapshot acceptanceRun(
            String generation, MachineCandidateRunState state) {
        return new MachineCandidateSubmission.RunSnapshot(
                "acceptance-run", MachineCandidateSubmission.CandidateScope.designerSession("designer-1"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("compilation-1"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7, "ACCEPTANCE_CLOSED_CHOICE_V7",
                3, 7, INTERNAL, "ACCEPTANCE_CLOSED_CHOICE_V7", generation,
                "acceptance-remote", state, 2, 1, "attempt-1", 0);
    }

    private TaskDecompositionRow decomposition(long version) {
        return new TaskDecompositionRow("dec-1", "designer-1", "rev-1", "RUNNING",
                null, null, "[]", "{}", "remote-1", "RUNNING", 0, 0, 0,
                null, null, "now", "now", version, "PLANNING", null, 0);
    }

    private DesignRequirementRevisionRow requirement(int revision) {
        return new DesignRequirementRevisionRow("rev-1", "designer-1", revision, "message-1",
                "requirement", "[]", 0, "ACTIVE", 0, 96, "now", "now", 0);
    }
}
