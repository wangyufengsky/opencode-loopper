package io.opencode.loopper.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.domain.LoopDraftStatus;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoopDraftService {
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final ObjectMapper json;
    private final TaskService tasks;
    private final Validator validator;
    public LoopDraftService(LoopperMapper mapper, ProjectService projects, ObjectMapper json, TaskService tasks,
                            Validator validator) {
        this.mapper = mapper; this.projects = projects; this.json = json; this.tasks = tasks; this.validator = validator;
    }
    @Transactional
    public LoopDraftRow create(LoopSpec spec) {
        validate(spec);
        projects.get(spec.projectId());
        String now = Instant.now().toString();
        LoopDraftRow row = new LoopDraftRow(UUID.randomUUID().toString(), spec.projectId(), spec.goal(), write(spec),
                LoopDraftStatus.DRAFT_READY.name(), now, now, 0);
        mapper.insertDraft(row); return row;
    }
    public LoopDraftRow get(String id) { return mapper.findDraft(id).orElseThrow(() -> new NotFoundException("Loop draft not found: " + id)); }
    @Transactional
    public LoopDraftRow update(String id, LoopSpec spec) {
        validate(spec);
        LoopDraftRow old = get(id);
        if (LoopDraftStatus.CONFIRMED.name().equals(old.status())) throw new ConflictException("DRAFT_CONFIRMED", "Confirmed LoopSpec is immutable; create a new draft");
        if (!old.projectId().equals(spec.projectId())) throw new BadRequestException("DRAFT_PROJECT_MISMATCH", "LoopSpec projectId cannot be changed");
        LoopDraftRow changed = new LoopDraftRow(old.id(), old.projectId(), spec.goal(), write(spec), LoopDraftStatus.DRAFT_READY.name(), old.createdAt(), Instant.now().toString(), old.version());
        if (mapper.updateDraft(changed) != 1) throw new ConflictException("DRAFT_VERSION_CONFLICT", "Loop draft was updated concurrently");
        return get(id);
    }
    @Transactional
    public io.opencode.loopper.persistence.TaskRow confirm(String id, String title) {
        LoopDraftRow draft = get(id);
        if (LoopDraftStatus.CONFIRMED.name().equals(draft.status())) return mapper.findTaskByDraft(id).orElseThrow(() -> new ConflictException("DRAFT_TASK_MISSING", "Confirmed draft has no associated task"));
        io.opencode.loopper.persistence.TaskRow task = tasks.createFromDraft(draft, title);
        LoopDraftRow confirmed = new LoopDraftRow(draft.id(), draft.projectId(), draft.goal(), draft.specJson(), LoopDraftStatus.CONFIRMED.name(), draft.createdAt(), Instant.now().toString(), draft.version());
        if (mapper.updateDraft(confirmed) != 1) throw new ConflictException("DRAFT_VERSION_CONFLICT", "Loop draft was updated concurrently");
        return task;
    }
    public LoopSpec spec(LoopDraftRow row) {
        try { return json.readValue(row.specJson(), LoopSpec.class); }
        catch (JacksonException e) { throw new BadRequestException("LOOPSPEC_INVALID", "Stored LoopSpec cannot be read: " + e.getMessage()); }
    }
    private String write(LoopSpec spec) {
        try { return json.writeValueAsString(spec); }
        catch (JacksonException e) { throw new BadRequestException("LOOPSPEC_INVALID", e.getMessage()); }
    }

    public void validate(LoopSpec spec) {
        if (spec == null) throw new BadRequestException("LOOPSPEC_REQUIRED", "LoopSpec is required");
        List<String> errors = new ArrayList<>(validator.validate(spec).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage()).sorted().toList());
        if (!"v1".equals(spec.schemaVersion())) errors.add("schemaVersion: only v1 is supported");
        if (!errors.isEmpty()) throw new BadRequestException("LOOPSPEC_INVALID", String.join("; ", errors));
    }
}
