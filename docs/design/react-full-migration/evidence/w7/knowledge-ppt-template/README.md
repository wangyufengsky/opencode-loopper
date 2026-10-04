# W7 Knowledge / PPT / 模板 / 历史 Automations 独立验收

状态：当前唯一 release 新817文件/sourceSHA `d1a38dd1471e5c5d0d23aeafa845c7494d3bc30e2d5875dd37c24e9faded596d`，本组最终旧桌面49/49＋W3生产31/31均实际通过，0skip/0retry/0errors，前后冻结校验与生产bundle一致。两份注册各含同一原read-consistency三合同，不冒称80个唯一业务合同。旧a882批和以下中间失败仅为分层历史诊断；未将其代为最终绿。

## 来源与边界

- 组员 B 复用既有指定 gpt-6.1-sol / xhigh；当前工具不能实时读取平台配置，指定历史不作为实时平台字段。
- 基准 HEAD `1e9161e06c01b5aa76b6a2b19789ac8379423195`；真实桌面收集 63 个原定义中的 49 项，14 项纯窄屏 OUT_OF_SCOPE，未删除/skip 源断言。
- 混合 Knowledge 文件预览 1 项、PPT 三皮肤 3 项只不执行 390px 布局尾段；桌面文件、主题不写入、键盘/几何/未知回执/资源断言保留。
- 只调用确定性 API/SSE 测试夹具；41773 服务由组长持有；无真实后端、付费模型、安装、提交或推送。
- 原始命令/结果、trace 和哈希：`/workspace/react-full-w7-evidence/B/`。初始 `source-before.json` 记录源码。

## 原标题与范围

