package io.opencode.loopper.service;

import io.opencode.loopper.domain.ErrorLayer;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ErrorEventRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.verification.VerifierEngine;
import io.opencode.loopper.verification.VerifierOutcome;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns existing-file changes outside an explicit GIT_DIFF allow-list into an auditable,
 * content-bound local decision. New files are handled by {@link VerifierEngine}; forbidden
 * paths and delete policy violations never enter this approval path.
 */
@Service
public class GitDiffScopeApprovalService {
    public static final String REQUIRED = "GIT_DIFF_SCOPE_APPROVAL_REQUIRED";
    public static final String DECIDED = "GIT_DIFF_SCOPE_DECIDED";
    public static final String STALE = "GIT_DIFF_SCOPE_APPROVAL_STALE";
    private final LoopperMapper mapper;
    private final ObjectMapper json;
    private final VerifierEngine verifiers;
    private final TaskStateStore taskStates;
    private final TaskEventService events;
    private final TransactionTemplate transactions;

    public GitDiffScopeApprovalService(LoopperMapper mapper, ObjectMapper json, VerifierEngine verifiers,
                                       LifecycleTransitionService lifecycle, TaskEventService events,
                                       PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.json = json;
        this.verifiers = verifiers;
        this.taskStates = new TaskStateStore(mapper, lifecycle);
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public PendingRequest pending(String taskId) {
        TaskRow task = task(taskId);
        return TaskState.WAITING_INPUT.name().equals(task.state()) ? pending(task.id(), task.version()) : null;
    }

    public VerifierEngine.DiffPreview preview(String taskId, String requestId, String path, Duration timeout) {
        TaskRow task = task(taskId);
        return preview(task, requireRequest(task, requestId), path, timeout);
    }

    public Resolution resolve(String taskId, long expectedTaskVersion, String requestId,
                              List<FileDecision> decisions, Duration timeout) {
        TaskRow task = task(taskId);
        if (!TaskState.WAITING_INPUT.name().equals(task.state()) || task.version() != expectedTaskVersion) {
            throw new ConflictException("GIT_DIFF_SCOPE_APPROVAL_VERSION_CONFLICT",
                    "The task or approval request changed; refresh before deciding");
        }
        PendingRequest request = requireRequest(task, requestId);
        Resolution resolution = validate(task, request, decisions, timeout);
        transactions.executeWithoutResult(status -> persistResolution(taskId, expectedTaskVersion, requestId, resolution));
        events.emit(taskId, resolution.stale() ? "git_diff.scope_approval_stale" : "git_diff.scope_decided",
                Map.of("requestId", requestId, "stale", resolution.stale()));
        return resolution;
    }

    private void persistResolution(String taskId, long expectedTaskVersion, String requestId,
                                   Resolution resolution) {
        TaskRow current = task(taskId);
        if (!TaskState.WAITING_INPUT.name().equals(current.state()) || current.version() != expectedTaskVersion) {
            throw new ConflictException("GIT_DIFF_SCOPE_APPROVAL_VERSION_CONFLICT",
                    "The task changed while the decision was being applied");
        }
        PendingRequest request = requireRequest(current, requestId);
        StageRow stage = mapper.findStage(request.stageId()).orElseThrow(() ->
                new ConflictException("GIT_DIFF_SCOPE_STAGE_MISSING", "The approval Stage is no longer available"));
        AttemptRow attempt = mapper.findAttempt(request.attemptId()).orElseThrow(() ->
                new ConflictException("GIT_DIFF_SCOPE_ATTEMPT_MISSING", "The approval Attempt is no longer available"));
        String code = resolution.stale() ? STALE : DECIDED;
        String message = resolution.stale()
                ? "The workspace changed after the diff was displayed; Loopper will generate a fresh approval request"
                : "The user decided every outside-allowed existing-file change";
        mapper.insertError(new ErrorEventRow(UUID.randomUUID().toString(), current.id(), stage.id(), attempt.id(),
                null, ErrorLayer.VERIFICATION.name(), code, message, resolution.stale(),
                write(resolution.evidence()), Instant.now().toString()));
        taskStates.updateTask(taskStates.taskState(current, TaskState.RUNNING), LifecycleEvent.RECOVER,
                Map.of("scopeApprovalRequestId", requestId, "stale", resolution.stale()));
    }

    private PendingRequest requireRequest(TaskRow task, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BadRequestException("GIT_DIFF_SCOPE_APPROVAL_ID_REQUIRED", "Approval request id is required");
        }
        PendingRequest request = pending(task.id(), task.version());
        if (request == null || !requestId.equals(request.requestId())) {
            throw new ConflictException("GIT_DIFF_SCOPE_APPROVAL_STALE",
                    "The approval request is no longer current; refresh the task");
        }
        return request;
    }

    private TaskRow task(String taskId) {
        return mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (RuntimeException failure) { throw new IllegalStateException("Unable to persist scope approval", failure); }
    }

