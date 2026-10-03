# W4：Task、Inbox、Recovery 生产 React 迁移

**本地有限范围验收完成，未发布。** 基于 W3 `73d851b83c6d392d9f61cbbb93dbd180906d5175`，分支 `feat/react-full-migration`。本波四个实际生产路由已接真实 React 页面和纯 TypeScript 业务所有者；Vue Router 仍是唯一 history owner。W5 未实施、未获本轮放行。没有 push、PR、merge、部署、真实模型调用或后端修改。

直接查看：[三皮肤桌面实际生产截图 27 张](screenshots/README.md)、[最终结果和命令](verification.json)、[44 次 W4 严格退出首样及先前波次回归](resources.json)、[四项 W0 原红与剩余 41 红](w0-current.json)、[源码/测试清单](source-manifest.json)。截图使用模拟传输 DTO，浏览器渲染的是正式生产构建，不是原型或 W1 demo。

## 准确范围与唯一所有权

入口权威是 [router/index.ts](../../../../../frontend/src/router/index.ts)、[w4Routes.ts](../../../../../frontend/src/migration/w4Routes.ts) 和 [W2RouteBridge.vue](../../../../../frontend/src/migration/W2RouteBridge.vue)。没有因名称新增 Inbox/Recovery API；本工程这两者确实有独立路由，发布、审批、脏工作区等则是 Task 页内组件。

| 实际路径/入口 | React 页面、唯一业务 owner | 保留的入口与行为 |
| --- | --- | --- |
| `/tasks/:id` | `TaskDetailPage`；Task、Session、Evidence 三个 owner，父 guard 汇总 Session、五类动作和 Publication，并复用两类 W3 模板恢复 owner | Start/Pause/Resume/Cancel、继续一轮、双评审重试、新分支重做、队列读取/显式协调；当前错误、租约/停止证据、阶段/模板图、会话问题与角色、双 Judge、尝试、结果/报告/审计/执行证据/日志/DIFF/搜索与下载 |
| 同一 Task 页内 | Judge、Decision、DirtyWorkspace、GitDiffScope、Rolling 五个 owner；Publication 一个 owner | 人工审批；成功/失败后继续、派生、只读审计、接受、取消；保留现场；范围预览/批准；滚动工作包讨论/设计/规划/纠偏；提交说明/本地交付/推送续接/MR/本地冲突修复 |
| `/tasks/:id/design` | `TaskDesignHistoryPage`；history owner | 只读冻结快照、原附件与安全预览、确认需求、工作包设计历史、设计对话、冻结配置。只读原 Task 保存内容，不跟随最新 Designer 会话 |
| `/inbox` | `InboxPage`；inbox owner | 当前权限/问题/审批/选择消费者；精确 interaction/task/designer/session/id/version/action；拒绝/仅本次/本会话/回答与跳转；退列仍保原草稿和未决操作 |
| `/tasks/:id/recovery` | `RecoveryStudioPage`；recovery owner | FAILED/CANCELLED 原 Task、失败阶段/错误、FROM_FAILED_STAGE / ALL_STAGES / VERIFY_ONLY 三模式、显式创建、lineage、已知原 child 打开；不自动 Start |

数量口径：[旧入口递归盘点](baseline-inventory.json)有 35 个旧组件文件、73 个不同直接 API 方法引用；[新模块索引](module-inventory.json)有 **32 个生产 TS/TSX 文件、20 个导出的 React 组件声明、12 个本波业务 owner、76 个不同 API 方法引用**（模板批次/诊断恢复另复用 W3 原 owner，不计为新重写）。索引排除 spec、fixtures、test-support 和 W0 契约测试入口。API 引用包含 GET、下载 URL 与写入，**76 不是 76 条写命令**；54 个新增中央语义 action 也包含读取/展开/本地操作。旧 SFC/测试仍保留历史与未迁模块使用，W6/W7 才执行全量 Vue 退场。

三个实际成员一直复用，没有 spawn 或换人。当前协作工具只能核对名字/状态，不能实时读模型字段；`gpt-6.1-sol / xhigh` 来自原明确创建回执，组长 Sol 极高来自项目经理 UI 切换记录。

