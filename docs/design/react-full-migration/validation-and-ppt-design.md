# 全站验证与 PPT 全页迁移设计

本文件是第二阶段的规划，不是实施或验收通过记录。基线为 `a3c692d38925206883f2b0a1255479108cfd439e`，工作分支 `feat/react-full-migration`。作者仍是原组员 `/root/react_ppt_canvas`；既有分工/启动记录显式指定 `model=gpt-6.1-sol`、`reasoning_effort=xhigh`，当前工具没有可读取的实时平台配置字段，因此这里只陈述记录证据，不把自述当作实时平台证明。本轮不新增人员、不安装依赖、不改运行代码、不提交或外发。

## 1. 已知事实与本轮设计结论

- PPT 自由对象画布和缩略导航已经是真实 React，应原样复用 `frontend/src/react/ppt/PptCanvasView.tsx`、`PptSlideNavigatorView.tsx` 及其纯 TS geometry，不重写为 React Flow。本轮迁移的是作品列表、工作台、属性、会话、方案、资料、历史和下载的 Vue 业务所有者。
- “保留 PPT store”指保留公开行为、API、幂等身份、CAS、未知回执与 SSE 合同，不保留 Vue/Pinia 实现字节。当前 `stores/pptStore.ts:1–2` 直接依赖两者；原文件不动与最终依赖树零 Vue 不能同时成立。组长已决定使用 **Zustand 5.0.15 vanilla／纯 TS 控制器**承接同一 owner，过渡 Vue wrapper 仅 delegate，最终移除 Vue/Pinia。
- 组长统一技术目标为 Ant Design 6.6.5、已有 Lucide `ReactIcon`、React Router Data 7.18.4，并继续保留已验证 React 画布和 XYFlow 锁定补丁。这里记录团队决策；本轮未安装或重新验证这些候选依赖。
- 已安装旧工作区与新工作区同为上述基线，Vite/Playwright 配置、锁文件、路由源码逐字节一致。只读收集得到 **192 个 unit 文件／1305 项**、**70 个 E2E 文件／304 项**。注册清单不代表本轮执行通过；上一阶段只有相关 204 项执行通过，其余 100 项含原 11 个已知失败没有该阶段的通过证明。
- 原 11 项不能成为最终全站门禁的豁免。先做 W0 根因清理，保留被阻断的后续功能断言，再移植测试；禁止把改选择器、改文本、跳过测试或改 expectedStatus 本身当作业务通过。

## 2. 测试实数与迁移工作量

### 2.1 可复现收集方式

在原工作区 `opencode-loopper-react/frontend` 使用既有依赖执行以下只读 list，不启动 webServer 或测试体，不在新 worktree 安装：

```sh
node node_modules/vitest/vitest.mjs list --json
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium node node_modules/@playwright/test/cli.js test --list --reporter=json
```

本轮收集文件为 `/tmp/react-full-unit-list.json`、`/tmp/react-full-e2e-list.json`；SHA-256 分别为 `2650be23a75b0e63d44769e097de7c9e1141fe5aab7d3c5a319844e904645185`、`acbbdf8cc21c9ee82c000b5c267e302a4378d91f9259c62d219bef382f487e2b`。这些是本地调查证据，实施时须把对应清单与 case 映射保存到正式证据目录并再次生成。Playwright 清单 304 项的 expectedStatus 都是 passed，无 skip；这不证明执行结果。

### 2.2 Unit 按主夹具分类

分类以测试主夹具为准：真实 mount／SFC import 是 Vue 组件类，同时采用 RTL／React 主夹具为混合；纯 TS 类还核查本地被测 import 链不依赖 Vue／Pinia；只使用 `flushPromises` 的 store 测试归状态运行时类。分类不声称 Vue 主夹具的子树没有 React。

| 分类 | 文件 | 已注册项 | 迁移方式 |
| --- | ---: | ---: | --- |
| 无 Vue／Pinia 依赖的纯 TS／API／领域合同 | 23 | 149 | 保留断言与假请求；路径变化时仅更新 import，独立验证错误/CAS/身份 |
| Vue／Pinia 状态、组合函数与主题运行时 | 10 | 81 | 改为 vanilla 控制器与受控时钟/订阅，不再用 effectScope／createPinia／Vue flushPromises |
| Vue 组件主夹具，可能嵌入 React | 143 | 875 | 把 VTU mount、vm／emit／setProps 迁到 RTL 的真实 React 页/组件；每个旧行为对映，不以 stub 代替被迁移入口 |
| Vue＋React 混合夹具 | 5 | 28 | 过渡期验证同 owner；最终将草稿/路由/DTO 更新断言移到 React 真页，删除过渡 adapter 主夹具 |
| React／RTL 组件及生命周期 | 11 | 172 | 保留已验交互、真实 StrictMode 根、即时 cleanup 和业务回调断言；bridge 测试随适配器退场对映到真实 React 根 |
| 合计 | **192** | **1305** | 数量用于发现遗漏，不能替代逐行为映射 |

