# W6 B：普通页面、Task 应用 owner 与旧测试退出

本轮为原 B 开发员，历史任务配置显式记录 `gpt-6.1-sol / xhigh`；没有实时平台配置读取能力。范围依据外部 `test-ownership-initial.json` 的 B 组：42 个旧 spec 文件、229 个测试定义，不是 229 条 `expect`。保留原 spec 路径、完整名称与定义数；仅将框架夹具接到已存在的 React 生产页面/纯 TS owner。原 Vue 技术回退标题为历史索引，实际断言唯一 React、相同 DTO、节点几何和草稿保持，不造 Vue 标记。

此报告不把单元测试视为浏览器像素、即时原生监听/RO、GC 或全站退出证据。全量类型/构建、真实浏览器和最终依赖门禁由 root 集中执行。本组不安装依赖、启动服务、运行全量门禁或提交。

## 单一 Task 应用 owner

`frontend/src/stores/taskStore.ts` 现在导出 `createTaskApplicationOwner()` / `TaskApplicationOwner`，没有 Vue、Pinia、React 导入。构造时不读取 API、不启动 SSE/轮询、不写业务。App 创建并持有一份，root 的 `w2TaskPort` 只订阅 `getSnapshot()/subscribe()`；W4 Task 详情仍用自身已迁唯一 command owner，不调用应用兼容 writer。

公开 plain 属性为 `projects/tasks/taskItems/runtime/artifacts/loading/auditLoading/auditErrors/taskNextCursor/taskFacets/error/usingDemo/taskNotices/streamState`，setter 发布不可变 DTO 快照。`selectedTask(id)` 是 getter；兼容旧显式方法 `loadOverview/loadProjects/loadTaskSummaries/invalidateTaskSummaries/loadTaskOverview/loadTaskAudit/loadTask/updateTask/retryJudges/retryWaitingLoop/resolveDirtyWorkspace/cancelDirtyWorkspace/reworkTask/setTaskArchived/deleteArchivedTask/watchTask/stopWatching/refreshRuntime/startRuntime/restartRuntime/activateDemo/deactivateDemo`。最后 `dispose()` 先失效全部读取 epoch、清订阅与自有 stream/timers；迟到读取不能发布或开始退休后的后续请求。

项目、Runtime、overview、audit、summary 用独立序号；demo 切换和应用退出再推进总 epoch。overview 的版本不回退；审计只供应历史 attempt/artifact，不覆盖当前 overview 所有的错误/Judge。未从时间器制造 demo 进度。

## 实际组件补齐及原 RED

所有新增 UI 都接生产消费者，而非仅测试导出。

- `RichDocument.tsx`：Markdown 思考过程支持 active/completed `aria-busy`、本地折叠和正文安全渲染。`B-documents-before` 的 8 红包含 4 条真实思考缺口及 4 条夹具错误，不能统称 8 个业务缺陷。
- `JudgeReviewCard.tsx`：TaskDetail 实际卡片 TIMED_OUT 结束描述，不再称等待。`B-judge-before` 3 项中的 1 条真实 RED。
- `ExecutionAcceptancePanel.tsx`：Designer after-stages 实际只读消费者；执行规范 PROCESS/文件检查/弱 GIT_DIFF、阶段与双评审证据分开，解析失败不暴露长 raw。
- `TokenUsageWindow.tsx`：Task 与 Designer 共用纯展示，无 timer/writer。Task Session owner 保持首次累计静默、单调增量、850 ms 窗口和切换基线清空。
- `SessionLifecyclePanel.tsx/sessionLifecycleController.ts`：接 TaskDetail 选中会话。todos/checkpoints 只读，六项既有动作复用真实 API；DIRECT 不回退，Fork/回退需 PAUSED 和原消息引用。keyless UNKNOWN 仅原对象合法读取，不能自动 POST、换身份或丢草稿。关闭详情不停止服务端。
- `TaskEvidencePanels.tsx`：当前双评审 compact 摘要使用最新持久化 Judge；GIT_DIFF 文件数只从已持久 `evidence.changedPaths` 字符串数组计数，不新增 filesystem/API。两项原 RED 分别见 `B-layered-before` 和 `B-remaining-middle`。
- `SessionMonitorPanel.tsx/task.css`：`B-session-publication-before` 三条真实 RED 后，恢复等待会话更新末尾/ARIA、原 part 时间和真实 1.2/3 秒检查频率投影，Todo HIGH/MEDIUM/LOW 中文，以及桌面输出 500/680 px；不新增 timer、API 或 receipt 行为。
- `TaskDetailPage.tsx`：`B-detail-third` 23/25，通过实际 App/ReactRouter 的原确认退休/子任务导航路径；两条模板 reportCount RED 从同 DTO 恢复。模板终态不凭文字制造双评审通过：需要当前双评审 PASS；dualReviewRequired=false 的报告终态沿原程序校验保存合同。TemplateProgress 对应 C 原 7 项中的 4 个 readonly 投影缺口恢复类别说明、remaining ARIA、返修轮次和未知总数说明；未知不改成零。
- `SnapshotBatchesPanel`：C 的实际 `C-recovery-panels-before` RED，展开状态中 owner A→B 后读取 B，原 owner 的 viewlease/ticket 隔离旧响应。effect 唯一触发读取，点击不再重复 GET。TaskDetail 给真实恢复面板传 taskStatus。

