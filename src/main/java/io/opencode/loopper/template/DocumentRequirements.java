package io.opencode.loopper.template;

import java.util.List;

/** Source-backed document semantics. Model text is a candidate, never an execution authorization. */
public final class DocumentRequirements {
    private DocumentRequirements() { }
    public enum Kind { FUNCTION, RULE, PERMISSION, EXCEPTION, ACCEPTANCE, CONSTRAINT }
    public enum Disposition { REQUIREMENT, BACKGROUND, LIMITATION }
    public record Source(String fileId, int section, String quote) { }
    public record Requirement(String key, String title, String group, Kind kind, String statement,
                              List<Source> sources, List<String> acceptance, List<String> issues) { }
    public record Coverage(String fileId, int section, Disposition disposition,
                           List<String> requirementKeys, String reason) { }
    public record Candidate(List<Requirement> requirements, List<Coverage> coverage) { }
    public record SourceSection(String fileId, int section, String title, String text, String sha256) { }
    public record Correction(String requirementKey, String category, String detail, List<Source> sources) { }
    public record Review(boolean approved, List<String> reviewedRequirementKeys,
                         List<Coverage> coverage, List<Correction> corrections) { }
}