10 个状态运行时文件为 `stores/{documentTemplateStore,taskStore,knowledgeStore,pptStore,templateTaskStore,sourceTemplateStore}.spec.ts`、`themes/skins.spec.ts`、`router/designerEntry.spec.ts`、`components/ppt/usePptCreation.spec.ts`、`components/workflow/modelChoice.spec.ts`。

5 个混合文件为 `components/{MarkdownDocument,StageRail}.spec.ts`、`views/PptStudioRecovery.spec.tsx`、`components/ppt/{PptCanvasBridge,PptCanvasRecovery}.spec.tsx`。把 `.spec.tsx` 当作“已全迁 React”会漏掉这些 Vue 主夹具。

PPT 子集为 **18 个 unit 文件／96 项**：API8、store15、creation5、作品列表1、chat10、generation3、plan3、properties1、project picker2、project sources2、artifact1、旧画布6、bridge5、canvas recovery1、studio recovery3、真实 React canvas23、immediate4、navigator3。这个子集不是额外计入全量。当前缺少独立 `PptSlideProperties`、`PptHistory`、`usePptPanels` 专属测试；相关行为有 E2E/父页覆盖，但全页迁移应补真实 React 单测，而不是把没有专属文件解释为无需验证。

### 2.3 全部 E2E 文件清单

以下列出所有 70 文件，参数化展开后的合计为 304；实施时现有 304 加新增全部执行，不使用相关文件 grep 作为最终全站结果。

| 文件（均在 `frontend/e2e/`） | 项数 | 文件 | 项数 |
| --- | ---: | --- | ---: |
| app-shell | 1 | connection-management | 4 |
| database-driver-upgrade | 10 | database-progress | 2 |
| designer-discussion | 9 | document-template-tasks | 2 |
| home | 5 | knowledge | 21 |
| linkage-regressions | 2 | ppt | 17 |
| publication-validation | 1 | react-all-canvas-cleanup | 14 |
| react-canvas-lifecycle | 2 | react-canvas-migration | 9 |
| react-canvas-narrow | 3 | react-canvas-pointer-lifecycle | 29 |
| react-diagram-accessibility | 6 | react-renderer-ro-diagnostic | 4 |
| react-workflow-cleanup-entries | 3 | react-workflow-review | 2 |
| read-consistency | 2 | roles | 13 |
| skills | 1 | skins | 14 |
| source-template-form | 6 | story-accounting | 1 |
| task-baseline-error | 1 | template-batch-recovery | 2 |
| template-batch-resilience | 2 | template-reports | 4 |
| template-session-diagnostics | 2 | template-tasks | 4 |
| workflow-authoring | 8 | workflow-canvas-focus | 12 |
| workflow-code-changes | 2 | workflow-command | 3 |
| workflow-default | 3 | workflow-document-combination | 4 |
| workflow-document-review | 3 | workflow-document | 3 |
| workflow-finish | 2 | workflow-history-analysis | 2 |
| workflow-history-report | 1 | workflow-history | 3 |
| workflow-input-reference | 1 | workflow-knowledge-evidence | 2 |
| workflow-knowledge-handoff | 2 | workflow-native-test | 5 |
| workflow-plan-review | 1 | workflow-pointer-contract | 6 |
| workflow-publication-commit | 1 | workflow-publication-preview | 1 |
| workflow-publication-push | 1 | workflow-repository | 3 |
| workflow-requirement | 4 | workflow-review-source | 4 |
| workflow-save-template | 2 | workflow-snapshot-analysis | 3 |
| workflow-snapshot-flow-report | 1 | workflow-snapshot-partial-report | 1 |
| workflow-source-design | 3 | workflow-source-plan | 2 |
| workflow-source | 2 | workflow-test-design | 2 |
| workflow-test-profile | 2 | workflow-test-review | 2 |
| workflow-test-summary | 3 | workflow-test-write | 2 |
| workflow-upload | 3 | workflow-writeback-preview | 1 |

所有文件都带 `.spec.ts` 后缀。静态 selector 盘点：69/70 文件含 role/label 查询；8/70 含 `.el-*` 或 ElementPlus 选择器；8/70 含 ReactFlow DOM 选择器；40/70 含 PPT/role/workflow 产品 CSS 选择器。集合重叠，不能相加。没有文件 import VTU/RTL 或读取 `__vue`／`data-v-`／`.vm`。所以 E2E 主逻辑可保留，不应整体重写。

ElementPlus 耦合文件为 `react-renderer-ro-diagnostic`、`skins`、`publication-validation`、`skills`、`linkage-regressions`、`template-tasks`、`database-driver-upgrade`、`document-template-tasks`。前者的 Table RAF 诊断属于真实他实例归属证据，不能机械把 `.el-table` 改成 `.ant-table` 后继续沿用已证实闭包。全站改 Ant 后必须重新证明实际实例归属；旧 probe 未识别时 unknown fail closed，保存全部 raw frames，不能按 callback 名、stack 或 roots=[] 排除。其他文件优先改为等价 role/label 与同一业务区域；保留数量、请求体、revision、禁用、错误、溢出和焦点断言。

## 3. 原 11 项历史失败的证据与 W0 方案

