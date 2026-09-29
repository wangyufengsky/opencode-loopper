package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Bounded Git data transport. stderr can never contaminate NUL-separated evidence. */
@Component
public final class GitEvidenceProcess {
    private static final int OUTPUT_LIMIT = 4_000_000;
    private static final Map<String, String> ENVIRONMENT = Map.of(
            "GIT_TERMINAL_PROMPT", "0", "GIT_OPTIONAL_LOCKS", "0", "LC_ALL", "C", "GIT_NO_REPLACE_OBJECTS", "1", "GIT_ATTR_NOSYSTEM", "1");
    private final SafeProcessRunner runner;
    private GitCredentialProvider credentials;

    public GitEvidenceProcess(SafeProcessRunner runner) { this.runner = runner; }

    @org.springframework.beans.factory.annotation.Autowired
    public void credentialProvider(GitCredentialProvider provider) { this.credentials = provider; }

    /** Caller supplies the registered project even when the command runs in a private bare snapshot. */
    public Result remote(Path directory, Path project, Duration timeout, List<String> arguments, String remote) {
        String url = remote;
        if (!remote.contains(":") && !remote.startsWith("/") && !remote.startsWith("\\\\")) {
            var lookup = new ArrayList<>(List.of("remote", "get-url"));
            if (!arguments.isEmpty() && arguments.getFirst().equals("push")) lookup.add("--push");
            lookup.add("--"); lookup.add(remote);
            url = read(directory, lookup.toArray(String[]::new)).strip();
        }
        Map<String, String> environment = credentials != null && (url.startsWith("http://") || url.startsWith("https://"))
                ? credentials.environment(project, url) : Map.of();
        return run(directory, timeout, arguments, environment);
    }

    public String read(Path directory, String... arguments) {
        Result result = run(directory, Duration.ofSeconds(60), List.of(arguments));
        result.requireSuccess(List.of(arguments));
        return result.output();
    }

    public void requireSupported(Path directory) {
        String version = read(directory, "--version").strip();
        var match = java.util.regex.Pattern.compile("^git version (\\d+)\\.(\\d+)(?:\\.(\\d+))?.*$").matcher(version);
        if (!match.matches()) throw new TaskFailure("TEMPLATE_GIT_VERSION_UNKNOWN", "无法识别 Git 版本，请使用 Git 2.30.2 或更高版本");
        int major = Integer.parseInt(match.group(1)), minor = Integer.parseInt(match.group(2));
        int patch = match.group(3) == null ? 0 : Integer.parseInt(match.group(3));
        if (major < 2 || major == 2 && (minor < 30 || minor == 30 && patch < 2)) {
            throw new TaskFailure("TEMPLATE_GIT_VERSION_UNSUPPORTED", "模板任务需要 Git 2.30.2 或更高版本，当前为 " + major + "." + minor + "." + patch);
        }
    }

    public Result run(Path directory, Duration timeout, List<String> arguments) {
        return run(directory, timeout, arguments, Map.of());
    }

    /** Overrides are server-owned scratch paths or scoped credentials, never arbitrary model/user environment. */
    public Result run(Path directory, Duration timeout, List<String> arguments, Map<String, String> environment) {
        BinaryResult result = bytes(directory, timeout, arguments, environment);
        return new Result(result.exitCode(), new String(result.output(), StandardCharsets.UTF_8), result.diagnostic());
    }

    /** Binary objects must not round-trip through a text decoder. Shares the same process and output bounds. */
    public BinaryResult bytes(Path directory, Duration timeout, List<String> arguments) {
        return bytes(directory, timeout, arguments, Map.of());
    }

    private BinaryResult bytes(Path directory, Duration timeout, List<String> arguments, Map<String, String> environment) {
        return exchange(directory, timeout, arguments, environment, null);
    }

    /** Raw object/index input, bounded like output. Writing concurrently keeps the process deadline effective. */
    public BinaryResult input(Path directory, Duration timeout, List<String> arguments,
                              Map<String, String> environment, byte[] input) {
        if (input == null || input.length > OUTPUT_LIMIT) throw new IllegalArgumentException("Git input exceeds transport bound");
        return exchange(directory, timeout, arguments, environment, input.clone());
    }

    private BinaryResult exchange(Path directory, Duration timeout, List<String> arguments,
                                  Map<String, String> environment, byte[] input) {
        ProcessScope scope = null;
        try {
            scope = start(directory,arguments,environment);
            return collect(scope, timeout, arguments, input);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TaskFailure("TEMPLATE_GIT_INTERRUPTED", "Git 证据读取已中断");
        } catch (IOException failure) {
            throw new TaskFailure("TEMPLATE_GIT_UNAVAILABLE", "无法运行 Git，请检查本机 Git 安装和仓库权限");
        } finally {
            if (scope != null && !scope.stopConfirmed()) throw new TaskFailure("TEMPLATE_GIT_STOP_UNCONFIRMED", "无法确认 Git 进程已停止，执行保持阻断");
        }
    }

