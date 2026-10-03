# W0 Workflow／Designer 实测与独立审查

基线 `008542f0bf02dc1c1b76f1e75429aab8452b797d`；作者 `/root/react_flow_workflow`，原显式启动记录 `gpt-6.1-sol / xhigh`，本轮工具不能实时读取平台配置。只新增两份W0测试及本文／相邻JSON；生产、后端和依赖未由本作者修改，未启动浏览器／服务、构建、提交或外发。

全局矩阵仍是 **28 个父case**，本作者负责其中 **16 个**。此前完整双文件聚焦实际 **53 子测试：8 PASS／45 FAIL／0 skip，exit1**（新增两阶段禁改断言后，仅B1.4 UI单test已另实际复跑；最新完整53由组长重做）；16父case分为 **15 REPRODUCED_FAIL／1 PASS**，覆盖 **15 partial／1 complete（B1.4分层最小合同）**。复合父case红不代表所有变体都跑，PASS负控也不把不可达入口升级为已验证功能。没有业务修复，未关闭生产准入。

## 运行及证据边界

运行cwd：`/workspace/opencode-loopper-react-full/frontend`。实际命令：

```sh
./node_modules/.bin/vitest run src/w0/workflow-w0.spec.ts src/w0/designer-w0.spec.ts --maxWorkers=1 --reporter=verbose --reporter=json --outputFile.json=/workspace/react-full-w0-evidence/workflow-designer-final.json > /workspace/react-full-w0-evidence/workflow-designer-final.log 2>&1
```

完整请求序列、导航、断言及各子测试结果见[JSON台账](workflow-designer-results.json)；原始log/report在 `/workspace/react-full-w0-evidence/workflow-designer-final.{log,json}`，hash记录在JSON。只有transport/API/SSE与展示投影mock；实际VueRouter、页面guard、业务command、Pinia、问题卡／设置选择和用户按钮仍执行。fetch未mock时明确拒绝，不访问后端或模型。

该聚焦报告产生于最后测试类型／DTO补齐之前：projects cursor/facets、DesignerAppendResult.notice、Promise<never>与timer handle联合类型已依组长typecheck补齐；所有合同断言未改，按组长要求不另浪费聚焦重跑。JSON分列**实际run产物hash**与**当前冻结测试／生产源码hash**，不冒称两者为逐字节同一测试版本；此前组长全量发生在B1.4此次加强之前，不作为新字节证明；新的冻结源码最终全量由组长重做并集成。

B1.4补强后实际执行1项、PASS1／FAIL0、exit0；另外31项只是名称过滤未执行，没有新增skip代码、不计通过，不将两次运行拼成新53项全量。该单项JSON/log为 `/workspace/react-full-w0-evidence/workflow-b1-4-locked-final.{json,log}`，SHA-256及对应当前Workflow测试源码hash见相邻JSON。实际命令：

```sh
./node_modules/.bin/vitest run src/w0/workflow-w0.spec.ts --testNamePattern='sending then unknown disables all four' --maxWorkers=1 --reporter=verbose --reporter=json --outputFile.json=/workspace/react-full-w0-evidence/workflow-b1-4-locked-final.json > /workspace/react-full-w0-evidence/workflow-b1-4-locked-final.log 2>&1
```

最初B5.3空stages导致确认不可达、第二轮错误getTask入口只是夹具首错，均剔除产品判断。最终B5.3的getTaskOverview硬断言通过后在恢复入口115／新表单116红。B6.1 reject因生产mandatory=true不能触发，保留真实不可达负控，不合成reject事件。

## 已冻结业务合同

本地 SENDING／UNKNOWN／ACCEPTED_READBACK 不得用普通确认离开；同一owner持原身份、版本、完整body/key和File，用户显式恢复，禁止自动重发或换key。确定receipt以后只恢复已失败的读取或导航；成功写推进版本是合法事实。普通dirty保留取消／明确放弃语义。没有现有持久化／服务端支持时不承诺跨刷新恢复，文件名不等于字节或实例证明。New unknown无by-request GET，恢复是明确同key/body POST；确定receipt after只有导航。静态原型mock查询与文档修正不是协议通过／业务修复。

## 16个父case与实际覆盖

