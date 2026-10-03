# W5 Designer：React 历史会话页面与独立复核

基线 `5c181fc39e26249650df851fe9a7c8a68cab519d`，原 C `/root/react_ppt_canvas` 续接，既有启动记录为 `gpt-6.1-sol / xhigh`；当前工具不能实时读取平台模型字段。仅 frontend 与本地证据，未安装依赖、运行后端/模型、提交或外发。C 实现 Designer；A 独立复核 C；C 独立复核 B 需求与 Root 共享入口。

## 生产入口与所有权

- `/designer?sessionId=…` 进入真正 [DesignerPage.tsx](../../../../../frontend/src/pages/w5/designer/DesignerPage.tsx)。`historyOnly` 默认 true；原 `designerEntry` 保持，无 session 的旧新建地址仍转 `/requirements/new`。没有新增生产新建 Designer 路由。
- [controller.ts](../../../../../frontend/src/pages/w5/designer/controller.ts) 使用 W1 receipt/navigation/resource 与 W4 `createW4Owner`。独占消息、原 File、草稿/CAS、Session REST/SSE、profile 与 command。无 Vue/Pinia/ElementPlus 业务 delegate；Vue Router 仍唯一持有 history。
- 页面选择详情、关闭上下文、换肤只改变投影；不退休原 command/File owner。已提交的安全 query scope 更换在 layout cleanup 退休旧 owner；UNKNOWN/pending 阻断 query/路由离开。真正 root/route 退出才 forced retire。
- 原 File 实例仅存在 controller 私有有序数组与冻结 command identity，不进入 DTO snapshot/JSON/sessionStorage。存储只保存文字与 session/draft 指针，失败不撤销 accepted 回执；没有跨刷新恢复完整 File 的承诺。
- 已验收 React Mermaid、W3 `RichDocument`/`ReadOnlyCode` 和纯 TS `frozenDesignTimeline` 继续复用；未重写画布或 markdown 安全规则。

## 原行为到 React 的映射

此表是行为/验证落点，不把旧 Vue mock 通过数当真实 React 通过，也不声称每条旧 UI spec 逐字迁入新文件。旧 specs 未删或改；初始生产不可达的展示能力与历史可达合同分开。

