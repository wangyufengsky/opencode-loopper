# 全站核心 UI、路由和 Vue 退出盘点

本文件是第二阶段的设计输入，基于 `a3c692d38925206883f2b0a1255479108cfd439e`，工作区为 `opencode-loopper-react-full`、分支为 `feat/react-full-migration`。本轮仅查阅源码、测试和锁文件，未安装依赖、运行应用或测试、修改运行代码。下文的测试位置是已有行为证据的入口，不代表本轮重新执行通过。

盘点者是既有协作任务 `/root/react_legacy_canvas`。原启动记录明确指定 `model=gpt-6.1-sol`、`reasoning_effort=xhigh`；当前 `list_agents` 只返回任务身份和运行状态，没有实时模型或推理配置字段，因此这里记录原启动配置，不把自述当作当前平台字段。

## 计数、索引与证据范围

以 [router/index.ts](../../../frontend/src/router/index.ts) 第 8–38 行逐项重数：**31 条路由记录、28 条挂载记录、27 个独立挂载 view、3 条重定向**。`WorkflowEditorView.vue` 同时用于 `/workflows/new` 和 `/workflows/:id`；`/designer` 是带历史入口守卫的挂载记录。物理 `views/*.vue` 是 **28 个**，其中 `AutomationsView.vue` 没有当前路由挂载，不能把它计成第 28 个可达页面，也不能因路由退役自动删除其业务协议。

`frontend/src` 有 **187 个 SFC、6 个 Pinia store**。SFC 分类为 `App.vue` 1、`views` 28、`migration` 1、`components` 157；组件再分为顶层 67、workflow 59、ppt 21、knowledge 7、roles 3。其中 7 个是 Legacy 副本。完整文件、源码行、静态依赖、store 和测试查找索引见 [source-inventory.json](source-inventory.json)，不在本文件重复 187 行。该索引使用词法查找；静态 import、函数名称、引用次数只能定位候选，不能替代“用户动作 → owner → API → 权威回执 → 投影/恢复”的行为链。

现有画布 React 实现已经纳入基线，仍须保留其资源释放和交互合同；本轮不重写已迁画布。**默认 React 画布不等于应用已退出 Vue**：整个 `App`、Router、Pinia、多数表单、全局会计弹窗、主题状态和皮肤验收 fixture 仍执行 Vue。

全站迁移也不是机械翻译旧界面。新壳、导航、密度、五类页面 before/after、动效和独立模拟原型由 [UI 栈与页面规格](ui-stack-and-page-spec.md) 持有；本盘点为它提供不可丢失的能力清单。高级操作、恢复/重试、隐藏弹窗和权限保护必须在重排后全部可达，视觉重排单独做截图、键盘、响应式和真实交互验收，不能用“首屏更简洁”删除原业务入口。

下表源码和测试位置均相对 `frontend/src/`，E2E 位置相对 `frontend/e2e/`。标注的行号是此基线定位锚点。所有页面共同遵守：服务端持有生命周期、许可、冻结版本、停止证明和最终验收；前端按钮显隐不能授予权限。当前 router 未设登录/角色鉴权总守卫；本地 UI 写接口的 `X-Loopper-Local-UI` 和服务端校验须继续由 API 层发送/执行，不能把新 React 路由当作鉴权边界。

## 全部 31 条路由合同

