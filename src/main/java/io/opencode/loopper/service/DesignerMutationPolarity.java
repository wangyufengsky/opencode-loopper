package io.opencode.loopper.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared action-aware polarity rules for requirement clauses and controlled DesignFacts. */
final class DesignerMutationPolarity {
    private static final List<String> ACTIONS = List.of(
            "新增", "修改", "实现", "写入", "创建", "更新", "调整", "补齐", "改造", "生成", "增加",
            "修复", "变更", "替换", "编辑", "删除", "移除", "移动", "重命名", "迁移");
    private static final List<String> EXAMPLES = List.of(
            "只是示例", "仅作示例", "仅为示例", "例如", "举例", "比如");
    private static final Pattern ENGLISH_EXAMPLE = Pattern.compile(
            "(?i)(?:^|[\\s:：])(?:examples?|e\\.g\\.)(?:[\\s:：]|$)");
    private static final Pattern CHINESE_EXAMPLE_MARKER = Pattern.compile(
            "(?:^|[\\s:：])示例(?:[\\s:：]|$)");
    private static final Pattern NEGATED_ACTION = Pattern.compile(
            "(?:不得|禁止|不要|无需|无须|不需|不需要|不用|不必|不可|严禁|不应|不再|不准|不许|请勿|勿|不能|不允许|避免|防止|不)"
                    + "(?:\\s|应|要|得|可)*"
                    + "(?:" + String.join("|", ACTIONS) + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SCOPED_NEGATED_ACTION = Pattern.compile(
            "(?:不得|禁止|不要|无需|无须|不需|不需要|不用|不必|不可|严禁|不应|不再|不准|不许|请勿|勿|不能|不允许|避免|防止|不(?=对|在|向|往|给))"
                    + "(?:(?!同时|并且|但是|不过).){0,1024}?"
                    + "(?:" + String.join("|", ACTIONS) + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ENGLISH_NEGATED_ACTION = Pattern.compile(
            "(?i)\\b(?:do\\s+not|don't|must\\s+not|should\\s+not|no\\s+need\\s+to)\\b");
    private static final Pattern INVARIANT = Pattern.compile(
            "保持不变|范围外|不变(?!量|式)|(?:保持|维持)(?:(?![，,；;]).){0,512}?"
                    + "(?:[A-Za-z0-9._@+$-]+/|[A-Za-z0-9._@+$-]+\\.[A-Za-z0-9]{1,16})"
                    + "(?:(?![，,；;]).){0,64}?只读|"
                    + "(?:^|[\\s:：])只读(?:文件|目录|路径|范围)?(?=$|[\\s:：，,；;。])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private DesignerMutationPolarity() { }

    static boolean negative(String value) {
        String normalized = normalized(value);
        return NEGATED_ACTION.matcher(normalized).find()
                || SCOPED_NEGATED_ACTION.matcher(normalized).find()
                || ENGLISH_NEGATED_ACTION.matcher(normalized).find()
                || INVARIANT.matcher(normalized).find();
    }

    static boolean example(String value) {
        String normalized = normalized(value);
        return containsAny(normalized, EXAMPLES) || ENGLISH_EXAMPLE.matcher(normalized).find()
                || CHINESE_EXAMPLE_MARKER.matcher(normalized).find();
    }

    static boolean negativeOrExample(String value) {
        return negative(value) || example(value);
    }

    static String removeNegativeAndExampleMarkers(String value) {
        String result = normalized(value);
        result = NEGATED_ACTION.matcher(result).replaceAll(" ");
        result = SCOPED_NEGATED_ACTION.matcher(result).replaceAll(" ");
        result = ENGLISH_NEGATED_ACTION.matcher(result).replaceAll(" ");
        result = INVARIANT.matcher(result).replaceAll(" ");
        for (String marker : EXAMPLES) result = result.replace(marker.toLowerCase(Locale.ROOT), " ");
        result = ENGLISH_EXAMPLE.matcher(result).replaceAll(" ");
        result = CHINESE_EXAMPLE_MARKER.matcher(result).replaceAll(" ");
        return result;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, List<String> terms) {
        return terms.stream().map(term -> term.toLowerCase(Locale.ROOT)).anyMatch(value::contains);
    }
}
