package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixed prompt version: examples are schema-tested; frozen source is distinct from model preparation. */
final class PackageDesignV2Prompt {
    static final String VERSION = "PACKAGE_PROMPT_V2_20260907_R2";
    private PackageDesignV2Prompt() { }
    static String example() {
        var json = new ObjectMapper();
        ObjectNode root = (ObjectNode) json.readTree(PackageDesignCandidatePromptContract.readyExample());
        root.put("contractVersion", "PACKAGE_DESIGN_V2");
        root.set("reviews", json.createArrayNode());
        ((ObjectNode) root.path("stages").get(0)).set("includes", json.valueToTree(List.of("SC-1", "DEL-1")));
        root.set("sourceBindings", json.valueToTree(List.of(new PackageDesignV2Document.SourceBinding(
                "SOURCE-1", List.of("REQ-1", "SC-1", "DEL-1"), List.of("REQ-L001")))));
        root.set("relations", json.createArrayNode()); root.set("gapClaims", json.createArrayNode());
        return json.writeValueAsString(root);
    }

    static String build(String base, String original, MachineCandidateSubmission.RunSnapshot run, String tool) {
        return (base == null ? "" : base.replace("PACKAGE_DESIGN_V1", "PACKAGE_DESIGN_V2")) + "\n" + instructions(original)
                + "\nOnly submit using `" + tool + "`. runId=" + run.runId()
                + "; expectedSubmissionRevision=" + run.version() + ". "
                + (run.correctionLimit() == null ? "No submission count limit."
                : "At most " + run.correctionLimit() + " candidate submissions including the first.")
                + RolePromptResources.read("prompt.v1.PackageDesignV2Prompt.correction-guidance");
    }

    static String instructions(String original) {
        return (RolePromptResources.read("prompt.v1.PackageDesignV2Prompt.block01.segment0")
                + String.format("%s", (Object) (example()))
                + "\nAllowed gapCodes: "
                + String.format("%s", (Object) (java.util.Arrays.stream(DesignerSemanticContracts.DesignGapCode.values()).map(Enum::name).toList()))
                + RolePromptResources.read("prompt.v1.PackageDesignV2Prompt.block01.segment2")
                + String.format("%s", (Object) (PackageRequirementSources.prompt(original)))
                + "\n");
    }
}
