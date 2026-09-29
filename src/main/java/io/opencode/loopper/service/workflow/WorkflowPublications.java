package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowPublicationMapper.Row;
import io.opencode.loopper.runtime.GitCommitIntent;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import io.opencode.loopper.workflow.WorkflowPublication.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One explicit, durable confirmation. Only immutable metadata and lifecycle facts are accessed here. */
@Service
@Transactional(readOnly=true)
public class WorkflowPublications {
    private final WorkflowPublicationMapper mapper;
    private final WorkflowPublicationReads reads;
    private final WorkflowFinishMapper finishes;
    private final WorkflowWorkspaceMapper workspaces;
    private final WorkflowCodeStore codes;
    private final WorkflowEncoding encoding;
    private final LifecycleTransitionService lifecycle;
    public WorkflowPublications(WorkflowPublicationMapper mapper,WorkflowPublicationReads reads,WorkflowFinishMapper finishes,
            WorkflowWorkspaceMapper workspaces,WorkflowCodeStore codes,WorkflowEncoding encoding,LifecycleTransitionService lifecycle) {
        this.mapper=mapper;this.reads=reads;this.finishes=finishes;this.workspaces=workspaces;this.codes=codes;this.encoding=encoding;this.lifecycle=lifecycle;
    }
    public View get(String requirement){return mapper.find(requirement).map(this::view).orElse(null);}
    @Transactional
    public View confirm(String requirement,Request request) {
        validate(request);String digest=encoding.digest("WORKFLOW_LOCAL_COMMIT",requirement,request);
        var replay=mapper.request(request.requestKey()).orElse(null);
        if(replay!=null){if(!replay.requirementId().equals(requirement)||!replay.requestSha256().equals(digest))throw changed();return view(replay);}
        if(mapper.find(requirement).isPresent())throw new ConflictException("WORKFLOW_PUBLICATION_EXISTS","这项需求已有确认的成果提交，请查看或恢复原提交。");
        var preview=reads.preview(requirement,request.revision(),request.node(),request.attempt(),request.output());
        if(preview.requirementVersion()!=request.expectedVersion()||!preview.sha256().equals(request.previewSha256()))throw changed();
        if(!preview.requirementState().equals("COMPLETED")||!finishes.remaining(requirement).empty())
            throw new ConflictException("WORKFLOW_PUBLICATION_NOT_READY","需求尚未完成并收束所有执行，请完成后再提交成果。");
        var workspace=workspaces.find(request.attempt()).orElseThrow(WorkflowPublications::changed);
        var snapshot=codes.snapshot(preview.reference().snapshotId());
        if(!preview.workspaceKind().equals("GIT")||!workspace.state().equals("RELEASED")||workspace.objectRepository()!=null
                ||!snapshot.repository().equals(workspace.canonicalRoot())||!snapshot.rootFingerprint().equals(workspace.rootFingerprint())
                ||!snapshot.baseTree().equals(workspace.baseTree())||!snapshot.resultTree().equals(workspace.checkpointTree()))throw changed();
        String id=UUID.randomUUID().toString(),now=Instant.now().toString();
        var intent=new Intent(requirement,workspace.projectId(),preview,snapshot.repository(),snapshot.rootFingerprint(),workspace.projectDirectory(),
                snapshot.projectPrefix(),"loopper/results/"+id,new GitCommitIntent(snapshot.resultTree(),workspace.sourceCommit(),request.message().strip()+"\n","Loopper","loopper@localhost",now));
        String body=encoding.encode(intent);
        var row=new Row(id,requirement,workspace.projectId(),request.requestKey(),digest,body,WorkflowEncoding.hash(body),"CONFIRMED",0,null,now,now);
        lifecycle.create(subject(row),row.state(),Map.of("source","USER","planRevision",preview.planRevision()),()->mapper.insert(row),WorkflowPublications::changed);
        return view(row);
    }
    public record Work(Row row,Intent intent,WorkflowCodeSnapshot manifest) { }
    public Work work(String requirement) {
        var row=mapper.find(requirement).orElseThrow(WorkflowPublications::changed);var intent=intent(row);
        var source=intent.preview().source();var manifest=codes.readOutput(row.projectId(),requirement,source.attemptId(),intent.preview().reference());
        if(!manifest.resultTree().equals(intent.commit().tree())||!manifest.baseTree().equals(intent.preview().baseTree()))throw changed();
        return new Work(row,intent,manifest);
    }
    @Transactional
    public View retry(String requirement,long version) {
        var row=mapper.find(requirement).orElseThrow(WorkflowPublications::changed);
        if(row.state().equals("CONFIRMED")||row.state().equals("COMMITTED"))return view(row);
        if(row.version()!=version)throw changed();
        transition(row,"CONFIRMED",LifecycleEvent.RETRY,null);return get(requirement);
    }
    @Transactional
    public void committed(Row expected,String commit) {
        if(commit==null||!commit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"))throw changed();
        var row=mapper.find(expected.requirementId()).orElseThrow(WorkflowPublications::changed);
        if(row.state().equals("COMMITTED")){if(!commit.equals(row.commitSha()))throw changed();return;}
        if(!row.equals(expected)||!row.state().equals("CONFIRMED"))throw changed();
        transition(row,"COMMITTED",LifecycleEvent.COMPLETE,commit);
    }
    @Transactional
    public void blocked(Row expected,String reason) {
        var row=mapper.find(expected.requirementId()).orElseThrow(WorkflowPublications::changed);
        if(row.equals(expected)&&row.state().equals("CONFIRMED"))transition(row,"BLOCKED",LifecycleEvent.REQUIRE_INPUT,null,WorkflowFailures.safe(reason));
    }
    private void transition(Row row,String next,LifecycleEvent event,String commit) {
        transition(row,next,event,commit,"WORKFLOW_LOCAL_COMMIT");
    }
    private void transition(Row row,String next,LifecycleEvent event,String commit,String reason) {
        lifecycle.transition(subject(row),row.state(),next,event,reason,Map.of(),
                ()->mapper.transition(row.id(),row.version(),row.state(),next,commit,Instant.now().toString()),WorkflowPublications::changed);
    }
    private Intent intent(Row row) {
        if(!WorkflowEncoding.hash(row.intentJson()).equals(row.intentSha256()))throw changed();
        var intent=encoding.decode(row.intentJson(),Intent.class);
        if(!intent.requirementId().equals(row.requirementId())||!intent.projectId().equals(row.projectId()))throw changed();return intent;
    }
    private View view(Row row) {var intent=intent(row);var source=intent.preview().source();return new View(row.requirementId(),row.state(),row.version(),source.nodeTitle(),source.outputTitle(),source.attemptState(),intent.branch(),intent.commit().message().strip(),row.commitSha(),row.createdAt(),row.state().equals("BLOCKED")?mapper.failure(row.id()).orElse(null):null);}
    private static void validate(Request request) {
        if(request==null||request.requestKey()==null||!request.requestKey().matches("[A-Za-z0-9_-]{16,100}")||request.previewSha256()==null
                ||!request.previewSha256().matches("[0-9a-f]{64}")||request.message()==null||request.message().isBlank()
                ||request.message().length()>200||request.message().codePoints().anyMatch(c->Character.isISOControl(c)||c==0x2028||c==0x2029))
            throw new BadRequestException("WORKFLOW_PUBLICATION_INVALID","请填写 1–200 字的单行提交说明，并重新核对所选成果。");
    }
    private static LifecycleTransitionService.Subject subject(Row row){return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_PUBLICATION,row.id(),LifecycleScopeType.PROJECT,row.projectId());}
    static ConflictException changed(){return new ConflictException("WORKFLOW_PUBLICATION_CHANGED","成果、项目或提交版本不一致，请刷新并检查原提交记录。");}
}
