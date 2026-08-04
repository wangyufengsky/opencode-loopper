package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TaskEventRow;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class TaskEventHub {
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Consumer<TaskEventRow>>> subscribers = new ConcurrentHashMap<>();
    public AutoCloseable subscribe(String taskId, Consumer<TaskEventRow> consumer) {
        subscribers.computeIfAbsent(taskId, ignored -> new CopyOnWriteArraySet<>()).add(consumer);
        return () -> subscribers.getOrDefault(taskId, new CopyOnWriteArraySet<>()).remove(consumer);
    }
    public void publish(TaskEventRow event) {
        subscribers.getOrDefault(event.taskId(), new CopyOnWriteArraySet<>()).forEach(consumer -> consumer.accept(event));
    }
}
