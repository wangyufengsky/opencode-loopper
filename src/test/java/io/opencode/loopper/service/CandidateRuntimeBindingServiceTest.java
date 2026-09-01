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
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.OpenCodeSessionRuntimeBindingRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.runtime.InternalMcpCredentialProvider;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CandidateRuntimeBindingServiceTest {
    private static final MachineCandidateSubmission.SubmissionChannel INTERNAL =
            MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP;
    private static final MachineCandidateSubmission.SubmissionChannel LEGACY =
            MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY;

    @Test
    void everyNonRunningDesignerScopeRejectsCandidateAdvanceBeforeAnyRuntimeOrOwnerWrite() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        DesignerSessionRow session = mock(DesignerSessionRow.class);
        when(mapper.findDesignerSession("designer-1")).thenReturn(Optional.of(session));
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(
                mapper, new InternalMcpRuntimeAccess());

        for (String state : java.util.List.of(
                "WAITING_INPUT", "STOPPING", "SESSION_ERROR", "COMPLETED", "CANCELLED")) {
            when(session.state()).thenReturn(state);
            assertThatThrownBy(() -> service.validate(run(
                    "remote-1", "external-" + "a".repeat(64), 4, 2, LEGACY), LEGACY))
                    .as(state)
                    .isInstanceOfSatisfying(ConflictException.class,
                            failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_SCOPE_NOT_WRITABLE"));
        }
        verify(mapper, never()).findOpenCodeSessionRuntimeBinding(any());
        verify(mapper, never()).findTaskDecomposition(any());
    }

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
    void internalAttestationMustExactlyEqualTheFrozenManagedPlanWithoutWideningLegacy() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
        InternalMcpCredentialProvider.Credentials credentials =
                new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials);
        access.connected(credentials.generation());
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);
        OpenCodeClient.SessionCreationPlan frozen = internalPlan(credentials, "A".repeat(43));
        OpenCodeClient.SessionAttestation attestation = new OpenCodeClient.SessionAttestation(
                "internal-remote", frozen.canonicalDirectory(), frozen.exactTitle(),
                frozen.runtimeGenerationId(), frozen.managed(), frozen.internalMcpServer(),
                frozen.endpointFingerprint(), frozen.model(), frozen.profile(), frozen.permissionPolicy(),
                frozen.permissionPolicyDigest(), frozen.creationCredential(), frozen.createRequestSha256(),
                OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED);
        when(mapper.findOpenCodeSessionRuntimeBinding("internal-remote")).thenReturn(Optional.empty());
        when(mapper.insertOpenCodeSessionRuntimeBinding(any())).thenReturn(1);

        CandidateRuntimeBindingService.Binding binding = service.bindInternalAttested(attestation, frozen);

        assertThat(binding.ownershipMode()).isEqualTo("MANAGED");
        assertThat(binding.runtimeGenerationId()).isEqualTo(credentials.generation());
        ArgumentCaptor<OpenCodeSessionRuntimeBindingRow> inserted =
                ArgumentCaptor.forClass(OpenCodeSessionRuntimeBindingRow.class);
        verify(mapper).insertOpenCodeSessionRuntimeBinding(inserted.capture());
        assertThat(inserted.getValue().internalMcpServer()).isEqualTo(credentials.serverName());

        OpenCodeClient.SessionCreationPlan drifted = internalPlan(credentials, "B".repeat(43));
        assertThatThrownBy(() -> service.bindInternalAttested(attestation, drifted))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CANDIDATE_INTERNAL_ATTESTATION_MISMATCH"));
        assertThatThrownBy(() -> service.bindAttested(attestation, INTERNAL))
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
    void correctionStopMarkerAndOwnerClosedProofRemainExactRuntimeCheckpointsAcrossRestart() {
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
        when(owner.lastErrorCode()).thenReturn("ACCEPTANCE_CORRECTION_WAITING_INPUT_PENDING");
        when(owner.lastErrorDetail()).thenReturn("BUDGET_EXHAUSTED");
        when(owner.version()).thenReturn(8L);
        when(owner.externalSessionState()).thenReturn("CORRECTION_STOP_REQUESTED");
        when(mapper.findLoopSpecCompilation("compilation-1")).thenReturn(Optional.of(owner));
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);

        MachineCandidateSubmission.RunSnapshot open = acceptanceRun(
                credentials.generation(), MachineCandidateRunState.OPEN);
        assertThatThrownBy(() -> service.validate(open, INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_REVISION_STALE"));
        service.validateCorrectionStopRecovery(open, INTERNAL);
        MachineCandidateSubmission.RunSnapshot closed = acceptanceRun(credentials.generation(),
                MachineCandidateRunState.CLOSED,
                MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);
        service.validateCorrectionStopRecovery(closed, INTERNAL);
        when(owner.version()).thenReturn(9L);
        when(owner.externalSessionState()).thenReturn("CORRECTION_ABORT_DISPATCHED");
        service.validateCorrectionStopRecovery(open, INTERNAL);
        service.validateCorrectionStopRecovery(closed, INTERNAL);
        when(owner.version()).thenReturn(10L);
        when(owner.externalSessionState()).thenReturn("ABORT_ACKNOWLEDGED");
        service.validateCorrectionStopRecovery(closed, INTERNAL);

        when(owner.lastErrorDetail()).thenReturn("untrusted historical detail");
        assertThatThrownBy(() -> service.validateCorrectionStopRecovery(closed, INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_REVISION_STALE"));
    }

    @Test
    void acceptedAndWaitingCorrectionRacesUseTheirDifferentExactOwnerOffsets() {
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
        when(owner.lastErrorCode()).thenReturn("ACCEPTANCE_CORRECTION_WAITING_INPUT_PENDING");
        when(owner.lastErrorDetail()).thenReturn("BUDGET_EXHAUSTED");
        when(mapper.findLoopSpecCompilation("compilation-1")).thenReturn(Optional.of(owner));
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);
        MachineCandidateSubmission.RunSnapshot accepted = acceptanceRun(
                credentials.generation(), MachineCandidateRunState.ACCEPTED);
        MachineCandidateSubmission.RunSnapshot waiting = acceptanceRun(
                credentials.generation(), MachineCandidateRunState.WAITING_INPUT);

        when(owner.version()).thenReturn(10L);
        when(owner.externalSessionState()).thenReturn("CORRECTION_ABORT_DISPATCHED");
        service.validateCorrectionStopRecovery(accepted, INTERNAL);
        assertThatThrownBy(() -> service.validateCorrectionStopRecovery(waiting, INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_REVISION_STALE"));

        when(owner.version()).thenReturn(9L);
        service.validateCorrectionStopRecovery(waiting, INTERNAL);
        assertThatThrownBy(() -> service.validateCorrectionStopRecovery(accepted, INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_REVISION_STALE"));

        when(owner.version()).thenReturn(11L);
        when(owner.externalSessionState()).thenReturn("REMOTE_COMPLETED");
        service.validate(accepted, INTERNAL);
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
        CandidateRuntimeBindingService service = new CandidateRuntimeBindingService(mapper, access);
        MachineCandidateSubmission.RunSnapshot accepted = acceptanceRun(
                credentials.generation(), MachineCandidateRunState.ACCEPTED);
        String canonicalCandidate = "{\"summary\":\"accepted choice\",\"capabilityPreferences\":[1]}";
        String compiledPlan = "{\"status\":\"READY\",\"stages\":[{\"objective\":\"compile\"}]}";

        when(mapper.findLoopSpecCompilation("compilation-1")).thenReturn(Optional.of(
                acceptedCompilationCheckpoint(10, false, canonicalCandidate, compiledPlan)));
        service.validate(accepted, INTERNAL);

        when(mapper.findLoopSpecCompilation("compilation-1")).thenReturn(Optional.of(
                acceptedCompilationCheckpoint(11, true, canonicalCandidate, compiledPlan)));
        service.validate(accepted, INTERNAL);

        when(mapper.findLoopSpecCompilation("compilation-1")).thenReturn(Optional.of(
                acceptedCompilationCheckpoint(12, true, canonicalCandidate, compiledPlan)));
        assertThatThrownBy(() -> service.validate(accepted, INTERNAL))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CANDIDATE_OWNER_REVISION_STALE"));
    }

    private LoopSpecCompilationRow acceptedCompilationCheckpoint(
            long version, boolean serverCompiled, String canonicalCandidate, String compiledPlan) {
        return new LoopSpecCompilationRow(
                "compilation-1", "designer-1", 3, "RUNNING",
                "acceptance-remote", "ABORT_ACKNOWLEDGED", 0,
                "message-1", 1, null, null, "now", "now", version,
                "WP-1", 0, null, "SERVER_COMPILING", compiledPlan, 0,
                "TEXT_MARKER", null, false, "TEXT_MARKER", null, false,
                canonicalCandidate, 0, 0, serverCompiled, "MCP_ACCEPTED", null);
    }

    private OpenCodeClient.SessionCreationPlan internalPlan(
            InternalMcpCredentialProvider.Credentials credentials, String creationCredential) {
        Path directory = Path.of("/tmp/internal-attestation");
        String title = OpenCodeClient.recoveryTitle("Acceptance binding", creationCredential);
        String tool = credentials.serverName().replaceAll("[^a-zA-Z0-9_-]", "_") + "_submit_candidate";
        List<OpenCodeClient.SessionPermissionRule> policy = List.of(
                new OpenCodeClient.SessionPermissionRule("*", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule("external_directory", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule(tool, "*", "allow"));
        String policyDigest = OpenCodeClient.permissionPolicyDigest(policy);
        String requestDigest = OpenCodeClient.sessionCreationRequestSha256(
                directory, title, credentials.generation(), true, credentials.serverName(),
                "a".repeat(64), null,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                policyDigest, creationCredential);
        return new OpenCodeClient.SessionCreationPlan(
                directory, title, credentials.generation(), true, credentials.serverName(),
                "a".repeat(64), null,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                policy, policyDigest, creationCredential, requestDigest);
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
        return acceptanceRun(generation, state, null);
    }

    private MachineCandidateSubmission.RunSnapshot acceptanceRun(
            String generation, MachineCandidateRunState state,
            MachineCandidateSubmission.CandidateCloseReason closeReason) {
        return new MachineCandidateSubmission.RunSnapshot(
                "acceptance-run", MachineCandidateSubmission.CandidateScope.designerSession("designer-1"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("compilation-1"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7, "ACCEPTANCE_CLOSED_CHOICE_V7",
                3, 7, INTERNAL, "ACCEPTANCE_CLOSED_CHOICE_V7", generation,
                "acceptance-remote", state, 2, 1, "attempt-1", 0, closeReason);
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
