# W0 行为基线、独立复核与下一阶段门禁

**W0取证完成；生产未修改，W1尚未放行。** 固定设计／运行基线 `008542f0bf02dc1c1b76f1e75429aab8452b797d`，分支 `feat/react-full-migration`。本轮新增测试与文档，不安装候选UI框架，不推送。后续桌面设计修订另形成本地检查点，不混入这批失败证据。

28个父场景的最低探针全部运行：**25组包含真实合同失败，3组已测变体通过；111个子测试为27通过、84失败。** 只有B1.4分层最小合同完整覆盖，其余27组为partial。不是25个完整缺陷族已穷举，也不是全站安全协议通过；未跑子变体逐项保留。产品问题仅记录最小修复与波次门槛，本轮没有运行时代码修复。

项目经理已实际查看旧单文件五页／三皮肤，候选方向曾原则认可；最新用户方向改为**仅桌面、知识库与第一阶段画布的选中上下文范式、低密度首页及全站统一语义**，须重新走设计门禁。新方向不改变本轮业务基线。原29图和设计HTML保留；没有以模拟原型当真实React/Ant验收。

## 1. 实际团队与独立性

原三名canonical任务由实际followup复用，没有spawn或换成员。原启动回执显式gpt-6.1-sol/xhigh；当前协作工具只返回身份／状态，不能实时回读model/effort。组长GPT-6.1 Sol极高来自项目经理UI切换及委派证据，不伪称工具实时配置字段。

| 原成员 | 实际作者范围／结果 | 非作者审查 |
| --- | --- | --- |
| `/root/react_flow_workflow` | B1/B2/B5/B6/B7：16父场景、53子测，8通过／45失败；New禁改UI与纯owner分层 | 审查C四份历史spec的原断言、before/after与日志hash，见[A报告](workflow-designer-results.md) |
| `/root/react_legacy_canvas` | B3/B4/B8/B9：12父场景、58子测，19通过／39失败；归档9GET落点 | 审查A与组长恢复合同，见[B非作者复核](w0-review-legacy.md) |
| `/root/react_ppt_canvas` | 原11失败前后、20相关Chromium与2纯health合同 | 审查A/B真实入口、夹具、File、资源与恢复语义，见[C非作者复核](w0-review-ppt.md) |
| 组长 | 统一契约、源代码边界、完整unit/type/build/tooling、文档集成与本地提交 | 作者自测与非作者证据分开，不能以1305原单测抵消红 |

## 2. 28组场景分类

父场景未运行／环境阻塞均0；25组含已复现合同失败、3组已有PASS。**不可达或未复现仅对应明确子变体**：Designer必答问题没有reject入口；当前Vue命令owner没有React StrictMode入口；这三个store没有removeItem路径。它们不算产品缺陷；证明不可达的负控可执行通过，但不代表那个不可达动作已验证。其余未穷举子变体见[A逐项JSON](workflow-designer-results.json)、[B逐项JSON](templates-history-results.json)与[汇总索引](case-index.json)。