| 原入口/断言锚点 | 新生产实现与原合同 | 实际 React/TS 验证落点 |
|---|---|---|
| `DesignerView.spec.ts:128–260` 会话恢复、重开代次、轮询、旧请求失效；`designerEntry.spec.ts` 旧入口 | controller `projectSession/refresh/connect/schedule`：id 与 revision/profile/draft/auto/package 版本单调；events 只提示 REST 失效；显式命令 invalidate 旧 readEpoch，轮询有界重试与退避 | controller 14 项；capabilities 同 session 旧 poll/旧 revision 两项；page 根 StrictMode/安全 query/UNKNOWN query；原 W0 B6/B7 |
| `DesignerView.spec.ts:310–495` Router、中文任务设置、component 与默认单包 | 真实 profile 控件、GET preview、原 CAS PUT；原 Router id/profile version 的 reroute/cancel/confirm；活动用真实 createdAt 算 elapsed，不伪造业务进度；大型任务仅显式启用 | capabilities profile CAS/confirmationReady；page 任务设置关闭不丢 draft；W0 preview/modal 迟到原 public proposal |
| `DesignerView.spec.ts:541–687,1173–1298,1454–1705` 持久讨论/角色、快照、候选、工作包与恢复 | Discussion 使用冻结时间线，保隐藏快照的回答锚点、持久化顺序、连续 system 与校验 disclosure；direct 模式不挂 package rail；原 requirement/package approval/reopen、compiler/decomposition/retry/redesign 端点及版本；候选事实/历史消歧按需展开 | page 时间线/隐藏锚点/direct 模式/资料停止确认；capabilities 原命令与 actor 活动；冻结 timeline 原纯 TS 继续复用 |
| `DesignerView.spec.ts:937–1173` 消息、原生问题、活动 | 实际推荐/单多选/custom 回答，mandatory 没有 reject；聊天 fallback 与原回答入口；持久消息不拼接 raw SSE；活动仅最新片段、安全 Markdown、有界滚动，重连失败保旧片段 | page native 问题、pending composer；W0 B6.1 真实独立 B 挂载后释放 A、no late GET/no B 污染 |
| `DesignerView.spec.ts:793–872,969–1000` multipart/附件 | ordered original File、submissionId、scope、discussion/design revisions；上传/drop、预览/原文件/停止未来使用；UNKNOWN keyed Send 恢复原同 multipart，后来可编辑 composer/extra File 留作未发送 | page 原 File 对象/有序 body 同一；W0 B5.2、B6.3、B7.3；生产 browser 原 metadata/File bytes 另验 |
| `DesignerView.spec.ts:1788–1980` 最终规范、save/conflict/confirm/原 Task | 完整结构化 LoopSpec、GET validation、draft expectedVersion PUT；仅真实 draft/profile conflict code 为确定拒绝，generic409 保 UNKNOWN。确认 receipt 非空 taskId，GET exact draft 与 Task/audit；accepted 后恢复仅 read/nav，不重复 POST，不自动 Start | editor 21、capabilities draft/profile conflict/错误 Task/accepted 读失败；W0 B5.3 false/reject navigation；原 task/draft id/body/CAS 断言 |
| `LoopSpecEditor.spec.ts:16–157` 全结构/上限/原身份 | Stage `workPackageId/stageKind/executionStrategy/artifactPlanId` 无损；criterionIds/MACHINE/JUDGE/BOTH/rubric、13 种原 verifier 与制品/表格/浏览器断言、runtime argv/readiness、limits/model/thinking/sessionPolicy/nextAttemptPromptTemplate；只读 JSON 披露不是 editor 替代 | editor 21：mount 不 normalize、完整 DTO 往返、封闭 type change、制品/runtime、策略/上限真实 controls |
| `DesignerView.vue:1448–1463,1885` direct 最终设计重新讨论 | FINAL_REVIEW 保 direct WP-1 的原 reopen 入口。wire 仍 `expectedDesignRevision`，值使用已批准 `approvedDesignRevision`；不误换 `/redesign`。真实 confirmation 再核原 scope/revisions | page 原 direct case 扩展：STATUS/SSE→GET discussion 更新使旧 confirm 禁用；Stay 后新确认仅一次 reopen，redesign0 |
| 原 analysis/report→design、v1 copy-v2、autoMode 与 StoryBinding | 原报告 identity/证据与转换 POST、accepted 精确 navigation；copy 草稿与 create session 两命令分开；auto server state/error 持续可见；独立 StoryBinding capability GET 与原配置 | capabilities report failed handoff/no repeat、copy 双命令 uncertainty、实际 capability available 才能启用 |
| `DesignerView.spec.ts:687–937` 旧初始 form/quick brief/auto 风险 | 原初始 API、原 prompt/session draft、初始 multipart、auto 风险确认及 StoryBinding 作为 `historyOnly=false` 的明确独立能力夹具保留。旧 quick template、demo 新建 UI 不恢复到当前生产路由 | W0 initial-text/File sending/unknown/dirty 与 capabilities 初始身份。初始能力的正负控不是当前生产新建页面证明 |

## 写入、回执与生命周期边界

`controller.ts:46–110`、`:120–241`、`:253–297` 持有真实 API endpoint/method/body/CAS 与冻结 scope；每段异步返回后复核 lease/context。没有虚构 by-request GET：有原 submissionId 的 context-turn 才可显式同 key/body/File 重试；keyless UNKNOWN 保 BLOCK/原输入。accepted readback DTO/GET 失败保持 ACCEPTED_READBACK，仅重读；导航 false/reject 不消费原身份。known Task 的原 GET/audit 精确核对仅授予原 owner/原目的地临时许可，不包装成虚假 POST。

自动初始读取与 SSE/poll 只 attach 一次实际 view lease。最后 view detach/retire 先使 scope 无效，再关闭流、timer/RAF；旧 callback 不写新投影。`retire(false)` 拒绝普通 dirty/pending/UNKNOWN 时仍保活动 owner 与恢复；route/root 的授权 forced dispose 不重发服务端命令。B7 立即首样在任何 advance/迟到回调之前采样；后投递 late callback 只做负控。

## 作者验证与失败保留

