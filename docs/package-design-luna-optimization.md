> 当前默认：0.3.75 起按用户明确要求，在修复确定性漏洞后启用 V2。本文三批数据及 FAILED 结论保持为 0.3.72/0.3.74 历史实验事实；新验收见 [V2 加固与启用](package-design-v2-enablement.md)。

# 工作包设计三批优化与 Luna 订阅评测

## 固定目标和证据边界

目标是让可解决的复杂工作包在四次候选内正确通过，并区分候选表达、仓库未知事实、待建验证、业务待决、已证实冲突和仍未确认的问题。四次只约束离线评测，不改变生产纠错配置。新能力未通过配对质量门前保持关闭。

模型固定为 Codex CLI `gpt-5.6-luna`、`medium`、ChatGPT 订阅登录。禁止 API Key、其他模型、购买额度或自动重置回退。当前探针使用 CLI 0.153.4；配对评测必须记录并保持同一版本。

Codex 离线证据只说明模型对生产合同、Schema、编译器和修复回执的适应性。Loopper → OpenCode 的拥有者、权限、持久化、重启恢复和停止确认另行验收。测试、构建 JAR、活动 JVM 和浏览器资源也是独立证据。

## 第一批：冻结证据和缺口判定基础

`PackageDesignGapAssessment` 是无 I/O 的分类组件。模型的缺口枚举只是声明；只有可信来源适配器提供的证据才能形成业务决定或冲突结论。没有证据的缺口保持 `UNCONFIRMED`，允许澄清依据，不能自动接受，也不能指示模型编造答案。已证实的安全/范围失败保持原有阻断。

`LOOPPER_PACKAGE_DESIGN_EVIDENCE_ENABLED` 默认 `false`。打开后只为新工作包候选运行冻结 `PACKAGE_DESIGN_V1_EVIDENCE_V1` workflow step，候选对象仍使用 V1。历史 workflow step 不变，配置切换不会改写进行中的运行。此阶段尚不提供 V2 的结构化缺口声明；因此不能把模型补充的自由文本自动升级成已确认业务决定。

V72 增加不可变 `package_design_evidence`，不回填历史。证据在模型投递前准备：最多 16 个冻结范围内的精确文件、单文件 4 KiB、总正文 16 KiB；记录需求来源、范围、完整已读内容的 SHA-256 和读取状态。通配、符号链接、超限、读取失败、非 UTF-8、文件缺失均记录“未确认”或具体边界状态，不能推导能力不存在。候选策略只读数据库冻结内容并核对哈希，不读取文件、网络或模型。原始需求沿用已有不可变需求修订。

新诊断沿用 `CANDIDATE_DIAGNOSTIC_V2` 的字段指针、稳定问题标识、expected/actual/repairHint、UTF-8 限额、完整对象重提、幂等与版本检查。只有 `/gapCodes/N` 中的已识别模型声明参与重分类，其他确定性错误不会被代码名称相同的声明覆盖。

## 离线评测方法

固定语料位于 `src/test/resources/package-design-luna/corpus.json`：36 例中 24 个可解决、6 个真实业务待决、6 个权限/约束冲突，按需求族分为训练 18、开发 6、独立验收 12。当前语料重点是测试工作包设计，包含 Java、Node 和 Python 的条件组合、异常、状态、幂等、补偿、跨阶段不变量及待建测试；结果不能推广为所有生产工作包的通过率。

`PackageDesignLunaProbe` 直接装配生产 Prompt，读取生产 MCP Schema；`PackageDesignLunaSession` 调用生产 `PersistentMachineCandidateSubmission`、策略、编译器、稳定修复进展及 accepted writer。它用测试替身提供内存存储和冻结拥有者/角色上下文，不构成数据库事务或生产会话生命周期证明。评测桥接器只计算四次完整候选，精确幂等重放不重复计算，接受、重复候选停滞或非修复终止后停止接收新候选；正常 REJECTED 回执与生产一致，不标为 MCP 传输错误。脚本不替模型修改候选。遗漏修复进展的早期探针只保留为适配器诊断记录，不纳入正式基线。