| 原任务 ID | 独占写入范围 | 作者聚焦结果 | 非作者复核 |
| --- | --- | --- | --- |
| `/root/react_flow_workflow`（A） | `pages/w4/task/**`、[Task 报告](task.md) | 41/41；最后 DTO 类型补齐另验 Task 16/16、严格 app 类型检查通过 | C 审 Task；A 审 B 的 Inbox/history/五类动作 |
| `/root/react_legacy_canvas`（B） | `pages/w4/inbox/**`、`history/**`、`actions/**`、[动作报告](inbox-history-actions.md) | 73/73 | A 审 B；B 审 C 的 Recovery/Publication |
| `/root/react_ppt_canvas`（C） | `pages/w4/recovery/**`、`publication/**`、[恢复发布报告](recovery-publication.md) | 62/62 | B 审 C；C 审 A、共享 owner/bridge/资源与最终证据 |
| 组长 | 四路由接入、纯 TS 共享边界、SSE 清理、中央语义生成、公共 host lifecycle、必要 Modal 修复、集成测试/截图/本文 | 26 个新增共享合同，另保原 bridge 15 项；独立 probes 3 项 | C 独立 bridge 20/20；B/C 核对 Modal 公开 API；三员非作者回放分别记录 |

作者测试不是独立评审；多次复跑同一用例不累加为唯一测试数。C 对组长最终 browser 方法与输出又做只读复核：29/29、44 首样全部严格0/外部 sentinel 保留/零后续输入，27 PNG 原字节和末两连续 hash 精确一致；审核 SHA256 `428d30962d8db221c4ea820355a0727d2c119d2d679f35baa5a4de5ddb81b283`。C 不把审自己的恢复/发布图当非作者模块评审。

## REST、回执与导航合同

- Task REST overview 是当前状态权威，audit 不复活已过期的当前错误。原 [`taskEventSubscription`](../../../../../frontend/src/stores/taskEventSubscription.ts) 仍是唯一 Task SSE owner；事件只合并失效读取/通知，不从 `task.status` 合成 Task 生命周期。原生 EventSource 自动重连保留实际 `Last-Event-ID`，不手工建第二个流。Native 测试中事件提示 CANCELLED、REST RUNNING，界面仍运行中；游标由空到 `17`，退出只关闭原 Task stream，保留全局 Story stream。
- [纯 W4 边界](../../../../../frontend/src/migration/w4TaskBoundary.ts) 不导入 Pinia，不向旧 taskStore 委托写入或订阅。Task 页内每个 writer 自持冻结操作，向同一父 guard 注册；父接口 `canStartWrite(caller)` 仅豁免 caller 自己普通 dirty，不能豁免自己的 BLOCK 或任何兄弟 owner 的未决保护。
- [共享 owner](../../../../../frontend/src/pages/w4/shared/core.ts) 复用 W1 receipt/resource/navigation/snapshot 合同。写入仅由显式用户动作，主题、render、effect 重放、loader 和轮询不写。视图 lease 只管理读取；retained command owner 保原 id/body/version/CAS/receipt。关闭 context 只收起视图，不能等价于停止或撤销服务器任务。
- pending、UNKNOWN、ACCEPTED_READBACK 阻止危险离开/根销毁；普通 dirty 需要明确确认。确认执行时再次核对 scope/draftRevision/Task 资格/原 CAS、父兄弟保护；等待确认期间新 pending 或 focus GET 的新 publication 会阻断旧确认。已接受操作只用合法 GET/readback，不再次 mutation。未知 keyless create 不以“新增一个相似 lineage”证明接受，不自动重写。
- 原 Task Start/Pause/Resume/Cancel/Retry、Session 回答、Recovery 创建等接口没有本次 by-request GET receipt/完整持久身份合同。保原 Task/session/question/mode/body，并只在真实读证明充分时解除 UNKNOWN；未证明继续 BLOCK。新请求 key 不被虚构。没有跨刷新完整恢复承诺；浏览器 UNKNOWN reload/back 两次原生 beforeunload 被拒绝后，原 owner、答案和唯一 POST 仍保留。
- STOPPING 来自服务端权威状态，仅显示正在核对旧会话与进程；断开 SSE、关闭面板或卸载 root 不会标为 CANCELLED。页面保留原 `stopProof` 投影，不将客户端 close 当停止证明。
- `PENDING_START` 才显示显式 Start，READY 不再次 Start；TaskDetail 重做与 Recovery 三模式是两个原合同：前者已知 child 后显式 Start/handoff，后者只创建草稿、打开 child，不自动开始。
- Publication 自动进入/focus 核对改为真实 GET，原 POST reconcile 留作显式按钮；AI 建议只由显式确认请求，加载建议不自动采用/保存；旧缓存建议加无关版本增长不能证明本次 AI 接受。commit、MR、resolution、session/file hash/version 原协议保留。

