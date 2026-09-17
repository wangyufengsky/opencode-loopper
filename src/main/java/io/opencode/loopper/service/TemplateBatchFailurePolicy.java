package io.opencode.loopper.service;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.TaskFailure;
import java.util.Locale;
import java.util.Set;

/** Explicit retry classes: unknown identity and programming faults are never fresh-model retry authority. */
final class TemplateBatchFailurePolicy {
    static final int MAX_RETRIES = 3;
    private static final Set<String> TRANSPORT = Set.of("OPENCODE_STATUS_FAILED", "OPENCODE_OUTPUT_FAILED",
            "OPENCODE_MESSAGES_FAILED", "OPENCODE_TRANSCRIPT_FAILED", "OPENCODE_SESSION_CREATE_FAILED",
            "OPENCODE_PROMPT_FAILED", "OPENCODE_SESSION_LOOKUP_FAILED", "OPENCODE_PROMPT_LOOKUP_FAILED",
            "OPENCODE_RUNTIME_UNAVAILABLE", "OPENCODE_RUNTIME_NOT_READY", "OPENCODE_RUNTIME_START_FAILED",
            "TEMPLATE_SESSION_LOOKUP_UNAVAILABLE", "TEMPLATE_PROMPT_LOOKUP_UNAVAILABLE");
    private static final Set<String> MODEL = Set.of("TEMPLATE_MODEL_FAILED", "TEMPLATE_SUBMISSION_MISSING",
            "TEMPLATE_ANALYSIS_STALLED", "OPENCODE_OUTPUT_LENGTH_EXHAUSTED", "TEMPLATE_CANDIDATE_INVALID");
    private TemplateBatchFailurePolicy() { }

    static String code(RuntimeException failure) {
        String value = failure instanceof SessionFailure s ? s.code() : failure instanceof TaskFailure t ? t.code()
                : failure instanceof BadRequestException b ? b.code() : "TEMPLATE_EXECUTION_INTERRUPTED";
        return value != null && value.matches("[A-Z0-9_]{1,100}") ? value : "TEMPLATE_EXECUTION_INTERRUPTED";
    }
    static boolean transport(RuntimeException failure) { return TRANSPORT.contains(code(failure)); }
    static boolean resumable(String code) { return code != null && (TRANSPORT.contains(code) || "TEMPLATE_EXECUTION_INTERRUPTED".equals(code)); }
    static boolean retryable(String state, String code) { return "FAILED".equals(state) && MODEL.contains(code == null ? "" : code); }
    static int retryDelay(int used) { return 10 << Math.min(used, 2); }

    /** Only derived classifications are retained; raw HTTP bodies, URLs and exception text may contain credentials. */
    static String detail(RuntimeException failure) {
        String text = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("401") || text.contains("403") || text.contains("unauthorized")) return "运行环境认证或访问被拒绝，请检查连接配置";
        if (text.contains("429") || text.contains("rate limit")) return "运行环境请求限流，将延迟重新检查";
        if (text.contains("timed out") || text.contains("timeout")) return "连接或读取超时，将保留原会话并重新检查";
        if (text.contains("connection refused") || text.contains("connectexception")) return "无法连接运行环境，请检查 OpenCode 服务";
        if (text.contains("404") || text.contains("not found")) return "未能读取对应会话，需按精确会话身份检查或确认停止";
        if (code(failure).contains("ABORT") || code(failure).contains("STOP_UNCONFIRMED"))
            return "停止请求尚未获得明确确认，将保留会话占用并继续检查";
        return transport(failure) ? "运行环境请求暂未成功，已保留原会话和请求身份" : "执行步骤异常，请查看诊断中的操作与错误类别";
    }
    static boolean environmentUnavailable(RuntimeException failure) {
        String text = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        return code(failure).contains("RUNTIME_") || text.contains("connection refused") || text.contains("connectexception")
                || text.contains("timeout") || text.contains("timed out") || text.contains("401") || text.contains("403")
                || text.contains("502") || text.contains("503") || text.contains("504");
    }
}
