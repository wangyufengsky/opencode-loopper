package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import static org.mockito.Mockito.*;
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
class WorkflowCodeSnapshotsIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("loopper.data-dir",()->DATA.toString());
        p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("code.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowExecutionMapper execution;
    @Autowired WorkflowCodeMapper code;
    @Autowired WorkflowWriterLeases writers;
    @Autowired WorkflowCodeSnapshots snapshots;
    @Autowired WorkflowCodeStore store;
    @Autowired WorkflowCodeFiles files;
    @Autowired WorkflowPublicationReads publication;
    @Autowired WorkflowPlanRevisions revisions;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired ProjectService projects;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean GitCodeSnapshots capture;
    @TempDir Path directory;
    Path root;
    String project,requirement,attempt,baseTree,resultTree;
    DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    @BeforeEach void setup() throws Exception {
        flyway.clean(); flyway.migrate();
        root=Files.createDirectory(directory.resolve("project")); command("init","--quiet");
        Files.writeString(root.resolve("code.txt"),"before"); baseTree=tree();
        Files.writeString(root.resolve("code.txt"),"after"); resultTree=tree();
        project=projects.create("代码快照",root.toString(),"").id();
        identity=DirectWorkspaceLeaseCoordinator.identify(root);
        attempt=createAttempt();
        tx.executeWithoutResult(s->writers.admit(identity,attempt));
        tx.executeWithoutResult(s->{
            execution.attach(attempt,nodes.attempt(attempt).version(),"session-"+attempt,Instant.now().toString());
            nodes.transition(nodes.attempt(attempt),WorkflowAttemptState.RUNNING,LifecycleEvent.START);
        });
    }
    @Test void completedDeliveryReadsManagedBytesWithExactScopeAndNeverFallsBackToCurrentDirectory() throws Exception {
        stopProof(); var reference=freeze();
        assertThat(freeze()).isEqualTo(reference);
        assertThatThrownBy(()->snapshots.manifest(project,requirement,attempt,reference))
                .isInstanceOfSatisfying(ConflictException.class,error->assertThat(error.code()).isEqualTo("WORK_DELIVERY_NOT_READY"));
        complete(reference);
        var manifest=snapshots.manifest(project,requirement,attempt,reference);
        assertThat(manifest.inputsSha256()).isEqualTo(nodes.attempt(attempt).inputsSha256());
        Files.writeString(root.resolve("code.txt"),"current unrelated");
        assertThat(new String(snapshots.read(project,requirement,attempt,reference,"code.txt"),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("after");
        assertThatThrownBy(()->snapshots.read("wrong",requirement,attempt,reference,"code.txt")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->snapshots.read(project,"wrong",attempt,reference,"code.txt")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->snapshots.read(project,requirement,attempt,reference,"../code.txt")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->snapshots.manifest(project,requirement,attempt,new WorkflowCodeSnapshot.Reference(1,reference.snapshotId(),"0".repeat(64)))).isInstanceOf(ConflictException.class);
        Path object=DATA.resolve("workflow-code").resolve(reference.snapshotId()).resolve("objects").resolve(manifest.files().getFirst().sha256());
        Files.writeString(object,"changed managed object");
        assertThatThrownBy(()->snapshots.read(project,requirement,attempt,reference,"code.txt")).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("current unrelated");
    }
    @Test void pagedChangesIncludeDeletedAndModeOnlyFilesAndNeverReadCurrentTree() throws Exception {
        Files.writeString(root.resolve("code.txt"),"before");
        Files.writeString(root.resolve("removed.txt"),"remove me");
        Files.writeString(root.resolve("mode.sh"),"echo fixed");baseTree=tree();
        Files.writeString(root.resolve("code.txt"),"after");
        Files.delete(root.resolve("removed.txt"));Files.writeString(root.resolve("added.txt"),"new");tree();
        command("update-index","--chmod=+x","mode.sh");resultTree=command("write-tree").strip();
        stopProof();var reference=freeze();complete(reference);
        var binding=files.output(requirement,"work",attempt,"code");
        Files.writeString(root.resolve("later.txt"),"unrelated");Files.writeString(root.resolve("added.txt"),"changed again");tree();
        var first=files.changes(binding,null,2);var second=files.changes(binding,first.nextCursor(),2);
        assertThat(first.items()).extracting(WorkflowCodeSnapshot.Change::path).containsExactly("added.txt","code.txt");
        assertThat(first.items()).extracting(WorkflowCodeSnapshot.Change::kind).containsExactly("ADD","MODIFY");
        assertThat(second.items()).extracting(WorkflowCodeSnapshot.Change::path).containsExactly("mode.sh","removed.txt");
        assertThat(second.items()).extracting(WorkflowCodeSnapshot.Change::kind).containsExactly("MODIFY","DELETE");
        assertThat(second.items().getFirst().beforeBlob()).isEqualTo(second.items().getFirst().afterBlob());
        assertThat(second.items().getLast().afterBlob()).isNull();assertThat(second.nextCursor()).isNull();
        assertThat(new String(files.bytes(binding,"added.txt"),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("new");
        assertThatThrownBy(()->files.bytes(binding,"removed.txt")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->files.changes(binding,files.list(binding,null,1).nextCursor(),1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->files.list(binding,first.nextCursor(),1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->files.changes(binding,new PageCursor("different:changes","added.txt").encode(),1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->files.changes(binding,null,101)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->files.output("different","work",attempt,"code")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->files.output(requirement,"different",attempt,"code")).isInstanceOf(NotFoundException.class);
        var wrong=new WorkflowCodeFiles.Binding(project,"different",attempt,reference);
        assertThatThrownBy(()->files.changes(wrong,null,2)).isInstanceOf(NotFoundException.class);
        var document=new WorkflowCodeFiles.Binding(project,requirement,attempt,null,
                new WorkflowSourceSnapshot.Reference(1,reference.snapshotId(),reference.sha256()));
        assertThatThrownBy(()->files.changes(document,null,2)).isInstanceOfSatisfying(BadRequestException.class,e->assertThat(e.code()).isEqualTo("WORKFLOW_CODE_CHANGES_REQUIRED"));
    }
    @Test void failedStoppedOutputCanBeInspectedWithoutAuthorizingItAsAnInput() {
        stopProof();var reference=freeze();
        tx.executeWithoutResult(s->{var current=nodes.attempt(attempt);nodes.accept(current,delivery(reference));nodes.finish(current,WorkflowAttemptState.FAILED);});
        var output=files.output(requirement,"work",attempt,"code");
        assertThat(files.changes(output,null,50).items()).extracting(WorkflowCodeSnapshot.Change::path).containsExactly("code.txt");
        assertThatThrownBy(()->files.changes(new WorkflowCodeFiles.Binding(project,requirement,attempt,reference),null,50))
                .isInstanceOf(ConflictException.class);
    }
    @Test void copyInterruptionKeepsOneDurableIntentAndRecoveryUsesTheOriginalTree() throws Exception {
        stopProof(); var once=new AtomicBoolean(true);
        doAnswer(call->{Object result=call.callRealMethod(); if(once.getAndSet(false)) throw new IllegalStateException("simulated disk interruption"); return result;})
                .when(capture).capture(any(),anyString(),anyString(),anyString(),any());
        assertThatThrownBy(this::freeze).hasMessage("simulated disk interruption");
        var intent=code.forAttempt(attempt).orElseThrow();
        assertThat(code.manifest(intent.id())).isEmpty();
        Files.writeString(root.resolve("code.txt"),"new workspace"); tree();
        var reference=freeze(); assertThat(reference.snapshotId()).isEqualTo(intent.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_code_snapshot",Integer.class)).isEqualTo(1);
        complete(reference);
        assertThat(new String(snapshots.read(project,requirement,attempt,reference,"code.txt"),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("after");
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_code_snapshot SET result_tree=? WHERE id=?",baseTree,intent.id())).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_code_manifest WHERE snapshot_id=?",intent.id())).hasMessageContaining("retained");
    }
    @Test void noStopProofOrForgedModelReferenceCannotCreateCodeDelivery() {
        assertThatThrownBy(this::freeze).isInstanceOf(ConflictException.class);
        assertThat(code.forAttempt(attempt)).isEmpty();
        var forged=new WorkflowCodeSnapshot.Reference(1,"unknown","0".repeat(64));
        assertThatThrownBy(()->tx.executeWithoutResult(s->nodes.accept(nodes.attempt(attempt),delivery(forged)))).isInstanceOf(ConflictException.class);
        assertThat(nodes.findDelivery(attempt)).isEmpty();
        stopProof(); var reference=freeze();
        assertThatThrownBy(()->tx.executeWithoutResult(s->nodes.accept(nodes.attempt(attempt),
                delivery(new WorkflowCodeSnapshot.Reference(1,reference.snapshotId(),"f".repeat(64)))))).isInstanceOf(ConflictException.class);
        assertThat(nodes.findDelivery(attempt)).isEmpty();
        var wrongType=json.createObjectNode().put("version","1").put("snapshotId",reference.snapshotId()).put("sha256",reference.sha256());
        var malformed=new WorkflowDelivery("类型错误",null,Map.of("code",new WorkflowDelivery.Value(DataKind.CODE,wrongType)));
        assertThatThrownBy(()->tx.executeWithoutResult(s->nodes.accept(nodes.attempt(attempt),malformed))).isInstanceOf(ConflictException.class);
    }
    @Test void lateCaptureCannotPublishAfterItsAttemptEndsAndCannotRewriteFrozenIntent() {
        stopProof();
        doAnswer(call->{Object captured=call.callRealMethod();tx.executeWithoutResult(s->{
            var stopping=nodes.transition(nodes.attempt(attempt),WorkflowAttemptState.STOPPING,LifecycleEvent.CANCEL);
            nodes.finish(stopping,WorkflowAttemptState.CANCELLED);
        });return captured;})
                .when(capture).capture(any(),anyString(),anyString(),anyString(),any());
        assertThatThrownBy(this::freeze).isInstanceOf(ConflictException.class);
        var intent=code.forAttempt(attempt).orElseThrow(); assertThat(code.manifest(intent.id())).isEmpty();
        assertThat(nodes.attempt(attempt).state()).isEqualTo("CANCELLED");
        assertThatThrownBy(this::freeze).isInstanceOf(ConflictException.class);
    }
    @Test void changedAttemptVersionOrTreeCannotReplaceReservedIdentity() {
        stopProof(); var reference=freeze();
        assertThatThrownBy(()->snapshots.freeze(attempt,nodes.attempt(attempt).version()+1,baseTree,resultTree)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->snapshots.freeze(attempt,nodes.attempt(attempt).version(),baseTree,baseTree)).isInstanceOf(ConflictException.class);
        assertThat(code.forAttempt(attempt).orElseThrow().id()).isEqualTo(reference.snapshotId());
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void concurrentReplayPublishesOneSnapshotAndOneManifestWithoutChangingTheLease() throws Exception {
        stopProof(); var barrier=new CyclicBarrier(2);
        WorkflowCodeSnapshot.Reference first,second;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{barrier.await();return freeze();});
            var two=pool.submit(()->{barrier.await();return freeze();});
            first=one.get(20,TimeUnit.SECONDS); second=two.get(20,TimeUnit.SECONDS);
        }
        assertThat(first).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_code_snapshot",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_code_manifest",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT holder_workflow_attempt_id FROM workspace_lease WHERE canonical_root=?",String.class,identity.canonicalRoot())).isEqualTo(attempt);
        assertThatThrownBy(()->writers.requireWritable(identity,attempt)).isInstanceOf(ConflictException.class);
        assertThat(nodes.findDelivery(attempt)).isEmpty();
        complete(first);
        assertThat(snapshots.manifest(project,requirement,attempt,first).files()).hasSize(1);
    }
    @Test void publicationPreviewSelectsAcceptedFixedCodeWithoutReadingOrChangingTheCheckout()throws Exception {
        assertThat(publication.sources(requirement,1,null,20).items()).isEmpty();stopProof();var reference=freeze();
        assertThat(publication.sources(requirement,1,null,20).items()).isEmpty();complete(reference);
        var list=publication.sources(requirement,1,null,20);assertThat(list.items()).hasSize(1);assertThat(list.nextCursor()).isNull();
        var source=list.items().getFirst();assertThat(source.nodeKey()).isEqualTo("work");assertThat(source.outputName()).isEqualTo("code");assertThat(source.outputTitle()).isEqualTo("代码");assertThat(source.changedFiles()).isEqualTo(1);
        var preview=publication.preview(requirement,1,"work",attempt,"code");assertThat(preview.modified()).isEqualTo(1);assertThat(preview.reference()).isEqualTo(reference);assertThat(preview.totalBytes()).isEqualTo(5);assertThat(preview.workspaceKind()).isEqualTo("GIT");
        var before=jdbc.queryForList("SELECT * FROM workspace_lease");long version=plans.require(requirement).version();
        Files.writeString(root.resolve("unrelated.txt"),"user changes");String index=command("ls-files","--stage"),status=command("status","--porcelain=v1");
        assertThat(publication.preview(requirement,1,"work",attempt,"code")).isEqualTo(preview);
        assertThat(command("ls-files","--stage")).isEqualTo(index);assertThat(command("status","--porcelain=v1")).isEqualTo(status);
        Files.move(root,root.resolveSibling("offline-project"));assertThat(publication.preview(requirement,1,"work",attempt,"code")).isEqualTo(preview);
        assertThat(jdbc.queryForList("SELECT * FROM workspace_lease")).isEqualTo(before);assertThat(plans.require(requirement).version()).isEqualTo(version);
    }
    @Test void publicationPreviewRejectsForeignOwnersStalePlansAndUnselectedOutputs(){
        stopProof();var reference=freeze();complete(reference);
        assertThatThrownBy(()->publication.preview(requirement,2,"work",attempt,"code")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->publication.preview("other",1,"work",attempt,"code")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->publication.preview(requirement,1,"other",attempt,"code")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->publication.preview(requirement,1,"work",attempt,"other")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->publication.preview(requirement,1,"work","other","code")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->publication.sources(requirement,1,new io.opencode.loopper.service.PageCursor("other:1:time","attempt:code").encode(),20)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->publication.sources(requirement,1,null,101)).isInstanceOf(BadRequestException.class);
    }
    @Test void publicationSourcesPageByStableTimeAndIdentityAndExcludeRemovedPlanNodes()throws Exception {
        stopProof();complete(freeze());String first=attempt;
        long leaseVersion=jdbc.queryForObject("SELECT version FROM workspace_lease WHERE canonical_root=?",Long.class,identity.canonicalRoot());
        tx.executeWithoutResult(s->writers.releaseAfterStopped(identity,first,leaseVersion));
        var original=plans.get(requirement,null).graph().nodes().getFirst();
        var next=new Node("next","第二开发节点",original.kind(),original.moduleId(),original.moduleVersion(),original.roleId(),original.task(),original.inputs(),original.outputs(),original.outcomes(),original.completion(),original.maxRetries(),original.pauseAfter(),original.parameters());
        revisions.revise(requirement,new WorkflowRequests.RevisePlan(key(),plans.require(requirement).version(),1,new WorkflowGraph(1,List.of(original,next),List.of(),List.of())));
        attempt=tx.execute(s->{var start=actions.admit(requirement,"next",new WorkflowNodeActions.Start(key(),plans.require(requirement).version(),null));return nodes.begin(start.node(),start.owner().headRevision(),start.inputs(),WorkflowWriterLeases.ADAPTER,"{}").id();});
        tx.executeWithoutResult(s->{writers.admit(identity,attempt);execution.attach(attempt,nodes.attempt(attempt).version(),"session-"+attempt,Instant.now().toString());nodes.transition(nodes.attempt(attempt),WorkflowAttemptState.RUNNING,LifecycleEvent.START);});
        Files.writeString(root.resolve("code.txt"),"third version");resultTree=tree();stopProof();complete(freeze());
        var page=publication.sources(requirement,2,null,1);assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().nodeKey()).isEqualTo("next");assertThat(page.nextCursor()).isNotNull();
        var last=publication.sources(requirement,2,page.nextCursor(),1);assertThat(last.items()).hasSize(1);assertThat(last.items().getFirst().attemptId()).isEqualTo(first);assertThat(last.nextCursor()).isNull();
        revisions.revise(requirement,new WorkflowRequests.RevisePlan(key(),plans.require(requirement).version(),2,new WorkflowGraph(1,List.of(next),List.of(),List.of())));
        assertThat(publication.sources(requirement,3,null,20).items()).extracting(io.opencode.loopper.workflow.WorkflowPublicationPreview.Source::nodeKey).containsExactly("next");
        assertThatThrownBy(()->publication.preview(requirement,3,"work",first,"code")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->publication.sources(requirement,3,page.nextCursor(),1)).isInstanceOf(ConflictException.class);
    }
    @Test void failedAcceptedCodeIsListedWithItsActualOutcomeWithoutAuthorizingDownstreamUse(){
        stopProof();var reference=freeze();tx.executeWithoutResult(s->{var current=nodes.attempt(attempt);nodes.accept(current,delivery(reference));nodes.finish(current,WorkflowAttemptState.FAILED);});
        assertThat(publication.sources(requirement,1,null,20).items().getFirst().attemptState()).isEqualTo("FAILED");
        assertThat(publication.preview(requirement,1,"work",attempt,"code").source().attemptState()).isEqualTo("FAILED");
        assertThat(nodes.attempt(attempt).state()).isEqualTo("FAILED");assertThatThrownBy(()->snapshots.manifest(project,requirement,attempt,reference)).isInstanceOf(ConflictException.class);
    }
    private WorkflowCodeSnapshot.Reference freeze() { return snapshots.freeze(attempt,nodes.attempt(attempt).version(),baseTree,resultTree); }
    private WorkflowDelivery delivery(WorkflowCodeSnapshot.Reference reference) {
        return new WorkflowDelivery("代码交付",null,Map.of("code",new WorkflowDelivery.Value(DataKind.CODE,json.valueToTree(reference))));
    }
    private void complete(WorkflowCodeSnapshot.Reference reference) {
        tx.executeWithoutResult(s->{var current=nodes.attempt(attempt);nodes.accept(current,delivery(reference));nodes.finish(current,WorkflowAttemptState.SUCCEEDED);});
    }
    private void stopProof() { tx.executeWithoutResult(s->execution.insertStop(new WorkflowExecutionRows.Stop(attempt,"ABORT_CONFIRMED","session-"+attempt,"{\"abort\":true}",Instant.now().toString()))); }
    private String createAttempt() {
        var node=new Node("work","代码节点",NodeKind.WORK,"free.write",1,"builtin.implementation","实现目标",List.of(),
                List.of(new Output("code","代码",DataKind.CODE,true)),List.of(),new Completion(CompletionKind.DELIVERABLES,"有效代码交付",null),1,false,Map.of());
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"开发节点","",new WorkflowGraph(1,List.of(node),List.of(),List.of()),CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求","目标",template.id(),1));
        requirement=owner.id(); plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));
        return tx.execute(s->{var start=actions.admit(owner.id(),"work",new WorkflowNodeActions.Start(key(),plans.require(owner.id()).version(),null));
            return nodes.begin(start.node(),start.owner().headRevision(),start.inputs(),WorkflowWriterLeases.ADAPTER,"{}").id();});
    }
    private String tree() { command("add","-A"); return command("write-tree").strip(); }
    private String command(String... args) { var result=git.run(root,Duration.ofSeconds(10),List.of(args));result.requireSuccess(List.of(args));return result.output(); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static Path data() { try{return Files.createTempDirectory("workflow-code-tests-");}catch(Exception e){throw new ExceptionInInitializerError(e);} }
}
