package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectConventionCandidateSourceSnapshotRow;
import io.opencode.loopper.persistence.ProjectConventionDraftRow;
import io.opencode.loopper.persistence.ReviewerReportSourceSnapshotRow;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GenericCandidateInternalLaunchPreIoGuardTest {
    @Test
    void reviewerRequiresExactRequirementAndSourceSnapshotBeforeRemoteReads() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        GenericCandidateInternalLaunchRow launch = mock(GenericCandidateInternalLaunchRow.class);
        AnalysisReportRow owner = mock(AnalysisReportRow.class);
        when(launch.candidateKind()).thenReturn("REVIEWER_REPORT_V1");
        when(launch.candidateRunId()).thenReturn("run-1");
        when(launch.designerSessionId()).thenReturn("designer-1");
        when(launch.analysisReportId()).thenReturn("report-1");
        when(launch.sourceRevision()).thenReturn(7L);
        when(launch.preparedOwnerVersion()).thenReturn(0L);
        when(launch.contractVersion()).thenReturn("REVIEWER_REPORT_V1");
        when(owner.state()).thenReturn("RUNNING");
        when(owner.version()).thenReturn(0L);
        when(owner.sourceRequirementRevision()).thenReturn(7);
        when(owner.sourceRequirement()).thenReturn("Review concurrency safety");
        when(owner.reviewerContractVersion()).thenReturn("REVIEWER_REPORT_V1");
        when(mapper.findAnalysisReport("designer-1", "report-1")).thenReturn(Optional.of(owner));
        GenericCandidateInternalLaunchPreIoGuard guard = new GenericCandidateInternalLaunchPreIoGuard(mapper);

        assertThatThrownBy(() -> guard.require(launch))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("REVIEWER_SOURCE_SNAPSHOT_PRE_IO_REQUIRED"));

        when(mapper.findReviewerReportSourceSnapshot("run-1")).thenReturn(Optional.of(
                new ReviewerReportSourceSnapshotRow("run-1", "report-1", 7, 0,
                        "REVIEWER_REPORT_V1", "[]", "a".repeat(64), "now")));
        guard.require(launch);

        when(owner.sourceRequirementRevision()).thenReturn(8);
        assertThatThrownBy(() -> guard.require(launch))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void conventionRequiresExactPreparedLaunchDraftAndEvidenceSnapshotBeforeRemoteReads() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        GenericCandidateInternalLaunchRow launch = mock(GenericCandidateInternalLaunchRow.class);
        ProjectConventionDraftRow owner = mock(ProjectConventionDraftRow.class);
        when(launch.candidateKind()).thenReturn("PROJECT_CONVENTION_V1");
        when(launch.candidateRunId()).thenReturn("convention-run");
        when(launch.projectId()).thenReturn("project-1");
        when(launch.ownerType()).thenReturn("PROJECT_CONVENTION_DRAFT");
        when(launch.ownerId()).thenReturn("draft-1");
        when(launch.projectConventionDraftId()).thenReturn("draft-1");
        when(launch.workflowStep()).thenReturn("PROJECT_CONVENTION_V1");
        when(launch.sourceRevision()).thenReturn(7L);
        when(launch.preparedOwnerVersion()).thenReturn(0L);
        when(launch.contractVersion()).thenReturn("PROJECT_CONVENTION_V1");
        when(launch.maxAttempts()).thenReturn(3);
        when(launch.state()).thenReturn("PREPARED");
        when(owner.projectId()).thenReturn("project-1");
        when(owner.state()).thenReturn("RUNNING");
        when(owner.version()).thenReturn(0L);
        when(owner.sourceRevision()).thenReturn(7L);
        when(owner.responseMode()).thenReturn("INTERNAL_MCP");
        when(owner.sourceExists()).thenReturn(1);
        when(owner.sourceSha256()).thenReturn("aef277fb6a70a89681a85e1b6d23f44ee2a6cc58490f9f5c95fc99db6d2d3542");
        when(owner.sourceContent()).thenReturn("# Project\n");
        when(owner.projectStackProfileId()).thenReturn("profile-1");
        when(owner.stackFingerprint()).thenReturn("c".repeat(64));
        when(mapper.findProjectConventionDraft("draft-1")).thenReturn(Optional.of(owner));
        ProjectConventionCandidateSourceSnapshotRow snapshot = conventionSnapshot(
                "project-1", "draft-1", 7, 0,
                "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
        when(mapper.findProjectConventionCandidateSourceSnapshot("convention-run"))
                .thenReturn(Optional.of(snapshot));
        GenericCandidateInternalLaunchPreIoGuard guard = new GenericCandidateInternalLaunchPreIoGuard(mapper);

        guard.require(launch);

        when(mapper.findProjectConventionCandidateSourceSnapshot("convention-run"))
                .thenReturn(Optional.empty());
        assertConventionPreIoRejected(guard, launch);
        when(mapper.findProjectConventionCandidateSourceSnapshot("convention-run"))
                .thenReturn(Optional.of(conventionSnapshot(
                        "project-2", "draft-1", 7, 0,
                        "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")));
        assertConventionPreIoRejected(guard, launch);
        when(mapper.findProjectConventionCandidateSourceSnapshot("convention-run"))
                .thenReturn(Optional.of(conventionSnapshot(
                        "project-1", "draft-2", 7, 0,
                        "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")));
        assertConventionPreIoRejected(guard, launch);
        when(mapper.findProjectConventionCandidateSourceSnapshot("convention-run"))
                .thenReturn(Optional.of(conventionSnapshot(
                        "project-1", "draft-1", 8, 0,
                        "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")));
        assertConventionPreIoRejected(guard, launch);
        when(mapper.findProjectConventionCandidateSourceSnapshot("convention-run"))
                .thenReturn(Optional.of(conventionSnapshot(
                        "project-1", "draft-1", 7, 1,
                        "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")));
        assertConventionPreIoRejected(guard, launch);
        when(mapper.findProjectConventionCandidateSourceSnapshot("convention-run"))
                .thenReturn(Optional.of(conventionSnapshot(
                        "project-1", "draft-1", 7, 0, "b".repeat(64))));
        assertConventionPreIoRejected(guard, launch);
        when(mapper.findProjectConventionCandidateSourceSnapshot("convention-run"))
                .thenReturn(Optional.of(snapshot));
        when(owner.version()).thenReturn(1L);
        assertConventionPreIoRejected(guard, launch);
    }

    private ProjectConventionCandidateSourceSnapshotRow conventionSnapshot(
            String projectId, String draftId, long sourceRevision,
            long preparedOwnerVersion, String evidenceSha256) {
        return new ProjectConventionCandidateSourceSnapshotRow(
                "convention-run", projectId, draftId, sourceRevision, preparedOwnerVersion,
                "PROJECT_CONVENTION_V1", 1,
                "aef277fb6a70a89681a85e1b6d23f44ee2a6cc58490f9f5c95fc99db6d2d3542",
                "# Project\n",
                "aef277fb6a70a89681a85e1b6d23f44ee2a6cc58490f9f5c95fc99db6d2d3542",
                "profile-1", "c".repeat(64), "{}", evidenceSha256, "now");
    }

    private void assertConventionPreIoRejected(
            GenericCandidateInternalLaunchPreIoGuard guard,
            GenericCandidateInternalLaunchRow launch) {
        assertThatThrownBy(() -> guard.require(launch))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("PROJECT_CONVENTION_SOURCE_SNAPSHOT_PRE_IO_REQUIRED"));
    }
}
