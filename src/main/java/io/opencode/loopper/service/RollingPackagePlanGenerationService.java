package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.PackagePlanRevisionState;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.TaskPackageRunState;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskPackagePlanRevisionRow;
import io.opencode.loopper.persistence.TaskPackageRunRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Runs the read-only, restart-safe AI suggestion transport for the unexecuted package suffix. */
@Service
public class RollingPackagePlanGenerationService {
    private static final Pattern MARKER = Pattern.compile(
            "(?s)<!--\\s*ROLLING_PACKAGE_PLAN_JSON_START\\s*-->(.*?)"
                    + "<!--\\s*ROLLING_PACKAGE_PLAN_JSON_END\\s*-->");
    private static final Set<String> UNFINISHED = Set.of(TaskPackageRunState.PLANNED.name(),
            TaskPackageRunState.DESIGNING.name(), TaskPackageRunState.DESIGN_REVIEW.name(),
            TaskPackageRunState.EXECUTION_READY.name(), TaskPackageRunState.WAITING_INPUT.name());

    private final LoopperMapper mapper;
    private final RollingPackagePlanService plans;
    private final RollingPackageCodec codec;
    private final TaskWorkspaceCheckpointService checkpoints;
    private final OpenCodeClient openCode;
    private final LoopperProperties properties;
    private final AiOutputExtractor extractor;

    public RollingPackagePlanGenerationService(LoopperMapper mapper, RollingPackagePlanService plans,
                                               tools.jackson.databind.ObjectMapper json,
                                               TaskWorkspaceCheckpointService checkpoints,
                                               OpenCodeClient openCode, LoopperProperties properties,
                                               AiOutputExtractor extractor) {
        this.mapper = mapper;
        this.plans = plans;
        this.codec = new RollingPackageCodec(json);
        this.checkpoints = checkpoints;
        this.openCode = openCode;
        this.properties = properties;
        this.extractor = extractor;
    }

    public RollingPackagePlanService.Proposal suggest(String taskId, long expectedTaskVersion,
                                                       String expectedPackageRunId,
                                                       long expectedPackageVersion) {
        RollingPackagePlanService.SuggestionAnchor anchor = plans.beginSuggestion(taskId, expectedTaskVersion,
                expectedPackageRunId, expectedPackageVersion);
        dispatch(anchor.revision());
        return plans.proposals(taskId).stream().filter(item -> item.id().equals(anchor.revision().id()))
                .findFirst().orElseThrow();
    }

    public void pollGenerating() {
        for (TaskPackagePlanRevisionRow row : mapper.listGeneratingTaskPackagePlanRevisions()) {
            try {
                poll(mapper.findTaskPackagePlanRevision(row.id()).orElse(row));
            } catch (RuntimeException failure) {
                TaskPackagePlanRevisionRow current = mapper.findTaskPackagePlanRevision(row.id()).orElse(row);
                if (PackagePlanRevisionState.GENERATING.name().equals(current.state())) {
                    plans.failSuggestion(current, code(failure), failure.getMessage());
                }
            }
        }
    }

    private void poll(TaskPackagePlanRevisionRow row) {
        if (row.externalSessionId() == null || row.externalSessionId().isBlank()
                || "PENDING".equals(row.externalSessionState())) {
            dispatch(row);
            return;
        }
        if ("PROMPTING".equals(row.externalSessionState())) {
            abort(row);
            dispatch(mapper.findTaskPackagePlanRevision(row.id()).orElse(row));
            return;
        }
        if (timedOut(row.createdAt())) {
            abort(row);
            plans.failSuggestion(mapper.findTaskPackagePlanRevision(row.id()).orElse(row),
                    "PACKAGE_PLAN_SUGGESTION_TIMEOUT", "AI 剩余拆包建议超过只读设计超时");
            return;
        }
        Path snapshot = verifiedSnapshot(row);
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(row.externalSessionId(), snapshot);
        List<OpenCodeClient.PendingQuestion> questions = openCode.pendingQuestions(remote);
        if (!questions.isEmpty()) {
            for (OpenCodeClient.PendingQuestion question : questions) {
                try { openCode.rejectQuestion(remote, question.id()); } catch (RuntimeException ignored) { }
            }
            abort(row);
            plans.failSuggestion(mapper.findTaskPackagePlanRevision(row.id()).orElse(row),
                    "PACKAGE_PLAN_INTERACTION_FORBIDDEN", "只读计划建议不得向用户提问");
            return;
        }
        OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
        if (status.retrying()) return;
        if (status.failed()) {
            plans.failSuggestion(row, "PACKAGE_PLAN_SESSION_FAILED",
                    status.detail() == null ? status.state() : status.detail());
            return;
        }
        if (!status.completed()) return;
        AiPlanEnvelope result = extractor.extractJson(openCode.sessionOutput(remote), MARKER,
                "PACKAGE_PLAN_SUGGESTION", AiPlanEnvelope.class, AiPlanEnvelope::normalized,
                this::validate).value();
        plans.completeSuggestion(row, toPlanPackages(row, result));
    }

