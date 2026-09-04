package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;

/** Recognizes the runtime's control notice before it can be mistaken for a completed artifact. */
public final class OpenCodeStepLimitNotice {
    private OpenCodeStepLimitNotice() { }

    public static String requireBusinessOutput(String output) {
        if (isControlNotice(output)) {
            throw new SessionFailure("OPENCODE_STEP_LIMIT_REACHED",
                    "OpenCode 返回了步数上限控制提示，本轮没有可用的业务结果。请确认运行时已加载最新角色配置后重试。");
        }
        return output;
    }

    public static boolean isControlNotice(String output) {
        if (output == null) return false;
        String text = output.stripLeading().replaceFirst("^#{1,6}\\s+", "");
        if (text.startsWith("**")) text = text.substring(2);
        return text.startsWith("CRITICAL - MAXIMUM STEPS REACHED")
                && text.contains("The maximum number of steps allowed for this task has been reached.")
                && text.contains("Tools are disabled until next user input.");
    }
}