    public Assessment assess(TaskRow task, StageRow stage, String attemptId, String baseline,
                             VerifierOutcome outcome, Duration timeout) {
        if (!"GIT_DIFF".equalsIgnoreCase(outcome.type())) return Assessment.completed(outcome);
        List<String> paths = strings(outcome.evidence().get("approvalRequiredPaths"));
        if (paths.isEmpty()) return Assessment.completed(outcome);
        List<String> hardViolations = strings(outcome.evidence().get("hardViolations"));
        if (!hardViolations.isEmpty()) return Assessment.completed(outcome);

        PendingRequest request = request(task, stage, attemptId, baseline, paths,
                stringMap(outcome.evidence().get("changeTypes")), timeout);
        DecisionRecord decision = decision(task.id(), request.requestId());
        if (decision == null) return Assessment.pending(request);

        List<String> approved = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (ApprovalFile file : request.files()) {
            FileDecision selected = decision.decisions().get(file.path());
            if (selected == null || !file.patchSha256().equals(selected.patchSha256())) {
                return Assessment.pending(request);
            }
            if (selected.action() == DecisionAction.ALLOW) approved.add(file.path());
            else rejected.add(file.path());
        }
        Map<String, Object> evidence = new LinkedHashMap<>(outcome.evidence());
        List<String> violations = new ArrayList<>(hardViolations);
        rejected.forEach(path -> violations.add("outside allowed existing file rejected by user: " + path));
        evidence.put("scopeApprovalRequestId", request.requestId());
        evidence.put("userApprovedOutsideExistingPaths", approved);
        evidence.put("userRejectedOutsideExistingPaths", rejected);
        evidence.put("violations", violations);
        boolean passed = violations.isEmpty();
        return Assessment.completed(new VerifierOutcome(outcome.type(),
                passed ? VerificationState.PASS : VerificationState.FAIL,
                passed ? "Git diff satisfies policy with user-approved existing-file changes"
                        : String.join("; ", violations), Map.copyOf(evidence)));
    }

    public PendingRequest pending(String taskId, long taskVersion) {
        for (ErrorEventRow row : mapper.listErrors(taskId)) {
            if (!REQUIRED.equals(row.code())) continue;
            PendingRequest request = readRequest(row.evidenceJson(), taskVersion);
            if (request != null && decision(taskId, request.requestId()) == null
                    && !stale(taskId, request.requestId())) return request;
        }
        return null;
    }

    public VerifierEngine.DiffPreview preview(TaskRow task, PendingRequest request, String path,
                                               Duration timeout) {
        ApprovalFile file = request.files().stream().filter(candidate -> candidate.path().equals(path))
                .findFirst().orElseThrow(() -> new BadRequestException("GIT_DIFF_SCOPE_PATH_INVALID",
                        "The requested path is not part of this approval request"));
        VerifierEngine.DiffPreview preview = verifiers.previewDiff(Path.of(requireWorktree(task)),
                request.baseline(), file.path(), false, timeout);
        if (preview.truncated()) {
            throw new ConflictException("GIT_DIFF_SCOPE_PREVIEW_TRUNCATED",
                    "The file diff is too large to review safely");
        }
        return preview;
    }

    public Resolution validate(TaskRow task, PendingRequest request, List<FileDecision> decisions,
                               Duration timeout) {
        if (decisions == null || decisions.size() != request.files().size()) {
            throw new BadRequestException("GIT_DIFF_SCOPE_DECISIONS_REQUIRED",
                    "Every changed existing file requires an allow or reject decision");
        }
        Map<String, FileDecision> byPath = new LinkedHashMap<>();
        for (FileDecision decision : decisions) {
            if (decision == null || decision.path() == null || decision.path().isBlank()
                    || decision.action() == null || decision.patchSha256() == null) {
                throw new BadRequestException("GIT_DIFF_SCOPE_DECISION_INVALID",
                        "Each scope decision requires path, action, and patch SHA-256");
            }
            if (byPath.put(decision.path(), decision) != null) {
                throw new BadRequestException("GIT_DIFF_SCOPE_DECISION_DUPLICATE",
                        "A changed file may be decided only once");
            }
        }
        for (ApprovalFile file : request.files()) {
            FileDecision selected = byPath.get(file.path());
            if (selected == null || !file.patchSha256().equals(selected.patchSha256())) {
                throw new ConflictException("GIT_DIFF_SCOPE_APPROVAL_TOKEN_MISMATCH",
                        "The submitted file decision does not match the displayed diff");
            }
            VerifierEngine.DiffPreview current = verifiers.previewDiff(Path.of(requireWorktree(task)),
                    request.baseline(), file.path(), false, timeout);
            if (current.truncated() || !file.patchSha256().equals(sha256(current.patch()))) {
                return Resolution.stale(request.requestId());
            }
        }
        return Resolution.decided(request.requestId(), List.copyOf(byPath.values()));
    }

