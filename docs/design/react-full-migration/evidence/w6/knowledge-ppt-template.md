# W6 C：Knowledge、PPT、模板与 Designer 旧测试退出

原 C 组续接，启动记录为 `gpt-6.1-sol / xhigh`；当前没有读取实时平台配置的接口。原归属是 47 个旧 spec、250 个测试定义（不是 250 条 `expect`）。本轮没有新增成员、安装依赖、后端执行、付费模型、共享服务、提交或外发。

最终作者聚焦实际执行 **47 文件 / 254 PASS / 0 FAIL / 0 pending / exit 0**。250 个原完整 `fullName` 的多重集合逐文件保留，missing=0；4 个新增定义仅是 PPT 消息恢复的错误 key、text、revision、scope 负控。旧路径保留为合同索引；执行面全部为实际 React 页面/组件与其生产纯 TS owner，不保留 Vue/Pinia 核心或并行业务 store。

## 最终证据及来源

外部原始输出位于 `/workspace/react-full-w6-evidence/`，不提交环境日志或原始归档。

| 证据 | 结果 / SHA-256 |
| --- | --- |
| `C-47-final.json` | 254/254；`ad34bdfb984e28cb37b407a22ffb4b0628f163e65cb537fce649cdc1ec37226d` |
| `C-47-final.log` | 实际进程 exit 0；`0578ba80c3ebb3f889faea42feb737e41e6e1b816e25245df62f365939db7c66` |
| `C-fullname-mapping-final.json` | 47/47、原 250 missing 0、added 4；`5f0ad81d4b9c9ea8e134e36b4c04bbdaf05dedcaf8f4b5295a18a64ed6e4e656` |
| `C-freeze-source-hashes-final.json` | 67 文件：47 spec + 10 测试编排文件 + 10 授权生产文件；`57b96e1aa1aba1429ece4c6930702ebf405a1d52317942128ba2c3474709d1c8` |
| `C-independent-B-root-source-review.json` | 非作者只读源码 manifest；`8eb8dd72e60ad018caa46461d245bd10107dd7a68102b38cd80f3d98e18d2247` |
| `C-deleted-vue-executables.json` | 65 个执行源删除索引；`e094a802fd6aee7fae2122dd329cf6eef098f2787100627a9bba86f9e88ea05f` |
| `C-type-focused-final2.log` | C 范围严格 tsc、noEmit、incremental=false，实际 exit 0；最后初始附件分类改动后的全仓 tsc 由 root 候选 exit 0 覆盖，本组不并发写构建产物 |

聚焦命令从 frontend 运行：

```bash
mapfile -t c_specs < /workspace/react-full-w6-evidence/C-focused-47.args
npx vitest run "${c_specs[@]}" --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w6-evidence/C-47-final.json
```

`C-47-first-all` 的 254 PASS 是初始分类补强前的候选，最终使用 `C-47-final`。Designer 首次可执行 React 批次 `C-designer-first-executed` 为 35 PASS / 21 FAIL，随后 45/11、53/3，修复与夹具纠正均保留原输出。语法加载失败的 0-test 批次、缺 EventSource 接口、错误 CursorPage/枚举、中央 action key 或原 Vue 批次不归为新 React 业务 RED。

## 旧五个 store 的实际生产归属

删除前逐公开方法核对；旧 spec 测实际生产 owner，测试 helper 不复制业务状态机。

| 旧公开方法 / 状态 | 实际生产出口 | 原合同 |
| --- | --- | --- |
| knowledge `conversation/messages/pendingText/error/disconnected/loading/sending/active/nextCursor` | `pages/w3/knowledge/controller.ts` 的 snapshot、active、mutation | 权威消息、单调 usage、当前问题和未知提示 |
| knowledge `load/refresh/more/send/stop` | `createKnowledgeController` 的同名实际命令；恢复用 `retryOriginal/readOriginal` | 原 id/key/body、GET receipt、增量页、stop 直到 REST 证明 idle |
| knowledge `close/reset` | view detach / `retire(true)`、明确创建新 owner 或合法 load scope | close 清自有 stream/timer；旧 scope 不覆新；不自动清未知写后再发 |
| PPT `document/deck/plan/sources/assets/jobs/revisions/messages/agent/generation/capabilities/issues/checkedRevision` 及 loading/busy/error/disconnected/pending/cursors | `pages/w3/ppt/controller.ts` 的 `StudioSnapshot` 与 `studioActive/studioJobsActive/studioEditable` | 同 revision 权威投影、未确认写 BLOCK、历史只读 |
| PPT `load/refresh/close/reset` | `createPptStudioController(id).start/refresh`、detach/retire、合法新实例 | 一份 stream、旧 owner 晚响应拒绝；无已退役 Pinia singleton |
| PPT `generate/confirmRequirements/resume/adjustAndResume/action` | 同 controller 的 generate/confirmRequirements/resume/action | 生成协议与准确 idempotency key/revision；调整沿原 action |
| PPT `operations/savePlan/send/reply/createJob/retryJob/retryPending/stop/check/more` | 同 controller 的实际方法；`retryOriginal` 替换测试词汇 retryPending | 首 send/回答/对象 CAS、保原 POST body；accepted 只读、分页不丢历史 |
| document `submitting/previousRun/start/restore` 与 `documentUploadError` | `pages/w3/templates/catalog/creation.ts` 的 `createDocumentCreationController`、`restore/recover`、`documentUploadError` | 同有序实际 File/SHA/body/requestKey；by-request 查询、校验和 duplicate click |
| source `submitting/create` | 同模块 `createSourceCreationController.start` | 原 lost-response 请求身份与 duplicate click |
| templateTask `catalog/loadCatalog/submitting/start` | `catalog/controller.ts` 的 catalog owner + `createReportCreationController` | 原读取目录；create 已确认 Task 后仅恢复 Start，不重复 create |

