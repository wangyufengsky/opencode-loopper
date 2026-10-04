# W6 Workflow / Roles：Vue 退场与原合同迁移

本分区复用原 A（平台既有启动记录显式 `gpt-6.1-sol` / `xhigh`；无实时配置读取接口），基线 `2186deaf`。作者只修改获授权文件，没有新增组员、提交、安装依赖、后端/模型调用或浏览器/共享构建。根 React Router/App 由组长持有。

## 原测试执行面

所有权输入 `/workspace/react-full-w6-evidence/test-ownership-initial.json` 的 A 为 **61 个原 spec、452 个 assertionResults 用例**。这里的 452 是执行用例数，不是源文件 `expect()` 调用次数。保留每个原 spec 路径、原 fullName 与参数化展开数量；没有删除、skip、todo、only、合并或用既有 W5 测试总数替代本批。完整 fullName 与新执行模块逐项清单见 `A-61-contract-mapping.json`；最终执行后逐项 multiset 核对在 `A-final-mapping-verification.json`。

组件测试以 React Testing Library 实际 `render()` + FoundationProvider 挂真正 React 组件/owner。`react-test-root.tsx` 仅提供真实 DOM 查询、受控 `rerender`、事件与 public callback 记录；没有 Vue lifecycle、虚拟组件 stub、Vue template 执行、`vm`、模拟业务状态或假 React 标记。旧 `global.stubs` 元数据已移除。disabled 表单不能通过 `setValue` 强制变更；FileReader/hash 等待真实生产 owner 结束。

页面测试通过组长公开 `mountApplicationHarness` 的真实 ApplicationOwnership、Data Router 和 useBlocker。`application-test-root.tsx` 只组装入口/DOM查询；导航不 stub guard。单组件 command fixture 的父读取刷新与许可是明确可注入的测试边界，真实 API write、回执、CAS、scope 和 recovery 全部保留在生产 owner；这些单组件边界不冒称整页导航已验证。

历史 `vue` / `react` 参数化标题和 Role『Vue 回退』fullName 保留供前后核对；本轮正式批准最终零 Vue，因此二者均挂实际 React Flow，历史偏好输入不复活 Vue。没有保留假 `data-canvas-runtime="vue"`。

## 逐文件数量与合同映射

下表行号定位当前第一条原合同。原行号/全部 fullName/实际 import 已同时记录机器清单；每行旧→新数量保持一致。

