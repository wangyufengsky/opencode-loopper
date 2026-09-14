package io.opencode.loopper.runtime;

import java.util.List;
import java.util.Set;

/** Extra read-only tools use independently signed document grants; legacy permission manifests remain valid. */
public final class DocumentDevelopmentProfiles {
    private DocumentDevelopmentProfiles() { }
    public static final List<String> TOOLS = List.of("list_development_requirements", "read_development_requirement", "read_development_source");
    private static final Set<String> PROFILES = Set.of("IMPLEMENTATION", "GENERAL_READ_ONLY", "DESIGNER_INTERACTIVE_READ_ONLY",
            "DECOMPOSER_CANDIDATE_READ_ONLY", "PACKAGE_DESIGN_CANDIDATE_READ_ONLY", "PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY",
            "PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY", "PACKAGE_DESIGN_CANDIDATE_V2_INTERACTIVE_READ_ONLY",
            "ROLLING_PACKAGE_CANDIDATE_READ_ONLY", "ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS", "JUDGE_CANDIDATE_READ_ONLY");
    public static boolean supports(String profile) { return PROFILES.contains(profile); }
}
