package io.opencode.loopper.template;

import java.util.List;

/** Immutable evidence. Every selected SHA is retained, including empty/merge/excluded changes. */
public record TemplateGitEvidence(String version, String branchId, String head, String startDate, String endDate,
                                  String timezone, String mailmapHash, List<Commit> commits) {
    public static final String VERSION = "GIT_EVIDENCE_V3";
    public TemplateGitEvidence { commits = List.copyOf(commits); }

    public record Contributor(String identity, String name, String email, boolean robot) { }
    public record Commit(String sha, List<String> parents, String committedAt, String message,
                         List<Contributor> contributors, String disposition, List<Change> changes,
                         CommitIdentity author, CommitIdentity committer, List<CommitIdentity> coauthors) {
        public Commit(String sha, List<String> parents, String committedAt, String message,
                      List<Contributor> contributors, String disposition, List<Change> changes) {
            this(sha, parents, committedAt, message, contributors, disposition, changes, null, null, List.of());
        }
        public Commit { coauthors = coauthors == null ? List.of() : List.copyOf(coauthors); parents = List.copyOf(parents); contributors = List.copyOf(contributors); changes = List.copyOf(changes); }
    }
    public record CommitIdentity(String rawName, String rawEmail, String name, String email, String time) { }
    public record Change(String evidenceId, String path, String beforeBlob, String afterBlob, long additions,
                         long deletions, boolean binary, long effectiveLines, String exclusionReason, String patch) { }
}
