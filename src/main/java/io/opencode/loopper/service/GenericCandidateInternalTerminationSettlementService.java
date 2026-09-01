package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short-transaction V57 termination settlement; role owners remain caller-owned. */
@Service
class GenericCandidateInternalTerminationSettlementService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final GenericCandidateInternalTerminationIntentStore intents;
    private final MachineCandidateSubmission submissions;

    GenericCandidateInternalTerminationSettlementService(
            LoopperMapper mapper, LifecycleTransitionService lifecycle,
            GenericCandidateInternalTerminationIntentStore intents,
            MachineCandidateSubmission submissions) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.intents = intents;
        this.submissions = submissions;
    }

    @Transactional
    GenericCandidateInternalTerminationIntentRow finishWithoutRemote(String intentId) {
        Context context = context(intentId);
        if (!GenericCandidateInternalLaunchState.PREPARED.name().equals(context.launch().state())
                || context.launch().createDispatchAttempted()) throw stale();
        terminalize(context, null);
        return intents.ready(intents.require(intentId));
    }

    @Transactional
    List<GenericCandidateInternalLaunchCleanupRemoteRow> registerCleanup(
            String intentId, List<OpenCodeClient.SessionAttestation> remotes) {
        Context context = context(intentId);
        GenericCandidateInternalLaunchRow launch = context.launch();
        List<GenericCandidateInternalLaunchCleanupRemoteRow> existing = cleanup(launch.id());
        if (!existing.isEmpty()) return existing;
        if (remotes == null || remotes.isEmpty()) throw stale();
        GenericCandidateInternalLaunchState state = GenericCandidateInternalLaunchState.valueOf(launch.state());
        if (state != GenericCandidateInternalLaunchState.SETTLED
                && state != GenericCandidateInternalLaunchState.STOPPING) {
            GenericCandidateInternalLaunchRow stopping = launch(launch,
                    GenericCandidateInternalLaunchState.STOPPING, null, null,
                    "REMOTE_STOP", null, null);
            lifecycle.transition(subject(launch), launch.state(), stopping.state(), LifecycleEvent.ABORT,
                    "GENERIC_CANDIDATE_INTERNAL_TERMINATION_CLEANUP_REGISTERED",
                    Map.of("remoteCount", remotes.size()),
                    () -> mapper.updateGenericCandidateInternalLaunchForTermination(stopping, intentId),
                    GenericCandidateInternalTerminationSettlementService::stale);
            launch = requireLaunch(launch.id());
        }
        String at = Instant.now().toString();
        for (OpenCodeClient.SessionAttestation remote : remotes) {
            GenericCandidateInternalLaunchCleanupRemoteRow row =
                    new GenericCandidateInternalLaunchCleanupRemoteRow(
                            launch.id(), remote.remoteId(), remote.runtimeGenerationId(),
                            remote.endpointFingerprint(), sha256(remote.canonicalDirectory().toString()),
                            sha256(remote.exactTitle()), "TERMINATION",
                            GenericCandidateInternalLaunchCleanupState.DISCOVERED.name(), null, null,
                            null, null, null, 0, false, null, null, null, at, at, 0);
            GenericCandidateInternalLaunchRow parent = launch;
            lifecycle.create(cleanupSubject(parent, row), row.state(), Map.of(),
                    () -> mapper.insertGenericCandidateInternalLaunchCleanupRemote(row),
                    GenericCandidateInternalTerminationSettlementService::stale);
        }
        return cleanup(launch.id());
    }

    @Transactional
    GenericCandidateInternalTerminationIntentRow finishAfterCleanup(String intentId) {
        Context context = context(intentId);
        requireStopped(context.launch().id());
        terminalize(context, proof(context.launch()));
        return intents.ready(intents.require(intentId));
    }

    /** Called inside CandidatePromptDispatchService.settleForRun. */
    @Transactional
    MachineCandidateSubmission.RunSnapshot finishSettled(String intentId, String proof) {
        Context context = context(intentId);
        requireStopped(context.launch().id());
        MachineCandidateSubmission.RunSnapshot run = submissions.find(context.intent().candidateRunId())
                .orElseThrow(GenericCandidateInternalTerminationSettlementService::stale);
        boolean normalCompletion = "RUN_COMPLETED".equals(context.intent().intentKind());
        if (normalCompletion && run.state() != MachineCandidateRunState.ACCEPTED
                && run.state() != MachineCandidateRunState.WAITING_INPUT
                && run.state() != MachineCandidateRunState.CLOSED) throw stale();
        if (run.state() == MachineCandidateRunState.OPEN) {
            MachineCandidateSubmission.CandidateCloseReason reason =
                    "PROTOCOL_FAILURE".equals(context.intent().intentKind())
                            ? MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED
                            : MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED;
            run = submissions.close(new MachineCandidateSubmission.CloseCommand(
                    run.runId(), run.version(), reason));
        }
        if (!run.state().terminal()) throw stale();
        terminalize(context, proof);
        intents.ready(intents.require(intentId));
        return run;
    }

    private void terminalize(Context context, String proof) {
        GenericCandidateInternalLaunchRow current = requireLaunch(context.launch().id());
        if (current.state().equals(context.intent().targetLaunchState())) return;
        GenericCandidateInternalLaunchState target = GenericCandidateInternalLaunchState
                .valueOf(context.intent().targetLaunchState());
        String at = Instant.now().toString();
        GenericCandidateInternalLaunchRow terminal = launch(current, target,
                current.externalSessionId() == null ? null : proof,
                current.externalSessionId() == null ? null : at,
                "REMOTE_STOP", null, null);
        lifecycle.transition(subject(current), current.state(), terminal.state(), event(target),
                "GENERIC_CANDIDATE_INTERNAL_TERMINATION_SETTLED", Map.of(),
                () -> mapper.updateGenericCandidateInternalLaunchForTermination(terminal, context.intent().id()),
                GenericCandidateInternalTerminationSettlementService::stale);
    }

    private Context context(String intentId) {
        GenericCandidateInternalTerminationIntentRow intent = intents.require(intentId);
        GenericCandidateInternalLaunchRow launch = requireLaunch(intent.launchId());
        if (!intent.candidateRunId().equals(launch.candidateRunId())) throw stale();
        return new Context(intent, launch);
    }
    private GenericCandidateInternalLaunchRow requireLaunch(String id) {
        return mapper.findGenericCandidateInternalLaunch(id)
                .orElseThrow(GenericCandidateInternalTerminationSettlementService::stale);
    }
    private List<GenericCandidateInternalLaunchCleanupRemoteRow> cleanup(String launchId) {
        return mapper.listGenericCandidateInternalLaunchCleanupRemotes(launchId);
    }
    private void requireStopped(String launchId) {
        List<GenericCandidateInternalLaunchCleanupRemoteRow> rows = cleanup(launchId);
        if (rows.isEmpty() || rows.stream().anyMatch(row ->
                !GenericCandidateInternalLaunchCleanupState.STOPPED.name().equals(row.state()))) throw stale();
    }
    private String proof(GenericCandidateInternalLaunchRow launch) {
        if (launch.externalSessionId() == null) return null;
        return cleanup(launch.id()).stream()
                .filter(row -> launch.externalSessionId().equals(row.externalSessionId()))
                .map(GenericCandidateInternalLaunchCleanupRemoteRow::terminationProof)
                .filter(CandidateSessionTerminationProof::persisted).findFirst().orElseThrow();
    }
    private static GenericCandidateInternalLaunchRow launch(
            GenericCandidateInternalLaunchRow row, GenericCandidateInternalLaunchState state,
            String proof, String proofAt, String phase, String code, String detail) {
        return new GenericCandidateInternalLaunchRow(
                row.id(), row.candidateRunId(), row.candidateKind(), row.designerSessionId(), row.taskId(),
                row.projectId(), row.ownerType(), row.ownerId(), row.analysisReportId(),
                row.projectConventionDraftId(), row.judgeRunId(), row.workflowStep(), row.sourceRevision(),
                row.contractVersion(), row.maxAttempts(), state.name(), row.preparedOwnerVersion(),
                row.settledOwnerVersion(), row.settledAt(), row.exactTitle(), row.canonicalDirectory(),
                row.runtimeGenerationId(), row.managed(), row.internalMcpServer(), row.endpointFingerprint(),
                row.modelProviderId(), row.modelId(), row.thinking(), row.profile(), row.permissionPolicyJson(),
                row.permissionPolicyDigest(), row.createRequestSha256(), row.creationCredential(),
                row.attestationType(), null, null, null, row.createFence(), row.createDispatchAttempted(),
                row.createDispatchStartedAt(), row.externalSessionId(), row.externalAttestedAt(), proof, proofAt,
                phase, code, detail, row.createdAt(), Instant.now().toString(), row.version());
    }
    private static LifecycleTransitionService.Subject subject(GenericCandidateInternalLaunchRow launch) {
        LifecycleScopeType type = launch.designerSessionId() != null ? LifecycleScopeType.DESIGNER
                : launch.taskId() != null ? LifecycleScopeType.TASK : LifecycleScopeType.PROJECT;
        String id = launch.designerSessionId() != null ? launch.designerSessionId()
                : launch.taskId() != null ? launch.taskId() : launch.projectId();
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.GENERIC_CANDIDATE_INTERNAL_LAUNCH, launch.id(), type, id);
    }
    private static LifecycleTransitionService.Subject cleanupSubject(GenericCandidateInternalLaunchRow launch,
            GenericCandidateInternalLaunchCleanupRemoteRow row) {
        LifecycleTransitionService.Subject parent = subject(launch);
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.GENERIC_CANDIDATE_INTERNAL_LAUNCH_CLEANUP,
                GenericCandidateInternalLaunchCleanupLedger.entityId(row.launchId(), row.externalSessionId()),
                parent.scopeType(), parent.scopeId());
    }
    private static LifecycleEvent event(GenericCandidateInternalLaunchState target) {
        return switch (target) {
            case COMPLETED -> LifecycleEvent.FINISH;
            case FAILED_STOPPED -> LifecycleEvent.FAIL;
            case CANCELLED -> LifecycleEvent.CANCEL;
            case STALE -> LifecycleEvent.STALE;
            default -> throw stale();
        };
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static ConflictException stale() {
        return new ConflictException("GENERIC_CANDIDATE_INTERNAL_TERMINATION_STALE",
                "通用候选 termination intent、run、prompt 或远端停止证明已变化");
    }
    private record Context(GenericCandidateInternalTerminationIntentRow intent,
                           GenericCandidateInternalLaunchRow launch) { }
}