    private ProcessScope start(Path directory,List<String> arguments,Map<String,String> environment)throws IOException {
        List<String> argv = new ArrayList<>(List.of("git", "-c", "core.safecrlf=false", "-c", "color.ui=false",
                "-c", "core.quotePath=false", "-c", "core.hooksPath=" + nullDevice(),
                "-c", "protocol.ext.allow=never", "-c", "core.attributesFile=" + nullDevice(),
                "-c", "mailmap.file=" + nullDevice(), "-c", "mailmap.blob="));
        argv.addAll(arguments);
        var resolution = runner.resolve(directory, argv);
        ProcessBuilder builder = new ProcessBuilder(resolution.argv()).directory(directory.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_") && !key.equals("GIT_SSH_COMMAND"));
        if (environment.containsKey("GIT_CONFIG_PARAMETERS")) builder.environment().remove("SSH_ASKPASS");
        builder.environment().putAll(ENVIRONMENT);
        builder.environment().putAll(environment);
        return new ProcessScope(ChildProcessEnvironment.start(builder));
    }

    Result createReference(Path directory,Duration timeout,String ref,String commit,Map<String,String> environment,Runnable guard) {
        ProcessScope scope=null;
        try {
            scope=start(directory,List.of("update-ref","--stdin"),environment);
            return GitReferenceTransaction.create(scope,timeout,ref,commit,guard);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new TaskFailure("TEMPLATE_GIT_INTERRUPTED","Git 引用保存已中断，请重试原操作");}
        catch(IOException failure){throw new TaskFailure("TEMPLATE_GIT_UNAVAILABLE","无法运行 Git，请检查本机 Git 安装和仓库权限");}
        finally{if(scope!=null&&!scope.stopConfirmed())throw new TaskFailure("TEMPLATE_GIT_STOP_UNCONFIRMED","无法确认 Git 引用操作已停止，请保留原操作并重新检查");}
    }

    private BinaryResult collect(ProcessScope scope, Duration timeout, List<String> arguments, byte[] input) throws InterruptedException, IOException {
        Process process = scope.process;
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var exceeded = new AtomicBoolean();
        Thread stdout = drain(process.getInputStream(), output, exceeded, scope);
        Thread stderr = drain(process.getErrorStream(), errors, exceeded, scope);
        var inputFailed = new AtomicBoolean();
        Thread stdin = Thread.ofVirtual().name("template-git-input").start(() -> {
            try (var stream = process.getOutputStream()) { if (input != null) stream.write(input); }
            catch (IOException failure) { inputFailed.set(true); }
        });
        boolean done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!done) scope.stop();
        stdout.join(2000);
        stderr.join(2000);
        stdin.join(2000);
        if (!done) throw new TaskFailure("TEMPLATE_GIT_TIMEOUT", "Git 操作超时（操作：git " + GitEvidenceDiagnostic.operation(arguments)
                + "；时限：" + timeout.toSeconds() + " 秒），请检查该操作的仓库访问或本地处理耗时后重试");
        if (exceeded.get() || stdout.isAlive() || stderr.isAlive() || stdin.isAlive()) {
            throw new TaskFailure("TEMPLATE_GIT_EVIDENCE_LIMIT", "单次 Git 证据超过读取上限，未生成完整报告");
        }
        if (process.exitValue() == 0 && inputFailed.get())
            throw new TaskFailure("TEMPLATE_GIT_INPUT_INCOMPLETE", "Git 未完整接收固定输入，未生成完整结果");
        return new BinaryResult(process.exitValue(), output.toByteArray(),
                GitEvidenceDiagnostic.classify(errors.toString(StandardCharsets.UTF_8)));
    }

    private static Thread drain(InputStream input, ByteArrayOutputStream output, AtomicBoolean exceeded, ProcessScope scope) {
        return Thread.ofVirtual().name("template-git-drain").start(() -> {
            try (input) {
                byte[] bytes = new byte[8192];
                int read;
                while ((read = input.read(bytes)) != -1) {
                    if (output.size() + read > OUTPUT_LIMIT) {
                        exceeded.set(true);
                        scope.stop();
                        break;
                    }
                    output.write(bytes, 0, read);
                }
            } catch (IOException failure) { exceeded.set(true); }
        });
    }

    static final class ProcessScope {
        final Process process;
        // Keep handles observed by timeout/output-cap kills even after the parent exits and children reparent.
        final java.util.Set<ProcessHandle> children = java.util.concurrent.ConcurrentHashMap.newKeySet();
        ProcessScope(Process process) { this.process = process; }
        void stop() {
            process.descendants().forEach(children::add);
            children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
        }
        boolean stopConfirmed() {
            stop();
            boolean interrupted = Thread.interrupted();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) && System.nanoTime() < deadline) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) { interrupted = true; }
                }
                return !process.isAlive() && children.stream().noneMatch(ProcessHandle::isAlive);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static String nullDevice() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "NUL" : "/dev/null";
    }

    public record Result(int exitCode, String output, GitEvidenceDiagnostic diagnostic) {
        public void requireSuccess(List<String> arguments) {
            if (exitCode != 0) throw diagnostic.failure(arguments, exitCode);
        }
    }
    public record BinaryResult(int exitCode, byte[] output, GitEvidenceDiagnostic diagnostic) {
        public void requireSuccess(List<String> arguments) {
            if (exitCode != 0) throw diagnostic.failure(arguments, exitCode);
        }
    }
}
