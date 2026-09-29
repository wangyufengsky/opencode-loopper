package io.opencode.loopper.workflow;

import static io.opencode.loopper.domain.MachineCandidateKind.*;
import static io.opencode.loopper.workflow.WorkModule.Family.*;
import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;
import java.util.Optional;

/** Executable source/document protocol inventory shared by existing adapters and the public catalog. */
public final class BuiltinWorkModules {
    private BuiltinWorkModules() { }
    private static final List<WorkModule> MODULES = List.of(
            module("source.design", "编写源码详细设计", SOURCE_DESIGN, "SOURCE_DETAILED_DESIGN_NO_TOOLS", "SourceDesign.Input", SOURCE_DETAILED_DESIGN_V1),
            module("source.design-review", "复核源码详细设计", SOURCE_DESIGN, "SOURCE_DESIGN_REVIEW_NO_TOOLS", "SourceDesign.Input", SOURCE_DESIGN_REVIEW_V1),
            module("document.requirements", "提取文档需求", DOCUMENT_ANALYSIS, "DOCUMENT_REQUIREMENTS_NO_TOOLS", "DocumentModelInput", DOCUMENT_REQUIREMENTS_V1),
            module("document.requirement-review", "复核文档需求", DOCUMENT_ANALYSIS, "DOCUMENT_REQUIREMENT_REVIEW_NO_TOOLS", "DocumentModelInput", DOCUMENT_REQUIREMENT_REVIEW_V1),
            module("document.code-assessment", "评估需求实现", DOCUMENT_ANALYSIS, "REQUIREMENT_CODE_ASSESSMENT_NO_TOOLS", "DocumentModelInput", REQUIREMENT_CODE_ASSESSMENT_V1),
            module("document.assessment-review", "复核需求实现评估", DOCUMENT_ANALYSIS, "REQUIREMENT_ASSESSMENT_REVIEW_NO_TOOLS", "DocumentModelInput", REQUIREMENT_ASSESSMENT_REVIEW_V1),
            module("document.direct-review", "需求与代码审查", DOCUMENT_ANALYSIS, "DOCUMENT_CODE_ASSESSMENT_V2_NO_TOOLS", "DocumentModelInput", DOCUMENT_CODE_ASSESSMENT_V2),
            module("document.direct-review-check", "复核需求与代码审查", DOCUMENT_ANALYSIS, "DOCUMENT_CODE_REVIEW_V2_NO_TOOLS", "DocumentModelInput", DOCUMENT_CODE_REVIEW_V2));

    public static List<WorkModule> all() { return MODULES; }
    public static Optional<WorkModule> find(MachineCandidateKind kind, WorkModule.Family family) {
        return MODULES.stream().filter(module -> module.resultContract() == kind && module.family() == family).findFirst();
    }
    public static boolean containsRole(String binding, WorkModule.Family family) {
        return MODULES.stream().anyMatch(module -> module.family() == family && module.roleBinding().equals(binding));
    }
    public static WorkModule require(MachineCandidateKind kind, WorkModule.Family family) {
        return find(kind, family).orElseThrow(() -> new IllegalArgumentException("Unsupported work module: " + kind));
    }
    private static WorkModule module(String id, String title, WorkModule.Family family, String role,
            String input, MachineCandidateKind result) {
        return new WorkModule(id, 1, title, family, role, input, result, WorkModule.Completion.ACCEPTED_RESULT_AND_STOPPED);
    }
}
