package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.workflow.BuiltinWorkModules;
import static io.opencode.loopper.workflow.WorkModule.Family.DOCUMENT_ANALYSIS;
import java.util.List;

/** Closed document roles: built-ins and third-party MCP remain denied. */
public final class DocumentTemplateProfiles {
    private DocumentTemplateProfiles() { }
    public static boolean contains(OpenCodeClient.SessionProfile profile) {
        return profile != null && BuiltinWorkModules.containsRole(profile.name(), DOCUMENT_ANALYSIS);
    }
    public static boolean supports(MachineCandidateKind kind) {
        return BuiltinWorkModules.find(kind, DOCUMENT_ANALYSIS).isPresent();
    }
    public static OpenCodeClient.SessionProfile profile(MachineCandidateKind kind) {
        return OpenCodeClient.SessionProfile.valueOf(BuiltinWorkModules.require(kind, DOCUMENT_ANALYSIS).roleBinding());
    }
    public static List<String> readTools(OpenCodeClient.SessionProfile profile) {
        if (!contains(profile)) return List.of();
        if (profile == OpenCodeClient.SessionProfile.DOCUMENT_CODE_ASSESSMENT_V2_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.DOCUMENT_CODE_REVIEW_V2_NO_TOOLS)
            return List.of("get_document_review_work", "check_document_review_candidate", "read_document_resource",
                    "list_requirement_documents", "list_document_sections", "read_document_section", "list_requirement_code",
                    "read_requirement_code", "search_requirement_code", "list_requirement_assessments", "read_requirement_assessment");
        if (profile == OpenCodeClient.SessionProfile.REQUIREMENT_CODE_ASSESSMENT_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.REQUIREMENT_ASSESSMENT_REVIEW_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.DOCUMENT_CODE_ASSESSMENT_V2_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.DOCUMENT_CODE_REVIEW_V2_NO_TOOLS) {
            return List.of("read_document_resource", "list_requirement_documents", "list_document_sections", "read_document_section", "list_requirement_code", "read_requirement_code", "search_requirement_code", "list_requirement_assessments", "read_requirement_assessment");
        }
        return List.of("read_document_resource", "list_requirement_documents", "list_document_sections", "read_document_section");
    }
}