| 原 spec（路径/标题保留） | 原→新用例 | 实际新执行入口 | 保留合同 |
| --- | ---: | --- | --- |
| [views/WorkflowEditorView.spec.ts](../../../../../frontend/src/views/WorkflowEditorView.spec.ts)（:24） | 13→13 | `pages/w5/workflow` | 预设绑定、节点/边选择与撤销、内置只读、双段保存回执、unknown禁离开、409草稿复制、布局reveal不写 |
| [views/WorkflowLibraryView.spec.ts](../../../../../frontend/src/views/WorkflowLibraryView.spec.ts)（:18） | 5→5 | `pages/w2/workflow/WorkflowLibraryPage` | 选中披露/外部关闭/回焦、服务端filter/cursor、原key复制恢复、版本删除确认、内置只读 |
| [views/WorkflowRequirementListView.spec.ts](../../../../../frontend/src/views/WorkflowRequirementListView.spec.ts)（:14） | 2→2 | `pages/w2/workflow/RequirementListPage` | 服务端项目筛选/游标分页、创建深链项目参数、错误与空态分离 |
| [views/WorkflowRequirementNewView.spec.ts](../../../../../frontend/src/views/WorkflowRequirementNewView.spec.ts)（:18） | 8→8 | `pages/w5/requirements/NewRequirementPage` | 默认真实流程修订、四字段选择、create同原body/key、pending/unknown禁离开、accepted导航恢复 |
| [views/WorkflowRequirementScope.spec.ts](../../../../../frontend/src/views/WorkflowRequirementScope.spec.ts)（:65） | 7→7 | `pages/w5/requirements/RequirementPage`<br>`pages/w5/requirements/controller` | A→B真实路由owner退休、迟到写/读/错误隔离、布局/新命令保持、unknown/pending/accepted读回守卫 |
| [views/WorkflowRequirementView.spec.ts](../../../../../frontend/src/views/WorkflowRequirementView.spec.ts)（:39） | 23→23 | `pages/w5/requirements/RequirementPage`<br>`pages/w5/requirements/controller`<br>`pages/w5/requirements/nodeController`<br>`pages/w5/requirements/uploadController` | 确认≠执行、检查点、原版本计划/候选/布局、冻结资料/File、节点草稿/unknown、默认模型与晚到隔离 |
| [components/roles/RoleWorkflowDiagram.spec.ts](../../../../../frontend/src/components/roles/RoleWorkflowDiagram.spec.ts)（:12） | 2→2 | `react/diagrams/RoleDiagram` | 真实React角色流程、绑定历史版本查看、中文步骤；历史Vue偏好不复活Vue |
| [components/workflow/WorkflowBranchInput.spec.ts](../../../../../frontend/src/components/workflow/WorkflowBranchInput.spec.ts)（:12） | 3→3 | `pages/w6-tests/workflow/read-panels` | 真实项目分支只读列表/选项、原值/错误重试、disabled |
| [components/workflow/WorkflowCandidates.spec.ts](../../../../../frontend/src/components/workflow/WorkflowCandidates.spec.ts)（:13） | 2→2 | `pages/w6-tests/workflow/command-panels` | 候选元数据→按需正文、用户显式选择/确认、错误与原版本回执保留 |
| [components/workflow/WorkflowCanvas.spec.ts](../../../../../frontend/src/components/workflow/WorkflowCanvas.spec.ts)（:11） | 10→10 | `react/workflow/workflowPointerTestHelpers`<br>`react/workflow/WorkflowCanvasReact` | 真实ReactFlow的键盘移动/连线/删除、只读缩放、blank/Escape而非pan取消、可选边、reveal不保存 |
| [components/workflow/WorkflowCodeChanges.spec.ts](../../../../../frontend/src/components/workflow/WorkflowCodeChanges.spec.ts)（:11） | 3→3 | `pages/w6-tests/workflow/read-panels` | 精确增改删与上下文、原cursor重试、固定文件下载、scope晚到隔离 |
| [components/workflow/WorkflowCommandEditor.spec.ts](../../../../../frontend/src/components/workflow/WorkflowCommandEditor.spec.ts)（:9） | 7→7 | `pages/w5/workflow/NodeSpecialists` | argv/env/workdir/timeout结构化编辑、未知配置显式替换、原参数与disabled |
| [components/workflow/WorkflowCommandEvidence.spec.ts](../../../../../frontend/src/components/workflow/WorkflowCommandEvidence.spec.ts)（:8） | 7→7 | `pages/w5/workflow/reports` | 程序停止/退出/超时证据、原命令/配置、真实只读代码/JSON、专业收尾与HTML安全 |
| [components/workflow/WorkflowCommandReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowCommandReport.spec.ts)（:6） | 6→6 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowDocumentInput.spec.ts](../../../../../frontend/src/components/workflow/WorkflowDocumentInput.spec.ts)（:18） | 7→7 | `pages/w6-tests/workflow/upload-panel`<br>`pages/w5/requirements/controller`<br>`pages/w5/requirements/uploadController`<br>`pages/w6-tests/workflow/command-panels` | 真实File/hash、原版本/修订、unknown同原上传身份、补传、章节offset、未完整不选用、多子owner聚合 |
| [components/workflow/WorkflowDocumentPlanReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowDocumentPlanReport.spec.ts)（:6） | 7→7 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowDocumentPreview.spec.ts](../../../../../frontend/src/components/workflow/WorkflowDocumentPreview.spec.ts)（:11） | 4→4 | `pages/w6-tests/workflow/read-panels`<br>`pages/w5/workflow/reports` | 真实正文/offset/错误、相对链接containment、分段读取、close/导航/晚到隔离 |
| [components/workflow/WorkflowDocumentReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowDocumentReport.spec.ts)（:6） | 12→12 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowDocumentReviewEditor.spec.ts](../../../../../frontend/src/components/workflow/WorkflowDocumentReviewEditor.spec.ts)（:10） | 5→5 | `pages/w5/workflow/NodeSpecialists`<br>`pages/w5/workflow/WorkflowNodeEditor` | 可读文档章节/批次、原parameters、未知配置确认、固定输出/完成策略 |
| [components/workflow/WorkflowDocumentReviewReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowDocumentReviewReport.spec.ts)（:9） | 10→10 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowFiles.spec.ts](../../../../../frontend/src/components/workflow/WorkflowFiles.spec.ts)（:8） | 3→3 | `pages/w6-tests/workflow/read-panels` | 固定文件/path/readonly上下文、分页、下载/ZIP、Markdown章节按需预览 |
| [components/workflow/WorkflowFinish.spec.ts](../../../../../frontend/src/components/workflow/WorkflowFinish.spec.ts)（:19） | 7→7 | `pages/w6-tests/workflow/command-panels` | 明确target/reason/CAS、STOPPING证据、unknown同key/body、accepted只读、普通dirty与节点写gate |
| [components/workflow/WorkflowHistoryAnalysisEditor.spec.ts](../../../../../frontend/src/components/workflow/WorkflowHistoryAnalysisEditor.spec.ts)（:11） | 3→3 | `pages/w5/workflow/WorkflowNodeEditor` | 历史分析专业参数/输出策略、原数据保持、只读锁定 |
| [components/workflow/WorkflowHistoryAnalysisReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowHistoryAnalysisReport.spec.ts)（:11） | 10→10 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowHistoryReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowHistoryReport.spec.ts)（:6） | 10→10 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowHistorySummary.spec.ts](../../../../../frontend/src/components/workflow/WorkflowHistorySummary.spec.ts)（:7） | 8→8 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowInputContent.spec.ts](../../../../../frontend/src/components/workflow/WorkflowInputContent.spec.ts)（:15） | 9→9 | `pages/w6-tests/workflow/read-panels` | 固定输入来源/版本、不同专业结构化正文、按需文件/引用、HTML安全、scope与错误重试 |
| [components/workflow/WorkflowKnowledgeEvidence.spec.ts](../../../../../frontend/src/components/workflow/WorkflowKnowledgeEvidence.spec.ts)（:12） | 4→4 | `pages/w6-tests/workflow/read-panels` | 元数据和正文按需、同cursor/选择重试、晚到隔离、三份有界缓存 |
| [components/workflow/WorkflowKnowledgeReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowKnowledgeReport.spec.ts)（:11） | 3→3 | `pages/w5/workflow/reports`<br>`pages/w6-tests/workflow/read-panels` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowNativeTestReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowNativeTestReport.spec.ts)（:12） | 18→18 | `pages/w5/workflow/reports`<br>`pages/w5/workflow/WorkflowNodeEditor` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowNodeRun.spec.ts](../../../../../frontend/src/components/workflow/WorkflowNodeRun.spec.ts)（:17） | 14→14 | `pages/w6-tests/workflow/command-panels` | 冻结attempt/definition/input/result、人工结果/CAS、停止确认、恢复同身份、轮询/迟到隔离与正文按需 |
| [components/workflow/WorkflowPresetPicker.spec.ts](../../../../../frontend/src/components/workflow/WorkflowPresetPicker.spec.ts)（:17） | 5→5 | `pages/w5/workflow/WorkflowPresetPicker` | 实际preset目录/详情、REQUIREMENT/NODE类型绑定、父依赖、重复/不兼容、只读 |
| [components/workflow/WorkflowPublicInputs.spec.ts](../../../../../frontend/src/components/workflow/WorkflowPublicInputs.spec.ts)（:7） | 2→2 | `pages/w5/workflow/WorkflowPublicInputs` | 公共字段声明/rename同时修消费者、删除/类型安全与readonly、原图不变 |
| [components/workflow/WorkflowPublication.spec.ts](../../../../../frontend/src/components/workflow/WorkflowPublication.spec.ts)（:17） | 7→7 | `pages/w6-tests/workflow/publication-fixture` | 来源/分页/失败成果、原预览Abort与scope校验、换修订只读、新写/unknown禁换上下文 |
| [components/workflow/WorkflowPublicationCommit.spec.ts](../../../../../frontend/src/components/workflow/WorkflowPublicationCommit.spec.ts)（:14） | 5→5 | `pages/w6-tests/workflow/publication-fixture` | 准确preview/version/key/message、unknown恢复、accepted只GET、失败读取不能新POST |
| [components/workflow/WorkflowPush.spec.ts](../../../../../frontend/src/components/workflow/WorkflowPush.spec.ts)（:15） | 6→6 | `pages/w6-tests/workflow/publication-fixture` | 远端/预览hash/版本、原key/body冻结、服务端push证据、unknown只原身份恢复 |
| [components/workflow/WorkflowRepositoryReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowRepositoryReport.spec.ts)（:6） | 9→9 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowReviewReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowReviewReport.spec.ts)（:7） | 6→6 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowReviewSourceEditor.spec.ts](../../../../../frontend/src/components/workflow/WorkflowReviewSourceEditor.spec.ts)（:9） | 2→2 | `pages/w5/workflow/NodeSpecialists` | 源码目标及范围结构化选择、原字段与readonly锁定 |
| [components/workflow/WorkflowReviewSourceReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowReviewSourceReport.spec.ts)（:8） | 12→12 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowRolePicker.spec.ts](../../../../../frontend/src/components/workflow/WorkflowRolePicker.spec.ts)（:15） | 3→3 | `pages/w5/workflow/WorkflowRolePicker` | 目录/修订/label、保原绑定/缺失/只读、晚到选择隔离 |
| [components/workflow/WorkflowSaveTemplate.spec.ts](../../../../../frontend/src/components/workflow/WorkflowSaveTemplate.spec.ts)（:18） | 6→6 | `pages/w6-tests/workflow/command-panels` | CURRENT/INITIAL准确preview、hash/修订固定、显式create、unknown保原体、确定拒绝后显式再preview |
| [components/workflow/WorkflowSnapshotEditor.spec.ts](../../../../../frontend/src/components/workflow/WorkflowSnapshotEditor.spec.ts)（:11） | 4→4 | `pages/w5/workflow/WorkflowNodeEditor` | 目标/上下文/边界结构化参数、原输入、专业固定输出、readonly |
| [components/workflow/WorkflowSnapshotPartialReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowSnapshotPartialReport.spec.ts)（:10） | 5→5 | `pages/w6-tests/workflow/read-panels` | 实际已接受阶段报告、明确局限/状态/统计、按需正文与下载、scope隔离 |
| [components/workflow/WorkflowSnapshotReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowSnapshotReport.spec.ts)（:6） | 12→12 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowSnapshotSummary.spec.ts](../../../../../frontend/src/components/workflow/WorkflowSnapshotSummary.spec.ts)（:7） | 11→11 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowSourceDesignEditor.spec.ts](../../../../../frontend/src/components/workflow/WorkflowSourceDesignEditor.spec.ts)（:10） | 7→7 | `pages/w5/workflow/NodeSpecialists` | 逐路径/专业任务参数、未知配置确认、disabled与原节点不变 |
| [components/workflow/WorkflowSourceDesignReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowSourceDesignReport.spec.ts)（:9） | 9→9 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowSourcePlanReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowSourcePlanReport.spec.ts)（:6） | 9→9 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowSourceReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowSourceReport.spec.ts)（:6） | 9→9 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowTestDesignReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowTestDesignReport.spec.ts)（:9） | 11→11 | `pages/w5/workflow/reports`<br>`pages/w5/workflow/WorkflowNodeEditor` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowTestProfileReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowTestProfileReport.spec.ts)（:9） | 10→10 | `pages/w5/workflow/reports`<br>`pages/w5/workflow/WorkflowNodeEditor` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowTestReviewReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowTestReviewReport.spec.ts)（:9） | 16→16 | `pages/w5/workflow/reports`<br>`pages/w5/workflow/WorkflowNodeEditor` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowTestScopeReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowTestScopeReport.spec.ts)（:8） | 9→9 | `pages/w5/workflow/reports`<br>`pages/w5/workflow/WorkflowNodeEditor` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowTestSummaryReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowTestSummaryReport.spec.ts)（:15） | 12→12 | `pages/w5/workflow/reports`<br>`pages/w5/workflow/WorkflowNodeEditor` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowVerificationEditor.spec.ts](../../../../../frontend/src/components/workflow/WorkflowVerificationEditor.spec.ts)（:9） | 6→6 | `pages/w5/workflow/NodeSpecialists` | 验证器/运行参数/边界/readonly、未知配置显式替换、保留原数据 |
| [components/workflow/WorkflowVerificationReport.spec.ts](../../../../../frontend/src/components/workflow/WorkflowVerificationReport.spec.ts)（:6） | 4→4 | `pages/w5/workflow/reports` | 专业DTO全部字段/校验边界、失败/局限中文投影、正文/引用真实React、安全转义、固定结果不冒业务成功 |
| [components/workflow/WorkflowWriteback.spec.ts](../../../../../frontend/src/components/workflow/WorkflowWriteback.spec.ts)（:16） | 6→6 | `pages/w6-tests/workflow/publication-fixture` | DIRECT预览/version/CAS、冲突/目标/完成资格、确认锁定、原key/accepted只读恢复 |
| [components/workflow/WorkflowWritebackPreview.spec.ts](../../../../../frontend/src/components/workflow/WorkflowWritebackPreview.spec.ts)（:13） | 5→5 | `pages/w6-tests/workflow/publication-fixture` | 显式读取目标预览、源hash/版本/identity校验、真实代码改动/冲突与路径 |
| [components/workflow/command.spec.ts](../../../../../frontend/src/components/workflow/command.spec.ts)（:12） | 7→7 | `pages/w4/shared/core` | W1/W4纯TS原回执owner：unknown原命令、accepted只读、明确拒绝、scope退休/迟到/新命令隔离 |
| [components/workflow/modelChoice.spec.ts](../../../../../frontend/src/components/workflow/modelChoice.spec.ts)（:16） | 5→5 | `pages/w5/requirements/controller` | 真实Requirement controller：一次默认读、持久/人工选择优先、晚到/retire隔离、显式错误重试 |

