# W4 Task 详情迁移与独立审查

范围：A `/root/react_flow_workflow` 编写 `frontend/src/pages/w4/task/**`；原启动记录指定 `gpt-6.1-sol / xhigh`，本轮工具没有实时配置读取接口。基线 `73d851b8`。真实入口是组长接入的 `/tasks/:id` → `TaskDetailPage`，本目录不创建第二个 history，不调用 `legacy.task`，不导入 Vue/Pinia，也不修改已验收的 React Flow 画布。B 的五类 Task 动作与 C 的发布子面板由各自作者持有；本页只注册同一父 guard 与写入门槛。

## 业务与资源所有权

| 原入口与合同 | 新入口与行为 | 作者聚焦证据 |
| --- | --- | --- |
| `views/TaskDetailView.vue:350–378` 开始、暂停、恢复、取消、继续一轮、重新双评审、重做、导航 | `taskController.ts:107` 许可投影及原确认版本复核；`TaskDetailPage.tsx:60` 原路由与显式动作。`PENDING_START` 才能 Start，READY 不再次 Start。许可与生命周期来自 REST。 | `taskController.spec.ts:64` SENDING/UNKNOWN 原身份；`:73` 409；`:78` 迟到确认；`pages.spec.tsx:38` 真实React入口、READY无Start |
| `stores/taskStore.ts:189–248` overview/audit/队列与事件；`docs/architecture.md:119–129` overview 唯一拥有当前错误与 Judge 元数据 | `taskController.ts:72` overview exact ID/version；audit 仅补 Attempt/artifact，旧审计不复活当前错误；SSE 仅失效读取与真实 AI 通知，180ms 合并；客户端 close 不是服务端 stop。 | Task owner 前五项：事件不产生生命周期、立即清 stream/clock/event timers、视图 lease 重放、外国/旧版 DTO、乱序读；跨 await 退休不再发后续 GET 负控 |
| `views/TaskDetailView.vue:101–113,443–455` 最新双Judge与当前故障；前端 AGENTS 当前 TASK 告警规则 | `projections.ts:25` 当前 TASK 只匹配当前 wait reason/latest失败；当前 WAITING_INPUT 未双PASS才保 JUDGE 验证故障；历史 Judge 冲突在重新通过后不继续告警。 | `taskController.spec.ts` 最后一项精确当前 reason、运行后隐藏、失败轮次最新条、JUDGE gate 与确定性模板收尾 |
| `TaskDetailView.vue:397–441` 结果、租约与工作包阶段进度 | `TaskDetailPage.tsx:64–77` 主区保摘要/关键阻断；用户选择进度、结果、双评审、尝试、证据才显示右 context。队列持有者可导航，归档/租约/释放原因来自 DTO；工作包 Attempt 池和阶段图保留。 | `pages.spec.tsx:38` selection/Escape；原已验收 `StageDiagram`、`TemplateStepDiagram` 直接复用，底层未变；真实三皮肤浏览器由组长验收 |
| `TaskDetailView.vue:256–333` 取消/Retry 与 `:330–349` rework | `taskController.ts:144` 取消/Retry 原 Task endpoint；停止仅按权威 STOPPING/CANCELLED；重做独立 create → 已知 child Start → 精确 accepted handoff，未知 create 不按 lineage 猜结果。 | `taskController.spec.ts` reconcile accepted read 失败不POST；已知child Start未知仅原child GET、不再次create；未知create无假lookup |
| `components/SessionMonitorPanel.vue:25–102,176–258` 会话轮询、输出、问题、冻结角色 | `sessionController.ts:34` 1200ms活动/3000ms终态真实GET；`SessionMonitorPanel.tsx:24` 动态输出、5行折叠、追随输出、冻结角色按需读；Task/session/question/answers 四层原身份冻结。 | Session 8项：poll cleanup、外部问题变更保草稿、SENDING/UNKNOWN锁原body、不把问题消失当接受、accepted仅读、退休/foreign、角色按需读、用量单调 |
| `OpenCodeTodoProgress.vue:14–63`、`TokenUsageWindow.spec.ts:8,26` | Todo非权威独立布局行与有界列表；有问题回文档流；用量首次静默/只正增量/旧读不降累计/同Task切Session不重置基线；动画只transform/opacity且尊重reduced motion。 | `pages.spec.tsx:85` 25条Todo与问题文档流；Session最后一项用量100→150→80仍累计150、delta50 |
| `SessionMonitorPanel.spec.ts:176` 问题提交；W1普通dirty与未知操作门槛 | 自己普通dirty可提交，兄弟dirty/BLOCK不豁免；关闭context只收起视图，不丢草稿/operation；Session退列仍显示保留的原问题和恢复按钮；问题未知只有真实原Session GET，因DTO无 resolvedAction，消失不被当成成功证明。 | `pages.spec.tsx:47` 收起后保草稿/Start阻；`:57` 真实form、自身dirty、有效fieldset禁用、UNKNOWN且sessions=[]仍保问题、POST1；Session changed revision确认负控 |
| `TemplateTaskProgressPanel.spec.ts:17–78`、`TemplateReportsPanel.spec.ts:16–116`、`SnapshotReviewPartialReport.spec.ts:8` | 模板程序校验与AI双评审分开；原步骤图、动态总数、未开始批次、repair round、确定性收尾、阶段图保持。报告metadata/body分读，最新summary/版本选择/原bundle内编码相对链接/Markdown与整bundle下载，阶段报告按需读。 | `evidenceController.spec.ts:12–22,42` lazy/cache/隔离bundle/下载URL；`pages.spec.tsx:93` 无假AI批准与同bundle链接；`:99` partial按需、计数与退休隔离 |
| `TaskAuditEvidencePanel.spec.ts:39–80`、`ExecutionEvidencePanel.vue` | 默认结构化验证；持久 stdout/交接/任务基线 DIFF 按需；文件预览、added/removed/hunk；执行证据来源/失败/搜索分页，不用当前文件补历史证据。 | Evidence 8项：lazy正文/差异foreign/真实stdout/URL同步revoke/搜索来源与精确offset/两段读取退休负控 |
| 原 `JudgeReviewCard.vue`, `AttemptTimeline.vue`, `LayeredErrorPanel.vue` | 双Judge只读理由安全RichDocument；Attempt排序/时间/摘要；错误证据按需加载只读代码。普通区域选中披露，当前错误、等待、dirty、UNKNOWN持续留在主区。 | 真实页面选中，controller当前错误投影与lazy证据；共享RichDocument/ReadOnlyCode复用W3已验收实现 |

