# W2 secondary 七页与非作者路由验收

本记录由原组员 C `/root/react_ppt_canvas` 编写；任务记录显式指定 `gpt-6.1-sol / xhigh`，没有把自述当作实时平台配置。工作区 `opencode-loopper-react-full`，W2 基线 `6ffc7dbef1302450e900979d5484d4f3b3ebfa80`。作者独占 `frontend/src/pages/w2/secondary/**`、新 `frontend/e2e/w2/**` 与本文件；未修改原 Vue 页面、store、API、W0 测试、已验证画布或后端，没有付费模型/实际数据库连接。桌面 1440/1280、三皮肤在范围内；窄屏不在本轮范围。

## 七页生产行为与旧断言映射

七个出口由 `secondary/index.tsx` 导出，经真实 `migration/w2Routes.ts:6–18`、`W2RouteBridge.vue` 进入生产路由。React 页面接收 plain W2PageProps；TaskPort 继续委托唯一原 Pinia owner，不增加任务/SSE owner。PPT Studio 与 Knowledge 会话仍由 W3 持有，已验 React 画布不重写。

| 实际路由 | 原来源与必要行为 | React 出口与验证 |
| --- | --- | --- |
| `/` | HomeView 原工作区/系统入口；真实项目与最近对话、低密度本地 search，无虚构汇总/count | HomePage：两个真实只读端口、各最多六项，选中才详情；普通 SPA 链接保留 Ctrl/Cmd/Shift/Alt/中键原生行为。secondary.spec Home 3 项 |
| `/ppt` | PptListView.spec:47；usePptCreation 的 create→逐附件 upload→取 revision→首 DOCUMENT send，禁止直接 generation | PptListPage + pptCreation：原 ID/key/body/revision、File 与 SHA/顺序，分段 accepted/unknown 恢复。pptCreation.spec 11 项 + 页面输入负控 |
| `/knowledge/history` | 原 KnowledgeHistoryView 搜索/项目/时间/归档/状态、分页去重、URL 与浏览深度/滚动、打开原会话、archive/restore 原版本 | KnowledgeHistoryPage：route.fullPath 恢复查询作用域，初挂/StrictMode 不隐式 replace；220ms 只更新实际 route，由 route props 驱动读；迟到读不可发布。成功读刷新 selected 的 archive/version，失去项关闭 context。页面 4 项 |
| `/runtime` | RuntimeView.spec:9/33/77/109：真实服务与 CLI 版本分开、尝试地址、启动失败、显式 start/restart localUI；不渲染独立授权卡 | RuntimePage：TaskPort 原 API/localUI 运输；generation 为真实 string DTO。UNKNOWN 显式 GET 后也不以 ONLINE 推断原 restart 已接受。页面 2 项 |
| `/tools` | ToolsView.spec:10/27：Skills lazy、选中文档、工具描述文本，原 MCP project/global CAS 与必需工具不可改 | ToolsPage + SkillMarkdown：项目/标签换代及关闭失效旧 reads；键盘 tabs、服务完整性、required 开关保护、source 显式停用、Markdown DOMPurify/真实 Mermaid；不调用 AI。页面 4 项 |
| `/databases` | DatabaseView.spec:8；原真实配置、历史 JDBC/driver、只读 test 与 save 分开、空密码保留、秘密/CAS/project 授权 | DatabasePage + databaseDraft：编辑、真实 DTO/版本、driver 元数据、草稿 test 不保存、迟到 probe 拒绝；dirty 明确确认，pending/unknown 硬阻离开。页面 3 项 + 纯 DTO 4 项 |
| `/insights` | 原 InsightsView 的已应用筛选、cursor、质量/人工认定、未知 token/currency，不把真实 read 变假 count | InsightsPage：筛选与分页保持作用域，null unknown、金额真实 string DTO；原任务评审深链。页面 2 项 |

`secondary.spec.tsx` 最终 20 项（含 PPT 输入与真正根 StrictMode 初挂不 write）；`state.spec.tsx` 7、`databaseDraft.spec.ts` 4、`pptCreation.spec.ts` 11，总定义 **42**。没有删除旧 Vue 业务断言或改为 skip。

## 写入、恢复与秘密边界