| spec | 原标题 | 本轮范围 |
| --- | --- | --- |
| document-template-tasks.spec.ts | 文档评审上传重试、刷新与按需报告 1440px | DESKTOP |
| document-template-tasks.spec.ts | 文档评审上传重试、刷新与按需报告 390px | OUT_OF_SCOPE |
| knowledge.spec.ts | 文件链接在当前页预览并保留气泡头像，失败不跳到 404 | DESKTOP；保桌面、窄屏尾段历史保留 |
| knowledge.spec.ts | 统一检索分页、来源覆盖与数据库原文 1440px | DESKTOP |
| knowledge.spec.ts | 统一检索分页、来源覆盖与数据库原文 768px | OUT_OF_SCOPE |
| knowledge.spec.ts | 无思考内容时的动态等待与回复切换 1440px | DESKTOP |
| knowledge.spec.ts | 无思考内容时的动态等待与回复切换 768px | OUT_OF_SCOPE |
| knowledge.spec.ts | 知识问答来源与引用面板 1920px | DESKTOP |
| knowledge.spec.ts | 知识问答来源与引用面板 1440px | DESKTOP |
| knowledge.spec.ts | 知识问答来源与引用面板 1280px | DESKTOP |
| knowledge.spec.ts | 知识问答来源与引用面板 768px | OUT_OF_SCOPE |
| knowledge.spec.ts | 新对话默认模型、来源与深层历史路由 | DESKTOP |
| knowledge.spec.ts | 继承提供方与模型名并发送完整模型标识 | DESKTOP |
| knowledge.spec.ts | 模型目录挂起不影响进入、输入或默认发送；选择器独立显示等待 | DESKTOP |
| knowledge.spec.ts | 引用栏拖动宽度在重新打开和刷新后保持 | DESKTOP |
| knowledge.spec.ts | 侧栏返回正在回答的知识对话、恢复草稿并读取离开期间的结果 | DESKTOP |
| knowledge.spec.ts | 侧栏记住新创建的对话，其他页面刷新后仍可返回，主动新建回到首页 | DESKTOP |
| knowledge.spec.ts | 侧栏返回历史对话页并保留筛选，浏览器后退仍进入原对话 | DESKTOP |
| knowledge.spec.ts | 历史独立筛选、归档、恢复和返回草稿 | DESKTOP |
| knowledge.spec.ts | 文档阅读视图和数据库结果的引用范围高亮 | DESKTOP |
| knowledge.spec.ts | 助手澄清问题的草稿可刷新恢复且只提交一次 | DESKTOP |
| knowledge.spec.ts | 从正文底部打开引用保留非零滚动位置，关闭后继续阅读 | DESKTOP |
| knowledge.spec.ts | 空回答失败可以恢复原问题且不会自动重发 | DESKTOP |
| ppt.spec.ts | PPT 助手显示真实思考和调用，展开状态随更新保留 | DESKTOP |
| ppt.spec.ts | 选择项目后自由讨论并保留草稿，明确确认后才开始设计 | DESKTOP |
| ppt.spec.ts | 历史生成授权继续兼容提问与需求确认，补充意见不冒充确认 | DESKTOP |
| ppt.spec.ts | 完成后默认预览与修改意见，选择对象限定修改，自动更新文件 | DESKTOP |
| ppt.spec.ts | 手动编辑可选开启，对象键盘移动保存真实坐标与版本 | DESKTOP |
| ppt.spec.ts | 安全暂停保持阻断，证明停止后显示继续制作且沿原工作流恢复 | DESKTOP |
| ppt.spec.ts | 详细方案移入更多入口，七模块修改自动保存且不偷偷开始制作 | DESKTOP |
| ppt.spec.ts | 窄屏预览和助手分层呈现，无横向溢出，深链刷新保留作品 | OUT_OF_SCOPE |
| ppt.spec.ts | spdb 画布按需展开、键盘缩放和取消选择（模拟数据） | DESKTOP；保桌面、窄屏尾段历史保留 |
| ppt.spec.ts | tech-blue 画布按需展开、键盘缩放和取消选择（模拟数据） | DESKTOP；保桌面、窄屏尾段历史保留 |
| ppt.spec.ts | github-white 画布按需展开、键盘缩放和取消选择（模拟数据） | DESKTOP；保桌面、窄屏尾段历史保留 |
| ppt.spec.ts | 手动拖拽和缩放使用稳定画布坐标，取消拖动不提交 | DESKTOP |
| ppt.spec.ts | 未知保存回执阻止离开，原身份恢复后安全回退（模拟数据） | DESKTOP |
| ppt.spec.ts | React 属性自动保存遇到409保留输入和基线，禁止旧修订继续写入（模拟数据） | DESKTOP |
| ppt.spec.ts | 存储不可写且无属性草稿时，未知键盘操作阻止回退直到原操作恢复（模拟数据） | DESKTOP |
| ppt.spec.ts | PPT drag 活动中三次SPA退出首采样零监听RAF捕获RO且无迟到保存（模拟数据） | DESKTOP |
| ppt.spec.ts | PPT resize 活动中三次SPA退出首采样零监听RAF捕获RO且无迟到保存（模拟数据） | DESKTOP |
| read-consistency.spec.ts | 较晚返回的旧筛选响应不能替换当前列表 | DESKTOP |
| read-consistency.spec.ts | 自动化页面显示检测失败并在服务端恢复后清除旧告警 | DESKTOP |
| read-consistency.spec.ts | 退役自动化深链和刷新进入模板任务，不读取或重开历史自动化写入口 | DESKTOP |
| source-template-form.spec.ts | spdb 源码表单 1440px 紧凑布局、预检和模板切换 | DESKTOP |
| source-template-form.spec.ts | spdb 源码表单 390px 紧凑布局、预检和模板切换 | OUT_OF_SCOPE |
| source-template-form.spec.ts | github-white 源码表单 1440px 紧凑布局、预检和模板切换 | DESKTOP |
| source-template-form.spec.ts | github-white 源码表单 390px 紧凑布局、预检和模板切换 | OUT_OF_SCOPE |
| source-template-form.spec.ts | tech-blue 源码表单 1440px 紧凑布局、预检和模板切换 | DESKTOP |
| source-template-form.spec.ts | tech-blue 源码表单 390px 紧凑布局、预检和模板切换 | OUT_OF_SCOPE |
| template-batch-recovery.spec.ts | 第39批失败后继续后续批次，结束后统一选择重新触发 1440px | DESKTOP |
| template-batch-recovery.spec.ts | 第39批失败后继续后续批次，结束后统一选择重新触发 390px | OUT_OF_SCOPE |
| template-batch-resilience.spec.ts | 等待任务显示失败清单、原批次恢复与独立重查 1440px | DESKTOP |
| template-batch-resilience.spec.ts | 等待任务显示失败清单、原批次恢复与独立重查 390px | OUT_OF_SCOPE |
| template-reports.spec.ts | 代码审查主子报告跳转、命名下载与1440px布局 | DESKTOP |
| template-reports.spec.ts | 代码审查主子报告跳转、命名下载与390px布局 | OUT_OF_SCOPE |
| template-reports.spec.ts | 项目贡献周报主子报告跳转、命名下载与1440px布局 | DESKTOP |
| template-reports.spec.ts | 项目贡献周报主子报告跳转、命名下载与390px布局 | OUT_OF_SCOPE |
| template-session-diagnostics.spec.ts | 已接受但忙碌的批次可独立收尾，未知请求复用命令标识 1440px | DESKTOP |
| template-session-diagnostics.spec.ts | 已接受但忙碌的批次可独立收尾，未知请求复用命令标识 390px | OUT_OF_SCOPE |
| template-tasks.spec.ts | 模板参数继承项目路径、可选择分支，结束日期校验阻止错误提交 | DESKTOP |
| template-tasks.spec.ts | 模板任务 1440px 布局与评分说明 | DESKTOP |
| template-tasks.spec.ts | 模板任务 390px 布局与评分说明 | OUT_OF_SCOPE |
| template-tasks.spec.ts | 目录可容纳后续模板，搜索后能选择，执行记录只从历史任务入口查看 | DESKTOP |

## 当前已取得证据

- `legacy-before.json`：49 项均 browser.launch 前 crashpad setsockopt Operation not permitted，全部 ENV_BLOCKED；不能算产品红。
- `legacy-before-permitted.*`：获正常本地 Chromium 权限后首诊 7 PASS / 32 FAIL / 1 INTERRUPTED / 9 NOT_RUN，主动仅中断本组进程；JSON skipped 10 必须按这两类拆分。期间 Knowledge PREPARED 修复落盘，故此批是带来源变化的诊断候选，不冒称全部固定原字节。
- `knowledge-prepared-before.*`：新增正式 DTO 负控 1 FAIL。PREPARED 成功回执后更新 GET 次数为 0，显式仅读恢复仍 ACCEPTED_READBACK/BLOCK。
- `knowledge-prepared-after.*`：原样 1 PASS；原问题/id/answers/version/key 保留，PREPARED 阶段阻断，REST 追上 ANSWERED 后才收尾，POST 恒为 1。其余 18 名称过滤不计通过。
- `knowledge-unit-final-candidate.*`：Knowledge 当前 46 定义 / 46 PASS，作者修复候选，不等价于浏览器或他人独立通过。

