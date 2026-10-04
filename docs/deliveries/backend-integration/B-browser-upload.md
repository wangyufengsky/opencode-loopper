# B：真实 Spring 浏览器与附件联调

本层基线 `11ca25a3bb764a2d80ac924350139a7087639121`。沿用原 B，历史明确启动配置为 `gpt-6.1-sol / xhigh`；未把自述当作可实时查询的平台配置。只写 `frontend/e2e/backend-integration/**` 与本文，不修改生产 UI、后端、共享资源夹具、依赖或发布。

## 状态与隔离

最新实际批为 `60759aeb19dd909a0303139ca44877c920ef2950 / phase1-run-5`：**原五项一次执行 5 PASS / 0 FAIL，exit 0，0 skip / retry / flaky / runner error**；17.6 秒，七文件与共享资源 helper 前后 SHA 不变。12 张三皮肤图全部实际查看并归档。run2/run3/run4 首错保留，最终五项结果完全来自新 DB 的同一批执行，不拼接历史绿项。

准备批曾为 **5 个浏览器用例全部 NOT_RUN；实际环境入口 ENV_BLOCKED**：官方 parent POM 的 Java DNS 获取失败，临时 Maven 参数/代理授权待处理。该历史证据保留在 `B-readiness/`，不被后续实际运行覆盖。组长持有后端编译、生产构建、端点及服务启动。准备批专用 strict TypeScript 与 Playwright 收集均已实际 exit 0（5 definitions/1 spec），不能当作 Spring 或 Fake runtime 已可用、隔离 proof 门禁已执行、接口已通过。初次无 alias 的独立 tsc invocation exit 2 是检查器配置错误；专用 tsconfig 复用工程 alias 后通过，没有改生产类型或断言。

用户批准本次 Maven 子进程沿用已有无凭据 proxy 后，组长于 `4f06621c11872c3b6ace9f0285ba9bdd669163e5` 提供 `phase1-run-2` ready proof、Spring `http://127.0.0.1:47179` 与生产 preview `http://127.0.0.1:48177`。B 首次实际 Playwright 运行 **exit 1：首项 beforeAll 失败，余四项未运行；五个业务 body 全部 NOT_RUN，B 实际 API 请求/写入均 0**。失败发生在 class tree 摘要门禁，早于 runtime/health GET 与项目 fixture mkdir。完整首错在 `/workspace/backend-integration-20261004/evidence/B-live-first/{browser.json,browser.log,results/}`。

只读复算证实这是 **测试基础设施的 producer/consumer 排序协议缺陷**：3790 文件精确数量与各 jar SHA 已通过；Python `sorted(Path)` 的路径组件排序重现 proof `ed3d4331eea44a8cc6621ea800dc2747209051897ab29fbec2889d14756a133a`，consumer 整 relative string 排序重现 `760effb65ded304949aff21b5abe6964c22abf374015cd1c346033152f95ff16`。首差在 `role-prompts/prompt/v1/…` 与 `role-prompts/prompt-v1/…`，不是 class 文件字节变更。`ordering-diagnostic.json` 保存两种完整 tree 摘要与首差；未放宽摘要门槛、绕过 proof 或盲重试业务写。

组长随后统一 producer 为 relative-path 字符串字典序并完成新冻结 `8553221a226f0996d363ad5777b6c5fcadb6ba31`。`phase1-run-3` 的 Spring `http://127.0.0.1:57173` / production preview `http://127.0.0.1:48178` 首批 B5 在 `/workspace/backend-integration-20261004/evidence/B-live-final-first` **实际 exit 1：2 PASS / 3 FAIL / 0 skip / 0 retry / 0 runner error**。五个业务 body 均已开始，first-write isolation（完整 class/jar 摘要、真实 runtime/health GET）已通过；该批执行期间七个测试基础设施文件 SHA 全部保持不变。`first-results.json` 的 SHA 为 `8ae83fa366543f385b3f4d2ea289d3fff3d98ee74f9c67544b863884300ed988`，原始 `browser.json/browser.log/trace` 不覆盖。

