package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.roles.RolePublishingService;
import io.opencode.loopper.template.SourceDesign;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.workflow-monitor-enabled=false","loopper.monitor-delay=1h"})
class WorkflowTestDesignIntegrationTest {
    private static final Path DATA=data();
    private static final String SOURCE="def total(a, b):\n    return a + b\n";
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("design.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowControls controls;
    @Autowired WorkflowDispatchExecution dispatch;
    @Autowired WorkflowSourceExecution sources;
    @Autowired WorkflowTestProfileExecution profiles;
    @Autowired WorkflowModelExecution execution;
    @Autowired WorkflowModelStore models;
    @Autowired WorkflowModelTools tools;
    @Autowired WorkflowRuntimeSupport identity;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired OpenCodeClient client;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowNodePresets presets;
    @Autowired WorkflowFinishes finishes;
    @Autowired WorkflowFinishDrain drain;
    @Autowired WorkflowTestInputs testInputs;
    @Autowired WorkflowTestWorkContract contract;
    @Autowired RolePublishingService roles;
    @Autowired ProjectService projects;
    @Autowired WorkflowSourceReadMapper reads;
    @Autowired WorkflowEncoding encoding;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    Path root;String project,id,source,profile,attempt;FakeOpenCodeClient fake;
    @BeforeEach void prepare()throws Exception {
        flyway.clean();flyway.migrate();roles.seedBuiltin();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        root=Files.createDirectory(directory.resolve("project"));Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("src/calculator.py"),SOURCE);Files.writeString(root.resolve("pytest.ini"),"[pytest]\ntestpaths = tests\n");
        project=projects.create("单测设计",root.toString(),"").id();
    }
    @Test void scenariosRequireFrozenProfileAndFullOwnSourceReadsThenWaitForStopAndHumanCheckpoint()throws Exception {
        create();attempt=start("design");var work=(Map<?,?>)call(WorkflowModelProfile.WORK,Map.of());assertThat(work.containsKey("testDesign")).isTrue();
        assertThatThrownBy(()->submit(candidate())).hasMessageContaining("完整读取本次固定测试配置");
        input();read(1,1);assertThatThrownBy(()->submit(candidate())).hasMessageContaining("完整读取");
        Files.writeString(root.resolve("src/calculator.py"),"live changed");assertThat(read(2,200).content()).isEqualTo("    return a + b");
        var request=submission(candidate());var receipt=call(WorkflowModelProfile.SUBMIT,request);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("RUNNING");assertThat(nodes.hasStop(attempt)).isFalse();
        execution.advance(attempt);assertThat(nodes.attempt(attempt).state()).isEqualTo("RUNNING");finish();
        assertThat(call(WorkflowModelProfile.SUBMIT,request)).isEqualTo(receipt);
        var result=encoding.decode(encoding.encode(delivery().outputs().get("design").content()),WorkflowTestDesign.Frozen.class);
        assertThat(result.sourceAttemptId()).isEqualTo(source);assertThat(result.profileAttemptId()).isEqualTo(profile);
        assertThat(result.design().scenarios()).hasSize(1);assertThat(controls.get(id).checkpoints()).extracting(c->c.attemptId()).contains(attempt);
        assertThat(nodes.node(id,1,"check").latestAttemptId()).isNull();
        controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,"check",Map.of(),MODEL,List.of(attempt)));
        dispatch.advance(id);var human=nodes.attempt(nodes.node(id,1,"check").latestAttemptId());assertThat(human.state()).isEqualTo("WAITING_INPUT");
        var consumed=nodes.inputs(human).values().getFirst();assertThat(consumed.attemptId()).isEqualTo(attempt);
        assertThat(consumed.content()).isEqualTo(delivery().outputs().get("design").content());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease",Integer.class)).isZero();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void sourceAndProfileMustHaveTheSameProgramProducerAndExactLineage() {
        create();attempt=start("design");var fixed=nodes.inputs(nodes.attempt(attempt));testInputs.require(fixed);
        for(String changed:List.of("source","profile")) {
            var values=fixed.values().stream().map(v->v.name().equals(changed)?new WorkflowDelivery.Input(v.name(),v.kind(),v.source(),v.sourceId(),v.outputName(),"another-attempt",v.sha256(),v.content()):v).toList();
            assertThatThrownBy(()->testInputs.require(copy(fixed,values))).hasMessageContaining("完全一致");
        }
        var values=fixed.values().stream().map(v->v.name().equals("profile")?new WorkflowDelivery.Input(v.name(),v.kind(),v.source(),v.sourceId(),v.outputName(),v.attemptId(),v.sha256(),encoding.decode("{}",tools.jackson.databind.JsonNode.class)):v).toList();
        assertThatThrownBy(()->testInputs.require(copy(fixed,values))).hasMessageContaining("完全一致");
        var publicProfile=fixed.values().stream().map(v->v.name().equals("profile")?new WorkflowDelivery.Input(v.name(),v.kind(),"REQUIREMENT",v.sourceId(),v.outputName(),v.attemptId(),v.sha256(),v.content()):v).toList();
        assertThatThrownBy(()->testInputs.require(copy(fixed,publicProfile))).hasMessageContaining("完全一致");
    }
    @Test void candidateCannotInventReferencesOmitCoverageOrUseContextAsTarget()throws Exception {
        Files.writeString(root.resolve("src/other.py"),"def other(): return 0\n");create();attempt=start("design");input();read(1,200);
        assertThatThrownBy(()->submit(candidate())).hasMessageContaining("覆盖本批全部源码");
        var scenario=candidate().scenarios().getFirst();
        var invented=new WorkflowTestDesign.Scenario(scenario.key(),scenario.path(),scenario.category(),scenario.title(),scenario.steps(),scenario.expected(),List.of(new SourceDesign.Reference("src/calculator.py",hash(),1,1,"def invented():")));
        assertThatThrownBy(()->submit(new WorkflowTestDesign.Candidate("测试","说明",List.of(invented),List.of()))).hasMessageContaining("引用");
        var original=nodes.definition(nodes.requireNode(nodes.attempt(attempt).nodeRunId()));
        var context=new Node(original.id(),original.title(),original.kind(),original.moduleId(),1,original.roleId(),original.task(),original.inputs(),original.outputs(),original.outcomes(),original.completion(),0,true,Map.of("targetPaths","[\"pytest.ini\"]"),original.roleRevisionId());
        assertThatThrownBy(()->contract.context(context,nodes.inputs(nodes.attempt(attempt)))).hasMessageContaining("不能用上下文");
    }
    @Test void malformedAndDuplicateScenariosCanBeCorrectedInTheSameAttempt() {
        create();attempt=start("design");input();read(1,200);
        assertThatThrownBy(()->call(WorkflowModelProfile.SUBMIT,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",result(Map.of("scenarios","bad"))))).hasMessageContaining("完整的单测场景结构");
        var value=candidate();assertThatThrownBy(()->submit(new WorkflowTestDesign.Candidate(value.title(),value.summary(),List.of(value.scenarios().getFirst(),value.scenarios().getFirst()),List.of()))).hasMessageContaining("编号必须唯一");
        submit(value);finish();assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");assertThat(nodes.requireNode(nodes.attempt(attempt).nodeRunId()).attemptCount()).isEqualTo(1);
    }
    @Test void failedReadAndSubmitTransactionsDoNotCreateFalseEvidenceOrDelivery() {
        create();attempt=start("design");input();
        jdbc.execute("CREATE TRIGGER fail_test_read BEFORE INSERT ON workflow_source_read BEGIN SELECT RAISE(ABORT,'read rollback'); END");
        assertThatThrownBy(()->read(1,200)).hasStackTraceContaining("read rollback");assertThat(reads.sources(attempt,"source","src/calculator.py")).isEmpty();
        jdbc.execute("DROP TRIGGER fail_test_read");read(1,200);
        jdbc.execute("CREATE TRIGGER fail_test_submit BEFORE INSERT ON workflow_command WHEN NEW.action='MODEL_SUBMIT' BEGIN SELECT RAISE(ABORT,'submit rollback'); END");
        var request=submission(candidate());assertThatThrownBy(()->call(WorkflowModelProfile.SUBMIT,request)).hasStackTraceContaining("submit rollback");assertThat(nodes.findDelivery(attempt)).isEmpty();
        jdbc.execute("DROP TRIGGER fail_test_submit");call(WorkflowModelProfile.SUBMIT,request);finish();assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_source_read WHERE attempt_id=?",attempt)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_design_input_read SET end_offset=0 WHERE attempt_id=?",attempt)).isInstanceOf(RuntimeException.class);
    }
    @Test void cancellationPreventsFurtherReadingOrLateSubmission() {
        create();attempt=start("design");input();read(1,200);String scope=identity.grant(models.require(attempt));var request=submission(candidate());
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消设计"));drain.stop(id,attempt);execution.advance(attempt);
        assertThat(finishes.finalizeReady(id)).isTrue();
        assertThatThrownBy(()->tools.call(WorkflowModelProfile.SUBMIT,Map.of("scope",scope,"attemptId",attempt,"args",request))).isInstanceOf(RuntimeException.class);
        assertThat(nodes.findDelivery(attempt)).isEmpty();assertThat(nodes.attempt(attempt).state()).isEqualTo("CANCELLED");
    }
    @Test void anotherRoleAttemptCannotReusePreviousSourceReadingEvidence() {
        create();attempt=start("design");input();read(1,200);String first=attempt;
        create();attempt=start("design");input();assertThatThrownBy(()->submit(candidate())).hasMessageContaining("引用");
        assertThat(reads.sources(first,"source","src/calculator.py")).hasSize(1);assertThat(reads.sources(attempt,"source","src/calculator.py")).isEmpty();
        read(1,200);submit(candidate());finish();assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");
    }
    @Test void oversizedBatchFailsBeforeCreatingAnyModelAttempt()throws Exception {
        for(int i=0;i<12;i++)Files.writeString(root.resolve("src/file"+i+".py"),"def value(): return 1\n");create();
        requestStart("design");dispatch.advance(id);assertThat(nodes.node(id,1,"design").latestAttemptId()).isNull();
        assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_SOURCE_BATCH_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_model_launch",Integer.class)).isZero();
    }
    private void create() {
        var capture=preset("source.snapshot","source",List.of(new Input("path",InputSource.REQUIREMENT,"path",null,DataKind.TEXT,true)));
        capture=new Node(capture.id(),capture.title(),capture.kind(),capture.moduleId(),1,null,capture.task(),capture.inputs(),capture.outputs(),capture.outcomes(),capture.completion(),0,false,Map.of("sourcePurpose","UNIT_TEST"));
        var input=new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true);
        var profileNode=preset("source.test-profile","profile",List.of(input));
        var design=preset("source.test-design","design",List.of(input,new Input("profile",InputSource.NODE,"profile","profile",DataKind.JSON,true)));
        var check=new Node("check","检查场景",NodeKind.HUMAN,null,1,null,"确认测试场景",List.of(new Input("design",InputSource.NODE,"design","design",DataKind.JSON,true)),List.of(new Output("result","确认",DataKind.TEXT,true)),List.of(),new Completion(CompletionKind.HUMAN,"确认",null),0,false,Map.of());
        var graph=new WorkflowGraph(1,List.of(capture,profileNode,design,check),List.of(new Edge("sp","source","profile",null),new Edge("pd","profile","design",null),new Edge("dc","design","check",null)),List.of(new PublicInput("path","源码路径",DataKind.TEXT,true)));
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"单测场景流程","",graph,CanvasLayout.empty()));var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"单测场景","补齐单测",template.id(),1));id=owner.id();plans.confirm(id,new WorkflowRequests.VersionCommand(key(),owner.version()));
        source=start("source");sources.advance(source);profile=start("profile");profiles.advance(profile);assertThat(nodes.attempt(profile).state()).isEqualTo("SUCCEEDED");
    }
    private Node preset(String preset,String id,List<Input> inputs){var n=presets.get(preset,1).node();return new Node(id,n.title(),n.kind(),n.moduleId(),1,n.roleId(),n.task(),inputs,n.outputs(),n.outcomes(),n.completion(),0,n.pauseAfter(),n.parameters(),n.roleRevisionId());}
    private void requestStart(String node){controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,node,Map.of("path",new WorkflowDelivery.Value(DataKind.TEXT,encoding.decode("\"src\"",tools.jackson.databind.JsonNode.class))),MODEL,List.of()));}
    private String start(String node){requestStart(node);dispatch.advance(id);String run=nodes.node(id,1,node).latestAttemptId();assertThat(run).as(controls.get(id).reasonCode()).isNotNull();if(WorkflowModelProfile.ADAPTER.equals(nodes.attempt(run).adapterKey()))for(int i=0;i<4&&!models.require(run).state().equals("RUNNING");i++)execution.advance(run);return run;}
    private WorkflowSourceReads.SourceText read(int start,int limit){return (WorkflowSourceReads.SourceText)call(WorkflowModelProfile.FILE,Map.of("name","source","path","src/calculator.py","sha256",hash(),"startLine",start,"lineCount",limit));}
    private void input(){int offset=0;while(true){var page=(Map<?,?>)call(WorkflowModelProfile.INPUT,Map.of("name","profile","offset",offset));if(page.get("nextOffset")==null)return;offset=((Number)page.get("nextOffset")).intValue();}}
    private WorkflowTestDesign.Candidate candidate(){return new WorkflowTestDesign.Candidate("加法场景","验证两个整数求和",List.of(new WorkflowTestDesign.Scenario("sum","src/calculator.py","NORMAL","两个正数求和",List.of("调用 total(1, 2)"),"返回 3",List.of(new SourceDesign.Reference("src/calculator.py",hash(),1,1,"def total(a, b):")))),List.of());}
    private WorkflowDelivery result(Object value){return new WorkflowDelivery("已设计场景，测试尚未执行",null,Map.of("design",new WorkflowDelivery.Value(DataKind.JSON,encoding.decode(encoding.encode(value),tools.jackson.databind.JsonNode.class)),"summary",new WorkflowDelivery.Value(DataKind.TEXT,encoding.decode("\"场景设计\"",tools.jackson.databind.JsonNode.class))));}
    private Map<String,Object> submission(WorkflowTestDesign.Candidate value){return Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",result(value));}
    private Object submit(WorkflowTestDesign.Candidate value){return call(WorkflowModelProfile.SUBMIT,submission(value));}
    private Object call(String tool,Map<String,Object> args){return tools.call(tool,Map.of("scope",identity.grant(models.require(attempt)),"attemptId",attempt,"args",args));}
    private void finish(){fake.setSessionState(nodes.attempt(attempt).externalSessionId(),"COMPLETED");execution.advance(attempt);}
    private WorkflowDelivery delivery(){return encoding.decode(nodes.delivery(attempt).contentJson(),WorkflowDelivery.class);}
    private static WorkflowDelivery.Inputs copy(WorkflowDelivery.Inputs value,List<WorkflowDelivery.Input> inputs){return new WorkflowDelivery.Inputs(1,value.requirementId(),value.planRevision(),value.nodeId(),value.objective(),inputs);}
    private static String hash(){return SourceTreeCapture.hash(SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-test-design-").toRealPath();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
