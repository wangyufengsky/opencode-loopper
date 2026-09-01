package io.opencode.loopper.service;

/** Builds the bounded REVIEWER_REPORT_V1 private-submission prompt. */
final class ReviewerReportCandidatePromptFactory {
    String internal(String roleInstructions, String projectRoot, String requirement,
                    MachineCandidateSubmission.RunSnapshot run, String exactToolName) {
        if (run == null || exactToolName == null || exactToolName.isBlank()
                || projectRoot == null || projectRoot.isBlank()
                || requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("Complete Reviewer candidate prompt facts are required");
        }
        return (roleInstructions == null ? "" : roleInstructions) + """


                REVIEWER_REPORT_V1 PRIVATE SUBMISSION CONTRACT:
                Work only as an independent read-only Reviewer. Read, glob, and grep may be used to inspect the
                managed project. Shell, writes, questions, every user-configured MCP, and every private tool except
                the exact tool below are forbidden. The server owns source-path authority, evidence validation,
                normalization, Markdown rendering, hashes, lifecycle, and the final report.

                Managed project root: %s
                Frozen review requirement:
                %s

                Submit one complete replacement candidate containing exactly title, summary, findings, and
                limitations. findings may contain zero to 128 objects with exactly severity, title, detail, path,
                line, and recommendation. An empty list means no confirmed finding; summary remains required.
                severity is CRITICAL, HIGH, MEDIUM, LOW, or INFO. Every finding must cite one
                managed relative path and exact line observed in this project. Do not submit absolute paths,
                commands, permissions, source contents, hashes, stable server IDs, or lifecycle conclusions.

                runId: %s
                expectedSubmissionRevision: 0
                contractVersion: REVIEWER_REPORT_V1
                exact submit_candidate tool: %s

                Call %s with runId, a fresh idempotencyKey, the complete candidate object, and
                expectedSubmissionRevision. You may call that same exact tool at most three times in this Session.
                On REJECTED, use only its bounded code, JSON Pointer, allowed values, and returned
                submissionRevision to replace the complete candidate and retry. On ACCEPTED or WAITING_INPUT stop.
                Never emit a compatibility payload. The final assistant text is ignored and is never authoritative.
                """.formatted(projectRoot, requirement, run.runId(), exactToolName, exactToolName);
    }
}
