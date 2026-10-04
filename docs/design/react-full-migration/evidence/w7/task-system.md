# W7 Task / system 桌面回归台账（A）

基准为 `1e9161e06c01b5aa76b6a2b19789ac8379423195`。沿用原 A 的 `gpt-6.1-sol / xhigh` 启动记录；工具没有实时模型配置读取接口。本轮不新增成员、不安装依赖、不调用真实后端或付费模型。原始运行与 SHA 台账在 `/workspace/react-full-w7-evidence/A`。

**最新门禁判定：A 既定桌面138项全部完成，最终原始结果通过；全站汇总归组长。** 第二冻结 `release/source-freeze.json` 为 **817 文件／`d1a38dd1471e5c5d0d23aeafa845c7494d3bc30e2d5875dd37c24e9faded596d`**。A第二冻结 old12、W6production97、W4production29已分别实际 **12/12、97/97、29/29 PASS**，均exit0/0retry/单次attempt、前后源码不变；production bundle127文件亦前后同。新W6共36图/93记录、W4共27图；A实际目视非A默认30图，并只读补查W5高级12图，作者关系与局限见末节。组长已报告 clean ci、单元2384、type/build、tooling39/accounting13/zeroVue/原2332映射全部通过；A读取最终 `release/browser-accounting.json`，确为521注册/518唯一/521attempt/0retry、missing/excess/failed均空，不冒这些为A亲跑。第一冻结10项单元失败、原首错与817/a8821a…的legacy12、W697/36图原样保留为诊断，早期“待GO”条目是历史检查点，不混成本次release。A未改运行源码／断言，全部浏览器进程已退出。

## 范围与来源

旧六份 E2E 展开共 20 项，其中桌面 12 项，窄屏 8 项保留为 `OUT_OF_SCOPE_NARROW`，不删、不 skip，也不计桌面通过。原窄屏中的非布局业务断言由同文件对应桌面案例完整保留。

| ID | 原路径及声明行 | 标题／展开 | 本轮范围 |
| --- | --- | --- | --- |
| A-CONNECTION-1440/1024 | `frontend/e2e/connection-management.spec.ts:6` | 连接管理工作区与统一 Git 入口 1440px／1024px | 桌面 2 |
| A-CONNECTION-768/390 | 同上 | 同标题 768px／390px | 窄屏 2，保留不执行 |
| A-GAUSS-UPGRADE-1440 | `database-driver-upgrade.spec.ts:4` | openGauss 驱动升级与认证反馈 1440px | 桌面 1 |
| A-GAUSS-UPGRADE-390 | 同上 | 同标题 390px | 窄屏 1，保留不执行 |
| A-DRIVER-{GAUSSDB,ORACLE,SQLSERVER,DB2}-1440 | `database-driver-upgrade.spec.ts:40` | 新增 GaussDB／Oracle／SQL Server／DB2 并独立传递密码 1440px | 桌面 4 |
| 同 ID 后缀 390 | 同上 | 同四标题 390px | 窄屏 4，保留不执行 |
| A-DB-PROGRESS-1440 | `database-progress.spec.ts:9` | 数据库分区配置与真实步骤投影 1440px | 桌面 1 |
| A-DB-PROGRESS-390 | 同上 | 同标题 390px | 窄屏 1，保留不执行 |
| A-PUBLICATION | `publication-validation.spec.ts:3` | 多行提交说明的预览与实际提交一致 | 默认桌面 1 |
| A-BASELINE | `task-baseline-error.spec.ts:3` | 启动基线失败显示可执行原因且不伪造执行会话 | 默认桌面 1 |
| A-LINKAGE-CANCEL | `linkage-regressions.spec.ts:13` | 任务 A 的取消确认在浏览器后退到任务 B 后失效 | 默认桌面 1 |
| A-LINKAGE-INBOX | `linkage-regressions.spec.ts:58` | 待处理中心的提交失败在重新核验和自动刷新成功后仍可见 | 默认桌面 1 |

追加 `e2e/w4/production-routes.spec.ts` 全 29 项及 `e2e/w6/production-routes.spec.ts` 全 97 项，均为桌面。本轮计划唯一桌面案例为 **12＋29＋97＝138**，重复诊断运行不再相加。W6 根卸载 6 项／18 循环由组长负责，不计入 A 的运行结果。

六份旧 E2E、W4/W6 浏览器测试及 Inbox、Recovery、Publication、DB、Runtime 源码均非 A 作者。TaskDetail 核心曾由 A 实现，读其源码或 A 自己修复后的测试不能冒称非作者生产审查；本轮生产浏览器测试为组长／其他成员编写，A 独立于测试作者运行。

## 保留的合同与执行顺序

1. 旧桌面 12 项先原样运行：严格 driver/profile/password/JDBC、test=1/save=0、test/save 相同 body、原版本、逐连接结果与错误、统一 Git 入口、12/14／85%／Session 2/4、原提交正文、基线失败、旧任务确认隔离及 Inbox 原 CAS。
2. 固定首错、源码 SHA、JSON／trace 后，才能最小纠正已证实的旧选择器或夹具；核心业务断言不放宽。首轮旧自动选中失败不证明 DB 缓存缺陷；第二轮触达原 cached-result 断言后，两桌面案例真实失败，详见下表。
3. W4 29 项独立复验：三个皮肤的四路由直达／刷新／真实 Back／Forward与三次退出，四类 root dispose，native EventSource Last-Event-ID=17、REST 生命周期权威，UNKNOWN 禁离开、原 GET 恢复不重 POST，Recovery 同原 mode／known child，Session 草稿与问题身份，活动 stage pan、publication dirty/focus/换肤。
4. W6 97 项：31 entries×3 skins＝93，加缺 session redirect、同文档 native POP dirty／UNKNOWN 两项、未知 MQL 负控一项。保 query/hash aliases、原 owner/key/body、合法显式原 POST 恢复和 App/page MQL 精确归属。

资源首次快照在 DOM 真实路由点击的 root-removal microtask 或公开 dispose 的同步返回时取得。活动手势的 mouseup 在首次清理断言之后；后续 450ms 只证明无迟到 GET，不能代替即时清理。严格监听／RO／IO／capture／RAF／timer门槛不因 detached target 而过滤。账本覆盖的目标和类型保持公开，不能声称所有 DOM 监听、GC 或堆保留已经证明。

## 历史 DB 两项

`evidence/w0/historical-failures.md:15`、JSON 的 `HF-DB-1440`／`HF-DB-390` 原首错是等待已过时的“主机”输入。W0 最小改成真实 JDBC URL／用户名后两项通过，保留 probe invalidation、test/save 次数与批次投影断言；分类为 `STALE_EXPECTATION`。390px 为历史 `OUT_OF_SCOPE`。W0 模拟浏览器通过不证明真实数据库连接或 Java／模型执行，也不替代本轮 React 回归。

## 当前状态

初始清单为 `A/initial-inventory.json`，记录八份 spec 的原始 SHA、20 条旧标题与精确范围。旧桌面首跑使用组长专用 Vite 41773、单 worker、零 retry、未知 API 返回 501；W4 的 native-SSE 静态 production runner 和 W6 41776 preview 由组长分阶段放行，A 不启动共享服务。

首轮 `legacy-first.json` 0 PASS／12 FAIL／0 skip（exit 1），原八份 spec SHA 前后匹配，副本在 `legacy-first-source/`。第二轮 `legacy-second.json` 6 PASS／6 FAIL／0 skip（exit 1），仍是诊断候选；其中 SQL Server trace 明确出现第二次 Vite connecting／React DevTools 和整页重新初始化，本轮没有将它记为产品缺陷。W4 29／W6 97 仍 `NOT_RUN`，等待组长 production runner 放行。

| 达到的旧合同 | 分类及证据 | 最小处理／状态 |
| --- | --- | --- |
| 初始自动选择连接、Element Plus drawer／message-box、旧 host placeholder／heading | `STALE_SELECTOR`；第一轮 trace 未到核心请求断言 | 显式选择真实 React 对象，使用 Ant complementary/dialog、既有 JDBC 字段；原值和请求断言保留 |
| 连接 one 测试成功→two 失败→切回 one | `REPRODUCED_PRODUCT_FAIL`；第二轮两个宽度均丢原成功 status，trace 有 one/test 200、two/test 400；原 Vue 分连接缓存，而新 DatabasePage 选择时清单值 probe | 组长已最小按 id/version 缓存修复；A 不写该生产目录，第三轮两个原缓存断言均通过 |
| AWAITING_DECISION＋FAILED 不应继续执行 | 第一轮真实状态提示仍称“系统按服务端状态继续执行”；最小 W4 projection 修复 | `projections.ts:43` 保原终止句；`taskController.spec.ts:18` 真状态正控＋RUNNING 负控，作者独跑 17/17（不算独审）；B 非作者同 17/17、exit 0（`B/task-projection-independent.json` SHA `c8fcc28d8cdafc9ad2442b6d2334d87839bad5078edd772bab8e404520e89422`），两者为同案例不相加。第三轮原浏览器终止句与恢复原因通过 |
| Inbox 原 409 错误在成功 list 刷新后仍可见 | 第二轮原句完整存在复合 alert，原独立 text-node exact selector 不匹配 | 改 actual alert＋同原句，不改 CAS／次数／自动刷新断言；第三轮全部通过 |
| 任务终止原句 | 第二轮原句已在 status 段落，独立 text-node exact selector 不匹配 | 改 `.w2-status` 含同原句；原可执行错误和禁止伪造泛化错误断言保留，第三轮通过 |
| 模板任务 12/14、85% 和会话 2/4 | 第二轮新真实 failed-batches GET 被旧通配 `[]` 假冒 CursorPage，UI 抛 rows.length | 仅补真实 CursorPage 空列表／facets，保所有数值与 Session 正文断言；第三轮通过 |
| SQL Server 创建表单 | 第二轮 trace 在 fill 前真实 full reload，其他三 driver 通过 | `DEV_RELOAD_OBSERVED`，第三轮稳定运行通过；原第二轮仍记录 full-reload 失败，不删除／覆盖 |

六份旧 spec 的 20 条展开标题／数量保持；窄屏八项原样保留。A 的截图改放各自 Playwright outputPath，避免修改旧共享截图。第三轮完整 **12/12 PASS、0 skip、0 retry、exit 0（32.7s）**：`legacy-third.{json,log}`。六份 spec 运行前后 SHA 相同，`legacy-third-source/`、`legacy-third-verification.json`、`legacy-case-results.json` 保存实际命令、各标题、次数与哈希。原 20 条标题可实际 `playwright --list` 重数（`legacy-title-list.log`），其中仅精确八项 390/768 尾缀排除。八文件作者冻结清单 `A-source-freeze-hashes.json` SHA `9346806e46cc18f352116c09ddda6791af5bb9ceb8e31d6de912609ddbf5c368`。

静态辅助清单 `legacy-assertion-shape.json` 确认六文件所有标题表达式相同，literal expect 调用为旧 66／新 68（Progress 将原 12/14／85% 合成 aria-label 拆成两值断言，Linkage 补退休确认不可见负控）。literal 数量仅辅助审查，不冒称语义或运行通过证明。

第三轮是组长 test-only Vite 的真实 Chromium 行为证据，**不是最终冻结 production bundle**，也不证明数据库网络、真实 Java、模型或凭据可用。W4／W6 仍未运行；等待统一最终 build／preview 放行，最终 source/bundle 改动后需相应重跑。

## 非作者 Designer COMPLETED 交接 delta

C 的原 before 报告为 2 PASS＋1 实际 RED；这是 C 取证，A 不冒称自己运行该红。A 独立读取当前 diff，并于干净依赖下实际运行 `vitest run src/pages/w5/designer/controller.spec.ts --maxWorkers=1 --reporter=json`：**17/17 PASS、exit 0**，`A/C-designer-independent.{json,log}`；运行前后 controller SHA `01aaa6c13bde5a66c9e334de18c6e015577dc5a1e08692d19064f0ee5425d2b9`、spec SHA `f853d8b9dc90e80129f189f02a51416fa9560a2ee43d1a95291394398c6a74a6` 匹配冻结，输出 SHA 在 `C-designer-independent-verification.json`。

`controller.ts:103–104` 仅补 `COMPLETED && lastAction === TASK_START_REQUESTED` 的真实既有任务读取交接；仍沿原 hasUnsentDraft、SENDING／UNKNOWN／ACCEPTED_READBACK 阻断。`openKnownTask`（约 215 行）核原 taskId、GET overview、audit、ticket 和 confirmation 后才导航，不创建或重发任务。`controller.spec.ts:12–38` 实际验证已完成历史自动模式 GET→唯一原目标导航、0 create，以及 dirty／UNKNOWN 的原消息／File／command 保留、BLOCK、0 GET／导航；既有迟到、accepted-read、keyless 正负控同批通过。此结论限定该 delta 和这 17 项，不推广整个 Designer 页面／浏览器，也不重新承诺跨刷新恢复。


