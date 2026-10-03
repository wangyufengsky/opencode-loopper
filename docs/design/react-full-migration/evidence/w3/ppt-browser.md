# W3 PPT 全页迁移与生产浏览器证据

本报告作者 C `/root/react_ppt_canvas`，沿用原启动记录显式指定的 `gpt-6.1-sol / xhigh`；当前工具不提供实时平台配置查询，身份自述不当作平台字段。基线 `a77b86ac40f458b9633acc3691fd2209125cb7a3`。作者拥有 `frontend/src/pages/w3/ppt/**`、W3 浏览器夹具与本报告；组长持有唯一 VueRouter、bridge、共享文档组件和完整集成验证。未提交、安装依赖、调用真实后端或付费模型；未修改原 Pinia、API 和已验收的 React 自由对象画布。

最终精确源码生产批次 `/workspace/react-full-w3-evidence/browser-delivery.log` 已 **31/31 PASS、exit0**。独立读取同批69份卸载首样、18次活动手势DTO/实际矩形投影和33张稳定图片，全部符合既有严格断言。旧候选图片发现的局部CSS祖先P2和公共 `retire(false)` 合同缺口已修正；下文保留各轮真实失败，旧图不当作最终图。同一生产构建的W2关联回归最后 **49/49 PASS、exit0**，其51份首样也独立核对严格零。

## 原行为与实际 React 入口

真实生产路由 `/ppt/:id` 导出 `ppt/index.tsx:1` 的 `PptStudioPage`。页面使用 `W2PageProps`、`PageChrome` 和 foundation；React 子树消费纯 DTO，完整纯 TS studio controller 持有唯一命令、草稿及 SSE owner。没有 Vue 组件套壳、第二份 history 或同页旧 Pinia writer。

| 原源码/功能 | 本批实际入口 | 保留的行为 |
| --- | --- | --- |
| `views/PptStudioView.vue:15`、`:632`、`:689`；自由对象画布和页面导航 | `ppt/PptStudioPage.tsx:85`、`:88`、`:93` | 直接使用既有 `react/ppt/PptCanvasView.tsx` 与 `PptSlideNavigatorView`；选择、拖动、缩放、revision、取消、锁定、键盘/Alt 调整、历史只读和预览过期提示保持底层实现；应用 skin 与 deck theme 分开 |
| `views/PptStudioView.vue:65`；整篇/页面/对象讨论作用域 | `PptStudioPage.tsx:45`、`Chat.tsx:10` | 原文档/slide/element scope；讨论和明确确认执行分开；默认模型来自作品 DTO；问题 ID/version/answer/confirmed 精确；thinking/tool/failure 披露、长回复折叠、历史分页、保留旧滚动位置及显式跳至最新 |
| `stores/pptStore.ts:173`、`:262`；状态读取与活动订阅 | `controller.ts:81`、`:115`、`:134` | 单一 SSE、4 秒活动轮询、revision 一致后投影、消息 version 不回退；scope/退休后迟到响应不投影；版本持续不一致每轮最多 3 次读取，留给后续轮询或明确刷新，无无限紧密 GET |
| `stores/pptStore.ts:287`；提交与恢复 | `controller.ts:156`、`:173`、`:222`、`:262` | 既有真实 endpoint/method/key/body/revision/File；发送与未知阻离开；未知仅按现行安全能力显式恢复；accepted 读失败只 GET，不再 POST；409 保原基线，不自动合并、换 key 或重发 |
| 原 PPT 对象/页面属性及纯 TS editing/domain | `Properties.tsx:7`、`controller.ts:286` | 文本、几何、字号/字体/颜色、对齐/粗体/列表、图片适配、形状/线、表格、图表序列、锁定/重叠；页面标题/section/notes；保未知字段、真实校验、900ms dirty 自动保存、旧版本草稿保持、明确刷新与手动保存 |
| 原制作方案 | `Plan.tsx:7` | 制作目标、叙事结构、页面内容、视觉规范、图表素材、演讲辅助、交付设置七模块；方向在 DESIGN 冻结；章节与页次调整、资料引用、版式主题、字体及 delivery 原字段保留；打开/恢复草稿不自动写、不自动生成 |
| `views/PptStudioView.vue:236`；资料/历史/方案详情 | `Details.tsx:11`、`:25`、`PptStudioPage.tsx:95` | 本地资料与 PNG/JPEG、拖放、项目知识和原文、素材插入；批次/作业进度、失败继续、版本分页、历史只读、原版本恢复、归档/恢复归档和方案重开 |
| 原下载与面板配置 | `Download.tsx:6`、`PptStudioPage.tsx:35`、`:68`、`:91` | 原 PPTX/PNG endpoint 与 filename，不信任 artifact 外部 URL；失败保留已有导出；abort/Blob URL/timer 精确释放；助手宽度原设置保留，Pointer capture、取消/blur/unmount 与键盘调整 |

