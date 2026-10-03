# W2：需求列表、流程库、历史设计与角色管理

基线：`6ffc7dbef1302450e900979d5484d4f3b3ebfa80`，分支 `feat/react-full-migration`。本模块作者为原 `/root/react_flow_workflow`；原启动记录显式指定 `gpt-6.1-sol / xhigh`，当前工具不能实时读取平台配置。本报告记录作者实施与聚焦自测，**不能代替非作者审查或真实浏览器验收**。

## 生产接入与所有权

| 原路由与 Vue 入口 | 实际 React 入口 | 保留的业务接口 |
| --- | --- | --- |
| `/requirements`，`WorkflowRequirementListView.vue:15` | `workflow/RequirementListPage.tsx:12`，`requirementListController.ts:10` | `workflowRuns.list/projects/project`；项目过滤、游标追加、新建项目查询、需求画布深链 |
| `/workflows`，`WorkflowLibraryView.vue:17` | `workflow/WorkflowLibraryPage.tsx:12`，`workflowLibraryController.ts:13` | `workflowApi.list/copy/archive`；服务器搜索与来源过滤、内置只读、复制冻结版本、删除 CAS 与确认、创建与使用流程深链 |
| `/designs`，`DesignerHistoryView.vue:136` | `workflow/DesignerHistoryPage.tsx:13`，`designerHistoryController.ts:43` | `api.listDesignerHistoryPage/getProjects`，真实项目/状态/归档/排序/关键词/游标；继续与修改、已确认任务深链、归档恢复、重试停止 |
| `/roles`，`RoleManagementView.vue:177` | `roles/RoleManagementPage.tsx:31`，`roleManagementController.ts:23` | 原目录、角色详情、绑定、权限预览、Prompt、版本与差异、ZIP 导出/校验/发布接口，`RoleDiagram` 原样复用 |

上述入口从各自 `index.tsx` 导出，供组长的 `W2RouteBridge` 实际路由映射使用。页面消费 `W2PageProps`，不创建 history，不导入 Vue/Pinia，不修改 API、原 store 或已验收画布。可注入同一 controller，供整页宿主和相邻行为测试复用，默认使用真实生产 API。

`workflow/pageParts.tsx:9` 统一 `useRetainedOwner + useLeaveGuard + attachView`：StrictMode 的 effect 清理只释放读租约，实际 bridge/root 生命周期才 `retire(true)`。`controllerCore.ts:21` 复用 W1 `createSnapshotController/createOperationOwner`；各读请求同时检查租约身份和频道请求代次。角色导出的 URL/计时器归当前读租约所有，迟到 Blob 不下载，最后租约释放即时清理（`roleManagementController.ts:130`）。没有全局监听、全局资源清除、私有 React Flow store 操作或自动 mutation。

## 原断言到 React 的映射

以下是功能与断言的映射，未声称每条旧 Vue 测试已原样执行 React。相邻 React 测试覆盖项按实际行号列出；默认路由、三皮肤、桌面布局、原有浏览器用例由组长/C 独立验收。

