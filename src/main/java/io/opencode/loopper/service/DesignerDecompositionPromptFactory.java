package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.runtime.MachineRoleContractCatalog;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Builds Decomposer planning, final, and bounded repair prompts. */
final class DesignerDecompositionPromptFactory {
    private static final int MAX_REPAIRS = 2;
    private final ObjectMapper json;
    private final TaskProfileService taskProfiles;
    private final RolePromptComposer rolePrompts;

    DesignerDecompositionPromptFactory(ObjectMapper json, TaskProfileService taskProfiles,
                                       RolePromptComposer rolePrompts) {
        this.json = json;
        this.taskProfiles = taskProfiles;
        this.rolePrompts = rolePrompts;
    }

    String planning(DesignerSessionRow session, ProjectRow project,
                    DesignRequirementRevisionRow revision, boolean retry) {
        return """
                You are OpenCode Loopper Task Decomposer / 任务规划师 in the semantic planning turn of a strictly
                read-only Session. Use only read, glob, and grep. Never edit/write files, execute commands, ask
                questions, create tasks, or emit the final TASK_DECOMPOSITION envelope in this turn.

                %s

                Think in this fixed order and expose only the bounded planning result, not private chain-of-thought:
                1. Plan one coherent package or 2-6 dependency-ordered vertical business packages.
                2. Map every numbered requirement segment to a global constraint or work package with a short
                   rationale, and explain every inter-package dependency.
                3. Return the structured planning envelope below. Do not mechanically split database/backend/
                   frontend/tests. Use NEEDS_INPUT only for a genuinely missing semantic fact and
                   MULTI_TASK_REQUIRED only for multiple roots, independent releases, or more than six packages.

                Project root: %s
                Designer session: %s
                Requirement revision: R%d%s
                Numbered immutable requirement segments:
                %s

                Complete immutable requirement:
                %s

                %s

                <!-- TASK_DECOMPOSITION_PLAN_JSON_START -->
                Put exactly one complete planning object matching the contract above here.
                <!-- TASK_DECOMPOSITION_PLAN_JSON_END -->

                The markers above are preferred. If they cannot be preserved, a complete top-level JSON object may
                be returned bare, in one Markdown fence, or with a short explanation. Never return multiple
                conflicting JSON objects; the server accepts only a uniquely identifiable valid object.
                """.formatted(rolePrompts.decomposerInstructions(taskProfiles.current(session.id())),
                project.rootPath(), session.id(), revision.revision(), retry ? " explicit retry" : "",
                numberedSegments(revision), revision.requirementText(), planningContract());
    }

    String finalJson(DesignerSessionService.DecompositionPlanEnvelope plan) {
        return """
                The semantic package planning and requirement coverage mapping below passed deterministic validation
                and is now frozen. Generate the final decomposition JSON without redesigning, adding, removing,
                reordering, or paraphrasing any planning decision. Do not emit planning markers in this turn.

                Frozen planning:
                %s

                %s

                <!-- TASK_DECOMPOSITION_JSON_START -->
                Put exactly one final decomposition object matching the frozen planning here.
                <!-- TASK_DECOMPOSITION_JSON_END -->

                The markers above are preferred. If they cannot be preserved, a complete top-level JSON object may
                be returned bare, in one Markdown fence, or with a short explanation. Never return multiple
                conflicting JSON objects; the server accepts only a uniquely identifiable valid object.
                """.formatted(write(plan), finalContract());
    }

    String legacy(DesignerSessionRow session, ProjectRow project,
                  DesignRequirementRevisionRow revision, boolean retry) {
        return """
                You are OpenCode Loopper Task Decomposer / 任务规划师 in a brand-new strictly read-only Session.
                You may use only read, glob, and grep under the registered project root. Never edit/write files,
                execute commands, ask questions, create tasks, or claim implementation occurred.

                Decide whether this complete requirement is one coherent package (DIRECT_DESIGN), 2-6 dependency-
                ordered vertical business packages (DECOMPOSED), requires explicit user input (NEEDS_INPUT), or
                crosses the single-Task boundary (MULTI_TASK_REQUIRED: more than six packages, multiple project roots,
                or independent release boundaries). Do not mechanically split database/backend/frontend/tests.
                Every numbered requirement segment must be referenced by at least one global constraint or package.
                Package ids are exactly WP-1..WP-n; dependencies point only to earlier ids.

                Project root: %s
                Designer session: %s
                Requirement revision: R%d%s
                Numbered immutable requirement segments:
                %s

                Complete immutable requirement:
                %s

                Prefer one JSON object between the exact markers. If your provider cannot preserve them, the same
                complete top-level object may be returned bare, in one Markdown fence, or with a short explanation.
                Never return multiple conflicting objects:
                <!-- TASK_DECOMPOSITION_JSON_START -->
                {"status":"DIRECT_DESIGN|DECOMPOSED|NEEDS_INPUT|MULTI_TASK_REQUIRED","normalizedGoal":"...","globalConstraints":[{"text":"...","requirementRefs":["RQ-1"]}],"workPackages":[{"id":"WP-1","title":"...","objective":"...","scopeIn":[],"scopeOut":[],"dependencies":[],"deliverables":[],"acceptanceIntent":[],"requirementRefs":["RQ-1"]}],"designGaps":[],"reason":null}
                <!-- TASK_DECOMPOSITION_JSON_END -->
                """.formatted(project.rootPath(), session.id(), revision.revision(), retry ? " explicit retry" : "",
                numberedSegments(revision), revision.requirementText());
    }

