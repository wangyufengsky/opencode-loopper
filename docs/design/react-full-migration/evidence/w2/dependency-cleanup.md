# W2：只读角色流程图最终 pane 清理补充

这是本批真实生产路由验收发现的退出回归，不是新画布功能。锁文件仍固定 `@xyflow/react 12.12.0`、其 `@xyflow/system 0.0.83`，不升级、不 fork，也不改既有应用实例手势方案。

## 红证据与根因

第一轮 49 项 Chromium 为 37 通过、12 失败。其中两个 Roles 路由首采样在旧 React root 已脱离后仍保留 renderer 的 D3 `wheel`/`mousedown`；另三个活动 Roles pan 用例先在测试命中点寻找阶段失败，未取得有效退出样本。两类失败分别记录，不能把未执行到退出的用例当清理失败或通过。

安装源码 `@xyflow/react/dist/esm/index.mjs` 的 ZoomPane 创建 `XYPanZoom({domNode: zoomPane.current})`；实际目标是 `.react-flow__renderer`。卸载调用返回的公开 `destroy()`。原系统返回的方法只清理 zoom dispatch；前期补丁已补 extent observer 的 disconnect，但 `.zoom` DOM 注册仍存活。即使应用关闭原生 pan/zoom，D3 初始化仍会注册这些本地监听。不能因 DOM 已脱离便不计监听，也不能借下一次自然 mouseup、伪事件或清理别人的监听取得通过。

## 最小变更与支持依据

仅返回的最终 `destroy` 增加 `d3Selection.on('.zoom', null)`，继续 disconnect 本实例 extent observer。`update(userSelectionActive)` 使用的词法 `destroy` 保持原样，以免暂停选择便销毁活实例。D3 官方明确支持[解绑该 selection 的 `.zoom` 监听](https://d3js.org/d3-zoom#zoom_selection)，[selection.on 的命名空间规则](https://d3js.org/d3-selection/events#selection_on)保证边界为该 selection；[锁定的 XYPanZoom 上游源码](https://github.com/xyflow/xyflow/blob/%40xyflow%2Fsystem%400.0.83/packages/system/src/xypanzoom/XYPanZoom.ts)可逐项对照。

ESM 系统两个入口、系统 UMD 均覆盖；React UMD/CJS 内联了 XYPanZoom，必须另补内联入口。系统 UMD selection 是 `g`，React UMD 内联 selection 是 `p`。React ESM 的前期 ResizeObserver ref cleanup 保持原补丁。六入口完整原始/补丁后 SHA、原 tarball integrity、包版本和 package.json SHA 继续由仓库 manifest 锁定，见：

- [系统 manifest](../../../../../scripts/patches/xyflow-system-0.0.83-resize-cleanup.json)
- [React manifest](../../../../../scripts/patches/xyflow-react-12.12.0-resize-cleanup.json)
- [可复现补丁机制](../../../../../scripts/patch-xyflow-react.mjs)
- [锁定机制与边界测试](../../../../../scripts/tests/xyflow-react-patch.test.mjs)
- [实际 ESM / React UMD 生命周期测试](../../../../../scripts/tests/xyflow-pane-lifecycle.test.mjs)

版本、完整文件哈希、入口集合或包元数据不匹配即失败；没有模糊替换或静默跨版本应用。沿用原机制的预检、原子 staging 和失败回滚。不修改全局事件、不 monkeypatch ResizeObserver、不运行时修改库私有全局状态。

## 干净依赖复现

实际执行 `npm ci` 重装（483 包），不复用旧 node_modules 手工修改；最终 postinstall 对全部六入口应用补丁成功，随后 `patch:check` 随 build 再次通过。锁文件未改，依赖版本未变。

初次默认离线缓存缺 `registry.npmjs.org/zustand/-/zustand-4.5.7.tgz`，返回 ENOTCACHED；使用正式 npm ci 和 `/workspace/npm-cache` 完成下载。最终离线重装在受限进程环境中遇 esbuild spawnSync EPERM，经正常工具进程权限再执行离线 npm ci 成功。没有修改网络、代理、凭据或绕过包完整性检查；这些失败没有记作通过。

## 独立复核与真实测试范围

非补丁作者 A `/root/react_flow_workflow` 只读复核六入口完整 SHA 与实际测试，确认最终 destroy 和词法 pause 的边界、React 内联 selection、首次同步采样及另一实例隔离。C 另独立核对公开 D3 API 与生产资源观测。作者不是独立审查者。

新增两项真正加载已安装包的 Node/jsdom 测试均通过：

1. 实际系统 ESM：两实例；初始化 wheel/mousedown 正控；pause 保活监听与 extent；400×200 测量后缩放 1.5 得 `{-100,-50,1.5}`；最终 destroy 后首次同步采样仅有 foreign sentinel，observer targets 为零；重复 destroy 不动另一实例，另一实例仍可缩放。
2. 实际 React UMD/CJS：真实 ReactFlow/Provider root；renderer 初始化监听正控；同步 root.unmount 后首次采样旧 renderer 仅保 foreign sentinel，脱离元素没有残留 observer targets。

新测试最初两个失败来自夹具：把 renderer 错记为 pane，以及错误要求 update 正常替换的旧 wheel callback 仍 active。修正真实目标与合法配置后才执行最终断言；没放宽卸载后零自有资源门槛。真实 foreign 正控为原生 wheel listener；实际 D3 `.foreign` 注册未在这两项执行，其保留由既有机制测试及锁定 D3 源码支持。

全生产 Chromium 的最终结果、Roles 三皮肤各三轮活动 pan 首样、首次无输入资源计数与源码哈希见 [最终验证](verification.json) 和 [资源摘要](resource-summary.json)。首次采样前不补 mouseup、pointercancel、移动或 window blur。

这不是通用原生 D3 活动 window 手势销毁方案。应用仍通过公开开关关闭原生手势，由已验收的实例 Pointer Events owner 管理活动手势。以上证明目标观察关系与实例资源释放，不证明所有 GC 对象消失；账本有意保留引用，堆/GC 全回收未测。