依据 [历史独立记录](../../deliveries/react-canvas-cross-review/known-baseline-failures.md) 和逐条原日志 `/workspace/react-canvas-evidence/e2e-baseline.log`，在未经改动的 `c26bf3bf` detached 基线已全部复现。本轮又核查当前 a3c692d 源码，未启动浏览器，不能宣称本轮已修复。没有证据证明这 11 项中存在迁移引入的真实产品 bug；也不能保证越过首个失败后没有其他 bug。

| 原失败 | 已证明的直接根因 | 当前源码锚点 | W0 根因清理与不得丢失的后续断言 |
| --- | --- | --- | --- |
| database-progress，1440/390，共2 | 原日志71/103行等待旧“主机”输入；当前是 JDBC URL/用户名表单。属于旧字段契约/夹具失配，不是单纯超时环境问题 | `e2e/database-progress.spec.ts:27,35`；`components/DatabaseConnectionDrawer.vue:51,78,80` | 用实际 JDBC URL 和用户名构造输入/DTO，核对 type profile、只读账号/访问范围、测试请求及结果；修改真实连接输入后旧 probe 消失；保留 tests=1/saves=0。后续任务12/14、85%、会话批次2/4、窄屏溢出断言仍须实际跑；它们被首错阻断，当前没有通过证明。另核查当前 server DTO，不能只换第一选择器就结束 |
| document-template-tasks，1440/390，共2 | 原日志133/160行 ENOENT；在业务与无溢出断言后写 `/private/tmp`，Cloud 路径不存在。是已证明输出路径 portability 错误 | `e2e/document-template-tasks.spec.ts:63` | 使用 TestInfo.outputPath 或显式证据 env 默认 test-results；只改本测试产物路径，不创建系统假目录或删 screenshot。保留 File 上传重试、同身份2次POST、按需报告正文只取1次等真实写操作断言。不同 OS 上的业务链路仍重新执行 |
| read-consistency 的自动化恢复，共1 | 原日志187行元素不存在；当前 `/automations` 已 redirect `/template-tasks`。旧入口仍被测试当作挂载 AutomationsView，不是网络恢复事实 | `e2e/read-consistency.spec.ts:32–49`；`router/index.ts:25`；孤立 `views/AutomationsView.vue:100` | 保留 redirect/deep-link 合同；将仍适用的失败→服务端恢复→清旧告警行为映射到原历史GET的正式归档消费者；TemplateTasks当前并非此消费者，详见[兼容映射](automations-compatibility-map.md)。冻结入口／原GET／状态断言后才迁移，不换成不相干API恢复测试。旧AutomationHealth的可见错误恢复合同、原数据兼容与公开写入口退役负控必须各有测试归属，不重新暴露退役写入。没有产品依据时标待确认/待复现，不能只删本case或把它改为redirect一条就丢掉恢复行为 |
| roles 三皮肤×1440/390，共6 | 原日志224等处期待“仍需运行时核定”，实际“阶段绑定配置 · 调用条件待运行时核定”。已证明是文案预期失配 | `e2e/roles.spec.ts:151`；`views/RoleManagementView.vue:552` | 对当前运行时待核定文案和 CONFIG_ONLY/complete=false 语义建立断言，继续保留 limitations、工具来源、required工具、API结果不冒充运行授权、无横溢出、两栏/窄屏布局。每种皮肤与宽度全部重跑，不能将正则放宽为任何“核定”或删 status检查 |

“后续功能待复现”是证据边界，不是最终允许失败。W0 先在原技术栈用相同模拟数据完整复现/修正以上根因，输出前后 trace 与行为映射；若发现真实业务 bug，以明确小修复和回归清除，不把它归咎于 React。最终 W7 全站零失败、零非预期 skip，不保留这 11 项豁免。

## 4. PPT 页面与业务所有权

权威产品语义仍见 [PPT 工作室合同](../../ppt-contract.md)。API/DTO 不变：`api/ppt.ts:37` 本地 UI 写标识、`:170` File/FormData、`:228` question.version/confirmed、`:251` 单作品 SSE；`types/ppt.ts` 与 `types/domain.ts` 保留服务端状态/CAS定义。新命令不能通过 UI 推断完成或自动重发。

### 4.1 现有边界与计划落点

以下新文件名为规划落点，实施时与组长共用目录约定统一；本轮不创建运行文件。

