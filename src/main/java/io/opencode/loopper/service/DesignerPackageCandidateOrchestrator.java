package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
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

/** Owns the one-Session PACKAGE_DESIGN_V1 transport without owning Designer persistence transitions. */
@Component
final class DesignerPackageCandidateOrchestrator {
    static final String CONTRACT_VERSION = "PACKAGE_DESIGN_V1";
    static final String WORKFLOW_STEP = CONTRACT_VERSION;
    static final int MAX_ATTEMPTS = 3;

    private final MachineCandidateSubmission submissions;
    private final Optional<CandidateRuntimeBindingService> bindings;
    private final OpenCodeClient openCode;
    private final InternalMcpRuntimeAccess runtime;
    private final LoopperProperties properties;
    private io.opencode.loopper.persistence.LoopperMapper conversationMapper;
    private DesignerConversationCoordinator conversations;

    @org.springframework.beans.factory.annotation.Autowired
    DesignerPackageCandidateOrchestrator(MachineCandidateSubmission submissions,
            Optional<CandidateRuntimeBindingService> bindings, OpenCodeClient openCode,
            InternalMcpRuntimeAccess runtime, LoopperProperties properties,
            io.opencode.loopper.persistence.LoopperMapper mapper, DesignerConversationCoordinator conversations) {
        this(submissions, bindings, openCode, runtime, properties);
        this.conversationMapper = mapper; this.conversations = conversations;
    }