## 夹具方法和合同差异

`pages/w6-tests/ordinary` 仅提供 RTL/FoundationProvider/原生事件、transport spies、真实 App/Router 导航和可控 DTO。没有 Vue stub、虚构组件、第二 writer 或测试专属业务实现。UI 关闭与 confirmation 使用真实局部 Modal。详情原路由离开/返回用 `mountApplicationHarness` 和 `navigationHarness` 的单一 ReactRouter，不是仅改 props。

Automations 四个历史完整名称保留，但既定 W0 的当前合法消费者是 W3 显式 history drawer：默认零历史 GET，MANUAL/AUTO_START/REVIEW_REQUIRED 等历史模式与数据只读；旧 create/start/rule/import/preview-confirm 写入已退役，不复活。下载用原合法导出字节和敏感字段拒绝。旧 title 不宣称旧写仍可执行。

Publication ambient entry/focus 只 GET；旧自动 reconcile POST 已由冻结的 UNKNOWN 安全合同替代。普通明确预写失败的 fallback 使用真实 ApiError 422；网络未知仍 BLOCK，不用 generic Error 假装明确拒绝后解锁。Settings 未知 PUT 保留草稿与错误且 BLOCK，比旧可再次新写的夹具更严格；不能为保旧 enabled 状态重新引入重写。

Inbox 错误通过实际串行 ambient poll 保留；只读刷新不自动重 POST。Role/Projects/Settings/Tools/数据库均接实际 owner 和 DOM，不以 presenter 替代目录/输入/晚读取合同。

## 42 文件逐项映射

下表列原定义数与实际生产消费者。完整逐 `fullName` 的机器对照、源哈希与退出记录由本轮外部证据提供；统计索引不代替各 spec 内行为链。

