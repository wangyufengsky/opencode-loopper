package io.opencode.loopper.service.workflow;

import io.opencode.loopper.template.SnapshotReview;
import io.opencode.loopper.workflow.WorkflowReviewSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Rebuild exact professional inputs from accepted content; never resolve a moving branch. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowSnapshotEvidence {
    private final WorkflowReviewContent content;
    private final WorkflowEncoding encoding;
    public WorkflowSnapshotEvidence(WorkflowReviewContent content,WorkflowEncoding encoding){this.content=content;this.encoding=encoding;}
    public SnapshotReview.Snapshot overview(WorkflowReviewSource.Manifest manifest){
        if(manifest.files().isEmpty()||!manifest.files().getFirst().path().equals("overview.json"))throw WorkflowCommands.conflict();
        var value=encoding.decode(new String(content.read(manifest.nodeRunId(),manifest.files().getFirst()),StandardCharsets.UTF_8),SnapshotReview.Snapshot.class);
        if(!value.units().isEmpty()||!Objects.equals(value.sourceSha(),manifest.sourceSha())||!Objects.equals(value.targetSha(),manifest.targetSha())
            ||!Objects.equals(value.baselineSha(),manifest.baselineSha())||!Objects.equals(value.capturedAt(),manifest.capturedAt())
            ||value.noChanges()!=manifest.noChanges()||value.nonMonotonic()!=manifest.nonMonotonic())throw WorkflowCommands.conflict();
        return value;
    }
    public SnapshotReview.Snapshot read(WorkflowReviewSource.Manifest manifest){
        var overview=overview(manifest);var units=new ArrayList<SnapshotReview.Unit>();long bytes=manifest.files().getFirst().sizeBytes();
        for(var file:manifest.files().subList(1,manifest.files().size())){
            bytes+=file.sizeBytes();if(bytes>WorkflowReviewSource.MAX_FILE_BYTES||!file.path().equals(String.format(Locale.ROOT,"units/%06d.json",units.size()+1)))throw WorkflowCommands.conflict();
            units.add(encoding.decode(new String(content.read(manifest.nodeRunId(),file),StandardCharsets.UTF_8),SnapshotReview.Unit.class));
        }
        if(units.size()!=manifest.unitCount()||units.stream().map(SnapshotReview.Unit::id).distinct().count()!=units.size()
            ||units.stream().filter(u->u.limitation()!=null&&u.excerpt().isBlank()).count()!=manifest.excludedCount())throw WorkflowCommands.conflict();
        return new SnapshotReview.Snapshot(overview.sourceSha(),overview.baselineSha(),overview.targetSha(),overview.baselineTree(),overview.targetTree(),overview.capturedAt(),
                overview.startInclusive(),overview.endExclusive(),overview.selectionBasis(),overview.nonMonotonic(),overview.noChanges(),overview.files(),List.copyOf(units),overview.scopeIdentity());
    }
}
