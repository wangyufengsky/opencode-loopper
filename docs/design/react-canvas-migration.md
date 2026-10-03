# React 画布迁移边界与后续清单

本阶段基于 `c26bf3bf7590424bd95bf83c093ae5740a068572`，在独立分支 `feat/react-canvas-migration` 接入全部现存画布渲染入口。页面容器、路由、业务命令与订阅暂留 Vue；节点流程画布使用 React Flow，PPT 使用 React 自由对象编辑器。这里的“接入”指真实渲染与交互已进入 React，不代表整页表单或全站已改写成 React。

## 路由与画布盘点

权威路由表为 [`router/index.ts`](../../frontend/src/router/index.ts)。同一适配器覆盖页面内预览，不增加第二套命令实现。

| 路由或入口 | 实际画布与预览 | 本阶段实现 | 仍由原页面持有 |
| --- | --- | --- | --- |
| `/workflows/new`、`/workflows/:id` | 新建、自定义流程、内置流程只读预览 | `WorkflowCanvas` → React Flow，自定义节点/边、拖动、端口连接、缩放、选择 | 编辑草稿、校验、撤销栈、保存/另存、冲突处理 |
| `/requirements/:id` | 规划、执行投影、调整计划、候选计划在画布中查看 | 同一 React Flow 适配器 | 模型归属、候选确认、版本和执行命令、轮询、节点表单 |
| 需求页“另存为流程模板” | `WorkflowSaveTemplate` 内只读图预览 | 同一 React Flow 适配器 | 原始冻结草稿、创建回执与重试 |
| `/ppt/:id` | 对象画布、选中/拖动/缩放、当前/历史渲染预览 | `PptCanvasView`，保留 PPT 几何与对象格式 | 文档 store、自动保存、冲突恢复、导出任务、上传 |
| `/ppt/:id` 页面导航 | PPT 缩略页、当前修订预览 | `PptSlideNavigatorView` | 选页、新增页面意图，预览 URL 由原 API 层生成 |
| `/tasks/:id` 普通任务 | `StageRail` 阶段与连接投影 | React Flow 只读节点图 | Task 权威状态、执行/停止、SSE 与轮询 |
| `/tasks/:id` 模板任务 | 模板步骤、展开的阶段详情 | `TemplateStepDiagram` 与 `StageDiagram` | 批次统计、失败恢复、报告与版本 |
| `/roles` | `RoleWorkflowDiagram` 中的角色阶段流程 | React Flow 只读图；保留查看绑定版本入口 | 角色编辑、版本读取与发布 |
| `/designer?sessionId=…` | 历史讨论/设计文档中的 Mermaid | `MermaidDiagram` React 图岛 | Designer 会话、附件、LoopSpec 表单与确认 |
| 共享 Markdown 消费页面 | 下表中的 Mermaid 图 | 同一 React 图岛与安全渲染服务 | 各自文档和业务来源 |

旧 Designer 源码没有独立的拖拽节点画布。工作包卡片、LoopSpec 编辑表单不是画布，不能用“迁移 Designer 画布”声称它们已经改成 React。无历史会话的 `/designer` 继续按原入口规则转到新建需求。

共享 `MarkdownDocument` 的图示可出现在 `/designer`、`/tasks/:id`、`/tasks/:id/design`、`/requirements/:id`、`/template-tasks/document-runs/:id`、`/template-tasks/source-runs/:id`、`/knowledge/:conversationId?`、`/ppt/:id`、`/projects` 的项目约定、`/tools` 的 Skill 预览，以及全局核算说明弹窗。只有 Markdown 含 Mermaid 时挂载 React 图岛；普通 Markdown 文本未重写。

Mermaid 保留原语法、解析器与安全 SVG 输出，包括时序图、类图等；不把文档图强行转换成业务节点 DTO 或执行依赖。React 管理图示加载、错误、主题和卸载。模板/任务图中的连接同样只是服务端顺序的显示投影，不创建执行依赖。

纯列表或表单入口不计为画布：`/workflows`、`/requirements`、`/requirements/new`、`/ppt`、`/tasks`、`/designs`、`/template-tasks`、`/knowledge/history`。`/tasks/:id/recovery` 的恢复表单/来源说明也不是可编辑节点画布。统计图、进度圆环、图标、附件图片继续使用原组件。

## 所有权与接口边界

```text
Vue Router（唯一 history 所有者）
  └─ 原页面 / store / API / SSE / 轮询 / 离开守卫（唯一业务所有者）
       ├─ 纯 TypeScript DTO、图操作、命令回执、几何与投影
       └─ Vue 薄适配器 → React root → React Flow / PPT / Mermaid
                              └─ 用户意图回调返回原业务所有者
```

