# W5 新需求与需求流程迁移、Workflow 独立复核

作者 B：沿用 `/root/react_legacy_canvas`，历史启动记录显式 `gpt-6.1-sol / xhigh`；工具不能实时查询模型字段，本记录不把自述当平台配置。基线 `5c181fc39e26249650df851fe9a7c8a68cab519d`，工作区 `/workspace/opencode-loopper-react-full`。只持有 `frontend/src/pages/w5/requirements/**` 和本报告。路由/桥、中央语义、基础组件、共享画布及集成门禁由组长持有；Workflow 编辑器及专业报告由 A 持有，Designer 由 C 持有。没有安装依赖、修改旧 Vue/Pinia/API/W0 测试、调用真实模型/后端、运行服务器或创建提交。

## 页面、功能与唯一 owner

`index.tsx` 导出真实 React `NewRequirementPage`、`RequirementPage`，实际入口分别 `/requirements/new`、`/requirements/:id`。两页使用 W2PageProps 的唯一导航口、PageChrome、公共 SkinControl 与 W1 语义按钮；没有第二 history，也不调用旧 `useWorkflowCommand` 或 Pinia writer。

- 新需求：项目、流程模板、名称、目标四字段，没有上传。默认模板固定服务端 revision，项目与流程读失败独立呈现/恢复；可选 `legacyDraft` 读取遇 Storage SecurityError 显示提示并继续四字段，不在渲染中写入或声称草稿已跨刷新恢复。项目/模板 picker 只读，有搜索、分页与原错误。只有显式“创建需求”发原 create；SENDING 与 UNKNOWN 都锁四字段，UNKNOWN 提供“重试创建”，已接受导航失败提供“打开已创建的需求”。
- 需求主区：默认真实 React Flow；节点、边、工具、公共资料、候选、发布按选择披露。关键草稿、未决回执、读取错误和恢复入口常显。保留流程名称/目标/项目、图和布局版本、撤销重做、自动布局、自由/人工节点、预设、角色固定版本、连接条件、节点定位、只读/受保护节点以及专业配置。选中 context 与画布处于同一有界桌面行，关闭面板恢复焦点；不将关闭面板冒充取消服务端命令。
- 执行：Confirm 与 Start 分开；CONTINUOUS/SINGLE/UNTIL、目标、检查点确认、公共资料、默认/手选/持久模型、暂停后修改、候选预览/应用/拒绝、取消、提前结束均保原许可。ACTIVE/已尝试节点及祖先保护、旧候选/历史计划只读；没有自动 Start、隐式新 attempt 或以轮询伪造状态。
- NodeRun：历史 attempts、冻结定义和固定输入、正文分页、输入/输出文件与档案下载、累计代码变化、专业报告/来源证据、局限、命令/准备步骤及原生报告、活动/断线详情、阶段报告读取/下载。历史尝试手选后不随新 latest 跳转。人工完成、Stop/Resume/Retry 使用原节点/attempt/版本，STOPPING 不表示已停止；DOCUMENT 人工输出保持原 JSON 输入，不混成上传组件。
- 文档：公共 DOCUMENT 输入保留原生 File、顺序、真实字节哈希、原 metadata/key/CAS；支持历史不完整上传的显式原身份补传、已上传记录选择、文件清单和 parsed text 分页。多个 uploader 独立注册父 guard，即使披露收起也不销毁未决 File/操作。
- 发布与模板：保完整成果来源/分页/预览、普通目录回填检查、Git 提交/远端显式网络检查/推送、持久 FAILED/BLOCKED 原操作恢复、CURRENT/INITIAL 保存模板、当前未保存 graph/layout 冻结快照、转换后的程序规划节点说明/diagnostics 和新模板链接。网络检查、commit/push/writeback/saveTemplate 均只在显式用户动作后执行。