## 非作者 Designer presentation／画布高度 delta（续接）

C 四份 runtime／unit 文件保持冻结：DesignerPage `647eeb543d15d9d3e91054bfe76554ec70e49ad1492a121fcb22ace7d94e1feb`、Page.spec `9c5d24c5dc78a872fb6cfd358cb10579622f791befdeb3a1f12e9567fa7a1a71`、designer.css `9cf667c20aa25fa82eca1029b3842c40fc251867b88da03c068c4d7f0f88b432`、workflow-editor.css `24133a56b33733f633a1d9f23f14b90e6bfe429515a23eabc9fc85f8372271f9`。

A 实际独跑 `DesignerPage.spec.tsx --maxWorkers=1` 为 **13/13 PASS、exit 0**（并非 14）；输出 `C-designer-presentation-independent.{json,log}`。C 的 before 是同文件 13 枚举中 **1 实际 FAIL＋12 过滤未选**，不计 12 为 PASS。`DesignerPage.tsx:33–35,44` 与旧 `DesignerView.vue:218,390` 的权威 WAITING_INPUT／SESSION_ERROR＋末条 deliveryState 判定一致；告警在主 status 常显，沿 `userFacingError` 中文映射，不打开历史也能看到。新 RTL（`:16–29`）实际验证无需展开、无 raw SYSTEM_ERROR、0 create，REST 状态恢复为 REVIEWING 后当前告警消失而历史仍保留。未新增写入／订阅，也未覆盖整个业务恢复矩阵。

矩阵 delta（`:47`）仅把原 criterion 的 description 和原 verificationMode／machineCovered／verifierIndexes／judgePlanned 拆成两个 paragraph，保数据与只读呈现；没有添加输入／按钮／write。`designer.css` 只补局部描述字体／次要文本 token。C 第一 browser 首错在旧按钮选择器，未到矩阵 bbox，不能称矩阵原样已跑红。C 第二候选真实执行已通过矩阵原 bbox 断言后，失败于后续 Task 开始文案选择器（`legacy-iteration-1.json`）；这个子步骤正控不能冒整个 case PASS。

画布高度是 C 原 browser 三皮肤实际 **732 ≤ 750** 的红（`legacy-before.json/log`），非选择器差异。最小 CSS 仅 `height:calc(100vh - 210px)`→`calc(100vh - 170px)`，增加 40px；原 min-height、toolbar flex、canvas min-height 及 React Flow、pointer controller 均无变化。当前 A 只读核查；未以 jsdom 声称实际 height 通过，最终 browser 待 C／组长稳定版运行。

A 在组长既有 Vite 41773 独复原“附件设计投递失败…历史”单项，**1/1 PASS、exit 0（4.8s）**：`C-designer-terminal-browser-selected.{json,log}`；保持原中文原因、兼容提示、附件、raw-code 禁止和 reload 保留全部断言。第一次 grep 使用整串标题起始锚，未选中任何 case（exit 1、`C-designer-terminal-browser.log`），明确 `TEST_SELECTION_ERROR`／0 执行，不计产品失败。观察窗口内四份 runtime/unit SHA 相同，整个 `designer-discussion.spec.ts` 被作者继续修另一案例（`5efc77…`→`6f45ed…`）；该单项按实际候选证据记录，不冒全 browser-spec 冻结版绿。完整哈希与命令见 `C-designer-presentation-independent-verification.json`。

Designer 单项独复属于 C 既定案例的交叉验证，不增加 A 的原 12＋29＋97＝138 唯一桌面案例计数。

## 全 31 路由／动作矩阵：只读核对，最终生产待跑

以下是本轮源码和既有合同的只读核对，**不是 31 路由已经通过 W7 的声明**。`router/index.tsx:8` 实际列出 31 records；`:10–16` 保持 28 页面记录＋`/automations`、`/settings/roles`、`*` 三项 redirect/fallback。`app/App.tsx:24–64` 的真实 RouteScreen 按 `scope.key` 加载 W2–W5 出口并使旧 promise 失效；`:69–81` 用同一 React Router 的 blocker 处理 native POP。W2、W3、W4、W5 loader 分别在 `migration/w2Routes.ts:5`、`w3Routes.ts:5`、`w4Routes.ts:5`、`w5Routes.ts:5`，不另建 history。

表中入口路径相对 `frontend/src/pages/`。`只读`表示 A 读当前入口／owner 和对应波次合同；`A候选实跑`仅为本报告已记录的真实 Vite Chromium 或独立 unit 子合同，不能扩成整页完整验收。A 曾是 W2 workflow/roles、W3 runs、W4 Task、W5 Workflow 的基础实现作者，对这些源码的回核也不是非作者生产审查；本轮组长／他人写的实际浏览器测试另有来源。**每一行的 W6 31×3 production direct/reload/Back/Forward/首次资源快照仍为 `NOT_RUN_FINAL_WAIT_FREEZE`**；W4 对应四入口的额外 29 项也同样待组长 GO。其他人的候选日志只作“读证据”，不计 A 运行结果。

| #／正式 route | 当前真实 React 入口及源码行 | 可发现动作及业务所有权 | 已迁移的原合同／本轮 A 状态 |
| --- | --- | --- | --- |
| 01 `/` | `w2/secondary/HomePage.tsx:17` | 已读 Projects／Knowledge history 的本地筛选；选择后原项目／会话深链、四个主入口及更多入口。无新 global recent API、虚构指标或写入。 | W2 [secondary](../w2/secondary.md) Home/native-modifier 合同；只读，最终待跑。 |
| 02 `/projects` | `w2/core/ProjectsPage.tsx:14` | 登记／目录选择、文档目录、Git/GitLab 凭据、辅助证据、读取／生成／应用／停止项目公约、取消管理；Projects 与 credentials 原 controller 分持版本、草稿和命令。 | W2 [core](../w2/core.md) 原 Projects／directory／credentials 映射；A连接 1440/1024 实跑原 Git 入口及保存次数，未遍历全部项目动作。 |
| 03 `/ppt` | `w2/secondary/PptListPage.tsx:14` | 搜索／归档范围／分页、选中打开；原资料 File 和 SHA 顺序、关联项目、创建→上传→发送各段恢复。新建未接受前保持原 owner。 | W2 secondary 的 PPT creation 合同；只读，最终待跑，不拿作品详情或设计原型证明创建。 |
| 04 `/ppt/:id` | `w3/ppt/PptStudioPage.tsx:20`；`controller.ts:59` | 真实 React 自由对象画布、选中属性草稿、修订／历史／生成／Agent／原生问题、导出下载；自动保存只送当前已捕获版本，pending/unknown 阻离开，原操作显式恢复。 | W3 [PPT](../w3/ppt-browser.md) 原 Studio/409/乱序/即时清理合同；只读，最终待跑。 |
| 05 `/knowledge/history` | `w2/secondary/KnowledgeHistoryPage.tsx:17` | 项目／日期／状态／归档／关键词、游标、选择后打开／归档恢复；CAS 与 URL/query/scroll 归原页面 owner。 | W2 secondary 的原 history、迟到筛选和版本恢复；只读，最终待跑。 |
| 06 `/knowledge/:conversationId?` | `w3/knowledge/KnowledgePage.tsx:22`；`controller.ts:27` | 无 id 的本地 welcome draft与显式创建；有 id 的原消息／问题／Todo／活动／来源／引用／model／stop、source upload/refresh/remove。keyless create/stop 不按猜测重写，keyed send 保原 body/key/File。 | W3 [knowledge/catalog/history](../w3/knowledge-catalog-history.md) 完整消费者；只读。W6 本条采样有 id，不能冒无 id 的同等覆盖。 |
| 07 `/designer` | `w5/designer/DesignerPage.tsx:20`；`controller.ts:23` | session/profile、讨论／原 File、问题、分包批准／reopen／重编译、结构化 LoopSpec、save/confirm、原 Task 精确读取交接。无 session 由 `router/designerEntry.ts` 转四字段需求入口。 | W5 [Designer](../w5/designer.md) 与 W0 原 21 action 合同；A 非作者 controller17、Page13、原失败告警 browser1 候选实跑，限各已列子合同，最终待跑。 |
| 08 `/requirements` | `w2/workflow/RequirementListPage.tsx:12` | 项目筛选／分页、选择打开原需求、新建携 projectId；只读 list controller，不产生执行命令。 | W2 [workflow/roles](../w2/workflow-roles.md) 原 list 深链；只读，最终待跑。 |
| 09 `/requirements/new` | `w5/requirements/NewRequirementPage.tsx:10`；`newController.ts:11` | **仅项目、流程、名称、目标四字段**；发送／未知全部禁改；显式同 key/body 创建 POST 恢复，无虚构 by-request GET；已接受只原目标 handoff，不重新创建。 | W0 B1.1/B1.4 及 W5 [requirements](../w5/requirements.md)；只读。本轮 W6 native POP dirty/UNKNOWN 两项将实际经 Runtime→list→New，不强解 disabled，尚待跑。 |
| 10 `/requirements/:id` | `w5/requirements/RequirementPage.tsx:23`；`controller.ts:12` | 真实 Flow、结构化专业节点／public inputs／model／候选／部分 graph-layout 保存、confirm plan、single/until/continuous/pause/checkpoint、plan edit/finish/template/export/publication与 NodeRun 证据。父和子 owner 唯一写 gate；REST 执行刷新不重载草稿。 | W5 requirements/W6 原 23 断言与专业正文映射；只读，最终待跑。React 运行画布的 movable 不代表可编辑已执行图。 |
| 11 `/workflows` | `w2/workflow/WorkflowLibraryPage.tsx:13` | 真实 search/kind/cursor、选中只读 Flow、使用／复制／删除；外部点击仅收起 context、不退休 owner；原 copy key/body 与 archive CAS。 | W0 B1.2 四 route BLOCK＋同原 copy 恢复正控，W2 workflow/roles；只读，最终待跑。 |
| 12 `/workflows/new` | `w5/workflow/WorkflowEditorPage.tsx:17`；`editorController.ts:24` | 结构化节点、roles/revisions、I/O/outcomes/completion/retry/public inputs/presets、undo/redo/validate/auto-layout、创建与 graph/layout 分段保存；部分已接受段不得重复写。 | W5 [workflow](../w5/workflow.md) 新建原协议及 W6 61 原测试映射；只读，最终待跑。 |
| 13 `/workflows/:id` | 同一 `WorkflowEditorPage.tsx:17`；`:66` 直接真实 Flow | 编辑／复制／只读 builtin，按选中 context；graph/layout 各原 CAS/key、409/422 后保已接受 graph 和原 layout 身份、显式恢复，accepted 仅读。builtin 节点不可移动／删除而 zoom/pan 可读。 | W5 workflow 与本节后列 C builtin delta；A 只独复 C delta 9 项，不冒基础页作者自测为非作者整页验收；production 高度/gesture 最终待跑。 |
| 14 `/designs` | `w2/workflow/DesignerHistoryPage.tsx:13` | 项目／状态／归档／q／排序／游标、继续设计／修改设置／原 Task design、归档恢复／重试 stop；未知 keyless只查同 session，不盲 PUT。 | W0 B8.1 原四迟到／退休／debounce 红→真实 React helper；W2 workflow/roles；只读，最终待跑。 |
| 15 `/tasks` | `w2/core/TasksPage.tsx:19` | type/project/statusGroup/archive/q/order／cursor、选择后真实 task/source/document run、冻结设计、归档恢复及仅归档普通 Task 的永久删除；单 application Task 读投影。 | W2 core 原 summaries/query/Task 与 report 状态区分；只读，最终待跑。 |
| 16 `/inbox` | `w4/inbox/index.tsx:12`；`controller.ts:10` | permission ONCE/SESSION/REJECT、mandatory question推荐／custom／reply；原 interaction/version/external identity、retained 原项和草稿、unknown 只原 list 证明确切 resolvedAction，刷新不清 command error。 | W4 [inbox/actions/history](../w4/inbox-history-actions.md)；A linkage Inbox browser原 ONCE/version4/POST1/成功 poll 后错误保留已实跑，完整 W4 29 待跑。 |
| 17 `/insights` | `w2/secondary/InsightsPage.tsx:19` | server filters／cursor、人工作最终认定、原 Task 深链；unknown currency/null 保原含义，无本地伪造成本或成功。 | W2 secondary 的 quality/amount/source 合同；只读，最终待跑。 |
| 18 `/automations` | `router/index.tsx:12` → `/template-tasks` | 保 query/hash；默认不读历史 API、不恢复退役写。**显式** `automation.readArchive` 才进入真实只读 health/template/rule/run 消费者，不能用 alias URL PASS 冒 health恢复。 | W0 HF-AUTOMATIONS 原 FAIL 保留；W3 history consumer 合同。A 读 B 本轮 candidate2 原 case 已实际 PASS，但不是 A 实跑／最终 bundle；下表分列。 |
| 19 `/template-tasks` | `w3/templates/catalog/TemplateTasksPage.tsx:13` | 动态模板／项目／branch／参数／scope preview、Report/Source/Document 三独立创建身份、File hash、原 Task/Run 交接；显式历史五 tab、九合法 GET、两原格式导出。 | W3 knowledge/catalog/history，原 W0 B3.1/B3.3/B9 creation 和历史兼容；只读，最终待跑。导出 v1 不冒全量备份／secret。 |
| 20 `/template-tasks/document-runs/:id` | `w3/templates/runs/RunPages.tsx:39`；`runController.ts:15` | 原进度与冻结正文／requirements／clarification／supplement File／report下载、cancel/resume、batch CAS与Session diagnostics；父保留资格变更后的 dirty/unknown child，File/body/key 不丢。 | W3 [template-runs](../w3/template-runs.md) 原 B3.2/B3.3/B4.1/B8.3/B9；只读。A DB-progress采样 Task 内模板 panel不冒 DocumentRun合同通过。 |
| 21 `/template-tasks/source-runs/:id` | `w3/templates/runs/RunPages.tsx:61` | PENDING_START、冻结源输入／coverage／artifacts／批次分页／选中重试／归档、cancel、diagnostics。无 key batch 的显式恢复严格原 task/batch/body/version，409 不能改成新代际命令。 | W3 template-runs CAS/stop-proof，W0 B9.3；只读，最终待跑。 |
| 22 `/tasks/:id` | `w4/task/TaskDetailPage.tsx:48`；`taskController.ts:27` | start/pause/resume/stop/retry/judges/rework、queue/lease、Stage/Attempt/Session/Todo/问题/角色/错误/证据/模板报告、B 审批/dirty/gitScope/decision/rolling、C publication。REST 权威；SSE only invalidate，last lease 即清；dirty sibling不被 caller豁免。 | W4 [Task](../w4/task.md)、[publication/recovery](../w4/recovery-publication.md)。A原 baseline/linkage/progress/publication browser子合同实跑；Task projection17作者自测＋B同17非作者，完整 production29待跑。 |
| 23 `/tasks/:id/recovery` | `w4/recovery/RecoveryStudioPage.tsx:10`；`controller.ts:34` | 仅原三 mode FROM_FAILED_STAGE/ALL_STAGES/VERIFY_ONLY 创建恢复**草稿**；lineage不能证明 keyless unknown接受；known child读/精确handoff，不自动 Start、不重 create。 | W4 recovery/publication，原 fingerprint/terminal/unknown guards；只读，最终 W4直达/草稿/unknown/accepted行为待跑。 |
| 24 `/tasks/:id/design` | `w4/history/index.tsx:15`；`controller.ts:8` | 只读冻结 LoopSpec/设计事实、讨论/File列表与原附件preview、hash/证据/版本/模型；原 taskId cache，root/query退休后迟到不改新 scope。 | W0 B8.2 原四红→React实际历史 helper及 W4 history；只读，最终待跑。 |
| 25 `/runtime` | `w2/secondary/RuntimePage.tsx:11` | runtime/server/CLI/MCP独立投影、显式detect/start/restart；未知 keyless GET ONLINE 不能据此证明原命令接受。`props.legacy.task` 名字是兼容 slot，W6实际 `w2TaskPort` 是纯TS application owner，无 Pinia writer。 | W2 secondary 与 W6 App统一读生命周期；只读。A legacy Task fixture经过 runtime GET不冒本页start/restart已验。 |
| 26 `/tools` | `w2/secondary/ToolsPage.tsx:17` | Skills／MCP lazy目录与安全正文、全局/项目配置及 CAS、serverRequired/service可用性、启停/refresh；不以展示配置推授权。 | W2 secondary 原 readonly tool/skill/Mermaid与版本；只读，最终待跑。 |
| 27 `/databases` | `w2/secondary/DatabasePage.tsx:17` | type/profile/driver/JDBC/username/password，test≠save，create/edit/enable/disable/archive、filters/cursor；test缓存严格id/version+active ticket，陌生行error隔离；secret变更 unknown不假查询证明。 | W0 HF-DB＋W2 secondary；A12中8项覆盖连接/驱动/Task真实进度，actual before缓存红→rootfix后原断言绿，最终 production仍待跑。 |
| 28 `/settings` | `w2/core/SettingsPage.tsx:40` | 分区展示但同页 draft owner、模型 provider/id 与 duration/limits、本地路径/CLI、Git credentials、demo；明确 PUT无假 CAS。422保草稿，unknown合法原 GET证据不足时仍BLOCK。 | W2 core 与 W1 ordinary dirty/unknown/File owner；只读，最终待跑，Taskpublication草稿通过不冒 Settings PUT覆盖。 |
| 29 `/settings/roles` | `router/index.tsx:12` → `/roles` | 保 role query及 #prompt等 hash，委托同一个 RoleManagementPage，alias自身无新command。 | W2 roles深链／W6 alias hash真实合同；只读，最终待跑。 |
| 30 `/roles` | `w2/roles/RoleManagementPage.tsx:31`；`roleManagementController.ts:23` | 搜索分页／角色阶段绑定／权限预估／Prompt版本与diff／只读Role Flow；ZIP File校验、bindings CAS publish、409重校验、export。CONFIG_ONLY/complete=false/limitations 不冒执行授权。 | W0六HF-ROLE及 W2 workflow/roles；A只读当前UI＋root三个1440候选PASS记录，最终待跑，不把历史390加入本轮。 |
| 31 `*` | `router/index.tsx:12` → `/` | 非法 route兜底首页；本条明确丢未知 query/hash，与两个兼容alias保留行为区分，无隐藏写／双history。 | W6 actual fallback `/absent-route?discard=A#missing` 精确 `/`；只读，最终待跑。 |

