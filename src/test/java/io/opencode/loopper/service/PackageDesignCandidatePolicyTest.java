package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.PackageDesignAcceptedResultRow;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class PackageDesignCandidatePolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PackageDesignCompilation compilation = new DeterministicPackageDesignCompilation(json);
    private final PackageDesignCompilationInputLoader inputs = ignored -> input();
    private final PackageDesignCandidatePolicy policy = new PackageDesignCandidatePolicy(inputs, compilation);

    @Test
    void mapsOnlyMechanicalProblemsToRetryAndBudgetFallback() {
        CandidatePolicy.Decision decision = policy.evaluate(context(), candidate()
                .replace("\"SC-1\",\"DEL-1\"", "\"SC-1\",\"UNKNOWN\""));

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.fallbackEligible()).isTrue();
        assertThat(decision.problems()).singleElement()
                .extracting(MachineCandidateSubmission.Problem::code)
                .isEqualTo("PACKAGE_DESIGN_REFERENCE_INVALID");
    }

    @Test
    void acceptedWriterPersistsCanonicalMarkdownAndCompiledEnvelopeTogether() throws Exception {
        CandidatePolicy.Decision decision = policy.evaluate(context(), candidate());
        LoopperMachineCandidateMapper mapper = mock(LoopperMachineCandidateMapper.class);
        when(mapper.insertPackageDesignAcceptedResult(any())).thenReturn(1);
        PackageDesignAcceptedCandidateWriter writer =
                new PackageDesignAcceptedCandidateWriter(mapper, inputs, compilation);

        writer.write(context(), decision.canonicalCandidateJson(), sha256(decision.canonicalCandidateJson()));

        ArgumentCaptor<PackageDesignAcceptedResultRow> row =
                ArgumentCaptor.forClass(PackageDesignAcceptedResultRow.class);
        verify(mapper).insertPackageDesignAcceptedResult(row.capture());
        assertThat(row.getValue().candidateRunId()).isEqualTo("run-1");
        assertThat(row.getValue().designWorkPackageId()).isEqualTo("package-row");
        assertThat(row.getValue().sourceRevision()).isEqualTo(2);
        assertThat(row.getValue().ownerVersion()).isEqualTo(0);
        assertThat(row.getValue().canonicalCandidateJson()).isEqualTo(decision.canonicalCandidateJson());
        assertThat(row.getValue().canonicalMarkdown()).contains("## 验收场景", "## 阶段与依赖");
        assertThat(json.readValue(row.getValue().compiledResultJson(),
                DesignerSemanticContracts.PackageCompilationPlanEnvelope.class).status()).isEqualTo("COMPILED");
        assertThat(row.getValue().settledCompilationId()).isNull();
        assertThat(row.getValue().version()).isZero();
    }

    private CandidatePolicy.Context context() {
        return new CandidatePolicy.Context("run-1", "designer",
                MachineCandidateSubmission.CandidateOwner.designWorkPackage("package-row"),
                MachineCandidateKind.PACKAGE_DESIGN_V1, PackageDesignCandidatePolicy.WORKFLOW_STEP,
                2, 0, PackageDesignCandidateCodec.CONTRACT_VERSION,
                PackageDesignCandidatePolicy.MAX_ATTEMPTS, 0);
    }

    private PackageDesignCompilation.Input input() {
        return new PackageDesignCompilation.Input(workPackage(), "新增事件分发安全分支",
                new WorkPackageRoleService.View("software-java", RolePackRegistry.VERSION,
                        ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.REQUIRED, List.of("java")),
                List.of("src/test/java/example/EventBusTest.java"), List.of(), List.of("EventBusTest"), 6, true);
    }

    private DesignWorkPackageRow workPackage() {
        return new DesignWorkPackageRow("package-row", "designer", "requirement", "decomposition",
                "WP-1", 0, "事件分发", "实现事件分发", "[]", "[]", "[]", "[]", "[]", "[]",
                "DESIGNING", null, null, null, 2, 0, 0, null, null, null, null, null, 0, null, null,
                "now", "now", 0);
    }

    private String candidate() {
        return """
                {"contractVersion":"PACKAGE_DESIGN_V1","outcome":"READY",
                 "requirements":[{"key":"REQ-1","statement":"事件分发必须安全处理未注册事件"}],
                 "scenarios":[{"key":"SC-1","title":"未注册事件被安全忽略","precondition":"事件类型尚未注册",
                   "action":"发布该事件","observableResult":"发布调用正常返回且没有处理器被调用",
                   "invariant":"既有已注册事件分发不变","requirementRefs":["REQ-1"]}],
                 "deliverables":[{"key":"DEL-1","kind":"DELIVERABLE",
                   "target":"src/test/java/example/EventBusTest.java","description":"新增 EventBusTest 聚焦验证未注册事件分支",
                   "requirementRefs":["REQ-1"]}],"reviews":[],
                 "stages":[{"key":"STAGE-1","title":"事件分发测试","objective":"实现并验证未注册事件分支",
                   "includes":["SC-1","DEL-1"],"dependencies":[]}],"gapCodes":[]}
                """;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