## 实际缺口与夹具修正分层

以下是迁移到真实 React 后原业务合同触发的最小补齐。失败日志原样保留，不能把缺按钮、旧选择器、未提供 DTO、FileReader 未就绪等所有候选失败一概称产品缺陷。

| 确切缺口 | 修复范围与合同 | 原 RED 证据 / 最终原合同 |
| --- | --- | --- |
| Knowledge 失败重试错入口/游标 | `w5/requirements/Content.tsx:46` 保存实际读动作；列表失败沿原 cursor、正文沿原 selected evidence id；只读 | `A-read-first-react.json` 两项真实失败；`WorkflowKnowledgeEvidence.spec.ts:13` 四原用例 |
| 确定409后模板预览丢失，没有显式重新预览入口 | `SaveTemplate.tsx:29` 仅原 command SETTLED 且未接受/未锁定显示 `ui.retry`，调用已有 owner.read，不重复 create | `A-commands-second.json` 第四原用例；`WorkflowSaveTemplate.spec.ts:37` |
| 真实成果预览快切未 abort 原读取 / loading 误禁第二选择 | `Publication.tsx` 许可不因纯 preview loading 锁 selection；`publicationController.ts:42` 同 channel instance Abort/cleanup，pending WRITE/unknown/dirty 仍禁止 | `A-source-selection-first.json` 第三原用例；`WorkflowPublication.spec.ts:24` signal.aborted 与晚到原选择 |
| 选中流程后外部 pointerdown 未取消披露 | `w2/workflow/WorkflowLibraryPage.tsx:17` 仅选中时实例 document listener，createResourceScope 精确释放；关闭仅披露，critical command与guard不变 | `A-all-views-second.json` library 第一原用例；`WorkflowLibraryView.spec.ts:18`；新增严格资源二项单列 |
| Requirement dirty/default-model error隐藏、部分禁用执行入口缺失、status refresh错误耦合plan reload | `RequirementPage.tsx` 主状态持续 dirty/modelError；生命周期 eligible 入口 discoverable 但按既有 executable/locked 禁用；既有 owner.refresh GET 与 plan load 分开 | `A-all-views-second.json` 对应 dirty/checkpoint/编辑等原失败；`WorkflowRequirementView.spec.ts` 原23用例 |
| Requirement 先baseGET后executionGET时 PublicValues 依赖null；同节点选择打开不必要dirty确认 | `RequirementPage.tsx` execution未就绪不挂PublicValues；same selected id直接返回；没有清 child draft/File/command | `A-all-views-second.json` 实际 null.execution 崩溃及 repeated-selection 失败；原上传/unsent node 用例 |
| 候选源 cleanup 尚未成功时 apply 按钮与 owner 保护不一致；自动布局缺真实 UI 入口 | `RequirementPage.tsx:31,60,94` sourceReady只投影原 sourceCompleted/精确attempt终态；autoLayout→已有setLayout，所有locked/history/pending/unknown许可与撤销不变 | `A-requirement-fifth.json` source cleanup 原用例；原 autoLayout/undo 用例始终点实际按钮 |

