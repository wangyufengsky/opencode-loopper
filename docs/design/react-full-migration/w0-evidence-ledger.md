# W0：Workflow、模板与 Designer 的红测及证据台账

> 本文是设计阶段冻结的待执行计划，下方 `NOT_RUN` 属于当时快照。实际 W0 已完成最小探针：28组＝25复现失败／3通过，27组仍部分覆盖；最终1416 unit＝1332通过／84合同红测失败。生产未修。当前证据、剩余变体及非作者复核以[W0实测报告](evidence/w0/README.md)、[验证回执](evidence/w0/verification.json)为准。窄屏需求已撤回，旧证据保留且不计当前桌面准入。

本台账承接 [workflow-protocol-design.md](workflow-protocol-design.md) 第 9 节的 B1–B9，并以项目经理本轮已决定的导航、恢复合同收紧准入。生产源码审查基线为 `a3c692d38925206883f2b0a1255479108cfd439e`；本次只读核对时工作树 HEAD 为 `0717a4a5c3f5bf6fb61af8208136d5353e05a941`，分支为 `feat/react-full-migration`。本轮只新增本文，未修改业务、安装依赖或运行生产单测／浏览器。

所有源码、原测试行号均指该工作树；下文 `views/`、`components/`、`stores/`、`api/`、`domain/` 的路径相对 `frontend/src/`，Java 路径相对仓库根。原测试名称和断言是**测试源码证据**，不代表本轮执行通过。静态原型的 mock unknown、查询、409 与截图仅验证设计呈现，不验证以下真实协议。

## 1. 已决定的业务合同

| 合同 | W0 必须验证的行为及边界 |
| --- | --- |
| 未确认写禁止离开 | 本地写入 `SENDING`、`UNKNOWN`、`ACCEPTED_READBACK`、正在上传／提交时，SPA 的 push、replace、back/pop、同路由换实体、关闭操作面板、会销毁 owner 的回退均拒绝；即使普通 confirm 返回 true，也不能放弃 pending。服务器已在运行、但当前页面仅只读且没有本地未确认写，不因此禁止离开。 |
| 同原身份显式恢复 | 原 path、实体／attempt、版本域、完整 body、该 endpoint 实有的 requestKey／commandId／submissionId、File 引用和顺序由同一业务 owner 持有。未知结果期间不因改表单、刷新读取、主题、render、StrictMode 或换视图生成新 key，也不自动重发。只有用户明确恢复且 endpoint 已支持原身份幂等时，才允许相同请求重投。 |
| accepted 只恢复后半段 | 已得到确定回执后，读取失败只重读；导航失败只重导航／读取原 ID。成功写推进 revision/version 是正常事实，不能要求读回版本仍等于写前 expectedVersion。不可再次 create/start/confirm 来“找回”已经接受的操作。 |
| 草稿与 File 不丢失 | 409、拒绝、未知、读取失败、取消导航保留草稿及未发送修改；同一会话恢复使用原 File 实例及原顺序，文件名或 metadata 不证明相同字节。处理完原操作后，后来编辑的内容仍是未发送草稿。 |
| 普通 dirty 保留确认语义 | 没有未确认写的普通未发送草稿可提示确认；取消则保留，明确放弃才离开。无写身份的文件草稿也必须纳入这项确认和取消后保留，不能因 LoopSpec 未 dirty 而静默丢失。pending 不能走该放弃入口。 |
| 自有回执导航交接 | 仅原 owner 的确定 receipt／ID 可签发限定目标的成功交接；不提供全局 bypass。导航被 guard 取消、返回 NavigationFailure 或 reject 时，原 receipt 仍可恢复，不能清完 pending 后重新 create。 |
| 刷新能力不得虚构 | beforeunload 只能请求浏览器警告，无法保证强制刷新／关闭被阻止。不得承诺当前无服务端或持久化支持的跨刷新恢复；request metadata 可存不等于 File 字节可恢复。storage 不可写时保留内存、披露刷新边界；需要重选时校验原身份及实际 bytes/hash，不自动新建或换 key。 |
| 迟到与资源独立处理 | 每个 await 后复核原实体、epoch 和对应版本域。旧 A 回执可以归档给 A，但不能更新 B、清 B 锁或导航 B。owner 退休先失效再逐项释放其读资源；不能以 abort 读取证明写入已停止，也不能丢弃 unresolved operation。 |

上述合同与 [controller-interfaces.md](controller-interfaces.md) 第 2–5 节对齐；该文件仍是待实现的接口设计，不能算业务修复。已验证 React 画布和依赖补丁继续保留，本台账不要求重写其手势，也不把第一阶段画布通过结果转记为 B1–B9 通过。

