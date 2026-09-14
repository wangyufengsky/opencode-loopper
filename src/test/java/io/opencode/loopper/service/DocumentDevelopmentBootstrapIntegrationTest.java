package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class DocumentDevelopmentBootstrapIntegrationTest {
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired Flyway flyway;
    @Autowired DocumentTemplateService service;
    @Autowired DocumentTemplateAdmission admission;
    @Autowired DocumentTemplateMapper runs;
    @Autowired DocumentRequirementMapper requirements;
    @Autowired DocumentDevelopmentBootstrap bootstrap;
    @Autowired DocumentDevelopmentDesign designs;
    @Autowired DocumentDevelopmentPromotion promotion;
    @Autowired DocumentDevelopmentFlow development;
    @Autowired DocumentTemplateControl controls;
    @Autowired DocumentTemplateCoordinator coordinator;
    @Autowired DesignerAutoModeService autoMode;
    @Autowired DesignerSessionService designers;
    @Autowired MachineCandidateSubmission submissions;
    @Autowired TaskService tasks;
    @Autowired DocumentRequirementReportService reports;
    @Autowired DocumentRequirementClarifications clarifications;
    @Autowired DocumentRequirementWorkflow requirementWorkflow;
    @Autowired DocumentSupplementService supplements;
    @Autowired DocumentSupplementDesign supplementDesign;
    @Autowired DocumentSupplementAdmission supplementAdmission;
    @Autowired DocumentSupplementMapper supplementRows;
    @Autowired DocumentTemplateStorage documentStorage;
    @Autowired RollingPackagePlanGenerationService planGeneration;
    @Autowired RollingPackageService rolling;
    @Autowired DocumentDevelopmentScope developmentScopes;
    @Autowired AssistMapper assist;
    @Autowired DocumentTemplateModelMapper models;
    @Autowired DocumentModelExecution modelExecution;
    @Autowired GitEvidenceProcess git;
    @Autowired DesignerConversationCoordinator conversations;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired DocumentDevelopmentMapper bindings;
    @Autowired LoopperMapper domain;
    @Autowired ProjectService projects;
    @Autowired LoopperProperties properties;
    @Autowired OpenCodeClient client;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temporary;
    private DocumentTemplateRunRow run;
    private DocumentTemplateService.Contract contract;
    private Path source;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); ((FakeOpenCodeClient) client).reset();
        properties.getOpenCode().setModel("fake/test-model");
        source = Files.createDirectory(temporary.resolve("source")); Files.writeString(source.resolve("existing.txt"), "用户未提交内容");
        String project = projects.create("文档开发", source.toString(), "test").id();
        run = LegacyDocumentFixture.create(applicationContext, new DocumentTemplateService.Request(UUID.randomUUID().toString(), "REQUIREMENT_DEVELOPMENT", "1", project, null),
                List.of(new DocumentTemplateStorage.Incoming("需求.md", "# 需求\n付款前必须鉴权".getBytes(StandardCharsets.UTF_8))));
        contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
        admission.transition(run, DocumentTemplateState.REVIEWING, LifecycleEvent.REVIEW_DOCUMENT_REQUIREMENTS, null, null);
        requirements.insertRevision(new DocumentRequirementMapper.Revision(run.id(), 1, "fixture-reviewed-manifest", "[]", Instant.now().toString()));
        var current = admission.require(run.id());
        requirements.bindRevision(run.id(), current.version(), 1, 0, Instant.now().toString());
        run = admission.transition(admission.require(run.id()), DocumentTemplateState.DESIGNING, LifecycleEvent.DESIGN_DOCUMENT_REQUIREMENTS, null, null);
    }
    @Test void frozenLongRequirementsCreateOneDesignerWithoutRouterTaskLeaseOrProjectWrite() throws Exception {
        String statement = "先校验操作权限再付款。".repeat(4000); add("RQ-1", statement, "[]");
        var first = bootstrap.create(run, contract); var again = bootstrap.create(run, contract);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(first.workflowPhase()).isEqualTo("DECOMPOSING");
        var revision = domain.listDesignRequirementRevisions(first.id()).getFirst();
        assertThat(revision.requirementText()).hasSizeLessThan(2000).contains("LOOPPER_DOCUMENT_REQUIREMENT_INDEX_V1");
        assertThat(revision.requirementSegmentsJson()).contains("RQ-1").doesNotContain(statement);
        assertThat(requirements.item(run.id(), 1, "RQ-1").orElseThrow().statement()).isEqualTo(statement);
        assertThat(bindings.design(revision.id()).orElseThrow().documentRevision()).isEqualTo(1);
        var profile = domain.findCurrentDesignerTaskProfile(first.id()).orElseThrow();
        assertThat(profile.intent()).isEqualTo("SOFTWARE_CHANGE"); assertThat(profile.testPolicy()).isEqualTo("REQUIRED");
        assertThat(profile.workflowTemplate()).isEqualTo("DIRECT_SOFTWARE_DESIGN"); assertThat(profile.state()).isEqualTo("FROZEN");
        assertThat(profile.decisionRequired()).isZero();
        assertThat(admission.require(run.id()).designerId()).isEqualTo(first.id());
        for (String table : List.of("task", "workspace_lease", "task_profile_router_run"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM designer_session", Integer.class)).isEqualTo(1);
        assertThat(((FakeOpenCodeClient) client).createSessionCalls()).isZero();
        assertThat(Files.readString(source.resolve("existing.txt"))).isEqualTo("用户未提交内容");
    }
    @Test void documentPackageUsesFrozenRequirementBodiesAndScopedReferences() {
        add("RQ-1", "付款前必须鉴权；无权限不调用扣款服务", "[]");
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (FakeOpenCodeClient) client; fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY, true);
        var designer = bootstrap.create(run, contract);
        designs.startSingle(designer.id()); designs.startSingle(designer.id());
        var revision = domain.listDesignRequirementRevisions(designer.id()).getFirst();
        var packages = domain.listDesignWorkPackages(revision.id()); assertThat(packages).hasSize(1);
        var original = DocumentRequirementContext.resolve(domain, revision, packages.getFirst());
        assertThat(original).contains("无权限不调用扣款服务");
        assertThat(PackageRequirementSources.index(original)).containsOnlyKeys("RQ-1", DocumentRequirementContext.FINAL_REGRESSION);
        assertThat(PackageRequirementSources.index(original).get("RQ-1").text()).contains("无权限不调用扣款服务", "无权限不能付款");
        assertThat(DocumentRequirementContext.prompt(original)).contains("RQ-1", "read_development_requirement").doesNotContain("无权限不调用扣款服务");
        assertThat(domain.findTaskByDraft(designer.loopDraftId())).isEmpty();
    }
    @Test void templateAuthorizationCannotEnableRecommendedBusinessAnswers() {
        add("RQ-1", "付款前必须鉴权", "[]");
        var designer = bootstrap.create(run, contract);
        assertThatThrownBy(() -> autoMode.setEnabled(designer.id(), true, 0)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("业务问题需要你回答");
        assertThatThrownBy(() -> autoMode.initialize(designer.id(), true)).isInstanceOf(ConflictException.class);
        assertThat(domain.findDesignerAutoMode(designer.id())).isEmpty();
    }
    @Test void supplementaryDocumentsPreserveOriginalRequirementsAndReplayOneAnalysisRevision() {
        add("RQ-1", "付款前必须鉴权", "[\"缺少审批规则\"]");
        development.advance(run, contract);
        var waiting = admission.require(run.id()); assertThat(waiting.state()).isEqualTo("WAITING_INPUT");
        var option = supplements.options(run.id()); assertThat(option.available()).isTrue();
        var files = List.of(new DocumentTemplateStorage.Incoming("审批.md", "# 审批\n超过1000元需要主管审批".getBytes(StandardCharsets.UTF_8)));
        var next = supplements.upload(run.id(), option.request(), files);
        assertThat(next.state()).isEqualTo("ANALYZING"); assertThat(next.requirementRevision()).isEqualTo(1);
        assertThat(supplements.upload(run.id(), option.request(), files).id()).isEqualTo(run.id());
        assertThat(runs.files(run.id())).hasSize(2);
        assertThat(clarifications.round(run.id())).isEqualTo(2);
        assertThat(requirements.item(run.id(), 1, "RQ-1").orElseThrow().issuesJson()).contains("缺少审批规则");
        requirementWorkflow.advance(next, contract);
        var model = models.latest(run.id(), "DOCUMENT_REQUIREMENTS_V1", 0).orElseThrow();
        assertThat(model.generation()).isEqualTo(contract.maxStageAttempts());
        assertThat(json.readValue(model.inputJson(), DocumentModelInput.class).sections()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM designer_session", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
        assertThatThrownBy(() -> supplements.upload(run.id(), option.request(), List.of(new DocumentTemplateStorage.Incoming(
                "审批.md", "不同规则".getBytes(StandardCharsets.UTF_8))))).isInstanceOf(ConflictException.class);
    }
    @Test void interruptedSupplementUploadWaitsForExactOriginalFilesAcrossRecovery() {
        add("RQ-1", "付款前必须鉴权", "[\"需要补充\"]"); development.advance(run, contract);
        var option = supplements.options(run.id());
        var incoming = List.of(new DocumentTemplateStorage.Incoming("补充.md", "# 补充\n金额必须为正数".getBytes(StandardCharsets.UTF_8)));
        var prepared = documentStorage.prepare(incoming);
        String digest = DocumentModelStore.hash(json.writeValueAsString(Map.of("request", option.request(), "files", prepared.stream()
                .map(file -> Map.of("filename", file.filename(), "sha256", file.sha256())).toList())));
        supplementAdmission.reserve(run.id(), option.request(), digest, prepared, new DocumentSupplementService.Anchor(null, 0, 0));
        coordinator.checkpoint(run.id());
        assertThat(admission.require(run.id()).state()).isEqualTo("PREPARING");
        assertThat(models.active(run.id())).isEmpty();
        assertThat(supplements.options(run.id()).request()).isEqualTo(option.request());
        assertThat(supplements.upload(run.id(), option.request(), incoming).state()).isEqualTo("ANALYZING");
        assertThat(supplementRows.pending(run.id()).orElseThrow().uploadReady()).isEqualTo(1);
        assertThat(runs.files(run.id())).hasSize(2);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void supplementReopensUnexecutedDesignWithNewSourceAndPreservesHistory(boolean recoverAnalysis) {
        add("RQ-1", "付款前必须鉴权", "[]");
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake=(FakeOpenCodeClient)client; fake.setManagedRuntime(credentials.generation(),credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY,true);
        var designer=bootstrap.create(run,contract); designs.startSingle(designer.id());
        var old=domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        var pack=domain.listDesignWorkPackages(old.id()).getFirst();
        String original=DocumentRequirementContext.resolve(domain,old,pack);
        jdbc.update("UPDATE designer_session SET state='WAITING_INPUT',version=version+1 WHERE id=?",designer.id());
        admission.transition(admission.require(run.id()),DocumentTemplateState.WAITING_INPUT,LifecycleEvent.REQUIRE_INPUT,"DOCUMENT_DEVELOPMENT_WAIT","业务规则待补充");
        var option=supplements.options(run.id()); assertThat(option.available()).as(option.message()).isTrue();
        var uploaded=supplements.upload(run.id(),option.request(),List.of(new DocumentTemplateStorage.Incoming("补充.md","# 补充\n审批后才付款".getBytes(StandardCharsets.UTF_8))));
        if(recoverAnalysis) {
            assertThat(supplementDesign.stopForAnalysis(uploaded)).isTrue();
            assertThat(domain.findCurrentDesignRequirementRevision(designer.id())).isEmpty();
            assertThat(domain.findDesignRequirementRevision(old.id()).orElseThrow().state()).isEqualTo("SUPERSEDED");
        }
        admission.transition(uploaded,DocumentTemplateState.REVIEWING,LifecycleEvent.REVIEW_DOCUMENT_REQUIREMENTS,null,null);
        requirements.insertRevision(new DocumentRequirementMapper.Revision(run.id(),2,"reviewed-supplement","[]",Instant.now().toString()));
        var item=requirements.item(run.id(),1,"RQ-1").orElseThrow();
        requirements.insert(new DocumentRequirementMapper.Requirement(run.id(),2,item.requirementKey(),item.ordinal(),item.title(),item.groupName(),item.kind(),
                "付款前必须鉴权并审批",item.sourcesJson(),item.acceptanceJson(),"[]"));
        requirements.bindRevision(run.id(),admission.require(run.id()).version(),2,1,Instant.now().toString());
        var next=admission.transition(admission.require(run.id()),DocumentTemplateState.DESIGNING,LifecycleEvent.DESIGN_DOCUMENT_REQUIREMENTS,null,null);
        var pending=supplementRows.pending(run.id()).orElseThrow();
        supplementDesign.advance(next,pending); supplementDesign.advance(next,pending);
        var revised=domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        assertThat(revised.id()).isNotEqualTo(old.id());
        assertThat(bindings.design(revised.id()).orElseThrow().documentRevision()).isEqualTo(2);
        assertThat(domain.findDesignRequirementRevision(old.id()).orElseThrow().state()).isEqualTo("SUPERSEDED");
        assertThat(DocumentRequirementContext.resolve(domain,old,pack)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isZero();
        assertThat(domain.listDesignRequirementRevisions(designer.id())).hasSize(2);
        assertThat(fake.abortedSessionIds()).contains(pack.designerExternalSessionId());
    }
    @Test void remainingPlanSourceDoesNotRewriteEarlierTaskOrPackageRequirements() {
        add("RQ-1", "旧版付款必须鉴权", "[]");
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (FakeOpenCodeClient) client; fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY, true);
        var designer = bootstrap.create(run, contract); designs.startSingle(designer.id());
        var revision = domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        var original = domain.listDesignWorkPackages(revision.id()).getFirst();
        String before = DocumentRequirementContext.resolve(domain, revision, original);
        String now = Instant.now().toString(); String taskId = UUID.randomUUID().toString();
        domain.insertTask(new TaskRow(taskId, run.projectId(), designer.loopDraftId(), "版本隔离夹具", "PENDING_START", null, null, null, now, now, 0));
        var current = admission.require(run.id());
        assertThat(bindings.linkTask(run.id(), current.version(), designer.id(), taskId, now)).isEqualTo(1);
        assertThat(domain.freezeDocumentTaskSource(taskId)).isEqualTo(1);
        requirements.insertRevision(new DocumentRequirementMapper.Revision(run.id(), 2, "new-manifest", "[]", now));
        var old = requirements.item(run.id(), 1, "RQ-1").orElseThrow();
        requirements.insert(new DocumentRequirementMapper.Requirement(run.id(), 2, "RQ-1", 0, old.title(), old.groupName(), old.kind(),
                "新版增加付款审批", old.sourcesJson(), "[\"鉴权和审批均通过\"]", "[]"));
        current = admission.require(run.id());
        assertThat(requirements.bindRevision(run.id(), current.version(), 2, 1, now)).isEqualTo(1);
        String planId = UUID.randomUUID().toString();
        domain.insertTaskPackagePlanRevision(new TaskPackagePlanRevisionRow(planId, taskId, designer.id(), revision.id(), 2,
                "PROPOSED", "[]", "{}", now, null, null, 0));
        assertThat(domain.freezeDocumentPlanSource(planId)).isEqualTo(1);
        var modified = (tools.jackson.databind.node.ObjectNode) json.valueToTree(original);
        modified.put("id", UUID.randomUUID().toString()); modified.put("planRevision", 2);
        var next = json.treeToValue(modified, DesignWorkPackageRow.class); domain.insertDesignWorkPackage(next);
        domain.insertTaskPackageRun(new TaskPackageRunRow(UUID.randomUUID().toString(), taskId, planId, next.id(), "WP-1", 0,
                "后续版本", "PLANNED", null, 0, 0, null, null, now, now, 0));
        assertThat(DocumentRequirementContext.resolve(domain, revision, original)).isEqualTo(before);
        assertThat(DocumentRequirementContext.resolve(domain, revision, next)).contains("新版增加付款审批", "new-manifest").doesNotContain("旧版付款必须鉴权");
        assertThat(domain.documentTaskRevision(taskId)).isEqualTo(1);
        assertThat(domain.documentPlanSource(planId).orElseThrow().documentRevision()).isEqualTo(2);
        assertThat(domain.documentPlanSourceCurrent(planId)).isTrue();
        requirements.insertRevision(new DocumentRequirementMapper.Revision(run.id(), 3, "later-manifest", "[]", now));
        current = admission.require(run.id()); requirements.bindRevision(run.id(), current.version(), 3, 2, now);
        assertThat(domain.documentPlanSourceCurrent(planId)).isFalse();
        assertThat(domain.freezeDocumentPlanSource(planId)).isZero();
        assertThat(domain.documentPlanSource(planId).orElseThrow().documentRevision()).isEqualTo(2);
        assertThat(domain.documentTaskRevision(taskId)).isEqualTo(1);
    }
    @Test void cancellationSurvivesUnconfirmedDesignerStopWithoutCreatingTaskOrChangingSource() throws Exception {
        add("RQ-1", "付款前必须鉴权", "[]");
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (FakeOpenCodeClient) client; fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY, true);
        var designer = bootstrap.create(run, contract); designs.startSingle(designer.id());
        int calls = fake.createSessionCalls();
        var current = admission.require(run.id());
        var request = new DocumentTemplateControl.Command(UUID.randomUUID().toString(), current.version());
        controls.command(run.id(), "cancel", request); fake.failNextAborts(100);
        coordinator.checkpoint(run.id());
        assertThat(admission.require(run.id()).state()).isEqualTo("STOPPING");
        assertThat(controls.command(run.id(), "cancel", request).state()).isEqualTo("STOPPING");
        coordinator.checkpoint(run.id()); assertThat(admission.require(run.id()).state()).isEqualTo("STOPPING");
        assertThat(fake.createSessionCalls()).isEqualTo(calls);
        fake.failNextAborts(0);
        for (int i = 0; i < 5 && !admission.require(run.id()).state().equals("CANCELLED"); i++) coordinator.checkpoint(run.id());
        assertThat(admission.require(run.id()).state()).isEqualTo("CANCELLED");
        assertThat(designers.get(designer.id()).state()).isEqualTo("CANCELLED");
        assertThat(domain.findTaskByDraft(designer.loopDraftId())).isEmpty();
        assertThat(Files.readString(source.resolve("existing.txt"))).isEqualTo("用户未提交内容");
    }
    @Test void documentDesignKeepsFrozenModelAndV2SourceContractWhenGlobalSettingsChange() {
        add("RQ-1", "付款前必须鉴权", "[]");
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (FakeOpenCodeClient) client; fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        var designer = bootstrap.create(run, contract);
        properties.getOpenCode().setModel("fake/changed-model"); properties.getInternalCandidate().setPackageDesignV2Enabled(false);
        try {
            designs.startSingle(designer.id());
            var conversation = conversations.history(designer.id()).getFirst();
            assertThat(conversation.profile()).isEqualTo("PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY");
            assertThat(fake.modelForSession(conversation.externalSessionId()).modelId()).isEqualTo("test-model");
        } finally { properties.getInternalCandidate().setPackageDesignV2Enabled(true); }
    }
    @Test void provenCapacityFailurePromotesSameDocumentsAfterStoppingAndPreservesBudget() {
        add("RQ-1", "付款前必须鉴权", "[]");
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (FakeOpenCodeClient) client; fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        var designer = bootstrap.create(run, contract); designs.startSingle(designer.id());
        var before = domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        jdbc.update("UPDATE design_requirement_revision SET model_calls_used=7 WHERE id=?", before.id());
        jdbc.update("UPDATE designer_session SET state='WAITING_INPUT',version=version+1 WHERE id=?", designer.id());
        jdbc.update("UPDATE design_work_package SET state='WAITING_INPUT',last_error_code='LARGE_TASK_MODE_REQUIRED',version=version+1 WHERE designer_session_id=?", designer.id());
        promotion.advance(run.id()); promotion.advance(run.id());
        var after = domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        assertThat(after.id()).isNotEqualTo(before.id()); assertThat(after.revision()).isEqualTo(2);
        assertThat(after.requirementSegmentsJson()).isEqualTo(before.requirementSegmentsJson());
        assertThat(after.modelCallsUsed()).isEqualTo(7); assertThat(after.maxModelCalls()).isEqualTo(before.maxModelCalls());
        assertThat(domain.findDesignRequirementRevision(before.id()).orElseThrow().state()).isEqualTo("SUPERSEDED");
        assertThat(bindings.design(after.id()).orElseThrow().documentRevision()).isEqualTo(1);
        var profile = domain.findCurrentDesignerTaskProfile(designer.id()).orElseThrow();
        assertThat(profile.workflowTemplate()).isEqualTo("FULL_PACKAGE_DESIGN"); assertThat(profile.testPolicy()).isEqualTo("REQUIRED");
        assertThat(domain.findTaskByDraft(designer.loopDraftId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_profile_router_run", Integer.class)).isZero();
        assertThat(promotion.pending(run.id())).isFalse();
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void singlePackageRunsCurrentDirectoryTestsAndReportsWithoutAcceptingResult(boolean gitProject) throws Exception {
        prepareMavenProject(gitProject);
        add("RQ-1", "工作包范围：`src/test/java/example/EventBusTest.java`。新增 EventBusTest 聚焦验证未注册事件安全忽略，并回归既有分发行为。", "[]");
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (FakeOpenCodeClient) client; fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY, true);
        development.advance(admission.require(run.id()), contract);
        development.advance(admission.require(run.id()), contract);
        var designer = designers.get(admission.require(run.id()).designerId());
        var revision = domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        var pack = domain.listDesignWorkPackages(revision.id()).getFirst();
        var candidateRun = domain.findLatestCandidateSubmissionRunForWorkPackage(pack.id(), pack.designRevision() + 1L).orElseThrow();
        var fixture = new PackageDesignV2CompilationTest(); var candidate = fixture.candidate();
        ((tools.jackson.databind.node.ObjectNode) candidate.path("sourceBindings").get(0))
                .set("sourceRefs", json.valueToTree(List.of("RQ-1", DocumentRequirementContext.FINAL_REGRESSION)));
        var submitted = submissions.submit(new MachineCandidateSubmission.SubmitCommand(candidateRun.id(), "document-package-fixture",
                json.writeValueAsString(candidate), candidateRun.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
        assertThat(submitted.outcome()).as("%s", submitted.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        fake.setSessionState(candidateRun.externalSessionId(), "COMPLETED");
        for (int i = 0; i < 12 && admission.require(run.id()).taskId() == null; i++) {
            designers.pollActiveHandoffs(); development.advance(admission.require(run.id()), contract);
        }
        var current = admission.require(run.id());
        assertThat(current.state()).as("%s", current.waitingMessage()).isEqualTo("EXECUTING");
        var task = domain.findTask(current.taskId()).orElseThrow();
        assertThat(task.state()).isEqualTo("PENDING_START"); assertThat(task.executionMode()).isNotEqualTo("TEMPLATE_REPORT");
        assertThat(domain.listStages(task.id())).isNotEmpty().allSatisfy(stage -> assertThat(stage.testPolicy()).isEqualTo("REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isEqualTo(1);
        assertThat(Files.readString(source.resolve("existing.txt"))).isEqualTo("用户未提交内容");
        development.advance(current, contract);
        task = tasks.get(task.id());
        assertThat(task.state()).as("%s", tasks.errors(task.id())).isEqualTo("RUNNING");
        assertThat(Path.of(task.worktreePath()).toRealPath()).isEqualTo(source.toRealPath());
        if (gitProject) {
            assertThat(task.branchName()).isNotEqualTo("main");
            assertThat(git.read(source, "branch", "--show-current").trim()).isEqualTo(task.branchName());
            String previous=domain.activeSessions(task.id()).getFirst().externalSessionId();
            tasks.recoverAfterRestart();
            assertThat(tasks.get(task.id()).state()).isEqualTo("RETRY_WAIT");
            assertThat(fake.abortedSessionIds()).contains(previous);
            assertThat(tasks.attempts(task.id())).hasSize(1);
            jdbc.update("UPDATE task_retry_schedule SET due_at=? WHERE task_id=? AND state='SCHEDULED'",Instant.EPOCH.toString(),task.id());
            tasks.startDueRetries();
            assertThat(tasks.get(task.id()).state()).isEqualTo("RUNNING");
            assertThat(domain.activeSessions(task.id())).hasSize(1).allSatisfy(session->assertThat(session.externalSessionId()).isNotEqualTo(previous));
            assertThat(domain.documentTaskRevision(task.id())).isEqualTo(1);
            assertThat(admission.require(run.id()).taskId()).isEqualTo(task.id());
        }
        // Controlled implementation fixture writes executable assertions; the formal verifier runs this native entry.
        Path test = source.resolve("src/test/java/example/EventBusTest.java"); Files.createDirectories(test.getParent());
        Files.writeString(test, """
                import java.util.*;
                class EventBusTest {
                    @org.junit.jupiter.api.Test void preservesUnknownAndRegisteredEvents() {
                        var handlers = new HashMap<String, Runnable>();
                        int[] calls = {0}; handlers.put("registered", () -> calls[0]++);
                        handlers.getOrDefault("missing", () -> {}).run();
                        if (calls[0] != 0) throw new AssertionError("unknown event must be ignored");
                        handlers.getOrDefault("registered", () -> {}).run();
                        if (calls[0] != 1) throw new AssertionError("existing dispatch must be preserved");
                        System.out.println("EventBusTest: unknown and existing event assertions passed");
                    }
                }
                """);
        properties.getInternalCandidate().setJudgeDecisionV1Enabled(false);
        try {
            tasks.verify(task.id()); tasks.pollJudges(task.id());
            assertThat(tasks.get(task.id()).state()).as("%s", tasks.verifications(domain.latestAttempt(domain.listStages(task.id()).getFirst().id()).orElseThrow().id())).isEqualTo("AWAITING_DECISION");
            development.advance(admission.require(run.id()), contract);
            assertThat(admission.require(run.id()).state()).as("%s", admission.require(run.id()).waitingMessage()).isEqualTo("REPORTING");
            development.advance(admission.require(run.id()), contract);
            assertThat(admission.require(run.id()).state()).as("%s", admission.require(run.id()).waitingMessage()).isEqualTo("COMPLETED");
            assertThat(reports.named(run.id(), "matrix.json").content()).contains("ACCEPTED_BY_EXECUTION_AND_DUAL_JUDGES", "RQ-1");
            assertThat(tasks.get(task.id()).state()).isEqualTo("AWAITING_DECISION");
        } finally { properties.getInternalCandidate().setJudgeDecisionV1Enabled(true); }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rollingTemplateStartsEachPackageAndFinalRegressionRejectsEarlierBreakage(boolean breakEarlier) throws Exception {
        prepareMavenProject(true);
        add("RQ-1", "工作包范围：`src/test/java/example/EventBusTest.java`。新增事件分发测试，未注册事件安全忽略。", "[]");
        add("RQ-2", "工作包范围：`src/test/java/example/RegressionTest.java`、`src/test/java/example/EventBusTest.java`。更新 EventBusTest 的共享行为并新增整体回归，保留未注册事件和既有已注册事件分发。", "[]");
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (FakeOpenCodeClient) client; fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY, true);
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_V2_INTERACTIVE_READ_ONLY, true);
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY, true);
        var designer = bootstrap.create(run, contract); designs.startSingle(designer.id());
        jdbc.update("UPDATE designer_session SET state='WAITING_INPUT',version=version+1 WHERE id=?", designer.id());
        jdbc.update("UPDATE design_work_package SET state='WAITING_INPUT',last_error_code='LARGE_TASK_MODE_REQUIRED',version=version+1 WHERE designer_session_id=?", designer.id());
        development.advance(admission.require(run.id()), contract); development.advance(admission.require(run.id()), contract);
        var revision = domain.findCurrentDesignRequirementRevision(designer.id()).orElseThrow();
        var decomposition = domain.findTaskDecompositionByRevision(revision.id()).orElseThrow();
        String candidateId = jdbc.queryForObject("SELECT id FROM ai_candidate_submission_run WHERE owner_type='TASK_DECOMPOSITION' AND owner_id=? ORDER BY created_at DESC LIMIT 1", String.class, decomposition.id());
        var decomposer = submissions.find(candidateId).orElseThrow();
        String plan = """
                {"outcome":"READY","normalizedGoal":"分包交付事件安全与最终回归","globalConstraints":[],
                 "workPackages":[
                  {"title":"事件安全分发","objective":"验证未注册事件安全忽略","scopeIn":["src/test/java/example/EventBusTest.java"],"scopeOut":[],"deliverables":["EventBusTest"],"acceptanceIntent":["未注册事件正常返回"],"dependsOn":[]},
                  {"title":"整体事件回归","objective":"在最后验证既有分发和前包行为","scopeIn":["src/test/java/example/RegressionTest.java","src/test/java/example/EventBusTest.java"],"scopeOut":[],"deliverables":["RegressionTest","EventBusTest"],"acceptanceIntent":["前包和已注册事件均保持正确"],"dependsOn":[{"packageIndex":0,"rationale":"使用第一包事件行为"}]}],
                 "coverage":[{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE","targetIndex":0,"rationale":"第一包事件行为"},{"requirementRef":"RQ-2","targetType":"WORK_PACKAGE","targetIndex":1,"rationale":"最后进行整体回归"}],"designGaps":[],"reason":null}
                """;
        var submitted = submissions.submit(new MachineCandidateSubmission.SubmitCommand(candidateId, UUID.randomUUID().toString(), plan,
                decomposer.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP, MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
        assertThat(submitted.outcome()).as("%s", submitted.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        fake.setSessionState(decomposer.externalSessionId(), "COMPLETED");
        for (int i = 0; i < 10 && domain.listDesignWorkPackages(revision.id()).isEmpty(); i++) designers.pollActiveHandoffs();
        var packages = domain.listDesignWorkPackages(revision.id()); assertThat(packages).hasSize(2);
        properties.getInternalCandidate().setJudgeDecisionV1Enabled(false);
        try {
            for (int index = 0; index < 2; index++) {
                var pack = packages.get(index);
                for (int i = 0; i < 12; i++) {
                    designers.pollActiveHandoffs(); development.advance(admission.require(run.id()), contract);
                    var refreshed = domain.findDesignWorkPackage(pack.id()).orElseThrow();
                    if (domain.findLatestCandidateSubmissionRunForWorkPackage(pack.id(), refreshed.designRevision() + 1L).isPresent()) break;
                }
                pack = domain.findDesignWorkPackage(pack.id()).orElseThrow();
                var candidateRun = domain.findLatestCandidateSubmissionRunForWorkPackage(pack.id(), pack.designRevision() + 1L).orElseThrow();
                var candidate = new PackageDesignV2CompilationTest().candidate();
                String testName = index == 0 ? "EventBusTest" : "RegressionTest";
                ((tools.jackson.databind.node.ObjectNode) candidate.path("deliverables").get(0)).put("target", "src/test/java/example/" + testName + ".java").put("description", "新增 " + testName + " 验证事件行为");
                ((tools.jackson.databind.node.ObjectNode) candidate.path("sourceBindings").get(0)).set("sourceRefs", json.valueToTree(index == 0 ? List.of("RQ-1") : List.of("RQ-2", DocumentRequirementContext.FINAL_REGRESSION)));
                if (index == 1) {
                    ((tools.jackson.databind.node.ObjectNode) candidate.path("scenarios").get(0))
                            .put("action", "运行 RegressionTest 并发布未注册和已注册事件")
                            .put("observableResult", "RegressionTest 断言未注册事件没有处理器调用且已注册事件保持一次调用");
                    var extra = ((tools.jackson.databind.node.ArrayNode) candidate.path("deliverables")).addObject();
                    extra.put("key", "DEL-2").put("kind", "DELIVERABLE").put("target", "src/test/java/example/EventBusTest.java")
                            .put("description", "更新 EventBusTest 共享事件行为").set("requirementRefs", json.valueToTree(List.of("REQ-1")));
                    ((tools.jackson.databind.node.ArrayNode) candidate.path("stages").get(0).path("includes")).add("DEL-2");
                    ((tools.jackson.databind.node.ArrayNode) candidate.path("sourceBindings").get(0).path("candidateRefs")).add("DEL-2");
                }
                submitted = submissions.submit(new MachineCandidateSubmission.SubmitCommand(candidateRun.id(), UUID.randomUUID().toString(), json.writeValueAsString(candidate),
                        candidateRun.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP, MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
                assertThat(submitted.outcome()).as("%s", submitted.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
                fake.setSessionState(candidateRun.externalSessionId(), "COMPLETED");
                for (int i = 0; i < 12; i++) {
                    designers.pollActiveHandoffs(); development.advance(admission.require(run.id()), contract);
                    var linked = admission.require(run.id());
                    if (linked.taskId() != null && tasks.get(linked.taskId()).state().equals("RUNNING")) break;
                }
                var current = admission.require(run.id());
                assertThat(current.state()).as("%s", current.waitingMessage()).isEqualTo("EXECUTING");
                var task = tasks.get(current.taskId()); assertThat(task.state()).as("%s", tasks.errors(task.id())).isEqualTo("RUNNING");
                assertThat(task.executionMode()).isEqualTo("ROLLING_PACKAGES");
                assertThat(Path.of(task.worktreePath()).toRealPath()).isEqualTo(source.toRealPath());
                Path test = source.resolve("src/test/java/example/" + testName + ".java"); Files.createDirectories(test.getParent());
                Files.writeString(test, index == 0 ? """
                        class EventBusTest {
                            static int dispatch(boolean registered) { return registered ? 1 : 0; }
                            @org.junit.jupiter.api.Test void unknownEventIsIgnored() { if (dispatch(false) != 0) throw new AssertionError(); }
                        }
                        """ : """
                        class RegressionTest {
                            @org.junit.jupiter.api.Test void earlierAndRegisteredBehaviorRemainCorrect() {
                                if (EventBusTest.dispatch(false) != 0 || EventBusTest.dispatch(true) != 1) throw new AssertionError("cross-package regression");
                            }
                        }
                        """);
                if (index == 1) {
                    Path earlier = source.resolve("src/test/java/example/EventBusTest.java");
                    String content = Files.readString(earlier);
                    Files.writeString(earlier, breakEarlier ? content.replace("registered ? 1 : 0", "registered ? 1 : 9") : content + "\n// Included in final regression.\n");
                }
                tasks.verify(task.id());
                if (index == 1 && breakEarlier) {
                    var last = domain.listStages(task.id()).getLast();
                    assertThat(tasks.verifications(domain.latestAttempt(last.id()).orElseThrow().id())).anySatisfy(result -> {
                        assertThat(result.type()).isEqualTo("PROCESS"); assertThat(result.state()).isEqualTo("FAIL");
                        assertThat(result.evidenceJson()).contains("cross-package regression");
                    });
                    development.advance(admission.require(run.id()), contract);
                    assertThat(admission.require(run.id()).state()).isNotEqualTo("COMPLETED");
                    assertThat(tasks.judges(task.id())).isEmpty(); assertThat(reports.list(run.id(), "", 100).items()).isEmpty();
                    verifySupplementReplansRemainingPackage(task.id());
                    return;
                }
                if (index == 0) {
                    assertThat(domain.listPackageFactSnapshots(task.id())).hasSize(1);
                    assertThat(tasks.judges(task.id())).isEmpty();
                } else tasks.pollJudges(task.id());
            }
            development.advance(admission.require(run.id()), contract); development.advance(admission.require(run.id()), contract);
            var finished = admission.require(run.id());
            assertThat(finished.state()).as("%s", finished.waitingMessage()).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isEqualTo(1);
            assertThat(reports.named(run.id(), "matrix.json").content()).contains("RQ-1", "RQ-2", "RegressionTest");
        } finally { properties.getInternalCandidate().setJudgeDecisionV1Enabled(true); }
    }
    @Test void ordinaryBusinessGapDoesNotAuthorizeLargeModePromotion() {
        add("RQ-1", "付款前必须鉴权", "[]"); bootstrap.create(run, contract);
        assertThatThrownBy(() -> promotion.advance(run.id())).isInstanceOf(ConflictException.class);
        assertThat(bindings.promotion(run.id())).isEmpty();
    }
    private void verifySupplementReplansRemainingPackage(String taskId) throws Exception {
        for (int i = 0; i < contract.maxStageAttempts(); i++) {
            if (tasks.get(taskId).state().equals("RETRY_WAIT")) {
                // Advance only the fixture retry clock; normal retry/verify owns every transition.
                jdbc.update("UPDATE task_retry_schedule SET due_at=? WHERE task_id=? AND state='SCHEDULED'", Instant.EPOCH.toString(), taskId);
                tasks.startDueRetries();
            } else if (tasks.get(taskId).state().equals("WAITING_INPUT")
                    && domain.currentTaskPackageRun(taskId).orElseThrow().state().equals("VERIFYING")) {
                // Explicit user continuation of loop-noise protection, not package failure or new scope approval.
                tasks.retryWaitingLoop(taskId);
            } else break;
            tasks.verify(taskId);
        }
        development.advance(admission.require(run.id()), contract);
        assertThat(admission.require(run.id()).state()).as("task=%s, reason=%s", tasks.get(taskId).state(), admission.require(run.id()).waitingMessage()).isEqualTo("WAITING_INPUT");
        var facts = domain.listPackageFactSnapshots(taskId);
        var frozen = domain.listTaskPackageRuns(taskId).stream().filter(pack -> pack.state().equals("FACT_FROZEN")).findFirst().orElseThrow();
        var before = domain.findDesignWorkPackage(frozen.designWorkPackageId()).orElseThrow();
        var sourceRevision = domain.findDesignRequirementRevision(before.requirementRevisionId()).orElseThrow();
        String original = DocumentRequirementContext.resolve(domain, sourceRevision, before);
        var option = supplements.options(run.id()); assertThat(option.available()).as("%s; %s", option.message(),
                rolling.policyContext(tasks.get(taskId), domain.currentTaskPackageRun(taskId).orElseThrow())).isTrue();
        var incoming = List.of(new DocumentTemplateStorage.Incoming("回归补充.md", "# 回归补充\n最终回归必须保留前包的未注册事件行为。".getBytes(StandardCharsets.UTF_8)));
        var uploaded = supplements.upload(run.id(), option.request(), incoming);
        assertThat(uploaded.state()).isEqualTo("ANALYZING");
        // The separately tested extraction/review protocol publishes this annotated revision fixture.
        admission.transition(uploaded, DocumentTemplateState.REVIEWING, LifecycleEvent.REVIEW_DOCUMENT_REQUIREMENTS, null, null);
        requirements.insertRevision(new DocumentRequirementMapper.Revision(run.id(), 2, "supplement-reviewed", "[]", Instant.now().toString()));
        for (var item : requirements.page(run.id(), 1, -1, 100)) requirements.insert(new DocumentRequirementMapper.Requirement(run.id(), 2,
                item.requirementKey(), item.ordinal(), item.title(), item.groupName(), item.kind(), item.statement(), item.sourcesJson(), item.acceptanceJson(), item.issuesJson()));
        var current = admission.require(run.id()); requirements.bindRevision(run.id(), current.version(), 2, 1, Instant.now().toString());
        admission.transition(admission.require(run.id()), DocumentTemplateState.DESIGNING, LifecycleEvent.DESIGN_DOCUMENT_REQUIREMENTS, null, null);
        var fake = (FakeOpenCodeClient) client; fake.holdProfileOpen(OpenCodeClient.SessionProfile.ROLLING_PACKAGE_CANDIDATE_READ_ONLY, true);
        development.advance(admission.require(run.id()), contract);
        var pending = supplementRows.pending(run.id()).orElseThrow(); assertThat(pending.planId()).isNotNull();
        for (int i = 0; i < 6; i++) planGeneration.pollGenerating();
        var planned = domain.findTaskPackagePlanRevision(pending.planId()).orElseThrow();
        assertThat(planned.externalSessionState()).as(planned.lastErrorDetail()).isEqualTo("RUNNING");
        // FakeOpenCodeClient omits the HTTP transport's persisted permission manifest.
        var creation = json.readValue(jdbc.queryForObject("SELECT creation_plan_json FROM document_plan_transport WHERE plan_id=?",
                String.class, planned.id()), OpenCodeClient.SessionCreationPlan.class);
        assist.insertSession(new AssistMapper.Session(planned.externalSessionId(), creation.runtimeGenerationId(),
                creation.canonicalDirectory().toString(), creation.profile().name(), "[]", "[]", Instant.now().toString()));
        var promptScope = new HashMap<String, Object>(); developmentScopes.enrich(planned.externalSessionId(), promptScope);
        assertThat(promptScope.get("system")).as("frozen supplement capability").isNotNull();
        assertThat(promptScope.get("system").toString()).contains("冻结需求版本=2");
        String candidateId = jdbc.queryForObject("SELECT id FROM ai_candidate_submission_run WHERE owner_id=?", String.class, planned.id());
        var candidate = submissions.find(candidateId).orElseThrow();
        String proposal = """
                {"packages":[{"packageKey":"WP-2","title":"补充后的最终回归","objective":"修复第二包并回归全部需求",
                  "replaces":["WP-2"],"dependencies":["WP-1"],"requirementRefs":["RQ-1","RQ-2"]}]}
                """;
        var outcome = submissions.submit(new MachineCandidateSubmission.SubmitCommand(candidateId, UUID.randomUUID().toString(), proposal,
                candidate.version(), MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP, MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
        assertThat(outcome.outcome()).as("%s", outcome.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        fake.setSessionState(planned.externalSessionId(), "COMPLETED"); planGeneration.pollGenerating();
        for (int i = 0; i < 4 && supplementRows.pending(run.id()).isPresent(); i++) development.advance(admission.require(run.id()), contract);
        assertThat(admission.require(run.id()).state()).as(admission.require(run.id()).waitingMessage()).isEqualTo("EXECUTING");
        assertThat(supplementRows.pending(run.id())).isEmpty();
        assertThat(domain.listPackageFactSnapshots(taskId)).isEqualTo(facts);
        assertThat(domain.findTaskPackageRun(frozen.id()).orElseThrow()).isEqualTo(frozen);
        assertThat(DocumentRequirementContext.resolve(domain, sourceRevision, before)).isEqualTo(original);
        var remaining = domain.currentTaskPackageRun(taskId).orElseThrow();
        assertThat(remaining.planRevisionId()).isEqualTo(planned.id());
        assertThat(domain.documentPackageDesign(remaining.designWorkPackageId(), run.designerId() == null ? admission.require(run.id()).designerId() : run.designerId())
                .orElseThrow().documentRevision()).isEqualTo(2);
        finishSupplementPackage(taskId,remaining.designWorkPackageId());
    }
    private void finishSupplementPackage(String taskId,String packageId) throws Exception {
        var fake=(FakeOpenCodeClient)client;
        for(int i=0;i<15;i++) {
            development.advance(admission.require(run.id()),contract); designers.pollActiveHandoffs();
            var pack=domain.findDesignWorkPackage(packageId).orElseThrow();
            if(domain.findLatestCandidateSubmissionRunForWorkPackage(pack.id(),pack.designRevision()+1L).isPresent()) break;
        }
        var pack=domain.findDesignWorkPackage(packageId).orElseThrow();
        var candidateRun=domain.findLatestCandidateSubmissionRunForWorkPackage(pack.id(),pack.designRevision()+1L).orElseThrow();
        var candidate=new PackageDesignV2CompilationTest().candidate();
        ((tools.jackson.databind.node.ObjectNode)candidate.path("deliverables").get(0)).put("target","src/test/java/example/RegressionTest.java")
                .put("description","新增 RegressionTest 验证全部事件行为");
        var extra=((tools.jackson.databind.node.ArrayNode)candidate.path("deliverables")).addObject();
        extra.put("key","DEL-2").put("kind","DELIVERABLE").put("target","src/test/java/example/EventBusTest.java")
                .put("description","修复事件分发并保留原测试").set("requirementRefs",json.valueToTree(List.of("REQ-1")));
        ((tools.jackson.databind.node.ArrayNode)candidate.path("stages").get(0).path("includes")).add("DEL-2");
        ((tools.jackson.databind.node.ArrayNode)candidate.path("sourceBindings").get(0).path("candidateRefs")).add("DEL-2");
        ((tools.jackson.databind.node.ObjectNode)candidate.path("sourceBindings").get(0)).set("sourceRefs",
                json.valueToTree(List.of("RQ-1","RQ-2",DocumentRequirementContext.FINAL_REGRESSION)));
        ((tools.jackson.databind.node.ObjectNode)candidate.path("scenarios").get(0)).put("action","执行全部事件与跨包回归")
                .put("observableResult","未注册事件不调用处理器，已注册事件仍调用一次，RegressionTest 通过");
        var result=submissions.submit(new MachineCandidateSubmission.SubmitCommand(candidateRun.id(),UUID.randomUUID().toString(),json.writeValueAsString(candidate),
                candidateRun.version(),MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
        assertThat(result.outcome()).as("%s",result.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        fake.setSessionState(candidateRun.externalSessionId(),"COMPLETED");
        for(int i=0;i<15 && !tasks.get(taskId).state().equals("RUNNING");i++) {
            designers.pollActiveHandoffs(); development.advance(admission.require(run.id()),contract);
        }
        assertThat(tasks.get(taskId).state()).as("current=%s; latestError=%s; stages=%s",domain.currentTaskPackageRun(taskId).orElseThrow(),
                tasks.errors(taskId).getFirst(),domain.listStages(taskId).stream().map(stage->stage.id()+":"+stage.state()).toList()).isEqualTo("RUNNING");
        Path earlier=source.resolve("src/test/java/example/EventBusTest.java");
        Files.writeString(earlier,Files.readString(earlier).replace("registered ? 1 : 9","registered ? 1 : 0")+"\n// Supplement regression.\n");
        Files.writeString(source.resolve("src/test/java/example/RegressionTest.java"),"""
                class RegressionTest {
                    @org.junit.jupiter.api.Test void allPackageBehavior() {
                        if(EventBusTest.dispatch(false)!=0 || EventBusTest.dispatch(true)!=1) throw new AssertionError("supplement regression");
                    }
                }
                """);
        tasks.verify(taskId); tasks.pollJudges(taskId);
        for(int i=0;i<4;i++) development.advance(admission.require(run.id()),contract);
        assertThat(admission.require(run.id()).state()).as(admission.require(run.id()).waitingMessage()).isEqualTo("COMPLETED");
        assertThat(reports.named(run.id(),"matrix.json").content()).contains("RQ-1","RQ-2","ACCEPTED_BY_EXECUTION_AND_DUAL_JUDGES");
        assertThat(tasks.get(taskId).state()).isEqualTo("AWAITING_DECISION");
    }
    @Test void unresolvedBusinessRulesPreventDesignAndDoNotAdoptAnyRecommendation() {
        add("RQ-1", "付款超过阈值需要审批", "[\"阈值没有业务依据；推荐 1000 只是候选意见\"]");
        assertThatThrownBy(() -> bootstrap.create(run, contract)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("未解决的业务问题");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loop_draft", Integer.class)).isZero();
        assertThat(admission.require(run.id()).designerId()).isNull();
    }
    @Test void businessAnswerCreatesReviewedNewRevisionWithoutEditingOldRequirementsOrDuplicatingRequest() {
        add("RQ-1", "付款前必须鉴权，付款审批阈值未确定", "[\"付款审批阈值待业务明确\"]");
        development.advance(admission.require(run.id()), contract);
        var waiting = admission.require(run.id()); assertThat(waiting.state()).isEqualTo("WAITING_INPUT");
        var request = new DocumentRequirementClarifications.Request(UUID.randomUUID().toString(), waiting.version(), 1,
                List.of(new DocumentRequirementClarifications.Answer("RQ-1", "付款金额超过 1000 元需要主管审批；等于 1000 元不需要审批。")));
        clarifications.submit(run.id(), request); clarifications.submit(run.id(), request);
        assertThat(clarifications.round(run.id())).isEqualTo(2);
        assertThat(requirements.item(run.id(), 1, "RQ-1").orElseThrow().issuesJson()).contains("待业务明确");
        assertThat(admission.require(run.id()).requirementRevision()).isEqualTo(1);
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        var fake = (FakeOpenCodeClient) client; fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(DocumentTemplateProfiles.profile(MachineCandidateKind.DOCUMENT_REQUIREMENTS_V1), true);
        fake.holdProfileOpen(DocumentTemplateProfiles.profile(MachineCandidateKind.DOCUMENT_REQUIREMENT_REVIEW_V1), true);
        var extraction = awaitDocumentModel("DOCUMENT_REQUIREMENTS_V1");
        var input = json.readValue(extraction.inputJson(), DocumentModelInput.class);
        assertThat(input.clarifications()).singleElement().satisfies(answer -> assertThat(answer.answer()).contains("等于 1000"));
        assertThat(extraction.generation()).isEqualTo(contract.maxStageAttempts());
        var section = input.sections().getFirst();
        var candidate = new DocumentRequirements.Candidate(List.of(new DocumentRequirements.Requirement("RQ-1", "付款规则", "付款",
                DocumentRequirements.Kind.PERMISSION, "付款前必须鉴权；金额大于 1000 元需审批，等于 1000 元不需要审批",
                List.of(new DocumentRequirements.Source(section.fileId(), section.section(), "付款前必须鉴权")),
                List.of("未授权不能付款", "1000 元不触发审批，1001 元需要审批"), List.of())),
                List.of(new DocumentRequirements.Coverage(section.fileId(), section.section(), DocumentRequirements.Disposition.REQUIREMENT, List.of("RQ-1"), "付款规则")));
        completeDocumentModel(extraction, candidate);
        var review = awaitDocumentModel("DOCUMENT_REQUIREMENT_REVIEW_V1");
        assertThat(json.readValue(review.inputJson(), DocumentModelInput.class).clarifications()).isEqualTo(input.clarifications());
        assertThat(admission.require(run.id()).designerId()).isNull();
        completeDocumentModel(review, new DocumentRequirements.Review(true, List.of("RQ-1"), candidate.coverage(), List.of()));
        for (int i = 0; i < 8 && admission.require(run.id()).requirementRevision() < 2; i++)
            requirementWorkflow.advance(admission.require(run.id()), contract);
        assertThat(admission.require(run.id()).requirementRevision()).isEqualTo(2);
        assertThat(requirements.item(run.id(), 2, "RQ-1").orElseThrow().statement()).contains("等于 1000");
        assertThat(requirements.item(run.id(), 1, "RQ-1").orElseThrow().statement()).contains("阈值未确定");
        assertThat(clarifications.answers(run.id(), 2)).hasSize(1);
        assertThatThrownBy(() -> clarifications.submit(run.id(), new DocumentRequirementClarifications.Request(request.requestKey(),
                waiting.version(), 1, List.of(new DocumentRequirementClarifications.Answer("RQ-1", "改成 2000")))))
                .isInstanceOf(ConflictException.class);
    }
    private DocumentTemplateModelRow awaitDocumentModel(String kind) {
        for (int i = 0; i < 12; i++) {
            requirementWorkflow.advance(admission.require(run.id()), contract);
            var model = models.latest(run.id(), kind, 0);
            if (model.isPresent() && model.get().state().equals("RUNNING")) return model.get();
        }
        throw new AssertionError("Document role did not start: " + kind);
    }
    private void completeDocumentModel(DocumentTemplateModelRow model, Object candidate) {
        var response = submissions.submit(new MachineCandidateSubmission.SubmitCommand(model.id(), UUID.randomUUID().toString(),
                json.writeValueAsString(candidate), submissions.find(model.id()).orElseThrow().version(),
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP, MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
        assertThat(response.outcome()).as("%s", response.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        ((FakeOpenCodeClient) client).setSessionState(model.externalSessionId(), "COMPLETED"); modelExecution.advance(model.id(), contract);
    }
    @Test void staleIntakeVersionCannotLeaveAnOrphanDraftOrDesigner() {
        add("RQ-1", "必须鉴权", "[]");
        jdbc.update("UPDATE document_template_run SET version=version+1 WHERE id=?", run.id());
        assertThatThrownBy(() -> bootstrap.create(run, contract)).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loop_draft", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM designer_session", Integer.class)).isZero();
    }
    private void prepareMavenProject(boolean gitProject) throws Exception {
        Files.writeString(source.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>events</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>6.0.3</version><scope>test</scope></dependency></dependencies>
                <build><plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.5.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version><configuration><forkCount>0</forkCount></configuration></plugin>
                </plugins></build></project>
                """);
        // Match the outer build's cached plugins; machine Maven defaults vary across CI platforms.
        Files.createDirectories(source.resolve(".mvn")); Files.writeString(source.resolve(".mvn/maven.config"), "-o\n");
        if (gitProject) {
            Files.writeString(source.resolve(".gitignore"), "target/\n");
            git.read(source, "init", "-b", "main", "--template="); git.read(source, "add", ".");
            git.read(source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "frozen fixture baseline");
        }
    }
    private void add(String key, String statement, String issues) {
        var file = runs.files(run.id()).getFirst();
        requirements.insert(new DocumentRequirementMapper.Requirement(run.id(), 1, key, Integer.parseInt(key.substring(3)) - 1, "付款权限", "付款", "PERMISSION", statement,
                json.writeValueAsString(List.of(Map.of("fileId", file.id(), "section", 0, "quote", "付款前必须鉴权"))),
                "[\"无权限不能付款\"]", issues));
    }
}
