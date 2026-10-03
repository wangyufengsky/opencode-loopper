# W3 Knowledge、模板目录与历史归档

本报告由 B `/root/react_legacy_canvas` 持有。既有启动记录是 `gpt-6.1-sol / xhigh`；本轮工具不能实时读取平台模型字段。基线为 `a77b86ac`，工作区 `opencode-loopper-react-full`。生产归属仅 `frontend/src/pages/w3/knowledge/**` 与 `frontend/src/pages/w3/templates/catalog/**`；路由、Vue 桥、共享富文档、中央语义、旧 store/API/W0 tests 和集中门禁由对应 owner 持有。

## 冻结与实际作者结果

生产与测试于 `knowledge-catalog-final-v5` 冻结：7 个 spec、96 个实际用例，96 PASS / 0 FAIL / 0 SKIP，命令退出 0。Knowledge 为 45（18 controller、6 browser、21 React 页面），catalog/history 为 51（18 creation、6 catalog controller、18 history、9 React 页面）。其中 React 页面使用真实 Foundation/Ant 组件及公开 owner，未把 Legacy Vue 结果充当 React 结果。

```sh
# cwd: frontend；不运行全量/build/browser
./node_modules/.bin/vitest run \
  src/pages/w3/knowledge/controller.spec.ts \
  src/pages/w3/knowledge/browser.spec.ts \
  src/pages/w3/knowledge/pages.spec.tsx \
  src/pages/w3/templates/catalog/creation.spec.ts \
  src/pages/w3/templates/catalog/controller.spec.ts \
  src/pages/w3/templates/catalog/history.spec.ts \
  src/pages/w3/templates/catalog/pages.spec.tsx \
  --maxWorkers=1 --reporter=json \
  --outputFile=/workspace/react-full-w3-evidence/knowledge-catalog-final-v5.json
```

原始输出为 `/workspace/react-full-w3-evidence/knowledge-catalog-final-v5.{log,json}`。结果 JSON SHA-256：`e46eb1a47468aadaa81add4cdf6b9ca1bedbd8032a0ad624478af3e2b1a0d845`。两个独占源码目录共 29 文件的完整清单为同目录 `knowledge-catalog-freeze-hashes.json`，清单 SHA-256：`ffd2af7f7fb8151824ac47c9809a4fb7a334d709bc3f4f119f9f0c2f0da2f8be`。

上述为作者聚焦结果。正式类型、全量 unit、build、三皮肤真实 Chromium、路由退出首快照由组长集中执行；本报告不提前宣称这些门禁通过，也不把 jsdom 捕获夹具当浏览器原生捕获或堆 GC 证明。

## 生产入口、权限与行为

| 路由 / 真实入口 | 输入、完整功能与输出 | 单 owner / 资源 / 退出 |
| --- | --- | --- |
| `/knowledge` → `knowledge/KnowledgePage.tsx:22` | 项目选择、已加载 READY 来源显式勾选、全局 provider/model 与其他模型选择、欢迎状态可提前输入、受管模式资格、发送与失败问句恢复；目录 / 文档上传、刷新、移除使用原公开 API 和版本；历史、项目、设置保留原深链与原生锚点修饰键 | `knowledge/controller.ts:27` 为唯一命令、草稿、会话投影与 SSE owner；无 Pinia delegate 或新 Task 订阅。普通文字 / question 草稿确认离开，未决写硬 BLOCK；本地 panel close 不调用 server stop |
| `/knowledge/:conversationId` → 同 React 页 | 读取原会话、冻结 model/source、问题选项与自定义回答、逐页旧消息、实时思考 / 工具 / 答案、等待 / 断线、停止、Token 累计、原消息 receipt 恢复；citation、file URI、来源搜索、目录 / DB 元数据 / Git 原文、原 SHA / section / offset / range | 仅实际 `attachView` 首 lease 建一 SSE，180ms 合并事件与 5s REST 只读核对；末 lease 精确 close、clear timer/interval；forced retire 使迟到结果无投影 / 后续 POST。React Strict cleanup 只 detach，不退休尚在路由的业务 owner |
| `/template-tasks` → `templates/catalog/TemplateTasksPage.tsx:13` | 动态模板、搜索 / 分类、项目与分支搜索 / cursor、远程认证原因与本地分支、目录 picker / 手输、日期增量 / FULL 及结束日 24:00、目录评分 / stage / dimension 配置、需求开发深链、源码能力字段与 preview-confirm、原生文档类型 / 数量 / 大小 / 顺序、明确用户创建 | `catalog/controller.ts:18` 唯一父 owner，三家族 `creation.ts:61` 各持原 endpoint/key/body/templateVersion/File；默认目录没有历史 GET、SSE 或 poll。开始 hashing 即 BLOCK；UNKNOWN 禁止改输入；全局 status 分别呈现三子 owner，即使原模板不在目录仍可恢复 |
| `/template-tasks` 显式历史入口 `automation.readArchive` → `HistoryDrawer.tsx:44` | 五分类历史 Drawer、模板冻结版本 / spec / SHA / immutable / 自动开始批准、四 trigger / 两 approval、旧 rule health、七 run state / evidence / nullable task/draft、关联任务仅读核对、两种原格式导出 | `history.ts:92` 只读归档 owner；constructor/default 零 GET，显式 open 只读 workspace，其余读 / 导出按意图触发；close/tab/id/retire 失效迟到读；自身 Blob URL 精确 revoke，无计时器、订阅或任何旧 writer |
| `/automations` | 保留组长原 redirect 到 `/template-tasks`，query 保留；不是独立历史消费者，不能以 redirect 通过代替 Drawer 读取 / 导出证明 | 原死入口 `AutomationsView` 与原写 mock 测试不恢复为生产能力；默认跳转 / 刷新零历史请求 |

