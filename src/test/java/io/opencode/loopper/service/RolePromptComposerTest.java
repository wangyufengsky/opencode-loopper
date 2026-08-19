package io.opencode.loopper.service;

import io.opencode.loopper.domain.TestPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RolePromptComposerTest {
    private final RolePromptComposer composer = new RolePromptComposer();

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
}
