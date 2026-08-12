package io.opencode.loopper.domain;

public enum VerifierRuntimeState {
    STARTING("正在启动"),
    RUNNING("正在运行"),
    STOPPING("正在停止"),
    STOPPED("已停止"),
    FAILED("启动或就绪失败"),
    DISCONNECTED("进程终止状态无法确认");

    private final String description;
    VerifierRuntimeState(String description) { this.description = description; }
    public String description() { return description; }
}
