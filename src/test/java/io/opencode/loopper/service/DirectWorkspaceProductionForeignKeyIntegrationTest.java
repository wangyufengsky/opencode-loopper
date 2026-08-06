package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Exercises the production foreign_keys=on URL, which the shared memory test DB cannot clean reliably. */
@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h"
})
class DirectWorkspaceProductionForeignKeyIntegrationTest {
    private static final Path DATA = temporaryDataDirectory();

    @DynamicPropertySource
    static void productionLikeSqlite(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("loopper.db")
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL");
        properties.add("loopper.data-dir", () -> DATA.toString());
    }

    @Autowired private ProjectService projects;
    @Autowired private LoopDraftService drafts;
    @Autowired private TaskService tasks;
    @Autowired private LoopperMapper mapper;

    @Test
    void directWriterLeaseReferencesTheDurableLocalExecutionSession() throws Exception {
        Path root = Files.createDirectory(DATA.resolve("direct-project"));
        Files.writeString(root.resolve("README.md"), "fixture");
        ProjectRow project = projects.create("foreign-key-direct", root.toString());
        LoopSpec spec = new LoopSpec("v1", project.id(), "Verify writer FK", "", List.of(
                new LoopSpec.StageSpec("run", List.of(), List.of(), List.of("evidence"), List.of(
                        new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null);
        TaskRow task = drafts.confirm(drafts.create(spec).id(), "foreign key writer");

        tasks.start(task.id());

        var session = mapper.activeSessions(task.id()).getFirst();
        var queue = mapper.findTaskQueue(task.id()).orElseThrow();
        var lease = mapper.findWorkspaceLease(queue.canonicalRoot()).orElseThrow();
        assertThat(lease.writerSessionId()).isEqualTo(session.id());
        assertThat(lease.writerSessionId()).isNotEqualTo(session.externalSessionId());
    }

    private static Path temporaryDataDirectory() {
        try { return Files.createTempDirectory("loopper-production-fk-"); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
}
