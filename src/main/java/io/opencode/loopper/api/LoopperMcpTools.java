package io.opencode.loopper.api;

import io.opencode.loopper.domain.LoopDraftStatus;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.DesignerSessionService;
import io.opencode.loopper.service.LoopDraftService;
import io.opencode.loopper.service.ProjectService;
import io.opencode.loopper.service.TaskService;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * The single implementation of Loopper's MCP tools. It is exposed through the official
 * Spring AI Streamable HTTP server and called by the legacy JSON-RPC compatibility controller.
 */
@Service
public class LoopperMcpTools {
    private final ProjectService projects;
    private final DesignerSessionService designerSessions;
    private final LoopDraftService drafts;
    private final TaskService tasks;
    private final LoopperMapper mapper;
    private final Validator validator;

    public LoopperMcpTools(ProjectService projects, DesignerSessionService designerSessions, LoopDraftService drafts,
                           TaskService tasks, LoopperMapper mapper, Validator validator) {
        this.projects = projects;
        this.designerSessions = designerSessions;
        this.drafts = drafts;
        this.tasks = tasks;
        this.mapper = mapper;
        this.validator = validator;
    }

    @Tool(name = "get_project_context", description = "Read registered project context without file-write authority")
    public Map<String, Object> getProjectContext(
            @ToolParam(description = "Registered Loopper project identifier", required = true) String projectId) {
        ProjectRow project = projects.get(requiredText(projectId, "PROJECT_ID_REQUIRED", "projectId"));
        return Map.of("project", project, "taskCount", projects.taskCount(project.id()), "accessMode", "READ_ONLY",
                "permissions", List.of("project.context.read", "loop_spec.propose"),
                "restriction", "MCP Designer tools never write repository files or alter a task without a confirmed draft.");
    }

    @Tool(name = "propose_loop_spec", description = "Validate and synchronize a complete DRAFT_READY LoopSpec into the draft bound to a read-only Designer session; human confirmation is required before task creation")
    public Map<String, Object> proposeLoopSpec(
            @ToolParam(description = "Read-only Designer session identifier", required = true) String designerSessionId,
            @ToolParam(description = "Registered project identifier", required = true) String projectId,
            @ToolParam(description = "Complete LoopSpec v1 to persist unchanged", required = true) LoopSpec spec) {
        String sessionId = requiredText(designerSessionId, "DESIGNER_SESSION_ID_REQUIRED", "designerSessionId");
        String normalizedProjectId = requiredText(projectId, "PROJECT_ID_REQUIRED", "projectId");
        requireReadOnlyDesigner(sessionId, normalizedProjectId);
        if (spec == null) throw new BadRequestException("LOOPSPEC_REQUIRED", "spec must be a complete LoopSpec JSON object");
        if (!normalizedProjectId.equals(spec.projectId())) {
            throw new BadRequestException("LOOPSPEC_PROJECT_MISMATCH", "spec.projectId must match projectId");
        }
        List<String> errors = validationErrors(spec);
        if (!"v1".equals(spec.schemaVersion())) errors = append(errors, "schemaVersion: only v1 is supported");
        if (!errors.isEmpty()) throw new BadRequestException("LOOPSPEC_INVALID", String.join("; ", errors));
        LoopDraftRow draft = designerSessions.syncLoopSpec(sessionId, spec);
        return Map.of("designerSessionId", sessionId, "draft", draft, "spec", spec, "status", draft.status(),
                "requiresHumanConfirmation", true,
                "nextStep", "Review and confirm the draft through the Loop Draft REST workflow before calling create_task.");
    }

