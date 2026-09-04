# 故事绑定与 AI 工作量统计

开始设计时可选“开启故事绑定”。Loopper 先查询当前项目目录所连接 OpenCode 的 `GET /command`；仅注册了 `aicoding` 时允许开启。检测中、命令缺失或连接失败均显示原因并禁用开关，普通设计仍可提交。“重新检测”可在插件安装后刷新。切换项目或运行时代次会重新检测，探测不会创建 Session、调用模型或提交统计。

开启后必填系统编号和故事编号。两者以字符串持久化，保留前导零，最多 128 字符，不允许空白或控制字符。配置只在创建设计时设置，普通提交和附件提交一致；历史设计和旧客户端默认关闭。任务确认、执行重试与 Recovery 沿用同一绑定链。

## 会话行为

- 每个实际派发业务提示且属于统计范围的新 Session 都先使用 `aicoding start <系统编号> <故事编号>`。只有 start 返回明确失败时，才在同一远端 Session、同一编号下自动补发一次 `aicoding continue <系统编号> <故事编号>`；continue 不递归重试，UNKNOWN、进程中断和用户取消也不补发。相同 Session 的继续讨论、问题回答和 Provider 重试不重复绑定。
- 仅统计需求设计师、工作包设计师（包含 PACKAGE_DESIGN_V1 候选会话）和 Implementation。Router、规划、Compiler、Reviewer、双 Judge、其他角色的修复/finalizer 与未知拥有者不统计。历史其他角色的记录保留供审计和消息隔离，不再补发 complete。未开展工作的 fork 和服务端生成步骤不制造统计调用。
- 同一绑定链交接时，新 Session 的 start 先等待已退役 Session 的 complete 结束或手动取消；后台收集、轮询与交接共享同一次调用。普通会话清理与统计命令按远端 Session 协调，不能中断尚未返回的 complete；其他 Session 不受该等待影响。
- 业务结果先落库；只有所属流程已经不再复用该远端 Session，才提交 `complete`。正常结束、失败和取消均适用。远端一次 IDLE、等待用户回答和单独 abort 不是业务结束判据。
- 统计调用持续等待，不再设置 30 秒自动超时。全局弹窗显示“正在开启／继续／完成故事点统计”、真实模型输出和用时，提供“取消本次统计，继续任务”。start 失败但 continue 成功时不发失败通知；continue 仍失败时只产生一次 Loopper 内通知。统计失败不消耗业务重试预算，不关闭全自动模式，不回滚业务结果。
- 每个 Session 的 BEGIN 先自动发起一次 start，明确失败时至多追加一次 continue；COMPLETE 自动发起一次。每次调用都有独立持久化消息 ID，continue 通过 `retry_of` 指向失败的 start 并保留原失败结果。网络请求前以短事务持久化，网络操作在事务外。结果未知不自动重发；重启把遗留 PREPARED/CANCELLING 标为 UNKNOWN。

SQLite 继续使用 WAL、IMMEDIATE 和既有 busy timeout。驱动在锁失败后可能留下不一致的 JDBC 事务状态，连接池会丢弃 SQLite BUSY/LOCKED 或明确无活动事务的连接，避免后续任务查询复用坏连接；不会因此重发统计或重试业务。

通知示例：“AI 工作量统计失败：统计服务暂不可用，任务继续执行。”设计通知保存在系统消息中，任务通知保存在事件记录中。通知和业务消息使用数据库原子追加，避免并发序号冲突。统计通知通过独立 SSE 事件刷新持久化消息；即使设计已停止轮询，也会显示结束调用失败，且不会覆盖业务角色或运行状态。重复刷新或重放不重复追加通知；通知投递异常也不能影响任务。

## 设计师复用（V68）

新建设计启用 `PER_PACKAGE_V1`：单包从需求讨论、回答到初稿、修改和编译返修共用一个实际 OpenCode Session；大型多包的全局讨论独立，每个包各自持有一个 Session。正常单包只有一组自动 `start → complete`，三个包则是全局加三个包，共四组。用户手动重试仍是独立调用记录。

等待回答、模型单轮 IDLE、候选停止确认、编译、校验和待确认均不退休。单包内部自动批准 WP-1 只是聚合步骤，最终确认草稿时才退休；多包在明确批准当前包交接时退休。结果先落库，再允许统计完成调用；下一 Session 的统计沿用既有串行交接。已退休后重新打开或需要清除旧附件上下文时创建下一设计轮次，历史会话、候选和统计保留。

