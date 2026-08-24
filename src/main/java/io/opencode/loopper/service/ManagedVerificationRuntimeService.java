package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.domain.VerifierRuntimeState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.VerifierRuntimeRow;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import io.opencode.loopper.verification.VerifierOutcome;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owns the one temporary loopback process allowed during a stage verification. */
@Service
public class ManagedVerificationRuntimeService {
    private final LoopperMapper mapper;
    private final SafeProcessRunner runner;
    private final LoopperProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, Lease> activeByTask = new ConcurrentHashMap<>();
    private final Set<String> startingTasks = ConcurrentHashMap.newKeySet();
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();

    public ManagedVerificationRuntimeService(LoopperMapper mapper, SafeProcessRunner runner,
                                             LoopperProperties properties, ObjectMapper json) {
        this.mapper = mapper;
        this.runner = runner;
        this.properties = properties;
        this.json = json;
    }

    public StartResult start(String taskId, String stageId, String attemptId, Path workspace,
                             LoopSpec.VerificationRuntime contract) {
        if (contract == null) return new StartResult(null, null);
        if (activeByTask.containsKey(taskId) || !startingTasks.add(taskId)) {
            throw new TaskFailure("VERIFIER_RUNTIME_OVERLAP", "A managed verifier runtime is already active for this task");
        }
        try {
            return startReserved(taskId, stageId, attemptId, workspace, contract);
        } finally {
            startingTasks.remove(taskId);
            cancelledTasks.remove(taskId);
        }
    }

    private StartResult startReserved(String taskId, String stageId, String attemptId, Path workspace,
                                      LoopSpec.VerificationRuntime contract) {
        String id = UUID.randomUUID().toString();
        int port = allocatePort();
        Path temp = properties.getDataDir().toAbsolutePath().normalize().resolve("verifier-runtimes").resolve(id);
        try { Files.createDirectories(temp); }
        catch (IOException failure) { throw new TaskFailure("VERIFIER_RUNTIME_TEMP_FAILED", "Unable to create verifier temp directory"); }
        List<String> argv = substitute(contract.startCommand(), port, temp);
        String now = Instant.now().toString();
        String beforeStatus = gitStatus(workspace);
        VerifierRuntimeRow starting = new VerifierRuntimeRow(id, taskId, stageId, attemptId,
                VerifierRuntimeState.STARTING.name(), null, null, port, sha256(write(argv)), write(argv),
                temp.toString(), write(Map.of("phase", "starting")), now, now, null, 0);
        mapper.insertVerifierRuntime(starting);

        SafeProcessRunner.ManagedProcess process;
        if (cancelledTasks.remove(taskId)) {
            VerifierRuntimeRow stopped = state(starting, VerifierRuntimeState.STOPPED, null,
                    Map.of("code", "VERIFIER_RUNTIME_INTERRUPTED", "phase", "before-launch"), true);
            update(stopped);
            cleanupTemp(temp);
            return new StartResult(null, outcome(VerificationState.FAIL, "VERIFIER_RUNTIME_INTERRUPTED",
                    "Managed verifier startup was cancelled before launch", stopped, null));
        }
        try {
            process = runner.startManaged(workspace, argv, Map.of(
                    "LOOPPER_VERIFY_PORT", Integer.toString(port),
                    "LOOPPER_VERIFY_TEMP_DIR", temp.toString()));
        } catch (TaskFailure failure) {
            VerifierRuntimeRow failed = state(starting, VerifierRuntimeState.FAILED, null,
                    Map.of("code", failure.code(), "message", safe(failure.getMessage())), true);
            update(failed);
            cleanupTemp(temp);
            return new StartResult(null, outcome(VerificationState.FAIL, failure.code(), failure.getMessage(), failed, null));
        }
        VerifierRuntimeRow running = new VerifierRuntimeRow(starting.id(), starting.taskId(), starting.stageId(),
                starting.attemptId(), VerifierRuntimeState.RUNNING.name(), process.pid(),
                process.startInstant() == null ? null : process.startInstant().toString(), port,
                sha256(write(process.resolvedArgv())), write(process.resolvedArgv()), temp.toString(),
                write(Map.of("phase", "readiness")), starting.createdAt(), Instant.now().toString(), null,
                starting.version());
        update(running);
        running = mapper.findVerifierRuntime(id).orElse(running);
        Lease lease = new Lease(running, process, contract, workspace, temp, port, beforeStatus,
                new ArrayList<>());
        activeByTask.put(taskId, lease);
        VerifierOutcome readinessFailure = awaitReadiness(lease);
        if (readinessFailure != null) {
            StopResult stopped = stop(lease, "readiness-failed");
            Map<String, Object> evidence = new LinkedHashMap<>(readinessFailure.evidence());
            evidence.putAll(stopped.outcome().evidence());
            VerificationState state = stopped.outcome().state() == VerificationState.ERROR
                    ? VerificationState.ERROR : VerificationState.FAIL;
            String summary = state == VerificationState.ERROR ? stopped.outcome().summary() : readinessFailure.summary();
            return new StartResult(null, new VerifierOutcome("MANAGED_RUNTIME", state,
                    summary, Map.copyOf(evidence)));
        }
        return new StartResult(lease, null);
    }

