package io.opencode.loopper.service.ppt;

import io.opencode.loopper.service.BestEffortEventSubscribers;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class PptEvents {
    public record Event(long sequence,String documentId,String type,String at) { }
    private final BestEffortEventSubscribers<String,Event> subscribers = new BestEffortEventSubscribers<>();
    private final AtomicLong sequence = new AtomicLong();
    public void publish(String id,String type) { subscribers.publish(id,new Event(sequence.incrementAndGet(),id,type,Instant.now().toString())); }
    public AutoCloseable subscribe(String id,Consumer<Event> consumer) { return subscribers.subscribe(id,consumer); }
}
