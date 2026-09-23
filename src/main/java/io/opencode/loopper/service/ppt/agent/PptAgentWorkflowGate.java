package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.persistence.PptAgentRows.Run;

/** Durable workflow admission. It never creates a model session or advances a document. */
public interface PptAgentWorkflowGate {
    record Authorization(String generationId, int attempt, String step, String mode, boolean requirementsConfirmed) {
        public Authorization(String generationId, int attempt, String step, String mode) {
            this(generationId, attempt, step, mode, false);
        }
    }
    record Answer(String question,String answer) { }
    void assertManualAdmission(String document);
    void validateAutomatic(String document, String key, Authorization authorization);
    void validateRun(Run run);
    java.util.List<Answer> answers(String document,Authorization authorization);
    default Object recoveryContext(Authorization authorization) { return java.util.Map.of(); }
    void cancel(String document);
}
