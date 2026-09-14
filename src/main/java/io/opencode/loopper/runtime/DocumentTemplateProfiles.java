package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;

/** Closed document roles: built-ins and third-party MCP remain denied. */
public final class DocumentTemplateProfiles {
    private DocumentTemplateProfiles() { }
    public static boolean contains(OpenCodeClient.SessionProfile profile) {
        return profile == OpenCodeClient.SessionProfile.DOCUMENT_REQUIREMENTS_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.DOCUMENT_REQUIREMENT_REVIEW_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.REQUIREMENT_CODE_ASSESSMENT_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.REQUIREMENT_ASSESSMENT_REVIEW_NO_TOOLS;
    }
    public static boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.DOCUMENT_REQUIREMENTS_V1
                || kind == MachineCandidateKind.DOCUMENT_REQUIREMENT_REVIEW_V1
                || kind == MachineCandidateKind.REQUIREMENT_CODE_ASSESSMENT_V1
                || kind == MachineCandidateKind.REQUIREMENT_ASSESSMENT_REVIEW_V1;
    }
    public static OpenCodeClient.SessionProfile profile(MachineCandidateKind kind) {
        return switch (kind) {
            case DOCUMENT_REQUIREMENTS_V1 -> OpenCodeClient.SessionProfile.DOCUMENT_REQUIREMENTS_NO_TOOLS;
            case DOCUMENT_REQUIREMENT_REVIEW_V1 -> OpenCodeClient.SessionProfile.DOCUMENT_REQUIREMENT_REVIEW_NO_TOOLS;
            case REQUIREMENT_CODE_ASSESSMENT_V1 -> OpenCodeClient.SessionProfile.REQUIREMENT_CODE_ASSESSMENT_NO_TOOLS;
            case REQUIREMENT_ASSESSMENT_REVIEW_V1 -> OpenCodeClient.SessionProfile.REQUIREMENT_ASSESSMENT_REVIEW_NO_TOOLS;
            default -> throw new IllegalArgumentException("Not a document-template candidate");
        };
    }
    public static List<String> readTools(OpenCodeClient.SessionProfile profile) {
        if (!contains(profile)) return List.of();
        if (profile == OpenCodeClient.SessionProfile.REQUIREMENT_CODE_ASSESSMENT_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.REQUIREMENT_ASSESSMENT_REVIEW_NO_TOOLS) {
            return List.of("list_requirement_documents", "list_document_sections", "read_document_section", "list_requirement_code", "read_requirement_code", "search_requirement_code", "list_requirement_assessments", "read_requirement_assessment");
        }
        return List.of("list_requirement_documents", "list_document_sections", "read_document_section");
    }
}
