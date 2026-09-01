package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Owns v7 candidate Session transport and the bounded internal/legacy submission loop. */
@Component
final class DesignerAcceptanceCandidateOrchestrator {
    private final AcceptanceClosedChoiceCandidateCoordinator candidates;
    private final DesignerAcceptanceCandidatePromptFactory prompts;
    private final OpenCodeClient openCode;
    private final InternalMcpRuntimeAccess runtimeAccess;

    DesignerAcceptanceCandidateOrchestrator(AcceptanceClosedChoiceCandidateCoordinator candidates,
                                            ObjectMapper json, OpenCodeClient openCode,
                                            InternalMcpRuntimeAccess runtimeAccess) {
        this.candidates = candidates;
        this.prompts = new DesignerAcceptanceCandidatePromptFactory(json);
        this.openCode = openCode;
        this.runtimeAccess = runtimeAccess;
    }

    AcceptanceClosedChoiceCandidateCoordinator.Decision decide(
            DesignAcceptancePlanningRow planning, DesignerAcceptanceWorkflow.RoutingResult routing) {
        return candidates.decide(planning, routing);
    }

    OpenCodeClient.OpenCodeSession createInternal(Path projectRoot, OpenCodeClient.OpenCodeModel model) {
        return openCode.createSession(projectRoot,
                "OpenCode Loopper acceptance closed-choice candidate (NO_TOOLS)", model,
                AcceptanceClosedChoiceCandidateCoordinator.SESSION_PROFILE);
    }

    Start openInternal(LoopSpecCompilationRow compilation, DesignAcceptancePlanningRow planning,
                       DesignerAcceptanceWorkflow.RoutingResult routing,
                       OpenCodeClient.OpenCodeSession remote) {
        MachineCandidateSubmission.RunSnapshot run = candidates.openInternal(
                compilation, planning, routing, remote);
        String tool = remote.internalMcpServer() + "_" + InternalMcpContractCatalog.TOOL_NAME;
        return new Start(remote, run, prompts.internal(planning, routing, run, tool));
    }

    OpenCodeClient.OpenCodeSession createLegacy(Path projectRoot, OpenCodeClient.OpenCodeModel model) {
        return openCode.createSession(projectRoot,
                "OpenCode Loopper acceptance closed-choice legacy candidate (NO_TOOLS)", model,
                OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS);
    }

    Start openLegacy(LoopSpecCompilationRow compilation, DesignAcceptancePlanningRow planning,
                     DesignerAcceptanceWorkflow.RoutingResult routing,
                     OpenCodeClient.OpenCodeSession remote,
                     MachineCandidateSubmission.SubmissionResult rejected) {
        MachineCandidateSubmission.RunSnapshot run = candidates.openLegacy(
                compilation, planning, routing, remote);
        return new Start(remote, run, prompts.legacy(planning, routing, rejected));
    }

