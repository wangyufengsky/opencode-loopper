package io.opencode.loopper.service;

import io.opencode.loopper.domain.DescribedEnum;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Machine-candidate correction protocol. MCP submissions have no count limit;
 * the frozen maxAttempts field is retained for legacy repair budgets and run identity.
 * Policy evaluation is deterministic and occurs outside the short persistence transaction.
 */
public interface MachineCandidateSubmission {
    RunSnapshot open(OpenCommand command);
    SubmissionResult submit(SubmitCommand command);
    RunSnapshot close(CloseCommand command);
    Optional<RunSnapshot> find(String runId);
    Optional<SubmissionResult> terminal(String runId);

    record OpenCommand(
            String runId, CandidateScope scope, CandidateOwnerRef owner, MachineCandidateKind candidateKind,
            String workflowStep, long sourceRevision, long ownerVersion, SubmissionChannel submissionChannel,
            String contractVersion, String runtimeGenerationId, String externalSessionId, int maxAttempts) { }

    record SubmitCommand(String runId, String idempotencyKey, String candidateJson,
                         long expectedSubmissionRevision, SubmissionChannel submissionChannel) { }
    record CloseCommand(String runId, long expectedVersion, CandidateCloseReason reason) {
        public CloseCommand(String runId, long expectedVersion) {
            this(runId, expectedVersion, CandidateCloseReason.OWNER_REQUESTED);
        }
    }

    enum CandidateCloseReason implements DescribedEnum {
        NORMAL_COMPLETION_ZERO_SUBMISSION("远端正常完成且未提交候选"),
        INTERACTION_FORBIDDEN("模型请求了禁止的交互"),
        TIMEOUT("候选会话超时"),
        REMOTE_FAILED("候选会话远端失败"),
        OWNER_REQUESTED("拥有者显式关闭");

        private final String description;
        CandidateCloseReason(String description) { this.description = description; }
        @Override public String description() { return description; }
    }

    enum CandidateScopeType implements DescribedEnum {
        DESIGNER_SESSION("设计会话"),
        TASK("任务"),
        PROJECT("项目");

        private final String description;
        CandidateScopeType(String description) { this.description = description; }
        @Override public String description() { return description; }
    }

    record CandidateScope(CandidateScopeType type, String id) {
        public CandidateScope {
            Objects.requireNonNull(type, "type");
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Candidate scope id is required");
        }

        public static CandidateScope designerSession(String id) {
            return new CandidateScope(CandidateScopeType.DESIGNER_SESSION, id);
        }
        public static CandidateScope task(String id) { return new CandidateScope(CandidateScopeType.TASK, id); }
        public static CandidateScope project(String id) { return new CandidateScope(CandidateScopeType.PROJECT, id); }
    }

    enum CandidateOwnerType implements DescribedEnum {
        TASK_DECOMPOSITION("任务拆解"),
        LOOP_SPEC_COMPILATION("LoopSpec 编译"),
        DESIGN_WORK_PACKAGE("设计工作包"),
        TASK_PACKAGE_PLAN_REVISION("任务工作包计划修订"),
        ANALYSIS_REPORT("分析报告"),
        PROJECT_CONVENTION_DRAFT("项目公约草稿"),
        JUDGE_RUN("评审运行");

        private final String description;
        CandidateOwnerType(String description) { this.description = description; }
        @Override public String description() { return description; }
    }

    record CandidateOwnerRef(CandidateOwnerType type, String id) {
        public CandidateOwnerRef {
            Objects.requireNonNull(type, "type");
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Candidate owner id is required");
        }

        public static CandidateOwnerRef taskDecomposition(String id) {
            return new CandidateOwnerRef(CandidateOwnerType.TASK_DECOMPOSITION, id);
        }
        public static CandidateOwnerRef loopSpecCompilation(String id) {
            return new CandidateOwnerRef(CandidateOwnerType.LOOP_SPEC_COMPILATION, id);
        }
        public static CandidateOwnerRef designWorkPackage(String id) {
            return new CandidateOwnerRef(CandidateOwnerType.DESIGN_WORK_PACKAGE, id);
        }
        public static CandidateOwnerRef taskPackagePlanRevision(String id) {
            return new CandidateOwnerRef(CandidateOwnerType.TASK_PACKAGE_PLAN_REVISION, id);
        }
        public static CandidateOwnerRef analysisReport(String id) {
            return new CandidateOwnerRef(CandidateOwnerType.ANALYSIS_REPORT, id);
        }
        public static CandidateOwnerRef projectConventionDraft(String id) {
            return new CandidateOwnerRef(CandidateOwnerType.PROJECT_CONVENTION_DRAFT, id);
        }
        public static CandidateOwnerRef judgeRun(String id) {
            return new CandidateOwnerRef(CandidateOwnerType.JUDGE_RUN, id);
        }
    }

    record RunSnapshot(
            String runId, CandidateScope scope, CandidateOwnerRef owner, MachineCandidateKind candidateKind,
            String workflowStep, long sourceRevision, long ownerVersion, SubmissionChannel submissionChannel,
            String contractVersion, String runtimeGenerationId, String externalSessionId,
            MachineCandidateRunState state, int maxAttempts, int attemptsUsed, String terminalAttemptId, long version,
            CandidateCloseReason closeReason) {
        public RunSnapshot(
                String runId, CandidateScope scope, CandidateOwnerRef owner, MachineCandidateKind candidateKind,
                String workflowStep, long sourceRevision, long ownerVersion, SubmissionChannel submissionChannel,
                String contractVersion, String runtimeGenerationId, String externalSessionId,
                MachineCandidateRunState state, int maxAttempts, int attemptsUsed, String terminalAttemptId,
                long version) {
            this(runId, scope, owner, candidateKind, workflowStep, sourceRevision, ownerVersion, submissionChannel,
                    contractVersion, runtimeGenerationId, externalSessionId, state, maxAttempts, attemptsUsed,
                    terminalAttemptId, version, null);
        }
    }

    enum SubmissionChannel implements DescribedEnum {
        INTERNAL_MCP("内部 MCP"),
        IN_PROCESS_LEGACY("进程内兼容调用");

        private final String description;
        SubmissionChannel(String description) { this.description = description; }
        @Override public String description() { return description; }
    }

    record Problem(String code, String pointer, String detail, List<String> allowedValues) {
        public Problem {
            allowedValues = List.copyOf(Objects.requireNonNull(allowedValues, "allowedValues"));
        }

        public Problem(String code, String pointer, String detail) {
            this(code, pointer, detail, List.of());
        }
    }

    record SubmissionResult(
            String runId, MachineCandidateOutcome outcome, MachineCandidateRunState runState,
            int attemptOrdinal, Integer remainingAttempts, boolean retryable, List<Problem> problems,
            String canonicalResultSha256, long submissionRevision, String responseJson) {
        public SubmissionResult {
            problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        }
    }
}