PageChrome 是唯一 `[data-react-page]`。知识与目录内部采用 `[data-w3-workspace]`（`KnowledgePage.tsx:44`、`TemplateTasksPage.tsx:33`），修复最初重复 root 导致资源账本归属不一致的问题。仅已选上下文显现高级详情；error / 草稿 / 未决回执与恢复入口始终可见。

Knowledge 来源浏览器 `browser.ts:11` 为纯只读子 owner：selected READY scope 不退化为全项目，query/project/close 使迟到检索失效；文件按返回 SHA / 原行 / 解析 section/textOffset 读取。DB 列命中用 `columns`、表命中用 `tables`，与原 `KnowledgeSourcesPanel.vue:70` 一致；数据库手动 table 输入仍使用原元数据 GET，不新增业务数据查询。Git 仍调用原两个公开只读 tool。

知识引用 / 来源 / PPT 共用组长 `RichDocument`。知识明确 `allowImages=false`，file link 转本地 hash 后由 owner 明确仅读，外链维持安全策略。最终 `ReadOnlyCode` 是 **React + 已装 Lezer 纯 parser**，DOM 为 `[data-code-renderer="react-lezer"]`，没有 CodeMirror EditorView 资源；原 CodeMirror 候选仅是历史候选。代码的高亮行号为绝对值，Markdown 的高亮行号为当前内容相对值，`Evidence.tsx:18–27` 分开投影。

## 原身份、接受事实与恢复

