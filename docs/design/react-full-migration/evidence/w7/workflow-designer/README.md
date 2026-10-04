# W7 Workflow / Requirements / Designer 独立验证

状态：release 冻结完整旧桌面 111/111 PASS、0 skip/flaky/retry/errors、exit 0；817 文件前后均匹配 `d1a38dd…`。W5 生产 49/49 也已在同冻结完整通过；本组共160项桌面有限范围门禁，不冒全站验收。首个冻结的 110 PASS / 1 截图阶段整项超时 FAIL 及原 trace 独立保留，不跨冻结合并通过。全量单测收尾暴露的节点测试夹具已以完整 76/76 及 A 非作者 94/94 复核。基线 `1e9161e06c01b5aa76b6a2b19789ac8379423195`。C 沿用原团队任务；历史启动记录明确为 `gpt-6.1-sol / xhigh`，本轮工具未暴露可实时读取的模型配置字段。本轮不新增成员、不安装依赖、不另开共享开发服务，不执行真实模型、Git 发布或后端操作。

## 文件及数量

C 持有 38 份 `frontend/e2e/workflow-*.spec.ts`、`designer-discussion.spec.ts` 和 `w5/production-routes.spec.ts`。TypeScript AST 只解析源码中的显式数组循环，没有导入或执行测试定义：旧 Workflow 106 项、旧 Designer 9 项、W5 已有生产矩阵 49 项，共 164 项。实际 Playwright `--list` 已核对旧桌面 111 项 / 39 文件；release 旧桌面 111 项已完整实际通过；W5 生产 49 项按组长独立端口GO在生产dist完整通过。静态数量与诊断未执行项不计 PASS。

完整标题、定义行号、静态展开值、基线 SHA、已知窄屏段见 [初始测试清单](test-inventory.md)。机器原始盘点存于外部 `C/source-inventory-initial.json`；环境日志不提交到仓库。

只有下列四个完整 390px 实例为 `OUT_OF_SCOPE`：`固定代码的累计改动和删除文件 390`、`节点知识原文按需查看与恢复 390`、`固定知识交付及后继输入 390`、`另存流程先预览、保留当前步骤及未知回执 390`。其余 160 项保留桌面合同。混合用例中的 390/640px 布局与图片段另列为 `OUT_OF_SCOPE`；同一用例在桌面的写入、读回、权限、File、键盘、主题及清理断言继续保留。保留原标题中的历史“窄屏”措辞以维持定义身份，不据此声称本轮验证窄屏。

## 生产入口与合同对应

| 合同 | 实际 React 入口 / owner | 本轮原有 E2E 证据入口 | 必须保留的谓词 |
| --- | --- | --- | --- |
| 创建和编辑流程、固定节点及角色版本、输入/输出、连接和布局 | `pages/w5/workflow/WorkflowEditorPage.tsx:17`，`editorController.ts:47,98–152`；沿用已验证 React WorkflowCanvas | `workflow-authoring` 8 项；`workflow-canvas-focus` 的流程、列表、默认模型部分；W5 新建/编辑矩阵 | 明确添加/连接/保存才改变 graph；内置只读；循环/自身/重复连接拒绝；固定 role revision、完整 parameters、inputs、outputs 往返不损失；保存 graph 与 layout 为原 CAS 和原两请求键 |
| 选择、键盘、主题及真实指针动作 | 页面 context + 现有 ReactCanvas；`WorkflowEditorPage.tsx:41–66` | `workflow-pointer-contract` 6 项；`workflow-canvas-focus` 12 项；W5 缩放 `.5/1/2`、首帧 `105/20`、撤销/重做、连接、取消 | 真实 pointer capture、首帧屏幕位移、业务写入次数、只读选择、Escape/关闭回焦；主题变化同 owner/同 DOM 不隐式写入 |
| 新建需求四字段、计划确认与显式启动 | `NewRequirementPage` / `newController.ts:28`，`RequirementPage.tsx:23` / `controller.ts:111–178` | `workflow-requirement` 4 项；W5 三皮肤 UNKNOWN 创建 | 真实 title/objective/project/template；confirm 只进入待启动；Start 为独立用户动作；local UI header、固定版本/model/input/checkpoint identity；未知创建阻导航、刷新、back，四字段和原 POST body/key 不变 |
| 候选计划与后续调整 | `Candidates.tsx` / `candidatesController.ts` 与父 `controller.ts:111` | `workflow-plan-review` | 候选只读预览不写；编辑后显式 apply；历史候选只读；CAS、原候选版本、已执行保护节点；apply 不自动 Start |
| 原文文档上传/补传与冻结输入 | `Inputs.tsx` / `uploadController.ts:14–84`，`Content.tsx` / `contentController.ts` | `workflow-upload` 3 项、`workflow-input-reference`、`workflow-document*`、`workflow-knowledge-handoff/evidence` | 实际 multipart、同 File 字节/顺序/原 key/version/revision；UNKNOWN 禁关闭/Escape/离页；完整原记录补传；ready receipt/精确 GET 校验后才消费 File；分页 offset、断线重读、历史内联及冻结输入 |
| 节点执行、人工交付、停止/恢复和结束 | `NodeRun.tsx` / `nodeController.ts:72–90`；`Finish.tsx` / `finishController.ts:19` | `workflow-requirement`、`workflow-command` 3 项、`workflow-finish` 2 项 | 人工结果/检查内容保持原输出；stop/resume/finish 用原版本/key；停止未证明保阻断；未知回执只原操作恢复；依赖失败与命令未启动明确区分；检查点仍需显式勾选 |
| 专业设计、测试、源码/版本/历史报告 | `workflow/reports/*`，`requirements/Content.tsx` 与只读 owner | `workflow-source*`、`workflow-test-*`、`workflow-native-test`、`workflow-review-source`、`workflow-history*`、`workflow-repository`、`workflow-snapshot-*` | 按需读取、完整正文/文件/固定下载、分页/返回缓存；结果/测试范围/执行结果/审查结果分开；未执行、不完整、缺失、损坏结构保真实错误；不得由前端制造 PASS |
| 所选成果提交、推送、目录回填 | `Publication.tsx` / `publicationController.ts:64–113` | `workflow-publication-preview/commit/push`、`workflow-writeback-preview` | 原 source revision/node/attempt/output/hash；只明确确认 POST；原 request key 和 publication/CAS；unknown 原操作，accepted 仅读；真实失败/冲突保恢复；浏览器模拟运输不实际 Git/推送 |
| 另存流程模板 | `SaveTemplate.tsx` / `templateController.ts:8–22` | `workflow-save-template` 桌面项 | CURRENT/INITIAL 原结构与 layout、preview hash、预览不生效、原模式/标题/版本；UNKNOWN 不关面板；显式重试同 body/key；准确新模板 URL |
| graph 已接受/layout 未结清、definitive409、最终 GET 失败 | `workflow/editorController.ts:98–152`；`requirements/controller.ts:111–151` | W5 最后三段原有部分接受/明确布局拒绝用例 | graph 不重写；原 layout body/key/CAS；已接受只 GET；partial rejection 保原 graph receipt 和 BLOCK；明确重试仅原 layout；错误身份/低版本不得 SETTLED |
| Designer 恢复、问题/逐包讨论、总体确认及自动历史流程 | `DesignerPage.tsx:20` / `controller.ts:77,115,191,295` | `designer-discussion` 历史逐包、auto、制品两类型；W5 默认/原生 SSE | 原消息/问题顺序；真 question revision；mandatory permissions；逐包 approval/reopen design CAS；保存/校验/confirm 原 DTO；Task 为 PENDING_START，已授权 auto 的历史任务才沿原协议启动；无新 initial 生产路由 |
| Designer 有序 File、后续未发草稿和原消息恢复 | `designer/controller.ts:128–164`，原生 File 独立保存，不进入 DTO clone | W5 UNKNOWN multipart 既有项；`designer-discussion` 附件错误/刷新历史 | 原 endpoint/body/submissionId/revision/metadata/File bytes/order；显式 Send 恢复原请求；later message/extra File 保未发送；accepted 恢复只读；错误中文与附件历史刷新保留 |
| Designer profile/modal、完整结构化规范、异步作用域 | `DesignerPage` / `controller.ts:169–246`，`LoopSpecEditor` | 历史制品与 W5 生产用例；必要时引用已冻结 W5/W6 相邻真实 RTL/TS 合同作为有限补证 | profile preview、确认版本、旧 modal token；所有 limits/models/verifiers/sessionPolicy/workPackageId 原 DTO 不损失；同 scope 乱序读不得盖新修订；query scope 已经路由守卫批准后旧 owner 立即退休；晚到流/读/写不投影他域 |
| Markdown/Mermaid、静态 SVG、实例资源 | W3 `RichDocument` / `ReadOnlyCode`，真实 React Mermaid；W5 production | `designer-discussion` 三皮肤 Mermaid +三循环退出；W5 49 中 route/root/SSE/活动 pan/drag/connect | 安全真实 SVG/冻结正文，脚本和 foreignObject 禁入；首采样先于任何 natural pointer cleanup；listener/observer/RAF/capture/owned timer 精确空数组；原 document 与其它 app SSE 仍保留；资源账本不作 heap/GC 或全 App 结论 |

