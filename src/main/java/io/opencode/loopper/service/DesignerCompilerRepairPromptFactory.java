package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;

/** Builds bounded Compiler repair prompts after all repository evidence has been frozen. */
final class DesignerCompilerRepairPromptFactory {
    String planning(int repairCount, int maxRepairs, String code, String detail, String prerequisites,
                    String declaredTests, String machineContract, String design) {
        return (RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.block01.segment0")
                + String.format("%d", (Object) (repairCount))
                + "/"
                + String.format("%d", (Object) (maxRepairs))
                + ". Error code: "
                + String.format("%s", (Object) (code))
                + ". Error detail: "
                + String.format("%s", (Object) (safe(detail)))
                + RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.block01.segment4")
                + String.format("%s", (Object) (prerequisites))
                + RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.block01.segment5")
                + String.format("%s", (Object) (declaredTests))
                + "\n\n"
                + String.format("%s", (Object) (machineContract))
                + RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.block01.segment7")
                + String.format("%s", (Object) (design))
                + "\n");
    }

    String semanticPatch(String packageId, String code, String detail, String semanticPlan) {
        return (RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.block02.segment0")
                + String.format("%s", (Object) (packageId))
                + ". Error code: "
                + String.format("%s", (Object) (code))
                + ". Error detail: "
                + String.format("%s", (Object) (safe(detail)))
                + RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.block02.segment3")
                + String.format("%s", (Object) (semanticPlan))
                + RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.block02.segment4"));
    }

    String legacyFinal(int repairCount, int maxRepairs, String code, String detail, String machineContract) {
        return RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.legacy-final-intro")
                + String.format("%d", (Object) repairCount) + "/" + String.format("%d", (Object) maxRepairs)
                + ". Error code: " + String.format("%s", (Object) code)
                + ". Error detail: " + String.format("%s", (Object) detail) + ".\n\n"
                + String.format("%s", (Object) machineContract)
                + RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.final-outro");
    }

    String completeFinal(int repairCount, int maxRepairs, String code, String detail,
                         String frozenPlanning, String machineContract) {
        return RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.complete-final-intro")
                + String.format("%d", (Object) repairCount) + "/" + String.format("%d", (Object) maxRepairs)
                + ". Error code: " + String.format("%s", (Object) code)
                + ". Error detail: " + String.format("%s", (Object) detail)
                + RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.complete-final-contract")
                + String.format("%s", (Object) frozenPlanning) + "\n\n"
                + String.format("%s", (Object) machineContract)
                + RolePromptResources.read("prompt.v1.DesignerCompilerRepairPromptFactory.final-outro");
    }

    private String safe(String value) {
        if (value == null) return "Unknown error";
        return value.substring(0, Math.min(value.length(), 4_000));
    }
}
