package io.opencode.loopper.domain;

public enum TaskPublicationState implements DescribedEnum {
    NOT_STARTED("尚未发布"), COMMITTED("已提交"), PUSHED("已推送"),
    MERGE_REQUEST_OPENED("合并请求已创建"), MERGE_REQUEST_CLOSED("合并请求已关闭"),
    MERGED("已合并"), LOCAL_COMPLETED("本地交付已完成"), NOT_APPLICABLE("不适用发布流程");

    private final String description;
    TaskPublicationState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this == MERGED || this == LOCAL_COMPLETED || this == NOT_APPLICABLE; }
}