V68 的会话及回合表保存远端身份、权限配置、模型、运行代次、业务消息 ID、候选运行 ID 和结束原因。派发、提问回答和轮询按所属设计互斥，慢统计不会占用其他设计的锁。每回合先持久化请求身份和摘要再发出；恢复只核对该请求，不把旧回复当作结果，不盲目重发未知请求。创建中断且尚未绑定业务请求的空会话允许重新领取，迟到创建方不能继续发送。运行代次变化但旧远端停止尚未确认时仍阻断替换，不能伪造停止证明。升级前已存在的设计保持旧策略，不合并历史会话、不补发统计。

## 统计弹窗与手动取消

统计弹窗独立于设计提交响应，因此设计师的首条业务提示尚未发送时也可看到统计状态。页面每 1.2 秒刷新调用列表与当前统计的输出；并行调用可切换查看。完成回执不会阻塞业务，关闭结果会持久化确认，刷新不重新弹出已关闭的历史结果。输出仅来自该统计消息的 assistant 子回复；不展示 HTTP 原始信封，也不读取业务设计稿。

取消只作用于指定调用。服务端先领取取消，再在业务提示尚未释放时核对最新远端 user 消息身份；只有它仍是当前统计消息才尝试 OpenCode abort。真实 abort 的网络请求仍有连接和读取边界；其失败不升级为业务错误。取消落为 CANCELLED，正常完成与取消只能有一个结果胜出；后续 Session 继续继承故事绑定，结束时仍尝试 complete。已送达平台的请求无法撤回，插件前置 hook 尚未生成消息或远端停止未确认时，迟到回复继续按统计身份隔离，不覆盖取消结果，也不再补发 abort。

V66 保存取消状态、模型活动快照及关闭确认。BEGIN 的等待区间从相关 Session/Task 的业务超时预算中扣除，并行区间按并集合并；业务自己的执行时限仍保留。统计输出读取失败只提示无法刷新，不改变正在执行的统计。进程重启不盲目重发未知请求。

失败、结果未知或取消后的弹窗提供“重新发起 start／continue／complete”。服务端只接受该阶段最新的一条失败记录，保留原始输出、原因与关闭状态；连续点击只能领取一次重试。V67 用 retry_of 关联前次调用，并限制同一远端 Session 同时只有一个活动统计调用。除明确 start 失败后的单次 continue 降级外，自动重试仍关闭。

业务或提问仍在复用该远端 Session 时，按钮禁用并显示原因；完成会话交接后才可重试，避免统计抢占业务回合。人工重试始终使用原 Session、原编号、原阶段和原操作；不会重启设计或任务。已成功完成统计的会话不再允许重开 BEGIN；若先重试 start 或 continue 恢复绑定，仍需对失败的 complete 点击重新发起，提交该旧会话的统计。

本地接口：`GET /api/story-accounting` 列出未关闭调用，`GET /api/story-accounting/{id}` 查询活动；`POST /api/story-accounting/{id}/cancel` 和 `/dismiss` 分别取消统计、确认关闭结果，三者（含 `POST /api/story-accounting/{id}/retry`）均要求 `X-Loopper-Local-UI: 1`；retry 立即返回新的调用快照，旧调用及其迟到响应不能覆盖新调用。

## OpenCode 接入

使用原生 `POST /session/{id}/command`，命令名固定为 `aicoding`，服务端生成 arguments，不调用 shell。用户在终端里看到的斜杠只是命令入口，HTTP command 字段不带斜杠。

统计命令使用受管运行时自动配置的 `loopper-accounting` Agent（两步，规则固定先 `* deny` 后 `aicoding* allow`，仅允许 aicoding 命名工具）。受管运行时还加载内置消息保护插件，隔离模型上下文，并禁止统计回合调用业务工具；该保护插件不会提供 aicoding 命令。受管 Designer 的 Session 在原有权限之外明确允许 `aicoding_*`，因为 OpenCode 的 Session deny-all 会覆盖统计 Agent 的 allow；这是一项只供统计回合使用的例外。guard 按每条消息的工具开关屏蔽统计回合中的 question/业务工具以及业务回合中的 aicoding 工具，并在调用前核对消息归属。它不替换 Session 原有读写/路径权限，普通 OpenCode 手动会话不受影响。统计消息明确使用统计 Agent，业务提示恢复业务 Agent。外部 HTTP 模式还需按现场 Session 权限明确允许统计工具，并在外部 OpenCode 配置同名 Agent 与 `src/main/resources/opencode/loopper-accounting-guard.mjs`；缺失时会产生统计失败通知，主流程继续。插件若依赖其他工具名、指定自己的 Agent 或需要业务写权限，应先现场核对其实现，不能为统计放宽业务角色权限。