W6 `e2e/w6/production-routes.spec.ts:9–31` 实际枚举上述 31 条×3皮肤，要求 exact URL、单真实 page/main、reload、native Back/Forward、合法 GET/no write/no unexpected，以及路由退出首样。三个额外 contract（`:32–58`）保 missing-session四字段、同文档 native POP dirty Stay/Discard、UNKNOWN原 owner/key/body→显式同 POST；`:60–72` 未知 MQL 负控仍硬失败，不能把未知 global 都认 App。这里只读审查既有测试，不冒已经跑完97。

W4 `e2e/w4/production-routes.spec.ts:69–119` 是四入口×三皮肤与三循环，公开 route-root `disposeIfSafe` 同步首样，真实 native EventSource reconnect/Last-Event-ID=17以及REST权威。其余17行为／root案例与本报告范围重数合计29；450ms延后检查只查迟到GET，不替代同步资源快照。W6全App root6/18loops另由组长负责，Task/Story每实例流和App MQL清零不能仅以page退出推断。

## 历史 11 项逐项映射：历史结果不覆盖，本轮来源分开

权威原证据为 [W0 historical-failures](../w0/historical-failures.md) 与相邻 JSON，before/after原日志及 source副本保留在 `/workspace/react-full-w0-evidence`。W0原11为0PASS/11FAIL→10PASS/1FAIL；不是本轮W7结果。其中当前桌面6（历史after5PASS/1FAIL）、窄屏5（历史after5PASS，全部OUT_OF_SCOPE）。本表不把设计文档修正、redirect绿、纯health unit绿或作者候选整批通过改记为原红业务修复。

| 原 ID／title中的视口 | W0首错分类与原 before→after | 真实 React消费者／必须保留的动作 | W7当前证据来源与最终状态 |
| --- | --- | --- | --- |
| HF-DB-1440 | 旧 host placeholder；`STALE_EXPECTATION`，FAIL→PASS | `/databases`真正JDBC/username/test/save，随后 `/tasks/:id`12/14、85%、Session2/4；见`database-progress.spec.ts:9`及本报告矩阵27/22。 | **A实际**legacy-third该title PASS，fixture修正与真实cache产品修复分列；最终 production待freeze。 |
| HF-DB-390 | 同上，FAIL→PASS | 同一原业务断言和历史无横溢出，原case保留。 | `OUT_OF_SCOPE_NARROW`；未在W7运行，不新计PASS。 |
| HF-DOC-1440 | 业务已到末尾后/private/tmp截图ENOENT；`FIXTURE_ENV`，FAIL→PASS | `/template-tasks`创建File首次失联→显式同key共2POST，`/document-runs/:id`原正文1次、总体报告1次、实际下载、reload0额外create；W3 template-runs/catalog。 | A**只读**B `legacy-candidate2.json`此原title实际PASS（retry0），不是A复跑；该整批33PASS/16FAIL为中间候选，最终待freeze。 |
| HF-DOC-390 | 同上，FAIL→PASS | 同一File/key/按需读取/下载原断言。 | `OUT_OF_SCOPE_NARROW`；历史保留，不新增窄屏门槛或本轮PASS。 |
| HF-AUTOMATIONS（默认1280） | 旧route预期＋实际消费者缺口；`STALE_ROUTE_EXPECTATION + COMPATIBILITY_CONSUMER_GAP`，FAIL→FAIL | `/automations`只alias；真正`automation.readArchive`→`HistoryDrawer.tsx:12–18,44`，`history.ts:92,105–117`按原health GET读取FAILED2→新CHECKED，原error消失；九原GET／两export，不恢复旧write。 | `read-consistency-contracts.ts:35–56`保原title／完整health次数/原因/刷新清错断言并接真实消费者。A**只读**B candidate2此原casePASS，不能以其`:59`独立redirect case替代；W0afterFAIL不改写，W7最终待freeze。 |
| HF-ROLE-spdb-1440 | 精确旧文案失配；`STALE_EXPECTATION`，FAIL→PASS | `/roles`CONFIG_ONLY/complete=false、limitations、工具来源、readonlyFlow、角色版本及桌面bbox不重叠；角色label语义不降。 | A**只读**root `roles-final-candidate.json`该原titlePASS/retry0；该10项为候选，非A实跑／最终bundle。 |
| HF-ROLE-spdb-390 | 同上，FAIL→PASS | 原权限和历史布局断言保留。 | `OUT_OF_SCOPE_NARROW`，本轮未跑。 |
| HF-ROLE-tech-blue-1440 | 同上，FAIL→PASS | 同原权限语义，tech-blue真实tokens／readonlyFlow。 | A只读root同一candidate该titlePASS/retry0，最终待freeze。 |
| HF-ROLE-tech-blue-390 | 同上，FAIL→PASS | 原权限／历史布局保留。 | `OUT_OF_SCOPE_NARROW`，本轮未跑。 |
| HF-ROLE-github-white-1440 | 同上，FAIL→PASS | 同原权限语义，github-white真实tokens／readonlyFlow。 | A只读root同一candidate该titlePASS/retry0，最终待freeze。 |
| HF-ROLE-github-white-390 | 同上，FAIL→PASS | 原权限／历史布局保留。 | `OUT_OF_SCOPE_NARROW`，本轮未跑。 |

本表11个ID与W0 JSON逐项对齐，不合并六个角色case成单个PASS。原W0根因8条字段/文案、2条fixture、1条复合消费者缺口保持；当前React对应消费者是否最终通过，以本轮稳定source/bundle＋真实各title执行为准。B/root候选日志经A读取不是A执行，不与A138唯一矩阵相加。最终31路线截图由组长GO后的本轮production产生，W2–W6旧图仅历史参考，**不冒W7截图**。

## 非作者 C builtin只读节点 delta

C独立原真实before在builtin article focus后ArrowRight使 positions从`{}`变成`24,0`；原Vue readonly没有movable覆盖。C仅删除`WorkflowEditorPage.tsx:66`上的builtin movable=true，无修改React Flow或pointer控制器、保存CAS或业务API。真实Flow默认`readonly`会禁节点drag／键盘位移／connect/Delete，`fit/zoom`仍公开并由原owner保viewport呈现、不dirty。

