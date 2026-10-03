# W5 Workflow Editor 与共享节点报告迁移

作者：沿用既有 A `/root/react_flow_workflow` 任务；原启动记录显式为 `gpt-6.1-sol / xhigh`。当前工具不能实时读取平台配置，本记录不把自述当实时平台字段。基线 `5c181fc39e26249650df851fe9a7c8a68cab519d`。本模块只修改 `frontend/src/pages/w5/workflow/**` 与本报告；路由/桥/中央语义由组长集成，Requirement 与 NodeRun 命令和读取由 B 持有。

## 实际入口及状态所有权

`index.tsx:1` 导出 `WorkflowEditorPage`，对应 `/workflows/new` 和 `/workflows/:id`。`WorkflowEditorPage.tsx:17` 真实 React 页面使用 W2PageProps 的唯一导航口、FoundationProvider 下的 PageChrome、公共 SkinControl 和非模态 UiContextPanel。没有 Vue 内容、旧 Pinia writer 或第二个 history。h1 是当前流程名称，默认主区只有流程工具栏和宽画布；节点、边、公共声明、预设、工具、节点检索按选择披露，关键读取错误、检查诊断、冲突、SENDING/UNKNOWN/ACCEPTED_READBACK/PARTIAL_REJECTION 独立常显。

`editorController.ts:24` 是唯一草稿/历史/保存 owner；共享 receipt/scope/lease 来自 W1 与 W4 成熟原语。视图 detach 只释放读取 lease 和 beforeunload，真正路由/root 退出由 retained lifecycle retire；StrictMode 不以普通 effect cleanup 退休业务身份。`canLeave:33` 优先阻断未结清保存，再判普通 dirty revision 确认。变皮肤、开关 context、定位/预览不创建请求键，不清草稿、不重发写入。恢复仅在同一保留 owner 内可用；没有实现 request/body 的跨刷新持久化，不承诺刷新后恢复。

画布为 `WorkflowEditorPage.tsx` 直接挂载 `WorkflowCanvasView`（`@/react/workflow/WorkflowCanvasReact`），公开 handle 的 reveal/focus 与 DTO/callback 连接。保留原已验收的 Pointer 手势、端口拖线/点按、反向连线、105px 首帧、zoom/pan、readonly 与 movable；未修改任何该画布源。专业参数不是通用 JSON 编辑器。

## 保存与恢复协议

旧纯 TS `components/workflow/save.ts:18,24,40`、`graph.ts:6,15,39,44`、`reviewSource.ts:4`、`presets.ts:3,7` 原样复用，不 import Vue。保留 `prepareSave/prepareCopy` 的原 draft、graphKey/layoutKey、expectedVersion/expectedRevision、sourceRevision。新 owner 将 `saveDraft` 同一请求序列拆为各自的真实 receipt owner，避免 final GET 失败被误认为写失败：

| 路径 | 原协议 | 新实现与恢复边界 |
| --- | --- | --- |
| 新建 | create POST 原 graph/layout 与 graphKey | `makeGraph:99`。UNKNOWN 显式同 body/key 重试；收到回执后只 GET 原 id，禁止第二 POST。 |
| 定义修改 | revise PUT graphKey + 原 version/revision | `makeGraph:99`。接受后保留 graphReceipt，后续布局使用该 receipt 的 revision/layoutVersion。 |
| 仅布局 | graph 无差异时预填 graphReceipt | `save:152` + `makeLayout:107`。不发送 revise。布局使用独立原 layoutKey。 |
| 图成功、布局未知 | 原 graphReceipt + layoutKey | `pipeline:127` 只恢复原 layout，绝不重发已接受 graph。 |
| 图已接受、布局明确拒绝 | 原 graphReceipt/layoutKey/body/CAS 仍保留 | `pipeline:142` 保 PARTIAL_REJECTION/BLOCK，仅退役拒绝布局 primitive；用户显式重试原布局，不能另存/重发图或按新 GET 换 CAS。 |
| 最终读取失败/错域/旧版本 | GET 原 receipt.id | `finalRead:88` 校验 id 与 revision/version/layoutVersion，保持 ACCEPTED_READBACK；只显式 GET，不 POST/PUT。 |
| builtin 复制 | copy POST sourceRevision + graphKey | `prepareCopy:40` 来源版本冻结；原 builtin 图只读，视口移动不标 dirty。 |
| 图定义尚未接受时明确 CAS 拒绝 | 事务拒绝后草稿保留 | `rejected:97` 只针对此模板 API 合同；保原本地 draft，显式确认另存新流程。图已接受的分段拒绝走上一行保护，不能按此另存。没有把所有域 409 泛化为停止证明。 |
| 已保存跨 id 导航失败 | 精确已接受目标 | `completeSave:114` 签发真实 receipt permit，goAccepted 成功才结清；导航 false/reject 只再试同目标交接，不再写。普通 sidebar/back 不获 permit。 |

