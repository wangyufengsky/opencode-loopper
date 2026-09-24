package io.opencode.loopper.service;

import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.service.roles.RolePromptResources;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class RolePromptComposer {
    public String requirementDesignerInstructions(TaskProfileService.View profile) {
        String specialized = switch (profile.rolePackId()) {
            case "software-python" -> packFragment("role-pack.2026-08-dynamic-v7.software-python.requirement-designer", profile.rolePackVersion());
            case "software-node" -> packFragment("role-pack.2026-08-dynamic-v7.software-node.requirement-designer", profile.rolePackVersion());
            case "software-mixed" -> packFragment("role-pack.2026-08-dynamic-v7.software-mixed.requirement-designer", profile.rolePackVersion());
            case "software-generic" -> packFragment("role-pack.2026-08-dynamic-v7.software-generic.requirement-designer", profile.rolePackVersion());
            case "document-markdown-docx" -> packFragment("role-pack.2026-08-dynamic-v7.document-markdown-docx.requirement-designer", profile.rolePackVersion());
            case "tabular-conversion" -> packFragment("role-pack.2026-08-dynamic-v7.tabular-conversion.requirement-designer", profile.rolePackVersion());
            case "read-only-report" -> packFragment("role-pack.2026-08-dynamic-v7.read-only-report.requirement-designer", profile.rolePackVersion());
            case "local-maintenance" -> packFragment("role-pack.2026-08-dynamic-v7.local-maintenance.requirement-designer", profile.rolePackVersion());
            default -> packFragment("role-pack.2026-08-dynamic-v7.default.requirement-designer", profile.rolePackVersion());
        };
        return header(profile) + "\n" + specialized
                + packFragment("role-pack.2026-08-dynamic-v7.common.requirement-designer-tail", profile.rolePackVersion());
    }

    public String decomposerInstructions(TaskProfileService.View profile) {
        return header(profile) + "\n" + switch (profile.workflowTemplate()) {
            case PACKAGED_ARTIFACT -> packFragment("role-pack.2026-08-dynamic-v7.workflow.packaged-artifact.decomposer", profile.rolePackVersion());
            case LOCAL_MAINTENANCE -> packFragment("role-pack.2026-08-dynamic-v7.workflow.local-maintenance.decomposer", profile.rolePackVersion());
            default -> switch (profile.rolePackId()) {
                case "software-python" -> packFragment("role-pack.2026-08-dynamic-v7.software-python.decomposer", profile.rolePackVersion());
                case "software-node" -> packFragment("role-pack.2026-08-dynamic-v7.software-node.decomposer", profile.rolePackVersion());
                case "software-mixed" -> packFragment("role-pack.2026-08-dynamic-v7.software-mixed.decomposer", profile.rolePackVersion());
                case "software-generic" -> packFragment("role-pack.2026-08-dynamic-v7.software-generic.decomposer", profile.rolePackVersion());
                default -> packFragment("role-pack.2026-08-dynamic-v7.default.decomposer", profile.rolePackVersion());
            };
        };
    }

    public String packageDesignerInstructions(TaskProfileService.View profile, String rolePackId,
                                              io.opencode.loopper.domain.ExecutionStrategy executionStrategy,
                                              List<String> technologies, TestPolicy testPolicy) {
        String focus = switch (rolePackId) {
            case "document-markdown-docx" -> packFragment("role-pack.2026-08-dynamic-v7.document-markdown-docx.package-designer", profile.rolePackVersion());
            case "tabular-conversion" -> packFragment("role-pack.2026-08-dynamic-v7.tabular-conversion.package-designer", profile.rolePackVersion());
            case "local-maintenance" -> packFragment("role-pack.2026-08-dynamic-v7.local-maintenance.package-designer", profile.rolePackVersion());
            case "software-java" -> packFragment("role-pack.2026-08-dynamic-v7.software-java.package-designer", profile.rolePackVersion());
            case "software-python" -> packFragment("role-pack.2026-08-dynamic-v7.software-python.package-designer", profile.rolePackVersion());
            case "software-node" -> packFragment("role-pack.2026-08-dynamic-v7.software-node.package-designer", profile.rolePackVersion());
            case "software-mixed" -> packFragment("role-pack.2026-08-dynamic-v7.software-mixed.package-designer", profile.rolePackVersion());
            default -> packFragment("role-pack.2026-08-dynamic-v7.default.package-designer", profile.rolePackVersion());
        };
        return "Work-package Role Pack: " + rolePackId + "@" + profile.rolePackVersion()
                + "; technologies=" + technologies + "; execution=" + executionStrategy
                + "; testPolicy=" + testPolicy + ".\n" + focus
                + packFragment("role-pack.2026-08-dynamic-v7.common.package-designer-tail", profile.rolePackVersion());
    }

    public String reviewerInstructions(TaskProfileService.View profile) {
        return header(profile) + packFragment("role-pack.2026-08-dynamic-v7.common.reviewer", profile.rolePackVersion());
    }

    public String implementationInstructions(String rolePackId, String rolePackVersion,
                                             List<String> technologies, TestPolicy testPolicy) {
        String id = rolePackId == null || rolePackId.isBlank() ? "legacy-software" : rolePackId;
        String version = rolePackVersion == null || rolePackVersion.isBlank() ? "legacy" : rolePackVersion;
        String specialized = switch (id) {
            case "software-java" -> packFragment("role-pack.2026-08-dynamic-v7.software-java.implementation", version);
            case "software-python" -> packFragment("role-pack.2026-08-dynamic-v7.software-python.implementation", version);
            case "software-node" -> packFragment("role-pack.2026-08-dynamic-v7.software-node.implementation", version);
            case "software-mixed" -> packFragment("role-pack.2026-08-dynamic-v7.software-mixed.implementation", version);
            case "software-generic" -> packFragment("role-pack.2026-08-dynamic-v7.software-generic.implementation", version);
            case "document-markdown-docx" -> packFragment("role-pack.2026-08-dynamic-v7.document-markdown-docx.implementation", version);
            case "local-maintenance" -> packFragment("role-pack.2026-08-dynamic-v7.local-maintenance.implementation", version);
            default -> packFragment("role-pack.2026-08-dynamic-v7.default.implementation", version);
        };
        return "Frozen execution Role Pack: " + id + "@" + version
                + "\nFrozen technologies: " + (technologies == null ? List.of() : technologies)
                + "\nFrozen test policy: " + (testPolicy == null ? TestPolicy.REQUIRED : testPolicy)
                + "\n" + specialized
                + packFragment("role-pack.2026-08-dynamic-v7.common.implementation-tail", version);
    }

    public String compilerInstructions(RolePackRegistry.RolePack pack, List<String> technologies,
                                       TestPolicy testPolicy) {
        String stack = technologies == null || technologies.isEmpty() ? "未确定技术栈" : String.join("/", technologies);
        String testing = switch (testPolicy) {
            case REQUIRED -> requiredTestInstructions(pack.id(), pack.version());
            case OPTIONAL -> packFragment("role-pack.2026-08-dynamic-v7.common.compiler-optional", pack.version());
            case NOT_APPLICABLE -> packFragment("role-pack.2026-08-dynamic-v7.common.compiler-not-applicable", pack.version());
        };
        return "Role Pack: " + pack.id() + "@" + pack.version() + "（" + pack.displayName() + "）。\n"
                + "技术/制品上下文：" + stack + "。\n" + testing;
    }

    public String compilerPlanningExample(String rolePackId) { return compilerPlanningExample(rolePackId, RolePackRegistry.VERSION); }

    public String compilerPlanningExample(String rolePackId, String version) {
        return switch (rolePackId) {
            case "software-java" -> packFragment("role-pack.2026-08-dynamic-v7.software-java.compiler-example", version);
            case "software-python" -> packFragment("role-pack.2026-08-dynamic-v7.software-python.compiler-example", version);
            case "software-node" -> packFragment("role-pack.2026-08-dynamic-v7.software-node.compiler-example", version);
            case "software-mixed" -> packFragment("role-pack.2026-08-dynamic-v7.software-mixed.compiler-example", version);
            case "local-maintenance" -> packFragment("role-pack.2026-08-dynamic-v7.local-maintenance.compiler-example", version);
            case "document-markdown-docx" -> packFragment("role-pack.2026-08-dynamic-v7.document-markdown-docx.compiler-example", version);
            case "tabular-conversion" -> packFragment("role-pack.2026-08-dynamic-v7.tabular-conversion.compiler-example", version);
            case "read-only-report" -> packFragment("role-pack.2026-08-dynamic-v7.read-only-report.compiler-example", version);
            default -> packFragment("role-pack.2026-08-dynamic-v7.default.compiler-example", version);
        };
    }

    private String requiredTestInstructions(String rolePackId, String version) {
        return switch (rolePackId) {
            case "software-java" -> packFragment("role-pack.2026-08-dynamic-v7.software-java.compiler-required-test", version);
            case "software-python" -> packFragment("role-pack.2026-08-dynamic-v7.software-python.compiler-required-test", version);
            case "software-node" -> packFragment("role-pack.2026-08-dynamic-v7.software-node.compiler-required-test", version);
            case "software-mixed" -> packFragment("role-pack.2026-08-dynamic-v7.software-mixed.compiler-required-test", version);
            case "software-generic" -> packFragment("role-pack.2026-08-dynamic-v7.software-generic.compiler-required-test", version);
            default -> packFragment("role-pack.2026-08-dynamic-v7.default.compiler-required-test", version);
        };
    }

    public String compilerInstructions(String rolePackId, String rolePackVersion,
                                       io.opencode.loopper.domain.ExecutionStrategy executionStrategy,
                                       List<String> technologies, TestPolicy testPolicy) {
        return compilerInstructions(new RolePackRegistry.RolePack(rolePackId, rolePackVersion, rolePackId,
                executionStrategy, testPolicy), technologies, testPolicy);
    }

    public String compilerInstructions(TaskProfileService.View profile) {
        RolePackRegistry.RolePack pack = new RolePackRegistry.RolePack(profile.rolePackId(), profile.rolePackVersion(),
                profile.rolePackId(), profile.executionStrategy(), profile.testPolicy());
        return compilerInstructions(pack, profile.technologies(), profile.testPolicy());
    }

    /** Historical packs retain the former Java default text without claiming a bundled historical revision. */
    private static String packFragment(String id, String frozenVersion) {
        return RolePackRegistry.VERSION.equals(frozenVersion) ? RolePromptResources.read(id) : RolePromptResources.readBaseline(id);
    }

    private static String header(TaskProfileService.View profile) {
        return "Frozen Role Pack " + profile.rolePackId() + "@" + profile.rolePackVersion()
                + "; intent=" + profile.intent() + "; workflow=" + profile.workflowTemplate()
                + "; execution=" + profile.executionStrategy() + "; testPolicy=" + profile.testPolicy() + ".";
    }
}
