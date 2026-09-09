package io.opencode.loopper.service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Source-owned pending decisions; a candidate's gap label alone is never proof. */
final class DesignSourceDecisionPolicy {
    private static final Pattern UNDECIDED = Pattern.compile("尚未(?:决定|确定|确认)|尚待(?:业务)?决定|待(?:业务方|用户)确认|未定|not yet decided", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHOICE = Pattern.compile("还是|或(?:者)?|两种|选择|采用|whether|either", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOLVED = Pattern.compile("(?:已|已经)(?:决定|确定|确认)|不(?:再|存在).{0,8}(?:未定|待决)");
    private static final Pattern DELEGATED = Pattern.compile("(?:由|交给|让)(?:设计师|开发者|开发工程师|AI|模型).{0,32}(?:自行|自主|决定|选择)|(?:设计师|开发者|AI).{0,8}(?:自行|自主)(?:选择|决定)", Pattern.CASE_INSENSITIVE);

    private DesignSourceDecisionPolicy() { }

    static boolean pending(String sentence) {
        return UNDECIDED.matcher(sentence).find() && CHOICE.matcher(sentence).find()
                && !RESOLVED.matcher(sentence).find() && !delegated(sentence);
    }

    private static boolean delegated(String sentence) {
        var matcher = DELEGATED.matcher(sentence);
        while (matcher.find()) {
            String prefix = sentence.substring(0, matcher.start());
            if (!prefix.matches("(?s).*(?:不要|不得|禁止|不允许|不可|不能|无需|并非|不)[^，,。；;]{0,8}$")) return true;
        }
        return false;
    }

    static List<String> decisions(String source) {
        return Arrays.stream((source == null ? "" : source).split("[。；;\\n]"))
                .filter(DesignSourceDecisionPolicy::pending).toList();
    }

    static boolean multipleTaskBoundary(String source) {
        return Arrays.stream((source == null ? "" : source).split("[。；;\\n]"))
                .filter(sentence -> !DesignerMutationPolarity.negativeOrExample(sentence))
                .anyMatch(sentence -> sentence.matches("(?is).*(?:(?:多个|两个|不同|独立的?).{0,12}(?:项目根|仓库|发布边界)|(?:two|multiple|independent).{0,24}(?:project roots|repositories|release boundaries)).*"));
    }
}
