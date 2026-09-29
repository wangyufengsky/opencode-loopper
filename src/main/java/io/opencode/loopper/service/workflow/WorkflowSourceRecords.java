package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.SourceManifest;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-only identity checks shared by file reads and source candidate validation. */
@Service
@Transactional(readOnly=true)
public class WorkflowSourceRecords {
    private final WorkflowSourceMapper mapper;
    private final WorkflowExecutionMapper nodes;
    private final WorkflowEncoding encoding;
    public WorkflowSourceRecords(WorkflowSourceMapper mapper,WorkflowExecutionMapper nodes,WorkflowEncoding encoding){this.mapper=mapper;this.nodes=nodes;this.encoding=encoding;}
    public SourceManifest manifest(String project,String requirement,String producer,WorkflowSourceSnapshot.Reference reference) {
        if(reference==null||reference.version()!=1)throw invalid();
        var row=mapper.find(reference.snapshotId()).orElseThrow(WorkflowSourceRecords::invalid);
        var attempt=nodes.attempt(producer).orElseThrow(WorkflowSourceRecords::invalid);
        if(!row.projectId().equals(project)||!row.requirementId().equals(requirement)||!row.nodeRunId().equals(attempt.nodeRunId())
                ||row.readyAt()==null||!row.manifestSha256().equals(reference.sha256())||!attempt.state().equals("SUCCEEDED")
                ||!attempt.adapterKey().equals(WorkflowSourceSnapshot.ADAPTER)||nodes.stop(producer).isEmpty())throw invalid();
        var delivery=nodes.delivery(producer).orElseThrow(WorkflowSourceRecords::invalid);
        if(!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256()))throw invalid();
        var source=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("source");
        if(source==null||source.kind()!=WorkflowGraph.DataKind.DOCUMENT||!encoding.encode(source.content()).equals(encoding.encode(reference)))throw invalid();
        return decode(row,encoding);
    }
    public static SourceManifest decode(WorkflowSourceMapper.Snapshot row,WorkflowEncoding encoding) {
        if(row.manifestJson()==null)return null;
        if(!WorkflowEncoding.hash(row.manifestJson()).equals(row.manifestSha256()))throw invalid();
        var manifest=encoding.decode(row.manifestJson(),SourceManifest.class);
        if(!SourceTreeCapture.fingerprint(manifest.sourcePath(),manifest.files()).equals(manifest.sha256()))throw invalid();
        return manifest;
    }
    public SourceManifest manifest(String project,String requirement,String producer,WorkflowSourceSnapshot.Reference reference,WorkflowSourceSnapshot.Purpose purpose) {
        var manifest=manifest(project,requirement,producer,reference);
        if(!mapper.find(reference.snapshotId()).orElseThrow(WorkflowSourceRecords::invalid).purpose().equals(purpose.name()))
            throw new BadRequestException("WORKFLOW_SOURCE_PURPOSE_MISMATCH","请选择单元测试用途的冻结源码，以包含构建配置和已有测试资料。");
        return manifest;
    }
    private static ConflictException invalid(){return new ConflictException("WORKFLOW_SOURCE_BINDING_INVALID","源码资料不属于当前节点的已完成交付，请重新检查输入绑定。");}
}
