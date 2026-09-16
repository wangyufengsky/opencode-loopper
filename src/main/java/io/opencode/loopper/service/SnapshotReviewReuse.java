package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Conservative reuse: an unchanged full dependency tree, frozen policy/model/scope and identical input are required. */
@Service
public class SnapshotReviewReuse {
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
        String key = fingerprint(snapshot, input, contract);
        if (row.state().equals("VALIDATED") && row.sessionId() != null) {
            var output = json.readValue(row.outputJson(), Analysis.class);
            if (eligible(output)) records.reusable(row.id(), key, TemplateGitEvidenceCollector.hash(row.outputJson()));
        }
        if (!row.state().equals("PREPARED") || row.generation() != 0 || row.sessionId() != null) return row;
        var cached = records.reusableResult(row.taskId(), key).orElse(null);
        if (cached == null || !TemplateGitEvidenceCollector.hash(cached.outputJson()).equals(cached.outputSha256())) return row;
        var original = json.readValue(cached.outputJson(), Analysis.class);
        if (!eligible(original)) return row;
        var source = json.readValue(cached.inputJson(), TemplateBatchExecution.Input.class).snapshot();
        if (source.units().size() != input.units().size()) return row;
        Map<String, String> mapping = new HashMap<>();
        for (int i = 0; i < input.units().size(); i++) mapping.put(source.units().get(i).id(), input.units().get(i).id());
        if (original.coverage().size() != mapping.size() || original.coverage().stream().anyMatch(c -> !mapping.containsKey(c.unitId()))) return row;
        var coverage = original.coverage().stream().map(c -> new Coverage(mapping.get(c.unitId()), c.conclusion(), List.of(), List.of())).toList();
        String value = json.writeValueAsString(new Analysis(coverage, List.of(), List.of(), List.of()));
        var accepted = batches.validated(row, value, true);
        if (!accepted.state().equals("VALIDATED")) return accepted;
        records.reuse(row.id(), cached.id(), cached.taskId(), key, cached.outputSha256());
        return accepted;
    }
    private boolean eligible(Analysis output) {
        return output.findings().isEmpty() && output.supplements().isEmpty() && output.limitations().isEmpty()
                && output.coverage().stream().allMatch(c -> c.limitations().isEmpty() && c.evidence().isEmpty());
    }
    private String fingerprint(Snapshot snapshot, Input input, TemplateTaskContractFactory.Frozen contract) {
        // Whole trees also invalidate negative searches, incoming callers, configuration, additions and deletions.
        var units = input.units().stream().map(u -> Arrays.asList(u.path(), u.beforePath(), u.change(), u.excerpt(),
                u.initialEvidence().stream().map(r -> List.of(r.path(), r.blob(), r.startLine(), r.endLine(), r.quote())).toList())).toList();
        return TemplateGitEvidenceCollector.hash(json.writeValueAsString(Arrays.asList("SNAPSHOT_REUSE_V1", input.policy(),
                snapshot.scopeIdentity(), snapshot.targetTree(), snapshot.baselineTree(), contract.spec().projectId(),
                contract.definition().version(), contract.spec().model(), units)));
    }
}
