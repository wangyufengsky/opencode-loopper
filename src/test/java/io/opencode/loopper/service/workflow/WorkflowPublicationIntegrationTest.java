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
class WorkflowPublicationIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("publication.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
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
    @MockitoSpyBean GitFixedCommits commits;
    @TempDir Path directory;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    Path root;String project,requirement,attempt,sourceCommit;
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
    }
    @Test void explicitConfirmationCommitsOnlyTheReviewedVersionWithoutTouchingDirtyCheckout()throws Exception {
        finish(WorkflowState.COMPLETED);var request=request();
        assertThat(publications.get(requirement)).isNull();assertThat(command("for-each-ref","refs/heads/loopper/results/")).isEmpty();
        var confirmed=publications.confirm(requirement,request);assertThat(confirmed.state()).isEqualTo("CONFIRMED");
        assertThat(command("for-each-ref","refs/heads/loopper/results/")).isEmpty();
        Files.writeString(root.resolve("code.txt"),"unrelated dirty work");Files.writeString(root.resolve("user.txt"),"user");command("add","user.txt");String status=command("status","--porcelain"),index=command("write-tree");
        publishing.advance(requirement);var result=publications.get(requirement);assertThat(result.state()).isEqualTo("COMMITTED");
        assertThat(command("show",result.commit()+":code.txt")).isEqualTo("fixed result");assertThat(command("show",result.commit()+":added.txt")).isEqualTo("saved addition");
        assertThat(command("rev-parse",result.commit()+"^1").strip()).isEqualTo(sourceCommit);
        assertThat(command("log","-1","--format=%an <%ae>",result.commit()).strip()).isEqualTo("Loopper <loopper@localhost>");
        assertThat(command("rev-parse","HEAD").strip()).isEqualTo(sourceCommit);assertThat(command("symbolic-ref","--short","HEAD").strip()).isEqualTo("main");assertThat(command("status","--porcelain")).isEqualTo(status);assertThat(command("write-tree")).isEqualTo(index);
        assertThat(publications.confirm(requirement,request)).isEqualTo(result);publishing.advance(requirement);
        assertThat(command("for-each-ref","--format=%(objectname)","refs/heads/loopper/results/").lines()).containsExactly(result.commit());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM state_transition_event WHERE machine_type='WORKFLOW_PUBLICATION'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void acknowledgementLossResumesTheSameRefAndCommitAfterReloadingPersistedIntent() {
        finish(WorkflowState.COMPLETED);publications.confirm(requirement,request());var once=new AtomicBoolean(true);
        doAnswer(call->{call.callRealMethod();if(once.getAndSet(false))throw new IllegalStateException("lost acknowledgement");return null;}).when(commits).reference(any(),anyString(),anyString());
        publishing.advance(requirement);var blocked=publications.get(requirement);assertThat(blocked.state()).isEqualTo("BLOCKED");
        String actual=command("rev-parse","refs/heads/"+blocked.branch()).strip();
        publications.retry(requirement,blocked.version());publishing.advance(requirement);
        assertThat(publications.get(requirement).commit()).isEqualTo(actual);assertThat(publicationMapper.find(requirement).orElseThrow().intentSha256()).hasSize(64);
    }
    @Test void occupiedReferenceIsNeverMovedAndFailureRemainsRecoverable() {
        finish(WorkflowState.COMPLETED);var confirmed=publications.confirm(requirement,request());command("update-ref","refs/heads/"+confirmed.branch(),sourceCommit);
        publishing.advance(requirement);assertThat(publications.get(requirement).state()).isEqualTo("BLOCKED");
        assertThat(publications.get(requirement).reasonCode()).isEqualTo("WORKFLOW_PUBLICATION_BRANCH_UNCONFIRMED");
        assertThat(command("rev-parse","refs/heads/"+confirmed.branch()).strip()).isEqualTo(sourceCommit);
        assertThatThrownBy(()->publications.retry(requirement,0)).isInstanceOf(ConflictException.class);
    }
    @Test void offlineProjectKeepsTheIntentAndResumesOnlyWhenTheOriginalDirectoryReturns()throws Exception {
        finish(WorkflowState.COMPLETED);publications.confirm(requirement,request());Path offline=root.resolveSibling("offline");Files.move(root,offline);
        publishing.advance(requirement);var blocked=publications.get(requirement);assertThat(blocked.state()).isEqualTo("BLOCKED");
        Files.move(offline,root);publications.retry(requirement,blocked.version());publishing.advance(requirement);assertThat(publications.get(requirement).state()).isEqualTo("COMMITTED");
    }
    @Test void unfinishedOrFailedRequirementsAndStaleOrReboundConfirmationsCannotPublish() {
        assertThatThrownBy(()->publications.confirm(requirement,request())).isInstanceOf(ConflictException.class);
        var old=request();finish(WorkflowState.FAILED);assertThatThrownBy(()->publications.confirm(requirement,old)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->publications.confirm(requirement,request())).isInstanceOf(ConflictException.class);assertThat(publications.get(requirement)).isNull();
    }
    @Test void confirmedIntentIsImmutableAndCrossScopeReplayRejected() {
        var old=request();finish(WorkflowState.COMPLETED);assertThatThrownBy(()->publications.confirm(requirement,old)).isInstanceOf(ConflictException.class);
        var request=request();publications.confirm(requirement,request);
        assertThatThrownBy(()->publications.confirm("other",request)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->publications.confirm(requirement,new WorkflowPublication.Request(request.requestKey(),request.expectedVersion(),1,"work",attempt,"code",request.previewSha256(),"changed"))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_publication SET intent_json='{}' WHERE requirement_id=?",requirement)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_publication WHERE requirement_id=?",requirement)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_publication SET state='COMMITTED',version=version+1 WHERE requirement_id=?",requirement)).hasMessageContaining("receipt required");
    }
    @Test void missingSavedFileBlocksBeforeCreatingAnyPublicBranch()throws Exception {
        finish(WorkflowState.COMPLETED);var confirmed=publications.confirm(requirement,request());var manifest=publications.work(requirement).manifest();
        var file=manifest.files().getFirst();Files.writeString(DATA.resolve("workflow-code").resolve(reference.snapshotId()).resolve("objects").resolve(file.sha256()),"corrupt saved bytes");
        publishing.advance(requirement);assertThat(publications.get(requirement).state()).isEqualTo("BLOCKED");
        assertThat(command("for-each-ref","refs/heads/"+confirmed.branch())).isEmpty();assertThat(command("rev-parse","HEAD").strip()).isEqualTo(sourceCommit);
    }
    @Test void parallelRecoveryHasOneCommitAndAStoredSuccessCannotBeReplacedByLateFailure()throws Exception {
        finish(WorkflowState.COMPLETED);publications.confirm(requirement,request());var old=publicationMapper.find(requirement).orElseThrow();
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->publishing.advance(requirement));var b=pool.submit(()->publishing.advance(requirement));a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);}
        var status=publications.get(requirement);if(status.state().equals("BLOCKED")){publications.retry(requirement,status.version());publishing.advance(requirement);}
        assertThat(publications.get(requirement).state()).isEqualTo("COMMITTED");publications.blocked(old,"WORKFLOW_PUBLICATION_BRANCH_UNCONFIRMED");assertThat(publications.get(requirement).state()).isEqualTo("COMMITTED");
        assertThat(command("for-each-ref","--format=%(objectname)","refs/heads/loopper/results/").lines()).hasSize(1);
    }
    @Test void failedFinalDatabaseWriteDoesNotLoseTheAlreadyCreatedRef() {
        finish(WorkflowState.COMPLETED);publications.confirm(requirement,request());
        jdbc.execute("CREATE TRIGGER test_commit_failure BEFORE UPDATE ON workflow_publication WHEN NEW.state='COMMITTED' BEGIN SELECT RAISE(ABORT,'injected database failure'); END");
        publishing.advance(requirement);var blocked=publications.get(requirement);assertThat(blocked.state()).isEqualTo("BLOCKED");
        assertThat(blocked.reasonCode()).isEqualTo("WORKFLOW_PUBLICATION_RECEIPT_UNCONFIRMED");
        String commit=command("rev-parse","refs/heads/"+blocked.branch()).strip();jdbc.execute("DROP TRIGGER test_commit_failure");publications.retry(requirement,blocked.version());publishing.advance(requirement);assertThat(publications.get(requirement).commit()).isEqualTo(commit);
    }
    private void finish(WorkflowState state){finishes.request(requirement,new WorkflowFinishes.Request(key(),plans.require(requirement).version(),state,"人工检查后确认"));assertThat(plans.require(requirement).state()).isEqualTo(state.name());}
    private WorkflowPublication.Request request(){var p=reads.preview(requirement,1,"work",attempt,"code");return new WorkflowPublication.Request(key(),p.requirementVersion(),1,"work",attempt,"code",p.sha256(),"实现所选开发阶段");}
    private String command(String...args){var r=git.run(root,Duration.ofSeconds(10),List.of(args));r.requireSuccess(List.of(args));return r.output();}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-publication-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
