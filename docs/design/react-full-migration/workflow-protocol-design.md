# Workflow、Requirements、Designer 与模板任务的全 React 迁移协议设计

状态：第二阶段设计候选，未实施。源码基线为 `a3c692d38925206883f2b0a1255479108cfd439e`，工作区为 `opencode-loopper-react-full` / `feat/react-full-migration`。本次只读源码、合同与测试并编写本报告，没有安装依赖、执行测试、启动浏览器或修改运行代码；下文“已有测试”表示已审阅的测试合同，不表示本轮重新通过。

作者是既有团队任务 `/root/react_flow_workflow`。既有启动/分配记录显式指定 `model=gpt-6.1-sol`、`reasoning_effort=xhigh`；当前工具没有实时读取平台模型配置的接口，因此这是历史配置证据，不是当前平台字段的实时核验。已读取根及 `frontend/AGENTS.md`；本轮明确的仅设计、不提交授权优先于默认交付流程。

## 1. 关键结论与范围

1. 已验证 React 画布应直接保留。仍在 Vue 页面的命令、草稿、undo、上传、导航与订阅所有者才是本次迁移对象；不能把 API 或幂等键下沉到画布。当前适配器的 DTO/事件边界见 `frontend/src/components/workflow/WorkflowCanvas.vue:11–27`，真实 React 接口见 `frontend/src/react/workflow/types.ts:3–26`。
2. “重试”有多种业务含义：图成功后只重试布局；写成功后只重读；通用模板创建成功后重试同一个 task 的 start；未知 multipart 保留原 File 与请求；停止未知只核对原执行。一个通用“失败后再提交”hook 会破坏这些合同。
3. Designer 是本专题最高风险页：2145 行 SFC 内持有会话恢复、SSE/轮询、真实 File、讨论/设计/画像修订、LoopSpec CAS、人工批准及确认后任务交接。应先提取并验证协议 controller，再迁视图，不能逐个按钮改 JSX 后认为页面等价。
4. 新需求、源码/文档运行页、Designer 的当前导航保护与需求详情并不一致。下文单列基线缺口；不能把迁移顺手改变的恢复行为称作纯 UI 等价，也不能因已有画布验收通过而漏掉页面所有者。
5. 本专题覆盖 13 个路由记录（含 `/automations` 重定向），其工作包依赖任务详情、共享 Markdown/CodeMirror/文件选择器/权限与模型设置，但不替这些专题重新定义业务所有权。

权威合同以现有 [工作流合同](../../workflow-contract.md)、[设计合同](../../design-contract.md)、[七特性合同](../../seven-feature-contract.md) 为准：尤其工作流图/布局版本、Confirm/Start 分离、上传与输入冻结、候选显式应用；Designer 原子上下文投递、冻结历史及旧画像；模板 V1–V7 冻结策略、批次独立失败与停止证明。以下源码行号均针对上述基线。

## 2. 架构决定与单一业务所有者

统一设计选型是 Ant Design `6.6.5` + 已有本地 Lucide `ReactIcon`；状态承接使用 Zustand `5.0.15` vanilla 工厂；最终路由为 React Router Data 模式 `7.18.4`。这些是后续实施的批准设计，本报告不安装或验证这些新依赖，也不顺带升级 Node 或 React Router 8。

过渡阶段仅保留一个 VueRouter history，由临时 `ReactRouteBridge` 承载整页 React，并注入框架无关的导航、确认及文件选择端口。所有页 ready 后一次原子切换根与路由。不能同时创建 VueRouter 与 React Router 两套 history，也不能让双方 guard 各弹一次确认。最终删除 Vue/Pinia/Element Plus/适配器及 Legacy SFC，而不是把整页 Vue 永久嵌在 React 内。

建议区分三层，避免把“框架无关”和“无副作用”混用：

| 层 | 保留/迁移边界 | 所有权与约束 |
| --- | --- | --- |
| DTO、计算与校验 | `types/`、`graph.ts`、计划保护/值解析/冻结时间线等 | DTO 无 Vue/React；计算不可产生执行事实。生成 UUID 的 graph/preset 工厂虽无框架依赖，仍有随机副作用，测试/重放时显式注入或在动作入口生成一次。 |
| 协议 controller + vanilla store | 原 Vue 页/Pinia/hook 的读写编排、原请求、回执、File、scope、计时器 | 一个动作只由一个 controller 调用 API；store 是发布 snapshot 的承接层，不在 setter/subscriber/effect 中自动发写命令。Pinia 过渡仅 delegate 同一 controller，不能复制 pending 状态。 |
| React 视图 | JSX、AntD 控件、语义标签、焦点、CodeMirror 与现有 React 画布 | 只读取 snapshot、发显式意图。挂载/StrictMode 重放不得 start/confirm/retry/write；订阅读取由明确 attach/dispose 生命周期控制。 |

建议 controller 公共形态为 `getSnapshot / subscribe / setScope / dispose`、具名动作与 `canLeave`；导航端口接受稳定 URL/replace 及已确认的任务交接意图，确认端口返回用户选择。每个异步动作保存 scope token（实体 ID + 本地 epoch，必要时再含 attempt/revision）；modal、每个 await 和读回之后分别检查 token。请求取消仅结束本地读取，不能伪装服务端执行已停止。

会话级待确认命令与 File 不能成为某个折叠面板的 useState。owner 生命周期需覆盖面板关闭、React/Vue 展示切换和同页重新渲染；普通实体切换只在 `canLeave` 允许后失效旧 scope。网络回执属于原 operation，不得解除新实体的 busy/pending。已有先例是 `domain/acknowledgedOperation.ts:2–23` 与 `components/workflow/command.ts:10–33`，其中 callback 内部还必须逐次检查 scope，不能只依赖 callback 开始时检查一次。

功能合同等价不要求复制旧页面外观。用户本轮新增的 shell、导航、信息层级、密度与动效翻新，可以改变页面组合和视觉位置；本报告只固定动作含义、输入输出与状态所有者。首页/列表/表单/任务详情/设置的 before→after、组件层次及独立本地模拟原型截图由统一视觉设计文档持有，本专题给出这些呈现限制：列表 secondary actions 保持键盘可达；表单收起不能丢 File 或未发送值；需求/Designer 首屏优先当前阻断与下一步，历史错误/已成功证明折叠为中性证据；画布 inspector 改位置后仍保留 selection/focus 与同一个 owner；动效尊重 reduced-motion，只展示真实读取/状态变化，不能伪造进度。窄屏可改变列数与面板排布，但不能通过隐藏恢复入口来降低密度。