| 原 spec（保留路径） | 定义 | 实际生产消费者 | 保留行为 |
| --- | ---: | --- | --- |
| `frontend/src/components/AutomationHealth.spec.ts` | 1 | W3 HistoryDrawer/archive owner | FAILED/CHECKED 历史健康、连续次数与旧告警解除 |
| `frontend/src/components/CodeMergeEditor.spec.ts` | 1 | W4 publication MergeEditor | 真实编辑值、路径语言、差异、回调，不只占位 |
| `frontend/src/components/DatabaseConnectionDrawer.spec.ts` | 6 | W2 DatabasePage/controller | 原字段、预览/保存/测试、版本、明确错误、脏稿保护 |
| `frontend/src/components/DirectoryPathInput.spec.ts` | 4 | W2 Projects directory owner | 实际路径挑选、建议、取消和退休迟到读取 |
| `frontend/src/components/DirtyWorkspaceDialog.spec.ts` | 3 | W4 actions DirtyWorkspaceDialog | mandatory 文件处理/提交、逐项策略、统一停止取消和错误入口 |
| `frontend/src/components/ExecutionAcceptancePanel.spec.ts` | 3 | W4 readonly ExecutionAcceptancePanel，C Designer实际消费 | 机器/弱 diff/文件检查/双评审区分与非法JSON安全 |
| `frontend/src/components/ExecutionEvidencePanel.spec.ts` | 1 | W4 ExecutionEvidencePanel/evidenceController | 证据读取、分类、正文、搜索及原受控路径 |
| `frontend/src/components/GitCredentialForm.spec.ts` | 4 | W2 Projects GitCredentialFields/credential owner | 用户名/token 原许可、验证、dirty、未知与安全只读恢复 |
| `frontend/src/components/GitDiffScopeApprovalDialog.spec.ts` | 2 | W4 actions scope | path/patchSHA/CAS、逐文件决策、换行差异和待确认封锁 |
| `frontend/src/components/JudgeReviewCard.spec.ts` | 3 | W4 JudgeReviewCard | 中文角色、非内部ID、RichDocument、TIMED_OUT结束 |
| `frontend/src/components/LayeredErrorPanel.spec.ts` | 3 | W4 LayeredTaskError | 层级/停止与最新Judge短摘要，不曝longraw |
| `frontend/src/components/MarkdownDocument.spec.ts` | 15 | W3 RichDocument + React Mermaid | DOMPurify、安全图/链接、IO失效、thinking与唯一React生命周期 |
| `frontend/src/components/McpToolPolicyPanel.spec.ts` | 3 | W2 ToolsPage/tool policy owner | 工具目录真实读取、可配置权限、catalog失败与原草稿 |
| `frontend/src/components/PendingQuestionCard.spec.ts` | 2 | W4 Inbox QuestionForm/Inbox owner | 推荐/有序答案/补充字段、非法空答案与显式提交 |
| `frontend/src/components/ProjectAssistDialog.spec.ts` | 3 | W2 ProjectsPage assist owner | 原GitLab字段/配置版本、不回显token、跨project late/error |
| `frontend/src/components/ProjectDocumentPathDialog.spec.ts` | 3 | W2 ProjectsPage document owner | 真实路径/已有约定/生成、只读预览与明确应用 |
| `frontend/src/components/RollingPackageWorkbench.spec.ts` | 6 | W4 RollingPackageWorkbench/rolling controller | 冻结包、显式规划审批、correction body/CAS/冲突仅读 |
| `frontend/src/components/SessionLifecyclePanel.spec.ts` | 1 | W4 SessionLifecyclePanel/lifecycle owner | 持久todos/checkpoints、真实sync、DIRECT回退禁用 |
| `frontend/src/components/SessionMonitorPanel.spec.ts` | 7 | W4 SessionMonitorPanel/session controller | role懒读、local session精确key、1200poll、活动、Todo、500/680、回答 |
| `frontend/src/components/SessionRoleSummary.spec.ts` | 3 | W4 SessionMonitorPanel冻结role disclosure | permission中文、原revision/SHA技术展开、历史未配置 |
| `frontend/src/components/SkillBrowser.spec.ts` | 3 | W2 ToolsPage skills owner | 技能列表/文档懒读、错误与退休scope |
| `frontend/src/components/StageRail.spec.ts` | 4 | 真实React StageDiagram | 原顺序、状态和版本props、ReactFlow几何与唯一React |
| `frontend/src/components/TaskAuditEvidencePanel.spec.ts` | 4 | W4 TaskAuditEvidencePanel/evidence owner | Attempt/Verifier/artifact不可变历史、实际DIFF和persisted GIT数量 |
| `frontend/src/components/TaskDecisionPanel.spec.ts` | 16 | W4 actions decision | 所有结果动作、CAS/版本/阶段/补充body、六确认失效、late与accepted导航 |
| `frontend/src/components/TaskJudgeApprovalPanel.spec.ts` | 2 | W4 actions judge | 精确cycle/task/reviewBatch版本、真实确认和刷新 |
| `frontend/src/components/TaskPublicationActions.spec.ts` | 15 | W4 Publication owner/actions | preview原字节、工单、MR分支/终态、显式merge、AI不自动选中、同步冲突 |
| `frontend/src/components/TokenUsageWindow.spec.ts` | 2 | W4 pure TokenUsageWindow + 实际session controller | 服务端单调累计、静默首值、正增量窗口、换scope重置 |
| `frontend/src/stores/taskStore.spec.ts` | 25 | 纯TS TaskApplicationOwner | 原25 reducer/summary/demo/lifecycle/SSE兼容断言全保 |
| `frontend/src/views/AutomationsView.spec.ts` | 4 | W3 explicit HistoryDrawer/archive decoder | 历史GET/健康/MANUAL/AUTO/REVIEW与两原导出，retired写入零 |
| `frontend/src/views/DatabaseView.spec.ts` | 1 | W2 DatabasePage/database owner | 原列表/连接元数据与selection详情 |
| `frontend/src/views/HomeView.spec.ts` | 2 | W2 HomePage + actual App/ReactRouter | 所有首页入口、品牌返回、未知redirect、两条Task深链 |
| `frontend/src/views/InboxView.spec.ts` | 7 | W4 InboxPage/inbox owner | 显式选择、version/回答/审批、串行poll、late与错误不被旧回执清除 |
| `frontend/src/views/InsightsDashboardView.spec.ts` | 3 | W2 InsightsPage/insights owner | 精确query、服务端质量/费用/汇总，中文枚举与cursor |
| `frontend/src/views/ProjectsView.spec.ts` | 9 | W2 ProjectsPage/projects owner | picker、约定生成应用、深分析poll/stop、stack、统计、DIRECT、解除登记 |
| `frontend/src/views/RecoveryStudioView.spec.ts` | 2 | W4 RecoveryStudioPage/recovery owner | 原三恢复模式、版本、只读证据/派生入口 |
| `frontend/src/views/RoleManagementView.spec.ts` | 10 | W2 RoleManagementPage/roles owner | prompt/history/diff、受限slots、权限、导入/未知原身份/发布 |
| `frontend/src/views/RuntimeView.spec.ts` | 4 | W2 RuntimePage/runtime owner | 原read/start/restart、等待/失败、退休late |
| `frontend/src/views/SettingsView.spec.ts` | 6 | W2 SettingsPage/settings owner + App task port | 模型资格/默认归属、高级数值、Runtime/demo、unknown草稿不重写 |
| `frontend/src/views/TaskDesignHistoryView.spec.ts` | 1 | W4 TaskDesignHistoryPage/history owner | 冻结快照/历史讨论与原附件，不追最新Designer |
| `frontend/src/views/TaskDetailView.spec.ts` | 25 | W4 TaskDetailPage + actual App/ReactRouter | 25项真实确认scope、queue/loop/Judge/currenterror、child导航、template排除 |
| `frontend/src/views/TasksView.spec.ts` | 8 | W2 TasksPage + TaskApplicationOwner/plain port | 列表筛选/缓存、archive/delete/restore/demo、原query |
| `frontend/src/views/ToolsView.spec.ts` | 2 | W2 ToolsPage/tools owner | MCP及skills完整可达、model资格与只读目录 |

