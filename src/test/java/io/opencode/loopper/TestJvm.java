package io.opencode.loopper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Process isolation for fixtures that load vendor native code or retain JVM-wide JDBC handles. */
public final class TestJvm {
    private TestJvm() { }

    public static void run(Class<?> entry, Path directory) throws Exception {
        Path arguments = directory.resolve("java.args"), log = directory.resolve("child.log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "-cp\n" + quote(classpath) + "\n" + entry.getName() + "\n" + quote(directory.toString()) + "\n");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(java.toString(), "@" + arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(180, TimeUnit.SECONDS)) throw new AssertionError("Fixture JVM timed out: " + tail(log));
            if (process.exitValue() != 0) throw new AssertionError("Fixture JVM exited " + process.exitValue() + ": " + tail(log));
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly();
                if (!process.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("Fixture JVM stop was not confirmed");
            }
        }
    }

    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String tail(Path log) throws Exception {
        String text = Files.readString(log);
        return text.substring(Math.max(0, text.length() - 16_000));
    }
}
