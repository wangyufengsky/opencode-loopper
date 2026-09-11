package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class GitEvidenceProcessTest {
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
}
