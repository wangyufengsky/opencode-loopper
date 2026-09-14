package io.opencode.loopper.template;

import java.util.List;

/** Requirements are described with their code conclusions, never compiled in a prerequisite run. */
public final class DirectDocumentAssessment {
    private DirectDocumentAssessment() { }
    public record Source(String fileId, int section) { }
    public record Entry(String title, String statement, List<Source> sources, List<String> issues,
                        RequirementCodeAssessment.Item assessment) { }
    public record Skipped(Source source, String reason) { }
    public record Candidate(String snapshotSha, List<Entry> entries, List<RequirementCodeAssessment.Finding> findings,
                            List<Skipped> skippedSections, List<String> limitations) { }
    public record Correction(String requirementKey, String findingKey, Source source, String detail) { }
    public record Review(String snapshotSha, boolean approved, List<String> reviewedRequirementKeys,
                         List<String> reviewedFindingKeys, List<Source> checkedSections, List<Correction> corrections) { }
}
