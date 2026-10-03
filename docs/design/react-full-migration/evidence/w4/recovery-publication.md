# W4 Recovery 与 Publication

本模块作者为原 C 组员 `/root/react_ppt_canvas`。既有团队启动记录指定 `gpt-6.1-sol / xhigh`；本工具不能实时读取平台模型字段。工作区 `feat/react-full-migration`，基线 `73d851b83c6d392d9f61cbbb93dbd180906d5175`。只修改 `pages/w4/{recovery,publication}` 和本报告；未修改 API、Java、Pinia、画布、依赖或共享契约，未实际推送/发布/调用模型。

## 生产边界与出口

- `/tasks/:id/recovery` 导出 `RecoveryStudioPage`、`createRecoveryStudioController`。三种模式只调用现有 `createTaskRecovery(id,mode)`；不会自动开始派生任务。TaskDetail 的 `REWORK_ALL_STAGES → startTask(child)` 两段流程由 A 父 owner 独占。
- TaskDetail child 导出 `TaskPublicationActions`、`createTaskPublicationOwner`。使用 `TaskPanelProps`，向父注册 `canLeave/retire`；新命令必须同时满足本人未决保护、真实任务资格及父 sibling gate。父刷新是只读端口，所有出版命令只有此 child 发出。
- 业务同步方法名为 `applyLocal`，保留基础 `SnapshotController.apply(token,state)`，不覆盖公共基础 API。
- `publication/fixtures.ts` 是无 Vitest 的完整模拟 DTO，可供组长生产浏览器夹具复用；`test-support.ts` 仅供单测，不进入生产代码依赖。

## 原功能 → React 映射

| 原入口/源位置 | 新入口/协议 | 对应真实测试 |
| --- | --- | --- |
| `RecoveryStudioView.vue:22–44` 父 Task、失败上下文、失败/活动/未成功/末阶段选择 | `recovery/controller.ts` 的 `load/recoveryStage`；真实标题、错误中文及父任务链接 | controller 阶段顺序；RTL failed context/StrictMode |
| 同文件 `:47–65` 三种恢复模式、创建草稿、lineage | `create/changeMode/openTask`，原 `{mode}` POST、冻结指纹与读写许可；所有模式零 start | controller 三模式；RTL radio/create/open child |
| `RecoveryStudioView.spec.ts:53–63` direct workspace 409 | 只将已核实写前抛出的明确 code 作为 rejected；未知 409 保 UNKNOWN | controller 409 正负控；RTL 未创建/中文/无 raw code |
| `TaskPublicationActions.vue:42–53,158–221` 资格、四位工单、120 字说明、规范化预览、生成失败 fallback、确认提交 | `publication/controller.ts` 的 `openCommit/changeTicket/changeSubject/submitCommit`；原 `commitMessage` 及单行规范化 | controller multiline 三项、validation/fallback/local publish；RTL 默认 Stay/一次确认 |
| 同文件 `:223–246` 已提交后继续推送/确认本地 | `submitCommit(true)` 保原分支/commit；不生成新 key、不重做 commit | controller COMMITTED resume/final MERGED |
| 同文件 `:247–258,426+` MR 目标分支、标题、描述、创建/重开/查看、popup/剪贴板 fallback | `openMerge/changeMerge/createMerge` 原 POST；之后实际 GET creation metadata；HTTP(S) 无凭据 URL，noopener；原表单与已准备链接保留 | controller MR exact/read-fail/keyless BLOCK；RTL COMPLETED/popup unavailable |
| 同文件 `:108–156` 进入/窗口聚焦自动核对、30 秒冷却 | 进入/聚焦只 GET；原 POST reconcile 保留为明确“检查合并状态”按钮。此变化是本波禁止 effect 隐写的必要安全变体 | controller 实际 focus/cooldown、零 POST；RTL StrictMode listener 身份与卸载 |
| 同文件 `:259–350` 文件列表、源/任务/手动方案、CAS、语言、共同祖先、变化/冲突块、前后跳转 | 原 session/file GET 与 resolution PUT；纯 `mergeView` 不变。源/任务/base 使用 W3 React/Lezer 只读投影，手动为真实受控 textarea，native selection/caret/scrollToLine | controller SOURCE/TASK/MANUAL、dirty file switch、late selection、marker 正负控；RTL Java 三栏/block adoption/save |
| 同文件 `:351–381` 单文件 AI、明确外发提示、尚未采用、载入/保存分离 | `suggest/loadSuggestion/saveResolution`；POST 只有用户确认后；建议不自动选择、载入不自动保存 | controller suggestion/unknown selected-body 负控；RTL 确认、pending lock、独立 load |
| 同文件 `:382–425` stale refresh、confirmed apply、rollback/backup/verifier output、binary/large | 原 create-refresh/apply + session/file CAS；dirty 须明确放弃；未解决/标记/rollback failed 不应用 | controller stale/rollback/unknown refresh/binary、apply exact CAS；RTL 验证器详情/backup |