| 现有源与行号 | 持有的合同 | React／纯 TS 计划 |
| --- | --- | --- |
| `views/PptListView.vue:23,44`；`components/ppt/usePptCreation.ts:6,67,138` | 搜索分页/归档筛选读sequence；先创建、上传附件、发送第一轮讨论，收到回执再导航；File只在活owner，元数据/原key落浏览器 | `react/ppt/PptListPage.tsx`；`domain/ppt/creationController.ts`。File对象不经JSON clone、不放URL/序列化Zustand持久化；缺附件显式重新选择原文件，保持creationId/uploadKey/generationKey/revision |
| `stores/pptStore.ts:45,70,151,173,233` | epoch/owner、refresh coalesce/queuedMode、快照一致revision、旧响应拒绝、恢复pending | `domain/ppt/controller.ts` 暴露 vanilla store/read/action/subscribe/dispose；兼容 `stores/pptStore.ts` 在过渡期仅delegate。React用useSyncExternalStore／Zustand订阅稳定snapshot，无独立HTTP/SSE owner |
| `stores/pptStore.ts:259–279,281–344,348` | 单SSE、消息活动刷新不重下deck、pending命令key/revision/payload冻结、409回读、未知回执原身份retry、旧finally不解锁新owner | 同controller保持所有API调用数、请求体、键、epoch保护；业务写只在显式用户命令，effects不发新命令，Router loader不补写或确认 |
| `views/PptStudioView.vue:47,65,85,113,162,181–204` | 当前/历史scene、发送scope、preview revision排序、同组件A→B、dirty/busy/pending路由守卫、4s恢复poll与清理 | `react/ppt/PptStudioPage.tsx` ＋ `domain/ppt/workspaceController.ts`；document/route/scope/session代数独立，守卫批准后才切owner；poll/SSE仍同owner，不因皮肤/面板/渲染重订阅 |
| `components/ppt/PptCanvas.vue:28`；`PptSlideNavigator.vue:30` | 深层DTO snapshot桥接、props/callback合同；runtime进入锁定 | 全页直接用已验 `react/ppt/PptCanvasView.tsx:11`、`PptSlideNavigatorView.tsx`。vanilla更新immutable；过渡DTO clone限JSON DTO，禁止clone File/callback/owner。移除wrapper后不再 mount第二React根 |
| `react/ppt/PptCanvasView.tsx:28,55,63,71,140`；`domain/pptCanvas/geometry.ts` | point坐标、首帧完整移动、固定gesture尺寸/scale/revision、锁定/选取换代取消、nativecapture释放、ROdisconnect、键盘/预览过期提示 | 保持实现与23+4项真实React测试；全页改变props或布局后重新执行已有105px/scale/键盘/drag-resize活动退出，无新ReactFlow强套 |
| `components/ppt/PptProperties.vue:26,65,69,100`；`PptSlideProperties.vue:37,62,97`；`usePptAutosave.ts:13,21,27` | document+element/slide独立草稿键、baseline revision、900ms debounce、invalid/disabled/locked不写、一次attempt不暗自重试、409保留输入 | `react/ppt/PptPropertiesView.tsx`／`PptSlidePropertiesView.tsx`；`domain/ppt/drafts.ts`／`autosave.ts` 纯TS状态与时钟接口。初始化按document+对象完整identity，重新读最新只显式用户动作；effect cleanup精确cancel |
| `components/ppt/PptChat.vue:38,47,99,113,137,159` | freeform讨论与显式确认分开、text/answers浏览器草稿、发送时scope冻结、历史滚动/未读、活动展开保留、question.version/confirmed | `react/ppt/PptChatView.tsx` 委托同controller；`domain/ppt/conversationDraft.ts`。发送后只清同owner且与accepted文本一致的草稿；停止未知保持阻断，resume沿原授权 |
| `components/ppt/PptDetailsDialog.vue:28,37,47`；`PptPlanEditor.vue:62,106,139,153,184` | 真dialog焦点/关闭、七模块unknown字段保留、direction冻结、计划草稿冲突、重开方案与重新制作为显式动作 | `react/ppt/PptDetailsDialogView.tsx`／`PptPlanEditorView.tsx`＋纯TS plan draft转换；不在打开弹窗时自动生成授权，不降低dirty关闭守卫 |
| `components/ppt/PptSources.vue:23,28,39,65`；`PptProjectSources.vue`／`PptProjectPicker.vue` | 原File、SHA相关upload key、READY回执、资料按需正文、项目来源冻结权限/不可用说明、图片仅assetId | `react/ppt/PptSourcesView.tsx` 与project子组件；`domain/ppt/uploadController.ts`。异步hash/upload/content每个await复核document+operation identity，旧finally不清新upload状态；资料对话框关闭不能丢原未知上传File/key |
| `components/ppt/PptHistory.vue:15,48`；`views/PptStudioView.vue:357,376` | job冻结revision、旧制品访问、分页历史、只读历史deck、restore创建新revision并确认 | `react/ppt/PptHistoryView.tsx`，历史读取绑定document+选中revision+读generation；同一document连续选历史A/B也保护乱序，restore回执由原action身份消费 |
| `components/ppt/PptDownloadControl.vue:11,39,44,67`；`PptArtifactDownload.vue` | 只当前revision成功export自动下载、旧文件仍可手动下载；许可/渲染不可用、失败/重试真实显示 | React对应组件继续调用同API/artifactURL；Blob/objectURL等自有资源显式清理。未验证真实PPTX生成的模拟测试不能冒称office可编辑验收 |
| `components/ppt/PptEditorToolbar.vue:25–35`；`usePptPanels.ts:37,42,51,62,76` | 工具菜单精确outside监听，侧栏resize范围/键盘HomeEnd/持久偏好、展开收起不丢聊天或属性草稿 | React toolbar/panel hook；事件owner精确remove，活动resize取消/blur/lostcapture/routeleave即时cleanup，宽度改动不能改变进行中画布gesture scale |

