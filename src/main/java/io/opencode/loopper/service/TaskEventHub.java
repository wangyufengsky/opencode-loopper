package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TaskEventRow;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class TaskEventHub {
    private final BestEffortEventSubscribers<String, TaskEventRow> subscribers = new BestEffortEventSubscribers<>();
    public AutoCloseable subscribe(String taskId, Consumer<TaskEventRow> consumer) {
        return subscribers.subscribe(taskId, consumer);
    }
    public void publish(TaskEventRow event) {
        subscribers.publish(event.taskId(), event);
    }
}
