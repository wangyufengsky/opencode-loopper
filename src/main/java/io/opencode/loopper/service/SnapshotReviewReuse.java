package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.SnapshotReview.*;
import io.opencode.loopper.template.SnapshotReviewReusePolicy;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Conservative reuse: an unchanged full dependency tree, frozen policy/model/scope and identical input are required. */
@Service
public class SnapshotReviewReuse {
    @org.springframework.beans.factory.annotation.Autowired private TemplateReuseContext reuseContext;
    private final SnapshotReviewMapper records;
    private final TemplateTaskMapper tasks;
    private final TemplateBatchStore batches;
    private final ObjectMapper json;
    public SnapshotReviewReuse(SnapshotReviewMapper records, TemplateTaskMapper tasks,
                               TemplateBatchStore batches, ObjectMapper json) {
        this.records = records; this.tasks = tasks; this.batches = batches; this.json = json;
    }
    @Transactional
    public TemplateTaskBatchRow consider(TemplateTaskBatchRow row, Input input, Snapshot snapshot, TemplateTaskContractFactory.Frozen contract) {
        if (!input.compact() || !row.purpose().equals("SNAPSHOT_ANALYSIS")) return row;
        var run = tasks.findRun(row.taskId()).orElseThrow();
        if (run.bypassCache() != 0 || run.repairRound() != 0 || snapshot.scopeIdentity() == null
                || input.units().stream().anyMatch(u -> u.limitation() != null || u.initialEvidence().isEmpty())) return row;
        String context = reuseContext.key(row.taskId(), io.opencode.loopper.runtime.OpenCodeClient.SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS);
        if (context == null) return row;
        String key = fingerprint(snapshot, input, contract, context);
        if (row.state().equals("VALIDATED") && row.sessionId() != null) {
            var output = json.readValue(row.outputJson(), Analysis.class);
            if (SnapshotReviewReusePolicy.eligible(output)) records.reusable(row.id(), key, TemplateGitEvidenceCollector.hash(row.outputJson()));
        }
        if (!row.state().equals("PREPARED") || row.generation() != 0 || row.sessionId() != null) return row;
        var cached = records.reusableResult(row.taskId(), key).orElse(null);
        if (cached == null || !TemplateGitEvidenceCollector.hash(cached.outputJson()).equals(cached.outputSha256())) return row;
        var original = json.readValue(cached.outputJson(), Analysis.class);
        var source = json.readValue(cached.inputJson(), TemplateBatchExecution.Input.class).snapshot();
        var mapped = SnapshotReviewReusePolicy.remap(original, source, input);
        if (mapped.isEmpty()) return row;
        String value = json.writeValueAsString(mapped.get());
        var accepted = batches.validated(row, value, true);
        if (!accepted.state().equals("VALIDATED")) return accepted;
        records.reuse(row.id(), cached.id(), cached.taskId(), key, cached.outputSha256());
        return accepted;
    }
    private String fingerprint(Snapshot snapshot, Input input, TemplateTaskContractFactory.Frozen contract, String context) {
        // Whole trees also invalidate negative searches, incoming callers, configuration, additions and deletions.
        var units = SnapshotReviewReusePolicy.materials(input);
        return TemplateGitEvidenceCollector.hash(json.writeValueAsString(Arrays.asList("SNAPSHOT_REUSE_V2", context, input.policy(),
                snapshot.scopeIdentity(), snapshot.targetTree(), snapshot.baselineTree(), contract.spec().projectId(),
                contract.definition().version(), contract.spec().model(), units)));
    }
}
