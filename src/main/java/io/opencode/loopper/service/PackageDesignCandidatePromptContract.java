package io.opencode.loopper.service;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Complete examples for the model-owned document; executable policy stays in the codec/compiler. */
final class PackageDesignCandidatePromptContract {
    private PackageDesignCandidatePromptContract() { }

    static String readyExample() {
        return """
                {"contractVersion":"PACKAGE_DESIGN_V1","outcome":"READY",
                 "requirements":[{"key":"REQ-1","statement":"需求语义"}],
                 "scenarios":[{"key":"SC-1","title":"场景标题","precondition":"前置或触发",
                  "action":"操作","observableResult":"可观察结果","invariant":"保持不变","requirementRefs":["REQ-1"]}],
                 "deliverables":[{"key":"DEL-1","kind":"DELIVERABLE","target":"src/example.txt",
                  "description":"交付说明","requirementRefs":["REQ-1"]}],
                 "reviews":[{"key":"REV-1","title":"人工评审标题","criteria":"可判断的主观标准",
                  "humanOnlyReason":"需要人工判断的具体原因","requirementRefs":["REQ-1"]}],
                 "stages":[{"key":"STAGE-1","title":"阶段标题","objective":"阶段目标",
                  "includes":["SC-1","DEL-1","REV-1"],"dependencies":[]}],"gapCodes":[]}
                """;
    }

    static String instructions() {
        return """
                The following is a complete shape example, not repository evidence; replace all example values.
                %s
                Every key is a unique candidate-local reference. kind is SCOPE or DELIVERABLE. Every scenario,
                deliverable and review has requirementRefs; each stage includes their keys and depends only on
                earlier stage keys. Include the deliverable in its owning stage so the server can prove ownership.
                Do not rename fields or add properties. Text fields are strings; all collections remain arrays.
                reviews is [] unless a real subjective outcome needs criteria AND humanOnlyReason; never fabricate
                a review to match this example. READY has gapCodes:[] and non-empty requirements/scenarios/deliverables/stages.
                NEEDS_INPUT keeps all root collections, uses only supported gapCodes and requests real missing
                design semantics; it is not a Markdown fallback or a way to escape a field error.
                Allowed gapCodes: %s.
                """.formatted(readyExample(), Arrays.stream(DesignerSemanticContracts.DesignGapCode.values())
                .map(Enum::name).collect(Collectors.joining(", ")));
    }
}