## 2. 判断、门槛与当前状态

“源码确定”仅表示策略或控制流差异可以直接读出；“待复现风险”需要行为反例证实；“接口能力约束”本身不等于缺陷。红测必须验证上表的正确业务合同，不能把旧有允许丢失的行为写进 expected 来使测试通过。运行后若基线已经满足某场景，应记录真实 GREEN／既有覆盖，不能伪造 RED 或声称修复。

| 编号 | 当前分类 | 后续消费波次（不延后 W0 门槛） | 本轮门禁状态 |
| --- | --- | --- | --- |
| B1 | 源码确定的导航策略／回执收尾差异；实际丢失待复现 | W0，W1 owner；W5 真实需求页接入 | `NOT_RUN`；策略已决定，业务未修复 |
| B2 | 源码确定的嵌套 guard 差异；组合页损失待复现 | W0，W1 command；W5 需求子面板 | `NOT_RUN`；不能拿父 page guard 代替逐子操作证据 |
| B3 | 源码确定的 pending 重置／存储差异；导航或输入变化损失待复现 | W0，W1 template owner；W3 模板页 | `NOT_RUN`；存储与 File 能力必须逐入口确认 |
| B4 | 源码可见的 modal 后才捕获 epoch；跨 run 结果污染待复现 | W0，W1 scope；W3 文档运行页 | `NOT_RUN`；暂无行为反例及修复证据 |
| B5 | 源码确定的 Designer dirty 覆盖差异；实际丢草稿／File 待复现 | W0，W1 leave policy；W5 Designer | `NOT_RUN`；普通 dirty 与未确认写需分开 |
| B6 | 源码可见的 await scope／multipart 快照不足风险 | W0，W1 immutable operation；W5 Designer | `NOT_RUN`；初次提交原测试不能代替全部后续动作 |
| B7 | 源码可见的资源登记／storage 异常风险 | W0，W1 resource／storage port；W5 Designer | `NOT_RUN`；无首采样或受限 storage 行为结果 |
| B8 | 源码可见的只读读取及子表单退休 scope 风险 | W0 映射和失败证据；W1 scope；W2/W3/W5 各消费页 | `NOT_RUN`；已有 prop/revision 测试不等于卸载隔离 |
| B9 | 源码确定的 endpoint 能力差异；通用自动恢复会误用的风险 | W0 endpoint 能力表；W1 recovery plan | `NOT_RUN`；能力约束不记为已存在业务缺陷 |

**本轮没有任何 B1–B9 的已确定业务修复。** 本轮及此前设计文案、接口草案、原型改正不能改变此结论。以下共有 28 个最小场景行，每行可能有明确的动作／阶段参数；均尚未编写或执行本轮红测，日志和修复提交栏为空。

## 3. B1：创建、流程库操作与自有导航

源码证据：`views/WorkflowRequirementNewView.vue:15,17–25` 捕获 body/key，却仅用普通 confirm 保护 pending；`:20` 的 after 回调只有导航、没有 GET，且没检查 `router.push` 的返回值；`:54–62` 在 locked 时禁用名称／说明 fieldset 及项目／流程选择，因此 UNKNOWN 后从该 UI 编辑或换 revision 不可达。`components/workflow/command.ts:17–26` 卸载 invalidate，并在 execute 正常 resolve 后清 pending；`domain/acknowledgedOperation.ts:14–18` 保有 accepted receipt，仅在原 read 回调仍待恢复时可重用。`views/WorkflowLibraryView.vue:22–34,46–47` 有本地 pending，但无 leave policy；copy 在 `:31` 导航前清 pending。对比需求详情 `views/WorkflowRequirementView.vue:207–215` 对主命令 hard block。

导航补充证据：`frontend/package-lock.json:5546–5549` 锁定 vue-router 4.6.4；已安装同版本副本 `/workspace/opencode-loopper-react/frontend/node_modules/vue-router/dist/vue-router.mjs:1304–1318` 会将部分取消／中止导航 resolve 为 NavigationFailure。因此“await push 未 throw”不能证明已进入目标。它是本机依赖源码旁证，W0 应在当前实际 router 集成测试中重现，不能依赖该绝对路径作为长期测试入口。

