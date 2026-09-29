package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A manifest is readable only through the exact successful producer and its accepted DOCUMENT reference. */
@Service
@Transactional(readOnly=true)
public class WorkflowReviewRecords {
    private final WorkflowReviewMapper mapper;
    private final WorkflowExecutionMapper nodes;
    private final WorkflowEncoding encoding;
    public WorkflowReviewRecords(WorkflowReviewMapper mapper,WorkflowExecutionMapper nodes,WorkflowEncoding encoding){this.mapper=mapper;this.nodes=nodes;this.encoding=encoding;}
    public WorkflowReviewSource.Manifest manifest(String project,String requirement,String producer,WorkflowReviewSource.Reference reference) {
        if(reference==null||reference.version()!=1||!WorkflowReviewSource.TYPE.equals(reference.type()))throw invalid();
        var row=mapper.find(reference.snapshotId()).orElseThrow(WorkflowReviewRecords::invalid);
        var attempt=nodes.attempt(producer).orElseThrow(WorkflowReviewRecords::invalid);
        if(!row.projectId().equals(project)||!row.requirementId().equals(requirement)||!row.nodeRunId().equals(attempt.nodeRunId())
                ||row.manifestJson()==null||!row.manifestSha256().equals(reference.sha256())||!attempt.state().equals("SUCCEEDED")
                ||!attempt.adapterKey().equals(WorkflowCommandVerification.ADAPTER)||nodes.stop(producer).isEmpty())throw invalid();
        var delivery=nodes.delivery(producer).orElseThrow(WorkflowReviewRecords::invalid);
        if(!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256()))throw invalid();
        var source=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("source");
        if(source==null||source.kind()!=WorkflowGraph.DataKind.DOCUMENT||!encoding.encode(source.content()).equals(encoding.encode(reference)))throw invalid();
        return decode(row,encoding);
    }
    public static WorkflowReviewSource.Manifest decode(WorkflowReviewMapper.Snapshot row,WorkflowEncoding encoding) {
        if(row.manifestJson()==null||!WorkflowEncoding.hash(row.manifestJson()).equals(row.manifestSha256()))throw invalid();
        var manifest=encoding.decode(row.manifestJson(),WorkflowReviewSource.Manifest.class);
        if(manifest.version()!=1||!WorkflowReviewSource.TYPE.equals(manifest.type())||!manifest.nodeRunId().equals(row.nodeRunId())||!manifest.branchId().equals(row.branchId()))throw invalid();
        return manifest;
    }
    private static ConflictException invalid(){return new ConflictException("WORKFLOW_REVIEW_BINDING_INVALID","版本审查资料不属于当前节点的已完成交付，请检查固定输入。");}
}