`core.ts:10` 基于 W1 receipt/resource/navigation 与 W4 scoped reader；`createRequirementController` 是主草稿/command/poll owner，子 writer 分别为 node、candidates、finish、publication、template、upload。`RequirementParent.registerChild/canStartWrite` 保唯一写入许可：自己的普通草稿可以提交，其他子草稿及任何 SENDING/UNKNOWN/ACCEPTED_READBACK 阻止兄弟写。对子 owner 的订阅只在 LeaveDecision 变化时推进父投影，避免读取状态互相触发循环。模型目录、只读 picker 和 File/content reader 不能开关主命令 owner。

视图 lease detach 清读取 ticket、timer、AbortController、FileReader 和本实例 beforeunload；实际路由/root 退出才强制 retire 逻辑 scope。普通 retire 必须先通过 guard；pending/unknown 不因主题、context、模型或权限变化被清掉。强制退出保迟到结果不可投影/不可继续下一请求的证据，**不承诺本实现提供跨刷新、跨进程的请求身份持久化**。父/子资源逐个失效与清理，单个异常不跳过其余 owner。

## 原协议与分段回执

API 出口原样复用 `api/workflowRuns.ts`、`workflowDocuments.ts`、`workflowPublication.ts`、`workflowPush.ts`、`workflowWriteback.ts`；纯 TS `graph.ts`、`planSave.ts` 等保持原 DTO/图约束。File 不经过 `captureDto` 或 JSON 序列化，DTO 与原生文件分开持有。每个实际写阶段记录真实 endpoint/method/key/body/versions，绝不用虚拟 `/plan` 加嵌套 payload 代替实际命令身份。

| 阶段 | 实际协议与恢复 |
| --- | --- |
| 新建 | POST `/workflows/requirements` 原四字段及 requestKey/templateRevision。UNKNOWN 用户显式原 key、同 body 引用 POST；accepted 只交接 `/requirements/:原id`，导航 false/cancel/reject 不重 POST。 |
| 图/应用/候选 | PUT `/:id/plan`、POST `/:id/plan/apply`、POST `/:id/candidates/:candidate/apply` 分别冻结原 version/revision/candidateVersion/graph/key。接受后存 graphReceipt，后续不重发已接受图。 |
| 布局 | PUT `/:id/layout` 独立原 key，以 graphReceipt.revision 与原 layoutVersion 为 CAS。布局超时/拒绝保持该分段身份和已接受 graph；UI 同时说明两个阶段，恢复不重发 graph。最后 GET 失败保两个 receipt，只读取。 |
| Confirm / Start / Pause | POST `/:id/confirm` 与 `/:id/control/start|pause` 分离。Start 冻结 mode/target/input/model/checkpointAttempts/expectedVersion/expectedControlVersion。Confirm 读回的 plan 与 execution 不能早于原 receipt，也不能以旧 PLANNING 投影结清已接受确认。 |
| 人工/进程 | 人工完成真实 `/:id/nodes/:node/human/complete`；命令 Stop/Resume 保节点/attempt 与 commandVersion，模型进程使用 modelVersion。不存在或终态证明来自实际原 attempt GET，不能新建尝试替代停止。 |
| Finish / 候选拒绝 | 原 key、requirementVersion/candidateVersion、目标/原因。accepted 只实际 finish/candidate/parent REST。特定已证实的 prewrite conflict 可保普通 draft 纠正；通用 409 不解锁。 |
| 提交/推送/回填 | 原 revision/node/attempt/output、previewSha256、消息/remote/sourceHash/CAS。已接受只原 status/REST；无 key 的旧 retry 不能盲重 POST，只以当前公开读取核对进展；证明仍不充分则 UNKNOWN/BLOCK。 |
| 文档上传 | 原 native Files/order/bytes/SHA + requestKey/CAS。UNKNOWN 原 key/files 显式重试；历史 partial 仅原记录和完整匹配重选文件可补传。正常后端 upload POST 返回 ready:true；单测中的 accepted ready:false 是防御性 DTO 分支，恢复只用既有 known upload id GET，**不是已复现真实 POST 返回 false**。 |
| 保存模板 | 冻结当前未保存 graph/layout；CURRENT/INITIAL 经真实 preview，save 保 previewSHA/revision/key/title/description。已接受不再 POST；已证 prewrite preview changed/version conflict 要重新 preview，其他 409 保 UNKNOWN。 |

