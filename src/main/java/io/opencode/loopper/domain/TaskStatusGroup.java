package io.opencode.loopper.domain;

import java.util.EnumSet;
import java.util.Set;

/** Public, server-owned grouping used by Task list filters and facets. */
public enum TaskStatusGroup implements DescribedEnum {
    PROCESSING("处理中", EnumSet.complementOf(EnumSet.of(
            TaskState.COMPLETED, TaskState.SUPERSEDED, TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED))),
    SUCCESSFUL("已成功", EnumSet.of(TaskState.COMPLETED, TaskState.SUCCEEDED)),
    TERMINATED("已终止", EnumSet.of(TaskState.FAILED, TaskState.CANCELLED));

    private final String description;
    private final Set<TaskState> states;

    TaskStatusGroup(String description, Set<TaskState> states) {
        this.description = description;
        this.states = Set.copyOf(states);
    }

    @Override public String description() { return description; }
    public Set<TaskState> states() { return states; }
}
