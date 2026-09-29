package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.roles.RolePublishingService;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.monitor-delay=1h","loopper.workflow-monitor-enabled=false"})
class WorkflowWriterExecutionIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("loopper.data-dir",()->DATA.toString());
        p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("writers.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowBuiltinFlows builtinFlows;
    @Autowired WorkflowNodePresets nodePresets;
    @Autowired WorkflowRunReads runReads;
    @Autowired WorkflowNodeActions nodeActions;
    @Autowired WorkflowVerificationStore verificationStore;
    @Autowired WorkflowVerificationExecution verificationExecution;
    @Autowired WorkflowCommandWorkspace commandWorkspace;
    @Autowired WorkflowCommandStore commandStore;
    @Autowired WorkflowCommandExecution commandExecution;
    @Autowired WorkflowCommandActions commandActions;
    @Autowired DurableCommands durableCommands;
    @Autowired WorkflowExecutionMapper executionRows;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowFinishes finishes;
    @Autowired WorkflowFinishDrain finishDrain;
    @Autowired WorkflowModelAdmission admission;
    @Autowired WorkflowModelExecution execution;
    @Autowired WorkflowModelStore models;
    @Autowired WorkflowModelMapper modelMapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired WorkflowModelActions controls;
    @Autowired WorkflowControls runControls;
    @Autowired WorkflowDispatchExecution dispatch;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowModelTools tools;
    @Autowired WorkflowRuntimeSupport scopes;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired OpenCodeClient client;
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired LoopDraftService drafts;
    @Autowired RolePublishingService roles;
    @Autowired RoleConfigurationMapper roleMapper;
    @Autowired LoopperMapper mapper;
    @Autowired WorkflowCodeSnapshots codes;
    @Autowired WorkflowCodeFiles files;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean WorkflowWorkspaceStore workspaceStore;
    @MockitoSpyBean WorkflowGitWorkspace workspaceGit;
    @MockitoSpyBean WorkflowDirectoryStorage directoryStorage;
    @TempDir Path directory;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    FakeOpenCodeClient fake;
    Path root;
    String project,revision;
    @BeforeEach void prepare() throws Exception {
        flyway.clean();flyway.migrate();roles.seedBuiltin();
        fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();
        access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_WRITE,true);
        root=Files.createDirectory(directory.resolve("project"));
        command("init","--quiet","--initial-branch=main");Files.writeString(root.resolve("code.txt"),"baseline");
        command("add",".");command("-c","user.name=Test","-c","user.email=test@example.invalid","commit","-qm","baseline");
        project=projects.create("可写流程",root.toString(),"").id();
        revision=roleMapper.latest("builtin.implementation").revisionId();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void formalStartAndMcpProducePinnedCodeThenTheNextWriterInheritsItWithoutHiddenJudges(boolean plain) throws Exception {
        if(plain)plainProject();
        String id=requirement(true),first=start(id,"work");running(first);
        if(plain)assertThat(workspaceStore.require(first).objectRepository()).isNotNull();
        assertThat(models.plan(models.require(first)).profile()).isEqualTo(OpenCodeClient.SessionProfile.WORKFLOW_WRITE);
        assertThat(models.prompt(models.require(first)).system()).contains("本次为可写工作").doesNotContain("本次为只读工作");
        Files.writeString(root.resolve("code.txt"),"first");Files.writeString(root.resolve("new.txt"),"added");
        var submission=submission(first);var receipt=(WorkflowModelTools.Accepted)call(first,submission);
        assertThat(receipt.status()).isEqualTo("PENDING_CAPTURE");
        assertThat(nodes.findDelivery(first)).isEmpty();execution.advance(first);
        assertThat(models.require(first).state()).isEqualTo("RUNNING");
        assertThatThrownBy(()->start(id,"next")).isInstanceOf(ConflictException.class);
        finish(first);assertThat(models.require(first).state()).isEqualTo("SUCCEEDED");
        assertThat(workspaceStore.require(first).state()).isEqualTo("RELEASED");
        if(plain)assertThat(root.resolve(".git")).doesNotExist();else assertThat(command("branch","--show-current").strip()).isEqualTo("main");
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("baseline");
        assertThat(call(first,submission)).isEqualTo(receipt);
        String second=start(id,"next");running(second);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("first");
        assertThat(Files.readString(root.resolve("new.txt"))).isEqualTo("added");
        Files.writeString(root.resolve("code.txt"),"second");call(second,submission(second));finish(second);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(read(first,id,"code.txt")).isEqualTo("first");assertThat(read(second,id,"code.txt")).isEqualTo("second");
        if(plain){assertThat(root.resolve(".git")).doesNotExist();assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("baseline");}
        else assertThat(command("status","--porcelain")).isEmpty();
        for (String table:List.of("task","task_queue","judge_run")) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void controlledWriterCheckpointCommitsWithDeliveryAndLeaseReleaseBeforeTheNextWriterCanStart(boolean plain) throws Exception {
        if(plain)plainProject();
        String id=requirement(true,true);
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),-1,WorkflowDispatch.Mode.CONTINUOUS,null,null,
                new OpenCodeClient.OpenCodeModel("fake","test",false),List.of()));
        dispatch.advance(id);String first=nodes.node(id,1,"work").latestAttemptId();running(first);
        Files.writeString(root.resolve("code.txt"),"checkpoint code");call(first,submission(first));
        var once=new AtomicBoolean(true);
        doAnswer(invocation->{var value=invocation.callRealMethod();if(once.getAndSet(false))throw new IllegalStateException("lost checkpoint commit");return value;})
                .when(workspaceStore).release(any(),any(),anyLong());
        assertThatThrownBy(()->finish(first)).hasMessage("lost checkpoint commit");
        assertThat(runControls.get(id).checkpoints()).isEmpty();assertThat(nodes.findDelivery(first)).isEmpty();
        dispatch.advance(id);assertThat(nodes.node(id,1,"next").latestAttemptId()).isNull();
        execution.advance(first);
        assertThat(workspaceStore.require(first).state()).isEqualTo("RELEASED");
        assertThat(runControls.get(id).state()).isEqualTo(WorkflowControlState.WAITING);
        assertThat(runControls.get(id).checkpoints()).extracting(row->row.attemptId()).containsExactly(first);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("baseline");
        dispatch.advance(id);assertThat(nodes.node(id,1,"next").latestAttemptId()).isNull();
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),runControls.get(id).controlVersion(),
                WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of(first)));
        dispatch.advance(id);String second=nodes.node(id,1,"next").latestAttemptId();running(second);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("checkpoint code");
        Files.writeString(root.resolve("code.txt"),"next code");call(second,submission(second));finish(second);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(workspaceStore.require(second).state()).isEqualTo("RELEASED");
        assertThat(read(first,id,"code.txt")).isEqualTo("checkpoint code");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM judge_run",Integer.class)).isZero();
    }
    @Test void bundledFileFlowCapturesCodeAndHandsPinnedInputsToHumanConfirmation() throws Exception {
        builtinFlows.publish();var template=templates.get("builtin.workflow.file-work",null);
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"按预设开发","完成文件修改",template.id(),template.revision()));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));String id=owner.id();
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),-1,WorkflowDispatch.Mode.CONTINUOUS,null,null,
                new OpenCodeClient.OpenCodeModel("fake","test",false),List.of()));
        dispatch.advance(id);String first=nodes.node(id,1,"develop").latestAttemptId();running(first);
        Files.writeString(root.resolve("code.txt"),"preset result");
        call(first,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(first).version(),"delivery",
                new WorkflowDelivery("文件工作完成",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("已更新文件")),
                        "checks",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("未运行程序验证，由人工检查"))))));
        finish(first);dispatch.advance(id);String human=nodes.node(id,1,"check").latestAttemptId();
        assertThat(nodes.attempt(human).state()).isEqualTo("WAITING_INPUT");
        assertThat(nodes.inputs(nodes.attempt(human)).values()).hasSize(3).allSatisfy(input->assertThat(input.attemptId()).isEqualTo(first));
        assertThat(new String(files.bytes(files.input(id,"check",human,"code"),"code.txt"),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("preset result");
        assertThat(command("branch","--show-current").strip()).isEqualTo("main");assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("baseline");
        nodeActions.completeHuman(id,"check",new WorkflowNodeActions.Complete(key(),plans.require(id).version(),human,nodes.attempt(human).version(),
                new WorkflowDelivery("已检查",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("人工确认文件"))))));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM judge_run",Integer.class)).isZero();
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void queuedCancellationNeverCreatesSessionOrTouchesTheActiveWritersFiles(boolean plain) throws Exception {
        if(plain)plainProject();
        String first=start(requirement(false),"work");running(first);Files.writeString(root.resolve("code.txt"),"still writing");
        String queued=start(requirement(false),"work");
        execution.advance(queued);assertThat(mapper.findWorkflowWriter(queued).orElseThrow().state()).isEqualTo("QUEUED");
        assertThat(workspaceStore.find(queued)).isEmpty();assertThat(models.require(queued).creationPlanJson()).isNull();
        int creates=fake.createSessionCalls()+fake.createReadOnlySessionCalls();
        stop(queued);execution.advance(queued);
        assertThat(models.require(queued).state()).isEqualTo("CANCELLED");
        assertThat(mapper.findWorkflowWriter(queued).orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("still writing");
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isEqualTo(creates);
        call(first,submission(first));finish(first);
        assertThat(mapper.findWorkspaceLease(DirectWorkspaceLeaseCoordinator.identify(root).canonicalRoot()).orElseThrow().state()).isEqualTo("RELEASED");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void admittedCancellationBeforeWorkspacePreparationClosesWithoutGitEffects(boolean plain)throws Exception {
        if(plain)plainProject();
        String attempt=start(requirement(false),"work");stop(attempt);execution.advance(attempt);
        assertThat(models.require(attempt).state()).isEqualTo("CANCELLED");assertThat(workspaceStore.find(attempt)).isEmpty();
        if(plain)assertThat(root.resolve(".git")).doesNotExist();else assertThat(command("branch","--show-current").strip()).isEqualTo("main");
        assertThat(mapper.findWorkflowWriter(attempt).orElseThrow().state()).isEqualTo("FINISHED");
    }
    @Test void cancelledPlainDirectoryPreparationRetainsEvidenceAndDoesNotRestoreOverLaterUserEdits()throws Exception {
        plainProject();String attempt=start(requirement(false),"work");
        doThrow(new IllegalStateException("baseline copy interrupted")).when(directoryStorage).importFiles(eq(attempt),any(),any());
        assertThatThrownBy(()->execution.advance(attempt)).hasMessage("baseline copy interrupted");
        assertThat(jdbc.queryForObject("SELECT manifest_json FROM workflow_directory_preparation WHERE attempt_id=?",String.class,attempt)).isNotBlank();
        assertThat(workspaceStore.find(attempt)).isEmpty();assertThat(models.require(attempt).creationPlanJson()).isNull();
        Files.writeString(root.resolve("code.txt"),"later user edit");stop(attempt);execution.advance(attempt);
        assertThat(models.require(attempt).state()).isEqualTo("CANCELLED");assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("later user edit");
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isZero();
        assertThat(mapper.findWorkflowWriter(attempt).orElseThrow().state()).isEqualTo("FINISHED");
        assertThat(mapper.findWorkspaceLease(DirectWorkspaceLeaseCoordinator.identify(root).canonicalRoot()).orElseThrow().state()).isEqualTo("RELEASED");
        assertThat(root.resolve(".git")).doesNotExist();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownStopRetainsLeaseThenConfirmedCancellationPreservesCheckpointAndRestoresSource(boolean plain) throws Exception {
        if(plain)plainProject();
        String first=start(requirement(false),"work");running(first);Files.writeString(root.resolve("code.txt"),"partial");
        String queued=start(requirement(false),"work");stop(first);
        fake.failNextAborts(1);
        assertThatThrownBy(()->execution.advance(first)).isInstanceOf(RuntimeException.class);
        assertThat(mapper.findWorkspaceLease(DirectWorkspaceLeaseCoordinator.identify(root).canonicalRoot()).orElseThrow().state()).isEqualTo("RELEASE_PENDING");
        execution.advance(queued);assertThat(models.require(queued).creationPlanJson()).isNull();
        fake.failNextAborts(0);execution.advance(first);
        assertThat(models.require(first).state()).isEqualTo("CANCELLED");assertThat(nodes.findDelivery(first)).isEmpty();
        assertThat(workspaceStore.require(first).checkpointTree()).isNotNull();
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("baseline");
        assertThat(mapper.findWorkflowWriter(queued).orElseThrow().state()).isEqualTo("ADMITTED");running(queued);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void finalizationRollbackKeepsSuccessInvisibleAndResumesWithoutProviderCalls(boolean plain) throws Exception {
        if(plain)plainProject();
        String id=requirement(false),attempt=start(id,"work");running(attempt);Files.writeString(root.resolve("code.txt"),"saved");call(attempt,submission(attempt));
        var once=new AtomicBoolean(true);
        doAnswer(invocation->{var value=invocation.callRealMethod();if(once.getAndSet(false))throw new IllegalStateException("lost final transaction");return value;})
                .when(workspaceStore).release(any(),any(),anyLong());
        assertThatThrownBy(()->finish(attempt)).hasMessage("lost final transaction");
        assertThat(models.stopProof(attempt)).isPresent();assertThat(models.require(attempt).state()).isEqualTo("RUNNING");
        assertThat(nodes.findDelivery(attempt)).isEmpty();assertThat(workspaceStore.require(attempt).state()).isEqualTo("RESTORED");
        assertThat(mapper.findWorkflowWriter(attempt).orElseThrow().state()).isEqualTo("ADMITTED");
        int prompts=fake.promptCalls();execution.advance(attempt);
        assertThat(fake.promptCalls()).isEqualTo(prompts);assertThat(models.require(attempt).state()).isEqualTo("SUCCEEDED");
        assertThat(read(attempt,id,"code.txt")).isEqualTo("saved");
    }
    @Test void forgedCodeIsCorrectableAndMissingCandidateFailsWithoutDiscardingFiles() throws Exception {
        String attempt=start(requirement(false),"work");running(attempt);
        var forged=Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",
                new WorkflowDelivery("伪造代码",null,Map.of("code",new WorkflowDelivery.Value(DataKind.CODE,json.valueToTree(Map.of("path",root.toString()))))));
        assertThatThrownBy(()->call(attempt,forged)).isInstanceOf(BadRequestException.class);
        Files.writeString(root.resolve("code.txt"),"unfinished");finish(attempt);
        assertThat(models.require(attempt).state()).isEqualTo("FAILED");assertThat(nodes.findDelivery(attempt)).isEmpty();
        var snapshot=workspaceStore.require(attempt);assertThat(snapshot.checkpointTree()).isNotNull();
        assertThat(command("show",snapshot.checkpointCommit()+":code.txt")).isEqualTo("unfinished");
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("baseline");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void lostCreateAndPromptAcknowledgementsResumeTheSameWriterEvenAfterItEditedFiles(boolean plain) throws Exception {
        if(plain)plainProject();
        String id=requirement(false),attempt=start(id,"work");execution.advance(attempt);
        var creating=models.require(attempt);var remote=fake.createSession(models.plan(creating));
        execution.advance(attempt);assertThat(nodes.attempt(attempt).externalSessionId()).isEqualTo(remote.remoteId());
        var dispatch=models.require(attempt);fake.promptAsync(remote.session(),models.prompt(dispatch));
        Files.writeString(root.resolve("code.txt"),"already sent");
        execution.advance(attempt);assertThat(fake.promptCalls()).isEqualTo(1);
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isEqualTo(1);
        call(attempt,submission(attempt));finish(attempt);assertThat(read(attempt,id,"code.txt")).isEqualTo("already sent");
    }
    @Test void savedStopProofRecoversAfterCaptureAcknowledgementLossWithoutAnotherSession() throws Exception {
        String id=requirement(false),attempt=start(id,"work");running(attempt);Files.writeString(root.resolve("code.txt"),"frozen");call(attempt,submission(attempt));
        var once=new AtomicBoolean(true);
        doAnswer(invocation->{var result=invocation.callRealMethod();if(once.getAndSet(false))throw new IllegalStateException("capture acknowledgement lost");return result;})
                .when(workspaceGit).capture(any(),anyString(),anyString());
        assertThatThrownBy(()->finish(attempt)).hasMessage("capture acknowledgement lost");
        assertThat(models.stopProof(attempt)).isPresent();assertThat(workspaceStore.require(attempt).state()).isEqualTo("CAPTURING");
        models.suspend(attempt,"WORKFLOW_CAPTURE_INTERRUPTED");var paused=models.require(attempt);
        controls.resume(id,"work",attempt,new WorkflowModelActions.Command(key(),paused.version()));
        execution.advance(attempt);assertThat(read(attempt,id,"code.txt")).isEqualTo("frozen");
        assertThat(fake.promptCalls()).isEqualTo(1);
    }
    @Test void candidateAndReceiptRollbackTogetherAndTheSavedCandidateIsImmutable() {
        String attempt=start(requirement(false),"work");running(attempt);var submission=submission(attempt);
        jdbc.execute("CREATE TRIGGER reject_writer_receipt BEFORE INSERT ON workflow_command WHEN NEW.action='MODEL_SUBMIT' BEGIN SELECT RAISE(ABORT,'test receipt failure'); END");
        assertThatThrownBy(()->call(attempt,submission)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_writer_candidate",Integer.class)).isZero();
        jdbc.execute("DROP TRIGGER reject_writer_receipt");var accepted=call(attempt,submission);
        assertThat(call(attempt,submission)).isEqualTo(accepted);
        assertThatThrownBy(()->jdbc.update("UPDATE workflow_writer_candidate SET content_json='{}' WHERE attempt_id=?",attempt)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM workflow_writer_candidate WHERE attempt_id=?",attempt)).isInstanceOf(RuntimeException.class);
    }
    @Test void workflowReleaseActuallyStartsTheWaitingLegacyTask() throws Exception {
        var spec=new io.opencode.loopper.domain.LoopSpec("v1",project,"Verify code",null,
                List.of(new io.opencode.loopper.domain.LoopSpec.StageSpec("Verify code",null,null,null,
                        List.of(new io.opencode.loopper.domain.LoopSpec.VerifierSpec("FILE_EXISTS",null,"code.txt",null,null,null,null)))),null,null,null,null);
        var legacy=drafts.confirm(drafts.create(spec).id(),"等待中的旧任务");
        String attempt=start(requirement(false),"work");running(attempt);
        assertThat(tasks.start(legacy.id()).state()).isEqualTo("QUEUED");
        Files.writeString(root.resolve("code.txt"),"workflow code");call(attempt,submission(attempt));finish(attempt);
        assertThat(models.require(attempt).state()).isEqualTo("SUCCEEDED");
        assertThat(tasks.get(legacy.id()).state()).isEqualTo("RUNNING");
        assertThat(mapper.findWorkspaceLease(DirectWorkspaceLeaseCoordinator.identify(root).canonicalRoot()).orElseThrow().holderTaskId()).isEqualTo(legacy.id());
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("baseline");
        assertThat(workspaceStore.released(attempt).task().taskId()).isEqualTo(legacy.id());
    }
    @Test void pollingOutsideTransactionsSeesStateCommittedByAnInnerTransaction() {
        String attempt=start(requirement(false),"work");
        var outside=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        outside.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        outside.executeWithoutResult(ignored->{
            var before=modelMapper.find(attempt).orElseThrow();
            models.suspend(attempt,"WORKFLOW_TEST_RECOVERY");
            var after=modelMapper.find(attempt).orElseThrow();
            assertThat(after.suspended()).isTrue();assertThat(after.version()).isGreaterThan(before.version());
        });
    }
    @Test void downstreamReadsPinnedFilesByNameAfterCheckoutChangesAndRejectsForeignBindings() throws Exception {
        String id=requirement(true),first=start(id,"work");running(first);
        Files.writeString(root.resolve("code.txt"),"甲😀乙");Files.write(root.resolve("binary.dat"),new byte[]{0,(byte)255,42});
        call(first,submission(first));finish(first);String second=start(id,"next");running(second);
        Files.writeString(root.resolve("code.txt"),"mutable checkout");
        var page=json.valueToTree(fileCall(second,WorkflowModelProfile.FILES,Map.of("name","workspace","limit",1)));
        assertThat(page.path("items").get(0).path("path").asText()).isEqualTo("binary.dat");
        var next=json.valueToTree(fileCall(second,WorkflowModelProfile.FILES,Map.of("name","workspace","limit",1,"cursor",page.path("nextCursor").asText())));
        assertThat(next.path("items").get(0).path("path").asText()).isEqualTo("code.txt");
        var part=(WorkflowCodeFiles.Text)fileCall(second,WorkflowModelProfile.FILE,Map.of("name","workspace","path","code.txt","offset",1,"limit",1));
        assertThat(part.text()).isEqualTo("😀");assertThat(part.nextOffset()).isEqualTo(3);
        assertThatThrownBy(()->fileCall(second,WorkflowModelProfile.FILE,Map.of("name","workspace","path","code.txt","offset",2))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->fileCall(second,WorkflowModelProfile.FILE,Map.of("name","workspace","path","binary.dat"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->fileCall(second,WorkflowModelProfile.FILE,Map.of("name","unknown","path","code.txt"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->fileCall(second,WorkflowModelProfile.FILE,Map.of("name","workspace","path","../code.txt"))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->fileCall(second,WorkflowModelProfile.FILES,Map.of("name","workspace","producer",first))).isInstanceOf(BadRequestException.class);
        var output=files.output(id,"work",first,"code");
        assertThat(files.bytes(output,"binary.dat")).containsExactly(0,(byte)255,42);
        assertThatThrownBy(()->files.input(requirement(false),"next",second,"workspace")).isInstanceOf(NotFoundException.class);
        String wrong=new PageCursor("f".repeat(64),"binary.dat").encode();
        assertThatThrownBy(()->fileCall(second,WorkflowModelProfile.FILES,Map.of("name","workspace","cursor",wrong))).isInstanceOf(BadRequestException.class);
    }
    @Test void programChecksReadFixedCodeWithoutAModelOrLeaseAndKeepTheirReportAsTheNextInput() throws Exception {
        String id=verificationRequirement(CompletionKind.VERIFIED,0,"frozen\n",false),writer=start(id,"work");running(writer);
        Files.writeString(root.resolve("code.txt"),"frozen\n");call(writer,submission(writer));finish(writer);
        Files.writeString(root.resolve("code.txt"),"later user changes");int calls=fake.promptCalls();
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),-1,WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of()));
        dispatch.advance(id);String check=nodes.node(id,1,"verify").latestAttemptId();assertThat(nodes.attempt(check).roleSnapshotJson()).isNull();
        assertThat(executionRows.activeVerifications("",20)).containsExactly(check);
        verificationExecution.advance(check);verificationExecution.advance(check);
        assertThat(nodes.attempt(check).state()).isEqualTo("SUCCEEDED");assertThat(executionRows.stop(check).orElseThrow().kind()).isEqualTo("NO_EXTERNAL_WORK");
        var delivery=models.encoding().decode(nodes.delivery(check).contentJson(),WorkflowDelivery.class);
        assertThat(delivery.outcome()).isEqualTo("PASS");assertThat(delivery.outputs().get("report").content().path("checks").size()).isEqualTo(3);
        assertThat(delivery.outputs().get("report").content().path("producerAttempt").asText()).isEqualTo(writer);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("later user changes");assertThat(fake.promptCalls()).isEqualTo(calls);
        assertThat(modelMapper.find(check)).isEmpty();assertThat(mapper.findWorkflowWriter(check)).isEmpty();
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void failedRequiredChecksRetryFromTheSameInputThenStallWithReportsRetained() throws Exception {
        String id=verificationRequirement(CompletionKind.VERIFIED,1,"expected",false),writer=start(id,"work");running(writer);call(writer,submission(writer));finish(writer);
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),-1,WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of()));
        dispatch.advance(id);String first=nodes.node(id,1,"verify").latestAttemptId();verificationExecution.advance(first);
        assertThat(nodes.attempt(first).state()).isEqualTo("FAILED");assertThat(nodes.delivery(first).outcome()).isEqualTo("FAIL");
        dispatch.advance(id);String second=nodes.node(id,1,"verify").latestAttemptId();assertThat(second).isNotEqualTo(first);
        assertThat(nodes.inputs(nodes.attempt(second))).isEqualTo(nodes.inputs(nodes.attempt(first)));verificationExecution.advance(second);dispatch.advance(id);
        assertThat(nodes.node(id,1,"verify").attemptCount()).isEqualTo(2);assertThat(runControls.get(id).state()).isEqualTo(WorkflowControlState.STALLED);
        assertThat(nodes.findDelivery(first)).isPresent();assertThat(nodes.findDelivery(second)).isPresent();
    }
    @Test void businessCheckFailureCanSelectTheUsersFailureBranchWithoutInventingSuccess() throws Exception {
        String id=verificationRequirement(CompletionKind.DELIVERABLES,0,"expected",true),writer=start(id,"work");running(writer);call(writer,submission(writer));finish(writer);
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),-1,WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of()));
        dispatch.advance(id);String check=nodes.node(id,1,"verify").latestAttemptId();verificationExecution.advance(check);dispatch.advance(id);
        assertThat(nodes.attempt(check).state()).isEqualTo("SUCCEEDED");assertThat(nodes.delivery(check).outcome()).isEqualTo("FAIL");
        String human=nodes.node(id,1,"repair").latestAttemptId();assertThat(nodes.attempt(human).state()).isEqualTo("WAITING_INPUT");
        assertThat(nodes.inputs(nodes.attempt(human)).values().getFirst().attemptId()).isEqualTo(check);
        assertThat(nodes.node(id,1,"accepted").state()).isEqualTo("SKIPPED");
    }
    @Test void verificationFinalTransactionCanRecoverWithoutRecreatingAttemptsOrErasingTheInput() throws Exception {
        String id=verificationRequirement(CompletionKind.VERIFIED,0,"baseline",false),writer=start(id,"work");running(writer);call(writer,submission(writer));finish(writer);
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),-1,WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of()));
        dispatch.advance(id);String check=nodes.node(id,1,"verify").latestAttemptId();
        jdbc.execute("CREATE TRIGGER fail_file_proof BEFORE INSERT ON workflow_attempt_stop WHEN NEW.attempt_id='"+check+"' BEGIN SELECT RAISE(ABORT,'verification rollback'); END");
        assertThatThrownBy(()->verificationExecution.advance(check)).isInstanceOf(RuntimeException.class);
        assertThat(nodes.attempt(check).state()).isEqualTo("RUNNING");assertThat(nodes.findDelivery(check)).isEmpty();assertThat(executionRows.stop(check)).isEmpty();
        jdbc.execute("DROP TRIGGER fail_file_proof");verificationExecution.advance(check);
        assertThat(nodes.attempt(check).state()).isEqualTo("SUCCEEDED");assertThat(nodes.node(id,1,"verify").attemptCount()).isEqualTo(1);
    }
    @Test void damagedFixedCodeCannotBeAcceptedEvenWhenTheCurrentCheckoutMatches() throws Exception {
        String id=verificationRequirement(CompletionKind.DELIVERABLES,0,"baseline",false),writer=start(id,"work");running(writer);call(writer,submission(writer));finish(writer);
        var result=models.encoding().decode(nodes.delivery(writer).contentJson(),WorkflowDelivery.class);
        var reference=models.encoding().decode(models.encoding().encode(result.outputs().get("code").content()),WorkflowCodeSnapshot.Reference.class);
        String hash=codes.manifest(project,id,writer,reference).files().getFirst().sha256();
        Files.writeString(DATA.resolve("workflow-code").resolve(reference.snapshotId()).resolve("objects").resolve(hash),"damaged");
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),-1,WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of()));
        dispatch.advance(id);String check=nodes.node(id,1,"verify").latestAttemptId();verificationExecution.advance(check);
        assertThat(nodes.attempt(check).state()).isEqualTo("FAILED");assertThat(nodes.delivery(check).contentJson()).contains("ERROR");
        assertThat(plans.require(id).state()).isEqualTo("STALLED");assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("baseline");
    }
    @Test void invalidProgramContractsAndAHiddenRoleAreRejectedBeforeCreatingAnAttempt() {
        var node=new Node("check","检查",NodeKind.SYSTEM,WorkflowVerification.MODULE,1,"builtin.general","检查",List.of(),List.of(),List.of(),new Completion(CompletionKind.VERIFIED,"检查通过",null),0,false,Map.of());
        assertThatThrownBy(()->verificationStore.contract(node)).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_node_attempt",Integer.class)).isZero();
        assertThatThrownBy(()->templates.create(new WorkflowRequests.CreateTemplate(key(),"错误系统角色","",new WorkflowGraph(1,List.of(node),List.of(),List.of()),CanvasLayout.empty()))).isInstanceOf(BadRequestException.class);
    }
    @Test void successfulProgramCheckpointRequiresExplicitConfirmationBeforeTheRequirementCompletes() throws Exception {
        String id=verificationRequirement(CompletionKind.VERIFIED,0,"baseline",false,true),writer=start(id,"work");running(writer);call(writer,submission(writer));finish(writer);
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),-1,WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of()));
        dispatch.advance(id);String check=nodes.node(id,1,"verify").latestAttemptId();verificationExecution.advance(check);dispatch.advance(id);
        assertThat(nodes.attempt(check).state()).isEqualTo("SUCCEEDED");assertThat(plans.require(id).state()).isEqualTo("PAUSED");
        assertThat(runControls.get(id).checkpoints()).extracting(value->value.attemptId()).containsExactly(check);
        assertThatThrownBy(()->runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),runControls.get(id).controlVersion(),
                WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of()))).isInstanceOf(ConflictException.class);
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),runControls.get(id).controlVersion(),
                WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of(check)));dispatch.advance(id);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(nodes.node(id,1,"verify").attemptCount()).isEqualTo(1);
    }
    @Test void commandPreparationCopiesTheFrozenTreeAndNeverMutatesEitherItsInputOrTheCurrentCheckout() throws Exception {
        String id=requirement(false),writer=start(id,"work");running(writer);Files.writeString(root.resolve("code.txt"),"frozen code");call(writer,submission(writer));finish(writer);
        var result=models.encoding().decode(nodes.delivery(writer).contentJson(),WorkflowDelivery.class);
        var reference=models.encoding().decode(models.encoding().encode(result.outputs().get("code").content()),WorkflowCodeSnapshot.Reference.class);
        Files.writeString(root.resolve("code.txt"),"user changes");String commandId=UUID.randomUUID().toString();
        Path workspace=commandWorkspace.prepare(commandId,project,id,writer,reference);
        assertThat(Files.readString(workspace.resolve("code.txt"))).isEqualTo("frozen code");
        Files.writeString(workspace.getParent().resolve("preparing").resolve("interrupted.preparing"),"partial");
        assertThat(commandWorkspace.prepare(commandId,project,id,writer,reference)).isEqualTo(workspace);
        Files.writeString(workspace.resolve("code.txt"),"test mutation");
        assertThatThrownBy(()->commandWorkspace.prepare(commandId,project,id,writer,reference)).isInstanceOf(ConflictException.class);
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("user changes");assertThat(read(writer,id,"code.txt")).isEqualTo("frozen code");
    }
    @Test void nativeCheckRunsAgainstFixedCodeAndPublishesReportAndProcessProofWithoutAHiddenTask() throws Exception {
        String id=commandRequirement("good",CompletionKind.VERIFIED,20),check=startCommand(id);
        assertThat(runReads.get(id,"verify",check).commandState()).isEqualTo("PREPARING");
        assertThat(runReads.get(id,"verify",check).modelState()).isNull();
        Files.writeString(root.resolve("code.txt"),"changed checkout");
        commandDone(check);var row=commandStore.require(check);
        assertThat(row.state()).isEqualTo("SUCCEEDED");assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(nodes.delivery(check).outcome()).isEqualTo("PASS");
        assertThat(commandActions.evidence(id,"verify",check).result().path("output").asString()).contains("baseline");
        assertThat(executionRows.stop(check).orElseThrow().kind()).isEqualTo("COMMAND_TERMINAL");
        var path=Path.of(commandStore.request(row).directory());assertThat(Files.readAllLines(path.resolve("effects"))).hasSize(1);
        var metadata=runReads.get(id,"verify",check);assertThat(metadata.commandState()).isEqualTo("SUCCEEDED");assertThat(metadata.commandVersion()).isEqualTo(row.version());
        assertThat(json.writeValueAsString(metadata)).doesNotContain("requestJson","resultJson","argv","one execution");
        var result=row.resultJson();commandExecution.advance(check);assertThat(commandStore.require(check).resultJson()).isEqualTo(result);
        assertThat(Files.readAllLines(path.resolve("effects"))).hasSize(1);assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("changed checkout");
        for(String table:List.of("task","judge_run"))assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_model_launch",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void preparationEvidenceIsPersistedAndFailureCannotBecomeAnAllowedBusinessOutcome(boolean success) throws Exception {
        String id=commandRequirement("good",CompletionKind.DELIVERABLES,20),check=startCommand(id);
        prepareCommandPipeline(check,success?"good":"fail");commandDone(check);
        var row=commandStore.require(check);assertThat(row.state()).isEqualTo(success?"SUCCEEDED":"FAILED");
        var evidence=commandActions.evidence(id,"verify",check);
        assertThat(evidence.request().path("preparations").size()).isEqualTo(1);
        assertThat(evidence.result().path("preparations").get(0).path("exitCode").asInt()).isEqualTo(success?0:3);
        assertThat(evidence.result().path("launched").asBoolean()).isEqualTo(success);
        assertThat(executionRows.stop(check).orElseThrow().kind()).isEqualTo("COMMAND_TERMINAL");
        var report=models.encoding().decode(nodes.delivery(check).contentJson(),WorkflowDelivery.class).outputs().get("report").content();assertThat(report.path("valid").asBoolean()).isEqualTo(success);
        assertThat(Files.readAllLines(Path.of(commandStore.request(row).directory()).resolve("effects"))).hasSize(success?2:1);
    }
    @Test void pipelineFinalTransactionFailureRecoversBothOriginalReceiptsWithoutAnyReplay() throws Exception {
        String id=commandRequirement("good",CompletionKind.VERIFIED,20),check=startCommand(id);prepareCommandPipeline(check,"good");
        jdbc.execute("CREATE TRIGGER fail_pipeline_proof BEFORE INSERT ON workflow_attempt_stop WHEN NEW.attempt_id='"+check+"' BEGIN SELECT RAISE(ABORT,'pipeline rollback'); END");
        commandAwait(()->{commandExecution.advance(check);return commandStore.require(check).suspended();});
        var row=commandStore.require(check);assertThat(row.resultJson()).isNull();assertThat(nodes.findDelivery(check)).isEmpty();
        var original=durableCommands.observe(new DurableCommands.Job(check,row.requestSha256())).result();assertThat(original.preparations()).hasSize(1);
        jdbc.execute("DROP TRIGGER fail_pipeline_proof");commandActions.resume(id,"verify",check,new WorkflowCommandActions.Command(key(),row.version()));commandDone(check);
        assertThat(commandStore.require(check).state()).isEqualTo("SUCCEEDED");
        assertThat(models.encoding().decode(commandStore.require(check).resultJson(),DurableCommandProtocol.Result.class)).isEqualTo(original);
        assertThat(Files.readAllLines(Path.of(commandStore.request(row).directory()).resolve("effects"))).hasSize(2);
    }
    private void prepareCommandPipeline(String check,String mode) {
        var context=commandStore.context(check);var input=context.input();var row=context.run();
        var reference=models.encoding().decode(models.encoding().encode(input.content()),WorkflowCodeSnapshot.Reference.class);
        var workspace=commandWorkspace.prepare(check,row.projectId(),row.requirementId(),input.attemptId(),reference);
        var preparation=new ArrayList<>(context.spec().argv());preparation.set(preparation.size()-1,mode);
        var request=new DurableCommandProtocol.Request(check,workspace.toString(),context.spec().argv(),context.spec().timeoutSeconds(),
                List.of(new DurableCommandProtocol.Preparation("PREPARE",workspace.toString(),preparation)));
        var job=durableCommands.prepare(request);commandStore.prepared(context,request,job.requestSha256());
    }
    @Test void failedCommandIsARealBusinessOutcomeOnlyWhenTheUserAllowsIt() throws Exception {
        String id=commandRequirement("fail",CompletionKind.DELIVERABLES,20),check=startCommand(id);commandDone(check);
        assertThat(commandStore.require(check).state()).isEqualTo("SUCCEEDED");assertThat(nodes.delivery(check).outcome()).isEqualTo("FAIL");
        assertThat(commandActions.evidence(id,"verify",check).result().path("exitCode").asInt()).isEqualTo(3);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void requiredCommandFailureStallsAndPreservesItsReport() throws Exception {
        String id=commandRequirement("fail",CompletionKind.VERIFIED,20),check=startCommand(id);commandDone(check);
        assertThat(commandStore.require(check).state()).isEqualTo("FAILED");assertThat(nodes.delivery(check).outcome()).isEqualTo("FAIL");
        assertThat(runControls.get(id).reasonCode()).isEqualTo("WORKFLOW_RETRY_EXHAUSTED");
    }
    @Test void commandTimeoutCannotBecomeAValidFailureBranch() throws Exception {
        String id=commandRequirement("sleep",CompletionKind.DELIVERABLES,1),check=startCommand(id);commandDone(check);
        assertThat(commandStore.require(check).state()).isEqualTo("FAILED");
        assertThat(commandActions.evidence(id,"verify",check).result().path("timedOut").asBoolean()).isTrue();
        assertThat(nodes.delivery(check).contentJson()).contains("\"valid\":false");
    }
    @Test void cancelBeforePreparationClosesWithoutAProcessAndRejectsTheLatePreparation() throws Exception {
        String id=commandRequirement("good",CompletionKind.VERIFIED,20),check=startCommand(id);
        var request=new WorkflowCommandActions.Command(key(),commandStore.require(check).version());
        var stopped=commandActions.stop(id,"verify",check,request);assertThat(commandActions.stop(id,"verify",check,request)).isEqualTo(stopped);
        commandDone(check);assertThat(commandStore.require(check).state()).isEqualTo("CANCELLED");
        assertThat(executionRows.stop(check).orElseThrow().kind()).isEqualTo("COMMAND_NOT_LAUNCHED");
        assertThat(commandStore.require(check).requestJson()).isNull();assertThat(Files.exists(DATA.resolve("workflow-commands").resolve(check))).isFalse();
        assertThatThrownBy(()->commandActions.stop(id,"different",check,request)).isInstanceOf(NotFoundException.class);
    }
    @Test void cancelReadyBeforeGrantHasARealSupervisorReceiptButNoCommandEffect() throws Exception {
        String id=commandRequirement("good",CompletionKind.VERIFIED,20),check=startCommand(id);commandExecution.advance(check);
        var row=commandStore.require(check);assertThat(row.state()).isEqualTo("READY");
        commandActions.stop(id,"verify",check,new WorkflowCommandActions.Command(key(),row.version()));commandDone(check);
        assertThat(commandStore.require(check).state()).isEqualTo("CANCELLED");
        var evidence=commandActions.evidence(id,"verify",check);assertThat(evidence.result().path("launched").asBoolean()).isFalse();
        assertThat(Files.exists(Path.of(commandStore.request(row).directory()).resolve("effects"))).isFalse();
    }
    @Test void cancelRunningStopsTheOriginalProcessBeforeSettlingTheNode() throws Exception {
        String id=commandRequirement("sleep",CompletionKind.VERIFIED,20),check=startCommand(id);commandRunning(check);
        var row=commandStore.require(check);commandActions.stop(id,"verify",check,new WorkflowCommandActions.Command(key(),row.version()));commandDone(check);
        assertThat(commandStore.require(check).state()).isEqualTo("CANCELLED");
        assertThat(commandActions.evidence(id,"verify",check).result().path("stopConfirmed").asBoolean()).isTrue();
        assertThat(Files.readAllLines(Path.of(commandStore.request(row).directory()).resolve("effects"))).hasSize(1);
    }
    @Test void failedFinalTransactionRecoversTheSameReceiptWithoutRerunningTheCommand() throws Exception {
        String id=commandRequirement("good",CompletionKind.VERIFIED,20),check=startCommand(id);
        jdbc.execute("CREATE TRIGGER fail_command_proof BEFORE INSERT ON workflow_attempt_stop WHEN NEW.attempt_id='"+check+"' BEGIN SELECT RAISE(ABORT,'command rollback'); END");
        commandAwait(()->{commandExecution.advance(check);return commandStore.require(check).suspended();});
        var row=commandStore.require(check);assertThat(row.state()).isEqualTo("RUNNING");assertThat(row.resultJson()).isNull();assertThat(nodes.findDelivery(check)).isEmpty();
        assertThat(executionRows.stop(check)).isEmpty();
        assertThatThrownBy(()->runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),runControls.get(id).controlVersion(),
                WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of()))).isInstanceOf(ConflictException.class);
        jdbc.execute("DROP TRIGGER fail_command_proof");commandActions.resume(id,"verify",check,new WorkflowCommandActions.Command(key(),row.version()));commandDone(check);
        assertThat(commandStore.require(check).state()).isEqualTo("SUCCEEDED");
        assertThat(Files.readAllLines(Path.of(commandStore.request(row).directory()).resolve("effects"))).hasSize(1);
    }
    @Test void missingSupervisorReceiptBlocksDispatchAndResumeNeverDuplicatesItsExternalEffect() throws Exception {
        String id=commandRequirement("sleep",CompletionKind.VERIFIED,30),check=startCommand(id);commandRunning(check);
        var row=commandStore.require(check);var job=new DurableCommands.Job(check,row.requestSha256());
        var worker=durableCommands.observe(job).registration().worker();
        assertThat(commandActions.evidence(id,"verify",check).registration().path("worker").path("pid").asLong()).isEqualTo(worker.pid());
        var child=DurableCommandProtocol.registration(DurableCommandProtocol.read(DATA.toRealPath().resolve("workflow-commands").resolve(check).resolve("process"))).worker();
        try {
            ProcessHandle.of(worker.pid()).filter(worker::matches).orElseThrow().destroyForcibly();commandAwait(()->!worker.alive());
            commandExecution.advance(check);assertThat(commandStore.require(check).lastErrorCode()).isEqualTo("WORKFLOW_COMMAND_STOP_UNCONFIRMED");
            assertThat(runReads.get(id,"verify",check).suspended()).isTrue();assertThat(runReads.get(id,"verify",check).errorCode()).isEqualTo("WORKFLOW_COMMAND_STOP_UNCONFIRMED");
            assertThat(runControls.get(id).state()).isEqualTo(WorkflowControlState.STALLED);assertThat(executionRows.stop(check)).isEmpty();
            var current=commandStore.require(check);commandActions.resume(id,"verify",check,new WorkflowCommandActions.Command(key(),current.version()));
            commandExecution.advance(check);assertThat(commandStore.require(check).suspended()).isTrue();
            assertThat(Files.readAllLines(Path.of(commandStore.request(row).directory()).resolve("effects"))).hasSize(1);
            assertThat(nodes.node(id,1,"verify").attemptCount()).isEqualTo(1);
            finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.COMPLETED,"用户选择结束"));
            finishDrain.stop(id,check);commandExecution.advance(check);
            assertThat(finishes.finalizeReady(id)).isFalse();assertThat(plans.require(id).state()).isEqualTo("STOPPING");
            assertThat(runControls.get(id).reasonCode()).isEqualTo("WORKFLOW_FINISHING");
            assertThat(finishes.get(id).intent().finalizedAt()).isNull();assertThat(executionRows.stop(check)).isEmpty();
        } finally {ProcessHandle.of(child.pid()).filter(child::matches).ifPresent(ProcessHandle::destroyForcibly);commandAwait(()->!child.alive());}
    }
    @Test void earlySuccessCannotFinishUntilWriterRestorationAndLeaseReleaseCommit() throws Exception {
        String id=requirement(true),run=start(id,"work");running(run);Files.writeString(root.resolve("code.txt"),"partial work");
        var request=new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.COMPLETED,"用户提前结束");
        finishes.request(id,request);finishDrain.stop(id,run);fake.failNextAborts(1);
        assertThatThrownBy(()->execution.advance(run)).isInstanceOf(io.opencode.loopper.domain.SessionFailure.class);
        assertThat(finishes.finalizeReady(id)).isFalse();
        assertThat(mapper.findWorkspaceLease(root.toRealPath().toString()).orElseThrow().holderWorkflowAttemptId()).isEqualTo(run);
        var once=new AtomicBoolean(true);
        doAnswer(call->{var result=call.callRealMethod();if(once.getAndSet(false))throw new IllegalStateException("finish release rollback");return result;})
                .when(workspaceStore).release(any(),any(),anyLong());
        assertThatThrownBy(()->execution.advance(run)).hasMessage("finish release rollback");
        assertThat(finishes.finalizeReady(id)).isFalse();assertThat(nodes.hasStop(run)).isTrue();
        assertThat(workspaceStore.require(run).state()).isEqualTo("RESTORED");
        execution.advance(run);assertThat(finishes.finalizeReady(id)).isTrue();
        assertThat(workspaceStore.require(run).state()).isEqualTo("RELEASED");assertThat(nodes.attempt(run).state()).isEqualTo("CANCELLED");
        assertThat(nodes.findDelivery(run)).isEmpty();assertThat(nodes.node(id,1,"next").latestAttemptId()).isNull();
        assertThat(Files.readString(root.resolve("code.txt"))).isEqualTo("baseline");assertThat(command("status","--porcelain")).isEmpty();
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
    }
    @Test void endingQueuedWriterDoesNotDisturbAnotherRequirementsActiveLease() throws Exception {
        String first=requirement(false),active=start(first,"work");running(active);
        String second=requirement(false),queued=start(second,"work");
        finishes.request(second,new WorkflowFinishes.Request(key(),plans.require(second).version(),WorkflowState.CANCELLED,"无需继续排队"));
        finishDrain.stop(second,queued);execution.advance(queued);
        assertThat(finishes.finalizeReady(second)).isTrue();
        assertThat(mapper.findWorkflowWriter(queued).orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(mapper.findWorkspaceLease(root.toRealPath().toString()).orElseThrow().holderWorkflowAttemptId()).isEqualTo(active);
        assertThat(models.require(active).state()).isEqualTo("RUNNING");
        stop(active);execution.advance(active);
    }
    @Test void earlyFailureDrainsTheOriginalNativeCommandBeforeRecordingUserOutcome() throws Exception {
        String id=commandRequirement("sleep",CompletionKind.VERIFIED,20),check=startCommand(id);commandRunning(check);
        var original=commandStore.require(check);finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.FAILED,"用户结束"));
        finishDrain.stop(id,check);assertThat(finishes.finalizeReady(id)).isFalse();commandDone(check);
        assertThat(finishes.finalizeReady(id)).isTrue();assertThat(plans.require(id).state()).isEqualTo("FAILED");
        assertThat(commandActions.evidence(id,"verify",check).result().path("stopConfirmed").asBoolean()).isTrue();
        assertThat(Files.readAllLines(Path.of(commandStore.request(original).directory()).resolve("effects"))).hasSize(1);
        assertThat(nodes.attempt(check).state()).isEqualTo("CANCELLED");assertThat(nodes.findDelivery(check)).isEmpty();
    }
    @Test void earlySuccessPreservesAnActualFailedProgramReportAndItsVerdict() throws Exception {
        String id=commandRequirement("fail",CompletionKind.VERIFIED,20),check=startCommand(id);commandDone(check);
        var report=nodes.delivery(check);assertThat(report.outcome()).isEqualTo("FAIL");
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.COMPLETED,"人工决定接受现有结果"));
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");assertThat(nodes.attempt(check).state()).isEqualTo("FAILED");
        assertThat(nodes.delivery(check)).isEqualTo(report);assertThat(finishes.get(id).intent().reason()).isEqualTo("人工决定接受现有结果");
    }
    @Test void cancellingFileVerificationRejectsLateResultsWithoutNeedingSyntheticRemoteProof() throws Exception {
        String id=verificationRequirement(CompletionKind.VERIFIED,0,"baseline",false),writer=start(id,"work");running(writer);call(writer,submission(writer));finish(writer);
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),-1,WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of()));
        dispatch.advance(id);String check=nodes.node(id,1,"verify").latestAttemptId();var context=verificationStore.context(check);
        finishes.request(id,new WorkflowFinishes.Request(key(),plans.require(id).version(),WorkflowState.CANCELLED,"取消检查"));
        finishDrain.stop(id,check);assertThat(finishes.finalizeReady(id)).isTrue();
        verificationStore.finish(context,new WorkflowDelivery("迟到结果","PASS",Map.of()),false);
        assertThat(nodes.attempt(check).state()).isEqualTo("CANCELLED");assertThat(nodes.findDelivery(check)).isEmpty();
        assertThat(executionRows.stop(check).orElseThrow().kind()).isEqualTo("NO_EXTERNAL_WORK");
    }
    private String commandRequirement(String mode,CompletionKind completion,int timeout) throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String classes=Path.of(CommandFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var spec=new WorkflowCommandVerification(1,"code",List.of(executable,"-cp",classes,CommandFixture.class.getName(),mode),timeout,"CHECK",null);
        var preset=nodePresets.get("verification.command",1).node();
        var verify=new Node("verify",preset.title(),preset.kind(),preset.moduleId(),preset.moduleVersion(),preset.roleId(),preset.task(),
                List.of(new Input("code",InputSource.NODE,"work","code",DataKind.CODE,true)),List.of(new Output("report","检查报告",DataKind.JSON,true),new Output("summary","检查说明",DataKind.TEXT,true)),
                List.of("PASS","FAIL"),new Completion(completion,"按配置完成检查",null),0,false,Map.of("commandVerification",json.writeValueAsString(spec)));
        var graph=new WorkflowGraph(1,List.of(node("work",List.of()),verify),List.of(new Edge("after","work","verify",null)),List.of());
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"命令验证流程","",graph,CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"命令检查需求","检查固定代码",template.id(),1));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private String startCommand(String id) throws Exception {
        String writer=start(id,"work");running(writer);call(writer,submission(writer));finish(writer);
        runControls.start(id,new WorkflowControls.Start(key(),plans.require(id).version(),-1,WorkflowDispatch.Mode.CONTINUOUS,null,null,null,List.of()));
        dispatch.advance(id);return nodes.node(id,1,"verify").latestAttemptId();
    }
    private void commandDone(String id) throws Exception {
        commandAwait(()->{commandExecution.advance(id);return WorkflowCommandState.valueOf(commandStore.require(id).state()).terminal();});
    }
    private void commandRunning(String id) throws Exception {
        commandAwait(()->{commandExecution.advance(id);var row=commandStore.require(id);return row.requestJson()!=null && Files.exists(Path.of(commandStore.request(row).directory()).resolve("effects"));});
    }
    private static void commandAwait(java.util.function.BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+java.time.Duration.ofSeconds(12).toNanos();
        while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(25);}assertThat(condition.getAsBoolean()).isTrue();
    }
    public static class CommandFixture {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of("effects"),"one execution\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            System.out.println(Files.readString(Path.of("code.txt")));
            if(args[0].equals("sleep"))Thread.sleep(60000);
            if(args[0].equals("fail"))System.exit(3);
        }
    }
    private String verificationRequirement(CompletionKind completion,int retries,String expected,boolean branch) {
        return verificationRequirement(completion,retries,expected,branch,false);
    }
    private String verificationRequirement(CompletionKind completion,int retries,String expected,boolean branch,boolean pauseAfter) {
        var checks=List.of(new WorkflowVerification.Check("文件内容","FILE_CONTENT","code.txt",expected,"EXACT"),
                new WorkflowVerification.Check("文件哈希","FILE_HASH","code.txt",ImmutableContentStore.hash(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8)),null),
                new WorkflowVerification.Check("临时文件移除","FILE_NOT_EXISTS","removed.txt",null,null));
        var check=new Node("verify","程序检查",NodeKind.SYSTEM,WorkflowVerification.MODULE,1,null,"检查已保存的交付物",
                List.of(new Input("code",InputSource.NODE,"work","code",DataKind.CODE,true)),List.of(new Output("report","检查报告",DataKind.JSON,true),new Output("summary","检查说明",DataKind.TEXT,true)),
                List.of("PASS","FAIL"),new Completion(completion,"按配置完成检查",null),retries,pauseAfter,Map.of("verification",json.writeValueAsString(new WorkflowVerification(1,"code",checks))));
        var all=new ArrayList<Node>(List.of(node("work",List.of()),check));var edges=new ArrayList<Edge>(List.of(new Edge("after","work","verify",null)));
        if(branch)for(String outcome:List.of("PASS","FAIL")) {
            String key=outcome.equals("PASS")?"accepted":"repair";
            all.add(new Node(key,"人工处理",NodeKind.HUMAN,null,0,null,"查看检查报告",List.of(new Input("report",InputSource.NODE,"verify","report",DataKind.JSON,true)),
                    List.of(new Output("result","人工结果",DataKind.TEXT,true)),List.of(),new Completion(CompletionKind.HUMAN,"填写结果",null),0,false,Map.of()));
            edges.add(new Edge("after_"+key,"verify",key,outcome));
        }
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"程序验证流程","",new WorkflowGraph(1,all,edges,List.of()),CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"检查需求","检查文件版本",template.id(),1));plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private Object fileCall(String attempt,String tool,Map<String,Object> args) { return tools.call(tool,Map.of("scope",scopes.grant(models.require(attempt)),"attemptId",attempt,"args",args)); }
    private void plainProject()throws Exception {
        root=Files.createDirectory(directory.resolve("plain"));Files.writeString(root.resolve("code.txt"),"baseline");
        project=projects.create("普通目录流程",root.toString(),"").id();
    }
    private String requirement(boolean chain) { return requirement(chain,false); }
    private String requirement(boolean chain,boolean checkpoint) {
        var all=new ArrayList<Node>();all.add(node("work",List.of(),checkpoint));var edges=new ArrayList<Edge>();
        if(chain) { all.add(node("next",List.of(new Input("workspace",InputSource.NODE,"work","code",DataKind.CODE,true))));edges.add(new Edge("after","work","next",null)); }
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"可写测试","",new WorkflowGraph(1,all,edges,List.of()),CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"需求","完成文件工作",template.id(),1));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private Node node(String id,List<Input> inputs) { return node(id,inputs,false); }
    private Node node(String id,List<Input> inputs,boolean checkpoint) { return new Node(id,"文件工作",NodeKind.WORK,WorkflowModelProfile.WRITE_MODULE,1,"builtin.implementation","完成当前文件修改",inputs,
            List.of(new Output("code","代码",DataKind.CODE,true),new Output("result","说明",DataKind.TEXT,true)),List.of(),new Completion(CompletionKind.DELIVERABLES,"交付文件",null),1,checkpoint,Map.of(),revision); }
    private String start(String id,String node) { return admission.start(id,node,new WorkflowModelAdmission.Start(key(),plans.require(id).version(),null,new OpenCodeClient.OpenCodeModel("fake","test",false))).attemptId(); }
    private void running(String attempt) { for(int i=0;i<4 && !models.require(attempt).state().equals("RUNNING");i++) execution.advance(attempt);assertThat(models.require(attempt).state()).isEqualTo("RUNNING"); }
    private void finish(String attempt) { fake.setSessionState(nodes.attempt(attempt).externalSessionId(),"COMPLETED");execution.advance(attempt); }
    private void stop(String attempt) { var row=models.require(attempt);models.stop(attempt);assertThat(models.require(attempt).state()).isEqualTo("STOPPING"); }
    private Map<String,Object> submission(String attempt) { return Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",new WorkflowDelivery("已完成",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("实际修改说明"))))); }
    private Object call(String attempt,Map<String,Object> args) { return tools.call(WorkflowModelProfile.SUBMIT,Map.of("scope",scopes.grant(models.require(attempt)),"attemptId",attempt,"args",args)); }
    private String read(String attempt,String requirement,String path) { var result=models.encoding().decode(nodes.delivery(attempt).contentJson(),WorkflowDelivery.class);var reference=models.encoding().decode(models.encoding().encode(result.outputs().get("code").content()),WorkflowCodeSnapshot.Reference.class);return new String(codes.read(project,requirement,attempt,reference,path),java.nio.charset.StandardCharsets.UTF_8); }
    private String command(String... args) { return git.read(root,args); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static Path data() { try{return Files.createTempDirectory("workflow-writer-model-tests-");}catch(Exception e){throw new ExceptionInInitializerError(e);} }
}
