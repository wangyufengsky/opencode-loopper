# 004：Shadow 评估、上线门槛与完整交付

状态：DONE

Blocked by：无（001、002、003 已完成）

Blocks：无

## 目标

用离线 corpus、只读 shadow 和真实弱模型回放证明 v7 提升的是端到端可执行率，而不是只提高 Compiler 表面通过率；达到门槛后仅对新设计启用。

## 评估资产

建立版本化 golden corpus，至少包含 [原型回交](../prototype-handoff.md) 的 10 类故障以及：

- 显式双路径遗漏；
- 唯一和多候选 Stage；
- 允许/禁止重叠；
- 项目根外路径；
- 删除/rename source；
- 唯一最优和真实同分能力；
- 弱模型额外字段、别名、遗漏、重复和越界；
- Java/Node/Python、普通单包和滚动包；
- v5/v6 历史快照恢复。

每个样本固定输入、预期权威状态、独立修改义务/硬缺口基数、模型调用上限、路径守恒、
focused-test 覆盖和硬阻断结果；评估必须精确执行引用的 guard，不能仅检查方法存在或直接聚合手填结果。
corpus 报告只发布预期与 guard 执行证据并标记 `authoritativeGate=false`；生产编译链对同一冻结输入实际
产生的 shadow 是权威实测但不是完整资格，只有全部精确 guard 与该实测共同通过的 qualification 才是
`authoritativeGate=true` 的权威本地门禁。
关键生产 guard 必须把实际产生的 Compiler 调用、重设计、修改义务、硬缺口、Judge/focused 和危险授权
有界计数发布给测试专用 registry；qualification 必须对这些实测逐项断言，不能只聚合绿色测试数。
registry 只接受登记过的 evidence ID、指标名和标记；闭集选择必须记录工作流实际 prompt/Session 数，
硬缺口与危险授权必须从 guard 的真实结果对象推导，未知字段、负值或冲突值均失败关闭。

## Shadow 规则

- v6 仍为权威结果时，v7 只读取同一冻结输入并生成有界差异，不创建额外 OpenCode Session，不修改状态或 Task。
- 对比字段：编译状态、义务覆盖、Stage 路径、能力集合、Judge-only 数、模型调用需求和设计缺口。
- 不保存原始模型输出、绝对路径或未脱敏需求正文到指标。
- shadow 失败不能推进或破坏当前 Designer 生命周期。

## 上线门槛

- [x] 显式必改路径逐样本基数不消失且守恒 100%。
- [x] 已知“编译成功、执行期路径遗漏”逃逸为 0。
- [x] 必须阻断硬缺口逐样本基数不消失且 100% 保持阻断。
- [x] corpus 端到端可执行率不低于 v6，目标样本有提升。
- [x] Compiler 模型调用和整稿重设计均不高于 v6。
- [x] Judge-only 占比不升高，focused-test 覆盖不下降。
- [x] 项目根外、删除和外部系统写入自动授权为 0。
- [x] fresh/upgrade SQLite、重启恢复、普通 WP-1 和滚动包兼容通过。

## 启用顺序

1. 离线 corpus 预期与精确 guard；
2. 生产编译链同输入实测 shadow；
3. 新 `DIRECT_SOFTWARE_DESIGN / WP-1`；
4. 新滚动工作包；
5. 历史 v5/v6 永不迁移。

若任一门槛失败，保持 v6 权威并输出具体差异；不能通过关闭运行期 `GIT_DIFF` 或扩大路径回退来过门槛。

## 完整交付

- 更新 README、架构、Designer、AI 角色、OpenCode、验证器和代码设计契约的已实现事实。
- 更新 AGENTS 正文、维护记录和全部发布版本引用。
- 运行相关聚焦测试、前端测试、`./scripts/verify.sh`、JAR 静态资源检查和 SHA-256。
- 使用隔离数据目录/JAR 做至少一次真实弱模型 Designer 到 Review Gate 回放，证明 Task 数仍为 0，记录模型调用、路径守恒和最终状态。
- 不替换 8080 运行实例，除非用户另行授权。
- 创建范围内本地提交；不推送、不打标签、不创建 Release，除非用户明确要求发版。

## 最终报告

同时报告 v6/v7 的设计编译、端到端可执行、模型调用、整稿重设计、硬阻断、Judge-only 和路径守恒结果。原型数字必须标记为 synthetic，生产回放数字必须说明样本和运行边界。

## 完成证据（2026-08-27）

- `scripts/evaluate-weak-model-v7.sh` 精确执行 22/22 corpus guard、3/3 补充指标 guard 和 1/1 同输入生产链 shadow；v7 端到端可执行样本由 0 提升到 1，v6/v7 Compiler 模型调用分别为 2/0，整稿重设计、已知路径逃逸和危险自动授权均为 0。
- 报告 SHA-256：corpus `d2f70d385eec511a37d2e26143521282c05fedeaf6c8943756080f286e03376a`，read-only shadow `5ba42ec1d84e633aa14c347ba1fd02882bd4ecbe840ab653621e10034cc43bd6`，qualification `e96bc7d7904029f9160e12a5934b3968c177922c87f1a6360e34e95a3052d679`。
- `./scripts/verify.sh` 通过：Java 742 项（0 失败、0 错误、2 跳过），Vitest 230/230；`target/opencode-loopper-0.2.73.jar` 为 283975191 字节，含 112 个 SPA 静态条目，SHA-256 `8ff7b6ddc4df69fde1cc6ea80c1d193ef001da63f33886bcc00a1e9508aa615a`。
- 隔离 JAR 使用 OpenCode 1.18.23 + `opencode-go/deepseek-v4-flash` 完成真实 `DIRECT_SOFTWARE_DESIGN / WP-1` 回放：Designer `19e7fb11-cb89-41f0-8d40-2c0a9ee6eb44` 进入 `FINAL_REVIEW`，任务画像冻结为 `2026-08-dynamic-v7 / software-java / REQUIRED`；Compiler `server_compiled=1`、外部 Session 为空、四类修复计数均为 0，`SERVER_STAGE_HINTS` 将 3/3 修改义务守恒且未解析数为 0，草稿仅到 `DRAFT_READY`，SQLite `task` 计数为 0。隔离 18073/55387 实例随后停止，8080 未触碰。