独立分支输入仅导出生产已有 `BranchInput`（`Inputs.tsx`），没有新增写 owner。没有修改 graph/plan/save、W1通用回执、API、共享画布或新业务端点。

夹具纠正包括：旧 CodeMergeEditor/Vue stub 属性改读真实 `.w3-code-text`；真实 Ant6 Select combobox 选择而非旧 native select；Portal retained hidden dialog 不作 active dialog；`fireEvent.click` 不自带浏览器 focus，先聚焦实际触发器；默认模型 AvailableModel/CursorPage 与真实 DTO；pending FileReader hash 等真实就绪；先等待真实 Ant 按钮 loading 状态退出后单次点 recovery，不强制 emit/不多点重试。Scope 无效 `.w5-command-notice` 选择器改为当前 owner.notice/error 空值；新增真实 Finish 子读提供准确 finishStatus fixture，Root自己的阻离开提示可保留，不把所有全局告警误当迟到A污染。

## 与最终零 Vue 架构一致的迁移断言

- Scope 原『同一个 Vue uid』是框架实现观察；现验证公开 ApplicationOwnership old scope 立即 inactive/new scope 不同，再验证原 A/B DOM、原请求次数/body同一引用、布局和 pending 锁定。`WorkflowRequirementScope.spec.ts:57` 的强制 host replacement 通过公开 scope/commit + 真 router.navigate；普通导航仍走真实 leave coordinator/useBlocker。没有私改 Router currentRoute 或绕过普通 guard。
- 原 UNKNOWN 上传末段『改为新上传』会丢原 File/body，违反已批准 W0/W1 恢复合同。保原fullName但加强：找不到 discard/reset、新写0、原File引用/原key/body保持、真实路由不能离开；只有用户显式同原上传成功并由实际GET证明完整才释放。`WorkflowRequirementView.spec.ts` 对应最后 File 用例；`WorkflowDocumentInput.spec.ts:58` 两个child独立未知状态聚合。
- Canvas 直接挂 `WorkflowCanvasView` / `RoleDiagram`，键盘/指针输入是真 React Flow，边出现等待其真实测量；reveal/focus通过 public handle，reveal不保存。既有105px/zoom/lifecycle严格专项不重写、不拿本批十项重复声称新增浏览器证明。
- readonly专业报告、安全Markdown/只读代码是真正文，不用通用 JSON 输入删专业能力。旧 JSON/Object/error/限额/HTML安全边界原用例全部保留。

