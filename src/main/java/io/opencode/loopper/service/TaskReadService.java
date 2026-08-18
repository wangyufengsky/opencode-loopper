package io.opencode.loopper.service;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.ErrorEventRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ReadModelMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskAuditAttemptRow;
import io.opencode.loopper.persistence.TaskAuditEntryRow;
import io.opencode.loopper.persistence.TaskArtifactSummaryRow;
import io.opencode.loopper.persistence.TaskOverviewRow;
import io.opencode.loopper.persistence.TaskStageReadRow;
import io.opencode.loopper.persistence.TaskSummaryRow;
import io.opencode.loopper.persistence.VerificationSummaryRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Read-only, bounded projections kept separate from lifecycle orchestration. */
@Service
public class TaskReadService {
    private static final Set<String> ARCHIVE_MODES = Set.of("ACTIVE", "ARCHIVED", "ALL");
    private final ReadModelMapper reads;
    private final LoopperMapper mapper;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public TaskReadService(ReadModelMapper reads, LoopperMapper mapper, ObjectMapper json, MeterRegistry meters) {
        this.reads = reads;
        this.mapper = mapper;
        this.json = json;
        this.meters = meters;
    }

    public CursorPage<TaskSummary> summaries(String projectId, List<String> states, String archive,
                                             String query, String order, String cursor, Integer requestedLimit) {
        return measured("task-summaries", () -> {
            int limit = PageCursor.limit(requestedLimit);
            String archiveMode = normalizedArchive(archive);
            List<String> normalizedStates = normalizedStates(states);
            boolean oldest = "oldest".equalsIgnoreCase(order);
            PageCursor decoded = PageCursor.decode(cursor);
            List<TaskSummaryRow> rows = reads.taskSummaries(blankToNull(projectId), normalizedStates, archiveMode,
                    likePattern(query), oldest, decoded == null ? null : decoded.value(),
                    decoded == null ? null : decoded.id(), limit + 1);
            boolean hasMore = rows.size() > limit;
            List<TaskSummaryRow> pageRows = hasMore ? rows.subList(0, limit) : rows;
            List<TaskSummary> items = pageRows.stream().map(this::summary).toList();
            Map<String, Long> facets = new LinkedHashMap<>();
            reads.taskFacets().forEach(row -> facets.put(row.state(), row.count()));
            facets.put("TOTAL", facets.values().stream().mapToLong(Long::longValue).sum());
            String next = hasMore ? new PageCursor(pageRows.getLast().updatedAt(), pageRows.getLast().id()).encode() : null;
            recordRows("task-summaries", items.size());
            return new CursorPage<>(items, next, facets);
        });
    }

    public TaskOverview overview(String taskId) {
        return measured("task-overview", () -> {
            TaskOverviewRow task = reads.taskOverview(taskId)
                    .orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
            List<TaskStageReadRow> stages = reads.taskOverviewStages(taskId);
            List<ErrorSummary> errors = mapper.listErrors(taskId).stream().map(this::errorSummary).toList();
            List<JudgeSummary> judges = mapper.listJudgeRuns(taskId).stream().map(this::judgeSummary).toList();
            recordRows("task-overview", 1L + stages.size() + errors.size() + judges.size());
            boolean retryAvailable = "LOOP_STAGNATION_DETECTED".equals(task.waitingReasonCode())
                    || "LOOP_FRESH_SESSION_REQUIRED".equals(task.waitingReasonCode());
            return new TaskOverview(task.id(), task.projectId(), task.projectName(), task.title(), task.goal(),
                    blankToDefault(task.branchName(), "等待选择执行模式"), task.worktreePath(), task.state(),
                    task.retryCause(), task.retryOrdinal(), task.retryCreatedAt(), task.retryDueAt(),
                    task.retryDelaySeconds(), task.waitingReasonCode(), retryAvailable,
                    task.hasDesignHistory() == 1, task.archived() == 1, task.executionResult(),
                    task.executionCycleOrdinal(), task.checkpointState(), task.parentTaskId(), task.successorTaskId(),
                    task.attemptCount(), task.maxTaskAttempts(), task.createdAt(), task.updatedAt(),
                    stages.stream().map(this::stage).toList(), workPackageProgress(stages, task.maxStageAttempts()), errors, judges);
        });
    }