`workflowDocuments.text` 的正式返回仅 `{text,nextOffset}`。因此 parsed text 只核原 selected upload/path/request cursor/ticket 和合法分页，不虚构返回 path/offset/sha256。另一个 attempt fileText/inputContent 出口实际含这些固定身份字段，按自身协议完整核对。所有分页错误保原 cursor/已接受前缀；不把一个 DTO 的字段移植到另一个端点。

REST 为权威：同 id 的旧 revision/version/controlVersion 不降级；执行计划 mismatch 保草稿并提示；dirty/File 不在无确认的 refresh 下丢失。原 2.5s poll 用实例资源拥有权，隐藏页面/未决子 writer 只等待读，不写入。每次 await 后复核 lease/ticket/原 scope，退休后不再发后续 layout、execution 或其他读请求。

## 原 Vue 断言到 React 的映射

下表列全部迁移能力所属旧测试族与新生产路径/实际证据。旧测试仍保留；70 个新定义并非把所有旧定义逐行复制为 70 个独立 UI 用例。协议组合用真实纯 TS owner 测试，关键操作与原 W0 使用实际 React DOM/唯一 Router；专业报告由 A 提供，B 持读取 scope。浏览器像素、Pointer 和严格资源由组长另验，不能以 jsdom 冒称通过。