`pages/w6-tests/knowledge-ppt-template/ppt-owner.ts` 的 `usePptStore` 只是一组旧测试词汇：每次 load 创建真实 production studio owner，每个读写直接 delegate，afterEach 明确 detach/retire；它不在生产 import graph，不复制 state、API 或流。其余编排文件只负责 RTL/FoundationProvider、DOM query、原生控件事件、ReactRouter 或 DTO。旧 `global/stubs` 参数不执行；disabled 控件遵守 `:disabled`，不通过私有 VM mutation 或 disabled emit 制造可达场景。

## 经实际原断言 RED 批准的最小等价补齐

- KnowledgeActivity：恢复真实 RUNNING 工具 spinner、最新工具中文/30 项顺序和 reduced-motion。PPT Chat 复用同 presenter；仅去重前一条相同用户 text 的重复展示，不改消息 ID、持久顺序或字节。
- PPT start：`controller.ts:151` 初始化 GET 消息后只在 `acceptedMessage` 精确 documentId/key/text/expectedRevision/scope 全匹配时解除原恢复条目，不 POST、不换 key。4 项错身份负控仍 BLOCK。
- PPT busy manual：只允许打开只读实际对象展示；不改变 `studioEditable`、Manual 写 gate、原 pending、自动保存或恢复身份。已验收 `react/ppt` 底层画布、几何、手势未改。
- Designer Activity：同 production owner 持有单调 token baseline/850 ms delta timer、RUNNING 1200 ms read、actor/工具标签；失败保原 fragments。首 GET 失败无 DTO 时单独显示 current alert，不虚构 activity。Discussion awaiting chat answer 抑制忙活动，当前错误保留。
- Designer：原 package-gap 来自实际 `lastErrorCode/detail`；Story AI 工作量范围、只读 profile 技术栈/独立 confidence、previous/current 区分、compiler SESSION_ERROR 恢复、候选状态/计数和 composer rows 恢复。原 profile/version/文件/stream owner 没有新增 writer。
- LoopSpec：实际结构化编辑器新增原 DOCUMENT_STRUCTURE 选型默认 TEXT_EXISTS；afterStages slot 放置 B 的真实只读 ExecutionAcceptancePanel，位置在 stage 后、limits 前。保原数值上限和全部原字段，无 generic JSON textarea 替代。
- Template readonly：RecoveryPanels 依据真实 taskStatus/DTO timestamps 呈现空 rows、恢复/继续、49 分钟 activity；TaskDetail 实际 consumer 的 TemplateProgress、SnapshotBatches owner 换代 reread 由 B 补齐，C 原合同实际复验。

## 初始 multipart 的 400 因果边界

初始 Designer 创建能力在当前 `/designer` historyOnly=true 路由不可达；此处仍保存原 owner/API 合同和真实 React 合同夹具，不新增生产初始入口。

`DesignerAttachmentCommandService.java:44` 先 prepare，但 `:65` 先 sessions.create、`:75` 才 changePrepared；`DesignerAttachmentContext.java:134–137` 的累计 50 MiB 校验可在 session 已创建后抛 400 `ATTACHMENT_SESSION_TOO_LARGE`。因此泛 400、TEXT_REQUIRED/MESSAGE_INVALID/STORE_FAILED/PATH_ESCAPE 不证明无副作用。

只对初始 multipart 实际 operation 使用已证明 prepare/inspect 前拒绝 code：FILE_COUNT_INVALID、DUPLICATE_FILENAME、FILENAME_INVALID、EMPTY、FILE_TOO_LARGE、TYPE_UNSUPPORTED、CONTEXT_TOO_LARGE、PARSE_FAILED、MAGIC_MISMATCH、MACRO_FORBIDDEN、UTF8_REQUIRED（均带 ATTACHMENT_ 前缀）。只有 SETTLED 且 accepted=false 才失效原 initialId；更正 File 在无 session 时仍归 initial。generic 400/SESSION_TOO_LARGE 保 UNKNOWN/BLOCK、原 File 引用/有序 bytes、body/submissionId；显式原身份恢复不会发送后来更正的 File。

分类 override 标记只在 initial multipart 真值启用，`saveDraft(DRAFT_VERSION_CONFLICT)` / `applyProfile(TASK_PROFILE_VERSION_CONFLICT)` 原 fallback 没变。原“更正拒绝文件” fullName 使用实际 prewrite TYPE_UNSUPPORTED；同一定义加入 generic400/SESSION_TOO_LARGE 负控，未降低 File/新身份谓词。`C-designer-prewrite-guard` 实际 RED 为 UNKNOWN 预期却 SETTLED；after 同定义实际 1 PASS。没有执行真实 Java、50 MiB 上传或 Provider。

## 非作者发现闭合

B 的永久 `ordinary/designer-activity-independent.spec.tsx` 以实际 GET exact id、snapshot activity=undefined/error 已有中文为前置，最终 DOM 告警 RED；同字节 after 1 PASS。最早缺 context 的 probe 单独保存为 fixture 错误，不归产品 RED。

B 的永久 `ordinary/designer-initial-attachment-independent.spec.ts` 实际 before 1 RED：exact POST1/原 submissionId/实际 File 均通过后 phase 错为 SETTLED；同字节 after 1 PASS，显式 retry 的第二 POST 保原 body/key/File、draft create1，后续 409 仍 UNKNOWN/BLOCK。两个 after JSON SHA-256 分别为 `b8464d31e57897b2f04f57d067ee25a4b2c0fa7bc729a167988da392315ed8de`、`95e63be8da882cce629b26a9230862ecda511e765a8599dc9d35fae06a28da1b`。这两项不混入 C 作者 254；B 在干净 npm ci 后实际独立执行两项永久负控，2/2 PASS，并只读核对本组 67 个最终源码/测试哈希及 250 个原名称 + 4 个新增定义；没有冒称另行独立执行完整 254 项。最终全量由组长执行。

## C → B / root 当前字节只读交叉审核

本组只读审核非本人 Task/ordinary、根配置、依赖、样式和中央语义，没有修改其 source。root 执行最终全量时不并发补另一套测试；以下是源码/原始 JSON 审核，未冒称本组独立执行 B 全部测试。

