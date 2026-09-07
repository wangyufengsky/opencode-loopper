package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.service.PackageDesignCompilation.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class PackageDesignV2SafetyTest {
    final ObjectMapper json = new ObjectMapper();
    final PackageDesignV2CompilationTest fixture = new PackageDesignV2CompilationTest();

    @Test void realAcceptedConflictCandidatesCannotHideDeletionInAnyOrMoveADeicticFile() throws Exception {
        for (String id : List.of("forbidden_deletion-2", "forbidden_deletion-3")) {
            var data = json.readTree(Files.readString(Path.of("src/test/resources/package-design-v2-regressions/" + id + ".json")));
            var original = data.path("fixture").path("requirement").asText();
            var old = fixture.input(original);
            var input = new Input(old.workPackage(), original, old.role(),
                    List.of(data.path("fixture").path("target").asText()), List.of(), List.of("RetentionPolicyTest"), 6, true);
            var result = fixture.compiler.compileCandidate(input, json.writeValueAsString(data.path("candidate")));
            assertThat(result.outcome()).as(id).isEqualTo(Outcome.NEEDS_INPUT);
            assertThat(result.problems()).extracting(Problem::code).contains("PACKAGE_GAP_PROVEN_CONFLICT");
            assertThat(result.compiledPlan()).isNull();
        }
    }

    @Test void fileRemovalRecognizesTaskActionsButPreservesNegationAndBusinessBehavior() {
        for (String request : List.of("需求同时要求删除全部现有测试以避免失败，包括目标文件。",
                "禁止移动文件；本次要求将该文件移动到仓库之外。", "删除所有文件。", "请移除这些测试文件。"))
            assertThat(PackageFrozenSafety.fileRemovalRequests(request)).as(request).isNotEmpty();
        for (String allowed : List.of("禁止删除全部测试。", "不要将该文件移动到仓库之外。",
                "实现删除文件功能并补充测试。", "当用户删除文件时应删除业务索引。",
                "测试移动文件的行为。", "例如删除文件。", "保留现有文件不变。",
                "新增删除文件事件监听器。"))
            assertThat(PackageFrozenSafety.fileRemovalRequests(allowed)).as(allowed).isEmpty();
    }

    @Test void candidateDeliverableCannotAddAnUnauthorizedPathEvenWithValidSourceBindings() {
        var candidate = fixture.candidate();
        ObjectNode added = ((ObjectNode) candidate.path("deliverables").get(0)).deepCopy();
        added.put("key", "DEL-OTHER"); added.put("target", "repository-external/EventBusTest.java");
        ((tools.jackson.databind.node.ArrayNode) candidate.path("deliverables")).add(added);
        ((tools.jackson.databind.node.ArrayNode) candidate.path("stages").get(0).path("includes")).add("DEL-OTHER");
        var result = fixture.compiler.compileCandidate(fixture.input("补充事件安全测试。"), json.writeValueAsString(candidate));
        assertThat(result.outcome()).isEqualTo(Outcome.NEEDS_INPUT);
        assertThat(result.problems()).extracting(Problem::code).contains("PACKAGE_COMPILED_SCOPE_EXPANSION");
        assertThat(result.compiledPlan()).isNull();
    }

    @Test void finalScopeGuardIndependentlyChecksStagesAndVerifiersIncludingFilePrefixAndGlob() {
        var input = fixture.input("补充事件安全测试。").withContract("PACKAGE_DESIGN_V2");
        var result = fixture.compiler.compileCandidate(input, json.writeValueAsString(fixture.candidate()));
        assertThat(result.accepted()).isTrue();
        for (String pointer : List.of("/stages/0/allowedPaths", "/stages/0/verifiers/0/allowedPaths")) {
            for (String outside : List.of("src/**", "private/secret.java", "src/test/java/../private.java", input.scopeIn().getFirst() + "/child.java")) {
                var plan = (ObjectNode) json.valueToTree(result.compiledPlan());
                if (pointer.contains("verifiers")) {
                    var verifiers = plan.path("stages").get(0).path("verifiers");
                    for (var verifier : verifiers) if (verifier.path("type").asText().equals("GIT_DIFF"))
                        ((ObjectNode) verifier).set("allowedPaths", json.valueToTree(List.of(outside)));
                } else ((ObjectNode) plan.path("stages").get(0)).set("allowedPaths", json.valueToTree(List.of(outside)));
                var typed = json.treeToValue(plan, DesignerSemanticContracts.PackageCompilationPlanEnvelope.class);
                assertThat(PackageDesignScopeGuard.validate(input, typed)).as(pointer + outside)
                        .extracting(Problem::code).contains("PACKAGE_COMPILED_SCOPE_EXPANSION");
            }
        }
        var broad = new Input(input.workPackage(), input.requirementText(), input.role(),
                List.of("src/test/java/example/*Test.java"), List.of(), input.deliverables(), 6, true, "PACKAGE_DESIGN_V2");
        assertThat(PackageDesignScopeGuard.validate(broad, result.compiledPlan())).isEmpty();
    }

    @Test void normalDirectPackageUsesFrozenRoleFallbackWithoutLettingCandidateExpandIt() {
        var old = fixture.input("补充事件安全测试。");
        var input = new Input(old.workPackage(), old.requirementText(), old.role(),
                List.of("当前完整软件需求"), List.of(), List.of("完成当前需求的软件变更"), 6, true, "PACKAGE_DESIGN_V2");
        var good = fixture.compiler.compileCandidate(input, json.writeValueAsString(fixture.candidate()));
        assertThat(good.problems()).isEmpty();
        assertThat(good.accepted()).isTrue();
        var outside = fixture.candidate();
        ((ObjectNode) outside.path("deliverables").get(0)).put("target", "private/EventBusTest.java");
        var bad = fixture.compiler.compileCandidate(input, json.writeValueAsString(outside));
        assertThat(bad.problems()).extracting(Problem::code).contains("PACKAGE_COMPILED_SCOPE_EXPANSION");
    }

    @Test void safetyLoweringDoesNotTrustACompilerResultWithDeletionProtectionRemoved() {
        var input = fixture.input("补充事件安全测试。").withContract("PACKAGE_DESIGN_V2");
        var result = fixture.compiler.compileCandidate(input, json.writeValueAsString(fixture.candidate()));
        var tree = (ObjectNode) json.valueToTree(result.compiledPlan());
        for (var verifier : tree.path("stages").get(0).path("verifiers"))
            if (verifier.path("type").asText().equals("GIT_DIFF")) ((ObjectNode) verifier).put("forbidDeletes", false);
        assertThat(PackageDesignScopeGuard.validate(input, json.treeToValue(tree,
                DesignerSemanticContracts.PackageCompilationPlanEnvelope.class)))
                .extracting(Problem::code).contains("PACKAGE_COMPILED_DELETE_PERMISSION");
        ((ObjectNode) tree.path("stages").get(0)).set("forbiddenPaths", json.createArrayNode());
        assertThat(PackageDesignScopeGuard.validate(input, json.treeToValue(tree,
                DesignerSemanticContracts.PackageCompilationPlanEnvelope.class)))
                .extracting(Problem::code).contains("PACKAGE_COMPILED_FORBIDDEN_SCOPE_LOST");
        ((ObjectNode) tree.path("stages").get(0)).set("verifiers", json.createArrayNode());
        assertThat(PackageDesignScopeGuard.validate(input, json.treeToValue(tree,
                DesignerSemanticContracts.PackageCompilationPlanEnvelope.class)))
                .extracting(Problem::code).contains("PACKAGE_COMPILED_SCOPE_VERIFIER_MISSING");
    }
}
