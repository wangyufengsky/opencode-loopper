package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
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
        MachineCandidateKind kind = MachineCandidateKind.valueOf(launch.candidateKind());
        if (kind == MachineCandidateKind.PROJECT_CONVENTION_V1) {
            requireProjectConvention(launch);
            return;
        }
        if (kind != MachineCandidateKind.REVIEWER_REPORT_V1) return;
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

    private void requireProjectConvention(GenericCandidateInternalLaunchRow launch) {
        if (!"PROJECT_CONVENTION_DRAFT".equals(launch.ownerType())
                || !Objects.equals(launch.ownerId(), launch.projectConventionDraftId())
                || !"PROJECT_CONVENTION_V1".equals(launch.workflowStep())
                || !"PROJECT_CONVENTION_V1".equals(launch.contractVersion())
                || launch.maxAttempts() != MachineCandidateKind.PROJECT_CONVENTION_V1.maximumAttempts()) {
            throw conventionStale();
        }
        var owner = mapper.findProjectConventionDraft(launch.ownerId())
                .orElseThrow(GenericCandidateInternalLaunchPreIoGuard::conventionStale);
        var snapshot = mapper.findProjectConventionCandidateSourceSnapshot(launch.candidateRunId())
                .orElseThrow(GenericCandidateInternalLaunchPreIoGuard::conventionStale);
        boolean sourceAnchorMatches = snapshot.sourceContent() != null
                && !blank(snapshot.sourceContentSha256())
                && snapshot.sourceContentSha256().equals(sha256(snapshot.sourceContent()))
                && Objects.equals(owner.sourceSha256(), snapshot.sourceAgentsSha256())
                && Objects.equals(owner.sourceSha256(), snapshot.sourceContentSha256())
                && Objects.equals(owner.sourceContent(), snapshot.sourceContent())
                && owner.sourceExists() == snapshot.sourceExists();
        boolean stackAnchorMatches = Objects.equals(
                owner.projectStackProfileId(), snapshot.projectStackProfileId())
                && Objects.equals(owner.stackFingerprint(), snapshot.stackFingerprint());
        boolean evidenceAnchorMatches = !blank(snapshot.canonicalEvidenceJson())
                && !blank(snapshot.evidenceSha256())
                && snapshot.evidenceSha256().equals(sha256(snapshot.canonicalEvidenceJson()));
        if (!"RUNNING".equals(owner.state()) || owner.externalSessionId() != null
                || owner.version() != launch.preparedOwnerVersion()
                || !Objects.equals(launch.projectId(), owner.projectId())
                || owner.sourceRevision() == null
                || owner.sourceRevision() != launch.sourceRevision()
                || !"INTERNAL_MCP".equals(owner.responseMode())
                || !launch.candidateRunId().equals(snapshot.candidateRunId())
                || !launch.projectId().equals(snapshot.projectId())
                || !launch.ownerId().equals(snapshot.projectConventionDraftId())
                || launch.sourceRevision() != snapshot.sourceRevision()
                || launch.preparedOwnerVersion() != snapshot.preparedOwnerVersion()
                || !launch.contractVersion().equals(snapshot.contractVersion())
                || !sourceAnchorMatches || !stackAnchorMatches || !evidenceAnchorMatches) {
            throw conventionStale();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ConflictException stale() {
        return new ConflictException("REVIEWER_SOURCE_SNAPSHOT_PRE_IO_REQUIRED",
                "Reviewer launch requires its exact immutable source and requirement snapshot before OpenCode I/O");
    }

    private static ConflictException conventionStale() {
        return new ConflictException("PROJECT_CONVENTION_SOURCE_SNAPSHOT_PRE_IO_REQUIRED",
                "Convention launch requires its exact immutable source and evidence snapshot before OpenCode I/O");
    }
}
