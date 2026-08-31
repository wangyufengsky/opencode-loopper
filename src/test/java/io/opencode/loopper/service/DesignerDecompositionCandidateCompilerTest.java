package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DesignerDecompositionCandidateCompilerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DesignerDecompositionCandidateCompiler compiler =
            new DesignerDecompositionCandidateCompiler(json);

    @Test
    void compilesCompactReadyCandidateIntoCanonicalServerOwnedPlan() throws Exception {
        String candidate = """
                {
                  "outcome":"READY",
                  "normalizedGoal":"deliver an auditable correction loop",
                  "globalConstraints":[{"text":"remain loopback-only"}],
                  "workPackages":[
                    {"title":"Candidate validation","objective":"validate candidate semantics",
                     "scopeIn":["policy"],"scopeOut":[],"deliverables":["validated plan"],
                     "acceptanceIntent":["invalid candidates explain all problems"],"dependsOn":[]},
                    {"title":"Accepted plan persistence","objective":"persist only accepted plans",
                     "scopeIn":["writer"],"scopeOut":[],"deliverables":["authoritative snapshot"],
                     "acceptanceIntent":["accepted state is atomic"],
                     "dependsOn":[{"packageIndex":0,"rationale":"uses the validated plan"}]}
                  ],
                  "coverage":[
                    {"requirementRef":"RQ-1","targetType":"GLOBAL_CONSTRAINT","targetIndex":0,
                     "rationale":"network boundary"},
                    {"requirementRef":"RQ-2","targetType":"WORK_PACKAGE","targetIndex":0,
                     "rationale":"validation behavior"},
                    {"requirementRef":"RQ-3","targetType":"WORK_PACKAGE","targetIndex":1,
                     "rationale":"persistence behavior"}
                  ],
                  "designGaps":[],"reason":null
                }
                """;

        DesignerDecompositionCandidateCompiler.Compilation result = compiler.compile(candidate, revision());

        assertThat(result.accepted()).isTrue();
        assertThat(result.problems()).isEmpty();
        JsonNode canonical = json.readTree(result.canonicalJson());
        assertThat(canonical.path("status").asText()).isEqualTo("DECOMPOSED");
        assertThat(canonical.path("workPackages").path(0).path("id").asText()).isEqualTo("WP-1");
        assertThat(canonical.path("workPackages").path(1).path("dependencies").get(0).asText())
                .isEqualTo("WP-1");
        assertThat(canonical.path("globalConstraints").path(0).path("requirementRefs").get(0).asText())
                .isEqualTo("RQ-1");
        assertThat(canonical.path("workPackages").path(0).path("requirementRefs").get(0).asText())
                .isEqualTo("RQ-2");
        assertThat(canonical.path("dependencyEvidence").path(0).path("rationale").asText())
                .isEqualTo("uses the validated plan");
    }

    @Test
    void reportsMultipleBoundedPointerProblemsInOneRejection() {
        String candidate = """
                {
                  "outcome":"READY",
                  "normalizedGoal":"",
                  "globalConstraints":[null],
                  "workPackages":[
                    {"title":"后端","objective":"","deliverables":[],"acceptanceIntent":[],
                     "dependsOn":[0]},
                    {"title":"Vertical result","objective":"result","deliverables":["x"],
                     "acceptanceIntent":["y"],"dependsOn":[1]}
                  ],
                  "coverage":[
                    {"requirementRef":"RQ-404","targetType":"WORK_PACKAGE","targetIndex":9},
                    {"requirementRef":"RQ-1","targetType":"WRONG","targetIndex":0}
                  ],
                  "designGaps":[]
                }
                """;

        DesignerDecompositionCandidateCompiler.Compilation result = compiler.compile(candidate, revision());

        assertThat(result.accepted()).isFalse();
        assertThat(result.canonicalJson()).isNull();
        assertThat(result.problems()).hasSizeGreaterThanOrEqualTo(8).hasSizeLessThanOrEqualTo(16);
        assertThat(result.problems()).extracting(MachineCandidateSubmission.Problem::pointer)
                .contains("/normalizedGoal", "/globalConstraints/0", "/workPackages/0/title",
                        "/workPackages/0/objective", "/workPackages/0/deliverables",
                        "/workPackages/0/acceptanceIntent", "/workPackages/0/dependsOn/0",
                        "/workPackages/1/dependsOn/0", "/coverage/0/requirementRef",
                        "/coverage/0/targetIndex", "/coverage/1/targetType", "/coverage");
    }

    @Test
    void acceptsClosedNeedsInputAndConcreteMultiTaskBoundary() throws Exception {
        DesignerDecompositionCandidateCompiler.Compilation needsInput = compiler.compile("""
                {"outcome":"NEEDS_INPUT","normalizedGoal":null,"globalConstraints":[],"workPackages":[],
                 "coverage":[],"designGaps":[{"code":"MISSING_SCOPE","detail":"choose the managed root"}],
                 "reason":null}
                """, revision());
        DesignerDecompositionCandidateCompiler.Compilation multiTask = compiler.compile("""
                {"outcome":"MULTI_TASK_REQUIRED","normalizedGoal":null,"globalConstraints":[],
                 "workPackages":[],"coverage":[],"designGaps":[],
                 "reason":"two independent project roots and releases"}
                """, revision());

        assertThat(json.readTree(needsInput.canonicalJson()).path("status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(json.readTree(multiTask.canonicalJson()).path("status").asText())
                .isEqualTo("MULTI_TASK_REQUIRED");
    }

    @Test
    void malformedJsonIsRetryableWithRootPointer() {
        DesignerDecompositionCandidateCompiler.Compilation result = compiler.compile("{", revision());

        assertThat(result.accepted()).isFalse();
        assertThat(result.problems()).containsExactly(new MachineCandidateSubmission.Problem(
                "CANDIDATE_JSON_INVALID", "", "Candidate must be one complete JSON object"));
    }

    private DesignRequirementRevisionRow revision() {
        return new DesignRequirementRevisionRow("rev", "session", 3, "message", "complete requirement",
                """
                        [{"id":"RQ-1","text":"remain loopback-only"},
                         {"id":"RQ-2","text":"validate all candidate semantics"},
                         {"id":"RQ-3","text":"persist only an accepted plan"}]
                        """, 7, "ACTIVE", 1, 8, "now", "now", 0);
    }
}
