package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Durable ownership evidence for a process actually spawned by this application; contains no credentials. */
@Component
public class OwnedRuntimeGenerationStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;
    private final ProcessProbe processes;

    @org.springframework.beans.factory.annotation.Autowired
    public OwnedRuntimeGenerationStore(LoopperProperties properties) {
        this(properties.getDataDir(), new NativeProcessProbe());
    }
    OwnedRuntimeGenerationStore(Path data, ProcessProbe processes) {
        this.directory = canonical(data).resolve("runtime/owned-generations"); this.processes = processes;
    }

    /** Must run before a newly spawned runtime becomes available for sessions. */
    public void remember(String generation, Process process) {
        try {
            var started = process.info().startInstant().orElseThrow(() -> unavailable());
            var identity = new Identity(generation, process.pid(), started.toString());
            if (identity.pid() <= 0) throw unavailable();
            Path target = path(generation); check(target); Files.createDirectories(directory); check(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            Path temporary = directory.resolve(generation + "." + UUID.randomUUID() + ".part");
            Files.write(temporary, JSON.writeValueAsBytes(identity), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
            check(target); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException failure) { throw unavailable(); }
    }

    /** Absence, a different OS start instant, or confirmed exit proves the recorded process ended. */
    public Optional<String> exitProof(String generation, boolean stopIfStillAlive) {
        try {
            var identity = read(generation); if (identity.isEmpty()) return Optional.empty();
            var recorded = identity.get();
            if (exited(recorded)) return Optional.of(proof(recorded));
            if (!stopIfStillAlive) return Optional.empty();
            var current = processes.inspect(recorded.pid());
            if (current.isEmpty() || !current.get().alive()
                    || !Instant.parse(recorded.startedAt()).equals(current.get().startedAt())) return Optional.empty();
            // The implementation rechecks the start instant immediately before signalling the process.
            processes.stop(recorded.pid(), Instant.parse(recorded.startedAt()));
            return exited(recorded) ? Optional.of(proof(recorded)) : Optional.empty();
        } catch (RuntimeException | IOException unknown) { return Optional.empty(); }
    }

    private boolean exited(Identity recorded) {
        var current = processes.inspect(recorded.pid());
        if (current.isEmpty()) return true;
        if (!current.get().alive()) return true;
        return current.get().startedAt() != null && !current.get().startedAt().equals(Instant.parse(recorded.startedAt()));
    }
    private Optional<Identity> read(String generation) throws IOException {
        Path file = path(generation); check(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 2048) return Optional.empty();
        byte[] data;
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { data = in.readNBytes(2049); }
        if (data.length > 2048) return Optional.empty();
        var identity = JSON.readValue(data, Identity.class);
        if (!generation.equals(identity.generation()) || identity.pid() <= 0 || identity.startedAt() == null) return Optional.empty();
        Instant.parse(identity.startedAt()); return Optional.of(identity);
    }
    private Path path(String generation) {
        if (generation == null || !generation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw unavailable();
        return directory.resolve(generation + ".json");
    }
    private static String proof(Identity identity) { return "OWNED_PROCESS_EXITED:" + identity.generation() + ":" + identity.pid() + ":" + identity.startedAt(); }
    private static Path canonical(Path data) {
        Path absolute = data.toAbsolutePath().normalize(), existing = absolute;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        try { return existing == null ? absolute : existing.toRealPath().resolve(existing.relativize(absolute)); }
        catch (IOException failure) { throw unavailable(); }
    }
    private static void check(Path target) {
        for (Path path = target; path != null; path = path.getParent()) if (Files.isSymbolicLink(path)) throw unavailable();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Managed OpenCode process ownership evidence is unavailable"); }
    record Identity(String generation, long pid, String startedAt) { }
    record Observed(Instant startedAt, boolean alive) { }
    interface ProcessProbe {
        Optional<Observed> inspect(long pid);
        void stop(long pid, Instant startedAt);
    }
    private static class NativeProcessProbe implements ProcessProbe {
        public Optional<Observed> inspect(long pid) {
            return ProcessHandle.of(pid).map(process -> new Observed(process.info().startInstant().orElse(null), process.isAlive()));
        }
        public void stop(long pid, Instant startedAt) {
            var found = ProcessHandle.of(pid); if (found.isEmpty()) return;
            var process = found.get();
            if (!process.info().startInstant().filter(startedAt::equals).isPresent() || !process.isAlive()) return;
            process.destroy();
            if (awaitExit(process)) return;
            if (process.info().startInstant().filter(startedAt::equals).isPresent()) process.destroyForcibly();
            awaitExit(process);
        }
        private boolean awaitExit(ProcessHandle process) {
            try { process.onExit().get(1, TimeUnit.SECONDS); return true; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unknown) { return false; }
        }
    }
}
