package io.opencode.loopper.domain;

public enum RetryCause {
    RATE_LIMIT("请求限流"),
    SESSION("会话错误"),
    VERIFICATION("验证失败");

    private final String description;

    RetryCause(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