## 作者聚焦结果

`B-42-final-candidate.command.txt` 记录确切 `npx vitest run <42原路径> src/pages/w6-tests/ordinary/ownership.spec.tsx --maxWorkers=1 --reporter=json`。

- 原 42 文件 **229/229 PASS**，原完整 `fullName` 的逐文件 multiset **42/42 相同**，无 missing/extra/skip；机器对照 `B-fullname-mapping.json`。
- 新 `ordinary/ownership.spec.tsx` **6/6 PASS**：构造零 IO/SSE、应用退出迟到读无发布、Runtime乱序/demo scope、实际根 StrictMode 两次 attach/disposer、keyless UNKNOWN仅仅读且原输入不变、rollback confirm真实Task许可重核、显式sync与close零cancel。这是6个测试定义，其中部分包含多个负控，不能把子assert加到229。
- 合并作者批次 **235/235 PASS / exit 0 / 0 skip**，43个spec。原始 `B-42-final-candidate.{json,log,exit}` 在 `/workspace/react-full-w6-evidence/`；纯TS类型/完整构建仍由root证明，不沿此单测冒通过。
- 组长 strict TypeScript 指出测试 DTO/空值表达后，9 个 test/helper 做类型夹具修正，生产字节未变；同一批次 `B-42-final-type-fixtures.{json,log,exit,command.txt}` 再次 **235/235 PASS**。`B-fullname-mapping-type-fixtures.json` 仍证 42/42 文件的 229 个原完整名称精确相同。`Dom.get()` 的非空返回由实际 missing-throw 守卫保证，Publication 通过真实 API spy 调用而非把 MockInstance 当函数；诊断分页不虚构 facets，缺 cursor 使用正式 DTO 的 undefined。
- 后续两处正式 DTO 夹具精确补齐：`TemplateSessionDiagnosticPage` 为 `nextCursor:null,hasMore:false`（不是通用 CursorPage），Knowledge history CursorPage 含 `facets:{}`。`B-236-final.{json,log,exit,command.txt}` 实际联合运行原235及下述首次活动错误独立1，**236/236 PASS /0 skip /exit0**；没有改变原229数量或断言。独立 partial400 的1项随后原样单独验证，不混为该236批次。
- StrictMode Session原setStart采用有效lease微任务：首replay在读取开始前已失效，第二有效lease真实GET1。测试分别证 attach2、首disposer已调用、第二挂载时active、最后两个都释放；没有把未发生的GET2假装重放证据。