## 删除与字节保护

实际删除 **67个SFC + 2个不再执行的Vue composable TS（69文件）**。完整归档 `/workspace/react-full-w6-evidence/A-old-source/`，亦可 `git show 2186deaf:<path>` 读取原字节。精确删除清单在 `A-removed-files.json`，下方逐项列出。本次不删原spec与纯domain/CSS。

`A-preserved-domain-verification.json` 区分15原文件：13个存续 TS/CSS **完整sha256逐字节一致**，两个 command.ts/modelChoice.ts 因Vuecomposable退场明确删除，不能把其删除当hash保持。graph.ts/save.ts/planSave.ts/planEditing.ts/presets.ts/repository.ts/reviewSource.ts/values.ts/knowledgeBundle.ts/测试fixtures/canonical workflow-canvas.css保持原字节。共享引用清零/全仓无Vue/dependency/build与浏览器门禁由组长统一，不能由本目录scan冒称全仓已清零。

```text
frontend/src/components/roles/RolePromptView.vue
frontend/src/components/roles/RoleWorkflowDiagram.vue
frontend/src/components/roles/RoleWorkflowDiagramLegacy.vue
frontend/src/components/workflow/WorkflowAddMenu.vue
frontend/src/components/workflow/WorkflowBranchInput.vue
frontend/src/components/workflow/WorkflowCandidates.vue
frontend/src/components/workflow/WorkflowCanvas.vue
frontend/src/components/workflow/WorkflowCanvasLegacy.vue
frontend/src/components/workflow/WorkflowChoice.vue
frontend/src/components/workflow/WorkflowCodeChanges.vue
frontend/src/components/workflow/WorkflowCommandEditor.vue
frontend/src/components/workflow/WorkflowCommandEvidence.vue
frontend/src/components/workflow/WorkflowCommandReport.vue
frontend/src/components/workflow/WorkflowContextPanel.vue
frontend/src/components/workflow/WorkflowDocumentInput.vue
frontend/src/components/workflow/WorkflowDocumentPlanReport.vue
frontend/src/components/workflow/WorkflowDocumentPreview.vue
frontend/src/components/workflow/WorkflowDocumentReport.vue
frontend/src/components/workflow/WorkflowDocumentReviewEditor.vue
frontend/src/components/workflow/WorkflowDocumentReviewReport.vue
frontend/src/components/workflow/WorkflowFiles.vue
frontend/src/components/workflow/WorkflowFinish.vue
frontend/src/components/workflow/WorkflowHistoryAnalysisReport.vue
frontend/src/components/workflow/WorkflowHistoryReport.vue
frontend/src/components/workflow/WorkflowHistorySummary.vue
frontend/src/components/workflow/WorkflowInputContent.vue
frontend/src/components/workflow/WorkflowKnowledgeEvidence.vue
frontend/src/components/workflow/WorkflowKnowledgeReport.vue
frontend/src/components/workflow/WorkflowModelChoice.vue
frontend/src/components/workflow/WorkflowNativeTestReport.vue
frontend/src/components/workflow/WorkflowNodeEditor.vue
frontend/src/components/workflow/WorkflowNodeList.vue
frontend/src/components/workflow/WorkflowNodeRun.vue
frontend/src/components/workflow/WorkflowPlanDiff.vue
frontend/src/components/workflow/WorkflowPresetPicker.vue
frontend/src/components/workflow/WorkflowPublicInputs.vue
frontend/src/components/workflow/WorkflowPublication.vue
frontend/src/components/workflow/WorkflowPublicationCommit.vue
frontend/src/components/workflow/WorkflowPush.vue
frontend/src/components/workflow/WorkflowRepositoryReport.vue
frontend/src/components/workflow/WorkflowReviewReport.vue
frontend/src/components/workflow/WorkflowReviewSourceEditor.vue
frontend/src/components/workflow/WorkflowReviewSourceReport.vue
frontend/src/components/workflow/WorkflowRolePicker.vue
frontend/src/components/workflow/WorkflowSaveTemplate.vue
frontend/src/components/workflow/WorkflowSnapshotPartialReport.vue
frontend/src/components/workflow/WorkflowSnapshotReport.vue
frontend/src/components/workflow/WorkflowSnapshotSummary.vue
frontend/src/components/workflow/WorkflowSourceDesignEditor.vue
frontend/src/components/workflow/WorkflowSourceDesignReport.vue
frontend/src/components/workflow/WorkflowSourcePlanReport.vue
frontend/src/components/workflow/WorkflowSourceReport.vue
frontend/src/components/workflow/WorkflowTestDesignReport.vue
frontend/src/components/workflow/WorkflowTestProfileReport.vue
frontend/src/components/workflow/WorkflowTestReviewReport.vue
frontend/src/components/workflow/WorkflowTestScopeReport.vue
frontend/src/components/workflow/WorkflowTestSummaryReport.vue
frontend/src/components/workflow/WorkflowValueFields.vue
frontend/src/components/workflow/WorkflowVerificationEditor.vue
frontend/src/components/workflow/WorkflowVerificationReport.vue
frontend/src/components/workflow/WorkflowWriteback.vue
frontend/src/components/workflow/WorkflowWritebackPreview.vue
frontend/src/components/workflow/command.ts
frontend/src/components/workflow/modelChoice.ts
frontend/src/views/WorkflowEditorView.vue
frontend/src/views/WorkflowLibraryView.vue
frontend/src/views/WorkflowRequirementListView.vue
frontend/src/views/WorkflowRequirementNewView.vue
frontend/src/views/WorkflowRequirementView.vue
```

