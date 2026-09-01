package io.opencode.loopper.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Process-local run barrier that closes the begin-I/O versus termination race. */
@Component
final class CandidatePromptDispatchBarrier {
    private final Map<String, State> runs = new HashMap<>();

    synchronized Ticket begin(String runId) {
        requireRunId(runId);
        State state = runs.computeIfAbsent(runId, ignored -> new State());
        if (state.terminating) return Ticket.rejected();
        state.active++;
        return new Ticket(this, runId, true);
    }

    synchronized boolean prepareTermination(String runId) {
        requireRunId(runId);
        State state = runs.computeIfAbsent(runId, ignored -> new State());
        state.terminating = true;
        return state.active == 0;
    }

    synchronized void complete(String runId) {
        requireRunId(runId);
        State state = runs.get(runId);
        if (state != null && state.active != 0) {
            throw new ConflictException("CANDIDATE_PROMPT_IO_IN_FLIGHT",
                    "Candidate prompt I/O is still active in this process");
        }
        runs.remove(runId);
    }

    private synchronized void release(String runId) {
        State state = runs.get(runId);
        if (state == null || state.active <= 0) throw new IllegalStateException("Dispatch barrier is stale");
        state.active--;
        if (state.active == 0 && !state.terminating) runs.remove(runId);
    }

    private static void requireRunId(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException();
    }

    static final class Ticket implements AutoCloseable {
        private final CandidatePromptDispatchBarrier owner;
        private final String runId;
        private final boolean acquired;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Ticket(CandidatePromptDispatchBarrier owner, String runId, boolean acquired) {
            this.owner = owner;
            this.runId = runId;
            this.acquired = acquired;
        }

        static Ticket rejected() { return new Ticket(null, null, false); }
        boolean acquired() { return acquired; }

        @Override public void close() {
            if (acquired && closed.compareAndSet(false, true)) owner.release(runId);
        }
    }

    private static final class State {
        private int active;
        private boolean terminating;
    }
}
