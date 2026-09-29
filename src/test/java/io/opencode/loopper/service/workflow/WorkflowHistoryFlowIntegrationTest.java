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
class WorkflowHistoryFlowIntegrationTest {
    private static final Path DATA=data();
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("history-flow.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
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
    @Autowired WorkflowHistoryAnalysisStore inputs;
    @Autowired WorkflowHistoryAnalysisMapper inputMapper;
    @Autowired WorkflowHistoryAnalysisPreparation preparation;
    @Autowired GitHistoryJobs jobs;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkflowBuiltinFlows builtin;
    @Autowired WorkflowSourcePlanExecution planExecution;
    @Autowired WorkflowSourcePlanStore planStore;
    @Autowired WorkflowPlanCandidates candidates;
    @Autowired WorkflowDocumentStore reportStore;
    @Autowired WorkflowDocumentExecution reportExecution;
    @Autowired WorkflowHistoryReportBuilder reportBuilder;
    @Autowired WorkflowHistoryPlanBuilder planBuilder;
    @Autowired WorkflowHistoryReportMapper reports;
    @Autowired WorkflowHistoryInputs historyInputs;
    @Autowired WorkflowHistoryEvidence historyEvidence;
    @Autowired WorkflowDocumentMapper documents;
    @Autowired WorkflowCodeFiles files;
    @Autowired WorkflowPlanTemplates exports;
    @TempDir Path directory;
    String date="2026-09-11";
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    Path root;String project;FakeOpenCodeClient fake;
    @BeforeEach void setup()throws Exception {
        flyway.clean();flyway.migrate();roles.seedBuiltin();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        root=Files.createDirectory(directory.resolve("project")).toRealPath();git.read(root,"init","-b","main","--template=");
        git.read(root,"config","user.name","Fixture");git.read(root,"config","user.email","fixture@example.invalid");
        Files.writeString(root.resolve("code.txt"),"original code\n");Files.writeString(root.resolve(".env"),"FIXTURE_ONLY=fake\n");
        for(int i=0;i<12;i++)Files.writeString(root.resolve("file-"+i+".txt"),"value "+i+"\n");
        git.read(root,"add",".");git.run(root,Duration.ofSeconds(10),List.of("commit","-m","fixture"),Map.of("GIT_AUTHOR_DATE","2026-09-11T01:00:00Z","GIT_COMMITTER_DATE","2026-09-11T01:00:00Z")).requireSuccess(List.of("commit"));
        project=projects.create("历史分析",root.toString(),"").id();builtin.publish();
    }

    @Test void completeHistoryPlanWaitsForConfirmationThenCompilesFrozenNavigableReport()throws Exception {
        String id=create(false);var initial=plans.get(id,null).graph();var originalReport=nodes.node(id,1,"report");
        String layout=reports.format(originalReport.id()).orElseThrow().layoutJson();capture(id);var proposal=propose(id);
        assertThat(plans.require(id).headRevision()).isEqualTo(1);assertThat(fake.createReadOnlySessionCalls()).isZero();
        assertThat(proposal.graph().nodes().stream().filter(n->WorkflowHistoryAnalysis.REVIEW.equals(n.moduleId()))).hasSize(2);
        apply(id,proposal);assertThat(reports.format(nodes.node(id,2,"report").id()).orElseThrow().layoutJson()).isEqualTo(layout);
        var current=plans.get(id,null);
        var exported=exports.preview(id,new WorkflowTemplateExport.PreviewRequest(current.revision(),WorkflowTemplateExport.Mode.CURRENT,current.graph(),current.layout()));
        assertThat(exported.fixedPlanningNodes()).hasSize(1);assertThat(exported.graph().nodes().stream().filter(n->n.id().equals("batches")).findFirst().orElseThrow().kind()).isEqualTo(NodeKind.HUMAN);
        assertThat(exports.preview(id,new WorkflowTemplateExport.PreviewRequest(current.revision(),WorkflowTemplateExport.Mode.INITIAL,current.graph(),current.layout())).graph()).isEqualTo(initial);
        analyze(id);String report=start(id,"report");var bundle=reports.bundle(report).orElseThrow();
        jdbc.update("UPDATE project SET name='执行中改名' WHERE id=?",project);
        var first=reportBuilder.build(reportStore.context(report));assertThat(reportBuilder.build(reportStore.context(report))).isEqualTo(first);
        assertThat(first.details().get("mainPath")).isEqualTo(bundle.folderName()+"/"+bundle.mainPath());
        reportExecution.advance(report);assertThat(nodes.attempt(report).state()).isEqualTo("SUCCEEDED");assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        var binding=files.output(id,"report",report,"document");var archive=files.archive(binding);var paths=new ArrayList<String>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive),java.nio.charset.StandardCharsets.UTF_8)) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){paths.add(entry.getName());assertThat(new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("执行中改名");}
        }
        assertThat(paths).hasSize(5).allMatch(path->path.startsWith(bundle.folderName()+"/")&&path.endsWith(".md"));
        var main=first.files().get(first.details().get("mainPath"));assertThat(main).contains("历史提交审查","未复核","%E");
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease",Integer.class)).isZero();
    }
    @Test void contributionFlowCoversAllBatchesAndCompilesProgramScores()throws Exception {
        String id=create(true);capture(id);apply(id,propose(id));analyze(id);String report=start(id,"report");
        var context=reportStore.context(report);var full=reportBuilder.build(context);
        assertThat(full.files().values()).anyMatch(body->body.contains("总分")&&body.contains("数量"));
        assertThat(full.details().get("assessedContributorCount")).isEqualTo(1);
        var withoutReview=context.inputs().values().stream().filter(i->!i.name().equals("review_2")).toList();
        var partial=new WorkflowDelivery.Inputs(1,id,context.inputs().planRevision(),"report",context.inputs().objective(),withoutReview);
        assertThatThrownBy(()->reportBuilder.build(new WorkflowDocumentStore.Context(context.attempt(),context.node(),partial,project))).hasMessageContaining("缺少审查批次");
        var withoutPerson=context.inputs().values().stream().filter(i->!i.name().startsWith("person_")).toList();
        var missing=new WorkflowDelivery.Inputs(1,id,context.inputs().planRevision(),"report",context.inputs().objective(),withoutPerson);
        assertThatThrownBy(()->reportBuilder.build(new WorkflowDocumentStore.Context(context.attempt(),context.node(),missing,project))).hasMessageContaining("缺少人员评价");
        reportExecution.advance(report);assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void emptyHistorySkipsAllModelsAndStillProvidesACompleteReport()throws Exception {
        date="2000-01-01";String id=create(true);capture(id);var proposal=propose(id);
        assertThat(proposal.graph().nodes()).extracting(Node::id).containsExactly("source","batches","report");apply(id,proposal);
        String report=start(id,"report");reportExecution.advance(report);assertThat(nodes.attempt(report).state()).isEqualTo("SUCCEEDED");
        assertThat(fake.createReadOnlySessionCalls()).isZero();assertThat(documents.files(report)).anyMatch(f->f.content().contains("没有 Git 提交"));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void reportWriteRollbackRetainsIdentityAndRecoveryUsesTheSameNumber()throws Exception {
        String id=create(false);capture(id);apply(id,propose(id));analyze(id);String report=start(id,"report");
        var context=reportStore.context(report);var result=reportBuilder.build(context);var identity=reports.bundle(report).orElseThrow();
        jdbc.execute("CREATE TRIGGER fail_history_report BEFORE INSERT ON workflow_node_delivery WHEN NEW.attempt_id='"+report+"' BEGIN SELECT RAISE(ABORT,'report rollback'); END");
        assertThatThrownBy(()->reportStore.finish(context,result,null)).hasStackTraceContaining("report rollback");
        assertThat(documents.find(report)).isEmpty();assertThat(documents.files(report)).isEmpty();assertThat(nodes.hasStop(report)).isFalse();
        assertThat(reports.bundle(report).orElseThrow()).isEqualTo(identity);jdbc.execute("DROP TRIGGER fail_history_report");
        reportExecution.advance(report);assertThat(nodes.attempt(report).state()).isEqualTo("SUCCEEDED");assertThat(reports.bundle(report).orElseThrow()).isEqualTo(identity);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_history_report_bundle SET sequence=sequence WHERE attempt_id=?",report)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_history_report_format WHERE node_run_id=?",nodes.attempt(report).nodeRunId())).isInstanceOf(RuntimeException.class);
    }
    @Test void cancellationRejectsALatePlanAndReportWithoutDiscardingTheirInputs()throws Exception {
        String id=create(false);capture(id);String planner=start(id,"batches");var context=planStore.context(planner);var proposal=planBuilder.build(context);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消"));drain.stop(id,planner);assertThat(finishes.finalizeReady(id)).isTrue();
        planStore.finish(context,planBuilder.delivery(proposal,null),true);assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();
        String next=create(false);capture(next);apply(next,propose(next));analyze(next);String report=start(next,"report");var reportContext=reportStore.context(report);var result=reportBuilder.build(reportContext);
        finishes.request(next,new WorkflowFinishes.Request(key(),plans.require(next).version(),WorkflowState.CANCELLED,"取消报告"));
        assertThatThrownBy(()->reportStore.finish(reportContext,result,null)).isInstanceOf(RuntimeException.class);assertThat(documents.find(report)).isEmpty();
        drain.stop(next,report);assertThat(finishes.finalizeReady(next)).isTrue();assertThat(reports.bundle(report)).isPresent();
    }
    @Test void zeroEffectiveContributorRemainsInReportWithoutASubjectiveModel()throws Exception {
        date="2026-09-12";
        git.run(root,Duration.ofSeconds(10),List.of("commit","--allow-empty","-m","empty"),Map.of("GIT_AUTHOR_DATE","2026-09-12T01:00:00Z","GIT_COMMITTER_DATE","2026-09-12T01:00:00Z")).requireSuccess(List.of("commit"));
        String id=create(true);capture(id);var proposal=propose(id);assertThat(proposal.graph().nodes()).noneMatch(n->WorkflowHistoryAnalysis.CONTRIBUTION.equals(n.moduleId()));
        apply(id,proposal);analyze(id);String report=start(id,"report");var result=reportBuilder.build(reportStore.context(report));
        assertThat(result.details().get("contributorCount")).isEqualTo(1);assertThat(result.details().get("assessedContributorCount")).isEqualTo(0);
        reportExecution.advance(report);assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
    }
    @Test void newReportAttemptsSharePersistentNumbersWhileRepeatedRenderingDoesNotConsumeOne()throws Exception {
        date="2000-01-01";String first=create(false);capture(first);apply(first,propose(first));String report=start(first,"report");
        assertThat(reports.bundle(report).orElseThrow().sequence()).isEqualTo(1);reportExecution.advance(report);reportExecution.advance(report);
        String next=create(false);capture(next);apply(next,propose(next));String second=start(next,"report");
        assertThat(reports.bundle(second).orElseThrow().sequence()).isEqualTo(2);reportExecution.advance(second);
        assertThat(documents.files(report)).noneMatch(f->f.path().equals(reports.bundle(second).orElseThrow().mainPath()));
    }
    @Test void planningKeepsUserConfigurationAndRefusesToTruncateOversizedHistory()throws Exception {
        String id=create(false);capture(id);String planner=start(id,"batches");var context=planStore.context(planner);var original=context.plan().graph();
        var changed=original.nodes().stream().map(n->WorkflowHistoryAnalysis.REVIEW.equals(n.moduleId())
            ?new Node(n.id(),"自定义审查",n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),"只检查明确范围",n.inputs(),n.outputs(),n.outcomes(),n.completion(),3,true,n.parameters(),n.roleRevisionId()):n).toList();
        var graph=new WorkflowGraph(1,changed,original.edges(),original.inputs());
        var plan=new WorkflowPlanCandidates.Context(1,"batches",context.plan().editableNodeKeys(),graph,context.plan().instruction());
        var configured=new WorkflowSourcePlanStore.Context(context.attempt(),context.node(),context.inputs(),project,plan);
        var result=planBuilder.build(configured);
        assertThat(result.proposal().graph().nodes().stream().filter(n->WorkflowHistoryAnalysis.REVIEW.equals(n.moduleId())))
            .allSatisfy(n->{assertThat(n.maxRetries()).isEqualTo(3);assertThat(n.pauseAfter()).isTrue();assertThat(n.task()).isEqualTo("只检查明确范围");assertThat(n.roleId()).isEqualTo("builtin.template");});
        var fixed=historyInputs.read(context.inputs());var originalEvidence=historyEvidence.read(fixed.manifest());var first=originalEvidence.commits().getFirst();
        var changes=new ArrayList<TemplateGitEvidence.Change>();for(int i=0;i<768;i++)changes.add(new TemplateGitEvidence.Change("change-"+i,"file-"+i,"b","a",1,0,false,1,null,"@@ -0,0 +1 @@\n+code\n"));
        var commit=new TemplateGitEvidence.Commit(first.sha(),first.parents(),first.committedAt(),"容量边界",first.contributors(),"ANALYZE",changes);
        var large=new TemplateGitEvidence(originalEvidence.version(),originalEvidence.branchId(),originalEvidence.head(),originalEvidence.startDate(),originalEvidence.endDate(),originalEvidence.timezone(),null,List.of(commit));
        var frozen=org.mockito.Mockito.mock(WorkflowHistoryInputs.class);org.mockito.Mockito.when(frozen.read(context.inputs())).thenReturn(fixed);
        var reader=org.mockito.Mockito.mock(WorkflowHistoryEvidence.class);org.mockito.Mockito.when(reader.read(fixed.manifest())).thenReturn(large);
        assertThatThrownBy(()->new WorkflowHistoryPlanBuilder(frozen,reader,new WorkflowEncoding(json)).build(context)).hasMessageContaining("未截断");
        assertThat(nodes.findDelivery(planner)).isEmpty();assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();
    }
    private String create(boolean contribution) {
        String template=contribution?"builtin.workflow.history-contribution":"builtin.workflow.history-review";
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"完整历史报告","使用固定资料",template,templates.get(template,null).revision()));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private WorkflowPlanCandidates.Detail propose(String id) {
        String planner=start(id,"batches");planExecution.advance(planner);assertThat(nodes.attempt(planner).state()).as(nodes.findDelivery(planner).map(WorkflowExecutionRows.Delivery::contentJson).orElse("")).isEqualTo("SUCCEEDED");
        return candidates.get(id,candidates.list(id,"PENDING",null,50).items().getFirst().id());
    }
    private void apply(String id,WorkflowPlanCandidates.Detail proposal){candidates.apply(id,proposal.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),plans.require(id).headRevision(),proposal.version(),null));}
    private void analyze(String id) {
        var graph=plans.get(id,null).graph();
        for(var node:graph.nodes())if(WorkflowHistoryAnalysis.REVIEW.equals(node.moduleId())){String run=start(id,node.id());running(run);submit(run,candidate(read(run)));finish(run);}
        for(var node:graph.nodes())if(WorkflowHistoryAnalysis.CONTRIBUTION.equals(node.moduleId())) {
            String run=start(id,node.id());running(run);var input=read(run);var dimension=new ContributionScore.Assessment(2,"固定本人证据",List.of(input.person().evidenceIds().iterator().next()));
            submit(run,new TemplateAnalysis.ContributorCandidate(input.person().author().identity(),"本人贡献",dimension,dimension,dimension,dimension));finish(run);
        }
    }
    private String start(String id,String node) {
        controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,node,
            Map.of("branch",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("local:refs/heads/main")),"startDate",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree(date)),"endDate",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree(date))),MODEL,List.of()));dispatch.advance(id);
        String run=nodes.node(id,plans.require(id).headRevision(),node).latestAttemptId();assertThat(run).as(controls.get(id).reasonCode()).isNotNull();return run;
    }
    private String capture(String id)throws Exception {
        String run=start(id,"source");long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<deadline&&!WorkflowAttemptState.valueOf(nodes.attempt(run).state()).terminal()&&!commandStore.require(run).suspended()){commands.advance(run);Thread.sleep(25);}
        assertThat(nodes.attempt(run).state()).as(commandStore.require(run).lastErrorCode()).isEqualTo("SUCCEEDED");return run;
    }
    private void running(String run){for(int i=0;i<4&&!models.require(run).state().equals("RUNNING");i++)execution.advance(run);assertThat(models.require(run).state()).isEqualTo("RUNNING");}
    private WorkflowHistoryAnalysis.Input read(String run) {
        int offset=0;StringBuilder text=new StringBuilder();do{var page=(WorkflowInputPages.Page)call(run,WorkflowModelProfile.WORK,Map.of("analysis",true,"offset",offset,"limit",12000));text.append(page.text());if(page.nextOffset()==null)break;offset=page.nextOffset();}while(true);
        return json.readValue(text.toString(),WorkflowHistoryAnalysis.Input.class);
    }
    private TemplateAnalysis.BatchCandidate candidate(WorkflowHistoryAnalysis.Input input){return new TemplateAnalysis.BatchCandidate(input.units().stream().map(u->new TemplateAnalysis.UnitReview(u.id(),"已审查",List.of(),List.of("静态证据；未运行测试"))).toList());}
    private Object submit(String run,Object analysis) {
        var delivery=new WorkflowDelivery("分析完成",null,Map.of("summary",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("静态分析")),"analysis",new WorkflowDelivery.Value(DataKind.JSON,json.valueToTree(analysis))));
        return call(run,WorkflowModelProfile.SUBMIT,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(run).version(),"delivery",delivery));
    }
    private void finish(String run){fake.setSessionState(nodes.attempt(run).externalSessionId(),"COMPLETED");execution.advance(run);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");}
    private Object call(String run,String tool,Map<String,Object> args){return tools.call(tool,Map.of("scope",identity.grant(models.require(run)),"attemptId",run,"args",args));}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-history-flow-tests-");}catch(Exception invalid){throw new ExceptionInInitializerError(invalid);}}
}