| Case | 分类 | 子测通过 / 失败 | 覆盖 | 实际观察／源码与断言入口 |
| --- | --- | --- | --- | --- |
| B1.1 | 真实合同失败已复现 | 1 / 6 | partial | [6项 pending 导航都实际离开（push/replace→/away，back→/previous），create均仅1次；普通 dirty 取消/明确放弃对照通过。](workflow-designer-results.md) |
| B1.2 | 真实合同失败已复现 | 1 / 4 | partial | [copy/archive各sending/unknown均导航到/away；copy unknown改变真实“我的流程”筛选后重试保相同请求。](workflow-designer-results.md) |
| B1.3 | 真实合同失败已复现 | 1 / 2 | partial | [真实guard=false及竞争导航取消均丢“打开已创建的需求”入口并重新显示submit；reject保入口。每项create仅1次。](workflow-designer-results.md) |
| B1.4 | 原有最小合同通过 | 2 / 0 | complete | [实际title/objective及project/template真实choice均禁改，显式重试为同body对象；纯owner projection变为新title/rev后write两次仍原body/key，accepted读失败后仅重读（read2）。](workflow-designer-results.md) |
| B2.1 | 真实合同失败已复现 | 0 / 13 | partial | [六真实panel经真实按钮发POST，sending/unknown × confirm=true的exposed canLeave均true；实际Requirement+Finish unknown route也到/away。](workflow-designer-results.md) |
| B2.2 | 真实合同失败已复现 | 0 / 1 | partial | [实际command stop未知后显式retry，两请求req/review/run/stop、commandVersion19与key相同；canLeave=true违反禁离开。](workflow-designer-results.md) |
| B2.3 | 真实合同失败已复现 | 0 / 1 | partial | [真实Finish POSTaccepted、finishStatus GET失败后提供“刷新操作结果”；canLeave=true为红。随后恢复仅finishStatus GET，finish POST仍1。](workflow-designer-results.md) |
| B3.1 | 真实合同失败已复现 | 0 / 9 | partial | [创建/hash/start 处理中 busy 禁输入有效，但真实 router.push 能离开；unknown 后输入重新可编辑，换草稿发新 key；已接受 Task/start unknown 再改回草稿又 create。](templates-history-results.md) |
| B3.2 | 真实合同失败已复现 | 2 / 4 | partial | [Source start/Document resume 的 inflight、unknown 都允许 A→B→exit；id-watch 会清 pending。无本地命令只读页允许退出，SSE close=1 且零 stop POST。](templates-history-results.md) |
| B3.3 | 真实合同失败已复现 | 0 / 4 | partial | [四动作均在 GET 3→4 后生成新 key/CAS；Source retry 同时改变选择 batch-a→batch-a+batch-b，原选择不冻结。](templates-history-results.md) |
| B3.4 | 真实合同失败已复现 | 1 / 4 | partial | [get/set 抛错时同输入两次 explicit retry 保同 key，仍能离开；文档 metadata reload 的 by-request404保未知，同字节重选保 key；异字节/反序实际发新 key和不同bytes/顺序。单独 accepted GET 正控只读找原run、零追加POST。](templates-history-results.md) |
| B4.1 | 真实合同失败已复现 | 2 / 1 | partial | [A modal pending→真实同组件路由 B→confirm 后实际 POST A，结果把 B run 变 A；当前 A 正常 confirm 单次 POST、reject 零 POST 两正控通过。](templates-history-results.md) |
| B4.2 | 原有最小合同通过 | 2 / 0 | partial | [A actual cancel POST 发出后强制根卸载/new B root，A success/error 都不能污染 B run/error/acting；保原 A key/CAS。](templates-history-results.md) |
| B5.1 | 真实合同失败已复现 | 1 / 4 | partial | [initial/followup text/File四种入口confirm拒绝配置但confirm0，真实route到/away；保存只读会话无draft离开无写通过。](workflow-designer-results.md) |
| B5.2 | 真实合同失败已复现 | 0 / 4 | partial | [两真实context-turn入口各sending/unknown，mutation1并含原submissionId/File，全部route到/away。](workflow-designer-results.md) |
| B5.3 | 真实合同失败已复现 | 0 / 2 | partial | [两variant都确认1、真实getTaskOverview(original taskId)已调用且store有accepted-task；workspace清空，恢复入口缺失，重新显示初始目标textarea。](workflow-designer-results.md) |
| B6.1 | 真实合同失败已复现 | 1 / 1 | partial | [实际question推荐answer POST在途，强制退休A、实际新建独立B后交付A回执又GET A；mandatory=true真实UI无reject按钮，reject0/confirm0。](workflow-designer-results.md) |
| B6.2 | 真实合同失败已复现 | 0 / 2 | partial | [实际修改设置/largeTask switch/保存设置；挂起preview或confirm modal，退休A后返回/确认，两项均向A执行update（profile version7、largeTask=true）。](workflow-designer-results.md) |
| B6.3 | 真实合同失败已复现 | 0 / 2 | partial | [unknown后真实可编辑textarea改正文并drop extraFile，第二次send同submissionId却body由原消息变后来编辑、Files由1变2，后编辑草稿清空。另文本send在途真实typing，ack也清新草稿。](workflow-designer-results.md) |
| B7.1 | 真实合同失败已复现 | 0 / 1 | partial | [实际stream终态+在途poll证明owned retry1；unmount后任何clock/promise/input之前首采样仍1（红），streamclose1。交付旧poll再推进100ms无额外GET（绿子断言）。](workflow-designer-results.md) |
| B7.2 | 真实合同失败已复现 | 0 / 1 | partial | [实际?sessionId=A&mode=edit queued1，真实router离开后首采样仍1；之后才交付旧callback，确实聚焦新页同id textarea。两断言红。](workflow-designer-results.md) |
| B7.3 | 真实合同失败已复现 | 1 / 1 | partial | [initial File createaccepted且workspace setItem SecurityError：create1、sessioncomposer仍在，但提示“无法创建设计草案”（红）；已有session显式GET恢复+storage不可写仍可用（绿）。](workflow-designer-results.md) |
| B8.1 | 真实合同失败已复现 | 0 / 4 | partial | [B 新查询先回、A 旧回覆盖 list/facets/cursor；旧 cursor append 混入 B；卸载后旧 read success/error 仍改退休 refs，但 debounce 被清，不新增 read。](templates-history-results.md) |
| B8.2 | 真实合同失败已复现 | 0 / 4 | partial | [A record 后回覆盖 B；同 attachment id 的 A late 正文进 B cache；A 已缓存跨 B 不重新读取，显示 A；退休 record error 改旧 busy/error。](templates-history-results.md) |
| B8.3 | 真实合同失败已复现 | 1 / 5 | partial | [目录/正文、补传 options 卸载后写旧 refs；回答/补传 POST 完成后清旧草稿/Files。Vue unmount 自身屏蔽 updated emit，实测没有跨根业务 emit；新 B clarification 草稿未变。运行 id/revision 的既有 clarification watcher 正控通过。](templates-history-results.md) |
| B9.1 | 原有最小合同通过 | 6 / 0 | partial | [实际 API fetch 的 start/batch/question/message 每次未知仅1 POST，body 无虚构 key；actual batch reload/props/render/三 applySkin/recovery open 以及 actual question 1200ms poll/props/三 applySkin 无自动重写。](templates-history-results.md) |
| B9.2 | 真实合同失败已复现 | 3 / 1 | partial | [report 外部 owner draft 改后恢复会替 key（UI 可编辑路径另有 B3.1）；multipart 两次同 File 确认 metadata/key/actual bytes/hash/name 相同；diagnostic unknown retry 原 action/CAS/commandId 相同；mock server samekey/different digest 返回409。](templates-history-results.md) |
| B9.3 | 真实合同失败已复现 | 2 / 3 | partial | [report 同草稿确实复用已知 Task id，仅事实正控，未实现先 GET；改后恢复 create3次。batch unknown GET version3→4 后重新发新 CAS；服务端 ready=false/STOPPING 投影阻断为正控。diagnostic accepted 后 GET失败/再旧 read 后重新 POST 同 commandId=2，丢 accepted 阶段。](templates-history-results.md) |