| `phase1-run-3` 首批用例 | 实际进度与分类 |
|---|---|
| 项目登记、dirty Stay、真实 document-path 409 | **PASS**，原 UI 草稿保持、权威 GET 核对，三皮肤无新增写。 |
| 四字段创建、graph/layout 显式保存与 reload | **PASS**，原字段/requestKey、graph/layout 各一次 PUT、同需求读取，无 Start。 |
| DOCX 解析/身份 | **测试选择器 FAIL，partial**：真实 fixture 已建立且 React 详情已挂载，但 helper 错把 H1 固定为“需求流程”；真实 H1 是权威需求标题“隔离联调需求 docx-positive”。尚未到达任何上传 POST、解析或 replay 断言。 |
| TXT/损坏 DOCX/纠正 | **测试选择器 FAIL，partial**：相同旧通用 H1 期望错误，实际显示“隔离联调需求 parse-errors”；格式/解析/上传/纠正均 NOT_RUN。 |
| SSE/poll 首快照 | **测试工具观测 FAIL，partial**：真实 idle 会话与目标 SSE 已打开，退出首快照原 strict `listeners=[]` 失败；8 个残留记录的同 callback 来源均为 Playwright `InjectedScript._setupHitTargetInterceptors`。不删除/过滤它们或称 strict 通过；后续 Requirement poll 断言 NOT_RUN。 |

SSE 的 `knowledge-first-snapshot.json` 来自失败 trace 内原 `page.evaluate` 结果的无改动解码：目标 conversation `opened=true/closeCalls=1/readyState=2`；不同 owner 的 App story-accounting SSE 保持 OPEN；原 Knowledge root 已断开，RO/RAF/capture/timer 均 0。此部分事实不能替代完整 strict gate，亦不能证明 heap/GC。三皮肤共 **6 张真实 Spring 数据代表图**已生成并逐张目视读取（项目 CAS 错误与保存需求）；全部数据为隔离合成记录，包含 A 的隔离 REST 测试项目并不表示 B 写入 A 路径。文件/解析/error 另外 6 张计划图尚未产生。

父任务已授权且 C 非作者静态复核通过的最小 helper 候选已冻结：以 fixture 已 GET 的 `requirement.title` 做更强 H1 `toHaveText`，保原节点断言。资源准备改为真实 `/knowledge/history?project=…` 只读页，通过 readonly `html.locator.evaluate` 初始化工具；该调用只读 DOM，另保留短暂测试自有旧 host 引用以核实实际身份，不写 DOM 或业务。前页断言没有 Knowledge workspace 或目标 conversation stream；按真实历史选择/打开动作 SPA 进入原 IDLE 对话，再用真实 GET 精确核 id/project/title/model/state，断言旧 host 与新 host 不同、旧 host 已 detached、documentIdentity 同一，删除测试自有 host 引用后才 `begin()`。原监听身份、全部 strict `[]`、首次 MutationObserver 快照、scope SSE close 与后续 polling 断言不变；不使用 `page.route`、API mock 或事件过滤。若 host 未分离，断言立即失败，不能把监听排除后宣称绿。

v3 冻结时仅实际 strict tsc / collect，均 exit 0；原五个 literal fullName 与数量对 `8553221a` 逐项完全一致，共享 `e2e/w2/resources.ts` 字节未变。证据 `/workspace/backend-integration-20261004/evidence/B-helper-v3/{typecheck.log,collect.log,source-freeze.json}`，七文件 manifest SHA `809e3a14367fb9486de4f2b94917885d6d296e5df6d7702d72715f07a1b1dffd`。v3 随后由组长提交为 `33406d46`；其实际 run4 结果如下单列，不沿用 run3 通过数。

### 正确 JDK 与 fresh run4 首次结果

组长提供 `phase1-run-4/isolation.json`、Spring `http://127.0.0.1:45685` 与生产 preview `http://127.0.0.1:48179`。B 先真实读取 health/runtime，再原五项单 worker、零 retry 一次执行；beforeAll 门禁通过。执行期间七文件 SHA 全保持不变。证据 `/workspace/backend-integration-20261004/evidence/B-live-jdk-final/{browser.json,browser.log,results/,first-results.json}`；摘要 SHA `106d75fb8177bf48f800f477cbb89627d2727d8d518ef57093b0a313d277e690`。**32.4 秒，exit 1：2 PASS / 3 FAIL；五项 body 均开始。**

