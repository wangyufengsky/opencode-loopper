# React 画布迁移交叉验收

本轮从 `63d2462f7b69d76bf1a27c9aa597be086c69ccfd` 开始，在 `feat/react-canvas-migration` 本地分支交叉审查与修复。原迁移基线为 `c26bf3bf7590424bd95bf83c093ae5740a068572`。本目录只包含审查说明与明确的模拟数据截图，不包含环境日志、trace、凭据或外部投递记录。准备本地交付不代表已获得推送、PR 或部署授权。

**所有图片均为 Chromium 实际浏览器渲染，使用模拟 API 数据。** PPT 预览制品本身也是测试 fixture；图片不证明真实后端、真实模型或真实文件解析可用。修复代码提交为 `628ab18d88173910139dd9709054b17d10e534cb`；33 张本轮新截图随本文收录在仓库内。

最终全量单测 187 个文件、1198 项通过；关联 Chromium 158 项按最终结果去重后，152 项通过、6 项既有失败。**不是全站全绿：11 项原始基线失败独立保留，且 React Flow 活动连线在卸载瞬间的 4 个上游监听仍有清理限制。** 下文分别说明修复、已验证行为和未满足门槛。

## 独立分工与实际回执

复用原有三名 `gpt-6.1-sol / xhigh` 组员；没有新建组员或改变模型。三次 `followup_task` 均获工具回执，随后 `list_agents` 确认三人同时处于 `running`。以下为工具返回的 canonical task 名称，不虚构另一个数字 ID。

| 审查者任务 | 原实现范围 | 本轮独立审查范围 |
| --- | --- | --- |
| `/root/react_flow_workflow` | 工作流 React Flow | PPT React 对象画布、导航、原 store、草稿/回执恢复与回退 |
| `/root/react_ppt_canvas` | PPT、跨需求命令作用域 | Task、模板、角色 React Flow；Designer/共享 Mermaid；独立核对 11 项基线失败 |
| `/root/react_legacy_canvas` | Task/模板/角色/Mermaid | 工作流节点/连线/坐标/视口、回执及跨需求隔离、导航保护 |

组长持有共享桥接和运行时偏好、浏览器调度、整体验收、文档与本地提交。各组员的原模块自测不计为独立评审。修改后的单元测试和浏览器证据均重新采集。

## 完整入口与真实渲染链

路由权威来源是 [router/index.ts](../../../frontend/src/router/index.ts)。默认路径是 Vue 页面持有唯一业务状态和 Router，经 [createRoot 桥接](../../../frontend/src/react/bridge.ts) 挂载真实 React 组件；`Legacy.vue` 只出现在明确选择兼容画布后的互斥分支。React 内没有挂载 Vue 画布。

