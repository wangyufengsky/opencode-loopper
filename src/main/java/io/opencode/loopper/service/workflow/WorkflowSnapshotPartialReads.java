package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One short SQLite snapshot before file reads, Git attribution and rendering. */
@Service
@Transactional(readOnly=true)
public class WorkflowSnapshotPartialReads {
    public static final int MAX_BYTES=32_000_000;
    private final WorkflowPlanMapper plans;
    private final WorkflowRunReadMapper reads;
    private final WorkflowExecutionMapper runs;
    private final WorkflowReviewRecords sources;
    private final WorkflowSnapshotPartialMapper results;
    private final WorkflowEncoding encoding;
    public WorkflowSnapshotPartialReads(WorkflowPlanMapper plans,WorkflowRunReadMapper reads,WorkflowExecutionMapper runs,WorkflowReviewRecords sources,WorkflowSnapshotPartialMapper results,WorkflowEncoding encoding){this.plans=plans;this.reads=reads;this.runs=runs;this.sources=sources;this.results=results;this.encoding=encoding;}
    public record Captured(int revision,String state,String capturedAt,WorkflowReviewSource.Reference reference,WorkflowReviewSource.Manifest manifest,List<WorkflowSnapshotPartialMapper.Result> results){ }
    public Captured capture(String id,String key,String attempt) {
        var owner=plans.find(id).orElseThrow(()->new NotFoundException("需求任务不存在"));
        var node=reads.definition(id,key,attempt).orElseThrow(()->new NotFoundException("该需求节点中不存在此执行尝试"));
        var definition=encoding.decode(node.definitionJson(),WorkflowGraph.Node.class);
        if(!WorkflowReviewSource.MODULE.equals(definition.moduleId())||definition.moduleVersion()!=1)throw invalid();
        var delivery=runs.delivery(attempt).orElseThrow(WorkflowSnapshotPartialReads::invalid);
        if(!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256()))throw invalid();
        var value=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("source");
        if(value==null||value.kind()!=WorkflowGraph.DataKind.DOCUMENT)throw invalid();
        var reference=encoding.decode(encoding.encode(value.content()),WorkflowReviewSource.Reference.class);
        var manifest=sources.manifest(owner.projectId(),id,attempt,reference);
        if(results.bytes(id,owner.headRevision(),attempt,reference.sha256())>MAX_BYTES)throw tooLarge();
        var rows=results.results(id,owner.headRevision(),attempt,reference.sha256());
        if(rows.size()>256)throw tooLarge();
        return new Captured(owner.headRevision(),owner.state(),Instant.now().toString(),reference,manifest,List.copyOf(rows));
    }
    static ConflictException tooLarge(){return new ConflictException("SNAPSHOT_REPORT_TOO_LARGE","阶段报告超过在线读取容量，请分别查看节点结果。");}
    static ConflictException invalid(){return new ConflictException("WORKFLOW_SNAPSHOT_REPORT_INVALID","固定版本资料尚未完成，或保存结果与当前来源不一致，请检查节点交付。");}
}
