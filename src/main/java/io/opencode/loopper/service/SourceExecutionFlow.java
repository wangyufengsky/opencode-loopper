package io.opencode.loopper.service;

import io.opencode.loopper.persistence.SourceTemplateRunRow;
import io.opencode.loopper.template.SourceTemplateContract;

/** Each source template owns its orchestration while shared Task and Session owners retain their lifecycles. */
public interface SourceExecutionFlow {
    boolean supports(String templateId);
    void advance(SourceTemplateRunRow run, SourceTemplateContract contract);
    boolean stop(SourceTemplateRunRow run);
}