## 旧断言到真实 React 测试

| 原测试/组件合同 | 新真实 React / TS 测试落点 |
| --- | --- |
| TaskDetail 生命周期、当前错误、队列、重做与会话 | `task/taskController.spec.ts`、`sessionController.spec.ts`、`pages.spec.tsx`；生产 browser Start/stop/问题 UNKNOWN/native refresh/back/SSE |
| SessionMonitorPanel / PendingQuestionCard / TokenUsageWindow / OpenCodeTodoProgress | 同 Session/RTL：原 task/session/question/answers、问题退列保留、5 行折叠/追随、角色按需、用量单调/Todo 有界 |
| TemplateTaskProgressPanel / TemplateReportsPanel / SnapshotReviewPartialReport | `task/evidenceController.spec.ts`、`pages.spec.tsx`：报告 metadata/body 分读、bundle 链接与下载、程序验证和 AI 审批分离；原 React 阶段/模板画布复用 |
| TaskAuditEvidencePanel / ExecutionEvidencePanel / LayeredErrorPanel | 同 Evidence/RTL：按需正文、搜索分页/offset、历史日志/DIFF、安全代码预览、迟到读取退休、URL revoke |
| InboxView 与五类 Task 动作旧 Vue specs | `inbox/{controller,pages}.spec.*`、`actions/{controllers,pages}.spec.*`；精确 CAS、dirty/pending/UNKNOWN/资格改变/已接受只读恢复；实际 Inbox browser 原 ONCE/version 4 |
| TaskDesignHistoryView 与冻结附件/对话 | `history/{controller,pages}.spec.*` 和原 `w0/templates-history-w0.spec.ts` 四个 B8.2：Task 隔离缓存、退出拒迟到正文、原冻结数据、同任务复用身份 |
| RecoveryStudioView | `recovery/{controller,RecoveryStudioPage}.spec.*`：三模式、精确 409 正负控、原 child/导航、lineage 非因果证明；生产两种恢复测试 |
| TaskPublicationActions / CodeMergeEditor / 本地 mergeView | `publication/{controller,TaskPublicationActions}.spec.*` 与非作者 `actions/independent-c.spec.tsx`：ticket/message/confirm、CAS、AI/load/save、apply/rollback/MR、focus 旧确认；生产三皮肤 commit panel/dirty/焦点 |
| 共享 bridge/资源/命令 | `migration/w2Bridge.spec.tsx`、`w4Routes.spec.ts`、`w4TaskBoundary.spec.ts`、`shared/core.spec.ts`、`taskEventSubscriptionCleanup.spec.ts` |

Task 作者 RTL 为降低聚焦成本隔离了 B/C 重组件，仅证明 A 合同；C 发布 RTL没有旧 Vue child mock。四路由真实生产浏览器加载实际 B/C siblings，作为集成证据。旧 Vue 断言不删除，未迁模块继续运行。

W0 原 **四项 B8.2 title 和业务 assert 保留**，仅将执行目标由旧 Vue retarget 到实际 Vue Router → bridge → React history 契约入口；基线 4 FAIL → 本波 4 PASS。其余 39 名称过滤项不算绿。全 W0 最终 **111 项：70 PASS / 41 FAIL / 0 skip**；剩余 Designer 18、Workflow/Requirement 23 均归 W5，完整名称见 [w0-current.json](w0-current.json)。原 11 历史 E2E 的来源/根因与 W0 映射仍在 [W0 报告](../w0/README.md)，没有把未完整重跑旧全站 E2E 当全部关闭；Automations 的合法 read/export 消费者由已验收 W3 保留，本波不恢复退役 write API。

## 独立审查发现与修复