    public LoopSpec.VerifierSpec bind(LoopSpec.VerifierSpec source, Lease lease) {
        if (lease == null) return source;
        String url = source.url() == null ? null : source.url().replace("{{LOOPPER_PORT}}", Integer.toString(lease.port()));
        List<String> command = substitute(source.command(), lease.port(), lease.tempDir());
        String path = source.path() == null ? null : source.path().replace("{{LOOPPER_TEMP}}", lease.tempDir().toString());
        return new LoopSpec.VerifierSpec(source.type(), command, path, source.requireChanges(), source.allowedPaths(),
                source.forbiddenPaths(), source.forbidDeletes(), source.outputContains(), url, source.httpMethod(),
                source.expectedStatus(), source.jsonPath(), source.expectedValue(), source.matchMode(),
                source.expectedContent(), source.expectedSha256(), source.sql(), source.expectedRowCount(),
                source.assertions(), source.criterionIds(), source.processPurpose(), source.testTargets());
    }

    public StopResult stop(Lease lease, String reason) {
        if (lease == null) return new StopResult(new VerifierOutcome("MANAGED_RUNTIME", VerificationState.PASS,
                "No managed runtime configured", Map.of()));
        synchronized (lease) {
            return stopLocked(lease, reason);
        }
    }

    private StopResult stopLocked(Lease lease, String reason) {
        activeByTask.remove(lease.row().taskId(), lease);
        VerifierRuntimeRow current = mapper.findVerifierRuntime(lease.row().id()).orElse(lease.row());
        if (VerifierRuntimeState.STOPPED.name().equals(current.state())) {
            Map<String, Object> priorEvidence = readEvidence(current.evidenceJson());
            if (Boolean.TRUE.equals(priorEvidence.get("workspaceMutated"))) {
                return new StopResult(new VerifierOutcome("MANAGED_RUNTIME", VerificationState.FAIL,
                        "Verification runtime changed Git-visible workspace content",
                        withCode(priorEvidence, "VERIFIER_WORKSPACE_MUTATED")));
            }
            return new StopResult(new VerifierOutcome("MANAGED_RUNTIME", VerificationState.PASS,
                    "Managed verifier runtime was already stopped", Map.of("runtimeId", current.id(), "idempotent", true)));
        }
        if (VerifierRuntimeState.DISCONNECTED.name().equals(current.state())) {
            return new StopResult(new VerifierOutcome("MANAGED_RUNTIME", VerificationState.ERROR,
                    "Managed verifier process termination could not be confirmed",
                    Map.of("runtimeId", current.id(), "code", "VERIFIER_RUNTIME_TERMINATION_UNCONFIRMED",
                            "idempotent", true)));
        }
        update(state(current, VerifierRuntimeState.STOPPING, lease.process(), Map.of("reason", reason), false));
        boolean stopped = lease.process().stop(Duration.ofSeconds(lease.contract().shutdownTimeoutSeconds()));
        String afterStatus = gitStatus(lease.workspace());
        boolean workspaceMutated = lease.beforeStatus() != null && afterStatus != null
                && !lease.beforeStatus().equals(afterStatus);
        Map<String, Object> evidence = evidence(lease, reason, stopped, workspaceMutated, afterStatus);
        VerifierRuntimeState state = stopped ? VerifierRuntimeState.STOPPED : VerifierRuntimeState.DISCONNECTED;
        VerifierRuntimeRow latest = mapper.findVerifierRuntime(lease.row().id()).orElse(current);
        update(state(latest, state, lease.process(), evidence, true));
        if (stopped) cleanupTemp(lease.tempDir());
        if (!stopped) return new StopResult(new VerifierOutcome("MANAGED_RUNTIME", VerificationState.ERROR,
                "Managed verifier process termination could not be confirmed",
                withCode(evidence, "VERIFIER_RUNTIME_TERMINATION_UNCONFIRMED")));
        if (workspaceMutated) return new StopResult(new VerifierOutcome("MANAGED_RUNTIME", VerificationState.FAIL,
                "Verification runtime changed Git-visible workspace content",
                withCode(evidence, "VERIFIER_WORKSPACE_MUTATED")));
        return new StopResult(new VerifierOutcome("MANAGED_RUNTIME", VerificationState.PASS,
                "Managed verifier runtime became ready and stopped cleanly", evidence));
    }