| 原能力/测试锚点（均在 frontend/src） | 新生产路径 | 新实际测试锚点 |
| --- | --- | --- |
| `views/WorkflowRequirementNewView.spec.ts:18,26,29,36,45,57,63,64` 默认/自定义模板、legacy draft、项目、读失败、原创建/dirty | newController/NewRequirementPage/Choice | `controller.spec.ts:15–19`；`pages.spec.tsx:44,55`；`content-pages.spec.tsx:26`；W0 B1.1/B1.3/B1.4 实际 Router 四字段/导航负控 |
| `WorkflowRequirementView.spec.ts:54,62,85,89,92` 本地预设、Confirm 不 Start、草稿/版本和暂停保护 | controller/RequirementPage + A 编辑组件 | `controller.spec.ts:23–29,34–35` 本地 undo、分段、版本、合法连接条件；`pages.spec.tsx:16,30,71` 实际 mount/context/dirty |
| 同旧 view `66,74,80,161,169,180,191` 检查点、mode/目标、读失败、默认/手选/持久模型/晚回 | controller.run/initializeModel/loadModels | `controller.spec.ts:30–33` 原 control id/version/model、Start 接受后 GET 恢复；W0 B2.1 六动作原 SENDING/UNKNOWN 组合 |
| 同旧 view `101,110,116,123` 候选预览/历史/cleanup、冻结值/只读 | Candidates/PlanDiff/Inputs/NodeRun | `children.spec.ts:25,52`、`controller.spec.ts:28–29`；candidate Apply 显式 graph/layout receipt；共享 A readonly NodeEditor 断言 |
| 同旧 view `42,131,144,153,201` snapshot、子草稿、UNKNOWN/Esc、多个上传保持挂载 | SaveTemplate、RequirementContextPanel、父 child Set、Upload | `children.spec.ts:35,46,51`；`readers.spec.ts:17–46`；`pages.spec.tsx:55,71`；W0 B2.1 parent-finish 实际页 guard |
| `views/WorkflowRequirementScope.spec.ts:96,113,127,153,169` 跨 id 晚回/原 scope BLOCK | core + parent/child tickets + protected retained owner | `controller.spec.ts:19,28,36`；`children.spec.ts:41`；`readers.spec.ts:59,63`；`pages.spec.tsx:87`，W0 actual push/replace/back；强制退出与允许导航明确分开 |
| `components/workflow/WorkflowNodeRun.spec.ts:17,25,33,41,48,54,60,66,71,77,81,86,90,95` attempt/history/inputs、Stop/人工/accepted/专业错误 | nodeController/NodeRun + A Report/Evidence | `children.spec.ts:25,37–42` 固定 attempt/body/CAS、commandVersion19、历史/迟到/断线、accepted GET-only；`pages.spec.tsx:30` 实际节点选择 |
| `WorkflowInputContent.spec.ts:15,25,36,46` 原分页/JSON/abort | contentController/FixedInputContent | `readers.spec.ts:54,59,63,67`；`content-pages.spec.tsx:16` 根 StrictMode、按需、完整正文后 JSON、安全原文 |
| `WorkflowDocumentInput.spec.ts:14,20,28,35,43,48,53` CAS/原File/partial/选择/parsed、manual DOCUMENT/多实例 | uploadController/DocumentInput + 原 manual JSON | `readers.spec.ts:17,25,31,36,41,46` native File 实例与 SHA/顺序、abort/no late POST、GET-only、防错域/原 cursor；正常 POST true 与 partial 历史区分 |
| `WorkflowCandidates.spec.ts:13,19` 拒绝 originalkey/late | candidatesController/Candidates | `children.spec.ts:25,52` + actual W0 candidate 双阶段；PlanDiff 保全每个 changes/taskBefore/taskAfter/affectedDownstream 字段 |
| `WorkflowFinish.spec.ts:19,27,34,40,48,53,57` 结束 target/reason/version、原键、持久intent、STOPPING、draft | finishController/Finish | `children.spec.ts:25,35–36,47,51`；`pages.spec.tsx:71` actual dirty confirmation/新 revision；W0 B2.3 POST1 后 GET 恢复 |
| `WorkflowPublication.spec.ts:14,21,27,36,41,47,54` 原来源、失败成果、cursor、晚回、close/blocked | publicationController/Publication + contentController | `children.spec.ts:43–45,49–50`；`readers.spec.ts:59,67`，关闭只读请求 abort，晚回不重开 context |
| `WorkflowPublicationCommit.spec.ts:14,21,29,34,40` + `WorkflowPush.spec.ts:14,22,30,36,42,47` + `WorkflowWriteback.spec.ts:15,21,27,33,39,45` 许可/preview/CAS/网络/原retry/accepted/父guard | publicationController 的 commit/showPush/checkPush/push/checkWriteback/writeback/retry | `children.spec.ts:25,43–45,49–50` 原六写之一 SENDING/UNKNOWN、accepted GET-only、wrong-domain、无key保守read；预览与对应全部 counts/remote/SHA 由实际组件及 A pure DTO Preview 呈现 |
| `WorkflowSaveTemplate.spec.ts:18,25,32,39,45,49` 当前/初始快照、未知、preview冲突、未执行禁INITIAL、close保草稿 | templateController/SaveTemplate | `children.spec.ts:46,48` immutable unsaved snapshot/previewSHA/明确冲突分类；捕获原 graph/layout 后再披露，close 为 local guard |
| `WorkflowWritebackPreview.spec.ts` 与专业 Report/CodeChanges/Document/Knowledge 旧族 | A `pages/w5/workflow/reports/**`，B Content/NodeRun scope | A 专业 54 个定义由 B 非作者原样复验，API读取属于 B readers 10；不把 A 展示测试算 B File/读恢复证据 |

表中连续行号为定位范围说明，不意味着每一行都有独立 test 定义；精确名称和个数见原始 JSON。原高级字段/许可仍在真实生产入口，不用 JSON dump 替代专业编辑、也不以新页面 `data-react-page` 标记替代行为。

## W0 原冻结断言接入

`w0-contract.tsx:42` 导出 `requirementW0Contract(mode,{proof?})`，mode group 为 B1.1/B1.3/B1.4/B2.1/B2.2/B2.3；其余 phase/action/failure/kind/variant 按原 case 指定。receipt/source 为内部原 DTO fixture，不将原 DTO 错当 callback。组长独占旧 `src/w0/workflow-w0.spec.ts` 的委托入口，原 literal fullName、定义数、body/key/File/guard 断言不删改。