服务端依据为 `src/main/java/io/opencode/loopper/service/workflow/WorkflowTemplates.java:47,61,71,82,90`：原 request-key/digest replay 在 CAS 前、事务内；revision/layout 各自核对原 CAS。只读审查后端，没有运行或修改 Java。API DTO 保持 `api/workflow.ts:10–13`。

## 旧编辑器功能/测试映射

行号基于本批实际源码；表中的新测试是作者证据，不能充当独立复核或真实 Chromium。

| 原功能/原测试锚点 | 新实现 | 实测作者锚点 |
| --- | --- | --- |
| 预设绑定 parent、删除/撤销，旧 `WorkflowEditorView.spec.ts:31,40` | `WorkflowPresetPicker.tsx:9` + appendPreset；controller add/insert/remove/history | `pages.spec.tsx:28,71`：真实 Flow measured edge、原输入来源、只一条编辑、删除确认、undo；无保存写。 |
| 原 graph/layout 键、SENDING/UNKNOWN 禁离开/重载，旧 view spec `45,51,64`；save spec `15,21,29,34` | 独立 graph/layout receipt owner 与 frozen draft | `editorController.spec.ts:16,21,47,57`；`pages.spec.tsx:34` 在实际字段锁定中三皮肤 rerender、关闭面板后 critical/原 identity 保留。确定分段拒绝另见 `editorController.spec.ts:27`、`pages.spec.tsx:41`。 |
| 版本冲突另存，旧 view spec `77` | 图段尚未接受时 CAS definite-rejection 分支，确认另存 | controller spec `62`（原 draft、原 revise 次数与显式 create body）；不能把已接受图/拒绝布局套此许可。 |
| 读取失败不能出现 editable stale canvas，旧 view spec `83` | load ready=false + exact scope | pages spec `86`（真实根 StrictMode、退休后读不复活）；controller spec `79` 旧 lease GET 不能污染新读取。 |
| builtin 只读/复制，旧 view spec `86`；save spec `9` | graph readonly，viewport local only；prepareCopy | pages spec `81`；controller spec `66` 原 sourceRevision、同原 copy request、不隐式 create。 |
| DOCUMENT 公共声明 rename/type 同步 consumers + 两次 undo，旧 view spec `91` | `WorkflowPublicInputs.tsx:5` 原子投影全部绑定 | pages spec `65`，实际输入/类型、validate graph 与两次 undo。公共声明不是上传 writer。 |
| dirty Stay/确认、清爽 context、重复选择稳定、node/edge exclusive、undo 清陈旧 selection，旧 view spec `102,106,115` | controlled UiContextPanel + parent revision confirmation | pages spec `21,71`、controller spec `82`；普通 context close 保 draft，Escape 关闭后按原选中节点/画布回焦。实际路由 guard 由组长 production bridge 浏览器验收。 |
| 离屏节点按名称定位，旧 view spec `129` | `locate` 公开 reveal/focus，无 layout 保存 | pages spec `76`：original layout JSON/dirty/history 不变，随后 ArrowRight +24 并 Ctrl+Z 回原布局。105px 原手势不在本模块重写。 |
| duplicate/cycle/self、producer 有消费者不能删，旧 graph spec `10,15` | 原 graph.connect/removeNode | controller spec `82`：边/undo 数不增长，绑定删节点被拒。 |
| 角色 slots/原发布 revision/迟到 label，旧 RolePicker spec `15,20` | `WorkflowRolePicker.tsx:7` 独立 read channels，node+role+revision+module identity 复核 | nodeEditors spec `53,54,72`：WORKFLOW_WRITE 许可、不隐式 latest、late revision 隔离、foreign DTO 拒绝绑定。 |
| argv 边界/空白/无法读取原配置/锁定，旧 CommandEditor spec `9,22,29,34` | `NodeSpecialists.tsx:15` 结构化程序、每项 argv、输出 exact、时限/用途/上游 CODE 选择 | nodeEditors spec `21,27,58`；确认期间原 node/config 改变后拒旧确认。 |
| File verification check 类型/原 criterion/未知形状/明确移除，旧 VerificationEditor spec `9,16,23,28` | `NodeSpecialists.tsx:33` FILE_CONTENT/HASH/NOT_EXISTS + 原参数保留 | nodeEditors spec `31`，真 Ant 确认、原 whitespace/其他 parameters。旧参数校验规则移植，不挂原 Vue。 |
| review FULL/date bindings，旧 ReviewSourceEditor spec `9,18` | `NodeSpecialists.tsx:50` + 原 replaceReviewSource | nodeEditors spec `50`：只移除日期输入、保留其他配置；图公共 date 同步仍由纯 TS owner完成。 |
| source paths/章节/ordinal/损坏配置不能静默转换，旧 SourceDesignEditor `10,19,27,33`、DocumentReviewEditor `10,22,28,32` | `NodeSpecialists.tsx:54,61` 列表/字段/确认重设，不是泛 JSON | nodeEditors spec `36,40,44,51`：原 whitespace、其他 parameters、可读 ordinal、专业输出固定与只读。 |
| repository/history/source/test-profile/native-test/summary/snapshot/report policies | `WorkflowNodeEditor.tsx:25` 专属字段与限制 | nodeEditors spec `44,47` 每模块时限/ordinal/purpose/policy/reuse；`64` 输入、CODE 输出、稳定 outcome key 中文名、retry/pause。 |
| 专业输出/专业完成方式固定，普通 outcomes 可以编辑且不能删已引用结果 | `WorkflowNodeEditor.tsx:25` 原 allowedKinds/固定交付/完成规则 | nodeEditors spec `51,52,64` 专业模块矩阵、expectedOutcome 保护；不删除专业节点专属能力。 |

