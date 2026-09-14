package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DocumentTemplateRunRow;

/** Bridge into the existing Designer/Task engines; ownership and stopping remain with those engines. */
public interface DocumentDevelopmentFlow {
    void advance(DocumentTemplateRunRow run, DocumentTemplateService.Contract contract);
    default void observe(DocumentTemplateRunRow run) { }
    boolean stop(DocumentTemplateRunRow run, boolean cancel);
}
