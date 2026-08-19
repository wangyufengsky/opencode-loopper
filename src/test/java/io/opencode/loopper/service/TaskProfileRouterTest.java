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
        assertThat(decision.workflowTemplate()).isEqualTo(WorkflowTemplate.FULL_PACKAGE_DESIGN);
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
        assertThat(decision.workflowTemplate()).isEqualTo(WorkflowTemplate.FULL_PACKAGE_DESIGN);
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

        assertThat(decision.workflowTemplate()).isEqualTo(WorkflowTemplate.FULL_PACKAGE_DESIGN);
        assertThat(decision.evidence()).contains("requirement-tests=required",
                "ai-router-intent=SOFTWARE_CHANGE", "ai-router-signal=explicit reusable script");
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
}
