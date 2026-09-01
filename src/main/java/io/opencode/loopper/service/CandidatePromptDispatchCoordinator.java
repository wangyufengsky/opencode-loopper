package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.CandidatePromptDispatchKind;
import io.opencode.loopper.domain.CandidatePromptDispatchState;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.CandidatePromptDispatchRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Coordinates exact-message recovery and the one-way POST boundary outside transactions. */
@Service
final class CandidatePromptDispatchCoordinator {
    private final CandidatePromptDispatchStore store;
    private final CandidatePromptDispatchBarrier barrier;
    private final Duration claimTtl;

    CandidatePromptDispatchCoordinator(CandidatePromptDispatchStore store,
            CandidatePromptDispatchBarrier barrier, LoopperProperties properties) {
        this.store = store;
        this.barrier = barrier;
        this.claimTtl = CandidatePromptDispatchService.claimTtl(properties);
    }

    CandidatePromptDispatchService.Result advanceInitial(MachineCandidateSubmission.RunSnapshot run,
            String internalLaunchId,
            OpenCodeClient.OpenCodeSession remote, OpenCodeClient.PromptRequest request,
            CandidatePromptDispatchService.BudgetReservation budget,
            CandidatePromptDispatchService.PromptIo io, String claimant, Instant instant) {
        validateChannelLaunch(run, internalLaunchId);
        validateInitial(run, remote, request, budget, io, claimant, instant);
        return advance(new CandidatePromptDispatchStore.Command(CandidatePromptDispatchKind.INITIAL,
                run, internalLaunchId, null, request), remote, budget, io, claimant, instant);
    }

    CandidatePromptDispatchService.Result advanceCorrection(MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.SubmissionResult rejected,
            String internalLaunchId,
            OpenCodeClient.OpenCodeSession remote, OpenCodeClient.PromptRequest request,
            CandidatePromptDispatchService.BudgetReservation budget,
            CandidatePromptDispatchService.PromptIo io, String claimant, Instant instant) {
        validateChannelLaunch(run, internalLaunchId);
        validateCorrection(run, rejected, remote, request, budget, io, claimant, instant);
        return advance(new CandidatePromptDispatchStore.Command(CandidatePromptDispatchKind.CORRECTION,
                run, internalLaunchId, rejected.attemptOrdinal(), request), remote, budget, io, claimant, instant);
    }

    private CandidatePromptDispatchService.Result advance(CandidatePromptDispatchStore.Command command,
            OpenCodeClient.OpenCodeSession remote, CandidatePromptDispatchService.BudgetReservation budget,
            CandidatePromptDispatchService.PromptIo io, String claimant, Instant instant) {
        CandidatePromptDispatchBarrier.Ticket ticket = barrier.begin(command.run().runId());
        if (!ticket.acquired()) return CandidatePromptDispatchService.Result.stopped();
        try {
            return advanceWithBarrier(command, remote, budget, io, claimant, instant);
        } finally {
            ticket.close();
        }
    }