## 原历史 11 的本分区落点

- HF-DOC 原 2 个 ENOENT 截图夹具：旧固定 /private/tmp 已在历史门禁修为 TestInfo.outputPath。W7 仅重跑 1440px；390px OUT_OF_SCOPE。上传原 File/原 key 重试、刷新只读、原文/报告按需读取和 zip 命名的原业务断言仍需本轮真实页面通过。
- HF-AUTOMATIONS 原 1 个消费者缺口：必须在 /template-tasks 显式历史归档读取，验证 自动化检测状态 的 FAILED 连续 2 次→CHECKED 检测正常并去掉旧 Git 告警。/automations redirect、默认目录零历史请求是独立负控，不能抵消此消费者断言。
- 9 合法 GET 和两种原 UTF-8 安全导出由 W3 production 三皮肤历史用例独立复验；不调用旧 create/start/rule/import 写 API。

## 待执行/限制

固定本组字节完整49已完成，结果见末尾；W3固定生产31尚待组长preview调度。开发服务结果不替代最终生产bundle；资源firstsnapshot账本不等于heap/GC泄漏证明。

## 已完成的非作者复核

- A Task projection：独立 `task-projection-independent.*` 17/17 PASS、exit 0，当前 `AWAITING_DECISION + executionResult=FAILED` 明确“任务已终止，不会再创建新会话”。仅投影新增分支，不改变 REST 生命周期、停止证明、重试/人工决策许可或写入入口。A 原第一浏览器红是原错误投影；第二候选是独立文本节点 selector；A 第三轮 12/12 为作者浏览器结果，不记为 B 运行。
- Root App / Database：独立 `root-fixes-independent.*` 精确 2/2 PASS，其余 32 名称过滤不算通过。skip-link 只防默认 hash 导航并聚焦当前 layout main，保当前 URL/scope/pending 身份；数据库 probe 只缓存非编辑原行 id/version，原 ticket 迟到不能跨行投影，新版本不复用旧结果；不修改输入/命令或隐式写。未将 Root 整体 34 绿称本组复验。
- Root Role scroll：只读核新增独立目录/详情滚动边界，CSS 限定 `object.role` 页 root；列表/详情/导入/确认按钮仍是原 owner 回调，未修改 File、key/body/CAS、许可或订阅。新增 details 的 MCP/权限原内容全部保留。此为只读结论，没有运行 Root 的 10 项浏览器。

## 诊断候选 2（全部原桌面标题实际执行）

`legacy-candidate2.json/log/exit`：49/49 执行，33 PASS / 16 FAIL / 0 skip，exit 1。开发服务与正在修订的来源属于诊断候选，不是最终固定生产批次。

| 差异 | 首证据与分类 | 最小处理 |
| --- | --- | --- |
| Knowledge PREPARED question reply | `knowledge-prepared-before` 1 真实合同 RED；POST 已接受后没有合法 read，始终阻断 | 原 question/id/version/answers 冻结，合法原 conversation read 等到 ANSWERED，所有异常/旧版本继续 BLOCK，POST1；当前46作者 PASS |
| Catalog 表单高度随相邻目录变化 | `source-template-after1` 原 `<1px` 实际 RED | 输入列 `align-self:start`；随后三皮肤原高度断言越过，不放宽阈值 |
| 原贡献评分说明缺失 | `source-template-after1` 评分原标题真实 RED | 只在真实 CONTRIBUTION_SCORE_V1 保原 30/25/15/20/10 分说明，无业务计算修改 |
| Catalog 历史入口丢过滤 | `knowledge-ppt-template-next` URL实际 `/tasks`，原断言 `/tasks?type=template` RED | 恢复原过滤深链与“历史任务”，默认不读取执行历史 |
| PPT BRIEFING 资料入口缺失 | `knowledge-ppt-template-next` 真实菜单缺“资料与素材” RED | 有 document 即保资料只读入口，历史/方案仍按原 ready gate；不自动写或启动制作 |
| Knowledge 左资料栏覆盖 citation | `legacy-candidate2` 1440/1280 trusted click 被左 aside 拦截 | 打开来源时预留自身左列；引用 overlay 保打开前 chat bbox/scroll；原断言保持 |
| PPT 插入菜单 Esc 不关闭 | `legacy-candidate2` 三皮肤真实 button count1/期望0 RED | 仅 Studio root Escape 清 menu/tools，无全局监听，无服务端 cancel |
| Source 原任务产出说明缺失 | `legacy-candidate2` 三皮肤 Markdown 原断言 RED | source 定义的 testOutputPath 能力投影测试/验证，否则 Markdown/流程图/源码覆盖清单，只读 |
| 旧 DOM/动作 selector 与夹具 | `legacy-candidate2` 其他 8 红 | 源码核真实中央中文名、选择详情入口、textbox、方案 footer/tab，不删合同；File/key/body/CAS/POST 数、105px/20px、资源硬零断言不变 |

