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
        assertThat(new DesignerVerificationIntentMapper().positiveEvidence(facts, PIN_TRANS_DESIGN))
                .anyMatch(value -> value.contains("PinClientTest"))
                .anyMatch(value -> value.contains("PinTransTest"));
        assertThat(registry.derivedCommands(role, facts, PIN_TRANS_DESIGN))
                .containsExactlyInAnyOrder(
                        List.of("mvn", "-pl", "upfs-common", "-Dtest=PinClientTest", "test"),
                        List.of("mvn", "-pl", "upfs-common", "-Dtest=PinTransTest", "test"));
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
        CompactAcceptanceBindingPlan binding = new CompactAcceptanceBindingPlan("PIN 转换验收",
                List.of(new AcceptanceGroupHint("PIN 转换", "实现并验证 PIN 转换行为", acceptanceIndexes, List.of())),
                List.of(), "PinTrans 与 PinClient 行为已冻结");
        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), PIN_TRANS_DESIGN,
                facts, capabilities, binding, role,
                List.of("upfs-common/src/main/java/**", "upfs-common/src/test/java/**"), List.of(),
                List.of("PinTrans 实现与两个独立测试类"), 6, true);

        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.plan().stages()).hasSize(1);
        assertThat(result.plan().evidenceMappings()).hasSize(5)
                .allSatisfy(mapping -> assertThat(mapping.verificationMode()).isEqualTo("MACHINE"));
        assertThat(result.plan().stages().getFirst().verifiers())
                .filteredOn(verifier -> "PROCESS".equals(verifier.type()))
                .extracting(verifier -> verifier.testTargets())
                .containsExactlyInAnyOrder(List.of("PinClientTest"), List.of("PinTransTest"));
        assertThat(result.plan().evidenceMappings())
                .filteredOn(mapping -> mapping.description().contains("PinClient 转 Pin"))
                .singleElement().satisfies(mapping -> assertThat(mapping.testTargets())
                        .containsExactly("PinTransTest"));
        assertThat(result.plan().evidenceMappings())
                .filteredOn(mapping -> mapping.description().contains("PinClientTest 必须单独通过"))
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
    void mapsStructuredDeliverableIntentAcrossQualifiedAndAggregateTestsAndRejectsNegativeMentions() {
        String design = """
                ## 目标与范围
                为调用器、业务执行器和基础模块补齐纯单元测试。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增 | src/test/java/example/ChainNodeInvokerCoreTest.java | ChainNodeInvoker 正常调用、中断短路与 clearForceState 句柄失效 |
                | 新增 | src/test/java/example/BusinessChainExecutorCoreTest.java | BusinessChainExecutor 构造校验、流水号复用与异常兜底 |
                | 新增 | src/test/java/example/ChainModulesTest.java | ValidateModule 中断、ConvertModule 生成 result、AuditModule 生成 auditMessage |
                | 不变 | src/test/java/example/LegacyExceptionTest.java | 既有测试保持原样 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | ChainNodeInvoker 正常调用 | 注册计数模块 | execute | 计数增加并记录 reached | 默认作用域不变 |
                | ChainNodeInvoker 中断短路 | context 已中断 | execute | 模块不被调用 | 中断原因不变 |
                | clearForceState 后旧句柄失效 | 已注册 force handle | 清理后执行旧句柄 | 抛出归属校验异常 | 属性不清除 |
                | BusinessChainExecutor 构造校验 | 参数为空 | 构造执行器 | 抛出参数异常 | 无副作用 |
                | BusinessChainExecutor 流水号复用 | 已有 tradeSeq | execute | 复用原流水号 | 其他属性不变 |
                | BusinessChainExecutor 异常兜底 | normal 抛异常 | execute | audit 补跑 | cause 不变 |
                | 基础模块行为 | 直接实例化三个模块 | 分别 execute | ValidateModule 中断；ConvertModule 生成 result；AuditModule 生成 auditMessage | 模块无共享状态 |

                ## 验收约束
                禁止 @SpringBootTest，不修改 LegacyExceptionTest；三个新增测试类必须各自独立通过。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | --- | --- | --- |
                | 核心调用器 | ChainNodeInvokerCoreTest 覆盖调用器三个场景 | 无 |
                | 业务与模块 | BusinessChainExecutorCoreTest 与 ChainModulesTest 覆盖业务和模块场景 | 核心调用器 |
                """;
        Catalog facts = extractor.extract("WP-1", 1, design);
        CapabilityCatalog capabilities = registry.build(facts, role("software-java", List.of("java")), design);

        assertThat(capabilities.capabilities()).extracting(Capability::testTargets)
                .containsExactlyInAnyOrder(
                        List.of("ChainNodeInvokerCoreTest"),
                        List.of("BusinessChainExecutorCoreTest"),
                        List.of("ChainModulesTest"))
                .doesNotContain(List.of("SpringBootTest"), List.of("LegacyExceptionTest"));
        assertThat(capabilities.issues()).isEmpty();
        assertThat(capabilities.capabilities()).allSatisfy(capability -> {
            assertThat(capability.mandatory()).isTrue();
            assertThat(capability.coversFactIndexes()).isNotEmpty();
        });
        assertThat(capabilities.capabilities()).filteredOn(capability ->
                        capability.testTargets().contains("ChainNodeInvokerCoreTest"))
                .singleElement().satisfies(capability -> assertThat(capability.coversFactIndexes()).hasSize(3));
        assertThat(capabilities.capabilities()).filteredOn(capability ->
                        capability.testTargets().contains("BusinessChainExecutorCoreTest"))
                .singleElement().satisfies(capability -> assertThat(capability.coversFactIndexes()).hasSize(3));
        assertThat(capabilities.capabilities()).filteredOn(capability ->
                        capability.testTargets().contains("ChainModulesTest"))
                .singleElement().satisfies(capability -> assertThat(capability.coversFactIndexes()).hasSize(1));

        Fact firstScenario = facts.facts().stream().filter(fact -> fact.kind() == FactKind.SCENARIO).findFirst().orElseThrow();
        assertThat(firstScenario.sourceRef()).isNotEqualTo("DS-L001");
        assertThat(firstScenario.sourceExcerpt()).startsWith("| ChainNodeInvoker 正常调用 |");

        CompactAcceptanceBindingPlan advice = new CompactAcceptanceBindingPlan("通用单元测试验收",
                List.of(), List.of(), "责任链单元测试能力已冻结");
        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), design, facts, capabilities,
                advice, role("software-java", List.of("java")), List.of("src/test/java/**"),
                List.of("src/main/**"), List.of("新增纯单元测试"), 6, true);
        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.diagnostics().uncoveredFactIndexes()).isEmpty();
        assertThat(result.plan().stages()).singleElement().satisfies(stage ->
                assertThat(stage.verifiers()).filteredOn(verifier -> "TEST".equals(verifier.processPurpose()))
                        .hasSize(3));
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

    @Test
    void derivesConcreteIncompleteOutcomeOnTheServerWhenNoClosedCapabilityCanCoverTheScenario() {
        String design = """
                ## 目标与范围
                修改 Listener 的事件投递行为。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 修改 | src/main/java/example/Listener.java | 事件投递实现 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | Listener 投递事件 | 已注册监听器 | publish | 监听器收到事件 | 注册表不变 |

                ## 验收约束
                必须提供可重复的自动化测试，但当前设计没有声明测试目标。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | --- | --- | --- |
                | 事件投递 | Listener 投递事件 | 无 |
                """;
        Catalog facts = extractor.extract("WP-1", 1, design);
        WorkPackageRoleService.View role = role("software-java", List.of("java"));
        CapabilityCatalog capabilities = registry.build(facts, role, design);
        assertThat(capabilities.issues()).contains("REQUIRED_FOCUSED_TEST_UNAVAILABLE");

        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), design, facts, capabilities,
                new CompactAcceptanceBindingPlan("事件投递验收", List.of(), List.of(), "等待补齐验证能力"),
                role, List.of("src/main/java/**", "src/test/java/**"), List.of(), List.of("事件投递"), 6, true);

        assertThat(result.plan().status()).isEqualTo("DESIGN_INCOMPLETE");
        assertThat(result.plan().designGaps()).singleElement().satisfies(gap -> {
            assertThat(gap.code()).isEqualTo(DesignGapCode.VERIFICATION_CAPABILITY_UNAVAILABLE);
            assertThat(gap.detail()).contains("Listener 投递事件");
        });
        assertThat(result.normalizations()).contains("SERVER_DERIVED_DESIGN_INCOMPLETE");
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