命令类型在 `controller.ts:9` 明列 operations、plan、action、message、reply、job、retry-job、generate、confirm-generation、resume、stop、upload。写入只由用户动作或原用户 dirty 命令的延迟保存触发，初始化、StrictMode effect replay 和 skin 变化均不写。语义确认分别使用 archive、restoreArchive、restoreRevision、reopenPlan、deleteSlide、removeObject；计划页面移除的最后漏项已在 `Plan.tsx:43` 明确传 `ppt.deleteSlide`，章节移除仍用原 `ui.delete`。

## 身份、回执、File 和草稿边界

- `controller.ts:212` 保存实际成功回执的 `acceptedRevision`，GET 仍为旧版本时保持 `ACCEPTED_READBACK`/BLOCK；刷新恢复不会把一个成功但旧的 GET 当作原写入已读回。旧 ops/plan metadata 没有该字段时保守要求至少原 revision+1。
- 同消息核对实际 document ID、idempotencyKey、expectedRevision、text 和 scope；上传核对原 SHA-256/name/bytes/READY。keyless stop/retry-job 只显式 GET 原 run/job，不能由其他终态或仅在线状态猜测接受，不能重发 keyless 写。
- 原 `loopper.ppt.pending/chat/answers/element/slideDraft/plan` sessionStorage 名称保持。原 File 只在当前 owner 内存，显式重选必须相同 SHA/name/size；旧无摘要条目不能证明同 bytes。不承诺跨刷新保留 File、不把手工重新选择称持久化恢复。存储失败仍保 volatile UNKNOWN 和 BLOCK。
- 本地 dirty 保留 baseline/revision/custom 字段。恢复草稿、切主题、打开 editor 不安装自动写；真正编辑才安排 900ms 自动保存，无效/旧 baseline/锁定/未知时暂停；关闭 editor 取消其定时器但不静默删草稿。
- 原 owner 退休后 response 和 finally 不污染新 owner；手动场景关闭或离开取消 capture，不调用服务端 stop。下载取消只取消本实例请求及 URL，不清他人资源。
- 非作者最后发现公共 `retire(false)` 在拒绝退出前先停 owner 的问题；实际 bridge 使用 `true`，未冒称已证路由回归。`controller.ts:363` 现先读取 `controller.canLeave()`，拒绝后不取消资源或禁用恢复，原 dirty/SENDING/UNKNOWN 仍可继续。既有 exact-message 测试补三阶段拒绝退休及原 SSE 不 close、同 key retry 成功、明确 detach 才 close 一次。

## 作者单测：已实际运行

命令（仅本模块聚焦，无全量/build 并发）：

```sh
npx vitest run src/pages/w3/ppt --maxWorkers=1 --reporter=verbose --reporter=json --outputFile.json=/workspace/react-full-w3-ppt-unit-final-v3.json
```

实际 exit0，3 文件 **39/39 PASS、0 pending**：controller 21、完整真实 React 页 13、属性/方案/下载 5。原始输出 `/workspace/react-full-w3-ppt-unit-final-v3.{log,json}`。这是计划确认语义最后一处修正之前的完整批次，保留其原字节，不冒称同一哈希重跑。

最后修正仅 `Plan.tsx` 的确认变体和既有首个七模块测试内的真实按钮→确认语义断言，没有新增/删除 case；已运行 `npx vitest run src/pages/w3/ppt/editors.spec.tsx --maxWorkers=1`，**5/5 PASS、exit0**，输出 `/workspace/react-full-w3-evidence/ppt-editors-semantic-final.{log,json}`。完整最终批次由组长统一运行。