`catalog-ppt-local-final-candidate.*` 23/23 PASS、exit0（Catalog9+PPT14）；新增1项是只读方案重开后显式保存正控，其前两个 probe 错误是测试语义 key/未 spy 夹具错误，**不是**产品红。方案保存浏览器的 poll 中间失败不代表最终错误，trace 已显示 POST，真实最后失败是旧 footer selector，未错误修改 owner。

最终完整桌面49与 W3 固定生产31仍待结束/组长生产 preview 调度；不将散批通过相加或替代它们。资源首采样不等于 heap/GC 证明。


## 诊断候选 3与本组来源冻结

`legacy-candidate3.*` 完整原49桌面定义实际45 PASS / 4 FAIL / 0 skip，exit1；期间最后一项生产改动落盘，仍不作为最终冻结批次。

- Knowledge18全部通过，包含1920/1440/1280左资料栏与引用可信点击、聊天bbox/非零scroll保持；源码表单3皮肤原高度/产出/预检断言通过。
- PPT普通属性dirty时“结束手动编辑”仍enabled为实际UI许可差异；最小补其availability的busy/pending/propertyDirty条件，保原draft/receipt/handler保护，原相邻invalid-input断言同时检验不可关闭。
- PPT三皮肤末尾告警失败为selector同时找到全局告警和面板关闭阻断提示两处合法alert；只改为实际`.w2-status`中的alert，保原文本/回执/恢复断言。
- PPT两项活动手势各3次SPA退出的监听/RAF/capture/RO第一快照硬零、原105px/20px位移及无迟到保存已在此批通过；不能用此重复通过代替最后完整49。

`w3-B-local-final-candidate.*`：本组Knowledge/PPT/Catalog相邻全集实际137/137 PASS、0skip、exit0。这是作者聚焦结果，不冒称其他组独立复验或固定生产浏览器。

B源/测试冻结清单为`/workspace/react-full-w7-evidence/B/B-freeze-source-hashes.json`（20文件，SHA256 `97e7af77770a750a79ffb356d6c9e7a51a54e399e27c500357d6b5c7ce3a24d4`）；固定本组字节完整49正在`legacy-final-fixed.*`执行。共享开发服务其他来源仍由组长持有，最终生产bundle/31项另行记录，不拼绿。

## Root严格RAF归属替换：非作者复核

只读范围为`allCanvasResources.ts`、`canvasRAFProvenance.ts`、`react-renderer-ro-diagnostic.spec.ts`与`rafOwner.html/tsx`。独立命令：

```sh
W7_BROWSER_JSON=/workspace/react-full-w7-evidence/B/raf-independent/results.json W7_BROWSER_OUTPUT=/workspace/react-full-w7-evidence/B/raf-independent/browser CANVAS_ALL_CLEANUP_EVIDENCE_DIR=/workspace/react-full-w7-evidence/B/raf-independent/raw ./node_modules/.bin/playwright test --config e2e/w7/playwright.legacy.config.ts e2e/react-renderer-ro-diagnostic.spec.ts --grep 'strict cleanup negative'
```

真实3/3 PASS、0skip、exit0；源清单和原始JSON在`B/raf-independent/`。活跃正控是独立React实例，使用真实App/Router生命周期，没有生产帧白名单。CDP核实际同callback闭包、active实例、connected自有element、精确fixture URL及实际runtime source SHA；回调名称只限定调试开销，不授予外部归属，allocation stack/rootIds为空也不授予归属。

独立raw中callbackId=1在活跃`/tasks`实例确认owner（runtime SHA256 `80058ca593f9e4eb7672b968760bf5967d71475e1569df9246923125c44a9b43`）；真实路由离开后同callback注册id70，owner=null，`pendingCanvasFrames=[70]`、`externalPendingFrames=[]`，原严格assert抛错。unknown/misleading两帧均阻断；脱离DOM的renderer观察器第一快照仍计入且零disconnect，严格assert同样抛错。其余listener/wheel/capture/root必须仍为空。

Playwright首次locator只读初始化发生于armed窗口前，实际工具分配栈保留，没有按工具栈过滤/删除监听。每次RAF注册先删除旧owner标签；CDP退出只移除自己的breakpoint/listener、释放自己的objectGroup并detach。实例回调仅WeakMap/WeakRef记录，负控临时强holder在finally删除。账本有意持有观察器/target用于身份统计，因此这证明严格首快照和归属过滤负控，**不证明heap/GC回收**。原“Table callback”标题保留，当前正控已明确替换成另一个React测试实例。


## 本组冻结来源：完整旧桌面49最终运行

`legacy-final-fixed.json/log/exit`：**49/49 PASS、0 FAIL、0 skip、0 flaky、exit0，2.8分钟**。JSON SHA256 `369b494f71f3c289eddc22a4e6ad03eae6bd16f464da8d61e284d46ef06707a8`；机器核对`legacy-final-fixed-verification.json`确认49原literal title multiplicity完全相等、missing/extra均0、每项恰一次passed且无runtime errors。运行前冻结的20file逐SHA运行后全部相同。

