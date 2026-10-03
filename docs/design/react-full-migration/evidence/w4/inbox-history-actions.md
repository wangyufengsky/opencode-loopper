# W4 Inbox、冻结历史与任务动作

作者为既有 B 组员 `/root/react_legacy_canvas`；团队历史启动记录明确指定 `gpt-6.1-sol / xhigh`，当前工具不提供实时平台配置查询。工作区 `feat/react-full-migration`，W4 基线 `73d851b`。本报告及新增 `frontend/src/pages/w4/{inbox,history,actions}` 为作者范围；未修改旧 Vue 页面/组件、旧 spec/W0、API、后端、依赖、中央语义表、桥接或路由。未提交、发布或运行真实付费模型。

## 出口与唯一所有权

- `inbox/index.tsx` 导出真实 `InboxPage`（`/inbox`）；`createInboxController` 持有读取、1.5 秒串行 REST 协调、问题草稿与唯一 resolve writer。没有新增 SSE，也不调用 Pinia。
- `history/index.tsx` 导出真实 `TaskDesignHistoryPage`（`/tasks/:id/design`）；`createHistoryController` 只读本 Task 冻结记录和附件。缓存按 owner 隔离，绝不向最新 Designer 会话查正文。
- `actions/index.tsx` 导出 `TaskJudgeApprovalPanel`、`TaskDecisionPanel`、`DirtyWorkspaceDialog`、`GitDiffScopeApprovalDialog`、`RollingPackageWorkbench`，由 A 的真实 TaskDetail 父 owner 调用。五个 child 稳定注册同一父 guard 图；不是五个新增路由。
- 每个 child 的控制器保持基础 owner 的确切实例身份，`parent.canStartWrite(caller)` 只豁免本人普通 dirty，不豁免本人 UNKNOWN 或 sibling dirty/pending。所有 mutation 都要经过该 gate；读取和 skin/StrictMode 重播没有隐式写。
- `actions/eligibility.ts:4` 与组件内部 enabled 状态保持旧 TaskDetail 的资格门槛：Judge 为非模板报告及原评审记录/状态/可重评条件；Decision 为非模板且 AWAITING_DECISION；Scope 为 WAITING_INPUT；Dirty 为尚无分支和执行目录的 SOURCE_BRANCH_WORKSPACE_DIRTY；Rolling 为 ROLLING_PACKAGES。永久挂载并不代表允许调用每个端点。资格消失不销毁 pending/draft/error owner，原恢复仍通过明确动作进入。

所有生产控制器均为纯 TypeScript，无 Vue/Pinia 核心 delegate。W0 helper 为本阶段实际 Vue Router → 唯一桥接 → 真实 React 页验证，仍执行 Vue 测试基础设施；这不是最终 W6 的零 Vue 证明，最终需一并迁移/清理，不能豁免为历史文档。

## 原断言 → 新生产与真实测试

表中路径均相对 `frontend/src`。旧测试源保留，表提供逐行为替代证据，不以 React 标记代替业务断言。