`SessionLifecyclePanel.vue` 在本波基线没有被 TaskDetail 或其他实际入口挂载，因此不新增虚构动作。角色、摘要、制品等技术 ID 只在用户主动展开技术标识/路径证据时展示。B/C 模块的审批、DirtyWorkspace、范围审批、Rolling、发布均稳定挂载；资格丢失只禁新请求，不热退休已持有的草稿或回执。

## 冻结与恢复边界

Task Start/Pause/Resume/Cancel/Retry API（`api/client.ts:1851–1856`）没有 requestKey/CAS body；本实现保留原 Task ID 与收到的版本作为核对事实，不发明后端幂等。UNKNOWN 恢复只调用真实原 GET，并要求足够的版本/动作证明；不足时继续 BLOCK。会话 reply/reject（`:1849–1850`）同样 keyless，不能自动重发；已接受的回执读取失败只重读。

Rework create（`:1765`）未知且没有 by-request 读入口，保持 BLOCK 与原 mode；不按“最近子任务”推断。已知 child receipt 后 Start 失联，保 child ID，不重建子任务；只恢复原 child GET。确认过的精确 handoff request 透传给每个 child，其它 siblings 仍各自校验，普通 Back/Sidebar 不拥有 permit。没有新增跨刷新持久化，本波不承诺 keyless UNKNOWN 跨刷新或 owner 重建自动恢复。

父 `canStartWrite(caller)` 仅豁免当前 caller 自己的普通 CONFIRM_DISCARD，绝不豁免自己的 BLOCK 或兄弟任何非ALLOW；`retire(false)` 先复核完整父子离开策略。实际 route/root 卸载先让 scope 失效再逐个清理；StrictMode effect cleanup 仅 detach read lease，真实 bridge lifecycle 才 force retire。Native SSE 仍由原 EventSource 自动重连与 Last-Event-ID，客户端没有偷偷重建第二个流或 POST stop。

## 作者执行与夹具修正

命令（cwd `frontend`，无真实后端、无模型调用）：

