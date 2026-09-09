package io.opencode.loopper.service;

import java.util.regex.Pattern;

/** One publication subject contract for generated suggestions and submitted messages. */
final class CommitMessagePolicy {
    static final Pattern MESSAGE = Pattern.compile("^#[0-9]{4}_[^\\p{Cntrl}\\u2028\\u2029]{1,120}$");
    private CommitMessagePolicy() { }

    static String subject(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\p{Z}]+", " ").strip();
    }

    static String requireMessage(String value) {
        String message = value == null ? "" : value.strip();
        if (!message.matches("(?s)^#[0-9]{4}_.*")) {
            throw invalid("提交信息须以 #加4位数字和下划线开头，例如 #3032_修复任务发布流程");
        }
        String subject = subject(message.substring(6));
        if (subject.isBlank()) throw invalid("提交说明不能为空，请填写本次实际改动");
        if (subject.codePointCount(0, subject.length()) > 120) throw invalid("提交说明超过120个字符，请精简说明后提交");
        message = message.substring(0, 6) + subject;
        if (!MESSAGE.matcher(message).matches()) throw invalid("提交说明包含不支持的控制字符，请删除后提交");
        return message;
    }

    private static BadRequestException invalid(String detail) {
        return new BadRequestException("COMMIT_MESSAGE_INVALID", detail);
    }
}
