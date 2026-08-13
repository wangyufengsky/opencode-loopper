package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.DesignWorkflowPhase;
import io.opencode.loopper.domain.DesignRequirementRevisionState;
import io.opencode.loopper.domain.DesignWorkPackageState;
import io.opencode.loopper.domain.DesignerActor;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.LoopSpecCompilationState;
import io.opencode.loopper.domain.TaskDecompositionState;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.StructuredModelStep;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Coordinates three strictly separated read-only roles. Designer produces only
 * Markdown, Compiler produces only a marked machine envelope, and the server
 * validator is the sole authority allowed to synchronize the bound draft.
 */
@Service
public class DesignerSessionService {
    public static final String READ_ONLY = "READ_ONLY";
    private static final int MAX_MESSAGE_LENGTH = 12_000;
    private static final int MAX_FROZEN_DESIGN_LENGTH = 24 * 1024;
    private static final int MAX_HANDOFF_SUMMARY_LENGTH = 4 * 1024;
    private static final int MAX_DECOMPOSER_REPAIRS = 2;
    private static final int MAX_MODEL_CALLS = 32;
    private static final int MAX_WORK_PACKAGES = 6;
    private static final int MAX_PACKAGE_STAGES = 3;
    private static final int MAX_TOTAL_STAGES = 18;
    private static final int MAX_COMPILER_REPAIRS = 2;
    private static final int MAX_AUTOMATIC_REDESIGNS = 1;
    private static final Pattern COMPILATION_PAYLOAD = Pattern.compile(
            "<!--\\s*LOOPSPEC_COMPILATION_JSON_START\\s*-->(.*?)<!--\\s*LOOPSPEC_COMPILATION_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DECOMPOSITION_PAYLOAD = Pattern.compile(
            "<!--\\s*TASK_DECOMPOSITION_JSON_START\\s*-->(.*?)<!--\\s*TASK_DECOMPOSITION_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DECOMPOSITION_PLAN_PAYLOAD = Pattern.compile(
            "<!--\\s*TASK_DECOMPOSITION_PLAN_JSON_START\\s*-->(.*?)<!--\\s*TASK_DECOMPOSITION_PLAN_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STANDALONE_JSON_FENCE = Pattern.compile(
            "\\A\\s*```(?:json)?\\s*(\\{.*})\\s*```\\s*\\z",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMPILATION_PLAN_PAYLOAD = Pattern.compile(
            "<!--\\s*LOOPSPEC_COMPILATION_PLAN_JSON_START\\s*-->(.*?)<!--\\s*LOOPSPEC_COMPILATION_PLAN_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** Compatibility sanitization only: a Designer payload is never consumed as LoopSpec. */
    private static final Pattern LEGACY_DESIGNER_PAYLOAD = Pattern.compile(
            "<!--\\s*LOOPSPEC_JSON_START\\s*-->.*?<!--\\s*LOOPSPEC_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<DesignGapCode> ALLOWED_DESIGN_GAPS = EnumSet.allOf(DesignGapCode.class);

    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ProjectService projects;
    private final OpenCodeClient openCode;
    private final LoopperProperties defaults;
    private final LoopDraftService drafts;
    private final ObjectMapper json;
    private final DesignerEventHub events;

    public DesignerSessionService(LoopperMapper mapper, LifecycleTransitionService lifecycle,
                                  ProjectService projects, OpenCodeClient openCode,
                                  LoopperProperties defaults, LoopDraftService drafts, ObjectMapper json,
                                  DesignerEventHub events) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.projects = projects;
        this.openCode = openCode;
        this.defaults = defaults;
        this.drafts = drafts;
        this.json = json;
        this.events = events;
    }

    public DesignerSessionRow create(String projectId, String initialMessage) {
        return create(projectId, null, initialMessage);
    }

    public DesignerSessionRow create(String projectId, String loopDraftId, String initialMessage) {
        projects.get(projectId);
        if (loopDraftId != null) {
            LoopDraftRow draft = drafts.get(loopDraftId);
            if (!projectId.equals(draft.projectId())) {
                throw new BadRequestException("DESIGNER_DRAFT_PROJECT_MISMATCH",
                        "Designer session and LoopSpec draft must belong to the same project");
            }
        }
        String now = now();
        DesignerSessionRow session = new DesignerSessionRow(UUID.randomUUID().toString(), projectId,
                DesignerSessionState.PENDING_HANDOFF.name(), READ_ONLY, now, now, 0,
                null, "PENDING", loopDraftId, DesignWorkflowPhase.DECOMPOSING.name(), 0, 0, null, null);
        lifecycle.create(designerSubject(session), session.state(), java.util.Map.of(),
                () -> mapper.insertDesignerSession(session),
                () -> new ConflictException("DESIGNER_SESSION_CREATE_CONFLICT",
                        "Designer session could not be created"));
        appendMessage(session.id(), DesignerActor.SYSTEM,
                "设计会话已创建。任务拆解器将先冻结完整需求并决定一个或多个纵向工作包；每个工作包再由独立设计师和规范编译器严格串行处理。",
                DesignerSessionState.PENDING_HANDOFF.name(), null, null);
        if (initialMessage != null && !initialMessage.isBlank()) appendUserMessage(session.id(), initialMessage);
        return get(session.id());
    }

    public DesignerSessionRow get(String sessionId) {
        return mapper.findDesignerSession(sessionId)
                .orElseThrow(() -> new NotFoundException("Designer session not found: " + sessionId));
    }

    public ProjectRow project(String sessionId) { return projects.get(get(sessionId).projectId()); }

    public List<DesignerMessageRow> messages(String sessionId) {
        get(sessionId);
        return mapper.listDesignerMessages(sessionId);
    }

    public CompilerStatus compilerStatus(String sessionId) {
        get(sessionId);
        return mapper.findLatestLoopSpecCompilation(sessionId)
                .map(row -> new CompilerStatus(row.id(), row.state(), row.externalSessionId(),
                        row.externalSessionState(), row.repairCount(), row.designRevision(),
                        row.lastErrorCode(), row.lastErrorDetail(), row.workPackageId(), row.workflowStep(),
                        row.planningRepairCount()))
                .orElse(null);
    }

    public RequirementRevisionStatus requirementStatus(String sessionId) {
        get(sessionId);
        return mapper.findCurrentDesignRequirementRevision(sessionId)
                .map(row -> new RequirementRevisionStatus(row.revision(), row.state(), row.modelCallsUsed(),
                        row.maxModelCalls(), row.sourceDraftVersion()))
                .orElse(null);
    }

    public DecompositionStatus decompositionStatus(String sessionId) {
        get(sessionId);
        return mapper.findLatestTaskDecomposition(sessionId)
                .map(row -> new DecompositionStatus(row.id(), row.state(), row.resultType(), row.repairCount(),
                        row.transportRetryCount(), row.lastErrorCode(), row.lastErrorDetail(), row.workflowStep(),
                        row.planningRepairCount()))
                .orElse(null);
    }

    public List<WorkPackageStatus> workPackageStatuses(String sessionId) {
        DesignRequirementRevisionRow revision = mapper.findCurrentDesignRequirementRevision(sessionId).orElse(null);
        if (revision == null) return List.of();
        return mapper.listDesignWorkPackages(revision.id()).stream().map(row -> {
            LoopSpecCompilationRow compiler = mapper.findLatestLoopSpecCompilationForPackage(sessionId, row.packageId())
                    .orElse(null);
            return new WorkPackageStatus(row.packageId(), row.ordinal(), row.title(), row.objective(), row.state(),
                    strings(row.dependenciesJson()), row.redesignCount(), compiler == null ? 0 : compiler.repairCount(),
                    compiler == null ? 0 : compiler.planningRepairCount(),
                    row.compilerSummary(), row.handoffSummary(), row.lastErrorCode(), row.lastErrorDetail());
        }).toList();
    }

    public String activeActor(DesignerSessionRow session) {
        return switch (DesignWorkflowPhase.valueOf(session.workflowPhase())) {
            case DECOMPOSING -> DesignerActor.DECOMPOSER.name();
            case VALIDATING_DECOMPOSITION, AGGREGATING -> DesignerActor.VALIDATOR.name();
            case DESIGNING, REDESIGNING -> DesignerActor.DESIGNER.name();
            case COMPILING -> DesignerActor.COMPILER.name();
            case VALIDATING -> DesignerActor.VALIDATOR.name();
            case COMPLETED, FAILED -> DesignerActor.SYSTEM.name();
        };
    }

    public String structuredModelStep(String sessionId) {
        String actor = activeActor(get(sessionId));
        if (DesignerActor.COMPILER.name().equals(actor)) {
            CompilerStatus compiler = compilerStatus(sessionId);
            return compiler == null ? null : compiler.workflowStep();
        }
        if (DesignerActor.DECOMPOSER.name().equals(actor)) {
            DecompositionStatus decomposition = decompositionStatus(sessionId);
            return decomposition == null ? null : decomposition.workflowStep();
        }
        return null;
    }

    public List<PendingQuestion> pendingQuestions(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        if (!DesignerSessionState.RUNNING.name().equals(session.state())
                || !Set.of(DesignWorkflowPhase.DESIGNING.name(), DesignWorkflowPhase.REDESIGNING.name())
                .contains(session.workflowPhase())
                || blank(session.externalSessionId())) return List.of();
        try {
            return openCode.pendingQuestions(designerRemote(session)).stream().map(this::question).toList();
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
    }

    public void replyQuestion(String sessionId, String questionId, List<List<String>> answers) {
        DesignerSessionRow session = requireRunningDesigner(sessionId);
        OpenCodeClient.OpenCodeSession remote = designerRemote(session);
        OpenCodeClient.PendingQuestion pending = pending(remote, questionId);
        try {
            openCode.replyQuestion(remote, pending.id(), validateAnswers(pending, answers));
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
        updateDesignerProjection(get(sessionId), DesignerSessionState.RUNNING,
                DesignWorkflowPhase.valueOf(get(sessionId).workflowPhase()), remote.id(), "RUNNING",
                get(sessionId).designRevision(), get(sessionId).redesignCount());
    }

    public void rejectQuestion(String sessionId, String questionId) {
        DesignerSessionRow session = requireRunningDesigner(sessionId);
        OpenCodeClient.OpenCodeSession remote = designerRemote(session);
        OpenCodeClient.PendingQuestion pending = pending(remote, questionId);
        try { openCode.rejectQuestion(remote, pending.id()); }
        catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
        updateDesignerProjection(get(sessionId), DesignerSessionState.RUNNING,
                DesignWorkflowPhase.valueOf(get(sessionId).workflowPhase()), remote.id(), "RUNNING",
                get(sessionId).designRevision(), get(sessionId).redesignCount());
    }

    public LoopDraftRow draft(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        return blank(session.loopDraftId()) ? null : drafts.get(session.loopDraftId());
    }

    /** MCP/manual compatibility boundary; the same deterministic contract still applies. */
    @Transactional
    public LoopDraftRow syncLoopSpec(String sessionId, LoopSpec spec) {
        DesignerSessionRow session = get(sessionId);
        if (session.currentRequirementRevision() != null
                && !DesignWorkflowPhase.COMPLETED.name().equals(session.workflowPhase())) {
            throw new ConflictException("DECOMPOSED_DESIGN_WORKFLOW_ACTIVE",
                    "MCP LoopSpec proposals are blocked until the active decomposed design workflow completes");
        }
        requireBoundDraft(session);
        requireProject(session, spec);
        return drafts.update(session.loopDraftId(), spec);
    }

    public List<DesignerMessageRow> appendUserMessage(String sessionId, String content) {
        DesignerSessionRow session = get(sessionId);
        int nextRevision = mapper.findCurrentDesignRequirementRevision(sessionId)
                .map(row -> row.revision() + 1).orElse(1);
        DesignerMessageRow user = appendMessage(session.id(), DesignerActor.USER,
                normalizeMessage(content), "PERSISTED", nextRevision, null);
        supersedeCurrentRequirement(session);
        DesignRequirementRevisionRow revision = freezeRequirementRevision(get(sessionId), user);
        DesignerMessageRow notice = dispatchDecomposer(get(sessionId), revision, false);
        return List.of(user, notice);
    }

    /** Explicit recovery: compile the latest frozen design in a brand-new read-only Session. */
    public void retryCompilation(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        if (DesignerSessionState.RUNNING.name().equals(session.state())) {
            throw new ConflictException("DESIGN_WORKFLOW_BUSY", "The design workflow is still running");
        }
        DesignWorkPackageRow workPackage = recoverableWorkPackage(session, true);
        retryPackageCompilation(sessionId, workPackage.packageId());
    }

    /** Explicit recovery: ask Designer for a complete replacement, not a patch. */
    public void requestRedesign(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        if (DesignerSessionState.RUNNING.name().equals(session.state())) {
            throw new ConflictException("DESIGN_WORKFLOW_BUSY", "The design workflow is still running");
        }
        DesignWorkPackageRow workPackage = recoverableWorkPackage(session, false);
        requestPackageRedesign(sessionId, workPackage.packageId());
    }

    public void retryDecomposition(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        if (DesignerSessionState.RUNNING.name().equals(session.state())) {
            throw new ConflictException("DESIGN_WORKFLOW_BUSY", "The design workflow is still running");
        }
        DesignRequirementRevisionRow revision = reactivateRequirement(currentRequirement(sessionId));
        dispatchDecomposer(session, revision, true);
    }

    public void retryPackageCompilation(String sessionId, String packageId) {
        DesignerSessionRow session = get(sessionId);
        if (DesignerSessionState.RUNNING.name().equals(session.state())) {
            throw new ConflictException("DESIGN_WORKFLOW_BUSY", "The design workflow is still running");
        }
        DesignWorkPackageRow workPackage = requireCurrentPackage(session, packageId);
        reactivateRequirement(currentRequirement(sessionId));
        DesignerMessageRow source = designMessage(workPackage);
        DesignWorkPackageRow compiling = updateWorkPackage(workPackage, DesignWorkPackageState.COMPILING,
                workPackage.designerExternalSessionId(), workPackage.designerExternalSessionState(),
                workPackage.designMessageId(), workPackage.designRevision(), workPackage.redesignCount(),
                workPackage.designerTransportRetryCount(), workPackage.compilerSummary(), workPackage.handoffSummary(),
                null, null);
        DesignerSessionRow running = updateDesignerProjection(session, DesignerSessionState.RUNNING,
                DesignWorkflowPhase.COMPILING, session.externalSessionId(), session.externalSessionState(),
                session.designRevision(), session.redesignCount(), session.currentRequirementRevision(), packageId);
        startCompilation(running, compiling, source);
    }

    public void requestPackageRedesign(String sessionId, String packageId) {
        DesignerSessionRow session = get(sessionId);
        if (DesignerSessionState.RUNNING.name().equals(session.state())) {
            throw new ConflictException("DESIGN_WORKFLOW_BUSY", "The design workflow is still running");
        }
        DesignWorkPackageRow workPackage = requireCurrentPackage(session, packageId);
        reactivateRequirement(currentRequirement(sessionId));
        dispatchPackageDesigner(session, workPackage, redesignPrompt("人工要求重新设计当前工作包完整方案"), true);
    }

    /** External model calls are deliberately outside a surrounding database transaction. */
    public void pollActiveHandoffs() {
        for (TaskDecompositionRow decomposition : mapper.activeTaskDecompositions()) {
            try { pollDecomposer(decomposition); }
            catch (RuntimeException ignoredConcurrentTransition) { }
        }
        for (DesignWorkPackageRow workPackage : mapper.activeDesignWorkPackages()) {
            try { pollWorkPackageDesigner(workPackage); }
            catch (RuntimeException ignoredConcurrentTransition) { }
        }
        for (DesignerSessionRow session : mapper.activeDesignerHandoffs()) {
            if (session.currentRequirementRevision() == null) try { pollDesigner(session); }
            catch (RuntimeException ignoredConcurrentTransition) { }
        }
        for (LoopSpecCompilationRow compilation : mapper.activeLoopSpecCompilations()) {
            try { pollCompiler(compilation); }
            catch (RuntimeException ignoredConcurrentTransition) { }
        }
    }

    private void supersedeCurrentRequirement(DesignerSessionRow session) {
        DesignRequirementRevisionRow current = mapper.findCurrentDesignRequirementRevision(session.id()).orElse(null);
        if (current == null || DesignRequirementRevisionState.SUPERSEDED.name().equals(current.state())) return;
        mapper.findTaskDecompositionByRevision(current.id()).ifPresent(row -> {
            abortQuietly(row.externalSessionId(), session.projectId());
            if (Set.of(TaskDecompositionState.PENDING_HANDOFF.name(), TaskDecompositionState.RUNNING.name(),
                    TaskDecompositionState.VALIDATING.name()).contains(row.state())) {
                updateDecomposition(row, TaskDecompositionState.SESSION_ERROR, row.resultType(), row.normalizedGoal(),
                        row.globalConstraintsJson(), row.planJson(), row.externalSessionId(), "SUPERSEDED",
                        row.repairCount(), row.transportRetryCount(), "REQUIREMENT_SUPERSEDED",
                        "A newer complete requirement revision replaced this workflow");
            }
        });
        for (DesignWorkPackageRow workPackage : mapper.listDesignWorkPackages(current.id())) {
            abortQuietly(workPackage.designerExternalSessionId(), session.projectId());
            if (Set.of(DesignWorkPackageState.DESIGNING.name(), DesignWorkPackageState.COMPILING.name(),
                    DesignWorkPackageState.VALIDATING.name()).contains(workPackage.state())) {
                updateWorkPackage(workPackage, DesignWorkPackageState.FAILED,
                        workPackage.designerExternalSessionId(), "SUPERSEDED", workPackage.designMessageId(),
                        workPackage.designRevision(), workPackage.redesignCount(),
                        workPackage.designerTransportRetryCount(), workPackage.compilerSummary(),
                        workPackage.handoffSummary(), "REQUIREMENT_SUPERSEDED",
                        "A newer complete requirement revision replaced this work package");
            }
            mapper.findLatestLoopSpecCompilationForPackage(session.id(), workPackage.packageId()).ifPresent(compilation -> {
                abortQuietly(compilation.externalSessionId(), session.projectId());
                if (Set.of(LoopSpecCompilationState.PENDING_HANDOFF.name(), LoopSpecCompilationState.RUNNING.name())
                        .contains(compilation.state())) {
                    updateCompilation(compilation, LoopSpecCompilationState.SESSION_ERROR,
                            compilation.externalSessionId(), "SUPERSEDED", compilation.repairCount(),
                            "REQUIREMENT_SUPERSEDED", "A newer requirement revision replaced this compilation",
                            session.projectId(), compilation.compiledPackageJson());
                }
            });
        }
        updateRequirement(current, DesignRequirementRevisionState.SUPERSEDED, current.modelCallsUsed());
    }

    private DesignRequirementRevisionRow freezeRequirementRevision(DesignerSessionRow session,
                                                                    DesignerMessageRow sourceMessage) {
        requireBoundDraft(session);
        int revision = sourceMessage.requirementRevision() == null ? 1 : sourceMessage.requirementRevision();
        String requirement = mapper.listDesignerMessages(session.id()).stream()
                .filter(message -> DesignerActor.USER.name().equals(message.actor()))
                .filter(message -> message.ordinal() <= sourceMessage.ordinal())
                .map(DesignerMessageRow::content).filter(value -> !blank(value))
                .reduce((left, right) -> left + "\n\n" + right).orElse(sourceMessage.content());
        List<RequirementSegment> segments = segmentRequirements(requirement);
        LoopDraftRow draft = drafts.get(session.loopDraftId());
        String now = now();
        DesignRequirementRevisionRow row = new DesignRequirementRevisionRow(UUID.randomUUID().toString(),
                session.id(), revision, sourceMessage.id(), requirement, write(segments), draft.version(),
                DesignRequirementRevisionState.ACTIVE.name(), 0, MAX_MODEL_CALLS, now, now, 0);
        lifecycle.create(requirementSubject(row, session.projectId()), row.state(), Map.of("revision", revision),
                () -> mapper.insertDesignRequirementRevision(row),
                () -> new ConflictException("DESIGN_REQUIREMENT_REVISION_CREATE_CONFLICT",
                        "The complete requirement revision could not be frozen"));
        updateDesignerProjection(get(session.id()), DesignerSessionState.PENDING_HANDOFF,
                DesignWorkflowPhase.DECOMPOSING, null, "PENDING", session.designRevision(), 0,
                revision, null);
        return getRequirement(row.id());
    }

    private DesignerMessageRow dispatchDecomposer(DesignerSessionRow input,
                                                   DesignRequirementRevisionRow revision,
                                                   boolean explicitRetry) {
        requireDraftUnchanged(input, revision.sourceDraftVersion());
        if (!openCode.healthy()) {
            DesignerSessionRow pending = updateDesignerProjection(input, DesignerSessionState.PENDING_HANDOFF,
                    DesignWorkflowPhase.DECOMPOSING, null, "UNAVAILABLE", input.designRevision(), 0,
                    revision.revision(), null);
            DesignerMessageRow message = appendMessage(pending.id(), DesignerActor.SYSTEM,
                    "SYSTEM_ERROR[SESSION] OPENCODE_DECOMPOSER_UNAVAILABLE: OpenCode 只读运行时不可用；需求版本已冻结，但尚未消耗模型调用。",
                    "PENDING_HANDOFF", revision.revision(), null);
            publish(pending, "ERROR", DesignerActor.SYSTEM, false, "", message.content());
            return message;
        }
        String now = now();
        TaskDecompositionRow pending = new TaskDecompositionRow(UUID.randomUUID().toString(), input.id(),
                revision.id(), TaskDecompositionState.PENDING_HANDOFF.name(), null, null, "[]", "{}",
                null, "PENDING", 0, 0, revision.sourceDraftVersion(), null, null, now, now, 0,
                StructuredModelStep.PLANNING.name(), null, 0);
        lifecycle.create(decompositionSubject(pending, input.projectId()), pending.state(), Map.of(),
                () -> mapper.insertTaskDecomposition(pending),
                () -> new ConflictException("TASK_DECOMPOSITION_CREATE_CONFLICT",
                        "Task decomposition could not be created"));
        ProjectRow project = projects.get(input.projectId());
        try {
            OpenCodeClient.OpenCodeSession remote = openCode.createReadOnlySession(Path.of(project.rootPath()),
                    "OpenCode Loopper Task Decomposer (READ_ONLY)", configuredModel());
            TaskDecompositionRow running = updateDecomposition(pending, TaskDecompositionState.RUNNING,
                    null, null, "[]", "{}", remote.id(), "RUNNING", 0, 0, null, null);
            DesignerSessionRow session = updateDesignerProjection(get(input.id()), DesignerSessionState.RUNNING,
                    DesignWorkflowPhase.DECOMPOSING, remote.id(), "RUNNING", input.designRevision(), 0,
                    revision.revision(), null);
            if (!consumeModelCall(session, revision, "DECOMPOSER_MODEL_CALL_LIMIT")) {
                abortQuietly(remote.id(), input.projectId());
                return appendMessage(session.id(), DesignerActor.SYSTEM,
                        "需求版本的 " + MAX_MODEL_CALLS + " 次自动模型调用预算已耗尽，任务拆解已停止。", "TERMINAL_ERROR",
                        revision.revision(), null);
            }
            openCode.promptAsync(remote, decomposerPlanningPrompt(session, project,
                    getRequirement(revision.id()), explicitRetry));
            publish(session, "STATUS", DesignerActor.DECOMPOSER, true, "",
                    "任务拆解器正在规划包边界、依赖并建立需求段覆盖映射");
            return appendMessage(session.id(), DesignerActor.SYSTEM,
                    "完整需求版本 R" + revision.revision() + " 已冻结并交给独立只读任务拆解器。",
                    "PENDING_HANDOFF", revision.revision(), null);
        } catch (SessionFailure failure) {
            failDecomposition(pending, input, failure.code(), failure.getMessage(), true);
        } catch (RuntimeException failure) {
            failDecomposition(pending, input, "OPENCODE_DECOMPOSER_HANDOFF_FAILED", failure.getMessage(), true);
        }
        return appendMessage(input.id(), DesignerActor.SYSTEM,
                "任务拆解器启动失败，需求版本仍保留，可人工重试。", "TERMINAL_ERROR",
                revision.revision(), null);
    }

    private void pollDecomposer(TaskDecompositionRow decomposition) {
        DesignerSessionRow session = get(decomposition.designerSessionId());
        DesignRequirementRevisionRow revision = getRequirement(decomposition.requirementRevisionId());
        if (!isCurrent(session, revision)) return;
        ProjectRow project = projects.get(session.projectId());
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                decomposition.externalSessionId(), Path.of(project.rootPath()));
        try {
            requireDraftUnchanged(session, revision.sourceDraftVersion());
            List<OpenCodeClient.PendingQuestion> questions = openCode.pendingQuestions(remote);
            if (!questions.isEmpty()) {
                questions.forEach(question -> { try { openCode.rejectQuestion(remote, question.id()); } catch (RuntimeException ignored) { } });
                decompositionRejected(decomposition, session, remote, "DECOMPOSER_INTERACTION_FORBIDDEN",
                        "Task Decomposer must return NEEDS_INPUT instead of asking a model-side question");
                return;
            }
            if (timedOut(decomposition.updatedAt())) {
                try { openCode.abort(remote); } catch (RuntimeException ignored) { }
                failDecomposition(decomposition, session, "OPENCODE_DECOMPOSER_TIMEOUT",
                        "Task Decomposer exceeded " + defaults.getDesignerTimeout(), true);
                return;
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.failed()) {
                failDecomposition(decomposition, session, "OPENCODE_DECOMPOSER_" + safeState(status.state()),
                        statusDetail(status), true);
            } else if (status.completed()) {
                if (StructuredModelStep.PLANNING.name().equals(decomposition.workflowStep())) {
                    handleDecompositionPlanningOutput(decomposition, session, remote,
                            openCode.sessionOutput(remote));
                } else {
                    handleDecompositionOutput(decomposition, session, remote, openCode.sessionOutput(remote));
                }
            } else if (!same(decomposition.externalSessionState(), status.state())) {
                updateDecomposition(decomposition, TaskDecompositionState.RUNNING, decomposition.resultType(),
                        decomposition.normalizedGoal(), decomposition.globalConstraintsJson(), decomposition.planJson(),
                        remote.id(), status.state(), decomposition.repairCount(), decomposition.transportRetryCount(),
                        decomposition.lastErrorCode(), decomposition.lastErrorDetail());
                publish(session, "STATUS", DesignerActor.DECOMPOSER, true, "", "任务拆解器正在生成结构化拆解计划");
            }
        } catch (ConflictException stale) {
            failDecomposition(decomposition, session, stale.code(), stale.getMessage(), false);
        } catch (SessionFailure failure) {
            failDecomposition(decomposition, session, failure.code(), failure.getMessage(), true);
        } catch (RuntimeException failure) {
            failDecomposition(decomposition, session, "OPENCODE_DECOMPOSER_STATUS_FAILED", failure.getMessage(), true);
        }
    }

    private void handleDecompositionPlanningOutput(TaskDecompositionRow input, DesignerSessionRow session,
                                                   OpenCodeClient.OpenCodeSession remote, String output) {
        DecompositionPlanEnvelope plan;
        try {
            plan = parseDecompositionPlan(output);
            validateDecompositionPlan(plan, getRequirement(input.requirementRevisionId()));
        } catch (BadRequestException invalid) {
            decompositionRejected(input, session, remote, invalid.code(), invalid.getMessage());
            return;
        }
        TaskDecompositionRow planned = updateDecomposition(input, TaskDecompositionState.RUNNING,
                null, plan.normalizedGoal(), write(plan.globalConstraints()), input.planJson(), remote.id(),
                "PLANNING_COMPLETED", input.repairCount(), input.transportRetryCount(), null, null,
                StructuredModelStep.GENERATING_JSON, write(plan));
        DesignRequirementRevisionRow revision = getRequirement(input.requirementRevisionId());
        if (!consumeModelCall(session, revision, "DECOMPOSER_MODEL_CALL_LIMIT")) return;
        try {
            openCode.promptAsync(remote, decomposerJsonPrompt(plan));
            updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                    DesignWorkflowPhase.DECOMPOSING, remote.id(), "GENERATING_JSON",
                    session.designRevision(), session.redesignCount(), revision.revision(), null);
            publish(session, "STATUS", DesignerActor.DECOMPOSER, true, "",
                    "拆解规划与需求覆盖映射已冻结，正在生成最终拆解 JSON");
        } catch (RuntimeException failure) {
            failDecomposition(planned, session, "OPENCODE_DECOMPOSER_JSON_HANDOFF_FAILED",
                    failure.getMessage(), true);
        }
    }

    private void handleDecompositionOutput(TaskDecompositionRow input, DesignerSessionRow session,
                                           OpenCodeClient.OpenCodeSession remote, String output) {
        TaskDecompositionRow validating = updateDecomposition(input, TaskDecompositionState.VALIDATING,
                input.resultType(), input.normalizedGoal(), input.globalConstraintsJson(), input.planJson(),
                remote.id(), "COMPLETED", input.repairCount(), input.transportRetryCount(), null, null);
        DesignerSessionRow validatingSession = updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                DesignWorkflowPhase.VALIDATING_DECOMPOSITION, remote.id(), "COMPLETED",
                session.designRevision(), 0, session.currentRequirementRevision(), null);
        DecompositionEnvelope envelope;
        try {
            envelope = parseDecomposition(output);
            if (!blank(input.planningJson())) {
                validateDecompositionAgainstPlan(readDecompositionPlan(input.planningJson()), envelope);
            }
            validateDecomposition(envelope, getRequirement(input.requirementRevisionId()));
        } catch (BadRequestException invalid) {
            decompositionRejected(validating, validatingSession, remote, invalid.code(), invalid.getMessage());
            return;
        }
        if ("NEEDS_INPUT".equals(envelope.status())) {
            updateDecomposition(validating, TaskDecompositionState.NEEDS_INPUT, envelope.status(),
                    envelope.normalizedGoal(), write(envelope.globalConstraints()), write(envelope), remote.id(),
                    "COMPLETED", validating.repairCount(), validating.transportRetryCount(), "DECOMPOSITION_NEEDS_INPUT",
                    summarizeInputGaps(envelope.designGaps()), StructuredModelStep.FINAL_JSON,
                    validating.planningJson());
            appendMessage(session.id(), DesignerActor.DECOMPOSER,
                    "拆解前仍需补充需求：\n" + summarizeInputGaps(envelope.designGaps()), "NEEDS_INPUT",
                    session.currentRequirementRevision(), null);
            waitForDesignInput(session, getRequirement(input.requirementRevisionId()), null,
                    "DECOMPOSITION_NEEDS_INPUT", summarizeInputGaps(envelope.designGaps()));
            return;
        }
        if ("MULTI_TASK_REQUIRED".equals(envelope.status())) {
            String detail = blank(envelope.reason()) ? "需求包含多个项目根、独立发布边界或超过六个工作包" : envelope.reason();
            updateDecomposition(validating, TaskDecompositionState.MULTI_TASK_REQUIRED, envelope.status(),
                    envelope.normalizedGoal(), write(envelope.globalConstraints()), write(envelope), remote.id(),
                    "COMPLETED", validating.repairCount(), validating.transportRetryCount(), "MULTI_TASK_REQUIRED", detail,
                    StructuredModelStep.FINAL_JSON, validating.planningJson());
            appendMessage(session.id(), DesignerActor.DECOMPOSER,
                    "该需求超出单个 Task 的安全边界：" + detail + "。系统不会自动创建子 Task。",
                    "MULTI_TASK_REQUIRED", session.currentRequirementRevision(), null);
            waitForDesignInput(session, getRequirement(input.requirementRevisionId()), null,
                    "MULTI_TASK_REQUIRED", detail);
            return;
        }
        TaskDecompositionRow completed = updateDecomposition(validating, TaskDecompositionState.COMPLETED,
                envelope.status(), envelope.normalizedGoal(), write(envelope.globalConstraints()), write(envelope),
                remote.id(), "COMPLETED", validating.repairCount(), validating.transportRetryCount(), null, null,
                StructuredModelStep.FINAL_JSON, validating.planningJson());
        List<DesignWorkPackageRow> packages = persistWorkPackages(validatingSession, completed, envelope);
        appendMessage(session.id(), DesignerActor.DECOMPOSER,
                "拆解校验通过：" + ("DIRECT_DESIGN".equals(envelope.status()) ? "采用单工作包直达设计。"
                        : "形成 " + packages.size() + " 个依赖有序的纵向工作包。"),
                "COMPLETED", session.currentRequirementRevision(), null);
        dispatchPackageDesigner(get(session.id()), packages.getFirst(), null, false);
    }

    private void decompositionRejected(TaskDecompositionRow input, DesignerSessionRow session,
                                       OpenCodeClient.OpenCodeSession remote, String code, String detail) {
        TaskDecompositionRow decomposition = getDecomposition(input.id());
        appendMessage(session.id(), DesignerActor.VALIDATOR,
                "拆解计划校验未通过（" + code + "）：" + safeMessage(detail), "RETRYABLE_ERROR",
                session.currentRequirementRevision(), null);
        boolean planning = StructuredModelStep.PLANNING.name().equals(decomposition.workflowStep());
        int repairsUsed = planning ? decomposition.planningRepairCount() : decomposition.repairCount();
        if (repairsUsed >= MAX_DECOMPOSER_REPAIRS) {
            updateDecomposition(decomposition, TaskDecompositionState.SESSION_ERROR,
                    decomposition.resultType(), decomposition.normalizedGoal(), decomposition.globalConstraintsJson(),
                    decomposition.planJson(), remote.id(), "FAILED", decomposition.repairCount(),
                    decomposition.transportRetryCount(), "DECOMPOSER_RETRY_EXHAUSTED", safeMessage(detail));
            waitForDesignInput(session, currentRequirement(session.id()), null,
                    "DECOMPOSER_RETRY_EXHAUSTED", detail);
            return;
        }
        int repair = repairsUsed + 1;
        TaskDecompositionRow repairing = updateDecomposition(decomposition, TaskDecompositionState.RUNNING,
                decomposition.resultType(), decomposition.normalizedGoal(), decomposition.globalConstraintsJson(),
                decomposition.planJson(), remote.id(), "REPAIRING_" + repair,
                planning ? decomposition.repairCount() : repair,
                decomposition.transportRetryCount(), code, safeMessage(detail),
                planning ? StructuredModelStep.PLANNING : StructuredModelStep.REPAIRING_JSON,
                decomposition.planningJson(), planning ? repair : decomposition.planningRepairCount());
        DesignRequirementRevisionRow revision = getRequirement(decomposition.requirementRevisionId());
        if (!consumeModelCall(session, revision, "DECOMPOSER_MODEL_CALL_LIMIT")) return;
        try {
            openCode.promptAsync(remote, planning
                    ? decompositionPlanningRepairPrompt(repairing, revision, code, detail)
                    : decompositionRepairPrompt(repairing, code, detail));
            updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                    DesignWorkflowPhase.DECOMPOSING, remote.id(), "REPAIRING_" + repair,
                    session.designRevision(), 0, session.currentRequirementRevision(), null);
            publish(session, "STATUS", DesignerActor.DECOMPOSER, true, "",
                    "任务拆解器正在进行第 " + repair + "/" + MAX_DECOMPOSER_REPAIRS + " 次结构修复");
        } catch (RuntimeException failure) {
            failDecomposition(repairing, session, "OPENCODE_DECOMPOSER_REPAIR_FAILED", failure.getMessage(), true);
        }
    }