| 路由 / 当前 view / router 行 | 状态、输入与深链 | 动作、输出、权限与恢复 | 源码行为锚点 / 已有测试锚点 |
| --- | --- | --- | --- |
| `/` · Home · 8 | 无业务请求；皮肤决定首页图形，目录链接由真实路由组成。 | 打开需求、任务、项目和系统页面；不隐式创建任务或启动 runtime。未知 URL 回到首页。 | `views/HomeView.vue:8,39,49,71`；`views/HomeView.spec.ts:10,34`；`home.spec.ts:16,40`。 |
| `/projects` · Projects · 9 | Task store 的项目 DTO，`?project=<id>` 定位项目；登记表的名称、绝对路径、说明；持久化 stack/Git 执行模式。 | 原生目录选择取消保留手填路径；明确登记；打开 Git/GitLab、文档路径、AGENTS.md；生成、停止、确认应用项目规范；取消管理保留历史，不删除文件。轮询只反映后端状态。 | `views/ProjectsView.vue:46,65,75,93,112,166,187,210,237,262,305`；`views/ProjectsView.spec.ts:28,52,78,128,145,187,205,221,242`。 |
| `/ppt` · PptList · 10 | 工作目录的查询、归档范围、游标；新建输入标题、目标、项目/资料。 | 明确创建作品并开始需求讨论；不会同时授权生成；打开 `/ppt/:id`，按版本归档/恢复。 | `views/PptListView.vue:23,44,78`；`views/PptListView.spec.ts:47`；PPT 完整行为见 [PPT 与验证设计](validation-and-ppt-design.md)。 |
| `/ppt/:id` · PptStudio · 11 | 精确作品 ID；Ppt store 的 deck/plan/sources/jobs/revision/agent/capabilities；手动对象草稿与助手操作互斥。 | 讨论、确认需求、生成/停止/恢复、修改方案、人工对象编辑、版本恢复、检查/下载、归档；按原请求 key/revision 恢复未知回执。busy/pending/草稿离开守卫；单 SSE 与 4 秒协调读归 owner。React 画布/页导航直接复用。 | `views/PptStudioView.vue:181,204,236,240,258,299,346,357,376,388,415`；`stores/pptStore.spec.ts:90,108,130,236,319,342`；`views/PptStudioRecovery.spec.tsx`。 |
| `/knowledge/history` · KnowledgeHistory · 12 | 必须先于可选 conversation 路由匹配；URL `project,query,archive,state,period`；游标、按 fullPath 存的已加载页数/滚动位置。 | 服务端过滤/分页；按版本归档/恢复；进入精确会话；返回恢复列表位置；失败与空列表分开。最多恢复 20 页，不自动发送问题。 | `views/KnowledgeHistoryView.vue:11,24,36,41,48,49,57`；`knowledge.spec.ts:272,289`。暂无相邻 view spec，E2E 是当前入口证据。 |
| `/knowledge/:conversationId?` · Knowledge · 13 | 空 ID 为欢迎/新问答；历史 ID 为冻结会话；按 fullPath 存问题草稿；系统默认与精确 provider/model ID、项目资料绑定、引用/分页文件预览。 | 仅明确发送才创建/发送；已有会话沿用冻结模型；同 key/text 恢复未知发送；停止与正文/思考不同状态域。SSE + 只在活动期协调读；切路由/卸载关流、失效预览请求、清 timer/listener，保留原草稿。资料 CRUD 不删除原文件或历史引用。 | `views/KnowledgeView.vue:29,42,56,72,84,113,126,154,167`；`stores/knowledgeStore.ts:17,53,76,90`；`views/KnowledgeView.spec.ts:86,104,111,135,145,153`；`knowledge.spec.ts:6,214,228,253,305,320,334,349`。 |
| `/designer` · Designer 历史 · 14 | `?sessionId=<id>` 显式恢复；`mode=edit` 进入可编辑历史；没有 sessionId 时，先检查旧 workspace 指针；指定 projectId 时走新需求，不自动复活别的项目会话。 | `designerEntry.ts:5–15`：可恢复指针转同一路径并补 sessionId；否则转 `/requirements/new?projectId=…&legacyDraft=1`。保留历史会话、冻结 draft/version、附件未知回执、问题/任务设置、保存与确认分离。真实 Mermaid 通过共享 Markdown 进入 React；不虚构新 Designer 流程画布。 | `router/designerEntry.ts:4`；`views/DesignerView.vue:1151,1167,1173,1244,1536`；`views/DesignerView.spec.ts:228,687,705,720,793,1128,1738,1788,1831,1914,1980`。 |
| `/requirements` · RequirementList · 15 | 项目、搜索、游标；加载/错误与空列表独立；请求 generation 防止迟到页面覆盖。 | 浏览精确需求；创建入口携带项目；不自动选择/确认/执行需求。 | `views/WorkflowRequirementListView.vue:15,21`；`views/WorkflowRequirementListView.spec.ts:22,36`。 |
| `/requirements/new` · RequirementNew · 16 | `?projectId,template,legacyDraft=1`；项目与流程各自加载/恢复；实际 templateRevision、标题、目标；旧 Designer 未发送目标只预填。 | 明确创建，固定 requestKey/project/template/revision；成功转 ID 页，不自动 Start。未知请求可重试原操作；**当前离开是普通 confirm，允许确认后丢内存 pending，区别于详情硬阻断**。 | `views/WorkflowRequirementNewView.vue:12,17,22,27,30,33`；`views/WorkflowRequirementNewView.spec.ts:18,26,29,36,45,57,63,64`。 |
| `/requirements/:id` · Requirement · 17 | 实际需求、有效 revision 与本地规划草稿分开；选中节点/边、候选预览、输入 File、执行模型；跨需求 RequestScope。 | 保存规划、确认计划、明确执行 SINGLE/UNTIL/整体、暂停/恢复/停止、候选审核/应用、节点操作、上传、另存模板、写回/提交/推送/结束。顶层命令未知和部分子面板 command/upload 有离开阻断；其余子面板的 confirm 语义见流程专题，不能推成所有未知写均硬阻断。确认不等于开始。2.5 秒 REST 协调不伪造运行事实。 | `views/WorkflowRequirementView.vue:39,69,86,207,218`；`views/WorkflowRequirementView.spec.ts:62,66,74,80,85,92,101,110,144,153,191,201`；`views/WorkflowRequirementScope.spec.ts`。 |
| `/workflows` · WorkflowLibrary · 18 | 服务端搜索、归档范围、游标；内置/用户流程区别；copy/archive 的 key 和版本留在页面 owner。 | 二级菜单按需展开，Esc 恢复焦点；复制内置允许，内置原定义不可改/删；删除明确确认；未知复制复用 key，不因换过滤器重建身份。当前无同等硬路由离开守卫。 | `views/WorkflowLibraryView.vue:12,15,22,33,47`；`views/WorkflowLibraryView.spec.ts:19,32,38,46,51`。 |
| `/workflows/new` · WorkflowEditor · 19 | 新草稿、公共输入、预置节点、选中节点/边、图与布局两份 baseline；undo/redo；默认未保存。 | 真 React Flow 添加、连线、删选中、定位、平移/缩放；手势与程序视口区分。保存图/布局使用独立原回执；pending/busy 硬阻断离开；普通 dirty 保留确认；未保存新流程不执行任务。 | `views/WorkflowEditorView.vue:30,31,40,132,137,139,142`；`views/WorkflowEditorView.spec.ts:31,40,45,51,64,77,91,102,115,129`。 |
| `/workflows/:id` · 同 WorkflowEditor · 20 | 精确定义 ID/revision；built-in 为只读，迟到加载失效；布局与图分离。 | 用户定义编辑；版本冲突留本地草稿并允许明确另存，读取失败不暴露陈旧可编辑图；程序定位不 dirty/入撤销。普通 deep link/刷新从后端恢复。 | 同上；`views/WorkflowEditorView.spec.ts:77,83,86,129`；`react/workflow/WorkflowCanvasReact.spec.tsx`、`WorkflowCanvasImmediate.spec.tsx`；`workflow-authoring.spec.ts`、`react-workflow-review.spec.ts`。 |
| `/designs` · DesignerHistory · 21 | URL `projectId,status,archive,order,q`，服务器列表/游标；确认、停止、归档各状态。 | continue/edit 只给可恢复会话；按版本归档/恢复；STOPPING 只重试停止；已确认任务跳 `/tasks/:id/design`，不提供继续/编辑/归档设计。 | `views/DesignerHistoryView.vue:85,113,135,152,178,199,254`；`views/DesignerHistoryView.spec.ts:58,78,100,122,138`。 |
| `/tasks` · Tasks · 22 | URL `status,type,project,order,archive,q,group`；服务端 facets/cursor；模板运行不冒充普通 Task；服务器 RETRY_WAIT 时间仅做倒计时。 | 按项目分组/分页/搜索；普通任务到详情，source/document 到各运行页；归档/恢复；仅已归档任务经确认永久删除；冻结设计入口。新需求入口传 projectId，未登记项目引导登记。 | `views/TasksView.vue:35,45,113,139,153,183,184,213,240`；`views/TasksView.spec.ts:79,96,112,127,152,170,185,196`。 |
| `/inbox` · Inbox · 23 | Interaction DTO、权限问题与回答问题；旧快照/错误分离，单 in-flight 读取和提交。 | 只发送服务器 availableActions 中的 ONCE/SESSION/REJECT 或回答；带 version；明确拒绝；刷新失败保留待处理内容，提交错误不能被背景刷新抹掉。1.5 秒递归协调读在读/写完成后排程，卸载停止。 | `views/InboxView.vue:22,27,32,56,80,81`；`views/InboxView.spec.ts:29,45,58,75,90,114`。 |
| `/insights` · InsightsDashboard · 24 | 项目、时间/质量过滤器；已应用查询快照与待编辑表单分开；Task 列表 cursor/global usage。 | 显式应用过滤，分页；质量中文投影；null 用量保留未知、不同货币分开，不把空值或模型 Todo 当成本/完成事实。只读。 | `views/InsightsDashboardView.vue:10,16,26,37`；`views/InsightsDashboardView.spec.ts:9,26,36`。 |
| `/automations` · redirect · 25 | 兼容旧 URL，无 mounted view。 | 固定转 `/template-tasks`；保留旧自动化 API/UI 合同的整合/归档决策，不能借迁移删除模板版本、规则、运行审计和导入确认功能。 | `router/index.ts:25`；`views/AutomationsView.vue:17,49,88,95,99,100,102`；`views/AutomationsView.spec.ts:38,44,62,79`（直接挂载孤立 view，并非当前路由可达证明）。 |
| `/template-tasks` · TemplateTasks · 26 | catalog、模板种类、项目/分支/日期、Source scope preview、Document 原 File；`?projectId` 初始项目；不同流程不同能力。 | 通用需求开发转默认 requirement；评审 FULL 不传日期，DATE_INCREMENTAL 才传；Source create 得 PENDING_START，明确 start；Document 校验/哈希/multipart，同请求恢复。服务器 caps/诊断决定入口，认证失败不伪造本地分支成功。 | `views/TemplateTasksView.vue:23,61,66,78,110,136,145`；`views/TemplateTasksView.spec.ts:31,41,53,70,84`；`stores/documentTemplateStore.spec.ts:15,26,34`。 |
| `/template-tasks/document-runs/:id` · DocumentTemplate · 27 | 精确 run ID、overview/source identity、batch/report/source/clarification；pending action/key/expectedVersion；原文档和补传 File。 | 明确取消/恢复、补传/回答、批次恢复、诊断/报告；单 SSE、10 秒协调读、180ms 合并；读断线不意味着测试已运行。跨 run 失效迟到读；**未知页面命令仅内存保护，当前无 route-leave 硬阻断**。 | `views/DocumentTemplateView.vue:26,27,48,55,62,69,79`；`views/DocumentTemplateView.spec.ts:24,34,48`；组件补传/报告/诊断测试见索引及流程专题。 |
| `/template-tasks/source-runs/:id` · SourceTemplate · 28 | 精确 run ID、Source 项目/分支/冻结 scope/proposal；批次 cursor、report coverage、linked execution disposition 独立；pending key/version/model。 | 明确 start/cancel/resume、batch retry；丢响应只重试原命令；恢复读取不重复执行，跨 run 失效迟到读；SSE/协调读归单 owner；**当前无 route-leave 硬阻断**。 | `views/SourceTemplateView.vue:22,28,40,56,72,79,88,98`；`views/SourceTemplateView.spec.ts:24,38,47`。 |
| `/tasks/:id` · TaskDetail · 29 | 精确 Task ID；overview/audit/version/Stage/Attempt/Session/Judge/Queue 彼此独立；template、DIRECT、双评审、dirty 等服务器 flags；动作捕获原 scope/generation。 | PENDING_START 才 Start，确认未入队/占租约/建目录；暂停/继续、取消或重试停止；条件允许才继续一轮/双评审重试/返工派生；AWAITING_DECISION 明确继续当前任务、继承修改派生或全部重做；scope 人工授权、独立人工认定、确定性验收、Git 发布/冲突；一条 Task SSE + overview/audit 合并协调读，模板进度另按真实状态协调读；卸载失效确认/读取并停止订阅。 | `views/TaskDetailView.vue:32,35,105,108,112,149,154,165,174,177,197,216,254,297,312,331,361,383,441,464`；`components/TaskDecisionPanel.vue:47,89,107`；`views/TaskDetailView.spec.ts:81,147,166,211,256,291,333,367,402,447,493,533`；`task-baseline-error.spec.ts:3`、`publication-validation.spec.ts:3`。 |
| `/tasks/:id/recovery` · RecoveryStudio · 30 | 原 Task ID、失败 stage/attempt、历史 recoveries；FROM_FAILED_STAGE / ALL_STAGES / VERIFY_ONLY，writableSession。 | 只给 FAILED/CANCELLED 创建派生恢复；明确调用带 Local UI 标识，服务器冻结合同和 DIRECT writer 冲突权威；409 显示阻断，不伪造草稿或改原 Task；创建不等于 Start。 | `views/RecoveryStudioView.vue:14,23,32,49,57,65,95`；`views/RecoveryStudioView.spec.ts:34,53`；`api/client.ts:1765`。 |
| `/tasks/:id/design` · TaskDesignHistory · 31 | 精确 Task 绑定的冻结设计 conversation/LoopSpec/父来源；附件按需只读预览；不跟随最新 Designer 会话。 | 只读时间线、问题、规范和附件；不提供执行、继续讨论或改冻结规范；深链刷新从 Task 设计历史恢复。 | `views/TaskDesignHistoryView.vue:15,22,27,52,63,78,91`；`views/TaskDesignHistoryView.spec.ts:11`；`views/TasksView.spec.ts:185`。 |
| `/runtime` · Runtime · 32 | Task store runtime snapshot、server Loopper version、CLI version、ONLINE/启动失败/尝试端口、managed generation。 | 只显式 start/restart；必须带 Local UI 标识，只有刷新得到 ONLINE 才报启动成功；不在 mount 自动启动；内部 MCP 能力发现/授权不恢复成独立 UI 卡片。 | `views/RuntimeView.vue:12,14,17,27,29`；`api/client.ts:1873,1874`；`views/RuntimeView.spec.ts:9,33,77,109`。 |
| `/tools` · Tools · 33 | 项目或全局 scope；MCP/Skills tab、servers/configurable/catalog complete/version；Skill search/directory/document 按需加载。 | 展开才读工具/策略/Markdown；global 与 project/inherit 权限不同，系统必需工具无开关；单条/整源禁用带正确 scope version，409 保留原开关；不扩大被冻结任务权限。 | `views/ToolsView.vue:12,13,24,29,40,51`；`components/McpToolPolicyPanel.vue:10,14,19,25,31,40`；`views/ToolsView.spec.ts:10,27`；`components/McpToolPolicyPanel.spec.ts:8,16,25`。 |
| `/databases` · Database · 34 | cursor/search/type/state、driver catalog、连接 version、只读 username/schema、JDBC、secret；Drawer 表单 revision 单独计数。 | 明确创建/编辑/连接测试/驱动升级/归档；password=null 保留旧 secret，关闭清明文；测试结果只匹配当前表单；installed driver、profile、连接测试、厂商兼容验收分开；归档不改旧任务冻结权限。 | `views/DatabaseView.vue:20,27,28,29,32,36,44,45`；`components/DatabaseConnectionDrawer.vue:26,31,36,50,55,57,63,71`；`views/DatabaseView.spec.ts:8`；`connection-management.spec.ts`、`database-driver-upgrade.spec.ts`。 |
| `/settings` · Settings · 35 | AppSettings 的 canvas/runtime/models/limits/retry/git credentials/publication/demo；单表单跨 section 保留 edits；实际 CLI model catalog。 | 显式保存，字段验证并聚焦失败 section；model 精确 ID；credentials 子表单独立写、不混入 save；demo 开/关显式刷新；canvas 偏好只影响下次进入，当前实例不热切换；需重启的端口等不假称立即生效。当前无一般 settings dirty 路由守卫。 | `views/SettingsView.vue:13,32,48,61,76,89,98,109,119,142`；`views/SettingsView.spec.ts:25,39,65,90,130,163`；`react-canvas-migration.spec.ts`。 |
| `/settings/roles` · redirect · 36 | 旧深链。 | 转 `/roles`，角色发布/激活不并入普通 Settings 保存。 | `router/index.ts:36`；`views/SettingsView.spec.ts:25`。 |
| `/roles` · RoleManagement · 37 | server 搜索/cursor、精确角色、latest published/bound revision/history、项目/slot 权限 preview；ZIP File、sourceSha256、key、activation expectedVersion。 | 查看 prompt/历史/diff/实际绑定 revision；只按实际声明显示可用工具/阻断；导出；选择 ZIP → validate → 可检查字段 diff → 明确确认 publish。同 File/未知发布复用 key；409 要重新校验，不能自动 publish。当前 File/key 在内存且无路由离开守卫。 | `views/RoleManagementView.vue:83,125,178,242,287,305,322,340,358,365,380,413,437,450,471,481,535`；`views/RoleManagementView.spec.ts:54,78,125,158,196,218,250,280`；`roles.spec.ts:84,105,130,174`。 |
| `/:pathMatch(.*)*` · redirect · 38 | 未知 URL / 错误深链。 | 转 `/`；保留浏览器刷新 SPA fallback，不能把未知 API URL 改成 HTML。 | `router/index.ts:38`；`views/HomeView.spec.ts:34`；服务端 SPA fallback 按前端公约保留。 |

