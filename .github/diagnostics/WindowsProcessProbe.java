import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** TEMPORARY control: no application, Maven, database, or test-framework dependencies. */
class WindowsProcessProbe {
    public static void main(String[] args) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        for (var command : List.of(List.of(java, "-version"), List.of("cmd.exe", "/d", "/c", "exit", "0"))) {
            long started = System.nanoTime();
            for (int i = 1; i <= 300; i++) {
                var child = new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (!child.waitFor(30, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    throw new IllegalStateException("[DEBUG-process-control] child timeout at iteration " + i);
                }
                if (child.exitValue() != 0) throw new IllegalStateException("[DEBUG-process-control] "
                        + command.getFirst() + " iteration=" + i + " exit=" + child.exitValue());
                if (i % 25 == 0) System.out.println("[DEBUG-process-control] " + command.getFirst() + " completed=" + i
                        + " elapsedMs=" + Duration.ofNanos(System.nanoTime() - started).toMillis());
            }
        }
    }
}