| 真实端点能力 | 本次保留的合同 | 核心断言位置 |
| --- | --- | --- |
| Knowledge 创建：客户端 `id` 的 keyless POST + 现有 `GET /knowledge/conversations/:id` | 原客户端 id/project/model 均匹配才接受该会话投影并发送问题；UNKNOWN 仅读原 id，不发第二次 keyless POST。fulfilled 异域回执同样保持 accepted readback / BLOCK，不把别的会话当成功 | `knowledge/controller.spec.ts:21,29` |
| Knowledge 消息：原 idempotencyKey/text，已有请求 receipt GET | UNKNOWN 锁正文；显式 retry 使用原 key/text，mount / 主题 / 读取不 POST；accepted 后读取失败只 GET，不再写 | `knowledge/controller.spec.ts:25–28`、`pages.spec.tsx:26` |
| Knowledge 回答：question/id/version/key/answers | 多选 + 自定义原答案捕获；刷新 metadata 无自动 POST，UNKNOWN 仍锁；服务端 ANSWERED 投影后安全恢复 | `knowledge/controller.spec.ts:36–37`、`pages.spec.tsx:27` |
| Knowledge stop：keyless，原会话 GET | 停止回执先核原 id，再读原会话；不把 STOPPING 冒称停止。unknown 只核原结果，不能泛化为 keyless 重发 | `knowledge/controller.ts:217–222`、`controller.spec.ts:22` |
| Knowledge source keyless directory/upload/refresh/remove | 原 source/version/File 捕获；UNKNOWN 没有真实原操作 lookup 时仍 BLOCK，不能造 GETreceipt 或重复写。明确 accepted 后来源列表 GET 失败可仅读恢复 | `knowledge/controller.spec.ts:34–35` |
| 报告模板 create 原 requestKey + Task GET；另 keyless Start | 创建与 Start 身份分开；已创建 Task 只用 known TaskID；Start UNKNOWN 必先 GET 权威状态，只有显式用户意图且 PENDING_START 才再次同 Task Start。`startOriginal()` 无参恢复，不伪造空 input DTO | `catalog/creation.spec.ts:17–20,25` |
| 源码模板 create 原 key；无 by-request GET | 原模板 / 字段 / version 锁定；UNKNOWN 仅显式原 key 幂等 POST，可核已知 run GET；不捏造 source by-request | 同上三家族循环与 `creation.spec.ts:24` |
| 文档模板 multipart + 已有 document by-request | actual File 实例 / 顺序独立于 immutable DTO；before POST 真 SHA-256。404 是未确认，不能换 key；跨刷新没有旧 File 实例，必须显式按原顺序重选同名同 SHA 文件，不同字节 / 顺序不追加 POST；accepted 原 run GET 后允许精确只读入口 | `creation.spec.ts:13,22–24` |
| 创建明确 400/401/403/422 拒绝 | 原写入未 accepted，解除创建身份，保留页面草稿 / File / 错误，普通 dirty 确认；下一次显式意图才生成新 key。409 未当作已证明拒绝，仍保原 key | `creation.spec.ts:20` 三家族；`catalog/controller.ts:26,135` |

创建成功实际导航 target 仅 `/tasks/:id` 或 `/template-tasks/source-runs/:id`、`/template-tasks/document-runs/:id`。运行页由 A 持有。当前活跃 operation 用 W1 accepted handoff；跨刷新只有 metadata 且 actual GET 核对原 run 时，允许精确只读 destination，不伪造写 receipt。`completeHandoff()` 只在实际导航成功后调用；未知与错误导航不清 metadata（`TemplateTasksPage.tsx:22`、`CreationRecovery.tsx:10`）。

Native FileReader fallback 的 handlers 与 LOADING reader 属于 hash ResourceScope；forced retire 先失效，再 abort / 释放 handler，迟到不会 POST。原生 `File.arrayBuffer()` 与 `crypto.subtle.digest()` 没有取消 API，本次证明的是 token 失效及零迟到业务写；不宣称原生 Promise 即时取消、跨刷新 File 对象恢复或堆内存释放。

## 历史 Automations 九 GET 的具体消费者

该落点实现 [Automations compatibility map](../../automations-compatibility-map.md) 的只读归档，未将 TemplateTasks 的当前创建协议当成旧 Automations 等价写接口。

| 原公开 GET | 实际消费者 / 显式动作 | 保留字段与限制 |
| --- | --- | --- |
| `/automations/workspace` | 打开 Drawer / 概览刷新 | templates/rules/runs/serverTime；真实 health 投影 |
| `/automations/templates` | “历史模板” tab | ACTIVE/ARCHIVED 名称、描述、版本、更新时间 |
| `/automations/templates/:id` | 打开原模板 | 身份精确匹配原 id；原 versions 保留 |
| `/automations/templates/:id/versions` | 同打开原模板 | 原 spec / specSha256 / versionNumber / immutable / autoStartApproved；不重编译、不迁移 spec |
| `/automations/templates/:id/export` | `automation.exportLatest` | 最新原合同 JSON 的原字节，说明不代表某历史版本；拒密钥字段 |
| `/automations/templates/export` | “导出说明” → `automation.exportWorkspace` | formatVersion1 原字节，仅 templates 与有限规则配置；不包含 runs、health、secrets，明确不是全备份 |
| `/automations/rules` | “旧规则与检测记录” | MANUAL/CRON/GIT_HEAD_CHANGED/WEBHOOK、REVIEW_REQUIRED/AUTO_START、version/公共 triggerConfig/health；不返回 token/hash/lastObservedHead |
| `/automations/rules/:id/runs` | 打开原规则 | 仅该 ruleId run，七种原 state / 审计 evidence / 时间 / nullable binding |
| `/automations/runs` | “运行记录” tab | runs/serverTime、原 rule / templateVersion / project binding |

