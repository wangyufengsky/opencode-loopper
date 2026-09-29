package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.monitor-delay=1h","loopper.workflow-monitor-enabled=false"})
class WorkflowDefaultDevelopmentIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("loopper.data-dir",()->DATA.toString());
        p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("default.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowBuiltinFlows builtins;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowPlanCandidates candidates;
    @Autowired WorkflowPlanRevisions revisions;
    @Autowired WorkflowControls controls;
    @Autowired WorkflowDispatchExecution dispatch;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowUploads uploads;
    @Autowired WorkflowModelExecution models;
    @Autowired WorkflowModelStore modelStore;
    @Autowired WorkflowModelTools tools;
    @Autowired WorkflowRuntimeSupport identity;
    @Autowired WorkflowCommandExecution commands;
    @Autowired WorkflowReviewGate gate;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired OpenCodeClient client;
    @Autowired ProjectService projects;
    @Autowired WorkflowEncoding encoding;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @TempDir Path directory;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    FakeOpenCodeClient fake;
    Path root;
    String project;
    @BeforeEach void prepare() throws Exception {
        flyway.clean();flyway.migrate();builtins.publish();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_WRITE,true);
        root=Files.createDirectory(directory.resolve("project"));git.read(root,"init","--quiet","--initial-branch=main");Files.writeString(root.resolve("code.txt"),"baseline");
        git.read(root,"add",".");git.read(root,"-c","user.name=Test","-c","user.email=test@example.invalid","commit","-qm","baseline");project=projects.create("默认开发试验",root.toString(),"").id();
    }
    @ParameterizedTest @CsvSource({"PASS,PASS,true,COMPLETED","BLOCKED,PASS,true,STALLED","PASS,BLOCKED,true,STALLED","PASS,PASS,false,STALLED"})
    void configuredDefaultRequiresReviewedPlanRealProgramEvidenceAndTwoIndependentOpinions(String requirement,String risk,boolean verification,String state) throws Exception {
        String id=toReviews(verification,false);String a=attempt(id,"requirementReview"),b=attempt(id,"riskReview");
        var work=read(a);assertThat(work.containsKey("reviewSubmission")).isTrue();assertThat(work.containsKey("planning")).isFalse();
        assertThat(nodes.attempt(a).externalSessionId()).isNotEqualTo(nodes.attempt(b).externalSessionId());
        var first=review(a,requirement);var receipt=submit(a,first);assertThat(submit(a,first)).isEqualTo(receipt);
        var accepted=encoding.decode(nodes.delivery(a).contentJson(),WorkflowDelivery.class).outputs().get("review").content();
        assertThat(accepted.path("basisSha256").asString()).hasSize(64);assertThat(accepted.path("perspective").asString()).isEqualTo("REQUIREMENT");
        finish(a);dispatch.advance(id);assertThat(attempt(id,"acceptance")).isNull();
        submit(b,review(b,risk));finish(b);dispatch.advance(id);
        assertThat(plans.require(id).state()).isEqualTo(state);String acceptance=attempt(id,"acceptance");assertThat(acceptance).isNotBlank();
        var report=encoding.decode(nodes.delivery(acceptance).contentJson(),WorkflowDelivery.class).outputs().get("report").content();
        assertThat(report.path("passed").asBoolean()).isEqualTo(state.equals("COMPLETED"));assertThat(report.path("verificationPassed").asBoolean()).isEqualTo(verification);
        assertThat(report.path("reviews")).hasSize(2);assertThat(nodes.hasStop(acceptance)).isTrue();
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("baseline");assertThat(git.read(root,"status","--porcelain")).isEmpty();
        for(String table:List.of("task","judge_run"))assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void invalidModelReviewCanBeCorrectedInTheSameAttemptAndCannotSetTheServerEvidenceIdentity() throws Exception {
        String id=toReviews(true,false),a=attempt(id,"requirementReview");
        var candidate=review(a,"PASS");var delivery=(WorkflowDelivery)candidate.get("delivery");
        var body=json.createObjectNode().put("version",1).put("verdict","PASS").put("reason","ok").put("basisSha256","forged");body.set("evidence",json.valueToTree(List.of("design","code","verification")));
        var bad=new HashMap<>(candidate);bad.put("delivery",new WorkflowDelivery(delivery.summary(),"PASS",Map.of("review",new WorkflowDelivery.Value(DataKind.DECISION,body))));
        assertThatThrownBy(()->submit(a,bad)).isInstanceOf(BadRequestException.class);assertThat(nodes.findDelivery(a)).isEmpty();
        body.remove("basisSha256");body.set("evidence",json.valueToTree(List.of("code")));
        assertThatThrownBy(()->submit(a,bad)).isInstanceOf(BadRequestException.class);assertThat(nodes.findDelivery(a)).isEmpty();
        submit(a,candidate);assertThat(nodes.findDelivery(a)).isPresent();assertThat(nodes.node(id,2,"requirementReview").attemptCount()).isEqualTo(1);
    }
    @Test void differentDesignEvidenceCannotBeCombinedEvenWhenBothOpinionsPass() throws Exception {
        String id=toReviews(true,true);for(String name:List.of("requirementReview","riskReview")){String a=attempt(id,name);submit(a,review(a,"PASS"));finish(a);}
        dispatch.advance(id);assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_REVIEW_EVIDENCE_MISMATCH");assertThat(attempt(id,"acceptance")).isNull();
        assertThat(plans.require(id).state()).isEqualTo("STALLED");
    }
    @Test void verificationOfAnotherCodeVersionCannotPassByAlsoBindingTheReviewedCodeAsAnUnusedInput() throws Exception {
        String id=plan(false,false,false,true);start(id);dispatch.advance(id);String writer=attempt(id,"develop");running(writer);
        Files.writeString(root.resolve("code.txt"),"delivered");submit(writer,output(writer,Map.of("result",text("实施"),"checks",text("待检查"))));finish(writer);
        dispatch.advance(id);String decoy=attempt(id,"decoy");running(decoy);Files.writeString(root.resolve("extra.txt"),"different version");
        submit(decoy,output(decoy,Map.of("result",text("另一版本"),"checks",text("待检查"))));finish(decoy);
        dispatch.advance(id);commandDone(attempt(id,"verify"));dispatch.advance(id);
        for(String name:List.of("requirementReview","riskReview")){String a=attempt(id,name);running(a);submit(a,review(a,"PASS"));finish(a);}
        dispatch.advance(id);assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_REVIEW_EVIDENCE_MISMATCH");assertThat(attempt(id,"acceptance")).isNull();
    }
    @Test void failedReceiptRollsBackThePureJoinAndTheOriginalCommandCanBeReplayed() throws Exception {
        String id=toReviews(true,false);for(String name:List.of("requirementReview","riskReview")){String a=attempt(id,name);submit(a,review(a,"PASS"));finish(a);}
        var ready=controls.prepare(id);assertThat(ready.nodeKey()).isEqualTo("acceptance");var request=new WorkflowNodeActions.Start(key(),ready.version(),null);
        jdbc.execute("CREATE TRIGGER fail_gate_ack BEFORE INSERT ON workflow_command WHEN NEW.action='REVIEW_GATE' BEGIN SELECT RAISE(ABORT,'gate receipt failure'); END");
        try{assertThatThrownBy(()->gate.dispatch(id,"acceptance",request,ready.permit())).hasStackTraceContaining("gate receipt failure");assertThat(attempt(id,"acceptance")).isNull();}
        finally{jdbc.execute("DROP TRIGGER fail_gate_ack");}
        var result=gate.dispatch(id,"acceptance",request,ready.permit());assertThat(gate.dispatch(id,"acceptance",request,ready.permit())).isEqualTo(result);
        assertThat(nodes.node(id,2,"acceptance").attemptCount()).isEqualTo(1);assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void userCanRemoveTheDefaultReviewsBeforeTheyRunWithoutHiddenAcceptance() throws Exception {
        String custom=plan(false,true,false);start(custom);dispatch.advance(custom);String writer=attempt(custom,"develop");running(writer);
        Files.writeString(root.resolve("code.txt"),"delivered");submit(writer,output(writer,Map.of("result",text("实现说明"),"checks",text("待程序检查"))));finish(writer);
        dispatch.advance(custom);commandDone(attempt(custom,"verify"));dispatch.advance(custom);
        assertThat(plans.require(custom).state()).isEqualTo("COMPLETED");assertThat(nodes.summaries(custom,2)).extracting(WorkflowExecutionRows.Summary::nodeKey).doesNotContain("requirementReview","riskReview","acceptance");
    }
    @Test void defaultFlowBindsTheSelectedOriginalDocumentsToQuestionsAndDesign() {
        var template=templates.get("builtin.workflow.development",null);
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"文档开发","按上传原文开发",template.id(),template.revision()));
        String id=owner.id();
        var original=uploads.upload(id,new WorkflowUploadStore.Request(key(),owner.version(),owner.revision()),List.of(
                new DocumentTemplateStorage.Incoming("需求.md","# 原文\n仅变更 code.txt，保留其他文件。".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        plans.confirm(id,new WorkflowRequests.VersionCommand(key(),plans.require(id).version()));
        var control=controls.get(id);
        controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),control.controlVersion(),WorkflowDispatch.Mode.CONTINUOUS,null,
                Map.of("documents",new WorkflowDelivery.Value(DataKind.DOCUMENT,json.valueToTree(original.reference()))),
                new OpenCodeClient.OpenCodeModel("fake","test",false),List.of()));
        dispatch.advance(id);String questions=attempt(id,"questions");running(questions);
        String source=readOriginal(questions);assertThat(source).contains("仅变更 code.txt");
        submit(questions,output(questions,Map.of("questions",text("确认原文中指定的内容"))));finish(questions);dispatch.advance(id);
        String human=attempt(id,"answers");actions.completeHuman(id,"answers",new WorkflowNodeActions.Complete(key(),plans.require(id).version(),human,
                nodes.attempt(human).version(),new WorkflowDelivery("确认",null,Map.of("result",text("code.txt 为 delivered")))));
        start(id);dispatch.advance(id);String design=attempt(id,"design");running(design);
        assertThat(readOriginal(design)).isEqualTo(source);
        assertThat(nodes.inputs(nodes.attempt(design)).values()).anySatisfy(value->{assertThat(value.name()).isEqualTo("documents");assertThat(value.content()).isEqualTo(json.valueToTree(original.reference()));});
    }
    private String readOriginal(String attempt) {
        var page=(WorkflowCodeFiles.Text)tools.call(WorkflowModelProfile.FILE,Map.of("scope",identity.grant(modelStore.require(attempt)),"attemptId",attempt,
                "args",Map.of("name","documents","path","parsed/01/0001.md","limit",12000)));
        return page.text();
    }
    @Test void modelReceivesCommandPurposeFeedbackBeforeCandidateAcceptanceAndRepairsInTheSameAttempt() throws Exception {
        String id=plan(false,false,false,false,true);
        assertThat(nodes.node(id,plans.require(id).headRevision(),"design").attemptCount()).isEqualTo(1);
        assertThat(candidates.list(id,"APPLIED",null,20).items()).hasSize(1);
    }
    private String toReviews(boolean verified,boolean mismatched) throws Exception {
        String id=plan(!verified,false,mismatched);start(id);dispatch.advance(id);String writer=attempt(id,"develop");running(writer);
        Files.writeString(root.resolve("code.txt"),"delivered");submit(writer,output(writer,Map.of("result",text("实现说明"),"checks",text("以程序检查为准"))));finish(writer);
        dispatch.advance(id);commandDone(attempt(id,"verify"));dispatch.advance(id);running(attempt(id,"requirementReview"));running(attempt(id,"riskReview"));return id;
    }
    private String plan(boolean verificationFailure,boolean removeReviews,boolean mismatched) throws Exception {
        return plan(verificationFailure,removeReviews,mismatched,false);
    }
    private String plan(boolean verificationFailure,boolean removeReviews,boolean mismatched,boolean wrongVerification) throws Exception {
        return plan(verificationFailure,removeReviews,mismatched,wrongVerification,false);
    }
    private String plan(boolean verificationFailure,boolean removeReviews,boolean mismatched,boolean wrongVerification,boolean repairCommand) throws Exception {
        var template=templates.get("builtin.workflow.development",null);assertThat(template.diagnostics()).isEmpty();
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"开发需求","交付 code.txt 内容为 delivered",template.id(),template.revision()));String id=owner.id();
        plans.confirm(id,new WorkflowRequests.VersionCommand(key(),owner.version()));start(id);dispatch.advance(id);String questions=attempt(id,"questions");running(questions);
        submit(questions,output(questions,Map.of("questions",text("请确认目标内容"))));finish(questions);dispatch.advance(id);String human=attempt(id,"answers");
        actions.completeHuman(id,"answers",new WorkflowNodeActions.Complete(key(),plans.require(id).version(),human,nodes.attempt(human).version(),new WorkflowDelivery("确认需求",null,Map.of("result",text("内容应为 delivered")))));
        start(id);dispatch.advance(id);String designer=attempt(id,"design");running(designer);var base=plans.get(id,null).graph();
        var argv=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",Path.of(CheckCode.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),CheckCode.class.getName(),verificationFailure?"fail":"pass");
        var graphNodes=new ArrayList<Node>();
        for(var n:base.nodes()) {
            if(removeReviews && Set.of("requirementReview","riskReview","acceptance").contains(n.id()))continue;
            var parameters=new HashMap<>(n.parameters());var completion=n.completion();var inputs=n.inputs();
            if(n.id().equals("verify")){parameters.put("commandVerification",encoding.encode(new WorkflowCommandVerification(1,"code",argv,20,"CHECK","checked fixed code")));if(verificationFailure)completion=new Completion(CompletionKind.DELIVERABLES,"保留失败验证供评审",null);}
            if(mismatched && n.id().equals("riskReview"))inputs=n.inputs().stream().map(i->i.name().equals("design")?new Input("design",InputSource.NODE,"answers","result",DataKind.TEXT,true):i).toList();
            graphNodes.add(new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),inputs,n.outputs(),n.outcomes(),completion,n.maxRetries(),n.pauseAfter(),parameters,n.roleRevisionId()));
        }
        var edges=new ArrayList<>(base.edges());
        if(wrongVerification) {
            var develop=graphNodes.stream().filter(n->n.id().equals("develop")).findFirst().orElseThrow();
            var previous=new ArrayList<>(develop.inputs());previous.add(new Input("source",InputSource.NODE,"develop","code",DataKind.CODE,true));
            graphNodes.add(new Node("decoy",develop.title(),develop.kind(),develop.moduleId(),develop.moduleVersion(),develop.roleId(),develop.task(),previous,develop.outputs(),develop.outcomes(),develop.completion(),0,false,develop.parameters(),develop.roleRevisionId()));
            graphNodes.replaceAll(n->n.id().equals("verify")?new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),List.of(new Input("code",InputSource.NODE,"decoy","code",DataKind.CODE,true),new Input("unused",InputSource.NODE,"develop","code",DataKind.CODE,true)),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),n.parameters(),n.roleRevisionId()):n);
            edges.removeIf(e->e.to().equals("verify"));edges.add(new Edge("decoy_after_develop","develop","decoy",null));edges.add(new Edge("verify_decoy","decoy","verify",null));
        }
        var keys=new HashSet<>(graphNodes.stream().map(Node::id).toList());var proposed=new WorkflowGraph(1,graphNodes,edges.stream().filter(e->keys.contains(e.from())&&keys.contains(e.to())).toList(),base.inputs());
        if(repairCommand) {
            var invalidNodes=proposed.nodes().stream().map(n->{
                if(!n.id().equals("verify"))return n;
                var parameters=new HashMap<>(n.parameters());parameters.put("commandVerification",encoding.encode(new WorkflowCommandVerification(1,"code",List.of("python3","verify.py"),20,"TEST",null)));
                return new Node(n.id(),n.title(),n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),n.inputs(),n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),parameters,n.roleRevisionId());
            }).toList();
            var invalid=new WorkflowGraph(1,invalidNodes,proposed.edges(),proposed.inputs());
            assertThat(encoding.diagnostics(invalid)).anySatisfy(issue->{assertThat(issue.code()).isEqualTo("WORKFLOW_COMMAND_INVALID");assertThat(issue.message()).contains("CHECK");});
            assertThatThrownBy(()->submit(designer,output(designer,Map.of("design",text("方案"),"plan",new WorkflowDelivery.Value(DataKind.PLAN,json.valueToTree(new WorkflowPlanCandidates.Proposal(1,1,invalid)))))))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("CHECK");
            assertThat(nodes.findDelivery(designer)).isEmpty();assertThat(candidates.list(id,"PENDING",null,20).items()).isEmpty();
            assertThatThrownBy(()->revisions.revise(id,new WorkflowRequests.RevisePlan(key(),plans.require(id).version(),1,invalid)))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("CHECK");
            assertThat(plans.require(id).headRevision()).isEqualTo(1);
        }
        submit(designer,output(designer,Map.of("design",text("将 code.txt 设置为 delivered，由实际命令验证"),"plan",new WorkflowDelivery.Value(DataKind.PLAN,json.valueToTree(new WorkflowPlanCandidates.Proposal(1,1,proposed))))));
        finish(designer);dispatch.advance(id);assertThat(attempt(id,"develop")).isNull();assertThat(plans.require(id).headRevision()).isEqualTo(1);
        assertThat(controls.get(id).reasonCode()).isEqualTo("WORKFLOW_PLAN_REVIEW_REQUIRED");var candidate=candidates.list(id,"PENDING",null,20).items().getFirst();
        candidates.apply(id,candidate.id(),new WorkflowPlanCandidates.Apply(key(),plans.require(id).version(),1,candidate.version(),null));dispatch.advance(id);assertThat(attempt(id,"develop")).isNull();return id;
    }
    public static final class CheckCode {public static void main(String[] args)throws Exception{if(!Files.readString(Path.of("code.txt")).equals("delivered"))System.exit(5);System.out.println("checked fixed code");if(args[0].equals("fail"))System.exit(3);}}
    private void start(String id){var c=controls.get(id);controls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),c.controlVersion(),WorkflowDispatch.Mode.CONTINUOUS,null,null,new OpenCodeClient.OpenCodeModel("fake","test",false),List.of()));}
    private String attempt(String id,String key){return nodes.node(id,plans.require(id).headRevision(),key).latestAttemptId();}
    private void running(String a){assertThat(a).isNotNull();for(int i=0;i<5&&!modelStore.require(a).state().equals("RUNNING");i++)models.advance(a);assertThat(modelStore.require(a).state()).isEqualTo("RUNNING");}
    private void finish(String a){fake.setSessionState(nodes.attempt(a).externalSessionId(),"COMPLETED");models.advance(a);}
    private Map<?,?> read(String a){return (Map<?,?>)tools.call(WorkflowModelProfile.WORK,Map.of("scope",identity.grant(modelStore.require(a)),"attemptId",a,"args",Map.of()));}
    private Object submit(String a,Map<String,Object> args){return tools.call(WorkflowModelProfile.SUBMIT,Map.of("scope",identity.grant(modelStore.require(a)),"attemptId",a,"args",args));}
    private Map<String,Object> output(String a,Map<String,WorkflowDelivery.Value> outputs){return Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(a).version(),"delivery",new WorkflowDelivery("节点交付",null,outputs));}
    private Map<String,Object> review(String a,String verdict){return Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(a).version(),"delivery",new WorkflowDelivery("独立意见",verdict,Map.of("review",new WorkflowDelivery.Value(DataKind.DECISION,json.valueToTree(Map.of("version",1,"verdict",verdict,"reason",verdict.equals("PASS")?"三项固定证据已核对":"仍有边界未覆盖","evidence",List.of("code","verification","design")))))));}
    private WorkflowDelivery.Value text(String value){return new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree(value));}
    private void commandDone(String a)throws Exception{assertThat(a).isNotNull();long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);while(!WorkflowAttemptState.valueOf(nodes.attempt(a).state()).terminal()&&System.nanoTime()<end){commands.advance(a);Thread.sleep(20);}assertThat(WorkflowAttemptState.valueOf(nodes.attempt(a).state()).terminal()).isTrue();}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-default-tests-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
