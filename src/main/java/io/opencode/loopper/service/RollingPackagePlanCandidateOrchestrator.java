package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.TaskPackagePlanRevisionRow;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.runtime.InternalMcpCredentialProvider;
import io.opencode.loopper.runtime.InternalMcpReadiness;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Owns strict one-Session ROLLING_PACKAGE_PLAN_V1 transport without owning plan lifecycle transitions. */
@Component
final class RollingPackagePlanCandidateOrchestrator {
    static final String CONTRACT_VERSION = RollingPackagePlanCandidatePolicy.CONTRACT_VERSION;
    static final String WORKFLOW_STEP = CONTRACT_VERSION;
    static final int MAX_ATTEMPTS = RollingPackagePlanCandidatePolicy.MAX_ATTEMPTS;

    private final MachineCandidateSubmission submissions;
    private final Optional<CandidateRuntimeBindingService> bindings;
    private final OpenCodeClient openCode;
    private final InternalMcpRuntimeAccess runtime;
    private final LoopperProperties properties;

    RollingPackagePlanCandidateOrchestrator(
            MachineCandidateSubmission submissions,
            Optional<CandidateRuntimeBindingService> bindings,
            OpenCodeClient openCode,
            InternalMcpRuntimeAccess runtime,
            LoopperProperties properties) {
        this.submissions = submissions;
        this.bindings = bindings;
        this.openCode = openCode;
        this.runtime = runtime;
        this.properties = properties;
    }

    Eligibility eligibility() {
        if (!properties.getInternalCandidate().isRollingPackagePlanV1Enabled()) {
            return new Eligibility(false, "FEATURE_DISABLED", null);
        }
        InternalMcpCredentialProvider.Credentials active = runtime.current().orElse(null);
        InternalMcpReadiness readiness = runtime.readiness();
        if (active == null || readiness == null || !"CONNECTED".equals(readiness.status())
                || !active.generation().equals(readiness.generation())
                || !active.serverName().equals(readiness.serverName()) || bindings.isEmpty()) {
            return new Eligibility(false, "MCP_NOT_READY_PRE_DISPATCH", null);
        }
        return new Eligibility(true, null, active);
    }

    OpenCodeClient.OpenCodeSession create(Path projectRoot, String planRevisionId,
                                          OpenCodeClient.OpenCodeModel model) {
        Eligibility eligibility = eligibility();
        if (!eligibility.candidate()) {
            throw new ConflictException("ROLLING_PACKAGE_CANDIDATE_NOT_READY", eligibility.fallbackReason());
        }
        return openCode.createSession(projectRoot,
                "OpenCode Loopper Rolling Package Planner " + planRevisionId + " candidate (READ_ONLY)",
                model, OpenCodeClient.SessionProfile.ROLLING_PACKAGE_CANDIDATE_READ_ONLY);
    }

