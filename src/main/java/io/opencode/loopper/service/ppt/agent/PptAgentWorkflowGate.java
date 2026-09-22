package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.persistence.PptAgentRows.Run;

/** Durable workflow admission. It never creates a model session or advances a document. */
public interface PptAgentWorkflowGate {
    record Authorization(String generationId, int attempt, String step, String mode) { }
    record Answer(String question,String answer) { }
    void assertManualAdmission(String document);
    void validateAutomatic(String document, String key, Authorization authorization);
    void validateRun(Run run);
    java.util.List<Answer> answers(String document,Authorization authorization);
    void cancel(String document);
}
