# W0 非作者复核：Workflow／Designer 与恢复合同

复核者 `/root/react_legacy_canvas`，被审测试作者 `/root/react_flow_workflow`，恢复合同作者为组长。沿用原显式启动记录 gpt-6.1-sol / xhigh，工具没有实时平台配置字段。只读原生产、A 测试/结果和原日志；只新建本复核文档，没有重跑 A／浏览器／全量，也没有把本人 B 组 58 项作为非作者行为证明。

基线 `008542f0bf02dc1c1b76f1e75429aab8452b797d`。结论：A 的原完整双文件 **53 subtests=8 PASS/45 FAIL、exit1** 与原 JSON 一致；后续同一 B1.4 test 补 SENDING→UNKNOWN 禁改断言，单项实际1PASS/exit0，31项因名称过滤未执行（JSON标skipped，不计通过），定义总数53未增加；最新完整候选由组长集中重跑。16 父 case=15 REPRODUCED_FAIL/1 PASS，15 partial/1 分层最小 complete。未发现本批次仍把夹具首错计成产品失败，未看到 skip/drop/disabled emit 绕控件或用旧只读组件绿替代真实 guard。正确合同红仍阻断对应实现验收，本复核不是 W1 生产放行。

## 逐组核对

| Case | 实际入口与证据 | 复核结果及不能推广的边界 |
| --- | --- | --- |
| B1.1 | `workflow-w0.spec.ts:106`；实际 RouterView + createMemoryHistory，发送/未知 × push/replace/back；New `:22` 原 guard | 六红是真实已离开，不是 router mock；dirty confirm=false/true 正控合法。没有证明后台重复创建，也没穷举 forward/query/同实体所有出口 |
| B1.2 | test `:123`；真实 Library action dropdown copy/archive；过滤→显式重试 `:133` | 四 pending route 红、copy body/key 保留绿吻合；archive 重试/复制已接受导航失败尚未测，报告未冒称完整 |
| B1.3 | test `:141–156`；真实 beforeEach false、异步 gate+竞争 replace、throw | VueRouter resolve NavigationFailure 与 reject 分开，前两失去恢复入口为红，reject 为绿；没有实际点击恢复或证明其它 owner 可被安全 bypass |
| B1.4 | test `:159–195`；title/objective + settings 按钮 actual disabled；用户“重试创建”同 body 引用；真实 `createAcknowledgedOperation` 分段 | 新 UI 两阶段（SENDING/UNKNOWN）禁改负控合法，单项回执/原四字段与同body对象重试经独立raw核对；没有先去 disabled 再强改。纯 owner projection 控制由测试捕获 const body，因此证明调用者冻结快照/accepted-write-read 分离，不单独证明 New 在可编辑状态下冻结 payload；作者已有明确分层说明 |
| B2.1 | test `:225` 六 panel 真按钮发 API，exposed canLeave；test `:233` Requirement+Finish 真父 route | 12 direct canLeave 红＋1 真父 route 红可信。其它5 panel的关面板/切 node/attempt/换 requirement 组合未运行，不能把 canLeave 调用等同这些实际路由 |
| B2.2 | test `:242–249`；actual command stop 原 req/review/run、metadata.commandVersion19→body.expectedVersion19 | 原请求两次一样、零新start成立；禁离开红。未测 model stop/new attempt/真实 stop proof，不可说两个 runtime 分支都覆盖 |
| B2.3 | test `:251–262`；Finish POST accepted→finishStatus GET失败→“刷新操作结果” | read-only retry 计数与 canLeave 红分开；其它五入口/实际父 route/新版本下一合法动作未测。没有把写前版本当本人成功回执冲突 |
| B5.1 | `designer-w0.spec.ts:69–88` 初始/后续 text、仅File 的真实输入/drop，生产Designer `:1173–1179` | 四 route 红仅证明普通 dirty未覆盖并静默离开；没有断言 File已被GC或字节永久丢失。保存无draft页安全离开正控一致 |
| B5.2 | test `:89–101` 两真实 multipart入口 sending/unknown | 四红保API arg身份证据，原 mutation1；无File message/回退/back 尚未测，不可把四项冒称所有 Designer actions |
| B5.3 | test `:102–118`；confirmDraft→真实 taskStore 使用 getTaskOverview/getTaskAudit、Task已存在→导航guard false/throw | 最终首错已到恢复入口和新表单断言，旧夹具 getTask错误不沿用；store仍有accepted Task，不是后台重复create证明。恢复点击/读取失败/竞争取消待补 |
| B6.1 | test `:121–138`；真实问题推荐answer POST，强制旧root退休/new独立B | 迟到后新增GET A是旧owner复活读取，未证明B state/busy被改。mandatory=true导致拒绝按钮不存在，UNREPRODUCED_OR_UNREACHABLE负控而非伪造reject事件，处理正确 |
| B6.2 | test `:139–154`；真实“修改设置”/large-task开关/保存，pending preview或modal后卸载 | 两次 retired A仍update是可观察真正后续写，区别于只读旧ref变化；同owner切新session/profile版本、accepted-readfail仍待测。`DesignerView.vue:483–505` 缺 await后scope重核与结果一致 |
| B6.3 | test `:155–175`；已证明composer可编辑，再真实改text/drop extra File；实际发送/接收 | Multipart同submissionId而content/Files变化及后来draft被清，原File reference断言有效；inflight无key requirement-message响应清后来text单独红。没有把无key分支当submissionId恢复。多File序/bytes完整变化仍是partial |
| B7.1 | test `:177–194`；actual SSE COMPLETED激活 terminal retry timeout，callback名字+identity归属；无advance/回执前第一快照 | 实际owned计数1→1红，stream close1；后续才释放read/check迟到新read。该账本不声称全部timer或堆回收 |
| B7.2 | test `:195–211`；actual edit入口排RAF，真实route退出严格sample后才手动交付旧callback | 首样owned1未cancel红＋确实聚焦新页同selector红；没有自然callback补清或全局清ledger。mock RAF不是Chromium/GC证据 |
| B7.3 | test `:212–227`；actual createaccepted后storage setItem SecurityError | “无法创建设计草案”误导错误红，composer仍在/Task未被证明丢失；显式session GET恢复绿不能当全storage get/remove、File跨刷新恢复 |