## B NodeRun 共享呈现出口与读取边界

`index.tsx:8` 导出所有真实 React 报告。`reports/index.tsx:19` 的 WorkflowOutputReport 按旧 NodeRun 的 moduleId/name 路由原 type 专属正文；TEXT 使用安全 Markdown，专业 JSON 用专属 validator/结构化 report，PLAN 指向候选计划，不冒称已应用。仅未知通用 JSON 交付保原只读代码显示。

| 原 Vue 报告/能力 | 新 React 文件/出口 | 作者实际覆盖 |
| --- | --- | --- |
| Command、Verification、Review、Repository、Source、History、ReviewSource、SourcePlan、DocumentPlan、Document、HistorySummary、SnapshotSummary | `reports/BasicReports.tsx:5–49`；各 Workflow…Report/Summary 名称保持 | 22 专业 parser 的 null 损坏 DTO 矩阵 `reports.spec.tsx:68`；所有原 validator 条件机械迁为纯 TS `validators.ts`，不简化成功判定。 |
| TestProfile、TestDesign、TestScope、NativeTest、TestReview、TestSummary | `reports/TestReports.tsx:6–26` | reports spec `42,45,48,51` 保实际执行/跳过、scenarioCoverage 不能冒认、inputUnchanged/lineage、多批、明确 NONE/DUAL、相互矛盾不能 PASS。 |
| SourceDesign、DocumentReview | `reports/DesignReports.tsx:5,9` | reports spec `24,28,31` 章节/citations、REVISE/PASS、损坏独立复核；安全 Markdown不加载图片、不执行 HTML。DocumentReview parser null 与原校验移植有覆盖，全部有效变体未逐个重放。 |
| Snapshot、HistoryAnalysis | `reports/AnalysisReports.tsx:4,10` | reports spec `34,39` 批次/证据/归因/自身复核/复用 provenance、无模型会话事实、损坏判定；HistoryAnalysis 有 null 校验，完整 contributor 各有效 DTO 未在 A 单测逐项重放。 |
| KnowledgeEvidence | `reports/WorkflowKnowledgeReport.tsx:8` + W3真实 KnowledgeEvidence | bundle/source body 原纯TS工具复用，按选中披露；证据读取由 B NodeRun持有，不新增API。有效 Knowledge 多格式作者尚无新专项，须 B NodeRun/组长集成验收。 |
| 原始进程/准备/native 文件正文 | `WorkflowCommandEvidence.tsx:10` | reports spec `54,59`：准备失败、后续未执行、取消/超时/输出不完整、停止未确认、启动命令默认折叠、native XML纯文本、raw身份只主动披露。 |
| Code changes / File text pagination / writeback / partial report | `reports/Previews.tsx:7,18,23,27` 纯DTO callbacks | A spec `63` 实际安全 Markdown relative link reader callback、外部/绝对/越界/编码禁跳。分页/hash/abort、manifest cursor、writeback identity/version/现场读取/partial download 均由 B 独占 loader 测试与集成证明，A不声称DTO显示完成那些API合同。 |