| 旧行为与精确测试入口 | 新 owner/呈现 | 新验证位置 |
| --- | --- | --- |
| `views/InboxView.spec.ts:29` 服务端权限、乐观版本、hard deny | `inbox/controller.ts:21` 原 `resolveInteraction(id,{action,version,answers})`；选中后详情、hard-deny 常显 | `inbox/controller.spec.ts:10,13`；`inbox/pages.spec.tsx:17` |
| 同文件 `:45,58` 失败保已持久化列表；命令错误不被成功 GET 掩盖 | 独立 read/command error；UNKNOWN 保存原事项/endpoint/body/version，只有真实列表读证明才能解除 | controller `:11,12,15`；pages `:18,30` |
| 同文件 `:75,90,114` 初读迟到不重启 timer、慢读串行、提交后读取排队、重复 submit | view lease/channel token；同一 inFlight 的原 lease 已失效时新 lease 重新读取 | controller `:10,14,16` |
| 原 `PendingQuestionCard.vue` 单选/多选/custom/recommended 顺序 | `inbox/index.tsx` 实际 React 原问题表单；单选 custom 替换、多选 custom 追加；推荐只由明确按钮提交 | pages `:19,20,21`（StrictMode 无 mount POST、三 skin 同 textarea/草稿） |
| 列表暂时没有返回正在编辑/UNKNOWN 事项 | `retained` 保存本 owner 原 DTO；输入、权限详情、只读恢复及 dirty discard 仍可达；不把缺行当接受证明 | pages `:22,30`；没有强改 disabled DOM |
| `views/TaskDesignHistoryView.spec.ts:11` 持久化设计、最终回答、内部消息过滤、原 LoopSpec | `history/index.tsx:14` 六类选择披露；复用纯 `frozenDesignTimeline`、实际安全 RichDocument；原任务设置/路径/验收器、需求/modelCalls、工作包 compiler/handoff、回答/附件均保留 | `history/pages.spec.tsx:17`；六按钮具有实际可见中文类别而非仅 ARIA target |
| 冻结附件 metadata/原始链接/安全正文 | 本 Task 预览 GET；plain `<pre>`；完整 SHA-256 换行；image/PDF 原精确 URL 与 noopener/noreferrer；深链 modifier 保原生 | pages `:18,19`；controller `:12,13` |
| `components/TaskJudgeApprovalPanel.spec.ts:9,23` 人工认定确认、显示代际、取消、接受后重读 | `actions/judge.tsx:14` 原 Task/Cycle/batch CAS；保 AI 结论；明确确认，不自动认可 | actions controllers `:25–27`；pages `:18,19` |
| `TaskDecisionPanel.spec.ts:56,79,98,114` 六种原安全处置、成功继续补充要求、未知变更数量、专用取消 | `actions/decision.tsx:17` availableActions 权威；原 Task/Cycle/stage/supplement/mode；STOPPING 不冒称已停止 | controllers `:28–32`；pages `:20–22` |
| 同文件 `:137,165,186,202,213,226` 原版本/正文冻结、取消/退休后不写、迟到不导航、错误保持 | body 不可变捕获、scope + draftRevision + 当前服务端 CAS 二次复核；已接受 child 精确 handoff，不自动导航、不重写 | controllers `:28,30,33,34,51,52`；pages `:30`（三 skin 同一次 POST） |
| `DirtyWorkspaceDialog.spec.ts:31,64,88` 每文件 COMMIT/STASH/REMOVE、提交正文、列表失败仍可确认取消 | `actions/dirty.tsx:15,26` 原 snapshotId 与有序 resolutions；REMOVE 再确认；nonclosable 工作区弹窗；取消独立端点、源文件不变 | controllers `:35–38`；pages `:23,24` |
| `GitDiffScopeApprovalDialog.spec.ts:33,66` 原 old/new/hunk 行号、内容 hash、全文件决定、关闭后保卡 | `actions/scope.tsx:11–20` 原 requestId/taskVersion/path/patchSha256；plain 安全差异；关闭保留决定，不取消任务 | controllers `:39–41`；pages `:25,26` |
| `RollingPackageWorkbench.spec.ts:72,110,171,201` approve/start 分离、事实三层、checkpoint retry、最新能力与 resume | `actions/rollingController.ts:26` 用实际 WB capabilities；四类版本均来自当前 run；事实/接受合同/导航摘要不同职责 | controllers `:42–44`；pages `:27` |
| 同文件 `:142` 读取持久化 AI 建议，显式 impact confirm | 原 AI/manual/correction proposal API；已知 proposal id GET，owned 1s timer；生成不激活；确认另发原 proposalVersion | controllers `:45–49`；pages `:28,29` |
| 同文件 `:236` concurrent replan conflict 重新读取 | 409 只触发原 WB/父 GET reconciliation，保 UNKNOWN 原意图/禁重发，不用刷新制造安全接受 | controllers `:53` |
| 原 manual 后缀增删/移动/拆分/合并、来源 IDs/dependencies/requirementRefs、frozen correction | 本地 plan DTO 保存原字段，仅编辑未冻结后缀；显式 proposal → server impact → confirm | controllers `:46,47`；pages `:29` |
| 新整体集成门槛：永久挂全部五 child，普通 RUNNING 不读不显示不相关端点 | 内部 enabled gate，资格丢失仍显示原 UNKNOWN/error/草稿 | pages `:31,39`，真实同时挂五出口及合法 WAITING_INPUT 转换 |
| 根退休后首段 GET 返回不得发下一段 GET | rolling WB→detail 与 dirty UNKNOWN workspace→Task 每 await 复核原 scope/lease | controllers `:60,63`；A 独立 red→green probe 保留 |