### B1.1 New 未确认 create 禁止离开

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/workflow-w0.spec.ts:106`。

源码：`frontend/src/views/WorkflowRequirementNewView.vue:15`、`frontend/src/views/WorkflowRequirementNewView.vue:20`、`frontend/src/views/WorkflowRequirementNewView.vue:22`、`frontend/src/views/WorkflowRequirementNewView.vue:25`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/WorkflowRequirementNewView.spec.ts:57`、`frontend/src/views/WorkflowRequirementNewView.spec.ts:63`。

合同：SENDING/UNKNOWN 必须硬拒绝 SPA 离开；普通 dirty 取消保留，显式放弃可离开。

实测：6项 pending 导航都实际离开（push/replace→/away，back→/previous），create均仅1次；普通 dirty 取消/明确放弃对照通过。

实际变体：

- sending/unknown × push/replace/back：6 FAIL
- 普通 dirty confirm=false 保 title；confirm=true 允许且create0：1 PASS

仍未跑：

- 同路由换实体/query及原 operation 在导航受阻后的完整恢复
- 取消后全部4字段与跨刷新恢复（New不提供该承诺）

界限：真实memory-history RouterView/生产guard；只证明离开与owner销毁入口，不扩成已证明后台重复create。

### B1.2 Library copy/archive pending 离开

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/workflow-w0.spec.ts:123`。

源码：`frontend/src/views/WorkflowLibraryView.vue:22`、`frontend/src/views/WorkflowLibraryView.vue:26`、`frontend/src/views/WorkflowLibraryView.vue:31`、`frontend/src/views/WorkflowLibraryView.vue:32`、`frontend/src/views/WorkflowLibraryView.vue:47`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/WorkflowLibraryView.spec.ts:38`、`frontend/src/views/WorkflowLibraryView.spec.ts:46`。

合同：未确认copy/archive禁离开；过滤不改变原body/key，未知仅用户显式恢复。

实测：copy/archive各sending/unknown均导航到/away；copy unknown改变真实“我的流程”筛选后重试保相同请求。

实际变体：

- copy/archive × sending/unknown：4 FAIL
- copy unknown→实际filter切换→重试原操作，body/key相同：1 PASS

仍未跑：

- archive筛选/显式恢复
- replace/back/同路由变化；copy已接受但目标导航失败
- 复制回执的只导航恢复入口

界限：原copy body/sourceRevision与archive expectedVersion、requestKey逐调用写入日志；没有实际后端。

### B1.3 New 确定回执导航失败

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/workflow-w0.spec.ts:141`。

源码：`frontend/src/views/WorkflowRequirementNewView.vue:20`、`frontend/src/views/WorkflowRequirementNewView.vue:22`、`frontend/src/components/workflow/command.ts:22`、`frontend/src/domain/acknowledgedOperation.ts:15`、`frontend/src/domain/acknowledgedOperation.ts:18`。原测试锚点（此次未由作者单独重跑）：`frontend/src/components/workflow/command.spec.ts:14`、`frontend/src/components/workflow/command.spec.ts:20`、`frontend/src/components/workflow/command.spec.ts:25`。

合同：NavigationFailure与reject均保确定receipt；只导航到原ID，不能重新create。

实测：真实guard=false及竞争导航取消均丢“打开已创建的需求”入口并重新显示submit；reject保入口。每项create仅1次。

实际变体：

- 真实guard false：FAIL
- 异步guard+真实竞争replace导致取消：FAIL
- 真实guard throw/reject：PASS

仍未跑：

- 失败后实际点击恢复、确认最终URL与create不增加
- 成功receipt交接不得绕其他owner pending guard

界限：不是所有导航失败都丢receipt；VueRouter正常resolve NavigationFailure与promise reject分开。New after只有导航，没有by-request GET。

### B1.4 New 禁改负控／纯owner冻结

**PASS；coverage=complete**。新测试：`frontend/src/w0/workflow-w0.spec.ts:159`。

源码：`frontend/src/views/WorkflowRequirementNewView.vue:19`、`frontend/src/views/WorkflowRequirementNewView.vue:54`、`frontend/src/views/WorkflowRequirementNewView.vue:59`、`frontend/src/views/WorkflowRequirementNewView.vue:61`、`frontend/src/views/WorkflowRequirementNewView.vue:62`、`frontend/src/domain/acknowledgedOperation.ts:14`、`frontend/src/domain/acknowledgedOperation.ts:18`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/WorkflowRequirementNewView.spec.ts:57`、`frontend/src/components/workflow/command.spec.ts:14`、`frontend/src/components/workflow/command.spec.ts:20`。