## 首轮只读发现

1. `designer-discussion.spec.ts:340–341` 仍写 sessionStorage 后直接进入 `/designer`。W6 已保留无 sessionId 的生产 guard，正确真实入口应带 `?sessionId=designer-e2e`；不得把历史缓存恢复能力当作生产初始入口复活。两制品原业务断言（stage/verifier 全等、先保存再 confirm、PENDING_START）仍需保留。
2. 多份旧用例使用旧 Vue context/class/按钮名称：`fixtures/workflowNavigation.ts:5–30`、Designer 的 `.matrix-criterion-*`/`.designer-session-alert`/`.designer-system-message-history`，以及 `workflow-native-test.spec.ts` 的 `.cm-content`。首轮诊断已在实际 React 路径证实其中过时入口，具体运行和真实产品缺口分列如下；选择器修正不替代后续完整业务断言。
3. 部分旧 HTTP mock 用兜底 `{}`/`[]` 响应所有未识别 endpoint，或在真实 React 读回前未更新 CAS/revision。首失败将据实际 endpoint、DTO 与业务断言分类；不能以空兜底制造已接受事实，不能把运输夹具失败计产品缺陷。
4. W5 的 49 项已是生产页面/真实 React owner 路径；本轮仍需在 W6 当前 Router/生命周期字节下真实重跑。旧 W5 PASS 是历史证据，不算本轮 PASS。技术资源 helper 和公共 bridge/ReactFlow/CSS 修改由组长统一。

## 执行与分类约定

浏览器及生产构建由组长统一到 41773。首轮原断言及 JSON/log/trace 固定到 `/workspace/react-full-w7-evidence/C/`。之后只纠正有证据的过时选择器/路由、运输 DTO 或等待真实稳定帧；不得删除、skip 或放宽桌面业务谓词。真实产品回归只在获准 W5 page/owner 边界做必要修复，由另一原成员独立复核；共享问题立即发组长。

逐项记录 `SOURCE_BUG`、`STALE_SELECTOR_OR_ROUTE`、`FIXTURE`、`FLAKY`、`ENV_BLOCKED`、`UNREPRODUCED`、`NOT_RUN`，附第一失败、后续被遮蔽断言、源码/测试 SHA、精确 before/after 结果。运输 mock 不证明 Java、数据库、真实 Provider/Git；本轮是有限 Workflow/Requirements/Designer 门禁，不能称全站全部动作已验证。


## 已执行诊断与修复来源

所有以下运行均为本组有限桌面范围，mock 仅替换运输，不执行真实模型、Git 或后端副作用。原 literal 标题与桌面行为谓词保留；工具/命令/fixture 错误另留原始输出，不当作产品 RED。

| 外部原始证据前缀（`/workspace/react-full-w7-evidence/C/`） | 实际结果 | 边界 |
| --- | --- | --- |
| `legacy-before` | 45 完成：5 PASS、40 FAIL；1 INTERRUPTED、69 NOT_RUN；exit 130 | 自己测试进程在自然边界中断，服务未停；误用诊断 grep 包含一个 390px OUT 失败，不计当前桌面缺口 |
| `legacy-iteration-1` | 20：9 PASS / 11 FAIL；exit 1 | 小批诊断，不能代替未执行尾段 |
| `legacy-iteration-2` | 42：26 PASS / 16 FAIL；exit 1 | Designer 九项全部 PASS；其余先失败处遮蔽的后续断言仍须最终实际运行 |
| `legacy-remaining-first` | 69：40 PASS / 29 FAIL、0 skip、0 flaky；exit 1 | 19 文件的共同过时呈现、真实 DTO 与四个页面缺口；原始 trace 和首错误完整保留 |
| `remaining-ui-before` → `remaining-ui-after` | 四个真实 RED（其余 22 仅名称过滤未选）→ 三文件 26/26 PASS、exit 0 | 首读 duplicate GET、未推送状态、零记录 undo/redo 和历史候选关闭，均用真实 React 页面/组件 + 实际 owner；非作者复核另列 |
| `legacy-full-candidate` | 全部 111：101 PASS / 10 FAIL，0 skip / flaky，exit 1 | 首次完整桌面实际运行；先失败处后的谓词仍未完成，不能将 101 当作最终来源 |
| `remaining-final-units` | 47/47 PASS，0 pending，exit 0 | children 30 / content 页 6 / Requirement 页 11；包含真实普通推送草稿确认及 UNKNOWN 负定事实负控，原 keyless readback case 补合法 push GET |
| `legacy-final-failures-focused` → `writeback-focused-final` | 原 13 项聚焦为 12 PASS / 1 旧恢复按钮层级 FAIL；该原项定向 1/1 PASS | 完整 111 第二候选仍需重新执行，不把分批相加冒充冻结整批 |

实际产品修复与旧→React 等价恢复：