- `state.ts:10–23` 的每个读 ticket 和 committed-result apply 都在真实 mounted/current scope 下发布；`state.ts:49–63` 每次 command 捕获一个原 OperationOwner，UNKNOWN 只调用显式原结果查询，退休后不 apply。
- Database `DatabasePage.tsx:46–70` 的既有 ID/version+1、非秘密字段与状态通过现行 list GET 核对。改变 password 或 create 丢失新 ID 时公开 DTO 不足以证明原写入，仍可只读检查但不能 settle/重写。保留原密码空白，不把公开 `passwordConfigured` 当秘密相等证明。
- Database `:64–69`、Tools `ToolsPage.tsx:32–51` 与 Knowledge 的 accepted 回调必须得到 current 成功读；load/reload 捕获读错返回 undefined/false 时抛入 ACCEPTED_READBACK，不清 dirty/File/password、不追加 PUT/POST，只重读。
- Tools `recovery.ts:15–24` 核对完整清单、原 project/global CAS 的准确版本转换与 desired enabled；无原 lookup 能力才 NONE/BLOCK，不把所有接口一概设不可恢复。
- Runtime 原 start/restart 没有等价命令 by-request 回执。`RuntimePage.tsx:17–29` GET 能显示状态，无法仅凭 ONLINE/generation 推断原命令接受。保留阻断与解释，不虚构 API/key。
- PPT `pptCreation.ts:46–56` 核对原 messages 中 exact key/text/revision/DOCUMENT scope；第一 write 与 accepted 导航分开。`sentAccepted` 刷新条目使用真实 GET 后仅临时允许准确原 `/ppt/id` destination，失败/取消不消费 metadata，finally 撤许可，没有 fake POST OperationOwner。单测明确额外 POST=0，错误 key/body/revision 拒绝、失败 go 仍 BLOCK。
- PPT 存储仅 best-effort sessionStorage 元数据/SHA，不保存完整 File bytes。旧无 SHA 条目明确不能证明重选字节一致；内存保真实 File/ref，换字节/顺序拒绝。不承诺跨刷新恢复原 File 或新增持久化协议。

## 作者验证与非作者发现闭合

实际聚焦命令（frontend cwd）：

```sh
./node_modules/.bin/vitest run src/pages/w2/secondary --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w2-evidence/secondary-final.json
```

实际 exit 0，**42/42 PASS，0 fail/skip**；raw `secondary-final.log/json`。JSON SHA256 `2164e1c31401c1fb7612fd4766c4f43e338f4572e8668678209bf28165d8ea8f`。随后仅移除 RTL ByRoleOptions 不存在的 exact 属性（字符串 name 已精确），受影响两项实际再次执行 **2 PASS/0 fail**，另 18 定义因 `-t` 未选中；raw `secondary-two-p2-final-typed.log/json`。最终 spec SHA256 `da758b8280c5a755dd1da3df7823e46e9ebddbfcaf0bb05ca9dadd22ae66b969`，全部 source/e2e 哈希 `secondary-freeze-hashes.json`。

非作者 B 原样 probe 在 `secondary-review-probe.log/json` 确认两个 P2：Knowledge 成功刷新后 context 仍以旧 archive/version 执行，Home Ctrl 链接吞原生新标签。最小修复为最新 rows 派生选中对象及共享 PageLink。B 原样两 probe + 作者 42 项 **44/44 PASS**，`secondary-independent-final.log/json`（JSON `a62f211aad9062b84944e2de9c5d78d4e6c5111ae7f33fcec4145cdff861aabf`）；不以作者自身测试替代独立结论。

初轮额外 fixture 类型缺口均按真实 DTO 修正：PptMessage 无 role、完整 questions/answer/detail/version；Runtime generation string；Knowledge.options contractVersion/lastActivityAt/version；UsageAggregate currency string、完整 quality verification 计数/generatedAt/cursor。未用 unknown 强转掩盖 DTO。

## C 非作者 A / shared / root 审查