## 3. 路由、深链与输入输出清单

路由定义见 `frontend/src/router/index.ts:14–31`。静态 `/new` 与运行子路径应优先于参数路由；保持刷新/后退/前进、已有查询参数与服务端 history fallback。

| 路由与当前入口 | 输入/深链 | 输出及写入/订阅所有者 | 页面级迁移位置 |
| --- | --- | --- | --- |
| `/workflows` — `WorkflowLibraryView.vue:11–47` | 搜索、种类、cursor；行带 builtin/version/revision | 页面读取模板页；显式复制/归档持原 row/key；进入 editor 或 `/requirements/new?template=…`；无订阅 | W2 列表视图，写 owner 先 W1 提取 |
| `/workflows/new`、`/workflows/:id` — `WorkflowEditorView.vue:24–48` | 新空图或准确模板 ID；已读取 baseline version/revision/layoutVersion | 页面持图/布局/选择/undo/校验/双阶段保存；已有 React 画布发意图；无业务 SSE | W5，graph/controller W1 |
| `/requirements` — `WorkflowRequirementListView.vue:11–27` | 本地项目筛选与 cursor；新增链接带 projectId | 页面持 generation 的 REST 列表，进入准确需求 ID；无写/订阅 | W2 |
| `/requirements/new` — `WorkflowRequirementNewView.vue:12–43` | `projectId`、`template`、`legacyDraft=1`；无 template 默认读取服务端 `builtin.workflow.development` 实际 revision | 页面冻结 create body/key；receipt 后进入 `/requirements/:id`；两个初始读取独立成功/失败；挂载不创建 | W5；初始查询解析 W1 |
| `/requirements/:id` — `WorkflowRequirementView.vue:34–92,207–220` | 实体 ID；服务器 requirement/execution/control；面板选择与草稿属于该 scope | 页面统一 plan/start/pause/confirm command owner；子面板独占其节点/上传/发布命令；2.5s REST 协调读取 | W5，高风险计划/执行界面 |
| `/designer` — `designerEntry.ts:4–15`、`DesignerView.vue:1151–1187` | 显式 sessionId 可进入历史；可带 projectId、mode=edit；无项目时可恢复本地 workspace 指针；否则跳新需求并传 legacyDraft | Designer 页面会话 controller 持草稿、File、讨论/画像动作与 SSE/轮询；提交已确认 task 后交 taskStore 与导航 | W5 最后段 |
| `/designs` — `DesignerHistoryView.vue:81–138,152–199` | URL 中 projectId/status/archive/order/q；180ms 搜索 debounce | 读取历史/计数、显式归档/恢复/重试停止；继续链接传 sessionId/projectId/mode；已确认任务只读 | W2 视图，停止语义复核依赖 W4 |
| `/tasks/:id/design` — `TaskDesignHistoryView.vue:15–63` | task ID，读取冻结 draft/designer/attachments | 只读冻结记录与附件预览；不会恢复最新 Designer 来代替 task 冻结版本；无写/订阅 | W4，由 Task 团队实现，共用 frozen DTO |
| `/automations` — `router/index.ts:25` | 原书签路径 | 重定向 `/template-tasks`，不创建 automation 或 task | W0能力映射／W6路由兼容 |
| `/template-tasks` — `TemplateTasksView.vue:20–145` | 项目深链；server catalog/template version/capabilities；分支、日期、目标路径、真实 File | 页面分三条提交路径：普通 task→create/start；source→source run；document→multipart run；恢复文档 by-request；三 store 保留原操作 | W3 |
| `/template-tasks/source-runs/:id` — `SourceTemplateView.vue:12–98` | source run ID；冻结 manifest/profile/batch projection | 页面 own run 命令；EventSource + 10s REST，180ms 合并刷新；源覆盖/制品/批次子读取；linked task 状态独立 | W3，执行去重验收依赖 W4 |
| `/template-tasks/document-runs/:id` — `DocumentTemplateView.vue:15–79` | document run ID；requirement/source revision、固定文件 SHA；linked task/session ID | 页面 cancel/resume + EventSource/REST；子面板补传/澄清/报告/批次恢复独占命令；历史 run 不改新默认策略 | W3，linked Task/Designer 跳转 W4/W5 |

`/designer` 的 guard 是历史兼容入口，不应改成永远重定向；`designerEntry.ts:5` 明确保留显式 sessionId，`:9–11` 读取 workspace，`:15` 才转新需求。React 路由切换时还要处理同一路径不同 query 的 scope 更新；当前 Designer 主要在 `onMounted` 读取 query，不能以 React 未重挂为由复用旧会话。

## 4. Workflow Library 与 Editor 的动作合同

| 动作 | 捕获输入与返回 | owner、错误与导航 |
| --- | --- | --- |
| 列表搜索/更多 | query/kind/cursor；`WorkflowPage<Summary>` | `WorkflowLibraryView.vue:15–20` generation 拒绝旧过滤结果；切过滤不丢 `pending` 行命令。复制用准确 sourceRevision，归档先确认并带 displayed expectedVersion（`:22–34`）。 |
| 节点/公共字段编辑、preset 插入、删除、undo/redo | `WorkflowGraph` 与 `WorkflowLayout` clone；插入准确 preset version/role revision；删除先处理消费者输入引用 | Editor `:50–69,98–104` 单次历史，最多 100；`graph.ts:15–25` 拒绝自身、重复、环；`:39–42` 有消费者则拒删；公共输入及消费者一起修改不能产生两个 undo。 |
| 端口拖线/点按、plus/键盘连线 | `(from,to)` 或原两步节点 id | Canvas 只发意图；Editor `:87–97` 与 Requirement `:125–142` 的原校验 owner 负责落图；reverse Handle 保持 source→target，不能双命令。 |
| 显示位置、fit/focus/reveal、校验 | layout；validate 返回 diagnostics | Editor `:104,109–114`；builtin 只允许显示位置；真实 Canvas `WorkflowCanvasReact.tsx:56–59` fit 有保存语义、reveal 只显示且不脏；focus 不写 API/undo。 |
| 保存/复制冲突草稿 | immutable graph/layout + source/version/revision/layoutVersion + 各自 requestKey；两个 `WorkflowReceipt` 后 GET | `save.ts:18–38` 一次 prepare，已得 graphReceipt 不重写图，已得 layoutReceipt 不重写布局；写后 GET 失败只重读。create 回执已覆盖初始 layout；copy 精确来源（`:40–41`）。 |
| 409、刷新、离开 | 保留本地 JSON/基线冲突；用户明确另存/重读 | Editor `:116–142` 未知/处理中 save 硬阻断导航和 reload，普通 dirty 沿原确认；409 不覆盖草稿；已完成创建后的 URL replace 是同 owner 交接例外，不弹自身 dirty。 |