- Designer 同作用域 `COMPLETED + TASK_START_REQUESTED` 沿原权威 Task GET 核对后交接；保留未发送草稿、UNKNOWN 与准确任务身份，不新增写入。`designer-auto-owner-before-valid` 真 1 RED → 原 controller 17 全绿；A 非作者实际 17/17。
- 当前终止/可恢复交付告警遵守旧 Vue 当前状态与末条消息谓词，历史条目仍保留。`designer-delivery-banner-before` 真 1 RED（12 未选）→ 13/13；A 非作者 13/13 + 原终止历史浏览器候选 1/1。矩阵描述拆段仅呈现，未删 criterion；此前没有走到 bbox 的旧选择器失败不冒充该呈现的产品 RED。
- Workflow 内置只读页面不再显式放开键盘布局移动；方向键/Delete/dirty/0write 负控和原 pan/zoom 均保留，底层未改。原实际 x +24 RED → 页面 9/9，A 非作者 9/9。
- Requirement 恢复原 header 返回需求任务 PageLink；模板预览隐藏并锁定同一个父画布，关闭后返回原实例、原 draft。真实页面三项 RED → Page 全部 9/9；A 关联 controller/Page/Node 31/31。
- NodeRun StrictMode 新 view lease 重读，不让旧 pending GET 把新挂载永久锁在 loading。实际新 lease GET 计数 1 而非 2 的 RED → 永久真实 StrictMode 负控绿；旧 lease reject 不污染新视图。
- 执行中的布局编辑标记 dirty，并恢复原独立“保存布局”入口；原 graph receipt 保留且 0 revise/applyPlan。实际 RUNNING 页面 RED → controller 22 / Page 10 / Node 1 共 33/33；A 原样独立 33/33。UNKNOWN 继续相同 requestKey/body/revision/layoutVersion；accepted GET 落后或失败继续 BLOCK，达到原布局回执才结清。
- 知识证据首挂读使用当前 effect 实例的微任务存活检查，Strict cleanup 阻止旧 launch；显式分页仍逐原 cursor 一次 GET，ticket 继续守护迟到结果。
- 仅成功的空 push 状态 GET 后，COMMITTED 成果显示“尚未推送到远端”；读取失败不生成该事实，不自动 POST。
- undo/redo 动作始终可达，零历史记录明确 disabled；计数、实际布局与撤销/重做协议没有变化。
- 候选预览显式呈现未生效/历史只读状态；统一 `ui.close` target 候选预览。历史只读恢复原 base，无 POST；可编辑预览仍显式确认放弃，UNKNOWN、child/File 草稿和 revision 保护不因此解除。

运输/入口修正保留原后续断言：真实中央 action/accessible name；候选列表与实际层级 details 披露； incomplete 固定正文使用实际 pre，完整正文继续真实 RichDocument/React Lezer；nullable publication 状态显式 GET null；原 graph 保存之后完整 layout PUT + 精确回执/CAS；requirement/execution/control revision 一致；原 File、分页 offset、private identity 不暴露、原请求次数和显式恢复仍逐一验证。无已证后端行为就不能用宽泛空对象/数组伪造已接受。

## C 对 B 最终 UI / PREPARED 增量的非作者复核

C 已独立运行 `knowledge/controller.spec.ts`、`ppt/PptStudioPage.spec.tsx`、`templates/catalog/pages.spec.tsx`，`B-ui-independent-final` 为 42/42 PASS、exit 0。运行来源为 B 原冻结 spec 字节；B 此后两处 test-only 类型/精确字符串 matcher修正单列为静态等价，不把旧运行宣称覆盖新的 spec SHA。runtime 18 文件未变，当前 B 20 文件 manifest 为 `fd3c4d0a323ca9bbbd9eeb33797cdf20fe9327cc3eb7bd8de395cf5fa24c2fe3`。

PREPARED 成功回执维持 accepted-readback，只通过合法原 conversation/qid/version/answers GET 追到 ANSWERED；原 POST 仅一次、错误版本/answers/foreign qid 均 BLOCK。最初 C 聚焦单项与最后独立 42 项分开计，不沿旧候选 hash 冒充最新测试字节。Knowledge 左来源布局仅呈现；PPT 资料/方案只读入口、Escape 插入菜单和 dirty Finish 许可保持 owner；Catalog 真实任务筛选 PageLink、原产出评分解释无隐式 writer。当前所审增量没有已确认未修阻塞。

## 当前交付边界

release 最终旧桌面111及W5生产49均已完整通过，同817/d1a38dd…源码冻结，没有跨revision合并。统一全仓单测/类型/构建结果由组长归档，不记作 C 自己的运行；以上中间诊断 PASS 不冒充该 release 整批。所有资源计数仅属于被审实例/活动窗口，观察器与账本强引用不证明 heap/GC 或全 App 清理。窄屏布局验收仍 OUT_OF_SCOPE；后端、真实 Git、付费模型和任意设备未验证。


五份只读旧测试曾完整交接组长持有后归还：`workflow-native-test`、`workflow-test-review`、`workflow-test-design`、`workflow-document-review`、`workflow-input-reference`。组长定向 `readonly13-candidate2` 为 13/13 PASS；C 非作者静态核 5 文件实际 SHA 与 `root/readonly13-source-hashes.json` 完全一致、原 literal 标题/计数/固定引用/分页 offsets/privacy 谓词保留。深层详情实际展开后检查可见真实 React Lezer textbox，不以 hidden DOM 冒可达；native XML 原字节额外精确检查仍在。C 首次完整 111 为 101/10；该组长定向结果不冒称 C 独立运行。两个后续纯只读文件另交接给组长并归还：handoff 真实 DTO `version=2/requirementId/nodeId/planRevision`，snapshot 先实际展开“源码依据”后再保精确文件路径、24–25 行可见与 `validate(input)`，原 privacy/bodyReads=1 均未改。组长 `readonly4-candidate` 为 4/4 PASS；C 非作者静态核 `root/readonly4-source-hashes.json` 两文件字节匹配。第二次完整 111 候选实际 111/111 PASS、0 skip/flaky/retry、exit 0（5.2m）；40测试/fixture＋六最新delta源在运行前后46个唯一SHA全部不变。原桌面 fullName 多重集 111 条 missing/added 均 0，见外部 `C/legacy-candidate2-audit.json`。


A 非作者已在 `A/C-remaining-ui-independent` 原样独立复跑 26/26 PASS，7 文件 SHA 运行前后匹配。另追加真实 UI 边界负控证实：成功 GET null 后，明确推送单 POST 失联进入 UNKNOWN，历史 `pushLoaded/null` 仍造成“尚未推送到远端”显示。`A/C-publication-empty-read-before` 为 1 真 RED，前置 UNKNOWN/BLOCK/POST1 均成立。111 诊断结束后已按原证据最小修正：负定事实只在非 busy 且 IDLE/SETTLED 的成功空 GET 阶段呈现；UNKNOWN/SENDING/ACCEPTED_READBACK 不沿旧空读宣称未执行。A 原样独立复验最终 48/48 PASS、exit 0：作者现有47＋原 f06fad648 探针1，六SHA前后全匹配；独立JSON SHA `4b3c68bc2a02b1f89cb34a2bc7ce7f0d03d21bc7d773c1da5d9a76ddf1aaa7aa`，verification SHA `688020d2…`。原UNKNOWN真实RED仍独立保存。


首次完整 111 的十个首错已逐项保留：

