package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.domain.ProjectStackProfileState;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProjectConventionLegacyAdapterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectConventionLegacyAdapter adapter = new ProjectConventionLegacyAdapter(
            new AiOutputExtractor(json),
            new DeterministicProjectConventionCompilation(json, new ProjectConventionDocumentStore()));

    @Test
    void adaptsExtractedLegacyMarkdownThroughTheSingleCompilation() {
        ProjectConventionLegacyAdapter.Adapted adapted = adapter.adapt("""
                preface
                <!-- LOOPPER_PROJECT_CONTEXT_START -->
                ## 技术栈与模块
                - Java 与 Maven。
                ## 构建与测试
                - `mvn test`
                ## 目录与边界
                - `pom.xml`
                <!-- LOOPPER_PROJECT_CONTEXT_END -->
                suffix
                """, "# Human\n", javaSnapshot());

        assertThat(adapted.compilation().accepted()).isTrue();
        assertThat(adapted.compilation().candidate().commandIds()).containsExactly("java-root:maven:test");
        assertThat(adapted.compilation().candidate().pathIds())
                .containsExactly("java-root:manifest:pom.xml");
        assertThat(adapted.compilation().proposedContent()).startsWith("# Human\n\n<!-- LOOPPER:START -->");
        assertThat(adapted.extraction().normalizations()).isEmpty();
    }

    @Test
    void snapshotDoesNotInventWrapperOrLockfileEvidenceAndRejectsTheWholeLegacyPayload() {
        assertThatThrownBy(() -> adapter.adapt("""
                ## 技术栈与模块
                - Java 与 Maven。
                ## 构建与测试
                - `./mvnw test`
                ## 目录与边界
                - `pom.xml`
                """, "", javaSnapshot()))
                .isInstanceOfSatisfying(BadRequestException.class, failure -> assertThat(failure.code())
                        .isEqualTo("PROJECT_CONVENTION_COMMAND_UNVERIFIED"));
    }

    private static ProjectStackSnapshot javaSnapshot() {
        return new ProjectStackSnapshot("stack-1", "project-1", ProjectStackProfileState.READY,
                "a".repeat(64), List.of("java"), List.of("java", "maven", "junit"), List.of(),
                1, null, null, "2026-09-02T00:00:00Z", List.of(
                new ProjectStackSnapshot.Component("java-root", ".", List.of("java"), List.of("java"),
                        List.of("maven"), List.of("junit"), List.of("pom.xml"), List.of())));
    }
}