## 作者执行与最终冻结

最终命令由 `A-final-452-v3-command.json` 逐参数保存：在 `frontend/` 执行 `./node_modules/.bin/vitest run <所有权A的61个原路径> --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w6-evidence/A-final-452-v3.json`。**61 个文件、452/452 PASS、0 FAIL / skip、exit0**；最终 JSON/log/exit 文件均归档。JSON 的 `numTotalTestSuites` 包含 describe 分组，不把它误称文件数。

`A-final-mapping-verification.json` 精确比较原所有权 fullName multiset：61→61、452→452、nameDifferences=[]。运行前后 75 个作者源码/测试文件 SHA 完全一致；`A-final-452-v3-source-verification.json` changed=[]。之前的 452/452 v2 只是上一字节候选；四个旧 spec 尾空格经纯格式清理后最终 v3 再实际全跑，未拿上一运行冒充最终源码。

新增两个严格用例单列，未混入原452：`library-resources.spec.tsx`（StrictMode 三轮 selected pointer callback 精确卸载，以及 unknown copy outside dismissal 保原身份/阻离开/零新写），`A-library-resources-final.{json,log}` **2/2 PASS、exit0**。退场首快照先断 listener identity，随后才发送迟到输入；没有用自然事件释放替代卸载证明，也没有忽略别的实例或删除他人 listener。

