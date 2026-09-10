package io.opencode.loopper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundledLauncherTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private static final boolean MAC = System.getProperty("os.name").startsWith("Mac");

    @TempDir
    Path temporary;

    @Test
    void bundledJavaLaunchesJarFromAnotherDirectoryAndPreservesArgumentsAndExitCode() throws Exception {
        Path bundle = prepareBundle();
        Path runtime = Path.of(System.getProperty("java.home")).toRealPath();
        Path linkedHome = bundle.resolve(MAC ? "jdk21/Contents/Home" : "jdk21");
        Files.createDirectories(linkedHome.getParent());
        if (WINDOWS) {
            Process link = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                    linkedHome.toString(), runtime.toString()).redirectErrorStream(true).start();
            String output = new String(link.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(link.waitFor()).as(output).isZero();
        } else {
            Files.createSymbolicLink(linkedHome, runtime);
        }
        try {
            Path source = temporary.resolve("LaunchProbe.java");
            Files.writeString(source, """
                    public class LaunchProbe {
                        public static void main(String[] args) {
                            System.out.println("PROBE_JAVA=" + System.getProperty("java.home"));
                            System.out.println("PROBE_ARGUMENT=" + args[0]);
                            System.out.println("PROBE_DATA=" + System.getenv("LOOPPER_DATA_DIR"));
                            System.exit(17);
                        }
                    }
                    """);
            assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, source.toString())).isZero();
            String version = Files.readString(ROOT.resolve("pom.xml"))
                    .split("<artifactId>opencode-loopper</artifactId>\\s*<version>")[1].split("</version>")[0];
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "LaunchProbe");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(
                    bundle.resolve("opencode-loopper-" + version + ".jar")), manifest)) {
                jar.putNextEntry(new JarEntry("LaunchProbe.class"));
                Files.copy(temporary.resolve("LaunchProbe.class"), jar);
                jar.closeEntry();
            }
            Result result = launch(bundle);
            assertThat(result.exitCode()).as(result.output()).isEqualTo(17);
            assertThat(result.output()).contains("PROBE_JAVA=", "PROBE_ARGUMENT=argument with spaces",
                    "PROBE_DATA=" + bundle.resolve("data"), "bundled jdk21");
        } finally {
            // Remove only this fixture's link, never traverse or remove the real JDK.
            Files.deleteIfExists(linkedHome);
        }
    }

    @Test
    void incompleteBundleFailsInsteadOfSilentlyUsingSystemJava() throws Exception {
        Path bundle = prepareBundle();
        Files.createDirectories(bundle.resolve("jdk21"));
        Result result = launch(bundle);
        assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
        assertThat(result.output()).doesNotContain("PROBE_JAVA=");
        assertThat(result.output()).contains(WINDOWS ? "Java was not found" : "找不到可执行的 Java");
    }

    private Path prepareBundle() throws Exception {
        Path bundle = Files.createDirectories(temporary.resolve("bundle with spaces"));
        // A pom.xml beside the extracted directory must not make it look like scripts/ in a checkout.
        Files.writeString(temporary.resolve("pom.xml"), "unrelated parent");
        for (String name : List.of("start-linux.sh", "start-macos.command", "start-windows.bat")) {
            Files.copy(ROOT.resolve("scripts").resolve(name), bundle.resolve(name));
        }
        return bundle;
    }

    private Result launch(Path bundle) throws Exception {
        String launcher = WINDOWS ? "start-windows.bat" : MAC ? "start-macos.command" : "start-linux.sh";
        // CALL keeps CMD from stripping the first quoted batch path when arguments also contain spaces.
        ProcessBuilder builder = WINDOWS
                ? new ProcessBuilder("cmd.exe", "/d", "/c", "call", bundle.resolve(launcher).toString(), "argument with spaces")
                : new ProcessBuilder("bash", bundle.resolve(launcher).toString(), "argument with spaces");
        builder.directory(temporary.toFile());
        for (String key : List.of("LOOPPER_JAVA_HOME", "LOOPPER_JAR_PATH", "LOOPPER_DATA_DIR")) {
            builder.environment().remove(key);
        }
        builder.environment().put("JAVA_HOME", temporary.resolve("absent-system-java").toString());
        builder.environment().put("LOOPPER_OPENCODE_MODE", "fake");
        builder.environment().put("OPENCODE_EXECUTABLE", "unused-launch-probe");
        builder.environment().put("LOOPPER_OPEN_BROWSER", "false");
        Path log = temporary.resolve("launcher.log");
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertThat(completed).as("launcher finishes within 30 seconds").isTrue();
        return new Result(process.exitValue(), Files.readString(log));
    }

    private record Result(int exitCode, String output) { }
}
