package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;

/** Read-only source roles obtain only their own frozen-source and candidate tools. */
public final class SourceTemplateProfiles {
    private SourceTemplateProfiles() { }
    public static boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.SOURCE_DETAILED_DESIGN_V1 || kind == MachineCandidateKind.SOURCE_DESIGN_REVIEW_V1;
    }
    public static boolean contains(OpenCodeClient.SessionProfile profile) {
        return profile == OpenCodeClient.SessionProfile.SOURCE_DETAILED_DESIGN_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.SOURCE_DESIGN_REVIEW_NO_TOOLS;
    }
    public static OpenCodeClient.SessionProfile profile(MachineCandidateKind kind) {
        return switch (kind) {
            case SOURCE_DETAILED_DESIGN_V1 -> OpenCodeClient.SessionProfile.SOURCE_DETAILED_DESIGN_NO_TOOLS;
            case SOURCE_DESIGN_REVIEW_V1 -> OpenCodeClient.SessionProfile.SOURCE_DESIGN_REVIEW_NO_TOOLS;
            default -> throw new IllegalArgumentException("Not a source-template role");
        };
    }
    public static List<String> readTools() {
        return List.of("get_source_design_work", "list_source_template_files", "read_source_template_file",
                "list_source_design_results", "read_source_design_result");
    }
}
