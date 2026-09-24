package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builds the bounded PROJECT_CONVENTION_V1 private-submission prompt from frozen evidence IDs. */
final class ProjectConventionCandidatePromptFactory {
    private static final int MAX_PROMPT_BYTES = 128 * 1024;

    String internal(MachineCandidateSubmission.RunSnapshot run,
                    ProjectConventionCompilation.EvidenceCatalog evidence,
                    String exactSubmitTool) {
        if (run == null || evidence == null || exactSubmitTool == null || exactSubmitTool.isBlank()
                || run.candidateKind() != MachineCandidateKind.PROJECT_CONVENTION_V1
                || run.submissionChannel() != MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                || run.state() != MachineCandidateRunState.OPEN
                || run.maxAttempts() != ProjectConventionCandidatePolicy.MAX_ATTEMPTS
                || !ProjectConventionCompilation.CONTRACT_VERSION.equals(run.contractVersion())) {
            throw new IllegalArgumentException("Complete open Convention candidate contract is required");
        }
        String prompt = (RolePromptResources.read("prompt.v1.ProjectConventionCandidatePromptFactory.block01.segment0")
                + String.format("%s", (Object) (exactSubmitTool))
                + RolePromptResources.read("prompt.v1.ProjectConventionCandidatePromptFactory.block01.segment1")
                + String.format("%s", (Object) (CandidateCorrectionPolicy.prompt(run)))
                + RolePromptResources.read("prompt.v1.ProjectConventionCandidatePromptFactory.block01.segment2")
                + String.format("%s", (Object) (run.runId()))
                + "\nexpectedSubmissionRevision: "
                + String.format("%d", (Object) (run.version()))
                + "\nsourceRevision: "
                + String.format("%d", (Object) (run.sourceRevision()))
                + "\nownerVersion: "
                + String.format("%d", (Object) (run.ownerVersion()))
                + "\nallowed componentKeys: "
                + String.format("%s", (Object) (ids(evidence.components().stream()
                        .map(ProjectConventionCompilation.ComponentEvidence::key).toList())))
                + "\nallowed commandIds: "
                + String.format("%s", (Object) (ids(evidence.commands().stream()
                        .map(ProjectConventionCompilation.CommandEvidence::id).toList())))
                + "\nallowed pathIds: "
                + String.format("%s", (Object) (ids(evidence.paths().stream()
                        .map(ProjectConventionCompilation.PathEvidence::id).toList())))
                + "\n");
        if (prompt.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) {
            throw new ConflictException("PROJECT_CONVENTION_CANDIDATE_PROMPT_TOO_LARGE",
                    "Frozen Convention evidence cannot fit the bounded candidate prompt");
        }
        return prompt;
    }

    private static String ids(List<String> values) {
        return values.isEmpty() ? "[]" : values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }
}