所有22报告原 `computed` DTO 校验从原组件完整提取，保严格类型/数量/相互一致条件，不通过 JSON摘要代替正文。样式只语义 tokens，不新增图标依赖；18必要 action key 由组长中央生成，A只提交 semantic-proposal.json。

## 作者执行证据与剩余门槛

最终结果/源码 SHA 见文末冻结记录。聚焦命令只运行 A 专属目录，`maxWorkers=1`，不启动server/browser，不写共享dist/tsbuildinfo。首次 collection 存在 NodeEditor JSX 缺括号；修正后原102全部通过。UI第二轮的重复纯文本查询及受控hash返回值属于测试定位/协议期望不准确，已对齐真实安全正文与 onNavigate，不计产品红。首轮确切作者缺陷为错误未登记 ui.edit 与 panel close 焦点恢复顺序，现已修复。后补配置确认与角色错域/公共声明确认负控；没有改原 Vue spec/W0，也没有以旧 Vue 测试通过冒真实 React。

范围内 vue-tsc 使用 `/tmp/workflow-w5-typecheck.json`（继承项目 strict/paths；include A目录与 `src/env.d.ts`），`--noEmit --incremental false`，不会产生共享tsbuildinfo。第一次临时 config 缺 env 声明导致 ImportMeta.env 类型错误，修正的是临时 include，不放宽项目类型。

仍待组长：真实 bridge 路由与 Ant leave 的浏览器、三皮肤1440/1280截图、105px/zoom/控制器即时 root清理既有合同及新页资源、全项目 type/build/unit、B NodeRun reader/专业报告实际集成、非作者独立评审。作者 PASS 不等于这些门槛已过。没有实际后台/Provider调用、没有新依赖、没有 commit/push/PR。

## 初次作者候选冻结记录（106；最新分段修复见文末）

- `vitest run src/pages/w5/workflow --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w5-evidence/A-final-v3.json`：exit 0，106 PASS / 0 FAIL / 0 SKIP。controller 13、真实 page 8、structured node 31、report 54。保留原102，新增4条确认/结构化/错域负控，未 drop/skip。
- 范围内 strict `vue-tsc -p /tmp/workflow-w5-typecheck.json --noEmit --incremental false`：exit 0，空 error log。
- `git diff --check -- frontend/src/pages/w5/workflow docs/design/react-full-migration/evidence/w5/workflow.md`：exit 0。
- 专属源码/测试 24 文件冻结 SHA 清单：`/workspace/react-full-w5-evidence/A-freeze-hashes.json`，SHA256 `4dff4d0b3a9a67fac7d9090de7b875177056e94569f6c54273d9a86371844b8d`。
- 原始证据 `/workspace/react-full-w5-evidence/A-final-v3.json`，SHA256 `11425c898d7b109a54de653a44d2644d55b350b836ca832e5c81777c33658f48`。
- 原始证据 `/workspace/react-full-w5-evidence/A-final-v3.log`，SHA256 `9e648d4e14f4570eb72371a9ef33f849aa83b69c74cb26915e6af9a685ca303b`。
- 原始证据 `/workspace/react-full-w5-evidence/A-type-final-v3.log`，SHA256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。
- 原始证据 `/workspace/react-full-w5-evidence/A-author-summary.json`，SHA256 `e72ffd112f6507f1f776cc6772589c27172e59a980cf77bcb5e00919a56a18c4`。

此冻结只代表 A 作者范围，B/C/root 的未决独立门槛保留，不修改其源。报告与源码交回组长；后续生产修改只针对明确独立发现并重新freeze。

## A 对 C Designer 的非作者独立审查

审查者为 A，C Designer 实现、修复及作者测试均由 `/root/react_ppt_canvas` 持有。A 未修改 C 源码或测试；只在自己的目录新增 `independent-designer-review.spec.tsx`，另复跑 C 已冻结测试及组长 retarget 的原 W0。A 自己的 106 作者测试不计入以下独立结果。

### 实际发现与闭合

