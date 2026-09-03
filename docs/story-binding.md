# 故事绑定与 AI 工作量统计

开始设计时可选“开启故事绑定”。Loopper 先查询当前项目目录所连接 OpenCode 的 `GET /command`；仅注册了 `aicoding` 时允许开启。检测中、命令缺失或连接失败均显示原因并禁用开关，普通设计仍可提交。“重新检测”可在插件安装后刷新。切换项目或运行时代次会重新检测，探测不会创建 Session、调用模型或提交统计。

开启后必填系统编号和故事编号。两者以字符串持久化，保留前导零，最多 128 字符，不允许空白或控制字符。配置只在创建设计时设置，普通提交和附件提交一致；历史设计和旧客户端默认关闭。任务确认、执行重试与 Recovery 沿用同一绑定链。

## 会话行为

- 每个实际派发业务提示且属于统计范围的新 Session 都使用 `aicoding start <系统编号> <故事编号>`，不自动使用 `continue`。相同 Session 的继续讨论、问题回答和 Provider 重试不重复绑定。
- 仅统计需求设计师、工作包设计师（包含 PACKAGE_DESIGN_V1 候选会话）和 Implementation。Router、规划、Compiler、Reviewer、双 Judge、其他角色的修复/finalizer 与未知拥有者不统计。历史其他角色的记录保留供审计和消息隔离，不再补发 complete。未开展工作的 fork 和服务端生成步骤不制造统计调用。
- 业务结果先落库；只有所属流程已经不再复用该远端 Session，才提交 `complete`。正常结束、失败和取消均适用。远端一次 IDLE、等待用户回答和单独 abort 不是业务结束判据。
- 统计调用持续等待，不再设置 30 秒自动超时。全局弹窗显示“正在开启／完成故事点统计”、真实模型输出和用时，提供“取消本次统计，继续任务”。真实调用失败仍只写入统计记录与一次 Loopper 内通知，不消耗业务重试预算，不关闭全自动模式，不回滚业务结果。
- 每个 Session 的 BEGIN/COMPLETE 各有独立且唯一的调用和消息 ID。网络请求前以短事务持久化，网络操作在事务外。结果未知不自动重发；重启把遗留 PREPARED/CANCELLING 标为 UNKNOWN。

SQLite 继续使用 WAL、IMMEDIATE 和既有 busy timeout。驱动在锁失败后可能留下不一致的 JDBC 事务状态，连接池会丢弃 SQLite BUSY/LOCKED 或明确无活动事务的连接，避免后续任务查询复用坏连接；不会因此重发统计或重试业务。

通知示例：“AI 工作量统计失败：统计服务暂不可用，任务继续执行。”设计通知保存在系统消息中，任务通知保存在事件记录中。通知和业务消息使用数据库原子追加，避免并发序号冲突。统计通知通过独立 SSE 事件刷新持久化消息；即使设计已停止轮询，也会显示结束调用失败，且不会覆盖业务角色或运行状态。重复刷新或重放不重复追加通知；通知投递异常也不能影响任务。

## 统计弹窗与手动取消

统计弹窗独立于设计提交响应，因此设计师的首条业务提示尚未发送时也可看到统计状态。页面每 1.2 秒刷新调用列表与当前统计的输出；并行调用可切换查看。完成回执不会阻塞业务，关闭结果会持久化确认，刷新不重新弹出已关闭的历史结果。输出仅来自该统计消息的 assistant 子回复；不展示 HTTP 原始信封，也不读取业务设计稿。

取消只作用于指定调用。服务端先领取取消，再在业务提示尚未释放时核对最新远端 user 消息身份；只有它仍是当前统计消息才尝试 OpenCode abort。真实 abort 的网络请求仍有连接和读取边界；其失败不升级为业务错误。取消落为 CANCELLED，正常完成与取消只能有一个结果胜出；后续 Session 继续继承故事绑定，结束时仍尝试 complete。已送达平台的请求无法撤回，插件前置 hook 尚未生成消息或远端停止未确认时，迟到回复继续按统计身份隔离，不覆盖取消结果，也不再补发 abort。

V66 保存取消状态、模型活动快照及关闭确认。BEGIN 的等待区间从相关 Session/Task 的业务超时预算中扣除，并行区间按并集合并；业务自己的执行时限仍保留。统计输出读取失败只提示无法刷新，不改变正在执行的统计。进程重启不盲目重发未知请求。

本地接口：`GET /api/story-accounting` 列出未关闭调用，`GET /api/story-accounting/{id}` 查询活动；`POST /api/story-accounting/{id}/cancel` 和 `/dismiss` 分别取消统计、确认关闭结果，两者要求 `X-Loopper-Local-UI: 1`。

## OpenCode 接入

使用原生 `POST /session/{id}/command`，命令名固定为 `aicoding`，服务端生成 arguments，不调用 shell。用户在终端里看到的斜杠只是命令入口，HTTP command 字段不带斜杠。