API 边界 `api/workflow.ts:6–15`：create/revise/copy/archive/validate/layout；图与布局的 CAS 域不同，不能以一个 version 字段替代。DTO `types/workflow.ts:4–17` 包含 moduleVersion、roleRevisionId、输入输出、完成规则、outcomes/parameters、layout 与 receipt。迁移表单必须无损保留原 module 及隐含专业参数，不能仅保存屏幕可见字段。

## 5. Requirement 页面与嵌套 owner

页面派生条件位于 `WorkflowRequirementView.vue:46–67`：PLANNING/PENDING_START 与执行状态分开；STOPPING、stale candidate、未读控制和 pending 锁编辑/执行。已 ACTIVE/SUCCEEDED 的节点及其祖先、候选范围外节点按 `planEditing.ts:2–11` 保护。`:260` 将 `readonly` 与 `movable` 分开，运行中的图不可编辑但显示位置可拖；保持该合同。

| 动作 | 输入/回执与权限 | owner 与恢复 |
| --- | --- | --- |
| 新建需求 | project、template ID + 实际 revision、title/objective/key → receipt.id | New `:17–20` 一次 body；不通过猜默认版本创建；失败独立恢复初始项目/模板选择（`:33–42`）。 |
| 计划保存/执行中改计划 | exact requirement version、plan revision；graph/layout key；optional candidate ID/version → graph/layout receipt 后 GET | `planSave.ts:4–17` 按运行/候选选择 revise/applyPlan/applyCandidate；页面 `:147–155` 每 await 校验 scope；409 保留草稿并禁执行。 |
| Confirm | 只确认当前计划，不执行/不入队 | `:157–165` 保留尚未保存的 presentation；已确认任务仍需 Start，不能在 effect 自动开始。 |
| Start / SINGLE / UNTIL / CONTINUOUS | key、expectedVersion/controlVersion、targetKey、inputs、model、checkpoint attempt IDs → control/execution projection | `:169–177` 冻结原命令；默认模型尚未读取禁开始，人工-only SINGLE 不强制模型；checkpoint 只能确认当次 attempt，不复用旧轮勾选。 |
| Pause / 进入编辑 / before-start cancel | control version 或 requirement version/key | `:179–193` 进入编辑先暂停派发再读权威版本；cancel 需确认；不把 pause 等同终止已有远端进程。 |
| candidate 预览/编辑/应用/拒绝 | originalGraph/diff/baseRevision/sourceCompleted/stale/protectedNodes；expectedCandidateVersion | `:195–201` 预览不应用；source 清理期间只读/预览，成功完成后显式应用；`WorkflowCandidates.vue:12–30` 子命令 owner。 |
| 人工结束需求 | target/reason/key/expectedVersion；intent + pending attempts/resources | `WorkflowFinish.vue:16–42` 子命令及 2.5s 读回；STOPPING 显示停止未确认，不能先乐观标 COMPLETED/CANCELLED。 |
| 另存模板 | CURRENT/INITIAL selection（expectedRevision+graph/layout）；previewSha256、title/description/key | `WorkflowSaveTemplate.vue:25–42` 服务端 preview 后提交；不复制 current execution 或覆盖当前计划；preview 使用现有 React 画布，未知操作锁离开。 |
| 选择节点/边、关闭/Escape、换面板、路由 | 当前 inspector 未发人工结果、uploadBusy/Pending、export/publish/finish/candidate command | `:99–119,207–218` 汇总 nested canLeave；重复选择不弹；未知节点操作不能借空白/Esc 卸载；父面板 key `:278` 包含 requirement/node。 |

`WorkflowRequirementView.vue:69–84` 的 2.5s REST poll 跳过 document hidden 或 locked 的写/上传/节点阶段，读取由 generation + request ticket + command scope 隔离；新 revision 遇 dirty 保留草稿并报告冲突。React 迁移应保留这种读写协调，而不是新增第二个 query-cache 定时器覆盖本地 draft。

### 5.1 子面板的资源、输入与写入边界

| 子组件/实际源码锚点 | 当前输入与 owner | React 提取/保留约束 |
| --- | --- | --- |
| `WorkflowNodeRun.vue:42–79,90–115` | requirement/node/latest attempt；attempt 列表、冻结定义/inputs/result/activity、2.5s poll；人工 complete 与精确 model/command process version stop/resume | 按 requirement+node+attempt scope；选择的是实际冻结 attempt，不拿最新 plan 替代；人工未发送 delivery 保留；deliveryAccepted 与 stopConfirmed 独立展示。 |
| `WorkflowDocumentInput.vue:10–44,46–69` | File[]、snapshot metadata/key/expectedVersion/revision；upload ready/reference/resume、历史/文件/正文 | 失败保留同一 File 实例及顺序；1–10、单份20MiB/总50MiB限制；只 ready 上传可显式采用 reference；Start 冻结该引用，不自动选最新。面板 key 见 `WorkflowValueFields.vue:13`。 |
| `WorkflowInputContent.vue:15–34` | requirement/node/attempt/name/kind/hash，paged text offset；AbortController | 完整 identity 校验与 cursor 进度，每页最多12000个Unicode码点，offset按UTF-16且不拆代理对；完整后才当 JSON；旧 inline 与新 reference 兼容，不能把一页当整份输入。 |
| `WorkflowFiles.vue:8–20`、`WorkflowCodeChanges.vue:9–18`、`WorkflowDocumentPreview.vue:9–36` | 固定 attempt 的文件/差异页，固定 path/hash/blob/text；8项正文 cache、Abort、generation | DELETE 文件仍显示无下载；链接限制在固定报告相对目录，不能读工作区最新版本；旧父 key 依赖应成为明确 controller scope。 |
| `WorkflowKnowledgeEvidence.vue:8–37` | 固定 attempt 的工具证据页/按需正文，3项 cache；无写 | Knowledge 团队迁共用 Evidence 视图；保持已采集内容不随资料变动，并保证 cache 属于 scope。 |
| `WorkflowPublication.vue:19–47` | exact plan revision 的候选成果页；selection + hash、Abort/generation；汇总 child canLeave | 成功需求与人工完成后选择原失败成果均保持 provenance；不把预览当已发布。 |
| `WorkflowPublicationCommit.vue:13–35` | COMPLETED + GIT + loaded 无既有 status；版本、node/attempt/output/previewSHA/message/key；2.5s CONFIRMED poll | commit 与 push 两个 owner/动作；重试用已记录 version，未知原 key；绝不默认 push。 |
| `WorkflowPush.vue:14–45` | publicationVersion、remote、remotePreviewSHA/key；read remotes/status、Abort；existing retry/version | remote 修改使 preview 失效；未知远端结果核对既有推送，不能重新创建 push 当恢复。 |
| `WorkflowWritebackPreview.vue`、`WorkflowWriteback.vue:11–36` | DIRECT + COMPLETED；requirement/sourceSHA/previewSHA/version、零冲突/targetSHA；key/selection | 回填独立确认、先备份/核对目录，status CONFIRMED/APPLYING 轮询；队列/阻断真实展示，恢复原记录，不把 APPLIED 之前标成功。 |
| `WorkflowRolePicker.vue:16–38`、`WorkflowPresetPicker.vue:18–24`、`WorkflowChoice.vue:10–23`、`WorkflowBranchInput.vue:12–27`、`modelChoice.ts:11–37` | 精确 role/preset revision、项目/模板/分支 cursor、模型来源 default/control/user | 分页/搜索迟到隔离；panel 关闭不重置人工模型；user 优先于迟到 default/control；路径/角色权限不由 picker 推导。 |