API module mocks只是 transport 函数的调用参数证据；真实HTTP path/body/header关系由现 `api/workflowRuns.ts`、workflowPublication/Push/Writeback API和client静态核对。A 并未动态跑 client编码或 Java、数据库/权限/stop side effects，本复核不替它增加这层证据。

## 组长恢复合同核对

被审：[recovery-and-leave-contract.md](recovery-and-leave-contract.md)。`:7–13` BLOCK/CONFIRM_DISCARD/ALLOW及NavigationFailure保持receipt、`:33–37`原identity/scope/存储边界与本轮批准合同一致；beforeunload不保证硬刷新恢复已明示。确定写后回读/导航失败不重写，与实际 acknowledgedOperation `:14–18` 一致，尚不说明各消费页守卫已满足。

`:19` New仅现 create(requestKey/digest)显式相同POST恢复，accepted只nav原receipt；`workflowRuns.ts:17`与`WorkflowPlans.java:51–54`静态对映正确，没有 New by-request GET。`:21` 普通Template start只有原TaskID，无requestKey，必须先现 Task读确认；本人 B9 unchanged start2只是原ID重用正控，不满足完整安全恢复，不冲抵该要求。`:24–27` batch expectedVersion/CAS、diagnostic commandId、context-turn submissionId与无key question/message/profile分开是必要且准确的能力边界，没有发明通用重放接口。

两处原文字歧义已报组长并由组长修订；本次只读确认关闭，不改作者文件：

1. **已关闭字段歧义：`:20` processVersion。** 全 `frontend/src` 和本次 workflow 服务静态检索没有该字段；`workflowRuns.ts:40–41` commandAction/modelAction的公开 body 都是 `expectedVersion`，值分别来自原 `commandVersion`／`modelVersion`（A 当前 test `:246`明确19）。最新合同已去掉该字段要求，并明确 metadata.commandVersion/modelVersion→实际body.expectedVersion，不新增DTO；与静态源码一致。
2. **已关闭 accepted lookup边界：`:22` 文档 by-request GET。** 正文已正确限制“只这个endpoint有GET”和File不可持久化；最新合同已明确GET404未确认、GET200识别原run后进入accepted仅读/原ID导航且不再create，安全找到receipt后的新意图与UNKNOWN分开。这防止把已知接受后的明确新意图误记UNKNOWN红。本人早先B3.4该误推断已在final-v5实际ApiError404/GET200两分支及Files哈希/序独立纠正，旧错误证据没有沿用。

该合同未把 runtime preference变更当正在销毁owner；现画布runtime实例固定，实际产生owner退出的路由/回退路径才执行leave策略。无key动作不能因为“用户点击重试”就盲重发，仍须现有权威读/明确服务端支持；如无法确认就保留本实例身份和限制说明。没有要求增加未授权后端能力。

## 证据一致性与交还

A 原输出 `/workspace/react-full-w0-evidence/workflow-designer-final.{log,json}` 的hash与作者结构化账本一致；所有当前 `frozenSourceSha256` 路径经SHA校验未见不符。作者已声明原聚焦产物先于最后类型/夹具DTO补齐、当前冻结source hash分列，不伪称字节完全同一版本。B1.4新字节的单项raw/log/hash已独立核对；最新完整53与全量集成由组长持有，本复核没有拼旧53与新1作为新全量实跑。

| 证据 | SHA-256 |
| --- | --- |
| workflow-w0.spec.ts 当前冻结 | `471be59eddd217fb3a398e53d4ab70587baaf1d1c923fcffeb21768445bc65ae` |
| designer-w0.spec.ts 当前冻结 | `0cbbf181235a1ffc3af7477b1e7692df959b07414c903afaa96555b3960f7556` |
| A 原log | `fb1da32275bf13a2cfe818a35084d855350741ce6aee73d923b2341c5c5308b0` |
| 新B1.4单项log | `a18273fd030effdf9eee27ce7198def872b49ccf93e46aa07084659873d025c4` |
| 新B1.4单项JSON | `ee229658c983d238f0726bcc0ff8fb2aa27581d24a600e960ec33a9774acb0a3` |
| A 原JSON | `f36393bd41d78d99956c69e9c0b162859cafe774dac929f663a5b53b7be9059b` |

复核范围通过的是证据方法、统计、真实UI/guard和能力分层；不是把A的45红关闭，亦不是全部28case/React/Ant/浏览器/后端验收通过。未测项以[A报告](workflow-designer-results.md)和本表保留，唯一本复核文档归还组长。

用户最新范围取消所有窄屏设计/适配/验收；本轮已存在旧窄屏断言和先前运行只作为历史记录保留，本复核不把它们列作后续准入、不删原断言、不扩大复跑。后续原型仅本地桌面五类/三皮肤，仍不能当真实业务验收。
