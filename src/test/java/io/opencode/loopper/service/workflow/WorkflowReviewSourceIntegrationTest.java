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
class WorkflowReviewSourceIntegrationTest {
    private static final Path DATA=data();
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("review-source.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
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
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean WorkflowReviewMapper repositories;
    @Autowired org.mybatis.spring.SqlSessionTemplate sql;
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
    @Autowired GitReviewJobs jobs;
    @Autowired DurableCommands processes;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    Path root;String project,head;FakeOpenCodeClient fake;
    final Map<String,Map<String,WorkflowDelivery.Value>> inputs=new HashMap<>();
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
        String id=create();assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_review_source",Integer.class)).isZero();
        assertThat(fake.createSessionCalls()).isZero();Files.writeString(root.resolve("code.txt"),"dirty code\n");
        String run=start(id,"repository");complete(run);
        assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");assertThat(fake.createSessionCalls()).isZero();
        assertThat(jdbc.queryForObject("SELECT kind FROM workflow_attempt_stop WHERE attempt_id=?",String.class,run)).isEqualTo("COMMAND_TERMINAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease",Integer.class)).isZero();
        var reference=reference(run);var manifest=json.readValue(repositories.find(reference.snapshotId()).orElseThrow().manifestJson(),WorkflowReviewSource.Manifest.class);
        assertThat(manifest.sourceSha()).isEqualTo(head);assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("dirty code\n");
        String consumer=start(id,"analysis");for(int i=0;i<5&&!models.require(consumer).state().equals("RUNNING");i++)modelExecution.advance(consumer);
        var listing=(io.opencode.loopper.api.CursorPage<?>)call(consumer,WorkflowModelProfile.FILES,Map.of("name","source"));
        assertThat(listing.items()).hasSize(3);
        var read=(WorkflowCodeFiles.Text)call(consumer,WorkflowModelProfile.FILE,Map.of("name","source","path",commitPath()));
        assertThat(read.text()).contains("original code").doesNotContain("FIXTURE_ONLY=fake");
        assertThatThrownBy(()->call(consumer,WorkflowModelProfile.FILE,Map.of("name","source","path",".env"))).isInstanceOf(NotFoundException.class);
        var delivery=new WorkflowDelivery("已读取固定代码",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("原提交包含实现"))));
        call(consumer,WorkflowModelProfile.SUBMIT,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(consumer).version(),"delivery",delivery));
        fake.setSessionState(nodes.attempt(consumer).externalSessionId(),"COMPLETED");modelExecution.advance(consumer);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        assertThatThrownBy(()->call(consumer,WorkflowModelProfile.FILES,Map.of("name","source"))).isInstanceOf(RuntimeException.class);
    }
    @Test void databaseSettlementRollbackResumesOriginalProcessReceiptAndFrozenTree()throws Exception {
        String id=create(),run=start(id,"repository");
        var written=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call->{
            int rows=sql.getMapper(WorkflowReviewMapper.class).manifest(call.getArgument(0,String.class),call.getArgument(1,String.class),call.getArgument(2,String.class));
            assertThat(rows).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT manifest_json FROM workflow_review_source WHERE node_run_id=?",String.class,call.getArgument(0,String.class))).isNotNull();
            written.set(true);return rows;
        }).when(repositories).manifest(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        jdbc.execute("CREATE TRIGGER fail_repository_delivery BEFORE INSERT ON workflow_node_delivery BEGIN SELECT RAISE(ABORT,'repository rollback'); END");
        await(run,true);assertThat(written).isTrue();assertThat(commands.require(run).suspended()).isTrue();assertThat(nodes.attempt(run).state()).isEqualTo("RUNNING");
        assertThat(nodes.findDelivery(run)).isEmpty();assertThat(nodes.hasStop(run)).isFalse();
        var row=repositories.find(nodes.attempt(run).nodeRunId()).orElseThrow();assertThat(row.manifestJson()).isNull();
        String registration=commands.require(run).registrationJson();jdbc.execute("DROP TRIGGER fail_repository_delivery");
        Files.writeString(root.resolve("code.txt"),"later commit\n");commit();
        commandActions.resume(id,"repository",run,new WorkflowCommandActions.Command(key(),commands.require(run).version()));complete(run);
        assertThat(commands.require(run).registrationJson()).isEqualTo(registration);
        assertThat(json.readValue(repositories.find(row.nodeRunId()).orElseThrow().manifestJson(),WorkflowReviewSource.Manifest.class).sourceSha()).isEqualTo(head);
        assertThat(files.text(files.output(id,"repository",run,"source"),commitPath(),0,12000).text()).contains("original code").doesNotContain("later commit");
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
        assertThat(json.readValue(repositories.find(row.nodeRunId()).orElseThrow().manifestJson(),WorkflowReviewSource.Manifest.class).sourceSha()).isEqualTo(head);
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
        String other=create();var wrong=new WorkflowCodeFiles.Binding(project,other,run,null,null,null,false,null,null,null,reference);
        assertThatThrownBy(()->files.list(wrong,null,50)).isInstanceOf(ConflictException.class);
        var changed=new WorkflowReviewSource.Reference(1,WorkflowReviewSource.TYPE,reference.snapshotId(),"0".repeat(64));
        var changedBinding=new WorkflowCodeFiles.Binding(project,id,run,null,null,null,false,null,null,null,changed);
        assertThatThrownBy(()->files.bytes(changedBinding,"code.txt")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_review_source SET branch_id='other' WHERE node_run_id=?",reference.snapshotId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_review_source SET manifest_json=manifest_json WHERE node_run_id=?",reference.snapshotId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_review_source WHERE node_run_id=?",reference.snapshotId())).isInstanceOf(RuntimeException.class);
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
    @Test void completedDocumentsSurviveBothOriginalAndPrivateGitLossAndRejectChangedStoredBytes()throws Exception {
        String id=create(),run=start(id,"repository");complete(run);var reference=reference(run);
        var manifest=json.readValue(repositories.find(reference.snapshotId()).orElseThrow().manifestJson(),WorkflowReviewSource.Manifest.class);
        var bound=files.output(id,"repository",run,"source");var first=files.list(bound,null,1);assertThat(first.items()).hasSize(1);assertThat(first.nextCursor()).isNotNull();
        assertThat(files.list(bound,first.nextCursor(),1).items()).hasSize(1);
        Files.move(root,root.resolveSibling("offline-source"));Files.move(jobs.repository(reference.snapshotId()),jobs.repository(reference.snapshotId()).resolveSibling("offline-history"));
        assertThat(files.text(bound,commitPath(),0,12000).text()).contains("original code");
        var commit=manifest.files().stream().filter(f->f.path().equals(commitPath())).findFirst().orElseThrow();
        Path object=DATA.toRealPath().resolve("workflow-review-content").resolve(reference.snapshotId()).resolve("objects").resolve(commit.sha256());
        Files.writeString(object,"changed saved bytes");assertThatThrownBy(()->files.bytes(bound,commitPath())).isInstanceOf(ImmutableContentStore.StorageFailure.class);
    }
    @Test void incrementalNodeFreezesFinalBoundaryVersionsAndRetainsNoChangeEvidence()throws Exception {
        String baseline=head;
        Files.writeString(root.resolve("code.txt"),"changed at selected boundary\n");git.read(root,"add",".");
        git.run(root,Duration.ofSeconds(10),List.of("commit","-m","selected"),Map.of("GIT_AUTHOR_DATE","2026-09-12T00:00:00Z","GIT_COMMITTER_DATE","2026-09-12T00:00:00Z")).requireSuccess(List.of("commit"));
        String target=git.read(root,"rev-parse","HEAD").strip();String id=create(false),run=start(id,"repository");complete(run);
        var manifest=json.readValue(repositories.find(reference(run).snapshotId()).orElseThrow().manifestJson(),WorkflowReviewSource.Manifest.class);
        assertThat(manifest.mode()).isEqualTo(io.opencode.loopper.template.SnapshotReview.Mode.DATE_INCREMENTAL);
        assertThat(manifest.baselineSha()).isEqualTo(baseline);assertThat(manifest.targetSha()).isEqualTo(target);assertThat(manifest.noChanges()).isFalse();assertThat(manifest.unitCount()).isEqualTo(1);
        assertThat(files.text(files.output(id,"repository",run,"source"),"units/000001.json",0,12000).text()).contains("original code","changed at selected boundary",baseline,target);
        String same=create(false);inputs.get(same).put("startDate",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("2026-09-13")));inputs.get(same).put("endDate",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("2026-09-13")));
        String second=start(same,"repository");complete(second);var unchanged=json.readValue(repositories.find(reference(second).snapshotId()).orElseThrow().manifestJson(),WorkflowReviewSource.Manifest.class);
        assertThat(unchanged.noChanges()).isTrue();assertThat(unchanged.unitCount()).isZero();assertThat(unchanged.files()).singleElement().satisfies(f->assertThat(f.path()).isEqualTo("overview.json"));assertThat(fake.createSessionCalls()).isZero();
    }
    @Test void invalidDateRangeIsRejectedBeforeAttemptOrCaptureIntent() {
        String id=create(false);inputs.get(id).put("startDate",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("2026-09-13")));
        controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,"repository",inputs.get(id),MODEL,List.of()));dispatch.advance(id);
        assertThat(nodes.node(id,plans.require(id).headRevision(),"repository").latestAttemptId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_review_source",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_command_run",Integer.class)).isZero();assertThat(fake.createSessionCalls()).isZero();
        assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_REVIEW_SOURCE_PARAMETERS");
    }
    private String create() { return create(true); }
    private String create(boolean full) {
        var preset=presets.get(full?"review.snapshot-full":"review.snapshot",1).node();
        var bindings=new ArrayList<Input>();bindings.add(new Input("branch",InputSource.REQUIREMENT,"branch",null,DataKind.TEXT,true));
        var publicInputs=new ArrayList<PublicInput>();publicInputs.add(new PublicInput("branch","代码分支",DataKind.TEXT,true));
        var values=new LinkedHashMap<String,WorkflowDelivery.Value>();values.put("branch",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("local:refs/heads/main")));
        if(!full)for(String name:List.of("startDate","endDate")){bindings.add(new Input(name,InputSource.REQUIREMENT,name,null,DataKind.TEXT,true));publicInputs.add(new PublicInput(name,name,DataKind.TEXT,true));values.put(name,new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("2026-09-12")));}
        var source=new Node("repository",preset.title(),preset.kind(),preset.moduleId(),1,null,preset.task(),
                bindings,
                preset.outputs(),preset.outcomes(),preset.completion(),0,false,preset.parameters());
        var original=presets.get("analysis.read",1).node();
        var analysis=new Node("analysis",original.title(),original.kind(),original.moduleId(),original.moduleVersion(),original.roleId(),original.task(),
                List.of(new Input("source",InputSource.NODE,"repository","source",DataKind.DOCUMENT,true)),original.outputs(),original.outcomes(),original.completion(),0,false,original.parameters(),original.roleRevisionId());
        var graph=new WorkflowGraph(1,List.of(source,analysis),List.of(new Edge("repository-analysis","repository","analysis",null)),publicInputs);
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"固定分支分析","",graph,CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"审查代码","对照固定资料",template.id(),1));plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));inputs.put(owner.id(),values);return owner.id();
    }
    private String start(String id,String node) {
        controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,node,
                inputs.get(id),MODEL,List.of()));dispatch.advance(id);
        String run=nodes.node(id,plans.require(id).headRevision(),node).latestAttemptId();assertThat(run).as(controls.get(id).reasonCode()).isNotNull();return run;
    }
    private void complete(String run)throws Exception {await(run,false);assertThat(commands.require(run).suspended()).as(commands.require(run).lastErrorCode()).isFalse();}
    private void await(String run,boolean suspension)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<deadline) {execution.advance(run);if(WorkflowAttemptState.valueOf(nodes.attempt(run).state()).terminal()||commands.require(run).suspended())return;Thread.sleep(25);}
        fail("Capture did not settle: "+commands.require(run).state());
    }
    private WorkflowReviewSource.Reference reference(String run){return json.treeToValue(json.readTree(nodes.delivery(run).contentJson()).path("outputs").path("source").path("content"),WorkflowReviewSource.Reference.class);}
    private Object call(String run,String tool,Map<String,Object> args){return tools.call(tool,Map.of("scope",identity.grant(models.require(run)),"attemptId",run,"args",args));}
    private String commitPath(){return "units/000002.json";}
    private void commit(){git.read(root,"add",".");git.run(root,Duration.ofSeconds(10),List.of("commit","-m","fixture"),Map.of("GIT_AUTHOR_DATE","2026-09-11T01:00:00Z","GIT_COMMITTER_DATE","2026-09-11T01:00:00Z")).requireSuccess(List.of("commit"));}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-review-source-tests-");}catch(Exception invalid){throw new ExceptionInInitializerError(invalid);}}
}