- `app/ownership.ts:117` App 一实例创建 `createTaskApplicationOwner`；`stores/taskStore.ts:63` 构造 0 read/POST/SSE。plain getter/setter 只在 active 发布不可变 DTO；W4 详情 command 继续属于已有 Task controller，没有再调用应用 compatibility writer。
- `taskStore.ts:78` 应用 epoch 和 project/runtime/overview/summary 序号守迟到；`:488` dispose 先失效全部读取/清 listener，再 stopWatching。`taskEventSubscription.ts:19` generation 失效并清两 180 ms timer，即便 close 抛错仍尝试状态清理并报告聚合失败，不依赖自然 SSE 事件。
- `sessionController.ts:25` 只接受单调 Token 累计，首次静默、后续 delta850，select 重置 baseline。`sessionLifecycleController.ts:19` keyless UNKNOWN 只原 todos/checkpoints GET→UNCONFIRMED，保持 BLOCK 不盲 POST；确认核原 draftRevision/taskVersion/state/DIRECT 与 message/part。只读 GET 并行等待后用 ticket/context.apply，不从 timer制造成功或停止证明。
- B 原 spec import 真实 TaskDetail/Publication/Session/项目/目录页面。`pages/w6-tests/ordinary/render.tsx:33` 只 RTL/FoundationProvider/DOM 与受控皮肤观察，`global` 参数不执行 Vue stub；`ownership.spec.tsx` 有 no-import-write/late-read/keyless-original/CAS/卸载不 stop 的实际负控。
- root `package.json` 去 Vue、VueRouter、Pinia、Element、IconifyVue、VueUse、Vue tsconfig/tooling 10 direct 家族依赖；React/Vite/tsc 配置保 strict/noUnused/noUnchecked/noEmit，构建输出实际 Rollup modules inventory。
- `scripts/audit-react-only.mjs` 从 executable AST import/export/dynamic-literal/require/import-type、锁依赖/实际 npm tree、实际 dist module inventory 三层 fail closed；测试负控不把 prose 的 Vue 单词当执行。它不是任意动态 eval 或 heap 证明。
- `styles/app.css/tokens.css` 只清 Element 专属 CSS/变量；existing domain styles/主题 token 仍保，未按皮肤硬编码或复制各页颜色。`generate-semantic-registry.mjs` W6 增量拒覆旧定义，同 label 强同 glyph，route 仅填终态 React 所有者，不宣称历史写重生。

在以上有限静态范围未发现新的已确认阻塞。root 候选全仓 tsc exit0、零 Vue 审计 768 source/426 packages/3940 build modules 是 root 独立门禁记录，本组未运行该命令。真实 HMR、OS 浏览器事件、全应用 GC/堆 retaining path、后端/Provider 未由此证明；最终干净 ci、构建/实际浏览器由 root 集中验收。

## 执行源归档与删除

原归档 `C-original-source/manifest.json` 共 119 文件保持旧字节；删除索引 65 个：55 SFC + 5 Pinia store + 5 Vue composable/hook。每个删除前 SHA 与归档一致；47 个原 spec 路径保留、7 个纯 CSS/TS fixture/util 保留。root 持有的最后旧 views/未引用 CSS 与其他组 SFC 不记为 C 删除。

原 canvas fallback 相关 6 个完整标题是历史索引：终态唯一 React，旧偏好不能复活 Vue；原几何、事件、revision、恢复谓词保留。Automations 原消费者为 W3 合法 history read/export，不恢复旧 scheduler 写入。无 session Designer quick-brief 的旧初始生产入口已 redirect；原 draft/owner 能力保留为明确 test-only 合同，报告不将其测试通过当生产 UI 可达。历史窄屏断言保留为原合同索引，不新增本轮窄屏验收。

## 47 文件实际映射

