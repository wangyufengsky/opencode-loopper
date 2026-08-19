package io.opencode.loopper.service;

import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.WorkPackageRoleProfileRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Freezes a role pack independently for every decomposed package. */
@Service
public final class WorkPackageRoleService {
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
        String text = (row.title() + "\n" + row.objective() + "\n" + row.scopeInJson()
                + "\n" + row.deliverablesJson()).toLowerCase(Locale.ROOT);
        List<String> technologies = new ArrayList<>();
        if (contains(text, "python", ".py", "pytest")) technologies.add("python");
        if (contains(text, "vue", "node", "typescript", "javascript", "npm", "frontend", "前端")) technologies.add("node");
        if (contains(text, "java", "spring", "maven", "gradle", ".java")) technologies.add("java");
        if (technologies.isEmpty()) technologies.addAll(parent.technologies());
        TaskIntent intent = parent.intent();
        List<ArtifactKind> artifacts = parent.artifactKinds();
        if (parent.workflowTemplate() == io.opencode.loopper.domain.WorkflowTemplate.PACKAGED_ARTIFACT) {
            intent = TaskIntent.DOCUMENT_AUTHORING;
            artifacts = parent.artifactKinds();
        }
        RolePackRegistry.RolePack pack = registry.resolve(intent, technologies, artifacts);
        TestPolicy testPolicy = pack.defaultTestPolicy();
        boolean explicitTests = parent.evidence().stream().anyMatch("requirement-tests=required"::equals);
        boolean pythonFramework = technologies.contains("python") && parent.evidence().stream()
                .anyMatch(value -> value.contains("test-framework=pytest") || value.contains("test-framework=unittest"));
        if (technologies.contains("java") || technologies.contains("node") || explicitTests || pythonFramework) {
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
        return mapper.findWorkPackageRoleProfile(row.id()).map(this::view).orElseGet(() -> assign(row));
    }

    private View view(WorkPackageRoleProfileRow row) {
        return new View(row.rolePackId(), row.rolePackVersion(),
                io.opencode.loopper.domain.ExecutionStrategy.valueOf(row.executionStrategy()),
                TestPolicy.valueOf(row.testPolicy()), read(row.technologiesJson()));
    }
    private List<String> read(String value) { try { return json.readValue(value, new TypeReference<>() { }); } catch (Exception ignored) { return List.of(); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private static boolean contains(String text, String... values) { for (String value : values) if (text.contains(value)) return true; return false; }

    public record View(String rolePackId, String rolePackVersion,
                       io.opencode.loopper.domain.ExecutionStrategy executionStrategy,
                       TestPolicy testPolicy, List<String> technologies) { }
}