合同：New SENDING／UNKNOWN均锁4字段，SENDING禁止retry；外部projection变化不得改冻结body/key；accepted恢复只能后半段。

实测：deferred create的真实SENDING与reject后的UNKNOWN，两阶段分别检查title、objective、独立project和template真实按钮均禁改；SENDING retry禁用且create1，UNKNOWN retry可用且尚create1；只有显式retry才create2、原body对象与key不变。原纯owner projection／accepted只读对照此前通过，本次名称过滤没有重跑。

实际变体：

- 真实New deferred SENDING→UNKNOWN两阶段4字段禁改＋retry状态／create计数＋用户明确同body/key恢复：当前单test PASS
- 纯owner外部draft projection变化＋accepted/readfail恢复：此前完整53运行 PASS，本次过滤未重跑

界限：按W0分层最小合同完成。两阶段只观察实际控件，没有解除disabled、DOM force或伪造select emit；单项新绿与旧完整53分列，不冒称全部当前字节已重跑。纯owner不能冒称真实New可编辑。实际accepted GET入口补证在B2.3；New只导航。后来草稿仅B6.3可达入口验证。

### B2.1 六嵌套panel guard及Finish父页组合

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/workflow-w0.spec.ts:225`。

源码：`frontend/src/components/workflow/WorkflowNodeRun.vue:90`、`frontend/src/components/workflow/WorkflowNodeRun.vue:109`、`frontend/src/components/workflow/WorkflowCandidates.vue:30`、`frontend/src/components/workflow/WorkflowFinish.vue:40`、`frontend/src/components/workflow/WorkflowPublicationCommit.vue:32`、`frontend/src/components/workflow/WorkflowPush.vue:42`、`frontend/src/components/workflow/WorkflowWriteback.vue:33`、`frontend/src/views/WorkflowRequirementView.vue:212`、`frontend/src/views/WorkflowRequirementView.vue:215`。原测试锚点（此次未由作者单独重跑）：`frontend/src/components/workflow/WorkflowNodeRun.spec.ts:33`、`frontend/src/components/workflow/WorkflowNodeRun.spec.ts:48`、`frontend/src/components/workflow/WorkflowNodeRun.spec.ts:90`、`frontend/src/components/workflow/WorkflowCandidates.spec.ts:13`、`frontend/src/components/workflow/WorkflowFinish.spec.ts:27`、`frontend/src/components/workflow/WorkflowFinish.spec.ts:57`、`frontend/src/components/workflow/WorkflowPublicationCommit.spec.ts:21`、`frontend/src/components/workflow/WorkflowPublicationCommit.spec.ts:40`、`frontend/src/components/workflow/WorkflowPush.spec.ts:22`、`frontend/src/components/workflow/WorkflowPush.spec.ts:42`、`frontend/src/components/workflow/WorkflowWriteback.spec.ts:27`、`frontend/src/components/workflow/WorkflowWriteback.spec.ts:45`、`frontend/src/views/WorkflowRequirementScope.spec.ts:153`、`frontend/src/views/WorkflowRequirementScope.spec.ts:169`。

合同：每个未确认嵌套owner hard block；不能用confirm放弃pending。实际父页组合也须保owner。

实测：六真实panel经真实按钮发POST，sending/unknown × confirm=true的exposed canLeave均true；实际Requirement+Finish unknown route也到/away。

实际变体：

- human complete/candidate reject/finish/commit/push confirm/writeback confirm × sending/unknown：12 FAIL（真实panel，direct canLeave）
- 实际Requirement父页+Finish子panel unknown→router.push：1 FAIL

仍未跑：

- 其它5panel真实父route/关闭panel/切node/attempt/换requirement组合
- WorkflowSaveTemplate locked对照与各panel无pending普通draft确认
- 其它动作（candidate accept等）

界限：13子测试不能宣称6×全部组合均跑；直接canLeave不是实际route证据。日志含每个实际原body/key/CAS；parent真实route仅Finish。

### B2.2 原process stop身份与禁离开

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/workflow-w0.spec.ts:242`。

