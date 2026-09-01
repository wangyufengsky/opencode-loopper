package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.LoopSpecCompilationState;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short-transaction half of durable internal-launch termination. */
@Service
class AcceptanceCandidateInternalTerminationSettlementService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final AcceptanceCandidateInternalTerminationIntentStore intents;
    private final MachineCandidateSubmission submissions;

    AcceptanceCandidateInternalTerminationSettlementService(
            LoopperMapper mapper, LifecycleTransitionService lifecycle,
            AcceptanceCandidateInternalTerminationIntentStore intents,
            MachineCandidateSubmission submissions) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.intents = intents;
        this.submissions = submissions;
    }

    @Transactional
    AcceptanceCandidateInternalTerminationIntentRow finishWithoutRemote(String intentId) {
        Context context = context(intentId);
        if (!AcceptanceCandidateInternalLaunchState.PREPARED.name().equals(context.launch().state())
                || context.launch().createDispatchAttempted()
                || context.launch().externalSessionId() != null) throw stale();
        terminalizeLaunch(context, null);
        terminalizeOwner(context);
        return intents.ready(intents.requireIntent(intentId));
    }

    @Transactional
    AcceptanceCandidateInternalTerminationIntentRow disconnectCreateUnknown(
            String intentId, String code, String detail) {
        Context context = context(intentId);
        AcceptanceCandidateInternalLaunchRow launch = context.launch();
        AcceptanceCandidateInternalLaunchState state = AcceptanceCandidateInternalLaunchState.valueOf(launch.state());
        if (state != AcceptanceCandidateInternalLaunchState.CREATING
                && state != AcceptanceCandidateInternalLaunchState.DISCONNECTED) throw stale();
        AcceptanceCandidateInternalLaunchRow disconnected = launch(launch,
                AcceptanceCandidateInternalLaunchState.DISCONNECTED, launch.settledOwnerVersion(), launch.settledAt(),
                launch.terminationProof(), launch.proofAt(), "CREATE_LOOKUP", code, detail);
        if (state == AcceptanceCandidateInternalLaunchState.CREATING) {
            lifecycle.transition(launchSubject(launch), launch.state(), disconnected.state(),
                    LifecycleEvent.DISCONNECT, "ACCEPTANCE_INTERNAL_TERMINATION_CREATE_UNKNOWN", Map.of(),
                    () -> mapper.updateAcceptanceCandidateInternalLaunchForTermination(disconnected, intentId),
                    AcceptanceCandidateInternalTerminationSettlementService::stale);
        } else {
            lifecycle.mutateWithoutTransition(
                    () -> mapper.updateAcceptanceCandidateInternalLaunchForTermination(disconnected, intentId),
                    AcceptanceCandidateInternalTerminationSettlementService::stale);
        }
        return intents.disconnected(intents.requireIntent(intentId), code, detail);
    }

    @Transactional
    List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> registerCleanup(
            String intentId, List<OpenCodeClient.SessionAttestation> remotes) {
        Context context = context(intentId);
        AcceptanceCandidateInternalLaunchRow launch = context.launch();
        List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> existing = cleanup(launch.id());
        if (!existing.isEmpty()) return existing;
        if (remotes == null || remotes.isEmpty()) throw stale();
        AcceptanceCandidateInternalLaunchState state = AcceptanceCandidateInternalLaunchState.valueOf(launch.state());
        if (state != AcceptanceCandidateInternalLaunchState.SETTLED
                && state != AcceptanceCandidateInternalLaunchState.STOPPING) {
            if (state != AcceptanceCandidateInternalLaunchState.CREATING
                    && state != AcceptanceCandidateInternalLaunchState.CREATED
                    && state != AcceptanceCandidateInternalLaunchState.DISCONNECTED) throw stale();
            AcceptanceCandidateInternalLaunchRow stopping = launch(launch,
                    AcceptanceCandidateInternalLaunchState.STOPPING, launch.settledOwnerVersion(), launch.settledAt(),
                    launch.terminationProof(), launch.proofAt(), "REMOTE_STOP", null, null);
            lifecycle.transition(launchSubject(launch), launch.state(), stopping.state(), LifecycleEvent.ABORT,
                    "ACCEPTANCE_INTERNAL_TERMINATION_CLEANUP_REGISTERED", Map.of("remoteCount", remotes.size()),
                    () -> mapper.updateAcceptanceCandidateInternalLaunchForTermination(stopping, intentId),
                    AcceptanceCandidateInternalTerminationSettlementService::stale);
            launch = requireLaunch(launch.id());
        }
        String at = Instant.now().toString();
        for (OpenCodeClient.SessionAttestation remote : remotes) {
            AcceptanceCandidateInternalLaunchCleanupRemoteRow row =
                    new AcceptanceCandidateInternalLaunchCleanupRemoteRow(
                            launch.id(), remote.remoteId(), remote.runtimeGenerationId(), remote.endpointFingerprint(),
                            sha256(remote.canonicalDirectory().toString()), sha256(remote.exactTitle()),
                            "TERMINATION_INTENT", intentId,
                            AcceptanceCandidateInternalLaunchCleanupState.DISCOVERED.name(), null, null,
                            null, null, null, 0, false, null, null, null, at, at, 0);
            AcceptanceCandidateInternalLaunchRow parent = launch;
            lifecycle.create(cleanupSubject(parent, row), row.state(), Map.of(),
                    () -> mapper.insertAcceptanceCandidateInternalLaunchCleanupRemote(row),
                    AcceptanceCandidateInternalTerminationSettlementService::stale);
        }
        return cleanup(launch.id());
    }

    @Transactional
    AcceptanceCandidateInternalTerminationIntentRow finishAfterCleanup(String intentId) {
        Context context = context(intentId);
        if (AcceptanceCandidateInternalLaunchState.SETTLED.name().equals(context.launch().state())) throw stale();
        requireCleanupStopped(context.launch().id());
        terminalizeLaunch(context, launchProof(context.launch()));
        terminalizeOwner(context);
        return intents.ready(intents.requireIntent(intentId));
    }

    /** Called inside CandidatePromptDispatchService.settleForRun's transaction. */
    @Transactional
    SettledRun finishSettled(String intentId, String proof) {
        if (!CandidateSessionTerminationProof.persisted(proof)) throw stale();
        Context context = context(intentId);
        AcceptanceCandidateInternalLaunchRow launch = context.launch();
        if (!AcceptanceCandidateInternalLaunchState.SETTLED.name().equals(launch.state())) {
            if (AcceptanceCandidateInternalLaunchState.valueOf(launch.state()).terminal()) {
                return requireSettledRun(intentId);
            }
            throw stale();
        }
        requireCleanupStopped(launch.id());
        MachineCandidateSubmission.RunSnapshot run = requireRun(launch.candidateRunId());
        if (run.state() == MachineCandidateRunState.OPEN) {
            run = closeOpen(run);
        }
        SettledRun settledRun = settledRun(run);
        terminalizeLaunch(context, proof);
        terminalizeOwner(context);
        intents.ready(intents.requireIntent(intentId));
        return settledRun;
    }

    SettledRun requireSettledRun(String intentId) {
        return findSettledRun(intentId).orElseThrow(
                AcceptanceCandidateInternalTerminationSettlementService::stale);
    }

    Optional<SettledRun> findSettledRun(String intentId) {
        Context context = context(intentId);
        return submissions.find(context.intent().candidateRunId()).map(this::settledRun);
    }

    private MachineCandidateSubmission.RunSnapshot closeOpen(
            MachineCandidateSubmission.RunSnapshot expected) {
        try {
            return submissions.close(new MachineCandidateSubmission.CloseCommand(
                    expected.runId(), expected.version(),
                    MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED));
        } catch (RuntimeException raced) {
            MachineCandidateSubmission.RunSnapshot latest = requireRun(expected.runId());
            if (latest.state() == MachineCandidateRunState.OPEN) throw raced;
            return latest;
        }
    }

    private MachineCandidateSubmission.RunSnapshot requireRun(String runId) {
        return submissions.find(runId).orElseThrow(
                AcceptanceCandidateInternalTerminationSettlementService::stale);
    }

    private SettledRun settledRun(MachineCandidateSubmission.RunSnapshot run) {
        if (!run.state().terminal()) throw stale();
        RunOutcome outcome = run.state() == MachineCandidateRunState.CLOSED
                && run.closeReason() == MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED
                ? RunOutcome.OWNER_STOPPED : RunOutcome.TERMINAL_RACE;
        return new SettledRun(outcome, run);
    }

    private void terminalizeLaunch(Context context, String proof) {
        AcceptanceCandidateInternalLaunchRow launch = requireLaunch(context.launch().id());
        AcceptanceCandidateInternalLaunchState current = AcceptanceCandidateInternalLaunchState.valueOf(launch.state());
        AcceptanceCandidateInternalLaunchState target = AcceptanceCandidateInternalLaunchState.valueOf(
                context.intent().targetState());
        if (current == target) return;
        String at = Instant.now().toString();
        AcceptanceCandidateInternalLaunchRow terminal = launch(launch, target, null, null,
                launch.externalSessionId() == null ? null : proof,
                launch.externalSessionId() == null ? null : at,
                "REMOTE_STOP", null, null);
        lifecycle.transition(launchSubject(launch), launch.state(), terminal.state(), event(target),
                "ACCEPTANCE_INTERNAL_TERMINATION_SETTLED", Map.of("kind", context.intent().kind()),
                () -> mapper.updateAcceptanceCandidateInternalLaunchForTermination(terminal, context.intent().id()),
                AcceptanceCandidateInternalTerminationSettlementService::stale);
    }

    private void terminalizeOwner(Context context) {
        if ("INITIAL_PROMPT_FAILURE".equals(context.intent().kind())) return;
        LoopSpecCompilationRow owner = mapper.findLoopSpecCompilation(context.intent().compilationId())
                .orElseThrow(AcceptanceCandidateInternalTerminationSettlementService::stale);
        if (LoopSpecCompilationState.SESSION_ERROR.name().equals(owner.state())) return;
        if (LoopSpecCompilationState.COMPLETED.name().equals(owner.state())
                || LoopSpecCompilationState.DESIGN_INCOMPLETE.name().equals(owner.state())) throw stale();
        String at = Instant.now().toString();
        String code = "DESIGNER_CANCEL".equals(context.intent().kind())
                ? "DESIGNER_CANCELLED" : "OWNER_REPLACED";
        LoopSpecCompilationRow terminal = new LoopSpecCompilationRow(
                owner.id(), owner.designerSessionId(), owner.designRevision(),
                LoopSpecCompilationState.SESSION_ERROR.name(), owner.externalSessionId(), "ABORTED",
                owner.repairCount(), owner.sourceDesignMessageId(), owner.sourceDraftVersion(), code,
                "Acceptance internal candidate owner was terminated", owner.createdAt(), at, owner.version(),
                owner.workPackageId(), owner.transportRetryCount(), owner.compiledPackageJson(), owner.workflowStep(),
                owner.planningJson(), owner.planningRepairCount(), owner.planningResponseMode(),
                owner.planningResponseSchemaId(), owner.planningFormatFallbackUsed(), owner.finalResponseMode(),
                owner.finalResponseSchemaId(), owner.finalFormatFallbackUsed(), owner.semanticPlanJson(),
                owner.formatRepairCount(), owner.semanticRepairCount(), owner.serverCompiled(),
                owner.compilationSource(), owner.fallbackReason());
        lifecycle.transition(ownerSubject(owner), owner.state(), terminal.state(), LifecycleEvent.SESSION_FAIL,
                code, Map.of(), () -> mapper.updateLoopSpecCompilation(terminal),
                AcceptanceCandidateInternalTerminationSettlementService::stale);
    }

    private Context context(String intentId) {
        AcceptanceCandidateInternalTerminationIntentRow intent = intents.requireIntent(intentId);
        AcceptanceCandidateInternalLaunchRow launch = requireLaunch(intent.launchId());
        if (!intent.designerSessionId().equals(launch.designerSessionId())
                || !intent.compilationId().equals(launch.compilationId())
                || !intent.candidateRunId().equals(launch.candidateRunId())) throw stale();
        return new Context(intent, launch);
    }

    private AcceptanceCandidateInternalLaunchRow requireLaunch(String id) {
        return mapper.findAcceptanceCandidateInternalLaunch(id).orElseThrow(
                AcceptanceCandidateInternalTerminationSettlementService::stale);
    }

    private List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> cleanup(String launchId) {
        return mapper.listAcceptanceCandidateInternalLaunchCleanupRemotes(launchId);
    }

    private void requireCleanupStopped(String launchId) {
        List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> rows = cleanup(launchId);
        if (rows.isEmpty() || rows.stream().anyMatch(row ->
                !AcceptanceCandidateInternalLaunchCleanupState.STOPPED.name().equals(row.state()))) throw stale();
    }

    private String launchProof(AcceptanceCandidateInternalLaunchRow launch) {
        if (launch.externalSessionId() == null) return null;
        return cleanup(launch.id()).stream()
                .filter(row -> launch.externalSessionId().equals(row.externalSessionId()))
                .map(AcceptanceCandidateInternalLaunchCleanupRemoteRow::terminationProof)
                .filter(CandidateSessionTerminationProof::persisted).findFirst().orElseThrow(
                        AcceptanceCandidateInternalTerminationSettlementService::stale);
    }

    private AcceptanceCandidateInternalLaunchRow launch(
            AcceptanceCandidateInternalLaunchRow row, AcceptanceCandidateInternalLaunchState state,
            Long settledVersion, String settledAt, String proof, String proofAt,
            String phase, String code, String detail) {
        return new AcceptanceCandidateInternalLaunchRow(
                row.id(), row.compilationId(), row.designerSessionId(), row.workPackageId(),
                row.sourceDesignRevision(), row.sourceDesignMessageId(), row.sourceDraftVersion(),
                row.sourceDesignSha256(), row.planningVersion(), row.planningBindingSource(),
                row.planningBindingJson(), row.planningBindingSha256(), row.routePlanJson(), row.routePlanSha256(),
                row.candidateRunId(), row.contractVersion(), row.workflowStep(), state.name(),
                row.preparedOwnerVersion(), settledVersion, settledAt, row.exactTitle(), row.canonicalDirectory(),
                row.runtimeGenerationId(), row.managed(), row.internalMcpServer(), row.endpointFingerprint(),
                row.modelProviderId(), row.modelId(), row.thinking(), row.profile(), row.permissionPolicyJson(),
                row.permissionPolicyDigest(), row.createRequestSha256(), row.creationCredential(),
                row.attestationType(), null, null, null, row.createFence(), row.createDispatchAttempted(),
                row.createDispatchStartedAt(), row.externalSessionId(), row.externalAttestedAt(), proof, proofAt,
                phase, code, detail, row.createdAt(), Instant.now().toString(), row.version());
    }

    private LifecycleTransitionService.Subject launchSubject(AcceptanceCandidateInternalLaunchRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.ACCEPTANCE_CANDIDATE_INTERNAL_LAUNCH,
                row.id(), LifecycleScopeType.DESIGNER, row.designerSessionId());
    }

    private LifecycleTransitionService.Subject cleanupSubject(AcceptanceCandidateInternalLaunchRow launch,
            AcceptanceCandidateInternalLaunchCleanupRemoteRow row) {
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.ACCEPTANCE_CANDIDATE_INTERNAL_LAUNCH_CLEANUP,
                AcceptanceCandidateInternalLaunchCleanupLedger.entityId(row.launchId(), row.externalSessionId()),
                LifecycleScopeType.DESIGNER, launch.designerSessionId());
    }

    private LifecycleTransitionService.Subject ownerSubject(LoopSpecCompilationRow owner) {
        String projectId = mapper.findDesignerSession(owner.designerSessionId()).orElseThrow(
                AcceptanceCandidateInternalTerminationSettlementService::stale).projectId();
        return new LifecycleTransitionService.Subject(LifecycleMachineType.LOOPSPEC_COMPILATION,
                owner.id(), LifecycleScopeType.PROJECT, projectId);
    }

    private static LifecycleEvent event(AcceptanceCandidateInternalLaunchState target) {
        return switch (target) {
            case FAILED_STOPPED -> LifecycleEvent.FAIL;
            case CANCELLED -> LifecycleEvent.CANCEL;
            case STALE -> LifecycleEvent.STALE;
            default -> throw stale();
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static ConflictException stale() {
        return new ConflictException("ACCEPTANCE_INTERNAL_TERMINATION_STALE",
                "验收候选内部终止意图、owner 或 writer 已变化");
    }

    enum RunOutcome { OWNER_STOPPED, TERMINAL_RACE }

    record SettledRun(RunOutcome outcome, MachineCandidateSubmission.RunSnapshot terminalRun) {
        SettledRun {
            if (outcome == null || terminalRun == null || !terminalRun.state().terminal()) {
                throw new IllegalArgumentException("A terminal candidate run outcome is required");
            }
            boolean ownerStopped = terminalRun.state() == MachineCandidateRunState.CLOSED
                    && terminalRun.closeReason() == MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED;
            if ((outcome == RunOutcome.OWNER_STOPPED) != ownerStopped) {
                throw new IllegalArgumentException("Candidate run outcome does not match its authoritative terminal");
            }
        }
    }

    private record Context(AcceptanceCandidateInternalTerminationIntentRow intent,
                           AcceptanceCandidateInternalLaunchRow launch) { }
}
