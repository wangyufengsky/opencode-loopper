package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.PackageDesignCompilation.*;
import java.util.List;

/** Checks only server-frozen inputs, before spending a model call or blaming candidate fields. */
final class PackageDesignInputPreflight {
    private PackageDesignInputPreflight() { }
    static List<Problem> problems(PackageDesignCompilation.Input input) {
        Catalog empty = new Catalog(CONTRACT_VERSION_V7, input.workPackage().packageId(),
                input.workPackage().designRevision(), "", true, List.of(), List.of(), List.of());
        try {
            Catalog frozen = new DesignerMutationObligationExtractor().frozenInput(empty, input.requirementText(), input.scopeIn(),
                    input.scopeOut(), input.deliverables());
            return frozen.mutationIssues().stream()
                    .filter(code -> code.equals("MUTATION_PATH_SCOPE_CONFLICT") || code.equals("FROZEN_MUTATION_PATH_SCOPE_CONFLICT"))
                    .map(code -> new Problem(code.equals("FROZEN_MUTATION_PATH_SCOPE_CONFLICT")
                            ? "AMBIGUOUS_MUTATION_PATH_SCOPE" : code, "/frozenInput",
                    "冻结需求或范围之间存在冲突，候选字段无法修复", List.of(), ProblemClass.HUMAN_REQUIRED,
                    false, "consistent frozen scope", "conflicting frozen constraints",
                    "请在本地调整冻结需求或任务范围后重新设计；不要反复修改候选来绕过范围边界")).toList();
        } catch (BadRequestException invalid) {
            return List.of(new Problem(invalid.code(), "/frozenInput", "冻结输入无法安全编译", List.of(),
                    ProblemClass.HUMAN_REQUIRED, false, "valid frozen input", "invalid frozen constraints",
                    "请调整冻结需求或范围后重新设计"));
        }
    }

}
