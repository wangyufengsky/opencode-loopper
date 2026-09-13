package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TemplateTaskProgressRow;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TemplateProgressFlowTest {
    private TemplateTaskProgressRow row(Integer code,Integer people,int completed,int contributors,int round,String version) {
        return new TemplateTaskProgressRow(code,people,completed,contributors,0,0,round,null,"attempt",null,version,0);
    }
    @Test void completedAnalysisStillRequiresReportAndActualTaskCompletion() {
        var flow=TemplateProgressFlow.project(row(10,4,10,4,0,"7"),"RUNNING");
        assertThat(flow.currentPhase()).isEqualTo("REPORT");
        assertThat(flow.steps().getLast().state()).isEqualTo("PENDING");
        assertThat(flow.steps()).noneMatch(s->s.key().equals("REVIEW"));
        assertThat(TemplateProgressFlow.project(row(10,4,10,4,0,"7"),"COMPLETED").steps()).allMatch(s->s.state().equals("COMPLETE"));
    }
    @Test void interruptionAndRepairNeverInheritOldBatchSuccess() {
        var flow=TemplateProgressFlow.project(row(10,4,0,0,1,"7"),"RETRY_WAIT");
        assertThat(flow.currentPhase()).isEqualTo("RETRY_WAIT");
        assertThat(flow.steps()).anyMatch(s->s.key().equals("CODE")&&s.state().equals("INTERRUPTED"));
        assertThat(flow.steps().getLast().state()).isEqualTo("PENDING");
    }
    @Test void missingHistoryAndZeroBatchesRemainDistinct() {
        assertThat(TemplateProgressFlow.project(row(null,null,0,0,0,"7"),"COMPLETED").steps().getFirst().state()).isEqualTo("UNKNOWN");
        assertThat(TemplateProgressFlow.project(row(0,0,0,0,0,"7"),"RUNNING").currentPhase()).isEqualTo("REPORT");
        assertThat(TemplateProgressFlow.project(row(null,null,0,0,0,"7"),"PENDING_START").steps()).allMatch(s->s.state().equals("PENDING"));
    }
}
