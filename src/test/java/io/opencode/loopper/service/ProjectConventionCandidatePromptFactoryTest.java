package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectConventionCandidatePromptFactoryTest {
    @Test
    void exposesOnlyFrozenEvidenceIdsAndTheExactPrivateSubmissionTool() {
        ProjectConventionCompilation.EvidenceCatalog evidence =
                new ProjectConventionCompilation.EvidenceCatalog("a".repeat(64), List.of(
                        new ProjectConventionCompilation.ComponentEvidence(
                                "component-java", ".", List.of("java"), List.of("maven"), List.of("junit"))),
                        List.of(new ProjectConventionCompilation.CommandEvidence(
                                "component-java:maven:test", "component-java", List.of("./mvnw", "test"))),
                        List.of(new ProjectConventionCompilation.PathEvidence(
                                "component-java:manifest:pom.xml", "component-java", "pom.xml",
                                ProjectConventionCompilation.PathKind.MANIFEST)));
        MachineCandidateSubmission.RunSnapshot run = new MachineCandidateSubmission.RunSnapshot(
                "run-1", MachineCandidateSubmission.CandidateScope.project("project-1"),
                MachineCandidateSubmission.CandidateOwnerRef.projectConventionDraft("draft-1"),
                MachineCandidateKind.PROJECT_CONVENTION_V1, "PROJECT_CONVENTION_V1", 1, 1,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                "PROJECT_CONVENTION_V1", "generation-1", "remote-1",
                MachineCandidateRunState.OPEN, 3, 0, null, 7, null);

        String prompt = new ProjectConventionCandidatePromptFactory().internal(
                run, evidence, "loopper_internal_submit_candidate");

        assertThat(prompt)
                .contains("PROJECT_CONVENTION_V1", "loopper_internal_submit_candidate",
                        "component-java", "component-java:maven:test",
                        "component-java:manifest:pom.xml")
                .contains("componentKeys", "commandIds", "pathIds")
                .contains("expectedSubmissionRevision: 7")
                .contains("returned submissionRevision")
                .contains("code, JSON Pointer, detail, allowed values")
                .contains("Do not return the candidate as final assistant text")
                .doesNotContain("fallbackAllowed: true");
    }
}