    DesignerPackageCandidateOrchestrator(
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
        if (!properties.getInternalCandidate().isPackageDesignV1Enabled()) {
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

    OpenCodeClient.OpenCodeSession create(
            Path projectRoot, String packageId, OpenCodeClient.OpenCodeModel model, boolean interactive) {
        Eligibility eligibility = eligibility();
        if (!eligibility.candidate()) {
            throw new ConflictException("PACKAGE_DESIGN_CANDIDATE_NOT_READY", eligibility.fallbackReason());
        }
        return openCode.createSession(projectRoot,
                "OpenCode Loopper package Designer " + packageId + " candidate (READ_ONLY)", model, interactive
                        ? OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY
                        : OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY);
    }

    Start open(DesignWorkPackageRow workPackage, OpenCodeClient.OpenCodeSession remote, String basePrompt) {
        if (workPackage == null || remote == null) {
            throw new BadRequestException("PACKAGE_DESIGN_CANDIDATE_OPEN_INVALID",
                    "工作包候选运行缺少拥有者或 OpenCode Session");
        }
        CandidateRuntimeBindingService.Binding binding = bindings.orElseThrow(() -> new ConflictException(
                        "CANDIDATE_RUNTIME_BINDING_UNAVAILABLE", "候选运行时绑定服务不可用"))
                .bind(remote, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        MachineCandidateSubmission.RunSnapshot run = submissions.open(new MachineCandidateSubmission.OpenCommand(
                runId(workPackage), MachineCandidateSubmission.CandidateScope.designerSession(
                        workPackage.designerSessionId()),
                MachineCandidateSubmission.CandidateOwnerRef.designWorkPackage(workPackage.id()),
                MachineCandidateKind.PACKAGE_DESIGN_V1, WORKFLOW_STEP, workPackage.designRevision() + 1L,
                workPackage.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                CONTRACT_VERSION, binding.runtimeGenerationId(), remote.id(), MAX_ATTEMPTS));
        String privateServer = remote.internalMcpServer();
        if (privateServer == null || privateServer.isBlank()) {
            throw new ConflictException("OPENCODE_INTERNAL_MCP_NOT_READY",
                    "工作包候选 Session 未绑定私有 MCP 名称");
        }
        return new Start(remote, run, prompt(basePrompt, run,
                privateServer + "_" + InternalMcpContractCatalog.TOOL_NAME));
    }

    Poll poll(DesignWorkPackageRow workPackage, Path projectRoot, boolean timedOut) {
        Optional<MachineCandidateSubmission.RunSnapshot> found = find(workPackage);
        if (found.isEmpty()) {
            return Poll.failed(null, null, "PACKAGE_DESIGN_CANDIDATE_RUN_MISSING",
                    "已调度的工作包候选 Session 缺少候选运行绑定");
        }
        MachineCandidateSubmission.RunSnapshot run = found.get();
        String serverName = runtime.current()
                .filter(active -> active.generation().equals(run.runtimeGenerationId()))
                .map(InternalMcpCredentialProvider.Credentials::serverName).orElse(null);
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                run.externalSessionId(), projectRoot, run.runtimeGenerationId(), serverName);
        try {
            if (conversations != null) remote = conversations.remote(remote.id(), projectRoot);
            bindings.orElseThrow(() -> new ConflictException("CANDIDATE_RUNTIME_BINDING_UNAVAILABLE",
                            "候选运行时绑定服务不可用"))
                    .validate(run, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
            if (run.state() == MachineCandidateRunState.WAITING_INPUT) {
                OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
                if (!status.completed()) openCode.abortWithConfirmation(remote);
                if (conversations != null) conversations.settle(remote.id());
                return Poll.waiting(remote, run, submissions.terminal(run.runId()).orElse(null));
            }
            if (run.state() == MachineCandidateRunState.ACCEPTED) {
                OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
                if (!status.completed()) openCode.abortWithConfirmation(remote);
                if (conversations != null) conversations.settle(remote.id());
                return Poll.accepted(remote, run);
            }
            if (timedOut) {
                openCode.abortWithConfirmation(remote);
                close(run);
                return Poll.failed(remote, run, "OPENCODE_PACKAGE_DESIGN_CANDIDATE_TIMEOUT",
                        "工作包候选 Session 已超时并确认停止");
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.retrying() || !status.completed() && !status.failed()) {
                return Poll.running(remote, run, status.state());
            }
            if (status.failed()) {
                close(run);
                return Poll.failed(remote, run, "OPENCODE_PACKAGE_DESIGN_CANDIDATE_" + safe(status.state()),
                        status.detail());
            }
            if (conversations != null) conversations.settle(remote.id());
            if (run.state() == MachineCandidateRunState.FALLBACK_REQUIRED
                    || run.state() == MachineCandidateRunState.CLOSED) {
                return markdownFallback(remote, run, run.state() == MachineCandidateRunState.FALLBACK_REQUIRED
                        ? "MECHANICAL_REJECTIONS_EXHAUSTED"
                        : run.attemptsUsed() == 0 ? "MODEL_COMPLETED_WITHOUT_SUBMISSION"
                        : "MODEL_COMPLETED_AFTER_MECHANICAL_REJECTION");
            }
            if (run.state() != MachineCandidateRunState.OPEN) {
                return Poll.failed(remote, run, "PACKAGE_DESIGN_CANDIDATE_STATE_INVALID",
                        "工作包候选运行进入未知终态");
            }
            MachineCandidateSubmission.RunSnapshot closed = close(run);
            return markdownFallback(remote, closed, run.attemptsUsed() == 0
                    ? "MODEL_COMPLETED_WITHOUT_SUBMISSION"
                    : "MODEL_COMPLETED_AFTER_MECHANICAL_REJECTION");
        } catch (RuntimeException failure) {
            String code = failure instanceof ConflictException conflict ? conflict.code()
                    : failure instanceof SessionFailure session ? session.code()
                    : "OPENCODE_PACKAGE_DESIGN_CANDIDATE_STATUS_FAILED";
            return Poll.failed(remote, run, code, failure.getMessage());
        }
    }

    String runId(DesignWorkPackageRow workPackage) {
        if (workPackage == null || workPackage.id() == null || workPackage.id().isBlank()) {
            throw new IllegalArgumentException("Work package is required");
        }
        if (conversationMapper != null && workPackage.designerExternalSessionId() != null) {
            var turn = conversationMapper.designerTurnForRemote(workPackage.designerExternalSessionId());
            if (turn.isPresent()) return turn.get().candidateRunId();
        }
        return UUID.nameUUIDFromBytes(("package-design-candidate:" + workPackage.id() + ":"
                + (workPackage.designRevision() + 1L) + ":INTERNAL_MCP")
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    Optional<MachineCandidateSubmission.RunSnapshot> find(DesignWorkPackageRow workPackage) {
        return submissions.find(runId(workPackage));
    }

    void closeQuietly(DesignWorkPackageRow workPackage) {
        find(workPackage).ifPresent(run -> {
            try { close(run); } catch (RuntimeException ignored) { }
        });
    }

    private MachineCandidateSubmission.RunSnapshot close(MachineCandidateSubmission.RunSnapshot run) {
        return submissions.close(new MachineCandidateSubmission.CloseCommand(run.runId(), run.version()));
    }

    private Poll markdownFallback(OpenCodeClient.OpenCodeSession remote,
                                  MachineCandidateSubmission.RunSnapshot run, String reason) {
        String markdown = io.opencode.loopper.runtime.OpenCodeStepLimitNotice.requireBusinessOutput(openCode.sessionOutput(remote));
        if (markdown == null || markdown.isBlank()) {
            return Poll.failed(remote, run, "DESIGN_OUTPUT_MISSING",
                    "工作包候选 Session 正常完成但没有可供兜底的 Markdown");
        }
        return Poll.markdown(remote, run, markdown, reason);
    }

    private String prompt(String basePrompt, MachineCandidateSubmission.RunSnapshot run, String toolName) {
        return (basePrompt == null ? "" : basePrompt) + """


                PACKAGE_DESIGN_V1 PRIVATE SUBMISSION CONTRACT:
                In this same Designer turn, prefer calling the exact private tool `%s`. Submit one complete
                replacement object with contractVersion=PACKAGE_DESIGN_V1; outcome READY or NEEDS_INPUT;
                requirements, scenarios, deliverables, reviews, stages, and closed gapCodes. Use only candidate-local
                keys and references. Never submit commands, writable-path allowlists, test commands/targets, verifier
                objects, permission conclusions, or stable server IDs. The server remains authoritative for all of
                those fields. On the private submission path, this object replaces the earlier Markdown output
                format; the design semantics still apply. An explicit frozen Markdown-only request takes priority.

                %s

                If the frozen original requirement explicitly requests Markdown-only or no private submission, you
                must respect that choice and do not call the private tool. Return the complete controlled Markdown
                design as the final response instead. This is the supported zero-submission fallback route.

                runId: %s
                expectedSubmissionRevision: %d
                MCP submissions have no count limit. On REJECTED, read only the returned
                bounded code, JSON Pointer, and allowed values, then replace the entire candidate and retry with the
                returned submissionRevision. On ACCEPTED, stop: final assistant text is ignored. On WAITING_INPUT,
                stop and wait for the user. On FALLBACK_REQUIRED, produce the complete controlled Markdown design
                required above as your final response; do not call another tool. If you choose not to call the tool,
                your final response must still be that complete controlled Markdown design.
                """.formatted(toolName, PackageDesignCandidatePromptContract.instructions(), run.runId(), run.version());
    }

    private static String safe(String value) {
        return value == null ? "FAILED" : value.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase();
    }

    record Eligibility(boolean candidate, String fallbackReason,
                       InternalMcpCredentialProvider.Credentials credentials) { }
    record Start(OpenCodeClient.OpenCodeSession remote,
                 MachineCandidateSubmission.RunSnapshot run, String prompt) { }
    enum Action { RUNNING, ACCEPTED, MARKDOWN_FALLBACK, WAITING_INPUT, FAILED }
    record Poll(Action action, OpenCodeClient.OpenCodeSession remote,
                MachineCandidateSubmission.RunSnapshot run,
                MachineCandidateSubmission.SubmissionResult submission,
                String markdown, String state, String reasonCode, String detail) {
        static Poll running(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                            String state) {
            return new Poll(Action.RUNNING, remote, run, null, null, state, null, null);
        }
        static Poll accepted(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run) {
            return new Poll(Action.ACCEPTED, remote, run, null, null, null, null, null);
        }
        static Poll markdown(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                             String markdown, String reason) {
            return new Poll(Action.MARKDOWN_FALLBACK, remote, run, null, markdown, null, reason, null);
        }
        static Poll waiting(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                            MachineCandidateSubmission.SubmissionResult result) {
            return new Poll(Action.WAITING_INPUT, remote, run, result, null, null, "PACKAGE_DESIGN_NEEDS_INPUT", null);
        }
        static Poll failed(OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                           String code, String detail) {
            return new Poll(Action.FAILED, remote, run, null, null, null, code, detail);
        }
    }
}