没有将 Vue fixture 结果当作新 React 测试结果。既有 `CodeMergeEditor.vue` 未被包壳调用；没有新增编辑器库。语法预览和所有原块采纳/保存动作仍可达；jsdom 不证明真实浏览器 Tab、caret 或像素。

## 回执、草稿和作用域

- 所有新写只由用户动作开始；StrictMode 与皮肤重渲染只管理 view lease。Publication 只有实例拥有的 window focus GET listener，精确 remove；无自有 polling interval、RAF、observer 或 document listener。
- 普通 dirty → 明确确认放弃。SENDING/UNKNOWN/ACCEPTED_READBACK → BLOCK；实际 disabled 控件不能用强制 emit 绕过。panel 关闭不清未决 owner/原输入；持续 `CommandNotice` 可恢复或明确缺少安全入口。
- 成功写入后 GET/父刷新失败停在 ACCEPTED_READBACK，保留原 commit/form/manual draft、原版本与 receipt。恢复只 GET，零追加 POST/PUT。
- Keyless publish 只通过原 branch/message/commit identity 核对；resolution 只通过原 path/hashes/version/body 核对。AI GET 还必须证明 suggestion 与原建议字节不同且 resolution/mergedContent 未变；旧建议被另一同值保存保留、仅 version+1 不能证明本次 AI 已接受。相同字节的新建议在丢失回执后仍不能核实，保持 UNKNOWN，不盲重发。refresh GET 不把原 session 原版本当作新预检接受。
- MR draft 和 commit-message suggestion 没有本次结果查询合同，未知结果 BLOCK，不凭空幂等重发。Recovery lineage 列表也不能证明某一次 keyless create 的因果身份，哪怕只新增一行；显式 GET 可显示 lineage，但原操作保持 UNKNOWN。
- Recovery 已知且验证的原 child receipt 可签发准确 destination handoff；只覆盖此已接受 mode，错误 target、无 permit、导航失败仍保原身份/阻断。不会自动导航、重写或更换 key。
- 409 不是一般性安全证明。Recovery 的特例仅 `RecoveryService.java:51–68,115–123,162–180` 中写前明确拒绝码；其余 generic 409/404 按共享保守合同。
- 每个异步读后复核 scope/ticket/lease；文件切换拒迟到，forced retirement 后不发下一段 GET、不向新 task 投影。普通 retire 拒 pending/unknown/dirty 时仍保 owner 活跃。相同 Task DTO 的 `updateTask` 为 no-op，避免父 DTO clone 的无意义回流。
- 发布确认捕获原 scope、draftRevision、Task 资格/版本、publication 身份及冲突 session/file CAS；policy 与确认执行同步再核。实际窗口 focus GET 改变原分支后旧确认阻断，保留草稿，用户取消后重新确认；没有强制修改 modal 后的 disabled 控件。
- 本模块没有 File 输入或持久化恢复协议，不承诺跨刷新恢复 mode、正文或命令；parent/root session 与恢复入口边界由真实 Task owner 持有。

