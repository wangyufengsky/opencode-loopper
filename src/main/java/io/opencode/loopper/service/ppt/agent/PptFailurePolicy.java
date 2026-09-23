package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.service.assist.AssistRedaction;
import java.util.Locale;
import java.util.Objects;

/** Only a proved terminal result is classified here; unknown delivery never enters this policy. */
public final class PptFailurePolicy {
    private PptFailurePolicy() { }
    public record Failure(String category, String code, String detail, String message) {
        public boolean recoverable() { return category.equals("TRANSIENT") || category.equals("OUTPUT_LIMIT"); }
    }
    public static Failure classify(String code, String detail) {
        String safeCode = safe(code, 160), safeDetail = safe(detail, 1600);
        String text = (safeCode.replace('_', ' ') + " " + safeCode + " " + safeDetail).toLowerCase(Locale.ROOT);
        if (text.matches("(?s).*(unauthoriz|forbidden|authentication|invalid.api.key|permission.denied|\\b401\\b|\\b403\\b).*"))
            return new Failure("CONFIGURATION", safeCode, safeDetail, "模型认证或权限有误，请检查模型配置后继续");
        if (text.contains("output_length") || text.contains("max_tokens") || text.contains("output token limit"))
            return new Failure("OUTPUT_LIMIT", safeCode, safeDetail, "模型输出达到长度限制，已保存页面保留");
        if (text.matches("(?s).*(rate.?limit|overload|timeout|timed.out|temporarily.unavailable|service.unavailable|connection.reset|\\b429\\b|\\b50[234]\\b).*"))
            return new Failure("TRANSIENT", safeCode, safeDetail, "模型服务暂时不可用，已保存页面保留");
        return new Failure("UNKNOWN", safeCode, safeDetail, "模型本轮失败，已保存页面保留；请查看原因后继续");
    }
    public static String safe(String value, int limit) {
        String text = AssistRedaction.text(Objects.toString(value, "")).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        return text.length() > limit ? text.substring(0, limit) : text;
    }
}
