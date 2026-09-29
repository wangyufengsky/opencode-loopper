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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.workflow-monitor-enabled=false","loopper.monitor-delay=1h"})
class WorkflowTestWriteIntegrationTest {
    private static final Path DATA=data();
    private static final String SOURCE="def total(a, b):\n    return a + b\n";
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("design.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowBuiltinFlows builtins;
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
    @Autowired WorkflowNodeActions actions;
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
    @Autowired WorkflowTestScopeStore scopeStore;
    @Autowired WorkflowWriterLeases leases;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired WorkflowWorkspaceStore workspaceStore;
    @Autowired WorkflowCodeSnapshots codes;
    @Autowired WorkflowCodeFiles files;
    @Autowired LoopperMapper mapper;
    @TempDir Path directory;
    Path root;String project,id,source,profile,attempt,design;FakeOpenCodeClient fake;
    @BeforeEach void prepare()throws Exception {
        flyway.clean();flyway.migrate();roles.seedBuiltin();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_WRITE,true);
        root=Files.createDirectory(directory.resolve("project"));Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("src/calculator.py"),SOURCE);Files.writeString(root.resolve("pytest.ini"),"[pytest]\ntestpaths = tests\n");
        Files.createDirectory(root.resolve("tests"));Files.writeString(root.resolve("tests/test_existing.py"),"def test_existing():\n    assert 1 == 1\n");
        project=projects.create("单测设计",root.toString(),"").id();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void fixedInputsAndOwnReadsProduceStoppedScopeProofAndPinnedCodeThenSeedNextWriter(boolean git)throws Exception {
        if(git)initGit();
        create();attempt=start("write");assertThat(models.plan(models.require(attempt)).profile()).isEqualTo(OpenCodeClient.SessionProfile.WORKFLOW_WRITE);
        assertThat(((Map<?,?>)call(WorkflowModelProfile.WORK,Map.of())).containsKey("testWrite")).isTrue();
        assertThatThrownBy(this::writerSubmit).hasMessageContaining("完整读取固定测试配置和场景设计");
        input("profile");input("design");assertThatThrownBy(this::writerSubmit).hasMessageContaining("完整读取");read(1,200);writeTest();writerSubmit();
        assertThat(scopeStore.result(attempt)).isEmpty();assertThat(nodes.findDelivery(attempt)).isEmpty();finish();
        assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");var proof=scopeStore.result(attempt).orElseThrow();assertThat(proof.passed()).isTrue();assertThat(proof.reportJson()).contains("\"testsExecuted\":false");
        assertThat(new String(codes.read(project,id,attempt,reference(),"tests/test_added.py"))).contains("assert 1 + 2 == 3");assertRestored();
        String first=attempt;attempt=start("next");assertThat(Files.readString(root.resolve("tests/test_added.py"))).contains("assert 1 + 2 == 3");writerRead();
        Files.writeString(root.resolve("tests/test_second.py"),"def test_second():\n    assert 2 + 2 == 4\n");writerSubmit();finish();assertRestored();
        assertThat(root.resolve("tests/test_second.py")).doesNotExist();assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");
        assertThat(codes.manifest(project,id,attempt,reference()).files()).extracting(f->f.path()).contains("tests/test_added.py","tests/test_second.py");
        assertThat(nodes.inputs(nodes.attempt(attempt)).values().stream().filter(v->v.name().equals("previous")).findFirst().orElseThrow().attemptId()).isEqualTo(first);
        if(!git)assertThat(root.resolve(".git")).doesNotExist();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @ParameterizedTest @CsvSource({"production,false","delete,false","weaken,false","disabled,false","production,true","delete,true","weaken,true","disabled,true"})
    void forbiddenChangesFailButRetainInspectableCodeWithoutBecomingDownstreamInput(String mutation,boolean git)throws Exception {
        if(git)initGit();create();attempt=start("write");writerRead();writeTest();
        switch(mutation){case "production"->Files.writeString(root.resolve("src/calculator.py"),"def total(a,b): return 0\n");case "delete"->Files.delete(root.resolve("tests/test_existing.py"));case "weaken"->Files.writeString(root.resolve("tests/test_existing.py"),"def test_existing():\n    pass\n");case "disabled"->Files.writeString(root.resolve("tests/test_added.py"),"import pytest\n@pytest.mark.skip\ndef test_added(): pass\n");}
        writerSubmit();finish();assertThat(nodes.attempt(attempt).state()).isEqualTo("FAILED");assertThat(scopeStore.result(attempt).orElseThrow().passed()).isFalse();assertThat(actions.result(id,"write",attempt).state()).isEqualTo("FAILED");assertRestored();
        assertThat(files.text(files.output(id,"write",attempt,"code"),"tests/test_added.py",0,12000).text()).contains("test_added");
        assertThatThrownBy(()->codes.manifest(project,id,attempt,reference())).hasMessageContaining("不能作为后续输入");
        assertThatThrownBy(()->files.output(id,"next",attempt,"code")).isInstanceOf(RuntimeException.class);
        dispatch.advance(id);assertThat(nodes.node(id,1,"next").latestAttemptId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease WHERE state<>'RELEASED'",Integer.class)).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void ignoredChangesCannotEscapePrivateBaselineOrPretendToBeDelivered(boolean validTest)throws Exception {
        Files.writeString(root.resolve(".gitignore"),"private.txt\ntests/test_ignored.py\n");Files.writeString(root.resolve("private.txt"),"private baseline");initGit();create();attempt=start("write");writerRead();
        if(validTest)Files.writeString(root.resolve("tests/test_ignored.py"),"def test_ignored(): assert 1 == 1\n");else Files.writeString(root.resolve("private.txt"),"private changed");
        writerSubmit();finish();assertThat(nodes.attempt(attempt).state()).isEqualTo("FAILED");assertThat(scopeStore.result(attempt).orElseThrow().passed()).isFalse();
        assertThat(delivery().outputs().get("scope").content().path("message").asString()).contains(validTest?"未进入固定代码交付":"不属于冻结测试源码或夹具范围");
        assertThat(codes.outputManifest(project,id,attempt,reference()).files()).extracting(f->f.path()).doesNotContain("private.txt","tests/test_ignored.py");
        assertRestored(); // Ignored files are retained; never silently delete them to make restoration look clean.
        assertThat(Files.readString(root.resolve("private.txt"))).isEqualTo(validTest?"private baseline":"private changed");
    }
    @Test void retryAfterScopeFailureKeepsFailedHistoryAndOnlyReleasesSuccessfulCode()throws Exception {
        create();attempt=start("write");writerRead();Files.writeString(root.resolve("src/calculator.py"),"wrong production change\n");writerSubmit();finish();
        String failed=attempt;var failedDelivery=nodes.delivery(failed);assertThat(nodes.attempt(failed).state()).isEqualTo("FAILED");
        attempt=start("write");assertThat(attempt).isNotEqualTo(failed);writerRead();writeTest();writerSubmit();finish();String successful=attempt;
        assertThat(nodes.attempt(successful).state()).isEqualTo("SUCCEEDED");assertThat(nodes.delivery(failed)).isEqualTo(failedDelivery);assertThat(nodes.attempt(failed).state()).isEqualTo("FAILED");
        attempt=start("next");assertThat(nodes.inputs(nodes.attempt(attempt)).values().stream().filter(v->v.name().equals("previous")).findFirst().orElseThrow().attemptId()).isEqualTo(successful);
        models.stop(attempt);execution.advance(attempt);assertRestored();
    }
    @Test void writerRejectsTamperedDesignLineageEvenWhenContentIsUnchanged() {
        create();attempt=start("write");var values=nodes.inputs(nodes.attempt(attempt));var context=testInputs.require(values);
        for(String field:List.of("producer","source")) {
            var changed=values.values().stream().map(v->!v.name().equals("design")?v:new WorkflowDelivery.Input(v.name(),v.kind(),v.source(),field.equals("source")?"other-node":v.sourceId(),v.outputName(),field.equals("producer")?"other-attempt":v.attemptId(),v.sha256(),v.content())).toList();
            assertThatThrownBy(()->testInputs.design(copy(values,changed),context)).hasMessageContaining("完全一致");
        }
        models.stop(attempt);execution.advance(attempt);assertThat(nodes.attempt(attempt).state()).isEqualTo("CANCELLED");
    }
    @Test void productionModeChangeCannotPassBecauseItsBytesAreUnchanged()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        initGit();create();attempt=start("write");writerRead();Path sourceFile=root.resolve("src/calculator.py");
        var permissions=new HashSet<>(Files.getPosixFilePermissions(sourceFile));permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);Files.setPosixFilePermissions(sourceFile,permissions);
        writerSubmit();finish();assertThat(nodes.attempt(attempt).state()).isEqualTo("FAILED");assertThat(scopeStore.result(attempt).orElseThrow().reportJson()).contains("src/calculator.py");
        assertThat(Files.isExecutable(sourceFile)).isFalse();assertRestored();
    }
    @Test void modelCannotForgeProgramScopeAndCanCorrectCandidateWithoutRestart()throws Exception {
        create();attempt=start("write");writerRead();var forged=new LinkedHashMap<>(writerResult().outputs());forged.put("scope",new WorkflowDelivery.Value(DataKind.JSON,encoding.decode("{\"passed\":true}",tools.jackson.databind.JsonNode.class)));
        assertThatThrownBy(()->call(WorkflowModelProfile.SUBMIT,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",new WorkflowDelivery("fake",null,forged)))).hasMessageContaining("范围检查由程序");
        writerSubmit();finish();assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");assertRestored();
    }
    @Test void sourceDriftBeforeModelCreationRetainsUserChangesAndCanCancel()throws Exception {
        create();Files.writeString(root.resolve("src/calculator.py"),"user changed source\n");attempt=admit("write");int created=fake.createSessionCalls()+fake.createReadOnlySessionCalls();
        assertThatThrownBy(()->execution.advance(attempt)).hasMessageContaining("工作区与本次固定源码");assertThat(models.require(attempt).creationPlanJson()).isNull();assertThat(scopeStore.find(attempt)).isEmpty();
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isEqualTo(created);models.stop(attempt);execution.advance(attempt);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("CANCELLED");assertThat(Files.readString(root.resolve("src/calculator.py"))).isEqualTo("user changed source\n");
    }
    @Test void fixedBaselineDetectsChangesBeforeDispatchAndOriginalObjectDamageBeforeCreation()throws Exception {
        create();attempt=admit("write");execution.advance(attempt);assertThat(models.require(attempt).state()).isEqualTo("CREATING");
        var baseline=scopeStore.files(scopeStore.find(attempt).orElseThrow().filesJson());var old=baseline.get("tests/test_existing.py");Path object=DATA.resolve("workflow-test-originals").resolve(attempt).resolve("objects").resolve(old.sha256());byte[] bytes=Files.readAllBytes(object);Files.writeString(object,"corrupt");
        int created=fake.createSessionCalls()+fake.createReadOnlySessionCalls();assertThatThrownBy(()->execution.advance(attempt)).isInstanceOf(RuntimeException.class);assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isEqualTo(created);
        Files.write(object,bytes);execution.advance(attempt);assertThat(models.require(attempt).state()).isEqualTo("DISPATCHING");Files.writeString(root.resolve("tests/test_existing.py"),"changed before dispatch\n");
        assertThatThrownBy(()->execution.advance(attempt)).isInstanceOf(ConflictException.class);models.stop(attempt);execution.advance(attempt);assertThat(nodes.attempt(attempt).state()).isEqualTo("CANCELLED");assertRestored();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void finalTransactionFailureReusesFixedScopeAfterWorkspaceRestoration(boolean git)throws Exception {
        if(git)initGit();create();attempt=start("write");writerRead();writeTest();writerSubmit();
        jdbc.execute("CREATE TRIGGER fail_test_writer_delivery BEFORE INSERT ON workflow_node_delivery WHEN NEW.attempt_id='"+attempt+"' BEGIN SELECT RAISE(ABORT,'writer final rollback'); END");
        assertThatThrownBy(this::finish).hasStackTraceContaining("writer final rollback");var proof=scopeStore.result(attempt).orElseThrow();assertThat(nodes.findDelivery(attempt)).isEmpty();assertThat(workspaceStore.require(attempt).state()).isEqualTo("RESTORED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease WHERE holder_workflow_attempt_id=? AND state<>'RELEASED'",Integer.class,attempt)).isEqualTo(1);
        jdbc.execute("DROP TRIGGER fail_test_writer_delivery");execution.advance(attempt);assertThat(scopeStore.result(attempt).orElseThrow()).isEqualTo(proof);assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");assertRestored();
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_test_scope WHERE attempt_id=?",attempt)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_test_baseline SET sha256=lower(hex(randomblob(32))) WHERE attempt_id=?",attempt)).isInstanceOf(RuntimeException.class);
    }
    @Test void frozenV1ScopeBaselineDeliveryAndCodeRemainUsableWithTheCurrentCatalog()throws Exception {
        initGit();create();attempt=start("write");writerRead();writeTest();writerSubmit();finish();
        var baseline=scopeStore.find(attempt).orElseThrow();var proof=scopeStore.result(attempt).orElseThrow();var accepted=nodes.delivery(attempt);var workspace=workspaceStore.require(attempt);var code=reference();
        // SQL upgrades are covered by WorkflowControlMigrationTest. Publishing the current catalog
        // must also leave this real V1 writer's frozen evidence and readable delivery unchanged.
        builtins.publish();flyway.validate();
        assertThat(scopeStore.find(attempt).orElseThrow()).isEqualTo(baseline);assertThat(scopeStore.result(attempt).orElseThrow()).isEqualTo(proof);assertThat(nodes.delivery(attempt)).isEqualTo(accepted);assertThat(workspaceStore.require(attempt)).isEqualTo(workspace);
        assertThat(new String(codes.read(project,id,attempt,code,"tests/test_added.py"))).contains("assert 1 + 2 == 3");assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();assertRestored();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    @Test void stoppedWriterWithPendingLeaseCanVerifyAndReleaseTheSameAttempt()throws Exception {
        initGit();create();attempt=start("write");writerRead();writeTest();writerSubmit();
        var identity=DirectWorkspaceLeaseCoordinator.identify(root);transactions.executeWithoutResult(tx->leases.markUnconfirmed(identity,attempt));
        assertThat(mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().state()).isEqualTo("RELEASE_PENDING");
        finish();assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");assertThat(scopeStore.result(attempt).orElseThrow().passed()).isTrue();assertRestored();
    }
    @Test void unknownStopDoesNotPublishScopeOrReleaseLeaseAndCancellationRestoresFiles()throws Exception {
        create();attempt=start("write");writerRead();writeTest();writerSubmit();models.stop(attempt);fake.failNextAborts(1);
        assertThatThrownBy(()->execution.advance(attempt)).isInstanceOf(RuntimeException.class);assertThat(nodes.hasStop(attempt)).isFalse();assertThat(scopeStore.result(attempt)).isEmpty();assertThat(nodes.findDelivery(attempt)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease WHERE holder_workflow_attempt_id=? AND state<>'RELEASED'",Integer.class,attempt)).isEqualTo(1);
        execution.advance(attempt);assertThat(nodes.attempt(attempt).state()).isEqualTo("CANCELLED");assertRestored();assertThat(workspaceStore.require(attempt).checkpointTree()).isNotNull();
    }
    private void create() {
        var capture=preset("source.snapshot","source",List.of(new Input("path",InputSource.REQUIREMENT,"path",null,DataKind.TEXT,true)));
        capture=new Node(capture.id(),capture.title(),capture.kind(),capture.moduleId(),1,null,capture.task(),capture.inputs(),capture.outputs(),capture.outcomes(),capture.completion(),0,false,Map.of("sourcePurpose","UNIT_TEST"));
        var input=new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true);
        var profileNode=preset("source.test-profile","profile",List.of(input));
        var design=preset("source.test-design","design",List.of(input,new Input("profile",InputSource.NODE,"profile","profile",DataKind.JSON,true)));
        var writerInputs=List.of(input,new Input("profile",InputSource.NODE,"profile","profile",DataKind.JSON,true),new Input("design",InputSource.NODE,"design","design",DataKind.JSON,true));
        var writer=preset("source.test-write","write",writerInputs);var seeded=new ArrayList<>(writerInputs);seeded.add(new Input("previous",InputSource.NODE,"write","code",DataKind.CODE,true));
        var next=preset("source.test-write","next",seeded);
        var graph=new WorkflowGraph(1,List.of(capture,profileNode,design,writer,next),List.of(new Edge("sp","source","profile",null),new Edge("pd","profile","design",null),new Edge("dw","design","write",null),new Edge("wn","write","next",null)),List.of(new PublicInput("path","源码路径",DataKind.TEXT,true)));
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"单测场景流程","",graph,CanvasLayout.empty()));var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"单测场景","补齐单测",template.id(),1));id=owner.id();plans.confirm(id,new WorkflowRequests.VersionCommand(key(),owner.version()));
        source=start("source");sources.advance(source);profile=start("profile");profiles.advance(profile);assertThat(nodes.attempt(profile).state()).isEqualTo("SUCCEEDED");
        attempt=start("design");input();read(1,200);submit(candidate());finish();this.design=attempt;
    }
    private Node preset(String preset,String id,List<Input> inputs){var n=presets.get(preset,1).node();return new Node(id,n.title(),n.kind(),n.moduleId(),1,n.roleId(),n.task(),inputs,n.outputs(),n.outcomes(),n.completion(),0,n.pauseAfter(),n.parameters(),n.roleRevisionId());}
    private void requestStart(String node){controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,node,Map.of("path",new WorkflowDelivery.Value(DataKind.TEXT,encoding.decode("\"src\"",tools.jackson.databind.JsonNode.class))),MODEL,controls.get(id).checkpoints().stream().map(c->c.attemptId()).toList()));}
    private String admit(String node){requestStart(node);dispatch.advance(id);String run=nodes.node(id,1,node).latestAttemptId();assertThat(run).as(controls.get(id).reasonCode()).isNotNull();return run;}
    private String start(String node){String run=admit(node);if(WorkflowModelProfile.ADAPTER.equals(nodes.attempt(run).adapterKey())||WorkflowWriterLeases.ADAPTER.equals(nodes.attempt(run).adapterKey()))running(run);return run;}
    private void running(String run){for(int i=0;i<4&&!models.require(run).state().equals("RUNNING");i++)execution.advance(run);assertThat(models.require(run).state()).isEqualTo("RUNNING");}
    private WorkflowSourceReads.SourceText read(int start,int limit){return (WorkflowSourceReads.SourceText)call(WorkflowModelProfile.FILE,Map.of("name","source","path","src/calculator.py","sha256",hash(),"startLine",start,"lineCount",limit));}
    private void input(){input("profile");}
    private void input(String name){int offset=0;while(true){var page=(Map<?,?>)call(WorkflowModelProfile.INPUT,Map.of("name",name,"offset",offset));if(page.get("nextOffset")==null)return;offset=((Number)page.get("nextOffset")).intValue();}}
    private WorkflowTestDesign.Candidate candidate(){return new WorkflowTestDesign.Candidate("加法场景","验证两个整数求和",List.of(new WorkflowTestDesign.Scenario("sum","src/calculator.py","NORMAL","两个正数求和",List.of("调用 total(1, 2)"),"返回 3",List.of(new SourceDesign.Reference("src/calculator.py",hash(),1,1,"def total(a, b):")))),List.of());}
    private WorkflowDelivery result(Object value){return new WorkflowDelivery("已设计场景，测试尚未执行",null,Map.of("design",new WorkflowDelivery.Value(DataKind.JSON,encoding.decode(encoding.encode(value),tools.jackson.databind.JsonNode.class)),"summary",new WorkflowDelivery.Value(DataKind.TEXT,encoding.decode("\"场景设计\"",tools.jackson.databind.JsonNode.class))));}
    private Map<String,Object> submission(WorkflowTestDesign.Candidate value){return Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",result(value));}
    private Object submit(WorkflowTestDesign.Candidate value){return call(WorkflowModelProfile.SUBMIT,submission(value));}
    private Object call(String tool,Map<String,Object> args){return tools.call(tool,Map.of("scope",identity.grant(models.require(attempt)),"attemptId",attempt,"args",args));}
    private void finish(){fake.setSessionState(nodes.attempt(attempt).externalSessionId(),"COMPLETED");execution.advance(attempt);}
    private WorkflowDelivery delivery(){return encoding.decode(nodes.delivery(attempt).contentJson(),WorkflowDelivery.class);}
    private static WorkflowDelivery.Inputs copy(WorkflowDelivery.Inputs value,List<WorkflowDelivery.Input> inputs){return new WorkflowDelivery.Inputs(1,value.requirementId(),value.planRevision(),value.nodeId(),value.objective(),inputs);}
    private static String hash(){return SourceTreeCapture.hash(SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private void writerRead(){input("profile");input("design");read(1,200);}
    private WorkflowDelivery writerResult(){return new WorkflowDelivery("补齐测试文件，实际测试待后续验证",null,Map.of("summary",new WorkflowDelivery.Value(DataKind.TEXT,encoding.decode("\"补齐求和场景\"",tools.jackson.databind.JsonNode.class))));}
    private Object writerSubmit(){return call(WorkflowModelProfile.SUBMIT,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",writerResult()));}
    private WorkflowCodeSnapshot.Reference reference(){return encoding.decode(encoding.encode(delivery().outputs().get("code").content()),WorkflowCodeSnapshot.Reference.class);}
    private void writeTest()throws Exception{Files.writeString(root.resolve("tests/test_added.py"),"def test_added():\n    assert 1 + 2 == 3\n");}
    private void assertRestored()throws Exception {assertThat(Files.readString(root.resolve("src/calculator.py"))).isEqualTo(SOURCE);assertThat(Files.readString(root.resolve("tests/test_existing.py"))).isEqualTo("def test_existing():\n    assert 1 == 1\n");assertThat(root.resolve("tests/test_added.py")).doesNotExist();assertThat(workspaceStore.require(attempt).state()).isEqualTo("RELEASED");}
    private void initGit()throws Exception{git("init","-b","main");git("add",".");git("-c","user.name=Test","-c","user.email=test@example.invalid","commit","-m","base");}
    private void git(String... args)throws Exception {var command=new ArrayList<>(List.of("git","-C",root.toString()));command.addAll(List.of(args));var process=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes());assertThat(process.waitFor()).as(output).isZero();}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-test-write-").toRealPath();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