    private List<DesignWorkPackageRow> persistWorkPackages(DesignerSessionRow session,
                                                           TaskDecompositionRow decomposition,
                                                           DecompositionEnvelope envelope) {
        String now = now();
        List<DesignWorkPackageRow> result = new ArrayList<>();
        for (int index = 0; index < envelope.workPackages().size(); index++) {
            DecomposedWorkPackage item = envelope.workPackages().get(index);
            DesignWorkPackageRow row = new DesignWorkPackageRow(UUID.randomUUID().toString(), session.id(),
                    decomposition.requirementRevisionId(), decomposition.id(), item.id(), index,
                    item.title(), item.objective(), write(item.scopeIn()), write(item.scopeOut()),
                    write(item.dependencies()), write(item.deliverables()), write(item.acceptanceIntent()),
                    write(item.requirementRefs()), DesignWorkPackageState.PENDING.name(), null, "PENDING",
                    null, 0, 0, 0, null, null, null, null, now, now, 0);
            lifecycle.create(workPackageSubject(row, session.projectId()), row.state(), Map.of("packageId", item.id()),
                    () -> mapper.insertDesignWorkPackage(row),
                    () -> new ConflictException("DESIGN_WORK_PACKAGE_CREATE_CONFLICT",
                            "Design work package could not be persisted: " + item.id()));
            result.add(getWorkPackage(row.id()));
        }
        return List.copyOf(result);
    }

