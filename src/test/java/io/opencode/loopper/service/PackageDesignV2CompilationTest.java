package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class PackageDesignV2CompilationTest {
    final ObjectMapper json = new ObjectMapper();
    final PackageDesignCompilation compiler = new DeterministicPackageDesignCompilation(json);
    final PackageDesignCompilationTest fixture = new PackageDesignCompilationTest();

    PackageDesignCompilation.Input input(String text) {
        var old = fixture.input();
        return new PackageDesignCompilation.Input(old.workPackage(), text, old.role(), old.scopeIn(), old.scopeOut(),
                old.deliverables(), 6, true);
    }
    ObjectNode candidate() {
        var root = (ObjectNode) json.readTree(fixture.readyCandidate());
        root.put("contractVersion", "PACKAGE_DESIGN_V2");
        root.set("sourceBindings", json.valueToTree(List.of(new PackageDesignV2Document.SourceBinding("B1",
                List.of("REQ-1", "SC-1", "DEL-1"), List.of("REQ-L001")))));
        root.set("relations", json.createArrayNode()); root.set("gapClaims", json.createArrayNode());
        return root;
    }
    @Test void removedBehaviorExtensionCannotBeSilentlyAcceptedAsOrdinaryV2() {
        var root = candidate(); root.set("behaviorBranches", json.createArrayNode());
        var result = compiler.compileCandidate(input("工作包范围：`src/test/java/example/EventBusTest.java`。补充 EventBusTest 聚焦测试。"), json.writeValueAsString(root));
        assertThat(result.accepted()).isFalse();
        assertThat(result.problems()).anySatisfy(problem -> {
            assertThat(problem.code()).isEqualTo("CANDIDATE_FIELD_UNKNOWN");
            assertThat(problem.pointer()).isEqualTo("/behaviorBranches");
        });
    }
    @Test void frozenExactScopeResolvesV2AmbiguityButLeavesV1AndNegativeConflictsUntouched() {
        String original = "工作包范围：`src/test/java/example/EventBusTest.java`。补充 EventBusTest 聚焦测试。";
        var v1 = compiler.compileCandidate(input(original), fixture.readyCandidate());
        assertThat(v1.accepted()).isFalse();
        var v2 = compiler.compileCandidate(input(original), json.writeValueAsString(candidate()));
        assertThat(v2.problems()).isEmpty();
        assertThat(v2.accepted()).isTrue();
        assertThat(v2.canonicalCandidateJson()).contains("PACKAGE_DESIGN_V2", "sourceBindings");
        assertThat(v2.compiledResultJson()).contains("REQ-L001", "sha256=", original.replace("\"", "\\\""));
        var forbidden = compiler.compileCandidate(input("修改 `src/test/java/example/EventBusTest.java`；不得修改 `src/test/java/example/EventBusTest.java`。"), json.writeValueAsString(candidate()));
        assertThat(forbidden.accepted()).isFalse(); assertThat(forbidden.retryable()).isFalse();
    }
    @Test void manyFrozenSourcesRemainInHandoffWithoutOverflowingEveryJudgeRubric() {
        String original = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "保留既有约束 " + i).collect(java.util.stream.Collectors.joining("\n"));
        var root = candidate();
        root.set("sourceBindings", json.valueToTree(List.of(new PackageDesignV2Document.SourceBinding("B1",
                List.of("REQ-1", "SC-1", "DEL-1"), List.copyOf(PackageRequirementSources.index(original).keySet())))));
        var result = compiler.compileCandidate(input(original), json.writeValueAsString(root));
        assertThat(result.problems()).isEmpty(); assertThat(result.accepted()).isTrue();
        assertThat(result.compiledPlan().handoffSummary()).contains("REQ-L100", "保留既有约束 99");
        assertThat(result.compiledPlan().evidenceMappings()).allSatisfy(mapping -> assertThat(mapping.judgeRubric().length()).isLessThanOrEqualTo(4000));
    }

    @Test void missingAndForgedSourcesAreRepairableWithoutPartialAcceptance() {
        var root = candidate(); root.set("sourceBindings", json.createArrayNode());
        var missing = compiler.compileCandidate(input("明确原始行为"), json.writeValueAsString(root));
        assertThat(missing.retryable()).isTrue(); assertThat(missing.problems()).extracting(PackageDesignCompilation.Problem::code).contains("PACKAGE_SOURCE_COVERAGE");
        root = candidate(); ((ObjectNode) root.path("sourceBindings").get(0)).set("sourceRefs", json.valueToTree(List.of("REQ-L999")));
        assertThat(compiler.compileCandidate(input("明确行为"), json.writeValueAsString(root)).accepted()).isFalse();
    }
    @Test void modelGapCodeIsNotProofButExplicitOriginalBusinessChoiceBlocksEvenReady() {
        var root = candidate(); root.put("outcome", "NEEDS_INPUT"); root.set("gapCodes", json.valueToTree(List.of("MISSING_EXCEPTION_SEMANTICS")));
        root.set("gapClaims", json.valueToTree(List.of(new PackageDesignV2Document.GapClaim("G1", "MISSING_EXCEPTION_SEMANTICS", List.of("REQ-L001"), "模型认为有缺口", List.of()))));
        var unconfirmed = compiler.compileCandidate(input("输入无效时返回拒绝，不改变状态。"), json.writeValueAsString(root));
        assertThat(unconfirmed.retryable()).isTrue();
        assertThat(unconfirmed.problems()).extracting(PackageDesignCompilation.Problem::code).contains("PACKAGE_GAP_UNCONFIRMED");
        var decision = compiler.compileCandidate(input("失败时继续还是回滚尚未决定。"), json.writeValueAsString(candidate()));
        assertThat(decision.retryable()).isFalse(); assertThat(decision.problems()).extracting(PackageDesignCompilation.Problem::code).contains("PACKAGE_GAP_BUSINESS_DECISION");
    }
    @Test void explicitSourceAddressedUserDecisionResolvesOnlyItsFrozenChoice() {
        var choices = PackageDesignConfirmedDecisions.parse("REQ-L001=失败时回滚\nREQ-L999=无关\nREQ-L002=尚未决定", Set.of("REQ-L001", "REQ-L002"));
        assertThat(choices).containsOnlyKeys("REQ-L001");
        var confirmed = input("失败时继续还是回滚尚未决定。").withDecisions(choices);
        assertThat(compiler.compileCandidate(confirmed, json.writeValueAsString(candidate())).problems())
                .extracting(PackageDesignCompilation.Problem::code).doesNotContain("PACKAGE_GAP_BUSINESS_DECISION");
        var unrelated = input("失败时继续还是回滚尚未决定。审计保存还是丢弃尚未决定。").withDecisions(choices);
        assertThat(compiler.compileCandidate(unrelated, json.writeValueAsString(candidate())).accepted()).isFalse();
        var dangerous = confirmed.withDecisions(java.util.Map.of("REQ-L001", "删除 `src/test/java/example/EventBusTest.java`"));
        assertThat(compiler.compileCandidate(dangerous, json.writeValueAsString(candidate())).accepted()).isFalse();
    }

    @Test void frozenDeletionAndExternalReleaseConflictsAreClassifiedBeforeModelGapClaims() {
        for (String original : List.of("删除 `src/test/java/example/EventBusTest.java`。",
                "禁止推送和发布。任务要求创建 GitHub Release。",
                "只允许只读分析，禁止外部写入。任务要求发送报告到外部 CRM。")) {
            var result = compiler.compileCandidate(input(original), json.writeValueAsString(candidate()));
            assertThat(result.accepted()).as(original + " " + result.problems()).isFalse();
            assertThat(result.retryable()).as(original + " " + result.problems()).isFalse();
            assertThat(result.problems()).extracting(PackageDesignCompilation.Problem::code).contains("PACKAGE_GAP_PROVEN_CONFLICT");
        }
        assertThat(PackageFrozenSafety.externalConflict("禁止推送。不要创建 GitHub Release。发布进程内领域事件。")).isFalse();
        assertThat(PackageFrozenSafety.externalConflict("禁止外部写入。发布器仅在进程内同步发布业务事件并按类型分发。")).isFalse();
    }

    @Test void malformedCollectionsAndServerFieldsNeverCrashOrGainAuthority() {
        for (String value : List.of("", "null", "{", "[]", json.writeValueAsString(candidate()).replace("\"gapClaims\":[]", "\"gapClaims\":[null]")))
            assertThatCode(() -> compiler.compileCandidate(input("原文"), value)).doesNotThrowAnyException();
        var root = candidate(); root.put("allowedPaths", "private");
        var result = compiler.compileCandidate(input("原文"), json.writeValueAsString(root));
        assertThat(result.retryable()).isFalse(); assertThat(result.problems()).extracting(PackageDesignCompilation.Problem::problemClass).contains(PackageDesignCompilation.ProblemClass.SECURITY);
    }
    @Test void promptExampleUsesProductionSchemaAndCodec() {
        var decoded = new PackageDesignV2Codec(json).decode(input("示例来源"), PackageDesignV2Prompt.example());
        assertThat(decoded.problems()).isEmpty();
    }
    @Test void anyAndUnlessKeepEveryLeafAndRejectCyclesDepthAndNodeOverflow() {
        var validator = new PackageSemanticRelations();
        var any = new PackageSemanticRelations.Relation("OR", "any", List.of("A", "B"), List.of("REQ-L001"));
        var unless = new PackageSemanticRelations.Relation("EX", "unless", List.of("OR", "C"), List.of("REQ-L001"));
        var valid = validator.validate(List.of(any, unless), Set.of("A", "B", "C"), Set.of("REQ-L001"));
        assertThat(valid.valid()).isTrue(); assertThat(valid.branchScenarios().get("EX")).containsExactly("A", "B", "C");
        var cycle = new PackageSemanticRelations.Relation("OR", "all", List.of("EX", "A"), List.of("REQ-L001"));
        assertThat(validator.validate(List.of(cycle, unless), Set.of("A", "B", "C"), Set.of("REQ-L001")).issues()).extracting(PackageSemanticRelations.Issue::code).contains("PACKAGE_RELATION_CYCLE");
        var nested = new java.util.ArrayList<PackageSemanticRelations.Relation>();
        for (int i = 0; i < 5; i++) nested.add(new PackageSemanticRelations.Relation("R"+i, "all", List.of(i == 4 ? "B" : "R"+(i+1), "A"), List.of("REQ-L001")));
        assertThat(validator.validate(nested, Set.of("A", "B"), Set.of("REQ-L001")).issues()).extracting(PackageSemanticRelations.Issue::code).contains("PACKAGE_RELATION_DEPTH");
        assertThat(validator.validate(java.util.Collections.nCopies(33, any), Set.of("A", "B"), Set.of("REQ-L001")).issues()).singleElement().extracting(PackageSemanticRelations.Issue::code).isEqualTo("PACKAGE_RELATION_LIMIT");
    }
}
