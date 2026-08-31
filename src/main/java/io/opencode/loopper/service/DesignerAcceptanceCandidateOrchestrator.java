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
        if (found.isEmpty()) return Poll.none();
        MachineCandidateSubmission.RunSnapshot run = found.get();
        String internalServer = run.submissionChannel()
                == MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                ? runtimeAccess.current().filter(credentials -> credentials.generation()
                        .equals(run.runtimeGenerationId())).map(credentials -> credentials.serverName()).orElse(null)
                : null;
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                run.externalSessionId(), projectRoot, run.runtimeGenerationId(), internalServer);
        try {
            if (run.state() == MachineCandidateRunState.ACCEPTED) return Poll.accepted(remote, run);
            if (run.state() == MachineCandidateRunState.WAITING_INPUT) return Poll.waiting(remote, run,
                    candidates.terminal(compilation.id()).orElseThrow(() -> new ConflictException(
                            "ACCEPTANCE_CANDIDATE_TERMINAL_MISSING",
                            "验收闭集候选运行缺少安全终态响应")));
            if (run.state() == MachineCandidateRunState.CLOSED) {
                return run.submissionChannel() == MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                        ? Poll.startLegacy(remote, run)
                        : Poll.failed(remote, run, "ACCEPTANCE_CANDIDATE_RUN_CLOSED",
                        "进程内兼容候选运行已关闭，未恢复旧代次");
            }
            candidates.validate(run);
            List<OpenCodeClient.PendingQuestion> questions = openCode.pendingQuestions(remote);
            if (!questions.isEmpty()) {
                questions.forEach(question -> reject(remote, question.id()));
                terminateAndClose(compilation.id(), run, remote);
                return Poll.failed(remote, run, "ACCEPTANCE_CANDIDATE_INTERACTION_FORBIDDEN",
                        "验收闭集选择器不得请求模型侧交互");
            }
            if (timedOut) {
                terminateAndClose(compilation.id(), run, remote);
                return Poll.failed(remote, run, "OPENCODE_ACCEPTANCE_CANDIDATE_TIMEOUT",
                        "验收闭集候选 Session 超时");
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.retrying() || !status.completed() && !status.failed()) {
                return Poll.running(remote, run, status.state());
            }
            if (status.failed()) {
                closeQuietly(compilation.id(), run.submissionChannel());
                return Poll.failed(remote, run, "OPENCODE_ACCEPTANCE_CANDIDATE_" + safe(status.state()),
                        status.detail());
            }
            if (run.submissionChannel() == MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP) {
                terminateAndClose(compilation.id(), run, remote);
                return Poll.startLegacy(remote, run);
            }
            MachineCandidateSubmission.SubmissionResult result = candidates.submitLegacy(
                    compilation.id(), openCode.sessionOutput(remote));
            if (result.outcome() == MachineCandidateOutcome.ACCEPTED) {
                return Poll.accepted(remote, candidates.find(compilation.id()).orElseThrow());
            }
            if (result.outcome() == MachineCandidateOutcome.WAITING_INPUT) {
                return Poll.waiting(remote, candidates.find(compilation.id()).orElseThrow(), result);
            }
            return Poll.rejected(remote, run, result,
                    prompts.legacy(planning, routing, result));
        } catch (RuntimeException failure) {
            return Poll.failed(remote, run, failure instanceof ConflictException conflict
                    ? conflict.code() : "OPENCODE_ACCEPTANCE_CANDIDATE_STATUS_FAILED", failure.getMessage());
        }
    }

    void closeQuietly(String compilationId, MachineCandidateSubmission.SubmissionChannel channel) {
        try { candidates.close(compilationId, channel); } catch (RuntimeException ignored) { }
    }

    private void terminateAndClose(String compilationId, MachineCandidateSubmission.RunSnapshot run,
                                   OpenCodeClient.OpenCodeSession remote) {
        openCode.abortWithConfirmation(remote);
        candidates.close(compilationId, run.submissionChannel());
    }

    private void reject(OpenCodeClient.OpenCodeSession remote, String questionId) {
        try { openCode.rejectQuestion(remote, questionId); } catch (RuntimeException ignored) { }
    }

    private static String safe(String state) {
        return state == null ? "FAILED" : state.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase();
    }

    record Start(OpenCodeClient.OpenCodeSession remote,
                 MachineCandidateSubmission.RunSnapshot run, String prompt) { }
    enum Action { NONE, RUNNING, ACCEPTED, WAITING_INPUT, START_LEGACY, REJECTED, FAILED }
    record Poll(Action action, OpenCodeClient.OpenCodeSession remote,
                MachineCandidateSubmission.RunSnapshot run,
                MachineCandidateSubmission.SubmissionResult submission,
                String prompt, String state, String code, String detail) {
        static Poll none() { return new Poll(Action.NONE, null, null, null, null, null, null, null); }
        static Poll running(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                            String state) { return new Poll(Action.RUNNING, remote, run, null, null, state, null, null); }
        static Poll accepted(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run) {
            return new Poll(Action.ACCEPTED, remote, run, null, null, null, null, null); }
        static Poll waiting(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                            MachineCandidateSubmission.SubmissionResult result) {
            return new Poll(Action.WAITING_INPUT, remote, run, result, null, null, null, null); }
        static Poll startLegacy(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run) {
            return new Poll(Action.START_LEGACY, remote, run, null, null, null, null, null); }
        static Poll rejected(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                             MachineCandidateSubmission.SubmissionResult result, String prompt) {
            return new Poll(Action.REJECTED, remote, run, result, prompt, null, null, null); }
        static Poll failed(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                           String code, String detail) {
            return new Poll(Action.FAILED, remote, run, null, null, null, code, detail); }
    }
}
