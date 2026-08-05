package io.opencode.loopper.runtime;

import java.nio.file.Path;
import java.util.List;

public interface OpenCodeClient {
    boolean healthy();
    OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model);
    /** Creates a session whose permission rules deny all mutation and shell/task execution. */
    OpenCodeSession createReadOnlySession(Path worktree, String title, OpenCodeModel model);
    void promptAsync(OpenCodeSession session, String prompt);
    SessionStatus sessionStatus(OpenCodeSession session);
    /** Returns the latest assistant text after a completed session, preserving the original model response. */
    String sessionOutput(OpenCodeSession session);
    /** Returns provider-exposed incremental assistant parts for the live local monitoring UI. */
    SessionTranscript sessionTranscript(OpenCodeSession session);
    String diff(OpenCodeSession session);
    void abort(OpenCodeSession session);
    record OpenCodeSession(String id, Path worktree) { }
    record OpenCodeModel(String providerId, String modelId, Boolean thinking) { }
    record SessionTranscript(List<SessionPart> parts) {
        public SessionTranscript { parts = parts == null ? List.of() : List.copyOf(parts); }
    }
    record SessionPart(String id, String type, String label, String content, String status, String startedAt) {
        public SessionPart(String id, String type, String label, String content, String status) {
            this(id, type, label, content, status, null);
        }
    }
    record SessionStatus(String state, String detail) {
        public SessionStatus(String state) { this(state, null); }
        public boolean completed() { return "COMPLETED".equalsIgnoreCase(state) || "IDLE".equalsIgnoreCase(state) || "DONE".equalsIgnoreCase(state); }
        public boolean failed() { return "FAILED".equalsIgnoreCase(state) || "ERROR".equalsIgnoreCase(state) || "ABORTED".equalsIgnoreCase(state)
                || "TIMED_OUT".equalsIgnoreCase(state) || "RETRY".equalsIgnoreCase(state); }
    }
}
