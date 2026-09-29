package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A manifest is readable only through the exact successful producer and its accepted DOCUMENT reference. */
@Service
@Transactional(readOnly=true)
public class WorkflowRepositoryRecords {
    private final WorkflowRepositoryMapper mapper;
    private final WorkflowExecutionMapper nodes;
    private final WorkflowEncoding encoding;
    public WorkflowRepositoryRecords(WorkflowRepositoryMapper mapper,WorkflowExecutionMapper nodes,WorkflowEncoding encoding){this.mapper=mapper;this.nodes=nodes;this.encoding=encoding;}
    public WorkflowRepositorySnapshot.Manifest manifest(String project,String requirement,String producer,WorkflowRepositorySnapshot.Reference reference) {
        if(reference==null||reference.version()!=1||!WorkflowRepositorySnapshot.TYPE.equals(reference.type()))throw invalid();
        var row=mapper.find(reference.snapshotId()).orElseThrow(WorkflowRepositoryRecords::invalid);
        var attempt=nodes.attempt(producer).orElseThrow(WorkflowRepositoryRecords::invalid);
        if(!row.projectId().equals(project)||!row.requirementId().equals(requirement)||!row.nodeRunId().equals(attempt.nodeRunId())
                ||row.manifestJson()==null||!row.manifestSha256().equals(reference.sha256())||!attempt.state().equals("SUCCEEDED")
                ||!attempt.adapterKey().equals(WorkflowCommandVerification.ADAPTER)||nodes.stop(producer).isEmpty())throw invalid();
        var delivery=nodes.delivery(producer).orElseThrow(WorkflowRepositoryRecords::invalid);
        if(!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256()))throw invalid();
        var source=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("source");
        if(source==null||source.kind()!=WorkflowGraph.DataKind.DOCUMENT||!encoding.encode(source.content()).equals(encoding.encode(reference)))throw invalid();
        return decode(row,encoding);
    }
    public static WorkflowRepositorySnapshot.Manifest decode(WorkflowRepositoryMapper.Snapshot row,WorkflowEncoding encoding) {
        if(row.manifestJson()==null||!WorkflowEncoding.hash(row.manifestJson()).equals(row.manifestSha256()))throw invalid();
        var manifest=encoding.decode(row.manifestJson(),WorkflowRepositorySnapshot.Manifest.class);
        if(manifest.version()!=1||!WorkflowRepositorySnapshot.TYPE.equals(manifest.type())||!manifest.nodeRunId().equals(row.nodeRunId())||!manifest.branchId().equals(row.branchId()))throw invalid();
        return manifest;
    }
    private static ConflictException invalid(){return new ConflictException("WORKFLOW_REPOSITORY_BINDING_INVALID","分支代码资料不属于当前节点的已完成交付，请检查固定输入。");}
}