流程定义、需求、Designer、模板协议的深入设计见 [workflow-protocol-design.md](workflow-protocol-design.md)。此总表保留完整入口和页面边界，不替代该专题里的回执、编译器和子面板合同。

## 壳、导航和全局能力

[main.ts](../../../frontend/src/main.ts) 第 1–46 行拥有当前唯一 Vue 根：创建 app、安装 canvas runtime、Pinia 和 Vue Router、注册 Element 组件、注册离线 Lucide collection、挂载 `#app`。第 37–38 行拥有跨标签主题同步与 HMR dispose。`index.html` 指向这个入口，不能仅把 `.vue` 页面逐个替换后宣称根已迁完。

[App.vue](../../../frontend/src/App.vue) 第 10–34 行区分普通壳和画布壳：`/workflows/:id`（含 `new`）、`/requirements/:id`（排除 `new`）和 `/ppt/:id` 使用浮动导航。打开导航后主内容 inert；Tab 循环、Esc/遮罩关闭、返回 toggle 焦点、fullPath 变化收起导航、skip link 都是行为合同。普通壳使用侧栏。`AppSidebar.vue:23–59` 有 11 个工作区入口和 5 个系统入口；知识入口记住经过合法路径校验的 `knowledge.lastPath`，运行环境摘要只消费 store 的已检查 snapshot，不自己探测 CLI。

