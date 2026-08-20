package io.opencode.loopper.service;

import io.opencode.loopper.domain.TestPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RolePromptComposerTest {
    private final RolePromptComposer composer = new RolePromptComposer();
    private final ObjectMapper json = new ObjectMapper();

    @Test void pythonExecutionPromptUsesPythonEvidenceWithoutJavaAssumptions() {
        String prompt = composer.implementationInstructions("software-python", "v1", List.of("python"),
                TestPolicy.OPTIONAL);

        assertThat(prompt).contains("pytest/unittest", "SELF_CHECK", "OPTIONAL")
                .doesNotContain("Maven", "Gradle", "Production Java");
    }

    @Test void notApplicableArtifactPromptExplicitlyForbidsProcessTests() {
        String prompt = composer.implementationInstructions("document-markdown-docx", "v1", List.of(),
                TestPolicy.NOT_APPLICABLE);

        assertThat(prompt).contains("NOT_APPLICABLE forbids PROCESS TEST");
    }

    @Test void everyCompilerRolePackGetsAStackSpecificCanonicalCompactExample() throws Exception {
        Map<String, List<String>> expected = Map.of(
                "software-java", List.of("\"outcome\":\"COMPILED\"", "mvn", "FOCUSED_TEST"),
                "software-python", List.of("\"outcome\":\"COMPILED\"", "pytest", "FOCUSED_TEST"),
                "software-node", List.of("\"outcome\":\"COMPILED\"", "npm", "FOCUSED_TEST"),
                "software-mixed", List.of("\"outcome\":\"COMPILED\"", "mvn", "npm"),
                "software-generic", List.of("\"outcome\":\"COMPILED\"", "judgeOnlyReason", "GIT_DIFF"),
                "local-maintenance", List.of("\"outcome\":\"COMPILED\"", "FILE_CONTENT", "GIT_DIFF"));

        expected.forEach((rolePack, fragments) -> {
            String example = composer.compilerPlanningExample(rolePack);
            assertThat(example).contains(fragments.toArray(String[]::new));
            try {
                assertThat(json.readValue(example, Map.class)).containsKey("outcome").containsKey("stages");
            } catch (Exception invalid) {
                throw new AssertionError(rolePack + " example is not JSON", invalid);
            }
        });
        assertThat(composer.compilerPlanningExample("software-node"))
                .doesNotContain("mvn", "pytest", "DOCUMENT_STRUCTURE", "TABULAR_DATA");
        assertThat(composer.compilerPlanningExample("software-generic"))
                .doesNotContain("mvn", "npm", "pytest", "SELF_CHECK");
    }

    @Test void serverOwnedArtifactAndReviewerPacksDoNotInheritSoftwareTestExamples() throws Exception {
        assertThat(json.readValue(composer.compilerPlanningExample("document-markdown-docx"), Map.class)
                .get("outcome")).isEqualTo("COMPILED");
        assertThat(composer.compilerPlanningExample("document-markdown-docx"))
                .contains("DOCUMENT_STRUCTURE").doesNotContain("FOCUSED_TEST", "mvn", "npm", "pytest");
        assertThat(composer.compilerPlanningExample("tabular-conversion"))
                .contains("TABULAR_DATA").doesNotContain("FOCUSED_TEST", "mvn", "npm", "pytest");
        assertThat(composer.compilerPlanningExample("read-only-report"))
                .contains("must never enter LoopSpec Compiler");
    }

    @Test void javaAndMixedCompilerContractsRejectBuildOnlyProductionStages() {
        assertThat(composer.compilerInstructions("software-java", "v1", null, List.of("java"),
                TestPolicy.REQUIRED))
                .contains("每个 JAVA_PRODUCTION Stage", "covers:[]", "FULL_TEST/BUILD");
        assertThat(composer.compilerInstructions("software-mixed", "v1", null,
                List.of("java", "javascript"), TestPolicy.REQUIRED))
                .contains("每个 JAVA_PRODUCTION Stage", "Judge-only", "FULL_TEST/BUILD");
    }
}