| 原用例 | 首错误分类及最小闭合 | 保留合同 |
| --- | --- | --- |
| 固定知识交付及后继输入 1600 | FIXTURE：固定 inputs 缺 requirement/node 身份，合法 DTO 补齐后才有正文入口 | 分页读 2 / private 查询 0 / result 1 / 原字节引用 |
| 拒绝重复、循环、自身连接 | STALE_SELECTOR：前置 graph 谓词已通过，尾部旧 count=0 恢复原零历史 disabled | 连接总数 2、0 write、撤销不可用 |
| 执行需求显示拖动 | SOURCE_BUG：本轮新增独立 dirty 行令 canvas Y 额外 +36；提示并入已有计划状态行 | 实际首帧 105px / 20px、只读 Delete / ports / 原 nodes/edges、dirty 和 CONFIRM |
| 本地提交恢复 | SOURCE_BUG：retry 原提交读 COMMITTED 后漏合法 push 状态 GET；恢复真实只读状态读取 | 两次原请求、原 key/body/CAS，GET null 才说明未推送，0 新 push POST |
| 远端明确推送 | 普通确认合同映射＋SOURCE_BUG：空表单没有登记 dirty；登记后 close → Stay 保原表单，不自动放弃 | confirm POST 0 → 原身份 2；BLOCKED 版本恢复 1；最终权威读取 |
| 另存流程 1600 | STALE_SELECTOR：旧 data-workflow-save-state 不存在，定位实际未保存 status | 同父 canvas、2 nodes、原 dirty；UNKNOWN 同 body/key，两次 save |
| 版本代码分析 / 问题复核（两项） | STALE_SELECTOR：先展开真实源码依据层，再检查精准引用可见 | 固定 24–25 行 / validate(input) / private identity 禁露 / 正文读 1 |
| 上传 UNKNOWN 保护 | STALE_SELECTOR：真实 App 导航阻断后仍打开且主区 inert，先真实关闭导航，再从原命令恢复 | 原上传入口 disabled、原重试 enabled；File 与 metadata 相同、POST 2 / start 0 |
| 普通目录回填 | 普通确认合同映射：close → Stay；后续恢复按钮为 panel 顶部共同 CommandNotice | 原检查 3 / confirm 2 / retry 1 / 原版本与 key/body，最终 GET APPLIED |

关闭保护没有删除：普通 draft 使用真实 modal Stay 验证面板和输入仍在、确认写 0；pending/UNKNOWN 仍明确 disabled/BLOCK。上述首错与后续遮蔽首错分别保留原 JSON/log/trace；当前复核不执行真实 Git、模型或 Java。


## 69 项诊断中 29 个首失败逐标题归因

原始来源逐项为外部 `C/legacy-remaining-first.json/log` 和对应 `legacy-remaining-first-results/<case>/trace.zip`；下面只分类当时第一个失败，后续真实产品缺口由其独立 RED/源码和上表另记。29不是产品缺陷数量；未走到的谓词不能推断通过。机器小清单另存 `C/legacy-remaining-first-classification.json`。

| 原 literal 标题 | 首错分类 | 原证据及更正边界 |
| --- | --- | --- |
| 需求代码评审 assessment 在画布按需读取，保留真实意见 | STALE_SELECTOR | `workflow-document-review.spec.ts`：只展开依据与局限，未展开内层源码依据；实际深层 details 披露后原 amount > 0 可见。 |
| 固定正文按需分页、断线重读、历史内联输入与窄屏显示 | STALE_SELECTOR | `workflow-input-reference.spec.ts`：正文尚未读完时实际 pre，原已读取字节及 offsets 未变；完整后再用真实 React Lezer。 |
| 节点知识原文按需查看与恢复 1600 | SOURCE_BUG | `workflow-knowledge-evidence.spec.ts`：Strict 首挂重复读取导致 lists=3，而原要求2；当前 effect lease 微任务检查恢复首挂1＋显式分页1。 |
| 固定知识交付及后继输入 1600 | STALE_SELECTOR + FIXTURE | `workflow-knowledge-handoff.spec.ts`：首错旧正文按钮名称；更正后确见 inputs 缺 requirement/node 身份，合法 DTO 补齐而未改变分页/隐私谓词。 |
| 原生单测 passed | STALE_EXPECTATION | `workflow-native-test.spec.ts`：同义警告少“都”字，仍明确数量不代表覆盖；后续实际报告打开使用统一 ui.open，原 XML 字节可见。 |
| 原生单测 failed | STALE_EXPECTATION | `workflow-native-test.spec.ts`：同义警告少“都”字，仍明确数量不代表覆盖；后续实际报告打开使用统一 ui.open，原 XML 字节可见。 |
| 原生单测 input-changed | STALE_EXPECTATION | `workflow-native-test.spec.ts`：同义警告少“都”字，仍明确数量不代表覆盖；后续实际报告打开使用统一 ui.open，原 XML 字节可见。 |
| 原生单测 final-passed | STALE_EXPECTATION | `workflow-native-test.spec.ts`：同义警告少“都”字，仍明确数量不代表覆盖；后续实际报告打开使用统一 ui.open，原 XML 字节可见。 |
| 原生单测 final-failed | STALE_EXPECTATION | `workflow-native-test.spec.ts`：同义警告少“都”字，仍明确数量不代表覆盖；后续实际报告打开使用统一 ui.open，原 XML 字节可见。 |
| 候选先查看、编辑后确认、历史预览和手动后续调整 | STALE_SELECTOR | `workflow-plan-review.spec.ts`：首错旧 candidate-list；后续真实页面证实历史候选只读关闭和状态遗漏，另记 SOURCE_BUG，不把首错当该产品证据。 |
| 反向端口指针连接只生成一次原方向意图与撤销记录，键盘连接继续可达（模拟数据） | SOURCE_BUG | `workflow-pointer-contract.spec.ts`：零历史 undo 被条件卸载，原 disabled 断言失败；恢复常驻 disabled，原图/坐标/写入计数不变。 |
| 正向端口两次真实点按保持原方向且只提交一次意图（模拟数据） | SOURCE_BUG | `workflow-pointer-contract.spec.ts`：零历史 undo 被条件卸载，原 disabled 断言失败；恢复常驻 disabled，原图/坐标/写入计数不变。 |
| 反向端口两次真实点按保持原方向且只提交一次意图（模拟数据） | SOURCE_BUG | `workflow-pointer-contract.spec.ts`：零历史 undo 被条件卸载，原 disabled 断言失败；恢复常驻 disabled，原图/坐标/写入计数不变。 |
| 端口点按与原加号连接模式互斥，不保留第二个连接意图（模拟数据） | SOURCE_BUG | `workflow-pointer-contract.spec.ts`：零历史 undo 被条件卸载，原 disabled 断言失败；恢复常驻 disabled，原图/坐标/写入计数不变。 |
| 确认固定成果后恢复同一本地提交，并在重新打开画布后保留记录 | SOURCE_BUG + FIXTURE | `workflow-publication-commit.spec.ts`：真实成功空 GET 后无未推送状态（相邻真实 RTL RED）；nullable GET 旧[]夹具另修。随后 retrycommit 漏 push GET另记真实首错。 |
| 用户核对远端后明确推送，未知回执重试原请求并在刷新后恢复记录 | SOURCE_BUG + FIXTURE | `workflow-publication-push.spec.ts`：同成功空 GET 状态遗漏；明确提供 GET null，绝不以undefined制造否定事实；普通关闭后续分列。 |
| 切换版本审查范围同步公共日期并保存配置 | FIXTURE | `workflow-review-source.spec.ts`：graph已接受后 layout-only PUT/GET mock不完整而 accepted-readback阻新保存；补真实两段DTO/CAS，保原两个graph body。 |
| 另存流程先预览、保留当前步骤及未知回执 1600 | STALE_SELECTOR | `workflow-save-template.spec.ts`：旧返回任务画布名称退役，真实 nav.back 返回仍在UNKNOWN disabled；原save两次同body/新模板href不变。 |
| 版本代码分析交付按需展开且窄屏可读 | STALE_SELECTOR | `workflow-snapshot-analysis.spec.ts`：引用行已在内层实际源码依据详情中；展开真实summary后再查exact路径24–25行/validate(input)。 |
| 版本问题复核交付按需展开且窄屏可读 | STALE_SELECTOR | `workflow-snapshot-analysis.spec.ts`：引用行已在内层实际源码依据详情中；展开真实summary后再查exact路径24–25行/validate(input)。 |
| 取消后按需读取阶段报告、刷新下载及桌面窄屏 | FIXTURE + STALE_SELECTOR | `workflow-snapshot-partial-report.spec.ts`：requirement/执行/control revision相互矛盾导致原域保护不展示入口；补一致真实revision，实际阶段报告region/refresh。 |
| 源码分批先查看批次再确认且不自动启动 | STALE_SELECTOR | `workflow-source-plan.spec.ts`：首错旧candidate-list；随后完善layout权威读回夹具，原apply次数/graph结构及start=0不变。 |
| 单测场景步骤期望和源码依据可查看 | STALE_SELECTOR | `workflow-test-design.spec.ts`：旧深层源码依据点击/错误提示region；合法详情真实展开，malformed原告警在实际NodeRun，仍不能认定测试通过。 |
| 单测场景损坏格式明确提示 | STALE_SELECTOR | `workflow-test-design.spec.ts`：旧深层源码依据点击/错误提示region；合法详情真实展开，malformed原告警在实际NodeRun，仍不能认定测试通过。 |
| 场景复核展示独立意见和固定断言 | STALE_EXPECTATION | `workflow-test-review.spec.ts`：同义当前警告仍区分独立评审与程序执行/版本；原逐场景断言、跳过、缺失、privacy不变。 |
| 场景复核保留未执行及缺失 | STALE_EXPECTATION | `workflow-test-review.spec.ts`：同义当前警告仍区分独立评审与程序执行/版本；原逐场景断言、跳过、缺失、privacy不变。 |
| 上传固定原文、预览并将其绑定到执行请求 | STALE_SELECTOR_OR_LABEL | `workflow-upload.spec.ts`：实际公共资料冻结说明及统一需求任务App导航名称；上传字节、原POST身份、离页阻断后续均保。 |
| 上传回执未知时拒绝关闭、Escape与离页，并按原身份重试 | STALE_SELECTOR_OR_LABEL | `workflow-upload.spec.ts`：实际公共资料冻结说明及统一需求任务App导航名称；上传字节、原POST身份、离页阻断后续均保。 |
| 普通目录成果先检查冲突，明确确认后支持未知回执、阻断恢复和刷新完成 | STALE_SELECTOR | `workflow-writeback-preview.spec.ts`：首错旧收起代码成果名称；之后普通dirty close→明确Stay，UNKNOWN共享CommandNotice原重试，原回填CAS及次数不变。 |

