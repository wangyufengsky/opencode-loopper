# W6：31 条原路由与唯一所有权

基线 `2186deafdfc6d8bfbd6047ea2b3b8853a5296683`；31 个路由记录保持为 28 个页面记录与 3 个 redirect/fallback 记录。记录数不等于不同 React 组件数。生产入口为 `frontend/src/main.tsx → app/bootstrap.tsx → RouterProvider → ApplicationLayout/RouteScreen`；`router/index.tsx` 的 `createBrowserRouter` 是唯一 history 所有者。没有第二个 Vue Router、Pinia 或嵌套生产 React root。

| 原 path（最终保持） | 实际 React 页面 / 等价出口 | 状态与命令归属 |
| --- | --- | --- |
| `/` | W2 HomePage | Home 只读 controller |
| `/projects` | W2 ProjectsPage | Projects、目录及项目约定各原 controller |
| `/ppt` | W2 PptListPage | PPT 列表与创建原 controller |
| `/ppt/:id` | W3 PptStudioPage | `createPptStudioController`，原 React 自由对象画布 |
| `/knowledge/history` | W2 KnowledgeHistoryPage | 历史只读 controller |
| `/knowledge/:conversationId?` | W3 KnowledgePage | `createKnowledgeController`，原消息/附件/stream 合同 |
| `/designer` | W5 DesignerPage | `createDesignerController`；无 session 先执行原 guard |
| `/requirements` | W2 RequirementListPage | 需求列表 controller |
| `/requirements/new` | W5 NewRequirementPage | 创建 owner 的原四字段、key/body，不含上传字段 |
| `/requirements/:id` | W5 RequirementPage | 原需求/候选/规划 controller，React Flow |
| `/workflows` | W2 WorkflowLibraryPage | 工作流库 controller |
| `/workflows/new` | W5 WorkflowEditorPage | 原 graph/layout/save-as 分段 owner |
| `/workflows/:id` | W5 WorkflowEditorPage | 同 controller 的指定工作流作用域，原 React Flow |
| `/designs` | W2 DesignerHistoryPage | Designer 历史只读 controller |
| `/tasks` | W2 TasksPage | Task 列表 controller |
| `/inbox` | W4 InboxPage | 原 Inbox controller；不是新增服务端领域 |
| `/insights` | W2 InsightsPage | 原 insights 读取 controller |
| `/automations` | redirect `/template-tasks` | 保留 query/hash；历史合法 read/export 在目标页，不复活旧写 API |
| `/template-tasks` | W3 TemplateTasksPage | 模板目录及历史合法消费者 controller |
| `/template-tasks/document-runs/:id` | W3 DocumentTemplatePage | 文档 run controller |
| `/template-tasks/source-runs/:id` | W3 SourceTemplatePage | 源码 run controller |
| `/tasks/:id` | W4 TaskDetailPage | App 唯一 Task TS owner；详情、publication、session 等按原作用域持有 |
| `/tasks/:id/recovery` | W4 RecoveryStudioPage | 原 Recovery controller，不把断开 stream 当停止 |
| `/tasks/:id/design` | W4 TaskDesignHistoryPage | 原冻结设计读取 controller，React 只读图 |
| `/runtime` | W2 RuntimePage | 原 Runtime controller / 服务端版本 DTO |
| `/tools` | W2 ToolsPage | 原工具配置 controller |
| `/databases` | W2 DatabasePage | 原数据库连接 controller |
| `/settings` | W2 SettingsPage | 原设置 controller；换肤仅 TS snapshot |
| `/settings/roles` | redirect `/roles` | 保留 query/hash |
| `/roles` | W2 RoleManagementPage | 原角色读取/版本写 controller及只读 React 图 |
| `*` | redirect `/` | 未知 path 的旧 fallback；丢弃未知页 query/hash |

## 保护边界

- App owner 在 bootstrap 创建，位于 render/effect 外；Task owner、Story Accounting owner 各仅一个。RouteScreen 传入稳定 navigation/lifecycle/task port，不重新创建旧 store。W2–W5 生产 loader 直接加载 React 页面。
- 页内点击导航及 React Router 同文档 POP 使用同一 leave coordinator。普通 dirty 明确确认，确认后重新读取 pending/unknown；审批不可缓存成无条件通行。竞争导航以 epoch 隔离迟到批准。query/hash 保留在目标身份中；Designer guard 沿用原纯 TS 解析/深链迁移。
- pending/unknown 不退休原 owner，不丢 File/draft/key/body/id/revision；accepted 仅合法 readback。关闭 UI 不等于取消/停止服务端。跨文档 refresh/离开使用原 beforeunload 阻断；浏览器原生确认不能保证跨刷新保存内存 File，也不能代替服务端停止证明。
- 退出按实例释放资源。异常 cleanup 会继续释放其它自有资源并将 App 标为 unhealthy；已退休的失败 App 仍卸载 React root，不能启动第二个 writer。HMR 有同样 fail-closed 路径，实际热更新浏览器场景未运行。
- App 的 reduced-motion MQL 生命周期跨页面，页面退出不应删除它。最终资源账本以锁定 provider SHA、构建产物的精确回调、media 与 App/RouteHost 身份共同证明归属；未知回调仍保守归属页面。另有真实负控证明未知监听使严格 gate 失败。App 根退出的独立测试要求所有账本监听及 timer/RAF/observer target/capture/SSE 归零。

## 实际验证与边界

`e2e/w6/production-routes.spec.ts` 针对表中 31 记录 × 3 皮肤测试真实直达、刷新、SPA 离开首样、后退、前进、main 键盘焦点、零隐式写；另有 Designer 无 session guard、两项真实同文档 dirty/unknown POP、未知 MQL 负控。这里 optional Knowledge 记录的三皮肤直达样本是已有 conversation；不将它冒称覆盖每个可选参数组合。

`e2e/w6/root-cleanup.spec.ts` 对 Projects、Task、Knowledge、PPT、Workflow、Designer 六类真实 bootstrap 分别三轮 StrictMode 挂载/根卸载，在同一 evaluate 内取第一样，不等待后续鼠标事件；另取延迟样仅检查迟到写入。手势中的 route/root 退出、105px 首帧、锁定/新布局、PPT 草稿等沿 W2–W5 实际生产 browser 回归核对。

这份表是 W6 入口及框架退出证据。W7 全站完整旧 E2E 的最终门禁尚未执行，真实后端/模型/文件解析、跨刷新完整持久恢复、全部 GC 对象回收与设备触控均不据此宣称验证。
