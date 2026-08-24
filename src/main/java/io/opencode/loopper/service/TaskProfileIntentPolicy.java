package io.opencode.loopper.service;

import java.util.regex.Pattern;

/** Distinguishes task-level read-only review requests from review vocabulary inside a writable design. */
final class TaskProfileIntentPolicy {
    private static final Pattern REVIEW_ACTION = Pattern.compile(
            "(?:^|[。！？；;\\n\\r])\\s*(?:[-*#>]\\s*)*(?:(?:请|需要|帮我)\\s*)?"
                    + "(?:只读\\s*)?(?:评审|审查|检查代码|诊断|review\\b|diagnose\\b)"
                    + "|(?:^|[。！？；;\\n\\r])\\s*(?:[-*#>]\\s*)*对[^。！？；;\\n\\r]{0,32}"
                    + "(?:进行)?(?:评审|审查|检查|诊断)"
                    + "|只读(?:评审|审查|检查|诊断|报告)");
    private static final Pattern MUTATION_ACTION = Pattern.compile(
            "新增|修改|修复|实现|编写|开发|重构|优化|更新|写入|生成文件"
                    + "|\\bimplement\\b|\\bfix\\b|\\bmodify\\b|\\bwrite\\b|\\brefactor\\b");
    private static final Pattern NEGATION = Pattern.compile(
            "(?:不得|禁止|不要|无需|避免|不能|不会|严禁|拒绝|防止|不)[^。！？；;，,\\n\\r]{0,10}$");

    boolean requestsReadOnlyReview(String requirement) {
        return REVIEW_ACTION.matcher(requirement == null ? "" : requirement).find();
    }

    boolean requestsMutation(String requirement) {
        String text = requirement == null ? "" : requirement;
        var matcher = MUTATION_ACTION.matcher(text);
        while (matcher.find()) {
            int clauseStart = clauseStart(text, matcher.start());
            String prefix = text.substring(clauseStart, matcher.start()).stripTrailing();
            if (!NEGATION.matcher(prefix).find()) return true;
        }
        return false;
    }

    private static int clauseStart(String text, int offset) {
        for (int index = offset - 1; index >= 0; index--) {
            char value = text.charAt(index);
            if (value == '。' || value == '！' || value == '？' || value == '；' || value == ';'
                    || value == '，' || value == ',' || value == '\n' || value == '\r') return index + 1;
        }
        return 0;
    }
}