| run4 用例 | 已到达断言与完整门槛分类 |
|---|---|
| 项目登记、dirty Stay、真实 409 | **PASS**，权威 GET、原 UI 草稿及三皮肤零额外写均通过。 |
| 四字段创建、graph/layout 保存 | **PASS**，原 requestKey、各一次 PUT、同需求 reload 与三皮肤通过，无 Start。 |
| DOCX 解析、字节与 replay | **测试观测 FAIL，partial**：上传 200/ready、两文件有序 name/size/SHA、真实 POI 第一段、解析清单、两份原下载 SHA 均通过。Chromium multipart `Request.postDataBuffer()` 为 null，旧提取处抛错；metadata/replay/改序 409/唯一记录及该项三皮肤 **NOT_RUN**。 |
| TXT、损坏 DOCX、纠正 | **测试文案期望 FAIL，partial**：TXT UI 零 POST、真实 TXT 400 格式码、坏 DOCX 400 parser 已到达。trace 实际 DTO 为 `DOCUMENT_READ_FAILED` / “不是有效的 Office 容器”；原文件名及同文案实际可见，旧断言猜测“文档无法解析”。零 acceptedUploads、显式合法重选 200/总两 POST、该项三皮肤 **NOT_RUN**。 |
| SSE/poll 首快照 | **测试新 document 工具归属 FAIL，partial**：历史页预热、old-host detached/same-document、原 IDLE 会话权威 GET、scope SSE open/close 一次及 Knowledge 全部 strict 首快照 **PASS**。旧 `page.goto` 随后新建 document，Requirement poll timer 已激活，但退出 listeners 数组含 8 个同 callback 的 Playwright InjectedScript；该 parent 保持 FAIL。 |

`scope-first-snapshots.json` 是失败 trace 中原 evaluate 结果的无改动解码。Knowledge 前后同 documentIdentity，原 root 断开后 listeners/RO/RAF/capture/timer 全 0，目标 stream `closeCalls=1/readyState=2`；不同 owner 的 App stream 保持 OPEN。Requirement documentIdentity 已换，首样仅 8 个工具 callback 非零，其他 RO/RAF/capture/timer 为空；没有过滤、删除或用延迟快照替换。六张项目/已保存需求三皮肤图均由本批实际 Spring 合成记录生成，已逐张目视；该 run4 的 DOCX/parser 错误六张计划图未生成；新 run5 十二张完整组另列。截图不能替代文件身份、服务端订阅者数或 heap/GC 证明。

### v4 最小测试修订与交还

组长授权且 C 非作者静态复核通过的三处修订仅在 B 测试目录，没有改生产 UI、后端、共享 ledger 或原五个 literal test 名称：

- 仅 DOCX 用例使用 **passive fetch wrapper**，测试确实替换该 window 的 fetch 函数身份。先立即原参数调用 native fetch 一次并返回原 Promise，随后异步读原 FormData 的 metadata Blob；只收该合成上传 path/method/raw metadata，精确一条且解析原 key/CAS。不读 headers/凭据/response，不改 body/响应，不等待再发请求，测量异常不污染请求。原 File/hash/order/replay/409 断言全保留。
- 坏 DOCX 精确断言真实 400 DTO 的 code/detail，UI alert 可见并 `toHaveText` 同一 detail；原文件、零接受和用户显式纠正断言保留。
- Poll 半段保持已预热 document，真实 sidebar → 需求列表 → 选择原 API seed 标题 → 原 id 的 PageLink；GET 再核 id/project/title，证明新旧 host 不同、旧 host detached、同 document，删除测试自有前页引用后才 begin。UI 写计数必须 0；原 active timer、全部 strict `[]` 和 MutationObserver 首快照断言不变。

v4 strict tsc / `--list` 已实际 **exit 0 / 0**，原五个 fullName 与 `33406d46` 原文/数量一致。证据 `/workspace/backend-integration-20261004/evidence/B-helper-v4/{typecheck.log,collect.log,source-freeze.json}`；七文件 manifest SHA `55001840064b967bb984a87d7a3b056c3648d1c400687c69d0610728dbbf7b7b`。spec SHA `e24765a24c4f907c8cb915b07ffaf6f755051ea2a4c126bcc8fe820ebc850d25`，support SHA `4d2d95d047073903525bb60daff748ceaedc907b7d0af5244088d9859ade33a2`；共享 `resources.ts` 仍原 `b2f55748d0109cee6e28002a3f135ca51ca686b80d4a21b7c692b8b52a5cd6bd`。收集只用 placeholder `127.0.0.1:9`，不执行 beforeAll/HTTP。源码已交还组长并统一提交 `60759aeb19dd909a0303139ca44877c920ef2950`；当时该候选 live 全部 NOT_RUN；随后组长提供新 DB 的 fresh run5 GO，其一次执行结果单列如下，不复用 run4 DB 或拼接旧绿项。

