package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.roles.*;
import io.opencode.loopper.template.*;
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
class WorkflowSnapshotWorkIntegrationTest {
    private static final Path DATA=data();
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("snapshot-work.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowControls controls;
    @Autowired WorkflowDispatchExecution dispatch;
    @Autowired WorkflowCommandExecution commands;
    @Autowired WorkflowCommandStore commandStore;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowNodePresets presets;
    @Autowired WorkflowModelExecution execution;
    @Autowired WorkflowModelStore models;
    @Autowired WorkflowModelTools tools;
    @Autowired WorkflowRuntimeSupport identity;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired OpenCodeClient client;
    @Autowired WorkflowFinishes finishes;
    @Autowired WorkflowFinishDrain drain;
    @Autowired RolePublishingService roles;
    @Autowired ProjectService projects;
    @Autowired WorkflowSnapshotWorkStore inputs;
    @Autowired WorkflowSnapshotWorkMapper inputMapper;
    @Autowired WorkflowSnapshotPreparation preparation;
    @Autowired WorkflowSnapshotReuseMapper reuse;
    @Autowired WorkflowSnapshotReuseStore reuseStore;
    @Autowired WorkflowSnapshotPartialReports partialReports;
    @Autowired WorkflowDocumentExecution reportExecution;
    @Autowired WorkflowDocumentMapper documents;
    @Autowired GitReviewJobs jobs;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    Path root;String project;FakeOpenCodeClient fake;boolean incremental,includeReport;
    String selectedRole,selectedRevision;Map<String,String> analysisParameters=Map.of();
    @BeforeEach void setup()throws Exception {
        flyway.clean();flyway.migrate();roles.seedBuiltin();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        root=Files.createDirectory(directory.resolve("project")).toRealPath();git.read(root,"init","-b","main","--template=");
        git.read(root,"config","user.name","Fixture");git.read(root,"config","user.email","fixture@example.invalid");
        Files.writeString(root.resolve("code.txt"),"original code\n");Files.writeString(root.resolve(".env"),"FIXTURE_ONLY=fake\n");
        git.read(root,"add",".");git.run(root,Duration.ofSeconds(10),List.of("commit","-m","fixture"),Map.of("GIT_AUTHOR_DATE","2026-09-11T01:00:00Z","GIT_COMMITTER_DATE","2026-09-11T01:00:00Z")).requireSuccess(List.of("commit"));
        project=projects.create("历史分析",root.toString(),"").id();
    }
    @Test void analysisAndIndependentReviewUseTheSameV3RulesAndWaitForStop()throws Exception {
        String id=create(true);capture(id);String analysis=start(id,"analysis");running(analysis);
        assertThat(((Map<?,?>)call(analysis,WorkflowModelProfile.WORK,Map.of())).containsKey("snapshotReview")).isTrue();
        var input=read(analysis);var ref=input.batch().units().getFirst().initialEvidence().getFirst();
        var candidate=analysis(input,List.of(finding(ref)));
        submit(analysis,candidate);assertThat(nodes.attempt(analysis).state()).isEqualTo("RUNNING");assertThat(nodes.hasStop(analysis)).isFalse();finish(analysis);
        String reviewer=start(id,"review");running(reviewer);var reviewInput=read(reviewer);
        assertThat(reviewInput.batch().analysisBatchId()).isEqualTo(analysis);assertThat(reviewInput.analysis()).isEqualTo(candidate);
        submit(reviewer,review(reviewInput,ref));finish(reviewer);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(nodes.delivery(reviewer).contentJson()).contains("SNAPSHOT_REVIEW",analysis);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease",Integer.class)).isZero();assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void completeInitialInputIsRequiredAndWrongClaimsAreCorrectable()throws Exception {
        String id=create(false);capture(id);String run=start(id,"analysis");running(run);var input=inputs.require(run);
        call(run,WorkflowModelProfile.WORK,Map.of("analysis",true,"offset",0,"limit",1));
        assertThatThrownBy(()->submit(run,analysis(input,List.of()))).hasMessageContaining("完整读取");read(run);
        assertThatThrownBy(()->submit(run,new SnapshotReview.Analysis(List.of(),List.of(),List.of(),List.of()))).hasMessageContaining("遗漏");
        var r=input.batch().units().getFirst().initialEvidence().getFirst();var forged=new SnapshotReview.Reference(r.version(),r.path(),r.blob(),r.startLine(),r.endLine(),"not-the-frozen-text");
        assertThatThrownBy(()->submit(run,analysis(input,List.of(finding(forged))))).hasMessageContaining("不匹配");
        var fields=json.valueToTree(analysis(input,List.of())).deepCopy();((tools.jackson.databind.node.ObjectNode)fields).put("unowned",true);
        assertThatThrownBy(()->submitRaw(run,fields,"NO_FINDINGS")).hasMessageContaining("无法解析");
        assertThatThrownBy(()->submitRaw(run,analysis(input,List.of()),"HAS_FINDINGS")).hasMessageContaining("问题数量");
        assertThat(nodes.findDelivery(run)).isEmpty();submit(run,analysis(input,List.of()));finish(run);
    }
    @Test void noFindingsSkipsConditionalReviewWithoutCreatingAnotherSession()throws Exception {
        String id=create(true);capture(id);String run=start(id,"analysis");running(run);submit(run,analysis(read(run),List.of()));finish(run);
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_node_attempt a JOIN workflow_node_run n ON n.id=a.node_run_id WHERE n.requirement_id=? AND n.node_key='review'",Integer.class,id)).isZero();
    }
    @Test void externalContextRequiresEachRolesOwnReadAndSearchAloneDoesNotGrantIt()throws Exception {
        for(int i=0;i<70;i++)Files.writeString(root.resolve(String.format(Locale.ROOT,"part%03d.txt",i)),"part "+i+"\n");
        Files.writeString(root.resolve("zzz-context.txt"),"external caller\nsecond line\n");git.read(root,"add",".");git.read(root,"commit","-m","more files");
        String id=create(true);capture(id);String run=start(id,"analysis");running(run);var input=read(run);
        assertThat(input.batch().units()).noneMatch(u->u.path().equals("zzz-context.txt"));
        var excluded=findFile(run,input.targetSha(),".env");
        assertThatThrownBy(()->call(run,WorkflowModelProfile.FILE,Map.of("name","source","version",excluded.version(),"path",excluded.path(),"blobSha",excluded.blob(),"startLine",1,"lineCount",1))).hasMessageContaining("排除");
        var file=findFile(run,input.targetSha(),"zzz-context.txt");
        var ref=new SnapshotReview.Reference(file.version(),file.path(),file.blob(),1,1,"external caller");
        call(run,WorkflowModelProfile.FILE,Map.of("name","source","version",file.version(),"path",file.path(),"blobSha",file.blob(),"query","external","afterLine",0));
        assertThatThrownBy(()->submit(run,analysis(input,List.of(finding(ref))))).hasMessageContaining("本角色实际读取");
        readCode(run,ref);submit(run,analysis(input,List.of(finding(ref))));finish(run);
        String reviewer=start(id,"review");running(reviewer);var reviewInput=read(reviewer);assertThat(reviewInput.batch().units()).isEmpty();
        assertThatThrownBy(()->submit(reviewer,review(reviewInput,ref))).hasMessageContaining("本角色实际读取");
        readCode(reviewer,ref);submit(reviewer,review(reviewInput,ref));finish(reviewer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_snapshot_work_receipt",Integer.class)).isEqualTo(2);
    }
    @Test void relatedRequestLimitPersistsAndReplayDoesNotConsumeSlots()throws Exception {
        String id=create(false);capture(id);String run=start(id,"analysis");running(run);var input=read(run);
        var request=Map.<String,Object>of("name","source","version",input.targetSha(),"limit",1);
        call(run,WorkflowModelProfile.FILES,request);call(run,WorkflowModelProfile.FILES,request);
        for(int i=1;i<12;i++)call(run,WorkflowModelProfile.FILES,Map.of("name","source","version",input.targetSha(),"cursor","after"+i,"limit",1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_snapshot_work_context WHERE attempt_id=?",Integer.class,run)).isEqualTo(12);
        assertThatThrownBy(()->call(run,WorkflowModelProfile.FILES,Map.of("name","source","version",input.targetSha(),"cursor","new","limit",1))).hasMessageContaining("12 次");
        call(run,WorkflowModelProfile.FILES,request);submit(run,analysis(input,List.of()));finish(run);
    }
    @Test void incrementalReviewKeepsBothVersionsAndAClaimNeedsTargetEvidence()throws Exception {
        incremental=true;Files.writeString(root.resolve("code.txt"),"updated code\n");git.read(root,"add",".");
        git.run(root,Duration.ofSeconds(10),List.of("commit","-m","updated"),Map.of("GIT_AUTHOR_DATE","2026-09-12T01:00:00Z","GIT_COMMITTER_DATE","2026-09-12T01:00:00Z")).requireSuccess(List.of("commit"));
        String id=create(false);capture(id);String run=start(id,"analysis");running(run);var input=read(run);
        assertThat(input.baselineSha()).isNotEqualTo(input.targetSha());
        var before=findFile(run,input.baselineSha(),"code.txt");var after=findFile(run,input.targetSha(),"code.txt");
        var old=new SnapshotReview.Reference(before.version(),before.path(),before.blob(),1,1,"original code");
        var current=new SnapshotReview.Reference(after.version(),after.path(),after.blob(),1,1,"updated code");
        readCode(run,old);readCode(run,current);
        assertThatThrownBy(()->submit(run,analysis(input,List.of(finding(old))))).hasMessageContaining("目标版本");
        assertThatThrownBy(()->call(run,WorkflowModelProfile.FILES,Map.of("name","source","version","f".repeat(40)))).hasMessageContaining("不属于");
        submit(run,analysis(input,List.of(finding(current))));finish(run);
    }
    @Test void preparedInputSurvivesBothSourceFilesAndPrivateRepositoryGoingOffline()throws Exception {
        String id=create(false),source=capture(id),run=start(id,"analysis");preparation.prepare(models.require(run));String frozen=inputMapper.find(run).orElseThrow().inputJson();
        var reference=json.treeToValue(json.readTree(nodes.delivery(source).contentJson()).path("outputs").path("source").path("content"),WorkflowReviewSource.Reference.class);
        Files.move(DATA.toRealPath().resolve("workflow-review-content").resolve(reference.snapshotId()),DATA.toRealPath().resolve("offline-content-"+key()));
        Files.move(jobs.repository(reference.snapshotId()),jobs.repository(reference.snapshotId()).resolveSibling("offline-review"));
        running(run);assertThat(inputMapper.find(run).orElseThrow().inputJson()).isEqualTo(frozen);submit(run,analysis(read(run),List.of()));finish(run);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_snapshot_work_input SET input_json=input_json WHERE attempt_id=?",run)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_snapshot_work_input WHERE attempt_id=?",run)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO workflow_snapshot_work_context VALUES(?,?,'t')",run,"b".repeat(64))).isInstanceOf(RuntimeException.class);
    }
    @Test void acceptanceAndReceiptRollbackTogetherWithoutRerunningTheModel()throws Exception {
        String id=create(false);capture(id);String run=start(id,"analysis");running(run);var input=read(run);
        jdbc.execute("CREATE TRIGGER fail_snapshot_receipt BEFORE INSERT ON workflow_command WHEN NEW.action='MODEL_SUBMIT' BEGIN SELECT RAISE(ABORT,'receipt rollback'); END");
        assertThatThrownBy(()->submit(run,analysis(input,List.of()))).isInstanceOf(RuntimeException.class).hasStackTraceContaining("receipt rollback");assertThat(nodes.findDelivery(run)).isEmpty();
        jdbc.execute("DROP TRIGGER fail_snapshot_receipt");submit(run,analysis(input,List.of()));finish(run);assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
    }
    @Test void cancellationRejectsLatePagesReadsAndSubmissionButRetainsInput()throws Exception {
        String id=create(false);capture(id);String run=start(id,"analysis");running(run);var input=read(run);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消审查"));
        assertThatThrownBy(()->call(run,WorkflowModelProfile.WORK,Map.of("analysis",true))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->call(run,WorkflowModelProfile.FILES,Map.of("name","source","version",input.targetSha()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->submit(run,analysis(input,List.of()))).isInstanceOf(RuntimeException.class);
        drain.stop(id,run);execution.advance(run);assertThat(finishes.finalizeReady(id)).isTrue();assertThat(inputs.require(run)).isEqualTo(input);assertThat(nodes.findDelivery(run)).isEmpty();
    }
    @Test void closedRoleReusesSameTreeWithNewCommitWithoutSessionOrReadingReceipts()throws Exception {
        closedRole();String original=completedAnalysis();var originalInput=inputs.require(original);
        git.read(root,"commit","--allow-empty","-m","new identity same tree");
        includeReport=true;String id=create(true);String source=capture(id);String run=start(id,"analysis");execution.advance(run);
        assertThat(models.require(run).state()).as(models.require(run).lastErrorCode()).isEqualTo("SUCCEEDED");
        assertThat(nodes.attempt(run).externalSessionId()).isNull();assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
        assertThat(reuse.receipt(run).orElseThrow().sourceAttemptId()).isEqualTo(original);
        assertThat(inputs.require(run).targetSha()).isNotEqualTo(originalInput.targetSha());
        assertThat(nodes.delivery(run).contentJson()).contains("sourceAttemptId",original,"SNAPSHOT_ANALYSIS");
        assertThat(jdbc.queryForObject("SELECT kind FROM workflow_attempt_stop WHERE attempt_id=?",String.class,run)).isEqualTo("NO_SESSION_CREATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_snapshot_work_page WHERE attempt_id=?",Integer.class,run)).isZero();
        assertThat(partialReports.read(id,"source",source).content()).contains("复用历史有效分析 1 批","分析固定代码");
        String report=start(id,"report");reportExecution.advance(report);assertThat(nodes.attempt(report).state()).isEqualTo("SUCCEEDED");
        assertThat(documents.files(report)).anyMatch(f->f.content().contains("复用历史有效分析 1 批")&&f.content().contains("源结果 SHA-256"));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        execution.advance(run);assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_snapshot_reuse SET claims_json=claims_json WHERE attempt_id=?",run)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_snapshot_reuse WHERE attempt_id=?",run)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_node_attempt SET external_session_id='forged' WHERE id=?",run)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void defaultKnowledgeRoleAndExplicitBypassAlwaysRunTheModel()throws Exception {
        String original=completedAnalysis();assertThat(reuse.context(original)).isEmpty();
        String id=create(false);capture(id);String run=start(id,"analysis");running(run);assertThat(reuse.receipt(run)).isEmpty();
        closedRole();completedAnalysis();analysisParameters=Map.of("snapshotReuse","BYPASS");
        id=create(false);capture(id);run=start(id,"analysis");running(run);assertThat(reuse.context(run)).isEmpty();assertThat(reuse.receipt(run)).isEmpty();
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(4);
    }
    @Test void fullTreeChangesAndChangedTaskParametersInvalidateReuse()throws Exception {
        closedRole();completedAnalysis();Files.writeString(root.resolve("dependency.txt"),"new caller\n");git.read(root,"add",".");git.read(root,"commit","-m","dependency changed");
        String id=create(false);capture(id);String run=start(id,"analysis");running(run);assertThat(reuse.receipt(run)).isEmpty();
        submit(run,analysis(read(run),List.of()));finish(run);
        analysisParameters=Map.of("userInstruction","check compatibility too");id=create(false);capture(id);run=start(id,"analysis");running(run);assertThat(reuse.receipt(run)).isEmpty();
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(3);
    }
    @Test void incompleteOrStillRunningSourceCannotBeReused()throws Exception {
        closedRole();String id=create(false);capture(id);String run=start(id,"analysis");running(run);var input=read(run);
        submit(run,new SnapshotReview.Analysis(analysis(input,List.of()).coverage(),List.of(),List.of(),List.of("尚未确认")));
        String second=create(false);capture(second);String secondRun=start(second,"analysis");running(secondRun);assertThat(reuse.receipt(secondRun)).isEmpty();
        finish(run);String third=create(false);capture(third);String thirdRun=start(third,"analysis");running(thirdRun);assertThat(reuse.receipt(thirdRun)).isEmpty();
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(3);
    }
    @Test void reuseAcceptanceRollsBackAndRecoversAtomically()throws Exception {
        closedRole();completedAnalysis();String id=create(false);capture(id);String run=start(id,"analysis");
        jdbc.execute("CREATE TRIGGER reject_reused_stop BEFORE INSERT ON workflow_attempt_stop WHEN NEW.kind='NO_SESSION_CREATED' BEGIN SELECT RAISE(ABORT,'reuse stop rollback'); END");
        assertThatThrownBy(()->preparation.prepare(models.require(run))).isInstanceOf(RuntimeException.class).hasStackTraceContaining("reuse stop rollback");
        assertThat(reuse.context(run)).isPresent();assertThat(reuse.receipt(run)).isEmpty();assertThat(nodes.findDelivery(run)).isEmpty();
        assertThat(nodes.attempt(run).state()).isEqualTo("PREPARING");assertThat(models.require(run).state()).isEqualTo("PREPARING");
        jdbc.execute("DROP TRIGGER reject_reused_stop");assertThat(preparation.prepare(models.require(run))).isTrue();
        assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
    }
    @Test void cancellationBeforeReuseRejectsLateAcceptance()throws Exception {
        closedRole();completedAnalysis();String id=create(false);capture(id);String run=start(id,"analysis");var expected=models.require(run);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"停止"));
        assertThatThrownBy(()->preparation.prepare(expected)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->reuseStore.apply(expected)).isInstanceOf(RuntimeException.class);
        drain.stop(id,run);execution.advance(run);assertThat(finishes.finalizeReady(id)).isTrue();
        assertThat(reuse.receipt(run)).isEmpty();assertThat(nodes.findDelivery(run)).isEmpty();assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
    }
    @Test void racingReuseRequestsPublishOneResultAndCannotCreateASession()throws Exception {
        closedRole();String first=create(false);capture(first);String original=start(first,"analysis");running(original);submit(original,analysis(read(original),List.of()));
        String id=create(false);capture(id);String run=start(id,"analysis");assertThat(preparation.prepare(models.require(run))).isFalse();var expected=models.require(run);
        assertThatThrownBy(()->jdbc.update("INSERT INTO workflow_attempt_stop VALUES(?,'NO_SESSION_CREATED',NULL,'{}','t')",run)).isInstanceOf(RuntimeException.class);
        finish(original);try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate=new java.util.concurrent.CountDownLatch(1);var action=(java.util.concurrent.Callable<Boolean>)()->{gate.await();try{return reuseStore.apply(expected);}catch(io.opencode.loopper.service.ConflictException conflict){return false;}};
            var one=pool.submit(action);var two=pool.submit(action);gate.countDown();assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_snapshot_reuse WHERE attempt_id=?",Integer.class,run)).isEqualTo(1);
        assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
    }
    @Test void manualRetryRunsAgainEvenWhenAMatchingSourceCompletesLater()throws Exception {
        closedRole();String first=create(false);capture(first);String original=start(first,"analysis");running(original);submit(original,analysis(read(original),List.of()));
        String id=create(false);capture(id);String failed=start(id,"analysis");running(failed);fake.setSessionState(nodes.attempt(failed).externalSessionId(),"FAILED");execution.advance(failed);
        assertThat(nodes.attempt(failed).state()).isEqualTo("FAILED");finish(original);
        String retried=start(id,"analysis");assertThat(nodes.attempt(retried).ordinal()).isEqualTo(2);running(retried);
        assertThat(reuse.context(retried)).isEmpty();assertThat(reuse.receipt(retried)).isEmpty();assertThat(fake.createReadOnlySessionCalls()).isEqualTo(3);
    }
    private void closedRole(){
        selectedRole="fixture.closed-review";
        var role=new RoleManifest.Role(selectedRole,"固定资料审查员","仅使用固定输入","general","通用",List.of("WORKFLOW_READ_ONLY"),"INTERSECT",List.of(),List.of(),List.of(),"INHERIT_WORKFLOW","WORKFLOW_ADAPTER",Map.of(),List.of(),"只根据固定资料审查，不使用动态知识。");
        var parsed=new RoleArchive.Parsed("c".repeat(64),new RoleManifest.Document(2,List.of(),List.of(role)),Map.of());var validation=roles.validate(parsed);
        assertThat(validation.diagnostics()).isEmpty();selectedRevision=roles.publish(parsed,new RolePublishingService.PublishRequest(parsed.sourceSha256(),key(),validation.activations())).roles().getFirst().revisionId();
    }
    private String completedAnalysis()throws Exception {String id=create(false);capture(id);String run=start(id,"analysis");running(run);submit(run,analysis(read(run),List.of()));finish(run);return run;}
    private String create(boolean review){
        var sourceInputs=new ArrayList<>(List.of(new Input("branch",InputSource.REQUIREMENT,"branch",null,DataKind.TEXT,true)));
        var publicInputs=new ArrayList<>(List.of(new PublicInput("branch","分支",DataKind.TEXT,true)));
        if(incremental)for(String date:List.of("startDate","endDate")){sourceInputs.add(new Input(date,InputSource.REQUIREMENT,date,null,DataKind.TEXT,true));publicInputs.add(new PublicInput(date,date,DataKind.TEXT,true));}
        var source=node(incremental?"review.snapshot":"review.snapshot-full","source",sourceInputs,Map.of());
        var analysis=node("snapshot.analyze","analysis",List.of(new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true)),analysisParameters);
        var graphNodes=new ArrayList<>(List.of(source,analysis));var edges=new ArrayList<>(List.of(new Edge("source-analysis","source","analysis",null)));
        if(review){graphNodes.add(node("snapshot.review","review",List.of(new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true),new Input("analysis",InputSource.NODE,"analysis","analysis",DataKind.JSON,true)),Map.of()));edges.add(new Edge("analysis-review","analysis","review","HAS_FINDINGS"));}
        if(includeReport){graphNodes.add(node("snapshot.report","report",List.of(new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true),new Input("analysis",InputSource.NODE,"analysis","analysis",DataKind.JSON,true)),Map.of("reviewPolicy","NONE")));edges.add(new Edge("analysis-report","analysis","report",null));}
        var graph=new WorkflowGraph(1,graphNodes,edges,publicInputs);
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"版本审查","",graph,CanvasLayout.empty()));var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"分析固定代码","按固定资料审查",template.id(),1));plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private Node node(String preset,String id,List<Input> inputs,Map<String,String> override){
        var n=presets.get(preset,1).node();var parameters=new LinkedHashMap<>(n.parameters());parameters.putAll(override);
        return new Node(id,n.title(),n.kind(),n.moduleId(),1,selectedRole!=null&&preset.equals("snapshot.analyze")?selectedRole:n.roleId(),n.task(),inputs,n.outputs(),n.outcomes(),n.completion(),0,false,parameters,selectedRole!=null&&preset.equals("snapshot.analyze")?selectedRevision:n.roleRevisionId());
    }
    private String start(String id,String node){
        var values=new LinkedHashMap<String,WorkflowDelivery.Value>();values.put("branch",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("local:refs/heads/main")));
        if(incremental)for(String date:List.of("startDate","endDate"))values.put(date,new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("2026-09-12")));
        controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,node,values,MODEL,List.of()));dispatch.advance(id);
        String run=nodes.node(id,plans.require(id).headRevision(),node).latestAttemptId();assertThat(run).as(controls.get(id).reasonCode()).isNotNull();return run;
    }
    private String capture(String id)throws Exception{
        String run=start(id,"source");long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<deadline&&!WorkflowAttemptState.valueOf(nodes.attempt(run).state()).terminal()&&!commandStore.require(run).suspended()){commands.advance(run);Thread.sleep(25);}
        assertThat(nodes.attempt(run).state()).as(commandStore.require(run).lastErrorCode()).isEqualTo("SUCCEEDED");return run;
    }
    private void running(String run){for(int i=0;i<4&&!models.require(run).state().equals("RUNNING");i++)execution.advance(run);assertThat(models.require(run).state()).isEqualTo("RUNNING");}
    private WorkflowSnapshotWork.Input read(String run){
        int offset=0;StringBuilder text=new StringBuilder();do{var page=(WorkflowInputPages.Page)call(run,WorkflowModelProfile.WORK,Map.of("analysis",true,"offset",offset,"limit",12000));text.append(page.text());if(page.nextOffset()==null)break;offset=page.nextOffset();}while(true);
        return json.readValue(text.toString(),WorkflowSnapshotWork.Input.class);
    }
    private SnapshotReview.Analysis analysis(WorkflowSnapshotWork.Input input,List<SnapshotReview.Finding> findings){return new SnapshotReview.Analysis(input.batch().units().stream().map(u->new SnapshotReview.Coverage(u.id(),"已分析",List.of(),List.of())).toList(),findings,List.of(),List.of());}
    private SnapshotReview.Finding finding(SnapshotReview.Reference reference){return new SnapshotReview.Finding("f1",TemplateAnalysis.Severity.HIGH,"候选问题","具体输入","实际错误","建议修复",SnapshotReview.Attribution.UNDETERMINED,List.of(reference));}
    private SnapshotReview.Review review(WorkflowSnapshotWork.Input input,SnapshotReview.Reference reference){return new SnapshotReview.Review(input.batch().units().stream().map(SnapshotReview.Unit::id).toList(),List.of(new SnapshotReview.Decision("f1",SnapshotReview.Verdict.SUPPORTED,"源码支持",null,List.of(reference))),List.of(reference),"独立复核",List.of());}
    private SnapshotReview.File findFile(String run,String version,String path){
        var page=(io.opencode.loopper.api.CursorPage<?>)call(run,WorkflowModelProfile.FILES,Map.of("name","source","version",version,"limit",100));
        return page.items().stream().map(SnapshotReview.File.class::cast).filter(f->f.path().equals(path)).findFirst().orElseThrow();
    }
    private void readCode(String run,SnapshotReview.Reference ref){call(run,WorkflowModelProfile.FILE,Map.of("name","source","version",ref.version(),"path",ref.path(),"blobSha",ref.blob(),"startLine",ref.startLine(),"lineCount",ref.endLine()-ref.startLine()+1));}
    private Object submit(String run,Object claims){return submitRaw(run,claims,claims instanceof SnapshotReview.Analysis a?(a.findings().isEmpty()?"NO_FINDINGS":"HAS_FINDINGS"):null);}
    private Object submitRaw(String run,Object claims,String outcome){
        var delivery=new WorkflowDelivery("审查完成",outcome,Map.of("summary",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("静态分析")),"analysis",new WorkflowDelivery.Value(DataKind.JSON,json.valueToTree(claims))));
        return call(run,WorkflowModelProfile.SUBMIT,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(run).version(),"delivery",delivery));
    }
    private void finish(String run){fake.setSessionState(nodes.attempt(run).externalSessionId(),"COMPLETED");execution.advance(run);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");}
    private Object call(String run,String tool,Map<String,Object> args){return tools.call(tool,Map.of("scope",identity.grant(models.require(run)),"attemptId",run,"args",args));}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-snapshot-work-tests-");}catch(Exception invalid){throw new ExceptionInInitializerError(invalid);}}
}