真实MemoryRouter/RouterView、SFC与Pinia owner未被mock；API/SSE运输及modal回执可控。六个panel的公开canLeave探针只作补充，实际父页route仅Finish组合，不冒称其他面板的所有出口。强制卸载只测迟到防御，不证明pending可普通离开。退休refs变化、新实体覆盖、业务POST与Vue卸载屏蔽emit逐项区分；不据旧ref探针推断堆泄漏。

New以deferred真实SENDING→UNKNOWN逐阶段检查名称、说明、项目、流程的:disabled与重试状态；没有解除disabled、伪造选择或修改DOM。纯TS operation外部projection变更负控独立；UNKNOWN显式POST仍原body/key，accepted只恢复实际读取／导航，New没有by-request GET。

## 3. 根因、最小修复方向与React断言迁移

| 现有断言／真实差异 | 最小后续修复建议 | 纯TS与真实React接入门槛 |
| --- | --- | --- |
| B1/B2/B3/B5：pending/unknown可普通confirm离开或无guard | 单owner先判未settle写BLOCK，再判普通dirty/File确认；父页消费子owner判定 | W0后专项合同修复→W1 Navigation/Operation；W3/W5真实React push/replace/pop/id/关闭/回退逐出口，不仅exposed方法 |
| B1/B5：NavigationFailure后丢可见accepted恢复 | 保receipt.id、检查导航boolean/异常；限定原目标handoff | accepted后mutation计数不增加，失败只nav/read；真实React恢复入口和焦点可达 |
| B3/B6/B9：unknown替body/key/CAS/Files、重建task或清后来草稿 | 不可变operation与后来draft分离；端点专属恢复，保taskId／File顺序及byte/hash；不造无key协议 | W1纯owner→W3模板／W5Designer，保持create/start计数、HTTP方法/header、404/200、409/CAS、原File与未发送后来draft |
| B4/B6/B8：modal/await/query/cache跨scope | modal前捕获scope；每个await复核epoch；缓存按原Task＋attachment身份；退休先失效 | W1 scope→W2历史/W3文档/W4冻结Task/W5Designer；A/B乱序、旧finally、晚emit/POST和读资源严格证明 |
| B7：retry timeout/RAF未退休，storage失败误报 | 实例ResourceScope登记、立即清理；storage可失败但保内存owner/原回执，披露刷新边界 | 已活动资源首样、无自然事件、迟到不得重排或聚焦新页；React StrictMode与真实浏览器另验，jsdom不等GC |
| 原DB/角色/文档测试 | 修真实JDBC DTO、当前中文文案和跨平台outputPath，保全部业务断言 | W2真实React表单／权限、W3上传、W4进度；不能用旧Vue夹具宣称React通过 |
| 原Automations可见health合同 | 保留失败、按确定归档入口迁移交互前提，保FAILED次数/原因→新读CHECKED清错 | W1只读adapter/owner→W3真实Drawer，原health断言＋GET/导出/关闭/深链；不以redirect或新Task恢复偷换 |