源码：`frontend/src/components/workflow/WorkflowNodeRun.vue:100`、`frontend/src/components/workflow/WorkflowNodeRun.vue:106`、`frontend/src/components/workflow/WorkflowNodeRun.vue:109`。原测试锚点（此次未由作者单独重跑）：`frontend/src/components/workflow/WorkflowNodeRun.spec.ts:41`、`frontend/src/components/workflow/WorkflowNodeRun.spec.ts:48`、`frontend/src/components/workflow/WorkflowNodeRun.spec.ts:71`、`frontend/src/components/workflow/WorkflowNodeRun.spec.ts:77`。

合同：unknown stop保原req/node/attempt/version/key且禁离开；无新start/重叠writer。

实测：实际command stop未知后显式retry，两请求req/review/run/stop、commandVersion19与key相同；canLeave=true违反禁离开。

实际变体：

- command stop unknown→显式重试保原身份／commandVersion19：PASS子断言；随后canLeave：FAIL

仍未跑：

- model stop/version
- unknown期间attempt/权威状态变更
- 实际父route、backend停止证明及无重叠writer

界限：只验证command分支；body两次相同不证明后端停止。

### B2.3 accepted-write/readback 分离

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/workflow-w0.spec.ts:251`。

源码：`frontend/src/components/workflow/WorkflowFinish.vue:16`、`frontend/src/components/workflow/WorkflowFinish.vue:24`、`frontend/src/components/workflow/WorkflowFinish.vue:35`、`frontend/src/components/workflow/WorkflowFinish.vue:38`、`frontend/src/components/workflow/WorkflowFinish.vue:40`、`frontend/src/domain/acknowledgedOperation.ts:14`、`frontend/src/domain/acknowledgedOperation.ts:18`。原测试锚点（此次未由作者单独重跑）：`frontend/src/components/workflow/WorkflowFinish.spec.ts:34`、`frontend/src/components/workflow/WorkflowFinish.spec.ts:40`、`frontend/src/components/workflow/WorkflowNodeRun.spec.ts:48`。

合同：accepted-readback保持禁离开；显式恢复只GET，mutation始终1，合法新版本读回。

实测：真实Finish POSTaccepted、finishStatus GET失败后提供“刷新操作结果”；canLeave=true为红。随后恢复仅finishStatus GET，finish POST仍1。

实际变体：

- 实际Finish accepted→read failure→canLeave：FAIL；继续仅读恢复/POST1：PASS子断言

仍未跑：

- 其它5panel accepted-readback
- 真实父route/关闭panel
- 读回版本推进用于下一合法操作

界限：恢复计数已测，下一操作新revision合法性未测，不能证明全部六动作accepted流程。

### B5.1 Designer普通text/File dirty覆盖

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/designer-w0.spec.ts:69`。

源码：`frontend/src/views/DesignerView.vue:81`、`frontend/src/views/DesignerView.vue:82`、`frontend/src/views/DesignerView.vue:117`、`frontend/src/views/DesignerView.vue:207`、`frontend/src/views/DesignerView.vue:1173`、`frontend/src/views/DesignerView.vue:1175`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/DesignerView.spec.ts:793`、`frontend/src/views/DesignerView.spec.ts:937`、`frontend/src/views/DesignerView.spec.ts:969`、`frontend/src/views/DesignerView.spec.ts:1831`。

合同：LoopSpec不dirty时未发送text/File也须confirm；取消保留，明确放弃才离开。保存只读无draft可离开。

实测：initial/followup text/File四种入口confirm拒绝配置但confirm0，真实route到/away；保存只读会话无draft离开无写通过。

实际变体：

- initial-text/followup-text/initial-file/followup-file：4 FAIL
- 保存无draft只读session可离开/no-confirm/no-create：1 PASS

仍未跑：

- 修复后取消保File原实例/bytes/顺序（当前route已离开仅证明静默离开）
- 各4draft显式放弃true路径
- 组合File+text/重选顺序

界限：text保留使用textarea.value；File当前后续断言仅UI original.txt。不能宣称已证明File GC或字节丢失。

### B5.2 Designer pending写入禁离开

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/designer-w0.spec.ts:89`。