新纯 TS 控制器不 import React/Vue/Pinia/DOM组件；Zustand vanilla只承载不可变快照与订阅通知，网络/时钟/Storage由显式依赖接口注入。命令、File、EventSource、capture target等活资源存在私有owner，不被snapshot序列化，不因React订阅数改变而创建额外业务实例。公开API语义与服务端请求不重新设计。

### 4.2 必须保持的全页流程

1. `/ppt` 新建：可选项目→真实File选择→create/upload→第一轮自由讨论；首次讨论不生成授权。重复点击、StrictMode、刷新恢复均保持原id/key，不自动导航或暗发第二请求。原文件不可恢复时显示重选，不宣称已持有File。
2. `/ppt/:id` 讨论：未发送文本、助手活动、待答问题阻止确认；用户显式确认冻结完整讨论。历史REQUIREMENTS_CONFIRMATION仍保持question.version与confirmed true/false，普通补充不冒充确认。
3. 生成/修改：生成授权、agent运行、jobs、phase独立投影；中间agent结束不等于流程完成。stop未知不能人工写或开第二writer，resume/adjust保持原冻结工作流。SSE重连与4s兜底读不重发写。
4. 成稿：默认预览/导航，手动编辑按需打开；document/slide/element选择仅影响新发送scope，既有请求scope冻结。键盘与指针同CAS操作批次；锁定页面/对象禁止写，Agent不能越权解锁。
5. 属性/方案：dirty、invalid、busy、pending、baseline冲突分别测试，切页/选对象/收侧栏/切路由不能绕过；属性draft、计划draft、聊天draft、当前页偏好各自保留键，不互相清除。成功写＋失败回读不重写。
6. 历史/下载：只读历史禁止编辑、独立revision筛选预览；restore创建新revision并保留旧文件；当前草稿更新提示不改变旧job冻结输入。选历史A/B、route A/B、下载revision变更均做迟到测试。
7. 对话框/窄屏：Ant弹窗、Drawer、select portals与原HTMLdialog同时迁移时保持aria名称、Tab循环、Escape/overlay规则、关闭回焦及dirty阻断；三皮肤只换应用语义变量，deck theme不随皮肤变化。

### 4.3 回退与移交

W3 过渡阶段仍可在**进入实例前**选择整个PPT页面的旧宿主或React宿主；两者都委托同一纯TS业务owner，不能同时激活两个命令/SSE生产者。现 `migration/canvasRuntime.ts:42` 的捕获一次语义、`canvasRuntimeVue.ts` 和两PPT wrapper只在过渡期保留；更新偏好不能热切活动页/卸载File/draft owner。离开先经过busy/pending/dirty守卫，允许后才销毁原页、建立下一页。

W6 删除旧Vue页/组件、adapter、偏好UI及Vue分支前，逐项将回退专属断言转成React导航保护合同，保留原行为映射和历史证据。最终 noVue 门槛不允许残留可运行Vue回退；最终回滚依赖可重建的已验证提交/包及数据兼容，部署回滚仍需独立授权，不能前端局部混搭两个owner。API、browser draft key和幂等键不随运行时切换改变。

## 5. 全路由与动作验证矩阵

基线 [router/index.ts](../../../frontend/src/router/index.ts) 第8–38行共31记录：28挂载入口、27种实际view（WorkflowEditor new/id复用），2redirect和1兜底；物理views另有孤立Automations。表中只列测试入口和合同，不在本轮恢复孤立路由。

每个挂载入口都须覆盖直接深链/刷新、应用内进入/离开、读失败/重试、空/长数据、键盘焦点及三皮肤390px代表状态；具写入的入口另必须覆盖下节通用命令矩阵。可复用数据夹具，但每条真实路由入口必须实际渲染，不能只验证一个共享组件。