`WorkflowNodeEditor.vue:1–58`、`WorkflowPublicInputs.vue:7–18`、`WorkflowValueFields.vue:6–13`、AddMenu/NodeList/PlanDiff/ContextPanel 属受控编辑与显示。专业编辑器（ReviewSource、Verification、Command、SourceDesign、DocumentReview）持节点参数的完整 patch，不直接执行。Outcome 标题、原固定专业输出、只读原因及公共输入绑定应一起迁移，避免“表单回写”丢未知 parameters。ContextPanel `:8` 的 focusOnOpen 与父节点键盘焦点恢复需要真实浏览器合同。

报告组件群不是可删的旧 Vue 装饰：Document/DocumentReview/DocumentPlan；Repository/Source/SourcePlan/SourceDesign；Snapshot/SnapshotSummary/SnapshotPartial；History/HistorySummary/HistoryAnalysis；Command/CommandEvidence/NativeTest；Verification/Review/ReviewSource；Knowledge/KnowledgeEvidence；TestScope/TestDesign/TestProfile/TestReview/TestSummary。大多数是 `content: unknown` 的 schema 验证/中文投影，例如 `WorkflowSnapshotReport.vue:10–19`、`WorkflowDocumentReviewReport.vue:12–27`、`WorkflowTestSummaryReport.vue:5–6`；应先提取无框架 decoder/projector，再写 React JSX，保留不完整/旧版本兜底。SnapshotPartial 与 KnowledgeEvidence 是实际按需读取 owner（`WorkflowSnapshotPartialReport.vue:7–20`、上表），不能误当纯 props 组件而遗失取消/范围隔离。

## 6. Designer：按动作与版本域拆 controller

### 6.1 DTO 与权限

`types/domain.ts:990–1045` 的 LoopSpec、`:1173` 的 draft、`:1194` 的附件、`:1440–1494` 的 DesignerSession 是原 API DTO，继续复用。`accessMode/READ_ONLY` 是产品内部模型执行权限，不等于用户不能回答/批准设计；可恢复、可确认、可重新编译等仍由服务端 projection/gate 决定。禁止 React 表单自行“修复”冻结 profile/工作包或自动批准历史合同。

LoopSpec 必须无损往返 Stage.workPackageId、criteria/runtime/verifier/assertions、全部 limits、model、sessionPolicy、nextAttemptPromptTemplate。已有 `LoopSpecEditor.vue:37–81` 外部 JSON→结构化控件→JSON，显式更改 verifier 类型才重置不兼容字段（`:155–167`）；API 正反映射 `client.ts:174–239,1147–1206` 不可用 AntD form 默认值覆盖。CodeMirror JSON 与结构化 editor 是一个草稿 owner，不各持一份可保存的 spec。

### 6.2 动作、回执和恢复

| 动作/当前入口 | 冻结输入与版本 | 结果、权限、owner 要求 |
| --- | --- | --- |
| 显式历史恢复/本地 workspace 恢复 | sessionId、bound draft；workspace 指针和未发送文字 | `DesignerView.vue:1020–1046,1124–1165`；backend 不可达保留指针，不新建会话；archived/CANCELLED/confirmed 不伪装可编辑；历史 mode=edit 必须走原 reopen requirement。 |
| 首次创建 draft/session 或上下文 multipart | goal、draft/settings、auto/story setup、原 File[]、submissionId；`initialAttempt` immutable snapshot | `:81–104,129–180,930–984`；未知保留最初目标/文件/ID，新编辑另留 composer，不混入 retry；仅明确 preflight rejection codes `:93–98` 可改附件重发新请求。 |
| 普通/需求/工作包 message 与附件 | scope、workPackageId、expectedDiscussionRevision/designRevision、text、File[]/submissionId | `:1308–1351` 原子上下文，成功 merge 持久 message ID 并清该次 composer；失败保留文字/文件；scope 不从后来的实时 session 临时推导；API `client.ts:1956–1965,1980–1989`。 |
| 问题回答/拒绝、停用附件/预览 | persisted question ID/answers；attachment ID、commandId；安全 preview | `:779–817,1353–1382`；回答卡仍按作用域/修订摆在对应设计稿前；停用仅停止后续使用，不删历史或宣称已停止远端执行；不可给所有 API 擅加并不存在的 requestKey。 |
| 任务设置 preview/update/confirm/reroute/cancel | taskProfile.version、run ID、intent/artifactKinds/large mode/component keys；preview.sessionRestartRequired | `:472–514,521–568`；明确确认重启设计、版本冲突刷新权威设置；预览不是执行许可。modal 前捕获 scope/version，返回后再查，不能对新 session 发旧 intent。 |
| 启用历史 large-task / 报告转设计 / 全自动 | expected discussion/profile/autoMode version、明确用户确认 | `:586–614,987–1015`；旧自动/逐包冻结策略保持；自动任务交接只接受服务器已创建任务，不靠 React effect 开始新 writer。 |
| Save LoopSpec | captured editor JSON + baselineVersion；validate→PUT→GET confirmation eligibility | `:1202–1236`；保存期间新编辑保留并推进 baseline；已落盘但 eligibility GET 失败显示“已保存/确认状态未读取”，禁止创建 task；409 preserve dirty+conflict。 |
| 显式 reload / V1 copy-to-V2 | captured draft/session identity，确认丢弃；新 V2 draft/session | `:1239–1276`；reload 每 await 检查 generation；V1 历史只复制，不改 immutable 已确认 spec。 |
| Confirm / 已确认任务打开 | expected draft version + server confirmationReady；task ID | `:1279–1306,1091–1094`；普通确认产生 PENDING_START；既有历史全自动沿冻结流程；task 已创建但目录准备 FAILED 仍打开同 task，不重复 create。 |
| Requirement/package accept/reopen 与各种 recovery | discussionRevision/designRevision/approvedDesignRevision、transitive dependencies | `:1384–1531`；显式批准前不继续；reopen 保留旧讨论、废弃受影响批准；requirement/profile/compiler/decomposition/package recovery 是不同动作，不能一键重试所有阶段。 |
| Start over / archive / stop | 当前 session、确认、返回 failedSessions/pendingFinalizations/archived事实 | `:1097–1118`；远端停止未确认不能清 workspace；历史列表 `DesignerHistoryView.vue:152–191` STOPPING 只允许重试停止，confirmed 只读。 |

