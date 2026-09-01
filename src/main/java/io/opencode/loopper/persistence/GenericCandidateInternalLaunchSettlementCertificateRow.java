package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Database-issued proof that a V57 launch and its exact run settled atomically. */
public record GenericCandidateInternalLaunchSettlementCertificateRow(
        String launchId, String candidateRunId, long settledOwnerVersion, String settledAt) {
    @AutomapConstructor public GenericCandidateInternalLaunchSettlementCertificateRow { }
}