- [`react/bridge.ts`](../../frontend/src/react/bridge.ts) 负责 `createRoot`、同步更新和卸载；React 不创建 Router，不调用 `pushState`，不另起订阅。组件卸载后忽略迟到的 render。
- Vue 适配器传入 DTO 快照和回调，不把 Vue proxy 交给 React，也不创建第二份持久草稿。React 的拖动/视口临时状态不作为服务端事实。
- `WorkflowCanvas` 保留原 props/emits 和 `fit/focus/reveal` 接口；新增端口拖线意图 `connect-pair(from,to)`，仍经原 `connect` 图校验和受保护节点校验。
- 自动视口同步、定位节点和 reveal 不生成保存命令或撤销历史。实际用户拖动才产生布局意图。只读与“允许调整显示位置”分别处理。
- PPT 使用原 `PptDeck`、`PptSlide`、`PptElement` 与修订号。对象拖动/缩放只发原 patch；锁定、修订变化、取消手势不提交残留位置。预览图不自动成为可编辑对象。
- React 图标使用已打包的 Iconify/Lucide 数据。React Flow 保留默认署名，无 Pro 功能或付费依赖。
- `themes/`、`styles/tokens.css` 与现有工作流/PPT CSS 继续是三套皮肤来源。React CSS 复用语义变量，不按皮肤 ID 分支。

纯 TypeScript 边界：

| 模块 | 当前职责 | 后续可复用方式 |
| --- | --- | --- |
| `domain/acknowledgedOperation.ts` | 原命令、已接受回执、未知结果重试、并发合并 | React 控制器继续使用同一协议，不能改成 effect 自动提交 |
| `domain/pptCanvas/geometry.ts` | 自由对象坐标和边界 | 独立于组件与状态库 |
| `domain/diagrams/projections.ts` | Task/模板/角色 DTO → 只读显示图 | 不改变原 DTO 或权威状态 |
| `domain/diagrams/mermaid.ts` | 串行渲染、安全清洗、主题参数与残留清理 | 浏览器服务，无 Vue/React 依赖；不是纯计算函数 |
| `components/workflow/graph.ts`、`planSave.ts`、`planEditing.ts` 等现有 TS | 图约束、保存快照与回执 | 位置暂保留，未来可搬到 domain；不能同时改写行为 |
| `migration/canvasRuntime.ts` | 按路由领域读取显示偏好 | 不负责命令、导航或持久业务状态 |

## 必须保持的业务合同

1. 写命令只由原 API/业务所有者发起。React 挂载、重渲染、主题切换、布局测量均不得发命令。
2. 未知回执重试使用原 request key、版本和原始 payload/File；已接受写入后读取失败只重读，不重做写入。
3. 409 不覆盖草稿；后台刷新不提升草稿基线。乱序响应、作用域变化和组件卸载后，迟到结果不得进入新的需求或 PPT。
4. 节点输入、上传恢复、候选应用、另存模板、结束/发布等原确认与离开守卫继续生效。选择/取消不能销毁待恢复 File 或未知回执。
5. 默认模型仍归原 modelChoice/store 和服务端控制投影，不由 React 引入默认值或替换用户选择。
6. API、SSE、轮询及订阅清理仍在原所有者。React 只清理 ResizeObserver、键鼠/指针监听、图渲染与自身 root。
7. 快捷键限定在画布；表单输入不被 Delete/方向键抢占。Esc 取消选择/连接/手势，关闭后焦点返回可用节点或画布，重复选中不得重复关闭/重建草稿。

相关合同仍以 [流程合同](../workflow-contract.md)、[设计合同](../design-contract.md) 及 API/DTO 源码为准。本阶段没有数据迁移、后端协议改版或第二套业务状态存储。

## 安全回退

入口：设置 → 画布显示。可分别为流程、PPT、任务阶段、角色流程、文档图示选择“新版画布”或“兼容画布”。浏览器仅保存 `loopper.canvas.runtime.v1`，默认 React。

偏好在画布组件实例创建时捕获；没有监听 storage 后热替换活动 root，没有强制刷新、自动跳路由、卸载业务容器或重发命令。同一组件被路由复用时，也不因偏好变化突然替换实例。

操作步骤：

