package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowPushMapper.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.workflow.WorkflowPush;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable destination consent and per-launch evidence. No credentials, Git or process I/O. */
@Service
@Transactional(readOnly=true)
public class WorkflowPushes {
    private final WorkflowPushMapper mapper;
    private final WorkflowPublicationMapper publications;
    private final WorkflowEncoding encoding;
    private final LifecycleTransitionService lifecycle;
    public WorkflowPushes(WorkflowPushMapper mapper,WorkflowPublicationMapper publications,WorkflowEncoding encoding,LifecycleTransitionService lifecycle){this.mapper=mapper;this.publications=publications;this.encoding=encoding;this.lifecycle=lifecycle;}
    public record Work(Push push,Attempt attempt,GitPushProtocol.Input input) { }
    public WorkflowPush.View get(String requirement){return mapper.find(requirement).map(this::view).orElse(null);}
    public WorkflowPush.View replay(String requirement,WorkflowPush.Request request) {
        validate(request);var row=mapper.request(request.requestKey()).orElse(null);if(row==null)return null;
        if(!row.requirementId().equals(requirement)||!row.requestSha256().equals(digest(requirement,request)))throw WorkflowPublications.changed();return view(row);
    }
    @Transactional
    public WorkflowPush.View confirm(String requirement,WorkflowPush.Request request,WorkflowPushPreviews.Context expected) {
        var replay=replay(requirement,request);if(replay!=null)return replay;
        var publication=publications.find(requirement).orElseThrow(WorkflowPublications::changed);
        if(!publication.equals(expected.publication().row())||!publication.state().equals("COMMITTED")||publication.version()!=request.expectedVersion()
                ||!expected.preview().sha256().equals(request.previewSha256())||!request.remote().equals(expected.input().remote())||mapper.find(requirement).isPresent())throw WorkflowPublications.changed();
        String id=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString(),now=Instant.now().toString();var input=identity(expected.input(),id);String body=encoding.encode(input);
        var row=new Push(id,requirement,publication.projectId(),publication.id(),request.requestKey(),digest(requirement,request),body,WorkflowEncoding.hash(body),"PREPARING",0,attempt,null,now,now);
        lifecycle.create(subject(row),row.state(),Map.of("source","USER"),()->mapper.insert(row),WorkflowPublications::changed);
        if(mapper.insertAttempt(new Attempt(attempt,id,1,null,null,null,null,0,now))!=1)throw WorkflowPublications.changed();return view(row);
    }
    public Work work(String requirement) {
        var push=mapper.find(requirement).orElseThrow(WorkflowPublications::changed);var attempt=mapper.attempt(push.activeAttemptId()).orElseThrow(WorkflowPublications::changed);
        if(!attempt.pushId().equals(push.id())||!WorkflowEncoding.hash(push.inputJson()).equals(push.inputSha256()))throw WorkflowPublications.changed();
        return new Work(push,attempt,identity(encoding.decode(push.inputJson(),GitPushProtocol.Input.class),attempt.id()));
    }
    @Transactional
    public void prepared(Work expected,DurableCommandProtocol.Request request,String sha) {
        var work=active(expected);if(!work.push().state().equals("PREPARING")||!request.id().equals(work.attempt().id()))throw WorkflowPublications.changed();
        try{if(!DurableCommandProtocol.hash(DurableCommandProtocol.request(request)).equals(sha))throw WorkflowPublications.changed();}catch(java.io.IOException failure){throw WorkflowPublications.changed();}
        if(mapper.prepare(work.attempt().id(),work.attempt().version(),encoding.encode(request),sha)!=1)throw WorkflowPublications.changed();transition(work.push(),"RUNNING",LifecycleEvent.START,work.attempt().id(),null);
    }
    @Transactional
    public void registered(Work expected,DurableCommandProtocol.Registration registration) {
        var work=active(expected);if(!work.push().state().equals("RUNNING")||!registration.requestSha256().equals(work.attempt().requestSha256()))throw WorkflowPublications.changed();
        if(mapper.register(work.attempt().id(),work.attempt().version(),encoding.encode(registration))!=1)throw WorkflowPublications.changed();
    }
    @Transactional
    public void finish(Work expected,DurableCommandProtocol.Result result) {
        var work=active(expected);var registered=registration(work);
        if(!work.push().state().equals("RUNNING")||!result.stopConfirmed()||!result.worker().equals(registered.worker())||!result.requestSha256().equals(work.attempt().requestSha256())
                ||!DurableCommandProtocol.matches(request(work),result))throw WorkflowPublications.changed();
        if(mapper.result(work.attempt().id(),work.attempt().version(),encoding.encode(result))!=1)throw WorkflowPublications.changed();
        boolean success=result.successful()&&result.output().strip().equals("LOOPPER_GIT_PUSH_CONFIRMED");
        transition(work.push(),success?"PUSHED":"BLOCKED",success?LifecycleEvent.COMPLETE:LifecycleEvent.REQUIRE_INPUT,work.attempt().id(),success?null:reason(result));
    }
    @Transactional
    public void suspend(Work expected,String reason) {
        var row=mapper.find(expected.push().requirementId()).orElseThrow(WorkflowPublications::changed);
        if(row.equals(expected.push())&&Set.of("PREPARING","RUNNING").contains(row.state()))transition(row,"BLOCKED",LifecycleEvent.REQUIRE_INPUT,row.activeAttemptId(),WorkflowFailures.safe(reason));
    }
    @Transactional
    public WorkflowPush.View retry(Work expected,long version,boolean stopped) {
        var work=active(expected);var row=work.push();if(!row.state().equals("BLOCKED"))return view(row);if(row.version()!=version)throw WorkflowPublications.changed();
        var attempt=work.attempt();String id=attempt.id(),next=attempt.requestJson()==null?"PREPARING":"RUNNING";
        if(attempt.resultJson()!=null){
            if(!stopped||!encoding.decode(attempt.resultJson(),DurableCommandProtocol.Result.class).stopConfirmed())throw WorkflowPublications.changed();
            id=UUID.randomUUID().toString();next="PREPARING";
            if(mapper.insertAttempt(new Attempt(id,row.id(),attempt.ordinal()+1,null,null,null,null,0,Instant.now().toString()))!=1)throw WorkflowPublications.changed();
        }
        transition(row,next,next.equals("PREPARING")?LifecycleEvent.RETRY:LifecycleEvent.RESUME,id,null);return get(row.requirementId());
    }
    public DurableCommandProtocol.Request request(Work work){return encoding.decode(work.attempt().requestJson(),DurableCommandProtocol.Request.class);}
    public DurableCommandProtocol.Registration registration(Work work){return encoding.decode(work.attempt().registrationJson(),DurableCommandProtocol.Registration.class);}
    public boolean sameResult(Work work,DurableCommandProtocol.Result result){return work.attempt().resultJson()!=null&&work.attempt().resultJson().equals(encoding.encode(result));}
    private Work active(Work expected){var current=work(expected.push().requirementId());if(!current.equals(expected))throw WorkflowPublications.changed();return current;}
    private void transition(Push row,String next,LifecycleEvent event,String attempt,String reason){lifecycle.transition(subject(row),row.state(),next,event,reason==null?"WORKFLOW_PUSH":reason,Map.of(),()->mapper.transition(row.id(),row.version(),row.state(),next,attempt,reason,Instant.now().toString()),WorkflowPublications::changed);}
    private WorkflowPush.View view(Push row){var input=encoding.decode(row.inputJson(),GitPushProtocol.Input.class);var attempt=mapper.attempt(row.activeAttemptId()).orElseThrow(WorkflowPublications::changed);return new WorkflowPush.View(row.requirementId(),row.state(),row.version(),input.remote(),input.url(),input.branch(),input.commit(),attempt.ordinal(),row.reasonCode());}
    private String digest(String requirement,WorkflowPush.Request request){return encoding.digest("WORKFLOW_REMOTE_PUSH",requirement,request);}
    private static void validate(WorkflowPush.Request request){if(request==null||request.requestKey()==null||!request.requestKey().matches("[A-Za-z0-9_-]{16,100}")||request.previewSha256()==null||!request.previewSha256().matches("[0-9a-f]{64}"))throw WorkflowPublications.changed();GitPublicationTransport.name(request.remote());}
    private static String reason(DurableCommandProtocol.Result result){String line=result.output().strip();String prefix="LOOPPER_GIT_PUSH_FAILURE:";return line.startsWith(prefix)&&line.substring(prefix.length()).matches("[A-Z][A-Z0-9_]{1,119}")?line.substring(prefix.length()):"WORKFLOW_PUSH_RESULT_UNCONFIRMED";}
    private static GitPushProtocol.Input identity(GitPushProtocol.Input input,String id){return new GitPushProtocol.Input(id,input.project(),input.repository(),input.gitDirectory(),input.remote(),input.url(),input.branch(),input.commit(),input.tree());}
    private static LifecycleTransitionService.Subject subject(Push row){return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_PUSH,row.id(),LifecycleScopeType.PROJECT,row.projectId());}
}