A独立执行 `./node_modules/.bin/vitest run src/pages/w5/workflow/pages.spec.tsx --maxWorkers=1 --reporter=json`，**9/9 PASS、0skip、exit0**。原builtin fullName未新增/删除；`pages.spec.tsx:81–92`保 actualarticle focus→ArrowRight positions不变、Delete graph不变、dirty=false、0revise/layout/create、无删除确认以及原 zoom>1。Page SHA `41d9e08ba79098bf700ae517ea1ba4ff9aa5da3fce9a85e3540ce0ad88cf2230`、spec `8a83f57447ba8e396e0c806e2ee4811047f24b45331959d2e83a446915be9f9c`前后完全匹配；`A/C-builtin-readonly-independent.json` SHA `ce5520b19e50664e3ba0daaf13516f8ebd1e7e5a4824f16267b8ba3ec6f0c226`，完整命令/结果在verification相邻JSON。A是基础页面原作者，本项**仅C这次delta／增强原test**为非作者审核，不冒9个既有案例都是非作者生产验收；真实技术browser原Arrow/Delete仍待root最终回归。

## 非作者 Requirement 返回／预览与 NodeRun StrictMode 读租约 delta

本次 Requirement 与 NodeRun runtime／测试作者为 B/C，A 不改其源码。C 有效 before 分别是返回／预览三项 **0PASS/3FAIL**、NodeRun StrictMode一项 **0PASS/1FAIL**；缺 summary 导致0GET的首次 NodeRun夹具失败不是产品红。A只读原before日志和源副本，不冒称自己执行该红；组长相关真实浏览器五项的候选绿也不是A运行。

A实际命令（cwd `frontend`）：`./node_modules/.bin/vitest run src/pages/w5/requirements/node-lifecycle.spec.tsx src/pages/w5/requirements/pages.spec.tsx src/pages/w5/requirements/controller.spec.ts --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w7-evidence/A/C-requirement-node-independent.json`。**31/31 PASS、0FAIL、0skip、exit0**，分为 Node生命周期1＋实际Page9＋controller21；没有把C的同批31再加成62唯一案例。JSON SHA `c4ca89621ca710fcff93cd679b0f94c2f1675621fd79828c9cb366ee16f5af36`、log `54a9f3174079328358b06aef135a456595c462d0ef2c05a9ed16f0f3030666c5`；完整运行前后七文件哈希在 `C-requirement-node-independent-before-hashes.json`／`C-requirement-node-independent-verification.json`，前后全部匹配。

| 审查边界 | 源码／实际测试与结果 | 限定 |
| --- | --- | --- |
| StrictMode pending attempt的新读租约 | `nodeController.ts:94`仅在真实 attachReads 的 start清`loading=false`后原list/refresh。`w4/shared/core.ts:19–29`最后detach立即失效原ResourceScope/channel；`nodeController.ts:27–44`每段await后复核原lease、selectedGeneration与原attempt。新增实际根StrictMode测试 `node-lifecycle.spec.tsx:13–33`证明第一GET仍pending时新lease第二GET成功、唯一definition、metadata2/loadingfalse；旧GET随后reject不写error/metadata/loading。 | 没有释放鼠标事件或sleep才取效果，不靠手动owner.refresh解除死锁；此unit未扩成所有浏览器事件／File生命周期。 |
| 没有隐式writer或原identity更换 | 新start只发attempts/attempt/必要definition GET。`complete/process`仍显式UI入口，key/body/CAS及operation closure未改；`core.ts:24–49`原SENDING/UNKNOWN/accepted gate/recover保存。`controller.spec.ts:24–31`accepted仅读、分段原布局body/key、退休晚graph不发layout、checkpoint/start原版本与0重复Start仍实际绿。 | 新Node单项是读恢复正控，不冒测了全部Node commandFile子变体。 |
| 真返回链接与普通guard | `RequirementPage.tsx:49`仅恢复`PageLink to=/requirements`、中央nav.back；`w2/shared/index.tsx:35–45`原modifier分支不preventDefault。实际Page新增第一case验证href、Ctrl click不调用go、普通click仅原路径go、0layout。 | RTL使用实际link／公开navigation mock，不能称本项已独立运行native Chromium Ctrl新标签或Router dirty确认；最终W5/W6实际生产另验。 |
| 导出预览保留父画布及草稿 | `RequirementPage.tsx:56–94`原main scene保持DOM并用hidden＋inert，不再条件卸载整个父Flow/子owners。原`:48`export入口拒locked／proposal／nonALLOW child；`controller.ts:23,35–37`exporting硬BLOCK且仅exportOwner可write。真实Page第二case先ArrowRight造dirty，保存预览后原Canvas.isConnected／hidden，preview Arrow/Delete0变更，返回仍exact sameDOM与原dirty/layout，0layout/save。 | 隐藏区域不等于释放owner；关键status仍在PageChrome主status。File相关子owner未改、入口仍拒它的dirty/BLOCK；本次没有另实际选File，故File不丢的这一结论是保持组件／gate的源码证据，不冒File字节新实跑。 |
| UNKNOWN原模板身份与离开保护 | `SaveTemplate.tsx:18,21,26–33`原readonly preview、owner锁与default dirty确认保留。Page第三case真实POST失联→UNKNOWN，父画布仍connected/hidden、返回disabled、guardBLOCK；显式retry同原body对象（selection.expectedRevision/previewSha）POST2，无另造命令。 | 不把unknown称接受，不自动重放；accepted-read与通用File保护沿原owner。 |

当前冻结SHA：`nodeController.ts` `f60ee7ac92b85e6da4ae782e39b416be39c13640905573455a762160a87f1117`；新增test `e5f615d1e52b9e0b12214bc35a7888ed049805256da64ed95a42f34ac55a50a1`；`RequirementPage.tsx` `2963b73d0d5de3c3171513b6c16f16fb0bb1603b3df260568ca1c75ee851710f`；Page.spec `6528f39b1a8e888a032c4b9b8e4693cc6c14df3f9cfba65d6a70cb185d8325a6`。本delta未发现未修复阻塞；仍不替代最终bundle／W4 29＋W6 97／真实后端，也不把旧红来源或root五项候选混作A实跑。

## 非作者 execution-layout 原能力恢复 delta

C原 `execution-layout-before.json` 是10项枚举中**1实际FAIL＋9过滤未选**，真实RUNNING article ArrowRight后dirty仍false；A只读该红来源，没有自己重跑修前字节。C最小修改仅`controller.ts:202`的非planning布局用原`owner.edit`推进dirty/revision，以及`RequirementPage.tsx:62`非planning＋dirty显示中央`workflow.saveLayout`。中央key和save图标由组长生成；原`components/workflow/planSave.ts`、`api/workflowRuns.ts`和底层Flow无改动。

A独立运行同三spec命令，输出改为 `A/C-execution-layout-independent.{json,log}`：实际 **33/33 PASS、0FAIL/skip、exit0**，准确拆分 **controller22＋Page10＋NodeStrict1**，不是23＋9＋1。JSON SHA `cae45706a363927ba447ba6639d6721cd0f7f722f5b7fa3031abf2cba0a8ed35`，log `b8b2386ed6627ffe0c2e039a8991ab355373d4ab389c870b1d1a01178276537e`。C六文件manifest SHA `5dcd259ab01b2bbfc8af05062230a40f70074b71a2ab27ac729297c97086357e`全项匹配；运行前后八文件（含纯planSave/API）完全一致，详见 `C-execution-layout-independent-before-hashes.json` 与 verification.json。上一31项是上一candidate执行，仍保留原SHA来源，不相加为64唯一测试。

`pages.spec.tsx:26–53`以真实RUNNING Page/article ArrowRight投影24px：dirty=true、canLeave=CONFIRM_DISCARD、点击前0PUT；用户明确“保存布局”仅PUT原revision2/layoutVersion4＋非空key，服务端GET投影v5后dirty=false，revise/applyPlan均0。`controller.spec.ts:28–46`实际UNKNOWN冻结同endpoint `/workflows/requirements/req/layout`、PUT、key/body/revision/layoutVersion，试改x999被拒；显式恢复原body对象仅第二PUT，成功但GET失败保持ACCEPTED_READBACK/dirty/BLOCK，后续v4旧读仍BLOCK，v5合法读才SETTLED，全部后续恢复不再PUT、不重写graph。

仅布局时原 `preparePlanSave:4–8`按**相等graph且无candidate**捕获已有版本凭据以跳过graph writer（这是已知原base版本，不谎称新增graph请求接受）；`controller.ts:118–137`只进入原layout段，accepted读要求原id及不旧于receipt，定时刷新／换肤／选择不调用save。PENDING_START仍走`planning()`原草稿路径，graph相等时同样由该纯函数跳过revise/applyPlan；本次两项新增实际交互/恢复用RUNNING，**没有另实际执行PENDING_START仅布局负控**，不把源码等价检查称此变体已跑。旧NodeStrict及返回／预览／UNKNOWN合同在这次最终字节33项中继续绿。本delta无新增阻塞，实际production仍待组长build GO。

### C 四项 UI delta 的非作者复核与推送事实负控

本次 A 只读检查 C 的七文件冻结清单 `/workspace/react-full-w7-evidence/C/remaining-ui-freeze.json`（SHA-256 `374b071ffff3a3eb236eb64d8a1066254508c356394bd0037bc1968b03996805`）。七个文件均匹配，连同未改的 publication/content/requirement controller 与 core 共十一文件在独立运行前后逐字节相同。C 的原 `remaining-ui-before.json` 是 **4 条有效失败、22 条过滤未执行**；`remaining-ui-after.json` 为作者 26/26。A 另外实际执行：

```sh
./node_modules/.bin/vitest run src/pages/w5/requirements/content-pages.spec.tsx src/pages/w5/requirements/pages.spec.tsx src/pages/w5/workflow/pages.spec.tsx --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w7-evidence/A/C-remaining-ui-independent.json
```

结果 **26/26 PASS、0 FAIL/skip、exit 0**，分别 Content 5、Requirement Page 11、Workflow Page 10。原始日志 `A/C-remaining-ui-independent.log`；JSON SHA-256 `d988ba1707e4e6e05354cd21992c4e09ad920b6b4148354fd3ae40b021c29ca0`；前后源码及命令证据 `A/C-remaining-ui-independent-verification.json` SHA-256 `ced1355416b59b3eedc14cbb6b3b70787365e1ae626a6785b6de7dfd43d157da`。这些与之前 31/33 项大量重叠，不累计为独立场景数量。

| delta | 本次实际证据与只读边界 | 分类 |
| --- | --- | --- |
| 检索证据 StrictMode 初读 | `requirements/Content.tsx:52–57` 的每次 effect 实例拥有 `currentView`，cleanup 先置 false，旧队列微任务不能启动 GET；`content-pages.spec.tsx:41–52` 根 StrictMode 实际初 GET 1 次，显式 next-cursor 仅再 GET 1 次，unmount 后不增加请求。`contentController.ts:60–72` 的异步成功、失败、finally 均核原 ticket，主体未变。 | PASS；本次未另执行新旧 scope 并存的延迟响应负控，迟到保护结论在本 delta 为源码复核。没有新 writer、计时器或 RAF。 |
| 已提交且成功空 push GET 的呈现 | `Publication.tsx:24` 恢复原“尚未推送到远端”；`content-pages.spec.tsx:54–61` 真实 controller 读 COMMITTED 和 null push，明确 0 POST。读取失败通过 `publicationController.ts:24–32` 的 error 告警，不把异常当 null。 | 正控 PASS，但其 UNKNOWN 旁路出现下列真实 RED，不能据 26 全绿宣称事实边界完整。 |
| 历史候选关闭 | `RequirementPage.tsx:53` 常显只读／未生效候选状态；`pages.spec.tsx:54–71` 实际历史 APPLIED 预览 2 节点→关闭→原 base 1 节点，0 applyCandidate/applyPlan/layout。可编辑候选仍走明确 discard 确认。 | PASS。关闭是本地预览披露，无 server cancel。`RequirementPage.tsx:37–46` 确认重新核 draftRevision 和 owner.locked；`controller.ts:35–41,204` 动态单写 gate 阻 child dirty/BLOCK 时 accept(base)，不清子 owner/File。另同批 `pages.spec.tsx:73–126` 实际原画布／草稿及 UNKNOWN 模板原 body/BLOCK 继续绿；不是新的“候选关闭＋File”实际交互负控。 |
| Undo/Redo 常驻禁用 | `workflow/WorkflowEditorPage.tsx:65` 仅零记录时投影 disabled，未改历史数学／底层 Flow；`workflow/pages.spec.tsx:96–107` 初始两按钮存在且禁用，实际 ArrowRight 后 undo 可用，undo 后回禁用、redo 可用，0 revise/layout。 | PASS。原组件基础由 A 编写，此处非作者结论仅 C 新增 UI delta 与其关联断言。 |

