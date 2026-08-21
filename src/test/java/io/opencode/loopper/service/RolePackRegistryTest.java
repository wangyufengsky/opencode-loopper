package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.TaskIntent;
import java.util.List;
import org.junit.jupiter.api.Test;

class RolePackRegistryTest {
    private final RolePackRegistry registry = new RolePackRegistry();

    @Test void aliasesFromOneTechnologyFamilyDoNotBecomeMixedStack() {
        RolePackRegistry.RolePack pack = registry.resolve(TaskIntent.SOFTWARE_CHANGE,
                List.of("java", "Java 8", "Spring Boot", "Maven", "JUnit 5", "Surefire"),
                List.of(ArtifactKind.SOURCE_CODE));

        assertThat(pack.id()).isEqualTo("software-java");
        assertThat(pack.version()).isEqualTo("2026-08-dynamic-v5");
        assertThat(RolePackRegistry.supportsDeterministicAcceptance("2026-08-dynamic-v4")).isTrue();
        assertThat(RolePackRegistry.supportsDeterministicAcceptance(pack.version())).isTrue();
    }

    @Test void javascriptIsNodeAndNeverMatchesTheJavaFamily() {
        assertThat(registry.resolve(TaskIntent.SOFTWARE_CHANGE,
                List.of("JavaScript", "TypeScript", "Vue"), List.of(ArtifactKind.SOURCE_CODE)).id())
                .isEqualTo("software-node");
        assertThat(WorkPackageRoleService.hasJavaSignal("JavaScript frontend listener"))
                .isFalse();
        assertThat(WorkPackageRoleService.hasJavaSignal("Spring Boot Java 8 listener"))
                .isTrue();
    }

    @Test void onlyRealCrossFamilyWorkIsMixedAndUnknownStacksStayGeneric() {
        assertThat(registry.resolve(TaskIntent.SOFTWARE_CHANGE,
                List.of("Java 21", "Vue 3"), List.of(ArtifactKind.SOURCE_CODE)).id())
                .isEqualTo("software-mixed");
        assertThat(registry.resolve(TaskIntent.SOFTWARE_CHANGE,
                List.of("Go"), List.of(ArtifactKind.SOURCE_CODE)).id())
                .isEqualTo("software-generic");
        assertThat(registry.resolve(TaskIntent.SOFTWARE_CHANGE,
                List.of(), List.of(ArtifactKind.SOURCE_CODE)).id())
                .isEqualTo("software-java");
    }
}
