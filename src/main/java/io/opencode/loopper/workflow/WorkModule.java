package io.opencode.loopper.workflow;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.Objects;

/** A versioned work definition selects a role binding and protocol; it is not a role or a run. */
public record WorkModule(String id, int version, String title, Family family, String roleBinding,
                         String inputContract, MachineCandidateKind resultContract, Completion completion) {
    public WorkModule {
        if (id == null || !id.matches("[a-z][a-z0-9.-]{0,79}") || version < 1)
            throw new IllegalArgumentException("Invalid work module identity");
        Objects.requireNonNull(title);
        Objects.requireNonNull(family);
        Objects.requireNonNull(roleBinding);
        Objects.requireNonNull(inputContract);
        Objects.requireNonNull(resultContract);
        Objects.requireNonNull(completion);
    }
    public enum Family { SOURCE_DESIGN, DOCUMENT_ANALYSIS }
    /** A valid REVISE verdict completes review work; the enclosing flow decides the next step. */
    public enum Completion { ACCEPTED_RESULT_AND_STOPPED }
}
