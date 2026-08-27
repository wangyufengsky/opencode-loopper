# 001：Mutation Obligation 与路径守恒门禁

状态：READY

Blocked by：无

Blocks：002、003、004

Primary sources：

- [原型回交](../prototype-handoff.md)
- [正式设计](../spec.md)
- 原型 SHA-256：`a7098afd45a10507cc232e51b589e38b0e0d4cd9a2b06646f4596e2d54217824`

## 目标

让任何新 v7 计划在返回 `COMPILED` 前证明冻结需求中的显式必改路径已经进入 Stage 路径合同。本票先建立不可绕过的守恒门禁；唯一自动补全留给 002。

## 先写红测

在 `DesignerAcceptancePlanningAlgorithmTest` 增加最小回归：

```text
冻结需求明确要求：
- src/main/java/example/Service.java
- config/external-adapter.yml

受控设计只把 Service.java 放进唯一 Stage 的包含列。
```

当前代码会生成只含 `Service.java` 的 `allowedPaths` 并 `COMPILED`。红测要求 v7 返回：

```text
status = DESIGN_INCOMPLETE
gap = REQUIRED_MUTATION_PATH_UNASSIGNED
path = config/external-adapter.yml（只在内部测试断言，普通 UI 不泄露未授权绝对路径）
```

完成条件：该测试在现状稳定变红，并能精确证明失败发生在 lowering 前。

## 实现范围

1. 新增独立的 `DesignerMutationObligationExtractor`，从冻结 `requirementText`、受控 DesignFact、`scopeIn` 和 `deliverables` 提取显式仓库相对写入义务。
2. 在 `DESIGN_ACCEPTANCE_V7` 的不可变 `facts_json` 中持久化 typed obligations、来源摘录和哈希；历史 v5/v6 缺失列表时保持原行为。
3. `DesignerSessionService.startCompilation(...)` 把当前 `DesignRequirementRevisionRow.requirementText()` 传入验收冻结边界。
4. 新增 `MutationConservationPolicy`，复用 `DesignerAcceptancePathPolicy` 和运行期 `VerifierPathPolicy` 的规则覆盖语义。
5. 在 `DesignerAcceptancePlanCompiler` 组装 Stage 后、调用 `DesignerPackagePlanCompiler` 前执行守恒门禁。
6. 把守恒结果写入有界 `diagnostics_json`；不改变当前 `VerifierEngine`。

## 强合同

- `WRITE` 义务未分配时返回 `REQUIRED_MUTATION_PATH_UNASSIGNED`。
- 必改路径被禁止规则覆盖时返回 `REQUIRED_MUTATION_PATH_FORBIDDEN`。
- 项目根外路径使用现有权限/多项目错误，不转换成相对路径。
- `DELETE_REQUEST / MOVE_SOURCE` 保持删除保护并形成明确缺口。
- 宽泛技术栈 fallback 不能证明显式路径义务已守恒。
- Stage、聚焦测试和显式 `GIT_DIFF` 继续使用同一份路径集合。

## 预期修改接缝

- `DesignerAcceptancePlanning.java`
- `DesignerMutationObligationExtractor.java`（新）
- `MutationConservationPolicy.java`（新）
- `DesignerAcceptanceWorkflow.java`
- `DesignerAcceptancePlanCompiler.java`
- `DesignerSessionService.java`（只传递冻结需求，避免新增编排职责）
- 相邻测试和历史 JSON/重启恢复测试

不新增数据库列是首选；如果实现证明无法在既有不可变 JSON 合同中安全兼容，才新增 V46 迁移，并同时覆盖 fresh/upgrade SQLite。

## 验收标准

- [ ] 最小路径遗漏回归由红变绿。
- [ ] 单 Stage、多 Stage、禁止覆盖、项目根外、删除请求各有测试。
- [ ] v5/v6 历史 `facts_json` 可读取且行为不变。
- [ ] v7 快照重启后 obligations 数量、来源和哈希不变。
- [ ] 没有新增 catch-all 路径、模糊匹配或 Judge-only 降级。
- [ ] `VerifierEngine` 的 `outside allowed paths` 回归保持通过。
- [ ] 相关契约、AGENTS 正文/维护记录、版本和 JAR 按仓库公约完成。

## 聚焦验证

实现者先从实际测试名核对命令，再至少运行：

```bash
./mvnw -Dtest=DesignerAcceptancePlanningAlgorithmTest,DesignerAcceptanceFastPathResolverTest,VerifierEngineTest,FeatureMigrationTest test
```

随后执行 `./scripts/verify.sh`、JAR 静态资源检查、SHA-256 和本地提交。实施时使用 TDD，每个红绿切片完成后再继续；提交前按 Standards 与本 Spec 两轴做代码评审。

## 非目标

- 本票不自动选择遗漏义务的 Stage。
- 本票不放宽删除或项目根外写入。
- 本票不修改 Compiler 模型输出合同。
