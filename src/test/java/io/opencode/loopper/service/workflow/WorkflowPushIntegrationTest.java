package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.monitor-delay=1h"})
class WorkflowPushIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("push.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired Flyway flyway;
    @Autowired WorkflowExecutionMapper execution;
    @Autowired WorkflowWriterLeases writers;
    @Autowired WorkflowWorkspaces workspaces;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowFinishes finishes;
    @Autowired WorkflowPublicationReads reads;
    @Autowired WorkflowPublications publications;
    @Autowired WorkflowPublicationExecution publishing;
    @Autowired WorkflowPublicationMapper publicationMapper;
    @Autowired ProjectService projects;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired WorkflowPushes pushes;
    @Autowired WorkflowPushPreviews previews;
    @Autowired WorkflowPushExecution pushing;
    @MockitoSpyBean GitCredentialService credentials;
    @MockitoSpyBean DurableCommands commands;
    @TempDir Path directory;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    Path root,remote;String project,requirement,attempt,sourceCommit;
    WorkflowCodeSnapshot.Reference reference;
    @BeforeEach void setup()throws Exception {
        flyway.clean();flyway.migrate();root=Files.createDirectory(directory.resolve("project"));command("init","--quiet","--initial-branch=main");
        Files.writeString(root.resolve("code.txt"),"original");command("add","-A");command("-c","user.name=Fixture","-c","user.email=fixture@local.invalid","commit","--quiet","-m","baseline");sourceCommit=command("rev-parse","HEAD").strip();
        project=projects.create("成果提交",root.toString(),"").id();
        var node=new Node("work","开发阶段",NodeKind.WORK,"free.write",1,"builtin.implementation","实现目标",List.of(),List.of(new Output("code","代码成果",DataKind.CODE,true)),List.of(),new Completion(CompletionKind.DELIVERABLES,"有效交付",null),1,false,Map.of());
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"开发","",new WorkflowGraph(1,List.of(node),List.of(),List.of()),CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求","完成代码",template.id(),1));requirement=owner.id();plans.confirm(requirement,new WorkflowRequests.VersionCommand(key(),owner.version()));
        var identity=DirectWorkspaceLeaseCoordinator.identify(root);
        attempt=tx.execute(s->{var admitted=actions.admit(requirement,"work",new WorkflowNodeActions.Start(key(),plans.require(requirement).version(),null));var a=nodes.begin(admitted.node(),admitted.owner().headRevision(),admitted.inputs(),WorkflowWriterLeases.ADAPTER,"{}");writers.admit(identity,a.id());return a.id();});
        workspaces.prepare(attempt);
        tx.executeWithoutResult(s->{execution.attach(attempt,nodes.attempt(attempt).version(),"session-"+attempt,Instant.now().toString());nodes.transition(nodes.attempt(attempt),WorkflowAttemptState.RUNNING,LifecycleEvent.START);});
        Files.writeString(root.resolve("code.txt"),"fixed result");Files.writeString(root.resolve("added.txt"),"saved addition");
        tx.executeWithoutResult(s->execution.insertStop(new WorkflowExecutionRows.Stop(attempt,"ABORT_CONFIRMED","session-"+attempt,"{\"abort\":true}",Instant.now().toString())));
        workspaces.capture(attempt);reference=workspaces.codeDelivery(attempt);workspaces.restore(attempt);
        tx.executeWithoutResult(s->{var a=nodes.attempt(attempt);nodes.accept(a,new WorkflowDelivery("代码",null,Map.of("code",new WorkflowDelivery.Value(DataKind.CODE,json.valueToTree(reference)))));nodes.finish(a,WorkflowAttemptState.SUCCEEDED);});
        workspaces.release(attempt);
        finish(WorkflowState.COMPLETED);publications.confirm(requirement,request());publishing.advance(requirement);assertThat(publications.get(requirement).state()).isEqualTo("COMMITTED");
        remote=directory.resolve("remote.git");command("init","--bare","--quiet",remote.toString());command("remote","add","origin",remote.toString());
    }
    @Test void confirmedPushPreservesOnePinnedDestinationAndDirtyLocalFiles()throws Exception {
        var preview=previews.inspect(requirement,"origin").preview();assertThat(preview.remoteCommit()).isNull();assertThat(remote("for-each-ref")).isEmpty();
        var request=pushRequest(preview);var created=pushing.confirm(requirement,request);assertThat(created.state()).isEqualTo("PREPARING");assertThat(remote("for-each-ref")).isEmpty();
        Files.writeString(root.resolve("user.txt"),"user work");command("add","user.txt");String index=command("write-tree"),status=command("status","--porcelain");
        drive();var result=pushes.get(requirement);assertThat(result.state()).isEqualTo("PUSHED");
        assertThat(remote("rev-parse","refs/heads/"+result.branch()).strip()).isEqualTo(publications.get(requirement).commit());
        assertThat(remote("for-each-ref","--format=%(refname)").lines()).containsExactly("refs/heads/"+result.branch());
        assertThat(command("write-tree")).isEqualTo(index);assertThat(command("status","--porcelain")).isEqualTo(status);
        assertThat(command("rev-parse","HEAD").strip()).isEqualTo(sourceCommit);assertThat(pushing.confirm(requirement,request)).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_push_attempt",Integer.class)).isEqualTo(1);assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void lostDatabaseReceiptResumesTheOriginalSupervisorWithoutAnotherPush()throws Exception {
        confirm();jdbc.execute("CREATE TRIGGER test_push_failure BEFORE UPDATE ON workflow_push WHEN NEW.state='PUSHED' BEGIN SELECT RAISE(ABORT,'receipt failure'); END");drive();
        var blocked=pushes.get(requirement);assertThat(blocked.state()).isEqualTo("BLOCKED");var original=pushes.work(requirement);assertThat(original.attempt().resultJson()).isNull();
        assertThat(remote("rev-parse","refs/heads/"+blocked.branch()).strip()).isEqualTo(blocked.commit());jdbc.execute("DROP TRIGGER test_push_failure");
        pushing.retry(requirement,blocked.version());drive();assertThat(pushes.get(requirement).state()).isEqualTo("PUSHED");assertThat(pushes.work(requirement).attempt().id()).isEqualTo(original.attempt().id());
    }
    @Test void changedDestinationStopsAndExplicitRetryKeepsTheOriginalTarget()throws Exception {
        confirm();Path other=directory.resolve("other.git");command("init","--bare","-q",other.toString());command("remote","set-url","origin",other.toString());drive();
        var blocked=pushes.get(requirement);assertThat(blocked.state()).isEqualTo("BLOCKED");assertThat(blocked.reasonCode()).isEqualTo("WORKFLOW_PUSH_TARGET_CHANGED");
        var old=pushes.work(requirement);assertThat(old.attempt().resultJson()).contains("stopConfirmed");assertThat(remote("for-each-ref")).isEmpty();
        command("remote","set-url","origin",remote.toString());pushing.retry(requirement,blocked.version());drive();assertThat(pushes.get(requirement).state()).isEqualTo("PUSHED");assertThat(pushes.get(requirement).ordinal()).isEqualTo(2);
        assertThat(pushes.work(requirement).input().url()).isEqualTo(remote.toString());assertThat(new GitEvidenceProcess(new SafeProcessRunner()).read(other,"for-each-ref")).isEmpty();
    }
    @Test void uncertainStopNeverCreatesAReplacementSupervisor()throws Exception {
        confirm();long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(pushes.work(requirement).attempt().registrationJson()==null&&System.nanoTime()<deadline){pushing.advance(requirement);Thread.sleep(20);}
        assertThat(pushes.work(requirement).attempt().registrationJson()).isNotNull();var once=new AtomicBoolean(true);
        doAnswer(call->{var observed=(DurableCommands.Observation)call.callRealMethod();if(observed.registration()!=null&&observed.result()==null&&once.compareAndSet(true,false))return new DurableCommands.Observation(observed.registration(),null,observed.workerAlive(),true);return observed;}).when(commands).observe(any());
        drive();var blocked=pushes.get(requirement);assertThat(blocked.reasonCode()).isEqualTo("WORKFLOW_PUSH_STOP_UNCONFIRMED");String id=pushes.work(requirement).attempt().id();
        pushing.retry(requirement,blocked.version());drive();assertThat(pushes.get(requirement).state()).isEqualTo("PUSHED");assertThat(pushes.work(requirement).attempt().id()).isEqualTo(id);assertThat(pushes.get(requirement).ordinal()).isEqualTo(1);
    }
    @Test void persistedFailureCannotCreateAnotherAttemptWhileFreshStopEvidenceIsUnknown()throws Exception {
        confirm();command("remote","set-url","origin",directory.resolve("missing.git").toString());drive();var blocked=pushes.get(requirement);var original=pushes.work(requirement);
        assertThat(original.attempt().resultJson()).isNotNull();
        doAnswer(call->{var observed=(DurableCommands.Observation)call.callRealMethod();return new DurableCommands.Observation(observed.registration(),observed.result(),observed.workerAlive(),true);}).when(commands).observe(any());
        assertThatThrownBy(()->pushing.retry(requirement,blocked.version())).isInstanceOf(ConflictException.class);
        assertThat(pushes.work(requirement).attempt().id()).isEqualTo(original.attempt().id());assertThat(pushes.get(requirement).state()).isEqualTo("BLOCKED");
        doCallRealMethod().when(commands).observe(any());
    }
    @Test void supervisedRemoteReadsUseEphemeralProjectCredentialsWithoutPersistingThem()throws Exception {
        var local=publications.get(requirement);command("push",remote.toString(),local.commit()+":refs/heads/"+local.branch());remote("update-server-info");
        String token="push-fixture-secret",authorization="Basic "+Base64.getEncoder().encodeToString(("fixture:"+token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var authenticated=new java.util.concurrent.atomic.AtomicInteger();var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            if(!authorization.equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.getResponseHeaders().set("WWW-Authenticate","Basic realm=fixture");exchange.sendResponseHeaders(401,-1);return;}
            authenticated.incrementAndGet();Path file=remote.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
            if(file.startsWith(remote)&&Files.isRegularFile(file)){byte[] bytes=Files.readAllBytes(file);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}else exchange.sendResponseHeaders(404,-1);
        }finally{exchange.close();}});server.start();
        try {
            String host="http://127.0.0.1:"+server.getAddress().getPort();command("remote","set-url","origin",host+"/");
            var environment=GitHttpAuthentication.environment(host,"fixture",token,host+"/");
            doReturn(environment).when(credentials).environment(root.toRealPath(),host+"/");
            doReturn(new GitCredentialProvider.Scope(host,true,environment)).when(credentials).scope(root.toRealPath());
            String config=Files.readString(root.resolve(".git/config"));confirm();int previews=authenticated.get();drive();assertThat(pushes.get(requirement).state()).isEqualTo("PUSHED");assertThat(authenticated.get()).isGreaterThan(previews);
            var work=pushes.work(requirement);assertThat(work.push().inputJson()+work.attempt().requestJson()+work.attempt().registrationJson()+work.attempt().resultJson()).doesNotContain(token,authorization);
            assertThat(Files.readString(root.resolve(".git/config"))).isEqualTo(config).doesNotContain(token,authorization);
        } finally {server.stop(0);}
    }
    @Test void alreadyPublishedRefIsConfirmedAndHistoryCannotBeRebound()throws Exception {
        var local=publications.get(requirement);command("push",remote.toString(),local.commit()+":refs/heads/"+local.branch());var preview=previews.inspect(requirement,"origin").preview();assertThat(preview.remoteCommit()).isEqualTo(local.commit());
        var request=pushRequest(preview);pushing.confirm(requirement,request);drive();assertThat(pushes.get(requirement).state()).isEqualTo("PUSHED");
        assertThatThrownBy(()->pushing.confirm("other",request)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_push SET input_json='{}' WHERE requirement_id=?",requirement)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_push_attempt")).hasMessageContaining("immutable");
    }
    @Test void staleTargetPreviewCannotAuthorizeTheChangedRemote() {
        var preview=previews.inspect(requirement,"origin").preview();command("remote","set-url","origin",directory.resolve("missing.git").toString());
        assertThatThrownBy(()->pushing.confirm(requirement,pushRequest(preview))).isInstanceOf(RuntimeException.class);assertThat(pushes.get(requirement)).isNull();
    }
    @AfterEach void stopTestJob()throws Exception {
        if(pushes.get(requirement)==null)return;var work=pushes.work(requirement);if(work.attempt().requestSha256()==null)return;
        var job=new DurableCommands.Job(work.attempt().id(),work.attempt().requestSha256());commands.requestStop(job);
        long deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos();while(System.nanoTime()<deadline){var observed=commands.observe(job);if(observed.registration()==null||!observed.workerAlive()&&observed.result()!=null&&observed.result().stopConfirmed())return;Thread.sleep(20);}
        fail("test supervisor must stop before temporary directories disappear");
    }
    private WorkflowPush.Request pushRequest(WorkflowPush.Preview preview){return new WorkflowPush.Request(key(),preview.publicationVersion(),preview.remote(),preview.sha256());}
    private void confirm(){pushing.confirm(requirement,pushRequest(previews.inspect(requirement,"origin").preview()));}
    private void drive()throws Exception {long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(System.nanoTime()<deadline){pushing.advance(requirement);var state=pushes.get(requirement).state();if(state.equals("PUSHED")||state.equals("BLOCKED"))return;Thread.sleep(20);}fail("push must settle with durable proof");}
    private String remote(String...args){var result=new GitEvidenceProcess(new SafeProcessRunner()).run(remote,Duration.ofSeconds(10),List.of(args));result.requireSuccess(List.of(args));return result.output();}
    private void finish(WorkflowState state){finishes.request(requirement,new WorkflowFinishes.Request(key(),plans.require(requirement).version(),state,"人工检查后确认"));assertThat(plans.require(requirement).state()).isEqualTo(state.name());}
    private WorkflowPublication.Request request(){var p=reads.preview(requirement,1,"work",attempt,"code");return new WorkflowPublication.Request(key(),p.requirementVersion(),1,"work",attempt,"code",p.sha256(),"实现所选开发阶段");}
    private String command(String...args){var r=git.run(root,Duration.ofSeconds(10),List.of(args));r.requireSuccess(List.of(args));return r.output();}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-publication-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
