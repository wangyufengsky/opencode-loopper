package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.DesignerSemanticContracts.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class DesignerAcceptancePlanningAlgorithmTest {
    private static final String PIN_TRANS_DESIGN = """
            ## 目标与范围
            为 PinTrans 与 PinClient 补齐正常、异常和边界行为；不访问外部网络、数据库或加密平台。

            ## 影响与交付
            | 类型 | 相对路径或符号 | 说明 |
            | --- | --- | --- |
            | 生产代码 | upfs-common/src/main/java/com/spdb/upfs/pin/PinTrans.java | PIN 转换行为 |
            | 测试代码 | upfs-common/src/test/java/com/spdb/upfs/pin/PinTransTest.java | PinTrans 单元测试 |
            | 测试代码 | upfs-common/src/test/java/com/spdb/upfs/pin/PinClientTest.java | PinClient 单元测试 |

            ## 验收场景
            | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
            | --- | --- | --- | --- | --- |
            | PinTrans: pinBlock 路径缺失 | 输入缺少 pinBlock 路径 | 调用转换 | 抛出 PinException，错误码 DEF9900000，信息为“缺少PIN工作密钥密文” | 不调用外部依赖 |
            | PinTrans: priAcctNo 路径缺失 | 输入缺少 priAcctNo 路径 | 调用转换 | 抛出 PinException，错误码 DEF9900000，信息为“缺少账号信息” | 不调用外部依赖 |
            | PinTrans: 内部 PinClient 调用异常 | PinClient 转 Pin 失败 | 调用转换 | 抛出 PinException，错误码 DEF9900000，信息为“转Pin失败” | 保留原始异常 cause |
            | PinTrans: pinBlock 为空 | pinBlock 是空字符串 | 调用转换 | 不抛异常并返回默认结果 | 不修改 input |

            ## 验收约束
            PinClientTest 与 PinTransTest 两个测试类必须各自独立通过。测试不依赖外部网络、数据库、加密平台或 Spring 上下文。

            ## 阶段与依赖
            | 阶段建议 | 包含场景/交付 | 前置阶段 |
            | --- | --- | --- |
            | 完成 PIN 转换行为 | 全部四个场景与两个测试类 | 无 |
            """;

    private final DesignerEvidenceIndexer evidenceIndexer = new DesignerEvidenceIndexer();
    private final DesignerDesignFactExtractor extractor = new DesignerDesignFactExtractor(evidenceIndexer);
    private final DesignerVerificationCapabilityRegistry registry = new DesignerVerificationCapabilityRegistry();
    private final DesignerAcceptancePlanCompiler compiler = new DesignerAcceptancePlanCompiler(
            new DesignerPackagePlanCompiler(evidenceIndexer));

    @Test
    void extractsEarsFactsBuildsIndependentCapabilitiesAndLowersPinTransDeterministically() {
        Catalog facts = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN);
        WorkPackageRoleService.View role = new WorkPackageRoleService.View("software-java",
                RolePackRegistry.VERSION, ExecutionStrategy.OPEN_CODE_IMPLEMENTATION,
                TestPolicy.REQUIRED, List.of("java"));
        CapabilityCatalog capabilities = registry.build(facts, role, PIN_TRANS_DESIGN);

        assertThat(facts.controlledFormat()).isTrue();
        assertThat(facts.facts()).filteredOn(fact -> fact.kind() == FactKind.SCENARIO).hasSize(4)
                .allSatisfy(fact -> assertThat(fact.sourceSha256()).hasSize(64));
        assertThat(facts.facts()).filteredOn(fact -> fact.kind() == FactKind.POLICY).singleElement()
                .satisfies(fact -> {
                    assertThat(fact.detail()).contains("两个测试类必须各自独立通过");
                    assertThat(fact.sourceRef()).matches("DS-L\\d{3}");
                    assertThat(fact.sourceExcerpt()).isEqualTo(
                            "PinClientTest 与 PinTransTest 两个测试类必须各自独立通过。测试不依赖外部网络、数据库、加密平台或 Spring 上下文。");
                });
        assertThat(capabilities.issues()).isEmpty();
        assertThat(capabilities.capabilities()).extracting(Capability::testTargets)
                .containsExactlyInAnyOrder(List.of("PinClientTest"), List.of("PinTransTest"));
        assertThat(capabilities.capabilities()).extracting(Capability::command)
                .containsExactlyInAnyOrder(
                        List.of("mvn", "-pl", "upfs-common", "-Dtest=PinClientTest", "test"),
                        List.of("mvn", "-pl", "upfs-common", "-Dtest=PinTransTest", "test"));
        assertThat(capabilities.capabilities()).allSatisfy(capability -> assertThat(capability.mandatory()).isTrue());

        List<Integer> acceptanceIndexes = facts.facts().stream()
                .filter(fact -> fact.kind() == FactKind.SCENARIO).map(Fact::index).toList();
        CompactAcceptanceBindingPlan binding = new CompactAcceptanceBindingPlan("COMPILED", "PIN 转换验收",
                List.of(new AcceptanceGroupHint("PIN 转换", "实现并验证 PIN 转换行为", acceptanceIndexes, List.of())),
                List.of(), "PinTrans 与 PinClient 行为已冻结", List.of());
        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), PIN_TRANS_DESIGN,
                facts, capabilities, binding, role,
                List.of("upfs-common/src/main/java/**", "upfs-common/src/test/java/**"), List.of(),
                List.of("PinTrans 实现与两个独立测试类"), 6, true);

        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.plan().stages()).hasSize(1);
        assertThat(result.plan().evidenceMappings()).hasSize(4)
                .allSatisfy(mapping -> assertThat(mapping.verificationMode()).isEqualTo("MACHINE"));
        assertThat(result.plan().stages().getFirst().verifiers())
                .filteredOn(verifier -> "PROCESS".equals(verifier.type()))
                .extracting(verifier -> verifier.testTargets())
                .containsExactlyInAnyOrder(List.of("PinClientTest"), List.of("PinTransTest"));
        assertThat(result.plan().evidenceMappings())
                .filteredOn(mapping -> mapping.description().contains("PinClient"))
                .singleElement().satisfies(mapping -> assertThat(mapping.testTargets())
                        .containsExactly("PinClientTest"));
        assertThat(result.plan().evidenceMappings())
                .filteredOn(mapping -> mapping.description().contains("不修改 input"))
                .singleElement().satisfies(mapping -> assertThat(mapping.testTargets())
                        .containsExactly("PinTransTest"));
        assertThat(result.diagnostics().algorithm()).isEqualTo("EXACT_BRANCH_AND_BOUND");
        assertThat(result.diagnostics().uncoveredFactIndexes()).isEmpty();
    }

    @Test
    void derivesRepositoryNativeCapabilitiesForJavaNodePythonAndMixedStacks() {
        assertNativeCapability(List.of("java"), "software-java",
                "当 Listener 被调用时，结果可观察。测试目标：ListenerTest。",
                List.of("mvn", "-Dtest=ListenerTest", "test"));
        assertNativeCapability(List.of("python"), "software-python",
                "当 Listener 被调用时，结果可观察。测试目标：tests/test_listener.py。",
                List.of("python3", "-m", "pytest", "tests/test_listener.py"));
        assertNativeCapability(List.of("node"), "software-node",
                "当 Listener 被调用时，结果可观察。测试目标：src/listener.spec.ts。",
                List.of("npm", "test", "--", "src/listener.spec.ts"));

        String mixedDesign = "当 Backend 被调用时返回结果，BackendListenerTest 独立通过；"
                + "当界面接收结果时更新状态，src/listener.spec.ts 独立通过。";
        Catalog mixedFacts = extractor.extract("WP-1", 1, mixedDesign);
        CapabilityCatalog mixed = registry.build(mixedFacts,
                role("software-mixed", List.of("java", "node")), mixedDesign);
        assertThat(mixed.capabilities()).extracting(Capability::command)
                .containsExactlyInAnyOrder(
                        List.of("mvn", "-Dtest=BackendListenerTest", "test"),
                        List.of("npm", "test", "--", "src/listener.spec.ts"));
        assertThat(mixed.issues()).isEmpty();
    }

    private void assertNativeCapability(List<String> technologies, String rolePack, String design,
                                        List<String> expectedCommand) {
        Catalog facts = extractor.extract("WP-1", 1, design);
        CapabilityCatalog capabilities = registry.build(facts, role(rolePack, technologies), design);
        assertThat(capabilities.capabilities()).extracting(Capability::command)
                .containsExactly(expectedCommand);
        assertThat(capabilities.issues()).isEmpty();
    }

    private static WorkPackageRoleService.View role(String rolePack, List<String> technologies) {
        return new WorkPackageRoleService.View(rolePack, RolePackRegistry.VERSION,
                ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.REQUIRED, technologies);
    }

    private static DesignWorkPackageRow workPackage() {
        return new DesignWorkPackageRow("row-1", "session-1", "requirement-1", "decomposition-1",
                "WP-1", 1, "PIN 转换", "完成 PIN 转换行为", "[]", "[]", "[]", "[]", "[]", "[]",
                "COMPILING", null, null, "design-1", 1, 0, 0, null, null, null, null,
                null, 0, null, null, "2026-08-21T00:00:00Z", "2026-08-21T00:00:00Z", 0);
    }
}
