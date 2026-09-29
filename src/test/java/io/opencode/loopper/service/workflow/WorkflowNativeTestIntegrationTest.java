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
class WorkflowNativeTestIntegrationTest {
    private static final Path DATA=data();
    private static final String SOURCE="public class Calculator { public static int total(int a, int b) { return a + b; } }\n";
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("design.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
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
    String sourceFolder="src/main/java",sourceFile="src/main/java/Calculator.java",sourceText=SOURCE,framework="maven",addedTest="src/test/java/AddedTest.java",moduleRoot=".";
    @BeforeEach void prepare()throws Exception {
        flyway.clean();flyway.migrate();
        roles.seedBuiltin();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_WRITE,true);
        root=Files.createDirectory(directory.resolve("project"));Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/Calculator.java"),SOURCE);
        Files.writeString(root.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>native-test</artifactId><version>1</version>
                <properties><maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.8.2</version><scope>test</scope></dependency></dependencies>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version></plugin></plugins></build></project>
                """);
        Files.createDirectories(root.resolve("src/test/java"));Files.writeString(root.resolve("src/test/java/ExistingTest.java"),"import org.junit.jupiter.api.Test; class ExistingTest { @Test void oldTest() { org.junit.jupiter.api.Assertions.assertEquals(1, 1); } }");
        project=projects.create("原生单测",root.toString(),"").id();
    }
    @Autowired WorkflowBuiltinFlows builtins;
    @Autowired WorkflowPlanCandidates candidates;
    @Autowired WorkflowSourcePlanExecution planExecution;
    @Autowired WorkflowSourcePlanStore planStore;
    @Autowired WorkflowTestPlanBuilder planBuilder;
    @Autowired WorkflowTestSummaryBuilder summaryBuilder;
    @Autowired WorkflowCommandExecution commands;
    @Autowired WorkflowCommandStore commandStore;
    @Autowired WorkflowCommandActions commandActions;
    @Autowired WorkflowNativeTestEvidence nativeEvidence;
    @Autowired WorkflowNativeTestContract nativeContract;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void builtinUnitTestPlanRequiresConfirmationThenCompletesFinalCodeAcrossModules(boolean removeReviews)throws Exception {
        twoModules();builtinTests(graph->removeReviews?withoutReviews(graph):graph);
        String planner=start("batches");planExecution.advance(planner);
        assertThat(nodes.attempt(planner).state()).isEqualTo("SUCCEEDED");assertThat(nodes.attempt(planner).roleSnapshotJson()).isNull();
        var candidate=candidates.get(id,candidates.list(id,"PENDING",null,50).items().getFirst().id());
        assertThat(candidate.diagnostics()).isEmpty();assertThat(candidate.sourceCompleted()).isTrue();assertThat(plans.require(id).headRevision()).isEqualTo(1);
        dispatch.advance(id);assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_model_launch",Integer.class)).isZero();
        candidates.apply(id,candidate.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,candidate.version(),null));
        assertThat(plans.require(id).state()).isEqualTo("PAUSED");dispatch.advance(id);assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_model_launch",Integer.class)).isZero();
        var graph=plans.get(id,null).graph();var designs=graph.nodes().stream().filter(n->WorkflowTestDesign.MODULE.equals(n.moduleId())).toList();assertThat(designs).hasSize(2);
        for(var node:designs){sourceFile=encoding.decode(node.parameters().get("targetPaths"),String[].class)[0];attempt=start(node.id());input();read(1,200);submit(candidate());finish();}
        var writers=graph.nodes().stream().filter(n->WorkflowTestWrite.MODULE.equals(n.moduleId())).toList();assertThat(writers).hasSize(2);
        for(int i=0;i<writers.size();i++) {
            var node=writers.get(i);sourceFile=(i==0?"one":"two")+"/src/main/java/Calculator.java";addedTest=(i==0?"one":"two")+"/src/test/java/AddedTest.java";
            attempt=start(node.id());if(i>0)assertThat(root.resolve("one/src/test/java/AddedTest.java")).exists();writerRead();writeTest(false);writerSubmit();finish();assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");
        }
        String finalWriter=attempt;
        for(var node:graph.nodes())if(WorkflowNativeTest.MODULE.equals(node.moduleId())) {
            String run=start(node.id());done(run);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");
            var report=encoding.decode(nodes.delivery(run).contentJson(),WorkflowDelivery.class).outputs().get("report").content();assertThat(report.path("producerAttempt").asString()).isEqualTo(finalWriter);
        }
        var reviews=graph.nodes().stream().filter(n->WorkflowTestReview.MODULE.equals(n.moduleId())).toList();assertThat(reviews).hasSize(removeReviews?0:4);
        for(var node:reviews) {
            var designKey=node.inputs().stream().filter(i->i.name().equals("design")).findFirst().orElseThrow().sourceId();
            sourceFile=encoding.decode(designs.stream().filter(n->n.id().equals(designKey)).findFirst().orElseThrow().parameters().get("targetPaths"),String[].class)[0];
            addedTest=sourceFile.substring(0,sourceFile.indexOf('/'))+"/src/test/java/AddedTest.java";completeFinalReview(node.id());
        }
        if(!removeReviews) {
            jdbc.execute("CREATE TRIGGER fail_summary_stop BEFORE INSERT ON workflow_attempt_stop WHEN NEW.kind='NO_EXTERNAL_WORK' AND EXISTS (SELECT 1 FROM workflow_node_attempt a WHERE a.id=NEW.attempt_id AND a.adapter_key='system.source.test-summary.v1') BEGIN SELECT RAISE(ABORT,'summary rollback'); END");
            requestStart("summary");dispatch.advance(id);assertThat(nodes.node(id,2,"summary").latestAttemptId()).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_node_attempt WHERE adapter_key='system.source.test-summary.v1'",Integer.class)).isZero();jdbc.execute("DROP TRIGGER fail_summary_stop");
        }
        String summary=start("summary");var saved=nodes.delivery(summary);var report=encoding.decode(saved.contentJson(),WorkflowDelivery.class).outputs().get("report").content();
        assertThat(report.path("passed").asBoolean()).isTrue();assertThat(report.path("complete").asBoolean()).isTrue();assertThat(report.path("coveredSourceCount").asInt()).isEqualTo(2);assertThat(report.path("moduleCount").asInt()).isEqualTo(2);
        assertThat(report.path("batchCount").asInt()).isEqualTo(2);assertThat(report.path("reviewPolicy").asString()).isEqualTo(removeReviews?"NONE":"DUAL");assertThat(report.path("reviewedBatchCount").asInt()).isEqualTo(removeReviews?0:2);
        assertThat(report.path("writerAttempt").asString()).isEqualTo(finalWriter);assertThat(nodes.hasStop(summary)).isTrue();assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        var fixed=nodes.inputs(nodes.attempt(summary));var definition=nodes.definition(nodes.requireNode(nodes.attempt(summary).nodeRunId()));
        var subset=fixed.values().stream().filter(v->!v.name().equals("test_2")&&!v.name().startsWith("review_2_")).toList();
        assertThat(summaryBuilder.build(definition,copy(fixed,subset)).passed()).isFalse();
        if(!removeReviews)assertThat(summaryBuilder.build(definition,copy(fixed,fixed.values().stream().filter(v->!v.name().startsWith("review_")||!v.name().endsWith("_2")).toList())).passed()).isFalse();
        if(!removeReviews) {
            var single=new Node(definition.id(),definition.title(),definition.kind(),definition.moduleId(),definition.moduleVersion(),null,definition.task(),definition.inputs(),definition.outputs(),definition.outcomes(),definition.completion(),0,false,Map.of("reviewPolicy","SINGLE"));
            assertThat(summaryBuilder.build(single,copy(fixed,fixed.values().stream().filter(v->!v.name().startsWith("review_")||!v.name().endsWith("_2")).toList())).passed()).isTrue();
            var duplicated=new ArrayList<>(fixed.values());duplicated.add(rename(fixed.values().stream().filter(v->v.name().startsWith("review_")).findFirst().orElseThrow(),"review_duplicate"));
            assertThatThrownBy(()->summaryBuilder.build(definition,copy(fixed,duplicated))).isInstanceOf(BadRequestException.class);
        }
        assertThat(root.resolve("one/src/test/java/AddedTest.java")).doesNotExist();assertThat(root.resolve("two/src/test/java/AddedTest.java")).doesNotExist();assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void unitTestPlanningRollsBackCandidateAndResumesTheSameAttempt() {
        builtinTests(java.util.function.UnaryOperator.identity());String run=start("batches");
        jdbc.execute("CREATE TRIGGER fail_test_plan BEFORE INSERT ON workflow_plan_candidate BEGIN SELECT RAISE(ABORT,'test plan rollback'); END");
        assertThatThrownBy(()->planExecution.advance(run)).hasStackTraceContaining("test plan rollback");assertThat(nodes.findDelivery(run)).isEmpty();assertThat(nodes.hasStop(run)).isFalse();assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();
        jdbc.execute("DROP TRIGGER fail_test_plan");planExecution.advance(run);planExecution.advance(run);assertThat(candidates.list(id,"PENDING",null,50).items()).hasSize(1);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");
    }
    @Test void cancellingTestPlanningRejectsLateCandidate() {
        builtinTests(java.util.function.UnaryOperator.identity());String run=start("batches");var context=planStore.context(run);var result=planBuilder.build(context);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消单测规划"));drain.stop(id,run);planStore.finish(context,planBuilder.delivery(result,null),true);
        assertThat(nodes.findDelivery(run)).isEmpty();assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();assertThat(finishes.finalizeReady(id)).isTrue();
    }
    @Test void incompleteReviewConfigurationDoesNotSilentlyRecreateMissingReviewer() {
        builtinTests(graph->new WorkflowGraph(1,graph.nodes().stream().filter(n->!n.id().equals("risk")).map(n->n.id().equals("summary")?new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),n.inputs().stream().filter(i->!i.sourceId().equals("risk")).toList(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),n.parameters(),n.roleRevisionId()):n).toList(),graph.edges().stream().filter(e->!e.from().equals("risk")&&!e.to().equals("risk")).toList(),graph.inputs()));
        String run=start("batches");planExecution.advance(run);assertThat(nodes.attempt(run).state()).isEqualTo("FAILED");assertThat(nodes.delivery(run).contentJson()).contains("WORKFLOW_TEST_PLAN_INVALID");assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();
    }
    @Test void testBatchExpansionPreservesCustomWorkAndReportsCapacityWithoutDroppingSources()throws Exception {
        Files.createDirectories(root.resolve("src/main/java/other"));Files.writeString(root.resolve("src/main/java/other/Other.java"),"class Other {}\n");
        builtinTests(graph->{var base=withoutReviews(graph);return new WorkflowGraph(1,base.nodes().stream().map(n->n.id().equals("design")?new Node(n.id(),"自定义场景",n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),"补充边界与异常",n.inputs(),n.outputs(),n.outcomes(),n.completion(),2,true,Map.of("custom","kept"),n.roleRevisionId()):n).toList(),base.edges(),base.inputs());});
        String run=start("batches");planExecution.advance(run);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");
        var candidate=candidates.get(id,candidates.list(id,"PENDING",null,50).items().getFirst().id());
        var originals=plans.get(id,null).graph().nodes();var designs=candidate.graph().nodes().stream().filter(n->WorkflowTestDesign.MODULE.equals(n.moduleId())).toList();assertThat(designs).hasSize(2);
        for(var n:designs){assertThat(n.roleRevisionId()).isEqualTo(originals.stream().filter(o->o.id().equals("design")).findFirst().orElseThrow().roleRevisionId());assertThat(n.task()).isEqualTo("补充边界与异常");assertThat(n.maxRetries()).isEqualTo(2);assertThat(n.pauseAfter()).isTrue();assertThat(n.parameters()).containsEntry("custom","kept");}
        assertThat(candidate.graph().nodes()).noneMatch(n->WorkflowTestReview.MODULE.equals(n.moduleId()));
        var test=candidate.graph().nodes().stream().filter(n->WorkflowNativeTest.MODULE.equals(n.moduleId())).findFirst().orElseThrow();assertThat(test.inputs().stream().filter(i->i.name().startsWith("design"))).hasSize(2);
        for(int i=0;i<32;i++){Files.createDirectories(root.resolve("src/main/java/p"+i));Files.writeString(root.resolve("src/main/java/p"+i+"/Part.java"),"class Part {}\n");}
        builtinTests(java.util.function.UnaryOperator.identity());run=start("batches");planExecution.advance(run);
        assertThat(nodes.attempt(run).state()).isEqualTo("FAILED");assertThat(nodes.delivery(run).contentJson()).contains("WORKFLOW_TEST_PLAN_LIMIT");assertThat(candidates.list(id,"PENDING",null,50).items()).isEmpty();assertThat(plans.require(id).headRevision()).isEqualTo(1);
    }
    @Test void explicitNativeFailureContinuationCannotBecomeASuccessfulSummary()throws Exception {
        builtinTests(graph->{var base=withoutReviews(graph);return new WorkflowGraph(1,base.nodes().stream().map(n->WorkflowNativeTest.MODULE.equals(n.moduleId())?new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),null,n.task(),n.inputs(),n.outputs(),n.outcomes(),new Completion(CompletionKind.DELIVERABLES,"保留真实失败继续汇总",null),0,false,n.parameters()):n).toList(),base.edges(),base.inputs());});
        String planner=start("batches");planExecution.advance(planner);var candidate=candidates.get(id,candidates.list(id,"PENDING",null,50).items().getFirst().id());candidates.apply(id,candidate.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,candidate.version(),null));
        attempt=start("design");input();read(1,200);submit(candidate());finish();attempt=start("write");writerRead();writeTest(true);writerSubmit();finish();
        String test=start("test");done(test);assertThat(nodes.attempt(test).state()).isEqualTo("SUCCEEDED");
        String summary=start("summary");assertThat(nodes.attempt(summary).state()).isEqualTo("FAILED");var report=encoding.decode(nodes.delivery(summary).contentJson(),WorkflowDelivery.class).outputs().get("report").content();
        assertThat(report.path("complete").asBoolean()).isTrue();assertThat(report.path("testPassed").asBoolean()).isFalse();assertThat(report.path("passed").asBoolean()).isFalse();assertThat(report.path("reviewPolicy").asString()).isEqualTo("NONE");assertThat(plans.require(id).state()).isEqualTo("STALLED");
    }
    private void builtinTests(java.util.function.UnaryOperator<WorkflowGraph> transform) {
        builtins.publish();var builtin=templates.get("builtin.workflow.source-unit-test",null);var template=templates.create(new WorkflowRequests.CreateTemplate(key(),builtin.title(),builtin.description(),transform.apply(builtin.graph()),CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"完整单测","补齐全部源码单测",template.id(),1));id=owner.id();plans.confirm(id,new WorkflowRequests.VersionCommand(key(),owner.version()));
        source=start("source");sources.advance(source);profile=start("profile");profiles.advance(profile);assertThat(nodes.attempt(profile).state()).isEqualTo("SUCCEEDED");
    }
    private WorkflowGraph withoutReviews(WorkflowGraph graph) {
        var result=graph.nodes().stream().filter(n->!WorkflowTestReview.MODULE.equals(n.moduleId())).map(n->!WorkflowTestSummary.MODULE.equals(n.moduleId())?n:new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),n.inputs().stream().filter(i->i.kind()!=DataKind.DECISION).toList(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),Map.of("reviewPolicy","NONE"),n.roleRevisionId())).toList();
        return new WorkflowGraph(1,result,graph.edges().stream().filter(e->!Set.of("review","risk").contains(e.from())&&!Set.of("review","risk").contains(e.to())).toList(),graph.inputs());
    }
    private void twoModules()throws Exception {
        String pom=Files.readString(root.resolve("pom.xml"));Files.createDirectories(root.resolve("one"));Files.move(root.resolve("src"),root.resolve("one/src"));Files.move(root.resolve("pom.xml"),root.resolve("one/pom.xml"));
        Files.createDirectories(root.resolve("two/src/main/java"));Files.createDirectories(root.resolve("two/src/test/java"));Files.writeString(root.resolve("two/pom.xml"),pom.replace("native-test","native-test-two"));Files.writeString(root.resolve("two/src/main/java/Calculator.java"),SOURCE);
        Files.writeString(root.resolve("two/src/test/java/ExistingTest.java"),"import org.junit.jupiter.api.Test; class ExistingTest { @Test void oldTest() { org.junit.jupiter.api.Assertions.assertEquals(1, 1); } }");
        Files.writeString(root.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging><modules><module>one</module><module>two</module></modules></project>");sourceFolder=".";
    }
    private void completeFinalReview(String node)throws Exception {
        attempt=start(node);input("profile");input("design");input("test");read(1,200);var context=reviewContract.context(models.definition(models.require(attempt)),nodes.inputs(nodes.attempt(attempt)));
        var actual=context.cases().stream().filter(c->WorkflowNativeCases.matches(c,addedTest,context.original().module())).findFirst().orElseThrow();var file=context.code().files().stream().filter(f->f.path().equals(addedTest)).findFirst().orElseThrow();
        var page=(WorkflowSourceReads.SourceText)call(WorkflowModelProfile.FILE,Map.of("name","code","path",addedTest,"sha256",file.sha256(),"startLine",1,"lineCount",200));
        reviewSubmit(new WorkflowTestReview.Candidate("独立核对本批最终代码与执行",List.of(new WorkflowTestReview.Mapping("sum","COVERED","核对固定求和断言",List.of(actual.id()),List.of(new SourceDesign.Reference(addedTest,file.sha256(),1,page.endLine(),page.content()))))));finish();assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void finalCodeRetestsAllBatchesAndReviewsEachDesignAgainstThatSameExecution(boolean laterFailure)throws Exception {
        finalBatches=true;withReview=true;create(true);
        attempt=start("write");writerRead();writeTest(false);writerSubmit();finish();String firstWriter=attempt;
        String oldTest=start("old-test");done(oldTest);
        assertThat(nodes.attempt(oldTest).state()).isEqualTo("SUCCEEDED");
        attempt=start("design2");input();read(1,200);submit(candidate());finish();String secondDesign=attempt;
        attempt=start("write2");writerRead();
        assertThat(root.resolve(addedTest)).exists();
        Files.writeString(root.resolve("src/test/java/SecondTest.java"),"import org.junit.jupiter.api.Test; class SecondTest { @Test void sum() { org.junit.jupiter.api.Assertions.assertEquals("+(laterFailure?"4":"3")+", Calculator.total(1, 2)); } }");
        writerSubmit();finish();String finalWriter=attempt;assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");
        String test=start("test");if(laterFailure)done(test);else recoverSavedReport(test);
        var report=encoding.decode(nodes.delivery(test).contentJson(),WorkflowDelivery.class).outputs().get("report").content();
        assertThat(report.path("version").asInt()).isEqualTo(2);assertThat(report.path("passed").asBoolean()).isEqualTo(!laterFailure);
        assertThat(report.path("counts").path("total").asInt()).isEqualTo(3);assertThat(report.path("producerAttempt").asString()).isEqualTo(finalWriter);
        assertThat(encoding.decode(encoding.encode(report.path("writerLineage")),String[].class)).containsExactly(finalWriter,firstWriter);
        assertThat(report.path("batches").size()).isEqualTo(2);assertThat(report.path("batches").get(0).path("designAttempt").asString()).isEqualTo(design);
        assertThat(report.path("batches").get(1).path("designAttempt").asString()).isEqualTo(secondDesign);
        assertThat(report.path("batches").get(0).path("scenarioCount").asInt()).isEqualTo(1);assertThat(report.path("batches").get(1).path("scenarioCount").asInt()).isEqualTo(1);
        var fixed=nodes.inputs(nodes.attempt(test));var definition=encoding.decode(jdbc.queryForObject("SELECT definition_json FROM workflow_node_run WHERE id=?",String.class,nodes.attempt(test).nodeRunId()),Node.class);
        // V1 must not gain V2 ancestry semantics, and listing one design twice cannot inflate coverage.
        var legacy=new Node(definition.id(),definition.title(),definition.kind(),definition.moduleId(),1,null,definition.task(),definition.inputs().subList(0,4),definition.outputs(),definition.outcomes(),definition.completion(),0,false,definition.parameters());
        assertThatThrownBy(()->nativeContract.resolve(legacy,copy(fixed,fixed.values().subList(0,4)))).isInstanceOf(BadRequestException.class);
        var duplicate=fixed.values().stream().map(v->!v.name().equals("design_second")?v:rename(fixed.values().stream().filter(i->i.name().equals("design")).findFirst().orElseThrow(),v.name())).toList();
        assertThatThrownBy(()->nativeContract.resolve(definition,copy(fixed,duplicate))).isInstanceOf(BadRequestException.class);
        for(String reviewer:List.of("review","review2")) {
            attempt=start(reviewer);input("profile");input("design");input("test");read(1,200);
            var oldDelivery=nodes.delivery(oldTest);var oldReport=encoding.decode(oldDelivery.contentJson(),WorkflowDelivery.class).outputs().get("report");
            var reviewValues=nodes.inputs(nodes.attempt(attempt));var borrowed=reviewValues.values().stream().map(i->!i.name().equals("test")?i:
                    new WorkflowDelivery.Input("test",DataKind.JSON,"NODE",nodes.attempt(oldTest).nodeRunId(),"report",oldTest,oldDelivery.sha256(),oldReport.content())).toList();
            assertThatThrownBy(()->reviewContract.context(models.definition(models.require(attempt)),copy(reviewValues,borrowed))).isInstanceOf(BadRequestException.class);
            var context=reviewContract.context(models.definition(models.require(attempt)),nodes.inputs(nodes.attempt(attempt)));
            String path=reviewer.equals("review")?addedTest:"src/test/java/SecondTest.java";
            var actual=context.cases().stream().filter(c->WorkflowNativeCases.matches(c,path,context.original().module())).findFirst().orElseThrow();
            var file=context.code().files().stream().filter(f->f.path().equals(path)).findFirst().orElseThrow();
            var page=(WorkflowSourceReads.SourceText)call(WorkflowModelProfile.FILE,Map.of("name","code","path",path,"sha256",file.sha256(),"startLine",1,"lineCount",200));
            reviewSubmit(new WorkflowTestReview.Candidate("按最终代码核对本批场景",List.of(new WorkflowTestReview.Mapping("sum","COVERED","核对固定求和断言",List.of(actual.id()),List.of(new SourceDesign.Reference(path,file.sha256(),1,page.endLine(),page.content()))))));finish();
            assertThat(delivery().outcome()).isEqualTo(laterFailure?"REVISE":"PASS");
            assertThat(delivery().outputs().get("review").content().path("writerAttempt").asString()).isEqualTo(finalWriter);
            assertThat(delivery().outputs().get("review").content().path("testAttempt").asString()).isEqualTo(test);
        }
        assertThat(root.resolve(addedTest)).doesNotExist();assertThat(root.resolve("src/test/java/SecondTest.java")).doesNotExist();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void aDependencyEdgeWithoutWorkspaceInheritanceCannotClaimEarlierBatchCode(boolean alternative)throws Exception {
        finalBatches=true;unseededBatch=!alternative;alternativeSeed=alternative;create(true);attempt=start("write");writerRead();writeTest(false);writerSubmit();finish();
        String old=start("old-test");done(old);attempt=start("design2");input();read(1,200);submit(candidate());finish();
        if(alternative) {
            attempt=start("other-write");writerRead();
            Files.writeString(root.resolve("src/test/java/AlternativeTest.java"),"import org.junit.jupiter.api.Test; class AlternativeTest { @Test void sum() { org.junit.jupiter.api.Assertions.assertEquals(3, Calculator.total(1, 2)); } }");
            writerSubmit();finish();
        }
        attempt=start("write2");writerRead();assertThat(root.resolve(addedTest)).doesNotExist();writeTest(false);writerSubmit();finish();
        assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");requestStart("test");dispatch.advance(id);
        assertThat(nodes.node(id,1,"test").latestAttemptId()).isNull();assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_TEST_CODE_LINEAGE_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_command_run",Integer.class)).isEqualTo(1);
    }
    @Test void frozenV1NativeEvidenceAndActiveReviewRemainUsableWithTheCurrentCatalog()throws Exception {
        withReview=true;create(false);attempt=start("write");writerRead();writeTest(false);writerSubmit();finish();String test=start("test");done(test);
        attempt=start("review");input("profile");input("design");input("test");read(1,200);
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("workflow_node_attempt","workflow_node_delivery","workflow_command_run","workflow_native_test_evidence","workflow_source_read","workflow_design_input_read"))
            before.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        // The current binary runs only after migration. Historical SQL upgrade preservation is
        // covered by WorkflowControlMigrationTest; here a current V2 catalog must not rewrite V1 runs.
        builtins.publish();flyway.validate();
        before.forEach((table,rows)->assertThat(jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1")).isEqualTo(rows));
        var context=reviewContract.context(models.definition(models.require(attempt)),nodes.inputs(nodes.attempt(attempt)));
        var actual=context.cases().stream().filter(c->WorkflowNativeCases.matches(c,addedTest,context.original().module())).findFirst().orElseThrow();
        var file=context.code().files().stream().filter(f->f.path().equals(addedTest)).findFirst().orElseThrow();
        var page=(WorkflowSourceReads.SourceText)call(WorkflowModelProfile.FILE,Map.of("name","code","path",addedTest,"sha256",file.sha256(),"startLine",1,"lineCount",200));
        reviewSubmit(new WorkflowTestReview.Candidate("升级后按原版本继续复核",List.of(new WorkflowTestReview.Mapping("sum","COVERED","原固定断言与测试一致",List.of(actual.id()),List.of(new SourceDesign.Reference(addedTest,file.sha256(),1,page.endLine(),page.content()))))));finish();
        assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");assertThat(delivery().outcome()).isEqualTo("PASS");
        assertThat(flyway.migrate().migrationsExecuted).isZero();assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void eachModuleRegressesItsOwnDesignUsingTheSameFinalCodeFromTheOtherModule()throws Exception {
        String pom=Files.readString(root.resolve("pom.xml"));
        Files.createDirectories(root.resolve("one"));Files.move(root.resolve("src"),root.resolve("one/src"));Files.move(root.resolve("pom.xml"),root.resolve("one/pom.xml"));
        Files.createDirectories(root.resolve("two/src/main/java"));Files.createDirectories(root.resolve("two/src/test/java"));
        Files.writeString(root.resolve("two/pom.xml"),pom.replace("native-test","native-test-two"));Files.writeString(root.resolve("two/src/main/java/Calculator.java"),SOURCE);
        Files.writeString(root.resolve("two/src/test/java/ExistingTest.java"),"import org.junit.jupiter.api.Test; class ExistingTest { @Test void oldTest() { org.junit.jupiter.api.Assertions.assertEquals(1, 1); } }");
        Files.writeString(root.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging><modules><module>one</module><module>two</module></modules></project>");
        finalBatches=true;secondModule=true;moduleRoot="one";sourceFolder=".";sourceFile="one/src/main/java/Calculator.java";addedTest="one/src/test/java/AddedTest.java";
        create(false);attempt=start("write");writerRead();writeTest(false);writerSubmit();finish();String firstWriter=attempt;String old=start("old-test");done(old);
        sourceFile="two/src/main/java/Calculator.java";addedTest="two/src/test/java/AddedTest.java";
        attempt=start("design2");input();read(1,200);submit(candidate());finish();attempt=start("write2");writerRead();writeTest(false);writerSubmit();finish();String finalWriter=attempt;
        assertThat(nodes.attempt(finalWriter).state()).isEqualTo("SUCCEEDED");
        for(String node:List.of("test","test2")) {
            String run=start(node);done(run);assertThat(nodes.attempt(run).state()).isEqualTo("SUCCEEDED");
            var report=encoding.decode(nodes.delivery(run).contentJson(),WorkflowDelivery.class).outputs().get("report").content();
            assertThat(report.path("producerAttempt").asString()).isEqualTo(finalWriter);assertThat(report.path("moduleRoot").asString()).isEqualTo(node.equals("test")?"one":"two");
            assertThat(report.path("batches").get(0).path("writerAttempt").asString()).isEqualTo(node.equals("test")?firstWriter:finalWriter);
            assertThat(report.path("counts").path("total").asInt()).isEqualTo(2);assertThat(report.path("inputUnchanged").asBoolean()).isTrue();
        }
        assertThat(root.resolve("one/src/test/java/AddedTest.java")).doesNotExist();assertThat(root.resolve(addedTest)).doesNotExist();
    }
    private static Node version(Node n,int version){return new Node(n.id(),n.title(),n.kind(),n.moduleId(),version,n.roleId(),n.task(),n.inputs(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),n.parameters(),n.roleRevisionId());}
    private static WorkflowDelivery.Input rename(WorkflowDelivery.Input i,String name){return new WorkflowDelivery.Input(name,i.kind(),i.source(),i.sourceId(),i.outputName(),i.attemptId(),i.sha256(),i.content());}
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="LOOPPER_NATIVE_FRAMEWORK_TESTS",matches="1")
    @ParameterizedTest @ValueSource(strings={"vitest","jest","pytest","gradle"})
    void realAdditionalFrameworksPreparePrivateDependenciesAndPublishNativeEvidence(String framework)throws Exception {
        withReview=true;configureFramework(framework);create(false);attempt=start("write");writerRead();writeTest(false);writerSubmit();finish();
        assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");String test=start("test");done(test);
        var report=encoding.decode(nodes.delivery(test).contentJson(),WorkflowDelivery.class).outputs().get("report").content();
        assertThat(nodes.attempt(test).state()).as(commandActions.evidence(id,"test",test).toString()).isEqualTo("SUCCEEDED");
        assertThat(report.path("counts").path("total").asInt()).isEqualTo(2);assertThat(report.path("passed").asBoolean()).isTrue();
        assertThat(report.path("inputUnchanged").asBoolean()).isTrue();
        var request=commandStore.request(commandStore.require(test));
        assertThat(request.preparations()).hasSize(framework.equals("pytest")?2:framework.equals("gradle")?0:1);
        if(framework.equals("gradle"))assertThat(request.argv().getFirst()).endsWith(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"gradlew.bat":"gradlew");
        assertThat(Files.readString(root.resolve(sourceFile))).isEqualTo(sourceText);assertThat(root.resolve(addedTest)).doesNotExist();
        assertThat(root.resolve("node_modules")).doesNotExist();assertThat(root.resolve(".loopper-test-env")).doesNotExist();
        reviewNativeCase(test);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void aPassingNativeTestThatChangesAnOriginalInputCannotPassTheFixedCodeNode()throws Exception {
        create(false);attempt=start("write");writerRead();
        Files.writeString(root.resolve(addedTest),"import org.junit.jupiter.api.Test; class AddedTest { @Test void sum() throws Exception { org.junit.jupiter.api.Assertions.assertEquals(3, Calculator.total(1, 2)); java.nio.file.Path p=java.nio.file.Paths.get(\"pom.xml\"); java.nio.file.Files.write(p, \"\\n\".getBytes(), java.nio.file.StandardOpenOption.APPEND); } }");
        writerSubmit();finish();assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");String test=start("test");done(test);
        var report=encoding.decode(nodes.delivery(test).contentJson(),WorkflowDelivery.class).outputs().get("report").content();
        assertThat(nodes.attempt(test).state()).isEqualTo("FAILED");assertThat(report.path("exitCode").asInt()).isZero();
        assertThat(report.path("counts").path("passed").asInt()).isEqualTo(2);assertThat(report.path("valid").asBoolean()).isFalse();
        assertThat(report.path("inputUnchanged").asBoolean()).isFalse();assertThat(report.path("message").asString()).contains("发生变化");
    }
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="LOOPPER_NATIVE_FRAMEWORK_TESTS",matches="1")
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realNpmPreparationFailureOrCancellationNeverStartsTheTests(boolean cancel)throws Exception {
        configureFramework("vitest");var packageJson=encoding.decode(Files.readString(root.resolve("package.json")),tools.jackson.databind.node.ObjectNode.class);
        ((tools.jackson.databind.node.ObjectNode)packageJson.path("scripts")).put("preinstall","node prepare.cjs");Files.writeString(root.resolve("package.json"),encoding.encode(packageJson));
        Files.writeString(root.resolve("prepare.cjs"),cancel?"require('fs').writeFileSync('preparation-started', 'yes'); setInterval(() => {}, 1000);":"process.exit(9);");
        create(true);attempt=start("write");writerRead();writeTest(false);writerSubmit();finish();String test=start("test");
        if(cancel) {
            await(()->{commands.advance(test);var row=commandStore.require(test);return row.requestJson()!=null&&Files.exists(Path.of(commandStore.request(row).directory()).resolve("preparation-started"));});
            commandActions.stop(id,"test",test,new WorkflowCommandActions.Command(key(),commandStore.require(test).version()));
        }
        done(test);var evidence=commandActions.evidence(id,"test",test);
        assertThat(nodes.attempt(test).state()).isEqualTo(cancel?"CANCELLED":"FAILED");assertThat(evidence.result().path("launched").asBoolean()).isFalse();
        assertThat(evidence.result().path("stopConfirmed").asBoolean()).isTrue();assertThat(evidence.result().path("preparations").size()).isEqualTo(1);
        assertThat(evidence.result().path("preparations").get(0).path("stopConfirmed").asBoolean()).isTrue();
        if(cancel)assertThat(nativeEvidence.find(test)).isEmpty();else assertThat(encoding.decode(nodes.delivery(test).contentJson(),WorkflowDelivery.class).outputs().get("report").content().path("valid").asBoolean()).isFalse();
        assertThat(root.resolve("node_modules")).doesNotExist();assertThat(root.resolve("preparation-started")).doesNotExist();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realNativeMavenTestsKeepOriginalCodeAndRealFailureEvenWhenFlowMayContinue(boolean failure)throws Exception {
        create(failure);attempt=start("write");writerRead();writeTest(failure);writerSubmit();finish();String writer=attempt;
        assertThat(nodes.attempt(writer).state()).isEqualTo("SUCCEEDED");String test=start("test");done(test);
        assertThat(nodes.attempt(test).state()).as(commandStore.require(test).toString()).isEqualTo("SUCCEEDED");
        var delivery=encoding.decode(nodes.delivery(test).contentJson(),WorkflowDelivery.class);var report=delivery.outputs().get("report").content();
        assertThat(delivery.outcome()).isEqualTo(failure?"FAIL":"PASS");assertThat(report.path("counts").path("total").asInt()).isEqualTo(2);
        assertThat(report.path("counts").path("failed").asInt()).isEqualTo(failure?1:0);assertThat(report.path("producerAttempt").asString()).isEqualTo(writer);
        assertThat(report.path("scenarioCoverageVerified").asBoolean()).isFalse();assertThat(nativeEvidence.find(test)).isPresent();
        assertThat(Files.readString(root.resolve("src/main/java/Calculator.java"))).isEqualTo(SOURCE);assertThat(root.resolve("src/test/java/AddedTest.java")).doesNotExist();assertThat(root.resolve("target")).doesNotExist();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void requiredFailureAndZeroExecutedTestsStallWithoutSuccess(boolean empty)throws Exception {
        if(empty){Path pom=root.resolve("pom.xml");Files.writeString(pom,Files.readString(pom).replace("<version>3.5.2</version>","<version>3.5.2</version><configuration><includes><include>**/NoTestsExist.java</include></includes></configuration>"));}
        create(false);attempt=start("write");writerRead();writeTest(!empty);writerSubmit();finish();String test=start("test");done(test);
        assertThat(nodes.attempt(test).state()).isEqualTo("FAILED");var report=encoding.decode(nodes.delivery(test).contentJson(),WorkflowDelivery.class).outputs().get("report").content();
        assertThat(report.path("passed").asBoolean()).isFalse();assertThat(report.path("valid").asBoolean()).isEqualTo(!empty);
        assertThat(report.path("counts").path("total").asInt()).isEqualTo(empty?0:2);assertThat(plans.require(id).state()).isEqualTo("STALLED");
        assertThat(commandActions.evidence(id,"test",test).nativeReport()).isNotNull();
    }
    @Test void finalRollbackRetainsExactReportsAndRecoveryDoesNotRerunOrReadChangedReports()throws Exception {checkReportRecovery();}
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="LOOPPER_NATIVE_FRAMEWORK_TESTS",matches="1")
    @Test void nativePreparationAndSavedReportsRecoverWithoutInstallingOrTestingAgain()throws Exception {
        configureFramework("vitest");checkReportRecovery();
    }
    private void checkReportRecovery()throws Exception {
        create(false);attempt=start("write");writerRead();writeTest(false);writerSubmit();finish();String test=start("test");
        recoverSavedReport(test);
    }
    private void recoverSavedReport(String test)throws Exception {
        jdbc.execute("CREATE TRIGGER fail_native_finish BEFORE INSERT ON workflow_attempt_stop WHEN NEW.attempt_id='"+test+"' BEGIN SELECT RAISE(ABORT,'native final rollback'); END");
        await(()->{commands.advance(test);return commandStore.require(test).suspended();});var saved=nativeEvidence.find(test).orElseThrow();
        assertThat(nodes.findDelivery(test)).isEmpty();assertThat(commandStore.require(test).resultJson()).isNull();
        var row=commandStore.require(test);Path root=Path.of(commandStore.request(row).directory());
        String savedPath=encoding.decode(saved.reportJson(),WorkflowNativeTestReports.Report.class).files().getFirst().path();
        Files.writeString(root.resolve(savedPath),"damaged after saved evidence");
        Files.writeString(root.resolve(addedTest),"cannot compile; a second execution must fail");
        jdbc.execute("DROP TRIGGER fail_native_finish");commandActions.resume(id,"test",test,new WorkflowCommandActions.Command(key(),row.version()));done(test);
        assertThat(nodes.attempt(test).state()).isEqualTo("SUCCEEDED");assertThat(nativeEvidence.find(test).orElseThrow()).isEqualTo(saved);
        assertThat(Files.readString(root.resolve(savedPath))).isEqualTo("damaged after saved evidence");
        assertThat(commandStore.request(commandStore.require(test)).preparations()).hasSize(framework.equals("vitest")?1:0);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_native_test_evidence SET report_json='{}' WHERE attempt_id=?",test)).hasStackTraceContaining("immutable");
    }
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="LOOPPER_NATIVE_FRAMEWORK_TESTS",matches="1")
    @Test void npmWorkspaceUsesItsRootLockButExecutesAndCollectsOnlyTheSelectedModule()throws Exception {
        configureFramework("vitest");moduleRoot="packages/web";Path child=Files.createDirectories(root.resolve(moduleRoot));
        Files.move(root.resolve("src"),child.resolve("src"));Files.move(root.resolve("tests"),child.resolve("tests"));Files.move(root.resolve("package.json"),child.resolve("package.json"));
        Files.delete(root.resolve("package-lock.json"));Files.writeString(root.resolve("package.json"),encoding.encode(Map.of("name","native-workspaces","private",true,"workspaces",List.of("packages/*"))));
        command(List.of("npm","install","--package-lock-only","--ignore-scripts","--no-audit","--no-fund"));
        sourceFolder=moduleRoot+"/src";sourceFile=moduleRoot+"/src/calculator.js";addedTest=moduleRoot+"/tests/added.test.js";
        create(false);attempt=start("write");writerRead();writeTest(false);writerSubmit();finish();String test=start("test");done(test);
        assertThat(nodes.attempt(test).state()).as(commandActions.evidence(id,"test",test).toString()).isEqualTo("SUCCEEDED");
        var request=commandStore.request(commandStore.require(test));assertThat(request.preparations().getFirst().directory()).isEqualTo(request.directory());
        assertThat(request.argv()).containsSubsequence("--prefix",moduleRoot);
        var report=encoding.decode(nodes.delivery(test).contentJson(),WorkflowDelivery.class).outputs().get("report").content();
        assertThat(report.path("moduleRoot").asString()).isEqualTo(moduleRoot);assertThat(report.path("counts").path("total").asInt()).isEqualTo(2);
        assertThat(report.path("files").get(0).path("path").asString()).startsWith(moduleRoot+"/");assertThat(root.resolve("node_modules")).doesNotExist();
    }
    @Test void codeCannotBorrowAnotherProfileOrDesignAndCancellationNeedsNoReport()throws Exception {
        create(false);attempt=start("write");writerRead();writeTest(false);writerSubmit();finish();String test=start("test");var values=nodes.inputs(nodes.attempt(test));var node=nodes.definition(nodes.requireNode(nodes.attempt(test).nodeRunId()));
        for(String name:List.of("source","profile","design","code")) {
            var changed=values.values().stream().map(v->!v.name().equals(name)?v:new WorkflowDelivery.Input(v.name(),v.kind(),v.source(),v.sourceId(),v.outputName(),"other-attempt",v.sha256(),v.content())).toList();
            assertThatThrownBy(()->nativeContract.resolve(node,copy(values,changed))).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(()->jdbc.update("INSERT INTO workflow_native_test_evidence VALUES(?,?,?,?,?,?,?)",test,"0".repeat(64),"{}","0".repeat(64),"{}","0".repeat(64),"now")).hasStackTraceContaining("owner mismatch");
        commandActions.stop(id,"test",test,new WorkflowCommandActions.Command(key(),commandStore.require(test).version()));commands.advance(test);
        assertThat(nodes.attempt(test).state()).isEqualTo("CANCELLED");assertThat(nativeEvidence.find(test)).isEmpty();
    }
    @Autowired WorkflowTestReviewContract reviewContract;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void independentScenarioReviewRequiresOwnReadsAndUsesActualNativeCaseOutcome(boolean failed)throws Exception {
        withReview=true;create(failed);attempt=start("write");writerRead();writeTest(failed);writerSubmit();finish();String writer=attempt;
        String test=start("test");done(test);attempt=start("review");
        var context=reviewContract.context(models.definition(models.require(attempt)),nodes.inputs(nodes.attempt(attempt)));
        var cases=(io.opencode.loopper.api.CursorPage<?>)call(WorkflowModelProfile.WORK,Map.of("testCases",true,"limit",1));
        assertThat(cases.items()).hasSize(1);assertThat(cases.nextCursor()).isNotNull();
        var next=(io.opencode.loopper.api.CursorPage<?>)call(WorkflowModelProfile.WORK,Map.of("testCases",true,"cursor",cases.nextCursor(),"limit",1));
        assertThat(next.items()).hasSize(1);assertThat(next.nextCursor()).isNull();assertThat(next.items().getFirst()).isNotEqualTo(cases.items().getFirst());
        assertThatThrownBy(()->call(WorkflowModelProfile.WORK,Map.of("testCases",true,"cursor",new PageCursor("other","anything").encode()))).hasMessageContaining("游标");
        var actual=context.cases().stream().filter(c->c.name().startsWith("sum")).findFirst().orElseThrow();
        var code=context.code().files().stream().filter(f->f.path().equals(addedTest)).findFirst().orElseThrow();
        var partial=new WorkflowTestReview.Candidate("核对实际断言",List.of(new WorkflowTestReview.Mapping("sum","COVERED","断言期望返回 3",List.of(actual.id()),List.of())));
        assertThatThrownBy(()->reviewSubmit(partial)).hasMessageContaining("完整读取");
        input("profile");input("design");input("test");read(1,200);
        assertThatThrownBy(()->reviewSubmit(partial)).hasMessageContaining("相匹配");
        Files.writeString(root.resolve(addedTest),"changed live project, never a review source");
        var page=(WorkflowSourceReads.SourceText)call(WorkflowModelProfile.FILE,Map.of("name","code","path",addedTest,"sha256",code.sha256(),"startLine",1,"lineCount",200));
        assertThat(page.content()).contains("assertEquals").doesNotContain("changed live");
        var ref=new SourceDesign.Reference(addedTest,code.sha256(),1,page.endLine(),page.content());
        var candidate=new WorkflowTestReview.Candidate("核对实际断言",List.of(new WorkflowTestReview.Mapping("sum","COVERED","断言和场景关联",List.of(actual.id()),List.of(ref))));
        assertThatThrownBy(()->reviewSubmit(new WorkflowTestReview.Candidate("错误关联",List.of(new WorkflowTestReview.Mapping("sum","COVERED","说明",List.of("invented"),List.of(ref)))))).hasMessageContaining("关联测试");
        var wrongRef=new SourceDesign.Reference(ref.path(),ref.sha256(),1,1,"invented assertion");
        assertThatThrownBy(()->reviewSubmit(new WorkflowTestReview.Candidate("错误引用",List.of(new WorkflowTestReview.Mapping("sum","COVERED","说明",List.of(actual.id()),List.of(wrongRef)))))).hasMessageContaining("引用");
        var args=reviewSubmission(candidate);var receipt=call(WorkflowModelProfile.SUBMIT,args);
        assertThat(nodes.attempt(attempt).state()).isEqualTo("RUNNING");assertThat(nodes.hasStop(attempt)).isFalse();finish();
        assertThat(call(WorkflowModelProfile.SUBMIT,args)).isEqualTo(receipt);assertThat(nodes.attempt(attempt).state()).isEqualTo(failed?"FAILED":"SUCCEEDED");
        var result=delivery().outputs().get("review").content();assertThat(result.path("verdict").asString()).isEqualTo(failed?"REVISE":"PASS");
        assertThat(result.path("scenarios").get(0).path("status").asString()).isEqualTo(failed?"FAILED":"COVERED");
        assertThat(result.path("writerAttempt").asString()).isEqualTo(writer);assertThat(result.path("testAttempt").asString()).isEqualTo(test);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_source_read WHERE attempt_id=?",attempt)).hasStackTraceContaining("retained");
        assertThatThrownBy(()->jdbc.update("INSERT INTO workflow_source_read VALUES(?,?,?,?,?,?,?,?,?)",attempt,"code",addedTest,code.sha256(),1,1,1,"late",java.time.Instant.now().toString())).hasStackTraceContaining("not active");
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingScenarioIsAnHonestRevisableResultAndForeignInputsAreRejected(boolean allowContinuation)throws Exception {
        allowReviewFailure=allowContinuation;withReview=true;create(false);attempt=start("write");writerRead();writeTest(false);writerSubmit();finish();String test=start("test");done(test);attempt=start("review");
        var values=nodes.inputs(nodes.attempt(attempt));var node=models.definition(models.require(attempt));
        for(String name:List.of("source","profile","design","code","test")) {
            var changed=values.values().stream().map(v->!v.name().equals(name)?v:new WorkflowDelivery.Input(v.name(),v.kind(),v.source(),v.sourceId(),v.outputName(),"other-attempt",v.sha256(),v.content())).toList();
            assertThatThrownBy(()->reviewContract.context(node,copy(values,changed))).isInstanceOf(BadRequestException.class);
        }
        input("profile");input("design");input("test");read(1,200);
        reviewSubmit(new WorkflowTestReview.Candidate("缺少验证边界的断言",List.of(new WorkflowTestReview.Mapping("sum","MISSING","尚未实现",List.of(),List.of()))));finish();
        assertThat(nodes.attempt(attempt).state()).isEqualTo(allowContinuation?"SUCCEEDED":"FAILED");
        assertThat(delivery().outcome()).isEqualTo("REVISE");assertThat(delivery().outputs().get("review").content().path("scenarios").get(0).path("status").asString()).isEqualTo("MISSING");
    }
    @Test void skippedNativeCaseCannotBecomeCoveredEvenWithAClaimedPassingReview()throws Exception {
        Files.writeString(root.resolve("src/test/java/ExistingTest.java"),"import org.junit.jupiter.api.Test; import org.junit.jupiter.api.Disabled; class ExistingTest { @Test @Disabled void later() { org.junit.jupiter.api.Assertions.assertEquals(3, Calculator.total(1, 2)); } }");
        withReview=true;create(false);attempt=start("write");writerRead();writeTest(false);writerSubmit();finish();String test=start("test");done(test);attempt=start("review");
        var context=reviewContract.context(models.definition(models.require(attempt)),nodes.inputs(nodes.attempt(attempt)));
        var actual=context.cases().stream().filter(c->c.status().equals("SKIPPED")).findFirst().orElseThrow();
        var file=context.code().files().stream().filter(f->f.path().equals("src/test/java/ExistingTest.java")).findFirst().orElseThrow();
        input("profile");input("design");input("test");read(1,200);
        var page=(WorkflowSourceReads.SourceText)call(WorkflowModelProfile.FILE,Map.of("name","code","path",file.path(),"sha256",file.sha256(),"startLine",1,"lineCount",200));
        reviewSubmit(new WorkflowTestReview.Candidate("核对跳过测试",List.of(new WorkflowTestReview.Mapping("sum","COVERED","有断言但实际跳过",List.of(actual.id()),List.of(new SourceDesign.Reference(file.path(),file.sha256(),1,page.endLine(),page.content()))))));finish();
        assertThat(delivery().outcome()).isEqualTo("REVISE");assertThat(delivery().outputs().get("review").content().path("scenarios").get(0).path("status").asString()).isEqualTo("NOT_EXECUTED");
    }
    private void reviewNativeCase(String test)throws Exception {
        attempt=start("review");input("profile");input("design");input("test");read(1,200);
        var context=reviewContract.context(models.definition(models.require(attempt)),nodes.inputs(nodes.attempt(attempt)));
        var actual=context.cases().stream().filter(c->c.name().equals("sum")||c.name().equals("sum()")||c.name().equals("test_sum")).findFirst().orElseThrow();
        var file=context.code().files().stream().filter(f->f.path().equals(addedTest)).findFirst().orElseThrow();
        var page=(WorkflowSourceReads.SourceText)call(WorkflowModelProfile.FILE,Map.of("name","code","path",file.path(),"sha256",file.sha256(),"startLine",1,"lineCount",200));
        reviewSubmit(new WorkflowTestReview.Candidate("本框架的真实测试和固定断言一致",List.of(new WorkflowTestReview.Mapping("sum","COVERED","期望求和结果为 3",List.of(actual.id()),List.of(new SourceDesign.Reference(file.path(),file.sha256(),1,page.endLine(),page.content()))))));finish();
        assertThat(nodes.attempt(attempt).state()).isEqualTo("SUCCEEDED");assertThat(delivery().outputs().get("review").content().path("testAttempt").asString()).isEqualTo(test);
    }
    private Map<String,Object> reviewSubmission(WorkflowTestReview.Candidate candidate) {
        var result=new WorkflowDelivery("场景覆盖复核","PASS",Map.of("review",new WorkflowDelivery.Value(DataKind.DECISION,encoding.decode(encoding.encode(candidate),tools.jackson.databind.JsonNode.class)),"summary",new WorkflowDelivery.Value(DataKind.TEXT,encoding.decode("\"独立核对固定证据\"",tools.jackson.databind.JsonNode.class))));
        return Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",result);
    }
    private Object reviewSubmit(WorkflowTestReview.Candidate candidate){return call(WorkflowModelProfile.SUBMIT,reviewSubmission(candidate));}
    private void done(String test)throws Exception {await(()->{commands.advance(test);var run=commandStore.require(test);assertThat(run.suspended()).as(run.lastErrorCode()).isFalse();return WorkflowCommandState.valueOf(run.state()).terminal();});}
    private static void await(java.util.function.BooleanSupplier condition)throws Exception {long until=System.nanoTime()+java.time.Duration.ofSeconds(110).toNanos();while(!condition.getAsBoolean()){if(System.nanoTime()>until)throw new AssertionError("native test timeout");Thread.sleep(40);}}
    boolean withReview,allowReviewFailure,finalBatches,unseededBatch,alternativeSeed,secondModule;
    private void create(boolean allowFailure) {
        var capture=preset("source.snapshot","source",List.of(new Input("path",InputSource.REQUIREMENT,"path",null,DataKind.TEXT,true)));
        capture=new Node(capture.id(),capture.title(),capture.kind(),capture.moduleId(),1,null,capture.task(),capture.inputs(),capture.outputs(),capture.outcomes(),capture.completion(),0,false,Map.of("sourcePurpose","UNIT_TEST"));
        var input=new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true);
        var profileNode=preset("source.test-profile","profile",List.of(input));
        var design=preset("source.test-design","design",List.of(input,new Input("profile",InputSource.NODE,"profile","profile",DataKind.JSON,true)));
        if(secondModule)design=target(design,sourceFile);
        var writerInputs=List.of(input,new Input("profile",InputSource.NODE,"profile","profile",DataKind.JSON,true),new Input("design",InputSource.NODE,"design","design",DataKind.JSON,true));
        var writer=preset("source.test-write","write",writerInputs);var seeded=new ArrayList<>(writerInputs);seeded.add(new Input("previous",InputSource.NODE,"write","code",DataKind.CODE,true));
        var testInputs=new ArrayList<>(writerInputs);testInputs.add(new Input("code",InputSource.NODE,"write","code",DataKind.CODE,true));
        var test=preset("source.test-run","test",testInputs);
        test=new Node(test.id(),test.title(),test.kind(),test.moduleId(),1,null,test.task(),test.inputs(),test.outputs(),test.outcomes(),new Completion(allowFailure?CompletionKind.DELIVERABLES:CompletionKind.VERIFIED,"执行实际单测",null),0,false,Map.of("testModuleRoot",moduleRoot,"testTimeoutSeconds","90"));
        var graph=new WorkflowGraph(1,List.of(capture,profileNode,design,writer,test),List.of(new Edge("sp","source","profile",null),new Edge("pd","profile","design",null),new Edge("dw","design","write",null),new Edge("wt","write","test",null)),List.of(new PublicInput("path","源码路径",DataKind.TEXT,true)));
        if(withReview) {
            var reviewInputs=new ArrayList<>(testInputs);reviewInputs.add(new Input("test",InputSource.NODE,"test","report",DataKind.JSON,true));
            var review=preset("source.test-review","review",reviewInputs);if(allowReviewFailure)review=new Node(review.id(),review.title(),review.kind(),review.moduleId(),1,review.roleId(),review.task(),review.inputs(),review.outputs(),review.outcomes(),new Completion(CompletionKind.DELIVERABLES,"保留复核意见后继续",null),0,false,review.parameters(),review.roleRevisionId());var graphNodes=new ArrayList<>(graph.nodes());graphNodes.add(review);
            var edges=new ArrayList<>(graph.edges());edges.add(new Edge("tr","test","review",null));graph=new WorkflowGraph(1,graphNodes,edges,graph.inputs());
        }
        if(finalBatches)graph=finalBatchGraph(graph);
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"单测场景流程","",graph,CanvasLayout.empty()));var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"单测场景","补齐单测",template.id(),1));id=owner.id();plans.confirm(id,new WorkflowRequests.VersionCommand(key(),owner.version()));
        source=start("source");sources.advance(source);profile=start("profile");profiles.advance(profile);assertThat(nodes.attempt(profile).state()).isEqualTo("SUCCEEDED");
        attempt=start("design");input();read(1,200);submit(candidate());finish();this.design=attempt;
    }
    private WorkflowGraph finalBatchGraph(WorkflowGraph graph) {
        var result=new ArrayList<Node>();for(var n:graph.nodes())if(!Set.of("test","review").contains(n.id()))result.add(n);
        var base=graph.nodes().stream().filter(n->n.id().equals("test")).findFirst().orElseThrow();
        result.add(new Node("old-test",base.title(),base.kind(),base.moduleId(),1,null,base.task(),base.inputs(),base.outputs(),base.outcomes(),base.completion(),0,false,base.parameters()));
        var designNode=graph.nodes().stream().filter(n->n.id().equals("design")).findFirst().orElseThrow();var secondDesign=preset("source.test-design","design2",designNode.inputs());result.add(secondModule?target(secondDesign,"two/src/main/java/Calculator.java"):secondDesign);
        var writer=new ArrayList<>(graph.nodes().stream().filter(n->n.id().equals("write")).findFirst().orElseThrow().inputs());
        writer.set(2,new Input("design",InputSource.NODE,"design2","design",DataKind.JSON,true));if(!unseededBatch)writer.add(new Input("previous",InputSource.NODE,"write","code",DataKind.CODE,true));
        if(alternativeSeed)result.add(preset("source.test-write","other-write",writer.subList(0,3)));
        if(alternativeSeed)writer.add(new Input("selected",InputSource.NODE,"other-write","code",DataKind.CODE,true));
        var secondWriter=preset("source.test-write","write2",writer);
        if(alternativeSeed)secondWriter=new Node(secondWriter.id(),secondWriter.title(),secondWriter.kind(),secondWriter.moduleId(),1,secondWriter.roleId(),secondWriter.task(),secondWriter.inputs(),secondWriter.outputs(),secondWriter.outcomes(),secondWriter.completion(),0,false,Map.of("workspaceInput","selected"),secondWriter.roleRevisionId());
        result.add(secondWriter);
        var testInputs=new ArrayList<>(base.inputs());testInputs.set(3,new Input("code",InputSource.NODE,"write2","code",DataKind.CODE,true));if(!secondModule)testInputs.add(new Input("design_second",InputSource.NODE,"design2","design",DataKind.JSON,true));
        result.add(new Node("test",base.title(),base.kind(),base.moduleId(),2,null,base.task(),testInputs,base.outputs(),base.outcomes(),base.completion(),0,false,base.parameters()));
        var edges=new ArrayList<Edge>();String previous=null;
        var sequence=new ArrayList<>(List.of("source","profile","design","write","old-test","design2","write2","test"));if(alternativeSeed)sequence.add(sequence.indexOf("write2"),"other-write");
        for(String key:sequence){if(previous!=null)edges.add(new Edge(previous+key,previous,key,null));previous=key;}
        if(secondModule) {
            var otherInputs=new ArrayList<>(testInputs);otherInputs.set(2,new Input("design",InputSource.NODE,"design2","design",DataKind.JSON,true));
            result.add(new Node("test2",base.title(),base.kind(),base.moduleId(),2,null,base.task(),otherInputs,base.outputs(),base.outcomes(),base.completion(),0,false,Map.of("testModuleRoot","two","testTimeoutSeconds","90")));
            edges.add(new Edge("w2t2","write2","test2",null));
        }
        if(withReview)for(String key:List.of("review","review2")) {
            var reviewInputs=new ArrayList<>(testInputs.subList(0,4));if(key.equals("review2"))reviewInputs.set(2,new Input("design",InputSource.NODE,"design2","design",DataKind.JSON,true));
            reviewInputs.add(new Input("test",InputSource.NODE,"test","report",DataKind.JSON,true));var review=version(preset("source.test-review",key,reviewInputs),2);
            result.add(new Node(review.id(),review.title(),review.kind(),review.moduleId(),2,review.roleId(),review.task(),review.inputs(),review.outputs(),review.outcomes(),new Completion(CompletionKind.DELIVERABLES,"保留意见",null),0,false,review.parameters(),review.roleRevisionId()));
            edges.add(new Edge("test"+key,"test",key,null));
        }
        return new WorkflowGraph(1,result,edges,graph.inputs());
    }
    private Node target(Node n,String path){return new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),n.inputs(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),Map.of("targetPaths",encoding.encode(List.of(path))),n.roleRevisionId());}
    private Node preset(String preset,String id,List<Input> inputs){var n=presets.get(preset,1).node();return new Node(id,n.title(),n.kind(),n.moduleId(),1,n.roleId(),n.task(),inputs,n.outputs(),n.outcomes(),n.completion(),0,n.pauseAfter(),n.parameters(),n.roleRevisionId());}
    private void requestStart(String node){controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,node,Map.of("path",new WorkflowDelivery.Value(DataKind.TEXT,encoding.decode(encoding.encode(sourceFolder),tools.jackson.databind.JsonNode.class))),MODEL,controls.get(id).checkpoints().stream().map(c->c.attemptId()).toList()));}
    private String admit(String node){requestStart(node);dispatch.advance(id);String run=nodes.node(id,plans.require(id).headRevision(),node).latestAttemptId();assertThat(run).as(controls.get(id).reasonCode()).isNotNull();return run;}
    private String start(String node){String run=admit(node);if(WorkflowModelProfile.ADAPTER.equals(nodes.attempt(run).adapterKey())||WorkflowWriterLeases.ADAPTER.equals(nodes.attempt(run).adapterKey()))running(run);return run;}
    private void running(String run){for(int i=0;i<4&&!models.require(run).state().equals("RUNNING");i++)execution.advance(run);assertThat(models.require(run).state()).isEqualTo("RUNNING");}
    private WorkflowSourceReads.SourceText read(int start,int limit){return (WorkflowSourceReads.SourceText)call(WorkflowModelProfile.FILE,Map.of("name","source","path",sourceFile,"sha256",hash(),"startLine",start,"lineCount",limit));}
    private void input(){input("profile");}
    private void input(String name){int offset=0;while(true){var page=(Map<?,?>)call(WorkflowModelProfile.INPUT,Map.of("name",name,"offset",offset));if(page.get("nextOffset")==null)return;offset=((Number)page.get("nextOffset")).intValue();}}
    private WorkflowTestDesign.Candidate candidate(){return new WorkflowTestDesign.Candidate("加法场景","验证两个整数求和",List.of(new WorkflowTestDesign.Scenario("sum",sourceFile,"NORMAL","两个正数求和",List.of("调用 total(1, 2)"),"返回 3",List.of(new SourceDesign.Reference(sourceFile,hash(),1,1,sourceText.strip())))),List.of());}
    private WorkflowDelivery result(Object value){return new WorkflowDelivery("已设计场景，测试尚未执行",null,Map.of("design",new WorkflowDelivery.Value(DataKind.JSON,encoding.decode(encoding.encode(value),tools.jackson.databind.JsonNode.class)),"summary",new WorkflowDelivery.Value(DataKind.TEXT,encoding.decode("\"场景设计\"",tools.jackson.databind.JsonNode.class))));}
    private Map<String,Object> submission(WorkflowTestDesign.Candidate value){return Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",result(value));}
    private Object submit(WorkflowTestDesign.Candidate value){return call(WorkflowModelProfile.SUBMIT,submission(value));}
    private Object call(String tool,Map<String,Object> args){return tools.call(tool,Map.of("scope",identity.grant(models.require(attempt)),"attemptId",attempt,"args",args));}
    private void finish(){fake.setSessionState(nodes.attempt(attempt).externalSessionId(),"COMPLETED");execution.advance(attempt);}
    private WorkflowDelivery delivery(){return encoding.decode(nodes.delivery(attempt).contentJson(),WorkflowDelivery.class);}
    private static WorkflowDelivery.Inputs copy(WorkflowDelivery.Inputs value,List<WorkflowDelivery.Input> inputs){return new WorkflowDelivery.Inputs(1,value.requirementId(),value.planRevision(),value.nodeId(),value.objective(),inputs);}
    private String hash(){return SourceTreeCapture.hash(sourceText.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private void writerRead(){input("profile");input("design");read(1,200);}
    private WorkflowDelivery writerResult(){return new WorkflowDelivery("补齐测试文件，实际测试待后续验证",null,Map.of("summary",new WorkflowDelivery.Value(DataKind.TEXT,encoding.decode("\"补齐求和场景\"",tools.jackson.databind.JsonNode.class))));}
    private Object writerSubmit(){return call(WorkflowModelProfile.SUBMIT,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",writerResult()));}
    private WorkflowCodeSnapshot.Reference reference(){return encoding.decode(encoding.encode(delivery().outputs().get("code").content()),WorkflowCodeSnapshot.Reference.class);}
    private void writeTest(boolean failure)throws Exception {
        String expected=failure?"4":"3";
        String body=switch(framework) {
            case "vitest"->"import { test, expect } from 'vitest'; import { total } from '../src/calculator.js'; test('sum', () => { expect(total(1, 2)).toBe("+expected+"); });\n";
            case "jest"->"const { total } = require('../src/calculator.js'); test('sum', () => { expect(total(1, 2)).toBe("+expected+"); });\n";
            case "pytest"->"from src.calculator import total\ndef test_sum(): assert total(1, 2) == "+expected+"\n";
            default->"import org.junit.jupiter.api.Test; class AddedTest { @Test void sum() { org.junit.jupiter.api.Assertions.assertEquals("+expected+", Calculator.total(1, 2)); } }";
        };
        Files.createDirectories(root.resolve(addedTest).getParent());Files.writeString(root.resolve(addedTest),body);
    }
    private void configureFramework(String framework)throws Exception {
        this.framework=framework;Files.delete(root.resolve("pom.xml"));
        Files.writeString(root.resolve(".gitignore"),"node_modules/\ncoverage/\n.gradle/\nbuild/\n");
        if(framework.equals("gradle")) {
            Files.writeString(root.resolve("settings.gradle"),"rootProject.name = 'native-fixture'\n");
            Files.writeString(root.resolve("build.gradle"),"plugins { id 'java' }\nrepositories { mavenCentral() }\ndependencies { testImplementation 'org.junit.jupiter:junit-jupiter:5.8.2'; testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.8.2' }\ntest { useJUnitPlatform() }\n");
            String gradle=System.getenv("LOOPPER_NATIVE_GRADLE");assertThat(gradle).as("Set LOOPPER_NATIVE_GRADLE for the opt-in native qualification").isNotBlank();
            command(List.of(gradle,"--no-daemon","--console=plain","wrapper","--gradle-version","9.5.1","--distribution-type","bin"));return;
        }
        // This is a temporary project owned solely by this test; remove the original Java fixture files.
        Files.delete(root.resolve("src/main/java/Calculator.java"));Files.delete(root.resolve("src/test/java/ExistingTest.java"));
        sourceFolder="src";sourceFile=framework.equals("pytest")?"src/calculator.py":"src/calculator.js";
        sourceText=framework.equals("pytest")?"def total(a, b): return a + b\n":framework.equals("jest")?"exports.total = (a, b) => a + b;\n":"export const total = (a, b) => a + b;\n";
        Files.writeString(root.resolve(sourceFile),sourceText);Files.createDirectories(root.resolve("tests"));
        if(framework.equals("pytest")) {
            addedTest="tests/test_added.py";Files.writeString(root.resolve("pytest.ini"),"[pytest]\ntestpaths = tests\n");
            Files.writeString(root.resolve("requirements-test.txt"),"pytest==9.0.2\n");Files.writeString(root.resolve("tests/test_existing.py"),"def test_existing(): assert 1 == 1\n");
        }else {
            addedTest="tests/added.test.js";String version=framework.equals("vitest")?"3.2.7":"30.2.0";
            Files.writeString(root.resolve("package.json"),encoding.encode(Map.of("name","native-fixture","version","1.0.0","type",framework.equals("vitest")?"module":"commonjs","scripts",Map.of("test",framework),"devDependencies",Map.of(framework,version))));
            Files.writeString(root.resolve("tests/existing.test.js"),(framework.equals("vitest")?"import { test, expect } from 'vitest'; ":"")+"test('existing', () => { expect(1).toBe(1); });\n");
            command(List.of("npm","install","--package-lock-only","--ignore-scripts","--no-audit","--no-fund"));
        }
    }
    private void command(List<String> argv) {
        var result=new SafeProcessRunner().run(root,argv,java.time.Duration.ofSeconds(120));
        assertThat(result.timedOut()).as(result.output()).isFalse();assertThat(result.exitCode()).as(result.output()).isZero();
    }
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-test-write-").toRealPath();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
