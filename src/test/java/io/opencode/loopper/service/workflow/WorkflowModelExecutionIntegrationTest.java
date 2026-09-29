package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
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
class WorkflowModelExecutionIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("loopper.data-dir",()->DATA.toString());
        properties.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("models.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowModelAdmission admission;
    @Autowired WorkflowModelExecution execution;
    @Autowired WorkflowModelStore store;
    @Autowired WorkflowModelActions controls;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowModelTools tools;
    @Autowired WorkflowRuntimeSupport identity;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired OpenCodeClient client;
    @Autowired ProjectService projects;
    @Autowired RolePublishingService roles;
    @Autowired RoleConfigurationMapper roleMapper;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    FakeOpenCodeClient fake;
    String project,revision;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); roles.seedBuiltin();
        fake=(FakeOpenCodeClient)client; fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        project=projects.create("模型工作节点",Files.createDirectory(directory.resolve("project")).toString(),"test").id();
        revision=roleMapper.latest("builtin.general").revisionId();
    }
    @Test void modelDeliveryWaitsForIndependentStopThenBecomesTheHumanNodesPinnedInput() {
        String id=requirement(true);
        var request=request(id); var receipt=admission.start(id,"work",request);
        assertThat(admission.start(id,"work",request)).isEqualTo(receipt);
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isZero();
        String attempt=receipt.attemptId(); running(attempt);
        var work=(Map<?,?>)call(attempt,WorkflowModelProfile.WORK,Map.of());
        assertThat(work.keySet().containsAll(List.of("task","outputs","expectedAttemptVersion"))).isTrue();
        assertThat(work.get("parameters")).isEqualTo(Map.of("workFocus","核对需求"));
        assertThat(work.containsKey("planningPresets")).isFalse();
        assertThatThrownBy(()->call(attempt,WorkflowModelProfile.WORK,Map.of("catalog",true))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->call(attempt,WorkflowModelProfile.WORK,Map.of("presetId","analysis.read","presetVersion",1))).isInstanceOf(BadRequestException.class);
        var submitted=submission(attempt,"分析结论");
        var accepted=(WorkflowModelTools.Accepted)call(attempt,WorkflowModelProfile.SUBMIT,submitted);
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        execution.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("RUNNING");
        assertThatThrownBy(()->actions.startHuman(id,"review",new WorkflowNodeActions.Start(key(),plans.require(id).version(),null)))
                .isInstanceOf(ConflictException.class);
        finish(attempt);
        assertThat(plans.require(id).state()).isEqualTo("PAUSED");
        var next=actions.startHuman(id,"review",new WorkflowNodeActions.Start(key(),plans.require(id).version(),null));
        var input=actions.inputs(id,"review",next.attemptId()).values().getFirst();
        assertThat(input.attemptId()).isEqualTo(attempt);
        assertThat(input.sha256()).isEqualTo(accepted.sha256());
        assertThat(input.content().isNull()).isTrue(); assertThat(input.reference().version()).isEqualTo(1);
        assertThat(nodes.input(nodes.attempt(next.attemptId()), input.name()).content().asString()).isEqualTo("分析结论");
        actions.completeHuman(id,"review",new WorkflowNodeActions.Complete(key(),plans.require(id).version(),
                next.attemptId(),next.attemptVersion(),result("人工确认")));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(call(attempt,WorkflowModelProfile.SUBMIT,submitted)).isEqualTo(accepted);
        assertThatThrownBy(()->call(attempt,WorkflowModelProfile.SUBMIT,submission(attempt,"迟到内容"))).isInstanceOf(ConflictException.class);
        for (String table:List.of("task","task_queue","workspace_lease","judge_run")) assertThat(count(table)).isZero();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void lostCreateAndSendAcknowledgementsRecoverExactSessionAndMessageWithoutDuplicating() {
        String id=requirement(false), attempt=start(id);
        execution.advance(attempt);
        var creating=store.require(attempt); assertThat(creating.state()).isEqualTo("CREATING");
        var remote=fake.createSession(store.plan(creating));
        execution.advance(attempt);
        assertThat(store.attempt(store.require(attempt)).externalSessionId()).isEqualTo(remote.remoteId());
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isEqualTo(1);
        var sending=store.require(attempt);
        fake.promptAsync(remote.session(),store.prompt(sending));
        execution.advance(attempt);
        assertThat(fake.promptCalls()).isEqualTo(1);
        assertThat(store.require(attempt).state()).isEqualTo("RUNNING");
        assertThat(store.require(attempt).promptJson()).doesNotContain("lpw_");
        assertThat(store.prompt(store.require(attempt)).system()).contains("阅读本节点授权的资料").doesNotContain("LoopSpec");
        call(attempt,WorkflowModelProfile.SUBMIT,submission(attempt,"完成")); finish(attempt);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void signedScopeCannotCrossAttemptsOrSurviveRuntimeGenerationChangeOrLeakIntoOutputs() {
        String first=start(requirement(false)), second=start(requirement(false));
        running(first); running(second);
        String grant=identity.grant(store.require(first));
        assertThatThrownBy(()->tools.call(WorkflowModelProfile.WORK,Map.of("scope",grant,"attemptId",second,"args",Map.of())))
                .isInstanceOf(io.opencode.loopper.domain.SessionFailure.class);
        assertThatThrownBy(()->call(first,WorkflowModelProfile.SUBMIT,submission(first,grant)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("临时工具凭证");
        assertThat(nodes.findDelivery(first)).isEmpty();
        access.activate(new InternalMcpCredentialProvider(()->18083).issue());
        assertThatThrownBy(()->tools.call(WorkflowModelProfile.WORK,Map.of("scope",grant,"attemptId",first,"args",Map.of())))
                .isInstanceOf(io.opencode.loopper.domain.SessionFailure.class);
        assertThat(store.require(first).state()).isEqualTo("RUNNING");
    }
    @Test void cancelBeforeCreationAndUnknownAbortNeverAllowAnOverlappingAttempt() {
        String early=start(requirement(false)); stop(early); execution.advance(early);
        assertThat(store.require(early).state()).isEqualTo("CANCELLED");
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isZero();
        assertThat(proof(early)).isEqualTo("NO_SESSION_CREATED");
        String prepared=start(requirement(false)); execution.advance(prepared); stop(prepared); execution.advance(prepared);
        assertThat(proof(prepared)).isEqualTo("SESSION_ABSENT");
        String id=requirement(false), active=start(id); running(active); stop(active);
        fake.failNextAborts(1);
        assertThatThrownBy(()->execution.advance(active)).isInstanceOf(io.opencode.loopper.domain.SessionFailure.class);
        assertThat(store.require(active).state()).isEqualTo("STOPPING");
        assertThat(nodes.attempt(active).state()).isEqualTo("STOPPING");
        assertThatThrownBy(()->start(id)).isInstanceOf(ConflictException.class);
        execution.advance(active);
        assertThat(store.require(active).state()).isEqualTo("CANCELLED");
        assertThat(proof(active)).isEqualTo("CREATION_STOP_CONFIRMED");
    }
    @Test void missingSubmissionFailsAndExplicitRetryKeepsItsOriginalInputAndRole() {
        String id=requirement(false), attempt=start(id); running(attempt); finish(attempt);
        assertThat(store.require(attempt).state()).isEqualTo("FAILED");
        assertThat(plans.require(id).state()).isEqualTo("STALLED");
        String retry=start(id);
        assertThat(nodes.attempt(retry).ordinal()).isEqualTo(2);
        assertThat(nodes.attempt(retry).inputsJson()).isEqualTo(nodes.attempt(attempt).inputsJson());
        assertThat(nodes.attempt(retry).roleSnapshotJson()).isEqualTo(nodes.attempt(attempt).roleSnapshotJson());
        running(retry); call(retry,WorkflowModelProfile.SUBMIT,submission(retry,"重试完成")); finish(retry);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void temporarySendFailureResumesTheSameAttemptAndTransactionFailureDoesNotPartiallyAccept() {
        String id=requirement(false), attempt=start(id);
        execution.advance(attempt); execution.advance(attempt);
        fake.failNextPrompts(1);
        assertThatThrownBy(()->execution.advance(attempt)).isInstanceOf(io.opencode.loopper.domain.SessionFailure.class);
        store.suspend(attempt,"OPENCODE_PROMPT_FAILED");
        var paused=controls.get(id,"work",attempt);
        assertThat(paused.suspended()).isTrue();
        assertThat(plans.require(id).state()).isEqualTo("STALLED");
        controls.resume(id,"work",attempt,new WorkflowModelActions.Command(key(),paused.version()));
        assertThat(plans.require(id).state()).isEqualTo("RUNNING");
        execution.advance(attempt);
        assertThat(count("workflow_node_attempt")).isEqualTo(1);
        jdbc.execute("CREATE TRIGGER reject_model_receipt BEFORE INSERT ON workflow_command WHEN NEW.action='MODEL_SUBMIT' BEGIN SELECT RAISE(ABORT,'test receipt failure'); END");
        var submitted=submission(attempt,"接受的结果");
        assertThatThrownBy(()->call(attempt,WorkflowModelProfile.SUBMIT,submitted)).isInstanceOf(RuntimeException.class);
        assertThat(nodes.findDelivery(attempt)).isEmpty();
        jdbc.execute("DROP TRIGGER reject_model_receipt");
        call(attempt,WorkflowModelProfile.SUBMIT,submitted); finish(attempt);
        assertThat(store.require(attempt).state()).isEqualTo("SUCCEEDED");
    }
    @Test void concurrentStartReplaysOneReceiptAndDoesNotCreateAModelSessionInTheTransaction() throws Exception {
        String id=requirement(false); var request=request(id);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<WorkflowNodeActions.Receipt> call=()->{ barrier.await(); return admission.start(id,"work",request); };
            var first=pool.submit(call); var second=pool.submit(call);
            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(second.get(15,TimeUnit.SECONDS));
        }
        assertThat(count("workflow_node_attempt")).isEqualTo(1);
        assertThat(count("workflow_model_launch")).isEqualTo(1);
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isZero();
    }
    @Test void modelReadsOnlyItsNamedFrozenInputAndCanPageWithoutChangingTheInput() {
        String id=requirement(false,true);
        String original="范围😀甲\n".repeat(4000);
        var inputs=Map.of("brief",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree(original)));
        String attempt=admission.start(id,"work",new WorkflowModelAdmission.Start(key(),plans.require(id).version(),inputs,
                new OpenCodeClient.OpenCodeModel("fake","test",false))).attemptId();
        running(attempt);
        var work=json.valueToTree(call(attempt,WorkflowModelProfile.WORK,Map.of()));
        assertThat(work.path("inputs").get(0).path("name").asText()).isEqualTo("source");
        assertThat(json.writeValueAsString(work)).doesNotContain(original);
        StringBuilder read=new StringBuilder(); int offset=0;
        while (true) {
            var page=json.valueToTree(call(attempt,WorkflowModelProfile.INPUT,Map.of("name","source","offset",offset,"limit",997)));
            read.append(page.path("text").asText());
            assertThat(page.path("sha256").asText()).isEqualTo(work.path("inputs").get(0).path("sha256").asText());
            if (page.path("nextOffset").isNull()) break;
            offset=page.path("nextOffset").asInt();
        }
        assertThat(read.toString()).isEqualTo(original);
        assertThatThrownBy(()->call(attempt,WorkflowModelProfile.INPUT,Map.of("name","brief"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->call(attempt,WorkflowModelProfile.INPUT,Map.of("name","source","limit",12001))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->call(attempt,WorkflowModelProfile.INPUT,Map.of("name","source","offset",original.length()+1))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->call(attempt,WorkflowModelProfile.INPUT,Map.of("name","source","offset",3))).isInstanceOf(BadRequestException.class);
        assertThat(json.valueToTree(call(attempt,WorkflowModelProfile.INPUT,Map.of("name","source","offset",2,"limit",1))).path("text").asText())
                .isEqualTo("😀");
        assertThat(nodes.inputs(nodes.attempt(attempt)).values().getFirst().content().asString()).isEqualTo(original);
    }
    @Test void malformedOrIncompleteDeliveryIsCorrectableInTheSameAttempt() {
        String attempt=start(requirement(false)); running(attempt);
        var good=json.valueToTree(result("正确交付"));
        var extra=((tools.jackson.databind.node.ObjectNode)good).deepCopy(); extra.put("undeclared",true);
        var extraOutput=((tools.jackson.databind.node.ObjectNode)good).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)extraOutput.path("outputs").path("result")).put("undeclared",true);
        var numericSummary=((tools.jackson.databind.node.ObjectNode)good).deepCopy(); numericSummary.put("summary",123);
        var wrongType=((tools.jackson.databind.node.ObjectNode)good).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)wrongType.path("outputs").path("result")).put("kind","JSON");
        var missing=((tools.jackson.databind.node.ObjectNode)good).deepCopy(); missing.putObject("outputs");
        for (var rejected:List.of(extra,extraOutput,numericSummary,wrongType,missing)) {
            assertThatThrownBy(()->call(attempt,WorkflowModelProfile.SUBMIT,Map.of("requestKey",key(),
                    "expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",rejected))).isInstanceOf(BadRequestException.class);
            assertThat(nodes.findDelivery(attempt)).isEmpty();
        }
        call(attempt,WorkflowModelProfile.SUBMIT,submission(attempt,"正确交付")); finish(attempt);
        assertThat(store.require(attempt).state()).isEqualTo("SUCCEEDED");
        assertThat(count("workflow_node_attempt")).isEqualTo(1);
    }
    private String requirement(boolean human) { return requirement(human,false); }
    private String requirement(boolean human,boolean input) {
        var first=new Node("work","分析",NodeKind.WORK,WorkflowModelProfile.MODULE,1,"builtin.general","分析需求并交付结论",
                input?List.of(new Input("source",InputSource.REQUIREMENT,"brief",null,DataKind.TEXT,true)):List.of(),
                List.of(new Output("result","分析结果",DataKind.TEXT,true)),List.of(),
                new Completion(CompletionKind.DELIVERABLES,"交付有效分析",null),2,false,Map.of("workFocus","核对需求"),revision);
        var all=new ArrayList<Node>(); all.add(first); var edges=new ArrayList<Edge>();
        if (human) {
            all.add(new Node("review","检查",NodeKind.HUMAN,null,0,null,"查看分析",
                    List.of(new Input("analysis",InputSource.NODE,"work","result",DataKind.TEXT,true)),
                    List.of(new Output("result","确认结果",DataKind.TEXT,true)),List.of(),
                    new Completion(CompletionKind.HUMAN,"人工确认",null),0,false,Map.of()));
            edges.add(new Edge("chain","work","review",null));
        }
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"模型测试","",new WorkflowGraph(1,all,edges,
                input?List.of(new PublicInput("brief","本次资料",DataKind.TEXT,true)):List.of()),CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求任务","分析本次目标",template.id(),1));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));
        return owner.id();
    }
    private WorkflowModelAdmission.Start request(String id) { return new WorkflowModelAdmission.Start(key(),plans.require(id).version(),null,new OpenCodeClient.OpenCodeModel("fake","test",false)); }
    private String start(String id) { return admission.start(id,"work",request(id)).attemptId(); }
    private void running(String id) { for (int i=0;i<4 && !store.require(id).state().equals("RUNNING");i++) execution.advance(id); assertThat(store.require(id).state()).isEqualTo("RUNNING"); }
    private void finish(String attempt) { fake.setSessionState(store.attempt(store.require(attempt)).externalSessionId(),"COMPLETED"); execution.advance(attempt); }
    private void stop(String attempt) { var row=store.require(attempt); controls.stop(row.requirementId(),"work",attempt,new WorkflowModelActions.Command(key(),row.version())); }
    private Map<String,Object> submission(String attempt,String text) { return Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",result(text)); }
    private WorkflowDelivery result(String text) { return new WorkflowDelivery("完成本节点工作",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree(text)))); }
    private Object call(String attempt,String tool,Map<String,Object> args) { return tools.call(tool,Map.of("scope",identity.grant(store.require(attempt)),"attemptId",attempt,"args",args)); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    private String proof(String attempt) { return jdbc.queryForObject("SELECT kind FROM workflow_attempt_stop WHERE attempt_id=?",String.class,attempt); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static Path data() { try { return Files.createTempDirectory("workflow-model-tests-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
}