### 6.3 订阅与显示边界

会话 poll `DesignerView.vue:713–765` 捕获 sessionId/pollGeneration，健康 1200/1500ms、失败退避上限12s，保留编辑 baseline；SSE `:820–851` 每 session own close/generation，terminal/error/auto 事件触发 REST 权威读，不渲染原始角色流当业务完成。`DesignerCurrentActivity.vue:31–73` 另有1.2s按会话活动摘要 poll，仅 while working 挂载；server-direct compilation 不应挂载远端活动读取。将订阅协调拆成 session reader 与可选 activity reader，但不新增第二条相同 SSE。

DiscussionHistory/SystemHistory/ValidatorHistory 的消息分组、已回答问题、持久化顺序以及工作包 scope 属展示投影；可复用 `utils/frozenDesignTimeline.ts:8`。PendingQuestion、TaskProfileRouterDialog、StagedFileContextCard、StoryBindingSetup 分别是问题/设置/真实文件/能力配置端口；StoryBinding 必须保持 capability 默认关闭、project/runtime 变更失效，不以统计授权替代业务授权。Markdown/安全附件预览与已经 React 化的 Mermaid 由共享 UI 专题承接。

历史列表读取及 task 冻结历史应作为只读 controller，不复用活跃 Designer poll：`DesignerHistoryView.vue:113–138` 查询/URL 是历史索引；`TaskDesignHistoryView.vue:22–63` timeline/附件属于已确认 task，冻结引用不追随 session 后续变化。

## 7. Template、SourceTemplate 与 DocumentTemplate

### 7.1 创建入口与 store

`TemplateTasksView.vue:54–64` 依服务端 capabilities 决定 branch/date/documentPath/source fields；`:30` 新创建目录过滤 REQUIREMENT_DEVELOPMENT，历史恢复仍保留；`:66–108` 分页项目/分支独立 generation，日期为用户本地日历 `YYYY-MM-DD`，snapshot FULL 不发送不适用日期。AntD DatePicker 更换不能变成 UTC ISO 时间、漏掉未在当前页的真实默认分支或强加新 capability。

| 提交路径与当前 owner | key、File、回执 | React controller 迁移边界 |
| --- | --- | --- |
| 通用 `templateTaskStore.ts:9–24` | input fingerprint → requestKey；create receipt.taskId 缓存，随后 start | create unknown 同 key；start unknown 只 start 同 taskId；`api/client.ts:1704–1705` start 本身无 requestKey/body，不杜撰 DTO。挂载只 catalog/query，不自动 start。 |
| 源码 `sourceTemplateStore.ts:6–31` + `SourceTemplateFields.vue:20–43` | fingerprint/input/key、completed；可选 sessionStorage；当前服务端预检 | 输入/project/template 改变使 preview 无效；create 未知同 body/key；完成后新的显式创建生成新 key；存储失败保留内存，不能依赖 sessionStorage 当唯一所有者。 |
| 文档 `documentTemplateStore.ts:8–51` | File 真字节 SHA-256 + 有序 filename/input fingerprint；metadata/key；runId/completed | hash/upload 期间 lock；File 不可 JSON 化；同字节同请求可重试，refresh `restore` 只 by-request GET 不上传；不存在才提示重选原文件，不自动重发。 |

当前三 Pinia store 是 app 实例级，而模板表单是 route 实例级。先让 store delegate 同一个纯 TS session controller，再换 React 表单；不能在 route unmount/new mount 时丢 taskId/pending，或同时留下旧 Pinia 与新 Zustand 两个写 owner。`TemplateTasksView.vue:110–145` 三分支导航结果需各自交接，恢复 previousRun 不是新任务创建事实。

### 7.2 运行页面动作与子面板

