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
import java.nio.charset.StandardCharsets;
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
class WorkflowDocumentReviewIntegrationTest {
    private static final Path DATA=data();
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("reviews.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired WorkflowPlanTemplates exports;
    @Autowired WorkflowBuiltinFlows builtins;
    @Autowired WorkflowSourcePlanExecution planExecution;
    @Autowired WorkflowSourcePlanStore planStore;
    @Autowired WorkflowDocumentPlanBuilder planBuilder;
    @Autowired WorkflowPlanCandidates candidates;
    @Autowired WorkflowDocumentExecution reportExecution;
    @Autowired WorkflowDocumentStore reportStore;
    @Autowired WorkflowDocumentFiles reportFiles;
    @Autowired WorkflowAssessmentReportBuilder reportBuilder;
    @Autowired WorkflowFinishDrain drain;
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowControls controls;
    @Autowired WorkflowDispatchExecution dispatch;
    @Autowired WorkflowCommandExecution commands;
    @Autowired WorkflowCommandStore commandStore;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowNodePresets presets;
    @Autowired WorkflowUploads uploads;
    @Autowired WorkflowUploadMapper uploaded;
    @Autowired WorkflowRepositoryMapper repositories;
    @Autowired WorkflowModelExecution execution;
    @Autowired WorkflowModelStore models;
    @Autowired WorkflowModelTools tools;
    @Autowired WorkflowRuntimeSupport identity;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired OpenCodeClient client;
    @Autowired WorkflowFinishes finishes;
    @Autowired WorkflowDocumentReviewContract contract;
    @Autowired WorkflowDocumentReviewContext contexts;
    @Autowired RolePublishingService roles;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    String project,head,id,author,blob;WorkflowUpload.Reference documents;FakeOpenCodeClient fake;
    @BeforeEach void setup()throws Exception {
        flyway.clean();flyway.migrate();roles.seedBuiltin();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        Path root=Files.createDirectory(directory.resolve("project")).toRealPath();var git=new GitEvidenceProcess(new SafeProcessRunner());
        git.read(root,"init","-b","main","--template=");git.read(root,"config","user.name","Fixture");git.read(root,"config","user.email","fixture@example.invalid");
        Files.writeString(root.resolve("code.txt"),"boolean allowed = amount > 0;\n");git.read(root,"add",".");git.read(root,"commit","-m","fixture");
        head=git.read(root,"rev-parse","HEAD").strip();blob=git.read(root,"rev-parse","HEAD:code.txt").strip();project=projects.create("需求代码评审",root.toString(),"").id();
    }
    @Test void exactIndependentReadsAndFixedDraftAreRequiredBeforeEachRoleCanFinish()throws Exception {
        prepare(true);var candidate=candidate("boolean allowed = amount > 0;",blob,List.of());
        assertThatThrownBy(()->submit(author,candidate,null)).isInstanceOf(BadRequestException.class).hasMessageContaining("完整读取");
        readDoc(author,1,200);assertThatThrownBy(()->submit(author,candidate,null)).hasMessageContaining("完整读取");
        readDoc(author,201,200);assertThatThrownBy(()->submit(author,candidate,null)).hasMessageContaining("引用未由本次");
        readCode(author);submit(author,candidate,null);
        assertThat(nodes.attempt(author).state()).isEqualTo("RUNNING");assertThat(nodes.hasStop(author)).isFalse();
        assertThat(json.readTree(nodes.delivery(author).contentJson()).path("outputs").path("assessment").path("content").path("snapshotSha").asText()).isEqualTo(head);
        finish(author);String reviewer=start("review");running(reviewer);
        assertThatThrownBy(()->submit(reviewer,review(true),"PASS")).hasMessageContaining("完整读取本次绑定");
        readDraft(reviewer,0,12000);assertThatThrownBy(()->submit(reviewer,review(true),"PASS")).hasMessageContaining("完整读取原文");
        allDocs(reviewer);assertThatThrownBy(()->submit(reviewer,review(true),"PASS")).hasMessageContaining("引用未由本次");
        readCode(reviewer);submit(reviewer,review(true),"PASS");finish(reviewer);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(count("task")).isZero();assertThat(count("workspace_lease")).isZero();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        assertThatThrownBy(()->call(reviewer,WorkflowModelProfile.FILE,Map.of("name","code","path","code.txt","blobSha",blob))).isInstanceOf(ConflictException.class);
    }
    @Test void invalidEvidenceAndUnresolvedBusinessRulesCanBeCorrectedInTheSameAttempt()throws Exception {
        prepare(true);allDocs(author);readCode(author);
        assertThatThrownBy(()->submit(author,candidate("invented text",blob,List.of()),null)).hasMessageContaining("摘录必须逐字");
        assertThatThrownBy(()->submit(author,candidate("boolean allowed", "0".repeat(40),List.of()),null)).hasMessageContaining("哈希不匹配");
        assertThatThrownBy(()->submit(author,candidate("boolean allowed",blob,List.of("金额单位尚未确认")),null)).hasMessageContaining("UNDETERMINED");
        var uncovered=new DirectDocumentAssessment.Candidate(null,List.of(),List.of(),List.of(),List.of());
        assertThatThrownBy(()->submit(author,uncovered,null)).hasMessageContaining("未覆盖原文章节");
        assertThat(nodes.findDelivery(author)).isEmpty();assertThat(nodes.attempt(author).state()).isEqualTo("RUNNING");
        assertThatThrownBy(()->call(author,WorkflowModelProfile.FILE,Map.of())).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->call(author,WorkflowModelProfile.FILE,Map.of("name","documents","path","parsed/01/0001.md","sha256","0".repeat(64)))).isInstanceOf(BadRequestException.class);
        submit(author,candidate("boolean allowed",blob,List.of()),null);finish(author);assertThat(nodes.attempt(author).state()).isEqualTo("SUCCEEDED");
    }
    @Test void userMayCompleteWithARealRevisionOpinionWithoutInventingApproval()throws Exception {
        prepare(false);allDocs(author);readCode(author);submit(author,candidate("boolean allowed",blob,List.of()),null);finish(author);
        String reviewer=start("review");running(reviewer);allDocs(reviewer);
        readDraft(reviewer,0,10);assertThatThrownBy(()->submit(reviewer,review(false),"REVISE")).hasMessageContaining("完整读取本次绑定");
        readDraft(reviewer,10,12000);assertThatThrownBy(()->submit(reviewer,review(false),"PASS")).hasMessageContaining("节点结果必须");
        submit(reviewer,review(false),"REVISE");finish(reviewer);assertThat(nodes.attempt(reviewer).state()).isEqualTo("SUCCEEDED");
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(json.readTree(nodes.delivery(reviewer).contentJson()).path("outputs").path("review").path("content").path("approved").asBoolean()).isFalse();
    }
    @Test void receiptHistoryIsImmutableAndCancellationRejectsLateReadsWithoutNewEvidence()throws Exception {
        prepare(true);allDocs(author);readCode(author);int before=count("workflow_document_read");
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_document_read SET content='changed' WHERE attempt_id=?",author)).hasStackTraceContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_document_read WHERE attempt_id=?",author)).hasStackTraceContaining("retained");
        var scope=identity.grant(models.require(author));finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消后续评审"));
        assertThatThrownBy(()->tools.call(WorkflowModelProfile.FILE,Map.of("scope",scope,"attemptId",author,"args",Map.of("name","code","path","code.txt","blobSha",blob)))).isInstanceOf(ConflictException.class);
        assertThat(count("workflow_document_read")).isEqualTo(before);assertThat(nodes.findDelivery(author)).isEmpty();
        assertThatThrownBy(()->jdbc.update("INSERT INTO workflow_document_read VALUES(?,'code','code.txt',?,1,1,1,'late','t')",author,blob)).isInstanceOf(RuntimeException.class);
    }
    @Test void failedAcceptanceTransactionLeavesEvidenceAndAllowsSafeResubmission()throws Exception {
        prepare(true);allDocs(author);readCode(author);
        jdbc.execute("CREATE TRIGGER fail_review_delivery BEFORE INSERT ON workflow_node_delivery BEGIN SELECT RAISE(ABORT,'review rollback'); END");
        assertThatThrownBy(()->submit(author,candidate("boolean allowed",blob,List.of()),null)).hasStackTraceContaining("review rollback");
        assertThat(nodes.findDelivery(author)).isEmpty();assertThat(nodes.hasStop(author)).isFalse();assertThat(nodes.attempt(author).state()).isEqualTo("RUNNING");
        jdbc.execute("DROP TRIGGER fail_review_delivery");submit(author,candidate("boolean allowed",blob,List.of()),null);finish(author);
        assertThat(nodes.attempt(author).state()).isEqualTo("SUCCEEDED");assertThat(count("workflow_document_read")).isEqualTo(3);
    }
    @Test void selectedChapterAndBatchIdentityCannotChangeUnderAnIndependentReview()throws Exception {
        prepare(true);allDocs(author);readCode(author);submit(author,candidate("boolean allowed",blob,List.of()),null);finish(author);
        String reviewer=start("review");running(reviewer);var definition=models.definition(models.require(reviewer));var inputs=nodes.inputs(nodes.attempt(reviewer));
        var parameters=new LinkedHashMap<>(definition.parameters());parameters.put("documentBatchOrdinal","1");
        assertThatThrownBy(()->contract.context(parameters(definition,parameters),inputs)).hasMessageContaining("批次序号不一致");
        parameters.put("documentSections","[{\"fileId\":\"DOC-1\",\"section\":2}]");
        assertThatThrownBy(()->contexts.resolve(parameters(definition,parameters),inputs)).hasMessageContaining("章节不属于");
        parameters.put("documentSections","[{\"fileId\":\"DOC-1\",\"section\":1},{\"fileId\":\"DOC-1\",\"section\":1}]");
        assertThatThrownBy(()->contexts.resolve(parameters(definition,parameters),inputs)).hasMessageContaining("单批范围");
    }
    @Test void expandedDocumentPlanCanSaveFixedBatchesOrRestoreItsFirstExecutionStructure()throws Exception {
        combined(false);var initial=plans.get(id,null).graph();String planner=start("batches");planExecution.advance(planner);var proposal=pending();
        candidates.apply(id,proposal.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,proposal.version(),null));
        var current=plans.get(id,null);int attempts=count("workflow_node_attempt");
        var selection=new WorkflowTemplateExport.PreviewRequest(current.revision(),WorkflowTemplateExport.Mode.CURRENT,current.graph(),current.layout());
        var fixed=exports.preview(id,selection);assertThat(fixed.fixedPlanningNodes()).hasSize(1);
        var confirmation=fixed.graph().nodes().stream().filter(n->n.id().equals("batches")).findFirst().orElseThrow();
        assertThat(confirmation.kind()).isEqualTo(NodeKind.HUMAN);assertThat(confirmation.moduleId()).isNull();
        assertThat(fixed.graph().edges()).isEqualTo(current.graph().edges());
        assertThat(fixed.graph().nodes().stream().filter(n->!n.id().equals("batches")).toList()).isEqualTo(current.graph().nodes().stream().filter(n->!n.id().equals("batches")).toList());
        var saved=exports.save(id,new WorkflowTemplateExport.Save(key(),"固定批次","",selection,fixed.sha256()));
        assertThat(templates.get(saved.id(),null).graph()).isEqualTo(fixed.graph());
        var generic=exports.preview(id,new WorkflowTemplateExport.PreviewRequest(current.revision(),WorkflowTemplateExport.Mode.INITIAL,current.graph(),current.layout()));
        assertThat(generic.graph()).isEqualTo(initial);assertThat(generic.sourceRevision()).isEqualTo(1);
        assertThat(generic.fixedPlanningNodes()).isEmpty();assertThat(plans.get(id,null)).isEqualTo(current);assertThat(count("workflow_node_attempt")).isEqualTo(attempts);
        var steps=new ArrayList<>(current.graph().nodes());var target=steps.getLast();var inputs=new ArrayList<>(target.inputs());
        inputs.add(new Input("planningReport",InputSource.NODE,"batches","report",DataKind.JSON,false));
        steps.set(steps.size()-1,new Node(target.id(),target.title(),target.kind(),target.moduleId(),target.moduleVersion(),target.roleId(),target.task(),inputs,target.outputs(),target.outcomes(),target.completion(),target.maxRetries(),target.pauseAfter(),target.parameters(),target.roleRevisionId()));
        var consuming=new WorkflowGraph(1,steps,current.graph().edges(),current.graph().inputs());
        assertThatThrownBy(()->exports.preview(id,new WorkflowTemplateExport.PreviewRequest(current.revision(),WorkflowTemplateExport.Mode.CURRENT,consuming,current.layout()))).hasMessageContaining("规划输出仍被使用");
    }
    @Test void fullOriginalPlanWaitsForConfirmationThenIndependentBatchesProduceFixedReport()throws Exception {
        combined(true);String planner=start("batches");planExecution.advance(planner);
        assertThat(nodes.attempt(planner).state()).isEqualTo("SUCCEEDED");assertThat(plans.require(id).headRevision()).isEqualTo(1);
        assertThat(count("workflow_model_launch")).isZero();var proposal=pending();assertThat(proposal.graph().nodes()).hasSize(7);
        assertThat(proposal.graph().nodes().stream().filter(n->WorkflowDocumentReview.REVIEW.equals(n.moduleId()))).allSatisfy(n->assertThat(n.inputs().stream().filter(i->i.kind()==DataKind.JSON).count()).isEqualTo(2));
        candidates.apply(id,proposal.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,proposal.version(),null));
        assertThat(count("workflow_model_launch")).isZero();assertThat(plans.require(id).headRevision()).isEqualTo(2);
        for(var node:proposal.graph().nodes())if(WorkflowDocumentReview.AUTHOR.equals(node.moduleId()))completeOriginal(node,false);
        for(var node:proposal.graph().nodes())if(WorkflowDocumentReview.REVIEW.equals(node.moduleId()))completeOriginal(node,true);
        String run=start("report");reportExecution.advance(run);assertThat(nodes.attempt(run).state()).as(nodes.delivery(run).contentJson()).isEqualTo("SUCCEEDED");
        var delivery=json.readTree(nodes.delivery(run).contentJson());var reference=json.treeToValue(delivery.path("outputs").path("document").path("content"),WorkflowDocument.Reference.class);
        var bundle=reportFiles.bundle(project,id,run,reference);assertThat(bundle.keySet()).containsExactlyInAnyOrder("summary.md","matrix.json","RQ-1.md","RQ-257.md");
        var matrix=json.readTree(bundle.get("matrix.json"));assertThat(matrix.path("independentReviewComplete").asBoolean()).isTrue();assertThat(matrix.path("sections").size()).isEqualTo(proposal.graph().nodes().stream().filter(n->WorkflowDocumentReview.AUTHOR.equals(n.moduleId())).mapToInt(n->json.readTree(n.parameters().get("documentSections")).size()).sum());
        assertThat(new String(bundle.get("summary.md"),StandardCharsets.UTF_8)).contains("通过 2 批","本次未运行");assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        assertThatThrownBy(()->reportFiles.manifest(project,id,run,new WorkflowDocument.Reference(1,WorkflowDocument.TYPE,run,reference.sha256()))).isInstanceOf(ConflictException.class);
    }
    @Test void optionalReviewReportsActualStateAndMissingBatchCannotClaimCompleteCoverage()throws Exception {
        combined(false);String planner=start("batches");planExecution.advance(planner);var proposal=pending();
        assertThat(proposal.graph().nodes()).noneMatch(n->WorkflowDocumentReview.REVIEW.equals(n.moduleId()));
        candidates.apply(id,proposal.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,proposal.version(),null));
        for(var node:proposal.graph().nodes())if(WorkflowDocumentReview.AUTHOR.equals(node.moduleId()))completeOriginal(node,false);
        String run=start("report");var context=reportStore.context(run);var inputs=context.inputs();
        var incomplete=new WorkflowDelivery.Inputs(inputs.version(),inputs.requirementId(),inputs.planRevision(),inputs.nodeId(),inputs.objective(),inputs.values().stream().filter(i->!i.name().equals("draft_2")).toList());
        assertThatThrownBy(()->reportBuilder.build(new WorkflowDocumentStore.Context(context.attempt(),context.node(),incomplete,context.project()))).hasMessageContaining("全部固定原文章节");
        jdbc.execute("CREATE TRIGGER fail_report BEFORE INSERT ON workflow_node_delivery BEGIN SELECT RAISE(ABORT,'report rollback'); END");
        assertThatThrownBy(()->reportExecution.advance(run)).hasStackTraceContaining("report rollback");assertThat(count("workflow_document")).isZero();assertThat(nodes.hasStop(run)).isFalse();
        jdbc.execute("DROP TRIGGER fail_report");reportExecution.advance(run);reportExecution.advance(run);assertThat(count("workflow_document")).isEqualTo(1);
        var output=json.readTree(nodes.delivery(run).contentJson()).path("outputs");assertThat(output.path("report").path("content").path("reviewedCount").asInt()).isZero();
        var reference=json.treeToValue(output.path("document").path("content"),WorkflowDocument.Reference.class);String summary=new String(reportFiles.bundle(project,id,run,reference).get("summary.md"),StandardCharsets.UTF_8);
        assertThat(summary).contains("未复核 2 批").doesNotContain("已完成静态分析与独立复核");
    }
    @Test void cancelledOriginalPlanCannotPublishLateCandidate()throws Exception {
        combined(true);String run=start("batches");var context=planStore.context(run);var result=planBuilder.build(context);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消"));drain.stop(id,run);
        planStore.finish(context,planBuilder.delivery(result,null),true);assertThat(nodes.findDelivery(run)).isEmpty();assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();
    }
    @Test void userMayKeepLocalReviewsWithoutClaimingCompleteCrossBatchReview()throws Exception {
        combined(true);String planner=start("batches");planExecution.advance(planner);var proposal=pending();
        var steps=proposal.graph().nodes().stream().map(n->{
            if(WorkflowDocumentReview.REVIEW.equals(n.moduleId()))return new Node(n.id(),n.title(),n.kind(),n.moduleId(),1,n.roleId(),n.task(),n.inputs().stream().filter(i->i.kind()!=DataKind.JSON||i.name().equals("draft")).toList(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),n.parameters(),n.roleRevisionId());
            if(WorkflowDocument.ASSESSMENT_MODULE.equals(n.moduleId()))return parameters(n,Map.of("reviewPolicy","NONE"));return n;
        }).toList();var graph=new WorkflowGraph(1,steps,proposal.graph().edges(),proposal.graph().inputs());
        candidates.apply(id,proposal.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,proposal.version(),graph));
        for(var node:steps)if(WorkflowDocumentReview.AUTHOR.equals(node.moduleId()))completeOriginal(node,false);
        for(var node:steps)if(WorkflowDocumentReview.REVIEW.equals(node.moduleId()))completeOriginal(node,true);
        String run=start("report");var context=reportStore.context(run);
        assertThatThrownBy(()->reportBuilder.build(new WorkflowDocumentStore.Context(context.attempt(),parameters(context.node(),Map.of("reviewPolicy","REQUIRED")),context.inputs(),context.project()))).hasMessageContaining("跨批次核对");
        reportExecution.advance(run);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");
        var output=json.readTree(nodes.delivery(run).contentJson()).path("outputs");assertThat(output.path("report").path("content").path("reviewedCount").asInt()).isEqualTo(2);assertThat(output.path("report").path("content").path("crossBatchReviewedCount").asInt()).isZero();
        var reference=json.treeToValue(output.path("document").path("content"),WorkflowDocument.Reference.class);var matrix=json.readTree(reportFiles.bundle(project,id,run,reference).get("matrix.json"));assertThat(matrix.path("independentReviewComplete").asBoolean()).isFalse();
    }
    @Test void originalCandidateCaptureRollsBackAndThenResumesInTheSameAttempt()throws Exception {
        combined(true);String run=start("batches");
        jdbc.execute("CREATE TRIGGER fail_original_plan BEFORE INSERT ON workflow_plan_candidate BEGIN SELECT RAISE(ABORT,'original plan rollback'); END");
        assertThatThrownBy(()->planExecution.advance(run)).hasStackTraceContaining("original plan rollback");
        assertThat(nodes.findDelivery(run)).isEmpty();assertThat(nodes.hasStop(run)).isFalse();assertThat(nodes.attempt(run).state()).isEqualTo("RUNNING");
        jdbc.execute("DROP TRIGGER fail_original_plan");planExecution.advance(run);planExecution.advance(run);assertThat(candidates.list(id,"PENDING",null,50).items()).hasSize(1);
    }
    @Test void completedInputsDoNotAllowReportPublicationAfterCancellation()throws Exception {
        combined(false);String planner=start("batches");planExecution.advance(planner);var proposal=pending();
        candidates.apply(id,proposal.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,proposal.version(),null));
        for(var node:proposal.graph().nodes())if(WorkflowDocumentReview.AUTHOR.equals(node.moduleId()))completeOriginal(node,false);
        String run=start("report");var context=reportStore.context(run);var result=reportBuilder.build(context);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消报告"));drain.stop(id,run);
        reportStore.finish(context,result,null);assertThat(count("workflow_document")).isZero();assertThat(nodes.findDelivery(run)).isEmpty();
    }
    private WorkflowPlanCandidates.Detail pending(){return candidates.get(id,candidates.list(id,"PENDING",null,50).items().getFirst().id());}
    private void combined(boolean review)throws Exception {
        builtins.publish();var built=templates.get("builtin.workflow.document-review",null);var graph=built.graph();
        if(!review) {
            var steps=graph.nodes().stream().filter(n->!n.id().equals("reviewer")).map(n->{if(!n.id().equals("report"))return n;
                return new Node(n.id(),n.title(),n.kind(),n.moduleId(),1,null,n.task(),n.inputs().stream().filter(i->i.kind()!=DataKind.DECISION).toList(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),Map.of("reviewPolicy","NONE"));}).toList();
            var edges=new ArrayList<>(graph.edges().stream().filter(e->!e.from().equals("reviewer")&&!e.to().equals("reviewer")).toList());edges.add(new Edge("author-report","author","report",null));graph=new WorkflowGraph(1,steps,edges,graph.inputs());
        }
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"完整原文评审","",graph,CanvasLayout.empty()));var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"评审需求","核对金额",template.id(),1));id=owner.id();plans.confirm(id,new WorkflowRequests.VersionCommand(key(),owner.version()));
        String text="# 金额规则\n"+"金额必须大于零。".repeat(4000)+"\n# 订单规则\n"+"订单金额须为正。".repeat(4000);
        documents=uploads.upload(id,new WorkflowUploadStore.Request(key(),plans.require(id).version(),1),List.of(new DocumentTemplateStorage.Incoming("需求.md",text.getBytes(StandardCharsets.UTF_8)))).reference();
        String capture=start("repository");long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(!WorkflowAttemptState.valueOf(nodes.attempt(capture).state()).terminal()&&System.nanoTime()<deadline){commands.advance(capture);Thread.sleep(25);}
        assertThat(nodes.attempt(capture).state()).isEqualTo("SUCCEEDED");
    }
    private void completeOriginal(Node node,boolean review)throws Exception {
        String run=start(node.id());running(run);var sources=List.of(json.readValue(node.parameters().get("documentSections"),DirectDocumentAssessment.Source[].class));
        for(var source:sources){
        String path=String.format(Locale.ROOT,"parsed/01/%04d.md",source.section()),hash=uploaded.file(documents.uploadId(),path).orElseThrow().sha256();
        call(run,WorkflowModelProfile.FILE,Map.of("name","documents","path",path,"sha256",hash,"startLine",1,"lineCount",200));}readCode(run);
        String key="RQ-"+(Integer.parseInt(node.parameters().get("documentBatchOrdinal"))*256+1);
        if(review) {
            for(var input:node.inputs())if(input.kind()==DataKind.JSON)call(run,WorkflowModelProfile.INPUT,Map.of("name",input.name(),"offset",0,"limit",12000));
            submit(run,new DirectDocumentAssessment.Review(null,true,List.of(key),List.of(),sources,List.of()),"PASS");
        }else {
            var item=new RequirementCodeAssessment.Item(key,RequirementCodeAssessment.Conclusion.SATISFIED,"正数校验存在",List.of(new RequirementCodeAssessment.CodeReference("code.txt",blob,1,1,"boolean allowed")),List.of("code.txt"),null,"静态未运行",List.of());
            submit(run,new DirectDocumentAssessment.Candidate(null,List.of(new DirectDocumentAssessment.Entry("金额","金额须为正",sources,List.of(),item)),List.of(),List.of(),List.of()),null);
        }
        finish(run);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");
    }
    private void prepare(boolean requirePass)throws Exception {
        var source=presets.get("repository.snapshot",1).node();source=new Node("repository",source.title(),source.kind(),source.moduleId(),1,null,source.task(),List.of(new Input("branch",InputSource.REQUIREMENT,"branch",null,DataKind.TEXT,true)),source.outputs(),source.outcomes(),source.completion(),0,false,source.parameters());
        var draft=work("author",false,requirePass);var review=work("review",true,requirePass);
        var graph=new WorkflowGraph(1,List.of(source,draft,review),List.of(new Edge("ra","repository","author",null),new Edge("rr","repository","review",null),new Edge("ar","author","review",null)),List.of(new PublicInput("branch","代码分支",DataKind.TEXT,true),new PublicInput("documents","需求原文",DataKind.DOCUMENT,true)));
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"需求代码评审","",graph,CanvasLayout.empty()));var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"评审需求","核对金额校验",template.id(),1));id=owner.id();plans.confirm(id,new WorkflowRequests.VersionCommand(key(),owner.version()));
        var result=uploads.upload(id,new WorkflowUploadStore.Request(key(),plans.require(id).version(),1),List.of(new DocumentTemplateStorage.Incoming("需求.md",("# 金额\n金额必须大于零。\n"+"适用于所有订单。\n".repeat(210)).getBytes(StandardCharsets.UTF_8))));documents=result.reference();assertThat(result.originals().getFirst().sections()).isEqualTo(1);
        String capture=start("repository");long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(!WorkflowAttemptState.valueOf(nodes.attempt(capture).state()).terminal()&&System.nanoTime()<deadline){commands.advance(capture);assertThat(commandStore.require(capture).suspended()).as(commandStore.require(capture).lastErrorCode()).isFalse();Thread.sleep(25);}
        assertThat(nodes.attempt(capture).state()).isEqualTo("SUCCEEDED");author=start("author");running(author);
        assertThat(json.valueToTree(call(author,WorkflowModelProfile.WORK,Map.of())).path("documentReview").path("testStatus").asText()).isEqualTo("NOT_RUN_STATIC_REVIEW");
    }
    private Node work(String id,boolean review,boolean requirePass){
        var original=presets.get(review?WorkflowDocumentReview.REVIEW:WorkflowDocumentReview.AUTHOR,1).node();
        var inputs=new ArrayList<>(List.of(new Input("documents",InputSource.REQUIREMENT,"documents",null,DataKind.DOCUMENT,true),new Input("code",InputSource.NODE,"repository","source",DataKind.DOCUMENT,true)));
        if(review)inputs.add(new Input("draft",InputSource.NODE,"author","assessment",DataKind.JSON,true));
        var completion=review&&!requirePass?new Completion(CompletionKind.DELIVERABLES,"有效复核即完成",null):original.completion();
        return new Node(id,original.title(),original.kind(),original.moduleId(),1,original.roleId(),original.task(),inputs,original.outputs(),original.outcomes(),completion,0,false,original.parameters(),original.roleRevisionId());
    }
    private Node parameters(Node n,Map<String,String> p){return new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),n.inputs(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),p,n.roleRevisionId());}
    private String start(String node){controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,node,Map.of("branch",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("local:refs/heads/main")),"documents",new WorkflowDelivery.Value(DataKind.DOCUMENT,json.valueToTree(documents))),MODEL,List.of()));dispatch.advance(id);var row=nodes.node(id,plans.require(id).headRevision(),node);assertThat(row.latestAttemptId()).as(controls.get(id).reasonCode()).isNotNull();return row.latestAttemptId();}
    private void running(String run){for(int i=0;i<5&&!models.require(run).state().equals("RUNNING");i++)execution.advance(run);assertThat(models.require(run).state()).as(models.require(run).lastErrorCode()).isEqualTo("RUNNING");}
    private void finish(String run){fake.setSessionState(nodes.attempt(run).externalSessionId(),"COMPLETED");execution.advance(run);}
    private void allDocs(String run){readDoc(run,1,200);readDoc(run,201,200);}
    private void readDoc(String run,int start,int count){String path="parsed/01/0001.md",hash=uploaded.file(documents.uploadId(),path).orElseThrow().sha256();call(run,WorkflowModelProfile.FILE,Map.of("name","documents","path",path,"sha256",hash,"startLine",start,"lineCount",count));}
    private void readCode(String run){call(run,WorkflowModelProfile.FILE,Map.of("name","code","path","code.txt","blobSha",blob,"startLine",1,"lineCount",200));}
    private void readDraft(String run,int offset,int limit){call(run,WorkflowModelProfile.INPUT,Map.of("name","draft","offset",offset,"limit",limit));}
    private Object submit(String run,Object candidate,String outcome){boolean review=WorkflowDocumentReview.REVIEW.equals(models.definition(models.require(run)).moduleId());var delivery=new WorkflowDelivery("静态评审完成",outcome,Map.of("summary",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("未运行测试")),review?"review":"assessment",new WorkflowDelivery.Value(review?DataKind.DECISION:DataKind.JSON,json.valueToTree(candidate))));return call(run,WorkflowModelProfile.SUBMIT,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(run).version(),"delivery",delivery));}
    private DirectDocumentAssessment.Candidate candidate(String quote,String hash,List<String> issues){var item=new RequirementCodeAssessment.Item("RQ-1",RequirementCodeAssessment.Conclusion.SATISFIED,"正数校验存在",List.of(new RequirementCodeAssessment.CodeReference("code.txt",hash,1,1,quote)),List.of("code.txt"),null,"未运行测试",List.of());return new DirectDocumentAssessment.Candidate(null,List.of(new DirectDocumentAssessment.Entry("金额正数","金额必须大于零",List.of(new DirectDocumentAssessment.Source("DOC-1",1)),issues,item)),List.of(),List.of(),List.of("静态检查未证明运行行为"));}
    private DirectDocumentAssessment.Review review(boolean approved){return new DirectDocumentAssessment.Review(null,approved,List.of("RQ-1"),List.of(),List.of(new DirectDocumentAssessment.Source("DOC-1",1)),approved?List.of():List.of(new DirectDocumentAssessment.Correction("RQ-1",null,null,"补充入口调用链的代码证据")));}
    private Object call(String run,String tool,Map<String,Object> args){return tools.call(tool,Map.of("scope",identity.grant(models.require(run)),"attemptId",run,"args",args));}
    private int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-document-review-tests-");}catch(Exception invalid){throw new ExceptionInInitializerError(invalid);}}
}