## 回执、资源与输入边界

普通 dirty 使用统一明确确认，pending/UNKNOWN/ACCEPTED_READBACK 先硬 BLOCK。`actions/owner.ts:24` 的二次确认同时冻结 body、owner token、draftRevision、原 Task/Cycle/批次或 snapshot/proposal 条件；确认期更新草稿、资格、版本或退休时不写。清页面/关闭面板不是服务端取消。

本范围接口没有幂等 request key。未知写不重 POST/PUT，也没有伪造 by-request/receipt GET。Inbox 仅当同 id/kind/task/session/externalRequest/action 且版本前进的实际已解决行匹配时确认；Dirty/Scope/Rolling 的普通 GET 没有本次因果证明时维持 UNKNOWN。已知取消终态、评审认定、计划 ACTIVE 的读取使用原身份核对，不能把通用 404/409 当作“从未写入”。明确 400/401/403/422 拒绝后保错误、允许修改原草稿和安全退出。

已接受命令只读恢复。父 GET/readback 失败仍留 accepted receipt、正文、CAS 与 BLOCK。Decision 已知且核对的原 child 可以签发准确目的 handoff；别的 task/失败导航不消除原身份，不再次 derive/audit。没有承诺跨刷新保留 keyless 命令或 File；本组没有 File 输入。

资源由 view lease 持有：Inbox 一次 1.5 秒 REST timer、Rolling 已知 AI proposal 一次 1 秒 timer。退休先失效，再精确清 timer；没有本组自有全局 document/window 监听、observer 或 RAF，没有全局清空或模拟自然 mouseup。fake-timer 零样只证明本组 owned timer；实际 DOM/Ant/Markdown 的首次严格账本与真实浏览器清理由组长单独验收，不能推为堆/GC 证明。

## 作者实际执行与冻结

工作目录 `frontend`，最终作者命令：

```sh
npx vitest run src/pages/w4/inbox/controller.spec.ts src/pages/w4/inbox/pages.spec.tsx src/pages/w4/history/controller.spec.ts src/pages/w4/history/pages.spec.tsx src/pages/w4/actions/controllers.spec.ts src/pages/w4/actions/pages.spec.tsx --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w4-evidence/b-final-skins73.json
```

实际 **6 文件，73 PASS / 0 FAIL / 0 skip，exit 0**：History 5 controller +3 RTL；Inbox 7 controller +7 RTL；Actions 36 controller +15 RTL。所有 transport 确定性 mock，没有真实 Git/模型/权限授予副作用。

接入公共 SkinControl 后的最终原始 JSON `b-final-skins73.json` SHA-256 `6137d4a9a3f1f0a681a009f7e7dc87c7c9d42cb2c3196f23feda536346734fa8`，log `6d595e556fb0765cc1b34ddf56de4b3401afb5f2100c903635f5fcf2f6f5abc6`。27 源/作者测试文件 manifest `/workspace/react-full-w4-evidence/b-final-skins-freeze-hashes.json` SHA `df29dbfd00c6f5fa72b8a2e3df99609cd4b0f6c4d82438e33a166291f8e9fbcf`。另一个永久 `actions/independent-c.spec.tsx` 属非作者审查 C，用例单独统计，不混入本组作者 73。源码及作者测试已冻结，报告可补独立结论。

保留历史批次而非只记录绿色：

