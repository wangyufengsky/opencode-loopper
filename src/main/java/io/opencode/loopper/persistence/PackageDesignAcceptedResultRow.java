package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Immutable accepted package-design candidate plus its optimistic settlement marker. */
public record PackageDesignAcceptedResultRow(
        String candidateRunId, String designWorkPackageId, long sourceRevision, long ownerVersion,
        String contractVersion, String canonicalCandidateJson, String canonicalMarkdown,
        String compiledResultJson, String canonicalResultSha256, String settledCompilationId,
        String createdAt, String updatedAt, long version) {
    @AutomapConstructor public PackageDesignAcceptedResultRow { }
}
