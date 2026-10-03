# 桌面全站语义与公共交互合同

**设计合同／独立模拟原型／生产未实施。** 本文依据 W0 本地冻结提交 `3c848261`，落实用户取消全部窄屏设计、适配和验收后的桌面修订。新原型检查尺寸为 1280×900、1440×960；三皮肤仍为 spdb、tech-blue、github-white。旧断言、旧截图和 W0 的正确合同红均保留。项目经理随后已批准W1基础工程；本文及原型不证明真实React/Ant验收或原owner安全缺口已修复，W2+仍未批准。

作者为原任务 `/root/react_legacy_canvas`；原创建回执显式指定 `gpt-6.1-sol / xhigh`，当前工具不能实时读取模型字段。仅持有本文件、[范围排除索引](desktop-scope-exclusions.md)、[语义 JSON](prototype/desktop-v2/semantic-registry.json) 和 [浏览器注册表](prototype/desktop-v2/semantic-registry.js)。没有改生产、W0 测试、依赖、旧原型或生成脚本。

## 1. 依据与边界

全部 31 路由记录来自 [router/index.ts:8](../../../frontend/src/router/index.ts#L8)：28 个挂载记录、27 个独立挂载 view、3 个重定向。`WorkflowEditorView` 被两个路由复用，旧 `AutomationsView` 未挂载；不能把 31 记录称作 31 个独立页面。完整行为、API/store 单一 owner 和测试锚点由 [核心盘点](core-ui-inventory.md)、[流程协议](workflow-protocol-design.md)、[PPT 与验收](validation-and-ppt-design.md) 持有；本合同统一展示与动作表达，不替换协议。

重新读取的实际参考：

| 入口 | 真实语义 | 桌面采用范围 |
| --- | --- | --- |
| [KnowledgeView:30](../../../frontend/src/views/KnowledgeView.vue#L30)、[93](../../../frontend/src/views/KnowledgeView.vue#L93)、[170](../../../frontend/src/views/KnowledgeView.vue#L170) | 来源、引用默认关闭；打开记录触发器；关闭回焦；引用读取受 route/sequence 保护 | 主内容优先，选中才有上下文；非模态面板不锁住普通 Tab。原小屏 overlay 不成为本轮要求 |
| [KnowledgeEvidence:29](../../../frontend/src/components/knowledge/KnowledgeEvidence.vue#L29)、[43](../../../frontend/src/components/knowledge/KnowledgeEvidence.vue#L43) | 原文范围定位，查询语句、原始结果、作者记录按需展开；阅读视图使用安全 Markdown | 主体、证据和原始结果分层，当前不完整/变化通知不能被折叠成不存在 |
| [KnowledgeHistoryView:36](../../../frontend/src/views/KnowledgeHistoryView.vue#L36)、[41](../../../frontend/src/views/KnowledgeHistoryView.vue#L41)、[48](../../../frontend/src/views/KnowledgeHistoryView.vue#L48) | 过滤 URL、分页深度/滚动恢复、按版本归档 | 关闭上下文不清查询；恢复归档不是恢复模型执行 |
| [WorkflowCanvasReact](../../../frontend/src/react/workflow/WorkflowCanvasReact.tsx)、[ReadonlyDiagramFlow](../../../frontend/src/react/diagrams/ReadonlyDiagramFlow.tsx) | 受控选择、明确节点/边方向、独立图/布局写、键盘定位、只读节点不写；局部手势取消 | 保留已验收 React 画布与 105px/20px 首帧、锁定/新布局、即时资源清理合同；不重新实现画布 |
| [PptCanvasView](../../../frontend/src/react/ppt/PptCanvasView.tsx) | 背景取消选择、选中对象属性、拖动/缩放与服务端 revision；0 恢复 zoom=1 | 对象属性按选择出现；不把 PPT 缩放复位替换成流程图 fit 算法 |

三皮肤只提供视觉 tokens。同一对象、动作和组件不得按皮肤改中文词、图标、权限、快捷键、dirty/receipt 或 File 身份。业务实体 `name/title` 属于数据，保留原值；统一的是“项目／任务／关闭／返回”等词义，不能把所有实体改成同一个名字。

## 2. 注册表与 SVG 来源

浏览器先加载 `semantic-registry.js`，再加载新原型的页面脚本。它提供：

```ts
window.LoopperUI = {
  schemaVersion: 1,
  source,
  objects: Record<SemanticKey, { label, name, icon, domain, description }>,
  actions: Record<SemanticKey, { label, name, icon, intent, scope, guard, behavior }>,
  icons: Record<LocalLucideName, { body, width, height, sourceName }>,
  guards, components, routes,
  icon(key): string,
  label(key): string,
  name(key): string,
}
```

`objects` 也持有 nav、page、section、field、status、app 的静态语义；`actions` 持有用户意图。当前首版包含 **120 个对象/词汇、173 个动作、78 个本地图标、31 个路由映射**；新增消费词必须先登记，不另造页面命名层。完整逐项中文 label/name、Lucide 名和行为可直接查 JSON，本文不复制一份会失配的大表。

`icon(key)` 只接受已登记的语义 key，返回可信本地 SVG 字符串；`label/name` 同样严格查表。未知 key 抛错，不回退到相近图标或英文协议名。注册表递归冻结，重复安装报错；不做 fetch、不拥有 listener/timer/SSE/command，也不实现 route guard。SVG 为 `aria-hidden="true"`、`focusable="false"`、`currentColor`，带 `data-semantic-icon=key`，不替代父按钮可访问名称。动态业务文本以文本节点渲染，不拼进 SVG body。

本地来源为已锁定 `@iconify-json/lucide@1.2.121` 的 `frontend/node_modules/@iconify-json/lucide/icons.json`，SHA-256 为 `428633667745fc16b264451b5d9bab478e7a648720a7d40c01e756b21346fe97`，图标集合元数据许可为 **ISC**。没有安装新的 Lucide 候选依赖。`history` 的本地 alias 解析到 `rotate-ccw-clock` 后复制 body；`sourceName` 记录真实源，不运行任意远程 alias。版本、hash、license 和全部选用 body 均记录在 JSON 的 `source/icons`；JS 内嵌相同数据以支持独立单文件，无 CDN 或图标网络加载。

公共 DOM 约定：对象/字段/标题使用 `data-semantic=key`；动作控件使用 `data-action=key`；所有可见图标由 `icon(key)` 给出。静态文本使用 `label(key)`，可访问基名使用 `name(key)`。有具体目标时公共控件可形成 `name(key) + '：' + entityName`，实体文本保持转义；禁止页面直接写 `lucide:x`、内嵌替代 SVG、emoji 或靠 icon 名猜操作。

## 3. 全 31 路由对象和动作映射

机器表 `routes` 保留原路由次序、view、routerLine、nav key、完整 actions 与行为说明。下表给审核者的定位；列出的动作是合同清单，显示/可用仍由对应 owner 判断，不是静态授权。

| 路由 | 对象/导航语义 | 关键动作与不能丢失的行为 |
| --- | --- | --- |
| `/` | `nav.home`，project/conversation | `project.select`、`task.open/create`、`knowledge.newConversation/openHistory`；主页搜索仅已加载项目与会话，不发明全局搜索 API |
| `/projects` | `nav.projects`、`object.project` | 登记、原生目录选择、公约读/生成/停止/确认写入、文档路径、Git、取消管理；保留文件和历史 |
| `/ppt` | `nav.ppt`、`object.deck` | 新建、搜索/游标、归档/恢复；新建不等于生成 |
| `/ppt/:id` | deck/slide/slideObject | 讨论、确认、生成、停止/恢复、人工对象/方案、检查、版本/下载；助手与 dirty 人工编辑互斥 |
| `/knowledge/history` | conversation/history | 服务端过滤与 URL 恢复、分页/滚动、按版本归档/恢复；不是重新发送问题 |
| `/knowledge/:conversationId?` | source/citation/conversation | 发送/停止、资料、模型、原文/阅读视图/原始结果；已有模型/资料冻结，面板默认关闭，解绑不删除文件 |
| `/designer` | `nav.designs`、requirement/history | 精确 sessionId 历史、任务设置、附件、保存/确认、停止；无 ID 按原 guard 恢复或转新需求；实际 Mermaid 沿原 React 路径 |
| `/requirements` | `nav.requirements` | 项目/搜索/游标、精确需求、新建；无自动确认/执行 |
| `/requirements/new` | `page.newRequirement` | title/goal/project/template 四字段、`workflow.createRequirement`；**没有 File**；固定 key/body，创建不等于 Start |
| `/requirements/:id` | requirement/node/edge | 规划保存、确认、SINGLE/UNTIL/整体执行、暂停/恢复/停止、候选、上传、模板、写回/提交/推送/结束；顶层与子 owner 分开 |
| `/workflows` | `nav.workflows` | 新建、搜索/归档、复制、用户流程删除；内置原定义不可改删；原复制 key 恢复 |
| `/workflows/new` | workflow/node/edge | 原新定义编辑器，图/布局各自保存、连接/删除、undo/redo、缩放/缩略图 |
| `/workflows/:id` | 同一编辑器 | 精确原定义和回执；105px/20px 首帧、取消与焦点、程序化 reveal 不污染撤销 |
| `/designs` | `nav.designs` | URL 过滤/归档、查看/修改历史、`designer.stop/retryStop`；不能调用 Task stop 替代 Designer 停止 |
| `/tasks` | `nav.tasks`、task | 搜索/项目/类型/状态/归档/排序/分组，打开/归档恢复/永久删除、登记项目、新需求；关闭上下文保留查询 |
| `/inbox` | `nav.inbox`、permission | 当前问题、拒绝、仅本次/本会话允许；不扩大 task/session scope |
| `/insights` | `nav.insights`、task/evidence | 原项目/状态/质量/归档过滤，用量、加载更多和真实任务评审；不制造业务计数 |
| `/automations` | `nav.templateTasks` | 仅 redirect；旧 View 不可达，9 GET/导出当前无消费者、10 写退役，不能复活 |
| `/template-tasks` | template/project/attachment | catalog、通用/Source/Document 原三家族、scope preview、原 File 哈希/创建；`automation.readArchive/exportArchive` 是已结清但**未实现的只读目标** |
| `/template-tasks/document-runs/:id` | templateRun/report/batch | 原取消/冻结恢复、补传/澄清、报告、批次诊断；原 File/bytes/order/key 不随换皮肤或关面板变化 |
| `/template-tasks/source-runs/:id` | templateRun/batch | 明确 Start、取消/冻结恢复、batch CAS/原命令重试、报告；linked execution disposition 不等于 run 完成 |
| `/tasks/:id` | `page.taskDetail`、stage/attempt/session/evidence | server flags 控制 Start/暂停/继续/取消/继续一轮/双评审；独立人工认定、scope、发布/冲突、派生与恢复、冻结设计 |
| `/tasks/:id/recovery` | `object.recovery` | FAILED/CANCELLED 的 FROM_FAILED_STAGE / ALL_STAGES / VERIFY_ONLY；创建草稿不等于 Start |
| `/tasks/:id/design` | history/attachment | 当前 task 冻结对话/规范/附件只读预览；不追随新 Designer 会话，无继续设计写入口 |
| `/runtime` | `nav.runtime` | 仅三条件动作：managed 重启；unmanaged 且 startupFailure 启动检查；其他重新检测。没有新增 stop/kill |
| `/tools` | tool/skill/permission | 原 MCP/Skill、global/project/inherit、按需读策略/文档、版本写；必需工具无任意开关 |
| `/databases` | connection/driver | 新增/编辑、测试、驱动升级、归档、查询/游标；秘密、表单 snapshot 和 driver/test/兼容验收不同域 |
| `/settings` | `page.settings`、settingsGroup/skin | 原 PUT 保存，无新增 CAS；Git 账号独立写、精确模型、demo、当前 canvas 实例不热切换，跨 section 保留草稿 |
| `/settings/roles` | `nav.roles` | redirect `/roles`，角色发布不并入设置保存 |
| `/roles` | role/roleRevision/permission | ZIP 选择/校验/diff/明确发布激活、历史/比较/导出、项目/slot 权限预览；published 与 bound revision 分开 |
| `/:pathMatch(.*)*` | `nav.home` | 原 fallback 到 `/`；未知 API 仍不能返回 HTML |

每个路由的完整测试锚点沿用 [核心路由表](core-ui-inventory.md#全部-31-条路由合同)。Automations 专门由 [兼容映射](automations-compatibility-map.md) 结清历史只读消费者与退役边界；表中未实现目标不会因注册了 icon/key 变成当前能力。

## 4. Guard 顺序与恢复身份

注册表的 `guard` 是可审查元数据，**不是运行时判定、服务端许可或 endpoint 调度器**。现有 W0 已复现的离开、迟到作用域、上传和恢复红详见 [W0 结果](evidence/w0/README.md)。后续正式 controller 按同一顺序处理每个可能销毁 owner 的 UI 关闭、back、route/id 更新、根卸载与新意图：

1. SENDING/busy、UNKNOWN 或已接受但尚未可靠交接身份/结果给存活 owner：先 **BLOCK**。中文说明先等待、核对原结果或重试受支持的原操作。普通 confirm 不可绕过；不能清 pending、生成新 key、替换 endpoint/body/File 或伪造停止。
2. 原操作已接受而后续读失败：仅仅读恢复，不重新 POST。完整原操作身份/导航目标安全交接后才允许销毁旧 owner。缺原 key 时不提供补造 key/自动重发；只有真实支持的已知 ID 状态读可以核对。
3. 仅普通未发送 dirty 草稿/File：明确留在页面或放弃修改后离开；保留原输入直到决定。选中和纯展示关闭可以保留 owner，不要求丢草稿；Skin/section 重排不能偷偷当放弃。
4. 真写操作仍执行原服务端生命周期、许可、范围、版本和破坏性/停止确认。只冻结该接口实际已有字段；**不为 Settings PUT 或其他无 CAS 接口虚构 expectedVersion**。

`receipt.retryOriginal` 是受支持恢复的特殊入口：仅未确认接受、原身份完整时重试同操作，不能被普通新意图按钮替代。原身份包括 key、endpoint、body、原 route/run owner，以及该接口已有 version/revision/model；上传还要核对真实 File bytes/hash/顺序。跨刷新无法 JSON 恢复 File，metadata 相同不等于 bytes 相同。受支持同字节重新选择必须实际核对，不同 bytes/order 不可替换未知操作。`receipt.readOriginal` 只在真实 GET/by-request/状态 API 存在时启用，不承诺所有接口都有查回执端点。

## 5. 公共组件 API 与合法变体

下表为全站设计目标；当前W1已实施8类受控基础组件，其实际API见[W1实现与验收](evidence/w1/README.md)及 `frontend/src/foundation/components.tsx`。完整领域包装、UiSelectionContext、UiStatus、UiKeyboardHint等尚未接入生产页面，不把设计名称当已实现。业务页只给数据/意图和 owner 可用性；不各自挑图标、注册全局键盘、重建 command controller 或复制确认逻辑。

| 公共组件 | 受控 props / events | 必须保持的行为 |
| --- | --- | --- |
| `UiContextPanel` | `id,titleKey,open,context:{objectKey,entityName},mode:inspect|edit,dismissPolicy:retainOwner|confirmDirty|blockOperation,initialFocus,returnFocusRef`；`onOpenChange(next,reason)` | 默认关，选中才开；桌面非模态，不 trap 普通 Tab；关闭仅请求 owner。改编辑字段先按 owner 守卫。触发器失效则回具名主内容；不销毁命令或 File |
| `ActionButton` | `actionKey,contextName?,appearance:primary|secondary|quiet|danger,iconOnly?,disabled?,busy?,reason?,availability`；`onAction(intent)` | 所有图标/文字/name 来自 registry；icon-only 仍有 name 和提示；真实 disabled/loading 防重复 intent；render/effect/换肤不触发写。`availability` 由原 owner/server 证据给出，不能靠 key 自动授权 |
| `ConfirmDialog` | `open,kind:dirty|destructive|permission|stop,contextName,description,confirmActionKey,dismissActionKey,operationState`；`onConfirm,onDismiss` | 明确目标/影响；dialog 自己 trap Tab，默认安全取消焦点；Escape 仅 dismiss，回触发器。UNKNOWN/SENDING 无“强行丢身份并离开”路径；成功只能来自权威回执 |
| `UiDisclosure` | `id,titleKey,expanded`；`onExpandedChange` | `aria-expanded/controls` 对应真实内容；高级/证据/历史具名可发现；收起不清输入/历史，不自动执行写 |
| `UiSelectionContext` | `selection:{kind,entityName,idInternal?},actions,readonly`；`onSelect,onDeselect` | 只显示当前对象合法上下文，背景取消选择不删对象/取消任务。内部 ID 留在协议，不直接变成普通标题 |
| `UiStatus` | `statusKey,description?,live:off|polite|assertive` | 当前错误、dirty、发送、unknown、等待和恢复阻断始终可见；历史错误不冒充当前状态，图标/动画不制造进度 |
| `UiKeyboardHint` | `shortcut,actionKey,scope` | 展示真实现有快捷键；输入框/CodeMirror 文本编辑优先，每个 keyboard/focus owner 只管理自身实例 |

W1正式采用可辨识联合类型，取代早期 `enabled:boolean` 草案：`ActionAvailability = {kind:'enabled'} | {kind:'hidden'} | {kind:'disabled',reason:string}`；`ClosePolicy = {kind:'allow'} | {kind:'confirm',reason:string} | {kind:'block',reason:string}`。原操作状态、File、dirty和允许销毁判断留在唯一owner，先由NavigationGate/decideLeave给出决定，再转换为组件策略，不让组件凭静态registry或任意boolean销毁owner。动作回调只接明确意图和已拥有的输入，不接“自动重试”开关。高级领域变体仍按上表行为定义，后续实际adapter必须验证两端。

| 语义变体 | key / Lucide | 行为差异 |
| --- | --- | --- |
| 关闭展示 | `ui.close` / x | 收起展示；owner/File/receipt 仍活着。不会 server cancel |
| 返回 | `nav.back` / arrow-left | 真导航，先 BLOCK，再 dirty confirm |
| 保存 | `ui.save`、各领域 `*.save` / save | 保存原输入；确认、执行、开始各自独立，不合并 |
| 删除 | `ui.delete`、task/workflow/PPT 特定删除 / trash-2 | 正确目标/范围确认；知识移除绑定使用 `knowledge.removeSource` / unlink，不称删除原文件 |
| 放弃编辑 | `ui.cancelEditing`、`ui.discardChanges` / rotate-ccw、trash-2 | 只丢普通本地草稿；无法放弃未确认写身份 |
| 业务停止 | `task.cancel`、workflow/template/knowledge/PPT/Designer 各原停止 key / circle-stop | 明确服务端副作用；按原目标与停止证明，不跨领域换 endpoint |
| 展开/收起 | `ui.expand/collapse` / chevron-down/up | 披露当前内容，状态和焦点可访问，不触发写 |
| 选择/取消选择 | `selection.select/deselect` / square-check/square | 仅本地选择；保存、删对象和取消业务均不跟随 |
| 本岛取消手势 | `canvas.cancelGesture` / x | Escape/cancel/blur/lost capture 仅结束本岛预览；close、servercancel 和 stop 不混用 |
| 缩放/定位 | `canvas.*`、`diagram.*`、`pptCanvas.*` / plus、minus、scan | 同意图使用同图形，名称按 canvas/只读流程明确；各自保留原算法/范围，程序化 focus/reveal 不发业务保存 |

Escape 顺序为最上层确认 dialog → 当前上下文 → 当前选择/本岛手势；没有通过这一顺序触发 server cancel。Tab 顺序、Enter/Space 激活、Arrow/Home/End 的原组件合同和焦点可见性保持；长节点离屏定位不改变当前 zoom。减少动态效果不延迟可用性或回焦。

## 6. 单一 owner、扫描与验收

原业务分工不因 registry 增加第二 writer：legacy 持有 core/Tasks/TemplateTasks 与同一 templateTask controller；workflow 持有 Workflow/Requirement/Designer 及 Document/Source 分支协议；PPT 持有 PPT controller/页。共享 schema/公共 primitive 由组长统一接口，过渡 delegate 只调用一套 controller。全部页面 ready 后原子替换 ReactRoot/Router，最终去 Vue；本轮独立 HTML 不是终态代码证明。

本轮静态核查注册表和源码映射；非作者真实浏览器记录由 [桌面原型索引](prototype/desktop-v2/README.md) 与 [独立审查](desktop-v2-review.md) 提供。它验证模拟页的语义、三皮肤、选择/关闭/键盘/重复操作与 dirty/unknown 展示，不替代真实 API/SSE/File、权限/冻结、命令恢复、StrictMode/RO 资源或 GC 证据。

W1 准入的后续防分叉门槛：

- registry 的每个 key 必须有中文 label/name、合法本地图标和明确 scope/guard/behavior；JSON 与浏览器内嵌数据一致，未知 key/重复安装失败。按路由核对缺失/多出动作，业务数据标题不被误扫为静态词义。
- 新页面不直接传 Lucide 名、raw SVG 或复制可执行的确认/键盘/导航策略；图表数据 SVG、Mermaid 安全 SVG、画布 renderer 自有图形单列真实所有权，不借白名单藏运行代码。
- 三皮肤比较同一 DOM semantic key、label/name、可用动作和 guard；对 dirty/unknown、无 key、accepted 后读失败、File 变化、取消选择/关闭面板分别做实际 owner 合同测，不以截图代替。
- 所有高级动作仍具名可达；删除/停止/权限/恢复按真实 server 许可负控。桌面动画与键盘、焦点、资源释放仍验；窄屏分支只按 [排除索引](desktop-scope-exclusions.md) 退出本轮，不删旧断言。

**未覆盖边界**：W1已实施foundation范围的扫描合同、公共组件和纯TS owner，其最终结果独立见W1证据；没有接管产品页面、history或API adapter，没有运行真实服务。上表完整领域包装和全站扫描仍待后续波次。历史Automations消费者未实现，W0红没有通过注册表消失。后续实现依既定W0–W7授权与行为门槛推进。

## 非作者发现后的词义修正

`task.openRecovery`统一为“打开任务恢复”（navigation／rotate-ccw），`task.restoreArchive`统一为“恢复归档任务”（server／archive-restore）；不把打开恢复工作台当执行服务器恢复。`page.newRequirement`与`workflow.newRequirement`统一使用plus，均表达“新建需求”；原需求对象本身仍git-branch。技能对象统一“技能（Skill）”。这些是中心registry修正，没有改既有业务路由/命令。全站同中文名的图标一致性作为新增合同断言保留。