| 入口 | 关键业务/动作 | 现有E2E种子与需补范围 |
| --- | --- | --- |
| `/` | 主页实际摘要、导航只跳转不建任务、空/错误/窄屏 | home、app-shell、skins；补React根/全局订阅生命周期 |
| `/projects` | 登记/编辑、详情、项目绑定与late选择 | connection-management、skins；补scope/CAS与所有弹窗 |
| `/ppt`、`/ppt/:id` | 上节7条全流程，资料、计划、history、side panels、download | ppt17＋96unit；补真实React全页及属性/方案/聊天资源门槛 |
| `/knowledge/history` | 会话列表、分页/筛选、旧会话深链 | knowledge；补列表迟到与恢复 |
| `/knowledge/:conversationId?` | File/source范围、问答/停止/恢复、历史读取、数据库/Git证据、Markdown | knowledge21；补无ID→有ID同实例、取消/late scope、资源清理 |
| `/designer` | query sessionId／mode=edit／projectId守卫、历史继续、讨论/候选、工作包范围 | designer-discussion、workflow历史与snapshot；补真实React历史只读、session/runs及迟到 |
| `/requirements` | 需求列表分页/筛选、状态恢复 | workflow-requirement；补列表与URL参数 |
| `/requirements/new` | 名称/目标/项目/流程、actual templateRevision、创建与正式开始分离、原key未知回执 | WorkflowRequirementNewView unit、workflow-requirement/default；补原命令身份/导航/读取恢复；此页无File上传 |
| `/requirements/:id` | 编辑/保存/确认/开始/执行、inputs/File上传恢复、recovery、publication、实时会话 | 全workflow种子含workflow-upload/input-reference；保留sameFile未知回执、same-instance A→B、命令finally/回读scope与所有二级面板 |
| `/workflows` | 库/版本/复制、只读图 | workflow-save-template、react-canvas-lifecycle；补库错误/分页 |
| `/workflows/new`、`/workflows/:id` | draft、新建/编辑、graph/ports/pan/undo、不可非法连线、CAS保存、另存preview | workflow-authoring、pointer-contract、canvas-focus；保留每个新建/编辑真实入口与dirty守卫 |
| `/designs` | 历史设计筛选/读取、继续链接 | designer-discussion、workflow-history；补空/late/原query |
| `/tasks` | 筛选/分页、晚到旧列表、链接进入、确认与开始分开 | read-consistency、home；补Data Router search/back/forward |
| `/inbox` | 待答/审查/恢复命令，未知状态不清提示 | linkage-regressions、workflow-requirement；补全部待处理动作 |
| `/insights` | 真实指标/时间窗口、chart与错误/empty | story-accounting、skins；补chart resize/unmount及不用mock伪造终态 |
| `/template-tasks` | 模板参数、项目/File/source范围、创建与正式开始分离 | template-tasks、source-template-form；W0恢复旧read-consistency语义对应 |
| `/template-tasks/document-runs/:id` | 上传/恢复、正文按需、report下载/错误 | document-template-tasks、template-reports；补same-instance scope/回读 |
| `/template-tasks/source-runs/:id` | 范围预检、source/test/report、恢复及download | source-template-form、template-batch-*、template-session-diagnostics；补所有真实路由进入 |
| `/tasks/:id` | authoritative phase/attempt/session/audit、waitreason、停止/重试、正文/报告、阶段图 | task-baseline-error、template-*、database-progress、react-*；补全部Action对映与长输出焦点/scroll |
| `/tasks/:id/recovery` | FAILED/CANCELLED父上下文、三种恢复模式、派生恢复、409安全阻断、创建不等于Start | RecoveryStudioView.spec.ts及linkage种子；补真实专属route/late scope/原Task不变；dirty-workspace File/停止保护属于Task详情相关弹层 |
| `/tasks/:id/design` | frozen设计版本、只读snapshot、工作包冻结内容/附件预览 | workflow-history-*、workflow-snapshot-*；补直接历史深链/late版本 |
| `/runtime` | localUI标识显式启动/重启、重新检测、进程边界、服务器真实版本；mount只读 | RuntimeView.spec.ts、skills/skins种子；补命令回执与读取失败，不增加停止endpoint或已退役的模型资格/能力卡 |
| `/tools` | capabilities、安全显示、runtime条件 | skills；补搜索/empty/error和边界提示 |
| `/databases` | JDBC输入、驱动/version、test与save分开、范围/项目、credentials不泄漏 | database-progress、database-driver-upgrade、connection-management；W0全部后续合同 |
| `/settings` | 原settings PUT保存/失败保留、本地皮肤/偏好、provider/model不暗写、生效范围、独立凭据 | SettingsView.spec.ts、skins/skills/react-canvas-migration；补完整表单/dirty；AppSettings无CAS version，不杜撰expectedVersion |
| `/roles` | role list/detail/history/diff/import ZIP/preview、409重校验后发布 | roles13；W0三皮肤×双宽度及File/版本合同 |
| `/automations`→`/template-tasks` | 兼容redirect；9历史GET／导出仍在，10公开旧写退役，当前缺归档消费者 | W0按[兼容映射](automations-compatibility-map.md)结清health读恢复、版本／run／导出／数据保护和拒写断言；不能只有redirect |
| `/settings/roles`→`/roles` | 兼容深链、刷新且仅一个role owner | roles现有首case保留 |
| `/:pathMatch(.*)*`→`/` | 404兼容返回与焦点，API/assets不被SPA吞 | app-shell＋新增fallback；服务端history fallback独立集成证明 |

## 6. 跨页面不可放宽的验证轴

