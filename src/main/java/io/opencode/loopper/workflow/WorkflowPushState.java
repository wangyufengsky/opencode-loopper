package io.opencode.loopper.workflow;
import io.opencode.loopper.domain.DescribedEnum;
public enum WorkflowPushState implements DescribedEnum {
    PREPARING("准备推送"),RUNNING("正在核对推送"),BLOCKED("推送需处理"),PUSHED("已确认推送");
    private final String description;
    WorkflowPushState(String description){this.description=description;}
    @Override public String description(){return description;}
}