| owner/源码锚点 | API 输入/输出与权限 | 订阅与恢复合同 |
| --- | --- | --- |
| Source run — `SourceTemplateView.vue:28–98` | overview/version/progress/canResume；start/cancel/resume/retry/archive/unarchive，key/expectedVersion；retry modelIds sorted | generation + overview requestSequence + batchSequence 拒绝旧读；command 前 supersede 旧overview；取消确认前后检查同scope；SSE open/error/progress 180ms合并 +10s REST，terminal关流/定时器。 |
| SourceCoverage — `SourceCoveragePanel.vue:12–38` | run ID、实际 coverage/page/detail | ledger 只展示实际采集范围，不暗示“全库已扫描”；切run/version读失效。 |
| SourceArtifacts — `SourceArtifactsPanel.vue:17–63` | 固定 run/version 的制品页、按 name 正文、完成后 ZIP | 按需正文 generation；相对 link 不越报告根；objectURL 当前由0ms revoke，迁移需要精确 owner 登记并 dispose，不删除下载功能。 |
| Document run — `DocumentTemplateView.vue:27–79` | overview/version/canResume/canCancel；cancel/resume key/expectedVersion | SSE+180ms/10s读回；STOPPING 表示停止尚未证实；原文件未ready提示原上传恢复；linked task AWAITING_DECISION、是否测试过与模板任务完成分开。 |
| DocumentSupplement — `DocumentSupplementForm.vue:16–40` | 先 options GET 得服务端 request（key/expectedVersion/expectedTaskVersion），再 metadata+Files multipart | 同 options/request/Files 恢复原上传；invalid choice 不执行；成功才清；scope变更不得 emit到新run。 |
| DocumentClarification — `DocumentClarificationForm.vue:11–25` | requirementKey/requirementRevision + expectedVersion、answer/key → updated overview | 未知保持原回答/key；换需求scope重置；正式反馈不等于模型自行确认需求。 |
| DocumentRequirements — `DocumentRequirementsPanel.vue:24–62` | run/revision/issues cursor、detail revision、source expectedSHA | 查询项/正文/原文独立 epoch，按需展开，不把页数或模型候选当覆盖成功。 |
| DocumentSources — `DocumentSourcesPanel.vue:14–35` | run/sourceRevision、file ID、section ordinal、expectedSHA | 读取冻结原文，不取当前工作目录版本；完整scope与dispose需补W0证据。 |
| DocumentReports — `DocumentReportsPanel.vue:23–62` | 固定 report ID/name/hash、按需正文与下载 | sanitize Markdown；相对报告链接/多页数据/URL生命周期原样保留；不把空或旧正文当完整报告。 |
| TemplateReports — `TemplateReportsPanel.vue:9–22` | task artifacts、accepted、dualReviewRequired + 当前 repair round | V1–V7 按冻结 server projection；最新 V7 不要求旧双 AI Judge 时不能再前端强加；历史需要复核的不因新默认放宽。 |
| BatchRecovery — `TemplateBatchRecoveryPanel.vue:23–77` | task/documentRun；server ready/resume/taskVersion；选中 batch ID/expectedVersion，recheck expectedVersion |5s有界poll，隐藏/忙/选择时协调；只选显式失败批次，独立工作继续；未知先刷新，无自动重试整个run；当前 endpoint 没有通用 commandId。 |
| SessionDiagnostics — `TemplateSessionDiagnosticsPanel.vue:28–184` |过滤/cursor/detail；check只读核对；FINALIZE/STOP exact batchVersion + stable commandId |scopeEpoch拒绝旧结果；STOP确认后再检查scope；key为task/batch/version/action；未知保留原命令；停止proof与candidateAccepted独立；normalizer `api/templateSessionDiagnostics.ts:7–21` 无有效identity拒绝capability。 |

Source 和 Document 底层端点位于 `api/client.ts:1618–1634,1658–1690`；公共模板任务端点`:1696–1705`，diagnostics`:1787–1794`。保留 SSE 仅作为失效通知、REST 为权威；controller attach 可重新连接同 scope 的读取，但不得顺带 resume/start。运行页面与 linked Task/Designer 的状态不能被一个 `status` union 覆盖。

## 8. API、权限与复用清单

本专题的 API client/DTO 已无 Vue 运行时依赖；API 是浏览器 IO，不称“纯函数”。`api/client.ts:19–48` 保留 ApiError.status/code/layer、空回执、JSON及 multipart/header 合成，FormData 不手写 Content-Type；`api/workflowDocuments.ts:5–16` 和 `client.ts:1658–1674,1907,1960` 保留 metadata Blob+有序 File parts。

| 可复用模块 | 实际依据 | 后续动作 |
| --- | --- | --- |
| `api/workflow.ts` / `workflowRuns.ts` / `workflowDocuments.ts` |前者`:6–15`，runs`:11–50`含 control/attempt/frozen inputs/candidates/files，documents`:5–16` |原 DTO/路由/headers不重写；需要 signal 时只加读取消参数，不改回执。 |
| `api/workflowPublication.ts` / `workflowPush.ts` / `workflowWriteback.ts` |publication`:5–9`，push`:6–10`，writeback`:4–12` |独立 commit/push/writeback request/version/hash；controller复用endpoint，不合并副作用。 |
| `domain/acknowledgedOperation.ts:2–23` |write receipt记忆、inflight coalesce、active read |继续复用核心，提取 Vue `command.ts` 的 UI 状态适配；必要的持久化是页面协议，不改成 hook自动重试。 |
| `components/workflow/graph.ts:3–57`、`reviewSource.ts:4`、`presets.ts:3–7`、`values.ts:2`、`planEditing.ts:2–11` |无Vue的图计算/输入消费者同步/显式值转换/保护集合 |可留原路径再集中移动；graph/preset ID生成保留“一次意图一次ID”，不在render调用。 |
| `save.ts:18–41`、`planSave.ts:4–17` |框架无关的多回执IO编排 |直接复用，不为React重新实现CAS/partial success；控制pending归属在controller。 |
| `knowledgeBundle.ts:3–10`、`repository.ts:1`、`utils/frozenDesignTimeline.ts:8` |类型安全资料/分支中文/冻结历史投影 |纯计算复用；所有专业report decoder逐项无框架提取，保持旧schema降级。 |
| `modelChoice.ts:6–38`、三个template Pinia store |Vue/Pinia持状态但已有清楚protocol |迁为vanilla controller并保留 source/user优先、key/taskId/File；过渡Pinia只delegate。 |
| LoopSpec parse/backend mapping 与 display labels |`client.ts:174–239,1147–1206`，`utils/displayLabels.ts` |保留bound字段、中文和安全兜底；用户界面不用内部枚举/recordID替代名称。 |

本地 UI 标识不是用户权限系统的替代，也不能通过 UI disabled 推导服务端许可。工作流 mutation header 已在上述API保留，服务器 `WorkflowTemplateController.java:46–47` 校验；start/pause/finish/node/process/candidate等沿各controller校验。源码模板 `SourceTemplateController.java:22–42` preview/create/start/control 都要localUI，文档multipart/controls亦然。Designer stop `DesignerControlController.java:29–36` 明确header，其他Designer API应逐endpoint保持当前授权/CAS，不能假称所有 endpoint 都有相同header或擅自新增授权绕过。产品模型的READ_ONLY、UI 本地动作和服务端 lifecycle是三种边界。

## 9. 基线差异、静态恢复缺口与 W0 决策

以下不是本轮 React 新回归；本轮未运行复现。表中“源码明确”指控制流可读确定，“待行为复现”指风险路径仍需红测试确认。项目经理现已决定pending／unknown写入硬阻离开，普通dirty／未发送File明确confirm，不自动重发或换身份，无持久化／by-request不承诺跨刷新。逐项[W0红测／证据台账](w0-evidence-ledger.md)区分静态事实、待复现风险与未修复状态；生产迁移前先结清全部映射／行为门槛，不能为绿测删导航、暂停、下载或上传恢复。