```sh
node_modules/.bin/vitest run src/pages/w4/task/taskController.spec.ts src/pages/w4/task/sessionController.spec.ts src/pages/w4/task/evidenceController.spec.ts src/pages/w4/task/pages.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w4-evidence/task-author-final.json
```

该候选实际 40/40 PASS、0 fail/skip、exit0；原始 stdout `task-author-final.log`。这是作者验证，不充当独立验收。`pages.spec.tsx:19–20` 仅在作者UI测试隔离 B/C writer，本页/controller/session/evidence均真实React/纯TS；生产实际挂载B/C由组长真实浏览器与C独审覆盖。

前轮夹具问题保留原日志：rework双阶段Promise需等真实链完成；fieldset的子textarea `.disabled` 属性不反映祖先disabled，改真实 `matches(':disabled')`，未解除禁用或强制emit；新STOPPING预期按已有displayLabel修成“正在停止”。这些不计产品缺陷。生产确切修复分列：原会话退列UI不应隐藏已保留问题；父 accepted permit 必须透传；迟到overview/evidence首GET结束后禁止再发第二GET。后两项有C独立红证据而非静态假称复现。

当前40候选14文件manifest `task-freeze-hashes.json` SHA256 `645d88687f2d7556589e4c7e524412dfd01859244d04a3ffa4c6026f5557d0b7`。若C后续发现同类SSE队列续读缺口，最终源码/hash/结果以末节补充为准，不用40候选证明后改源码。

## A 对 B 的非作者审查

审查B `actions/{owner,eligibility,judge,decision,dirty,scope,rollingController,rolling}.ts*`、`inbox/{controller,index}.ts*`、`history/{controller,index,w0-contract}.ts*` 及六spec；未改B源码。确认真实body/CAS原版本、中心动作、许可、确认后重读revision、pending可见、UNKNOWN keyless只GET、Inbox退列保原draft、read lease退休、history Task-local附件缓存。

初冻结71作者测试独立复跑，加组长保持原title/assertion的B8.2四真实Router→bridge→React映射，实际selected75/75 PASS，39名称过滤未选中不计绿，exit0；原始 `b-independent-first.{log,json}`。history导出夹具出现jsdom“不支持完整导航”warning，不把单位断言当真实浏览器下载证明。

发现分类：

1. P2 **已实际复现**：Rolling Workbench首GET已开始，owner退役后resolve仍发detail GET1；最终snapshot保持不变但请求资源违反停止边界。独立 `task/independent-b.spec.ts:9`，`independent-probes/b-read-before.{log,json}`。
2. P2 **已实际复现**：Dirty UNKNOWN原写1次，显式原workspace GET进行中退休，返回后仍发Task GET1；同样未污染snapshot、没有第二POST。独立同文件 `:16`，相同raw，两项2FAIL/exit1。
3. P2 **静态确定策略遗漏，未称实跑红**：Dirty资格消失且无draft/pending时旧读取error也随面板隐藏，与另外四面板保error策略不同。B按组长授权修正visible notice；其它历史可见标签/换行属于组长授权可达性补齐。

B作者已最小增加跨await原ticket/context+active复核，并把同义负控加入其spec；A原样两项独立负控已经2/2通过。最终73作者候选、原四历史retarget和两独立负控一起实际selected79/79 PASS、39名称过滤未选中、exit0，raw `b-independent-final.{log,json}`。该次执行后hash复核发现history/index与inbox/index后来增加共享SkinControl，其余25文件仍匹配；因此79只证实改皮肤选择器前的UI来源，新两页最终独立复验另列，不拿旧hash冒称新UI全通过。Inbox正常1500ms读轮询不发命令；hardDenied不解禁；原 Interaction id/externalRequestId/kind/task/designer/session/version/action 多字段精确核对，不用单列表消失推断UNKNOWN成功。Rolling accepted生成建议轮询只读原proposal、不自动confirm；手工/AI重排与修正仍需服务端impact显式确认。

## 尚未替代的门禁

本文件的单位测试不替代组长实际三皮肤桌面路由、native EventSource重连/Last-Event-ID、同步首资源快照与导出浏览器验收。所有root build/type/full-unit/browser由组长统一调度。本轮未改后端、canvas、package、历史W0断言或发布入口，未提交、未启动共享端口。作者/独立/browser证据分别计数，不能把未选中case或未实现跨刷新恢复计为通过。

