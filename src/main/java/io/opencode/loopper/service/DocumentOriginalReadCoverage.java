package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import org.springframework.stereotype.Service;

/** Reading proves scope coverage only; semantic completeness remains an independent Judge obligation. */
@Service
public final class DocumentOriginalReadCoverage {
    private final DocumentTemplateMapper documents;
    private final LoopperMapper candidates;
    private final DocumentDevelopmentMapper scopes;
    public DocumentOriginalReadCoverage(DocumentTemplateMapper documents, LoopperMapper candidates, DocumentDevelopmentMapper scopes) {
        this.documents = documents; this.candidates = candidates; this.scopes = scopes;
    }
    public boolean complete(String taskId, String candidateRunId) {
        var candidate = candidates.findCandidateSubmissionRun(candidateRunId).orElse(null);
        return sessionComplete(taskId, candidate == null ? null : candidate.externalSessionId());
    }
    public boolean sessionComplete(String taskId, String sessionId) {
        var run = documents.findTask(taskId).orElse(null);
        if (run == null || !run.directDocuments() || !run.templateId().equals("REQUIREMENT_DEVELOPMENT")) return true;
        if (sessionId == null) return false;
        var scope = scopes.scope(sessionId).orElse(null);
        if (scope == null || !scope.runId().equals(run.id()) || scope.requirementRevision() != run.sourceRevision()) return false;
        var files = documents.sourceFiles(run.id(), run.sourceRevision());
        return !files.isEmpty() && files.stream().allMatch(file -> documents.sourceReadCount(sessionId,
                run.id(), run.sourceRevision(), file.id()) == file.sectionCount());
    }
}
