package io.opencode.loopper.service;

/** A specific repair location, shared by preflight and formal candidate validation. */
public final class DocumentCandidateProblem extends BadRequestException {
    private final String pointer;
    DocumentCandidateProblem(String pointer, String detail) {
        this("DOCUMENT_DIRECT_ASSESSMENT_INVALID", pointer, detail);
    }
    DocumentCandidateProblem(String code, String pointer, String detail) {
        super(code, detail); this.pointer = pointer;
    }
    public String pointer() { return pointer; }
}