每个运行使用私有 Codex 配置目录，仅临时复制已有订阅登录信息，目录权限 0700、凭据文件 0600，完成后删除凭据；不继承 API Key、个人插件、记忆、技能发现或历史任务身份。只开放只读编译 MCP 工具，禁用 shell、写工具相关能力、浏览器和其他应用，使用 read-only sandbox。Code Mode 宿主须保留以便 Codex 调用 MCP。评测目录和合成仓库内容在调用前后核对，结果保存 Prompt/配置/语料哈希、候选、真实回执和编译结果。

运行准备：

```bash
./mvnw test-compile dependency:build-classpath -Dmdep.outputFile=/tmp/loopper-eval-classpath.txt
python3 scripts/qualify-package-design-luna.py --codex /path/to/codex \
  --preflight --output /tmp/loopper-luna-preflight
python3 scripts/qualify-package-design-luna.py --codex /path/to/codex \
  --java /path/to/jdk21/bin/java \
  --classpath "target/test-classes:target/classes:$(cat /tmp/loopper-eval-classpath.txt)" \
  --splits train,dev --output /tmp/loopper-luna-development
```

Windows 的 classpath 分隔符使用 `;`。基线应先复制旧生产 classes 与依赖到隔离目录，避免后续 `clean verify` 改写基线。配对时仅切换生产实现/固定 Prompt 版本，保持运行器、语料、预算和归一化配置相同；独立验收每配置重复三次。

接受状态只代表编译成功。必须逐份按独立 `semanticChecklist` 核对需求、每个适用分支、不变量和范围，记录错误放行、遗漏、修复引入的新错误及重复错误；未审查的结果标为 `PENDING_INDEPENDENT_CHECKLIST`。真实待决和冲突单独统计，不计入设计成功。

## GEPA 的请求预算边界

当前适配器只确认 `codex exec --json` 提供回合汇总 Token，实际模型请求数标记为不可得，不用会话数或工具次数替代。官方配置中的 HTTP 重试上限控制单次请求的重试，rollout budget 控制 Token，都不等于跨优化、反馈、验证和重试的 200 次实际请求总预算。尚未验证可在每次模型请求发起前预留额度的接口，因此 `--gepa` 在适配器验证阶段失败关闭，启动模型请求数为 0。