    public TaskAudit audit(String taskId) {
        return measured("task-audit", () -> {
            List<TaskAuditAttemptRow> attempts = reads.taskAuditAttempts(taskId);
            Map<String, List<VerificationSummary>> verifications = reads.verificationSummaries(taskId).stream()
                    .map(this::verificationSummary)
                    .collect(Collectors.groupingBy(VerificationSummary::attemptId, LinkedHashMap::new, Collectors.toList()));
            List<AttemptSummary> attemptViews = attempts.stream().map(row -> new AttemptSummary(
                    row.id(), row.stageId(), row.ordinal(), row.executionCycleId(), row.sessionId(), row.state(),
                    row.failureKind(), row.summary(), row.createdAt(), row.endedAt(),
                    verifications.getOrDefault(row.id(), List.of()))).toList();
            List<TaskAuditEntryRow> entries = reads.taskAuditEntries(taskId);
            if (entries.stream().noneMatch(row -> "TASK".equals(row.entryType()))) {
                throw new NotFoundException("Task not found: " + taskId);
            }
            List<ErrorSummary> errors = entries.stream().filter(row -> "ERROR".equals(row.entryType()))
                    .map(row -> errorSummary(node(row.payloadJson()))).toList();
            List<JudgeSummary> judges = entries.stream().filter(row -> "JUDGE".equals(row.entryType()))
                    .map(row -> judgeSummary(node(row.payloadJson()))).toList();
            List<ArtifactSummary> artifacts = entries.stream().filter(row -> "ARTIFACT".equals(row.entryType()))
                    .map(row -> artifactSummary(node(row.payloadJson()))).toList();
            recordRows("task-audit", attemptViews.size() + errors.size() + judges.size() + artifacts.size());
            return new TaskAudit(attemptViews, errors, judges, artifacts);
        });
    }

    public ContentView verificationEvidence(String taskId, String id) {
        return measuredContent("verification-evidence", () -> {
            var row = reads.findVerificationForTask(taskId, id)
                    .orElseThrow(() -> new NotFoundException("Verification evidence not found: " + id));
            return new ContentView(row.id(), "VERIFICATION", "application/json", row.evidenceJson(), null);
        });
    }

    public ContentView errorEvidence(String taskId, String id) {
        return measuredContent("error-evidence", () -> {
            var row = reads.findErrorForTask(taskId, id)
                    .orElseThrow(() -> new NotFoundException("Error evidence not found: " + id));
            return new ContentView(row.id(), "ERROR", "application/json", row.evidenceJson(), null);
        });
    }

    public ContentView judgeOutput(String taskId, String id) {
        return measuredContent("judge-output", () -> {
            var row = reads.findJudgeForTask(taskId, id)
                    .orElseThrow(() -> new NotFoundException("Judge output not found: " + id));
            return new ContentView(row.id(), "JUDGE", "text/plain", row.rawOutput(), null);
        });
    }

    public ContentView artifactContent(String taskId, String id) {
        return measuredContent("artifact-content", () -> {
            var row = reads.findArtifactForTask(taskId, id)
                    .orElseThrow(() -> new NotFoundException("Task artifact not found: " + id));
            return new ContentView(row.id(), "ARTIFACT", row.contentType(), row.content(), node(row.metadataJson()));
        });
    }

    private TaskSummary summary(TaskSummaryRow row) {
        return new TaskSummary(row.id(), row.projectId(), row.projectName(), row.title(), row.goalPreview(),
                blankToDefault(row.branchName(), "等待选择执行模式"), row.state(), row.retryCause(), row.retryDueAt(),
                row.hasDesignHistory() == 1, row.archived() == 1, row.attemptCount(), row.maxAttempts(),
                row.createdAt(), row.updatedAt());
    }

    private StageSummary stage(StageRow row) {
        return new StageSummary(row.id(), row.ordinal(), row.objective(), row.state(), node(row.allowedPathsJson()),
                node(row.forbiddenPathsJson()), node(row.deliverablesJson()), node(row.verifiersJson()),
                row.createdAt(), row.updatedAt(), row.workPackageId());
    }