统计命令使用受管运行时自动配置的 `loopper-accounting` Agent（两步，规则固定先 `* deny` 后 `aicoding* allow`，仅允许 aicoding 命名工具）。受管运行时还加载内置消息保护插件，隔离模型上下文，并禁止统计回合调用业务工具；该保护插件不会提供 aicoding 命令。受管 Designer 的 Session 在原有权限之外明确允许 `aicoding_*`，因为 OpenCode 的 Session deny-all 会覆盖统计 Agent 的 allow；这是一项只供统计回合使用的例外。guard 按每条消息的工具开关屏蔽统计回合中的 question/业务工具以及业务回合中的 aicoding 工具，并在调用前核对消息归属。它不替换 Session 原有读写/路径权限，普通 OpenCode 手动会话不受影响。统计消息明确使用统计 Agent，业务提示恢复业务 Agent。外部 HTTP 模式还需按现场 Session 权限明确允许统计工具，并在外部 OpenCode 配置同名 Agent 与 `src/main/resources/opencode/loopper-accounting-guard.mjs`；缺失时会产生统计失败通知，主流程继续。插件若依赖其他工具名、指定自己的 Agent 或需要业务写权限，应先现场核对其实现，不能为统计放宽业务角色权限。

OpenCode 自定义命令可能产生普通 user/assistant 模型回合。统计使用 `msg_loopper_aicoding_` 消息身份；所有业务输出、结构化结果、活动与用量读取会排除对应消息及其 assistant 子消息。迟到统计回合的全局 Session 状态不能覆盖已经存在的业务消息结果。统计输出不能作为设计稿、需求快照或评审结论。BEGIN 等待期间业务状态固定投影为 RUNNING，业务问题列表为空；统计成功、错误或手动取消都不触发“必须提问”检查，只有随后真实业务回合受该检查约束。

V65 保存绑定链、Designer/Task 继承关系、每个远端 Session 的角色与顺序、BEGIN/COMPLETE 调用、消息 ID、返回摘要、错误和插件返回的 runId。SUCCEEDED 表示命令响应成功，不等于独立验证内网平台已入账。平台回执格式、业务错误字段和并行 run 语义需用内网实际插件确认。OpenCode 若仅返回通用错误，通知保留 HTTP 状态和诊断标识，不推断被隐藏的插件内部原因。

## 开发环境与复现

`scripts/aicoding/native-tools-plugin.mjs` 注册原生 `aicoding_story_start/continue/complete/status/sync` 工具，只有模型实际调用工具才向接收端发送请求；命令钩子只生成提示。`mock-plugin.mjs` 保留用于前置 hook 延迟故障测试；`mock-receiver.mjs` 提供独立本地接收端、请求台账、可控延迟/错误/丢失响应以及确定性模型。模拟插件不会默认安装到正式运行环境，也不访问内网平台。

`start-qualification.mjs` 启动独立端口、数据库和 XDG 目录的 Loopper/真实 OpenCode 环境，输出 environment.json 路径。模型只访问 localhost。可传入已构建 JAR：

```bash
node scripts/aicoding/start-qualification.mjs target/opencode-loopper-0.3.42.jar --native-tools
node scripts/aicoding/qualify-workflow.mjs /绝对路径/environment.json
# 等待超过 30 秒仍运行，再手动取消并验证完整业务链
node scripts/aicoding/qualify-workflow.mjs /绝对路径/environment.json --long-wait
# 模型已返回正文时取消；附加 --manual-cancel 可改由浏览器点击
node scripts/aicoding/qualify-workflow.mjs /绝对路径/environment.json --model-wait
node scripts/aicoding/qualify-workflow.mjs /绝对路径/environment.json --model-wait --complete-wait
```

`--native-tools` 默认读取本机 `~/.config/opencode/node_modules/@opencode-ai/plugin/dist/index.js` 的插件 API；非此布局可用 `AICODING_MOCK_PLUGIN_API` 指定实际模块的 file URL。模拟插件与 API 只在隔离实例加载。普通/故障链路必须核对只有设计师和执行者的四次调用，且两次 BEGIN 都是 start。

接收端 `POST /control` 支持 `fail`、`delayMs`、`loseResponse`、`accountingModelDelayMs`（统计流式正文返回后的停顿） 故障注入；`GET /requests` 回读接收台账和模型请求。终止启动脚本会停止专用 JVM、受管 OpenCode 和接收服务；保留隔离目录的日志用于核验，后续可删除整个目录。原有 OpenCode 配置和 8080 服务不受影响。

自动化测试覆盖配置、前导零、继承、唯一调用、网络事务边界、持续等待/失败/手动取消、消息分流、重启未知结果和 UI 探测竞态；真实调用验收记录单独保存。模拟成功证明的是 Loopper 接入与容错能力，不能代替内网插件的现场验证。
