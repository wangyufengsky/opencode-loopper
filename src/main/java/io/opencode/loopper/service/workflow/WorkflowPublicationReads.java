package io.opencode.loopper.service.workflow;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Planning metadata only: no Git, model, files, lease, or publication state is mutated here. */
@Service
@Transactional(readOnly=true)
public class WorkflowPublicationReads {
    private final WorkflowPublicationReadMapper mapper;
    private final WorkflowPlans plans;
    private final WorkflowNodeRuns nodes;
    private final WorkflowCodeStore codes;
    private final WorkflowCodeMapper snapshots;
    private final WorkflowWorkspaceMapper workspaces;
    private final WorkflowEncoding encoding;
    public WorkflowPublicationReads(WorkflowPublicationReadMapper mapper,WorkflowPlans plans,WorkflowNodeRuns nodes,WorkflowCodeStore codes,WorkflowCodeMapper snapshots,WorkflowWorkspaceMapper workspaces,WorkflowEncoding encoding){this.mapper=mapper;this.plans=plans;this.nodes=nodes;this.codes=codes;this.snapshots=snapshots;this.workspaces=workspaces;this.encoding=encoding;}
    public CursorPage<WorkflowPublicationPreview.Source> sources(String id,int revision,String cursor,Integer requested){
        require(id,revision);int limit=PageCursor.limit(requested);var after=PageCursor.decode(cursor);String prefix=id+":"+revision+":";
        if(after!=null&&!after.value().startsWith(prefix))throw stale();
        var found=mapper.page(id,revision,after==null?null:after.value().substring(prefix.length()),after==null?null:after.id(),limit+1);
        var items=found.stream().limit(limit).toList();var last=items.isEmpty()?null:items.getLast();
        return new CursorPage<>(items,found.size()>limit?new PageCursor(prefix+last.createdAt(),last.attemptId()+":"+last.outputName()).encode():null);
    }
    public WorkflowPublicationPreview preview(String id,int revision,String node,String attempt,String output){
        var owner=require(id,revision);var source=mapper.find(id,revision,node,attempt,output).orElseThrow(()->new NotFoundException("当前计划中没有这份已停止的代码成果，请刷新后重新选择。"));
        var delivery=nodes.delivery(attempt);if(!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256()))throw stale();
        var value=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get(output);
        if(value==null||value.kind()!=WorkflowGraph.DataKind.CODE)throw stale();
        var reference=encoding.decode(encoding.encode(value.content()),WorkflowCodeSnapshot.Reference.class);
        var manifest=codes.readOutput(owner.projectId(),id,attempt,reference);
        var snapshot=snapshots.snapshot(reference.snapshotId()).orElseThrow(WorkflowPublicationReads::stale);var workspace=workspaces.find(attempt).orElse(null);
        int added=0,modified=0,deleted=0;for(var change:manifest.changes())switch(change.kind()){case "ADD"->added++;case "MODIFY"->modified++;case "DELETE"->deleted++;default->throw stale();}
        if(source.changedFiles()!=manifest.changes().size()||source.totalFiles()!=manifest.files().size())throw stale();
        String kind=snapshot.objectRepository()==null?"GIT":"DIRECT",branch=workspace==null?null:workspace.sourceBranch();
        long bytes=manifest.files().stream().mapToLong(WorkflowCodeSnapshot.File::sizeBytes).sum();
        String sha=WorkflowEncoding.hash(encoding.encode(Arrays.asList(id,owner.version(),revision,source,reference,delivery.sha256(),kind,branch,manifest.baseTree(),manifest.resultTree())));
        return new WorkflowPublicationPreview(id,owner.version(),revision,owner.state(),source,kind,branch,reference,delivery.sha256(),manifest.baseTree(),manifest.resultTree(),added,modified,deleted,bytes,sha);
    }
    private WorkflowRows.Requirement require(String id,int revision){var owner=plans.require(id);if(revision<1||owner.headRevision()!=revision)throw stale();return owner;}
    private static ConflictException stale(){return new ConflictException("WORKFLOW_PUBLICATION_PREVIEW_CHANGED","计划或代码成果已变化，请刷新并重新选择成果。");}
}
