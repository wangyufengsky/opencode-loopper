package io.opencode.loopper.service;

import io.opencode.loopper.runtime.MachineRoleContractCatalog;

/** Centralizes versioned, closed Compiler prompt contracts independently from workflow orchestration. */
final class DesignerCompilerPromptContracts {
    private DesignerCompilerPromptContracts() { }

    static String acceptanceBinding(String packageId, String factsJson, String capabilitiesJson,
                                    int stageLimit, String priorError) {
        return """
                %s
                You receive a frozen DesignFact catalog and a closed verification-capability catalog. Return only
                advisory grouping and capability preferences. The server owns EARS criterion text, exact source
                excerpts and hashes, commands, paths, test targets, verifier objects, acceptance ids, workPackageId,
                dependency validation, set-cover optimization, compilation outcome, design gaps, and final LoopSpec v2
                lowering. Do not return outcome, status, designGaps, contractVersion, workPackageId, or design hashes.
                - Return 0-%d groupHints. Empty groupHints asks the server to use one deterministic group. factIndexes
                  and capabilityIndexes must reference only the supplied
                  catalogs. Every acceptance fact should appear in one group. dependsOnHintIndexes may reference only
                  an earlier group. Preferences are soft; omit them when uncertain.
                - Never invent a verifier, test command, source reference, path, id, or fact.
                - Built-in repository tools are disabled in this binding session. Configured MCP tools remain available
                  under the existing permission policy; do not read the repository again; return the complete object immediately.
                In TEXT_MARKER compatibility mode, put the same complete object between
                LOOPSPEC_COMPILATION_PLAN_JSON_START and LOOPSPEC_COMPILATION_PLAN_JSON_END markers.
                The complete object has exactly summary, groupHints, capabilityPreferences, and handoffSummary.
                Exact compact shape (property names and JSON types are literal):
                {"summary":"short summary","groupHints":[{"title":"stage title","objective":"stage objective",
                "factIndexes":[0],"dependsOnHintIndexes":[]}],"capabilityPreferences":[{"factIndex":0,
                "capabilityIndexes":[0]}],"handoffSummary":"short handoff"}
                Do not emit groupIndex, capabilityIndex, preference, reason, or capabilityIndexes inside groupHints.
                %s

                Frozen package: %s
                Frozen DesignFacts:
                %s
                Frozen verification capabilities:
                %s
                """.formatted(MachineRoleContractCatalog.card("COMPILER"), stageLimit,
                priorError == null || priorError.isBlank() ? "" : "Repair the complete binding after: " + priorError,
                packageId, factsJson, capabilitiesJson);
    }

    static String planning(String packageId, WorkPackageRoleService.View profile,
                           RolePromptComposer rolePrompts) {
        String example = rolePrompts.compilerPlanningExample(profile.rolePackId());
        return """
                %s
                %s
                Return only semantic stages, criteria, sourceRefs, and evidence intentions. The server generates
                %s-AC-n, workPackageId, exact Designer excerpts, criterionIds, testTargets, and final StageSpec JSON.
                Evidence kinds are FOCUSED_TEST, FULL_TEST, BUILD, SELF_CHECK, GIT_DIFF, HTTP_STATUS, JSON_PATH,
                BROWSER, DATABASE_QUERY, FILE_CONTENT, FILE_HASH, DOCUMENT_STRUCTURE, TABULAR_DATA,
                FILE_NOT_EXISTS, and JUNIT_XML. covers contains zero-based criterion indexes.
                FULL_TEST/BUILD/GIT_DIFF/FILE_NOT_EXISTS/JUNIT_XML are supplemental and must use covers:[].
                FOCUSED_TEST uses the current stack's safe direct test argv; SELF_CHECK includes successMarker and
                must emit that marker on success; source-text searches such as grep/rg are not behavior SELF_CHECK
                commands. Every criterion must either be covered by one native behavior evidence item or provide
                both judgeRubric and judgeOnlyReason for a genuinely Judge-only result. Every JAVA_PRODUCTION Stage
                must include a focused Maven/Gradle TEST even if all criteria are Judge-only; that gate may use
                covers:[] but FULL_TEST and BUILD never replace it. Merge Java wiring/demo work into the related
                focused-test Stage instead of emitting a production-only final Stage. Criteria contain only
                observable business outcomes. Code style, source shape, annotations, assembly shape, build success,
                and test success stay in deliverables or supplemental evidence instead of becoming criteria.
                Shells, pipes, redirects, unsafe paths, fake tests, and missing tests required by the frozen Role Pack
                are still rejected by the server validator.
                %s
                """.formatted(rolePrompts.compilerInstructions(profile.rolePackId(), profile.rolePackVersion(),
                        profile.executionStrategy(), profile.technologies(), profile.testPolicy()),
                MachineRoleContractCatalog.card("COMPILER"), packageId, example);
    }