    private void dispatch(TaskPackagePlanRevisionRow input) {
        TaskPackagePlanRevisionRow row = mapper.findTaskPackagePlanRevision(input.id()).orElse(input);
        try {
            if (!openCode.healthy()) throw new ConflictException(
                    "PACKAGE_PLAN_OPENCODE_UNAVAILABLE", "OpenCode 只读运行时不可用");
            Path snapshot = verifiedSnapshot(row);
            OpenCodeClient.OpenCodeSession remote = openCode.createSession(snapshot,
                    "OpenCode Loopper Rolling Task Decomposer (READ_ONLY)", configuredModel(),
                    OpenCodeClient.SessionProfile.DECOMPOSER_READ_ONLY);
            row = plans.attachSuggestionSession(row, remote.id(), "PROMPTING");
            openCode.promptAsync(remote, new OpenCodeClient.PromptRequest(prompt(row),
                    OpenCodeClient.STRUCTURED_AGENT_PROMPT, OpenCodeClient.STRUCTURED_AGENT,
                    new OpenCodeClient.ResponseFormat.Text()));
            plans.updateSuggestionState(mapper.findTaskPackagePlanRevision(row.id()).orElse(row), "RUNNING");
        } catch (RuntimeException failure) {
            TaskPackagePlanRevisionRow current = mapper.findTaskPackagePlanRevision(row.id()).orElse(row);
            abort(current);
            plans.failSuggestion(mapper.findTaskPackagePlanRevision(row.id()).orElse(current),
                    code(failure), failure.getMessage());
        }
    }

    private Path verifiedSnapshot(TaskPackagePlanRevisionRow row) {
        var task = mapper.findTask(row.taskId()).orElseThrow();
        var run = mapper.findTaskPackageRun(row.basePackageRunId()).orElseThrow();
        var checkpoint = mapper.findTaskWorkspaceCheckpoint(row.baseCheckpointId()).orElseThrow();
        if (task.version() != row.baseTaskVersion() || run.version() != row.basePackageVersion()) {
            throw new ConflictException("PACKAGE_PLAN_SUGGESTION_BASE_CHANGED",
                    "AI 建议期间任务或工作包已变化");
        }
        return checkpoints.designSnapshot(task, checkpoint);
    }

    private String prompt(TaskPackagePlanRevisionRow row) {
        var requirement = mapper.findDesignRequirementRevision(row.requirementRevisionId()).orElseThrow();
        List<Map<String, Object>> unfinished = new ArrayList<>();
        for (TaskPackageRunRow run : mapper.listTaskPackageRuns(row.taskId())) {
            if (!row.taskId().equals(run.taskId()) || !UNFINISHED.contains(run.state())) continue;
            DesignWorkPackageRow design = mapper.findDesignWorkPackage(run.designWorkPackageId()).orElseThrow();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("packageKey", run.packageKey());
            item.put("title", run.title());
            item.put("objective", design.objective());
            item.put("dependencies", codec.jsonValue(design.dependenciesJson()));
            item.put("requirementRefs", codec.jsonValue(design.requirementRefsJson()));
            unfinished.add(item);
        }
        String facts = codec.factContext(mapper.listPackageFactSnapshots(row.taskId()));
        return """
                你是大型软件任务的只读剩余计划规划师。当前目录是上一成功事实点构造的精确只读快照。
                只能使用 read/glob/grep 核对真实代码，不得写文件、执行命令、提问或修改已冻结工作包。
                基于原始冻结需求、真实快照和已冻结事实，只重新规划尚未执行的后缀。输出 1–6 个包。
                replaces 必须引用下方当前未执行包的 packageKey；拆分时多个目标可引用同一来源，合并时一个目标可引用多个来源。
                dependencies 使用工作包 packageKey。新增包可使用空 replaces。不要把 AI 摘要当成机器证据。

                原始冻结需求：
                %s

                当前未执行计划：
                %s

                已冻结事实索引：
                %s

                仅返回以下 marker 包裹的 JSON，不要解释：
                <!-- ROLLING_PACKAGE_PLAN_JSON_START -->
                {"packages":[{"packageKey":"WP-2","title":"标题","objective":"目标","replaces":["WP-2"],"dependencies":["WP-1"],"requirementRefs":["RQ-2"]}]}
                <!-- ROLLING_PACKAGE_PLAN_JSON_END -->
                """.formatted(requirement.requirementText(), codec.write(unfinished), facts);
    }