    Poll poll(LoopSpecCompilationRow compilation, DesignAcceptancePlanningRow planning,
              DesignerAcceptanceWorkflow.RoutingResult routing, Path projectRoot, boolean timedOut) {
        Optional<MachineCandidateSubmission.RunSnapshot> found = candidates.find(compilation.id());
        if (found.isEmpty()) return recoverUnopenedHandoff(compilation, projectRoot);
        MachineCandidateSubmission.RunSnapshot run = found.get();
        String internalServer = run.submissionChannel()
                == MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                ? runtimeAccess.current().filter(credentials -> credentials.generation()
                        .equals(run.runtimeGenerationId())).map(credentials -> credentials.serverName()).orElse(null)
                : null;
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                run.externalSessionId(), projectRoot, run.runtimeGenerationId(), internalServer);
        PollStep step = PollStep.VALIDATE;
        try {
            candidates.validate(run);
            if (run.state() == MachineCandidateRunState.ACCEPTED) {
                step = PollStep.TERMINATION;
                String proof = terminationProof(compilation, remote);
                return Poll.accepted(remote, run, proof);
            }
            if (run.state() == MachineCandidateRunState.WAITING_INPUT) {
                step = PollStep.TERMINATION;
                String proof = terminationProof(compilation, remote);
                return Poll.waiting(remote, run,
                        candidates.terminal(compilation.id()).orElseThrow(() -> new ConflictException(
                                "ACCEPTANCE_CANDIDATE_TERMINAL_MISSING",
                                "验收闭集候选运行缺少安全终态响应")), proof);
            }
            if (run.state() == MachineCandidateRunState.CLOSED) {
                step = PollStep.TERMINATION;
                String proof = terminationProof(compilation, remote);
                return run.submissionChannel() == MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                        && run.closeReason()
                        == MachineCandidateSubmission.CandidateCloseReason.NORMAL_COMPLETION_ZERO_SUBMISSION
                        ? Poll.startLegacy(remote, run, proof)
                        : closedFailure(remote, run, proof);
            }
            step = PollStep.STATUS;
            List<OpenCodeClient.PendingQuestion> questions = openCode.pendingQuestions(remote);
            if (!questions.isEmpty()) {
                questions.forEach(question -> reject(remote, question.id()));
                step = PollStep.TERMINATION;
                String proof = abortProof(remote);
                step = PollStep.CLOSE;
                MachineCandidateSubmission.RunSnapshot closed = close(compilation.id(), run,
                        MachineCandidateSubmission.CandidateCloseReason.INTERACTION_FORBIDDEN);
                return Poll.failed(remote, closed, proof, "ACCEPTANCE_CANDIDATE_INTERACTION_FORBIDDEN",
                        "验收闭集选择器不得请求模型侧交互");
            }
            if (timedOut) {
                step = PollStep.TERMINATION;
                String proof = abortProof(remote);
                step = PollStep.CLOSE;
                MachineCandidateSubmission.RunSnapshot closed = close(compilation.id(), run,
                        MachineCandidateSubmission.CandidateCloseReason.TIMEOUT);
                return Poll.failed(remote, closed, proof, "OPENCODE_ACCEPTANCE_CANDIDATE_TIMEOUT",
                        "验收闭集候选 Session 超时");
            }
            step = PollStep.STATUS;
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.retrying() || !status.completed() && !status.failed()) {
                return Poll.running(remote, run, status.state());
            }
            if (status.failed()) {
                step = PollStep.TERMINATION;
                String proof = abortProof(remote);
                step = PollStep.CLOSE;
                MachineCandidateSubmission.RunSnapshot closed = close(compilation.id(), run,
                        MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED);
                return Poll.failed(remote, closed, proof,
                        "OPENCODE_ACCEPTANCE_CANDIDATE_" + safe(status.state()),
                        status.detail());
            }
            if (run.submissionChannel() == MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP) {
                step = PollStep.CLOSE;
                MachineCandidateSubmission.RunSnapshot closed = close(compilation.id(), run,
                        MachineCandidateSubmission.CandidateCloseReason.NORMAL_COMPLETION_ZERO_SUBMISSION);
                return Poll.startLegacy(remote, closed, CandidateSessionTerminationProof.REMOTE_COMPLETED.name());
            }
            step = PollStep.STATUS;
            MachineCandidateSubmission.SubmissionResult result = candidates.submitLegacy(
                    compilation.id(), openCode.sessionOutput(remote));
            if (result.outcome() == MachineCandidateOutcome.ACCEPTED) {
                return Poll.accepted(remote, candidates.find(compilation.id()).orElseThrow(),
                        CandidateSessionTerminationProof.REMOTE_COMPLETED.name());
            }
            if (result.outcome() == MachineCandidateOutcome.WAITING_INPUT) {
                return Poll.waiting(remote, candidates.find(compilation.id()).orElseThrow(), result,
                        CandidateSessionTerminationProof.REMOTE_COMPLETED.name());
            }
            return Poll.rejected(remote, run, result,
                    prompts.legacy(planning, routing, result));
        } catch (RuntimeException failure) {
            if (run.state() == MachineCandidateRunState.ACCEPTED
                    || run.state() == MachineCandidateRunState.WAITING_INPUT
                    || run.state() == MachineCandidateRunState.CLOSED
                    || recoverableOpenFailure(step, failure)) {
                return Poll.recovering(remote, run, "DISCONNECTED",
                        recoveryCode(step, failure),
                        failure.getMessage());
            }
            return Poll.failed(remote, run, failure instanceof ConflictException conflict
                    ? conflict.code() : "OPENCODE_ACCEPTANCE_CANDIDATE_STATUS_FAILED", failure.getMessage());
        }
    }

    private static boolean recoverableOpenFailure(PollStep step, RuntimeException failure) {
        if (step != PollStep.VALIDATE) return true;
        if (!(failure instanceof ConflictException conflict)) return true;
        return conflict.code().startsWith("CANDIDATE_RUNTIME_");
    }

    private static String recoveryCode(PollStep step, RuntimeException failure) {
        if (failure instanceof ConflictException conflict) return conflict.code();
        return step == PollStep.TERMINATION
                ? "OPENCODE_ACCEPTANCE_CANDIDATE_STOP_UNCONFIRMED"
                : "OPENCODE_ACCEPTANCE_CANDIDATE_STATUS_UNCONFIRMED";
    }

    private static Poll closedFailure(OpenCodeClient.OpenCodeSession remote,
                                      MachineCandidateSubmission.RunSnapshot run, String proof) {
        String code = switch (run.closeReason()) {
            case INTERACTION_FORBIDDEN -> "ACCEPTANCE_CANDIDATE_INTERACTION_FORBIDDEN";
            case TIMEOUT -> "OPENCODE_ACCEPTANCE_CANDIDATE_TIMEOUT";
            case REMOTE_FAILED -> "OPENCODE_ACCEPTANCE_CANDIDATE_REMOTE_FAILED";
            case NORMAL_COMPLETION_ZERO_SUBMISSION, OWNER_REQUESTED -> "ACCEPTANCE_CANDIDATE_RUN_CLOSED";
            case null -> "ACCEPTANCE_CANDIDATE_CLOSE_REASON_MISSING";
        };
        return Poll.failed(remote, run, proof, code, "验收闭集候选运行已关闭，不允许切换兼容通道");
    }

    void closeQuietly(String compilationId, MachineCandidateSubmission.SubmissionChannel channel) {
        try { candidates.close(compilationId, channel); } catch (RuntimeException ignored) { }
    }

    StopResult stopUnopened(OpenCodeClient.OpenCodeSession remote) {
        try { return new StopResult(true, abortProof(remote), null, null); }
        catch (RuntimeException failure) {
            return new StopResult(false, null, recoveryCode(PollStep.TERMINATION, failure), failure.getMessage());
        }
    }

    private Poll recoverUnopenedHandoff(LoopSpecCompilationRow compilation, Path projectRoot) {
        if (!"RUNNING".equals(compilation.state())
                || !"DISCONNECTED".equals(compilation.externalSessionState())
                || !"OPENCODE_ACCEPTANCE_CANDIDATE_STOP_UNCONFIRMED".equals(compilation.lastErrorCode())
                || compilation.externalSessionId() == null || compilation.externalSessionId().isBlank()) {
            return Poll.none();
        }
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                compilation.externalSessionId(), projectRoot);
        StopResult stopped = stopUnopened(remote);
        return stopped.confirmed()
                ? Poll.startLegacyHandoff(remote, stopped.proof())
                : Poll.recovering(remote, null, "DISCONNECTED", stopped.code(), stopped.detail());
    }

    private String terminationProof(LoopSpecCompilationRow compilation,
                                    OpenCodeClient.OpenCodeSession remote) {
        if (CandidateSessionTerminationProof.persisted(compilation.externalSessionState())) {
            return compilation.externalSessionState();
        }
        OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
        return status.completed() ? CandidateSessionTerminationProof.REMOTE_COMPLETED.name()
                : abortProof(remote);
    }

    private String abortProof(OpenCodeClient.OpenCodeSession remote) {
        return CandidateSessionTerminationProof.from(
                openCode.abortWithConfirmation(remote)).name();
    }

    private MachineCandidateSubmission.RunSnapshot close(
            String compilationId, MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.CandidateCloseReason reason) {
        return candidates.close(compilationId, run.submissionChannel(), reason);
    }

    private void reject(OpenCodeClient.OpenCodeSession remote, String questionId) {
        try { openCode.rejectQuestion(remote, questionId); } catch (RuntimeException ignored) { }
    }

    private static String safe(String state) {
        return state == null ? "FAILED" : state.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase();
    }

    record Start(OpenCodeClient.OpenCodeSession remote,
                 MachineCandidateSubmission.RunSnapshot run, String prompt) { }
    private enum PollStep { VALIDATE, STATUS, TERMINATION, CLOSE }
    enum Action { NONE, RUNNING, ACCEPTED, WAITING_INPUT, START_LEGACY, START_LEGACY_HANDOFF, REJECTED, FAILED }
    record Poll(Action action, OpenCodeClient.OpenCodeSession remote,
                MachineCandidateSubmission.RunSnapshot run,
                MachineCandidateSubmission.SubmissionResult submission,
                String prompt, String state, String code, String detail) {
        String problemSummary() {
            return submission.problems().stream().map(problem -> problem.code()
                            + (problem.pointer() == null || problem.pointer().isBlank()
                            ? "" : " " + problem.pointer()) + ": " + problem.detail())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        static Poll none() { return new Poll(Action.NONE, null, null, null, null, null, null, null); }
        static Poll running(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                            String state) { return new Poll(Action.RUNNING, remote, run, null, null, state, null, null); }
        static Poll accepted(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                             String proof) {
            return new Poll(Action.ACCEPTED, remote, run, null, null, proof, null, null); }
        static Poll waiting(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                            MachineCandidateSubmission.SubmissionResult result, String proof) {
            return new Poll(Action.WAITING_INPUT, remote, run, result, null, proof, null, null); }
        static Poll startLegacy(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                                String proof) {
            return new Poll(Action.START_LEGACY, remote, run, null, null, proof, null, null); }
        static Poll startLegacyHandoff(OpenCodeClient.OpenCodeSession remote, String proof) {
            return new Poll(Action.START_LEGACY_HANDOFF, remote, null, null, null, proof, null, null); }
        static Poll recovering(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                               String state, String code, String detail) {
            return new Poll(Action.RUNNING, remote, run, null, null, state, code, detail); }
        static Poll rejected(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                             MachineCandidateSubmission.SubmissionResult result, String prompt) {
            return new Poll(Action.REJECTED, remote, run, result, prompt, null, null, null); }
        static Poll failed(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                           String code, String detail) {
            return failed(remote, run, null, code, detail); }
        static Poll failed(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                           String state, String code, String detail) {
            return new Poll(Action.FAILED, remote, run, null, null, state, code, detail); }
    }
    record StopResult(boolean confirmed, String proof, String code, String detail) { }
}
