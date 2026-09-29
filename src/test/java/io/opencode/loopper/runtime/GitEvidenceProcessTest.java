package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitEvidenceProcessTest {
    @TempDir Path directory;
    @Test void parentExitAfterTimeoutDoesNotLoseUnconfirmedChild() {
        Process process = mock(Process.class);
        ProcessHandle child = mock(ProcessHandle.class);
        AtomicBoolean first = new AtomicBoolean(true);
        when(process.descendants()).thenAnswer(ignored -> first.getAndSet(false) ? Stream.of(child) : Stream.empty());
        when(process.isAlive()).thenReturn(false);
        when(child.isAlive()).thenReturn(true);
        var scope = new GitEvidenceProcess.ProcessScope(process);
        scope.stop();
        assertThat(scope.stopConfirmed()).isFalse();
        when(child.isAlive()).thenReturn(false);
        assertThat(scope.stopConfirmed()).isTrue();
    }
    @Test void binaryStandardInputIsExactAndBoundedBeforeStartingAProcess() throws Exception {
        var runner=new SafeProcessRunner();var git=new GitEvidenceProcess(runner);
        byte[] bytes={0,(byte)0xff,13,10,(byte)0xc0,42};
        var args=List.of("hash-object","--no-filters","--stdin");
        var result=git.input(directory,Duration.ofSeconds(5),args,Map.of(),bytes);result.requireSuccess(args);
        var digest=MessageDigest.getInstance("SHA-1");digest.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(result.output(),StandardCharsets.US_ASCII).strip()).isEqualTo(HexFormat.of().formatHex(digest.digest(bytes)));
        var unused=mock(SafeProcessRunner.class);
        assertThatThrownBy(()->new GitEvidenceProcess(unused).input(directory,Duration.ofSeconds(5),args,Map.of(),new byte[4_000_001]))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(unused);
    }
    @Test void childThatDoesNotReadInputCannotBlockTheDeadlineAndIsConfirmedStopped() throws Exception {
        Path pid=directory.resolve("child.pid");
        var runner=mock(SafeProcessRunner.class);
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
        when(runner.resolve(any(),anyList())).thenReturn(new ExecutableResolver.Resolution(
                List.of(java,"-cp",System.getProperty("java.class.path"),InputSleeper.class.getName(),pid.toString()),null));
        assertThatThrownBy(()->new GitEvidenceProcess(runner).input(directory,Duration.ofSeconds(2),List.of("hash-object","--stdin"),Map.of(),new byte[2_000_000]))
                .isInstanceOfSatisfying(TaskFailure.class,error->assertThat(error.code()).isEqualTo("TEMPLATE_GIT_TIMEOUT"));
        long id=Long.parseLong(Files.readString(pid));
        assertThat(ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
    public static class InputSleeper {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]),Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(30_000);
        }
    }
}
