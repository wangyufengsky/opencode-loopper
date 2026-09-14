package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerSemanticContracts.*;
import java.util.List;

/** Deterministic single-package planning preserves every frozen requirement reference. */
final class DesignerDirectPackagePlan {
    private DesignerDirectPackagePlan() { }
    static DecompositionPlanEnvelope create(String requirement, List<String> refs) {
        String goal = requirement.substring(0, Math.min(requirement.length(), 12_000));
        var workPackage = new DecomposedWorkPackage("WP-1", "默认单包设计", goal,
                List.of("当前完整软件需求"), List.of(), List.of(), List.of("完成当前需求的软件变更"),
                List.of("满足完整需求中的可观察业务结果"), refs);
        var coverage = refs.stream().map(ref -> new RequirementCoverageMapping(ref, "WORK_PACKAGE", "WP-1", "默认单包完整覆盖该需求段")).toList();
        return new DecompositionPlanEnvelope("DIRECT_DESIGN", goal, List.of(), List.of(workPackage), coverage,
                List.of(), List.of(), null).normalized();
    }
}