| 非作者/证据分类 | 发现 | 最小修复及复验 |
| --- | --- | --- |
| C 审 A，真实前红 | overview → audit、executionEvidence → failures、SSE overview → queue：首 GET 回来时 lease 已退休却继续第二 GET | 每个 await 后/下一入口复核同 lease/ticket；原样独立 4 probes 全通过（包括 handoff 正控），父子 handoff 同 request 透传 |
| A 审 B，真实 2 红 | Rolling workbench → detail、Dirty UNKNOWN workspace → Task：退休后仍发第二 GET，虽没投影仍违反资源停止 | 原 ticket/context/active 双复核；原样两项独立 probes 转绿，作者 71 原项保留并补 2 项，共 73 |
| B 审 C，真实红 | 旧 AI suggestion + 无关同值保存版本上涨，被误当 UNKNOWN 已接受 | 必须新增/变化 suggestion，原 hash/body/CAS 继续精确核；原红→绿，不盲重发相同建议 |
| B/C 审 C，真实 focus GET 前红 | COMMITTED/SHA 或分支在确认等待时改变，旧确认仍发 publish | 捕获原 scope/draft/pub/session/file 身份；policy 与 handler 再核。B 回放旧组件原哈希，3 条测试 import 适配单列；真实原红 1 → 当前 0 POST，C62+B1 独立 63/63 |
| C 审组长，真实 bridge 红 | 成功公开 dispose 后正常 Vue route 离开被 terminal coordinator 阻断 | 仅 clean terminal 状态允许后续正常离开；原 bridge 20 中 19/1 → 20/0；failure sticky 仍 false、retire 只一次 |
| 三员静态确定、未冒称实跑红 | caller accepted permit 未透传；Dirty 资格退失隐藏旧 error；相同 DTO 回流风险；重复 dispose 失败变 true | 同 request 到兄弟 guard、持续 error、相同 DTO no-op、sticky cleanupHealthy；正负控保留，报告区分静态与红证据 |
| 组长真实 browser 红，B/C 非作者核对 | reduced-motion 下 dialog role 已 hidden，`.ant-modal-wrap` 留存并截获背景真实点击；旧测试漏验遮罩 | Ant 6.6.5 公开 `transitionName=""` / `maskTransitionName=""`，不删除 DOM/force click/改库。三个皮肤的 Publication 和 spdb Task cancel 明确 assert visible wrap 0、真实点击/回焦/原草稿/POST1；W1 41/41、18 清理重新通过 |

Modal 两公开属性**关闭所有 UiConfirmDialog 的进出动画，包括普通 motion**；不是只关闭 reduce。已有面板/hover 反馈仍保留，未修改稳定 theme provider，也不因换 motion 重建 owner。这是已记录代价，可在后续统一组件波次恢复可靠动效，不是本波阻断。

## 最终验证与资源门槛

| 最终执行 | 通过 | 失败/未选中 | 证据说明 |
| --- | ---: | --- | --- |
| W4 相关 unit | 220 | 0 / 0 skip | **205 唯一新增** + 原 bridge 15；A41+B73+C62、永久独立3、组长新增26，不将重复审查 run 累加 |
| W1 foundation unit | 69 | 0 | 公共 Modal 变更后重跑 |
| 全量 unit（最终串行） | 2016 | **41 W5** / 0 skip | 2057 总项，250 个物理文件；没有其它失败，全量命令 exit1，不能称全绿 |
| W0 全量 | 70 | **41 W5** / 0 skip | 是上述全量子集，不再加到总数；本波原4红转绿 |
| W4 production Chromium | 29 | 0 / 0 skip / 0 flaky | 4 路由×3皮肤直达/reload/back/forward；SSE、问题/回执/审批、root disposal、活动 pan、dirty/主题/焦点；27 PNG |
| W2 production Chromium 回归 | 49 | 0 / 0 skip / 0 flaky | 原 42 路由首样 + 9 活动平移轮次；原 JSON attachments 保留首样 |
| W3 production Chromium 回归 | 31 | 0 / 0 skip / 0 flaky | 原 69 次退出首样；PPT/Knowledge/templates/合法历史消费者保持 |
| W1 React/Ant Chromium 回归 | 41 | 0 | 执行完整；18 次严格清理。旧 W1 PNG 原字节保留，重跑截图存仓库外 |
| `npm run typecheck` / `npm run build` | exit0 | 已知大 chunk/PURE 注释 warning | 生产 dist 是最终已冻结运行源码；未降低类型/构建要求 |
| tooling / accounting | Node33 + Python6/4；Node13 | 0 | 默认沙盒 child spawn 错误首轮单列；正常授权本地执行后通过，不改安全设置 |
| `build:foundation-preview` | exit0 | — | 只用于 W1 回归，不代替正式 production build |