- `workflow/controllerCore.ts:25–38` 每个 view lease 的 reads ticket 同时核 resources identity 与 generation；StrictMode cleanup detach lease，不退休 retained command。`workflow/pageParts.tsx:9–13` 保 owner/guard 一致。列表初挂仅读，没有 effect command。
- `workflowLibraryController.ts:34–61` copy/archive 使用真实 sourceRevision/expectedVersion/requestKey；accepted copy 仅准确 goAccepted permit；失败导航重读/打开原副本，不再次 copy。DesignerHistory `:87–103` keyless archive/restore/stop 只用现行 GET 原 ID 状态核对，读失败保 accepted、迟到 context.apply 拒绝。
- `roles/roleManagementController.ts:166–188` publish 捕获原 File/key/SHA/activations expectedVersion，UNKNOWN 不换文件/校验/新发布；accepted 两个列表读均成功后才清原文件。`:130–145` export 的 URL/timer 归本 view ResourceScope；`RoleManagementPage.tsx:46–57` 持续 critical/recovery 保留在 context 外，关闭只隐藏呈现，不丢未决 File。原 RoleDiagram React/XYFlow 入口保留。
- root `W2RouteBridge.vue:88–126` loader epoch+path/active 防迟到换页，navigation 仍由现有 VueRouter 持有；shared leave `:37–55` 二次核 draftRevision/BLOCK，不把 unknown 当普通 dirty confirm。TaskPort `:20–45` 单一原 store、plain 冻结快照、read epoch/demo scope、raw runtime failure；dispose 仅退本订阅/本 summaries projection，不重建 SSE。
- 非作者发现 root 初版 nested StrictMode 不能证明 React19 首挂 effect replay。最小共享 bridge 增可选 `{strict:true}`，W2 直接 root child StrictMode，旧 canvas 默认调用不变。最终 `w2Bridge.spec.tsx:59` 明确 setups=2/detaches=1/attached=1/write=0，真实 exit retire=1；实际 root `bridge-root-strict.log` **8/8 PASS**。这关闭方法学缺口，不把 unit 当真实 Chromium 资源证据。

## 真实生产浏览器门禁与当前状态

新增 `e2e/w2/playwright.config.ts` 只对**现行生产 dist 的 vite preview**运行，41773 单 worker/系统 Chromium，原 config 不变。dev 首次预检 1 项发现 Vite @vite/client updateStyle 为目的 Vue PageHeader.css 创建 timer，被旧 root 登记；raw 仍保留，没有过滤未知 timer。生产 config 消除 dev HMR，不靠等待/取消/白名单改变门槛。

`product-routes.spec.ts` 实际 `--list` **49 项**：14 生产路由 × 三皮肤 = 42，另 PPT dirty/UNKNOWN、Knowledge 同实例 query/back、三皮肤 Roles 活动 pan 各三轮、Database accepted/readfail 与 Settings dirty 共七项。每路由 default 截图前真实 reload；首样严格零资源之后再真实 browser back/forward。42 default 图及选择/错误代表图以实际产生为准，首次冻结定义时未预判通过；最终49项与58图的实际结果见下节。

`resources.ts` 在初挂记录真实 root 与 callback/target/capture 身份（含 MediaQueryList/keydown/focus，不只 arm 后 gesture）；先于 React page 挂载的 beforeunload 绑定真实 bridge host。RO detached target 不过滤；未知 armed RAF 失败关闭；timer/RAF/RO/listener/capture 都只观察，不主动清他人资源。真实 SPA link 点击→原 root 脱离的第一 MutationObserver 微任务采样，禁止此前任何 move/up/cancel/真实 window blur。原 root 外 test-owned keydown 哨兵精确 callback 在退出后仍存活且真实键盘响应；重复 cycle 不清账本。强引用账本不是 heap/GC/任意设备证明。

生产首次预检还发现 Ant Modal 模块级 `document.documentElement` capture click 安装的 100ms mousePosition timer。`resources.ts:26–40` 锁定本次实际 `antd/es/modal/Modal.js` 完整 SHA256 `882d5d7880d5915ab4fef19b0b7f1dc3e0a5047b6e029b73555dce6d065e1f32`，从真实 dist 唯一完整函数与原注册语句取字节；运行时只在真实 HTML target/click/capture、注册时无 page root、原 callback Function.toString 完全相等时标此模块 owner。只有该精确 callback 包装执行 owner；React delegated/其它 callback 不包装或排除，未知 armed timer 仍失败。`allTimers` 保留 raw timer、完整 callback 与 source/bundle SHA、注册 target/callback/capture/root 身份；严格 `timers=[]` 仅不把这个证明为模块 owner 的 timer 算作 page owner。remove 精确原身份、signal aborted 和 duplicate 注册，once 在注册时捕获；不按 100ms、minified name 或 stack 白名单忽略资源。B 非作者确认方法学及一份 Home 首样 owned 五类均零、raw allTimers=1；该审查不冒充完整浏览器或 GC 证明。

