package io.opencode.loopper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ReleasePackagingContractTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));

    @TempDir
    Path tempDir;

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
                .contains("opencode-loopper-0.2.15.jar")
                .contains("STARTUP_OVERRIDES=%LOOPPER_DATA_DIR%\\config\\startup-overrides.properties")
                .contains("$line.Split('=',2)")
                .contains("if not defined SERVER_PORT set \"SERVER_PORT=%%L\"")
                .contains("LOOPPER_PUBLICATION_HTTP_WEB_HOSTS=gitlab.spdb.com")
                .contains("LOOPPER_GITLAB_HOST=gitlab.spdb.com")
                .contains("LOOPPER_GITLAB_API_BASE_URL=http://gitlab.spdb.com/api/v4")
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
                .doesNotContain("call \"%STARTUP_OVERRIDES%\"")
                .doesNotContain("127.0.0.1:4096")
                .doesNotContain("serve --hostname 127.0.0.1 --port 4096");

        assertThat(linux)
                .contains("opencode-loopper-0.2.15.jar")
                .contains("STARTUP_OVERRIDES=\"${LOOPPER_DATA_DIR}/config/startup-overrides.properties\"")
                .contains("printf -v \"${key}\" '%s' \"${value}\"")
                .contains("LOOPPER_PUBLICATION_HTTP_WEB_HOSTS=\"${LOOPPER_PUBLICATION_HTTP_WEB_HOSTS:-gitlab.spdb.com}\"")
                .contains("LOOPPER_GITLAB_HOST=\"${LOOPPER_GITLAB_HOST:-gitlab.spdb.com}\"")
                .contains("LOOPPER_GITLAB_API_BASE_URL=\"${LOOPPER_GITLAB_API_BASE_URL:-http://gitlab.spdb.com/api/v4}\"")
                .contains("discover_opencode_base_url()")
                .contains("ps -eo pid=,args=")
                .contains("lsof -nP -a -p")
                .contains("ss -H -ltnp")
                .contains("ss -H -ltn")
                .contains("OPENCODE_SERVER_PASSWORD")
                .contains("type -P opencode")
                .contains("environment wildcard normalized to loopback")
                .contains("running opencode process")
                .contains("LOOPPER_OPENCODE_MODE=\"auto\"")
                .contains("动态 loopback 端口")
                .doesNotContain("source \"${STARTUP_OVERRIDES}\"")
                .doesNotContain("eval ")
                .doesNotContain("127.0.0.1:4096");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void linuxStartupDiscoversTuiServerFromItsListeningPort() throws Exception {
        assertLinuxTuiDiscovery("lsof");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void linuxStartupFallsBackToSsForTuiServerDiscovery() throws Exception {
        assertLinuxTuiDiscovery("ss");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void linuxStartupHealthChecksListenersWhenSocketOwnershipIsHidden() throws Exception {
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path javaHome = fakeJavaHome();
        Path jar = Files.writeString(tempDir.resolve("loopper.jar"), "test");
        executable(bin.resolve("ps"), "#!/usr/bin/env bash\nprintf '999 unrelated-service\\n'\n");
        executable(bin.resolve("lsof"), "#!/usr/bin/env bash\nexit 0\n");
        executable(bin.resolve("ss"), """
                #!/usr/bin/env bash
                printf 'LISTEN 0 20 0.0.0.0:54321 0.0.0.0:*\n'
                """);
        executable(bin.resolve("curl"), healthCurl("http://127.0.0.1:54321/global/health", null));

        String output = runLinuxStartup(bin, javaHome, jar, Map.of());

        assertThat(output)
                .contains("OpenCode：http://127.0.0.1:54321（来源：running opencode process）")
                .contains("JAVA_OPENCODE_BASE_URL=http://127.0.0.1:54321")
                .contains("JAVA_OPENCODE_MODE=http");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void linuxStartupNormalizesWildcardAddressAndUsesOfficialServerCredentials() throws Exception {
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path javaHome = fakeJavaHome();
        Path jar = Files.writeString(tempDir.resolve("loopper.jar"), "test");
        executable(bin.resolve("curl"), healthCurl("http://127.0.0.1:54321/global/health", "opencode:secret"));

        String output = runLinuxStartup(bin, javaHome, jar, Map.of(
                "OPENCODE_BASE_URL", "http://0.0.0.0:54321",
                "OPENCODE_SERVER_PASSWORD", "secret"));

        assertThat(output)
                .contains("OpenCode：http://127.0.0.1:54321（来源：environment wildcard normalized to loopback）")
                .contains("JAVA_OPENCODE_BASE_URL=http://127.0.0.1:54321")
                .contains("JAVA_OPENCODE_USERNAME=opencode")
                .contains("JAVA_OPENCODE_PASSWORD_SET=true");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void linuxStartupPinsTheResolvedCliBeforeManagedAutoStartup() throws Exception {
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path javaHome = fakeJavaHome();
        Path jar = Files.writeString(tempDir.resolve("loopper.jar"), "test");
        executable(bin.resolve("ps"), "#!/usr/bin/env bash\nprintf '999 unrelated-service\\n'\n");
        executable(bin.resolve("lsof"), "#!/usr/bin/env bash\nexit 0\n");
        executable(bin.resolve("ss"), "#!/usr/bin/env bash\nexit 0\n");
        executable(bin.resolve("curl"), healthCurl("http://127.0.0.1:1/global/health", null));
        executable(bin.resolve("opencode"), "#!/usr/bin/env bash\nexit 0\n");

        String output = runLinuxStartup(bin, javaHome, jar, Map.of());

        assertThat(output)
                .contains("OpenCode：未发现可复用端点，将由 auto 模式在动态 loopback 端口启动")
                .contains("OpenCode CLI：" + bin.resolve("opencode"))
                .contains("JAVA_OPENCODE_EXECUTABLE=" + bin.resolve("opencode"))
                .contains("JAVA_HTTP_WEB_HOSTS=gitlab.spdb.com")
                .contains("JAVA_OPENCODE_MODE=auto");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void linuxStartupUsesEnvironmentThenSettingsFileThenScriptDefaults() throws Exception {
        Path bin = Files.createDirectories(tempDir.resolve("settings-bin"));
        Path javaHome = fakeJavaHome();
        Path jar = Files.writeString(tempDir.resolve("settings-loopper.jar"), "test");
        executable(bin.resolve("ps"), "#!/usr/bin/env bash\nprintf '999 unrelated-service\\n'\n");
        executable(bin.resolve("lsof"), "#!/usr/bin/env bash\nexit 0\n");
        executable(bin.resolve("ss"), "#!/usr/bin/env bash\nexit 0\n");
        executable(bin.resolve("curl"), "#!/usr/bin/env bash\nexit 22\n");
        executable(bin.resolve("opencode"), "#!/usr/bin/env bash\nexit 0\n");
        Path config = Files.createDirectories(tempDir.resolve("data/config")).resolve("startup-overrides.properties");
        Files.writeString(config, """
                SERVER_PORT=9090
                LOOPPER_RETRY_SESSION_BASE=20s
                LOOPPER_ALLOWED_ROOT=/tmp/project dir & more
                UNKNOWN_STARTUP_KEY=ignored
                """);

        String output = runLinuxStartup(bin, javaHome, jar, Map.of("SERVER_PORT", "7070"));

        assertThat(output)
                .contains("JAVA_SERVER_PORT=7070")
                .contains("JAVA_RETRY_SESSION_BASE=20s")
                .contains("JAVA_RETRY_SESSION_MAX=")
                .contains("JAVA_ALLOWED_ROOT=/tmp/project dir & more")
                .contains("忽略启动配置中的未知键：UNKNOWN_STARTUP_KEY");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void linuxStartupRejectsAnInvalidKnownSettingsFileValue() throws Exception {
        Path config = Files.createDirectories(tempDir.resolve("data/config")).resolve("startup-overrides.properties");
        Files.writeString(config, "SERVER_PORT=not-a-port\n");
        ProcessBuilder builder = new ProcessBuilder("bash", PROJECT_ROOT.resolve("scripts/start-linux.sh").toString());
        builder.environment().put("LOOPPER_DATA_DIR", tempDir.resolve("data").toString());
        builder.environment().remove("SERVER_PORT");
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isEqualTo(1);
        assertThat(output).contains("启动配置 SERVER_PORT 的值无效");
    }

    private void assertLinuxTuiDiscovery(String listenerTool) throws Exception {
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path javaHome = fakeJavaHome();
        Path jar = Files.writeString(tempDir.resolve("loopper.jar"), "test");
        executable(bin.resolve("ps"), """
                #!/usr/bin/env bash
                printf '4321 opencode\n'
                """);
        executable(bin.resolve("lsof"), listenerTool.equals("lsof") ? """
                #!/usr/bin/env bash
                printf 'p4321\nn127.0.0.1:54321\n'
                """ : "#!/usr/bin/env bash\nexit 0\n");
        executable(bin.resolve("ss"), listenerTool.equals("ss") ? """
                #!/usr/bin/env bash
                printf 'LISTEN 0 128 127.0.0.1:54321 0.0.0.0:* users:(("opencode",pid=4321,fd=8))\n'
                """ : "#!/usr/bin/env bash\nexit 0\n");
        executable(bin.resolve("curl"), healthCurl("http://127.0.0.1:54321/global/health", null));

        String output = runLinuxStartup(bin, javaHome, jar, Map.of());

        assertThat(output)
                .contains("OpenCode：http://127.0.0.1:54321（来源：running opencode process）")
                .contains("JAVA_OPENCODE_BASE_URL=http://127.0.0.1:54321")
                .contains("JAVA_OPENCODE_MODE=http");
    }

    private Path fakeJavaHome() throws IOException {
        Path javaHome = Files.createDirectories(tempDir.resolve("jdk/bin")).getParent();
        executable(javaHome.resolve("bin/java"), """
                #!/usr/bin/env bash
                if [[ "${1:-}" == "-version" ]]; then
                  echo 'openjdk version "21.0.2"' >&2
                  exit 0
                fi
                printf 'JAVA_OPENCODE_BASE_URL=%s\nJAVA_OPENCODE_MODE=%s\nJAVA_OPENCODE_USERNAME=%s\nJAVA_OPENCODE_PASSWORD_SET=%s\nJAVA_OPENCODE_EXECUTABLE=%s\nJAVA_HTTP_WEB_HOSTS=%s\nJAVA_SERVER_PORT=%s\nJAVA_RETRY_SESSION_BASE=%s\nJAVA_RETRY_SESSION_MAX=%s\nJAVA_ALLOWED_ROOT=%s\n' \
                  "${OPENCODE_BASE_URL:-}" "${LOOPPER_OPENCODE_MODE:-}" "${OPENCODE_USERNAME:-}" \
                  "$([[ -n "${OPENCODE_PASSWORD:-}" ]] && printf true || printf false)" "${OPENCODE_EXECUTABLE:-}" \
                  "${LOOPPER_PUBLICATION_HTTP_WEB_HOSTS:-}" "${SERVER_PORT:-}" \
                  "${LOOPPER_RETRY_SESSION_BASE:-}" "${LOOPPER_RETRY_SESSION_MAX:-}" "${LOOPPER_ALLOWED_ROOT:-}"
                """);
        return javaHome;
    }

    private String runLinuxStartup(Path bin, Path javaHome, Path jar, Map<String, String> overrides) throws Exception {

        ProcessBuilder builder = new ProcessBuilder("bash", PROJECT_ROOT.resolve("scripts/start-linux.sh").toString());
        Map<String, String> environment = builder.environment();
        environment.put("PATH", bin + ":" + environment.get("PATH"));
        environment.put("LOOPPER_JAVA_HOME", javaHome.toString());
        environment.put("LOOPPER_JAR_PATH", jar.toString());
        environment.put("LOOPPER_DATA_DIR", tempDir.resolve("data").toString());
        environment.put("LOOPPER_OPEN_BROWSER", "false");
        environment.remove("OPENCODE_BASE_URL");
        environment.remove("LOOPPER_OPENCODE_MODE");
        environment.remove("OPENCODE_USERNAME");
        environment.remove("OPENCODE_PASSWORD");
        environment.remove("OPENCODE_SERVER_USERNAME");
        environment.remove("OPENCODE_SERVER_PASSWORD");
        environment.putAll(overrides);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isZero();
        return output;
    }

    private static String healthCurl(String expectedUrl, String expectedCredentials) {
        String credentialCheck = expectedCredentials == null ? "" : """
                [[ " $* " == *" --user %s "* ]] || exit 22
                """.formatted(expectedCredentials);
        return """
                #!/usr/bin/env bash
                %s
                url="${!#}"
                if [[ "${url}" == "%s" ]]; then
                  printf '{"healthy":true,"version":"test"}\n'
                  exit 0
                fi
                exit 22
                """.formatted(credentialCheck, expectedUrl);
    }

    private static void executable(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        assertThat(path.toFile().setExecutable(true)).isTrue();
    }
}