接口证据：`api/workflowRuns.ts:14,17` 提供按需求 ID GET 与 create POST，没有 New 的公开 by-request GET。服务端 `src/main/java/io/opencode/loopper/service/workflow/WorkflowPlans.java:51–65` 使用原 key/digest replay；`WorkflowCommands.java:24–28` 拒绝同 key 不同 payload。UNKNOWN New 的恢复应由用户显式发原 key、原 body 的幂等 POST；原型的 mock“查询原操作”不是此 API 的 GET 证明。原 key/body 没有持久化时不承诺跨刷新找回。

原测试锚点：`views/WorkflowRequirementNewView.spec.ts:57` 检查同请求恢复，`:63` 只检查普通 dirty 的 confirm=false；`views/WorkflowLibraryView.spec.ts:38` 检查换筛选仍同 copy key，`:46` 检查 archive 版本；`components/workflow/command.spec.ts:14,20,25` 检查 unknown、accepted-only-read 和卸载抑制。均没有同时证明以下路由与 receipt 留存合同。

| ID | 最小红测／合同场景 | 必须观察的业务断言 | 状态 |
| --- | --- | --- | --- |
| B1.1 | New 发出 create，分别保持 SENDING 和模拟服务器可能接受但响应丢失的 UNKNOWN；令普通 confirm=true，尝试 push、back 与 replace。 | 保持原路由及 owner，原 body/key 不变，有显式恢复入口，未自动调用第二次 create。先取消 dirty 导航作为负控；无 pending 的普通 dirty=true 确认后可离开。 | `NOT_RUN` |
| B1.2 | Library 分别 copy、archive，写 in-flight／unknown 后尝试离开及切换筛选，再明确恢复。 | 离开被阻断；筛选不会换操作的源 ID、sourceRevision／expectedVersion、key；恢复仅按实际支持的原操作，不以新的行选择重拼。copy 已接受但目标导航失败仍保有原副本 ID。 | `NOT_RUN` |
| B1.3 | New create 返回确定 receipt；目标 guard 返回 false，再以竞争导航导致取消；另单独让导航 reject。随后用户恢复交接。 | 三种失败都保留原 receipt/ID；不以 resolve 当导航成功，不开放再次 create；New 仅恢复导航到原目标，进入详情后的读取由详情 owner 持有。成功交接只允许 owner 指定目标，不能绕过另一页未确认写的 guard。 | `NOT_RUN` |
| B1.4 | 分层合同测试：真实 New UI unknown 时尝试操作名称、说明、项目、流程，证明均禁改；纯 owner 负控改变外部 draft projection，再显式恢复原 operation。另在有实际读取回调的命令入口验证 accepted-readback 失败；New 的 accepted 导航失败沿 B1.3。 | New 的禁改负控不移除 disabled、不伪造选择 emit，也不预设基线必红；原恢复 POST 完整 body/key 不变，无自动写。外部 projection 变化不改冻结操作；后来编辑草稿的保留仅在已证明可编辑的入口验证。通用命令 accepted 后只恢复其实际读取；New 只恢复导航、不捏造 after GET 或 by-request GET。无支持的持久化不承诺跨刷新恢复。 | `NOT_RUN`（UI／owner 分层；不预设全部必红） |

门禁：策略差异确定，损失及 NavigationFailure 路径尚未跑；W0 不能以 mock 导航正常成功或原同 key 测试关闭 B1。

## 4. B2：嵌套命令及父页组合保护

源码证据：`components/workflow/WorkflowNodeRun.vue:90–109` 捕获 human delivery／attempt／process version，但 pending 可经普通 confirm 离开；`WorkflowCandidates.vue:24–30`、`WorkflowFinish.vue:35–40`、`WorkflowPublicationCommit.vue:26–32`、`WorkflowPush.vue:36–42`、`WorkflowWriteback.vue:27–33` 同样允许确认离开未确认命令。`WorkflowSaveTemplate.vue:38–43` 已是 locked 时拒绝关闭的对照。父 `views/WorkflowRequirementView.vue:212` 调用各子 canLeave，不会把子 true 提升成 hard block；`:216` beforeunload 也不能代替 SPA 阻断。

原测试锚点：`components/workflow/WorkflowNodeRun.spec.ts:33,41,48,71,77,90`（原 process/human、accepted-only-read、普通 dirty）；`WorkflowCandidates.spec.ts:13`；`WorkflowFinish.spec.ts:27,34,40,57`；`WorkflowPublicationCommit.spec.ts:21,40`；`WorkflowPush.spec.ts:22,30,42`；`WorkflowWriteback.spec.ts:27,33,45`；`WorkflowSaveTemplate.spec.ts:32,49`。详情主命令 hard block 原测试在 `views/WorkflowRequirementScope.spec.ts:153,169`，不能代替嵌套面板矩阵。

