package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutableResolverTest {
    @TempDir Path directory;

    @Test
    void resolvesWindowsMavenAndNpmCmdFilesFromCaseInsensitiveQuotedPath() throws Exception {
        Path bin = Files.createDirectories(directory.resolve("Program Files/tools/bin"));
        Path maven = Files.writeString(bin.resolve("mvn.cmd"), "@echo off\r\n");
        Path npm = Files.writeString(bin.resolve("npm.CMD"), "@echo off\r\n");
        ExecutableResolver resolver = new ExecutableResolver("Windows 10", Map.of(
                "Path", "\"" + bin + "\"", "PathExt", ".EXE;.CMD;.BAT"));

        assertThat(resolver.resolve(directory, List.of("mvn", "-v")))
                .isEqualTo(new ExecutableResolver.Resolution(
                        List.of(maven.toAbsolutePath().normalize().toString(), "-v"), "WINDOWS_PATHEXT_PATH"));
        String resolvedNpm = resolver.resolve(directory, List.of("npm", "--version")).argv().getFirst();
        assertThat(Path.of(resolvedNpm)).isRegularFile();
        assertThat(Path.of(resolvedNpm).getFileName().toString()).isEqualToIgnoringCase(npm.getFileName().toString());
    }

    @Test
    void resolvesWindowsProjectWrappersBeforePath() throws Exception {
        Path mavenWrapper = Files.writeString(directory.resolve("mvnw.cmd"), "@echo off\r\n");
        Path gradleWrapper = Files.writeString(directory.resolve("gradlew.bat"), "@echo off\r\n");
        ExecutableResolver resolver = new ExecutableResolver("Windows Server", Map.of(
                "PATH", directory.resolve("missing-bin").toString(), "PATHEXT", ".EXE;.CMD;.BAT"));

        assertThat(resolver.resolve(directory, List.of("./mvnw", "test")).argv().getFirst())
                .isEqualTo(mavenWrapper.toAbsolutePath().normalize().toString());
        assertThat(resolver.resolve(directory, List.of(".\\gradlew", "test")).argv().getFirst())
                .isEqualTo(gradleWrapper.toAbsolutePath().normalize().toString());
    }

    @Test
    void missingWindowsCommandFailsBeforeProcessStart() {
        ExecutableResolver resolver = new ExecutableResolver("Windows 10", Map.of(
                "PATH", directory.toString(), "PATHEXT", ".EXE;.CMD"));

        assertThatThrownBy(() -> resolver.resolve(directory, List.of("missing-tool", "test")))
                .isInstanceOfSatisfying(TaskFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("PROCESS_COMMAND_UNAVAILABLE"))
                .hasMessageContaining("missing-tool")
                .hasMessageContaining("Loopper process PATH");
    }

    @Test
    void nonWindowsKeepsNativeArgvResolution() {
        ExecutableResolver resolver = new ExecutableResolver("Linux", Map.of("PATH", "/opt/tools/bin"));
        assertThat(resolver.resolve(directory, List.of("mvn", "test")))
                .isEqualTo(new ExecutableResolver.Resolution(List.of("mvn", "test"), null));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void runsResolvedWindowsCmdWithoutCallerSuppliedShell() throws Exception {
        Path tool = Files.writeString(directory.resolve("loopper-check.cmd"),
                "@echo off\r\necho WINDOWS_CMD_OK:%~1\r\n");
        ExecutableResolver resolver = new ExecutableResolver(System.getProperty("os.name"), Map.of(
                "PATH", directory.toString(), "PATHEXT", ".EXE;.CMD"));

        ProcessResult result = new SafeProcessRunner(resolver).run(directory,
                List.of("loopper-check", "path with spaces"), Duration.ofSeconds(5));

        assertThat(tool).isRegularFile();
        assertThat(System.getProperty("jdk.lang.Process.allowAmbiguousCommands")).isEqualTo("false");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("WINDOWS_CMD_OK:path with spaces");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void preservesLinuxAndMacRelativeExecutableWithSpacesInWorkingPath() throws Exception {
        Path tool = Files.writeString(directory.resolve("loopper-check"),
                "#!/usr/bin/env sh\nprintf 'UNIX_EXEC_OK\\n'\n");
        assertThat(tool.toFile().setExecutable(true)).isTrue();

        ProcessResult result = new SafeProcessRunner(new ExecutableResolver()).run(directory,
                List.of("./loopper-check"), Duration.ofSeconds(5));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("UNIX_EXEC_OK");
    }
}