| raw 前缀 | 实际结果 | 归因 |
| --- | --- | --- |
| inbox-history-first | 10 PASS /1 FAIL，exit 1 | hard-deny fixture 被已经开始的 initial read 覆盖；显式加载正确 DTO 后保持原拒写断言 |
| b-controllers-first | 37 PASS /7 FAIL，exit 1 | 自有 child 返回新包装实例，父 caller 身份不等导致本人 dirty submit 被挡；保精确 owner 实例修复 |
| b-controllers-second | 44 PASS，exit 0 | 上述真实缺陷闭合 |
| b-all-first | 65 PASS，exit 0 | 初版实际 React/UI 合同；不含后续合法入口及原事项消失负控 |
| b-gates-inbox-final | 66 PASS /4 FAIL，exit 1 | 新 Question 子组件接线引用父局部 missing 的真实 ReferenceError；传合并 locked 后原断言保留 |
| b-final70 | 70 PASS，exit 0 | 五 owner gate、UNKNOWN 资格变化、原项消失、view lease 重新读取 |
| b-final71 | 71 PASS，exit 0 | 再补原 Rolling 409 只读 reconciliation 合同 |
| independent-probes/b-read-before | 0 PASS /2 FAIL，exit 1（非作者 A） | 根卸载后额外 detail/Task GET；没有新页覆盖或写入，准确不夸串页 |
| b-final73 | 73 PASS，exit 0 | 上述 await token 两负控、历史可见分类/阅读布局、Dirty 资格变化后错误保留 |
| b-final-skins73 | 73 PASS，exit 0 | 两个真实 page 公共换肤入口及实际端口调用；没有增加或删除 case |

## 原 W0 B8.2 四项

Root 持有原 `w0/templates-history-w0.spec.ts:177,181,185,189`，标题/count/assert 不减少。`history/w0-contract.tsx:18` 提供 `historyScopeW0Contract('record'|'attachment-late'|'attachment-cache'|'retired-error',{record,proof?})`，真实进入 `/tasks/A/design`、实际 router push `/tasks/B/design`，真实选择附件类别及点击安全预览。

Root 原 `w0-four-before.{json,log}` 4 FAIL 是旧 Vue 退休投影/缓存缺口；`w0-four-after` 4 PASS 是第一次真实 React retarget。最终公共 SkinControl 后，A 非作者重跑两 page spec＋原四 helper：`b-independent-skins-final` 14 PASS，exit 0；39 个名称过滤未选中不计绿。A 核对 27 个最终 author source hash 全部匹配。此前 `b-independent-final` 79 selected PASS 包括本组原 73、四 helper 和 A 两个独立 late-GET 负控；14 是受影响复验，不能与 79 相加为唯一样本。Inbox 与冻结历史均在 PageChrome actions 复用公共 SkinControl；实际 select 向原 setSkin 端口传 skin id，不创建新 owner、不复发 mutation。原 in-place 三皮肤同 textarea/原操作证明仍保留。

作者未修改 W0 原文件、断言或 case 数，也不以 73 作者绿抵消其余 W5 红。

## 非作者审 C Recovery/Publication

非作者实际审查了 C 的 Recovery 原三模式、lineage/冻结指纹/accepted child、Publication 原提交/继续推送/MR/源代码冲突/AI/手动编辑/应用与 rollback。对照 `recovery/controller.ts`、`publication/controller.ts`、`TaskPublicationActions.tsx`、`MergeEditor.tsx` 及原 Vue/Java 协议：控制器无 Vue/Pinia delegate，提交、保存、AI 与同步仅显式动作；父 sibling gate 与同身份读取保护存在。Recovery lineage 不是 keyless create 的因果回执，UNKNOWN 保 BLOCK；已接受 parent/mode/fingerprint/child 只允许精确 child handoff。Publication 读失败保 receipt/草稿，recovery 只 GET，不再次 POST/PUT。手工 textarea 为受控本地编辑，不偷偷采用或保存 AI。

| 严重性与发现 | 实际证据 | 作者最小修复与独立结论 |
| --- | --- | --- |
| P2：UNKNOWN AI 已有旧 suggestion 时，另一次同值 resolution 保存可增加 version 并保原 suggestion，被旧 lookup 误认本次接受 | 静态交叉依据 `LocalSyncConflictService.java:202` 保旧建议；C 的 `publication-ai-unknown-before.json` 实际期望 UNKNOWN、得到 SETTLED，1 FAIL。不是被测试标题推断的缺陷 | C lookup 在原 path/hash/version/resolution/body 之外，要求实际新 suggestion 字节不同；既有 case 保全部断言，新旧值正负控在独立最终复跑通过。无法核对时仍 UNKNOWN，不新造 GET 或重发 AI |
| P2：继续推送确认等待期间 window focus GET 返回别的 commit，旧确认仍发当前任务 POST | C 实际 branch 变化负控 red 保在 `publication-confirmation-before.*`。B 另用真实 COMMITTED/SHA 流程、真实窗口 focus/30s 冷却、实际 owner GET 投影，重建旧组件回放得到明确 `publishTask(task,undefined)` 一次 | C 公开 UI confirmation 捕原 token、draftRevision、Task 状态/版本/资格、原 publication、session/file path/version/hashes/resolution，并在 policy 和 handler 重新核对父 gate。原确认变 BLOCK、Stay 可用；显式新确认才可写。本组同一 COMMITTED/SHA probe 修后 0 POST/原 dialog 保留，独立通过 |

