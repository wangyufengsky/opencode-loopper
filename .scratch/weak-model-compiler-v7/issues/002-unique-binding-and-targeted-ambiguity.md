# 002：唯一自动绑定与局部路径消歧

状态：DONE

Blocked by：001

Blocks：003、004

## 目标

在 001 的路径守恒门禁之上，自动修复唯一可证明的路径遗漏；真实多候选保留为有界业务诊断，不触发整稿重设计，不把路径塞进最后一个 Stage，也不把路径权限交给弱模型选择。

## 行为切片

1. 单 Stage：显式 `WRITE` 义务唯一归属该 Stage，服务端补入同一 Stage、聚焦测试和 `GIT_DIFF` 的路径集合，不调用模型。
2. 精确事实引用：Stage 引用了产生义务的 `DELIVERABLE / SCOPE`，直接绑定。
3. 路径规则唯一覆盖：只有一个 Stage 的现有规则按运行期语义覆盖义务，直接绑定。
4. 多候选：返回 `REQUIRED_MUTATION_PATH_UNASSIGNED / AMBIGUOUS_ACCEPTANCE_INTENT`，保留冻结事实和已确定绑定，并显示候选 Stage 中文名称。
5. 无候选：返回同一有界缺口；不得按包范围、技术栈、标题、目录词或最后 Stage 猜测。
6. 当前 v7 普通包、大型任务包和滚动执行当前包在确定性绑定完整时统一服务端直编；路径缺口只进入定点人工输入门，不触发整份包设计自动重做。

## 实现范围

- 新增 `DesignerMutationStageBinder`，只处理精确事实引用、唯一运行期兼容路径覆盖和单 Stage 精确写路径三种确定性证明。
- v7 不扩展模型输入输出；v6 Schema 和冻结快照保持兼容，Mutation Obligation 的历史候选/分配索引继续为空。
- `DesignerAcceptancePlanCompiler` 在 Stage lowering 前合并确定性路径选择，再由 `MutationConservationPolicy` 独立复核所有者、禁区与证据路径一致性。
- 将唯一绑定的来源写入诊断：`EXACT_FACT_REFERENCE / UNIQUE_PATH_COVERAGE / SINGLE_STAGE`。
- 多候选写入 `MUTATION_PATH_AMBIGUOUS_STAGE_BLOCKED`，UI 只显示“路径待归属”、项目相对路径和 Stage 中文名称，不显示内部索引或原始 JSON。

## 验收标准

- [x] 单 Stage 漏路径从 001 的 `DESIGN_INCOMPLETE` 变为服务端直接 `COMPILED`。
- [x] 唯一事实引用和唯一规则覆盖均不创建 Compiler Session。
- [x] 多 Stage 多候选不调用模型，保留为只含项目相对路径和候选 Stage 名称的定点阻断。
- [x] 不允许模型、包级 fallback、技术栈 fallback、标题或目录词修改路径归属和拓扑。
- [x] Stage、聚焦测试和 `GIT_DIFF` 路径集合完全一致。
- [x] 多个义务可分别绑定，不因一个局部歧义丢弃已确定绑定。
- [x] 普通 WP-1 与滚动包当前包均有覆盖；历史包行为不变。

## 聚焦验证

```bash
./mvnw -Dtest=DesignerAcceptanceFastPathResolverTest,DesignerAcceptancePlanningAlgorithmTest,DesignerSessionMcpIntegrationTest test
npm --prefix frontend run test -- src/views/DesignerView.spec.ts
```

命令需在实现时按实际测试文件复核。随后执行完整仓库交付门禁、双轴代码评审和本地提交。

## 非目标

- 不按标题相似度、目录词或最后 Stage 猜测归属。
- 不允许模型新增或选择路径、glob、命令或 Stage。
- 不在本票改变能力候选的 Compiler 调用策略。
