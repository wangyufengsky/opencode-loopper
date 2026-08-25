package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignWorkflowPhase;
import io.opencode.loopper.domain.DesignerAutoModeState;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignerAutoModeRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Persisted, restart-safe authorization that advances only Designer review boundaries. */
@Service
public class DesignerAutoModeService {
    private static final String AUTOMATION = "AUTOMATION";
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final DesignerSessionService designerSessions;
    private final LoopDraftService drafts;
    private final TaskService tasks;
    private final TaskProfileService profiles;
    private final AnalysisReportService reports;
    private final DirectArtifactDesignService directArtifacts;
    private final DirectMaintenanceDesignService directMaintenance;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public DesignerAutoModeService(LoopperMapper mapper, LifecycleTransitionService lifecycle,
                                   DesignerSessionService designerSessions, LoopDraftService drafts,
                                   TaskService tasks, TaskProfileService profiles,
                                   AnalysisReportService reports, DirectArtifactDesignService directArtifacts,
                                   DirectMaintenanceDesignService directMaintenance) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.designerSessions = designerSessions;
        this.drafts = drafts;
        this.tasks = tasks;
        this.profiles = profiles;
        this.reports = reports;
        this.directArtifacts = directArtifacts;
        this.directMaintenance = directMaintenance;
    }

    public View initialize(String sessionId, boolean enabled) {
        DesignerSessionRow session = designerSessions.get(sessionId);
        DesignerAutoModeState state = enabled ? DesignerAutoModeState.ACTIVE : DesignerAutoModeState.DISABLED;
        String now = now();
        DesignerAutoModeRow row = new DesignerAutoModeRow(sessionId, state.name(),
                enabled ? "MODE_ENABLED" : "MODE_DISABLED", null, null, null,
                enabled ? now : null, enabled ? null : now, now, 0);
        lifecycle.create(subject(session), row.state(), Map.of("enabled", enabled),
                () -> mapper.insertDesignerAutoMode(row), conflict());
        if (enabled) designerSessions.recordAutoModeNotice(sessionId,
                "全自动模式已授权：仅自动完成设计问题、需求与工作包确认、最终设计确认和任务启动。",
                "AUTO_MODE_ENABLED");
        return view(row);
    }

    public View get(String sessionId) {
        designerSessions.get(sessionId);
        return mapper.findDesignerAutoMode(sessionId).map(this::view)
                .orElseGet(() -> new View(false, DesignerAutoModeState.DISABLED.name(), 0,
                        null, null, null, null, null));
    }

    public View setEnabled(String sessionId, boolean enabled, long expectedVersion) {
        DesignerSessionRow session = designerSessions.get(sessionId);
        DesignerAutoModeRow current = mapper.findDesignerAutoMode(sessionId).orElse(null);
        if (current == null) {
            if (expectedVersion != 0) throw conflict().get();
            if (enabled) requireEnableAllowed(session);
            return initialize(sessionId, enabled);
        }
        if (current.version() != expectedVersion) throw conflict().get();
        DesignerAutoModeState from = DesignerAutoModeState.valueOf(current.state());
        DesignerAutoModeState to = enabled ? DesignerAutoModeState.ACTIVE : DesignerAutoModeState.DISABLED;
        if (from == DesignerAutoModeState.COMPLETED) {
            throw new ConflictException("DESIGNER_AUTO_MODE_COMPLETED",
                    "全自动模式已完成本次授权范围，不能再次切换");
        }
        if (from == to) return view(current);
        if (enabled) requireEnableAllowed(session);
        String now = now();
        DesignerAutoModeRow changed = new DesignerAutoModeRow(sessionId, to.name(),
                enabled ? "MODE_ENABLED" : "MODE_DISABLED", null, null, current.taskId(),
                enabled ? now : current.authorizedAt(), enabled ? null : now, now, current.version());
        lifecycle.transition(subject(session), from.name(), to.name(),
                enabled ? LifecycleEvent.ENABLE : LifecycleEvent.DISABLE,
                enabled ? "USER_AUTHORIZED" : "USER_DISABLED", Map.of("enabled", enabled),
                () -> mapper.updateDesignerAutoMode(changed), conflict());
        designerSessions.recordAutoModeNotice(sessionId,
                enabled
                        ? "全自动模式已重新授权，将从当前设计步骤继续。"
                        : "全自动模式已关闭；已完成的自动动作不会撤销，正在执行的模型调用不会终止。",
                enabled ? "AUTO_MODE_ENABLED" : "AUTO_MODE_DISABLED");
        return get(sessionId);
    }

    public void pollActive() {
        for (DesignerAutoModeRow row : mapper.listDesignerAutoModesForAdvance()) {
            if (!inFlight.add(row.designerSessionId())) continue;
            try {
                if (isLegacyProfileDecisionBlock(row)) resumeProfileDecisionBlock(row.designerSessionId());
                else advanceOne(row.designerSessionId());
            }
            finally { inFlight.remove(row.designerSessionId()); }
        }
    }

    /** Restores a profile blocker only after the authoritative profile is explicitly ready. */
    public boolean resumeProfileDecisionBlock(String sessionId) {
        DesignerAutoModeRow current = mapper.findDesignerAutoMode(sessionId).orElse(null);
        if (!isProfileDecisionBlock(current)) return false;
        TaskProfileService.View profile = profiles.current(sessionId);
        if (!isLegacyProfileDecisionBlock(current) && !profile.confirmationReady()) return false;
        DesignerSessionRow session = designerSessions.get(sessionId);
        DesignerAutoModeRow changed = new DesignerAutoModeRow(sessionId, DesignerAutoModeState.ACTIVE.name(),
                "PROFILE_DECISION_RESUMED", null, null, current.taskId(), current.authorizedAt(),
                current.disabledAt(), now(), current.version());
        try {
            lifecycle.transition(subject(session), current.state(), changed.state(), LifecycleEvent.RESUME,
                    "TASK_PROFILE_AUTO_DECISION_AVAILABLE", Map.of("taskProfileVersion", profile.version()),
                    () -> mapper.updateDesignerAutoMode(changed), conflict());
        } catch (ConflictException concurrentChange) {
            if ("DESIGNER_AUTO_MODE_VERSION_CONFLICT".equals(concurrentChange.code())) return false;
            throw concurrentChange;
        }
        designerSessions.recordAutoModeNotice(session.id(),
                "任务设置已可继续，全自动模式将沿用原授权处理当前识别结果。",
                "AUTO_MODE_PROFILE_RESUMED");
        return true;
    }

    void advanceOne(String sessionId) {
        DesignerAutoModeRow mode = mapper.findDesignerAutoMode(sessionId).orElse(null);
        if (mode == null || !DesignerAutoModeState.ACTIVE.name().equals(mode.state())) return;
        try {
            DesignerSessionRow session = designerSessions.get(sessionId);
            if (DesignerSessionState.STOPPING.name().equals(session.state())
                    || DesignerSessionState.CANCELLED.name().equals(session.state())) return;
            LoopDraftRow draft = designerSessions.draft(sessionId);
            if (draft == null) {
                block(mode, session, "DESIGNER_AUTO_DRAFT_MISSING", "设计会话没有绑定草稿");
                return;
            }
            if ("CONFIRMED".equals(draft.status())) {
                TaskRow task = mapper.findTaskByDraft(draft.id()).orElseThrow(() ->
                        new ConflictException("DRAFT_TASK_MISSING", "已确认设计没有关联任务"));
                tasks.start(task.id(), AUTOMATION);
                complete(mode, session, task.id());
                return;
            }
            if (DesignerSessionState.WAITING_INPUT.name().equals(session.state())
                    || DesignerSessionState.SESSION_ERROR.name().equals(session.state())
                    || DesignWorkflowPhase.FAILED.name().equals(session.workflowPhase())) {
                block(mode, session, "DESIGNER_AUTO_WORKFLOW_BLOCKED",
                        "设计流程正在等待人工处理或已发生会话错误");
                return;
            }
            if (DesignWorkflowPhase.ROUTING.name().equals(session.workflowPhase())) {
                TaskProfileService.View profile = profiles.current(sessionId);
                if ("ROUTING".equals(profile.decisionState())) return;
                if (profile.evidence().stream().anyMatch(value -> value.startsWith("router-error="))) {
                    block(mode, session, "TASK_PROFILE_ROUTER_REVIEW_REQUIRED",
                            "任务设置识别失败或已降级，需要人工重做、修改或显式采用");
                    return;
                }
                if (profile.decisionRequired()) {
                    profiles.acceptRecommendation(sessionId, profile.version());
                    recordAction(mode, "PROFILE_AUTO_CONFIRMED");
                    designerSessions.recordAutoModeNotice(session.id(),
                            "全自动模式已采用 Router 的安全识别结果并进入需求设计。",
                            "AUTO_MODE_PROFILE_CONFIRMED");
                }
                designerSessions.continueAfterTaskProfileDecision(sessionId);
                return;
            }
            var questions = designerSessions.pendingQuestions(sessionId);
            if (!questions.isEmpty()) {
                designerSessions.replyRecommendedQuestion(sessionId, questions.getFirst().id());
                recordAction(mode, "QUESTION_AUTO_REPLIED");
                return;
            }
            if (DesignerSessionState.REVIEWING.name().equals(session.state())
                    && DesignWorkflowPhase.DISCUSSING_REQUIREMENT.name().equals(session.workflowPhase())
                    && session.currentRequirementRevision() == null) {
                TaskProfileService.View currentProfile = profiles.current(sessionId);
                if ("ROUTING".equals(currentProfile.decisionState())) return;
                if (currentProfile.decisionRequired()) {
                    profiles.acceptRecommendation(sessionId, currentProfile.version());
                    recordAction(mode, "PROFILE_AUTO_CONFIRMED");
                    designerSessions.recordAutoModeNotice(session.id(),
                            "全自动模式已采用 Router 当前推荐的任务类型和主要制品；仍可在需求确认前人工覆盖。",
                            "AUTO_MODE_PROFILE_CONFIRMED");
                    return;
                }
                TaskProfileService.View profile = profiles.freeze(sessionId);
                if (profile.executionStrategy() == io.opencode.loopper.domain.ExecutionStrategy.READ_ONLY_REPORT) {
                    designerSessions.beginReadOnlyReport(sessionId);
                    reports.startReviewer(sessionId);
                } else if (profile.workflowTemplate() == io.opencode.loopper.domain.WorkflowTemplate.DIRECT_ARTIFACT) {
                    directArtifacts.compile(sessionId, profile);
                    designerSessions.completeDirectArtifactDesign(sessionId);
                } else if (profile.workflowTemplate() == io.opencode.loopper.domain.WorkflowTemplate.PACKAGED_ARTIFACT) {
                    directArtifacts.compilePackagedDocument(sessionId, profile);
                    designerSessions.completeDirectArtifactDesign(sessionId);
                } else if (profile.workflowTemplate() == io.opencode.loopper.domain.WorkflowTemplate.LOCAL_MAINTENANCE) {
                    directMaintenance.compile(sessionId, profile);
                    designerSessions.completeDirectArtifactDesign(sessionId);
                } else {
                    designerSessions.confirmRequirementAutomatically(sessionId, session.discussionRevision());
                }
                recordAction(mode, "REQUIREMENT_AUTO_CONFIRMED");
                return;
            }
            if (DesignWorkflowPhase.REVIEWING_PACKAGE.name().equals(session.workflowPhase())
                    && session.activeWorkPackageId() != null) {
                DesignerSessionService.WorkPackageStatus workPackage = designerSessions.workPackageStatuses(sessionId)
                        .stream().filter(item -> item.id().equals(session.activeWorkPackageId())).findFirst()
                        .orElseThrow(() -> new ConflictException("WORK_PACKAGE_MISSING", "当前工作包不存在"));
                if ("REVIEWING".equals(workPackage.state())) {
                    designerSessions.approvePackageAutomatically(sessionId, workPackage.id(),
                            session.discussionRevision(), workPackage.designRevision());
                    recordAction(mode, "WORK_PACKAGE_AUTO_APPROVED");
                    return;
                }
            }
            if (DesignWorkflowPhase.FINAL_REVIEW.name().equals(session.workflowPhase())
                    && designerSessions.finalConfirmationEligible(sessionId)) {
                TaskRow task = drafts.confirm(draft.id(), draft.goal(), AUTOMATION);
                tasks.start(task.id(), AUTOMATION);
                complete(mode, session, task.id());
            }
        } catch (RuntimeException failure) {
            DesignerAutoModeRow current = mapper.findDesignerAutoMode(sessionId).orElse(null);
            if (current == null || !DesignerAutoModeState.ACTIVE.name().equals(current.state())) return;
            block(current, designerSessions.get(sessionId), errorCode(failure), safeDetail(failure));
        }
    }

    private void recordAction(DesignerAutoModeRow current, String action) {
        DesignerAutoModeRow latest = mapper.findDesignerAutoMode(current.designerSessionId()).orElse(current);
        if (!DesignerAutoModeState.ACTIVE.name().equals(latest.state())) return;
        if (action.equals(latest.lastAction()) && latest.errorCode() == null && latest.errorDetail() == null) return;
        DesignerAutoModeRow changed = new DesignerAutoModeRow(latest.designerSessionId(), latest.state(), action,
                null, null, latest.taskId(), latest.authorizedAt(), latest.disabledAt(), now(), latest.version());
        lifecycle.mutateWithoutTransition(() -> mapper.updateDesignerAutoMode(changed), conflict());
    }

    private boolean isProfileDecisionBlock(DesignerAutoModeRow row) {
        return row != null && DesignerAutoModeState.BLOCKED.name().equals(row.state())
                && Set.of("TASK_PROFILE_DECISION_REQUIRED", "TASK_PROFILE_ROUTER_REVIEW_REQUIRED")
                .contains(row.errorCode());
    }

    private boolean isLegacyProfileDecisionBlock(DesignerAutoModeRow row) {
        return isProfileDecisionBlock(row) && "TASK_PROFILE_DECISION_REQUIRED".equals(row.errorCode());
    }

    private void block(DesignerAutoModeRow current, DesignerSessionRow session, String code, String detail) {
        DesignerAutoModeRow changed = new DesignerAutoModeRow(current.designerSessionId(),
                DesignerAutoModeState.BLOCKED.name(), "MODE_BLOCKED", code, detail, current.taskId(),
                current.authorizedAt(), current.disabledAt(), now(), current.version());
        lifecycle.transition(subject(session), current.state(), changed.state(), LifecycleEvent.REQUIRE_INPUT,
                code, Map.of("errorCode", code), () -> mapper.updateDesignerAutoMode(changed), conflict());
        designerSessions.recordAutoModeNotice(session.id(),
                "全自动模式已阻断：" + detail + "。请先关闭后人工处理，或处理完成后重新授权。",
                "AUTO_MODE_BLOCKED");
    }

    private void complete(DesignerAutoModeRow current, DesignerSessionRow session, String taskId) {
        DesignerAutoModeRow latest = mapper.findDesignerAutoMode(current.designerSessionId()).orElse(current);
        if (!DesignerAutoModeState.ACTIVE.name().equals(latest.state())) return;
        DesignerAutoModeRow changed = new DesignerAutoModeRow(latest.designerSessionId(),
                DesignerAutoModeState.COMPLETED.name(), "TASK_START_REQUESTED", null, null, taskId,
                latest.authorizedAt(), latest.disabledAt(), now(), latest.version());
        lifecycle.transition(subject(session), latest.state(), changed.state(), LifecycleEvent.COMPLETE,
                "TASK_START_REQUESTED", Map.of("taskId", taskId),
                () -> mapper.updateDesignerAutoMode(changed), conflict());
        designerSessions.recordAutoModeNotice(session.id(),
                "全自动设计已完成并请求启动任务，后续执行期决策仍需人工处理。",
                "AUTO_MODE_COMPLETED");
    }

    private void requireEnableAllowed(DesignerSessionRow session) {
        if (designerSessions.archived(session.id())) {
            throw new ConflictException("DESIGNER_SESSION_ARCHIVED", "归档设计不能开启全自动模式");
        }
        LoopDraftRow draft = designerSessions.draft(session.id());
        if (draft == null || "CONFIRMED".equals(draft.status())) {
            throw new ConflictException("DESIGNER_AUTO_MODE_UNAVAILABLE", "已确认或未绑定草稿的设计不能开启全自动模式");
        }
    }

    private LifecycleTransitionService.Subject subject(DesignerSessionRow session) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGNER_AUTO_MODE, session.id(),
                LifecycleScopeType.DESIGNER, session.id());
    }

    private java.util.function.Supplier<ConflictException> conflict() {
        return () -> new ConflictException("DESIGNER_AUTO_MODE_VERSION_CONFLICT", "全自动模式状态已变化，请刷新后重试");
    }

    private String errorCode(RuntimeException failure) {
        if (failure instanceof BadRequestException badRequest) return badRequest.code();
        if (failure instanceof ConflictException conflict) return conflict.code();
        if (failure instanceof ServiceUnavailableException unavailable) return unavailable.code();
        return "DESIGNER_AUTO_MODE_FAILED";
    }

    private String safeDetail(RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = "自动推进发生未知错误";
        detail = detail.replaceAll("[\\r\\n]+", " ").trim();
        return detail.length() <= 1000 ? detail : detail.substring(0, 1000);
    }

    private View view(DesignerAutoModeRow row) {
        return new View(Set.of(DesignerAutoModeState.ACTIVE.name(), DesignerAutoModeState.BLOCKED.name())
                .contains(row.state()), row.state(), row.version(),
                row.lastAction(), row.errorCode(), row.errorDetail(), row.taskId(), row.updatedAt());
    }

    private String now() { return Instant.now().toString(); }

    public record View(boolean enabled, String state, long version, String lastAction,
                       String errorCode, String errorDetail, String taskId, String updatedAt) { }
}
