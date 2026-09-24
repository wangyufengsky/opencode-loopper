package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Builds the bounded v7 closed-choice prompts without exposing commands, paths, or source excerpts. */
final class DesignerAcceptanceCandidatePromptFactory {
    private final ObjectMapper json;
    private final DesignerClosedChoiceContract contract;

    DesignerAcceptanceCandidatePromptFactory(ObjectMapper json) {
        this.json = json;
        this.contract = new DesignerClosedChoiceContract(json, new AiOutputExtractor(json));
    }

    String internal(DesignAcceptancePlanningRow planning,
                    DesignerAcceptanceWorkflow.RoutingResult routing,
                    MachineCandidateSubmission.RunSnapshot run,
                    String exactToolName) {
        requireEligible(planning, routing);
        if (run == null || exactToolName == null || exactToolName.isBlank()) {
            throw new BadRequestException("ACCEPTANCE_CANDIDATE_PROMPT_INVALID",
                    "验收闭集候选提示参数不完整");
        }
        return (RolePromptResources.read("prompt.v1.DesignerAcceptanceCandidatePromptFactory.block01.segment0")
                + String.format("%s", (Object) (facts(planning, routing)))
                + "\nFrozen capabilities:\n"
                + String.format("%s", (Object) (capabilities(planning, routing)))
                + RolePromptResources.read("prompt.v1.DesignerAcceptanceCandidatePromptFactory.block01.segment2")
                + String.format("%s", (Object) (contract.resolution(routing.resolution())))
                + "\n\n"
                + String.format("%s", (Object) (DesignerClosedChoiceContract.outputContract()))
                + RolePromptResources.read("prompt.v1.DesignerAcceptanceCandidatePromptFactory.block01.segment4")
                + String.format("%s", (Object) (run.runId()))
                + "\nexpectedSubmissionRevision: "
                + String.format("%d", (Object) (run.version()))
                + "\ncontractVersion: "
                + String.format("%s", (Object) (run.contractVersion()))
                + "\nexact role submission tool: "
                + String.format("%s", (Object) (exactToolName))
                + "\n\nCall "
                + String.format("%s", (Object) (exactToolName))
                + RolePromptResources.read("prompt.v1.DesignerAcceptanceCandidatePromptFactory.block01.segment9")
                + String.format("%s", (Object) (CandidateCorrectionPolicy.prompt(run)))
                + RolePromptResources.read("prompt.v1.DesignerAcceptanceCandidatePromptFactory.block01.segment10"));
    }

    String legacy(DesignAcceptancePlanningRow planning,
                  DesignerAcceptanceWorkflow.RoutingResult routing,
                  MachineCandidateSubmission.SubmissionResult rejected) {
        requireEligible(planning, routing);
        String repair = rejected == null ? "" : (RolePromptResources.read("prompt.v1.DesignerAcceptanceCandidatePromptFactory.block02.segment0")
                + String.format("%s", (Object) (problems(rejected.problems())))
                + "\n");
        return (RolePromptResources.read("prompt.v1.DesignerAcceptanceCandidatePromptFactory.block03.segment0")
                + String.format("%s", (Object) (facts(planning, routing)))
                + "\nFrozen capabilities:\n"
                + String.format("%s", (Object) (capabilities(planning, routing)))
                + RolePromptResources.read("prompt.v1.DesignerAcceptanceCandidatePromptFactory.block01.segment2")
                + String.format("%s", (Object) (contract.resolution(routing.resolution())))
                + "\n"
                + String.format("%s", (Object) (repair))
                + "\n\n"
                + String.format("%s", (Object) (DesignerClosedChoiceContract.outputContract()))
                + RolePromptResources.read("prompt.v1.DesignerAcceptanceCandidatePromptFactory.block03.segment5"));
    }

    String candidateJson(String output) {
        try { return contract.parse(output).canonicalJson(); }
        catch (BadRequestException invalid) {
            return "{\"candidateContractInvalid\":true}";
        }
    }

    private String facts(DesignAcceptancePlanningRow planning,
                         DesignerAcceptanceWorkflow.RoutingResult routing) {
        return contract.facts(read(planning.factsJson(), DesignerAcceptancePlanning.Catalog.class),
                routing.resolution());
    }

    private String capabilities(DesignAcceptancePlanningRow planning,
                                DesignerAcceptanceWorkflow.RoutingResult routing) {
        return contract.capabilities(read(planning.capabilitiesJson(),
                DesignerAcceptancePlanning.CapabilityCatalog.class), routing.resolution());
    }

    private void requireEligible(DesignAcceptancePlanningRow planning,
                                 DesignerAcceptanceWorkflow.RoutingResult routing) {
        if (planning == null || !AcceptanceClosedChoiceCandidateCoordinator.exactTrueTie(routing)) {
            throw new ConflictException("ACCEPTANCE_CANDIDATE_NOT_ELIGIBLE",
                    "当前验收规划不是可枚举的 v7 真实同分闭集");
        }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JacksonException invalid) {
            throw new ConflictException("ACCEPTANCE_CANDIDATE_SNAPSHOT_INVALID",
                    "冻结的验收候选快照无法读取");
        }
    }

    private String problems(List<MachineCandidateSubmission.Problem> problems) {
        try { return json.writeValueAsString(problems == null ? List.of() : problems); }
        catch (JacksonException impossible) { throw new IllegalStateException(impossible); }
    }
}
