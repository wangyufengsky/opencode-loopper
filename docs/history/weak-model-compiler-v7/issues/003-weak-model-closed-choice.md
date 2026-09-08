# 003：弱模型闭集协议与唯一最优能力

状态：DONE

Blocked by：001、002

Blocks：004

## 目标

把弱模型职责缩到真正无法由服务端唯一决定的闭集选择，并容忍不改变语义或安全边界的机械输出偏差。

## 行为切片

### 唯一最优能力

当一个事实有多个 covering capability，但现有确定性评分能得到唯一最优集合时，直接选择并编译；只有全部业务评分维度真实同分时才进入 Compiler。

### 安全规范化

以下情况仅在选择完整且闭集合法时可规范化：

- 可逆字段别名；
- 单项对象与单项数组互转；
- `null` 集合归一为空集合；
- 不参与合同的说明字段被忽略。

以下情况继续阻断：

- 缺少任何必需选择；
- 重复或冲突选择；
- 越界 fact、obligation、capability 或 Stage 索引；
- 模型尝试写路径、命令、测试目标、Stage 拓扑或安全字段；
- 多个不等价且都看似有效的 JSON 候选。

所有规范化记录 `AI_OUTPUT_NORMALIZED` 和具体代码，不消耗隐藏修复池，也不把无效结果降级为空建议。

## 实现范围

- 为 v7 新增最小 Schema/marker 合同，并同步 `MachineRoleContractCatalog`、`OpenCodeStructuredSchemas`、`DesignerCompilerPromptContracts` 和 `docs/ai-role-contracts.md`。
- 将“是否真实同分”的决定放在服务端 solver，不接受模型自报优先级。
- 保持一次 Compiler Session、零工具、锁定拓扑和现有模型调用预算。
- diagnostics 增加 `compilerAvoidedReason=UNIQUE_OPTIMUM`、真实 tie 数和安全规范化列表。

## 验收标准

- [x] 多 covering capability、唯一最优时模型调用为 0。
- [x] 真实同分时最多调用 1 次，合法闭集选择可编译。
- [x] 弱模型夹带无害摘要字段不再触发整稿重设计。
- [x] 路径、命令、拓扑等越权字段仍阻断。
- [x] invalid 输出保留冻结事实和已完成的唯一绑定。
- [x] 不增加 Judge-only 条件，不减少 focused-test 覆盖。
- [x] OpenCode Schema 不支持时沿既有全新 marker Session 回退，不扩大重试预算。

## 聚焦验证

```bash
./mvnw -Dtest=DesignerAcceptanceFastPathResolverTest,DesignerAcceptancePlanningAlgorithmTest,MachineRoleContractCatalogTest,DesignerSessionMcpIntegrationTest test
```

实现时补充 marker/Schema/规范化的精确测试，再完成全量门禁、双轴代码评审和本地提交。

## 非目标

- 不让模型评价安全或生成执行字段。
- 不引入第二套能力评分算法。
- 不把不完整输出当成可选建议静默丢弃。