| 实际入口 | 真实渲染与交互 | 源码证据 |
| --- | --- | --- |
| `/workflows/new`、`/workflows/:id` | 新建、自定义编辑、内置只读；React Flow 节点、边、句柄和视口 | [WorkflowCanvas 适配器](../../../frontend/src/components/workflow/WorkflowCanvas.vue)、[WorkflowCanvasReact](../../../frontend/src/react/workflow/WorkflowCanvasReact.tsx) |
| `/requirements/:id` | 规划、执行、调整计划、待确认/历史候选预览；同一 React Flow | [需求页面](../../../frontend/src/views/WorkflowRequirementView.vue) |
| 需求页“另存为流程模板” | 当前/首次结构的只读图与上下文预览；同一 React Flow | [WorkflowSaveTemplate](../../../frontend/src/components/workflow/WorkflowSaveTemplate.vue) |
| `/ppt/:id` | 自由对象移动/尺寸/选择；React JSX 按原 PPT 几何和修订号发 patch，不套节点流程模型 | [PptCanvas](../../../frontend/src/components/ppt/PptCanvas.vue)、[PptCanvasView](../../../frontend/src/react/ppt/PptCanvasView.tsx) |
| PPT 当前/历史制品及缩略导航 | React 图片/对象预览与缩略页；原 URL/修订来源不变 | [PptSlideNavigator](../../../frontend/src/components/ppt/PptSlideNavigator.vue)、[React 导航](../../../frontend/src/react/ppt/PptSlideNavigatorView.tsx) |
| `/tasks/:id` 普通任务 | 阶段投影；真实 React Flow | [StageRail](../../../frontend/src/components/StageRail.vue)、[StageDiagram](../../../frontend/src/react/diagrams/StageDiagram.tsx) |
| `/tasks/:id` 模板任务 | 模板步骤与折叠阶段详情；真实 React Flow | [TemplateTaskProgressPanel](../../../frontend/src/components/TemplateTaskProgressPanel.vue)、[TemplateStepDiagram](../../../frontend/src/react/diagrams/TemplateStepDiagram.tsx) |
| `/roles` | 角色阶段图及绑定版本操作；真实 React Flow | [RoleWorkflowDiagram](../../../frontend/src/components/roles/RoleWorkflowDiagram.vue)、[RoleDiagram](../../../frontend/src/react/diagrams/RoleDiagram.tsx) |
| `/designer?sessionId=…` | 历史文档 Mermaid，React 管理实际 SVG/主题/错误/卸载 | [DesignerView](../../../frontend/src/views/DesignerView.vue)、[MarkdownDocument](../../../frontend/src/components/MarkdownDocument.vue)、[MermaidDiagram](../../../frontend/src/react/diagrams/MermaidDiagram.tsx) |
| 共享 Markdown 的图示 | 同一 React Mermaid 服务；保留时序、类图等原语法 | [安全渲染服务](../../../frontend/src/domain/diagrams/mermaid.ts) |

共享 Mermaid 的静态 import 链实际到达 10 个页面：Designer、TaskDetail、TaskDesignHistory、WorkflowRequirement、DocumentTemplate、SourceTemplate、Knowledge、PptStudio、Projects、Tools；另有全局 StoryAccountingDialog。典型间接入口为 `Projects → ProjectConventionActivityPanel → AiActivityPanel → MarkdownDocument`、`PptStudio → PptDetailsDialog → PptSources → MarkdownDocument`。这属于源码入口覆盖；未声称逐页穷尽所有文档内容和所有 Mermaid 语法的浏览器排列。

无历史会话的 `/designer` 保持原重定向。旧 Designer 的工作包卡片和 LoopSpec 表单没有独立拖拽画布。`/workflows`、`/requirements`、`/requirements/new`、`/ppt`、`/tasks`、`/designs`、`/template-tasks`、`/knowledge/history` 是列表/表单，`/tasks/:id/recovery` 是恢复表单，均不冒称已经全页 React 化。进度环、统计图、图标和普通附件图片也不纳入节点画布。

## 契约与逐项验收

| 核查项 | 验收方式与边界 |
| --- | --- |
| 真实 React | 源码追踪 createRoot → JSX → React Flow / PPT；浏览器同时检查实际节点/边/对象和行为，不能仅靠 data 标记 |
| 安全回退 | 偏好只捕获于新实例；活动 dirty/File/pending/自动保存/未知回执不热卸载；恢复成功后才安全切换 |
| 幂等与乱序 | 原 key、payload、File、版本和已接受回执保留；已接受写入后读取失败只重读；跨需求迟到结果不能覆盖新所有者 |
| root 与资源 | 真实 StrictMode 根重放、反复挂载/卸载、ResizeObserver 断开、pointer 捕获释放、旧异步结果忽略；SPA 路由反复进入不重复写 |
| 坐标与历史 | 首个鼠标事件完整 105px/20px 位移；不同缩放；拖动中锁定/新布局；旧平移不得污染新布局；程序化视口不增加撤销记录 |
| 交互与视觉 | 三皮肤；默认/选择/切换/取消；节点与边键盘操作；PPT 尺寸/属性草稿/409；390px 及长序列 320px |
| 限制 | 模拟 API；未验证真实 Java、模型、文件解析、外部发布；既有 11 项失败独立保留 |