最终作者执行（cwd `frontend`）：

```text
node_modules/.bin/vitest run src/pages/w5/designer src/w0/designer-w0.spec.ts --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w5-evidence/C-author-final-reopen.json
```

实际 exit0，84 PASS / 0 FAIL / 0 SKIP：controller14 + capabilities16 + page12 + editor21 = 63 作者；原 W0 Designer21 独立列，不把原18红遗漏或豁免。最终14文件 manifest `C-designer-freeze-hashes-reopen.json` SHA256 `7e9447d9d5c4403741e7927bb04fafb090c435cbd750ce9bc3ad0cb1a019a07b`。作者 JSON SHA256 `3fb7d59ce61a4142f3505c489cc2374071456d3944c63653d6028c1b96112cb6`。

原 W0 21 fullName/variants 保持，真实 React + memory VueRouter 夹具保 Stay/dirty/两种 unresolved phase、false/reject accepted handoff、独立 B、actual profile preview/旧 proposal、实际 router RAF 退出、Storage 失败。仅 transport mock，不用 disabled emit/私有 Vue mutation。

| 中间证据（外部 raw 保留） | 实际失败/修正 | 口径 |
|---|---|---|
| `C-controller-first.log`；`C-react-first.log` | idle command 初始化调用错误；未注册语义 object key，随后修 correct object/nav key | 实现/测试初稿失败，不能算最终通过 |
| `C-w0-first.log` 57 PASS/4 FAIL | memory Router afterEach 对 aborted navigation 错误 unmount；按真实 failure 不卸载，保持原 Stay/owner 断言 | 夹具错误，不归产品故障 |
| A `A-independent-C-before-v2` / `...limits-before-v2` | initial accepted foreign project/draft 被消费 File；真实 structured controls 可投影 21/301/61 | 两个独立真实 P2，先核 exact receipt 再 consume File、恢复 domain max；原样六 probe PASS |
| `C-direct-reopen-before.json` | 仅选中 direct final 1 条实际 FAIL：缺少原 reopen button；其余11未选择，不是11 skip 豁免 | 原生产可达功能遗漏；受 Root 授权仅恢复此入口/CAS。现原 case 新确认 PASS |
| `C-direct-reopen-after.json` | 测试误用未公开 command spy，首错为 `command does not exist` | 明确夹具错误；没有新增测试 API。后 v2 与最终全84保持 actual API 参数次数 |

原14文件候选 `f3939b05…` 下 A 非作者 90/90（63 + W021 + 原独立6），raw `A-independent-C-final.json` SHA256 `8a1ba9524a500bd561a51f8e1c97f5d93e0b0a59a84119bd45c1b01b83b4bbde`。该证据保留为 direct reopen 修正前候选，不冒称最终新字节。新冻结 A 原样独立90/90、exit0、0skip已通过，`A-independent-C-final-reopen.json` SHA256 `aee7f6d8f79b1e3811891231d81c2a58189dadf383c43756c3c878f631e53793`；14file manifest运行前后全部一致。direct case真实 STATUS/SSE→GET 将讨论1→2使旧确认禁用，新确认唯一 reopen(A,WP-1,2,approved3)，redesign0。A仅静态复核该项 before 源，不冒称其独立跑过此 before RED；C作者 before 实际失败独立列。

## C 非作者复核 B 与 Root

