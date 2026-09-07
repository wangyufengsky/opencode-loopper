# MCP 设计候选三批次优化与评测

2026-09-07。目标：减少合法候选的无效拒绝，让可修正错误能在少量提交内收敛，同时保留服务端路径、验证、权限和生命周期权威。本次交付版本为 0.3.66。

## 三批次实现

| 批次 | 实现 | 验证方式 |
| --- | --- | --- |
| 一：消除输入损失与隐藏合同 | PACKAGE_DESIGN_V1 直接组装事实；场景/阶段按 key 引用，允许显示标题重复；Markdown 保留 DS-L 来源行；数量限制共享并前置；冻结输入预检；按当前输出通道组装主提示 | 三个旧实现失败用例先复现再通过；新旧 Markdown 编译兼容、重复标题、64 场景上限、冻结范围冲突回归 |
| 二：让修正可解释且可停止 | 权威检查优先，形状错误阻止依赖它的语义检查；稳定 issue ID；resolved/remaining/introduced；规范候选哈希检测重复和振荡；V70 冻结可选提交预算 | 持久化四次预算、SQL 不可变与次数守卫、幂等重放、历史无限运行、数组重排、诊断截断、重复及振荡回归 |
| 三：用模型检验并补足问题 | 隔离 OpenCode + MCP 编译器探针；相同模型、温度、需求与预算对照 0.3.64；自然与故障注入分组；试跑发现 SCOPE 文字再推断造成误拒绝，改由结构化种类明确表示范围内事实 | 合并聚焦 181 项通过；真实模型对照完成；完整 JAR 门禁结果见交付记录 |

本次统一回归、打包和本地提交；三批次不各自产生一套不同的协议或运行时。

## 设计选择与算法