| 原测试锚点 | 新源码/测试锚点 | 本模块实际覆盖与范围 |
| --- | --- | --- |
| `WorkflowRequirementListView.spec.ts:22` | `requirementListController.ts:14`；`workflowPages.spec.tsx:54` | 原 cursor 传递，换项目从空 cursor 重读；完成中文状态；选中才出现画布入口；新建保留 projectId |
| `WorkflowRequirementListView.spec.ts:36` | `RequirementListPage.tsx:21`、`:39` | 错误、重试、加载与空列表分别呈现；源码保留，本模块未另执行完整错误→重试 DOM 变体 |
| `WorkflowLibraryView.spec.ts:19`、`:51` | `WorkflowLibraryPage.tsx:19`；`workflowPages.spec.tsx:65` | 详情与高级操作按选择发现，内置只读仍可复制/使用，不能删除；Escape 收起并返回选择焦点。呈现从每卡菜单变为统一 context，未沿用旧菜单 DOM |
| `WorkflowLibraryView.spec.ts:32`、`:38` | `workflowLibraryController.ts:23`、`:34`；`workflowPages.spec.tsx:73` | 搜索/来源过滤不更改已捕获 copy 正文或 key；UNKNOWN 显式恢复同 key/body；接受后导航失败不再 POST |
| `WorkflowLibraryView.spec.ts:46` | `WorkflowLibraryPage.tsx:42`；`workflowPages.spec.tsx:82`、`:88` | 原 displayed expectedVersion；删除有真实 UiConfirmDialog；接受后读取失败只重读；409 定义为拒绝并保持可见错误。controller 测试直接调用已确认动作，取消确认浏览器变体由独立验收补齐 |
| `DesignerHistoryView.spec.ts:58`、`:78` | `designerHistoryController.ts:13`、`:19`；`workflowPages.spec.tsx:30`、`:42` | 原深链过滤投影、状态 FAILED 兼容映射、服务器 facets；继续携 sessionId/projectId，修改额外 mode=edit；无 key 的归档 UNKNOWN 只查同 session，绝不重复 PUT |
| `DesignerHistoryView.spec.ts:100`、`:122`、`:138` | `designerHistoryController.ts:23`、`:34`；`workflowPages.spec.tsx:30` | 已确认仅可查看任务设计；CANCELLED 只读；STOPPING 只展示原 retry-stop 入口；真实 stop/archive/restore API 保留。恢复与部分停止回执的全部 DOM 组合未另作作者单测 |
| `RoleManagementView.spec.ts:54`、`:78`、`:95` | `roles/rolePresentation.ts:8`；`rolesPages.spec.tsx:47`、`:59` | 策略、授权、运行时、发现、任务作用域阻断保持；配置数量不能称可用；实际旧绑定 preview 不并入最新版本工具声明，中文说明与来源保留 |
| `RoleManagementView.spec.ts:125` | `roleManagementController.ts:42`；`rolesPages.spec.tsx:81` | 失败 next 原 cursor 重试；成功才推进 previous 栈，previous 原 cursor 返回 |
| `RoleManagementView.spec.ts:142`、`:158` | `roleManagementController.ts:90`、`:214`；`rolesPages.spec.tsx:65`、`:71` | 未绑定角色从 manifest allowedSlots 选阶段；项目作用域 preview；选中仅 detail，打开 tab 才取 Prompt/history；静态变量、专业节点说明、旧版对最新差异 |
| `RoleManagementView.spec.ts:196` | `RoleManagementPage.tsx:85`；`rolesPages.spec.tsx:36` | 最新发布与实际旧绑定版本分开；直接真正 `RoleDiagram`/React Flow；阶段版本入口仍读取该绑定 revision，未换成 bindingVersion 或假图 |
| `RoleManagementView.spec.ts:218`、`:250` | `roleManagementController.ts:150`、`:166`；`rolesPages.spec.tsx:87`、`:101` | 原 File 实例、SHA、激活 CAS、可核对字段差异、明确 checkbox；无 diff 禁发布；409 留原 File，清旧 preview/确认，重新校验同包 |
| `RoleManagementView.spec.ts:280` | `roleManagementController.ts:166`、`:190`；`rolesPages.spec.tsx:87`、`:109`、`:116` | UNKNOWN 同原 File/body/idempotencyKey 显式恢复；SENDING/UNKNOWN 禁换文件、校验、新发布/丢弃；接受后目录/绑定读失败只重读；最后租约 detach→remount 不自动发布或换身份 |

角色 Prompt 复用纯 TS `utils/rolePrompt.ts`；权限与工具标签复用 `utils/rolePresentation.ts`；静态配置不伪装历史会话完整 Prompt，不引入在线角色编辑，不扩权。角色及 ZIP 读错误放在 context 外，关闭详情不隐藏恢复状态或丢 File。

## B8.1 原四红合同

原四场景在 W0 基线 `templates-history-w0.spec.ts:239` 记录真实 Vue 红。此次修复为生产 `designerHistoryController.ts:51`、`:65`：查询变更立即使旧请求失效，不等 180ms；旧追加不能进入新过滤；root 退休后成功/错误均不能改快照或复活 debounce。

