# 活动手势即时清理：阻塞复核

历史诊断记录：本文及原始失败断言保持原样。用户随后授权应用自有 Pointer Events 交互替换，当前实现与验收见 [后续报告](pointer-gesture-review.md)。这不表示原生上游手势的公开销毁缺口已经得到修复。

2026-10-03，基于本地提交 `50221409a8ea05568422354c6dcba764ed610da7`。本轮仅续接活动连线/节点拖动的即时清理门槛，不改生产实现、依赖、网络或凭据，不扩展到全站旧问题。

**结论：门槛仍失败。** 保留当前 React Flow 原生手势时，没有找到受支持的即时销毁接口；本轮也未找到可直接升级的已发布修复版本。没有采用伪造全局 mouseup、删除其他组件监听、访问 d3 私有全局注册表或放宽计数断言。此文是阻塞记录，不是修复成功记录。

## 公开接口与资源所有权

本轮直接只读查询 npm 官方注册表的 `latest`：`@xyflow/react` 为 `12.12.0`、`@xyflow/system` 为 `0.0.83`、`d3-drag` 为 `3.0.0`，均与当前安装链一致。未安装或修改锁文件。

| 入口 | 已核对的行为与边界 | 官方及项目证据 |
| --- | --- | --- |
| React 根卸载 | Vue 适配器 `onBeforeUnmount` 调用 bridge；bridge 先禁止迟到 render，再同步 `root.unmount()`。该调用已正确执行，不能代替库对外部手势闭包的清理 | [项目 bridge:16](../../../frontend/src/react/bridge.ts#L16)、[适配器:25](../../../frontend/src/components/workflow/WorkflowCanvas.vue#L25)、[React root.unmount 官方说明](https://react.dev/reference/react-dom/client/createRoot#root-unmount) |
| ReactFlowInstance、useStoreApi | 实例提供节点、边、坐标和视口操作；store 可读写及订阅。未提供活动手势的实例专属 abort/dispose | [实例 API](https://reactflow.dev/api-reference/types/react-flow-instance)、[store API](https://reactflow.dev/api-reference/hooks/use-store-api) |
| XYHandle | `onPointerDown` 返回 `void`；类型只另有 `isValid`，没有 disposer 或 AbortSignal | [锁定 system 版本 types.ts，59–62 行](https://raw.githubusercontent.com/xyflow/xyflow/%40xyflow%2Fsystem%400.0.83/packages/system/src/xyhandle/types.ts) |
| 连线监听 | 四个 document 监听在 `onPointerDown` 内注册，清理位于私有 `onPointerUp`；取消 store 后仍依赖下一次合适的事件进入此闭包。没有 touchcancel 监听 | [锁定 XYHandle.ts，187–231 行](https://raw.githubusercontent.com/xyflow/xyflow/%40xyflow%2Fsystem%400.0.83/packages/system/src/xyhandle/XYHandle.ts) |
| cancelConnection / reset | 仅更新连接/store 状态；StoreUpdater 卸载时调用 reset。没有闭包监听的引用或销毁调用 | [store，407–416 行](https://raw.githubusercontent.com/xyflow/xyflow/%40xyflow%2Freact%4012.12.0/packages/react/src/store/index.ts)、[StoreUpdater，121–128 行](https://raw.githubusercontent.com/xyflow/xyflow/%40xyflow%2Freact%4012.12.0/packages/react/src/components/StoreUpdater/index.tsx) |
| Handle 回调 | 用户 `onMouseDown/onTouchStart` 在库启动手势之后调用；在回调里更改状态或属性，不能撤销已完成的监听分配 | [Handle，130–171 行](https://raw.githubusercontent.com/xyflow/xyflow/%40xyflow%2Freact%4012.12.0/packages/react/src/components/Handle/index.tsx) |
| 节点拖动 | React `useDrag` 的 cleanup 调用 `XYDrag.destroy()`，但 destroy 只拆节点 `.drag` 绑定。d3 在开始鼠标拖动时另向 window 注册监听，只在 mouseupped 中解除 | [React useDrag](https://raw.githubusercontent.com/xyflow/xyflow/%40xyflow%2Freact%4012.12.0/packages/react/src/hooks/useDrag.ts)、[XYDrag](https://raw.githubusercontent.com/xyflow/xyflow/%40xyflow%2Fsystem%400.0.83/packages/system/src/xydrag/XYDrag.ts)、[d3-drag，54–78 行](https://raw.githubusercontent.com/d3/d3-drag/v3.0.0/src/drag.js) |

应用已经在 [WorkflowCanvasReact.tsx:27](../../../frontend/src/react/workflow/WorkflowCanvasReact.tsx#L27) 清空本地拖动引用、取消连接并禁止卸载后的业务连接写回；[170 行](../../../frontend/src/react/workflow/WorkflowCanvasReact.tsx#L170) 已关闭节点和连线边缘自动平移。这些措施消除了所测悬挂 RAF，但没有获得上游闭包的销毁权。再次调用 root.unmount、清空 nodes、切换 readonly 或重置 store，不能被视为已验证的监听清理方案。

## 严格失败证据

诊断挂载实际 React Flow，没有替换成 Vue、静态画布或假的库实现。单测直接在 `unmount()` 返回后断言；浏览器按住实际鼠标或通过 Chromium CDP 启动触摸连线，再用 RouterLink 的 DOM `click()` 导航，避免普通鼠标点击先释放手势而掩盖问题。计数保持 `listeners === []`，没有放宽。

| 真实浏览器场景 | 路由离开后残留 | 再等待 100ms | 结果 |
| --- | --- | --- | --- |
| 连线只按下、未过阈值 | document：mousemove、mouseup、touchmove、touchend，4 项 | 仍 4 项 | FAIL |
| 鼠标连线已开始 | 同上，4 项 | 仍 4 项 | FAIL |
| 触摸连线已开始 | 同上，4 项 | 仍 4 项 | FAIL |
| 节点鼠标拖动 | window capture：mousemove、mouseup、dragstart、selectstart，4 项 | 仍 4 项 | FAIL |

四项中路由均已进入 `/workflows`，旧根脱离 DOM、画布根数量为 0；采样时 pending RAF、API mutation 与 pageerror 均为 0。jsdom 的三项直接根卸载诊断也全部 FAIL：连接各 4 项，节点为 3 项 window 监听；jsdom 不支持该浏览器的 selectstart 分支，因此节点计数相差 1，不是降低门槛。

证据措辞边界：浏览器 JSON 的 `immediate` 是路由和 DOM 断言后的首次采样，不是 unmount 返回瞬间；更晚仍残留，足以否定即时清零。trace 确认导航后没有测试发送的 mousemove/mouseup/touchend 命令；未另外记录全部浏览器自发原生事件，因此不声称已穷尽审计所有原生事件分发。单测的直接同步断言与此互相补充。

本轮未穷举多轮累积、其他根并行隔离、touchcancel、多触点、全部计时器/订阅/捕获资源，以及 100ms 之后的迟到行为。发现单次确定反例已足够判定当前门槛失败，不能反过来把这些未跑场景宣称通过。

## 影响与可行下一步

连线监听保留旧闭包及其状态引用。节点拖动的残留还可能影响离开后的页面：d3 的 window `dragstart/selectstart` 处理会调用 `preventDefault()` 和 `stopImmediatePropagation()`，可阻止文字选择及原生拖动，捕获的 mousemove/up 也会影响传播；只有后续对应释放路径才恢复。这是[官方 nodrag 源码](https://raw.githubusercontent.com/d3/d3-drag/v3.0.0/src/nodrag.js)及 [noevent 源码](https://raw.githubusercontent.com/d3/d3-drag/v3.0.0/src/noevent.js)直接支持的影响判断，本轮没有把它冒称为逐项浏览器实测。

| 路径 | 必须完成的工作 | 代价和当前状态 |
| --- | --- | --- |
| 优先寻求上游实例专属取消支持 | XYHandle 返回 disposer 或接收 AbortSignal；Handle/reconnect 在卸载与取消时调用。XYDrag destroy 必须终止自己拥有的活动窗口手势、恢复自己的选择抑制状态；取消不得触发成功连接/布局提交，也不得清理别的实例资源 | 当前锁定版本没有所需接口。需要上游补丁与发布，随后升级并执行严格门禁；本轮没有向上游发帖、提 PR 或修改安装包 |
| 应用持有自己的手势生命周期 | 用公开 `nodesDraggable={false}`、Handle `isConnectableStart={false}` 在启动前关闭库原生手势，保留 React Flow 的真实节点/边/Handle测量；应用接管 pointer capture、取消及最终意图提交，坐标使用公开 API，连接预览可使用 ViewportPortal | 可行的设计方向，尚未实现或验证。必须完整保留鼠标/触摸/键盘、拖动/点击连接、105px 首帧、焦点、锁定、新布局保护与单次提交；是交互实现替换，不能包装成一行销毁调用 |

公开配置参考：[Handle](https://reactflow.dev/api-reference/components/handle)、[ReactFlow](https://reactflow.dev/api-reference/react-flow)、[ViewportPortal](https://reactflow.dev/api-reference/components/viewport-portal)。不建议再增加只清 store、只屏蔽回调或仅拒绝路由离开的临时措施：它们不能满足根直接卸载的要求，且可能改变用户导航行为。

后续任一路径都必须在没有补发释放事件的情况下，验证连接阈值前后、活动节点拖动、触摸取消、三次以上进入/手势/退出、StrictMode、重复卸载和其他根不受影响；同时保持正常连接、105px 位移、锁定和新布局回写保护。当前不能通过该门槛，也不能据此宣布可以删除全部 Vue 回退路径。

## 独立复核、回归与交付边界

复用原有三名 `gpt-6.1-sol / xhigh`，未新增或替换成员。三次 `followup_task` 成功：`/root/react_ppt_canvas` 独立核对官方接口与固定 tag；`/root/react_legacy_canvas` 编写严格根卸载诊断；组长编写真实 Chromium 路由诊断，由非作者 `/root/react_flow_workflow` 独立阅读源码、四项结果及 trace，确认 FAIL 归因与上述措辞边界。不存在作者自测冒充独立复核。

- 严格新诊断：单测 **3 失败**、真实 Chromium **4 失败**，均为本门槛明确反例，不能计为通过。
- 正常行为对照：4 文件、**32 项单测通过**；3 文件、**19 项 Chromium 通过**，含正常原生连接/拖动、105px 首帧位移、锁定、新布局、桥接、回退与 SPA 往返。通过这些对照不改变严格门槛失败。
- 没有实施生产修复，故本轮未重复全量 1198 项、类型检查或生产构建；它们是 [上一轮](README.md#最终验证分类2026-10-03-utc) 的结果，不称为本轮新通过。未跑真实后端、模型、文件解析或发布。
- [原始基线 11 项失败](known-baseline-failures.md) 原样保留，不修复、不计通过。
- 33 张已提交截图及其 manifest 的字节和哈希保持不变。本轮不改变视觉，不以新截图冒充已修复的资源状态。

## 可复核的诊断源码

原样保留 [根卸载 probe](probes/WorkflowCanvasImmediate.probe.spec.tsx.txt) 与 [真实浏览器 probe](probes/react-flow-immediate.probe.spec.ts.txt) 为文档文本；没有加入默认测试发现，也没有修改正式测试为 skip/expected-fail。它们记录失败，不能作为绿色门禁。原始 JSON、日志与 trace 仅保存在本任务 Cloud 的 `/workspace/react-canvas-immediate-review/`，没有把环境日志或整个证据目录提交入仓库。

在该 Cloud 环境可将文档文本复制到它原来的临时位置后运行；仅在确认目标文件不存在时复制，运行后保留结果并删除自己创建的临时副本：

```sh
# 仓库根目录；浏览器 probe 的诊断输出使用此目录。
mkdir -p /workspace/react-canvas-immediate-review
cp -n docs/deliveries/react-canvas-cross-review/probes/WorkflowCanvasImmediate.probe.spec.tsx.txt frontend/src/react/workflow/WorkflowCanvasImmediate.probe.spec.tsx
cp -n docs/deliveries/react-canvas-cross-review/probes/react-flow-immediate.probe.spec.ts.txt frontend/e2e/react-flow-immediate.probe.spec.ts
npm --prefix frontend test -- src/react/workflow/WorkflowCanvasImmediate.probe.spec.tsx
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium npm --prefix frontend run test:e2e -- e2e/react-flow-immediate.probe.spec.ts --workers=1
```

预期现版得到上面的 3 项和 4 项失败，不应当通过补发事件、换成 Vue 夹具或放宽断言使它们变绿。其他平台需调整浏览器路径和诊断输出目录，未宣称跨平台复现通过。
