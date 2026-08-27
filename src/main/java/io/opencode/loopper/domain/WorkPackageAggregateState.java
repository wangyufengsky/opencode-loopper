package io.opencode.loopper.domain;

import java.util.Collection;

/** Public aggregate state shared by both Task DTO implementations. */
public enum WorkPackageAggregateState implements DescribedEnum {
    PENDING("工作包等待执行"), RUNNING("工作包执行中"), CANCELLED("工作包已取消"),
    SUCCEEDED("工作包已成功"), FAILED("工作包已失败");

    private final String description;
    WorkPackageAggregateState(String description) { this.description = description; }
    @Override public String description() { return description; }

    public static WorkPackageAggregateState aggregate(Collection<String> stageStates) {
        if (stageStates.stream().anyMatch(StageState.FAILED.name()::equals)) return FAILED;
        if (stageStates.stream().anyMatch(state -> StageState.RUNNING.name().equals(state)
                || StageState.PAUSED.name().equals(state))) return RUNNING;
        if (stageStates.stream().anyMatch(StageState.CANCELLED.name()::equals)) return CANCELLED;
        if (!stageStates.isEmpty() && stageStates.stream().allMatch(StageState.SUCCEEDED.name()::equals)) {
            return SUCCEEDED;
        }
        return PENDING;
    }
}