### fresh run5 原五项最终一次执行

新批只使用 `phase1-run-5/projects/B`，allowedRoot 为同批 `projects`；实际 Spring `http://127.0.0.1:38701`，正式 production preview `http://127.0.0.1:48180`。源码冻结 `60759aeb19dd909a0303139ca44877c920ef2950`，没有改测试或 production。B 先经真实 preview proxy 读 health/runtime 得到 `UP / AVAILABLE / fake / managed=false / fake/model`（双 200），再按原配置单 worker、零 retry 执行原五项一次。完整 first-write isolation（ready/revision/canonical roots/class count/tree 与每 jar SHA/真实 runtime）均通过；没有 API mock、`page.route`、真实模型消息、Start 或外部副作用。

**最终实际 5 PASS / 0 FAIL / 0 skip / 0 flaky / 0 retry / 0 runner error，exit 0，17.6 秒。** 原五个名称、body/key/File/409/strict 谓词均保留。七文件及共享 ledger 前后 hash 相同；source-before/after 可逐文件核对，不能把 run4 的 2 PASS 或其部分事实算入本批。

| 原用例 | fresh run5 完整结果 |
|---|---|
| 项目登记、dirty Stay、CAS | **PASS**：真实登记、原草稿 Stay、第二 HTTP writer 后原 UI 409 与权威 GET、三皮肤零额外写。 |
| 四字段创建与图保存 | **PASS**：原四字段/key，graph/layout 分开各一次 PUT，同 id reload，三皮肤，无 Confirm/Start。 |
| DOCX 解析、字节、replay | **PASS**：两份实际 DOCX 上传 ready、有序原 name/size/SHA、POI 原标记段落、两份实际原下载 SHA；被动原 metadata 捕获精确一次且核原 key/version/revision；同 key/body/files replay 同 upload，反序 409/`WORKFLOW_REQUEST_CONFLICT`，清单唯一、三皮肤零新写。 |
| TXT、坏 DOCX、显式纠正 | **PASS**：TXT UI 零 POST 与真实 API 格式 400；坏 DOCX 实际 400/`DOCUMENT_READ_FAILED`/原 detail“不是有效的 Office 容器”在 UI 可见；原文件名保留、acceptedUploads 为空；用户显式合法重选 ready 200，UI 总两 POST。三皮肤错误呈现完整。 |
| SSE 与 REST 首快照 | **PASS**：真实 IDLE 会话 SSE 确已 open，首 SPA 退出 close 一次/readyState 2；Requirement 原 recursive poll timer 确已激活。两 scope 均以原 MutationObserver 首快照证明 root detached，listeners/observers/pendingFrames/captures/timers **各 0**。原历史/list SPA 前页 host detached、同 documentIdentity，UI 写 0；未删监听、补 cleanup 事件或等待后快照。 |

资源原始证据 `sse-poll-first-exit.json` 中 Knowledge 首样位于 **`knowledgeAfter.resources`**，不是 wrapper 顶层；两种 scope 前后 documentIdentity 均相同。App 的单独媒体监听、全局 stream 与 foreign sentinel 保留未动，不把其它 owner 的正常资源称为页面残留；scope SSE 正式原网络 close 证据独立核对。该账本仍保留测试审计引用，不证明 GC 或后端 subscriber 内部数量。

全部证据位于 `/workspace/backend-integration-20261004/evidence/B-live-business-final/`：

- `browser.json/browser.log/exit-code.txt`：原五项一次 raw，exit 0。
- `final-results.json` SHA `872b5d6513279c74bf8d9abb29b383fd47e576c8a7bc342a4a6931f7bb5460a4`：精确 case/source/strict/artifact 汇总。
- `source-after.json` SHA `5c6f9537eb21dc67e98a9a3fb841fac093fb5f081c206fa84c72d2186302673d`：与 source-before、v4 七文件清单逐项一致。
- `docx-identity.json/parser-error-recovery.json/poll-observation-preparation.json/sse-poll-first-exit.json`：原 metadata、SHA/order/回执、可见拒绝与显式纠正、same-document scope 证据。
- `screenshot-manifest.json` SHA `499dbb859852f57e7c421b5b92c53f7899096c3be6a341760cfd666a546664ab`：12 PNG 的 bytes SHA、尺寸、skin、实际查看记录；四组（项目 CAS、保存需求、解析 DOCX、parser 错误）×三皮肤。