这些是**修复建议，未实施**。具体源码行、原测试、新测试、body/key/File与失败首断言在两份作者台账中；设计接口不是已存在文件或已通过实现。

## 4. 原11历史失败与当前桌面范围

原样before 11/11失败；最小测试修正后，原11为10通过／1仍失败。根因为**8项字段／文案过时、2项平台截图路径、1项旧路由预期过时并叠加历史消费者缺口**。没有删、skip、test.fail或降低断言；Automations原UI测试保留真实红，额外redirect负控与纯health合同不是它的等价通过。[逐项前后、命令、源码hash、断言与trace](historical-failures.md)／[JSON](historical-failures.json)。

四份相关Chromium实际运行20项：19通过／1失败、0skip/0flaky。**运行发生于用户撤回窄屏要求之前**；已运行的5个390px结果保留为历史范围外信息，不作为本轮桌面准入。15项桌面为14通过／1失败；原11中的桌面6项为5通过／1失败。混合测试的桌面业务断言完整保留，不为缩范围删除窄屏或无关既有断言；后续不新增窄屏工作。

当前全站发现305 E2E／70文件，本轮20已运行，其余285未运行（其中后续窄屏均范围外），不是全站绿。两项纯health聚焦通过，其余76仅名称过滤未选中；它们都包含在原1305完整unit基线，不重复加到总数。

