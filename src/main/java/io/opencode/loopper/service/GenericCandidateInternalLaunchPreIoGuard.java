package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperMapper;
import org.springframework.stereotype.Component;

/** Enforces role-owned frozen facts before the generic coordinator performs any OpenCode I/O. */
@Component
final class GenericCandidateInternalLaunchPreIoGuard {
    private final LoopperMapper mapper;

    GenericCandidateInternalLaunchPreIoGuard(LoopperMapper mapper) {
        this.mapper = mapper;
    }

    void require(GenericCandidateInternalLaunchRow launch) {
        if (launch == null) throw stale();
        if (MachineCandidateKind.valueOf(launch.candidateKind()) != MachineCandidateKind.REVIEWER_REPORT_V1) {
            return;
        }
        var owner = mapper.findAnalysisReport(launch.designerSessionId(), launch.analysisReportId())
                .orElseThrow(GenericCandidateInternalLaunchPreIoGuard::stale);
        var snapshot = mapper.findReviewerReportSourceSnapshot(launch.candidateRunId())
                .orElseThrow(GenericCandidateInternalLaunchPreIoGuard::stale);
        if (!"RUNNING".equals(owner.state()) || owner.externalSessionId() != null
                || owner.version() != launch.preparedOwnerVersion()
                || owner.sourceRequirementRevision() == null
                || owner.sourceRequirementRevision() != launch.sourceRevision()
                || owner.sourceRequirement() == null || owner.sourceRequirement().isBlank()
                || !launch.contractVersion().equals(owner.reviewerContractVersion())
                || !launch.candidateRunId().equals(snapshot.candidateRunId())
                || !launch.analysisReportId().equals(snapshot.analysisReportId())
                || launch.sourceRevision() != snapshot.sourceRevision()
                || launch.preparedOwnerVersion() != snapshot.preparedOwnerVersion()
                || !launch.contractVersion().equals(snapshot.contractVersion())) throw stale();
    }

    private static ConflictException stale() {
        return new ConflictException("REVIEWER_SOURCE_SNAPSHOT_PRE_IO_REQUIRED",
                "Reviewer launch requires its exact immutable source and requirement snapshot before OpenCode I/O");
    }
}
