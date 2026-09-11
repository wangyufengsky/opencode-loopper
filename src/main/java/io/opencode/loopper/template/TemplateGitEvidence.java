package io.opencode.loopper.template;

import java.util.List;

/** Immutable evidence. Every selected SHA is retained, including empty/merge/excluded changes. */
public record TemplateGitEvidence(String version, String branchId, String head, String startDate, String endDate,
                                  String timezone, String mailmapHash, List<Commit> commits) {
    public static final String VERSION = "GIT_EVIDENCE_V2";
    public TemplateGitEvidence { commits = List.copyOf(commits); }

    public record Contributor(String identity, String name, String email, boolean robot) { }
    public record Commit(String sha, List<String> parents, String committedAt, String message,
                         List<Contributor> contributors, String disposition, List<Change> changes) {
        public Commit { parents = List.copyOf(parents); contributors = List.copyOf(contributors); changes = List.copyOf(changes); }
    }
    public record Change(String evidenceId, String path, String beforeBlob, String afterBlob, long additions,
                         long deletions, boolean binary, long effectiveLines, String exclusionReason, String patch) { }
}
