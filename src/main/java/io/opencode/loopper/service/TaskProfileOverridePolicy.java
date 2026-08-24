package io.opencode.loopper.service;

import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.WorkflowTemplate;
import io.opencode.loopper.persistence.DesignerTaskProfileRow;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Validates a task-setting override against the immutable project stack snapshot. */
@Component
final class TaskProfileOverridePolicy {
    private final ProjectStackProfileService profiles;
    private final ObjectMapper json;

    TaskProfileOverridePolicy(ProjectStackProfileService profiles, ObjectMapper json) {
        this.profiles = profiles;
        this.json = json;
    }

    Context resolve(DesignerTaskProfileRow current, String projectId, TaskIntent intent,
                    ArtifactKind primaryArtifact, Boolean largeTaskMode,
                    List<String> componentKeys, long expectedVersion) {
        if (intent == null || primaryArtifact == null) {
            throw new BadRequestException("TASK_PROFILE_OVERRIDE_INVALID", "必须选择任务意图和主要制品类型");
        }
        if (!"PROVISIONAL".equals(current.state())) {
            throw new ConflictException("TASK_PROFILE_FROZEN", "需求确认后不能修改任务设置");
        }
        if (current.version() != expectedVersion) {
            throw new ConflictException("TASK_PROFILE_VERSION_CONFLICT", "任务设置已被并发更新");
        }
        if (read(current.evidenceJson()).contains("unsafe-operation-conflict")) {
            throw new BadRequestException("UNSAFE_MAINTENANCE_OUT_OF_SCOPE",
                    "当前版本不接受删除、服务启停、提交推送、发布或外部系统写入，不能通过画像覆盖绕过此边界");
        }
        if (Boolean.TRUE.equals(largeTaskMode) && intent != TaskIntent.SOFTWARE_CHANGE) {
            throw new BadRequestException("LARGE_TASK_MODE_NOT_APPLICABLE", "大型任务模式只适用于软件任务");
        }
        List<String> selected = componentKeys == null
                ? read(current.componentKeysJson()) : componentKeys.stream().distinct().toList();
        List<String> technologies = read(current.technologiesJson());
        if (current.projectStackProfileId() != null) {
            ProjectStackSnapshot latest = profiles.current(projectId);
            if (!current.projectStackProfileId().equals(latest.id())
                    || !Objects.equals(current.stackFingerprint(), latest.manifestFingerprint())) {
                throw new ConflictException("PROJECT_STACK_PROFILE_STALE", "项目技术栈画像已变化，请重新识别任务设置");
            }
            profiles.validateComponentKeys(latest, selected);
            if (!selected.isEmpty()) technologies = profiles.technologies(latest.id(), selected);
            if (selected.isEmpty() && latest.technologyFamilies().size() > 1) {
                throw new BadRequestException("PROJECT_COMPONENT_SELECTION_REQUIRED", "多栈项目必须选择本任务影响的组件");
            }
        }
        WorkflowTemplate workflow = workflow(intent, current.workflowTemplate(), largeTaskMode);
        return new Context(current, technologies, selected, workflow,
                selectionChanged(current, intent, primaryArtifact, workflow, selected));
    }

    private boolean selectionChanged(DesignerTaskProfileRow current, TaskIntent intent,
                                     ArtifactKind primaryArtifact, WorkflowTemplate workflow,
                                     List<String> componentKeys) {
        List<String> artifacts = read(current.artifactKindsJson());
        String currentPrimary = artifacts.isEmpty() ? ArtifactKind.OTHER.name() : artifacts.getFirst();
        return !current.intent().equals(intent.name()) || !currentPrimary.equals(primaryArtifact.name())
                || !current.workflowTemplate().equals(workflow.name())
                || !read(current.componentKeysJson()).equals(componentKeys);
    }

    private static WorkflowTemplate workflow(TaskIntent intent, String previous, Boolean largeTaskMode) {
        return switch (intent) {
            case DOCUMENT_AUTHORING -> WorkflowTemplate.PACKAGED_ARTIFACT.name().equals(previous)
                    ? WorkflowTemplate.PACKAGED_ARTIFACT : WorkflowTemplate.DIRECT_ARTIFACT;
            case DATA_CONVERSION -> WorkflowTemplate.DIRECT_ARTIFACT;
            case READ_ONLY_REVIEW, RESEARCH -> WorkflowTemplate.READ_ONLY_REPORT;
            case CONFIGURATION, LOCAL_MAINTENANCE -> WorkflowTemplate.FULL_PACKAGE_DESIGN.name().equals(previous)
                    ? WorkflowTemplate.FULL_PACKAGE_DESIGN : WorkflowTemplate.LOCAL_MAINTENANCE;
            case SOFTWARE_CHANGE -> largeTaskMode == null
                    ? (WorkflowTemplate.FULL_PACKAGE_DESIGN.name().equals(previous)
                            ? WorkflowTemplate.FULL_PACKAGE_DESIGN : WorkflowTemplate.DIRECT_SOFTWARE_DESIGN)
                    : largeTaskMode ? WorkflowTemplate.FULL_PACKAGE_DESIGN : WorkflowTemplate.DIRECT_SOFTWARE_DESIGN;
            default -> WorkflowTemplate.FULL_PACKAGE_DESIGN;
        };
    }

    private List<String> read(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }

    record Context(DesignerTaskProfileRow current, List<String> technologies,
                   List<String> componentKeys, WorkflowTemplate workflow, boolean selectionChanged) { }
}