**P2，REPRODUCED_FAIL：旧空 push 读取不能继续作为 UNKNOWN 后的当前否定事实。** A 使用真实 `PublicationPanel` 与 `createPublicationController`，先 GET 已提交和 null push，再按真实按钮开启推送、选择 `origin`、GET preview、点击确认推送。唯一 POST reject 后 command=UNKNOWN、canLeave=BLOCK，页面却仍出现“尚未推送到远端”。无改 disabled、无手写 snapshot/owner.patch，无真实后端调用。`publicationController.ts:85–88` 的写命令更新 `command`，未清历史 `pushLoaded`；新增 `Publication.tsx:24` 检查的是 `s.error` 而非 command 的不确定阶段，故此时 `pushLoaded=true/push=null/s.error=''` 与 `command.error='推送回执未知'` 同时存在。

独立临时 probe 实际 **1 FAIL、0 PASS、exit 1**，首错是最后“UNKNOWN 时该否定文字应消失”的 DOM 断言；前面的 POST 1 次与 BLOCK 断言通过。原始 `A/C-publication-empty-read-before.{json,log}`、准确请求／snapshot 的 `A/C-publication-empty-read-observation.json` 保留；probe 原文 `A/C-publication-empty-read-independent.probe.spec.tsx.txt` SHA-256 `f06fad648d784f8358ea535f236e79e91e93398bcfa7e34136081f453938d47d`，临时工程文件已删除以免进入共享全量。已通知 C 与组长由作者最小限制这条新增否定文案的阶段；A 未改 C runtime 或其测试。这个发现不表示 UNKNOWN 写保护失效，实际保护仍 BLOCK，问题是写后未知时错误表达“没有推送”的事实。修复及原样负控回放尚待作者冻结，不提前写为通过。W4 29/W6 97 仍等待最终生产构建 GO。

### 末次 C 冻结：推送事实负控闭环

C 获组长授权后仅修改 `Publication.tsx` 的否定文案阶段、`publicationController.ts` 的空推送草稿登记／原提交恢复后的读取，以及 `RequirementPage.tsx` 的 dirty 提示位置。六文件清单 `/workspace/react-full-w7-evidence/C/remaining-final-freeze.json` SHA-256 `bb94071f840ec5af6c51b21c09967cff27c42471a91369be46f03806a7111ff5`，A 实际运行前后全部匹配：

| 文件 | SHA-256 |
| --- | --- |
| requirements/Publication.tsx | `7d3d21a99598fbf3b3b9af5e3c374069f6ca1d49a17e01ec42e3f83b040b175a` |
| requirements/publicationController.ts | `ce507f494a240806239dbcb76dd32d8dcd6e2ce2ac1260e34eb0b6aa545ede94` |
| requirements/RequirementPage.tsx | `719848fa7f7ff8e10b49cec332902c475890e814a1658f43782afad5c1ea0c4f` |
| requirements/children.spec.ts | `42996b57a088a27f220ef99d1238e4db59f1d8d23c07a8f47c846a8f0c085efc` |
| requirements/content-pages.spec.tsx | `2855f02b0360d0b600170ceae5193a4f8246ebebae7d668a60bb8e42ece601b4` |
| requirements/pages.spec.tsx | `3f5d0eae6dfb724dc42a817821c1c9ef4184cb298893f934fa78516c02203405` |

命令在 frontend 目录实际执行：

```sh
./node_modules/.bin/vitest run src/pages/w5/requirements/children.spec.ts src/pages/w5/requirements/content-pages.spec.tsx src/pages/w5/requirements/pages.spec.tsx src/pages/w7-tests/A-publication-empty-read-independent.probe.spec.tsx --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w7-evidence/A/C-remaining-final-independent.json
```

结果 **48/48 PASS、0 FAIL/skip、exit 0**：C 既有 children 30、content-pages 6、Requirement Page 11 共 **47**，加 A 原 probe **1**。probe 从外部原文复制，运行前后 SHA 均为 `f06fad648d784f8358ea535f236e79e91e93398bcfa7e34136081f453938d47d`，没有改原断言；执行后临时工程文件再次删除。原真实 1 RED 与本次 1 GREEN 分开保存，不把前面 26 或其他重叠批次累加为新增数量。原始 `A/C-remaining-final-independent.{json,log}`；JSON SHA-256 `4b3c68bc2a02b1f89cb34a2bc7ce7f0d03d21bc7d773c1da5d9a76ddf1aaa7aa`，日志 `0838185c6ce14f379cd644f31a6a0e1819576db9d4a968e5cd5e534cc464ff6a`；逐源码前后与命令证明 `A/C-remaining-final-independent-verification.json` SHA-256 `688020d240100349875c178754c90fbd01b579a07cacbd170453edc87aaa4d5e`。

`Publication.tsx:24` 现在仅在已成功核对空 push、没有读取错误、command 不 busy 且阶段 **IDLE/SETTLED** 时呈现“尚未推送”；UNKNOWN／发送中／accepted-readback 不把旧 null 冒充当前事实。原样 probe 再次实际获得 UNKNOWN、pushLoaded=true、push=null、s.error空、command.error存在、POST 1、GET 1、leave BLOCK，但该否定文字已不存在，**上述 P2 已闭环**。修后 snapshot `A/C-publication-empty-read-after-observation.json` SHA-256 `196a9f30b13bfb70b1b3a0f49a5364de021e7a15192cd4fb6a119ba6cef1f046`。原 probe 固定观察文件名，重放后先保存 after，再从前一轮工具原文恢复 before 字节，完整 SHA 与先前记录 `6c4c0182e0659f9e1b98f63eb428470cf4b32dc90aba51ea16344e51f6f47645` 一致；操作来源单独记录在 `A/C-publication-observation-provenance.json`，没有以 after 覆盖前红来源。

`publicationController.ts:71–74` 的 showPush 先调用原 `owner.edit({pushOpen:true})`，普通空草稿亦有 dirty/revision；只有通过原单写 gate 才 GET remotes。`content-pages.spec.tsx:63–88` 的真实 UI 流程已独复：开启空表单→canLeave=CONFIRM_DISCARD→点击真实关闭→确认框选择 Stay→同 owner pushOpen 保留／close callback 0／POST 0；随后显式选 origin、GET preview、确认推送，唯一 POST 成为 UNKNOWN，BLOCK 且关闭禁用。空表单阶段尚未 mint 业务 body/key，并不声称已有在途请求；实际 body/key 仅由后续明确推送捕获，原样 probe 验证对应单 POST 与保护。close/Stay 没有 server cancel、没有清原操作。

`publicationController.ts:98–102` 的 keyless 原提交恢复只在同 scope 的权威 commit GET 核对 COMMITTED 后再 GET push；`children.spec.ts:43` 已独复：第一次 retry POST 失联，模糊原状态仍 UNKNOWN 且 POST 1；读取合法 version5/COMMITTED 后 SETTLED，随后 push GET 恰好 1 次/null，push confirm 0。没有因增加推送呈现而自动 POST。`RequirementPage.tsx:51` 仅把 dirty 放入持续状态／版本行，原 owner 和 Flow 未改；同批原画布、历史候选、UNKNOWN 模板与 stale discard 断言继续绿。本最小 delta 未发现未修阻塞；这是单元／只读复核，最终生产浏览器与 W4 29/W6 97 仍等组长唯一 freeze/build GO。

### FINAL clean-install：旧六 spec 的桌面 12 项

组长完成同一冻结源码的正式 clean npm ci／既有六入口补丁复现后，A 只使用其新建的测试专用 Vite 41773，未匹配 API 仍 501，不连接真实后端。本阶段源码冻结为 **817 文件**、`a8821a377da1577b8b3a94de2c1f1f17e056cb66c4a1f91509c96b27c7efd8b8`。A 在浏览器执行前后均实际运行 `node scripts/w7-acceptance-evidence.mjs verify /workspace/react-full-w7-evidence/final/source-freeze.json`，两次成功输出完全相同。最初普通沙箱核验出现 `spawnSync git EPERM`，未启动浏览器；同命令以正常工具权限执行成功，未改源码／工具配置。该 preflight 环境阻塞不算浏览器失败或重试。

在 frontend 目录实际运行：

```sh
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium W7_BROWSER_JSON=/workspace/react-full-w7-evidence/final/legacy/A.json W7_BROWSER_OUTPUT=/workspace/react-full-w7-evidence/final/legacy/A-output ./node_modules/.bin/playwright test e2e/connection-management.spec.ts e2e/database-driver-upgrade.spec.ts e2e/database-progress.spec.ts e2e/publication-validation.spec.ts e2e/task-baseline-error.spec.ts e2e/linkage-regressions.spec.ts --config e2e/w7/playwright.legacy.config.ts
```

**12/12 PASS、0 skip/flaky/retry、exit 0，实际 12 个单次执行，136.204 秒（list 显示 2.3m）**。结果中的文件＋标题多重集与冻结 desktop baseline 精确一致，原八项 pure-narrow 仍保留在源码／manifest 且不计本批。原始 `final/legacy/A.json` SHA-256 `001c856e828ed7c14378865fbe826e17dd7aa90ebfb9dd67629ae265ac0b04c0`、`A.log` `4146bd55a166633060b46b639cd7d51849dc9e5287f84c6f357f8dc98e45da4b`；输出 `final/legacy/A-output/`，逐标题／唯一尝试／原八项排除及前后冻结记录 `final/legacy/A-verification.json`。此次没有修改运行源码、断言、选择器或夹具，未把前面第三候选 12 绿沿用为这次结果。

原连接管理 1440/1024、openGauss 和 GaussDB/Oracle/SQL Server/DB2 独立密码、数据库真实 12/14 与 85%／2/4 投影、A→B 后退旧取消确认失效／Inbox 错误保留、多行提交说明、启动基线失败的原十二合同均实际通过。JDBC/模型/提交为严格 mock 传输，本结果不宣称真实数据库或真实模型已运行。此批是最终 clean-install 开发服务上的旧 E2E，不冒充 production bundle 路由图；另有明确 GO 后，A 正在独立 41776 跑实际 production W6 97 项。W4 29 仍等待全部旧 260 项结束并停 dev 后的独立 GO。

### FINAL production：W6 31 路由／三皮肤 97 项

组长最终 production build 完成后单独放行 W6。A 使用 `e2e/w6/playwright.config.ts` 创建独立 41776 production preview（`reuseExistingServer=false`），未复用旧开发服务、未另改服务／运行源码。浏览器前后 utility 均确认同一 **817 文件／a8821a377da1577b8b3a94de2c1f1f17e056cb66c4a1f91509c96b27c7efd8b8** 冻结。实际命令：

```sh
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium CANVAS_W6_BROWSER_JSON=/workspace/react-full-w7-evidence/final/w6/browser.json CANVAS_W6_EVIDENCE_DIR=/workspace/react-full-w7-evidence/final/w6/evidence ./node_modules/.bin/playwright test --config e2e/w6/playwright.config.ts --workers=1 --retries=0 --output=/workspace/react-full-w7-evidence/final/w6/output
```

**97/97 PASS、0 skip/flaky/retry、exit 0，97 个单次执行，540.852 秒（9.0m）**。最终报告题名多重集与冻结 baseline 的本文件 97 条 desktop 注册精确一致，没有增删／跳过／换标题；全部三皮肤×31 路由记录和另外四项均实际执行。原始 `final/w6/browser.json` SHA-256 `65a96a9ba287133a834b419cb96447b776a346bfffd2c66efb83315e9090bdfa`，日志 `13cb80d4133cf3f0abe87d650abcae9ac628897ddfa22db943921c6e66455a7a`；逐题名、单次执行、前后核验和产物全文哈希 `final/w6/A-verification.json` SHA-256 `42a1222f93eabb131a33d6e7c6a03b070d928b5a1c4a6bad20675540acfce44d`。

前面的 31 路由矩阵在本轮均已有真实生产**导航／读取／页面作用域退出**证据：直达和 reload 核单一真实 React route owner／main／h1，实际 Sidebar 退出后原生 Back／Forward／Back 回原路由；`/automations?archive=A#legacy`、`/settings/roles?role=A#prompt` 的别名完整保 query/hash，fallback 明确去 `/`。这不意味着表中每个高级写动作都由 W6 路由矩阵执行；其余动作需按各旧 E2E／实际消费者用例分别计，不拿 97 条只读路由通过替代全部业务动作。

93 条 route/skin 用例的资源断言顺序未降低：`immediateW3Exit` 的实际 DOM link click 启动生产导航，MutationObserver 在原 page root 首次 disconnected 的微任务内直接 snapshot；`assertW2Disposed` 随即核页实例 listeners／ResizeObserver／RAF／capture／timer 全空，然后才执行 Back／Forward。没有等待定时器自然到期或补 pointerup/mouseup/blur 帮助清理。精确公开 App provider MQL callback 在页面之间仍同身份 live；它属于唯一 App，最终 root 释放另由组长 root6 验证。这里只声称该透明 ledger 的所有权集合，不声称所有 DOM 监听、GC 或堆对象均已清空。