| 轴 | 数据/输入构造与硬断言 | 最终层级 |
| --- | --- | --- |
| 每个写动作 | 建立动作清单：endpoint/method/expectedVersion或revision/key/权限/header/提交前guard/成功回读/失败恢复/导航副作用。双击、Enter+click、StrictMode、retry恰好一次有效写，明确新用户命令才有新key | 纯TS controller＋真实React页＋E2E记录请求体/次数 |
| 未知回执 | 服务端已接受但网络丢回执；当前owner保留原payload/revision/key/File，busy或pending阻止另写/离开；显式重试原身份。accepted write＋read失败只重读 | acknowledgedOperation/API/各controller单测，workflow/PPT/角色/模板E2E |
| 400/403/404/409 | 拒绝、权限/不存在、CAS冲突分别处理；409最新回读但输入/baseline不被覆盖、旧revision不再自动提交。失败后错误可见，不伪成功或自动导航 | 每类表单/命令至少真实例，既有断言全对映 |
| File/ZIP/Blob | 原File对象同身份跨失败/重试，file数量/总大小/类型/metadata校验；cancel重选保留；刷新只元数据恢复提示重选；FormData原bytes及key；objectURL自有回收 | domain负控＋RTL真实inputFile＋browser模拟multipart |
| 迟到/乱序/卸载 | A读/写挂起→guard批准进B→A晚到，A回读不得覆盖B、旧finally不得解锁新命令；同document不同历史revision/选择/问题也保护。卸载后不导航/写新owner/改草稿 | 受控Promise unit＋same-instance/SPA browser |
| 草稿/导航 | dirty独立于busy/pending；输入invalid、冲突、保存中、未知receipt都有真实恢复入口。切页/对象/tab/dialog/路由、POP/back、刷新关闭、runtime偏好均保持guard，Storage禁用仍保护内存活draft | Data Router blocker/BeforeUnload controller＋RTL＋E2E；不能只测确认框文案 |
| 真实订阅与StrictMode | 根 `<StrictMode>` effect replay，读owner的激活/取消有确定性；不靠永久didRun逃避第二setup，重渲/皮肤/面板不增HTTP/SSE。确切owner close一次，全局accounting不被画布close；Late SSE不越scope | unit记录identity/close调用＋真实eventstream E2E |
| 焦点/键盘/输入法 | 真Tab序/焦点框、dialog焦点/关闭回原trigger；Enter/Space/IME组合中不误submit；CodeMirror/textarea导航不冒充画布快捷键；只读可选择文字，PPTArrow/Alt/Shift/+−0/Esc保持 | RTL user events＋真实mouse/keyboard browser |
| 三皮肤与窄屏 | spdb/tech-blue/github-white，桌面1440与390；长名/长列表/错误/弹窗/生成/历史真实状态，scrollWidth≤innerWidth+1、controls可达、文字选区命中，decktheme分离 | 每页基础矩阵，复杂操作每个皮肤代表；截图仅视觉证明 |
| 所有实例资源 | route离开/关闭子preview/dirty拒离开各自正确；listeners精确target/function/capture，RAF/RO/IO/timer/捕获/portal/ObjectURL归属，3cycles不累计、两岛互不伤害，活动drag/connect/pan/resize取消首采样0 | 保留现有Immediate/Chromium4根4路由等矩阵；新PPT属性/面板/会话/图表资源扩展 |
| 信任与显示 | Markdown DOMPurify、Mermaid strict安全/late render/主题/清理；只显示真实活动/服务端phase，权限preview不冒运行授权；技术ID/凭据不进普通UI或日志 | 安全unit负控＋所有共享宿主页真实入口 |

资源门槛采样顺序不变：先证明可信手势与实际capture/位移/preview，激活真实SPA控制，DOM离开后的**第一份资源快照**必须清零；采样前不补up/move/cancel/window blur，不用poll让残留自行消失。哨兵健康检查仅在不可变首样及no-cleanup-input断言之后，观察器账本不按isConnected过滤。未知RAF/伪名/旧callback归属及真实detached RO负控继续阻断；全页Ant改变归属时先取得真实新的闭包/target证据，unknown不能假绿。

普通账本强持有对象只证明显式remove/disconnect；独立WeakRef采样不等于heap retaining path或无限增长证明。上一阶段补丁后extent RO和renderer在3次GC仍alive的实际结果继续保留，不能把本次全站资源通过写为“没有任何内存泄漏”。已验 XYFlow六入口补丁、StrictMode retired RO、selection pause测量与最终destroy边界继续全量执行。

## 7. 分波实施及最终门禁

采用组长统一顺序，各波有源码所有权、旧case→新case/入口映射、差异/聚焦结果和非作者审查；本轮只交付设计：

| 波次 | 工作 | 进入下一波条件 |
| --- | --- | --- |
| W0 | 原11根因及后续业务合同清理；B1–B9红测与Automations消费者／兼容映射结清；全站baseline304实际执行形成准确红绿/根因表 | 当前未通过，生产迁移前全部准入；每项有实际证据，unknown标待复现，不以修改expectedStatus清零 |
| W1 | Ant组件封装、Zustand vanilla/controller、共享主题/图标/Markdown/活动/表单；React根与导航协议仅隔离原型/Memory fixture验证，VueRouter仍唯一browser history owner | lifecycle、焦点/输入、回执/File、路由blocker合同与兼容测试通过；W6才正式接管history |
| W2 | 低风险系统列表/设置/工具/数据库/角色等 | 每条路由深链/错误/写动作/三皮肤基线及原11相关场景通过 |
| W3 | 模板任务/文档与源码run、Knowledge会话、PPT Studio owner与子组件；PPT列表/Knowledge历史在W2 | 上述PPT/资料/会话/停止/未知/历史/资源合同全迁React/E2E；过渡wrapper只delegate |
| W4 | Task详情/recovery/history/inbox；Tasks列表与Insights在W2 | 服务端状态、草稿、SSE与所有次级面板/图表入口完整对映 |
| W5 | 需求、Editor、Designer复杂owner | 已验画布不重写；全部scope/命令/恢复/publication/late候选/dirty保持 |
| W6 | 根与路由彻底切React，移除Vue页/适配器/运行时回退及旧测试驱动 | 零Vue静态/树/测试/锁门禁通过，所有旧行为有新case，不能仅删除文件降数 |
| W7 | 干净安装与完整验收 | 以下全部硬门禁，失败根因关闭后再对同一最终候选全站执行 |