其后公共 `retire(false)` 修正也只补既有 guard 测试，`npx vitest run src/pages/w3/ppt/controller.spec.ts --maxWorkers=1` 实际 **21/21 PASS、exit0**，输出 `/workspace/react-full-w3-evidence/ppt-retire-guard-final.{log,json}`。三份 spec 仍39定义，不把这些重叠批次相加。

非作者 B 对上述最终字节独立完整复验 **39/39 PASS、0 skip、exit0**，raw `/workspace/react-full-w3-evidence/ppt-independent-legacy-retire-final.{log,json}`，JSON SHA-256 `b7341f41c2b84496697e44ba35ce79515c46588122c001d5c95463a51163c8dd`；其13源文件快照前后0变化。普通退休修正的原因是静态公共合同缺口，不伪造一次未运行的真实路由红。

图片中发现局部样式P2后，只在 `PptStudioPage.tsx:88` 与 `:93` 的Canvas/Navigator各加 `.ppt-page` wrapper，复用已验收样式；原Strict case补局部祖先并验证Foundation/page根不在旧样式范围。`PptStudioPage.spec.tsx` 关联聚焦 **13/13 PASS、exit0**，`/workspace/react-full-w3-evidence/ppt-local-css-final.{log,json}`。这些jsdom断言只验证样式范围和既有行为，实际CSS矩形由原六项浏览器手势用例验证。

非作者 B 已对该局部修正的最终13源文件完整复验 **39/39 PASS、0 skip、exit0**，`/workspace/react-full-w3-evidence/ppt-independent-legacy-local-css-final.{log,json}`；JSON SHA-256 `909e2be7e881bf752f06af7f84d4f960074f870fd36ebad246ddb5b3aae4ad3b`，21文件来源索引运行前后0变化。未触碰Foundation根、原画布或共用CSS。

组长最终完整 `unit-delivery.json` 也包含该最终39项并全部PASS。该全站批次真实 **1852项：1807 PASS / 45既有W0 FAIL / 0 pending，230文件**；45红分别为designer18、workflow23、templates-history4，不将全站批次称全绿。JSON SHA-256 `b76e080e86b294df493f58cdee3dfe2a1bc3870ad11a61efc11ffd9fcbc61459`。

39 项覆盖初始化 GET/单订阅/StrictMode、原 body/key/version/revision、accepted 旧 GET 与只读恢复、消息和文档不回退、409、迟到响应/退休、稳定 mismatch 有界、STOPPING、keyless unknown、不隐式执行、真实问题 boolean、File 原引用/SHA/旧无摘要/大小限制、draft/autosave/旧基线、源码/check 迟到、真实 React 画布键盘、三皮肤原 DOM/body/SSE 保留、历史只读、原对象菜单 pointerdown 后 click 一次写、Stay 确认、安全富文档、旧消息滚动及显式返回最新、助手 resize cancel、下载 abort/URL/timer。

助手 blur/capture 单测使用 jsdom 事件和模拟 capture 方法，明确不是 Chromium trusted pointer 或 OS 焦点证明。旧 Vue 单测通过也不作为本批 React 实现通过。

## 实际生产浏览器矩阵和采样方法

`frontend/e2e/w3/playwright.config.ts:1` 使用已构建生产 dist 的 Vite preview，Chromium 本地路径，41773 单 worker；无 W1 demo、HMR、第二份 router 或后端 proxy。组长唯一启动/执行。`--list` 实际 exit0：**31 tests/3 files**，含本模块新 28 项及组长引入原 `read-consistency.spec.ts` 的 3 项原合同。