    public VerifierOutcome stopTask(String taskId, String reason) {
        Lease lease = activeByTask.get(taskId);
        if (lease != null) return stop(lease, reason).outcome();
        if (!startingTasks.contains(taskId)) return null;
        cancelledTasks.add(taskId);
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline) && startingTasks.contains(taskId)) {
            lease = activeByTask.get(taskId);
            if (lease != null) return stop(lease, reason).outcome();
            try { Thread.sleep(25); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        lease = activeByTask.get(taskId);
        if (lease != null) return stop(lease, reason).outcome();
        if (!startingTasks.contains(taskId)) return null;
        return new VerifierOutcome("MANAGED_RUNTIME", VerificationState.ERROR,
                "Managed verifier startup could not be cancelled safely",
                Map.of("code", "VERIFIER_RUNTIME_TERMINATION_UNCONFIRMED", "phase", "starting"));
    }

    /**
     * Reconciles the persisted process identity after a stop request. A Task may
     * become terminal only when every verifier process is either already gone or
     * was stopped through the exact persisted PID/start-time identity.
     */
    public boolean confirmTaskStopped(String taskId) {
        boolean confirmed = true;
        for (VerifierRuntimeRow row : mapper.listVerifierRuntimes(taskId)) {
            VerifierRuntimeState state;
            try { state = VerifierRuntimeState.valueOf(row.state()); }
            catch (RuntimeException unknownState) { confirmed = false; continue; }
            if (state == VerifierRuntimeState.STOPPED || state == VerifierRuntimeState.FAILED) continue;
            if (activeByTask.containsKey(taskId) || startingTasks.contains(taskId)) {
                confirmed = false;
                continue;
            }
            ProcessHandle handle = row.pid() == null ? null : ProcessHandle.of(row.pid()).orElse(null);
            if (handle == null || !handle.isAlive()) {
                update(state(row, VerifierRuntimeState.STOPPED, null,
                        Map.of("recovery", "process-already-gone", "cancellation", true), true));
                continue;
            }
            boolean identityMatches = row.processStartInstant() != null
                    && handle.info().startInstant()
                    .map(instant -> instant.toString().equals(row.processStartInstant())).orElse(false);
            if (!identityMatches) {
                if (state != VerifierRuntimeState.DISCONNECTED) {
                    update(state(row, VerifierRuntimeState.DISCONNECTED, null,
                            Map.of("recovery", "pid-identity-mismatch", "cancellation", true), true));
                }
                confirmed = false;
                continue;
            }
            List<ProcessHandle> descendants = handle.descendants().toList();
            for (ProcessHandle child : descendants.reversed()) if (child.isAlive()) child.destroyForcibly();
            handle.destroyForcibly();
            boolean stopped = waitStopped(handle, descendants, Duration.ofSeconds(5));
            update(state(row, stopped ? VerifierRuntimeState.STOPPED : VerifierRuntimeState.DISCONNECTED,
                    null, Map.of("recovery", stopped ? "terminated-matching-process" : "termination-unconfirmed",
                            "cancellation", true), true));
            confirmed &= stopped;
        }
        return confirmed;
    }

    /** Reconciles only matching process identities; PID reuse is never killed. */
    public RecoveryResult recoverActive() {
        List<String> blocked = new ArrayList<>();
        List<String> cleaned = new ArrayList<>();
        for (VerifierRuntimeRow row : mapper.activeVerifierRuntimes()) {
            ProcessHandle handle = row.pid() == null ? null : ProcessHandle.of(row.pid()).orElse(null);
            boolean identityMatches = handle != null && row.processStartInstant() != null
                    && handle.info().startInstant().map(instant -> instant.toString().equals(row.processStartInstant())).orElse(false);
            if (handle == null || !handle.isAlive()) {
                update(state(row, VerifierRuntimeState.STOPPED, null,
                        Map.of("recovery", "process-already-gone"), true));
                cleaned.add(row.taskId());
            } else if (!identityMatches) {
                update(state(row, VerifierRuntimeState.DISCONNECTED, null,
                        Map.of("recovery", "pid-identity-mismatch"), true));
                blocked.add(row.taskId());
            } else {
                List<ProcessHandle> descendants = handle.descendants().toList();
                for (ProcessHandle child : descendants.reversed()) if (child.isAlive()) child.destroyForcibly();
                handle.destroyForcibly();
                boolean stopped = waitStopped(handle, descendants, Duration.ofSeconds(5));
                update(state(row, stopped ? VerifierRuntimeState.STOPPED : VerifierRuntimeState.DISCONNECTED,
                        null, Map.of("recovery", stopped ? "terminated-matching-process" : "termination-unconfirmed"), true));
                (stopped ? cleaned : blocked).add(row.taskId());
            }
        }
        return new RecoveryResult(List.copyOf(cleaned), List.copyOf(blocked));
    }

    private VerifierOutcome awaitReadiness(Lease lease) {
        Instant deadline = Instant.now().plusSeconds(lease.contract().startupTimeoutSeconds());
        LoopSpec.RuntimeReadiness readiness = lease.contract().readiness();
        URI uri = URI.create("http://127.0.0.1:" + lease.port() + readiness.path());
        while (Instant.now().isBefore(deadline)) {
            if (!lease.process().alive()) {
                return new VerifierOutcome("MANAGED_RUNTIME", VerificationState.FAIL,
                        "Managed verifier process exited before readiness",
                        Map.of("code", "VERIFIER_RUNTIME_EARLY_EXIT", "exitCode", lease.process().exitCode(),
                                "output", lease.process().output(), "outputTruncated", lease.process().outputTruncated()));
            }
            long remainingMillis = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
            int timeoutMillis = (int) Math.min(1_000, remainingMillis);
            try {
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMillis)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                boolean ready = response.statusCode() == readiness.expectedStatus() && matches(readiness, response.body());
                lease.readinessAttempts().add(Map.of("at", Instant.now().toString(), "status", response.statusCode(), "ready", ready));
                if (ready) return null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new VerifierOutcome("MANAGED_RUNTIME", VerificationState.ERROR,
                        "Managed runtime readiness was interrupted", Map.of("code", "VERIFIER_RUNTIME_INTERRUPTED"));
            } catch (Exception unavailable) {
                lease.readinessAttempts().add(Map.of("at", Instant.now().toString(), "error", safe(unavailable.getMessage())));
            }
            try { Thread.sleep(200); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new VerifierOutcome("MANAGED_RUNTIME", VerificationState.ERROR,
                        "Managed runtime readiness was interrupted", Map.of("code", "VERIFIER_RUNTIME_INTERRUPTED"));
            }
        }
        return new VerifierOutcome("MANAGED_RUNTIME", VerificationState.FAIL,
                "Managed verifier runtime did not become ready before its startup timeout",
                Map.of("code", "VERIFIER_RUNTIME_READINESS_TIMEOUT", "uri", uri.toString(),
                        "attempts", List.copyOf(lease.readinessAttempts()), "output", lease.process().output(),
                        "outputTruncated", lease.process().outputTruncated()));
    }

    private boolean matches(LoopSpec.RuntimeReadiness readiness, String body) {
        if (readiness.jsonPath() == null) return true;
        try {
            JsonNode node = json.readTree(body);
            String path = readiness.jsonPath();
            if (!path.startsWith("$")) return false;
            for (String part : path.substring(1).split("\\.")) {
                if (!part.isBlank()) node = node.path(part);
            }
            if ("EXISTS".equals(readiness.matchMode())) return !node.isMissingNode() && !node.isNull();
            String actual = node.isValueNode() ? node.asText() : node.toString();
            if ("CONTAINS".equals(readiness.matchMode())) return actual.contains(safe(readiness.expectedValue()));
            return readiness.expectedValue() == null || actual.equals(readiness.expectedValue());
        } catch (JacksonException invalidJson) { return false; }
    }

    private Map<String, Object> evidence(Lease lease, String reason, boolean stopped,
                                         boolean workspaceMutated, String afterStatus) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runtimeId", lease.row().id());
        result.put("pid", lease.process().pid());
        result.put("processStartInstant", lease.process().startInstant() == null ? "" : lease.process().startInstant().toString());
        result.put("port", lease.port());
        result.put("resolvedArgv", lease.process().resolvedArgv());
        result.put("readinessAttempts", List.copyOf(lease.readinessAttempts()));
        result.put("output", lease.process().output());
        result.put("outputTruncated", lease.process().outputTruncated());
        result.put("exitCode", lease.process().exitCode() == null ? -1 : lease.process().exitCode());
        result.put("terminationConfirmed", stopped);
        result.put("reason", reason);
        result.put("workspaceMutated", workspaceMutated);
        if (afterStatus != null) result.put("workspaceStatusSha256", sha256(afterStatus));
        return Map.copyOf(result);
    }

    private VerifierRuntimeRow state(VerifierRuntimeRow row, VerifierRuntimeState state,
                                     SafeProcessRunner.ManagedProcess process, Map<String, Object> evidence,
                                     boolean terminal) {
        return new VerifierRuntimeRow(row.id(), row.taskId(), row.stageId(), row.attemptId(), state.name(),
                process == null ? row.pid() : process.pid(),
                process == null || process.startInstant() == null ? row.processStartInstant() : process.startInstant().toString(),
                row.port(), row.argvSha256(), row.resolvedArgvJson(), row.tempDir(), write(evidence), row.createdAt(),
                Instant.now().toString(), terminal ? Instant.now().toString() : null, row.version());
    }

    private void update(VerifierRuntimeRow row) {
        if (mapper.updateVerifierRuntime(row) != 1) {
            throw new ConflictException("VERIFIER_RUNTIME_VERSION_CONFLICT", "Verifier runtime changed concurrently");
        }
    }

    private String gitStatus(Path workspace) {
        try {
            ProcessResult result = runner.run(workspace,
                    List.of("git", "status", "--porcelain=v1", "-z", "--untracked-files=all"), Duration.ofSeconds(10));
            return !result.timedOut() && result.exitCode() == 0 && !result.outputTruncated() ? result.output() : null;
        } catch (RuntimeException notGit) { return null; }
    }

    private int allocatePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new TaskFailure("VERIFIER_RUNTIME_PORT_UNAVAILABLE", "Unable to allocate a loopback verification port");
        }
    }

    private List<String> substitute(List<String> values, int port, Path temp) {
        if (values == null) return List.of();
        return values.stream().map(value -> value
                .replace("{{LOOPPER_PORT}}", Integer.toString(port))
                .replace("{{LOOPPER_TEMP}}", temp.toString())).toList();
    }

    private boolean waitStopped(ProcessHandle root, List<ProcessHandle> descendants, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!root.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive)) return true;
            try { Thread.sleep(50); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        return !root.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
    }

    private void cleanupTemp(Path temp) {
        try {
            if (!Files.exists(temp)) return;
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // The evidence records the temp directory; startup recovery can clean a retained directory later.
        }
    }

    private VerifierOutcome outcome(VerificationState state, String code, String summary,
                                    VerifierRuntimeRow row, Map<String, Object> extra) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("code", code);
        evidence.put("runtimeId", row.id());
        evidence.put("port", row.port());
        if (extra != null) evidence.putAll(extra);
        return new VerifierOutcome("MANAGED_RUNTIME", state, safe(summary), Map.copyOf(evidence));
    }

    private Map<String, Object> withCode(Map<String, Object> evidence, String code) {
        Map<String, Object> copy = new LinkedHashMap<>(evidence);
        copy.put("code", code);
        return Map.copyOf(copy);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException("Unable to serialize verifier runtime evidence", failure); }
    }
    private Map<String, Object> readEvidence(String value) {
        try { return value == null || value.isBlank() ? Map.of() : json.readValue(value, new TypeReference<>() { }); }
        catch (JacksonException invalid) { return Map.of(); }
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private String safe(String value) { return value == null ? "" : value; }

    public record Lease(VerifierRuntimeRow row, SafeProcessRunner.ManagedProcess process,
                        LoopSpec.VerificationRuntime contract, Path workspace, Path tempDir, int port,
                        String beforeStatus, List<Map<String, Object>> readinessAttempts) { }
    public record StartResult(Lease lease, VerifierOutcome failure) { }
    public record StopResult(VerifierOutcome outcome) { }
    public record RecoveryResult(List<String> cleanedTaskIds, List<String> blockedTaskIds) { }
}