1. 先完成或恢复在途写入/上传，保存当前草稿；未知结果使用现有“重试原操作”或“刷新操作结果”。
2. 在设置中选择对应领域的兼容画布。该动作只写本地偏好，不调用服务端设置保存。
3. 经原离开守卫安全离开，再进入目标页面。节点/文件/草稿守卫拒绝离开时保持原实例，不强行回退。
4. 验证兼容画布后继续原操作。恢复新版时按同样步骤选择新版并重新进入。

在开发者需要诊断时，可检查 `data-canvas-runtime="react|vue"` 与 `data-canvas-kind`。这些标记也用于 E2E 确认真正跑过 React 路径。它不是服务端能力或授权状态。

回退不撤销已接受的服务端写入；不要把重装旧前端当作清除未知回执的办法。若代码本身需回滚，可切回基线构建；同样应先恢复在途操作并使用原守卫退出，不删除服务端记录或本地待处理数据。

## 验收方式

- 单测：真实 React Flow/React Testing Library 交互；Vue 适配器与 Legacy 分开验证；原纯 TS 合同继续执行。
- E2E：默认偏好下显式检查 React 标记与 `.react-flow`。仅为验证回退的用例才选择 Vue。
- 场景：默认/空白、选择/重复选择/切换/取消、拖动、原生端口连接、撤销、只读预览、候选与另存预览、PPT 修订/缩放/对象操作、上传未知结果恢复、草稿拒绝离开、安全回退。
- 三皮肤：spdb、tech-blue、github-white；桌面与 390px 窄屏。截图由 Chromium 对模拟 API 数据实际渲染生成，不是设计稿。
- jsdom 只补 ResizeObserver、尺寸与 DOMMatrix 等浏览器测量 API；不在集成测试里把 React Flow 替换为 Vue 或静态假图。真实几何和手势最终由 Chromium 验证。
- 运行 `npm ci`、`npm run typecheck`、`npm test`、`npm run build`、`npm run test:tooling`、`npm run test:accounting` 和 Chromium E2E。仓库未配置独立 lint 脚本，不把其他检查冒称 lint。

具体计数、截图和限制见 [交付与验证记录](react-canvas-validation.md)；模拟 API 验收不证明真实后端、模型、文件解析或外部发布链路可用。

## 后续全站替换清单与 Vue 退场条件

| 顺序 | 模块 | 当前保留部分 | 迁移前提 |
| --- | --- | --- | --- |
| 1 | 共享基础 UI、列表、只读页 | Element Plus、StatusBadge、Markdown 文本、列表/筛选/分页 | 建立 React 交互原语；不借机改中文含义、URL 和筛选合同 |
| 2 | 工作流/需求页面容器 | 节点表单、草稿/历史、上传、候选、确认/发布 | 先抽控制器和不可变命令；所有 409、未知回执、默认模型、File 测试跨框架共用 |
| 3 | PPT 工作室外壳 | store、自动保存、属性面板、聊天、上传、任务预览 | 一个 store/订阅拥有者；修订、草稿恢复、异步响应隔离达到原门槛 |
| 4 | Task、模板、Designer/Knowledge | SSE/轮询、会话、附件、权限/问题、编辑器 | 逐路由迁移，保留故障恢复合同；不从显示图推断服务端动作许可 |
| 5 | 系统页与全局外壳 | Settings、Runtime、Roles、Tools、Database、导航与通知 | 确认所有副作用入口已迁移且可回退；覆盖深层链接和错误呈现 |
| 6 | Router、状态库与工具链退场 | Vue Router、Pinia、Vue 适配器、Legacy、Vue 插件/类型检查 | 最后一次性移交 history 所有权，不能长期并行两个 BrowserRouter |

Vue 可删除的门槛：所有生产路由及懒加载弹窗无 Vue/Element Plus 依赖；业务命令/订阅只剩一个拥有者；各领域回退观察期结束；真实 React 行为门禁覆盖既有合同；API/DTO 无隐式变更；深链接和部署 fallback 通过；无丢草稿、重复命令、未知回执丢失或监听泄漏。随后才删除 Legacy、桥接、Vue/Pinia/Element Plus 与对应构建插件，并重测产物和包体积。

下一阶段可以逐组页面推进，不需要本阶段就引入 React Router、Redux 或替换每个业务 store。框架变化本身不提升视觉质量；本次继续沿用已经验证的大画布、按选择出现操作面板及三套设计 tokens。

React root 的挂载/卸载依据 [React createRoot 文档](https://react.dev/reference/react-dom/client/createRoot)；节点、边、视口和连接使用 [React Flow 公共接口](https://reactflow.dev/api-reference/react-flow)。依赖版本以提交中的 `frontend/package-lock.json` 为准。