- B `requirements/controller.ts` confirm 的真实 RED：accepted POST revision2/version4/PENDING_START 后，旧 GET version3/PLANNING 把 phase 设 SETTLED；可能重新确认发新 key。外部原样 probe `C-requirement-confirm-before.json` 1 FAIL（SHA256 `c2d9f2154dd4fe3021ba90854660bd78fa4f66fce28c38bda6d3ff1963ccb74a`）。作者现核 GET 与 strict execution 不早于原 receipt，保 ACCEPTED_READBACK/BLOCK；原样 after 1 PASS，恢复 GET 后 POST 总数仍1（SHA256 `e06a33ed3b3a2bef0ba237f3b54e1f7323a8bb348a59b4f4df96a3c58b8540a8`）。C 未改 B 文件。
- B choice/content 的旧 subtree StrictMode 只能证明严格渲染，不能证明 React19 首挂 effect replay；作者已改根 `<StrictMode>{FoundationProvider…}</StrictMode>`，真实 picker setup2/retained1 强断言。C完整非作者102/102、exit0、0skip（B70：controller21/children30/readers10/page6/content3，加原 W0workflow32），raw `C-B-independent-final.json` SHA256 `0bfc9884db6d9acc769cf342deb26918311bb5893a4dd31fa4f5d5a5e8b3dce3`。B最后仅content CursorPage类型夹具补facets，受影响3项独立再验3/3，raw `C-B-content-fixture-final.json` SHA256 `625ab8789c2dc78088dab37aa8604620b9fa497ebadb86da1492fdd050983692`；32文件实际匹配 B最终manifest `f10d77dfb216587fa1dd678e4e24590157d66328b220cde94d589a56918541fc`。fields helper两阶段均真实disabled，retry SENDING禁用/UNKNOWN同body显式恢复；两个child草稿先合法产生再注册父gate，未强行在其它child保护期间造新draft。
- Root `W2RouteBridge.vue:98–166` 只在已 guard 批准的 W5 committed fullPath 更换 mount/retire；old go/back/retain 的 sequence 隔离，theme 不换 owner。public host lifecycle 仍同 frozen interface，实时 ALLOW 与 sticky cleanup failure 保持，不借 private Vue state 或第二 history。
- Root preview 去 `ViewportPortal` 为普通 root 子 SVG，原 previewPath、pointer/ReactFlow node/edge 协议不变，`g translate(x,y) scale(zoom)` 保原坐标。38 个旧 detached portal container delegated element listeners 是严格账本反例；global/RAF/observer 等为0，账本强持有不证明 GC/全局泄漏。没有删他人监听/库 patch/放宽门槛。
- Root CSS 从旧 `workflow.css` 迁出28条 canvas rule 到唯一 `workflow-canvas.css`，逐行与旧字节一致；legacy @import 与 React import 共用同一定义，补原 Vue ancestor 控件呈现与 focus/caption。最终 Chromium 逐页核 unscaled224×118、border1px、实际曲线 fillnone、控件在画布与桌面内；C实际查看最终选中节点/上下文/连接代表图，不由 jsdom 代替视觉。
- Root 首轮非作者聚焦85/85（bridge26/ownership3/routes17/Immediate39），exit0，7源文件前后 hash0变化。raw `C-root-independent.json` SHA256 `db9d113ee9730477ae5f83b893a367cd50f7fd2df0d796e051672ea6ec3a854c`；此轮在追加 CSS import 前，准确保其来源。
- multipart browser parser 按真实 `Content-Disposition name="metadata"` 区分 metadata Blob（实际 filename metadata.json）与 ordered Files，符合 client.ts:942 的现协议。原 File name/hex bytes、metadata/submissionId/body 相等与 POST 次数断言保留，没有按 filename 排除文件/降低次数。
- canvas 路由的真实导航先展开 App 菜单，主内容随之 inert。C 指出这一步与实际 SPA link 之间缺少活动见证，未将静态疑点冒称产品故障。Root 已在展开后、实际 link 前再次强断言原 gesture marker 与 capture1；最终6活动route raw全部保 pan/drag/connect原marker与单个connected capture。原首次退出 exact0、无后续自然输入断言不变；此方法学边界已由实际 Chromium 闭合。
- 最终静态复核的12文件来源另列外部 `C-root-final-static-sources.json`，SHA256 `73144ec1314b7ae0df245b2b8532897dc19ddc9d25356ae76b501fb10c5682b3`；它不替代85项旧来源的测试，也不替代最终 Chromium 结果。

## 浏览器与剩余边界

Root 集中执行最终真实 production dist 的 W5 三皮肤桌面，C不启动服务或重跑浏览器。原候选48的47/1是 metadata Blob 解析夹具错误；后视觉候选41/7中，4条把屏幕缩放后的矩形误当未缩放224×118，3条对尚无边的图调用 `getComputedStyle(null)`。现改真实 CSS 未缩放尺寸与逐实际边检查，原105/20与后续实际连接断言不变；定向9/9 PASS，旧失败全部保留。最终整批另含实际确定性 partial409恢复，49/49 PASS、0 FAIL、0 SKIP、0 FLAKY，exit0；外部 `browser-final.json` SHA256 `a47aa6136090f53bfba0c75f90351982219a0e24a4ba03e9769068e0da762ca7`。

