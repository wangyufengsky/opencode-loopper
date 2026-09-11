package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.LoopDraftStatus;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskRow;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Persists an ordinary confirmed draft inside its caller's short transaction; performs no external I/O. */
@Component
final class TaskDraftConfirmation {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final TaskEvidenceService taskEvidence;
    private final DesignerAttachmentContext attachmentContext;

    TaskDraftConfirmation(LoopperMapper mapper, LifecycleTransitionService lifecycle, ObjectMapper json,
                          TaskEvidenceService taskEvidence, DesignerAttachmentContext attachmentContext) {
        this.mapper = mapper; this.lifecycle = lifecycle; this.json = json;
        this.taskEvidence = taskEvidence; this.attachmentContext = attachmentContext;
    }

    Creation persist(LoopDraftRow inputDraft, LoopSpec spec, ProjectRow project,
                                             String title, String admissionSource, String isolatedBaseline,
                                             boolean confirmDraft,
                                             DesignerAttachmentContext.PreparedFreeze preparedAttachments) {
        LoopDraftRow draft = mapper.findDraft(inputDraft.id())
                .orElseThrow(() -> new NotFoundException("Loop draft not found: " + inputDraft.id()));
        if (draft.version() != inputDraft.version()) {
            throw new ConflictException("DRAFT_VERSION_CONFLICT", "Loop draft was updated concurrently");
        }
        TaskRow existing = mapper.findTaskByDraft(draft.id()).orElse(null);
        if (existing != null) {
            if (confirmDraft) confirmDraft(draft);
            return new Creation(existing.id(), true);
        }
        if (confirmDraft) mapper.findLatestDesignerSessionByDraft(draft.id()).ifPresent(session ->
                DesignerConfirmationGate.assess(mapper, session, spec).requireEligible());
        String timestamp = now();
        String taskId = UUID.randomUUID().toString();
        io.opencode.loopper.persistence.DesignerTaskProfileRow profile = mapper.findFrozenTaskProfileByDraft(draft.id()).orElse(null);
        TaskRow task = new TaskRow(taskId, project.id(), draft.id(), normalizedTitle(title, draft.goal()),
                TaskState.PENDING_START.name(), null, null, null, isolatedBaseline, timestamp, timestamp, 0,
                profile == null ? null : profile.id(), profile == null ? null : profile.rolePackId(),
                profile == null ? null : profile.rolePackVersion());
        lifecycle.create(subject(LifecycleMachineType.TASK, task.id(), task.id()), task.state(),
                Map.of("source", admissionSource), () -> mapper.insertTask(task),
                () -> new ConflictException("TASK_CREATE_CONFLICT", "Task could not be created"));
        if (preparedAttachments != null) {
            attachmentContext.freezePrepared(new DesignerAttachmentContext.FreezeForTask(
                    task.id(), preparedAttachments.designerSessionId(), null), preparedAttachments);
        }
        taskEvidence.persistConfirmedDesignContext(task, draft);
        int ordinal = 0;
        for (LoopSpec.StageSpec stage : spec.stages()) {
            ExecutionRoleSnapshot executionRole = executionRole(draft, stage, profile);
            StageRow stageRow = new StageRow(UUID.randomUUID().toString(), taskId, ordinal++, stage.objective(),
                    write(stage.allowedPaths()), write(stage.forbiddenPaths()), write(stage.deliverables()), write(stage.verifiers()),
                    StageState.PENDING.name(), timestamp, timestamp, 0, stage.workPackageId(),
                    (stage.stageKind() == null ? io.opencode.loopper.domain.StageKind.LEGACY_SOFTWARE : stage.stageKind()).name(),
                    (stage.executionStrategy() == null
                            ? ExecutionStrategy.OPEN_CODE_IMPLEMENTATION : stage.executionStrategy()).name(),
                    stage.artifactPlanId(), executionRole.rolePackId(), executionRole.rolePackVersion(),
                    executionRole.testPolicy(), executionRole.technologiesJson(), executionRole.projectStackProfileId(),
                    executionRole.componentKeysJson(),
                    executionRole.stackFingerprint());
            lifecycle.create(subject(LifecycleMachineType.STAGE, stageRow.id(), taskId), stageRow.state(), Map.of(),
                    () -> mapper.insertStage(stageRow),
                    () -> new ConflictException("STAGE_CREATE_CONFLICT", "Stage could not be created"));
        }
        if (confirmDraft) confirmDraft(draft);
        return new Creation(taskId, false);
    }
    private ExecutionRoleSnapshot executionRole(LoopDraftRow draft, LoopSpec.StageSpec stage,
                                                  io.opencode.loopper.persistence.DesignerTaskProfileRow profile) {
        io.opencode.loopper.persistence.WorkPackageRoleProfileRow packageRole = null;
        if (stage.workPackageId() != null) {
            packageRole = mapper.findLatestDesignerSessionByDraft(draft.id())
                    .flatMap(session -> mapper.findLatestDesignWorkPackage(session.id(), stage.workPackageId()))
                    .flatMap(workPackage -> mapper.findWorkPackageRoleProfile(workPackage.id()))
                    .orElse(null);
        }
        if (packageRole != null) return new ExecutionRoleSnapshot(
                    packageRole.rolePackId(), packageRole.rolePackVersion(), packageRole.testPolicy(),
                    packageRole.technologiesJson(), packageRole.projectStackProfileId(),
                    packageRole.componentKeysJson(), packageRole.stackFingerprint());
        if (profile != null) return new ExecutionRoleSnapshot(
                    profile.rolePackId(), profile.rolePackVersion(), profile.testPolicy(),
                    profile.technologiesJson(), profile.projectStackProfileId(),
                    profile.componentKeysJson(), profile.stackFingerprint());
        return new ExecutionRoleSnapshot("software-java", "legacy", "REQUIRED", "[]", null, "[]", null);
    }
    private void confirmDraft(LoopDraftRow draft) {
        if (LoopDraftStatus.CONFIRMED.name().equals(draft.status())) return;
        LoopDraftRow confirmed = new LoopDraftRow(draft.id(), draft.projectId(), draft.goal(), draft.specJson(),
                LoopDraftStatus.CONFIRMED.name(), draft.createdAt(), now(), draft.version());
        LifecycleTransitionService.Subject draftSubject = new LifecycleTransitionService.Subject(
                LifecycleMachineType.LOOP_DRAFT, confirmed.id(), LifecycleScopeType.PROJECT, confirmed.projectId());
        lifecycle.transition(draftSubject, draft.status(), confirmed.status(), null, Map.of(),
                () -> mapper.updateDraft(confirmed),
                () -> new ConflictException("DRAFT_VERSION_CONFLICT", "Loop draft was updated concurrently"));
    }
    private static LifecycleTransitionService.Subject subject(LifecycleMachineType type, String id, String taskId) {
        return new LifecycleTransitionService.Subject(type, id, LifecycleScopeType.TASK, taskId);
    }
    private String write(Object value) { return json.writeValueAsString(value); }
    private static String now() { return Instant.now().toString(); }
    private static String normalizedTitle(String title, String goal) {
        return title == null || title.isBlank() ? goal.substring(0, Math.min(goal.length(), 120)) : title.trim();
    }
    record Creation(String taskId, boolean existing) { }
    private record ExecutionRoleSnapshot(String rolePackId, String rolePackVersion, String testPolicy,
            String technologiesJson, String projectStackProfileId, String componentKeysJson, String stackFingerprint) { }
}
