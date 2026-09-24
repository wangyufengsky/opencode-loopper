package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.LocalSyncConflictFileRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Isolates the read-only OpenCode session used to propose a single-file three-way merge. */
final class LocalSyncAiAdvisor {
    @org.springframework.beans.factory.annotation.Autowired(required = false) private io.opencode.loopper.service.RoleSessions roleSessions;
    private static final Duration TIMEOUT = Duration.ofSeconds(75);
    private static final long MAX_OUTPUT_BYTES = 1024L * 1024L;
    private final OpenCodeClient openCode;
    private final LoopperProperties properties;

    LocalSyncAiAdvisor(OpenCodeClient openCode, LoopperProperties properties) {
        this.openCode = openCode;
        this.properties = properties;
    }

    String suggest(Path workspace, String taskGoal, LocalSyncConflictFileRow file) { return suggest(null, workspace, taskGoal, file); }
    String suggest(String taskId, Path workspace, String taskGoal, LocalSyncConflictFileRow file) {
        if (!openCode.healthy()) {
            throw new ServiceUnavailableException("OPENCODE_UNAVAILABLE", "当前 OpenCode 模型不可用");
        }
        OpenCodeClient.OpenCodeSession session;
        try {
            session = RoleSessions.readOnly(roleSessions, openCode, "TASK", taskId, "MERGE_ADVISOR", workspace,
                    "Loopper Local Sync Merge Suggestion (READ_ONLY)", configuredModel());
            openCode.promptAsync(session, RoleSessions.renderSession(roleSessions, session.id(), () -> prompt(taskGoal, file)));
        } catch (RuntimeException failure) {
            throw new ServiceUnavailableException("LOCAL_SYNC_AI_FAILED", safe(failure));
        }
        return await(session);
    }

    private String prompt(String taskGoal, LocalSyncConflictFileRow file) {
        return (RolePromptResources.read("prompt.v1.LocalSyncAiAdvisor.prompt.segment0")
                + String.format("%s", (Object) (taskGoal))
                + "\n文件："
                + String.format("%s", (Object) (file.path()))
                + "\n===== BASE =====\n"
                + String.format("%s", (Object) (text(file.baseContent())))
                + "\n===== 源项目 =====\n"
                + String.format("%s", (Object) (text(file.sourceContent())))
                + "\n===== 任务 =====\n"
                + String.format("%s", (Object) (text(file.taskContent())))
                + "\n");
    }

    private String await(OpenCodeClient.OpenCodeSession session) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                OpenCodeClient.SessionStatus status = openCode.sessionStatus(session);
                if (status.completed()) {
                    String output = openCode.sessionOutput(session);
                    if (output == null || output.isBlank()) {
                        throw new ServiceUnavailableException("LOCAL_SYNC_AI_EMPTY", "AI 未返回建议");
                    }
                    if (output.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
                        throw new ServiceUnavailableException("LOCAL_SYNC_AI_TOO_LARGE", "AI 建议超过 1 MiB 安全上限");
                    }
                    return output;
                }
                if (status.failed()) {
                    throw new ServiceUnavailableException("LOCAL_SYNC_AI_FAILED", status.detail());
                }
                TimeUnit.MILLISECONDS.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                abort(session);
                throw new ServiceUnavailableException("LOCAL_SYNC_AI_INTERRUPTED", "AI 建议生成被中断");
            }
        }
        abort(session);
        throw new ServiceUnavailableException("LOCAL_SYNC_AI_TIMEOUT", "AI 建议生成超时");
    }

    private OpenCodeClient.OpenCodeModel configuredModel() {
        String configured = properties.getOpenCode().getModel();
        if (configured == null) return null;
        int separator = configured.indexOf('/');
        if (separator <= 0 || separator >= configured.length() - 1) return null;
        return new OpenCodeClient.OpenCodeModel(
                configured.substring(0, separator), configured.substring(separator + 1), null);
    }

    private void abort(OpenCodeClient.OpenCodeSession session) {
        try {
            openCode.abort(session);
        } catch (RuntimeException ignored) {
            // Best effort: timeout/interruption already owns the user-visible failure.
        }
    }

    private String safe(Throwable failure) {
        String message = failure == null || failure.getMessage() == null ? "未知错误" : failure.getMessage();
        return message.substring(0, Math.min(message.length(), 1_200));
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