隔离 `tsc -p /tmp/A-w6-tsconfig.json --noEmit --incremental false` 最终 **exit0**，`A-type-final-v3.log`；包括原61、真实React helpers、新资源测试与独立Root probes，无共享 tsbuildinfo/build 产物。授权路径 `git diff --check` exit0。当前所有权范围 Vue/Pinia/.vue imports、旧模板 stub metadata、测试 skip/todo/only 扫描为0；67 SFC＋2 obsolete Vue composables 均已移除。`A-preserved-domain-verification-final.json` 13 份必须保留的 TS/CSS 完整 SHA 原样相同。

源码冻结清单 `A-final-freeze-hashes.json`：75 作者文件＋2 非作者探针文件共77项，SHA `c55781752a00108f714387ba99513f7b619aa95e6036f18e704060f664c04fda`；文档 SHA 另在交接清单记录，避免自引用哈希。原归属/删除/映射输入和所有失败候选均保留在 `/workspace/react-full-w6-evidence/`。未提交、未启动服务、未运行共享构建或浏览器；全仓门禁和最终 production browser 由组长执行。

## 非作者 Root 交叉审查

独立只读审查 `src/app/*`、`router/index.tsx`、`themes/state.ts`、`migration/w2TaskPort.ts`，并实际复跑组长既有 ownership / bridge / port / W0。没有编辑 Root 源，没有把自己的452作者用例当交叉验收。

### 三个真实 RED → 原样 GREEN

| 分类与原来源 | 原首次观察 / 红断言 | Root 最小修复 | 最终独立证据 |
| --- | --- | --- | --- |
| P2，`app/storyAccounting.ts` 原dispose；`root-cleanup-independent.probe.spec.tsx:13` | 注入 stream.close 抛 Error 后首同步快照 callback 未remove、timer2仍留；异常确实可见 | 当前dispose逐资源尝试，先使alive/generation失效；监听、timeout、interval、subscribers均释放，首异常最后throw | 同一原probe：firstAfterTimers0、精确callback removed=true、close1、异常可见 |
| P2，`app/bootstrap.tsx` 原unmount；同probe `:30` | forced application.dispose 已 inactive 且返回false，真实React内容仍在DOM；没有任何后续事件或timer推进 | 当前bootstrap:20仅active=true表示保护拒绝；inactive即实际root.unmount，healthy=false不冒充成功；ownership.dispose也继续其他cleanup | 同一原probe：applicationRetired=true、realRootTreePresent=false、returnedHealthy=false、失败仍可见 |
| P2，`app/storyAccounting.ts:58` 迟到selection catch；`root-story-read-independent.probe.spec.ts:13` | A detail GET pending→真实select(B)→reject(A)：Bselected与command IDLE保持、mutation0，但B.error被旧A写成“统计状态暂时无法刷新” | 当前catch/list retry只接受原generation；detail成功先核原id，再投影 | 同一原probe：Bselected、同command、mutation0、read仅A，首 settled response error=''；未先推进timer或等B自然修正 |