## 作者聚焦验证

命令（工作目录 `frontend`）：

```sh
npx vitest run src/pages/w4/publication/controller.spec.ts src/pages/w4/recovery/controller.spec.ts src/pages/w4/publication/TaskPublicationActions.spec.tsx src/pages/w4/recovery/RecoveryStudioPage.spec.tsx --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w4-evidence/recovery-publication-author-final-v6.json
```

最终作者 **62 PASS / 0 FAIL / 0 skip，exit 0**。JSON SHA256 `d7489e0c7fcd1552bd632cd7175ec34d19ce99c3e4c6508f903a02eb7ad45ab7`；15 源/测试文件 manifest SHA256 `1c8d339d846de923f0491127feb170d3058639e27daa9c5bb8733d787ca2a005`（外部 `recovery-publication-source-hashes-v6.json`）。定义共 62 项：Publication controller 34、真实 React RTL 12；Recovery controller 11、真实 React RTL 5。原 61 项全部保留，增加 1 项真实窗口 focus → GET 更新发布分支 → 旧确认阻断 → 重新确认一次写入。无 Vue child mock。所有 transport 均 mock；本地无真实 Git、模型、发布副作用。

保留实际中间输出：

| 输出 | 实际结果 | 归因/修正边界 |
| --- | --- | --- |
| `publication-first` | 28 PASS / 2 FAIL | ApiError 构造参数夹具错误、基础 retire 返回 LeaveDecision 却按 boolean 断言；保原业务期望修夹具 |
| `controllers-second` | 36 PASS / 4 FAIL | 上述旧字节继续跑，另两 Recovery boolean 夹具；中间不是最终 |
| `recovery-publication-first-complete` | 52 PASS / 3 FAIL | UiClose 真实 target name、AI 明确确认步骤缺失、重渲染夹具改变父 DOM 层次导致 remount |
| `second-complete` | 54 PASS / 1 FAIL | scope test 同上层次夹具，修为同层结构后继续原 pending 身份断言 |
| `third-complete` | 54 PASS / 1 FAIL | Ant 隐藏 panel 保留 DOM，错误使用包括 hidden 的 label query；改为 accessible role + 新 owner 空正文双断言 |
| `author-candidate` | 55 PASS / 0 FAIL | 初始聚焦；未包含随后 scope/session/eligibility 增强 |
| `author-final` | 59 PASS / 0 FAIL | 补 dirty reopen/loading gate、旧 session 不能证明新 refresh、foreign receipt、资格变化保护 |
| `author-final-v2/v3` | 各 61 PASS / 0 FAIL | 增 409 精确正负控与真实 UI 原断言；v3 同 case 加准确 accepted child/dirty handoff |
| `author-final-v4` | 61 PASS / 0 FAIL | A 静态指出父子 DTO 回流风险后，C 最小相同 DTO no-op＋既有 case snapshot identity 断言；未宣称此前真实生产已复现环路 |
| `publication-ai-unknown-before/after` | 有效负控 1 FAIL → 1 PASS，另 33 仅名称过滤 | B 静态候选经真实 controller 复现：旧 suggestion＋相同 MANUAL 正文保存只提升版本会错误 SETTLED；最小增加实际新增/变化的建议证明，原 hash/CAS/body 条件保留 |
| `author-final-v5` | 61 PASS / 0 FAIL | 上述 AI 修复后完整既有 61；仍是确认加固前候选 |
| `publication-confirmation-before/after` | 有效真实 RTL 1 FAIL → 1 PASS，另 11 仅名称过滤 | 合法 window focus GET 改变分支后旧 Modal 按钮原仍 enabled；加 scope/draft/pub/session/file 身份与同步复核，后续显式新确认只写一次 |
| `author-final-v6` | 62 PASS / 0 FAIL | AI 和旧确认合同闭合，Recovery 复用公共 SkinControl；15 文件冻结，无底层画布或共享生产改动 |