| 编号/风险 | 实际源码证据 | 判断与建议门槛 |
| --- | --- | --- |
| B1 未知create导航可丢原操作 |New `WorkflowRequirementNewView.vue:22` pending只普通confirm；Library `:22–47`无路由guard；对比详情`:207–213`硬阻断page pending |源码明确的策略差异。PM已决定未settle写硬阻离开；原key／冻结body显式幂等恢复POST（无by-request GET），已知receipt只nav/read。另补取消导航resolve NavigationFailure与reject两条红测；普通dirty确认保留，不把设计当已修。 |
| B2 嵌套命令导航策略较宽 |NodeRun `:109`、Candidates`:30`、Finish`:40`、PublicationCommit`:32`、Push`:42`、Writeback`:33`允许confirm后离开；另存模板`:38`严格锁 |源码明确；父canLeave调用不自动提升成hardblock。逐动作核验原key/accepted恢复能力，尤其网络未知与processstop不应新key重发；不误报所有需求命令都已hard-block。 |
| B3 模板表单/运行页的pending生命周期 |TemplateTasks `:110–145`无leaveguard；三个store内存/可选storage各异；Source routewatch`:72–78`、Document`:48–61`reset pending，无leaveguard |源码明确，但是否实际丢请求需复现。W0拟增加统一待确认操作恢复展示及合法导航策略；不能从store“还存在”推断跨刷新File可恢复。 |
| B4 Document取消modal晚确认可跨run |`DocumentTemplateView.vue:63–74` current在modal前、generation却modal后捕获；无对current.id复核；Source `:80–85`有相应检查 |待行为复现：A确认框期间转B，随后A回执可能符合B generation。须红测后以captured owner/epoch在confirm后及write/readback后保护。 |
| B5 Designer离开只保护LoopSpec dirty |Designer `:207,1173–1186`不覆盖File/未发送文本/初次unknown或messagebusy；initialsnapshot是`:88–92`，后续File是真实例 |源码明确的guard覆盖缺口；W0分普通未发送draft、不可丢File与未知写，不能清workspace或把File序列化宣称安全；confirmedTaskNavigation保留特例。 |
| B6 Designer部分动作缺统一late scope |questions`:779–817`、profilepreview/modal/update`:472–505`、message`:1308–1351`未统一逐await scope gate；message用stable submissionId但当前input/version可变化 |待行为复现；只保留初始multipartsnapshot不足以证明后续message-body与key恒定。新增A→B/修订变化/用户边编辑边retry矩阵，controller明确 immutable operation。 |
| B7 未登记timeout/RAF及storage异常 |Designer terminal retry `:859–864`至多50个100ms递归timeout，unmount`:1167–1171`未登记；composerfocus`:1047`RAF；workspace`:970,1026,1086`直接storage访问 |源码可见的资源/恢复风险；需active callback→unmount首采样/迟到scope红测。storage helper`:106–114`有catch，但不能替所有访问证明容错。 |
| B8 只读历史/文档子读取scope不足 |History`:113–132,199`缺读取epoch/unmount失效；TaskHistory`:27–63`缺epoch；DocumentSources`:14–35`、Clarification`:11–25`、Supplement`:16–40`无dispose失效 |待行为复现；React复用组件不等于Vue旧key语义。先在base查迟到/卸载，再提取标准readonlyscope，不能让A附件正文回到B。 |
| B9 无key的endpoint不能用统一重写策略 |template start `client.ts:1705`，batch retry`:1698–1699`、部分Designer动作无requestKey；诊断recover有commandId |源码明确；分别用已有ID/version/CAS/状态read恢复。若需要新服务器幂等能力是单独协议工作，不在JSX改名时悄悄添加。 |

B1–B9全部进入[W0准入台账](w0-evidence-ledger.md)，不能将B8延后到生产波次，或将B9误判为统一补key的授权。静态风险先复现；实际修复需后续专项阶段放行，保留当前保护与原测试断言。没有证据不写成“已复现／已修”；本轮只修文档与原型交付，未修上述业务。

## 10. 已有行为测试与必须增加的验收

| 协议 | 已审阅测试源码锚点 | React阶段仍需真实证明 |
| --- | --- | --- |
| Library/search/key/版本 |`WorkflowLibraryView.spec.ts:32,38,46,51` |新React DOM筛选/分页、copy未知换filter保留key、builtin只读；测试不能stub回Vue页。 |
| Editor partial/unknown/409/undo |`WorkflowEditorView.spec.ts:31,45,51,64,77,91,115,129`；`save.spec.ts:15,21,29,34` |真React页+现Canvas selection立即保边、undo一次、reveal不脏；graphsuccess/layoutunknown/readfail分别只恢复缺阶段。 |
| 新需求actualrevision |`WorkflowRequirementNewView.spec.ts:18,26,29,36,45,57,63` |深链actualrevision/default独立失败、无mountwrite、unknown导航策略和storage失败明确。 |
| requirementcontrol/candidate/上传 |`WorkflowRequirementView.spec.ts:62,66,74,80,85,92,101,110,116,144,153,169,191,201` |真实表单与File从Esc/切selection/route/pref都不丢；只有owner写一次；runtime readonly+movable与checkpoint scopes。 |
| requirementlateisolation |`WorkflowRequirementScope.spec.ts:96,113,127,153,169` |跨A/B写回/读回/复合plan的latecallback不能清B锁、覆盖Blayout；需用不同DTO同json及相同layout验证scope，不只比较JSON。 |
| Designer multipart、CAS、确认 |`DesignerView.spec.ts:793,824,969,1738,1831,1865,1914,1980` |首multipart immutableFiles+body；followup unknown body/key冻结；已写GET失败只read；confirmedtaskpreparefailed打开原task不重create。 |
| Designer讨论/权限/历史 |`DesignerView.spec.ts:541,575,687,705,720,1128,1173,1258,1350,1492,1556`；History`:58,78,100,138` |同pathquery切session、modalA→B、SSE/timeoutlate、服务器直编不poll远端；冻结历史与默认新入口仍可用。 |
| LoopSpec无损 |`LoopSpecEditor.spec.ts:54,92,121,144,157` |真实AntD与CodeMirror混合编辑保存往返所有fields，外部刷新不抹draft、英文enum仍稳定；未知verifier不可随便变默认。 |
| 三templatecreateowner |`templateTaskStore.spec.ts:10,20`、`sourceTemplateStore.spec.ts:8,16`、`documentTemplateStore.spec.ts:15,26,34` |Piniadelegate/Zustand共享同controller、unknownkey/taskId原样；storage写失败；File相同名异内容/顺序/刷新重选；mountrestore只GET。 |
| source/documentread命令 |`SourceTemplateView.spec.ts:24,38,47`、`DocumentTemplateView.spec.ts:24,34,48`、`SourceTemplateFields.spec.ts:24,39,56` |真实route+lateconfirm、SSE断线REST恢复、STOPPING保停止未知、cap仅server支持字段、历史策略未被新默认替换。 |
| 补传/澄清/批次diagnostics |`DocumentSupplementForm.spec.ts:11,28`、Clarification`:10,26`、BatchRecovery`:43,52,77`、SessionDiagnostics`:60,77,93,112` |真实旧操作恢复与dispose；transport check不重发；batch exactversions/commandId；暂停/selection/hidden协调不启动第二poll。 |