| 发现 | 实测修前证据 | 作者修复与原样复验 | 分类/范围 |
| --- | --- | --- | --- |
| 已接受初始会话返回异项目或异草稿，原 File 被消费并连接异会话 | `A-independent-C-before-v2.json/log`：3 项中 2 FAIL、1 PASS。实际 phase=SETTLED、initialFiles=0、foreign-session SSE=1；不是只有静态推断。修前 controller/fixture/spec 完整副本在 `A-independent-C-before-source/`。 | `controller.ts:132,137` 先核非空 id、原 projectId/draft.id，再消费 File/输入。独立 spec `14,21` 原样断言已接受事实保持 ACCEPTED_READBACK、原 File 实例保留、原输入不清、异会话订阅 0、显式恢复 POST 总数仍 1。最终三项 PASS。 | P2，新 TS owner 回执隔离缺陷，已关闭。初始创建属于保留的 owner/UI 能力夹具；生产 `/designer` 默认 historyOnly，不能把该负控冒称用户当前可达的新建路由。follow-up 正控是实际历史会话能力。 |
| 结构化数值输入丢失原公开上限 | `A-independent-C-limits-before-v2.json/log`：6 项中三项数值 FAIL，其余三项 PASS。真实 React change 后依次投影 maxStageAttempts=21、startup=301、shutdown=61，max 属性均为空；不是仅检查标记。原 NumberField/spec 副本在 `A-independent-C-limits-before-source/`。 | `LoopSpecEditor.tsx:17,42,48` 为原字段加 max/min 与用户 change 夹紧；不在 mount 归一化输入 DTO。独立 spec `25` 原样三项变为 20/300/60，max 属性对应；最终全部 PASS。 | P2，实际迁移回归，已关闭。合同锚点：旧 `components/LoopSpecEditor.vue:293,348`、`frontend/AGENTS.md` 数值上限规则、`domain/LoopSpec.java:121,122,275`。原 maxTask=100/stagnation=20/readiness=100..599 的源码也已恢复，本独立探针只实测上述三个字段。 |

### 原协议与测试方法核对

- `controller.ts:40–44,76–103,160–175`：active lease/scope/readEpoch 限制每段 GET 与投影；SSE 只触发 REST 读取。`capabilities.spec.ts:14,20` 实测同会话旧 poll 不覆盖新讨论/画像/草稿版本、权限与持久消息，显式旧版本读取保留本地编辑。
- `controller.ts:120–157,268` 与 `controller.spec.ts:13–19`、`DesignerPage.spec.tsx:20–21`：multipart UNKNOWN 显式恢复同 submissionId/body/有序原 File 引用；可达后编辑保留为未发送草稿。keyless 写 UNKNOWN 不凭新 GET 重新发送，接受后的读取失败只 retryReadback；没有新造 by-request GET。实际 File 引用与 body 相等由测试核验，未把 filename 显示当完整上传协议证明。
- `controller.ts:168,235` 与 `capabilities.spec.ts:21–27`：仅真实 draft/profile CAS 冲突码按确定拒绝处理，普通 409 仍 UNKNOWN/BLOCK；没有泛化全部 409。`capabilities.spec.ts:29–44` 实测错误 accepted DTO/读失败不导航、不重 POST，known Task GET 仅放行精确临时目的地，停止无归档证明仍 BLOCK，copy-v2 两个原命令分开。
- `DesignerPage.tsx:22–27`、`DesignerPage.spec.tsx:25–35`：同路径 query 安全切换先退役 A 再挂 B；UNKNOWN File owner 不能被 B 替换。严格根测试 `DesignerPage.spec.tsx:18` 覆盖实际 StrictMode replay、无自动写、退出 close 一次。它不是全局所有 listener/observer 清零证明。
- 原 W0 21 项标题/组合表达式与基线 `5c181fc39e26249650df851fe9a7c8a68cab519d` 相同，静态比对结果保存在 `A-independent-C-w0-title-proof.json`。`w0-contract.tsx:45–63` 使用真正 memory VueRouter + React 页面/TS owner，保 Stay 后草稿、pending/unknown 阻离开及失败导航后的原 accepted Task 入口。初始夹具 `historyOnly=false` 显式标记非生产入口；没有强行解除 disabled。
- B6.1 `w0-contract.tsx:67` 已实际挂 B，采 B snapshot 与 GET 次数后才释放 A 原 promise；没有用未挂载 B 代替隔离。mandatory reject `:66` 保不可达负控，未制造 reject emit。B6.2 `:69–72` 先真实 UI preview，再退役；modal 变体调用原 public proposal 回调证明旧确认失效，未强行点击已销毁弹层。
- B7.1 `w0-contract.tsx:76–82` 先证明 terminal event 期间 owned timer>0，root.unmount/retire 后立即采 0，随后才 resolve/advance。B7.2 `:83–90` 先证明 composer RAF=1，以真正 router.push 离开，再核 0；手动迟到回调在严格样本后。RAF 账本仅覆盖该 composer callback，不宣称所有平台 RAF；`router.afterEach:37` 负责该隔离夹具的真实 owner 退休，不把最后 finally 清理冒称首快照。
- `LoopSpecEditor.tsx:21–84` 保原 Stage workPackageId/stageKind/artifactPlanId/执行策略与 criterionIds，公开结构化 stage、runtime、封闭 verifier、制品断言、limits、model、sessionPolicy、nextAttemptPromptTemplate；不是通用 JSON 编辑替代。`LoopSpecEditor.spec.tsx:11,13,15–18` 实际保存其他字段时全 DTO 往返、13 种 verifier 类型、runtime/制品断言、AI-only 条件与新会话策略以及数值最大值。原始 JSON 只读披露。

