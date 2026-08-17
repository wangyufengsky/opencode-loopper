package io.opencode.loopper.api;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopDraftStatus;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.DesignDiscussionRevisionRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.DesignerSessionService;
import io.opencode.loopper.service.LoopDraftService;
import io.opencode.loopper.service.ProjectService;
import io.opencode.loopper.service.TaskService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.opencode.model=opencode/deepseek-v4-flash-free",
        "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h",
        "loopper.mcp.bearer-token=designer-mcp-test-token",
        "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.name=opencode-loopper",
        "spring.ai.mcp.server.version=0.1.67", "spring.ai.mcp.server.annotation-scanner.enabled=false",
        "spring.ai.mcp.server.capabilities.resource=false", "spring.ai.mcp.server.capabilities.prompt=false",
        "spring.ai.mcp.server.capabilities.completion=false",
        "spring.ai.mcp.server.streamable-http.mcp-endpoint=/api/mcp-streamable",
        "spring.ai.mcp.server.streamable-http.disallow-delete=true"})
@AutoConfigureMockMvc
class DesignerSessionMcpIntegrationTest {
    private static final String TOKEN = "designer-mcp-test-token";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private Flyway flyway;
    @Autowired private ProjectService projects;
    @Autowired private DesignerSessionService designerSessions;
    @Autowired private LoopDraftService drafts;
    @Autowired private TaskService tasks;
    @Autowired private LoopperMapper mapper;
    @Autowired private OpenCodeClient openCode;
    @Autowired private ToolCallbackProvider loopperMcpToolCallbackProvider;
    @Autowired private ApplicationContext context;
    @Autowired private org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties springAiMcpProperties;
    @Autowired private org.springframework.core.env.Environment environment;
    @TempDir Path temp;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        fake().reset();
        fake().setStructuredCapability(new OpenCodeClient.StructuredOutputCapability(
                OpenCodeClient.CapabilityState.UNAVAILABLE, OpenCodeClient.CapabilityState.UNKNOWN,
                "marker compatibility fixture"));
    }

    @Test
    void directDesignUsesIndependentReadOnlyRolesAndCreatesNoTaskBeforeConfirmation() throws Exception {
        ProjectRow project = project("direct");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 单包设计\n\n缓存刷新后用户能看到新值。", legacySpec(project.id())));

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "实现缓存刷新并保留验收证据");
        assertThat(session.workflowPhase()).isEqualTo("DECOMPOSING");
        assertThat(mapper.listTasks()).isEmpty();
        pollUntilSettled(session.id());

        DesignerSessionRow completed = designerSessions.get(session.id());
        assertThat(completed.state()).as("session=%s packages=%s messages=%s", completed,
                designerSessions.workPackageStatuses(session.id()), designerSessions.messages(session.id()))
                .isEqualTo("REVIEWING");
        assertThat(completed.workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(5);
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(5);
        assertThat(designerSessions.decompositionStatus(session.id()).resultType()).isEqualTo("DIRECT_DESIGN");
        assertThat(designerSessions.workPackageStatuses(session.id())).singleElement().satisfies(workPackage -> {
            assertThat(workPackage.id()).isEqualTo("WP-1");
            assertThat(workPackage.state()).isEqualTo("APPROVED");
        });
        assertThat(designerSessions.messages(session.id()).stream().map(message -> message.actor()).toList())
                .contains("DECOMPOSER", "DESIGNER", "COMPILER", "VALIDATOR")
                .doesNotContain("{\"status\":\"COMPILED\"");
        assertThat(mapper.listTasks()).isEmpty();
        assertThat(drafts.spec(drafts.get(draft.id())).stages()).allMatch(stage -> "WP-1".equals(stage.workPackageId()));

        TaskRow task = drafts.confirm(draft.id(), "缓存刷新");
        assertThat(task.state()).isEqualTo("PENDING_START");
        assertThat(mapper.findTaskQueue(task.id())).isEmpty();
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
        assertThat(mapper.listSessions(task.id())).isEmpty();
        assertThat(task.worktreePath()).isNullOrEmpty();
        assertThat(mapper.listTasks()).singleElement().extracting(TaskRow::id).isEqualTo(task.id());
        assertThat(tasks.artifacts(task.id()).stream().map(TaskArtifactRow::kind).toList())
                .contains("REQUIREMENT_CONTEXT", "DECOMPOSITION_CONTEXT", "WORK_PACKAGE_DESIGN",
                        "WORK_PACKAGE_COMPILATION_SUMMARY", "DESIGN_CONTEXT");
    }

    @Test
    void compactSemanticRolesAreServerCompiledWithoutFinalModelPrompts() throws Exception {
        ProjectRow project = project("compact-semantic");
        LoopDraftRow draft = drafts.create(v2DocumentationSpec(project.id()));
        fake().setDecomposerPlanningOutput("""
                <!-- TASK_DECOMPOSITION_PLAN_JSON_START -->
                {"outcome":"READY","normalizedGoal":"文档行为可验证",
                 "globalConstraints":["不新增依赖"],
                 "workPackages":[{"title":"文档能力","objective":"README 行为说明可自检","scopeIn":["README"],
                 "scopeOut":[],"deliverables":["文档"],"acceptanceIntent":["可执行自检"],"dependsOn":[]}],
                 "coverage":[{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE","targetIndex":0}],
                 "designGaps":["实施阶段再确定内部类名"],"reason":null}
                <!-- TASK_DECOMPOSITION_PLAN_JSON_END -->
                """);
        String design = "# 紧凑设计\n\nREADME 事件说明可执行自检";
        fake().setPackageDesignerOutput("WP-1", design);
        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"outcome":"COMPILED","summary":"文档阶段","stages":[{"objective":"更新 README 事件说明",
                 "implementationKind":"NON_JAVA","allowedPaths":["README.md"],"forbiddenPaths":[".env"],
                 "deliverables":["README 事件说明"],
                 "criteria":[{"description":"README 事件说明可执行自检","sourceRefs":["DS-L002"]}],
                 "evidence":[{"kind":"SELF_CHECK","command":["python3","-c","print('DOC_OK')"],
                 "successMarker":"DOC_OK","covers":[0]},{"kind":"GIT_DIFF","covers":[],
                 "requireChanges":true,"forbidDeletes":true}],"verificationRuntime":null}],
                 "handoffSummary":"文档能力可复用","designGaps":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "补充 README 事件说明并可验证");
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.decompositionStatus(session.id())).satisfies(status -> {
            assertThat(status.resultType()).isEqualTo("DIRECT_DESIGN");
            assertThat(status.serverCompiled()).isTrue();
            assertThat(status.formatRepairCount()).isZero();
            assertThat(status.semanticRepairCount()).isZero();
        });
        assertThat(designerSessions.compilerStatus(session.id())).satisfies(status -> {
            assertThat(status.serverCompiled()).isTrue();
            assertThat(status.formatRepairCount()).isZero();
            assertThat(status.semanticRepairCount()).isZero();
        });
        assertThat(drafts.spec(drafts.get(draft.id())).stages().getFirst()).satisfies(stage -> {
            assertThat(stage.workPackageId()).isEqualTo("WP-1");
            assertThat(stage.acceptanceCriteria()).singleElement()
                    .extracting(LoopSpec.AcceptanceCriterion::id).isEqualTo("WP-1-AC-1");
            assertThat(stage.verifiers().getFirst().criterionIds()).containsExactly("WP-1-AC-1");
        });
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(5);
        assertThat(fake().promptHistory()).noneMatch(call -> call.prompt().contains("Frozen planning:")
                && call.prompt().contains("final CompiledPackage JSON"));
    }

    @Test
    void compilerSemanticRepairAppliesPatchToFrozenCompactPlan() throws Exception {
        ProjectRow project = project("compact-semantic-patch");
        LoopDraftRow draft = drafts.create(v2DocumentationSpec(project.id()));
        fake().setDecomposerPlanningOutput("""
                <!-- TASK_DECOMPOSITION_PLAN_JSON_START -->
                {"outcome":"READY","normalizedGoal":"README 行为可验证","globalConstraints":[],
                 "workPackages":[{"title":"文档能力","objective":"README 两项行为均可自检",
                 "scopeIn":["README"],"scopeOut":[],"deliverables":["文档"],
                 "acceptanceIntent":["两项行为可执行自检"],"dependsOn":[]}],
                 "coverage":[{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE","targetIndex":0}],
                 "designGaps":[],"reason":null}
                <!-- TASK_DECOMPOSITION_PLAN_JSON_END -->
                """);
        String design = "# 紧凑设计\n\nREADME 提供事件说明。\n\nREADME 提供异常说明。";
        fake().setPackageDesignerOutput("WP-1", design);
        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"outcome":"COMPILED","summary":"文档阶段","stages":[{"objective":"更新 README",
                 "implementationKind":"NON_JAVA","allowedPaths":["README.md"],"forbiddenPaths":[".env"],
                 "deliverables":["README 事件与异常说明"],
                 "criteria":[{"description":"事件说明可自检","sourceRefs":["DS-L002"]},
                 {"description":"异常说明可自检","sourceRefs":["DS-L003"]}],
                 "evidence":[{"kind":"SELF_CHECK","command":["python3","-c","print('EVENT_OK')"],
                 "successMarker":"EVENT_OK","covers":[0]},{"kind":"GIT_DIFF","covers":[],
                 "requireChanges":true,"forbidDeletes":true}],"verificationRuntime":null}],
                 "handoffSummary":"README 合同可复用","designGaps":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "补充 README 两项可验证说明");
        pollUntilCompilerState(session.id(), "RUNNING", 1);

        assertThat(designerSessions.compilerStatus(session.id())).satisfies(status -> {
            assertThat(status.formatRepairCount()).isZero();
            assertThat(status.semanticRepairCount()).isEqualTo(1);
            assertThat(status.serverCompiled()).isFalse();
        });
        assertThat(mapper.findLatestLoopSpecCompilation(session.id()).orElseThrow().semanticPlanJson())
                .contains("\"outcome\":\"COMPILED\"");

        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"patches":[{"op":"add","path":"/stages/0/evidence/-",
                 "value":{"kind":"SELF_CHECK","command":["python3","-c","print('ERROR_OK')"],
                 "successMarker":"ERROR_OK","covers":[1]}}]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.compilerStatus(session.id())).satisfies(status -> {
            assertThat(status.formatRepairCount()).isZero();
            assertThat(status.semanticRepairCount()).isEqualTo(1);
            assertThat(status.serverCompiled()).isTrue();
            assertThat(status.lastErrorCode()).isNull();
        });
        assertThat(drafts.spec(drafts.get(draft.id())).stages().getFirst().verifiers())
                .filteredOn(verifier -> "SELF_CHECK".equals(verifier.processPurpose()))
                .hasSize(2);
    }

    @Test
    void exposesLatestUnconfirmedDesignerSessionsSeparatelyFromTasks() throws Exception {
        ProjectRow project = project("designer-recovery");
        LoopDraftRow openDraft = drafts.create(legacySpec(project.id()));
        LoopDraftRow confirmedDraft = drafts.create(legacySpec(project.id()));
        mapper.insertDesignerSession(designerRow("designer-old", project.id(), openDraft.id(),
                "WAITING_INPUT", "FAILED", "2026-08-17T01:00:00Z"));
        mapper.insertDesignerSession(designerRow("designer-latest", project.id(), openDraft.id(),
                "WAITING_INPUT", "FAILED", "2026-08-17T02:00:00Z"));
        mapper.insertDesignerSession(designerRow("designer-confirmed", project.id(), confirmedDraft.id(),
                "COMPLETED", "COMPLETED", "2026-08-17T03:00:00Z"));
        assertThat(mapper.updateDraft(new LoopDraftRow(confirmedDraft.id(), confirmedDraft.projectId(),
                confirmedDraft.goal(), confirmedDraft.specJson(), LoopDraftStatus.CONFIRMED.name(),
                confirmedDraft.createdAt(), "2026-08-17T03:01:00Z", confirmedDraft.version()))).isEqualTo(1);

        assertThat(designerSessions.listOpen(project.id())).extracting(DesignerSessionRow::id)
                .containsExactly("designer-latest");
        assertThat(projects.taskCount(project.id())).isZero();
        assertThat(projects.openDesignerSessionCount(project.id())).isEqualTo(1);

        mvc.perform(get("/api/designer-sessions").queryParam("projectId", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("designer-latest"))
                .andExpect(jsonPath("$[0].goal").value(openDraft.goal()))
                .andExpect(jsonPath("$[0].draftStatus").value(openDraft.status()));
        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskCount").value(0))
                .andExpect(jsonPath("$[0].openDesignerSessionCount").value(1));
    }

    @Test
    void listsFiltersArchivesAndRestoresDesignerHistoryWithoutDeletingSnapshots() throws Exception {
        ProjectRow project = project("designer-history");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        mapper.insertDesignerSession(designerRow("designer-history-session", project.id(), draft.id(),
                "WAITING_INPUT", "FAILED", "2026-08-17T04:00:00Z"));

        mvc.perform(get("/api/designer-sessions/history").queryParam("projectId", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("designer-history-session"))
                .andExpect(jsonPath("$[0].projectName").value(project.name()))
                .andExpect(jsonPath("$[0].archived").value(false));

        mvc.perform(put("/api/designer-sessions/designer-history-session/archive"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/designer-sessions/designer-history-session/archive")
                        .header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isNoContent());

        assertThat(designerSessions.listOpen(project.id())).isEmpty();
        assertThat(projects.openDesignerSessionCount(project.id())).isZero();
        assertThat(designerSessions.messages("designer-history-session")).isNotNull();
        mvc.perform(get("/api/designer-sessions/history").queryParam("projectId", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].archived").value(true))
                .andExpect(jsonPath("$[0].archivedAt").isNotEmpty());

        mvc.perform(delete("/api/designer-sessions/designer-history-session/archive")
                        .header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isNoContent());
        assertThat(designerSessions.listOpen(project.id())).extracting(DesignerSessionRow::id)
                .containsExactly("designer-history-session");
        assertThat(projects.openDesignerSessionCount(project.id())).isEqualTo(1);
    }

    @Test
    void mandatoryQuestionGetsOneFreshSessionRepairThenWaitsWithoutLosingTheSnapshot() throws Exception {
        ProjectRow project = project("mandatory-question-repair");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 完整需求\n\n保留可恢复设计边界。", legacySpec(project.id())));

        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "验证设计问题不可跳过");
        String firstRemote = session.externalSessionId();
        designerSessions.pollActiveHandoffs();

        DesignerSessionRow repaired = designerSessions.get(session.id());
        assertThat(repaired.state()).isEqualTo("RUNNING");
        assertThat(repaired.externalSessionId()).isNotEqualTo(firstRemote);
        assertThat(mapper.listDesignDiscussionRevisions(session.id())).singleElement().satisfies(revision -> {
            assertThat(revision.questionRetryCount()).isEqualTo(1);
            assertThat(revision.questionAnswered()).isFalse();
        });
        assertThat(fake().sessionStatus(new OpenCodeClient.OpenCodeSession(firstRemote,
                Path.of(project.rootPath()))).state()).isEqualTo("ABORTED");

        String repairRemote = repaired.externalSessionId();
        designerSessions.pollActiveHandoffs();

        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.messages(session.id())).anyMatch(message ->
                message.content().contains("DESIGN_QUESTION_REQUIRED"));
        assertThat(fake().sessionStatus(new OpenCodeClient.OpenCodeSession(repairRemote,
                Path.of(project.rootPath()))).state()).isEqualTo("ABORTED");
        assertThat(mapper.findCurrentDesignRequirementRevision(session.id())).isEmpty();
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void scopedPackageDiscussionPersistsFiveFullRevisionsAndRejectsStaleOrSixthActions() throws Exception {
        ProjectRow project = project("package-discussion-revisions");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 初稿\n\n提供完整且可观察的结果。", legacySpec(project.id())));
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "逐轮优化一个工作包");
        pollUntilPackageReview(session.id());
        DesignRequirementRevisionRow frozen = mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow();
        String decompositionId = mapper.findTaskDecompositionByRevision(frozen.id()).orElseThrow().id();

        for (int round = 1; round <= 5; round++) {
            DesignerSessionRow reviewing = designerSessions.get(session.id());
            var workPackage = designerSessions.workPackageStatuses(session.id()).getFirst();
            designerSessions.appendPackageMessage(session.id(), "WP-1", "第 " + round + " 轮补充边界",
                    reviewing.discussionRevision(), workPackage.designRevision());
            assertThat(mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().id())
                    .isEqualTo(frozen.id());
            assertThat(mapper.findTaskDecompositionByRevision(frozen.id()).orElseThrow().id())
                    .isEqualTo(decompositionId);
            pollUntilPackageReview(session.id());
        }

        DesignerSessionRow reviewing = designerSessions.get(session.id());
        var current = designerSessions.workPackageStatuses(session.id()).getFirst();
        assertThat(current.discussionRoundCount()).isEqualTo(5);
        assertThat(mapper.listDesignDiscussionRevisions(session.id()).stream()
                .filter(row -> "WP-1".equals(row.scopeKey())).toList()).hasSize(6)
                .allMatch(row -> row.questionAnswered() && !row.snapshotMarkdown().isBlank());
        assertThatThrownBy(() -> designerSessions.appendPackageMessage(session.id(), "WP-1", "第六轮",
                reviewing.discussionRevision(), current.designRevision()))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        assertThat(error.code()).isEqualTo("WORK_PACKAGE_DISCUSSION_LIMIT_REACHED"));
        assertThatThrownBy(() -> designerSessions.approvePackage(session.id(), "WP-1",
                reviewing.discussionRevision(), current.designRevision() - 1))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        assertThat(error.code()).isEqualTo("WORK_PACKAGE_APPROVAL_STALE"));

        designerSessions.approvePackage(session.id(), "WP-1", reviewing.discussionRevision(),
                current.designRevision());
        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
    }

    @Test
    void invalidReplacementCandidateKeepsTheLastVerifiedCandidate() throws Exception {
        ProjectRow project = project("candidate-retention");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 有效初稿\n\n提供可观察结果。", legacySpec(project.id())));
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "候选失败时保留上一版");
        pollUntilPackageReview(session.id());
        DesignerSessionService.CandidateStatus previous = designerSessions.candidateStatus(session.id());
        assertThat(previous.spec()).isNotNull();

        fake().setPackageCompilerOutput("WP-1",
                "<!-- LOOPSPEC_COMPILATION_JSON_START -->{}<!-- LOOPSPEC_COMPILATION_JSON_END -->");
        DesignerSessionRow reviewing = designerSessions.get(session.id());
        var current = designerSessions.workPackageStatuses(session.id()).getFirst();
        designerSessions.appendPackageMessage(session.id(), "WP-1", "请替换为新的失败候选",
                reviewing.discussionRevision(), current.designRevision());
        pollUntilSettled(session.id());

        DesignerSessionService.CandidateStatus retained = designerSessions.candidateStatus(session.id());
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(retained.syncState()).isEqualTo("FAILED");
        assertThat(retained.spec()).isEqualTo(previous.spec());
    }

    @Test
    void reopeningAnApprovedPackageInvalidatesOnlyItsTransitiveDependents() throws Exception {
        ProjectRow project = project("package-reopen-dependencies");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String split = decomposition("DECOMPOSED", "三包依赖和一个无关包", 4)
                .replace("\"dependencies\":[\"WP-3\"]", "\"dependencies\":[]");
        fake().setDecomposerOutput(split);
        for (int ordinal = 1; ordinal <= 4; ordinal++) {
            String packageId = "WP-" + ordinal;
            String design = "# " + packageId + " 设计\n\n提供独立可观察结果。";
            fake().setPackageDesignerOutput(packageId, design);
            fake().setPackageCompilerOutput(packageId, packageCompilation(packageId, design));
        }
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "验证工作包传递失效边界");
        pollUntilSettled(session.id());
        assertThat(designerSessions.workPackageStatuses(session.id())).allMatch(item -> "APPROVED".equals(item.state()));

        var first = designerSessions.workPackageStatuses(session.id()).getFirst();
        List<String> invalidated = designerSessions.reopenPackage(session.id(), "WP-1",
                designerSessions.get(session.id()).discussionRevision(), first.approvedDesignRevision());

        assertThat(invalidated).containsExactly("WP-2", "WP-3");
        assertThat(designerSessions.workPackageStatuses(session.id())).extracting(item -> item.state())
                .containsExactly("REVIEWING", "STALE", "STALE", "APPROVED");
    }

    @Test
    void structuredDesignUsesRoleProfilesAndFallsBackOnceInAFreshSession() throws Exception {
        fake().setStructuredCapability(new OpenCodeClient.StructuredOutputCapability(
                OpenCodeClient.CapabilityState.AVAILABLE, OpenCodeClient.CapabilityState.AVAILABLE, null));
        fake().failNextStructuredPrompts(1);
        ProjectRow project = project("structured-fallback");
        LoopSpec structuredSpec = v2DocumentationSpec(project.id());
        LoopDraftRow draft = drafts.create(structuredSpec);
        fake().setDesignerOutput(designerOutput(
                "# 结构化设计\n\nREADME 文档设计可执行验证。", structuredSpec));

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "结构化输出失败时安全回退");
        pollUntilSettled(session.id());

        var decomposition = mapper.findTaskDecompositionByRevision(
                mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().id()).orElseThrow();
        assertThat(decomposition.planningResponseMode()).isEqualTo("TEXT_MARKER");
        assertThat(decomposition.planningResponseSchemaId()).isNull();
        assertThat(decomposition.planningFormatFallbackUsed()).isTrue();
        assertThat(decomposition.finalResponseMode()).isEqualTo("JSON_SCHEMA");
        assertThat(decomposition.finalResponseSchemaId()).isEqualTo("DECOMPOSITION_FINAL_V1");
        assertThat(decomposition.planningRepairCount()).isEqualTo(1);
        var compilation = mapper.findLatestLoopSpecCompilationForPackage(session.id(), "WP-1").orElseThrow();
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(6);
        assertThat(fake().profileForSession(decomposition.externalSessionId()))
                .isEqualTo(OpenCodeClient.SessionProfile.DECOMPOSER_READ_ONLY);
        assertThat(fake().modelForSession(decomposition.externalSessionId()).thinking()).isFalse();

        assertThat(compilation.planningResponseMode()).isEqualTo("JSON_SCHEMA");
        assertThat(compilation.planningResponseSchemaId()).isEqualTo("PACKAGE_COMPILATION_SEMANTIC_V3");
        assertThat(compilation.finalResponseSchemaId()).isEqualTo("PACKAGE_COMPILATION_FINAL_V2");
        assertThat(compilation.state()).as("compilation=%s", compilation).isEqualTo("COMPLETED");
        assertThat(fake().profileForSession(compilation.externalSessionId()))
                .isEqualTo(OpenCodeClient.SessionProfile.COMPILER_READ_ONLY);
        assertThat(fake().modelForSession(compilation.externalSessionId()).thinking()).isFalse();
        assertThat(designerSessions.get(session.id()).workflowPhase())
                .as("session=%s packages=%s messages=%s", designerSessions.get(session.id()),
                        designerSessions.workPackageStatuses(session.id()), designerSessions.messages(session.id()))
                .isEqualTo("FINAL_REVIEW");
    }

    @Test
    void decomposerRetryStatusRemainsTransientWithoutCreatingAnotherSession() throws Exception {
        ProjectRow project = project("decomposer-retry-status");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 瞬态重试\n\n模型恢复后继续拆解。", legacySpec(project.id())));
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "验证 OpenCode 瞬态重试");
        var before = mapper.findTaskDecompositionByRevision(
                mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().id()).orElseThrow();
        int sessionsBefore = fake().createReadOnlySessionCalls();
        int callsBefore = designerSessions.requirementStatus(session.id()).modelCallsUsed();
        fake().setSessionStatus(before.externalSessionId(), "RETRY", "provider socket reconnecting");

        designerSessions.pollActiveHandoffs();

        var after = mapper.findTaskDecomposition(before.id()).orElseThrow();
        assertThat(after.state()).isEqualTo("RUNNING");
        assertThat(after.externalSessionId()).isEqualTo(before.externalSessionId());
        assertThat(after.externalSessionState()).isEqualTo("RETRY");
        assertThat(after.transportRetryCount()).isZero();
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(sessionsBefore);
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(callsBefore);
    }

    @Test
    void exhaustedDecomposerTransportRetryAbortsTheLastRemoteSession() throws Exception {
        ProjectRow project = project("decomposer-terminal-abort");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 终态清理\n\n传输失败后停止远端执行。", legacySpec(project.id())));
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "验证终态清理");
        var first = mapper.findTaskDecompositionByRevision(
                mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().id()).orElseThrow();
        fake().setSessionStatus(first.externalSessionId(), "FAILED", "first transport failure");

        designerSessions.pollActiveHandoffs();
        var retry = mapper.findTaskDecomposition(first.id()).orElseThrow();
        assertThat(retry.externalSessionId()).isNotEqualTo(first.externalSessionId());
        assertThat(retry.transportRetryCount()).isEqualTo(1);
        fake().setSessionStatus(retry.externalSessionId(), "FAILED", "second transport failure");

        designerSessions.pollActiveHandoffs();

        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(mapper.findTaskDecomposition(first.id()).orElseThrow().state()).isEqualTo("SESSION_ERROR");
        assertThat(fake().sessionStatus(new OpenCodeClient.OpenCodeSession(
                retry.externalSessionId(), Path.of(project.rootPath()))).state()).isEqualTo("ABORTED");
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void threePackagesAreDesignedAndCompiledStrictlySerialThenAggregatedIntoOneTask() throws Exception {
        ProjectRow project = project("three-packages");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerOutput(decomposition("DECOMPOSED", "交付三段纵向能力", 3));
        for (int ordinal = 1; ordinal <= 3; ordinal++) {
            String packageId = "WP-" + ordinal;
            String design = "# " + packageId + " 设计\n\n" + packageId + " 完成后产生可观察结果。";
            fake().setPackageDesignerOutput(packageId, design);
            fake().setPackageCompilerOutput(packageId, packageCompilation(packageId, design));
        }

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "交付一个包含三个纵向能力的大型需求");
        assertThat(mapper.listTasks()).isEmpty();
        pollUntilPackageStates(session.id(), "COMPLETED", "DESIGNING", "PENDING");
        assertPackageStates(session.id(), "COMPLETED", "DESIGNING", "PENDING");
        assertThat(designerSessions.get(session.id()).activeWorkPackageId()).isEqualTo("WP-2");
        assertThat(mapper.listTasks()).isEmpty();

        pollUntilPackageStates(session.id(), "COMPLETED", "COMPLETED", "DESIGNING");
        assertPackageStates(session.id(), "COMPLETED", "COMPLETED", "DESIGNING");
        assertThat(designerSessions.get(session.id()).activeWorkPackageId()).isEqualTo("WP-3");
        assertThat(mapper.listTasks()).isEmpty();

        pollUntilSettled(session.id());
        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertPackageStates(session.id(), "COMPLETED", "COMPLETED", "COMPLETED");
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(9);
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(9);
        LoopSpec aggregate = drafts.spec(drafts.get(draft.id()));
        assertThat(aggregate.goal()).isEqualTo("交付三段纵向能力");
        assertThat(aggregate.stages()).extracting(LoopSpec.StageSpec::workPackageId)
                .containsExactly("WP-1", "WP-2", "WP-3");
        assertThat(aggregate.limits().maxTaskAttempts()).isGreaterThanOrEqualTo(9);
        assertThat(mapper.listTasks()).isEmpty();
        assertThatThrownBy(() -> drafts.update(draft.id(), withoutWorkPackageIds(aggregate)))
                .isInstanceOfSatisfying(BadRequestException.class, error ->
                        assertThat(error.code()).isEqualTo("WORK_PACKAGE_MAPPING_IMMUTABLE"));

        TaskRow task = drafts.confirm(draft.id(), "三包任务");
        assertThat(mapper.listTasks()).hasSize(1);
        assertThat(tasks.stages(task.id())).extracting(stage -> stage.workPackageId())
                .containsExactly("WP-1", "WP-2", "WP-3");
    }

    @Test
    void sixPackagesStayWithinTheRevisionBudgetAndRemainStrictlySerial() throws Exception {
        ProjectRow project = project("six-packages");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerOutput(decomposition("DECOMPOSED", "交付六个纵向能力", 6));
        for (int ordinal = 1; ordinal <= 6; ordinal++) {
            String packageId = "WP-" + ordinal;
            String design = "# " + packageId + " 设计\n\n" + packageId + " 提供独立可观察结果。";
            fake().setPackageDesignerOutput(packageId, design);
            fake().setPackageCompilerOutput(packageId, packageCompilation(packageId, design));
        }

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "一个需要六个纵向工作包的大型任务");
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.workPackageStatuses(session.id())).hasSize(6)
                .allMatch(workPackage -> "APPROVED".equals(workPackage.state()));
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(15);
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(15);
        assertThat(drafts.spec(drafts.get(draft.id())).stages()).extracting(LoopSpec.StageSpec::workPackageId)
                .containsExactly("WP-1", "WP-2", "WP-3", "WP-4", "WP-5", "WP-6");
        assertThat(mapper.listTasks()).isEmpty();

        LoopDraftRow stored = drafts.get(draft.id());
        LoopSpec flattened = withoutWorkPackageIds(drafts.spec(stored));
        LoopDraftRow corrupted = new LoopDraftRow(stored.id(), stored.projectId(), stored.goal(),
                json.writeValueAsString(flattened), stored.status(), stored.createdAt(), stored.updatedAt(), stored.version());
        assertThat(mapper.updateDraftContent(corrupted)).isOne();
        assertThatThrownBy(() -> drafts.confirm(draft.id(), "不得扁平化的六包任务"))
                .isInstanceOfSatisfying(BadRequestException.class, error ->
                        assertThat(error.code()).isEqualTo("WORK_PACKAGE_STAGE_MAPPING_INVALID"));
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void concurrentDraftChangeStopsBeforeAnyPackageModelCall() throws Exception {
        ProjectRow project = project("draft-conflict");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerOutput(decomposition("DIRECT_DESIGN", "不覆盖人工草稿", 1));
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "冻结后不得覆盖人工修改");
        LoopSpec edited = legacySpec(project.id());
        drafts.update(draft.id(), new LoopSpec(edited.schemaVersion(), edited.projectId(), "人工编辑后的目标",
                edited.context(), edited.stages(), edited.limits(), edited.model(), edited.sessionPolicy(),
                edited.nextAttemptPromptTemplate(), edited.budget()));

        designerSessions.pollActiveHandoffs();

        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.messages(session.id())).anyMatch(message ->
                "VALIDATOR".equals(message.actor()) && message.content().contains("DESIGNER_DRAFT_CHANGED"));
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(2);
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void exhaustedGlobalModelBudgetStopsTheActivePackageAndCompilerSession() throws Exception {
        ProjectRow project = project("model-budget");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerOutput(decomposition("DECOMPOSED", "两包预算边界", 2));
        for (int ordinal = 1; ordinal <= 2; ordinal++) {
            String packageId = "WP-" + ordinal;
            String design = "# " + packageId + " 设计\n\n可观察结果。";
            fake().setPackageDesignerOutput(packageId, design);
            fake().setPackageCompilerOutput(packageId, packageCompilation(packageId, design));
        }
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "验证九十六次模型调用硬上限");
        pollUntilPackageStates(session.id(), "COMPLETED", "DESIGNING");
        assertPackageStates(session.id(), "COMPLETED", "DESIGNING");
        DesignRequirementRevisionRow revision = mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow();
        assertThat(mapper.updateDesignRequirementRevision(new DesignRequirementRevisionRow(revision.id(),
                revision.designerSessionId(), revision.revision(), revision.sourceMessageId(),
                revision.requirementText(), revision.requirementSegmentsJson(), revision.sourceDraftVersion(),
                revision.state(), 96, revision.maxModelCalls(), revision.createdAt(), revision.updatedAt(),
                revision.version()))).isEqualTo(1);

        pollWithMandatoryQuestion(session.id());

        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(96);
        assertThat(designerSessions.workPackageStatuses(session.id()).get(1).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.compilerStatus(session.id()).state()).isEqualTo("SESSION_ERROR");
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void decompositionInputAndMultiTaskBoundariesWaitWithoutCreatingTasks() throws Exception {
        for (String status : List.of("NEEDS_INPUT", "MULTI_TASK_REQUIRED")) {
            fake().reset();
            fake().setStructuredCapability(new OpenCodeClient.StructuredOutputCapability(
                    OpenCodeClient.CapabilityState.UNAVAILABLE, OpenCodeClient.CapabilityState.UNKNOWN,
                    "marker compatibility fixture"));
            ProjectRow project = project(status.toLowerCase());
            LoopDraftRow draft = drafts.create(legacySpec(project.id()));
            fake().setDecomposerOutput(decomposition(status, "需要人工处理", 0));
            DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "包含关键歧义或多个发布边界的需求");
            designerSessions.pollActiveHandoffs();
            designerSessions.pollActiveHandoffs();
            assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
            assertThat(designerSessions.decompositionStatus(session.id()).resultType()).isEqualTo(status);
            assertThat(mapper.countTasksForProject(project.id())).isZero();
            assertThatThrownBy(() -> drafts.confirm(draft.id(), "不可确认"))
                    .hasMessageContaining("every work package is approved");
        }
    }

    @Test
    void decompositionPlanningAndFinalJsonHaveIndependentRepairBudgets() throws Exception {
        ProjectRow project = project("decomposer-planning");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerOutput(decomposition("NEEDS_INPUT", "需要补充异步边界", 0));
        fake().setDecomposerPlanningOutput("""
                <!-- TASK_DECOMPOSITION_PLAN_JSON_START -->
                {"status":"NEEDS_INPUT","normalizedGoal":"需要补充异步边界","globalConstraints":[],
                 "workPackages":[],"coverageMappings":[],"dependencyEvidence":[],
                 "designGaps":["事件分发是否异步未明确"],"reason":null}
                <!-- TASK_DECOMPOSITION_PLAN_JSON_END -->
        """);

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "明确事件分发边界");
        String decompositionSessionId = mapper.findTaskDecompositionByRevision(
                mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().id())
                .orElseThrow().externalSessionId();
        designerSessions.pollActiveHandoffs();

        DesignerSessionService.DecompositionStatus repairing = designerSessions.decompositionStatus(session.id());
        assertThat(repairing.planningRepairCount()).isEqualTo(1);
        assertThat(repairing.repairCount()).isZero();
        assertThat(repairing.workflowStep()).isEqualTo("PLANNING");
        assertThat(repairing.lastErrorCode()).isEqualTo("DECOMPOSER_PLAN_OUTPUT_INVALID");
        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.decompositionStatus(session.id()).planningRepairCount()).isEqualTo(2);
        fake().setDecomposerPlanningOutput(decompositionPlan("NEEDS_INPUT", "需要补充异步边界"));

        designerSessions.pollActiveHandoffs();
        DesignerSessionService.DecompositionStatus generating = designerSessions.decompositionStatus(session.id());
        assertThat(generating.workflowStep()).isEqualTo("FINAL_JSON");
        assertThat(generating.formatRepairCount()).isEqualTo(2);
        assertThat(generating.semanticRepairCount()).isZero();
        assertThat(generating.serverCompiled()).isTrue();
        assertThat(mapper.findTaskDecompositionByRevision(
                mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().id()).orElseThrow().planningJson())
                .contains("coverageMappings", "designGaps");

        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.decompositionStatus(session.id()).workflowStep()).isEqualTo("FINAL_JSON");
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(2);
        assertThat(fake().promptHistory().stream().filter(call -> decompositionSessionId.equals(call.sessionId())))
                .hasSize(3);
        assertThat(designerSessions.messages(session.id())).noneMatch(message ->
                message.content().contains("TASK_DECOMPOSITION_PLAN_JSON_START"));
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void decomposerAcceptsOneStandaloneJsonObjectWhenWeakModelDropsMarkers() throws Exception {
        ProjectRow project = project("decomposer-marker-fallback");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerPlanningOutput(unwrapped(decompositionPlan("NEEDS_INPUT", "需要补充事件边界"),
                "<!-- TASK_DECOMPOSITION_PLAN_JSON_START -->",
                "<!-- TASK_DECOMPOSITION_PLAN_JSON_END -->"));
        String finalJson = unwrapped(decomposition("NEEDS_INPUT", "需要补充事件边界", 0),
                "<!-- TASK_DECOMPOSITION_JSON_START -->", "<!-- TASK_DECOMPOSITION_JSON_END -->");
        fake().setDecomposerOutput("```json\n" + finalJson + "\n```");

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "明确事件系统边界");
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();

        DesignerSessionService.DecompositionStatus status = designerSessions.decompositionStatus(session.id());
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(status.resultType()).isEqualTo("NEEDS_INPUT");
        assertThat(status.planningRepairCount()).isZero();
        assertThat(status.repairCount()).isZero();
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(2);
        assertThat(designerSessions.messages(session.id())).noneMatch(message ->
                message.content().contains("OUTPUT_MARKERS_MISSING"));
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void decomposerAcceptsUnmarkedJsonWithSurroundingProseWithoutRepair() throws Exception {
        ProjectRow project = project("decomposer-marker-fallback-accepts-prose");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String planningJson = unwrapped(decompositionPlan("NEEDS_INPUT", "需要补充事件边界"),
                "<!-- TASK_DECOMPOSITION_PLAN_JSON_START -->",
                "<!-- TASK_DECOMPOSITION_PLAN_JSON_END -->");
        fake().setDecomposerPlanningOutput("planning result:\n" + planningJson);
        fake().setDecomposerOutput("final result:\n" + unwrapped(
                decomposition("NEEDS_INPUT", "需要补充事件边界", 0),
                "<!-- TASK_DECOMPOSITION_JSON_START -->", "<!-- TASK_DECOMPOSITION_JSON_END -->"));

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "明确事件系统边界");
        designerSessions.pollActiveHandoffs();
        DesignerSessionService.DecompositionStatus generating = designerSessions.decompositionStatus(session.id());
        assertThat(generating.workflowStep()).isEqualTo("FINAL_JSON");
        assertThat(generating.serverCompiled()).isTrue();

        DesignerSessionService.DecompositionStatus status = designerSessions.decompositionStatus(session.id());
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(status.planningRepairCount()).isZero();
        assertThat(status.repairCount()).isZero();
        assertThat(designerSessions.messages(session.id())).anyMatch(message ->
                "NORMALIZED".equals(message.deliveryState())
                        && message.content().contains("WRAPPER_TOLERATED"));
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void requirementDiscussionDoesNotDecomposeUntilExplicitConfirmationAndStartsWithNinetySixCallBudget() throws Exception {
        ProjectRow project = project("revision");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 补充后的完整设计\n\n异常和验收边界完整。", legacySpec(project.id())));
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "先实现查询能力");
        completeMandatoryDesignerQuestion(session.id());
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow firstReview = designerSessions.get(session.id());
        assertThat(firstReview.workflowPhase()).isEqualTo("DISCUSSING_REQUIREMENT");
        assertThat(mapper.findCurrentDesignRequirementRevision(session.id())).isEmpty();
        assertThat(mapper.findLatestTaskDecomposition(session.id())).isEmpty();

        designerSessions.appendRequirementMessage(session.id(),
                "补充：失败时返回明确错误，且保持同一发布边界", firstReview.discussionRevision());
        completeMandatoryDesignerQuestion(session.id());
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow secondReview = designerSessions.get(session.id());
        assertThat(secondReview.discussionRevision()).isEqualTo(2);
        assertThat(mapper.listDesignDiscussionRevisions(session.id())).hasSize(2)
                .allMatch(row -> !row.snapshotMarkdown().isBlank());
        assertThat(mapper.findLatestTaskDecomposition(session.id())).isEmpty();

        fake().setDecomposerOutput(decomposition("NEEDS_INPUT", "补充后的完整需求", 0));
        designerSessions.confirmRequirement(session.id(), secondReview.discussionRevision());
        assertThat(designerSessions.requirementStatus(session.id()).revision()).isEqualTo(1);
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(3);
        assertThat(designerSessions.requirementStatus(session.id()).maxModelCalls()).isEqualTo(96);
        pollUntilSettled(session.id());
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
    }

    @Test
    void reopenedRequirementFreezesANewRevisionWithoutReusingTheOldDecomposition() throws Exception {
        ProjectRow project = project("reopened-requirement");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 第一版完整需求\n\n结果必须可观察。", legacySpec(project.id())));
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "第一版整体需求");
        DesignRequirementRevisionRow first = mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow();
        String firstDecomposition = mapper.findTaskDecompositionByRevision(first.id()).orElseThrow().id();
        pollUntilPackageReview(session.id());

        DesignerSessionRow packageReview = designerSessions.get(session.id());
        designerSessions.reopenRequirement(session.id(), packageReview.discussionRevision());

        assertThat(mapper.findCurrentDesignRequirementRevision(session.id())).isEmpty();
        assertThat(mapper.findDesignRequirementRevision(first.id()).orElseThrow().state()).isEqualTo("SUPERSEDED");
        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("DISCUSSING_REQUIREMENT");

        fake().setDesignerOutput(designerOutput("# 第二版完整需求\n\n保留目标并增加异常边界。", legacySpec(project.id())));
        DesignerSessionRow reopened = designerSessions.get(session.id());
        designerSessions.appendRequirementMessage(session.id(), "增加明确的异常边界",
                reopened.discussionRevision());
        completeMandatoryDesignerQuestion(session.id());
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow secondReview = designerSessions.get(session.id());
        fake().setDecomposerOutput(decomposition("NEEDS_INPUT", "第二版需求", 0));
        designerSessions.confirmRequirement(session.id(), secondReview.discussionRevision());

        DesignRequirementRevisionRow second = mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow();
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(mapper.findTaskDecompositionByRevision(second.id()).orElseThrow().id())
                .isNotEqualTo(firstDecomposition);
        assertThat(mapper.listDesignDiscussionRevisions(session.id()).stream()
                .filter(row -> "REQUIREMENT".equals(row.scopeKey()) && row.revision() == 2)
                .map(DesignDiscussionRevisionRow::requirementRevision)).containsExactly(2);
    }

    @Test
    void compilerGetsTwoRepairsWhileSemanticGapRedesignsOnlyCurrentPackage() throws Exception {
        ProjectRow project = project("retry");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 完整设计\n\n输出明确且可验收。", legacySpec(project.id())));
        fake().setPackageCompilerPlanningOutput("WP-1", "planning without required JSON");
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "实现可验收能力");
        pollUntilCompilerState(session.id(), "RUNNING", 1);
        assertThat(designerSessions.compilerStatus(session.id()).formatRepairCount()).isEqualTo(1);
        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.compilerStatus(session.id()).formatRepairCount()).isEqualTo(2);
        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.workPackageStatuses(session.id()).getFirst().lastErrorCode())
                .isEqualTo("COMPILER_RETRY_EXHAUSTED");

        fake().setCompilerOutput(designIncomplete("MISSING_EXCEPTION_SEMANTICS", "缺少异常结果"));
        fake().setPackageCompilerPlanningOutput("WP-1", null);
        designerSessions.retryPackageCompilation(session.id(), "WP-1");
        for (int i = 0; i < 8 && !"QUESTIONING_PACKAGE".equals(designerSessions.get(session.id()).workflowPhase()); i++) {
            pollWithMandatoryQuestion(session.id());
        }
        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("QUESTIONING_PACKAGE");
        assertThat(designerSessions.workPackageStatuses(session.id()).getFirst().redesignCount()).isEqualTo(1);
    }

    @Test
    void packageCompilerInitialAndRepairPromptsRepeatCompleteJsonTypeContract() throws Exception {
        ProjectRow project = project("compiler-contract");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 完整 Java 设计\n\n新增生产代码并由聚焦单元测试证明行为。",
                legacySpec(project.id())));
        fake().setCompilerOutput("<!-- LOOPSPEC_COMPILATION_JSON_START -->{}<!-- LOOPSPEC_COMPILATION_JSON_END -->");
        fake().setPackageCompilerPlanningOutput("WP-1", packageCompilationPlan("WP-1"));

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "新增 Java 行为并提供聚焦单元测试");
        pollUntilCompilerState(session.id(), "RUNNING", 1);

        String compilerSessionId = designerSessions.compilerStatus(session.id()).externalSessionId();
        List<String> compilerPrompts = fake().promptHistory().stream()
                .filter(call -> compilerSessionId.equals(call.sessionId()))
                .map(io.opencode.loopper.runtime.FakeOpenCodeClient.PromptCall::prompt)
                .toList();
        assertThat(compilerPrompts).hasSize(1);
        assertThat(compilerPrompts.getFirst())
                .contains("Machine role contract 2026-08-semantic-v1")
                .contains("DS-L001", "FOCUSED_TEST", "covers")
                .contains("Do not assign acceptance ids", "testTargets");
    }

    @Test
    void packageCompilerPlanningAndFinalJsonHaveIndependentRepairBudgets() throws Exception {
        ProjectRow project = project("compiler-independent-repairs");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 可编译设计\n\n文档行为和确定性自检均完整。",
                legacySpec(project.id())));
        fake().setPackageCompilerPlanningOutput("WP-1", "planning without required markers");
        fake().setCompilerOutput("<!-- LOOPSPEC_COMPILATION_JSON_START -->{}<!-- LOOPSPEC_COMPILATION_JSON_END -->");

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "验证编译器分步修复隔离");
        for (int i = 0; i < 24; i++) {
            pollWithMandatoryQuestion(session.id());
            DesignerSessionService.CompilerStatus status = designerSessions.compilerStatus(session.id());
            if (status != null && status.planningRepairCount() == 1) break;
        }
        DesignerSessionService.CompilerStatus firstPlanningRepair = designerSessions.compilerStatus(session.id());
        assertThat(firstPlanningRepair.planningRepairCount()).isEqualTo(1);
        assertThat(firstPlanningRepair.repairCount()).isZero();

        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.compilerStatus(session.id()).planningRepairCount()).isEqualTo(2);
        fake().setPackageCompilerPlanningOutput("WP-1", packageCompilationPlan("WP-1"));
        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.compilerStatus(session.id()).workflowStep()).isEqualTo("FINAL_JSON");
        assertThat(designerSessions.compilerStatus(session.id()).planningRepairCount()).isEqualTo(2);
        assertThat(designerSessions.compilerStatus(session.id()).formatRepairCount()).isEqualTo(2);
        assertThat(designerSessions.compilerStatus(session.id()).serverCompiled()).isTrue();
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void v2CompilerRejectsShellEvidenceDuringPlanningAndFreezesExecutableVerifierBlueprints() throws Exception {
        ProjectRow project = project("compiler-planned-verifiers");
        LoopSpec draftSpec = v2DocumentationSpec(project.id());
        LoopDraftRow draft = drafts.create(draftSpec);
        String design = "# 文档设计\n\nREADME 事件说明可执行自检";
        fake().setDesignerOutput(designerOutput(design, draftSpec));
        fake().setPackageCompilerOutput("WP-1", packageCompilationV2("WP-1",
                "README 事件说明可执行自检"));
        fake().setPackageCompilerPlanningOutput("WP-1", packageCompilationPlanV2("WP-1",
                "README 事件说明可执行自检", true));

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "增加 README 事件说明");
        for (int attempt = 0; attempt < 20; attempt++) {
            pollWithMandatoryQuestion(session.id());
            DesignerSessionService.CompilerStatus compiler = designerSessions.compilerStatus(session.id());
            if (compiler != null && compiler.planningRepairCount() == 1) break;
        }
        DesignerSessionService.CompilerStatus repairing = designerSessions.compilerStatus(session.id());
        assertThat(repairing.planningRepairCount()).isEqualTo(1);
        assertThat(repairing.repairCount()).isZero();
        assertThat(designerSessions.messages(session.id()).stream().map(message -> message.content()).toList())
                .anyMatch(message -> message.contains("COMPILER_PLAN_VERIFIER_INVALID")
                        && message.contains("shell launchers are forbidden"));

        fake().setPackageCompilerPlanningOutput("WP-1", packageCompilationPlanV2("WP-1",
                "README 事件说明可执行自检", false));
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
        DesignerSessionService.CompilerStatus completed = designerSessions.compilerStatus(session.id());
        assertThat(completed.planningRepairCount()).isEqualTo(1);
        assertThat(completed.repairCount()).isZero();
        assertThat(drafts.spec(drafts.get(draft.id())).stages()).singleElement().satisfies(stage -> {
            assertThat(stage.verifiers()).extracting(LoopSpec.VerifierSpec::type)
                    .containsExactly("PROCESS", "GIT_DIFF");
            assertThat(stage.verifiers().getFirst().command()).startsWith("python3", "-c");
            assertThat(stage.verifiers().getFirst().criterionIds()).containsExactly("WP-1-AC-1");
        });
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void v2CompilerRepairsShadowedPathPolicyBeforeDraftSynchronizationOrTaskCreation() throws Exception {
        ProjectRow project = project("compiler-path-policy");
        LoopSpec draftSpec = v2DocumentationSpec(project.id());
        LoopDraftRow draft = drafts.create(draftSpec);
        String design = "# 文档设计\n\nREADME 事件说明可执行自检";
        fake().setDesignerOutput(designerOutput(design, draftSpec));
        fake().setPackageCompilerOutput("WP-1", packageCompilationV2("WP-1",
                "README 事件说明可执行自检"));
        fake().setPackageCompilerPlanningOutput("WP-1", packageCompilationPlanV2("WP-1",
                "README 事件说明可执行自检", false, true));

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(),
                "阻止相互冲突的路径规则进入执行阶段");
        for (int attempt = 0; attempt < 20; attempt++) {
            pollWithMandatoryQuestion(session.id());
            DesignerSessionService.CompilerStatus compiler = designerSessions.compilerStatus(session.id());
            if (compiler != null && compiler.planningRepairCount() == 1) break;
        }

        DesignerSessionService.CompilerStatus repairing = designerSessions.compilerStatus(session.id());
        assertThat(repairing.planningRepairCount()).isEqualTo(1);
        assertThat(repairing.repairCount()).isZero();
        assertThat(designerSessions.messages(session.id()).stream().map(message -> message.content()).toList())
                .anyMatch(message -> message.contains("COMPILER_PLAN_VERIFIER_INVALID")
                        && message.contains("entirely shadowed")
                        && message.contains("event/bridge/**")
                        && message.contains("event/**"));
        assertThat(mapper.listTasks()).isEmpty();

        fake().setPackageCompilerPlanningOutput("WP-1", packageCompilationPlanV2("WP-1",
                "README 事件说明可执行自检", false));
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.compilerStatus(session.id()).planningRepairCount()).isEqualTo(1);
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void dependentPackageTrustsCompletedPredecessorAndCanonicalizesMechanicalCompilerFields() throws Exception {
        ProjectRow project = project("dependent-package-compiler");
        LoopDraftRow draft = drafts.create(v2DocumentationSpec(project.id()));
        fake().setDecomposerOutput(decomposition("DECOMPOSED", "先交付事件核心，再融合状态机", 2));

        String firstDesign = "# WP-1 设计\n\nREADME 事件说明可执行自检";
        fake().setPackageDesignerOutput("WP-1", firstDesign);
        fake().setPackageCompilerPlanningOutput("WP-1", packageCompilationPlanV2("WP-1",
                "README 事件说明可执行自检", false));
        fake().setPackageCompilerOutput("WP-1", packageCompilationV2("WP-1",
                "README 事件说明可执行自检"));

        String dependentExcerpt = "依赖前置包提供的 `EventPublisher`，发布事件后状态机推进到 PAID。";
        String secondDesign = "# WP-2 设计\n\n" + dependentExcerpt
                + "\n\n聚焦单元测试：`mvn -q -Dtest=EventStateBridgeTest test`。";
        fake().setPackageDesignerOutput("WP-2", secondDesign);
        fake().setPackageCompilerPlanningOutput("WP-2", mechanicallyInconsistentJavaPlan(
                "依赖前置包提供的 EventPublisher，发布事件后状态机推进到 PAID。"));
        fake().setPackageCompilerOutput("WP-2", canonicalJavaCompilation(dependentExcerpt));

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(),
                "先新增事件发布能力，再让监听器驱动状态机推进");
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.workPackageStatuses(session.id())).extracting(status -> status.state())
                .containsExactly("APPROVED", "APPROVED");
        assertThat(designerSessions.workPackageStatuses(session.id()).get(1).compilerPlanningRepairCount()).isZero();
        assertThat(drafts.spec(drafts.get(draft.id())).stages().get(1)).satisfies(stage -> {
            assertThat(stage.workPackageId()).isEqualTo("WP-2");
            assertThat(stage.acceptanceCriteria()).singleElement()
                    .extracting(LoopSpec.AcceptanceCriterion::id).isEqualTo("WP-2-AC-1");
            assertThat(stage.verifiers().getFirst().criterionIds()).containsExactly("WP-2-AC-1");
            assertThat(stage.verifiers().getFirst().testTargets()).containsExactly("EventStateBridgeTest");
            assertThat(stage.verifiers().get(1).command()).containsExactly("mvn", "test");
            assertThat(stage.verifiers().get(1).criterionIds()).isEmpty();
            assertThat(stage.verifiers().get(1).testTargets()).isEmpty();
        });
        assertThat(fake().promptHistory().stream().map(FakeOpenCodeClient.PromptCall::prompt)
                .filter(prompt -> prompt.contains("Required workPackageId: WP-2")
                        && prompt.contains("LOOPSPEC_COMPILATION_PLAN_JSON_START")))
                .singleElement().satisfies(prompt -> assertThat(prompt)
                        .contains("immutable pre-execution baseline")
                        .contains("\"workPackageId\":\"WP-1\"")
                        .contains("\"state\":\"APPROVED\"")
                        .contains("Designer-declared focused test evidence")
                        .contains("mvn -q -Dtest=EventStateBridgeTest test")
                        .contains("must not return", "MISSING_SCOPE"));
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void manualPackageRecompileReactivatesTheSameRequirementAndCanCompleteAggregation() throws Exception {
        ProjectRow project = project("manual-recompile");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String design = "# 可恢复设计\n\n提供明确且可观察的结果。";
        fake().setDesignerOutput(designerOutput(design, legacySpec(project.id())));
        fake().setCompilerOutput("<!-- LOOPSPEC_COMPILATION_JSON_START -->{}<!-- LOOPSPEC_COMPILATION_JSON_END -->");
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "验证人工重新编译恢复");
        pollUntilSettled(session.id());
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.requirementStatus(session.id()).state()).isEqualTo("WAITING_INPUT");

        fake().setCompilerOutput(packageCompilation("WP-1", design));
        fake().setPackageCompilerPlanningOutput("WP-1", null);
        designerSessions.retryPackageCompilation(session.id(), "WP-1");
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow reviewing = designerSessions.get(session.id());
        var workPackage = designerSessions.workPackageStatuses(session.id()).getFirst();
        designerSessions.approvePackage(session.id(), "WP-1", reviewing.discussionRevision(),
                workPackage.designRevision());

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.requirementStatus(session.id()).state()).isEqualTo("COMPLETED");
        assertThat(drafts.spec(drafts.get(draft.id())).stages()).singleElement()
                .extracting(LoopSpec.StageSpec::workPackageId).isEqualTo("WP-1");
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void mcpProposalCannotBypassActiveDecomposition() throws Exception {
        ProjectRow project = project("mcp-active");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerOutput(decomposition("DIRECT_DESIGN", "active", 1));
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "仍在拆解中的需求");
        String args = "{\"designerSessionId\":\"" + session.id() + "\",\"projectId\":\"" + project.id()
                + "\",\"spec\":" + json.writeValueAsString(legacySpec(project.id())) + "}";
        mvc.perform(mcp(rpc(1, "tools/call", "{\"name\":\"propose_loop_spec\",\"arguments\":" + args + "}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text")
                        .value(org.hamcrest.Matchers.containsString("DESIGN_WORKFLOW_ACTIVE")));
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void taskHistoryRestoresRequirementDecompositionPackagesAndRoleTaggedMessages() throws Exception {
        ProjectRow project = project("history");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 历史设计\n\n可观察结果。", legacySpec(project.id())));
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "保存完整历史");
        pollUntilSettled(session.id());
        TaskRow task = drafts.confirm(draft.id(), "历史设计");

        mvc.perform(get("/api/tasks/{id}/design-history", task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirement.revision").value(1))
                .andExpect(jsonPath("$.decomposition.resultType").value("DIRECT_DESIGN"))
                .andExpect(jsonPath("$.workPackages[0].id").value("WP-1"))
                .andExpect(jsonPath("$.designerSession.messages[?(@.actor == 'DECOMPOSER')]").exists())
                .andExpect(jsonPath("$.designerSession.messages[?(@.workPackageId == 'WP-1')]").exists());
    }

    @Test
    void restAndMcpReturnSameV2AssessmentAndNewDraftsRejectV1() throws Exception {
        ProjectRow project = project("v2-validation");
        LoopSpec buildOnly = new LoopSpec("v2", project.id(), "Prove behavior", "",
                List.of(new LoopSpec.StageSpec("Build only", List.of(), List.of(), List.of("jar"),
                        List.of(new LoopSpec.VerifierSpec("PROCESS", List.of("mvn", "package"), null, null,
                                List.of(), List.of(), false, null, null, null, null, null, null, null,
                                null, null, null, null, List.of(), List.of("AC-1"), "BUILD", List.of())),
                        List.of(new LoopSpec.AcceptanceCriterion("AC-1", "Feature behaves correctly")), null,
                        ImplementationKind.NON_JAVA)), LoopSpec.Limits.defaults(), null, null, null);
        String requestBody = "{\"spec\":" + json.writeValueAsString(buildOnly) + "}";
        MvcResult rest = mvc.perform(post("/api/loop-drafts/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false)).andReturn();
        MvcResult mcp = mvc.perform(mcp(rpc(30, "tools/call", "{\"name\":\"validate_loop_spec\",\"arguments\":{\"spec\":"
                        + json.writeValueAsString(buildOnly) + "}}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.structuredContent.valid").value(false)).andReturn();
        assertThat(body(rest).path("errors")).isEqualTo(body(mcp).at("/result/structuredContent/errors"));
        mvc.perform(post("/api/loop-drafts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spec\":" + json.writeValueAsString(legacySpec(project.id())) + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOOPSPEC_V2_REQUIRED"));
    }

    @Test
    void springAiStreamableServerRegistersSixToolsAndRequiresBearer() throws Exception {
        ProjectRow project = project("streamable");
        assertThat(java.util.Arrays.stream(loopperMcpToolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name()).toList())
                .containsExactlyInAnyOrder("get_project_context", "propose_loop_spec", "validate_loop_spec",
                        "create_task", "start_task", "get_task_status");
        assertThat(environment.getProperty("spring.ai.mcp.server.protocol")).isEqualTo("STREAMABLE");
        assertThat(springAiMcpProperties.getCapabilities().isTool()).isTrue();
        assertThat(context.getBeanNamesForType(org.springframework.web.servlet.function.RouterFunction.class))
                .contains("webMvcStreamableServerRouterFunction");

        String initialize = rpc(10, "initialize", "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}");
        mvc.perform(post("/api/mcp-streamable").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).content(initialize))
                .andExpect(status().isUnauthorized());
        MvcResult initialized = mvc.perform(streamable(initialize, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.serverInfo.version").value("0.1.67")).andReturn();
        String sessionId = initialized.getResponse().getHeader("Mcp-Session-Id");
        mvc.perform(streamable("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}", sessionId))
                .andExpect(status().isAccepted());
        MvcResult listed = mvc.perform(streamable(rpc(11, "tools/list", "{}"), sessionId))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(listed)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("propose_loop_spec")));
        String arguments = "{\"name\":\"get_project_context\",\"arguments\":{\"projectId\":\"" + project.id() + "\"}}";
        MvcResult contextResult = mvc.perform(streamable(rpc(12, "tools/call", arguments), sessionId))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(contextResult)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(project.id())));
    }

    private FakeOpenCodeClient fake() { return (FakeOpenCodeClient) openCode; }

    private DesignerSessionRow designerRow(String id, String projectId, String draftId, String state,
                                            String workflowPhase, String at) {
        return new DesignerSessionRow(id, projectId, state, DesignerSessionService.READ_ONLY, at, at, 0,
                null, "PENDING", draftId, workflowPhase, 0, 0, 1, "WP-2");
    }

    private ProjectRow project(String name) throws Exception {
        return projects.create(name, Files.createDirectory(temp.resolve(name)).toString());
    }

    private DesignerSessionRow createConfirmedSession(String projectId, String draftId, String requirement) {
        DesignerSessionRow session = designerSessions.create(projectId, draftId, requirement);
        completeMandatoryDesignerQuestion(session.id());
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow reviewing = designerSessions.get(session.id());
        assertThat(reviewing.state()).isEqualTo("REVIEWING");
        designerSessions.confirmRequirement(session.id(), reviewing.discussionRevision());
        return designerSessions.get(session.id());
    }

    private void completeMandatoryDesignerQuestion(String sessionId) {
        DesignerSessionRow session = designerSessions.get(sessionId);
        if (!"RUNNING".equals(session.state())
                || !("DISCUSSING_REQUIREMENT".equals(session.workflowPhase())
                || "QUESTIONING_PACKAGE".equals(session.workflowPhase()))) return;
        String questionId = "question-" + session.discussionScope() + "-" + session.discussionRevision();
        fake().setPendingQuestion(session.externalSessionId(), new OpenCodeClient.PendingQuestion(
                questionId, session.externalSessionId(), List.of(new OpenCodeClient.QuestionPrompt(
                "采用推荐设计边界吗？", "设计边界", List.of(new OpenCodeClient.QuestionOption(
                "采用推荐项 (Recommended)", "保持最小且可验收的范围")), false, true))));
        designerSessions.replyQuestion(sessionId, questionId, List.of(List.of("采用推荐项 (Recommended)")));
        fake().setSessionState(session.externalSessionId(), "COMPLETED");
    }

    private void pollWithMandatoryQuestion(String sessionId) {
        completeMandatoryDesignerQuestion(sessionId);
        designerSessions.pollActiveHandoffs();
    }

    private List<String> compatibilityPackageStates(String sessionId) {
        return designerSessions.workPackageStatuses(sessionId).stream().map(status -> switch (status.state()) {
            case "APPROVED" -> "COMPLETED";
            case "QUESTIONING" -> "DESIGNING";
            default -> status.state();
        }).toList();
    }

    private void pollUntilSettled(String sessionId) {
        for (int attempt = 0; attempt < 120; attempt++) {
            DesignerSessionRow current = designerSessions.get(sessionId);
            if ("FINAL_REVIEW".equals(current.workflowPhase()) || "WAITING_INPUT".equals(current.state())
                    || "SESSION_ERROR".equals(current.state())) return;
            if ("REVIEWING_PACKAGE".equals(current.workflowPhase())) {
                var workPackage = designerSessions.workPackageStatuses(sessionId).stream()
                        .filter(item -> "REVIEWING".equals(item.state())).findFirst().orElse(null);
                if (workPackage != null) {
                    designerSessions.approvePackage(sessionId, workPackage.id(), current.discussionRevision(),
                            workPackage.designRevision());
                    continue;
                }
            }
            completeMandatoryDesignerQuestion(sessionId);
            designerSessions.pollActiveHandoffs();
        }
    }

    private void pollUntilPackageReview(String sessionId) {
        for (int attempt = 0; attempt < 80; attempt++) {
            DesignerSessionRow current = designerSessions.get(sessionId);
            if ("REVIEWING_PACKAGE".equals(current.workflowPhase())
                    || "WAITING_INPUT".equals(current.state()) || "SESSION_ERROR".equals(current.state())) return;
            pollWithMandatoryQuestion(sessionId);
        }
    }

    private void pollUntilPackageStates(String sessionId, String... states) {
        for (int attempt = 0; attempt < 40; attempt++) {
            List<String> current = compatibilityPackageStates(sessionId);
            if (current.equals(List.of(states))) return;
            DesignerSessionRow session = designerSessions.get(sessionId);
            if ("REVIEWING_PACKAGE".equals(session.workflowPhase())) {
                var workPackage = designerSessions.workPackageStatuses(sessionId).stream()
                        .filter(item -> "REVIEWING".equals(item.state())).findFirst().orElse(null);
                if (workPackage != null) {
                    designerSessions.approvePackage(sessionId, workPackage.id(), session.discussionRevision(),
                            workPackage.designRevision());
                    continue;
                }
            }
            completeMandatoryDesignerQuestion(sessionId);
            designerSessions.pollActiveHandoffs();
        }
    }

    private void pollUntilCompilerState(String sessionId, String state, int repairCount) {
        for (int attempt = 0; attempt < 40; attempt++) {
            DesignerSessionService.CompilerStatus compiler = designerSessions.compilerStatus(sessionId);
            if (compiler != null && state.equals(compiler.state())
                    && (compiler.repairCount() == repairCount
                    || compiler.formatRepairCount() + compiler.semanticRepairCount() == repairCount)) return;
            completeMandatoryDesignerQuestion(sessionId);
            designerSessions.pollActiveHandoffs();
        }
    }

    private void assertPackageStates(String sessionId, String... states) {
        assertThat(compatibilityPackageStates(sessionId)).containsExactly(states);
    }

    private String decomposition(String status, String goal, int count) throws Exception {
        List<Map<String, Object>> packages = new ArrayList<>();
        if ("DIRECT_DESIGN".equals(status) || "DECOMPOSED".equals(status)) for (int index = 1; index <= count; index++) {
            String id = "WP-" + index;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id); item.put("title", "能力 " + index); item.put("objective", "交付能力 " + index);
            item.put("scopeIn", List.of("能力 " + index)); item.put("scopeOut", List.of("其他发布边界"));
            item.put("dependencies", index == 1 ? List.of() : List.of("WP-" + (index - 1)));
            item.put("deliverables", List.of("能力 " + index + " 实现"));
            item.put("acceptanceIntent", List.of("能力 " + index + " 可观察")); item.put("requirementRefs", List.of("RQ-1"));
            packages.add(item);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", status); envelope.put("normalizedGoal", goal);
        envelope.put("globalConstraints", List.of()); envelope.put("workPackages", packages);
        envelope.put("designGaps", status.equals("NEEDS_INPUT")
                ? List.of(Map.of("code", "MISSING_SCOPE", "detail", "范围不明确")) : List.of());
        envelope.put("reason", status.equals("MULTI_TASK_REQUIRED") ? "包含多个独立发布边界" : null);
        return "<!-- TASK_DECOMPOSITION_JSON_START -->\n" + json.writeValueAsString(envelope)
                + "\n<!-- TASK_DECOMPOSITION_JSON_END -->";
    }

    private String packageCompilation(String packageId, String excerpt) throws Exception {
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("完成 " + packageId, List.of("src/**"), List.of(".env"),
                List.of(packageId + " 交付物"), List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null,
                "README.md", null, List.of("README.md"), List.of(), true)), List.of(), null, null, packageId);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", "COMPILED"); envelope.put("summary", packageId + " 编译完成");
        envelope.put("stages", List.of(stage)); envelope.put("criterionSources", List.of());
        envelope.put("handoffSummary", packageId + " 已交付，后续包可复用。" + excerpt.substring(0, Math.min(8, excerpt.length())));
        envelope.put("designGaps", List.of());
        return "<!-- LOOPSPEC_COMPILATION_JSON_START -->\n" + json.writeValueAsString(envelope)
                + "\n<!-- LOOPSPEC_COMPILATION_JSON_END -->";
    }

    private String packageCompilationPlan(String packageId) throws Exception {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("objective", "完成 " + packageId);
        stage.put("allowedPaths", List.of("src/**"));
        stage.put("forbiddenPaths", List.of(".env"));
        stage.put("deliverables", List.of(packageId + " 交付物"));
        stage.put("implementationKind", null);
        stage.put("workPackageId", packageId);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", "COMPILED");
        envelope.put("summary", packageId + " 已完成阶段与证据规划");
        envelope.put("stages", List.of(stage));
        envelope.put("evidenceMappings", List.of());
        envelope.put("handoffSummary", packageId + " 已交付，后续包可复用。");
        envelope.put("designGaps", List.of());
        return "<!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->\n" + json.writeValueAsString(envelope)
                + "\n<!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->";
    }

    private String packageCompilationPlanV2(String packageId, String excerpt, boolean shell) throws Exception {
        return packageCompilationPlanV2(packageId, excerpt, shell, false);
    }

    private String packageCompilationPlanV2(String packageId, String excerpt, boolean shell,
                                            boolean shadowedPathPolicy) throws Exception {
        String criterionId = packageId + "-AC-1";
        LoopSpec.VerifierSpec behavior = new LoopSpec.VerifierSpec("PROCESS",
                shell ? List.of("bash", "-lc", "grep -Fq event README.md")
                        : List.of("python3", "-c", "from pathlib import Path; assert 'event' in Path('README.md').read_text(); print('DOC_CHECK_OK')"),
                null, null, List.of(), List.of(), null, shell ? null : "DOC_CHECK_OK",
                null, null, null, null, null, null, null, null, null, null, List.of(),
                List.of(criterionId), "SELF_CHECK", List.of());
        LoopSpec.VerifierSpec scope = new LoopSpec.VerifierSpec("GIT_DIFF", List.of(), null, true,
                shadowedPathPolicy ? List.of("src/main/java/com/spdb/upfs/event/bridge/**") : List.of("README.md"),
                shadowedPathPolicy ? List.of("src/main/java/com/spdb/upfs/event/**") : List.of(".env"), true);
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("objective", "完成 " + packageId);
        stage.put("allowedPaths", shadowedPathPolicy
                ? List.of("src/main/java/com/spdb/upfs/event/bridge/**") : List.of("README.md"));
        stage.put("forbiddenPaths", shadowedPathPolicy
                ? List.of("src/main/java/com/spdb/upfs/event/**") : List.of(".env"));
        stage.put("deliverables", List.of("README.md"));
        stage.put("verifiers", List.of(behavior, scope));
        stage.put("verificationRuntime", null);
        stage.put("implementationKind", "NON_JAVA");
        stage.put("workPackageId", packageId);
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("stageIndex", 0); mapping.put("criterionId", criterionId);
        mapping.put("description", "README 包含可执行验证的事件说明");
        mapping.put("designerExcerpt", excerpt); mapping.put("verificationMode", "MACHINE");
        mapping.put("judgeRubric", null); mapping.put("judgeOnlyReason", null);
        mapping.put("verifierStrategy", "direct Python content self-check");
        mapping.put("testCommand", List.of()); mapping.put("testTargets", List.of());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("contractVersion", 2); envelope.put("status", "COMPILED");
        envelope.put("summary", packageId + " 已冻结可执行验证器蓝图");
        envelope.put("stages", List.of(stage)); envelope.put("evidenceMappings", List.of(mapping));
        envelope.put("handoffSummary", packageId + " 已交付，后续包可复用。");
        envelope.put("designGaps", List.of());
        return "<!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->\n" + json.writeValueAsString(envelope)
                + "\n<!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->";
    }

    private String packageCompilationV2(String packageId, String excerpt) throws Exception {
        String criterionId = packageId + "-AC-1";
        LoopSpec.VerifierSpec behavior = new LoopSpec.VerifierSpec("PROCESS",
                List.of("python3", "-c", "from pathlib import Path; assert 'event' in Path('README.md').read_text(); print('DOC_CHECK_OK')"),
                null, null, List.of(), List.of(), null, "DOC_CHECK_OK", null, null, null, null,
                null, null, null, null, null, null, List.of(), List.of(criterionId), "SELF_CHECK", List.of());
        LoopSpec.VerifierSpec scope = new LoopSpec.VerifierSpec("GIT_DIFF", List.of(), null, true,
                List.of("README.md"), List.of(".env"), true);
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("完成 " + packageId, List.of("README.md"),
                List.of(".env"), List.of("README.md"), List.of(behavior, scope),
                List.of(new LoopSpec.AcceptanceCriterion(criterionId, "README 包含可执行验证的事件说明",
                        "MACHINE", null, null)), null, ImplementationKind.NON_JAVA, packageId);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", "COMPILED"); envelope.put("summary", packageId + " 编译完成");
        envelope.put("stages", List.of(stage));
        envelope.put("criterionSources", List.of(Map.of("stageIndex", 0, "criterionId", criterionId,
                "excerpt", excerpt)));
        envelope.put("handoffSummary", packageId + " 已交付，后续包可复用。");
        envelope.put("designGaps", List.of());
        return "<!-- LOOPSPEC_COMPILATION_JSON_START -->\n" + json.writeValueAsString(envelope)
                + "\n<!-- LOOPSPEC_COMPILATION_JSON_END -->";
    }

    private String mechanicallyInconsistentJavaPlan(String approximateExcerpt) throws Exception {
        List<String> testCommand = List.of("mvn", "-q", "-Dtest=EventStateBridgeTest", "test");
        LoopSpec.VerifierSpec behavior = new LoopSpec.VerifierSpec("PROCESS", testCommand,
                null, null, List.of(), List.of(), null, null, null, null, null, null,
                null, null, null, null, null, null, List.of(), List.of("temporary-id"), "TEST", List.of());
        LoopSpec.VerifierSpec fullSuite = new LoopSpec.VerifierSpec("PROCESS", List.of("mvn", "test"),
                null, null, List.of(), List.of(), null, null, null, null, null, null,
                null, null, null, null, null, null, List.of(), List.of("temporary-meta"), "TEST", List.of());
        LoopSpec.VerifierSpec scope = new LoopSpec.VerifierSpec("GIT_DIFF", List.of(), null, true,
                List.of("src/main/java/**", "src/test/java/**"), List.of(".env"), true);
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("objective", "事件发布驱动状态机推进");
        stage.put("allowedPaths", List.of("src/main/java/**", "src/test/java/**"));
        stage.put("forbiddenPaths", List.of(".env"));
        stage.put("deliverables", List.of("事件状态桥接实现和聚焦单元测试"));
        stage.put("verifiers", List.of(behavior, fullSuite, scope));
        stage.put("verificationRuntime", null);
        stage.put("implementationKind", "JAVA_PRODUCTION");
        stage.put("workPackageId", "WP-2");
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("stageIndex", 0);
        mapping.put("criterionId", "temporary-id");
        mapping.put("description", "发布事件后状态机推进到 PAID");
        mapping.put("designerExcerpt", approximateExcerpt);
        mapping.put("verificationMode", "MACHINE");
        mapping.put("judgeRubric", null);
        mapping.put("judgeOnlyReason", null);
        mapping.put("verifierStrategy", "EventStateBridgeTest 聚焦 Maven 单元测试");
        mapping.put("testCommand", List.of());
        mapping.put("testTargets", List.of());
        Map<String, Object> metaMapping = new LinkedHashMap<>();
        metaMapping.put("stageIndex", 0);
        metaMapping.put("criterionId", "temporary-meta");
        metaMapping.put("description", "全量测试通过");
        metaMapping.put("designerExcerpt", approximateExcerpt);
        metaMapping.put("verificationMode", "MACHINE");
        metaMapping.put("judgeRubric", null);
        metaMapping.put("judgeOnlyReason", null);
        metaMapping.put("verifierStrategy", "Maven 全量回归测试");
        metaMapping.put("testCommand", List.of("mvn", "test"));
        metaMapping.put("testTargets", List.of());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("contractVersion", 2);
        envelope.put("status", "COMPILED");
        envelope.put("summary", "WP-2 语义规划完成，机械字段待服务端规范化");
        envelope.put("stages", List.of(stage));
        envelope.put("evidenceMappings", List.of(mapping, metaMapping));
        envelope.put("handoffSummary", "事件驱动状态推进能力可供后续包复用。");
        envelope.put("designGaps", List.of());
        return "<!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->\n" + json.writeValueAsString(envelope)
                + "\n<!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->";
    }

    private String canonicalJavaCompilation(String exactExcerpt) throws Exception {
        String criterionId = "WP-2-AC-1";
        LoopSpec.VerifierSpec behavior = new LoopSpec.VerifierSpec("PROCESS",
                List.of("mvn", "-q", "-Dtest=EventStateBridgeTest", "test"), null, null,
                List.of(), List.of(), null, null, null, null, null, null, null, null,
                null, null, null, null, List.of(), List.of(criterionId), "TEST",
                List.of("EventStateBridgeTest"));
        LoopSpec.VerifierSpec fullSuite = new LoopSpec.VerifierSpec("PROCESS", List.of("mvn", "test"),
                null, null, List.of(), List.of(), null, null, null, null, null, null, null, null,
                null, null, null, null, List.of(), List.of(), "TEST", List.of());
        LoopSpec.VerifierSpec scope = new LoopSpec.VerifierSpec("GIT_DIFF", List.of(), null, true,
                List.of("src/main/java/**", "src/test/java/**"), List.of(".env"), true);
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("事件发布驱动状态机推进",
                List.of("src/main/java/**", "src/test/java/**"), List.of(".env"),
                List.of("事件状态桥接实现和聚焦单元测试"), List.of(behavior, fullSuite, scope),
                List.of(new LoopSpec.AcceptanceCriterion(criterionId, "发布事件后状态机推进到 PAID",
                        "MACHINE", null, null)), null, ImplementationKind.JAVA_PRODUCTION, "WP-2");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", "COMPILED");
        envelope.put("summary", "WP-2 编译完成");
        envelope.put("stages", List.of(stage));
        envelope.put("criterionSources", List.of(Map.of("stageIndex", 0, "criterionId", criterionId,
                "excerpt", exactExcerpt)));
        envelope.put("handoffSummary", "事件驱动状态推进能力可供后续包复用。");
        envelope.put("designGaps", List.of());
        return "<!-- LOOPSPEC_COMPILATION_JSON_START -->\n" + json.writeValueAsString(envelope)
                + "\n<!-- LOOPSPEC_COMPILATION_JSON_END -->";
    }

    private String decompositionPlan(String status, String goal) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", status);
        envelope.put("normalizedGoal", goal);
        envelope.put("globalConstraints", List.of());
        envelope.put("workPackages", List.of());
        envelope.put("coverageMappings", List.of());
        envelope.put("dependencyEvidence", List.of());
        envelope.put("designGaps", List.of(Map.of("code", "MISSING_SCOPE", "detail", "范围不明确")));
        envelope.put("reason", null);
        return "<!-- TASK_DECOMPOSITION_PLAN_JSON_START -->\n" + json.writeValueAsString(envelope)
                + "\n<!-- TASK_DECOMPOSITION_PLAN_JSON_END -->";
    }

    private String unwrapped(String output, String startMarker, String endMarker) {
        return output.replace(startMarker, "").replace(endMarker, "").trim();
    }

    private String designIncomplete(String code, String detail) throws Exception {
        return "<!-- LOOPSPEC_COMPILATION_JSON_START -->\n" + json.writeValueAsString(Map.of(
                "status", "DESIGN_INCOMPLETE", "summary", "设计不完整", "stages", List.of(),
                "criterionSources", List.of(), "handoffSummary", "",
                "designGaps", List.of(Map.of("code", code, "detail", detail))))
                + "\n<!-- LOOPSPEC_COMPILATION_JSON_END -->";
    }

    private LoopSpec legacySpec(String projectId) {
        return new LoopSpec("v1", projectId, "Implement the validated designer plan", "Keep the worktree isolated",
                List.of(new LoopSpec.StageSpec("Implement the plan", List.of("src/**"), List.of(".env"),
                        List.of("README.md"), List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null,
                        "README.md", null, List.of("README.md"), List.of(), true)))),
                new LoopSpec.Limits(3, 3, 2, 2, 3600L, 120L, 60L),
                new LoopSpec.ModelSpec("opencode", "deepseek", false),
                new LoopSpec.SessionPolicy(true, true), "Continue from verified evidence");
    }

    private LoopSpec withoutWorkPackageIds(LoopSpec spec) {
        List<LoopSpec.StageSpec> stages = spec.stages().stream().map(stage -> new LoopSpec.StageSpec(
                stage.objective(), stage.allowedPaths(), stage.forbiddenPaths(), stage.deliverables(),
                stage.verifiers(), stage.acceptanceCriteria(), stage.verificationRuntime(),
                stage.implementationKind(), null)).toList();
        return new LoopSpec(spec.schemaVersion(), spec.projectId(), spec.goal(), spec.context(), stages,
                spec.limits(), spec.model(), spec.sessionPolicy(), spec.nextAttemptPromptTemplate(), spec.budget());
    }

    private LoopSpec v2DocumentationSpec(String projectId) {
        String script = "print('DRAFT_CHECK_OK')";
        LoopSpec.VerifierSpec verifier = new LoopSpec.VerifierSpec("PROCESS", List.of("python3", "-c", script),
                null, null, List.of(), List.of(), null, "DRAFT_CHECK_OK", null, null, null, null,
                null, null, null, null, null, null, List.of(), List.of("AC-1"), "SELF_CHECK", List.of());
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("设计 README 文档", List.of("README.md"),
                List.of(".env"), List.of("README.md"), List.of(verifier),
                List.of(new LoopSpec.AcceptanceCriterion("AC-1", "README 文档设计可执行验证", "MACHINE",
                        null, null)), null, ImplementationKind.NON_JAVA);
        return new LoopSpec("v2", projectId, "设计 README 文档", "", List.of(stage),
                LoopSpec.Limits.defaults(), null, null, null);
    }

    private String designerOutput(String markdown, LoopSpec spec) throws Exception {
        return markdown + "\n\n<!-- LOOPSPEC_JSON_START -->\n" + json.writeValueAsString(spec)
                + "\n<!-- LOOPSPEC_JSON_END -->";
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mcp(String requestBody) {
        return post("/api/mcp").header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(requestBody);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder streamable(String requestBody, String sessionId) {
        var builder = post("/api/mcp-streamable").header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(requestBody);
        if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
        return builder;
    }

    private String rpc(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