| ID | 最小红测／合同场景 | 必须观察的业务断言 | 状态 |
| --- | --- | --- | --- |
| B2.1 | 参数化 human complete、candidate reject、finish、commit、push confirm、writeback confirm；分别写 in-flight／unknown，confirm=true，再尝试关面板、切 node/attempt、换 requirement 和 route leave。 | 每个实际 owner 都 hard block，父页组合保留原 key/body/version/draft；不能只测 page command.pending。另存模板 locked 作为已有对照；无 pending 的普通原因／说明草稿仍走原确认语义。 | `NOT_RUN` |
| B2.2 | 当前 command/model stop 响应未知；尝试离开，再显式恢复；期间读取另一 attempt 或出现新状态。 | 原 requirement/node/attempt、commandVersion／modelVersion、key 不变；没有新 start 或重叠 writer，没有从新 attempt 生成恢复 body。只能在后端有停止证明后表现停止。 | `NOT_RUN` |
| B2.3 | 参数化上述写成功、其 refresh GET 失败；尝试关闭及导航，然后显式刷新。 | accepted 持续阻断离开；恢复只读，mutation 次数仍 1，草稿／receipt 保持；读回的新版本合法用于后续动作，不误判为旧 expectedVersion 冲突。 | `NOT_RUN` |

门禁：父子保护必须使用实际组合页和子按钮证明；直接调用 canLeave 的单元断言只能作补充。

## 5. B3：模板创建 store、运行页与刷新能力

源码证据：`views/TemplateTasksView.vue:110–145` 三种提交分支及卸载都无 leave guard。`stores/templateTaskStore.ts:9,12–24` 在内存保留 key/taskId；`:15` 新 fingerprint 可换 pending。`stores/sourceTemplateStore.ts:6–29` 可选 sessionStorage，仅捕获同 fingerprint；`stores/documentTemplateStore.ts:6–21,26–51` 可选 metadata 持久化／by-request read、真实 File hash，但 pending 不存 File 对象，`:43–44` 改 input/hash 可换 key。`views/SourceTemplateView.vue:72–78`、`views/DocumentTemplateView.vue:48–61` 换 ID 清 pending；Source `:88–89`、Document `:69–70` 会因当前 version 等变化重新分配 command key，不能据此认为 unknown 已解除。

原测试锚点：`views/TemplateTasksView.spec.ts:31,41,53,84` 为入口／字段合同；`stores/templateTaskStore.spec.ts:10,20` 为同 task/start/key；`stores/sourceTemplateStore.spec.ts:8,16` 为丢响应同 key／duplicate；`stores/documentTemplateStore.spec.ts:15,26,34` 为重建 Pinia 后**重新提供相同字节 File**、校验和 hashing lock，不是浏览器自动持久化 File。`views/SourceTemplateView.spec.ts:24,38,47`、`views/DocumentTemplateView.spec.ts:24,34` 证明部分已有命令／读 scope 源码覆盖，不包含以下完整 guard。

| ID | 最小红测／合同场景 | 必须观察的业务断言 | 状态 |
| --- | --- | --- | --- |
| B3.1 | TemplateTasks 三种分支分别卡在 hash、create、start／unknown；尝试离开和更改输入后再次提交。 | 未确认阶段拒绝导航／新操作；保留原 input/key，已创建 taskId 不丢、不得再次 create。继续原 start 的方式先按 B9 实际服务端能力确认；不能因新 fingerprint 丢旧身份。 | `NOT_RUN` |
| B3.2 | Source／Document run A 控制命令 unknown/in-flight，真实同路由尝试转 B，再尝试离开。 | guard 拒绝，不执行 watcher 的 pending 清空；有原操作显式恢复入口。没有本地未确认写的运行只读页允许离开，关闭本页 SSE 不等于停止服务端。 | `NOT_RUN` |
| B3.3 | Source retry/cancel 与 Document resume/cancel unknown；权威 GET 推进版本或用户改变选中批次，随后点击恢复。 | 原命令的 action/ID/key/expectedVersion/modelIds 保持；恢复不是针对新状态的新命令，确定拒绝后才按明确新意图解锁。读取新版本不能默默换 key 绕 CAS。 | `NOT_RUN` |
| B3.4 | storage get/set/remove 抛错下创建及 unknown 保持；另用真实受支持的 metadata 重载入口，重选同名不同 bytes、原 bytes、改变顺序。 | 内存 owner 不丢，导航仍阻断；无自动写。仅实际有的 `api/client.ts:1665` 文档 by-request GET 可找原 run。没有 File 时明确要求重选并校验 hash／顺序；不承诺 Source、普通模板与文档都有同一种跨刷新恢复。 | `NOT_RUN` |

