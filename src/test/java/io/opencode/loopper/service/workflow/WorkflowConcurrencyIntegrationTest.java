package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowFixtures.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.service.ConflictException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes=LoopperApplication.class, properties={"loopper.opencode.mode=fake"})
class WorkflowConcurrencyIntegrationTest {
    private static final Path DATA = directory();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("loopper.data-dir", () -> DATA.toString());
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("workflow.db")
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void prepare() { flyway.clean(); flyway.migrate(); }

    @Test void concurrentReplayCreatesOneTemplateAndOneAcknowledgement() throws Exception {
        var request = new WorkflowRequests.CreateTemplate(key(), "并发创建", "", single(), null);
        var values = race(() -> templates.create(request), () -> templates.create(request));
        assertThat(values.getFirst()).isEqualTo(values.getLast());
        for (String table : List.of("workflow_template", "workflow_template_revision", "workflow_command"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void concurrentEditsAcceptOnlyOneVersionAndNeverRewriteTheFirstDefinition() throws Exception {
        var created = templates.create(new WorkflowRequests.CreateTemplate(key(), "初始", "", single(), null));
        var left = new WorkflowRequests.ReviseTemplate(key(), 0, 1, "左侧", "", chain());
        var right = new WorkflowRequests.ReviseTemplate(key(), 0, 1, "右侧", "", single());
        var outcomes = race(() -> revise(created.id(), left), () -> revise(created.id(), right));
        assertThat(outcomes).containsExactlyInAnyOrder("ACCEPTED", "CONFLICT");
        assertThat(templates.get(created.id(), null).headRevision()).isEqualTo(2);
        assertThat(templates.get(created.id(), 1).graph()).isEqualTo(single());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_template_revision", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_command", Integer.class)).isEqualTo(2);
    }
    private String revise(String id, WorkflowRequests.ReviseTemplate request) {
        try { templates.revise(id, request); return "ACCEPTED"; }
        catch (ConflictException expected) { return "CONFLICT"; }
    }
    private static <T> List<T> race(Supplier<T> first, Supplier<T> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var one = threads.submit(() -> run(ready, start, first));
            var two = threads.submit(() -> run(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        } finally { start.countDown(); }
    }
    private static <T> T run(CountDownLatch ready, CountDownLatch start, Supplier<T> work) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("race did not start");
        return work.get();
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static Path directory() {
        try { return Files.createTempDirectory("loopper-workflow-concurrency-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
