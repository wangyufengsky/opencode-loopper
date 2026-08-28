package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignWorkPackageState;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.List;
import java.util.stream.Collectors;

/** Projects unresolved mutation ownership into the package recovery routes. */
final class DesignerMutationOwnershipRecovery {
    private static final Projection NONE = new Projection(false, 0, "");
    private final LoopperMapper mapper;
    private final DesignerAcceptanceWorkflow workflow;

    DesignerMutationOwnershipRecovery(LoopperMapper mapper, DesignerAcceptanceWorkflow workflow) {
        this.mapper = mapper;
        this.workflow = workflow;
    }

    Projection forWaitingInput(DesignerSessionRow session, DesignWorkPackageRow workPackage) {
        if (!DesignerSessionState.WAITING_INPUT.name().equals(session.state())
                || !DesignWorkPackageState.WAITING_INPUT.name().equals(workPackage.state())) return NONE;
        return inspect(session, workPackage);
    }

    Projection inspect(DesignerSessionRow session, DesignWorkPackageRow workPackage) {
        LoopSpecCompilationRow compilation = mapper.findLoopSpecCompilationForPackageRevision(
                session.id(), workPackage.packageId(), workPackage.designRevision()).orElse(null);
        AcceptancePlanningStatus planning = workflow.status(compilation);
        if (planning == null || planning.unresolvedMutationObligationCount() <= 0
                || !"BLOCKED".equals(planning.pathConservation())) return NONE;
        return new Projection(true, planning.unresolvedMutationObligationCount(), prompt(planning));
    }

    void rejectUnchanged(DesignerSessionRow session, DesignWorkPackageRow workPackage) {
        Projection projection = inspect(session, workPackage);
        if (!projection.required()) return;
        throw new ConflictException("DESIGN_UNCHANGED_RECOMPILE_BLOCKED",
                "当前设计稿仍有 " + projection.unresolvedCount()
                        + " 条必改路径没有可证明的阶段归属；请补充阶段负责路径或恢复当前包设计");
    }

    String promptOr(DesignerSessionRow session, DesignWorkPackageRow workPackage, String fallback) {
        Projection projection = inspect(session, workPackage);
        return projection.required() ? projection.prompt() : fallback;
    }

    private static String prompt(AcceptancePlanningStatus planning) {
        List<String> unresolved = planning.mutationBindingReasons().stream()
                .filter(reason -> reason != null && !reason.isBlank() && !reason.contains("归属阶段："))
                .limit(128).toList();
        String details = unresolved.isEmpty()
                ? "- 当前有 " + planning.unresolvedMutationObligationCount() + " 条必改路径尚未归属阶段"
                : unresolved.stream().map(reason -> "- " + reason).collect(Collectors.joining("\n"));
        return """
                TARGETED MUTATION OWNERSHIP RECOVERY:
                The previous complete design is unchanged and cannot be recompiled safely. Produce one complete
                replacement design, not a patch. Preserve all valid scope, scenarios, review items, test gates,
                dependencies, and deliverables. In the exact `阶段与依赖` table add the `负责路径` column and assign
                every unresolved create/write/move-destination path to exactly one stage. Do not copy all package
                paths into every stage and do not weaken forbidden-path or focused-test constraints.

                Server-proven unresolved mutation ownership:
                %s
                """.formatted(details);
    }

    record Projection(boolean required, int unresolvedCount, String prompt) {
        boolean acceptsFeedback(DesignWorkPackageRow workPackage) {
            return required || DesignWorkPackageState.REVIEWING.name().equals(workPackage.state());
        }
        DesignWorkPackageState nextState() {
            return required ? DesignWorkPackageState.WAITING_INPUT : DesignWorkPackageState.REVIEWING;
        }
        String promptPrefix() { return required ? prompt + "\n\n" : ""; }
    }
}
