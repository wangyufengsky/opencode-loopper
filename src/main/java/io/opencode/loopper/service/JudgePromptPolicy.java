package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.TaskFailure;
import java.nio.charset.StandardCharsets;

/** Shared, byte-accurate bounds for the confirmed Judge contract and runtime prompt. */
final class JudgePromptPolicy {
    static final int MAX_CONTRACT_UTF8_BYTES = 96 * 1024;
    static final int MAX_PROMPT_UTF8_BYTES = 128 * 1024;
    static final int MAX_EVIDENCE_EXCERPT_UTF8_BYTES = 4 * 1024;

    private JudgePromptPolicy() { }

    static String contract(LoopSpec spec) {
        return "已确认目标：" + text(spec.goal()) + "\n上下文：" + text(spec.context())
                + "\n跨阶段 AI 验收合同：\n" + criteria(spec);
    }

    static String criteria(LoopSpec spec) {
        StringBuilder result = new StringBuilder();
        for (int stageIndex = 0; stageIndex < spec.stages().size(); stageIndex++) {
            LoopSpec.StageSpec stage = spec.stages().get(stageIndex);
            for (LoopSpec.AcceptanceCriterion criterion : stage.acceptanceCriteria()) {
                String mode = text(criterion.verificationMode());
                if (!("JUDGE".equals(mode) || "BOTH".equals(mode))) continue;
                result.append("- 阶段 ").append(stageIndex + 1).append("（")
                        .append(text(stage.objective())).append("） ").append(text(criterion.id()))
                        .append(" [").append(mode).append("]: ")
                        .append(text(criterion.description())).append("\n  评审准则：")
                        .append(text(criterion.judgeRubric()));
                if ("JUDGE".equals(mode)) {
                    result.append("\n  仅 AI 评审原因：").append(text(criterion.judgeOnlyReason()));
                }
                result.append('\n');
            }
        }
        return result.isEmpty()
                ? "- 此草案没有显式 JUDGE/BOTH 条件；按兼容规则评审整体需求与风险。"
                : result.toString().stripTrailing();
    }

    static int utf8Bytes(String value) {
        return text(value).getBytes(StandardCharsets.UTF_8).length;
    }

    static void requirePromptWithinBudget(String prompt) {
        int actual = utf8Bytes(prompt);
        if (actual > MAX_PROMPT_UTF8_BYTES) {
            throw new TaskFailure("JUDGE_PROMPT_BUDGET_EXCEEDED",
                    "Judge prompt is " + actual + " UTF-8 bytes; maximum is " + MAX_PROMPT_UTF8_BYTES
                            + ". Reduce the confirmed Judge contract or deterministic evidence before retrying review");
        }
    }

    static String evidenceExcerpt(String evidence) {
        String value = text(evidence);
        if (utf8Bytes(value) <= MAX_EVIDENCE_EXCERPT_UTF8_BYTES) return value;
        int end = 0;
        int bytes = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > MAX_EVIDENCE_EXCERPT_UTF8_BYTES) break;
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private static String text(String value) { return value == null ? "" : value; }
}