### 7.1 noVue 四层门槛

1. **源码**：AST/import与文件清单检查 `frontend/src`、test helper、入口及Vite config：无SFC `.vue`、Vue/Pinia/vue-router/ElementPlus/@iconify/vue/@vueuse 引用、plugin-vue、Vue专属虚拟模块/宏/编译配置、旧canvas runtimeVue分支。纯TS控制器和类型链不暗依Vue；React root只创建一次。文档/历史归档可描述Vue，不把这些文字当运行依赖。
2. **测试**：无VTU、createPinia/effectScope/Vueh/mount与Vue flushPromises；没有把业务断言变成stub文案或删除。清单对映覆盖当前192文件/1305case的行为，加新增测试；合理合并重复夹具需解释行为合并，不用单纯总数证明完整。旧回退断言迁成React guard/owner合同，归档原case历史。
3. **依赖树与bundle**：干净 `npm ci` 后检查 `npm ls`／锁文件可达树和构建module graph，不含Vue/Pinia/vue-router/ElementPlus/相关Vue插件或测试驱动，built chunk实际未打包Vue。只从package.json删名字不能证明transitive退出。
4. **锁与工具链**：lock根/传递条目及postinstall校验一致，无陈旧Vue条目；React/XYFlow patch唯一匹配与错误入口负控通过，Vite/TS/测试工具全部用React路径。CI提供确定失败的noVue code/test/tree/lock负控，不能靠grep“没有输出”假设检测器工作。

### 7.2 执行计划与证据

W7 先从空 `node_modules` /独立干净 checkout执行合法 `npm ci --no-audit --no-fund`（不加ignore-scripts），确认锁定patch自动应用、check通过/重入changed=false。不能借旧Vite预构建缓存或手改node_modules获得绿。安装、测试、构建均保留版本、命令、退出码、source/lock hash及失败输出。

正式工具链为 `pom.xml:32–33` 管理的 **Node22.14.0／npm10.9.2**；Cloud现有 **Node24.19.0／npm11.9.0**作为额外兼容轨。两轨都做干净ci、类型、build、tooling、unit及同版本实际浏览器合同；不能用Cloud Node24绿代替正式Node22门禁。本轮不安装后端工具链或运行Maven。正式工具链具体命令/缓存由组长确定，不改变网络/凭据配置。

最终候选至少连续执行并分别报告：patch inspect/check → noVue code/test/tree/lock → React TypeScript noEmit/typecheck（退场后不再依vue-tsc）→ production build → project/tooling全套（当前31项，新增检查另计）→ accounting全套（当前13项）→ full unit（baseline1305＋新增全部）→ full E2E（baseline304＋新增全部，70现文件，不用grep/shard子集替代合计）。独立lint当前未配置，若新增其规则/命令另有明确范围，不能声称运行不存在的lint。

E2E由组长单worker/独立端口调度，使用显式授权的真实Chromium和模拟fixture，无真实付费模型、外部发布或凭据改动。Windows/macOS/Linux路径测试与官方Node轨至少覆盖产物输出、File/input/下载和深链启动；截图用TestInfo.outputPath或证据env默认目录，不硬编码 `/workspace`、`/private/tmp` 或修改浏览器策略。

只有 full E2E **全部通过、零失败/超时、零非预期skip** 且原11后续合同恢复、新增边界无缺口才闭合前端门禁。失败时保留trace/原始资源账本并区分产品、迁移、夹具、环境；未知必须待复现，不把重复运行绿或重试次数替代根因关闭。模拟API/浏览器/单测不证明真实Java持久化、模型运行、office渲染、发布或部署。若最终交付包含可执行构建，依据根公约由组长安排完整Maven/JAR与隔离运行集成；本轮不开展此实施或发布。

## 8. 本轮完成与移交

本轮仅完成源码/合同/历史日志只读盘点与两份注册清单收集，未执行unit体、E2E、typecheck/build、安装或后端验证。需实施后验证的事项包括原11越过首错后的功能、同document历史读乱序、完整React route blocker/POP、正式Node22兼容和Ant替换后的真实其他实例RAF归属；这些写在设计矩阵中，不宣称现已通过。文档检查 `node scripts/check-project.mjs` 通过（其20份受检查路由文档范围不等于本新文件全部内容已验证）；本文件链接存在性、70文件304项表格加总和局部 `git diff --check` 另行核对。本文交回组长集成，运行文件/依赖/既有测试保持原样。
