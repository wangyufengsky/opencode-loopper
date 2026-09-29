package io.opencode.loopper.workflow;
import io.opencode.loopper.domain.DescribedEnum;
public enum WorkflowControlState implements DescribedEnum {
    ACTIVE("继续调度"), PAUSED("暂停调度"), WAITING("等待人工确认"), STALLED("需要处理"), DONE("执行结束");
    private final String description;
    WorkflowControlState(String description) { this.description=description; }
    @Override public String description() { return description; }
}