B 已用 `view_image` 实际逐张读取全部 12 图：中文 CAS/parser 错误、保留输入、保存 HUMAN 节点和两份已选文档在三皮肤下均可见；只含隔离合成数据，列表含 A 合成项目不代表 B 写入 A 路径。文档面板下方为有界滚动，图没有覆盖全部正文；File 字节/identity、replay 与下载依赖 raw 断言，不能由截图或底部屏外内容冒证。C 非作者最终 raw/hash/图片复核另记，不以 B 自测替代独审。

准备阶段只在组长明确的 `/workspace/backend-integration-20261004/run/projects/B` 建立空 B 目录及两个合成 DOCX 文件。后续正式 B 根、data 路径均以组长本批新建的 isolation manifest 为准，绝不沿用旧 run 数据。浏览器不直接读取或写入服务数据目录。每个用例只在 B 子目录建立合成项目与 `docs` 目录，通过正式 API 建立数据库记录，绝不手写 SQLite。不得开始任务、发送模型消息、生成/应用 AGENTS.md、运行命令、提交/推送或触发外部集成。

专用配置没有 `webServer`，不会自行启动服务；不使用 `page.route`、`route.fulfill`、API mock 或假 DTO。正式 React 页通过组长提供的本地端点访问实际 Spring API。原 W7 mock 套件与本层分开。

`beforeAll` 在第一项 fixture mkdir / API write 前核对父 isolation manifest：`ready=true`、完整预期 revision、Spring origin、fake mode、`fake/model`、scheduling/startup recovery 均 false、canonical `runRoot/data/projects`、独立 B 子目录、未 stopped 与 ownedJavaPid；对最新 `runtimeHashes` 校验非零 `classFileCount`、`classesSha256` 与完整非空 jar 清单，实算各现存 jar SHA 与编译 class tree 的精确 count/JSON摘要。然后才经真实 preview proxy GET `/api/runtime/opencode` 要求 `AVAILABLE/fake/managed=false/fake/model`，GET health 要求 UP。任一不符直接失败，不写数据。准备批 type/collect 只证明静态检查；run3/run4/run5 的实际门禁通过分别记录，不把静态检查替代本批 live 门禁。REST 并未公开 SQLite 路径，不能把 manifest 核对写成 endpoint 证明 dataDir。

C 非作者静态复核发现 producer 从 `applicationClassSha256` 改为 `runtimeHashes` 而 B consumer 曾仍读取旧字段；已按完整新结构同步，没有删除哈希门槛或放宽 ready/runtime 检查。随后发现 producer 的 `ensure_ascii=False` 与 consumer ASCII escaping 不一致，consumer 已精确改为 UTF-8 compact `JSON.stringify(entries)`；这是测试基础设施协议缺陷，不是生产 UI 缺陷。准备阶段没有真实 proof；之后 run2 排序失败及 run3/run4/run5 实际门禁通过单独记录，不能把静态闭合当作启动验证。

专属 `preview.config.ts` 只使用官方 `preview.proxy` 将 `/api` 和 `/actuator` 转发至显式 loopback `BACKEND_INTEGRATION_SPRING_URL`。host/port 与 Spring URL 都没有隐式默认，生产 dist 仍由组长统一构建、启动；不修改共享 Vite 配置。

## 少量高价值用例（初始准备批计划与当时分类）

