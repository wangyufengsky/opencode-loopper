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
class WorkflowWritebackPreviewsIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("preview.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired Flyway flyway;
    @Autowired WorkflowExecutionMapper execution;
    @Autowired WorkflowWriterLeases writers;
    @Autowired WorkflowWorkspaces workspaces;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowFinishes finishes;
    @Autowired WorkflowPublicationReads publication;
    @Autowired WorkflowWritebackPreviews previews;
    @Autowired WorkflowWritebacks writebacks;
    @Autowired WorkflowWritebackExecution writebackExecution;
    @Autowired WorkflowWritebackFiles writebackFiles;
    @Autowired WorkflowWritebackMapper writebackMapper;
    @Autowired LoopperMapper mapper;
    @Autowired DirectWorkspaceLeaseCoordinator legacy;
    @Autowired TaskService tasks;
    @Autowired WorkflowDirectoryTransform transform;
    @Autowired WorkflowDirectoryStorage storage;
    @Autowired ProjectService projects;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean WorkflowDirectoryFiles files;
    @TempDir Path temporary;
    Path root;String project,requirement,attempt,templateId;
    WorkflowWriteback.Selection selected;
    @BeforeEach void setup()throws Exception {
        flyway.clean();flyway.migrate();root=Files.createDirectory(temporary.toRealPath().resolve("project"));
        Files.writeString(root.resolve("code.txt"),"original");Files.writeString(root.resolve("notes.txt"),"original notes");Files.writeString(root.resolve("remove.txt"),"old");
        Files.writeString(root.resolve(".gitignore"),"build/\n");Files.writeString(root.resolve(".env"),"fixture-only");
        project=projects.create("普通目录",root.toString(),"").id();
        var node=new Node("work","开发",NodeKind.WORK,"free.write",1,"builtin.implementation","执行",List.of(),List.of(new Output("code","代码",DataKind.CODE,true)),List.of(),new Completion(CompletionKind.DELIVERABLES,"交付",null),1,false,Map.of());
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"开发","",new WorkflowGraph(1,List.of(node),List.of(),List.of()),CanvasLayout.empty()));
        templateId=template.id();var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求","开发",template.id(),1));requirement=owner.id();plans.confirm(requirement,new WorkflowRequests.VersionCommand(key(),owner.version()));
        var identity=DirectWorkspaceLeaseCoordinator.identify(root);
        attempt=tx.execute(s->{var admission=actions.admit(requirement,"work",new WorkflowNodeActions.Start(key(),plans.require(requirement).version(),null));var a=nodes.begin(admission.node(),1,admission.inputs(),WorkflowWriterLeases.ADAPTER,"{}");writers.admit(identity,a.id());return a.id();});
        workspaces.prepare(attempt);
        tx.executeWithoutResult(s->{execution.attach(attempt,nodes.attempt(attempt).version(),"session-"+attempt,Instant.now().toString());nodes.transition(nodes.attempt(attempt),WorkflowAttemptState.RUNNING,LifecycleEvent.START);});
        Files.writeString(root.resolve("code.txt"),"result");Files.writeString(root.resolve("new.txt"),"new file");Files.delete(root.resolve("remove.txt"));
        tx.executeWithoutResult(s->execution.insertStop(new WorkflowExecutionRows.Stop(attempt,"ABORT_CONFIRMED","session-"+attempt,"{\"abort\":true}",Instant.now().toString())));
        workspaces.capture(attempt);var output=workspaces.codeDelivery(attempt);workspaces.restore(attempt);
        tx.executeWithoutResult(s->{nodes.accept(nodes.attempt(attempt),new WorkflowDelivery("代码",null,Map.of("code",new WorkflowDelivery.Value(DataKind.CODE,json.valueToTree(output)))));nodes.finish(nodes.attempt(attempt),WorkflowAttemptState.SUCCEEDED);});
        workspaces.release(attempt);finishes.request(requirement,new WorkflowFinishes.Request(key(),plans.require(requirement).version(),WorkflowState.COMPLETED,"确认完成"));
        var preview=publication.preview(requirement,1,"work",attempt,"code");selected=new WorkflowWriteback.Selection(1,"work",attempt,"code",preview.sha256());clearInvocations(files);
    }
    @Test void explicitPreviewPreservesUnrelatedUserWorkAndDoesNotWriteOrAcquireALease()throws Exception {
        Files.writeString(root.resolve("notes.txt"),"user notes");Files.writeString(root.resolve("user.txt"),"user file");
        var lease=jdbc.queryForList("SELECT * FROM workspace_lease");var before=jdbc.queryForObject("SELECT count(*) FROM state_transition_event",Integer.class);
        doAnswer(call->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return call.callRealMethod();}).when(files).discover(any(),any(),any(),anyList());
        var result=previews.inspect(requirement,selected);assertThat(result.preview().conflictCount()).isZero();
        assertThat(List.of(result.preview().added(),result.preview().modified(),result.preview().deleted(),result.preview().preservedChanges())).containsExactly(1,1,1,2);
        assertThat(result.plan().after().files()).extracting(WorkflowCodeSnapshot.File::path).contains("notes.txt","user.txt","new.txt").doesNotContain("remove.txt",".env");
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");assertThat(root.resolve("new.txt")).doesNotExist();assertThat(Files.readString(root.resolve("user.txt"))).isEqualTo("user file");
        assertThat(jdbc.queryForList("SELECT * FROM workspace_lease")).isEqualTo(lease);assertThat(jdbc.queryForObject("SELECT count(*) FROM state_transition_event",Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_publication",Integer.class)).isZero();assertThat(root.resolve(".git")).doesNotExist();
    }
    @Test void changedTargetReportsConflictAndAnotherScanHasANewDigest()throws Exception {
        var before=previews.inspect(requirement,selected).preview();Files.writeString(root.resolve("code.txt"),"later user code");var conflict=previews.inspect(requirement,selected);
        assertThat(conflict.preview().conflicts()).containsExactly("code.txt");assertThat(conflict.preview().targetSha256()).isNull();assertThat(conflict.plan().after()).isNull();assertThat(conflict.preview().sha256()).isNotEqualTo(before.sha256());
        Files.writeString(root.resolve("code.txt"),"result");var matched=previews.inspect(requirement,selected).preview();assertThat(matched.conflictCount()).isZero();assertThat(matched.modified()).isZero();
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("result");
    }
    @Test void staleSourceOtherOwnerAndReplacedDirectoryCannotBeUsed()throws Exception {
        assertThatThrownBy(()->previews.inspect(requirement,new WorkflowWriteback.Selection(1,"work",attempt,"code","0".repeat(64)))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->previews.inspect("another",selected)).isInstanceOf(RuntimeException.class);
        Path original=root.resolveSibling("original");Files.move(root,original);Files.createDirectory(root);Files.writeString(root.resolve("code.txt"),"original");
        assertThatThrownBy(()->previews.inspect(requirement,selected)).isInstanceOf(ConflictException.class);assertThat(Files.readString(original.resolve("code.txt"))).isEqualTo("original");
    }
    @Test void ignoredTargetIsStillComparedAndSymlinksCannotBeRead()throws Exception {
        Files.writeString(root.resolve(".gitignore"),"build/\nnew.txt\n");Files.writeString(root.resolve("new.txt"),"ignored user file");
        assertThat(previews.inspect(requirement,selected).preview().conflicts()).containsExactly("new.txt");
        Files.delete(root.resolve("new.txt"));Path outside=Files.writeString(temporary.resolve("outside"),"outside");Files.createSymbolicLink(root.resolve("new.txt"),outside);
        assertThatThrownBy(()->previews.inspect(requirement,selected)).isInstanceOf(ConflictException.class);assertThat(Files.readString(outside)).isEqualTo("outside");
    }
    @Test void protectedOrIgnoredDescendantsBlockAReplacementBeforeAnyOtherFileChanges()throws Exception {
        Files.createDirectory(root.resolve("new.txt"));Files.writeString(root.resolve("new.txt/.env"),"protected fixture");
        var preview=previews.inspect(requirement,selected).preview();assertThat(preview.conflicts()).containsExactly("new.txt/.env");assertThat(preview.targetSha256()).isNull();
        assertThat(Files.readString(root.resolve("new.txt/.env"))).isEqualTo("protected fixture");assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");assertThat(root.resolve("remove.txt")).exists();
    }
    @Test void sharedTransformationResumesAFrozenDeltaAndPreservesUnrelatedCurrentFiles()throws Exception {
        Files.writeString(root.resolve("notes.txt"),"user notes");Files.writeString(root.resolve("user.txt"),"user file");var context=previews.inspect(requirement,selected);
        var plan=context.plan();String operation=key();var tracked=new TreeMap<String,WorkflowCodeSnapshot.File>();plan.before().files().forEach(file->tracked.put(file.path(),file));plan.after().files().forEach(file->tracked.put(file.path(),file));
        var addition=plan.after().files().stream().filter(file->file.path().equals("new.txt")).findFirst().orElseThrow();
        Files.writeString(root.resolve("code.txt"),"result");Files.write(root.resolve(WorkflowDirectoryTransform.temporary(operation,addition)),Arrays.copyOf(storage.read(attempt,addition),2));
        java.util.function.Supplier<WorkflowDirectorySnapshot> inspect=()->files.discover(root,Path.of(context.source().workspace().objectRepository()),DATA,new ArrayList<>(tracked.values()),WorkflowDirectoryTransform.temporaryPaths(operation,plan.after()));
        transform.apply(operation,plan.before(),plan.after(),file->storage.read(attempt,file),inspect);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("result");assertThat(Files.readString(root.resolve("new.txt"))).isEqualTo("new file");assertThat(root.resolve("remove.txt")).doesNotExist();
        assertThat(Files.readString(root.resolve("notes.txt"))).isEqualTo("user notes");assertThat(Files.readString(root.resolve("user.txt"))).isEqualTo("user file");assertThat(root.resolve(WorkflowDirectoryTransform.temporary(operation,addition))).doesNotExist();
        transform.apply(operation,plan.before(),plan.after(),file->{throw new AssertionError("completed application must not rewrite a file");},inspect);
        Files.writeString(root.resolve("code.txt"),"new user change");assertThatThrownBy(()->transform.apply(operation,plan.before(),plan.after(),file->storage.read(attempt,file),inspect)).isInstanceOf(ConflictException.class);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("new user change");assertThat(Files.readString(root.resolve(".env"))).isEqualTo("fixture-only");
    }
    @Test void confirmationFreezesExactFilesAndCompletionPreservesUserWorkAndNeverReplaysWrites()throws Exception {
        Files.writeString(root.resolve("notes.txt"),"user notes");Files.writeString(root.resolve("user.txt"),"user file");
        var request=request();var confirmed=writebackExecution.confirm(requirement,request);
        assertThat(confirmed.state()).isEqualTo("CONFIRMED");assertThat(confirmed.queueState()).isEqualTo("ADMITTED");
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");
        var work=writebacks.work(requirement);assertThat(mapper.findWorkspaceLease(root.toString()).orElseThrow().holderWritebackId()).isEqualTo(work.row().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isZero();
        writebackExecution.advance(requirement);assertThat(writebacks.get(requirement).state()).isEqualTo("APPLIED");
        assertThat(writebacks.get(requirement).appliedAt()).isNotBlank();assertThat(writebacks.get(requirement).queueState()).isEqualTo("FINISHED");
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("result");assertThat(Files.readString(root.resolve("new.txt"))).isEqualTo("new file");assertThat(root.resolve("remove.txt")).doesNotExist();
        assertThat(Files.readString(root.resolve("user.txt"))).isEqualTo("user file");assertThat(Files.readString(root.resolve("notes.txt"))).isEqualTo("user notes");assertThat(Files.readString(root.resolve(".env"))).isEqualTo("fixture-only");
        for(var file:work.intent().before().files())assertThat(storage.read(work.row().id(),file)).isNotNull();
        Files.writeString(root.resolve("code.txt"),"next owner code");writebackExecution.advance(requirement);
        assertThat(writebackExecution.confirm(requirement,request).state()).isEqualTo("APPLIED");assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("next owner code");
        assertThat(mapper.findWorkspaceLease(root.toString()).orElseThrow().state()).isEqualTo("RELEASED");assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void staleConsentAndKeyReuseCannotCreateAnotherIntentOrAcquireOwnership()throws Exception {
        var request=request();Files.writeString(root.resolve("notes.txt"),"changed after preview");
        assertThatThrownBy(()->writebackExecution.confirm(requirement,request)).isInstanceOf(ConflictException.class);
        assertThat(writebacks.get(requirement)).isNull();assertThat(mapper.findWorkspaceLease(root.toString()).orElseThrow().state()).isEqualTo("RELEASED");
        var current=request();var confirmed=writebackExecution.confirm(requirement,current);
        assertThat(writebackExecution.confirm(requirement,current)).isEqualTo(confirmed);
        assertThatThrownBy(()->writebackExecution.confirm(requirement,new WorkflowWriteback.Request(current.requestKey(),current.expectedVersion()+1,selected,current.previewSha256()))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->writebackExecution.confirm("other",current)).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_writeback",Integer.class)).isEqualTo(1);
    }
    @Test void tasksWritebackAndNodesUseOneFifoAndLatePublicationCannotTouchNextWriter()throws Exception {
        legacyTask("first");legacyTask("last");var identity=DirectWorkspaceLeaseCoordinator.identify(root);
        legacy.acquireOrEnqueue(identity,"first","MANUAL",null);var confirmed=writebackExecution.confirm(requirement,request());
        assertThat(confirmed.queueState()).isEqualTo("QUEUED");String node=waitingNode(identity);legacy.acquireOrEnqueue(identity,"last","MANUAL",null);
        assertThat(tasks.queueStatus("last").queuePosition()).isEqualTo(3);
        writebackExecution.advance(requirement);assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");
        legacy.releaseAfterWriterStopped(root,"first","stopped");assertThat(writebacks.get(requirement).queueState()).isEqualTo("ADMITTED");
        assertThatThrownBy(()->tasks.reconcileQueue("last")).isInstanceOfSatisfying(ConflictException.class,e->assertThat(e.code()).isEqualTo("WORKFLOW_WRITEBACK_ACTIVE"));
        writebackExecution.advance(requirement);assertThat(mapper.findWorkspaceLease(root.toString()).orElseThrow().holderWorkflowAttemptId()).isEqualTo(node);
        Files.writeString(root.resolve("code.txt"),"next node work");writebackExecution.advance(requirement);assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("next node work");
        assertThat(tasks.queueStatus("last").queuePosition()).isEqualTo(1);
        assertThat(mapper.findTaskQueue("last").orElseThrow().state()).isEqualTo("QUEUED");
    }
    @Test void changedDirectoryWhileQueuedBlocksWithoutWritesAndRetryKeepsOriginalConsent()throws Exception {
        legacyTask("holder");legacy.acquireOrEnqueue(DirectWorkspaceLeaseCoordinator.identify(root),"holder","MANUAL",null);
        writebackExecution.confirm(requirement,request());Files.writeString(root.resolve("notes.txt"),"other owner work");legacy.releaseAfterWriterStopped(root,"holder","stopped");
        writebackExecution.advance(requirement);var blocked=writebacks.get(requirement);assertThat(blocked.state()).isEqualTo("BLOCKED");assertThat(blocked.blocker()).isEqualTo("WORKFLOW_WRITEBACK_PREPARATION_CHANGED");
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");assertThat(Files.readString(root.resolve("notes.txt"))).isEqualTo("other owner work");
        assertThat(writebacks.work(requirement).row().preparedAt()).isNull();assertThat(mapper.findWorkspaceLease(root.toString()).orElseThrow().state()).isEqualTo("HELD");
        assertThatThrownBy(()->writebacks.retry(requirement,blocked.version()+1)).isInstanceOf(ConflictException.class);
        Files.writeString(root.resolve("notes.txt"),"original notes");writebacks.retry(requirement,blocked.version());writebackExecution.advance(requirement);assertThat(writebacks.get(requirement).state()).isEqualTo("APPLIED");
    }
    @Test void finalTransactionFailureRetainsReceiptAbsenceAndLeaseThenRecoversWithoutAnotherWrite()throws Exception {
        writebackExecution.confirm(requirement,request());legacyTask("next");legacy.acquireOrEnqueue(DirectWorkspaceLeaseCoordinator.identify(root),"next","MANUAL",null);
        jdbc.execute("CREATE TRIGGER test_writeback_transfer BEFORE UPDATE ON workspace_lease WHEN NEW.holder_task_id='next' BEGIN SELECT RAISE(ABORT,'receipt failure fixture'); END");
        assertThatThrownBy(()->writebackExecution.advance(requirement)).isInstanceOf(RuntimeException.class);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("result");var work=writebacks.work(requirement);
        assertThat(work.row().state()).isEqualTo("APPLYING");assertThat(writebackMapper.receipt(work.row().id())).isEmpty();assertThat(work.queueState()).isEqualTo("ADMITTED");
        assertThat(mapper.findTaskQueue("next").orElseThrow().state()).isEqualTo("QUEUED");var modified=Files.getLastModifiedTime(root.resolve("code.txt"));
        jdbc.execute("DROP TRIGGER test_writeback_transfer");writebackExecution.advance(requirement);assertThat(writebacks.get(requirement).state()).isEqualTo("APPLIED");
        assertThat(Files.getLastModifiedTime(root.resolve("code.txt"))).isEqualTo(modified);assertThat(mapper.findWorkspaceLease(root.toString()).orElseThrow().holderTaskId()).isEqualTo("next");
    }
    @Test void liveOperationLockExcludesAnotherRecoveryAndPartialFilesResumeOnlyFromStoredBytes()throws Exception {
        writebackExecution.confirm(requirement,request());var work=writebacks.work(requirement);
        try(var guard=writebackFiles.lock(work.row().id())) {
            assertThat(guard).isNotNull();writebackExecution.advance(requirement);assertThat(writebacks.get(requirement).state()).isEqualTo("CONFIRMED");
            writebackFiles.prepare(work.row().id(),work.intent());work=writebacks.prepared(work);
            Files.writeString(root.resolve("code.txt"),"result");var addition=work.intent().after().files().stream().filter(f->f.path().equals("new.txt")).findFirst().orElseThrow();
            Files.write(root.resolve(WorkflowDirectoryTransform.temporary(work.row().id(),addition)),Arrays.copyOf(storage.read(work.row().id(),addition),2));
            writebackExecution.advance(requirement);assertThat(root.resolve("new.txt")).doesNotExist();
        }
        writebackExecution.advance(requirement);assertThat(writebacks.get(requirement).state()).isEqualTo("APPLIED");assertThat(Files.readString(root.resolve("new.txt"))).isEqualTo("new file");
        assertThat(root.resolve("remove.txt")).doesNotExist();
    }
    @Test void afterPreparationUserConflictsCannotBeRebaselinedAndBackupDamageRetainsOwnership()throws Exception {
        writebackExecution.confirm(requirement,request());var work=writebacks.work(requirement);
        try(var guard=writebackFiles.lock(work.row().id())){writebackFiles.prepare(work.row().id(),work.intent());work=writebacks.prepared(work);}
        Files.writeString(root.resolve("code.txt"),"later user edit");writebackExecution.advance(requirement);assertThat(writebacks.get(requirement).state()).isEqualTo("BLOCKED");
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("later user edit");Files.writeString(root.resolve("code.txt"),"original");
        var saved=work.intent().before().files().stream().filter(f->f.path().equals("remove.txt")).findFirst().orElseThrow();
        Files.writeString(storage.repository(work.row().id()).getParent().resolve("objects").resolve(saved.sha256()),"corrupt fixture");
        writebacks.retry(requirement,writebacks.get(requirement).version());writebackExecution.advance(requirement);assertThat(writebacks.get(requirement).state()).isEqualTo("BLOCKED");
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");assertThat(mapper.findWorkspaceLease(root.toString()).orElseThrow().holderWritebackId()).isEqualTo(work.row().id());
    }
    @Test void databaseGuardsRetainConsentReceiptAndIndependentOwnership()throws Exception {
        writebackExecution.confirm(requirement,request());var work=writebacks.work(requirement);legacyTask("other");
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_writeback SET intent_json='{}' WHERE id=?",work.row().id())).hasMessageContaining("workflow writeback");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_writeback WHERE id=?",work.row().id())).hasMessageContaining("retained");
        assertThatThrownBy(()->jdbc.update("UPDATE workspace_lease SET holder_task_id='other' WHERE holder_writeback_id=?",work.row().id())).hasMessageContaining("incompatible owners");
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_writeback SET state='APPLIED',version=version+1 WHERE id=?",work.row().id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_writeback_queue SET position=position+1 WHERE writeback_id=?",work.row().id())).isInstanceOf(RuntimeException.class);
        writebackExecution.advance(requirement);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_writeback_receipt WHERE writeback_id=?",work.row().id())).hasMessageContaining("retained");
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_writeback SET state='CONFIRMED',version=version+1 WHERE id=?",work.row().id())).isInstanceOf(RuntimeException.class);
    }
    @Test void aSeparateLiveProcessExcludesRecoveryAndItsExitIsRequiredBeforeApplying()throws Exception {
        writebackExecution.confirm(requirement,request());var work=writebacks.work(requirement);Path lock=storage.repository(work.row().id()).getParent().resolve("writeback.lock");Files.createDirectories(lock.getParent());
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),LockHolder.class.getName(),lock.toString()).redirectErrorStream(true).start();
        try {
            var ready=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return process.inputReader().readLine();}catch(Exception e){throw new RuntimeException(e);}});
            assertThat(ready.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("LOCKED");
            writebackExecution.advance(requirement);assertThat(writebacks.get(requirement).state()).isEqualTo("CONFIRMED");assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");
            process.destroyForcibly();assertThat(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            writebackExecution.advance(requirement);assertThat(writebacks.get(requirement).state()).isEqualTo("APPLIED");
        }finally{if(process.isAlive()){process.destroyForcibly();assertThat(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}}
    }
    @Test void nodeHandsOffToWritebackAndThenLegacyWhileNestedWritersRemainBlocked()throws Exception {
        var identity=DirectWorkspaceLeaseCoordinator.identify(root);String node=waitingNode(identity);
        assertThat(writebackExecution.confirm(requirement,request()).queueState()).isEqualTo("QUEUED");legacyTask("last");legacy.acquireOrEnqueue(identity,"last","MANUAL",null);
        tx.executeWithoutResult(s->{execution.attach(node,nodes.attempt(node).version(),"session-"+node,Instant.now().toString());nodes.transition(nodes.attempt(node),WorkflowAttemptState.RUNNING,LifecycleEvent.START);nodes.transition(nodes.attempt(node),WorkflowAttemptState.STOPPING,LifecycleEvent.CANCEL);execution.insertStop(new WorkflowExecutionRows.Stop(node,"ABORT_CONFIRMED","session-"+node,"{\"abort\":true}",Instant.now().toString()));nodes.finish(nodes.attempt(node),WorkflowAttemptState.CANCELLED);});
        var held=mapper.findWorkspaceLease(root.toString()).orElseThrow();tx.executeWithoutResult(s->writers.releaseAfterStopped(identity,node,held.version()));
        var writeback=writebacks.work(requirement);assertThat(mapper.findWorkspaceLease(root.toString()).orElseThrow().holderWritebackId()).isEqualTo(writeback.row().id());
        Path child=Files.createDirectory(root.resolve("nested"));String childProject=projects.create("子目录",child.toString(),"").id(),now=Instant.now().toString();
        mapper.insertTask(new TaskRow("nested-task",childProject,null,"嵌套工作","READY",child.toString(),"DIRECT",null,"direct:test",now,now,0,null,null,null,"LEGACY_AGGREGATE",null));
        assertThatThrownBy(()->legacy.acquireOrEnqueue(DirectWorkspaceLeaseCoordinator.identifyDirectory(child),"nested-task","MANUAL",null)).isInstanceOf(TaskFailure.class);
        writebackExecution.advance(requirement);assertThat(writebacks.get(requirement).state()).isEqualTo("APPLIED");assertThat(mapper.findWorkspaceLease(root.toString()).orElseThrow().holderTaskId()).isEqualTo("last");
    }
    @Test void concurrentLegacyAndPublicationConfirmationCannotCreateTwoWriters()throws Exception {
        legacyTask("parallel");var identity=DirectWorkspaceLeaseCoordinator.identify(root);var request=request();var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{barrier.await();return legacy.acquireOrEnqueue(identity,"parallel","MANUAL",null).state();});
            var second=pool.submit(()->{barrier.await();return writebackExecution.confirm(requirement,request).queueState();});
            assertThat(List.of(first.get(20,java.util.concurrent.TimeUnit.SECONDS),second.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder("ADMITTED","QUEUED");
        }
        var holder=mapper.findWorkspaceLease(root.toString()).orElseThrow();assertThat((holder.holderTaskId()==null)!=(holder.holderWritebackId()==null)).isTrue();assertThat(holder.holderWorkflowAttemptId()).isNull();
        assertThat(mapper.findTaskQueue("parallel").orElseThrow().position()).isNotEqualTo(mapper.findWritebackQueue(writebacks.work(requirement).row().id()).orElseThrow().position());
    }
    @Test void unownedTemporaryFileBeforePreparationIsNeverClaimedOrDeleted()throws Exception {
        writebackExecution.confirm(requirement,request());var work=writebacks.work(requirement);var addition=work.intent().after().files().stream().filter(f->f.path().equals("new.txt")).findFirst().orElseThrow();
        Path temporary=root.resolve(WorkflowDirectoryTransform.temporary(work.row().id(),addition));Files.writeString(temporary,"n");
        writebackExecution.advance(requirement);assertThat(writebacks.get(requirement).state()).isEqualTo("BLOCKED");assertThat(work.row().preparedAt()).isNull();
        assertThat(Files.readString(temporary)).isEqualTo("n");assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("original");
    }
    public static final class LockHolder {
        public static void main(String[] arguments)throws Exception {
            try(var channel=java.nio.channels.FileChannel.open(Path.of(arguments[0]),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()) {
                System.out.println("LOCKED");System.out.flush();System.in.read();
            }
        }
    }
    private WorkflowWriteback.Request request(){var preview=previews.inspect(requirement,selected).preview();return new WorkflowWriteback.Request(key(),preview.requirementVersion(),selected,preview.sha256());}
    private void legacyTask(String id) {
        String now=Instant.now().toString();mapper.insertTask(new TaskRow(id,project,null,id,"READY",root.toString(),"DIRECT",null,"direct:test",now,now,0,null,null,null,"LEGACY_AGGREGATE",null));
    }
    private String waitingNode(DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity) {
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"后续节点","开发",templateId,1));plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));
        return tx.execute(s->{var admission=actions.admit(owner.id(),"work",new WorkflowNodeActions.Start(key(),plans.require(owner.id()).version(),null));var a=nodes.begin(admission.node(),1,admission.inputs(),WorkflowWriterLeases.ADAPTER,"{}");writers.admit(identity,a.id());return a.id();});
    }
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-writeback-preview-").toRealPath();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
