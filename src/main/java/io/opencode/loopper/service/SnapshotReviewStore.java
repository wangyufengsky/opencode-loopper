package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.SnapshotReview;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Short atomic publication of immutable code evidence and append-only review plans. */
@Service
public class SnapshotReviewStore {
    private final SnapshotReviewMapper snapshots;
    private final LoopperMapper tasks;
    private final ObjectMapper json;
    public SnapshotReviewStore(SnapshotReviewMapper snapshots, LoopperMapper tasks, ObjectMapper json) {
        this.snapshots = snapshots; this.tasks = tasks; this.json = json;
    }
    public SnapshotReviewMapper.Run require(String id) { return snapshots.find(id).orElseThrow(() -> new NotFoundException("代码审查任务不存在")); }
    public SnapshotReview.Snapshot snapshot(String id) {
        var run = require(id);
        if (run.snapshotJson() == null || !TemplateGitEvidenceCollector.hash(run.snapshotJson()).equals(run.snapshotSha256()))
            throw conflict();
        return json.readValue(run.snapshotJson(), SnapshotReview.Snapshot.class);
    }
    @Transactional
    public void source(String id, String sha) {
        running(id); var run = require(id);
        if (run.sourceSha() != null) { if (!run.sourceSha().equals(sha)) throw conflict(); return; }
        if (snapshots.source(id, run.version(), sha) != 1) throw conflict();
    }
    @Transactional
    public SnapshotReview.Snapshot freeze(String id, SnapshotReview.Snapshot snapshot) {
        running(id); var run = require(id);
        if (run.snapshotJson() != null) return snapshot(id);
        if (!snapshot.sourceSha().equals(run.sourceSha())) throw conflict();
        String body = json.writeValueAsString(snapshot);
        for (var file : snapshot.files()) if (snapshots.file(id, file) != 1) throw conflict();
        if (snapshots.snapshot(id, run.version(), body, TemplateGitEvidenceCollector.hash(body)) != 1) throw conflict();
        return snapshot;
    }
    @Transactional
    public void plan(String id, SnapshotReview.Plan plan, String reason) {
        running(id); var run = require(id); String value = json.writeValueAsString(plan);
        if (value.equals(run.planJson())) return;
        if (snapshots.plan(id, run.version(), value) != 1 || snapshots.revision(id, run.planRevision() + 1, value, reason) != 1) throw conflict();
    }
    @Transactional
    public void supplement(String id, SnapshotReview.Input input, String reason) {
        running(id); var run = require(id);
        if (snapshots.plan(id, run.version(), run.planJson()) != 1 || snapshots.revision(id, run.planRevision() + 1, json.writeValueAsString(input), reason) != 1) throw conflict();
    }
    @Transactional
    public void delete(String id) {
        snapshots.deleteReads(id); snapshots.deleteFiles(id); snapshots.deletePlans(id); snapshots.deleteRun(id);
    }
    public SnapshotReview.Plan plan(String id) {
        var run = require(id);
        if (run.planJson() == null) throw conflict();
        return json.readValue(run.planJson(), SnapshotReview.Plan.class);
    }
    private void running(String id) {
        if (!tasks.findTask(id).orElseThrow().state().equals("RUNNING")) throw conflict();
    }
    private static ConflictException conflict() { return new ConflictException("SNAPSHOT_REVIEW_CHANGED", "代码审查身份、版本或状态已经变化"); }
}