首个冻结为 `final/source-freeze.json`（817文件，SHA `a8821a377da1577b8b3a94de2c1f1f17e056cb66c4a1f91509c96b27c7efd8b8`）。其完整浏览器原始输出保留，不冒最终绿；随后组长因全量单测和 PPT 真回归明确授权最小收尾，需新冻结、干净安装并完整重跑。111 候选与首个最终尝试不混为同一次通过。


## 最初中断诊断的 40 个首失败

来源 `C/legacy-before.json/log/results`：45已完成=5PASS＋40FAIL，随后1INTERRUPTED、69NOT_RUN；未执行不按失败或通过计。一个390整项已标OUT_OF_SCOPE，不掩盖原错误，也不纳入最终桌面通过。下面按每个原 literal title列当时首错；后续实际bbox/当前terminal/只读builtin等产品RED另有专门来源，不将所有旧控件失败都归产品。机器清单 `C/legacy-before-first-failures.json`。

| 原 literal 标题 | 首错分类 | 证据边界 |
| --- | --- | --- |
| 历史设计继续需求提问与逐包讨论，再确认为 PENDING_START 任务 | STALE_SELECTOR_OR_LABEL | `designer-discussion.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| 附件设计投递失败时展示具体原因并在刷新后保留附件历史 | STALE_SELECTOR_OR_LABEL | `designer-discussion.spec.ts`：首错旧designer-session-alert；后续当前告警真实P2另取永久RTL RED，不把此旧selector首错冒作产品证据。 |
| 已开启全自动的历史设计继续原冻结流程进入已启动任务 | SOURCE_BUG | `designer-discussion.spec.ts`：COMPLETED/TASK_START_REQUESTED 缺真实既有任务只读交接；精确Task GET身份确认后导航，无隐式写。 |
| document 制品确认保留断言与冻结执行身份 | STALE_ROUTE_OR_FIXTURE | `designer-discussion.spec.ts`：旧无sessionId /designer已合法redirect；fixture改真实历史?sessionId入口，原制品结构/先save再confirm/冻结任务断言保持。 |
| table 制品确认保留断言与冻结执行身份 | STALE_ROUTE_OR_FIXTURE | `designer-discussion.spec.ts`：旧无sessionId /designer已合法redirect；fixture改真实历史?sessionId入口，原制品结构/先save再confirm/冻结任务断言保持。 |
| 历史Designer静态Mermaid三次真实SPA退出清理SVG渲染残留与观察器（模拟数据） | STALE_SELECTOR_OR_LABEL | `designer-discussion.spec.ts`：首错旧继续按钮，历史页面用真实后续发送/读取入口；原静态SVG三轮SPA首样清理仍严格。 |
| 创建、连接、拖动、保存并重新打开流程；三种皮肤和窄屏可用 | STALE_SELECTOR_OR_LABEL | `workflow-authoring.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| 内置流程允许点击查看节点，但不能拖动修改 | STALE_SELECTOR_OR_LABEL | `workflow-authoring.spec.ts`：首错旧详情/label层级；真实选中上下文与节点任务控件定位，原disabled/草稿/主题状态不变。 |
| 预设选择固定角色版本，绑定上游后添加节点并保存为可编辑流程 | STALE_SELECTOR_OR_LABEL | `workflow-authoring.spec.ts`：首错旧输入来源label；原结构化输入由真实当前控件绑定，role版本/来源/output/parameters图DTO断言保留。 |
| 程序检查绑定固定代码，保存检查内容并重开，支持窄屏配置 | STALE_SELECTOR_OR_LABEL | `workflow-authoring.spec.ts`：首错旧输入来源label；原结构化输入由真实当前控件绑定，role版本/来源/output/parameters图DTO断言保留。 |
| 命令预设支持独立参数、固定代码输入、保存重开及窄屏配置 | STALE_SELECTOR_OR_LABEL | `workflow-authoring.spec.ts`：首错旧输入来源label；原结构化输入由真实当前控件绑定，role版本/来源/output/parameters图DTO断言保留。 |
| 冻结源码预设绑定路径，保存采集用途并保留完整采集规则 | STALE_SELECTOR_OR_LABEL | `workflow-authoring.spec.ts`：首错旧输入来源label；原结构化输入由真实当前控件绑定，role版本/来源/output/parameters图DTO断言保留。 |
| 专业编写、复核和文档汇总绑定同版源码，自定义规则保存重开 | STALE_SELECTOR_OR_LABEL | `workflow-authoring.spec.ts`：首错旧输入来源label；原结构化输入由真实当前控件绑定，role版本/来源/output/parameters图DTO断言保留。 |
| spdb 流程画布默认留白，选择、切换、连线和取消形成完整循环 | SOURCE_BUG | `workflow-canvas-focus.spec.ts`：实际canvas高732低于原750门槛；只修W5页面CSS40px，不改原bbox断言或Flow底层。 |
| spdb 需求规划与执行均按选择展示详情，资料与辅助操作仍可达 | STALE_SELECTOR_OR_LABEL | `workflow-canvas-focus.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| tech-blue 流程画布默认留白，选择、切换、连线和取消形成完整循环 | SOURCE_BUG | `workflow-canvas-focus.spec.ts`：实际canvas高732低于原750门槛；只修W5页面CSS40px，不改原bbox断言或Flow底层。 |
| tech-blue 需求规划与执行均按选择展示详情，资料与辅助操作仍可达 | STALE_SELECTOR_OR_LABEL | `workflow-canvas-focus.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| github-white 流程画布默认留白，选择、切换、连线和取消形成完整循环 | SOURCE_BUG | `workflow-canvas-focus.spec.ts`：实际canvas高732低于原750门槛；只修W5页面CSS40px，不改原bbox断言或Flow底层。 |
| github-white 需求规划与执行均按选择展示详情，资料与辅助操作仍可达 | STALE_SELECTOR_OR_LABEL | `workflow-canvas-focus.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| spdb 流程库、需求列表和新建需求的入口与视觉一致 | STALE_SELECTOR_OR_LABEL | `workflow-canvas-focus.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| tech-blue 流程库、需求列表和新建需求的入口与视觉一致 | STALE_SELECTOR_OR_LABEL | `workflow-canvas-focus.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| github-white 流程库、需求列表和新建需求的入口与视觉一致 | STALE_SELECTOR_OR_LABEL | `workflow-canvas-focus.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| 跨标签换肤不丢失选中草稿，刷新恢复皮肤，窄屏面板可以关闭 | STALE_SELECTOR_OR_LABEL | `workflow-canvas-focus.spec.ts`：首错旧详情/label层级；真实选中上下文与节点任务控件定位，原disabled/草稿/主题状态不变。 |
| 导航键盘闭环、搜索画布外节点与高级设置保持可达且不造成布局修改 | STALE_SELECTOR_OR_LABEL | `workflow-canvas-focus.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| 固定代码的累计改动和删除文件 1600 | STALE_SELECTOR_OR_LABEL | `workflow-code-changes.spec.ts`：首错共用workflowNavigation旧更多工具名称；统一中央语义打开真实菜单，无API/owner替代。 |
| 固定代码的累计改动和删除文件 390 | OUT_OF_SCOPE | `workflow-code-changes.spec.ts`：首诊误用grep带入一个整项390；原失败保留，当前桌面最终矩阵明确不注册此项。 |
| 原命令恢复后可查看失败报告和执行记录，按需读取且不显示模型操作 | STALE_SELECTOR_OR_LABEL | `workflow-command.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| 命令停止回执未知时重放原操作，收到回执后保持等待停止确认 | STALE_SELECTOR_OR_LABEL | `workflow-command.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| 依赖准备失败单独显示，检查命令未启动且输出可展开 | STALE_SELECTOR_OR_LABEL | `workflow-command.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| 内置默认流程显示八个模块，新需求读取默认流程版本 | STALE_SELECTOR_OR_LABEL | `workflow-default.spec.ts`：首错旧名称、控件角色或层级；真实 React 入口定位后保留原后续业务谓词。 |
| 验收报告显示同批通过且窄屏可读 | STALE_SELECTOR_OR_LABEL | `workflow-default.spec.ts`：首错共用workflowNavigation旧更多工具名称；统一中央语义打开真实菜单，无API/owner替代。 |
| 验收报告保留阻断意见且窄屏可读 | STALE_SELECTOR_OR_LABEL | `workflow-default.spec.ts`：首错共用workflowNavigation旧更多工具名称；统一中央语义打开真实菜单，无API/owner替代。 |
| 完整原文流程 plan 结果可读且不虚构执行事实 | STALE_SELECTOR_OR_LABEL | `workflow-document-combination.spec.ts`：首错共用workflowNavigation旧更多工具名称；统一中央语义打开真实菜单，无API/owner替代。 |
| 完整原文流程 reviewed 结果可读且不虚构执行事实 | STALE_SELECTOR_OR_LABEL | `workflow-document-combination.spec.ts`：首错共用workflowNavigation旧更多工具名称；统一中央语义打开真实菜单，无API/owner替代。 |
| 完整原文流程 unreviewed 结果可读且不虚构执行事实 | STALE_SELECTOR_OR_LABEL | `workflow-document-combination.spec.ts`：首错共用workflowNavigation旧更多工具名称；统一中央语义打开真实菜单，无API/owner替代。 |
| 完整原文流程 local-review 结果可读且不虚构执行事实 | STALE_SELECTOR_OR_LABEL | `workflow-document-combination.spec.ts`：首错共用workflowNavigation旧更多工具名称；统一中央语义打开真实菜单，无API/owner替代。 |
| 需求代码评审 assessment 在画布按需读取，保留真实意见 | STALE_SELECTOR_OR_LABEL | `workflow-document-review.spec.ts`：首错共用workflowNavigation旧更多工具名称；统一中央语义打开真实菜单，无API/owner替代。 |
| 需求代码评审 pass 在画布按需读取，保留真实意见 | STALE_SELECTOR_OR_LABEL | `workflow-document-review.spec.ts`：首错共用workflowNavigation旧更多工具名称；统一中央语义打开真实菜单，无API/owner替代。 |
| 需求代码评审 revise 在画布按需读取，保留真实意见 | STALE_SELECTOR_OR_LABEL | `workflow-document-review.spec.ts`：首错共用workflowNavigation旧更多工具名称；统一中央语义打开真实菜单，无API/owner替代。 |
| 文档汇总 reviewed 展示真实状态与固定下载 | STALE_SELECTOR_OR_LABEL | `workflow-document.spec.ts`：首错共用workflowNavigation旧更多工具名称；统一中央语义打开真实菜单，无API/owner替代。 |

