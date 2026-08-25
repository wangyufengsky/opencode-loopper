package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignerTaskProfileRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskProfileRouterRunRow;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Owns optimistic Router-run retry validation and its bounded read projection. */
@Service
public final class TaskProfileRouterRunService {
    private static final Set<String> TERMINAL_STATES = Set.of("COMPLETED", "FAILED");
    private final LoopperMapper mapper;
    private final TaskSemanticRouter router;

    public TaskProfileRouterRunService(LoopperMapper mapper, TaskSemanticRouter router) {
        this.mapper = mapper;
        this.router = router;
    }

    public RetryRequest requireReroute(String sessionId, String expectedRunId, long expectedProfileVersion) {
        session(sessionId);
        TaskProfileRouterRunRow run = mapper.findLatestTaskProfileRouterRun(sessionId)
                .orElseThrow(() -> new NotFoundException("Task Router run not found for Designer session: " + sessionId));
        if (!run.id().equals(expectedRunId)) {
            throw new ConflictException("TASK_PROFILE_ROUTER_RUN_CONFLICT", "识别记录已变化，请刷新后重试");
        }
        if (!TERMINAL_STATES.contains(run.state())) {
            throw new ConflictException("TASK_PROFILE_ROUTER_NOT_TERMINAL", "当前识别仍在运行，不能重复启动");
        }
        DesignerTaskProfileRow profile = mapper.findCurrentDesignerTaskProfile(sessionId)
                .orElseThrow(() -> new ConflictException("TASK_PROFILE_MISSING", "当前识别结果尚未形成任务设置"));
        if (!"PROVISIONAL".equals(profile.state()) || profile.version() != expectedProfileVersion) {
            throw new ConflictException("TASK_PROFILE_VERSION_CONFLICT", "任务设置已变化，请刷新后重试");
        }
        if (profile.decisionRequired() != 1) {
            throw new ConflictException("TASK_PROFILE_ROUTER_DECISION_RESOLVED", "任务设置已经确认，不能重新启动识别");
        }
        return new RetryRequest(run.requirementSnapshot());
    }

    public RouterRunView current(String sessionId) {
        session(sessionId);
        TaskProfileRouterRunRow run = mapper.findLatestTaskProfileRouterRun(sessionId).orElse(null);
        if (run == null) return null;
        DesignerTaskProfileRow profile = mapper.findCurrentDesignerTaskProfile(sessionId).orElse(null);
        return new RouterRunView(run.id(), run.state(), run.externalSessionState(), run.errorCode(), run.errorDetail(),
                run.createdAt(), run.updatedAt(), router.deadline(run.createdAt()).toString(),
                TERMINAL_STATES.contains(run.state()) && profile != null
                        && "PROVISIONAL".equals(profile.state()) && profile.decisionRequired() == 1);
    }

    public String timeoutDetail() {
        return "任务设置识别超过 " + router.timeout().toSeconds() + " 秒，远端 Session 已终止";
    }

    private void session(String sessionId) {
        mapper.findDesignerSession(sessionId)
                .orElseThrow(() -> new NotFoundException("Designer session not found: " + sessionId));
    }

    public record RetryRequest(String requirementSnapshot) { }
    public record RouterRunView(String id, String state, String externalState,
                                String errorCode, String errorDetail, String createdAt,
                                String updatedAt, String deadlineAt, boolean retryAvailable) { }
}
