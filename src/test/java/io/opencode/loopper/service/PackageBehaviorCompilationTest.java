package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageBehaviorContract.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class PackageBehaviorCompilationTest {
    final ObjectMapper json = new ObjectMapper();
    final PackageDesignV2CompilationTest fixture = new PackageDesignV2CompilationTest();
    final PackageDesignCompilation compiler = new DeterministicPackageDesignCompilation(json);
    PackageDesignCompilation.Input input() { return fixture.input("未注册事件应忽略。工作包范围：`src/test/java/example/EventBusTest.java`。新增 EventBusTest 聚焦测试。").withBehavior(book()); }
    PackageBehaviorContract book() { return new PackageBehaviorContract(VERSION,
            List.of(new Variable("registered", "BOOLEAN", "INPUT", List.of("false", "true"), List.of("REQ-L001")),
                    new Variable("result", "ENUM", "OUTPUT", List.of("IGNORED", "DISPATCHED"), List.of("REQ-L001"))),
            List.of(new Obligation("IGNORE", List.of("REQ-L001"), "publish", Expr.eq("registered", "false"), Map.of("result", "IGNORED"))), List.of(), List.of()); }
    ObjectNode candidate() { var root = fixture.candidate();
        root.set("behaviorBranches", json.valueToTree(List.of(new Branch("SC-1", List.of("IGNORE"), "publish", Expr.eq("registered", "false"), Map.of("result", "IGNORED"))))); return root; }
    @Test void productionCompilerGeneratesObservableContractAndCanonicalReplayIsStable() {
        var root = candidate(); ((ObjectNode) root.path("scenarios").get(0)).put("observableResult", "这段候选建议不得覆盖结构化结果");
        var result = compiler.compileCandidate(input(), json.writeValueAsString(root));
        assertThat(result.problems()).as(result.toString()).isEmpty();
        assertThat(result.canonicalCandidateJson()).contains("result = IGNORED").doesNotContain("这段候选建议");
        assertThat(result.compiledResultJson()).contains("PACKAGE_BEHAVIOR_V1", "IGNORE", "未建模来源");
        var replay = compiler.compileCandidate(input(), result.canonicalCandidateJson());
        assertThat(replay.accepted()).isTrue(); assertThat(replay.canonicalCandidateJson()).isEqualTo(result.canonicalCandidateJson());
    }
    @Test void oldV2RejectsUnfrozenBehaviorButRetainsOriginalCanonicalShape() {
        var old = input().withBehavior(null);
        assertThat(compiler.compileCandidate(old, json.writeValueAsString(candidate())).problems()).extracting(PackageDesignCompilation.Problem::code).contains("PACKAGE_BEHAVIOR_NOT_FROZEN");
        var root = fixture.candidate(); var accepted = compiler.compileCandidate(old, json.writeValueAsString(root));
        assertThat(accepted.accepted()).isTrue(); assertThat(accepted.canonicalCandidateJson()).doesNotContain("behaviorBranches");
    }
    @Test void rejectRepairKeepsCounterexampleAndAllObligationsOnEachReplacement() {
        var root = candidate(); ((ObjectNode) root.path("behaviorBranches").get(0).path("effects")).put("result", "DISPATCHED");
        var result = compiler.compileCandidate(input(), json.writeValueAsString(root));
        assertThat(result.retryable()).isTrue();
        assertThat(result.problems()).anySatisfy(p -> { assertThat(p.actual()).contains("counterexample=", "registered"); assertThat(p.pointer()).startsWith("/behaviorBranches"); assertThat(p.repairHint()).contains("完整"); });
        var enriched = CandidateDiagnosticEnricher.enrich(json, json.writeValueAsString(root), result.problems().stream().map(PackageDesignCompilation.Problem::submissionProblem).toList());
        assertThat(enriched).anySatisfy(p -> assertThat(p.actual()).contains("counterexample=", "false"));
        assertThat(compiler.compileCandidate(input(), json.writeValueAsString(candidate())).accepted()).isTrue();
    }
    @Test void extraScenarioCannotHideOutsideTypedChecksAndSchemaRejectsWrongAtoms() {
        var root = candidate(); var extra = ((ObjectNode) root.path("scenarios").get(0)).deepCopy(); extra.put("key", "SC-2");
        ((tools.jackson.databind.node.ArrayNode) root.path("scenarios")).add(extra);
        assertThat(compiler.compileCandidate(input(), json.writeValueAsString(root)).accepted()).isFalse();
        root = candidate(); ((ObjectNode) root.path("behaviorBranches").get(0).path("when")).put("op", "execute");
        assertThat(CandidateShapeValidator.validate(json, io.opencode.loopper.runtime.InternalMcpContractCatalog.packageDesignV2InputSchema(), json.writeValueAsString(root)).problems()).isNotEmpty();
    }
    @Test void dangerousDynamicOutputNamesCannotBecomePermissionFields() {
        var root = candidate(); ((ObjectNode) root.path("behaviorBranches").get(0).path("effects")).put("allowedPaths", "private");
        assertThat(compiler.compileCandidate(input(), json.writeValueAsString(root)).problems()).anySatisfy(p -> assertThat(p.problemClass()).isEqualTo(PackageDesignCompilation.ProblemClass.SECURITY));
    }
}
