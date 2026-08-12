package io.opencode.loopper.service;

import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.VerifierRuntimeRow;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedVerificationRuntimeServiceTest {
    @TempDir Path directory;

    @Test
    void startsOnDynamicPortChecksReadinessBindsVerifierAndStopsCleanly() throws Exception {
        Harness harness = harness();
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        String classpath = Path.of(HttpRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        LoopSpec.VerificationRuntime contract = new LoopSpec.VerificationRuntime(
                List.of(java.toString(), "-cp", classpath, HttpRuntime.class.getName(), "{{LOOPPER_PORT}}"),
                new LoopSpec.RuntimeReadiness("/health", 200, "$.status", "UP", "EXACT"), 10, 5);

        var started = harness.service.start("task", "stage", "attempt", directory, contract);

        assertThat(started.failure()).isNull();
        assertThat(started.lease()).isNotNull();
        assertThat(started.lease().port()).isPositive();
        assertThat(started.lease().process().alive()).isTrue();
        LoopSpec.VerifierSpec bound = harness.service.bind(httpVerifier(), started.lease());
        assertThat(bound.url()).isEqualTo("http://127.0.0.1:" + started.lease().port() + "/health");

        var stopped = harness.service.stop(started.lease(), "test-complete").outcome();
        assertThat(stopped.state()).isEqualTo(VerificationState.PASS);
        assertThat(started.lease().process().alive()).isFalse();
        assertThat(harness.row.get().state()).isEqualTo("STOPPED");
        assertThat(Path.of(harness.row.get().tempDir())).doesNotExist();
        assertThat(harness.service.stop(started.lease(), "duplicate").outcome().state())
                .isEqualTo(VerificationState.PASS);
    }

    @Test
    void reportsEarlyExitAsVerificationFailure() {
        Harness harness = harness();
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        LoopSpec.VerificationRuntime contract = new LoopSpec.VerificationRuntime(
                List.of(java.toString(), "-version", "{{LOOPPER_PORT}}"),
                new LoopSpec.RuntimeReadiness("/health", 200, null, null, null), 5, 2);

        var started = harness.service.start("task", "stage", "attempt", directory, contract);

        assertThat(started.lease()).isNull();
        assertThat(started.failure()).isNotNull();
        assertThat(started.failure().state()).isEqualTo(VerificationState.FAIL);
        assertThat(started.failure().evidence().get("code")).isEqualTo("VERIFIER_RUNTIME_EARLY_EXIT");
        assertThat(harness.row.get().state()).isEqualTo("STOPPED");
    }

    @Test
    void neverKillsReusedPidWhenStartIdentityDoesNotMatch() {
        Harness harness = harness();
        VerifierRuntimeRow stale = new VerifierRuntimeRow("runtime", "task", "stage", "attempt", "RUNNING",
                ProcessHandle.current().pid(), Instant.EPOCH.toString(), 49152, "hash", "[]",
                directory.toString(), "{}", Instant.now().toString(), Instant.now().toString(), null, 0);
        harness.row.set(stale);
        when(harness.mapper.activeVerifierRuntimes()).thenReturn(List.of(stale));

        var result = harness.service.recoverActive();

        assertThat(result.blockedTaskIds()).containsExactly("task");
        assertThat(ProcessHandle.current().isAlive()).isTrue();
        assertThat(harness.row.get().state()).isEqualTo("DISCONNECTED");
    }

    @Test
    void failsWhenManagedRuntimeCreatesGitVisiblePollution() throws Exception {
        Files.writeString(directory.resolve("tracked.txt"), "before");
        run("git", "init");
        run("git", "config", "user.email", "test@example.invalid");
        run("git", "config", "user.name", "test");
        run("git", "add", "tracked.txt");
        run("git", "commit", "-m", "baseline");
        Harness harness = harness();
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        String classpath = Path.of(HttpRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        LoopSpec.VerificationRuntime contract = new LoopSpec.VerificationRuntime(
                List.of(java.toString(), "-cp", classpath, HttpRuntime.class.getName(), "{{LOOPPER_PORT}}",
                        directory.resolve("tracked.txt").toString()),
                new LoopSpec.RuntimeReadiness("/health", 200, null, null, null), 10, 5);

        var started = harness.service.start("task", "stage", "attempt", directory, contract);
        var stopped = harness.service.stop(started.lease(), "pollution-test").outcome();

        assertThat(stopped.state()).isEqualTo(VerificationState.FAIL);
        assertThat(stopped.evidence().get("code")).isEqualTo("VERIFIER_WORKSPACE_MUTATED");
        assertThat(harness.service.stop(started.lease(), "duplicate").outcome().state())
                .isEqualTo(VerificationState.FAIL);
    }

    @Test
    void stopsTheCompleteManagedChildProcessTree() throws Exception {
        Harness harness = harness();
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        String classpath = Path.of(HttpRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        LoopSpec.VerificationRuntime contract = new LoopSpec.VerificationRuntime(
                List.of(java.toString(), "-cp", classpath, HttpRuntime.class.getName(), "{{LOOPPER_PORT}}", "spawn-child"),
                new LoopSpec.RuntimeReadiness("/health", 200, null, null, null), 10, 5);

        var started = harness.service.start("task", "stage", "attempt", directory, contract);
        assertThat(started.failure()).as("managed runtime start failure").isNull();
        List<ProcessHandle> descendants = ProcessHandle.of(started.lease().process().pid()).orElseThrow()
                .descendants().toList();
        assertThat(descendants).isNotEmpty();

        assertThat(harness.service.stop(started.lease(), "tree-test").outcome().state())
                .isEqualTo(VerificationState.PASS);
        assertThat(descendants).allMatch(process -> !process.isAlive());
    }

    private Harness harness() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        AtomicReference<VerifierRuntimeRow> row = new AtomicReference<>();
        when(mapper.insertVerifierRuntime(any())).thenAnswer(invocation -> {
            row.set(invocation.getArgument(0));
            return 1;
        });
        when(mapper.updateVerifierRuntime(any())).thenAnswer(invocation -> {
            VerifierRuntimeRow value = invocation.getArgument(0);
            row.set(new VerifierRuntimeRow(value.id(), value.taskId(), value.stageId(), value.attemptId(), value.state(),
                    value.pid(), value.processStartInstant(), value.port(), value.argvSha256(), value.resolvedArgvJson(),
                    value.tempDir(), value.evidenceJson(), value.createdAt(), value.updatedAt(), value.endedAt(),
                    value.version() + 1));
            return 1;
        });
        when(mapper.findVerifierRuntime(any())).thenAnswer(invocation -> java.util.Optional.ofNullable(row.get()));
        when(mapper.activeVerifierRuntimes()).thenReturn(List.of());
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(directory.resolve("data"));
        ManagedVerificationRuntimeService service = new ManagedVerificationRuntimeService(mapper,
                new SafeProcessRunner(), properties, JsonMapper.builder().build());
        return new Harness(service, mapper, row);
    }

    private LoopSpec.VerifierSpec httpVerifier() {
        return new LoopSpec.VerifierSpec("HTTP_STATUS", null, null, null, List.of(), List.of(), false, null,
                "http://127.0.0.1:{{LOOPPER_PORT}}/health", "GET", 200, null, null, null, null, null,
                null, null, List.of(), List.of("AC-1"), null, List.of());
    }

    private static String executable(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
    }

    private void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError(output);
    }

    private record Harness(ManagedVerificationRuntimeService service, LoopperMapper mapper,
                           AtomicReference<VerifierRuntimeRow> row) { }

    public static final class HttpRuntime {
        public static void main(String[] args) throws Exception {
            int port = Integer.parseInt(args[0]);
            if (args.length > 1 && "spawn-child".equals(args[1])) {
                String javaName = System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe" : "java";
                Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
                new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                        ChildSleeper.class.getName()).start();
            } else if (args.length > 1) {
                Files.writeString(Path.of(args[1]), "changed by verifier");
            }
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/health", exchange -> {
                byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            new CountDownLatch(1).await();
        }
    }

    public static final class ChildSleeper {
        public static void main(String[] args) throws Exception { Thread.sleep(60_000); }
    }
}
