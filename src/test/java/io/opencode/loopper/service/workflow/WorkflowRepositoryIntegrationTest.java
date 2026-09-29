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
import java.time.Duration;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.workflow-monitor-enabled=false","loopper.monitor-delay=1h"})
class WorkflowRepositoryIntegrationTest {
    private static final Path DATA=data();
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("repository.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowControls controls;
    @Autowired WorkflowDispatchExecution dispatch;
    @Autowired WorkflowCommandExecution execution;
    @Autowired WorkflowCommandStore commands;
    @Autowired WorkflowCommandActions commandActions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowNodePresets presets;
    @Autowired WorkflowRepositoryMapper repositories;
    @Autowired WorkflowCodeFiles files;
    @Autowired WorkflowModelExecution modelExecution;
    @Autowired WorkflowModelStore models;
    @Autowired WorkflowModelTools tools;
    @Autowired WorkflowRuntimeSupport identity;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired OpenCodeClient client;
    @Autowired WorkflowFinishes finishes;
    @Autowired WorkflowFinishDrain drain;
    @Autowired RolePublishingService roles;
    @Autowired ProjectService projects;
    @Autowired GitSnapshotJobs jobs;
    @Autowired DurableCommands processes;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    Path root;String project,head;FakeOpenCodeClient fake;
    @BeforeEach void setup()throws Exception {
        flyway.clean();flyway.migrate();roles.seedBuiltin();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        root=Files.createDirectory(directory.resolve("project")).toRealPath();git.read(root,"init","-b","main","--template=");
        git.read(root,"config","user.name","Fixture");git.read(root,"config","user.email","fixture@example.invalid");
        Files.writeString(root.resolve("code.txt"),"original code\n");Files.writeString(root.resolve(".env"),"FIXTURE_ONLY=fake\n");commit();
        head=git.read(root,"rev-parse","HEAD").strip();project=projects.create("固定分支资料",root.toString(),"").id();
    }
    @Test void systemCaptureProducesFixedDocumentForActualDownstreamMcpWithoutWriterOrHiddenModel()throws Exception {
        String id=create();assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_repository_snapshot",Integer.class)).isZero();
        assertThat(fake.createSessionCalls()).isZero();Files.writeString(root.resolve("code.txt"),"dirty code\n");
        String run=start(id,"repository");complete(run);
        assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");assertThat(fake.createSessionCalls()).isZero();
        assertThat(jdbc.queryForObject("SELECT kind FROM workflow_attempt_stop WHERE attempt_id=?",String.class,run)).isEqualTo("COMMAND_TERMINAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease",Integer.class)).isZero();
        var reference=reference(run);var manifest=json.readValue(repositories.find(reference.snapshotId()).orElseThrow().manifestJson(),WorkflowRepositorySnapshot.Manifest.class);
        assertThat(manifest.commitSha()).isEqualTo(head);assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("dirty code\n");
        String consumer=start(id,"analysis");for(int i=0;i<5&&!models.require(consumer).state().equals("RUNNING");i++)modelExecution.advance(consumer);
        var listing=(io.opencode.loopper.api.CursorPage<?>)call(consumer,WorkflowModelProfile.FILES,Map.of("name","source"));
        assertThat(listing.items()).hasSize(2);
        var read=(WorkflowCodeFiles.Text)call(consumer,WorkflowModelProfile.FILE,Map.of("name","source","path","code.txt"));
        assertThat(read.text()).isEqualTo("original code\n");
        assertThatThrownBy(()->call(consumer,WorkflowModelProfile.FILE,Map.of("name","source","path",".env"))).isInstanceOf(BadRequestException.class);
        var delivery=new WorkflowDelivery("已读取固定代码",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("原提交包含实现"))));
        call(consumer,WorkflowModelProfile.SUBMIT,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(consumer).version(),"delivery",delivery));
        fake.setSessionState(nodes.attempt(consumer).externalSessionId(),"COMPLETED");modelExecution.advance(consumer);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void databaseSettlementRollbackResumesOriginalProcessReceiptAndFrozenTree()throws Exception {
        String id=create(),run=start(id,"repository");
        jdbc.execute("CREATE TRIGGER fail_repository_delivery BEFORE INSERT ON workflow_node_delivery BEGIN SELECT RAISE(ABORT,'repository rollback'); END");
        await(run,true);assertThat(commands.require(run).suspended()).isTrue();assertThat(nodes.attempt(run).state()).isEqualTo("RUNNING");
        assertThat(nodes.findDelivery(run)).isEmpty();assertThat(nodes.hasStop(run)).isFalse();
        var row=repositories.find(nodes.attempt(run).nodeRunId()).orElseThrow();assertThat(row.manifestJson()).isNull();
        String registration=commands.require(run).registrationJson();jdbc.execute("DROP TRIGGER fail_repository_delivery");
        Files.writeString(root.resolve("code.txt"),"later commit\n");commit();
        commandActions.resume(id,"repository",run,new WorkflowCommandActions.Command(key(),commands.require(run).version()));complete(run);
        assertThat(commands.require(run).registrationJson()).isEqualTo(registration);
        assertThat(json.readValue(repositories.find(row.nodeRunId()).orElseThrow().manifestJson(),WorkflowRepositorySnapshot.Manifest.class).commitSha()).isEqualTo(head);
        assertThat(files.text(files.output(id,"repository",run,"source"),"code.txt",0,100).text()).isEqualTo("original code\n");
    }
    @Test void stoppedFailedAttemptCanRetrySameNodeWithoutReplacingItsSelectedCommit()throws Exception {
        String id=create(),first=start(id,"repository");execution.advance(first);
        var row=repositories.find(nodes.attempt(first).nodeRunId()).orElseThrow();Path target=jobs.repository(row.nodeRunId());Files.createSymbolicLink(target,root);
        complete(first);assertThat(nodes.attempt(first).state()).isEqualTo("FAILED");assertThat(plans.require(id).state()).isEqualTo("STALLED");
        assertThat(json.readTree(nodes.delivery(first).contentJson()).path("outputs").has("source")).isFalse();
        String input=row.inputJson();Files.delete(target);Files.writeString(root.resolve("code.txt"),"new branch version\n");commit();
        String second=start(id,"repository");complete(second);
        assertThat(second).isNotEqualTo(first);assertThat(nodes.attempt(second).state()).isEqualTo("SUCCEEDED");
        assertThat(repositories.find(row.nodeRunId()).orElseThrow().inputJson()).isEqualTo(input);
        assertThat(json.readValue(repositories.find(row.nodeRunId()).orElseThrow().manifestJson(),WorkflowRepositorySnapshot.Manifest.class).commitSha()).isEqualTo(head);
        assertThat(nodes.attempt(first).state()).isEqualTo("FAILED");assertThat(nodes.hasStop(first)).isTrue();
    }
    @Test void earlyCancellationUsesNoLaunchProofAndRetainsTheUnexecutedSourceIdentity() {
        String id=create(),run=start(id,"repository");
        assertThatThrownBy(()->jdbc.update("INSERT INTO workflow_attempt_stop VALUES(?,'NO_EXTERNAL_WORK',NULL,'{}','t')",run)).isInstanceOf(RuntimeException.class);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消采集"));
        drain.stop(id,run);execution.advance(run);assertThat(finishes.finalizeReady(id)).isTrue();
        assertThat(nodes.attempt(run).state()).isEqualTo("CANCELLED");assertThat(plans.require(id).state()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT kind FROM workflow_attempt_stop WHERE attempt_id=?",String.class,run)).isEqualTo("COMMAND_NOT_LAUNCHED");
        var row=repositories.find(nodes.attempt(run).nodeRunId()).orElseThrow();assertThat(row.manifestJson()).isNull();
        assertThat(jobs.repository(row.nodeRunId()).getParent()).doesNotExist();
    }
    @Test void fixedFileReferencesRejectOtherRequirementsVersionsAndHistoryMutation()throws Exception {
        String id=create(),run=start(id,"repository");complete(run);var reference=reference(run);
        String other=create();var wrong=new WorkflowCodeFiles.Binding(project,other,run,null,null,null,false,null,reference);
        assertThatThrownBy(()->files.list(wrong,null,50)).isInstanceOf(ConflictException.class);
        var changed=new WorkflowRepositorySnapshot.Reference(1,WorkflowRepositorySnapshot.TYPE,reference.snapshotId(),"0".repeat(64));
        var changedBinding=new WorkflowCodeFiles.Binding(project,id,run,null,null,null,false,null,changed);
        assertThatThrownBy(()->files.bytes(changedBinding,"code.txt")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_repository_snapshot SET branch_id='other' WHERE node_run_id=?",reference.snapshotId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_repository_snapshot SET manifest_json=manifest_json WHERE node_run_id=?",reference.snapshotId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_repository_snapshot WHERE node_run_id=?",reference.snapshotId())).isInstanceOf(RuntimeException.class);
    }
    @Test void lateSuccessfulProcessReceiptCannotPublishAfterTheUserCancels()throws Exception {
        String id=create(),run=start(id,"repository");
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(!commands.require(run).state().equals("RUNNING")&&System.nanoTime()<deadline){execution.advance(run);Thread.sleep(25);}
        var row=commands.require(run);assertThat(row.state()).isEqualTo("RUNNING");
        var job=new DurableCommands.Job(run,row.requestSha256());
        processes.grant(job,commands.registration(row));
        while(processes.observe(job).result()==null&&System.nanoTime()<deadline)Thread.sleep(25);
        assertThat(processes.observe(job).result()).isNotNull();assertThat(processes.observe(job).result().successful()).isTrue();
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"停止后续工作"));
        drain.stop(id,run);execution.advance(run);assertThat(finishes.finalizeReady(id)).isTrue();
        assertThat(nodes.attempt(run).state()).isEqualTo("CANCELLED");assertThat(nodes.findDelivery(run)).isEmpty();
        assertThat(repositories.find(nodes.attempt(run).nodeRunId()).orElseThrow().manifestJson()).isNull();
        assertThat(jdbc.queryForObject("SELECT kind FROM workflow_attempt_stop WHERE attempt_id=?",String.class,run)).isEqualTo("COMMAND_TERMINAL");
    }
    private String create() {
        var preset=presets.get("repository.snapshot",1).node();
        var source=new Node("repository",preset.title(),preset.kind(),preset.moduleId(),1,null,preset.task(),
                List.of(new Input("branch",InputSource.REQUIREMENT,"branch",null,DataKind.TEXT,true)),
                preset.outputs(),preset.outcomes(),preset.completion(),0,false,preset.parameters());
        var original=presets.get("analysis.read",1).node();
        var analysis=new Node("analysis",original.title(),original.kind(),original.moduleId(),original.moduleVersion(),original.roleId(),original.task(),
                List.of(new Input("source",InputSource.NODE,"repository","source",DataKind.DOCUMENT,true)),original.outputs(),original.outcomes(),original.completion(),0,false,original.parameters(),original.roleRevisionId());
        var graph=new WorkflowGraph(1,List.of(source,analysis),List.of(new Edge("repository-analysis","repository","analysis",null)),List.of(new PublicInput("branch","代码分支",DataKind.TEXT,true)));
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"固定分支分析","",graph,CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"审查代码","对照固定资料",template.id(),1));plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private String start(String id,String node) {
        controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,node,
                Map.of("branch",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("local:refs/heads/main"))),MODEL,List.of()));dispatch.advance(id);
        String run=nodes.node(id,plans.require(id).headRevision(),node).latestAttemptId();assertThat(run).as(controls.get(id).reasonCode()).isNotNull();return run;
    }
    private void complete(String run)throws Exception {await(run,false);assertThat(commands.require(run).suspended()).as(commands.require(run).lastErrorCode()).isFalse();}
    private void await(String run,boolean suspension)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<deadline) {execution.advance(run);if(WorkflowAttemptState.valueOf(nodes.attempt(run).state()).terminal()||commands.require(run).suspended())return;Thread.sleep(25);}
        fail("Capture did not settle: "+commands.require(run).state());
    }
    private WorkflowRepositorySnapshot.Reference reference(String run){return json.treeToValue(json.readTree(nodes.delivery(run).contentJson()).path("outputs").path("source").path("content"),WorkflowRepositorySnapshot.Reference.class);}
    private Object call(String run,String tool,Map<String,Object> args){return tools.call(tool,Map.of("scope",identity.grant(models.require(run)),"attemptId",run,"args",args));}
    private void commit(){git.read(root,"add",".");git.read(root,"commit","-m","fixture");}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-repository-tests-");}catch(Exception invalid){throw new ExceptionInInitializerError(invalid);}}
}
