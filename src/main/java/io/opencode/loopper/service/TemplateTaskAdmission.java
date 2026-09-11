package io.opencode.loopper.service;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.LoopDraftStatus;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskExecutionMode;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.TaskWorkspacePolicy;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TemplateTaskMapper;
import io.opencode.loopper.persistence.TemplateTaskRunRow;
import io.opencode.loopper.template.TemplateDateRange;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Atomic creation and confirmation only. Queue admission, filesystem and model calls belong to Start. */
@Service
public class TemplateTaskAdmission {
    private final LoopperMapper mapper;
    private final TemplateTaskMapper templates;
    private final LifecycleTransitionService lifecycle;
    private final TaskEvidenceService evidence;
    private final StoryBindingService stories;
    private final ObjectMapper json;

    TemplateTaskAdmission(LoopperMapper mapper, TemplateTaskMapper templates, LifecycleTransitionService lifecycle,
                            TaskEvidenceService evidence, StoryBindingService stories, ObjectMapper json) {
        this.mapper = mapper; this.templates = templates; this.lifecycle = lifecycle;
        this.evidence = evidence; this.stories = stories; this.json = json;
    }

    @Transactional
    public TaskRow create(Command command) {
        var existing = templates.findRequest(command.requestKey()).orElse(null);
        if (existing != null) return requireSameRequest(existing, command.requestSha256());
        String taskId = UUID.randomUUID().toString(), now = Instant.now().toString();
        var spec = command.contract().spec();
        LoopDraftRow draft = new LoopDraftRow(UUID.randomUUID().toString(), spec.projectId(), spec.goal(), json.writeValueAsString(spec),
                LoopDraftStatus.DRAFT_READY.name(), now, now, 0);
        lifecycle.create(draftSubject(draft), draft.status(), Map.of("source", "BUILTIN_TEMPLATE"), () -> mapper.insertDraft(draft), TemplateTaskAdmission::conflict);
        TaskRow task = new TaskRow(taskId, spec.projectId(), draft.id(), spec.goal(), TaskState.PENDING_START.name(),
                null, null, null, null, now, now, 0, null, "template-report", command.contract().definition().version(),
                TaskExecutionMode.TEMPLATE_REPORT.name(), TaskWorkspacePolicy.ISOLATED_REPORT.name());
        lifecycle.create(subject(LifecycleMachineType.TASK, taskId, taskId), task.state(), Map.of("source", "BUILTIN_TEMPLATE"),
                () -> mapper.insertTask(task), TemplateTaskAdmission::conflict);
        for (int ordinal = 0; ordinal < spec.stages().size(); ordinal++) createStage(task, command.contract(), ordinal, now);
        var branch = command.branch();
        templates.insertRun(new TemplateTaskRunRow(taskId, command.requestKey(), command.requestSha256(),
                command.contract().definition().id(), command.contract().definition().version(), branch.id(), branch.label(), branch.ref(), branch.remote(),
                command.dates().startDate().toString(), command.dates().endDate().toString(), json.writeValueAsString(command.contract()),
                null, null, 0, command.bypassCache() ? 1 : 0, now, now, 0));
        stories.attachTask(taskId, command.story());
        evidence.persistConfirmedDesignContext(task, draft);
        LoopDraftRow confirmed = new LoopDraftRow(draft.id(), draft.projectId(), draft.goal(), draft.specJson(), "CONFIRMED", now, now, 0);
        lifecycle.transition(draftSubject(draft), draft.status(), confirmed.status(), LifecycleEvent.CONFIRM,
                null, Map.of("source", "BUILTIN_TEMPLATE"), () -> mapper.updateDraft(confirmed), TemplateTaskAdmission::conflict);
        return task;
    }

    TaskRow requireSameRequest(TemplateTaskRunRow existing, String digest) {
        if (!existing.requestSha256().equals(digest)) throw new ConflictException("TEMPLATE_REQUEST_CONFLICT", "该发起请求已使用不同参数，请重新发起");
        return mapper.findTask(existing.taskId()).orElseThrow(() -> new NotFoundException("模板任务不存在"));
    }

    private void createStage(TaskRow task, TemplateTaskContractFactory.Frozen frozen, int ordinal, String now) {
        var spec = frozen.spec().stages().get(ordinal);
        StageRow stage = new StageRow(UUID.randomUUID().toString(), task.id(), ordinal, spec.objective(),
                json.writeValueAsString(spec.allowedPaths()), json.writeValueAsString(spec.forbiddenPaths()), json.writeValueAsString(spec.deliverables()),
                json.writeValueAsString(spec.verifiers()), StageState.PENDING.name(), now, now, 0, null,
                spec.stageKind().name(), spec.executionStrategy().name(), null, "template-report", frozen.definition().version(),
                "NOT_APPLICABLE", "[]", null, "[]", null);
        lifecycle.create(subject(LifecycleMachineType.STAGE, stage.id(), task.id()), stage.state(), Map.of(),
                () -> mapper.insertStage(stage), TemplateTaskAdmission::conflict);
    }

    private static LifecycleTransitionService.Subject subject(LifecycleMachineType type, String id, String taskId) {
        return new LifecycleTransitionService.Subject(type, id, LifecycleScopeType.TASK, taskId);
    }
    private static LifecycleTransitionService.Subject draftSubject(LoopDraftRow draft) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.LOOP_DRAFT, draft.id(), LifecycleScopeType.PROJECT, draft.projectId());
    }
    private static ConflictException conflict() { return new ConflictException("TEMPLATE_CREATE_CONFLICT", "模板任务创建冲突，请重试同一请求"); }
    public record Command(String requestKey, String requestSha256, ProjectBranchService.Branch branch,
                           TemplateDateRange dates, TemplateTaskContractFactory.Frozen contract,
                           StoryBindingConfiguration story, boolean bypassCache) { }
}
