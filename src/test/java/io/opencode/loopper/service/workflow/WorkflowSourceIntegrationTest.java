package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.roles.RolePublishingService;
import io.opencode.loopper.template.*;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.workflow-monitor-enabled=false","loopper.monitor-delay=1h"})
class WorkflowSourceIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("loopper.data-dir",()->DATA.toString());
        p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("source.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowControls controls;
    @Autowired WorkflowDispatchExecution dispatch;
    @Autowired WorkflowSourceExecution execution;
    @Autowired WorkflowSourceStore store;
    @Autowired WorkflowSourceMapper mapper;
    @Autowired WorkflowSourceContent content;
    @Autowired WorkflowSourceFiles sources;
    @Autowired WorkflowCodeFiles files;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowFinishes finishes;
    @Autowired WorkflowFinishDrain drain;
    @Autowired SourceTreeCapture capture;
    @Autowired SourceWorkspaceGuard workspace;
    @Autowired WorkflowEncoding encoding;
    @Autowired WorkflowNodePresets presets;
    @Autowired WorkflowModelExecution modelExecution;
    @Autowired WorkflowModelStore models;
    @Autowired WorkflowModelTools tools;
    @Autowired WorkflowRuntimeSupport identity;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired OpenCodeClient client;
    @Autowired RolePublishingService roles;
    @Autowired RoleConfigurationMapper roleMapper;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    Path root;String project;
    @BeforeEach void prepare() throws Exception {
        flyway.clean();flyway.migrate();root=Files.createDirectory(directory.resolve("project")).toRealPath();
        Files.createDirectory(root.resolve("src"));Files.writeString(root.resolve("src/Main.java"),"class Main { int value() { return 1; } }\n");
        Files.writeString(root.resolve("README.md"),"original context\n");Files.writeString(root.resolve(".env"),"secret example\n");
        project=projects.create("源码资料",root.toString(),"").id();
    }
    @Test void nonGitSourceAndContextStayExactAfterCheckoutChangesAndBindingsRemainScoped() throws Exception {
        String id=create("src","DESIGN");String attempt=start(id);execution.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");assertThat(nodes.hasStop(attempt)).isTrue();
        var binding=files.output(id,"source",attempt,"source");
        Files.writeString(root.resolve("src/Main.java"),"new live content");Files.delete(root.resolve("README.md"));
        assertThat(new String(files.bytes(binding,"src/Main.java"))).contains("return 1");
        assertThat(new String(files.bytes(binding,"README.md"))).contains("original context");
        var all=new ArrayList<WorkflowCodeFiles.File>();String cursor=null;
        do{var page=files.list(binding,cursor,1);all.addAll(page.items());cursor=page.nextCursor();}while(cursor!=null);
        assertThat(all).extracting(WorkflowCodeFiles.File::path).contains(".env","README.md","src/Main.java");
        assertThat(all.stream().filter(f->f.path().equals("src/Main.java")).findFirst().orElseThrow().target()).isTrue();
        assertThat(all.stream().filter(f->f.path().equals("README.md")).findFirst().orElseThrow().target()).isFalse();
        assertThatThrownBy(()->files.bytes(binding,".env")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->files.bytes(binding,"../README.md")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->sources.manifest(project,"another",attempt,binding.sourceReference())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->sources.manifest(project,id,attempt,new WorkflowSourceSnapshot.Reference(1,binding.sourceReference().snapshotId(),"0".repeat(64)))).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_model_launch",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease",Integer.class)).isZero();
    }
    @Test void committedManifestAndBytesRecoverWithoutRecapturingChangedOrDeletedLiveSource() throws Exception {
        String id=create("src","DESIGN");String attempt=start(id);var context=store.context(attempt);var captured=captured(false);
        var row=store.plan(context,captured.manifest());content.write(row.nodeRunId(),captured.contents());
        jdbc.execute("CREATE TRIGGER fail_source_delivery BEFORE INSERT ON workflow_node_delivery BEGIN SELECT RAISE(ABORT,'delivery rollback'); END");
        assertThatThrownBy(()->execution.advance(attempt)).hasStackTraceContaining("delivery rollback");
        assertThat(mapper.find(row.nodeRunId()).orElseThrow().readyAt()).isNull();assertThat(nodes.hasStop(attempt)).isFalse();
        jdbc.execute("DROP TRIGGER fail_source_delivery");Files.delete(root.resolve("src/Main.java"));
        var restarted=new WorkflowSourceExecution(store,capture,content,workspace);
        // The managed bytes path does not consult a live tree or writer admission after the manifest has been fully stored.
        restarted.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");
        assertThat(new String(files.bytes(files.output(id,"source",attempt,"source"),"src/Main.java"))).contains("return 1");
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_source_snapshot SET source_path='other' WHERE node_run_id=?",row.nodeRunId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_source_snapshot WHERE node_run_id=?",row.nodeRunId())).isInstanceOf(RuntimeException.class);
    }
    @Test void partialSnapshotCannotSilentlyAdoptNewSourceAndRetryKeepsItsOriginalManifest() throws Exception {
        String id=create("src","DESIGN");String attempt=start(id);var context=store.context(attempt);var original=captured(false);
        var frozen=store.plan(context,original.manifest());
        Files.writeString(root.resolve("src/Main.java"),"changed source");execution.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("FAILED");assertThat(nodes.delivery(attempt).contentJson()).contains("WORKFLOW_SOURCE_CHANGED").doesNotContain("\"source\":");
        assertThat(mapper.find(frozen.nodeRunId()).orElseThrow().manifestJson()).isEqualTo(frozen.manifestJson());
        Files.write(root.resolve("src/Main.java"),original.contents().get(original.manifest().files().stream().filter(f->f.path().equals("src/Main.java")).findFirst().orElseThrow().sha256()));
        String retry=start(id);assertThat(retry).isNotEqualTo(attempt);execution.advance(retry);
        assertThat(nodes.attempt(retry).state()).isEqualTo("SUCCEEDED");assertThat(nodes.attempt(retry).ordinal()).isEqualTo(2);
        assertThat(files.output(id,"source",retry,"source").sourceReference().sha256()).isEqualTo(frozen.manifestSha256());
        assertThat(nodes.attempt(attempt).state()).isEqualTo("FAILED");
    }
    @Test void unreadableTextTargetIsIncompleteAndCannotBeForgedIntoASuccessfulSourceReference() throws Exception {
        Files.write(root.resolve("src/Broken.java"),new byte[]{(byte)0xff,(byte)0xfe});
        String id=create("src","DESIGN");String attempt=start(id);execution.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("FAILED");
        var report=encoding.decode(nodes.delivery(attempt).contentJson(),WorkflowDelivery.class);
        assertThat(report.outputs()).doesNotContainKey("source");assertThat(report.outputs().get("report").content().path("incompleteCount").asInt()).isEqualTo(1);
        assertThat(report.outputs().get("report").content().path("exclusions").toString()).contains("src/Broken.java");
        var row=mapper.find(nodes.attempt(attempt).nodeRunId()).orElseThrow();assertThat(row.readyAt()).isNull();
        assertThatThrownBy(()->sources.manifest(project,id,attempt,new WorkflowSourceSnapshot.Reference(1,row.nodeRunId(),row.manifestSha256()))).isInstanceOf(ConflictException.class);
    }
    @Test void finishIntentStopsSourceAndLateExecutionCannotPublishOrReviveIt() {
        String id=create("src","DESIGN");String attempt=start(id);var context=store.context(attempt);
        store.plan(context,captured(false).manifest());
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消本次采集"));
        drain.stop(id,attempt);assertThat(finishes.finalizeReady(id)).isTrue();
        store.finish(context,true,null);execution.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("CANCELLED");assertThat(nodes.findDelivery(attempt)).isEmpty();
        assertThat(mapper.find(context.snapshot().nodeRunId()).orElseThrow().readyAt()).isNull();
        assertThat(plans.require(id).state()).isEqualTo("CANCELLED");
    }
    @Test void unitPurposeRetainsExistingTestsAsContextWithoutClaimingThemAsTargets() throws Exception {
        Files.createDirectories(root.resolve("src/test/java"));Files.writeString(root.resolve("src/test/java/MainTest.java"),"class MainTest {}\n");
        String id=create("src","UNIT_TEST");String attempt=start(id);execution.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");
        var binding=files.output(id,"source",attempt,"source");var manifest=sources.manifest(project,id,attempt,binding.sourceReference());
        assertThat(manifest.targetCount()).isEqualTo(1);
        assertThat(manifest.files().stream().filter(f->f.path().endsWith("MainTest.java")).findFirst().orElseThrow().exclusion()).isEqualTo("已有测试作为上下文读取");
        assertThat(new String(files.bytes(binding,"src/test/java/MainTest.java"))).contains("MainTest");
    }
    @Test void traversalInputFailsWithOriginalAttemptAndWithoutProducingManifest() {
        String id=create("../outside","DESIGN");String attempt=start(id);execution.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("FAILED");assertThat(nodes.hasStop(attempt)).isTrue();
        assertThat(mapper.find(nodes.attempt(attempt).nodeRunId()).orElseThrow().manifestJson()).isNull();
        assertThat(nodes.findDelivery(attempt)).isPresent();assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void downstreamModelReadsOnlyItsPinnedSourceThroughTheExistingScopedFileMcp() throws Exception {
        roles.seedBuiltin();var fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        String id=create("src","DESIGN",true);String source=start(id);execution.advance(source);
        Files.writeString(root.resolve("src/Main.java"),"new live bytes");
        controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,"reader",null,
                new OpenCodeClient.OpenCodeModel("fake","test",false),List.of()));dispatch.advance(id);
        String reader=nodes.node(id,1,"reader").latestAttemptId();assertThat(reader).isNotNull();
        for(int i=0;i<4&&!models.require(reader).state().equals("RUNNING");i++)modelExecution.advance(reader);
        var work=(Map<?,?>)call(reader,WorkflowModelProfile.WORK,Map.of());assertThat(work.containsKey("files")).isTrue();
        var text=(WorkflowCodeFiles.Text)call(reader,WorkflowModelProfile.FILE,Map.of("name","source","path","src/Main.java","limit",12));
        assertThat(text.text()).isEqualTo("class Main {");assertThat(text.nextOffset()).isEqualTo(12);
        var rest=(WorkflowCodeFiles.Text)call(reader,WorkflowModelProfile.FILE,Map.of("name","source","path","src/Main.java","offset",12));
        assertThat(rest.text()).contains("return 1");
        assertThat(call(reader,WorkflowModelProfile.FILES,Map.of("name","source","limit",1))).isInstanceOf(io.opencode.loopper.api.CursorPage.class);
        assertThatThrownBy(()->call(reader,WorkflowModelProfile.FILE,Map.of("name","source","path",".env"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->call(reader,WorkflowModelProfile.FILE,Map.of("name","invented","path","src/Main.java"))).isInstanceOf(BadRequestException.class);
        assertThat(nodes.inputs(nodes.attempt(reader)).values().getFirst().attemptId()).isEqualTo(source);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"结束隔离模型读取验证"));
        drain.stop(id,reader);modelExecution.advance(reader);assertThat(finishes.finalizeReady(id)).isTrue();
    }
    private Object call(String attempt,String tool,Map<String,Object> args){return tools.call(tool,Map.of("scope",identity.grant(models.require(attempt)),"attemptId",attempt,"args",args));}
    private SourceTreeCapture.Capture captured(boolean unit){return capture.capture(new SourceTemplateParameters(root.toString(),"src",null,null,null),unit);}
    private String create(String path,String purpose) {
        return create(path,purpose,false);
    }
    private String create(String path,String purpose,boolean reader) {
        var preset=presets.get("source.snapshot",1).node();var parameters=new HashMap<>(preset.parameters());parameters.put("sourcePurpose",purpose);
        var node=new Node("source",preset.title(),preset.kind(),preset.moduleId(),preset.moduleVersion(),null,preset.task(),
                List.of(new Input("path",InputSource.REQUIREMENT,"sourcePath",null,DataKind.TEXT,true)),preset.outputs(),preset.outcomes(),preset.completion(),0,false,parameters);
        var steps=new ArrayList<Node>();steps.add(node);var edges=new ArrayList<Edge>();
        if(reader){steps.add(new Node("reader","源码分析",NodeKind.WORK,WorkflowModelProfile.MODULE,1,"builtin.general","读取固定源码",
                List.of(new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true)),List.of(new Output("result","分析结果",DataKind.TEXT,true)),List.of(),
                new Completion(CompletionKind.DELIVERABLES,"提交分析",null),0,false,Map.of(),roleMapper.latest("builtin.general").revisionId()));edges.add(new Edge("source-reader","source","reader",null));}
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"源码流程","",new WorkflowGraph(1,steps,edges,List.of(new PublicInput("sourcePath","源码路径",DataKind.TEXT,true))),CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"源码需求","读取指定源码",template.id(),1));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));paths.put(owner.id(),path);return owner.id();
    }
    private final Map<String,String> paths=new HashMap<>();
    private String start(String id) {
        controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,"source",
                Map.of("sourcePath",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree(paths.get(id)))),null,List.of()));
        dispatch.advance(id);String attempt=nodes.node(id,1,"source").latestAttemptId();assertThat(attempt).isNotNull();return attempt;
    }
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-source-tests-");}catch(Exception failure){throw new ExceptionInInitializerError(failure);}}
}
