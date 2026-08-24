package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.ProjectStackProfileState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class ProjectStackProfileServiceTest {
    @Autowired private Flyway flyway;
    @Autowired private ProjectService projects;
    @Autowired private ProjectStackProfileService profiles;
    @Autowired private LoopperMapper mapper;
    @TempDir Path temporary;

    @BeforeEach void reset() { flyway.clean(); flyway.migrate(); }

    @Test void newAndReRegisteredProjectsAreAnalyzedAutomatically() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("managed"));
        Files.writeString(root.resolve("pom.xml"), "<project />");

        ProjectRow created = projects.create("managed", root.toString());
        assertThat(profiles.current(created.id()).technologyFamilies()).containsExactly("java");

        projects.cancelManagement(created.id());
        Files.delete(root.resolve("pom.xml"));
        Files.writeString(root.resolve("package.json"), "{}");
        projects.create("managed-again", root.toString());

        assertThat(profiles.current(created.id()).technologyFamilies()).containsExactly("node");
    }

    @Test void legacyProjectStaysUnanalyzedUntilAnExplicitLazyTrigger() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("legacy"));
        Files.writeString(root.resolve("pyproject.toml"), "[tool.pytest.ini_options]\n");
        String now = Instant.now().toString();
        ProjectRow legacy = new ProjectRow("legacy", "Legacy", root.toString(), "", now, now, 1, 0);
        mapper.insertProject(legacy);

        assertThat(profiles.current(legacy.id()).state()).isEqualTo(ProjectStackProfileState.UNANALYZED);
        assertThat(mapper.findCurrentProjectStackProfile(legacy.id())).isEmpty();

        ProjectStackSnapshot analyzed = profiles.ensureCurrent(legacy.id());
        assertThat(analyzed.state()).isEqualTo(ProjectStackProfileState.READY);
        assertThat(analyzed.technologies()).containsExactly("python");
    }

    @Test void changedManifestCreatesANewSnapshotWithoutMutatingTheOldOne() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("history"));
        Files.writeString(root.resolve("pom.xml"), "<project />");
        ProjectRow project = projects.create("history", root.toString());
        ProjectStackSnapshot java = profiles.current(project.id());

        Path frontend = Files.createDirectory(root.resolve("frontend"));
        Files.writeString(frontend.resolve("package.json"), "{}");
        ProjectStackSnapshot mixed = profiles.ensureCurrent(project.id());

        assertThat(mixed.id()).isNotEqualTo(java.id());
        assertThat(mixed.technologyFamilies()).containsExactly("java", "node");
        assertThat(profiles.get(project.id(), java.id()).technologyFamilies()).containsExactly("java");
    }

    @Test void failedAnalysisIsPersistedAndCanBeRetriedAfterTheRootRecovers() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("retry"));
        Files.writeString(root.resolve("pom.xml"), "<project />");
        ProjectRow project = projects.create("retry", root.toString());

        Files.delete(root.resolve("pom.xml"));
        Files.delete(root);
        ProjectStackSnapshot failed = profiles.forceRefresh(project.id());

        assertThat(failed.state()).isEqualTo(ProjectStackProfileState.FAILED);
        assertThat(failed.errorCode()).isEqualTo("PROJECT_STACK_ROOT_INVALID");

        Files.createDirectory(root);
        Files.writeString(root.resolve("package.json"), "{}");
        ProjectStackSnapshot retried = profiles.forceRefresh(project.id());

        assertThat(retried.state()).isEqualTo(ProjectStackProfileState.READY);
        assertThat(retried.technologyFamilies()).containsExactly("node");
        assertThat(retried.id()).isNotEqualTo(failed.id());
    }
}