    private StageSummary stage(TaskStageReadRow row) {
        return new StageSummary(row.id(), row.ordinal(), row.objective(), row.state(), node(row.allowedPathsJson()),
                node(row.forbiddenPathsJson()), node(row.deliverablesJson()), node(row.verifiersJson()),
                row.createdAt(), row.updatedAt(), row.workPackageId());
    }

    private VerificationSummary verificationSummary(VerificationSummaryRow row) {
        return new VerificationSummary(row.id(), row.attemptId(), row.verifierIndex(), row.type(), row.state(),
                row.summary(), node(row.evidenceSummaryJson()), row.createdAt());
    }

    private ErrorSummary errorSummary(ErrorEventRow row) {
        return new ErrorSummary(row.id(), row.layer(), row.code(), row.message(), row.retryable(), row.stageId(),
                row.attemptId(), row.sessionId(), row.occurredAt());
    }

    private JudgeSummary judgeSummary(JudgeRunRow row) {
        return new JudgeSummary(row.id(), row.role(), row.ordinal(), row.state(), row.verdict(), row.reason(),
                row.externalSessionId(), row.createdAt(), row.endedAt(), row.rawOutput() != null && !row.rawOutput().isBlank());
    }

    private ArtifactSummary artifactSummary(TaskArtifactSummaryRow row) {
        return new ArtifactSummary(row.id(), row.kind(), row.name(), row.contentType(), node(row.metadataSummaryJson()),
                row.contentBytes(), row.attemptId(), row.judgeRunId(), row.createdAt());
    }

    private ErrorSummary errorSummary(JsonNode row) {
        return new ErrorSummary(text(row, "id"), text(row, "layer"), text(row, "code"), text(row, "message"),
                row.path("retryable").asBoolean(), text(row, "stageId"), text(row, "attemptId"),
                text(row, "sessionId"), text(row, "at"));
    }

    private JudgeSummary judgeSummary(JsonNode row) {
        return new JudgeSummary(text(row, "id"), text(row, "role"), row.path("ordinal").asInt(),
                text(row, "status"), text(row, "verdict"), text(row, "reason"),
                text(row, "externalSessionId"), text(row, "createdAt"), text(row, "endedAt"),
                row.path("hasRawOutput").asBoolean());
    }

    private ArtifactSummary artifactSummary(JsonNode row) {
        return new ArtifactSummary(text(row, "id"), text(row, "kind"), text(row, "name"),
                text(row, "contentType"), node(text(row, "metadataSummaryJson")),
                row.path("contentBytes").asLong(), text(row, "attemptId"), text(row, "judgeRunId"),
                text(row, "createdAt"));
    }

    private List<WorkPackageSummary> workPackageProgress(List<TaskStageReadRow> stages, int maxStageAttempts) {
        Map<String, List<TaskStageReadRow>> grouped = new LinkedHashMap<>();
        for (TaskStageReadRow stage : stages) if (stage.workPackageId() != null && !stage.workPackageId().isBlank()) {
            grouped.computeIfAbsent(stage.workPackageId(), ignored -> new ArrayList<>()).add(stage);
        }
        int[] ordinal = {0};
        return grouped.entrySet().stream().map(entry -> {
            List<TaskStageReadRow> packageStages = entry.getValue();
            int complete = (int) packageStages.stream().filter(stage -> "SUCCEEDED".equals(stage.state())).count();
            String state = packageStages.stream().anyMatch(stage -> "FAILED".equals(stage.state())) ? "FAILED"
                    : complete == packageStages.size() ? "SUCCEEDED"
                    : packageStages.stream().anyMatch(stage -> List.of("RUNNING", "PAUSED").contains(stage.state()))
                    ? "RUNNING" : "PENDING";
            int used = packageStages.stream().mapToInt(TaskStageReadRow::attemptCount).sum();
            int stageCount = packageStages.size();
            int limit = Math.min(stageCount * maxStageAttempts, stageCount + 2);
            return new WorkPackageSummary(entry.getKey(), ++ordinal[0], state, stageCount, complete, used, limit);
        }).toList();
    }

    private List<String> normalizedStates(List<String> states) {
        if (states == null || states.isEmpty()) return List.of();
        return states.stream().filter(value -> value != null && !value.isBlank()).map(value -> {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            try { TaskState.valueOf(normalized); }
            catch (IllegalArgumentException invalid) {
                throw new BadRequestException("TASK_STATE_INVALID", "Unknown task state: " + value);
            }
            return normalized;
        }).distinct().toList();
    }