| 原路径 | 原 / 当前定义 | 实际生产消费 |
| --- | ---: | --- |
| `frontend/src/components/DesignerCurrentActivity.spec.ts` | 2 / 2 | W5 Discussion + actual Designer controller activity |
| `frontend/src/components/DocumentClarificationForm.spec.ts` | 2 / 2 | W3 DocumentPanels / createClarificationOwner |
| `frontend/src/components/DocumentFilePicker.spec.ts` | 3 / 3 | W3 TemplateTasksPage / document creation File controller |
| `frontend/src/components/DocumentReportsPanel.spec.ts` | 2 / 2 | W3 ContentPanels document reports owner |
| `frontend/src/components/DocumentRequirementsPanel.spec.ts` | 2 / 2 | W3 ContentPanels requirement owner |
| `frontend/src/components/DocumentSourcesPanel.spec.ts` | 1 / 1 | W3 ContentPanels source owner |
| `frontend/src/components/DocumentSupplementForm.spec.ts` | 2 / 2 | W3 DocumentPanels / supplement owner |
| `frontend/src/components/LoopSpecEditor.spec.ts` | 9 / 9 | W5 actual structured LoopSpecEditor + W4 ExecutionAcceptancePanel |
| `frontend/src/components/PackageGapNotice.spec.ts` | 2 / 2 | W5 DesignerPage / Discussion server error projection |
| `frontend/src/components/SnapshotReviewBatchesPanel.spec.ts` | 1 / 1 | W4 SnapshotBatchesPanel actual owner |
| `frontend/src/components/SnapshotReviewPartialReport.spec.ts` | 1 / 1 | W4 TemplateProgress + W3 RecoveryPanels |
| `frontend/src/components/SourceArtifactsPanel.spec.ts` | 1 / 1 | W3 ContentPanels source artifact owner |
| `frontend/src/components/SourceCoveragePanel.spec.ts` | 2 / 2 | W3 ContentPanels coverage projection |
| `frontend/src/components/SourceTemplateFields.spec.ts` | 4 / 4 | W3 TemplateTasksPage / source creation owner |
| `frontend/src/components/StoryBindingSetup.spec.ts` | 3 / 3 | W5 DesignerPage / actual Designer controller |
| `frontend/src/components/TaskProfileRouterDialog.spec.ts` | 4 / 4 | W5 DesignerPage / task-profile actions |
| `frontend/src/components/TemplateBatchRecoveryPanel.spec.ts` | 6 / 6 | W3 RecoveryPanels + W4 TemplateProgress |
| `frontend/src/components/TemplateReportsPanel.spec.ts` | 7 / 7 | W4 Task evidence owner + W3 reports projection |
| `frontend/src/components/TemplateSessionDiagnosticsPanel.spec.ts` | 6 / 6 | W3 RecoveryPanels diagnostic projection |
| `frontend/src/components/TemplateTaskProgressPanel.spec.ts` | 7 / 7 | W4 TemplateProgress / authoritative Task projection |
| `frontend/src/stores/documentTemplateStore.spec.ts` | 3 / 3 | W3 catalog createDocumentCreationController |
| `frontend/src/stores/knowledgeStore.spec.ts` | 12 / 12 | W3 createKnowledgeController |
| `frontend/src/stores/pptStore.spec.ts` | 15 / 19 | W3 createPptStudioController |
| `frontend/src/stores/sourceTemplateStore.spec.ts` | 2 / 2 | W3 catalog createSourceCreationController |
| `frontend/src/stores/templateTaskStore.spec.ts` | 2 / 2 | W3 catalog createReportCreationController |
| `frontend/src/views/DesignerHistoryView.spec.ts` | 5 / 5 | W2 DesignerHistoryPage |
| `frontend/src/views/DesignerView.spec.ts` | 56 / 56 | W5 DesignerPage / createDesignerController / structured editor |
| `frontend/src/views/DocumentTemplateView.spec.ts` | 3 / 3 | W3 DocumentRunPage / React application router harness |
| `frontend/src/views/KnowledgeView.spec.ts` | 10 / 10 | W3 KnowledgePage / createKnowledgeController |
| `frontend/src/views/PptListView.spec.ts` | 1 / 1 | W2 PptListPage / createPptCreation |
| `frontend/src/views/PptStudioRecovery.spec.tsx` | 3 / 3 | W3 PptStudioPage / studio controller |
| `frontend/src/views/SourceTemplateView.spec.ts` | 3 / 3 | W3 SourceRunPage / React application router harness |
| `frontend/src/views/TemplateTasksView.spec.ts` | 5 / 5 | W3 TemplateTasksPage / catalog creation owners |
| `frontend/src/components/knowledge/KnowledgeEvidence.spec.ts` | 1 / 1 | W3 Evidence actual Markdown/diagram renderer |
| `frontend/src/components/knowledge/KnowledgeSourcesPanel.spec.ts` | 5 / 5 | W3 SourcesPanel / knowledge source owner |
| `frontend/src/components/knowledge/KnowledgeThinking.spec.ts` | 18 / 18 | W3 KnowledgeActivity |
| `frontend/src/components/ppt/PptArtifactDownload.spec.ts` | 1 / 1 | W3 Download actual artifact action |
| `frontend/src/components/ppt/PptCanvas.spec.ts` | 6 / 6 | react/ppt/PptCanvasView |
| `frontend/src/components/ppt/PptCanvasBridge.spec.tsx` | 5 / 5 | react/ppt/PptCanvasView + PptSlideNavigatorView |
| `frontend/src/components/ppt/PptCanvasRecovery.spec.tsx` | 1 / 1 | react/ppt/PptCanvasView + actual studio pending owner |
| `frontend/src/components/ppt/PptChat.spec.ts` | 10 / 10 | W3 Chat / studio controller / KnowledgeActivity |
| `frontend/src/components/ppt/PptGenerationStatus.spec.ts` | 3 / 3 | W3 PptStudioPage generation/status projection |
| `frontend/src/components/ppt/PptPlanEditor.spec.ts` | 3 / 3 | W3 Plan / studio draft controller |
| `frontend/src/components/ppt/PptProjectPicker.spec.ts` | 2 / 2 | W2 PptListPage / PPT creation owner |
| `frontend/src/components/ppt/PptProjectSources.spec.ts` | 2 / 2 | W3 PptStudioPage source selector |
| `frontend/src/components/ppt/PptProperties.spec.ts` | 1 / 1 | W3 Properties / studio object draft owner |
| `frontend/src/components/ppt/usePptCreation.spec.ts` | 5 / 5 | W2 createPptCreation exact create/send split |

## 原 250 完整名称逐项保持

下列完整名称逐文件与原冻结 JSON 多重集合比较，全部实际 PASS。每项执行目标与上表对应；涉及被明确退役入口的技术标题遵守上文合同差异，不将 literal 标题当 Vue 可执行或初始路由仍存在。

### `frontend/src/components/DesignerCurrentActivity.spec.ts`

- DesignerCurrentActivity renders only the newest activity as Markdown inside the current role card — PASS
- DesignerCurrentActivity keeps the single latest fragment visible during a reconnectable refresh failure — PASS

### `frontend/src/components/DocumentClarificationForm.spec.ts`

- keeps the same identity for an unknown reply and prevents duplicate submission — PASS
- does not apply a late response to a different requirement — PASS

### `frontend/src/components/DocumentFilePicker.spec.ts`

- DocumentFilePicker shows the full selected filenames and removes only the requested file — PASS
- DocumentFilePicker keeps the current list when selection is cancelled and accepts a replacement selection — PASS
- DocumentFilePicker disables choosing and removing files while a submission is in progress — PASS

### `frontend/src/components/DocumentReportsPanel.spec.ts`

- loads report bodies on demand and keeps incomplete reports distinct from a downloadable completed bundle — PASS
- clears previous scope while the next report directory is still loading — PASS

### `frontend/src/components/DocumentRequirementsPanel.spec.ts`

- reads only summaries until expanded, then verifies original file identity and exposes an actionable business question — PASS
- does not show old requirement text after the frozen revision changes — PASS

### `frontend/src/components/DocumentSourcesPanel.spec.ts`

- loads chapter metadata and frozen body only when requested and discards a stale body after source revision changes — PASS

### `frontend/src/components/DocumentSupplementForm.spec.ts`

- retries a lost response using the same upload identity and suppresses duplicate clicks — PASS
- shows an unavailable server action and rejects a late response after navigation — PASS

### `frontend/src/components/LoopSpecEditor.spec.ts`

