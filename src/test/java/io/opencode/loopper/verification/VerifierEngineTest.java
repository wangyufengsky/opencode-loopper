package io.opencode.loopper.verification;

import io.opencode.loopper.domain.LoopSpec.VerifierSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.DirectWorkspaceBaselineManager;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