门禁：可选 storage 和 store 未卸载不能单独证明安全；每条 path 的身份、字节、读取／显式重投能力都必须进入 B9 映射。

## 6. B4：Document 控制 modal 的跨 run 窗口

源码证据：`views/DocumentTemplateView.vue:63–74` 在 modal 前捕获 current，modal 后才取 generation，没有确认当前 run ID 仍一致；对照 `views/SourceTemplateView.vue:80–85` 在 modal 前取 token，并在确认后检查。Document `:74–77` 的 token 检查不足以排除“旧 current＋新 token”。

原测试锚点：`views/DocumentTemplateView.spec.ts:34` 只覆盖旧 overview GET；`views/SourceTemplateView.spec.ts:47` 也为 GET scope，不是 modal 迟确认。不能将这些作为取消写入无跨 run 风险的证据。

| ID | 最小红测／合同场景 | 必须观察的业务断言 | 状态 |
| --- | --- | --- | --- |
| B4.1 | A 点取消后挂起真实确认 promise；未确认前导航到 B，读到 B；随后确认 A。 | A 的 modal 已过期，不向 A/B 发取消 POST；B 的 run/error/acting/pending 不被改变。取消 modal 的负控保持 A、零写；原 A 有效确认仍只一次写。 | `NOT_RUN` |
| B4.2 | A 的合法取消已发出，测试强制退休旧 owner／创建 B，再交付 A 的响应／错误。 | 老回执仅归 A；不能覆盖 B run、清 B 锁或发 B 后续请求。正式导航须先受 B3 guard 阻断；强制退休夹具只测 scope 防御，不作为允许未知写导航的产品合同。 | `NOT_RUN` |

门禁：必须实际控制 modal 前后两个 await 的顺序；只调 props 或 token 值而不证明真实入口可达不能关闭 B4。

## 7. B5：Designer 草稿、File、未知写与交接

源码证据：`views/DesignerView.vue:207` dirty 只比较 LoopSpec editor；`:1173–1186` leave/beforeunload 只看该 dirty 和 committedTaskNavigation。初始未知 multipart snapshot 在 `:88–92`，文字／初始 File／message File 在 `:81–82,117–118` 等独立状态；follow-up 写及其 busy 在 `:1308–1351`。确定 Task 交接 `:1091–1095` 先 clear workspace，再 await push，失败保留情况需专门验证，不能从此 bypass 推出全局许可。

原测试锚点：`views/DesignerView.spec.ts:793` 覆盖未知初次 multipart 及后来编辑；`:937,969` 覆盖后续失败保 text/File；`:1831` 覆盖 editor baseline/409；`:1738` 覆盖确定 Task 正常导航，即使 worktree prepare 失败也打开实际 Task。以上都不能证明 dirty=false 时离开正确。

| ID | 最小红测／合同场景 | 必须观察的业务断言 | 状态 |
| --- | --- | --- | --- |
| B5.1 | LoopSpec 不 dirty，分别输入未发送初始目标、follow-up text、仅放入 File；请求导航，选择取消／明确放弃。 | 每种草稿都提示普通 dirty 确认；取消保留 text 与相同 File 实例／顺序；明确放弃才允许离开，不静默销毁。无草稿读视图允许离开。 | `NOT_RUN` |
| B5.2 | 初次 context-turn、follow-up context-turn/message 分别 sending／unknown；LoopSpec 仍不 dirty，尝试离开、重新开始或视图回退。 | 不可用普通 confirm 放弃未确认写；原 operation、File、草稿、submissionId（接口实有时）仍由 owner 保持；重渲染不重发，新输入不替换原操作。无 key 的 message 恢复须按 B9 能力阻断／读取。 | `NOT_RUN` |
| B5.3 | 确定确认回执发起自有 Task 交接，目标 guard 中止或导航 reject；恢复后导航。 | 保留确定 Task ID／receipt 与未交接状态，恢复只 nav/read，绝不再次确认／创建；成功目标是该 receipt 的 Task，不将 WAITING_INPUT 或其它 lifecycle 合同改成可直接发布。 | `NOT_RUN` |

门禁：用户已决定 File/draft 保护与 unknown 禁离开；不再把 B5 写为“待选择是否保留”。跨刷新 bytes 能力仍不得虚构。

## 8. B6：Designer 多 await scope 与后续 multipart