    private CandidatePromptDispatchService.Result advanceWithBarrier(CandidatePromptDispatchStore.Command command,
            OpenCodeClient.OpenCodeSession remote, CandidatePromptDispatchService.BudgetReservation budget,
            CandidatePromptDispatchService.PromptIo io, String claimant, Instant instant) {
        String invocationOwner = claimant + ":" + UUID.randomUUID();
        CandidatePromptDispatchStore.Reservation reservation;
        try {
            reservation = store.reserve(command, budget, invocationOwner, instant, claimTtl);
        } catch (CandidatePromptDispatchStore.BudgetUnavailable exhausted) {
            return CandidatePromptDispatchService.Result.budgetExhausted();
        }
        CandidatePromptDispatchRow row = reservation.row();
        CandidatePromptDispatchState state = CandidatePromptDispatchState.valueOf(row.state());
        if (state == CandidatePromptDispatchState.ACKNOWLEDGED) {
            return CandidatePromptDispatchService.Result.acknowledged();
        }
        if (state == CandidatePromptDispatchState.STOPPING || state == CandidatePromptDispatchState.STOPPED
                || state == CandidatePromptDispatchState.CANCELLED) {
            return CandidatePromptDispatchService.Result.stopped();
        }
        CandidatePromptDispatchStore.Claim claim = reservation.claim() != null
                ? reservation.claim() : store.claim(row.id(), invocationOwner, instant, claimTtl);
        if (!claim.acquired()) return CandidatePromptDispatchService.Result.pending();

        OpenCodeClient.MessageLookup before = lookup(row, claim, remote, command.request(), io);
        if (before == null) return afterLookupFailure(row.id());
        CandidatePromptDispatchService.Result resolved = resolveLookup(row.id(), claim, before, false);
        if (resolved != null) return resolved;

        try {
            if (!store.markDispatchStarted(row.id(), claim)) return CandidatePromptDispatchService.Result.pending();
        } catch (RuntimeException failure) {
            store.disconnect(row.id(), claim, CandidatePromptDispatchStore.code(failure), failure.getMessage());
            return CandidatePromptDispatchService.Result.pending();
        }
        try {
            io.dispatch(remote, command.request());
        } catch (RuntimeException failure) {
            store.disconnect(row.id(), claim, "OPENCODE_PROMPT_RESULT_UNKNOWN",
                    "Candidate prompt POST result is unknown; exact acknowledgement recovery is required: "
                            + safe(failure.getMessage()));
            return CandidatePromptDispatchService.Result.resultUnknown();
        }

        OpenCodeClient.MessageLookup after = lookup(row, claim, remote, command.request(), io);
        if (after == null) return CandidatePromptDispatchService.Result.resultUnknown();
        CandidatePromptDispatchService.Result afterResult = resolveLookup(row.id(), claim, after, true);
        return afterResult == null ? CandidatePromptDispatchService.Result.resultUnknown() : afterResult;
    }

    private OpenCodeClient.MessageLookup lookup(CandidatePromptDispatchRow row,
            CandidatePromptDispatchStore.Claim claim, OpenCodeClient.OpenCodeSession remote,
            OpenCodeClient.PromptRequest request, CandidatePromptDispatchService.PromptIo io) {
        try {
            store.requireLookupClaim(row.id(), claim);
            return io.lookup(remote, request, row.requestSha256());
        } catch (RuntimeException failure) {
            CandidatePromptDispatchRow latest = store.get(row.id());
            String code = latest.dispatchAttempted()
                    ? "OPENCODE_PROMPT_RESULT_UNKNOWN" : CandidatePromptDispatchStore.code(failure);
            store.disconnect(row.id(), claim, code,
                    latest.dispatchAttempted()
                            ? "Candidate prompt acknowledgement lookup failed after the POST boundary: "
                                    + safe(failure.getMessage())
                            : failure.getMessage());
            return null;
        }
    }

    private CandidatePromptDispatchService.Result afterLookupFailure(String id) {
        return store.get(id).dispatchAttempted()
                ? CandidatePromptDispatchService.Result.resultUnknown()
                : CandidatePromptDispatchService.Result.pending();
    }

    private CandidatePromptDispatchService.Result resolveLookup(String id,
            CandidatePromptDispatchStore.Claim claim, OpenCodeClient.MessageLookup lookup, boolean afterPost) {
        CandidatePromptDispatchRow row = store.get(id);
        if (!lookup.supported()) {
            store.disconnect(id, claim, afterPost || row.dispatchAttempted()
                            ? "OPENCODE_PROMPT_RESULT_UNKNOWN" : "OPENCODE_PROMPT_LOOKUP_UNAVAILABLE",
                    afterPost || row.dispatchAttempted()
                            ? "Candidate prompt crossed the POST boundary but exact lookup is unavailable"
                            : "OpenCode cannot recover a deterministic candidate prompt acknowledgement");
            return afterPost || row.dispatchAttempted()
                    ? CandidatePromptDispatchService.Result.resultUnknown()
                    : CandidatePromptDispatchService.Result.lookupUnsupported();
        }
        if (lookup.exists() && !row.requestSha256().equals(lookup.verifiedRequestSha256())) {
            store.disconnect(id, claim, "OPENCODE_PROMPT_REQUEST_STALE",
                    "OpenCode candidate prompt acknowledgement does not match the frozen request");
            return CandidatePromptDispatchService.Result.lookupUnsupported();
        }
        if (lookup.exists()) {
            try {
                return store.acknowledge(id, claim)
                        ? CandidatePromptDispatchService.Result.acknowledged()
                        : CandidatePromptDispatchService.Result.pending();
            } catch (ConflictException stopped) {
                store.disconnect(id, claim, stopped.code(), stopped.getMessage());
                return CandidatePromptDispatchService.Result.pending();
            }
        }
        if (afterPost || row.dispatchAttempted()) {
            store.disconnect(id, claim, "OPENCODE_PROMPT_RESULT_UNKNOWN",
                    "Candidate prompt crossed POST boundary but is not yet recoverable; resend is forbidden");
            return CandidatePromptDispatchService.Result.resultUnknown();
        }
        return null;
    }

