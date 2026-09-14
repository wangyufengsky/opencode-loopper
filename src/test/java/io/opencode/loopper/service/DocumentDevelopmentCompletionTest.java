package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class DocumentDevelopmentCompletionTest {
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final TaskReadService reads = mock(TaskReadService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final DocumentOriginalReadCoverage originalReads = mock(DocumentOriginalReadCoverage.class);
    private final DocumentDevelopmentCompletion completion = new DocumentDevelopmentCompletion(mapper, reads, json, originalReads);
    @BeforeEach void prepare() {
        when(originalReads.sessionComplete(anyString(), nullable(String.class))).thenReturn(true);
        when(mapper.findTask("task")).thenReturn(Optional.of(json.readValue("""
                {"id":"task","projectId":"project","state":"AWAITING_DECISION","version":8,"executionMode":"LEGACY_AGGREGATE"}
                """, TaskRow.class)));
        when(mapper.latestTaskExecutionCycle("task")).thenReturn(Optional.of(json.readValue("""
                {"id":"cycle","taskId":"task","ordinal":1,"state":"SUCCEEDED","version":3}
                """, TaskExecutionCycleRow.class)));
        when(mapper.listJudgeReviewBatches("task")).thenReturn(List.of(new JudgeReviewBatchRow("batch", "task", "cycle", "attempt", 1, "COMPLETED", "now", "now", "now", 2)));
        for (String role : List.of("REQUIREMENT", "RISK")) when(mapper.latestJudgeRunForBatchRole("batch", role)).thenReturn(Optional.of(judge(role, "PASS")));
        var overview = mock(TaskReadService.TaskOverview.class); var summary = mock(TaskReadService.StageSummary.class);
        when(summary.id()).thenReturn("stage"); when(overview.stages()).thenReturn(List.of(summary)); when(reads.overview("task")).thenReturn(overview);
        var stage = json.readValue("""
                {"id":"stage","taskId":"task","ordinal":0,"version":1,"state":"SUCCEEDED","objective":"付款权限回归",
                 "verifiersJson":"[{\\"type\\":\\"PROCESS\\",\\"processPurpose\\":\\"TEST\\",\\"testTargets\\":[\\"PaymentRegressionTest\\"]}]"}
                """, StageRow.class);
        when(mapper.findStage("stage")).thenReturn(Optional.of(stage));
        when(mapper.latestAttempt("stage")).thenReturn(Optional.of(new AttemptRow("attempt", "task", "stage", "cycle", 1, "SUCCEEDED", null, "通过", "now", "now", 1)));
        when(mapper.listVerifications("attempt")).thenReturn(List.of(new VerificationResultRow("verification", "attempt", 0, "PROCESS", "PASS", "付款权限回归通过", "{}", "now")));
    }
    @Test void recordsActualCurrentExecutionEvidenceAndKeepsResultDispositionSeparate() {
        var proof = completion.require("task");
        assertThat(proof.taskState()).isEqualTo("AWAITING_DECISION");
        assertThat(proof.judges()).extracting(DocumentDevelopmentCompletion.Judge::role).containsExactly("REQUIREMENT", "RISK");
        assertThat(proof.stages().getFirst().tests()).singleElement().satisfies(test -> {
            assertThat(test.id()).isEqualTo("verification"); assertThat(test.targets()).containsExactly("PaymentRegressionTest");
        });
        verify(mapper, never()).updateTaskState(any());
    }
    @Test void missingFailedOrDuplicateVerificationNeverBecomesPassed() {
        for (List<VerificationResultRow> results : List.of(Collections.<VerificationResultRow>emptyList(),
                List.of(new VerificationResultRow("failed", "attempt", 0, "PROCESS", "FAIL", "失败", "{}", "now")),
                List.of(new VerificationResultRow("a", "attempt", 0, "PROCESS", "PASS", "", "{}", "now"),
                        new VerificationResultRow("b", "attempt", 0, "PROCESS", "PASS", "", "{}", "now")))) {
            when(mapper.listVerifications("attempt")).thenReturn(results);
            assertThatThrownBy(() -> completion.require("task")).isInstanceOf(ConflictException.class);
        }
    }
    @Test void manualAcceptanceCannotTurnFailedJudgeIntoAutomaticApproval() {
        when(mapper.latestJudgeRunForBatchRole("batch", "RISK")).thenReturn(Optional.of(judge("RISK", "FAIL")));
        assertThatThrownBy(() -> completion.require("task")).isInstanceOf(ConflictException.class);
    }
    @Test void oldBatchOrUnfinishedLatestAttemptCannotCertifyCurrentWork() {
        when(mapper.listJudgeReviewBatches("task")).thenReturn(List.of(new JudgeReviewBatchRow("batch", "task", "previous-cycle", "attempt", 1, "COMPLETED", "now", "now", "now", 2)));
        assertThatThrownBy(() -> completion.require("task")).isInstanceOf(ConflictException.class);
        when(mapper.listJudgeReviewBatches("task")).thenReturn(List.of(new JudgeReviewBatchRow("batch", "task", "cycle", "attempt", 1, "COMPLETED", "now", "now", "now", 2)));
        when(mapper.latestAttempt("stage")).thenReturn(Optional.of(new AttemptRow("new-attempt", "task", "stage", "cycle", 2, "RUNNING", null, "", "now", null, 1)));
        assertThatThrownBy(() -> completion.require("task")).isInstanceOf(ConflictException.class);
    }
    private JudgeRunRow judge(String role, String verdict) {
        return new JudgeRunRow(role, "task", "attempt", role, 1, role + "-session", "COMPLETED", verdict, "独立结论", "", "now", "now", 1, "TEXT", null, "batch", 5L);
    }
}
