package io.opencode.loopper.verification;

import io.opencode.loopper.domain.LoopSpec.VerifierSpec;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.DirectWorkspaceBaselineManager;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifierEngineTest {
    private final VerifierEngine engine = new VerifierEngine(new SafeProcessRunner());
    @TempDir Path directory;

    @Test
    void gitDiffIncludesUntrackedFilesAndAppliesForbiddenPolicy() throws Exception {
        git("init"); git("config", "user.email", "test@example.invalid"); git("config", "user.name", "test");
        Files.writeString(directory.resolve("tracked.txt"), "base"); git("add", "tracked.txt"); git("commit", "-m", "base");
        String baseline = git("rev-parse", "HEAD").trim();
        Files.writeString(directory.resolve("untracked.txt"), "new");
        VerifierOutcome pass = engine.verify(directory, baseline, new VerifierSpec("GIT_DIFF", null, null, true, List.of("untracked.txt"), List.of(), false), Duration.ofSeconds(5));
        assertThat(pass.state()).isEqualTo(VerificationState.PASS);
        VerifierOutcome failed = engine.verify(directory, baseline, new VerifierSpec("GIT_DIFF", null, null, true, List.of(), List.of("untracked.txt"), false), Duration.ofSeconds(5));
        assertThat(failed.state()).isEqualTo(VerificationState.FAIL);
    }

    @Test
    void previewsModifiedAndNewFilesAsUnifiedDiffs() throws Exception {
        git("init"); git("config", "user.email", "test@example.invalid"); git("config", "user.name", "test");
        Files.writeString(directory.resolve("tracked.txt"), "before\ncontext\n");
        git("add", "tracked.txt"); git("commit", "-m", "base");
        String baseline = git("rev-parse", "HEAD").trim();
        Files.writeString(directory.resolve("tracked.txt"), "after\ncontext\n");
        Files.writeString(directory.resolve("new file.txt"), "new line\n");

        VerifierEngine.DiffPreview modified = engine.previewDiff(directory, baseline, "tracked.txt", false, Duration.ofSeconds(5));
        VerifierEngine.DiffPreview added = engine.previewDiff(directory, baseline, "new file.txt", true, Duration.ofSeconds(5));

        assertThat(modified.changeType()).isEqualTo("MODIFIED");
        assertThat(modified.patch()).contains("-before", "+after");
        assertThat(added.changeType()).isEqualTo("NEW");
        assertThat(added.patch()).contains("+new line");
        assertThatThrownBy(() -> engine.previewDiff(directory, baseline, "../outside.txt", false, Duration.ofSeconds(5)))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("escaped its worktree");
    }

    @Test
    void gitDiffPathPolicySupportsGlobRulesWithoutChangingPrefixSemantics() throws Exception {
        git("init"); git("config", "user.email", "test@example.invalid"); git("config", "user.name", "test");
        Files.createDirectories(directory.resolve("src/main"));
        Files.writeString(directory.resolve("src/main/App.java"), "base");
        git("add", "src/main/App.java"); git("commit", "-m", "base");
        String baseline = git("rev-parse", "HEAD").trim();
        Files.writeString(directory.resolve("src/main/App.java"), "changed");

        VerifierOutcome globPass = engine.verify(directory, baseline,
                new VerifierSpec("GIT_DIFF", null, null, true, List.of("src/**"), List.of("data/**"), false),
                Duration.ofSeconds(5));
        VerifierOutcome prefixPass = engine.verify(directory, baseline,
                new VerifierSpec("GIT_DIFF", null, null, true, List.of("src"), List.of(), false),
                Duration.ofSeconds(5));
        VerifierOutcome normalizedBackslashRulePass = engine.verify(directory, baseline,
                new VerifierSpec("GIT_DIFF", null, null, true, List.of("src\\**"), List.of(), false),
                Duration.ofSeconds(5));

        assertThat(globPass.state()).isEqualTo(VerificationState.PASS);
        assertThat(prefixPass.state()).isEqualTo(VerificationState.PASS);
        assertThat(normalizedBackslashRulePass.state()).isEqualTo(VerificationState.PASS);
        assertThatThrownBy(() -> engine.verify(directory, baseline,
                new VerifierSpec("GIT_DIFF", null, null, true, List.of("src/[invalid"), List.of(), false),
                Duration.ofSeconds(5)))
                .isInstanceOf(TaskFailure.class)
                .hasMessageContaining("Invalid verifier path pattern");
    }

    @Test
    void gitDiffChecksBothRenamePathsAndTreatsTheSourceAsADeletion() throws Exception {
        git("init"); git("config", "user.email", "test@example.invalid"); git("config", "user.name", "test");
        Files.createDirectories(directory.resolve("forbidden"));
        Files.writeString(directory.resolve("forbidden/secret.txt"), "base");
        git("add", "forbidden/secret.txt"); git("commit", "-m", "base");
        String baseline = git("rev-parse", "HEAD").trim();
        Files.createDirectories(directory.resolve("allowed"));
        git("mv", "forbidden/secret.txt", "allowed/secret.txt");

        VerifierOutcome outcome = engine.verify(directory, baseline,
                new VerifierSpec("GIT_DIFF", null, null, true, List.of("allowed/**"), List.of("forbidden/**"), true),
                Duration.ofSeconds(5));

        assertThat(outcome.state()).isEqualTo(VerificationState.FAIL);
        assertThat(outcome.evidence().get("changedPaths")).isEqualTo(List.of("forbidden/secret.txt", "allowed/secret.txt"));
        assertThat(outcome.summary())
                .contains("forbidden path: forbidden/secret.txt")
                .contains("outside allowed paths: forbidden/secret.txt")
                .contains("rename removes source path: forbidden/secret.txt");
    }

    @Test
    void directBaselineTracksProjectChangesWithoutAddingGitMetadataToProject() throws Exception {
        Path project = Files.createDirectory(directory.resolve("plain-project"));
        Files.writeString(project.resolve("README.md"), "base");
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(project.resolve(".loopper-data"));
        DirectWorkspaceBaselineManager baselines = new DirectWorkspaceBaselineManager(new SafeProcessRunner(), properties);
        String baseline = baselines.capture(project, "task-direct-1");

        Files.writeString(project.resolve("README.md"), "changed");
        VerifierOutcome outcome = new VerifierEngine(new SafeProcessRunner(), baselines).verify(project, baseline,
                new VerifierSpec("GIT_DIFF", null, null, true, List.of("README.md"), List.of(), true),
                Duration.ofSeconds(5));

        assertThat(outcome.state()).isEqualTo(VerificationState.PASS);
        assertThat(outcome.evidence().get("changedPaths")).isEqualTo(List.of("README.md"));
        assertThat(project.resolve(".git")).doesNotExist();
        VerifierEngine.DiffPreview preview = new VerifierEngine(new SafeProcessRunner(), baselines)
                .previewDiff(project, baseline, "README.md", false, Duration.ofSeconds(5));
        assertThat(preview.patch()).contains("-base", "+changed");
    }

    @Test
    void rejectsSymlinkThatResolvesOutsideWorktree() throws Exception {
        Path outside = Files.createTempFile("loopper-outside", ".txt");
        Path link = directory.resolve("outside-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException ex) { return; }
        assertThatThrownBy(() -> engine.verify(directory, "unused", new VerifierSpec("FILE_EXISTS", null, "outside-link", null, null, null, null), Duration.ofSeconds(1)))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("outside its worktree");
    }

    @Test
    void invalidPlatformPathBecomesTaskFailure() {
        assertThatThrownBy(() -> engine.verify(directory, "unused",
                new VerifierSpec("FILE_EXISTS", null, "bad\u0000path", null, null, null, null),
                Duration.ofSeconds(1)))
                .isInstanceOf(TaskFailure.class)
                .hasMessageContaining("not valid");
    }

    @Test
    void missingFileExistsVerifierIsRecordedWithoutBlockingLegacyTasks() {
        VerifierOutcome outcome = engine.verify(directory, "unused",
                new VerifierSpec("FILE_EXISTS", null, "target/model-guessed-output.txt", null, null, null, null),
                Duration.ofSeconds(1));

        assertThat(outcome.state()).isEqualTo(VerificationState.PASS);
        assertThat(outcome.summary()).contains("non-blocking");
        assertThat(outcome.evidence())
                .containsEntry("exists", false)
                .containsEntry("blocking", false);
    }

    @Test
    void drainsNoisyProcessWithoutDeadlock() {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        long startedAt = System.nanoTime();
        VerifierOutcome result = engine.verify(directory, "unused", new VerifierSpec("PROCESS",
                List.of(java, "-cp", System.getProperty("java.class.path"), NoisyProcessFixture.class.getName()),
                null, null, null, null, null), Duration.ofSeconds(15));
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(10));
        assertThat(result.state()).isEqualTo(VerificationState.FAIL);
        assertThat(result.summary()).contains("safe limit");
        assertThat(result.evidence()).containsEntry("outputTruncated", true);
    }

    @Test
    void processCanRequireDesignerSpecifiedOutputText() {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"), OutputFixture.class.getName());

        VerifierOutcome pass = engine.verify(directory, "unused",
                new VerifierSpec("PROCESS", command, null, null, null, null, null, "CROSS-CHECK PASS"), Duration.ofSeconds(5));
        VerifierOutcome fail = engine.verify(directory, "unused",
                new VerifierSpec("PROCESS", command, null, null, null, null, null, "MISSING MARKER"), Duration.ofSeconds(5));

        assertThat(pass.state()).isEqualTo(VerificationState.PASS);
        assertThat(pass.evidence()).containsEntry("outputMatched", true);
        assertThat(fail.state()).isEqualTo(VerificationState.FAIL);
        assertThat(fail.summary()).contains("MISSING MARKER");
        assertThat(fail.evidence()).containsEntry("outputMatched", false);
    }

    @Test
    void rejectsVerifierTimeoutAboveRuntimeSafetyLimit() {
        assertThatThrownBy(() -> engine.verify(directory, "unused",
                new VerifierSpec("FILE_NOT_EXISTS", null, "missing.txt", null, null, null, null),
                Duration.ofHours(2)))
                .isInstanceOf(TaskFailure.class)
                .hasMessageContaining("1 hour");
    }

    @Test
    void outputLimitTerminatesInheritedOutputDescendants() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        Path childPidFile = directory.resolve("descendant.pid");
        VerifierOutcome result = engine.verify(directory, "unused", new VerifierSpec("PROCESS",
                List.of(java, "-cp", System.getProperty("java.class.path"), ProcessTreeFixture.class.getName(), childPidFile.toString()),
                null, null, null, null, null), Duration.ofSeconds(15));

        assertThat(result.state()).isEqualTo(VerificationState.FAIL);
        assertThat(result.evidence()).containsEntry("outputTruncated", true);
        assertThat(childPidFile).exists();
        long childPid = Long.parseLong(Files.readString(childPidFile));
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        boolean childAlive = ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false);
        if (childAlive) ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
        assertThat(childAlive).isFalse();
    }

    @Test
    void gitDiffFailsClosedForTruncatedEvidenceAndExhaustedCombinedPolicyBudget() {
        SafeProcessRunner truncated = new SafeProcessRunner() {
            @Override public ProcessResult run(Path ignored, List<String> argv, Duration timeout) {
                return new ProcessResult(0, "M\tsrc/App.java\n", false, true);
            }
        };
        assertThatThrownBy(() -> new VerifierEngine(truncated).verify(directory, "baseline",
                new VerifierSpec("GIT_DIFF", null, null, true, List.of("src/**"), List.of(), false), Duration.ofSeconds(1)))
                .isInstanceOf(TaskFailure.class)
                .hasMessageContaining("safe evidence limit");

        String diff = IntStream.range(0, 200)
                .mapToObj(index -> "M\tpath-" + index + "-" + "x".repeat(1_000))
                .collect(Collectors.joining("\n"));
        List<String> rules = IntStream.range(0, 64).mapToObj(index -> "allowed-" + index).toList();
        SafeProcessRunner oversizedPolicy = new SafeProcessRunner() {
            @Override public ProcessResult run(Path ignored, List<String> argv, Duration timeout) {
                return argv.contains("diff") ? new ProcessResult(0, diff, false) : new ProcessResult(0, "", false);
            }
        };
        assertThatThrownBy(() -> new VerifierEngine(oversizedPolicy).verify(directory, "baseline",
                new VerifierSpec("GIT_DIFF", null, null, true, rules, List.of(), false), Duration.ofSeconds(1)))
                .isInstanceOf(TaskFailure.class)
                .hasMessageContaining("bounded matching budget");
    }

    @Test
    void rejectsShellWrappedVerifier() {
        assertThatThrownBy(() -> engine.verify(directory, "unused",
                new VerifierSpec("PROCESS", List.of(isWindows() ? "cmd.exe" : "sh", isWindows() ? "/c" : "-c", "echo unsafe"),
                        null, null, null, null, null), Duration.ofSeconds(1)))
                .isInstanceOf(TaskFailure.class)
                .hasMessageContaining("not a shell");
    }

    @Test
    void nativeHttpJsonAndFileVerifiersProduceBoundedStructuredEvidence() throws Exception {
        Files.writeString(directory.resolve("proof.txt"), "hello loopper");
        HttpServer server = server("{\"status\":\"ok\",\"items\":[\"one\"]}", "application/json");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/proof";
            VerifierOutcome http = engine.verify(directory, "unused", nativeSpec("HTTP_STATUS", null, url, "GET", 200, null, null, null, null, null, null, null), Duration.ofSeconds(5));
            VerifierOutcome json = engine.verify(directory, "unused", nativeSpec("JSON_PATH", null, url, "GET", null, "$.items[0]", "one", "EXACT", null, null, null, null), Duration.ofSeconds(5));
            VerifierOutcome content = engine.verify(directory, "unused", nativeSpec("FILE_CONTENT", "proof.txt", null, null, null, null, null, "CONTAINS", "loopper", null, null, null), Duration.ofSeconds(5));
            VerifierOutcome hash = engine.verify(directory, "unused", nativeSpec("FILE_HASH", "proof.txt", null, null, null, null, null, null, null,
                    BinaryArtifactStore.sha256("hello loopper".getBytes(StandardCharsets.UTF_8)), null, null), Duration.ofSeconds(5));

            assertThat(http.state()).isEqualTo(VerificationState.PASS);
            assertThat(http.evidence()).containsEntry("loopbackOnly", true).containsKey("bodySha256");
            assertThat(json.state()).isEqualTo(VerificationState.PASS);
            assertThat(json.evidence()).containsEntry("observedValue", "one");
            assertThat(content.state()).isEqualTo(VerificationState.PASS);
            assertThat(hash.state()).isEqualTo(VerificationState.PASS);
        } finally { server.stop(0); }
    }

    @Test
    void nativeJunitAndSqliteVerifiersFailClosedForUnsafeInput() throws Exception {
        Files.writeString(directory.resolve("report.xml"), "<testsuite tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"1\"/>");
        VerifierOutcome junit = engine.verify(directory, "unused", nativeSpec("JUNIT_XML", "report.xml", null, null, null, null, null, null, null, null, null, null), Duration.ofSeconds(5));
        assertThat(junit.state()).isEqualTo(VerificationState.PASS);
        assertThat(junit.evidence()).containsEntry("tests", 2L).containsEntry("skipped", 1L);
        Files.writeString(directory.resolve("unsafe.xml"), "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><testsuite/>");
        assertThatThrownBy(() -> engine.verify(directory, "unused", nativeSpec("JUNIT_XML", "unsafe.xml", null, null, null, null, null, null, null, null, null, null), Duration.ofSeconds(5)))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("DTD");

        Path database = directory.resolve("fixture.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("CREATE TABLE sample(value TEXT)");
            connection.createStatement().execute("INSERT INTO sample VALUES ('ok')");
        }
        VerifierOutcome query = engine.verify(directory, "unused", nativeSpec("DATABASE_QUERY", "fixture.sqlite", null, null, null, null, null, null, null, null, "SELECT value FROM sample", 1), Duration.ofSeconds(5));
        assertThat(query.state()).isEqualTo(VerificationState.PASS);
        assertThatThrownBy(() -> engine.verify(directory, "unused", nativeSpec("DATABASE_QUERY", "fixture.sqlite", null, null, null, null, null, null, null, null, "SELECT 1; DELETE FROM sample", null), Duration.ofSeconds(5)))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("one comment-free");
    }

    @Test
    void browserVerifierUsesOnlyLocalChromeLoopbackAndFilesystemArtifacts() throws Exception {
        HttpServer server = server("""
                <body data-ws="pending"><main id="proof">local browser proof</main>
                <script>
                  try { new WebSocket('ws://example.com/socket'); document.body.dataset.ws = 'attempted'; }
                  catch (ignored) { document.body.dataset.ws = 'attempted'; }
                </script></body>
                """, "text/html");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/browser";
            VerifierEngine browserEngine = new VerifierEngine(new SafeProcessRunner(), null, new BinaryArtifactStore(directory.resolve("loopper-data")));
            LoopSpec.BrowserAssertion visible = new LoopSpec.BrowserAssertion("VISIBLE", "#proof", null, null, null);
            LoopSpec.BrowserAssertion text = new LoopSpec.BrowserAssertion("TEXT_CONTAINS", "#proof", "browser proof", null, null);
            LoopSpec.BrowserAssertion websocketAttempted = new LoopSpec.BrowserAssertion(
                    "ATTRIBUTE_EQUALS", "body", "attempted", "data-ws", null);
            VerifierSpec spec = new VerifierSpec("BROWSER", List.of(), null, null, List.of(), List.of(), null, null,
                    url, "GET", null, null, null, null, null, null, null, null, List.of(visible, text, websocketAttempted));
            VerifierOutcome outcome = browserEngine.verify(directory, "unused", spec, Duration.ofSeconds(15));
            assertThat(outcome.state()).isEqualTo(VerificationState.PASS);
            assertThat((List<?>) outcome.evidence().get("artifacts")).hasSize(2);
            assertThat(outcome.evidence()).containsEntry("serviceWorkers", "BLOCK");
            assertThat(outcome.evidence()).containsEntry("externalNetworkPolicy", "DEAD_LOOPBACK_PROXY_AND_HOST_RESOLVER_DENY");
            assertThat(Files.list(directory.resolve("loopper-data/artifacts")).toList()).hasSize(2);
        } finally { server.stop(0); }
    }

    @Test
    void rejectsExternalHttpAndArbitraryBrowserSelectorsBeforeNetworkOrBrowserUse() {
        assertThatThrownBy(() -> engine.verify(directory, "unused", nativeSpec("HTTP_STATUS", null, "http://example.com", "GET", 200, null, null, null, null, null, null, null), Duration.ofSeconds(5)))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("loopback");
        assertThatThrownBy(() -> VerifierSafety.requireCssSelector("xpath=//body"))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("CSS selectors");
    }

    private HttpServer server(String response, String contentType) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start(); return server;
    }

    private VerifierSpec nativeSpec(String type, String path, String url, String method, Integer status, String jsonPath,
                                    String expectedValue, String matchMode, String expectedContent, String expectedHash,
                                    String sql, Integer expectedRows) {
        return new VerifierSpec(type, List.of(), path, null, List.of(), List.of(), null, null,
                url, method, status, jsonPath, expectedValue, matchMode, expectedContent, expectedHash, sql, expectedRows, List.of());
    }

    private boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }

    public static final class NoisyProcessFixture {
        public static void main(String[] args) throws Exception {
            if (args.length > 0) Thread.sleep(Long.parseLong(args[0]));
            String block = "x".repeat(8_192);
            while (true) System.out.print(block);
        }
    }

    public static final class OutputFixture {
        public static void main(String[] args) {
            System.out.println("CROSS-CHECK PASS");
        }
    }

    public static final class ProcessTreeFixture {
        public static void main(String[] args) throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", isWindowsStatic() ? "java.exe" : "java").toString();
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    NoisyProcessFixture.class.getName(), "500").inheritIO().start();
            Files.writeString(Path.of(args[0]), Long.toString(child.pid()));
            Thread.sleep(Long.MAX_VALUE);
        }
        private static boolean isWindowsStatic() { return System.getProperty("os.name").toLowerCase().contains("win"); }
    }

    private String git(String... args) throws Exception {
        String[] command = new String[args.length + 1]; command[0] = "git"; System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new AssertionError(output);
        return output;
    }
}
