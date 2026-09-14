package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.DocumentCodeMapper;
import io.opencode.loopper.persistence.DocumentTemplateMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class DocumentCodeSnapshotIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired DocumentTemplateService service;
    @Autowired DocumentTemplateMapper runs;
    @Autowired DocumentCodeMapper files;
    @Autowired DocumentCodeSnapshotService snapshots;
    @Autowired ProjectService projects;
    @Autowired LoopperProperties properties;
    @Autowired ObjectMapper json;
    @TempDir Path temporary;
    private Path source;
    private String project;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); properties.getOpenCode().setModel("fake/test-model");
        source = Files.createDirectory(temporary.resolve("source"));
        git(source, "init", "--initial-branch=main", "--template=");
        git(source, "config", "user.name", "Snapshot Fixture"); git(source, "config", "user.email", "fixture@example.invalid");
        Files.writeString(source.resolve("service.txt"), "permission checked\n");
        Files.writeString(source.resolve(".env"), "TEST_ONLY=fake\n");
        git(source, "add", "."); git(source, "commit", "-m", "original requirement implemented");
        project = projects.create("静态评审夹具", source.toString(), "test").id();
    }
    @Test void freezesCommittedTreeWithoutDirtyChangesAndIgnoresLaterBranchMoves() throws Exception {
        String head = git(source, "rev-parse", "HEAD").strip();
        Files.writeString(source.resolve("service.txt"), "dirty workspace must not be reviewed\n");
        var run = create();
        var frozen = json.readValue(run.snapshotJson(), DocumentCodeSnapshotStore.Snapshot.class);
        assertThat(frozen.sha()).isEqualTo(head); assertThat(frozen.ready()).isTrue();
        var file = files.file(run.id(), "service.txt").orElseThrow();
        assertThat(git(snapshots.repository(run.id()), "cat-file", "blob", file.blobSha())).isEqualTo("permission checked\n");
        assertThat(files.file(run.id(), ".env").orElseThrow().limitation()).contains("受保护");
        assertThat(Files.readString(source.resolve("service.txt"))).contains("dirty workspace");
        git(source, "add", "service.txt"); git(source, "commit", "-m", "later changed branch");
        assertThat(snapshots.freeze(runs.find(run.id()).orElseThrow()).sha()).isEqualTo(head);
        assertThat(git(source, "symbolic-ref", "--short", "HEAD").strip()).isEqualTo("main");
    }
    @Test void capturesCurrentAbsenceAndFetchesOneCommitRegardlessOfHistoryLength() throws Exception {
        for (int i = 0; i < 12; i++) git(source, "commit", "--allow-empty", "-m", "unrelated history " + i);
        Files.delete(source.resolve("service.txt")); git(source, "add", "."); git(source, "commit", "-m", "remove implementation");
        var run = create();
        assertThat(files.file(run.id(), "service.txt")).isEmpty();
        assertThat(git(snapshots.repository(run.id()), "rev-list", "--count", "refs/heads/frozen").strip()).isEqualTo("1");
        assertThat(git(source, "status", "--porcelain")).isEmpty();
    }
    private io.opencode.loopper.persistence.DocumentTemplateRunRow create() {
        return service.create(new DocumentTemplateService.Request(UUID.randomUUID().toString(), "REQUIREMENT_CODE_REVIEW", "1", project,
                "local:refs/heads/main"), List.of(new DocumentTemplateStorage.Incoming("需求.md", "# 权限\n必须检查权限".getBytes(StandardCharsets.UTF_8))));
    }
    private static String git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null")); command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("%s", command).isZero(); return output;
    }
}