    Start open(TaskPackagePlanRevisionRow owner, OpenCodeClient.OpenCodeSession remote, String basePrompt) {
        if (owner == null || remote == null) {
            throw new BadRequestException("ROLLING_PACKAGE_CANDIDATE_OPEN_INVALID",
                    "滚动计划候选运行缺少拥有者或 OpenCode Session");
        }
        CandidateRuntimeBindingService.Binding binding = bindings.orElseThrow(() -> new ConflictException(
                        "CANDIDATE_RUNTIME_BINDING_UNAVAILABLE", "候选运行时绑定服务不可用"))
                .bind(remote, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        MachineCandidateSubmission.RunSnapshot run = submissions.open(new MachineCandidateSubmission.OpenCommand(
                runId(owner), MachineCandidateSubmission.CandidateScope.task(owner.taskId()),
                MachineCandidateSubmission.CandidateOwnerRef.taskPackagePlanRevision(owner.id()),
                MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1, WORKFLOW_STEP, owner.revision(), owner.version(),
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP, CONTRACT_VERSION,
                binding.runtimeGenerationId(), remote.id(), MAX_ATTEMPTS));
        String privateServer = remote.internalMcpServer();
        if (privateServer == null || privateServer.isBlank()) {
            throw new ConflictException("OPENCODE_INTERNAL_MCP_NOT_READY",
                    "滚动计划候选 Session 未绑定私有 MCP 名称");
        }
        return new Start(remote, run, prompt(basePrompt, run,
                privateServer + "_" + InternalMcpContractCatalog.TOOL_NAME));
    }

    Poll poll(TaskPackagePlanRevisionRow owner, Path projectRoot, boolean timedOut) {
        MachineCandidateSubmission.RunSnapshot run = find(owner).orElse(null);
        if (run == null) {
            return Poll.failed(null, null, "ROLLING_PACKAGE_CANDIDATE_RUN_MISSING",
                    "已选择 MCP 的滚动计划 Session 缺少候选运行绑定", null);
        }
        OpenCodeClient.OpenCodeSession remote = remote(run, projectRoot);
        try {
            bindings.orElseThrow(() -> new ConflictException("CANDIDATE_RUNTIME_BINDING_UNAVAILABLE",
                            "候选运行时绑定服务不可用"))
                    .validate(run, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
            if (!openCode.pendingQuestions(remote).isEmpty()) {
                String proof = stop(remote);
                MachineCandidateSubmission.RunSnapshot terminal = closeIfOpen(
                        run, MachineCandidateSubmission.CandidateCloseReason.INTERACTION_FORBIDDEN);
                return Poll.failed(remote, terminal, "ROLLING_PACKAGE_INTERACTION_FORBIDDEN",
                        "只读滚动计划候选不得向用户提问", proof);
            }
            if (run.state() == MachineCandidateRunState.ACCEPTED) {
                OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
                String proof = status.completed() ? CandidateSessionTerminationProof.REMOTE_COMPLETED.name()
                        : stop(remote);
                return Poll.accepted(remote, run, proof);
            }
            if (run.state() == MachineCandidateRunState.WAITING_INPUT
                    || run.state() == MachineCandidateRunState.FALLBACK_REQUIRED) {
                OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
                String proof = status.completed() ? CandidateSessionTerminationProof.REMOTE_COMPLETED.name()
                        : stop(remote);
                return Poll.failed(remote, run, "ROLLING_PACKAGE_CANDIDATE_REJECTED",
                        "滚动计划候选无法在有界机械修正内通过", proof);
            }
            if (timedOut) {
                String proof = stop(remote);
                MachineCandidateSubmission.RunSnapshot terminal = closeIfOpen(
                        run, MachineCandidateSubmission.CandidateCloseReason.TIMEOUT);
                return Poll.failed(remote, terminal, "ROLLING_PACKAGE_CANDIDATE_TIMEOUT",
                        "滚动计划候选 Session 超时并已确认停止", proof);
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.retrying() || !status.completed() && !status.failed()) {
                return Poll.running(remote, run, status.state());
            }
            if (status.failed()) {
                String proof = stop(remote);
                MachineCandidateSubmission.RunSnapshot terminal = closeIfOpen(
                        run, MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED);
                return Poll.failed(remote, terminal, "ROLLING_PACKAGE_CANDIDATE_REMOTE_FAILED",
                        status.detail() == null ? status.state() : status.detail(), proof);
            }
            String proof = CandidateSessionTerminationProof.REMOTE_COMPLETED.name();
            MachineCandidateSubmission.RunSnapshot terminal = closeIfOpen(
                    run, MachineCandidateSubmission.CandidateCloseReason.NORMAL_COMPLETION_ZERO_SUBMISSION);
            return Poll.failed(remote, terminal, "ROLLING_PACKAGE_ZERO_SUBMISSION",
                    "滚动计划模型正常结束但没有提交结构化候选", proof);
        } catch (RuntimeException failure) {
            return Poll.disconnected(remote, run, code(failure), failure.getMessage());
        }
    }

    Poll terminateAfterDispatchFailure(TaskPackagePlanRevisionRow owner, Path projectRoot,
                                       String reasonCode, String detail) {
        MachineCandidateSubmission.RunSnapshot run = find(owner).orElse(null);
        OpenCodeClient.OpenCodeSession remote;
        if (run != null) {
            remote = remote(run, projectRoot);
        } else if (owner != null && owner.externalSessionId() != null
                && !owner.externalSessionId().isBlank()) {
            remote = new OpenCodeClient.OpenCodeSession(owner.externalSessionId(), projectRoot);
        } else {
            return Poll.disconnected(null, null, reasonCode, detail);
        }
        try {
            String proof = stop(remote);
            MachineCandidateSubmission.RunSnapshot current = find(owner).orElse(run);
            if (current != null && current.state() == MachineCandidateRunState.ACCEPTED) {
                return Poll.accepted(remote, current, proof);
            }
            MachineCandidateSubmission.RunSnapshot terminal = current == null ? null : closeIfOpen(
                    current, MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);
            return Poll.failed(remote, terminal, reasonCode, detail, proof);
        } catch (RuntimeException failure) {
            return Poll.disconnected(remote, run, code(failure), failure.getMessage());
        }
    }

    Optional<MachineCandidateSubmission.RunSnapshot> find(TaskPackagePlanRevisionRow owner) {
        return owner == null ? Optional.empty() : submissions.find(runId(owner));
    }

    String runId(TaskPackagePlanRevisionRow owner) {
        if (owner == null || owner.id() == null || owner.id().isBlank()) {
            throw new IllegalArgumentException("Rolling package plan owner is required");
        }
        return UUID.nameUUIDFromBytes(("rolling-package-plan-candidate:" + owner.id() + ":"
                + owner.revision() + ":INTERNAL_MCP").getBytes(StandardCharsets.UTF_8)).toString();
    }

    private OpenCodeClient.OpenCodeSession remote(MachineCandidateSubmission.RunSnapshot run, Path projectRoot) {
        String serverName = runtime.current()
                .filter(active -> active.generation().equals(run.runtimeGenerationId()))
                .map(InternalMcpCredentialProvider.Credentials::serverName).orElse(null);
        return new OpenCodeClient.OpenCodeSession(
                run.externalSessionId(), projectRoot, run.runtimeGenerationId(), serverName);
    }

    private MachineCandidateSubmission.RunSnapshot closeIfOpen(
            MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.CandidateCloseReason reason) {
        return run.state() == MachineCandidateRunState.OPEN
                ? submissions.close(new MachineCandidateSubmission.CloseCommand(run.runId(), run.version(), reason))
                : run;
    }

    private String stop(OpenCodeClient.OpenCodeSession remote) {
        return CandidateSessionTerminationProof.from(openCode.abortWithConfirmation(remote)).name();
    }

    private String prompt(String basePrompt, MachineCandidateSubmission.RunSnapshot run, String toolName) {
        return (basePrompt == null ? "" : basePrompt) + """


                ROLLING_PACKAGE_PLAN_V1 PRIVATE SUBMISSION CONTRACT:
                In this same planner turn, call the exact private tool `%s` with one complete replacement object.
                Submit only package-local keys and frozen closed-set references in this exact shape:
                {"packages":[{"packageKey":"WP-2","title":"标题","objective":"目标",
                "replaces":["WP-2"],"dependencies":["WP-1"],"requirementRefs":[]}]}
                Do not add fields. Server compilation owns stable identity, lifecycle, execution and impact.

                runId: %s
                expectedSubmissionRevision: %d
                You may call the same tool at most three times in this Session. On REJECTED, replace the whole
                candidate and retry only with the returned bounded code, JSON Pointer, allowed values and returned
                submissionRevision. On ACCEPTED, stop; final assistant text is ignored. On WAITING_INPUT or
                FALLBACK_REQUIRED, stop. Never return a legacy payload instead of calling the private tool.
                """.formatted(toolName, run.runId(), run.version());
    }

    private String code(RuntimeException failure) {
        return failure instanceof ConflictException conflict ? conflict.code()
                : failure instanceof SessionFailure session ? session.code()
                : "ROLLING_PACKAGE_CANDIDATE_TRANSPORT_FAILED";
    }

    record Eligibility(boolean candidate, String fallbackReason,
                       InternalMcpCredentialProvider.Credentials credentials) { }
    record Start(OpenCodeClient.OpenCodeSession remote,
                 MachineCandidateSubmission.RunSnapshot run, String prompt) { }
    enum Action { RUNNING, ACCEPTED, FAILED, DISCONNECTED }
    record Poll(Action action, OpenCodeClient.OpenCodeSession remote,
                MachineCandidateSubmission.RunSnapshot run, String state,
                String reasonCode, String detail, String terminationProof) {
        static Poll running(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                            String state) {
            return new Poll(Action.RUNNING, remote, run, state, null, null, null);
        }
        static Poll accepted(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                             String proof) {
            return new Poll(Action.ACCEPTED, remote, run, null, null, null, proof);
        }
        static Poll failed(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                           String code, String detail, String proof) {
            return new Poll(Action.FAILED, remote, run, null, code, detail, proof);
        }
        static Poll disconnected(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                                 String code, String detail) {
            return new Poll(Action.DISCONNECTED, remote, run, null, code, detail, null);
        }
    }
}
