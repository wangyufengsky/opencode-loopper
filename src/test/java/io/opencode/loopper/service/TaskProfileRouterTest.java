package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.domain.WorkflowTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class TaskProfileRouterTest {
    @TempDir Path root;
    private final TaskProfileRouter router = new TaskProfileRouter();

    @Test void normalizesModelTestArtifactAliasesWithoutChangingTheTaskIntent() {
        assertThat(TaskSemanticRouter.artifactKind("TEST_CODE")).isEqualTo(ArtifactKind.SOURCE_CODE);
        assertThat(TaskSemanticRouter.artifactKind("TEST_SOURCE_CODE")).isEqualTo(ArtifactKind.SOURCE_CODE);
        assertThat(TaskSemanticRouter.artifactKind("unit-tests")).isEqualTo(ArtifactKind.SOURCE_CODE);
        assertThat(TaskSemanticRouter.artifactKind("python test")).isEqualTo(ArtifactKind.PYTHON_SCRIPT);
    }

    @Test void routesPythonScriptWithoutJavaAssumptions() throws Exception {
        Files.writeString(root.resolve("pyproject.toml"), "[tool.pytest.ini_options]\n");
        Files.writeString(root.resolve("test_converter.py"), "def test_convert(): pass\n");
        TaskProfileRouter.Decision decision = router.route(root, "开发一个可复用的 Python Excel 转换脚本");
        assertThat(decision.intent()).isEqualTo(TaskIntent.SOFTWARE_CHANGE);
        assertThat(decision.workflowTemplate()).isEqualTo(WorkflowTemplate.DIRECT_SOFTWARE_DESIGN);
        assertThat(decision.technologies()).contains("python");
        assertThat(decision.decisionRequired()).isFalse();
        RolePackRegistry.RolePack pack = new RolePackRegistry().resolve(decision.intent(),
                decision.technologies(), decision.artifactKinds());
        assertThat(pack.id()).isEqualTo("software-python");
        assertThat(new RolePromptComposer().compilerInstructions(pack, decision.technologies(), TestPolicy.REQUIRED))
                .contains("pytest").doesNotContain("Maven");
    }

    @Test void reusablePythonConverterWinsOverItsMarkdownOutputArtifact() {
        TaskProfileRouter.Decision decision = router.route(root,
                "编写一个可复用的 Python 命令行脚本 convert_csv.py：接收输入 CSV 和输出 Markdown 路径");

        assertThat(decision.intent()).isEqualTo(TaskIntent.SOFTWARE_CHANGE);
        assertThat(decision.workflowTemplate()).isEqualTo(WorkflowTemplate.DIRECT_SOFTWARE_DESIGN);
        assertThat(decision.technologies()).contains("python");
        assertThat(decision.artifactKinds()).containsExactly(io.opencode.loopper.domain.ArtifactKind.PYTHON_SCRIPT);
        assertThat(new RolePackRegistry().resolve(decision.intent(), decision.technologies(),
                decision.artifactKinds()).id()).isEqualTo("software-python");
    }

    @Test void oneOffExcelConversionUsesServerArtifactFlowWithoutTests() {
        TaskProfileRouter.Decision decision = router.route(root, "把 report.xlsx 一次性转换成 Markdown");
        assertThat(decision.intent()).isEqualTo(TaskIntent.DATA_CONVERSION);
        RolePackRegistry.RolePack pack = new RolePackRegistry().resolve(decision.intent(),
                decision.technologies(), decision.artifactKinds());
        assertThat(pack.executionStrategy()).isEqualTo(ExecutionStrategy.SERVER_TABULAR_CONVERSION);
        assertThat(pack.defaultTestPolicy()).isEqualTo(TestPolicy.NOT_APPLICABLE);
    }

    @Test void nodeRepositoryOnlyRequiresTestsWhenItsManifestDeclaresATestScript() throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"build\":\"vite build\"}}");
        TaskProfileRouter.Decision withoutTests = router.route(root, "修改 Vue 页面交互");
        RolePackRegistry.RolePack optional = new RolePackRegistry().resolve(withoutTests.intent(),
                withoutTests.technologies(), withoutTests.artifactKinds());
        assertThat(optional.defaultTestPolicy()).isEqualTo(TestPolicy.OPTIONAL);

        Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"test\":\"vitest\"}}");
        assertThat(router.route(root, "修改 Vue 页面交互").evidence()).contains("test-framework=npm");
    }

    @Test void unknownSingleStackManifestUsesGenericSoftwarePackInsteadOfJava() throws Exception {
        Files.writeString(root.resolve("go.mod"), "module example.com/listener\n\ngo 1.23\n");

        TaskProfileRouter.Decision decision = router.route(root, "开发 Go 事件监听器");
        RolePackRegistry.RolePack pack = new RolePackRegistry().resolve(decision.intent(),
                decision.technologies(), decision.artifactKinds());

        assertThat(decision.technologies()).containsExactly("go");
        assertThat(pack.id()).isEqualTo("software-generic");
        assertThat(pack.defaultTestPolicy()).isEqualTo(TestPolicy.OPTIONAL);
    }

    @Test void ambiguousOrUnsafeMaintenanceRequiresHumanDecision() {
        TaskProfileRouter.Decision decision = router.route(root, "日常维护，删除文件并重启服务后推送");
        assertThat(decision.decisionRequired()).isTrue();
        assertThat(decision.confidence()).isLessThan(80);
        assertThat(decision.evidence()).contains("unsafe-operation-conflict");
    }

    @Test void eventPublishingAndReleaseBoundariesAreNotExternalReleaseOperations() {
        assertThat(router.route(root, "新增事件发布能力并划分多个发布边界").evidence())
                .doesNotContain("unsafe-operation-conflict");
        assertThat(router.route(root,
                "失败时发布包含调用身份、失败节点、错误分类和原始 cause 的领域事件").evidence())
                .doesNotContain("unsafe-operation-conflict");
        assertThat(router.route(root, "发布携带版本号的状态变更事件").evidence())
                .doesNotContain("unsafe-operation-conflict");
        assertThat(router.route(root, "提交代码并发布版本").evidence())
                .contains("unsafe-operation-conflict");
        assertThat(router.route(root, "发布领域事件，并发布新版本").evidence())
                .contains("unsafe-operation-conflict");
    }

    @Test void combinesAiSemanticsWithServerOwnedWorkflowAndExplicitTestPolicyEvidence() {
        TaskProfileRouter.SemanticLabels labels = new TaskProfileRouter.SemanticLabels(
                TaskIntent.SOFTWARE_CHANGE, java.util.List.of(ArtifactKind.PYTHON_SCRIPT),
                java.util.List.of("python"), "SIMPLE", 96, java.util.List.of("explicit reusable script"));

        TaskProfileRouter.Decision decision = router.route(root,
                "编写 Python 脚本并且必须测试", labels);

        assertThat(decision.workflowTemplate()).isEqualTo(WorkflowTemplate.DIRECT_SOFTWARE_DESIGN);
        assertThat(decision.evidence()).contains("requirement-tests=required",
                "ai-router-intent=SOFTWARE_CHANGE", "ai-router-signal=explicit reusable script");
    }

    @Test void packagedAiComplexityOnlyRecommendsLargeModeAndStillDefaultsToOnePackage() {
        TaskProfileRouter.SemanticLabels labels = new TaskProfileRouter.SemanticLabels(
                TaskIntent.SOFTWARE_CHANGE, java.util.List.of(ArtifactKind.SOURCE_CODE),
                java.util.List.of("java"), "PACKAGED", 96, java.util.List.of("broad change"));

        TaskProfileRouter.Decision decision = router.route(root, "实现跨模块软件能力", labels);

        assertThat(decision.workflowTemplate()).isEqualTo(WorkflowTemplate.DIRECT_SOFTWARE_DESIGN);
        assertThat(decision.evidence()).contains("large-task-recommended", "ai-router-complexity=PACKAGED");
    }

    @Test void semanticConflictCannotSilentlyOverrideStrongRepositoryAndRequirementEvidence() {
        TaskProfileRouter.SemanticLabels labels = new TaskProfileRouter.SemanticLabels(
                TaskIntent.DOCUMENT_AUTHORING, java.util.List.of(ArtifactKind.MARKDOWN),
                java.util.List.of(), "SIMPLE", 95, java.util.List.of("ambiguous wording"));

        TaskProfileRouter.Decision decision = router.route(root,
                "把 report.xlsx 一次性转换成 Markdown", labels);

        assertThat(decision.decisionRequired()).isTrue();
        assertThat(decision.confidence()).isLessThan(80);
        assertThat(decision.evidence()).contains("router-evidence-conflict=DATA_CONVERSION");
    }

    @Test void structuredMultiChapterSnapshotKeepsPackagedDocumentWorkflowOnReroute() {
        TaskProfileRouter.SemanticLabels labels = new TaskProfileRouter.SemanticLabels(
                TaskIntent.DOCUMENT_AUTHORING, java.util.List.of(ArtifactKind.MARKDOWN),
                java.util.List.of(), "SIMPLE", 93, java.util.List.of("document"));

        TaskProfileRouter.Decision decision = router.route(root,
                "# 手册\n\n## 安装\n正文\n\n## 运维\n正文", labels);

        assertThat(decision.workflowTemplate()).isEqualTo(WorkflowTemplate.PACKAGED_ARTIFACT);
    }

    @Test void incompatibleReviewerArtifactAndMixedMutationRequireHumanDecision() {
        TaskProfileRouter.SemanticLabels labels = new TaskProfileRouter.SemanticLabels(
                TaskIntent.READ_ONLY_REVIEW, java.util.List.of(ArtifactKind.SOURCE_CODE),
                java.util.List.of("java"), "SIMPLE", 98, java.util.List.of("review"));

        TaskProfileRouter.Decision decision = router.route(root, "评审当前代码并直接修复发现的问题", labels);

        assertThat(decision.decisionRequired()).isTrue();
        assertThat(decision.evidence()).contains("mixed-mutation-conflict", "router-artifact-conflict=READ_ONLY_REVIEW");
    }

    @Test void reviewVocabularyInsideWritableDesignDoesNotTurnTheTaskIntoAReadOnlyReview() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project />");
        String assembledSnapshot = """
                ## 目标
                新增调用身份、节点执行轨迹与领域事件发布能力，并补充 JUnit 5 聚焦测试。
                轨迹应当可通过 ChainContext 只读获取，每个节点记录轨迹并发布/可观测。
                ## 人工复核点
                如需新增错误码则进行人工评审；不得修改既有 XML 转换成功路径。
                """;
        TaskProfileRouter.SemanticLabels labels = new TaskProfileRouter.SemanticLabels(
                TaskIntent.SOFTWARE_CHANGE, java.util.List.of(ArtifactKind.SOURCE_CODE),
                java.util.List.of("java"), "PACKAGED", 96, java.util.List.of("writable software design"));

        TaskProfileRouter.Decision deterministic = router.route(root, assembledSnapshot);
        TaskProfileRouter.Decision semantic = router.route(root, assembledSnapshot, labels);

        assertThat(deterministic.intent()).isEqualTo(TaskIntent.SOFTWARE_CHANGE);
        assertThat(deterministic.evidence()).doesNotContain("unsafe-operation-conflict", "mixed-mutation-conflict");
        assertThat(semantic.intent()).isEqualTo(TaskIntent.SOFTWARE_CHANGE);
        assertThat(semantic.evidence()).doesNotContain("unsafe-operation-conflict", "mixed-mutation-conflict");
    }

    @Test void explicitReadOnlyAndMixedReviewRequestsStillKeepTheirSafetyBoundary() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project />");

        TaskProfileRouter.Decision readOnly = router.route(root, "只读评审当前 Java 代码并输出证据报告");
        TaskProfileRouter.Decision mixed = router.route(root, "评审当前代码并直接修复发现的问题");

        assertThat(readOnly.intent()).isEqualTo(TaskIntent.READ_ONLY_REVIEW);
        assertThat(readOnly.evidence()).doesNotContain("mixed-mutation-conflict");
        assertThat(mixed.intent()).isEqualTo(TaskIntent.READ_ONLY_REVIEW);
        assertThat(mixed.evidence()).contains("mixed-mutation-conflict");
    }

    @Test void maintenanceProhibitionsAreNotMisreadAsRequestsForUnsafeOperations() {
        TaskProfileRouter.Decision decision = router.route(root,
                "本地配置维护：只修改 settings.yml，不删除文件、不操作服务、不提交推送或发布");

        assertThat(decision.intent()).isEqualTo(TaskIntent.LOCAL_MAINTENANCE);
        assertThat(decision.decisionRequired()).isFalse();
        assertThat(decision.evidence()).doesNotContain("unsafe-operation-conflict");
    }

    @Test void configurableSoftwareAndNegativeDependencyBoundariesAreNotLocalMaintenance() {
        TaskProfileRouter.Decision software = router.route(root,
                "新增显式可配置的补偿节点，不新增第三方依赖，并为每个工作包编写 JUnit 5 测试");
        TaskProfileRouter.Decision responsibilityDescription = router.route(root, """
                新增责任链执行治理与 JUnit 5 聚焦测试。
                - BusinessChainExecutor（调用入口，维护 tradeSeq/MDC）。
                - 引入可配置的补偿节点，不新增第三方依赖。
                """);
        TaskProfileRouter.Decision maintenance = router.route(root, "本地配置维护：更新 application.yml 配置项");
        TaskProfileRouter.Decision assembledMaintenance = router.route(root,
                "初始需求：本地配置维护：只修改 `settings.yml`");
        TaskProfileRouter.Decision generatedMaintenance = router.route(root,
                "# 安全维护\n\n只修改 `settings.yml`，把 enabled 调整为 true");

        assertThat(software.intent()).isEqualTo(TaskIntent.SOFTWARE_CHANGE);
        assertThat(responsibilityDescription.intent()).isEqualTo(TaskIntent.SOFTWARE_CHANGE);
        assertThat(maintenance.intent()).isEqualTo(TaskIntent.LOCAL_MAINTENANCE);
        assertThat(assembledMaintenance.intent()).isEqualTo(TaskIntent.LOCAL_MAINTENANCE);
        assertThat(generatedMaintenance.intent()).isEqualTo(TaskIntent.LOCAL_MAINTENANCE);
    }

    @Test void componentEvidenceSelectsJavaNodeAndRealMixedTasksWithoutFlatUnion() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Path frontend = Files.createDirectory(root.resolve("frontend"));
        Files.writeString(frontend.resolve("package.json"), "{\"scripts\":{\"test\":\"vitest\"}}");

        TaskProfileRouter.Decision javaTask = router.route(root, "在 Java 服务中新增责任链单元测试");
        TaskProfileRouter.Decision nodeTask = router.route(root, "修改 frontend/ 的 Vue 交互");
        TaskProfileRouter.Decision mixedTask = router.route(root, "同时修改 Java API 与 frontend/ Vue 页面");
        TaskProfileRouter.Decision ambiguous = router.route(root, "优化公共业务流程");

        assertThat(javaTask.technologies()).containsExactly("java");
        assertThat(nodeTask.technologies()).containsExactly("node");
        assertThat(mixedTask.technologies()).containsExactly("java", "node");
        assertThat(ambiguous.technologies()).isEmpty();
        assertThat(ambiguous.decisionRequired()).isTrue();
        assertThat(ambiguous.evidence()).contains("component-selection-ambiguous");
    }

    @Test void emptyRepositoryUsesGenericConfirmationInsteadOfImplicitJava() {
        TaskProfileRouter.Decision decision = router.route(root, "新增业务能力");

        assertThat(decision.technologies()).isEmpty();
        assertThat(decision.decisionRequired()).isTrue();
        assertThat(new RolePackRegistry().resolve(decision.intent(), decision.technologies(),
                decision.artifactKinds()).id()).isEqualTo("software-generic");
    }

    @Test void aiTechnologyLabelsCannotInventAStackForAnEmptyRepository() {
        TaskProfileRouter.SemanticLabels labels = new TaskProfileRouter.SemanticLabels(
                TaskIntent.SOFTWARE_CHANGE, java.util.List.of(ArtifactKind.SOURCE_CODE),
                java.util.List.of("java"), "SIMPLE", 99, java.util.List.of("model guessed java"));

        TaskProfileRouter.Decision decision = router.route(root, "新增业务能力", labels);

        assertThat(decision.technologies()).isEmpty();
        assertThat(decision.decisionRequired()).isTrue();
        assertThat(new RolePackRegistry().resolve(decision.intent(), decision.technologies(),
                decision.artifactKinds()).id()).isEqualTo("software-generic");
    }

    @Test
    @EnabledIfSystemProperty(named = "loopper.cupxml2java.root", matches = ".+")
    void cupXml2JavaResponsibilityChainTestRoutesFromRealMavenEvidence() {
        Path cupXml2Java = Path.of(System.getProperty("loopper.cupxml2java.root"));

        TaskProfileRouter.Decision decision = router.route(cupXml2Java, "新增责任链单元测试");

        assertThat(decision.technologies()).containsExactly("java");
        assertThat(decision.componentKeys()).hasSize(1);
        assertThat(decision.decisionRequired()).isFalse();
        assertThat(new RolePackRegistry().resolve(decision.intent(), decision.technologies(),
                decision.artifactKinds()).id()).isEqualTo("software-java");
    }
}