以上路径省略时，测试分别位于 `frontend/src/views/`、`frontend/src/components/`、`frontend/src/components/workflow/` 或 `frontend/src/stores/`，名称与相邻生产文件相同。现有 Vue mount 测试是行为来源；应保留纯TS协议测试并新增 React真实组件/route集成，不能把它们全部改stub以获得等价数字。

已有浏览器合同需复用且确认路径实际走React：`e2e/workflow-pointer-contract.spec.ts:19,38,63,84,104`（重复/环/self/reverse/tap/plus/readonly-movable）、`react-workflow-cleanup-entries.spec.ts:75`（另存预览和父岛隔离）、workflow-authoring/canvas-focus/react-workflow-review，以及 Designer `designer-discussion.spec.ts:171,212,253,303,355,379`（历史设计、附件失败、冻结自动策略、制品断言、三皮肤窄屏Mermaid与SPA释放）。Source表单 `source-template-form.spec.ts:13`、Document `document-template-tasks.spec.ts:4`、template-session-diagnostics/batch-recovery/resilience覆盖模板主路径。这里的模拟REST浏览器证明真实DOM/路由/指针行为，不冒称真实Provider/Git副作用执行。

每一页切换还须明确资源首快照：active读/SSE/timeout/RAF/ResizeObserver/Pointercapture/对象URL→真实SPA离开→任何自然释放事件之前，实例资源为零、无late写回、重复3cycles不累积，两实例不互清。已有ReactCanvas自有Pointer与锁定依赖ROcleanup机制继续保留，不重新启用风险原生gesture、不改清理探针过滤规则。StrictMode根重放要证明setup确实多次及每个退休owner被释放；不能以root DOM消失替代资源证明。

## 11. 统一 W0–W7 内的专题执行顺序

| 统一波次 | 本专题具体工作 | 准入/退出条件与不确定项 |
| --- | --- | --- |
| W0 债务/合同 |冻结本报告路由/动作/DTO，补B类基线红证据；明确unknown导航恢复策略；迁移开关与资源账本合同 |未取得产品决定的guard差异不擅自统一；必须区分基线缺口与迁移回归。 |
| W1 基础UI/controller |AntD输入/确认/消息/中文projection/CodeMirror端口；scope+ackcore+三templatecontroller；editor/plan save直接复用；导航注入 |Pinia仅delegate一个owner；controller工厂无自动写；StrictMode及accepted-readback、File、版本CAS的纯TS测试先绿。 |
| W2 列表/系统 |RequirementList、WorkflowLibrary、DesignerHistory；深链及过滤兼容；TaskDesignHistory随W4 Task族实现 |Library仍有copy/archive/unknown，不把列表当零写；冻结历史先修lateisolation后验；任务历史DTO与W4联测。 |
| W3 模板/知识库/PPT |TemplateTasks、Source/Document run及所有subpanels/report decoder；三store移出Vue；共用Markdown/文件picker/知识Evidence |未知操作/补传Files、SSE恢复、batchStop证明与历史V1–V7投影；真实路由dispose后资源首快照。 |
| W4 Task生命周期 |本专题交接task ID、冻附件/规范、批次recovery和diagnostics，与Task controller接轨 |create/start/idempotence不得两个owner；source/documentlinkedTask的失败/决策显示独立；历史task只读保持。 |
| W5 需求/Editor/Designer |先Editor→Requirement子owner与页面→Designer workspace/subscriptions/CAS→讨论/画像/编译与历史自动handoff |按上述行为矩阵准入；现React画布保留；Designer最后迁，所有基线scope/guard未决项必须结清或明确阻塞。 |
| W6 全React根+去Vue |一次切DataRouter、根入口、导航guard；删除VueRouter/Pinia/SFC/ElementPlus与临时bridge/Legacy |所有路由都ready且只有一套history；保留canonicalURL/serverfallback，打包产物不含Vue运行时；ReactFlow及其已核验cleanup补丁不随Vue删除。 |
| W7 干净全量验收 |统一clean安装/锁定依赖机制、typecheck/build/全unit/e2e、三皮肤桌面/窄屏和deep refresh |重新证明最终整页React，而非沿用第一阶段画布截图；真实响应未知/accepted读回、无storage、3cycles首快照；实际Provider调用另需明确授权。 |

这是统一波次的专题落点，不另外创建一套互相矛盾的波次编号。W3/W5可分route族独立交付，但 shared controller/guard接口先冻结；未ready的同族页面不能接管原operation后又fallback到另一个controller。

## 12. 回退、去 Vue 终态与交付判断

阶段内页面回退按“下次安全进入点”选择展示，复用同一 app/session controller；不热卸载busy/pending/File owner。画布偏好继续使用现有 `migration/canvasRuntime.ts:31–44` 的默认React、每实例一次capture；页面回退不能复用画布偏好偷偷切业务owner，也不能以切Legacy让测试通过。

建议切换前检查普通dirty确认、pending/accepted-readback/上传/远端stopunknown；有未知结果先提供原操作retry或read入口，不能把错误清空后导航。后端可持久化的requestId/版本/回执沿已有接口恢复，真实File只能留内存或用户显式重选并核对服务端原身份；storage不可写时禁止宣称跨reload完整恢复。回退不是清pending、生成新key、再次create/start、以abort当stop或改历史冻结数据。

最终W6之后不再保留整页Vue或Legacy作为产品终态。回退是外部整体返回上一份完整已验证构建/commit，并继续读取原DTO/冻结合同；历史checkpoint可能含过渡双栈，但最终构建不携带Vue fallback。过渡开关、VueRouteBridge、canvasRuntimeVue、Vue wrappers/Legacy SFC及Piniadelegate清除前先记录对应React入口/behavior/resource evidence。直接React组合现Canvas，保持其DTO/callback/ref contract，移除 `react/bridge.ts:6–20` 的双框架root桥是入口收口，不重写pointer数学或图业务。

本轮交付是源码审查与规划，没有实施、测试通过声明或运行截图。报告归还后组长可引用路由表、B清单及W落点；实际开发需按唯一文件所有权分配controller/页/共享UI，再用非作者的独立协议与资源验收完成各波次。