两个原close/root probe文本 SHA `63f89ed35ac0957dc376130f7b03432f8a6f23623d30d527e20ef35d0bfe9d62`，外部原样文本 `A-root-cleanup-independent.probe.spec.tsx.txt`；迟到read原文本 SHA `1eb8e9f2a33805ed3deb7dffefa9b094f07b72f9ed0d8dda9a9ce0c3b6b2a57f`，外部 `A-root-story-read-independent.probe.spec.ts.txt`。修前 raw `A-root-two-independent-before.*` 为2FAIL，`A-root-story-read-independent-before.*` 为1FAIL；observation-before 和 source-before 均归档。中间2/2与1/1 after不冒称最终Root字节：最后同两文件原样一起跑 `A-root-three-independent-final.{json,log}` **3/3 PASS、exit0**，六个相关源码/probe运行前后SHA无变化。没有修改原断言，没有把安全拒绝pending/unknown卸载错认成cleanup bug。

### 既有 Root 测试独立执行

最终实际命令：`./node_modules/.bin/vitest run src/app/ownership.spec.ts src/migration/w2Bridge.spec.tsx src/migration/w2TaskPort.spec.ts src/w0 --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w6-evidence/A-root-related-independent-final.json`，**156/156 PASS、0 FAIL / skip、exit0**。

精确分层：ownership14、实际React Router桥26、pure task port5、原W0 111（Designer21＋endpoint15＋templates/history43＋workflow32）。不是预估的bridge28，也没有把156全称W0。`A-root-related-independent-final-source-verification.json` 22份审查源码/测试运行前后SHA均匹配。

- `app/ownership.spec.ts:16–28`：query数组/hash/原param、pending/unknown/File/dirty safe disposal、无页面globalBLOCK与guard异常、确认后新pending、并发目的地epoch、所有owner异常仍继续释放、31真实route records和alias query/hash、global accounting未知原id/GET证明、keyless retry不能盲写、partial dismiss逐已接受记录。
- `migration/w2Bridge.spec.tsx:55–210`：真正React根 public生命周期、Strict effect重放而owner不退休、三轮/重复route释放、五类W5 exactscope、同path query变更真实确认与旧owner先退休、pending/unknown不得离开、更晚BLOCK禁confirm、authentic accepted精确destination、beforeunload callback身份、迟到import不挂载、失败cleanup禁止替换。历史describe含“Vue history”的标题保留，实际入口已是 React Data Router，没有Vue运行层。
- `migration/w2TaskPort.ts:19–58` / 原spec五条：唯一纯TS应用owner subscription投影、immutable快照、read epoch/retired失败隔离、实际summary读取归属才invalidate；不另起task SSE，不借旧Pinia。
- `router/index.tsx:18–35`：production只createBrowserRouter一次（测试显式initialEntries才memory），公开router状态取Fetch Request遗漏的fragment，query/hash alias已实际断言；router订阅与dispose在application.cleanup。
- `themes/state.ts:15–25`：每bootstrap实例精确storage callback，optional localStorage失败不改业务owner；React theme listeners经useSyncExternalStore释放，换肤不key重挂业务页。

### HMR 与剩余验证边界

发现 `main.tsx` 将unmount返回false保存的两种原因混合。Root采用安全策略：pending/unknown时active仍true，保留原React树/owner；cleanup failure退休但unhealthy时不启动第二套subscription/command owner，当前 `main.tsx:7–14` 显式失败alert并保留终止结果，等待用户刷新核对。此项是**源码确定策略与只读delta审查**，未跑真实HMR浏览器，不能称HMR已实测通过。

本批独立执行使用真实DataRouter memory history＋guard/UI，不把它推广成真实浏览器nativePOP、页面reload/关闭或HMR证明；真实BrowserRouter source已审，nativePOP与最终route/resource/三skin截图由组长生产浏览器门禁确认。本次没有自行跑浏览器、heap/GC或推论跨刷新恢复。最终独立探针3与既有156覆盖内无未修复阻塞；全仓最终门禁仍由组长持有。