早期 `type-first.log` 所报业务 `apply` 名称覆盖基础 API 和 receipt 泛型推断问题已经修正，未降低 TS 配置。build/type/full unit 与实际生产浏览器由组长统一运行，当前本报告不声称其已通过。

## 组长真实生产浏览器验收接口与缺口

建议本波既有矩阵覆盖实际 `/tasks/:id/recovery` 与 TaskDetail 中的 publication，而不是 W1 demo：三皮肤桌面；reload/back/forward；radio/create/lineage；commit four-digit/confirmation/normal/unknown/accepted read-fail；MR link/form；conflict SOURCE/MANUAL/AI/rollback/stale；真实 textarea keyboard/focus；重复真实 SPA 退出的首次 resources 零清理；目的 owner 与 app SSE 分开。C 没有启动 browser/server/build，没有把 DOM listener 首样当 heap/GC 证明。浏览器、完整门禁与非作者复验结果待组长记录，本作者62项不能替代这些证据。

## C 非作者审查 A 与 Root

只读审查并实际聚焦运行，未修改 A/Root 的生产或作者测试。所有独立 probe transport 为 mock，不调用实际后台/模型。

| 发现及分类 | 原证据 | 修正与最终核对 |
| --- | --- | --- |
| P2 Task refresh 的旧 overview 退作用域后发 audit GET（迁移新问题） | `task-independent-before.json` 实际 audit 调用 1→2；不是迟到 DTO 仅被丢弃的空断言 | A `task/taskController.ts:99–105` 捕获原 refresh ticket，在 await 后复核 read lease；原样 probe 通过 |
| P2 executionEvidence 退作用域后发第二 failure GET（迁移新问题） | 同一 before 实际 `evidenceFailures` 1 次 | A `task/evidenceController.ts:27–33,65–69` 将 current 显式传入 load，首 await 后不再发后续请求；原样 probe 通过 |
| P2 SSE overview 已开始，last detach 后继续 Queue GET（迁移新问题） | `task-sse-independent-before-final.json` 实际事件/180ms timer/旧 QUEUED state，GET 1→2；此前两次 probe 错误 named API import 是夹具错误，不当业务证据 | A `task/taskController.ts:90–95` queue 开始即核原 lease；原样 4 项 probe 最终全部通过 |
| exact child handoff 被父 gate 丢 request（静态发现，C before 未复现红） | A 在 C before 运行前已传同 request，故 before 第 3 项实际 PASS；不能声称 C 获此原红 | A `task/taskController.ts:32–34`、Root `shared/types.ts:7–10` 同 NavigationRequest 传给所有 children；既有真实 permit/wrong target/sibling pending/unknown 作者负控保留，C 原样 probe PASS |
| 公开 dispose 首次失败、重复调用误返回 true（Root 静态 API 边界，未另制造业务红） | 首版 `disposeIfSafe` 的 inactive 分支直接 true | Root 保留 cleanupHealthy sticky 失败；原失败 case 增第二次 false 且 retired 仍一次；C 复验结果见下 |

A 完整 4 spec 的候选已独立 **41/41 PASS**，exit0、0skip：`task-independent-c-final.json` SHA256 `4087da26834dbb34df5f8b8ea34e2aa94a66714d9326b64c0522b71a57a30c0a`。同一原样 probe 的最终 **4/4 PASS**：`task-independent-final.json` SHA256 `655067457f625b144841833515fe3ed7a55b89b8ccd6a9d4f15bb652666191e1`。临时工程 probe 已移除，只保外部原文本（SHA256 `0a896548ede1c2c5cdd32a8272f13e5842367ab963ab0a464334263370f6d34d`），不混入作者 62 或最终全量。A 随后权限中文投影/真实 HELD 枚举的最小更正使源变更，最后受影响 RTL 已独立 **9/9 PASS**、exit0、0skip：`task-ui-independent-c-permissions-final.json` SHA256 `35c01e95f58b708083d0e98d24d587f32d8d96221d258f2c768e07b1dff1b05e`。最终 14 文件 manifest `task-freeze-hashes-final.json` SHA256 `79b28eee956eda60ea4c1bdd87baf0272217dd8275f4f0b405706fcfef4e0046` 逐文件与当前源一致；不把旧 41 记录冒成后改字节完整覆盖。

