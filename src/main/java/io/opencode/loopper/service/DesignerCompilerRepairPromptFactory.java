package io.opencode.loopper.service;

/** Builds bounded Compiler repair prompts after all repository evidence has been frozen. */
final class DesignerCompilerRepairPromptFactory {
    String planning(int repairCount, int maxRepairs, String code, String detail, String prerequisites,
                    String declaredTests, String machineContract, String design) {
        return """
                The deterministic server rejected the previous Stage/evidence planning envelope. Repair the entire
                planning result without emitting final StageSpec/verifier JSON. Do not redesign, inspect another
                package, or use DESIGN_INCOMPLETE to escape format, mapping, or field errors. Built-in repository
                tools are disabled. Configured MCP tools remain available, but return the complete object immediately
                from the frozen design, source index, Role Pack contract, and exact error below.
                Repair %d/%d. Error code: %s. Error detail: %s.

                Frozen prerequisite package contracts:
                %s
                The repository is the pre-execution baseline. A prerequisite with state APPROVED executes before
                this package; its current file absence is not a design gap and must not be returned as MISSING_SCOPE.

                Designer-declared focused test evidence (exact frozen design lines; all applicable named tests are
                mandatory evidence and must be copied into testCommand/testTargets and PROCESS TEST verifiers):
                %s

                %s

                Return one replacement object between LOOPSPEC_COMPILATION_PLAN_JSON_START/END markers.

                Frozen work-package design:
                %s
                """.formatted(repairCount, maxRepairs, code, safe(detail), prerequisites, declaredTests,
                machineContract, design);
    }

    String semanticPatch(String packageId, String code, String detail, String semanticPlan) {
        return """
                The server parsed the compact Compiler object but rejected a semantic or safety contract. Return
                only a bounded patch object with add, replace, or remove operations. Allowed roots are outcome,
                summary, stages, handoffSummary, and designGaps. Server-derived ids, excerpts, criterionIds,
                testTargets, verification modes, and final verifier objects are outside patch space. Built-in
                repository tools are disabled. Configured MCP tools remain available, but return the patch
                immediately from the supplied snapshot without repository exploration.
                Work package: %s. Error code: %s. Error detail: %s.
                Error detail may contain several [CODE] /json/pointer entries. Repair every listed entry in this
                single patch response; do not spend one response per error. Do not turn engineering metadata into
                business criteria or use source-text search as a behavior SELF_CHECK.
                Patch the compact object exactly as frozen below: its Stage evidence array is named `evidence`, not
                the server-derived final field `verifiers`. Use paths such as /stages/3/evidence/0/path. Every
                criterion must either be covered by one native behavior evidence item or contain both judgeRubric
                and judgeOnlyReason. Every JAVA_PRODUCTION Stage must retain a focused Maven/Gradle TEST even when
                its criteria are Judge-only; FULL_TEST and BUILD never satisfy that Java gate. A Java wiring/demo
                Stage without its own focused TEST must either add the frozen repository test with covers:[] and
                make its criterion explicitly Judge-only, or be merged into the related focused-test Stage by
                replacing the bounded stages array. Do not invent FILE_CONTENT evidence for runtime Java behavior.

                Frozen semantic object:
                %s

                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"patches":[{"op":"replace","path":"/stages/0/objective","value":"observable result from the frozen design"}]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """.formatted(packageId, code, safe(detail), semanticPlan);
    }

    private String safe(String value) {
        if (value == null) return "Unknown error";
        return value.substring(0, Math.min(value.length(), 4_000));
    }
}
