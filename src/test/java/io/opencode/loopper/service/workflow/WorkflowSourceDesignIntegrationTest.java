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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.workflow-monitor-enabled=false","loopper.monitor-delay=1h"})
class WorkflowSourceDesignIntegrationTest {
    private static final Path DATA=data();
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("design.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowControls controls;
    @Autowired WorkflowDispatchExecution dispatch;
    @Autowired WorkflowSourceExecution sourceExecution;
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
    @Autowired RolePublishingService roles;
    @Autowired ProjectService projects;
    @Autowired WorkflowSourceReadMapper reads;
    @Autowired WorkflowEncoding encoding;
    @Autowired WorkflowDocumentExecution documentExecution;
    @Autowired WorkflowDocumentStore documentStore;
    @Autowired WorkflowDocumentBuilder documentBuilder;
    @Autowired WorkflowBuiltinFlows builtins;
    @Autowired WorkflowSourcePlanStore sourcePlanStore;
    @Autowired WorkflowSourcePlanBuilder sourcePlanBuilder;
    @Autowired WorkflowSourcePlanExecution sourcePlanExecution;
    @Autowired WorkflowPlanCandidates candidates;
    @Autowired WorkflowDocumentMapper documents;
    @Autowired WorkflowCodeFiles files;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    Path root;String project;FakeOpenCodeClient fake;
    @BeforeEach void prepare() throws Exception {
        flyway.clean();flyway.migrate();roles.seedBuiltin();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        root=Files.createDirectory(directory.resolve("project"));Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"),"class Main {\n int value() { return 1; }\n}\n");Files.writeString(root.resolve("README.md"),"context only\n");
        project=projects.create("专业源码设计",root.toString(),"").id();
    }
    @Test void authorAndReviewerNeedIndependentFullReadsAndAcceptanceStillWaitsForStop() {
        assertThat(roles.bindings().stream().filter(binding->binding.slot().equals("WORKFLOW_READ_ONLY")).findFirst().orElseThrow().activeRoleId())
                .as("adding a specialist must not replace the existing generic workflow binding").isEqualTo("builtin.designer");
        String id=create(false);freeze(id);String author=start(id,"author");
        var work=(Map<?,?>)call(author,WorkflowModelProfile.WORK,Map.of());assertThat(work.containsKey("sourceDesign")).isTrue();
        var design=design("源码依据",false);assertThatThrownBy(()->submit(author,design)).isInstanceOf(BadRequestException.class);
        read(author,1,1);assertThatThrownBy(()->submit(author,design)).isInstanceOf(BadRequestException.class).hasMessageContaining("完整读取");
        read(author,2,200);assertThatThrownBy(()->submit(author,design("伪造引用",true))).isInstanceOf(BadRequestException.class).hasMessageContaining("引用");
        var request=submission(author,design);var accepted=call(author,WorkflowModelProfile.SUBMIT,request);
        assertThat(nodes.attempt(author).state()).isEqualTo("RUNNING");assertThat(nodes.hasStop(author)).isFalse();
        execution.advance(author);assertThat(nodes.attempt(author).state()).isEqualTo("RUNNING");finish(author);
        assertThat(call(author,WorkflowModelProfile.SUBMIT,request)).isEqualTo(accepted);
        String reviewer=start(id,"reviewer");assertThatThrownBy(()->submit(reviewer,review(false))).isInstanceOf(BadRequestException.class);
        input(reviewer,"draft");assertThatThrownBy(()->submit(reviewer,review(false))).isInstanceOf(BadRequestException.class).hasMessageContaining("完整读取");
        read(reviewer,1,200);submit(reviewer,review(false));finish(reviewer);
        assertThat(nodes.attempt(reviewer).state()).isEqualTo("SUCCEEDED");assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(reads.sources(author,"source","src/Main.java")).hasSize(2);assertThat(reads.sources(reviewer,"source","src/Main.java")).hasSize(1);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void reviewerMustReadEveryExplicitContextDraftAndReviseIsPreservedAsARealOpinion() {
        String id=create(true);freeze(id);String author=start(id,"author");read(author,1,200);submit(author,design("第一份设计",false));finish(author);
        String other=start(id,"other");read(other,1,200);submit(other,design("关联模块设计",false));finish(other);
        String reviewer=start(id,"reviewer");read(reviewer,1,200);input(reviewer,"draft");
        assertThatThrownBy(()->submit(reviewer,review(true))).isInstanceOf(BadRequestException.class).hasMessageContaining("context");
        input(reviewer,"context");submit(reviewer,review(true));finish(reviewer);
        assertThat(nodes.attempt(reviewer).state()).isEqualTo("FAILED");assertThat(nodes.delivery(reviewer).outcome()).isEqualTo("REVISE");
        assertThat(nodes.delivery(reviewer).contentJson()).contains("补充异常路径");assertThat(plans.require(id).state()).isEqualTo("STALLED");
    }
    @Test void readEvidenceAndAcceptedCandidateRollBackWithTheirReceiptsAndSurviveRetry() {
        String id=create(false);freeze(id);String author=start(id,"author");
        jdbc.execute("CREATE TRIGGER fail_source_read BEFORE INSERT ON workflow_source_read BEGIN SELECT RAISE(ABORT,'read rollback'); END");
        assertThatThrownBy(()->read(author,1,200)).hasStackTraceContaining("read rollback");assertThat(reads.sources(author,"source","src/Main.java")).isEmpty();
        jdbc.execute("DROP TRIGGER fail_source_read");read(author,1,200);
        jdbc.execute("CREATE TRIGGER fail_source_submit BEFORE INSERT ON workflow_command WHEN NEW.action='MODEL_SUBMIT' BEGIN SELECT RAISE(ABORT,'submit rollback'); END");
        var request=submission(author,design("完整设计",false));assertThatThrownBy(()->call(author,WorkflowModelProfile.SUBMIT,request)).hasStackTraceContaining("submit rollback");
        assertThat(nodes.findDelivery(author)).isEmpty();jdbc.execute("DROP TRIGGER fail_source_submit");
        call(author,WorkflowModelProfile.SUBMIT,request);finish(author);assertThat(nodes.attempt(author).state()).isEqualTo("SUCCEEDED");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_source_read WHERE attempt_id=?",author)).isInstanceOf(RuntimeException.class);
    }
    @Test void safeMarkdownAndCompleteCoverageCannotBeBypassedByOtherwiseValidReads() {
        String id=create(false);freeze(id);String author=start(id,"author");read(author,1,200);
        assertThatThrownBy(()->submit(author,design("[外部文档](https://invalid.example)",false))).isInstanceOf(BadRequestException.class).hasMessageContaining("链接");
        assertThatThrownBy(()->submit(author,design("<img src=x>",false))).isInstanceOf(BadRequestException.class);
        var wrong=new SourceDesign.Candidate("设计","说明",List.of(new SourceDesign.Section("main","模块","说明",List.of("README.md"),references(false))),List.of());
        assertThatThrownBy(()->submit(author,delivery("design",DataKind.JSON,wrong,null))).isInstanceOf(BadRequestException.class).hasMessageContaining("范围");
        assertThat(nodes.findDelivery(author)).isEmpty();
    }
    @Test void candidateOverTheGenericLimitRetainsTheSourceModules256KiBContract() {
        String id=create(false);freeze(id);String author=start(id,"author");read(author,1,200);
        var sections=new ArrayList<SourceDesign.Section>();for(int i=0;i<4;i++)sections.add(new SourceDesign.Section("s"+i,"章节"+i,"design ".repeat(6000),List.of("src/Main.java"),references(false)));
        var result=delivery("design",DataKind.JSON,new SourceDesign.Candidate("大设计","说明",sections,List.of()),null);
        assertThat(encoding.encode(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(128*1024);
        submit(author,result);finish(author);assertThat(nodes.delivery(author).contentJson().length()).isGreaterThan(128*1024);
    }
    @Test void malformedCandidateCanBeCorrectedWithinTheOriginalAttempt() {
        String id=create(false);freeze(id);String author=start(id,"author");read(author,1,200);
        assertThatThrownBy(()->submit(author,delivery("design",DataKind.JSON,Map.of("sections","wrong shape"),null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("完整的设计或复核结构");
        assertThat(nodes.findDelivery(author)).isEmpty();assertThat(nodes.attempt(author).state()).isEqualTo("RUNNING");
        submit(author,design("修正后的设计",false));finish(author);assertThat(nodes.attempt(author).state()).isEqualTo("SUCCEEDED");
    }
    @Test void multiMegabyteDesignInputsUseFixedReferencesThroughReviewAndDocumentRendering() throws Exception {
        var paths=new ArrayList<String>(List.of("src/Main.java"));
        for(int i=1;i<9;i++){String path="src/Part"+i+".java";paths.add(path);Files.writeString(root.resolve(path),"class Part"+i+" {}\n");}
        String id=create(false,original->{
            var steps=new ArrayList<Node>(List.of(original.nodes().getFirst()));var edges=new ArrayList<Edge>();
            var source=new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true);
            var reviewInputs=new ArrayList<Input>(List.of(source));var documentInputs=new ArrayList<Input>(List.of(source));
            for(int i=0;i<paths.size();i++) {
                String key="author"+i;
                steps.add(parameters(preset("source.design",key,List.of(source)),Map.of("targetPaths",encoding.encode(List.of(paths.get(i))))));
                edges.add(new Edge("source-"+key,"source",key,null));edges.add(new Edge(key+"-reviewer",key,"reviewer",null));
                reviewInputs.add(new Input(i==0?"draft":"context"+i,InputSource.NODE,key,"design",DataKind.JSON,true));
                documentInputs.add(new Input("draft"+i,InputSource.NODE,key,"design",DataKind.JSON,true));
            }
            steps.add(parameters(preset("source.design-review","reviewer",reviewInputs),Map.of("targetPaths",encoding.encode(List.of(paths.getFirst())))));
            documentInputs.add(new Input("review",InputSource.NODE,"reviewer","review",DataKind.DECISION,true));
            steps.add(parameters(preset("source.design-document","document",documentInputs),Map.of("reviewPolicy","NONE")));
            edges.add(new Edge("reviewer-document","reviewer","document",null));return new WorkflowGraph(1,steps,edges,original.inputs());
        });
        freeze(id);int total=0;
        for(int i=0;i<paths.size();i++) {
            String run=start(id,"author"+i),path=paths.get(i),text=Files.readString(root.resolve(path)),sha=SourceTreeCapture.hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            call(run,WorkflowModelProfile.FILE,Map.of("name","source","path",path,"sha256",sha,"startLine",1,"lineCount",200));
            var references=List.of(new SourceDesign.Reference(path,sha,1,1,text.lines().findFirst().orElseThrow()));
            var sections=new ArrayList<SourceDesign.Section>();for(int section=0;section<6;section++)sections.add(new SourceDesign.Section("s"+section,"章节 "+section,"design ".repeat(5700),List.of(path),references));
            submit(run,delivery("design",DataKind.JSON,new SourceDesign.Candidate("设计 "+i,"完整设计",sections,List.of()),null));finish(run);
            total+=nodes.delivery(run).contentJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        assertThat(total).isGreaterThan(2*1024*1024);
        String reviewer=start(id,"reviewer");var snapshot=nodes.inputSnapshot(nodes.attempt(reviewer));
        assertThat(snapshot.version()).isEqualTo(2);assertThat(nodes.attempt(reviewer).inputsJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(12000);
        assertThat(snapshot.values().stream().filter(i->i.kind()==DataKind.JSON)).hasSize(9).allSatisfy(input->assertThat(input.reference()).isNotNull());
        read(reviewer,1,200);for(int i=0;i<8;i++)input(reviewer,i==0?"draft":"context"+i);
        assertThatThrownBy(()->submit(reviewer,review(false))).isInstanceOf(BadRequestException.class).hasMessageContaining("context8");
        input(reviewer,"context8");submit(reviewer,review(false));finish(reviewer);
        String document=start(id,"document");assertThat(nodes.attempt(document).inputsJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(15000);documentExecution.advance(document);
        assertThat(nodes.attempt(document).state()).isEqualTo("SUCCEEDED");assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(documents.count(document)).isEqualTo(56);
        var report=encoding.decode(nodes.delivery(document).contentJson(),WorkflowDelivery.class).outputs().get("report").content();
        assertThat(report.path("draftCount").asInt()).isEqualTo(9);assertThat(report.path("reviewedCount").asInt()).isEqualTo(1);
        assertThat(files.text(files.output(id,"document",document,"document"),"module-9-s5.md",0,12000).text()).contains("design design");
    }
    @Test void oversizedScopeStopsBeforeCreatingAnAttemptAndNeverSilentlyDropsFiles() throws Exception {
        for(int i=0;i<12;i++)Files.writeString(root.resolve("src/Part"+i+".java"),"class Part"+i+" {}\n");
        String id=create(false);freeze(id);requestStart(id,"author");dispatch.advance(id);
        assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_SOURCE_BATCH_REQUIRED");
        assertThat(nodes.node(id,1,"author").attemptCount()).isZero();assertThat(nodes.node(id,1,"author").latestAttemptId()).isNull();
    }
    @Test void reviewerCannotMixTwoSnapshotIdentitiesEvenWhenTheirSourceBytesMatch() {
        String id=create(false,graph->{
            var steps=new ArrayList<>(graph.nodes());var reviewer=steps.removeLast();
            steps.add(preset("source.snapshot","source2",steps.getFirst().inputs()));
            steps.add(preset("source.design-review","reviewer",List.of(new Input("source",InputSource.NODE,"source2","source",DataKind.DOCUMENT,true),reviewer.inputs().get(1))));
            var edges=new ArrayList<>(graph.edges());edges.add(new Edge("source2-reviewer","source2","reviewer",null));
            return new WorkflowGraph(1,steps,edges,graph.inputs());
        });
        freeze(id);String second=start(id,"source2");sourceExecution.advance(second);
        String author=start(id,"author");read(author,1,200);submit(author,design("相同内容但不同采集身份",false));finish(author);
        requestStart(id,"reviewer");dispatch.advance(id);
        assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_SOURCE_DESIGN_INVALID");
        assertThat(nodes.node(id,1,"reviewer").attemptCount()).isZero();assertThat(nodes.node(id,1,"reviewer").latestAttemptId()).isNull();
    }
    @Test void cancellationPreventsFurtherReadsAndDoesNotAcceptLateDesign() {
        String id=create(false);freeze(id);String author=start(id,"author");read(author,1,1);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"结束本次专业编写"));drain.stop(id,author);
        assertThatThrownBy(()->read(author,2,200)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->submit(author,design("迟到设计",false))).isInstanceOf(ConflictException.class);
        execution.advance(author);assertThat(finishes.finalizeReady(id)).isTrue();assertThat(reads.sources(author,"source","src/Main.java")).hasSize(1);
    }
    @Test void documentPackageIsImmutableAndAFollowingModelReadsTheSameBytes() throws Exception {
        String id=create(false,graph->{
            var bundled=bundle(graph,"REQUIRED",true,"author");var steps=new ArrayList<>(bundled.nodes());
            steps.add(preset("analysis.read","reader",List.of(new Input("material",InputSource.NODE,"document","document",DataKind.DOCUMENT,true))));
            var edges=new ArrayList<>(bundled.edges());edges.add(new Edge("document-reader","document","reader",null));return new WorkflowGraph(1,steps,edges,bundled.inputs());
        });
        completeDesign(id,true);String run=start(id,"document");documentExecution.advance(run);
        assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");assertThat(documents.count(run)).isEqualTo(3);
        var binding=files.output(id,"document",run,"document");var listed=files.list(binding,null,100);
        assertThat(listed.items()).extracting(WorkflowCodeFiles.File::path).containsExactly("coverage.md","module-1-main.md","overview.md");
        byte[] bytes=files.bytes(binding,"module-1-main.md");assertThat(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).contains("class Main {","返回总览");
        var response=new io.opencode.loopper.api.WorkflowFileController(files).archive(id,"document",run,"outputs","document");
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/zip");
        var unpacked=new TreeMap<String,byte[]>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.getBody()),java.nio.charset.StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null)unpacked.put(entry.getName(),zip.readAllBytes());
        }
        assertThat(unpacked.keySet()).containsExactly("coverage.md","module-1-main.md","overview.md");assertThat(unpacked.get("module-1-main.md")).isEqualTo(bytes);
        assertThat(files.archive(binding)).isEqualTo(response.getBody());
        Files.writeString(root.resolve("src/Main.java"),"changed after accepted source\n");
        String reader=start(id,"reader");var text=(WorkflowCodeFiles.Text)call(reader,WorkflowModelProfile.FILE,Map.of("name","material","path","module-1-main.md"));
        assertThat(text.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(bytes);
        assertThatThrownBy(()->files.bytes(new WorkflowCodeFiles.Binding("other",id,run,null,null,binding.documentReference()),"overview.md")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->files.bytes(binding,"../overview.md")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_document_file SET content='changed' WHERE attempt_id=?",run)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_document WHERE attempt_id=?",run)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void requiredReviewCannotBeOmittedButExplicitUnreviewedPolicyProducesHonestDocuments() {
        for(String policy:List.of("REQUIRED","NONE")) {
            String id=create(false,graph->bundle(graph,policy,false,"author"));completeDesign(id,false);String run=start(id,"document");documentExecution.advance(run);
            if(policy.equals("REQUIRED")) {
                assertThat(nodes.attempt(run).state()).isEqualTo("FAILED");assertThat(nodes.delivery(run).contentJson()).contains("SOURCE_REVIEW_INCOMPLETE");assertThat(documents.find(run)).isEmpty();
            }else {
                assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");var binding=files.output(id,"document",run,"document");
                assertThat(files.text(binding,"overview.md",0,12000).text()).contains("未绑定通过的独立复核");
                assertThat(files.text(binding,"coverage.md",0,12000).text()).contains("已生成，未通过独立复核").doesNotContain("| 已复核 |");
                assertThat(encoding.decode(nodes.delivery(run).contentJson(),WorkflowDelivery.class).outputs().get("report").content().path("reviewedCount").asInt()).isZero();
            }
        }
    }
    @Test void explicitUnreviewedPolicyRetainsReviseOpinionWithoutClaimingReviewPassed() {
        String id=create(false,graph->{
            var steps=graph.nodes().stream().map(n->n.id().equals("reviewer")?new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),n.inputs(),n.outputs(),n.outcomes(),
                    new Completion(CompletionKind.DELIVERABLES,"保留复核意见",null),n.maxRetries(),n.pauseAfter(),n.parameters(),n.roleRevisionId()):n).toList();
            return bundle(new WorkflowGraph(1,steps,graph.edges(),graph.inputs()),"NONE",true,"author");
        });
        completeDesign(id,false);String reviewer=start(id,"reviewer");read(reviewer,1,200);input(reviewer,"draft");submit(reviewer,review(true));finish(reviewer);
        assertThat(nodes.attempt(reviewer).state()).isEqualTo("SUCCEEDED");assertThat(nodes.delivery(reviewer).outcome()).isEqualTo("REVISE");
        String document=start(id,"document");documentExecution.advance(document);assertThat(nodes.attempt(document).state()).isEqualTo("SUCCEEDED");
        var report=encoding.decode(nodes.delivery(document).contentJson(),WorkflowDelivery.class).outputs().get("report").content();
        assertThat(report.path("reviewedCount").asInt()).isZero();assertThat(report.path("reviseCount").asInt()).isEqualTo(1);
        var binding=files.output(id,"document",document,"document");
        assertThat(files.text(binding,"module-1-main.md",0,12000).text()).contains("复核要求返修，本文件不代表复核通过");
        assertThat(files.text(binding,"coverage.md",0,12000).text()).contains("已生成，未通过独立复核").doesNotContain("| 已复核 |");
    }
    @Test void documentCoverageCannotSilentlyExcludeUnassignedSource() throws Exception {
        Files.writeString(root.resolve("src/Extra.java"),"class Extra {}\n");
        String id=create(false,graph->{var changed=graph.nodes().stream().map(node->WorkflowSourceDesign.supports(node.moduleId())?parameters(node,Map.of("targetPaths","[\"src/Main.java\"]")):node).toList();return bundle(new WorkflowGraph(1,changed,graph.edges(),graph.inputs()),"REQUIRED",true,"author");});
        completeDesign(id,true);String run=start(id,"document");documentExecution.advance(run);
        assertThat(nodes.attempt(run).state()).isEqualTo("FAILED");assertThat(nodes.delivery(run).contentJson()).contains("SOURCE_COVERAGE_INCOMPLETE");assertThat(documents.find(run)).isEmpty();
    }
    @Test void reviewForAnotherDraftCannotAuthorizeTheBoundDocument() {
        String id=create(true,graph->bundle(graph,"REQUIRED",true,"other"));freeze(id);
        for(String key:List.of("author","other")){String run=start(id,key);read(run,1,200);submit(run,design(key,false));finish(run);}
        String reviewer=start(id,"reviewer");read(reviewer,1,200);input(reviewer,"draft");input(reviewer,"context");submit(reviewer,review(false));finish(reviewer);
        String document=start(id,"document");documentExecution.advance(document);
        assertThat(nodes.attempt(document).state()).isEqualTo("FAILED");assertThat(nodes.delivery(document).contentJson()).contains("WORKFLOW_DOCUMENT_INPUT_INVALID");assertThat(documents.find(document)).isEmpty();
    }
    @Test void documentFilesAndCompletionRollBackTogetherAndRetryTheOriginalAttempt() {
        String id=create(false,graph->bundle(graph,"REQUIRED",true,"author"));completeDesign(id,true);String run=start(id,"document");
        jdbc.execute("CREATE TRIGGER fail_document_delivery BEFORE INSERT ON workflow_node_delivery WHEN NEW.attempt_id='"+run+"' BEGIN SELECT RAISE(ABORT,'document rollback'); END");
        assertThatThrownBy(()->documentExecution.advance(run)).hasStackTraceContaining("document rollback");
        assertThat(documents.find(run)).isEmpty();assertThat(documents.count(run)).isZero();assertThat(nodes.hasStop(run)).isFalse();assertThat(nodes.attempt(run).state()).isEqualTo("RUNNING");
        jdbc.execute("DROP TRIGGER fail_document_delivery");documentExecution.advance(run);var before=documents.find(run).orElseThrow();documentExecution.advance(run);
        assertThat(documents.find(run)).contains(before);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void cancellationBetweenRenderingAndPublishingCannotExposeLateDocuments() {
        String id=create(false,graph->bundle(graph,"REQUIRED",true,"author"));completeDesign(id,true);String run=start(id,"document");
        var context=documentStore.context(run);var result=documentBuilder.build(context);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消文档汇总"));drain.stop(id,run);
        documentStore.finish(context,result,null);
        assertThat(nodes.attempt(run).state()).isEqualTo("CANCELLED");assertThat(documents.find(run)).isEmpty();assertThat(nodes.findDelivery(run)).isEmpty();assertThat(finishes.finalizeReady(id)).isTrue();
    }
    @Test void completeBuiltinPlanWaitsForConfirmationThenRunsEveryBatchAndDownloadsAllDocuments() throws Exception {
        Files.createDirectories(root.resolve("src/feature"));Files.writeString(root.resolve("src/feature/Other.java"),"class Other {}\n");
        String id=builtin(java.util.function.UnaryOperator.identity());freeze(id);String planner=start(id,"batches");sourcePlanExecution.advance(planner);
        assertThat(nodes.attempt(planner).state()).isEqualTo("SUCCEEDED");assertThat(nodes.attempt(planner).roleSnapshotJson()).isNull();
        var report=encoding.decode(nodes.delivery(planner).contentJson(),WorkflowDelivery.class).outputs().get("report").content();
        assertThat(report.path("batches").get(0).path("title").asString()).isEqualTo("src");
        assertThat(report.path("batches").get(1).path("title").asString()).isEqualTo("src/feature");
        assertThat(report.path("batchCount").asInt()).isEqualTo(2);assertThat(report.path("sourceCount").asInt()).isEqualTo(2);
        var pending=candidates.list(id,"PENDING",null,50).items();assertThat(pending).hasSize(1);var candidate=candidates.get(id,pending.getFirst().id());
        assertThat(candidate.graph().nodes()).hasSize(7);assertThat(candidate.sourceCompleted()).isTrue();assertThat(candidate.diagnostics()).isEmpty();
        assertThat(plans.require(id).headRevision()).isEqualTo(1);assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_PLAN_REVIEW_REQUIRED");
        dispatch.advance(id);assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_model_launch",Integer.class)).isZero();
        var request=new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,candidate.version(),null);
        var accepted=candidates.apply(id,candidate.id(),request);assertThat(candidates.apply(id,candidate.id(),request)).isEqualTo(accepted);
        assertThat(plans.require(id).headRevision()).isEqualTo(2);assertThat(plans.require(id).state()).isEqualTo("PAUSED");dispatch.advance(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_model_launch",Integer.class)).isZero();
        var graph=plans.get(id,null).graph();var authors=graph.nodes().stream().filter(n->n.moduleId().equals(WorkflowSourceDesign.AUTHOR)).toList();
        var reviewers=graph.nodes().stream().filter(n->n.moduleId().equals(WorkflowSourceDesign.REVIEW)).toList();assertThat(authors).hasSize(2);assertThat(reviewers).hasSize(2);
        for(var author:authors)completeBatch(id,author,false);
        for(var reviewer:reviewers){assertThat(reviewer.inputs().stream().filter(i->i.kind()==DataKind.JSON)).hasSize(2);completeBatch(id,reviewer,true);}
        String document=start(id,"document");documentExecution.advance(document);assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(files.list(files.output(id,"document",document,"document"),null,100).items()).hasSize(4);
        assertThat(nodes.inputs(nodes.attempt(planner)).planRevision()).isEqualTo(1);assertThat(candidates.get(id,candidate.id()).state()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT source FROM workflow_plan_revision WHERE requirement_id=? AND revision=2",String.class,id)).isEqualTo("USER");
    }
    @Test void customPlanKeepsRolesTasksRetryAndCheckpointsAndDoesNotRecreateRemovedReview() {
        String id=builtin(graph->{
            var steps=graph.nodes().stream().filter(n->!n.id().equals("reviewer")).map(n->{
                if(n.id().equals("author"))return new Node(n.id(),"自定义作者",n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),"额外说明接口行为",n.inputs(),n.outputs(),n.outcomes(),n.completion(),2,true,Map.of("custom","preserved"),n.roleRevisionId());
                if(n.id().equals("document"))return new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),n.inputs().stream().filter(i->i.kind()!=DataKind.DECISION).toList(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),Map.of("reviewPolicy","NONE"),n.roleRevisionId());return n;
            }).toList();var edges=new ArrayList<>(graph.edges().stream().filter(e->!e.from().equals("reviewer")&&!e.to().equals("reviewer")).toList());edges.add(new Edge("author-document","author","document",null));return new WorkflowGraph(1,steps,edges,graph.inputs());
        });freeze(id);String run=start(id,"batches");sourcePlanExecution.advance(run);
        var candidate=candidates.get(id,candidates.list(id,"PENDING",null,50).items().getFirst().id());
        assertThat(candidate.graph().nodes()).noneMatch(n->n.moduleId().equals(WorkflowSourceDesign.REVIEW));
        var author=candidate.graph().nodes().stream().filter(n->n.id().equals("author")).findFirst().orElseThrow();
        var original=plans.get(id,null).graph().nodes().stream().filter(n->n.id().equals("author")).findFirst().orElseThrow();
        assertThat(author.roleRevisionId()).isEqualTo(original.roleRevisionId());assertThat(author.task()).isEqualTo("额外说明接口行为");assertThat(author.maxRetries()).isEqualTo(2);assertThat(author.pauseAfter()).isTrue();assertThat(author.parameters()).containsEntry("custom","preserved");
    }
    @Test void planFailureRetainsFullSourceAndDoesNotSilentlyDropBatches() throws Exception {
        for(int i=0;i<32;i++){Files.createDirectories(root.resolve("src/p"+i));Files.writeString(root.resolve("src/p"+i+"/Part.java"),"class Part {}\n");}
        String id=builtin(java.util.function.UnaryOperator.identity());freeze(id);String run=start(id,"batches");sourcePlanExecution.advance(run);
        assertThat(nodes.attempt(run).state()).isEqualTo("FAILED");assertThat(nodes.delivery(run).contentJson()).contains("WORKFLOW_SOURCE_PLAN_LIMIT");
        assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();assertThat(plans.require(id).headRevision()).isEqualTo(1);
        var inventory=files.list(files.output(id,"source",nodes.node(id,1,"source").latestAttemptId(),"source"),null,100).items();
        assertThat(inventory).filteredOn(file->Boolean.TRUE.equals(file.target())).hasSize(33);
        assertThat(inventory).anyMatch(file->file.path().equals("README.md")&&!file.target());
    }
    @Test void planCandidateAndHoldRollBackTogetherThenSameAttemptCanResume() {
        String id=builtin(java.util.function.UnaryOperator.identity());freeze(id);String run=start(id,"batches");
        jdbc.execute("CREATE TRIGGER fail_plan_capture BEFORE INSERT ON workflow_plan_candidate BEGIN SELECT RAISE(ABORT,'plan rollback'); END");
        assertThatThrownBy(()->sourcePlanExecution.advance(run)).hasStackTraceContaining("plan rollback");
        assertThat(nodes.findDelivery(run)).isEmpty();assertThat(nodes.hasStop(run)).isFalse();assertThat(nodes.attempt(run).state()).isEqualTo("RUNNING");assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();
        jdbc.execute("DROP TRIGGER fail_plan_capture");sourcePlanExecution.advance(run);sourcePlanExecution.advance(run);
        assertThat(candidates.list(id,"PENDING",null,50).items()).hasSize(1);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");
    }
    @Test void cancelledPlanningCannotPublishItsLateProposal() {
        String id=builtin(java.util.function.UnaryOperator.identity());freeze(id);String run=start(id,"batches");var context=sourcePlanStore.context(run);var result=sourcePlanBuilder.build(context);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消规划"));drain.stop(id,run);
        sourcePlanStore.finish(context,sourcePlanBuilder.delivery(result,null),true);
        assertThat(nodes.findDelivery(run)).isEmpty();assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();assertThat(finishes.finalizeReady(id)).isTrue();
    }
    @Test void externalConsumerOfAnUnsplitDraftMustBeResolvedBeforePlanning() {
        String id=builtin(graph->{var steps=new ArrayList<>(graph.nodes());steps.add(preset("analysis.read","extra",List.of(new Input("draft",InputSource.NODE,"author","design",DataKind.JSON,true))));var edges=new ArrayList<>(graph.edges());edges.add(new Edge("author-extra","author","extra",null));return new WorkflowGraph(1,steps,edges,graph.inputs());});
        freeze(id);String run=start(id,"batches");sourcePlanExecution.advance(run);assertThat(nodes.attempt(run).state()).isEqualTo("FAILED");
        assertThat(nodes.delivery(run).contentJson()).contains("WORKFLOW_SOURCE_PLAN_INVALID");assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();
    }
    private String builtin(java.util.function.UnaryOperator<WorkflowGraph> transform) {
        builtins.publish();var built=templates.get("builtin.workflow.source-design",null);var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"源码设计",built.description(),transform.apply(built.graph()),CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"完整源码设计","覆盖源码行为",template.id(),1));plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private void completeBatch(String id,Node node,boolean reviewing) throws Exception {
        String run=start(id,node.id());var paths=json.readTree(node.parameters().get("targetPaths"));var references=new ArrayList<SourceDesign.Reference>();var names=new ArrayList<String>();
        for(var item:paths){String path=item.asString();names.add(path);String body=Files.readString(root.resolve(path)),sha=SourceTreeCapture.hash(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            call(run,WorkflowModelProfile.FILE,Map.of("name","source","path",path,"sha256",sha,"startLine",1,"lineCount",200));references.add(new SourceDesign.Reference(path,sha,1,1,body.lines().findFirst().orElseThrow()));}
        if(reviewing){for(var input:node.inputs())if(input.kind()==DataKind.JSON)input(run,input.name());submit(run,delivery("review",DataKind.DECISION,new SourceDesign.Review("PASS","完整核对源码与关联稿",names,references,List.of()),"PASS"));}
        else submit(run,delivery("design",DataKind.JSON,new SourceDesign.Candidate("本批设计","完整说明",List.of(new SourceDesign.Section("main","模块设计","按源码说明行为",names,references)),List.of()),null));
        finish(run);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");
    }
    private WorkflowGraph bundle(WorkflowGraph graph,String policy,boolean reviewed,String author) {
        var steps=new ArrayList<>(graph.nodes());var edges=new ArrayList<>(graph.edges());
        if(!reviewed){steps.removeIf(n->n.id().equals("reviewer"));edges.removeIf(e->e.to().equals("reviewer"));}
        var inputs=new ArrayList<Input>(List.of(new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true),new Input("draft",InputSource.NODE,author,"design",DataKind.JSON,true)));
        if(reviewed){inputs.add(new Input("review",InputSource.NODE,"reviewer","review",DataKind.DECISION,true));edges.add(new Edge("review-document","reviewer","document",null));}
        else edges.add(new Edge("author-document",author,"document",null));
        steps.add(parameters(preset("source.design-document","document",inputs),Map.of("reviewPolicy",policy)));
        return new WorkflowGraph(1,steps,edges,graph.inputs());
    }
    private Node parameters(Node n,Map<String,String> values){var parameters=new LinkedHashMap<>(n.parameters());parameters.putAll(values);return new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),n.inputs(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),parameters,n.roleRevisionId());}
    private void completeDesign(String id,boolean reviewed) {freeze(id);String author=start(id,"author");read(author,1,200);submit(author,design("完整源码设计",false));finish(author);if(reviewed){String reviewer=start(id,"reviewer");read(reviewer,1,200);input(reviewer,"draft");submit(reviewer,review(false));finish(reviewer);}}
    private String create(boolean context){return create(context,java.util.function.UnaryOperator.identity());}
    private String create(boolean context,java.util.function.UnaryOperator<WorkflowGraph> transform) {
        var source=preset("source.snapshot","source",List.of(new Input("path",InputSource.REQUIREMENT,"path",null,DataKind.TEXT,true)));
        var input=new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true);
        var author=preset("source.design","author",List.of(input));var steps=new ArrayList<Node>(List.of(source,author));
        var edges=new ArrayList<Edge>(List.of(new Edge("source-author","source","author",null),new Edge("author-reviewer","author","reviewer",null)));
        var inputs=new ArrayList<Input>(List.of(input,new Input("draft",InputSource.NODE,"author","design",DataKind.JSON,true)));
        if(context){steps.add(preset("source.design","other",List.of(input)));inputs.add(new Input("context",InputSource.NODE,"other","design",DataKind.JSON,true));edges.add(new Edge("source-other","source","other",null));edges.add(new Edge("other-reviewer","other","reviewer",null));}
        steps.add(preset("source.design-review","reviewer",inputs));
        var graph=transform.apply(new WorkflowGraph(1,steps,edges,List.of(new PublicInput("path","源码路径",DataKind.TEXT,true))));
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"专业设计流程","",graph,CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"详细设计","说明源码行为",template.id(),1));plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private Node preset(String preset,String id,List<Input> inputs){var n=presets.get(preset,1).node();return new Node(id,n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),inputs,n.outputs(),n.outcomes(),n.completion(),0,false,n.parameters(),n.roleRevisionId());}
    private void freeze(String id){String run=start(id,"source");sourceExecution.advance(run);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");}
    private void requestStart(String id,String node){controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,node,Map.of("path",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("src"))),MODEL,List.of()));}
    private String start(String id,String node){requestStart(id,node);dispatch.advance(id);String run=nodes.node(id,plans.require(id).headRevision(),node).latestAttemptId();assertThat(run).as(controls.get(id).reasonCode()).isNotNull();if(WorkflowModelProfile.ADAPTER.equals(nodes.attempt(run).adapterKey()))for(int i=0;i<4&&!models.require(run).state().equals("RUNNING");i++)execution.advance(run);return run;}
    private WorkflowSourceReads.SourceText read(String run,int start,int limit){return (WorkflowSourceReads.SourceText)call(run,WorkflowModelProfile.FILE,Map.of("name","source","path","src/Main.java","sha256",hash(),"startLine",start,"lineCount",limit));}
    private void input(String run,String name){int offset=0;while(true){var value=(Map<?,?>)call(run,WorkflowModelProfile.INPUT,Map.of("name",name,"offset",offset));if(value.get("nextOffset")==null)return;offset=((Number)value.get("nextOffset")).intValue();}}
    private List<SourceDesign.Reference> references(boolean fake){return List.of(new SourceDesign.Reference("src/Main.java",hash(),1,1,fake?"class Other {":"class Main {"));}
    private WorkflowDelivery design(String markdown,boolean fake){return delivery("design",DataKind.JSON,new SourceDesign.Candidate("Main 设计","说明职责",List.of(new SourceDesign.Section("main","主模块",markdown,List.of("src/Main.java"),references(fake))),List.of()),null);}
    private WorkflowDelivery review(boolean revise){return delivery("review",DataKind.DECISION,new SourceDesign.Review(revise?"REVISE":"PASS",revise?"需要补充":"源码与设计一致",List.of("src/Main.java"),references(false),revise?List.of(new SourceDesign.Issue("main","缺少异常说明","补充异常路径")):List.of()),revise?"REVISE":"PASS");}
    private WorkflowDelivery delivery(String name,DataKind kind,Object value,String outcome){return new WorkflowDelivery("专业交付",outcome,Map.of(name,new WorkflowDelivery.Value(kind,json.valueToTree(value)),"summary",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("本批工作说明"))));}
    private Object submit(String run,WorkflowDelivery delivery){return call(run,WorkflowModelProfile.SUBMIT,submission(run,delivery));}
    private Map<String,Object> submission(String run,WorkflowDelivery delivery){return Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(run).version(),"delivery",delivery);}
    private Object call(String run,String tool,Map<String,Object> args){return tools.call(tool,Map.of("scope",identity.grant(models.require(run)),"attemptId",run,"args",args));}
    private void finish(String run){fake.setSessionState(nodes.attempt(run).externalSessionId(),"COMPLETED");execution.advance(run);}
    private String hash(){try{return SourceTreeCapture.hash(Files.readAllBytes(root.resolve("src/Main.java")));}catch(Exception failure){throw new RuntimeException(failure);}}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-source-design-tests-");}catch(Exception failure){throw new ExceptionInInitializerError(failure);}}
}