- LoopSpecEditor shows JSON as Chinese structured fields with adaptive textareas — PASS
- LoopSpecEditor orders the review cards by the user workflow — PASS
- LoopSpecEditor edits the fresh-session policy and next-attempt template without losing the threshold — PASS
- LoopSpecEditor does not add path rules or a Git diff verifier to a new stage by default — PASS
- LoopSpecEditor offers every native verifier type without accepting unknown free text — PASS
- LoopSpecEditor edits v2 criteria mappings and managed-runtime fields — PASS
- LoopSpecEditor shows and edits artifact assertions while retaining the frozen execution identity — PASS
- LoopSpecEditor clears incompatible command fields when explicitly changing to a document verifier — PASS
- LoopSpecEditor follows external JSON updates without losing the structured view — PASS

### `frontend/src/components/PackageGapNotice.spec.ts`

- PackageGapNotice keeps an unconfirmed claim distinct from a proven business decision — PASS
- PackageGapNotice does not manufacture a classification for legacy or unknown codes — PASS

### `frontend/src/components/SnapshotReviewBatchesPanel.spec.ts`

- loads summaries on demand and discards a response from a previous task — PASS

### `frontend/src/components/SnapshotReviewPartialReport.spec.ts`

- snapshot partial report loads only on demand and discards a response after switching tasks — PASS

### `frontend/src/components/SourceArtifactsPanel.spec.ts`

- renders document read failures with production component registration and clears a recovered error — PASS

### `frontend/src/components/SourceCoveragePanel.spec.ts`

- pages metadata and only loads the requested evidence body — PASS
- shows coverage read failure and clears it after a successful retry — PASS

### `frontend/src/components/SourceTemplateFields.spec.ts`

- requires a current preflight and sends only capability-supported output fields — PASS
- ignores late preflight and folder selection after switching project and template — PASS
- shows configuration failures and preserves input when the picker is cancelled — PASS
- renders a picker failure without the full Element Plus plugin and keeps the typed path — PASS

### `frontend/src/components/StoryBindingSetup.spec.ts`

- StoryBindingSetup disables while detecting or absent and allows explicit recheck after installation — PASS
- StoryBindingSetup preserves identifier leading zeros and only shows fields after enabling — PASS
- StoryBindingSetup rejects stale project results, refreshes for runtime generation changes, and exposes failures — PASS

### `frontend/src/components/TaskProfileRouterDialog.spec.ts`

- TaskProfileRouterDialog locks the running dialog and displays real activity, elapsed time, and provider tokens without a timeout limit — PASS
- TaskProfileRouterDialog opens manual settings after the server confirms Router cancellation — PASS
- TaskProfileRouterDialog shows unavailable confidence separately from Java and exposes all decisions — PASS
- TaskProfileRouterDialog shows a comprehensible warning for a failed Router run — PASS

### `frontend/src/components/TemplateBatchRecoveryPanel.spec.ts`

- batch recovery records failure while independent work continues and reveals selection only on server readiness — PASS
- batch recovery submits the selected failures together once and clears them after acceptance — PASS
- batch recovery retains the choice after a conflict and never automatically resends an unknown request — PASS
- batch recovery ignores a late page from the previous task and resets selection when switching task — PASS
- batch recovery shows paused blockers and resumes only with a server version and capability — PASS
- batch recovery does not restart polling when a retry response arrives after leaving the page — PASS

### `frontend/src/components/TemplateReportsPanel.spec.ts`

- template report evidence shows generated reports before selection and never claims AI approval for a deterministic template — PASS
- template report evidence distinguishes metadata loading and failure from a report that has not been generated — PASS
- template report evidence loads only the selected task-owned body and distinguishes superseded versions — PASS
- template report evidence opens a contributor link in the same report version without leaving the task — PASS
- template report evidence resolves encoded Chinese detail and parent links within the exact bundle — PASS
- template report evidence downloads the selected bundle with its named folder and keeps body reads lazy — PASS
- template report evidence does not navigate outside the bundle for unresolved or escaping local links — PASS

### `frontend/src/components/TemplateSessionDiagnosticsPanel.spec.ts`

- TemplateSessionDiagnosticsPanel requests server filters and cursor pages, preserving exact session selection — PASS
- TemplateSessionDiagnosticsPanel loads explicit details and copies only allowlisted diagnostic fields — PASS
- TemplateSessionDiagnosticsPanel posts exact batch version and preserves command identity after uncertain delivery — PASS
- TemplateSessionDiagnosticsPanel requires explicit confirmation for stopping and prevents duplicate requests while pending — PASS
- TemplateSessionDiagnosticsPanel discards stale filter and task responses and bounds polling to active tasks — PASS
- TemplateSessionDiagnosticsPanel separates transport checks from automatic retries and sends the exact batch version — PASS

### `frontend/src/components/TemplateTaskProgressPanel.spec.ts`

- Template progress shows lightweight steps and conditional finding reviews without recursive planning — PASS
- Template progress shows remaining work including analysis batches that have no session yet — PASS
- Template progress keeps report review separate from finished analysis and shows the repair round — PASS
- Template progress shows final cleanup after deterministic validation without claiming a review — PASS
- Template progress does not invent totals before evidence has been collected — PASS
- Template progress mounts server steps when they arrive and tears down the React root when they disappear — PASS
- Template progress Vue rollback preserves both the template step sequence and nested stage details — PASS

### `frontend/src/stores/documentTemplateStore.spec.ts`

- document template upload identity reuses a lost-response request after a page reload and binds it to actual file bytes — PASS
- document template upload identity rejects unsupported and oversized batches before any network call — PASS
- document template upload identity blocks a duplicate click while hashing and uploading — PASS

### `frontend/src/stores/knowledgeStore.spec.ts`

