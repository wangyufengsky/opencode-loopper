package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignerTaskProfileRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskProfileRouterRunRow;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Owns Router-run claiming, cancellation, retry validation, and its bounded read projection. */
@Service
public final class TaskProfileRouterRunService {
    private static final Set<String> ACTIVE_STATES = Set.of("PENDING", "RUNNING");
    private static final Set<String> TERMINAL_STATES = Set.of("COMPLETED", "FAILED");
    private final LoopperMapper mapper;
    private final TaskSemanticRouter router;
    /** Prevents request threads and the 750 ms monitor from owning the same persisted run. */
    private final Set<String> claimedRuns = ConcurrentHashMap.newKeySet();

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

    public CancellationRequest requireCancellation(String sessionId, String expectedRunId) {
        session(sessionId);
        TaskProfileRouterRunRow run = mapper.findLatestTaskProfileRouterRun(sessionId)
                .orElseThrow(() -> new NotFoundException("Task Router run not found for Designer session: " + sessionId));
        if (!run.id().equals(expectedRunId)) {
            throw new ConflictException("TASK_PROFILE_ROUTER_RUN_CONFLICT", "识别记录已变化，请刷新后重试");
        }
        if (!ACTIVE_STATES.contains(run.state())) {
            throw new ConflictException("TASK_PROFILE_ROUTER_NOT_ACTIVE", "当前识别已经结束，不能重复取消");
        }
        return new CancellationRequest(run);
    }

    public TaskProfileRouterRunRow cancel(String sessionId, String expectedRunId, Path root) {
        TaskProfileRouterRunRow candidate = requireCancellation(sessionId, expectedRunId).run();
        if (!claim(candidate.id())) {
            throw new ConflictException("TASK_PROFILE_ROUTER_BUSY", "识别状态正在刷新，请稍后重试取消");
        }
        try {
            TaskProfileRouterRunRow run = requireCancellation(sessionId, expectedRunId).run();
            router.abort(root, run.externalSessionId());
            String detail = "用户已取消 AI 任务设置识别，请手动选择任务设置";
            TaskProfileRouterRunRow cancelled = new TaskProfileRouterRunRow(run.id(), run.designerSessionId(),
                    "SUPERSEDED", run.requirementSnapshot(), run.repositoryEvidenceJson(), run.externalSessionId(),
                    "ABORTED", run.responseMode(), null, "ROUTER_USER_CANCELLED", detail, run.createdAt(),
                    Instant.now().toString(), run.version(), run.projectStackProfileId(), run.componentKeysJson(),
                    run.stackFingerprint());
            if (mapper.updateTaskProfileRouterRun(cancelled) != 1) {
                throw new ConflictException("TASK_PROFILE_ROUTER_VERSION_CONFLICT", "任务设置识别记录被并发更新");
            }
            return mapper.findTaskProfileRouterRun(run.id()).orElse(cancelled);
        } finally {
            release(candidate.id());
        }
    }

    public boolean claim(String runId) {
        return claimedRuns.add(runId);
    }

    public void release(String runId) {
        claimedRuns.remove(runId);
    }

    public RouterRunView current(String sessionId) {
        session(sessionId);
        TaskProfileRouterRunRow run = mapper.findLatestTaskProfileRouterRun(sessionId).orElse(null);
        if (run == null) return null;
        DesignerTaskProfileRow profile = mapper.findCurrentDesignerTaskProfile(sessionId).orElse(null);
        return new RouterRunView(run.id(), run.state(), run.externalSessionState(), run.errorCode(), run.errorDetail(),
                run.createdAt(), run.updatedAt(), router.connectionDeadline(run.externalSessionId(), run.createdAt())
                        .map(java.time.Instant::toString).orElse(null),
                TERMINAL_STATES.contains(run.state()) && profile != null
                        && "PROVISIONAL".equals(profile.state()) && profile.decisionRequired() == 1);
    }

    public String timeoutDetail() {
        return "任务设置识别长时间未能连接远端 Session，已停止本次连接尝试";
    }

    private void session(String sessionId) {
        mapper.findDesignerSession(sessionId)
                .orElseThrow(() -> new NotFoundException("Designer session not found: " + sessionId));
    }

    public record RetryRequest(String requirementSnapshot) { }
    public record CancellationRequest(TaskProfileRouterRunRow run) { }
    public record RouterRunView(String id, String state, String externalState,
                                String errorCode, String errorDetail, String createdAt,
                                String updatedAt, String deadlineAt, boolean retryAvailable) { }
}