另四项实际绿：无 session 的 Designer 跳真实四字段 New 且 0 POST；同文档原生 POP 的 dirty Stay 保原 marker/字段，Discard 才退出且 0 write；同文档 UNKNOWN POP 不退休原 owner、title/marker/key/body 保留，只有用户显式原 POST 第二次才接受并交接；未知 MQL 在 lazy page 挂载前注册也不被当作 App provider，首次退出 strict gate **确实 throw**，仅在负控移除自己注册的 callback 后 gate 才绿。最后一项是验证未知资源不能被豁免的负控，不伪称其首次 snapshot 已全空。

最终新产物 `final/w6/evidence/` 共 **36 PNG（12 类主体×3 皮肤，全部 PNG header 核 1440×1000）＋93 route JSON**，名称、长度、尺寸、SHA-256 全入 `A-verification.json`，供最终 gallery／他人像素复核；未使用旧 W6 截图。测试专用 mock HTTP/SSE 与零未知 API/写入检查是本批边界，不调用真实后端／付费模型；没有把这些默认主体截图称为全部高级操作的选中态视觉证明。本轮 W6 与最终 legacy12 已闭环，A 尚待 W4 29 的独立 41773 GO。


### 第二冻结前非作者复核：Role 定位与 Node/PPT 恢复

本节是第一冻结全量单元失败后的**关联单元审查**，不是新的最终浏览器结果。A 沿用原启动记录 gpt-6.1-sol/xhigh，未新增成员；当前工具不能实时读取该平台配置。A 未编辑 B/C/组长运行源码、原测试或断言，未安装、构建或启动浏览器／服务。第一冻结的 legacy12 与 W6 97 留作该字节的诊断证据，不能拼为后续新冻结通过；W4 29 尚未执行。

组长 Role 变更只把旧 `get('details')` 的歧义选择器改为 `details[aria-label="MCP 工具清单"]` 与 `details[aria-label="权限规则"]`（`RoleManagementView.spec.ts:116–117`）；原“读取文件”与 `.env` 两条内容断言保留，原 10 个 fullName 和 62 个 literal expect 均不变。A 实际独立命令：

```sh
./node_modules/.bin/vitest run src/views/RoleManagementView.spec.ts src/pages/w2/core/pages.spec.tsx src/migration/w5Routes.spec.ts --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w7-evidence/A/Root-role-independent.json
```

结果 **50/50 PASS、0 FAIL/skip、exit 0**：旧 Role 10、W2 core pages 23、W5 routes 17；这里实际跑的是 `core/pages.spec.tsx` 中的 Settings 合同，不冒称另跑了旧 `SettingsView` 文件。四个运行相关 source/test SHA 前后相同，Role spec `0076f04049b7c535afc357a6b7e7c899789d80f334ad6eaee2729834e7d689f3`。原始 JSON SHA `8309ee78d1fe0f969807fc227df79dc0746d736ce51272421a4022f3e513a1fd`，日志 SHA `9d082774b8e98858423f452740fe39dfc8da06d3290a45bbc6e2e822f9fb325b`；逐文件／标题／计数证明 `A/Root-role-independent-verification.json` SHA `1495cd309890b3cb532cb020e4ceecb218ca301d967c73e5f026f7fc2e28ea7a`。分类为**旧测试定位歧义修正**，未改变生产功能或放宽业务谓词。

C 最终 `C/node-ready-freeze.json` SHA `8155575d1e9d4787771167b080d146174451a4ec36a0e66085e01e54473a0467`，七文件冻结；B `B/B-freeze-source-hashes-v3.json` SHA `8e6a072c972deb9a5298891700ab5a4ef4ee42806e344fdeab47caac21e3131c`，二十文件冻结。A 在一次独立单 worker 调用中原样复跑六文件：

```sh
./node_modules/.bin/vitest run src/views/WorkflowRequirementView.spec.ts src/pages/w5/requirements/controller.spec.ts src/pages/w5/requirements/children.spec.ts src/pages/w5/requirements/node-lifecycle.spec.tsx src/pages/w3/ppt/PptStudioPage.spec.tsx src/views/PptStudioRecovery.spec.tsx --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w7-evidence/A/C-node-B-ppt-independent.json
```

结果 **94/94 PASS、0 FAIL/skip、exit 0**，拆分为 **C 76**（旧 Requirement View 23、controller 22、children 30、Node Strict 1）与 **B 18**（PPT Page 15、原恢复 3）。两 manifest 与四个关联文件共 **31 个 source/test SHA** 在运行前／后完全一致；B manifest 中未列入本命令的模块只有哈希核对，不冒称 A 独立运行了 B 全 138。原始 `A/C-node-B-ppt-independent.json` SHA `8d6c15c218a60231060c642ca827e84d0f983416bbc2c1cd73ce3bd6ca5a9b98`，日志 SHA `895913055e8e4044213696325d660d10467dc4bc5717cc6ce9da46a133f4cbf0`；前后／全名证明 `A/C-node-B-ppt-independent-verification.json` SHA `3003a38b2bca01d6110da2903a058992628c7bbdddf1c32f29e50f6d5e602a8f`。

| 非作者审查项 | 精确源码／测试证据与分类 | 本次结论 |
| --- | --- | --- |
| Node 两个就绪前置 | `WorkflowRequirementView.spec.ts:34–43` 读取真实 Node owner 的 selected=run、metadata.id=run、readable、loading=false 和实际 DOM 非 disabled；`:140,145` 分别在人工输入与停止命令之前等待这些条件。不改 disabled、不触发隐藏 emit、不 patch snapshot。 | PASS。**夹具交互早于真实读取就绪的修正**，未改 production/runtime。 |
| 原重试 busy/class 与拒导航 | `WorkflowRequirementView.spec.ts:145` 仍同时核 retry aria-busy=false 与无 ant-btn-loading，实际 flush React 后才点原重试；`:98` 只用短 act 启动真实 `application.current.navigation.go`，再实际 Stay 并 await 原 Promise，原 pathname／两节点草稿断言保留。 | PASS。没有把整段用户确认 Promise 包进 act，未 stub guard、改 timeout 或删除原谓词。 |
| 原 23 fullName 保留 | A 实际 JSON 的旧 View 23 个 fullName 多重集与第一冻结 `final/unit.json`、C 最终作者 JSON 精确一致，missing/added=0；静态 21 个 `it` 声明之外还有参数化展开，不能把声明数误称实际条数。原 literal expect 111→116，增量仅 Ready helper 的五条前置。 | PASS。完整 23 条原业务测试均执行，0 skip/drop。 |
| PPT 已恢复 pending 的查看入口 | `PptStudioPage.tsx:73` 只在进入手动查看时放行本地 presentation toggle，不执行 protect/write；退出仍要求非 busy／pending／propertyDirty 并调用 protect。`:43,88` 的 studioEditable 与 Canvas disabled 原 gate 保留，`Properties.tsx:13,29` 属性 fieldset 原 gate 未改。Page SHA `3e30323306bc51168d7c629dc77e2301c87f079a3763f4fdcacb3655ba1acc24`；spec SHA `3e84f81d2eec05c11d1f11c81d400a6bf21fab67a37c4dd71552998da0b08927`。 | PASS。B 原恢复三项 2 PASS/1 实际 FAIL 后的**真实只读入口回归修复**；没有改变写 owner／幂等协议。 |
| PPT 真实原身份与草稿保护 | `PptStudioPage.spec.tsx:43–73` 实际进入恢复查看／选对象，文字原 draft 可见但禁改、结束／save／remove 禁用；1200ms operations/job=0，原 stream/root 未重建，原 session draft/pending 相同。仅点击显式原重试后唯一 POST=`doc,3,operations,original-key`，真实读回才 ALLOW、可编辑；结束不增写或重挂。 | PASS。原 `PptStudioRecovery.spec.tsx` 三项与 B 归档 before 原文逐字节相同，原普通 dirty Stay/discard、存储不可写和原 key/body/revision 恢复断言均实际绿。 |

本范围没有新增未修阻塞；C 的两个 readiness 前置、短 act 与 Root Role 定位变化均是测试夹具／定位修正，不能计作生产 bug 修复。B 的恢复查看入口有真实原 2 PASS/1 FAIL 证据与当前 18 项独立回放。A 本轮没有新增行为场景或修改写入协议；仍需组长对新唯一冻结执行 clean-install／全量单元／production build，再明确放行下一阶段旧 12、W4 29、W6 97，不能把本单元绿称为全 W7 已通过。


### 第二冻结 release：31 路由／动作与实际截图审查准备

本节为**方案／只读映射，尚未执行第二冻结浏览器**。正式清单 `/workspace/react-full-w7-evidence/release/source-freeze.json` 的文件 SHA `b20e36a9b2bb42938b90bd4270b64d656cafa809d2e4cd55a2467434f75cdb38`，其 sourceSha256=`d1a38dd1471e5c5d0d23aeafa845c7494d3bc30e2d5875dd37c24e9faded596d`、817 文件；清单文件 hash 与源码聚合 hash 是不同值。组长已 verify exit0，正在统一 clean ci／全量单元／build，A 不并行启动 browser/server 或重跑构建。下一 A 顺序仍为明确 GO 后的 old12→W6production97；W4 29 按组长独立端口／阶段调度。新的 report、trace、JSON、图和前后核验全部用 `release/`，不覆盖 `final/` 第一冻结证据。

前面 31 行正式 route/action 表继续逐条保留。不把生产路由只读矩阵的 pass 等同于动作全覆盖：

| 正式记录 #（与前表对应） | 当前第二冻结状态／后续确切证据入口 | 动作覆盖的限界 |
| --- | --- | --- |
| 01–17、19–28、30（28 页面记录） | 全部 `NOT_RUN_RELEASE_WAIT_GO`；W6 对实际列表中的每条 fixture 深链跑三皮肤 direct/reload/原生 Back/Forward／首次退出快照，生成同 kind JSON。 | 原表高级动作仍按其既有 E2E／各波 consumer 合同查证；不以没有 POST 的路由默认态覆盖创建／保存／恢复／上传／权限批准。Knowledge 本矩阵有 id，不冒无 id 创建场景。 |
| 18 `/automations`、29 `/settings/roles` | `NOT_RUN_RELEASE_WAIT_GO`；W6 分别严格核 archive/role query 和 #legacy/#prompt 原 hash。 | alias 成功不关闭 health 归档消费者／Role 版本和权限动作门槛；历史实际 UI case 另由原 spec 所属人最终执行。 |
| 31 fallback | `NOT_RUN_RELEASE_WAIT_GO`；W6 访问准确 `/absent-route?discard=A#missing`，只期望 `/`。 | 这是非法 URL fallback，不据此说合法 query/hash 被保留；与两 alias 分开。 |
| New dirty／UNKNOWN native POP、missing-session、未知 MQL 四额外项 | `NOT_RUN_RELEASE_WAIT_GO`；使用已有 W6 四 case 原题名与断言。 | 不增新场景、假 beforeunload 或 disabled DOM 输入；UNKNOWN 原身份／显式原 POST、未知 MQL 首 gate throw 保持硬合同。 |
| Task／Inbox／Recovery／TaskDesign 四 W4 入口及 17 额外项 | 全 29 为 `NOT_RUN_RELEASE_WAIT_GO`；native SSE、原 command、草稿、Last-Event-ID=17、publication 与活动 pan 有各原 case 明确断言。 | 原权威 REST／GET-only recovery／原问题 body 与 versions 按真实动作 case核，不能用 W6 默认读屏截图补写入证明。 |

现有 W6 spec 在 `production-routes.spec.ts:19–20` 仅为 12 种代表主体截屏（三皮肤共预期 36 PNG），没有给全部 31 record 各生成图片；余下 19 record 的 route JSON/DOM/资源／无未知请求证据与图片来源分开列。不能因此宣称“31 页面均有截图”。截图选择行为是既有测试的一部分，不新增浏览器场景或改原 spec。

