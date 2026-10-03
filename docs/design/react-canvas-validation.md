# React 画布阶段交付与验证记录

当前续接验收见 [锁定依赖 ResizeObserver 清理修复](../deliveries/react-canvas-all-cleanup/resize-observer-patch.md)；[全部画布类别退出清理](../deliveries/react-canvas-all-cleanup/README.md) 保留此前发现上游缺陷的历史结果，工作流手势替换见 [Pointer Events 验收](../deliveries/react-canvas-cross-review/pointer-gesture-review.md)。本文是首次迁移历史，以下旧计数与即时清理限制不能替代后续实现的结果。

本文保留首次阶段提交 `63d2462f7b69d76bf1a27c9aa597be086c69ccfd` 的历史结果。后续独立交叉验收、修复代码 `628ab18d88173910139dd9709054b17d10e534cb`、1198 项单测、分批 Chromium 结果及仓库内 33 张新截图，见 [本轮交叉验收](../deliveries/react-canvas-cross-review/README.md)。该记录明确列出仍有的基线失败与 React Flow 活动连线即时监听清理限制，不能用本文旧结果替代当前门禁。

本地实施基线：`c26bf3bf7590424bd95bf83c093ae5740a068572`。本地分支：`feat/react-canvas-migration`。读取远端时 `main` 为 `3fe0fc459fa21e196ce64655da11f3c3402929c6`，原画布改版分支仍指向上述基线。

本阶段只交付前端本地可审查版本，没有推送、PR、合并、部署、后端/JAR 改动或付费模型调用。原工程和原画布分支保持原样。

## 实施与审查

实际创建恰好三名组员，三次 spawn 均显式传入 `model=gpt-6.1-sol`、`reasoning_effort=xhigh` 并成功。工具返回的 canonical task 名称即以下任务标识；未虚构另一个数字 ID。

| 任务标识 | 模型与推理 | 隔离分工 |
| --- | --- | --- |
| `/root/react_flow_workflow` | gpt-6.1-sol / xhigh | 工作流 React Flow、Canvas 适配器与相邻回归 |
| `/root/react_ppt_canvas` | gpt-6.1-sol / xhigh | PPT 自由对象画布、缩略预览；交叉评审后补跨需求回执隔离 |
| `/root/react_legacy_canvas` | gpt-6.1-sol / xhigh | Task/模板/角色 React Flow、Markdown Mermaid 图岛与安全服务 |

组长持有路由/桥接/依赖/设置入口、端口连接业务适配、E2E、文档、集成和最终门禁。文件所有权变更均明确移交，没有由多个组员同时修改同一源码文件。

范围与接口见 [迁移边界与后续清单](react-canvas-migration.md)。所有画布类别默认进入真实 React 路径；外层页面、业务命令与订阅仍由 Vue 持有。兼容画布保留，偏好仅在下一次实例创建时应用。

集成中修复了程序化视口同步污染撤销栈、拖动中锁定残留坐标、旧拖动覆盖新布局、选择时已测量边闪烁，以及拖动阈值丢失首帧位移等问题。额外修复迁移前已存在的跨需求迟到命令/读取覆盖新页面风险；原有守卫批准离开后使旧作用域失效，旧 catch/finally 不能清除新命令。

## 依赖与检查

- 官方 npm 精确安装并写入锁文件：React/React DOM `19.3.0`、React Flow `12.12.0`、Vite React 插件 `4.7.0`；保留 Vite `6.4.3`。新增包来自 `registry.npmjs.org` 并含 integrity。
- 最终锁文件 `npm ci --no-audit --no-fund --cache /workspace/npm-react-migration-cache` 成功，安装 420 个包。单独指定工作区缓存，未修改 npm、网络、代理或凭据设置。
- 最终全量 Vitest：184 个文件、1169 项全部通过。实际 React/React Flow 交互、Vue 桥接与兼容画布测试分别执行，未把 React 实现替换为旧 Vue 夹具。
- Typecheck、生产构建、`test:tooling`（7 项）、`test:accounting`（13 项）、文档检查及 diff 检查通过。
- 工具链初跑因沙箱子进程 `spawnSync git EPERM`、`spawnSync rg EPERM` 失败；按正常权限机制重跑原命令后通过，没有改写或跳过测试。
- 未配置独立 lint 脚本，未声称运行 lint。构建仍有第三方 PURE 注释与大于 500 kB chunk 警告；当前双运行时主入口约 1.66 MB（gzip 约 455 kB），不能据此声称性能提升。