源码：`frontend/src/views/DesignerView.vue:88`、`frontend/src/views/DesignerView.vue:92`、`frontend/src/views/DesignerView.vue:1173`、`frontend/src/views/DesignerView.vue:1175`、`frontend/src/views/DesignerView.vue:1308`、`frontend/src/views/DesignerView.vue:1348`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/DesignerView.spec.ts:793`、`frontend/src/views/DesignerView.spec.ts:937`、`frontend/src/views/DesignerView.spec.ts:969`。

合同：initial/followup写sending/unknown不得confirm放弃；原body/submissionId/File留原owner。

实测：两真实context-turn入口各sending/unknown，mutation1并含原submissionId/File，全部route到/away。

实际变体：

- initial/followup multipart context-turn × sending/unknown：4 FAIL

仍未跑：

- 无File message分支sending/unknown
- 重新开始/视图回退/replace/back
- 实际阻断后的File原实例/顺序与body保留

界限：READ_ONLY/readOnly控制runtime；REVIEWING+DISCUSSING_REQUIREMENT composer依生产380–389实际可编辑。无disabled破坏。

### B5.3 Designer确定Task交接恢复

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/designer-w0.spec.ts:102`。

源码：`frontend/src/views/DesignerView.vue:1091`、`frontend/src/views/DesignerView.vue:1093`、`frontend/src/views/DesignerView.vue:1288`、`frontend/src/views/DesignerView.vue:1292`、`frontend/src/views/DesignerView.vue:1300`、`frontend/src/stores/taskStore.ts:193`、`frontend/src/stores/taskStore.ts:238`、`frontend/src/stores/taskStore.ts:242`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/DesignerView.spec.ts:1738`。

合同：确定TaskID交接guard false/reject后保receipt恢复；只nav/read，不再confirm/create。

实测：两variant都确认1、真实getTaskOverview(original taskId)已调用且store有accepted-task；workspace清空，恢复入口缺失，重新显示初始目标textarea。

实际变体：

- 真实Task目标guard=false：FAIL（恢复入口/新表单断言）
- 真实Task目标guard throw/reject：FAIL（同断言）

仍未跑：

- 竞争导航取消
- 恢复后实际nav原Task且confirm始终1
- Task读取失败及后续显示失败Task状态

界限：最终getTaskOverview+getTaskAudit真实transport通过，不计旧getTask0夹具失败。store仍保Task，不夸大为后台重复创建或所有receipt丢失。

### B6.1 退休question回调隔离

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/designer-w0.spec.ts:121`。

源码：`frontend/src/views/DesignerView.vue:779`、`frontend/src/views/DesignerView.vue:783`、`frontend/src/views/DesignerView.vue:790`、`frontend/src/views/DesignerView.vue:798`、`frontend/src/views/DesignerView.vue:1812`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/DesignerView.spec.ts:1128`。

合同：退休A后回执不得复活A读/影响B；可达reject modal过期确认须零新写。

实测：实际question推荐answer POST在途，强制退休A、实际新建独立B后交付A回执又GET A；mandatory=true真实UI无reject按钮，reject0/confirm0。

实际变体：

- 实际reply→强制退休A→独立B→late receipt：FAIL（退休后新GET A）
- mandatory question真实UI reject不可达负控：UNREPRODUCED_OR_UNREACHABLE（测试PASS）

仍未跑：

- 可达reject modal入口（当前生产无此入口，不强造）
- 同owner已切B时question/busy是否污染；各version变化

界限：强制root retirement是迟到防御，不代替正常pending禁导航。B为独立owner；仅证明A退休后新读，没有证明B被改。

### B6.2 profile preview/modal退休后写

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/designer-w0.spec.ts:139`。

