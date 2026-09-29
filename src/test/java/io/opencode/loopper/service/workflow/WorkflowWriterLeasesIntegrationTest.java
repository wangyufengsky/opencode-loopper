package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
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
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.monitor-delay=1h"})
class WorkflowWriterLeasesIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("loopper.data-dir",()->DATA.toString());
        p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("writers.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired LoopperMapper mapper;
    @Autowired WorkflowExecutionMapper execution;
    @Autowired DirectWorkspaceLeaseCoordinator legacy;
    @Autowired WorkflowWriterLeases writers;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    Path root;
    String project;
    DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity;
    @BeforeEach void setup() throws Exception {
        flyway.clean(); flyway.migrate();
        root=Files.createDirectory(directory.resolve("project"));
        project=projects.create("共同工作区",root.toString(),"").id();
        identity=DirectWorkspaceLeaseCoordinator.identify(root);
    }
    @Test void tasksAndNodesShareOneFifoAndTransferBackWithoutCreatingShadowTasks() {
        task("first"); task("last");
        assertThat(legacy.acquireOrEnqueue(identity,"first","MANUAL",null).state()).isEqualTo("ADMITTED");
        String node=attempt(); var waiting=admit(node);
        assertThat(waiting.state()).isEqualTo("QUEUED");
        assertThat(legacy.acquireOrEnqueue(identity,"last","MANUAL",null).state()).isEqualTo("QUEUED");
        assertThat(mapper.writerQueuePosition(identity.canonicalRoot(),mapper.findTaskQueue("last").orElseThrow().position())).isEqualTo(2);
        assertThat(tasks.queueStatus("last").queuePosition()).isEqualTo(2L);
        var transfer=legacy.releaseAfterWriterStopped(root,"first","stopped");
        assertThat(transfer.admittedNext()).isNull(); assertThat(transfer.admittedWorkflowAttemptId()).isEqualTo(node);
        var held=writers.requireWritable(identity,node);
        assertThat(held.holderTaskId()).isNull(); assertThat(held.holderWorkflowAttemptId()).isEqualTo(node);
        assertThat(mapper.findTaskQueue("last").orElseThrow().state()).isEqualTo("QUEUED");
        assertThat(tasks.queueStatus("last").queuePosition()).isEqualTo(1L);
        assertThatThrownBy(()->tasks.reconcileQueue("last")).isInstanceOfSatisfying(ConflictException.class,
                failure->assertThat(failure.code()).isEqualTo("WORKFLOW_WRITER_ACTIVE"));
        stop(node);
        var returned=tx.execute(s->writers.releaseAfterStopped(identity,node,held.version()));
        assertThat(returned.task().taskId()).isEqualTo("last");
        assertThat(returned.workflow()).isNull();
        assertThat(legacy.requireWritableLease(root,"last").holderTaskId()).isEqualTo("last");
        assertThat(mapper.findWorkflowWriter(node).orElseThrow().state()).isEqualTo("FINISHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void unknownStopKeepsTheNodeHolderAndAnOldTaskCannotAcquireOrReleaseIt() {
        String node=attempt(); admit(node); task("waiting");
        tx.executeWithoutResult(s->writers.markUnconfirmed(identity,node));
        assertThat(mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().state()).isEqualTo("RELEASE_PENDING");
        assertThatThrownBy(()->writers.requireWritable(identity,node)).isInstanceOf(ConflictException.class);
        assertThat(legacy.acquireOrEnqueue(identity,"waiting","MANUAL",null).state()).isEqualTo("QUEUED");
        assertThatThrownBy(()->legacy.releaseAfterWriterStopped(root,"waiting","no authority")).isInstanceOf(TaskFailure.class);
        var held=mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow();
        assertThatThrownBy(()->jdbc.update("UPDATE workspace_lease SET holder_task_id='waiting' WHERE canonical_root=?",identity.canonicalRoot()))
                .hasMessageContaining("incompatible owners");
        assertThatThrownBy(()->tx.executeWithoutResult(s->writers.releaseAfterStopped(identity,node,held.version())))
                .isInstanceOf(ConflictException.class);
        stop(node);
        var transfer=tx.execute(s->writers.releaseAfterStopped(identity,node,held.version()));
        assertThat(transfer.task().taskId()).isEqualTo("waiting");
    }
    @Test void queuedNodeCancellationPreservesTheHolderAndFifoSkipsOnlyTheCancelledNode() {
        task("holder"); task("next"); legacy.acquireOrEnqueue(identity,"holder","MANUAL",null);
        String cancelled=attempt(); admit(cancelled);
        tx.executeWithoutResult(s->writers.cancelQueued(cancelled));
        legacy.acquireOrEnqueue(identity,"next","MANUAL",null);
        assertThat(mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().holderTaskId()).isEqualTo("holder");
        assertThat(legacy.releaseAfterWriterStopped(root,"holder","stopped").admittedNext().taskId()).isEqualTo("next");
        assertThat(mapper.findWorkflowWriter(cancelled).orElseThrow().state()).isEqualTo("CANCELLED");
    }
    @Test void staleFingerprintOrLeaseVersionCannotTransferAndTheTransactionRollsBack() {
        String node=attempt(); var admitted=admit(node); task("waiting"); legacy.acquireOrEnqueue(identity,"waiting","MANUAL",null);
        stop(node); var held=mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow();
        var changed=new DirectWorkspaceLeaseCoordinator.WorkspaceIdentity(identity.canonicalRoot(),"different");
        assertThatThrownBy(()->tx.executeWithoutResult(s->writers.releaseAfterStopped(changed,node,held.version()))).isInstanceOf(TaskFailure.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->writers.releaseAfterStopped(identity,node,held.version()+1))).isInstanceOf(ConflictException.class);
        assertThat(mapper.findWorkflowWriter(node).orElseThrow().state()).isEqualTo(admitted.state());
        assertThat(mapper.findTaskQueue("waiting").orElseThrow().state()).isEqualTo("QUEUED");
        jdbc.execute("CREATE TRIGGER reject_transfer BEFORE UPDATE ON workspace_lease WHEN NEW.holder_task_id='waiting' BEGIN SELECT RAISE(ABORT,'test transfer failure'); END");
        assertThatThrownBy(()->tx.executeWithoutResult(s->writers.releaseAfterStopped(identity,node,held.version()))).isInstanceOf(RuntimeException.class);
        assertThat(mapper.findWorkflowWriter(node).orElseThrow().state()).isEqualTo("ADMITTED");
        assertThat(mapper.findTaskQueue("waiting").orElseThrow().state()).isEqualTo("QUEUED");
        assertThat(mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().holderWorkflowAttemptId()).isEqualTo(node);
    }
    @Test void concurrentLegacyAndNodeAdmissionCreatesOneWriterAndOneWaiter() throws Exception {
        task("legacy"); String node=attempt(); var barrier=new CyclicBarrier(2);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{barrier.await(); return legacy.acquireOrEnqueue(identity,"legacy","MANUAL",null).state();});
            var second=pool.submit(()->{barrier.await(); return admit(node).state();});
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("ADMITTED","QUEUED");
        }
        var held=mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow();
        assertThat((held.holderTaskId()==null)!=(held.holderWorkflowAttemptId()==null)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease WHERE state<>'RELEASED'",Integer.class)).isEqualTo(1);
        assertThat(mapper.findTaskQueue("legacy").orElseThrow().position()).isNotEqualTo(mapper.findWorkflowWriter(node).orElseThrow().position());
    }
    @Test void replayUsesTheOriginalQueueAndOverlapWithLegacySubdirectoryLeasesIsRejected() throws Exception {
        String node=attempt(); var first=admit(node); assertThat(admit(node)).isEqualTo(first);
        assertThatThrownBy(()->tx.executeWithoutResult(s->writers.cancelQueued(node))).isInstanceOf(ConflictException.class);
        Path child=Files.createDirectory(root.resolve("child"));
        String now=Instant.now().toString();
        String childProject=projects.create("子项目",child.toString(),"").id();
        mapper.insertTask(new TaskRow("child-task",childProject,null,"子任务","READY",child.toString(),"DIRECT",null,"direct:test",now,now,0,null,null,null,"LEGACY_AGGREGATE",null));
        assertThatThrownBy(()->legacy.acquireOrEnqueue(DirectWorkspaceLeaseCoordinator.identifyDirectory(child),"child-task","MANUAL",null))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("旧目录任务");
    }
    @Test void historicalNestedHolderBlocksNodeAdmissionButPrivateReportDirectoryDoesNot() throws Exception {
        Path child=Files.createDirectory(root.resolve("historical-child"));
        String childProject=projects.create("旧子目录项目",child.toString(),"").id();
        String created=Instant.now().toString();
        mapper.insertTask(new TaskRow("historical",childProject,null,"旧子目录任务","READY",child.toString(),"DIRECT",null,"direct:test",created,created,0,null,null,null,"LEGACY_AGGREGATE",null));
        legacy.acquireOrEnqueue(DirectWorkspaceLeaseCoordinator.identifyDirectory(child),"historical","MANUAL",null);
        String node=attempt();
        assertThatThrownBy(()->admit(node)).isInstanceOfSatisfying(ConflictException.class,
                failure->assertThat(failure.code()).isEqualTo("WORKSPACE_OVERLAPPING_LEASE"));
        assertThat(mapper.findWorkflowWriter(node)).isEmpty();
        legacy.releaseAfterWriterStopped(child,"historical","stopped");
        Path report=Files.createDirectories(root.resolve("data/template-tasks/report"));
        String now=Instant.now().toString();
        mapper.insertTask(new TaskRow("report",project,null,"报告","READY",report.toString(),"DIRECT",null,"direct:test",now,now,0,null,null,null,"TEMPLATE_REPORT",null));
        legacy.acquireOrEnqueue(DirectWorkspaceLeaseCoordinator.identifyDirectory(report),"report","MANUAL",null);
        assertThat(admit(node).state()).isEqualTo("ADMITTED");
        assertThat(legacy.requireWritableLease(report,"report").holderTaskId()).isEqualTo("report");
    }
    @Test void staleWaitingAttemptCannotBeAdmittedAndKeepsTheTransferAtomicUntilItsQueueIsCancelled() {
        task("holder"); legacy.acquireOrEnqueue(identity,"holder","MANUAL",null);
        String stopped=attempt(); admit(stopped); stop(stopped);
        assertThatThrownBy(()->legacy.releaseAfterWriterStopped(root,"holder","stopped"))
                .isInstanceOfSatisfying(TaskFailure.class,failure->assertThat(failure.code()).isEqualTo("WORKFLOW_WRITER_QUEUE_STALE"));
        assertThat(mapper.findTaskQueue("holder").orElseThrow().state()).isEqualTo("ADMITTED");
        assertThat(mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().holderTaskId()).isEqualTo("holder");
        tx.executeWithoutResult(s->writers.cancelQueued(stopped));
        assertThat(legacy.releaseAfterWriterStopped(root,"holder","stopped").releasedHolder().state()).isEqualTo("RELEASED");
    }
    private WorkflowWriterQueueRow admit(String id) { return tx.execute(s->writers.admit(identity,id)); }
    @Test void persistedStopProofBlocksPreparingWriterBeforeItsTerminalStateIsRecorded() {
        String held=attempt(); admit(held);
        proveStoppedWhilePreparing(held);
        assertThat(nodes.attempt(held).state()).isEqualTo("PREPARING");
        assertThatThrownBy(()->admit(held)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->writers.requireWritable(identity,held)).isInstanceOf(ConflictException.class);
        assertThat(mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().holderWorkflowAttemptId()).isEqualTo(held);
    }
    @Test void stoppedWaitingWriterCannotReceiveTheLeaseBeforeItsAttemptBecomesTerminal() {
        task("holder"); legacy.acquireOrEnqueue(identity,"holder","MANUAL",null);
        String waiting=attempt(); admit(waiting); proveStoppedWhilePreparing(waiting);
        assertThat(mapper.workflowWriterReady(waiting)).isFalse();
        assertThatThrownBy(()->legacy.releaseAfterWriterStopped(root,"holder","stopped"))
                .isInstanceOfSatisfying(TaskFailure.class,error->assertThat(error.code()).isEqualTo("WORKFLOW_WRITER_QUEUE_STALE"));
        assertThat(mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().holderTaskId()).isEqualTo("holder");
        assertThat(mapper.findWorkflowWriter(waiting).orElseThrow().state()).isEqualTo("QUEUED");
    }
    private void proveStoppedWhilePreparing(String id) {
        tx.executeWithoutResult(s->{
            execution.attach(id,nodes.attempt(id).version(),"session-"+id,Instant.now().toString());
            execution.insertStop(new WorkflowExecutionRows.Stop(id,"ABORT_CONFIRMED","session-"+id,"{\"abort\":true}",Instant.now().toString()));
        });
    }
    private String attempt() {
        var node=new Node("work","修改代码",NodeKind.WORK,"free.write",1,"builtin.implementation","实现指定目标",List.of(),
                List.of(new Output("result","成果",DataKind.TEXT,true)),List.of(),new Completion(CompletionKind.DELIVERABLES,"有效交付",null),1,false,Map.of());
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"开发节点","",new WorkflowGraph(1,List.of(node),List.of(),List.of()),CanvasLayout.empty()));
        var requirement=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求","目标",template.id(),1));
        plans.confirm(requirement.id(),new WorkflowRequests.VersionCommand(key(),requirement.version()));
        return tx.execute(s->{
            var admitted=actions.admit(requirement.id(),"work",new WorkflowNodeActions.Start(key(),plans.require(requirement.id()).version(),null));
            return nodes.begin(admitted.node(),admitted.owner().headRevision(),admitted.inputs(),WorkflowWriterLeases.ADAPTER,"{}").id();
        });
    }
    private void stop(String id) {
        tx.executeWithoutResult(s->{
            var attempt=nodes.attempt(id);
            execution.attach(id,attempt.version(),"session-"+id,Instant.now().toString());
            attempt=nodes.transition(nodes.attempt(id),WorkflowAttemptState.RUNNING,LifecycleEvent.START);
            attempt=nodes.transition(attempt,WorkflowAttemptState.STOPPING,LifecycleEvent.CANCEL);
            execution.insertStop(new WorkflowExecutionRows.Stop(id,"ABORT_CONFIRMED",attempt.externalSessionId(),"{\"abort\":true}",Instant.now().toString()));
            nodes.finish(attempt,WorkflowAttemptState.CANCELLED);
        });
    }
    private void task(String id) {
        String now=Instant.now().toString();
        mapper.insertTask(new TaskRow(id,project,null,id,"READY",root.toString(),"DIRECT",null,"direct:test",now,now,0,null,null,null,"LEGACY_AGGREGATE",null));
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static Path data() { try { return Files.createTempDirectory("workflow-writer-tests-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
}