`workflow/designer-history-w0-contract.tsx:12` 是仅测试使用的共享合同。组长将旧 W0 四 case body 定向到此 helper，原标题、case 数、B 列表、facets=7、B cursor、旧 A cursor 请求、卸载前后 rows/error/loading、readCalls=1 和 debounce 等待均保留。本作者没有修改旧 W0 文件。

helper 使用真正 `DesignerHistoryPage + FoundationProvider`。真实 `RouteLifetime` 的 root cleanup 消费 `lifecycle.retain` 并退休 owner，**先**断言 `owner.capture().isCurrent() === false`、采首快照，**再**放行迟到请求与 200ms 计时；不能只靠 finally 退休来证明退出，也不能把 effect 的 detach 当 root retire。新相邻四测试在 `workflowPages.spec.tsx:25`。

## B1.2 原四红与一项正控

原 `workflow-w0.spec.ts:123` 的 copy/archive × sending/unknown 四项离开红与原第五项过滤恢复正控，由组长仅定向 case body 到 `workflow-library-w0-contract.tsx:22`；本作者未改旧 W0 文件、标题或 case 数。新相邻 `workflowRouteContracts.spec.tsx:10` 的五项实际使用生产 `W2RouteBridge`、真实动态路由 loader、`WorkflowLibraryPage` 和唯一 MemoryRouter；没有替换 guard、navigation.go 或页面 controller。

helper 首先等待真实动态模块加载并检查 `data-react-page="object.workflow"`，再经实际选择、复制或真实 Ant 删除确认开启原 transport。四项均断言请求一次、真实 SENDING/UNKNOWN 标记、`router.push('/away')` 后仍在 `/workflows` 且 React 页面未卸载（`:81`）。第五项通过实际 Ant combobox 选择“我的流程”，断言 `list('', 'CUSTOM', '')`，显式恢复后 copy 请求与原正文/key 完全相同（`:64`）。所有未 mock 请求仍遵守外围测试的离线 transport 边界。

首轮五项仅因 `flushPromises` 未等待 Vite 动态 import 而在“缺原 list transport”停住，是测试夹具失败，不能计业务红。修正为 `vi.dynamicImportSettled` 后五项通过，未放宽业务断言；B8.1 DOM 选择名称也按原 fixture 的真实 `goal`，不将“设计B”强制改成“B”。

## 恢复与离开合同

- 普通操作通过原 `go/back` 和唯一 router；未确认操作的 `canLeave` 优先 BLOCK，不能通过 dirty 确认绕过。
- copy 捕获 sourceRevision/title/requestKey，archive 捕获 expectedVersion；未知显式同身份幂等恢复。copy 接受后用实际 `goAccepted` 精确回执目标交接；false/reject 保留 receipt，不重新复制（`workflowLibraryController.ts:34`）。
- 历史归档/恢复/停止没有原幂等 key。UNKNOWN 仅显式 `getDesignerSession(同id)` 核对实际 archived 或独立 CANCELLED；未证明则保留 UNKNOWN/BLOCK；不盲重发，不用乐观时间或计时器伪造停止（`designerHistoryController.ts:82`）。
- ZIP File 留在 controller closure 与 `OperationOwner.files`，不进入 DTO。409 重新校验前保留原文件；UNKNOWN 不许替换/丢弃/重校验，接受后只重读。同视图 owner 重挂不自动重发（`roleManagementController.ts:166`）。
- 仅收起 context 保留 owner/File，故可允许；未发布 File 普通路由离开需确认，pending/unknown/accepted 未恢复先 BLOCK。没有增加跨刷新保存 File 或无支持恢复的承诺。
- forced root 退休先使读与写回失效；迟到 definitive 409 不启动新绑定 GET 或写页面（`rolesPages.spec.tsx:146`）。

## 实际作者验证

命令（frontend cwd）：

```sh
node_modules/.bin/vitest run src/pages/w2/workflow/workflowPages.spec.tsx src/pages/w2/workflow/workflowRouteContracts.spec.tsx src/pages/w2/roles/rolesPages.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w2-evidence/workflow-roles-freeze31.json
```

