package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskLineageRow;
import io.opencode.loopper.persistence.TaskRow;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Resolves immutable design provenance without copying or rewriting historical conversation rows. */
@Service
public class TaskDesignOriginService {
    private final LoopperMapper mapper;

    public TaskDesignOriginService(LoopperMapper mapper) { this.mapper = mapper; }

    public Origin resolve(TaskRow task) {
        TaskLineageRow direct = mapper.findTaskLineage(task.id()).orElse(null);
        if (direct == null) return own(task, false);
        Origin persisted = persisted(direct);
        if (persisted != null) return persisted;

        Set<String> visited = new HashSet<>();
        String currentId = direct.parentTaskId();
        while (currentId != null && visited.add(currentId)) {
            TaskRow current = mapper.findTask(currentId).orElse(null);
            if (current == null) break;
            TaskLineageRow lineage = mapper.findTaskLineage(current.id()).orElse(null);
            if (lineage != null) {
                Origin inherited = persisted(lineage);
                if (inherited != null) return inherited;
            }
            Origin candidate = own(current, true);
            if (candidate.designerSession() != null || lineage == null) return candidate;
            currentId = lineage.parentTaskId();
        }
        return own(task, true);
    }

    public Origin sourceForChild(TaskRow parent) {
        Origin origin = resolve(parent);
        return new Origin(origin.sourceTask(), origin.loopDraft(), origin.designerSession(), true);
    }

    private Origin persisted(TaskLineageRow lineage) {
        if (blank(lineage.designSourceTaskId()) || blank(lineage.designSourceLoopDraftId())) return null;
        TaskRow sourceTask = mapper.findTask(lineage.designSourceTaskId()).orElse(null);
        LoopDraftRow sourceDraft = mapper.findDraft(lineage.designSourceLoopDraftId()).orElse(null);
        if (sourceTask == null || sourceDraft == null || !sourceDraft.id().equals(sourceTask.loopDraftId())) {
            return null;
        }
        DesignerSessionRow session = blank(lineage.designSourceDesignerSessionId()) ? null
                : mapper.findDesignerSession(lineage.designSourceDesignerSessionId()).orElse(null);
        if (session != null && !sourceDraft.id().equals(session.loopDraftId())) session = null;
        if (session == null) session = mapper.findLatestDesignerSessionByDraft(sourceDraft.id()).orElse(null);
        return new Origin(sourceTask, sourceDraft, session, true);
    }

    private Origin own(TaskRow task, boolean inherited) {
        LoopDraftRow draft = blank(task.loopDraftId()) ? null : mapper.findDraft(task.loopDraftId()).orElse(null);
        DesignerSessionRow session = draft == null ? null
                : mapper.findLatestDesignerSessionByDraft(draft.id()).orElse(null);
        return new Origin(task, draft, session, inherited);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record Origin(TaskRow sourceTask, LoopDraftRow loopDraft,
                         DesignerSessionRow designerSession, boolean inherited) { }
}