C非作者只读审计外部 `browser-final/`，生成 `C-final-browser-audit.json`（SHA256 `62d368ccd31570ff3dfc94a48d484cf14ca21e29376076cde870c0ab52e9a6c6`），不改资源工具/断言、不注入释放输入、不清他人资源：

- **64/64 首样严格0**：5 route records×3skins×3cycles=45；5公开root shutdown；native Designer SSE route/root2；两画布×pan/drag/connect×route/root=12。每份同document、原root已断开、sentinel原callback仍活动；listeners/RO/RAF/capture/timers全部 `[]`。before至首样没有pointermove/up/cancel、mousemove/up或window blur；61条element blur明确不冒作window blur。12活动首样前均connected capture1；上述6route在展开导航后仍原marker/capture1。
- **坐标与订阅正控**：workflow与requirement各zoom0.5/1/2的6实际矩形差均严格105/20，释放后位置差0/0；撤销/重做与键盘取消仍由原owner处理。Designer三皮肤共9cycles每轮原SSE before1→after0，全局Story订阅没有被关闭；native两路径各连接null cursor→`17`，4真实连接均记录关闭，SSE仅提示权威REST读取。
- **未决与分段保存**：三皮肤各真实reload/back触发2次native beforeunload并dismiss，共6次；同document/原UNKNOWN与四字段、POST1保持，只有之后显式retry才POST2且body/key相同。确定性partial409是图定义1次接受、布局3次仅显式提交；3份布局字符串逐字相等，原requestKey/CAS expectedRevision3/layoutVersion4不变。另图已接受/布局UNKNOWN/最终GET失败分段原身份仍保，accepted只读恢复不重写前段。
- **真实multipart**：Designer显式Send恢复两份metadata与original.txt hex bytes完全相等，submissionId/文字/讨论1/设计0原身份保留；后来composer和later.txt仍可见未发送。此为transport模拟与真实浏览器File上传，不是后端模型处理证明。
- **30张实际PNG**：15default、6selected、3newunknown、3normal-connected、partialaccepted/partialrejected/Designerunknown各1。全部文件字节SHA与相邻两次完整buffer完全一致，无像素容差。C实际看10张最终代表：Designer三skin、未知后续草稿、需求与流程选中详情、四字段UNKNOWN、accepted/definite409恢复及普通连接，未见本scope新视觉阻塞；逐字节检查与人工代表查看分开，[截图目录](screenshots/)由Root归档。

上述结果对应当前96file `source-freeze.json`，SHA256 `dd022083a1df77676e45883cbf1ac89ccbe001c5fdd7234f767494a28cec97ba`，C逐文件核0差异。W2–W4相关回归由Root继续运行，此时未将其称完成。C实现/独审范围当前无未修复阻塞，旧真实反例与夹具失败均保留。

单位测试使用 jsdom/mock transport、真实 React/Ant 与 memory Router；native Chromium/browser 另列。未验证真实模型/后端文件解析/实际Git写入；无服务端持久化能力时不承诺跨刷新 File/UNKNOWN恢复。没有新窄屏验收，没有全 App / 任意设备 / heap GC 无限增长结论。完整 unit/type/build/tooling/accounting及W2–W4相关回归由 Root 集中执行；浏览器是本波有限范围门禁，不代表全站所有路由/动作 E2E 已验收，也不代表 W6/W7 去 Vue 终态门禁已完成。


## 组长最终集成门槛

作者阶段的“待组长”是当时来源记录。最后冻结源码下：W5 428/428、全量2332/2332、111 W0及原41红通过；type/build/tooling/accounting通过。生产W5 49/49、64严格首样与30新PNG，W2–W4回归49/31/29全部通过；W1既定隔离dev夹具41/41。详见 [最终验证](verification.json) 和 [截图](screenshots/README.md)，不以作者或候选结果代替最终证据。旧default全站E2E、真实后端/模型、真实文件解析、设备及GC边界未验证，W6尚未执行。
