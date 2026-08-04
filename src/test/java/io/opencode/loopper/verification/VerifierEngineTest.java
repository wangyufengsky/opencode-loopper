package io.opencode.loopper.verification;

import io.opencode.loopper.domain.LoopSpec.VerifierSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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

        assertThat(globPass.state()).isEqualTo(VerificationState.PASS);
        assertThat(prefixPass.state()).isEqualTo(VerificationState.PASS);
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
    void drainsNoisyProcessWithoutDeadlock() {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        VerifierOutcome result = engine.verify(directory, "unused", new VerifierSpec("PROCESS",
                List.of(java, "-cp", System.getProperty("java.class.path"), NoisyProcessFixture.class.getName()),
                null, null, null, null, null), Duration.ofSeconds(5));
        assertThat(result.state()).isEqualTo(VerificationState.PASS);
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
        public static void main(String[] ignored) {
            System.out.print("x".repeat(200_000));
        }
    }

    private String git(String... args) throws Exception {
        String[] command = new String[args.length + 1]; command[0] = "git"; System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new AssertionError(output);
        return output;
    }
}
