package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperMapper;
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
}