    private String normalizedArchive(String archive) {
        String normalized = archive == null || archive.isBlank() ? "ACTIVE" : archive.trim().toUpperCase(Locale.ROOT);
        if (!ARCHIVE_MODES.contains(normalized)) {
            throw new BadRequestException("ARCHIVE_FILTER_INVALID", "Archive filter must be ACTIVE, ARCHIVED, or ALL");
        }
        return normalized;
    }

    private String likePattern(String query) {
        if (query == null || query.isBlank()) return null;
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 200) throw new BadRequestException("QUERY_TOO_LONG", "Search query must not exceed 200 characters");
        return "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private JsonNode node(String source) {
        if (source == null || source.isBlank()) return json.createObjectNode();
        try { return json.readTree(source); }
        catch (JacksonException invalid) { return json.createObjectNode(); }
    }

    private String text(JsonNode source, String field) {
        JsonNode value = source.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private <T> T measured(String view, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meters);
        try { return action.get(); }
        finally { sample.stop(Timer.builder("loopper.read_model.duration").tag("model", view).register(meters)); }
    }

    private ContentView measuredContent(String view, Supplier<ContentView> action) {
        ContentView result = measured(view, action);
        meters.counter("loopper.read_model.content.loads", "model", view).increment();
        DistributionSummary.builder("loopper.read_model.content.bytes").tag("model", view).register(meters)
                .record(result.content() == null ? 0 : result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        return result;
    }

    private void recordRows(String view, long rows) {
        DistributionSummary.builder("loopper.read_model.rows").tag("model", view).register(meters).record(rows);
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String blankToDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    public record TaskSummary(String id, String projectId, String projectName, String title, String goal,
                              String branch, String status, String retryCause, String retryDueAt,
                              boolean hasDesignHistory, boolean archived, int attemptCount, int maxAttempts,
                              String createdAt, String updatedAt) { }
    public record TaskOverview(String id, String projectId, String projectName, String title, String goal,
                               String branch, String worktreePath, String status, String retryCause,
                               Integer retryOrdinal, String retryScheduledAt, String retryDueAt,
                               Integer retryDelaySeconds, String waitingReasonCode, boolean loopRetryAvailable,
                               boolean hasDesignHistory, boolean archived, String executionResult,
                               Integer executionCycleOrdinal, String checkpointState, String parentTaskId,
                               String successorTaskId, int attemptCount, int maxAttempts, String createdAt,
                               String updatedAt, List<StageSummary> stages, List<WorkPackageSummary> workPackages,
                               List<ErrorSummary> errors, List<JudgeSummary> judges) { }
    public record StageSummary(String id, int ordinal, String objective, String status, JsonNode allowedPaths,
                               JsonNode forbiddenPaths, JsonNode deliverables, JsonNode verifiers,
                               String startedAt, String updatedAt, String workPackageId) { }
    public record WorkPackageSummary(String id, int ordinal, String status, int stageCount,
                                     int completedStages, int attemptCount, int attemptLimit) { }
    public record TaskAudit(List<AttemptSummary> attempts, List<ErrorSummary> errors,
                            List<JudgeSummary> judges, List<ArtifactSummary> artifacts) { }
    public record AttemptSummary(String id, String stageId, int ordinal, String executionCycleId,
                                 String sessionId, String status, String failureKind, String summary,
                                 String startedAt, String endedAt, List<VerificationSummary> verifications) { }
    public record VerificationSummary(String id, String attemptId, int verifierIndex, String type,
                                      String status, String summary, JsonNode evidenceSummary, String at) { }
    public record ErrorSummary(String id, String layer, String code, String message, boolean retryable,
                               String stageId, String attemptId, String sessionId, String at) { }
    public record JudgeSummary(String id, String role, int ordinal, String status, String verdict, String reason,
                               String externalSessionId, String createdAt, String endedAt, boolean hasRawOutput) { }
    public record ArtifactSummary(String id, String kind, String name, String contentType, JsonNode metadataSummary,
                                  long contentBytes, String attemptId, String judgeRunId, String createdAt) { }
    public record ContentView(String id, String kind, String contentType, String content, JsonNode metadata) { }
}