`StoryAccountingDialog.vue` 由 `App.vue:34` 常驻挂载，**不属于当前 Designer 或某个 route**。它在 `:145–155` 拥有唯一 accounting SSE、visibility listener、受限恢复 timer 和显示时钟；并行 calls 与 selected call 分开，取消只作用当前 call，远端仍被业务拥有时禁止重试，完成回执保留到明确关闭。`StoryAccountingDialog.spec.ts:31,41,52,79,87,103,117,132,152,162,170,178` 已覆盖这些不同边界。迁移时 root controller 独占该流，route island 卸载不能把它一起关闭；根卸载必须释放所有资源。

主题不是一个普通 picker 的本地 state：`themes/state.ts:4–6` 当前用 Vue module ref/computed；`:8–29` 写 HTML dataset、theme-color、localStorage 并管理 storage listener。`themes/compile.ts` 与 `vite.config.ts:9–14` 的首屏同步 bootstrap 必须保留，防止深链首屏闪回默认皮肤。Ant Design 的主题映射消费现有语义 tokens，不能复制一套全站硬编码颜色；减少动态效果、中文错误/状态标签、长内容换行和离线图标仍是共同合同。

## SFC 类别与共享组件的业务能力

以下是能力归类，完整成员查 [静态索引](source-inventory.json)。类别可交叉，一个文件包含的权限/协议不因改到新的 UI primitive 而消失。

| 类别 | 现有代表和数量边界 | 迁移边界 |
| --- | --- | --- |
| 壳/基础展示 | App、AppSidebar、PageHeader、StatusBadge、MetricCard、SkinSelector、LayeredErrorPanel、TokenUsageWindow | React base UI + tokens/中文投影；错误层次和单调 token baseline 是共享纯函数/外部 store 能力，不由视觉组件制造状态。 |
| 文档、代码与图 | MarkdownDocument、CodeMergeEditor、LoopSpecEditor、ArtifactAssertionEditor、StageRail、TemplateTaskProgressPanel、RoleWorkflowDiagram | DOMPurify、Mermaid 安全 SVG、异步失效/observer/theme cleanup；CodeMirror 实例销毁、语言映射；Stage/role/template 的真实节点语义。React 实现复用，最后移除 Vue wrapper 与 Legacy；不得把 sequence/gantt 伪装为 React Flow 节点。 |
| 项目/系统编辑 | DirectoryPathInput、ProjectAssistDialog、ProjectDocumentPathDialog、ProjectConventionActivityPanel、GitCredentialForm、DatabaseConnectionDrawer、McpToolPolicyPanel、SkillBrowser、StoryBindingSetup | 原生目录 chooser、空/取消处理；Git 继承/独立账号；秘密不进 URL/storage/日志；服务器版本/真实 capabilities；按 scope 单 owner。 |
| Task/执行证据 | AttemptTimeline、JudgeReviewCard、TaskDecisionPanel、TaskJudgeApprovalPanel、ExecutionAcceptancePanel、ExecutionEvidencePanel、TaskAuditEvidencePanel、TaskPublicationActions、RollingPackageWorkbench | Task/Stage/Attempt/Judge/Queue/Lease 独立；人工认定不改 AI verdict；确定性验收不被按钮绕过；冻结 work package、提交/推送/冲突有独立权限与确认。 |
| Session/活动/问题 | SessionMonitorPanel、SessionRoleSummary、OpenCodeTodoProgress、PendingQuestionCard、AiActivityPanel、AssistantActivity、DesignerCurrentActivity、DesignerDiscussionHistory、DesignerSystemMessageHistory、DesignerValidatorHistory、StagedFileContextCard、PackageGapNotice | 原 Session 身份、明确回答/拒绝；Todo 是非权威投影；输出/问题布局、边界内滚动、引用/折叠；连续 system 信息合并不能隐藏活动错误。SessionMonitor 有自己的选中会话 generation/协调读，不能让新旧页面分别安排协调读取。 |
| 模板运行 | SourceTemplateFields、SourceCoveragePanel、SourceArtifactsPanel、DocumentFilePicker/Summary、DocumentSourcesPanel、DocumentRequirementsPanel、DocumentClarificationForm、DocumentSupplementForm、DocumentReportsPanel、TemplateReportsPanel、TemplateBatchRecoveryPanel、TemplateSessionDiagnosticsPanel、SnapshotReviewBatchesPanel、SnapshotReviewPartialReport | 原文档 File/哈希、补传身份、batch 与 linked execution disposition、单 run 订阅、明确停止/重试；报告完成不能等同开发已验收。 |
| knowledge 子组件 7 | SourcesPanel、GitBrowser、ModelPicker、Question、Thinking、Evidence、Usage | 资料查询和问答执行不同 owner；模型/资料冻结、精确引用和分段文件预览；空 turn 和流式思考分开，绝不自动发送。 |
| roles 子组件 3 | RolePromptView、RoleWorkflowDiagram、RoleWorkflowDiagramLegacy | 显示发布版本、绑定版本、历史、工具阻断；版本查看事件保留；图不授予权限。 |
| workflow 子组件 59 | Canvas、编辑菜单/预置/输入/节点上下文、NodeRun、Candidates、Files、各种 report/evidence、SaveTemplate、Writeback、PublicationCommit、Push、Finish 等 | 类型化 graph/layout/NodeSpec 和服务端命令；页面/节点/上传 scope，不把编辑/确认/执行合并；见流程专题完整分类与子面板守卫。 |
| ppt 子组件 21 | Canvas、SlideNavigator、Chat、PromptInput、Properties、SlideProperties、EditorToolbar、DetailsDialog、PlanEditor/Narrative/Presentation、Sources、History、Downloads、Progress 等 | React 自由对象画布和导航复用；Vue editor/forms/panel hooks/DTO 快照仍待迁；具体协议由 PPT 专题持有。 |
| 兼容和不可达候选 | migration/CanvasRuntimeSettings；7 Legacy；AutomationsView；SessionLifecyclePanel | canvas 偏好当前实例固定、只影响下次进入；W6 才删双 runtime/adapter。词法扫描未发现 AutomationsView/SessionLifecyclePanel 的生产 SFC import，需行为映射再决定整合/归档，不能以未引用为业务删除授权。 |

7 个 Legacy 是 `MarkdownDocumentLegacy`、`StageRailLegacy`、`TemplateTaskProgressPanelLegacy`、`roles/RoleWorkflowDiagramLegacy`、`workflow/WorkflowCanvasLegacy`、`ppt/PptCanvasLegacy`、`ppt/PptSlideNavigatorLegacy`。它们是过渡技术副本；业务能力在现有 React 路径中保留后，最终退出 Vue 才清除其源码、偏好项、fixture 和测试引用。

## 可达弹窗、侧面板和二级操作

“打开页面”测试不能覆盖以下隐藏入口。Ant Design 替换须逐项保留打开条件、不可关闭条件、Esc/遮罩、焦点恢复、原 File/文本草稿和原 key/version，而不只比对画面。

