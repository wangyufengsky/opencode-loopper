package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.opencode.loopper.service.PackageDesignGapAssessment.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackageDesignGapAssessmentTest {
    private final PackageDesignGapAssessment classifier = new PackageDesignGapAssessment();

    @Test void aModelGapCodeDoesNotProveMissingUserSemantics() {
        var result = classifier.assess("MISSING_EXCEPTION_SEMANTICS", List.of());
        assertThat(result.category()).isEqualTo(Category.UNCONFIRMED);
        assertThat(result.retryable()).isTrue();
        assertThat(result.detail()).contains("尚未证实", "冻结需求来源");
    }
    @Test void aMissingCapabilityDoesNotProveARepositoryFact() {
        assertThat(classifier.assess("VERIFICATION_CAPABILITY_UNAVAILABLE", List.of()).category())
                .isEqualTo(Category.UNCONFIRMED);
        assertThat(assess(EvidenceKind.REPOSITORY_UNKNOWN).action()).isEqualTo(Action.COLLECT_EVIDENCE);
        assertThat(assess(EvidenceKind.PLANNED_TEST).action()).isEqualTo(Action.PLAN_TEST);
    }
    @Test void onlyTrustedDecisionEvidenceRoutesToTheUser() {
        var result = assess(EvidenceKind.USER_DECISION);
        assertThat(result.category()).isEqualTo(Category.BUSINESS_DECISION);
        assertThat(result.sourceRefs()).containsExactly("REQ-L1");
        assertThat(result.retryable()).isFalse();
    }
    @Test void provenConflictWinsOverRepairAndTestPlanning() {
        var result = classifier.assess("MISSING_SCOPE", List.of(
                evidence(EvidenceKind.PLANNED_TEST), evidence(EvidenceKind.CANDIDATE_FIELD),
                evidence(EvidenceKind.CONSTRAINT_CONFLICT)));
        assertThat(result.category()).isEqualTo(Category.PROVEN_CONFLICT);
        assertThat(result.action()).isEqualTo(Action.STOP_CONFLICT);
        assertThat(result.retryable()).isFalse();
    }
    @Test void deterministicReferenceFailuresRemainRepairable() {
        assertThat(classifier.assess("PACKAGE_DESIGN_REFERENCE_INVALID", List.of()).action())
                .isEqualTo(Action.REPAIR_CANDIDATE);
    }
    @Test void evidenceCannotBeAnUnattributedFlag() {
        assertThatThrownBy(() -> new Evidence(EvidenceKind.USER_DECISION, List.of(), "缺失"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private Assessment assess(EvidenceKind kind) { return classifier.assess("MISSING_SCOPE", List.of(evidence(kind))); }
    private Evidence evidence(EvidenceKind kind) { return new Evidence(kind, List.of("REQ-L1"), "冻结来源中的具体事实"); }
}