| 预期 release W6 图 kind（三皮肤分别一张） | A 生产作者关系／计划目视内容 | 证据用途与边界 |
| --- | --- | --- |
| `home` | 非 A；低密度主体、项目／会话标题、真实入口和本地搜索范围。 | 不允许虚构 global metrics/API；默认图不证明实际筛选交互。 |
| `projects`、`settings` | 非 A；主次层级／表单行高、可发现新增/保存/分区入口、不横向挤压。 | 默认图不证明凭据写、Settings unknown PUT 或普通 dirty confirm；既有动作测试另计。 |
| `templates` | 非 A catalog；模板类别／选择入口、读历史可发现性。 | 默认图不能替代 File hash/create、Document正文下载或 health failed→CHECKED 消费者。 |
| `knowledge` | 非 A；纯 React 富文档／问答／来源／活动，可读正文与右 context，不把 Todo 冒完成。 | 当前 fixture为有 id；无 id welcome/create、上传和未知 original send 按原 Knowledge E2E。 |
| `ppt` | 非 A；自由对象真实图形／selected context入口、viewport、revision/关键状态不隐藏。 | 默认 fixture 未构造恢复 pending，不拿这图证明新恢复查看修复；其有效原恢复 3 和新 Page15单元已有本节上方独复，最终 PPT浏览器由对应所有者运行。 |
| `designer` | 非 A；完整结构化设计画布、专业矩阵、状态与 toolbar、40px高度修复及主题对比。 | 默认图不证明 COMPLETED handoff或FINAL_REVIEW reopen CAS；使用既有独立 controller／实际旧 DesignerE2E来源。 |
| `requirement` | 非 A B/C；宽画布／执行状态常显、dirty/error/banner空间、返回入口。 | 默认图不代替 layout-only save／候选退出／File或子 owner BLOCK测试。 |
| `inbox`、`task-design` | 非 A；permission/question入口、原失败提示与冻结设计／角色／版本呈现，长文本/哈希自然换行。 | 默认图与 Inbox明确 answer/permission/UNKNOWN、history附件按需读取的实际原 case 分开。 |
| `workflow`、`task` | A曾是基础实现作者；只做文件/尺寸/来源核验和测试运行，生产像素独立结论交其他成员。 | 此六图不由 A 自审冒非作者产品视觉门禁；本轮 C最小delta另有限定审查，不扩大成整页独立验收。 |

非 A 十类主体共预期 **30 张**可独立目视；A 基础两类共 **6 张**保持可供别人复核。不把预期数写为已产生或已看过。实际 release产物生成后，A 先核 report exit／题名多重集／attempt与retry计数、source前后verify和 bundle来源，再读每 PNG header、长度、SHA与实际尺寸；只打开新 release 目录的图，不把之前 `final/w6` 或 W2–W6老图混入。既有 screenshot顺序先 document.fonts/images decode、scrollTop0、reducedMotion／动画禁用，普通视口1440×1000，无新窄屏／fullPage弹层拼接或额外截屏生成。随后逐三皮肤查看上述30图：文字与背景对比、按钮主次／语义一致、可读中文／无 raw错误码、正文与 canvas不黑填充、工具栏不覆盖状态、主画面简洁且未删高级操作发现入口。图片只证明实际可见状态，焦点／权限／dirty／unknown／资源释放仍以实际 DOM与动作断言为证。

### 历史11的最终核账约束（第二冻结待跑）

前表 11 ID 与 W0 `historical-failures.json` 全量一致：DB两条旧字段、Role六条旧精确文案共 **8 条 STALE_EXPECTATION**，Document两条 `/private/tmp`尾部截图异常为 **2 条 FIXTURE_ENV**，Automations一条为 **1 条 STALE_ROUTE_EXPECTATION + COMPATIBILITY_CONSUMER_GAP**。W0原before0/11→after10/11保持原字节与失败，不以本轮页面／文档修正重写旧账。

- 桌面六条：HF-DB-1440的第二冻结实际结果由 A old12对应原 case承担；HF-DOC-1440、HF-AUTOMATIONS及HF-ROLE三个1440皮肤各由相应最终旧 E2E report核 exact title，不由A路由截图或RootRole10单元代替。A只读别人 report时标明作者/执行人，绝不冒A亲跑。
- 窄屏五条：DB390、DOC390及三Role390均继续 `OUT_OF_SCOPE_NARROW`；原 after5PASS只历史保留，不新跑、不改原标题或抛掉混合桌面断言，不计第二冻结绿。
- React本轮新发现分别保独立来源：DB按行/版本结果缓存真实产品RED；PPT恢复只读入口真实2PASS/1FAIL；NodeReady/短act与RoleDetails是测试夹具／定位修正；SQLServer中间full-reload为DEV_RELOAD_OBSERVED。这些不混进W0的8/2/1根因重计。
- HF-AUTOMATIONS原UI恢复的最终闭合仍必须核显式readArchive→真实health GET原失败次数/原因→服务端新CHECKED清错，并保九GET/两导出、无退役写；独立alias redirect绿色、纯health2或“页面出现模板”都不是替代。

目前上述第二冻结六个历史desktop消费者最终状态、30张非 A目视图、A old12／W6production97／W429结果均**待实际运行**；原失败trace/source/report、副本与第一冻结绿原样保留，不删除、不串接成为final。


### 第二冻结 release：A old12 最终实跑

组长在第二冻结 clean-install／全量单元／production build 完成后仅放行 A old12；本次使用其仍在运行的测试专用开发服务41773，A未启动服务器，未复用第一冻结结果。执行前与后分别实际运行 `node scripts/w7-acceptance-evidence.mjs verify /workspace/react-full-w7-evidence/release/source-freeze.json`，两次exit0、817文件/sourceSha256=`d1a38dd1471e5c5d0d23aeafa845c7494d3bc30e2d5875dd37c24e9faded596d`完全相同。实际命令（cwd frontend）：

```sh
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium W7_BROWSER_JSON=/workspace/react-full-w7-evidence/release/legacy/A.json W7_BROWSER_OUTPUT=/workspace/react-full-w7-evidence/release/legacy/A-results ./node_modules/.bin/playwright test e2e/connection-management.spec.ts e2e/database-driver-upgrade.spec.ts e2e/database-progress.spec.ts e2e/publication-validation.spec.ts e2e/task-baseline-error.spec.ts e2e/linkage-regressions.spec.ts --config e2e/w7/playwright.legacy.config.ts --workers=1 --retries=0
```

**12/12 PASS、0 FAIL/skip/flaky/retry、exit0，12个单次执行，32.735秒**。报告file＋title多重集精确等于原桌面12，未增删、换名或重试；六spec八个pureNarrow原样保留并按固定范围排除，不计通过。原始 `release/legacy/A.json` SHA `5c9dca96f631e354bd2a36f810d981e51b866c3b801576e8c1fc3ee49d99351c`，日志 `A.log` SHA `fd5ed1c09ca86e55563431df1422708da0cb0e785a92223014604d21354bb250`；`A-source-before.log`／`A-source-after.log`相同SHA `bf9d451f0cb95f6ebc1218af8e6e7fcc8aa1ad826fdaf906e92d9dbdd48d8fd0`。完整命令／原标题／执行次数／8条排除／freeze前后证明 `release/legacy/A-verification.json` SHA `78d95be8128ef17ea5f050e94c91af8f1ef7b6493f3a8ee9870f2c3d693df519`，产物独占 `release/legacy/A-results/`，没有覆盖首错或第一冻结`final/`。

连接管理1440/1024、openGauss及四driver独立密码与JDBC body、one成功/two失败后回one仍显示本行结果、原test/save次数与CAS、Task12/14/85%/Session2/4、原多行commit正文、原基线失败告警、A旧取消确认退休后迟到click零POST及Inbox错误持续可见均由本次原12实际执行。数据库/HTTP/模型/提交使用原严格mock；这不是实际数据库连接或真实后端运行的证明，也不冒称该开发服务证据是production bundle截图。

历史11映射中的 **HF-DB-1440** 现有第二冻结实际对应原case PASS（`database-progress.spec.ts`），W0旧host首错与本轮React行缓存真实产品红仍保各来源；HF-DB-390继续OUT_OF_SCOPE。HF-DOC、HF-AUTOMATIONS与三Role1440仍需相应所有者最终report，不能用本12或A Role单元代替。第二冻结W6production97与W429未启动，A已释放本批浏览器执行，按组长要求等待单独followup，不自动续跑或占用服务器。


### 第二冻结 release：W6 production97 与非作者实际像素复核

组长在 A old12结束后单独放行 production97。A只启动一次原 Playwright config的独立41776 production preview（reuseExistingServer=false），未复用41773 dev、未新增capture或测试场景；进程结束后原自动preview亦退出。实际命令（cwd frontend）：

```sh
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium CANVAS_W6_BROWSER_JSON=/workspace/react-full-w7-evidence/release/w6/browser.json CANVAS_W6_EVIDENCE_DIR=/workspace/react-full-w7-evidence/release/w6/evidence ./node_modules/.bin/playwright test --config e2e/w6/playwright.config.ts --workers=1 --retries=0 --output=/workspace/react-full-w7-evidence/release/w6/results
```

**97/97 PASS、0 FAIL/skip/flaky/retry、exit0，97单次attempt，191.321秒。** 实际file＋title多重集精确等于冻结baseline97，没有改原标题、drop、重试或新增；runner errors空。前后源码utility均exit0、817／`d1a38dd1471e5c5d0d23aeafa845c7494d3bc30e2d5875dd37c24e9faded596d`完全相同。A读取实际production dist所有127文件，relative path＋完整SHA聚合前后均为 `454f1f79446aa3766bee47efc62919f034e95ae2e1945d1f78df9af7368d0303`，index SHA `cd06511ff076416776ce08204fb59e5cdf14980c6d03a818c2bc41eb17fb8af0`、组长build.log SHA `69a312c8348fe53fe626a726bcaa06c65eb40f0edb23c1e81bd6800acb583c36`；完整文件manifest见 `release/w6/A-bundle-before.json`／`A-bundle-after.json`，两个对象精确相同。不以源码verify冒充bundle核对，不沿用第一冻结图或绿数。

`release/w6/browser.json` SHA `5fe3ce05970dc583aaa176efeba40001697ed5f4ed9311f56566e6662c89a33b`，日志 SHA `daf866ce28beac811a63aa06d3c1780be06905d5045a9ef059a6b3fa5b6f7e1b`；完整命令／97title／attempt／source/bundle前后／全部图片和记录bytes与SHA证明 `release/w6/A-verification.json` SHA `3b16b6aa5d991cc2cd7993635fa5feb6eb3f881f19dddfdb78828d319579b3ae`。output为`release/w6/results/`；没有覆盖`final/w6`第一冻结原始报告。

前面31route/actions表的**route读取／导航／scope资源退出**第二冻结栏现在全部由实际93条（三皮肤×31record）闭环：exactURL、唯一真实Reactpage/main、reload、真实Sidebar退出、原生Back/Forward/Back；两个alias query/hash和fallback目标分别严格核。首次DOMroot移除微任务 snapshot的page-owned listener/RO/RAF/capture/timer硬0在任何后续Back/Forward之前，不等自然mouseup/blur、timer到期或下一次导航协助释放；App provider精确MQL callback在页面之间按同身份存活，最终App root清零是组长另行root6证据，本批不冒A执行。只声称透明ledger定义的资源集合，不推广为全部DOMlistener／堆／GC。

四额外case也实际通过：missing Designer session进入真实New四字段0POST；同文档dirtyPOP真实Stay保owner/字段、Discard才离开0write；UNKNOWN POP保原marker/body/key并BLOCK，只有显式同POST第二次接受交接；未知lazy-page前MQL负控首次strict gate确实throw，测试仅移除自身callback之后gate才绿。最后一项不可描述成其首次未知资源已经空。高级创建、批准、上传、保存、证据按需读取等动作仍需对应原consumer/E2E判定，不能以93条无POST默认路线读屏替代。

新 `release/w6/evidence/` 实际 **36PNG＋93routeJSON**：PNG名称精确12kind×3skin，header均1440×1000；JSON名称精确31kind×3skin且每条path/expected与冻结entries一致，desktop/actualRuntime/readOnlyTransport/errors核真值。其余19个record没有独立PNG，不能宣称全31页截图覆盖。所有产物bytes/SHA入A-verification。

A已通过view_image实际打开以下非A十种主体的三皮肤各一张，**30张独立生产像素复核**：home、projects、settings、templates、requirement、designer、knowledge、ppt、inbox、task-design。逐文件和SHA／查看方法见 `release/w6/A-pixel-review.json` SHA `9bc5d3ef0fb21c24d3f6263405d6f4965165c7f1aa1d08ad775f4c7231e49fd4`。不是只凭PNG存在/hash称目视；A基础workflow/task六图只做artifact完整性核，独立生产像素结论留其他成员。

