package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;
import io.opencode.loopper.runtime.MachineRoleContractCatalog;

/** Centralizes versioned, closed Compiler prompt contracts independently from workflow orchestration. */
final class DesignerCompilerPromptContracts {
    private DesignerCompilerPromptContracts() { }

    static String acceptanceBinding(String packageId, String factsJson, String capabilitiesJson,
                                    int stageLimit, String priorError) {
        return (String.format("%s", (Object) (MachineRoleContractCatalog.legacyCompilerCard()))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block01.segment1")
                + String.format("%d", (Object) (stageLimit))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block01.segment2")
                + String.format("%s", (Object) (priorError == null || priorError.isBlank() ? "" : RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.binding-repair-prefix") + priorError))
                + "\n\nFrozen package: "
                + String.format("%s", (Object) (packageId))
                + "\nFrozen DesignFacts:\n"
                + String.format("%s", (Object) (factsJson))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block01.segment5")
                + String.format("%s", (Object) (capabilitiesJson))
                + "\n");
    }

    static String acceptanceDisambiguation(String packageId, String factsJson, String capabilitiesJson,
                                           String lockedResolutionJson, String priorError) {
        return (String.format("%s", (Object) (MachineRoleContractCatalog.card("COMPILER")))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block02.segment1")
                + String.format("%s", (Object) (priorError == null || priorError.isBlank() ? ""
                        : RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.replacement-repair-prefix") + priorError))
                + "\n\nFrozen package: "
                + String.format("%s", (Object) (packageId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block02.segment3")
                + String.format("%s", (Object) (lockedResolutionJson))
                + "\nFrozen DesignFacts:\n"
                + String.format("%s", (Object) (factsJson))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block01.segment5")
                + String.format("%s", (Object) (capabilitiesJson))
                + "\n");
    }

    static String acceptanceClosedChoice(String packageId, String factsJson, String capabilitiesJson,
                                         String lockedResolutionJson, String priorError) {
        return (String.format("%s", (Object) (MachineRoleContractCatalog.closedChoiceCompilerCard()))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block03.segment1")
                + String.format("%s", (Object) (priorError == null || priorError.isBlank() ? ""
                        : RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.replacement-repair-prefix") + priorError))
                + "\n\nFrozen package: "
                + String.format("%s", (Object) (packageId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block02.segment3")
                + String.format("%s", (Object) (lockedResolutionJson))
                + "\nFrozen DesignFacts:\n"
                + String.format("%s", (Object) (factsJson))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block01.segment5")
                + String.format("%s", (Object) (capabilitiesJson))
                + "\n");
    }

    static String planning(String packageId, WorkPackageRoleService.View profile,
                           RolePromptComposer rolePrompts) {
        String example = rolePrompts.compilerPlanningExample(profile.rolePackId(), profile.rolePackVersion());
        return (String.format("%s", (Object) (rolePrompts.compilerInstructions(profile.rolePackId(), profile.rolePackVersion(),
                        profile.executionStrategy(), profile.technologies(), profile.testPolicy())))
                + "\n"
                + String.format("%s", (Object) (MachineRoleContractCatalog.legacySemanticCompilerCard()))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block04.segment2")
                + String.format("%s", (Object) (packageId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block04.segment3")
                + String.format("%s", (Object) (example))
                + "\n");
    }

    static String semanticPlanning(String projectRoot, int requirementRevision, String packageId,
                                   String workflowMode, String prerequisites, String testEvidence,
                                   String sourceIndex, String machineContract, int designRevision, String design) {
        return (RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block05.segment0")
                + String.format("%s", (Object) (projectRoot))
                + "\nRequirement revision: R"
                + String.format("%d", (Object) (requirementRevision))
                + "\nRequired workPackageId: "
                + String.format("%s", (Object) (packageId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block05.segment3")
                + String.format("%s", (Object) (packageId))
                + "-AC-\nWorkflow mode: "
                + String.format("%s", (Object) (workflowMode))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block05.segment5")
                + String.format("%s", (Object) (prerequisites))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block05.segment6")
                + String.format("%s", (Object) (testEvidence))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block05.segment7")
                + String.format("%s", (Object) (sourceIndex))
                + "\n\n"
                + String.format("%s", (Object) (machineContract))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block05.segment9")
                + String.format("%d", (Object) (designRevision))
                + ":\n"
                + String.format("%s", (Object) (design))
                + "\n");
    }

    static String finalJson(String packageId, String draftJson, String planJson, String machineContract) {
        return (RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block06.segment0")
                + String.format("%s", (Object) (packageId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block06.segment1")
                + String.format("%s", (Object) (draftJson))
                + "\nFrozen planning:\n"
                + String.format("%s", (Object) (planJson))
                + "\n\n"
                + String.format("%s", (Object) (machineContract))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block06.segment4"));
    }

    static String legacyFinal(String projectRoot, String packageId, String draftJson, String prerequisites,
                              String stageRange, String machineContract, int designRevision,
                              int requirementRevision, String design) {
        return (RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block07.segment0")
                + String.format("%s", (Object) (projectRoot))
                + "\nRequired workPackageId: "
                + String.format("%s", (Object) (packageId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block05.segment3")
                + String.format("%s", (Object) (packageId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block07.segment3")
                + String.format("%s", (Object) (draftJson))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block07.segment4")
                + String.format("%s", (Object) (prerequisites))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block07.segment5")
                + String.format("%s", (Object) (stageRange))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block07.segment6")
                + String.format("%s", (Object) (packageId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block07.segment7")
                + String.format("%s", (Object) (machineContract))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block07.segment8")
                + String.format("%d", (Object) (designRevision))
                + " for requirement R"
                + String.format("%d", (Object) (requirementRevision))
                + ":\n"
                + String.format("%s", (Object) (design))
                + "\n");
    }

    static String compiledPackage(String packageId) {
        String criterionId = packageId + "-AC-1";
        return (RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block08.segment0")
                + String.format("%s", (Object) (criterionId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block08.segment1")
                + String.format("%s", (Object) (criterionId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block08.segment2")
                + String.format("%s", (Object) (criterionId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block08.segment3")
                + String.format("%s", (Object) (packageId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block08.segment4")
                + String.format("%s", (Object) (packageId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block08.segment5")
                + String.format("%s", (Object) (criterionId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block08.segment6")
                + String.format("%s", (Object) (criterionId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block08.segment7")
                + String.format("%s", (Object) (criterionId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block08.segment8")
                + String.format("%s", (Object) (packageId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block08.segment9")
                + String.format("%s", (Object) (criterionId))
                + RolePromptResources.read("prompt.v1.DesignerCompilerPromptContracts.block08.segment10"));
    }
}
