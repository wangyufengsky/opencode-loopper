# 模板任务重构执行记录

状态：完成，最终本地交付为 [0.4.7](0.4.7.md)。用户先行收到的 0.4.4 内网测试 JAR 保留原样。

## 已确认范围

- 删除旧模板编辑与自动化的新触发能力，保留历史查询及已运行任务恢复。第一批只支持手动发起。
- 内置代码审查、项目人员贡献周报；项目和分支使用可搜索下拉框，默认主分支；日期使用日期控件，默认最近七天，结束日期不能早于开始日期。
- 固定北京时间，以选中分支可达提交的 committer 时间筛选，包含起止两日；远程分支在任务自有仓库同步，原项目工作区保持原状。
- 使用版本化的预制执行合同，参数确认与正式 Start 分开；不经过 AI 设计。复用 Task、Stage、Attempt、Session、预算、停止证明和双 Judge 验收。
- 两个固定阶段：Git 证据、分析与报告；提交分批处理，每个提交必须有覆盖结果。报告由服务端渲染，最多两轮内容返修。
- 贡献报告包含总报告、个人报告及排名。程序计算有效变更量；AI 仅提交有证据的离散等级，程序按冻结公式计算分数。同输入的已接受分析可复用。
- 故事直接绑定 Task，实际报告分析会话记为 Implementation；程序采集不虚构 AI 用量。
- 真实模型资格验收使用当前配置，每类五次独立执行；隔离仓库与运行数据，遵守既有预算，不重启已有服务。
- 完成交付：聚焦/集成/浏览器验证、完整门禁、递增版本完整 JAR 与 SHA-256、仅本任务本地提交。推送、发布与部署不在本次授权内。

## 贡献评分 V1

本项目定义的评分，不声称是行业统一绩效标准。数量 30、代码价值 25、必要技术难度 15、质量与验证证据 20、工程维护 10。数量分为 `30 × ln(1+L)/ln(1+Lmax)`，无有效变更时为零；其余各项以 0–4 等级换算。总分保留两位小数，同分使用竞赛排名（1、1、3）。只评价所选范围内可观察的代码贡献。

依据：

