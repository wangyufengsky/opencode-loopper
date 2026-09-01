package io.opencode.loopper.service;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.ProjectConventionState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectConventionDraftRow;
import io.opencode.loopper.persistence.ProjectConventionRuntimeRow;
import io.opencode.loopper.persistence.ProjectRow;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Creates the durable Convention candidate owner and local runtime before any remote I/O. */
@Component
final class ProjectConventionCandidateDraftCreator {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;

    ProjectConventionCandidateDraftCreator(LoopperMapper mapper, LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    ProjectConventionDraftRow create(ProjectRow project,
                                     ProjectConventionDocumentStore.SourceSnapshot source,
                                     ProjectStackSnapshot stackProfile) {
        String now = Instant.now().toString();
        ProjectConventionDraftRow created = new ProjectConventionDraftRow(
                UUID.randomUUID().toString(), project.id(), ProjectConventionState.RUNNING.name(),
                null, "CANDIDATE_PREPARED", source.exists() ? 1 : 0, source.sha256(), source.content(),
                null, stackProfile.state() == io.opencode.loopper.domain.ProjectStackProfileState.PARTIAL
                        ? "项目技术栈画像证据不完整；请重点复核技术栈与模块章节" : null,
                null, now, now, 0, stackProfile.id(), stackProfile.manifestFingerprint(),
                ProjectConventionCandidateWorkflow.RESPONSE_MODE,
                ProjectConventionCandidateWorkflow.SOURCE_REVISION);
        lifecycle.create(subject(created), created.state(), Map.of(),
                () -> mapper.insertProjectConventionDraft(created),
                () -> new ConflictException("PROJECT_CONVENTION_CREATE_CONFLICT",
                        "AGENTS.md proposal could not be created"));
        try {
            mapper.insertProjectConventionRuntime(new ProjectConventionRuntimeRow(created.id(), now,
                    "CANDIDATE_PREPARED", null, null, now, now, 0));
            return created;
        } catch (RuntimeException failure) {
            ProjectConventionDraftRow failed = new ProjectConventionDraftRow(
                    created.id(), created.projectId(), ProjectConventionState.FAILED.name(), null,
                    "RUNTIME_CREATE_FAILED", created.sourceExists(), created.sourceSha256(),
                    created.sourceContent(), null, created.normalizationNotice(),
                    "AGENTS.md generation runtime state could not be persisted", created.createdAt(),
                    Instant.now().toString(), created.version(), created.projectStackProfileId(),
                    created.stackFingerprint(), created.responseMode(), created.sourceRevision());
            lifecycle.transition(subject(failed), created.state(), failed.state(), LifecycleEvent.FAIL,
                    "PROJECT_CONVENTION_RUNTIME_CREATE_FAILED", Map.of(),
                    () -> mapper.updateProjectConventionDraft(failed),
                    () -> new ConflictException("PROJECT_CONVENTION_VERSION_CONFLICT",
                            "AGENTS.md proposal was updated concurrently"));
            return mapper.findProjectConventionDraft(created.id()).orElse(failed);
        }
    }

    private static LifecycleTransitionService.Subject subject(ProjectConventionDraftRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.PROJECT_CONVENTION,
                row.id(), LifecycleScopeType.PROJECT, row.projectId());
    }
}
