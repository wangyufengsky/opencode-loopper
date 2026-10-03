# 工作流画布 Pointer Events 交互替换

本轮从本地提交 `9152124f7a2bcee6d3f52f0795b3d0d418787b7d` 继续，分支仍为 `feat/react-canvas-migration`。这是节点拖动、端口连接及其关联视口手势的实现替换，不是调用一个销毁 API 的小补丁。React Flow 继续渲染、测量节点和端口、展示边与受控视口；没有改依赖安装包、API、graph/layout DTO、业务命令或存储格式。

**工作流原严格根卸载三项、原路由离开四项门槛已通过。** 手势结束不再依赖下一次自然释放事件；完整单测 1247/1247、受影响 Chromium 180/180 通过。该结论限定于下述工作流实现和已验证矩阵，不等于全站所有图形手势或真实服务均已通过。

## 公开接口和单一所有者

- 使用 React Flow 的 `nodesDraggable={false}`，并将每个节点的 `draggable` 设为 `false`，避免节点配置覆盖总开关；节点添加 `nopan`。依据 [官方交互配置](https://reactflow.dev/api-reference/react-flow#interaction-props)。
- 使用 Handle 的 `isConnectableStart={false}` 与 Flow 的 `connectOnClick={false}`，不再分配上游 XYHandle 活动连线监听；Handle 仍作为 React Flow 的真实连接位置。依据 [Handle 公共属性](https://reactflow.dev/api-reference/components/handle#props)。
- 坐标换算使用公开 `screenToFlowPosition`，实测尺寸通过公开 `getInternalNode(id)?.measured` 读取，连接预览通过 `ViewportPortal` 随同一视口变换；不读写库内部的活动手势状态。依据 [实例 API](https://reactflow.dev/api-reference/types/react-flow-instance) 与 [ViewportPortal](https://reactflow.dev/api-reference/components/viewport-portal)。`getInternalNode` 是官方公开实例方法；这里只读测量值，不修改 internals/store。
- 同一工作流画布的平移/双指缩放也由该控制器持有，关闭原生 `panOnDrag/zoomOnPinch`；这是为消除旧视口手势和程序化视口更新之间的竞争，不改变其他只读图或 PPT 实现。Ctrl＋滚轮保留原 `1.1` 倍缩放步进；普通滚轮交给页面滚动。
- [Vue 适配器](../../../frontend/src/components/workflow/WorkflowCanvas.vue) 仍是显示桥接；原页面持有唯一 Router、业务状态、历史与 API 命令。手势只在成功结束时发 `onLayout` 或 `onConnectPair` 意图，不自行发请求。原图校验继续拒绝重复边、循环、自身连接与受保护节点编辑。

## 实例资源与退出路径

[手势控制器](../../../frontend/src/react/workflow/pointerGestures.ts) 每次会话记录起点、原布局、graph/layout 基线与 pointerId；不会用后续鼠标释放事件替代卸载清理。

| 资源 | 拥有者 | 清理原则 |
| --- | --- | --- |
| pointermove / pointerup / pointercancel / lostpointercapture 监听 | 当前实例的 section | 只移除本次会话注册的同一函数与目标 |
| blur 监听 | 当前 section 所属 window；函数由会话创建 | 会话结束直接移除，不触碰其他实例 |
| 端口点按等待期间的 blur 监听 | 当前实例的点按连接意图 | 第二端口完成、取消、其他手势接管或卸载时移除；不与原加号连接模式并存 |
| Ctrl＋滚轮监听 | 当前 section 的组件生命周期，非 passive | 使用同一函数在 unmount 时移除；普通 wheel 不阻止默认行为 |
| pointer capture | 当前节点、端口或画布 section，逐 pointerId 记录 | 先令会话失效并移除监听，再释放捕获，避免 lostcapture 重入提交 |
| 手势预览与未提交位置 | React 实例临时状态 | 取消回滚；新权威布局优先；卸载后不写业务 |
| RAF、计时器、额外业务订阅 | 手势控制器不创建 | 不能把“没有创建”扩大为全站所有资源均已穷尽验证 |

退出矩阵包括正常 pointerup、pointercancel、lostpointercapture、window blur、Escape、权限锁定、graph/layout 更新、程序化 fit/reveal/zoom、路由离开和根卸载。Pointer Capture 使用 [W3C Pointer Events 的公开机制](https://www.w3.org/TR/pointerevents3/#pointer-capture)，不伪造全局 mouseup，不删除其他组件监听。

独立审查发现上游 filter 对节点/边的中键 mousedown 在 `panOnDrag=false` 前有特判。应用在本画布内接管中键平移，用标准 Pointer Event `preventDefault()` 抑制兼容鼠标事件，局部 capture 阶段也阻止中键 mousedown 到达第二个控制器；不修改库 filter 或全局监听。工具栏、表单和链接不启动该手势，其原生默认动作保留。

连接预览的 `ViewportPortal` 随画布固定挂载，只切换内部 SVG。这样不会在第一次连线后才向固定 portal 容器注册 React 委托事件。测试没有按回调名称过滤这些监听；React 固定渲染容器的委托与临时手势资源明确分开，不能把容器移除后的垃圾回收当作全局监听清理。

## 验收与独立复核

原三名 `gpt-6.1-sol / xhigh` 的 `followup_task` 均成功续接，没有新增或更换成员：

| 原任务 | 本轮职责 |
| --- | --- |
| `/root/react_flow_workflow` | 生产手势实现；复核其他作者的验收覆盖 |
| `/root/react_legacy_canvas` | 真实 React Flow 单测、严格根卸载矩阵；独立审查生产实现 |
| `/root/react_ppt_canvas` | 真实 Chromium 生命周期、触控、主题与窄屏；独立审查生产实现 |
| Astra 组长 | 接口、桥接与业务合同测试、集成、完整前端门禁及本地交付 |

原 [严格失败记录](immediate-cleanup-gate.md) 和原样诊断文本保留为历史证据。新的单测必须先证明 Pointer Events 手势确实激活，再在 unmount 返回后直接断言监听、RAF、capture 清零；浏览器必须先断言清理，再允许测试设备释放。禁止仅向新实现发送不生效的旧 MouseEvent，获得虚假的绿色结果。

### 独立审查发现及处理

| 发现者与问题 | 修复与验证证据 |
| --- | --- |
| legacy 组员：旧端口 tap 后开始拖动仍保留旧连接意图 | [控制器](../../../frontend/src/react/workflow/pointerGestures.ts#L123) 越阈值即清旧 tap；[四种接管回归](../../../frontend/src/react/workflow/WorkflowCanvasReact.spec.tsx#L95) 与 [实际两种连接入口互斥](../../../frontend/e2e/workflow-pointer-contract.spec.ts#L104) |
| legacy 组员：中键在 node/edge 上绕过上游 `panOnDrag=false` | [本实例中键捕获入口](../../../frontend/src/react/workflow/WorkflowCanvasReact.tsx#L124)；[严格卸载单测](../../../frontend/src/react/workflow/WorkflowCanvasImmediate.spec.tsx#L321)；[节点/边各三轮退出与正常 105px](../../../frontend/e2e/react-canvas-pointer-lifecycle.spec.ts#L344) |
| legacy 组员：关闭原生 pinch 后原 Ctrl＋wheel 入口丢失 | [实例非 passive wheel](../../../frontend/src/react/workflow/WorkflowCanvasReact.tsx#L67)；[可编辑/只读缩放与普通滚轮](../../../frontend/src/react/workflow/WorkflowCanvasReact.spec.tsx#L164)；[真实滚轮取消旧连接/拖动](../../../frontend/e2e/react-canvas-pointer-lifecycle.spec.ts#L374) |
| PPT 组员的严格浏览器探针：首连线动态 Portal 在固定容器新增 React 委托监听 | 固定挂载 Portal，内部 SVG 按需显示；保留严格计数，没有按回调名称过滤；[第一轮三次路由退出门禁](../../../frontend/e2e/react-canvas-pointer-lifecycle.spec.ts#L149) |
| 组长全量单测、legacy 独立定位：选择后边暂时消失 | `getNode` 返回未带实测的受控输入 DTO，改为公开 `getInternalNode` 读取实际尺寸；[立即保留同一边](../../../frontend/src/react/workflow/WorkflowCanvasReact.spec.tsx#L36)。原两页选择测试不增加等待、不改断言，36/36 重跑通过 |
| workflow 组员独立审查浏览器探针：capture blur 也记录元素失焦 | [探针](../../../frontend/e2e/fixtures/pointerResources.ts#L60) 增加 target 与坐标；窗口失焦只接受 `target=window`；路由首快照禁止新增 move/up/cancel/window blur，避免自然事件替清理 |

作者自测不计为独立复核。legacy/PPT 两位没有修改生产手势文件；workflow 作者只读检查其他人的测试，不把自己的实现自评冒称独立生产审查。

最终非作者复核读取了 45 份浏览器原始指标：24 份按住手势离路由、6 份取消、11 份正常结束、2 份滚轮取消、2 份滚轮后卸载。26 份卸载快照均为 root 0、监听 `[]`、RAF `[]`、capture `[]`、实例 wheel `[]`；24 份按住退出在开始与首次退出快照之间没有 move/up/cancel/window-target blur。两份窗口失焦记录均为 trusted 且 target 为 window；双指用例明确记录 first pointerup 和另一 pointerId 的后续移动，未追加输入修正结果。指标原件保留在任务工作区，不作为环境日志提交。

PPT 组员独立核对生产实现、原四场景 12/12 次退出与六张最终图片：桌面三皮肤的节点、边和连接预览可见；390px 图片确实保留原有详情浮层遮挡，没有水平溢出，未冒称无遮挡画布。该审查确认本轮工作流门槛无剩余阻塞，同时保留文末共享只读图和设备验证边界。

### 严格门槛及合同覆盖

| 门槛/合同 | 证据与采样边界 |
| --- | --- |
| 原严格根卸载三项：连接按下、活动连接、活动节点拖动 | [Immediate:40](../../../frontend/src/react/workflow/WorkflowCanvasImmediate.spec.tsx#L40) 扩展成 mouse/touch × 四种手势 8 项，另含裸画布平移。先证明会话、捕获和位移/预览激活；`unmount()` 返回后首次同步快照必须监听 `[]`、RAF `0`、capture `0`，随后才发送迟到事件 |
| 原四项 SPA 路由门槛 | [浏览器:149](../../../frontend/e2e/react-canvas-pointer-lifecycle.spec.ts#L149) 原四情形每种三轮，再加背景平移；原生鼠标/CDP 触摸仍按住，通过 RouterLink DOM click 导航。路由与 DOM 断言后的首次快照资源全零，禁止期间出现 move/up/cancel/window blur；这不是声称浏览器 API 能在 unmount 调用栈里采样 |
| 重复 root 与其他所有者不受影响 | [StrictMode 三轮](../../../frontend/src/react/workflow/WorkflowCanvasImmediate.spec.tsx#L67)、[双实例同时触摸](../../../frontend/src/react/workflow/WorkflowCanvasImmediate.spec.tsx#L86)、[父 React 保留的嵌套岛](../../../frontend/src/react/workflow/WorkflowCanvasImmediate.spec.tsx#L126)；浏览器监听记录不随新一轮 arm 丢弃旧项，并保留无关监听/RAF 哨兵 |
| cancel、blur、lost capture、异常 capture API | [同步退出矩阵](../../../frontend/src/react/workflow/WorkflowCanvasImmediate.spec.tsx#L143)、[capture 缺失/抛错](../../../frontend/src/react/workflow/WorkflowCanvasImmediate.spec.tsx#L202)、[真实取消矩阵](../../../frontend/e2e/react-canvas-pointer-lifecycle.spec.ts#L181)。CDP touchCancel 由 Chromium 产生 trusted pointercancel；lostcapture 使用公开释放捕获及真实输入，非全局伪造事件 |
| 105px/20px 首帧、缩放、正常保存与撤销 | [真实鼠标 0.5/1/2 倍](../../../frontend/e2e/react-canvas-pointer-lifecycle.spec.ts#L224)、[真实 CDP 单指](../../../frontend/e2e/react-canvas-pointer-lifecycle.spec.ts#L264)、[反向连接及一次历史](../../../frontend/e2e/workflow-pointer-contract.spec.ts#L38)；按下原点不因阈值重置 |
| 锁定、新布局、程序化视口，迟到输入不写回 | [锁定与权威布局](../../../frontend/src/react/workflow/WorkflowCanvasReact.spec.tsx#L366)、[平移遇新视口/reveal](../../../frontend/src/react/workflow/WorkflowCanvasReact.spec.tsx#L256)、[根层锁定/新布局取消](../../../frontend/src/react/workflow/WorkflowCanvasImmediate.spec.tsx#L167)；真实 Ctrl＋wheel 另验旧拖动/连线释放不覆盖新视口 |
| 图与命令唯一所有者 | [业务 E2E](../../../frontend/e2e/workflow-pointer-contract.spec.ts#L19) 实际指针触发重复/循环/自连接时零边变更、零撤销、零请求；正反端口 tap、键盘、加号入口及只读可移动要求各自验证 |
| 双指到单指 | [浏览器:277](../../../frontend/e2e/react-canvas-pointer-lifecycle.spec.ts#L277) 裸画布两指 1.4 倍缩放，释放一指后剩指准确 25px/15px 平移，最终清零；记录 trusted pointerId/坐标。等待同一移动事件及画面渲染，不额外派发输入；退出清零采样不采用此等待 |

### 最终验证分类

| 分类 | 结果 |
| --- | --- |
| 全量单测通过 | 188 文件，1247/1247；最终源码冻结后重新运行，123.31 秒。包含 35 项 React 行为、38 项严格 Immediate，以及原 API/回执/上传/草稿/版本/默认模型/PPT 合同 |
| 通过 | `npm run typecheck`；`npm run build`，55.85 秒；`test:tooling` 7/7；`test:accounting` 13/13 |
| 受影响浏览器通过 | 最终同一批 Chromium 46 文件、180/180，8.4 分钟；含新 29 项生命周期与 6 项业务合同，以及既有工作流/React/PPT/Designer 回归。截图在本批重新采集，不沿用修复前的 34/35 结果 |
| 历史失败单列 | 原 11 项基线失败，本轮未修改或重跑，不计为通过；见 [原始明细](known-baseline-failures.md) |
| 未跑 | 完整 Chromium 清单为 67 文件、280 项，本轮运行其中 180 项；剩余 100 项含历史 11 项。不提供全站全绿结论；未验证真实后端/模型/文件解析、非 Chromium 或实体设备 |

没有独立 lint 脚本，未冒称执行。工具链初次运行被沙箱 `spawnSync git EPERM`、`spawnSync rg EPERM` 阻止，按正常权限机制原命令重跑成功；未改测试或网络/代理/凭据。生产构建保留第三方 PURE 注释与大 chunk 警告；双运行时包体积不因此得到改善。

模拟浏览器批次另记录一次 `/api/story-accounting/events` 代理连接 `127.0.0.1:8080 ECONNREFUSED`；该后台通道没有真实后端，相关前端断言继续执行。这不是后端已运行的证据，也未为消除日志而改变服务或代理设置。

本轮失败过程保留在 Cloud 本地日志，未混入仓库：最初浏览器 12/29 通过、17 失败；第二轮 34/35 通过，双指转单指因 CDP 移动尚未派发而提前采样失败。独立静态 Chromium 探针证明事件延后到 RAF，最终测试等待同一次 trusted move 与原精确位移，不补发输入。取消后的冗余 touchEnd、真实窗口 focus 模拟、窄屏命中浮层等测试方法问题逐一修正，没有放宽清零断言。全量单测首轮 1244 通过、2 失败暴露实测丢失的真实渲染回归，修复后完整重跑。修复前中断的浏览器批次为 28 通过、1 中断、151 未跑，不计入最终通过总数。

复现命令（`frontend/`）：

```sh
npm test
npm run typecheck
npm run build
npm run test:tooling
npm run test:accounting
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium npm run test:e2e -- 'workflow-|react-|ppt.spec.ts|designer-discussion' --workers=1
```

`CANVAS_POINTER_EVIDENCE_DIR` 可指定新图/指标的工作区输出，默认在 `test-results/react-canvas-pointer`；`CANVAS_EVIDENCE_DIR` 指定既有截图套件的输出。只将六张明确的 PNG 与校验清单复制进仓库，没有把整个输出目录、环境日志或 trace 入库。新图片在 [README](README.md#pointer-手势替换后的实际截图) 直接显示，[pointer-screenshots.json](pointer-screenshots.json) 同时记录采集时生产文件与测试文件 SHA-256。原 33 张 PNG 与原 manifest 的字节摘要全部核对未变。

## 历史与验证边界

原 [11 项基线失败](known-baseline-failures.md) 继续独立记录，不修复、不计通过。原 33 张截图及 manifest 保留。新的浏览器截图必须明确标注实际 Chromium 渲染与模拟 API 数据；不由浏览器前端验收推断真实 Java 后端、模型调用、文件解析、部署或其他浏览器/实体触控设备已通过。

本轮浏览器设备范围是 Cloud 系统 Chromium、Playwright 鼠标/键盘/wheel 与 CDP 触摸输入。双指只支持裸画布加入第二指；第二指从节点/Handle 开始不升级为 pinch。真实 CDP 单指的连接、拖动和 touchCancel 已验证；双指中途离路由/touchCancel 的真实浏览器组合未单独验证，其取消/根卸载由 jsdom 单测覆盖。没有 iOS Safari、Firefox、实体触屏/触控板、触控笔或屏幕阅读器结果。

窗口 blur 测试用公开 CDP 关闭 Playwright 默认的 focus emulation 后切到新页，要求真实 `isTrusted` 窗口 blur；这不等价于物理操作系统的切窗验收。jsdom Pointer/capture 辅助只补平台缺失，不能作为原生设备证据。

PPT 组员还单列了范围外的源码风险：[ReadonlyDiagramFlow:22–24](../../../frontend/src/react/diagrams/ReadonlyDiagramFlow.tsx#L22) 的 Task/模板/角色只读图关闭了节点拖动与连接，但仍保留 [上游默认背景 pan/pinch](https://github.com/xyflow/xyflow/blob/%40xyflow%2Freact%4012.12.0/packages/react/src/container/ReactFlow/index.tsx#L95)。固定版本 [XYPanZoom.destroy](https://github.com/xyflow/xyflow/blob/%40xyflow%2Fsystem%400.0.83/packages/system/src/xypanzoom/XYPanZoom.ts#L229) 与 [d3-zoom 窗口手势](https://github.com/d3/d3-zoom/blob/v3.0.0/src/zoom.js#L274) 提示活动平移卸载可能仍保留监听；这是源码推断，这些图没有进入本轮严格探针，未通过浏览器复现或排除。本轮关闭的是工作流原三项根卸载/四项路由门槛，不能推广为所有 React Flow 页面任意手势均无泄漏。若继续收紧到共享只读图，应先增加独立严格反例，再决定接管其视口手势；本轮不改该原有模块。

替代方案的代价是应用现在维护阈值、坐标、点击/拖动互斥、捕获取消和视口组合手势。以后 React Flow 升级必须重跑此矩阵；不能重新打开原生手势形成双控制器。边缘自动平移继续关闭。运行时回退仍只在下一实例创建时生效，活动 File、pending、dirty draft、未知回执或自动保存不热卸载、不重放命令。

本轮仅本地实现和提交，不推送、PR、合并、部署或发送 Slack；原 API 幂等、未知回执、File、草稿、版本冲突与唯一命令所有者的边界不变。
