package io.opencode.loopper.workflow;

/** A confirmed local commit is independent of requirement and node results. */
public enum WorkflowPublicationState implements io.opencode.loopper.domain.DescribedEnum {
    CONFIRMED("正在保存本地提交"), BLOCKED("提交需处理"), COMMITTED("已保存本地提交");
    private final String description;
    WorkflowPublicationState(String description){this.description=description;}
    @Override public String description(){return description;}
}
