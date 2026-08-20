package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.domain.WorkflowTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class TaskProfileRouterTest {
    @TempDir Path root;
    private final TaskProfileRouter router = new TaskProfileRouter();

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
        assertThat(router.route(root, "提交代码并发布版本").evidence())
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

    @Test void maintenanceProhibitionsAreNotMisreadAsRequestsForUnsafeOperations() {
        TaskProfileRouter.Decision decision = router.route(root,
                "本地配置维护：只修改 settings.yml，不删除文件、不操作服务、不提交推送或发布");

        assertThat(decision.intent()).isEqualTo(TaskIntent.LOCAL_MAINTENANCE);
        assertThat(decision.decisionRequired()).isFalse();
        assertThat(decision.evidence()).doesNotContain("unsafe-operation-conflict");
    }
}