`history.ts:78–87` 使用原公开 `request`/GET transport，export 用原 GET 的文本响应并保留字节，无新增后台接口。`history.spec.ts:12` 顺序核九路由和 GET；`:13–15` 核冻结 DTO、四 trigger / 两 approval / 七 run state；`:18` 核关联 Task 不可读时旧 evidence 不丢；`:19` 核导出格式与密钥阻断。原十项退役写入 **没有消费者**，不会复活：旧模板 CRUD / archive / import、旧 rule 创建 / 更新 / 启停 / trigger、preview-confirm / approval / webhook secret 不因迁移重新开放。

原 health 行为完整保留 `aria-label="自动化检测状态"`、“检测失败 · 连续 2 次”、“检测正常”，只有新服务器 CHECKED 读取移除旧 Git 告警（`HistoryDrawer.tsx:12–19`、`catalog/pages.spec.tsx:25`）。组长 retarget 的 `e2e/read-consistency.spec.ts:34` 通过 redirect 后显式打开 Drawer再读，`:58` 独立负控 default redirect/reload 不请求历史；其本轮真实浏览器结果需以组长最终日志为准。

五分类受控 `ArchiveTabs` 用 Ant Button + React 单 activeKey；Arrow/Home/End 只移焦点，Enter/Space/click 才选择，busy 禁用；tablist/tab/tabpanel 关联公开 ARIA（`HistoryDrawer.tsx:34–41`、`pages.spec.tsx:18`）。旧 Ant Tabs 浏览器候选留下两个 detached touchstart/wheel 是严格资源门槛失败，不能推为 heap leak；新实现不删除库私有资源，不按栈白名单豁免，等待组长新真实首快照。

## 原 Vue 断言迁移映射

旧 spec 保留，以下列出实际行为链与 React 对应验证，不把旧 spec 的绿替代新入口证明。细粒度旧 timer/cursor/状态排列仅在原用例覆盖时仍保留原断言；表内“源码保留 / 部分”不伪称新增 RTL 已执行所有子排列。

