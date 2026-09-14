package io.opencode.loopper.template;

import java.util.List;

/** Static evidence never implies that this run executed tests. */
public final class RequirementCodeAssessment {
    private RequirementCodeAssessment() { }
    public enum Conclusion { SATISFIED, PARTIAL, INCORRECT, NOT_IMPLEMENTED, UNDETERMINED }
    public enum FindingKind { DEFECT, VALIDATION_GAP, SUGGESTION }
    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
    public record CodeReference(String path, String blobSha, int startLine, int endLine, String quote) { }
    public record Item(String requirementKey, Conclusion conclusion, String rationale,
                       List<CodeReference> evidence, List<String> checkedPaths, String missingEntryEvidence,
                       String testSourceCoverage, List<String> limitations) { }
    public record Finding(String key, FindingKind kind, Severity severity, String title, String trigger,
                          String impact, String recommendation, List<String> requirementKeys,
                          List<CodeReference> evidence, String rootCauseKey) { }
    public record Candidate(String snapshotSha, List<Item> items, List<Finding> findings, List<String> limitations) { }
    public record Correction(String requirementKey, String findingKey, String detail) { }
    public record Review(String snapshotSha, boolean approved, List<String> reviewedRequirementKeys,
                         List<String> reviewedFindingKeys, List<Correction> corrections) { }
}