- knowledge authoritative chat keeps exact message identity across a lost response and page reload — PASS
- knowledge authoritative chat clears an acknowledged lost response on reload so the next question can be sent — PASS
- knowledge authoritative chat restores an unacknowledged draft after reload without automatically dispatching it — PASS
- knowledge authoritative chat rejects a changed question while the previous delivery is unknown — PASS
- knowledge authoritative chat never lets late reads overwrite a different conversation — PASS
- knowledge authoritative chat keeps stop pending until REST reports an idle conversation — PASS
- knowledge authoritative chat does not restore an old conversation when its stop response arrives after navigation — PASS
- knowledge authoritative chat does not turn an SSE disconnect into a model failure — PASS
- knowledge authoritative chat does not download loaded historical pages on a live refresh — PASS
- knowledge authoritative chat updates the last turn, catches up missing pages, and preserves older messages and token totals — PASS
- knowledge authoritative chat coalesces event bursts and discards a pending timer when leaving the conversation — PASS
- knowledge authoritative chat ignores late incremental pages after a conversation switch — PASS

### `frontend/src/stores/pptStore.spec.ts`

- PPT authoritative workspace retains the explicit confirmation and question version when retrying an uncertain reply — PASS
- PPT authoritative workspace retries a lost request with its original scope, text, revision and identity — PASS
- PPT authoritative workspace sends the edit baseline rather than replacing it with a newer visible revision — PASS
- PPT authoritative workspace does not let an old workspace response replace a newer route — PASS
- PPT authoritative workspace keeps stopping blocked until the server reports a terminal state — PASS
- PPT authoritative workspace clears layout results once a different revision is loaded — PASS
- PPT authoritative workspace waits for a queued SSE refresh before freezing a follow-up preview revision — PASS
- PPT authoritative workspace replays an uncertain generation request using its frozen revision and key — PASS
- PPT authoritative workspace replays the explicit requirements confirmation with the same revision and idempotency key — PASS
- PPT authoritative workspace keeps automatic production active between separate assistant runs and resumes explicitly — PASS
- PPT authoritative workspace refreshes activity without downloading an unchanged deck and reloads on revision changes — PASS
- PPT authoritative workspace freezes adjusted requirements in the retry payload when the response is lost — PASS
- PPT authoritative workspace shows recovery immediately for a restored request and blocks editing until its explicit retry — PASS
- PPT authoritative workspace retains an accepted operation with a missing receipt while reading a newer revision and retrying the same document — PASS
- PPT authoritative workspace clears the restored retry hint when an authoritative message proves its original request was accepted — PASS

新增实际身份负控：

- PPT restored original message read proof keeps UNKNOWN when the authoritative row has a different key — PASS
- PPT restored original message read proof keeps UNKNOWN when the authoritative row has a different text — PASS
- PPT restored original message read proof keeps UNKNOWN when the authoritative row has a different revision — PASS
- PPT restored original message read proof keeps UNKNOWN when the authoritative row has a different scope — PASS

### `frontend/src/stores/sourceTemplateStore.spec.ts`

- reuses the same creation identity after a lost response and reload — PASS
- prevents duplicate requests while creation is pending — PASS

### `frontend/src/stores/templateTaskStore.spec.ts`

- template task creation retries the same confirmed task when Start acknowledgement is lost — PASS
- template task creation reuses the idempotency key after lost confirmation and creates a new key for another run — PASS

### `frontend/src/views/DesignerHistoryView.spec.ts`

- DesignerHistoryView filters by project and status and sorts matching designs by time — PASS
- DesignerHistoryView opens continue and edit modes and archives without losing the server record — PASS
- DesignerHistoryView shows confirmed task designs as read-only history without continue, edit, or archive actions — PASS
- DesignerHistoryView keeps a stopped and archived design as a read-only cancelled record — PASS
- DesignerHistoryView shows only retry-stop while the server reports STOPPING — PASS

### `frontend/src/views/DesignerView.spec.ts`