    private List<RollingPackagePlanService.PlanPackage> toPlanPackages(TaskPackagePlanRevisionRow row,
                                                                        AiPlanEnvelope envelope) {
        Map<String, TaskPackageRunRow> current = new LinkedHashMap<>();
        for (TaskPackageRunRow run : mapper.listTaskPackageRuns(row.taskId())) {
            if (UNFINISHED.contains(run.state())) current.put(run.packageKey(), run);
        }
        List<RollingPackagePlanService.PlanPackage> result = new ArrayList<>();
        for (AiPlanPackage item : envelope.packages()) {
            List<String> sourceIds = item.replaces().stream().map(key -> {
                TaskPackageRunRow source = current.get(key);
                if (source == null) throw new BadRequestException("PACKAGE_PLAN_SOURCE_MISSING",
                        "AI 建议引用了非当前未执行工作包: " + key);
                return source.id();
            }).toList();
            result.add(new RollingPackagePlanService.PlanPackage(item.packageKey(), item.title(), item.objective(),
                    sourceIds.isEmpty() ? null : sourceIds.getFirst(), sourceIds, null,
                    item.dependencies(), item.requirementRefs()));
        }
        return List.copyOf(result);
    }

    private void validate(AiPlanEnvelope envelope) {
        if (envelope.packages().isEmpty() || envelope.packages().size() > 6) {
            throw new BadRequestException("PACKAGE_PLAN_SIZE_INVALID", "AI 建议必须包含 1–6 个工作包");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (AiPlanPackage item : envelope.packages()) {
            if (item.packageKey() == null || !item.packageKey().matches("[A-Za-z0-9_-]{1,40}")
                    || item.title() == null || item.title().isBlank() || !keys.add(item.packageKey())) {
                throw new BadRequestException("PACKAGE_PLAN_ITEM_INVALID", "AI 建议包含无效或重复工作包");
            }
        }
    }

    private void abort(TaskPackagePlanRevisionRow row) {
        if (row.externalSessionId() == null || row.externalSessionId().isBlank()) return;
        try {
            openCode.abortWithConfirmation(new OpenCodeClient.OpenCodeSession(row.externalSessionId(),
                    verifiedSnapshot(row)));
        } catch (RuntimeException ignored) { }
    }

    private OpenCodeClient.OpenCodeModel configuredModel() {
        String configured = properties.getOpenCode().getModel();
        if (configured == null) return null;
        String value = configured.trim();
        int separator = value.indexOf('/');
        if (separator <= 0 || separator >= value.length() - 1) return null;
        String provider = value.substring(0, separator).trim();
        String model = value.substring(separator + 1).trim();
        return provider.isEmpty() || model.isEmpty() ? null
                : new OpenCodeClient.OpenCodeModel(provider, model, null);
    }

    private boolean timedOut(String createdAt) {
        Duration timeout = properties.getDesignerTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return false;
        try { return Duration.between(Instant.parse(createdAt), Instant.now()).compareTo(timeout) > 0; }
        catch (RuntimeException ignored) { return false; }
    }

    private String code(RuntimeException failure) {
        return failure instanceof SessionFailure session ? session.code() : failure instanceof ConflictException conflict
                ? conflict.code() : "PACKAGE_PLAN_SUGGESTION_FAILED";
    }

    public record AiPlanEnvelope(List<AiPlanPackage> packages) {
        AiPlanEnvelope normalized() { return new AiPlanEnvelope(packages == null ? List.of() : List.copyOf(packages)); }
    }

    public record AiPlanPackage(String packageKey, String title, String objective, List<String> replaces,
                                List<String> dependencies, List<String> requirementRefs) {
        public AiPlanPackage {
            replaces = replaces == null ? List.of() : List.copyOf(replaces);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            requirementRefs = requirementRefs == null ? List.of() : List.copyOf(requirementRefs);
            objective = objective == null ? title : objective;
        }
    }
}