| 用例 | 真实入口与断言 | 准备批当时结果 |
|---|---|---|
| 项目登记、dirty 与 CAS | `/projects` 在真实 React 表单登记隔离根路径；尝试离开后 Stay 保留草稿；合法第二个 HTTP writer 更新同项目 `document-path`；原 UI stale version 收到真实 409，保留 `docs` 草稿、错误可见，服务仍保持第二个 writer 的路径。项目 rename 无 CAS，不假造版本字段。 | NOT_RUN |
| 四字段创建与图保存 | `/requirements/new?projectId=…&template=…` 使用真实 API 建立的 HUMAN-only 模板；四字段加原 requestKey 精确核对；dirty Stay；显式保存 graph 与 layout、各一次原 PUT；GET/reload 保留同需求；不 Confirm/Start。 | NOT_RUN |
| DOCX 实际解析与身份 | 同需求公共资料面板有序上传两份合成 DOCX；实际 Spring 返回 `ready:true`，核文件顺序、大小、SHA256、解析段落与原下载字节；同 metadata/key/files 的实际 multipart replay 返回同 upload；原 key 改顺序 409，记录仍唯一；换肤不产生写。 | NOT_RUN |
| 可见格式/解析错误与显式纠正 | TXT 的 UI 格式拒绝无 POST，另显式 API multipart 请求证明真实格式拒绝 400；损坏 DOCX 通过选择后由真实 parser 返回 400，原文件名与中文错误持续可见、列表不伪造接受；用户显式选择合法 DOCX 再提交成功。 | NOT_RUN |
| SSE 与 polling 退出 | 使用正式 POST 创建 `IDLE` Knowledge 会话（仅冻结隔离 `documents` 来源，不发送消息），真实 `/events` 已 open；原 React 根退出首快照 EventSource close=1/readyState=CLOSED，冻结共享资源 ledger 仍严格空；Requirement 实际 2.5s REST timeout 单独验证。 | NOT_RUN |

用例均 `workers=1`、`retries=0`、桌面 `1440×1000`。项目 CAS、已保存需求、DOCX 解析与 parser 错误四组代表图，各覆盖 `spdb / tech-blue / github-white`；图内容来自真实 Spring 返回记录。截图只能证明可见呈现，文件身份依赖原始请求、响应与实际下载哈希。

最终原文件源码入口（以 `rg '^test\('` 核对）为 `project-requirement-upload.spec.ts:13 / :45 / :79 / :120 / :151`，不把 Playwright 收集输出的转换后行号当源码锚点。准备批未产生代表图；run3/run4 各自已有六张项目/需求三皮肤图，run5 独立产生十二张四组/三皮肤图。项目页无内联 SkinControl，因此测试拟在第二个真实 Settings 页操作皮肤，让实际同源 storage subscriber 更新原 dirty 项目页；其余页使用已有内联 SkinControl。不销毁原项目 owner，不用直接伪造 theme marker，且第二页明确断言零 write。

## 实际协议与能力边界

- `ProjectController.java` 的 POST 使用真实 `ProjectRequest`；`ProjectService.create` 检查真实目录与 configured allowedRoot，事务后 `ProjectStackProfileService` 做本地静态文件扫描，不调用模型。文档路径 PUT 使用真实 `version`，服务抛 `PROJECT_VERSION_CONFLICT`。不把无 version 的项目 rename 说成 CAS。
- `WorkflowRequirementController.java` 与 `WorkflowPlans.java` 将创建、计划保存、布局保存、确认、执行分开。模板仅 HUMAN 节点与 DOCUMENT 公共输入；本层不开始节点或控制器，不需要真实模型。
- `WorkflowUploadController.java` 接收 `metadata` JSON part 与有序 `files`；元数据为 `{requestKey,expectedVersion,expectedRevision}`。`WorkflowUploadStore.replay` 对原 key 与有序 filename/SHA 做摘要验证，冲突码通过 `ApiExceptionHandler` 的 **`errorCode`** 返回。
- `DocumentTemplateStorage.prepare` 支持 `.docx/.md/.markdown/.pdf`，**Requirement 上传不支持 `.txt`**。本层不把 txt 拒绝当解析成功；DOCX 正控走真实 POI parser。
- `WorkflowUploads.upload` 正常 POST 返回 `ready:true`，可能在磁盘保存异常前提交 durable metadata；本层没有磁盘故障注入，**不声称已覆盖 partial disk failure/ready:false 补传**。实际完整上传同 key replay 与 parser 明确拒绝后显式更正分别取证，不用虚构 GET-by-request。
- known upload GET、files GET、text GET、file GET 都是现有公开 API；text DTO 只有 `text/nextOffset`，不增加 path/sha/version 响应字段。
- Requirement 没有 SSE；其 owner 使用 2.5s recursive timeout。Knowledge `create` 仅持久化 `IDLE`，`send` 才进入 coordinator；创建需组长已配置的 default model，测试不修改模型设置，不打开 catalog 或发送消息。若环境没有可用 default model，必须报 ENV_BLOCKED 或改用组长确认的合法 idle SSE fixture，不能静默 skip 或伪造会话。
- SSE 观测继承真实 `EventSource`，透明调用原 constructor/close，不拦截网络。通用 ledger 复用已经冻结的 `e2e/w2/resources.ts`，不改计数/署名白名单/原断言。首快照在原根 disconnect 的 MutationObserver 回调取，不补自然 move/up、延迟或其他 cleanup 事件。ledger 自身保留审计引用，不能证明 heap/GC 回收或后端订阅者内部数量。