### 最终独立执行与准确边界

2026-10-03，在 C 冻结 manifest `C-designer-freeze-hashes.json` 下执行；SHA256 `f3939b05eff5877f36231f252869db6188a588e8f04fded431dee175160a7e46`，14 文件执行前后完整 hash 一致。A 作者冻结 24 文件也仍逐字节一致。

```text
cwd frontend
node_modules/.bin/vitest run src/pages/w5/designer src/w0/designer-w0.spec.ts src/pages/w5/workflow/independent-designer-review.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w5-evidence/A-independent-C-final.json
```

exit 0，90 PASS / 0 FAIL / 0 SKIP：C 原作者 controller14/capabilities16/page12/LoopSpec21=63，原 W0=21，A 新独立负控=6。独立 6 项自身冻结 SHA256 `c60de246de8e233ad61140a616f76d6086e5dd1eab4d69ef4bdc81f858150561`；强负控 before-v2 与 after/最终均使用相同断言，没有以修后新正控代替原红。

- 最终 raw JSON SHA256 `8a1ba9524a500bd561a51f8e1c97f5d93e0b0a59a84119bd45c1b01b83b4bbde`；log SHA256 `cce14b0e5649d00bca577956b8556fa3ce0c568454802c9802301e3b084a15ac`。
- foreign before-v2 JSON SHA256 `3ca722bdc883fd102749536ddd321cce5590f23ca65655903b8e6c704b405185`；numeric before-v2 JSON SHA256 `8ba069117602739816d2eecc8161a12e39d34079bb398ecbc3b6636b336f8a2f`。
- 新独立 test 的范围内 strict typecheck exit 0，raw `A-independent-C-probe-type.log` 为空。未运行全量 type/build/Chromium/真实 API。

raw 保留预期的 navigation reject VueRouter 警告，以及 terminal timer/storage-read helper 的 React act 警告；不声称“零警告”。资源账本的首采样是同步 owner 清理，不等待该 DOM warning 对应的渲染或自然计时；这些警告未使断言失效，后续夹具可统一 act。当前独立审查范围没有未关闭阻塞；真实路由桥/三皮肤截图/原生 EventSource/全资源即时清理和完整浏览器仍由组长另行执行，不能由上述 jsdom/mock transport 通过数替代。File 原身份只在保留内存 owner 中恢复，不承诺无持久化支持的跨刷新恢复。

### 后续批准的单工作包重新讨论差异复核

C 作者自查确认原 direct FINAL_REVIEW 的 `reopenPackage('WP-1')` 入口遗漏，且重开 CAS 应使用 `approvedDesignRevision`，不能使用可能不同的当前 `designRevision`。这是源码确定的迁移策略/功能差异；A 没有独立运行该修复前的红测，不把前面的 90 PASS 宣称覆盖了此遗漏。原证据/原 14 文件 manifest 已原样保留为 `A-independent-C-final-base-manifest.json`（SHA `f3939b05eff5877f36231f252869db6188a588e8f04fded431dee175160a7e46`）。

组长批准 C 仅修改 `DesignerPage.tsx`、`controller.ts`、`DesignerPage.spec.tsx`。A 只读核对旧 `DesignerView.vue:1448,1463,1885`、真实 `DesignerSessionService.java:1032–1039`、`api/client.ts:1985` 与新 `DesignerPage.tsx:69` / `controller.ts:279,285,286`：原已接受单包在总体确认前恢复可发现的重新讨论入口；冻结原包 id/discussion revision/approved revision，缺失接受版本时拒绝新写。HTTP 字段仍按现有 client 叫 `expectedDesignRevision`，其值按该 endpoint 的原合同传已接受版本；不改 API、不将 redraw/redesign 作为重新讨论替代。