- B1.1：六项 SENDING/UNKNOWN × push/replace/back，另普通 dirty Stay 正控，真实单 VueRouter + React route +真实导航 guard，不调用两个 history。
- B1.3：guard-false/cancelled/reject 三项接受后交接，原两红和原绿均保。恢复仅原 receipt 导航，POST 总数1。
- B1.4：同一原 case 中依次断言 SENDING 四字段全禁改、retry disabled=true/count1；UNKNOWN 四字段全禁改、retry disabled=false/count1；最后显式原同 body 引用 retry/count2（helper:77–97）。没有只测 UNKNOWN。
- B2.1：human/candidate/finish/commit/push/writeback 六动作 × 两阶段，另 parent-finish。真实 React 子面板、原版本/消息/原因/来源/hash/key 和父子 guard；不换成另一种动作规避原失败。
- B2.2：真实 Stop 原 attempt/commandVersion19/body 引用；不用 requirementVersion 或 modelVersion 伪替。
- B2.3：真实 Finish POST 接受后 GET 失败，恢复只 real finish GET，不 POST2。

B helper 覆盖26定义（原23红与3原正控），原 workflow 文件仍32定义，余6原正控未由本模块改写。组长 W5-before 两文件实际53=12PASS/41FAIL，候选迁移后两文件53/53PASS；其中 Designer 属 C，不归为 B 作者测试。C 非作者独立组合102=本模块70 + 原 workflow32，不能把102全部称为新增B测试。

## 作者证据与真实发现

最终作者命令（frontend cwd）：

```sh
npx vitest run src/pages/w5/requirements/controller.spec.ts src/pages/w5/requirements/children.spec.ts src/pages/w5/requirements/readers.spec.ts src/pages/w5/requirements/pages.spec.tsx src/pages/w5/requirements/content-pages.spec.tsx --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w5-evidence/B-author-final.json
```

实际 **70/70 PASS，0 skip，exit0**：controller21、children30、readers10、pages6、content-pages3。raw `B-author-final.{json,log}`；JSON SHA256 `bd22c460848645c43a135740080c6ffc6f03966d79f78892859ca40f0f403dac`。随后只补 content picker CursorPage 正式 `facets:{}` 类型字段，独立复跑该3定义仍3PASS/0FAIL/0skip/exit0，raw `B-content-type-fixture-final.{json,log}`，JSON SHA `8ff51c49d6a07c82459c1f916418975c8047d8108bb5f799299e99f4ea1b87fb`；两批不相加为73个唯一定义。

冻结32file清单 `/workspace/react-full-w5-evidence/B-freeze-hashes.json` SHA256 `f10d77dfb216587fa1dd678e4e24590157d66328b220cde94d589a56918541fc`；content spec 最新 SHA `99b0fe8931ec23447ceb2fc6501a4b300ca249e6def8da82494636c7074313c9`。本报告不在32生产/test清单内。

| 发现/历史候选 | 分类与闭合证据 |
| --- | --- |
| Root 真实 Requirement mount 只有标题、无 GET/plan/loading/error | 真实生产缺口：controller 首版缺 `setStart(load)`。现 `controller.ts:181` 唯一首次读入口；`pages.spec.tsx:16` 实际根 StrictMode mount，不手工 load，证明 plan/execution 与 detach资源。 |
| Root 条件 edge 原面板仅删除 | 真实功能遗漏：补合法 declared outcome/null select + immutable local change，protected target/readonly 阻止，`controller.spec.ts:35` 不发 revise。 |
| C 原样 Confirm accepted DTO v4/PENDING_START 被旧 GET/execution v3/PLANNING 结清 | 真实非作者协议红：`C-requirement-confirm-before.json` 1FAIL，原样 after1PASS；作者 `controller.spec.ts:24` 保 accepted/BLOCK，原 GET 追上 receipt 后恢复且 POST1。 |
| Root 真实选中 context below viewport | 真实视觉候选缺口：本目录 CSS 改同一有界 flex 行，选中 panel滚动有界；共享 canvas基础 CSS由组长修。最终像素/viewport证据只由组长正式浏览器填，不以 jsdom 可见代替。 |
| StrictMode 只读 Choice/Content 候选 effect cleanup 退休逻辑 owner | 对应 viewlease策略修正：路由 lifecycle retain 退休，普通 effect 只detach。最终 content3 真 root Strict，picker registerGuard setup2/current1，且无自动 POST。 |
| 多 child 每读状态均发布父 revision 导致循环候选 | 减少为 LeaveDecision 变化订阅，保全部父 guard。早期 `B-page-owners-second` 中断，无最终 JSON，不列PASS；旧自有 Vitest PID457449后来为 STAT Z/PPID1，已按组长许可精确清理，未杀其他测试进程。 |
| 早期 mock/DTO/type 错误 | 错 fixture templateId、write microtask未等待、native originals size、accepted layout state、缺正式 cursor facets 等均为测试夹具，不归业务红；原 JSON 保留。最后 children 两draft案例曾在父gate注册后尝试制造第二draft，被正确拒绝；改真实已存在两普通draft再注册，不解disabled或降低 guard。 |

