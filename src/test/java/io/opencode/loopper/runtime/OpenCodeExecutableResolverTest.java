package io.opencode.loopper.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class OpenCodeExecutableResolverTest {
    @TempDir Path directory;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Path", "PATH"})
    void managedAndModelDiscoverySelectTheSameWindowsCommandInsteadOfTheUnixShim(String pathKey) throws Exception {
        Path bin = Files.createDirectories(directory.resolve("Program Files/OpenCode"));
        Files.writeString(bin.resolve("opencode"), "#!/bin/sh\n");
        Path cmd = Files.writeString(bin.resolve("opencode.cmd"), "@echo off\r\n");
        var env = Map.of(pathKey, "\"" + bin + "\"", "PathExt", ".EXE;.CMD;.BAT");
        var managed = new OpenCodeExecutableResolver("Windows 11", env).resolve("opencode");
        var discovery = new ExecutableResolver("Windows 11", env).resolve(directory, List.of("opencode", "models"));
        assertThat(managed).isEqualTo(Path.of(discovery.argv().getFirst())).isEqualTo(cmd.toAbsolutePath().normalize());
    }

    @Test void resolvesExeFromMixedCasePathAndKeepsConfiguredExeAheadOfEnvironment() throws Exception {
        Path bin = Files.createDirectories(directory.resolve("CLI tools"));
        Path exe = Files.writeString(bin.resolve("opencode.exe"), "fixture");
        Path explicit = Files.writeString(directory.resolve("custom tool.exe"), "fixture");
        var resolver = new OpenCodeExecutableResolver("Windows 11", Map.of("Path", bin.toString(),
                "OpenCode_Executable", exe.toString(), "PathExt", ".EXE;.CMD"));
        assertThat(resolver.resolve(explicit.toString())).isEqualTo(explicit.toAbsolutePath().normalize());
        assertThat(resolver.resolve("missing-command")).isEqualTo(exe.toAbsolutePath().normalize());
        assertThat(new OpenCodeExecutableResolver("Windows 11", Map.of("Path", bin.toString())).resolve("opencode"))
                .isEqualTo(exe.toAbsolutePath().normalize());
    }

    @Test void explicitExtensionlessPathUsesWindowsSuffixesAndNeverTheUnixShim() throws Exception {
        Path bare = Files.writeString(directory.resolve("opencode"), "#!/bin/sh\n");
        Path cmd = Files.writeString(directory.resolve("opencode.cmd"), "@echo off\r\n");
        assertThat(new OpenCodeExecutableResolver("Windows 11", Map.of()).resolve(bare.toString()))
                .isEqualTo(cmd.toAbsolutePath().normalize());
    }

    @Test void missingExecutableFailsBeforeTheManagedProcessIsStarted() throws Exception {
        Files.writeString(directory.resolve("opencode"), "#!/bin/sh\n");
        assertThatThrownBy(() -> new OpenCodeExecutableResolver("Windows 11", Map.of("Path", directory.toString())).resolve("opencode"))
                .isInstanceOf(IllegalStateException.class).hasMessage("OpenCode executable was not found in OPENCODE_EXECUTABLE or PATH");
    }

    @Test void nativeResolutionKeepsExplicitEnvironmentAndPathPrecedence() throws Exception {
        Path bin = Files.createDirectories(directory.resolve("bin"));
        Path path = Files.writeString(bin.resolve("opencode"), "#!/bin/sh\n");
        Path override = Files.writeString(directory.resolve("override"), "#!/bin/sh\n");
        Path configured = Files.writeString(directory.resolve("configured"), "#!/bin/sh\n");
        for (Path file : List.of(path, override, configured)) assertThat(file.toFile().setExecutable(true)).isTrue();
        var resolver = new OpenCodeExecutableResolver("Mac OS X", Map.of("PATH", bin.toString(), "OPENCODE_EXECUTABLE", override.toString()));
        assertThat(resolver.resolve(configured.toString())).isEqualTo(configured.toAbsolutePath().normalize());
        assertThat(resolver.resolve("missing")).isEqualTo(override.toAbsolutePath().normalize());
        assertThat(new OpenCodeExecutableResolver("Linux", Map.of("PATH", bin.toString())).resolve("opencode"))
                .isEqualTo(path.toAbsolutePath().normalize());
    }
}