| 入口与当前宿主 | 隐藏能力 / 必须保留的关闭和写入边界 | 源码 / 行为测试入口 |
| --- | --- | --- |
| 应用导航 overlay；全局 accounting dialog | 惰性导航、inert/focus trap；会计流即使没有 Designer 也可出现，running/retrying 不能关闭，receipt 明确 dismiss。 | `App.vue:13,18,31,34`；`app-shell.spec.ts:23`；`components/StoryAccountingDialog.vue:159` 与上述完整 spec。 |
| Projects 登记、原生目录 chooser、Git/GitLab、文档路径、AGENTS.md | 登记手填/chooser；Git 凭据加载/保存期禁止关闭；文档保存期禁止关闭；规范 RUNNING/STOPPING 禁止遮罩/Esc 静默关闭，stop 需明确请求与证明。 | `views/ProjectsView.vue:305,306,307,323`；`components/ProjectAssistDialog.vue:54`、`ProjectDocumentPathDialog.vue:33`、`GitCredentialForm.vue:42`；Projects spec `28,52,145,242`。 |
| Database ConnectionDrawer | 新增/编辑、只读用户名/schema、秘密保持/清除、驱动升级、当前 draft 连接测试；saving/testing 禁止关闭；只匹配当前 revision 的测试可显示。 | `components/DatabaseConnectionDrawer.vue:26,36,50,55,63,71`；`views/DatabaseView.spec.ts:8`、connection-management/database-driver-upgrade E2E。 |
| Task dirty workspace / scope approval / diff preview | dirty workspace 不可静默 dismiss，逐文件 commit/stash/remove，取消仍走 Task 停止协议；GIT_DIFF scope 授权与验收不同；diff 惰性请求失败可重试。 | `components/DirtyWorkspaceDialog.vue:72,125`、`GitDiffScopeApprovalDialog.vue:163,202`、`TaskAuditEvidencePanel.vue:201`；`views/TaskDetailView.spec.ts:81,533`；相邻三个组件 spec。 |
| Task 发布、MR、源代码同步冲突中心 | commit 多行原文、MR target/title/body；明确发布/推送/同步；冲突逐路径选择/手工 CodeMirror 内容、expectedVersion、AI 建议不自动选、不自动 apply。 | `components/TaskPublicationActions.vue:194,226,356,401,485,503,515`；TaskPublicationActions/CodeMergeEditor specs；`publication-validation.spec.ts:3`。 |
| Task rolling package / decisions / judge approval | 调整未执行包、影响预览确认、只读 AI 建议、追加修正包的标题/目标 prompt；既有冻结包不可改；暂停/取消/人工认定明确确认、保留旧 verdict。 | `components/RollingPackageWorkbench.vue:115,186,223,237,246,317`；`TaskDecisionPanel.vue:66`、`TaskJudgeApprovalPanel.vue:26`；相邻 specs 和 TaskDetail `333,367,402`。 |
| Designer 历史任务设置/问题/草稿确认 | TaskProfileRouterDialog、覆盖快速模板、问题拒绝、自动模式风险、保存/确认/重载冲突、拆包/整体需求重讨论；新入口仍转需求；拒绝不等于强制停止。 | `views/DesignerView.vue:494,801,915,989,1099,1177,1244,1356,1405,1454,1536`；`components/TaskProfileRouterDialog.vue:117`；Designer spec `310,403,751,872,1128,1831`。 |
| Roles ZIP import / details disclosure | 导入区初始隐藏；保留 File；validate 后字段 diff 和明确确认才 publish；permissions/prompt/history 惰性 tab，历史/diff/绑定版本与最新发布版本分开。 | `views/RoleManagementView.vue:83,125,413,437,450,487,492,529,535`；Roles spec `158,196,218,250,280`。 |
| Tools MCP disclosure / skill document | 服务器/工具展开、整源禁用确认与版本；系统必需工具不可关；Skill 文件按需打开且安全 Markdown。 | `views/ToolsView.vue:40`、`components/McpToolPolicyPanel.vue:14,25`、`SkillBrowser.vue:28,43,56`；Tools spec `10,27`，McpToolPolicyPanel spec `8,16,25`。 |
| Knowledge 模型 picker / 小屏资料和引用侧栏 | 本地 role=dialog picker；精确 provider/model；左右 panel 切换、Tab/Esc/restore focus、分栏宽度恢复；引用点击拉实际 chunk/file，资料移除确认保留原文件。 | `components/knowledge/KnowledgeModelPicker.vue:19`、`KnowledgeSourcesPanel.vue:82`；`views/KnowledgeView.vue:93,100,101,102,113,126,147`；Knowledge spec `104,111,135`、E2E `214,305`。 |
| Inbox / Session 回答与拒绝 | permission 的 ONCE/SESSION/REJECT、multi/single/custom answers；session 切换、输出折叠、迟到响应失效；拒绝明确确认，不把 question 当任务完成。 | `components/PendingQuestionCard.vue`、`SessionMonitorPanel.vue:123,137,193,198,231,271`；Inbox spec `29,58,114`；SessionMonitorPanel spec。 |
| 模板补传/澄清/批次诊断/恢复 | Document supplement File、澄清答案、report/原文/coverage disclosures；只停止所选 session/batch，完成批次不重置；失联/停止未知保留阻断。 | `components/DocumentSupplementForm.vue`、`DocumentClarificationForm.vue`、`TemplateBatchRecoveryPanel.vue`、`TemplateSessionDiagnosticsPanel.vue:158`；对应组件 specs、Source/Document view specs。 |
| Workflow 节点 context / 菜单 / 保存模板 / 候选 / 写回 / 发布 / 推送 / 结束 | 二级菜单焦点；节点未知操作/上传在空白画布、Esc、关闭等入口有保护，普通 dirty 保留确认；部分子 owner 的 canLeave 仍允许确认后离开，须按流程专题 B2 分别验收；SaveTemplate 非模态 dialog；candidate read-only preview 与明确 apply；publication/push/writeback/finish 各原 key/version。 | `components/workflow/WorkflowSaveTemplate.vue:46`、`WorkflowNodeRun.vue:104,109`、`WorkflowCandidates.vue:26,30`、`WorkflowWriteback.vue:30,33`、`WorkflowPublicationCommit.vue:29,32`、`WorkflowPush.vue:39,42`、`WorkflowFinish.vue:40`；需求 spec `144,153,201` 与流程专题。 |
| PPT details / properties / plan editing | native dialog 的资料/历史/方案；object 与 slide properties、助手互斥 dirty；移除章节/设计明确确认；历史恢复和下载的实际 job 状态。 | `components/ppt/PptDetailsDialog.vue:38,48`、`PptPlanNarrative.vue:23`、`PptPlanEditor.vue:204`；`views/PptStudioView.vue:236`；PPT 专题。 |
| Settings 子表单与恢复入口 | 各 section 切换保留同一 settings draft；Git 全局停用确认、项目独立账号不受影响；模型发现失败可重试；canvas preference 不刷新、不导航、不使当前手势热切换。 | `views/SettingsView.vue:32,61,89,142`；`components/GitCredentialForm.vue:42`；Settings spec `39,90,163`、react-canvas-migration E2E。 |

## 六个 store：当前动作、DTO 和唯一 owner

目标是把状态与命令搬到 **Zustand 5.0.15 vanilla store / 纯 TS controller**；过渡 Pinia 只 delegate 同一个 controller，不保留一份可自行写入或自行订阅的镜像。React 使用 selector 读取快照，Vue adapter 读同一快照；订阅不发业务写。纯领域对象/API normalize 保留普通 DTO，不从新 store 输出 Vue proxy/Ref/File 的序列化替身。