实际 **exit 0，31/31 PASS（workflow 12 + library 实际路由 5 + roles 14）、0 skip、无未处理异常**。日志 `/workspace/react-full-w2-evidence/workflow-roles-freeze31.log`，完整 JSON 同名；冻结后该次日志 SHA256 `12f9b1de95844de20340b48ffe90577bb267b4dde37eb439618e0ae36ff176c2`、JSON `05919b54f3c47ad8fb6c515b78c58addc95ce916e13ac15509fb385c6a13b851`。首轮 workflow 8 个错误来自新增共享 `guardChanged` 未补测试 fixture，不计生产回归；roles 首轮 1 个错误来自文本同时命中 heading/tab，已用准确 heading 修正，不放宽业务断言。之前 26 项候选及夹具失败日志均保留。

临时 `/tmp/w2-workflow-tsconfig.json` 的早期纯 TS 模块检查通过；新增真实 `.vue` bridge 测试后普通 `tsc` 不解析 SFC，故该次退出 2 不作最终类型门禁。新增 helper 的 `VueWrapper.element` 查询已显式收紧为 HTMLElement；实际 Vue/React 全工程 typecheck、build、浏览器由组长统一执行。本作者没有启动服务或浏览器，没有 API/后端真实请求，没有安装依赖或提交。

## 独立验收边界

五项作者测试证明实际生产 bridge 下的 MemoryRouter push 离开保护，仍未证明 Chromium history 导航、原 sidebar/popstate/beforeunload 拦截、Chromium 文件选取与下载、三皮肤像素及 1280/1440 桌面密度、所有高级操作的原浏览器回归、已验收 Flow 全资源门槛。上述由组长/C 实际默认路由验收；原 Vue/W0 红证据保留，其他波次正确红不转为通过。本节后续只记录真正非作者证据，不把作者 31 个绿算独立通过。

## B core 非作者独立审查

此部分作者仍为 A，审查 B 编写的 `core/**`，没有修改其源码或测试。只读核查原 API、Task store/bridge、五个 controller、三页 React UI 及相邻测试，再对 B 正式冻结的两个 spec 实际复跑。运行前后 16 个文件均匹配 `/workspace/react-full-w2-evidence/core-freeze-hashes.json`；该账本 SHA256 `26e8a244aeffaa74ccea67a5d8a0d5c1eebef56f6cebc314903128b231398185`。

先报告的三项为 **P2 静态合同差异**，没有在修前运行出红，不能写成已复现的前后红绿。作者最小修正后，以下负控在本次非作者执行均通过：

| 发现与归属 | 最终源码 | 实际独立负控及结论 |
| --- | --- | --- |
| 项目本地切换确认未捕获草稿修订；同类 context 关闭也需 owner 核对 | `ProjectsPage.tsx:17`、`:34`、`:140`；`CoreUi.tsx:15` | `pages.spec.tsx:118` 真正 ProjectsPage：登记 dirty→选择项目→打开确认→retained 原 controller 发布新草稿，旧确认禁用、草稿和原 context 保留、0 create；`:238` 对真实 CoreContextPanel/owner 验证相同边界。没有解除禁用或 emit 禁用字段。P2 关闭 |
| 模型目录 fallback 改 provider/model 未推进修订 | `settingsController.ts:43`、`:49` | `controllers.spec.ts:84` deferred 真 transport 回来改变默认模型后，canLeave 的 revision 增长且新 provider/model 保留。UNKNOWN 时拒字段投影由源码锁检查保障；本项未冒称另跑了 UNKNOWN/catalog 并发子变体。P2 关闭 |
| 父清理异常跳过凭据子 owner 退休 | `projectsController.ts:243` | `controllers.spec.ts:216` 保留真实 ResourceScope，仅注入一个 owned disposer 抛错；父/子 token 均失效、子 secret 清空、后续 save 0 write，最终仍报告清理异常。P2 关闭 |
| 组长先发现的已退休 owner 仍能创建新 mutation | `owner.ts:63`、`:72` | `controllers.spec.ts:31` 实际退休后 create/update credentials/unmanage 均 0 write；注册失败显式退休新 operation 后返回 false。该发现不算 A 首报；此测试未单独构造 subscriber 重入导致注册失败 |

其余协议核查及实际覆盖：

