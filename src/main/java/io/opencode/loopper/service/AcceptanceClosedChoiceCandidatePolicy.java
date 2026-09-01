package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.AcceptanceBindingSource;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Database-read-only v7 policy; only a mechanical selection miss is retryable. */
final class AcceptanceClosedChoiceCandidatePolicy implements CandidatePolicy {
    static final String SELECTION_INVALID = "ACCEPTANCE_CANDIDATE_SELECTION_INVALID";
    static final String SECURITY_BOUNDARY = "ACCEPTANCE_CANDIDATE_SECURITY_BOUNDARY";
    static final String CONTRACT_INVALID = "ACCEPTANCE_CANDIDATE_CONTRACT_INVALID";
    static final String NOT_ENUMERABLE = "ACCEPTANCE_CANDIDATE_NOT_ENUMERABLE";

    private final LoopperDesignerMapper mapper;
    private final ObjectMapper json;
    private final DesignerAcceptanceFastPathResolver resolver = new DesignerAcceptanceFastPathResolver();
    private final DesignerClosedChoiceContract contract;

    AcceptanceClosedChoiceCandidatePolicy(LoopperDesignerMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
        this.contract = new DesignerClosedChoiceContract(json, new AiOutputExtractor(json));
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7;
    }

    @Override
    public Decision evaluate(Context context, String candidateJson) {
        LoopSpecCompilationRow owner = mapper.findLoopSpecCompilation(context.owner().id())
                .orElseThrow(() -> new ConflictException(
                        "CANDIDATE_OWNER_MISSING", "LoopSpec compilation candidate owner no longer exists"));
        if (!context.scope().id().equals(owner.designerSessionId())
                || !"RUNNING".equals(owner.state())
                || !AcceptanceCandidateOwnerCheckpoint.openVersionMatches(context.ownerVersion(), owner)
                || owner.designRevision() != context.sourceRevision()) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "LoopSpec compilation candidate owner revision has changed");
        }
        if (context.candidateKind() != MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7
                || !AcceptanceClosedChoiceCandidateCoordinator.WORKFLOW_STEP.equals(context.workflowStep())
                || !AcceptanceClosedChoiceCandidateCoordinator.CONTRACT_VERSION.equals(context.contractVersion())
                || context.maxAttempts() != AcceptanceClosedChoiceCandidateCoordinator.MAX_ATTEMPTS) {
            return rejected(NOT_ENUMERABLE, "/", "候选运行不属于 v7 验收闭集协议", List.of());
        }
        DesignAcceptancePlanningRow planning = mapper.findDesignAcceptancePlanning(owner.id())
                .orElseThrow(() -> new ConflictException(
                        "DESIGN_ACCEPTANCE_PLANNING_MISSING", "Frozen acceptance planning no longer exists"));
        if (!owner.id().equals(planning.compilationId())
                || !context.scope().id().equals(planning.designerSessionId())
                || planning.designRevision() != context.sourceRevision()
                || !DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(planning.contractVersion())
                || !AcceptanceBindingSource.AI_DISAMBIGUATION_V6.name().equals(planning.bindingSource())
                || planning.bindingJson() == null || planning.bindingJson().isBlank()) {
            throw new ConflictException("CANDIDATE_ACCEPTANCE_SNAPSHOT_STALE",
                    "Frozen acceptance planning revision or persisted routing has changed");
        }
        DesignerAcceptancePlanning.Catalog facts = read(
                planning.factsJson(), DesignerAcceptancePlanning.Catalog.class,
                "DESIGN_ACCEPTANCE_FACTS_INVALID", "冻结的验收事实无法读取");
        DesignerAcceptancePlanning.CapabilityCatalog capabilities = read(
                planning.capabilitiesJson(), DesignerAcceptancePlanning.CapabilityCatalog.class,
                "DESIGN_ACCEPTANCE_CAPABILITIES_INVALID", "冻结的验收能力无法读取");
        DesignerAcceptanceFastPathResolver.Resolution resolution = resolver.resolve(facts, capabilities);
        DesignerAcceptanceWorkflow.RoutingResult routing = new DesignerAcceptanceWorkflow.RoutingResult(
                resolution, resolution.outcome() == DesignerAcceptanceFastPathResolver.Outcome.RESOLVED,
                resolution.outcome() == DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER);
        if (!AcceptanceClosedChoiceCandidateCoordinator.exactTrueTie(routing)) {
            return rejected(NOT_ENUMERABLE, "/", "冻结验收规划不是可枚举的真实同分闭集", List.of());
        }

        DesignerClosedChoiceContract.CandidateBoundary boundary = contract.inspectCandidateBoundary(candidateJson);
        if (boundary == DesignerClosedChoiceContract.CandidateBoundary.SECURITY_BOUNDARY) {
            return rejected(SECURITY_BOUNDARY, "/candidate",
                    "候选包含路径、权限、安全、执行或拓扑字段", List.of());
        }
        if (boundary == DesignerClosedChoiceContract.CandidateBoundary.CONTRACT_INVALID) {
            return rejected(CONTRACT_INVALID, "/candidate",
                    "候选不是 ACCEPTANCE_CLOSED_CHOICE_V7 的 JSON object 合同", List.of());
        }

        DesignerSemanticContracts.CompactAcceptanceDisambiguationPlan candidate;
        try {
            candidate = contract.parse(candidateJson).value();
        } catch (BadRequestException invalid) {
            return rejected(CONTRACT_INVALID, "/candidate", invalid.getMessage(), List.of());
        }
        try {
            DesignerSemanticContracts.CompactAcceptanceBindingPlan canonical =
                    resolver.merge(resolution, candidate, facts, capabilities);
            return Decision.accepted(json.writeValueAsString(canonical));
        } catch (BadRequestException invalidSelection) {
            return rejected(SELECTION_INVALID, "/capabilityPreferences",
                    invalidSelection.getMessage(), allowedValues(resolution));
        } catch (JacksonException impossible) {
            throw new IllegalStateException("Unable to serialize v7 acceptance binding", impossible);
        }
    }

    private List<String> allowedValues(DesignerAcceptanceFastPathResolver.Resolution resolution) {
        return resolution.optimalTieChoiceSets().stream().map(choice -> {
            try { return json.writeValueAsString(choice); }
            catch (JacksonException impossible) { throw new IllegalStateException(impossible); }
        }).toList();
    }

    private Decision rejected(String code, String pointer, String detail, List<String> allowedValues) {
        return Decision.rejected(SELECTION_INVALID.equals(code), List.of(
                new MachineCandidateSubmission.Problem(code, pointer, bounded(detail), allowedValues)));
    }

    private <T> T read(String value, Class<T> type, String code, String detail) {
        try { return json.readValue(value, type); }
        catch (JacksonException invalid) { throw new ConflictException(code, detail); }
    }

    private static String bounded(String value) {
        String detail = value == null || value.isBlank() ? "候选不符合冻结闭集" : value;
        return detail.length() <= 1000 ? detail : detail.substring(0, 1000);
    }
}
