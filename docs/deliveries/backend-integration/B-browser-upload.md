# B：真实 Spring 浏览器与附件联调

本层基线 `11ca25a3bb764a2d80ac924350139a7087639121`。沿用原 B，历史明确启动配置为 `gpt-6.1-sol / xhigh`；未把自述当作可实时查询的平台配置。只写 `frontend/e2e/backend-integration/**` 与本文，不修改生产 UI、后端、共享资源夹具、依赖或发布。

## 状态与隔离

当前 **5 个浏览器用例全部 NOT_RUN；实际环境入口 ENV_BLOCKED**。组长提供的阻塞为官方 parent POM 的 Java DNS 获取失败，临时 Maven 参数/代理授权尚待处理；浏览器没有启动，Spring runtime/health 没有由 B 请求。组长持有后端编译、生产构建、端点及服务启动。最终专用 strict TypeScript 与 Playwright 收集均已实际 exit 0（5 definitions/1 spec），不能当作 Spring 或 Fake runtime 已可用、隔离 proof 门禁已执行、接口已通过。初次无 alias 的独立 tsc invocation exit 2 是检查器配置错误；专用 tsconfig 复用工程 alias 后通过，没有改生产类型或断言。

准备阶段只在组长明确的 `/workspace/backend-integration-20261004/run/projects/B` 建立空 B 目录及两个合成 DOCX 文件。实际服务将使用组长新建的 `/workspace/backend-integration-20261004/phase1-run-1`，正式 B 根、data 路径均以该批 isolation manifest 为准，绝不沿用旧 run 数据。浏览器不直接读取或写入服务数据目录。每个用例只在 B 子目录建立合成项目与 `docs` 目录，通过正式 API 建立数据库记录，绝不手写 SQLite。不得开始任务、发送模型消息、生成/应用 AGENTS.md、运行命令、提交/推送或触发外部集成。

专用配置没有 `webServer`，不会自行启动服务；不使用 `page.route`、`route.fulfill`、API mock 或假 DTO。正式 React 页通过组长提供的本地端点访问实际 Spring API。原 W7 mock 套件与本层分开。

`beforeAll` 在第一项 fixture mkdir / API write 前核对父 isolation manifest：`ready=true`、完整预期 revision、Spring origin、fake mode、`fake/model`、scheduling/startup recovery 均 false、canonical `runRoot/data/projects`、独立 B 子目录、未 stopped 与 ownedJavaPid；对最新 `runtimeHashes` 校验非零 `classFileCount`、`classesSha256` 与完整非空 jar 清单，实算各现存 jar SHA 与编译 class tree 的精确 count/JSON摘要。然后才经真实 preview proxy GET `/api/runtime/opencode` 要求 `AVAILABLE/fake/managed=false/fake/model`，GET health 要求 UP。任一不符直接失败，不写数据。该门禁实现已经 type/collect，通过并不表示当前未启动的运行时已通过门禁。REST 并未公开 SQLite 路径，不能把 manifest 核对写成 endpoint 证明 dataDir。

C 非作者静态复核发现 producer 从 `applicationClassSha256` 改为 `runtimeHashes` 而 B consumer 曾仍读取旧字段；已按完整新结构同步，没有删除哈希门槛或放宽 ready/runtime 检查。随后发现 producer 的 `ensure_ascii=False` 与 consumer ASCII escaping 不一致，consumer 已精确改为 UTF-8 compact `JSON.stringify(entries)`；这是测试基础设施协议缺陷，不是生产 UI 缺陷。真实 proof 尚未产生，不能把静态闭合当作实际启动验证。

专属 `preview.config.ts` 只使用官方 `preview.proxy` 将 `/api` 和 `/actuator` 转发至显式 loopback `BACKEND_INTEGRATION_SPRING_URL`。host/port 与 Spring URL 都没有隐式默认，生产 dist 仍由组长统一构建、启动；不修改共享 Vite 配置。

## 少量高价值用例

| 用例 | 真实入口与断言 | 当前结果 |
|---|---|---|
| 项目登记、dirty 与 CAS | `/projects` 在真实 React 表单登记隔离根路径；尝试离开后 Stay 保留草稿；合法第二个 HTTP writer 更新同项目 `document-path`；原 UI stale version 收到真实 409，保留 `docs` 草稿、错误可见，服务仍保持第二个 writer 的路径。项目 rename 无 CAS，不假造版本字段。 | NOT_RUN |
| 四字段创建与图保存 | `/requirements/new?projectId=…&template=…` 使用真实 API 建立的 HUMAN-only 模板；四字段加原 requestKey 精确核对；dirty Stay；显式保存 graph 与 layout、各一次原 PUT；GET/reload 保留同需求；不 Confirm/Start。 | NOT_RUN |
| DOCX 实际解析与身份 | 同需求公共资料面板有序上传两份合成 DOCX；实际 Spring 返回 `ready:true`，核文件顺序、大小、SHA256、解析段落与原下载字节；同 metadata/key/files 的实际 multipart replay 返回同 upload；原 key 改顺序 409，记录仍唯一；换肤不产生写。 | NOT_RUN |
| 可见格式/解析错误与显式纠正 | TXT 的 UI 格式拒绝无 POST，另显式 API multipart 请求证明真实格式拒绝 400；损坏 DOCX 通过选择后由真实 parser 返回 400，原文件名与中文错误持续可见、列表不伪造接受；用户显式选择合法 DOCX 再提交成功。 | NOT_RUN |
| SSE 与 polling 退出 | 使用正式 POST 创建 `IDLE` Knowledge 会话（仅冻结隔离 `documents` 来源，不发送消息），真实 `/events` 已 open；原 React 根退出首快照 EventSource close=1/readyState=CLOSED，冻结共享资源 ledger 仍严格空；Requirement 实际 2.5s REST timeout 单独验证。 | NOT_RUN |