| 核查边界 | 源码/API 与实际测试 |
| --- | --- |
| no-key UNKNOWN、accepted 只读恢复 | `settingsController.ts:92` 用真实 `/settings` PUT + GET 核对全部 writable DTO，不虚构 key/CAS；`controllers.spec.ts:57` 同原 identity、0 新 PUT，`:67` accepted 后 runtime 读失败仅再读。`projectsController.ts:213` 的生成 POST 无原 draft 身份，不虚构 lookup，不自动再生成；`:214` 的 UNKNOWN stop/apply 恢复仅读原 draft；`controllers.spec.ts:202` 覆盖 UNKNOWN 生成不能切页或重写 |
| 凭据与版本 | `credentialsController.ts:53` 保留原 version、body；`:56` 变 secret 的 UNKNOWN 禁用 metadata lookup，保持 BLOCK；真实 API 在 `api/client.ts:1635`；`controllers.spec.ts:123` 原 PUT 一次、GET 仍一次，不能用空秘密 DTO 假证明；`:130` 409 留草稿、demo 无写/验证。实际 UI `pages.spec.tsx:207` 验证未保存 draft 测连接不保存、保存成功清输入、没有 Settings PUT |
| 任务列表与实际 mutation owner | `tasksController.ts:117` source/document 原版本与 requestKey；`:125` 普通 Task 复用既有 TaskPort；`:137` 删除未知仅精确 GET 404 可核对。API 源码 `api/client.ts:1623`、`:1666`、`:1857` 与原 store 路径相符。`controllers.spec.ts:238` 的两变体、`:250`、`:286` 实测接受读失败不重发、显式 retry 同正文/key、普通未知只读同 Task。没有增加 watchTask/EventSource |
| 租约、迟到读与停止证明 | `useCoreOwner.ts:6` Strict effect cleanup 只 detach，实际 root 使用 shared retain；`projectsController.ts:181` poll 受租约/代次约束；`tasksController.ts:58` 最后租约清 clock/debounce 并 invalidate。`controllers.spec.ts:183` 等独立 CANCELLED 才允许离开，`:194` GET 迟到不重开 poll、不调用 activity；`:273` replay 租约后 timer 清零。`pages.spec.tsx:42` 真 StrictMode Task 页只读一次、无额外 watcher；不是所有 root 三循环资源账本的证明 |
| 高级入口与呈现 | `pages.spec.tsx:88` 等实际选中项目才显六入口、深链，文档路径/目录选取保手工输入，Git/GitLab 来源保持 scope/version；Settings 全高级 runtime/model/limits/retry/publication 字段可达。`CoreUi.tsx:69` Markdown 经 DOMPurify、真实 React Mermaid；`:233` 测 script 不进入 DOM与安全外链。没有把测试中的 synthetic token 当真实凭据记录 |

实际命令（frontend cwd）：

```sh
node_modules/.bin/vitest run src/pages/w2/core/controllers.spec.ts src/pages/w2/core/pages.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w2-evidence/core-independent-workflow.json
```

**独立 exit 0，58/58 PASS（controller 35 + page 23），0 skip、无未处理异常**。日志 SHA256 `cba54fe5deb6b9a35d7e42e1ea5a6e6711ea94dd98e482ba6cfafc93ff300d89`；JSON `06665a6330f6fd696f014bf04259f17fa683c7cbb68b013334a9b68222968aba`。测试源码 SHA：controllers `b2e204679c6664ccec62dddf6153b6e3c6b0fad4fba940888b52e28a269fed6b`；pages `bb70da395a170f9db573dc4614555fed8f6317835f7b456a3758983d7356cb13`。

结论：本次只读源码与冻结 58 项单测范围内，没有未修复的 core 阻塞。此批使用 mock transport/TaskPort，未验证真实后端、系统目录选择器、网络凭据、Chromium history/focus、全部资源首采样或三皮肤像素；父/子清理抛错负控不能代替所有资源异常组合。core 没有 File 输入，本审查不虚构此模块的 File 用例，也不将 A 自己 roles 的 File 自测计作 B 独立证据。浏览器、真实 bridge 集成与最终全量仍归组长/C。
