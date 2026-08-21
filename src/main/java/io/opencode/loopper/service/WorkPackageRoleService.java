package io.opencode.loopper.service;

import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.domain.WorkflowTemplate;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.WorkPackageRoleProfileRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Freezes a role pack independently for every decomposed package. */
@Service
public final class WorkPackageRoleService {
    private static final Pattern JAVA_SIGNAL = Pattern.compile(
            "(?<![a-z0-9])java(?:\\s*\\d+)?(?![a-z0-9])|(?<![a-z0-9])(jdk|spring|maven|gradle|kotlin)(?![a-z0-9])|\\.java(?![a-z0-9])");
    private static final Pattern NODE_SIGNAL = Pattern.compile(
            "(?<![a-z0-9])(javascript|typescript|node(?:\\.js)?|npm|pnpm|yarn|vue|react|vite|vitest|frontend)(?![a-z0-9])|前端");
    private static final Pattern PYTHON_SIGNAL = Pattern.compile(
            "(?<![a-z0-9])(python(?:3)?|pytest|unittest|django|flask|fastapi)(?![a-z0-9])|\\.py(?![a-z0-9])");
    private final LoopperMapper mapper;
    private final RolePackRegistry registry;
    private final TaskProfileService profiles;
    private final ObjectMapper json;

    public WorkPackageRoleService(LoopperMapper mapper, RolePackRegistry registry,
                                  TaskProfileService profiles, ObjectMapper json) {
        this.mapper = mapper; this.registry = registry; this.profiles = profiles; this.json = json;
    }

    public View assign(DesignWorkPackageRow row) {
        TaskProfileService.View parent = profiles.current(row.designerSessionId());
        return assign(row, parent);
    }

    private View assign(DesignWorkPackageRow row, TaskProfileService.View parent) {
        String text = (row.title() + "\n" + row.objective() + "\n" + row.scopeInJson()
                + "\n" + row.deliverablesJson()).toLowerCase(Locale.ROOT);
        boolean directSoftware = directSoftware(parent);
        List<String> technologies = new ArrayList<>();
        if (directSoftware) {
            technologies.addAll(parent.technologies());
        } else {
            if (hasPythonSignal(text)) technologies.add("python");
            if (hasNodeSignal(text)) technologies.add("node");
            if (hasJavaSignal(text)) technologies.add("java");
            if (technologies.isEmpty()) technologies.addAll(parent.technologies());
        }
        TaskIntent intent = parent.intent();
        List<ArtifactKind> artifacts = parent.artifactKinds();
        boolean codeSignals = hasPythonSignal(text) || hasNodeSignal(text) || hasJavaSignal(text)
                || contains(text, "代码", "接口");
        boolean documentSignals = contains(text, "markdown", "docx", "文档", "章节", "readme") && !codeSignals;
        boolean maintenanceSignals = hasMaintenanceSignal(text) && !codeSignals;
        boolean packageSpecialization = parent.workflowTemplate() == WorkflowTemplate.FULL_PACKAGE_DESIGN;
        if (parent.workflowTemplate() == WorkflowTemplate.PACKAGED_ARTIFACT
                || packageSpecialization && documentSignals) {
            intent = TaskIntent.DOCUMENT_AUTHORING;
            artifacts = text.contains("docx") ? List.of(ArtifactKind.DOCX) : List.of(ArtifactKind.MARKDOWN);
            technologies.clear();
        } else if (packageSpecialization && maintenanceSignals) {
            intent = TaskIntent.LOCAL_MAINTENANCE;
            artifacts = List.of(ArtifactKind.CONFIGURATION);
            technologies.clear();
        }
        RolePackRegistry.RolePack pack = registry.resolve(intent, technologies, artifacts);
        TestPolicy testPolicy = directSoftware ? parent.testPolicy() : pack.defaultTestPolicy();
        boolean explicitTests = parent.evidence().stream().anyMatch("requirement-tests=required"::equals);
        boolean pythonFramework = technologies.contains("python") && parent.evidence().stream()
                .anyMatch(value -> value.contains("test-framework=pytest") || value.contains("test-framework=unittest"));
        boolean nodeFramework = technologies.contains("node") && parent.evidence().stream()
                .anyMatch(value -> value.contains("test-framework=npm"));
        if (technologies.contains("java") || explicitTests || pythonFramework || nodeFramework) {
            testPolicy = TestPolicy.REQUIRED;
        }
        WorkPackageRoleProfileRow stored = new WorkPackageRoleProfileRow(row.id(), row.designerSessionId(), row.packageId(),
                parent.id(), pack.id(), pack.version(), pack.executionStrategy().name(), testPolicy.name(), write(technologies));
        if (mapper.assignWorkPackageRoleProfile(stored) != 1) {
            throw new ConflictException("WORK_PACKAGE_ROLE_PROFILE_CONFLICT", "工作包 Role Pack 无法冻结：" + row.packageId());
        }
        return view(stored);
    }

    public View get(DesignWorkPackageRow row) {
        TaskProfileService.View parent = profiles.current(row.designerSessionId());
        return mapper.findWorkPackageRoleProfile(row.id())
                .filter(stored -> !inconsistentDirectSoftwareRole(parent, stored))
                .map(this::view)
                .orElseGet(() -> assign(row, parent));
    }

    private boolean inconsistentDirectSoftwareRole(TaskProfileService.View parent,
                                                    WorkPackageRoleProfileRow stored) {
        return directSoftware(parent)
                && (stored.rolePackId() == null || !stored.rolePackId().startsWith("software-"));
    }

    private static boolean directSoftware(TaskProfileService.View parent) {
        return parent.workflowTemplate() == WorkflowTemplate.DIRECT_SOFTWARE_DESIGN
                && (parent.intent() == TaskIntent.SOFTWARE_CHANGE || parent.intent() == TaskIntent.LEGACY_SOFTWARE);
    }

    private View view(WorkPackageRoleProfileRow row) {
        return new View(row.rolePackId(), row.rolePackVersion(),
                io.opencode.loopper.domain.ExecutionStrategy.valueOf(row.executionStrategy()),
                TestPolicy.valueOf(row.testPolicy()), read(row.technologiesJson()));
    }
    private List<String> read(String value) { try { return json.readValue(value, new TypeReference<>() { }); } catch (Exception ignored) { return List.of(); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    static boolean hasJavaSignal(String text) {
        return text != null && JAVA_SIGNAL.matcher(text.toLowerCase(Locale.ROOT)).find();
    }
    static boolean hasNodeSignal(String text) {
        return text != null && NODE_SIGNAL.matcher(text.toLowerCase(Locale.ROOT)).find();
    }
    static boolean hasPythonSignal(String text) {
        return text != null && PYTHON_SIGNAL.matcher(text.toLowerCase(Locale.ROOT)).find();
    }
    static boolean hasMaintenanceSignal(String text) {
        return text != null && contains(text.toLowerCase(Locale.ROOT), "配置文件", "配置项", "修改配置", "更新配置",
                "调整配置", "依赖版本", "依赖升级", "升级依赖", "更新依赖", "新增依赖", "添加依赖", "移除依赖",
                "删除依赖", "pom.xml", "package.json", ".yaml", ".yml", ".properties");
    }
    private static boolean contains(String text, String... values) { for (String value : values) if (text.contains(value)) return true; return false; }

    public record View(String rolePackId, String rolePackVersion,
                       io.opencode.loopper.domain.ExecutionStrategy executionStrategy,
                       TestPolicy testPolicy, List<String> technologies) { }
}
