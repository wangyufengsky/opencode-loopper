package io.opencode.loopper.service;

import io.opencode.loopper.domain.CandidatePromptDispatchKind;
import io.opencode.loopper.domain.CandidatePromptDispatchState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.CandidatePromptDispatchRow;
import io.opencode.loopper.persistence.CandidateSubmissionRunRow;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Owns short transactions, run gates, claims, lifecycle audit, and terminal settlement. */
@Service
final class CandidatePromptDispatchStore {
    private final LoopperMachineCandidateMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final List<CandidateRunGuard> guards;

    CandidatePromptDispatchStore(
            @Qualifier("loopperMachineCandidateMapper") LoopperMachineCandidateMapper mapper,
            LifecycleTransitionService lifecycle, ObjectMapper json, List<CandidateRunGuard> guards) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.json = json;
        this.guards = List.copyOf(guards);
    }

    Reservation reserve(Command command, CandidatePromptDispatchService.BudgetReservation budget,
            String claimant, Instant instant, Duration ttl) {
        String id = id(command.kind(), command.run().runId(), command.sourceAttemptOrdinal());
        String requestSha = OpenCodeClient.promptRequestSha256(command.request());
        CandidatePromptDispatchRow existing = mapper.findCandidatePromptDispatch(id).orElse(null);
        if (existing != null) {
            requireIdentity(existing, command, requestSha);
            requireOpenRun(existing);
            return new Reservation(existing, null);
        }
        String now = instant.toString();
        String token = UUID.randomUUID().toString();
        CandidateLaunchRef launch = command.launch();
        CandidatePromptDispatchRow created = new CandidatePromptDispatchRow(id, command.run().runId(),
                launch == null ? null : launch.internalLaunchId(),
                launch == null ? null : launch.candidateLaunchId(),
                command.kind().name(), command.sourceAttemptOrdinal(), remoteId(command.run()),
                command.run().runtimeGenerationId(), command.request().messageId(), write(command.request()),
                requestSha, CandidatePromptDispatchState.PROMPTING.name(), true, now,
                claimant, token, instant.plus(ttl).toString(), 1,
                false, null, false, null, null, null, null, null, now, now, 0);
        lifecycle.create(subject(created, command.run()), created.state(), audit(created), () -> {
            requireOpenRun(created);
            if (!budget.reserve()) throw new BudgetUnavailable();
            return mapper.insertCandidatePromptDispatch(created);
        }, CandidatePromptDispatchStore::conflict);
        return new Reservation(created, new Claim(true, token, created.fence()));
    }

    Claim claim(String id, String claimant, Instant instant, Duration ttl) {
        CandidatePromptDispatchRow row = get(id);
        requireOpenRun(row);
        if (active(row.claimExpiresAt(), instant)) return Claim.unavailable(row.fence());
        if (!CandidatePromptDispatchState.PROMPTING.name().equals(row.state())
                && !CandidatePromptDispatchState.DISCONNECTED.name().equals(row.state())) {
            return Claim.unavailable(row.fence());
        }
        CandidatePromptDispatchRow claimed = copy(row, row.state(), row.modelCallConsumed(),
                claimant, UUID.randomUUID().toString(), instant.plus(ttl).toString(), row.fence() + 1,
                row.dispatchAttempted(), row.dispatchStartedAt(), row.acknowledged(), row.ackedAt(),
                row.terminationProof(), row.terminationProofAt(), row.lastErrorCode(), row.lastErrorDetail());
        mutate(claimed);
        return new Claim(true, claimed.claimToken(), claimed.fence());
    }

    boolean markDispatchStarted(String id, Claim claim) {
        CandidatePromptDispatchRow row = get(id);
        requireClaim(row, claim);
        if (row.dispatchAttempted()) return false;
        CandidatePromptDispatchRow attempted = copy(row, row.state(), true, row.claimOwner(), row.claimToken(),
                row.claimExpiresAt(), row.fence(), true, now(), false, null, null, null, null, null);
        mutate(attempted);
        return true;
    }

    boolean acknowledge(String id, Claim claim) {
        CandidatePromptDispatchRow row = get(id);
        if (CandidatePromptDispatchState.ACKNOWLEDGED.name().equals(row.state())) return true;
        requireClaimToken(row, claim);
        if (CandidatePromptDispatchState.STOPPING.name().equals(row.state())) {
            CandidatePromptDispatchRow stopping = copy(row, row.state(), row.modelCallConsumed(), null, null, null,
                    row.fence(), row.dispatchAttempted(), row.dispatchStartedAt(), true, now(),
                    row.terminationProof(), row.terminationProofAt(), row.lastErrorCode(), row.lastErrorDetail());
            mutate(stopping);
            return false;
        }
        requireOpenRun(row);
        CandidatePromptDispatchRow acknowledged = copy(row, CandidatePromptDispatchState.ACKNOWLEDGED.name(),
                true, null, null, null, row.fence(), row.dispatchAttempted(), row.dispatchStartedAt(), true, now(),
                null, null, null, null);
        transition(row, acknowledged, LifecycleEvent.COMPLETE, "CANDIDATE_PROMPT_ACKNOWLEDGED");
        return true;
    }

    void requireClaim(String id, Claim claim) { requireClaim(get(id), claim); }
    void requireLookupClaim(String id, Claim claim) {
        CandidatePromptDispatchRow row = get(id);
        CandidatePromptDispatchState state = CandidatePromptDispatchState.valueOf(row.state());
        if (state != CandidatePromptDispatchState.PROMPTING
                && state != CandidatePromptDispatchState.DISCONNECTED
                && state != CandidatePromptDispatchState.STOPPING) throw conflict();
        requireClaimToken(row, claim);
    }
    private void requireClaim(CandidatePromptDispatchRow row, Claim claim) {
        CandidatePromptDispatchState state = CandidatePromptDispatchState.valueOf(row.state());
        if (state != CandidatePromptDispatchState.PROMPTING
                && state != CandidatePromptDispatchState.DISCONNECTED) throw conflict();
        requireClaimToken(row, claim);
        requireOpenRun(row);
    }

    private void requireClaimToken(CandidatePromptDispatchRow row, Claim claim) {
        if (claim == null || !claim.acquired() || claim.fence() != row.fence()
                || !Objects.equals(claim.token(), row.claimToken())) throw conflict();
    }

    void disconnect(String id, Claim claim, String code, String detail) {
        CandidatePromptDispatchRow row = get(id);
        if (claim != null && claim.acquired() && (claim.fence() != row.fence()
                || !Objects.equals(claim.token(), row.claimToken()))) return;
        boolean stopping = CandidatePromptDispatchState.STOPPING.name().equals(row.state());
        CandidatePromptDispatchRow disconnected = copy(row,
                stopping ? row.state() : CandidatePromptDispatchState.DISCONNECTED.name(),
                row.modelCallConsumed(), null, null, null, row.fence(), row.dispatchAttempted(),
                row.dispatchStartedAt(), row.acknowledged(), row.ackedAt(), row.terminationProof(),
                row.terminationProofAt(), code, safe(detail));
        if (stopping || CandidatePromptDispatchState.DISCONNECTED.name().equals(row.state())) mutate(disconnected);
        else transition(row, disconnected, LifecycleEvent.DISCONNECT, "CANDIDATE_PROMPT_RESULT_UNKNOWN");
    }

    boolean prepareDesignerCancellation(String designerSessionId, Instant instant) {
        boolean ready = true;
        for (CandidatePromptDispatchRow row : activeDesignerDispatches(designerSessionId)) {
            if (active(row.claimExpiresAt(), instant)) {
                ready = false;
                if (!CandidatePromptDispatchState.STOPPING.name().equals(row.state())) {
                    CandidatePromptDispatchRow stopping = copy(row, CandidatePromptDispatchState.STOPPING.name(),
                            row.modelCallConsumed(), row.claimOwner(), row.claimToken(), row.claimExpiresAt(),
                            row.fence(), row.dispatchAttempted(), row.dispatchStartedAt(), row.acknowledged(),
                            row.ackedAt(), row.terminationProof(), row.terminationProofAt(),
                            "DESIGNER_CANCELLED_IO_IN_FLIGHT",
                            "Designer cancellation is waiting for candidate prompt I/O reconciliation");
                    transition(row, stopping, LifecycleEvent.ABORT, "DESIGNER_CANCELLED_IO_IN_FLIGHT");
                }
                continue;
            }
            CandidatePromptDispatchRow current = row;
            if (row.claimOwner() != null) {
                current = copy(row, row.state(), row.modelCallConsumed(), null, null, null,
                        row.fence() + 1, row.dispatchAttempted(), row.dispatchStartedAt(), row.acknowledged(),
                        row.ackedAt(), row.terminationProof(), row.terminationProofAt(), row.lastErrorCode(),
                        row.lastErrorDetail());
                mutate(current);
                current = get(row.id());
            }
            if (!CandidatePromptDispatchState.STOPPING.name().equals(current.state())) {
                CandidatePromptDispatchRow stopping = copy(current, CandidatePromptDispatchState.STOPPING.name(),
                        current.modelCallConsumed(), null, null, null, current.fence(), current.dispatchAttempted(),
                        current.dispatchStartedAt(), current.acknowledged(), current.ackedAt(),
                        current.terminationProof(), current.terminationProofAt(), "DESIGNER_CANCELLED",
                        "Designer cancellation requires positive remote stop proof");
                transition(current, stopping, LifecycleEvent.ABORT, "DESIGNER_CANCELLED");
            }
        }
        return ready;
    }

    boolean prepareRunTermination(String runId, Instant instant) {
        if (runId == null || runId.isBlank() || instant == null) throw new IllegalArgumentException();
        boolean ready = true;
        for (CandidatePromptDispatchRow row : mapper.listActiveCandidatePromptDispatchesForRun(runId)) {
            if (active(row.claimExpiresAt(), instant)) {
                ready = false;
                if (!CandidatePromptDispatchState.STOPPING.name().equals(row.state())) {
                    CandidatePromptDispatchRow stopping = copy(row, CandidatePromptDispatchState.STOPPING.name(),
                            row.modelCallConsumed(), row.claimOwner(), row.claimToken(), row.claimExpiresAt(),
                            row.fence(), row.dispatchAttempted(), row.dispatchStartedAt(), row.acknowledged(),
                            row.ackedAt(), row.terminationProof(), row.terminationProofAt(),
                            "CANDIDATE_RUN_TERMINATION_IO_IN_FLIGHT",
                            "Candidate run termination is waiting for prompt I/O reconciliation");
                    transition(row, stopping, LifecycleEvent.ABORT,
                            "CANDIDATE_RUN_TERMINATION_IO_IN_FLIGHT");
                }
                continue;
            }
            CandidatePromptDispatchRow current = row;
            if (row.claimOwner() != null) {
                current = copy(row, row.state(), row.modelCallConsumed(), null, null, null,
                        row.fence() + 1, row.dispatchAttempted(), row.dispatchStartedAt(), row.acknowledged(),
                        row.ackedAt(), row.terminationProof(), row.terminationProofAt(), row.lastErrorCode(),
                        row.lastErrorDetail());
                mutate(current);
                current = get(row.id());
            }
            if (!CandidatePromptDispatchState.STOPPING.name().equals(current.state())) {
                CandidatePromptDispatchRow stopping = copy(current, CandidatePromptDispatchState.STOPPING.name(),
                        current.modelCallConsumed(), null, null, null, current.fence(), current.dispatchAttempted(),
                        current.dispatchStartedAt(), current.acknowledged(), current.ackedAt(),
                        current.terminationProof(), current.terminationProofAt(),
                        "CANDIDATE_RUN_TERMINATION_PENDING",
                        "Candidate prompt is fenced before candidate run termination");
                transition(current, stopping, LifecycleEvent.ABORT, "CANDIDATE_RUN_TERMINATION_PENDING");
            }
        }
        return ready;
    }

    Map<String, String> completeDesignerCancellation(String designerSessionId, Map<String, String> remoteProofs) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (remoteProofs != null) merged.putAll(remoteProofs);
        for (CandidatePromptDispatchRow row : mapper.listCandidatePromptDispatchesForDesigner(designerSessionId)) {
            String proof = row.terminationProof() != null ? row.terminationProof() : merged.get(row.externalSessionId());
            if (CandidatePromptDispatchState.CANCELLED.name().equals(row.state())
                    && !row.dispatchAttempted() && !row.acknowledged()) {
                if (proof != null && !CandidateSessionTerminationProof.persisted(proof)) throw conflict();
                if (proof != null) merged.put(row.externalSessionId(), proof);
                continue;
            }
            if (!CandidateSessionTerminationProof.persisted(proof)) {
                throw new ConflictException("CANDIDATE_PROMPT_STOP_PROOF_MISSING",
                        "Candidate prompt remote stop proof is missing");
            }
            complete(row, proof, "DESIGNER_CANCELLED");
            merged.put(row.externalSessionId(), proof);
        }
        return Map.copyOf(merged);
    }

    boolean completeForRun(String runId, String proof) {
        List<CandidatePromptDispatchRow> rows = mapper.listActiveCandidatePromptDispatchesForRun(runId);
        if (rows.stream().anyMatch(row -> active(row.claimExpiresAt(), Instant.now()))) return false;
        rows.forEach(row -> complete(row, proof, "CANDIDATE_REMOTE_TERMINATED"));
        return true;
    }

    List<MachineCandidateSubmission.Problem> rejectedProblems(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException();
        List<io.opencode.loopper.persistence.CandidateSubmissionAttemptRow> attempts =
                mapper.listCandidateSubmissionAttempts(runId);
        for (int index = attempts.size() - 1; index >= 0; index--) {
            var attempt = attempts.get(index);
            if (!"REJECTED".equals(attempt.outcome())) continue;
            try {
                return List.copyOf(json.readValue(attempt.problemsJson(),
                        new TypeReference<List<MachineCandidateSubmission.Problem>>() { }));
            } catch (JacksonException | NullPointerException invalid) {
                throw new ConflictException("ACCEPTANCE_CORRECTION_PROBLEMS_INVALID",
                        "验收候选修正缺少可恢复的问题闭集");
            }
        }
        throw new ConflictException("ACCEPTANCE_CORRECTION_REJECTED_ATTEMPT_MISSING",
                "验收候选修正缺少可恢复的 rejected attempt");
    }

    private void complete(CandidatePromptDispatchRow input, String proof, String reason) {
        if (!CandidateSessionTerminationProof.persisted(proof)) {
            throw new ConflictException("CANDIDATE_PROMPT_STOP_PROOF_MISSING",
                    "Candidate prompt remote stop proof is missing");
        }
        CandidatePromptDispatchRow row = get(input.id());
        if (CandidatePromptDispatchState.STOPPED.name().equals(row.state())) {
            if (!proof.equals(row.terminationProof())) throw conflict();
            return;
        }
        if (active(row.claimExpiresAt(), Instant.now())) {
            throw new ConflictException("CANDIDATE_PROMPT_IO_IN_FLIGHT",
                    "Candidate prompt I/O must finish before remote termination is settled");
        }
        if (!CandidatePromptDispatchState.STOPPING.name().equals(row.state())) {
            CandidatePromptDispatchRow stopping = copy(row, CandidatePromptDispatchState.STOPPING.name(),
                    row.modelCallConsumed(), null, null, null, row.fence() + 1, row.dispatchAttempted(),
                    row.dispatchStartedAt(), row.acknowledged(), row.ackedAt(), row.terminationProof(),
                    row.terminationProofAt(), row.lastErrorCode(), row.lastErrorDetail());
            transition(row, stopping, LifecycleEvent.ABORT, reason);
            row = get(row.id());
        }
        CandidatePromptDispatchRow stopped = copy(row, CandidatePromptDispatchState.STOPPED.name(),
                row.modelCallConsumed(), null, null, null, row.fence(), row.dispatchAttempted(),
                row.dispatchStartedAt(), row.acknowledged(), row.ackedAt(), proof, now(),
                row.lastErrorCode(), row.lastErrorDetail());
        transition(row, stopped, LifecycleEvent.COMPLETE, "REMOTE_STOP_PROOF_PERSISTED");
    }

    private List<CandidatePromptDispatchRow> activeDesignerDispatches(String designerSessionId) {
        return mapper.listCandidatePromptDispatchesForDesigner(designerSessionId).stream()
                .filter(row -> !CandidatePromptDispatchState.STOPPED.name().equals(row.state())
                        && !CandidatePromptDispatchState.CANCELLED.name().equals(row.state()))
                .toList();
    }

    private CandidateSubmissionRunRow requireOpenRun(CandidatePromptDispatchRow dispatch) {
        CandidateSubmissionRunRow run = mapper.findCandidateSubmissionRun(dispatch.runId())
                .orElseThrow(CandidatePromptDispatchStore::conflict);
        if (!MachineCandidateRunState.OPEN.name().equals(run.state())
                || !Objects.equals(run.externalSessionId(), dispatch.externalSessionId())
                || !Objects.equals(run.runtimeGenerationId(), dispatch.runtimeGenerationId())) throw conflict();
        CandidatePromptDispatchKind kind = CandidatePromptDispatchKind.valueOf(dispatch.dispatchKind());
        if (kind == CandidatePromptDispatchKind.INITIAL) {
            if (run.attemptsUsed() != 0) throw conflict();
        } else if (dispatch.sourceAttemptOrdinal() == null
                || run.attemptsUsed() != dispatch.sourceAttemptOrdinal()) throw conflict();
        try {
            CandidateLaunchRef launch = CandidateLaunchRef.fromColumns(
                    dispatch.internalLaunchId(), dispatch.candidateLaunchId());
            if (MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP.name().equals(run.submissionChannel())) {
                CandidatePromptRunContract.validateInternal(snapshot(run), launch);
            } else if (launch != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw conflict();
        }
        validateGuards(run);
        return run;
    }

    private void validateGuards(CandidateSubmissionRunRow row) {
        MachineCandidateSubmission.RunSnapshot snapshot = snapshot(row);
        MachineCandidateSubmission.SubmissionChannel channel =
                MachineCandidateSubmission.SubmissionChannel.valueOf(row.submissionChannel());
        guards.forEach(guard -> guard.validate(snapshot, channel));
    }

    private static MachineCandidateSubmission.RunSnapshot snapshot(CandidateSubmissionRunRow row) {
        MachineCandidateSubmission.CandidateScope scope = row.designerSessionId() != null
                ? MachineCandidateSubmission.CandidateScope.designerSession(row.designerSessionId())
                : row.taskId() != null ? MachineCandidateSubmission.CandidateScope.task(row.taskId())
                : MachineCandidateSubmission.CandidateScope.project(row.projectId());
        return new MachineCandidateSubmission.RunSnapshot(row.id(), scope,
                new MachineCandidateSubmission.CandidateOwnerRef(
                        MachineCandidateSubmission.CandidateOwnerType.valueOf(row.ownerType()), row.ownerId()),
                MachineCandidateKind.valueOf(row.candidateKind()), row.workflowStep(), row.sourceRevision(),
                row.ownerVersion(), MachineCandidateSubmission.SubmissionChannel.valueOf(row.submissionChannel()),
                row.contractVersion(), row.runtimeGenerationId(), row.externalSessionId(),
                MachineCandidateRunState.valueOf(row.state()), row.maxAttempts(), row.attemptsUsed(),
                row.terminalAttemptId(), row.version(), row.closeReason() == null ? null
                : MachineCandidateSubmission.CandidateCloseReason.valueOf(row.closeReason()));
    }

    private void requireIdentity(CandidatePromptDispatchRow row, Command command, String requestSha) {
        if (!row.runId().equals(command.run().runId())
                || !Objects.equals(row.internalLaunchId(), internalLaunchId(command.launch()))
                || !Objects.equals(row.candidateLaunchId(), candidateLaunchId(command.launch()))
                || !row.dispatchKind().equals(command.kind().name())
                || !Objects.equals(row.sourceAttemptOrdinal(), command.sourceAttemptOrdinal())
                || !row.externalSessionId().equals(remoteId(command.run()))
                || !row.runtimeGenerationId().equals(command.run().runtimeGenerationId())
                || !row.messageId().equals(command.request().messageId())
                || !row.requestSha256().equals(requestSha)) {
            throw new ConflictException("CANDIDATE_PROMPT_DISPATCH_STALE",
                    "Candidate prompt identity or runtime binding changed");
        }
    }

    CandidatePromptDispatchRow get(String id) {
        return mapper.findCandidatePromptDispatch(id).orElseThrow(CandidatePromptDispatchStore::conflict);
    }

    private void mutate(CandidatePromptDispatchRow row) {
        lifecycle.mutateWithoutTransition(() -> mapper.updateCandidatePromptDispatch(row),
                CandidatePromptDispatchStore::conflict);
    }

    private void transition(CandidatePromptDispatchRow from, CandidatePromptDispatchRow to,
            LifecycleEvent event, String reason) {
        lifecycle.transition(subject(from), from.state(), to.state(), event, reason, audit(to),
                () -> mapper.updateCandidatePromptDispatch(to), CandidatePromptDispatchStore::conflict);
    }

    private LifecycleTransitionService.Subject subject(CandidatePromptDispatchRow row) {
        CandidateSubmissionRunRow run = mapper.findCandidateSubmissionRun(row.runId()).orElseThrow();
        return subject(row, scope(run));
    }

    private LifecycleTransitionService.Subject subject(CandidatePromptDispatchRow row,
            MachineCandidateSubmission.RunSnapshot run) {
        return subject(row, new Scope(scopeType(run.scope().type()), run.scope().id()));
    }

    private static LifecycleTransitionService.Subject subject(CandidatePromptDispatchRow row, Scope scope) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.CANDIDATE_PROMPT_DISPATCH,
                row.id(), scope.type(), scope.id());
    }

    private static Scope scope(CandidateSubmissionRunRow run) {
        if (run.designerSessionId() != null) return new Scope(LifecycleScopeType.DESIGNER, run.designerSessionId());
        if (run.taskId() != null) return new Scope(LifecycleScopeType.TASK, run.taskId());
        return new Scope(LifecycleScopeType.PROJECT, run.projectId());
    }

    private static LifecycleScopeType scopeType(MachineCandidateSubmission.CandidateScopeType type) {
        return switch (type) {
            case DESIGNER_SESSION -> LifecycleScopeType.DESIGNER;
            case TASK -> LifecycleScopeType.TASK;
            case PROJECT -> LifecycleScopeType.PROJECT;
        };
    }

    private CandidatePromptDispatchRow copy(CandidatePromptDispatchRow row, String state, boolean consumed,
            String claimOwner, String claimToken, String claimExpiresAt, long fence,
            boolean attempted, String attemptedAt, boolean acknowledged, String ackedAt,
            String proof, String proofAt, String code, String detail) {
        return new CandidatePromptDispatchRow(row.id(), row.runId(), row.internalLaunchId(), row.candidateLaunchId(),
                row.dispatchKind(),
                row.sourceAttemptOrdinal(), row.externalSessionId(), row.runtimeGenerationId(),
                row.messageId(), row.requestJson(),
                row.requestSha256(), state, consumed, row.modelCallConsumedAt(), claimOwner, claimToken,
                claimExpiresAt, fence, attempted, attemptedAt, acknowledged, ackedAt, proof, proofAt,
                code, detail, row.createdAt(), now(), row.version());
    }

    private String write(OpenCodeClient.PromptRequest request) {
        try { return json.writeValueAsString(request); }
        catch (JacksonException failure) {
            throw new IllegalArgumentException("Prompt request is not serializable", failure);
        }
    }

    private static Map<String, Object> audit(CandidatePromptDispatchRow row) {
        LinkedHashMap<String, Object> audit = new LinkedHashMap<>();
        audit.put("dispatchKind", row.dispatchKind());
        if (row.internalLaunchId() != null) audit.put("internalLaunchId", row.internalLaunchId());
        if (row.candidateLaunchId() != null) audit.put("candidateLaunchId", row.candidateLaunchId());
        if (row.sourceAttemptOrdinal() != null) audit.put("sourceAttemptOrdinal", row.sourceAttemptOrdinal());
        audit.put("modelCallConsumed", row.modelCallConsumed());
        audit.put("dispatchAttempted", row.dispatchAttempted());
        audit.put("acknowledged", row.acknowledged());
        audit.put("hasTerminationProof", row.terminationProof() != null);
        return Map.copyOf(audit);
    }

    private static String id(CandidatePromptDispatchKind kind, String runId, Integer sourceAttemptOrdinal) {
        String salt = kind == CandidatePromptDispatchKind.INITIAL
                ? "candidate-prompt-dispatch:" + runId + ":INITIAL"
                : "candidate-prompt-dispatch:" + runId + ":" + sourceAttemptOrdinal;
        return UUID.nameUUIDFromBytes(salt.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String remoteId(MachineCandidateSubmission.RunSnapshot run) {
        if (run.externalSessionId() == null || run.externalSessionId().isBlank()) throw conflict();
        return run.externalSessionId();
    }
    private static String internalLaunchId(CandidateLaunchRef launch) {
        return launch == null ? null : launch.internalLaunchId();
    }
    private static String candidateLaunchId(CandidateLaunchRef launch) {
        return launch == null ? null : launch.candidateLaunchId();
    }
    private static boolean active(String expiresAt, Instant instant) {
        return expiresAt != null && Instant.parse(expiresAt).isAfter(instant);
    }
    static String code(RuntimeException failure) {
        return failure instanceof io.opencode.loopper.domain.SessionFailure session ? session.code()
                : failure instanceof ConflictException conflict ? conflict.code() : "OPENCODE_PROMPT_DISPATCH_FAILED";
    }
    private static String safe(String detail) {
        if (detail == null || detail.isBlank()) return "Candidate prompt failed";
        String value = detail.replaceAll("[\\r\\n]+", " ").trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
    private static String now() { return Instant.now().toString(); }
    private static ConflictException conflict() {
        return new ConflictException("CANDIDATE_PROMPT_DISPATCH_STALE",
                "Candidate prompt state, owner, or runtime binding changed");
    }

    record Command(CandidatePromptDispatchKind kind, MachineCandidateSubmission.RunSnapshot run,
                   CandidateLaunchRef launch, Integer sourceAttemptOrdinal,
                   OpenCodeClient.PromptRequest request) { }
    record Claim(boolean acquired, String token, long fence) {
        static Claim unavailable(long fence) { return new Claim(false, null, fence); }
    }
    record Reservation(CandidatePromptDispatchRow row, Claim claim) { }
    private record Scope(LifecycleScopeType type, String id) { }
    static final class BudgetUnavailable extends RuntimeException { }
}