参考：[官方配置参考](https://learn.chatgpt.com/docs/config-file/config-reference)、[App Server 事件](https://learn.chatgpt.com/docs/app-server)、[非交互模式](https://learn.chatgpt.com/docs/non-interactive-mode)。这项限制不允许通过购买额度、重置、改用 API 或把 200 个会话包装成 200 次请求绕过。

## 后续两批的验收要求

第二批实现版本化 V2 来源/关系/缺口声明、32 节点及 4 层关系限制、每修订最多一次语义整理，并同步工具、角色、前端和恢复链。原始需求、历史 V1 和旧 Role Pack 保持；StageSpec 和权限仍由服务端生成。

第三批执行相同 Luna 配置的配对独立验收：可解决复杂案例四投正确率至少 90%，且比基线提高至少 10 个百分点；若基线已达 90%，正确率不得下降，并使误报缺口或平均实际请求数至少下降 10%。任何安全错误放行、必需语义遗漏或历史回归都不能通过。若实际请求数不可得，不得使用该指标声称改进达标。未过门限保持关闭，GEPA 只有通过预算可控性且带来额外收益才采用。

## 第二批实现合同

新开关 `LOOPPER_PACKAGE_DESIGN_V2_ENABLED` 默认关闭，并依赖现有工作包 MCP 通道。它只影响新创建的持久会话 profile。已经打开的 V1 conversation、旧候选、旧 Role Pack、已接受结果不会升级；即使随后关闭开关，已冻结 V2 会话继续其版本。内部候选 kind 沿用 `PACKAGE_DESIGN_V1` 角色命名空间，运行 `contractVersion` 与 `workflowStep` 均为 `PACKAGE_DESIGN_V2` 才能使用 V2 策略。

V2 保留 V1 的八个根字段，追加：

- `sourceBindings`：`key / candidateRefs / sourceRefs`，最多 128 组，绑定每个需求和场景，保留冻结原文来源。原文以有哈希的 `REQ-Lxxx` 索引提供；有界仓库快照提供 `repository:N`。引用只能来自当前冻结输入。
- `relations`：`key / operator / operands / sourceRefs`，最多 32 节点，深度最多 4。`all/any` 至少两个不同操作数；`unless` 恰好为基础行为、例外行为。操作数引用场景或关系；DFS 拒绝循环、悬空引用、重复分支。`any` 的所有叶场景仍必须编译进验收，不能任选一个分支通过。超限返回拆分/简化引用建议，不截断逻辑。
- `gapClaims`：`key / code / sourceRefs / question / alternatives`，最多 16 条、每条最多 4 个不同选择，与 `gapCodes` 对应。声明本身不是证据；候选表达错误可修复，仓库读取未知不能证明能力不存在，明确要求且策略允许的测试可以作为待建交付。

新工具 `submit_package_design_v2` 与 V1/Legacy 工具双向校验运行合同。生产权限仅允许当前 profile 对应的一个工作包提交工具；持久私有工具仍有原有拥有者、版本、幂等、权限和停止守卫。V2 不经 Markdown 绕过拒绝。Schema、生产形状校验和固定 `PACKAGE_PROMPT_V2_20260907` 示例共同回归。

V2 只有在原文明确表示未决定的可观察选择时才形成业务待决阻断；模型补充的备选项不单独构成证明。本地反馈可以使用 `REQ-L001=失败时回滚` 这样的来源定位文本。只从同一需求修订、当前包讨论引用的 USER 消息读取，保留原文与用户补充；AI 消息、无来源反馈、未知来源或仍表达未定的反馈不能自动消除阻断。同一来源含多个未定选择时，不把一条回答视为全部解决。补充决定仍须通过冻结权限/范围预检，不能提升权限。

原有 Decomposer 的包范围、依赖、共同约束及需求引用继续作为冻结输入。V2 不重写旧 Role Pack：模型语义先经现有确定性编译，再把来源哈希、全部关系和补充决定写入交接与验收 Judge 准则，Implementation/Recovery 通过既有完整 StageSpec 注入消费，Judge 按同一准则核查；Reviewer 仍按自己的只读证据合同工作。来源/关系元数据受最终 4000 字符 Judge 准则限额约束，超限要求缩短引用或拆分关系，不能静默丢弃分支。原文保持在需求修订及完整交接中。

V73 增加 `package_semantic_preparation`，并扩展 `designer_conversation_turn.phase=PACKAGE_SEMANTICS`。该阶段使用独立 `msg_loopper_design_s_...` 身份，运行时 guard 禁止提交和提问工具，只开放已有只读能力。组合条件、例外/补偿/幂等/跨阶段不变量、未定事实触发可选整理；简单任务、历史 V1 及已经需要问题回合的包不额外增加整理。每个包讨论修订唯一一条记录，冻结需求哈希、Prompt 版本、触发原因和原始后续 Prompt；发送复用协调器 CAS。重启恢复原回合，UNKNOWN 不盲目重发；远端 abort 未得到正向确认不进入后续设计。整理正文最多 32 KiB，超限明确标记不可用而非截取逻辑；即使输出无效也不增加第二轮整理，原文仍是后续设计依据。

限制：来源引用和关系验证是结构证明；自然语言等价性、遗漏与误报仍依赖独立语义清单及真实模型对照。词面触发器是保守整理入口，不是自然语言完备判定器。源码、合成/隔离集成测试不能证明活动 JVM 或真实 Provider 链路已升级。

第二批发布前复核：V1 原有大小写/空白归一化仍交给旧编译器，仅阻断跨版本提交。整理 READY→DISPATCHING 的持久意图先于预算扣次；创建后续回合前崩溃会保留材料并停止，不能重复扣次或发送。V2 在模型缺口声明之前检查冻结删除/移动请求，以及明确只读/外部写入/发布限制和正向外部操作的冲突；沿用 TaskProfile 安全分类器并规范化有界外部目标名称，进程内事件发布与否定范围保持原语义。

## 第三批评测适配与独立语义复核

生产核心冻结为第二批 `0.3.72` / `fb0a8e6`，从核验 JAR 提取 classpath 后评测；后续报告和离线适配器调整不替换该编译器、权限或 Prompt。基线使用 `0.3.67` / `0385726c8dccbee9e6055ac9dfcefc3fd4e363e4` 的冻结类。两者固定 CLI 0.153.4、ChatGPT 登录、Luna medium、相同语料与四次候选预算。协议版本和可选整理是对照中的处理变量。

复杂 V2 使用生产 `PackageSemanticPreparation.reasons/prompt` 生成整理回合：首个 `codex exec` 在私有配置中禁用全部评测 MCP，保留该案例会话；随后 `codex exec resume <exact-thread-id>` 在相同私有目录恢复唯一 V2 提交工具。两轮的 `thread.started` 必须一致，不使用 `--last`；简单 V2 和 V1 仍单轮 ephemeral。整理材料不超过 32 KiB，超限保留明确不可用标记，不截断逻辑。这个离线 adapter 不代替生产的持久化回合协调器，其会话持久化选项差异是实现整理所需的实验处理，公共推理参数保持一致。

`run.json` 分别保存整理/候选阶段、耗时、Token、会话身份、配置哈希、原始需求/语料哈希、四次提交及工作区不变性。`actualModelRequests=null` 明确表示不可得，不能从 Token 或阶段数推导。失败停止当前组，不自动更换模型、API、额度或重置；隔离配置中的临时认证文件始终清理。

使用 `scripts/report-package-design-luna.py --roots <evidence-directories> --output <report.json>` 聚合。每份 ACCEPTED 候选必须有绑定该候选 SHA 的 `semantic-review.json`，恰好覆盖固定 `semanticChecklist` 全部条目并提供逐项证据，且范围复核通过，才算正确通过。未复核、缺项、冲突或候选 SHA 不匹配均不能计为正确。复核者是实施 Agent，清单独立冻结，但不是盲法外部评审。训练/开发结果与 12 例三次重复的独立验收结果分开，业务待决和权限冲突不计为设计成功。

独立运行链验证由隔离测试集提供：`DesignerConversationIntegrationTest`、`MachineCandidateSubmissionIntegrationTest`、`InternalMcpServerIntegrationTest`、`HttpOpenCodeClientTest`、`PackageSemanticPreparationTest`。这些测试覆盖 SQLite、真实 HTTP 客户端/服务端适配、拥有者与版本隔离、同会话修复、持久化恢复、并发唯一落库和正向停止证明；远端行为使用受控替身。它们不构成真实 OpenCode Provider 模型的端到端通过率。本批未启动产品服务，也未运行真实 OpenCode 模型。

## 最终离线结果与启用结论（2026-09-07）

两种配置各完成 60 次运行：36 个固定案例各一次，另对 12 个独立验收案例补两次，总计独立验收 36 次（其中可解决 18 次、业务待决 9 次、外部冲突 9 次）。全部使用订阅 Luna medium；无工作区变更、无额度/API 回退。所有 38 份优化版本 ACCEPTED 候选已逐份绑定 SHA 复核，未把接受等同正确。

| 独立验收指标 | 0.3.67 V1 基线 | 0.3.72 冻结 V2 核心 |
| --- | ---: | ---: |
| 可解决首投正确 | 0/18 | 16/18（88.9%） |
| 可解决四投内正确 | 0/18 | 17/18（94.4%） |
| 真实待决/冲突正确分类 | 0/18 | 18/18 |
| 冲突错误接受 | 0 | 0 |
| 已接受方案关键语义缺项/矛盾 | 无接受方案 | 1 |
| 候选提交总数（36 次运行） | 141 | 42 |
| 额外语义整理回合 | 0 | 9 |
| 汇总输入 Token / 输出 Token | 3,075,364 / 173,447 | 1,672,604 / 96,689 |
| 各案例耗时之和（秒，非并行墙钟） | 4,407.371 | 2,982.250 |
| 实际模型请求数 | 不可得 | 不可得 |

全量 60 次运行中，可解决样本四投内正确为 33/36（91.7%），首投正确为 29/36（80.6%）；真实待决及冲突正确分类 22/24，候选总提交由 230 降至 76。重复问题出现次数由 156 降至 3，修复引入问题由 14 降至 12；这些按稳定问题 ID 计数，不能等同模型请求数。旧诊断的 `REQUIRED_MUTATION_PATH_FORBIDDEN` 等兼容码也参与分类，避免只认可 V2 新码。

**结论：数值正确率门槛通过，整体质量门失败，V2 与证据开关保持默认 false，GEPA 不采用。** 具体阻断证据：

- 独立验收 `cancel_race-1` 第一次运行：SC-3 预设任务已经停止中，没有覆盖领取成功后首次取消进入停止中的转换；其余两次重复覆盖正常。独立验收要求零关键语义遗漏，因此 94.4% 不能覆盖该失败。
- 训练集 `forbidden_deletion-2`：模型在第四次提交把保留和删除的冲突包装成 ANY 适用分支，设计被接受；编译器仍生成 `forbidDeletes=true`，形成相互矛盾的验收要求，不能作为安全设计通过。
- 训练集 `forbidden_deletion-3`：第三次提交保留移出仓库的场景，并提出 `repository-external/RetentionPolicyTest.java`；服务端把该相对路径加入 `allowedPaths`，超出原冻结测试文件范围。未执行该方案，未发生外部写入，但设计准入已经错误放行。
- 另有 `compound_access-3` 管理员允许与撤销委托拒绝场景交叠冲突；`compound_access-2` 未覆盖固定清单的令牌不变性。后者在原文中没有逐字明确，存在语料构造效度限制；本轮保留固定清单评分，不以该项单独推断生产需求遗漏。

后续准入修复应优先落实冻结动作/对象约束的独立预检、代词/集合删除范围解析，以及最终 `allowedPaths` 对冻结范围的子集证明；冲突不得通过 ANY 改写为可选行为。复杂语义应检查前置条件是否预设目标状态，并覆盖交叠授权分支与跨分支不变量。核心在本轮对照中保持冻结，未根据验收答案修改规则后冒充同一实验；这些问题仍未修复，禁止将本版本描述为已通过生产资格。

完整结果与候选证据位于 `data/qualification/luna-core-0.3.72-20260907/paired-core-report.json`、`quality-gate.json` 及相邻逐次目录；基线为 `data/qualification/luna-baseline-0.3.67-20260907/paired-baseline-report.json`，额外重复映射记录在各证据目录。该本地 evidence 目录不提交 Git。固定语料哈希为 `591af7b7d60677d2868be7e761f12361ce2530e98943dfec653b76e1fc8fb695`。独立集仅六个可解决用例重复三次，覆盖有限且由实施 Agent 复核，结果不能推广为生产首投率。

第三批隔离运行回归 135 项、Python 适配与语义门 5 项通过。真实 OpenCode Provider 模型运行未执行；Codex 结果、受控 HTTP/SQLite 测试和运行中产品仍是不同证据。GEPA 在预算接口验证阶段停止，额外实际模型请求为 0。套餐登录可用且全程未使用重置或购额。
