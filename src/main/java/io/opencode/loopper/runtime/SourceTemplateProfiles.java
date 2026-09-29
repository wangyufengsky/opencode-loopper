package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.workflow.BuiltinWorkModules;
import static io.opencode.loopper.workflow.WorkModule.Family.SOURCE_DESIGN;
import java.util.List;

/** Read-only source roles obtain only their own frozen-source and candidate tools. */
public final class SourceTemplateProfiles {
    private SourceTemplateProfiles() { }
    public static boolean supports(MachineCandidateKind kind) {
        return BuiltinWorkModules.find(kind, SOURCE_DESIGN).isPresent();
    }
    public static boolean contains(OpenCodeClient.SessionProfile profile) {
        return profile != null && BuiltinWorkModules.containsRole(profile.name(), SOURCE_DESIGN);
    }
    public static OpenCodeClient.SessionProfile profile(MachineCandidateKind kind) {
        return OpenCodeClient.SessionProfile.valueOf(BuiltinWorkModules.require(kind, SOURCE_DESIGN).roleBinding());
    }
    public static List<String> readTools() {
        return List.of("get_source_design_work", "list_source_template_files", "read_source_template_file",
                "list_source_design_results", "read_source_design_result");
    }
}
