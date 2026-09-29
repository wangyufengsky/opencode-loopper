package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TemplateTaskMapper;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.template.*;
import java.nio.file.Path;
import java.time.*;
import org.springframework.stereotype.Service;

/** Builds bounded version-tree evidence outside transactions, never checks out or executes project code. */
@Service
public class SnapshotReviewEvidence {
    private final TemplateGitSnapshotService repositories;
    private final TemplateTaskMapper tasks;
    private final SnapshotReviewStore store;
    private final GitEvidenceProcess git;
    private final TemplateGitCaptureGuard guard;
    private final tools.jackson.databind.ObjectMapper json;
    public SnapshotReviewEvidence(TemplateGitSnapshotService repositories, TemplateTaskMapper tasks, SnapshotReviewStore store,
                                  GitEvidenceProcess git, TemplateGitCaptureGuard guard, tools.jackson.databind.ObjectMapper json) {
        this.repositories = repositories; this.tasks = tasks; this.store = store; this.git = git; this.guard = guard; this.json = json;
    }
    public Path repository(String taskId) { return repositories.workspace(taskId).resolve("repository.git"); }
    public SnapshotReview.Snapshot freeze(String taskId) {
        if (store.require(taskId).snapshotJson() != null) return store.snapshot(taskId);
        return guard.capture(taskId, () -> collect(taskId));
    }
    private SnapshotReview.Snapshot collect(String taskId) {
        var run = tasks.findRun(taskId).orElseThrow();
        // Project id is authoritative in the frozen contract.
        var contract = json.readTree(run.contractJson());
        String projectId = contract.path("spec").path("projectId").asText();
        var source = repositories.freeze(taskId, projectId,
                new ProjectBranchService.Branch(run.branchId(), run.branchLabel(), run.branchRef(), run.remoteName()));
        store.source(taskId, source.head());
        var mode = SnapshotReview.Mode.valueOf(store.require(taskId).mode());
        var dates = TemplateDateRange.parse(run.startDate(), run.endDate(), Clock.systemUTC());
        String scope = TemplateGitEvidenceCollector.hash(projectId + "\n" + source.projectRoot() + "\n" + source.projectPrefix());
        var snapshot = new GitReviewReader(git).collect(source.repository(), source.head(), source.projectPrefix(), mode, dates,
                contract.path("definition").path("version").asText(), scope, Instant.now().toString());
        return store.freeze(taskId, snapshot);
    }
}
