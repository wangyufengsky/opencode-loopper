package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateRepairPolicyTest {
    @Test void rejectsCandidateAuthorityWithoutEndingItsCorrectionOpportunity() {
        for (String code : List.of("PACKAGE_DESIGN_SECURITY_BOUNDARY", "PROJECT_CONVENTION_AUTHORITY_FIELD_FORBIDDEN",
                "ROLLING_PACKAGE_AUTHORITY_FIELD_FORBIDDEN", "JUDGE_DECISION_ROLE_MISMATCH",
                "JUDGE_DECISION_CONTRACT_VERSION_INVALID", "REVIEWER_EVIDENCE_PATH_UNSAFE", "PACKAGE_COMPILED_SCOPE_EXPANSION")) {
            var decision = CandidatePolicy.Decision.rejected(false, false, List.of(problem(code)));
            assertThat(decision.accepted()).as(code).isFalse();
            assertThat(decision.retryable()).as(code).isTrue();
            assertThat(decision.fallbackEligible()).as(code).isFalse();
            assertThat(decision.canonicalCandidateJson()).isNull();
        }
    }

    @Test void neverTurnsActualIdentityOrFrozenEvidenceFailuresIntoCandidateRepair() {
        assertThat(CandidatePolicy.Decision.rejected(false, List.of(problem("JUDGE_DECISION_ROLE_MISMATCH"),
                problem("JUDGE_DECISION_EVIDENCE_INVALID"))).retryable()).isTrue();
        for (String code : List.of("CANDIDATE_OWNER_REVISION_STALE", "PACKAGE_EVIDENCE_SOURCE_MISMATCH",
                "JUDGE_DECISION_INPUT_ROLE_INVALID", "PACKAGE_COMPILED_DELETE_PERMISSION", "PACKAGE_GAP_PROVEN_CONFLICT")) {
            assertThat(CandidatePolicy.Decision.rejected(false, List.of(problem(code))).retryable()).as(code).isFalse();
            assertThat(CandidatePolicy.Decision.rejected(false,
                    List.of(problem("PACKAGE_DESIGN_SECURITY_BOUNDARY"), problem(code))).retryable()).as(code).isFalse();
        }
    }

    @Test void userDelegatedChoiceIsNotAUserInputGap() {
        assertThat(DesignSourceDecisionPolicy.decisions("采用 JUnit 还是 TestNG 尚未确定，由设计师根据项目现状自行选择。")).isEmpty();
        assertThat(DesignSourceDecisionPolicy.decisions("采用退款还是补偿尚未确定，待业务方确认。")).hasSize(1);
        assertThat(DesignSourceDecisionPolicy.decisions("采用退款还是补偿尚未确定。测试由设计师自行选择。")).hasSize(1);
        assertThat(DesignSourceDecisionPolicy.decisions("采用退款还是补偿尚未确定，不能由AI自行选择。")).hasSize(1);
        assertThat(DesignSourceDecisionPolicy.multipleTaskBoundary("不要创建多个仓库。")).isFalse();
    }

    private MachineCandidateSubmission.Problem problem(String code) {
        return new MachineCandidateSubmission.Problem(code, "/candidate", "拒绝当前候选");
    }
}
