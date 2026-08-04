package io.opencode.loopper.domain;

/** A non-recoverable task boundary violation or exhausted task budget. */
public class TaskFailure extends RuntimeException {
    private final String code;
    public TaskFailure(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