干净安装首批旧111已完整结束，`final/legacy/C.*` 为 110 PASS / 1 FAIL，0 skip / flaky / retry，exit 1；前后 utility verify 均严格匹配817/a8821a…。W5 生产49暂未启动，等待组长新冻结 GO。


## 首个干净安装完整尝试与单测夹具收尾

`final/legacy/C.json/log/output` 实际完整执行全部111：110 PASS、1 FAIL、0 skip/flaky/retry，exit1，13.7分钟。机器审计 `final/legacy/C-audit.json` 对初始桌面标题多重集核对 missing0/added0；原 JSON SHA `331a5f7619d588fefdbd4bb01e73e9db81dcb1b693ba31ec95625734a01a25e7`，log SHA `2e07506e9971db2071a6abc1a4b6cc593a7d1d6a0eec89a106af5266b2d967c2`。前后817源码均为 `a8821a…`，不能把该批称全PASS。

唯一失败原标题为“专业编写、复核和文档汇总绑定同版源码，自定义规则保存重开”。trace表明图参数、输入/角色、重开、DELIVERABLES、最后NONE和完整说明visible均已通过；末尾截图遭整项30000ms期限终止。证据 `final/legacy/C-timeout-17-analysis.json` 与原trace SHA `f8dcc82f39dd4ae6772e74eb424960a813dac7579f4e97afd7932a177a5bcbfb`保留。原诊断归 `ENV_TIMING_CANDIDATE`；新 release 冻结完整111实跑后，该原项在7657ms通过，确认本次有限范围内未复现该截图期限波动。未修改测试期限、资源或业务谓词，旧失败及trace不删除。