实际 RTL `DesignerPage.spec.tsx:23–36` 使用 `designRevision=4`、`approvedDesignRevision=3` 严格区分 CAS，真实点击先打开确认并断言 POST=0。通过原 mock stream STATUS callback 触发原 GET，将权威 discussionRevision 从 1 推进到 2，使旧确认按钮 disabled 且 POST 仍 0；没有伪造总体确认页不可达的 composer 输入。Stay 后重新点击，再确认只有 `reopenWorkPackage('A','WP-1',2,3)` 一次，`redesignWorkPackage` 为 0，command SETTLED。原“不显示工作包轨道”的断言仍保留，该项未删除或 skip。

新冻结 manifest `C-designer-freeze-hashes-reopen.json` SHA256 `7e9447d9d5c4403741e7927bb04fafb090c435cbd750ce9bc3ad0cb1a019a07b`；14 文件运行前后 hash 全匹配，较原 90 对应 manifest 只有上述 3 文件改变。2026-10-03 原完整命令复跑、仅输出文件改成 `A-independent-C-final-reopen.json/log`：exit 0，90 PASS / 0 FAIL / 0 SKIP，仍为 C 63 + 原 W0 21 + 独立 6，不能把两轮相加为 180 个独立场景。

最终 raw JSON SHA256 `aee7f6d8f79b1e3811891231d81c2a58189dadf383c43756c3c878f631e53793`；log SHA256 `24d3476dc70868fff04b043ca3fbce1e78b372424a242ba5c07ba8e44bab4ab1`。该批准差异的独立复核已关闭，没有新增未修复阻塞；浏览器/全量门槛仍归组长。画布 base CSS 缺载问题由组长独占共享修复，A 仅报告真实样式归属（旧 Views import 的 `components/workflow/workflow.css`），未修改共享画布/CSS，不能将其计入本次 C 单包差异修复。

## 已接受图定义后布局明确拒绝：独立红测与最小修复

本项由 B `/root/react_legacy_canvas` 非作者审查发现并取得真实红，组长批准 A 作者修复。分类为 P2 **实际迁移分段回执缺陷**，不是旧后端协议缺口，也不是只有源码风险。旧 `components/workflow/save.ts:24–38` 和 `WorkflowEditorView.vue:129` 持有原 graphReceipt；新 owner 修前 `editorController.ts:142` 在布局明确 409/422 后将整个 saving 清掉。

B 原样量化场景：图 revise 已接受 revision=3/version=4/layoutVersion=4，layout 409 明确拒绝，再由用户显式 recover。修前 `B-A-partial-layout-quantified-before.json` 实际 1 FAIL；对应 observation 记录原保存对象未保留、reviseCount=2、layoutCount=2、leave=CONFIRM_DISCARD。不能以原 106 PASS 声称覆盖了该情况。原 probe 全文及执行前源码 manifest 留在 `/workspace/react-full-w5-evidence/`，未修改或删除红证据。

`editorController.ts:142–148` 现在只退役已确定拒绝的布局 primitive，保原 saving 对象、graphReceipt、layoutKey、冻结 draft 和原布局 CAS。域内 PARTIAL_REJECTION 表示“图定义已接受，但布局已明确拒绝”，`canLeave:37` 保 BLOCK，`save:155` 阻另存；普通编辑/重载也被锁定。后续 **用户显式** `recover()` 仅重新建立原布局 primitive，body 仍为原 requestKey/expectedRevision/expectedLayoutVersion/layout；不重新发送图定义，不读取新 GET 来换版本，也不自动写入。通用 W1 receipt 实现、后端和 API 没有改变。

`WorkflowEditorPage.tsx:50` 保关键状态常显和语义 `receipt.retryOriginal` 恢复入口，字段禁改、copy 不可执行。布局仍 409 时继续原身份 BLOCK，不伪造全部保存成功或把已确定拒绝称 UNKNOWN。最终仅布局被接受并且权威 GET 通过原 id/版本验证后结清。纯英文或空错误消息使用该分支专属中文拒绝 fallback，不改变原 severity/conflict；其余 SENDING/UNKNOWN/accepted-read 的错误路径不变。

永久作者回归：

- `editorController.spec.ts:27` 的 409/422 两条均真实完成图写入，连续两次布局拒绝，再第三次显式成功；严格比较每次布局原 body/key/CAS，图 PUT 总数始终 1，拒绝期 GET 不增加。原 saving/graphReceipt 保留，draft 禁改、load/copy 为 0，新成功 GET 后才 ALLOW。英文错误负控同时断言确定中文拒绝而不是“尚未确认”。
- `pages.spec.tsx:41` 真实 React 页面点击保存后经历两次布局 409，三皮肤实际 rerender 不写入；状态清楚、原字段 disabled、copy 不存在、owner BLOCK。两个真实恢复按钮点击后布局总数 3、图总数 1、全部 layout body 相等；最后 GET 后字段可编辑并显示已保存。