```sh
W7_BROWSER_JSON=/workspace/react-full-w7-evidence/B/legacy-final-fixed.json W7_BROWSER_OUTPUT=/workspace/react-full-w7-evidence/B/legacy-final-fixed-output CANVAS_EVIDENCE_DIR=/workspace/react-full-w7-evidence/B/legacy-final-fixed-ppt ./node_modules/.bin/playwright test --config e2e/w7/playwright.legacy.config.ts e2e/knowledge.spec.ts e2e/ppt.spec.ts e2e/document-template-tasks.spec.ts e2e/source-template-form.spec.ts e2e/template-tasks.spec.ts e2e/template-batch-recovery.spec.ts e2e/template-batch-resilience.spec.ts e2e/template-reports.spec.ts e2e/template-session-diagnostics.spec.ts e2e/read-consistency.spec.ts
```

49逐项结果已进入相邻`case-inventory.json`；14纯窄屏仍OUT_OF_SCOPE，原源断言未删除/skip，4混合尾段仅按W7桌面project不执行。全部原File/key/body/CAS/上传metadata/order、显式恢复POST数、accepted仅读、上下文关闭/dirty保护、SSE和105px/20px画布原几何断言保持。本轮没有运行纯窄屏，不能把旧HF-DOC的390px失败称已实跑绿。

原HF-DOC桌面1440px上传/原key重试、刷新、按需报告/下载已绿；HF-AUTOMATIONS实际只读归档健康FAILED→CHECKED及旧Git错误消失已绿，默认目录和/automations redirect零历史GET是另外负控，不充消费者正控。9GET+两安全导出三皮肤完整消费者仍归W3生产31。

Knowledge18、PPT16、Document1、Source3、Template目录3、恢复/韧性各1、报告2、诊断1、原共享read-consistency3全在同一49JSON。PPT活动drag/resize各三轮均先trusted pointer启动，105px/20px原位移证明激活，再真实SPA退出；严格firstsnapshot listener/wheel/RAF/capture/RO空、没有cleanup move/up/blur和迟到operations，并保其他sentinel资源存活。样本保留在原TestInfo attachment；这证明即时资源账本合同，不能当GC证明。

最终来源摘要已复制本目录`source-hashes.json`。本批为组长持有mock开发服务上的固定B来源，其他模块/shared与最终production bundle由组长集中冻结；**未把散批绿色相加、没有冒称W3生产31或最终全站通过**。B来源停止写入，等待非作者复核和productionpreview GO。


## 类型门禁补正与最终v2冻结

组长candidate类型检查实际发现两处作者测试fixture/API类型问题；仅补KnowledgeQuestion准确DTO标注、invalid readback数组`satisfies KnowledgeQuestion[]`，以及RTL不支持`exact`时改精确`name: /^受众$/`。没有改变业务断言、生产代码或浏览器spec。

最终本组单测重新真实执行`w3-B-types-final.*`：137/137 PASS、0skip、exit0。应用源码类型检查`tsc -p tsconfig.app.json --incremental false --noEmit`真实exit0；未运行build/写共享tsbuildinfo，不冒称node config或全量build门禁。Knowledge修正前源码由反向最小类型patch重建且SHA与前冻`ffbb67...`完全相等，TypeScript转译JS前后逐字节相同；该证明在`test-type-provenance.json`，不冒称新的before执行。

最新20file清单为`source-hashes.json`，SHA256 `fd3c4d0a323ca9bbbd9eeb33797cdf20fe9327cc3eb7bd8de395cf5fa24c2fe3`；前冻清单保留在`source-hashes-before-test-types.json`（SHA97e7af...）。只有两个相邻spec的SHA变化，18其他来源全相同。原49/49浏览器批仍准确绑定原清单；其全部runtime/e2e字节与v2一致，所以保留既有49证据，不冒称因类型修正重新跑49。精确命令/结果见`test-types-results.json`。


## Root汇总脚本及两只读spec：非作者最终候选复核

只读范围`scripts/w7-acceptance-evidence.mjs`、`workflow-knowledge-handoff.spec.ts`和`workflow-snapshot-analysis.spec.ts`，三份源码运行前后逐SHA无变化。本组未改它们或共享资源helper。独立`B/readonly4-independent/results.json/log/exit`实际既有4/4 PASS、0skip、exit0（10.2s）；在组长停止开发服务之前自然完成，没有中断，不把Root候选4或C整体111算本组运行。

- handoff增加真实固定输入DTO所需version/requirementId/nodeId/planRevision，匹配实际node reader原requirement/node身份检查；没有放开该检查。result/inputs/input-content均仍精确原attempt/原sha和分页内容。选择节点后result=0、输入展开前inputReads=0、两段完整后inputReads=2、results=1、privateReads=0保留。原退款quote、阅读视图正文和private隐藏仍实际断言。
- snapshot只改变实际“源码依据”组的选择器；现在明确打开该组，精确显示`src/main/java/example/Validation.java · 第 24–25 行`并保`validate(input)`断言。analysis/review/reuse原批次、独立复核、证据支持/归因未确定、无新模型会话、原复用深链/privacy均保留；选中前bodyReads=0、显式展开后=1不变。
- snapshot原390px尾段源断言保留但本轮不执行；handoff390独立原定义为精确OUT_OF_SCOPE。混合三mode虽标题仍含“窄屏可读”，桌面合同没有被通配忽略。

汇总脚本按`file :: literal title`做多重集：原566注册=512桌面+54取消窄屏；明确桌面新增9=最终521注册/518唯一名称。read-consistency三合同原重复注册各2的multiplicity保留，没有误去重成少跑三项。baseline缺项、超过原multiplicity、未声明新增、9新增缺失/重复、OUT_OF_SCOPE出现、非expected或非单次passed/retry0都会失败；runner/collection errors也阻断。没有通配跳过失败、没有把skip/retry算pass。

