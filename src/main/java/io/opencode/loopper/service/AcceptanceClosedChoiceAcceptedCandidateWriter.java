package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceBindingSource;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Short-transaction projection of an accepted v7 closed choice into the existing acceptance owners. */
final class AcceptanceClosedChoiceAcceptedCandidateWriter implements AcceptedCandidateWriter {
    private final LoopperDesignerMapper mapper;
    private final ObjectMapper json;

    AcceptanceClosedChoiceAcceptedCandidateWriter(LoopperDesignerMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7;
    }

    @Override
    public void write(CandidatePolicy.Context context, String canonicalCandidateJson,
                      String canonicalResultSha256) {
        LoopSpecCompilationRow owner = mapper.findLoopSpecCompilation(context.owner().loopSpecCompilationId())
                .orElseThrow(() -> new ConflictException(
                        "CANDIDATE_OWNER_MISSING", "LoopSpec compilation candidate owner no longer exists"));
        if (!context.designerSessionId().equals(owner.designerSessionId())
                || owner.version() != context.ownerVersion()
                || owner.designRevision() != context.sourceRevision()) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "LoopSpec compilation candidate owner revision has changed");
        }
        DesignAcceptancePlanningRow planning = mapper.findDesignAcceptancePlanning(owner.id())
                .orElseThrow(() -> new ConflictException(
                        "DESIGN_ACCEPTANCE_PLANNING_MISSING", "Frozen acceptance planning no longer exists"));
        if (!context.designerSessionId().equals(planning.designerSessionId())
                || planning.designRevision() != context.sourceRevision()
                || !DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(planning.contractVersion())
                || !AcceptanceBindingSource.AI_DISAMBIGUATION_V6.name().equals(planning.bindingSource())
                || planning.bindingJson() == null || planning.bindingJson().isBlank()
                || "BOUND".equals(planning.state())) {
            throw new ConflictException("CANDIDATE_ACCEPTANCE_SNAPSHOT_STALE",
                    "Frozen acceptance planning revision has changed");
        }

        String now = Instant.now().toString();
        DesignAcceptancePlanningRow bound = new DesignAcceptancePlanningRow(
                planning.compilationId(), planning.designerSessionId(), planning.workPackageId(),
                planning.designRevision(), planning.contractVersion(), planning.designSha256(), "BOUND",
                AcceptanceBindingSource.AI_DISAMBIGUATION_V6.name(), planning.factsJson(),
                planning.capabilitiesJson(), canonicalCandidateJson,
                diagnostics(planning.diagnosticsJson(), context.runId(), canonicalResultSha256),
                null, null, planning.createdAt(), now, planning.version());
        if (mapper.updateDesignAcceptancePlanning(bound) != 1) {
            throw new ConflictException("DESIGN_ACCEPTANCE_PLANNING_VERSION_CONFLICT",
                    "验收意图规划已被并发更新");
        }

        LoopSpecCompilationRow semantic = new LoopSpecCompilationRow(
                owner.id(), owner.designerSessionId(), owner.designRevision(), owner.state(),
                owner.externalSessionId(), owner.externalSessionState(), owner.repairCount(),
                owner.sourceDesignMessageId(), owner.sourceDraftVersion(), null, null,
                owner.createdAt(), now, owner.version(), owner.workPackageId(), owner.transportRetryCount(),
                owner.compiledPackageJson(), owner.workflowStep(), owner.planningJson(), owner.planningRepairCount(),
                owner.planningResponseMode(), owner.planningResponseSchemaId(), owner.planningFormatFallbackUsed(),
                owner.finalResponseMode(), owner.finalResponseSchemaId(), owner.finalFormatFallbackUsed(),
                canonicalCandidateJson, owner.formatRepairCount(), owner.semanticRepairCount(),
                owner.serverCompiled());
        if (mapper.updateLoopSpecCompilation(semantic) != 1) {
            throw new ConflictException("LOOPSPEC_COMPILATION_VERSION_CONFLICT",
                    "LoopSpec compilation was updated concurrently");
        }
    }

    private String diagnostics(String existingJson, String runId, String canonicalResultSha256) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try { values.putAll(json.readValue(existingJson, new TypeReference<Map<String, Object>>() { })); }
            catch (JacksonException invalid) {
                throw new ConflictException("DESIGN_ACCEPTANCE_DIAGNOSTICS_INVALID",
                        "冻结的验收规划诊断无法读取");
            }
        }
        values.put("candidateProtocol", AcceptanceClosedChoiceCandidateCoordinator.CONTRACT_VERSION);
        values.put("candidateRunId", runId);
        values.put("canonicalResultSha256", canonicalResultSha256);
        try { return json.writeValueAsString(values); }
        catch (JacksonException impossible) {
            throw new IllegalStateException("Unable to serialize acceptance candidate diagnostics", impossible);
        }
    }
}