DTO 边界从各 store 的真实 import 核对：Task 为 `Project/Task/TaskListItem/RuntimeInfo/Artifact/TaskEvent/DirtyWorkspaceAction`（`taskStore.ts:3–7`）；Knowledge 为 `KnowledgeConversation/KnowledgeMessage/KnowledgeCreate`（`:3–4`）；PPT 为 `PptDocument/Deck/Plan/Source/Job/Revision/Message/AgentStatus/Capabilities/Issue/Scope/Operation/Question/Generation`（`pptStore.ts:4–21`，由 domain re-export `types/ppt.ts`）；三个模板 store 分别为 `TemplateTaskCatalog/TemplateTaskRequest`、`DocumentTemplateOverview/DocumentTemplateRequest`、`SourceTemplateRequest`（各文件 `:3–4`）。这些普通 DTO、服务端 normalize 和稳定枚举保留；File、原请求快照、scope token 是 owner 资源，不序列化成新的服务端模型。

| 当前 store / return 锚点 | 对外动作（完整返回 action 集合） | 当前状态、身份和订阅 owner / 迁移合同 |
| --- | --- | --- |
| `stores/taskStore.ts:55,462` | `activateDemo`, `deactivateDemo`, `loadOverview`, `loadProjects`, `loadTaskSummaries`, `invalidateTaskSummaries`, `loadTaskOverview`, `loadTaskAudit`, `loadTask`, `updateTask`, `retryJudges`, `retryWaitingLoop`, `resolveDirtyWorkspace`, `cancelDirtyWorkspace`, `reworkTask`, `setTaskArchived`, `deleteArchivedTask`, `watchTask`, `stopWatching`, `refreshRuntime`, `restartRuntime`, `startRuntime` | projects/tasks/taskItems/runtime/artifacts/notices/loading/auditErrors/facets/cursor/demo/streamState/selectedTask；Task/overview/audit 单调 version 和每任务 request generation，摘要不把 source/document DTO 塞成 Task。`:403–422` 与 `taskEventSubscription.ts:5,18,28` 拥有一个当前 Task 流、两个 180ms 合并 timer。root controller 缓存，detail route lease 决定活动订阅；不能 Vue/React 各 watch。写动作由明确 UI 触发，Start、恢复、返工子任务仍各走原协议。tests `taskStore.spec.ts:127,159,174,226,242,258,272,306,320,345`。 |
| `stores/knowledgeStore.ts:6,97` | `reset`, `close`, `load`, `refresh`, `more`, `send`, `stop` | conversation/messages/pendingText/error/disconnected/loading/sending/active/cursor；`:17–20` epoch/关闭/receipt reconcile、`:28` 合并读、`:53` 单 SSE；`:76–86` 把原 `{key,text}` 放 `loopper.knowledge.pending.<conversationId>`，旧未知 text 未核对不能发新 text；load/route scope 分离引用预览。controller 独占消息去重/usage 单调值；问答草稿与资料筛选属于 route，不让模型刷新覆盖。已有 view/E2E 证据见路由表；API receipt/消息 tests 查索引。 |
| `stores/pptStore.ts:45,469` | `generate`, `confirmRequirements`, `resume`, `adjustAndResume`, `close`, `reset`, `load`, `refresh`, `operations`, `savePlan`, `action`, `send`, `reply`, `createJob`, `retryJob`, `retryPending`, `stop`, `check`, `more` | generation/document/deck/plan/sources/assets/jobs/revisions/messages/agent/capabilities/issues/checkedRevision、pending/busy/editable/cursors；`:151` 合并读，`:175,201` epoch+owner，`:259–279` 单 SSE，`:281–344` key/revision/accepted/unknown receipt；已接受写后读失败只读恢复，不重写。pending 身份按作品持久化；手动草稿和 server revision 分开。实际 action/controller 迁移和画布边界见 PPT 专题；`pptStore.spec.ts:90,108,130,146,193,236,258,306,319,342,369`。 |
| `stores/templateTaskStore.ts:6,26` | `loadCatalog`, `start` | catalog/submitting，内存 pending `{fingerprint,requestKey,taskId}`；`:12–22` create 取得 Task ID 后再 start，Start 丢响应不能再 create；明确新 run 才换 key。此旧通用模板 API 和现在 UI 转需求开发并存，不能把 source create 的 PENDING_START 混同此 create+start。tests `templateTaskStore.spec.ts:10,20`。 |
| `stores/documentTemplateStore.ts:22,53` | `start`, `restore` | submitting/previousRun，`loopper.document-template-upload.v1` 保存 fingerprint/requestKey/runId/completed；`:8–13` 1–10 份 DOCX/MD/Markdown/PDF、单份 20MiB/总 50MiB、非空；`:31–50` 用真实 File bytes SHA-256 冻结身份并 multipart。restore 按 requestKey 查询，只有 404 说明找不到；completed 不复用旧 run。File 不可靠 JSON 恢复，页面/操作 owner 必须保留原 File，不能以同文件名替代内容。tests `documentTemplateStore.spec.ts:15,26,34`。 |
| `stores/sourceTemplateStore.ts:14,31` | `create` | submitting；sessionStorage pending fingerprint/requestKey/completed；`:18` create 只创建待正式开始 run，不隐式执行；同输入未知结果复用 key，明确新 run 才换。SourceRequest/proposal 的 scope、project stack caps/验收由服务端持有。tests `sourceTemplateStore.spec.ts:8,16`。 |

页面 controller 并不等于新增第七个全局万能 store。Projects、Roles、Database、Tools、Inbox、列表各有当前页面 action owner；框架迁移只改变消费者和生命周期适配：

- 列表 owner 独占查询参数、cursor、generation、错误和 debounce；Router 是 URL 的唯一提交者，浏览器 back/forward 是输入。summary/project/runtime cache 可共享，不能由两个 UI framework 分别重新发同一写。
- 项目规范、角色发布、连接测试、Git 凭据写各拥有原输入快照、version/幂等 key、busy/unknown/accepted 状态；单条命令完成后再刷新。纯 read 可以 abort 或失效，已有写不能因取消显示组件而声称副作用撤销。
- `StoryAccountingDialog` 的 app-root 生命周期、`themes/state` 的 app-root storage 同步、Task detail 的活动 stream、Knowledge/PPT 的当前会话 stream、模板 run 的 stream、SessionMonitor 所选 Session 协调读分别有一个 owner/disposer。路由进入 adapter 只申请自己那一份 lease，退出只释放自己的 lease；禁止模块 singleton 清理其他实例。
- `api/client.ts` 的 normalize/Local UI headers、`api/workflow*` 和 `types/domain.ts` 保持框架无关；`domain/acknowledgedOperation.ts` 等领域回执不改成 React effect 自动重试。框架重放、route load、render、subscription 都不触发命令。

## 现存基线差异和待验证合同

这些是静态读到的基线差异或待验证风险，**不是本轮新 React 回归，也不是已经运行复现的缺陷列表**。W0 必须把预期写进迁移验收，不能通过删除保护测试或只测试 happy path 避开。