源码证据：`views/DesignerView.vue:779–817` answer/reject 在 await 后处理当前 session；reject modal 后才读当前 session ID。profile `:472–507` 跨 preview、modal、update、refresh，未逐 await 复核原 scope。follow-up `:1322–1348` 使用相对稳定 messageSubmissionId，却从当前 content/files/package/revisions 组 body，成功后清当前表单并重启 poll。与初次不可变 snapshot 的行为不能相互替代。

原测试锚点：`views/DesignerView.spec.ts:310,358` 为 profile 明确选择／409；`:1128` 为 question 正常答复；`:793` 只为初次 snapshot；`:937,969` 不包含“未知后编辑再恢复原 multipart”；`:228` 为 poll 卸载，不是上述写回调隔离。

| ID | 最小红测／合同场景 | 必须观察的业务断言 | 状态 |
| --- | --- | --- | --- |
| B6.1 | A question reply 在途；或 A reject modal 待确认。退休 A 再实际挂 B／同 owner 已切新 session，交付 A 回执或确认。 | 不删 B pendingQuestion、不改 B state/busy，不向 B 发 A question 请求／refresh。modal 后过期则零写；已发写仍只归原 A。 | `NOT_RUN` |
| B6.2 | profile preview 和需要 restart 的 modal 分别挂起；期间 session/TaskProfile version 改变，随后返回／确认；再测 update accepted 后 readback 失败。 | preview/确认不继承新 scope；不提交过期选择到新 session。accepted 的合法 version 推进可读回，但只能恢复读，不再执行 update 或 restart。409 保留用户草稿。 | `NOT_RUN` |
| B6.3 | follow-up context-turn 返回 unknown；编辑 text、换 File／顺序、切 package 或推进 discussion/designRevision，然后显式恢复；另在原写在途期间编辑草稿再交付成功。 | 有 submissionId 的重投保原 body/Files/顺序/revisions；新编辑留作未发送，不以同 ID 搭新 body；迟到成功不得清新草稿或重启已退休 poll。无 key 文本消息不套重投策略。 | `NOT_RUN` |

门禁：用实际会话切换／重挂入口和 controller scope 防御分开取证；不能仅修改当前无真实 watcher 的 route.query，再宣称 A→B 真路由已验证。

## 9. B7：Designer 资源登记与 storage 异常

源码证据：`views/DesignerView.vue:859–864` terminal event 期间 poll 在途会登记未持有 handle 的递归 100ms timeout；`:1167–1171` cleanup 没登记这些 timeout。composer focus `:1047–1050` 通过全局 selector 分配未登记 RAF。workspace `:970,1026,1086–1088` 直接调用 sessionStorage；`:106–114` 带 catch 的文字 helper 不覆盖这些调用。资源登记缺口源码可见，但实际退休后副作用需复现。

原测试锚点：`views/DesignerView.spec.ts:128,185,228` 检查 poll 重开／卸载；`:705,720` 检查恢复失败／归档 workspace；`:793` 检查原 initial File。没有等价的 terminal retry 首采样、focus RAF 新页面隔离或直接 storage 写抛错场景。

| ID | 最小红测／合同场景 | 必须观察的业务断言 | 状态 |
| --- | --- | --- | --- |
| B7.1 | 真实 terminal stream event 时有挂起 poll，证明已登记 retry timeout；立即卸载，在推进 timer／返回 poll 前首采样。再只为迟到防御交付 callback。 | 本 owner timeout、stream、读订阅立即释放；退休后不 GET、不重排 timer；不能等到 50 次自然结束再声称清理。另实例不互清，未知资源来源不能过滤。 | `NOT_RUN` |
| B7.2 | 通过实际入口排入 composer focus RAF，卸载 A 后挂另一页同 selector 的 textarea；先记录 RAF 所有权首采样，再模拟迟到回调。 | A RAF 在卸载立即取消，不夺新页焦点；迟到防御不写／导航／复活订阅。RAF 来源与 callback identity 明确，不用全局清空或等待自然执行。 | `NOT_RUN` |
| B7.3 | 直接 workspace set/remove 抛 SecurityError／quota error；分别发生在确定 create receipt 后、恢复已有 session、清理准备时。 | 不把已接受操作变成可再次 create 的普通失败；原 receipt/ID/File/draft 留内存，恢复入口可见；明确存储与刷新限制，不能借 storage 可写作为禁离开前提。 | `NOT_RUN` |

门禁：资源零与对象 GC 是不同证据；只需精确本 owner 的立即资源释放与迟到不复活，不宣称全堆已回收。

## 10. B8：历史读取、冻结原文与文档子表单