## 旧 SFC 退出与限制

对应41个原同名SFC及MarkdownDocumentLegacy/StageRailLegacy先按原字节归档，路径/SHA/删除状态见 `B-vue-retirement.json` 与 `B-retired-vue-source/*.txt`，**43/43 已删除**。最后 PendingQuestionCard 等 C 的 DesignerView 原测试真实 React 化并移除执行 import 后才删除。此为保留历史源码证据，不是继续可执行Vue消费者。删除前已扫42测试零Vue/Pinia/Element/Router执行导入；根/shared/other stores/其它分区旧技术由对应owner清理，不能据本分区零Vue声称全站完成。

未执行真实 Provider、Java、浏览器像素/RO/GC、全量unit/type/build；没有安装/提交/发布。剩余全站门禁由组长执行，非作者审查另列后续节，不以本组自测替代。

## 对 C 的最终独立审查

只读范围是 W3 PPT/Knowledge/Templates、W5 Designer 的本轮最小生产改动与 C 原 47 文件/250 定义映射，未编辑 C 实现。PPT restored-message 仅用实际 messages GET 的 documentId、key、expectedRevision、text、scope 精确匹配解除已接受输入，不靠渲染自动 POST；操作/计划 accepted readback 仍有最低 revision 保护，原 File/SHA 和 keyless 仅读恢复由原纯 TS owner 持有。Document clarification 的 updated callback 绑定 run.id/requirementRevision/requirementKey，退休表单不能借新 callback 写另一 scope。Designer 活动首次累计静默、后续单调累计与 850 ms delta 由唯一 owner 管，展示共用无 writer/timer 的 TokenUsageWindow。

发现并关闭一项 P2：首次活动 GET 失败时 `activityError` 已存在而 `activity` 尚不存在，Discussion 与 Router context 的 activity 条件使告警不呈现。旧 CurrentActivity 即使没有首份 DTO 也显示读取错误。永久独立回归 `ordinary/designer-activity-independent.spec.tsx` 用实际 createDesignerController、真实 API spy、纯 React 订阅和生产 Discussion：先证 GET 原 id、无 activity、snapshot 中文 error，再要求真实 DOM 同一告警。`B-C-activity-initial-before-valid.json` **1 FAIL / exit 1** 正确失败于最后 DOM 断言；对应 4 份 source SHA 与 before 清单一致。C 只在 Discussion/Router 无 activity 而有 error 时独立常显告警，未造 activity DTO/改变 owner、写请求或 poll。`B-C-activity-initial-after.json` **1 PASS / exit 0**，测试字节与 valid-before 归档完全相同，after 4 份 source SHA 与运行后字节一致。第一次 `B-C-activity-initial-before` 失败于测试父 context 夹具，单独保留，**不计业务 RED**。

另发现并关闭初始附件部分写入被400掩盖的身份问题。Backend只读证据：`DesignerAttachmentCommandService.java:45` 先逐文件 prepare，`:65` 创建 session，`:75` 才 changePrepared；`DesignerAttachmentContext.java:85` 后续 validateBudget，`:137` 累计超过50MiB的 `ATTACHMENT_SESSION_TOO_LARGE` 仍是400。因此不能把所有400视为整个会话创建完全未发生。prepare中可证的 `ATTACHMENT_FILE_COUNT_INVALID/DUPLICATE_FILENAME` 与 Store.inspect 中的 `FILENAME_INVALID/EMPTY/FILE_TOO_LARGE/TYPE_UNSUPPORTED/CONTEXT_TOO_LARGE/PARSE_FAILED/MAGIC_MISMATCH/MACRO_FORBIDDEN/UTF8_REQUIRED` 属于此前置检查；`SESSION_TOO_LARGE/MESSAGE_INVALID/MESSAGE_TEXT_REQUIRED/STORE_FAILED/PATH_ESCAPE` 不纳初始预写拒绝白名单。

