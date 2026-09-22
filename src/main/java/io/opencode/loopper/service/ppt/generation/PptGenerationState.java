package io.opencode.loopper.service.ppt.generation;

/** Automatic intent is independent of document, model and output job state. */
public enum PptGenerationState implements io.opencode.loopper.domain.DescribedEnum {
    PLANNING, PRODUCING, PREVIEW, EXPORT, WAITING_INPUT, STOPPING, STOPPED, FAILED, COMPLETED;
    public boolean terminal() { return this == STOPPED || this == FAILED || this == COMPLETED; }
    @Override public String description() { return switch(this) {
        case PLANNING -> "自动规划"; case PRODUCING -> "制作页面"; case PREVIEW -> "生成预览";
        case EXPORT -> "生成文件"; case WAITING_INPUT -> "等待回答"; case STOPPING -> "正在停止";
        case STOPPED -> "已停止"; case FAILED -> "需要重试"; case COMPLETED -> "生成完成";
    }; }
}