Root shared core5＋真实 VueRouter/React bridge20＋W4 route13＋纯 boundary2＋订阅 cleanup1，独立 **41/41 PASS**，exit0、0skip：`root-independent-c-final.json` SHA256 `55bb96540864bbd2ad18c66e307a2222d3362b6272d77c5d842af35032874739`。这次包括首版公开 dispose 5 个合同；sticky 返回修复后的真实 bridge 已独立 **20/20 PASS**、exit0、0skip：`root-bridge-independent-c-sticky-final.json` SHA256 `2453684268d9a79ec93ff5eb7f09c7b2ff4314ea3ade82ea40f6e2b4930591e6`。原失败 case 强断言第二次 dispose 仍 false、retired 保持一次；不把此旧 41 称后改源码完整终态。

源码核对：Task SSE 只有一个 owner；事件仅触发权威 GET，不合成生命周期。Session question 保留原 task/session/question/answers；缺失问题不证明未知回答接受，已接受恢复只读；真实 fieldset 继承禁用用 `matches(":disabled")`，不强行触发 disabled emit。Session ResizeObserver 的实例 disconnect 与 late callback guard 明确。Report/bundle 下载只从原 owner 发起，Blob URL 同步 revoke，迟到下载不创建 URL。A RTL 将 B/C heavy siblings 单独 mock，只证明 A 页面自身合同，不冒真实多 child 集成与浏览器资源证明。

Root `shared/core.ts` 原 scope/ticket/view lease、`shared/parts.tsx` reads detach 与 command owner 保留、`w4TaskBoundary.ts` 空纯 TS compatibility projection 未新建 Pinia writer；`taskEventSubscription.ts:20–32` 先 invalidation、清两个 coalescing timers，再逐项 close/state 并聚合故障。公开 bridge disposal 只 ALLOW 才终止本 React root/retained owners；BLOCK 和 ordinary dirty 不丢身份，cleanup failure 可见。浏览器若通过 host 的 Vue 内部实例字段发现正式 `defineExpose.disposeIfSafe`，该发现手段属测试适配，不能称私有字段为受官方支持公共 DOM API。

整 Vue App 卸载产生原 Story/ElementPlus useLockscreen 的 200ms module/app timer，由 Root 单列未修边界。本 bridge strict0 不证明 whole App strict0，更不证明 heap/GC 或跨刷新持久化。

B 的非作者 COMMITTED 原 commitSha 确认路径也取得实际旧组件红：`c-confirm-reconstructed-before-v2` 合法 focus GET 换 SHA 后旧确认实际发 `publishTask(task,undefined)`；重建旧组件字节先与已记录 SHA256 `4fd2843a63b561a9751cfd3f277ab6600d14c57e9dafafdf9298715c0f09dd4b` 完全一致，只有测试复制体的三个相对 import 适配。C 自己的 branch 红/绿和 B 非作者 SHA 证据分开，未借私有 VM 或强制 disabled 控件构造不可达用户场景。B 最终非作者独立 **63/63 PASS**、exit0、0skip（C 原有 62＋B 独立 COMMITTED/SHA 1）：`independent-probes/c-final63-verified.json` SHA256 `8c9d0681278974cd9e74b7e9ba2b7a94596efdf77b834c3e41270bfdf2922673`；B 逐文件核对 C 15 文件与 v6 manifest 一致，运行前后无源变化。该 63 不作 C 作者 case 数。
