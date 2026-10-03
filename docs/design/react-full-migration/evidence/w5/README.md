# W5：工作流、需求与 Designer 生产迁移

**本地验收记录，尚未发布。** 基于 W4 `5c181fc39e26249650df851fe9a7c8a68cab519d`，分支 `feat/react-full-migration`。本波五条既有路由接入真实 React 页面与纯 TypeScript 业务所有者，Vue Router 暂时仍是唯一 history owner。没有推进 W6/deVue、修改后端、调用付费模型或执行 GitHub/Slack 写入。

优先查看 [三皮肤生产截图](screenshots/README.md)、[原 41 条红测映射](w0-mapping.json)、[最终验证](verification.json) 与 [严格退出资源样本](resources.json)。截图是 Chromium 实际运行生产 dist，采用明确的模拟 DTO/运输故障；不是 W1 demo、设计原型或真实后端证明。

## 入口、功能与所有权

| 既有入口 | React 页面 / 纯 TS owner | 保留能力及披露路径 |
|---|---|---|
| `/workflows/new`、`/workflows/:id` | WorkflowEditorPage / editorController | 创建、编辑/只读、原版本读取、图定义与布局独立回执、另存；选择节点/边编辑，更多工具提供检查、定位、自动布局；公共输入、角色、预设、专业模块、撤销/重做保留。 |
| `/requirements/new` | NewRequirementPage / newController | 项目、流程、名称、目标四字段；原 templateRevision/key/body 冻结、创建和精确 accepted 页面交接。没有上传字段、没有新增 by-request GET。 |
| `/requirements/:id` | RequirementPage / controller + retained 子 owner | 计划/布局/部分成功、候选与 PlanDiff、暂停调整、模型读取/默认归属、开始/单步/直到节点、人工输入、原 attempt 停止/恢复、提前结束、上传/补传/正文与固定输入、代码成果预览/提交/推送/回填、保存为模板及预览。选择节点/边和更多操作提供完整入口。 |
| `/designer?sessionId=…` | DesignerPage / controller | 历史会话/权限/版本、讨论及顺序/附件、问题、画像、包/编译/规范/最终确认、重开和重新设计、停止证明、消息 multipart、流与 REST 核对、旧稿复制/报告转换。原无 session redirect 与 `historyOnly=true` 保持。 |

完整旧→新功能/断言文件与行号见 [Workflow](workflow.md)、[Requirements](requirements.md)、[Designer](designer.md)。[旧递归盘点](baseline-inventory.json)包含 84 个旧 Vue 组件/页面引用、92 个不同直接 API 方法引用，不能解释成全部重写或 92 条写命令。[新模块索引](module-inventory.json)只计生产 TS/TSX 文件/导出声明，排除夹具及 W0 委托入口。

每个页面由 retained 业务 owner 发起唯一命令/订阅。渲染、主题、模型面板开关没有隐式写入；视图 lease 与命令身份分开。父需求 owner 合并子 owner 的 dirty/File/pending/unknown 风险。路由守卫批准后才卸载；同路径 query 的已提交切换会退休旧 scope，旧 go/back/retain 不能作用于新 root。普通 dirty 明确确认；pending/unknown/accepted-readback 持续阻断不安全离开。

## 本波修复与独立审查

团队恰好复用原三员：A `/root/react_flow_workflow` 实现 Workflow、审 C；B `/root/react_legacy_canvas` 实现 Requirements、审 A；C `/root/react_ppt_canvas` 实现 Designer、审 B 与组长共享入口。模型配置沿用原实际创建的 `gpt-6.1-sol/xhigh` 回执；本波使用原任务 followup，没有新建/替换成员。作者自测不计作独立评审，重复 run 不累加为唯一测试数。

已修复并保留红→绿证据：

- B 对 A 的独立审查复现：图定义已接受后布局明确 409/422 拒绝，原实现会丢分段回执，恢复时再写已接受的图。现保留原 graph receipt 与布局 key/body/CAS，明确显示 `PARTIAL_REJECTION` 并阻断离开；只有用户显式操作才重试同一布局。连续拒绝仍保持阻断，不伪称保存成功、不自动换身份或放开编辑。原探针红→绿，作者与非作者最终各 109/109（同一组场景，不相加）。
- C 初始已接受回执的 foreign project/draft 不能消费原 File 或解除 accepted 阻断；属于保留初始 capability 的合同夹具，生产 Designer 路由仍 history-only。LoopSpec 原 20/300/60 数值上限补齐。
- B 需求真实 mount 首次读取恢复；边条件编辑补回；只读 Choice/Content StrictMode replay 不会退休 retained owner。确认后的 GET 必须追上原 accepted revision/version/state，旧 GET 不能解除阻断或发起新 key。
- Designer 直接设计最终评审的原“重开工作包”入口恢复，沿用 approvedDesignRevision CAS。与重新设计是不同原命令，确认期间变更会作废旧确认。
- 组长在实际截图发现画布基础 CSS 仅由旧 Vue 页面加载：提取原 canvas-only 规则到框架无关 `workflow-canvas.css`，旧 CSS 与 React 同用；需求选中面板在画布同排并可滚动。实际浏览器检查节点尺寸、边 `fill:none`、工具与面板在桌面视口内。
- 同一路径 Designer query 切换的旧 bridge retirement 原红转绿。SVG 连接预览改为普通 root 子元素，保持原路径、translate/scale 和实例手势。原严格账本发现额外 ViewportPortal 容器上 38 个 React 元素代理监听；全局监听/观察目标/RAF 原已为 0，账本强引用**不能证明 GC 泄漏**。未放宽断言、删除他人监听或调用库私有状态。见 [修前诊断](portal-diagnosis.json)。