B 修后第一次原样量化 probe 1/1 PASS，`B-A-partial-layout-after-observation.json` 实际 originalSave/graphReceipt 保留、reviseCount=1、layoutCount=2 原 body/key/CAS、leave=BLOCK。该轮针对最后 fallback 前候选，保留此来源，不冒充最后文本字节完整复验；B 最终独立复验由其报告记录。

### 最后作者冻结（109；覆盖上述分段修复）

```text
cwd frontend
node_modules/.bin/vitest run src/pages/w5/workflow/editorController.spec.ts src/pages/w5/workflow/pages.spec.tsx src/pages/w5/workflow/nodeEditors.spec.tsx src/pages/w5/workflow/reports.spec.tsx --maxWorkers=1 --reporter=default --reporter=json --outputFile=/workspace/react-full-w5-evidence/A-final-partial-v2.json
node_modules/.bin/vue-tsc -p /tmp/workflow-w5-typecheck.json --noEmit --incremental false
```

两命令 exit 0；109 PASS / 0 FAIL / 0 SKIP = controller15 + page9 + structured node31 + report54。原 106 全部保留，仅新增 2 条 TS 合同与 1 条实际 UI，未 drop/skip/放宽原断言；独立 Designer 6 条未混入作者 109。第二命令严格限定本目录及 env，无 emit/共享 tsbuildinfo。

- 24 文件最终 manifest `A-freeze-hashes-partial-v2.json` SHA256 `70d1f7e471f587dabe90d9a912cd8670ba73cfd25562e55814cf4a1e8857f23e`。仅 editorController/WorkflowEditorPage 及其两相邻 spec 改变；专业节点、报告和独立 Designer spec 原字节保留。
- 作者 raw JSON SHA256 `88c0aef496fdbb6f694600a7b90767f3198ac9cf13a53eb02a6e704999165b83`；log SHA256 `2d459bf50a70616711e7794b685a7054a86ac5a1acdc9eff198ceb248e531e3e`。保留既有 role picker 异步更新的 act 警告，不声称零警告；上述新增三项自身聚焦 24 项无该警告。
- type log `A-type-final-partial-v2.log` 空，SHA256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。
- 作者 summary `A-author-summary-partial-v2.json` SHA256 `2abc6996ed352e13ac88363d49f4594b4f6e461b917558615db9941fef1ef484`。原 106、分段初次 109 与最后文本准确性 109 候选证据分别保留，不相加计唯一覆盖数。

源码和测试至此永久冻结；A 不自行运行浏览器或全量门禁。此次修复使旧 root 候选的受影响来源失效，最终全量与真实三皮肤路由/恢复/严格资源浏览器由组长针对本 manifest 重建重跑。若原布局 CAS 持续 409，只能继续按原身份阻断；本补丁没有提供换版本、另存绕过或跨刷新持久恢复保证。

B 最后非作者复跑原四 spec：`B-A-independent-final.json` 实际 109 PASS / 0 FAIL / 0 SKIP，exit 0，SHA256 `da7719b8a44d87b5d52b98276f8dd0813158160dd7297a6f9e27dfe16b7eed3d`。执行前完整副本清单 `B-A-independent-final-source-before.json` 与执行后 `B-A-independent-final-verification.json` 核 24 文件全部匹配上述 `70d1f7e…` manifest，hashMismatch=[]。不含 A 独立 Designer 6 项；A 作者和 B 复跑仍是同一 109 个场景，不能加作 218。此最终字节复验含英文错误负控，非作者报告已将本 P2 关闭；原样探针的修前/修后来源分别保留。


## 组长最终集成门槛

作者阶段的“待组长”是当时来源记录。最后冻结源码下：W5 428/428、全量2332/2332、111 W0及原41红通过；type/build/tooling/accounting通过。生产W5 49/49、64严格首样与30新PNG，W2–W4回归49/31/29全部通过；W1既定隔离dev夹具41/41。详见 [最终验证](verification.json) 和 [截图](screenshots/README.md)，不以作者或候选结果代替最终证据。旧default全站E2E、真实后端/模型、真实文件解析、设备及GC边界未验证，W6尚未执行。
