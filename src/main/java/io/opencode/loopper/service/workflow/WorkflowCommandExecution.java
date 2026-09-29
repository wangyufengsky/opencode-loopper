package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.*;
import io.opencode.loopper.runtime.DurableCommandProtocol.*;
import io.opencode.loopper.verification.ProcessCommandPolicy;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Resumes original intents and receipts; a missing result never authorizes another test process. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowCommandExecution {
    private final WorkflowCommandStore store;
    private final WorkflowCommandWorkspace workspace;
    private final DurableCommands commands;
    private final WorkflowEncoding encoding;
    private final WorkflowNativeTestReports nativeReports;
    private final WorkflowNativeEnvironment nativeEnvironment;
    private final WorkflowGitCaptures captures;
    public WorkflowCommandExecution(WorkflowCommandStore store,WorkflowCommandWorkspace workspace,DurableCommands commands,WorkflowEncoding encoding,WorkflowNativeTestReports nativeReports,WorkflowNativeEnvironment nativeEnvironment,WorkflowGitCaptures captures) {
        this.store=store;this.workspace=workspace;this.commands=commands;this.encoding=encoding;this.nativeReports=nativeReports;this.nativeEnvironment=nativeEnvironment;this.captures=captures;
    }
    public void advance(String id) {
        var context=store.context(id);if(context==null || context.run().suspended() && !context.run().state().equals("STOPPING"))return;
        try {
            var row=context.run();
            if(row.state().equals("PREPARING")){prepare(context);return;}
            if(row.state().equals("STOPPING") && row.requestJson()==null){store.finish(context,null);return;}
            var job=new DurableCommands.Job(id,row.requestSha256());
            if(row.state().equals("STOPPING"))commands.requestStop(job);
            var observation=commands.observe(job);
            if(observation.stopUnknown()){store.suspend(row,"WORKFLOW_COMMAND_STOP_UNCONFIRMED");return;}
            if(observation.registration()==null){commands.ensureSupervisor(job,captures.environment(context));return;}
            if(row.registrationJson()==null){store.registered(context,observation.registration());return;}
            if(!store.registration(row).equals(observation.registration()))throw WorkflowCommands.conflict();
            if(observation.result()!=null){
                if(context.nativeTest()!=null&&!row.state().equals("STOPPING"))nativeReports.capture(context,java.nio.file.Path.of(store.request(row).directory()),observation.result());
                var captured=captures.read(context,observation.result());
                store.finish(context,observation.result(),captured.repository(),captured.history(),captured.review());return;
            }
            if(row.state().equals("RUNNING"))commands.grant(job,store.registration(row));
        } catch(RuntimeException failure) {
            String code=WorkflowFailures.code(failure);
            if(!code.equals("WORKFLOW_VERSION_CONFLICT"))store.suspend(context.run(),code.equals("WORKFLOW_MODEL_RECOVERY_REQUIRED")?"WORKFLOW_COMMAND_RECOVERY_REQUIRED":code);
        }
    }
    private void prepare(WorkflowCommandStore.Context context) {
        var capture=captures.prepare(context);
        if(capture!=null){var job=commands.prepare(capture);store.prepared(context,capture,job.requestSha256());return;}
        var input=context.input();var row=context.run();
        var reference=encoding.decode(encoding.encode(input.content()),WorkflowCodeSnapshot.Reference.class);
        var directory=workspace.prepare(row.attemptId(),row.projectId(),row.requirementId(),input.attemptId(),reference);
        if(context.nativeTest()!=null)nativeReports.preflight(directory,context.nativeTest().module());
        var compiled=context.nativeTest()==null?new WorkflowNativeEnvironment.Prepared(context.spec().argv(),java.util.List.of()):nativeEnvironment.compile(directory,context.nativeTest().module(),context.spec().argv());
        var normalized=ProcessCommandPolicy.normalizeMavenCommand(compiled.argv());
        if(normalized.failure()!=null)throw new io.opencode.loopper.service.BadRequestException("WORKFLOW_COMMAND_INVALID","命令参数无法解析，请分别填写每个参数。");
        var argv=context.nativeTest()!=null&&context.nativeTest().module().framework().equals("pytest")?normalized.command():new ExecutableResolver().resolve(directory,normalized.command()).argv();
        var spec=context.spec();new WorkflowCommandVerification(1,spec.inputName(),argv,spec.timeoutSeconds(),spec.purpose(),spec.outputContains()).validate();
        var request=new Request(row.attemptId(),directory.toString(),argv,spec.timeoutSeconds(),compiled.preparations());
        var job=commands.prepare(request);store.prepared(context,request,job.requestSha256());
    }
}