- Designer draft composer shows a reopened design generation while preserving its stage after refresh — PASS
- Designer draft composer refreshes accounting failures while idle and warns once across replay and remount — PASS
- Designer draft composer keeps polling a reviewing session until profile rerouting finishes and then enables single-package design — PASS
- Designer draft composer invalidates an in-flight poll when the Designer view unmounts — PASS
- Designer draft composer cancels the active Router through the server and refreshes into manual selection — PASS
- Designer draft composer shows changed profile choices and confirms the new recommendation explicitly — PASS
- Designer draft composer refreshes an authoritative profile conflict without showing a red error — PASS
- Designer draft composer shows task profile summaries and override options in Chinese while preserving enum values — PASS
- Designer draft composer asks for a component only when a multi-stack software task is ambiguous — PASS
- Designer draft composer defaults software design to one package and only enables decomposition through the explicit switch — PASS
- Designer draft composer renders the authoritative requirement snapshot separately and excludes its audit message from system history — PASS
- Designer draft composer labels a historical AI requirement snapshot without presenting it as a server assembly — PASS
- Designer draft composer hides the package approval rail in direct mode and exposes only the explicit large-task recovery — PASS
- Designer draft composer shows server-owned MCP candidate facts without restoring the package rail in direct mode — PASS
- Designer draft composer shows the persisted Markdown fallback reason in the direct-mode candidate summary — PASS
- Designer draft composer keeps the new-design page focused and restores a history session from an explicit route — PASS
- Designer draft composer keeps the recovery pointer when the backend is temporarily unavailable after restart — PASS
- Designer draft composer does not auto-open an archived design from a stale browser recovery pointer — PASS
- Designer draft composer starts a structured brief from a quick template and persists it locally — PASS
- Designer draft composer asks before a quick template overwrites the current draft — PASS
- Designer draft composer restores the initial goal and submits it as both the session message and LoopSpec goal — PASS
- Designer draft composer replays an uncertain initial attachment submission with its original draft and retains later edits — PASS
- Designer draft composer allows correcting files rejected before the initial session was created — PASS
- Designer draft composer shows staged files in a standalone context card and hides it after the last file is removed — PASS
- Designer draft composer requires risk confirmation before creating an auto-mode design — PASS
- Designer draft composer shows that auto mode will adopt a low-confidence task profile recommendation without manual override — PASS
- Designer draft composer keeps auto mode off when the creation warning is cancelled — PASS
- Designer draft composer keeps an unsent follow-up after a request failure and clears it only after persistence succeeds — PASS
- Designer draft composer keeps follow-up text and staged files after an atomic context-turn failure — PASS
- Designer draft composer keeps the chat answer composer available in auto mode when native question is unsupported — PASS
- Designer draft composer keeps the reply composer immediately after the naturally growing message history — PASS
- Designer draft composer places the current role activity inside the message list only while work is running — PASS
- Designer draft composer uses the inline current-role activity for every actor and never renders raw SSE role output — PASS
- Designer draft composer renders pending Designer questions and submits their answers before continuing — PASS
- Designer draft composer places each discussion round immediately before its matching design snapshot — PASS
- Designer draft composer keeps requirement discussion anchored before its hidden server snapshot as later messages arrive — PASS
- Designer draft composer keeps package feedback scoped and requires explicit acceptance before continuing — PASS
- Designer draft composer shows acceptance intent coverage without exposing internal fact or capability ids — PASS
- Designer draft composer labels server direct compilation and does not mount remote activity polling — PASS
- Designer draft composer replaces the right-side LoopSpec when the completed Designer session returns its bound draft — PASS
- Designer draft composer hides informational handoff notices but keeps pending runtime errors visible — PASS
- Designer draft composer collects consecutive system notices into one disclosure even when their scope metadata changes — PASS
- Designer draft composer restores distinct role cards, hides compiler JSON, and exposes both recovery actions — PASS
- Designer draft composer does not expose compiler recovery actions when requirement questioning failed before compilation — PASS
- Designer draft composer restores the Decomposer card, package rail, retry counters, and waiting-input boundary — PASS
- Designer draft composer blocks unchanged recompilation and opens targeted package feedback for mutation ownership gaps — PASS
- Designer draft composer clears the restored workspace and local message drafts when starting over — PASS
- Designer draft composer loads the confirmed Task into the store and opens its detail even when worktree preparation failed — PASS
- Designer draft composer renders the coverage matrix and blocks save when a v2 criterion is uncovered — PASS
- Designer draft composer keeps the editing baseline across refreshed snapshots and preserves conflicts until explicit reload — PASS
- Designer draft composer refreshes confirmation eligibility after save and displays the server blocker without creating a task — PASS
- Designer draft composer shows a restored invalid design blocker and refreshes eligibility after a successful repair save — PASS
- Designer draft composer keeps creation blocked when the eligibility refresh fails after a persisted save — PASS
- Designer draft composer confirms a DIRECT_ARTIFACT document from FINAL_REVIEW using server eligibility without an artifact plan — PASS
- Designer draft composer confirms a PACKAGED_ARTIFACT document from FINAL_REVIEW using server eligibility without an artifact plan — PASS
- Designer draft composer reopens an already confirmed draft idempotently without trying to modify the immutable LoopSpec — PASS

### `frontend/src/views/DocumentTemplateView.spec.ts`

- refreshes the authoritative overview after SSE disconnect and never implies tests ran — PASS
- discards a late old overview on navigation and shows independent development result disposition — PASS
- shows original source identity and defers the result matrix for direct document runs — PASS

### `frontend/src/views/KnowledgeView.spec.ts`

- 知识库真实设置接口与模型选择 switches empty-turn feedback to embedded thinking and answer content as server messages arrive — PASS
- 知识库真实设置接口与模型选择 combines the real separate provider/model fields and sends the exact catalog id — PASS
- 知识库真实设置接口与模型选择 refreshes configuration and catalog without clearing the question or creating a conversation — PASS
- 知识库真实设置接口与模型选择 keeps an explicitly selected provider when reloading the system default — PASS
- 知识库真实设置接口与模型选择 does not guess a provider from a bare name shared by several providers — PASS
- 知识库真实设置接口与模型选择 keeps the default usable when discovery fails and retries only inside the picker — PASS
- 知识库真实设置接口与模型选择 shows the welcome and accepts typing before any configuration response — PASS
- 知识库真实设置接口与模型选择 sends with the global default while the catalog remains pending and deduplicates picker loading — PASS
- 知识库真实设置接口与模型选择 keeps the frozen model of an existing conversation when settings are reloaded — PASS
- 知识库真实设置接口与模型选择 opens history before global settings return and never loads a model catalog for it — PASS

### `frontend/src/views/PptListView.spec.ts`

- PPT first message creates the work and starts discussion without creating a generation authorization — PASS

### `frontend/src/views/PptStudioRecovery.spec.tsx`

- PPT workspace recovery guards with the actual React canvas blocks busy and volatile pending navigation without a property draft until the original request is recovered — PASS
- PPT workspace recovery guards with the actual React canvas exposes a restored pending retry immediately and pauses restored property autosave — PASS
- PPT workspace recovery guards with the actual React canvas keeps the original confirmation semantics for an ordinary unsaved property draft — PASS

### `frontend/src/views/SourceTemplateView.spec.ts`

- waits for formal start and reuses a lost-response command identity — PASS
- keeps unknown stop blocked and shows the linked execution disposition independently — PASS
- ignores old overview responses after changing the source run — PASS

### `frontend/src/views/TemplateTasksView.spec.ts`

- 需求开发转入默认流程，保持入口项目且不调用旧创建协议 — PASS
- shows branch selection without dates for review and rejects an unsupported file — PASS
- defaults to date increment and sends no dates in full review — PASS
- shows the authentication cause and lets an explicit local branch start review — PASS
- uses server source capabilities and creates a pending source run after preview — PASS

### `frontend/src/components/knowledge/KnowledgeEvidence.spec.ts`

- 保存的资料概览 shows captured metadata and incomplete coverage without a text body — PASS

### `frontend/src/components/knowledge/KnowledgeSourcesPanel.spec.ts`

- uses the unified endpoint for selected files and databases and preserves filters on continuation — PASS
- reads a file hit using its returned version and original line — PASS
- preserves the document text offset when opening a cross-section match — PASS
- opens database metadata hits without executing a business-data query — PASS
- discards late search pages after editing query or changing project — PASS

### `frontend/src/components/knowledge/KnowledgeThinking.spec.ts`

