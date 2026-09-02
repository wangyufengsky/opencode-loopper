# Convention 与双 Judge 默认启用资格

2026-09-02：三个角色均完成真实模型的机械拒绝后同 Session 修正，0.3.22 默认开启
`PROJECT_CONVENTION_V1` 和 `JUDGE_DECISION_V1`。本次没有放宽任何校验或权限。

## 方法与事实边界

使用已交付的 0.3.20 成品 JAR（SHA-256
`5cc75cf4f6d15692399d95adcd1b71e2036b548e000a10db5b5926bb06bc77e5`），在独立数据目录、
只读 Git 夹具与端口 18056 上运行真实 `opencode/gpt-5.4`。没有替换既有 8080 实例。

测试专用启动 wrapper 只在受管 OpenCode 子进程的 `loopper-structured` system prompt 中追加
一次性格式故障指令：Convention 首投重复一个合法组件 ID；两个 Judge 首投在真实理由中插入换行。
模型自己调用工具、接收真实拒绝并生成完整修正候选。wrapper 没有改写 MCP 请求、工具参数或响应；
服务端成品 JAR、原始角色提示、权限策略、冻结证据、编译器及生命周期均未改动。
指令没有要求 PASS，也没有规定第二投答案。原始 overlay 已保存在下方证据 JSON 中。

这是受控格式故障下的协议资格，不是自然错误率、跨模型可靠性或生产规模通过率统计。
既有正常首投资格与本次拒绝修正证据共同支持默认开关；不能只用模拟工具或人工提交替代真实模型。

## 三角色结果

| 角色 | 首投真实拒绝 | 原 Session 第二投 | owner 结算 |
| --- | --- | --- | --- |
| Convention | `PROJECT_CONVENTION_COMPONENT_UNVERIFIED`，重复合法组件 ID | 删除重复值，`ACCEPTED` | 公约预览 `READY`，未应用文件 |
| Requirement | `JUDGE_DECISION_REASON_LINE_BREAK_INVALID` | 单行理由，`ACCEPTED` | `COMPLETED / PASS` |
| Risk | `JUDGE_DECISION_REASON_LINE_BREAK_INVALID` | 单行理由，`ACCEPTED` | `COMPLETED / PASS` |

所有角色均为一个 Candidate run、一个 OpenCode Session、两次提交，提交修订从 0 到 1，
第二投使用新 idempotency key。三个 launch 都是 `COMPLETED / ABORT_ACKNOWLEDGED`，
三个 INITIAL dispatch 都是 `STOPPED`，三个 `RUN_COMPLETED` termination intent 都是 `COMPLETED`。
公约及双 Judge 的不可变 accepted result 均绑定最终 owner。
两个 Judge 属于同一批次 `e9f9281e-38c6-44a9-b51c-6b2e031ed3ac`；
测试任务结果为 `AWAITING_DECISION / SUCCEEDED`，不是未经人工确认的已发布或已提交终态。
夹具的 `git status --porcelain` 为空。

| 角色 | Candidate run | OpenCode Session |
| --- | --- | --- |
| Convention | `189b3e0a-c8e0-3116-bc47-31bfba2d3324` | `ses_fa013bccfffeljy9ecZacA6lGt` |
| Requirement | `d0254c78-d467-39fa-80e7-aae006d65f2e` | `ses_fa0130a7fffebiqaTf8eJPKwQX` |
| Risk | `5a6dffcd-6bb7-3352-8cfa-77a8c66f0ebb` | `ses_fa0130a39ffeWa4XBEv1nWWWrN` |

[持久化与原始模型工具调用证据](evidence/mcp-default-enablement-20260902.json) 包含六次真实私有 MCP
调用参数/响应、服务端 attempt、权限闭集、accepted result、停止证明、测试 overlay 和作用域 ID。
仅提取本次三个 Session 的提交工具记录，不含模型思考、凭证或其他用户会话。

## 集中问题与修改

本轮先完整执行三角色资格、只读审查同步项，再统一修改，没有发现需要改变核心协议的新缺陷。

- 两个开关此前因资格缺失而关闭：资格完成后同时开启；保留各自显式 `false` 回滚。
- 历史 Designer 和 Recovery 集成夹具遗漏 Judge 开关隔离：明确关闭，使旧断言继续验证 Legacy 路线。
- 已有恢复测试没有覆盖运行中关闭开关：补齐 Convention 和双 Judge 接受后关闭开关仍能停止结算；
  两个 Judge 分别验证换行拒绝后修正，而非只有 Requirement。
- 配置测试补齐关闭开关时 policy、writer、冻结来源与 accepted result Bean 仍常驻。
- 公共兼容 MCP 版本仍写死 0.3.12：改与公共 Streamable/内部 MCP 共用配置版本，并验证两个公共入口一致。
- 同步七个候选合同、资格说明、版本及运维配置表；历史版本当时未获资格的记录保留。

## 回滚与验收

`LOOPPER_PROJECT_CONVENTION_CANDIDATE_V1_ENABLED=false` 或
`LOOPPER_JUDGE_DECISION_CANDIDATE_V1_ENABLED=false` 仅回滚对应的新运行；
已有 run、来源快照、结果和远端停止恢复不依赖当前开关。
安全拒绝、超时、传输或停止不确定、代次冲突仍失败关闭，最终 assistant text 不参与权威结果。

本次不修改经资格验证的两类核心编译/候选工作流。首轮组合聚焦 254 项通过，0.3.21 完整验证
跑完 1220 项后仅 Recovery 历史夹具一项失败，未产生 JAR；已一次性核对全部 Judge 断言用法，
补齐该 Legacy 夹具并递增交付版本，再执行合并聚焦及完整门禁，不重跑真实模型资格。
精确测试数、新 JAR 校验值及运行时验收结果记录于
[AGENTS 维护记录](../AGENTS.md#12-维护记录)，不将源码测试、成品 JAR、运行进程和发布状态混作一个结论。
