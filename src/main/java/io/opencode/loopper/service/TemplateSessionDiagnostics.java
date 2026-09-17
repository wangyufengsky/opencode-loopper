package io.opencode.loopper.service;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Read-only health and recovery capabilities; no model calls or inferred business completion. */
@Service
public class TemplateSessionDiagnostics {
    private final TemplateSessionDiagnosticMapper reads;
    private final LoopperMapper tasks;
    public TemplateSessionDiagnostics(TemplateSessionDiagnosticMapper reads, LoopperMapper tasks) {
        this.reads = reads; this.tasks = tasks;
    }

    public CursorPage<Diagnostic> list(String taskId, String filter, String cursor, int limit) {
        requireTask(taskId);
        if (!Set.of("ALL", "ACTIVE", "ATTENTION").contains(filter) || limit < 1 || limit > 100)
            throw new BadRequestException("TEMPLATE_DIAGNOSTIC_QUERY_INVALID", "筛选或分页参数无效，请刷新后重试");
        String[] position = cursor(cursor);
        Instant now = Instant.now();
        var rows = reads.list(taskId, filter, position[0], position[1], limit + 1,
                now.minusSeconds(90).toString(), now.minusSeconds(300).toString());
        var page = rows.stream().limit(limit).toList();
        String next = rows.size() > limit ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (page.getLast().createdAt() + "\n" + page.getLast().batchId()).getBytes(StandardCharsets.UTF_8)) : null;
        return new CursorPage<>(page.stream().map(row -> project(row, now, false)).toList(), next);
    }

    public Diagnostic get(String taskId, String batchId) {
        requireTask(taskId);
        return project(reads.find(taskId, batchId).orElseThrow(() -> new NotFoundException("批次不属于当前任务")), Instant.now(), true);
    }

    private void requireTask(String id) {
        var task = tasks.findTask(id).orElseThrow(() -> new NotFoundException("任务不存在"));
        if (!TemplateWorkspaceService.applies(task)) throw new BadRequestException("TEMPLATE_TASK_REQUIRED", "请选择模板任务");
    }

    private static Diagnostic project(TemplateSessionDiagnosticMapper.Row r, Instant now, boolean detail) {
        boolean terminal = Set.of("VALIDATED", "FAILED", "STOPPED").contains(r.state());
        boolean fresh = r.observedAt() != null && Instant.parse(r.observedAt()).plusSeconds(90).isAfter(now);
        boolean connected = fresh && r.connected() == 1;
        if (r.transportError() != null) connected = false;
        boolean stalled = r.lastActivityAt() != null && !Instant.parse(r.lastActivityAt()).plusSeconds(300).isAfter(now);
        boolean autoRetry = r.taskState().equals("RUNNING") && TemplateBatchFailurePolicy.retryable(r.state(), r.errorCode())
                && r.automaticRetries() < r.retryLimit();
        String retryAt = autoRetry ? Instant.parse(r.updatedAt()).plusSeconds(TemplateBatchFailurePolicy.retryDelay(r.automaticRetries())).toString() : null;
        String phase = r.state().equals("VALIDATED") ? "COMPLETED" : autoRetry ? "AUTO_RETRY_WAIT" : terminal ? "FAILED"
                : r.stopProof() != null ? "STOP_CONFIRMED" : r.recoveryError() != null ? "STOP_UNCONFIRMED"
                : "STOP".equals(r.recoveryAction()) ? "STOP_REQUESTED" : r.acceptedAt() != null ? "ACCEPTED_WAITING_STOP"
                : r.transportError() != null || r.observedAt() != null && !connected ? "DISCONNECTED" : stalled ? "STALLED"
                : r.state().equals("RUNNING") ? "ANALYZING" : "PREPARING";
        String reason = switch (phase) {
            case "COMPLETED" -> "分析结果已验证，会话已结束";
            case "AUTO_RETRY_WAIT" -> "本批次已确认失败，等待自动重试；其他独立批次继续执行";
            case "FAILED" -> "该批次已结束，可在其余批次结束后查看重试操作";
            case "STOP_CONFIRMED" -> "会话停止已确认，正在收束批次";
            case "STOP_UNCONFIRMED" -> "尚未确认会话停止，已保留结果并阻止重复执行";
            case "STOP_REQUESTED" -> "已请求停止当前批次，等待停止确认";
            case "ACCEPTED_WAITING_STOP" -> "结果已接受，等待会话结束；超出收尾窗口将自动请求停止";
            case "DISCONNECTED" -> "近期未获得有效会话观察，请检查运行环境；不会据此重新调用模型";
            case "STALLED" -> "至少五分钟没有观察到新活动，疑似停滞；尚未判定执行失败";
            case "ANALYZING" -> "等待分析结果，连接状态不代表业务进展";
            default -> "批次正在准备或投递，尚未进入结果收尾";
        };
        if (r.transportMessage() != null) reason = r.transportMessage();
        boolean runnable = r.currentGeneration() == 1 && r.taskState().equals("RUNNING") && r.attemptState().equals("RUNNING")
                && r.state().equals("RUNNING") && r.externalSessionId() != null && r.requestMessageId() != null;
        boolean stoppable = r.currentGeneration() == 1 && Set.of("RUNNING", "WAITING_INPUT").contains(r.taskState())
                && r.attemptState().equals("RUNNING") && Set.of("RUNNING", "STOPPING").contains(r.state())
                && r.externalSessionId() != null && r.requestMessageId() != null && r.stopProof() == null
                && r.recoveryAction() == null && r.acceptedAt() == null;
        boolean checkable = r.currentGeneration() == 1 && !terminal
                && Set.of("RUNNING", "WAITING_INPUT", "STOPPING").contains(r.taskState());
        return new Diagnostic(r.batchId(), r.batchVersion(), r.localSessionId() == null ? null : "execution:" + r.localSessionId(),
                r.localSessionId(), r.externalSessionId(), r.purpose(), r.ordinal(), r.generation(), r.stageOrdinal(), r.state(), phase, reason,
                r.acceptedAt(), r.observedAt(), r.lastActivityAt(), r.lastProgressAt(), r.remoteState(), connected,
                r.stopProof(), r.stopConfirmedAt(), runnable && r.acceptedAt() != null && r.stopProof() == null
                    && !"STOP".equals(r.recoveryAction()), stoppable,
                detail ? r.worktreePath() : null, detail ? r.requestMessageId() : null, r.submissionRevision(), r.acceptedAt() != null,
                r.recoveryRequestedAt(), r.recoveryAction(), r.automaticRetries(), r.retryLimit(), retryAt,
                r.failedOperation(), r.transportError(), r.transportMessage(), r.firstFailedAt(), r.lastFailedAt(),
                r.transportFailures(), r.nextCheckAt(), checkable);
    }

    private static String[] cursor(String value) {
        if (value == null || value.isBlank()) return new String[]{null, null};
        try {
            if (value.length() > 512) throw new IllegalArgumentException();
            var parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 2 || parts[1].isBlank()) throw new IllegalArgumentException();
            Instant.parse(parts[0]); return parts;
        } catch (RuntimeException invalid) { throw new BadRequestException("PAGE_CURSOR_INVALID", "分页位置无效，请刷新列表"); }
    }

    public record Diagnostic(String batchId, long batchVersion, String sessionKey, String localSessionId, String externalSessionId,
            String purpose, int ordinal, int generation, int stageOrdinal, String state, String phase, String reason,
            String acceptedAt, String observedAt, String lastActivityAt, String lastProgressAt, String remoteState, boolean connected,
            String stopProof, String stopConfirmedAt, boolean canFinalize, boolean canStop, String worktreePath,
            String requestMessageId, long submissionRevision, boolean candidateAccepted, String recoveryRequestedAt, String recoveryAction,
            int automaticRetries, int retryLimit, String nextRetryAt, String failedOperation, String transportError,
            String transportMessage, String firstFailedAt, String lastFailedAt, Integer transportFailures, String nextCheckAt,
            boolean canCheck) { }
}