| 原行为测试锚点 | React owner / 页面与本轮测试 |
| --- | --- |
| `views/KnowledgeView.spec.ts:65` 空 turn → thinking/answer | `Activity.tsx:8`；`knowledge/pages.spec.tsx:37–39` 等待/终态/最后30工具实际展开；server 逐帧状态排列仍由旧用例 + 集中 browser 验证 |
| `KnowledgeView.spec.ts:86` 精确 provider/model；`:93` 刷新不发命令；`:104` 显式选择不被默认覆盖 | `controller.ts:97–118`；`pages.spec.tsx:22–24` 精确 selected model、pending、失败可 retry 与实际选择持久 |
| `KnowledgeView.spec.ts:111` bare name 不猜 provider | `controller.ts:103–104` 只组合非空 provider/model；本轮源码核对，原专属负控保留，未把默认 model 测试充作 bare-name 全排列 |
| `KnowledgeView.spec.ts:117` catalog 失败默认可用；`:126` early typing；`:135` 默认模型不等 catalog / 去重 | `pages.spec.tsx:23–24`；`controller.spec.ts:24` |
| `KnowledgeView.spec.ts:145` 现有 conversation 冻结model；`:153` 历史入口不等 settings | `pages.spec.tsx:25` actual route/read links、无 catalog；旧 history-before-settings 定向时序未另写新case |
| `stores/knowledgeStore.spec.ts:17,27,38,43` lost key / reload / accepted新意图 / no-auto-send / changed text | `controller.spec.ts:25–28`、`pages.spec.tsx:26` |
| `knowledgeStore.spec.ts:48,63,109` late conversation / stop / updates 作用域 | `controller.spec.ts:30–31`、`:22` foreign stop；每个读函数复核 generation/token；原独立 updates-switch 排列仍旧case保留 |
| `knowledgeStore.spec.ts:56,70` STOPPING 权威与 SSE disconnect | `controller.ts:60,77,217` 原ID/status；本轮 foreign stop 负控与源码核对，旧 STOPPING/disconnect 精确正控未另计新增测试 |
| `knowledgeStore.spec.ts:76,83` 不重下历史、cursor catchup、单调Tokens | `controller.ts:165–190`；`controller.spec.ts:33` original ordinal-1 / merge历史 / usage；多页 catchup 排列源码保留，未声称此单case实测每个cursor分支 |
| `knowledgeStore.spec.ts:97` 180ms 合并/退出清理 | `controller.spec.ts:32` 两lease / replay / exact close；`:23` 异常close独立timer硬0 |
| `components/knowledge/KnowledgeThinking.spec.ts:14,38,48` real output取代等待 / 初始关闭 / latestpreview / only tools | `Activity.tsx:8–18`、`pages.spec.tsx:37–39`；独立 thought/tool disclosure，四 active / 四 terminal 分别运行 |
| `KnowledgeSourcesPanel.spec.ts:21,31,36,42,50` selected search/cursor、原hash/line、textOffset、DB metadata、late query/project | `browser.spec.ts:13–18` 六实际case；DB 同case同时核 column/table 两种原kind |
| `KnowledgeEvidence.spec.ts:6` 无正文也保metadata / incomplete coverage | `Evidence.tsx:14–28`、`pages.spec.tsx:40` DB NULL/range/raw；原 no-text coverage 子排列仅源码/旧case，不把 DB raw 当全部等价证据 |
| `views/TemplateTasksView.spec.ts:31` 需求开发转默认workflow，保project、不旧create | `catalog/pages.spec.tsx:19` 原 href，catalog不显示退役 REQ_DEVELOPMENT；正式router深链由root bridge测试 |
| `TemplateTasksView.spec.ts:41` branch无date / DOC拒绝 | `pages.spec.tsx:23` 原native files / chooser取消 / DOC拒绝；inputs能力由`:22`与源码投影；精确doc-branch/no-date旧排列保留 |
| `TemplateTasksView.spec.ts:53` DATE_INCREMENTAL / FULL不传date | `pages.spec.tsx:19–20`、`catalog/controller.spec.ts:12` |
| `TemplateTasksView.spec.ts:70` auth原因与明确local branch | `catalog/controller.spec.ts:12` remoteProblems/defaultlocal；`TemplateTasksPage.tsx:40` 实际显示并允许选择local；旧完整click-submit auth子排列本轮未单独RTL |
| `TemplateTasksView.spec.ts:84` 服务端source能力 / preview / pending run无Start | `pages.spec.tsx:22`、`controller.spec.ts:14`、`creation.spec.ts:17–20` source循环和`:24`实际destination；完整run深链由组长与A验证 |
| `stores/templateTaskStore.spec.ts:10,20` 原 Task重试Start / lost create同key与新run新key | `creation.spec.ts:18,20,25`；changed/restored精确body/key/Create1/Start数组由root retarget原W0负控继续验证 |

原 `/automations` 退役 UI/mock 断言的具体归档映射沿 compatibility map 与以上实际九GET消费者；旧模板写 mock 不应迁成真实生产按钮。旧 Vue/store/fixtures 尚作为迁移历史行为门禁执行，W3不声称最终全仓零Vue；W6按原 noVue门槛转换/清理可执行旧框架入口，不以 desktop-only scope 豁免。

## 原 W0 红的逐项闭环边界

本人原 W3 15 红为 B3.1 九项、B3.4 四项、B9.2 report key 一项、B9.3 accepted Task/start 一项。新 `w0Creation.ts:2–4` 只导出真实页面 / creation / hash，不另造测试 writer；组长将原 spec接实际 React UI 或纯TSowner，原标题 / body/key/File bytes/SHA/order / create次数 / start数组 / guard断言保留。UNKNOWN UI字段disabled无需强解DOM，外部owner变更正文负控继续证明原operation不变。

组长 `w0-current.json` 的 31 个原W3红标题在 `/workspace/react-full-w3-evidence/w0-integration-second.json` 逐项匹配均 PASS（本人15 + A16）。该 raw 的合并命令总数113=68PASS/45FAIL，实际包括原 W0 111=66PASS/45FAIL **及 foundation/ownership.spec.ts 原2PASS**，不是新增W0、更不是113个W0。本次不把其余45后续波次红抵消或改成跳过。首跑111的索引仍明确“最终冻结后另复跑”；集中最终统计由root持有。

## 中途失败与必要修复的准确分类

