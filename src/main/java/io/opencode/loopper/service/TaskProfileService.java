package io.opencode.loopper.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.MutationMode;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.domain.WorkflowTemplate;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.DesignerTaskProfileRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskProfileService {
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final TaskProfileRouter router;
    private final TaskSemanticRouter semanticRouter;
    private final RolePackRegistry rolePacks;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public TaskProfileService(LoopperMapper mapper, ProjectService projects, TaskProfileRouter router,
                              TaskSemanticRouter semanticRouter,
                              RolePackRegistry rolePacks, ObjectMapper json,
                              PlatformTransactionManager transactionManager) {
        this.mapper = mapper; this.projects = projects; this.router = router; this.semanticRouter = semanticRouter;
        this.rolePacks = rolePacks;
        this.json = json; this.transactions = new TransactionTemplate(transactionManager);
    }

    public View initialize(String sessionId, String requirement) {
        DesignerTaskProfileRow existing = mapper.findCurrentDesignerTaskProfile(sessionId).orElse(null);
        if (existing != null) return view(existing);
        DesignerSessionRow session = session(sessionId);
        ProjectRow project = projects.get(session.projectId());
        Path root = Path.of(project.rootPath());
        TaskProfileRouter.Decision serverEvidence = router.route(root, requirement);
        TaskSemanticRouter.Result semantic = semanticRouter.classify(root, requirement, serverEvidence.evidence());
        TaskProfileRouter.Decision decision = semantic.success()
                ? router.route(root, requirement, semantic.labels())
                : router.genericFallback(root, semantic.errorCode());
        List<String> routingEvidence = new java.util.ArrayList<>(decision.evidence());
        if (!semantic.success()) routingEvidence.add("router-error=" + semantic.errorCode() + ":" + semantic.errorDetail());
        RolePackRegistry.RolePack pack = rolePacks.resolve(decision.intent(), decision.technologies(), decision.artifactKinds());
        TestPolicy testPolicy = effectiveTestPolicy(pack.defaultTestPolicy(), decision.intent(), decision.technologies(), decision.evidence());
        String now = Instant.now().toString();
        DesignerTaskProfileRow row = new DesignerTaskProfileRow(UUID.randomUUID().toString(), sessionId, null,
                "PROVISIONAL", decision.intent().name(), decision.workflowTemplate().name(),
                decision.mutationMode().name(), write(decision.artifactKinds()), write(decision.technologies()),
                testPolicy.name(), pack.executionStrategy().name(), pack.id(), pack.version(), decision.confidence(),
                write(routingEvidence), semantic.success() ? "AI_ROUTER" : "ROUTER_FALLBACK",
                decision.decisionRequired() ? 1 : 0, now, now, 0);
        if (mapper.insertDesignerTaskProfile(row) != 1) throw new ConflictException("TASK_PROFILE_CREATE_CONFLICT", "任务画像未能持久化");
        return view(row);
    }

    public View current(String sessionId) {
        session(sessionId);
        return mapper.findCurrentDesignerTaskProfile(sessionId).map(this::view).orElseGet(this::legacy);
    }

    public View override(String sessionId, TaskIntent intent, ArtifactKind primaryArtifact, long expectedVersion) {
        if (intent == null || primaryArtifact == null) throw new BadRequestException("TASK_PROFILE_OVERRIDE_INVALID", "必须选择任务意图和主要制品类型");
        DesignerTaskProfileRow current = mapper.findCurrentDesignerTaskProfile(sessionId)
                .orElseThrow(() -> new NotFoundException("Task profile not found for Designer session: " + sessionId));
        if (!"PROVISIONAL".equals(current.state())) throw new ConflictException("TASK_PROFILE_FROZEN", "需求确认后不能覆盖任务画像");
        if (current.version() != expectedVersion) throw new ConflictException("TASK_PROFILE_VERSION_CONFLICT", "任务画像已被并发更新");
        if (readStrings(current.evidenceJson()).contains("unsafe-operation-conflict")) {
            throw new BadRequestException("UNSAFE_MAINTENANCE_OUT_OF_SCOPE",
                    "当前版本不接受删除、服务启停、提交推送、发布或外部系统写入，不能通过画像覆盖绕过此边界");
        }
        List<String> technologies = readStrings(current.technologiesJson());
        WorkflowTemplate workflow = workflow(intent, current.workflowTemplate());
        MutationMode mutation = mutation(intent);
        RolePackRegistry.RolePack pack = rolePacks.resolve(intent, technologies, List.of(primaryArtifact));
        TestPolicy testPolicy = effectiveTestPolicy(pack.defaultTestPolicy(), intent, technologies, readStrings(current.evidenceJson()));
        DesignerTaskProfileRow updated = new DesignerTaskProfileRow(current.id(), current.designerSessionId(), null,
                current.state(), intent.name(), workflow.name(), mutation.name(), write(List.of(primaryArtifact)),
                current.technologiesJson(), testPolicy.name(), pack.executionStrategy().name(), pack.id(), pack.version(),
                100, write(List.of("manual-override")), "USER_OVERRIDE", 0, current.createdAt(), Instant.now().toString(), current.version());
        if (mapper.updateDesignerTaskProfile(updated) != 1) throw new ConflictException("TASK_PROFILE_VERSION_CONFLICT", "任务画像已被并发更新");
        return current(sessionId);
    }

    public View freeze(String sessionId) {
        if (mapper.findCurrentDesignerTaskProfile(sessionId).isEmpty()) {
            String now = Instant.now().toString();
            DesignerTaskProfileRow legacy = new DesignerTaskProfileRow(UUID.randomUUID().toString(), sessionId, null,
                    "PROVISIONAL", TaskIntent.LEGACY_SOFTWARE.name(), WorkflowTemplate.FULL_PACKAGE_DESIGN.name(),
                    MutationMode.WRITE_CODE.name(), write(List.of(ArtifactKind.SOURCE_CODE)), write(List.of()),
                    TestPolicy.REQUIRED.name(), ExecutionStrategy.OPEN_CODE_IMPLEMENTATION.name(),
                    "software-java", "legacy", 0, write(List.of("legacy-session-without-router")),
                    "LEGACY", 0, now, now, 0);
            if (mapper.insertDesignerTaskProfile(legacy) != 1) {
                throw new ConflictException("TASK_PROFILE_CREATE_CONFLICT", "历史任务画像未能持久化");
            }
        }
        return transactions.execute(status -> {
            DesignerTaskProfileRow current = mapper.findCurrentDesignerTaskProfile(sessionId)
                    .orElseThrow(() -> new ConflictException("TASK_PROFILE_MISSING", "确认需求前必须完成任务画像识别"));
            if (current.decisionRequired() == 1) throw new ConflictException("TASK_PROFILE_DECISION_REQUIRED", "任务类型存在歧义，请先确认任务画像");
            if ("FROZEN".equals(current.state())) return view(current);
            DesignerTaskProfileRow frozen = new DesignerTaskProfileRow(current.id(), current.designerSessionId(),
                    current.requirementRevisionId(), "FROZEN", current.intent(), current.workflowTemplate(), current.mutationMode(),
                    current.artifactKindsJson(), current.technologiesJson(), current.testPolicy(), current.executionStrategy(),
                    current.rolePackId(), current.rolePackVersion(), current.confidence(), current.evidenceJson(),
                    current.resolutionSource(), 0, current.createdAt(), Instant.now().toString(), current.version());
            if (mapper.updateDesignerTaskProfile(frozen) != 1) throw new ConflictException("TASK_PROFILE_VERSION_CONFLICT", "任务画像已被并发更新");
            return view(frozen);
        });
    }

    private TestPolicy effectiveTestPolicy(TestPolicy fallback, TaskIntent intent, List<String> technologies, List<String> evidence) {
        if (intent == TaskIntent.DOCUMENT_AUTHORING || intent == TaskIntent.DATA_CONVERSION
                || intent == TaskIntent.READ_ONLY_REVIEW || intent == TaskIntent.RESEARCH) return TestPolicy.NOT_APPLICABLE;
        if (evidence.stream().anyMatch("requirement-tests=required"::equals)) return TestPolicy.REQUIRED;
        if (technologies.contains("java") || technologies.contains("node")) return TestPolicy.REQUIRED;
        if (technologies.contains("python") && evidence.stream().anyMatch(value -> value.contains("pytest") || value.contains("unittest")))
            return TestPolicy.REQUIRED;
        return fallback;
    }

    private WorkflowTemplate workflow(TaskIntent intent, String previous) {
        return switch (intent) {
            case DOCUMENT_AUTHORING -> WorkflowTemplate.PACKAGED_ARTIFACT.name().equals(previous)
                    ? WorkflowTemplate.PACKAGED_ARTIFACT : WorkflowTemplate.DIRECT_ARTIFACT;
            case DATA_CONVERSION -> WorkflowTemplate.DIRECT_ARTIFACT;
            case READ_ONLY_REVIEW, RESEARCH -> WorkflowTemplate.READ_ONLY_REPORT;
            case CONFIGURATION, LOCAL_MAINTENANCE -> WorkflowTemplate.FULL_PACKAGE_DESIGN.name().equals(previous)
                    ? WorkflowTemplate.FULL_PACKAGE_DESIGN : WorkflowTemplate.LOCAL_MAINTENANCE;
            default -> WorkflowTemplate.FULL_PACKAGE_DESIGN;
        };
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
        return new View(row.id(), row.state(), TaskIntent.valueOf(row.intent()), WorkflowTemplate.valueOf(row.workflowTemplate()),
                MutationMode.valueOf(row.mutationMode()), artifacts, readStrings(row.technologiesJson()),
                TestPolicy.valueOf(row.testPolicy()), ExecutionStrategy.valueOf(row.executionStrategy()),
                row.rolePackId(), row.rolePackVersion(), row.confidence(), readStrings(row.evidenceJson()),
                row.resolutionSource(), row.decisionRequired() == 1, row.version());
    }
    private View legacy() {
        return new View(null, "LEGACY", TaskIntent.LEGACY_SOFTWARE, WorkflowTemplate.FULL_PACKAGE_DESIGN,
                MutationMode.WRITE_CODE, List.of(ArtifactKind.SOURCE_CODE), List.of(), TestPolicy.REQUIRED,
                ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, "software-java", "legacy", 0,
                List.of("historical-session-without-profile"), "LEGACY", false, 0);
    }

    public record View(String id, String state, TaskIntent intent, WorkflowTemplate workflowTemplate,
                       MutationMode mutationMode, List<ArtifactKind> artifactKinds, List<String> technologies,
                       TestPolicy testPolicy, ExecutionStrategy executionStrategy, String rolePackId,
                       String rolePackVersion, int confidence, List<String> evidence, String resolutionSource,
                       boolean decisionRequired, long version) { }
}