    String repair(TaskDecompositionRow row, String code, String detail) {
        String frozen = row.planningJson() == null || row.planningJson().isBlank() ? null : row.planningJson();
        return frozen == null ? """
                The deterministic server rejected the previous decomposition envelope from a workflow started
                before structured planning was introduced. Repair the complete envelope without changing the
                requirement or using NEEDS_INPUT/MULTI_TASK_REQUIRED to escape JSON or validation errors.
                Repair %d/%d. Error code: %s. Error detail: %s.

                %s

                Prefer one complete replacement object between TASK_DECOMPOSITION_JSON_START/END markers. If
                markers cannot be preserved, return one uniquely identifiable complete top-level JSON object,
                either bare, fenced, or accompanied by a short explanation.
                """.formatted(row.repairCount(), MAX_REPAIRS, code, safe(detail), finalContract()) : """
                The deterministic server rejected the previous decomposition envelope. Repair the complete envelope
                using the already validated frozen planning below. Do not redesign or change to NEEDS_INPUT or
                MULTI_TASK_REQUIRED merely to escape JSON, coverage, dependency, or field errors.
                Repair %d/%d. Error code: %s. Error detail: %s.

                Frozen planning:
                %s

                %s

                Prefer one complete replacement object between TASK_DECOMPOSITION_JSON_START/END markers. If
                markers cannot be preserved, return one uniquely identifiable complete top-level JSON object,
                either bare, fenced, or accompanied by a short explanation.
                """.formatted(row.repairCount(), MAX_REPAIRS, code, safe(detail), frozen, finalContract());
    }

    String planningRepair(TaskDecompositionRow row, DesignRequirementRevisionRow revision,
                          String code, String detail) {
        return """
                The deterministic server rejected the previous decomposition planning envelope. Repair the complete
                planning result without emitting the final decomposition JSON. Do not use NEEDS_INPUT or
                MULTI_TASK_REQUIRED to escape JSON, coverage, dependency, or field errors.
                Repair %d/%d. Error code: %s. Error detail: %s.

                Numbered immutable requirement segments:
                %s

                %s

                Prefer one complete replacement object between TASK_DECOMPOSITION_PLAN_JSON_START/END markers. If
                markers cannot be preserved, return one uniquely identifiable complete top-level JSON object,
                either bare, fenced, or accompanied by a short explanation.
                """.formatted(row.planningRepairCount(), MAX_REPAIRS, code, safe(detail), numberedSegments(revision),
                planningContract());
    }

    String semanticPatch(TaskDecompositionRow row, String code, String detail) {
        return """
                The server parsed the compact decomposition object but rejected a semantic or safety contract.
                Return only a bounded patch object; do not repeat the full plan. Allowed operations are add,
                replace, and remove. Allowed roots are outcome, normalizedGoal, globalConstraints, workPackages,
                coverage, designGaps, and reason. Never patch ids, status, requirementRefs, or dependencies because
                the server derives them. Error code: %s. Error detail: %s.

                Frozen semantic object:
                %s

                <!-- TASK_DECOMPOSITION_PLAN_JSON_START -->
                {"patches":[{"op":"replace","path":"/coverage/0/targetIndex","value":0}]}
                <!-- TASK_DECOMPOSITION_PLAN_JSON_END -->
                """.formatted(code, safe(detail), row.semanticPlanJson());
    }

    private String planningContract() {
        return """
                %s
                Return only the compact semantic object. The server derives DIRECT_DESIGN/DECOMPOSED, GC/WP ids,
                requirementRefs, dependency ids, and dependency evidence; do not spend effort emitting those fields.
                READY uses 1-6 vertical work packages. targetIndex and packageIndex are zero-based.
                {"outcome":"READY","normalizedGoal":"observable overall goal","globalConstraints":[{"text":"constraint"}],"workPackages":[{"title":"vertical capability","objective":"observable result","scopeIn":["..."],"scopeOut":[],"deliverables":["..."],"acceptanceIntent":["..."],"dependsOn":[]}],"coverage":[{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE","targetIndex":0,"rationale":"optional"}],"designGaps":[],"reason":null}
                NEEDS_INPUT and MULTI_TASK_REQUIRED keep workPackages/coverage empty and provide the existing closed
                designGaps or a concrete reason. All arrays remain arrays.
                """.formatted(MachineRoleContractCatalog.card("DECOMPOSER"));
    }

    private String finalContract() {
        return """
                Final decomposition JSON contract:
                - The final object contains exactly status, normalizedGoal, globalConstraints, workPackages,
                  designGaps, and reason. It omits coverageMappings and dependencyEvidence because the server already
                  persisted those planning proofs.
                - All collection fields remain JSON arrays. Global constraints and work packages retain the exact
                  object shapes, values, ordering, requirementRefs, and dependencies from the frozen planning.
                - DIRECT_DESIGN/DECOMPOSED use designGaps:[] and reason:null. NEEDS_INPUT uses designGaps objects
                  {"code":"closed code","detail":"concrete missing fact"}, never strings.
                  MULTI_TASK_REQUIRED uses workPackages:[], designGaps:[], and a concrete reason.
                """;
    }

    private String numberedSegments(DesignRequirementRevisionRow revision) {
        return readSegments(revision.requirementSegmentsJson()).stream()
                .map(segment -> segment.id() + ": " + segment.text())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private List<DesignerSessionService.RequirementSegment> readSegments(String source) {
        try {
            return json.readValue(source, new TypeReference<>() { });
        } catch (Exception failure) {
            throw new IllegalStateException("Frozen requirement segments are unreadable", failure);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to serialize frozen decomposition plan", failure);
        }
    }

    private String safe(String value) {
        return value == null ? "Unknown error" : value.substring(0, Math.min(value.length(), 4_000));
    }
}