源码证据：`views/DesignerHistoryView.vue:113–132` list/search/page response 没有 request epoch，`:199` 卸载只清 debounce；`views/TaskDesignHistoryView.vue:27–32,52–63` 的 record/attachment preview 缺读取 scope。`components/DocumentSourcesPanel.vue:14–35` 已有 ID/sourceRevision generation；`DocumentClarificationForm.vue:11–25` 已有 run/requirement watcher；`DocumentSupplementForm.vue:16–40` 已有 run watcher，但都无卸载失效。后两项是写表单，不能被“只读历史”名称误归类为只读。

原测试锚点：`views/DesignerHistoryView.spec.ts:58,78,100,122,138` 为筛选及真实状态动作；`views/TaskDesignHistoryView.spec.ts:11` 为冻结记录；`components/DocumentSourcesPanel.spec.ts:8` 为 revision 变更后丢旧 body；`DocumentClarificationForm.spec.ts:10,26`、`DocumentSupplementForm.spec.ts:11,28` 覆盖部分同 key／换 run，未单独证明卸载后零 emit。源码有 guard 的场景可作为正控，不预判其必红。

| ID | 最小红测／合同场景 | 必须观察的业务断言 | 状态 |
| --- | --- | --- | --- |
| B8.1 | History 搜索 A/list/page 挂起，改筛选 B 并先返回 B，最后返回 A；另卸载后返回 list/error。 | B 列表/facets/cursor/selection 不被 A 覆盖；append 只属于原 query/cursor；卸载不更新新 owner 或复活 debounce，历史页不自动提交任何业务动作。 | `NOT_RUN` |
| B8.2 | TaskHistory ID=A 的 record、attachment preview 挂起，实际同路由换 B／重挂后交付 A；使附件 ID 相同便于发现缓存错用。 | 原 A 的 record/body/error/busy 不进 B；只读冻结身份准确；卸载后旧请求无新 owner 通知。不得只按 attachmentId 缓存跨 Task。 | `NOT_RUN` |
| B8.3 | DocumentSources 已发目录/body 后卸载；Clarification/Supplement 已发原写或读取 options 后退休、再交付响应。 | 读回不更新退休 owner，写回不 emit 到新页、不清新草稿／锁；已有 prop/revision 变更 guard 仍保持。写 unknown 正常导航先被 B3/组合 policy 阻断，强制退休仅作迟到防御。 | `NOT_RUN` |

门禁：W0 完成 scope 映射及基线反例／既有覆盖分类；不能等 React 页落地后才发现读取串页。后续消费页必须复用同一已验 owner。

## 11. B9：endpoint 能力映射与禁止盲重试

源码证据：`api/client.ts:1704–1705` createTemplateTask 接收原 requestKey，start 只有 taskId、没有通用 key；`:1693,1697–1699` batch retry 有 batch ID/expectedVersion，没有通用 requestKey；`:1949–1959` question/compiler/redesign及部分 message 没有通用 key；`:1960–1964` multipart context-turn 有 submissionId；`:1794` 诊断 recover 有真实 commandId。`api/workflowRuns.ts:17` 的需求 create 有 key，`api/client.ts:1665` 是文档 create by-request GET，两者不能混用。现有 `stores/templateTaskStore.ts:19–21` 显式重试复用 taskId 的 start 行为需要沿后端实际状态合同解释，不能擅自认定其必不幂等或自动安全。

原测试锚点：`stores/templateTaskStore.spec.ts:10,20` 检查原 taskId/create key；`components/TemplateBatchRecoveryPanel.spec.ts:43,61,77` 检查 conflict、不自动 resend、实际版本和卸载后不 restart poll；`components/TemplateSessionDiagnosticsPanel.spec.ts:60,77,112` 检查 commandId／人工停止与 transport check；`components/workflow/command.spec.ts:14,20` 检查已有幂等命令。`src/test/java/io/opencode/loopper/service/workflow/WorkflowPlanTemplatesIntegrationTest.java:50–55` 检查另存模板原 key replay、不同 body 拒绝，不能把该 server 合同推广到所有 endpoint。

