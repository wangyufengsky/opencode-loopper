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
        if (kind == MachineCandidateKind.JUDGE_DECISION_V1) {
            requireJudge(launch);
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

    private void requireJudge(GenericCandidateInternalLaunchRow launch) {
        var owner = mapper.findJudgeRun(launch.judgeRunId())
                .orElseThrow(GenericCandidateInternalLaunchPreIoGuard::judgeStale);
        var batch = owner.reviewBatchId() == null ? null
                : mapper.findJudgeReviewBatch(owner.reviewBatchId()).orElse(null);
        var snapshot = mapper.findJudgeCandidateSourceSnapshot(launch.candidateRunId())
                .orElseThrow(GenericCandidateInternalLaunchPreIoGuard::judgeStale);
        boolean sourceHash = !blank(snapshot.sourcePrompt()) && !blank(snapshot.sourcePromptSha256())
                && snapshot.sourcePromptSha256().equals(sha256(snapshot.sourcePrompt()));
        boolean evidenceHash = !blank(snapshot.canonicalEvidenceJson()) && !blank(snapshot.evidenceSha256())
                && snapshot.evidenceSha256().equals(sha256(snapshot.canonicalEvidenceJson()));
        if (!"JUDGE_RUN".equals(launch.ownerType()) || !Objects.equals(launch.ownerId(), owner.id())
                || !Objects.equals(launch.taskId(), owner.taskId())
                || !"JUDGE_DECISION_V1".equals(launch.workflowStep())
                || !"JUDGE_DECISION_V1".equals(launch.contractVersion())
                || launch.maxAttempts() != MachineCandidateKind.JUDGE_DECISION_V1.maximumAttempts()
                || !"CREATING".equals(owner.state()) || owner.externalSessionId() != null
                || owner.version() != launch.preparedOwnerVersion()
                || !"INTERNAL_MCP".equals(owner.responseMode()) || owner.sourceRevision() == null
                || owner.sourceRevision() != launch.sourceRevision() || batch == null
                || !"RUNNING".equals(batch.state()) || !owner.taskId().equals(batch.taskId())
                || !owner.attemptId().equals(batch.finalAttemptId())
                || !launch.candidateRunId().equals(snapshot.candidateRunId())
                || !owner.id().equals(snapshot.judgeRunId()) || !owner.taskId().equals(snapshot.taskId())
                || !batch.id().equals(snapshot.reviewBatchId())
                || !batch.executionCycleId().equals(snapshot.executionCycleId())
                || !owner.attemptId().equals(snapshot.finalAttemptId())
                || !owner.role().equals(snapshot.role()) || owner.ordinal() != snapshot.ordinal()
                || launch.sourceRevision() != snapshot.sourceRevision()
                || launch.preparedOwnerVersion() != snapshot.preparedOwnerVersion()
                || !launch.contractVersion().equals(snapshot.contractVersion())
                || !sourceHash || !evidenceHash) throw judgeStale();
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

    private static ConflictException judgeStale() {
        return new ConflictException("JUDGE_SOURCE_SNAPSHOT_PRE_IO_REQUIRED",
                "Judge launch requires its exact immutable batch, source, and evidence snapshot before OpenCode I/O");
    }
}