    @Tool(name = "validate_loop_spec", description = "Validate a LoopSpec v1 as spec, or validate a persisted draftId paired with its exact version")
    public Map<String, Object> validateLoopSpec(
            @ToolParam(description = "LoopSpec v1 to validate; omit when validating a persisted draft", required = false) LoopSpec spec,
            @ToolParam(description = "Persisted LoopSpec draft identifier; supply together with version", required = false) String draftId,
            @ToolParam(description = "Exact optimistic-lock version for draftId validation", required = false) Long version) {
        if (draftId != null && !draftId.isBlank()) {
            LoopDraftRow draft = drafts.get(draftId.trim());
            if (version == null || version.longValue() != draft.version()) {
                return Map.of("valid", false, "errors", List.of("version: must match the current draft version"),
                        "draftId", draft.id(), "version", draft.version());
            }
            return validationResult(drafts.spec(draft), draft.id(), version);
        }
        if (spec == null) return Map.of("valid", false, "errors", List.of("spec or draftId/version is required"));
        return validationResult(spec, null, null);
    }

    @Tool(name = "create_task", description = "Create or return the one isolated task for a CONFIRMED draft; this never auto-confirms a draft")
    public TaskRow createTask(
            @ToolParam(description = "Human-confirmed LoopSpec draft identifier", required = true) String draftId) {
        LoopDraftRow draft = drafts.get(requiredText(draftId, "DRAFT_ID_REQUIRED", "draftId"));
        if (!LoopDraftStatus.CONFIRMED.name().equals(draft.status())) {
            throw new ConflictException("DRAFT_NOT_CONFIRMED", "create_task accepts only a CONFIRMED draft; review and confirm it first");
        }
        return mapper.findTaskByDraft(draft.id()).orElseGet(() -> createTaskIdempotently(draft));
    }

    @Tool(name = "start_task", description = "Start a task whose confirmed contract and isolated worktree are prepared")
    public TaskRow startTask(@ToolParam(description = "Task identifier", required = true) String taskId) {
        return tasks.start(requiredText(taskId, "TASK_ID_REQUIRED", "taskId"));
    }

    @Tool(name = "get_task_status", description = "Read task, stage, attempt, verifier and layered error status")
    public Map<String, Object> getTaskStatus(@ToolParam(description = "Task identifier", required = true) String taskId) {
        String id = requiredText(taskId, "TASK_ID_REQUIRED", "taskId");
        TaskRow task = tasks.get(id);
        return Map.of("task", task, "stages", tasks.stages(id), "attempts", tasks.attempts(id), "errors", tasks.errors(id));
    }

    private Map<String, Object> validationResult(LoopSpec spec, String draftId, Long version) {
        List<String> errors = validationErrors(spec);
        if (!"v1".equals(spec.schemaVersion())) errors = append(errors, "schemaVersion: only v1 is supported");
        return draftId == null ? Map.of("valid", errors.isEmpty(), "errors", errors)
                : Map.of("valid", errors.isEmpty(), "errors", errors, "draftId", draftId, "version", version);
    }

    private TaskRow createTaskIdempotently(LoopDraftRow draft) {
        try {
            return tasks.createFromDraft(draft, null);
        } catch (RuntimeException exception) {
            // The unique draft index makes concurrent callers converge on the winning task.
            return mapper.findTaskByDraft(draft.id()).orElseThrow(() -> exception);
        }
    }

    private void requireReadOnlyDesigner(String designerSessionId, String projectId) {
        var session = designerSessions.get(designerSessionId);
        if (!projectId.equals(session.projectId())) {
            throw new BadRequestException("DESIGNER_PROJECT_MISMATCH", "Designer session is not registered for projectId");
        }
        if (!DesignerSessionService.READ_ONLY.equals(session.accessMode())) {
            throw new ConflictException("DESIGNER_ACCESS_MODE_INVALID", "Designer session must use READ_ONLY access mode");
        }
    }

    private String requiredText(String value, String code, String field) {
        if (value == null || value.isBlank()) throw new BadRequestException(code, field + " is required");
        return value.trim();
    }

    private List<String> validationErrors(LoopSpec spec) {
        return validator.validate(spec).stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().toList();
    }

    private List<String> append(List<String> values, String value) {
        List<String> copy = new ArrayList<>(values);
        copy.add(value);
        return List.copyOf(copy);
    }
}