| 现有 case 范围 | 数量与严格首样计划 |
| --- | --- |
| PPT 三皮肤真实路由 direct/reload/back/forward | 3 项，3 份首样；真正手动 scene/navigator 再返回默认预览 |
| PPT drag 与 resize 三皮肤 | 6 项，各 3 cycles，共 18 首样；真实键盘 Enter 选择、105px/20px Pointer move、dragging 和实际 capture=1 正控；header 原返回作品列表 SPA leave |
| PPT UNKNOWN discussion 原 body、三皮肤原 DOM、BLOCK、显式同 key retry | 1 项；运输模拟第一次失联，精确第二 POST 与第一相同，不隐式写 |
| 目录、document run、source run、Knowledge 新建/已有五状态×三皮肤 | 15 项，各 3 cycles，共 45 首样；direct/reload/back/forward、真实内容/选择/返回焦点、Mermaid、纯 Lezer readonly 代码/原文/引用绝对行号高亮 |
| 显式历史归档三皮肤 | 3 项，3 首样；默认历史 GET=0，明确进入后九个原 GET、冻结 hash/immutable/health、两个导出原 bytes，版本 summary 真实展开后正文可见 |
| 原 read-consistency 当前真实消费者 | 3 项；组长持有原 spec，仅重定向实际入口，保旧筛选迟到、health/count/stale 告警、退役深链读合同 |

28项新用例与3项原合同最终产生 **69个严格immediate snapshots**。实际33张截图：18默认、9已选富内容、3显式历史归档，以及在既有active-drag cycle0起手前保存的3张PPT真实对象选中/属性图。三skins=spdb/tech-blue/github-white，1440或1280桌面；窄屏OUT_OF_SCOPE。

资源观察复用已验证的 `e2e/w2/resources.ts`，其 source 不变。先证明 canonical `[data-react-page]` 恰好一个、root connected、beforeunload 和 MediaQueryList change、外部 sentinel；手势另证明当前真实 capture。通过实际原 PageLink 的 DOM click 发唯一 VueRouter 意图，在旧 root 移除的第一 MutationObserver microtask 立即 snapshot；首次清零前不补 move/up/cancel/window blur。PPT 返回 `/ppt`，其他返回 `/inbox`，不先展开收起的 sidebar 或触发 modal/inert 取消手势。采样后才自然 release 物理鼠标和 back/forward。

owned/未知 listener、observer、RAF、capture、timer 仍严格空数组；外部哨兵及精确已证明的 Ant application-module 100ms timer 留在 raw，不以栈白名单、connected 过滤、wait 或删别人监听求绿。强账本记录 detached target 不等于 retaining path/GC/无限堆增长证明。SSE separately 检查原页订阅 close 与 app accounting 订阅保留；completed run 原本无活动订阅，不能伪造 activity。

PPT PNG fixture 已由透明 1px 改为导航前本地浏览器 Canvas 按同一 deck DTO 的 960×540、文本/位置/字号/颜色生成两页（第二页原 DTO 无对象）。正常路径验证自然尺寸并等待连续两 PNG buffer 完全相同；这是实际 Chromium UI + 本地运输模拟预览，**不代表真实后端 PPTX 排版、模型生成或内容质量**。所有其他 fixture 同样仅 mock 现行 HTTP/SSE DTO，unexpected endpoint/外部请求失败；截图文本明确本地模拟。

## 首轮红与修正：保留真实失败

`/workspace/react-full-w3-evidence/browser-first.log`：**31 项，6 PASS / 25 FAIL**。原三个 read-consistency 与三 skin Knowledge-new 通过；其余失败没有 skip、改 expected 或删除业务断言。

| 首轮失败 | 精确归因及本轮修正边界 |
| --- | --- |
| PPT normal 3、UNKNOWN 1 | fixture 错用 canvas route 已收起的 sidebar Inbox；改真实已有 header 返回作品列表 `/ppt`，保 guard、第一次 root-removal sample 与 back/forward |
| PPT active 6 | mouse 预选择曾记录同 element/pointer capture，再起手后历史账本显示 2 行；改真实键盘 focus+Enter 预选择，保旧账本/pre1/after[]；不 dedup、不把多个记录误称多个 native capture |
| 目录 3 | 原 Ant Tabs/overflow detached target 上 touchstart/wheel 仍显式未卸载；B 改受控 React 归档 tabs，保全部五分类、按需 GET、ARIA/键盘和两个导出 |
| source 3 | 原 RichDocument 的 React portal 新 host 保有 38 delegated DOM listeners；组长改安全 sanitized DOM→同根 React 树，保真实 Mermaid、安全规则和 lazy IO 清理 |
| document 3、Knowledge-existing 3 | 上述 38 加原 CodeMirror DOM 的 9 个 listeners；组长改正式只读 React+既有纯 Lezer parser，无 EditorView DOM 副作用；browser 更新实际 renderer 定位并保持 exact 原文、readonly、引用高亮与严格资源 |
| 历史导出 3 | 原 Ant 受控 tab 的『导出说明』点击后 aria-selected 未切换、第二 export 不可达；B 同一 Tabs 替换修复，不能删除 workspace export 覆盖 |

