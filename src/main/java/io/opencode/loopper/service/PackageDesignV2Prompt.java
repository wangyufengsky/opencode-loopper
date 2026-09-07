package io.opencode.loopper.service;

import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixed prompt version: examples are schema-tested; frozen source is distinct from model preparation. */
final class PackageDesignV2Prompt {
    static final String VERSION = "PACKAGE_PROMPT_V2_20260907";
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
                + "\nUse a fresh idempotencyKey for a changed complete replacement. On REJECTED follow action, JSON Pointer,"
                + " issueId, expected, actual and repairHint; check repairProgress for resolved and introduced errors."
                + " Preserve keys and unaffected branches. Retry using the returned submissionRevision."
                + " diagnosticsComplete=false means the returned list is partial. Stop on ACCEPTED or WAITING_INPUT."
                + " A missing field or test to be built is not a reason to invent a business gap."
                + " V2 has no Markdown substitution for a rejected or missing candidate.";
    }

    static String instructions(String original) {
        return """
                PACKAGE_DESIGN_V2, fixed prompt PACKAGE_PROMPT_V2_20260907.
                Express each behavior as: while/precondition, when/action, shall/observableResult; record exceptions and invariants.
                requirements capture original obligations; scenarios enumerate observable applicable branches; deliverables describe exact
                scoped outputs and native focused tests, including tests to create when the frozen policy permits them.
                stages.includes contains candidate-local scenario/review/deliverable keys, each owned exactly once.
                all = joint conditions; any = alternative applicable branches, ALL branches still need behavior and coverage;
                unless has two operands, base behavior then exception behavior. Operands reference scenarios or relation nodes.
                At most 32 relation nodes and 4 relation levels, no cycles. If too complex, request a split; never truncate logic.
                sourceBindings map candidateRefs to the frozen REQ-Lxxx sourceRefs below. Bind every requirement/scenario and preserve every source.
                References prove provenance only. Re-check negation, exceptions, state changes, idempotency, compensation, cross-stage invariants.
                Never fabricate repository facts, user choices, permissions or executable server fields. Original input outranks preparation.
                READY uses gapCodes:[] and gapClaims:[]. NEEDS_INPUT preserves collections and supplies gapClaims with key, closed code,
                sourceRefs, question and alternatives. A model's gap code is an unverified claim. Distinguish unknown repository facts,
                tests to construct, candidate expression errors, explicitly undecided business alternatives and proven frozen conflicts.
                This is a complete SHAPE example; replace its values and sources. Optional reviews may be empty:
                %s
                Allowed gapCodes: %s
                FROZEN ORIGINAL SOURCES (unchanged original text, with source hashes):
                %s
                """.formatted(example(), java.util.Arrays.stream(DesignerSemanticContracts.DesignGapCode.values()).map(Enum::name).toList(),
                PackageRequirementSources.prompt(original));
    }
}
