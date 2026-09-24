package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;
import io.opencode.loopper.domain.WorkflowTemplate;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.runtime.MachineRoleContractCatalog;

/** Builds the interactive, read-only prompt for one frozen work package. */
final class DesignerPackagePromptFactory {
    private final TaskProfileService taskProfiles;
    private final RolePromptComposer rolePrompts;
    private final WorkPackageRoleService workPackageRoles;
    private final DesignerPackageContext context;

    DesignerPackagePromptFactory(TaskProfileService taskProfiles, RolePromptComposer rolePrompts,
                                 WorkPackageRoleService workPackageRoles, DesignerPackageContext context) {
        this.taskProfiles = taskProfiles;
        this.rolePrompts = rolePrompts;
        this.workPackageRoles = workPackageRoles;
        this.context = context;
    }

    String build(DesignerSessionRow session, ProjectRow project, DesignRequirementRevisionRow revision,
                 DesignWorkPackageRow workPackage, TaskDecompositionRow decomposition,
                 boolean questionRequired, boolean nativeQuestion) {
        return build(session, project, revision, workPackage, decomposition, questionRequired, nativeQuestion, false);
    }

    String build(DesignerSessionRow session, ProjectRow project, DesignRequirementRevisionRow revision,
                 DesignWorkPackageRow workPackage, TaskDecompositionRow decomposition,
                 boolean questionRequired, boolean nativeQuestion, boolean candidateChannel) {
        WorkPackageRoleService.View packageRole = workPackageRoles.get(workPackage);
        boolean directSoftware = taskProfiles.workflowTemplateIncludingSuperseded(session.id())
                == WorkflowTemplate.DIRECT_SOFTWARE_DESIGN;
        String turnContract = directSoftware || !questionRequired ? (RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block01.segment0")
                + String.format("%s", (Object) (directSoftware
                ? RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.direct-stage-guidance")
                : RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.package-stage-guidance")))
                + "\n") : nativeQuestion ? RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block02") : RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block03");
        if (candidateChannel) turnContract = (questionRequired && nativeQuestion
                ? "Before designing, call question exactly once with 1-3 concise questions and await the answers.\n"
                : "Do not call question or ask the user anything.\n")
                + RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.candidate-submission-guidance")
                + (directSoftware ? "Use 1-6 stages." : "Use 1-3 stages.");
        return (RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block04.segment0")
                + String.format("%s", (Object) (MachineRoleContractCatalog.packageDesignerCard(candidateChannel) + "\n"
                        + rolePrompts.packageDesignerInstructions(taskProfiles.current(session.id()),
                        packageRole.rolePackId(), packageRole.executionStrategy(), packageRole.technologies(),
                        packageRole.testPolicy())))
                + "\n\nProject root: "
                + String.format("%s", (Object) (project.rootPath()))
                + RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block04.segment2")
                + String.format("%d", (Object) (revision.revision()))
                + ":\n"
                + String.format("%s", (Object) (context.original(revision, workPackage)))
                + "\n\nFrozen decomposition plan:\n"
                + String.format("%s", (Object) (decomposition.planJson()))
                + "\n\nCurrent package "
                + String.format("%s", (Object) (workPackage.packageId()))
                + " (only scope to design):\n"
                + String.format("%s", (Object) (context.packageScope(workPackage)))
                + RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block04.segment7")
                + String.format("%s", (Object) (context.prerequisites(revision.id(), workPackage)))
                + RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block04.segment8")
                + String.format("%s", (Object) (context.previousDesign(workPackage)))
                + RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block04.segment9")
                + String.format("%s", (Object) (context.decisions(session, workPackage)))
                + "\n\n"
                + String.format("%s", (Object) (repositoryContext(session.taskId() != null)))
                + "\n\n"
                + String.format("%s", (Object) (turnContract))
                + "\n\n"
                + String.format("%s", (Object) (candidateChannel ? RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.candidate-output-guidance")
                        : markdownContract()))
                + RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block04.segment13"));
    }
    static String markdownContract() {
        return RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block05");
    }

    static String repositoryContext(boolean rollingExecution) {
        return rollingExecution ? RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block06") : RolePromptResources.read("prompt.v1.DesignerPackagePromptFactory.block07");
    }
}