## 5. Automations兼容落点与未决项

[完整9GET／10退役写映射](../../automations-compatibility-map.md)已冻结：TemplateTasks工具入口“历史模板与自动化记录”→四页签只读Drawer（历史模板、旧规则与检测记录、运行记录、导出说明），同一个legacy拥有的纯TS `TemplateHistoryArchiveController`。这是W3设计落点，**目前尚无实际消费者**，不新增业务路由，/automations仍redirect。

默认目录零历史请求；显式打开/选页签/记录/刷新/导出才读取真实workspace/templates/detail/versions/rules/rule-runs/full-runs及两GET导出。完整旧run不依赖新Task列表；空/失效Task保留历史，精确版本/spec/hash不写回。两种导出范围有限，不含secret且不是整库备份；无旧create/start/rule/import/签发，后台历史对账仍原Java owner。

D1/D5具体位置和单owner设计已定。仍未通过：D2十退役写的真实HTTP全拒绝矩阵；D3完整旧数据/health升级夹具、旧cron字段及COMPLETED/SUPERSEDED静态映射风险；D4所有旧Vue断言的动态迁移和真实React归档消费。后端未运行，静态风险不记已复现缺陷；删除Vue、旧数据或旧断言前必须结清相应动态门槛。

## 6. 冻结恢复、安装与验证边界

[原身份恢复与离开合同](recovery-and-leave-contract.md)：pending/unknown/未安全交接accepted先BLOCK，普通dirty/File明确确认。New未知仅用户显式原key/body幂等POST；Document有真实by-request GET，404未知与200已找到回执分层，已安全确认后的新意图不冒称恢复。无key endpoint遵守真实ID/version/CAS/状态读取，不自动write或新身份。storage失败、跨刷新File与beforeunload限制如实说明。

锁定依赖正常npm ci安装420包，既有XYFlow postinstall成功，package/lock未变。默认缓存日志写失败及普通沙箱registry.npmjs.org connect EPERM已留原结果；正常工具权限后成功，未改网络/代理/凭据。测试运行Node24.19.0/npm11.9.0；不是正式JAR固定Node22工具链证明。

原单测基线1305/1305通过。冻结版全量、类型、build及tooling的最终回执见[组长验证摘要](verification.json)；新增正确合同红原样保留，所以完整unit应exit1，不冒称全绿。tooling31通过、guard13通过；初轮spawnSync EPERM仅环境权限，正常工具机制下复跑成功，未改脚本。仓库说明及diff检查完成后本地提交。

原始日志、JSON、trace、模拟截图位于本任务 `/workspace/react-full-w0-evidence/`，未把环境日志、私人数据或凭据加入仓库。3121个基线工程文件的hash核对仅四份历史spec改变；生产、后端、依赖、原39个原型文件／29图均保持原样。本轮单测/mock与浏览器Mock API不验证真实后端、付费模型、DOCX/PDF/Java解析或真实服务器幂等。观察关系退休与GC全部对象释放分开；第一阶段断开后仍存活采样边界保留。

## 7. 给项目经理的W1建议

**不建议直接放行W1或全面页面迁移。** 这批取证可审查，25组失败、27组partial和当前归档消费者缺口没有产品修复。先按上表批准限定合同修复包，使原身份、离开、scope、accepted交接、File与资源红转绿，再按最新桌面设计／统一语义另过设计门禁；不要改correct expected或删旧断言换绿。

W1即使获批，也仅先做共享语义registry/组件、纯owner及正式React/Ant样例，真实桌面、键盘、焦点与reduced-motion重新验收。后续W3/W5逐入口保高级操作、当前错误和恢复可达，W6删除Vue前检查所有依赖/源代码/测试夹具，无运行白名单；不把窄屏撤回解释为可删桌面业务、File保护或历史数据。