本组将真实四项绿色JSON送入`browser`模式，实际exit1、`ok=false`，指出505个不同key/508次原注册缺失和9新增缺失，证据`partial-matrix-rejected.*`。这是对“不允许部分绿”的真实CLI负控，不是新增browsercase或伪造全521结果。

`freeze/verify`按排序后的实际源文件内容哈希核新增/删除/修改；`browser`做矩阵/结果核算，它本身不自动执行source verify或给Playwright结果补源哈希。最终组长必须仍按既定流程在批次前后执行同manifest verify，不能将本四项candidate与最终生产批次拼revision。此方法边界已明确，无需修改冻结工具。


## 817统一冻结批：最终legacy49与新增单元阻塞

组长干净npmci/6入口补丁、type/tooling门禁后，按FINAL GO使用唯一新开发服务执行`final/legacy/B.json/log/output`：**49/49 PASS、0FAIL、0skip、0flaky、0retry、exit0，9.9分钟**。前后`w7-acceptance-evidence.mjs verify final/source-freeze.json`均真实817文件/sourceSHA `a8821a377da1577b8b3a94de2c1f1f17e056cb66c4a1f91509c96b27c7efd8b8`通过。49原literal title multiplicity再次完全相等，不使用此前候选代数拼绿，逐项见`final-legacy-results.json`。

该浏览器结果不抵消全量单元的新失败。依组长只读指派，原`src/views/PptStudioRecovery.spec.tsx`三定义独立运行实际2PASS/1FAIL/0skip/exit1（`B/ppt-recovery-independent-before.*`）：恢复pending+propertydraft时“手动编辑”入口disabled，随后原select helper找不到canvas对象。不是超时或File/mock fixture错误。新增页availability把busy/pending/propertyDirty同时作用于进入和退出；读取原草稿入口被写保护误挡。真实owner.openDraft是lazy只读恢复、不自动保存，Properties已有studioEditable/fieldset禁写，原导航/identity保护正确。

最小建议为保退出busy/pending/dirty禁止，同时允许合法静态作品进入原只读属性查看；本记录时仍只读诊断，**未编辑817冻结源、未称此阻塞已关闭**。最终W3production31与组长完整门禁等待该实际合同问题结清及统一GO。


## PPT恢复查看阻塞：v3最小修复与新冻结

817全量单元发现的原恢复3case实际2PASS/1FAIL已闭合。经组长明确授权并等待C旧浏览器111自然结束后，仅改变`PptStudioPage.tsx:73`进入/退出许可：进入合法静态作品只是查看，不调用丢弃草稿的`protect()`；`!active`、archive、historical边界仍保留。结束手动查看仍受busy/pending/propertyDirty阻断。Properties的fieldset、Canvas禁写、原owner autosave/leave/key/body/revision/accepted恢复均未改变。

原`src/views/PptStudioRecovery.spec.tsx`源码SHA `bbd6a6a5ad2effdff9880fe149e144878925a446350d92436615768b0541ba73`前后相等，3个名称与断言不改，实际after **3/3 PASS**。正式Page新增1个必要负控（`:43`），Page现15/15；两文件合并18/18、exit0。其测试真实恢复pending+草稿，输入/save/delete/结束及键盘写仍禁止，1200ms无自动写，原页面/SSE不替换；唯一显式恢复严格原key/body/revision发送一次，GET原正文与草稿相同才清保存标志并ALLOW。普通dirty原确认离开测试继续保留并通过。

新增负控初轮18=17PASS/1FAIL是测试额外预期错误：把服务端已精确接受的同字节草稿仍期待CONFIRM_DISCARD。原`projectDrafts`确实会将匹配权威读回的草稿标为已保存；校正为原操作恢复后ALLOW，不降未确认期间的BLOCK/禁写/原身份断言。这份失败raw保留，单列fixture，不当第二个产品红。

随后本组10spec实际 **138/138 PASS、0skip、exit0**（原137+1新增Page负控），App无输出类型检查`tsc -p tsconfig.app.json --incremental false --noEmit`实际exit0。18中Page15与138重叠，不相加当唯一总数。结果及命令/逐case/hash详见`ppt-restored-view-results.json`；raw `/workspace/react-full-w7-evidence/B/ppt-restored-view/`。

最新20file v3清单SHA `8e6a072c972deb9a5298891700ab5a4ef4ee42806e344fdeab47caac21e3131c`，相对v2仅两文件变化：Page `3e30323306bc51168d7c629dc77e2301c87f079a3763f4fdcacb3655ba1acc24`、spec `3e84f81d2eec05c11d1f11c81d400a6bf21fab67a37c4dd71552998da0b08927`。原817浏览器49仍作为修前实际批保留；**不沿用它为当前新源码最终绿**。新的唯一全仓冻结、clean门禁及最终旧49/W3production31等待组长重新调度；当前作者代码停止写入并交原A非作者复验。


## 新release冻结与最终执行准备