| 原始证据 | 真实归因、修复与最终负控 |
| --- | --- |
| `independent-blockers-before` 选中3：0PASS/3FAIL（其余未选不计PASS） | creation foreign receipt 实际发送B消息，确认迁移回归；`controller.ts:202` 原id/project/model gate，`controller.spec.ts:21` final PASS。FileReader fallback实际活跃未abort / handler未清，确认资源缺口；`creation.ts:33–48` owned reader，`creation.spec.ts:13` final PASS。**第三SSE timer1不是已复现产品owned泄漏**，见下一段 |
| `evidence-offset-before` 1FAIL | 非1起始源码引用相对高亮传绝对API，实际丢高亮；`Evidence.tsx:18,27` absoluteLines，`pages.spec.tsx:36` 保首行50、只51 second高亮；最终换Lezer DOM selector，不削行号/text断言 |
| `stop-identity-before` 1FAIL/17未选 | fulfilled foreign stop 实际把conversation投影foreign并GETforeign；`controller.ts:220`先核原ID，新增`:22` final PASS且accepted readback/BLOCK |
| 首批catalog UI / Knowledge pages夹具错误 | 误import另一个spec导致重复test hooks、select label歧义、失败catalog只RejectedOnce后retry成功、thinking hint/content重复匹配；改纯fixtures/真实label/持续失败到明确retry/实际内容selector，不计产品红 |
| `knowledge-catalog-final-v3` 92PASS/2FAIL | SSE aggregate falsepositive，另 selected-model GETfixture返回旧默认model，被新identity check正确拒绝；GETfixture改真实create input id/project/model，不放宽production gate |
| `knowledge-catalog-final-v4` 94PASS/1FAIL | root shared刚由CodeMirror候选切Lezer，而offsettest仍旧 `.cm-line` selector；仅改真实 renderer DOMselector，业务50/51断言保留；v5实际96PASS |
| 集中首批 browser catalog/history | 原 Ant Tabs2监听严格FAIL、导出说明Tab未激活第二export不可达；受控React/Ant Button五tab修复，keyboard/disallowbusy negative +原九GET/两export保留；browser最终未由本人运行或提前宣称通过 |

SSE 异常close初始 `vi.getTimerCount()===1` 经 exact scheduled callback/handle 诊断为 jsdom `Storage._dispatchStorageEvent` 的 0ms 广播；owned180ms句柄当时已被清。原日志 `sse-cleanup-diagnostic2/3.log` 保留 set/clear identity，不能称 confirmed SSE leak。原同closure尾部的异常顺序是静态防御风险：timer与stream改分别登记ResourceScope且closeRefs finally清。最终异常负控显式 `storage:null` 隔离可选平台广播，仍保持 immediate timer0硬断言；没有clearAllTimers、删除他人资源或按栈排除残留。没有原源码+storage:null同一case修前红，故报告只称静态风险补强与最终负控，不伪造产品red闭环。

## 非作者独立审查 C 的 PPT

审查范围为 `pages/w3/ppt` controller、PptStudioPage、Chat、Properties、Plan、Details、Download 与3个spec。本人未修改这些源码/测试，未启动browser。最新Plan明确 `ppt.deleteSlide`语义传给真实confirmation也包含在本次运行字节中，未沿用旧39候选来冒称新版本通过。

```sh
# cwd frontend
./node_modules/.bin/vitest run src/pages/w3/ppt --maxWorkers=1 \
  --reporter=json --outputFile=/workspace/react-full-w3-evidence/ppt-independent-legacy-final.json
```

首轮独立结果39/39 PASS、0 FAIL、0 SKIP、exit0；历史输出 `ppt-independent-legacy-final.{log,json}`，JSON SHA-256 `bcc3391c0f9b9923160404e1ec89c774e56190c6428ee1cfca139d8c9c02eb9b`。对应旧作者冻结清单 `ppt-browser-freeze-hashes.json` SHA `835fc0dc8831d099ada415a1ee9e94095a9bc891961452626a4fada9b6a8fa82`（含browser/source，本文不把browser列算unit）。