源码：`frontend/src/views/DesignerView.vue:472`、`frontend/src/views/DesignerView.vue:483`、`frontend/src/views/DesignerView.vue:494`、`frontend/src/views/DesignerView.vue:501`、`frontend/src/views/DesignerView.vue:505`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/DesignerView.spec.ts:310`、`frontend/src/views/DesignerView.spec.ts:358`。

合同：每个await后重新确认scope/version；未发写的退休preview/modal不得继续update。

实测：实际修改设置/largeTask switch/保存设置；挂起preview或confirm modal，退休A后返回/确认，两项均向A执行update（profile version7、largeTask=true）。

实际变体：

- preview挂起退休→resolvepreview+modal：FAIL
- modal挂起退休→resolveconfirm：FAIL

仍未跑：

- 同session TaskProfile版本推进/同owner换session
- accepted update后readback失败仅读
- 409保留选择与成功version推进

界限：只证明退休后仍POST原A，未证明错写B；modal由真实ElMessageBox入口mock异步决定。

### B6.3 follow-up冻结multipart及后编辑草稿

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/designer-w0.spec.ts:155`。

源码：`frontend/src/views/DesignerView.vue:380`、`frontend/src/views/DesignerView.vue:1324`、`frontend/src/views/DesignerView.vue:1325`、`frontend/src/views/DesignerView.vue:1331`、`frontend/src/views/DesignerView.vue:1339`、`frontend/src/views/DesignerView.vue:1340`、`frontend/src/views/DesignerView.vue:1342`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/DesignerView.spec.ts:793`、`frontend/src/views/DesignerView.spec.ts:937`、`frontend/src/views/DesignerView.spec.ts:969`。

合同：同submissionId恢复原body/File/顺序；原写成功不能清后来未发送草稿。

实测：unknown后真实可编辑textarea改正文并drop extraFile，第二次send同submissionId却body由原消息变后来编辑、Files由1变2，后编辑草稿清空。另文本send在途真实typing，ack也清新草稿。

实际变体：

- multipart unknown→实际text/File编辑→显式send：FAIL
- text message send在途→实际later typing→ack：FAIL

仍未跑：

- package/discussion/designRevision变化
- File原顺序改变及bytes验证
- 退休后成功不重启poll

界限：未解除disabled或合成禁用选择事件；两Files包含原File且第一项reference保留，失败是追加File/body变了，不说原File本身被替换。文本endpoint无通用key，不伪造重投。

### B7.1 terminal retry timeout即时释放

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/designer-w0.spec.ts:177`。

源码：`frontend/src/views/DesignerView.vue:859`、`frontend/src/views/DesignerView.vue:861`、`frontend/src/views/DesignerView.vue:1167`、`frontend/src/views/DesignerView.vue:1169`、`frontend/src/views/DesignerView.vue:1170`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/DesignerView.spec.ts:128`、`frontend/src/views/DesignerView.spec.ts:185`、`frontend/src/views/DesignerView.spec.ts:228`。

合同：证明活动retry后root retirement首采样owned timeout0、streamclosed，迟到不得新GET/重排。

实测：实际stream终态+在途poll证明owned retry1；unmount后任何clock/promise/input之前首采样仍1（红），streamclose1。交付旧poll再推进100ms无额外GET（绿子断言）。

实际变体：

- 真实stream+deferred poll，fake-clock首采样，late read/100ms分层：1 FAIL

仍未跑：

- 两实例不互清/重复cycles
- 其它资源及递归50次所有路径
- 真实浏览器timer/GC

界限：set/clearTimeout透明callthrough，按真实callback源码refreshDesignerAfterTerminalEvent识别唯一owned1；不等自然结束清理。只声明首采样ledger；已执行timer从集合移除的全周期未测。

### B7.2 composer focus RAF即时释放/迟到焦点

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/designer-w0.spec.ts:195`。

