package io.opencode.loopper.runtime;

import static io.opencode.loopper.runtime.DurableCommandProtocol.*;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Process boundary only. SQLite owners create durable intents before invoking this adapter. */
@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DurableCommands {
    private final Path root;
    public DurableCommands(LoopperProperties properties) { root = canonical(properties.getDataDir()).resolve("workflow-commands"); }
    public record Job(String id, String requestSha256) { }
    public record Observation(Registration registration, Result result, boolean workerAlive, boolean stopUnknown) { }

    /** Repeatable preparation checks exact bytes; it cannot replace a different request for the same identity. */
    public Job prepare(Request request) {
        try {
            Path directory = path(request.id()); check(directory); Files.createDirectories(directory); check(directory);
            byte[] bytes = request(request); publish(directory.resolve("request"), bytes); return new Job(request.id(), hash(bytes));
        } catch (IOException | RuntimeException failure) { throw invalid("WORKFLOW_COMMAND_PREPARATION_FAILED", "检查命令的执行记录无法准备，请检查数据目录后重试。"); }
    }
    /** Safe to replay only for this supervisor: its durable registration and file lock permit one external launch. */
    public void ensureSupervisor(Job job) {
        ensureSupervisor(job, Map.of());
    }
    /** Trusted server-only launch context. Credentials are ephemeral, never part of durable request bytes. */
    public void ensureSupervisor(Job job, Map<String,String> environment) {
        try {
            Path directory = require(job);
            if (Files.exists(directory.resolve("registration"), LinkOption.NOFOLLOW_LINKS)) { observe(job); return; }
            Path archive = helper();
            String executable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
            var builder = new ProcessBuilder(executable, "-jar", archive.toString(), directory.toString(), job.id())
                    .directory(directory.toFile()).redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(environment);
            builder.environment().remove("JAVA_TOOL_OPTIONS"); builder.environment().remove("JDK_JAVA_OPTIONS"); builder.environment().remove("_JAVA_OPTIONS");
            ChildProcessEnvironment.start(builder);
        } catch (IOException | RuntimeException failure) { throw invalid("WORKFLOW_COMMAND_START_UNKNOWN", "检查进程启动结果尚未确认，保留原执行记录并重新读取。"); }
    }
    /** Call only after the owner has persisted and revalidated this exact worker identity. */
    public void grant(Job job, Registration expected) {
        try {
            var observation = observe(job);
            Path granted = require(job).resolve("grant");
            if (Files.exists(granted, LinkOption.NOFOLLOW_LINKS)) {
                if (!expected.equals(registration(read(granted))) || !Objects.equals(expected, observation.registration())) throw new IOException("Grant differs");
                return;
            }
            if (!Objects.equals(expected, observation.registration()) || !expected.requestSha256().equals(job.requestSha256())
                    || ProcessHandle.of(expected.worker().pid()).filter(ProcessHandle::isAlive).filter(expected.worker()::matches).isEmpty())
                throw new IOException("Supervisor identity is not current");
            publish(require(job).resolve("grant"), registration(expected));
        } catch (IOException | RuntimeException failure) { throw invalid("WORKFLOW_COMMAND_GRANT_UNKNOWN", "检查命令的准入回执尚未确认，请继续核对原进程。"); }
    }
    public void requestStop(Job job) {
        try { publish(require(job).resolve("stop"), job.requestSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
        catch (IOException | RuntimeException failure) { throw invalid("WORKFLOW_COMMAND_STOP_UNCONFIRMED", "检查进程停止请求尚未确认，执行保持阻断。"); }
    }
    public Observation observe(Job job) {
        try {
            var directory = require(job); Registration registered = null; Result receipt = null;
            if (Files.exists(directory.resolve("registration"), LinkOption.NOFOLLOW_LINKS)) {
                registered = registration(read(directory.resolve("registration")));
                if (!registered.requestSha256().equals(job.requestSha256())) throw new IOException("Supervisor contract differs");
            }
            if (Files.exists(directory.resolve("result"), LinkOption.NOFOLLOW_LINKS)) {
                receipt = result(read(directory.resolve("result")));
                if (registered == null || !receipt.requestSha256().equals(job.requestSha256()) || !receipt.worker().equals(registered.worker()))
                    throw new IOException("Supervisor receipt differs");
                if(!matches(request(read(directory.resolve("request"))),receipt))throw new IOException("Preparation receipt differs");
            }
            boolean alive = registered != null && registered.worker().alive();
            boolean unknown = registered != null && !alive && receipt == null || receipt != null && !receipt.stopConfirmed();
            if (receipt != null && receipt.stopConfirmed() && receipt.children().stream().anyMatch(Identity::alive)) unknown = true;
            if(receipt!=null&&receipt.preparations().stream().anyMatch(p->!p.stopConfirmed()||p.children().stream().anyMatch(Identity::alive)))unknown=true;
            return new Observation(registered, receipt, alive, unknown);
        } catch (IOException | RuntimeException failure) { throw invalid("WORKFLOW_COMMAND_EVIDENCE_INVALID", "检查进程的原始记录缺失或不一致，保留现场并阻止重复执行。"); }
    }
    private Path require(Job job) throws IOException {
        Path directory = path(job.id()); byte[] bytes = read(directory.resolve("request"));
        if (!hash(bytes).equals(job.requestSha256()) || !request(bytes).id().equals(job.id())) throw new IOException("Command request differs"); return directory;
    }
    private Path path(String id) { UUID.fromString(id); return root.resolve(id); }
    /** A tiny dependency-free JAR works identically under test classes and the packaged Spring Boot JAR. */
    private Path helper() throws IOException {
        return DurableHelperArchive.write(root.resolve("helpers"), DurableCommandWorker.class, DurableCommandProtocol.class);
    }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    private static Path canonical(Path configured) {
        var absolute = configured.toAbsolutePath().normalize(); var ancestor = absolute;
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
        try {
            if (ancestor == null) throw new IOException("Data directory unavailable");
            return ancestor.toRealPath().resolve(ancestor.relativize(absolute));
        } catch (IOException failure) { throw invalid("WORKFLOW_COMMAND_PREPARATION_FAILED", "检查命令的数据目录无法定位。"); }
    }
    private static File nullDevice() { return new File(isWindows() ? "NUL" : "/dev/null"); }
    private static TaskFailure invalid(String code, String message) { return new TaskFailure(code, message); }
}