    private void dispatchPackageDesigner(DesignerSessionRow session, DesignWorkPackageRow input,
                                         String replacementPrompt, boolean redesign) {
        DesignRequirementRevisionRow revision = getRequirement(input.requirementRevisionId());
        requireDraftUnchanged(session, revision.sourceDraftVersion());
        if (!isCurrent(session, revision)) throw new ConflictException("REQUIREMENT_REVISION_STALE",
                "This work package belongs to a superseded requirement revision");
        if (redesign && input.redesignCount() >= MAX_AUTOMATIC_REDESIGNS) {
            waitForDesignInput(session, revision, input, "DESIGN_RETRY_EXHAUSTED",
                    "The work package already used its one complete redesign");
            return;
        }
        if (!openCode.healthy()) {
            waitForDesignInput(session, revision, input, "OPENCODE_DESIGNER_UNAVAILABLE",
                    "OpenCode Designer runtime is unavailable");
            return;
        }
        ProjectRow project = projects.get(session.projectId());
        try {
            OpenCodeClient.OpenCodeSession remote = openCode.createReadOnlySession(Path.of(project.rootPath()),
                    "OpenCode Loopper Designer " + input.packageId() + " (READ_ONLY)", configuredModel());
            int redesignCount = redesign ? input.redesignCount() + 1 : input.redesignCount();
            DesignWorkPackageRow designing = updateWorkPackage(input, DesignWorkPackageState.DESIGNING,
                    remote.id(), "RUNNING", input.designMessageId(), input.designRevision(), redesignCount,
                    input.designerTransportRetryCount(), input.compilerSummary(), input.handoffSummary(), null, null);
            DesignWorkflowPhase phase = redesign ? DesignWorkflowPhase.REDESIGNING : DesignWorkflowPhase.DESIGNING;
            DesignerSessionRow running = updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                    phase, remote.id(), "RUNNING", session.designRevision(), redesignCount,
                    revision.revision(), input.packageId());
            if (!consumeModelCall(running, revision, "WORK_PACKAGE_MODEL_CALL_LIMIT")) {
                abortQuietly(remote.id(), session.projectId());
                return;
            }
            openCode.promptAsync(remote, replacementPrompt == null
                    ? packageDesignerPrompt(running, project, revision, designing)
                    : replacementPrompt + "\n\n" + packageDesignerPrompt(running, project, revision, designing));
            publish(running, "STATUS", DesignerActor.DESIGNER, true, "",
                    input.packageId() + " " + (redesign ? "正在生成完整替代稿" : "正在生成独立 Markdown 设计稿"));
            appendMessage(session.id(), DesignerActor.SYSTEM,
                    input.packageId() + " 已交给全新的只读设计师 Session。", "PENDING_HANDOFF",
                    revision.revision(), input.packageId());
        } catch (SessionFailure failure) {
            failPackageDesigner(input, session, failure.code(), failure.getMessage(), true);
        } catch (RuntimeException failure) {
            failPackageDesigner(input, session, "OPENCODE_PACKAGE_DESIGNER_HANDOFF_FAILED",
                    failure.getMessage(), true);
        }
    }

    private void pollWorkPackageDesigner(DesignWorkPackageRow input) {
        DesignWorkPackageRow workPackage = getWorkPackage(input.id());
        DesignerSessionRow session = get(workPackage.designerSessionId());
        DesignRequirementRevisionRow revision = getRequirement(workPackage.requirementRevisionId());
        if (!isCurrent(session, revision)) return;
        ProjectRow project = projects.get(session.projectId());
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                workPackage.designerExternalSessionId(), Path.of(project.rootPath()));
        try {
            requireDraftUnchanged(session, revision.sourceDraftVersion());
            List<OpenCodeClient.PendingQuestion> pending = openCode.pendingQuestions(remote);
            if (!pending.isEmpty()) {
                // Designer is the only model role allowed to request user input.
                DesignWorkPackageRow waiting = updateWorkPackage(workPackage, DesignWorkPackageState.DESIGNING,
                        remote.id(), "WAITING_INPUT", workPackage.designMessageId(), workPackage.designRevision(),
                        workPackage.redesignCount(), workPackage.designerTransportRetryCount(),
                        workPackage.compilerSummary(), workPackage.handoffSummary(), null, null);
                updateDesignerProjection(session, DesignerSessionState.RUNNING,
                        DesignWorkflowPhase.valueOf(session.workflowPhase()), remote.id(), "WAITING_INPUT",
                        session.designRevision(), session.redesignCount(), revision.revision(), waiting.packageId());
                publish(session, "STATUS", DesignerActor.DESIGNER, true, openCode.sessionLiveOutput(remote),
                        workPackage.packageId() + " 的设计师正在等待你的回答");
                return;
            }
            if (timedOut(workPackage.updatedAt())) {
                try { openCode.abort(remote); } catch (RuntimeException ignored) { }
                failPackageDesigner(workPackage, session, "OPENCODE_PACKAGE_DESIGNER_TIMEOUT",
                        "Package Designer exceeded " + defaults.getDesignerTimeout(), true);
                return;
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.failed()) {
                failPackageDesigner(workPackage, session,
                        "OPENCODE_PACKAGE_DESIGNER_" + safeState(status.state()), statusDetail(status), true);
            } else if (status.completed()) {
                String markdown = designerMarkdown(openCode.sessionOutput(remote));
                if (markdown.isBlank()) {
                    failPackageDesigner(workPackage, session, "DESIGN_OUTPUT_MISSING",
                            "Package Designer completed without Markdown", false);
                    return;
                }
                if (markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_FROZEN_DESIGN_LENGTH) {
                    failPackageDesigner(workPackage, session, "WORK_PACKAGE_DESIGN_TOO_LARGE",
                            "Package design exceeds 24 KiB UTF-8", false);
                    return;
                }
                DesignerMessageRow source = appendMessage(session.id(), DesignerActor.DESIGNER, markdown,
                        "PERSISTED", revision.revision(), workPackage.packageId());
                DesignWorkPackageRow compiling = updateWorkPackage(workPackage, DesignWorkPackageState.COMPILING,
                        remote.id(), "COMPLETED", source.id(), workPackage.designRevision() + 1,
                        workPackage.redesignCount(), workPackage.designerTransportRetryCount(), null, null, null, null);
                DesignerSessionRow compilerSession = updateDesignerProjection(get(session.id()),
                        DesignerSessionState.RUNNING, DesignWorkflowPhase.COMPILING, remote.id(), "COMPLETED",
                        session.designRevision() + 1, workPackage.redesignCount(), revision.revision(),
                        workPackage.packageId());
                publish(compilerSession, "STATUS", DesignerActor.COMPILER, true, "",
                        workPackage.packageId() + " 设计稿已冻结，正在启动全新的只读规范编译器");
                startCompilation(compilerSession, compiling, source);
            } else if (!same(workPackage.designerExternalSessionState(), status.state())) {
                updateWorkPackage(workPackage, DesignWorkPackageState.DESIGNING, remote.id(), status.state(),
                        workPackage.designMessageId(), workPackage.designRevision(), workPackage.redesignCount(),
                        workPackage.designerTransportRetryCount(), workPackage.compilerSummary(),
                        workPackage.handoffSummary(), workPackage.lastErrorCode(), workPackage.lastErrorDetail());
                publish(session, "PARTIAL", DesignerActor.DESIGNER, true,
                        designerMarkdown(openCode.sessionLiveOutput(remote)),
                        workPackage.packageId() + " 正在接收设计师 Markdown");
            }
        } catch (ConflictException stale) {
            failPackageDesigner(workPackage, session, stale.code(), stale.getMessage(), false);
        } catch (SessionFailure failure) {
            failPackageDesigner(workPackage, session, failure.code(), failure.getMessage(), true);
        } catch (RuntimeException failure) {
            failPackageDesigner(workPackage, session, "OPENCODE_PACKAGE_DESIGNER_STATUS_FAILED",
                    failure.getMessage(), true);
        }
    }

    private void startCompilation(DesignerSessionRow session, DesignWorkPackageRow workPackage,
                                  DesignerMessageRow source) {
        DesignRequirementRevisionRow revision = getRequirement(workPackage.requirementRevisionId());
        requireDraftUnchanged(session, revision.sourceDraftVersion());
        String now = now();
        LoopSpecCompilationRow pending = new LoopSpecCompilationRow(UUID.randomUUID().toString(), session.id(),
                workPackage.designRevision(), LoopSpecCompilationState.PENDING_HANDOFF.name(), null, "PENDING", 0,
                source.id(), revision.sourceDraftVersion(), null, null, now, now, 0,
                workPackage.packageId(), 0, null, StructuredModelStep.PLANNING.name(), null, 0);
        lifecycle.create(compilationSubject(pending, session.projectId()), pending.state(),
                Map.of("workPackageId", workPackage.packageId()), () -> mapper.insertLoopSpecCompilation(pending),
                () -> new ConflictException("LOOPSPEC_COMPILATION_CREATE_CONFLICT",
                        "Work-package compilation could not be created"));
        ProjectRow project = projects.get(session.projectId());
        try {
            OpenCodeClient.OpenCodeSession remote = openCode.createReadOnlySession(Path.of(project.rootPath()),
                    "OpenCode Loopper LoopSpec Compiler " + workPackage.packageId() + " (READ_ONLY)", configuredModel());
            LoopSpecCompilationRow running = updateCompilation(pending, LoopSpecCompilationState.RUNNING,
                    remote.id(), "RUNNING", 0, null, null, session.projectId(), null);
            if (!consumeModelCall(session, revision, "WORK_PACKAGE_MODEL_CALL_LIMIT")) {
                abortQuietly(remote.id(), session.projectId());
                return;
            }
            openCode.promptAsync(remote, packageCompilerPlanningPrompt(project, revision, workPackage,
                    source.content()));
            publish(session, "STATUS", DesignerActor.COMPILER, true, "",
                    workPackage.packageId() + " 规范编译器正在规划 Stage 与验收证据映射");
        } catch (SessionFailure failure) {
            failPackageCompilation(pending, session, failure.code(), failure.getMessage(), true);
        } catch (RuntimeException failure) {
            failPackageCompilation(pending, session, "OPENCODE_COMPILER_HANDOFF_FAILED", failure.getMessage(), true);
        }
    }

    private DesignerMessageRow dispatchDesigner(DesignerSessionRow input, String request,
                                                  DesignWorkflowPhase phase, int redesignCount) {
        if (!openCode.healthy()) {
            DesignerSessionRow pending = updateDesignerProjection(input, DesignerSessionState.PENDING_HANDOFF,
                    phase, null, "UNAVAILABLE", input.designRevision(), redesignCount);
            DesignerMessageRow message = appendMessage(pending.id(), DesignerActor.SYSTEM,
                    "SYSTEM_ERROR[SESSION] OPENCODE_DESIGNER_UNAVAILABLE: OpenCode Designer runtime is unavailable; "
                            + "the request remains pending and no task was changed.", "PENDING_HANDOFF");
            publish(pending, "ERROR", DesignerActor.SYSTEM, false, "", message.content());
            return message;
        }
        ProjectRow project = projects.get(input.projectId());
        DesignerSessionRow current = input;
        try {
            OpenCodeClient.OpenCodeSession remote = reusableDesigner(input)
                    ? designerRemote(input)
                    : openCode.createReadOnlySession(Path.of(project.rootPath()),
                    "OpenCode Loopper Designer (READ_ONLY)", configuredModel());
            current = updateDesignerProjection(input, DesignerSessionState.RUNNING, phase,
                    remote.id(), "CREATED", input.designRevision(), redesignCount);
            publish(current, "STATUS", DesignerActor.DESIGNER, true, "",
                    phase == DesignWorkflowPhase.REDESIGNING ? "设计师正在生成完整替代稿" : "设计师正在生成 Markdown 设计稿");
            openCode.promptAsync(remote, phase == DesignWorkflowPhase.REDESIGNING
                    ? request : designerPrompt(current, project, request));
            current = updateDesignerProjection(current, DesignerSessionState.RUNNING, phase,
                    remote.id(), "RUNNING", current.designRevision(), redesignCount);
            return appendMessage(current.id(), DesignerActor.SYSTEM,
                    "请求已交给只读设计师；只有模型的实际 Markdown 输出会作为设计稿保存。",
                    "PENDING_HANDOFF");
        } catch (SessionFailure failure) {
            return failWorkflow(current, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return failWorkflow(current, "OPENCODE_DESIGNER_HANDOFF_FAILED", failure.getMessage());
        }
    }

    private void pollDesigner(DesignerSessionRow session) {
        OpenCodeClient.OpenCodeSession remote = designerRemote(session);
        try {
            List<OpenCodeClient.PendingQuestion> pending = openCode.pendingQuestions(remote);
            if (!pending.isEmpty()) {
                DesignerSessionRow current = same(session.externalSessionState(), "WAITING_INPUT") ? session
                        : updateDesignerProjection(session, DesignerSessionState.RUNNING,
                        DesignWorkflowPhase.valueOf(session.workflowPhase()), remote.id(), "WAITING_INPUT",
                        session.designRevision(), session.redesignCount());
                publish(current, "STATUS", DesignerActor.DESIGNER, true,
                        openCode.sessionLiveOutput(remote), "设计师正在等待你的回答");
                return;
            }
            if (timedOut(session.updatedAt())) {
                try { openCode.abort(remote); } catch (RuntimeException ignored) { }
                failWorkflow(session, "OPENCODE_DESIGNER_TIMEOUT", "Designer exceeded " + defaults.getDesignerTimeout());
                return;
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.failed()) {
                failWorkflow(session, "OPENCODE_DESIGNER_" + safeState(status.state()), statusDetail(status));
            } else if (status.completed()) {
                String output = openCode.sessionOutput(remote);
                String markdown = designerMarkdown(output);
                if (markdown.isBlank()) {
                    failWorkflow(session, "DESIGN_OUTPUT_MISSING", "Designer completed without a Markdown design");
                    return;
                }
                DesignerMessageRow source = appendMessage(session.id(), DesignerActor.DESIGNER, markdown, "PERSISTED");
                DesignerSessionRow compiling = updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                        DesignWorkflowPhase.COMPILING, remote.id(), "COMPLETED",
                        session.designRevision() + 1, session.redesignCount());
                publish(compiling, "STATUS", DesignerActor.COMPILER, true, "",
                        "设计稿已冻结，正在启动独立规范编译器");
                startCompilation(compiling, source);
            } else {
                DesignerSessionRow current = same(session.externalSessionState(), status.state()) ? session
                        : updateDesignerProjection(session, DesignerSessionState.RUNNING,
                        DesignWorkflowPhase.valueOf(session.workflowPhase()), remote.id(), status.state(),
                        session.designRevision(), session.redesignCount());
                publish(current, "PARTIAL", DesignerActor.DESIGNER, true,
                        designerMarkdown(openCode.sessionLiveOutput(remote)), "正在接收设计师 Markdown");
            }
        } catch (SessionFailure failure) {
            failWorkflow(session, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            failWorkflow(session, "OPENCODE_DESIGNER_STATUS_FAILED", failure.getMessage());
        }
    }

    private void startCompilation(DesignerSessionRow session, DesignerMessageRow source) {
        requireBoundDraft(session);
        LoopDraftRow draft = drafts.get(session.loopDraftId());
        if (source == null || source.actor() == null || !DesignerActor.DESIGNER.name().equals(source.actor())) {
            failWorkflow(session, "DESIGN_SOURCE_MISSING", "No frozen Designer message is available for compilation");
            return;
        }
        String now = now();
        LoopSpecCompilationRow pending = new LoopSpecCompilationRow(UUID.randomUUID().toString(), session.id(),
                session.designRevision(), LoopSpecCompilationState.PENDING_HANDOFF.name(), null, "PENDING", 0,
                source.id(), draft.version(), null, null, now, now, 0);
        lifecycle.create(compilationSubject(pending, session.projectId()), pending.state(), java.util.Map.of(),
                () -> mapper.insertLoopSpecCompilation(pending),
                () -> new ConflictException("LOOPSPEC_COMPILATION_CREATE_CONFLICT",
                        "LoopSpec compilation could not be created"));
        try {
            ProjectRow project = projects.get(session.projectId());
            OpenCodeClient.OpenCodeSession remote = openCode.createReadOnlySession(Path.of(project.rootPath()),
                    "OpenCode Loopper LoopSpec Compiler (READ_ONLY)", configuredModel());
            LoopSpecCompilationRow running = updateCompilation(pending, LoopSpecCompilationState.RUNNING,
                    remote.id(), "RUNNING", 0, null, null, session.projectId());
            openCode.promptAsync(remote, compilerPrompt(session, project, draft, source.content()));
            publish(session, "STATUS", DesignerActor.COMPILER, true, "",
                    "规范编译器已连接；原始 JSON 只会进入 Review Gate");
        } catch (SessionFailure failure) {
            failCompilation(pending, session, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            failCompilation(pending, session, "OPENCODE_COMPILER_HANDOFF_FAILED", failure.getMessage());
        }
    }

    private void pollCompiler(LoopSpecCompilationRow compilation) {
        DesignerSessionRow session = get(compilation.designerSessionId());
        ProjectRow project = projects.get(session.projectId());
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                compilation.externalSessionId(), Path.of(project.rootPath()));
        try {
            if (!blank(compilation.workPackageId())) {
                requireDraftUnchanged(session, currentRequirement(session.id()).sourceDraftVersion());
            }
            List<OpenCodeClient.PendingQuestion> questions = openCode.pendingQuestions(remote);
            if (!questions.isEmpty()) {
                for (OpenCodeClient.PendingQuestion question : questions) {
                    try { openCode.rejectQuestion(remote, question.id()); } catch (RuntimeException ignored) { }
                }
                compilerRejected(compilation, session, remote, "COMPILER_INTERACTION_FORBIDDEN",
                        "LoopSpec Compiler must resolve the frozen design without asking questions");
                return;
            }
            if (timedOut(compilation.updatedAt())) {
                try { openCode.abort(remote); } catch (RuntimeException ignored) { }
                failCompilation(compilation, session, "OPENCODE_COMPILER_TIMEOUT",
                        "LoopSpec Compiler exceeded " + defaults.getDesignerTimeout());
                return;
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.failed()) {
                failCompilation(compilation, session, "OPENCODE_COMPILER_" + safeState(status.state()), statusDetail(status));
            } else if (status.completed()) {
                if (!blank(compilation.workPackageId())
                        && StructuredModelStep.PLANNING.name().equals(compilation.workflowStep())) {
                    handlePackageCompilationPlanningOutput(compilation, session, remote,
                            openCode.sessionOutput(remote));
                } else {
                    handleCompilerOutput(compilation, session, remote, openCode.sessionOutput(remote));
                }
            } else if (!same(compilation.externalSessionState(), status.state())) {
                updateCompilation(compilation, LoopSpecCompilationState.RUNNING, remote.id(), status.state(),
                        compilation.repairCount(), compilation.lastErrorCode(), compilation.lastErrorDetail(),
                        session.projectId());
                publish(session, "STATUS", DesignerActor.COMPILER, true, "", "规范编译器正在生成结构化结果");
            }
        } catch (ConflictException stale) {
            failPackageCompilation(compilation, session, stale.code(), stale.getMessage(), false);
        } catch (SessionFailure failure) {
            failCompilation(compilation, session, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            failCompilation(compilation, session, "OPENCODE_COMPILER_STATUS_FAILED", failure.getMessage());
        }
    }

    private void handlePackageCompilationPlanningOutput(LoopSpecCompilationRow input,
                                                        DesignerSessionRow session,
                                                        OpenCodeClient.OpenCodeSession remote,
                                                        String output) {
        DesignWorkPackageRow workPackage = requireCurrentPackage(session, input.workPackageId());
        String design = designMessage(workPackage).content();
        PackageCompilationPlanEnvelope plan;
        try {
            plan = parsePackageCompilationPlan(output);
            validatePackageCompilationPlan(workPackage, design, plan,
                    "v2".equalsIgnoreCase(drafts.spec(drafts.get(session.loopDraftId())).schemaVersion()));
        } catch (BadRequestException invalid) {
            packageCompilerRejected(input, session, workPackage, remote, invalid.code(), invalid.getMessage());
            return;
        }
        LoopSpecCompilationRow planned = updateCompilation(input, LoopSpecCompilationState.RUNNING,
                remote.id(), "PLANNING_COMPLETED", input.repairCount(), null, null, session.projectId(),
                input.compiledPackageJson(), StructuredModelStep.GENERATING_JSON, write(plan));
        DesignRequirementRevisionRow revision = currentRequirement(session.id());
        if (!consumeModelCall(session, revision, "WORK_PACKAGE_MODEL_CALL_LIMIT")) return;
        try {
            openCode.promptAsync(remote, packageCompilerJsonPrompt(session, drafts.get(session.loopDraftId()),
                    workPackage, plan));
            updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING, DesignWorkflowPhase.COMPILING,
                    session.externalSessionId(), "GENERATING_JSON", session.designRevision(),
                    session.redesignCount(), revision.revision(), workPackage.packageId());
            publish(session, "STATUS", DesignerActor.COMPILER, true, "",
                    workPackage.packageId() + " Stage规划与验收证据映射已冻结，正在生成 CompiledPackage JSON");
        } catch (RuntimeException failure) {
            failPackageCompilation(planned, session, "OPENCODE_COMPILER_JSON_HANDOFF_FAILED",
                    failure.getMessage(), true);
        }
    }

    private void handleCompilerOutput(LoopSpecCompilationRow compilation, DesignerSessionRow session,
                                      OpenCodeClient.OpenCodeSession remote, String output) {
        if (!blank(compilation.workPackageId())) {
            handlePackageCompilerOutput(compilation, session, remote, output);
            return;
        }
        CompilationEnvelope envelope;
        try {
            envelope = parseCompilation(output);
        } catch (BadRequestException invalid) {
            compilerRejected(compilation, session, remote, invalid.code(), invalid.getMessage());
            return;
        }
        if ("DESIGN_INCOMPLETE".equals(envelope.status())) {
            List<DesignGap> gaps;
            try { gaps = validateDesignGaps(envelope.designGaps()); }
            catch (BadRequestException invalid) {
                compilerRejected(compilation, session, remote, invalid.code(), invalid.getMessage());
                return;
            }
            LoopSpecCompilationRow incomplete = updateCompilation(compilation,
                    LoopSpecCompilationState.DESIGN_INCOMPLETE, remote.id(), "COMPLETED",
                    compilation.repairCount(), "DESIGN_INCOMPLETE", summarizeGaps(gaps), session.projectId());
            appendMessage(session.id(), DesignerActor.COMPILER,
                    "设计稿暂不可编译：\n" + summarizeGaps(gaps), "DESIGN_INCOMPLETE");
            if (session.redesignCount() < MAX_AUTOMATIC_REDESIGNS) {
                dispatchDesigner(get(session.id()), redesignPrompt(summarizeGaps(gaps)),
                        DesignWorkflowPhase.REDESIGNING, session.redesignCount() + 1);
            } else {
                failWorkflow(get(session.id()), "DESIGN_RETRY_EXHAUSTED",
                        "Designer automatic redesign limit was reached: " + incomplete.lastErrorDetail());
            }
            return;
        }
        if (!"COMPILED".equals(envelope.status())) {
            compilerRejected(compilation, session, remote, "COMPILER_STATUS_INVALID",
                    "Compiler status must be COMPILED or DESIGN_INCOMPLETE");
            return;
        }
        try {
            DesignerSessionRow validating = updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                    DesignWorkflowPhase.VALIDATING, session.externalSessionId(), session.externalSessionState(),
                    session.designRevision(), session.redesignCount());
            DesignerMessageRow source = mapper.listDesignerMessages(session.id()).stream()
                    .filter(message -> message.id().equals(compilation.sourceDesignMessageId())).findFirst()
                    .orElseThrow(() -> new ConflictException("DESIGN_SOURCE_MISSING",
                            "The frozen Designer source message no longer exists"));
            requireProject(validating, envelope.loopSpec());
            validateTraceability(source.content(), envelope.loopSpec(), envelope.criterionSources());
            drafts.updateAtVersion(validating.loopDraftId(), envelope.loopSpec(), compilation.sourceDraftVersion());
            String summary = blank(envelope.summary()) ? "LoopSpec 已从当前冻结设计编译完成。"
                    : bounded(envelope.summary(), 1_000);
            appendMessage(session.id(), DesignerActor.COMPILER, summary, "COMPILED");
            appendMessage(session.id(), DesignerActor.VALIDATOR,
                    "确定性校验通过：字段、验证器、验收覆盖、来源追踪及 Java 单元测试规则均满足。",
                    "PASS");
            updateCompilation(getCompilation(compilation.id()), LoopSpecCompilationState.COMPLETED,
                    remote.id(), "COMPLETED", compilation.repairCount(), null, null, session.projectId());
            DesignerSessionRow completed = updateDesignerProjection(get(session.id()), DesignerSessionState.COMPLETED,
                    DesignWorkflowPhase.COMPLETED, session.externalSessionId(), session.externalSessionState(),
                    session.designRevision(), session.redesignCount());
            publish(completed, "COMPLETED", DesignerActor.VALIDATOR, true, "",
                    "LoopSpec 已通过确定性校验并同步到 Review Gate");
        } catch (BadRequestException invalid) {
            compilerRejected(getCompilation(compilation.id()), get(session.id()), remote, invalid.code(), invalid.getMessage());
        } catch (ConflictException stale) {
            failCompilation(getCompilation(compilation.id()), get(session.id()), stale.code(), stale.getMessage());
        }
    }

    private void handlePackageCompilerOutput(LoopSpecCompilationRow input, DesignerSessionRow session,
                                             OpenCodeClient.OpenCodeSession remote, String output) {
        LoopSpecCompilationRow compilation = getCompilation(input.id());
        DesignWorkPackageRow workPackage = requireCurrentPackage(session, compilation.workPackageId());
        PackageCompilationEnvelope envelope;
        try {
            envelope = parsePackageCompilation(output);
            if (!blank(compilation.planningJson())) {
                validatePackageCompilationAgainstPlan(readPackageCompilationPlan(compilation.planningJson()), envelope);
            }
        } catch (BadRequestException invalid) {
            packageCompilerRejected(compilation, session, workPackage, remote, invalid.code(), invalid.getMessage());
            return;
        }
        if ("DESIGN_INCOMPLETE".equals(envelope.status())) {
            List<DesignGap> gaps;
            try { gaps = validateDesignGaps(envelope.designGaps()); }
            catch (BadRequestException invalid) {
                packageCompilerRejected(compilation, session, workPackage, remote, invalid.code(), invalid.getMessage());
                return;
            }
            updateCompilation(compilation, LoopSpecCompilationState.DESIGN_INCOMPLETE, remote.id(), "COMPLETED",
                    compilation.repairCount(), "DESIGN_INCOMPLETE", summarizeGaps(gaps), session.projectId(), null,
                    StructuredModelStep.FINAL_JSON, compilation.planningJson());
            DesignWorkPackageRow waiting = updateWorkPackage(workPackage, DesignWorkPackageState.WAITING_INPUT,
                    workPackage.designerExternalSessionId(), workPackage.designerExternalSessionState(),
                    workPackage.designMessageId(), workPackage.designRevision(), workPackage.redesignCount(),
                    workPackage.designerTransportRetryCount(), workPackage.compilerSummary(), workPackage.handoffSummary(),
                    "DESIGN_INCOMPLETE", summarizeGaps(gaps));
            appendMessage(session.id(), DesignerActor.COMPILER,
                    workPackage.packageId() + " 设计稿暂不可编译：\n" + summarizeGaps(gaps),
                    "DESIGN_INCOMPLETE", session.currentRequirementRevision(), workPackage.packageId());
            if (workPackage.redesignCount() < MAX_AUTOMATIC_REDESIGNS) {
                dispatchPackageDesigner(get(session.id()), waiting, redesignPrompt(summarizeGaps(gaps)), true);
            } else {
                waitForDesignInput(session, currentRequirement(session.id()), waiting,
                        "DESIGN_RETRY_EXHAUSTED", summarizeGaps(gaps));
            }
            return;
        }
        if (!"COMPILED".equals(envelope.status())) {
            packageCompilerRejected(compilation, session, workPackage, remote, "COMPILER_STATUS_INVALID",
                    "Compiler status must be COMPILED or DESIGN_INCOMPLETE");
            return;
        }
        try {
            DesignerMessageRow source = designMessage(workPackage);
            DesignWorkPackageRow validatingPackage = updateWorkPackage(workPackage,
                    DesignWorkPackageState.VALIDATING, workPackage.designerExternalSessionId(),
                    workPackage.designerExternalSessionState(), workPackage.designMessageId(),
                    workPackage.designRevision(), workPackage.redesignCount(),
                    workPackage.designerTransportRetryCount(), workPackage.compilerSummary(),
                    workPackage.handoffSummary(), null, null);
            DesignerSessionRow validating = updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                    DesignWorkflowPhase.VALIDATING, session.externalSessionId(), session.externalSessionState(),
                    session.designRevision(), session.redesignCount(), session.currentRequirementRevision(),
                    workPackage.packageId());
            validateCompiledPackage(validating, validatingPackage, source.content(), envelope);
            String summary = blank(envelope.summary()) ? workPackage.packageId() + " 已完成结构化编译。"
                    : bounded(envelope.summary(), 1_000);
            String handoff = boundedUtf8(envelope.handoffSummary(), MAX_HANDOFF_SUMMARY_LENGTH);
            updateCompilation(compilation, LoopSpecCompilationState.COMPLETED, remote.id(), "COMPLETED",
                    compilation.repairCount(), null, null, session.projectId(), write(envelope),
                    StructuredModelStep.FINAL_JSON, compilation.planningJson());
            DesignWorkPackageRow completed = updateWorkPackage(getWorkPackage(workPackage.id()),
                    DesignWorkPackageState.COMPLETED, workPackage.designerExternalSessionId(),
                    workPackage.designerExternalSessionState(), workPackage.designMessageId(),
                    workPackage.designRevision(), workPackage.redesignCount(),
                    workPackage.designerTransportRetryCount(), summary, handoff, null, null);
            appendMessage(session.id(), DesignerActor.COMPILER, workPackage.packageId() + "：" + summary,
                    "COMPILED", session.currentRequirementRevision(), workPackage.packageId());
            appendMessage(session.id(), DesignerActor.VALIDATOR,
                    workPackage.packageId() + " 确定性校验通过：1–3 个 Stage、验收来源、验证器覆盖及 Java 单测门禁均满足。",
                    "PASS", session.currentRequirementRevision(), workPackage.packageId());
            advancePackageOrAggregate(validating, completed);
        } catch (BadRequestException invalid) {
            packageCompilerRejected(getCompilation(compilation.id()), get(session.id()),
                    getWorkPackage(workPackage.id()), remote, invalid.code(), invalid.getMessage());
        } catch (ConflictException stale) {
            failPackageCompilation(getCompilation(compilation.id()), get(session.id()),
                    stale.code(), stale.getMessage(), false);
        }
    }

    private void validateCompiledPackage(DesignerSessionRow session, DesignWorkPackageRow workPackage,
                                         String design, PackageCompilationEnvelope envelope) {
        if (envelope.stages().isEmpty() || envelope.stages().size() > MAX_PACKAGE_STAGES) {
            throw new BadRequestException("WORK_PACKAGE_STAGE_COUNT_INVALID",
                    "A compiled work package must contain 1-3 stages");
        }
        Set<String> criterionIds = new HashSet<>();
        for (int stageIndex = 0; stageIndex < envelope.stages().size(); stageIndex++) {
            LoopSpec.StageSpec stage = envelope.stages().get(stageIndex);
            if (!workPackage.packageId().equals(stage.workPackageId())) {
                throw new BadRequestException("WORK_PACKAGE_STAGE_ID_MISMATCH",
                        "Every generated stage must set workPackageId=" + workPackage.packageId());
            }
            for (LoopSpec.AcceptanceCriterion criterion : stage.acceptanceCriteria()) {
                if (!criterion.id().matches(Pattern.quote(workPackage.packageId()) + "-AC-[1-9][0-9]*")) {
                    throw new BadRequestException("WORK_PACKAGE_CRITERION_ID_INVALID",
                            "Acceptance ids must use " + workPackage.packageId() + "-AC-n");
                }
                if (!criterionIds.add(criterion.id())) {
                    throw new BadRequestException("WORK_PACKAGE_CRITERION_ID_DUPLICATE",
                            "Acceptance ids must be unique across a work package: " + criterion.id());
                }
            }
        }
        if (envelope.handoffSummary() != null
                && envelope.handoffSummary().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_HANDOFF_SUMMARY_LENGTH) {
            throw new BadRequestException("WORK_PACKAGE_HANDOFF_TOO_LARGE",
                    "Dependency handoff summary must be at most 4 KiB UTF-8");
        }
        LoopDraftRow draft = drafts.get(session.loopDraftId());
        LoopSpec base = drafts.spec(draft);
        LoopSpec packageSpec = new LoopSpec(base.schemaVersion(), base.projectId(), base.goal(), base.context(),
                envelope.stages(), base.limits(), base.model(), base.sessionPolicy(),
                base.nextAttemptPromptTemplate(), base.budget());
        drafts.validate(packageSpec);
        validateTraceability(design, packageSpec, envelope.criterionSources());
    }

    private void packageCompilerRejected(LoopSpecCompilationRow input, DesignerSessionRow session,
                                         DesignWorkPackageRow workPackage, OpenCodeClient.OpenCodeSession remote,
                                         String code, String detail) {
        LoopSpecCompilationRow compilation = getCompilation(input.id());
        appendMessage(session.id(), DesignerActor.VALIDATOR,
                workPackage.packageId() + " 确定性校验未通过（" + code + "）：" + safeMessage(detail),
                "RETRYABLE_ERROR", session.currentRequirementRevision(), workPackage.packageId());
        boolean planning = StructuredModelStep.PLANNING.name().equals(compilation.workflowStep());
        int repairsUsed = planning ? compilation.planningRepairCount() : compilation.repairCount();
        if (repairsUsed >= MAX_COMPILER_REPAIRS) {
            updateCompilation(compilation, LoopSpecCompilationState.SESSION_ERROR, remote.id(), "FAILED",
                    compilation.repairCount(), "COMPILER_RETRY_EXHAUSTED", safeMessage(detail),
                    session.projectId(), compilation.compiledPackageJson());
            DesignWorkPackageRow waiting = updateWorkPackage(workPackage, DesignWorkPackageState.WAITING_INPUT,
                    workPackage.designerExternalSessionId(), workPackage.designerExternalSessionState(),
                    workPackage.designMessageId(), workPackage.designRevision(), workPackage.redesignCount(),
                    workPackage.designerTransportRetryCount(), workPackage.compilerSummary(),
                    workPackage.handoffSummary(), "COMPILER_RETRY_EXHAUSTED", safeMessage(detail));
            waitForDesignInput(session, currentRequirement(session.id()), waiting,
                    "COMPILER_RETRY_EXHAUSTED", detail);
            return;
        }
        int repair = repairsUsed + 1;
        LoopSpecCompilationRow repairing = updateCompilation(compilation, LoopSpecCompilationState.RUNNING,
                remote.id(), "REPAIRING_" + repair, planning ? compilation.repairCount() : repair,
                code, safeMessage(detail), session.projectId(),
                compilation.compiledPackageJson(),
                planning ? StructuredModelStep.PLANNING : StructuredModelStep.REPAIRING_JSON,
                compilation.planningJson(), planning ? repair : compilation.planningRepairCount());
        DesignRequirementRevisionRow revision = currentRequirement(session.id());
        if (!consumeModelCall(session, revision, "WORK_PACKAGE_MODEL_CALL_LIMIT")) return;
        try {
            openCode.promptAsync(remote, planning
                    ? packageCompilerPlanningRepairPrompt(repairing, workPackage,
                    designMessage(workPackage).content(), code, detail)
                    : packageCompilerRepairPrompt(repairing, code, detail));
            updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING, DesignWorkflowPhase.COMPILING,
                    session.externalSessionId(), session.externalSessionState(), session.designRevision(),
                    session.redesignCount(), session.currentRequirementRevision(), workPackage.packageId());
            publish(session, "STATUS", DesignerActor.COMPILER, true, "",
                    workPackage.packageId() + " 规范编译器正在进行第 " + repair + "/"
                            + MAX_COMPILER_REPAIRS + " 次修复");
        } catch (RuntimeException failure) {
            failPackageCompilation(repairing, session, "OPENCODE_COMPILER_REPAIR_FAILED",
                    failure.getMessage(), true);
        }
    }

    private void advancePackageOrAggregate(DesignerSessionRow session, DesignWorkPackageRow completed) {
        DesignRequirementRevisionRow revision = currentRequirement(session.id());
        requireDraftUnchanged(session, revision.sourceDraftVersion());
        List<DesignWorkPackageRow> packages = mapper.listDesignWorkPackages(revision.id());
        DesignWorkPackageRow next = packages.stream()
                .filter(row -> row.ordinal() > completed.ordinal())
                .filter(row -> DesignWorkPackageState.PENDING.name().equals(row.state()))
                .findFirst().orElse(null);
        if (next != null) {
            dispatchPackageDesigner(get(session.id()), next, null, false);
            return;
        }
        if (packages.stream().anyMatch(row -> !DesignWorkPackageState.COMPLETED.name().equals(row.state()))) {
            waitForDesignInput(session, revision, completed, "WORK_PACKAGE_SEQUENCE_INCOMPLETE",
                    "Not every work package has completed successfully");
            return;
        }
        aggregateLoopSpec(get(session.id()), revision, packages);
    }

    private void aggregateLoopSpec(DesignerSessionRow session, DesignRequirementRevisionRow revision,
                                   List<DesignWorkPackageRow> packages) {
        DesignerSessionRow aggregating = updateDesignerProjection(session, DesignerSessionState.RUNNING,
                DesignWorkflowPhase.AGGREGATING, session.externalSessionId(), session.externalSessionState(),
                session.designRevision(), session.redesignCount(), revision.revision(), null);
        List<LoopSpec.StageSpec> stages = new ArrayList<>();
        List<PackageCompilationEnvelope> compiled = new ArrayList<>();
        for (DesignWorkPackageRow workPackage : packages) {
            LoopSpecCompilationRow compilation = mapper.findLatestLoopSpecCompilationForPackage(
                    session.id(), workPackage.packageId()).orElseThrow(() -> new ConflictException(
                    "WORK_PACKAGE_COMPILATION_MISSING", "Missing compilation for " + workPackage.packageId()));
            if (!LoopSpecCompilationState.COMPLETED.name().equals(compilation.state())
                    || blank(compilation.compiledPackageJson())) {
                throw new ConflictException("WORK_PACKAGE_COMPILATION_INCOMPLETE",
                        "Compilation is incomplete for " + workPackage.packageId());
            }
            try {
                PackageCompilationEnvelope envelope = json.readValue(compilation.compiledPackageJson(),
                        PackageCompilationEnvelope.class).normalized();
                compiled.add(envelope);
                stages.addAll(envelope.stages());
            } catch (JacksonException unreadable) {
                throw new ConflictException("WORK_PACKAGE_COMPILATION_INVALID",
                        "Stored compilation is unreadable for " + workPackage.packageId());
            }
        }
        if (stages.isEmpty() || stages.size() > MAX_TOTAL_STAGES) {
            waitForDesignInput(aggregating, revision, null, "AGGREGATED_STAGE_COUNT_INVALID",
                    "Aggregated LoopSpec must contain 1-18 stages");
            return;
        }
        TaskDecompositionRow decomposition = mapper.findTaskDecompositionByRevision(revision.id())
                .orElseThrow(() -> new ConflictException("DECOMPOSITION_MISSING", "Frozen decomposition is missing"));
        LoopDraftRow draft = drafts.get(session.loopDraftId());
        LoopSpec base = drafts.spec(draft);
        String context = aggregateContext(base.context(), decomposition.globalConstraintsJson());
        LoopSpec.Limits limits = safeAggregateLimits(base.limits(), packages, compiled);
        LoopSpec aggregate = new LoopSpec(base.schemaVersion(), base.projectId(), decomposition.normalizedGoal(),
                context, stages, limits, base.model(), base.sessionPolicy(), base.nextAttemptPromptTemplate(), base.budget());
        try {
            drafts.updateAtVersion(draft.id(), aggregate, revision.sourceDraftVersion());
            updateRequirement(revision, DesignRequirementRevisionState.COMPLETED, revision.modelCallsUsed());
            appendMessage(session.id(), DesignerActor.VALIDATOR,
                    "最终聚合校验通过：" + packages.size() + " 个工作包、" + stages.size()
                            + " 个 Stage 已按包顺序写入 Review Gate；确认前仍未创建 Task。",
                    "PASS", revision.revision(), null);
            DesignerSessionRow completed = updateDesignerProjection(get(session.id()),
                    DesignerSessionState.COMPLETED, DesignWorkflowPhase.COMPLETED,
                    session.externalSessionId(), session.externalSessionState(), session.designRevision(),
                    session.redesignCount(), revision.revision(), null);
            publish(completed, "COMPLETED", DesignerActor.VALIDATOR, true, "",
                    "全部工作包已聚合并通过确定性校验");
        } catch (BadRequestException invalid) {
            waitForDesignInput(aggregating, revision, null, invalid.code(), invalid.getMessage());
        } catch (ConflictException stale) {
            waitForDesignInput(aggregating, revision, null, stale.code(), stale.getMessage());
        }
    }

    private void compilerRejected(LoopSpecCompilationRow input, DesignerSessionRow session,
                                  OpenCodeClient.OpenCodeSession remote, String code, String detail) {
        if (!blank(input.workPackageId())) {
            packageCompilerRejected(input, session, requireCurrentPackage(session, input.workPackageId()),
                    remote, code, detail);
            return;
        }
        LoopSpecCompilationRow compilation = getCompilation(input.id());
        appendMessage(session.id(), DesignerActor.VALIDATOR,
                "确定性校验未通过（" + code + "）：" + safeMessage(detail), "RETRYABLE_ERROR");
        if (compilation.repairCount() >= MAX_COMPILER_REPAIRS) {
            failCompilation(compilation, session, "COMPILER_RETRY_EXHAUSTED", detail);
            return;
        }
        int repair = compilation.repairCount() + 1;
        LoopSpecCompilationRow repairing = updateCompilation(compilation, LoopSpecCompilationState.RUNNING,
                remote.id(), "REPAIRING_" + repair, repair, code, safeMessage(detail), session.projectId());
        DesignerSessionRow compiling = updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                DesignWorkflowPhase.COMPILING, session.externalSessionId(), session.externalSessionState(),
                session.designRevision(), session.redesignCount());
        try {
            openCode.promptAsync(remote, compilerRepairPrompt(repairing, code, detail));
            publish(compiling, "STATUS", DesignerActor.COMPILER, true, "",
                    "规范编译器正在进行第 " + repair + "/" + MAX_COMPILER_REPAIRS + " 次修复");
        } catch (RuntimeException failure) {
            failCompilation(repairing, compiling, "OPENCODE_COMPILER_REPAIR_FAILED", failure.getMessage());
        }
    }

    private void failCompilation(LoopSpecCompilationRow input, DesignerSessionRow session,
                                 String code, String detail) {
        if (!blank(input.workPackageId())) {
            failPackageCompilation(input, session, code, detail, true);
            return;
        }
        LoopSpecCompilationRow current = mapper.findLoopSpecCompilation(input.id()).orElse(input);
        if (!LoopSpecCompilationState.SESSION_ERROR.name().equals(current.state())
                && !LoopSpecCompilationState.COMPLETED.name().equals(current.state())
                && !LoopSpecCompilationState.DESIGN_INCOMPLETE.name().equals(current.state())) {
            updateCompilation(current, LoopSpecCompilationState.SESSION_ERROR,
                    current.externalSessionId(), "FAILED", current.repairCount(), code,
                    safeMessage(detail), session.projectId());
        }
        failWorkflow(get(session.id()), code, detail);
    }

    private void failDecomposition(TaskDecompositionRow input, DesignerSessionRow session,
                                   String code, String detail, boolean transportFailure) {
        TaskDecompositionRow decomposition = mapper.findTaskDecomposition(input.id()).orElse(input);
        DesignRequirementRevisionRow revision = getRequirement(decomposition.requirementRevisionId());
        if (transportFailure && decomposition.transportRetryCount() < 1 && isCurrent(session, revision)) {
            abortQuietly(decomposition.externalSessionId(), session.projectId());
            ProjectRow project = projects.get(session.projectId());
            try {
                OpenCodeClient.OpenCodeSession remote = openCode.createReadOnlySession(Path.of(project.rootPath()),
                        "OpenCode Loopper Task Decomposer retry (READ_ONLY)", configuredModel());
                TaskDecompositionRow retried = updateDecomposition(decomposition, TaskDecompositionState.RUNNING,
                        decomposition.resultType(), decomposition.normalizedGoal(), decomposition.globalConstraintsJson(),
                        decomposition.planJson(), remote.id(), "TRANSPORT_RETRY", decomposition.repairCount(),
                        decomposition.transportRetryCount() + 1, code, safeMessage(detail));
                DesignerSessionRow running = updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                        DesignWorkflowPhase.DECOMPOSING, remote.id(), "TRANSPORT_RETRY", session.designRevision(),
                        session.redesignCount(), revision.revision(), null);
                if (!consumeModelCall(running, revision, "DECOMPOSER_MODEL_CALL_LIMIT")) return;
                openCode.promptAsync(remote, decomposerTransportRetryPrompt(retried, project, revision));
                publish(running, "STATUS", DesignerActor.DECOMPOSER, true, "",
                        "任务拆解器传输失败后正在使用唯一一次全新 Session 重试");
                return;
            } catch (RuntimeException retryFailure) {
                detail = safeMessage(detail) + "; transport retry failed: " + safeMessage(retryFailure.getMessage());
            }
        }
        if (Set.of(TaskDecompositionState.PENDING_HANDOFF.name(), TaskDecompositionState.RUNNING.name(),
                TaskDecompositionState.VALIDATING.name()).contains(decomposition.state())) {
            updateDecomposition(decomposition, TaskDecompositionState.SESSION_ERROR,
                    decomposition.resultType(), decomposition.normalizedGoal(), decomposition.globalConstraintsJson(),
                    decomposition.planJson(), decomposition.externalSessionId(), "FAILED", decomposition.repairCount(),
                    decomposition.transportRetryCount(), code, safeMessage(detail));
        }
        waitForDesignInput(session, revision, null, code, detail);
    }

    private void failPackageDesigner(DesignWorkPackageRow input, DesignerSessionRow session,
                                     String code, String detail, boolean transportFailure) {
        DesignWorkPackageRow workPackage = getWorkPackage(input.id());
        DesignRequirementRevisionRow revision = getRequirement(workPackage.requirementRevisionId());
        abortQuietly(workPackage.designerExternalSessionId(), session.projectId());
        if (transportFailure && workPackage.designerTransportRetryCount() < 1 && isCurrent(session, revision)) {
            DesignWorkPackageRow waiting = updateWorkPackage(workPackage, DesignWorkPackageState.WAITING_INPUT,
                    workPackage.designerExternalSessionId(), "TRANSPORT_RETRY", workPackage.designMessageId(),
                    workPackage.designRevision(), workPackage.redesignCount(),
                    workPackage.designerTransportRetryCount() + 1, workPackage.compilerSummary(),
                    workPackage.handoffSummary(), code, safeMessage(detail));
            dispatchPackageDesigner(get(session.id()), waiting, null, false);
            return;
        }
        DesignWorkPackageRow waiting = Set.of(DesignWorkPackageState.DESIGNING.name(),
                DesignWorkPackageState.COMPILING.name(), DesignWorkPackageState.VALIDATING.name())
                .contains(workPackage.state())
                ? updateWorkPackage(workPackage, DesignWorkPackageState.WAITING_INPUT,
                workPackage.designerExternalSessionId(), "FAILED", workPackage.designMessageId(),
                workPackage.designRevision(), workPackage.redesignCount(), workPackage.designerTransportRetryCount(),
                workPackage.compilerSummary(), workPackage.handoffSummary(), code, safeMessage(detail))
                : workPackage;
        waitForDesignInput(session, revision, waiting, code, detail);
    }

    private void failPackageCompilation(LoopSpecCompilationRow input, DesignerSessionRow session,
                                        String code, String detail, boolean transportFailure) {
        LoopSpecCompilationRow compilation = mapper.findLoopSpecCompilation(input.id()).orElse(input);
        DesignWorkPackageRow workPackage = requireCurrentPackage(session, compilation.workPackageId());
        DesignRequirementRevisionRow revision = currentRequirement(session.id());
        abortQuietly(compilation.externalSessionId(), session.projectId());
        if (transportFailure && compilation.transportRetryCount() < 1 && isCurrent(session, revision)) {
            ProjectRow project = projects.get(session.projectId());
            try {
                OpenCodeClient.OpenCodeSession remote = openCode.createReadOnlySession(Path.of(project.rootPath()),
                        "OpenCode Loopper LoopSpec Compiler " + workPackage.packageId() + " retry (READ_ONLY)",
                        configuredModel());
                LoopSpecCompilationRow retryBase = new LoopSpecCompilationRow(compilation.id(),
                        compilation.designerSessionId(), compilation.designRevision(), compilation.state(),
                        compilation.externalSessionId(), compilation.externalSessionState(), compilation.repairCount(),
                        compilation.sourceDesignMessageId(), compilation.sourceDraftVersion(),
                        compilation.lastErrorCode(), compilation.lastErrorDetail(), compilation.createdAt(),
                        compilation.updatedAt(), compilation.version(), compilation.workPackageId(),
                        compilation.transportRetryCount() + 1, compilation.compiledPackageJson(),
                        compilation.workflowStep(), compilation.planningJson(), compilation.planningRepairCount());
                LoopSpecCompilationRow running = updateCompilation(retryBase, LoopSpecCompilationState.RUNNING,
                        remote.id(), "TRANSPORT_RETRY", compilation.repairCount(), code, safeMessage(detail),
                        session.projectId(), compilation.compiledPackageJson());
                if (!consumeModelCall(session, revision, "WORK_PACKAGE_MODEL_CALL_LIMIT")) return;
                openCode.promptAsync(remote, packageCompilerTransportRetryPrompt(running, session, project,
                        revision, workPackage, designMessage(workPackage).content()));
                publish(session, "STATUS", DesignerActor.COMPILER, true, "",
                        workPackage.packageId() + " 编译器传输失败后正在使用唯一一次全新 Session 重试");
                return;
            } catch (RuntimeException retryFailure) {
                detail = safeMessage(detail) + "; transport retry failed: " + safeMessage(retryFailure.getMessage());
            }
        }
        if (Set.of(LoopSpecCompilationState.PENDING_HANDOFF.name(), LoopSpecCompilationState.RUNNING.name())
                .contains(compilation.state())) {
            updateCompilation(compilation, LoopSpecCompilationState.SESSION_ERROR,
                    compilation.externalSessionId(), "FAILED", compilation.repairCount(), code,
                    safeMessage(detail), session.projectId(), compilation.compiledPackageJson());
        }
        DesignWorkPackageRow waiting = Set.of(DesignWorkPackageState.COMPILING.name(),
                DesignWorkPackageState.VALIDATING.name()).contains(workPackage.state())
                ? updateWorkPackage(workPackage, DesignWorkPackageState.WAITING_INPUT,
                workPackage.designerExternalSessionId(), workPackage.designerExternalSessionState(),
                workPackage.designMessageId(), workPackage.designRevision(), workPackage.redesignCount(),
                workPackage.designerTransportRetryCount(), workPackage.compilerSummary(),
                workPackage.handoffSummary(), code, safeMessage(detail)) : workPackage;
        waitForDesignInput(session, revision, waiting, code, detail);
    }

    private void waitForDesignInput(DesignerSessionRow input, DesignRequirementRevisionRow revision,
                                    DesignWorkPackageRow workPackage, String code, String detail) {
        DesignRequirementRevisionRow currentRevision = getRequirement(revision.id());
        if (DesignRequirementRevisionState.ACTIVE.name().equals(currentRevision.state())) {
            updateRequirement(currentRevision, DesignRequirementRevisionState.WAITING_INPUT,
                    currentRevision.modelCallsUsed());
        }
        DesignerSessionRow session = get(input.id());
        DesignerSessionRow waiting = DesignerSessionState.WAITING_INPUT.name().equals(session.state()) ? session
                : updateDesignerProjection(session, DesignerSessionState.WAITING_INPUT,
                DesignWorkflowPhase.FAILED, session.externalSessionId(), "WAITING_INPUT",
                session.designRevision(), session.redesignCount(), currentRevision.revision(),
                workPackage == null ? session.activeWorkPackageId() : workPackage.packageId());
        DesignerMessageRow message = appendMessage(waiting.id(), DesignerActor.VALIDATOR,
                "工作流等待人工处理（" + code + "）：" + safeMessage(detail)
                        + "。当前需求版本未同步草稿，也没有创建或修改 Task。",
                "TERMINAL_ERROR", currentRevision.revision(),
                workPackage == null ? waiting.activeWorkPackageId() : workPackage.packageId());
        publish(waiting, "ERROR", DesignerActor.VALIDATOR, false, "", message.content());
    }

    private DesignerMessageRow failWorkflow(DesignerSessionRow input, String code, String detail) {
        DesignerSessionRow current = get(input.id());
        DesignerSessionRow failed = current;
        if (!DesignerSessionState.SESSION_ERROR.name().equals(current.state())) {
            failed = updateDesignerProjection(current, DesignerSessionState.SESSION_ERROR,
                    DesignWorkflowPhase.FAILED, current.externalSessionId(), "FAILED",
                    current.designRevision(), current.redesignCount());
        }
        DesignerMessageRow message = appendMessage(failed.id(), DesignerActor.VALIDATOR,
                "工作流已停止（" + code + "）：" + safeMessage(detail)
                        + "。设计稿未同步，也没有创建或修改 Task。",
                "TERMINAL_ERROR");
        publish(failed, "ERROR", DesignerActor.VALIDATOR, false, "", message.content());
        return message;
    }

    private CompilationEnvelope parseCompilation(String output) {
        if (blank(output)) throw new BadRequestException("COMPILER_OUTPUT_MISSING",
                "LoopSpec Compiler completed without output");
        Matcher matcher = COMPILATION_PAYLOAD.matcher(output);
        if (!matcher.find()) throw new BadRequestException("COMPILER_OUTPUT_MARKERS_MISSING",
                "Compiler output did not contain the required compilation markers");
        String payload = matcher.group(1);
        int start = payload.indexOf('{');
        int end = payload.lastIndexOf('}');
        if (start < 0 || end <= start) throw new BadRequestException("COMPILER_OUTPUT_INVALID",
                "Compiler payload is not one JSON object");
        try {
            CompilationEnvelope envelope = json.readValue(payload.substring(start, end + 1), CompilationEnvelope.class);
            if (envelope == null || blank(envelope.status())) {
                throw new BadRequestException("COMPILER_STATUS_MISSING", "Compiler status is required");
            }
            return envelope.normalized();
        } catch (BadRequestException failure) {
            throw failure;
        } catch (JacksonException failure) {
            throw new BadRequestException("COMPILER_OUTPUT_INVALID",
                    "Compiler JSON cannot be read: " + failure.getMessage());
        }
    }

    private List<DesignGap> validateDesignGaps(List<DesignGap> input) {
        if (input == null || input.isEmpty()) throw new BadRequestException("DESIGN_GAPS_REQUIRED",
                "DESIGN_INCOMPLETE requires at least one concrete design gap");
        List<DesignGap> result = new ArrayList<>();
        for (DesignGap gap : input) {
            if (gap == null || gap.code() == null || !ALLOWED_DESIGN_GAPS.contains(gap.code()) || blank(gap.detail())) {
                throw new BadRequestException("DESIGN_GAP_INVALID",
                        "Design gaps must use a closed semantic gap code and a concrete detail");
            }
            result.add(new DesignGap(gap.code(), bounded(gap.detail().trim(), 1_000)));
        }
        return List.copyOf(result);
    }

    private void validateTraceability(String design, LoopSpec spec, List<CriterionSource> sources) {
        if (spec == null) throw new BadRequestException("COMPILED_LOOPSPEC_MISSING", "COMPILED requires loopSpec");
        List<CriterionSource> trace = sources == null ? List.of() : sources;
        Set<String> seen = new HashSet<>();
        for (int stageIndex = 0; stageIndex < spec.stages().size(); stageIndex++) {
            int currentStageIndex = stageIndex;
            for (LoopSpec.AcceptanceCriterion criterion : spec.stages().get(stageIndex).acceptanceCriteria()) {
                if (criterion == null || blank(criterion.id())) {
                    throw new BadRequestException("CRITERION_SOURCE_INVALID",
                            "Every compiled acceptance criterion must have a non-blank id before source mapping");
                }
                String key = stageIndex + ":" + criterion.id();
                CriterionSource source = trace.stream()
                        .filter(item -> item != null && item.stageIndex() == currentStageIndex
                                && criterion.id().equals(item.criterionId())).findFirst()
                        .orElseThrow(() -> new BadRequestException("CRITERION_SOURCE_MISSING",
                                "No Designer source excerpt was supplied for " + key));
                if (blank(source.excerpt()) || !design.contains(source.excerpt())) {
                    throw new BadRequestException("CRITERION_SOURCE_NOT_IN_DESIGN",
                            "Criterion source excerpt is not an exact substring of the frozen design: " + key);
                }
                if (!seen.add(key)) throw new BadRequestException("CRITERION_SOURCE_DUPLICATE",
                        "Criterion source was duplicated: " + key);
            }
        }
        if (trace.size() != seen.size()) throw new BadRequestException("CRITERION_SOURCE_EXTRA",
                "Compiler supplied criterion source entries that do not belong to the compiled LoopSpec");
    }

    private DecompositionEnvelope parseDecomposition(String output) {
        String payload = decompositionPayload(output, DECOMPOSITION_PAYLOAD, "DECOMPOSER_OUTPUT");
        try {
            DecompositionEnvelope envelope = json.readValue(payload, DecompositionEnvelope.class);
            if (envelope == null || blank(envelope.status())) {
                throw new BadRequestException("DECOMPOSER_STATUS_MISSING", "Decomposer status is required");
            }
            return envelope.normalized();
        } catch (BadRequestException failure) {
            throw failure;
        } catch (JacksonException failure) {
            throw new BadRequestException("DECOMPOSER_OUTPUT_INVALID",
                    "Decomposer JSON cannot be read: " + failure.getMessage());
        } catch (RuntimeException failure) {
            throw new BadRequestException("DECOMPOSER_OUTPUT_INVALID",
                    "Decomposer JSON cannot be normalized: " + safeMessage(failure.getMessage()));
        }
    }

    private DecompositionPlanEnvelope parseDecompositionPlan(String output) {
        String payload = decompositionPayload(output, DECOMPOSITION_PLAN_PAYLOAD, "DECOMPOSER_PLAN_OUTPUT");
        try {
            DecompositionPlanEnvelope envelope = json.readValue(payload, DecompositionPlanEnvelope.class);
            if (envelope == null || blank(envelope.status())) {
                throw new BadRequestException("DECOMPOSER_PLAN_STATUS_MISSING",
                        "Decomposer planning status is required");
            }
            return envelope.normalized();
        } catch (BadRequestException failure) {
            throw failure;
        } catch (JacksonException failure) {
            throw new BadRequestException("DECOMPOSER_PLAN_OUTPUT_INVALID",
                    "Decomposer planning JSON cannot be read: " + failure.getMessage());
        } catch (RuntimeException failure) {
            throw new BadRequestException("DECOMPOSER_PLAN_OUTPUT_INVALID",
                    "Decomposer planning JSON cannot be normalized: " + safeMessage(failure.getMessage()));
        }
    }

    private DecompositionPlanEnvelope readDecompositionPlan(String payload) {
        try {
            return json.readValue(payload, DecompositionPlanEnvelope.class).normalized();
        } catch (JacksonException failure) {
            throw new ConflictException("DECOMPOSER_PLAN_INVALID", "Frozen decomposition planning is unreadable");
        } catch (RuntimeException failure) {
            throw new ConflictException("DECOMPOSER_PLAN_INVALID", "Frozen decomposition planning is invalid");
        }
    }

    private PackageCompilationEnvelope parsePackageCompilation(String output) {
        String payload = markedPayload(output, COMPILATION_PAYLOAD, "COMPILER_OUTPUT");
        try {
            PackageCompilationEnvelope envelope = json.readValue(payload, PackageCompilationEnvelope.class);
            if (envelope == null || blank(envelope.status())) {
                throw new BadRequestException("COMPILER_STATUS_MISSING", "Compiler status is required");
            }
            return envelope.normalized();
        } catch (BadRequestException failure) {
            throw failure;
        } catch (JacksonException failure) {
            throw new BadRequestException("COMPILER_OUTPUT_INVALID",
                    "Compiler JSON cannot be read: " + failure.getMessage());
        } catch (RuntimeException failure) {
            throw new BadRequestException("COMPILER_OUTPUT_INVALID",
                    "Compiler JSON cannot be normalized: " + safeMessage(failure.getMessage()));
        }
    }

    private PackageCompilationPlanEnvelope parsePackageCompilationPlan(String output) {
        String payload = markedPayload(output, COMPILATION_PLAN_PAYLOAD, "COMPILER_PLAN_OUTPUT");
        try {
            PackageCompilationPlanEnvelope envelope = json.readValue(payload, PackageCompilationPlanEnvelope.class);
            if (envelope == null || blank(envelope.status())) {
                throw new BadRequestException("COMPILER_PLAN_STATUS_MISSING",
                        "Compiler planning status is required");
            }
            return envelope.normalized();
        } catch (BadRequestException failure) {
            throw failure;
        } catch (JacksonException failure) {
            throw new BadRequestException("COMPILER_PLAN_OUTPUT_INVALID",
                    "Compiler planning JSON cannot be read: " + failure.getMessage());
        } catch (RuntimeException failure) {
            throw new BadRequestException("COMPILER_PLAN_OUTPUT_INVALID",
                    "Compiler planning JSON cannot be normalized: " + safeMessage(failure.getMessage()));
        }
    }

    private PackageCompilationPlanEnvelope readPackageCompilationPlan(String payload) {
        try {
            return json.readValue(payload, PackageCompilationPlanEnvelope.class).normalized();
        } catch (JacksonException failure) {
            throw new ConflictException("COMPILER_PLAN_INVALID", "Frozen package compilation planning is unreadable");
        } catch (RuntimeException failure) {
            throw new ConflictException("COMPILER_PLAN_INVALID", "Frozen package compilation planning is invalid");
        }
    }

    private String markedPayload(String output, Pattern pattern, String prefix) {
        if (blank(output)) throw new BadRequestException(prefix + "_MISSING", "Read-only model completed without output");
        Matcher matcher = pattern.matcher(output);
        if (!matcher.find()) throw new BadRequestException(prefix + "_MARKERS_MISSING",
                "Output did not contain the required structured markers");
        String body = matcher.group(1);
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) throw new BadRequestException(prefix + "_INVALID",
                "Marked payload is not one JSON object");
        return body.substring(start, end + 1);
    }

    private String decompositionPayload(String output, Pattern pattern, String prefix) {
        if (blank(output)) throw new BadRequestException(prefix + "_MISSING", "Read-only model completed without output");
        Matcher matcher = pattern.matcher(output);
        if (matcher.find()) {
            String body = matcher.group(1);
            int start = body.indexOf('{');
            int end = body.lastIndexOf('}');
            if (start < 0 || end <= start) throw new BadRequestException(prefix + "_INVALID",
                    "Marked payload is not one JSON object");
            return body.substring(start, end + 1);
        }
        String standalone = standaloneJsonObject(output);
        if (standalone != null) return standalone;
        throw new BadRequestException(prefix + "_MARKERS_MISSING",
                "Output did not contain the required structured markers or one standalone JSON object");
    }

    private String standaloneJsonObject(String output) {
        String candidate = output.trim();
        Matcher fence = STANDALONE_JSON_FENCE.matcher(candidate);
        if (fence.matches()) candidate = fence.group(1).trim();
        if (!candidate.startsWith("{") || !candidate.endsWith("}")) return null;
        try (JsonParser parser = json.createParser(candidate)) {
            JsonNode root = json.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) return null;
            return candidate;
        } catch (JacksonException failure) {
            return null;
        }
    }

    private void validateDecomposition(DecompositionEnvelope envelope, DesignRequirementRevisionRow revision) {
        Set<String> statuses = Set.of("DIRECT_DESIGN", "DECOMPOSED", "NEEDS_INPUT", "MULTI_TASK_REQUIRED");
        if (!statuses.contains(envelope.status())) throw new BadRequestException("DECOMPOSER_STATUS_INVALID",
                "Status must be DIRECT_DESIGN, DECOMPOSED, NEEDS_INPUT, or MULTI_TASK_REQUIRED");
        if ("NEEDS_INPUT".equals(envelope.status())) {
            if (envelope.designGaps().isEmpty()) throw new BadRequestException("DECOMPOSITION_GAPS_REQUIRED",
                    "NEEDS_INPUT requires concrete closed-set design gaps");
            validateDesignGaps(envelope.designGaps());
            return;
        }
        if ("MULTI_TASK_REQUIRED".equals(envelope.status())) {
            if (blank(envelope.reason())) throw new BadRequestException("MULTI_TASK_REASON_REQUIRED",
                    "MULTI_TASK_REQUIRED requires a concrete boundary reason");
            return;
        }
        if (blank(envelope.normalizedGoal())) throw new BadRequestException("DECOMPOSITION_GOAL_REQUIRED",
                "A normalized overall goal is required");
        if (envelope.normalizedGoal().length() > 12_000) throw new BadRequestException("DECOMPOSITION_GOAL_TOO_LONG",
                "The normalized goal exceeds the LoopSpec limit");
        int count = envelope.workPackages().size();
        if ("DIRECT_DESIGN".equals(envelope.status()) && count != 1) {
            throw new BadRequestException("DIRECT_DESIGN_PACKAGE_COUNT_INVALID",
                    "DIRECT_DESIGN must produce exactly one work package");
        }
        if ("DECOMPOSED".equals(envelope.status()) && (count < 2 || count > MAX_WORK_PACKAGES)) {
            throw new BadRequestException("DECOMPOSED_PACKAGE_COUNT_INVALID",
                    "DECOMPOSED must produce 2-6 work packages");
        }
        List<RequirementSegment> segments = readSegments(revision.requirementSegmentsJson());
        Set<String> validRefs = segments.stream().map(RequirementSegment::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> covered = new LinkedHashSet<>();
        for (GlobalConstraint constraint : envelope.globalConstraints()) {
            if (constraint == null || blank(constraint.text()) || constraint.requirementRefs().isEmpty()) {
                throw new BadRequestException("GLOBAL_CONSTRAINT_INVALID",
                        "Each global constraint needs text and requirement references");
            }
            validateRequirementRefs(constraint.requirementRefs(), validRefs, covered);
        }
        Set<String> ids = new LinkedHashSet<>();
        Set<String> mechanicalTitles = Set.of("前端", "后端", "数据库", "测试", "frontend", "backend", "database", "tests");
        for (int index = 0; index < count; index++) {
            DecomposedWorkPackage workPackage = envelope.workPackages().get(index);
            String expectedId = "WP-" + (index + 1);
            if (workPackage == null || !expectedId.equals(workPackage.id())) {
                throw new BadRequestException("WORK_PACKAGE_ID_INVALID",
                        "Work package ids must be stable and ordered as WP-1..WP-n");
            }
            if (!ids.add(workPackage.id()) || blank(workPackage.title()) || blank(workPackage.objective())
                    || workPackage.deliverables().isEmpty() || workPackage.acceptanceIntent().isEmpty()
                    || workPackage.requirementRefs().isEmpty()) {
                throw new BadRequestException("WORK_PACKAGE_INCOMPLETE",
                        expectedId + " requires title, objective, deliverables, acceptance intent, and requirement refs");
            }
            if (mechanicalTitles.contains(workPackage.title().trim().toLowerCase())) {
                throw new BadRequestException("MECHANICAL_LAYER_SPLIT_FORBIDDEN",
                        "Work packages must be vertical business capabilities, not isolated technical layers");
            }
            validateRequirementRefs(workPackage.requirementRefs(), validRefs, covered);
            Set<String> earlier = envelope.workPackages().subList(0, index).stream()
                    .map(DecomposedWorkPackage::id).collect(java.util.stream.Collectors.toSet());
            if (!earlier.containsAll(workPackage.dependencies())) {
                throw new BadRequestException("WORK_PACKAGE_DEPENDENCY_INVALID",
                        expectedId + " may depend only on unique earlier work packages");
            }
            if (new HashSet<>(workPackage.dependencies()).size() != workPackage.dependencies().size()) {
                throw new BadRequestException("WORK_PACKAGE_DEPENDENCY_DUPLICATE",
                        expectedId + " contains duplicate dependencies");
            }
        }
        Set<String> missing = new LinkedHashSet<>(validRefs);
        missing.removeAll(covered);
        if (!missing.isEmpty()) throw new BadRequestException("REQUIREMENT_SEGMENT_UNCOVERED",
                "Every requirement segment must map to a global constraint or work package: " + missing);
    }

    private void validateDecompositionPlan(DecompositionPlanEnvelope plan,
                                           DesignRequirementRevisionRow revision) {
        DecompositionEnvelope projected = plan.toEnvelope();
        validateDecomposition(projected, revision);
        if (!Set.of("DIRECT_DESIGN", "DECOMPOSED").contains(plan.status())) return;
        List<RequirementSegment> segments = readSegments(revision.requirementSegmentsJson());
        Set<String> validRefs = segments.stream().map(RequirementSegment::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> mappedRefs = new LinkedHashSet<>();
        Set<String> mappingKeys = new HashSet<>();
        for (RequirementCoverageMapping mapping : plan.coverageMappings()) {
            if (mapping == null || !validRefs.contains(mapping.requirementRef()) || blank(mapping.targetType())
                    || blank(mapping.targetId()) || blank(mapping.rationale())) {
                throw new BadRequestException("DECOMPOSITION_COVERAGE_MAPPING_INVALID",
                        "Every coverage mapping needs a known requirement ref, target, and rationale");
            }
            String type = mapping.targetType().toUpperCase();
            boolean targetMatches;
            if ("GLOBAL_CONSTRAINT".equals(type) && mapping.targetId().matches("GC-[1-9][0-9]*")) {
                int index = Integer.parseInt(mapping.targetId().substring(3)) - 1;
                targetMatches = index >= 0 && index < plan.globalConstraints().size()
                        && plan.globalConstraints().get(index).requirementRefs().contains(mapping.requirementRef());
            } else if ("WORK_PACKAGE".equals(type)) {
                targetMatches = plan.workPackages().stream().anyMatch(item -> mapping.targetId().equals(item.id())
                        && item.requirementRefs().contains(mapping.requirementRef()));
            } else {
                targetMatches = false;
            }
            if (!targetMatches) throw new BadRequestException("DECOMPOSITION_COVERAGE_MAPPING_MISMATCH",
                    "Coverage mapping target does not carry " + mapping.requirementRef() + ": " + mapping.targetId());
            String key = mapping.requirementRef() + ":" + type + ":" + mapping.targetId();
            if (!mappingKeys.add(key)) throw new BadRequestException("DECOMPOSITION_COVERAGE_MAPPING_DUPLICATE",
                    "Coverage mapping is duplicated: " + key);
            mappedRefs.add(mapping.requirementRef());
        }
        if (!mappedRefs.containsAll(validRefs)) {
            Set<String> missing = new LinkedHashSet<>(validRefs);
            missing.removeAll(mappedRefs);
            throw new BadRequestException("DECOMPOSITION_PLAN_COVERAGE_INCOMPLETE",
                    "Planning evidence does not explain requirement coverage: " + missing);
        }
        Set<String> expectedDependencies = new LinkedHashSet<>();
        for (DecomposedWorkPackage workPackage : plan.workPackages()) {
            for (String dependency : workPackage.dependencies()) {
                expectedDependencies.add(workPackage.id() + ":" + dependency);
            }
        }
        Set<String> explainedDependencies = new LinkedHashSet<>();
        for (DependencyEvidence evidence : plan.dependencyEvidence()) {
            if (evidence == null || blank(evidence.workPackageId()) || blank(evidence.dependsOn())
                    || blank(evidence.rationale())) {
                throw new BadRequestException("DECOMPOSITION_DEPENDENCY_EVIDENCE_INVALID",
                        "Every dependency evidence entry needs package ids and a rationale");
            }
            String key = evidence.workPackageId() + ":" + evidence.dependsOn();
            if (!expectedDependencies.contains(key) || !explainedDependencies.add(key)) {
                throw new BadRequestException("DECOMPOSITION_DEPENDENCY_EVIDENCE_MISMATCH",
                        "Dependency evidence is missing, extra, or duplicated: " + key);
            }
        }
        if (!explainedDependencies.equals(expectedDependencies)) {
            throw new BadRequestException("DECOMPOSITION_DEPENDENCY_EVIDENCE_INCOMPLETE",
                    "Every planned dependency needs one concrete rationale");
        }
    }

    private void validateDecompositionAgainstPlan(DecompositionPlanEnvelope plan,
                                                  DecompositionEnvelope envelope) {
        if (!plan.toEnvelope().equals(envelope)) {
            throw new BadRequestException("DECOMPOSITION_PLAN_DRIFT",
                    "Final decomposition JSON must preserve the frozen planning and coverage decisions exactly");
        }
    }

    private void validatePackageCompilationPlan(DesignWorkPackageRow workPackage, String design,
                                                PackageCompilationPlanEnvelope plan,
                                                boolean requireAcceptanceEvidence) {
        if (requireAcceptanceEvidence && plan.contractVersion() != 2) {
            throw new BadRequestException("COMPILER_PLAN_CONTRACT_VERSION_REQUIRED",
                    "v2 package planning must use contractVersion=2 with executable verifier blueprints");
        }
        if ("DESIGN_INCOMPLETE".equals(plan.status())) {
            validateDesignGaps(plan.designGaps());
            if (!plan.stages().isEmpty() || !plan.evidenceMappings().isEmpty()) {
                throw new BadRequestException("COMPILER_PLAN_INCOMPLETE_SHAPE_INVALID",
                        "DESIGN_INCOMPLETE planning cannot contain stages or evidence mappings");
            }
            return;
        }
        if (!"COMPILED".equals(plan.status())) throw new BadRequestException("COMPILER_PLAN_STATUS_INVALID",
                "Compiler planning status must be COMPILED or DESIGN_INCOMPLETE");
        if (!plan.designGaps().isEmpty()) throw new BadRequestException("COMPILER_PLAN_GAPS_UNEXPECTED",
                "COMPILED planning must use an empty designGaps array");
        if (plan.stages().isEmpty() || plan.stages().size() > MAX_PACKAGE_STAGES) {
            throw new BadRequestException("COMPILER_PLAN_STAGE_COUNT_INVALID",
                    "Compiler planning must contain 1-3 stages");
        }
        Set<String> criterionIds = new LinkedHashSet<>();
        Set<Integer> coveredStages = new LinkedHashSet<>();
        for (int index = 0; index < plan.stages().size(); index++) {
            PlannedStage stage = plan.stages().get(index);
            if (stage == null || blank(stage.objective())
                    || (requireAcceptanceEvidence && stage.implementationKind() == null)
                    || !workPackage.packageId().equals(stage.workPackageId()) || stage.deliverables().isEmpty()) {
                throw new BadRequestException("COMPILER_PLAN_STAGE_INVALID",
                        "Each planned stage needs objective, implementationKind, workPackageId, and deliverables");
            }
        }
        for (AcceptanceEvidenceMapping mapping : plan.evidenceMappings()) {
            if (mapping == null || mapping.stageIndex() < 0 || mapping.stageIndex() >= plan.stages().size()
                    || blank(mapping.criterionId()) || blank(mapping.description())
                    || blank(mapping.designerExcerpt()) || !design.contains(mapping.designerExcerpt())) {
                throw new BadRequestException("COMPILER_PLAN_EVIDENCE_INVALID",
                        "Every acceptance evidence mapping must target a stage and quote the frozen design exactly");
            }
            if (!mapping.criterionId().matches(Pattern.quote(workPackage.packageId()) + "-AC-[1-9][0-9]*")
                    || !criterionIds.add(mapping.criterionId())) {
                throw new BadRequestException("COMPILER_PLAN_CRITERION_ID_INVALID",
                        "Planned criterion ids must be unique and use " + workPackage.packageId() + "-AC-n");
            }
            String mode = mapping.verificationMode().toUpperCase();
            if (!Set.of("MACHINE", "JUDGE", "BOTH").contains(mode)) {
                throw new BadRequestException("COMPILER_PLAN_VERIFICATION_MODE_INVALID",
                        "Planned verificationMode must be MACHINE, JUDGE, or BOTH");
            }
            if (Set.of("JUDGE", "BOTH").contains(mode) && blank(mapping.judgeRubric())) {
                throw new BadRequestException("COMPILER_PLAN_JUDGE_RUBRIC_REQUIRED",
                        "JUDGE/BOTH planning requires a judgeRubric");
            }
            if ("JUDGE".equals(mode) && blank(mapping.judgeOnlyReason())) {
                throw new BadRequestException("COMPILER_PLAN_JUDGE_REASON_REQUIRED",
                        "JUDGE-only planning requires judgeOnlyReason");
            }
            if (Set.of("MACHINE", "BOTH").contains(mode) && blank(mapping.verifierStrategy())) {
                throw new BadRequestException("COMPILER_PLAN_VERIFIER_STRATEGY_REQUIRED",
                        "MACHINE/BOTH planning requires a concrete verifier strategy");
            }
            PlannedStage stage = plan.stages().get(mapping.stageIndex());
            if (stage.implementationKind() == ImplementationKind.JAVA_PRODUCTION
                    && Set.of("MACHINE", "BOTH").contains(mode)
                    && (mapping.testCommand().isEmpty() || mapping.testTargets().isEmpty())) {
                throw new BadRequestException("COMPILER_PLAN_JAVA_TEST_EVIDENCE_REQUIRED",
                        "Every JAVA_PRODUCTION machine criterion needs a focused Maven/Gradle test command and target");
            }
            coveredStages.add(mapping.stageIndex());
        }
        for (int index = 0; index < plan.stages().size(); index++) {
            if (requireAcceptanceEvidence && !coveredStages.contains(index)) throw new BadRequestException("COMPILER_PLAN_STAGE_UNCOVERED",
                    "Every planned stage needs at least one acceptance evidence mapping: " + index);
        }
        if (plan.contractVersion() >= 2) {
            List<LoopSpec.StageSpec> plannedStages = new ArrayList<>();
            for (int index = 0; index < plan.stages().size(); index++) {
                int stageIndex = index;
                PlannedStage stage = plan.stages().get(index);
                List<LoopSpec.AcceptanceCriterion> criteria = plan.evidenceMappings().stream()
                        .filter(mapping -> mapping.stageIndex() == stageIndex)
                        .map(mapping -> new LoopSpec.AcceptanceCriterion(mapping.criterionId(),
                                mapping.description(), mapping.verificationMode(), mapping.judgeRubric(),
                                mapping.judgeOnlyReason()))
                        .toList();
                plannedStages.add(new LoopSpec.StageSpec(stage.objective(), stage.allowedPaths(),
                        stage.forbiddenPaths(), stage.deliverables(), stage.verifiers(), criteria,
                        stage.verificationRuntime(), stage.implementationKind(), stage.workPackageId()));
            }
            DesignerSessionRow session = get(workPackage.designerSessionId());
            LoopSpec plannedContract = new LoopSpec("v2", session.projectId(), workPackage.objective(), "",
                    plannedStages, LoopSpec.Limits.defaults(), null, null, null, null);
            List<String> errors = drafts.assessment(plannedContract, true, false).errors();
            if (!errors.isEmpty()) {
                throw new BadRequestException("COMPILER_PLAN_VERIFIER_INVALID",
                        "Planned verifier blueprint is not executable: " + bounded(String.join("; ", errors), 4_000));
            }
        }
        boundedUtf8(plan.handoffSummary(), MAX_HANDOFF_SUMMARY_LENGTH);
    }

    private void validatePackageCompilationAgainstPlan(PackageCompilationPlanEnvelope plan,
                                                       PackageCompilationEnvelope envelope) {
        if (!plan.status().equals(envelope.status())) throw new BadRequestException("COMPILER_PLAN_STATUS_DRIFT",
                "Final CompiledPackage status differs from the frozen plan");
        if ("DESIGN_INCOMPLETE".equals(plan.status())) {
            if (!plan.designGaps().equals(envelope.designGaps())) {
                throw new BadRequestException("COMPILER_PLAN_GAP_DRIFT",
                        "Final design gaps must match the frozen semantic planning");
            }
            return;
        }
        if (plan.stages().size() != envelope.stages().size()) {
            throw new BadRequestException("COMPILER_PLAN_STAGE_DRIFT",
                    "Final stage count differs from the frozen planning");
        }
        for (int index = 0; index < plan.stages().size(); index++) {
            int stageIndex = index;
            PlannedStage planned = plan.stages().get(index);
            LoopSpec.StageSpec actual = envelope.stages().get(index);
            if (!planned.objective().equals(actual.objective())
                    || !planned.allowedPaths().equals(actual.allowedPaths())
                    || !planned.forbiddenPaths().equals(actual.forbiddenPaths())
                    || !planned.deliverables().equals(actual.deliverables())
                    || planned.implementationKind() != actual.implementationKind()
                    || !planned.workPackageId().equals(actual.workPackageId())) {
                throw new BadRequestException("COMPILER_PLAN_STAGE_DRIFT",
                        "Final stage " + index + " differs from the frozen planning");
            }
            if (plan.contractVersion() >= 2
                    && (!planned.verifiers().equals(actual.verifiers())
                    || !java.util.Objects.equals(planned.verificationRuntime(), actual.verificationRuntime()))) {
                throw new BadRequestException("COMPILER_PLAN_VERIFIER_DRIFT",
                        "Final verifier/runtime contract differs from the executable frozen blueprint for stage "
                                + index);
            }
            List<AcceptanceEvidenceMapping> mappings = plan.evidenceMappings().stream()
                    .filter(item -> item.stageIndex() == stageIndex).toList();
            List<LoopSpec.AcceptanceCriterion> expectedCriteria = mappings.stream().map(item ->
                    new LoopSpec.AcceptanceCriterion(item.criterionId(), item.description(), item.verificationMode(),
                            item.judgeRubric(), item.judgeOnlyReason())).toList();
            if (!expectedCriteria.equals(actual.acceptanceCriteria())) {
                throw new BadRequestException("COMPILER_PLAN_CRITERION_DRIFT",
                        "Final acceptance criteria differ from the frozen evidence mapping for stage " + index);
            }
            for (AcceptanceEvidenceMapping mapping : mappings) {
                boolean covered = actual.verifiers().stream()
                        .anyMatch(verifier -> verifier.criterionIds().contains(mapping.criterionId()));
                if (Set.of("MACHINE", "BOTH").contains(mapping.verificationMode()) && !covered) {
                    throw new BadRequestException("COMPILER_PLAN_EVIDENCE_DRIFT",
                            "Final verifiers do not implement planned evidence for " + mapping.criterionId());
                }
                if (!mapping.testCommand().isEmpty()) {
                    boolean focusedTest = actual.verifiers().stream().anyMatch(verifier ->
                            "PROCESS".equals(verifier.type()) && "TEST".equals(verifier.processPurpose())
                                    && verifier.command().equals(mapping.testCommand())
                                    && verifier.testTargets().containsAll(mapping.testTargets())
                                    && verifier.criterionIds().contains(mapping.criterionId()));
                    if (!focusedTest) throw new BadRequestException("COMPILER_PLAN_TEST_EVIDENCE_DRIFT",
                            "Final focused test differs from planned evidence for " + mapping.criterionId());
                }
            }
        }
        List<CriterionSource> expectedSources = plan.evidenceMappings().stream().map(item ->
                new CriterionSource(item.stageIndex(), item.criterionId(), item.designerExcerpt())).toList();
        if (!expectedSources.equals(envelope.criterionSources())) {
            throw new BadRequestException("COMPILER_PLAN_SOURCE_DRIFT",
                    "Final criterion sources differ from the frozen evidence mapping");
        }
        if (!same(plan.handoffSummary(), envelope.handoffSummary())) {
            throw new BadRequestException("COMPILER_PLAN_HANDOFF_DRIFT",
                    "Final handoff summary differs from the frozen planning");
        }
    }

    private void validateRequirementRefs(List<String> refs, Set<String> valid, Set<String> covered) {
        for (String ref : refs) {
            if (!valid.contains(ref)) throw new BadRequestException("REQUIREMENT_REFERENCE_INVALID",
                    "Unknown requirement segment reference: " + ref);
            covered.add(ref);
        }
    }

    private List<RequirementSegment> segmentRequirements(String requirement) {
        List<RequirementSegment> result = new ArrayList<>();
        String[] paragraphs = requirement.replace("\r\n", "\n").split("\\n\\s*\\n");
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) continue;
            List<String> items = new ArrayList<>();
            StringBuilder prose = new StringBuilder();
            for (String rawLine : trimmed.split("\\n")) {
                String line = rawLine.trim();
                if (line.matches("^(?:[-*+]\\s+|\\d+[.)、]\\s*).+")) {
                    if (!prose.isEmpty()) { items.add(prose.toString().trim()); prose.setLength(0); }
                    items.add(line.replaceFirst("^(?:[-*+]\\s+|\\d+[.)、]\\s*)", "").trim());
                } else {
                    if (!prose.isEmpty()) prose.append(' ');
                    prose.append(line);
                }
            }
            if (!prose.isEmpty()) items.add(prose.toString().trim());
            for (String item : items) if (!item.isBlank()) {
                result.add(new RequirementSegment("RQ-" + (result.size() + 1), item));
            }
        }
        if (result.isEmpty()) throw new BadRequestException("DESIGNER_MESSAGE_REQUIRED", "Requirement text is empty");
        return List.copyOf(result);
    }

    private String decomposerPlanningPrompt(DesignerSessionRow session, ProjectRow project,
                                            DesignRequirementRevisionRow revision, boolean retry) {
        return """
                You are OpenCode Loopper Task Decomposer / 任务拆解器 in the semantic planning turn of a strictly
                read-only Session. Use only read, glob, and grep. Never edit/write files, execute commands, ask
                questions, create tasks, or emit the final TASK_DECOMPOSITION envelope in this turn.

                Think in this fixed order and expose only the bounded planning result, not private chain-of-thought:
                1. Plan one coherent package or 2-6 dependency-ordered vertical business packages.
                2. Map every numbered requirement segment to a global constraint or work package with a short
                   rationale, and explain every inter-package dependency.
                3. Return the structured planning envelope below. Do not mechanically split database/backend/
                   frontend/tests. Use NEEDS_INPUT only for a genuinely missing semantic fact and
                   MULTI_TASK_REQUIRED only for multiple roots, independent releases, or more than six packages.

                Project root: %s
                Designer session: %s
                Requirement revision: R%d%s
                Numbered immutable requirement segments:
                %s

                Complete immutable requirement:
                %s

                %s

                <!-- TASK_DECOMPOSITION_PLAN_JSON_START -->
                Put exactly one complete planning object matching the contract above here.
                <!-- TASK_DECOMPOSITION_PLAN_JSON_END -->

                The markers above are preferred. If your provider cannot preserve HTML comment markers, return
                exactly one bare top-level JSON object, optionally inside one ```json fence, with no prose before
                or after it. Never return multiple JSON objects.
                """.formatted(project.rootPath(), session.id(), revision.revision(),
                retry ? " explicit retry" : "",
                readSegments(revision.requirementSegmentsJson()).stream()
                        .map(segment -> segment.id() + ": " + segment.text())
                        .collect(java.util.stream.Collectors.joining("\n")), revision.requirementText(),
                decompositionPlanningMachineContract());
    }

    private String decompositionPlanningMachineContract() {
        return """
                Strict decomposition planning JSON contract:
                - globalConstraints, workPackages, scopeIn, scopeOut, dependencies, deliverables,
                  acceptanceIntent, requirementRefs, coverageMappings, dependencyEvidence, and designGaps are JSON
                  arrays even when empty or single-item. Entries are objects, never descriptive strings.
                - A global constraint is {"text":"...","requirementRefs":["RQ-1"]}.
                - A work package is {"id":"WP-1","title":"vertical capability","objective":"observable package result","scopeIn":["..."],"scopeOut":["..."],"dependencies":[],"deliverables":["..."],"acceptanceIntent":["..."],"requirementRefs":["RQ-1"]}.
                - A coverage mapping is {"requirementRef":"RQ-1","targetType":"GLOBAL_CONSTRAINT|WORK_PACKAGE","targetId":"GC-1 or WP-1","rationale":"why this target owns the requirement"}. GC-n is the 1-based globalConstraints index.
                - A dependency evidence entry is {"workPackageId":"WP-2","dependsOn":"WP-1","rationale":"concrete prerequisite produced by WP-1"}; emit exactly one for every dependencies entry and none for absent dependencies.
                - DIRECT_DESIGN has exactly one package; DECOMPOSED has 2-6 ordered packages. Both use
                  designGaps:[] and reason:null. NEEDS_INPUT uses no packages and designGaps objects such as
                  {"code":"MISSING_SCOPE","detail":"concrete missing fact"}. Allowed gap codes are
                  MISSING_OBSERVABLE_OUTCOME, MISSING_EXCEPTION_SEMANTICS, MISSING_SCOPE, and
                  MISSING_ACCEPTANCE_INTENT. MULTI_TASK_REQUIRED uses workPackages:[], designGaps:[], and a concrete
                  reason. designGaps entries are never strings, assumptions, or recommendations.

                Canonical two-package planning shape (replace values and requirement refs with frozen facts):
                {"status":"DECOMPOSED","normalizedGoal":"overall observable goal","globalConstraints":[],"workPackages":[{"id":"WP-1","title":"first vertical capability","objective":"first observable result","scopeIn":["first capability"],"scopeOut":["second capability"],"dependencies":[],"deliverables":["first result"],"acceptanceIntent":["first behavior is observable"],"requirementRefs":["RQ-1"]},{"id":"WP-2","title":"second vertical capability","objective":"second observable result","scopeIn":["second capability"],"scopeOut":[],"dependencies":["WP-1"],"deliverables":["integrated result"],"acceptanceIntent":["end-to-end behavior is observable"],"requirementRefs":["RQ-1"]}],"coverageMappings":[{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE","targetId":"WP-1","rationale":"the first package establishes the requested capability"},{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE","targetId":"WP-2","rationale":"the second package completes its integrated behavior"}],"dependencyEvidence":[{"workPackageId":"WP-2","dependsOn":"WP-1","rationale":"WP-2 consumes the first package deliverable"}],"designGaps":[],"reason":null}
                """;
    }

    private String decomposerJsonPrompt(DecompositionPlanEnvelope plan) {
        return """
                The semantic package planning and requirement coverage mapping below passed deterministic validation
                and is now frozen. Generate the final decomposition JSON without redesigning, adding, removing,
                reordering, or paraphrasing any planning decision. Do not emit planning markers in this turn.

                Frozen planning:
                %s

                %s

                <!-- TASK_DECOMPOSITION_JSON_START -->
                Put exactly one final decomposition object matching the frozen planning here.
                <!-- TASK_DECOMPOSITION_JSON_END -->

                The markers above are preferred. If your provider cannot preserve HTML comment markers, return
                exactly one bare top-level JSON object, optionally inside one ```json fence, with no prose before
                or after it. Never return multiple JSON objects.
                """.formatted(write(plan), decompositionMachineContract());
    }

    private String decompositionMachineContract() {
        return """
                Final decomposition JSON contract:
                - The final object contains exactly status, normalizedGoal, globalConstraints, workPackages,
                  designGaps, and reason. It omits coverageMappings and dependencyEvidence because the server already
                  persisted those planning proofs.
                - All collection fields remain JSON arrays. Global constraints and work packages retain the exact
                  object shapes, values, ordering, requirementRefs, and dependencies from the frozen planning.
                - DIRECT_DESIGN/DECOMPOSED use designGaps:[] and reason:null. NEEDS_INPUT uses designGaps objects
                  {"code":"closed code","detail":"concrete missing fact"}, never strings.
                  MULTI_TASK_REQUIRED uses workPackages:[], designGaps:[], and a concrete reason.
                """;
    }

    /** Compatibility prompt for a V22 decomposition already active during a V23 upgrade. */
    private String decomposerPrompt(DesignerSessionRow session, ProjectRow project,
                                    DesignRequirementRevisionRow revision, boolean retry) {
        return """
                You are OpenCode Loopper Task Decomposer / 任务拆解器 in a brand-new strictly read-only Session.
                You may use only read, glob, and grep under the registered project root. Never edit/write files,
                execute commands, ask questions, create tasks, or claim implementation occurred.

                Decide whether this complete requirement is one coherent package (DIRECT_DESIGN), 2-6 dependency-
                ordered vertical business packages (DECOMPOSED), requires explicit user input (NEEDS_INPUT), or
                crosses the single-Task boundary (MULTI_TASK_REQUIRED: more than six packages, multiple project roots,
                or independent release boundaries). Do not mechanically split database/backend/frontend/tests.
                Every numbered requirement segment must be referenced by at least one global constraint or package.
                Package ids are exactly WP-1..WP-n; dependencies point only to earlier ids.

                Project root: %s
                Designer session: %s
                Requirement revision: R%d%s
                Numbered immutable requirement segments:
                %s

                Complete immutable requirement:
                %s

                Return one JSON object between exact markers and no raw JSON elsewhere. If your provider cannot
                preserve HTML comment markers, return exactly one bare top-level JSON object, optionally inside one
                ```json fence, with no prose before or after it:
                <!-- TASK_DECOMPOSITION_JSON_START -->
                {"status":"DIRECT_DESIGN|DECOMPOSED|NEEDS_INPUT|MULTI_TASK_REQUIRED","normalizedGoal":"...","globalConstraints":[{"text":"...","requirementRefs":["RQ-1"]}],"workPackages":[{"id":"WP-1","title":"...","objective":"...","scopeIn":[],"scopeOut":[],"dependencies":[],"deliverables":[],"acceptanceIntent":[],"requirementRefs":["RQ-1"]}],"designGaps":[],"reason":null}
                <!-- TASK_DECOMPOSITION_JSON_END -->
                """.formatted(project.rootPath(), session.id(), revision.revision(), retry ? " explicit retry" : "",
                readSegments(revision.requirementSegmentsJson()).stream()
                        .map(segment -> segment.id() + ": " + segment.text()).collect(java.util.stream.Collectors.joining("\n")),
                revision.requirementText());
    }

    private String decompositionRepairPrompt(TaskDecompositionRow row, String code, String detail) {
        if (blank(row.planningJson())) {
            return """
                    The deterministic server rejected the previous decomposition envelope from a workflow started
                    before structured planning was introduced. Repair the complete envelope without changing the
                    requirement or using NEEDS_INPUT/MULTI_TASK_REQUIRED to escape JSON or validation errors.
                    Repair %d/%d. Error code: %s. Error detail: %s.

                    %s

                    Prefer one complete replacement object between TASK_DECOMPOSITION_JSON_START/END markers. If
                    markers cannot be preserved, return exactly one bare top-level JSON object or one ```json fence
                    with no surrounding prose.
                    """.formatted(row.repairCount(), MAX_DECOMPOSER_REPAIRS, code, safeMessage(detail),
                    decompositionMachineContract());
        }
        DecompositionPlanEnvelope plan = readDecompositionPlan(row.planningJson());
        return """
                The deterministic server rejected the previous decomposition envelope. Repair the complete envelope
                using the already validated frozen planning below. Do not redesign or change to NEEDS_INPUT or
                MULTI_TASK_REQUIRED merely to escape JSON, coverage, dependency, or field errors.
                Repair %d/%d. Error code: %s. Error detail: %s.

                Frozen planning:
                %s

                %s

                Prefer one complete replacement object between TASK_DECOMPOSITION_JSON_START/END markers. If
                markers cannot be preserved, return exactly one bare top-level JSON object or one ```json fence
                with no surrounding prose.
                """.formatted(row.repairCount(), MAX_DECOMPOSER_REPAIRS, code, safeMessage(detail),
                write(plan), decompositionMachineContract());
    }

    private String decompositionPlanningRepairPrompt(TaskDecompositionRow row,
                                                     DesignRequirementRevisionRow revision,
                                                     String code, String detail) {
        return """
                The deterministic server rejected the previous decomposition planning envelope. Repair the complete
                planning result without emitting the final decomposition JSON. Do not use NEEDS_INPUT or
                MULTI_TASK_REQUIRED to escape JSON, coverage, dependency, or field errors.
                Repair %d/%d. Error code: %s. Error detail: %s.

                Numbered immutable requirement segments:
                %s

                %s

                Prefer one complete replacement object between TASK_DECOMPOSITION_PLAN_JSON_START/END markers. If
                markers cannot be preserved, return exactly one bare top-level JSON object or one ```json fence
                with no surrounding prose.
                """.formatted(row.planningRepairCount(), MAX_DECOMPOSER_REPAIRS, code, safeMessage(detail),
                readSegments(revision.requirementSegmentsJson()).stream()
                        .map(segment -> segment.id() + ": " + segment.text())
                        .collect(java.util.stream.Collectors.joining("\n")),
                decompositionPlanningMachineContract());
    }

    private String decomposerTransportRetryPrompt(TaskDecompositionRow row, ProjectRow project,
                                                  DesignRequirementRevisionRow revision) {
        return switch (StructuredModelStep.valueOf(row.workflowStep())) {
            case PLANNING -> decomposerPlanningPrompt(get(row.designerSessionId()), project, revision, true);
            case GENERATING_JSON -> decomposerJsonPrompt(readDecompositionPlan(row.planningJson()));
            case REPAIRING_JSON -> decompositionRepairPrompt(row, row.lastErrorCode(), row.lastErrorDetail());
            case FINAL_JSON -> decomposerPrompt(get(row.designerSessionId()), project, revision, true);
        };
    }

    private String packageDesignerPrompt(DesignerSessionRow session, ProjectRow project,
                                         DesignRequirementRevisionRow revision,
                                         DesignWorkPackageRow workPackage) {
        TaskDecompositionRow decomposition = mapper.findTaskDecompositionByRevision(revision.id()).orElseThrow();
        String prerequisites = mapper.listDesignWorkPackages(revision.id()).stream()
                .filter(item -> strings(workPackage.dependenciesJson()).contains(item.packageId()))
                .map(item -> item.packageId() + ": " + (blank(item.handoffSummary()) ? "无交接摘要" : item.handoffSummary()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return """
                You are OpenCode Loopper Designer / 设计师 for exactly one work package in a new strictly read-only Session.
                You may use read, glob, and grep. Do not edit/write files, execute commands, ask implementation agents,
                create tasks, emit LoopSpec fields/JSON, or redesign other packages.

                Project root: %s
                Complete original requirement R%d:
                %s

                Frozen decomposition plan:
                %s

                Current package %s (only scope to design):
                %s

                Bounded prerequisite handoff summaries:
                %s

                Produce one complete Simplified-Chinese Markdown design no larger than 24 KiB UTF-8. Cover scope and
                non-scope, observable results, exception semantics, affected files/modules, 1-3 dependency-ordered
                stages, delivery details, and acceptance intent. Production Java and its focused Maven/Gradle unit
                test belong in the same stage. Tests are evidence for business behavior, not a meta acceptance item.
                """.formatted(project.rootPath(), revision.revision(), revision.requirementText(),
                decomposition.planJson(), workPackage.packageId(), write(Map.of(
                        "title", workPackage.title(), "objective", workPackage.objective(),
                        "scopeIn", strings(workPackage.scopeInJson()), "scopeOut", strings(workPackage.scopeOutJson()),
                        "deliverables", strings(workPackage.deliverablesJson()),
                        "acceptanceIntent", strings(workPackage.acceptanceIntentJson()),
                        "requirementRefs", strings(workPackage.requirementRefsJson()))),
                prerequisites.isBlank() ? "无" : prerequisites);
    }

    private String packageCompilerPlanningPrompt(ProjectRow project, DesignRequirementRevisionRow revision,
                                                 DesignWorkPackageRow workPackage, String design) {
        return """
                You are OpenCode Loopper LoopSpec Compiler / 规范编译器 in the semantic planning turn for exactly one
                frozen work-package design. This is a strictly read-only Session: use only read, glob, and grep;
                never write files, execute commands, ask questions, create tasks, or emit final StageSpec/verifier
                JSON in this turn.

                Think in this fixed order and expose only the bounded planning result, not private chain-of-thought:
                1. Plan 1-3 coherent, dependency-ordered Stages inside the current package.
                2. Map each observable acceptance criterion to an exact Designer excerpt and a concrete machine/
                   Judge evidence strategy; production Java criteria must name the focused Maven/Gradle test argv
                   and test targets that will prove them in the same Stage.
                3. Return the structured planning envelope below. Do not redesign another package or invent a
                   requirement absent from the frozen design.

                Project root: %s
                Requirement revision: R%d
                Required workPackageId: %s
                Required criterion id prefix: %s-AC-

                %s

                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                Put exactly one complete planning object matching the contract above here.
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->

                Frozen work-package design revision %d:
                %s
                """.formatted(project.rootPath(), revision.revision(), workPackage.packageId(),
                workPackage.packageId(), packageCompilerPlanningMachineContract(workPackage.packageId()),
                workPackage.designRevision(), design);
    }

    private String packageCompilerPlanningMachineContract(String packageId) {
        String criterionId = packageId + "-AC-1";
        return """
                Strict package compilation planning JSON contract:
                - contractVersion must be the number 2. stages, allowedPaths, forbiddenPaths, deliverables,
                  verifiers, evidenceMappings, testCommand, testTargets,
                  and designGaps are JSON arrays even when empty or single-item. Entries are objects, never
                  descriptive command/verifier strings.
                - A planned stage already contains the exact executable verifier blueprints that final JSON must
                  copy: {"objective":"observable stage result","allowedPaths":["src/main/java/**","src/test/java/**"],"forbiddenPaths":[".env"],"deliverables":["implementation and focused test"],"verifiers":[{"type":"PROCESS","command":["mvn","-q","-Dtest=ExampleFocusedTest","test"],"processPurpose":"TEST","testTargets":["ExampleFocusedTest"],"criterionIds":["%s"]}],"verificationRuntime":null,"implementationKind":"JAVA_PRODUCTION|JAVA_TEST_ONLY|NON_JAVA","workPackageId":"%s"}.
                - An evidence mapping is {"stageIndex":0,"criterionId":"%s","description":"observable business result","designerExcerpt":"exact non-empty Designer substring","verificationMode":"MACHINE|JUDGE|BOTH","judgeRubric":"required for JUDGE/BOTH or null","judgeOnlyReason":"required only for JUDGE or null","verifierStrategy":"concrete deterministic proof","testCommand":["mvn","-q","-Dtest=ExampleFocusedTest","test"],"testTargets":["ExampleFocusedTest"]}.
                - Every MACHINE/BOTH mapping must be covered by criterionIds on a BEHAVIOR verifier blueprint in
                  the same planned Stage. Every Stage needs at least one blocking deterministic verifier. PROCESS
                  always uses direct argv: shell launchers (sh/bash/zsh/cmd/powershell), pipes, redirects, && and
                  command strings are forbidden. For a NON_JAVA content self-check, use direct argv such as
                  {"type":"PROCESS","command":["python3","-c","from pathlib import Path; text=Path('README.md').read_text(); assert 'required phrase' in text; print('DOC_CHECK_OK')"],"processPurpose":"SELF_CHECK","outputContains":"DOC_CHECK_OK","criterionIds":["%s"]}; use GIT_DIFF separately for scope and never map criterionIds to GIT_DIFF.
                - JAVA_PRODUCTION puts production code and its focused Maven/Gradle test in the same planned Stage.
                  Every MACHINE/BOTH criterion in that Stage repeats the focused direct-argv testCommand and concrete
                  testTargets it expects the final verifier to implement. Non-test evidence uses empty testCommand/
                  testTargets but still names verifierStrategy. Tests are evidence, never a 'tests pass' criterion.
                - COMPILED has 1-3 stages, at least one evidence mapping per Stage, handoffSummary <=4 KiB UTF-8,
                  and designGaps:[]. DESIGN_INCOMPLETE has stages:[], evidenceMappings:[], and gap objects such as
                  {"code":"MISSING_EXCEPTION_SEMANTICS","detail":"concrete missing design fact"}. Allowed gap codes
                  are MISSING_OBSERVABLE_OUTCOME, MISSING_EXCEPTION_SEMANTICS, MISSING_SCOPE, and
                  MISSING_ACCEPTANCE_INTENT. designGaps entries are never strings.

                Canonical planning envelope:
                {"contractVersion":2,"status":"COMPILED","summary":"planned package summary","stages":[{"objective":"observable stage result","allowedPaths":["src/main/java/**","src/test/java/**"],"forbiddenPaths":[".env"],"deliverables":["production implementation and focused test"],"verifiers":[{"type":"PROCESS","command":["mvn","-q","-Dtest=ExampleFocusedTest","test"],"processPurpose":"TEST","testTargets":["ExampleFocusedTest"],"criterionIds":["%s"]},{"type":"GIT_DIFF","requireChanges":true,"allowedPaths":["src/main/java/**","src/test/java/**"],"forbiddenPaths":[".env"],"forbidDeletes":true}],"verificationRuntime":null,"implementationKind":"JAVA_PRODUCTION","workPackageId":"%s"}],"evidenceMappings":[{"stageIndex":0,"criterionId":"%s","description":"observable business result","designerExcerpt":"exact non-empty Designer substring","verificationMode":"BOTH","judgeRubric":"Confirm behavior matches the frozen design and deterministic evidence.","judgeOnlyReason":null,"verifierStrategy":"focused Maven unit test","testCommand":["mvn","-q","-Dtest=ExampleFocusedTest","test"],"testTargets":["ExampleFocusedTest"]}],"handoffSummary":"bounded dependency handoff summary","designGaps":[]}
                """.formatted(criterionId, packageId, criterionId, criterionId, criterionId,
                packageId, criterionId);
    }

    private String packageCompilerJsonPrompt(DesignerSessionRow session, LoopDraftRow draft,
                                             DesignWorkPackageRow workPackage,
                                             PackageCompilationPlanEnvelope plan) {
        return """
                The Stage planning and acceptance evidence mapping below passed deterministic validation and is now
                frozen. Compile it into the final CompiledPackage JSON. Preserve every Stage field, acceptance field,
                exact Designer excerpt, verifier object, verificationRuntime, test argv/target, handoff, ordering,
                and status. The verifier/runtime blueprints are already executable and must be copied byte-for-field;
                do not redesign, add, remove, or paraphrase planning decisions.

                Required workPackageId: %s
                Read-only draft defaults preserved later by server aggregation: %s
                Frozen planning:
                %s

                %s

                <!-- LOOPSPEC_COMPILATION_JSON_START -->
                Put exactly one complete replacement object matching the frozen plan and machine contract here.
                <!-- LOOPSPEC_COMPILATION_JSON_END -->
                """.formatted(workPackage.packageId(), draft.specJson(), write(plan),
                packageCompilerMachineContract(workPackage.packageId()));
    }

    /** Compatibility prompt for a V22 package compilation already active during a V23 upgrade. */
    private String packageCompilerPrompt(DesignerSessionRow session, ProjectRow project, LoopDraftRow draft,
                                         DesignRequirementRevisionRow revision, DesignWorkPackageRow workPackage,
                                         String design) {
        return """
                You are OpenCode Loopper LoopSpec Compiler / 规范编译器 for one frozen work-package design in a new
                strictly read-only Session. You may use read, glob, and grep to verify build/test conventions. Never
                edit/write files, execute commands, ask questions, create tasks, compile other packages, or add absent
                business requirements.

                Project root: %s
                Required workPackageId: %s
                Required criterion id prefix: %s-AC-
                Read-only draft defaults to preserve during later server aggregation: %s

                COMPILED returns 1-3 complete StageSpec objects only (not a LoopSpec), a short summary, an exact
                Designer excerpt for every criterion, and a dependency handoffSummary <=4 KiB UTF-8. Every StageSpec
                sets workPackageId=%s. DESIGN_INCOMPLETE is only for the closed semantic gap codes. JSON/schema/
                validator/coverage uncertainty must be repaired as COMPILED, not escaped as DESIGN_INCOMPLETE.
                Existing implementationKind, direct PROCESS, behavior coverage, Java focused-unit-test, runtime, and
                blocking deterministic verifier rules all apply.

                %s

                <!-- LOOPSPEC_COMPILATION_JSON_START -->
                Put exactly one complete replacement object matching the canonical envelope above here.
                <!-- LOOPSPEC_COMPILATION_JSON_END -->

                Frozen package design revision %d for requirement R%d:
                %s
                """.formatted(project.rootPath(), workPackage.packageId(), workPackage.packageId(), draft.specJson(),
                workPackage.packageId(), packageCompilerMachineContract(workPackage.packageId()),
                workPackage.designRevision(), revision.revision(), design);
    }

    private String packageCompilerRepairPrompt(LoopSpecCompilationRow compilation, String code, String detail) {
        if (blank(compilation.planningJson())) {
            return """
                    The deterministic server rejected the previous CompiledPackage envelope from a workflow started
                    before structured planning was introduced. Repair the complete package envelope without
                    redesigning, asking questions, or using DESIGN_INCOMPLETE to escape JSON or validation errors.
                    Repair %d/%d. Error code: %s. Error detail: %s.

                    %s

                    Return one replacement object between LOOPSPEC_COMPILATION_JSON_START/END markers.
                    """.formatted(compilation.repairCount(), MAX_COMPILER_REPAIRS, code, safeMessage(detail),
                    packageCompilerMachineContract(compilation.workPackageId()));
        }
        PackageCompilationPlanEnvelope plan = readPackageCompilationPlan(compilation.planningJson());
        return """
                The deterministic server rejected the previous CompiledPackage envelope. Repair the entire package
                envelope using the validated frozen planning below. Do not return a full LoopSpec, redesign, ask
                questions, inspect another package, or use DESIGN_INCOMPLETE to escape format/validation errors.
                Repair %d/%d. Error code: %s. Error detail: %s.
                The replacement must follow this complete machine contract; do not infer Java record shapes from
                the error text and do not replace an object or array with a descriptive string:

                Frozen planning:
                %s

                %s

                Return one replacement object between LOOPSPEC_COMPILATION_JSON_START/END markers.
                """.formatted(compilation.repairCount(), MAX_COMPILER_REPAIRS, code, safeMessage(detail),
                write(plan), packageCompilerMachineContract(compilation.workPackageId()));
    }

    private String packageCompilerPlanningRepairPrompt(LoopSpecCompilationRow compilation,
                                                       DesignWorkPackageRow workPackage,
                                                       String design, String code, String detail) {
        return """
                The deterministic server rejected the previous Stage/evidence planning envelope. Repair the entire
                planning result without emitting final StageSpec/verifier JSON. Do not redesign, inspect another
                package, or use DESIGN_INCOMPLETE to escape format, mapping, or field errors.
                Repair %d/%d. Error code: %s. Error detail: %s.

                %s

                Return one replacement object between LOOPSPEC_COMPILATION_PLAN_JSON_START/END markers.

                Frozen work-package design:
                %s
                """.formatted(compilation.planningRepairCount(), MAX_COMPILER_REPAIRS, code, safeMessage(detail),
                packageCompilerPlanningMachineContract(workPackage.packageId()), design);
    }

    private String packageCompilerTransportRetryPrompt(LoopSpecCompilationRow row, DesignerSessionRow session,
                                                       ProjectRow project, DesignRequirementRevisionRow revision,
                                                       DesignWorkPackageRow workPackage, String design) {
        return switch (StructuredModelStep.valueOf(row.workflowStep())) {
            case PLANNING -> packageCompilerPlanningPrompt(project, revision, workPackage, design);
            case GENERATING_JSON -> packageCompilerJsonPrompt(session, drafts.get(session.loopDraftId()),
                    workPackage, readPackageCompilationPlan(row.planningJson()));
            case REPAIRING_JSON -> packageCompilerRepairPrompt(row, row.lastErrorCode(), row.lastErrorDetail());
            case FINAL_JSON -> packageCompilerPrompt(session, project, drafts.get(session.loopDraftId()),
                    revision, workPackage, design);
        };
    }

    private String packageCompilerMachineContract(String packageId) {
        String criterionId = packageId + "-AC-1";
        return """
                Strict JSON type contract (property names and JSON types are exact):
                - stages, allowedPaths, forbiddenPaths, deliverables, verifiers, acceptanceCriteria,
                  criterionSources, designGaps, command, criterionIds, testTargets, assertions, and startCommand are
                  JSON arrays even when they contain only one item. Never emit a command or verifier as a string.
                - stages[*].verifiers[*] is a VerifierSpec JSON object. A PROCESS verifier uses
                  {"type":"PROCESS","command":["mvn","-q","-Dtest=ExampleFocusedTest","test"],"processPurpose":"TEST","testTargets":["ExampleFocusedTest"],"criterionIds":["%s"]}.
                  processPurpose is BUILD, TEST, or SELF_CHECK. TEST has non-empty testTargets; SELF_CHECK has
                  outputContains. command is direct argv and never one shell command string.
                - stages[*].acceptanceCriteria[*] is
                  {"id":"%s","description":"observable business result","verificationMode":"MACHINE|JUDGE|BOTH","judgeRubric":"required for JUDGE/BOTH or null","judgeOnlyReason":"required only for JUDGE or null"}.
                - verificationRuntime is null for PROCESS-only stages. It is never a test framework name such as
                  MAVEN_JUNIT5. Only an HTTP_STATUS, JSON_PATH, or BROWSER stage that starts its own service uses
                  {"startCommand":["java","-jar","app.jar","--server.port={{LOOPPER_PORT}}"],"readiness":{"path":"/actuator/health","expectedStatus":200,"jsonPath":"$.status","expectedValue":"UP","matchMode":"EXACT"},"startupTimeoutSeconds":60,"shutdownTimeoutSeconds":10}.
                - Other supported verifier object shapes are:
                  GIT_DIFF {"type":"GIT_DIFF","requireChanges":true,"allowedPaths":["src/**"],"forbiddenPaths":[".env"],"forbidDeletes":true};
                  HTTP_STATUS {"type":"HTTP_STATUS","url":"http://127.0.0.1:{{LOOPPER_PORT}}/path","httpMethod":"GET","expectedStatus":200,"criterionIds":["%s"]};
                  JSON_PATH adds jsonPath, expectedValue, and matchMode; FILE_CONTENT uses path, expectedContent,
                  matchMode, and criterionIds; FILE_HASH uses path, expectedSha256, and criterionIds;
                  DATABASE_QUERY uses path, sql, expectedRowCount, and criterionIds; BROWSER uses url,
                  criterionIds, and assertion objects {"type":"EXISTS|VISIBLE|TEXT_CONTAINS|COUNT|ATTRIBUTE_EQUALS","selector":"...","value":"... or null","attribute":"... or null","expectedCount":1}.
                  FILE_NOT_EXISTS, JUNIT_XML, and legacy advisory FILE_EXISTS use a path and cannot cover behavior.
                - implementationKind is exactly JAVA_PRODUCTION, JAVA_TEST_ONLY, or NON_JAVA. JAVA_PRODUCTION puts
                  production Java and its focused Maven/Gradle PROCESS TEST in the same stage, and that TEST's
                  criterionIds covers every MACHINE/BOTH criterion in the stage.
                - Every stage sets workPackageId to "%s". Criterion ids are unique and use %s-AC-n. Every criterion
                  has one criterionSources object {"stageIndex":0,"criterionId":"%s","excerpt":"exact non-empty Designer substring"}.
                - COMPILED uses designGaps:[]. DESIGN_INCOMPLETE uses stages:[], criterionSources:[], and one or more
                  objects such as {"code":"MISSING_OBSERVABLE_OUTCOME","detail":"concrete missing design fact"};
                  allowed codes are MISSING_OBSERVABLE_OUTCOME, MISSING_EXCEPTION_SEMANTICS, MISSING_SCOPE, and
                  MISSING_ACCEPTANCE_INTENT. designGaps entries are never strings.

                Canonical COMPILED envelope for a JAVA_PRODUCTION stage (copy its JSON types and complete nesting;
                replace example values with facts from the frozen design, and add up to three stages when needed):
                {"status":"COMPILED","summary":"compiled package summary","stages":[{"objective":"observable stage result","allowedPaths":["src/main/java/**","src/test/java/**"],"forbiddenPaths":[".env"],"deliverables":["production implementation and focused test"],"verifiers":[{"type":"PROCESS","command":["mvn","-q","-Dtest=ExampleFocusedTest","test"],"processPurpose":"TEST","testTargets":["ExampleFocusedTest"],"criterionIds":["%s"]},{"type":"GIT_DIFF","requireChanges":true,"allowedPaths":["src/main/java/**","src/test/java/**"],"forbiddenPaths":[".env"],"forbidDeletes":true}],"acceptanceCriteria":[{"id":"%s","description":"observable business result","verificationMode":"BOTH","judgeRubric":"Confirm the implemented behavior matches the frozen design and deterministic test evidence.","judgeOnlyReason":null}],"verificationRuntime":null,"implementationKind":"JAVA_PRODUCTION","workPackageId":"%s"}],"criterionSources":[{"stageIndex":0,"criterionId":"%s","excerpt":"exact non-empty Designer substring"}],"handoffSummary":"bounded dependency handoff summary","designGaps":[]}
                """.formatted(criterionId, criterionId, criterionId, packageId, packageId, criterionId,
                criterionId, criterionId, packageId, criterionId);
    }

    private String designerPrompt(DesignerSessionRow session, ProjectRow project, String message) {
        return """
                You are OpenCode Loopper Designer / 设计师 in strictly read-only advisory mode.
                You may use read, glob, and grep to inspect the registered project. Do not edit or write files,
                run commands, create tasks, or claim implementation has happened.

                Registered project root: %s
                Designer session id: %s
                Bound draft id: %s

                Produce one complete, replacement-quality Markdown design in Simplified Chinese. Do not emit
                LoopSpec JSON, schema fields, hidden markers, or a machine payload. Include implementation scope,
                observable business results, exception semantics, affected modules/files, dependency-ordered stages,
                acceptance intent and exact validation commands when evidenced. Non-trivial work should normally use
                2-6 independently deliverable stages; an atomic change may use one stage with a stated reason.
                Every stage must be coherent and immediately verifiable. Do not postpone all behavior checks to a
                final test stage. If a stage adds or changes production Java, put its focused Maven/Gradle unit test
                in the same stage and describe which business acceptance behavior that test proves. A statement such
                as 'all tests pass' is evidence, not a standalone business acceptance item. Include Mermaid for
                multi-step workflows. Preserve identifiers, commands, paths, and enum literals exactly.

                User request:
                %s
                """.formatted(project.rootPath(), session.id(), session.loopDraftId(), message);
    }

    private String compilerPrompt(DesignerSessionRow session, ProjectRow project, LoopDraftRow draft, String design) {
        return """
                You are OpenCode Loopper LoopSpec Compiler / 规范编译器 in a new strictly read-only Session.
                You compile a frozen Designer Markdown document into machine LoopSpec; you do not redesign it.
                You may use read, glob, and grep to verify build files and test conventions. Do not edit/write files,
                execute commands, ask questions, create tasks, or add business requirements absent from the design.

                Project root: %s
                Required projectId: %s
                Draft schema/version context (read-only):
                %s

                Return exactly one JSON object between the exact markers below and no raw LoopSpec elsewhere.
                Status COMPILED requires loopSpec, a short summary, and one criterionSources entry for every
                stage acceptance criterion. Each entry has stageIndex, criterionId, and excerpt; excerpt must be an
                exact non-empty substring of the frozen design. Status DESIGN_INCOMPLETE is allowed only when the
                design lacks business semantics and requires designGaps using only these codes:
                MISSING_OBSERVABLE_OUTCOME, MISSING_EXCEPTION_SEMANTICS, MISSING_SCOPE, MISSING_ACCEPTANCE_INTENT.
                Never use DESIGN_INCOMPLETE for malformed JSON, schema uncertainty, invalid validators, or coverage errors.

                For v2 every stage must set implementationKind to JAVA_PRODUCTION, JAVA_TEST_ONLY, or NON_JAVA.
                JAVA_PRODUCTION requires a non-skipped focused Maven/Gradle PROCESS TEST with concrete testTargets,
                and every MACHINE/BOTH business criterion must be mapped to that focused test through criterionIds.
                Tests are evidence for business criteria, never a separate 'tests pass' criterion. PROCESS is direct
                argv, never shell. Every v2 PROCESS declares processPurpose. Every stage has at least one blocking
                deterministic verifier. GIT_DIFF is scope only; FILE_EXISTS is advisory; build/lint/typecheck are not
                behavior. Use JUDGE only when deterministic proof is genuinely unreliable and explain why.

                Required envelope shape:
                <!-- LOOPSPEC_COMPILATION_JSON_START -->
                ```json
                {"status":"COMPILED","summary":"...","loopSpec":{"schemaVersion":"v2","projectId":"%s","goal":"...","context":"...","stages":[{"objective":"...","allowedPaths":[],"forbiddenPaths":[],"deliverables":[],"implementationKind":"NON_JAVA","acceptanceCriteria":[{"id":"AC-1","description":"...","verificationMode":"MACHINE"}],"verifiers":[]}],"limits":{}},"criterionSources":[{"stageIndex":0,"criterionId":"AC-1","excerpt":"exact Designer text"}],"designGaps":[]}
                ```
                <!-- LOOPSPEC_COMPILATION_JSON_END -->

                Frozen Designer Markdown revision %d:
                %s
                """.formatted(project.rootPath(), session.projectId(), draft.specJson(),
                session.projectId(), session.designRevision(), design);
    }

    private String compilerRepairPrompt(LoopSpecCompilationRow compilation, String code, String detail) {
        return """
                The deterministic server validator rejected the previous compiler envelope.
                Repair the complete compilation envelope using only the same frozen Designer document and prior
                read-only evidence. Do not redesign, ask questions, inspect additional scope, execute commands, or
                return DESIGN_INCOMPLETE to escape JSON/schema/verifier/coverage errors.

                Repair %d/%d
                Error code: %s
                Error detail: %s

                Return one complete replacement JSON object between
                <!-- LOOPSPEC_COMPILATION_JSON_START --> and <!-- LOOPSPEC_COMPILATION_JSON_END -->.
                """.formatted(compilation.repairCount(), MAX_COMPILER_REPAIRS, code, safeMessage(detail));
    }

    private String redesignPrompt(String gaps) {
        return """
                The independent LoopSpec Compiler could not compile the previous frozen design because required
                business semantics were missing. Produce a complete replacement Markdown design, not a patch or
                commentary about the old design. Do not emit LoopSpec JSON or hidden machine markers. Preserve the
                original user goal, but explicitly fill every listed gap with observable results, exception semantics,
                scope, and acceptance intent. Production Java changes must include focused Maven/Gradle unit-test
                evidence mapped to the business behavior in the same stage.

                Design gaps:
                %s
                """.formatted(gaps);
    }

    private String designerMarkdown(String output) {
        if (blank(output)) return "";
        return LEGACY_DESIGNER_PAYLOAD.matcher(output).replaceAll("").trim();
    }

    private DesignerMessageRow latestDesign(String sessionId) {
        return mapper.listDesignerMessages(sessionId).stream()
                .filter(message -> DesignerActor.DESIGNER.name().equals(message.actor()))
                .filter(message -> "PERSISTED".equals(message.deliveryState()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new ConflictException("DESIGN_SOURCE_MISSING",
                        "No frozen Designer Markdown is available"));
    }

    private void requireBoundDraft(DesignerSessionRow session) {
        if (blank(session.loopDraftId())) throw new ConflictException("DESIGNER_DRAFT_NOT_BOUND",
                "Designer session is not bound to a LoopSpec draft");
    }

    private void requireProject(DesignerSessionRow session, LoopSpec spec) {
        if (spec == null || !session.projectId().equals(spec.projectId())) {
            throw new BadRequestException("LOOPSPEC_PROJECT_MISMATCH",
                    "LoopSpec projectId must match the Designer session projectId");
        }
    }

    private DesignerSessionRow requireRunningDesigner(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        if (!DesignerSessionState.RUNNING.name().equals(session.state())
                || !Set.of(DesignWorkflowPhase.DESIGNING.name(), DesignWorkflowPhase.REDESIGNING.name())
                .contains(session.workflowPhase()) || blank(session.externalSessionId())) {
            throw new ConflictException("DESIGNER_QUESTION_UNAVAILABLE",
                    "Designer session has no running Designer question to answer");
        }
        return session;
    }

    private OpenCodeClient.OpenCodeSession designerRemote(DesignerSessionRow session) {
        return new OpenCodeClient.OpenCodeSession(session.externalSessionId(),
                Path.of(projects.get(session.projectId()).rootPath()));
    }

    private boolean reusableDesigner(DesignerSessionRow session) {
        return !blank(session.externalSessionId())
                && !DesignerSessionState.SESSION_ERROR.name().equals(session.state());
    }

    private OpenCodeClient.PendingQuestion pending(OpenCodeClient.OpenCodeSession remote, String questionId) {
        if (blank(questionId)) throw new BadRequestException("QUESTION_ID_REQUIRED", "Question id is required");
        try {
            return openCode.pendingQuestions(remote).stream().filter(question -> questionId.equals(question.id()))
                    .findFirst().orElseThrow(() -> new NotFoundException(
                            "Pending question not found for this Designer Session: " + questionId));
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
    }

    private PendingQuestion question(OpenCodeClient.PendingQuestion pending) {
        return new PendingQuestion(pending.id(), pending.questions().stream().map(prompt -> new QuestionPrompt(
                prompt.question(), prompt.header(), prompt.options().stream()
                .map(option -> new QuestionOption(option.label(), option.description())).toList(),
                prompt.multiple(), prompt.custom())).toList());
    }

    private List<List<String>> validateAnswers(OpenCodeClient.PendingQuestion pending, List<List<String>> answers) {
        if (answers == null || answers.size() != pending.questions().size()) {
            throw new BadRequestException("QUESTION_ANSWERS_INVALID",
                    "Answers must contain one entry for every question");
        }
        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < pending.questions().size(); index++) {
            OpenCodeClient.QuestionPrompt prompt = pending.questions().get(index);
            List<String> answer = answers.get(index) == null ? List.of() : answers.get(index);
            List<String> normalized = answer.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::trim).distinct().toList();
            if (normalized.isEmpty()) throw new BadRequestException("QUESTION_ANSWER_REQUIRED",
                    "Every question requires an answer");
            if (!prompt.multiple() && normalized.size() > 1) {
                throw new BadRequestException("QUESTION_ANSWER_MULTIPLE_FORBIDDEN",
                        "This question accepts only one answer");
            }
            if (!prompt.custom()) {
                List<String> labels = prompt.options().stream().map(OpenCodeClient.QuestionOption::label).toList();
                if (!labels.containsAll(normalized)) throw new BadRequestException("QUESTION_CUSTOM_ANSWER_FORBIDDEN",
                        "This question only accepts listed options");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private DesignRequirementRevisionRow currentRequirement(String sessionId) {
        return mapper.findCurrentDesignRequirementRevision(sessionId)
                .orElseThrow(() -> new ConflictException("DESIGN_REQUIREMENT_REVISION_MISSING",
                        "No frozen requirement revision exists for this Designer session"));
    }

    private DesignRequirementRevisionRow getRequirement(String id) {
        return mapper.findDesignRequirementRevision(id)
                .orElseThrow(() -> new NotFoundException("Design requirement revision not found: " + id));
    }

    private TaskDecompositionRow getDecomposition(String id) {
        return mapper.findTaskDecomposition(id)
                .orElseThrow(() -> new NotFoundException("Task decomposition not found: " + id));
    }

    private DesignWorkPackageRow getWorkPackage(String id) {
        return mapper.findDesignWorkPackage(id)
                .orElseThrow(() -> new NotFoundException("Design work package not found: " + id));
    }

    private DesignWorkPackageRow requireCurrentPackage(DesignerSessionRow session, String packageId) {
        if (blank(packageId)) throw new BadRequestException("WORK_PACKAGE_ID_REQUIRED", "Work package id is required");
        DesignWorkPackageRow row = mapper.findLatestDesignWorkPackage(session.id(), packageId)
                .orElseThrow(() -> new NotFoundException("Design work package not found: " + packageId));
        DesignRequirementRevisionRow revision = currentRequirement(session.id());
        if (!revision.id().equals(row.requirementRevisionId())) {
            throw new ConflictException("WORK_PACKAGE_REVISION_STALE",
                    "The requested work package belongs to a superseded requirement revision");
        }
        return row;
    }

    private DesignWorkPackageRow recoverableWorkPackage(DesignerSessionRow session, boolean compiler) {
        List<DesignWorkPackageRow> packages = mapper.listDesignWorkPackages(currentRequirement(session.id()).id());
        if (!blank(session.activeWorkPackageId())) {
            return requireCurrentPackage(session, session.activeWorkPackageId());
        }
        return packages.stream().filter(row -> Set.of(DesignWorkPackageState.WAITING_INPUT.name(),
                        DesignWorkPackageState.FAILED.name(), compiler ? DesignWorkPackageState.COMPILING.name()
                                : DesignWorkPackageState.DESIGNING.name()).contains(row.state()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new ConflictException("WORK_PACKAGE_RECOVERY_UNAVAILABLE",
                        "No failed or active work package is available for recovery"));
    }

    private DesignerMessageRow designMessage(DesignWorkPackageRow workPackage) {
        if (blank(workPackage.designMessageId())) throw new ConflictException("DESIGN_SOURCE_MISSING",
                "The work package has no frozen Designer Markdown");
        return mapper.listDesignerMessages(workPackage.designerSessionId()).stream()
                .filter(message -> workPackage.designMessageId().equals(message.id()))
                .findFirst().orElseThrow(() -> new ConflictException("DESIGN_SOURCE_MISSING",
                        "The frozen package design message no longer exists"));
    }

    private boolean consumeModelCall(DesignerSessionRow session, DesignRequirementRevisionRow input, String code) {
        DesignRequirementRevisionRow revision = getRequirement(input.id());
        if (revision.modelCallsUsed() >= revision.maxModelCalls()) {
            String detail = "Requirement revision R" + revision.revision() + " exhausted its "
                    + revision.maxModelCalls() + " automatic model calls";
            DesignWorkPackageRow waitingPackage = null;
            DesignerSessionRow currentSession = get(session.id());
            if (!blank(currentSession.activeWorkPackageId())) {
                DesignWorkPackageRow currentPackage = mapper.findLatestDesignWorkPackage(
                        currentSession.id(), currentSession.activeWorkPackageId()).orElse(null);
                if (currentPackage != null && Set.of(DesignWorkPackageState.DESIGNING.name(),
                        DesignWorkPackageState.COMPILING.name(), DesignWorkPackageState.VALIDATING.name())
                        .contains(currentPackage.state())) {
                    waitingPackage = updateWorkPackage(currentPackage, DesignWorkPackageState.WAITING_INPUT,
                            currentPackage.designerExternalSessionId(), "MODEL_CALL_LIMIT",
                            currentPackage.designMessageId(), currentPackage.designRevision(),
                            currentPackage.redesignCount(), currentPackage.designerTransportRetryCount(),
                            currentPackage.compilerSummary(), currentPackage.handoffSummary(), code, detail);
                    abortQuietly(currentPackage.designerExternalSessionId(), currentSession.projectId());
                }
                mapper.findLatestLoopSpecCompilationForPackage(currentSession.id(),
                        currentSession.activeWorkPackageId()).ifPresent(compilation -> {
                    if (Set.of(LoopSpecCompilationState.PENDING_HANDOFF.name(),
                            LoopSpecCompilationState.RUNNING.name()).contains(compilation.state())) {
                        abortQuietly(compilation.externalSessionId(), currentSession.projectId());
                        updateCompilation(compilation, LoopSpecCompilationState.SESSION_ERROR,
                                compilation.externalSessionId(), "MODEL_CALL_LIMIT", compilation.repairCount(),
                                code, detail, currentSession.projectId(), compilation.compiledPackageJson());
                    }
                });
            } else {
                mapper.findTaskDecompositionByRevision(revision.id()).ifPresent(decomposition -> {
                    if (Set.of(TaskDecompositionState.PENDING_HANDOFF.name(), TaskDecompositionState.RUNNING.name())
                            .contains(decomposition.state())) {
                        abortQuietly(decomposition.externalSessionId(), currentSession.projectId());
                        updateDecomposition(decomposition, TaskDecompositionState.SESSION_ERROR,
                                decomposition.resultType(), decomposition.normalizedGoal(),
                                decomposition.globalConstraintsJson(), decomposition.planJson(),
                                decomposition.externalSessionId(), "MODEL_CALL_LIMIT", decomposition.repairCount(),
                                decomposition.transportRetryCount(), code, detail);
                    }
                });
            }
            waitForDesignInput(currentSession, revision, waitingPackage, code, detail);
            return false;
        }
        updateRequirement(revision, DesignRequirementRevisionState.valueOf(revision.state()),
                revision.modelCallsUsed() + 1);
        return true;
    }

    private DesignRequirementRevisionRow reactivateRequirement(DesignRequirementRevisionRow revision) {
        if (DesignRequirementRevisionState.WAITING_INPUT.name().equals(revision.state())) {
            return updateRequirement(revision, DesignRequirementRevisionState.ACTIVE, revision.modelCallsUsed());
        }
        if (!DesignRequirementRevisionState.ACTIVE.name().equals(revision.state())) {
            throw new ConflictException("DESIGN_REQUIREMENT_REVISION_NOT_RECOVERABLE",
                    "Only the current active or waiting requirement revision can be retried");
        }
        return revision;
    }

    private DesignRequirementRevisionRow updateRequirement(DesignRequirementRevisionRow row,
                                                            DesignRequirementRevisionState state,
                                                            int modelCallsUsed) {
        DesignRequirementRevisionRow updated = new DesignRequirementRevisionRow(row.id(), row.designerSessionId(),
                row.revision(), row.sourceMessageId(), row.requirementText(), row.requirementSegmentsJson(),
                row.sourceDraftVersion(), state.name(), modelCallsUsed, row.maxModelCalls(), row.createdAt(), now(),
                row.version());
        if (row.state().equals(updated.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateDesignRequirementRevision(updated),
                    () -> new ConflictException("DESIGN_REQUIREMENT_REVISION_CONFLICT",
                            "Requirement revision was updated concurrently"));
        } else {
            DesignerSessionRow session = get(row.designerSessionId());
            lifecycle.transition(requirementSubject(updated, session.projectId()), row.state(), updated.state(), null,
                    Map.of("revision", row.revision()), () -> mapper.updateDesignRequirementRevision(updated),
                    () -> new ConflictException("DESIGN_REQUIREMENT_REVISION_CONFLICT",
                            "Requirement revision was updated concurrently"));
        }
        return getRequirement(row.id());
    }

    private TaskDecompositionRow updateDecomposition(TaskDecompositionRow row, TaskDecompositionState state,
                                                     String resultType, String normalizedGoal,
                                                     String globalConstraintsJson, String planJson,
                                                     String externalSessionId, String externalSessionState,
                                                     int repairCount, int transportRetryCount,
                                                     String errorCode, String errorDetail) {
        return updateDecomposition(row, state, resultType, normalizedGoal, globalConstraintsJson, planJson,
                externalSessionId, externalSessionState, repairCount, transportRetryCount, errorCode, errorDetail,
                StructuredModelStep.valueOf(row.workflowStep()), row.planningJson(), row.planningRepairCount());
    }

    private TaskDecompositionRow updateDecomposition(TaskDecompositionRow row, TaskDecompositionState state,
                                                     String resultType, String normalizedGoal,
                                                     String globalConstraintsJson, String planJson,
                                                     String externalSessionId, String externalSessionState,
                                                     int repairCount, int transportRetryCount,
                                                     String errorCode, String errorDetail,
                                                     StructuredModelStep workflowStep, String planningJson) {
        return updateDecomposition(row, state, resultType, normalizedGoal, globalConstraintsJson, planJson,
                externalSessionId, externalSessionState, repairCount, transportRetryCount, errorCode, errorDetail,
                workflowStep, planningJson, row.planningRepairCount());
    }

    private TaskDecompositionRow updateDecomposition(TaskDecompositionRow row, TaskDecompositionState state,
                                                     String resultType, String normalizedGoal,
                                                     String globalConstraintsJson, String planJson,
                                                     String externalSessionId, String externalSessionState,
                                                     int repairCount, int transportRetryCount,
                                                     String errorCode, String errorDetail,
                                                     StructuredModelStep workflowStep, String planningJson,
                                                     int planningRepairCount) {
        TaskDecompositionRow updated = new TaskDecompositionRow(row.id(), row.designerSessionId(),
                row.requirementRevisionId(), state.name(), resultType, normalizedGoal,
                globalConstraintsJson == null ? "[]" : globalConstraintsJson,
                planJson == null ? "{}" : planJson, externalSessionId, externalSessionState,
                repairCount, transportRetryCount, row.sourceDraftVersion(), errorCode, errorDetail,
                row.createdAt(), now(), row.version(), workflowStep.name(), planningJson, planningRepairCount);
        DesignerSessionRow session = get(row.designerSessionId());
        if (row.state().equals(updated.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateTaskDecomposition(updated),
                    () -> new ConflictException("TASK_DECOMPOSITION_VERSION_CONFLICT",
                            "Task decomposition was updated concurrently"));
        } else {
            lifecycle.transition(decompositionSubject(updated, session.projectId()), row.state(), updated.state(), null,
                    Map.of(), () -> mapper.updateTaskDecomposition(updated),
                    () -> new ConflictException("TASK_DECOMPOSITION_VERSION_CONFLICT",
                            "Task decomposition was updated concurrently"));
        }
        return getDecomposition(row.id());
    }

    private DesignWorkPackageRow updateWorkPackage(DesignWorkPackageRow row, DesignWorkPackageState state,
                                                    String externalSessionId, String externalSessionState,
                                                    String designMessageId, int designRevision, int redesignCount,
                                                    int transportRetryCount, String compilerSummary,
                                                    String handoffSummary, String errorCode, String errorDetail) {
        DesignWorkPackageRow updated = new DesignWorkPackageRow(row.id(), row.designerSessionId(),
                row.requirementRevisionId(), row.decompositionId(), row.packageId(), row.ordinal(), row.title(),
                row.objective(), row.scopeInJson(), row.scopeOutJson(), row.dependenciesJson(), row.deliverablesJson(),
                row.acceptanceIntentJson(), row.requirementRefsJson(), state.name(), externalSessionId,
                externalSessionState, designMessageId, designRevision, redesignCount, transportRetryCount,
                compilerSummary, handoffSummary, errorCode, errorDetail, row.createdAt(), now(), row.version());
        DesignerSessionRow session = get(row.designerSessionId());
        if (row.state().equals(updated.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateDesignWorkPackage(updated),
                    () -> new ConflictException("DESIGN_WORK_PACKAGE_VERSION_CONFLICT",
                            "Design work package was updated concurrently"));
        } else {
            lifecycle.transition(workPackageSubject(updated, session.projectId()), row.state(), updated.state(), null,
                    Map.of("packageId", row.packageId()), () -> mapper.updateDesignWorkPackage(updated),
                    () -> new ConflictException("DESIGN_WORK_PACKAGE_VERSION_CONFLICT",
                            "Design work package was updated concurrently"));
        }
        return getWorkPackage(row.id());
    }

    private boolean isCurrent(DesignerSessionRow session, DesignRequirementRevisionRow revision) {
        return session.currentRequirementRevision() != null
                && session.currentRequirementRevision() == revision.revision()
                && !DesignRequirementRevisionState.SUPERSEDED.name().equals(revision.state());
    }

    private void requireDraftUnchanged(DesignerSessionRow session, long expectedVersion) {
        LoopDraftRow draft = drafts.get(session.loopDraftId());
        if (draft.version() != expectedVersion) {
            throw new ConflictException("DESIGNER_DRAFT_CHANGED",
                    "The bound draft changed after the complete requirement revision was frozen");
        }
    }

    private void abortQuietly(String externalSessionId, String projectId) {
        if (blank(externalSessionId)) return;
        try {
            openCode.abort(new OpenCodeClient.OpenCodeSession(externalSessionId,
                    Path.of(projects.get(projectId).rootPath())));
        } catch (RuntimeException ignored) { }
    }

    private LoopSpec.Limits safeAggregateLimits(LoopSpec.Limits base,
                                                List<DesignWorkPackageRow> packages,
                                                List<PackageCompilationEnvelope> compiled) {
        int minimumAttempts = 0;
        long minimumDuration = 0;
        for (int index = 0; index < packages.size(); index++) {
            int stageCount = compiled.get(index).stages().size();
            int packageLimit = Math.min(stageCount * base.maxStageAttempts(), stageCount + 2);
            minimumAttempts += packageLimit;
            int maxVerifiers = compiled.get(index).stages().stream()
                    .mapToInt(stage -> stage.verifiers().size()).max().orElse(0);
            minimumDuration = Math.addExact(minimumDuration,
                    Math.addExact(Math.multiplyExact((long) packageLimit, base.attemptTimeoutSeconds()),
                            Math.multiplyExact((long) maxVerifiers, base.verifierTimeoutSeconds())));
        }
        minimumDuration = Math.addExact(minimumDuration, Math.multiplyExact(2L, base.attemptTimeoutSeconds()));
        if (minimumDuration > 604_800L) throw new BadRequestException("DECOMPOSED_TASK_DURATION_TOO_LARGE",
                "Safe execution duration " + minimumDuration + " seconds exceeds the seven-day domain limit");
        return new LoopSpec.Limits(base.maxStageAttempts(), Math.max(base.maxTaskAttempts(), minimumAttempts),
                base.sessionErrorLimit(), base.stagnationLimit(),
                Math.max(base.maxDurationSeconds(), minimumDuration), base.attemptTimeoutSeconds(),
                base.verifierTimeoutSeconds());
    }

    private String aggregateContext(String original, String constraintsJson) {
        List<GlobalConstraint> constraints;
        try { constraints = json.readValue(constraintsJson, new TypeReference<List<GlobalConstraint>>() { }); }
        catch (JacksonException invalid) { throw new ConflictException("DECOMPOSITION_CONTEXT_INVALID",
                "Frozen global constraints are unreadable"); }
        if (constraints.isEmpty()) return original == null ? "" : original;
        String tracked = constraints.stream().map(item -> "- " + item.text() + " ["
                + String.join(",", item.requirementRefs()) + "]")
                .collect(java.util.stream.Collectors.joining("\n"));
        return (blank(original) ? "" : original.trim() + "\n\n") + "全局约束（来源可追踪）：\n" + tracked;
    }

    private List<String> strings(String source) {
        try { return json.readValue(blank(source) ? "[]" : source, new TypeReference<List<String>>() { }); }
        catch (JacksonException invalid) { return List.of(); }
    }

    private List<RequirementSegment> readSegments(String source) {
        try { return json.readValue(source, new TypeReference<List<RequirementSegment>>() { }); }
        catch (JacksonException invalid) { throw new ConflictException("REQUIREMENT_SEGMENTS_INVALID",
                "Frozen requirement segments are unreadable"); }
    }

    private String boundedUtf8(String value, int maxBytes) {
        if (value == null) return "";
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
            throw new BadRequestException("UTF8_CONTENT_TOO_LARGE", "Content exceeds " + maxBytes + " UTF-8 bytes");
        }
        return value;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException("Unable to serialize design workflow", failure); }
    }

    private DesignerSessionRow updateDesignerProjection(DesignerSessionRow session, DesignerSessionState state,
                                                         DesignWorkflowPhase phase, String externalSessionId,
                                                         String externalSessionState, int revision, int redesignCount) {
        return updateDesignerProjection(session, state, phase, externalSessionId, externalSessionState,
                revision, redesignCount, session.currentRequirementRevision(), session.activeWorkPackageId());
    }

    private DesignerSessionRow updateDesignerProjection(DesignerSessionRow session, DesignerSessionState state,
                                                         DesignWorkflowPhase phase, String externalSessionId,
                                                         String externalSessionState, int revision, int redesignCount,
                                                         Integer requirementRevision, String activeWorkPackageId) {
        DesignerSessionRow updated = new DesignerSessionRow(session.id(), session.projectId(), state.name(),
                session.accessMode(), session.createdAt(), now(), session.version(), externalSessionId,
                externalSessionState, session.loopDraftId(), phase.name(), revision, redesignCount,
                requirementRevision, activeWorkPackageId);
        if (session.state().equals(updated.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateDesignerSessionProjection(updated),
                    () -> new ConflictException("DESIGNER_SESSION_VERSION_CONFLICT",
                            "Designer session was updated concurrently"));
        } else {
            lifecycle.transition(designerSubject(updated), session.state(), updated.state(), null, java.util.Map.of(),
                    () -> mapper.updateDesignerSession(updated),
                    () -> new ConflictException("DESIGNER_SESSION_VERSION_CONFLICT",
                            "Designer session was updated concurrently"));
        }
        return get(session.id());
    }

    private LoopSpecCompilationRow updateCompilation(LoopSpecCompilationRow row,
                                                     LoopSpecCompilationState state,
                                                     String externalSessionId, String externalSessionState,
                                                     int repairCount, String errorCode, String errorDetail,
                                                     String projectId) {
        return updateCompilation(row, state, externalSessionId, externalSessionState, repairCount,
                errorCode, errorDetail, projectId, row.compiledPackageJson());
    }

    private LoopSpecCompilationRow updateCompilation(LoopSpecCompilationRow row,
                                                     LoopSpecCompilationState state,
                                                     String externalSessionId, String externalSessionState,
                                                     int repairCount, String errorCode, String errorDetail,
                                                     String projectId, String compiledPackageJson) {
        return updateCompilation(row, state, externalSessionId, externalSessionState, repairCount, errorCode,
                errorDetail, projectId, compiledPackageJson, StructuredModelStep.valueOf(row.workflowStep()),
                row.planningJson(), row.planningRepairCount());
    }

    private LoopSpecCompilationRow updateCompilation(LoopSpecCompilationRow row,
                                                     LoopSpecCompilationState state,
                                                     String externalSessionId, String externalSessionState,
                                                     int repairCount, String errorCode, String errorDetail,
                                                     String projectId, String compiledPackageJson,
                                                     StructuredModelStep workflowStep, String planningJson) {
        return updateCompilation(row, state, externalSessionId, externalSessionState, repairCount, errorCode,
                errorDetail, projectId, compiledPackageJson, workflowStep, planningJson,
                row.planningRepairCount());
    }

    private LoopSpecCompilationRow updateCompilation(LoopSpecCompilationRow row,
                                                     LoopSpecCompilationState state,
                                                     String externalSessionId, String externalSessionState,
                                                     int repairCount, String errorCode, String errorDetail,
                                                     String projectId, String compiledPackageJson,
                                                     StructuredModelStep workflowStep, String planningJson,
                                                     int planningRepairCount) {
        LoopSpecCompilationRow updated = new LoopSpecCompilationRow(row.id(), row.designerSessionId(),
                row.designRevision(), state.name(), externalSessionId, externalSessionState, repairCount,
                row.sourceDesignMessageId(), row.sourceDraftVersion(), errorCode, errorDetail,
                row.createdAt(), now(), row.version(), row.workPackageId(), row.transportRetryCount(),
                compiledPackageJson, workflowStep.name(), planningJson, planningRepairCount);
        if (row.state().equals(updated.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateLoopSpecCompilation(updated),
                    () -> new ConflictException("LOOPSPEC_COMPILATION_VERSION_CONFLICT",
                            "LoopSpec compilation was updated concurrently"));
        } else {
            lifecycle.transition(compilationSubject(updated, projectId), row.state(), updated.state(), null,
                    java.util.Map.of(), () -> mapper.updateLoopSpecCompilation(updated),
                    () -> new ConflictException("LOOPSPEC_COMPILATION_VERSION_CONFLICT",
                            "LoopSpec compilation was updated concurrently"));
        }
        return getCompilation(row.id());
    }

    private LoopSpecCompilationRow getCompilation(String id) {
        return mapper.findLoopSpecCompilation(id)
                .orElseThrow(() -> new NotFoundException("LoopSpec compilation not found: " + id));
    }

    private LifecycleTransitionService.Subject designerSubject(DesignerSessionRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGNER_SESSION, row.id(),
                LifecycleScopeType.PROJECT, row.projectId());
    }

    private LifecycleTransitionService.Subject compilationSubject(LoopSpecCompilationRow row, String projectId) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.LOOPSPEC_COMPILATION, row.id(),
                LifecycleScopeType.PROJECT, projectId);
    }

    private LifecycleTransitionService.Subject requirementSubject(DesignRequirementRevisionRow row, String projectId) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGN_REQUIREMENT_REVISION, row.id(),
                LifecycleScopeType.PROJECT, projectId);
    }

    private LifecycleTransitionService.Subject decompositionSubject(TaskDecompositionRow row, String projectId) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.TASK_DECOMPOSITION, row.id(),
                LifecycleScopeType.PROJECT, projectId);
    }

    private LifecycleTransitionService.Subject workPackageSubject(DesignWorkPackageRow row, String projectId) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGN_WORK_PACKAGE, row.id(),
                LifecycleScopeType.PROJECT, projectId);
    }

    private DesignerMessageRow appendMessage(String sessionId, DesignerActor actor,
                                             String content, String deliveryState) {
        DesignerSessionRow session = get(sessionId);
        return appendMessage(sessionId, actor, content, deliveryState,
                session.currentRequirementRevision(), session.activeWorkPackageId());
    }

    private DesignerMessageRow appendMessage(String sessionId, DesignerActor actor,
                                             String content, String deliveryState,
                                             Integer requirementRevision, String workPackageId) {
        String role = actor == DesignerActor.USER ? "USER"
                : Set.of(DesignerActor.DECOMPOSER, DesignerActor.DESIGNER, DesignerActor.COMPILER).contains(actor)
                ? "ASSISTANT" : "SYSTEM";
        int contentLimit = actor == DesignerActor.DESIGNER ? MAX_FROZEN_DESIGN_LENGTH : MAX_MESSAGE_LENGTH;
        DesignerMessageRow message = new DesignerMessageRow(UUID.randomUUID().toString(), sessionId,
                mapper.nextDesignerMessageOrdinal(sessionId), role, bounded(content, contentLimit),
                deliveryState, now(), actor.name(), requirementRevision, workPackageId);
        mapper.insertDesignerMessage(message);
        return message;
    }

    private void publish(DesignerSessionRow session, String type, DesignerActor actor,
                         boolean connected, String content, String detail) {
        CompilerStatus compiler = compilerStatus(session.id());
        String remoteState = actor == DesignerActor.COMPILER && compiler != null
                ? compiler.externalSessionState() : session.externalSessionState();
        RequirementRevisionStatus requirement = requirementStatus(session.id());
        events.publish(session.id(), type, session.state(), session.workflowPhase(), actor.name(),
                remoteState, connected, Set.of(DesignerActor.COMPILER, DesignerActor.DECOMPOSER).contains(actor)
                        ? "" : designerMarkdown(content), detail,
                session.currentRequirementRevision(), session.activeWorkPackageId(),
                requirement == null ? 0 : requirement.modelCallsUsed(),
                requirement == null ? MAX_MODEL_CALLS : requirement.maxModelCalls(), structuredModelStep(session.id()));
    }

    private OpenCodeClient.OpenCodeModel configuredModel() {
        String configured = defaults.getOpenCode().getModel();
        if (configured == null) return null;
        String value = configured.trim();
        int separator = value.indexOf('/');
        if (separator <= 0 || separator >= value.length() - 1) return null;
        String provider = value.substring(0, separator).trim();
        String model = value.substring(separator + 1).trim();
        return provider.isEmpty() || model.isEmpty() ? null
                : new OpenCodeClient.OpenCodeModel(provider, model, null);
    }

    private boolean timedOut(String updatedAt) {
        Duration timeout = defaults.getDesignerTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return false;
        try { return Duration.between(Instant.parse(updatedAt), Instant.now()).compareTo(timeout) > 0; }
        catch (RuntimeException invalidTimestamp) { return false; }
    }

    private String normalizeMessage(String content) {
        if (blank(content)) throw new BadRequestException("DESIGNER_MESSAGE_REQUIRED",
                "Designer message content is required");
        String normalized = content.trim();
        if (normalized.length() > MAX_MESSAGE_LENGTH) throw new BadRequestException("DESIGNER_MESSAGE_TOO_LONG",
                "Designer message must be at most " + MAX_MESSAGE_LENGTH + " characters");
        return normalized;
    }

    private String summarizeGaps(List<DesignGap> gaps) {
        return gaps.stream().map(gap -> "- " + gap.code().name() + "：" + gap.detail())
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private String summarizeInputGaps(List<DesignGap> gaps) {
        if (gaps == null || gaps.isEmpty()) return "请补充范围、可观察结果、异常语义或验收意图。";
        return summarizeGaps(validateDesignGaps(gaps));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }
    private static String statusDetail(OpenCodeClient.SessionStatus status) {
        return blank(status.detail()) ? "OpenCode session ended in " + status.state() : status.detail();
    }
    private static String safeState(String state) {
        return blank(state) ? "UNKNOWN" : state.replaceAll("[^A-Za-z0-9_-]", "_");
    }
    private static String safeMessage(String message) {
        if (blank(message)) return "OpenCode read-only workflow failed";
        return bounded(message.replaceAll("[\\r\\n]+", " ").trim(), 500);
    }
    private static String bounded(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
    private String now() { return Instant.now().toString(); }

    public record PendingQuestion(String id, List<QuestionPrompt> questions) { }
    public record QuestionPrompt(String question, String header, List<QuestionOption> options,
                                 boolean multiple, boolean custom) { }
    public record QuestionOption(String label, String description) { }
    public record CompilerStatus(String id, String state, String externalSessionId,
                                 String externalSessionState, int repairCount,
                                 int designRevision, String lastErrorCode, String lastErrorDetail,
                                 String workPackageId, String workflowStep, int planningRepairCount) { }
    public record RequirementRevisionStatus(int revision, String state, int modelCallsUsed,
                                            int maxModelCalls, long sourceDraftVersion) { }
    public record DecompositionStatus(String id, String state, String resultType, int repairCount,
                                      int transportRetryCount, String lastErrorCode, String lastErrorDetail,
                                      String workflowStep, int planningRepairCount) { }
    public record WorkPackageStatus(String id, int ordinal, String title, String objective, String state,
                                    List<String> dependencies, int redesignCount, int compilerRepairCount,
                                    int compilerPlanningRepairCount,
                                    String compilerSummary, String handoffSummary,
                                    String lastErrorCode, String lastErrorDetail) { }
    public record RequirementSegment(String id, String text) { }
    public record GlobalConstraint(String text, List<String> requirementRefs) {
        public GlobalConstraint { requirementRefs = requirementRefs == null ? List.of() : List.copyOf(requirementRefs); }
    }
    public record DecomposedWorkPackage(String id, String title, String objective,
                                        List<String> scopeIn, List<String> scopeOut, List<String> dependencies,
                                        List<String> deliverables, List<String> acceptanceIntent,
                                        List<String> requirementRefs) {
        public DecomposedWorkPackage {
            scopeIn = scopeIn == null ? List.of() : List.copyOf(scopeIn);
            scopeOut = scopeOut == null ? List.of() : List.copyOf(scopeOut);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
            acceptanceIntent = acceptanceIntent == null ? List.of() : List.copyOf(acceptanceIntent);
            requirementRefs = requirementRefs == null ? List.of() : List.copyOf(requirementRefs);
        }
    }
    public record RequirementCoverageMapping(String requirementRef, String targetType,
                                             String targetId, String rationale) {
        public RequirementCoverageMapping {
            targetType = targetType == null ? null : targetType.trim().toUpperCase();
        }
    }
    public record DependencyEvidence(String workPackageId, String dependsOn, String rationale) { }
    public record DecompositionPlanEnvelope(String status, String normalizedGoal,
                                            List<GlobalConstraint> globalConstraints,
                                            List<DecomposedWorkPackage> workPackages,
                                            List<RequirementCoverageMapping> coverageMappings,
                                            List<DependencyEvidence> dependencyEvidence,
                                            List<DesignGap> designGaps, String reason) {
        DecompositionPlanEnvelope normalized() {
            return new DecompositionPlanEnvelope(status == null ? null : status.trim().toUpperCase(), normalizedGoal,
                    globalConstraints == null ? List.of() : List.copyOf(globalConstraints),
                    workPackages == null ? List.of() : List.copyOf(workPackages),
                    coverageMappings == null ? List.of() : List.copyOf(coverageMappings),
                    dependencyEvidence == null ? List.of() : List.copyOf(dependencyEvidence),
                    designGaps == null ? List.of() : List.copyOf(designGaps), reason);
        }
        DecompositionEnvelope toEnvelope() {
            return new DecompositionEnvelope(status, normalizedGoal, globalConstraints, workPackages,
                    designGaps, reason).normalized();
        }
    }
    public record DecompositionEnvelope(String status, String normalizedGoal,
                                        List<GlobalConstraint> globalConstraints,
                                        List<DecomposedWorkPackage> workPackages,
                                        List<DesignGap> designGaps, String reason) {
        DecompositionEnvelope normalized() {
            return new DecompositionEnvelope(status == null ? null : status.trim().toUpperCase(), normalizedGoal,
                    globalConstraints == null ? List.of() : List.copyOf(globalConstraints),
                    workPackages == null ? List.of() : List.copyOf(workPackages),
                    designGaps == null ? List.of() : List.copyOf(designGaps), reason);
        }
    }
    public record CriterionSource(int stageIndex, String criterionId, String excerpt) { }
    public record DesignGap(DesignGapCode code, String detail) { }
    public enum DesignGapCode {
        MISSING_OBSERVABLE_OUTCOME, MISSING_EXCEPTION_SEMANTICS, MISSING_SCOPE, MISSING_ACCEPTANCE_INTENT
    }
    public record CompilationEnvelope(String status, String summary, LoopSpec loopSpec,
                                      List<CriterionSource> criterionSources, List<DesignGap> designGaps) {
        CompilationEnvelope normalized() {
            return new CompilationEnvelope(status == null ? null : status.trim().toUpperCase(), summary,
                    loopSpec, criterionSources == null ? List.of() : List.copyOf(criterionSources),
                    designGaps == null ? List.of() : List.copyOf(designGaps));
        }
    }
    public record PackageCompilationEnvelope(String status, String summary,
                                             List<LoopSpec.StageSpec> stages,
                                             List<CriterionSource> criterionSources,
                                             String handoffSummary, List<DesignGap> designGaps) {
        PackageCompilationEnvelope normalized() {
            return new PackageCompilationEnvelope(status == null ? null : status.trim().toUpperCase(), summary,
                    stages == null ? List.of() : List.copyOf(stages),
                    criterionSources == null ? List.of() : List.copyOf(criterionSources), handoffSummary,
                    designGaps == null ? List.of() : List.copyOf(designGaps));
        }
    }
    public record PlannedStage(String objective, List<String> allowedPaths, List<String> forbiddenPaths,
                               List<String> deliverables, List<LoopSpec.VerifierSpec> verifiers,
                               LoopSpec.VerificationRuntime verificationRuntime,
                               ImplementationKind implementationKind, String workPackageId) {
        public PlannedStage {
            allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
            forbiddenPaths = forbiddenPaths == null ? List.of() : List.copyOf(forbiddenPaths);
            deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
            verifiers = verifiers == null ? List.of() : List.copyOf(verifiers);
        }
    }
    public record AcceptanceEvidenceMapping(int stageIndex, String criterionId, String description,
                                            String designerExcerpt, String verificationMode,
                                            String judgeRubric, String judgeOnlyReason,
                                            String verifierStrategy, List<String> testCommand,
                                            List<String> testTargets) {
        public AcceptanceEvidenceMapping {
            verificationMode = blank(verificationMode) ? "MACHINE" : verificationMode.trim().toUpperCase();
            testCommand = testCommand == null ? List.of() : List.copyOf(testCommand);
            testTargets = testTargets == null ? List.of() : List.copyOf(testTargets);
        }
    }
    public record PackageCompilationPlanEnvelope(Integer contractVersion, String status, String summary,
                                                 List<PlannedStage> stages,
                                                 List<AcceptanceEvidenceMapping> evidenceMappings,
                                                 String handoffSummary, List<DesignGap> designGaps) {
        PackageCompilationPlanEnvelope normalized() {
            return new PackageCompilationPlanEnvelope(contractVersion == null ? 0 : contractVersion,
                    status == null ? null : status.trim().toUpperCase(), summary,
                    stages == null ? List.of() : List.copyOf(stages),
                    evidenceMappings == null ? List.of() : List.copyOf(evidenceMappings), handoffSummary,
                    designGaps == null ? List.of() : List.copyOf(designGaps));
        }
    }
}
