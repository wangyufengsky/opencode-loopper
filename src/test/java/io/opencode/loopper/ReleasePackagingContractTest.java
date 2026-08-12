package io.opencode.loopper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReleasePackagingContractTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));

    @Test
    void releasePublishesBothPlatformStartupScriptsWithChecksums() throws IOException {
        String workflow = Files.readString(PROJECT_ROOT.resolve(".github/workflows/release.yml"));

        assertThat(workflow)
                .contains("bash -n scripts/start-linux.sh")
                .contains("scripts\\start-windows.bat --validate")
                .contains("cp scripts/start-linux.sh target/release/")
                .contains("cp scripts/start-windows.bat target/release/")
                .contains("sha256sum \"${{ steps.version.outputs.jar_name }}\" start-linux.sh start-windows.bat")
                .contains("target/release/start-linux.sh#Linux intranet startup script")
                .contains("target/release/start-windows.bat#Windows startup script");
    }

    @Test
    void windowsScriptPinsCurrentJarAndUsesHealthInsteadOfStaleStartErrorLevel() throws IOException {
        String script = Files.readString(PROJECT_ROOT.resolve("scripts/start-windows.bat"));

        assertThat(script)
                .contains("opencode-loopper-0.1.16.jar")
                .contains("if %JAVA_MAJOR_NUMBER% LSS 21 goto java_too_old")
                .contains("%OPENCODE_BASE_URL%/global/health")
                .contains("cmd /d /c exit 7")
                .contains("call :start_background \"Loopper OpenCode Server\"")
                .contains("START is asynchronous and can preserve an earlier nonzero ERRORLEVEL on success")
                .contains("goto opencode_start_timeout")
                .contains("%WAIT_URL%/actuator/health")
                .contains("-jar \"%JAR_PATH%\"")
                .doesNotContain("if errorlevel 1 goto opencode_start_failed");
    }
}
