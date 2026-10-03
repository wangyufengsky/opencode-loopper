# 全部画布类别的退出清理验收

最新的锁定依赖补丁、安装复现和最终续接验收见 **[ResizeObserver 清理修复](resize-observer-patch.md)**。下文保留 `5df03685` 的历史诊断、失败计数和截图；其中“仍未通过”属于该次历史结果，当前结论以新报告为准。

本轮基于 `b2b88768169d4319bdb7194cca5fc1e0efc97142`，继续在 `feat/react-canvas-migration` 本地工作。工作流已经通过的 Pointer Events 实现保持原行为；本次替换共享只读图的视口交互，并补齐 PPT、需求与静态图的独立退出证据。原 33 张审查截图和随后 6 张工作流截图保留。

这属于只读图的交互实现替换，不是调用销毁 API 的补丁。节点和边仍由真实 React Flow 渲染；PPT 仍是 React 自由对象编辑器。没有修改 API、DTO、命令身份、持久化、依赖版本、路由所有者或网络设置。

**严格全资源清理仍未通过。** 本次已解决原生背景平移的活动监听与捕获所有权，但更严格的原生观察器探针确认了固定版本 React Flow 的另一个尺寸观察器清理缺陷。没有过滤掉 detached 目标或放宽清零断言；下面分别记录已通过的合同与这个剩余阻塞。

## 原反例与受支持的修复

固定版本为 React Flow `12.12.0`、XYFlow system `0.0.83`。原共享只读图只关闭节点拖动和连接，仍使用原生背景平移。新增反例先证明真实视口移动 `105px / 20px`，再直接卸载：jsdom 首次快照留下 3 个 window 捕获监听；实际 Chromium 从 `/tasks/:id` 经原“全部任务”控件离开，首次快照留下 `mousemove / mouseup / dragstart / selectstart` 四个 window 捕获监听。快照之前没有发送鼠标释放、取消或窗口失焦。

