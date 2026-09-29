package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowSnapshotPartialMapper;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowSnapshotPartialReports {
    private final WorkflowSnapshotPartialReads reads;
    private final WorkflowSnapshotEvidence evidence;
    private final WorkflowEncoding encoding;
    private final SnapshotReviewAuthors authors;
    private final GitReviewJobs jobs;
    public WorkflowSnapshotPartialReports(WorkflowSnapshotPartialReads reads,WorkflowSnapshotEvidence evidence,WorkflowEncoding encoding,SnapshotReviewAuthors authors,GitReviewJobs jobs){this.reads=reads;this.evidence=evidence;this.encoding=encoding;this.authors=authors;this.jobs=jobs;}
    public WorkflowSnapshotPartialReport read(String requirement,String key,String attempt) {
        var captured=reads.capture(requirement,key,attempt);var snapshot=evidence.read(captured.manifest());
        var groups=SnapshotReviewLightweightPolicy.plan(snapshot.units()).groups();
        var batches=new ArrayList<SnapshotReviewPartialCompiler.Batch>();var reviews=new ArrayList<SnapshotReviewPartialCompiler.Judgment>();var reused=new ArrayList<WorkflowSnapshotWork.Reuse>();
        for(var row:captured.results()) {
            var value=body(row);
            if(WorkflowSnapshotWork.ANALYZE.equals(row.module())) {
                var result=encoding.decode(encoding.encode(value),WorkflowSnapshotWork.Analysis.class);int i=result.batchOrdinal();
                if(result.version()!=1||!WorkflowSnapshotWork.ANALYSIS_TYPE.equals(result.type())||!captured.reference().equals(result.source())
                    ||result.batchCount()!=groups.size()||i<0||i>=groups.size()
                    ||!new HashSet<>(result.claims().coverage().stream().map(SnapshotReview.Coverage::unitId).toList()).equals(new HashSet<>(groups.get(i).unitIds())))throw WorkflowSnapshotPartialReads.invalid();
                if(result.reuse()!=null)reused.add(result.reuse());
                batches.add(new SnapshotReviewPartialCompiler.Batch(row.attemptId(),row.title(),result.claims()));
            }else {
                var result=encoding.decode(encoding.encode(value),WorkflowSnapshotWork.Review.class);
                if(result.version()!=1||!WorkflowSnapshotWork.REVIEW_TYPE.equals(result.type())||!captured.reference().equals(result.source()))throw WorkflowSnapshotPartialReads.invalid();
                reviews.add(new SnapshotReviewPartialCompiler.Judgment(row.attemptId(),row.title(),result.analysisAttempt(),result.claims()));
            }
        }
        var ids=new HashSet<String>();batches.forEach(b->ids.add(b.id()));var relevant=reviews.stream().filter(r->ids.contains(r.analysisId())).toList();
        String note="按读取时的计划版本 "+captured.revision()+" 汇总所选固定资料的当前有效节点；已移出计划的结果保留在历史尝试中。刷新可查看后续变化，查看报告不推进任务或完整报告。";
        if(relevant.size()!=reviews.size())note+="\n\n有 "+(reviews.size()-relevant.size())+" 个复核的原分析已不在当前有效结果中，未计入本报告。";
        note+=WorkflowSnapshotReuseNotes.render(reused);
        var rendered=SnapshotReviewPartialCompiler.compile(snapshot,captured.capturedAt(),WorkflowState.valueOf(captured.state()).description(),note,batches,relevant,
            refs->authors.renderFrozen(captured.reference().snapshotId(),jobs.repository(captured.reference().snapshotId()),captured.manifest().projectPrefix(),snapshot,refs));
        if(rendered.content().getBytes(StandardCharsets.UTF_8).length>WorkflowSnapshotPartialReads.MAX_BYTES)throw WorkflowSnapshotPartialReads.tooLarge();
        return new WorkflowSnapshotPartialReport(rendered.content(),WorkflowEncoding.hash(rendered.content()),captured.capturedAt(),captured.revision(),rendered.analyzedUnits(),rendered.pendingUnits(),rendered.excludedUnits());
    }
    private tools.jackson.databind.JsonNode body(WorkflowSnapshotPartialMapper.Result row) {
        if(!WorkflowEncoding.hash(row.contentJson()).equals(row.sha256()))throw WorkflowSnapshotPartialReads.invalid();
        var value=encoding.decode(row.contentJson(),WorkflowDelivery.class).outputs().get("analysis");
        if(value==null||value.kind()!=WorkflowGraph.DataKind.JSON)throw WorkflowSnapshotPartialReads.invalid();return value.content();
    }
}
