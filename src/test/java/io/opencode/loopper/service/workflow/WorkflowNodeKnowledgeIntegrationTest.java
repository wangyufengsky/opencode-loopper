package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.assist.*;
import io.opencode.loopper.service.knowledge.*;
import io.opencode.loopper.service.roles.*;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.monitor-delay=1h","loopper.workflow-monitor-enabled=false"})
class WorkflowNodeKnowledgeIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("loopper.data-dir",()->DATA.toString());
        p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("knowledge.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired ProjectService projects;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowBuiltinFlows builtins;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowModelAdmission admission;
    @Autowired WorkflowModelExecution execution;
    @Autowired WorkflowModelStore store;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired WorkflowNodeKnowledgeEvidence evidence;
    @Autowired WorkflowModelTools nodeTools;
    @Autowired WorkflowRuntimeSupport identity;
    @Autowired OpenCodeClient runtime;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired RolePublishingService publishing;
    @Autowired RoleConfigurationMapper roles;
    @Autowired WorkflowKnowledgeMapper bindings;
    @Autowired KnowledgeSources sources;
    @Autowired AssistScopeService scopes;
    @Autowired AssistToolService tools;
    @Autowired AssistToolPolicyService policies;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sql;
    @MockitoSpyBean WorkflowKnowledgeBindings sourceBindings;
    @MockitoSpyBean WorkflowNodeKnowledgeAccess gate;
    @MockitoSpyBean AssistMapper assist;
    @MockitoSpyBean WorkflowExecutionMapper executionRows;
    @TempDir Path directory;
    FakeOpenCodeClient fake;
    Path root;
    String project,revision;
    @BeforeEach void setup()throws Exception {
        directory=directory.toRealPath();flyway.clean();flyway.migrate();publishing.seedBuiltin();fake=(FakeOpenCodeClient)runtime;fake.reset();
        var credential=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credential);access.connected(credential.generation());
        fake.setManagedRuntime(credential.generation(),credential.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        root=Files.createDirectory(directory.resolve("project")).toRealPath();Files.writeString(root.resolve("rules.md"),"# 约定\n退款申请需要核对订单状态。\n");
        project=projects.create("节点知识",root.toString()).id();revision=roles.latest("builtin.knowledge").revisionId();
        policies.catalog(project,AssistToolCatalog.SERVER,AssistToolCatalog.tools().stream().map(AssistToolCatalog.Tool::name).toList(),true);
    }
    @Test void readsAuthorizedSourcesAndSavesOwnEvidenceWithoutChangingFormalInputs()throws Exception {
        String attempt=start(requirement());String grant=grant(attempt);
        var scope=scopes.resolve(session(attempt));assertThat(scope.connections()).isEmpty();
        assertThat(scope.tools()).allMatch(AssistToolCatalog::knowledgeTool).contains("read_knowledge_source");
        var late=sources.addDirectory(project,Files.createDirectory(directory.resolve("late")).toString());
        var search=tools.call("search_project_knowledge",Map.of("scope",grant,"query","退款申请"));
        assertThat(search.error()).as(search.content().toString()).isFalse();assertThat(search.content().toString()).contains("rules.md");
        var read=read(grant);assertThat(read.error()).as(read.content().toString()).isFalse();
        assertThat(read.content()).containsKeys("citationId","sha256","collectedAt","sourceAuthorizationFrozenAt");
        String citation=read.content().get("citationId").toString();
        Files.writeString(root.resolve("rules.md"),"# 新约定\n资料已改变。\n");
        var historical=tools.call("read_knowledge_evidence",Map.of("scope",grant,"reference",citation));
        assertThat(historical.error()).isFalse();assertThat(historical.content().toString()).contains("退款申请").doesNotContain("新约定");
        assertThat(tools.call("read_knowledge_source",Map.of("scope",grant,"sourceId","documents","path","rules.md","section",0,"expectedSha",read.content().get("sha256"))).error()).isTrue();
        assertThat(tools.call("browse_knowledge_source",Map.of("scope",grant,"sourceId",late.id())).error()).isTrue();
        assertThat(tools.call("query_database_readonly",Map.of("scope",grant,"connectionId","unauthorized","sql","SELECT 1")).error()).isTrue();
        assertThat(tools.call("read_knowledge_source",Map.of("scope",grant,"sourceId","code","path","../outside")).error()).isTrue();
        assertThat(nodes.inputSnapshot(nodes.attempt(attempt)).values()).isEmpty();
        assertThat(nodes.findDelivery(attempt)).isEmpty();assertThat(nodes.attempt(attempt).state()).isEqualTo("RUNNING");
        String other=start(requirement());String otherGrant=grant(other);
        assertThat(tools.call("read_knowledge_evidence",Map.of("scope",otherGrant,"reference",citation)).error()).isTrue();
    }
    @Test void retryInheritsOriginalSourceSetAndOriginalSessionCannotReadAgain()throws Exception {
        String owner=requirement(),first=start(owner);String old=grant(first);
        String original=bindings.binding("WORKFLOW_ATTEMPT",first).sourcesJson();
        sources.addDirectory(project,Files.createDirectory(directory.resolve("added-later")).toString());
        fake.setSessionState(session(first),"COMPLETED");execution.advance(first);
        assertThat(nodes.attempt(first).state()).isEqualTo("FAILED");
        String retry=start(owner);assertThat(bindings.binding("WORKFLOW_ATTEMPT",retry).sourcesJson()).isEqualTo(original);
        assertThat(tools.call("list_knowledge_sources",Map.of("scope",old)).error()).isTrue();assertThat(read(grant(retry)).error()).isFalse();
    }
    @Test void policyChangesOnlyAffectNewSessionsAndRuntimeRotationClosesOldGrant() {
        String first=start(requirement()),old=grant(first);
        policies.update(project,AssistToolCatalog.SERVER,"read_knowledge_source",0,-1);
        assertThat(read(old).error()).isFalse();
        String next=start(requirement()),fresh=grant(next);
        assertThat(scopes.resolve(session(next)).tools()).doesNotContain("read_knowledge_source");assertThat(read(fresh).error()).isTrue();
        access.activate(new InternalMcpCredentialProvider(()->18083).issue());assertThat(read(old).error()).isTrue();
    }
    @Test void legacyNodeWithoutSourceBindingDoesNotAcquireSourcesOnReplayOrRetry() {
        doAnswer(call->{
            var owner=(RoleConfigurationService.OwnerRef)call.getArgument(0);
            if(owner.type().equals("WORKFLOW_NODE"))return null;return call.callRealMethod();
        }).when(sourceBindings).freeze(any(),any());
        String owner=requirement(),first=start(owner);String node=nodes.attempt(first).nodeRunId();
        reset(sourceBindings);
        assertThat(bindings.binding("WORKFLOW_NODE",node)).isNull();assertThat(bindings.binding("WORKFLOW_ATTEMPT",first)).isNull();
        assertThat(store.plan(store.require(first)).permissionPolicy()).noneMatch(rule->rule.permission().contains("_assist_")&&rule.action().equals("allow"));
        fake.setSessionState(session(first),"COMPLETED");execution.advance(first);String retry=start(owner);
        assertThat(bindings.binding("WORKFLOW_ATTEMPT",retry)).isNull();assertThat(bindings.binding("WORKFLOW_NODE",node)).isNull();
        assertThat(store.plan(store.require(retry)).permissionPolicy()).noneMatch(rule->rule.permission().contains("_assist_")&&rule.action().equals("allow"));
    }
    @Test void cancellationImmediatelyBeforeReceiptCommitRejectsLateEvidence() {
        String attempt=start(requirement()),grant=grant(attempt);
        doAnswer(call->{store.stop(attempt);return call.callRealMethod();}).when(gate).complete(any(),anyString(),anyString(),anyString());
        var read=read(grant);assertThat(read.error()).isTrue();assertThat(read.content().get("code")).isEqualTo("WORKFLOW_KNOWLEDGE_DENIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assist_call WHERE external_session_id=? AND state='SUCCEEDED'",Integer.class,session(attempt))).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM assist_call WHERE external_session_id=?",String.class,session(attempt))).isEqualTo("FAILED");
        assertThat(nodes.findDelivery(attempt)).isEmpty();assertThat(nodes.attempt(attempt).state()).isEqualTo("STOPPING");
    }
    @Test void receiptFailureRollsBackSuccessAndScopeCannotLeakIntoDelivery() {
        String attempt=start(requirement()),grant=grant(attempt);
        var inserted=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(sql.getMapper(AssistMapper.class).finishScopedCall(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state FROM assist_call WHERE id=?",String.class,call.getArgument(0,String.class))).isEqualTo("SUCCEEDED");
            inserted.set(true);throw new IllegalStateException("receipt interruption");})
                .when(assist).finishScopedCall(anyString(),anyString(),anyString(),anyString(),anyString());
        var result=read(grant);assertThat(result.error()).isTrue();assertThat(inserted).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assist_call WHERE external_session_id=? AND state='SUCCEEDED'",Integer.class,session(attempt))).isZero();
        var delivery=new WorkflowDelivery("结论",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree(grant))));
        assertThatThrownBy(()->nodeTools.call(WorkflowModelProfile.SUBMIT,Map.of("scope",identity.grant(store.require(attempt)),"attemptId",attempt,
                "args",Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",delivery))))
                .isInstanceOfSatisfying(BadRequestException.class,e->assertThat(e.code()).isEqualTo("WORKFLOW_SCOPE_IN_OUTPUT"));
        assertThat(nodes.findDelivery(attempt)).isEmpty();
    }
    @Test void historicalEvidenceIsPagedByExactAttemptAndSurvivesStopAndChangedFiles()throws Exception {
        String owner=requirement(),attempt=start(owner),grant=grant(attempt);
        var read=read(grant);String id=read.content().get("citationId").toString().substring("call:".length());
        assertThat(tools.call("search_project_knowledge",Map.of("scope",grant,"query","退款申请")).error()).isFalse();
        assertThat(tools.call("read_knowledge_evidence",Map.of("scope",grant,"reference","call:"+id)).error()).isFalse();
        var first=evidence.list(owner,"work",attempt,null,1);
        var second=evidence.list(owner,"work",attempt,first.nextCursor(),1);
        assertThat(first.items()).hasSize(1);assertThat(first.nextCursor()).isNotNull();
        assertThat(second.items()).hasSize(1);assertThat(second.nextCursor()).isNull();
        assertThat(first.items().getFirst().id()).isNotEqualTo(second.items().getFirst().id());
        assertThat(json.writeValueAsString(first)).doesNotContain("退款申请","resultJson","content");
        assertThatThrownBy(()->evidence.read(owner,"wrong-node",attempt,id)).isInstanceOf(NotFoundException.class);
        String other=requirement(),otherAttempt=start(other);grant(otherAttempt);
        assertThatThrownBy(()->evidence.read(other,"work",otherAttempt,id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->evidence.read(other,"work",attempt,id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->evidence.list(other,"work",otherAttempt,first.nextCursor(),1)).isInstanceOf(BadRequestException.class);
        Files.writeString(root.resolve("rules.md"),"内容已经改变");store.stop(attempt);
        var fixed=evidence.read(owner,"work",attempt,id);
        assertThat(fixed.content().toString()).contains("退款申请").doesNotContain("内容已经改变",grant);
        assertThat(evidence.list(owner,"work",attempt,null,50).items()).hasSize(2);
        assertThat(read(grant).error()).isTrue();assertThat(nodes.findDelivery(attempt)).isEmpty();
    }
    @Test void selectedEvidenceBecomesFixedInputWhileUnselectedCallsRemainPrivate()throws Exception {
        String owner=knowledgeRequirement(),first=start(owner,"research"),grant=grant(first);
        String selected=read(grant).content().get("citationId").toString();
        Files.writeString(root.resolve("private-notes.md"),"这项私有调查不交给后续角色。");
        var unused=tools.call("read_knowledge_source",Map.of("scope",grant,"sourceId","documents","path","private-notes.md","section",0));
        assertThat(unused.error()).isFalse();
        var work=nodeTools.call(WorkflowModelProfile.WORK,request(first,Map.of()));
        assertThat(json.writeValueAsString(work)).contains("knowledgeEvidence","references","128 KiB");
        Files.writeString(root.resolve("rules.md"),"后来修改的退款规则");
        var command=submission(first,List.of(selected),List.of());var accepted=nodeTools.call(WorkflowModelProfile.SUBMIT,command);
        assertThat(nodeTools.call(WorkflowModelProfile.SUBMIT,command)).isEqualTo(accepted);
        var stored=nodes.findDelivery(first).orElseThrow();
        assertThat(stored.contentJson()).contains("KNOWLEDGE_EVIDENCE","退款申请","read_knowledge_source").doesNotContain("后来修改",unused.content().get("citationId").toString(),"私有调查");
        assertThatThrownBy(()->start(owner,"summary")).isInstanceOf(ConflictException.class);
        fake.setSessionState(session(first),"COMPLETED");execution.advance(first);
        assertThat(nodes.attempt(first).state()).isEqualTo("SUCCEEDED");String next=start(owner,"summary");
        var snapshot=nodes.inputSnapshot(nodes.attempt(next));
        assertThat(snapshot.values()).allSatisfy(input->assertThat(input.reference()).isNotNull());
        var input=nodeTools.call(WorkflowModelProfile.INPUT,request(next,Map.of("name","evidence","offset",0,"limit",12000)));
        assertThat(json.writeValueAsString(input)).contains("退款申请","KNOWLEDGE_EVIDENCE").doesNotContain("后来修改","私有调查");
        assertThat(tools.call("read_knowledge_evidence",Map.of("scope",grant(next),"reference",selected)).error()).isTrue();
    }
    @Test void foreignDuplicateAndInventedEvidenceAreRejectedWithoutClosingCurrentSession() {
        String owner=knowledgeRequirement(),first=start(owner,"research");String own=read(grant(first)).content().get("citationId").toString();
        String another=start(knowledgeRequirement(),"research");String foreign=read(grant(another)).content().get("citationId").toString();
        for(var refs:List.of(List.of(own,own),List.of(foreign),List.of("call:invented"))) {
            assertThatThrownBy(()->nodeTools.call(WorkflowModelProfile.SUBMIT,submission(first,refs,List.of()))).isInstanceOf(BadRequestException.class);
            assertThat(nodes.findDelivery(first)).isEmpty();assertThat(nodes.attempt(first).state()).isEqualTo("RUNNING");
        }
        assertThatThrownBy(()->nodeTools.call(WorkflowModelProfile.SUBMIT,submission(first,List.of(),List.of()))).isInstanceOf(BadRequestException.class);
        nodeTools.call(WorkflowModelProfile.SUBMIT,submission(first,List.of(own),List.of("仅核对退款规则")));
        assertThat(nodes.findDelivery(first)).isPresent();
    }
    @Test void publicationReceiptAndSelectedEvidenceRollBackTogetherAndCanReplay() {
        String attempt=start(knowledgeRequirement(),"research"),ref=read(grant(attempt)).content().get("citationId").toString();
        var command=submission(attempt,List.of(ref),List.of());
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var delivery=(WorkflowExecutionRows.Delivery)call.getArgument(0);
            assertThat(sql.getMapper(WorkflowExecutionMapper.class).insertDelivery(delivery)).isEqualTo(1);
            assertThat(nodes.findDelivery(attempt)).isPresent();throw new IllegalStateException("delivery interruption");}).when(executionRows).insertDelivery(any());
        assertThatThrownBy(()->nodeTools.call(WorkflowModelProfile.SUBMIT,command)).hasStackTraceContaining("delivery interruption");
        assertThat(nodes.findDelivery(attempt)).isEmpty();assertThat(nodes.attempt(attempt).state()).isEqualTo("RUNNING");
        reset(executionRows);var accepted=nodeTools.call(WorkflowModelProfile.SUBMIT,command);
        assertThat(nodeTools.call(WorkflowModelProfile.SUBMIT,command)).isEqualTo(accepted);assertThat(nodes.findDelivery(attempt)).isPresent();
    }
    @Test void missingEvidenceCanBeExplicitlyReportedAndStoppedNodeCannotPublishLate() {
        String first=start(knowledgeRequirement(),"research");nodeTools.call(WorkflowModelProfile.SUBMIT,submission(first,List.of(),List.of("获准来源未能读取；未确认全项目覆盖。")));
        assertThat(nodes.findDelivery(first).orElseThrow().contentJson()).contains("未确认全项目覆盖","\"entries\":[]");
        String stopped=start(knowledgeRequirement(),"research");String ref=read(grant(stopped)).content().get("citationId").toString();
        var command=submission(stopped,List.of(ref),List.of());store.stop(stopped);
        assertThatThrownBy(()->nodeTools.call(WorkflowModelProfile.SUBMIT,command)).isInstanceOf(ConflictException.class);
        assertThat(nodes.findDelivery(stopped)).isEmpty();
    }
    private Map<String,Object> submission(String attempt,List<String> refs,List<String> limitations) {
        var delivery=new WorkflowDelivery("已整理项目资料",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("见所选原文及局限")),
                "evidence",new WorkflowDelivery.Value(DataKind.JSON,json.valueToTree(Map.of("version",1,"references",refs,"limitations",limitations)))));
        return request(attempt,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(attempt).version(),"delivery",delivery));
    }
    private Map<String,Object> request(String attempt,Map<String,Object> args) {
        return Map.of("scope",identity.grant(store.require(attempt)),"attemptId",attempt,"args",args);
    }
    private String knowledgeRequirement() {
        builtins.publish();var template=templates.get("builtin.workflow.knowledge",null);
        assertThat(template.graph().nodes()).hasSize(2);assertThat(template.diagnostics()).isEmpty();
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"知识整理","核对退款规则",template.id(),template.revision()));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private AssistToolService.Result read(String grant){return tools.call("read_knowledge_source",Map.of("scope",grant,"sourceId","documents","path","rules.md","section",0));}
    private String requirement() {
        var node=new Node("work","检索知识",NodeKind.WORK,WorkflowModelProfile.MODULE,1,"builtin.knowledge","查询退款规则并给出来源",
                List.of(),List.of(new Output("result","检索结论",DataKind.TEXT,true)),List.of(),new Completion(CompletionKind.DELIVERABLES,"交付结论",null),1,false,Map.of(),revision);
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"知识流程","",new WorkflowGraph(1,List.of(node),List.of(),List.of()),CanvasLayout.empty()));
        var owner=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"知识需求","回答退款规则",template.id(),1));
        plans.confirm(owner.id(),new WorkflowRequests.VersionCommand(key(),owner.version()));return owner.id();
    }
    private String start(String requirement) {
        return start(requirement,"work");
    }
    private String start(String requirement,String node) {
        String id=admission.start(requirement,node,new WorkflowModelAdmission.Start(key(),plans.require(requirement).version(),null,new OpenCodeClient.OpenCodeModel("fake","test",false))).attemptId();
        for(int i=0;i<4&&!store.require(id).state().equals("RUNNING");i++)execution.advance(id);
        assertThat(store.require(id).state()).isEqualTo("RUNNING");return id;
    }
    private String session(String attempt){return nodes.attempt(attempt).externalSessionId();}
    private String grant(String attempt) {
        var row=store.require(attempt);var plan=store.plan(row);String session=session(attempt);
        String prefix=AssistToolCatalog.serverName(plan.internalMcpServer())+"_";
        var allowed=AssistToolCatalog.allowed(plan.profile().name()).stream().filter(name->ConfiguredRoleRuntime.allowed(plan.permissionPolicy(),prefix+name)).toList();
        assist.insertSession(new AssistMapper.Session(session,access.current().orElseThrow().generation(),row.directory(),plan.profile().name(),
                json.writeValueAsString(plan.permissionPolicy()),json.writeValueAsString(allowed),Instant.now().toString()));
        String grant=scopes.grant(session);assertThat(grant).startsWith("lpa_");return grant;
    }
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-node-knowledge-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
