package io.opencode.loopper.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class SafeProcessRunnerTreeTest {
    @TempDir Path directory;

    @Test void outputOverflowReapsBothLevelsBeforeEndingTheirParents() throws Exception {
        var result = runTree("noise", Duration.ofSeconds(10));
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.timedOut()).isFalse();
        assertDescendantsReaped();
    }

    @Test void timeoutReapsBothLevelsBeforeEndingTheirParents() throws Exception {
        var result = runTree("sleep", Duration.ofSeconds(2));
        assertThat(result.timedOut()).isTrue();
        assertDescendantsReaped();
    }

    private ProcessResult runTree(String mode, Duration timeout) {
        return new SafeProcessRunner().run(directory, List.of(java(), "-cp", System.getProperty("java.class.path"),
                Tree.class.getName(), directory.toString(), "2", mode), timeout);
    }

    private void assertDescendantsReaped() throws Exception {
        for (int depth = 0; depth < 2; depth++) {
            Path file = directory.resolve(depth + ".pid");
            assertThat(file).exists();
            long pid = Long.parseLong(Files.readString(file));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).as("reaped tree level %s", depth).isFalse();
        }
    }

    private static String java() {
        String name = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    public static class Tree {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            int depth = Integer.parseInt(args[1]);
            Files.writeString(directory.resolve(depth + ".pid"), Long.toString(ProcessHandle.current().pid()));
            if (depth > 0) {
                new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"), Tree.class.getName(),
                        args[0], Integer.toString(depth - 1), args[2]).inheritIO().start();
            } else if (args[2].equals("noise")) {
                String block = "x".repeat(8192);
                while (true) System.out.print(block);
            }
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}