## 文件夹具

`files.ts` 用 Node Buffer、CRC32 与 stored ZIP 构造确定性的最小 OOXML：`[Content_Types].xml`、`_rels/.rels`、`word/document.xml`、`word/styles.xml`、`word/_rels/document.xml.rels`。仅含合成中文标题/段落，无宏、外链、下载或真实个人资料。TXT 为合成 UTF-8；坏 DOCX 是非 ZIP 合成字节。浏览器直接 `setInputFiles` 接收 Buffer；原上传 bytes、顺序、metadata 与下载 SHA 分别核对。

夹具自检已实际执行 Node strip-types 生成与 deterministic byte 比较、Python 标准库 `zipfile.testzip` 与 XML parser 对两份文件的五个 parts 核验，全部 exit 0。仅证明 ZIP CRC 与 XML well-formed；准备阶段真实 POI parser 尚未运行；run4 已实际核对上传后的解析第一段与两个原文件下载 SHA。哈希记录位于准备批 `evidence/B-readiness/fixtures-check.json`。

## 执行接口

必须显式提供本批环境与父 proof；没有默认服务器：

```sh
BACKEND_INTEGRATION_BASE_URL=http://127.0.0.1:<lead-port>
BACKEND_INTEGRATION_SPRING_URL=http://127.0.0.1:<lead-spring-port>
BACKEND_INTEGRATION_ALLOWED_ROOT=/workspace/backend-integration-20261004/phase1-run-1/projects
BACKEND_INTEGRATION_PROJECT_ROOT=/workspace/backend-integration-20261004/phase1-run-1/projects/B
BACKEND_INTEGRATION_ISOLATION_PROOF=/workspace/backend-integration-20261004/phase1-run-1/isolation.json
BACKEND_INTEGRATION_EXPECTED_REVISION=<lead-frozen-full-40-char-revision>
BACKEND_INTEGRATION_EVIDENCE_DIR=<lead-approved-absolute-evidence-dir>
node node_modules/@playwright/test/cli.js test --config e2e/backend-integration/playwright.config.ts
```

组长 preview 命令：显式设置 `BACKEND_INTEGRATION_SPRING_URL` 与 `BACKEND_INTEGRATION_PREVIEW_PORT` 后，执行 `node node_modules/vite/bin/vite.js preview --config e2e/backend-integration/preview.config.ts`；浏览器本人未执行该启动命令。

独立静态检查：`node node_modules/typescript/bin/tsc --noEmit -p e2e/backend-integration/tsconfig.json`。收集用 `--list`，不访问后端；收集所用 placeholder origin 不可冒称可用服务。全局旧 suite 需由组长排除本专用目录，真实服务测试只按本配置显式运行。

最终准备证据：`/workspace/backend-integration-20261004/evidence/B-readiness/typecheck-freeze-v2.log` 与 `collect-freeze-v2.log` 均 exit 0；仍是 5 definitions/1 spec。收集使用 `http://127.0.0.1:9` placeholder，没有请求该端点；five bodies/first-write isolation gate 都未执行。`source-freeze.json` 记录本目录 7 文件 SHA，`readiness-results.json` 将静态 PASS 与 live NOT_RUN/环境 ENV_BLOCKED 分开；旧 v1 源与结果分别留在 `source-freeze-v1.json/readiness-results-v1.json`，不重用原 W7 mock 通过数作后端证据。

## 尚未验证

原五项已在 fresh run5 同一次执行全部通过；此前 run2/run3/run4 首错与 partial 分类原样保留，不能改写为历史通过。该五项只覆盖本文指定业务与 scope，未覆盖全站全部 API、真实模型、Confirm/Start 或外部集成。没有后台扫描失败、跨进程恢复、磁盘写失败补传、网络结果未知、后端订阅者计数或 GC 结论。运行失败先保首轮 raw/trace/hash 并报告，生产 bug 由组长独立确认后另行授权，不在本目录擅改 UI。