正常拖拽首帧屏幕 105px/20px 在 0.5/1/2 倍缩放下验证；连接、撤销/重做、键盘取消、锁定及新布局防旧回写复用并复跑原画布合同。预览仅用普通 SVG 与已有公开 React Flow 节点/边；[React Flow 官方 ViewportPortal 文档](https://reactflow.dev/api-reference/components/viewport-portal)和 [React createPortal 文档](https://react.dev/reference/react-dom/createPortal)说明原额外 portal 归属，未补丁依赖或改内部手势。

## 回执与资源边界

graph/layout/save-as、Designer 双阶段复制和需求计划分开保留部分成功与原 receipt。unknown 仅用户显式恢复原 key/body/File/id/revision；accepted 只用已有合法 read 核对。keyless API 遵守实际版本/状态/原 scope 读取，不发明请求键或 receipt GET。断开流、关闭面板不代表服务端停止。

严格资源样本在 root/真实 SPA route 退出第一时刻读取，无下一次 mouseup/touch 输入；检查 owned listeners、观察 target、RAF、capture、timer 与同文档外部 sentinel。原生 Designer SSE 验证 Last-Event-ID 17 重连、REST 权威读取、关闭及停止后不再轮询。具体计数见最终 JSON，不用 Workflow 通过替代其他页面。

Playwright 自身 main-world 命中监听在 root 前初始化，保持原资源计数断言。审计账本仍保留原观察器/元素引用；观察关系解除不等于所有对象都被 GC。既有 Ant 模块鼠标位置 timer、应用 story 订阅等其他 owner 的证据与组件自身资源分开。

## 验证结果与限制

冻结后的最终结果：

| 验证 | 最终结果 |
|---|---:|
| W5 相关单测 | 428/428 |
| 全量前端单测 | 2332/2332，0 fail/skip |
| W0 原合同 | 111/111；本波原 41 红逐项转绿 |
| W5 新增唯一单测（相比 W4） | 275；原断言出现次数删除 0 |
| W5 真实生产 Chromium | 49/49，0 fail/skip/flaky |
| W5 严格退出首样 | 64；6 次活动手势真实路由正控 |
| W2 / W3 / W4 生产回归 | 49/49、31/31、29/29 |
| W2 / W3 / W4 严格首样 | 51、69、44 |
| W1 官方隔离 React/Ant dev 夹具回归 | 41/41；18 次严格卸载（非 W5 生产证据） |
| typecheck / build | 通过；构建大包体/PURE 警告仍记录 |
| tooling / accounting | 33 Node + 6/4 Python / 13，全部通过 |
| 最终三皮肤生产 PNG | 30 张；连续两次字节一致 |

生产模块盘点：46 个 TS/TSX 文件、68 个导出组件声明、11 个 owner factory；117 个来自实际 `@/api/` import 的不同 AST 引用，包含读取和写入，不能当成 117 条命令。模块拥有职责仍复用 W1–W4 的接口与公共 API。[非作者审查摘要](independent-review.json)、[断言保留比对](assertion-preservation.json)、[恢复协议](browser-protocols.json)、[旧波退出回归](regression-resources.json)均独立可审查。

最终命令、唯一计数、退出码、源与输出 SHA256 集中在 [verification.json](verification.json)。[source-manifest.json](source-manifest.json)固定运行源码/测试/依赖合同，检查验证后字节没有变化。原始日志、trace、失败候选及自然事件样本保存在任务仓库外；仓库只提交代码、模拟截图和可审查摘要。

W0 原 41 条名称、阶段、身份/引用/调用次数和首样清理要求保留，通过真实 React/纯 TS owner 委托执行；不删除/skip/降低断言。历史 11 个旧 E2E 的 W0 根因映射与 Automations 已有 read/export 消费者仍见 [W0 原证据](../w0/README.md)及 W3。原旧全站 E2E 尚未完整重跑/重新接入，不能因 W0 转绿称它们全部关闭或全站已验收。

本波没有新增依赖/锁文件变化或重新冒称干净 npm ci；固定依赖补丁的既有 `patch:check` / tooling 门禁已复跑。最终干净安装、history 接管、旧测试迁移和零 Vue 是后续 W6/W7 门槛。项目没有 lint script，不声称 lint 通过。大包体/PURE 注释 warning 保留为后续非阻断技术债。

真实后端、模型、物理文件解析、真实 Git 提交/推送/回填、跨刷新服务端未提供的完整恢复、Safari/Firefox 和真实触控设备没有验证。鼠标和键盘为本波 Chromium 实测；已有 Pointer Events 单测继续保留，但不等于真实设备已测。窄屏由用户明确取消，本轮不新增或声称覆盖。

W6 建议由项目经理单独放行：先冻结这一波及旧→新测试映射，再接管单一 app shell/history，证明全部路由/刷新/回退与 owner 边界不变；最终依赖移除与干净全站 E2E 门槛仍独立执行。此报告不是 W6 的执行或发布授权。