采用“确定性检查 → 有界反例 → 完整候选修正”的闭环。检查器负责正确性，模型只修正候选语义。其方法与 [CEGIS](https://people.csail.mit.edu/asolar/papers/asplos06-final.pdf) 的反例驱动思想一致；这里不声称对任意自然语言需求存在有限次成功保证。

首先去掉 JSON → Markdown → 语义重解析造成的信息损失。`PackageDesignFactAssembler` 保留候选 key，渲染器另行提供来源行；Legacy Markdown 继续走旧入口。数量限制由 `PackageDesignLimits` 在 schema、codec 和旧提取器间共享：场景最多 64，总事实为 requirements + scenarios + deliverables + reviews + stages，最多 128；直接软件最多 6 阶段，分包最多 3。

诊断按依赖排序。`PackageDesignCandidateEvaluation` 先执行原有禁止字段扫描，再验证形状，形状正确后才调用语义策略，避免缺少一个数组导致一串无法使用的下游错误。冻结范围冲突由 `PackageDesignInputPreflight` 在模型调度前识别，归为人工输入。候选错误仍按实际字段定位。

`CandidateRepairProgress` 用实体 key/位置、错误码和预期约束的 SHA-256 标识问题；实体在数组中移动不会变成新问题。仅当相邻诊断都完整时计算已解决和新增项。重复检测比较规范 JSON 哈希，读取最近三次持久化结果，能够识别相同对象重投和 A→B→A；有限策略下停止，无限策略下仅报告。不会存储拒绝候选正文，也不让模型填写服务端状态。

本次没有引入 QuickXplain、SAT/SMT 求解器或额外模型裁判。当前主要损失来自表示转换、合同缺失与反馈路径，先修这些更直接。只有未来出现可形式化且单调的约束冲突，才适合考虑 [QuickXplain](https://cdn.aaai.org/AAAI/2004/AAAI04-027.pdf)。[PICARD](https://aclanthology.org/2021.emnlp-main.779/) 一类受约束解码可减少语法错误，但不能解决冻结范围冲突，也不应在 Provider 未证明支持时强行启用。

工具说明和评测方法参考 [Anthropic 工具设计与评测实践](https://www.anthropic.com/engineering/writing-tools-for-agents)；MCP 业务错误通过模型可见的工具结果返回，参见 [MCP Tools 规范](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)。这些方法支持本次设计取舍，不是本项目通过率的证据。

## 有限修正的配置与历史兼容

```bash
LOOPPER_PACKAGE_DESIGN_CORRECTION_LIMIT=4
```

含义是首投加最多三次修正，允许 2–16；默认 0 保持既有无限提交语义。仅新建 PACKAGE_DESIGN_V1 INTERNAL_MCP 运行可冻结此值。V70 中旧记录为 NULL；重启、修改配置、重新取得同一 run 都不会改变它。旧 `maxAttempts=3` 继续作为合同身份，不能拿它冒充新预算。

有效接受优先；达到预算而仍不合法，或有限运行重复完整诊断下的相同候选，进入 WAITING_INPUT。不会为了“通过”强制降级到 Markdown 或放宽验证。幂等重放返回原回执，不消耗新次数。超时、Provider 传输、权限、取消、并发版本与远端停止确认仍使用独立规则。

V2 回执新增 `repairProtocolVersion=PACKAGE_REPAIR_V1`、`correctionLimit`、`repairProgress`、`stopReason`。问题 ID 是不透明稳定标识，通过零基 `problemIndex` 关联本次 problems 中的 JSON Pointer；不重复长指针，以保留完整响应的 96 KiB 字节预算。`comparisonComplete=false` 时不得从 resolved 为空推断没有进展，也不能把未列出的错误当成消失。

## 真实模型评测方法与边界

运行 `scripts/qualify-package-design-model.py`，配合测试类 `PackageDesignModelProbe`。每次使用隔离配置、数据库、缓存和只读 Java 夹具，模型只能 read/glob/grep 和调用唯一 MCP 工具。探针执行对应版本的生产 `DeterministicPackageDesignCompilation` 和有界诊断；外层四次预算由评测适配器统一施加，便于比较旧版无限策略与新版编译器。

模型为 `opencode/gpt-5.4`，温度 0，四个任务各重复两次。自然任务包括普通场景、同名但不同 key 的场景，以及预留的反引号/分号标题场景。第四个任务要求首投引用不存在的 key，单独作为故障修正资格，不纳入自然首投率。预留场景没有用于本轮试跑调参，但样本都来自同一小型 Java 测试夹具，覆盖面有限。

两个版本使用同一份独立简短任务提示和各自发布的输入 schema。此对照主要检验编译器与错误反馈，不是完整生产 Designer 主提示的 A/B；不证明 HTTP 鉴权、owner 结算或服务端终止远端 Session。有限策略及持久化由应用集成测试另行验证。模型 Provider 可能有未公开的版本变化，温度 0 也不意味着输出完全一致。

基线为保留在隔离目录的 0.3.64 JAR，其 SHA-256 为 `a26a7975859d4dfa79bbbcc1f145b0a021112b4a60699aec6479e240028a689e`。改进组使用打包前复制出的同一批生产编译类，避免 clean verify 删除正在使用的类。试跑用于定位问题，独立存档，不计入正式对照。

通过率按任务运行计数，首投和最多四次分别报告；没有提交、超时、内部错误和预算耗尽均不能算成功。令牌只统计 Provider 返回的 step-finish 数值，不用文本长度估算。诊断截断不能用于宣称全部根因都已修复。

正式对照结果（不含试跑）：

| 指标 | 0.3.64 基线 | 改进编译器 |
| --- | --- | --- |
| 未注入故障的夹具运行数 | 6 | 6 |
| 首投接受 | 0/6 | 1/6 |
| 两次内接受 | 3/6 | 6/6 |
| 四次内接受 | 3/6 | 6/6 |
| 同名场景任务 | 0/2，均用尽四次预算 | 2/2，分别首投/第二投接受 |
| 无 MCP 提交 | 1/6 | 0/6 |
| 运行耗时中位数（含读取、模型和启动） | 46.20 秒 | 30.96 秒 |
| 引用故障注入（单独统计） | 2/2，均第三投接受 | 2/2，均第二投接受 |

两个接受的同名场景候选仍保留用户要求的重复标题，没有通过改名绕过要求。所有 16 个正式运行的夹具均未修改，无超时。旧版有一个模型没有提交 MCP；它不算本评测成功，但本适配器没有评估应用的零提交 Markdown 兜底，不能据此断言完整应用必然失败。

回执、哈希、Provider step 令牌数与逐次结果见 [机器可读证据](evidence/mcp-design-optimization-20260907.json)。没有把模型思考或认证写入证据。

正式对照后，将 includes/dependencies 的引用规则写入 schema 字段说明，并做两次补充试跑。两次都在第二投接受；首投错误变为遗漏 requirementRefs 覆盖，没有再出现 includes 填路径/遗漏场景的错误。该结果单独保存在证据中，不并入正式对照，也不能凭两次试跑认定首投率已经提高。

结果支持“本组可修正问题能更快收敛”和“同名引用误拒绝已消除”，尚不支持稳定高首投率。85% 首投、95% 四次内成功仍是未来扩大真实任务集后的目标。默认继续无限策略；用户可显式设置四次策略。

## 复现

先使用 JDK 21 编译生产类和测试探针，再提供依赖 classpath。基线必须使用对应 JAR 解包后的生产类，不能混入新版生产类。

```bash
python3 scripts/qualify-package-design-model.py \
  --java "$JAVA_HOME/bin/java" \
  --classpath '<production-classes>:<probe-test-classes>:<dependency-jars>' \
  --output '<new-isolated-directory>' \
  --model opencode/gpt-5.4 --repeats 2
```

输出目录包含逐次回执和模型事件。认证只在隔离运行期间复用，结束后删除复制件；不得把认证、模型思考或其他用户会话纳入仓库证据。可提交证据仅包含本轮汇总、候选哈希、错误码与工具回执。正在运行的 8080 实例和业务工作区不参与此评测。