回退入口、唯一 history/命令/订阅所有者，以及后续全站 Vue 退场条件继续见 [迁移边界](../../design/react-canvas-migration.md)。

## 每位审查者的确认发现与修复

| 审查者 | 确认发现 | 修复与回归证据 |
| --- | --- | --- |
| `react_flow_workflow` 审 PPT | 未发现 PPT React 几何迁移缺陷；发现两个既有恢复缺口：无属性草稿、存储不可写时未知操作仍可能离开；重新载入 pending 后缺少可见恢复入口 | [PptStudioView.vue:182](../../../frontend/src/views/PptStudioView.vue#L182) 拒绝 busy/pending 离开、刷新前提醒；[pptStore.ts:84](../../../frontend/src/stores/pptStore.ts#L84) pending 锁定编辑与自动保存，恢复提示只有对应权威回执才能清除。真实适配器＋store 的 [PptCanvasRecovery.spec.tsx:54](../../../frontend/src/components/ppt/PptCanvasRecovery.spec.tsx#L54)、[Studio 恢复:81](../../../frontend/src/views/PptStudioRecovery.spec.tsx#L81)、[三项浏览器恢复/409:648](../../../frontend/e2e/ppt.spec.ts#L648) 通过 |
| `react_ppt_canvas` 审只读图与 Mermaid | 迁移将只读节点设为不可聚焦，也使节点文字不能原生选择；320px 长序列的末节点无法通过键盘定位 | [ReadonlyDiagramFlow.tsx:20](../../../frontend/src/react/diagrams/ReadonlyDiagramFlow.tsx#L20) 恢复节点焦点、焦点自动定位、文字选择，继续禁止业务选择/拖拽/连接；[焦点轮廓](../../../frontend/src/react/diagrams/diagrams.css#L6) 复用 tokens。[DiagramFlows 测试](../../../frontend/src/react/diagrams/DiagramFlows.spec.tsx)、[三皮肤×两类长序列 E2E](../../../frontend/e2e/react-diagram-accessibility.spec.ts) 验证 20 节点 Tab 到末端、原生文字选择、主题切换不重建 SSE，SPA 离开只关闭任务订阅、保留全局订阅 |
| `react_legacy_canvas` 审工作流及命令 | 旧平移可覆盖新权威视口；平移期间程序化 reveal 可生成布局保存/撤销历史；活动节点/连线的边缘自动平移可在卸载后保留 RAF；另发现未知命令允许确认离开的既有恢复缺口 | [WorkflowCanvasReact.tsx:23](../../../frontend/src/react/workflow/WorkflowCanvasReact.tsx#L23) 增加布局代次与真实事件边界，阻止旧手势回写；[public autoPan 配置:170](../../../frontend/src/react/workflow/WorkflowCanvasReact.tsx#L170) 禁止边缘自动平移；卸载取消连接、清除本地手势，迟到连接不能提交。[Editor:132](../../../frontend/src/views/WorkflowEditorView.vue#L132)、[Requirement:207](../../../frontend/src/views/WorkflowRequirementView.vue#L207) 拒绝 pending/busy 离开，普通 dirty 确认语义保留。[真实 Flow 回归:165](../../../frontend/src/react/workflow/WorkflowCanvasReact.spec.tsx#L165)、[两项浏览器原身份恢复](../../../frontend/e2e/react-workflow-review.spec.ts) 通过；上游监听限制另列 |

三位审查者均检查了自己未实现的模块。PPT/流程导航改动是为满足此次安全回退验收而修补的既有缺口，不冒称全部由 React 引入。原 API、DTO、命令格式、默认模型选择、SSE 所有者没有改写。

组长独立补充 [真实 createRoot / StrictMode 根测试](../../../frontend/src/react/bridge.spec.tsx#L10) 与 [SPA 三次往返及内置只读浏览器测试](../../../frontend/e2e/react-canvas-lifecycle.spec.ts)。StrictMode 测试明确核对 effect 重放、单一活动监听、回调更新与最终清理；没有仅靠内部包一层 StrictMode 而默认认为发生了重放。

## 关键合同的具体测试证据

| 合同 | 本轮重新运行的证据 |
| --- | --- |
| 首帧完整位移 | [真实浏览器 105px 屏幕位移](../../../frontend/e2e/workflow-authoring.spec.ts#L63)；[真实 React Flow 在 0.5/1/2 倍缩放的 105px/20px 用例](../../../frontend/src/react/workflow/WorkflowCanvasReact.spec.tsx#L247)。继续使用 `nodeDragThreshold={0}`，未缩小断言容差来隐藏首帧损失 |
| 拖动锁定、新布局及视口 | [WorkflowCanvasReact.spec.tsx:165](../../../frontend/src/react/workflow/WorkflowCanvasReact.spec.tsx#L165) 旧平移、程序化 reveal；[同文件:284](../../../frontend/src/react/workflow/WorkflowCanvasReact.spec.tsx#L284) 锁定和旧拖动遇新布局；[原生连线与撤销 E2E](../../../frontend/e2e/react-canvas-migration.spec.ts#L11) |
| 未知回执与已接受命令 | [acknowledgedOperation.spec.ts:5](../../../frontend/src/domain/acknowledgedOperation.spec.ts#L5)、[command.spec.ts:14](../../../frontend/src/components/workflow/command.spec.ts#L14)：同 key/closure 重试、已接受写入只重读、并发合并；本轮未修改这两份协议实现 |
| 跨需求迟到隔离 | [WorkflowRequirementScope.spec.ts:96](../../../frontend/src/views/WorkflowRequirementScope.spec.ts#L96)：旧写、旧 readback、组合读取、调整计划不能覆盖新需求；测试刻意越过正常导航守卫以单独验证作用域保护，另两项验证正常守卫确实拒绝 pending 离开 |
| File、上传和关闭 | [workflow-upload.spec.ts:47](../../../frontend/e2e/workflow-upload.spec.ts#L47)：刷新后补传；[同文件:57](../../../frontend/e2e/workflow-upload.spec.ts#L57)：未知回执时关闭/Escape/离页拒绝，原身份重试。真实文件解析不在该模拟 API 验收内 |
| 运行时偏好、dirty 与恢复 | [canvasRuntimeVue.spec.ts](../../../frontend/src/migration/canvasRuntimeVue.spec.ts)、[React 运行时回退 E2E](../../../frontend/e2e/react-canvas-migration.spec.ts#L34)、[未知操作恢复 E2E](../../../frontend/e2e/react-workflow-review.spec.ts)、[PPT 恢复 E2E](../../../frontend/e2e/ppt.spec.ts#L648)。偏好变化不替换活动 root，确认原操作完成后重新进入才切换 |
| PPT 属性、版本、指针 | [PptCanvasView.spec.tsx:37](../../../frontend/src/react/ppt/PptCanvasView.spec.tsx#L37) 几何、尺寸、revision、锁定、Escape/pointercancel；[同文件:247](../../../frontend/src/react/ppt/PptCanvasView.spec.tsx#L247) StrictMode 中每个 ResizeObserver 断开、活动 pointer 释放；[浏览器拖拽:617](../../../frontend/e2e/ppt.spec.ts#L617)、[409 保留属性草稿:706](../../../frontend/e2e/ppt.spec.ts#L706) |
| 默认模型归属 | [modelChoice.spec.ts:15](../../../frontend/src/components/workflow/modelChoice.spec.ts#L15) 原默认初始化、显式选择、迟到响应与需求隔离合同，没有在 React 引入第二默认值 |
| Mermaid 和订阅 | [MermaidDiagram.spec.tsx:64](../../../frontend/src/react/diagrams/MermaidDiagram.spec.tsx#L64) 根重放、旧结果/错误与产物清理；[长序列浏览器用例](../../../frontend/e2e/react-diagram-accessibility.spec.ts) 直接记录任务及全局 EventSource 的创建与关闭 |

## 尚未满足的资源清理门槛

锁定依赖是 `@xyflow/react 12.12.0` / `@xyflow/system 0.0.83`。活动连线的 `XYHandle.onPointerDown` 内部注册 `mousemove`、`mouseup`、`touchmove`、`touchend` 四个 document 监听；其公共类型返回 `void`，没有 disposer 或 AbortSignal。React Flow 的 `cancelConnection()` 清空 store，不能立刻解除这些监听。第二位审查者独立检查了安装包的公共类型与实现，确认这不是本地遗漏调用某个公开销毁接口。

本轮使用 [React Flow 公共配置](https://reactflow.dev/api-reference/react-flow) 关闭节点/连线的边缘自动平移，消除悬挂 RAF；手动平移、节点拖动、端口连接仍可用。卸载后的回调由 mounted/readonly 检查阻止写入。在合适的下一次自然鼠标事件到来后，上游自己释放四个监听；[专项测试:221](../../../frontend/src/react/workflow/WorkflowCanvasReact.spec.tsx#L221) 明确验证这一边界，没有宣称“卸载瞬间为零”。

**严格的即时清理门槛未通过。** 用户按住连线时卸载、随后没有自然释放/移动事件，监听可继续存留；多点触摸仍有其他触点或仅触发 touchcancel/blur 的路径不保证及时解除。禁止跨 root 写回和取消 RAF 降低了影响，但不是完整修复。未修改 node_modules、广播伪造 mouseup、拦截全局 addEventListener 或移除别的组件监听。下一阶段完整资源门禁需要上游公开取消能力，或经过独立行为验收的受控手势替代方案；在此之前不能以这些通过数宣称生命周期问题已全部消除。

## 最终验证分类（2026-10-03 UTC）

| 分类 | 最终实际结果 |
| --- | --- |
| 通过 | 完整 Vitest：187 文件、1198 项，最终源码/夹具冻结后再次运行，82.91 秒；不是沿用上一轮的 1169 项 |
| 通过 | `npm run typecheck`；`npm run build`（21.80 秒）；`test:tooling` 7 项；`test:accounting` 13 项；文档检查、相对资源链接和 diff 检查 |
| 浏览器分批通过 | 关联 45 个文件、158 项首轮：149 通过、9 失败；其中 3 个新长模板测试漏配 `/api/template-tasks/:id/failed-batches`，错误返回 `[]` 导致原 Vue 容器报错。只修测试 DTO 后重跑全部 6 个长序列测试，6/6 通过，卸载/订阅断言原样保留。去重后 **152 项通过、6 项失败**，不把重复执行相加 |
| 仍失败 | 6 项 `roles.spec.ts` 旧文案预期失败，本轮确实复现；未修改断言掩盖失败 |
| 历史失败单列 | 全部 11 项原始基线失败见 [独立记录](known-baseline-failures.md)。另外 5 项本轮没有再运行，不计通过，也不因迁移回归通过而豁免 |
| 未跑 | 当前完整 E2E 清单为 65 个文件、245 项；本轮未重跑其余 87 项。没有独立 lint 脚本，未运行/冒称 lint；未运行 Maven/JAR、真实 Java/API 持久化、模型、真实文件解析、外部发布或部署验证 |
| 部分满足 | root、项目自己持有的监听、ResizeObserver、已测试订阅与计时器路径通过；上述上游活动连线即时监听清理仍不满足，不能列为全通过 |

复现主命令：在 `frontend/` 执行 `npm test`、`npm run typecheck`、`npm run build`、`npm run test:tooling`、`npm run test:accounting`；浏览器使用系统 Chromium：

```sh
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium npm run test:e2e -- 'workflow-|react-|ppt.spec.ts|roles.spec.ts|designer-discussion' --workers=1
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium npm run test:e2e -- e2e/react-diagram-accessibility.spec.ts --workers=1
```

截图输出由 `CANVAS_EVIDENCE_DIR` 指定。浏览器首启受默认沙箱限制，Vite 监听 `127.0.0.1:41773` 返回 `EPERM`；通过正常工具权限机制运行后成功。工具链与守卫初跑分别被 `spawnSync git EPERM`、`spawnSync rg EPERM` 阻止；原命令获正常提权后通过，未改测试、网络、代理或凭据。本轮未改依赖及锁文件。

构建保留第三方 PURE 注释和大 chunk 警告；主入口为 1,664.22 kB、gzip 454.99 kB，不能据此声称性能改善。交付仅本地提交，没有触发远端 CI，没有提供尚未发布的 GitHub URL。

## 本轮实际浏览器截图

下面仅收录代表图片，全部为 **Chromium 实际截图＋模拟数据**，PNG 原始字节未加工。点击图片可打开原图。PPT 的页面制品是固定 SVG fixture；连接中断提示来自模拟 SSE，不能当作真实服务健康状态。图中测试路径、项目与业务数值均为 fixture。截图的代码基线为 `628ab18d88173910139dd9709054b17d10e534cb`，不是上一轮截图。图片尺寸、大小和 SHA-256 见 [清单](screenshots.json)。

### 流程创作 · 浦发蓝 spdb（模拟数据）

**默认画布 · 模拟数据**

![默认画布 · Chromium 实际截图 · 模拟数据](screenshots/spdb-editor.png)

**选中节点与上下文操作 · 模拟数据**

![选中节点与上下文操作 · Chromium 实际截图 · 模拟数据](screenshots/spdb-selected.png)



### 流程创作 · 科技蓝 tech-blue（模拟数据）

**默认画布 · 模拟数据**

![默认画布 · Chromium 实际截图 · 模拟数据](screenshots/tech-blue-editor.png)

**选中节点与上下文操作 · 模拟数据**

![选中节点与上下文操作 · Chromium 实际截图 · 模拟数据](screenshots/tech-blue-selected.png)



### 流程创作 · GitHub 白 github-white（模拟数据）

**默认画布 · 模拟数据**

![默认画布 · Chromium 实际截图 · 模拟数据](screenshots/github-white-editor.png)

**选中节点与上下文操作 · 模拟数据**

![选中节点与上下文操作 · Chromium 实际截图 · 模拟数据](screenshots/github-white-selected.png)



<details>
<summary>流程与需求的其他入口（模拟数据）</summary>

**spdb 空白新建画布 · 模拟数据**

![spdb 空白新建画布 · Chromium 实际截图 · 模拟数据](screenshots/spdb-empty.png)

**spdb 内置流程只读预览 · 模拟数据**

![spdb 内置流程只读预览 · Chromium 实际截图 · 模拟数据](screenshots/workflow-builtin-preview.png)

**spdb 需求规划选中节点 · 模拟数据**

![spdb 需求规划选中节点 · Chromium 实际截图 · 模拟数据](screenshots/spdb-planning-selected.png)

**tech-blue 需求执行选中节点 · 模拟数据**

![tech-blue 需求执行选中节点 · Chromium 实际截图 · 模拟数据](screenshots/tech-blue-execution-selected.png)

**spdb 候选计划画布预览 · 模拟数据**

![spdb 候选计划画布预览 · Chromium 实际截图 · 模拟数据](screenshots/workflow-candidate-preview.png)

**spdb 另存模板桌面预览 · 模拟数据**

![spdb 另存模板桌面预览 · Chromium 实际截图 · 模拟数据](screenshots/workflow-save-template-1600.png)

**spdb React Flow 原生拖动与端口连线 · 模拟数据**

![spdb React Flow 原生拖动与端口连线 · Chromium 实际截图 · 模拟数据](screenshots/react-flow-drag-connect.png)

</details>

<details>
<summary>PPT 自由对象 · 浦发蓝 spdb（模拟数据）</summary>

**默认画布与缩略预览 · 模拟数据**

![默认画布与缩略预览 · Chromium 实际截图 · 模拟数据](screenshots/ppt-spdb-default-mock.png)

**选中对象 · 模拟数据**

![选中对象 · Chromium 实际截图 · 模拟数据](screenshots/ppt-spdb-selected-mock.png)

</details>

<details>
<summary>PPT 自由对象 · 科技蓝 tech-blue（模拟数据）</summary>

**默认画布与缩略预览 · 模拟数据**

![默认画布与缩略预览 · Chromium 实际截图 · 模拟数据](screenshots/ppt-tech-blue-default-mock.png)

**选中对象 · 模拟数据**

![选中对象 · Chromium 实际截图 · 模拟数据](screenshots/ppt-tech-blue-selected-mock.png)

</details>

<details>
<summary>PPT 自由对象 · GitHub 白 github-white（模拟数据）</summary>

**默认画布与缩略预览 · 模拟数据**

![默认画布与缩略预览 · Chromium 实际截图 · 模拟数据](screenshots/ppt-github-white-default-mock.png)

**选中对象 · 模拟数据**

![选中对象 · Chromium 实际截图 · 模拟数据](screenshots/ppt-github-white-selected-mock.png)

</details>

<details>
<summary>只读流程和共享 Mermaid（模拟数据）</summary>

**spdb 普通任务阶段 · 模拟数据**

![spdb 普通任务阶段 · Chromium 实际截图 · 模拟数据](screenshots/spdb-stages.png)

**tech-blue 模板步骤与展开的阶段详情 · 模拟数据**

![tech-blue 模板步骤与展开的阶段详情 · Chromium 实际截图 · 模拟数据](screenshots/tech-blue-template-progress.png)

**github-white 角色阶段流程 · 模拟数据**

![github-white 角色阶段流程 · Chromium 实际截图 · 模拟数据](screenshots/github-white-roles.png)

**spdb 旧 Designer 历史文档 Mermaid · 模拟数据**

![spdb 旧 Designer 历史文档 Mermaid · Chromium 实际截图 · 模拟数据](screenshots/spdb-designer-mermaid.png)

</details>

<details>
<summary>窄屏与恢复边界（模拟数据）</summary>

**tech-blue 390px 流程节点上下文 · 模拟数据**

![tech-blue 390px 流程节点上下文 · Chromium 实际截图 · 模拟数据](screenshots/tech-blue-workflow-mobile-selected.png)

**spdb 390px 另存模板预览 · 模拟数据**

![spdb 390px 另存模板预览 · Chromium 实际截图 · 模拟数据](screenshots/workflow-save-template-390.png)

**github-white 390px PPT 手动编辑 · 模拟数据**

![github-white 390px PPT 手动编辑 · Chromium 实际截图 · 模拟数据](screenshots/ppt-github-white-mobile-mock.png)

**spdb 390px 普通任务阶段 · 模拟数据**

![spdb 390px 普通任务阶段 · Chromium 实际截图 · 模拟数据](screenshots/spdb-stages-mobile.png)

**github-white 390px 模板步骤 · 模拟数据**

![github-white 390px 模板步骤 · Chromium 实际截图 · 模拟数据](screenshots/github-white-template-progress-mobile.png)

**tech-blue 390px 角色流程 · 模拟数据**

![tech-blue 390px 角色流程 · Chromium 实际截图 · 模拟数据](screenshots/tech-blue-roles-mobile.png)

**github-white 390px 旧 Designer 文档图示 · 模拟数据**

![github-white 390px 旧 Designer 文档图示 · Chromium 实际截图 · 模拟数据](screenshots/github-white-designer-mermaid-mobile.png)

**tech-blue 320px 长任务序列键盘定位末节点 · 模拟数据**

![tech-blue 320px 长任务序列键盘定位末节点 · Chromium 实际截图 · 模拟数据](screenshots/tech-blue-stages-long-keyboard.png)

**tech-blue 320px 长模板序列键盘定位末节点 · 模拟数据**

![tech-blue 320px 长模板序列键盘定位末节点 · Chromium 实际截图 · 模拟数据](screenshots/tech-blue-template-progress-long-keyboard.png)

**spdb PPT 属性自动保存遇到 409 后保留输入 · 模拟数据**

![spdb PPT 属性自动保存遇到 409 后保留输入 · Chromium 实际截图 · 模拟数据](screenshots/ppt-react-property-conflict-mock.png)

</details>
