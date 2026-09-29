package io.opencode.loopper.workflow;
import io.opencode.loopper.domain.DescribedEnum;
public enum WorkflowPlanCandidateState implements DescribedEnum {
    PENDING("待确认"),APPLIED("已应用"),REJECTED("已退回");
    private final String description;
    WorkflowPlanCandidateState(String description){this.description=description;}
    @Override public String description(){return description;}
}