1. Editor 和 Requirement 详情已硬阻断 busy/unknown；RequirementNew 的 `:22` 仍允许普通 confirm 后离开未知创建，WorkflowLibrary 未设同等守卫，Source/Document run 的 pending 命令随 route 重置，Roles 的未知 ZIP 发布 key/File 仅在内存且无离开守卫。迁移应统一“保留原操作身份 → 明确重试或核对结果 → 安全离开”，需先明确各接口是否支持持久化/by-request 恢复；不可自行自动重发。Roles 现有 `RoleManagementView.spec.ts:280` 证明同页同 key，不证明离开/重入身份保存；新需求 `spec:57` 同理。
2. Projects 在 `:117–136` awaited 规范读取后重新排 timer，而 `:262` 只 clear 当前 timer；需要测卸载中读返回是否会重新排程。RecoveryStudio `:32,65`、TaskDesignHistory `:27,52,63` 未见同等 route generation/unmount 失效；迁移控制器要用跨 ID 迟到返回用例确认归属，不能只加 effect cleanup 就宣称业务隔离。
3. Roles export `:395` 的 60 秒 Blob URL 撤销 timer、各弹窗 CodeMirror/Markdown observer、SessionMonitor timer/尺寸观察器、全局 accounting 和主题 listener 必须归属清楚。全站卸载账本需要覆盖 listeners/timer/RAF/capture/ResizeObserver/订阅/Blob URL；账本为资源释放证据，不能称作 GC 堆保留测量。
4. Settings 目前保留 section 内 edits，但没有一般离页 dirty 保护；是否要求统一草稿离开提示应在 W0 行为合同中定下，不能迁移时静默丢输入或未经授权新增自动保存。KnowledgeHistory 当前 E2E 有历史过滤/返回/归档证据，缺相邻 view 单测；不应拿静态测试索引假称已有全部 race 覆盖。
5. Automations 旧 view 未被路由挂载，但其 template/version/rule/run/import-preview-confirm/API 仍实际存在。`AutomationsView.spec.ts:44,62,79` 明确保留 MANUAL 才立即触发、AUTO_START 需要版本授权、REVIEW_REQUIRED 明确确认、导入先 preview 后 confirm、webhook secret 只显露一次。W0 要形成“现行可达入口 / 兼容读写 / 归档展示”的功能映射，再决定新 UI 是否整合；框架退出不授权删除这些业务或历史数据。
6. 前阶段未执行的浏览器全集和旧失败不会因为 React 全站规划而自动通过。既有记录中的 11 项旧失败（数据库旧 host selector 2、文档外部 `/private/tmp` 截图 2、退役自动化读取入口 1、角色旧文案 6）须在验证专题逐项归档/修夹具/确认产品行为；本轮没有重跑，也没有修复这些失败。

## 可执行 Vue 技术债：源码、入口、锁、fixture 与工具链

以下均从当前仓库读取；旧 `package.json` 的范围不能当作实际安装版本。完整 Vue 家族引用/锁条目见 [source-inventory.json](source-inventory.json)。

| 层面 | 当前实际入口 / 锁定版本 | 最终退出所需证据 |
| --- | --- | --- |
| app / routing | `main.ts:1,4–7,42–46`；`App.vue`；`router/index.ts:1,5`；Vue **3.5.40**，Vue Router **4.6.4** | 原子换 ReactRoot/React Router Data 模式；删除 createApp、RouterView/Link、Vue 导航 hook 和 `.vue` 动态 import；31 条 route 含 guard/redirect/fallback 与 back/forward/刷新仍映射。过渡只有 VueRouter 一个 history。 |
| state | 六个 `stores/*Store.ts:1–2` 的 Vue/Pinia；Pinia **3.0.4**；`themes/state.ts:1` | Pinia delegate 过渡结束后删除 defineStore/storeToRefs/Ref/reactive adapters，清除 Vue module-level ref。所有读写使用一套 plain DTO/controller owner，命令 key 和 stream 不复制。 |
| UI library | `main.ts:3,9–33,45` 的组件注册/CSS；App locale；模板 `<el-*>`、消息/确认/prompt、`.el-*` scoped/global selectors；Element Plus **2.14.3** | 改为 Ant Design **6.6.5** 的受控 primitive 和 tokens/中文 locale；modal/portal/focus/loading/close contract 逐项验证；保留业务 class 和语义 tokens，移除只服务旧 Element DOM 的 CSS。不得保留包装后的 ElMessageBox 暗依赖。 |
| icons | `icons.ts:1–6` 注册 Vue collection、SFC 的 `@iconify/vue`；锁 **5.0.1**；本地 `@iconify-json/lucide` 和已有 ReactIcon | 全部 UI 图标走已有本地 Lucide ReactIcon，保留离线运行；移除 Vue addCollection/Icon，保留本地 JSON，不能替换成 CDN。 |
| unused direct package | package 第 31 行 `@vueuse/core` **13.9.0**，词法源码查找未发现直接 import | 退出 Vue 时删除该直接依赖及不再需要的 transitive Vue helpers；“未直接引用”不等于所有 transitive usage 已证明无关，需锁/实际产物图确认。 |
| hooks / types | `components/ppt/usePptAutosave.ts`、`usePptCreation.ts`、`usePptPanels.ts`；`composables/useKnowledgeModels.ts`、`useKnowledgeSplit.ts`；`components/workflow/command.ts`、`modelChoice.ts`；`migration/canvasRuntimeVue.ts`；`router/designerEntry.ts` 的 Router type | 迁纯 TS controller / React hooks，明确 setup/dispose；最终删除 Vue runtime 和 Vue 类型/注入器，Designer entry 逻辑保留为普通导航函数。纯 TS domain、API、图 projection/service 无须重写。 |
| runtime adapters | `migration/CanvasRuntimeSettings.vue`、Vue wrappers、7 Legacy；`react/bridge.ts` 的 island mount | W6 删临时 ReactRouteBridge、Vue canvas wrapper/Legacy/runtime preference 和 Vue injection；React canvas 作为普通组件进入唯一 React 根，保留原 lifecycle/resource 证明。bridge 若不再被其他真实挂载入口使用则退出。 |
| compiler / typecheck | package `build: vue-tsc -b && vite build`、`typecheck: vue-tsc -b`；vue-tsc **2.2.12**，`@vue/tsconfig` **0.7.0**；`tsconfig.app.json:2,3`；`env.d.ts` 当前 Vite 客户端类型 | 使用 React/TS config，删 `.vue` include、Vue tsconfig extends、Vue type checker；检查 ts/tsx、strict/noUnused/noUnchecked 保持，不能以降低 TS gate 代替迁移。锁中 `@vue/compiler-core/dom/sfc`、`@vue/server-renderer` **3.5.40** 与相关 language tooling 也要退出。 |
| Vite / first paint | `vite.config.ts:3,8` 同时 Vue/React plugin；Vue plugin **5.2.4**；Vite **6.4.3** / Vitest **3.2.7**；皮肤 compile/bootstrap 与 test setup | 删除 plugin-vue，保留 plugin-react、alias、first-paint skin 注入、jsdom resource setup 和 TSX include；保留现有控制器/图测试。不能通过不编译旧源码而漏发现依赖。 |
| unit fixtures | 静态 192 文件：178 `.spec.ts` + 14 `.spec.tsx`；其中 149 文件引用 `@vue/test-utils`，锁 **2.4.11**；createPinia/memory VueRouter/mount/SFC template stubs、`wrapper.vm/$emit` | 分波替换为真实 RTL/纯 controller 测试；导出 DTO/行为 contract 保持，不能把所有旧 Vue 集成断言丢掉。最终无需 VueTestUtils/compiler、内联 Vue fixture 或 Pinia 测试 app。静态文件数不是当前执行的 case 数。 |
| browser fixtures | `e2e/fixtures/SkinsPreview.vue`、`fixtures/skins.ts:1–9` 实际 createApp/Element；画布回退用例通过偏好实际进入 Vue Legacy；8 个 E2E 文件含 Element 选择器；resource ledger 中 Element RAF provenance filters。E2E 主逻辑未发现 VTU/RTL 或 `.vm`/`__vue` 探针，不能与 unit fixtures 混算 | 改真实 React skin fixture/导航流程；回退用例把原 File/key/dirty 断言映射到最终 React 页面恢复合同；保留原截图/键盘/三皮肤/协议场景。Element 技术选择器/特定 provenance 退役时资源断言不放宽，仍须证明新真实来源/身份，不能机械搬旧计数/闭包归属。 |
| build/packaging/tooling | `pom.xml:149,153,167,179,185,202` 的固定 frontend toolchain、npm ci/build/Vitest/static copy；package tooling/accounting/patch scripts | 按正式 Node **22.14** 范围选择 React Router **7.18.4**，不引入额外升级；更新必要 frontend commands/lock/工具断言，保持 Maven 完整静态资源打包、SPA API 边界、patch check 和安全会计门禁。不跳过前端或 Maven gate。 |
| 既有 React Flow cleanup 补丁 | package `postinstall:7`、`prebuild:13`；`scripts/patch-xyflow-react.mjs` 与两份锁定 manifest | 全站迁移仍保留两包六入口七局部片段的 fail-closed apply/check/ci 证据，不能换库版本或删除 postinstall 掩盖 RO 清理要求；具体来源与限制见 [RO 补丁交付记录](../../deliveries/react-canvas-all-cleanup/resize-observer-patch.md)。 |