全量单测的两个旧 `WorkflowRequirementView.spec.ts` node首错是读取未就绪时触发控件，真实页面仍disabled。仅测试补 exact metadata `run`、readable=true、loading=false及DOM原生`:disabled=false`前置；不强制操作disabled控件。原UNKNOWN重试继续断言aria-busy=false及Ant loading class=false，轮询先flush真实React提交。原dirty拒导航夹具把 `act` 保持至用户确认Promise结束，导致整文件overlapping act；按明确授权改为短act只发起同一真实 `application.navigation.go('/requirements')`，随后真实Stay，再await原Promise，原路径与草稿谓词不变。没有改共享harness或production。

中间两次完整76均75 PASS / 1 FAIL，隔离1 PASS仅诊断来源，不补成完整绿。最终 `C/node-ready-navigation-final.json/log/exit` 真完整76/76、0pending、exit0（旧view23＋controller22＋children30＋Strict node1）。原23fullName实测多重集missing0/added0。旧spec SHA `c1d917efcd14c9a5c5b36b6885b92b708e6640d649df22a4b6e016c663d888e3`；JSON SHA `2c6e61f02c6145fc2f6f24f0c4f7168f2660c71a8ff970d3f7b7249c21227d9b`。`C/node-ready-freeze.json`列7个相关源码/测试SHA。A非作者原样独立执行94/94 PASS、0skip、exit0，准确拆为本组76＋B的PPT18；本组7SHA和旧view最终SHA运行前后相同。原完整23fullName保持，原字面expect111未删除，新增5个真实Ready谓词；B的18不能记作本组作者运行。外部 `A/C-node-B-ppt-independent.json/log` 与 verification SHA `3003a38b2bca01d6110da2903a058992628c7bbdddf1c32f29e50f6d5e602a8f` 可核。本节只说明单测夹具和非作者复核；最终全站结果由组长归档，本组 release 浏览器结果单列如下。


## Release 冻结旧桌面完整执行

组长新冻结 `release/source-freeze.json` 为817文件，SHA `d1a38dd1471e5c5d0d23aeafa845c7494d3bc30e2d5875dd37c24e9faded596d`。本组只读 utility verify 实际exit0并全匹配（`C/release-verify-preparation.log`）；普通沙箱 `spawnSync git EPERM` 属工具权限边界，授权普通本地权限复核通过，没有修改策略/源码。此前817首批a8821a的110/1及trace完整保留，不能跨两次freeze合并PASS。

组长已确认同release冻结的干净npm ci、全量2384单测（0fail/0pending）、W0原111、type/browserType/build/tooling39/accounting13及零Vue依赖/产物审计均PASS；这些是组长来源，不冒称C执行。本组依明确GO使用正式Playwright/本地Chromium完整运行原旧桌面111：`release/legacy/C.json/log/C-results`，111 PASS、0 FAIL/skip/flaky/retry/errors、exit0，耗时398828.57ms。1worker、retries0、原整项30000ms均保持。前后utility均817文件、SHA `d1a38dd1471e5c5d0d23aeafa845c7494d3bc30e2d5875dd37c24e9faded596d`；初始桌面fullName多重集111条missing0/added0，39文件实际全部执行。原case17“专业编写、复核和文档汇总绑定同版源码，自定义规则保存重开”本批7657ms PASS，未用单项补首次110/1。

外部机器审计 `release/legacy/C-audit.json` SHA `08a27cc92869662287e9f8c1f926088b5be69f6bc8eac4b9d51b48b0ea15f418`；原JSON SHA `6ea6a0a0a529feccf04ec3d6971d0b85ce312ac1a290833cf1d49c36518cacd2`，log SHA `b2fa087bc9911aa296ffd785070a6eada67c5eecaf328e7b27751083c0a1ecf4`。源码/测试/工具未变。随后按组长明确GO在41775原production config完整运行49，服务自动退出；C当前无browser/server/unit进程。其资源和当前图像证据单列下文，不以旧图代替release像素证据。


## Release W5 生产49与原始资源/身份审计

`release/w5/browser.json/log/results/evidence` 实际49/49 PASS、0 skip/flaky/retry/errors、exit0（117224.346ms）。原49 fullName多重集missing0/added0，1worker/retries0；真实产品路由生产dist＋模拟API运输及本地原生SSE，不是W1 fixture。前后源码817/d1a38dd…全匹配；生产dist全部文件前后相同，bundle摘要 `454f1f79446aa3766bee47efc62919f034e95ae2e1945d1f78df9af7368d0303`。原JSON SHA `aafd0d1dc3a19edd65024d1f45170bbdf0d4b81e926d538c9f271dbcc0e29427`、log SHA `8ae43080d659b3cdc2da5870fadff177831f6b5f7afcb7dd98aba4b0572ecc33`。

C只读再核64/64首次原始快照：45普通route（五入口×三skin×三轮）、5真实root、2原生SSE、12活动退出。每份旧root断开、同document、listener/observer/RAF/capture/owned timer严格空数组；原Application MQL和独立哨兵保留。事件增量没有pointermove/up/cancel、mousemove/up或window blur；元素blur不冒窗口blur。12活动起手有真实单capture，六route在真实展开App导航后、SPA link之前仍原pan/drag/connect marker＋capture1。没有等待或补发自然事件使门槛变绿，也没有修改已验证资源helper。

45普通轮原App核算SSE实例保持；Designer九轮原session订阅各close一次；两原生SSE route/root均读取真实cursor17重连并关闭原连接，原用例还核退出后450ms无新增session读。三skin未知创建各真实beforeunload两次（刷新与back均dismiss）、同document/原四字段及POST body/key；阻断期间仅原POST1，显式恢复后POST2字节相同。明确409记录graph1/layout3、三次layout同key/CAS/body，未隐式重试；accepted读失败分支graph1/layout2随后仅GET恢复。六zoom `.5/1/2` 原首帧及提交均实际105/20屏幕位移。Designer同multipart metadata/submissionId和原File完整bytes/order重试相等，后续消息与额外附件仍由原用例证明未发送。