OpenCode 自定义命令可能产生普通 user/assistant 模型回合。统计使用 `msg_loopper_aicoding_` 消息身份；所有业务输出、结构化结果、活动与用量读取会排除对应消息及其 assistant 子消息。迟到统计回合的全局 Session 状态不能覆盖已经存在的业务消息结果。统计输出不能作为设计稿、需求快照或评审结论。BEGIN 等待期间业务状态固定投影为 RUNNING，业务问题列表为空；统计成功、错误或手动取消都不触发“必须提问”检查，只有随后真实业务回合受该检查约束。

V65 保存绑定链、Designer/Task 继承关系、每个远端 Session 的角色与顺序、BEGIN/COMPLETE 调用、消息 ID、返回摘要、错误和插件返回的 runId。SUCCEEDED 表示命令响应成功，不等于独立验证内网平台已入账。平台回执格式、业务错误字段和并行 run 语义需用内网实际插件确认。OpenCode 若仅返回通用错误，通知保留 HTTP 状态和诊断标识，不推断被隐藏的插件内部原因。

## 开发环境与复现

`scripts/aicoding/native-tools-plugin.mjs` 注册原生 `aicoding_story_start/continue/complete/status/sync` 工具，只有模型实际调用工具才向接收端发送请求；命令钩子只生成提示。`mock-plugin.mjs` 保留用于前置 hook 延迟故障测试；`mock-receiver.mjs` 提供独立本地接收端、请求台账、可控延迟/错误/丢失响应以及确定性模型。模拟插件不会默认安装到正式运行环境，也不访问内网平台。

`start-qualification.mjs` 启动独立端口、数据库和 XDG 目录的 Loopper/真实 OpenCode 环境，输出 environment.json 路径。模型只访问 localhost。可传入已构建 JAR：

```bash
node scripts/aicoding/start-qualification.mjs target/opencode-loopper-0.3.49.jar --native-tools
node scripts/aicoding/qualify-workflow.mjs /绝对路径/environment.json
# complete 延迟 40 秒且同一故事不允许重叠 run，核验交接顺序
node scripts/aicoding/qualify-workflow.mjs /绝对路径/environment.json --handoff-wait
# 等待超过 30 秒仍运行，再手动取消并验证完整业务链
node scripts/aicoding/qualify-workflow.mjs /绝对路径/environment.json --long-wait
# 模型已返回正文时取消；附加 --manual-cancel 可改由浏览器点击
node scripts/aicoding/qualify-workflow.mjs /绝对路径/environment.json --model-wait
node scripts/aicoding/qualify-workflow.mjs /绝对路径/environment.json --model-wait --complete-wait
```

`--native-tools` 默认读取本机 `~/.config/opencode/node_modules/@opencode-ai/plugin/dist/index.js` 的插件 API；非此布局可用 `AICODING_MOCK_PLUGIN_API` 指定实际模块的 file URL。模拟插件与 API 只在隔离实例加载。普通链路必须核对只有设计师和执行者的四次调用，且两次 BEGIN 都是 start；start 明确失败的故障链路还必须核对其后各自至多一个 continue、独立消息 ID 与 retry_of，并确认 UNKNOWN/取消不补发。

接收端 `POST /control` 支持 `fail`、`delayMs`、`loseResponse`、`onlyOperation`（限定故障操作）、`strictActiveRun`（活动故事拒绝再次 start）、`accountingModelDelayMs`（统计流式正文返回后的停顿） 故障注入；`GET /requests` 回读接收台账和模型请求。终止启动脚本会停止专用 JVM、受管 OpenCode 和接收服务；保留隔离目录的日志用于核验，后续可删除整个目录。原有 OpenCode 配置和 8080 服务不受影响。

设计师复用联调另提供 `--reuse` 模型夹具，仍经过真实 OpenCode 和原生插件：

```bash
node scripts/aicoding/start-qualification.mjs target/opencode-loopper-0.3.49.jar --native-tools --reuse
node scripts/aicoding/qualify-reuse.mjs /绝对路径/environment.json --execute --slow
# 完整分包设计兼容模式：全局和三个包，每包修订一次
node scripts/aicoding/start-qualification.mjs target/opencode-loopper-0.3.49.jar --native-tools --reuse --nonrolling
node scripts/aicoding/qualify-multi-reuse.mjs /绝对路径/environment.json
```

自动化测试覆盖配置、前导零、继承、唯一调用、网络事务边界、持续等待/失败/手动取消、消息分流、重启未知结果和 UI 探测竞态；真实调用验收记录单独保存。模拟成功证明的是 Loopper 接入与容错能力，不能代替内网插件的现场验证。