实际图中三skin保持独立tokens：spdb深蓝、tech-blue暗底/浅字、github-white绿色主要操作；无正文黑填充、节点/线样式丢失、toolbar覆盖主状态或横向不可读。Home无虚构统计，Projects单卡低密度；Knowledge安全文档/嵌套Mermaid正常、输入独立，PPT白色slide是内容本色且非暗皮肤样式丢失；Requirement两个专业节点与线完整、toolbar/状态可见，未选中context干净；Inbox两原项可发现，TaskDesign六类冻结证据入口完整。Settings分区按钮仍原生灰底、Requirement两刷新入口同短视觉名作为非阻断样式/信息层级观察记录，未为此解冻源码或虚称功能缺失。

**视觉结论限定默认fixture状态。** Designer本批实际图是讨论/待确认＋补充消息输入，并未显示LoopSpec画布或选中矩阵，所以不能用此图验证40px高度／矩阵bbox/reopenCAS；PPT未构造恢复pending，Requirement也未构造UNKNOWN/File子draft，不能用默认图替代本报告真实原恢复/guard测试与对应旧生产E2E。截图不是焦点、权限、dirty/unknown、真实网络或资源全部释放的证明；没有增加窄屏或新的selected/modal场景。

本A第二冻结已实际完成old12＋W697＝**109个既定桌面case**，重复的首冻结/候选跑法不相加。无本批首错，原失败证据保留；W429仍未启动且需组长下一单独GO。W7全521的最终总体结论、其余高级consumer和跨模块全图归组长汇总，不因A109绿提前宣布全站最终通过。


### 第二冻结 release：W4 production29 最终实跑与即时资源核账

组长确认全部前序41773浏览器已退出后独立放行最后W4批次。A只启动一次原 `e2e/w4/playwright.config.ts` 的production静态/native-SSE服务，reuseExistingServer=false，未复用开发服务、未启动额外capture；会话71118与自动服务现均已结束。实际命令（cwd frontend）：

```sh
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium CANVAS_W4_EVIDENCE_DIR=/workspace/react-full-w7-evidence/release/w4/evidence PLAYWRIGHT_JSON_OUTPUT_NAME=/workspace/react-full-w7-evidence/release/w4/browser.json ./node_modules/.bin/playwright test --config e2e/w4/playwright.config.ts --workers=1 --retries=0 --reporter=list,json --output=/workspace/react-full-w7-evidence/release/w4/results
```

**29/29 PASS、0 FAIL/skip/flaky/retry、exit0，29单次attempt，55.922秒。** report file＋title多重集严格等于冻结baseline原29，missing/added均空；没有改断言、重跑或拼绿。`release/w4/browser.json` SHA `f79791238a7cdbbbb57dda495a3bd84a0cc0ff6de9677d9b28f62dc516510fd9`，log SHA `d326c40193f638939a70f0e6f46687d5942d859a3087aac3e93f032f8322a1fd`；完整病例／raw资源／stream／图片／hash证明 `release/w4/A-verification.json` SHA `537bfffb4983c2ff82c8d42419c96a228b647961a2ea95fad3909ece2b330190`。

前后utility均exit0、817／d1a38dd…相同，source日志SHA均 `bf9d451f0cb95f6ebc1218af8e6e7fcc8aa1ad826fdaf906e92d9dbdd48d8fd0`。127个production文件逐路径完整hash前后相同，bundle聚合 `454f1f79446aa3766bee47efc62919f034e95ae2e1945d1f78df9af7368d0303`、index `cd06511ff076416776ce08204fb59e5cdf14980c6d03a818c2bc41eb17fb8af0`；两个A-bundle对象SHA均 `1d6ba19446ab3f99147430fbef5c20f33b9dbc69d47548dfcf52206c608a3d72`。最初离线核验比较用了两种对象schema触发assert，逐文件内容无差异；纠正为相同schema核验成功后才启动浏览器，该验证脚本错误不是产品/source变动或首个浏览器失败。

本次raw证据实际解析出**44个首次资源快照**：四route×三skin×三退出＝36，公开root卸载4、native Task SSE退出2、活动stage pan退出2。这是从本次JSON逐条计数，不继承旧批次数字。所有44的root确实已移除且同一document，sentinel身份保持；page-owned listeners/observers/pendingFrames/captures/timers首样均精确空。活动两样before capture=1，after=0。所有样本before listener范围1–10、observer0–4、RAF0、capture0–1、timer0–3。首样事件增量无pointermove/up/cancel、mousemove/up或真正window blur；元素失焦单独保留，不能当自然释放。route先真实link DOM click，再首次移除microtask采样；root先公开dispose同步返回采样；活动mouse.up在strict断言之后。native SSE之后等待450ms只验证无迟到GET，不用于即时清理。

**不声称全部timer或App资源均清零。** 44样本中41个首样raw allTimers仍含公开识别的外部Ant模块定时器，完整source/stack/identity原样入证明；其不属于退休page，未知armed timer仍保守进入strict门槛而不能豁免。唯一App的exact provider MQL callback在page间同身份保持，不把它冒作page应清资源。全App root6/18由组长另行实际执行且通过；A未运行该批。账本不是全部DOM监听、WeakRef/堆、GC或所有设备的证明。

29的实际动作覆盖保留原强度：Task pending/UNKNOWN原stop只有一次POST，恢复只权威GET；Inbox原version回答/权限明确操作与unknown禁丢；PENDING_START只有显式start而READY不再start；Recovery确定child后仅读、keyless失联不能用相似lineage解锁；Session原回答draft/换肤/native beforeunload刷新与Back拒绝后同输入/owner、不重发原answer；publication三个皮肤真实ordinary dirty→Stay/Discard、焦点、reducedMotion及换肤保持。两种native Task EventSource退出均实证服务端第二连接Last-Event-ID=17、所有本Task连接closed，原Story stream不被误关；REST模拟仍为RUNNING，不能让SSE终态字段直接冒生命周期。

实际新图**27PNG**，全部1440×1000，稳定性记录最后两个完整buffer SHA相同且等于PNG；三skin各9张：Task默认/选中/进度，History默认/选中，Recovery默认，Inbox默认/选中，Publication选中。所有bytes/SHA保在A-verification。A曾是Task作者，故此批只记录实际测试执行和artifact核验，不自审整页像素冒独立验收；三skinTask/Publication新图路径已交C做非作者视觉审查。没有再启动浏览器生成其他截图。

### 第二冻结 release：只读非作者补核W5高级12图

A从**新 `release/w5`**读取C实际production49报告及C-audit，不运行或追加这49，不能加到A138。C report为49/49、0skip/flaky/retry、49单次attempt、exit0，117.224秒；SHA `aafd0d1dc3a19edd65024d1f45170bbdf0d4b81e926d538c9f271dbcc0e29427`，C-audit SHA `3415bbd90f63ed7da7e9f1aef756d64dc3d3caeb726dcff4bef3b4bdbad89d3d`。A重算所有C-audit所列report/log/source/bundleSHA均一致，817当前source与冻结逐文件一致，bundle前后127完整fileSHA与实际dist一致。独立离线证据 `release/w5/A-advanced-independent-review.json` SHA `6db00a00c82f56080ec89ca573c9288f0d89f5aeea0f0a66195a7eb4afd514eb`。

A通过view_image实际目视12张：三skin各 `requirement-plan-selected`、`workflow-edit-selected`（6），三skin `requirement-new-unknown`（3），`spdb-workflow-partial-accepted`、`tech-blue-workflow-partial-rejected`（2），`tech-blue-designer-unknown-file`（1）。逐PNG均1440×1000、完整bytes/SHA核真值、稳定性最后两buffer完全一致，不把文件存在代替目视。选中态保宽主画布与300px以上右context，专业字段及更多设置可发现；UNKNOWN四字段不假装可编辑，critical正文/原操作入口三skin均可见。**A原先编写WorkflowEditor与底层Flow，这五张workflow选中/partial只审非本人测试/呈现delta，不能冒整页owner/画布的非作者质量结论。** Requirement/Designer页owner不属于A，独立像素与保护映射按该限定成立。

原行为映射直接查当前真实测试及raw：

| 状态与当前测试行 | 本次可证业务行为 | 边界 |
| --- | --- | --- |
| 选中 `e2e/w5/production-routes.spec.ts:37` | 真React article点击、complementary可见且bbox在viewport内、宽≥300；三轮退出无write。 | 默认/选中图不证明全部专业模块执行，Flow本体是A作者。 |
| New UNKNOWN `:139` | 三skin各POST2且两个endpoint/body完全相同，原key/templateRevision/title/objective冻结；真实beforeunload reload/Back各dismiss一次、同document、sidebar/rootBLOCK、换肤不write，只有用户显式retry第二POST。 | 不虚构by-request GET或跨刷新owner重建；四字段disabled由实际DOM断言，PNG不是可编辑证明。 |
| 图/布局accepted-read `:198` | graph1/layout2，两layout PUT原key/body/expectedRevision3/layoutVersion4相同；布局已接受后GET503只显示原结果核对入口，后续read不重写。 | 两阶段owner是A原作者，此处只读核非本人生产测试/可见反馈与raw，不自审业务owner。 |
| 图accepted/layout明确409 `:218` | graph始终1/layout3，三次原key/body/CAS完全相同；首次与第二次409维持PARTIAL_REJECTION/BLOCK，换肤/导航不隐式写，用户显式第三次后权威GET保存。 | 不把明确拒绝误称UNKNOWN或无条件完成。 |
| Designer File UNKNOWN `:243` | 两multipart metadata/submissionId/CAS完全相同，original.txt完整bytes=`6f726967696e616c0062797465730ae4b8ade69687`含NUL/换行/中文；恢复后later draft与later.txt 11bytes仍在。 | 图中显示后来消息，但附件行在当前viewport下方；File保证据raw multipart与实际DOM断言，不能仅凭上半截图宣称。 |

A逐条原raw JSON重新核**64首样**：root5＋ordinary-route45＋native-SSE2＋active12；每条page-owned listener/RO/RAF/capture/timer均精确空，同document/sentinel/App MQL身份一致；活动before capture均1，首样前无自然move/up/cancel/windowblur。raw外部Ant timer原样保留、未将未知timer过滤。只核现有明确page ledger，不将C的64绿扩张为全App/堆/GC。

**非阻断视觉例外**：两partial图顶端首行左头约两字被折叠shell toggle遮住，组长/B与A实际图均确认；下面完整“已接受/明确拒绝”阻断正文、版本和恢复按钮仍可见，因此保留例外但不解冻源码。此前Settings灰分区按钮源码 `SettingsPage.tsx:67` 是允许的default样式、不是凭像素认定disabled/失效；Requirement `RequirementPage.tsx:49` 两个“刷新”是同semantic action不同target（执行状态GET与需求计划GET/dirty确认），可访问名不同，不能叫重复业务或未复现功能bug。需要美化只能作为后续视觉评估，不在本冻结新增修复。此轮12高级图未发现新增阻断。

### 最终归档范围与历史11消费者闭环

A亲跑最终唯一**138＝old12＋W6prod97＋W4prod29**，全部0retry/单attempt/exit0，三批前后同817/d1a38dd…；历史第一冻结／候选／独复不叠加为新增case。A真实目视W6默认30与W5高级12分开计来源/作者关系；W4新27图只核artifact并交C独立像素。文档更新不改变817 frozen runtime/test/tooling字节。

A只读最终四份legacy报告核回历史11：HF-DB-1440对应A12，HF-DOC-1440及HF-AUTOMATIONS对应B49，三skinHF-ROLE-1440对应组长88，六desktop各原title实际PASS/单attempt/0retry；五390px继续OUT_OF_SCOPE，不计最终绿。Automations注册函数已提取到 `read-consistency-contracts.ts:35`，原spec只import/register，report记录定义helper文件但原title与显式readArchive→实际health FAILED连续2→CHECKED清旧error断言保留，不能将alias redirect当消费者闭环。9GET/双export的广泛archive合同属于W3另批，不用这一单UIcase冒全面覆盖。原W0 before0/11→after10/11、8stale＋2fixture＋1route/consumer缺口分类/失败trace继续原样保留，不把文档修正称业务修复。精确11映射及四report SHA见 `release/legacy/A-historical-11-final-consumer-review.json` SHA `6019b8296bf15356c27ebb9bef4b32987532669f72ac3492956c7c5fb138acb5`。

A读取组长最终 `release/browser-accounting.json` SHA `0f2cfedf5de1631440115752795c03fbb08af723bc57a1037d42c6ef09330da9`：ok=true、521registered／518unique／521attempts、retry0、missing/excess/failed均空；三重复注册保实账，不冒521唯一。W1正确开发模式41/18通过与wrong-production33/8首错、组长root6/18、2384全unit/type/build/tooling/account/zeroVue均是组长运行/证据，不列为A亲跑。最终全站发布／提交归组长，A仅交回自己的文档和原始证据；没有生产源码编辑、额外场景、部署或外发。
