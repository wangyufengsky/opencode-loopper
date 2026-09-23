package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class SourceUnitFoundationIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired SourceTemplateService service;
    @Autowired SourceTemplateAdmission admission;
    @Autowired SourceTemplateCoordinator coordinator;
    @Autowired SourceUnitScopeGuard guard;
    @Autowired SourceTestProfileService profiles;
    @Autowired SourceDevelopmentPromotion promotion;
    @Autowired DocumentDevelopmentDesign designs;
    @Autowired io.opencode.loopper.runtime.OpenCodeClient client;
    @Autowired io.opencode.loopper.runtime.InternalMcpRuntimeAccess access;
    @Autowired LoopperMapper domain;
    @Autowired ProjectService projects;
    @Autowired LoopperProperties properties;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temporary;
    private Path root;
    private String project;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); properties.getOpenCode().setModel("fake/test-model");
        root = Files.createDirectory(temporary.resolve("source")).toRealPath();
        Files.createDirectories(root.resolve("src/main/java/example")); Files.createDirectories(root.resolve("src/test/java/example"));
        Files.writeString(root.resolve("pom.xml"), "<project><dependencies><dependency><artifactId>junit-jupiter</artifactId></dependency></dependencies></project>");
        Files.writeString(root.resolve("src/main/java/example/Service.java"), "package example; class Service { int value() { return 3; } }");
        Files.writeString(root.resolve("src/test/java/example/ServiceTest.java"), "package example;\nclass ServiceTest {\n @org.junit.jupiter.api.Test void value() { org.junit.jupiter.api.Assertions.assertEquals(3, new Service().value()); }\n}\n");
        project = projects.create("单测范围", root.toString(), "fixture").id();
    }
    @Test void freezesNativeMappingAndCreatesGenuineSourceDesignerWithoutDocumentRowsOrTaskStart() {
        var run = start();
        assertThat(run.state()).isEqualTo("DESIGNING");
        assertThat(profiles.require(run.id()).modules()).singleElement().satisfies(module -> {
            assertThat(module.testRoots()).containsExactly("src/test/java"); assertThat(module.framework()).isEqualTo("junit");
            assertThat(module.sourcePaths()).containsExactly("src/main/java/example/Service.java");
        });
        coordinator.advance(run.id()); run = admission.require(run.id());
        assertThat(run.designerId()).isNotNull(); assertThat(run.taskId()).isNull();
        var designer = domain.findDesignerSession(run.designerId()).orElseThrow();
        var revision = domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        assertThat(revision.requirementText()).startsWith("LOOPPER_SOURCE_TEST_INDEX_V1");
        assertThat(revision.requirementText()).contains("补齐空值和边界场景");
        assertThat(domain.sourceDevelopmentDesign(revision.id(), designer.id())).isPresent();
        assertThat(domain.documentDesign(revision.id(), designer.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_template_run", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease", Integer.class)).isZero();
    }
    @Test void allowsAddedTestsAndFixturesButRejectsBusinessBuildAndOutsideAdditions() throws Exception {
        var run = start();
        Files.writeString(root.resolve("src/test/java/example/BoundaryTest.java"), "package example; class BoundaryTest {}");
        Files.createDirectories(root.resolve("src/test/resources")); Files.writeString(root.resolve("src/test/resources/fixture.json"), "{}");
        Files.createDirectories(root.resolve("target/classes")); Files.writeString(root.resolve("target/classes/Service.class"), "build output");
        assertThatCode(() -> guard.check(run, root)).doesNotThrowAnyException();
        Path outside = root.resolve("new-business.java"); Files.writeString(outside, "class Business {}");
        assertThatThrownBy(() -> guard.check(run, root)).isInstanceOf(TaskFailure.class).hasMessageContaining("new-business.java");
        Files.delete(outside);
        Path hidden = root.resolve("src/main/java/target/Hidden.java");
        Files.createDirectories(hidden.getParent()); Files.writeString(hidden, "class Hidden {}");
        assertThatThrownBy(() -> guard.check(run, root)).hasMessageContaining("Hidden.java"); Files.delete(hidden);
        Files.writeString(root.resolve("pom.xml"), "changed build");
        assertThatThrownBy(() -> guard.check(run, root)).hasMessageContaining("pom.xml");
        Files.writeString(root.resolve("src/main/java/example/Service.java"), "changed source");
        assertThatThrownBy(() -> guard.check(run, root)).hasMessageContaining("Service.java");
    }
    @Test void identifiesDeclaredMavenModulesAndInheritsFrameworkWithoutChangingBuild() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><packaging>pom</packaging><modules><module>a</module><module>b</module></modules>"
                + "<dependencyManagement><dependencies><dependency><artifactId>junit-jupiter</artifactId></dependency></dependencies></dependencyManagement></project>");
        for (String module : List.of("a", "b")) {
            Files.createDirectories(root.resolve(module + "/src/main/java"));
            Files.writeString(root.resolve(module + "/src/main/java/Service.java"), "class Service {}");
            Files.writeString(root.resolve(module + "/pom.xml"), "<project><parent><relativePath>../pom.xml</relativePath></parent></project>");
        }
        var request = new SourceTemplateRequests.Create(UUID.randomUUID().toString(), "UNIT_TEST_DEVELOPMENT", "1", project, ".", null, null, "");
        var preview = service.preview(request);
        assertThat(preview.configurationProblem()).isNull();
        assertThat(preview.testProfile().modules()).hasSize(3);
        for (String module : List.of("a", "b")) assertThat(preview.testProfile().modules().stream().filter(m -> m.root().equals(module)).findFirst().orElseThrow())
                .satisfies(m -> { assertThat(m.testRoots()).containsExactly(module + "/src/test/java");
                    assertThat(m.command()).containsExactly("mvn", "-f", "pom.xml", "-pl", module, "-am", "test"); });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_template_run", Integer.class)).isZero();
    }
    @Test void rejectsDeletedRenamedAndWeakenedExistingTests() throws Exception {
        var run = start(); Path test = root.resolve("src/test/java/example/ServiceTest.java"); String original = Files.readString(test);
        Files.writeString(test, original.replace("assertEquals(3", "assertEquals(4"));
        assertThatThrownBy(() -> guard.check(run, root)).hasMessageContaining("断言被移除或改写");
        Files.writeString(test, original.replace("@org.junit.jupiter.api.Test", "@org.junit.jupiter.api.Disabled @org.junit.jupiter.api.Test"));
        assertThatThrownBy(() -> guard.check(run, root)).hasMessageContaining("屏蔽测试");
        Files.move(test, test.resolveSibling("RenamedTest.java"));
        assertThatThrownBy(() -> guard.check(run, root)).hasMessageContaining("删除或重命名");
    }
    @Test void missingFrameworkWaitsWithoutInstallingOrStartingAnyModel() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>"); Files.delete(root.resolve("src/test/java/example/ServiceTest.java"));
        var request = request(); var run = service.create(request);
        admission.start(run.id(), new SourceTemplateRequests.Command(UUID.randomUUID().toString(), run.version()));
        coordinator.checkpoint(run.id()); coordinator.checkpoint(run.id());
        run = admission.require(run.id());
        assertThat(run.state()).isEqualTo("WAITING_INPUT"); assertThat(run.waitingReasonCode()).isEqualTo("SOURCE_TEST_CONFIGURATION_REQUIRED");
        assertThat(run.designerId()).isNull(); assertThat(Files.readString(root.resolve("pom.xml"))).isEqualTo("<project/>");
    }
    @Test void gitBuildProductsRequireExistingIgnoreRulesAndCannotEnterSourceDelivery() throws Exception {
        var init = new ProcessBuilder("git", "init", "-b", "main").directory(root.toFile()).redirectErrorStream(true).start();
        init.getInputStream().readAllBytes(); assertThat(init.waitFor()).isZero();
        var preview = service.preview(request());
        assertThat(preview.configurationProblem()).contains("target", "Git", "不会修改 .gitignore");
        Files.writeString(root.resolve(".gitignore"), "target/\n");
        assertThat(service.preview(request()).configurationProblem()).isNull();
        Files.createDirectories(root.resolve("target")); Files.writeString(root.resolve("target/tracked.txt"), "old tracked build");
        var add = new ProcessBuilder("git", "add", "-f", "target/tracked.txt").directory(root.toFile()).redirectErrorStream(true).start();
        add.getInputStream().readAllBytes(); assertThat(add.waitFor()).isZero();
        assertThat(service.preview(request()).configurationProblem()).contains("已跟踪文件");
    }
    @Test void firstStartRejectsAnyDriftAndOldVersionControlsDoNotChangeState() throws Exception {
        var run = start(); coordinator.advance(run.id());
        assertThat(admission.require(run.id()).designerId()).isNotNull();
        Files.writeString(root.resolve("src/main/java/example/Service.java"), "new business");
        assertThatThrownBy(() -> guard.check(run, root)).hasMessageContaining("Service.java");
        assertThatThrownBy(() -> admission.start(run.id(), new SourceTemplateRequests.Command(UUID.randomUUID().toString(), 0))).isInstanceOf(ConflictException.class);
    }
    @Test void capacityPromotionRetainsSourceAndSpentBudgetAndRequiresStoppedOldWriter() {
        var credentials = new io.opencode.loopper.runtime.InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (io.opencode.loopper.runtime.FakeOpenCodeClient) client;
        fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        var run = start(); coordinator.advance(run.id()); run = admission.require(run.id());
        String designerId = run.designerId(), runId = run.id();
        assertThatThrownBy(() -> promotion.advance(runId)).isInstanceOf(ConflictException.class);
        designs.startSingle(designerId);
        var before = domain.findCurrentDesignRequirementRevision(designerId).orElseThrow();
        jdbc.update("UPDATE design_requirement_revision SET model_calls_used=7 WHERE id=?", before.id());
        jdbc.update("UPDATE designer_session SET state='WAITING_INPUT',version=version+1 WHERE id=?", designerId);
        jdbc.update("UPDATE design_work_package SET state='WAITING_INPUT',last_error_code='LARGE_TASK_MODE_REQUIRED',version=version+1 WHERE designer_session_id=?", designerId);
        fake.failNextAborts(10);
        assertThatThrownBy(() -> promotion.advance(runId)).isInstanceOf(RuntimeException.class);
        assertThat(domain.findCurrentDesignRequirementRevision(designerId).orElseThrow().id()).isEqualTo(before.id());
        assertThat(promotion.pending(runId)).isTrue();
        fake.failNextAborts(0);
        promotion.advance(runId); promotion.advance(runId);
        var after = domain.findCurrentDesignRequirementRevision(designerId).orElseThrow();
        assertThat(after.id()).isNotEqualTo(before.id()); assertThat(after.revision()).isEqualTo(2);
        assertThat(after.requirementText()).isEqualTo(before.requirementText());
        assertThat(after.requirementSegmentsJson()).isEqualTo(before.requirementSegmentsJson());
        assertThat(after.modelCallsUsed()).isEqualTo(7); assertThat(after.maxModelCalls()).isEqualTo(before.maxModelCalls());
        assertThat(domain.sourceDevelopmentDesign(after.id(), designerId).orElseThrow().manifestSha256())
                .isEqualTo(profiles.require(runId).manifestSha256());
        assertThat(domain.findCurrentDesignerTaskProfile(designerId).orElseThrow().workflowTemplate()).isEqualTo("FULL_PACKAGE_DESIGN");
        assertThat(promotion.pending(runId)).isFalse();
        assertThat(admission.require(runId).taskId()).isNull();
    }
    private SourceTemplateRunRow start() {
        var row = service.create(request()); admission.start(row.id(), new SourceTemplateRequests.Command(UUID.randomUUID().toString(), row.version()));
        coordinator.advance(row.id()); return admission.require(row.id());
    }
    private SourceTemplateRequests.Create request() {
        return new SourceTemplateRequests.Create(UUID.randomUUID().toString(), "UNIT_TEST_DEVELOPMENT", "1", project, "src/main", null, null, "补齐空值和边界场景");
    }
}