    private PendingRequest request(TaskRow task, StageRow stage, String attemptId, String baseline,
                                   List<String> paths, Map<String, String> changeTypes, Duration timeout) {
        List<ApprovalFile> files = new ArrayList<>();
        for (String path : paths) {
            VerifierEngine.DiffPreview preview = verifiers.previewDiff(Path.of(requireWorktree(task)), baseline,
                    path, false, timeout);
            if (preview.truncated()) {
                throw new io.opencode.loopper.domain.TaskFailure("GIT_DIFF_SCOPE_PREVIEW_TRUNCATED",
                        "An outside-allowed existing-file diff exceeded the safe review limit: " + path);
            }
            files.add(new ApprovalFile(path, changeTypes.getOrDefault(path, "MODIFIED"), sha256(preview.patch())));
        }
        StringBuilder fingerprint = new StringBuilder(task.id()).append('\0').append(stage.id())
                .append('\0').append(baseline);
        files.forEach(file -> fingerprint.append('\0').append(file.path()).append('\0')
                .append(file.changeType()).append('\0').append(file.patchSha256()));
        return new PendingRequest("git-diff-scope:" + sha256(fingerprint.toString()), task.id(), stage.id(),
                attemptId, baseline, task.version(), List.copyOf(files));
    }

    private PendingRequest readRequest(String evidenceJson, long taskVersion) {
        try {
            JsonNode node = json.readTree(evidenceJson);
            String requestId = node.path("requestId").asText();
            String taskId = node.path("taskId").asText();
            String stageId = node.path("stageId").asText();
            String attemptId = node.path("attemptId").asText();
            String baseline = node.path("baseline").asText();
            if (requestId.isBlank() || taskId.isBlank() || stageId.isBlank()
                    || attemptId.isBlank() || baseline.isBlank() || !node.path("files").isArray()) return null;
            List<ApprovalFile> files = new ArrayList<>();
            for (JsonNode item : node.path("files")) {
                String path = item.path("path").asText();
                String type = item.path("changeType").asText("MODIFIED");
                String patchSha256 = item.path("patchSha256").asText();
                if (path.isBlank() || patchSha256.isBlank()) return null;
                files.add(new ApprovalFile(path, type, patchSha256));
            }
            return files.isEmpty() ? null : new PendingRequest(requestId, taskId, stageId, attemptId,
                    baseline, taskVersion, List.copyOf(files));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private DecisionRecord decision(String taskId, String requestId) {
        for (ErrorEventRow row : mapper.listErrors(taskId)) {
            if (!DECIDED.equals(row.code())) continue;
            try {
                JsonNode node = json.readTree(row.evidenceJson());
                if (!requestId.equals(node.path("requestId").asText()) || !node.path("decisions").isArray()) continue;
                Map<String, FileDecision> decisions = new LinkedHashMap<>();
                for (JsonNode item : node.path("decisions")) {
                    String path = item.path("path").asText();
                    DecisionAction action = DecisionAction.valueOf(item.path("action").asText());
                    String hash = item.path("patchSha256").asText();
                    decisions.put(path, new FileDecision(path, action, hash));
                }
                return new DecisionRecord(requestId, Map.copyOf(decisions));
            } catch (RuntimeException ignored) {
                // Malformed historical evidence cannot authorize a path exception.
            }
        }
        return null;
    }

    private boolean stale(String taskId, String requestId) {
        return mapper.listErrors(taskId).stream().filter(row -> STALE.equals(row.code())).anyMatch(row -> {
            try { return requestId.equals(json.readTree(row.evidenceJson()).path("requestId").asText()); }
            catch (RuntimeException ignored) { return false; }
        });
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, item) -> {
            if (key instanceof String path && item instanceof String type) result.put(path, type);
        });
        return Map.copyOf(result);
    }

    private String requireWorktree(TaskRow task) {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new ConflictException("WORKTREE_UNAVAILABLE", "Task worktree is unavailable");
        }
        return task.worktreePath();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public enum DecisionAction { ALLOW, REJECT }
    public record ApprovalFile(String path, String changeType, String patchSha256) { }
    public record FileDecision(String path, DecisionAction action, String patchSha256) { }
    public record PendingRequest(String requestId, String taskId, String stageId, String attemptId,
                                 String baseline, long taskVersion, List<ApprovalFile> files) {
        public Map<String, Object> evidence() {
            return Map.of("requestId", requestId, "taskId", taskId, "stageId", stageId,
                    "attemptId", attemptId, "baseline", baseline, "files", files);
        }
    }
    public record Assessment(VerifierOutcome outcome, PendingRequest pending) {
        static Assessment completed(VerifierOutcome outcome) { return new Assessment(outcome, null); }
        static Assessment pending(PendingRequest request) { return new Assessment(null, request); }
    }
    public record Resolution(String requestId, boolean stale, List<FileDecision> decisions) {
        static Resolution stale(String requestId) { return new Resolution(requestId, true, List.of()); }
        static Resolution decided(String requestId, List<FileDecision> decisions) {
            return new Resolution(requestId, false, decisions);
        }
        public Map<String, Object> evidence() {
            return stale ? Map.of("requestId", requestId)
                    : Map.of("requestId", requestId, "decisions", decisions);
        }
    }
    private record DecisionRecord(String requestId, Map<String, FileDecision> decisions) { }
}
