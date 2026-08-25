package io.opencode.loopper.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.DesignWorkflowPhase;
import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.MutationMode;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.domain.WorkflowTemplate;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.DesignerTaskProfileRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskProfileRouterRunRow;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskProfileService {
    private static final String SOFTWARE_WORKFLOW_EVIDENCE_PREFIX = "user-software-workflow=";
    private static final String HISTORICAL_SOFTWARE_WORKFLOW_EVIDENCE_PREFIX = "historical-software-workflow=";
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final TaskProfileRouter router;
    private final ProjectStackProfileService stackProfiles;
    private final TaskProfileOverridePolicy overridePolicy;
    private final TaskSemanticRouter semanticRouter;
    private final TaskProfileRouterRunService routerRuns;
    private final RolePackRegistry rolePacks;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public TaskProfileService(LoopperMapper mapper, ProjectService projects, TaskProfileRouter router,
                              ProjectStackProfileService stackProfiles,
                              TaskProfileOverridePolicy overridePolicy,
                              TaskSemanticRouter semanticRouter,
                              TaskProfileRouterRunService routerRuns,
                              RolePackRegistry rolePacks, ObjectMapper json,
                              PlatformTransactionManager transactionManager) {
        this.mapper = mapper; this.projects = projects; this.router = router; this.stackProfiles = stackProfiles;
        this.overridePolicy = overridePolicy;
        this.semanticRouter = semanticRouter;
        this.routerRuns = routerRuns;
        this.rolePacks = rolePacks;
        this.json = json; this.transactions = new TransactionTemplate(transactionManager);
    }

    public View initialize(String sessionId, String requirement) {
        DesignerTaskProfileRow existing = mapper.findCurrentDesignerTaskProfile(sessionId).orElse(null);
        if (existing != null) return view(existing);
        if (mapper.findLatestTaskProfileRouterRun(sessionId)
                .filter(run -> "PENDING".equals(run.state()) || "RUNNING".equals(run.state())).isEmpty()) {
            schedule(sessionId, requirement, List.of());
        }
        return current(sessionId);
    }

    public synchronized View reroute(String sessionId, String requirement) {
        session(sessionId);
        List<String> inherited = new java.util.ArrayList<>(mapper.findCurrentDesignerTaskProfile(sessionId).map(profile ->
                readStrings(profile.evidenceJson()).stream()
                        .filter("requirement-tests=required"::equals).toList()).orElse(List.of()));
        preservedSoftwareWorkflowEvidence(sessionId).ifPresent(inherited::add);
        invalidate(sessionId);
        schedule(sessionId, requirement, inherited);
        return current(sessionId);
    }

    public synchronized TaskProfileRouterRunService.RouterRunView reroutePersistedSnapshot(
            String sessionId, String expectedRunId, long expectedProfileVersion) {
        TaskProfileRouterRunService.RetryRequest retry = routerRuns.requireReroute(
                sessionId, expectedRunId, expectedProfileVersion);
        reroute(sessionId, retry.requirementSnapshot());
        return routerRuns.current(sessionId);
    }

    public synchronized View cancelRouting(String sessionId, String expectedRunId) {
        DesignerSessionRow session = session(sessionId);
        Path root = Path.of(projects.get(session.projectId()).rootPath());
        TaskProfileRouterRunRow cancelled = routerRuns.cancel(sessionId, expectedRunId, root);
        return materialize(cancelled, null, cancelled.errorCode(), cancelled.errorDetail());
    }

    public void invalidate(String sessionId) {
        DesignerSessionRow session = session(sessionId);
        Path root = Path.of(projects.get(session.projectId()).rootPath());
        // Every abort must be acknowledged before the old rows are superseded. Otherwise a replacement
        // Router could run in parallel with an unobserved predecessor.
        mapper.listActiveTaskProfileRouterRuns().stream()
                .filter(run -> sessionId.equals(run.designerSessionId()))
                .forEach(run -> semanticRouter.abort(root, run.externalSessionId()));
        String now = Instant.now().toString();
        mapper.supersedeActiveTaskProfileRouterRuns(sessionId, now);
        mapper.supersedeActiveTaskProfiles(sessionId, now);
    }

    private void schedule(String sessionId, String requirement, List<String> inheritedEvidence) {
        DesignerSessionRow session = session(sessionId);
        ProjectRow project = projects.get(session.projectId());
        Path root = Path.of(project.rootPath());
        ProjectStackSnapshot stackProfile = stackProfiles.ensureCurrent(project.id());
        TaskProfileRouter.Decision serverEvidence = router.route(stackProfile, requirement);
        List<String> observedEvidence = new java.util.ArrayList<>(serverEvidence.evidence());
        if (inheritedEvidence != null) inheritedEvidence.stream().filter(value -> !observedEvidence.contains(value))
                .forEach(observedEvidence::add);
        String now = Instant.now().toString();
        String runId = UUID.randomUUID().toString();
        TaskProfileRouterRunRow pending = new TaskProfileRouterRunRow(runId, sessionId,
                "PENDING", requirement == null ? "" : requirement, write(observedEvidence),
                null, null, null, null, null, null, now, now, 0,
                serverEvidence.projectStackProfileId(), write(serverEvidence.componentKeys()),
                serverEvidence.stackFingerprint());
        routerRuns.claim(runId);
        try {
            if (mapper.insertTaskProfileRouterRun(pending) != 1) {
                throw new ConflictException("TASK_PROFILE_ROUTER_CREATE_CONFLICT", "任务设置识别记录未能持久化");
            }
            TaskSemanticRouter.StartResult started = semanticRouter.start(root, requirement, observedEvidence);
            TaskProfileRouterRunRow updated = new TaskProfileRouterRunRow(pending.id(), sessionId,
                    started.started() ? "RUNNING" : "FAILED", pending.requirementSnapshot(), pending.repositoryEvidenceJson(),
                    started.externalSessionId(), started.started() ? "RUNNING" : "FAILED", started.responseMode(), null,
                    started.errorCode(), started.errorDetail(), pending.createdAt(), Instant.now().toString(), pending.version(),
                    pending.projectStackProfileId(), pending.componentKeysJson(), pending.stackFingerprint());
            if (mapper.updateTaskProfileRouterRun(updated) != 1) {
                semanticRouter.abortQuietly(root, started.externalSessionId());
                throw new ConflictException("TASK_PROFILE_ROUTER_VERSION_CONFLICT", "任务设置识别记录被并发更新");
            }
            if (!started.started()) materialize(updated, null, started.errorCode(), started.errorDetail());
        } finally {
            routerRuns.release(runId);
        }
    }

    public List<String> pollActive() {
        List<String> completedSessions = new java.util.ArrayList<>();
        for (TaskProfileRouterRunRow run : mapper.listActiveTaskProfileRouterRuns()) {
            if (!routerRuns.claim(run.id())) continue;
            try {
                DesignerSessionRow session = session(run.designerSessionId());
                if (stopping(session)) continue;
                Path root = Path.of(projects.get(session.projectId()).rootPath());
                if (semanticRouter.connectionTimedOut(run.externalSessionId(), run.createdAt(), Instant.now())) {
                    semanticRouter.abort(root, run.externalSessionId());
                    TaskProfileRouterRunRow failed = updateRun(run, "FAILED", run.externalSessionId(), "FAILED",
                            run.responseMode(), null, "ROUTER_TIMEOUT", routerRuns.timeoutDetail());
                    materialize(failed, null, "ROUTER_TIMEOUT", routerRuns.timeoutDetail());
                    completedSessions.add(run.designerSessionId());
                    continue;
                }
                if ("PENDING".equals(run.state())) {
                    TaskSemanticRouter.StartResult started = semanticRouter.start(root, run.requirementSnapshot(),
                            readStrings(run.repositoryEvidenceJson()));
                    TaskProfileRouterRunRow startedRow;
                    try {
                        startedRow = updateRun(run, started.started() ? "RUNNING" : "FAILED",
                                started.externalSessionId(), started.started() ? "RUNNING" : "FAILED", started.responseMode(),
                                null, started.errorCode(), started.errorDetail());
                    } catch (RuntimeException concurrentUpdate) {
                        semanticRouter.abortQuietly(root, started.externalSessionId());
                        throw concurrentUpdate;
                    }
                    if (!started.started()) {
                        materialize(startedRow, null, started.errorCode(), started.errorDetail());
                        completedSessions.add(run.designerSessionId());
                    }
                    continue;
                }
                TaskSemanticRouter.PollResult result = semanticRouter.poll(root, run.externalSessionId(), run.responseMode());
                if (result.completed()) {
                    TaskProfileRouterRunRow completed = updateRun(run, "COMPLETED", run.externalSessionId(),
                            result.externalState(), run.responseMode(), write(result.labels()), null, null);
                    materialize(completed, result.labels(), null, null);
                    completedSessions.add(run.designerSessionId());
                } else if (result.failed()) {
                    TaskProfileRouterRunRow failed = updateRun(run, "FAILED", run.externalSessionId(),
                            result.externalState(), run.responseMode(), null, result.errorCode(), result.errorDetail());
                    materialize(failed, null, result.errorCode(), result.errorDetail());
                    completedSessions.add(run.designerSessionId());
                } else if (!java.util.Objects.equals(run.externalSessionState(), result.externalState())) {
                    updateRun(run, "RUNNING", run.externalSessionId(), result.externalState(), run.responseMode(),
                            run.semanticLabelsJson(), null, null);
                }
            } catch (RuntimeException ignoredConcurrentOrMissingSession) {
            } finally {
                routerRuns.release(run.id());
            }
        }
        return List.copyOf(completedSessions);
    }

    private TaskProfileRouterRunRow updateRun(TaskProfileRouterRunRow run, String state, String externalSessionId,
                                               String externalState, String responseMode, String labels,
                                               String errorCode, String errorDetail) {
        TaskProfileRouterRunRow updated = new TaskProfileRouterRunRow(run.id(), run.designerSessionId(), state,
                run.requirementSnapshot(), run.repositoryEvidenceJson(), externalSessionId, externalState,
                responseMode, labels, errorCode, errorDetail, run.createdAt(), Instant.now().toString(), run.version(),
                run.projectStackProfileId(), run.componentKeysJson(), run.stackFingerprint());
        if (mapper.updateTaskProfileRouterRun(updated) != 1) {
            throw new ConflictException("TASK_PROFILE_ROUTER_VERSION_CONFLICT", "任务设置识别记录被并发更新");
        }
        return mapper.findTaskProfileRouterRun(run.id()).orElse(updated);
    }

    private View materialize(TaskProfileRouterRunRow run, TaskProfileRouter.SemanticLabels labels,
                             String errorCode, String errorDetail) {
        DesignerSessionRow session = session(run.designerSessionId());
        ProjectStackSnapshot stackProfile = run.projectStackProfileId() == null
                ? stackProfiles.current(session.projectId()) : stackProfiles.get(session.projectId(), run.projectStackProfileId());
        TaskProfileRouter.Decision decision = labels != null
                ? router.route(stackProfile, run.requirementSnapshot(), labels)
                : router.genericFallback(stackProfile, run.requirementSnapshot(),
                        errorCode == null ? "router-output-unavailable" : errorCode);
        String preservedWorkflowEvidence = readStrings(run.repositoryEvidenceJson()).stream()
                .filter(value -> value.startsWith(SOFTWARE_WORKFLOW_EVIDENCE_PREFIX)
                        || value.startsWith(HISTORICAL_SOFTWARE_WORKFLOW_EVIDENCE_PREFIX))
                .findFirst().orElse(null);
        WorkflowTemplate explicitWorkflow = java.util.Optional.ofNullable(preservedWorkflowEvidence)
                .map(value -> value.substring(value.indexOf('=') + 1))
                .map(value -> {
                    try { return WorkflowTemplate.valueOf(value); }
                    catch (IllegalArgumentException ignored) { return null; }
                })
                .filter(java.util.Objects::nonNull).orElse(null);
        if (decision.intent() == TaskIntent.SOFTWARE_CHANGE && explicitWorkflow != null) {
            decision = new TaskProfileRouter.Decision(decision.intent(), explicitWorkflow, decision.mutationMode(),
                    decision.artifactKinds(), decision.technologies(), decision.confidence(),
                    decision.decisionRequired(), decision.evidence(), decision.projectStackProfileId(),
                    decision.stackFingerprint(), decision.componentKeys(), decision.availableComponents(),
                    decision.stackProfileState());
        }
        List<String> routingEvidence = new java.util.ArrayList<>(decision.evidence());
        readStrings(run.repositoryEvidenceJson()).stream().filter(value -> value.startsWith("requirement-tests="))
                .filter(value -> !routingEvidence.contains(value)).forEach(routingEvidence::add);
        boolean userCancelled = "ROUTER_USER_CANCELLED".equals(errorCode);
        if (labels == null) routingEvidence.add("router-error=" + errorCode + ":" + errorDetail);
        RolePackRegistry.RolePack pack = rolePacks.resolve(decision.intent(), decision.technologies(), decision.artifactKinds());
        TestPolicy testPolicy = effectiveTestPolicy(pack.defaultTestPolicy(), decision.intent(), decision.technologies(), routingEvidence);
        ConfirmedChoice previous = previousConfirmedChoice(run.designerSessionId(), null);
        boolean safeToCarry = labels != null && previous != null && !routingEvidence.contains("unsafe-operation-conflict")
                && previous.matches(decision);
        // Every first Router result is an explicit gate in ordinary mode. Full-auto authorization may
        // adopt a safe successful recommendation through the same persisted confirmation boundary.
        boolean decisionRequired = previous == null || !safeToCarry;
        if (safeToCarry) routingEvidence.add("user-confirmed-carried-forward");
        String now = Instant.now().toString();
        DesignerTaskProfileRow row = new DesignerTaskProfileRow(UUID.randomUUID().toString(), run.designerSessionId(), null,
                "PROVISIONAL", decision.intent().name(), decision.workflowTemplate().name(),
                decision.mutationMode().name(), write(decision.artifactKinds()), write(decision.technologies()),
                testPolicy.name(), pack.executionStrategy().name(), pack.id(), pack.version(), decision.confidence(),
                write(routingEvidence), safeToCarry ? "USER_CONFIRMED_CARRIED_FORWARD"
                        : userCancelled ? "USER_SELECTION_PENDING" : explicitWorkflow != null
                            ? preservedWorkflowEvidence.startsWith(SOFTWARE_WORKFLOW_EVIDENCE_PREFIX)
                                    ? "USER_OVERRIDE" : "HISTORICAL_WORKFLOW_PRESERVED"
                            : labels != null ? "AI_ROUTER" : "ROUTER_FALLBACK",
                decisionRequired ? 1 : 0, now, now, 0, decision.projectStackProfileId(),
                write(decision.componentKeys()), decision.stackFingerprint());
        if (mapper.insertDesignerTaskProfile(row) != 1) throw new ConflictException("TASK_PROFILE_CREATE_CONFLICT", "任务设置未能持久化");
        return view(row);
    }

    public View current(String sessionId) {
        session(sessionId);
        return mapper.findCurrentDesignerTaskProfile(sessionId).map(this::view).orElseGet(() ->
                mapper.findLatestTaskProfileRouterRun(sessionId)
                        .filter(run -> "PENDING".equals(run.state()) || "RUNNING".equals(run.state()))
                        .map(run -> routing(sessionId)).orElseGet(() -> legacy(sessionId)));
    }

    public TaskProfileRouterRunService.RouterRunView routerRun(String sessionId) { return routerRuns.current(sessionId); }

    public WorkflowTemplate workflowTemplateIncludingSuperseded(String sessionId) {
        session(sessionId);
        return mapper.findCurrentDesignerTaskProfile(sessionId)
                .map(DesignerTaskProfileRow::workflowTemplate)
                .map(WorkflowTemplate::valueOf)
                .orElseGet(() -> mapper.listDesignerTaskProfiles(sessionId).stream()
                        .findFirst()
                        .map(DesignerTaskProfileRow::workflowTemplate)
                        .map(WorkflowTemplate::valueOf)
                        .orElseGet(() -> current(sessionId).workflowTemplate()));
    }

    public View override(String sessionId, TaskIntent intent, ArtifactKind primaryArtifact,
                         Boolean largeTaskMode, long expectedVersion) {
        return override(sessionId, intent, primaryArtifact, largeTaskMode, null, expectedVersion);
    }

    public View override(String sessionId, TaskIntent intent, ArtifactKind primaryArtifact,
                         Boolean largeTaskMode, List<String> componentKeys, long expectedVersion) {
        TaskProfileOverridePolicy.Context context = overrideContext(sessionId, intent, primaryArtifact, largeTaskMode,
                componentKeys, expectedVersion);
        DesignerTaskProfileRow current = context.current();
        if (current.decisionRequired() == 0 && !context.selectionChanged()) {
            return view(current);
        }
        List<String> technologies = context.technologies();
        WorkflowTemplate workflow = context.workflow();
        MutationMode mutation = mutation(intent);
        RolePackRegistry.RolePack pack = rolePacks.resolve(intent, technologies, List.of(primaryArtifact));
        TestPolicy testPolicy = effectiveTestPolicy(pack.defaultTestPolicy(), intent, technologies, readStrings(current.evidenceJson()));
        List<String> evidence = new java.util.ArrayList<>(readStrings(current.evidenceJson()));
        evidence.removeIf(value -> value.startsWith("manual-override"));
        evidence.add("manual-override");
        DesignerTaskProfileRow updated = new DesignerTaskProfileRow(current.id(), current.designerSessionId(), null,
                current.state(), intent.name(), workflow.name(), mutation.name(), write(List.of(primaryArtifact)),
                write(technologies), testPolicy.name(), pack.executionStrategy().name(), pack.id(), pack.version(),
                100, write(evidence), "USER_OVERRIDE", 0, current.createdAt(), Instant.now().toString(), current.version(),
                current.projectStackProfileId(), write(context.componentKeys()), current.stackFingerprint());
        if (mapper.updateDesignerTaskProfile(updated) != 1) throw new ConflictException("TASK_PROFILE_VERSION_CONFLICT", "任务设置已被并发更新");
        return current(sessionId);
    }

    public OverridePreview previewOverride(String sessionId, TaskIntent intent, ArtifactKind primaryArtifact,
                                           Boolean largeTaskMode, long expectedVersion) {
        return previewOverride(sessionId, intent, primaryArtifact, largeTaskMode, null, expectedVersion);
    }

    public OverridePreview previewOverride(String sessionId, TaskIntent intent, ArtifactKind primaryArtifact,
                                           Boolean largeTaskMode, List<String> componentKeys, long expectedVersion) {
        TaskProfileOverridePolicy.Context context = overrideContext(sessionId, intent, primaryArtifact, largeTaskMode,
                componentKeys, expectedVersion);
        DesignerTaskProfileRow current = context.current();
        boolean selectionChanged = context.selectionChanged();
        boolean updateRequired = selectionChanged || current.decisionRequired() == 1;
        boolean initialRoutingGate = DesignWorkflowPhase.ROUTING.name().equals(session(sessionId).workflowPhase());
        boolean sessionRestartRequired = !initialRoutingGate && selectionChanged
                && WorkflowTemplate.valueOf(current.workflowTemplate()) != context.workflow();
        return new OverridePreview(selectionChanged, updateRequired, sessionRestartRequired, context.workflow());
    }

    private TaskProfileOverridePolicy.Context overrideContext(String sessionId, TaskIntent intent,
            ArtifactKind primaryArtifact, Boolean largeTaskMode, List<String> componentKeys, long expectedVersion) {
        DesignerTaskProfileRow current = mapper.findCurrentDesignerTaskProfile(sessionId)
                .orElseThrow(() -> new NotFoundException("Task profile not found for Designer session: " + sessionId));
        return overridePolicy.resolve(current, sessionId == null ? null : session(sessionId).projectId(), intent,
                primaryArtifact, largeTaskMode, componentKeys, expectedVersion);
    }

    public View acceptRecommendation(String sessionId, long expectedVersion) {
        return confirmRecommendation(sessionId, expectedVersion, false);
    }

    public View confirmRecommendation(String sessionId, long expectedVersion) {
        return confirmRecommendation(sessionId, expectedVersion, true);
    }

    private View confirmRecommendation(String sessionId, long expectedVersion, boolean userConfirmed) {
        DesignerTaskProfileRow current = mapper.findCurrentDesignerTaskProfile(sessionId)
                .orElseThrow(() -> new NotFoundException("Task profile not found for Designer session: " + sessionId));
        if (!"PROVISIONAL".equals(current.state())) {
            throw new ConflictException("TASK_PROFILE_FROZEN", "需求确认后不能自动确认任务设置");
        }
        if (current.version() != expectedVersion) {
            throw new ConflictException("TASK_PROFILE_VERSION_CONFLICT", "任务设置已被并发更新");
        }
        if (current.decisionRequired() == 0) return view(current);
        List<String> evidence = new java.util.ArrayList<>(readStrings(current.evidenceJson()));
        if (evidence.contains("unsafe-operation-conflict")) {
            throw new BadRequestException("UNSAFE_MAINTENANCE_OUT_OF_SCOPE",
                    "当前版本不接受删除、服务启停、提交推送、发布或外部系统写入，不能由全自动确认绕过此边界");
        }
        if (evidence.contains("component-selection-ambiguous") && readStrings(current.componentKeysJson()).isEmpty()) {
            throw new BadRequestException("PROJECT_COMPONENT_SELECTION_REQUIRED", "多栈项目必须先选择本任务影响的组件");
        }
        evidence.add(userConfirmed ? "user-confirmed-profile" : "auto-recommended-profile");
        DesignerTaskProfileRow updated = new DesignerTaskProfileRow(current.id(), current.designerSessionId(),
                current.requirementRevisionId(), current.state(), current.intent(), current.workflowTemplate(),
                current.mutationMode(), current.artifactKindsJson(), current.technologiesJson(), current.testPolicy(),
                current.executionStrategy(), current.rolePackId(), current.rolePackVersion(), current.confidence(),
                write(evidence), userConfirmed ? "USER_CONFIRMED" : "AUTO_RECOMMENDED", 0,
                current.createdAt(), Instant.now().toString(), current.version(), current.projectStackProfileId(),
                current.componentKeysJson(), current.stackFingerprint());
        if (mapper.updateDesignerTaskProfile(updated) != 1) {
            throw new ConflictException("TASK_PROFILE_VERSION_CONFLICT", "任务设置已被并发更新");
        }
        return current(sessionId);
    }

    public View freeze(String sessionId) {
        if (mapper.findLatestTaskProfileRouterRun(sessionId)
                .filter(run -> "PENDING".equals(run.state()) || "RUNNING".equals(run.state())).isPresent()) {
            throw new ConflictException("TASK_PROFILE_ROUTING_IN_PROGRESS", "任务设置仍在识别，请等待完成");
        }
        if (mapper.findCurrentDesignerTaskProfile(sessionId).isEmpty()) {
            String now = Instant.now().toString();
            DesignerTaskProfileRow legacy = new DesignerTaskProfileRow(UUID.randomUUID().toString(), sessionId, null,
                    "PROVISIONAL", TaskIntent.LEGACY_SOFTWARE.name(), WorkflowTemplate.FULL_PACKAGE_DESIGN.name(),
                    MutationMode.WRITE_CODE.name(), write(List.of(ArtifactKind.SOURCE_CODE)), write(List.of()),
                    TestPolicy.REQUIRED.name(), ExecutionStrategy.OPEN_CODE_IMPLEMENTATION.name(),
                    "software-java", "legacy", 0, write(List.of("legacy-session-without-router")),
                    "LEGACY", 0, now, now, 0);
            if (mapper.insertDesignerTaskProfile(legacy) != 1) {
                throw new ConflictException("TASK_PROFILE_CREATE_CONFLICT", "历史任务设置未能持久化");
            }
        }
        return transactions.execute(status -> {
            DesignerTaskProfileRow current = mapper.findCurrentDesignerTaskProfile(sessionId)
                    .orElseThrow(() -> new ConflictException("TASK_PROFILE_MISSING", "确认需求前必须完成任务设置识别"));
            if (!confirmationReady(current)) throw new ConflictException("TASK_PROFILE_DECISION_REQUIRED", "任务设置尚未确认，请先确认或修改当前设置");
            if ("FROZEN".equals(current.state())) return view(current);
            DesignerTaskProfileRow frozen = new DesignerTaskProfileRow(current.id(), current.designerSessionId(),
                    current.requirementRevisionId(), "FROZEN", current.intent(), current.workflowTemplate(), current.mutationMode(),
                    current.artifactKindsJson(), current.technologiesJson(), current.testPolicy(), current.executionStrategy(),
                    current.rolePackId(), current.rolePackVersion(), current.confidence(), current.evidenceJson(),
                    current.resolutionSource(), 0, current.createdAt(), Instant.now().toString(), current.version(),
                    current.projectStackProfileId(), current.componentKeysJson(), current.stackFingerprint());
            if (mapper.updateDesignerTaskProfile(frozen) != 1) throw new ConflictException("TASK_PROFILE_VERSION_CONFLICT", "任务设置已被并发更新");
            return view(frozen);
        });
    }

    public View restoreAsLargeSoftwareProfile(String sessionId, View source, long expectedVersion) {
        if (source == null || source.id() == null || source.version() != expectedVersion) {
            throw new ConflictException("TASK_PROFILE_VERSION_CONFLICT", "任务设置已变化，请刷新后重试");
        }
        if (source.intent() != TaskIntent.SOFTWARE_CHANGE
                || source.workflowTemplate() != WorkflowTemplate.DIRECT_SOFTWARE_DESIGN) {
            throw new BadRequestException("LARGE_TASK_MODE_NOT_APPLICABLE", "只有普通软件任务可以切换为大型任务模式");
        }
        if (mapper.findCurrentDesignerTaskProfile(sessionId).isPresent()) {
            throw new ConflictException("TASK_PROFILE_REOPEN_CONFLICT", "重新打开需求后仍存在活动任务设置");
        }
        List<String> evidence = new java.util.ArrayList<>(source.evidence());
        evidence.add("large-task-mode-enabled-after-overflow");
        String now = Instant.now().toString();
        DesignerTaskProfileRow row = new DesignerTaskProfileRow(UUID.randomUUID().toString(), sessionId, null,
                "PROVISIONAL", TaskIntent.SOFTWARE_CHANGE.name(), WorkflowTemplate.FULL_PACKAGE_DESIGN.name(),
                source.mutationMode().name(), write(source.artifactKinds()), write(source.technologies()),
                source.testPolicy().name(), source.executionStrategy().name(), source.rolePackId(),
                source.rolePackVersion(), 100, write(evidence), "USER_OVERRIDE", 0, now, now, 0,
                source.projectStackProfileId(), write(source.componentKeys()), source.stackFingerprint());
        if (mapper.insertDesignerTaskProfile(row) != 1) {
            throw new ConflictException("TASK_PROFILE_CREATE_CONFLICT", "大型任务设置未能持久化");
        }
        return view(row);
    }

    private TestPolicy effectiveTestPolicy(TestPolicy fallback, TaskIntent intent, List<String> technologies, List<String> evidence) {
        if (intent == TaskIntent.DOCUMENT_AUTHORING || intent == TaskIntent.DATA_CONVERSION
                || intent == TaskIntent.READ_ONLY_REVIEW || intent == TaskIntent.RESEARCH) return TestPolicy.NOT_APPLICABLE;
        if (evidence.stream().anyMatch("requirement-tests=required"::equals)) return TestPolicy.REQUIRED;
        if (technologies.contains("java")) return TestPolicy.REQUIRED;
        if (technologies.contains("node") && evidence.stream().anyMatch(value -> value.contains("test-framework=npm")))
            return TestPolicy.REQUIRED;
        if (technologies.contains("python") && evidence.stream().anyMatch(value -> value.contains("pytest") || value.contains("unittest")))
            return TestPolicy.REQUIRED;
        return fallback;
    }

    private java.util.Optional<String> preservedSoftwareWorkflowEvidence(String sessionId) {
        List<DesignerTaskProfileRow> profiles = mapper.listDesignerTaskProfiles(sessionId);
        java.util.Optional<WorkflowTemplate> userWorkflow = profiles.stream()
                .filter(row -> "USER_OVERRIDE".equals(row.resolutionSource()))
                .filter(row -> TaskIntent.SOFTWARE_CHANGE.name().equals(row.intent()))
                .map(DesignerTaskProfileRow::workflowTemplate)
                .map(value -> {
                    try { return WorkflowTemplate.valueOf(value); }
                    catch (IllegalArgumentException ignored) { return null; }
                })
                .filter(value -> value == WorkflowTemplate.DIRECT_SOFTWARE_DESIGN
                        || value == WorkflowTemplate.FULL_PACKAGE_DESIGN)
                .findFirst();
        if (userWorkflow.isPresent()) {
            return java.util.Optional.of(SOFTWARE_WORKFLOW_EVIDENCE_PREFIX + userWorkflow.get().name());
        }
        return profiles.stream()
                .filter(row -> TaskIntent.SOFTWARE_CHANGE.name().equals(row.intent()))
                .filter(row -> WorkflowTemplate.FULL_PACKAGE_DESIGN.name().equals(row.workflowTemplate()))
                .findFirst()
                .map(row -> HISTORICAL_SOFTWARE_WORKFLOW_EVIDENCE_PREFIX
                        + WorkflowTemplate.FULL_PACKAGE_DESIGN.name());
    }

    private MutationMode mutation(TaskIntent intent) {
        return switch (intent) {
            case READ_ONLY_REVIEW, RESEARCH -> MutationMode.READ_ONLY;
            case DOCUMENT_AUTHORING, DATA_CONVERSION -> MutationMode.WRITE_FILES;
            case CONFIGURATION, LOCAL_MAINTENANCE -> MutationMode.SAFE_LOCAL_MAINTENANCE;
            default -> MutationMode.WRITE_CODE;
        };
    }

    private DesignerSessionRow session(String id) {
        return mapper.findDesignerSession(id).orElseThrow(() -> new NotFoundException("Designer session not found: " + id));
    }
    private boolean stopping(DesignerSessionRow session) {
        return "STOPPING".equals(session.state()) || "CANCELLED".equals(session.state());
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Unable to persist task profile", failure); }
    }
    private List<String> readStrings(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }
    private View view(DesignerTaskProfileRow row) {
        List<ArtifactKind> artifacts;
        try { artifacts = json.readValue(row.artifactKindsJson(), new TypeReference<>() { }); }
        catch (Exception ignored) { artifacts = List.of(ArtifactKind.OTHER); }
        String decisionState = "FROZEN".equals(row.state()) ? "FROZEN"
                : row.decisionRequired() == 1 ? "NEEDS_CONFIRMATION" : "CONFIRMED";
        boolean ready = confirmationReady(row);
        ProjectStackSnapshot stackProfile = row.projectStackProfileId() == null ? null
                : stackProfiles.get(session(row.designerSessionId()).projectId(), row.projectStackProfileId());
        List<ProjectStackSnapshot.Component> components = stackProfile == null
                ? List.of() : stackProfile.components();
        List<String> componentKeys = readStrings(row.componentKeysJson());
        boolean componentSelectionRequired = row.decisionRequired() == 1 && componentKeys.isEmpty()
                && readStrings(row.evidenceJson()).contains("component-selection-ambiguous");
        return new View(row.id(), row.state(), decisionState, ready,
                TaskIntent.valueOf(row.intent()), WorkflowTemplate.valueOf(row.workflowTemplate()),
                MutationMode.valueOf(row.mutationMode()), artifacts, readStrings(row.technologiesJson()),
                TestPolicy.valueOf(row.testPolicy()), ExecutionStrategy.valueOf(row.executionStrategy()),
                row.rolePackId(), row.rolePackVersion(), row.confidence(), readStrings(row.evidenceJson()),
                row.resolutionSource(), row.decisionRequired() == 1,
                TaskIntent.SOFTWARE_CHANGE.name().equals(row.intent())
                        && WorkflowTemplate.FULL_PACKAGE_DESIGN.name().equals(row.workflowTemplate()),
                ready ? null : previousConfirmedChoice(row.designerSessionId(), row.id()), row.version(),
                row.projectStackProfileId(), row.stackFingerprint(), componentKeys, components,
                stackProfile == null ? "UNANALYZED" : stackProfile.state().name(),
                componentSelectionRequired);
    }
    private View legacy(String sessionId) {
        return new View(null, "LEGACY", "CONFIRMED", true,
                TaskIntent.LEGACY_SOFTWARE, WorkflowTemplate.FULL_PACKAGE_DESIGN,
                MutationMode.WRITE_CODE, List.of(ArtifactKind.SOURCE_CODE), List.of(), TestPolicy.REQUIRED,
                ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, "software-java", "legacy", 0,
                List.of("historical-session-without-profile"), "LEGACY", false, false, null, 0,
                null, null, List.of(), List.of(), "UNANALYZED", false);
    }

    private View routing(String sessionId) {
        ConfirmedChoice previous = previousConfirmedChoice(sessionId, null);
        return new View(null, "ROUTING", "ROUTING", false, TaskIntent.SOFTWARE_CHANGE,
                WorkflowTemplate.DIRECT_SOFTWARE_DESIGN, MutationMode.WRITE_CODE,
                List.of(ArtifactKind.SOURCE_CODE), List.of(), TestPolicy.OPTIONAL,
                ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, "routing", "pending", 0,
                List.of(), "ROUTER", true, false, previous, 0,
                null, null, List.of(), List.of(), "UNANALYZED", false);
    }

    private boolean confirmationReady(DesignerTaskProfileRow row) {
        return "FROZEN".equals(row.state()) || ("PROVISIONAL".equals(row.state()) && row.decisionRequired() == 0);
    }

    private ConfirmedChoice previousConfirmedChoice(String sessionId, String excludeId) {
        return mapper.listDesignerTaskProfiles(sessionId).stream()
                .filter(row -> excludeId == null || !excludeId.equals(row.id()))
                .filter(row -> java.util.Set.of("USER_CONFIRMED", "USER_OVERRIDE",
                        "USER_CONFIRMED_CARRIED_FORWARD").contains(row.resolutionSource()))
                .map(this::choice).findFirst().orElse(null);
    }

    private ConfirmedChoice choice(DesignerTaskProfileRow row) {
        List<ArtifactKind> artifacts;
        try { artifacts = json.readValue(row.artifactKindsJson(), new TypeReference<>() { }); }
        catch (Exception ignored) { artifacts = List.of(ArtifactKind.OTHER); }
        WorkflowTemplate workflow = WorkflowTemplate.valueOf(row.workflowTemplate());
        return new ConfirmedChoice(TaskIntent.valueOf(row.intent()), artifacts.isEmpty() ? ArtifactKind.OTHER : artifacts.getFirst(),
                workflow, MutationMode.valueOf(row.mutationMode()), workflow == WorkflowTemplate.FULL_PACKAGE_DESIGN,
                row.resolutionSource(), row.projectStackProfileId(), row.stackFingerprint(),
                readStrings(row.componentKeysJson()));
    }

    public record ConfirmedChoice(TaskIntent intent, ArtifactKind primaryArtifactKind,
                                  WorkflowTemplate workflowTemplate, MutationMode mutationMode,
                                  boolean largeTaskMode, String resolutionSource,
                                  String projectStackProfileId, String stackFingerprint,
                                  List<String> componentKeys) {
        private boolean matches(TaskProfileRouter.Decision decision) {
            ArtifactKind primary = decision.artifactKinds().isEmpty() ? ArtifactKind.OTHER : decision.artifactKinds().getFirst();
            return intent == decision.intent() && primaryArtifactKind == primary
                    && workflowTemplate == decision.workflowTemplate() && mutationMode == decision.mutationMode()
                    && java.util.Objects.equals(stackFingerprint, decision.stackFingerprint())
                    && componentKeys.equals(decision.componentKeys());
        }
    }

    public record OverridePreview(boolean selectionChanged, boolean updateRequired,
                                  boolean sessionRestartRequired, WorkflowTemplate targetWorkflowTemplate) { }

    public record View(String id, String state, String decisionState, boolean confirmationReady,
                       TaskIntent intent, WorkflowTemplate workflowTemplate,
                       MutationMode mutationMode, List<ArtifactKind> artifactKinds, List<String> technologies,
                       TestPolicy testPolicy, ExecutionStrategy executionStrategy, String rolePackId,
                       String rolePackVersion, int confidence, List<String> evidence, String resolutionSource,
                       boolean decisionRequired, boolean largeTaskMode, ConfirmedChoice previousConfirmedChoice,
                       long version, String projectStackProfileId, String stackFingerprint,
                       List<String> componentKeys, List<ProjectStackSnapshot.Component> candidateComponents,
                       String stackProfileState, boolean componentSelectionRequired) { }
}