以下普通retire合同修正后，本人再次非作者运行相同3个spec，未添加/删除case：**最终39/39 PASS、0FAIL、0SKIP、exit0**。最终原输出 `ppt-independent-legacy-retire-final.{log,json}`，JSON SHA-256 `b7341f41c2b84496697e44ba35ce79515c46588122c001d5c95463a51163c8dd`。本次实际13个PPT目录文件另存 `ppt-independent-legacy-freeze-hashes.json`，清单 SHA `97e3a8821b560b9ec631915e96b6c695830c7f9022c4622fec3d8df659ca0133`；controller SHA `ca4d510736a2af3b7e89b3777c158fd72ff009f9ae59c86b6501f7fe54a60084`、controller.spec SHA `2847292a86a69fb74a329b315531809fa68e504d5e5101656620f85eba2d99d3`。旧结果/清单保留原字节 provenance，不能充作修正后browser通过。

| 核查合同 | 源码链与独立实际复验 |
| --- | --- |
| sole writer、原id/key/body/revision / scope / question version | `controller.ts:156–190,225–269` copy request后唯一execute，keyless stop/retryJob仅读，原scope/文字/key/revision精确重试；`controller.spec.ts:18,26,41,59–65` 实际重试数组与原请求保持 |
| accepted读失败 / GET旧revision不解锁 | `controller.ts:212–219,234–240,272–287` 留acceptedRevision、minimum大于原revision，读取旧projection仍BLOCK、不重复POST；`controller.spec.ts:29–38` actualGET失败/旧200/恢复均复验 |
| coherence、late / 其他owner不可污染 | `controller.ts:115–147` summaryid/单调版本、plan/generation一致性、bounded3read、token；`controller.spec.ts:50–56,80` forcedretire晚receipt与老check/read不投影B |
| File实际实例 / SHA / metadata | `controller.ts:328–354` 原File引用＋实际SHA / size / name，unknownreselection不接受不同字节，旧无SHAmetadata不可伪同文件；`:240` upload读READY核同identity；`controller.spec.ts` 的原File/oversize两case实测byte负控与无旧SHA不伪同文件 |
| SSE / 4s REST / dirty autosave ownership | `controller.ts:82–96` 一lease读资源，4s仅active/disconnected GET；dirty change后900ms显式已输入意图保存一次，stale不自动mergewrite，关闭editor只pause保草稿；`controller.spec.ts:15,68–71`、`editors.spec.tsx:24–27` |
| React旧canvas与查看历史只读 | `PptStudioPage.tsx:27,31,44–64` owner retained/lifecycle+真实PptCanvasView/PptSlideNavigatorView；`PptStudioPage.spec.tsx:20,32,38,44` 实际键盘写、unknown锁、三skin保持同DOM/POST1、历史readonly |
| blur / root / download cleanup | `PptStudioPage.tsx:36–38` 实例window blur/outsidepointer精确remove、自有capture释放；`PptStudioPage.spec.tsx:56` jsdom模拟capture blur/unmount仅此证据；`Download.tsx:8–25` AbortController+Blob/timer ownedcleanup；`editors.spec.tsx:30–33` 原artifactURL与URLrevoke实际复验 |

P2普通API合同缺口已关闭：独立静态发现原 `controller.ts:363` 公开 `retire(false)` 在foundation正常退出guard之前设置 `retired=true`、取消timer/订阅；直接调用false遇pending/dirty会使底层拒绝退休但公开owner不可恢复。实际React页面只在授权路由销毁调用 `retire(true)`（`PptStudioPage.tsx:27`），因此没有正常路由可达故障/修前实跑红，不能冒称新路由回归。组长授权C最小修正：普通retire先读当前guard，拒绝时不改变owner/资源；真正允许退出才失效清理。同一 `controller.spec.ts:18` 原case补真实 dirty / SENDING / UNKNOWN 三阶段 `retire(false)` 拒绝、SSE仍活跃、同原key/body显式retry后正常detach只close一次。上述最终39独立实跑包含这一实际负控，本人未修改C源/tests，未增加case或放宽断言。

方法边界：39只覆盖现有确定性API/SSE mocks与真实React组件，不证明后端CAS实际事务、真实模型执行、File跨刷新对象保留、browser捕获或所有库资源账本/堆GC；三皮肤页面/首退出监听/RAF/RO/capture须引用组长/C最终Chromium raw。本次无后台付费调用、安装、build、全量运行、提交或外发。

### PPT 局部画布样式祖先与屏幕坐标复核