首轮 resource red 的 detached listener 证据仅指未显式 remove 的严格门槛，未以强账本证明堆泄漏。observer/RAF/capture/timer 该组失败记录为零，不能覆盖 listener 失败结论。

第二轮 `browser-second.log` 准确为 **19 PASS / 12 FAIL**：PPT9为跨React路由复用时旧beforeunload未解绑，组长修精确生命周期；历史3在版本折叠details上默认accessible textbox查询失败，代码locator更新后的新夹具遗漏真实summary展开。已改真实click summary→可见正文→原导出，未includeHidden；后者是fixture可达性修正，不列产品bug。

中间 `browser-final.log` 组长实际 **31/31 PASS**；独立读取当轮 raw `/workspace/react-full-w3-evidence/browser-independent-ppt-intermediate-audit.json`，69/69 connected 正控、69/69首次 root disconnected、五类 owned resource 严格零、69/69无后续清理输入，30/30 PNG declared/hash 和最后两次 exact buffer 一致。该批在公共 retire(false) 修正重建和3张新增 selected 截图之前，不充当最终源码/33图证明。第二轮原日志准确为19 PASS/12 FAIL，其中PPT9 legacy beforeunload 归属跨 React route reuse 未解绑，组长修精确生命周期；历史3为上述折叠夹具。

随后 `browser-frozen.log` 实际 **31/31 PASS、exit0**，raw `/workspace/react-full-w3-evidence/browser-frozen`。非作者/独立读取形成 `/workspace/react-full-w3-evidence/browser-independent-ppt-final-audit.json`（SHA-256 `5aea249fddb609aaa2fe2c3eb83d2a09f1a7e0e40a9d181371c37298397720c1`）：69首样全部 same document/root、connected→disconnected、五类 owned资源0、sentinel保留、无 move/up/cancel/window blur清理；其中drag/resize18首样全部 pre capture=1，各三皮肤三轮；33/33图片实际字节与稳定声明hash一致、两连续buffer完全相同。82条 allTimers 记录（跨snapshot计数，并非82独立对象）全部精确Ant module owner，原raw未隐藏；45条普通路由样本另保 SSE前后记录。此“final”文件名指该批当时冻结候选，以下图片P2修正后需另新批次，不能覆盖旧证据。

**真实图片审查P2**：三张PPT selected图显示原生灰button遮住预览文字，框位于画布左上；原DTO x=80/y=70却raw beforeX=95为画布左边。`components/ppt/pptEditor.css:206` 的 absolute/transparent/selected 规则要求 `.ppt-page` 祖先，W3页只有 `w3-ppt-studio`，即便导入CSS也未应用定位。修正仅两个局部 wrapper，不改基础画布或CSS、不把旧class套整个Foundation。既有六active case每轮新增真实 position=absolute、DTO x/y/w/h→实际client矩形投影（浏览器CSS量化0.05px）、105/20真实输入后的world整数geometry exact及screen projection；保持capture和首次资源零门槛。旧31绿只能证明其当时断言，**不能证明对象坐标和视觉已正确**；33张旧图原样保留，最终重新捕获。

## 最终生产证据独立核对

最后重建后 `browser-delivery.log` **31/31 PASS、exit0（1.6分钟）**；同名证据目录 `/workspace/react-full-w3-evidence/browser-delivery/`。独立审计 `/workspace/react-full-w3-evidence/browser-independent-ppt-delivery-audit.json` SHA-256 `73adfdbf6000fbc214e39940b309d5988e42cc9590d679063890e91695ad487b`，保留每个raw文件SHA、首样与图片明细：

