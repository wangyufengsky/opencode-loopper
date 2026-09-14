package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Freezes one complete ordinary discussion and binds its profile, before Session workflow projection. */
final class DesignerRequirementFreeze {
    private DesignerRequirementFreeze() { }
    static DesignRequirementRevisionRow freeze(LoopperMapper mapper, LoopDraftService drafts, TaskProfileService taskProfiles,
            LifecycleTransitionService lifecycle, ObjectMapper json, DesignerSessionRow session,
            DesignerMessageRow sourceMessage, int modelCalls, int maxModelCalls) {
        int revision = mapper.listDesignRequirementRevisions(session.id()).stream()
                .mapToInt(DesignRequirementRevisionRow::revision).max().orElse(0) + 1;
        // Every discussion turn stores a complete replacement snapshot. Freezing the historical
        // user messages again would duplicate requirements and manufacture uncovered RQ segments.
        // The conversation and decision log remain persisted separately for audit and recovery.
        String requirement = sourceMessage.content();
        List<DesignerSessionService.RequirementSegment> segments = DesignerRequirementSegmenter.segment(requirement);
        LoopDraftRow draft = drafts.get(session.loopDraftId());
        String now = Instant.now().toString();
        DesignRequirementRevisionRow row = new DesignRequirementRevisionRow(UUID.randomUUID().toString(),
                session.id(), revision, sourceMessage.id(), requirement, json.writeValueAsString(segments), draft.version(),
                DesignRequirementRevisionState.ACTIVE.name(), modelCalls,
                maxModelCalls, now, now, 0);
        lifecycle.create(new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGN_REQUIREMENT_REVISION, row.id(), LifecycleScopeType.PROJECT, session.projectId()), row.state(), Map.of("revision", revision),
                () -> mapper.insertDesignRequirementRevision(row),
                () -> new ConflictException("DESIGN_REQUIREMENT_REVISION_CREATE_CONFLICT",
                        "The complete requirement revision could not be frozen"));
        TaskProfileService.View profile = taskProfiles.current(session.id());
        if (profile.id() != null && mapper.bindTaskProfileRequirement(profile.id(), row.id(), now) != 1) {
            throw new ConflictException("TASK_PROFILE_REQUIREMENT_BIND_CONFLICT",
                    "冻结任务设置未能绑定需求版本");
        }
        mapper.bindOpenRequirementDiscussions(session.id(), revision);
        return mapper.findDesignRequirementRevision(row.id()).orElseThrow();
    }
}
