package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import static io.opencode.loopper.workflow.WorkflowDispatch.Mode.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.RoleConfigurationMapper;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.roles.RolePublishingService;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.workflow-monitor-enabled=false"})
class WorkflowControlsIntegrationTest {
    private static final Path DATA=data();
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("loopper.data-dir",()->DATA.toString());
        properties.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("controls.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowBuiltinFlows builtinFlows;
    @Autowired WorkflowControls controls;
    @Autowired WorkflowPlanRevisions revisions;
    @Autowired WorkflowPlanCandidates candidates;
    @Autowired WorkflowDispatchExecution dispatch;
    @Autowired WorkflowModelAdmission admission;
    @Autowired WorkflowModelExecution execution;
    @Autowired WorkflowModelStore models;
    @Autowired WorkflowModelActions modelActions;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowRunReads reads;
    @Autowired WorkflowRunActivity activity;
    @Autowired WorkflowModelTools tools;
    @Autowired WorkflowRuntimeSupport identity;
    @Autowired InternalMcpRuntimeAccess access;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean OpenCodeClient client;
    @Autowired ProjectService projects;
    @Autowired RolePublishingService roles;
    @Autowired RoleConfigurationMapper roleMapper;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.web.context.WebApplicationContext web;
    @TempDir Path directory;
    FakeOpenCodeClient fake;
    String project,revision;
    @BeforeEach void prepare() throws Exception {
        flyway.clean();flyway.migrate();roles.seedBuiltin();
        fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();
        access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        project=projects.create("流程执行控制",Files.createDirectory(directory.resolve("project")).toString(),"test").id();
        revision=roleMapper.latest("builtin.general").revisionId();
    }
    @Test void continuousStartsParallelRootsAndJoinWaitsForBothIndependentStops() {
        String id=requirement(List.of(work("left",false,0),work("right",false,0),work("join",false,0)),
                List.of(edge("left","join"),edge("right","join")));
        var request=startRequest(id,CONTINUOUS,null,List.of());
        var receipt=controls.start(id,request);
        assertThat(controls.start(id,request)).isEqualTo(receipt);
        dispatch.advance(id);
        String left=attempt(id,"left"),right=attempt(id,"right");
        assertThat(attempt(id,"join")).isNull();
        assertThat(count("workflow_node_attempt")).isEqualTo(2);
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isZero();
        running(left);running(right);submit(left);submit(right);
        dispatch.advance(id);assertThat(attempt(id,"join")).isNull();
        finish(left);dispatch.advance(id);assertThat(attempt(id,"join")).isNull();
        finish(right);dispatch.advance(id);
        succeed(attempt(id,"join"));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.DONE);
        for(String table:List.of("task","task_queue","workspace_lease","judge_run"))assertThat(count(table)).isZero();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void untilIncludesEveryAncestorButNeitherAnUnrelatedBranchNorTheTargetsChild() {
        String id=requirement(List.of(work("a",false,0),work("b",false,0),work("target",false,0),work("after",false,0),work("other",false,0)),
                List.of(edge("a","target"),edge("b","target"),edge("target","after")));
        assertThatThrownBy(()->start(id,SINGLE,"target",List.of())).isInstanceOf(ConflictException.class);
        assertThat(count("workflow_node_attempt")).isZero();
        start(id,UNTIL,"target",List.of());dispatch.advance(id);
        assertThat(attempt(id,"other")).isNull();
        succeed(attempt(id,"a"));succeed(attempt(id,"b"));dispatch.advance(id);
        succeed(attempt(id,"target"));dispatch.advance(id);
        assertThat(attempt(id,"after")).isNull();assertThat(attempt(id,"other")).isNull();
        assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_SCOPE_COMPLETE");
        assertThat(plans.require(id).state()).isEqualTo("PAUSED");
        assertThat(count("workflow_node_attempt")).isEqualTo(3);
    }
    @Test void singleOnlyRunsTheSelectedNodeAndCannotUseLegacyStartToBypassControl() {
        String id=requirement(List.of(work("a",false,0),work("b",false,0)),List.of());
        start(id,SINGLE,"a",List.of());dispatch.advance(id);succeed(attempt(id,"a"));dispatch.advance(id);
        assertThat(attempt(id,"b")).isNull();assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.PAUSED);
        assertThatThrownBy(()->admission.start(id,"b",new WorkflowModelAdmission.Start(key(),plans.require(id).version(),null,MODEL)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("执行控制");
        start(id,SINGLE,"b",List.of());dispatch.advance(id);succeed(attempt(id,"b"));
        assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.DONE);
    }
    @Test void checkpointsRequireExactAcknowledgementAndFinalCheckpointPreventsCompletion() {
        String id=requirement(List.of(work("a",true,0),work("b",true,0)),List.of(edge("a","b")));
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);String a=attempt(id,"a");succeed(a);
        assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.WAITING);
        for(int i=0;i<3;i++)dispatch.advance(id);
        assertThat(attempt(id,"b")).isNull();
        assertThatThrownBy(()->start(id,CONTINUOUS,null,List.of())).isInstanceOf(ConflictException.class).hasMessageContaining("检查点");
        var continuation=startRequest(id,CONTINUOUS,null,List.of(a));
        jdbc.execute("CREATE TRIGGER fail_control_receipt BEFORE INSERT ON workflow_command WHEN NEW.action='CONTROL_START' BEGIN SELECT RAISE(ABORT,'test receipt failure'); END");
        assertThatThrownBy(()->controls.start(id,continuation)).isInstanceOf(RuntimeException.class);
        assertThat(controls.get(id).checkpoints()).extracting(value->value.attemptId()).containsExactly(a);
        assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.WAITING);
        jdbc.execute("DROP TRIGGER fail_control_receipt");
        var accepted=controls.start(id,continuation);assertThat(controls.start(id,continuation)).isEqualTo(accepted);
        dispatch.advance(id);String b=attempt(id,"b");succeed(b);
        assertThat(plans.require(id).state()).isEqualTo("PAUSED");
        assertThat(controls.get(id).checkpoints()).extracting(value->value.attemptId()).containsExactly(b);
        start(id,CONTINUOUS,null,List.of(b));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(count("workflow_node_checkpoint")).isEqualTo(2);
    }
    @Test void aHumanNodeIsItsOwnCheckpointAndCompletionResumesContinuousDispatch() {
        var human=new Node("human","人工决定",NodeKind.HUMAN,null,0,null,"确认结果",List.of(),outputs(),List.of(),
                new Completion(CompletionKind.HUMAN,"人工完成",null),0,true,Map.of());
        String id=requirement(List.of(human,work("next",false,0)),List.of(edge("human","next")));
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);
        assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_HUMAN_INPUT");
        assertThatThrownBy(()->start(id,CONTINUOUS,null,List.of())).isInstanceOf(ConflictException.class).hasMessageContaining("人工节点");
        dispatch.advance(id);assertThat(attempt(id,"next")).isNull();
        String attempt=attempt(id,"human");
        actions.completeHuman(id,"human",new WorkflowNodeActions.Complete(key(),plans.require(id).version(),attempt,nodes.attempt(attempt).version(),result()));
        assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.ACTIVE);
        assertThat(count("workflow_node_checkpoint")).isZero();
        dispatch.advance(id);succeed(attempt(id,"next"));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void retryExhaustionPersistsAcrossModeChangesAndManualRetryAuthorizesOnlyOneExtraAttempt() {
        String id=requirement(List.of(work("a",false,1)),List.of());
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);
        String first=attempt(id,"a");running(first);finish(first);dispatch.advance(id);
        String second=attempt(id,"a");assertThat(second).isNotEqualTo(first);
        assertThat(nodes.attempt(second).inputsJson()).isEqualTo(nodes.attempt(first).inputsJson());
        assertThat(nodes.attempt(second).roleSnapshotJson()).isEqualTo(nodes.attempt(first).roleSnapshotJson());
        running(second);finish(second);dispatch.advance(id);
        assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_RETRY_EXHAUSTED");
        assertThat(plans.require(id).state()).isEqualTo("STALLED");
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);
        assertThat(count("workflow_node_attempt")).isEqualTo(2);
        start(id,SINGLE,"a",List.of());dispatch.advance(id);
        String third=attempt(id,"a");assertThat(nodes.attempt(third).ordinal()).isEqualTo(3);
        running(third);finish(third);dispatch.advance(id);
        assertThat(count("workflow_node_attempt")).isEqualTo(3);
        assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_RETRY_EXHAUSTED");
    }
    @Test void acknowledgedPauseInvalidatesAReadyPermitBeforeAnySessionOrAttemptIsCreated() {
        String id=requirement(List.of(work("a",false,0)),List.of());
        start(id,CONTINUOUS,null,List.of());var ready=controls.prepare(id);
        var request=new WorkflowControls.Pause(key(),controls.get(id).controlVersion());
        var paused=controls.pause(id,request);assertThat(controls.pause(id,request)).isEqualTo(paused);
        assertThatThrownBy(()->admission.dispatch(id,"a",new WorkflowModelAdmission.Start(key(),plans.require(id).version(),null,MODEL),ready.permit()))
                .isInstanceOf(ConflictException.class);
        dispatch.advance(id);assertThat(count("workflow_node_attempt")).isZero();
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isZero();
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);assertThat(count("workflow_node_attempt")).isEqualTo(1);
    }
    @Test void concurrentCompletedCheckpointsRemainVisibleWhilePausedAndInvalidateOldAcknowledgements() {
        String id=requirement(List.of(work("a",true,0),work("b",true,0)),List.of());
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);
        controls.pause(id,new WorkflowControls.Pause(key(),controls.get(id).controlVersion()));
        String a=attempt(id,"a"),b=attempt(id,"b");succeed(a);
        var stale=startRequest(id,CONTINUOUS,null,List.of(a));succeed(b);
        assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.PAUSED);
        assertThat(controls.get(id).checkpoints()).extracting(value->value.attemptId()).containsExactlyInAnyOrder(a,b);
        assertThatThrownBy(()->controls.start(id,stale)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->start(id,CONTINUOUS,null,List.of(a))).isInstanceOf(ConflictException.class);
        start(id,CONTINUOUS,null,List.of(a,b));assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void suspendedOrUnknownStopBlocksNewDispatchWithoutDiscardingTheOriginalAttempt() {
        String id=requirement(List.of(work("a",false,1),work("b",false,0)),List.of(edge("a","b")));
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);String a=attempt(id,"a");running(a);
        models.suspend(a,"OPENCODE_STATUS_UNAVAILABLE");
        assertThatThrownBy(()->start(id,CONTINUOUS,null,List.of())).isInstanceOf(ConflictException.class).hasMessageContaining("原尝试");
        dispatch.advance(id);assertThat(count("workflow_node_attempt")).isEqualTo(1);
        var state=models.require(a);modelActions.resume(id,"a",a,new WorkflowModelActions.Command(key(),state.version()));
        state=models.require(a);modelActions.stop(id,"a",a,new WorkflowModelActions.Command(key(),state.version()));
        fake.failNextAborts(1);assertThatThrownBy(()->execution.advance(a)).isInstanceOf(io.opencode.loopper.domain.SessionFailure.class);
        assertThatThrownBy(()->start(id,CONTINUOUS,null,List.of())).isInstanceOf(ConflictException.class);
        dispatch.advance(id);assertThat(count("workflow_node_attempt")).isEqualTo(1);
        assertThat(nodes.attempt(a).state()).isEqualTo("STOPPING");
        execution.advance(a);assertThat(nodes.attempt(a).state()).isEqualTo("CANCELLED");
        assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.STALLED);
        assertThat(attempt(id,"b")).isNull();
    }
    @Test void simultaneousDispatchRechecksTheSameAdmissionVersion() throws Exception {
        String id=requirement(List.of(work("a",false,0)),List.of());start(id,CONTINUOUS,null,List.of());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<Void> call=()->{barrier.await();dispatch.advance(id);return null;};
            var first=pool.submit(call);var second=pool.submit(call);first.get(15,TimeUnit.SECONDS);second.get(15,TimeUnit.SECONDS);
        }
        assertThat(count("workflow_node_attempt")).isEqualTo(1);assertThat(count("workflow_model_launch")).isEqualTo(1);
        assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.ACTIVE);
    }
    @Test void databaseRejectsCrossRequirementRetriesAndPrematureOrRewrittenCheckpoints() {
        String first=requirement(List.of(work("a",true,0)),List.of()),second=requirement(List.of(work("a",true,0)),List.of());
        start(first,CONTINUOUS,null,List.of());start(second,CONTINUOUS,null,List.of());dispatch.advance(first);dispatch.advance(second);
        String a=attempt(first,"a"),b=attempt(second,"a");
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_run_control SET manual_retry_node=?,manual_retry_ordinal=2 WHERE requirement_id=?",nodes.attempt(b).nodeRunId(),first))
                .hasMessageContaining("owner mismatch");
        assertThatThrownBy(()->jdbc.update("INSERT INTO workflow_node_checkpoint VALUES(?,?,?,'test',NULL)",a,first,"a"))
                .hasMessageContaining("completed work");
        succeed(a);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_node_checkpoint SET requirement_id=? WHERE attempt_id=?",second,a)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_node_checkpoint WHERE attempt_id=?",a)).hasMessageContaining("retained");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_run_control WHERE requirement_id=?",first)).hasMessageContaining("retained");
        start(first,CONTINUOUS,null,List.of(a));
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_node_checkpoint SET acknowledged_at=NULL WHERE attempt_id=?",a)).hasMessageContaining("immutable");
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void continuousSettlesOnlyTheChosenBusinessBranchAndJoinsWithoutExecutingSkippedWork() {
        var decision=new Node("choose","选择路径",NodeKind.HUMAN,null,0,null,"确认需要的工作",List.of(),outputs(),List.of("yes","no"),
                new Completion(CompletionKind.HUMAN,"人工选择",null),0,false,Map.of());
        String id=requirement(List.of(decision,work("yes",false,0),work("no",false,0),work("join",false,0)),
                List.of(new Edge("choose-yes","choose","yes","yes"),new Edge("choose-no","choose","no","no"),edge("yes","join"),edge("no","join")));
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);String choice=attempt(id,"choose");
        actions.completeHuman(id,"choose",new WorkflowNodeActions.Complete(key(),plans.require(id).version(),choice,nodes.attempt(choice).version(),
                new WorkflowDelivery("选择执行路径","yes",result().outputs())));
        dispatch.advance(id);assertThat(nodes.node(id,1,"no").state()).isEqualTo("SKIPPED");
        assertThat(attempt(id,"no")).isNull();assertThat(attempt(id,"join")).isNull();
        succeed(attempt(id,"yes"));dispatch.advance(id);succeed(attempt(id,"join"));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(count("workflow_node_attempt")).isEqualTo(3);
    }
    @Test void preflightFailureKeepsItsActionableReasonAndCanResumeAfterTheDirectoryIsRestored() throws Exception {
        String id=requirement(List.of(work("a",false,0)),List.of());start(id,CONTINUOUS,null,List.of());
        Path root=directory.resolve("project"),moved=directory.resolve("temporarily-moved");Files.move(root,moved);
        try {
            dispatch.advance(id);
            assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.STALLED);
            assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_DIRECTORY_UNAVAILABLE");
            assertThat(count("workflow_node_attempt")).isZero();
        } finally {Files.move(moved,root);}
        var old=controls.get(id);start(id,CONTINUOUS,null,List.of());
        controls.fail(id,new WorkflowDispatch.Permit(old.controlVersion(),old.revision()),"WORKFLOW_OLD_FAILURE");
        assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.ACTIVE);
        dispatch.advance(id);succeed(attempt(id,"a"));assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void resumingSingleKeepsItsScopeThroughAnActiveAttemptAndTheCompletedCheckpoint() {
        String id=requirement(List.of(work("a",true,0),work("b",false,0)),List.of());
        start(id,SINGLE,"a",List.of());dispatch.advance(id);String a=attempt(id,"a");running(a);
        controls.pause(id,new WorkflowControls.Pause(key(),controls.get(id).controlVersion()));
        start(id,SINGLE,"a",List.of());dispatch.advance(id);
        assertThat(attempt(id,"a")).isEqualTo(a);assertThat(attempt(id,"b")).isNull();
        submit(a);finish(a);
        assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.WAITING);
        start(id,SINGLE,"a",List.of(a));dispatch.advance(id);
        assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_SCOPE_COMPLETE");
        assertThat(controls.get(id).mode()).isEqualTo(SINGLE);
        assertThat(attempt(id,"b")).isNull();assertThat(count("workflow_node_attempt")).isEqualTo(1);
        assertThatThrownBy(()->start(id,SINGLE,"a",List.of())).isInstanceOf(ConflictException.class);
    }
    @Test void pagedAttemptSummariesRetainHistoryWithoutReturningBodiesAndCannotCrossRequirements() {
        String id=requirement(List.of(work("a",false,1)),List.of());start(id,CONTINUOUS,null,List.of());dispatch.advance(id);
        String first=attempt(id,"a");running(first);finish(first);dispatch.advance(id);String second=attempt(id,"a");
        var page=reads.page(id,"a",null,1);assertThat(page.items()).extracting(value->value.id()).containsExactly(second);
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(reads.page(id,"a",page.nextCursor(),1).items()).extracting(value->value.id()).containsExactly(first);
        var snapshot=reads.snapshot(id);assertThat(snapshot.control().version()).isEqualTo(snapshot.execution().version());assertThat(snapshot.execution().nodes()).hasSize(1);
        var summary=reads.get(id,"a",second);assertThat(summary.roleName()).isNotBlank();assertThat(summary.version()).isEqualTo(nodes.attempt(second).version());
        assertThat(json.writeValueAsString(summary)).doesNotContain("inputsJson","roleSnapshotJson","promptJson","contentJson","externalSessionId");
        assertThat(reads.definition(id,"a",first).title()).isEqualTo("a");
        String other=requirement(List.of(work("a",false,0)),List.of());
        assertThatThrownBy(()->reads.get(other,"a",first)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->activity.get(other,"a",first)).isInstanceOf(NotFoundException.class);
        var before=models.require(second);assertThat(activity.get(id,"a",second).connected()).isFalse();assertThat(models.require(second)).isEqualTo(before);
        running(second);assertThat(activity.get(id,"a",second).connected()).isTrue();
        String grant=identity.grant(models.require(second));
        assertThat(new OpenCodeClient.SessionPart("part","text","结果",grant,"done").content()).doesNotContain(grant).contains("已隐藏");
    }

    @Test void transcriptObservationSuspendsTransactionsBoundsBodiesAndNeverChangesBusinessState() {
        String id=requirement(List.of(work("a",false,0)),List.of());start(id,CONTINUOUS,null,List.of());dispatch.advance(id);
        String run=attempt(id,"a");running(run);var before=models.require(run);var ownerBefore=plans.require(id);
        org.mockito.Mockito.doAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var parts=new ArrayList<OpenCodeClient.SessionPart>();
            for(int i=0;i<90;i++)parts.add(new OpenCodeClient.SessionPart("part"+i,"text","日志","x".repeat(7999)+"😀".repeat(1000),"done"));
            return new OpenCodeClient.SessionTranscript(parts);
        }).when(client).sessionTranscript(org.mockito.ArgumentMatchers.any());
        var value=activity.get(id,"a",run);
        assertThat(value.connected()).isTrue();assertThat(value.truncated()).isTrue();assertThat(value.parts().getFirst().id()).isEqualTo("part77");
        assertThat(value.parts().stream().mapToInt(part->part.content().length()).sum()).isLessThanOrEqualTo(96000);
        assertThat(value.parts()).allSatisfy(part->{assertThat(part.content().length()).isLessThanOrEqualTo(8000);if(!part.content().isEmpty())assertThat(Character.isHighSurrogate(part.content().charAt(part.content().length()-1))).isFalse();});
        org.mockito.Mockito.doThrow(new RuntimeException("provider unavailable")).when(client).sessionTranscript(org.mockito.ArgumentMatchers.any());
        var unavailable=activity.get(id,"a",run);assertThat(unavailable.connected()).isFalse();assertThat(unavailable.parts()).isEmpty();assertThat(unavailable.detail()).contains("重试");
        assertThat(models.require(run)).isEqualTo(before);assertThat(plans.require(id)).isEqualTo(ownerBefore);
    }

    @Test void requirementCanvasHttpContractsUseRealPersistenceAndConfirmationCannotStartWork() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        var human=new Node("human","人工决定",NodeKind.HUMAN,null,0,null,"确认结果",List.of(),outputs(),List.of(),new Completion(CompletionKind.HUMAN,"人工完成",null),0,true,Map.of());
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"人工流程","",new WorkflowGraph(1,List.of(human),List.of(),List.of()),CanvasLayout.empty()));
        var created=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workflows/requirements")
                .header("X-Loopper-Local-UI","1").contentType("application/json").content(json.writeValueAsString(new WorkflowRequests.CreateRequirement(key(),project,"画布需求","检查目标",template.id(),1))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        String id=json.readTree(created.getResponse().getContentAsString()).path("id").asText();String path="/api/workflows/requirements/"+id;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/confirm").header("X-Loopper-Local-UI","1").contentType("application/json")
                .content(json.writeValueAsString(new WorkflowRequests.VersionCommand(key(),plans.require(id).version()))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/execution"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.execution.state").value("PENDING_START"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.control.configured").value(false));
        assertThat(count("workflow_node_attempt")).isZero();assertThat(count("workspace_lease")).isZero();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/control/start").header("X-Loopper-Local-UI","1").contentType("application/json").content(json.writeValueAsString(startRequest(id,SINGLE,"human",List.of()))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        dispatch.advance(id);String run=attempt(id,"human"),attemptPath=path+"/nodes/human/attempts/"+run;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(attemptPath)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.state").value("WAITING_INPUT"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(attemptPath+"/definition")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.task").value("确认结果"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/nodes/human/human/complete").header("X-Loopper-Local-UI","1").contentType("application/json")
                .content(json.writeValueAsString(new WorkflowNodeActions.Complete(key(),plans.require(id).version(),run,nodes.attempt(run).version(),result()))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/execution")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.execution.state").value("COMPLETED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(attemptPath+"/result")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.delivery.outputs.result.content").value("结果"));
        assertThat(count("workflow_node_attempt")).isEqualTo(1);assertThat(count("workflow_node_delivery")).isEqualTo(1);assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isZero();
    }

    @Test void activePlanChangesCarryUnchangedExecutionAndItsFrozenInputIntoTheNewRevision() {
        String id=requirement(List.of(work("a",false,0),work("b",false,0)),List.of(edge("a","b")));
        start(id,SINGLE,"a",List.of());dispatch.advance(id);String run=attempt(id,"a");running(run);var frozen=nodes.attempt(run);
        var graph=replaceTask(plans.get(id,null).graph(),"b","新的后续任务");var request=revisionRequest(id,graph);
        var applied=revisions.revise(id,request);assertThat(revisions.revise(id,request)).isEqualTo(applied);
        assertThat(plans.require(id).headRevision()).isEqualTo(2);assertThat(attempt(id,"a")).isEqualTo(run);assertThat(nodes.attempt(run)).isEqualTo(frozen);
        assertThat(controls.get(id).revision()).isEqualTo(2);assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.PAUSED);
        succeed(run);start(id,SINGLE,"b",List.of());dispatch.advance(id);assertThat(nodes.attempt(attempt(id,"b")).planRevision()).isEqualTo(2);
    }
    @Test void editingFailedWorkRetainsCumulativeAttemptsAndOldDefinitionWhileUnchangedRetriesCanCrossPlanRevisions() {
        String id=requirement(List.of(work("a",false,0),work("b",false,0)),List.of());
        start(id,SINGLE,"a",List.of());dispatch.advance(id);String old=attempt(id,"a");running(old);finish(old);
        revisions.revise(id,revisionRequest(id,replaceTask(plans.get(id,null).graph(),"b","无关后续调整")));
        start(id,SINGLE,"a",List.of());dispatch.advance(id);String retry=attempt(id,"a");assertThat(nodes.attempt(retry).ordinal()).isEqualTo(2);assertThat(nodes.attempt(retry).planRevision()).isEqualTo(2);
        running(retry);finish(retry);
        revisions.revise(id,revisionRequest(id,replaceTask(plans.get(id,null).graph(),"a","修改失败任务")));
        assertThat(nodes.node(id,3,"a").attemptCount()).isEqualTo(2);assertThat(attempt(id,"a")).isNull();
        start(id,SINGLE,"a",List.of());dispatch.advance(id);String changed=attempt(id,"a");assertThat(nodes.attempt(changed).ordinal()).isEqualTo(3);
        assertThat(reads.definition(id,"a",old).task()).isEqualTo("分析并交付结果");assertThat(reads.definition(id,"a",changed).task()).isEqualTo("修改失败任务");
        running(changed);finish(changed);assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.STALLED);
    }
    @Test void aRunningDefinitionCannotBeReplacedAndFailedRevisionReceiptRollsBackBindingsAndPublicInputs() {
        String id=requirement(List.of(work("a",false,0),work("b",false,0)),List.of(edge("a","b")));
        start(id,SINGLE,"a",List.of());dispatch.advance(id);
        assertThatThrownBy(()->revisions.revise(id,revisionRequest(id,replaceTask(plans.get(id,null).graph(),"a","非法替换")))).isInstanceOf(ConflictException.class);
        var request=revisionRequest(id,replaceTask(plans.get(id,null).graph(),"b","调整后续"));
        jdbc.execute("CREATE TRIGGER fail_dynamic_receipt BEFORE INSERT ON workflow_command WHEN NEW.action='ACTIVE_REVISE' BEGIN SELECT RAISE(ABORT,'test receipt failure'); END");
        assertThatThrownBy(()->revisions.revise(id,request)).isInstanceOf(RuntimeException.class);
        assertThat(plans.require(id).headRevision()).isEqualTo(1);assertThat(count("workflow_plan_node")).isEqualTo(2);assertThat(count("workflow_input_snapshot")).isEqualTo(1);
        jdbc.execute("DROP TRIGGER fail_dynamic_receipt");revisions.revise(id,request);assertThat(plans.require(id).headRevision()).isEqualTo(2);
    }
    @Test void modelPlanDeliveryPausesBothDispatchAndCompletionUntilExplicitUserApplication() {
        String id=requirement(List.of(planner("a"),work("b",false,0)),List.of(edge("a","b")));
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);String run=attempt(id,"a");running(run);
        var next=replaceTask(plans.get(id,null).graph(),"b","根据设计执行阶段一");var request=proposalRequest(run,next);
        var receipt=tools.call(WorkflowModelProfile.SUBMIT,request);assertThat(tools.call(WorkflowModelProfile.SUBMIT,request)).isEqualTo(receipt);
        var candidate=candidates.list(id,"PENDING",null,20).items().getFirst();
        assertThat(candidates.get(id,candidate.id()).originalGraph()).isEqualTo(plans.get(id,null).graph());
        assertThat(plans.require(id).headRevision()).isEqualTo(1);dispatch.advance(id);assertThat(attempt(id,"b")).isNull();
        assertThatThrownBy(()->start(id,CONTINUOUS,null,List.of())).hasMessageContaining("候选计划");
        assertThatThrownBy(()->candidates.apply(id,candidate.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,0,null))).hasMessageContaining("收尾");
        finish(run);assertThat(plans.require(id).state()).isEqualTo("PAUSED");assertThat(candidates.get(id,candidate.id()).sourceCompleted()).isTrue();
        var command=new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,0,null);
        var applied=candidates.apply(id,candidate.id(),command);assertThat(candidates.apply(id,candidate.id(),command)).isEqualTo(applied);
        assertThat(plans.get(id,null).graph()).isEqualTo(next);assertThat(candidates.get(id,candidate.id()).state()).isEqualTo("APPLIED");assertThat(attempt(id,"b")).isNull();
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);succeed(attempt(id,"b"));assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void candidateSubmissionAndApprovalReceiptsAreAtomicAndCannotCrossRequirementScope() {
        String id=requirement(List.of(planner("a"),work("b",false,0)),List.of(edge("a","b")));
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);String run=attempt(id,"a");running(run);var request=proposalRequest(run,replaceTask(plans.get(id,null).graph(),"b","规划的后续"));
        jdbc.execute("CREATE TRIGGER fail_proposal_receipt BEFORE INSERT ON workflow_command WHEN NEW.action='MODEL_SUBMIT' BEGIN SELECT RAISE(ABORT,'test receipt failure'); END");
        assertThatThrownBy(()->tools.call(WorkflowModelProfile.SUBMIT,request)).isInstanceOf(RuntimeException.class);assertThat(count("workflow_plan_candidate")).isZero();assertThat(nodes.findDelivery(run)).isEmpty();assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.ACTIVE);
        jdbc.execute("DROP TRIGGER fail_proposal_receipt");tools.call(WorkflowModelProfile.SUBMIT,request);finish(run);
        var candidate=candidates.list(id,"PENDING",null,20).items().getFirst();String other=requirement(List.of(work("other",false,0)),List.of());
        assertThatThrownBy(()->candidates.get(other,candidate.id())).isInstanceOf(NotFoundException.class);
        var command=new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,0,null);
        jdbc.execute("CREATE TRIGGER fail_apply_receipt BEFORE INSERT ON workflow_command WHEN NEW.action='CANDIDATE_APPLY' BEGIN SELECT RAISE(ABORT,'test receipt failure'); END");
        assertThatThrownBy(()->candidates.apply(id,candidate.id(),command)).isInstanceOf(RuntimeException.class);
        assertThat(plans.require(id).headRevision()).isEqualTo(1);assertThat(candidates.get(id,candidate.id()).state()).isEqualTo("PENDING");
        jdbc.execute("DROP TRIGGER fail_apply_receipt");candidates.apply(id,candidate.id(),command);assertThat(plans.require(id).headRevision()).isEqualTo(2);
    }
    @Test void outOfScopePlansAreCorrectableAndStaleCandidatesRequireAUserDecision() {
        String id=requirement(List.of(planner("a"),work("b",false,0),work("other",false,0)),List.of(edge("a","b")));
        start(id,SINGLE,"a",List.of());dispatch.advance(id);String run=attempt(id,"a");running(run);
        assertThatThrownBy(()->tools.call(WorkflowModelProfile.SUBMIT,proposalRequest(run,replaceTask(plans.get(id,null).graph(),"other","越界修改")))).isInstanceOf(BadRequestException.class);assertThat(nodes.findDelivery(run)).isEmpty();
        tools.call(WorkflowModelProfile.SUBMIT,proposalRequest(run,replaceTask(plans.get(id,null).graph(),"b","计划修改")));
        var candidate=candidates.list(id,"PENDING",null,20).items().getFirst();revisions.revise(id,revisionRequest(id,replaceTask(plans.get(id,null).graph(),"b","用户修改")));finish(run);
        assertThat(candidates.get(id,candidate.id()).stale()).isTrue();
        assertThatThrownBy(()->candidates.apply(id,candidate.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),2,0,null))).hasMessageContaining("版本已变化");
        var reject=new WorkflowPlanCandidates.Reject(key(),0,"保留用户修改");var receipt=candidates.reject(id,candidate.id(),reject);assertThat(candidates.reject(id,candidate.id(),reject)).isEqualTo(receipt);
        assertThat(candidates.get(id,candidate.id()).decisionReason()).isEqualTo("保留用户修改");assertThat(plans.require(id).headRevision()).isEqualTo(2);
    }

    @Test void removingCompletedNodeCannotHideAnUnacknowledgedCheckpointButRetainsItsHistoryAfterAcknowledgement() {
        String id=requirement(List.of(work("a",true,0),work("b",false,0)),List.of());
        start(id,SINGLE,"a",List.of());dispatch.advance(id);String completed=attempt(id,"a");succeed(completed);
        var graph=plans.get(id,null).graph();var after=new WorkflowGraph(graph.schemaVersion(),List.of(graph.nodes().get(1)),List.of(),graph.inputs());
        assertThatThrownBy(()->revisions.revise(id,revisionRequest(id,after))).hasMessageContaining("检查点");
        start(id,SINGLE,"a",List.of(completed));revisions.revise(id,revisionRequest(id,after));
        assertThat(nodes.attempt(completed).state()).isEqualTo("SUCCEEDED");assertThat(nodes.findDelivery(completed)).isPresent();
        assertThat(nodes.summaries(id,plans.require(id).headRevision())).extracting(value->value.nodeKey()).containsExactly("b");
    }
    @Test void candidateNumericVersionsAreStrictAndHistoryCannotBeMutatedAcrossDecisions() {
        String id=requirement(List.of(planner("a"),work("b",false,0)),List.of(edge("a","b")));
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);String run=attempt(id,"a");running(run);var graph=plans.get(id,null).graph();
        var malformed=json.valueToTree(proposalRequest(run,graph));
        ((tools.jackson.databind.node.ObjectNode)malformed.path("args").path("delivery").path("outputs").path("plan").path("content")).put("baseRevision",1.1);
        assertThatThrownBy(()->tools.call(WorkflowModelProfile.SUBMIT,json.readValue(json.writeValueAsString(malformed),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}))).isInstanceOf(BadRequestException.class);
        assertThat(nodes.findDelivery(run)).isEmpty();tools.call(WorkflowModelProfile.SUBMIT,proposalRequest(run,graph));
        var candidate=candidates.list(id,"PENDING",null,20).items().getFirst();
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_plan_candidate SET graph_json='{}' WHERE id=?",candidate.id())).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_plan_candidate WHERE id=?",candidate.id())).hasMessageContaining("retained");
        candidates.reject(id,candidate.id(),new WorkflowPlanCandidates.Reject(key(),0,"重新设计后续工作"));
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_plan_candidate SET state='PENDING' WHERE id=?",candidate.id())).hasMessageContaining("immutable");
        assertThat(nodes.findDelivery(run)).isPresent();
    }
    @Test void removingTheOldScopeTargetDoesNotPreventAdmittedWorkFromRecordingFailure() {
        String id=requirement(List.of(work("a",false,0),work("b",false,0)),List.of(edge("a","b")));
        start(id,UNTIL,"b",List.of());dispatch.advance(id);String run=attempt(id,"a");running(run);
        var graph=plans.get(id,null).graph();revisions.revise(id,revisionRequest(id,new WorkflowGraph(graph.schemaVersion(),List.of(graph.nodes().getFirst()),List.of(),graph.inputs())));
        finish(run);assertThat(nodes.attempt(run).state()).isEqualTo("FAILED");assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_PLAN_CHANGED");
        assertThat(controls.get(id).targetKey()).isEqualTo("b");assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.PAUSED);
    }

    @Test void installedAnalysisFlowExecutesARealPresetThenPassesItsFrozenResultToTheHumanNode() {
        builtinFlows.publish();var template=templates.get("builtin.workflow.analysis",null);
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"按预设分析","分析并检查交付",template.id(),template.revision()));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));String id=owner.id();
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);String run=attempt(id,"analysis");succeed(run);dispatch.advance(id);
        String human=attempt(id,"check");assertThat(nodes.attempt(human).state()).isEqualTo("WAITING_INPUT");
        assertThat(nodes.inputs(nodes.attempt(human)).values().getFirst().attemptId()).isEqualTo(run);
        assertThat(nodes.inputs(nodes.attempt(human)).values().getFirst().content().asText()).isEqualTo("结果");
        actions.completeHuman(id,"check",new WorkflowNodeActions.Complete(key(),plans.require(id).version(),human,nodes.attempt(human).version(),result()));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(count("task")).isZero();
    }
    @Test void bundledDesignerProducesThePlanAndRequiresUserApprovalWithoutASeparatePlanner() {
        builtinFlows.publish();var template=templates.get("builtin.workflow.design",null);
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"按预设设计","澄清需求并规划后续工作",template.id(),template.revision()));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));String id=owner.id();
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);String question=attempt(id,"questions");running(question);
        presetDelivery(question,Map.of("questions",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("请确认交付范围"))));finish(question);dispatch.advance(id);
        String answers=attempt(id,"answers");assertThat(nodes.attempt(answers).state()).isEqualTo("WAITING_INPUT");
        actions.completeHuman(id,"answers",new WorkflowNodeActions.Complete(key(),plans.require(id).version(),answers,nodes.attempt(answers).version(),result()));
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);String design=attempt(id,"design");running(design);
        assertThat(reads.definition(id,"design",design).roleId()).isEqualTo("builtin.designer");
        var work=json.valueToTree(planningRead(design,Map.of()));assertThat(work.path("planningPresets").path("tool").asText()).isEqualTo(WorkflowModelProfile.WORK);
        var catalog=json.valueToTree(planningRead(design,Map.of("catalog",true,"limit",2)));assertThat(catalog.path("items").size()).isEqualTo(2);assertThat(catalog.hasNonNull("nextCursor")).isTrue();
        assertThat(json.writeValueAsString(catalog)).doesNotContain("roleRevisionId","workInstructions");
        var page=json.valueToTree(planningRead(design,Map.of("catalog",true,"limit",2,"cursor",catalog.path("nextCursor").asText())));assertThat(page.path("items").size()).isEqualTo(2);
        assertThatThrownBy(()->planningRead(design,Map.of("catalog",true,"presetId","analysis.read"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->planningRead(design,Map.of("presetId","analysis.read","presetVersion",0))).isInstanceOf(BadRequestException.class);
        var preset=((WorkflowNodePresets.Detail)planningRead(design,Map.of("presetId","analysis.read","presetVersion",1))).node();
        assertThat(preset.roleRevisionId()).isEqualTo(revision);assertThat(count("workflow_node_attempt")).isEqualTo(3);
        var base=plans.get(id,null).graph();var next=new ArrayList<Node>(base.nodes());
        next.add(new Node("followup",preset.title(),preset.kind(),preset.moduleId(),preset.moduleVersion(),preset.roleId(),preset.task(),
                List.of(new Input("material",InputSource.NODE,"design","design",DataKind.TEXT,true)),preset.outputs(),preset.outcomes(),preset.completion(),preset.maxRetries(),preset.pauseAfter(),preset.parameters(),preset.roleRevisionId()));
        var edges=new ArrayList<Edge>(base.edges());edges.add(edge("design","followup"));var proposed=new WorkflowGraph(1,next,edges,base.inputs());
        presetDelivery(design,Map.of("design",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("已澄清的设计方案")),
                "plan",new WorkflowDelivery.Value(DataKind.PLAN,json.valueToTree(new WorkflowPlanCandidates.Proposal(1,1,proposed)))));
        finish(design);dispatch.advance(id);assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_PLAN_REVIEW_REQUIRED");
        assertThat(plans.require(id).headRevision()).isEqualTo(1);assertThat(count("workflow_node_attempt")).isEqualTo(3);
        var candidate=candidates.list(id,"PENDING",null,20).items().getFirst();
        candidates.apply(id,candidate.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,0,null));
        dispatch.advance(id);assertThat(attempt(id,"followup")).isNull();
        start(id,CONTINUOUS,null,List.of());dispatch.advance(id);succeed(attempt(id,"followup"));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(attempt(id,"design")).isEqualTo(design);
        assertThat(count("task")).isZero();assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        assertThatThrownBy(()->planningRead(design,Map.of("catalog",true))).isInstanceOf(ConflictException.class);
    }
    private Object planningRead(String attempt,Map<String,Object> args){return tools.call(WorkflowModelProfile.WORK,Map.of("scope",identity.grant(models.require(attempt)),"attemptId",attempt,"args",args));}
    private void presetDelivery(String attempt,Map<String,WorkflowDelivery.Value> outputs) {
        tools.call(WorkflowModelProfile.SUBMIT,Map.of("scope",identity.grant(models.require(attempt)),"attemptId",attempt,"args",
                Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",new WorkflowDelivery("预设交付",null,outputs))));
    }
    private WorkflowRequests.RevisePlan revisionRequest(String id,WorkflowGraph graph){return new WorkflowRequests.RevisePlan(key(),plans.require(id).version(),plans.require(id).headRevision(),graph);}
    private WorkflowGraph replaceTask(WorkflowGraph graph,String key,String task){return new WorkflowGraph(1,graph.nodes().stream().map(node->!node.id().equals(key)?node:new Node(node.id(),node.title(),node.kind(),node.moduleId(),node.moduleVersion(),node.roleId(),task,node.inputs(),node.outputs(),node.outcomes(),node.completion(),node.maxRetries(),node.pauseAfter(),node.parameters(),node.roleRevisionId())).toList(),graph.edges(),graph.inputs());}
    private Node planner(String key){var node=work(key,false,0);return new Node(node.id(),node.title(),node.kind(),node.moduleId(),node.moduleVersion(),node.roleId(),node.task(),node.inputs(),List.of(new Output("result","设计说明",DataKind.TEXT,true),new Output("plan","候选计划",DataKind.PLAN,true)),node.outcomes(),node.completion(),node.maxRetries(),false,node.parameters(),revision);}
    private Map<String,Object> proposalRequest(String run,WorkflowGraph graph){var row=models.require(run);var attempt=nodes.attempt(run);var outputs=new LinkedHashMap<>(result().outputs());outputs.put("plan",new WorkflowDelivery.Value(DataKind.PLAN,json.valueToTree(new WorkflowPlanCandidates.Proposal(1,attempt.planRevision(),graph))));return Map.of("scope",identity.grant(row),"attemptId",run,"args",Map.of("requestKey",key(),"expectedAttemptVersion",attempt.version(),"delivery",new WorkflowDelivery("设计与后续计划",null,outputs)));}
    private Node work(String id,boolean pause,int retries) {
        return new Node(id,id,NodeKind.WORK,WorkflowModelProfile.MODULE,1,"builtin.general","分析并交付结果",List.of(),outputs(),List.of(),
                new Completion(CompletionKind.DELIVERABLES,"交付结果",null),retries,pause,Map.of(),revision);
    }
    private List<Output> outputs(){return List.of(new Output("result","工作结果",DataKind.TEXT,true));}
    private Edge edge(String from,String to){return new Edge(from+"-"+to,from,to,null);}
    private String requirement(List<Node> nodeList,List<Edge> edges) {
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"执行控制测试","",new WorkflowGraph(1,nodeList,edges,List.of()),CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求任务","执行目标",template.id(),1));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private WorkflowControls.Start startRequest(String id,WorkflowDispatch.Mode mode,String target,List<String> checkpoints) {
        return new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),mode,target,null,MODEL,checkpoints);
    }
    private WorkflowControls.View start(String id,WorkflowDispatch.Mode mode,String target,List<String> checkpoints){return controls.start(id,startRequest(id,mode,target,checkpoints));}
    private String attempt(String id,String node){return nodes.node(id,plans.require(id).headRevision(),node).latestAttemptId();}
    private void running(String attempt){for(int i=0;i<4&&!models.require(attempt).state().equals("RUNNING");i++)execution.advance(attempt);assertThat(models.require(attempt).state()).isEqualTo("RUNNING");}
    private void submit(String attempt){tools.call(WorkflowModelProfile.SUBMIT,Map.of("scope",identity.grant(models.require(attempt)),"attemptId",attempt,"args",
            Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",result())));}
    private WorkflowDelivery result(){return new WorkflowDelivery("完成工作",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("结果"))));}
    private void finish(String attempt){fake.setSessionState(models.attempt(models.require(attempt)).externalSessionId(),"COMPLETED");execution.advance(attempt);}
    private void succeed(String attempt){running(attempt);submit(attempt);finish(attempt);}
    private int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-control-tests-");}catch(Exception failure){throw new ExceptionInInitializerError(failure);}}
}
