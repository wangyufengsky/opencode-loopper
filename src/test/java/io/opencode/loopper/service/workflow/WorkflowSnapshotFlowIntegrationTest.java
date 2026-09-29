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

@org.springframework.context.annotation.Import(WorkflowSnapshotFlowIntegrationTest.CountingConfiguration.class)
@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.workflow-monitor-enabled=false","loopper.monitor-delay=1h"})
class WorkflowSnapshotFlowIntegrationTest {
    private static final Path DATA=data();
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("snapshot-flow.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired QueryCounter queries;
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
    @Autowired GitReviewJobs jobs;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkflowBuiltinFlows builtin;
    @Autowired WorkflowSourcePlanExecution planExecution;
    @Autowired WorkflowSourcePlanStore planStore;
    @Autowired WorkflowSnapshotPlanBuilder planBuilder;
    @Autowired WorkflowPlanCandidates candidates;
    @Autowired WorkflowDocumentStore reportStore;
    @Autowired WorkflowDocumentExecution reportExecution;
    @Autowired WorkflowSnapshotReportBuilder reportBuilder;
    @Autowired WorkflowSnapshotReportMapper reports;
    @Autowired WorkflowDocumentMapper documents;
    @Autowired WorkflowCodeFiles files;
    @Autowired WorkflowPlanTemplates exports;
    @Autowired WorkflowSnapshotPartialReports partialReports;
    @Autowired WorkflowSnapshotPartialReads partialReads;
    @Autowired WorkflowPlanRevisions revisions;
    @Autowired WorkflowSnapshotInputs snapshotInputs;
    @Autowired WorkflowSnapshotEvidence evidence;
    @TempDir Path directory;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    Path root;String project;FakeOpenCodeClient fake;boolean incremental;
    @BeforeEach void setup()throws Exception {
        flyway.clean();flyway.migrate();roles.seedBuiltin();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        root=Files.createDirectory(directory.resolve("project")).toRealPath();git.read(root,"init","-b","main","--template=");
        git.read(root,"config","user.name","Fixture");git.read(root,"config","user.email","fixture@example.invalid");
        Files.writeString(root.resolve("code.txt"),"original code\n");Files.writeString(root.resolve(".env"),"FIXTURE_ONLY=fake\n");
        for(int i=0;i<70;i++)Files.writeString(root.resolve(String.format(Locale.ROOT,"part%03d.txt",i)),"code "+i+"\n");
        git.read(root,"add",".");git.run(root,Duration.ofSeconds(10),List.of("commit","-m","fixture"),Map.of("GIT_AUTHOR_DATE","2026-09-11T01:00:00Z","GIT_COMMITTER_DATE","2026-09-11T01:00:00Z")).requireSuccess(List.of("commit"));
        project=projects.create("版本审查",root.toString(),"").id();builtin.publish();
    }
    @Test void confirmedBatchesUseConditionalReviewsAndProduceACompleteNavigableArchive()throws Exception {
        String id=create(true);var initial=plans.get(id,null).graph();capture(id);var proposal=propose(id);
        assertThat(plans.require(id).headRevision()).isEqualTo(1);assertThat(fake.createReadOnlySessionCalls()).isZero();
        assertThat(proposal.graph().nodes().stream().filter(n->WorkflowSnapshotWork.ANALYZE.equals(n.moduleId()))).hasSize(2);
        assertThat(controls.get(id).state()).isNotEqualTo("RUNNING");apply(id,proposal);
        var current=plans.get(id,null);var export=exports.preview(id,new WorkflowTemplateExport.PreviewRequest(current.revision(),WorkflowTemplateExport.Mode.CURRENT,current.graph(),current.layout()));
        assertThat(export.fixedPlanningNodes()).hasSize(1);assertThat(export.graph().nodes().stream().filter(n->n.id().equals("batches")).findFirst().orElseThrow().kind()).isEqualTo(NodeKind.HUMAN);
        assertThat(exports.preview(id,new WorkflowTemplateExport.PreviewRequest(current.revision(),WorkflowTemplateExport.Mode.INITIAL,current.graph(),current.layout())).graph()).isEqualTo(initial);
        analyze(id,true);String report=start(id,"report");var bundle=reports.bundle(report).orElseThrow();
        jdbc.update("UPDATE project SET name='执行中改名' WHERE id=?",project);
        var result=reportBuilder.build(reportStore.context(report));assertThat(reportBuilder.build(reportStore.context(report))).isEqualTo(result);
        assertThat(result.files().values()).anyMatch(body->body.contains("Fixture")&&body.contains("问题引入者：未确定"));
        assertThat(result.details().get("supportedCount")).isEqualTo(1);assertThat(result.reviewedCount()).isEqualTo(1);
        reportExecution.advance(report);assertThat(nodes.attempt(report).state()).isEqualTo("SUCCEEDED");assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(3);assertThat(nodes.hasStop(report)).isTrue();
        var archive=files.archive(files.output(id,"report",report,"document"));var paths=new ArrayList<String>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive),java.nio.charset.StandardCharsets.UTF_8)) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){paths.add(entry.getName());assertThat(new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("执行中改名");}
        }
        assertThat(paths).hasSize(6).allMatch(path->path.startsWith(bundle.folderName()+"/")&&path.endsWith(".md"));
        assertThat(result.files().get(result.details().get("mainPath"))).contains("%E","静态审查","未经独立复核");
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease",Integer.class)).isZero();
    }
    @Test void noFindingsSkipsEveryReviewAndStillCompletesTheReport()throws Exception {
        String id=create(true);capture(id);apply(id,propose(id));analyze(id,false);String report=start(id,"report");reportExecution.advance(report);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(fake.createReadOnlySessionCalls()).isEqualTo(2);
        assertThat(nodes.summaries(id,plans.require(id).headRevision()).stream().filter(n->n.state().equals("SKIPPED"))).hasSize(2);
        assertThat(documents.files(report)).anyMatch(f->f.content().contains("未发现经独立复核支持的问题"));
    }
    @Test void unchangedDateRangeCreatesNoModelAndKeepsACompleteRangeReport()throws Exception {
        incremental=true;String id=create(true);capture(id);var proposal=propose(id);
        assertThat(proposal.graph().nodes()).extracting(Node::id).containsExactly("source","batches","report");apply(id,proposal);
        String report=start(id,"report");reportExecution.advance(report);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(fake.createReadOnlySessionCalls()).isZero();
        assertThat(documents.files(report)).hasSize(4).anyMatch(f->f.content().contains("没有最终代码差异"));
    }
    @Test void explicitOmissionRetainsUnreviewedCandidatesAndRequiredPolicyRejectsMissingReviews()throws Exception {
        String id=create(false);capture(id);apply(id,propose(id));analyze(id,true);String report=start(id,"report");var context=reportStore.context(report);
        var result=reportBuilder.build(context);assertThat(result.reviewedCount()).isZero();assertThat(result.details().get("supportedCount")).isEqualTo(0);
        assertThat(result.files().values()).anyMatch(body->body.contains("仅候选，未经独立复核"));
        var n=context.node();var p=new LinkedHashMap<>(n.parameters());p.put("reviewPolicy","REQUIRED");
        var required=new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),n.inputs(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),p,n.roleRevisionId());
        assertThatThrownBy(()->reportBuilder.build(new WorkflowDocumentStore.Context(context.attempt(),required,context.inputs(),project))).hasMessageContaining("独立复核");
        var missing=context.inputs().values().stream().filter(i->!i.name().equals("analysis_2")).toList();
        var partial=new WorkflowDelivery.Inputs(1,id,context.inputs().planRevision(),"report",context.inputs().objective(),missing);
        assertThatThrownBy(()->reportBuilder.build(new WorkflowDocumentStore.Context(context.attempt(),n,partial,project))).hasMessageContaining("缺少分析批次");
        reportExecution.advance(report);assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void reportRetainsEvidenceWhenAuthorRepositoryIsUnavailable()throws Exception {
        String id=create(true);String capture=capture(id);apply(id,propose(id));analyze(id,true);
        Path repository=jobs.repository(nodes.attempt(capture).nodeRunId());Files.move(repository,repository.resolveSibling("offline-review.git"));
        String report=start(id,"report");reportExecution.advance(report);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(documents.files(report)).anyMatch(f->f.content().contains("作者追溯不可用")&&f.content().contains("候选问题"));
    }
    @Test void reportRollbackAndRecoveryKeepNumberAndFrozenInputs()throws Exception {
        String id=create(true);capture(id);apply(id,propose(id));analyze(id,false);String report=start(id,"report");var context=reportStore.context(report);var result=reportBuilder.build(context);var before=reports.bundle(report).orElseThrow();
        jdbc.execute("CREATE TRIGGER fail_snapshot_report BEFORE INSERT ON workflow_node_delivery WHEN NEW.attempt_id='"+report+"' BEGIN SELECT RAISE(ABORT,'snapshot rollback'); END");
        assertThatThrownBy(()->reportStore.finish(context,result,null)).hasStackTraceContaining("snapshot rollback");
        assertThat(documents.find(report)).isEmpty();assertThat(documents.files(report)).isEmpty();assertThat(nodes.hasStop(report)).isFalse();
        jdbc.execute("DROP TRIGGER fail_snapshot_report");reportExecution.advance(report);assertThat(reports.bundle(report).orElseThrow()).isEqualTo(before);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_snapshot_report_bundle SET sequence=sequence WHERE attempt_id=?",report)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_document WHERE attempt_id=?",report)).isInstanceOf(RuntimeException.class);
        assertThat(nodes.attempt(report).state()).isEqualTo("SUCCEEDED");
    }
    @Test void cancellationRejectsLatePlanAndReportWhileRetainingHistory()throws Exception {
        String id=create(true);capture(id);String planner=start(id,"batches");var context=planStore.context(planner);var result=planBuilder.build(context);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消"));drain.stop(id,planner);assertThat(finishes.finalizeReady(id)).isTrue();
        planStore.finish(context,planBuilder.delivery(result,null),true);assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();
        String next=create(true);capture(next);apply(next,propose(next));analyze(next,false);String report=start(next,"report");var rc=reportStore.context(report);var rendered=reportBuilder.build(rc);
        finishes.request(next,new WorkflowFinishes.Request(key(),plans.require(next).version(),WorkflowState.CANCELLED,"取消报告"));
        assertThatThrownBy(()->reportStore.finish(rc,rendered,null)).isInstanceOf(RuntimeException.class);assertThat(documents.find(report)).isEmpty();
        drain.stop(next,report);assertThat(finishes.finalizeReady(next)).isTrue();assertThat(reports.bundle(report)).isPresent();
    }
    @Test void planningPreservesConfigurationAndRejectsUnconditionalReviewBypassAndTruncation()throws Exception {
        String id=create(true);capture(id);String planner=start(id,"batches");var context=planStore.context(planner);var graph=context.plan().graph();
        var changed=graph.nodes().stream().map(n->WorkflowSnapshotWork.ANALYZE.equals(n.moduleId())?new Node(n.id(),"自定义分析",n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),"固定自定义任务",n.inputs(),n.outputs(),n.outcomes(),n.completion(),3,true,n.parameters(),n.roleRevisionId()):n).toList();
        var configured=context(context,new WorkflowGraph(1,changed,graph.edges(),graph.inputs()));var result=planBuilder.build(configured);
        assertThat(result.proposal().graph().nodes().stream().filter(n->WorkflowSnapshotWork.ANALYZE.equals(n.moduleId()))).allSatisfy(n->{assertThat(n.maxRetries()).isEqualTo(3);assertThat(n.pauseAfter()).isTrue();assertThat(n.task()).isEqualTo("固定自定义任务");assertThat(n.roleRevisionId()).isNotBlank();});
        var bypass=new ArrayList<>(graph.edges());bypass.add(new Edge("bypass","source","review",null));
        assertThatThrownBy(()->planBuilder.build(context(context,new WorkflowGraph(1,graph.nodes(),bypass,graph.inputs())))).hasMessageContaining("有问题进入复核");
        var source=snapshotInputs.source(context.inputs());var fixed=evidence.read(source.manifest());var unit=fixed.units().stream().filter(u->!u.excerpt().isBlank()).findFirst().orElseThrow();var many=new ArrayList<SnapshotReview.Unit>();
        for(int i=0;i<2049;i++)many.add(new SnapshotReview.Unit("unit"+i,"code-"+i,null,unit.change(),unit.excerpt(),null,unit.initialEvidence()));
        var large=new SnapshotReview.Snapshot(fixed.sourceSha(),fixed.baselineSha(),fixed.targetSha(),fixed.baselineTree(),fixed.targetTree(),fixed.capturedAt(),fixed.startInclusive(),fixed.endExclusive(),fixed.selectionBasis(),fixed.nonMonotonic(),fixed.noChanges(),fixed.files(),many,fixed.scopeIdentity());
        var mock=org.mockito.Mockito.mock(WorkflowSnapshotEvidence.class);org.mockito.Mockito.when(mock.read(source.manifest())).thenReturn(large);
        assertThatThrownBy(()->new WorkflowSnapshotPlanBuilder(snapshotInputs,mock,new WorkflowEncoding(json)).build(context)).hasMessageContaining("未截断");
        assertThat(nodes.findDelivery(planner)).isEmpty();
    }
    @Test void largeAcceptedAnalysisRemainsUsableAsFrozenReviewAndReportInput()throws Exception {
        String id=create(true);capture(id);apply(id,propose(id));String run=start(id,"analysis");running(run);var input=read(run);var ref=input.batch().units().getFirst().initialEvidence().getFirst();var base=analysis(input,List.of(finding(ref)));
        var large=new SnapshotReview.Analysis(base.coverage(),base.findings(),List.of(),Collections.nCopies(60,"界".repeat(2000)));submit(run,large);finish(run);
        assertThat(nodes.delivery(run).contentJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(300*1024);
        String review=start(id,"review");running(review);var ri=read(review);assertThat(ri.analysis().limitations()).hasSize(60);submit(review,review(ri,ref));finish(review);
        for(var n:plans.get(id,null).graph().nodes())if(WorkflowSnapshotWork.ANALYZE.equals(n.moduleId())&&!n.id().equals("analysis")){String next=start(id,n.id());running(next);submit(next,analysis(read(next),List.of()));finish(next);}
        String report=start(id,"report");reportExecution.advance(report);assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(WorkflowDeliveries.limit("free.readonly")).isEqualTo(128*1024);
    }
    @Test void partialReportWaitsForActualStopAndRetainsCandidatesAfterCancellation()throws Exception {
        String id=create(true),source=capture(id);var initial=partialReports.read(id,"source",source);
        assertThat(initial.analyzedUnits()).isZero();assertThat(initial.pendingUnits()).isEqualTo(71);assertThat(initial.excludedUnits()).isEqualTo(1);
        assertThat(initial.content()).contains("非完整报告","尚未完成分析");assertThat(fake.createReadOnlySessionCalls()).isZero();
        apply(id,propose(id));String run=start(id,"analysis");running(run);var input=read(run);
        submit(run,analysis(input,List.of(finding(input.batch().units().getFirst().initialEvidence().getFirst()))));
        assertThat(nodes.findDelivery(run)).isPresent();assertThat(nodes.hasStop(run)).isFalse();
        assertThat(partialReports.read(id,"source",source).analyzedUnits()).isZero();finish(run);
        var before=plans.require(id);var report=partialReports.read(id,"source",source);
        assertThat(report.analyzedUnits()).isEqualTo(input.batch().units().size());assertThat(report.pendingUnits()).isEqualTo(71-report.analyzedUnits());
        assertThat(report.content()).contains("尚未独立复核（仅候选）","Fixture","问题引入者：未确定").doesNotContain("独立复核支持");
        assertThat(plans.require(id)).isEqualTo(before);assertThat(report.sha256()).isEqualTo(WorkflowEncoding.hash(report.content()));
        finishes.request(id,new WorkflowFinishes.Request(key(),before.version(),WorkflowState.CANCELLED,"停止后保留阶段证据"));assertThat(finishes.finalizeReady(id)).isTrue();
        int sessions=fake.createReadOnlySessionCalls();var cancelled=partialReports.read(id,"source",source);
        assertThat(cancelled.analyzedUnits()).isEqualTo(report.analyzedUnits());assertThat(cancelled.content()).contains("已取消","尚未独立复核（仅候选）");
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(sessions);assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_document",Integer.class)).isZero();
    }
    @Test void partialReportRequiresSameSourceScopeAndDoesNotPromoteUnfinishedReview()throws Exception {
        String id=create(true),source=capture(id);queries.reset();partialReads.capture(id,"source",source);int emptyQueries=queries.count();
        apply(id,propose(id));String run=start(id,"analysis");running(run);var input=read(run);var ref=input.batch().units().getFirst().initialEvidence().getFirst();
        submit(run,analysis(input,List.of(finding(ref))));finish(run);String review=start(id,"review");running(review);submit(review,review(read(review),ref));
        assertThat(partialReports.read(id,"source",source).content()).doesNotContain("独立复核支持");finish(review);
        queries.reset();assertThat(partialReads.capture(id,"source",source).results()).hasSize(2);assertThat(queries.count()).isEqualTo(emptyQueries).isLessThanOrEqualTo(12);
        var report=partialReports.read(id,"source",source);assertThat(report.content()).contains("独立复核支持","源码支持");assertThat(report.pendingUnits()).isPositive();
        String other=create(true);assertThatThrownBy(()->partialReports.read(other,"source",source)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->partialReports.read(id,"analysis",source)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->partialReports.read(id,"analysis",run)).isInstanceOf(ConflictException.class);
        String nextSource=capture(other);assertThat(partialReports.read(other,"source",nextSource).analyzedUnits()).isZero();
    }
    @Test void partialReportUsesCurrentPlanBindingsAndPreservesRemovedHistory()throws Exception {
        String id=create(true),source=capture(id);apply(id,propose(id));String run=start(id,"analysis");running(run);submit(run,analysis(read(run),List.of()));finish(run);
        var old=partialReads.capture(id,"source",source);assertThat(old.results()).hasSize(1);var before=plans.get(id,null);var graph=before.graph();
        var removed=Set.of("analysis","review","report");
        var after=new WorkflowGraph(1,graph.nodes().stream().filter(n->!removed.contains(n.id())).toList(),graph.edges().stream().filter(e->!removed.contains(e.from())&&!removed.contains(e.to())).toList(),graph.inputs());
        revisions.revise(id,new WorkflowRequests.RevisePlan(key(),plans.require(id).version(),before.revision(),after));
        var current=partialReports.read(id,"source",source);assertThat(current.planRevision()).isEqualTo(before.revision()+1);assertThat(current.analyzedUnits()).isZero();assertThat(current.pendingUnits()).isEqualTo(71);
        assertThat(nodes.delivery(run)).isNotNull();assertThat(old.results()).hasSize(1);assertThat(partialReads.capture(id,"source",source).results()).isEmpty();
    }
    @Test void failedFlowPartialReportRemainsReadableOfflineWithoutCreatingWork()throws Exception {
        String id=create(false),source=capture(id);apply(id,propose(id));String run=start(id,"analysis");running(run);var input=read(run);submit(run,analysis(input,List.of(finding(input.batch().units().getFirst().initialEvidence().getFirst()))));finish(run);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.FAILED,"人工结束"));assertThat(finishes.finalizeReady(id)).isTrue();
        Path repository=jobs.repository(nodes.attempt(source).nodeRunId());Files.move(repository,repository.resolveSibling("offline-partial.git"));Files.move(root,root.resolveSibling("offline-project"));
        int sessions=fake.createReadOnlySessionCalls();var before=plans.require(id);var report=partialReports.read(id,"source",source);
        assertThat(report.content()).contains("已失败","作者追溯不可用","候选问题");assertThat(report.pendingUnits()).isPositive();assertThat(plans.require(id)).isEqualTo(before);assertThat(fake.createReadOnlySessionCalls()).isEqualTo(sessions);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_document",Integer.class)).isZero();
    }
    private WorkflowSourcePlanStore.Context context(WorkflowSourcePlanStore.Context c,WorkflowGraph graph){return new WorkflowSourcePlanStore.Context(c.attempt(),c.node(),c.inputs(),c.project(),new WorkflowPlanCandidates.Context(c.plan().baseRevision(),c.node().id(),c.plan().editableNodeKeys(),graph,c.plan().instruction()));}
    private String create(boolean reviews) {
        String template=incremental?"builtin.workflow.snapshot-review":"builtin.workflow.snapshot-full";
        var source=templates.get(template,null);if(!reviews){var graph=source.graph();var changed=graph.nodes().stream().filter(n->!n.id().equals("review")).map(n->{if(!n.id().equals("report"))return n;var p=new LinkedHashMap<>(n.parameters());p.put("reviewPolicy","NONE");return new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),n.inputs().stream().filter(i->!i.name().equals("review")).toList(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),p,n.roleRevisionId());}).toList();
            var edges=graph.edges().stream().filter(e->!e.from().equals("review")&&!e.to().equals("review")).map(e->e.from().equals("analysis")?new Edge(e.id(),e.from(),e.to(),null):e).toList();
            var custom=templates.create(new WorkflowRequests.CreateTemplate(key(),"自定义无复核","",new WorkflowGraph(1,changed,edges,graph.inputs()),CanvasLayout.empty()));template=custom.id();source=templates.get(template,null);}
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"完整版本审查","使用固定资料",template,source.revision()));plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private WorkflowPlanCandidates.Detail propose(String id){String run=start(id,"batches");planExecution.advance(run);assertThat(nodes.attempt(run).state()).as(nodes.findDelivery(run).map(WorkflowExecutionRows.Delivery::contentJson).orElse("")).isEqualTo("SUCCEEDED");return candidates.get(id,candidates.list(id,"PENDING",null,50).items().getFirst().id());}
    private void apply(String id,WorkflowPlanCandidates.Detail proposal){candidates.apply(id,proposal.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),plans.require(id).headRevision(),proposal.version(),null));}
    private void analyze(String id,boolean findings) {
        var graph=plans.get(id,null).graph();
        for(var n:graph.nodes())if(WorkflowSnapshotWork.ANALYZE.equals(n.moduleId())){String run=start(id,n.id());running(run);var input=read(run);submit(run,analysis(input,findings&&input.batchOrdinal()==0?List.of(finding(input.batch().units().getFirst().initialEvidence().getFirst())):List.of()));finish(run);}
        for(var n:graph.nodes())if(WorkflowSnapshotWork.REVIEW.equals(n.moduleId())&&!nodes.node(id,plans.require(id).headRevision(),n.id()).state().equals("SKIPPED")){String run=start(id,n.id());running(run);var input=read(run);submit(run,review(input,input.analysis().findings().getFirst().evidence().getFirst()));finish(run);}
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
    @org.springframework.boot.test.context.TestConfiguration
    static class CountingConfiguration {
        @org.springframework.context.annotation.Bean QueryCounter queryCounter(){return new QueryCounter();}
    }
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="query",
        args={org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class}))
    static final class QueryCounter implements org.apache.ibatis.plugin.Interceptor {
        private final java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger();
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation call)throws Throwable{count.incrementAndGet();return call.proceed();}
        void reset(){count.set(0);}int count(){return count.get();}
    }
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-snapshot-flow-tests-");}catch(Exception invalid){throw new ExceptionInInitializerError(invalid);}}
}
