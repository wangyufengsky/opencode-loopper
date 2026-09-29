package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.roles.RolePublishingService;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.workflow-monitor-enabled=false","loopper.monitor-delay=1h"})
class WorkflowFinishIntegrationTest {
    private static final Path DATA=data();
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("loopper.data-dir",()->DATA.toString());
        p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("finish.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowFinishes finishes;
    @Autowired WorkflowFinishDrain drain;
    @Autowired WorkflowFinishMapper finishMapper;
    @Autowired WorkflowControls controls;
    @Autowired WorkflowDispatchExecution dispatch;
    @Autowired WorkflowModelExecution execution;
    @Autowired WorkflowModelStore models;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowModelTools tools;
    @Autowired WorkflowRuntimeSupport scopes;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired OpenCodeClient client;
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
        flyway.clean();flyway.migrate();roles.seedBuiltin();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        project=projects.create("提前结束",Files.createDirectory(directory.resolve("project")).toString(),"").id();
        revision=roleMapper.latest("builtin.general").revisionId();
    }
    @ParameterizedTest @EnumSource(value=WorkflowState.class,names={"COMPLETED","FAILED","CANCELLED"})
    void neverStartedPlanCanRecordEachUserOutcomeWithoutFabricatingNodeResults(WorkflowState outcome) {
        String id=requirement(false);var request=request(id,outcome);var receipt=finishes.request(id,request);
        assertThat(receipt.state()).isEqualTo(outcome.name());assertThat(finishes.request(id,request)).isEqualTo(receipt);
        assertThat(finishes.get(id).intent().reason()).isEqualTo("用户明确决定");
        assertThat(finishes.get(id).intent().finalizedAt()).isNotNull();
        assertThat(count("workflow_node_attempt")).isZero();
        assertThatThrownBy(()->finishes.request(id,request(id,WorkflowState.CANCELLED))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_finish_intent SET reason='改写' WHERE requirement_id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_finish_intent WHERE requirement_id=?",id)).isInstanceOf(RuntimeException.class);
    }
    @Test void receiptFailureRollsBackDecisionControlAndAllLifecycleChanges() {
        String id=requirement(true);start(id);var before=plans.require(id);var control=controls.get(id);
        jdbc.execute("CREATE TRIGGER fail_finish_receipt BEFORE INSERT ON workflow_command WHEN NEW.action='FINISH' BEGIN SELECT RAISE(ABORT,'lost finish receipt'); END");
        var request=request(id,WorkflowState.FAILED);
        assertThatThrownBy(()->finishes.request(id,request)).isInstanceOf(RuntimeException.class);
        assertThat(plans.require(id)).isEqualTo(before);assertThat(controls.get(id)).isEqualTo(control);assertThat(finishMapper.find(id)).isEmpty();
        jdbc.execute("DROP TRIGGER fail_finish_receipt");
        assertThat(finishes.request(id,request).state()).isEqualTo("FAILED");
        assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.DONE);
    }
    @Test void userSuccessWaitsForUnknownAbortAndPreservesTheAcceptedDeliverable() {
        String id=requirement(true);start(id);dispatch.advance(id);String attempt=attempt(id);running(attempt);
        submit(attempt);var original=nodes.delivery(attempt);
        var request=request(id,WorkflowState.COMPLETED);var receipt=finishes.request(id,request);
        assertThat(receipt.state()).isEqualTo("STOPPING");assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_FINISHING");
        dispatch.advance(id);assertThat(count("workflow_node_attempt")).isEqualTo(1);
        drain.stop(id,attempt);fake.failNextAborts(1);
        assertThatThrownBy(()->execution.advance(attempt)).isInstanceOf(io.opencode.loopper.domain.SessionFailure.class);
        assertThat(finishes.finalizeReady(id)).isFalse();assertThat(finishes.get(id).intent().finalizedAt()).isNull();
        assertThat(plans.require(id).state()).isEqualTo("STOPPING");
        assertThatThrownBy(()->submit(attempt)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->start(id)).isInstanceOf(ConflictException.class);
        execution.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("CANCELLED");assertThat(nodes.delivery(attempt)).isEqualTo(original);
        var restarted=new WorkflowFinishCoordinator(finishMapper,drain,finishes);restarted.tick();
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_USER_FINISHED");
        assertThat(finishes.request(id,request)).isEqualTo(receipt);assertThat(finishes.get(id).pending().empty()).isTrue();
    }
    @Test void cancellationBeforeCreationNeverCallsProvider() {
        String id=requirement(true);start(id);dispatch.advance(id);String attempt=attempt(id);
        finishes.request(id,request(id,WorkflowState.CANCELLED));drain.stop(id,attempt);
        execution.advance(attempt);assertThat(finishes.finalizeReady(id)).isTrue();
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isZero();
        assertThat(nodes.hasStop(attempt)).isTrue();assertThat(nodes.findDelivery(attempt)).isEmpty();
    }
    @Test void humanInputStopsLocallyAndLateSubmissionCannotChangeTheOutcome() {
        var human=new Node("work","人工决定",NodeKind.HUMAN,null,0,null,"确认结果",List.of(),outputs(),List.of(),
                new Completion(CompletionKind.HUMAN,"人工完成",null),0,false,Map.of());
        String id=create(human,true);start(id);dispatch.advance(id);String attempt=attempt(id);
        var late=new WorkflowNodeActions.Complete(key(),plans.require(id).version(),attempt,nodes.attempt(attempt).version(),result());
        finishes.request(id,request(id,WorkflowState.FAILED));drain.stop(id,attempt);
        assertThat(finishes.finalizeReady(id)).isTrue();
        assertThatThrownBy(()->actions.completeHuman(id,"work",late)).isInstanceOf(ConflictException.class);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("CANCELLED");assertThat(nodes.findDelivery(attempt)).isEmpty();
    }
    @Test void concurrentDifferentDecisionsCannotOverwriteTheWinningUserIntent() throws Exception {
        String id=requirement(true);long version=plans.require(id).version();
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var operations=List.<java.util.concurrent.Callable<Object>>of(
                    ()->{barrier.await(3,java.util.concurrent.TimeUnit.SECONDS);try{return finishes.request(id,new WorkflowFinishes.Request(key(),version,WorkflowState.COMPLETED,"成功决定"));}catch(ConflictException conflict){return conflict;}},
                    ()->{barrier.await(3,java.util.concurrent.TimeUnit.SECONDS);try{return finishes.request(id,new WorkflowFinishes.Request(key(),version,WorkflowState.CANCELLED,"取消决定"));}catch(ConflictException conflict){return conflict;}});
            var results=new ArrayList<Object>();for(var future:pool.invokeAll(operations))results.add(future.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(WorkflowCommands.Receipt.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream().filter(ConflictException.class::isInstance).count()).isEqualTo(1);
        }
        assertThat(count("workflow_finish_intent")).isEqualTo(1);
        assertThat(finishes.get(id).intent().targetState()).isEqualTo(plans.require(id).state());
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void finalizationFailureRetainsIntentAndStopProofForRestart() {
        String id=requirement(true);start(id);dispatch.advance(id);String attempt=attempt(id);
        finishes.request(id,request(id,WorkflowState.COMPLETED));drain.stop(id,attempt);execution.advance(attempt);
        jdbc.execute("CREATE TRIGGER fail_finish_final BEFORE UPDATE ON workflow_finish_intent BEGIN SELECT RAISE(ABORT,'final rollback'); END");
        assertThatThrownBy(()->finishes.finalizeReady(id)).isInstanceOf(RuntimeException.class);
        assertThat(plans.require(id).state()).isEqualTo("STOPPING");assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.PAUSED);
        assertThat(nodes.hasStop(attempt)).isTrue();assertThat(finishes.get(id).intent().finalizedAt()).isNull();
        jdbc.execute("DROP TRIGGER fail_finish_final");assertThat(finishes.finalizeReady(id)).isTrue();
        assertThat(finishes.finalizeReady(id)).isTrue();
    }
    @Test void invalidDecisionStaleVersionCrossOwnerAndMissingLocalAuthorityDoNotMutate() throws Exception {
        String id=requirement(true),other=requirement(true);start(id);dispatch.advance(id);String attempt=attempt(id);
        for(var bad:List.of(new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.RUNNING,"原因"),
                new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.COMPLETED," ")))
            assertThatThrownBy(()->finishes.request(id,bad)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->finishes.request(id,new WorkflowFinishes.Request(key(),0,WorkflowState.COMPLETED,"旧版本"))).isInstanceOf(ConflictException.class);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workflows/requirements/"+id+"/finish")
                .contentType("application/json").content(json.writeValueAsString(request(id,WorkflowState.COMPLETED))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        assertThat(finishMapper.find(id)).isEmpty();
        start(other);dispatch.advance(other);finishes.request(other,request(other,WorkflowState.CANCELLED));
        assertThatThrownBy(()->drain.stop(other,attempt)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->drain.stop(id+"-missing",attempt)).isInstanceOf(ConflictException.class);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("PREPARING");
    }
    private WorkflowFinishes.Request request(String id,WorkflowState target){return new WorkflowFinishes.Request(key(),plans.require(id).version(),target,"用户明确决定");}
    private String requirement(boolean confirm){return create(new Node("work","分析",NodeKind.WORK,WorkflowModelProfile.MODULE,1,"builtin.general","分析",
            List.of(),outputs(),List.of(),new Completion(CompletionKind.DELIVERABLES,"交付",null),0,false,Map.of(),revision),confirm);}
    private String create(Node node,boolean confirm) {
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"流程","",new WorkflowGraph(1,List.of(node),List.of(),List.of()),CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求","完成目标",template.id(),1));
        if(confirm)plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private void start(String id){controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.CONTINUOUS,null,null,MODEL,List.of()));}
    private String attempt(String id){return nodes.node(id,1,"work").latestAttemptId();}
    private void running(String attempt){for(int i=0;i<4 && !models.require(attempt).state().equals("RUNNING");i++)execution.advance(attempt);}
    private void submit(String attempt){tools.call(WorkflowModelProfile.SUBMIT,Map.of("scope",scopes.grant(models.require(attempt)),"attemptId",attempt,"args",
            Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",result())));}
    private List<Output> outputs(){return List.of(new Output("result","结果",DataKind.TEXT,true));}
    private WorkflowDelivery result(){return new WorkflowDelivery("结果",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("原始结论"))));}
    private int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-finish-tests-");}catch(Exception failure){throw new ExceptionInInitializerError(failure);}}
}
