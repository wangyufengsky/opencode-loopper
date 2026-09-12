package io.opencode.loopper.service.assist;

import io.opencode.loopper.service.BadRequestException;

/** Safe, actionable auxiliary-tool diagnostics, without driver exceptions or credentials. */
public final class AssistFailure extends BadRequestException {
    private final String action;
    public AssistFailure(String code, String detail) { this(code, detail, "FIX_INPUT"); }
    public AssistFailure(String code, String detail, String action) { super(code, detail); this.action = action; }
    public String action() { return action; }
}
