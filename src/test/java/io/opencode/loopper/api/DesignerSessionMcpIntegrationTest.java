package io.opencode.loopper.api;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopDraftStatus;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.opencode.model=opencode/deepseek-v4-flash-free",
        "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h",
        "loopper.mcp.bearer-token=designer-mcp-test-token",
        "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.name=opencode-loopper",
        "spring.ai.mcp.server.version=0.1.43", "spring.ai.mcp.server.annotation-scanner.enabled=false",
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
    }

    @Test
    void directDesignUsesIndependentReadOnlyRolesAndCreatesNoTaskBeforeConfirmation() throws Exception {
        ProjectRow project = project("direct");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 单包设计\n\n缓存刷新后用户能看到新值。", legacySpec(project.id())));

        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "实现缓存刷新并保留验收证据");
        assertThat(session.workflowPhase()).isEqualTo("DECOMPOSING");
        assertThat(mapper.listTasks()).isEmpty();
        pollUntilSettled(session.id());

        DesignerSessionRow completed = designerSessions.get(session.id());
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.workflowPhase()).isEqualTo("COMPLETED");
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(3);
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(5);
        assertThat(designerSessions.decompositionStatus(session.id()).resultType()).isEqualTo("DIRECT_DESIGN");
        assertThat(designerSessions.workPackageStatuses(session.id())).singleElement().satisfies(workPackage -> {
            assertThat(workPackage.id()).isEqualTo("WP-1");
            assertThat(workPackage.state()).isEqualTo("COMPLETED");
        });
        assertThat(designerSessions.messages(session.id()).stream().map(message -> message.actor()).toList())
                .contains("DECOMPOSER", "DESIGNER", "COMPILER", "VALIDATOR")
                .doesNotContain("{\"status\":\"COMPILED\"");
        assertThat(mapper.listTasks()).isEmpty();
        assertThat(drafts.spec(drafts.get(draft.id())).stages()).allMatch(stage -> "WP-1".equals(stage.workPackageId()));

        TaskRow task = drafts.confirm(draft.id(), "缓存刷新");
        assertThat(mapper.listTasks()).singleElement().extracting(TaskRow::id).isEqualTo(task.id());
        assertThat(tasks.artifacts(task.id()).stream().map(TaskArtifactRow::kind).toList())
                .contains("REQUIREMENT_CONTEXT", "DECOMPOSITION_CONTEXT", "WORK_PACKAGE_DESIGN",
                        "WORK_PACKAGE_COMPILATION_SUMMARY", "DESIGN_CONTEXT");
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

        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "交付一个包含三个纵向能力的大型需求");
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
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("COMPLETED");
        assertPackageStates(session.id(), "COMPLETED", "COMPLETED", "COMPLETED");
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(7);
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(11);
        LoopSpec aggregate = drafts.spec(drafts.get(draft.id()));
        assertThat(aggregate.goal()).isEqualTo("交付三段纵向能力");
        assertThat(aggregate.stages()).extracting(LoopSpec.StageSpec::workPackageId)
                .containsExactly("WP-1", "WP-2", "WP-3");
        assertThat(aggregate.limits().maxTaskAttempts()).isGreaterThanOrEqualTo(9);
        assertThat(mapper.listTasks()).isEmpty();

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

        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "一个需要六个纵向工作包的大型任务");
        pollUntilSettled(session.id());

        assertThat(designerSessions.get(session.id()).state()).isEqualTo("COMPLETED");
        assertThat(designerSessions.workPackageStatuses(session.id())).hasSize(6)
                .allMatch(workPackage -> "COMPLETED".equals(workPackage.state()));
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(20);
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(13);
        assertThat(drafts.spec(drafts.get(draft.id())).stages()).extracting(LoopSpec.StageSpec::workPackageId)
                .containsExactly("WP-1", "WP-2", "WP-3", "WP-4", "WP-5", "WP-6");
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void concurrentDraftChangeStopsBeforeAnyPackageModelCall() throws Exception {
        ProjectRow project = project("draft-conflict");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerOutput(decomposition("DIRECT_DESIGN", "不覆盖人工草稿", 1));
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "冻结后不得覆盖人工修改");
        LoopSpec edited = legacySpec(project.id());
        drafts.update(draft.id(), new LoopSpec(edited.schemaVersion(), edited.projectId(), "人工编辑后的目标",
                edited.context(), edited.stages(), edited.limits(), edited.model(), edited.sessionPolicy(),
                edited.nextAttemptPromptTemplate(), edited.budget()));

        designerSessions.pollActiveHandoffs();

        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.messages(session.id())).anyMatch(message ->
                "VALIDATOR".equals(message.actor()) && message.content().contains("DESIGNER_DRAFT_CHANGED"));
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(1);
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
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "验证三十二次模型调用硬上限");
        pollUntilPackageStates(session.id(), "COMPLETED", "DESIGNING");
        assertPackageStates(session.id(), "COMPLETED", "DESIGNING");
        DesignRequirementRevisionRow revision = mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow();
        assertThat(mapper.updateDesignRequirementRevision(new DesignRequirementRevisionRow(revision.id(),
                revision.designerSessionId(), revision.revision(), revision.sourceMessageId(),
                revision.requirementText(), revision.requirementSegmentsJson(), revision.sourceDraftVersion(),
                revision.state(), 32, revision.maxModelCalls(), revision.createdAt(), revision.updatedAt(),
                revision.version()))).isEqualTo(1);

        designerSessions.pollActiveHandoffs();

        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(32);
        assertThat(designerSessions.workPackageStatuses(session.id()).get(1).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.compilerStatus(session.id()).state()).isEqualTo("SESSION_ERROR");
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void decompositionInputAndMultiTaskBoundariesWaitWithoutCreatingTasks() throws Exception {
        for (String status : List.of("NEEDS_INPUT", "MULTI_TASK_REQUIRED")) {
            fake().reset();
            ProjectRow project = project(status.toLowerCase());
            LoopDraftRow draft = drafts.create(legacySpec(project.id()));
            fake().setDecomposerOutput(decomposition(status, "需要人工处理", 0));
            DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "包含关键歧义或多个发布边界的需求");
            designerSessions.pollActiveHandoffs();
            designerSessions.pollActiveHandoffs();
            assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
            assertThat(designerSessions.decompositionStatus(session.id()).resultType()).isEqualTo(status);
            assertThat(mapper.countTasksForProject(project.id())).isZero();
            assertThatThrownBy(() -> drafts.confirm(draft.id(), "不可确认"))
                    .hasMessageContaining("every work package complete");
        }
    }

    @Test
    void decompositionPlanningRejectsStringGapsThenGeneratesJsonFromTheFrozenPlan() throws Exception {
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

        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "明确事件分发边界");
        String decompositionSessionId = mapper.findTaskDecompositionByRevision(
                mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().id())
                .orElseThrow().externalSessionId();
        designerSessions.pollActiveHandoffs();

        DesignerSessionService.DecompositionStatus repairing = designerSessions.decompositionStatus(session.id());
        assertThat(repairing.repairCount()).isEqualTo(1);
        assertThat(repairing.workflowStep()).isEqualTo("PLANNING");
        assertThat(repairing.lastErrorCode()).isEqualTo("DECOMPOSER_PLAN_OUTPUT_INVALID");
        fake().setDecomposerPlanningOutput(decompositionPlan("NEEDS_INPUT", "需要补充异步边界"));

        designerSessions.pollActiveHandoffs();
        DesignerSessionService.DecompositionStatus generating = designerSessions.decompositionStatus(session.id());
        assertThat(generating.workflowStep()).isEqualTo("GENERATING_JSON");
        assertThat(mapper.findTaskDecompositionByRevision(
                mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow().id()).orElseThrow().planningJson())
                .contains("coverageMappings", "designGaps");

        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.decompositionStatus(session.id()).workflowStep()).isEqualTo("FINAL_JSON");
        assertThat(fake().createReadOnlySessionCalls()).isEqualTo(1);
        assertThat(fake().promptHistory().stream().filter(call -> decompositionSessionId.equals(call.sessionId())))
                .hasSize(3);
        assertThat(designerSessions.messages(session.id())).noneMatch(message ->
                message.content().contains("TASK_DECOMPOSITION_PLAN_JSON_START"));
        assertThat(mapper.listTasks()).isEmpty();
    }

    @Test
    void addedRequirementSupersedesOldRevisionAndStartsWithFreshThirtyTwoCallBudget() throws Exception {
        ProjectRow project = project("revision");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDecomposerOutput(decomposition("NEEDS_INPUT", "需要补充", 0));
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "先实现查询能力");
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();
        DesignRequirementRevisionRow first = mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow();
        assertThat(first.state()).isEqualTo("WAITING_INPUT");
        assertThat(first.modelCallsUsed()).isEqualTo(2);

        fake().setDesignerOutput(designerOutput("# 补充后的完整设计\n\n异常和验收边界完整。", legacySpec(project.id())));
        fake().setDecomposerOutput(decomposition("DIRECT_DESIGN", "补充后的完整需求", 1)
                .replace("\"RQ-1\"]", "\"RQ-1\",\"RQ-2\"]"));
        designerSessions.appendUserMessage(session.id(), "补充：失败时返回明确错误，且保持同一发布边界");
        assertThat(mapper.findDesignRequirementRevision(first.id()).orElseThrow().state()).isEqualTo("SUPERSEDED");
        assertThat(designerSessions.requirementStatus(session.id()).revision()).isEqualTo(2);
        assertThat(designerSessions.requirementStatus(session.id()).modelCallsUsed()).isEqualTo(1);
        assertThat(designerSessions.requirementStatus(session.id()).maxModelCalls()).isEqualTo(32);
        pollUntilSettled(session.id());
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("COMPLETED");
    }

    @Test
    void compilerGetsTwoRepairsWhileSemanticGapRedesignsOnlyCurrentPackage() throws Exception {
        ProjectRow project = project("retry");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        fake().setDesignerOutput(designerOutput("# 完整设计\n\n输出明确且可验收。", legacySpec(project.id())));
        fake().setCompilerOutput("<!-- LOOPSPEC_COMPILATION_JSON_START -->{}<!-- LOOPSPEC_COMPILATION_JSON_END -->");
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "实现可验收能力");
        pollUntilCompilerState(session.id(), "RUNNING", 1);
        assertThat(designerSessions.compilerStatus(session.id()).repairCount()).isEqualTo(1);
        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.compilerStatus(session.id()).repairCount()).isEqualTo(2);
        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.workPackageStatuses(session.id()).getFirst().lastErrorCode())
                .isEqualTo("COMPILER_RETRY_EXHAUSTED");

        fake().setCompilerOutput(designIncomplete("MISSING_EXCEPTION_SEMANTICS", "缺少异常结果"));
        designerSessions.retryPackageCompilation(session.id(), "WP-1");
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();
        assertThat(designerSessions.get(session.id()).workflowPhase()).isEqualTo("REDESIGNING");
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

        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "新增 Java 行为并提供聚焦单元测试");
        pollUntilCompilerState(session.id(), "RUNNING", 1);

        String compilerSessionId = designerSessions.compilerStatus(session.id()).externalSessionId();
        List<String> compilerPrompts = fake().promptHistory().stream()
                .filter(call -> compilerSessionId.equals(call.sessionId()))
                .map(io.opencode.loopper.runtime.FakeOpenCodeClient.PromptCall::prompt)
                .toList();
        assertThat(compilerPrompts).hasSize(3);
        assertThat(compilerPrompts.getFirst())
                .contains("Strict package compilation planning JSON contract")
                .contains("evidenceMappings")
                .contains("focused Maven/Gradle test argv", "testCommand", "testTargets");
        assertThat(compilerPrompts.subList(1, 3)).allSatisfy(prompt -> assertThat(prompt)
                .contains("stages[*].verifiers[*] is a VerifierSpec JSON object")
                .contains("\"command\":[\"mvn\",\"-q\",\"-Dtest=ExampleFocusedTest\",\"test\"]")
                .contains("\"criterionIds\":[\"WP-1-AC-1\"]")
                .contains("\"testTargets\":[\"ExampleFocusedTest\"]")
                .contains("verificationRuntime is null for PROCESS-only stages")
                .contains("It is never a test framework name such as", "MAVEN_JUNIT5")
                .contains("designGaps entries are never strings"));
        assertThat(compilerPrompts.get(1)).contains("Canonical COMPILED envelope for a JAVA_PRODUCTION stage")
                .contains("Put exactly one complete replacement object matching the frozen plan and machine contract here.");
        assertThat(compilerPrompts.get(2)).contains("Repair 1/2")
                .contains("do not replace an object or array with a descriptive string");
    }

    @Test
    void manualPackageRecompileReactivatesTheSameRequirementAndCanCompleteAggregation() throws Exception {
        ProjectRow project = project("manual-recompile");
        LoopDraftRow draft = drafts.create(legacySpec(project.id()));
        String design = "# 可恢复设计\n\n提供明确且可观察的结果。";
        fake().setDesignerOutput(designerOutput(design, legacySpec(project.id())));
        fake().setCompilerOutput("<!-- LOOPSPEC_COMPILATION_JSON_START -->{}<!-- LOOPSPEC_COMPILATION_JSON_END -->");
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "验证人工重新编译恢复");
        pollUntilSettled(session.id());
        assertThat(designerSessions.get(session.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(designerSessions.requirementStatus(session.id()).state()).isEqualTo("WAITING_INPUT");

        fake().setCompilerOutput(packageCompilation("WP-1", design));
        fake().setPackageCompilerPlanningOutput("WP-1", null);
        designerSessions.retryPackageCompilation(session.id(), "WP-1");
        designerSessions.pollActiveHandoffs();
        designerSessions.pollActiveHandoffs();

        assertThat(designerSessions.get(session.id()).state()).isEqualTo("COMPLETED");
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
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "仍在拆解中的需求");
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
        DesignerSessionRow session = designerSessions.create(project.id(), draft.id(), "保存完整历史");
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
                .andExpect(jsonPath("$.result.serverInfo.version").value("0.1.43")).andReturn();
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

    private ProjectRow project(String name) throws Exception {
        return projects.create(name, Files.createDirectory(temp.resolve(name)).toString());
    }

    private void pollUntilSettled(String sessionId) {
        for (int attempt = 0; attempt < 40 && "RUNNING".equals(designerSessions.get(sessionId).state()); attempt++) {
            designerSessions.pollActiveHandoffs();
        }
    }

    private void pollUntilPackageStates(String sessionId, String... states) {
        for (int attempt = 0; attempt < 40; attempt++) {
            List<String> current = designerSessions.workPackageStatuses(sessionId).stream().map(status -> status.state()).toList();
            if (current.equals(List.of(states))) return;
            designerSessions.pollActiveHandoffs();
        }
    }

    private void pollUntilCompilerState(String sessionId, String state, int repairCount) {
        for (int attempt = 0; attempt < 40; attempt++) {
            DesignerSessionService.CompilerStatus compiler = designerSessions.compilerStatus(sessionId);
            if (compiler != null && state.equals(compiler.state()) && compiler.repairCount() == repairCount) return;
            designerSessions.pollActiveHandoffs();
        }
    }

    private void assertPackageStates(String sessionId, String... states) {
        assertThat(designerSessions.workPackageStatuses(sessionId)).extracting(status -> status.state())
                .containsExactly(states);
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
