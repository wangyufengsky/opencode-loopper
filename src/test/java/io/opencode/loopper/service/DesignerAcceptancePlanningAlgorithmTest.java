package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.DesignerSemanticContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
    void rejectsControlledDesignWhoseAcceptanceTableIsMalformedInsteadOfTreatingEveryParagraphAsScenario() {
        String malformed = """
                ## 目标与范围
                为责任链增加补偿与幂等保护。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | 新增 | src/main/java/example/Compensation.java | 补偿能力 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | 逆序补偿 | A、B 成功且 C 失败 | 触发补偿 | B 先于 A 补偿 | 原始失败不变 |

                ## 验收约束
                CompensationTest 必须独立通过。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | 补偿执行 | 逆序补偿与 CompensationTest | 无 |
                """;

        assertThatThrownBy(() -> extractor.extract("WP-3", 1, malformed))
                .isInstanceOfSatisfying(BadRequestException.class, error -> {
                    assertThat(error.code()).isEqualTo("MISSING_ACCEPTANCE_INTENT");
                    assertThat(error).hasMessage("设计稿缺少可观察的验收场景");
                });
    }

    @Test
    void rejectsRepeatedControlledSectionsInsteadOfCompilingDuplicateAcceptanceTables() {
        String duplicated = """
                ## 目标与范围
                为状态机增加事件桥接。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增 | src/main/java/example/EventBridge.java | 状态机事件桥接 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 合法事件迁移 | 已配置迁移 | 发布事件 | 状态完成迁移 | 公开 API 不变 |

                ## 验收约束
                EventBridgeTest 必须独立通过。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | --- | --- | --- |
                | 事件桥接 | 合法事件迁移与 EventBridgeTest | 无 |

                ## 目标与范围
                再次输出完整替代设计。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增 | src/main/java/example/EventBridge.java | 状态机事件桥接 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 合法事件迁移 | 已配置迁移 | 发布事件 | 状态完成迁移 | 公开 API 不变 |

                ## 验收约束
                EventBridgeTest 必须独立通过。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | --- | --- | --- |
                | 事件桥接 | 合法事件迁移与 EventBridgeTest | 无 |
                """;

        assertThatThrownBy(() -> extractor.extract("WP-4", 1, duplicated))
                .isInstanceOfSatisfying(BadRequestException.class, error -> {
                    assertThat(error.code()).isEqualTo("DUPLICATED_CONTROLLED_DESIGN_SECTION");
                    assertThat(error).hasMessageContaining("目标与范围").hasMessageContaining("验收场景");
                });
    }

    @Test
    void v6StageTableSplitsChineseAndEnglishSemicolonsWithoutRewritingExactReferences() {
        String design = """
                ## 目标与范围
                实现两阶段 Java 行为。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 生产代码 | src/main/java/example/Flow.java | 流程行为 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 成功路径 | 输入合法 | 执行 | 返回成功 | 不写外部系统 |
                | 失败路径 | 输入非法 | 执行 | 抛出异常 | 不写外部系统 |

                ## 验收约束
                FlowTest 必须独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 成功阶段 | 实现成功路径 | 成功路径；src/main/java/example/Flow.java | 无 |
                | 失败阶段 | 实现失败路径 | 失败路径; src/main/java/example/Flow.java | 成功阶段 |
                """;

        Catalog catalog = extractor.extract("WP-1", 1, design, CONTRACT_VERSION_V6);

        assertThat(catalog.stageHints()).hasSize(2);
        assertThat(catalog.stageHints().get(0).includedReferences())
                .containsExactly("成功路径", "src/main/java/example/Flow.java");
        assertThat(catalog.stageHints().get(1).includedReferences())
                .containsExactly("失败路径", "src/main/java/example/Flow.java");
        assertThat(catalog.stageHints().get(1).dependencyReferences()).containsExactly("成功阶段");
    }

    @Test
    void extractsCompleteControlledDesignLargerThanTwentyFourKibibytes() {
        String design = """
                ## 目标与范围
                %s

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 修改 | src/main/java/example/EventBridge.java | 状态机事件桥接 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 合法事件迁移 | 已配置迁移 | 发布事件 | 状态完成迁移 | 公开 API 不变 |

                ## 验收约束
                EventBridgeTest 必须独立通过。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | --- | --- | --- |
                | 事件桥接 | 合法事件迁移与 EventBridgeTest | 无 |
                """.formatted("完整设计背景与业务边界。".repeat(1_500));

        assertThat(design.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(24 * 1024);
        Catalog facts = extractor.extract("WP-1", 1, design);

        assertThat(facts.controlledFormat()).isTrue();
        assertThat(facts.facts()).anyMatch(fact -> fact.kind() == FactKind.SCENARIO
                && "合法事件迁移".equals(fact.title()));
    }

    @Test
    void bindsUniqueDeclaredFocusedTestAndRejectsNegatedFrameworkMarkersAsCapabilities() {
        String design = """
                ## 目标与范围
                为链路生命周期事件增加状态机订阅桥接。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增 | src/main/java/example/ChainLifecycleEventBridge.java | 生命周期事件桥接 |
                | 新增测试 | src/test/java/example/ChainStateMachineEventTest.java | 本包唯一新增聚焦测试类 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 合法开始事件驱动转换 | 已配置开始迁移 | 发布开始事件 | 状态变为运行中 | 公开 API 不变 |
                | 重复事件被拒绝 | 已完成一次迁移 | 重复发布事件 | 状态保持不变 | 不影响链路执行 |
                | 订阅器与事件总线解耦 | 纯 JUnit 环境 | 直接构造并发布 | 订阅器收到事件 | 无 Spring 上下文 |

                ## 验收约束
                ChainStateMachineEventTest 必须独立通过。StateMachineCoreTest 必须继续回归通过。
                所有新增测试为纯 JUnit 5，无 @SpringBootTest、无 Spring 上下文。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | --- | --- | --- |
                | 桥接层实现与聚焦测试 | ChainStateMachineEventTest 覆盖全部验收场景；StateMachineCoreTest 继续回归 | 无 |
                """;
        Catalog facts = extractor.extract("WP-4", 1, design);
        CapabilityCatalog capabilities = registry.build(facts, role("software-java", List.of("java")), design);

        assertCoverage(facts, capabilities, "ChainStateMachineEventTest",
                "合法开始事件驱动转换", "重复事件被拒绝", "订阅器与事件总线解耦");
        assertThat(capabilities.capabilities()).extracting(Capability::testTargets)
                .doesNotContain(List.of("SpringBootTest"));
        assertThat(capabilities.capabilities()).filteredOn(capability ->
                        capability.testTargets().contains("StateMachineCoreTest"))
                .singleElement().satisfies(capability -> {
                    assertThat(capability.mandatory()).isTrue();
                    assertThat(capability.coversFactIndexes()).isEmpty();
                });
        assertThat(capabilities.issues()).isEmpty();
    }

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
    void bindsUnambiguousFocusedTestAnaphoraAcrossStagesWithoutBorrowingRegressionTests() {
        String design = """
                ## 目标与范围
                为责任链调用身份和节点轨迹补齐治理能力。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 修改 | src/main/java/example/ChainExecutor.java | 调用身份与节点轨迹实现 |
                | 新增测试 | src/test/java/example/ChainExecutionTraceTest.java | 本工作包唯一新增聚焦测试类 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 节点轨迹成功 | 普通节点正常返回 | 执行节点 | 记录 SUCCESS 轨迹 | 结果不变 |
                | 身份推导正向 | 已有 tradeSeq | 执行整链 | 复用原调用身份 | 不生成 UUID |
                | UUID 兜底 | 所有身份键缺失 | 执行整链 | 生成 UUID 并回写 | 成功路径不变 |

                ## 验收约束
                ChainExecutionTraceTest 必须独立通过。ExistingExceptionTest 必须保持通过。
                实现风格与 ExistingExceptionTest 的测试风格一致。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | --- | --- | --- |
                | 节点轨迹 | 聚焦测试覆盖节点轨迹成功 | 无 |
                | 调用身份 | 同一聚焦测试类覆盖身份推导正向、UUID 兜底 | 节点轨迹 |
                """;
        Catalog facts = extractor.extract("WP-1", 1, design);
        CapabilityCatalog capabilities = registry.build(facts, role("software-java", List.of("java")), design);

        assertCoverage(facts, capabilities, "ChainExecutionTraceTest",
                "节点轨迹成功", "身份推导正向", "UUID 兜底");
        assertThat(capabilities.capabilities()).filteredOn(capability ->
                        capability.testTargets().contains("ExistingExceptionTest"))
                .singleElement().satisfies(capability -> {
                    assertThat(capability.mandatory()).isTrue();
                    assertThat(capability.coversFactIndexes()).isEmpty();
                });
        assertThat(capabilities.issues()).isEmpty();
    }

    @Test
    void doesNotResolveAnaphoricStageToOneOfSeveralDeclaredFocusedTests() {
        String design = """
                ## 目标与范围
                为两个独立组件补齐测试。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增测试 | src/test/java/example/AlphaBehaviorTest.java | Alpha 基础行为 |
                | 新增测试 | src/test/java/example/BetaBehaviorTest.java | Beta 基础行为 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 共享兜底 | 两个组件均无值 | 执行兜底 | 生成共享值 | 组件状态不变 |

                ## 验收约束
                AlphaBehaviorTest 与 BetaBehaviorTest 必须分别独立通过。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | --- | --- | --- |
                | 共享行为 | 同一聚焦测试类覆盖共享兜底 | 无 |
                """;
        Catalog facts = extractor.extract("WP-1", 1, design);
        CapabilityCatalog capabilities = registry.build(facts, role("software-java", List.of("java")), design);

        assertThat(capabilities.capabilities()).filteredOn(capability -> "FOCUSED_TEST".equals(capability.kind()))
                .allSatisfy(capability -> assertThat(capability.coversFactIndexes()).isEmpty());
        assertThat(capabilities.issues()).contains("VERIFICATION_CAPABILITY_UNAVAILABLE:[2]");
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
    void preservesExplicitUnittestCommandWhenStructuredFactsAlsoProvidePositiveEvidence() {
        String design = """
                ## 目标与范围
                更新 README 中的滚动包事实。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 修改 | README.md | 当前包形成的可观察结果 |
                | 测试 | tests/test_acceptance.py | 当前包的聚焦验收测试 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 当前包验收 | 前序事实已冻结 | 执行当前包 | README 保留 event 标记 | 既有事实不删除 |

                ## 验收约束
                聚焦测试必须独立通过：
                `python3 -m unittest tests.test_acceptance`

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 实现与验证 | 写入并验证当前包事实 | 当前包验收；README.md；tests/test_acceptance.py | 无 |
                """;
        Catalog facts = extractor.extract("WP-1", 1, design);

        CapabilityCatalog capabilities = registry.build(
                facts, role("software-python", List.of("python")), design);

        assertThat(capabilities.capabilities()).singleElement().satisfies(capability -> {
            assertThat(capability.command())
                    .containsExactly("python3", "-m", "unittest", "tests.test_acceptance");
            assertThat(capability.testTargets()).containsExactly("tests.test_acceptance");
        });
        assertThat(capabilities.issues()).isEmpty();
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
        Catalog facts = new DesignerMutationObligationExtractor().extract(
                extractor.extract("WP-1", 1, design, CONTRACT_VERSION_V7),
                "修改 `src/main/java/example/Listener.java`", List.of(), List.of(), List.of());
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
        assertThat(result.mutationConservation()).satisfies(evaluation -> {
            assertThat(evaluation.obligationCount()).isEqualTo(1);
            assertThat(evaluation.unresolvedCount()).isEqualTo(1);
            assertThat(evaluation.pathConservation()).isEqualTo("NOT_EVALUATED");
        });
    }

    @Test
    void v7AutoBindsAnExactRequiredMutationPathWhenThereIsOnlyOneStage() {
        String design = """
                ## 目标与范围
                修改 Service 并保持外部适配配置与实现一致。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 修改 | src/main/java/example/Service.java | 服务实现 |
                | 新增测试 | src/test/java/example/ServiceTest.java | 聚焦验收 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 外部适配生效 | 已配置 adapter | 调用服务 | 返回适配结果 | 无外部写入 |

                ## 验收约束
                ServiceTest 必须独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 服务实现 | 实现并验证服务 | 外部适配生效；src/main/java/example/Service.java；src/test/java/example/ServiceTest.java | 无 |
                """;
        Catalog extracted = extractor.extract("WP-1", 1, design, CONTRACT_VERSION_V7);
        MutationObligation missing = new MutationObligation(1, "MO-2",
                "config/external-adapter.yml", MutationOperation.WRITE,
                MutationSourceKind.REQUIREMENT, "REQUIREMENT:L003",
                "- config/external-adapter.yml", "1".repeat(64), List.of(), List.of());
        Catalog facts = new Catalog(extracted.contractVersion(), extracted.workPackageId(),
                extracted.designRevision(), extracted.designSha256(), extracted.controlledFormat(),
                extracted.facts(), extracted.stageHints(), List.of(missing), List.of(), extracted.issues());
        WorkPackageRoleService.View role = role("software-java", List.of("java"));
        CapabilityCatalog capabilities = registry.build(facts, role, design);
        List<Integer> groupFacts = facts.facts().stream()
                .filter(fact -> fact.kind() == FactKind.SCENARIO || fact.kind() == FactKind.DELIVERABLE)
                .map(Fact::index).toList();

        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), design, facts, capabilities,
                new CompactAcceptanceBindingPlan("外部适配验收",
                        List.of(new AcceptanceGroupHint("服务实现", "实现并验证服务",
                                groupFacts, List.of())), List.of(), "待验证"),
                role, List.of("src/main/java/**", "src/test/java/**"), List.of(),
                List.of("服务实现"), 6, true);

        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.plan().stages()).singleElement().satisfies(stage -> {
            assertThat(stage.allowedPaths()).containsExactly(
                    "src/main/java/example/Service.java",
                    "src/test/java/example/ServiceTest.java",
                    "config/external-adapter.yml");
            assertThat(stage.verifiers()).allSatisfy(verifier -> {
                assertThat(verifier.allowedPaths()).containsExactlyElementsOf(stage.allowedPaths());
                assertThat(verifier.forbiddenPaths()).containsExactlyElementsOf(stage.forbiddenPaths());
            });
        });
        assertThat(result.mutationConservation()).satisfies(evaluation -> {
            assertThat(evaluation.obligationCount()).isEqualTo(1);
            assertThat(evaluation.resolvedCount()).isEqualTo(1);
            assertThat(evaluation.pathConservation()).isEqualTo("CONSERVED");
        });
        assertThat(result.normalizations()).contains("MUTATION_PATH_SINGLE_STAGE_BOUND");
    }

    @Test
    void v7BindsARequirementMutationToTheOnlyStageWhoseDeclaredRuleCoversIt() {
        String design = """
                ## 目标与范围
                实现配置适配和回退行为。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 修改 | config/*.yml | 适配配置 |
                | 修改 | src/main/java/example/AdapterService.java | 适配实现 |
                | 新增测试 | src/test/java/example/AdapterServiceTest.java | 适配配置生效 |
                | 修改 | src/main/java/example/FallbackService.java | 回退实现 |
                | 新增测试 | src/test/java/example/FallbackServiceTest.java | 回退策略生效 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 适配配置生效 | 配置 adapter | 调用适配服务 | 返回适配结果 | 不写外部系统 |
                | 回退策略生效 | 适配器失败 | 调用回退服务 | 返回回退结果 | 原始错误保留 |

                ## 验收约束
                AdapterServiceTest 与 FallbackServiceTest 必须各自独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 适配配置 | 实现并验证配置适配 | 适配配置生效；config/*.yml；src/main/java/example/AdapterService.java；src/test/java/example/AdapterServiceTest.java | 无 |
                | 回退实现 | 实现并验证回退策略 | 回退策略生效；src/main/java/example/FallbackService.java；src/test/java/example/FallbackServiceTest.java | 适配配置 |
                """;
        Catalog base = extractor.extract("WP-1", 1, design, CONTRACT_VERSION_V7);
        Catalog facts = mutationCatalog(base, List.of(
                mutation(0, "config/external-adapter.yml", MutationOperation.WRITE)));
        WorkPackageRoleService.View role = role("software-java", List.of("java"));
        CapabilityCatalog capabilities = registry.build(facts, role, design);
        DesignerAcceptanceFastPathResolver.Resolution resolution =
                new DesignerAcceptanceFastPathResolver().resolve(facts, capabilities);

        assertThat(resolution.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), design, facts, capabilities,
                new CompactAcceptanceBindingPlan("两阶段验收", resolution.groupHints(), List.of(), "待验证"),
                role, List.of(), List.of(), List.of("配置适配和回退行为"), 6, true);

        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.plan().stages()).hasSize(2);
        assertThat(result.plan().stages().getFirst().allowedPaths()).contains("config/*.yml");
        assertThat(result.plan().stages().get(1).allowedPaths()).doesNotContain("config/*.yml");
        assertThat(result.normalizations()).contains("MUTATION_PATH_UNIQUE_PATH_COVERAGE_BOUND");

        int configFact = facts.facts().stream().filter(fact -> "config/*.yml".equals(fact.title()))
                .map(Fact::index).findFirst().orElseThrow();
        AcceptanceGroupHint second = resolution.groupHints().get(1);
        LinkedHashSet<Integer> secondFacts = new LinkedHashSet<>(second.factIndexes());
        secondFacts.add(configFact);
        DesignerAcceptancePlanCompiler.Result ambiguous = compiler.compile(workPackage(), design, facts, capabilities,
                new CompactAcceptanceBindingPlan("两阶段验收", List.of(resolution.groupHints().getFirst(),
                        new AcceptanceGroupHint(second.title(), second.objective(), List.copyOf(secondFacts),
                                second.dependsOnHintIndexes())), List.of(), "待验证"),
                role, List.of(), List.of(), List.of("配置适配和回退行为"), 6, true);

        assertThat(ambiguous.plan().status()).isEqualTo("DESIGN_INCOMPLETE");
        assertThat(ambiguous.plan().designGaps()).singleElement().satisfies(gap -> {
            assertThat(gap.code()).isEqualTo(DesignGapCode.REQUIRED_MUTATION_PATH_UNASSIGNED);
            assertThat(gap.detail()).contains("config/external-adapter.yml", "适配配置", "回退实现")
                    .doesNotContain("实现并验证配置适配", "实现并验证回退策略");
        });
        assertThat(ambiguous.normalizations()).contains(
                "MUTATION_PATH_AMBIGUOUS_STAGE_BLOCKED", "MUTATION_PATH_CONSERVATION_BLOCKED");
    }

    @Test
    void v7UsesTheResponsiblePathColumnAsExplicitMultiStageOwnership() {
        String design = """
                ## 目标与范围
                实现配置适配和回退行为。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 修改 | config/external-adapter.yml | 适配配置 |
                | 修改 | src/main/java/example/AdapterService.java | 适配实现 |
                | 新增测试 | src/test/java/example/AdapterServiceTest.java | 适配配置生效 |
                | 修改 | src/main/java/example/FallbackService.java | 回退实现 |
                | 新增测试 | src/test/java/example/FallbackServiceTest.java | 回退策略生效 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 适配配置生效 | 配置 adapter | 调用适配服务 | 返回适配结果 | 不写外部系统 |
                | 回退策略生效 | 适配器失败 | 调用回退服务 | 返回回退结果 | 原始错误保留 |

                ## 验收约束
                AdapterServiceTest 与 FallbackServiceTest 必须各自独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 负责路径 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- | --- |
                | 适配配置 | 实现并验证配置适配 | config/external-adapter.yml | 适配配置生效；src/main/java/example/AdapterService.java；src/test/java/example/AdapterServiceTest.java | 无 |
                | 回退实现 | 实现并验证回退策略 | src/main/java/example/FallbackService.java；src/test/java/example/FallbackServiceTest.java | 回退策略生效；src/main/java/example/FallbackService.java；src/test/java/example/FallbackServiceTest.java | 适配配置 |
                """;
        Catalog base = extractor.extract("WP-1", 1, design, CONTRACT_VERSION_V7);
        Catalog facts = mutationCatalog(base, List.of(
                mutation(0, "config/external-adapter.yml", MutationOperation.WRITE)));
        WorkPackageRoleService.View role = role("software-java", List.of("java"));
        CapabilityCatalog capabilities = registry.build(facts, role, design);
        DesignerAcceptanceFastPathResolver.Resolution resolution =
                new DesignerAcceptanceFastPathResolver().resolve(facts, capabilities);

        assertThat(resolution.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), design, facts, capabilities,
                new CompactAcceptanceBindingPlan("两阶段验收", resolution.groupHints(), List.of(), "待验证"),
                role, List.of(), List.of(), List.of("配置适配和回退行为"), 6, true);

        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.plan().stages()).hasSize(2);
        assertThat(result.plan().stages().getFirst().allowedPaths())
                .contains("config/external-adapter.yml");
        assertThat(result.plan().stages().get(1).allowedPaths())
                .doesNotContain("config/external-adapter.yml");
        assertThat(result.mutationConservation().pathConservation()).isEqualTo("CONSERVED");
        assertThat(result.normalizations()).contains("MUTATION_PATH_EXPLICIT_RESPONSIBILITY_BOUND");
    }

    @Test
    void v7BlocksAResponsiblePathDeclaredByMultipleStages() {
        Catalog facts = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "5".repeat(64), true,
                List.of(), List.of(),
                List.of(mutation(0, "config/external-adapter.yml", MutationOperation.WRITE)),
                List.of(), List.of());
        DesignerAcceptanceStagePathPlanner.Selection selection =
                new DesignerAcceptanceStagePathPlanner.Selection(List.of("config/*.yml"), List.of("config/*.yml"));

        DesignerMutationStageBinder.Resolution result = new DesignerMutationStageBinder().bind(facts, List.of(
                new DesignerMutationStageBinder.StageInput("适配配置", "实现适配配置", List.of(), selection,
                        List.of("config/*.yml")),
                new DesignerMutationStageBinder.StageInput("回退配置", "实现回退配置", List.of(), selection,
                        List.of("config/*.yml"))));

        assertThat(result.assignments()).isEmpty();
        assertThat(result.unresolved()).singleElement().satisfies(unresolved -> {
            assertThat(unresolved.candidateStageIndexes()).containsExactly(0, 1);
            assertThat(unresolved.reason()).contains("负责路径列", "适配配置", "回退配置");
        });
        assertThat(result.normalizations()).contains("MUTATION_PATH_AMBIGUOUS_STAGE_BLOCKED");
    }

    @Test
    void v7UsesAnExactUniqueDeliverableSymbolToRecoverLegacyFourColumnOwnership() {
        String design = """
                ## 目标与范围
                实现调度场景与引擎能力。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增 | src/main/java/com/spdb/upfs/schedule/ScheduleSceneEnum.java | 调度场景枚举 |
                | 新增 | src/main/java/com/spdb/upfs/schedule/SchedulerEngine.java | 调度引擎 |
                | 新增测试 | src/test/java/com/spdb/upfs/schedule/SchedulerEngineTest.java | 引擎验收 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 场景编码可用 | 已定义调度场景 | 查询编码 | 返回稳定编码 | 枚举不可变 |
                | 到期任务执行 | 已注册任务 | 调用 tick | 动作被调用 | 同步执行 |

                ## 验收约束
                SchedulerEngineTest 必须独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 任务定义 | 交付 ScheduleSceneEnum 与不可变任务定义 | 场景编码可用 | 无 |
                | 调度引擎 | 交付 SchedulerEngine 并通过 SchedulerEngineTest | 到期任务执行；src/main/java/com/spdb/upfs/schedule/SchedulerEngine.java；src/test/java/com/spdb/upfs/schedule/SchedulerEngineTest.java | 任务定义 |
                """;
        Catalog base = extractor.extract("WP-1", 1, design, CONTRACT_VERSION_V7);
        Catalog facts = mutationCatalog(base, List.of(mutation(0,
                "src/main/java/com/spdb/upfs/schedule/ScheduleSceneEnum.java", MutationOperation.WRITE)));
        WorkPackageRoleService.View role = role("software-java", List.of("java"));
        CapabilityCatalog capabilities = registry.build(facts, role, design);
        DesignerAcceptanceFastPathResolver.Resolution resolution =
                new DesignerAcceptanceFastPathResolver().resolve(facts, capabilities);

        assertThat(resolution.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), design, facts, capabilities,
                new CompactAcceptanceBindingPlan("两阶段验收", resolution.groupHints(), List.of(), "待验证"),
                role, List.of(), List.of(), List.of("调度场景与引擎能力"), 6, true);

        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.plan().stages().getFirst().allowedPaths())
                .contains("src/main/java/com/spdb/upfs/schedule/ScheduleSceneEnum.java");
        assertThat(result.plan().stages().get(1).allowedPaths())
                .doesNotContain("src/main/java/com/spdb/upfs/schedule/ScheduleSceneEnum.java");
        assertThat(result.normalizations()).contains("MUTATION_PATH_UNIQUE_DELIVERABLE_SYMBOL_BOUND");
    }

    @Test
    void v7KeepsMultipleIndirectStageCandidatesBlockedWithoutAWeakModelAssignment() {
        Fact sharedRule = new Fact(0, FactKind.DELIVERABLE, "config/*.yml", null, null,
                null, null, "修改：共享配置规则", "DS-L010", "config/*.yml", "4".repeat(64));
        Catalog facts = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "5".repeat(64), true,
                List.of(sharedRule), List.of(),
                List.of(mutation(0, "config/external-adapter.yml", MutationOperation.WRITE)),
                List.of(), List.of());
        DesignerAcceptanceStagePathPlanner.Selection sharedSelection =
                new DesignerAcceptanceStagePathPlanner.Selection(
                        List.of("config/*.yml"), List.of("config/*.yml"));

        DesignerMutationStageBinder.Resolution result = new DesignerMutationStageBinder().bind(facts, List.of(
                new DesignerMutationStageBinder.StageInput("适配配置", List.of(0), sharedSelection),
                new DesignerMutationStageBinder.StageInput("回退配置", List.of(0), sharedSelection)));

        assertThat(result.assignments()).isEmpty();
        assertThat(result.unresolved()).singleElement().satisfies(unresolved -> {
            assertThat(unresolved.obligationIndex()).isZero();
            assertThat(unresolved.candidateStageIndexes()).containsExactly(0, 1);
            assertThat(unresolved.reason()).contains("适配配置", "回退配置");
        });
        assertThat(result.normalizations()).contains("MUTATION_PATH_AMBIGUOUS_STAGE_BLOCKED");
    }

    @Test
    void v7BindsAnObligationThroughItsExactControlledFactReference() {
        Fact exactFact = new Fact(0, FactKind.DELIVERABLE, "config/exact.yml", null, null,
                null, null, "修改：精确配置", "DS-L010", "config/exact.yml", "4".repeat(64));
        Catalog facts = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "5".repeat(64), true,
                List.of(exactFact), List.of(),
                List.of(new MutationObligation(0, "MO-1", "config/exact.yml", MutationOperation.WRITE,
                        MutationSourceKind.DESIGN_DELIVERABLE, "DS-L010", "config/exact.yml",
                        "6".repeat(64), List.of(), List.of())), List.of(), List.of());
        DesignerAcceptanceStagePathPlanner.Selection selection =
                new DesignerAcceptanceStagePathPlanner.Selection(
                        List.of("config/exact.yml"), List.of("config/exact.yml"));

        DesignerMutationStageBinder.Resolution result = new DesignerMutationStageBinder().bind(facts, List.of(
                new DesignerMutationStageBinder.StageInput("精确配置", List.of(0), selection)));

        assertThat(result.assignments()).singleElement().satisfies(assignment -> {
            assertThat(assignment.stageIndexes()).containsExactly(0);
            assertThat(assignment.source())
                    .isEqualTo(DesignerMutationStageBinder.BindingSource.EXACT_FACT_REFERENCE);
        });
        assertThat(result.normalizations()).contains("MUTATION_PATH_EXACT_FACT_REFERENCE_BOUND");
    }

    @Test
    void v7KeepsMultipleExactFactOwnersBlocked() {
        Fact exactFact = new Fact(0, FactKind.DELIVERABLE, "config/exact.yml", null, null,
                null, null, "修改：精确配置", "DS-L010", "config/exact.yml", "4".repeat(64));
        Catalog facts = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "5".repeat(64), true,
                List.of(exactFact), List.of(),
                List.of(new MutationObligation(0, "MO-1", "config/exact.yml", MutationOperation.WRITE,
                        MutationSourceKind.DESIGN_DELIVERABLE, "DS-L010", "config/exact.yml",
                        "6".repeat(64), List.of(), List.of())), List.of(), List.of());
        DesignerAcceptanceStagePathPlanner.Selection selection =
                new DesignerAcceptanceStagePathPlanner.Selection(
                        List.of("config/exact.yml"), List.of("config/exact.yml"));

        DesignerMutationStageBinder.Resolution result = new DesignerMutationStageBinder().bind(facts, List.of(
                new DesignerMutationStageBinder.StageInput("适配配置", List.of(0), selection),
                new DesignerMutationStageBinder.StageInput("回退配置", List.of(0), selection)));

        assertThat(result.assignments()).isEmpty();
        assertThat(result.unresolved()).singleElement().satisfies(unresolved -> {
            assertThat(unresolved.candidateStageIndexes()).containsExactly(0, 1);
            assertThat(unresolved.reason()).contains("适配配置", "回退配置");
        });
        assertThat(result.normalizations()).contains("MUTATION_PATH_AMBIGUOUS_STAGE_BLOCKED");
    }

    @Test
    void v7ExtractsOnlyPositiveProjectMutationObligationsWithExactSourceEvidence() {
        String requirement = """
                请完成外部适配能力：
                - 修改 `src/main/java/example/Service.java`
                - 新增 `config/external-adapter.yml`
                - 将 `config/adapter-old.yml` 重命名为 `config/adapter.yml`
                - 删除 `config/obsolete.yml`
                - `docs/example.yml` 只是示例，不要修改
                - 不得写入 `/tmp/external-adapter.yml`
                """;
        String design = """
                ## 目标与范围
                修改 Service 并补齐配置测试。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 修改 | src/main/java/example/Service.java | 服务实现 |
                | 新增测试 | src/test/java/example/ServiceTest.java | 聚焦验收 |
                | 保持不变 | docs/example.yml | 文档示例 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 外部适配生效 | 已配置 adapter | 调用服务 | 返回适配结果 | 示例文档不变 |

                ## 验收约束
                ServiceTest 必须独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 服务实现 | 实现并验证服务 | 外部适配生效；src/main/java/example/Service.java；src/test/java/example/ServiceTest.java | 无 |
                """;
        Catalog base = extractor.extract("WP-1", 1, design, CONTRACT_VERSION_V7);

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base, requirement,
                List.of("config/**"), List.of("docs/**"), List.of("config/external-adapter.yml"));

        assertThat(enriched.mutationObligations())
                .extracting(MutationObligation::pathRule, MutationObligation::operation)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("src/main/java/example/Service.java", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple("config/external-adapter.yml", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple("config/adapter-old.yml", MutationOperation.MOVE_SOURCE),
                        org.assertj.core.groups.Tuple.tuple("config/adapter.yml", MutationOperation.MOVE_DESTINATION),
                        org.assertj.core.groups.Tuple.tuple("config/obsolete.yml", MutationOperation.DELETE_REQUEST),
                        org.assertj.core.groups.Tuple.tuple("src/test/java/example/ServiceTest.java", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple("config/**", MutationOperation.WRITE));
        assertThat(enriched.mutationObligations()).allSatisfy(obligation -> {
            assertThat(obligation.obligationId()).startsWith("MO-");
            assertThat(obligation.sourceRef()).isNotBlank();
            assertThat(obligation.sourceExcerpt()).isNotBlank();
            assertThat(obligation.sourceSha256()).matches("[0-9a-f]{64}");
            assertThat(obligation.candidateStageIndexes()).isEmpty();
            assertThat(obligation.assignedStageIndexes()).isEmpty();
        });
        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .doesNotContain("docs/example.yml", "/tmp/external-adapter.yml", "tmp/external-adapter.yml");
        assertThat(enriched.mutationIssues()).isEmpty();
    }

    @Test
    void v7KeepsPositiveMutationScopeAcrossEveryBareMarkdownListPath() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base, """
                需要修改以下文件：
                - `src/main/java/example/Service.java`
                - `config/external-adapter.yml`
                """, List.of(), List.of(), List.of());

        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("src/main/java/example/Service.java", "config/external-adapter.yml");
    }

    @Test
    void v7BindsPositiveAndNegativePathsToTheirOwnClauses() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "修改 `src/main/java/example/Service.java`，但不要修改 `config/reference.yml`",
                List.of(), List.of(), List.of());

        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("src/main/java/example/Service.java")
                .doesNotContain("config/reference.yml");
        assertThat(enriched.mutationIssues()).isEmpty();
    }

    @Test
    void v7RecognizesObjectInsertedAndCommonNegatedMutationPhrases() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();

        for (String requirement : List.of(
                "请勿修改 config/a.yml",
                "禁止对 config/a.yml 进行修改",
                "不要对 config/a.yml 做任何修改",
                "无需在 config/a.yml 中新增",
                "无须修改 config/a.yml",
                "不对 config/a.yml 进行修改",
                "不能修改 config/a.yml",
                "不允许修改 config/a.yml",
                "不需要修改 config/a.yml",
                "不用修改 config/a.yml",
                "不准修改 config/a.yml",
                "不许修改 config/a.yml",
                "避免修改 config/a.yml",
                "防止对 config/a.yml 进行修改")) {
            Catalog enriched = mutationExtractor.extract(base, requirement,
                    List.of(), List.of(), List.of());

            assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                    .as(requirement).doesNotContain("config/a.yml");
            assertThat(enriched.mutationIssues()).as(requirement).isEmpty();
        }
    }

    @Test
    void v7DoesNotTurnALongObjectInsertedNegationIntoAWrite() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        String path = "config/" + "nested/".repeat(30) + "reference.yml";

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "禁止对 " + path + " 进行修改", List.of(), List.of(), List.of());

        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .doesNotContain(path);
        assertThat(enriched.mutationIssues()).isEmpty();
    }

    @Test
    void v7FailsClosedWhenScopedNegationAndPositiveMutationShareOneUnseparatedClause() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "不得对 config/a.yml 进行修改同时修改 config/b.yml",
                List.of(), List.of(), List.of());

        assertThat(enriched.mutationIssues()).contains("AMBIGUOUS_MUTATION_PATH_SCOPE");
        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .doesNotContain("config/a.yml", "config/b.yml");
    }

    @Test
    void v7KeepsControlledCannotModifyFactsNegative() {
        Fact negative = new Fact(0, FactKind.SCOPE, "config/a.yml", null, null,
                null, null, "不需要修改：安全边界", "DS-L001", "受控负向事实", "1".repeat(64));
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(negative), List.of(), List.of());

        Catalog enriched = new DesignerMutationObligationExtractor().extract(catalog,
                "修改 config/a.yml", List.of(), List.of(), List.of());

        assertThat(enriched.mutationIssues()).contains("AMBIGUOUS_MUTATION_PATH_SCOPE");
    }

    @Test
    void v7BlocksAnUnseparatedMixedMutationScopeInsteadOfDroppingEveryPath() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "修改 `src/main/java/example/Service.java` 不要修改 `config/reference.yml`",
                List.of(), List.of(), List.of());
        CapabilityCatalog capabilities = registry.build(enriched, role("software-java", List.of("java")),
                PIN_TRANS_DESIGN);

        assertThat(enriched.mutationIssues()).containsExactly("AMBIGUOUS_MUTATION_PATH_SCOPE");
        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .doesNotContain("src/main/java/example/Service.java", "config/reference.yml");
        assertThat(new DesignerAcceptanceFastPathResolver().resolve(enriched, capabilities).outcome())
                .isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.DESIGN_INCOMPLETE);
    }

    @Test
    void v7BlocksReverseOrderedUnseparatedMixedMutationScopeInsteadOfTreatingEveryPathAsReadOnly() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "保持 `docs/reference.yml` 不变并修改 `src/main/java/example/Service.java`",
                List.of(), List.of(), List.of());

        assertThat(enriched.mutationIssues()).containsExactly("AMBIGUOUS_MUTATION_PATH_SCOPE");
        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .doesNotContain("docs/reference.yml", "src/main/java/example/Service.java");
    }

    @Test
    void v7BlocksCrossLinePositiveAndNegativeMutationScopesForTheSamePath() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base, """
                需要修改以下文件：
                - `src/main/java/example/Service.java`
                以下文件必须保持不变：
                - `src/main/java/example/Service.java`
                """, List.of(), List.of(), List.of());

        assertThat(enriched.mutationIssues()).contains("AMBIGUOUS_MUTATION_PATH_SCOPE");
        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("src/main/java/example/Service.java");
    }

    @Test
    void v7DoesNotTurnUncOrPureSymbolsIntoProjectMutationPaths() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();

        assertThatThrownBy(() -> mutationExtractor.extract(base,
                "修改 `\\\\server\\share\\external-adapter.yml`", List.of(), List.of(), List.of()))
                .isInstanceOfSatisfying(BadRequestException.class, error ->
                        assertThat(error.code()).isEqualTo("PROJECT_ROOT_EXTERNAL_PATH"));
        Catalog symbols = mutationExtractor.extract(base, "修改 `Service.handle`", List.of(), List.of(), List.of());
        assertThat(symbols.mutationObligations()).extracting(MutationObligation::pathRule)
                .doesNotContain("Service.handle");
        Catalog uri = mutationExtractor.extract(base, "更新 https://example.com/api 客户端", List.of(), List.of(), List.of());
        assertThat(uri.mutationObligations()).extracting(MutationObligation::pathRule)
                .noneMatch(path -> path.contains("example.com"));
    }

    @Test
    void v7RejectsWindowsRootAndFileUriMutationsAsProjectExternal() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();

        for (String requirement : List.of("修改 `\\outside\\x.yml`", "修改 `C:outside\\x.yml`",
                "修改 `file:///tmp/x.yml`")) {
            assertThatThrownBy(() -> mutationExtractor.extract(base, requirement,
                    List.of(), List.of(), List.of()))
                    .isInstanceOfSatisfying(BadRequestException.class, error -> {
                        assertThat(error.code()).isEqualTo("PROJECT_ROOT_EXTERNAL_PATH");
                        assertThat(error.getMessage()).doesNotContain("outside").doesNotContain("/tmp/x.yml");
                    });
        }
    }

    @Test
    void v7FreezesCommonRootFileMutationsWithoutTurningPureSymbolsIntoPaths() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "修改 Dockerfile，更新 LICENSE，修改 NOTICE，更新 mvnw，修改 .gitignore，修改 Service.handle",
                List.of(), List.of(), List.of());

        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("Dockerfile", "LICENSE", "NOTICE", "mvnw", ".gitignore")
                .doesNotContain("Service.handle");
    }

    @Test
    void v7RecognizesCommonPositiveVerbsAndFailsClosedOnUnclassifiedPathScope() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();

        Catalog positive = mutationExtractor.extract(base,
                "修复 config/a.yml，变更 config/b.yml，替换 config/c.yml，编辑 config/d.yml",
                List.of(), List.of(), List.of());
        Catalog ambiguous = mutationExtractor.extract(base, "处理 config/unclassified.yml",
                List.of(), List.of(), List.of());

        assertThat(positive.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("config/a.yml", "config/b.yml", "config/c.yml", "config/d.yml");
        assertThat(ambiguous.mutationIssues()).contains("AMBIGUOUS_MUTATION_PATH_SCOPE");
    }

    @Test
    void v7RejectsUnclassifiedExternalPathsInsteadOfSilentlyDroppingThem() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);

        assertThatThrownBy(() -> new DesignerMutationObligationExtractor().extract(base,
                "处理 /tmp/unclassified.yml", List.of(), List.of(), List.of()))
                .isInstanceOfSatisfying(BadRequestException.class, error ->
                        assertThat(error.code()).isEqualTo("PROJECT_ROOT_EXTERNAL_PATH"));
    }

    @Test
    void v7TreatsApiRoutesAsSymbolsWhileStillFreezingRepositoryFilesInTheSameClause() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();

        Catalog routeOnly = mutationExtractor.extract(base, "实现 /api/users 接口",
                List.of(), List.of(), List.of());
        Catalog mixed = mutationExtractor.extract(base,
                "实现 /v1/users/{id} 端点并修改 src/main/java/example/UserController.java",
                List.of(), List.of(), List.of());

        assertThat(routeOnly.mutationObligations()).extracting(MutationObligation::pathRule)
                .noneMatch(path -> path.startsWith("api/") || path.startsWith("v1/"));
        assertThat(mixed.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("src/main/java/example/UserController.java")
                .noneMatch(path -> path.startsWith("api/") || path.startsWith("v1/"));
    }

    @Test
    void v7TreatsHttpMethodRoutesAndControlledRouteCellsAsSymbols() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        Catalog requirement = new DesignerMutationObligationExtractor().extract(base,
                "实现 GET /api/users 并修改 src/main/java/example/UserController.java",
                List.of(), List.of(), List.of());
        Fact route = new Fact(0, FactKind.DELIVERABLE, "/v1/users", null, null,
                null, null, "新增接口：用户查询", "DS-L001", "受控接口符号", "1".repeat(64));
        Catalog routeCatalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(route), List.of(), List.of());
        Catalog controlled = new DesignerMutationObligationExtractor().extract(routeCatalog, "",
                List.of(), List.of(), List.of());

        assertThat(requirement.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("src/main/java/example/UserController.java")
                .noneMatch(path -> path.startsWith("api/") || path.startsWith("v1/"));
        assertThat(controlled.mutationObligations()).isEmpty();
        assertThat(controlled.mutationIssues()).isEmpty();
    }

    @Test
    void v7TreatsHanSlashConceptsAsSymbolsWithoutHidingARepositoryPath() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        String requirement = "实现发布/订阅能力并修改 src/main/java/example/EventBus.java";
        assertThat(DesignerMutationPolarity.negative(requirement)).isFalse();
        assertThat(new DesignerRequirementMutationActionPolicy().hasMultipleOperations(requirement,
                List.of("发布/订阅", "src/main/java/example/EventBus.java"))).isFalse();
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();
        assertThat(mutationExtractor.extract(base, "", List.of(), List.of(), List.of()).mutationIssues()).isEmpty();
        Catalog enriched = mutationExtractor.extract(base,
                requirement,
                List.of(), List.of(), List.of());

        assertThat(enriched.mutationIssues()).isEmpty();
        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("src/main/java/example/EventBus.java")
                .doesNotContain("发布/订阅");
    }

    @Test
    void v7DoesNotTreatABareSlashSeparatedModuleReferenceAsAnUnclassifiedPath() {
        Catalog base = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(), List.of(), List.of());
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();

        Catalog reference = mutationExtractor.extract(base,
                "项目现有 chain/cache/state 均采用注解+工厂注册模式。",
                List.of(), List.of(), List.of());
        Catalog explicitMutation = mutationExtractor.extract(base,
                "修改 chain/cache/state 目录",
                List.of(), List.of(), List.of());
        Catalog realUnclassifiedPath = mutationExtractor.extract(base,
                "处理 src/main/java/example",
                List.of(), List.of(), List.of());

        assertThat(reference.mutationIssues()).isEmpty();
        assertThat(reference.mutationObligations()).isEmpty();
        assertThat(explicitMutation.mutationIssues()).isEmpty();
        assertThat(explicitMutation.mutationObligations())
                .extracting(MutationObligation::pathRule, MutationObligation::operation)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "chain/cache/state", MutationOperation.WRITE));
        assertThat(realUnclassifiedPath.mutationIssues()).contains("AMBIGUOUS_MUTATION_PATH_SCOPE");
    }

    @Test
    void v7IgnoresControlledHanSlashSymbolsWithoutCreatingAPathGap() {
        Fact symbol = new Fact(0, FactKind.DELIVERABLE, "发布/订阅", null, null,
                null, null, "新增能力：进程内事件投递", "DS-L001", "受控业务符号", "1".repeat(64));
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(symbol), List.of(), List.of());

        Catalog enriched = new DesignerMutationObligationExtractor().extract(catalog, "",
                List.of(), List.of(), List.of());

        assertThat(enriched.mutationObligations()).isEmpty();
        assertThat(enriched.mutationIssues()).isEmpty();
    }

    @Test
    void v7KeepsNegatedDeleteAndMoveAsSafetyConstraintsButFreezesPositiveOperations() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        Catalog enriched = new DesignerMutationObligationExtractor().extract(base, """
                不得删除 config/a.yml
                禁止移除 config/b.yml
                不要重命名 config/c.yml
                删除 config/d.yml
                将 config/e.yml 重命名为 config/f.yml
                """, List.of(), List.of(), List.of());

        assertThat(enriched.mutationObligations())
                .extracting(MutationObligation::pathRule, MutationObligation::operation)
                .doesNotContain(
                        org.assertj.core.groups.Tuple.tuple("config/a.yml", MutationOperation.DELETE_REQUEST),
                        org.assertj.core.groups.Tuple.tuple("config/b.yml", MutationOperation.DELETE_REQUEST),
                        org.assertj.core.groups.Tuple.tuple("config/c.yml", MutationOperation.MOVE_SOURCE))
                .contains(
                        org.assertj.core.groups.Tuple.tuple("config/d.yml", MutationOperation.DELETE_REQUEST),
                        org.assertj.core.groups.Tuple.tuple("config/e.yml", MutationOperation.MOVE_SOURCE),
                        org.assertj.core.groups.Tuple.tuple("config/f.yml", MutationOperation.MOVE_DESTINATION));
    }

    @Test
    void v7BindsTheFirstExplicitPathActionInsteadOfBusinessMigrationOrDeleteNouns() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        Catalog enriched = new DesignerMutationObligationExtractor().extract(base, """
                修改 src/main/java/example/MigrationService.java 的迁移逻辑
                新增 src/main/java/example/DeleteMarker.java 的删除标记
                迁移逻辑需要修改 src/main/java/example/ReverseMigrationService.java
                删除标记需要新增 src/main/java/example/ReverseDeleteMarker.java
                为数据库迁移修改 src/main/resources/db/migration/V46__path.sql
                src/main/java/example/PathFirstMigrationService.java 的迁移逻辑需要修改
                src/main/java/example/PathFirstDeleteMarker.java 的删除标记需要新增
                src/main/java/example/DeleteService.java 的删除功能需要新增
                src/main/java/example/MigrationTask.java 的迁移任务需要修改
                修改 src/main/java/example/BusinessDeleteService.java 以实现删除功能
                修改 src/main/java/example/BusinessMigrationService.java 以实现数据库迁移
                """, List.of(), List.of(), List.of());

        assertThat(enriched.mutationObligations())
                .filteredOn(obligation -> obligation.pathRule().contains("MigrationService")
                        || obligation.pathRule().contains("DeleteMarker")
                        || obligation.pathRule().contains("DeleteService")
                        || obligation.pathRule().contains("MigrationTask")
                        || obligation.pathRule().contains("BusinessDeleteService")
                        || obligation.pathRule().contains("BusinessMigrationService")
                        || obligation.pathRule().contains("PathFirst")
                        || obligation.pathRule().contains("V46__path.sql"))
                .extracting(MutationObligation::pathRule, MutationObligation::operation)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "src/main/java/example/MigrationService.java", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple(
                                "src/main/java/example/DeleteMarker.java", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple(
                                "src/main/java/example/ReverseMigrationService.java", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple(
                                "src/main/java/example/ReverseDeleteMarker.java", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple(
                                "src/main/resources/db/migration/V46__path.sql", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple(
                                "src/main/java/example/PathFirstMigrationService.java", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple(
                                "src/main/java/example/PathFirstDeleteMarker.java", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple(
                                "src/main/java/example/DeleteService.java", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple(
                                "src/main/java/example/MigrationTask.java", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple(
                                "src/main/java/example/BusinessDeleteService.java", MutationOperation.WRITE),
                        org.assertj.core.groups.Tuple.tuple(
                                "src/main/java/example/BusinessMigrationService.java", MutationOperation.WRITE));
    }

    @Test
    void v7FailsClosedWhenOneClauseAssignsDifferentOperationsToMultiplePaths() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();

        for (String requirement : List.of(
                "修改 config/a.yml 并删除 config/b.yml",
                "删除 config/a.yml 并新增 config/b.yml")) {
            Catalog enriched = mutationExtractor.extract(base, requirement,
                    List.of(), List.of(), List.of());

            assertThat(enriched.mutationIssues()).as(requirement)
                    .contains("AMBIGUOUS_MUTATION_PATH_SCOPE");
            assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                    .as(requirement).doesNotContain("config/a.yml", "config/b.yml");
        }
    }

    @Test
    void v7FailsClosedWhenOnePathHasBothMoveAndWriteCommands() {
        Catalog base = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(), List.of(), List.of());
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();

        for (String requirement : List.of(
                "移动并修改 config/a.yml",
                "重命名后修改 config/a.yml",
                "实现删除 config/a.yml",
                "实现删除旧配置文件 config/obsolete.yml",
                "实现删除后台配置文件 config/backend.yml",
                "实现删除后端配置文件 config/server.yml",
                "实现删除并发配置文件 config/concurrency.yml",
                "实现删除待更新配置文件 config/pending.yml",
                "实现删除自动生成配置文件 config/generated.yml",
                "实现删除变更记录文件 config/history.yml",
                "实现删除生成记录 config/generated-records.yml",
                "删除生成文件 config/generated.yml",
                "删除更新记录文件 config/update-history.yml",
                "删除修改记录文件 config/change-history.yml",
                "为清理删除生成文件 config/purpose-generated.yml",
                "为了清理删除更新记录文件 config/purpose-update.yml",
                "为兼容迁移更新配置文件 config/app.yml")) {
            Catalog enriched = mutationExtractor.extract(base, requirement,
                    List.of(), List.of(), List.of());

            assertThat(enriched.mutationIssues()).as(requirement)
                    .contains("AMBIGUOUS_MUTATION_PATH_SCOPE");
            assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                    .as(requirement).noneMatch(path -> path.startsWith("config/"));
        }
    }

    @Test
    void v7DoesNotTreatBusinessMutationNounsAsSecondPathOperations() {
        Catalog base = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(), List.of(), List.of());
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();

        for (String requirement : List.of(
                "为数据库迁移修改 db/V46.sql 和 src/Test.java",
                "为删除标记新增 src/Marker.java 和 src/MarkerTest.java",
                "实现删除能力 src/main/java/example/DeleteService.java",
                "实现数据库迁移脚本 db/V46.sql")) {
            Catalog enriched = mutationExtractor.extract(base, requirement,
                    List.of(), List.of(), List.of());

            assertThat(enriched.mutationIssues()).as(requirement).isEmpty();
            assertThat(enriched.mutationObligations()).extracting(MutationObligation::operation)
                    .as(requirement).containsOnly(MutationOperation.WRITE);
        }
    }

    @Test
    void v7DoesNotTreatMutationWordsInsideRepositoryPathsAsCommands() {
        Catalog base = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(), List.of(), List.of());

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "修改 src/删除记录/config.yml", List.of(), List.of(), List.of());

        assertThat(enriched.mutationIssues()).isEmpty();
        assertThat(enriched.mutationObligations())
                .extracting(MutationObligation::pathRule, MutationObligation::operation)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "src/删除记录/config.yml", MutationOperation.WRITE));
    }

    @Test
    void v7KeepsCommandsOutsideHanSlashBusinessSymbols() {
        Catalog base = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(), List.of(), List.of());

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "实现新增/删除能力并修改 src/main/java/example/Feature.java",
                List.of(), List.of(), List.of());

        assertThat(enriched.mutationIssues()).isEmpty();
        assertThat(enriched.mutationObligations())
                .extracting(MutationObligation::pathRule, MutationObligation::operation)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "src/main/java/example/Feature.java", MutationOperation.WRITE));
    }

    @Test
    void v7SuppressesClauseAndListExamplesWithoutSuppressingRealPathsNamedExample() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        Catalog enriched = new DesignerMutationObligationExtractor().extract(base, """
                例如修改 config/clause-example.yml
                示例：
                - 修改 config/list-example.yml
                修改 src/main/java/example/RealService.java
                """, List.of(), List.of(), List.of());

        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("src/main/java/example/RealService.java")
                .doesNotContain("config/clause-example.yml", "config/list-example.yml");
    }

    @Test
    void v7IgnoresAPureExampleThatListsMultiplePaths() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "例如修改 config/a.yml 和 config/b.yml", List.of(), List.of(), List.of());

        assertThat(enriched.mutationIssues()).isEmpty();
        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .doesNotContain("config/a.yml", "config/b.yml");
    }

    @Test
    void v7FailsClosedWhenOneClauseMixesAWritePathWithAnInvariant() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "修改 config/a.yml 并保持外部行为不变", List.of(), List.of(), List.of());

        assertThat(enriched.mutationIssues()).contains("AMBIGUOUS_MUTATION_PATH_SCOPE");
        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .doesNotContain("config/a.yml");
    }

    @Test
    void v7DoesNotTreatInvariantOrReadOnlyCapabilityNounsAsPathNegation() {
        Catalog base = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(), List.of(), List.of());
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();

        Catalog invariant = mutationExtractor.extract(base,
                "修改 config/rules.yml 以支持不变量校验", List.of(), List.of(), List.of());
        Catalog readOnlyView = mutationExtractor.extract(base,
                "修改 config/view.yml 实现只读视图", List.of(), List.of(), List.of());
        Catalog readOnlyDatasource = mutationExtractor.extract(base,
                "修改 config/datasource.yml 实现只读数据源", List.of(), List.of(), List.of());
        Catalog keepReadOnlyView = mutationExtractor.extract(base,
                "修改 config/view.yml 以保持只读视图可用", List.of(), List.of(), List.of());
        Catalog pageReadOnlyMode = mutationExtractor.extract(base,
                "修改 config/ui.yml 支持把页面设为只读模式", List.of(), List.of(), List.of());

        assertThat(invariant.mutationIssues()).isEmpty();
        assertThat(invariant.mutationObligations()).extracting(MutationObligation::pathRule)
                .containsExactly("config/rules.yml");
        assertThat(readOnlyView.mutationIssues()).isEmpty();
        assertThat(readOnlyView.mutationObligations()).extracting(MutationObligation::pathRule)
                .containsExactly("config/view.yml");
        assertThat(readOnlyDatasource.mutationIssues()).isEmpty();
        assertThat(readOnlyDatasource.mutationObligations()).extracting(MutationObligation::pathRule)
                .containsExactly("config/datasource.yml");
        assertThat(keepReadOnlyView.mutationIssues()).isEmpty();
        assertThat(keepReadOnlyView.mutationObligations()).extracting(MutationObligation::pathRule)
                .containsExactly("config/view.yml");
        assertThat(pageReadOnlyMode.mutationIssues()).isEmpty();
        assertThat(pageReadOnlyMode.mutationObligations()).extracting(MutationObligation::pathRule)
                .containsExactly("config/ui.yml");
    }

    @Test
    void v7MakesPathBearingNeutralDesignFactsAmbiguousAndNegativeFactsUnableToAuthorizeWrites() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        Fact template = base.facts().getFirst();
        Fact neutral = new Fact(template.index(), FactKind.DELIVERABLE, "config/a.yml", null, null,
                null, null, "影响：中性说明", template.sourceRef(), template.sourceExcerpt(),
                template.sourceSha256());
        Fact negative = new Fact(template.index(), FactKind.DELIVERABLE, "config/a.yml", null, null,
                null, null, "保持不变：安全边界", template.sourceRef(), template.sourceExcerpt(),
                template.sourceSha256());
        Catalog neutralCatalog = new Catalog(base.contractVersion(), base.workPackageId(), base.designRevision(),
                base.designSha256(), base.controlledFormat(), List.of(neutral), base.stageHints(), base.issues());
        Catalog negativeCatalog = new Catalog(base.contractVersion(), base.workPackageId(), base.designRevision(),
                base.designSha256(), base.controlledFormat(), List.of(negative), base.stageHints(), base.issues());

        Catalog neutralResult = new DesignerMutationObligationExtractor().extract(neutralCatalog, "",
                List.of(), List.of(), List.of());
        Catalog negativeResult = new DesignerMutationObligationExtractor().extract(negativeCatalog,
                "修改 config/a.yml", List.of(), List.of(), List.of());
        DesignerAcceptanceStagePathPlanner.Selection selection = new DesignerAcceptanceStagePathPlanner()
                .select(negativeCatalog, List.of(negative.index()), List.of(),
                        role("software-java", List.of("java")));

        assertThat(neutralResult.mutationIssues()).contains("AMBIGUOUS_MUTATION_PATH_SCOPE");
        assertThat(negativeResult.mutationIssues()).contains("AMBIGUOUS_MUTATION_PATH_SCOPE");
        assertThat(selection.justifiedPaths()).isEmpty();
    }

    @Test
    void v7FreezesControlledScopeDirectoriesAsSubtreeRules() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        Fact template = base.facts().getFirst();
        Fact scope = new Fact(template.index(), FactKind.SCOPE, "config/templates", null, null,
                null, null, "范围内：模板目录", template.sourceRef(), template.sourceExcerpt(),
                template.sourceSha256());
        Catalog catalog = new Catalog(base.contractVersion(), base.workPackageId(), base.designRevision(),
                base.designSha256(), base.controlledFormat(), List.of(scope), base.stageHints(), base.issues());

        Catalog enriched = new DesignerMutationObligationExtractor().extract(catalog, "",
                List.of(), List.of(), List.of());

        assertThat(enriched.mutationObligations()).singleElement().satisfies(obligation -> {
            assertThat(obligation.pathRule()).isEqualTo("config/templates");
            assertThat(obligation.pathKind()).isEqualTo(MutationPathKind.PATH_RULE);
        });
    }

    @Test
    void v7FreezesRequirementAndDeliverableDirectoriesAsSubtreeRules() {
        Catalog base = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(), List.of(), List.of());
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();
        Catalog requirement = mutationExtractor.extract(base, "新增 config/templates 目录",
                List.of(), List.of(), List.of());
        Catalog deliverable = mutationExtractor.extract(base, "",
                List.of(), List.of(), List.of("config/templates"));
        MutationObligation requirementDirectory = requirement.mutationObligations().stream()
                .filter(item -> "config/templates".equals(item.pathRule())).findFirst().orElseThrow();
        MutationObligation deliverableDirectory = deliverable.mutationObligations().stream()
                .filter(item -> "config/templates".equals(item.pathRule())).findFirst().orElseThrow();
        CompactStage shallow = policyStage(List.of("config/*"), List.of());
        CompactStage deep = policyStage(List.of("config/**"), List.of());

        assertThat(requirementDirectory.pathKind()).isEqualTo(MutationPathKind.PATH_RULE);
        assertThat(deliverableDirectory.pathKind()).isEqualTo(MutationPathKind.PATH_RULE);
        assertThat(new MutationConservationPolicy().evaluate(requirement, List.of(shallow),
                List.of(shallow.allowedPaths())).passed()).isFalse();
        assertThat(new MutationConservationPolicy().evaluate(requirement, List.of(deep),
                List.of(deep.allowedPaths())).passed()).isTrue();
    }

    @Test
    void v7FreezesExplicitRootDirectoriesAcrossRequirementControlledAndFrozenSources() {
        Catalog empty = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(), List.of(), List.of());
        Fact controlledDirectory = new Fact(0, FactKind.DELIVERABLE, "config", null, null,
                null, null, "新增目录：模板根目录", "DS-L001", "受控根目录", "1".repeat(64));
        Catalog controlledBase = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(controlledDirectory), List.of(), List.of());
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();

        Catalog requirement = mutationExtractor.extract(empty, "新增 config 目录",
                List.of(), List.of(), List.of());
        Catalog controlled = mutationExtractor.extract(controlledBase, "",
                List.of(), List.of(), List.of());
        Catalog frozen = mutationExtractor.extract(empty, "",
                List.of(), List.of(), List.of("config"));
        Catalog symbol = mutationExtractor.extract(empty, "",
                List.of(), List.of(), List.of("backend"));
        DesignerAcceptanceStagePathPlanner.Selection controlledSelection =
                new DesignerAcceptanceStagePathPlanner().select(controlledBase, List.of(0), List.of(),
                        role("software-java", List.of("java")));

        for (Catalog catalog : List.of(requirement, controlled, frozen)) {
            assertThat(catalog.mutationObligations()).singleElement().satisfies(obligation -> {
                assertThat(obligation.pathRule()).isEqualTo("config");
                assertThat(obligation.pathKind()).isEqualTo(MutationPathKind.PATH_RULE);
            });
        }
        assertThat(symbol.mutationObligations()).isEmpty();
        assertThat(controlledSelection.paths()).containsExactly("config");
        assertThat(controlledSelection.justifiedPaths()).containsExactly("config");
    }

    @Test
    void v7DoesNotTreatAnExactPathAsOverlappingItsDescendantExclusionRule() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "修改 config/a.yml", List.of(), List.of("config/a.yml/child/**"), List.of());

        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("config/a.yml");
        assertThat(enriched.mutationIssues()).doesNotContain("MUTATION_PATH_SCOPE_CONFLICT");
    }

    @Test
    void v7PreservesBareRootScopeOutAsAConflictAndRuntimeForbiddenRule() {
        Catalog v7 = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(), List.of(), List.of());
        Catalog v6 = new Catalog(CONTRACT_VERSION_V6, "WP-1", 1, "0".repeat(64), true,
                List.of(), List.of(), List.of());

        Catalog enriched = new DesignerMutationObligationExtractor().extract(v7,
                "新增 config 目录", List.of(), List.of("config"), List.of());
        DesignerAcceptanceStagePathPlanner planner = new DesignerAcceptanceStagePathPlanner();

        assertThat(enriched.mutationIssues()).contains("MUTATION_PATH_SCOPE_CONFLICT");
        assertThat(planner.forbiddenPaths(v7, List.of("config"))).contains("config");
        assertThat(planner.forbiddenPaths(v6, List.of("config"))).doesNotContain("config");
    }

    @Test
    void v7DetectsControlledPositiveAndNegativeRootDirectoryConflict() {
        Fact positive = new Fact(0, FactKind.DELIVERABLE, "config", null, null,
                null, null, "新增目录：模板", "DS-L001", "正向目录", "1".repeat(64));
        Fact negative = new Fact(1, FactKind.SCOPE, "config", null, null,
                null, null, "范围外目录：禁止修改", "DS-L002", "负向目录", "2".repeat(64));
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(positive, negative), List.of(), List.of());

        Catalog enriched = new DesignerMutationObligationExtractor().extract(catalog, "",
                List.of(), List.of(), List.of());

        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("config");
        assertThat(enriched.mutationIssues()).contains("AMBIGUOUS_MUTATION_PATH_SCOPE");
    }

    @Test
    void v7PreservesTheStricterScopeRuleWhenTheRequirementNamesTheSameExactPath() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "修改 config/cache.v1", List.of("config/cache.v1"), List.of(), List.of());
        CompactStage shallow = policyStage(List.of("config/*.v1"), List.of());

        MutationConservationPolicy.Evaluation evaluation = new MutationConservationPolicy().evaluate(
                enriched, List.of(shallow), List.of(shallow.allowedPaths()));

        assertThat(enriched.mutationObligations()).filteredOn(obligation ->
                        "config/cache.v1".equals(obligation.pathRule()))
                .extracting(MutationObligation::pathKind)
                .containsExactly(MutationPathKind.EXACT_PATH, MutationPathKind.PATH_RULE);
        assertThat(evaluation.passed()).isFalse();
    }

    @Test
    void javaProductionDirectoryRulesKeepTheFocusedTestGateClassification() {
        DesignerAcceptanceStagePathPlanner planner = new DesignerAcceptanceStagePathPlanner();
        WorkPackageRoleService.View javaRole = role("software-java", List.of("java"));

        assertThat(planner.implementationKind(javaRole, List.of("src/main/java")))
                .isEqualTo(io.opencode.loopper.domain.ImplementationKind.JAVA_PRODUCTION);
        assertThat(planner.implementationKind(javaRole, List.of("module/src/main/java")))
                .isEqualTo(io.opencode.loopper.domain.ImplementationKind.JAVA_PRODUCTION);
        assertThat(planner.implementationKind(javaRole, List.of("src/test/java")))
                .isEqualTo(io.opencode.loopper.domain.ImplementationKind.JAVA_TEST_ONLY);
    }

    @Test
    void historicalV6StagePathsKeepTheirFrozenPrePolaritySemantics() {
        Fact negative = new Fact(0, FactKind.DELIVERABLE, "docs/reference.yml", null, null,
                null, null, "保持不变：历史边界", "DS-L001", "历史事实", "1".repeat(64));
        Fact neutral = new Fact(1, FactKind.DELIVERABLE, "config/legacy.yml", null, null,
                null, null, "影响：历史中性类型", "DS-L002", "历史事实", "2".repeat(64));
        Catalog v6 = new Catalog(CONTRACT_VERSION_V6, "WP-1", 1, "0".repeat(64), true,
                List.of(negative, neutral), List.of(), List.of());

        DesignerAcceptanceStagePathPlanner.Selection selection = new DesignerAcceptanceStagePathPlanner()
                .select(v6, List.of(0, 1), List.of(), role("software-java", List.of("java")));

        assertThat(selection.paths()).containsExactly("docs/reference.yml", "config/legacy.yml");
    }

    @Test
    void historicalV6StagePathsDoNotGainV7BareDirectorySemantics() {
        Fact directory = new Fact(0, FactKind.DELIVERABLE, "config", null, null,
                null, null, "新增目录：历史目录", "DS-L001", "历史事实", "1".repeat(64));
        Catalog v6 = new Catalog(CONTRACT_VERSION_V6, "WP-1", 1, "0".repeat(64), true,
                List.of(directory), List.of(), List.of());

        DesignerAcceptanceStagePathPlanner.Selection selection = new DesignerAcceptanceStagePathPlanner()
                .select(v6, List.of(0), List.of(), role("software-java", List.of("java")));

        assertThat(selection.paths()).containsExactly("src/main/java/**", "src/test/java/**");
        assertThat(selection.justifiedPaths()).isEmpty();
    }

    @Test
    void v7DoesNotInventConflictBetweenProvablyDisjointSiblingGlobs() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        Catalog enriched = new DesignerMutationObligationExtractor().extract(base,
                "修改 config/public-*.yml，但不要修改 config/secret-*.yml",
                List.of(), List.of(), List.of());

        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("config/public-*.yml");
        assertThat(enriched.mutationIssues()).isEmpty();
    }

    @Test
    void v7FreezesExplicitPackageGlobRulesWithoutTreatingThemAsPreciseStageOwnership() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);

        Catalog enriched = new DesignerMutationObligationExtractor().extract(base, "",
                List.of("config/**"), List.of(), List.of("scripts/*.sh"));

        assertThat(enriched.mutationObligations()).extracting(MutationObligation::pathRule)
                .contains("config/**", "scripts/*.sh");
    }

    @Test
    void v7HashesTheFullMutationSourceAndReusesControlledFactHashes() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();
        String prefix = "修改 config/a.yml " + "完整来源".repeat(1_100);

        MutationObligation first = mutationExtractor.extract(base, prefix + "甲",
                        List.of(), List.of(), List.of()).mutationObligations().stream()
                .filter(item -> "config/a.yml".equals(item.pathRule())).findFirst().orElseThrow();
        MutationObligation second = mutationExtractor.extract(base, prefix + "乙",
                        List.of(), List.of(), List.of()).mutationObligations().stream()
                .filter(item -> "config/a.yml".equals(item.pathRule())).findFirst().orElseThrow();
        Fact productionFact = base.facts().stream()
                .filter(fact -> "upfs-common/src/main/java/com/spdb/upfs/pin/PinTrans.java".equals(fact.title()))
                .findFirst().orElseThrow();
        MutationObligation inherited = mutationExtractor.extract(base, "",
                        List.of(), List.of(), List.of()).mutationObligations().stream()
                .filter(item -> productionFact.title().equals(item.pathRule())).findFirst().orElseThrow();

        assertThat(first.sourceExcerpt()).hasSize(4_000).isEqualTo(second.sourceExcerpt());
        assertThat(first.sourceSha256()).isNotEqualTo(second.sourceSha256());
        assertThat(inherited.sourceSha256()).isEqualTo(productionFact.sourceSha256());
    }

    @Test
    void v7RejectsExternalControlledDesignFactsSkipsSymbolsAndFreezesExplicitGlobs() {
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();
        Catalog symbol = extractor.extract("WP-1", 1,
                PIN_TRANS_DESIGN.replace(
                        "upfs-common/src/main/java/com/spdb/upfs/pin/PinTrans.java", "Service.handle"),
                CONTRACT_VERSION_V7);
        Catalog broad = extractor.extract("WP-1", 1,
                PIN_TRANS_DESIGN.replace(
                        "upfs-common/src/main/java/com/spdb/upfs/pin/PinTrans.java", "src/**"),
                CONTRACT_VERSION_V7);
        Catalog external = extractor.extract("WP-1", 1,
                PIN_TRANS_DESIGN.replace(
                        "upfs-common/src/main/java/com/spdb/upfs/pin/PinTrans.java", "/tmp/PinTrans.java"),
                CONTRACT_VERSION_V7);

        assertThat(mutationExtractor.extract(symbol, "", List.of(), List.of(), List.of())
                .mutationObligations()).extracting(MutationObligation::pathRule).doesNotContain("Service.handle");
        assertThat(mutationExtractor.extract(broad, "修改 src/**", List.of(), List.of(), List.of())
                .mutationObligations()).extracting(MutationObligation::pathRule).contains("src/**");
        assertThat(mutationExtractor.extract(broad, "修改 src/**", List.of(), List.of(), List.of())
                .mutationIssues()).isEmpty();
        assertThatThrownBy(() -> mutationExtractor.extract(external, "", List.of(), List.of(), List.of()))
                .isInstanceOfSatisfying(BadRequestException.class, error -> {
                    assertThat(error.code()).isEqualTo("PROJECT_ROOT_EXTERNAL_PATH");
                    assertThat(error.getMessage()).doesNotContain("/tmp/PinTrans.java");
                });
    }

    @Test
    void v7DoesNotUseCatchAllStageFactsAsMutationOwnerProof() {
        Catalog broad = extractor.extract("WP-1", 1,
                PIN_TRANS_DESIGN.replace(
                        "upfs-common/src/main/java/com/spdb/upfs/pin/PinTrans.java", "src/**"),
                CONTRACT_VERSION_V7);
        int broadFact = broad.facts().stream().filter(fact -> "src/**".equals(fact.title()))
                .map(Fact::index).findFirst().orElseThrow();

        DesignerAcceptanceStagePathPlanner.Selection selection = new DesignerAcceptanceStagePathPlanner()
                .select(broad, List.of(broadFact), List.of(), role("software-java", List.of("java")));

        assertThat(selection.paths()).contains("src/**");
        assertThat(selection.justifiedPaths()).isEmpty();
    }

    @Test
    void v7RejectsPositiveProjectExternalMutationInsteadOfDroppingItFromTheFrozenContract() {
        Catalog base = extractor.extract("WP-1", 1, PIN_TRANS_DESIGN, CONTRACT_VERSION_V7);

        assertThatThrownBy(() -> new DesignerMutationObligationExtractor().extract(base,
                "修改 `/tmp/external-adapter.yml`", List.of(), List.of(), List.of()))
                .isInstanceOfSatisfying(BadRequestException.class, error -> {
                    assertThat(error.code()).isEqualTo("PROJECT_ROOT_EXTERNAL_PATH");
                    assertThat(error.getMessage()).doesNotContain("/tmp/external-adapter.yml");
                });
    }

    @Test
    void v7SingleStageBindingAddsTheExactPathInsteadOfTreatingTechnologyFallbackAsProof() {
        String design = """
                ## 目标与范围
                实现 EventService 的事件投递行为。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 修改 | EventService | 服务符号 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 事件投递 | 已注册监听器 | 调用 publish | 监听器收到事件 | 注册表不变 |

                ## 验收约束
                EventServiceTest 必须独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 事件服务 | 实现并验证事件投递 | 事件投递；EventService | 无 |
                """;
        Catalog base = extractor.extract("WP-1", 1, design, CONTRACT_VERSION_V7);
        MutationObligation obligation = new MutationObligation(0, "MO-1",
                "src/main/java/example/EventService.java", MutationOperation.WRITE,
                MutationSourceKind.REQUIREMENT, "REQUIREMENT:L001", "修改 EventService.java",
                "2".repeat(64), List.of(), List.of());
        Catalog facts = new Catalog(base.contractVersion(), base.workPackageId(), base.designRevision(),
                base.designSha256(), base.controlledFormat(), base.facts(), base.stageHints(),
                List.of(obligation), List.of(), base.issues());
        WorkPackageRoleService.View role = role("software-java", List.of("java"));
        CapabilityCatalog capabilities = registry.build(facts, role, design);

        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), design, facts, capabilities,
                new CompactAcceptanceBindingPlan("事件服务验收", List.of(), List.of(), "待验证"),
                role, List.of(), List.of(), List.of("EventService"), 6, true);

        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.plan().stages()).singleElement().satisfies(stage ->
                assertThat(stage.allowedPaths()).contains(
                        "src/main/java/**", "src/test/java/**",
                        "src/main/java/example/EventService.java"));
        assertThat(result.normalizations()).contains("MUTATION_PATH_SINGLE_STAGE_BOUND");
    }

    @Test
    void v7SingleStageBindingAddsTheExactPathInsteadOfTreatingPackageScopeAsProof() {
        String design = """
                ## 目标与范围
                实现 EventService 的事件投递行为。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 修改 | EventService | 服务符号 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 事件投递 | 已注册监听器 | 调用 publish | 监听器收到事件 | 注册表不变 |

                ## 验收约束
                EventServiceTest 必须独立通过。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | 事件服务 | 实现并验证事件投递 | 事件投递；EventService | 无 |
                """;
        Catalog base = extractor.extract("WP-1", 1, design, CONTRACT_VERSION_V7);
        Catalog facts = mutationCatalog(base, List.of(
                mutation(0, "config/external-adapter.yml", MutationOperation.WRITE)));
        WorkPackageRoleService.View role = role("software-java", List.of("java"));
        CapabilityCatalog capabilities = registry.build(facts, role, design);

        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), design, facts, capabilities,
                new CompactAcceptanceBindingPlan("事件服务验收", List.of(), List.of(), "待验证"),
                role, List.of("config/**"), List.of(), List.of("EventService"), 6, true);

        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.plan().stages()).singleElement().satisfies(stage ->
                assertThat(stage.allowedPaths()).containsExactly(
                        "config/**", "config/external-adapter.yml"));
        assertThat(result.normalizations()).contains("MUTATION_PATH_SINGLE_STAGE_BOUND");
    }

    @Test
    void mutationConservationAcceptsSingleAndMultipleJustifiedStageOwners() {
        Catalog facts = mutationCatalog(List.of(
                mutation(0, "src/main/java/example/Service.java", MutationOperation.WRITE),
                mutation(1, "config/external-adapter.yml", MutationOperation.WRITE),
                mutation(2, "src/test/java/example/ServiceTest.java", MutationOperation.MOVE_DESTINATION)));
        List<CompactStage> stages = List.of(
                policyStage(List.of("src/main/java/example/Service.java"), List.of()),
                policyStage(List.of("config/**", "src/test/java/**"), List.of()));

        MutationConservationPolicy.Evaluation evaluation = new MutationConservationPolicy().evaluate(
                facts, stages, stages.stream().map(CompactStage::allowedPaths).toList());

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.obligationCount()).isEqualTo(3);
        assertThat(evaluation.resolvedCount()).isEqualTo(3);
        assertThat(evaluation.unresolved()).isEmpty();
        assertThat(evaluation.pathConservation()).isEqualTo("CONSERVED");
    }

    @Test
    void mutationConservationRequiresProvableGlobRuleContainment() {
        Catalog facts = mutationCatalog(List.of(
                mutation(0, "src/**/Service.java", MutationOperation.WRITE)));
        CompactStage narrower = policyStage(List.of("src/*/Service.java"), List.of());
        CompactStage covering = policyStage(List.of("src/**"), List.of());

        MutationConservationPolicy.Evaluation rejected = new MutationConservationPolicy().evaluate(
                facts, List.of(narrower), List.of(narrower.allowedPaths()));
        MutationConservationPolicy.Evaluation accepted = new MutationConservationPolicy().evaluate(
                facts, List.of(covering), List.of(covering.allowedPaths()));
        Catalog overlappingFacts = mutationCatalog(List.of(
                mutation(0, "config/*.yml", MutationOperation.WRITE)));
        CompactStage overlapping = policyStage(List.of("config/**"), List.of("config/secret*.yml"));
        MutationConservationPolicy.Evaluation forbidden = new MutationConservationPolicy().evaluate(
                overlappingFacts, List.of(overlapping), List.of(overlapping.allowedPaths()));
        Catalog exactFileFacts = mutationCatalog(List.of(
                mutation(0, "config/a.yml", MutationOperation.WRITE)));
        CompactStage boundedFileGlob = policyStage(List.of("config/*.yml"), List.of());
        MutationConservationPolicy.Evaluation boundedFileCoverage = new MutationConservationPolicy().evaluate(
                exactFileFacts, List.of(boundedFileGlob), List.of(boundedFileGlob.allowedPaths()));

        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.unresolved()).singleElement().satisfies(unresolved ->
                assertThat(unresolved.code()).isEqualTo(DesignGapCode.REQUIRED_MUTATION_PATH_UNASSIGNED));
        assertThat(accepted.passed()).isTrue();
        assertThat(boundedFileCoverage.passed()).isTrue();
        assertThat(forbidden.unresolved()).singleElement().satisfies(unresolved ->
                assertThat(unresolved.code()).isEqualTo(DesignGapCode.REQUIRED_MUTATION_PATH_FORBIDDEN));

        CompactStage directoryForbidden = policyStage(List.of("config/**"), List.of("config"));
        MutationConservationPolicy.Evaluation forbiddenByDirectory = new MutationConservationPolicy().evaluate(
                overlappingFacts, List.of(directoryForbidden), List.of(directoryForbidden.allowedPaths()));
        assertThat(forbiddenByDirectory.unresolved()).singleElement().satisfies(unresolved ->
                assertThat(unresolved.code()).isEqualTo(DesignGapCode.REQUIRED_MUTATION_PATH_FORBIDDEN));

        Catalog directoryFacts = mutationCatalog(List.of(
                mutation(0, "config/templates", MutationPathKind.PATH_RULE, MutationOperation.WRITE)));
        CompactStage shallowGlob = policyStage(List.of("config/*"), List.of());
        MutationConservationPolicy.Evaluation shallowCoverage = new MutationConservationPolicy().evaluate(
                directoryFacts, List.of(shallowGlob), List.of(shallowGlob.allowedPaths()));
        assertThat(shallowCoverage.unresolved()).singleElement().satisfies(unresolved ->
                assertThat(unresolved.code()).isEqualTo(DesignGapCode.REQUIRED_MUTATION_PATH_UNASSIGNED));

        CompactStage sameDirectory = policyStage(List.of("config/templates"), List.of());
        CompactStage ancestorDirectory = policyStage(List.of("config"), List.of());
        assertThat(new MutationConservationPolicy().evaluate(directoryFacts, List.of(sameDirectory),
                List.of(sameDirectory.allowedPaths())).passed()).isTrue();
        assertThat(new MutationConservationPolicy().evaluate(directoryFacts, List.of(ancestorDirectory),
                List.of(ancestorDirectory.allowedPaths())).passed()).isTrue();

        CompactStage nestedForbidden = policyStage(List.of("config/templates"),
                List.of("config/templates/secret/**"));
        MutationConservationPolicy.Evaluation forbiddenSubtree = new MutationConservationPolicy().evaluate(
                directoryFacts, List.of(nestedForbidden), List.of(nestedForbidden.allowedPaths()));
        assertThat(forbiddenSubtree.unresolved()).singleElement().satisfies(unresolved ->
                assertThat(unresolved.code()).isEqualTo(DesignGapCode.REQUIRED_MUTATION_PATH_FORBIDDEN));
    }

    @Test
    void mutationConservationProvesCommonSiblingGlobExclusionsAreDisjoint() {
        Catalog facts = mutationCatalog(List.of(
                mutation(0, "config/*.yml", MutationPathKind.PATH_RULE, MutationOperation.WRITE)));
        CompactStage generatedSubtree = policyStage(List.of("config/**"), List.of("config/generated/**"));
        CompactStage differentSuffix = policyStage(List.of("config/**"), List.of("config/*.json"));

        MutationConservationPolicy.Evaluation subtree = new MutationConservationPolicy().evaluate(
                facts, List.of(generatedSubtree), List.of(generatedSubtree.allowedPaths()));
        MutationConservationPolicy.Evaluation suffix = new MutationConservationPolicy().evaluate(
                facts, List.of(differentSuffix), List.of(differentSuffix.allowedPaths()));

        assertThat(subtree.passed()).isTrue();
        assertThat(suffix.passed()).isTrue();
    }

    @Test
    void mutationConservationUsesTypedExactAndSubtreeSemanticsWithoutDotNameHeuristics() {
        CompactStage shallowGlob = policyStage(List.of("config/*.d"), List.of());
        Catalog exact = mutationCatalog(List.of(
                mutation(0, "config/config.d", MutationPathKind.EXACT_PATH, MutationOperation.WRITE)));
        Catalog subtree = mutationCatalog(List.of(
                mutation(0, "config/config.d", MutationPathKind.PATH_RULE, MutationOperation.WRITE)));

        MutationConservationPolicy.Evaluation exactEvaluation = new MutationConservationPolicy().evaluate(
                exact, List.of(shallowGlob), List.of(shallowGlob.allowedPaths()));
        MutationConservationPolicy.Evaluation subtreeEvaluation = new MutationConservationPolicy().evaluate(
                subtree, List.of(shallowGlob), List.of(shallowGlob.allowedPaths()));

        assertThat(exactEvaluation.passed()).isTrue();
        assertThat(subtreeEvaluation.passed()).isFalse();
    }

    @Test
    void mutationConservationUsesOnlyThePublicThreeStateDiagnosticContract() {
        Catalog empty = mutationCatalog(List.of());

        MutationConservationPolicy.Evaluation routed =
                MutationConservationPolicy.Evaluation.notEvaluated(empty);
        MutationConservationPolicy.Evaluation compiled = new MutationConservationPolicy().evaluate(
                empty, List.of(), List.of());

        assertThat(routed.pathConservation()).isEqualTo("NOT_EVALUATED");
        assertThat(routed.passed()).isFalse();
        assertThat(compiled.pathConservation()).isEqualTo("CONSERVED");
        assertThat(compiled.passed()).isTrue();
    }

    @Test
    void unevaluatedMutationConservationKeepsFrozenObligationCounts() {
        Catalog facts = mutationCatalog(List.of(
                mutation(0, "src/main/java/example/Service.java", MutationOperation.WRITE),
                mutation(1, "config/external-adapter.yml", MutationOperation.WRITE)));

        MutationConservationPolicy.Evaluation evaluation =
                MutationConservationPolicy.Evaluation.notEvaluated(facts);

        assertThat(evaluation.obligationCount()).isEqualTo(2);
        assertThat(evaluation.resolvedCount()).isZero();
        assertThat(evaluation.unresolvedCount()).isEqualTo(2);
        assertThat(evaluation.pathConservation()).isEqualTo("NOT_EVALUATED");
        assertThat(evaluation.passed()).isFalse();
    }

    @Test
    void mutationConservationBlocksForbiddenDeleteAndMoveSourceObligations() {
        Catalog facts = mutationCatalog(List.of(
                mutation(0, "config/external-adapter.yml", MutationOperation.WRITE),
                mutation(1, "config/obsolete.yml", MutationOperation.DELETE_REQUEST),
                mutation(2, "config/adapter-old.yml", MutationOperation.MOVE_SOURCE)));
        CompactStage stage = policyStage(List.of("config/**"), List.of("config/external-adapter.yml"));

        MutationConservationPolicy.Evaluation evaluation = new MutationConservationPolicy().evaluate(
                facts, List.of(stage), List.of(stage.allowedPaths()));

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.unresolved()).extracting(MutationConservationPolicy.Unresolved::code)
                .containsExactly(
                        DesignGapCode.REQUIRED_MUTATION_PATH_FORBIDDEN,
                        DesignGapCode.REQUIRED_MUTATION_PATH_FORBIDDEN,
                        DesignGapCode.REQUIRED_MUTATION_PATH_FORBIDDEN);
        assertThat(evaluation.resolvedCount()).isZero();
        assertThat(evaluation.pathConservation()).isEqualTo("BLOCKED");
    }

    @Test
    void mutationConservationRejectsDivergentStageAndEvidencePathContracts() {
        Catalog facts = mutationCatalog(List.of(
                mutation(0, "src/main/java/example/Service.java", MutationOperation.WRITE)));
        CompactStage divergent = policyStage(List.of("src/main/java/**"), List.of(),
                List.of("src/main/java/example/Service.java"));

        MutationConservationPolicy.Evaluation evaluation = new MutationConservationPolicy().evaluate(
                facts, List.of(divergent), List.of(divergent.allowedPaths()));

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.unresolved()).singleElement().satisfies(unresolved -> {
            assertThat(unresolved.code()).isEqualTo(DesignGapCode.REQUIRED_MUTATION_PATH_UNASSIGNED);
            assertThat(unresolved.reason()).contains("路径合同不一致");
        });
    }

    @Test
    void mutationConservationComparesStageAndEvidencePathsAfterRuntimeNormalization() {
        Catalog facts = mutationCatalog(List.of(
                mutation(0, "src/main/java/example/Service.java", MutationOperation.WRITE)));
        CompactStage equivalent = policyStage(List.of("./src/main/java/**"), List.of(".\\.env"),
                List.of("src/main/java/**"));
        CompactStage normalizedEvidence = new CompactStage(equivalent.objective(), equivalent.implementationKind(),
                equivalent.allowedPaths(), equivalent.forbiddenPaths(), equivalent.deliverables(),
                equivalent.criteria(), equivalent.evidence().stream().map(evidence -> new CompactEvidence(
                        evidence.kind(), evidence.command(), evidence.covers(), evidence.successMarker(), evidence.path(),
                        evidence.requireChanges(), evidence.allowedPaths(), List.of(".env"), evidence.forbidDeletes(),
                        evidence.url(), evidence.httpMethod(), evidence.expectedStatus(), evidence.jsonPath(),
                        evidence.expectedValue(), evidence.matchMode(), evidence.expectedContent(),
                        evidence.expectedSha256(), evidence.sql(), evidence.expectedRowCount(), evidence.assertions(),
                        evidence.documentAssertions(), evidence.tabularAssertions())).toList(), null);

        MutationConservationPolicy.Evaluation evaluation = new MutationConservationPolicy().evaluate(
                facts, List.of(normalizedEvidence), List.of(normalizedEvidence.allowedPaths()));

        assertThat(evaluation.passed()).isTrue();
    }

    @Test
    void historicalV5V6FactsJsonDefaultsMissingMutationCatalogAndV7RoundTripsExactly() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String historical = """
                {"contractVersion":"DESIGN_ACCEPTANCE_V6","workPackageId":"WP-1","designRevision":3,
                 "designSha256":"%s","controlledFormat":true,"facts":[],"stageHints":[],"issues":[]}
                """.formatted("a".repeat(64));

        for (String version : List.of(CONTRACT_VERSION_V5, CONTRACT_VERSION_V6)) {
            Catalog restored = json.readValue(historical.replace(CONTRACT_VERSION_V6, version), Catalog.class);
            assertThat(restored.mutationObligations()).isEmpty();
            assertThat(restored.mutationIssues()).isEmpty();
        }

        Catalog v7 = mutationCatalog(List.of(
                mutation(0, "config/external-adapter.yml", MutationOperation.WRITE)));
        Catalog restoredV7 = json.readValue(json.writeValueAsString(v7), Catalog.class);
        assertThat(restoredV7).isEqualTo(v7);
        assertThat(restoredV7.mutationObligations()).singleElement().satisfies(obligation -> {
            assertThat(obligation.sourceRef()).isEqualTo("REQUIREMENT:L001");
            assertThat(obligation.sourceSha256()).isEqualTo("3".repeat(64));
        });
    }

    @Test
    void acceptsAlternateAdvisoryGroupingShapeAndDropsUnreadableOptionalAdvice() {
        ObjectMapper json = new ObjectMapper();
        DesignerAcceptanceWorkflow workflow = new DesignerAcceptanceWorkflow(null, json,
                new AiOutputExtractor(json), null, evidenceIndexer,
                new DesignerPackagePlanCompiler(evidenceIndexer));

        var compatible = workflow.parse("""
                LOOPSPEC_COMPILATION_PLAN_JSON_START
                {"summary":"分组建议","groupHints":[{"groupIndex":0,"title":"注册表测试",
                "factIndexes":[1,2],"capabilityIndexes":[0],"dependsOnHintIndexes":[]}],
                "capabilityPreferences":[{"capabilityIndex":0,"preference":"preferred","reason":"唯一测试"}],
                "handoffSummary":"建议完成"}
                LOOPSPEC_COMPILATION_PLAN_JSON_END
                """, 6);
        assertThat(compatible.value().groupHints()).singleElement().satisfies(group -> {
            assertThat(group.title()).isEqualTo("注册表测试");
            assertThat(group.factIndexes()).containsExactly(1, 2);
        });
        assertThat(compatible.value().capabilityPreferences()).isEmpty();
        assertThat(compatible.normalizations()).contains("UNKNOWN_FIELDS_IGNORED", "CONTRACT_METADATA_DERIVED");

        var fallback = workflow.parse("""
                LOOPSPEC_COMPILATION_PLAN_JSON_START
                {"summary":"无法采用的建议","groupHints":"not-an-array",
                 "capabilityPreferences":false,"handoffSummary":null}
                LOOPSPEC_COMPILATION_PLAN_JSON_END
                """, 6);
        assertThat(fallback.value().groupHints()).isEmpty();
        assertThat(fallback.value().capabilityPreferences()).isEmpty();
        assertThat(fallback.normalizations()).containsExactly("OPTIONAL_ACCEPTANCE_ADVICE_DROPPED");
    }

    @Test
    void v7SafelyNormalizesClosedChoicesAndIgnoresHarmlessNarrativeMetadata() {
        ObjectMapper json = new ObjectMapper();
        DesignerAcceptanceWorkflow workflow = new DesignerAcceptanceWorkflow(null, json,
                new AiOutputExtractor(json), null, evidenceIndexer,
                new DesignerPackagePlanCompiler(evidenceIndexer));

        var extracted = workflow.parseV7("""
                LOOPSPEC_COMPILATION_PLAN_JSON_START
                {"summary":"唯一闭集选择","fact_assignments":{"fact_index":3,"stage_index":1},
                 "capability_preferences":{"fact_index":3,"capability_indexes":2},
                 "handoff_summary":null,"explanation":"仅解释为何选择服务端候选"}
                LOOPSPEC_COMPILATION_PLAN_JSON_END
                """);

        assertThat(extracted.value().factAssignments()).containsExactly(new AcceptanceFactAssignment(3, 1));
        assertThat(extracted.value().capabilityPreferences()).containsExactly(
                new AcceptanceCapabilityPreference(3, List.of(2)));
        assertThat(extracted.normalizations()).contains(
                "FIELD_NAME_NORMALIZED", "SINGLETON_COLLECTION_NORMALIZED",
                "UNKNOWN_FIELDS_IGNORED");
    }

    @Test
    void v7CarriesTheUniqueGlobalCapabilitySetThroughStageLowering() {
        List<Fact> factList = List.of(
                new Fact(0, FactKind.SCENARIO, "成功", "输入合法", "执行", "返回成功", "状态一致",
                        null, "DS-L001", "受控设计", "0".repeat(64)),
                new Fact(1, FactKind.SCENARIO, "失败", "输入非法", "执行", "抛出异常", "状态一致",
                        null, "DS-L001", "受控设计", "1".repeat(64)),
                new Fact(2, FactKind.DELIVERABLE, "src/main/java/example/Flow.java", null, null, null,
                        null, "生产代码", "DS-L001", "受控设计", "2".repeat(64)),
                new Fact(3, FactKind.DELIVERABLE, "src/test/java/example/FlowTest.java", null, null, null,
                        null, "测试代码", "DS-L001", "受控设计", "3".repeat(64)));
        Catalog facts = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "4".repeat(64), true,
                factList, List.of(), List.of(), List.of(), List.of());
        Capability broad = new Capability(2, "FOCUSED_TEST", "全局 Flow 测试",
                List.of("mvn", "-Dtest=BroadFlowTest", "test"), List.of(0, 1),
                List.of("BroadFlowTest"), true, false, 80);
        Capability localSuccess = new Capability(0, "FOCUSED_TEST", "局部成功测试",
                List.of("mvn", "-Dtest=LocalSuccessTest", "test"), List.of(0),
                List.of("LocalSuccessTest"), true, false, 100);
        Capability localFailure = new Capability(1, "FOCUSED_TEST", "局部失败测试",
                List.of("mvn", "-Dtest=LocalFailureTest", "test"), List.of(1),
                List.of("LocalFailureTest"), true, false, 100);
        CompactAcceptanceBindingPlan binding = new CompactAcceptanceBindingPlan("全局唯一能力",
                List.of(new AcceptanceGroupHint("成功阶段", "实现成功", List.of(0, 2, 3), List.of()),
                        new AcceptanceGroupHint("失败阶段", "实现失败", List.of(1, 2, 3), List.of(0))),
                List.of(), null);

        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), "受控设计", facts,
                new CapabilityCatalog(CONTRACT_VERSION_V7,
                        List.of(localSuccess, localFailure, broad), List.of()),
                binding, role("software-java", List.of("java")), List.of(), List.of(), List.of(), 6, true);

        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.plan().stages()).hasSize(2).allSatisfy(stage ->
                assertThat(stage.verifiers()).filteredOn(verifier -> "TEST".equals(verifier.processPurpose()))
                        .singleElement().satisfies(verifier ->
                                assertThat(verifier.command()).contains("-Dtest=BroadFlowTest")));
    }

    @Test
    void v7StageLoweringRejectsANonExhaustiveCapabilitySolution() {
        List<Fact> factList = List.of(
                new Fact(0, FactKind.SCENARIO, "成功", "输入合法", "执行", "返回成功", "状态一致",
                        null, "DS-L001", "受控设计", "0".repeat(64)),
                new Fact(1, FactKind.DELIVERABLE, "src/main/java/example/Flow.java", null, null, null,
                        null, "生产代码", "DS-L001", "受控设计", "1".repeat(64)),
                new Fact(2, FactKind.DELIVERABLE, "src/test/java/example/FlowTest.java", null, null, null,
                        null, "测试代码", "DS-L001", "受控设计", "2".repeat(64)));
        Catalog facts = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "3".repeat(64), true,
                factList, List.of(), List.of(), List.of(), List.of());
        CapabilityCatalog capabilities = new CapabilityCatalog(CONTRACT_VERSION_V7, List.of(
                new Capability(0, "FOCUSED_TEST", "测试 A", List.of("mvn", "-Dtest=ATest", "test"),
                        List.of(0), List.of("ATest"), true, false, 100),
                new Capability(1, "FOCUSED_TEST", "测试 B", List.of("mvn", "-Dtest=BTest", "test"),
                        List.of(0), List.of("BTest"), true, false, 100)), List.of());
        CompactAcceptanceBindingPlan binding = new CompactAcceptanceBindingPlan("受控验收",
                List.of(new AcceptanceGroupHint("实现", "实现并验证", List.of(0, 1, 2), List.of())),
                List.of(), null);
        DesignerAcceptancePlanCompiler bounded = new DesignerAcceptancePlanCompiler(
                new DesignerPackagePlanCompiler(evidenceIndexer), new DesignerAcceptanceCapabilitySolver(1));

        DesignerAcceptancePlanCompiler.Result result = bounded.compile(workPackage(), "受控设计", facts,
                capabilities, binding, role("software-java", List.of("java")), List.of(), List.of(),
                List.of(), 6, true);

        assertThat(result.plan().status()).isEqualTo("DESIGN_INCOMPLETE");
        assertThat(result.plan().designGaps()).singleElement().satisfies(gap -> {
            assertThat(gap.code()).isEqualTo(DesignGapCode.AMBIGUOUS_ACCEPTANCE_INTENT);
            assertThat(gap.detail()).contains("有界节点", "权威最优");
        });
    }

    @Test
    void v7RejectsModelAttemptsToWriteExecutionOrTopologyFields() {
        ObjectMapper json = new ObjectMapper();
        DesignerAcceptanceWorkflow workflow = new DesignerAcceptanceWorkflow(null, json,
                new AiOutputExtractor(json), null, evidenceIndexer,
                new DesignerPackagePlanCompiler(evidenceIndexer));

        assertThatThrownBy(() -> workflow.parseV7("""
                LOOPSPEC_COMPILATION_PLAN_JSON_START
                {"summary":"越权选择","factAssignments":[],"capabilityPreferences":[],
                 "handoffSummary":null,"commands":["mvn","test"],
                 "stages":[{"allowedPaths":["src/**"]}]}
                LOOPSPEC_COMPILATION_PLAN_JSON_END
                """))
                .isInstanceOfSatisfying(BadRequestException.class, error -> {
                    assertThat(error.code()).isEqualTo("AMBIGUOUS_ACCEPTANCE_INTENT");
                    assertThat(error).hasMessageContaining("execution or topology");
                });
    }

    @Test
    void v7RejectsSingularAndCompoundExecutionOrTopologyFieldNames() {
        ObjectMapper json = new ObjectMapper();
        DesignerAcceptanceWorkflow workflow = new DesignerAcceptanceWorkflow(null, json,
                new AiOutputExtractor(json), null, evidenceIndexer,
                new DesignerPackagePlanCompiler(evidenceIndexer));

        for (String field : List.of("testTarget", "stageTopology", "stageIndex", "safetyPolicy",
                "shellCommand", "obligationIndex", "cmd", "argv", "shell", "script")) {
            assertThatThrownBy(() -> workflow.parseV7("""
                    LOOPSPEC_COMPILATION_PLAN_JSON_START
                    {"factAssignments":[],"capabilityPreferences":[],"%s":"forbidden"}
                    LOOPSPEC_COMPILATION_PLAN_JSON_END
                    """.formatted(field)))
                    .as(field)
                    .isInstanceOfSatisfying(BadRequestException.class, error ->
                            assertThat(error.code()).isEqualTo("AMBIGUOUS_ACCEPTANCE_INTENT"));
        }
    }

    @Test
    void v7RejectsConflictingValidChoicesAcrossMarkerAndNarrativeObjects() {
        ObjectMapper json = new ObjectMapper();
        DesignerAcceptanceWorkflow workflow = new DesignerAcceptanceWorkflow(null, json,
                new AiOutputExtractor(json), null, evidenceIndexer,
                new DesignerPackagePlanCompiler(evidenceIndexer));

        assertThatThrownBy(() -> workflow.parseV7("""
                {"factAssignments":[],"capabilityPreferences":[{"factIndex":3,"capabilityIndexes":[1]}]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"factAssignments":[],"capabilityPreferences":[{"factIndex":3,"capabilityIndexes":[2]}]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """))
                .isInstanceOfSatisfying(BadRequestException.class, error ->
                        assertThat(error.code()).isEqualTo("ACCEPTANCE_CLOSED_CHOICE_OUTPUT_AMBIGUOUS"));
    }

    @Test
    void v7RejectsAnyDangerousRawCandidateEvenWhenTheMarkerChoiceIsValid() {
        ObjectMapper json = new ObjectMapper();
        DesignerAcceptanceWorkflow workflow = new DesignerAcceptanceWorkflow(null, json,
                new AiOutputExtractor(json), null, evidenceIndexer,
                new DesignerPackagePlanCompiler(evidenceIndexer));

        assertThatThrownBy(() -> workflow.parseV7("""
                {"shellCommand":["mvn","test"]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"factAssignments":[],"capabilityPreferences":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """))
                .isInstanceOfSatisfying(BadRequestException.class, error ->
                        assertThat(error.code()).isEqualTo("AMBIGUOUS_ACCEPTANCE_INTENT"));
    }

    @Test
    void v7CandidateLimitCannotHideALaterDangerousObject() {
        ObjectMapper json = new ObjectMapper();
        DesignerAcceptanceWorkflow workflow = new DesignerAcceptanceWorkflow(null, json,
                new AiOutputExtractor(json), null, evidenceIndexer,
                new DesignerPackagePlanCompiler(evidenceIndexer));
        String safeMarker = """
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->
                {"factAssignments":[],"capabilityPreferences":[]}
                <!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->
                """;

        assertThatThrownBy(() -> workflow.parseV7(safeMarker.repeat(16)
                + "{\"shellCommand\":[\"mvn\",\"test\"]}"))
                .isInstanceOfSatisfying(BadRequestException.class, error ->
                        assertThat(error.code()).isEqualTo("AMBIGUOUS_ACCEPTANCE_INTENT"));
    }

    @Test
    void v7RejectsConflictingAliasesInsteadOfSilentlyTakingTheLastValue() {
        ObjectMapper json = new ObjectMapper();
        DesignerAcceptanceWorkflow workflow = new DesignerAcceptanceWorkflow(null, json,
                new AiOutputExtractor(json), null, evidenceIndexer,
                new DesignerPackagePlanCompiler(evidenceIndexer));

        assertThatThrownBy(() -> workflow.parseV7("""
                {"factAssignments":{"factIndex":3,"stageIndex":0},
                 "fact_assignments":{"fact_index":3,"stage_index":1},
                 "capabilityPreferences":[]}
                """))
                .isInstanceOfSatisfying(BadRequestException.class, error ->
                        assertThat(error.code()).isEqualTo("AMBIGUOUS_ACCEPTANCE_INTENT"));
    }

    @Test
    void v7RejectsSelectionFieldsOutsideTheirClosedChoiceObjects() {
        ObjectMapper json = new ObjectMapper();
        DesignerAcceptanceWorkflow workflow = new DesignerAcceptanceWorkflow(null, json,
                new AiOutputExtractor(json), null, evidenceIndexer,
                new DesignerPackagePlanCompiler(evidenceIndexer));

        for (String field : List.of("factIndex", "capabilityIndexes")) {
            assertThatThrownBy(() -> workflow.parseV7("""
                    {"factAssignments":[],"capabilityPreferences":[],"%s":3}
                    """.formatted(field)))
                    .as(field)
                    .isInstanceOfSatisfying(BadRequestException.class, error ->
                            assertThat(error.code()).isEqualTo("AMBIGUOUS_ACCEPTANCE_INTENT"));
        }
    }

    @Test
    void bindsPositiveRegressionTargetOnceWithoutMakingItCoverUnrelatedBusinessScenarios() {
        String design = """
                ## 目标与范围
                为对象注册表补齐单元测试，既有失败回归继续作为独立证据。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增测试 | src/test/java/example/ObjectRegistryTest.java | 注册、查询和重复键行为 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 重复键拒绝 | 已注册 key=A | 再次 register(A) | 抛出 duplicate 异常 | 首次注册仍可查询 |

                ## 验收约束
                ObjectRegistryTest 与 ExistingFailureRegressionTest 必须分别独立通过。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | --- | --- | --- |
                | 注册表测试 | ObjectRegistryTest 覆盖重复键拒绝；ExistingFailureRegressionTest 继续回归 | 无 |
                """;
        Catalog facts = extractor.extract("WP-1", 1, design);
        WorkPackageRoleService.View role = role("software-java", List.of("java"));
        CapabilityCatalog capabilities = registry.build(facts, role, design);
        assertThat(capabilities.capabilities()).filteredOn(capability ->
                        capability.testTargets().contains("ExistingFailureRegressionTest"))
                .singleElement().satisfies(capability -> {
                    assertThat(capability.mandatory()).isTrue();
                    assertThat(capability.coversFactIndexes()).isEmpty();
                });

        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), design, facts, capabilities,
                new CompactAcceptanceBindingPlan("注册表验收", List.of(), List.of(), "完成"), role,
                List.of("src/test/java/**"), List.of("src/main/**"), List.of("新增测试"), 6, true);
        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.plan().stages()).singleElement().satisfies(stage -> {
            assertThat(stage.implementationKind().name()).isEqualTo("JAVA_TEST_ONLY");
            assertThat(stage.verifiers()).filteredOn(verifier -> "TEST".equals(verifier.processPurpose()))
                    .extracting(verifier -> verifier.testTargets())
                    .containsExactlyInAnyOrder(List.of("ObjectRegistryTest"),
                            List.of("ExistingFailureRegressionTest"));
        });
        assertThat(result.normalizations()).contains("INDEPENDENT_REQUIRED_CAPABILITIES_BOUND");
    }

    @Test
    void competitivelyMapsSharedDomainNamesToTheirDeclaredStageInsteadOfEverySimilarTest() {
        String design = """
                ## 目标与范围
                为共享 Object 前缀的注册表、策略、上下文和调用器分别补齐测试。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增测试 | src/test/java/example/ObjectRegistryTest.java | 注册、查询、重复键和未知键 |
                | 新增测试 | src/test/java/example/ObjectPolicyTest.java | 默认作用域、空白归一化和不可变性 |
                | 新增测试 | src/test/java/example/ObjectContextTest.java | 属性、中断和 reached 状态 |
                | 新增测试 | src/test/java/example/ObjectInvokerNormalTest.java | 正常按序调用、显式作用域和 MDC 清理 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 重复键拒绝 | 已注册 key=A | 再次 register(A) | 抛出 duplicate 异常 | 原值可查询 |
                | 未知键拒绝 | 空注册表 | get(NO_SUCH) | 抛出 unknown 异常 | 注册表为空 |
                | 空白作用域归一化 | 构造 ObjectPolicy | scope(" ") | 返回默认作用域 | 原对象不变 |
                | reached 状态隔离 | 新 ObjectContext | 在两个 scope 标记 reached | 两个作用域互不污染 | 属性不变 |
                | 正常按序调用 | 注册 A、B | ObjectInvoker 依次 execute | A 先于 B | 各执行一次 |
                | 显式作用域调用 | 注册 A | execute(A, scope1) | reached 记录在 scope1 | 默认作用域不变 |

                ## 验收约束
                四个测试类必须分别独立通过。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | --- | --- | --- |
                | 注册表 | ObjectRegistryTest 覆盖重复键与未知键 | 无 |
                | 策略 | ObjectPolicyTest 覆盖作用域归一化 | 无 |
                | 上下文 | ObjectContextTest 覆盖 reached 状态隔离 | 无 |
                | 调用器 | ObjectInvokerNormalTest 覆盖按序调用与显式作用域 | 注册表、上下文 |
                """;
        Catalog facts = extractor.extract("WP-1", 1, design);
        CapabilityCatalog capabilities = registry.build(facts, role("software-java", List.of("java")), design);

        assertThat(capabilities.issues()).isEmpty();
        assertCoverage(facts, capabilities, "ObjectRegistryTest", "重复键拒绝", "未知键拒绝");
        assertCoverage(facts, capabilities, "ObjectPolicyTest", "空白作用域归一化");
        assertCoverage(facts, capabilities, "ObjectContextTest", "reached 状态隔离");
        assertCoverage(facts, capabilities, "ObjectInvokerNormalTest", "正常按序调用", "显式作用域调用");
    }

    @Test
    void bindsUniquePackageTestAsReviewOnlyJavaStageGateAndKeepsNaturalLanguageOutOfPaths() {
        String design = """
                ## 目标与范围
                为责任链生命周期事件补齐进程内分发、状态订阅与发布接入。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 新增 | src/main/java/example/event/EventBus.java | 事件注册与分发 |
                | 新增 | src/main/java/example/state/StateSubscriber.java | 合法事件驱动状态迁移 |
                | 新增 | src/main/java/example/business/ChainLifecyclePublisher.java | executor/invoker 生命周期发布接入 |
                | 新增测试 | src/test/java/example/event/ChainLifecycleEventTest.java | 覆盖事件分发、状态迁移与发布 cause 语义 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | 事件总线分发 | 已注册监听器 | 发布开始事件 | 监听器收到同一调用身份 | 注册表不变 |
                | 状态订阅迁移 | 已配置合法映射 | 发布成功事件 | 状态机完成一次合法迁移 | 重复事件不改状态 |
                | 失败 cause 传播 | executor 发生失败 | 发布失败事件 | 监听器收到原始 cause 同一实例 | 原异常不被包装 |

                ## 可选人工评审项
                | 评审项 | 判断标准 | 仅人工原因 |
                | --- | --- | --- |
                | 状态事件映射 | 四类映射符合业务编排语义 | 语义合理性依赖业务意图 |
                | 发布接入位置 | executor/invoker 接入自然且不侵入成功路径 | 代码组织需要人工判断 |

                ## 验收约束
                ChainLifecycleEventTest 必须独立通过并作为每个生产 Java 阶段的聚焦门禁，不依赖 Spring 上下文。

                ## 阶段与依赖
                | 阶段建议 | 包含场景/交付 | 前置阶段 |
                | --- | --- | --- |
                | 事件模型与总线 | EventBus 与事件总线分发 | 无 |
                | 状态机订阅 | StateSubscriber 与状态订阅迁移、失败 cause 传播 | 事件模型与总线 |
                | 发布接入 | ChainLifecyclePublisher、ChainLifecycleEventTest 与发布接入位置评审 | 状态机订阅 |
                """;
        Catalog facts = extractor.extract("WP-4", 1, design);
        WorkPackageRoleService.View role = role("software-java", List.of("java"));
        CapabilityCatalog capabilities = registry.build(facts, role, design);
        assertThat(capabilities.issues()).isEmpty();
        assertThat(capabilities.capabilities()).filteredOn(capability ->
                        capability.testTargets().contains("ChainLifecycleEventTest"))
                .singleElement();

        CompactAcceptanceBindingPlan binding = new CompactAcceptanceBindingPlan("生命周期事件三阶段验收", List.of(
                new AcceptanceGroupHint("事件模型与总线", "实现事件模型与总线", List.of(
                        factIndex(facts, FactKind.DELIVERABLE, "EventBus.java"),
                        factIndex(facts, FactKind.SCENARIO, "事件总线分发")), List.of()),
                new AcceptanceGroupHint("状态机订阅", "实现状态机订阅", List.of(
                        factIndex(facts, FactKind.DELIVERABLE, "StateSubscriber.java"),
                        factIndex(facts, FactKind.SCENARIO, "状态订阅迁移"),
                        factIndex(facts, FactKind.SCENARIO, "失败 cause 传播"),
                        factIndex(facts, FactKind.REVIEW, "状态事件映射")), List.of(0)),
                new AcceptanceGroupHint("发布接入", "实现发布接入并运行 ChainLifecycleEventTest", List.of(
                        factIndex(facts, FactKind.DELIVERABLE, "ChainLifecyclePublisher.java"),
                        factIndex(facts, FactKind.DELIVERABLE, "ChainLifecycleEventTest.java"),
                        factIndex(facts, FactKind.REVIEW, "发布接入位置"),
                        facts.facts().stream().filter(fact -> fact.kind() == FactKind.POLICY)
                                .findFirst().orElseThrow().index()), List.of(0, 1))),
                List.of(), "生命周期事件链路完成");
        DesignerAcceptancePlanCompiler.Result result = compiler.compile(workPackage(), design, facts, capabilities,
                binding, role, List.of(
                        "新增事件/监听包：进程内事件注册/分发",
                        "business/ 与 chain/：生命周期发布点",
                        "state/：状态机订阅映射"), List.of(
                        "失败领域事件发布（阶段二）",
                        "补偿节点与幂等（阶段三）",
                        "多线程/多进程语义与持久化",
                        "target/**"), List.of("生命周期事件能力"), 3, false);

        assertThat(result.plan().status()).isEqualTo("COMPILED");
        assertThat(result.plan().stages()).hasSize(3).allSatisfy(stage ->
                assertThat(stage.verifiers()).filteredOn(verifier -> "TEST".equals(verifier.processPurpose()))
                        .singleElement().satisfies(verifier ->
                                assertThat(verifier.testTargets()).containsExactly("ChainLifecycleEventTest")));
        assertThat(result.plan().stages().get(2).verifiers())
                .filteredOn(verifier -> "TEST".equals(verifier.processPurpose()))
                .singleElement().satisfies(verifier -> assertThat(verifier.criterionIds()).isEmpty());
        assertThat(result.plan().stages().get(0).allowedPaths())
                .containsExactly("src/main/java/example/event/EventBus.java");
        assertThat(result.plan().stages().get(1).allowedPaths())
                .containsExactly("src/main/java/example/state/StateSubscriber.java");
        assertThat(result.plan().stages().get(2).allowedPaths()).containsExactly(
                "src/main/java/example/business/ChainLifecyclePublisher.java",
                "src/test/java/example/event/ChainLifecycleEventTest.java");
        assertThat(result.plan().stages()).allSatisfy(stage -> assertThat(stage.allowedPaths())
                .noneMatch(path -> path.contains("：") || path.contains(" 与 ")));
        assertThat(result.plan().stages()).allSatisfy(stage -> {
            assertThat(stage.forbiddenPaths()).containsExactly(".env", ".env.*", "target/**");
            assertThat(stage.verifiers()).allSatisfy(verifier ->
                    assertThat(verifier.forbiddenPaths()).containsExactly(".env", ".env.*", "target/**"));
        });
        assertThat(result.normalizations()).contains("JAVA_PRODUCTION_STAGE_GATE_BOUND");
    }

    @Test
    void mapsEveryExplicitlyEquivalentAlternativeTestToTheSameScenarioWithoutMakingEitherMandatory() {
        String design = """
                ## 目标与范围
                实现并验证 Java Flow 成功行为。

                ## 影响与交付
                | 类型 | 相对路径或符号 | 说明 |
                | --- | --- | --- |
                | 生产代码 | src/main/java/example/Flow.java | Flow 实现 |

                ## 验收场景
                | 场景 | 前置/触发 | 操作 | 可观察结果 | 保持不变 |
                | --- | --- | --- | --- | --- |
                | Flow 成功行为 | 输入合法请求 | 调用 Flow | 返回成功结果 | 不写外部系统 |

                ## 验收约束
                FlowATest 或 FlowBTest 同等覆盖 Flow 成功行为，可任选一个：`mvn -Dtest=FlowATest test` 或 `mvn -Dtest=FlowBTest test`。

                ## 阶段与依赖
                | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
                | --- | --- | --- | --- |
                | Flow 实现 | 实现并验证 Flow | Flow 成功行为；src/main/java/example/Flow.java | 无 |
                """;

        Catalog facts = extractor.extract("WP-1", 1, design, CONTRACT_VERSION_V7);
        CapabilityCatalog capabilities = registry.build(facts, role("software-java", List.of("java")), design);
        int scenarioIndex = factIndex(facts, FactKind.SCENARIO, "Flow 成功行为");

        assertThat(capabilities.issues()).isEmpty();
        assertThat(capabilities.capabilities()).hasSize(2).allSatisfy(capability -> {
            assertThat(capability.coversFactIndexes()).containsExactly(scenarioIndex);
            assertThat(capability.mandatory()).isFalse();
        });
        assertThat(capabilities.capabilities()).extracting(Capability::testTargets)
                .containsExactlyInAnyOrder(List.of("FlowATest"), List.of("FlowBTest"));
    }

    private static Catalog mutationCatalog(List<MutationObligation> obligations) {
        return new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "b".repeat(64), true,
                List.of(), List.of(), obligations, List.of(), List.of());
    }

    private static Catalog mutationCatalog(Catalog base, List<MutationObligation> obligations) {
        return new Catalog(base.contractVersion(), base.workPackageId(), base.designRevision(), base.designSha256(),
                base.controlledFormat(), base.facts(), base.stageHints(), obligations, List.of(), base.issues());
    }

    private static MutationObligation mutation(int index, String path, MutationOperation operation) {
        return mutation(index, path, path.contains("*") ? MutationPathKind.PATH_RULE : MutationPathKind.EXACT_PATH,
                operation);
    }

    private static MutationObligation mutation(int index, String path, MutationPathKind pathKind,
                                                 MutationOperation operation) {
        return new MutationObligation(index, "MO-" + (index + 1), path, pathKind, operation,
                MutationSourceKind.REQUIREMENT, "REQUIREMENT:L001", "修改 " + path,
                "3".repeat(64), List.of(), List.of());
    }

    private static CompactStage policyStage(List<String> allowedPaths, List<String> forbiddenPaths) {
        return policyStage(allowedPaths, forbiddenPaths, allowedPaths);
    }

    private static CompactStage policyStage(List<String> allowedPaths, List<String> forbiddenPaths,
                                            List<String> evidenceAllowedPaths) {
        CompactEvidence focused = new CompactEvidence("FOCUSED_TEST", List.of("mvn", "test"), List.of(),
                null, null, null, evidenceAllowedPaths, forbiddenPaths, null,
                null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of());
        CompactEvidence gitDiff = new CompactEvidence("GIT_DIFF", List.of(), List.of(),
                null, null, true, evidenceAllowedPaths, forbiddenPaths, true,
                null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of());
        return new CompactStage("实现并验证", io.opencode.loopper.domain.ImplementationKind.JAVA_PRODUCTION,
                allowedPaths, forbiddenPaths, List.of(), List.of(), List.of(focused, gitDiff), null);
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

    private static void assertCoverage(Catalog facts, CapabilityCatalog capabilities,
                                       String target, String... scenarioTitles) {
        List<String> titles = List.of(scenarioTitles);
        List<Integer> expected = facts.facts().stream().filter(fact -> titles.contains(fact.title()))
                .map(Fact::index).toList();
        assertThat(expected).hasSize(scenarioTitles.length);
        assertThat(capabilities.capabilities()).filteredOn(capability -> capability.testTargets().contains(target))
                .singleElement().satisfies(capability ->
                        assertThat(capability.coversFactIndexes()).containsExactlyInAnyOrderElementsOf(expected));
    }

    private static int factIndex(Catalog facts, FactKind kind, String titleFragment) {
        return facts.facts().stream().filter(fact -> fact.kind() == kind)
                .filter(fact -> fact.title().contains(titleFragment)).findFirst().orElseThrow().index();
    }

    private static DesignWorkPackageRow workPackage() {
        return new DesignWorkPackageRow("row-1", "session-1", "requirement-1", "decomposition-1",
                "WP-1", 1, "PIN 转换", "完成 PIN 转换行为", "[]", "[]", "[]", "[]", "[]", "[]",
                "COMPILING", null, null, "design-1", 1, 0, 0, null, null, null, null,
                null, 0, null, null, "2026-08-21T00:00:00Z", "2026-08-21T00:00:00Z", 0);
    }
}
