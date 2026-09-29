package io.opencode.loopper.runtime;

import static io.opencode.loopper.runtime.DurableCommandProtocol.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** JDK-only detached supervisor. A durable identity and matching application grant precede effects. */
public final class DurableCommandWorker {
    private DurableCommandWorker() { }
    public static void main(String[] args) {
        if (args.length != 2) return;
        try { execute(Path.of(args[0]), args[1]); }
        catch (IOException | RuntimeException | InterruptedException unknown) {
            // A missing final receipt is UNKNOWN, never a fabricated stop proof. No raw arguments or secrets in stderr.
            System.err.println("Command supervision ended without a verified receipt.");
        }
    }
    static void execute(Path directory, String id) throws IOException, InterruptedException {
        UUID.fromString(id); check(directory);
        if (!directory.isAbsolute() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid job directory");
        try (var channel = FileChannel.open(directory.resolve("worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = channel.tryLock()) {
            if (lock == null || Files.exists(directory.resolve("registration"), LinkOption.NOFOLLOW_LINKS)) return;
            byte[] bytes = read(directory.resolve("request")); Request request = request(bytes);
            if (!request.id().equals(id)) throw new IOException("Command identity differs");
            var registration = new Registration(hash(bytes), Identity.current());
            publish(directory.resolve("registration"), registration(registration));
            boolean granted = awaitGrant(directory, registration);
            if (!granted) {
                publish(directory.resolve("result"), result(new Result(registration.requestSha256(), registration.worker(), null,
                        false, false, true, false, true, "", "GRANT_NOT_RECEIVED", List.of()))); return;
            }
            Path workspace = Path.of(request.directory()); check(workspace);
            if (!workspace.toRealPath().equals(workspace)) throw new IOException("Command directory identity differs");
            publish(directory.resolve("launch-intent"), registration(registration));
            Result result = run(directory, request, registration);
            publish(directory.resolve("result"), result(result));
        }
    }
    private static boolean awaitGrant(Path directory, Registration registration) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(directory.resolve("stop"), LinkOption.NOFOLLOW_LINKS)) return false;
            if (Files.exists(directory.resolve("grant"), LinkOption.NOFOLLOW_LINKS))
                return registration(read(directory.resolve("grant"))).equals(registration);
            Thread.sleep(25);
        }
        return false;
    }
    private static Result run(Path directory, Request request, Registration registration) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(request.timeoutSeconds()).toNanos();
        int remaining = OUTPUT_LIMIT;
        var preparations = new ArrayList<Result>();
        for (int i = 0; i < request.preparations().size(); i++) {
            var step = runStep(directory, request.preparations().get(i), registration, "process-preparation-" + i, deadline, remaining);
            publish(directory.resolve("preparation-" + i + "-result"), result(step));
            preparations.add(step);
            remaining = Math.max(0, remaining - step.output().getBytes(StandardCharsets.UTF_8).length);
            if (!step.successful()) return new Result(registration.requestSha256(), registration.worker(), null, false,
                    step.timedOut(), step.cancelled(), step.outputTruncated(), step.stopConfirmed(), "", "COMMAND_PREPARATION_FAILED", List.of(), preparations);
        }
        var main = runStep(directory, new Preparation("EXECUTE", request.directory(), request.argv()), registration, "process", deadline, remaining);
        return new Result(main.requestSha256(), main.worker(), main.exitCode(), main.launched(), main.timedOut(), main.cancelled(),
                main.outputTruncated(), main.stopConfirmed(), main.output(), main.error(), main.children(), preparations);
    }
    private static Result runStep(Path directory, Preparation command, Registration registration, String processFile, long deadline, int outputLimit) throws IOException, InterruptedException {
        boolean cancelledBeforeLaunch = Files.exists(directory.resolve("stop"), LinkOption.NOFOLLOW_LINKS);
        boolean timeoutBeforeLaunch = System.nanoTime() >= deadline;
        if (cancelledBeforeLaunch || timeoutBeforeLaunch || outputLimit == 0)
            return new Result(registration.requestSha256(), registration.worker(), null, false, timeoutBeforeLaunch, cancelledBeforeLaunch, outputLimit == 0, true, "", "", List.of());
        Process process;
        try {
            Path workspace = Path.of(command.directory()); check(workspace);
            if (!workspace.toRealPath().equals(workspace)) throw new IOException("Command directory identity differs");
            var builder = new ProcessBuilder(command.argv()).directory(workspace.toFile()).redirectErrorStream(true);
            builder.environment().remove("LOOPPER_GIT_MASTER_KEY"); builder.environment().remove("LOOPPER_DATABASE_MASTER_KEY");
            // Do not propagate JVM injection options to the explicitly selected test process.
            builder.environment().remove("JAVA_TOOL_OPTIONS"); builder.environment().remove("JDK_JAVA_OPTIONS"); builder.environment().remove("_JAVA_OPTIONS");
            builder.environment().put("GIT_TERMINAL_PROMPT", "0"); builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) System.setProperty("jdk.lang.Process.allowAmbiguousCommands", "false");
            process = builder.start();
        } catch (IOException failure) {
            return new Result(registration.requestSha256(), registration.worker(), null, false, false, false, false, true, "", "COMMAND_START_FAILED", List.of());
        }
        var children = new ConcurrentHashMap<Long, Owned>();
        try { remember(process.toHandle(), children); }
        catch (RuntimeException unknown) { process.destroyForcibly(); throw unknown; }
        var output = new ByteArrayOutputStream(); var overflow = new AtomicBoolean(); var readError = new AtomicBoolean();
        Thread drain = Thread.ofVirtual().start(() -> drain(process, output, overflow, readError, outputLimit));
        Thread shutdown = new Thread(() -> stop(process, children), "command-shutdown"); Runtime.getRuntime().addShutdownHook(shutdown);
        boolean timeout = false, cancelled = false, stopped = false; Integer exit = null;
        try {
            process.getOutputStream().close();
            var child = children.get(process.pid());
            if (child != null) publish(directory.resolve(processFile), registration(new Registration(registration.requestSha256(), child.identity())));
            while (true) {
                observe(process, children);
                if (children.size() > 1024) throw new IOException("Command process limit exceeded");
                cancelled = Files.exists(directory.resolve("stop"), LinkOption.NOFOLLOW_LINKS);
                timeout = System.nanoTime() >= deadline;
                if (cancelled || timeout || overflow.get() || !process.isAlive()) break;
                Thread.sleep(25);
            }
            if (!process.isAlive()) exit = process.exitValue();
            stopped = stop(process, children);
            drain.join(1000);
            if (drain.isAlive()) readError.set(true);
            String captured = text(output, overflow, outputLimit);
            if (!stopped) return new Result(registration.requestSha256(), registration.worker(), exit, true, timeout, cancelled, true, false,
                    captured, "COMMAND_STOP_UNCONFIRMED", identities(children));
            return new Result(registration.requestSha256(), registration.worker(), exit, true, timeout, cancelled, overflow.get() || readError.get(), true,
                    captured, readError.get() ? "COMMAND_OUTPUT_INCOMPLETE" : "", identities(children));
        } finally {
            if (!stopped) stop(process, children);
            Runtime.getRuntime().removeShutdownHook(shutdown);
        }
    }
    private static void drain(Process process, ByteArrayOutputStream output, AtomicBoolean overflow, AtomicBoolean readError, int outputLimit) {
        try (var in = process.getInputStream()) {
            byte[] bytes = new byte[8192]; int length;
            while ((length = in.read(bytes)) != -1) synchronized (output) {
                int accepted = Math.min(length, outputLimit - output.size()); if (accepted > 0) output.write(bytes, 0, accepted);
                if (accepted < length) overflow.set(true);
            }
        } catch (IOException failure) { readError.set(true); }
    }
    private static String text(ByteArrayOutputStream output, AtomicBoolean overflow, int limit) {
        synchronized (output) {
            String safe = output.toString(StandardCharsets.UTF_8).replaceAll("(?:lpa|lpp|lpw)_[A-Za-z0-9_-]{1,800}\\.[A-Za-z0-9_-]{43}", "[辅助作用域凭证已隐藏]");
            byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= limit) return safe;
            // Replacement characters from malformed bytes may expand the saved UTF-8 text.
            overflow.set(true); int end = limit;
            while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
            return new String(bytes, 0, end, StandardCharsets.UTF_8);
        }
    }
    private record Owned(ProcessHandle handle, Identity identity) {
        boolean alive() { return identity.alive(); }
    }
    private static void remember(ProcessHandle handle, Map<Long, Owned> children) {
        var started = handle.info().startInstant();
        if (started.isPresent()) children.putIfAbsent(handle.pid(), new Owned(handle, new Identity(handle.pid(), started.get().toString())));
        else if (handle.isAlive()) throw new IllegalStateException("Live command process identity is unavailable");
    }
    private static void observe(Process process, Map<Long, Owned> children) { process.descendants().forEach(child -> remember(child, children)); }
    private static boolean stop(Process process, Map<Long, Owned> children) {
        observe(process, children); boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            do {
                observe(process, children);
                for (var child : children.values()) if (child.handle().pid() != process.pid() && child.alive() && child.identity().matches(child.handle())) child.handle().destroyForcibly();
                // Give the direct parent time to reap descendants before terminating it.
                boolean descendantsAlive = children.values().stream().anyMatch(child -> child.handle().pid() != process.pid() && child.alive());
                if (process.isAlive() && (!descendantsAlive || System.nanoTime() + Duration.ofMillis(500).toNanos() >= deadline)) process.destroyForcibly();
                if (!process.isAlive() && children.values().stream().noneMatch(Owned::alive)) return true;
                try { Thread.sleep(20); } catch (InterruptedException ignored) { interrupted = true; }
            } while (System.nanoTime() < deadline);
            return false;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static List<Identity> identities(Map<Long, Owned> children) { return children.values().stream().map(Owned::identity).toList(); }
}