原始日志在本任务仓库外，仓库内 [verification.json](verification.json) 只保命令、计数与 SHA256，不加入日志/凭据/私人数据。package 没有 lint 脚本，不声称 lint 通过。本波没有新增依赖或锁文件变化，也没有重做/冒称本波干净 npm ci；干净安装与最终零 Vue 是 W7 门槛。

中间失败不掩盖：第一次并行全量 2011 PASS / 46 FAIL，除 W5 41 外既有 Settings 5 失败；未改 Settings，原 23 项隔离全通过，最终同断言/默认 timeout、限制一个 worker 全量为 2016/41。怀疑并发负载，但首轮具体根因没有被证明。强化原生 beforeunload 后一轮 28/29 的失败是浏览器拒绝导航后没有 commit，runner 等待 60s；仅限制 API 等待 3000ms 并严格保真实两次 beforeunload、原 owner token/body/唯一 POST，最终完整 **29/29**。不把定向 1/1 与旧 29 累加称最终通过。

三皮肤各四 route 普通退出完整；root/native SSE/活动 pan 专项是 spdb 代表，并非三皮肤全故障矩阵。W4 严格门槛 **44 次 = 普通真实路由退出 36 + 正式公开 host 根卸载 4 + native SSE 退出 2 + 活动阶段图 pan 退出 2**。首样 listener、active observer targets、RAF、capture、timer 全为 exact `[]`；同 document、外部 sentinel 不被删除、首样前无后续自然 mouseup/pointerup/window blur。原 W2 observer/resource assert 与 W3 immediateExit 源字节未改；未通过忽略 observer 或放宽计数取得绿。

`ReactViewHost.reactViewLifecycle.disposeIfSafe()` 是本项目声明、只读的正式公开宿主接口，不是 Vue/React 私有字段或库 API；只 ALLOW 时释放该 root/owner，dirty/pending/unknown 返回 false 并保持原 owner。独立 bridge tests 确认同一公开对象、idempotence、failure 可见/sticky、清理仅一次及正常路由后移除自有 property。

测试 runner 在 app root 尚不存在时初始化自己的 hit-target listener；之后加载未改的真实生产模块，避免把测试工具全局监听误归给 root。没有豁免 root 活动资源、删除他人 listener 或伪造 mouseup。Native SSE 检查退出关闭同 stream，450ms 后无新增旧 scope overview；单测另证迟到返回零投影/不再启动第二 GET。

## 保留边界与 W5 门禁建议

1. **W5 41 项仍失败**，没有全站前端完成或零 Vue 声明。W4 同意有限验收后可由项目经理决定 W5；下一波按 frozen 原断言迁移 Requirement/Workflow/Designer，不先接管 history，不删除红测/skip/降低门槛。
2. 浏览器为真实 production React + 模拟 DTO/本地原生 SSE 传输。真实 Java/REST、OpenCode、付费模型、服务器断线语义/进程停止、文件解析、实际 Git commit/push/MR/备份回滚均未联调。高级动作的协议多数有精确 controller/RTL 正负控，29 browser 不是所有 action 的真实端到端 backend 覆盖。
3. 没有本波 File 输入新增；既有 owner 类型合同保留，未知请求内 File 不被热卸载。本波不证明 W5 上传或跨刷新完整持久恢复。keyless UNKNOWN 用户绕过 beforeunload 或关闭浏览器可能丢内存身份，现有后端无充分持久恢复合同，准确保留风险。
4. 整个旧 Vue App `app.unmount()` 有 Story/ElementPlus `useLockscreen` 的 200ms 模块级 delayed cleanup timer，未修、未称全 App strict0。W4 用正式自身 React 根与实际路由退出严格验证，不把模块定时器计入自己拥有的资源。卸载观察关系释放不等于所有 GC 对象已释放；ledger 自身持强引用，未做 heap/GC 证明。
5. 桌面 1440×1000、Chromium、mouse/keyboard/reduced-motion 已测；窄屏按用户取消范围保留旧断言而未新增验收。物理 touch/pen、其它浏览器、系统无障碍工具未测；沿用原已验收 pointer canvas，不重写其底层。
6. 已知主 chunk 体积警告、全部 confirmation 无动画及普通视觉微调列后续对应公共工程/设计集中波次，不为非阻断装饰扩展 W4。W4 新增 27 图及 W1/W3 历史图均保留独立来源。

本轮运行源码、测试、截图和报告冻结后形成独立本地 W4 wave commit；发布仍需要新的授权。本文件属于工程验收说明，不是新的生产开发放行。
