package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignWorkPackageState;
import io.opencode.loopper.domain.DesignWorkflowPhase;
import io.opencode.loopper.domain.DesignerActor;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.LoopSpecCompilationState;
import io.opencode.loopper.domain.ModelResponseMode;
import io.opencode.loopper.domain.StructuredModelStep;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignDiscussionRevisionRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.PackageDesignAcceptedResultRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Settles package-design candidate facts through the existing Designer state machine. */
final class DesignerPackageCandidateWorkflow {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final OpenCodeClient openCode;
    private final DesignerQuestionSupport questionSupport;
    private final DesignerPackageCandidateOrchestrator candidateRuns;

    DesignerPackageCandidateWorkflow(
            LoopperMapper mapper, LifecycleTransitionService lifecycle, ObjectMapper json,
            OpenCodeClient openCode, DesignerQuestionSupport questionSupport,
            DesignerPackageCandidateOrchestrator candidateRuns) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.json = json;
        this.openCode = openCode;
        this.questionSupport = questionSupport;
        this.candidateRuns = candidateRuns;
    }

    void recover(DesignerSessionService host) {
        for (PackageDesignAcceptedResultRow accepted : mapper.listUnsettledPackageDesignAcceptedResults()) {
            try {
                DesignWorkPackageRow workPackage = host.getWorkPackage(accepted.designWorkPackageId());
                // DESIGNING still needs remote termination proof. COMPILING proves that boundary was crossed.
                if (!DesignWorkPackageState.COMPILING.name().equals(workPackage.state())
                        || workPackage.designRevision() != accepted.sourceRevision()) continue;
                DesignerSessionRow session = host.get(workPackage.designerSessionId());
                DesignRequirementRevisionRow revision = host.getRequirement(workPackage.requirementRevisionId());
                DesignDiscussionRevisionRow discussion = mapper.findLatestDesignDiscussionRevision(
                        session.id(), workPackage.packageId()).orElseThrow();
                settle(host, accepted, workPackage, session, revision, discussion);
            } catch (RuntimeException ignoredConcurrentOrStaleSettlement) { }
        }
    }

    void handle(
            DesignerSessionService host, DesignWorkPackageRow workPackage, DesignerSessionRow session,
            DesignRequirementRevisionRow revision, DesignDiscussionRevisionRow discussion,
            DesignerPackageCandidateOrchestrator.Poll polled) {
        switch (polled.action()) {
            case RUNNING -> {
                DesignerSessionRow current = host.get(session.id());
                if (!same(current.externalSessionState(), polled.state())) {
                    current = host.updateDesignerProjection(current, DesignerSessionState.RUNNING,
                            DesignWorkflowPhase.valueOf(current.workflowPhase()), polled.remote().id(),
                            polled.state(), current.designRevision(), current.redesignCount(),
                            revision.revision(), workPackage.packageId());
                }
                host.publish(current, "PARTIAL", DesignerActor.DESIGNER, true,
                        questionSupport.markdown(openCode.sessionLiveOutput(polled.remote())),
                        workPackage.packageId() + " 正在同一候选 Session 中生成并校验 PACKAGE_DESIGN_V1");
            }
            case ACCEPTED -> {
                PackageDesignAcceptedResultRow accepted = mapper
                        .findPackageDesignAcceptedResult(polled.run().runId())
                        .orElseThrow(() -> new ConflictException("PACKAGE_DESIGN_ACCEPTED_RESULT_MISSING",
                                "已接受工作包候选缺少原子冻结结果"));
                settle(host, accepted, workPackage, session, revision, discussion);
            }
            case MARKDOWN_FALLBACK -> completeMarkdown(host, session, revision, workPackage, discussion,
                    polled.remote(), polled.markdown(), polled.reasonCode());
            case WAITING_INPUT -> {
                String detail = polled.submission() == null || polled.submission().problems().isEmpty()
                        ? "工作包设计候选明确需要补充需求信息" : candidateProblems(polled.submission());
                DesignWorkPackageRow waiting = host.updateWorkPackage(workPackage,
                        DesignWorkPackageState.WAITING_INPUT, polled.remote().id(), "WAITING_INPUT",
                        workPackage.designMessageId(), workPackage.designRevision(), workPackage.redesignCount(),
                        workPackage.designerTransportRetryCount(), workPackage.compilerSummary(),
                        workPackage.handoffSummary(), "PACKAGE_DESIGN_NEEDS_INPUT", safeMessage(detail));
                host.waitForDesignInput(session, revision, waiting, "PACKAGE_DESIGN_NEEDS_INPUT", detail);
            }
            case FAILED -> host.failPackageDesigner(
                    workPackage, session, polled.reasonCode(), polled.detail(), false);
        }
    }

    void completeMarkdown(
            DesignerSessionService host, DesignerSessionRow session, DesignRequirementRevisionRow revision,
            DesignWorkPackageRow workPackage, DesignDiscussionRevisionRow discussion,
            OpenCodeClient.OpenCodeSession remote, String markdown, String fallbackReason) {
        if (blank(markdown)) {
            host.failPackageDesigner(workPackage, session, "DESIGN_OUTPUT_MISSING",
                    "Package Designer completed without Markdown", false);
            return;
        }
        DesignerMessageRow source = host.appendMessage(session.id(), DesignerActor.DESIGNER, markdown,
                "PERSISTED", revision.revision(), workPackage.packageId());
        host.updateDiscussion(discussion, "COMPILING", discussion.sourceMessageId(), source.id(), markdown,
                discussion.decisionLogJson(), true, discussion.questionRetryCount(), null, null, null);
        DesignWorkPackageRow compiling = host.updateWorkPackage(workPackage, DesignWorkPackageState.COMPILING,
                remote.id(), "COMPLETED", source.id(), workPackage.designRevision() + 1,
                workPackage.redesignCount(), workPackage.designerTransportRetryCount(), null, null, null, null);
        DesignerSessionRow compilerSession = host.updateDesignerProjection(host.get(session.id()),
                DesignerSessionState.RUNNING, DesignWorkflowPhase.COMPILING, remote.id(), "COMPLETED",
                session.designRevision() + 1, workPackage.redesignCount(), revision.revision(),
                workPackage.packageId());
        host.publish(compilerSession, "STATUS", DesignerActor.COMPILER, true, "",
                workPackage.packageId() + " 已使用 Markdown 兜底冻结设计稿（" + fallbackReason
                        + "），正在沿用现有规范编译路线");
        host.startCompilation(compilerSession, compiling, source, "MARKDOWN_FALLBACK", fallbackReason);
    }

    void failHandoff(
            DesignerSessionService host, DesignWorkPackageRow workPackage, DesignerSessionRow session,
            String code, String detail, boolean legacyTransportRetry) {
        boolean candidateRun = candidateRuns.find(workPackage).isPresent();
        if (candidateRun) candidateRuns.closeQuietly(workPackage);
        host.failPackageDesigner(workPackage, session, code, detail, legacyTransportRetry && !candidateRun);
    }

    private void settle(
            DesignerSessionService host, PackageDesignAcceptedResultRow accepted, DesignWorkPackageRow input,
            DesignerSessionRow inputSession, DesignRequirementRevisionRow revision,
            DesignDiscussionRevisionRow inputDiscussion) {
        DesignWorkPackageRow workPackage = host.getWorkPackage(input.id());
        DesignerSessionRow session = host.get(inputSession.id());
        if (!accepted.designWorkPackageId().equals(workPackage.id())
                || !DesignerPackageCandidateOrchestrator.CONTRACT_VERSION.equals(accepted.contractVersion())
                || accepted.sourceRevision() < 1
                || accepted.sourceRevision() != workPackage.designRevision()
                && accepted.sourceRevision() != workPackage.designRevision() + 1L) {
            throw new ConflictException("PACKAGE_DESIGN_ACCEPTED_RESULT_STALE",
                    "已接受工作包候选与当前工作包修订不一致");
        }
        DesignerSemanticContracts.PackageCompilationPlanEnvelope plan;
        try {
            plan = json.readValue(accepted.compiledResultJson(),
                    DesignerSemanticContracts.PackageCompilationPlanEnvelope.class).normalized();
        } catch (JacksonException invalid) {
            throw new ConflictException("PACKAGE_DESIGN_ACCEPTED_RESULT_INVALID",
                    "已接受工作包候选的确定性编译结果无法读取");
        }
        DesignerMessageRow source = designMessage(host, session, revision, workPackage, accepted);
        DesignDiscussionRevisionRow discussion = mapper.findLatestDesignDiscussionRevision(
                session.id(), workPackage.packageId()).orElse(inputDiscussion);
        if (!"COMPILING".equals(discussion.state()) || !source.id().equals(discussion.designMessageId())) {
            host.updateDiscussion(discussion, "COMPILING", discussion.sourceMessageId(), source.id(),
                    accepted.canonicalMarkdown(), discussion.decisionLogJson(), true,
                    discussion.questionRetryCount(), discussion.candidateCompilationId(), null, null);
        }
        if (workPackage.designRevision() < accepted.sourceRevision()) {
            if (workPackage.version() != accepted.ownerVersion()) {
                throw new ConflictException("PACKAGE_DESIGN_ACCEPTED_OWNER_STALE",
                        "工作包在候选接受后已被并发修订");
            }
            workPackage = host.updateWorkPackage(workPackage, DesignWorkPackageState.COMPILING,
                    workPackage.designerExternalSessionId(), "CANDIDATE_ACCEPTED", source.id(),
                    Math.toIntExact(accepted.sourceRevision()), workPackage.redesignCount(),
                    workPackage.designerTransportRetryCount(), null, null, null, null);
        } else if (!source.id().equals(workPackage.designMessageId())) {
            throw new ConflictException("PACKAGE_DESIGN_ACCEPTED_SOURCE_CONFLICT",
                    "工作包已绑定到不同的设计稿来源");
        }
        if (!DesignWorkflowPhase.COMPILING.name().equals(session.workflowPhase())
                || !workPackage.packageId().equals(session.activeWorkPackageId())) {
            session = host.updateDesignerProjection(session, DesignerSessionState.RUNNING,
                    DesignWorkflowPhase.COMPILING, workPackage.designerExternalSessionId(),
                    "CANDIDATE_ACCEPTED", Math.max(session.designRevision(), workPackage.designRevision()),
                    workPackage.redesignCount(), revision.revision(), workPackage.packageId());
        }
        LoopSpecCompilationRow compilation = mapper.findLoopSpecCompilationForPackageRevision(
                session.id(), workPackage.packageId(), Math.toIntExact(accepted.sourceRevision())).orElse(null);
        boolean created = compilation == null;
        if (created) {
            String now = now();
            compilation = new LoopSpecCompilationRow(
                    UUID.randomUUID().toString(), session.id(), Math.toIntExact(accepted.sourceRevision()),
                    LoopSpecCompilationState.PENDING_HANDOFF.name(), null, "SERVER_DIRECT", 0,
                    source.id(), revision.sourceDraftVersion(), null, null, now, now, 0,
                    workPackage.packageId(), 0, null, StructuredModelStep.SERVER_COMPILING.name(),
                    accepted.compiledResultJson(), 0, ModelResponseMode.TEXT_MARKER.name(), null, false,
                    ModelResponseMode.TEXT_MARKER.name(), null, false, accepted.compiledResultJson(),
                    0, 0, true, "MCP_ACCEPTED", null);
            LoopSpecCompilationRow pending = compilation;
            lifecycle.create(compilationSubject(pending, session.projectId()), pending.state(),
                    Map.of("workPackageId", workPackage.packageId(), "compilationSource", "MCP_ACCEPTED"),
                    () -> mapper.insertLoopSpecCompilation(pending),
                    () -> new ConflictException("LOOPSPEC_COMPILATION_CREATE_CONFLICT",
                            "MCP 工作包编译记录无法创建"));
            compilation = host.getCompilation(pending.id());
        } else if (!"MCP_ACCEPTED".equals(compilation.compilationSource())) {
            throw new ConflictException("PACKAGE_DESIGN_COMPILATION_SOURCE_CONFLICT",
                    "当前工作包修订已由另一编译来源占用");
        }
        if (accepted.settledCompilationId() == null) {
            int settled = mapper.settlePackageDesignAcceptedResult(
                    accepted.candidateRunId(), accepted.version(), compilation.id(), now());
            if (settled != 1) {
                PackageDesignAcceptedResultRow current = mapper
                        .findPackageDesignAcceptedResult(accepted.candidateRunId()).orElseThrow();
                if (!compilation.id().equals(current.settledCompilationId())) {
                    throw new ConflictException("PACKAGE_DESIGN_ACCEPTED_SETTLEMENT_CONFLICT",
                            "已接受工作包候选被并发绑定到另一编译记录");
                }
            }
        } else if (!compilation.id().equals(accepted.settledCompilationId())) {
            throw new ConflictException("PACKAGE_DESIGN_ACCEPTED_SETTLEMENT_CONFLICT",
                    "已接受工作包候选绑定到另一编译记录");
        }
        if (LoopSpecCompilationState.COMPLETED.name().equals(compilation.state())) return;
        if (LoopSpecCompilationState.PENDING_HANDOFF.name().equals(compilation.state())) {
            compilation = host.updateCompilation(compilation, LoopSpecCompilationState.RUNNING,
                    null, "SERVER_DIRECT", 0, null, null, session.projectId(), null,
                    StructuredModelStep.SERVER_COMPILING, accepted.compiledResultJson());
        }
        if (created) {
            host.appendMessage(session.id(), DesignerActor.VALIDATOR,
                    workPackage.packageId() + " PACKAGE_DESIGN_V1 已接受；最终文本已忽略，服务端正在确定性编译。",
                    "NORMALIZED", revision.revision(), workPackage.packageId());
        }
        host.publish(session, "STATUS", DesignerActor.COMPILER, true, "",
                workPackage.packageId() + " MCP 候选已冻结，未创建独立规范工程师 Session");
        host.handlePackageCompilationEnvelope(
                compilation, session, null, host.compilePackagePlan(plan));
    }

    private DesignerMessageRow designMessage(
            DesignerSessionService host, DesignerSessionRow session, DesignRequirementRevisionRow revision,
            DesignWorkPackageRow workPackage, PackageDesignAcceptedResultRow accepted) {
        if (workPackage.designRevision() == accepted.sourceRevision() && !blank(workPackage.designMessageId())) {
            DesignerMessageRow existing = mapper.findDesignerMessage(workPackage.designMessageId()).orElse(null);
            if (existing != null && accepted.canonicalMarkdown().equals(existing.content())) return existing;
        }
        return mapper.listDesignerMessages(session.id()).stream()
                .filter(message -> DesignerActor.DESIGNER.name().equals(message.actor()))
                .filter(message -> workPackage.packageId().equals(message.workPackageId()))
                .filter(message -> Integer.valueOf(revision.revision()).equals(message.requirementRevision()))
                .filter(message -> accepted.canonicalMarkdown().equals(message.content()))
                .reduce((first, second) -> second)
                .orElseGet(() -> host.appendMessage(session.id(), DesignerActor.DESIGNER,
                        accepted.canonicalMarkdown(), "PERSISTED", revision.revision(), workPackage.packageId()));
    }

    private static LifecycleTransitionService.Subject compilationSubject(
            LoopSpecCompilationRow row, String projectId) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.LOOPSPEC_COMPILATION, row.id(),
                LifecycleScopeType.PROJECT, projectId);
    }

    private static String candidateProblems(MachineCandidateSubmission.SubmissionResult result) {
        return result.problems().stream().map(problem -> problem.code()
                        + (blank(problem.pointer()) ? "" : " " + problem.pointer()) + ": " + problem.detail())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "No diagnostic detail was provided";
        return message.length() <= 4000 ? message : message.substring(0, 4000);
    }
    private static String now() { return Instant.now().toString(); }
}
