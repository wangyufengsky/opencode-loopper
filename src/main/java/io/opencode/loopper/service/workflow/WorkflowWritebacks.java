package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.WorkflowWritebackMapper;
import io.opencode.loopper.persistence.WorkflowWritebackMapper.Row;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.WorkflowWriteback.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable user consent, short metadata transactions and the final receipt/FIFO handoff. No file I/O. */
@Service
@Transactional(readOnly=true)
public class WorkflowWritebacks {
    private final WorkflowWritebackMapper mapper;
    private final WorkflowWritebackReads reads;
    private final WorkflowWritebackLeases leases;
    private final WorkflowEncoding encoding;
    private final LifecycleTransitionService lifecycle;
    public WorkflowWritebacks(WorkflowWritebackMapper mapper,WorkflowWritebackReads reads,WorkflowWritebackLeases leases,
            WorkflowEncoding encoding,LifecycleTransitionService lifecycle) {
        this.mapper=mapper;this.reads=reads;this.leases=leases;this.encoding=encoding;this.lifecycle=lifecycle;
    }
    public View get(String requirement){return mapper.find(requirement).map(this::view).orElse(null);}
    public View replay(String requirement,Request request) {
        validate(request);var row=mapper.request(request.requestKey()).orElse(null);if(row==null)return null;
        if(!row.requirementId().equals(requirement)||!row.requestSha256().equals(digest(requirement,request)))throw changed();return view(row);
    }
    @Transactional
    public View confirm(String requirement,Request request,WorkflowWritebackPreviews.Context context) {
        var replay=replay(requirement,request);if(replay!=null)return replay;
        if(mapper.find(requirement).isPresent())throw new ConflictException("WORKFLOW_WRITEBACK_EXISTS","这项需求已有确认的回填，请查看或恢复原记录。");
        var source=reads.get(requirement,request.selection());var preview=context.preview();
        if(!source.equals(context.source())||preview.requirementVersion()!=request.expectedVersion()||!preview.sha256().equals(request.previewSha256())
                ||context.plan().after()==null||preview.conflictCount()!=0)throw changed();
        var intent=new Intent(request.selection(),preview,source.preview().source().nodeTitle(),source.preview().source().outputTitle(),source.preview().source().attemptState(),source.workspace().objectRepository(),context.plan().before(),context.plan().after(),source.result());
        requireHashes(intent);String now=Instant.now().toString(),body=encoding.encode(intent);
        var row=new Row(UUID.randomUUID().toString(),requirement,source.workspace().projectId(),request.requestKey(),digest(requirement,request),
                body,WorkflowEncoding.hash(body),"CONFIRMED",0,null,now,now);
        lifecycle.create(subject(row),row.state(),Map.of("source","USER","planRevision",preview.revision()),()->mapper.insert(row),WorkflowWritebacks::changed);
        leases.admit(row,intent);return view(row);
    }
    public record Work(Row row,Intent intent,String queueState) { }
    public Work work(String requirement) {
        var row=mapper.find(requirement).orElseThrow(WorkflowWritebacks::changed);
        return new Work(row,intent(row),leases.queue(row.id()).state());
    }
    public long requireWriter(Work work) {
        var row=mapper.find(work.row().requirementId()).orElseThrow(WorkflowWritebacks::changed);
        if(!row.equals(work.row())||!Set.of("CONFIRMED","APPLYING").contains(row.state()))throw changed();
        return leases.requireHolder(row.id(),work.intent()).version();
    }
    @Transactional
    public Work prepared(Work expected) {
        requireWriter(expected);var row=expected.row();if(!row.state().equals("CONFIRMED")||row.preparedAt()!=null)throw changed();
        transition(row,"APPLYING",LifecycleEvent.START,Instant.now().toString(),"WORKFLOW_WRITEBACK_PREPARED");return work(row.requirementId());
    }
    @Transactional
    public void applied(Work expected,long leaseVersion,String observedTargetSha256) {
        var row=mapper.find(expected.row().requirementId()).orElseThrow(WorkflowWritebacks::changed);
        if(row.state().equals("APPLIED")) {
            if(!mapper.receipt(row.id()).orElseThrow(WorkflowWritebacks::changed).targetSha256().equals(observedTargetSha256))throw changed();return;
        }
        if(requireWriter(expected)!=leaseVersion||!row.state().equals("APPLYING")||row.preparedAt()==null
                ||!observedTargetSha256.equals(expected.intent().preview().targetSha256()))throw changed();
        if(mapper.insertReceipt(new WorkflowWritebackMapper.Receipt(row.id(),observedTargetSha256,leaseVersion,Instant.now().toString()))!=1)throw changed();
        transition(row,"APPLIED",LifecycleEvent.COMPLETE,row.preparedAt(),"WORKFLOW_WRITEBACK_VERIFIED");
        leases.release(row.id(),expected.intent(),leaseVersion);
    }
    @Transactional
    public void blocked(Work expected,String reason) {
        var row=mapper.find(expected.row().requirementId()).orElseThrow(WorkflowWritebacks::changed);
        if(row.equals(expected.row())&&Set.of("CONFIRMED","APPLYING").contains(row.state()))
            transition(row,"BLOCKED",LifecycleEvent.REQUIRE_INPUT,row.preparedAt(),WorkflowFailures.safe(reason));
    }
    @Transactional
    public View retry(String requirement,long version) {
        var row=mapper.find(requirement).orElseThrow(WorkflowWritebacks::changed);
        if(!row.state().equals("BLOCKED"))return view(row);
        if(row.version()!=version)throw changed();leases.requireHolder(row.id(),intent(row));
        transition(row,row.preparedAt()==null?"CONFIRMED":"APPLYING",row.preparedAt()==null?LifecycleEvent.RETRY:LifecycleEvent.RESUME,row.preparedAt(),"USER_RETRY");
        return get(requirement);
    }
    private void transition(Row row,String next,LifecycleEvent event,String prepared,String reason) {
        lifecycle.transition(subject(row),row.state(),next,event,reason,Map.of(),
                ()->mapper.transition(row.id(),row.version(),row.state(),next,prepared,Instant.now().toString()),WorkflowWritebacks::changed);
    }
    private Intent intent(Row row) {
        if(!WorkflowEncoding.hash(row.intentJson()).equals(row.intentSha256()))throw changed();
        var intent=encoding.decode(row.intentJson(),Intent.class);requireHashes(intent);
        if(!intent.preview().requirementId().equals(row.requirementId()))throw changed();return intent;
    }
    private void requireHashes(Intent intent) {
        if(!WorkflowEncoding.hash(encoding.encode(intent.before())).equals(intent.preview().currentSha256())
                ||!WorkflowEncoding.hash(encoding.encode(intent.after())).equals(intent.preview().targetSha256()))throw changed();
    }
    private View view(Row row) {
        var intent=intent(row);var queue=leases.queue(row.id());
        return new View(row.requirementId(),row.state(),row.version(),queue.state(),leases.position(queue),intent.preview(),intent.nodeTitle(),intent.outputTitle(),intent.attemptState(),row.createdAt(),
                mapper.receipt(row.id()).map(WorkflowWritebackMapper.Receipt::confirmedAt).orElse(null),row.state().equals("BLOCKED")?mapper.failure(row.id()).orElse(null):null);
    }
    private String digest(String requirement,Request request){return encoding.digest("WORKFLOW_DIRECTORY_WRITEBACK",requirement,request);}
    private static void validate(Request request) {
        if(request==null||request.requestKey()==null||!request.requestKey().matches("[A-Za-z0-9_-]{16,100}")||request.expectedVersion()<0
                ||request.selection()==null||request.previewSha256()==null||!request.previewSha256().matches("[0-9a-f]{64}"))
            throw new BadRequestException("WORKFLOW_WRITEBACK_INVALID","请重新检查所选成果和目录后确认回填。");
    }
    private static LifecycleTransitionService.Subject subject(Row row){return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_WRITEBACK,row.id(),LifecycleScopeType.PROJECT,row.projectId());}
    static ConflictException changed(){return new ConflictException("WORKFLOW_WRITEBACK_CHANGED","回填来源、目录或记录已变化，请刷新并检查原回填记录。");}
}
