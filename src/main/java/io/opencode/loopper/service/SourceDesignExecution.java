package io.opencode.loopper.service;

import io.opencode.loopper.domain.SourceTemplateState;
import io.opencode.loopper.persistence.SourceTemplateRunRow;
import io.opencode.loopper.template.SourceTemplateContract;
import org.springframework.stereotype.Component;

@Component
public final class SourceDesignExecution implements SourceExecutionFlow {
    private final SourceTemplatePreparation preparation;
    private final SourceTemplateAdmission admission;
    private final SourceDesignFlow design;
    private final SourceDesignArtifacts artifacts;
    public SourceDesignExecution(SourceTemplatePreparation preparation, SourceTemplateAdmission admission,
            SourceDesignFlow design, SourceDesignArtifacts artifacts) {
        this.preparation = preparation; this.admission = admission; this.design = design; this.artifacts = artifacts;
    }
    @Override public boolean supports(String template) { return template.equals("DETAILED_DESIGN_WRITING"); }
    @Override public void advance(SourceTemplateRunRow run, SourceTemplateContract contract) {
        switch (run.state()) {
            case "PREPARING" -> admission.transition(preparation.freeze(run.id()), SourceTemplateState.WRITING, null, null, null);
            case "WRITING", "REVIEWING" -> design.advance(run, contract);
            case "REPORTING" -> artifacts.publish(run);
            default -> throw SourceTemplateAdmission.conflict();
        }
    }
    @Override public boolean stop(SourceTemplateRunRow run) { return true; }
}