组长新唯一`release/source-freeze.json`有817文件/sourceSHA `d1a38dd1471e5c5d0d23aeafa845c7494d3bc30e2d5875dd37c24e9faded596d`；本组只读逐文件重算当前哈希，817全部一致。manifest文件SHA `b20e36a9b2bb42938b90bd4270b64d656cafa809d2e4cd55a2467434f75cdb38`。没有启动浏览器、安装、构建或改变实现。当前每个桌面case在`case-inventory.json`明确追加release=NOT_RUN；旧a882批PASS及其sourceSHA原样保留，不能冒新d1a38批已绿。

A非作者已在同批94中实际复验本组PPT18（原恢复3+正式Page15）全部通过；本组只读取并核该真实JSON和前后hash，没有把C76加到B总数。`A/C-node-B-ppt-independent.json`SHA `8d6c15c218a60231060c642ca827e84d0f983416bbc2c1cd73ce3bd6ca5a9b98`，B20manifest逐字节与v3完全匹配，原恢复3源码不变。进入查看无protect/write、1200ms无自动POST、原key/body显式一次恢复与权威读回ALLOW均由该独立批实测。

历史11映射仍完整区分：本组HF-DOC1440为原截图夹具故障，最终必须重做真实File/metadata/原key两POST、按需读与zip、reload零新增写；HF-DOC390只OUT_OF_SCOPE，原断言和历史证据保留。HF-AUTOMATIONS旧消费者缺口必须由真实显式归档health FAILED2→CHECKED/旧告警消失关闭，alias/default零历史请求是另一个负控；三皮肤9GET+两个原字节安全导出在W3production31核，禁止复活已退役写入。其余DB2/Role6归组长/A，本组不冒称执行其最终批。旧11当前桌面6、窄屏5，不能把取消窄屏称全11重跑通过。

最终顺序为parent GO旧49（新release前后verify、workers1/retries0）→等旧260全结束/停止dev→parent另GO W3生产31；当前两批均NOT_RUN。非自身路线/三皮肤图片只使用组长最终release截图和行为raw，拟查Workflow/Designer/Task-Recovery-Publication/Role-Database的选中披露、关键错误与身份、局部滚动/焦点/overlay和真实画布几何；不把本组Knowledge/PPT/catalog自测图当独立审查，也不以像素代替行为/资源/GC。准备台账见`release-readiness.json`。


## release最终旧桌面49：新源码整批真实通过

按组长最终GO，完整10spec原49桌面定义于`release/legacy/B.json/log/B-results`实际执行 **49 PASS／0 FAIL／0skip／0flaky／0retry／0errors、exit0，3.2分钟**。运行前后官方utility实际verify0，817文件/sourceSHA均为新 `d1a38dd1471e5c5d0d23aeafa845c7494d3bc30e2d5875dd37c24e9faded596d`。这次完整运行没有沿用旧a882批或单项候选填数；逐标题multiplicity missing0/extra0，每项仅一个passed attempt/retry0。JSON与log/命令/hash、物理源码行和注册文件分别记在`release-legacy-results.json`；原14纯窄屏仍OUT_OF_SCOPE。

原HF-DOC1440再次在当前真实React目录/Document run完成原文件上传、明确同key两POST、冻结原文/总报告各按需一次、准确zip名、reload零额外创建，末尾实际截图成功。原HF-AUTOMATIONS实际显式归档health FAILED连续2次→CHECKED检测正常、旧Git错误清除再次通过；redirect/default零历史GET是另外已执行负控，未冒消费者正控。其三皮肤全部9GET与2安全原字节导出仍等W3production31，不调用旧退役writes。

最初沙箱只读utility的git子进程EPERM已单列`B-verify-before-sandbox-env-blocked.*`，当时未启动浏览器；按常规授权require_escalated机制运行后verify0。不是源码或浏览器失败，也没有改变Chromium flags/网络设置。完整49没有重试、改源码或扩大时限。

本组browser已自然结束；组长随后停止dev，最终W3production31需另GO。当前不启动额外browser/server。只读非作者图片已核组长当前lead.json相同三皮肤Role附件与实际PNG逐SHA一致，图中文字/选中权限/MCP可读；精确边界见`non-author-release-images.json`，未把组长88作为本组运行或将像素宣称业务/资源/GC证据。


## 最终release：本组49旧桌面＋31生产完成

最终W3命令使用原`e2e/w3/playwright.config.ts`自动生产preview41773、workers1/retries0、list+json，无HMR/proxy/真实模型或后端：

```sh
CANVAS_W3_EVIDENCE_DIR=/workspace/react-full-w7-evidence/release/w3/evidence PLAYWRIGHT_JSON_OUTPUT_NAME=/workspace/react-full-w7-evidence/release/w3/browser.json ./node_modules/.bin/playwright test --config e2e/w3/playwright.config.ts --workers=1 --retries=0 --reporter=list,json --output=/workspace/react-full-w7-evidence/release/w3/results
```

实际 **31/31 PASS、exit0、76.18秒、0skip/0flaky/0retry/0runtime errors**，JSON SHA256 `c6a94f1c199141da8db6c2ebaa1f916f20c27874d41d3afb1e4250a6b8ae02dc`。原31 literal title multiplicity missing0/extra0，每项仅一次passed attempt。前后官方utility verify0均817/source d1a；实际dist127文件前后及当前逐SHA一致。默认/选中共33PNG全部有两个连续相同buffer，PNG SHA与stability JSON全匹配。精确命令、逐case、源与bundle哈希在`release-production-results.json`，外部原始证据在`release/w3/`。

