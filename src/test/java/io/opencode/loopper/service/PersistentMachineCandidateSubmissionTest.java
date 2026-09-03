package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.CandidateSubmissionRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class PersistentMachineCandidateSubmissionTest {
    @ParameterizedTest
    @EnumSource(MachineCandidateKind.class)
    void everyMcpRoleMayCorrectBeyondItsFormerSubmissionCap(MachineCandidateKind kind) {
        String owner = switch (kind) {
            case DECOMPOSITION_PLAN_V2 -> "TASK_DECOMPOSITION";
            case ACCEPTANCE_CLOSED_CHOICE_V7 -> "LOOP_SPEC_COMPILATION";
            case PACKAGE_DESIGN_V1 -> "DESIGN_WORK_PACKAGE";
            case ROLLING_PACKAGE_PLAN_V1 -> "TASK_PACKAGE_PLAN_REVISION";
            case REVIEWER_REPORT_V1 -> "ANALYSIS_REPORT";
            case PROJECT_CONVENTION_V1 -> "PROJECT_CONVENTION_DRAFT";
            case JUDGE_DECISION_V1 -> "JUDGE_RUN";
        };
        boolean task = kind == MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1 || kind == MachineCandidateKind.JUDGE_DECISION_V1;
        boolean project = kind == MachineCandidateKind.PROJECT_CONVENTION_V1;
        var row = new CandidateSubmissionRunRow("run", task || project ? null : "designer",
                task ? "task" : null, project ? "project" : null, owner, "owner", kind.name(), kind.name(), 1, 1,
                "INTERNAL_MCP", kind.name(), "generation", "remote", "OPEN", kind.maximumAttempts(),
                kind.maximumAttempts(), null, "now", "now", kind.maximumAttempts());
        var mapper = mock(LoopperMapper.class);
        when(mapper.findCandidateSubmissionRun("run")).thenReturn(Optional.of(row));
        var policy = mock(CandidatePolicy.class);
        when(policy.supports(kind)).thenReturn(true);
        when(policy.evaluate(any(), anyString())).thenReturn(CandidatePolicy.Decision.rejected(true,
                kind == MachineCandidateKind.PACKAGE_DESIGN_V1,
                List.of(new MachineCandidateSubmission.Problem("VALUE_INVALID", "/value", "Correct the value"))));
        var submissions = new PersistentMachineCandidateSubmission(mapper, mock(LifecycleTransitionService.class),
                JsonMapper.builder().build(), List.of(policy), List.of(), List.of());

        var result = submissions.submit(new MachineCandidateSubmission.SubmitCommand("run", "next", "{}",
                row.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP));

        assertThat(result.outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
        assertThat(result.runState()).isEqualTo(MachineCandidateRunState.OPEN);
        assertThat(result.retryable()).isTrue();
        assertThat(result.remainingAttempts()).isNull();
        assertThat(result.attemptOrdinal()).isEqualTo(kind.maximumAttempts() + 1);
    }
}
