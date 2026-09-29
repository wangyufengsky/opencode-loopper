package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.*;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.monitor-delay=1h"})
class WorkflowDirectoryPreparationIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("loopper.data-dir",()->DATA.toString());
        p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("directory.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowDirectoryPreparation preparation;
    @Autowired WorkflowDirectoryStore store;
    @Autowired WorkflowDirectoryMapper mapper;
    @Autowired WorkflowWorkspaceStore workspaces;
    @Autowired WorkflowWriterLeases writers;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowNodeActions actions;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowModelStore models;
    @Autowired ProjectService projects;
    @Autowired LoopperMapper domain;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean WorkflowDirectoryFiles files;
    @MockitoSpyBean GitDirectoryTrees trees;
    @TempDir Path directory;
    Path root;
    String project,requirement,first,template;
    DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity;
    @BeforeEach void setup() throws Exception {
        flyway.clean();flyway.migrate();
        root=Files.createDirectory(directory.toRealPath().resolve("project"));
        Files.writeString(root.resolve("one.txt"),"one");Files.writeString(root.resolve("two.txt"),"two");
        project=projects.create("普通目录工作区",root.toString(),"").id();identity=DirectWorkspaceLeaseCoordinator.identify(root);
        var node=new Node("write","执行",NodeKind.WORK,"free.write",1,"builtin.implementation","实现目标",List.of(),
                List.of(new Output("code","代码",DataKind.CODE,true)),List.of(),new Completion(CompletionKind.DELIVERABLES,"有效交付",null),1,false,Map.of());
        template=templates.create(new WorkflowRequests.CreateTemplate(key(),"普通目录流程","",new WorkflowGraph(1,List.of(node),List.of(),List.of()),CanvasLayout.empty())).id();
        requirement=requirement();first=start(requirement);
    }
    @Test void reservedIdentityPrecedesPrivateIoAndCompleteBaselineReplaysWithoutReadingChangedSource() throws Exception {
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var row=mapper.find(first).orElseThrow();assertThat(row.manifestJson()).isNull();
            assertThat(call.<Path>getArgument(0).toString()).isEqualTo(row.objectRepository());
            return call.callRealMethod();
        }).when(trees).initialize(any());
        var prepared=preparation.prepare(workspaces.context(first));
        assertThat(prepared.baseTree()).matches("[0-9a-f]{40}");assertThat(prepared.sourceCommit()).matches("[0-9a-f]{40}");
        assertThat(prepared.canonicalRoot()).isEqualTo(root.toString());assertThat(prepared.inputsSha256()).isEqualTo(nodes.attempt(first).inputsSha256());
        assertThat(store.snapshot(prepared).files()).hasSize(2);assertThat(workspaces.find(first)).isEmpty();
        reset(trees);Files.writeString(root.resolve("one.txt"),"later manual edit");Files.delete(root.resolve("two.txt"));
        doThrow(new AssertionError("Already stored bytes must not re-read current files")).when(files).read(any(),any());
        assertThat(preparation.prepare(workspaces.context(first))).isEqualTo(prepared);
        var original=store.snapshot(prepared).files().stream().filter(file->file.path().equals("one.txt")).findFirst().orElseThrow();
        assertThat(new String(preparation.read(prepared,original))).isEqualTo("one");
        assertThat(Files.readString(root.resolve("one.txt"))).isEqualTo("later manual edit");
        assertThat(root.resolve(".git")).doesNotExist();assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isZero();
    }
    @Test void partialBodyCopyKeepsOriginalInventoryAndOnlyMissingMatchingBytesCanResume() throws Exception {
        var count=new AtomicInteger();
        doAnswer(call->{if(count.incrementAndGet()==2)throw new IllegalStateException("interrupted body copy");return call.callRealMethod();})
                .when(files).read(any(),any());
        assertThatThrownBy(()->preparation.prepare(workspaces.context(first))).hasMessage("interrupted body copy");
        var pending=mapper.find(first).orElseThrow();assertThat(pending.manifestJson()).isNotNull();assertThat(pending.baseTree()).isNull();
        Files.writeString(root.resolve("one.txt"),"later first");Files.writeString(root.resolve("two.txt"),"later second");
        assertThatThrownBy(()->preparation.prepare(workspaces.context(first))).isInstanceOf(ConflictException.class);
        assertThat(mapper.find(first).orElseThrow()).isEqualTo(pending);
        Files.writeString(root.resolve("two.txt"),"two");
        var recovered=preparation.prepare(workspaces.context(first));
        assertThat(recovered.manifestJson()).isEqualTo(pending.manifestJson());assertThat(recovered.manifestSha256()).isEqualTo(pending.manifestSha256());
        assertThat(recovered.baseTree()).isNotNull();assertThat(Files.readString(root.resolve("one.txt"))).isEqualTo("later first");
        assertThat(domain.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().holderWorkflowAttemptId()).isEqualTo(first);
    }
    @Test void lostObjectAcknowledgementReusesTheOriginalCommitWithoutRecapturing() throws Exception {
        var once=new AtomicBoolean(true);var commit=new AtomicReference<String>();
        doAnswer(call->{String value=(String)call.callRealMethod();commit.set(value);if(once.getAndSet(false))throw new IllegalStateException("lost object acknowledgement");return value;})
                .when(trees).commit(any(),anyString(),anyString(),anyString());
        assertThatThrownBy(()->preparation.prepare(workspaces.context(first))).hasMessage("lost object acknowledgement");
        var pending=mapper.find(first).orElseThrow();String originalCommit=commit.get();assertThat(pending.baseTree()).isNull();
        Files.writeString(root.resolve("one.txt"),"later");
        var recovered=preparation.prepare(workspaces.context(first));
        assertThat(recovered.sourceCommit()).isEqualTo(originalCommit);assertThat(recovered.manifestJson()).isEqualTo(pending.manifestJson());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_directory_preparation",Integer.class)).isEqualTo(1);
    }
    @Test void queuedAttemptCannotReserveOrCreatePrivateObjects() {
        String queued=start(requirement());
        assertThat(domain.findWorkflowWriter(queued).orElseThrow().state()).isEqualTo("QUEUED");
        assertThatThrownBy(()->preparation.prepare(workspaces.context(queued))).isInstanceOf(ConflictException.class);
        assertThat(mapper.find(queued)).isEmpty();assertThat(DATA.resolve("workflow-directory").resolve(queued)).doesNotExist();
    }
    @Test void stoppingDuringCopyCannotPublishBaselineOrReleaseTheWriter() {
        tx.executeWithoutResult(s->models.create(nodes.attempt(first),plans.require(requirement),root,new OpenCodeClient.OpenCodeModel("fake","test",false)));
        doAnswer(call->{Object result=call.callRealMethod();models.stop(first);return result;}).when(trees).commit(any(),anyString(),anyString(),anyString());
        assertThatThrownBy(()->preparation.prepare(workspaces.context(first))).isInstanceOf(ConflictException.class);
        assertThat(mapper.find(first).orElseThrow().baseTree()).isNull();assertThat(nodes.attempt(first).state()).isEqualTo("STOPPING");
        assertThat(domain.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().holderWorkflowAttemptId()).isEqualTo(first);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_directory_preparation SET base_tree=?,source_commit=? WHERE attempt_id=?","a".repeat(40),"b".repeat(40),first))
                .hasMessageContaining("no longer active");
    }
    @Test void unconfirmedLeaseAfterInventoryCannotAcceptLateDiscovery() {
        doAnswer(call->{Object result=call.callRealMethod();tx.executeWithoutResult(s->writers.markUnconfirmed(identity,first));return result;})
                .when(files).discover(any(),any(),any(),anyList());
        assertThatThrownBy(()->preparation.prepare(workspaces.context(first))).isInstanceOf(ConflictException.class);
        var row=mapper.find(first).orElseThrow();assertThat(row.manifestJson()).isNull();assertThat(row.baseTree()).isNull();
        assertThat(domain.findWorkspaceLease(identity.canonicalRoot()).orElseThrow().state()).isEqualTo("RELEASE_PENDING");
    }
    @Test void corruptStoredObjectNeverFallsBackToMatchingCurrentFile() throws Exception {
        var row=preparation.prepare(workspaces.context(first));var original=store.snapshot(row).files().getFirst();
        Path object=DATA.resolve("workflow-directory").resolve(first).resolve("objects").resolve(original.sha256());
        Files.writeString(object,"corrupt");
        assertThatThrownBy(()->preparation.prepare(workspaces.context(first))).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThatThrownBy(()->preparation.read(row,original)).isInstanceOf(ImmutableContentStore.StorageFailure.class);
        assertThat(Files.readString(object)).isEqualTo("corrupt");assertThat(mapper.find(first).orElseThrow()).isEqualTo(row);
    }
    @Test void immutableIdentityInventoryAndBaselineCannotBeChangedOrDeleted() {
        var context=workspaces.context(first);var row=preparation.prepare(context);
        for(String column:List.of("attempt_id","requirement_id","project_id","canonical_root","root_fingerprint","object_repository","inputs_sha256","created_at")) {
            assertThatThrownBy(()->jdbc.update("UPDATE workflow_directory_preparation SET "+column+"=? WHERE attempt_id=?","replacement",first))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("workflow directory");
            assertThat(mapper.find(first).orElseThrow()).isEqualTo(row);
        }
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_directory_preparation SET manifest_json=manifest_json WHERE attempt_id=?",first)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_directory_preparation SET base_tree=base_tree WHERE attempt_id=?",first)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_directory_preparation WHERE attempt_id=?",first)).hasMessageContaining("retained");
        var altered=new WorkflowDirectorySnapshot(1,row.canonicalRoot(),row.rootFingerprint(),List.of());
        assertThatThrownBy(()->store.manifest(context,row,altered)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->store.ready(context,row,"a".repeat(40),"b".repeat(40))).isInstanceOf(ConflictException.class);
        assertThat(mapper.find(first).orElseThrow()).isEqualTo(row);
        var stale=workspaces.context(first);jdbc.update("UPDATE project SET version=version+1 WHERE id=?",project);
        assertThatThrownBy(()->preparation.prepare(stale)).isInstanceOf(ConflictException.class);
    }
    @Test void databaseRejectsQueuedOrWrongScopePreparationEvenWhenApplicationChecksAreBypassed() {
        var row=preparation.prepare(workspaces.context(first));String waitingOwner=requirement(), waiting=start(waitingOwner);
        String insert="INSERT INTO workflow_directory_preparation(attempt_id,requirement_id,project_id,canonical_root,root_fingerprint,object_repository,inputs_sha256,created_at) VALUES(?,?,?,?,?,?,?,?)";
        assertThatThrownBy(()->jdbc.update(insert,waiting,waitingOwner,project,row.canonicalRoot(),row.rootFingerprint(),"/private/waiting",nodes.attempt(waiting).inputsSha256(),row.createdAt()))
                .hasMessageContaining("owner mismatch");
        assertThatThrownBy(()->jdbc.update(insert,first,waitingOwner,project,row.canonicalRoot(),row.rootFingerprint(),"/private/wrong-owner",row.inputsSha256(),row.createdAt()))
                .hasMessageContaining("owner mismatch");
        assertThat(mapper.find(waiting)).isEmpty();assertThat(mapper.find(first).orElseThrow()).isEqualTo(row);
    }
    private String requirement() {
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求","普通目录写入",template,1));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private String start(String owner) {
        return tx.execute(s->{var admitted=actions.admit(owner,"write",new WorkflowNodeActions.Start(key(),plans.require(owner).version(),null));
            String attempt=nodes.begin(admitted.node(),admitted.owner().headRevision(),admitted.inputs(),WorkflowWriterLeases.ADAPTER,"{}").id();
            writers.admit(identity,attempt);return attempt;});
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static Path data() { try{return Files.createTempDirectory("workflow-directory-").toRealPath();}catch(Exception e){throw new ExceptionInInitializerError(e);} }
}
