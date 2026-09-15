package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Authorizes contract discovery against the same frozen owner and runtime as submission. */
@Service
public class SubmissionContractReadService {
    private final MachineCandidateSubmission submissions;
    private final List<CandidateRunGuard> guards;
    private final TemplateCandidateSubmissionService templates;

    SubmissionContractReadService(MachineCandidateSubmission submissions, List<CandidateRunGuard> guards,
            TemplateCandidateSubmissionService templates) {
        this.submissions = submissions; this.guards = guards; this.templates = templates;
    }

    public Contract describe(String runId) {
        var run = submissions.find(runId).orElse(null);
        if (run == null) return templates.contract(runId);
        if (run.state() != MachineCandidateRunState.OPEN)
            throw new ConflictException("CANDIDATE_CONTRACT_CLOSED", "候选运行已结束，不再接受新的提交");
        if (guards.isEmpty()) throw new ConflictException("CANDIDATE_GUARD_REQUIRED", "候选身份校验不可用");
        guards.forEach(guard -> guard.validate(run, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP));
        String tool = "PACKAGE_DESIGN_V2".equals(run.contractVersion()) ? InternalMcpContractCatalog.PACKAGE_V2_TOOL
                : InternalMcpContractCatalog.toolName(run.candidateKind());
        return new Contract(tool, run.contractVersion(), run.version(), Map.of(
                "sourceRevision", run.sourceRevision(), "attemptsUsed", run.attemptsUsed(),
                "candidateKind", run.candidateKind().name(), "workflowStep", run.workflowStep()));
    }

    public record Contract(String toolName, String contractVersion, long expectedSubmissionRevision,
                           Map<String, Object> context) { }
}