组长执行的第一轮真实生产 49 项，`browser-production-final.json/log`：**37 PASS /12 FAIL /0 skip**。失败逐项为：三皮肤 Designs 运输夹具缺 API 必填 `stopRetryAvailable`，client.ts:1088 拒绝后显示真实读取错误（goal 字段正确）；Database 末尾全局 complementary selector 误计原 App sidebar，trace 中 page context 已关闭；spdb Settings/spdb Roles/github-white Workflows 三项精确截图 hash 未稳定；三皮肤 Roles 活动 pan 的九点候选在原 320px context 中未找到真实可见连续空白路径；tech-blue/github-white Roles 两项在退出首样保留原生 D3 pane mousedown/wheel 两监听，是真实严格门禁失败，不能过滤 detached targets 或推成 heap 结论。

本组仅修运输夹具、scope selector 与截图/真实命中采样：Designer 补完整 draftId/必填布尔；Database GET 明确 503→原已接受 version5 的成功读取，保留读前密码/disabled/阻离开与仅一次 PUT，再验证成功后离开；截图等待实际加载、字体和有限动画后有界采连续精确 SHA，最终无像素容差，保存每次 hash；Roles 通过真实“展开”及 scrollIntoView，从可见 pane 寻找 106 点连续 105/20 路径，实际 mouse 输入、精确 viewport 105/20、marker/capture 都是采样正控。首样清零/禁止后续输入条件没有改。该阶段 spec SHA256 `4c16c0285084b54b70c3035cf528346099e5ee7472a88b2ef106e34398122d99`；fixture `eab47ab702c008b2d9f79f7a36d495d546ee8dba9d0fc5b0445fa6680ac4e864`；resource ledger `51983279d16f852171953b35a2b0ec8174e74bb4858785a597175ad8bc1c2c1f`。实际后续结果单列，不以修订源码代替验证。D3 底层修复由组长持有，本组没有编辑已验收画布或依赖。

