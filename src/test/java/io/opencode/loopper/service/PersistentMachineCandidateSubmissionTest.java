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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

class PersistentMachineCandidateSubmissionTest {
    @ParameterizedTest
    @EnumSource(MachineCandidateKind.class)
    void everyMcpRoleReturnsTheCompleteCandidateDiagnosticV2EnvelopeBeyondItsFormerSubmissionCap(
            MachineCandidateKind kind) throws Exception {
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
                List.of(new MachineCandidateSubmission.Problem(
                        "VALUE_INVALID", "/contractVersion", "Use the active role contract version"))));
        var submissions = new PersistentMachineCandidateSubmission(mapper, mock(LifecycleTransitionService.class),
                JsonMapper.builder().build(), List.of(policy), List.of(), List.of());

        var result = submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run", "next", "{\"contractVersion\":\"WRONG\"}",
                row.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));

        assertThat(result.outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
        assertThat(result.runState()).isEqualTo(MachineCandidateRunState.OPEN);
        assertThat(result.retryable()).isTrue();
        assertThat(result.remainingAttempts()).isNull();
        assertThat(result.attemptOrdinal()).isEqualTo(kind.maximumAttempts() + 1);
        JsonNode response = JsonMapper.builder().build().readTree(result.responseJson());
        assertThat(response.path("diagnosticVersion").asText()).isEqualTo("CANDIDATE_DIAGNOSTIC_V2");
        assertThat(response.path("action").asText()).isEqualTo("FIX_AND_RESUBMIT");
        assertThat(response.path("diagnosticsComplete").asBoolean()).isTrue();
        assertThat(response.path("problemCount").asInt()).isGreaterThan(1);
        assertThat(response.path("returnedProblemCount").asInt())
                .isEqualTo(response.path("problemCount").asInt());
        assertThat(response.path("truncated").asBoolean()).isFalse();
        JsonNode valueProblem = java.util.stream.StreamSupport.stream(
                        response.path("problems").spliterator(), false)
                .filter(problem -> "VALUE_INVALID".equals(problem.path("code").asText()))
                .findFirst().orElseThrow();
        assertThat(valueProblem.path("parameter").asText()).isEqualTo("candidate");
        assertThat(valueProblem.path("category").asText()).isEqualTo("VALUE");
        assertThat(valueProblem.path("expected").asText()).isEqualTo("Use the active role contract version");
        assertThat(valueProblem.path("actual").asText()).isEqualTo("string \"WRONG\"");
        assertThat(valueProblem.path("repairHint").asText())
                .contains("/contractVersion", "Use the active role contract version");
        assertThat(valueProblem.toString()).doesNotContain(
                "value satisfying", "does not satisfy the declared contract", "Replace candidate");
    }

    @Test
    void trimsOversizedProblemSetsInsteadOfReplacingPreciseDiagnosticsWithAnInternalError() throws Exception {
        MachineCandidateKind kind = MachineCandidateKind.REVIEWER_REPORT_V1;
        var row = new CandidateSubmissionRunRow("run", "designer", null, null,
                "ANALYSIS_REPORT", "owner", kind.name(), kind.name(), 1, 1,
                "INTERNAL_MCP", kind.name(), "generation", "remote", "OPEN", kind.maximumAttempts(),
                0, null, "now", "now", 0);
        var mapper = mock(LoopperMapper.class);
        when(mapper.findCandidateSubmissionRun("run")).thenReturn(Optional.of(row));
        var policy = mock(CandidatePolicy.class);
        when(policy.supports(kind)).thenReturn(true);
        List<MachineCandidateSubmission.Problem> largeProblemSet = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            largeProblemSet.add(new MachineCandidateSubmission.Problem(
                    "PROBLEM_" + index, "/field" + index, "d".repeat(1000),
                    java.util.stream.IntStream.range(0, 32).mapToObj(value -> "v".repeat(200)).toList(),
                    "candidate", MachineCandidateSubmission.ProblemCategory.VALUE,
                    "e".repeat(1000), "a".repeat(1000), "r".repeat(1000)));
        }
        when(policy.evaluate(any(), anyString())).thenReturn(
                CandidatePolicy.Decision.rejected(true, false, largeProblemSet, true));
        var submissions = new PersistentMachineCandidateSubmission(mapper, mock(LifecycleTransitionService.class),
                JsonMapper.builder().build(), List.of(policy), List.of(), List.of());

        var result = submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                "run", "next", "{}", 0, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP));

        JsonNode response = JsonMapper.builder().build().readTree(result.responseJson());
        assertThat(response.path("diagnosticsComplete").asBoolean()).isFalse();
        assertThat(response.path("problemCount").isNull()).isTrue();
        assertThat(response.path("truncated").asBoolean()).isTrue();
        assertThat(response.path("returnedProblemCount").asInt()).isLessThan(64).isPositive();
        assertThat(result.responseJson().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(96 * 1024);
    }
}