用例均 `workers=1`、`retries=0`、桌面 `1440×1000`。项目 CAS、已保存需求、DOCX 解析与 parser 错误四组代表图，各覆盖 `spdb / tech-blue / github-white`；图内容来自真实 Spring 返回记录。截图只能证明可见呈现，文件身份依赖原始请求、响应与实际下载哈希。

最终原文件源码入口（以 `rg '^test\('` 核对）为 `project-requirement-upload.spec.ts:13 / :45 / :79 / :115 / :143`，不把 Playwright 收集输出的转换后行号当源码锚点。当前没有产生任何这些代表图。项目页无内联 SkinControl，因此测试拟在第二个真实 Settings 页操作皮肤，让实际同源 storage subscriber 更新原 dirty 项目页；其余页使用已有内联 SkinControl。不销毁原项目 owner，不用直接伪造 theme marker，且第二页明确断言零 write。

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

夹具自检已实际执行 Node strip-types 生成与 deterministic byte 比较、Python 标准库 `zipfile.testzip` 与 XML parser 对两份文件的五个 parts 核验，全部 exit 0。仅证明 ZIP CRC 与 XML well-formed；**真实 POI parser 尚未运行**。哈希记录位于准备批 `evidence/B-readiness/fixtures-check.json`。

## 执行接口

必须显式提供本批环境与父 proof；没有默认服务器：

```sh
BACKEND_INTEGRATION_BASE_URL=http://127.0.0.1:<lead-port>
BACKEND_INTEGRATION_SPRING_URL=http://127.0.0.1:<lead-spring-port>
BACKEND_INTEGRATION_ALLOWED_ROOT=/workspace/backend-integration-20261004/phase1-run-1/projects
BACKEND_INTEGRATION_PROJECT_ROOT=/workspace/backend-integration-20261004/phase1-run-1/projects/B
BACKEND_INTEGRATION_ISOLATION_PROOF=/workspace/backend-integration-20261004/phase1-run-1/isolation.json
BACKEND_INTEGRATION_EXPECTED_REVISION=11ca25a3bb764a2d80ac924350139a7087639121
BACKEND_INTEGRATION_EVIDENCE_DIR=<lead-approved-absolute-evidence-dir>
node node_modules/@playwright/test/cli.js test --config e2e/backend-integration/playwright.config.ts
```

组长 preview 命令：显式设置 `BACKEND_INTEGRATION_SPRING_URL` 与 `BACKEND_INTEGRATION_PREVIEW_PORT` 后，执行 `node node_modules/vite/bin/vite.js preview --config e2e/backend-integration/preview.config.ts`；浏览器本人未执行该启动命令。

独立静态检查：`node node_modules/typescript/bin/tsc --noEmit -p e2e/backend-integration/tsconfig.json`。收集用 `--list`，不访问后端；收集所用 placeholder origin 不可冒称可用服务。全局旧 suite 需由组长排除本专用目录，真实服务测试只按本配置显式运行。

最终准备证据：`/workspace/backend-integration-20261004/evidence/B-readiness/typecheck-freeze-v2.log` 与 `collect-freeze-v2.log` 均 exit 0；仍是 5 definitions/1 spec。收集使用 `http://127.0.0.1:9` placeholder，没有请求该端点；five bodies/first-write isolation gate 都未执行。`source-freeze.json` 记录本目录 7 文件 SHA，`readiness-results.json` 将静态 PASS 与 live NOT_RUN/环境 ENV_BLOCKED 分开；旧 v1 源与结果分别留在 `source-freeze-v1.json/readiness-results-v1.json`，不重用原 W7 mock 通过数作后端证据。

## 尚未验证

真实 endpoint readiness、全部五个浏览器测试、12 张三皮肤代表图、实际 parser 输出、真实 CAS 错误与恢复、SSE/REST 首快照都尚未执行。没有后台扫描失败、跨进程恢复、磁盘写失败补传、网络结果未知、后端订阅者计数或 GC 结论。运行失败先保首轮 raw/trace/hash 并报告，生产 bug 由组长独立确认后另行授权，不在本目录擅改 UI。
