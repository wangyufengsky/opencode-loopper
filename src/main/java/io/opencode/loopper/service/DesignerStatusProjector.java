package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.persistence.CandidateSubmissionRunRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Projects Designer machine-role usage and package status from persisted authority. */
final class DesignerStatusProjector {
    private final LoopperMapper mapper;
    private final ObjectMapper json;
    private final DesignerAcceptanceWorkflow acceptanceWorkflow;

    DesignerStatusProjector(LoopperMapper mapper, ObjectMapper json,
                            DesignerAcceptanceWorkflow acceptanceWorkflow) {
        this.mapper = mapper;
        this.json = json;
        this.acceptanceWorkflow = acceptanceWorkflow;
    }

    DesignerSessionService.CompilerStatus compiler(String sessionId) {
        return mapper.findLatestLoopSpecCompilation(sessionId)
                .map(row -> new DesignerSessionService.CompilerStatus(
                        row.id(), row.state(), row.externalSessionId(), row.externalSessionState(),
                        row.repairCount(), row.designRevision(), row.lastErrorCode(), row.lastErrorDetail(),
                        row.workPackageId(), row.workflowStep(), row.planningRepairCount(),
                        row.formatRepairCount(), row.semanticRepairCount(), row.serverCompiled(),
                        mapper.countCandidateSubmissionRunsForCompilation(row.id()),
                        mapper.countCandidateSubmissionAttemptsForCompilation(row.id())))
                .orElse(null);
    }

    DesignerSessionService.DecompositionStatus decomposition(String sessionId) {
        return mapper.findLatestTaskDecomposition(sessionId)
                .map(row -> new DesignerSessionService.DecompositionStatus(
                        row.id(), row.state(), row.resultType(), row.repairCount(), row.transportRetryCount(),
                        row.lastErrorCode(), row.lastErrorDetail(), row.workflowStep(), row.planningRepairCount(),
                        row.formatRepairCount(), row.semanticRepairCount(), row.serverCompiled(),
                        mapper.countCandidateSubmissionRunsForDecomposition(row.id()),
                        mapper.countCandidateSubmissionAttemptsForDecomposition(row.id())))
                .orElse(null);
    }

    List<DesignerSessionService.WorkPackageStatus> workPackages(String sessionId) {
        DesignRequirementRevisionRow revision = mapper
                .findCurrentDesignRequirementRevision(sessionId).orElse(null);
        if (revision == null) return List.of();
        return mapper.listDesignWorkPackages(revision.id()).stream().map(row -> {
            LoopSpecCompilationRow compiler = mapper
                    .findLatestLoopSpecCompilationForPackage(sessionId, row.packageId()).orElse(null);
            long candidateSourceRevision = compiler == null
                    ? (long) row.designRevision() + 1L
                    : row.designRevision();
            CandidateSubmissionRunRow candidateRun = mapper
                    .findLatestCandidateSubmissionRunForWorkPackage(row.id(), candidateSourceRevision)
                    .orElse(null);
            boolean reuse = mapper.designerConversationsEnabled(sessionId);
            int candidateSubmissions = reuse ? mapper.designerPackageCandidateSubmissions(row.id()) : candidateRun == null ? 0
                    : mapper.countCandidateSubmissionAttemptsForRun(candidateRun.id());
            WorkPackageRoleService.View role = mapper.findWorkPackageRoleProfile(row.id())
                    .map(stored -> new WorkPackageRoleService.View(
                            stored.rolePackId(), stored.rolePackVersion(),
                            ExecutionStrategy.valueOf(stored.executionStrategy()),
                            TestPolicy.valueOf(stored.testPolicy()), strings(stored.technologiesJson())))
                    .orElse(null);
            return new DesignerSessionService.WorkPackageStatus(
                    row.packageId(), row.ordinal(), row.title(), row.objective(), row.state(),
                    strings(row.dependenciesJson()), row.redesignCount(),
                    compiler == null ? 0 : compiler.repairCount(),
                    compiler == null ? 0 : compiler.planningRepairCount(),
                    compiler == null ? 0 : compiler.formatRepairCount(),
                    compiler == null ? 0 : compiler.semanticRepairCount(),
                    compiler != null && compiler.serverCompiled(), row.compilerSummary(), row.handoffSummary(),
                    row.lastErrorCode(), row.lastErrorDetail(), row.designRevision(), row.approvedDesignRevision(),
                    row.discussionRoundCount(), row.invalidatedByPackageId(), row.approvedAt(),
                    role == null ? null : role.rolePackId(), role == null ? null : role.rolePackVersion(),
                    role == null ? null : role.executionStrategy().name(),
                    role == null ? null : role.testPolicy().name(),
                    role == null ? List.of() : role.technologies(), acceptanceWorkflow.status(compiler),
                    candidateRun == null ? null : candidateRun.state(), reuse ? mapper.designerPackageCandidateSessions(row.id()) : candidateRun == null ? 0 : 1,
                    candidateSubmissions, compiler == null ? null : compiler.compilationSource(),
                    compiler != null && "MARKDOWN_FALLBACK".equals(compiler.compilationSource())
                            ? compiler.fallbackReason() : null,
                    compiler != null && compiler.serverCompiled());
        }).toList();
    }

    private List<String> strings(String source) {
        try {
            return json.readValue(source == null || source.isBlank() ? "[]" : source,
                    new TypeReference<List<String>>() { });
        } catch (JacksonException invalid) {
            return List.of();
        }
    }
}
