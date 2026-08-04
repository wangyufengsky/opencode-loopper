package io.opencode.loopper.domain;

/** A session-scoped problem. The orchestrator must close this session and may continue the task. */
public class SessionFailure extends RuntimeException {
    private final String code;
    public SessionFailure(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
