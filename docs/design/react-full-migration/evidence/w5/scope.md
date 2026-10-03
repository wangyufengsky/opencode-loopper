# W5 冻结范围与责任

基线：`5c181fc39e26249650df851fe9a7c8a68cab519d`。仅本地 W5，尚未批准 W6、history 接管或去 Vue。

| 原生产路由 | 原入口 | 新 React 页面 | 唯一业务所有者 | 组员写入范围 |
|---|---|---|---|---|
| `/workflows/new`、`/workflows/:id` | WorkflowEditorView | WorkflowEditorPage | 纯 TS 流程 editor；分开 graph/layout 原身份及回执 | A：pages/w5/workflow |
| `/requirements/new` | WorkflowRequirementNewView | NewRequirementPage | 纯 TS 创建 owner；四字段、原 key/body、精确 accepted handoff | B：pages/w5/requirements |
| `/requirements/:id` | WorkflowRequirementView | RequirementPage | 纯 TS 需求 scope；子命令由 retained child owner 持有，父合并导航保护 | B：pages/w5/requirements |
| `/designer?sessionId=…` | DesignerView | DesignerPage | 纯 TS 会话、消息、File、版本、SSE/轮询 scope | C：pages/w5/designer |

Vue Router 仍唯一持有 history。无 session 的 Designer 原 redirect 保持；旧初始创作 API 合同独立验证，不新增可达生产页面。React 页面不通过旧 Vue 子组件/Pinia 代理承担核心业务。已验收 React Flow、实例手势、PPT 和基础组件保持。

组长独占共享路由 bridge、语义表生成、原 W0 委托入口、浏览器生产夹具、最终整合/报告/提交。所有组员只改自己模块；非作者交叉审查 A→Designer、B→Workflow、C→Requirements 与共享入口。身份来自原三名开发员真实 followup 回执，不增员。

## 原红与原正控

实际两文件基线：53 条定义、12 通过、41 失败、0 跳过，运行退出码 1。完整前一波 W0 111=70 通过+41 失败继续留存，不能把此次 53 当完整 111。

| 合同 | 原红数 | 实现/验证责任 |
|---|---:|---|
| B1.1 pending/unknown 创建，push/replace/back | 6 | B，真实 React 页面与唯一 Router |
| B1.3 accepted 创建但导航失败 | 2 | B；三条定义均保留，cancelled 原正控不删 |
| B2.1 human/candidate/finish/commit/push/writeback sending/unknown 与父保护 | 13 | B，实际 child owner/面板与父路由 |
| B2.2 原 attempt/version/key 停止恢复 | 1 | B |
| B2.3 accepted Finish 只读回执恢复 | 1 | B |
| B5.1 Designer dirty/File 取消离开 | 4 | C |
| B5.2 Designer sending/unknown File 所有者 | 4 | C |
| B5.3 accepted Task 精确交接恢复 | 2 | C |
| B6.1 旧 question 迟到返回 | 1 | C |
| B6.2 profile preview/确认期间退休 | 2 | C |
| B6.3 原 multipart/后来可达草稿 | 2 | C |
| B7.1 owned terminal retry timeout 立即退休 | 1 | C |
| B7.2 owned composer RAF 立即退休 | 1 | C |
| B7.3 accepted File 在可选存储失败时保持身份 | 1 | C |

逐条固定 fullName/文件/原状态见 [w0-mapping.json](w0-mapping.json)，冻结旧源完整 SHA256/行数见 [baseline-inventory.json](baseline-inventory.json)。原 12 正控包括 W2 library 的 5 条已迁合同、创建四字段真实 disabled、纯 owner 冻结、Designer mandatory 无 reject 等；不能通过强改 disabled DOM 或触发不可达动作制造证据。

## 验收计划

保留全部 41 原失败断言的语义、身份/引用/次数与严格即时清理，不删、skip 或放宽。真实生产路由直达、刷新、back/forward、换肤、keyboard/focus、独立模型面板、partial receipts、Files/后续草稿、unknown 显式恢复和 accepted 只读恢复。相关 unit、全量 unit、typecheck、build/tooling/accounting、W1–W4 受影响浏览器回归；三皮肤桌面截图来自实际生产入口+模拟 DTO。窄屏为用户取消的范围，不运行/声称通过。

资源证据继续检查同一文档实际 root/route 退出第一样本、实例 listeners/RAF/targets/pointer capture、迟到请求与重复挂载；不模拟全局释放输入，不移除他人监听，不以 object 断开观察关系宣称所有 GC 已完成。

真实后端、模型、文件解析、Git 外部写入、跨刷新服务端未提供的持久恢复及其他浏览器/真实触控设备不得借模拟运输测试称已验证。最终冻结结果见 [验证](verification.json)：本波 49/49 生产 Chromium、428/428 相关 unit、2332/2332 全量 unit；41 原红与全部111 W0 转绿。原断言出现次数删除0。W6/全站E2E/真实后端边界仍独立保留。
