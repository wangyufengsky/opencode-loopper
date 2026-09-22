package io.opencode.loopper.ppt;

/** Typed deterministic engine failure; transport maps the code to its problem response. */
public final class PptFailure extends RuntimeException {
    private final String code;
    public PptFailure(String code, String message) { super(message); this.code = code; }
    public PptFailure(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
}
