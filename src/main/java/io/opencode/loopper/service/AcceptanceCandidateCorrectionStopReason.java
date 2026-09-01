package io.opencode.loopper.service;

enum AcceptanceCandidateCorrectionStopReason {
    BUDGET_EXHAUSTED("WORK_PACKAGE_MODEL_CALL_LIMIT"),
    LOOKUP_UNSUPPORTED("DESIGN_INCOMPLETE");

    private final String finalCode;

    AcceptanceCandidateCorrectionStopReason(String finalCode) {
        this.finalCode = finalCode;
    }

    String finalCode() {
        return finalCode;
    }

    static AcceptanceCandidateCorrectionStopReason parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ConflictException("ACCEPTANCE_CORRECTION_MARKER_INVALID",
                    "验收候选修正停止原因不属于服务端闭集");
        }
    }
}
