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
    void startupScriptsPinCurrentJarAndDiscoverOpenCodeWithoutFixedPort() throws IOException {
        String windows = Files.readString(PROJECT_ROOT.resolve("scripts/start-windows.bat"));
        String linux = Files.readString(PROJECT_ROOT.resolve("scripts/start-linux.sh"));

        assertThat(windows)
                .contains("opencode-loopper-0.1.18.jar")
                .contains("if %JAVA_MAJOR_NUMBER% LSS 21 goto java_too_old")
                .contains("%OPENCODE_BASE_URL%/global/health")
                .contains("call :discover_opencode")
                .contains("Get-CimInstance Win32_Process")
                .contains("--port[= ]+(\\d{1,5})")
                .contains("$health.healthy -eq $true")
                .contains("auto mode will start it on a dynamic loopback port")
                .contains("cmd /d /c exit 7")
                .contains("call :start_background \"Loopper Start Validation\"")
                .contains("%WAIT_URL%/actuator/health")
                .contains("-jar \"%JAR_PATH%\"")
                .doesNotContain("127.0.0.1:4096")
                .doesNotContain("serve --hostname 127.0.0.1 --port 4096");

        assertThat(linux)
                .contains("opencode-loopper-0.1.18.jar")
                .contains("discover_opencode_base_url()")
                .contains("ps -eo pid=,args=")
                .contains("running opencode process")
                .contains("LOOPPER_OPENCODE_MODE=\"auto\"")
                .contains("动态 loopback 端口")
                .doesNotContain("127.0.0.1:4096");
    }
}