## 最终冻结 v2 与独立来源

A最终源码14文件（不含非作者B probe）manifest `task-freeze-hashes-v2.json` SHA256 `23498d98603a73a44ce3137e9a48db77e8c82145a50e82698b2e5cb068f9eefc`；同上作者命令改输出 `task-author-final-v2.json`，实际41/41 PASS、0 fail/skip、exit0，Task16+Session8+Evidence8+真实RTL9。第15个本目录文件 `independent-b.spec.ts` 是A对B的两项独立负控，单独计数，不归作者41。

相比40候选仅必要修正：Taskheader补已存在SkinControl；原SSE `task.status` → 180ms overview → queue链退休后的queue入口严格判原read ticket；对应 `taskController.spec.ts:44` 实际事件和定时器负控（不只直接调函数）。C非作者有效前红 `task-sse-independent-before-final.json` 1FAIL，queue调用1→2；此前C临时probe错误import属于夹具错误，不计产品红。原overview/audit与evidence/failures两条C独立前红记录 `task-independent-before.json` 1PASS+2FAIL，随后原样3项after3PASS；后续4项最终结果由C归档，不由A作者自测充替。

普通取消确认的 `cancelConfirmation()` 只收起本地confirming，不清command/receipt、不发送servercancel；UNKNOWN主区恢复与父guard仍BLOCK。Task未知无幂等身份时没有跨刷新承诺。

最终B两页SkinControl必要变动后，A独立命令：

```sh
node_modules/.bin/vitest run src/pages/w4/inbox/pages.spec.tsx src/pages/w4/history/pages.spec.tsx src/w0/templates-history-w0.spec.ts --testNamePattern='^(?!.*B[1345679]|.*B8\.[13]).*$' --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w4-evidence/b-independent-skins-final.json
```

实际14/14 selected PASS（Inbox7+History3+原B8.2四）、39名称过滤未选中不计绿、exit0。最终B27文件manifest `b-final-skins-freeze-hashes.json` SHA256 `df29dbfd00c6f5fa72b8a2e3df99609cd4b0f6c4d82438e33a166291f8e9fbcf` 全部逐文件匹配；`b-independent-skins-verification.json`记录源码/日志/report hash。结合前79实际复验，当前B71原用例+2新增作者边界用例、原历史四映射、两独立边界负控均有对应来源，不把14与79简单相加当唯一case总数。该审查范围内没有待修复阻塞；真实浏览器下载、native SSE与全资源仍属于组长独立门禁。

最终A角色权限呈现必要修正（组长明确批准）：复用已有 `nativeToolLabel`，权限action保原 allow/deny/ask 中文；原permissions只在展开技术标识显示。既有角色RTL现在实查“编辑文件 · src/** · 允许”与原edit规则，作者v3仍41/41 PASS、0 fail/skip、exit0，raw `task-author-final-v3.{log,json}`。最后Task SSE队列fixture仅把原无效ACQUIRED改真实HELD并补required reconcileAvailable:false完整DTO；断言/生产行为无改，不把此前log冒称最后test字节已跑。最终14文件manifest `task-freeze-hashes-final.json` SHA256 `79b28eee956eda60ea4c1bdd87baf0272217dd8275f4f0b405706fcfef4e0046`；最终独立/组长全unit覆盖最后字节由其单独记账。

按组长追加授权，实际 `node_modules/.bin/vue-tsc -p tsconfig.app.json --noEmit --incremental false` exit0，raw `task-types-final-app.log`（0字节错误输出）；不写共享tsbuildinfo、不改tsconfig。未将references根config的空files `--noEmit`快退出当检查证据。A源码现冻结，文档交回组长；未提交。

最后type-only DTO补齐后的Task16再次作者聚焦实际16/16 PASS、0fail/skip、exit0，raw `task-typed-final.{log,json}`，不会用类型检查替代行为证据。C非作者最终权限RTL9/9 PASS、0skip、exit0，`task-ui-independent-c-permissions-final.json` SHA256 `35c01e95f58b708083d0e98d24d587f32d8d96221d258f2c768e07b1dff1b05e`；C已逐14file核对manifest-final完全吻合。其原独立41候选及4原样probe均保留原来源，不声称对后补DTO字节重新全量；组长最终全unit/build/browser另外验收。
