package io.opencode.loopper.service;

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
        return """
                You are OpenCode Loopper's ACCEPTANCE_CLOSED_CHOICE_V7 selector in one no-tools Session.
                Built-in tools and every user-configured MCP are forbidden. The only callable tool is the exact
                private tool named below. Choose exactly one complete equal optimum from the frozen closed set.
                Do not return or infer commands, paths, tests, topology, permissions, safety rules, or design fields.

                Frozen facts:
                %s
                Frozen capabilities:
                %s
                Frozen exhaustive resolution:
                %s

                %s

                Submission contract:
                runId: %s
                expectedSubmissionRevision: %d
                contractVersion: %s
                exact submit_candidate tool: %s

                Call %s once with runId, a new idempotencyKey, candidate containing exactly factAssignments and
                capabilityPreferences plus optional summary/handoffSummary, and expectedSubmissionRevision. The tool
                result is authoritative. Only when it returns REJECTED with
                ACCEPTANCE_CANDIDATE_SELECTION_INVALID may you mechanically correct the listed selection and call the
                same exact tool again with the returned submissionRevision. MCP submissions have no count limit.
                On ACCEPTED or WAITING_INPUT stop.
                The final text is non-authoritative and must never claim acceptance.
                """.formatted(facts(planning, routing), capabilities(planning, routing),
                contract.resolution(routing.resolution()), DesignerClosedChoiceContract.outputContract(), run.runId(), run.version(), run.contractVersion(),
                exactToolName, exactToolName);
    }

    String legacy(DesignAcceptancePlanningRow planning,
                  DesignerAcceptanceWorkflow.RoutingResult routing,
                  MachineCandidateSubmission.SubmissionResult rejected) {
        requireEligible(planning, routing);
        String repair = rejected == null ? "" : """

                The prior candidate was mechanically rejected. Change only the selection using these safe problems:
                %s
                """.formatted(problems(rejected.problems()));
        return """
                You are OpenCode Loopper's ACCEPTANCE_CLOSED_CHOICE_V7 compatibility selector in one no-tools Session.
                Built-in tools and every MCP tool are forbidden. Choose exactly one complete equal optimum from the
                frozen closed set. Do not return or infer commands, paths, tests, topology, permissions, safety rules,
                or design fields. The final text is only a candidate; the server is the sole acceptance authority.

                Frozen facts:
                %s
                Frozen capabilities:
                %s
                Frozen exhaustive resolution:
                %s
                %s

                %s

                Return exactly one JSON object between these markers:
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"factAssignments":[],"capabilityPreferences":[{"factIndex":0,"capabilityIndexes":[0]}]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """.formatted(facts(planning, routing), capabilities(planning, routing),
                contract.resolution(routing.resolution()), repair, DesignerClosedChoiceContract.outputContract());
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