源码中 Vue/Pinia/Router/Element/Iconify 的消失只是必要条件。锁文件 transitive/compiler、入口/Vite/plugin、内联测试 fixture、浏览器 skin fixture 和类型检查命令也都属于可执行残留；文档历史里的 Vue 字样、兼容 JSON 数据和稳定协议枚举不属于应删除的运行依赖。

## 本范围的模块波次和单一 owner 交接

统一目标栈为 Ant Design **6.6.5**、本地 Lucide ReactIcon、Zustand **5.0.15** vanilla/纯 TS controller、React Router **7.18.4 Data 模式**。此处是既定设计目标，不表示本轮已安装或上线。

| 波次 | 本盘点范围交付 | 命令/订阅交接与通过条件 |
| --- | --- | --- |
| W0 债务/合同 | 固定 31 route/27 page/28 physical view/187 SFC；旧入口重定向、Automations/SessionLifecycle 可达性映射；未知回执/草稿/权限/失败基线；现有行为 tests 的保留清单 | 明确每条写的 key/version/输入/accepted/unknown 语义、每条 stream/timer 的 owner/disposer；先区分已有保护与缺口，禁止不明业务删除。 |
| W1 基础 UI/controller | App chrome、navigation port、base UI/AntD tokens/中文 locale、状态错误投影、ReactIcon、主题 external store、全局 accounting、目录/凭据/安全文档/编辑器基础能力；六 store controller 基础 | Pinia 仅 delegate 单一 owner；临时 ReactRouteBridge 只使用 VueRouter 当前 history，React 页面注入 framework-independent navigate/replace/back/block 口，不再创建 React BrowserRouter。严格卸载、重放、两实例和迟到读/写测试。 |
| W2 低风险列表/系统 | Home、Projects、Tasks 列表壳、Insights、Roles、Runtime、Tools、Database、Settings；KnowledgeHistory/PptList/流程与需求列表/Designs 的只读目录部分按统一路由表先迁；具写操作页面仍须完整协议，不以“系统页”降低风险 | cursor/query/debounce owner、global runtime/project cache 统一；role ZIP/credentials/DB test/project convention 命令独占；先验证列表 URL/返回/权限/隐藏弹窗，再开放 route。Tasks list 此波迁，深度执行详情与 Inbox 仍在 W4。 |
| W3 模板/Knowledge/PPT | Knowledge 会话、TemplateTasks/Source/Document runs、PPT studio/forms；共享文档、报告、资料、File 恢复；接 W2 目录入口 | 单 conversation/run/document scope；unknown upload/send/start 不换身份；SSE 与 REST 合并读一套 owner；React 画布复用，不再从 Vue 反向挂载 island。模板/PPT具体分工见两专题。 |
| W4 Tasks | TaskDetail、Inbox、RecoveryStudio、TaskDesignHistory、Stage/Attempt/Judge/Queue/Session/audit/rolling package/publication/scope/dirty 等全部二级入口 | 保持 PENDING_START/Start/停止证明、人工认定/自动 judge 区别、server waitingReason、冻结回溯、原 Task 不被恢复操作修改；单活动 Task SSE，离开不停止全局 accounting。深度 race/未知写后读恢复验证后才切 page。 |
| W5 Requirements/Editor/Designer | Editor、需求 new/detail、Designer 显式历史；流程/需求列表与Designs在W2，此波做集成回归；图/布局/子面板/附件 scope | graph/layout 双回执、unknown/dirty/upload 导航保护、编译器/候选/确认与 Start 分离；历史 Designer 入口留在真实行为映射，旧表单不虚构新画布。 |
| W6 根替换/去 Vue | 所有页面 ready 后一次替换 ReactRoot + ReactRouter Data，删除 VueRoot/VueRouter/Pinia delegates/Element/IconifyVue/VueUse/Vue compiler/ViteVue/TestUtils/SFC fixtures/Legacy/临时 bridge/runtime；锁和测试构建链同步 | **只在此刻交换唯一 history owner**；deep link/back/forward/scroll/query/redirect/blocker/404 API fallback/全局 modal 同一轮验收。功能页可回退的过渡期结束后，不能留下暗中 Vue 生产 chunk。 |
| W7 全量验收 | 逐入口三皮肤、桌面/窄屏/键盘、隐藏弹窗、全协议失败/恢复/并发、构建/正式包/离线 | 当前旧失败独立处理，全面资源账本含 RO/timer/listener/RAF/capture/SSE；framework imports/lock/产物检查与真实页面行为共同证明框架退出。测试总数变化需逐项行为映射，不用数量增长替代覆盖。 |

## 框架退出的可审查证据

1. **路由行为**：31 条记录逐项对应，27 独立页面可在真实浏览器从导航/深链/刷新进入；全部 3 redirect、Designer conditional entry、可选 Knowledge path 的匹配顺序保留；query/scroll/back/forward 和失效 ID 的可恢复错误可见；页面 blocker 能保留原 File/draft/key。服务端 SPA fallback 对 API/静态资源的既有边界不变。
2. **单 owner**：新旧过渡消费者读同一 controller；同时 mount/StrictMode/route repeat 不产生第二条写、第二订阅或第二 history。请求已接受后的读失败只核对结果；未知状态保留原 key/输入；跨 scope 迟到响应不写新页面；dispose 先失效再释放本实例资源，不能广播全局 mouseup 或删除他人监听。
3. **UI 能力**：表中隐藏 modal/disclosure 一一通过；保留焦点/不可关闭、中文状态/错误、长内容和小屏、三皮肤首帧、离线 icons、Markdown/foreignObject 安全、CodeMirror 释放。审批、scope、Role/MCP/native 能力、Git/数据库 secret、Task/Stage/Session/Judge 等领域差异不被 AntD 表单合并。
4. **技术退出**：生产源码/入口不含可执行 Vue-family import 或 `.vue`，包和锁无 Vue/Pinia/VueRouter/Element/IconifyVue/VueUse/compiler/TestUtils 所需残留；Vite/typecheck 纯 React；旧 fixtures 不执行 Vue；实际 bundle/metafile/网络 module 图不加载 Vue 家族。保留必要本地 Lucide JSON、普通 domain/API/Markdown/图引擎，并验证最终产物，不仅 grep package.json。
5. **验证诚实**：每个已有 Vue 行为断言有 React/controller/E2E 去向；退役业务/测试有明确功能映射和原因。全站单测/类型/构建/工具/协议/浏览器/正式包分别记录真实命令和结果；资源释放不冒称 GC heap-retention 证明。当前文档只是计划证据，不写未来通过数。

本轮交付是这份盘点和对应静态索引，未实施上述迁移或修复基线差异；后续以统一设计、专题协议和各波可复验行为为开发边界。
