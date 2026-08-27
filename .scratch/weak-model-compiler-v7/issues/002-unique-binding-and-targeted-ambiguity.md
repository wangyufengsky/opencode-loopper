# 002：唯一自动绑定与局部路径消歧

状态：BLOCKED

Blocked by：001

Blocks：003、004

## 目标

在 001 的路径守恒门禁之上，自动修复唯一可证明的路径遗漏；真实多候选只暴露一个局部闭集选择，不触发整稿重设计，也不把路径塞进最后一个 Stage。

## 行为切片

1. 单 Stage：显式 `WRITE` 义务唯一归属该 Stage，服务端补入同一 Stage、聚焦测试和 `GIT_DIFF` 的路径集合，不调用模型。
2. 精确事实引用：Stage 引用了产生义务的 `DELIVERABLE / SCOPE`，直接绑定。
3. 路径规则唯一覆盖：只有一个 Stage 的现有规则按运行期语义覆盖义务，直接绑定。
4. 多候选：生成 `mutationAssignments` 闭集，只允许选择服务端列出的 Stage。
5. 无候选或无效选择：返回 `REQUIRED_MUTATION_PATH_UNASSIGNED / AMBIGUOUS_ACCEPTANCE_INTENT`，保留冻结事实。

## 实现范围

- 扩展 `DesignerAcceptanceFastPathResolver.Resolution`，增加未决义务及候选 Stage 投影。
- 为 v7 binding/disambiguation 增加 `mutationAssignments`；v6 Schema 和冻结快照保持兼容。
- `merge(...)` 校验每个未决义务恰好分配一次、索引在候选闭集内且不改变锁定拓扑。
- 将唯一绑定的来源写入诊断：`EXACT_FACT_REFERENCE / UNIQUE_PATH_COVERAGE / SINGLE_STAGE`。
- UI 只显示“有一项交付路径需要确认归属”和 Stage 中文名称，不显示内部索引或原始 JSON。

## 验收标准

- [ ] 单 Stage 漏路径从 001 的 `DESIGN_INCOMPLETE` 变为服务端直接 `COMPILED`。
- [ ] 唯一事实引用和唯一规则覆盖均不创建 Compiler Session。
- [ ] 多 Stage 只请求当前路径义务，不要求模型重述场景、命令或整稿。
- [ ] 非候选 Stage、重复分配、遗漏分配和修改拓扑全部失败关闭。
- [ ] Stage、聚焦测试和 `GIT_DIFF` 路径集合完全一致。
- [ ] 多个义务可分别绑定，不因一个局部歧义丢弃已确定绑定。
- [ ] 普通 WP-1 与滚动包当前包均有覆盖；历史包行为不变。

## 聚焦验证

```bash
./mvnw -Dtest=DesignerAcceptanceFastPathResolverTest,DesignerAcceptancePlanningAlgorithmTest,DesignerSessionMcpIntegrationTest test
npm --prefix frontend run test -- src/views/DesignerView.spec.ts
```

命令需在实现时按实际测试文件复核。随后执行完整仓库交付门禁、双轴代码评审和本地提交。

## 非目标

- 不按标题相似度、目录词或最后 Stage 猜测归属。
- 不允许模型新增路径、glob、命令或 Stage。
- 不在本票改变能力候选的 Compiler 调用策略。