| ID | 最小红测／合同场景 | 必须观察的业务断言 | 状态 |
| --- | --- | --- | --- |
| B9.1 | 给 start、batch retry、question/message 等无通用 key 的 endpoint 注入 unknown，再重复 render/StrictMode/读刷新/主题切换及打开恢复入口。 | 无自动 mutation；不制造 requestKey／commandId 字段。先读原 ID/version/状态，只有该 endpoint 合同明确支持的用户动作才可继续；无法确定则 BLOCKED，说明能力限制。 | `NOT_RUN`（能力合同测；不预设基线必红） |
| B9.2 | 给实际有 key 的 create／multipart／diagnostic recover 丢回执；已证明 UI 可编辑的入口才经真实输入改变草稿并显式恢复。禁改页先证明禁改，再仅在纯 owner 负控改变外部 draft projection；另让原 key 搭改变后的 body 作为服务端负控。 | 不解除 disabled 或伪造禁用控件事件来制造 UI 路径；原恢复完全相同身份/body/Files，后编辑仅在可达入口验证。服务器拒绝不同 digest，不通过换新 key 绕过；accepted 后只恢复真实读取／导航阶段（New 仅导航）。endpoint 没有某字段时测试不虚构它。 | `NOT_RUN` |
| B9.3 | createTemplateTask 已确定 taskId、start unknown；batch retry unknown 后读回版本前进／仍阻断；诊断 recover accepted 后读失败。 | create 次数不再增加；按后端 lifecycle/CAS/现有读入口映射每个恢复阶段，区分显式继续原任务与新执行；diagnostic commandId 原样，accepted 不重发。保留独立状态和停止证明。 | `NOT_RUN` |

门禁：B9 首先是能力表准入，不能写“统一同 key 重试已修复”。若实际 endpoint 没有恢复能力，记录确切限制并阻断不安全动作；需要新增服务端协议时单独授权和验收，不能由 JSX/controller 接口虚构。

## 12. 生产开发前的结清要求与证据格式

1. **先完成映射。** 对 B1–B9 每个真实动作记录 route/entity/owner、mutation/读取 endpoint、授权 header、key 与 payload/File 捕获、CAS 版本域、receipt阶段、可恢复 read／显式重投能力、离开 policy 和订阅退休边界。缺能力留 BLOCKED，不以任一原型或其它 endpoint 兜底。映射和行为门槛在生产迁移开发前结清。
2. **再取得基线证据。** 新增有实际入口的最小合同测试，真实 router guard 和确定性 API/SSE/deferred fixtures 控制先后顺序；无真实付费模型或外发。保留 source commit、测试源码 hash/行号、实际命令、exit、失败断言、请求序列及原 key/body/File 身份。若断言原本通过，记既有 GREEN，不把未复现风险升级为确定缺陷。
3. **确认缺陷后最小修复。** 先修纯 TS owner／Vue delegate 共用协议，再让 React 页消费；不删高级操作、暂停、下载、上传、版本冲突、深链或历史恢复来过测。每项取得 red→green 与普通 dirty、正常成功、自有 receipt 交接、无 mount 自动写的负控；由非作者独立审查。
4. **再进入相应迁移波次。** W1 冻结通过的 controller 接口；W2/W3/W5 真 React 页面重做受影响行为集成，W6 才原子切唯一 history/入口并去 Vue。回退只在安全进入点选择视图，未确认写不得强制销毁 owner。W0 通过不等于后续 React DOM／浏览器／最终零 Vue 已通过。

每个场景须回填同一条证据记录：

| 字段 | 要求 |
| --- | --- |
| `caseId / classification / contract` | 本文场景 ID；源码差异／复现缺陷／能力约束／已有覆盖分开；引用已决定合同。 |
| `source / originalTest / newTest` | 实际 commit、文件、行号／hash；原测试覆盖范围和此次新增断言分开。 |
| `baselineResult / repairResult` | 实际命令、exit、日志、请求序列；尚未执行保持 `NOT_RUN`。不以文档修订提交填写 repairResult。 |
| `identityProof` | 原 scope/epoch/path、key/body、expectedVersion/revision；File 实例、顺序与真实 bytes/hash（无密钥／私密正文）。accepted receipt/ID 与只读恢复计数。 |
| `navigationProof` | 导航前后 route、guard 结果、NavigationFailure/throw 区分；取消后 draft/operation 仍可见；blocked 后没有新写。 |
| `resourceProof` | 活动见证、owner/callback identity、卸载首采样在任何自然完成／flush 前；迟到防御另测。未知来源先查归属，不放宽过滤。 |
| `review / remainingCapability` | 非作者复核、未覆盖项及真实刷新限制；不能用“store 保留”代替 receipt/File/导航证据。 |

建议后续证据归档到 `docs/design/react-full-migration/evidence/w0/<caseId>/` 或专门的本地证据目录；**本轮未创建这些结果目录，也没有 RED/GREEN 运行产物**。当前唯一完成项是源码／原测试映射及本台账；28 个场景均待跑，B1–B9 未关闭，生产迁移准入尚未据此通过。