独立 before 的完整 provenance：C 事后 inverse-patch 重建组件与其修前记录 SHA `4fd2843a63b561a9751cfd3f277ab6600d14c57e9dafafdf9298715c0f09dd4b` 完全一致。本组复制到 test-only fixture，仅改三条相对 import 路径；组件行为保持原字节，在当前真实纯 TS owner/原 API mocks/真实 FoundationProvider 上回放，不冒称再次运行完整旧 checkout。fixture SHA `0213823a3ae831d42ffa1b1512b0ec6ec60810b81ed82b8da0a2ad9d1e4c77a6`、probe SHA `e6bf830bb02f5e6bdf5637d8f390b7b1209123a556222de4b33a67bb1cff8a17`。实际 red `/workspace/react-full-w4-evidence/independent-probes/c-confirm-reconstructed-before-v2.json` SHA `dc1294f00e144bfcc0cd39953e2be33491d6c72c8da132c1d9b887abb29dff7f`，exit 1，0 PASS/1 FAIL。临时工程组件和 spec 已归档并删除，没有回滚 C 生产、留下永久 red 或改他人测试。

本组早期 `independent-probes/c-confirm-before` 文件名虽为 before，实际读取的是 C 已修后的源码，1 PASS **仅是修后候选**；不能当修前失败。第一次 reconstructed-before 在旧 Modal 关闭/SENDING 上失败，随后同 case 以 act/flush 等实际 Promise，第二轮精准到 1 POST 失败，未降低门槛。第一次 final63 前 hash collector 对 list 误按 object 读取报 AttributeError，不是产品红；修正采集格式后完整 63 原样再跑并保存真实 before/after hashes。

最终 C v6 15 文件 manifest `/workspace/react-full-w4-evidence/recovery-publication-source-hashes-v6.json` SHA `1c8d339d846de923f0491127feb170d3058639e27daa9c5bb8733d787ca2a005`。本组永久独立负控为 `actions/independent-c.spec.tsx`，不计 B 作者 73。工作目录 frontend，非作者最终命令：

```sh
npx vitest run src/pages/w4/publication/controller.spec.ts src/pages/w4/publication/TaskPublicationActions.spec.tsx src/pages/w4/recovery/controller.spec.ts src/pages/w4/recovery/RecoveryStudioPage.spec.tsx src/pages/w4/actions/independent-c.spec.tsx --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w4-evidence/independent-probes/c-final63-verified.json
```

实际 **63 PASS /0 FAIL /0 skip，exit 0**：C 已有 62（Publication controller 34/RTL12、Recovery controller11/RTL5）＋B 独立 probe1，来源独立统计，不把重复复跑计入全局 unit 数。JSON SHA `8c9d0681278974cd9e74b7e9ba2b7a94596efdf77b834c3e41270bfdf2922673`；log `f9b7417887c040f31184696214550e29aea36c4f649c61f5c78e88c204735b3d`。C 15 个文件加独立 spec 共 16 文件在最终执行前/后 hash 0 差异；`c-verified-before-hashes.json` SHA `2ad0d412ebc358f6819964a249b8c3b91d15ef4bfcd63d49f4d39746d39bbb76`，after 同内容。两项发现闭合，当前所审 scope 没有未处理阻塞。

验证边界：所有 transport 确定性 mock，本组没有 File 输入或跨刷新命令持久化承诺，没有真实 Git/模型/外发。C RTL 证明一个实际 StrictMode view lease 的 window focus callback 同身份精确 remove；没有把它推广为全部 DOM/Ant/App SSE 的 RO/RAF/监听零账本或 GC 保留证明。组长统一 typecheck/build/full unit、真实四 routes ×三 skin 浏览器、App SSE 与 page/child 分离账本。B 没有启动 browser/server/build，不引用候选截图为本次最终视觉验收。窄屏设计/适配本轮 scope 外，原历史断言留存；本报告仅桌面行为。