- **69/69**同document、原root身份保持、connected→disconnected、外部sentinel原callback保留、listeners/RO/RAF/capture/owned timer严格0；首样之前没有pointermove/up/cancel、mousemove/up或真正window blur。卸载元素自身blur单独记录，不充当window blur。
- **18/18**活动drag/resize正控capture=1、trusted pointermove、absolute定位；三皮肤×两手势各3cycles。原DTO 80/70/400/90按实际canvasclient矩形投影，105px/20px真实mouse输入对应原底层缩放和整数round规则，移动后geometry逐项exact。初始及移动后screen投影最大量化误差均 **0.0104167px**，原0.05px阈值未变。输入坐标由实际browser测试源码与raw矩形/expectedGeometry交叉核对；事件账本只记录type/target/trusted，不伪称账本另记录了clientX/Y。
- **33/33 PNG**实际字节SHA等于stability声明SHA及最后两次连续完全相等buffer。逐张查看三张最终PPT selected图，原生灰button遮挡已消除、选择框与80/70/400/90属性一致；另查看三皮肤Knowledge引用代码高亮图，组长新只读代码改淡action混色/边线后的字节均来自本次batch，不沿用旧强蓝图。该视觉检查不等于真实后端PPTX或完整对比度审计。
- 69首样中的 **71条raw Ant module timer记录**（跨snapshot计数，非71独立对象）均保持透明；每条正向核对真实HTMLHtmlElement/capture click、pre-root注册身份、完整callback函数字节、本次dist bundle SHA及已安装Ant源码SHA。无unknown timer排除，没有取消他人帧或计时器。
- **45份普通路由SSE样本**核对app accounting订阅保持；其中9份Knowledge-existing原页SSE明确关闭一次，18份terminal source/document run原本没有活动own SSE，未虚构订阅以求close断言。其余PPT的单SSE与StrictMode证明来自真实controller单测，浏览器首样未另持有其SSE计数，边界分开。
- PPT+浏览器 **21文件冻结索引0 mismatch**。旧 `browser-frozen/` 和旧审计原样保留，含修正前视觉缺陷，仅作before证据；最终引用 `browser-delivery/`。

## 非作者交叉审 A 文档/源码 run 模块

只读核对 `templates/runs/core.ts:18` 的 view resource lease 和 per-channel ticket、`parts.tsx:11` 的 protected owner、`runController.ts:59` 版本不回退/child draft 保留、`:78` 原 requestKey/version、`:103` 注册 child/BLOCK、`:110` 明确退休、`documentOwners.ts:36` clarification、`:67` supplement 原 File、`recoveryOwners.ts:10` 原 batch CAS 与 `:61` diagnostics、`contentOwners.ts:66` 相对报告路径边界/`:90` 下载资源。

确认 import/render 不写；Strict detach 仅取消 view resources，实际 route owner 退休独立；child draft/UNKNOWN 保原 identity，父页刷新不能以新 requirement revision 悄然替换其 UI；accepted callback 复核原 ID/version，keyless recheck/check 只 GET 后仍 UNCONFIRMED；批次专属原 CAS 显式重放不伪装通用 key；诊断返回停止状态不以“连接正常”猜停止证明。未在这些 source 找到额外可复现写入/作用域回归。

独立 hash 核对 `/workspace/react-full-w3-evidence/run-freeze-hashes-v3.json` 的24 entries（20源码/test＋4 raw）**0 mismatch**。随后按组长指派实际复跑 A 全部4 spec，`npx vitest run src/pages/w3/templates/runs/*.spec.ts src/pages/w3/templates/runs/*.spec.tsx --maxWorkers=1`：**53/53 PASS、0 pending、exit0**；独立 raw `/workspace/react-full-w3-evidence/runs-independent-ppt-final.{log,json}`，JSON SHA-256 `b535f7fed0e640a20f40730581e7e7a82288fd86b7b5a4a855be2ff636959f58`。测试前后作者24entry hash仍0变化。作者 W0 helper21 selected PASS＋37 filter未选择另计，不当作整58通过。真实source/document已包含最终31项生产门禁和18个严格退出首样，首轮共享listener红仍保留。