组长最小补丁只在 XYPanZoom returned final destroy 中以 [D3 公开 selection.on API](https://d3js.org/d3-zoom#zoom_selection) 移除该 pane 的 `.zoom` 命名监听；内部 update/selection pause 的 lexical destroy 不改，继续保留 live extent，原 RO final disconnect 保留。非作者确认三个 system 入口及 React UMD 内联 selection 变量分别正确，解绑不指向 window/document 或其它 pane；实际 ESM 与 React UMD/CJS lifecycle 2/2 是独立机制证据，不能替代 browser 或取消已开始的原生全局手势。当前画布仍禁用原生手势、自有 Pointer controller 管理活动会话。

第二轮 `browser-production-final2.json` **46 PASS /3 FAIL**，实际 `production-final2/` 的 **42/42** 默认路由首样全部 owned listener/RO/RAF/capture/timer 严格零；before root/beforeunload/MQL 正控、同 document、root 脱离、原哨兵仍存活、零后续 move/up/cancel/window blur 均成立。42 份无写入或意外请求/错误，App 原 SSE 均 opened=`['/api/story-accounting/events']`、closed=`[]`。每份保留 raw Ant module timer=1；完整 callback 等值，真实 bundle SHA `35f116311124b1745b54d5bef5a3297222507dc8c3c7c7c0e1ead930fb94eb45`。58 PNG/58 stability 记录均为两次 buffer 精确 SHA 相等，无容差。

第二轮剩三项 Roles 活动 pan 均真实命中 106 点、105/20 位移、capture=1，却在首样留下八个 Playwright main-world 工具监听。源码 `playwright-core/lib/client/locator.js:58–70/247` 的 `_withElement`→`server/frames.js:647–659` waitForSelector adopt mainContext→`server/dom.js:110–115` ElementHandle `_initializePreview`→`:74–101` main-world new InjectedScript→生成 source 的 `_setupHitTargetInterceptors`；与八条 raw window/capture/stack 完整吻合。仅替换三处 locator.evaluate 后，专项 `roles-final3` 仍 **0 PASS /3 FAIL**，因为 locator.scrollIntoViewIfNeeded 也经相同链产生主世界 ElementHandle；该失败保留。最终限定修为 page.evaluate 原生 DOM scrollIntoView 返回 void、其它测量只返回 plain 坐标，仍在手势前真实 visible/hit-test；没有改 ledger、过滤工具 stack 或清其监听。最终 spec SHA `9bc85ee190d691620bdcd61de6b037907ca4806bd10bb501ef67c6afb9be4d48`；下列专项与全批都对该冻结字节实际执行。

## 最终独立证据核对

组长运行的 `browser-roles-final4.json/log` 专项 **3 PASS /0 FAIL /0 skip**，`roles-final4/` 三皮肤各三轮共九个首样。C 与非作者 B 分别只读核对：每轮 106 点真实命中、viewport 14/12→119/32（屏幕 105/20）、before capture=1，首样五类资源全零、同 document/root ID、原 root 已脱离、原哨兵 callback 活跃，无后续 move/up/cancel/window blur；没有清账本后重新计数。

最终完整命令（frontend cwd，由组长独占生产 preview/Chromium）：

```sh
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium W2_EVIDENCE_DIR=/workspace/react-full-w2-evidence/production-final3 node node_modules/@playwright/test/cli.js test --config e2e/w2/playwright.config.ts --reporter=json --output /workspace/react-full-w2-evidence/production-final3/artifacts
```

`browser-production-final3.json/log` 实际 **49 PASS /0 FAIL /0 skip /0 flaky，exit 0**，124.983 秒。JSON SHA256 `77b5529a08224dd16c786eeb3226aff0c321e2b3b3ed76d2a88bc1d47d9ade06`。C 对终态 raw 独立重算，与仓库 [资源汇总](resource-summary.json)、[完整验证清单](verification.json) 一致：

- **42 个默认路由首样**：真实 before root/beforeunload/MQL 正控、同 document/root ID、root 脱离、原哨兵保留；owned listener/RO/RAF/capture/timer 严格空数组；零晚到清理输入、零隐写/错误/意外请求。每份保留一条完整证明为原 Ant 模块 owner 的 raw timer。每份 App story-accounting SSE 只打开原一个、close=0。
- **9 个 Roles 活动 pan 首样**：三皮肤各三轮，106 点/105+20/capture=1 正控全部成立，同 document/root ID、五类资源严格零，未依赖物理 release 或失焦。完整账本跨轮保留，不累计。
- PPT dirty Stay、UNKNOWN 阻离开，原 body/read-only/同 DOM owner/metadata 不变且 POST=1。新 tab 使用真实 Settings SkinControl 写 localStorage，原 tab 三次 `StorageEvent` 均 `isTrusted=true`，值依次 tech-blue/github-white/spdb，没有伪造事件。Settings 的 8089 草稿通过同实例三皮肤保留，0 PUT，最后明确 discard；Knowledge 查询/back 同步和 Database accepted 503→仅 GET 恢复、一次 PUT 均通过。
- **58 个实际 PNG +58 个 stability 记录**：42 默认图（1440×1000）、14 选中图（1280×900）、PPT UNKNOWN/Settings dirty 各一图（1280×720）。终批每图均恰两次完整 PNG SHA 相等，58/58 raw bytes 与仓库 [截图画廊](screenshots.md) 对应 PNG 完全相同，无像素容差。截图只证明实际所采模拟状态，不能证明清理或后端业务。

终批没有新增场景或删除任何旧断言，第一轮 37/12、第二轮 46/3 和中间工具专项 0/3 原始记录完整保留。所有生产源与测试继续冻结；本轮没有新增窄屏、真实后端/数据库/AI/ZIP 解析或跨刷新 File 持久化验收。资源账本保留强引用，只证明所记录实例资源的即时释放，不能推广为 heap、GC、所有设备或全站去 Vue 已通过。

完整 unit/type/build/tooling/accounting 与 W0 剩余红见组长[最终总报告](README.md)，与本页浏览器数各自持有，不重复累计。截图只证明所截图桌面 mock DTO 状态，不能证明 Java/数据库/付费模型/后台执行。
