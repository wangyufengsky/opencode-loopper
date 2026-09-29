package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.monitor-delay=1h"})
class WorkflowWorkspacesIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("loopper.data-dir",()->DATA.toString());
        p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("workspaces.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowExecutionMapper execution;
    @Autowired LoopperMapper mapper;
    @Autowired WorkflowWriterLeases writers;
    @Autowired WorkflowWorkspaces workspaces;
    @Autowired WorkflowWorkspaceStore store;
    @Autowired WorkflowCodeSnapshots codes;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowModelStore models;
    @Autowired ProjectService projects;
    @Autowired DirectWorkspaceLeaseCoordinator legacy;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean WorkflowGitWorkspace gitWorkspace;
    @TempDir Path directory;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    Path root;
    String project,requirement,first,sourceCommit;
    DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity;
    @BeforeEach void setup() throws Exception {
        flyway.clean();flyway.migrate();
        root=Files.createDirectory(directory.resolve("project"));
        command("init","--quiet","--initial-branch=main");
        Files.writeString(root.resolve("code.txt"),"original"); commit("baseline");
        sourceCommit=command("rev-parse","HEAD").strip();
        project=projects.create("节点工作区",root.toString(),"").id();
        identity=DirectWorkspaceLeaseCoordinator.identify(root);
        var parent=node("first",List.of());
        var child=node("second",List.of(new Input("workspace",InputSource.NODE,"first","code",DataKind.CODE,true)));
        var graph=new WorkflowGraph(1,List.of(parent,child),List.of(new Edge("dependency","first","second",null)),List.of());
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"顺序开发","",graph,CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求","两步开发",template.id(),1));
        requirement=owner.id();plans.confirm(requirement,new WorkflowRequests.VersionCommand(key(),owner.version()));
        first=start("first");
    }
    @Test void twoNodesUseTheirPinnedCodeInputAndRestoreTheSourceBeforeEachLeaseHandoff() throws Exception {
        var prepared=workspaces.prepare(first);
        assertThat(branch()).isEqualTo(prepared.branch()); assertThat(prepared.state()).isEqualTo("READY");
        run(first); Files.writeString(root.resolve("code.txt"),"first result");Files.writeString(root.resolve("added.txt"),"first added");
        stopProof(first);
        var checkpoint=workspaces.capture(first); assertThat(checkpoint.state()).isEqualTo("FROZEN");
        var firstResult=workspaces.codeDelivery(first);
        workspaces.restore(first);complete(first,firstResult);workspaces.release(first);
        assertThat(branch()).isEqualTo("main");assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");
        assertThat(root.resolve("added.txt")).doesNotExist();
        String second=start("second");var childWorkspace=workspaces.prepare(second);
        assertThat(childWorkspace.seedSnapshotId()).isEqualTo(firstResult.snapshotId());
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("first result");
        assertThat(Files.readString(root.resolve("added.txt"))).isEqualTo("first added");
        assertThat(nodes.inputs(nodes.attempt(second)).values().getFirst().attemptId()).isEqualTo(first);
        run(second);Files.writeString(root.resolve("code.txt"),"second result");stopProof(second);
        workspaces.capture(second);var secondResult=workspaces.codeDelivery(second);
        workspaces.restore(second);complete(second,secondResult);workspaces.release(second);
        assertThat(read(second,secondResult,"code.txt")).isEqualTo("second result");
        assertThat(read(first,firstResult,"code.txt")).isEqualTo("first result");
        assertThat(branch()).isEqualTo("main");assertThat(command("rev-parse","HEAD").strip()).isEqualTo(sourceCommit);
        assertThat(command("status","--porcelain")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isZero();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void preparationAcknowledgementLossResumesTheSameBranchWithoutAnotherWorkspace() {
        var once=new AtomicBoolean(true);
        doAnswer(call->{call.callRealMethod();if(once.getAndSet(false))throw new IllegalStateException("lost prepare acknowledgement");return null;})
                .when(gitWorkspace).enter(any());
        assertThatThrownBy(()->workspaces.prepare(first)).hasMessage("lost prepare acknowledgement");
        var pending=store.require(first);assertThat(pending.state()).isEqualTo("PREPARING");
        assertThat(branch()).isEqualTo(pending.branch());
        var restored=workspaces.prepare(first);
        assertThat(restored.state()).isEqualTo("READY");assertThat(restored.branch()).isEqualTo(pending.branch());
        assertThat(command("for-each-ref","--format=%(refname)","refs/heads/loopper/").lines()).hasSize(1);
    }
    @Test void captureAcknowledgementLossKeepsTheOriginalCheckpointAndBlocksLaterDirtyDrift() throws Exception {
        workspaces.prepare(first);run(first);Files.writeString(root.resolve("code.txt"),"frozen result");stopProof(first);
        var once=new AtomicBoolean(true);
        doAnswer(call->{Object value=call.callRealMethod();if(once.getAndSet(false))throw new IllegalStateException("lost capture acknowledgement");return value;})
                .when(gitWorkspace).capture(any(),anyString(),anyString());
        assertThatThrownBy(()->workspaces.capture(first)).hasMessage("lost capture acknowledgement");
        var pending=store.require(first);assertThat(pending.state()).isEqualTo("CAPTURING");
        String frozenCommit=command("rev-parse",pending.checkpointRef()).strip();
        Files.writeString(root.resolve("user-change.txt"),"later user edit");
        assertThatThrownBy(()->workspaces.capture(first)).isInstanceOfSatisfying(TaskFailure.class,
                error->assertThat(error.code()).isEqualTo("WORKFLOW_CHECKPOINT_DRIFT"));
        assertThat(command("rev-parse",pending.checkpointRef()).strip()).isEqualTo(frozenCommit);
        assertThat(Files.readString(root.resolve("user-change.txt"))).isEqualTo("later user edit");
        Files.delete(root.resolve("user-change.txt"));
        var recovered=workspaces.capture(first);
        assertThat(recovered.checkpointCommit()).isEqualTo(frozenCommit);
        var result=workspaces.codeDelivery(first);workspaces.restore(first);complete(first,result);workspaces.release(first);
        assertThat(read(first,result,"code.txt")).isEqualTo("frozen result");
    }
    @Test void restorationAcknowledgementLossAndLeaseReceiptReplayNeverTouchTheNextWriter() throws Exception {
        workspaces.prepare(first);run(first);Files.writeString(root.resolve("code.txt"),"result");stopProof(first);
        workspaces.capture(first);var result=workspaces.codeDelivery(first);
        var once=new AtomicBoolean(true);
        doAnswer(call->{call.callRealMethod();if(once.getAndSet(false))throw new IllegalStateException("lost restore acknowledgement");return null;})
                .when(gitWorkspace).restore(any());
        assertThatThrownBy(()->workspaces.restore(first)).hasMessage("lost restore acknowledgement");
        assertThat(store.require(first).state()).isEqualTo("RESTORING");assertThat(branch()).isEqualTo("main");
        assertThat(workspaces.restore(first).state()).isEqualTo("RESTORED");complete(first,result);
        Files.writeString(root.resolve("manual.txt"),"manual");
        assertThatThrownBy(()->workspaces.release(first)).isInstanceOf(TaskFailure.class);
        assertThat(mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().holderWorkflowAttemptId()).isEqualTo(first);
        Files.delete(root.resolve("manual.txt"));
        legacyWaiter();
        var released=workspaces.release(first);assertThat(released.task().taskId()).isEqualTo("legacy-next");
        Files.writeString(root.resolve("next-writer.txt"),"new owner content");
        assertThat(workspaces.release(first)).isEqualTo(released);
        assertThat(Files.readString(root.resolve("next-writer.txt"))).isEqualTo("new owner content");
        assertThat(store.require(first).state()).isEqualTo("RELEASED");
    }
    @Test void cancellationBeforeAnyBranchSwitchReleasesOnlyAfterUnusedWorkspaceIsProvenRestored() {
        doThrow(new IllegalStateException("before checkout")).when(gitWorkspace).enter(any());
        assertThatThrownBy(()->workspaces.prepare(first)).hasMessage("before checkout");
        tx.executeWithoutResult(s->models.create(nodes.attempt(first),plans.require(requirement),root,new OpenCodeClient.OpenCodeModel("fake","test",false)));
        models.stop(first);models.stopped(models.require(first),"NO_SESSION_CREATED",Map.of("phase","PREPARING"));
        assertThatThrownBy(()->workspaces.release(first)).isInstanceOf(ConflictException.class);
        assertThat(workspaces.restore(first).state()).isEqualTo("RESTORED");
        assertThat(workspaces.release(first).lease().state()).isEqualTo("RELEASED");
        assertThat(branch()).isEqualTo("main");
        assertThat(command("for-each-ref","--format=%(refname)","refs/heads/loopper/")).isEmpty();
    }
    @Test void unknownStopKeepsFilesAndLeaseThenAllowsTheSameStoppedAttemptToFinishCapture() throws Exception {
        workspaces.prepare(first);run(first);Files.writeString(root.resolve("code.txt"),"still owned");
        tx.executeWithoutResult(s->writers.markUnconfirmed(identity,first));
        assertThatThrownBy(()->workspaces.capture(first)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->workspaces.restore(first)).isInstanceOf(ConflictException.class);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("still owned");
        assertThat(store.require(first).state()).isEqualTo("READY");
        stopProof(first);workspaces.capture(first);var result=workspaces.codeDelivery(first);
        workspaces.restore(first);complete(first,result);workspaces.release(first);
        assertThat(read(first,result,"code.txt")).isEqualTo("still owned");
    }
    @Test void sourceChangesAfterParentCompletionAreNeverOverwrittenByTheChildInput() throws Exception {
        workspaces.prepare(first);run(first);Files.writeString(root.resolve("code.txt"),"parent");stopProof(first);
        workspaces.capture(first);var result=workspaces.codeDelivery(first);workspaces.restore(first);complete(first,result);workspaces.release(first);
        Files.writeString(root.resolve("code.txt"),"user later commit");commit("user update");
        String second=start("second");
        assertThatThrownBy(()->workspaces.prepare(second)).isInstanceOfSatisfying(ConflictException.class,
                error->assertThat(error.code()).isEqualTo("WORKFLOW_CODE_BASE_CHANGED"));
        assertThat(store.find(second)).isEmpty();assertThat(branch()).isEqualTo("main");
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("user later commit");
    }
    @Test void dirtyInitialSourceIsLeftIntactWithoutCreatingABranchOrCheckpoint() throws Exception {
        Files.writeString(root.resolve("untracked.txt"),"user work");
        assertThatThrownBy(()->workspaces.prepare(first)).isInstanceOfSatisfying(TaskFailure.class,
                error->assertThat(error.code()).isEqualTo("WORKFLOW_WORKSPACE_DIRTY"));
        assertThat(store.find(first)).isEmpty();assertThat(branch()).isEqualTo("main");
        assertThat(Files.readString(root.resolve("untracked.txt"))).isEqualTo("user work");
        assertThat(command("for-each-ref","--format=%(refname)","refs/loopper/checkpoints/")).isEmpty();
    }
    @Test void preparedWorkspaceIsRecheckedBeforeModelLaunchAndLaterEditsArePreserved() throws Exception {
        var ready=workspaces.prepare(first);
        assertThat(workspaces.prepare(first)).isEqualTo(ready);
        Files.writeString(root.resolve("code.txt"),"user changed before model launch");
        assertThatThrownBy(()->workspaces.prepare(first)).isInstanceOfSatisfying(TaskFailure.class,
                error->assertThat(error.code()).isEqualTo("WORKFLOW_WORKSPACE_CHANGED"));
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("user changed before model launch");
        assertThat(nodes.attempt(first).state()).isEqualTo("PREPARING");
        assertThat(nodes.attempt(first).externalSessionId()).isNull();
    }
    @Test void writerLaunchRequiresItsOwnActiveQueueAndReadonlyLaunchesKeepTheirSeparateContract() {
        var source=plans.require(requirement);
        var another=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"等待的需求","目标",source.sourceTemplateId(),source.sourceRevision()));
        plans.confirm(another.id(),new WorkflowRequests.VersionCommand(key(),another.version()));
        String queued=tx.execute(s->{
            var admitted=actions.admit(another.id(),"first",new WorkflowNodeActions.Start(key(),plans.require(another.id()).version(),null));
            return nodes.begin(admitted.node(),admitted.owner().headRevision(),admitted.inputs(),WorkflowWriterLeases.ADAPTER,"{}").id();
        });
        var model=new OpenCodeClient.OpenCodeModel("fake","test",false);
        assertThatThrownBy(()->tx.executeWithoutResult(s->models.create(nodes.attempt(queued),plans.require(another.id()),root,model)))
                .hasMessageContaining("workflow model owner mismatch");
        var waiting=tx.execute(s->writers.admit(identity,queued));
        assertThat(waiting.state()).isEqualTo("QUEUED");
        assertThatThrownBy(()->tx.executeWithoutResult(s->models.create(nodes.attempt(queued),plans.require(requirement),root,model)))
                .hasMessageContaining("workflow model owner mismatch");
        tx.executeWithoutResult(s->models.create(nodes.attempt(queued),plans.require(another.id()),root,model));
        assertThat(models.require(queued).state()).isEqualTo("PREPARING");
        assertThat(models.require(queued).creationPlanJson()).isNull();
        assertThat(nodes.attempt(queued).externalSessionId()).isNull();
        assertThat(mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().holderWorkflowAttemptId()).isEqualTo(first);
    }
    private Node node(String id,List<Input> inputs) {
        return new Node(id,id,NodeKind.WORK,"free.write",1,"builtin.implementation","实现目标",inputs,
                List.of(new Output("code","代码",DataKind.CODE,true)),List.of(),new Completion(CompletionKind.DELIVERABLES,"有效交付",null),1,false,Map.of());
    }
    private String start(String key) {
        return tx.execute(s->{
            var admitted=actions.admit(requirement,key,new WorkflowNodeActions.Start(key(),plans.require(requirement).version(),null));
            String attempt=nodes.begin(admitted.node(),admitted.owner().headRevision(),admitted.inputs(),WorkflowWriterLeases.ADAPTER,"{}").id();
            writers.admit(identity,attempt);return attempt;
        });
    }
    private void run(String id) { tx.executeWithoutResult(s->{
        execution.attach(id,nodes.attempt(id).version(),"session-"+id,Instant.now().toString());
        nodes.transition(nodes.attempt(id),WorkflowAttemptState.RUNNING,LifecycleEvent.START);
    }); }
    private void stopProof(String id) { tx.executeWithoutResult(s->execution.insertStop(new WorkflowExecutionRows.Stop(id,"ABORT_CONFIRMED","session-"+id,"{\"abort\":true}",Instant.now().toString()))); }
    private void complete(String id,WorkflowCodeSnapshot.Reference reference) {
        tx.executeWithoutResult(s->{var attempt=nodes.attempt(id);
            nodes.accept(attempt,new WorkflowDelivery("代码",null,Map.of("code",new WorkflowDelivery.Value(DataKind.CODE,json.valueToTree(reference)))));
            nodes.finish(attempt,WorkflowAttemptState.SUCCEEDED);
        });
    }
    private String read(String id,WorkflowCodeSnapshot.Reference reference,String path) {
        return new String(codes.read(project,requirement,id,reference,path),java.nio.charset.StandardCharsets.UTF_8);
    }
    private void legacyWaiter() {
        String now=Instant.now().toString();
        mapper.insertTask(new TaskRow("legacy-next",project,null,"旧任务","READY",root.toString(),"DIRECT",null,"direct:test",now,now,0,null,null,null,"LEGACY_AGGREGATE",null));
        assertThat(legacy.acquireOrEnqueue(identity,"legacy-next","MANUAL",null).state()).isEqualTo("QUEUED");
    }
    private void commit(String message) { command("add","-A");command("-c","user.name=Fixture","-c","user.email=fixture@local.invalid","commit","--quiet","-m",message); }
    private String branch() { return command("symbolic-ref","--short","HEAD").strip(); }
    private String command(String... args) { var result=git.run(root,Duration.ofSeconds(10),List.of(args));result.requireSuccess(List.of(args));return result.output(); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static Path data() { try{return Files.createTempDirectory("workflow-workspaces-");}catch(Exception e){throw new ExceptionInInitializerError(e);} }
}