实际30新PNG均有连续两个完全相等buffer，PNG SHA与`.stability.json`最后两次hash完全一致，零像素容差。该机器审计是完整性/稳定性；C自身Designer/runtime图不是非作者视觉验收，交A/B独审。外部完整审计 `release/w5/C-audit.json` SHA `3415bbd90f63ed7da7e9f1aef756d64dc3d3caeb726dcff4bef3b4bdbad89d3d` 列全部64份正控/原始其他owner timer、30图SHA及动作identity。Ant模块timer与Application MQL精确归属原记录保留；未知资源继续严格阻断。资源账本不证明heap/GC、全App或任意设备清理。

## 非作者release代表图与动作读取

C实际查看15张本轮release图，SHA/路径在外部 `release/C-non-author-image-actions.json`（SHA `00f95b9b3d347a0128a51df13da7ec2fb221680feaee27024c9fbf3dc39768d6`）：A Task三skin与Workflow三skin，B Knowledge两skin/模板目录两skin/文档评审一图、B W7 PPT unknown及选中两图、Role github-white权限一图、组长Settings github-white一图。真实1440桌面Task状态/六详情入口、角色选中权限MCP上下文、模板原参数/开始/评分说明、Knowledge真实Mermaid/引用/composer及全局导航皮肤呈现均可读；本代表范围未确认阻塞。W6图没有W5的exact sidecar，不转借其稳定保证；模拟DTO/预览不称后端真实输出。

C同时读取B最终旧49、lead88、A W6生产97实际JSON（均0failure/skip/flaky/errors），并读组长release全量2384/2384单测中Task44和Role14断言。知识File预览/分页/来源/草稿/模型/停止、PPT未知恢复/409/暂停及资料方案、模板报告/恢复/评分，角色ZIP保留/绑定版本重新核验/只读权限与深链，均对应真实现行组件和原次数/身份谓词。上述A/B/lead运行是只读证据复核，不能记作C重新执行；图片不能代替高级命令合同，原PPT底层/C本波Workflow delta也不自称非作者。统一其余生产/全站汇总归组长。

## W1开发启动模式的追加非作者核对

组长以compiled W1 preview首次运行原脚本为33PASS/8FAIL、exit1，原 `release/w1/first-production-fixture/*` 留存。C核八个首错均在 `verify-foundation.mjs:169` 的before pendingFrames正控（0≠1）；Strict重放case也先失败于此，尚未到435行setup×2谓词。before真实fixtureowned listener/RAF/interval均1，raw native RAF仍在；compiled调用栈是 `/assets/preview-h_jom_E8.js:348` 的CG/s，源码ownedStack第67行则精确要求源TSX路径和attachResources/tick名，二者启动模式不符。raw immediate空数组不能用来把八项改计PASS。

[React官方StrictMode说明](https://react.dev/reference/react/StrictMode)明确额外effect检查仅开发模式。保留原源码过滤及全部positive/strict断言，以原 `e2e/w1/vite.config.ts` 开发fixture重跑同41项符合原门禁；生产根启动/清理另由组长compiled root6验证，不冒开发重放。历史tracked evidence前后SHA `908936fc…`同字节已还原。外部只读 `release/w1/C-startup-mode-review.json` 保具体八首错/raw stacks/官方边界；下述最终开发模式回执作为同脚本新的完整运行单列，八项首轮FAIL仍保留，本组未启动额外浏览器或修改任何观察器规则。


## 最终追加非作者复核：W1与当前W3/W4交付

C读取组长最终原开发fixture `release/w1/evidence.json`（SHA `35481ce4ebcf50e173310704af8daec72eb08a63ab572467a87155d185f4cba5`）：41/41 PASS、executionComplete=true、errors=[]、exit0；41原标题多重集与首轮compiled模式完全相同，脚本/fixture当前源码与before/after摘要均一致。18实际resourceProofs全部首个同步卸载样本：viewCount0、owned listener/RAF/timer/observer0，原始listener/pendingFrames/rawPendingFrames/interval/observer严格空数组。17份有before正控且tracked RAF=1、interval=1、原w1-root观察target仍在；第18份forced-retirement原记录没有before字段，不冒称它也保有同一个before正控。十次UNKNOWN轮setup为68、70、72、74、76、78、80、82、84、86，原435行每次mount加2谓词实际通过，writes一直1且key/body保持；不能把首轮未到该谓词的八项失败说成StrictMode资源缺陷。

11个最终PNG逐文件bytes/SHA与原evidence一致，记录定帧样本至少2；历史tracked evidence前后完整SHA `908936fc2c480d938d52db7b2e97d58fdbc0ebab1971de54327fa9bd5da65a12`相同。C同时核组长compiled应用root6/18：每个route三份首样owned listener/RO/RAF/capture/timer空数组、树为空、原核算SSE各close一次；`release/root/evidence`六文件与实际原输出 `/tmp/w6-root-evidence`及proof的SHA逐个一致。原环境变量名字未被config读取导致输出在/tmp，copy/hash校验不是第二次执行。开发fixture41与compiled应用root6分开证明，不声称production有开发Strict重放。最终追加只读审查JSON `release/w1/C-startup-mode-review.json` SHA `ef1458850832fee5b436c8ff455345e0773f1d7f98e16d155493e35cd0c865d4`。

在先前15张release代表图之外，C又实际查看当前W3十张（PPT真实manual选中三skin、Knowledge引用选中三skin、catalog默认三skin、tech-blue历史归档）和W4 Task会话选中/进度三skin六张；合计31张实际查看。新增16张逐文件与其连续两次完全相同buffer的stability SHA匹配，无像素容差。Knowledge引用Finance.java 1–3行/高亮、真实报告/Mermaid/composer和来源上下文可读；模板原参数/开始/评分披露、五归档tab/导出入口保留；Task会话105 Token/原Todo非权威说明/跟随输出/刷新，以及运行阶段图/次数/zoom-fit控件可读。Task模拟输出头部元信息呈现紧凑，未发现遮挡必要内容或操作的阻塞。C原PPT底层和本组Designer/Workflow delta仍是作者范围，不用这些图声称其全套非作者批准。图片是实际Chromium生产React渲染配模拟DTO，不是后端/模型真实输出。

只读核当前W3生产31与W4生产29的实际JSON：分别31/31、29/29 PASS，0 skip/flaky/retry/errors；这些是B/A及组长运行来源，C未再次执行。外部全部31图SHA、代表观察与原动作JSON来源在 `release/C-non-author-image-actions.json`，SHA `1b5e5dc5d09604306a6b09d470f31583dbfa9ee26ce14565566b43e97b1e3db2`。仅上述有限代表范围未发现新阻塞；不以代表图批准所有状态，不把资源账本当heap/GC或真实后端证明。最终全站521注册/518唯一的汇总仍归组长，C自己执行的111＋49与只读复核的其他批次保持分开。
