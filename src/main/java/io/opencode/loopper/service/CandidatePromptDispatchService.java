package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Stable seam for durable INITIAL and CORRECTION candidate prompt dispatch. */
@Service
final class CandidatePromptDispatchService {
    private final CandidatePromptDispatchCoordinator coordinator;
    private final TransactionTemplate transactions;

    CandidatePromptDispatchService(CandidatePromptDispatchCoordinator coordinator,
            PlatformTransactionManager transactionManager) {
        this.coordinator = coordinator;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    Result advance(MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.SubmissionResult rejected,
            String internalLaunchId,
            OpenCodeClient.OpenCodeSession remote, OpenCodeClient.PromptRequest request,
            BudgetReservation budget, PromptIo io, String claimant, Instant instant) {
        return coordinator.advanceCorrection(
                run, rejected, internalLaunchId, remote, request, budget, io, claimant, instant);
    }

    Result advance(MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.SubmissionResult rejected,
            OpenCodeClient.OpenCodeSession remote, OpenCodeClient.PromptRequest request,
            BudgetReservation budget, PromptIo io, String claimant, Instant instant) {
        requireLegacyChannel(run);
        return advance(run, rejected, null, remote, request, budget, io, claimant, instant);
    }

    Result advanceInitial(MachineCandidateSubmission.RunSnapshot run,
            String internalLaunchId,
            OpenCodeClient.OpenCodeSession remote, OpenCodeClient.PromptRequest request,
            BudgetReservation budget, PromptIo io, String claimant, Instant instant) {
        return coordinator.advanceInitial(
                run, internalLaunchId, remote, request, budget, io, claimant, instant);
    }

    Result advanceInitial(MachineCandidateSubmission.RunSnapshot run,
            OpenCodeClient.OpenCodeSession remote, OpenCodeClient.PromptRequest request,
            BudgetReservation budget, PromptIo io, String claimant, Instant instant) {
        requireLegacyChannel(run);
        return advanceInitial(run, null, remote, request, budget, io, claimant, instant);
    }

    private static void requireLegacyChannel(MachineCandidateSubmission.RunSnapshot run) {
        if (run == null
                || run.submissionChannel() != MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY) {
            throw new IllegalArgumentException(
                    "Candidate prompt overload without internalLaunchId is reserved for IN_PROCESS_LEGACY");
        }
    }

    boolean prepareDesignerCancellation(String designerSessionId, Instant instant) {
        return coordinator.prepareDesignerCancellation(designerSessionId, instant);
    }

    boolean prepareRunTermination(String runId, Instant instant) {
        return coordinator.prepareRunTermination(runId, instant);
    }

    Map<String, String> completeDesignerCancellation(
            String designerSessionId, Map<String, String> remoteProofs) {
        return coordinator.completeDesignerCancellation(designerSessionId, remoteProofs);
    }

    boolean completeForRun(String runId, String proof) {
        return coordinator.completeForRun(runId, proof);
    }

    boolean settleForRun(String runId, String proof, Runnable ownerSettlement) {
        if (ownerSettlement == null) throw new IllegalArgumentException();
        Boolean settled = transactions.execute(status -> {
            if (!coordinator.completeForRunInTransaction(runId, proof)) return false;
            ownerSettlement.run();
            return true;
        });
        if (!Boolean.TRUE.equals(settled)) return false;
        coordinator.releaseRunBarrier(runId);
        return true;
    }

    List<MachineCandidateSubmission.Problem> rejectedProblems(String runId) {
        return coordinator.rejectedProblems(runId);
    }

    static String initialMessageId(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException();
        return "loopper-candidate-prompt-" + UUID.nameUUIDFromBytes(
                (runId + ":INITIAL").getBytes(StandardCharsets.UTF_8));
    }

    static String messageId(String runId, int attemptOrdinal) {
        if (runId == null || runId.isBlank() || attemptOrdinal < 1) throw new IllegalArgumentException();
        return "loopper-candidate-prompt-" + UUID.nameUUIDFromBytes(
                (runId + ":" + attemptOrdinal).getBytes(StandardCharsets.UTF_8));
    }

    static Duration claimTtl(LoopperProperties properties) {
        Duration connect = properties.getOpenCode().getConnectTimeout();
        Duration request = properties.getOpenCode().getRequestTimeout();
        if (connect == null || connect.isZero() || connect.isNegative()
                || request == null || request.isZero() || request.isNegative()) throw new IllegalArgumentException();
        return connect.plus(request).multipliedBy(2).plusSeconds(15);
    }

    @FunctionalInterface interface BudgetReservation { boolean reserve(); }
    interface PromptIo {
        OpenCodeClient.MessageLookup lookup(OpenCodeClient.OpenCodeSession remote,
                OpenCodeClient.PromptRequest request, String requestSha256);
        void dispatch(OpenCodeClient.OpenCodeSession remote, OpenCodeClient.PromptRequest request);
    }
    record Result(Status status) {
        static Result acknowledged() { return new Result(Status.ACKNOWLEDGED); }
        static Result pending() { return new Result(Status.PENDING); }
        static Result resultUnknown() { return new Result(Status.RESULT_UNKNOWN); }
        static Result budgetExhausted() { return new Result(Status.BUDGET_EXHAUSTED); }
        static Result lookupUnsupported() { return new Result(Status.LOOKUP_UNSUPPORTED); }
        static Result stopped() { return new Result(Status.STOPPED); }
    }
    enum Status { ACKNOWLEDGED, PENDING, RESULT_UNKNOWN, BUDGET_EXHAUSTED, LOOKUP_UNSUPPORTED, STOPPED }
}