- 知识问答独立过程面板 shows waiting feedback for an empty PREPARED turn — PASS
- 知识问答独立过程面板 shows waiting feedback for an empty CREATING turn — PASS
- 知识问答独立过程面板 shows waiting feedback for an empty SENDING turn — PASS
- 知识问答独立过程面板 shows waiting feedback for an empty RUNNING turn — PASS
- 知识问答独立过程面板 replaces waiting feedback with real output or a running tool and resumes after tools finish — PASS
- 知识问答独立过程面板 does not animate an empty STOPPING turn — PASS
- 知识问答独立过程面板 does not animate an empty STOP_UNKNOWN turn — PASS
- 知识问答独立过程面板 does not animate an empty CREATE_UNKNOWN turn — PASS
- 知识问答独立过程面板 does not animate an empty SEND_UNKNOWN turn — PASS
- 知识问答独立过程面板 does not animate an empty COMPLETED turn — PASS
- 知识问答独立过程面板 does not animate an empty STOPPED turn — PASS
- 知识问答独立过程面板 does not animate an empty FAILED turn — PASS
- 知识问答独立过程面板 hides waiting feedback during a PENDING question and resumes after its reply — PASS
- 知识问答独立过程面板 hides waiting feedback during a PREPARED question and resumes after its reply — PASS
- 知识问答独立过程面板 hides waiting feedback during a SENDING question and resumes after its reply — PASS
- 知识问答独立过程面板 hides waiting feedback during a UNKNOWN question and resumes after its reply — PASS
- 知识问答独立过程面板 defaults closed and keeps independent user choices while updating only latest previews — PASS
- 知识问答独立过程面板 does not show an empty thinking placeholder when only tools have output — PASS

### `frontend/src/components/ppt/PptArtifactDownload.spec.ts`

- PPT artifact download keeps the existing artifact available when download fails and retries on request — PASS

### `frontend/src/components/ppt/PptCanvas.spec.ts`

- PPT legacy object manipulation translates scaled preview dragging to point geometry and retains its revision — PASS
- PPT legacy object manipulation supports keyboard movement but does not modify locked pages — PASS
- PPT legacy object manipulation identifies stale rendered output without claiming it shows the current draft — PASS
- PPT legacy object manipulation resizes using Alt and arrow keys while keeping the point geometry and revision — PASS
- PPT legacy object manipulation cancels an in-flight drag with Escape without committing and allows readonly deselection — PASS
- PPT legacy object manipulation zooms the viewport without changing objects or the presentation theme — PASS

### `frontend/src/components/ppt/PptCanvasBridge.spec.tsx`

- PPT Vue to React bridge mounts the React canvas and forwards events and changed props through the existing Vue contract — PASS
- PPT Vue to React bridge keeps a Vue fallback with the same event contract — PASS
- PPT Vue to React bridge reflects an in-place Vue object update through a plain React DTO snapshot — PASS
- PPT Vue to React bridge bridges revision-matched thumbnail previews and selection without creating a new request path — PASS
- PPT Vue to React bridge uses the Vue thumbnail fallback when the route selects Vue — PASS

### `frontend/src/components/ppt/PptCanvasRecovery.spec.tsx`

- PPT canvas recovery through the authoritative store retains an unknown keyboard operation without a property draft across another document and Vue re-entry — PASS

### `frontend/src/components/ppt/PptChat.spec.ts`

- PPT concise conversation does not show the generic completion detail beside a completed discussion reply — PASS
- PPT concise conversation keeps a specific completion check failure visible — PASS
- PPT concise conversation keeps the composer open for freeform discussion and confirms the persisted transcript on demand — PASS
- PPT concise conversation requires an explicit confirmation card before starting design and keeps clarification replies separate — PASS
- PPT concise conversation restores a pending requirements confirmation after remount without automatically accepting it — PASS
- PPT concise conversation shares knowledge waiting, thinking and tool disclosure behavior across streaming updates and stop — PASS
- PPT concise conversation separates embedded thinking from answer and suppresses waiting while a question is pending — PASS
- PPT concise conversation collapses long replies while keeping failures and pending questions visible — PASS
- PPT concise conversation follows new replies at the bottom but preserves an intentional history scroll — PASS
- PPT concise conversation explicitly adjusts a stopped authorization without sending a manual request — PASS

### `frontend/src/components/ppt/PptGenerationStatus.spec.ts`

- PPT requirements progress shows discussion and confirmation before design and follows the server confirmation state — PASS
- PPT requirements progress preserves a stopped workflow and resume action while requirements are unconfirmed — PASS
- PPT requirements progress describes the pre-generation freeform discussion and the explicit execution action — PASS

### `frontend/src/components/ppt/PptPlanEditor.spec.ts`

- PPT plan editing loads confirmed requirements and preserves unsaved input across a conflicting revision — PASS
- PPT plan editing autosaves after editing pauses, preserves unknown fields, and never confirms a phase — PASS
- PPT plan editing restores an unsaved edit after remount without overwriting a newer server plan — PASS

### `frontend/src/components/ppt/PptProjectPicker.spec.ts`

- PPT project selection loads choices on demand, follows the server cursor and selects only the requested project — PASS
- PPT project selection keeps restored association visible and allows unassociated work when catalog loading fails — PASS

### `frontend/src/components/ppt/PptProjectSources.spec.ts`

- PPT frozen project source summary shows usable sources and individual source failures without hiding the rest of the material panel — PASS
- PPT frozen project source summary ignores an earlier document response and recovers a failed source read explicitly — PASS

### `frontend/src/components/ppt/PptProperties.spec.ts`

- PPT property autosave debounces object edits and pauses when their baseline is no longer current — PASS

### `frontend/src/components/ppt/usePptCreation.spec.ts`

- PPT initial request recovery retains the exact generation identity across reload even when the server revision advances — PASS
- PPT initial request recovery allows an independent presentation without any project lookup or association — PASS
- PPT initial request recovery requires original file reselection after reload and replays an uncertain upload with its original identity — PASS
- PPT concise naming uses an explicitly named topic without shortening the actual requirement — PASS
- PPT concise naming falls back to the first sentence and limits the title to 24 characters — PASS