## 浏览器失败的基线对照

完整 Chromium 首轮执行 61 个文件、229 项：217 通过，12 失败。工作流拖动用例原先断言 CSS `left`，已改为可见位移合同，随后继续暴露并修复 React Flow 拖动阈值导致首帧位移丢失的问题；最终真实浏览器保留并通过完整 105px/20px 位移断言。

最终针对全部 `workflow-` 与 `react-canvas-` 用例复核：112 项全部通过（5.3 分钟），含新增的三皮肤 390px 窄屏用例。PPT 的 12 项及角色/Designer 的三皮肤 React 检查已在前述全量执行中通过；Designer 展开完整文档后的三皮肤截图另行复核通过。最终套件共 62 个文件、232 项，未声称在最后一轮一次性全量通过，也不把重复执行次数相加。

其余 11 项已在独立 detached 基线工作区、未经源码修改的 `c26bf3bf` 上逐项重跑，全部复现同样失败，不计为新增迁移回归，也不计为通过：

| 文件 | 项数 | 确切失败 |
| --- | --- | --- |
| `e2e/database-progress.spec.ts` | 2 | 旧测试等待“主机”输入框，实际页面字段已变化，超时 |
| `e2e/document-template-tasks.spec.ts` | 2 | 业务断言完成后写 `/private/tmp/loopper-document-template-*.png`，Linux Cloud 返回 ENOENT |
| `e2e/read-consistency.spec.ts` | 1 | 等待旧“自动化检测状态”，该旧路由已重定向 |
| `e2e/roles.spec.ts` 的权限预估用例 | 6 | 期待“仍需运行时核定”，基线实际为“调用条件待运行时核定” |

本阶段保留这些失败的原始断言与证据，没有为了制造全绿修改无关产品语义。

## 证据与实际覆盖

Cloud 本地证据目录为 `/workspace/react-canvas-evidence/`，原始日志与失败 trace 不进入源码提交。`final/` 下是 Chromium 对明确的模拟 API 数据实际渲染生成的图片，覆盖三皮肤的默认/空白/选中/取消、规划/执行、PPT、任务阶段、模板步骤、角色流程、旧 Designer 图示与窄屏。PPT 的预览制品本身也是 fixture，不证明真实模型生成效果。

本地 `README.md` 和 `index.html` 提供截图索引。仓库原有 `canvas-phase1` 图片属于上一轮 Vue 界面改版，不应当作本轮 React 验收图片。

真实 React 单测覆盖工作流端口拖线、Esc 取消、缩放坐标、平移与只读；PPT 指针/键盘、锁定、修订取消和导航；Mermaid 清洗、迟到渲染、主题、卸载；Vue 桥接与兼容回退单列验证。纯 TS 回执合同与跨需求复用同一 Vue 实例的乱序测试继续独立执行。

E2E 包含原生拖动/连接、选择/重复选择/取消、候选/另存预览、上传失败与未知回执同 key 重试、运行时偏好变化不卸载活动画布、dirty/File 离开守卫、默认模型、三皮肤和窄屏。测试显式检查 `data-canvas-runtime="react"`；不能用 Legacy 通过替代 React 验收。

未验证真实 Java/API 持久化、DeepSeek/其他真实模型、真实文件解析及外部发布；未运行 Maven/JAR 门禁；未部署，所以远端 CI 未触发。后续全站 Vue 退场仍须逐路由迁移原业务所有者，并满足边界文档中的退出门槛。