    static String compiledPackage(String packageId) {
        String criterionId = packageId + "-AC-1";
        return """
                Strict JSON type contract (property names and JSON types are exact):
                - stages, allowedPaths, forbiddenPaths, deliverables, verifiers, acceptanceCriteria,
                  criterionSources, designGaps, command, criterionIds, testTargets, assertions, and startCommand are
                  JSON arrays even when they contain only one item. Never emit a command or verifier as a string.
                - stages[*].verifiers[*] is a VerifierSpec JSON object. A PROCESS verifier uses
                  {"type":"PROCESS","command":["mvn","-q","-Dtest=ExampleFocusedTest","test"],"processPurpose":"TEST","testTargets":["ExampleFocusedTest"],"criterionIds":["%s"]}.
                  processPurpose is BUILD, TEST, or SELF_CHECK. A TEST mapped to a business criterion has non-empty
                  testTargets; a full-suite supplemental TEST has empty criterionIds/testTargets. SELF_CHECK has
                  outputContains. command is direct argv and never one shell command string.
                - Path policies must be satisfiable. No stage or GIT_DIFF allowedPaths rule may be entirely covered
                  by a forbiddenPaths rule. Narrow exclusions inside a broader allow rule remain valid.
                - stages[*].acceptanceCriteria[*] is
                  {"id":"%s","description":"observable business result","verificationMode":"MACHINE|JUDGE|BOTH","judgeRubric":"required for JUDGE/BOTH or null","judgeOnlyReason":"required only for JUDGE or null"}.
                - verificationRuntime is null for PROCESS-only stages and never a test framework name. Only an
                  HTTP_STATUS, JSON_PATH, or BROWSER stage that starts its own service uses
                  {"startCommand":["java","-jar","app.jar","--server.port={{LOOPPER_PORT}}"],"readiness":{"path":"/actuator/health","expectedStatus":200,"jsonPath":"$.status","expectedValue":"UP","matchMode":"EXACT"},"startupTimeoutSeconds":60,"shutdownTimeoutSeconds":10}.
                - Other supported verifier object shapes are:
                  GIT_DIFF {"type":"GIT_DIFF","requireChanges":true,"allowedPaths":["src/**"],"forbiddenPaths":[".env"],"forbidDeletes":true};
                  HTTP_STATUS {"type":"HTTP_STATUS","url":"http://127.0.0.1:{{LOOPPER_PORT}}/path","httpMethod":"GET","expectedStatus":200,"criterionIds":["%s"]};
                  JSON_PATH adds jsonPath, expectedValue, and matchMode; FILE_CONTENT uses path, expectedContent,
                  matchMode, and criterionIds; FILE_HASH uses path, expectedSha256, and criterionIds;
                  DATABASE_QUERY uses path, sql, expectedRowCount, and criterionIds; BROWSER uses url, criterionIds,
                  and assertion objects {"type":"EXISTS|VISIBLE|TEXT_CONTAINS|COUNT|ATTRIBUTE_EQUALS","selector":"...","value":"... or null","attribute":"... or null","expectedCount":1}.
                  FILE_NOT_EXISTS, JUNIT_XML, and legacy advisory FILE_EXISTS use a path and cannot cover behavior.
                - implementationKind is exactly JAVA_PRODUCTION, JAVA_TEST_ONLY, or NON_JAVA. JAVA_PRODUCTION puts
                  production Java and its focused Maven/Gradle PROCESS TEST in the same stage, and that TEST's
                  criterionIds covers every MACHINE/BOTH criterion in the stage.
                - Every stage sets workPackageId to "%s". Criterion ids are unique and use %s-AC-n. Every criterion
                  has one criterionSources object {"stageIndex":0,"criterionId":"%s","excerpt":"exact non-empty Designer substring"}.
                - COMPILED uses designGaps:[]. DESIGN_INCOMPLETE uses stages:[], criterionSources:[], and one or more
                  objects such as {"code":"MISSING_OBSERVABLE_OUTCOME","detail":"concrete missing design fact"};
                  allowed codes are MISSING_OBSERVABLE_OUTCOME, MISSING_EXCEPTION_SEMANTICS, MISSING_SCOPE,
                  MISSING_ACCEPTANCE_INTENT, and LARGE_TASK_MODE_REQUIRED. LARGE_TASK_MODE_REQUIRED is valid only for
                  DIRECT_SOFTWARE_DESIGN when one coherent 1-6 Stage package cannot safely hold the complete design.
                  designGaps entries are never strings.

                Canonical COMPILED envelope for a JAVA_PRODUCTION stage:
                {"status":"COMPILED","summary":"compiled package summary","stages":[{"objective":"observable stage result","allowedPaths":["src/main/java/**","src/test/java/**"],"forbiddenPaths":[".env"],"deliverables":["production implementation and focused test"],"verifiers":[{"type":"PROCESS","command":["mvn","-q","-Dtest=ExampleFocusedTest","test"],"processPurpose":"TEST","testTargets":["ExampleFocusedTest"],"criterionIds":["%s"]},{"type":"GIT_DIFF","requireChanges":true,"allowedPaths":["src/main/java/**","src/test/java/**"],"forbiddenPaths":[".env"],"forbidDeletes":true}],"acceptanceCriteria":[{"id":"%s","description":"observable business result","verificationMode":"BOTH","judgeRubric":"Confirm the implemented behavior matches the frozen design and deterministic test evidence.","judgeOnlyReason":null}],"verificationRuntime":null,"implementationKind":"JAVA_PRODUCTION","workPackageId":"%s"}],"criterionSources":[{"stageIndex":0,"criterionId":"%s","excerpt":"exact non-empty Designer substring"}],"handoffSummary":"bounded dependency handoff summary","designGaps":[]}
                """.formatted(criterionId, criterionId, criterionId, packageId, packageId, criterionId,
                criterionId, criterionId, packageId, criterionId);
    }
}