- [Microsoft SPACE](https://www.microsoft.com/en-us/research/publication/the-space-of-developer-productivity-theres-more-to-it-than-you-think/)：开发生产力具有多个维度，不能用单个活动量代表。
- [DORA metrics](https://dora.dev/guides/dora-metrics/)：应用或团队层面的交付速度和稳定性，不直接套用为个人评分。
- [Flow Impact](https://appfire.atlassian.net/wiki/spaces/FD/pages/1801945528)：考量变更上下文和认知工作量。
- [GitClear Diff Delta](https://www.gitclear.com/diff_delta_factors)：关注重复、生成及机械变更的噪声；不复制其专有权重。
- [Git log](https://git-scm.com/docs/git-log)、[mailmap](https://git-scm.com/docs/gitmailmap)：冻结提交集合与身份映射。

## 实施与验证进度

- [x] 工作区初始干净，读取当前合同和相似实现，确认与已有 Designer 报告路径的区别。
- [x] 内置合同、Git 快照及评分核心与行为测试。
- [x] 任务生命周期、报告候选、停止恢复、同批双评审、返修和故事绑定。
- [x] API、历史兼容迁移、手动入口和前端交互。
- [x] 集成回归、浏览器及真实模型十次资格验收。
- [x] 稳定候选版本、完整门禁、JAR 哈希、交付记录、本地提交。

### 当前开发证据

- Git、日期、评分和报告编译：16 项聚焦测试通过，日志 `/tmp/loopper-template-report-check-2.log`。首次报告测试有一处错误的文本断言，已修正；失败日志保留在 `/tmp/loopper-template-report-check.log`。
- V76 到 V77 升级及全新库：4 项迁移回归通过，日志 `/tmp/loopper-template-migration-check.log`。通过新增列、复制值、删除旧列扩展执行模式约束，未替换 Task 表及其外键关系。
- 新任务确认与故事迁移：6 项测试通过，日志 `/tmp/loopper-template-admission-check-2.log`。首次编译缺少生命周期调用的 reasonCode 参数，已修正，失败日志保留。
- 任务创建职责从 TaskService 提取为 TaskDraftConfirmation 后，新旧任务回归合计 83 项通过，日志 `/tmp/loopper-template-task-integration.log`。模板接入完成后 TaskService 为 2671 行，结构债务门限已相应降低。
- 新执行链路和旧触发停用：17 项通过，`/tmp/loopper-template-execution-check-2.log`。代码审查、总/个人贡献报告、同批双评审、返修上限与取消租约已经行为验证。
- 模板集中聚焦：27 项通过，1 项真实模型资格测试默认跳过，`/tmp/loopper-template-focused-all.log`。
- UI 幂等重试 Store 2 项通过，`/tmp/loopper-template-store-check-2.log`。浏览器发现并修复日期组件未注册；现 3 项通过，`/tmp/loopper-template-browser-check-3.log`，覆盖日期反向校验、默认/切换分支、同日、桌面和窄屏。
- 真实模型首轮 `/tmp/loopper-template-real-qualification-a1.log` 暴露 OpenCode Schema 传输不兼容，保留失败证据并改为严格 JSON 文本候选。当时尚未通过资格验收；后续修复和最终 a6 通过结果见下文。

### 执行实现边界

已有模板模型、Git 快照、报告编译、V77 持久化、参数确认和批次状态机。新 TEMPLATE_REPORT 已从旧 TaskService Start/恢复/发布流程分流。报告协调器负责独立目录的队列准入、Git 采集和分析；复用 Task/Stage/Attempt/ExecutionSession、现有同批双 Judge、停止协议和终态一致性检查。TaskService 的普通执行语义保持原样。

批次会话需先持久化 SessionCreationPlan 和精确 PromptRequest，再跨远端创建/发送边界；恢复使用精确标题和请求哈希查询，未知投递不得重发。TEMPLATE_ANALYSIS_NO_TOOLS 只接收嵌入的证据和结构化候选；托管故事命令沿用已有守卫例外。代码审查生成报告后即可进入评审；贡献报告先完成证据分析，再按贡献者形成离散等级。内容最多返修两轮，双评审通过后自动完成报告任务，评分低或发现缺陷不导致报告失败。

### 真实模型验证迭代

- a1：OpenCode 1.18.23 拒绝模板 Schema 请求，改为 JSON 文本候选，服务端校验与两轮返修权威保持。
- a2：真实 MCP Judge 的 canonical directory 校验揭示 `/tmp` 与 `/private/tmp` 别名差异，现冻结真实目录；10 项相关回归通过，日志 `/tmp/loopper-template-canonical-check.log`。
- a3：两份审查任务曾通过双评审，但人工复核发现把文档排除的输入和测试缺口列为缺陷，因此不计入最终资格通过。已取消仍在运行的资格任务，并停止本轮独立 JVM；原服务未动。收紧报告分析和 Judge 的误报检查，并为资格样本增加正常输入无误报、已植入算术错误必须检出的断言。
- a4：五次代码审查满足当轮质量断言。贡献周报人工复核发现将正确测试暴露实现缺陷重复列为测试文件 finding，且第四次双评审指出跨作者贡献和缺陷归属错误，触发了内容返修。该轮整体不计入最终资格通过；已通过 stop-requested 停止剩余执行，保留全部数据库和日志，独立 JVM 已退出。分析和 Judge 规则已明确同根因只定位实际错误行，正确测试是证据；贡献评分须以本人文件清单为权威，不能转移他人的贡献或缺陷责任。新资格断言要求错误样本只检出唯一实现缺陷，并检查质量评分未错扣测试作者。
- a5：五次代码审查均通过严格质量断言，其中一次用完两轮修正。贡献报告第一例三轮均遇到身份不匹配，按合同阻断并由资格脚本取消；不计入最终合格。导出失败会话证明模型使用 `Alice <alice@example.test>` 替代程序给定的身份哈希。已将真实机器身份写入响应示例，明确禁止姓名/邮箱替代，并把有界的具体候选校验错误带入下一轮返修提示。身份严格校验和两轮上限保持不变；对应执行/协议聚焦 10 项通过，日志 `/tmp/loopper-template-identity-feedback-check.log`。
- a6：代码审查 5/5、贡献周报 5/5 均通过；所有任务 COMPLETED、repairRound=0、质量断言和源仓库保全断言通过，20 次真实分析会话、20 个最终 Judge 全部 PASS。目录 `/tmp/loopper-template-qualification-20260911-a6`，原始结果 `qualification.json`，日志 `/tmp/loopper-template-real-qualification-a6-run.log`。首次启动因测试目录未创建而未建立数据库、未调用模型，启动失败日志 `/tmp/loopper-template-real-qualification-a6.log` 保留；创建独立目录后正常发起。成功命令总用时 12 分 36 秒，累计报告用量 602,962 tokens。

### 最终真实模型资格明细

模型 `opencode-go/deepseek-v4-flash`，OpenCode 1.18.23。缓存全部绕过，各例独立新仓库和任务，同日北京时间范围。奇数例为正确实现，偶数例植入 `total = sum + 1`；共六例正常样本零误报，四例错误样本各准确定位唯一实现错误。五份贡献报告均未将实现缺陷扣给正确测试作者。真实分析、独立评审均未执行夹具的测试代码，报告明确其为静态证据。

| 模板 | 1 | 2 | 3 | 4 | 5 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 代码审查耗时（秒） | 70 | 137 | 46 | 22 | 38 |
| 贡献周报耗时（秒） | 123 | 51 | 67 | 97 | 47 |
| 自动内容返修轮数（两类均同） | 0 | 0 | 0 | 0 | 0 |

独立复核了个人摘要、文件归属、缺陷证据和质量等级。数量、总分和排名由程序计算；其余等级仍由模型判断，独立运行存在等级差异，本组测试作者的质量等级为 2 或 3，不应将该小样本描述为任意模型或大型项目的普遍成功率。后续 0.4.7 仅修改报告卡片宽表布局及发布版本引用，Java 分析/评分/返修实现与通过 a6 的代码一致。
- a4 的第三次贡献评审曾出现一次 SQLITE_BUSY_SNAPSHOT，现有 MCP 幂等重试随后完成；原始日志保留，不声明零传输异常。
- 前端相关集成回归 97 项通过，`/tmp/loopper-template-final-ui-regression.log`；随后总报告到个人报告的同版本链接回归 2 项通过，`/tmp/loopper-template-panel-final-check.log`。
- 最新后端改动在 `/tmp/loopper-template-regression.n2HSzg` 独立副本编译回归，避免改写 a4 使用中的编译输出；日志 `/tmp/loopper-template-final-backend-regression-2.log`。第一次命令早于副本同步完成，命令未启动，保留 `/tmp/loopper-template-final-backend-regression.log`。
- 0.4.3 首次完整门禁执行 1654 项 Java 测试，10 项失败均为旧迁移断言仍写死 V76，3 项可选测试跳过；无执行错误，未生成正式 JAR。六个相邻测试已同步 V77，35 项迁移聚焦回归通过，日志 `/tmp/loopper-template-migration-final-regression.log`。候选之后收紧了报告归因规则，下一正式候选递增为 0.4.4。
