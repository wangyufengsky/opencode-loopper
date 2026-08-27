package io.opencode.loopper.api;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopDraftStatus;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.DesignDiscussionRevisionRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerAutoModeRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskLineageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.WorkPackageRoleProfileRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeStructuredSchemas;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.DesignerSessionService;
import io.opencode.loopper.service.DesignerAutoModeService;
import io.opencode.loopper.service.AnalysisReportService;
import io.opencode.loopper.service.DirectArtifactDesignService;
import io.opencode.loopper.service.DirectMaintenanceDesignService;
import io.opencode.loopper.service.TaskProfileService;
import io.opencode.loopper.service.LoopDraftService;
import io.opencode.loopper.service.ProjectService;
import io.opencode.loopper.service.ProjectStackProfileService;
import io.opencode.loopper.service.ProjectStackSnapshot;
import io.opencode.loopper.service.RollingPackagePlanGenerationService;
import io.opencode.loopper.service.ServiceUnavailableException;
import io.opencode.loopper.service.TaskService;
import io.opencode.loopper.service.WorkPackageRoleService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired private DesignerAutoModeService designerAutoMode;
    @Autowired private AnalysisReportService reports;
    @Autowired private DirectArtifactDesignService directArtifacts;
    @Autowired private DirectMaintenanceDesignService directMaintenance;
    @Autowired private TaskProfileService taskProfiles;
    @Autowired private ProjectStackProfileService stackProfiles;
    @Autowired private LoopperProperties properties;
    @Autowired private LoopDraftService drafts;
    @Autowired private TaskService tasks;
    @Autowired private WorkPackageRoleService workPackageRoles;
    @Autowired private RollingPackagePlanGenerationService rollingPlanGeneration;
    @Autowired private LoopperMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OpenCodeClient openCode;
    @Autowired private ToolCallbackProvider loopperMcpToolCallbackProvider;
    @Autowired private ApplicationContext context;
    @Autowired private org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties springAiMcpProperties;
    @Autowired private org.springframework.core.env.Environment environment;
    @TempDir Path temp;
    private final Set<String> legacyAcceptanceSessions = new LinkedHashSet<>();

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        fake().reset();
        legacyAcceptanceSessions.clear();
        fake().setStructuredCapability(new OpenCodeClient.StructuredOutputCapability(
                OpenCodeClient.CapabilityState.UNAVAILABLE, OpenCodeClient.CapabilityState.UNKNOWN,
                "marker compatibility fixture"));
        properties.setTaskProfileRouterTimeout(Duration.ofSeconds(240));
        properties.setRollingPackagesEnabled(false);
    }

    @Test
    void rollingThreePackageFlowFreezesRealFactsAndLaunchesOneFinalJudgePair() throws Exception {
        properties.setRollingPackagesEnabled(true);
        ProjectRow project = project("rolling-large-package-one");
        Files.writeString(Path.of(project.rootPath()).resolve("README.md"), "event rolling baseline\n");
        Files.createDirectories(Path.of(project.rootPath()).resolve("tests"));
        Files.writeString(Path.of(project.rootPath()).resolve("tests/__init__.py"), "");
        Files.writeString(Path.of(project.rootPath()).resolve("tests/test_acceptance.py"), """
                import pathlib
                import unittest

                class RollingPackageAcceptanceTest(unittest.TestCase):
                    def test_event_marker_remains_visible(self):
                        self.assertIn("event", pathlib.Path("README.md").read_text())
                """);
        fake().setTaskRouterOutput("{\"intent\":\"SOFTWARE_CHANGE\",\"artifactKinds\":[\"SOURCE_CODE\"],"
                + "\"complexity\":\"PACKAGED\"}");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerOutput(decomposition("DECOMPOSED", "先建立核心，再接入业务", 3));
        String firstDesign = rollingControlledDesign("# WP-1 设计\n\n交付可独立验证的核心能力。");
        setPackageDesignerOutput("WP-1", firstDesign);
        fake().setPackageCompilerPlanningOutput("WP-1",
                packageCompilationPlanV2("WP-1", "交付可独立验证的核心能力。", false));
        String secondDesign = rollingControlledDesign("# WP-2 设计\n\n读取包1真实产物后接入业务能力。");
        setPackageDesignerOutput("WP-2", secondDesign);
        fake().setPackageCompilerPlanningOutput("WP-2",
                packageCompilationPlanV2("WP-2", "读取包1真实产物后接入业务能力。", false));
        String thirdDesign = rollingControlledDesign("# WP-3 设计\n\n基于前两包事实完成集成。");
        setPackageDesignerOutput("WP-3", thirdDesign);
        fake().setPackageCompilerPlanningOutput("WP-3",
                packageCompilationPlanV2("WP-3", "基于前两包事实完成集成。", false));

        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "实现大型软件能力并分两包交付");
        designerSessions.pollActiveHandoffs();
        TaskProfileService.View detected = taskProfiles.current(created.id());
        TaskProfileService.View updated = designerSessions.updateTaskProfile(created.id(),
                io.opencode.loopper.domain.TaskIntent.SOFTWARE_CHANGE,
                io.opencode.loopper.domain.ArtifactKind.SOURCE_CODE, true, detected.version());
        if (!updated.confirmationReady()) {
            taskProfiles.confirmRecommendation(created.id(), updated.version());
        }
        designerSessions.continueAfterTaskProfileDecision(created.id());
        completeMandatoryDesignerQuestion(created.id());
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow session = designerSessions.get(created.id());
        TaskProfileService.View recomputed = taskProfiles.current(session.id());
        TaskProfileService.View ready = designerSessions.updateTaskProfile(session.id(),
                io.opencode.loopper.domain.TaskIntent.SOFTWARE_CHANGE,
                io.opencode.loopper.domain.ArtifactKind.SOURCE_CODE, true, recomputed.version());
        if (!ready.confirmationReady()) {
            taskProfiles.confirmRecommendation(session.id(), ready.version());
        }
        completeMandatoryDesignerQuestion(session.id());
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();
        session = designerSessions.get(session.id());
        TaskProfileService.View finalProfile = taskProfiles.current(session.id());
        if (!finalProfile.confirmationReady()) {
            taskProfiles.confirmRecommendation(session.id(), finalProfile.version());
        }
        designerSessions.confirmRequirement(session.id(), session.discussionRevision());
        session = designerSessions.get(session.id());
        pollUntilPackageReview(session.id());

        assertThat(mapper.listTasks()).isEmpty();
        assertThat(taskProfiles.current(session.id())).satisfies(profile -> {
            assertThat(profile.state()).isEqualTo("FROZEN");
            assertThat(profile.intent().name()).isEqualTo("SOFTWARE_CHANGE");
            assertThat(profile.workflowTemplate().name()).isEqualTo("FULL_PACKAGE_DESIGN");
        });
        DesignerSessionRow reviewing = designerSessions.get(session.id());
        assertThat(reviewing.workflowPhase()).as("session=%s packages=%s compiler=%s messages=%s", reviewing,
                designerSessions.workPackageStatuses(session.id()), designerSessions.compilerStatus(session.id()),
                designerSessions.messages(session.id())).isEqualTo("REVIEWING_PACKAGE");
        var first = designerSessions.workPackageStatuses(session.id()).stream()
                .filter(item -> "REVIEWING".equals(item.state())).findFirst().orElseThrow();
        designerSessions.approvePackage(session.id(), first.id(), reviewing.discussionRevision(),
                first.designRevision());

        TaskRow task = mapper.listTasks().getFirst();
        String taskId = task.id();
        String firstRunId = mapper.listTaskPackageRuns(taskId).getFirst().id();
        assertThat(mapper.listTasks()).singleElement().extracting(TaskRow::id).isEqualTo(task.id());
        assertThat(task.state()).isEqualTo("PENDING_START");
        assertThat(task.executionMode()).isEqualTo("ROLLING_PACKAGES");
        assertThat(task.workspacePolicy()).isEqualTo("PINNED_DIRECT");
        assertThat(mapper.findTaskQueue(task.id())).isEmpty();
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
        assertThat(mapper.listSessions(task.id())).isEmpty();
        assertThat(mapper.listTaskPackageRuns(task.id())).extracting(row -> row.state())
                .containsExactly("EXECUTION_READY", "PLANNED", "PLANNED");
        assertThat(mapper.listTaskSpecRevisions(task.id())).singleElement()
                .extracting(row -> row.revision()).isEqualTo(1);
        assertThat(mapper.listStages(task.id())).allSatisfy(stage ->
                assertThat(stage.packageRunId()).isEqualTo(firstRunId));
        assertThat(mapper.findDesignerSession(session.id())).hasValueSatisfying(row ->
                assertThat(row.taskId()).isEqualTo(taskId));

        var packageOne = mapper.currentTaskPackageRun(task.id()).orElseThrow();
        tasks.startRollingPackage(task.id(), packageOne.id(), task.version(), packageOne.version());
        Files.writeString(Path.of(project.rootPath()).resolve("README.md"),
                "event rolling baseline\nfact from package one\n");
        tasks.verify(task.id());

        assertThat(mapper.listPackageFactSnapshots(task.id())).hasSize(1);
        assertThat(tasks.judges(task.id())).isEmpty();
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isPresent();
        assertThat(Files.readString(Path.of(project.rootPath()).resolve("README.md")))
                .contains("fact from package one");
        pollUntilRollingPackageReview(task.id(), session.id());
        assertThat(fake().promptHistory()).anySatisfy(call -> assertThat(call.prompt())
                .contains("### 已冻结事实", "AI 导航摘要（非证据）"));

        var replanAnchor = mapper.currentTaskPackageRun(task.id()).orElseThrow();
        fake().setDecomposerOutput("""
                <!-- ROLLING_PACKAGE_PLAN_JSON_START -->
                {"packages":[
                  {"packageKey":"WP-2","title":"业务接入","objective":"按包1真实产物接入业务能力",\
                   "replaces":["WP-2"],"dependencies":["WP-1"],"requirementRefs":["RQ-2"]},
                  {"packageKey":"WP-3","title":"集成收口","objective":"基于前两包事实完成集成",\
                   "replaces":["WP-3"],"dependencies":["WP-2"],"requirementRefs":["RQ-3"]}
                ]}
                <!-- ROLLING_PACKAGE_PLAN_JSON_END -->
                """);
        var generating = rollingPlanGeneration.suggest(task.id(), mapper.findTask(task.id()).orElseThrow().version(),
                replanAnchor.id(), replanAnchor.version());
        assertThat(generating.state()).isEqualTo("GENERATING");
        rollingPlanGeneration.pollGenerating();
        assertThat(mapper.findTaskPackagePlanRevision(generating.id())).hasValueSatisfying(proposal -> {
            assertThat(proposal.state()).isEqualTo("PROPOSED");
            assertThat(proposal.origin()).isEqualTo("AI");
            assertThat(proposal.impactJson()).contains("dependencyChanges", "split", "merged");
            assertThat(proposal.baseCheckpointId())
                    .isEqualTo(mapper.listPackageFactSnapshots(taskId).getFirst().checkpointId());
        });
        assertThat(fake().promptHistory()).anySatisfy(call -> {
            assertThat(call.prompt()).contains("原始冻结需求", "当前未执行计划", "已冻结事实索引");
            assertThat(Files.readString(fake().sessionWorktree(call.sessionId()).resolve("README.md")))
                    .contains("fact from package one");
        });
        assertThat(mapper.listPackageFactSnapshots(task.id())).hasSize(1);

        var packageTwo = mapper.currentTaskPackageRun(task.id()).orElseThrow();
        String packageTwoWorkPackageId = packageTwo.packageKey();
        var packageTwoDesign = designerSessions.workPackageStatuses(session.id()).stream()
                .filter(item -> item.id().equals(packageTwoWorkPackageId)).findFirst().orElseThrow();
        DesignerSessionRow packageTwoReview = designerSessions.get(session.id());
        designerSessions.approvePackage(session.id(), packageTwoDesign.id(),
                packageTwoReview.discussionRevision(), packageTwoDesign.designRevision());
        task = mapper.findTask(task.id()).orElseThrow();
        packageTwo = mapper.findTaskPackageRun(packageTwo.id()).orElseThrow();
        TaskRow packageTwoStarted = tasks.startRollingPackage(task.id(), packageTwo.id(), task.version(), packageTwo.version());
        assertThat(packageTwoStarted.state()).as("errors=%s events=%s stages=%s cycles=%s",
                mapper.listErrors(task.id()), mapper.eventsAfter(task.id(), 0), mapper.listStages(task.id()),
                mapper.listTaskExecutionCycles(task.id())).isEqualTo("RUNNING");
        Files.writeString(Path.of(project.rootPath()).resolve("README.md"),
                "event rolling baseline\nfact from package one\nfact from package two\n");
        tasks.verify(task.id());

        assertThat(mapper.listPackageFactSnapshots(task.id())).hasSize(2);
        assertThat(tasks.judges(task.id())).isEmpty();
        pollUntilRollingPackageReview(task.id(), session.id());
        var packageThree = mapper.currentTaskPackageRun(task.id()).orElseThrow();
        String packageThreeWorkPackageId = packageThree.packageKey();
        var packageThreeDesign = designerSessions.workPackageStatuses(session.id()).stream()
                .filter(item -> item.id().equals(packageThreeWorkPackageId)).findFirst().orElseThrow();
        DesignerSessionRow packageThreeReview = designerSessions.get(session.id());
        designerSessions.approvePackage(session.id(), packageThreeDesign.id(),
                packageThreeReview.discussionRevision(), packageThreeDesign.designRevision());
        task = mapper.findTask(task.id()).orElseThrow();
        packageThree = mapper.findTaskPackageRun(packageThree.id()).orElseThrow();
        TaskRow packageThreeStarted = tasks.startRollingPackage(task.id(), packageThree.id(), task.version(), packageThree.version());
        assertThat(packageThreeStarted.state()).as("errors=%s events=%s stages=%s cycles=%s",
                mapper.listErrors(task.id()), mapper.eventsAfter(task.id(), 0), mapper.listStages(task.id()),
                mapper.listTaskExecutionCycles(task.id())).isEqualTo("RUNNING");
        Files.writeString(Path.of(project.rootPath()).resolve("README.md"),
                "event rolling baseline\nfact from package one\nfact from package two\nfact from package three\n");
        tasks.verify(task.id());

        assertThat(mapper.listPackageFactSnapshots(task.id())).hasSize(3);
        assertThat(mapper.listTaskPackageRuns(task.id())).extracting(row -> row.state())
                .containsExactly("FACT_FROZEN", "FACT_FROZEN", "FACT_FROZEN");
        assertThat(tasks.judges(task.id())).extracting(row -> row.role())
                .containsExactlyInAnyOrder("REQUIREMENT", "RISK");
        assertThat(mapper.listTaskSpecRevisions(task.id())).hasSize(3);
        assertThat(mapper.listStages(task.id())).hasSize(3);
        for (String packageId : List.of("WP-1", "WP-2", "WP-3")) {
            var compilation = mapper.findLatestLoopSpecCompilationForPackage(session.id(), packageId).orElseThrow();
            assertThat(mapper.findDesignAcceptancePlanning(compilation.id())).hasValueSatisfying(planning -> {
                assertThat(planning.contractVersion()).isEqualTo("DESIGN_ACCEPTANCE_V7");
                assertThat(planning.factsJson()).contains(
                        "mutationObligations", "README.md", "tests/test_acceptance.py", "sourceSha256");
            });
        }
    }

    @Test
    void routerIsPersistedAsyncAndRerunsForTheCompleteRequirementSnapshot() throws Exception {
        fake().setStructuredCapability(new OpenCodeClient.StructuredOutputCapability(
                OpenCodeClient.CapabilityState.AVAILABLE, OpenCodeClient.CapabilityState.AVAILABLE,
                "Router deliberately uses marker compatibility"));
        ProjectRow project = project("async-router");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput("# Python 转换工具\n\n新增可复用 Python 脚本并保留明确输入输出。");

        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "新增 Python 转换脚本");

        assertThat(created.workflowPhase()).isEqualTo("ROUTING");
        assertThat(taskProfiles.current(created.id())).satisfies(profile -> {
            assertThat(profile.state()).isEqualTo("ROUTING");
            assertThat(profile.decisionState()).isEqualTo("ROUTING");
            assertThat(profile.confirmationReady()).isFalse();
            assertThat(profile.confidenceAvailable()).isFalse();
            assertThat(profile.evidence()).isEmpty();
        });
        assertThat(mapper.findLatestTaskProfileRouterRun(created.id())).hasValueSatisfying(run -> {
            assertThat(run.state()).isEqualTo("RUNNING");
            assertThat(run.externalSessionId()).isNotBlank();
            assertThat(run.responseMode()).isEqualTo("TEXT_MARKER");
            assertThat(fake().promptRequestForSession(run.externalSessionId()).responseFormat())
                    .isInstanceOf(OpenCodeClient.ResponseFormat.Text.class);
            assertThat(fake().profileForSession(run.externalSessionId()))
                    .isEqualTo(OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS);
            assertThat(fake().promptRequestForSession(run.externalSessionId()).text())
                    .contains("TASK_PROFILE_ROUTER_V2", "fast single-shot task classifier",
                            "Do not use tools", "Return immediately after choosing three labels")
                    .doesNotContain("MCP tools may be used", "Server-observed repository facts",
                            "\"technologies\"", "\"confidence\"", "\"signals\"");
        });
        assertThatThrownBy(() -> taskProfiles.freeze(created.id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("仍在识别");

        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.get(created.id())).satisfies(waiting -> {
            assertThat(waiting.state()).isEqualTo("PENDING_HANDOFF");
            assertThat(waiting.workflowPhase()).isEqualTo("ROUTING");
        });
        confirmInitialTaskProfile(created.id());
        completeMandatoryDesignerQuestion(created.id());
        designerSessions.pollActiveHandoffs();

        assertThat(designerSessions.get(created.id()).state()).isEqualTo("REVIEWING");
        assertThatThrownBy(() -> taskProfiles.freeze(created.id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("仍在识别");
        designerSessions.pollActiveHandoffs();

        assertThat(taskProfiles.current(created.id())).satisfies(profile -> {
            assertThat(profile.state()).isEqualTo("PROVISIONAL");
            assertThat(profile.rolePackId()).isEqualTo("software-python");
            assertThat(profile.confidenceAvailable()).isTrue();
        });
        assertThat(mapper.listDesignerTaskProfiles(created.id())).hasSize(2)
                .extracting(io.opencode.loopper.persistence.DesignerTaskProfileRow::state)
                .containsExactly("PROVISIONAL", "SUPERSEDED");
    }

    @Test
    void confirmedProfileIsCarriedAcrossTheCompleteRequirementRerouteWithoutASecondHiddenDecision() throws Exception {
        ProjectRow project = project("confirmed-profile-reroute");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput("# Java 缓存刷新\n\n修改源代码并使用 Maven 聚焦测试验证。");
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(),
                "修改 Java 代码实现缓存刷新，并使用 Maven 测试验证");
        designerSessions.pollActiveHandoffs();
        TaskProfileService.View initial = taskProfiles.current(created.id());

        mvc.perform(post("/api/designer-sessions/{id}/task-profile/confirm", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("expectedVersion", initial.version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionState").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmationReady").value(true))
                .andExpect(jsonPath("$.resolutionSource").value("USER_CONFIRMED"));
        TaskProfileService.View confirmedProfile = taskProfiles.current(created.id());
        var confirmedRun = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow();
        assertThat(taskProfiles.routerRun(created.id()).retryAvailable()).isFalse();
        mvc.perform(post("/api/designer-sessions/{id}/task-profile/reroute", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "expectedRunId", confirmedRun.id(),
                                "expectedProfileVersion", confirmedProfile.version()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TASK_PROFILE_ROUTER_DECISION_RESOLVED"));

        completeMandatoryDesignerQuestion(created.id());
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow reviewing = designerSessions.get(created.id());
        assertThat(reviewing.state()).isEqualTo("REVIEWING");
        assertThat(taskProfiles.current(created.id())).satisfies(profile -> {
            assertThat(profile.decisionState()).isEqualTo("ROUTING");
            assertThat(profile.confirmationReady()).isFalse();
            assertThat(profile.previousConfirmedChoice()).isNotNull();
        });
        assertThatThrownBy(() -> designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision()))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TASK_PROFILE_ROUTING_IN_PROGRESS"));

        designerSessions.pollActiveHandoffs();

        assertThat(taskProfiles.current(created.id())).satisfies(profile -> {
            assertThat(profile.decisionState()).isEqualTo("CONFIRMED");
            assertThat(profile.confirmationReady()).isTrue();
            assertThat(profile.resolutionSource()).isEqualTo("USER_CONFIRMED_CARRIED_FORWARD");
            assertThat(profile.rolePackId()).isEqualTo("software-java");
            assertThat(profile.testPolicy().name()).isEqualTo("REQUIRED");
        });
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        assertThat(designerSessions.get(created.id()).workflowPhase()).isEqualTo("DESIGNING");
    }

    @Test
    void manifestChangePreventsCarryingAnUnfrozenConfirmedProfileAcrossReroute() throws Exception {
        ProjectRow project = project("profile-manifest-reroute");
        Path root = Path.of(project.rootPath());
        Files.writeString(root.resolve("pom.xml"), "<project />");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(),
                "修改 Java 服务并新增单元测试");
        designerSessions.pollActiveHandoffs();
        TaskProfileService.View initial = taskProfiles.current(created.id());
        taskProfiles.confirmRecommendation(created.id(), initial.version());

        Files.writeString(root.resolve("pom.xml"), "<project><version>2</version></project>");
        taskProfiles.reroute(created.id(), "修改 Java 服务并新增单元测试");
        designerSessions.pollActiveHandoffs();

        assertThat(taskProfiles.current(created.id())).satisfies(changed -> {
            assertThat(changed.decisionState()).isEqualTo("NEEDS_CONFIRMATION");
            assertThat(changed.confirmationReady()).isFalse();
            assertThat(changed.stackFingerprint()).isNotEqualTo(initial.stackFingerprint());
            assertThat(changed.previousConfirmedChoice()).isNotNull();
        });
    }

    @Test
    void ambiguousMultiStackSelectionIsExplicitAndFrozenAgainstLaterProjectRefreshes() throws Exception {
        ProjectRow project = project("component-profile-freeze");
        Path root = Path.of(project.rootPath());
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Path frontend = Files.createDirectory(root.resolve("frontend"));
        Files.writeString(frontend.resolve("package.json"), "{\"scripts\":{\"test\":\"vitest\"}}");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));

        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "优化公共业务流程");
        designerSessions.pollActiveHandoffs();
        TaskProfileService.View ambiguous = taskProfiles.current(created.id());

        assertThat(ambiguous.decisionState()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(ambiguous.componentSelectionRequired()).isTrue();
        assertThat(ambiguous.componentKeys()).isEmpty();
        assertThat(ambiguous.candidateComponents()).hasSize(2);
        String javaComponent = ambiguous.candidateComponents().stream()
                .filter(component -> component.technologies().contains("java"))
                .map(ProjectStackSnapshot.Component::key).findFirst().orElseThrow();

        TaskProfileService.View selected = taskProfiles.override(created.id(), ambiguous.intent(),
                ambiguous.artifactKinds().getFirst(), false, List.of(javaComponent), ambiguous.version());
        assertThat(selected.technologies()).containsExactly("java");
        assertThat(selected.rolePackId()).isEqualTo("software-java");
        assertThat(selected.componentKeys()).containsExactly(javaComponent);
        TaskProfileService.View frozen = taskProfiles.freeze(created.id());

        Files.writeString(frontend.resolve("package.json"), "{\"scripts\":{\"test\":\"vitest run\"}}");
        ProjectStackSnapshot refreshed = stackProfiles.forceRefresh(project.id());

        assertThat(refreshed.id()).isNotEqualTo(frozen.projectStackProfileId());
        assertThat(taskProfiles.current(created.id())).satisfies(stillFrozen -> {
            assertThat(stillFrozen.state()).isEqualTo("FROZEN");
            assertThat(stillFrozen.projectStackProfileId()).isEqualTo(frozen.projectStackProfileId());
            assertThat(stillFrozen.stackFingerprint()).isEqualTo(frozen.stackFingerprint());
            assertThat(stillFrozen.componentKeys()).containsExactly(javaComponent);
        });
    }

    @Test
    void changedRerouteBlocksDesignAndExposesThePreviousConfirmedChoice() throws Exception {
        ProjectRow project = project("changed-profile-reroute");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "修改 Java 源代码并测试");
        designerSessions.pollActiveHandoffs();
        TaskProfileService.View initial = taskProfiles.current(created.id());
        taskProfiles.confirmRecommendation(created.id(), initial.version());

        taskProfiles.reroute(created.id(), "编写一份 Markdown 操作手册，仅生成文档制品");
        assertThat(taskProfiles.current(created.id()).decisionState()).isEqualTo("ROUTING");
        designerSessions.pollActiveHandoffs();

        TaskProfileService.View changed = taskProfiles.current(created.id());
        assertThat(changed.decisionState()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(changed.confirmationReady()).isFalse();
        assertThat(changed.intent().name()).isEqualTo("DOCUMENT_AUTHORING");
        assertThat(changed.previousConfirmedChoice()).isNotNull();
        assertThat(changed.previousConfirmedChoice().intent().name()).isEqualTo("SOFTWARE_CHANGE");

        mvc.perform(post("/api/designer-sessions/{id}/task-profile/confirm", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("expectedVersion", changed.version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationReady").value(true))
                .andExpect(jsonPath("$.resolutionSource").value("USER_CONFIRMED"));
    }

    @Test
    void rerouteNeverStartsAReplacementWhenThePreviousRouterAbortIsUnconfirmed() throws Exception {
        ProjectRow project = project("router-replacement-abort");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "修改 Java 缓存刷新逻辑");
        var original = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow();
        int sessionsBefore = fake().createReadOnlySessionCalls();
        fake().failNextAborts(1);

        assertThatThrownBy(() -> taskProfiles.reroute(created.id(), "修改 Java 缓存刷新逻辑并补充测试"))
                .isInstanceOf(SessionFailure.class)
                .hasMessageContaining("abort transport failure");

        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(sessionsBefore);
        assertThat(mapper.findLatestTaskProfileRouterRun(created.id())).hasValueSatisfying(current -> {
            assertThat(current.id()).isEqualTo(original.id());
            assertThat(current.state()).isEqualTo("RUNNING");
        });

        taskProfiles.reroute(created.id(), "修改 Java 缓存刷新逻辑并补充测试");

        assertThat(fake().abortedSessionIds()).contains(original.externalSessionId());
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(sessionsBefore + 1);
        assertThat(mapper.findLatestTaskProfileRouterRun(created.id())).hasValueSatisfying(current -> {
            assertThat(current.id()).isNotEqualTo(original.id());
            assertThat(current.state()).isEqualTo("RUNNING");
        });
    }

    @Test
    void profileWorkflowSwitchStopsTheOldDesignerBeforePersistingAndDispatchingAReplacement() throws Exception {
        ProjectRow project = project("profile-workflow-replacement");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "修改 Java 缓存刷新逻辑");
        designerSessions.pollActiveHandoffs();
        confirmInitialTaskProfile(created.id());
        DesignerSessionRow discussing = designerSessions.get(created.id());
        TaskProfileService.View before = taskProfiles.current(created.id());
        String oldRemote = discussing.externalSessionId();
        fake().setSessionState(oldRemote, "RUNNING");
        int sessionsBefore = fake().createReadOnlySessionCalls();
        fake().failNextAborts(1);

        assertThatThrownBy(() -> designerSessions.updateTaskProfile(created.id(), before.intent(),
                before.artifactKinds().getFirst(), true, before.version()))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, failure ->
                        assertThat(failure.code()).isEqualTo("DESIGNER_SESSION_REPLACEMENT_ABORT_FAILED"));

        assertThat(taskProfiles.current(created.id()).workflowTemplate())
                .isEqualTo(before.workflowTemplate());
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(sessionsBefore);
        assertThat(designerSessions.get(created.id()).externalSessionId()).isEqualTo(oldRemote);

        TaskProfileService.View updated = designerSessions.updateTaskProfile(created.id(), before.intent(),
                before.artifactKinds().getFirst(), true, before.version());

        assertThat(updated.workflowTemplate().name()).isEqualTo("FULL_PACKAGE_DESIGN");
        assertThat(fake().abortedSessionIds()).contains(oldRemote);
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(sessionsBefore + 1);
        assertThat(designerSessions.get(created.id()).externalSessionId()).isNotEqualTo(oldRemote);
    }

    @Test
    void taskProfilePreviewIsReadOnlyAndAnExactConfirmedSelectionIsANoOp() throws Exception {
        ProjectRow project = project("profile-update-preview");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "修改 Java 缓存刷新逻辑");
        designerSessions.pollActiveHandoffs();
        confirmInitialTaskProfile(created.id());
        DesignerSessionRow discussing = designerSessions.get(created.id());
        TaskProfileService.View before = taskProfiles.current(created.id());
        int abortsBefore = fake().abortedSessionIds().size();
        int sessionsBefore = fake().createReadOnlySessionCalls();

        mvc.perform(post("/api/designer-sessions/{id}/task-profile/preview", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "intent", before.intent().name(),
                                "primaryArtifactKind", before.artifactKinds().getFirst().name(),
                                "largeTaskMode", before.largeTaskMode(),
                                "expectedVersion", before.version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionChanged").value(false))
                .andExpect(jsonPath("$.updateRequired").value(false))
                .andExpect(jsonPath("$.sessionRestartRequired").value(false))
                .andExpect(jsonPath("$.targetWorkflowTemplate").value(before.workflowTemplate().name()));

        TaskProfileService.View unchanged = designerSessions.updateTaskProfile(created.id(), before.intent(),
                before.artifactKinds().getFirst(), before.largeTaskMode(), before.version());

        assertThat(unchanged.version()).isEqualTo(before.version());
        assertThat(unchanged.resolutionSource()).isEqualTo(before.resolutionSource());
        assertThat(fake().abortedSessionIds()).hasSize(abortsBefore);
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(sessionsBefore);
        assertThat(designerSessions.get(created.id()).externalSessionId()).isEqualTo(discussing.externalSessionId());

        TaskProfileService.OverridePreview replacement = taskProfiles.previewOverride(
                created.id(), before.intent(), before.artifactKinds().getFirst(), true, before.version());
        assertThat(replacement.selectionChanged()).isTrue();
        assertThat(replacement.updateRequired()).isTrue();
        assertThat(replacement.sessionRestartRequired()).isTrue();
        assertThat(replacement.targetWorkflowTemplate().name()).isEqualTo("FULL_PACKAGE_DESIGN");
    }

    @Test
    void staleProfileWorkflowSwitchDoesNotStopTheCurrentDesigner() throws Exception {
        ProjectRow project = project("stale-profile-workflow-replacement");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "修改 Java 缓存刷新逻辑");
        designerSessions.pollActiveHandoffs();
        confirmInitialTaskProfile(created.id());
        DesignerSessionRow discussing = designerSessions.get(created.id());
        TaskProfileService.View before = taskProfiles.current(created.id());
        String oldRemote = discussing.externalSessionId();
        int sessionsBefore = fake().createReadOnlySessionCalls();
        int abortsBefore = fake().abortedSessionIds().size();

        assertThatThrownBy(() -> designerSessions.updateTaskProfile(created.id(), before.intent(),
                before.artifactKinds().getFirst(), true, before.version() + 1))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("TASK_PROFILE_VERSION_CONFLICT"));

        assertThat(fake().abortedSessionIds()).hasSize(abortsBefore);
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(sessionsBefore);
        assertThat(designerSessions.get(created.id()).externalSessionId()).isEqualTo(oldRemote);
        assertThat(taskProfiles.current(created.id()).workflowTemplate()).isEqualTo(before.workflowTemplate());
    }

    @Test
    void localStopAbortsEveryActiveDesignerRoleAndRetriesBeforeArchiving() throws Exception {
        ProjectRow project = project("designer-stop-all-roles");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "修改 Java 缓存刷新逻辑");
        designerSessions.pollActiveHandoffs();
        confirmInitialTaskProfile(created.id());
        DesignerSessionRow discussing = designerSessions.get(created.id());
        String designerRemote = discussing.externalSessionId();
        taskProfiles.reroute(created.id(), "修改 Java 缓存刷新逻辑并补充测试");
        String routerRemote = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow().externalSessionId();
        fake().failNextAborts(1);

        mvc.perform(post("/api/designer-sessions/{id}/stop", created.id())
                        .header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopStatus").value("STOPPING"))
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.stoppedSessions").value(1))
                .andExpect(jsonPath("$.failedSessions").value(1))
                .andExpect(jsonPath("$.pendingFinalizations").value(0));
        assertThat(designerSessions.get(created.id()).state()).isEqualTo("STOPPING");

        designerSessions.pollActiveHandoffs();
        taskProfiles.pollActive();
        assertThat(designerSessions.get(created.id()).state()).isEqualTo("STOPPING");

        mvc.perform(post("/api/designer-sessions/{id}/stop", created.id())
                        .header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.failedSessions").value(0))
                .andExpect(jsonPath("$.pendingFinalizations").value(0));
        assertThat(fake().abortedSessionIds()).contains(designerRemote, routerRemote);
        assertThat(mapper.listActiveTaskProfileRouterRuns()).noneMatch(row -> created.id().equals(row.designerSessionId()));

        mvc.perform(post("/api/designer-sessions/{id}/stop", created.id())
                        .header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.stoppedSessions").value(0));
    }

    @Test
    void routerFailureFallsBackToAnOverridableProfileWithoutTerminatingDesigner() throws Exception {
        ProjectRow project = project("router-fallback");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().failNextReadOnlySessions("ROUTER", 1);

        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "新增一个边界尚不明确的工具");
        designerSessions.pollActiveHandoffs();

        assertThat(taskProfiles.current(created.id())).satisfies(profile -> {
            assertThat(profile.state()).isEqualTo("PROVISIONAL");
            assertThat(profile.resolutionSource()).isEqualTo("ROUTER_FALLBACK");
            assertThat(profile.confidenceAvailable()).isFalse();
            assertThat(profile.decisionRequired()).isTrue();
            assertThat(profile.evidence()).anyMatch(value -> value.startsWith("router-error=ROUTER_SESSION_FAILED"));
        });
        assertThat(designerSessions.get(created.id())).satisfies(session -> {
            assertThat(session.state()).isEqualTo("PENDING_HANDOFF");
            assertThat(session.workflowPhase()).isEqualTo("ROUTING");
        });
        assertThat(mapper.listTasks()).isEmpty();
        assertThat(fake().createSessionCalls()).isZero();
    }

    @Test
    void manualOverrideAtTheInitialRoutingGateContinuesExactlyOnceWithoutAFalseRestartWarning() throws Exception {
        ProjectRow project = project("router-initial-manual-override");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "编写 Markdown 使用说明");
        designerSessions.pollActiveHandoffs();
        TaskProfileService.View recommendation = taskProfiles.current(created.id());

        mvc.perform(post("/api/designer-sessions/{id}/task-profile/preview", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "intent", "SOFTWARE_CHANGE", "primaryArtifactKind", "SOURCE_CODE",
                                "largeTaskMode", false, "expectedVersion", recommendation.version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateRequired").value(true))
                .andExpect(jsonPath("$.sessionRestartRequired").value(false));

        mvc.perform(put("/api/designer-sessions/{id}/task-profile", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "intent", "SOFTWARE_CHANGE", "primaryArtifactKind", "SOURCE_CODE",
                                "largeTaskMode", false, "expectedVersion", recommendation.version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionSource").value("USER_OVERRIDE"))
                .andExpect(jsonPath("$.confirmationReady").value(true));

        assertThat(designerSessions.get(created.id()).workflowPhase()).isEqualTo("DISCUSSING_REQUIREMENT");
        assertThat(fake().createSessionCalls()).isZero();
        mvc.perform(put("/api/designer-sessions/{id}/task-profile", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "intent", "SOFTWARE_CHANGE", "primaryArtifactKind", "SOURCE_CODE",
                                "largeTaskMode", false, "expectedVersion", recommendation.version()))))
                .andExpect(status().isConflict());
        assertThat(fake().createSessionCalls()).isZero();
    }

    @Test
    void connectedRouterHasNoWallClockTimeoutAndRerouteUsesOnlyThePersistedRequirementSnapshot() throws Exception {
        ProjectRow project = project("router-timeout-reroute");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String requirement = "修改多模块 Maven 项目的 Java 异常构造器";
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), requirement);
        var running = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow();
        assertThat(jdbc.update("UPDATE task_profile_router_run SET created_at=? WHERE id=?",
                Instant.now().minusSeconds(241).toString(), running.id())).isEqualTo(1);
        running = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow();
        fake().setSessionStatus(running.externalSessionId(), "RUNNING", "正在分析 Maven 模块");

        designerSessions.pollActiveHandoffs();

        var stillRunning = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow();
        assertThat(stillRunning.state()).isEqualTo("RUNNING");
        assertThat(stillRunning.errorCode()).isNull();
        assertThat(fake().abortedSessionIds()).doesNotContain(running.externalSessionId());
        assertThat(taskProfiles.routerRun(created.id()).deadlineAt()).isNull();

        fake().setSessionStatus(running.externalSessionId(), "FAILED", "Provider connection closed");
        designerSessions.pollActiveHandoffs();

        var failed = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow();
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("ROUTER_SESSION_FAILED");
        assertThat(taskProfiles.current(created.id())).satisfies(profile -> {
            assertThat(profile.decisionState()).isEqualTo("NEEDS_CONFIRMATION");
            assertThat(profile.confirmationReady()).isFalse();
            assertThat(profile.resolutionSource()).isEqualTo("ROUTER_FALLBACK");
            assertThat(profile.confidenceAvailable()).isFalse();
        });

        TaskProfileService.View fallback = taskProfiles.current(created.id());
        mvc.perform(post("/api/designer-sessions/{id}/task-profile/reroute", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "expectedRunId", failed.id(),
                                "expectedProfileVersion", fallback.version()))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.deadlineAt").doesNotExist());
        var replacement = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow();
        assertThat(replacement.id()).isNotEqualTo(failed.id());
        assertThat(replacement.requirementSnapshot()).isEqualTo(requirement);

        mvc.perform(post("/api/designer-sessions/{id}/task-profile/reroute", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "expectedRunId", failed.id(),
                                "expectedProfileVersion", fallback.version()))))
                .andExpect(status().isConflict());
    }

    @Test
    void userCanCancelAnActiveRouterAndContinueOnlyThroughManualSelection() throws Exception {
        ProjectRow project = project("router-user-cancel");
        Files.writeString(Path.of(project.rootPath()).resolve("pom.xml"), "<project />\n");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(),
                "修改多模块 Maven 项目的 Java 异常构造器");
        var running = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow();
        String externalSessionId = running.externalSessionId();

        mvc.perform(post("/api/designer-sessions/{id}/task-profile/cancel", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("expectedRunId", running.id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionState").value("NEEDS_CONFIRMATION"))
                .andExpect(jsonPath("$.confirmationReady").value(false))
                .andExpect(jsonPath("$.resolutionSource").value("USER_SELECTION_PENDING"));

        var cancelled = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow();
        assertThat(cancelled.state()).isEqualTo("SUPERSEDED");
        assertThat(cancelled.externalSessionState()).isEqualTo("ABORTED");
        assertThat(cancelled.errorCode()).isEqualTo("ROUTER_USER_CANCELLED");
        assertThat(fake().abortedSessionIds()).contains(externalSessionId);
        assertThat(mapper.listActiveTaskProfileRouterRuns()).noneMatch(run -> created.id().equals(run.designerSessionId()));
        assertThat(taskProfiles.routerRun(created.id()).retryAvailable()).isFalse();
        assertThat(designerSessions.get(created.id()).workflowPhase()).isEqualTo("ROUTING");
        TaskProfileService.View manual = taskProfiles.current(created.id());
        assertThat(manual.evidence()).anyMatch(value -> value.startsWith("router-error=ROUTER_USER_CANCELLED:"));

        mvc.perform(post("/api/designer-sessions/{id}/task-profile/cancel", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("expectedRunId", running.id()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TASK_PROFILE_ROUTER_NOT_ACTIVE"));

        mvc.perform(put("/api/designer-sessions/{id}/task-profile", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "intent", "SOFTWARE_CHANGE", "primaryArtifactKind", "SOURCE_CODE",
                                "largeTaskMode", false, "expectedVersion", manual.version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionSource").value("USER_OVERRIDE"))
                .andExpect(jsonPath("$.confirmationReady").value(true));
        assertThat(designerSessions.get(created.id()).workflowPhase()).isEqualTo("DISCUSSING_REQUIREMENT");
    }

    @Test
    void unconnectedRouterRunStillStopsAtTheConnectionDeadline() throws Exception {
        ProjectRow project = project("router-connection-timeout");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "修改 Java 源代码");
        var started = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow();
        openCode.abort(new OpenCodeClient.OpenCodeSession(started.externalSessionId(), Path.of(project.rootPath())));
        assertThat(jdbc.update("""
                UPDATE task_profile_router_run
                   SET state='PENDING', external_session_id=NULL, external_session_state=NULL,
                       response_mode=NULL, created_at=?, updated_at=?
                 WHERE id=?
                """, Instant.now().minusSeconds(241).toString(), Instant.now().toString(), started.id())).isEqualTo(1);

        designerSessions.pollActiveHandoffs();

        var timedOut = mapper.findLatestTaskProfileRouterRun(created.id()).orElseThrow();
        assertThat(timedOut.state()).isEqualTo("FAILED");
        assertThat(timedOut.errorCode()).isEqualTo("ROUTER_TIMEOUT");
        assertThat(timedOut.errorDetail()).contains("未能连接").doesNotContain("240");
        assertThat(taskProfiles.current(created.id())).satisfies(profile -> {
            assertThat(profile.resolutionSource()).isEqualTo("ROUTER_FALLBACK");
            assertThat(profile.confidenceAvailable()).isFalse();
        });
    }

    @Test
    void directDesignUsesIndependentReadOnlyRolesAndCreatesNoTaskBeforeConfirmation() throws Exception {
        ProjectRow project = project("direct");
        Files.writeString(Path.of(project.rootPath()).resolve("pom.xml"), "<project />\n");
        LoopSpec sixStageSpec = legacySpecWithStages(project.id(), 6);
        LoopDraftRow draft = drafts.create(sixStageSpec);
        fake().setDesignerOutput(designerOutput("# 单包设计\n\n缓存刷新后用户能看到新值。", sixStageSpec));
        fake().setJudgeOutput("DESIGNER", "");
        String largePackageDesignBody = "# 单包设计\n\n"
                + "缓存刷新后用户能看到新值，并保持并发读写与失败恢复边界。".repeat(700);
        assertThat(largePackageDesignBody.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(24 * 1024);
        String largePackageDesign = stageControlledDesign(largePackageDesignBody, 6);
        setPackageDesignerOutput("WP-1", largePackageDesign);

        DesignerSessionRow reviewing = prepareReviewingSession(project.id(), draft.id(), "实现缓存刷新并保留验收证据");
        TaskProfileService.View directProfile = taskProfiles.current(reviewing.id());
        assertThat(directProfile.workflowTemplate().name()).isEqualTo("DIRECT_SOFTWARE_DESIGN");
        assertThat(directProfile.projectStackProfileId()).isNotBlank();
        assertThat(directProfile.componentKeys()).hasSize(1);
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        DesignerSessionRow session = designerSessions.get(reviewing.id());
        assertThat(session.workflowPhase()).isEqualTo("DESIGNING");
        assertThat(designerSessions.pendingQuestions(session.id())).isEmpty();
        assertThat(fake().profileForSession(session.externalSessionId()))
                .isEqualTo(OpenCodeClient.SessionProfile.GENERAL_READ_ONLY);
        assertThat(mapper.listTasks()).isEmpty();
        var markerDecomposition = mapper.findTaskDecompositionByRevision(
                mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().id()).orElseThrow();
        assertThat(markerDecomposition.externalSessionId()).isNull();
        assertThat(markerDecomposition.serverCompiled()).isTrue();
        pollUntilSettled(session.id());

        DesignerSessionRow completed = designerSessions.get(session.id());
        assertThat(completed.state()).as("session=%s packages=%s messages=%s", completed,
                designerSessions.workPackageStatuses(session.id()), designerSessions.messages(session.id()))
                .isEqualTo("REVIEWING");
        assertThat(completed.workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(2);
        assertThat(designerSessions.requirementSnapshot(session.id())).satisfies(snapshot -> {
            assertThat(snapshot.source()).isEqualTo("SERVER_ASSEMBLED");
            assertThat(snapshot.markdown()).contains("实现缓存刷新并保留验收证据", "采用推荐项 (Recommended)")
                    .doesNotContain("缓存刷新后用户能看到新值");
        });
        assertThat(designerSessions.messages(session.id()))
                .anyMatch(message -> "SERVER_REQUIREMENT_SNAPSHOT".equals(message.deliveryState())
                        && message.content().equals(designerSessions.requirementSnapshot(session.id()).markdown()));
        DesignerMessageRow snapshotSource = designerSessions.messages(session.id()).stream()
                .filter(message -> "SERVER_REQUIREMENT_SNAPSHOT".equals(message.deliveryState()))
                .findFirst().orElseThrow();
        DesignRequirementRevisionRow frozenRequirement = mapper.findCurrentDesignRequirementRevision(session.id())
                .orElseThrow();
        assertThat(frozenRequirement.sourceMessageId()).isEqualTo(snapshotSource.id());
        assertThat(frozenRequirement.requirementText()).isEqualTo(snapshotSource.content());
        mvc.perform(get("/api/designer-sessions/{id}", session.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementSnapshot.discussionRevision").value(1))
                .andExpect(jsonPath("$.requirementSnapshot.source").value("SERVER_ASSEMBLED"))
                .andExpect(jsonPath("$.requirementSnapshot.markdown").value(snapshotSource.content()));
        assertThat(designerSessions.decompositionStatus(session.id()).resultType()).isEqualTo("DIRECT_DESIGN");
        assertThat(designerSessions.workPackageStatuses(session.id())).singleElement().satisfies(workPackage -> {
            assertThat(workPackage.id()).isEqualTo("WP-1");
            assertThat(workPackage.state()).isEqualTo("APPROVED");
            assertThat(workPackage.rolePackId()).isEqualTo("software-java");
            assertThat(workPackage.rolePackVersion()).isEqualTo("2026-08-dynamic-v7");
            assertThat(workPackage.executionStrategy()).isEqualTo("OPEN_CODE_IMPLEMENTATION");
            assertThat(workPackage.testPolicy()).isEqualTo("REQUIRED");
        });
        WorkPackageRoleProfileRow packageProfile = mapper.findWorkPackageRoleProfile(
                mapper.findLatestDesignWorkPackage(session.id(), "WP-1").orElseThrow().id()).orElseThrow();
        assertThat(packageProfile.projectStackProfileId()).isEqualTo(directProfile.projectStackProfileId());
        assertThat(json.readValue(packageProfile.componentKeysJson(), List.class))
                .containsExactlyElementsOf(directProfile.componentKeys());
        assertThat(packageProfile.stackFingerprint()).isEqualTo(directProfile.stackFingerprint());
        assertThat(designerSessions.messages(session.id()).stream().map(message -> message.actor()).toList())
                .contains("DESIGNER", "COMPILER", "VALIDATOR")
                .doesNotContain("DECOMPOSER")
                .doesNotContain("{\"status\":\"COMPILED\"");
        assertThat(designerSessions.messages(session.id()))
                .anyMatch(message -> "WP-1".equals(message.workPackageId())
                        && largePackageDesign.strip().equals(message.content()));
        assertThat(mapper.listTasks()).isEmpty();
        assertThat(drafts.spec(drafts.get(draft.id())).stages()).hasSize(6)
                .allMatch(stage -> "WP-1".equals(stage.workPackageId()));

        TaskRow task = drafts.confirm(draft.id(), "缓存刷新");
        assertThat(task.state()).isEqualTo("PENDING_START");
        assertThat(mapper.findTaskQueue(task.id())).isEmpty();
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
        assertThat(mapper.listSessions(task.id())).isEmpty();
        assertThat(task.worktreePath()).isNullOrEmpty();
        assertThat(mapper.listTasks()).singleElement().extracting(TaskRow::id).isEqualTo(task.id());
        assertThat(mapper.listStages(task.id())).hasSize(6).allSatisfy(stage -> {
            assertThat(stage.rolePackId()).isEqualTo("software-java");
            assertThat(stage.rolePackVersion()).isEqualTo("2026-08-dynamic-v7");
            assertThat(stage.testPolicy()).isEqualTo("REQUIRED");
            assertThat(stage.technologiesJson()).isEqualTo("[\"java\"]");
            assertThat(stage.projectStackProfileId()).isEqualTo(directProfile.projectStackProfileId());
            assertThat(stage.componentKeysJson()).isEqualTo(packageProfile.componentKeysJson());
            assertThat(stage.stackFingerprint()).isEqualTo(directProfile.stackFingerprint());
        });
        assertThat(tasks.artifacts(task.id()).stream().map(TaskArtifactRow::kind).toList())
                .contains("REQUIREMENT_CONTEXT", "DECOMPOSITION_CONTEXT", "WORK_PACKAGE_DESIGN",
                        "WORK_PACKAGE_COMPILATION_SUMMARY", "DESIGN_CONTEXT");
    }

    @Test
    void directSoftwareRoleSurvivesMaintenanceVocabularyAndRepairsBeforeCompilerSelection() throws Exception {
        ProjectRow project = project("direct-role-invariant");
        Files.writeString(Path.of(project.rootPath()).resolve("pom.xml"), "<project/>\n");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String design = """
                ## 目标与范围
                为责任链框架补充纯 JUnit 5 单元测试，不引入 mock 框架依赖，生产配置保持不变。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 测试代码 | src/test/java/example/ChainContextTest.java | 上下文聚焦单元测试 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 中断状态 | 新上下文 | 调用 interrupt | 状态与原因可查询 | 生产代码不变 |

                ## 验收约束
                `mvn -q -Dtest=ChainContextTest test` 必须独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 上下文测试 | 新增并验证责任链单元测试 | 中断状态；src/test/java/example/ChainContextTest.java | 无 |
                """;
        fake().setDesignerOutput(designerOutput(design, legacySpec(project.id())));
        setPackageDesignerOutput("WP-1", design);

        DesignerSessionRow reviewing = prepareReviewingSession(project.id(), draft.id(),
                "实现责任链功能并新增单元测试，测试不依赖外部服务");
        assertThat(taskProfiles.current(reviewing.id())).satisfies(profile -> {
            assertThat(profile.workflowTemplate().name()).isEqualTo("DIRECT_SOFTWARE_DESIGN");
            assertThat(profile.rolePackId()).isEqualTo("software-java");
        });
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        DesignWorkPackageRow workPackage = mapper.findLatestDesignWorkPackage(reviewing.id(), "WP-1").orElseThrow();
        WorkPackageRoleProfileRow assigned = mapper.findWorkPackageRoleProfile(workPackage.id()).orElseThrow();
        assertThat(assigned.rolePackId()).isEqualTo("software-java");
        assertThat(assigned.testPolicy()).isEqualTo("REQUIRED");

        assertThat(mapper.assignWorkPackageRoleProfile(new WorkPackageRoleProfileRow(
                assigned.id(), assigned.designerSessionId(), assigned.packageId(), assigned.taskProfileId(),
                "local-maintenance", assigned.rolePackVersion(), assigned.executionStrategy(), "OPTIONAL", "[]")))
                .isEqualTo(1);
        designerSessions.pollActiveHandoffs();

        var compilation = mapper.findLatestLoopSpecCompilationForPackage(reviewing.id(), "WP-1").orElseThrow();
        if ("JSON_SCHEMA".equals(compilation.planningResponseMode()))
            assertThat(compilation.planningResponseSchemaId()).isEqualTo("PACKAGE_ACCEPTANCE_DISAMBIGUATION_V6");
        assertThat(mapper.findDesignAcceptancePlanning(compilation.id())).isPresent();
        assertThat(compilation.externalSessionId()).isNull();
        assertThat(mapper.findDesignAcceptancePlanning(compilation.id()).orElseThrow().bindingSource())
                .isEqualTo("SERVER_STAGE_HINTS");
        assertThat(mapper.findWorkPackageRoleProfile(workPackage.id())).hasValueSatisfying(repaired -> {
            assertThat(repaired.rolePackId()).isEqualTo("software-java");
            assertThat(repaired.testPolicy()).isEqualTo("REQUIRED");
            assertThat(repaired.technologiesJson()).contains("java");
        });
    }

    @Test
    void directSoftwareOverflowStopsWithoutRedesignAndExplicitlyReopensAsLargeTask() throws Exception {
        ProjectRow project = project("direct-overflow");
        Files.writeString(Path.of(project.rootPath()).resolve("pom.xml"), "<project/>");
        LoopSpec sevenStageSpec = legacySpecWithStages(project.id(), 7);
        LoopDraftRow draft = drafts.create(sevenStageSpec);
        fake().setDesignerOutput(designerOutput("# 超限单包设计\n\n需求包含多个必须独立推进的业务阶段。",
                sevenStageSpec));
        setPackageDesignerOutput("WP-1", stageControlledDesign("# 超限单包设计", 7));

        DesignerSessionRow reviewing = prepareReviewingSession(project.id(), draft.id(), "实现跨域大型软件能力");
        TaskProfileService.View profile = taskProfiles.current(reviewing.id());
        if (!profile.confirmationReady()) {
            taskProfiles.confirmRecommendation(reviewing.id(), profile.version());
        }
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        pollUntilSettled(reviewing.id());

        DesignerSessionRow blocked = designerSessions.get(reviewing.id());
        assertThat(blocked.state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.workPackageStatuses(reviewing.id())).singleElement().satisfies(workPackage -> {
            assertThat(workPackage.id()).isEqualTo("WP-1");
            assertThat(workPackage.lastErrorCode()).isEqualTo("LARGE_TASK_MODE_REQUIRED");
            assertThat(workPackage.redesignCount()).isZero();
        });
        assertThat(designerSessions.compilerStatus(reviewing.id()).lastErrorCode())
                .isEqualTo("LARGE_TASK_MODE_REQUIRED");

        mvc.perform(post("/api/designer-sessions/{id}/large-task-mode/enable", reviewing.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "expectedDiscussionRevision", blocked.discussionRevision(),
                                "expectedProfileVersion", taskProfiles.current(reviewing.id()).version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowTemplate").value("FULL_PACKAGE_DESIGN"))
                .andExpect(jsonPath("$.largeTaskMode").value(true));
        assertThat(designerSessions.get(reviewing.id()).workflowPhase()).isEqualTo("DISCUSSING_REQUIREMENT");
        assertThat(designerSessions.get(reviewing.id()).state()).isEqualTo("RUNNING");
        assertThat(mapper.findCurrentDesignRequirementRevision(reviewing.id())).isEmpty();
    }

    @Test
    void directRequirementSnapshotRejectsUtf8ContentOverTwentyFourKibWithoutTruncation() throws Exception {
        ProjectRow project = project("direct-snapshot-limit");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "需".repeat(8_500));
        designerSessions.pollActiveHandoffs();
        confirmInitialTaskProfile(session.id());
        completeMandatoryDesignerQuestion(session.id());
        designerSessions.pollActiveHandoffs();

        DesignerSessionRow blocked = designerSessions.get(session.id());
        assertThat(blocked.state()).isEqualTo("WAITING_INPUT");
        assertThat(mapper.findLatestDesignDiscussionRevision(session.id(), "REQUIREMENT").orElseThrow())
                .satisfies(discussion -> {
                    assertThat(discussion.lastErrorCode()).isEqualTo("REQUIREMENT_SNAPSHOT_TOO_LARGE");
                    assertThat(discussion.snapshotMarkdown()).isBlank();
                });
        assertThat(designerSessions.messages(session.id())).noneMatch(message ->
                "SERVER_REQUIREMENT_SNAPSHOT".equals(message.deliveryState()));
        assertThat(mapper.findCurrentDesignRequirementRevision(session.id())).isEmpty();
    }

    @Test
    void readOnlyReviewRunsIndependentReviewerAndCreatesNoWritableRuntime() throws Exception {
        ProjectRow project = project("independent-reviewer");
        Files.writeString(Path.of(project.rootPath()).resolve("README.md"), "# Baseline\n\nreview target\n");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput("# 只读评审范围\n\n检查 README.md 并输出带文件行号的报告。");
        DesignerSessionRow session = prepareReviewingSession(project.id(), draft.id(), "只读评审当前项目并给出证据报告");

        TaskProfileService.View profile = taskProfiles.freeze(session.id());
        designerSessions.beginReadOnlyReport(session.id());
        AnalysisReportService.View started = reports.startReviewer(session.id());
        reports.pollActive();
        AnalysisReportService.View ready = reports.get(session.id(), started.id());

        assertThat(ready.state()).isEqualTo("READY");
        assertThat(ready.reviewerContractVersion()).isEqualTo("REVIEWER_REPORT_V1");
        assertThat(ready.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.path()).isEqualTo("README.md");
            assertThat(finding.line()).isEqualTo(1);
        });
        assertThat(ready.evidence()).isNotEmpty().allMatch(item -> !item.stale());
        assertThat(mapper.listTasks()).isEmpty();
        assertThat(mapper.findWorkspaceLease(Path.of(project.rootPath()).toRealPath().toString())).isEmpty();
        var row = mapper.listAnalysisReports(session.id()).getFirst();
        assertThat(fake().profileForSession(row.externalSessionId()))
                .isEqualTo(OpenCodeClient.SessionProfile.REVIEWER_READ_ONLY);
        assertThat(profile.executionStrategy().name()).isEqualTo("READ_ONLY_REPORT");

        mvc.perform(get("/api/designer-sessions/{id}/reports/{reportId}", session.id(), ready.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewerContractVersion").value("REVIEWER_REPORT_V1"))
                .andExpect(jsonPath("$.findings[0].path").value("README.md"));
        MvcResult converted = mvc.perform(post("/api/designer-sessions/{id}/reports/{reportId}/convert-to-design",
                        session.id(), ready.id()).header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        String convertedId = json.readTree(converted.getResponse().getContentAsString()).get("id").asText();
        assertThat(convertedId).isNotEqualTo(session.id());
        assertThat(designerSessions.get(convertedId)).satisfies(created -> {
            assertThat(created.loopDraftId()).isNotBlank();
            assertThat(created.workflowPhase()).isEqualTo("ROUTING");
        });
        assertThat(mapper.listTasks()).isEmpty();
        assertThat(mapper.findWorkspaceLease(Path.of(project.rootPath()).toRealPath().toString())).isEmpty();
        assertThat(fake().createSessionCalls()).isZero();
    }

    @Test
    void mixedPackagesFreezeIndependentStackAndTestPolicies() throws Exception {
        ProjectRow project = project("mixed-role-packs");
        Files.writeString(Path.of(project.rootPath()).resolve("pom.xml"), "<project/>\n");
        LoopDraftRow draft = drafts.create(v2DocumentationSpec(project.id()));
        String split = decomposition("DECOMPOSED", "Java 服务与 Python 工具", 2)
                .replace("能力 1", "Java Spring 服务")
                .replace("能力 2", "Python 独立脚本")
                .replace("\"requirementRefs\":[\"RQ-1\"]", "\"requirementRefs\":[\"RQ-1\",\"RQ-2\"]");
        fake().setDecomposerOutput(split);
        fake().setDesignerOutput("# 混合技术栈设计\n\n先完成 Java 服务，再交付 Python 独立脚本。");

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(),
                "先修改 Java Spring 服务，再新增无测试体系的 Python 独立脚本");
        for (int attempt = 0; attempt < 12 && designerSessions.workPackageStatuses(session.id()).isEmpty(); attempt++) {
            designerSessions.pollActiveHandoffs();
        }

        assertThat(designerSessions.workPackageStatuses(session.id()))
                .as("session=%s decomposition=%s messages=%s", designerSessions.get(session.id()),
                        designerSessions.decompositionStatus(session.id()), designerSessions.messages(session.id()))
                .hasSize(2).satisfiesExactly(
                javaPackage -> {
                    assertThat(javaPackage.rolePackId()).isEqualTo("software-java");
                    assertThat(javaPackage.technologies()).containsExactly("java");
                    assertThat(javaPackage.testPolicy()).isEqualTo("REQUIRED");
                },
                pythonPackage -> {
                    assertThat(pythonPackage.rolePackId()).isEqualTo("software-python");
                    assertThat(pythonPackage.technologies()).containsExactly("python");
                    assertThat(pythonPackage.testPolicy()).isEqualTo("OPTIONAL");
                });
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void incompleteHistoricalWorkPackageRoleSnapshotDoesNotBreakProjectionAndRepairsOnUse() throws Exception {
        ProjectRow project = project("incomplete-work-package-role");
        Files.writeString(Path.of(project.rootPath()).resolve("pom.xml"), "<project/>\n");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerOutput(decomposition("DIRECT_DESIGN", "历史 Java 工作包", 1));
        fake().setDesignerOutput(designerOutput("# 历史工作包\n\n保持旧设计可恢复。", legacySpec(project.id())));
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "恢复历史 Java 软件工作包");
        for (int attempt = 0; attempt < 12 && designerSessions.workPackageStatuses(session.id()).isEmpty(); attempt++) {
            designerSessions.pollActiveHandoffs();
        }
        var workPackage = mapper.findLatestDesignWorkPackage(session.id(), "WP-1").orElseThrow();
        var assigned = mapper.findWorkPackageRoleProfile(workPackage.id()).orElseThrow();
        assertThat(mapper.assignWorkPackageRoleProfile(new io.opencode.loopper.persistence.WorkPackageRoleProfileRow(
                assigned.id(), assigned.designerSessionId(), assigned.packageId(), assigned.taskProfileId(),
                assigned.rolePackId(), assigned.rolePackVersion(), null, null, assigned.technologiesJson())))
                .isEqualTo(1);

        assertThat(designerSessions.workPackageStatuses(session.id())).singleElement().satisfies(status -> {
            assertThat(status.rolePackId()).isNull();
            assertThat(status.executionStrategy()).isNull();
            assertThat(status.testPolicy()).isNull();
        });

        assertThat(workPackageRoles.get(workPackage)).satisfies(repaired -> {
            assertThat(repaired.rolePackId()).isEqualTo("software-java");
            assertThat(repaired.executionStrategy().name()).isEqualTo("OPEN_CODE_IMPLEMENTATION");
            assertThat(repaired.testPolicy().name()).isEqualTo("REQUIRED");
        });
        assertThat(designerSessions.workPackageStatuses(session.id())).singleElement()
                .extracting(DesignerSessionService.WorkPackageStatus::executionStrategy)
                .isEqualTo("OPEN_CODE_IMPLEMENTATION");
    }

    @Test
    void largeDocumentAndSafeMaintenanceUseDedicatedImplicitPackageFlows() throws Exception {
        ProjectRow documentProject = project("packaged-document");
        LoopDraftRow documentDraft = drafts.create(v2DocumentationSpec(documentProject.id()));
        fake().setDesignerOutput("# 运维手册\n\n目标文件 `docs/operations.md`。\n\n## 安装\n\n安装说明。\n\n## 运维\n\n运维说明。");
        DesignerSessionRow documentSession = prepareReviewingSession(documentProject.id(), documentDraft.id(),
                "编写大型多章节 Markdown 文档 `docs/operations.md`");
        TaskProfileService.View documentProfile = taskProfiles.freeze(documentSession.id());
        directArtifacts.compilePackagedDocument(documentSession.id(), documentProfile);
        designerSessions.completeDirectArtifactDesign(documentSession.id());

        LoopSpec documentSpec = drafts.spec(drafts.get(documentDraft.id()));
        assertThat(documentProfile.workflowTemplate().name()).isEqualTo("PACKAGED_ARTIFACT");
        assertThat(documentSpec.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.stageKind().name()).isEqualTo("DOCUMENT_MATERIALIZATION");
            assertThat(stage.verifiers()).extracting(LoopSpec.VerifierSpec::type)
                    .containsExactly("DOCUMENT_STRUCTURE");
            try {
                var storedPlan = mapper.findArtifactPlan(stage.artifactPlanId()).orElseThrow();
                var plan = json.readValue(storedPlan.planJson(),
                        io.opencode.loopper.verification.ArtifactMaterializationService.DocumentPlan.class);
                assertThat(plan.chapters()).extracting(
                        io.opencode.loopper.verification.ArtifactMaterializationService.DocumentChapter::workPackageId)
                        .containsExactly("WP-1", "WP-2");
                assertThat(plan.chapters()).extracting(
                        io.opencode.loopper.verification.ArtifactMaterializationService.DocumentChapter::title)
                        .containsExactly("安装", "运维");
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });

        ProjectRow maintenanceProject = project("safe-maintenance");
        Files.writeString(Path.of(maintenanceProject.rootPath()).resolve("settings.yml"), "enabled: false\n");
        LoopDraftRow maintenanceDraft = drafts.create(v2DocumentationSpec(maintenanceProject.id()));
        fake().setDesignerOutput("# 安全维护\n\n只修改 `settings.yml`，把 enabled 调整为 true，不删除文件、不操作服务。");
        DesignerSessionRow maintenanceSession = prepareReviewingSession(maintenanceProject.id(), maintenanceDraft.id(),
                "本地配置维护：只修改 `settings.yml`");
        TaskProfileService.View maintenanceProfile = taskProfiles.freeze(maintenanceSession.id());
        directMaintenance.compile(maintenanceSession.id(), maintenanceProfile);
        designerSessions.completeDirectArtifactDesign(maintenanceSession.id());

        LoopSpec maintenanceSpec = drafts.spec(drafts.get(maintenanceDraft.id()));
        assertThat(maintenanceProfile.workflowTemplate().name()).isEqualTo("LOCAL_MAINTENANCE");
        assertThat(maintenanceSpec.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.stageKind().name()).isEqualTo("LOCAL_MAINTENANCE");
            assertThat(stage.verifiers()).singleElement().satisfies(verifier -> {
                assertThat(verifier.type()).isEqualTo("GIT_DIFF");
                assertThat(verifier.requireChanges()).isTrue();
                assertThat(verifier.forbidDeletes()).isTrue();
                assertThat(verifier.allowedPaths()).containsExactly("settings.yml");
            });
        });
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void autoModeRequiresLocalAuthorizationAndCompletesRecommendedDesignThroughTaskStart() throws Exception {
        ProjectRow project = project("designer-auto-mode");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 全自动设计\n\n按推荐边界形成可验收结果。",
                legacySpec(project.id())));

        mvc.perform(post("/api/designer-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("projectId", project.id(), "draftId", draft.id(),
                                "initialMessage", "全自动完成设计", "autoModeEnabled", true))))
                .andExpect(status().isBadRequest());

        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "全自动完成设计");
        fake().setPackageDesignerOutput("WP-1", controlledDesign("# 全自动工作包设计\n\n按推荐边界形成可验收结果。"));
        DesignerAutoModeService.View enabled = designerAutoMode.initialize(session.id(), true);
        assertThat(enabled.state()).isEqualTo("ACTIVE");

        for (int attempt = 0; attempt < 200 && mapper.findTaskByDraft(draft.id()).isEmpty(); attempt++) {
            DesignerSessionRow current = designerSessions.get(session.id());
            if ("RUNNING".equals(current.state())
                    && ("DISCUSSING_REQUIREMENT".equals(current.workflowPhase())
                    || "QUESTIONING_PACKAGE".equals(current.workflowPhase()))) {
                String questionId = "auto-question-" + current.discussionScope() + "-" + current.discussionRevision();
                fake().setPendingQuestion(current.externalSessionId(), new OpenCodeClient.PendingQuestion(
                        questionId, current.externalSessionId(), List.of(new OpenCodeClient.QuestionPrompt(
                        "采用哪个设计边界？", "设计边界", List.of(
                        new OpenCodeClient.QuestionOption("保守方案", "只保留基础行为"),
                        new OpenCodeClient.QuestionOption("推荐方案（推荐）", "保持最小且可验收的范围")),
                        false, false))));
                designerAutoMode.pollActive();
                if (designerSessions.pendingQuestions(session.id()).isEmpty()) {
                    fake().setSessionState(current.externalSessionId(), "COMPLETED");
                }
            }
            designerSessions.pollActiveHandoffs();
            designerAutoMode.pollActive();
        }

        TaskRow task = mapper.findTaskByDraft(draft.id()).orElseThrow();
        assertThat(task.state()).isNotEqualTo("PENDING_START");
        assertThat(designerAutoMode.get(session.id())).satisfies(mode -> {
            assertThat(mode.state()).as(mode.toString()).isEqualTo("COMPLETED");
            assertThat(mode.taskId()).isEqualTo(task.id());
        });
        assertThat(mapper.listDesignDiscussionRevisions(session.id()))
                .anyMatch(row -> row.decisionLogJson().contains("AUTO_RECOMMENDED")
                        && row.decisionLogJson().contains("推荐方案（推荐）"));
    }

    @Test
    void autoModeKeepsRequirementDesignerActiveDuringProviderRetryAndResumesSameSession() throws Exception {
        ProjectRow project = project("designer-auto-provider-retry");
        Files.writeString(Path.of(project.rootPath()).resolve("pom.xml"), "<project/>");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 过载恢复后的设计\n\n保持原会话并继续形成可验收结果。",
                legacySpec(project.id())));
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "验证全自动瞬态过载恢复");
        designerAutoMode.initialize(session.id(), true);
        designerSessions.pollActiveHandoffs();
        designerAutoMode.pollActive();

        DesignerSessionRow questioning = designerSessions.get(session.id());
        String remoteId = questioning.externalSessionId();
        String questionId = "auto-provider-retry-question";
        fake().setPendingQuestion(remoteId, new OpenCodeClient.PendingQuestion(
                questionId, remoteId, List.of(new OpenCodeClient.QuestionPrompt(
                "采用哪个恢复边界？", "恢复边界", List.of(
                new OpenCodeClient.QuestionOption("推荐方案（推荐）", "在同一会话等待 Provider 恢复"),
                new OpenCodeClient.QuestionOption("人工处理", "立即停止自动设计")),
                false, false))));

        designerAutoMode.pollActive();
        assertThat(fake().answersForQuestion(questionId)).containsExactly(List.of("推荐方案（推荐）"));
        fake().setSessionStatus(remoteId, "RETRY", "system cpu overloaded");

        designerSessions.pollActiveHandoffs();
        designerAutoMode.pollActive();

        assertThat(designerSessions.get(session.id())).satisfies(retrying -> {
            assertThat(retrying.state()).isEqualTo("RUNNING");
            assertThat(retrying.workflowPhase()).isEqualTo("DISCUSSING_REQUIREMENT");
            assertThat(retrying.externalSessionId()).isEqualTo(remoteId);
            assertThat(retrying.externalSessionState()).isEqualTo("RETRY");
        });
        assertThat(designerAutoMode.get(session.id())).satisfies(mode -> {
            assertThat(mode.state()).isEqualTo("ACTIVE");
            assertThat(mode.errorCode()).isNull();
            assertThat(mode.errorDetail()).isNull();
        });
        assertThat(designerSessions.messages(session.id()))
                .noneMatch(message -> message.content().contains("OPENCODE_DESIGNER_retry")
                        || message.content().contains("全自动模式已阻断"));

        fake().setSessionState(remoteId, "COMPLETED");
        for (int step = 0; step < 5; step++) {
            designerSessions.pollActiveHandoffs();
            designerAutoMode.pollActive();
            if (designerSessions.get(session.id()).currentRequirementRevision() != null) break;
        }

        DesignerSessionRow resumed = designerSessions.get(session.id());
        assertThat(resumed.currentRequirementRevision()).isNotNull();
        assertThat(resumed.workflowPhase()).isEqualTo("DESIGNING");
        assertThat(designerAutoMode.get(session.id()).state()).isEqualTo("ACTIVE");
        assertThat(mapper.listDesignDiscussionRevisions(session.id()))
                .anyMatch(row -> row.decisionLogJson().contains("AUTO_RECOMMENDED")
                        && row.decisionLogJson().contains("推荐方案（推荐）"));
    }

    @Test
    void autoModeAdoptsAmbiguousProfileRecommendationWithoutManualOverride() throws Exception {
        ProjectRow project = project("designer-auto-profile-decision");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 自动确认画像后的设计\n\n沿用已授权的全自动流程继续。",
                legacySpec(project.id())));
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "新增一个边界尚不明确的工具");
        designerAutoMode.initialize(session.id(), true);
        designerSessions.pollActiveHandoffs();
        TaskProfileService.View ambiguous = taskProfiles.current(session.id());
        assertThat(ambiguous.decisionRequired()).isTrue();

        designerAutoMode.pollActive();

        assertThat(designerAutoMode.get(session.id())).satisfies(mode -> {
            assertThat(mode.state()).isEqualTo("ACTIVE");
            assertThat(mode.lastAction()).isEqualTo("PROFILE_AUTO_CONFIRMED");
            assertThat(mode.errorCode()).isNull();
        });
        assertThat(taskProfiles.current(session.id())).satisfies(profile -> {
            assertThat(profile.decisionRequired()).isFalse();
            assertThat(profile.resolutionSource()).isEqualTo("AUTO_RECOMMENDED");
            assertThat(profile.evidence()).contains("auto-recommended-profile");
        });
        assertThat(mapper.findCurrentDesignRequirementRevision(session.id())).isEmpty();

        designerAutoMode.pollActive();

        assertThat(taskProfiles.current(session.id())).satisfies(profile -> {
            assertThat(profile.state()).isEqualTo("PROVISIONAL");
            assertThat(profile.workflowTemplate().name()).isEqualTo("DIRECT_SOFTWARE_DESIGN");
            assertThat(profile.largeTaskMode()).isFalse();
        });
        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("DISCUSSING_REQUIREMENT");
    }

    @Test
    void autoModeStopsAtAFailedRouterGateUntilTheFallbackIsExplicitlyConfirmed() throws Exception {
        ProjectRow project = project("designer-auto-router-failure");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().failNextReadOnlySessions("ROUTER", 1);
        fake().setDesignerOutput(designerOutput("# 人工确认降级设置后的设计\n\n继续形成可验收结果。",
                legacySpec(project.id())));
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "修改 Java 服务并补充测试");
        designerAutoMode.initialize(session.id(), true);

        designerSessions.pollActiveHandoffs();
        designerAutoMode.pollActive();

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("ROUTING");
        assertThat(designerAutoMode.get(session.id())).satisfies(mode -> {
            assertThat(mode.state()).isEqualTo("BLOCKED");
            assertThat(mode.errorCode()).isEqualTo("TASK_PROFILE_ROUTER_REVIEW_REQUIRED");
        });
        TaskProfileService.View fallback = taskProfiles.current(session.id());
        mvc.perform(post("/api/designer-sessions/{id}/task-profile/confirm", session.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("expectedVersion", fallback.version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionSource").value("USER_CONFIRMED"));

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("DISCUSSING_REQUIREMENT");
        assertThat(designerAutoMode.get(session.id())).satisfies(mode -> {
            assertThat(mode.state()).isEqualTo("ACTIVE");
            assertThat(mode.lastAction()).isEqualTo("PROFILE_DECISION_RESUMED");
        });
    }

    @Test
    void legacyProfileDecisionBlockResumesAfterManualConfirmation() throws Exception {
        ProjectRow project = project("designer-auto-profile-manual-recovery");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().failNextReadOnlySessions("ROUTER", 1);
        fake().setDesignerOutput(designerOutput("# 人工覆盖画像后的设计\n\n沿用已授权的全自动流程继续。",
                legacySpec(project.id())));
        DesignerSessionRow session = prepareReviewingSession(project.id(), draft.id(), "新增一个边界尚不明确的工具");
        TaskProfileService.View ambiguous = taskProfiles.current(session.id());
        designerAutoMode.initialize(session.id(), true);

        DesignerAutoModeRow active = mapper.findDesignerAutoMode(session.id()).orElseThrow();
        assertThat(mapper.updateDesignerAutoMode(new DesignerAutoModeRow(session.id(), "BLOCKED", "MODE_BLOCKED",
                "TASK_PROFILE_DECISION_REQUIRED", "任务类型存在歧义，请先确认任务画像", active.taskId(),
                active.authorizedAt(), active.disabledAt(), active.updatedAt(), active.version()))).isEqualTo(1);

        mvc.perform(put("/api/designer-sessions/{id}/task-profile", session.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "intent", "SOFTWARE_CHANGE",
                                "primaryArtifactKind", "SOURCE_CODE",
                                "expectedVersion", ambiguous.version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionRequired").value(false))
                .andExpect(jsonPath("$.resolutionSource").value("USER_CONFIRMED_CARRIED_FORWARD"))
                .andExpect(jsonPath("$.version").value(ambiguous.version()));

        assertThat(designerAutoMode.get(session.id())).satisfies(mode -> {
            assertThat(mode.state()).isEqualTo("ACTIVE");
            assertThat(mode.lastAction()).isEqualTo("PROFILE_DECISION_RESUMED");
            assertThat(mode.errorCode()).isNull();
            assertThat(mode.errorDetail()).isNull();
        });
        assertThat(designerSessions.messages(session.id()))
                .anyMatch(message -> message.content().contains("任务设置已可继续")
                        && "AUTO_MODE_PROFILE_RESUMED".equals(message.deliveryState()));

        designerAutoMode.pollActive();

        assertThat(taskProfiles.current(session.id()).state()).isEqualTo("FROZEN");
        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("DESIGNING");
    }

    @Test
    void softwareProfileLargeTaskSwitchIsExplicitOptimisticAndSoftwareOnly() throws Exception {
        ProjectRow project = project("designer-large-task-profile");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput("# Java 软件变更\n\n修改源代码实现缓存刷新并补充聚焦测试。");
        DesignerSessionRow session = prepareReviewingSession(project.id(), draft.id(),
                "修改 Java 源代码实现缓存刷新功能并补充测试");
        TaskProfileService.View initial = taskProfiles.current(session.id());
        String directRequirementSessionId = designerSessions.get(session.id()).externalSessionId();
        assertThat(initial.workflowTemplate().name()).isEqualTo("DIRECT_SOFTWARE_DESIGN");
        assertThat(initial.largeTaskMode()).isFalse();

        mvc.perform(put("/api/designer-sessions/{id}/task-profile", session.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "intent", "SOFTWARE_CHANGE", "primaryArtifactKind", "SOURCE_CODE",
                                "largeTaskMode", true, "expectedVersion", initial.version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowTemplate").value("FULL_PACKAGE_DESIGN"))
                .andExpect(jsonPath("$.largeTaskMode").value(true));
        DesignerSessionRow largePredesign = designerSessions.get(session.id());
        String largeRequirementSessionId = largePredesign.externalSessionId();
        assertThat(largePredesign.state()).isEqualTo("RUNNING");
        assertThat(largePredesign.workflowPhase()).isEqualTo("DISCUSSING_REQUIREMENT");
        assertThat(fake().abortedSessionIds()).contains(directRequirementSessionId);
        assertThatThrownBy(() -> designerSessions.confirmRequirement(session.id(), largePredesign.discussionRevision()))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        assertThat(error.code()).isEqualTo("REQUIREMENT_DISCUSSION_INCOMPLETE"));

        mvc.perform(put("/api/designer-sessions/{id}/task-profile", session.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "intent", "SOFTWARE_CHANGE", "primaryArtifactKind", "SOURCE_CODE",
                                "largeTaskMode", false, "expectedVersion", initial.version()))))
                .andExpect(status().isConflict());

        TaskProfileService.View large = taskProfiles.current(session.id());
        taskProfiles.reroute(session.id(), "即使 Router 认为复杂，也保留用户选择");
        designerSessions.pollActiveHandoffs();
        assertThat(taskProfiles.current(session.id()).largeTaskMode()).isTrue();

        TaskProfileService.View rerouted = taskProfiles.current(session.id());
        mvc.perform(put("/api/designer-sessions/{id}/task-profile", session.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "intent", "DOCUMENT_AUTHORING", "primaryArtifactKind", "MARKDOWN",
                                "largeTaskMode", true, "expectedVersion", rerouted.version()))))
                .andExpect(status().isBadRequest());

        assertThat(large.largeTaskMode()).isTrue();

        TaskProfileService.View latestLarge = taskProfiles.current(session.id());
        mvc.perform(put("/api/designer-sessions/{id}/task-profile", session.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "intent", "SOFTWARE_CHANGE", "primaryArtifactKind", "SOURCE_CODE",
                                "largeTaskMode", false, "expectedVersion", latestLarge.version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowTemplate").value("DIRECT_SOFTWARE_DESIGN"))
                .andExpect(jsonPath("$.largeTaskMode").value(false));
        assertThat(fake().abortedSessionIds()).contains(largeRequirementSessionId);
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("REVIEWING");
        assertThat(designerSessions.requirementSnapshot(session.id())).satisfies(snapshot -> {
            assertThat(snapshot.source()).isEqualTo("SERVER_ASSEMBLED");
            assertThat(snapshot.markdown()).contains("修改 Java 源代码实现缓存刷新功能并补充测试")
                    .doesNotContain("# Java 软件变更");
        });
    }

    @Test
    void autoModeDefaultsOffAndUsesOptimisticLockForDisableAndReauthorization() throws Exception {
        ProjectRow project = project("designer-auto-toggle");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), null);

        assertThat(designerAutoMode.initialize(session.id(), false)).satisfies(mode -> {
            assertThat(mode.enabled()).isFalse();
            assertThat(mode.state()).isEqualTo("DISABLED");
            assertThat(mode.version()).isZero();
        });
        assertThat(designerAutoMode.setEnabled(session.id(), true, 0)).satisfies(mode -> {
            assertThat(mode.enabled()).isTrue();
            assertThat(mode.state()).isEqualTo("ACTIVE");
            assertThat(mode.version()).isEqualTo(1);
        });
        assertThatThrownBy(() -> designerAutoMode.setEnabled(session.id(), false, 0))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        assertThat(error.code()).isEqualTo("DESIGNER_AUTO_MODE_VERSION_CONFLICT"));
        assertThat(designerAutoMode.setEnabled(session.id(), false, 1)).satisfies(mode -> {
            assertThat(mode.enabled()).isFalse();
            assertThat(mode.state()).isEqualTo("DISABLED");
            assertThat(mode.version()).isEqualTo(2);
        });
        assertThat(designerAutoMode.setEnabled(session.id(), true, 2)).satisfies(mode -> {
            assertThat(mode.enabled()).isTrue();
            assertThat(mode.state()).isEqualTo("ACTIVE");
            assertThat(mode.version()).isEqualTo(3);
        });
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
        setPackageDesignerOutput("WP-1", design);
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

        assertThat(designerSessions.get(session.id()).workflowPhase())
                .as("session=%s compiler=%s packages=%s messages=%s", designerSessions.get(session.id()),
                        designerSessions.compilerStatus(session.id()),
                        designerSessions.workPackageStatuses(session.id()), designerSessions.messages(session.id()))
                .isEqualTo("FINAL_REVIEW");
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
        setPackageDesignerOutput("WP-1", design);
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
        var firstRepair = mapper.findLatestLoopSpecCompilation(session.id()).orElseThrow();
        assertThat(fake().promptForSession(firstRepair.externalSessionId()))
                .contains("named `evidence`, not", "/stages/3/evidence/0/path",
                        "Every JAVA_PRODUCTION Stage", "FULL_TEST and BUILD never satisfy");

        String frozenSemantic = mapper.findLatestLoopSpecCompilation(session.id()).orElseThrow().semanticPlanJson();
        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"patches":[{"op":"replace","path":"/status","value":"COMPILED"}]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);
        designerSessions.pollActiveHandoffs();

        var rejectedPatch = mapper.findLatestLoopSpecCompilation(session.id()).orElseThrow();
        assertThat(rejectedPatch.semanticRepairCount()).isEqualTo(2);
        assertThat(rejectedPatch.semanticPlanJson()).isEqualTo(frozenSemantic);
        assertThat(fake().profileForSession(rejectedPatch.externalSessionId()))
                .isEqualTo(OpenCodeClient.SessionProfile.COMPILER_REPAIR_NO_TOOLS);
        OpenCodeClient.ResponseFormat repairFormat = fake()
                .promptRequestForSession(rejectedPatch.externalSessionId()).responseFormat();
        if (repairFormat instanceof OpenCodeClient.ResponseFormat.JsonSchema schema) {
            assertThat(schema.schemaId()).isEqualTo(OpenCodeStructuredSchemas.AI_SEMANTIC_PATCH_V1);
        } else {
            assertThat(repairFormat).isInstanceOf(OpenCodeClient.ResponseFormat.Text.class);
        }

        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"patches":[{"op":"add","path":"/stages/0/verifiers/-",
                 "value":{"kind":"SELF_CHECK","command":["python3","-c","print('ERROR_OK')"],
                 "successMarker":"ERROR_OK","covers":[1]}}]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.compilerStatus(session.id())).satisfies(status -> {
            assertThat(status.formatRepairCount()).isZero();
            assertThat(status.semanticRepairCount()).isEqualTo(2);
            assertThat(status.serverCompiled()).isTrue();
            assertThat(status.lastErrorCode()).isNull();
        });
        assertThat(drafts.spec(drafts.get(draft.id())).stages().getFirst().verifiers())
                .filteredOn(verifier -> "SELF_CHECK".equals(verifier.processPurpose()))
                .hasSize(2);
    }

    @Test
    void compilerSupplementalizesEngineeringMetadataWithoutSpendingSemanticRepair() throws Exception {
        ProjectRow project = project("compact-meta-normalization");
        LoopDraftRow draft = drafts.create(v2DocumentationSpec(project.id()));
        fake().setDecomposerPlanningOutput("""
                <!-- TASK_DECOMPOSITION_PLAN_JSON_START -->
                {"outcome":"READY","normalizedGoal":"事件总线行为可验证","globalConstraints":[],
                 "workPackages":[{"title":"事件总线","objective":"交付事件模型和同步总线",
                 "scopeIn":["event"],"scopeOut":[],"deliverables":["生产代码与聚焦测试"],
                 "acceptanceIntent":["发布、注册和异常行为可验证"],"dependsOn":[]}],
                 "coverage":[{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE","targetIndex":0}],
                 "designGaps":[],"reason":null}
                <!-- TASK_DECOMPOSITION_PLAN_JSON_END -->
                """);
        String design = """
                # WP-1 事件总线

                BaseEvent 对空事件编码抛 IllegalArgumentException。

                EventTypeCodes 为 final 类、私有构造并使用静态字符串常量。

                新增代码遵循 Java 8 语法（无 var、无钻石语法省略）和中文注释。

                EventRegistry 拒绝同一事件类型的重复监听器。

                EventListener 为 @FunctionalInterface 且声明唯一 onEvent 方法。

                EventBus 按注册顺序同步执行，异常原样上抛并中止后续监听器。

                阶段一装配形态为显式 registry.register，注解装配留给下游包。
                """;
        setPackageDesignerOutput("WP-1", design);
        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"outcome":"COMPILED","summary":"事件总线阶段","stages":[{"objective":"交付事件总线",
                 "implementationKind":"JAVA_PRODUCTION","allowedPaths":["src/main/java/**","src/test/java/**"],
                 "forbiddenPaths":[".env"],"deliverables":["事件模型、注册表、总线与聚焦测试"],
                 "criteria":[
                  {"description":"BaseEvent 对空事件编码抛 IllegalArgumentException","sourceRefs":["DS-L002"]},
                  {"description":"EventTypeCodes 为 final 类、私有构造并使用静态字符串常量","sourceRefs":["DS-L003"]},
                  {"description":"新增代码遵循 Java 8 语法、无 var 并使用中文注释","sourceRefs":["DS-L004"]},
                  {"description":"EventRegistry 拒绝同一事件类型的重复监听器","sourceRefs":["DS-L005"]},
                  {"description":"EventListener 为 @FunctionalInterface 且声明唯一 onEvent 方法","sourceRefs":["DS-L006"]},
                  {"description":"EventBus 按注册顺序同步执行，异常原样上抛并中止后续监听器","sourceRefs":["DS-L007"]},
                  {"description":"阶段一装配形态为显式 registry.register","sourceRefs":["DS-L008"]}],
                 "evidence":[
                  {"kind":"FOCUSED_TEST","command":["mvn","-Dtest=BaseEventTest","test"],"covers":[0]},
                  {"kind":"SELF_CHECK","command":["grep","-rn","var","src/main/java"],"successMarker":"no matches","covers":[2]},
                  {"kind":"FOCUSED_TEST","command":["mvn","-Dtest=EventRegistryTest","test"],"covers":[3]},
                  {"kind":"FOCUSED_TEST","command":["mvn","-Dtest=EventBusTest","test"],"covers":[5]},
                  {"kind":"GIT_DIFF","covers":[],"requireChanges":true,"forbidDeletes":true}],
                 "verificationRuntime":null}],"handoffSummary":"事件总线可供下游复用","designGaps":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "交付同步事件总线");
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.compilerStatus(session.id())).satisfies(status -> {
            assertThat(status.semanticRepairCount()).isZero();
            assertThat(status.serverCompiled()).isTrue();
        });
        assertThat(drafts.spec(drafts.get(draft.id())).stages().getFirst()).satisfies(stage -> {
            assertThat(stage.acceptanceCriteria()).extracting(LoopSpec.AcceptanceCriterion::description)
                    .containsExactly(
                            "BaseEvent 对空事件编码抛 IllegalArgumentException",
                            "EventRegistry 拒绝同一事件类型的重复监听器",
                            "EventBus 按注册顺序同步执行，异常原样上抛并中止后续监听器");
            assertThat(stage.verifiers()).noneMatch(verifier -> "SELF_CHECK".equals(verifier.processPurpose()));
            assertThat(stage.verifiers()).filteredOn(verifier -> "TEST".equals(verifier.processPurpose()))
                    .allSatisfy(verifier -> assertThat(verifier.criterionIds()).hasSize(1));
        });
        assertThat(designerSessions.messages(session.id())).extracting(DesignerMessageRow::content)
                .anyMatch(content -> content.contains("ENGINEERING_META_CRITERIA_SUPPLEMENTALIZED")
                        && content.contains("UNEXECUTABLE_META_SELF_CHECK_DROPPED"));
    }

    @Test
    void compilerDerivesUniqueFocusedTestCoverageForRemainingBusinessCriteria() throws Exception {
        ProjectRow project = project("compact-unique-test");
        LoopDraftRow draft = drafts.create(v2DocumentationSpec(project.id()));
        fake().setDecomposerPlanningOutput(directDecompositionPlan("事件总线", "交付事件总线行为"));
        String design = "# 事件行为\n\n未注册事件静默返回。\n\n监听器异常中止后续处理并原样上抛。";
        setPackageDesignerOutput("WP-1", design);
        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"outcome":"COMPILED","summary":"唯一聚焦测试","stages":[{"objective":"交付总线行为",
                 "implementationKind":"JAVA_PRODUCTION","allowedPaths":["src/main/java/**","src/test/java/**"],
                 "forbiddenPaths":[".env"],"deliverables":["总线与 EventBusTest"],
                 "criteria":[{"description":"未注册事件静默返回","sourceRefs":["DS-L002"]},
                 {"description":"监听器异常中止后续处理并原样上抛","sourceRefs":["DS-L003"]}],
                 "evidence":[{"kind":"FOCUSED_TEST","command":["mvn","-Dtest=EventBusTest","test"],"covers":[0]}],
                 "verificationRuntime":null}],"handoffSummary":"总线行为已冻结","designGaps":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "事件总线两项行为");
        pollUntilSettled(session.id());

        assertThat(designerSessions.compilerStatus(session.id()).semanticRepairCount()).isZero();
        assertThat(drafts.spec(drafts.get(draft.id())).stages().getFirst().verifiers().getFirst().criterionIds())
                .containsExactly("WP-1-AC-1", "WP-1-AC-2");
        assertThat(designerSessions.messages(session.id())).extracting(DesignerMessageRow::content)
                .anyMatch(content -> content.contains("UNIQUE_FOCUSED_TEST_COVERAGE_DERIVED"));
    }

    @Test
    void compilerReturnsAllSemanticProblemsWithExactJsonPathsInOneRepair() throws Exception {
        ProjectRow project = project("compact-batched-errors");
        LoopDraftRow draft = drafts.create(v2DocumentationSpec(project.id()));
        fake().setDecomposerPlanningOutput(directDecompositionPlan("文档能力", "交付两项文档行为"));
        String design = "# 两项行为\n\n事件说明必须可观察。\n\n异常说明必须可观察。";
        setPackageDesignerOutput("WP-1", design);
        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"outcome":"COMPILED","summary":"两项缺口","stages":[{"objective":"更新文档",
                 "implementationKind":"NON_JAVA","allowedPaths":["README.md"],"forbiddenPaths":[".env"],
                 "deliverables":["README"],"criteria":[
                  {"description":"事件说明必须可观察","sourceRefs":["DS-L002"]},
                  {"description":"异常说明必须可观察","sourceRefs":["DS-L003"],"judgeRubric":"检查说明"}],
                 "evidence":[],"verificationRuntime":null}],"handoffSummary":"待修复","designGaps":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "两项文档行为");
        pollUntilCompilerState(session.id(), "RUNNING", 1);

        assertThat(designerSessions.compilerStatus(session.id())).satisfies(status -> {
            assertThat(status.lastErrorCode()).isEqualTo("COMPILER_PLAN_SEMANTIC_INVALID");
            assertThat(status.lastErrorDetail()).contains(
                    "[COMPILER_PLAN_CRITERION_UNCOVERED] /stages/0/criteria/0",
                    "[COMPILER_PLAN_JUDGE_REASON_REQUIRED] /stages/0/criteria/1/judgeOnlyReason");
            assertThat(status.semanticRepairCount()).isEqualTo(1);
        });
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
        designerSessions.pollActiveHandoffs();
        confirmInitialTaskProfile(session.id());
        session = designerSessions.get(session.id());
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
    void missingQuestionCapabilityFallsBackToChatAndPersistsTheDirectAnswer() throws Exception {
        ProjectRow project = project("question-chat-fallback");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setToolCapability(new OpenCodeClient.ToolCapabilityProbe(
                OpenCodeClient.CapabilityState.AVAILABLE, List.of("read", "glob", "grep"), null));
        fake().setDesignerOutput("1. 缓存更新失败时如何处理？\n   - 保留旧值并报错（推荐）\n   - 清空缓存\n\n可直接回复选项或自己的方案。");

        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "实现可恢复的缓存刷新");
        designerSessions.pollActiveHandoffs();
        confirmInitialTaskProfile(session.id());
        session = designerSessions.get(session.id());
        assertThat(fake().profileForSession(session.externalSessionId()))
                .isEqualTo(OpenCodeClient.SessionProfile.GENERAL_READ_ONLY);
        assertThat(fake().promptForSession(session.externalSessionId()))
                .contains("does not expose the native question tool", "ordinary Markdown text")
                .doesNotContain("Call the question tool exactly once");

        designerSessions.pollActiveHandoffs();

        session = designerSessions.get(session.id());
        assertThat(session.state()).isEqualTo("RUNNING");
        assertThat(session.workflowPhase()).isEqualTo("DISCUSSING_REQUIREMENT");
        assertThat(session.externalSessionState()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.pendingQuestions(session.id())).isEmpty();
        assertThat(designerSessions.questionInteractionStatus(session.id()))
                .isEqualTo(new DesignerSessionService.QuestionInteractionStatus("CHAT_FALLBACK", true));
        assertThat(designerSessions.messages(session.id())).anyMatch(message ->
                "CHAT_QUESTION".equals(message.deliveryState()) && message.content().contains("缓存更新失败"));

        designerSessions.appendRequirementMessage(session.id(), "选择保留旧值并报错，同时记录失败原因。",
                session.discussionRevision());

        assertThat(designerSessions.get(session.id()).state()).isEqualTo("REVIEWING");
        assertThat(mapper.listDesignDiscussionRevisions(session.id())).singleElement().satisfies(revision -> {
            assertThat(revision.questionAnswered()).isTrue();
            assertThat(revision.decisionLogJson()).contains("CHAT_FALLBACK", "选择保留旧值并报错");
            assertThat(revision.snapshotMarkdown()).contains("实现可恢复的缓存刷新", "选择保留旧值并报错");
        });
        assertThat(mapper.findCurrentDesignRequirementRevision(session.id())).isEmpty();
        assertThat(mapper.listTasks()).isEmpty();

        mvc.perform(get("/api/designer-sessions/{sessionId}", session.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionInteraction.mode").value("NONE"))
                .andExpect(jsonPath("$.questionInteraction.awaitingAnswer").value(false));
    }

    @Test
    void availableQuestionCapabilityKeepsTheNativeInteractiveProfile() throws Exception {
        ProjectRow project = project("question-native-tool");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setToolCapability(new OpenCodeClient.ToolCapabilityProbe(
                OpenCodeClient.CapabilityState.AVAILABLE,
                List.of("read", "glob", "grep", "question"), null));

        DesignerSessionRow created = designerSessions.create(project.id(), draft.id(), "实现可恢复的缓存刷新");
        designerSessions.pollActiveHandoffs();
        confirmInitialTaskProfile(created.id());
        DesignerSessionRow running = designerSessions.get(created.id());

        assertThat(fake().profileForSession(running.externalSessionId()))
                .isEqualTo(OpenCodeClient.SessionProfile.DESIGNER_INTERACTIVE_READ_ONLY);
        assertThat(fake().promptForSession(running.externalSessionId()))
                .contains("Call the question tool exactly once")
                .doesNotContain("does not expose the native question tool");
        assertThat(designerSessions.questionInteractionStatus(running.id()))
                .isEqualTo(new DesignerSessionService.QuestionInteractionStatus("NATIVE_TOOL", false));

        String questionId = "native-question";
        fake().setPendingQuestion(running.externalSessionId(), new OpenCodeClient.PendingQuestion(
                questionId, running.externalSessionId(), List.of(new OpenCodeClient.QuestionPrompt(
                "采用推荐边界吗？", "设计边界", List.of(new OpenCodeClient.QuestionOption(
                "采用推荐项 (Recommended)", "保持最小范围")), false, true))));
        assertThat(designerSessions.pendingQuestions(running.id()))
                .extracting(DesignerSessionService.PendingQuestion::id).containsExactly(questionId);
    }

    @Test
    void largeTaskPackageAlsoUsesChatFallbackAndContinuesAfterTheDirectAnswer() throws Exception {
        ProjectRow project = project("package-question-chat-fallback");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 大型缓存改造\n\n拆分可恢复刷新与状态展示。",
                legacySpec(project.id())));
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "大型缓存改造需要拆包设计");
        fake().setToolCapability(new OpenCodeClient.ToolCapabilityProbe(
                OpenCodeClient.CapabilityState.AVAILABLE, List.of("read", "glob", "grep"), null));
        setPackageDesignerOutput("WP-1", "1. 失败状态由谁观察？\n   - 管理页面展示（推荐）\n   - 仅写日志");

        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();

        session = designerSessions.get(session.id());
        DesignWorkPackageRow workPackage = mapper.listDesignWorkPackages(
                mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().id()).getFirst();
        assertThat(session.workflowPhase()).isEqualTo("QUESTIONING_PACKAGE");
        assertThat(workPackage.state()).isEqualTo("QUESTIONING");
        assertThat(designerSessions.questionInteractionStatus(session.id()))
                .isEqualTo(new DesignerSessionService.QuestionInteractionStatus("CHAT_FALLBACK", true));
        assertThat(fake().profileForSession(workPackage.designerExternalSessionId()))
                .isEqualTo(OpenCodeClient.SessionProfile.GENERAL_READ_ONLY);

        setPackageDesignerOutput("WP-1", "# 完整工作包设计\n\n展示失败状态并保留旧缓存值。");
        designerSessions.appendPackageMessage(session.id(), "WP-1", "由管理页面展示，同时保留旧值。",
                session.discussionRevision(), workPackage.designRevision());

        DesignWorkPackageRow designing = mapper.listDesignWorkPackages(workPackage.requirementRevisionId()).getFirst();
        assertThat(designing.state()).isEqualTo("DESIGNING");
        assertThat(fake().promptForSession(designing.designerExternalSessionId()))
                .contains("Do not ask another question", "COMPLETE-DESIGN CONTRACT")
                .doesNotContain("CHAT QUESTION COMPATIBILITY CONTRACT");
        assertThat(designerSessions.questionInteractionStatus(session.id()))
                .isEqualTo(new DesignerSessionService.QuestionInteractionStatus("CHAT_FALLBACK", false));
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
            setPackageDesignerOutput(packageId, design);
            fake().setPackageCompilerOutput(packageId, packageCompilation(packageId, design));
        }
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "验证工作包传递失效边界");
        pollUntilSettled(session.id());
        assertThat(designerSessions.workPackageStatuses(session.id())).allMatch(item -> "APPROVED".equals(item.state()));
        assertThat(mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().state())
                .isEqualTo("COMPLETED");

        var first = designerSessions.workPackageStatuses(session.id()).getFirst();
        List<String> invalidated = designerSessions.reopenPackage(session.id(), "WP-1",
                designerSessions.get(session.id()).discussionRevision(), first.approvedDesignRevision());

        assertThat(invalidated).containsExactly("WP-2", "WP-3");
        assertThat(designerSessions.workPackageStatuses(session.id())).extracting(item -> item.state())
                .containsExactly("REVIEWING", "STALE", "STALE", "APPROVED");
        assertThat(mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().state())
                .isEqualTo("ACTIVE");

        designerSessions.retryPackageCompilation(session.id(), "WP-1");
        pollUntilPackageReview(session.id());
        assertThat(designerSessions.workPackageStatuses(session.id()).getFirst().state()).isEqualTo("REVIEWING");
    }

    @Test
    void externalDraftEditAfterFinalAggregationStillBlocksPackageReopen() throws Exception {
        ProjectRow project = project("package-reopen-draft-conflict");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerOutput(decomposition("DECOMPOSED", "两个工作包", 2));
        for (int ordinal = 1; ordinal <= 2; ordinal++) {
            String packageId = "WP-" + ordinal;
            String design = "# " + packageId + " 设计\n\n提供独立可观察结果。";
            setPackageDesignerOutput(packageId, design);
            fake().setPackageCompilerOutput(packageId, packageCompilation(packageId, design));
        }
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "验证聚合后的外部草稿修改仍被阻断");
        pollUntilSettled(session.id());
        LoopDraftRow aggregated = drafts.get(draft.id());
        LoopSpec spec = drafts.spec(aggregated);
        drafts.update(draft.id(), new LoopSpec(spec.schemaVersion(), spec.projectId(), spec.goal(),
                spec.context() + "\n外部修改", spec.stages(), spec.limits(), spec.model(), spec.sessionPolicy(),
                spec.nextAttemptPromptTemplate(), spec.budget()));

        var first = designerSessions.workPackageStatuses(session.id()).getFirst();
        assertThatThrownBy(() -> designerSessions.reopenPackage(session.id(), "WP-1",
                designerSessions.get(session.id()).discussionRevision(), first.approvedDesignRevision()))
                .isInstanceOfSatisfying(ConflictException.class,
                        error -> assertThat(error.code()).isEqualTo("DESIGNER_DRAFT_CHANGED"));
        assertThat(designerSessions.requirementStatus(session.id()).state()).isEqualTo("COMPLETED");
        assertThat(designerSessions.workPackageStatuses(session.id())).allMatch(item -> "APPROVED".equals(item.state()));
    }

    @Test
    void structuredDesignUsesRoleProfilesAndFallsBackOnceInAFreshSession() throws Exception {
        fake().setStructuredCapability(new OpenCodeClient.StructuredOutputCapability(
                OpenCodeClient.CapabilityState.AVAILABLE, OpenCodeClient.CapabilityState.AVAILABLE, null));
        ProjectRow project = project("structured-fallback");
        LoopSpec structuredSpec = v2DocumentationSpec(project.id());
        LoopDraftRow draft = drafts.create(structuredSpec);
        fake().setDesignerOutput(designerOutput(
                "# 结构化设计\n\nREADME 文档设计可执行验证。", structuredSpec));

        DesignerSessionRow reviewing = prepareLargeReviewingSession(project.id(), draft.id(), "结构化输出失败时安全回退");
        fake().failNextStructuredPrompts(1);
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        DesignerSessionRow session = designerSessions.get(reviewing.id());
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
        assertThat(fake().modelForSession(decomposition.externalSessionId()).thinking()).isNull();

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
    void workPackageDesignerRetryStatusRemainsTransientAndResumesSameSession() throws Exception {
        ProjectRow project = project("package-designer-retry-status");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String design = controlledDesign("# 工作包瞬态恢复\n\nProvider 恢复后继续生成同一份完整设计。");
        fake().setDesignerOutput(designerOutput(
                "# 工作包瞬态恢复\n\nProvider 恢复后继续生成同一份完整设计。",
                legacySpec(project.id())));
        setPackageDesignerOutput("WP-1", design);
        fake().setPackageCompilerPlanningOutput("WP-1", acceptanceDisambiguation());
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "验证工作包 Designer 瞬态重试");

        for (int attempt = 0; attempt < 20
                && !"QUESTIONING_PACKAGE".equals(designerSessions.get(session.id()).workflowPhase()); attempt++) {
            designerSessions.pollActiveHandoffs();
        }
        DesignerSessionRow questioning = designerSessions.get(session.id());
        assertThat(questioning.workflowPhase()).isEqualTo("QUESTIONING_PACKAGE");
        String remoteId = questioning.externalSessionId();
        String questionId = "package-provider-retry-question";
        fake().setPendingQuestion(remoteId, new OpenCodeClient.PendingQuestion(
                questionId, remoteId, List.of(new OpenCodeClient.QuestionPrompt(
                "是否保持当前工作包边界？", "工作包边界", List.of(
                new OpenCodeClient.QuestionOption("保持（推荐）", "沿同一 Session 继续")), false, false))));
        designerSessions.replyQuestion(session.id(), questionId, List.of(List.of("保持（推荐）")));
        fake().setSessionStatus(remoteId, "RETRY", "system cpu overloaded");

        designerSessions.pollActiveHandoffs();

        DesignerSessionRow retrying = designerSessions.get(session.id());
        assertThat(retrying.state()).isEqualTo("RUNNING");
        assertThat(retrying.workflowPhase()).isEqualTo("DESIGNING");
        assertThat(retrying.externalSessionId()).isEqualTo(remoteId);
        assertThat(retrying.externalSessionState()).isEqualTo("RETRY");
        var requirement = mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow();
        assertThat(mapper.listDesignWorkPackages(requirement.id())).singleElement().satisfies(workPackage -> {
            assertThat(workPackage.state()).isEqualTo("DESIGNING");
            assertThat(workPackage.designerExternalSessionId()).isEqualTo(remoteId);
            assertThat(workPackage.designerExternalSessionState()).isEqualTo("RETRY");
            assertThat(workPackage.designerTransportRetryCount()).isZero();
            assertThat(workPackage.lastErrorCode()).isNull();
        });

        fake().setSessionState(remoteId, "COMPLETED");
        designerSessions.pollActiveHandoffs();

        assertThat(designerSessions.get(session.id())).satisfies(resumed -> {
            assertThat(resumed.state()).isNotIn("WAITING_INPUT", "SESSION_ERROR");
            assertThat(resumed.workflowPhase()).isIn("COMPILING", "VALIDATING", "REVIEWING_PACKAGE");
        });
        assertThat(mapper.listDesignWorkPackages(requirement.id())).singleElement().satisfies(workPackage -> {
            assertThat(workPackage.state()).isIn("COMPILING", "VALIDATING", "REVIEWING");
            assertThat(workPackage.designerExternalSessionId()).isEqualTo(remoteId);
            assertThat(workPackage.designRevision()).isEqualTo(1);
        });
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
        LoopSpec initial = legacySpec(project.id());
        LoopSpec.StageSpec placeholder = initial.stages().getFirst();
        LoopDraftRow draft = drafts.create(new LoopSpec(initial.schemaVersion(), initial.projectId(), initial.goal(),
                initial.context(), List.of(new LoopSpec.StageSpec(placeholder.objective(), placeholder.allowedPaths(),
                placeholder.forbiddenPaths(), placeholder.deliverables(), placeholder.verifiers(),
                placeholder.acceptanceCriteria(), placeholder.verificationRuntime(), placeholder.implementationKind(),
                "WP-1")), initial.limits(), initial.model(), initial.sessionPolicy(),
                initial.nextAttemptPromptTemplate(), initial.budget()));
        fake().setDecomposerOutput(decomposition("DECOMPOSED", "交付三段纵向能力", 3));
        for (int ordinal = 1; ordinal <= 3; ordinal++) {
            String packageId = "WP-" + ordinal;
            String design = "# " + packageId + " 设计\n\n" + packageId + " 完成后产生可观察结果。";
            setPackageDesignerOutput(packageId, design);
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
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(11);
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
            setPackageDesignerOutput(packageId, design);
            fake().setPackageCompilerOutput(packageId, packageCompilation(packageId, design));
        }

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "一个需要六个纵向工作包的大型任务");
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.workPackageStatuses(session.id())).hasSize(6)
                .allMatch(workPackage -> "APPROVED".equals(workPackage.state()));
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(15);
        // The initial task-setting gate switches to large-task mode before any requirement Designer is created.
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(17);
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
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(4);
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
            setPackageDesignerOutput(packageId, design);
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
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(4);
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
        designerSessions.pollActiveHandoffs();
        confirmInitialTaskProfile(session.id());
        completeMandatoryDesignerQuestion(session.id());
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow firstReview = designerSessions.get(session.id());
        assertThat(firstReview.workflowPhase()).isEqualTo("DISCUSSING_REQUIREMENT");
        assertThat(mapper.findCurrentDesignRequirementRevision(session.id())).isEmpty();
        assertThat(mapper.findLatestTaskDecomposition(session.id())).isEmpty();
        assertThat(designerSessions.answeredQuestions(session.id())).singleElement().satisfies(answered -> {
            assertThat(answered.scope()).isEqualTo("REQUIREMENT");
            assertThat(answered.designMessageId()).isNotBlank();
            assertThat(answered.questions()).singleElement().satisfies(question -> {
                assertThat(question.question()).isEqualTo("采用推荐设计边界吗？");
                assertThat(question.options()).singleElement().satisfies(option -> {
                    assertThat(option.label()).isEqualTo("采用推荐项 (Recommended)");
                    assertThat(option.description()).isEqualTo("保持最小且可验收的范围");
                });
                assertThat(question.answers()).containsExactly("采用推荐项 (Recommended)");
            });
        });
        mvc.perform(get("/api/designer-sessions/{id}", session.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingQuestions").isEmpty())
                .andExpect(jsonPath("$.answeredQuestions[0].scope").value("REQUIREMENT"))
                .andExpect(jsonPath("$.answeredQuestions[0].designMessageId").isNotEmpty())
                .andExpect(jsonPath("$.answeredQuestions[0].questions[0].question")
                        .value("采用推荐设计边界吗？"))
                .andExpect(jsonPath("$.answeredQuestions[0].questions[0].options[0].label")
                        .value("采用推荐项 (Recommended)"))
                .andExpect(jsonPath("$.answeredQuestions[0].questions[0].answers[0]")
                        .value("采用推荐项 (Recommended)"));
        DesignDiscussionRevisionRow firstDecision = mapper.listDesignDiscussionRevisions(session.id()).getFirst();
        String legacyDecision = """
                [{"questionId":"legacy-question","questions":["历史问题仍然可见吗？"],
                  "answers":[["可以"]],"answeredAt":"2026-08-18T04:00:00Z"}]
                """;
        assertThat(mapper.updateDesignDiscussionRevision(new DesignDiscussionRevisionRow(
                firstDecision.id(), firstDecision.designerSessionId(), firstDecision.requirementRevision(),
                firstDecision.scopeKey(), firstDecision.workPackageId(), firstDecision.revision(),
                firstDecision.state(), firstDecision.sourceMessageId(), firstDecision.designMessageId(),
                firstDecision.snapshotMarkdown(), legacyDecision, firstDecision.questionRequired(),
                firstDecision.questionAnswered(), firstDecision.questionRetryCount(),
                firstDecision.candidateCompilationId(), firstDecision.lastErrorCode(),
                firstDecision.lastErrorDetail(), firstDecision.createdAt(), firstDecision.updatedAt(),
                firstDecision.version()))).isEqualTo(1);
        assertThat(designerSessions.answeredQuestions(session.id())).singleElement().satisfies(answered ->
                assertThat(answered.questions()).singleElement().satisfies(question -> {
                    assertThat(question.question()).isEqualTo("历史问题仍然可见吗？");
                    assertThat(question.options()).isEmpty();
                    assertThat(question.answers()).containsExactly("可以");
                }));

        designerSessions.appendRequirementMessage(session.id(),
                "补充：失败时返回明确错误，且保持同一发布边界", firstReview.discussionRevision());
        completeMandatoryDesignerQuestion(session.id());
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow secondReview = designerSessions.get(session.id());
        assertThat(secondReview.discussionRevision()).isEqualTo(2);
        assertThat(mapper.listDesignDiscussionRevisions(session.id())).hasSize(2)
                .allMatch(row -> !row.snapshotMarkdown().isBlank());
        assertThat(mapper.findLatestTaskDecomposition(session.id())).isEmpty();
        DesignerSessionService.RequirementSnapshot snapshot = designerSessions.requirementSnapshot(session.id());
        assertThat(snapshot.source()).isEqualTo("SERVER_ASSEMBLED");
        assertThat(snapshot.markdown()).contains(
                        "后续输入和回答优先于冲突的旧内容",
                        "先实现查询能力",
                        "历史问题仍然可见吗？",
                        "回答：可以",
                        "补充：失败时返回明确错误，且保持同一发布边界",
                        "采用推荐项 (Recommended)")
                .doesNotContain("# 补充后的完整设计", "异常和验收边界完整");
        assertThat(snapshot.markdown().indexOf("先实现查询能力"))
                .isLessThan(snapshot.markdown().indexOf("补充：失败时返回明确错误"));

        fake().setDecomposerOutput(decomposition("NEEDS_INPUT", "补充后的完整需求", 0));
        TaskProfileService.View profile = taskProfiles.current(session.id());
        taskProfiles.override(session.id(), profile.intent(), profile.artifactKinds().getFirst(), true, profile.version());
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
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow secondReview = designerSessions.get(session.id());
        fake().setDecomposerOutput(decomposition("NEEDS_INPUT", "第二版需求", 0));
        designerSessions.confirmRequirement(session.id(), secondReview.discussionRevision());

        DesignRequirementRevisionRow second = mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow();
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(mapper.findTaskDecompositionByRevision(second.id()).orElseThrow().id())
                .isNotEqualTo(firstDecomposition);
        int latestRequirementDiscussion = mapper.findLatestDesignDiscussionRevision(session.id(), "REQUIREMENT")
                .orElseThrow().revision();
        assertThat(mapper.listDesignDiscussionRevisions(session.id()).stream()
                .filter(row -> "REQUIREMENT".equals(row.scopeKey())
                        && row.revision() == latestRequirementDiscussion)
                .map(DesignDiscussionRevisionRow::requirementRevision)).containsExactly(2);
    }

    @Test
    void historicalFrozenAiSnapshotIsTheCompatibilityBaselineWhenSwitchingBackToDirectMode() throws Exception {
        ProjectRow project = project("historical-ai-snapshot");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 历史冻结需求\n\n保留既有业务语义。", legacySpec(project.id())));
        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), "原始历史需求");
        DesignerSessionRow frozen = designerSessions.get(session.id());
        designerSessions.reopenRequirement(session.id(), frozen.discussionRevision());

        fake().setDesignerOutput(designerOutput("# 未冻结 AI 推断\n\n这段内容不能进入普通快照。", legacySpec(project.id())));
        DesignerSessionRow reopened = designerSessions.get(session.id());
        designerSessions.appendRequirementMessage(session.id(), "新增用户明确边界", reopened.discussionRevision());
        completeMandatoryDesignerQuestion(session.id());
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();

        TaskProfileService.View large = taskProfiles.current(session.id());
        designerSessions.updateTaskProfile(session.id(), large.intent(), large.artifactKinds().getFirst(),
                false, large.version());

        assertThat(designerSessions.requirementSnapshot(session.id())).satisfies(snapshot -> {
            assertThat(snapshot.source()).isEqualTo("SERVER_ASSEMBLED");
            assertThat(snapshot.markdown()).contains(
                            "## 历史兼容基线", "# 历史冻结需求", "保留既有业务语义。",
                            "新增用户明确边界", "采用推荐项 (Recommended)")
                    .doesNotContain("# 未冻结 AI 推断", "这段内容不能进入普通快照");
        });
    }

    @Test
    void invalidV6CompilerOutputTriggersTargetedRedesignWithoutGenericStageFallback() throws Exception {
        ProjectRow project = project("optional-acceptance-advice");
        Files.writeString(Path.of(project.rootPath()).resolve("pom.xml"), "<project/>\n");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String design = """
                ## 目标与范围
                为 Java ObjectRegistry 补齐重复键拒绝的单元测试，不修改生产代码。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增测试 | src/test/java/example/ObjectRegistryTest.java | 重复键拒绝的聚焦测试 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | ObjectRegistry 重复键拒绝 | 已注册 key=A | 再次注册 key=A | 抛出 IllegalArgumentException | 首次注册仍可查询 |

                ## 验收约束
                ObjectRegistryTest 必须独立通过，不依赖外部网络、数据库或 Spring 上下文。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 注册表单元测试 | 验证重复键拒绝行为 | 需消歧的重复键场景；src/test/java/example/ObjectRegistryTest.java | 无 |
                """;
        fake().setDesignerOutput(designerOutput(design, legacySpec(project.id())));
        setPackageDesignerOutput("WP-1", design);
        fake().setPackageCompilerPlanningOutput("WP-1", "planning without required JSON");
        DesignerSessionRow reviewing = prepareReviewingSession(project.id(), draft.id(),
                "修改 Java ObjectRegistry 并用聚焦单元测试验收");
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        DesignerSessionRow session = designerSessions.get(reviewing.id());
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase())
                .as("session=%s compiler=%s packages=%s messages=%s", designerSessions.get(session.id()),
                        designerSessions.compilerStatus(session.id()),
                        designerSessions.workPackageStatuses(session.id()), designerSessions.messages(session.id()))
                .isEqualTo("FAILED");
        var compilation = mapper.findLatestLoopSpecCompilationForPackage(session.id(), "WP-1").orElseThrow();
        assertThat(designerSessions.compilerStatus(session.id()))
                .as("status=%s compilation=%s planning=%s", designerSessions.compilerStatus(session.id()),
                        compilation, mapper.findDesignAcceptancePlanning(compilation.id()).orElse(null))
                .satisfies(status -> {
            assertThat(status.serverCompiled()).isFalse();
            assertThat(status.formatRepairCount()).isZero();
            assertThat(status.semanticRepairCount()).isZero();
            assertThat(status.lastErrorCode()).isEqualTo("DESIGN_INCOMPLETE");
            assertThat(status.lastErrorDetail()).contains("AMBIGUOUS_ACCEPTANCE_INTENT");
        });
        assertThat(mapper.findDesignAcceptancePlanning(compilation.id())).hasValueSatisfying(planning -> {
            assertThat(planning.state()).isEqualTo("FAILED");
            assertThat(planning.bindingSource()).isEqualTo("AI_DISAMBIGUATION_V6");
            assertThat(planning.diagnosticsJson()).contains(
                    "ACCEPTANCE_DISAMBIGUATION_OUTPUT_UNPARSEABLE",
                    "\"mutationObligationCount\":1",
                    "\"resolvedMutationObligationCount\":0",
                    "\"unresolvedMutationObligationCount\":1",
                    "\"pathConservation\":\"NOT_EVALUATED\"");
        });
        assertThat(designerSessions.messages(session.id()))
                .anyMatch(message -> "DESIGN_INCOMPLETE".equals(message.deliveryState())
                        && message.content().contains("验收绑定未完成"));
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void deterministicDesignIncompletePersistsBoundPlanningInsteadOfBecomingSessionError() throws Exception {
        ProjectRow project = project("deterministic-incomplete-planning-state");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String design = """
                ## 目标与范围
                为 Java Listener 补齐事件投递行为，但当前设计尚未声明聚焦测试目标。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 生产代码 | src/main/java/example/Listener.java | 事件投递行为 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | Listener 投递事件 | 已注册监听器 | publish | 监听器收到事件 | 注册表不变 |

                ## 验收约束
                必须提供可重复的自动化测试，但当前设计没有声明测试目标。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 事件投递 | 实现并验证事件投递 | Listener 投递事件；src/main/java/example/Listener.java | 无 |
                """;
        fake().setDesignerOutput(designerOutput(design, legacySpec(project.id())));
        setPackageDesignerOutput("WP-1", design);
        DesignerSessionRow reviewing = prepareReviewingSession(project.id(), draft.id(),
                "修改 Java Listener 并提供聚焦单元测试");
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        DesignerSessionRow session = designerSessions.get(reviewing.id());
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.get(session.id()).state()).isNotEqualTo("SESSION_ERROR");
        var compilation = mapper.findLatestLoopSpecCompilationForPackage(session.id(), "WP-1").orElseThrow();
        assertThat(compilation.state()).isEqualTo("DESIGN_INCOMPLETE");
        assertThat(compilation.externalSessionId()).isNull();
        assertThat(compilation.externalSessionState()).isEqualTo("COMPLETED");
        assertThat(compilation.lastErrorCode()).isEqualTo("DESIGN_INCOMPLETE");
        assertThat(mapper.findDesignAcceptancePlanning(compilation.id())).hasValueSatisfying(planning -> {
            assertThat(planning.state()).isEqualTo("BOUND");
            assertThat(planning.bindingSource()).isEqualTo("SERVER_STAGE_HINTS");
            assertThat(planning.diagnosticsJson())
                    .contains("\"fastPathDecision\":\"DESIGN_INCOMPLETE\"",
                            "VERIFICATION_CAPABILITY_UNAVAILABLE");
        });
        assertThat(designerSessions.messages(session.id()))
                .noneSatisfy(message -> assertThat(message.content()).contains("SQLITE_CONSTRAINT_CHECK"));
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void compilerTreatsWrongRoleEnvelopeAsFormatFailureAndRepairsInFreshNoToolsSession() throws Exception {
        ProjectRow project = project("compiler-wrong-role-envelope");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput(
                "# 完整 Java 设计\n\n发布事件后监听器同步推进状态。\n\n聚焦测试：`mvn -q -Dtest=EventListenerFocusedTest test`。",
                legacySpec(project.id())));
        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"workPackageId":"WP-1","planStatus":"COMPILED","stages":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(),
                "新增 Java 行为并提供聚焦单元测试");
        pollUntilCompilerState(session.id(), "RUNNING", 1);

        var repairing = mapper.findLatestLoopSpecCompilation(session.id()).orElseThrow();
        assertThat(repairing.lastErrorCode()).isEqualTo("COMPILER_PLAN_OUTPUT_CONTRACT_MISMATCH");
        assertThat(repairing.formatRepairCount()).isEqualTo(1);
        assertThat(repairing.semanticRepairCount()).isZero();
        assertThat(repairing.semanticPlanJson()).isNull();
        assertThat(fake().profileForSession(repairing.externalSessionId()))
                .isEqualTo(OpenCodeClient.SessionProfile.COMPILER_REPAIR_NO_TOOLS);
        assertThat(fake().promptForSession(repairing.externalSessionId()))
                .contains("Built-in repository", "tools are disabled", "Configured MCP tools remain available",
                        "return the complete object immediately")
                .contains("Machine role contract 2026-08-semantic-v5", "Frozen DesignFacts", "mvn")
                .doesNotContain("Use DOCUMENT_STRUCTURE or TABULAR_DATA native evidence");
        List<String> compilerSessionIds = fake().promptHistory().stream()
                .filter(call -> call.prompt().contains("LOOPSPEC_COMPILATION_PLAN_JSON_START"))
                .map(FakeOpenCodeClient.PromptCall::sessionId).distinct().toList();
        assertThat(compilerSessionIds).hasSizeGreaterThanOrEqualTo(2);
        assertThat(fake().abortedSessionIds()).contains(compilerSessionIds.getFirst());

        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"outcome":"COMPILED","summary":"事件监听阶段","stages":[{
                 "objective":"发布事件后监听器同步推进状态","implementationKind":"JAVA_PRODUCTION",
                 "allowedPaths":["src/main/java/**","src/test/java/**"],"forbiddenPaths":[".env"],
                 "deliverables":["事件监听实现和聚焦测试"],
                 "criteria":[{"description":"发布事件后监听器同步推进状态","sourceRefs":["DS-L002"]}],
                 "evidence":[{"kind":"FOCUSED_TEST",
                   "command":["mvn","-q","-Dtest=EventListenerFocusedTest","test"],"covers":[0]},
                   {"kind":"GIT_DIFF","covers":[],"requireChanges":true,"forbidDeletes":true}],
                 "verificationRuntime":null}],"handoffSummary":"事件监听能力已冻结","designGaps":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);
        pollUntilSettled(session.id());
        assertThat(designerSessions.get(session.id()).workflowPhase())
                .as("session=%s compiler=%s packages=%s messages=%s", designerSessions.get(session.id()),
                        designerSessions.compilerStatus(session.id()),
                        designerSessions.workPackageStatuses(session.id()), designerSessions.messages(session.id()))
                .isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.compilerStatus(session.id()).serverCompiled()).isTrue();
    }

    @Test
    void controlledAcceptanceDesignFreezesV7MutationsAndUsesServerFastPathWithoutCompilerSession() throws Exception {
        fake().setStructuredCapability(new OpenCodeClient.StructuredOutputCapability(
                OpenCodeClient.CapabilityState.AVAILABLE, OpenCodeClient.CapabilityState.AVAILABLE, null));
        ProjectRow project = project("controlled-acceptance-v6");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String design = """
                ## 目标与范围
                为 Java PinTrans 冻结缺失 pinBlock 时的异常行为。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 生产代码 | upfs-common/src/main/java/com/spdb/upfs/pin/PinTrans.java | PIN 转换行为 |
                | 测试代码 | upfs-common/src/test/java/com/spdb/upfs/pin/PinTransTest.java | 聚焦单元测试 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | PinTrans: pinBlock 路径缺失 | 输入缺少 pinBlock 路径 | 调用转换 | 抛出 PinException，错误码 DEF9900000 | 不调用外部依赖 |

                ## 验收约束
                PinTransTest 必须独立通过，不依赖外部网络、数据库或 Spring 上下文。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | PIN 转换 | 实现并验证 PIN 转换异常行为 | PinTrans: pinBlock 路径缺失；upfs-common/src/main/java/com/spdb/upfs/pin/PinTrans.java；upfs-common/src/test/java/com/spdb/upfs/pin/PinTransTest.java | 无 |
                """;
        fake().setDesignerOutput(designerOutput(design, legacySpec(project.id())));
        setPackageDesignerOutput("WP-1", design);

        DesignerSessionRow reviewing = prepareReviewingSession(project.id(), draft.id(),
                "修改 Java PinTrans 并用 PinTransTest 验收异常行为");
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        DesignerSessionRow session = designerSessions.get(reviewing.id());
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase())
                .as("session=%s compiler=%s packages=%s messages=%s", designerSessions.get(session.id()),
                        designerSessions.compilerStatus(session.id()),
                        designerSessions.workPackageStatuses(session.id()), designerSessions.messages(session.id()))
                .isEqualTo("FINAL_REVIEW");
        var compilation = mapper.findLatestLoopSpecCompilationForPackage(session.id(), "WP-1").orElseThrow();
        assertThat(compilation.planningResponseSchemaId()).isEqualTo("PACKAGE_ACCEPTANCE_DISAMBIGUATION_V6");
        assertThat(compilation.externalSessionId()).isNull();
        assertThat(compilation.serverCompiled()).isTrue();
        assertThat(mapper.findDesignAcceptancePlanning(compilation.id())).hasValueSatisfying(planning -> {
            assertThat(planning.state()).isEqualTo("COMPILED");
            assertThat(planning.contractVersion()).isEqualTo("DESIGN_ACCEPTANCE_V7");
            assertThat(planning.bindingSource()).isEqualTo("SERVER_STAGE_HINTS");
            assertThat(planning.bindingJson()).contains("capabilityPreferences");
            assertThat(planning.factsJson()).contains(
                    "upfs-common/src/main/java/com/spdb/upfs/pin/PinTrans.java",
                    "upfs-common/src/test/java/com/spdb/upfs/pin/PinTransTest.java",
                    "mutationObligations", "sourceSha256");
            assertThat(planning.diagnosticsJson()).contains(
                    "EXACT_BRANCH_AND_BOUND",
                    "\"mutationObligationCount\":2",
                    "\"resolvedMutationObligationCount\":2",
                    "\"unresolvedMutationObligationCount\":0",
                    "\"pathConservation\":\"CONSERVED\"");
        });
        assertThat(designerSessions.workPackageStatuses(session.id())).singleElement().satisfies(workPackage -> {
            assertThat(workPackage.acceptancePlanning().state()).isEqualTo("COMPILED");
            assertThat(workPackage.acceptancePlanning().bindingSource()).isEqualTo("SERVER_STAGE_HINTS");
            assertThat(workPackage.acceptancePlanning().routingReasons()).isEmpty();
            assertThat(workPackage.acceptancePlanning().scenarioCount()).isEqualTo(1);
            assertThat(workPackage.acceptancePlanning().automatedCount()).isEqualTo(1);
            assertThat(workPackage.acceptancePlanning().unresolvedCount()).isZero();
        });
        assertThat(drafts.spec(drafts.get(draft.id())).stages()).singleElement().satisfies(stage -> {
            assertThat(stage.acceptanceCriteria()).singleElement()
                    .satisfies(criterion -> assertThat(criterion.description())
                            .contains("当输入缺少 pinBlock 路径时", "抛出 PinException", "不调用外部依赖"));
            assertThat(stage.verifiers()).filteredOn(verifier -> "TEST".equals(verifier.processPurpose()))
                    .singleElement().satisfies(verifier -> {
                        assertThat(verifier.command()).containsExactly(
                                "mvn", "-pl", "upfs-common", "-Dtest=PinTransTest", "test");
                        assertThat(verifier.testTargets()).containsExactly("PinTransTest");
                    });
        });
        mvc.perform(get("/api/designer-sessions/{id}", session.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workPackages[0].acceptancePlanning.state").value("COMPILED"))
                .andExpect(jsonPath("$.workPackages[0].acceptancePlanning.bindingSource").value("SERVER_STAGE_HINTS"))
                .andExpect(jsonPath("$.workPackages[0].acceptancePlanning.scenarioCount").value(1))
                .andExpect(jsonPath("$.workPackages[0].acceptancePlanning.unresolvedCount").value(0));
    }

    @Test
    void v7ServerCompilationBlocksWhenFrozenRequirementMutationIsMissingFromEveryStage() throws Exception {
        ProjectRow project = project("controlled-acceptance-v7-missing-mutation");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String design = """
                ## 目标与范围
                为 Java Service 实现外部适配行为。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 生产代码 | src/main/java/example/Service.java | 服务实现 |
                | 测试代码 | src/test/java/example/ServiceTest.java | 聚焦单元测试 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 外部适配生效 | 已配置 adapter | 调用服务 | 返回适配结果 | 无外部写入 |

                ## 验收约束
                ServiceTest 必须独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 服务实现 | 实现并验证服务 | 外部适配生效；src/main/java/example/Service.java；src/test/java/example/ServiceTest.java | 无 |
                """;
        fake().setDesignerOutput(designerOutput(design, legacySpec(project.id())));
        setPackageDesignerOutput("WP-1", design);
        DesignerSessionRow reviewing = prepareReviewingSession(project.id(), draft.id(), """
                修改 Java 外部适配能力：
                - 修改 `src/main/java/example/Service.java`
                - 新增 `config/external-adapter.yml`
                - 新增 `src/test/java/example/ServiceTest.java`
                """);

        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        pollUntilSettled(reviewing.id());

        assertThat(designerSessions.get(reviewing.id()).state()).isEqualTo("WAITING_INPUT");
        var compilation = mapper.findLatestLoopSpecCompilationForPackage(reviewing.id(), "WP-1").orElseThrow();
        assertThat(compilation.state()).isEqualTo("DESIGN_INCOMPLETE");
        assertThat(compilation.externalSessionId()).isNull();
        assertThat(compilation.serverCompiled()).isTrue();
        assertThat(compilation.lastErrorDetail()).contains("config/external-adapter.yml");
        assertThat(mapper.findDesignAcceptancePlanning(compilation.id())).hasValueSatisfying(planning -> {
            assertThat(planning.state()).isEqualTo("BOUND");
            assertThat(planning.contractVersion()).isEqualTo("DESIGN_ACCEPTANCE_V7");
            assertThat(planning.factsJson()).contains(
                    "config/external-adapter.yml", "REQUIREMENT:L", "sourceSha256");
            assertThat(planning.diagnosticsJson()).contains(
                    "\"mutationObligationCount\":3",
                    "\"resolvedMutationObligationCount\":2",
                    "\"unresolvedMutationObligationCount\":1",
                    "\"pathConservation\":\"BLOCKED\"");
        });
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void v7ProjectExternalMutationStopsBeforeCompilationWithoutTransportRetry() throws Exception {
        ProjectRow project = project("controlled-acceptance-v7-external-path");
        LoopSpec initial = legacySpec(project.id());
        LoopDraftRow draft = drafts.create(initial);
        String design = controlledDesign("# Java 外部路径边界\n\n只处理登记项目内文件。\n\nJava Maven");
        fake().setDesignerOutput(designerOutput(design, initial));
        setPackageDesignerOutput("WP-1", design);
        DesignerSessionRow reviewing = prepareReviewingSession(project.id(), draft.id(),
                "修改 Java 服务并写入 `/tmp/external-adapter.yml`");

        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        pollUntilSettled(reviewing.id());

        assertThat(designerSessions.get(reviewing.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(mapper.findLatestLoopSpecCompilationForPackage(reviewing.id(), "WP-1")).isEmpty();
        assertThat(mapper.findLatestDesignWorkPackage(reviewing.id(), "WP-1")).hasValueSatisfying(workPackage -> {
            assertThat(workPackage.state()).isEqualTo("WAITING_INPUT");
            assertThat(workPackage.lastErrorCode()).isEqualTo("PROJECT_ROOT_EXTERNAL_PATH");
            assertThat(workPackage.designerTransportRetryCount()).isZero();
        });
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void serverDirectCompilationIsRecoveredIdempotentlyFromPersistedRunningState() throws Exception {
        ProjectRow project = project("controlled-acceptance-v6-recovery");
        Files.writeString(Path.of(project.rootPath()).resolve("pom.xml"), "<project/>\n");
        LoopSpec initial = legacySpec(project.id());
        LoopDraftRow draft = drafts.create(initial);
        String design = controlledDesign("# Java 恢复设计\n\n恢复后仍按冻结阶段表编译。\n\nJava Maven");
        fake().setDesignerOutput(designerOutput(design, initial));
        setPackageDesignerOutput("WP-1", design);
        DesignerSessionRow reviewing = prepareReviewingSession(project.id(), draft.id(),
                "修改 Java 恢复逻辑并用 AcceptanceContractTest 验收");
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        pollUntilSettled(reviewing.id());

        var completed = mapper.findLatestLoopSpecCompilationForPackage(reviewing.id(), "WP-1").orElseThrow();
        var frozenPlanning = mapper.findDesignAcceptancePlanning(completed.id()).orElseThrow();
        String frozenFactsJson = frozenPlanning.factsJson();
        assertThat(frozenPlanning.contractVersion()).isEqualTo("DESIGN_ACCEPTANCE_V7");
        assertThat(frozenFactsJson).contains("mutationObligations", "sourceSha256");
        DesignWorkPackageRow workPackage = mapper.findLatestDesignWorkPackage(reviewing.id(), "WP-1").orElseThrow();
        DesignRequirementRevisionRow requirement = mapper.findCurrentDesignRequirementRevision(reviewing.id())
                .orElseThrow();
        int modelCalls = designerSessions.requirementStatus(reviewing.id()).modelCallsUsed();
        assertThat(completed.externalSessionId()).isNull();
        assertThat(jdbc.update("""
                UPDATE loop_spec_compilation
                SET state='RUNNING',external_session_id=NULL,external_session_state='SERVER_DIRECT',
                    compiled_package_json=NULL,workflow_step='SERVER_COMPILING',planning_json=NULL,
                    semantic_plan_json=NULL,server_compiled=0,last_error_code=NULL,last_error_detail=NULL,
                    version=version+1 WHERE id=?
                """, completed.id())).isEqualTo(1);
        assertThat(jdbc.update("""
                UPDATE design_acceptance_planning
                SET state='EXTRACTED',binding_source='SERVER_STAGE_HINTS',error_code=NULL,error_detail=NULL,
                    version=version+1 WHERE compilation_id=?
                """, completed.id())).isEqualTo(1);
        assertThat(jdbc.update("""
                UPDATE design_work_package
                SET state='COMPILING',compiler_summary=NULL,handoff_summary=NULL,approved_design_revision=NULL,
                    approved_at=NULL,last_error_code=NULL,last_error_detail=NULL,version=version+1 WHERE id=?
                """, workPackage.id())).isEqualTo(1);
        assertThat(jdbc.update("""
                UPDATE design_discussion_revision
                SET state='COMPILING',candidate_compilation_id=NULL,last_error_code=NULL,last_error_detail=NULL,
                    version=version+1 WHERE designer_session_id=? AND scope_key='WP-1'
                """, reviewing.id())).isEqualTo(1);
        assertThat(jdbc.update("""
                UPDATE design_requirement_revision SET state='ACTIVE',version=version+1 WHERE id=?
                """, requirement.id())).isEqualTo(1);
        assertThat(jdbc.update("""
                UPDATE designer_session
                SET state='RUNNING',workflow_phase='COMPILING',active_work_package_id='WP-1',
                    discussion_scope='WP-1',version=version+1 WHERE id=?
                """, reviewing.id())).isEqualTo(1);
        assertThat(jdbc.update("""
                UPDATE loop_draft SET goal=?,spec_json=?,status='DRAFT_READY',version=? WHERE id=?
                """, initial.goal(), json.writeValueAsString(initial), requirement.sourceDraftVersion(), draft.id()))
                .isEqualTo(1);

        assertThat(mapper.activeLoopSpecCompilations()).extracting(item -> item.id()).contains(completed.id());
        designerSessions.pollActiveHandoffs();

        var recovered = mapper.findLoopSpecCompilation(completed.id()).orElseThrow();
        assertThat(recovered.state()).isEqualTo("COMPLETED");
        assertThat(recovered.externalSessionId()).isNull();
        assertThat(recovered.serverCompiled()).isTrue();
        assertThat(mapper.findDesignAcceptancePlanning(completed.id())).hasValueSatisfying(planning ->
                assertThat(planning.factsJson()).isEqualTo(frozenFactsJson));
        assertThat(designerSessions.requirementStatus(reviewing.id()).modelCallsUsed()).isEqualTo(modelCalls);
        assertThat(designerSessions.get(reviewing.id()).workflowPhase())
                .as("session=%s packages=%s compiler=%s messages=%s",
                        designerSessions.get(reviewing.id()), designerSessions.workPackageStatuses(reviewing.id()),
                        designerSessions.compilerStatus(reviewing.id()), designerSessions.messages(reviewing.id()))
                .isEqualTo("FINAL_REVIEW");
        assertThat(drafts.spec(drafts.get(draft.id())).stages()).singleElement()
                .satisfies(stage -> assertThat(stage.objective()).contains("完成当前工作包"));
    }

    @Test
    void v6AcceptanceAmbiguityCreatesExactlyOneLockedDisambiguationSession() throws Exception {
        fake().setStructuredCapability(new OpenCodeClient.StructuredOutputCapability(
                OpenCodeClient.CapabilityState.AVAILABLE, OpenCodeClient.CapabilityState.AVAILABLE, null));
        ProjectRow project = project("controlled-acceptance-v6-disambiguation");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String design = """
                ## 目标与范围
                为 Java Flow 增加成功和失败两条可观察路径。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 生产代码 | src/main/java/example/Flow.java | 流程行为 |
                | 测试代码 | src/test/java/example/FlowTest.java | 聚焦单元测试 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 成功路径 | 输入合法 | 执行 Flow | 返回成功结果 | 不写外部系统 |
                | 失败路径 | 输入非法 | 执行 Flow | 抛出领域异常 | 不写外部系统 |

                ## 验收约束
                FlowTest 必须独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 成功阶段 | 实现成功路径 | 成功路径；src/main/java/example/Flow.java | 无 |
                | 失败阶段 | 实现失败路径 | 需消歧的失败场景；src/test/java/example/FlowTest.java | 成功阶段 |
                """;
        fake().setDesignerOutput(designerOutput(design, legacySpec(project.id())));
        setPackageDesignerOutput("WP-1", design);
        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"summary":"失败路径已消歧","factAssignments":[{"factIndex":3,"stageIndex":1}],
                 "capabilityPreferences":[],"handoffSummary":"两阶段流程已冻结"}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);

        DesignerSessionRow reviewing = prepareReviewingSession(project.id(), draft.id(),
                "修改 Java Flow 并用 FlowTest 验收成功和失败行为");
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        pollUntilSettled(reviewing.id());

        var compilation = mapper.findLatestLoopSpecCompilationForPackage(
                reviewing.id(), "WP-1").orElseThrow();
        assertThat(compilation.planningResponseSchemaId()).isEqualTo("PACKAGE_ACCEPTANCE_DISAMBIGUATION_V6");
        assertThat(compilation.formatRepairCount()).isZero();
        assertThat(compilation.semanticRepairCount()).isZero();
        assertThat(fake().profileForSession(compilation.externalSessionId()))
                .isEqualTo(OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS);
        assertThat(mapper.findDesignAcceptancePlanning(compilation.id())).hasValueSatisfying(planning -> {
            assertThat(planning.bindingSource()).isEqualTo("AI_DISAMBIGUATION_V6");
            assertThat(planning.bindingJson()).contains("factIndexes").doesNotContain("factAssignments");
            assertThat(planning.diagnosticsJson()).contains("UNRESOLVED_FACTS:[3]");
        });
        assertThat(fake().promptHistory().stream()
                .filter(call -> call.prompt().contains("factAssignments")
                        && call.prompt().contains("WP-1"))).hasSize(1);
        assertThat(fake().promptForSession(compilation.externalSessionId()))
                .contains("Machine role contract 2026-08-semantic-v6", "server-locked stage topology")
                .doesNotContain("Machine role contract 2026-08-semantic-v5");
        assertThat(drafts.spec(drafts.get(draft.id())).stages()).hasSize(2);
    }

    @Test
    void largeV6PackagesStillUseOneCompilerPassWithoutChangingFrozenStageTopology() throws Exception {
        fake().setStructuredCapability(new OpenCodeClient.StructuredOutputCapability(
                OpenCodeClient.CapabilityState.AVAILABLE, OpenCodeClient.CapabilityState.AVAILABLE, null));
        ProjectRow project = project("large-v6-locked-topology");
        Files.writeString(Path.of(project.rootPath()).resolve("pom.xml"), "<project/>\n");
        LoopSpec initial = legacySpec(project.id());
        LoopDraftRow draft = drafts.create(initial);
        fake().setDesignerOutput(designerOutput(
                "开发两个依赖有序的 Java 工作包，每个工作包都必须用 Maven 聚焦单元测试验证可观察行为。",
                initial));
        fake().setDecomposerOutput(decomposition("DECOMPOSED", "两包 Java 能力", 2));
        String firstDesign = stageControlledDesign("# WP-1 Java 设计", 2);
        String secondDesign = stageControlledDesign("# WP-2 Java 设计", 1);
        setPackageDesignerOutput("WP-1", firstDesign);
        setPackageDesignerOutput("WP-2", secondDesign);
        String emptyDisambiguation = """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"summary":"阶段拓扑保持不变","factAssignments":[],"capabilityPreferences":[],
                 "handoffSummary":"按冻结阶段交接"}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """;
        fake().setPackageCompilerPlanningOutput("WP-1", emptyDisambiguation);
        fake().setPackageCompilerPlanningOutput("WP-2", emptyDisambiguation);

        DesignerSessionRow reviewing = prepareLargeReviewingSession(project.id(), draft.id(),
                "大型 Java 任务：分两包实现三个依赖有序阶段并用 Maven 聚焦测试验收");
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        pollUntilSettled(reviewing.id());

        assertThat(designerSessions.get(reviewing.id()).workflowPhase())
                .as("session=%s packages=%s compiler=%s messages=%s",
                        designerSessions.get(reviewing.id()), designerSessions.workPackageStatuses(reviewing.id()),
                        designerSessions.compilerStatus(reviewing.id()), designerSessions.messages(reviewing.id()))
                .isEqualTo("FINAL_REVIEW");
        var firstCompilation = mapper.findLatestLoopSpecCompilationForPackage(reviewing.id(), "WP-1")
                .orElseThrow();
        var secondCompilation = mapper.findLatestLoopSpecCompilationForPackage(reviewing.id(), "WP-2")
                .orElseThrow();
        assertThat(List.of(firstCompilation, secondCompilation)).allSatisfy(compilation -> {
            assertThat(compilation.planningResponseSchemaId())
                    .isEqualTo("PACKAGE_ACCEPTANCE_DISAMBIGUATION_V6");
            assertThat(fake().profileForSession(compilation.externalSessionId()))
                    .isEqualTo(OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS);
            assertThat(mapper.findDesignAcceptancePlanning(compilation.id())).hasValueSatisfying(planning -> {
                assertThat(planning.bindingSource()).isEqualTo("AI_DISAMBIGUATION_V6");
                assertThat(planning.bindingJson()).contains("阶段 1").doesNotContain("factAssignments");
            });
        });
        assertThat(fake().promptHistory().stream()
                .filter(call -> call.prompt().contains("factAssignments"))).hasSize(2);
        assertThat(drafts.spec(drafts.get(draft.id())).stages())
                .extracting(LoopSpec.StageSpec::objective)
                .containsExactly("完成能力 1", "完成能力 2", "完成能力 1");
        assertThat(drafts.spec(drafts.get(draft.id())).stages())
                .extracting(LoopSpec.StageSpec::workPackageId)
                .containsExactly("WP-1", "WP-1", "WP-2");
    }

    @Test
    void pythonAndNodeRolePacksCompileRepositoryNativeFocusedTestsToFinalReview() throws Exception {
        assertNonJavaCompilerFlow("python-compiler-role", "pyproject.toml", "[tool.pytest.ini_options]\n",
                "开发 Python 事件监听器并使用 pytest 聚焦测试",
                "# Python 监听设计\n\n发布事件后 Python 监听器同步收到消息。\n\n聚焦测试：`python3 -m pytest tests/test_listener.py`。",
                "software-python", "[\"python3\",\"-m\",\"pytest\",\"tests/test_listener.py\"]",
                "src/**", "tests/**");
        assertNonJavaCompilerFlow("node-compiler-role", "package.json",
                "{\"scripts\":{\"test\":\"vitest\"}}\n",
                "开发 TypeScript 事件监听器并使用 npm 聚焦测试",
                "# Node 监听设计\n\n发布事件后 TypeScript 监听器同步更新界面。\n\n聚焦测试：`npm test -- src/listener.spec.ts`。",
                "software-node", "[\"npm\",\"test\",\"--\",\"src/listener.spec.ts\"]",
                "src/**", "src/listener.spec.ts");
    }

    @Test
    void mixedAndGenericRolePacksCompileWithoutFallingBackToJavaOnlyContracts() throws Exception {
        ProjectRow mixedProject = project("mixed-compiler-role");
        Files.writeString(Path.of(mixedProject.rootPath()).resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>\n");
        Files.writeString(Path.of(mixedProject.rootPath()).resolve("package.json"),
                "{\"scripts\":{\"test\":\"vitest\"}}\n");
        LoopSpec mixedInitial = legacySpec(mixedProject.id());
        LoopDraftRow mixedDraft = drafts.create(mixedInitial);
        String mixedDesign = "# 混合栈监听设计\n\nJava 后端发布事件并返回可观察结果。"
                + "\n\nTypeScript 前端接收结果并更新界面。";
        fake().setDesignerOutput(designerOutput(mixedDesign, mixedInitial));
        setPackageDesignerOutput("WP-1", mixedDesign);
        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"outcome":"COMPILED","summary":"mixed vertical listener plan","stages":[
                 {"objective":"Java 后端发布事件","implementationKind":"JAVA_PRODUCTION",
                  "allowedPaths":["backend/**"],"forbiddenPaths":[".env"],
                  "deliverables":["后端事件行为与聚焦测试"],
                  "criteria":[{"description":"后端发布事件后产生可观察结果","sourceRefs":["DS-L002"]}],
                  "evidence":[{"kind":"FOCUSED_TEST","command":["mvn","-q","-Dtest=BackendListenerTest","test"],"covers":[0]},
                    {"kind":"GIT_DIFF","covers":[],"requireChanges":true,"forbidDeletes":true}],
                  "verificationRuntime":null},
                 {"objective":"TypeScript 前端展示事件结果","implementationKind":"NON_JAVA",
                  "allowedPaths":["frontend/**"],"forbiddenPaths":[".env"],
                  "deliverables":["前端监听行为与聚焦测试"],
                  "criteria":[{"description":"前端收到事件后更新界面","sourceRefs":["DS-L003"]}],
                  "evidence":[{"kind":"FOCUSED_TEST","command":["npm","test","--","src/listener.spec.ts"],"covers":[0]},
                    {"kind":"GIT_DIFF","covers":[],"requireChanges":true,"forbidDeletes":true}],
                  "verificationRuntime":null}],"handoffSummary":"跨栈行为已冻结","designGaps":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);

        DesignerSessionRow mixedSession = createConfirmedSession(mixedProject.id(), mixedDraft.id(),
                "开发 Java 后端和 TypeScript 前端的事件监听能力并分别聚焦测试");
        pollUntilSettled(mixedSession.id());
        assertThat(designerSessions.get(mixedSession.id()).workflowPhase())
                .as("compiler=%s messages=%s", designerSessions.compilerStatus(mixedSession.id()),
                        designerSessions.messages(mixedSession.id()))
                .isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.workPackageStatuses(mixedSession.id())).singleElement()
                .satisfies(workPackage -> assertThat(workPackage.rolePackId()).isEqualTo("software-mixed"));
        assertThat(drafts.spec(drafts.get(mixedDraft.id())).stages())
                .flatExtracting(LoopSpec.StageSpec::verifiers)
                .filteredOn(verifier -> "TEST".equals(verifier.processPurpose()))
                .hasSize(2).allSatisfy(verifier -> assertThat(verifier.testTargets()).isNotEmpty());

        ProjectRow genericProject = project("generic-compiler-role");
        Files.writeString(Path.of(genericProject.rootPath()).resolve("go.mod"),
                "module example.com/listener\n\ngo 1.23\n");
        LoopSpec genericInitial = legacySpec(genericProject.id());
        LoopDraftRow genericDraft = drafts.create(genericInitial);
        String genericDesign = "# Go 监听设计\n\n发布事件后 Go 监听器返回可观察结果。";
        fake().setDesignerOutput(designerOutput(genericDesign, genericInitial));
        setPackageDesignerOutput("WP-1", genericDesign);
        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"outcome":"COMPILED","summary":"repository-native Go listener plan","stages":[{
                 "objective":"实现 Go 事件监听行为","implementationKind":"NON_JAVA",
                 "allowedPaths":["listener.go"],"forbiddenPaths":[".env"],
                 "deliverables":["Go 监听实现"],
                 "criteria":[{"description":"发布事件后 Go 监听器返回冻结结果","sourceRefs":["DS-L002"],
                   "judgeRubric":"根据实现证据确认事件发布后返回冻结结果","judgeOnlyReason":null}],
                 "evidence":[{"kind":"FILE_CONTENT","path":"listener.go","expectedContent":"Publish","covers":[0]},
                   {"kind":"GIT_DIFF","covers":[],"requireChanges":true,"forbidDeletes":true}],
                 "verificationRuntime":null}],"handoffSummary":"Go 监听合同已冻结","designGaps":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """);

        DesignerSessionRow genericSession = createConfirmedSession(genericProject.id(), genericDraft.id(),
                "开发 Go 事件监听器并保持仓库原生工具链");
        pollUntilSettled(genericSession.id());
        assertThat(designerSessions.get(genericSession.id()).workflowPhase())
                .as("compiler=%s messages=%s", designerSessions.compilerStatus(genericSession.id()),
                        designerSessions.messages(genericSession.id()))
                .isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.workPackageStatuses(genericSession.id())).singleElement()
                .satisfies(workPackage -> assertThat(workPackage.rolePackId()).isEqualTo("software-generic"));
        String genericCompilerPrompt = fake().promptHistory().stream()
                .filter(call -> call.prompt().contains("Machine role contract 2026-08-semantic-v5")
                        && call.prompt().contains("Go 监听器")
                        && call.prompt().contains("LOOPSPEC_COMPILATION_PLAN_JSON_START"))
                .map(FakeOpenCodeClient.PromptCall::prompt).findFirst().orElseThrow();
        assertThat(genericCompilerPrompt).contains("Frozen DesignFacts", "Frozen verification capabilities")
                .doesNotContain("-Dtest=ExampleFocusedTest", "python3 -m pytest", "npm test --");
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
                .contains("Machine role contract 2026-08-semantic-v5")
                .contains("DESIGN_ACCEPTANCE_V5", "FOCUSED_TEST", "Frozen verification capabilities")
                .contains("server owns EARS criterion text", "groupHints", "capabilityIndexes");
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
        setPackageDesignerOutput("WP-1", firstDesign);
        fake().setPackageCompilerPlanningOutput("WP-1", packageCompilationPlanV2("WP-1",
                "README 事件说明可执行自检", false));
        fake().setPackageCompilerOutput("WP-1", packageCompilationV2("WP-1",
                "README 事件说明可执行自检"));

        String dependentExcerpt = "依赖前置包提供的 `EventPublisher`，发布事件后状态机推进到 PAID。";
        String secondDesign = "# WP-2 设计\n\n" + dependentExcerpt
                + "\n\n聚焦单元测试：`mvn -q -Dtest=EventStateBridgeTest test`。";
        setPackageDesignerOutput("WP-2", secondDesign);
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
        assertThat(designerSessions.get(session.id()).workflowPhase())
                .as("session=%s compiler=%s packages=%s messages=%s", designerSessions.get(session.id()),
                        designerSessions.compilerStatus(session.id()),
                        designerSessions.workPackageStatuses(session.id()), designerSessions.messages(session.id()))
                .isEqualTo("FINAL_REVIEW");
        TaskRow task = drafts.confirm(draft.id(), "历史设计");

        mvc.perform(get("/api/designer-sessions/history").queryParam("projectId", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(session.id()))
                .andExpect(jsonPath("$[0].draftStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].taskId").value(task.id()))
                .andExpect(jsonPath("$[0].taskState").value("PENDING_START"));

        mvc.perform(get("/api/tasks/{id}/design-history", task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirement.revision").value(1))
                .andExpect(jsonPath("$.decomposition.resultType").value("DIRECT_DESIGN"))
                .andExpect(jsonPath("$.workPackages[0].id").value("WP-1"))
                .andExpect(jsonPath("$.designerSession.messages[?(@.actor == 'DECOMPOSER')]").exists())
                .andExpect(jsonPath("$.designerSession.messages[?(@.workPackageId == 'WP-1')]").exists());

        LoopDraftRow reworkDraft = drafts.create(legacySpec(project.id()));
        TaskRow rework = drafts.confirm(reworkDraft.id(), "历史设计重做");
        assertThat(mapper.insertTaskLineage(new TaskLineageRow(rework.id(), task.id(), "REWORK_ALL_STAGES",
                null, "baseline", Instant.now().toString(), task.id(), draft.id(), session.id()))).isEqualTo(1);

        mvc.perform(get("/api/tasks/{id}/design-history", rework.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.id").value(reworkDraft.id()))
                .andExpect(jsonPath("$.designSourceTaskId").value(task.id()))
                .andExpect(jsonPath("$.inheritedConversation").value(true))
                .andExpect(jsonPath("$.designerSession.id").value(session.id()))
                .andExpect(jsonPath("$.requirement.requirementText")
                        .value(org.hamcrest.Matchers.containsString("历史设计")))
                .andExpect(jsonPath("$.designerSession.messages[?(@.actor == 'DECOMPOSER')]").exists());
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

    private void assertNonJavaCompilerFlow(String projectName, String manifest, String manifestContent,
                                           String requirement, String design, String expectedRolePack,
                                           String commandJson, String implementationPath,
                                           String testPath) throws Exception {
        ProjectRow project = project(projectName);
        Files.writeString(Path.of(project.rootPath()).resolve(manifest), manifestContent);
        LoopSpec initial = legacySpec(project.id());
        LoopDraftRow draft = drafts.create(initial);
        fake().setDesignerOutput(designerOutput(design, initial));
        setPackageDesignerOutput("WP-1", design);
        fake().setPackageCompilerPlanningOutput("WP-1", """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"outcome":"COMPILED","summary":"repository-native listener plan","stages":[{
                 "objective":"发布事件后监听器产生可观察结果","implementationKind":"NON_JAVA",
                 "allowedPaths":["%s","%s"],"forbiddenPaths":[".env"],
                 "deliverables":["监听器实现和聚焦测试"],
                 "criteria":[{"description":"发布事件后监听器产生冻结的可观察结果","sourceRefs":["DS-L002"]}],
                 "evidence":[{"kind":"FOCUSED_TEST","command":%s,"covers":[0]},
                   {"kind":"GIT_DIFF","covers":[],"requireChanges":true,"forbidDeletes":true}],
                 "verificationRuntime":null}],"handoffSummary":"监听器能力已冻结","designGaps":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """.formatted(implementationPath, testPath, commandJson));

        DesignerSessionRow session = createConfirmedSession(project.id(), draft.id(), requirement);
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).workflowPhase())
                .as("role=%s compiler=%s messages=%s", expectedRolePack,
                        designerSessions.compilerStatus(session.id()), designerSessions.messages(session.id()))
                .isEqualTo("FINAL_REVIEW");
        assertThat(designerSessions.workPackageStatuses(session.id())).singleElement().satisfies(workPackage -> {
            assertThat(workPackage.rolePackId()).isEqualTo(expectedRolePack);
            assertThat(workPackage.rolePackVersion()).isEqualTo("2026-08-dynamic-v5");
            assertThat(workPackage.testPolicy()).isIn("OPTIONAL", "REQUIRED");
            assertThat(workPackage.compilerServerCompiled()).isTrue();
        });
        assertThat(drafts.spec(drafts.get(draft.id())).stages().getFirst().verifiers())
                .filteredOn(verifier -> "TEST".equals(verifier.processPurpose()))
                .singleElement().satisfies(verifier -> assertThat(verifier.testTargets()).isNotEmpty());
    }

    private DesignerSessionRow createConfirmedSession(String projectId, String draftId, String requirement) {
        DesignerSessionRow created = designerSessions.create(projectId, draftId, requirement);
        legacyAcceptanceSessions.add(created.id());
        designerSessions.pollActiveHandoffs();
        TaskProfileService.View profile = taskProfiles.current(created.id());
        if (profile.intent().name().equals("SOFTWARE_CHANGE")
                && profile.workflowTemplate().name().equals("DIRECT_SOFTWARE_DESIGN")) {
            designerSessions.updateTaskProfile(created.id(), profile.intent(), profile.artifactKinds().getFirst(),
                    true, profile.version());
        } else if (!profile.confirmationReady()) {
            taskProfiles.confirmRecommendation(created.id(), profile.version());
            designerSessions.continueAfterTaskProfileDecision(created.id());
        }
        completeMandatoryDesignerQuestion(created.id());
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow reviewing = designerSessions.get(created.id());
        assertThat(reviewing.state()).isEqualTo("REVIEWING");
        TaskProfileService.View currentProfile = taskProfiles.current(reviewing.id());
        if (!currentProfile.confirmationReady() && !"ROUTING".equals(currentProfile.decisionState())) {
            taskProfiles.confirmRecommendation(reviewing.id(), currentProfile.version());
        }
        designerSessions.confirmRequirement(reviewing.id(), reviewing.discussionRevision());
        return designerSessions.get(reviewing.id());
    }

    private DesignerSessionRow prepareReviewingSession(String projectId, String draftId, String requirement) {
        DesignerSessionRow session = designerSessions.create(projectId, draftId, requirement);
        designerSessions.pollActiveHandoffs();
        confirmInitialTaskProfile(session.id());
        completeMandatoryDesignerQuestion(session.id());
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow reviewing = designerSessions.get(session.id());
        assertThat(reviewing.state()).isEqualTo("REVIEWING");
        return reviewing;
    }

    private DesignerSessionRow prepareLargeReviewingSession(String projectId, String draftId, String requirement) {
        DesignerSessionRow session = designerSessions.create(projectId, draftId, requirement);
        designerSessions.pollActiveHandoffs();
        TaskProfileService.View profile = taskProfiles.current(session.id());
        designerSessions.updateTaskProfile(session.id(), profile.intent(), profile.artifactKinds().getFirst(),
                true, profile.version());
        completeMandatoryDesignerQuestion(session.id());
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();
        DesignerSessionRow reviewing = designerSessions.get(session.id());
        assertThat(reviewing.state()).isEqualTo("REVIEWING");
        TaskProfileService.View currentProfile = taskProfiles.current(reviewing.id());
        if (!currentProfile.confirmationReady() && !"ROUTING".equals(currentProfile.decisionState())) {
            taskProfiles.confirmRecommendation(reviewing.id(), currentProfile.version());
        }
        return reviewing;
    }

    private void confirmInitialTaskProfile(String sessionId) {
        TaskProfileService.View profile = taskProfiles.current(sessionId);
        if (!profile.confirmationReady()) taskProfiles.confirmRecommendation(sessionId, profile.version());
        designerSessions.continueAfterTaskProfileDecision(sessionId);
    }

    private void completeMandatoryDesignerQuestion(String sessionId) {
        DesignerSessionRow session = designerSessions.get(sessionId);
        if (!"RUNNING".equals(session.state())
                || !("DISCUSSING_REQUIREMENT".equals(session.workflowPhase())
                || "QUESTIONING_PACKAGE".equals(session.workflowPhase()))) return;
        if ("QUESTIONING_PACKAGE".equals(session.workflowPhase())
                && legacyAcceptanceSessions.contains(sessionId)) {
            jdbc.update("UPDATE design_work_package SET role_pack_version='2026-08-dynamic-v5' "
                    + "WHERE designer_session_id=? AND package_id=?", sessionId, session.discussionScope());
        }
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

    private void pollUntilRollingPackageReview(String taskId, String sessionId) {
        for (int attempt = 0; attempt < 80; attempt++) {
            var run = mapper.currentTaskPackageRun(taskId).orElse(null);
            if (run != null && "DESIGN_REVIEW".equals(run.state())) {
                var workPackage = designerSessions.workPackageStatuses(sessionId).stream()
                        .filter(item -> item.id().equals(run.packageKey()))
                        .findFirst().orElse(null);
                if (workPackage != null && "REVIEWING".equals(workPackage.state())) return;
            }
            pollWithMandatoryQuestion(sessionId);
        }
        throw new AssertionError("rolling package review did not settle: run="
                + mapper.currentTaskPackageRun(taskId).orElse(null)
                + " packages=" + designerSessions.workPackageStatuses(sessionId)
                + " session=" + designerSessions.get(sessionId)
                + " errors=" + mapper.listErrors(taskId)
                + " events=" + mapper.eventsAfter(taskId, 0)
                + " messages=" + designerSessions.messages(sessionId));
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

    private String directDecompositionPlan(String title, String goal) throws Exception {
        Map<String, Object> workPackage = new LinkedHashMap<>();
        workPackage.put("title", title);
        workPackage.put("objective", goal);
        workPackage.put("scopeIn", List.of(title));
        workPackage.put("scopeOut", List.of());
        workPackage.put("deliverables", List.of(goal));
        workPackage.put("acceptanceIntent", List.of("业务行为可验证"));
        workPackage.put("dependsOn", List.of());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("outcome", "READY");
        envelope.put("normalizedGoal", goal);
        envelope.put("globalConstraints", List.of());
        envelope.put("workPackages", List.of(workPackage));
        envelope.put("coverage", List.of(Map.of(
                "requirementRef", "RQ-1",
                "targetType", "WORK_PACKAGE",
                "targetIndex", 0)));
        envelope.put("designGaps", List.of());
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

    private String packageDesignIncompletePlan(String code, String detail) throws Exception {
        return "<!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->\n" + json.writeValueAsString(Map.of(
                "outcome", "DESIGN_INCOMPLETE", "summary", "默认单包容量不足", "stages", List.of(),
                "handoffSummary", "", "designGaps", List.of(Map.of("code", code, "detail", detail))))
                + "\n<!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->";
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

    private LoopSpec legacySpecWithStages(String projectId, int stageCount) {
        List<LoopSpec.StageSpec> stages = java.util.stream.IntStream.rangeClosed(1, stageCount)
                .mapToObj(index -> new LoopSpec.StageSpec("Implement step " + index,
                        List.of("src/**"), List.of(".env"), List.of("README-" + index + ".md"),
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null,
                                List.of("README.md"), List.of(), true))))
                .toList();
        return new LoopSpec("v1", projectId, "Implement the validated designer plan",
                "Keep the worktree isolated", stages, new LoopSpec.Limits(3, 3, 2, 2, 3600L, 120L, 60L),
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

    private void setPackageDesignerOutput(String packageId, String markdown) {
        fake().setPackageDesignerOutput(packageId, markdown);
    }

    private String controlledDesign(String markdown) {
        if (markdown != null && markdown.contains("## 目标与范围")) return markdown;
        String source = markdown == null ? "" : markdown;
        String lower = source.toLowerCase();
        String target;
        String command;
        if (lower.contains("java") || lower.contains("maven") || lower.contains("objectregistry")) {
            target = "src/test/java/example/AcceptanceContractTest.java";
            command = "`mvn -q -Dtest=AcceptanceContractTest test`";
        } else if (lower.contains("typescript") || lower.contains("node") || lower.contains("npm")) {
            target = "tests/acceptance.test.ts";
            command = "`npm test -- tests/acceptance.test.ts`";
        } else {
            target = "tests/test_acceptance.py";
            command = "`python3 -m pytest tests/test_acceptance.py`";
        }
        return source + """

                ## 目标与范围
                完成当前工作包并保留原设计中的业务边界。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增测试 | %s | 当前验收场景的聚焦测试 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 当前工作包验收 | 已满足设计前置条件 | 执行当前工作包能力 | 产生原设计声明的可观察结果 | 原设计的其他边界保持不变 |

                ## 验收约束
                聚焦测试必须独立通过：%s

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 实现与验证 | 完成当前工作包并验证可观察结果 | 当前工作包验收；%s | 无 |
                """.formatted(target, command, target);
    }

    private String rollingControlledDesign(String markdown) {
        return markdown + """

                ## 目标与范围
                完成当前滚动工作包，并且只修改 README 中的可观察事实。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 修改 | README.md | 当前包形成的可观察结果 |
                | 测试 | tests/test_acceptance.py | 当前包的聚焦验收测试 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 当前包验收 | 前序事实已冻结 | 执行当前包 | README 保留 event 标记，由 tests/test_acceptance.py 覆盖 | 既有事实不删除 |

                ## 验收约束
                聚焦测试必须独立通过：
                `python3 -m unittest tests.test_acceptance`

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 实现与验证 | 写入并验证当前包事实 | 当前包验收；README.md；tests/test_acceptance.py | 无 |
                """;
    }

    private String stageControlledDesign(String markdown, int stageCount) {
        StringBuilder scenarios = new StringBuilder();
        StringBuilder stages = new StringBuilder();
        for (int index = 1; index <= stageCount; index++) {
            scenarios.append("| 场景 ").append(index)
                    .append(" | 前序阶段已完成 | 执行能力 ").append(index)
                    .append(" | 产生可观察结果 ").append(index)
                    .append(" | 已有边界保持不变 |\n");
            stages.append("| 阶段 ").append(index).append(" | 完成能力 ").append(index)
                    .append(" | 场景 ").append(index)
                    .append("；src/test/java/example/AcceptanceContractTest.java | ")
                    .append(index == 1 ? "无" : "阶段 " + (index - 1)).append(" |\n");
        }
        return markdown + """

                ## 目标与范围
                完成受控多阶段软件能力。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增测试 | src/test/java/example/AcceptanceContractTest.java | 聚焦验收测试 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                """ + scenarios + """

                ## 验收约束
                `mvn -q -Dtest=AcceptanceContractTest test` 必须独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                """ + stages;
    }

    private String acceptanceDisambiguation() {
        return """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"summary":"locked topology confirmed","factAssignments":[],"capabilityPreferences":[],
                 "handoffSummary":"server-owned topology preserved"}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """;
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