C 非作者已原样复验 `C-B-independent-final.{json,log}`：102/102PASS，70生产模块 + 原workflow32（JSON SHA `0bfc9884db6d9acc769cf342deb26918311bb5893a4dd31fa4f5d5a5e8b3dce3`）；随后当前 facets字段后的 content3再次3/3PASS，32SHA逐项与最终manifest匹配。C 原样Confirm红绿独立，RootStrict边界与生产浏览器另列。作者自测不替代该非作者证据。

## B 对 A Workflow 的独立复核

范围为 A 原24file清单（`A-freeze-hashes.json` SHA `4dff4d0b3a9a67fac7d9090de7b875177056e94569f6c54273d9a86371844b8d`），读取旧 save/graph/preset/reviewSource、WorkflowEditorView及其测试、实际模板 API/Java 事务实现，然后复验新唯一 editor owner、真实 React Flow/readonly、NodeEditor/PublicInputs/Presets/RolePicker、22专业 Report/Evidence/Preview；不拿 B 自测充独立审查。A 自己新增的6 Designer 独审定义不属于本复验106。

```sh
npx vitest run src/pages/w5/workflow/editorController.spec.ts src/pages/w5/workflow/pages.spec.tsx src/pages/w5/workflow/nodeEditors.spec.tsx src/pages/w5/workflow/reports.spec.tsx --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w5-evidence/B-A-independent.json
```

原冻结版实际 **106/106 PASS，0skip，exit0**，raw `B-A-independent.{json,log}`（JSON SHA `f0b22abc70ee908f90d0c6eabe7f1487ac4c847311aed16f031a12f2e7c2b79d`），24个SHA全部与清单相同。实测包含原 graph/layout独立键和CAS、unknown布局只恢复布局、最后GET失败与错域/旧版本只读、builtin来源冻结、真实 preset bound parent/edge/undo、普通dirty确认、三skins原保存identity、真实rootStrict旧读取scope、角色冻结版本与晚回拒绝、结构参数原字节/未识别配置确认、专业报告schema/源证据/原始日志/受控文档链接。报告直接使用安全 RichDocument/ReadOnlyCode，File/API reader属于调用者；不把纯DTO展示当 API恢复证据。

### P2：图已接受后布局明确拒绝丢分段身份（原样红已修复）

`editorController.ts:142` 原逻辑对 layout明确409/422也清 `saving/graphOperation/layoutOperation/finalOperation`，没有区分此前 graph已接受。旧 `components/workflow/save.ts:34–38` 和旧 Editor:129 保留成功graphReceipt；服务端 `WorkflowTemplates.java:88–95` 布局只修改独立layout CAS。A原部分接受用例只覆盖 timeout，未覆盖 layout 明确拒绝。

