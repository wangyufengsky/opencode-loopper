package io.opencode.loopper.template;

import java.util.List;

/** Frozen input and model claims for version-oriented static review. */
public final class SnapshotReview {
    private SnapshotReview() { }
    public static final String ID = "SNAPSHOT_CODE_REVIEW";
    public enum Mode { DATE_INCREMENTAL, FULL }
    public enum Verdict { SUPPORTED, UNDETERMINED, DISMISSED, DUPLICATE }
    public enum Attribution { CHANGE_RELATED, EXISTING, UNDETERMINED }
    public record File(String version, String path, String blob, String mode, long bytes, String limitation) { }
    public record Unit(String id, String path, String beforePath, String change, String excerpt, String limitation) { }
    public record Snapshot(String sourceSha, String baselineSha, String targetSha, String baselineTree, String targetTree,
                           String capturedAt, String startInclusive, String endExclusive, String selectionBasis,
                           boolean nonMonotonic, boolean noChanges, List<File> files, List<Unit> units) { }
    public record Group(String key, String title, String objective, List<String> unitIds, List<String> contextPaths) { }
    public record Relation(String key, String fromGroup, String toGroup, String question) { }
    public record Plan(List<Group> groups, List<Relation> relations) { }
    public record Reference(String version, String path, String blob, int startLine, int endLine, String quote) { }
    public record Coverage(String unitId, String conclusion, List<Reference> evidence, List<String> limitations) { }
    public record Finding(String key, TemplateAnalysis.Severity severity, String title, String trigger, String behavior,
                          String recommendation, Attribution attribution, List<Reference> evidence) { }
    public record Analysis(List<Coverage> coverage, List<Finding> findings, List<Group> supplements, List<String> limitations) { }
    public record Decision(String findingKey, Verdict verdict, String reason, String duplicateOf, List<Reference> evidence) { }
    public record Review(List<String> checkedUnitIds, List<Decision> decisions, List<Reference> evidence,
                         String conclusion, List<String> limitations) { }
    public record Input(String phase, List<Unit> units, List<Group> groups, List<Relation> relations,
                        List<String> dependencies, String objective, String analysisBatchId) { }
    public static boolean applies(String id) { return ID.equals(id); }
    public static boolean batch(String purpose) { return purpose != null && purpose.startsWith("SNAPSHOT_"); }
}