永久独立回归 `ordinary/designer-initial-attachment-independent.spec.ts` 用真实 Designer owner 收到 partial400：先证 POST1与原 File 实例，要求 UNKNOWN/BLOCK；后来草稿和 File 不自动写，显式恢复仍须原 submissionId/body/File，初始 draft 不重建，随后真实 `ATTACHMENT_INITIAL_SUBMISSION_INCOMPLETE` 409仍保 UNKNOWN/BLOCK。`B-C-initial-partial400-before.json` **1 FAIL /exit1** 于 SETTLED≠UNKNOWN，原源码全文和 SHA 已归档；C 的 endpoint classifier false 曾被 run 的默认400规则覆盖。最小修以 `definitiveOverridesDefault` 仅初始 multipart owner 优先，原 saveDraft/applyProfile 的默认400/401/403/422分类保留。`B-C-initial-partial400-after.json` **1 PASS /exit0**，测试字节完全同before，运行后 controller SHA同after记录。明确前置 `ATTACHMENT_TYPE_UNSUPPORTED` 拒绝后可改 File 并显式生成新 submissionId；UNKNOWN/accepted均不能借此清身份。此为确定性前端 transport mock 加后台分支源码证据，**未执行 Java或实际50MiB上传/数据库副作用**。

两独立项与原229/作者新增6分开统计，当前 **237个定义=229原+6作者+2独立**；作者最终联合批次是236，partial400另外1。最终 clean ci 后另将这两个永久独立负控一起对 C 最终字节复跑，**2/2 PASS /0 skip /exit0**；这是已有定义的重复复验，不再增加定义总数。

最终检查记录为 `/workspace/react-full-w6-evidence/B-C-independent-review-final.json`。C 的冻结清单 `C-freeze-source-hashes-final.json` SHA256 为 `57b96e1aa1aba1429ece4c6930702ebf405a1d52317942128ba2c3474709d1c8`；67 个 source/test/helper 文件在独立运行前后逐项 SHA 均匹配，运行没有编辑 C 的任何文件。只读审查还核对了以下具体边界：

- `ppt/controller.ts:153,186`：恢复消息只以原 documentId/key/expectedRevision/text/scope 的权威 GET 行作接受证明；不匹配继续 UNKNOWN，挂载不发送消息。原 plan/operations 的 acceptedRevision 与仅仅读恢复保持；File/字节哈希重选不能伪装原实例跨刷新恢复。
- `designer/controller.ts:109–151,312`：初始 multipart 的明确前置拒绝白名单具有唯一优先分支；generic400 和部分会话已创建的错误不能被默认分类解锁。仅已明确拒绝且未接受时允许纠正 File 后显式创建新意图；UNKNOWN 恢复始终原 submissionId/body/File，accepted 不重复写。其他原 saveDraft/applyProfile 分类没有被全局替换。
- `Discussion.tsx:30–38` 与 `DesignerPage.tsx:77`：首次活动错误没有 DTO 仍可见，已有活动的重连错误保留原正文；累计 token/短暂 delta 仍由唯一 Designer owner 读取与管理，共享展示组件不新增 timer 或 writer。
- C 的测试词汇适配器只驱动实际 W3/W5 生产组件或纯 TS owner；PPT load/close 持有实际 viewlease，Document/Source 运行页使用实际 App/单一 ReactRouter，callbacks 和退休读仍绑定原 scope。历史声明中的 `usePptStore` 等词汇不是执行 Pinia 或新建业务实现。

独立解析 `test-ownership-initial.json` 与 C 最终作者原始 `C-47-final.json`：**47 个原文件、250 个原 fullName 全部存在，missing=0**。额外 4 个定义仅在 `frontend/src/stores/pptStore.spec.ts:395`，分别核对恢复消息的 key/text/revision/scope；C 最终作者批次 **254/254 PASS /0 skip**，原始 JSON SHA256 为 `ad34bdfb984e28cb37b407a22ffb4b0628f163e65cb537fce649cdc1ec37226d`。这 254 项是 **C 作者执行、B 核对来源和名称映射**；组长已经运行最终全量，为避免重叠 UI 测试负载，本次 B **没有独立再执行整个254批次**。准备过的完整批次命令保持 NOT_RUN，不作为通过证据。

本次 B 实际独立命令在 `frontend/` 执行：

```sh
npx vitest run src/pages/w6-tests/ordinary/designer-activity-independent.spec.tsx src/pages/w6-tests/ordinary/designer-initial-attachment-independent.spec.ts --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w6-evidence/B-C-independent-final-two.json
```

对应 `B-C-independent-final-two.{command.txt,json,log,exit}` 保留完整执行证据。两份测试与各自有效 before 归档逐字节相同；活动告警和 partial400 的真实 RED→GREEN 闭环均在最终字节仍成立。未发现本次独审范围内剩余阻塞；全站 browser/资源账本、安装审计、type/build/fullunit 由组长独立汇总。当前聚焦结果不证明堆对象不可达、GC 回收、真实后端副作用或全部端点的幂等能力，也不承诺刷新后恢复原 File 实例。
