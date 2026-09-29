package io.opencode.loopper.service.workflow;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.service.GitReviewJobProtocol;
import io.opencode.loopper.template.SnapshotReview;
import io.opencode.loopper.workflow.WorkflowReviewSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Materializes exact version metadata and individual code units without database transactions. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class WorkflowReviewContent {
    private final ImmutableContentStore content;
    private final WorkflowEncoding encoding;
    public WorkflowReviewContent(LoopperProperties properties, WorkflowEncoding encoding) {
        content = new ImmutableContentStore(properties.getDataDir().resolve("workflow-review-content")); this.encoding = encoding;
    }
    public WorkflowReviewSource.Manifest capture(GitReviewJobProtocol.Input input, GitReviewJobProtocol.Frozen frozen, String evidenceHash) {
        var snapshot = frozen.snapshot(); var files = new ArrayList<WorkflowReviewSource.File>();
        var overview = new SnapshotReview.Snapshot(snapshot.sourceSha(), snapshot.baselineSha(), snapshot.targetSha(), snapshot.baselineTree(), snapshot.targetTree(),
                snapshot.capturedAt(), snapshot.startInclusive(), snapshot.endExclusive(), snapshot.selectionBasis(), snapshot.nonMonotonic(), snapshot.noChanges(), snapshot.files(), List.of(), snapshot.scopeIdentity());
        files.add(save(input.nodeId(), "overview.json", overview)); long total = files.getFirst().sizeBytes(); int ordinal = 0;
        for (var unit : snapshot.units()) {
            var file = save(input.nodeId(), String.format(Locale.ROOT, "units/%06d.json", ++ordinal), unit); files.add(file); total += file.sizeBytes();
            if (total > WorkflowReviewSource.MAX_FILE_BYTES) throw new TaskFailure("SNAPSHOT_EVIDENCE_LIMIT", "版本审查资料超过完整保存容量，请缩小项目范围后新增采集节点。");
        }
        int excluded = (int) snapshot.units().stream().filter(u -> u.limitation() != null && u.excerpt().isBlank()).count();
        return new WorkflowReviewSource.Manifest(1, WorkflowReviewSource.TYPE, input.nodeId(), input.source().selection().id(), input.mode(),
                snapshot.sourceSha(), snapshot.baselineSha(), snapshot.targetSha(), frozen.selection().binding().prefix(), input.startDate(), input.endDate(),
                snapshot.capturedAt(), snapshot.noChanges(), snapshot.nonMonotonic(), evidenceHash, snapshot.units().size(), excluded, files);
    }
    public byte[] read(String owner, WorkflowReviewSource.File file) { return content.read(owner, file.sha256(), file.sizeBytes(), WorkflowReviewSource.MAX_FILE_BYTES); }
    private WorkflowReviewSource.File save(String owner, String path, Object value) {
        byte[] bytes = encoding.encode(value).getBytes(StandardCharsets.UTF_8); String sha = ImmutableContentStore.hash(bytes);
        content.write(owner, sha, bytes, WorkflowReviewSource.MAX_FILE_BYTES); return new WorkflowReviewSource.File(path, bytes.length, sha);
    }
}
