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
import java.time.Instant;
import java.util.*;
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

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.monitor-delay=1h","loopper.workflow-monitor-enabled=false"})
class WorkflowDirectoryWorkspacesIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("loopper.data-dir",()->DATA.toString());
        p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("directory-workspaces.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowExecutionMapper execution;
    @Autowired LoopperMapper mapper;
    @Autowired WorkflowWriterLeases writers;
    @Autowired WorkflowWorkspaces workspaces;
    @Autowired WorkflowWorkspaceStore store;
    @Autowired WorkflowDirectoryStore directoryStore;
    @MockitoSpyBean WorkflowDirectoryStorage storage;
    @Autowired WorkflowCodeSnapshots codes;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowModelStore models;
    @Autowired ProjectService projects;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean WorkflowDirectoryWorkspaces directories;
    @MockitoSpyBean WorkflowDirectoryMutations mutations;
    @TempDir Path temporary;
    Path root;
    String project,requirement,first;
    DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity;
    @BeforeEach void setup()throws Exception {
        flyway.clean();flyway.migrate();root=Files.createDirectory(temporary.toRealPath().resolve("project"));
        Files.writeString(root.resolve("code.txt"),"original");Files.writeString(root.resolve(".gitignore"),"build/\n");
        Files.writeString(root.resolve(".env"),"fixture-only");
        project=projects.create("普通目录",root.toString(),"").id();identity=DirectWorkspaceLeaseCoordinator.identify(root);
        var graph=new WorkflowGraph(1,List.of(node("first",List.of()),node("second",List.of(new Input("workspace",InputSource.NODE,"first","code",DataKind.CODE,true)))),
                List.of(new Edge("dependency","first","second",null)),List.of());
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"顺序执行","",graph,CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求","连续执行",template.id(),1));requirement=owner.id();
        plans.confirm(requirement,new WorkflowRequests.VersionCommand(key(),owner.version()));first=start("first");
    }
    @Test void twoPlainDirectoryWritersUsePinnedOutputsAndRestoreOriginalFilesBeforeHandoff()throws Exception {
        var ready=workspaces.prepare(first);assertThat(ready.objectRepository()).startsWith(DATA.toString());
        run(first);Files.writeString(root.resolve("code.txt"),"first result");Files.write(root.resolve("binary.bin"),new byte[]{0,(byte)255,13,10});
        Files.createDirectory(root.resolve("build"));Files.writeString(root.resolve("build/cache"),"ignored cache");stopProof(first);
        workspaces.capture(first);var parent=workspaces.codeDelivery(first);workspaces.restore(first);complete(first,parent);workspaces.release(first);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");assertThat(root.resolve("binary.bin")).doesNotExist();
        assertThat(Files.readString(root.resolve(".env"))).isEqualTo("fixture-only");assertThat(Files.readString(root.resolve("build/cache"))).isEqualTo("ignored cache");
        String second=start("second");var child=workspaces.prepare(second);
        assertThat(child.seedSnapshotId()).isEqualTo(parent.snapshotId());assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("first result");
        assertThat(Files.readAllBytes(root.resolve("binary.bin"))).containsExactly(0,(byte)255,13,10);
        run(second);Files.writeString(root.resolve("code.txt"),"second result");stopProof(second);workspaces.capture(second);
        var result=workspaces.codeDelivery(second);workspaces.restore(second);complete(second,result);workspaces.release(second);
        assertThat(new String(codes.read(project,requirement,first,parent,"code.txt"))).isEqualTo("first result");
        assertThat(new String(codes.read(project,requirement,second,result,"code.txt"))).isEqualTo("second result");
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");assertThat(root.resolve(".git")).doesNotExist();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isZero();
        assertNoPending();
    }
    @Test void partialSeedAndTemporaryBytesResumeTheSameFrozenOperation()throws Exception {
        var result=parent();String second=start("second");var once=new AtomicBoolean(true);
        doAnswer(call->{var intent=call.<WorkflowDirectoryMapper.Apply>getArgument(0);
            if(intent.phase().equals("SEED")&&once.getAndSet(false)) {
                Files.writeString(root.resolve("code.txt"),"parent");
                var file=directoryStore.after(intent).files().stream().filter(value->value.path().equals("new.txt")).findFirst().orElseThrow();
                Files.write(root.resolve(WorkflowDirectoryMutations.temporary(intent,file)),Arrays.copyOf(storage.read(second,file),2));
                throw new IllegalStateException("interrupted seed");
            }return call.callRealMethod();}).when(mutations).apply(any());
        assertThatThrownBy(()->workspaces.prepare(second)).hasMessage("interrupted seed");
        var intent=directoryStore.apply(second,"SEED").orElseThrow();assertThat(store.require(second).state()).isEqualTo("PREPARING");
        assertThat(workspaces.prepare(second).state()).isEqualTo("READY");assertThat(Files.readString(root.resolve("new.txt"))).isEqualTo("added");
        assertThat(directoryStore.apply(second,"SEED").orElseThrow()).isEqualTo(intent);assertNoPending();
        assertThat(codes.manifest(project,requirement,first,result).files()).extracting(WorkflowCodeSnapshot.File::path).contains("new.txt");
    }
    @Test void cancellingPartialSeedRestoresOriginalAndCleansOnlyOwnedTemporaryFiles()throws Exception {
        parent();String second=start("second");
        doAnswer(call->{var intent=call.<WorkflowDirectoryMapper.Apply>getArgument(0);if(intent.phase().equals("SEED")) {
            Files.writeString(root.resolve("code.txt"),"parent");
            var file=directoryStore.after(intent).files().stream().filter(value->value.path().equals("new.txt")).findFirst().orElseThrow();
            Files.write(root.resolve(WorkflowDirectoryMutations.temporary(intent,file)),Arrays.copyOf(storage.read(second,file),1));
            throw new IllegalStateException("cancel during seed");
        }return call.callRealMethod();}).when(mutations).apply(any());
        assertThatThrownBy(()->workspaces.prepare(second)).hasMessage("cancel during seed");
        cancelProof(second);assertThat(workspaces.restore(second).state()).isEqualTo("RESTORED");
        tx.executeWithoutResult(s->models.completeWriter(models.require(second),null));workspaces.release(second);
        assertThat(nodes.attempt(second).state()).isEqualTo("CANCELLED");assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");
        assertThat(root.resolve("new.txt")).doesNotExist();assertNoPending();
    }
    @Test void lostCaptureAcknowledgementRetainsCheckpointAndRejectsLaterUnrelatedEdits()throws Exception {
        workspaces.prepare(first);run(first);Files.writeString(root.resolve("code.txt"),"frozen");stopProof(first);
        var once=new AtomicBoolean(true);
        doAnswer(call->{var value=call.callRealMethod();if(once.getAndSet(false))throw new IllegalStateException("lost capture");return value;}).when(directories).capture(any());
        assertThatThrownBy(()->workspaces.capture(first)).hasMessage("lost capture");
        var original=directoryStore.result(first).orElseThrow();Files.writeString(root.resolve("code.txt"),"later user edit");
        assertThatThrownBy(()->workspaces.capture(first)).isInstanceOf(ConflictException.class);
        assertThat(directoryStore.result(first).orElseThrow()).isEqualTo(original);assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("later user edit");
        Files.writeString(root.resolve("code.txt"),"frozen");var checkpoint=workspaces.capture(first);
        assertThat(checkpoint.state()).isEqualTo("FROZEN");var result=workspaces.codeDelivery(first);workspaces.restore(first);complete(first,result);workspaces.release(first);
        assertThat(new String(codes.read(project,requirement,first,result,"code.txt"))).isEqualTo("frozen");
    }
    @Test void restoreAcknowledgementLossDoesNotOverwriteNewUserFilesOrTouchNextWriterOnReceiptReplay()throws Exception {
        workspaces.prepare(first);run(first);Files.writeString(root.resolve("code.txt"),"result");stopProof(first);workspaces.capture(first);var result=workspaces.codeDelivery(first);
        var once=new AtomicBoolean(true);
        doAnswer(call->{call.callRealMethod();if(once.getAndSet(false))throw new IllegalStateException("lost restore");return null;}).when(directories).restore(any());
        assertThatThrownBy(()->workspaces.restore(first)).hasMessage("lost restore");
        Files.writeString(root.resolve("user.txt"),"later user file");
        assertThatThrownBy(()->workspaces.restore(first)).isInstanceOf(ConflictException.class);assertThat(Files.readString(root.resolve("user.txt"))).isEqualTo("later user file");
        assertThat(mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().holderWorkflowAttemptId()).isEqualTo(first);
        Files.delete(root.resolve("user.txt"));workspaces.restore(first);complete(first,result);var receipt=workspaces.release(first);
        Files.writeString(root.resolve("next.txt"),"new owner");assertThat(workspaces.release(first)).isEqualTo(receipt);
        assertThat(Files.readString(root.resolve("next.txt"))).isEqualTo("new owner");
    }
    @Test void unknownStopKeepsOriginalLeaseUntilTheSameAttemptCanFinish()throws Exception {
        workspaces.prepare(first);run(first);Files.writeString(root.resolve("code.txt"),"owned");tx.executeWithoutResult(s->writers.markUnconfirmed(identity,first));
        assertThatThrownBy(()->workspaces.capture(first)).isInstanceOf(ConflictException.class);assertThatThrownBy(()->workspaces.restore(first)).isInstanceOf(ConflictException.class);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("owned");stopProof(first);workspaces.capture(first);var result=workspaces.codeDelivery(first);
        workspaces.restore(first);complete(first,result);workspaces.release(first);assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");
    }
    @Test void newBaselineBeforeChildAndDriftBeforeLaunchRemainUntouched()throws Exception {
        parent();Files.writeString(root.resolve("code.txt"),"user new baseline");String second=start("second");
        assertThatThrownBy(()->workspaces.prepare(second)).isInstanceOfSatisfying(ConflictException.class,error->assertThat(error.code()).isEqualTo("WORKFLOW_CODE_BASE_CHANGED"));
        assertThat(store.find(second)).isEmpty();assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("user new baseline");
    }
    @Test void nestedFileDirectoryTransitionsRestoreBothShapesAndPreserveExecutableMode()throws Exception {
        Files.writeString(root.resolve("swap"),"original file");Files.createDirectory(root.resolve("tree"));Files.writeString(root.resolve("tree/child.txt"),"original child");
        workspaces.prepare(first);run(first);
        Files.delete(root.resolve("swap"));Files.createDirectory(root.resolve("swap"));Files.writeString(root.resolve("swap/child.txt"),"new child");
        Files.delete(root.resolve("tree/child.txt"));Files.delete(root.resolve("tree"));Files.writeString(root.resolve("tree"),"new file");
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            var permissions=Files.getPosixFilePermissions(root.resolve("tree"));permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);Files.setPosixFilePermissions(root.resolve("tree"),permissions);
        }
        stopProof(first);workspaces.capture(first);var result=workspaces.codeDelivery(first);workspaces.restore(first);complete(first,result);workspaces.release(first);
        assertThat(Files.readString(root.resolve("swap"))).isEqualTo("original file");assertThat(Files.readString(root.resolve("tree/child.txt"))).isEqualTo("original child");
        String second=start("second");workspaces.prepare(second);
        assertThat(Files.readString(root.resolve("swap/child.txt"))).isEqualTo("new child");assertThat(Files.readString(root.resolve("tree"))).isEqualTo("new file");
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))assertThat(Files.isExecutable(root.resolve("tree"))).isTrue();
        cancelProof(second);workspaces.capture(second);workspaces.restore(second);tx.executeWithoutResult(s->models.completeWriter(models.require(second),null));workspaces.release(second);
        assertThat(Files.readString(root.resolve("swap"))).isEqualTo("original file");assertThat(Files.readString(root.resolve("tree/child.txt"))).isEqualTo("original child");assertNoPending();
    }
    @Test void cancellingBeforeSeedBodiesAreCopiedUsesTheOriginalBaselineWithoutInventingAnExecution()throws Exception {
        parent();String second=start("second");
        doAnswer(call->{if(directoryStore.apply(second,"SEED").isPresent())throw new IllegalStateException("seed copy interrupted");return call.callRealMethod();})
                .when(storage).importFiles(eq(second),any(),any());
        assertThatThrownBy(()->workspaces.prepare(second)).hasMessage("seed copy interrupted");
        assertThat(directoryStore.apply(second,"SEED")).isPresent();assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");
        cancelProof(second);workspaces.restore(second);tx.executeWithoutResult(s->models.completeWriter(models.require(second),null));workspaces.release(second);
        assertThat(nodes.attempt(second).state()).isEqualTo("CANCELLED");assertThat(root.resolve("new.txt")).doesNotExist();assertNoPending();
    }
    @Test void partiallyRestoredFilesResumeWithTheSameJournalAndRejectInterveningEdits()throws Exception {
        workspaces.prepare(first);run(first);Files.writeString(root.resolve("code.txt"),"result");
        Files.writeString(root.resolve(".gitignore"),"build/\nextra/\n");Files.writeString(root.resolve("new.txt"),"created");
        stopProof(first);workspaces.capture(first);var result=workspaces.codeDelivery(first);var once=new AtomicBoolean(true);
        doAnswer(call->{var intent=call.<WorkflowDirectoryMapper.Apply>getArgument(0);
            if(intent.phase().equals("RESTORE")&&once.getAndSet(false)) {
                Files.writeString(root.resolve("code.txt"),"original");Files.delete(root.resolve("new.txt"));
                var file=directoryStore.after(intent).files().stream().filter(value->value.path().equals(".gitignore")).findFirst().orElseThrow();
                Files.write(root.resolve(WorkflowDirectoryMutations.temporary(intent,file)),Arrays.copyOf(storage.read(first,file),2));
                throw new IllegalStateException("partial restore");
            }return call.callRealMethod();}).when(mutations).apply(any());
        assertThatThrownBy(()->workspaces.restore(first)).hasMessage("partial restore");var intent=directoryStore.apply(first,"RESTORE").orElseThrow();
        Files.writeString(root.resolve("code.txt"),"user edit");assertThatThrownBy(()->workspaces.restore(first)).isInstanceOf(ConflictException.class);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("user edit");assertThat(store.require(first).state()).isEqualTo("RESTORING");
        Files.writeString(root.resolve("code.txt"),"original");workspaces.restore(first);complete(first,result);workspaces.release(first);
        assertThat(directoryStore.apply(first,"RESTORE").orElseThrow()).isEqualTo(intent);assertThat(Files.readString(root.resolve(".gitignore"))).isEqualTo("build/\n");
        assertThat(new String(codes.read(project,requirement,first,result,"code.txt"))).isEqualTo("result");assertNoPending();
    }
    @Test void symlinkAfterCaptureCannotRedirectRestorationIntoAnOutsideFile()throws Exception {
        workspaces.prepare(first);run(first);Files.writeString(root.resolve("code.txt"),"result");stopProof(first);workspaces.capture(first);
        var result=workspaces.codeDelivery(first);Path outside=Files.writeString(temporary.resolve("outside.txt"),"outside");
        Files.delete(root.resolve("code.txt"));Files.createSymbolicLink(root.resolve("code.txt"),outside);
        assertThatThrownBy(()->workspaces.restore(first)).isInstanceOf(ConflictException.class);assertThat(Files.readString(outside)).isEqualTo("outside");
        assertThat(mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().holderWorkflowAttemptId()).isEqualTo(first);
        Files.delete(root.resolve("code.txt"));Files.writeString(root.resolve("code.txt"),"result");
        workspaces.restore(first);complete(first,result);workspaces.release(first);assertThat(Files.readString(outside)).isEqualTo("outside");
    }
    @Test void protectedFileInsideADirectoryBlocksReplacingThatDirectoryWithAnOriginalFile()throws Exception {
        Files.writeString(root.resolve("swap"),"original file");workspaces.prepare(first);run(first);
        Files.delete(root.resolve("swap"));Files.createDirectory(root.resolve("swap"));Files.writeString(root.resolve("swap/new.txt"),"result");
        Files.writeString(root.resolve("swap/.env"),"protected fixture");stopProof(first);workspaces.capture(first);var result=workspaces.codeDelivery(first);
        assertThatThrownBy(()->workspaces.restore(first)).isInstanceOf(ConflictException.class);
        assertThat(Files.readString(root.resolve("swap/.env"))).isEqualTo("protected fixture");assertThat(Files.readString(root.resolve("swap/new.txt"))).isEqualTo("result");assertThat(store.require(first).state()).isEqualTo("RESTORING");
        Files.delete(root.resolve("swap/.env"));workspaces.restore(first);complete(first,result);workspaces.release(first);
        assertThat(Files.readString(root.resolve("swap"))).isEqualTo("original file");
    }
    @Test void databaseGuardsRequireStoppedOwnershipAndRetainResultAndApplicationHistory()throws Exception {
        workspaces.prepare(first);var prepared=directoryStore.preparation(first);
        assertThatThrownBy(()->jdbc.update("INSERT INTO workflow_directory_result VALUES(?,?,?,?)",first,prepared.manifestJson(),prepared.manifestSha256(),"t"))
                .hasMessageContaining("requires stopped owner");
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_workspace SET object_repository=NULL WHERE attempt_id=?",first)).hasMessageContaining("immutable");
        run(first);stopProof(first);workspaces.capture(first);var result=workspaces.codeDelivery(first);
        assertThatThrownBy(()->jdbc.update("INSERT INTO workflow_directory_apply VALUES(?,?,'RESTORE',?,?,?,?,?)",key(),first,
                prepared.manifestJson(),prepared.manifestSha256(),prepared.manifestJson(),prepared.manifestSha256(),"t")).hasMessageContaining("owner mismatch");
        workspaces.restore(first);complete(first,result);workspaces.release(first);
        for(String table:List.of("workflow_directory_result","workflow_directory_apply")) {
            assertThatThrownBy(()->jdbc.update("UPDATE "+table+" SET created_at='changed' WHERE attempt_id=?",first)).hasMessageContaining("immutable");
            assertThatThrownBy(()->jdbc.update("DELETE FROM "+table+" WHERE attempt_id=?",first)).hasMessageContaining("retained");
        }
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_code_snapshot SET object_repository=NULL WHERE attempt_id=?",first)).hasMessageContaining("immutable");
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    private WorkflowCodeSnapshot.Reference parent()throws Exception {
        workspaces.prepare(first);run(first);Files.writeString(root.resolve("code.txt"),"parent");Files.writeString(root.resolve("new.txt"),"added");stopProof(first);
        workspaces.capture(first);var result=workspaces.codeDelivery(first);workspaces.restore(first);complete(first,result);workspaces.release(first);return result;
    }
    private Node node(String id,List<Input> inputs) { return new Node(id,id,NodeKind.WORK,"free.write",1,"builtin.implementation","执行",inputs,List.of(new Output("code","代码",DataKind.CODE,true)),List.of(),new Completion(CompletionKind.DELIVERABLES,"交付",null),1,false,Map.of()); }
    private String start(String key) { return tx.execute(s->{var admission=actions.admit(requirement,key,new WorkflowNodeActions.Start(key(),plans.require(requirement).version(),null));
        var attempt=nodes.begin(admission.node(),admission.owner().headRevision(),admission.inputs(),WorkflowWriterLeases.ADAPTER,"{}");writers.admit(identity,attempt.id());return attempt.id();}); }
    private void run(String id) { tx.executeWithoutResult(s->{execution.attach(id,nodes.attempt(id).version(),"session-"+id,Instant.now().toString());nodes.transition(nodes.attempt(id),WorkflowAttemptState.RUNNING,LifecycleEvent.START);}); }
    private void stopProof(String id) { tx.executeWithoutResult(s->execution.insertStop(new WorkflowExecutionRows.Stop(id,"ABORT_CONFIRMED","session-"+id,"{\"abort\":true}",Instant.now().toString()))); }
    private void cancelProof(String id) {
        tx.executeWithoutResult(s->models.create(nodes.attempt(id),plans.require(requirement),root,new OpenCodeClient.OpenCodeModel("fake","test",false)));
        models.stop(id);models.writerStopped(models.require(id),"NO_SESSION_CREATED",Map.of("phase","PREPARING"),false);
    }
    private void complete(String id,WorkflowCodeSnapshot.Reference reference) { tx.executeWithoutResult(s->{nodes.accept(nodes.attempt(id),new WorkflowDelivery("代码",null,Map.of("code",new WorkflowDelivery.Value(DataKind.CODE,json.valueToTree(reference)))));nodes.finish(nodes.attempt(id),WorkflowAttemptState.SUCCEEDED);}); }
    private void assertNoPending()throws Exception { try(var paths=Files.walk(root)){assertThat(paths.noneMatch(path->path.getFileName().toString().startsWith(".loopper-"))).isTrue();} }
    private static String key() { return UUID.randomUUID().toString(); }
    private static Path data(){try{return Files.createTempDirectory("workflow-directory-workspaces-").toRealPath();}catch(Exception e){throw new ExceptionInInitializerError(e);} }
}
