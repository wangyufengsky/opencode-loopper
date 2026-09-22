package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnedRuntimeGenerationStoreTest {
    @TempDir Path data;
    private static final long PID = 42813;
    private static final Instant STARTED = Instant.parse("2026-09-22T01:00:00Z");
    private final String generation = UUID.randomUUID().toString();
    private final Probe probe = new Probe();
    @Test void durableOwnershipSurvivesStoreRestartAndGenerationChangeAloneProvesNothing() throws Exception {
        var first = new OwnedRuntimeGenerationStore(data, probe); first.remember(generation, owned());
        var restarted = new OwnedRuntimeGenerationStore(data, probe);
        assertThat(restarted.exitProof(generation, false)).isEmpty();
        assertThat(restarted.exitProof(UUID.randomUUID().toString(), true)).isEmpty();
        assertThat(probe.stops).isZero();
        probe.observed = Optional.empty();
        assertThat(restarted.exitProof(generation, false)).hasValueSatisfying(proof -> assertThat(proof).startsWith("OWNED_PROCESS_EXITED:" + generation));
        assertThat(Files.readString(manifest())).contains("startedAt", generation).doesNotContain("password", "bearer", "scope");
    }
    @Test void reusedPidIsAnExitProofAndNeverSignalsReplacementProcess() {
        var store = new OwnedRuntimeGenerationStore(data, probe); store.remember(generation, owned());
        probe.observed = Optional.of(new OwnedRuntimeGenerationStore.Observed(STARTED.plusSeconds(10), true));
        assertThat(store.exitProof(generation, true)).isPresent(); assertThat(probe.stops).isZero();
    }
    @Test void unknownStartIdentityAndUnconfirmedStopRemainBlocked() {
        var store = new OwnedRuntimeGenerationStore(data, probe); store.remember(generation, owned());
        assertThat(store.exitProof(generation, true)).isEmpty(); assertThat(probe.stops).isEqualTo(1);
        probe.observed = Optional.of(new OwnedRuntimeGenerationStore.Observed(null, true));
        assertThat(store.exitProof(generation, true)).isEmpty();
        probe.observed = Optional.of(new OwnedRuntimeGenerationStore.Observed(STARTED, false));
        assertThat(store.exitProof(generation, true)).isPresent();
    }
    @Test void confirmedStopAfterRetiringExactOwnedProcessReleasesGeneration() {
        var store = new OwnedRuntimeGenerationStore(data, probe); store.remember(generation, owned());
        probe.stopSucceeds = true;
        assertThat(store.exitProof(generation, true)).isPresent(); assertThat(probe.stops).isEqualTo(1);
    }
    @Test void missingOwnershipIdentityCannotBecomeRecoverableRuntime() {
        var process = owned(); when(process.info().startInstant()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new OwnedRuntimeGenerationStore(data, probe).remember(generation, process)).hasMessageContaining("ownership evidence");
        assertThat(Files.exists(manifest())).isFalse();
    }
    @Test void corruptOrSymlinkOwnershipFilesCannotAuthorizeSignalsOrExitProof() throws Exception {
        var store = new OwnedRuntimeGenerationStore(data, probe); store.remember(generation, owned());
        Files.writeString(manifest(), "{}"); assertThat(store.exitProof(generation, true)).isEmpty();
        Path elsewhere = data.resolve("elsewhere.json"); Files.move(manifest(), elsewhere); Files.createSymbolicLink(manifest(), elsewhere);
        assertThat(store.exitProof(generation, true)).isEmpty(); assertThat(probe.stops).isZero();
    }
    @Test void actualOsProcessIdentityCanBeRetiredAfterApplicationObjectRestart() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"), Sleeper.class.getName()).start();
        var properties = new LoopperProperties(); properties.setDataDir(data);
        try {
            new OwnedRuntimeGenerationStore(properties).remember(generation, process);
            assertThat(process.isAlive()).isTrue();
            var restored = new OwnedRuntimeGenerationStore(properties);
            assertThat(restored.exitProof(generation, false)).isEmpty();
            assertThat(restored.exitProof(generation, true)).isPresent();
            assertThat(process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    public static class Sleeper { public static void main(String[] args) throws Exception { Thread.sleep(30_000); } }
    private Path manifest() { return data.resolve("runtime/owned-generations/" + generation + ".json"); }
    private Process owned() {
        var process = mock(Process.class); var info = mock(ProcessHandle.Info.class);
        when(process.pid()).thenReturn(PID); when(process.info()).thenReturn(info); when(info.startInstant()).thenReturn(Optional.of(STARTED));
        return process;
    }
    private static class Probe implements OwnedRuntimeGenerationStore.ProcessProbe {
        Optional<OwnedRuntimeGenerationStore.Observed> observed = Optional.of(new OwnedRuntimeGenerationStore.Observed(STARTED, true));
        int stops; boolean stopSucceeds;
        public Optional<OwnedRuntimeGenerationStore.Observed> inspect(long pid) { assertThat(pid).isEqualTo(PID); return observed; }
        public void stop(long pid, Instant startedAt) {
            assertThat(pid).isEqualTo(PID); assertThat(startedAt).isEqualTo(STARTED); stops++;
            if (stopSucceeds) observed = Optional.empty();
        }
    }
}