经组长确认合同后，B使用临时真实 transport负控：graph修改先收到原 receipt（revision3/version4/layoutVersion4），layout明确409，再用户显式 recover。原样1FAIL；强化同一场景定量1FAIL观察：graphAccepted=true，但原save未保留、recover后reviseCount2/layoutCount2、leave降为CONFIRM_DISCARD。必须保已接受graph及原layout身份，明确partial拒绝，不得把全保存当未发生。A由组长授权持生产最小修复及永久负控；B不修改A源。

原证据 `B-A-partial-layout-before.{json,log}` / `B-A-partial-layout-before-source.json`；量化证据 `B-A-partial-layout-quantified-before.{json,log}` / `B-A-partial-layout-quantified-before-observation.json`。临时源码外部归档 `B-A-partial-layout.probe.spec.ts.txt`（SHA `3ae9c9e34416d97b90410e954cd253798d57a9cd7c94be754b5dd433c581e5da`）与增强同场景 `B-A-partial-layout-quantified.probe.spec.ts.txt`。工程临时文件已删除，不进入最终单测数量。增强probe原样源码 SHA `efad8d119c78060c7d3147325ebce11e64ca9ddbb37aa293e1a4aa65348c6055`，修后1/1PASS、0skip、exit0：原save对象/graphReceipt保留、reviseCount1、layoutCount2，原layout body/key/CAS相等且leave BLOCK。after raw `B-A-partial-layout-after.json` SHA `e6a745b77ec656ff31cf25809d42617f4eec03ea0a77f0927c68a102781cdebf` 与 `B-A-partial-layout-after-observation.json`。A永久 `editorController.spec.ts:27` 两种409/422和`pages.spec.tsx:41`真实三skin按钮/字段/copy/导航恢复补齐，总109定义。末轮明确拒绝fallback中文校正与英文错误负控也纳入终态；不将明确拒绝说成回执未知。最终24file manifest `A-freeze-hashes-partial-v2.json` SHA `70d1f7e471f587dabe90d9a912cd8670ba73cfd25562e55814cf4a1e8857f23e`，B实际原4spec **109/109PASS、0skip、exit0**（controller15、page9、node31、report54），raw `B-A-independent-final.{json,log}`；JSON SHA `da7719b8a44d87b5d52b98276f8dd0813158160dd7297a6f9e27dfe16b7eed3d`。before/after逐24文件SHA均与最后manifest匹配，见 `B-A-independent-final-source-before.json` 与 `B-A-independent-final-verification.json`。原106、上一PARTIAL候选109（`B-A-independent-partial-candidate.*`）、量化probe和最终109均保留来源，不相加为唯一定义。此P2已闭合，本次有限A独审范围无剩余已确认业务阻塞；Root最终全局/浏览器门槛仍单独执行。

## 验收边界

本模块没有运行完整 suite/type/build/服务器/浏览器/JAR，均由组长统一最终门禁。组件与 API mock 是确定性实际生产路径测试，不说明真实服务端/模型/Git外部副作用成功。StrictMode单测/即时计数不能证明堆 GC 或浏览器所有监听；FileReader/AbortSignal与scope证明只针对本实例实际拥有的资源。跨刷新 File原实例不承诺保存，无公开GETreceipt的端点不发明恢复读。

原窄屏断言本轮 scope外留存，桌面/混合资源及三skins合同保留；OUT_OF_SCOPE不能掩盖最终执行 Vue/Pinia/fixture退出门槛。当前全站尚未W6退Vue，本模块纯TSowner与React入口已独立，不声称整个仓库零Vue完成。最终像素/Strict第一快照、原W0合并数量、related回归与构建请引用组长冻结后结果。


## 组长最终集成门槛

作者阶段的“待组长”是当时来源记录。最后冻结源码下：W5 428/428、全量2332/2332、111 W0及原41红通过；type/build/tooling/accounting通过。生产W5 49/49、64严格首样与30新PNG，W2–W4回归49/31/29全部通过；W1既定隔离dev夹具41/41。详见 [最终验证](verification.json) 和 [截图](screenshots/README.md)，不以作者或候选结果代替最终证据。旧default全站E2E、真实后端/模型、真实文件解析、设备及GC边界未验证，W6尚未执行。
