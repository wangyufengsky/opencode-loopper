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
class WorkflowHistoryAnalysisIntegrationTest {
    private static final Path DATA=data();
    private static final OpenCodeClient.OpenCodeModel MODEL=new OpenCodeClient.OpenCodeModel("fake","test",false);
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("history-analysis.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
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
    @TempDir Path directory;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    Path root;String project;FakeOpenCodeClient fake;
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
    @Test void professionalReviewAndContributionUseMcpAndWaitForIndependentStop()throws Exception {
        String id=create(),source=capture(id),review=start(id,"review");running(review);
        assertThat(((Map<?,?>)call(review,WorkflowModelProfile.WORK,Map.of())).containsKey("historyAnalysis")).isTrue();
        var input=read(review);assertThat(input.units()).hasSize(2);assertThat(input.units()).anyMatch(u->u.disposition().equals("SENSITIVE_CONTENT_WITHHELD"));
        var candidate=candidate(input);assertThatThrownBy(()->submit(review,new TemplateAnalysis.BatchCandidate(List.of()))).isInstanceOf(BadRequestException.class);
        Object receipt=submit(review,candidate);assertThat(nodes.attempt(review).state()).isEqualTo("RUNNING");assertThat(nodes.hasStop(review)).isFalse();
        assertThat(receipt).isInstanceOf(WorkflowModelTools.Accepted.class);finish(review);
        String contributor=start(id,"contribution");running(contributor);var person=read(contributor);
        assertThat(person.person().author().email()).isEqualTo("fixture@example.invalid");assertThat(person.reviews()).hasSize(2);
        var valid=new ContributionScore.Assessment(2,"本人固定提交包含实现",List.of(person.person().evidenceIds().iterator().next()));
        var foreign=new ContributionScore.Assessment(2,"他人提交",List.of("other-person"));
        assertThatThrownBy(()->submit(contributor,new TemplateAnalysis.ContributorCandidate(person.person().author().identity(),"评价",foreign,valid,valid,valid))).isInstanceOf(BadRequestException.class);
        submit(contributor,new TemplateAnalysis.ContributorCandidate(person.person().author().identity(),"本人贡献",valid,valid,valid,valid));finish(contributor);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(nodes.delivery(contributor).contentJson()).contains("HISTORY_CONTRIBUTION",review).doesNotContain("totalScore");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease",Integer.class)).isZero();assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        assertThat(nodes.attempt(source).state()).isEqualTo("SUCCEEDED");
    }
    @Test void partialPagesAndWrongLineCannotBecomeAcceptedEvidence()throws Exception {
        String id=create();capture(id);String review=start(id,"review");running(review);var input=inputs.require(review);
        call(review,WorkflowModelProfile.WORK,Map.of("analysis",true,"offset",0,"limit",1));
        assertThatThrownBy(()->submit(review,candidate(input))).isInstanceOf(BadRequestException.class).hasMessageContaining("完整读取");
        read(review);assertThatThrownBy(()->submit(review,Map.of("reviews","wrong-shape"))).isInstanceOf(BadRequestException.class).hasMessageContaining("格式无法解析");
        var values=new ArrayList<>(candidate(input).reviews());var first=values.getFirst();
        values.set(0,new TemplateAnalysis.UnitReview(first.unitId(),"问题",List.of(new TemplateAnalysis.Finding(TemplateAnalysis.Severity.HIGH,TemplateAnalysis.Side.AFTER,99999,"问题","证据","修复")),List.of()));
        assertThatThrownBy(()->submit(review,new TemplateAnalysis.BatchCandidate(values))).isInstanceOf(BadRequestException.class).hasMessageContaining("问题位置");
        assertThat(nodes.findDelivery(review)).isEmpty();assertThat(nodes.attempt(review).state()).isEqualTo("RUNNING");
        submit(review,candidate(input));finish(review);
    }
    @Test void preparationUsesAcceptedFilesAfterOriginalAndPrivateHistoryChange()throws Exception {
        String id=create(),source=capture(id);var reference=json.treeToValue(json.readTree(nodes.delivery(source).contentJson()).path("outputs").path("source").path("content"),WorkflowHistorySnapshot.Reference.class);
        Files.writeString(root.resolve("code.txt"),"changed checkout after capture\n");
        Files.move(jobs.repository(reference.snapshotId()).getParent(),jobs.repository(reference.snapshotId()).getParent().resolveSibling("offline-"+key()));
        String review=start(id,"review");running(review);var input=read(review);
        assertThat(input.units()).anyMatch(u->u.patch().contains("original code"));assertThat(input.units()).noneMatch(u->u.patch().contains("changed checkout"));
        submit(review,candidate(input));finish(review);
    }
    @Test void savedInputRecoversWithoutSourceFilesAndRemainsAttemptScoped()throws Exception {
        String id=create();String source=capture(id);String review=start(id,"review");var row=models.require(review);
        preparation.prepare(row);String frozen=inputMapper.find(review).orElseThrow().inputJson();
        assertThat(fake.createSessionCalls()).isZero();assertThat(fake.createReadOnlySessionCalls()).isZero();
        var reference=json.treeToValue(json.readTree(nodes.delivery(source).contentJson()).path("outputs").path("source").path("content"),WorkflowHistorySnapshot.Reference.class);
        Files.move(DATA.toRealPath().resolve("workflow-history-content").resolve(reference.snapshotId()),DATA.toRealPath().resolve("history-offline-"+key()));
        Files.move(jobs.repository(reference.snapshotId()),jobs.repository(reference.snapshotId()).resolveSibling("offline-history"));
        running(review);assertThat(inputMapper.find(review).orElseThrow().inputJson()).isEqualTo(frozen);submit(review,candidate(read(review)));finish(review);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_history_analysis_input SET input_json=input_json WHERE attempt_id=?",review)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_history_analysis_input WHERE attempt_id=?",review)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO workflow_history_analysis_read VALUES(?,?,0,1,1,'t')",review,inputMapper.find(review).orElseThrow().sha256())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->call(review,WorkflowModelProfile.WORK,Map.of("analysis",true))).isInstanceOf(RuntimeException.class);
    }
    @Test void acceptedDeliveryAndReceiptRollBackTogetherAndSameSessionCanCorrect()throws Exception {
        String id=create();capture(id);String review=start(id,"review");running(review);var input=read(review);
        jdbc.execute("CREATE TRIGGER fail_history_receipt BEFORE INSERT ON workflow_command WHEN NEW.action='MODEL_SUBMIT' BEGIN SELECT RAISE(ABORT,'receipt rollback'); END");
        assertThatThrownBy(()->submit(review,candidate(input))).isInstanceOf(RuntimeException.class).hasStackTraceContaining("receipt rollback");assertThat(nodes.findDelivery(review)).isEmpty();
        jdbc.execute("DROP TRIGGER fail_history_receipt");submit(review,candidate(input));assertThat(nodes.findDelivery(review)).isPresent();
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);finish(review);
    }
    @Test void cancellationRejectsPreparedInputPagesAndLateSubmission()throws Exception {
        String id=create();capture(id);String review=start(id,"review");running(review);var input=read(review);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消分析"));
        assertThatThrownBy(()->call(review,WorkflowModelProfile.WORK,Map.of("analysis",true))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->submit(review,candidate(input))).isInstanceOf(RuntimeException.class);
        drain.stop(id,review);execution.advance(review);assertThat(finishes.finalizeReady(id)).isTrue();assertThat(nodes.attempt(review).state()).isEqualTo("CANCELLED");
        assertThat(nodes.findDelivery(review)).isEmpty();assertThat(inputs.require(review)).isEqualTo(input);
    }
    private String create() {
        var source=node("git.history","source",List.of(new Input("branch",InputSource.REQUIREMENT,"branch",null,DataKind.TEXT,true),new Input("startDate",InputSource.REQUIREMENT,"startDate",null,DataKind.TEXT,true),new Input("endDate",InputSource.REQUIREMENT,"endDate",null,DataKind.TEXT,true)),Map.of());
        var review=node("history.review","review",List.of(new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true)),Map.of());
        var contributor=node("history.contribution","contribution",List.of(new Input("source",InputSource.NODE,"source","source",DataKind.DOCUMENT,true),new Input("review",InputSource.NODE,"review","analysis",DataKind.JSON,true)),Map.of("historyContributorEmail","fixture@example.invalid"));
        var graph=new WorkflowGraph(1,List.of(source,review,contributor),List.of(new Edge("source-review","source","review",null),new Edge("review-contribution","review","contribution",null),new Edge("source-contribution","source","contribution",null)),List.of(new PublicInput("branch","分支",DataKind.TEXT,true),new PublicInput("startDate","开始日期",DataKind.TEXT,true),new PublicInput("endDate","结束日期",DataKind.TEXT,true)));
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"历史分析","",graph,CanvasLayout.empty()));var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"审查历史","按固定资料审查",template.id(),1));plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private Node node(String preset,String id,List<Input> inputs,Map<String,String> override) {
        var n=presets.get(preset,1).node();var parameters=new LinkedHashMap<>(n.parameters());parameters.putAll(override);
        return new Node(id,n.title(),n.kind(),n.moduleId(),1,n.roleId(),n.task(),inputs,n.outputs(),n.outcomes(),n.completion(),0,false,parameters,n.roleRevisionId());
    }
    private String start(String id,String node) {
        controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),controls.get(id).controlVersion(),WorkflowDispatch.Mode.SINGLE,node,
            Map.of("branch",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("local:refs/heads/main")),"startDate",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("2026-09-11")),"endDate",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("2026-09-11"))),MODEL,List.of()));dispatch.advance(id);
        String run=nodes.node(id,plans.require(id).headRevision(),node).latestAttemptId();assertThat(run).as(controls.get(id).reasonCode()).isNotNull();return run;
    }
    private String capture(String id)throws Exception {
        String run=start(id,"source");long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<deadline&&!WorkflowAttemptState.valueOf(nodes.attempt(run).state()).terminal()&&!commandStore.require(run).suspended()){commands.advance(run);Thread.sleep(25);}
        assertThat(nodes.attempt(run).state()).as(commandStore.require(run).lastErrorCode()).isEqualTo("SUCCEEDED");return run;
    }
    private void running(String run){for(int i=0;i<4&&!models.require(run).state().equals("RUNNING");i++)execution.advance(run);assertThat(models.require(run).state()).isEqualTo("RUNNING");}
    private WorkflowHistoryAnalysis.Input read(String run) {
        int offset=0;StringBuilder text=new StringBuilder();do{var page=(WorkflowInputPages.Page)call(run,WorkflowModelProfile.WORK,Map.of("analysis",true,"offset",offset,"limit",50));text.append(page.text());if(page.nextOffset()==null)break;offset=page.nextOffset();}while(true);
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
    private static Path data(){try{return Files.createTempDirectory("workflow-history-analysis-tests-");}catch(Exception invalid){throw new ExceptionInInitializerError(invalid);}}
}