固定版本的 [XYPanZoom.destroy 源码](https://github.com/xyflow/xyflow/blob/%40xyflow%2Fsystem%400.0.83/packages/system/src/xypanzoom/XYPanZoom.ts#L229) 与 [d3-zoom 活动窗口手势](https://github.com/d3/d3-zoom/blob/v3.0.0/src/zoom.js#L274) 解释了该现象。原样失败探针、trace 和首次指标保留在 Cloud 本地，不将环境日志加入仓库。

[ReadonlyDiagramFlow](../../../frontend/src/react/diagrams/ReadonlyDiagramFlow.tsx) 使用官方公开的 `panOnDrag / panOnScroll / zoomOnScroll / zoomOnPinch / zoomOnDoubleClick / autoPanOnNodeFocus` 等开关关闭原生交互，且关闭 activation keys 和边缘自动平移。每个输入节点继续明确不可拖动、连接、业务选择，边不可重连。依据：[ReactFlow 属性](https://reactflow.dev/api-reference/react-flow)、[Controls](https://reactflow.dev/api-reference/components/controls)、[实例 API](https://reactflow.dev/api-reference/types/react-flow-instance)。

新的 [viewportGestures](../../../frontend/src/react/diagrams/viewportGestures.ts) 持有受控 viewport，保留背景左键/中键平移、节点与边上的中键平移、裸画布双指缩放、剩余单指续拖、Ctrl 滚轮、Mac Meta 滚轮、双击/触摸双击、放大缩小/适应、Tab 离屏定位。节点和边原有 `nopan` 的左键行为继续保留，节点文字可原生选择。程序化缩放或聚焦先取消旧手势，不启动引擎动画 RAF。

这里只读取公开 `getInternalNode` 的实测尺寸，使用 `getNodesBounds / getViewportForBounds` 计算视口；不读取或修改库私有活动手势状态。三套皮肤继续使用原 tokens。不得重新打开原生手势形成双控制器。

普通滚轮现在交给外层页面滚动；这是局部可用性改善，不能描述成与旧事件拦截行为完全相同。旧默认 `preventScrolling=true` 即使不进行缩放也可能吞 wheel；实际 pan、激活键缩放及边界范围保持。Mac 曲线有单测，Cloud Chromium 的真实 wheel 证据仅来自 Linux。

## 实例资源所有权

| 资源 | 所有者与释放路径 |
| --- | --- |
| 四个 pointer 监听 | 当前图 root 上的 move/up/cancel/lostcapture；仅按本次注册的目标、函数和 capture 标志移除 |
| window blur | 当前 root 的 ownerWindow；每个会话一个独立函数 |
| Pointer capture | 当前 root，逐 pointerId 记录；先令会话失效并移除监听，再释放捕获 |
| 非 passive wheel | 当前图组件生命周期；卸载移除同一函数，普通 wheel 留给页面 |
| 会话中的 viewport | 仅实例临时状态；cancel/blur/lostcapture/Escape/图数据换代回滚，unmount 后不 setState |
| RAF、计时器、业务订阅 | 新手势控制器不创建；React Flow 的测量观察器与既有页面订阅另由测试核对 |

[共享资源函数](../../../frontend/src/react/gestures/pointerResources.ts) 从工作流原函数原样抽出；工作流仅改 import 和移除原定义，数学、状态机和业务回调没有重写。没有广播伪造 mouseup，没有删除他人监听，没有修改 node_modules。双实例测试及浏览器无关监听/RAF 哨兵验证所有权隔离。

## 所有画布类别的验收清单

“根卸载”是 `unmount()` 返回后的首次同步采样；“路由退出”是实际 SPA 控件激活、路由/DOM 断言后的首次浏览器采样，两者不混称。活动退出用 DOM click 激活原路由控件，使真实鼠标或触摸仍按住；清零断言后才释放测试设备。重复进入的资源账本不随 `arm()` 清空。

| 实际组件与入口 | 手势所有者 | 根卸载 / 重复根证据 | 真实退出 / 重复进入证据 | 结果与确切边界 |
| --- | --- | --- | --- | --- |
| `WorkflowCanvasReact`；`/workflows/new`、`/workflows/:id`、内置只读 | 原工作流 Pointer owner | [Immediate](../../../frontend/src/react/workflow/WorkflowCanvasImmediate.spec.tsx)：连接、拖动、背景平移、StrictMode、双实例、取消/新布局 | [原严格生命周期](../../../frontend/e2e/react-canvas-pointer-lifecycle.spec.ts)：原四种退出和背景/中键，均三轮 | 原手势清理门槛通过；原探针未覆盖 renderer RO，不能推断引擎所有资源通过 |
| 同一 WorkflowCanvas；`/requirements/:id` 规划/执行/调整/候选 | 原工作流 owner；Vue 页面持命令与订阅 | 复用上述真实组件的根测试；[桥接测试](../../../frontend/src/react/bridge.spec.tsx) | [新增真实需求入口](../../../frontend/e2e/react-workflow-cleanup-entries.spec.ts)：规划、执行活动背景平移各三轮 | 规划/执行活动 pan 退出通过；调整及历史候选为同一 root 的投影，未逐个状态交叉全部手势；未单列 RO |
| `WorkflowSaveTemplate` 的当前/首次模板预览 | 每个预览独立工作流 owner | 相同 React 根实现；双实例测试 | [新增关闭预览](../../../frontend/e2e/react-workflow-cleanup-entries.spec.ts)：活动 pan 关闭三轮，父 root 和父 wheel 保留 | 当前预览活动 pan 关闭通过；首次结构的全部组合及 RO 未单列 |
| `StageRail → StageDiagram → ReadonlyDiagramFlow`；普通 `/tasks/:id` | 新只读 viewport owner | [Readonly Immediate](../../../frontend/src/react/diagrams/ReadonlyDiagramImmediate.spec.tsx)：pending/active、StrictMode 三轮、双实例、pinch | [只读浏览器](../../../frontend/e2e/react-all-canvas-cleanup.spec.ts)：105/20 活动 pan 三轮；任务 SSE 精确关闭 | 手势监听/capture/wheel 清零；renderer RO 失败。20 节点、三皮肤与窄屏功能单列；真实推送未验证 |
| `TemplateTaskProgressPanel → TemplateStepDiagram`；模板 `/tasks/:id` | 新只读 viewport owner | 同上，实际 TemplateStepDiagram 根 | 同上，模板任务入口三轮退出 | 手势资源清零但 renderer RO 失败，账本亦捕获阶段详情的同类 observer；展开/折叠与全部手势的乘积未单列 |
| `RoleWorkflowDiagram → RoleDiagram`；`/roles` | 每个只读流程独立 owner | 同上，实际 RoleDiagram 根；双岛隔离 | 同上，从真实角色详情进入，三轮退出 | 手势资源清零但 renderer RO 失败；多组组合未穷尽，组件双实例单测单列 |
| `PptCanvasView`；`/ppt/:id` 对象拖动/尺寸调整 | 既有 React 事件、对象 capture、ResizeObserver | [新增 PPT Immediate](../../../frontend/src/react/ppt/PptCanvasImmediate.spec.tsx)：drag/resize 各三次 StrictMode，110% 缩放、观察回调、双 touch 岛 | [PPT 实际路由](../../../frontend/e2e/ppt.spec.ts)：拖动/resize 各三轮，105px、无迟到 patch、SSE 关闭 | 已测 mouse 拖动/resize 根与路由退出通过；生产未改。PPT 无连续背景 pan/pinch，缩放为同步操作；双 touch 隔离仅 jsdom |
| PPT 当前/历史制品图片、`PptSlideNavigatorView` 缩略导航 | 图片/按钮，无持续画布手势 | [PPT 现有测试](../../../frontend/src/react/ppt/PptCanvasView.spec.tsx)与导航测试 | 同一 PPT 路由退出；既有预览/缩略回归 | 既有图片/导航回归通过；没有为静态图片编造活动拖动，真实文件解析未验证 |
| `MarkdownDocument → MermaidDiagram`；旧 `/designer?sessionId=…` | React SVG；Vue 持懒加载 observer，安全渲染服务持队列 | [Mermaid StrictMode、迟到成功/失败](../../../frontend/src/react/diagrams/MermaidDiagram.spec.tsx)；[Markdown 卸载](../../../frontend/src/components/MarkdownDocument.spec.ts) | [Designer 新三轮 SPA](../../../frontend/e2e/designer-discussion.spec.ts)：真实 SVG、观察器及引擎残留 | 已渲染 SVG 的三轮路由退出通过；原本无 pan/zoom。引擎 Promise 不可中途取消，完成后清产物且不回写，迟到路径由单测覆盖 |
| 同一共享 Mermaid；TaskDetail、TaskDesignHistory、WorkflowRequirement、DocumentTemplate、SourceTemplate、Knowledge、PptStudio、Projects、Tools 及全局 StoryAccountingDialog | 同上 | 相同组件/桥接/安全服务证据 | 当前严格浏览器代表入口为 Designer；其他页面原功能回归按所选套件执行 | 未将 Designer 三轮退出冒称每个宿主页、弹窗和异步渲染阶段均有独立严格浏览器证明 |

列表、配置表单、统计图标不计入画布。兼容 Vue 回退继续仅在下一实例创建生效；pending、File、dirty draft、未知回执和自动保存期间的原守卫不改。

## 团队与独立复核

本轮通过真实 `followup_task` 续接原三名 `gpt-6.1-sol / xhigh`，没有新增或换模型：

| 原任务名称 | 分工与独立性 |
| --- | --- |
| `/root/react_flow_workflow` | 只读图生产实现和原样资源抽取；独立审查组长的 PPT 根测试及其他组员浏览器证据 |
| `/root/react_legacy_canvas` | 新只读根/行为测试；独立审查未由自己编写的生产实现及组长需求/预览退出测试 |
| `/root/react_ppt_canvas` | 只读/PPT/Designer 真实浏览器；独立审查只读生产实现 |
| Astra 组长 | 边界、PPT 最小根测试、需求/预览入口补证、集成与最终门禁 |

各作者的自测不算独立生产评审。测试中的 Pointer/capture 与测量 polyfill 仅用于 jsdom；真实设备语义以 Chromium 的 trusted 鼠标/键盘/wheel、CDP touch 为限。

独立复核纠正了两个不能当作产品回归的新增测试假设：固定引擎给 edge 加 `nopan`，原本不允许左键平移/双击缩放；离屏自动定位只处理完全离屏节点，部分可见节点不保证居中。新测试按固定版本的实际合同验证，保留了中键 edge/node 平移与精确 105/20 位移要求。只读根/行为/既有图测试聚焦共 41/41 通过，其中新 Immediate 16 项只证明监听、RAF、capture、wheel，不包含原生 RO 清理结论。

## 剩余阻塞与可行下一步

三名原组员分别检查过 [锁定版本 useResizeHandler](https://github.com/xyflow/xyflow/blob/%40xyflow%2Freact%4012.12.0/packages/react/src/hooks/useResizeHandler.ts) 的代码及原生指标。该 hook 在 `ZoomPane` 无条件执行，创建局部 `ResizeObserver`；清理时仅在 `domNode.current` 非空时 unobserve，且不 disconnect。React 卸载已清空这个 ref，导致 renderer 的观察记录仍在。设置公开 width/height、Provider 初始尺寸或关闭原生手势都不绕过此 hook；公开实例没有暴露该闭包内观察器的 disposer。

首次普通任务反例中，节点测量 observer 正常 disconnect；两个 renderer observer 仍记录同一个已断开的 renderer，均未调用 unobserve/disconnect。原始 native-pan 版本也有同样记录，因此不是自有 Pointer 控制器新增。探针透明转发原生调用，按实例/目标身份记录，不因目标断开就把它视作已清理。这证明显式清理门槛未满足，**不等同于已经测量并证实持续堆内存泄漏**。

当前受支持 API 无法完成这个闭包资源的精确销毁。最小下一步是对已经识别的 `@xyflow/react` 固定版本准备可审查的源码补丁，让 `useResizeHandler` 的 cleanup 无条件 `resizeObserver.disconnect()`，并核对该 hook 的延迟回调和相邻测量 RAF。这样的依赖源码修复需要明确纳入后续范围及供应链维护流程；本轮未私改安装包、私有 ref/store 或全局 ResizeObserver，也未用“等待垃圾回收”作为通过依据。若采用补丁，应保留当前严格反例并重跑 React Flow 各类别的根/路由/重复退出，而不是只测普通任务。

严格探针记录活动期间注册的 RAF 及其原始调用栈；它不把页面所有初始帧都归为当前手势。未完成的帧要根据调用栈和目标归因，不能仅凭数字 ID 认定是应用控制器，也不能删除未知条目获得绿色。工作流旧门槛没有原生 renderer RO 账本，故原门槛重跑通过也不能排除相同引擎缺陷在工作流中的适用性。

最终批次及定向复核中，部分 pan 退出和 touchCancel 后路由退出的原始 RAF 栈落在 Element Plus Table 的 `doLayout → requestAnimationFrame(syncPosition)`。分配时画布 root 已为 0，目的页 `/tasks` 存在该表格，原 Task/模板/角色详情源码没有 `el-table`；不是自有画布手势申请的 RAF。原始计数没有过滤这些帧，也没有取消它们。本轮不扩展修复表格行为。touchCancel 的取消快照已回滚视口并清零手势资源，之后路由切换仍可能出现表格帧；另有首次退出 RAF 为 0、RO 仍非零的独立样本，证明 RO 阻塞不依赖这项表格观测干扰。

为了取得真实重复退出数据，三种活动 pan 用例对 RAF/RO 保留完全相同的 `[]` 期望，只使用 Playwright `expect.soft` 让失败后仍记录后两轮；每项仍以失败结束。root、手势监听、wheel、capture、无后续输入等断言继续立即失败，不放宽。不能把“执行三轮”写成“三轮全资源通过”。

## 验证、图片与剩余边界

| 分类 | 本轮结果 |
| --- | --- |
| 通过：全量单测 | 191 文件、1287/1287，最终生产源码冻结后执行；包括新只读行为 20 项、Immediate 16 项及 PPT Immediate 4 项 |
| 通过：静态与构建 | `npm run typecheck`；`npm run build`；`test:tooling` 7/7；`test:accounting` 13/13 |
| 通过：相关 Chromium | 200 项批次中 186 通过：原工作流/React/PPT/Designer 回归与新增需求/预览 3 项、PPT 2 项、Designer 1 项 |
| 失败：新增严格只读浏览器 | 14 项；最后定向批次 17 项为 3 通过（PPT/Designer）、14 失败（只读）。9 项三皮肤窄屏均完成平移/缩放/键盘定位/真实选字，再在退出 RO 断言失败；不把功能子断言计为整项通过 |
| 历史失败独立保留 | 原 11 项在原基线复现，本轮未修改、未重跑、不计通过，见 [原始明细](../react-canvas-cross-review/known-baseline-failures.md) |
| 未跑 | 完整清单 69 文件、300 项；本轮选其中 200 项，其余 100 项含历史 11 项。未运行 Maven/JAR、真实后端/模型/文件解析或远端 CI |

没有独立 lint 脚本，未声称运行。工具链与 guard 初次在沙箱分别出现 `spawnSync git EPERM / spawnSync rg EPERM`，按正常权限机制重跑原命令后通过，没有改网络、凭据或测试期望。构建保留第三方 PURE 注释和大 chunk 警告；单测亦有既有 React `act` 警告，不能把“通过”描述成没有任何警告。浏览器批次使用模拟 API/订阅；它们不验证真实 Java 服务或任何模型。

本轮真实输入范围为 Cloud Chromium 的鼠标、键盘、滚轮与 CDP 触摸。只读双指 1.4 倍缩放、释放一指后剩余指针精确 25px/15px 续拖、touchCancel 回滚有浏览器证据；Mac Meta 曲线、双实例 touch 隔离及取消故障矩阵以单测为限。没有 Safari/Firefox、实体触屏/触控板/触控笔、屏幕阅读器或操作系统切窗的新验收结果。

复现命令（`frontend/`）：

```sh
npm test
npm run typecheck
npm run build
npm run test:tooling
npm run test:accounting
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium npm run test:e2e -- 'workflow-|react-|ppt.spec.ts|designer-discussion' --workers=1
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium npm run test:e2e -- e2e/react-all-canvas-cleanup.spec.ts --workers=1
```

新增 `CANVAS_ALL_CLEANUP_EVIDENCE_DIR` 指定只读/PPT/静态图指标和图片输出；`CANVAS_CLEANUP_EVIDENCE_DIR` 指定新增需求/预览退出指标。只读专项当前应返回失败，不能将它跳过或删除后宣称门槛满足。

### 最后一次重复退出的实际结果

以下来自最后 `verified-proof`，每次都先证明真实鼠标平移 105px/20px。九次首次退出采样的 root、会话监听、实例 wheel、capture 均为 0；与活动快照之间没有新增 move/up/cancel/window blur。监听与捕获不依赖自然释放，RO 显式清理失败且记录累计。

| 入口 | 第一次退出的 renderer RO | 第二次 | 第三次 | 严格结论 |
| --- | --- | --- | --- | --- |
| 普通任务阶段 | 2 | 4 | 6 | 失败 |
| 模板任务流程及阶段图 | 4 | 8 | 12 | 失败 |
| 角色流程 | 2 | 4 | 6 | 失败 |

这些 RO 的目标均已断开 DOM，账本中没有对应 unobserve/disconnect。最后九次活动 pan 中仅普通任务第一次还有一个原始 RAF（99），调用栈为上述目的页表格 `syncPosition`；其余八次为 0。最后两项触摸退出均为手势监听/wheel/capture 0、renderer RO 2；原始 RAF 分别还有一个表格帧（pinch 115、touchCancel 102），调用栈与普通任务 99 相同。普通触摸取消本身已恢复原 viewport 并清零手势资源，之后退出仍不能满足 RO 门槛。

同一最后定向批次中，PPT 拖动和 resize 各三次、Designer 静态 Mermaid 三次退出均通过，观察器、手势监听、RAF 和 capture 的相应账本清零。需求规划/执行与另存模板预览的三轮退出在 200 项批次中通过；预览关闭明确保留父画布和父 wheel。

首次 17 项反馈为 2 通过、15 失败，其中新测试夹具缺少 Designer DTO 必填字段、roles 实际 root 数量与假设不同、Space 后选字坐标离开可见区域等问题随后更正。200 项批次中新增离屏移动的小数位移又被浏览器 CSS 量化为约 0.0055px 差值，最后改为整数屏幕像素输入，原 105px 断言未放宽。新 API 模拟数据的阶段 ordinal 也按 `normalizeStage` 的零基输入修正，再运行最后 17 项并重新取图。生产源码自最终全量单测之前已冻结；没有用这些测试调整掩盖上游 RO 失败。

### 关键源码与断言位置

- [原生手势公开开关](../../../frontend/src/react/diagrams/ReadonlyDiagramFlow.tsx#L58)、[实例 cancel/unmount 顺序](../../../frontend/src/react/diagrams/viewportGestures.ts#L32)、[复用的原样资源函数](../../../frontend/src/react/gestures/pointerResources.ts#L3)。
- [三种真实只读根即时卸载](../../../frontend/src/react/diagrams/ReadonlyDiagramImmediate.spec.tsx#L22)、[StrictMode 重复根](../../../frontend/src/react/diagrams/ReadonlyDiagramImmediate.spec.tsx#L51)、[双实例隔离](../../../frontend/src/react/diagrams/ReadonlyDiagramImmediate.spec.tsx#L81)。
- [PPT 严格根卸载](../../../frontend/src/react/ppt/PptCanvasImmediate.spec.tsx#L50)、[PPT 实际退出](../../../frontend/e2e/ppt.spec.ts#L782)、[Designer 实际退出](../../../frontend/e2e/designer-discussion.spec.ts#L379)。
- [只读三轮严格退出](../../../frontend/e2e/react-all-canvas-cleanup.spec.ts#L135)、[只记录、不代为清理的原生探针](../../../frontend/e2e/fixtures/allCanvasResources.ts#L27)、[不放宽的空数组断言](../../../frontend/e2e/fixtures/allCanvasResources.ts#L151)。

### 本轮实际浏览器截图

下面均为 **Chromium 实际渲染＋模拟 API 数据**、390px 窄屏、原始 PNG，没有改图或隐藏浮层。键盘焦点、原生选字及页面导航的实际状态保留；长截图中的固定导航位于当时滚动位置。截图证明可见状态，不能证明退出清理成功。图片和采集时源码 SHA-256 见 [清单](screenshots.json)。原 [33 张图片及后续 6 张工作流图](../react-canvas-cross-review/README.md) 均与本轮基线逐字节一致。

| spdb 普通任务阶段 · 模拟数据 | tech-blue 模板流程 · 模拟数据 | github-white 角色流程 · 模拟数据 |
| --- | --- | --- |
| ![spdb 普通任务阶段，390px 键盘定位与选字，模拟数据](screenshots/spdb-stages-390-keyboard.png) | ![tech-blue 模板流程，390px 键盘定位与选字，模拟数据](screenshots/tech-blue-template-progress-390-keyboard.png) | ![github-white 角色流程，390px 键盘定位与选字，模拟数据](screenshots/github-white-roles-390-keyboard.png) |

本地证据目录为 `/workspace/react-canvas-all-cleanup/`。只选取明确的浏览器 PNG、图片/源码摘要和本文进入仓库，不提交原始环境日志、trace、凭据或私人数据。仅本地提交，没有推送、PR、合并、部署或 Slack 发送。
