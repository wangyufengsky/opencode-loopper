package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
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
class WorkflowTestProfileIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("profiles.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowControls controls;
    @Autowired WorkflowDispatchExecution dispatch;
    @Autowired WorkflowSourceExecution sources;
    @Autowired WorkflowTestProfileExecution execution;
    @Autowired WorkflowTestProfileStore store;
    @Autowired WorkflowTestProfileBuilder builder;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowNodePresets presets;
    @Autowired WorkflowEncoding encoding;
    @Autowired WorkflowSourceMapper sourceRows;
    @Autowired WorkflowFinishes finishes;
    @Autowired WorkflowFinishDrain drain;
    @Autowired ProjectService projects;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temporary;
    Path root;String project,id,attempt,source;
    @BeforeEach void setup()throws Exception {
        flyway.clean();flyway.migrate();root=Files.createDirectory(temporary.resolve("project")).toRealPath();
        Files.createDirectory(root.resolve("src"));Files.writeString(root.resolve("src/calculator.py"),"def total(a,b): return a+b\n");
        Files.writeString(root.resolve("pytest.ini"),"[pytest]\ntestpaths = tests\n");Files.createDirectory(root.resolve("tests"));
        Files.writeString(root.resolve("tests/test_existing.py"),"def test_existing(): assert 1 == 1\n");
        project=projects.create("测试配置",root.toString(),"").id();
    }
    @Test void fixedConfigurationBecomesDownstreamInputWithoutReadingChangedProjectOrRunningTests()throws Exception {
        start("UNIT_TEST",false,Map.of());Files.writeString(root.resolve("pytest.ini"),"changed live configuration");Files.delete(root.resolve("src/calculator.py"));
        execution.advance(attempt);var frozen=result();
        assertThat(frozen.sourceAttemptId()).isEqualTo(source);assertThat(frozen.profile().modules()).singleElement().satisfies(m->{
            assertThat(m.framework()).isEqualTo("pytest");assertThat(m.command()).containsExactly("python","-m","pytest","tests");
            assertThat(m.sourcePaths()).containsExactly("src/calculator.py");assertThat(m.testRoots()).containsExactly("tests");
        });
        dispatch.advance(id);var human=nodes.attempt(nodes.node(id,1,"check").latestAttemptId());assertThat(human.state()).isEqualTo("WAITING_INPUT");
        var input=nodes.inputs(human).values().getFirst();assertThat(input.attemptId()).isEqualTo(attempt);
        assertThat(input.content()).isEqualTo(encoding.decode(encoding.encode(frozen),tools.jackson.databind.JsonNode.class));
        assertThat(Files.readString(root.resolve("pytest.ini"))).isEqualTo("changed live configuration");
        assertThat(nodes.delivery(attempt).contentJson()).contains("测试尚未执行");noExternalExecution();
    }
    @Test void missingFrameworkFailsWithActionableReasonWithoutGuessingOrChangingTheProject()throws Exception {
        Files.delete(root.resolve("pytest.ini"));start("UNIT_TEST",false,Map.of());execution.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("FAILED");var delivery=delivery();
        assertThat(delivery.outputs()).doesNotContainKey("profile");assertThat(delivery.outputs().get("report").content().path("code").asText()).isEqualTo("SOURCE_TEST_CONFIGURATION_REQUIRED");
        assertThat(delivery.outputs().get("report").content().path("message").asText()).contains("未找到所属模块的构建配置");
        assertThat(root.resolve("pytest.ini")).doesNotExist();noExternalExecution();
    }
    @Test void designPurposeCannotStandInForFrozenTestContext() {
        start("DESIGN",false,Map.of());execution.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("FAILED");assertThat(delivery().outputs().get("report").content().path("code").asText()).isEqualTo("WORKFLOW_SOURCE_PURPOSE_MISMATCH");
    }
    @Test void completionRollbackRecoversTheSameAttemptAndFixedInputs()throws Exception {
        start("UNIT_TEST",false,Map.of());
        jdbc.execute("CREATE TRIGGER fail_test_profile BEFORE INSERT ON workflow_node_delivery WHEN NEW.attempt_id='"+attempt+"' BEGIN SELECT RAISE(ABORT,'profile transaction rollback'); END");
        assertThatThrownBy(()->execution.advance(attempt)).hasStackTraceContaining("profile transaction rollback");
        assertThat(nodes.findDelivery(attempt)).isEmpty();assertThat(nodes.hasStop(attempt)).isFalse();assertThat(nodes.attempt(attempt).state()).isEqualTo("RUNNING");
        Files.delete(root.resolve("pytest.ini"));jdbc.execute("DROP TRIGGER fail_test_profile");execution.advance(attempt);
        assertThat(result().profile().modules().getFirst().framework()).isEqualTo("pytest");assertThat(nodes.node(id,1,"profile").attemptCount()).isEqualTo(1);
        var saved=nodes.delivery(attempt);execution.advance(attempt);assertThat(nodes.delivery(attempt)).isEqualTo(saved);noExternalExecution();
    }
    @Test void cancellationRejectsAComputedLateConfigurationWithoutRevivingTheRequirement() {
        start("UNIT_TEST",false,Map.of());var context=store.context(attempt);var computed=builder.build(context);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消识别"));
        drain.stop(id,attempt);assertThat(finishes.finalizeReady(id)).isTrue();
        store.finish(context,builder.delivery(computed,null,null),true);execution.advance(attempt);
        assertThat(nodes.findDelivery(attempt)).isEmpty();assertThat(nodes.attempt(attempt).state()).isEqualTo("CANCELLED");assertThat(plans.require(id).state()).isEqualTo("CANCELLED");
    }
    @Test void recognizedConfigurationHonorsTheUsersManualCheckpoint() {
        start("UNIT_TEST",true,Map.of());execution.advance(attempt);dispatch.advance(id);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");assertThat(controls.get(id).state()).isEqualTo(WorkflowControlState.WAITING);
        assertThat(controls.get(id).checkpoints()).extracting(c->c.attemptId()).containsExactly(attempt);assertThat(nodes.node(id,1,"check").latestAttemptId()).isNull();
    }
    @Test void outputOverrideMayNarrowTheNativeTestRootButCannotEscapeIt() {
        start("UNIT_TEST",false,Map.of("testOutputPath","tests/unit"));execution.advance(attempt);assertThat(result().profile().modules().getFirst().testRoots()).containsExactly("tests/unit");
        for(String path:List.of("../tests","tests/../src","/tmp/tests","C:\\tests","tests//unit")) {
            var original=presets.get("source.test-profile",1).node();var node=profile(original,false,Map.of("testOutputPath",path));
            assertThatThrownBy(()->WorkflowTestProfile.require(node)).isInstanceOf(IllegalArgumentException.class);
        }
        var context=store.context(source); // The source attempt is terminal; no new work is created by a lookup.
        assertThat(context).isNull();
    }
    @Test void damagedFrozenConfigurationCannotFallBackToTheLiveProject()throws Exception {
        start("UNIT_TEST",false,Map.of());var row=sourceRows.find(nodes.attempt(source).nodeRunId()).orElseThrow();
        var manifest=WorkflowSourceRecords.decode(row,encoding);var file=manifest.files().stream().filter(f->f.path().equals("pytest.ini")).findFirst().orElseThrow();
        Files.writeString(DATA.resolve("workflow-source").resolve(row.nodeRunId()).resolve("objects").resolve(file.sha256()),"corrupt");
        execution.advance(attempt);assertThat(nodes.attempt(attempt).state()).isEqualTo("FAILED");assertThat(delivery().outputs()).doesNotContainKey("profile");
        assertThat(Files.readString(root.resolve("pytest.ini"))).contains("[pytest]");
    }
    private void start(String purpose,boolean pause,Map<String,String> parameters) {
        var p=presets.get("source.snapshot",1).node();var capture=new Node("source",p.title(),p.kind(),p.moduleId(),1,null,p.task(),List.of(new Input("path",InputSource.REQUIREMENT,"path",null,DataKind.TEXT,true)),p.outputs(),p.outcomes(),p.completion(),0,false,Map.of("sourcePurpose",purpose));
        var profile=profile(presets.get("source.test-profile",1).node(),pause,parameters);
        var human=new Node("check","查看配置",NodeKind.HUMAN,null,1,null,"核对识别结果",List.of(new Input("profile",InputSource.NODE,"profile","profile",DataKind.JSON,true)),List.of(new Output("result","确认",DataKind.TEXT,true)),List.of(),new Completion(CompletionKind.HUMAN,"确认",null),0,false,Map.of());
        var graph=new WorkflowGraph(1,List.of(capture,profile,human),List.of(new Edge("source-profile","source","profile",null),new Edge("profile-check","profile","check",null)),List.of(new PublicInput("path","源码路径",DataKind.TEXT,true)));
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"单测配置","",graph,CanvasLayout.empty()));var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"配置识别","补齐单测",template.id(),1));id=owner.id();
        plans.confirm(id,new WorkflowRequests.VersionCommand(key(),owner.version()));controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.CONTINUOUS,null,
                Map.of("path",new WorkflowDelivery.Value(DataKind.TEXT,encoding.decode("\"src\"",tools.jackson.databind.JsonNode.class))),null,List.of()));
        dispatch.advance(id);source=nodes.node(id,1,"source").latestAttemptId();sources.advance(source);assertThat(nodes.attempt(source).state()).isEqualTo("SUCCEEDED");dispatch.advance(id);
        attempt=nodes.node(id,1,"profile").latestAttemptId();assertThat(attempt).isNotNull();
    }
    private Node profile(Node p,boolean pause,Map<String,String> parameters){return new Node("profile",p.title(),p.kind(),p.moduleId(),1,null,p.task(),List.of(new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true)),p.outputs(),p.outcomes(),p.completion(),0,pause,parameters);}
    private WorkflowDelivery delivery(){return encoding.decode(nodes.delivery(attempt).contentJson(),WorkflowDelivery.class);}
    private WorkflowTestProfile.Frozen result(){assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");return encoding.decode(encoding.encode(delivery().outputs().get("profile").content()),WorkflowTestProfile.Frozen.class);}
    private void noExternalExecution(){for(String table:List.of("task","workflow_model_launch","workspace_lease","workflow_command_run","source_template_run"))assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-test-profile-").toRealPath();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