组长 bridge 最后修正亦独立复验：`W2RouteBridge.vue:64` 每次 destroyPage 精确移除本页 beforeunload，`:117` 成功 mount后绑定一份；BLOCK 的 canNavigate 不进入 destroy，skin/query仅 render，仍唯一 VueRouter。新 `w2Bridge.spec.tsx:124` 使用真实MemoryRouter+Reactroot验证旧 callback remove 身份、拒绝导航保持原 owner/guard、允许reuse仅一个新guard、真正exit后不再 beforeunload阻止。完整同文件 **15/15 PASS、exit0**，raw `/workspace/react-full-w3-evidence/bridge-independent-ppt-final.{log,json}`，JSON SHA-256 `e0b69b9305f1a0a2fc437f97b2b206f3e7f2c6be08dec7f2eda103df93719946`；没有修改组长文件。

W2关联浏览器旧目的地也必须按迁移后真实入口核对。只读检查组长 `e2e/w2/product-routes.spec.ts:64–75` 的最小diff：原root第一移除microtask的owned五类严格零、无清理输入和0写入断言保留；仅随后back/forward的目的地改为实际React `nav.templateTasks` /「任务模板」。Roles末尾标题同样仅校正目的地。`e2e/w2/resources.ts:98` 仍固定原root身份，`:108–121` 未因新目的地React而更改旧实例门槛。此前45 FAIL/4 PASS的批次是旧目的地root0/旧heading预期，保留原日志，不掩盖为一次全绿。

同构建最后实际 `/workspace/react-full-w3-evidence/w2-regression-delivery.log` **49/49 PASS、exit0（2.2分钟）**；独立读取目录下42份普通首样＋9份Roles活动pan首样，**51/51**原root身份、同document、connected→disconnected、五类owned严格0、sentinel保留、无补发自然清理输入。活动9份仍真实106点命中路径、105/20位移、capture1，不按工具栈过滤。额外只读审计 `/workspace/react-full-w3-evidence/w2-regression-independent-ppt-audit.json` SHA-256 `0d23705ed8f2dd19d4edc8520422391c2517a517a43d86aa7288d2cbbaabc156`。

## 证据索引与未覆盖项

较早冻结来源 `/workspace/react-full-w3-evidence/ppt-browser-freeze-hashes.json`，21 files（PPT13＋浏览器8，含组长 historical-consumers 文件只读哈希），SHA-256 `835fc0dc8831d099ada415a1ee9e94095a9bc891961452626a4fada9b6a8fa82`，原样保留。summary展开、retire(false) 和三张选中截图修订的中间来源另锁 `/workspace/react-full-w3-evidence/ppt-browser-freeze-final-hashes.json`，21files，SHA-256 `0ba7d91ecfed561990cb9426a7f6377d9dbaa9e763bf51a4e6eeaed4723dd477`；controller `ca4d510736a2af3b7e89b3777c158fd72ff009f9ae59c86b6501f7fe54a60084`，controller spec `2847292a86a69fb74a329b315531809fa68e504d5e5101656620f85eba2d99d3`。该旧来源原样保留，随后局部样式修正另建来源索引。

图片P2修正最新候选另锁 `/workspace/react-full-w3-evidence/ppt-local-css-freeze-hashes.json`，21files，SHA-256 `0e658dc220d5b525ab2358bbe27df711ab5cca7f72f9e6f9020f05ff40501dc7`；page `0d744c2211a7ed32f7b6c091db322fe619d84ec45ec056f5ed7ef52f68507712`、page spec `1de6117bf8f63b57148619db0eca70e60ac42e43927cd88d2bd9ae4ff093c37b`、browser PPT `4cfc22338f0c89a1a32c94bb4dd9b515c15aa50109f83ac8e8f1e5d202cd5030`。之后仅doc/既有证据审计，不扩场景。

原日志、trace、中间截图与独立raw审计在workspace外部证据保留，不提交环境日志。组长最终delivery typecheck/foundation typecheck/build/tooling33/accounting13均PASS；聚焦226为218本批新项＋8旧bridge项。W3 Chromium31、最终33图片和69首样已独立核对，同构建W2关联49及51首样已实际完成；全unit45已知W0红准确保留，不能称全站unit全绿。未验证真实Java/Provider/PPTX工具链、跨刷新File持久化、任意设备、窄屏或GC retaining path；没有把本批模拟浏览器、jsdom或源码审查推广为这些证明。