源码：`frontend/src/views/DesignerView.vue:1047`、`frontend/src/views/DesignerView.vue:1048`、`frontend/src/views/DesignerView.vue:1049`、`frontend/src/views/DesignerView.vue:1167`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/DesignerView.spec.ts:128`、`frontend/src/views/DesignerView.spec.ts:185`、`frontend/src/views/DesignerView.spec.ts:228`。

合同：实际edit入口排入owned composer RAF，卸载即cancel；迟到不能聚焦新页同selector。

实测：实际?sessionId=A&mode=edit queued1，真实router离开后首采样仍1；之后才交付旧callback，确实聚焦新页同id textarea。两断言红。

实际变体：

- 实际edit入口→owned queuedRAF→真实route leave→严格首采样→独立late delivery：1 FAIL

仍未跑：

- 多实例/全部RAF来源
- 实际Chromium调度与全堆回收

界限：RAF mock登记callback identity并按真实#designer-message函数源码归属；无主动清global账本。故意late回调在首采样后，独立防御不掩盖即时清理。

### B7.3 storage失败与确定create事实

**REPRODUCED_FAIL；coverage=partial**。新测试：`frontend/src/w0/designer-w0.spec.ts:212`。

源码：`frontend/src/views/DesignerView.vue:106`、`frontend/src/views/DesignerView.vue:110`、`frontend/src/views/DesignerView.vue:970`、`frontend/src/views/DesignerView.vue:1026`、`frontend/src/views/DesignerView.vue:1086`。原测试锚点（此次未由作者单独重跑）：`frontend/src/views/DesignerView.spec.ts:705`、`frontend/src/views/DesignerView.spec.ts:720`、`frontend/src/views/DesignerView.spec.ts:793`。

合同：optional storage失败不把accepted create伪装普通失败；内存与显式session恢复保留，刷新能力不虚构。

实测：initial File createaccepted且workspace setItem SecurityError：create1、sessioncomposer仍在，但提示“无法创建设计草案”（红）；已有session显式GET恢复+storage不可写仍可用（绿）。

实际变体：

- accepted initial File create→setItem SecurityError：FAIL（误导错误）
- ?sessionId=A显式恢复+setItem SecurityError：PASS

仍未跑：

- getItem/removeItem/quota错误
- 清理/Taskhandoff阶段storage失败
- 回退UI/File/draft完整内存与刷新限制提示

界限：确定回执与composer没有丢；不说已证明自动重发、File GC或跨刷新字节恢复。只复现postreceipt误导消息。

## 非作者独立审查 C：四份历史浏览器测试

被审作者为 `/root/react_ppt_canvas`。本作者只读核对四文件git diff、before／after源码副本、JSON及log hash，并解包before失败trace和after唯一失败trace；没有重跑浏览器，没有将自己53项充当独立验收。

结论：未发现测试方法学阻塞。before四份源码逐字节等于HEAD；after副本逐字节等于当前四spec，before sourceSha256与afterSourceSha256分别正确，双report／log hash正确。before原11为0PASS／11FAIL；after全部20为19PASS／1FAIL／0skip／0flaky，原11标题全部仍发现（10PASS／1FAIL），其余原8与新增redirect1分开计数。

四份最小修正保持原业务断言：

- `frontend/e2e/database-progress.spec.ts:29` 改为当前真实JDBC URL／用户名，`:13`、`:34–44`加强POST/localUI、DTO、probe失效、Task12/14与85%及窄屏溢出；没有删除数据库流程。
- `frontend/e2e/document-template-tasks.spec.ts:63` 仅换为testInfo.outputPath，保同File上传失败后同key重试、正文／报告按需读取、下载、刷新与溢出。before两trace已到最终截图，首错为旧绝对路径ENOENT。
- `frontend/e2e/roles.spec.ts:151` 采用当前完整精确权限文案，CONFIG_ONLY／complete=false fixture、limitations、工具来源及三皮肤两宽度仍在。
- `frontend/e2e/read-consistency.spec.ts:35` 原Automations可见health恢复case断言逐字保留；before和after唯一trace都因正式redirect后不存在health元素失败。新增`:55`真实深链＋刷新负控只证明模板入口及无退役API调用，不能替代health可见恢复。

after成功项没有保留trace（配置仅保失败trace），成功流程依据实际JSON、当前测试与截图，不宣称审过成功trace。C现有health两项聚焦单位通过与76名称过滤未选中分开，不证明归档consumer可达或真实调度器恢复。证据与未关闭消费者见[历史台账](historical-failures.md)及相邻JSON。

## 交付及剩余门槛

两spec及本MD／JSON冻结，文件所有权交还组长集成。此前完整运行45条正确合同红断言保留；B1.4新禁改断言单项已PASS，15父case缺口待获批最小业务修复和非作者red→green验收；所有partial未测变体逐项保留。B6.1无reject入口不强造，跨刷新／真实后端／真实File解析不虚构。C剩余历史health可见消费者FAIL不以redirect绿关闭。W1／全面React生产开发尚未获本轮授权。