本批3个共享read-consistency＋PPT10＋目录/Document/Source/Knowledge两态和历史归档18是31个实际注册；与旧49内共享三项的重复注册均真实跑了，不能去重冒少跑或称80个唯一业务能力。组长最终global utility实际521注册/518唯一名称/521attempts、0retry/missing/excess/failed；本组只读核该真实`release/browser-accounting.json`，不冒称执行其余组所有测试。

历史HF-DOC1440的原上传/metadata/原key两POST、按需原文/总报告、zip/reload零新增写与真实截图在最终旧49关闭；HF-DOC390仍OUT_OF_SCOPE且源断言保留。HF-AUTOMATIONS最终健康消费者FAILED连续2次→CHECKED检测正常和旧Git告警清除在两份原注册均通过；三皮肤**明确打开**实际`/template-tasks`归档后九个合法GET、两份原UTF-8导出逐字节相等均通过。默认目录与`/automations`redirect零历史GET是另一个负控；没有复活10个退役写入口，不把现代TemplateTask IDs当旧rule/version/run。

协议映射维持：Knowledge question原id/version/answers/key固定，PREPARED不当已完成，只有合法GET同身份ANSWERED才结清且POST1；PPT busy/pending/propertyDirty可查看原草稿但不能结束/危险写，原File/key/body/revision和accepted仅读、普通dirty确认不变；模板Report/Source/Document沿原实际端点、请求键、顺序字节和CAS恢复，已接受Task/run只合法原ID读取和显式原Start，不造GET-by-request或render自动重写。旧49与相邻永久单测分别给这些合同证据，截图不替代原transport/guard断言。

本组重新核27个raw文件的**69个第一退场快照**：15页面场景×3轮45、3历史归档退出、3PPT默认退出、6PPT活动手势×3轮18。各自page root disconnected、listeners/RO/RAF/capture/timers严格空、退场至首样无move/up/cancel/windowblur；原哨兵与应用层media listener身份仍活跃，独立Ant Modal模块timer完整留在allTimers归属证据，不误删/清他人资源。Knowledge现有会话单SSE在样本中打开一次且原owner退出关闭，终态Document/Source样本不假称有活动SSE；全局accounting订阅不被这些路由清掉。PPT手势先trusted pointer/capture激活并保DTO→屏幕原105px/20px几何，再首样，natural mouseup在样本之后。69是重叠raw样本数量，不是额外69个测试。账本为了计数保留resource/target引用，**没有heap/GC回收证明**。

## 非作者最终W5三皮肤30图与动作审查

实际逐张`view_image`看完C提供的最终W5 **30PNG**：五入口默认15＋选中6＋新建UNKNOWN3＋正常连线3＋分段回执2＋Designer原File恢复1；30PNG均1440×1000、与stability逐SHA一致。B未重新执行C49；只读核其真实49/49、0retry/errors和127bundle前后/当前一致，重新审64个strict首样原硬零。对原31route清单，本次覆盖5个W5入口，不能声称所有31的全部状态。Workflow14及Designer4共18张是非作者源码审查；Requirement12张来自B此前实现，明确只作自源像素核查，不冒独立实现审查。本组W3自己图亦不冒独立，由C另审。

默认图、6选中图中的节点/曲线/控件和中文语义三皮肤可读；选中节点右侧context位于桌面视口内，advanced按需披露。正常连线图只有最终一条edge，trusted handle操作、换肤不清图、undo/redo且零隐式写，必须以`production-routes.spec.ts:188`实际assert支持。两分段图与raw核原layout key/body/CAS：graph1/layout2且最后GET失败→仅读恢复不重写；明确409拒绝分支graph1/layout3，三个layout正文逐字节相同，无自动重试且保graph已接受事实，不误称UNKNOWN。

Designer UNKNOWN图显示后来未发送正文及关键阻断/恢复；actual raw两个multipart的submissionId/content/scope/expectedDiscussionRevision1/expectedDesignRevision0完全相等，原`original.txt`字节为`original\0bytes\n中文`，没有后来File混入原重试。恢复后正文及`later.txt · 11 bytes`由原实际expect保持；**后来附件行在这张截图屏外，不能从像素声称字节证明**。所有图内容都是确定性模拟项目/会话，未看到凭据、secret或真实私信；仅本批fixture边界，不承诺任意真实内容没有隐私。

明确保留一个非阻断像素例外：`spdb-workflow-partial-accepted.png`和`tech-blue-workflow-partial-rejected.png`的全屏左上shell折叠按钮遮住顶部通知开头约两字；下方重复的完整关键阻断/原身份恢复正文和按钮清楚可用。组长也实际看图确认，选择明示该装饰例外而不为此解冻/扩W7。未声称“所有像素无瑕”。准确30图逐项、所属作者、截图能/不能证明状态、64raw及动作/source/JSON定位在`non-author-release-images.json`。

首轮ENV_BLOCKED的49次Chromium无法启动、schema/DTO或旧selector夹具错误、第一次817批PPT真实恢复入口红、中间新增负控预期错误及工具首次注入监听诊断全部分层留原raw，不改写为当前业务红或抹去失败。最终源码自v3冻结后不再修改；本组最终原始运行已自然结束，当前没有B浏览器或待开发动作。