    boolean prepareDesignerCancellation(String designerSessionId, Instant instant) {
        return store.prepareDesignerCancellation(designerSessionId, instant);
    }
    boolean prepareRunTermination(String runId, Instant instant) {
        boolean processReady = barrier.prepareTermination(runId);
        boolean storeReady = store.prepareRunTermination(runId, instant);
        return processReady && storeReady;
    }
    Map<String, String> completeDesignerCancellation(String designerSessionId, Map<String, String> proofs) {
        return store.completeDesignerCancellation(designerSessionId, proofs);
    }
    boolean completeForRun(String runId, String proof) {
        if (!completeForRunInTransaction(runId, proof)) return false;
        releaseRunBarrier(runId);
        return true;
    }
    boolean completeForRunInTransaction(String runId, String proof) {
        if (!barrier.prepareTermination(runId)) return false;
        return store.completeForRun(runId, proof);
    }
    void releaseRunBarrier(String runId) {
        barrier.complete(runId);
    }
    List<MachineCandidateSubmission.Problem> rejectedProblems(String runId) {
        return store.rejectedProblems(runId);
    }

    private static void validateChannelLaunch(
            MachineCandidateSubmission.RunSnapshot run, String internalLaunchId) {
        if (run == null || run.submissionChannel() == null) {
            throw new IllegalArgumentException("Candidate prompt run channel is required");
        }
        if (run.submissionChannel() == MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP) {
            if (internalLaunchId == null || internalLaunchId.isBlank()) {
                throw new IllegalArgumentException("INTERNAL_MCP candidate prompt requires internalLaunchId");
            }
            return;
        }
        if (internalLaunchId != null) {
            throw new IllegalArgumentException("IN_PROCESS_LEGACY candidate prompt forbids internalLaunchId");
        }
    }

    private static void validateInitial(MachineCandidateSubmission.RunSnapshot run,
            OpenCodeClient.OpenCodeSession remote, OpenCodeClient.PromptRequest request,
            CandidatePromptDispatchService.BudgetReservation budget,
            CandidatePromptDispatchService.PromptIo io, String claimant, Instant instant) {
        if (run == null || remote == null || request == null || budget == null || io == null
                || claimant == null || claimant.isBlank() || instant == null
                || run.state() != MachineCandidateRunState.OPEN || run.attemptsUsed() != 0
                || run.candidateKind() != MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7
                || run.submissionChannel() != MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                || !AcceptanceClosedChoiceCandidateCoordinator.WORKFLOW_STEP.equals(run.workflowStep())
                || !"ACCEPTANCE_CLOSED_CHOICE_V7".equals(run.contractVersion())
                || run.maxAttempts() != 2 || !remote.id().equals(run.externalSessionId())
                || !CandidatePromptDispatchService.initialMessageId(run.runId()).equals(request.messageId())) {
            throw new IllegalArgumentException("Initial candidate prompt dispatch is incomplete or stale");
        }
    }

    private static void validateCorrection(MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.SubmissionResult rejected, OpenCodeClient.OpenCodeSession remote,
            OpenCodeClient.PromptRequest request, CandidatePromptDispatchService.BudgetReservation budget,
            CandidatePromptDispatchService.PromptIo io, String claimant, Instant instant) {
        if (run == null || rejected == null || remote == null || request == null || budget == null || io == null
                || claimant == null || claimant.isBlank() || instant == null
                || rejected.outcome() != MachineCandidateOutcome.REJECTED || !rejected.retryable()
                || !run.runId().equals(rejected.runId()) || !remote.id().equals(run.externalSessionId())
                || !CandidatePromptDispatchService.messageId(run.runId(), rejected.attemptOrdinal())
                        .equals(request.messageId())) {
            throw new IllegalArgumentException("Candidate correction prompt dispatch is incomplete or stale");
        }
    }

    private static String safe(String detail) {
        if (detail == null || detail.isBlank()) return "Candidate prompt failed";
        String value = detail.replaceAll("[\\r\\n]+", " ").trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