组长真实 selected 截图发现灰色原生对象按钮遮挡预览、DTO x/y 未实际定位；根因是原 `components/ppt/pptEditor.css:206` 的 `.ppt-page .ppt-canvas-object` 祖先限定没有被W3入口满足。C只在 `PptStudioPage.tsx:88` 的 Canvas、`:93` 的 Navigator 各增加局部 `div.ppt-page`。PageChrome/Foundation/root不在这个祖先之下，助手、表单、status/confirmation没有被旧页面样式包住。祖先同时继承原 `pptBase.css:1–9` 主题底色/字体/box-sizing，这是复用原画布样式边界的预期效果。没有改底层React画布、navigator、sharedcss、controller或receipt协议；本轮 `git diff -- frontend/src/react/ppt frontend/src/components/ppt` 为空。

现有 StrictMode case `PptStudioPage.spec.tsx:20–21` 补实际Canvas/Nav局部祖先与root不在该祖先的负控，保一live SSE、无隐式write/preview/job、unmount close一次；case数不变。普通 `retire(false)` 仍在 `controller.ts:363` 先读guard，原 dirty/SENDING/UNKNOWN及同key/body恢复负控保持。本次非作者再次执行上述同一 `vitest run src/pages/w3/ppt --maxWorkers=1` 命令：**39/39 PASS（page13/controller21/editor5）、0FAIL/0SKIP、exit0**。最新输出 `ppt-independent-legacy-local-css-final.{log,json}`，结果JSON SHA `909e2be7e881bf752f06af7f84d4f960074f870fd36ebad246ddb5b3aae4ad3b`。关联 C 冻结21文件清单 `ppt-local-css-freeze-hashes.json` SHA `0e658dc220d5b525ab2358bbe27df711ab5cca7f72f9e6f9020f05ff40501dc7`，逐文件核对与运行后核对均0差异；页面SHA `0d744c2211a7ed32f7b6c091db322fe619d84ec45ec056f5ed7ef52f68507712`、page spec SHA `1de6117bf8f63b57148619db0eca70e60ac42e43927cd88d2bd9ae4ff093c37b`。

非作者静态复核 `e2e/w3/ppt-studio.spec.ts:23–54` 六个既有活动case（3skins×drag/resize，各3cycle），未新增case或降低assertion：手势前验证命中真实未遮挡target、对象computed `position:absolute`、DTO x/y/w/h按canvas clientWidth/clientHeight真实投影（`:31–41`）；实际鼠标第一移动仍105/20 screen px（`:42`）。gesture pointScale使用旧画布公开实际surface.width/deck.width，ghost期expectedGeometry由原DTO加105/scale与20/scale并按原round生成；反算DTO精确`toEqual`，另屏幕四坐标`toBeCloseTo(...,1)`（0.05 CSS px量化，`:43–48`）。这些断言检查ghost期而不是自然mouseup后的业务写，适合随后立即SPA退出；不会把ghost本身充作服务端提交。`:49–50` 强制1 capture/trusted pointermove/root connected才退出，`immediateW3Exit`仍在真实root移除的第一microtask读取，先 `assertW2Disposed`、后`:51`才mouse.up。原严格listener/RO/RAF/capture/timer零与无清理move/up/cancel/blur保持，没有工具栈过滤或自然release补足。本人未执行此browser批次，最终projection与截图以组长新冻结构建后的集中结果为准。

共享只读代码高亮复核：`shared/documents.css:15` 仅 `.w3-code-line.evidence-highlight` 改为现有primary颜色10%透明混色和左侧3px inset边线，未改semantic token定义、行号、highlight set、parser/内容/token逻辑。`ReadOnlyCode.tsx` SHA仍 `b2af293550d5bd604c1fcf49a94ed9a6c96c1dc2528df1cbb3fc95d2ae727272`，高亮仍 `marked.has(firstLineNumber+index)`；CSS SHA `cce69cb8b55afd71285f51de21933e4195b8fbe00a4c49733b6bca76c6a489db`。本次静态样式核对不冒称三皮肤对比或新browser截图已通过。

另存本轮只读边界hash `ppt-independent-local-boundary-hashes.json`（旧底层Canvas/Nav、pptBase/Editor CSS、shared code/CSS），方便集中最终图与源码对应。独立结论：局部wrapper范围符合最小修复，39关联unit通过，六活动browser门槛未放宽；未发现新增业务writer或协议变化。最终browser-delivery图与集中门禁尚由root持有。
