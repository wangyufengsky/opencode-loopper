# 内网辅助 MCP 合同

本主题持有辅助能力的工具策略、数据库凭据和证据读取边界。生命周期与最终接受仍由 [架构合同](architecture.md)、[验证器合同](seven-feature-contract.md) 持有；候选提交仍由 [OpenCode 合同](opencode-contract.md) 持有。

## 服务与权限

辅助服务位于 `/api/assist-mcp-streamable`，随受管 OpenCode 配置注入，独立于公开六工具和内部候选提交服务。传输只接受 literal loopback、当前代际 bearer；每次工具调用还必须持有服务端签发的 `scope`。该凭证绑定外部 Session、代际、项目、Task／Stage／Attempt 或 Designer 身份，模型不能以请求参数替换这些身份。停止、结束、所有者改变或代际变化会使作用域失效。兼容模式不获得辅助凭证。

受管子进程关闭模型目录下载和自动更新，并使用 npm 严格离线模式。内置本地统计守卫不依赖 npm 包，保持启用；用户已有配置和本地插件保留。需要额外 npm 包的用户插件须由管理员提前准备依赖，缺失时不可通过自动联网安装绕过。内网 Provider 仍使用管理员明确配置的地址与模型；这些开关不禁止已配置的内网模型请求。

`AssistToolCatalog` 是辅助工具的名称、schema 和角色目录：

| 工具 | 用途 |
| --- | --- |
| `list_database_connections` | 冻结授权中的数据库连接与 schema |
| `inspect_database_schema` | 表、列、索引、外键目录，最多 100 行一页 |
| `query_database_readonly` | 单条受控 SELECT／WITH |
| `inspect_document` | 文件身份、格式、解析限制和分段目录 |
| `read_document` | 指定分段正文，支持预期 SHA |
| `generate_word` | 已明确登记的 DOCX 交付物 |
| `get_execution_context` | 当前阶段要求、附件、产物和辅助调用目录 |
| `get_failure_evidence` | 最近或指定已完成 Attempt 的验证事实 |
| `read_task_evidence` | `evidence:`、`verification:`、`call:` 引用正文 |

工具页采用全局默认和项目覆盖，覆盖值为继承、启用、停用；更新携带对应版本并审计。配置以稳定服务 ID 和精确工具名持有，内部服务使用稳定别名。系统候选提交不可关闭。初次完整登记第三方服务时保留现有工具默认启用；同一服务以后发现的新工具默认停用。清单不完整或名称无法精确表达时不扩大授权。实施 Session 显式拒绝服务工具前缀，再逐项允许选中的精确工具；候选角色不获得第三方通配授权。

第三方来源支持“关闭此来源全部工具”：客户端提交完整目录及各工具在当前作用域的版本，服务端在事务外核对来源完整目录，在短事务中逐项 CAS 停用并审计；任一冲突整批回滚。全局操作修改全局默认，不覆盖项目显式启用；项目操作为该项目逐项写入停用覆盖。系统内部和辅助来源不提供此批量入口；新发现工具仍默认停用，既有会话权限不追改。

策略只控制 Loopper 新建 Session，不修改用户 OpenCode 文件，不中断现有调用，不控制其他客户端。既有 Session 与精确恢复使用原权限摘要；新 Attempt 读取当前工具策略。Task 创建时即冻结数据库连接配置、凭据版本与项目绑定，后续连接修改、归档或新绑定不能扩大该 Task 的数据授权。升级前 Task 冻结为空数据库授权，需通过新任务使用新增数据能力。Designer 作用域首次建立时冻结连接集合。

需求、规划、设计可读取数据库和文档；实施增加当前任务证据查询，Word 写入仍需逐阶段授权；评审和双 Judge 仅可读取 Session 开始前已采集的证据，不开放实时数据库或 Word。Judge／Reviewer 的辅助调用结果不进入共享证据目录。Router、纯修复及收尾保持最小工具集合。模板分析只增加明确的辅助只读工具。

服务端在 HTTP 投递前附加最小上下文、凭证、工具与限制，因此自定义 Role Pack 和重试模板不能删除必要约束。辅助 MCP 不可用时在投递模型前返回明确错误；已明确要求 DOCX 的实施阶段若关闭 Word 工具，也在投递前拒绝。工具或配置错误不能自动替代生命周期决策，也不能改用 shell／外部网站绕过授权。

## 数据库管理、驱动与只读执行

页面 `/databases`、REST `/api/database-connections` 支持创建、编辑、项目绑定、测试、启停和归档；不提供网页 SQL 编辑器。密码不回显，所有修改及连接测试要求本地 UI 标识。分页使用时间加 ID 游标；名称／主机搜索、类型和启用／停用／归档筛选在服务端执行。页面采用连接表格与分区侧栏，新增只列已具备内置驱动的类型，自动选择驱动；高级设置提供受控字段，不接收任意 JSON。`POST /api/database-connections/test` 测试草稿，不写入 SQLite 或密码密文，沿用有界执行器；编辑已保存连接时校验版本并可沿用原凭据。输入变更后前端丢弃旧测试结果，测试成功不自动保存。

新建支持 MySQL、openGauss、达梦；服务端 `/types` 持有固定类型、端口与驱动目录。MySQL Connector/J 8.0.33（面向 MySQL 5.7／8.0，其他 8.x 版本仍须现场验收）、openGauss JDBC 7.0.0-RC3-og（默认）及 6.0.3（历史恢复）、DmJdbcDriver18 8.1.3.140 及必要依赖在构建时复制进 JAR 的 `jdbc-bundled` 资源，版本、SHA 与来源见随包 NOTICE。驱动不进入应用依赖 classpath，避免干扰 SQLite。运行时在 `LOOPPER_DATA_DIR/jdbc-bundled/<profile>` 原子提取并校验 SHA，拒绝符号链接、篡改和缺件，不在线下载或自动覆盖损坏文件；按不可变 profile 使用平台父加载器隔离驱动及依赖，最多 64 个加载版本，单文件 64 MiB。

新配置由服务端按类型编译 `driverProfile`、文件与类名，拒绝不匹配的客户端驱动身份；任务冻结整个具体配置，后续版本不得重定义已有 profile。openGauss 使用独立 `OPENGAUSS` 类型；新默认版本驱动类为 `org.opengauss.Driver`，历史 6.0.3 为 `org.postgresql.Driver`，不因此声称支持华为 GaussDB。GaussDB 与 GoldenDB 暂不开放新建；历史记录保留查看、停用、归档和精确恢复。未带 profile 的历史配置继续从管理员原有 `jdbc-drivers` 目录按原文件与类加载，不迁移或改写冻结记录；支持类型在用户明确编辑连接参数时才切换到内置 profile。

连接页面以 JDBC URL、用户名、密码录入。支持 MySQL、openGauss、达梦对应协议；openGauss/MySQL 可用逗号分隔最多 16 个节点，支持 IPv6 方括号。URL 最多 4096 字符，拒绝内嵌凭据、重复参数和越过保护的参数；`targetServerType`、`loadBalanceHosts`、`hostRecheckSeconds` 可用于节点选择。解析后的白名单参数单独传给驱动，URL 中不能覆盖用户、密码、只读、超时或本地文件访问设置。界面保存原始非秘密 URL，服务端派生首节点及默认库；历史 host/port/database 配置保持可读，用户编辑时才转换。openGauss 新默认 profile 为 `opengauss-7.0.0-RC3-og`，使用 `org.opengauss.Driver` 与 `jdbc:opengauss:`。保留旧 `opengauss-6.0.3` profile、原 JAR／SHA 和 `org.postgresql.Driver`；旧配置与冻结任务继续使用 `jdbc:postgresql:`。两种输入前缀均按选定驱动转换，保留全部节点与参数。类型接口每种产品只返回一个当前默认驱动，恢复查找保留全部历史 profile。已有连接通过编辑抽屉测试及保存切换到新默认驱动，界面明确提示；仅启用／停用等元数据更新不升级，未保存的测试不改写连接或凭据，密码留空仍沿用原加密引用。已冻结任务不随连接升级变化。密码继续使用独立加密版本引用。SQLState 28 类认证失败返回安全中文提示，要求核对账号、目标节点和驱动，不断言密码输入错误，不回显驱动原始异常或凭据。

驱动类和协议必须匹配现场版本。Gauss 适配器根据厂商驱动类选择 `gaussdb`、`opengauss` 或 PostgreSQL 协议；GoldenDB 的厂商 MySQL 驱动配置可选择 MySQL 协议。这只是适配路径，不能据此宣称产品完整兼容。Gauss 强制 `allowReadOnly=true`；达梦不允许配置会绕过只读设置的兼容参数。连接参数仅接受白名单中的 TLS、时区、字符集及集群节点选择选项，不能覆盖只读、超时、本地文件访问或多语句保护。

账号应由管理员授予限定对象的 SELECT 权限。执行同时要求连接只读、非自动提交、JSqlParser 5.3 AST 解析以及 schema 范围校验；关闭时回滚和释放连接。只接受保守 SQL 子集，拒绝写入、锁、会话修改、过程、多语句、非白名单函数、注释和无法解析的方言扩展；不以关键词解析替代 AST。WITH 中的每个子查询也经过同一完整语句校验。无法表达的查询应缩小为基础 SELECT，不能降低保护。

默认 10 秒、200 行，可配置至 30 秒、1,000 行。结果最多 1 MiB、256 列，单文本单元格最多 16,384 字符；省略大对象和截断必须明确标记。数字、布尔、NULL 保留类型，列附带数据库类型。每连接最多两项任务，全局八个 worker、十六个排队位置；取消使用独立有界执行器。超时后请求取消，未实际结束的 worker 继续占用并发许可；结果未知不自动重发。连接建立另有五秒余量。连接测试不执行写入，只能证明连通及只读标记，不能证明账号拒写和完整产品兼容。

每次查询结果持久化采集时间、配置版本、耗时和完整性标记；历史快照不是实时数据库状态。普通接口与错误不返回底层异常、密码或敏感连接属性。

## 数据库凭据例外

数据库密码使用 AES-GCM、随机 nonce、凭据版本作为 AAD，密文保存于 `LOOPPER_DATA_DIR/database-secrets`。SQLite 只保存不可变引用和配置版本。主密钥优先读取 `LOOPPER_DATABASE_MASTER_KEY`（Base64 编码的 32 字节）；否则首次使用在 `~/.opencode-loopper/keys/database-master.key` 生成随机密钥。

POSIX 目录权限为 0700、文件为 0600；其他平台使用当前文件所有者的独占 ACL，无法建立保护时拒绝持久化。密文使用临时文件和原子发布；配置保存冲突可能留下未引用的加密版本，但不会损坏旧引用。归档不删除凭据。缺少旧主密钥时拒绝自动生成新密钥覆盖现有密文；应恢复密钥，或由管理员明确配置新的环境密钥后重新提供相应密码。

普通导出不应包含密钥和凭据目录。备份迁移必须分别保护 SQLite、密文目录与原主密钥，缺一无法解密。此例外仅限数据库密码，不允许持久化 Provider 密钥、运行 bearer 或模型作用域凭证。作用域凭证仅注入传输请求；任务正文、活动与交接投影隐藏其值。

## 文档和 Word

复用 Java POI、PDFBox 和 CommonMark。新附件和辅助读取共用解析器；新附件记录 extractor version 2，旧附件仍使用原冻结表示，不重算历史正文。设计附件整体上下文仍有既有 128 KiB 边界，大文档应在授权工作区用辅助协议分段读取。

支持 DOCX 段落／表格原始顺序、XLSX 工作表与坐标／公式原文／缓存显示值、PPTX 页序与文字／表格、文本 PDF 分页、UTF-8 Markdown。单文件 20 MiB、Office 解压 100 MiB／10,000 项、PDF 与 PPTX 1,000 页、XLSX 128 表／100,000 单元格、提取总量 200 万字符。工作区解析使用有界线程池，等待上限 15 秒。分段正文每次最多 12,000 字符，UTF-8 正文小于 64 KiB；目录每页 100 项。扫描、加密、损坏、宏或超限文件返回明确错误，不执行公式、脚本或远程资源。原文件 SHA、表示 SHA、解析版本一起返回，`expectedSha` 拒绝来源变化。

工作区引用使用 `workspace:相对路径`；附件使用 `attachment:ID` 且必须属于当前作用域。文件访问检查 canonical containment、符号链接和敏感路径。第一版 Word 目标必须在阶段 deliverables 中明确登记精确路径；省略 target 时，只能选择该阶段唯一的精确 DOCX 路径，并被允许路径覆盖、不被禁止路径匹配；生成器不猜测自然语言中的输出路径。设计 prompt 必须将默认文档路径解析为明确交付物后再冻结。

Word 支持标题、段落、强调、列表、GFM 表格、代码、链接和受管 PNG／JPEG（最多 32 张、单张 2 MiB、总计 16 MiB、单张 1,600 万像素），使用内置中文字体设置。HTML 会明确提示省略；远程图片拒绝。先保存内容与输入身份和 PREPARED 回执，再原子写入授权输出，重开检查后登记二进制产物。相同幂等键只能重放相同请求，修正使用新键；覆盖只允许本 Task 上次生成且 SHA 未变化的版本。用户修改或中断导致不确定时保留原件，使用同键恢复或新授权路径。

生成回执不是业务通过，DOCX 继续通过阶段的 DOCUMENT_STRUCTURE 和内容断言。无手动导出入口，也不会给所有模板报告生成 Word。

## 执行反馈和验收

辅助调用保存真实开始、成功／失败和结果引用，通过现有任务活动及产物区域展示；不推算百分比。参数、SQL、分段错误在同一会话按 `code`、`problemPath`、`detail`、`action` 修正；权限、配置与未知执行结果要求明确恢复，不盲重试。错误回执不直接改变 Task／Attempt。

Attempt 交接继续保存有界摘要，并提供原 Attempt ID 和查询工具指引；模型通过失败验证引用、差异和采集快照取证。自定义 `nextAttemptPromptTemplate` 不能覆盖服务端作用域和正式验收。数据库、文档、日志内容始终作为数据，不可授权模型扩大任务范围。

离线验收使用 `scripts/database-acceptance.sh` 或 PowerShell 脚本，参数为成品 JAR、数据目录、probe JSON 和新的报告路径；密码只由终端或 `LOOPPER_DATABASE_PROBE_PASSWORD` 提供。运行器不启动 Spring／OpenCode，不创建表，不执行写入。报告包含产品／驱动版本、驱动 SHA、读取／结构／本地拒绝探针结果；实际数据值不进入报告。可提供专用只读 `timeoutSql` 验收超时，缺省明确标记未执行。

命令示例：`bash scripts/database-acceptance.sh <成品.jar> <数据目录> <probe.json> <新报告.json>`；未设置密码环境变量时在终端交互输入。

probe JSON 示例（省略驱动字段即按支持类型使用内置驱动；历史厂商诊断仍可显式提供原文件与类）：

```json
{"connection":{"type":"MYSQL","host":"db.internal","port":3306,"database":"app","username":"reader","schemas":["app"],"parameters":{},"timeoutSeconds":10,"maxRows":200},"readSql":"SELECT 1","timeoutSql":null}
```

支持状态按现场产品、版本、驱动和探针报告逐项登记。驱动加载与模拟 JDBC 测试不等于产品联调；未具备现场实例的产品均保持待联调。

工具页以稳定服务 ID 合并列表，每个服务仅有一份展开目录，名称、说明和策略同列展示。辅助服务未在线时仍能配置新会话默认值并显示真实不可用状态；服务连接状态与清单读取状态分别呈现。全局使用开关，项目使用继承／启用／停用选择。

## 第二批：GitLab 与日志／测试证据

辅助目录共 21 个工具，第二批新增 12 个工具默认关闭，包括首次安装。项目页“GitLab 与证据”维护版本化配置，工具页继续逐项启用；两类配置只影响新授权，不修改已创建任务和会话。

### GitLab

沿用设置页 GitLab 单实例 host/API base、环境变量 `LOOPPER_GITLAB_PRIVATE_TOKEN` 和 3 秒连接／10 秒请求默认值。不提供 Token 页面录入或持久化。项目绑定支持 origin 候选识别、手工仓库路径、离线保存和显式检查；检查成功保存项目 ID、名称和时间。任务创建时冻结实例和已检查的项目绑定；未检查、旧任务无绑定或当前实例变化均返回可修正错误。

新增 `gitlab_project_context`、`gitlab_list_issues`、`gitlab_read_issue`、`gitlab_list_merge_requests`、`gitlab_read_merge_request`、`gitlab_read_merge_request_diff`、`gitlab_list_pipelines`、`gitlab_list_pipeline_jobs`、`gitlab_read_job_log`。Issue/MR 的 `section=discussions` 使用独立分页。差异先读 `/diffs`，404/405 时确认父 MR 存在后读兼容 `/changes`；保留上游 overflow 等字段。辅助查询不影响正式发布判断。

只接受冻结项目下的只读请求，不接受任意 URL、主机或项目 ID；禁止重定向，客户端复用连接，最多四个并发请求，包含正文传输的超时与取消。默认每页 20、最大 50，不自动遍历；单次响应最多 1 MiB。结构化响应超限拒绝解析，Job 日志保存有限前缀并标记截断，不宣称拥有完整或尾部日志。

响应在受管目录保存脱敏快照，返回来源、采集时间、SHA、`snapshotReference` 和至多 12,000 字符正文。后续通过该引用和 `nextOffset` 读取同一快照，不能重新下载正在变化的源作为旧快照后半段。保留 `nextPage`；HTTP 401/403/404/429、超时、接口缺失和上限各有诊断，不自动循环重试。GitLab 文本属于外部资料，不产生授权。

### 正式验证证据

`GET/PUT /api/projects/{id}/assist-config` 维护配置；`POST .../check` 核验已保存 GitLab 绑定，`GET .../discover` 只读识别 origin。所有配置写入和连接检查要求本地 UI 标识，使用配置版本 CAS。每项目最多 16 条来源规则，类型为 JUNIT 或 LOG；JUnit 只允许任务目录，日志可人工登记绝对外部目录，默认无外部授权。拒绝敏感路径、符号链接、根目录和上级跳转。V83 追加项目配置、Task/Designer 冻结绑定、快照元数据与解析缓存；旧任务绑定为空，旧会话不追授新工具。

正式验证在执行前分配 execution ID，采集前后文件身份；保存进程已经捕获的完整输出，保留 stdout/stderr 合并和 1,000,000 字节上限及超限停止语义。受管验证服务停止时也保存输出与停止证明。应用日志采集执行窗口的新增部分，记录偏移、轮转和截断；共享日志只证明观察窗口，不证明独占因果。JUnit 内容变化仅标记“验证期间变化”，不能证明具体生成进程；未变化报告标记来源未确认，不代替正式验收。

扫描最多 4,096 个目录项、深度 12、单次 2 秒、64 个文件和 32 MiB 报告读取量；缺文件、部分目录不可读和扫描上限均有诊断。单份报告上限 4,000,000 字节，日志最多保存新增内容末段 2 MiB。正式验证每次采集读取至多 32 MiB，并以 Attempt 聚合限制 64 个报告／日志和 32 MiB 留存，任务／设计作用域新增快照最多 256 MiB。达到上限保留不完整回执，不自动删除历史文件。

快照先登记 PREPARED，再原子写文件并结束回执；中断或磁盘失败显示未完成／不可用，不伪造完整性。只对正文和字符串字段脱敏，不破坏数字、布尔等元数据类型。读取校验归属、时间范围、文件路径和内容 SHA。后续 clean、重跑或覆盖源文件不改变已保存快照；读取目录只取元数据，不加载正文。

JUnit 解析禁用 DTD 和外部实体，按 testcase 叶子统计，保留失败、错误、跳过和重跑信息。解析缓存以内容 SHA 和解析器版本为键，不在启动时扫描。报告损坏、缺失、零用例、来源未确认和内容截断不能推断测试通过；解析结果不修改验证器或 Judge 结论。

新增 `list_test_failures`、`read_test_failure`、`search_evidence`，已有上下文／失败证据工具增加快照目录，`read_task_evidence` 支持 `snapshot:`。搜索仅字面匹配已保存快照，一次最多 32 MiB、3 秒、50 个片段和 12,000 字符；达到边界明确 incomplete，并返回可用续查游标。评审只读取会话建立前的快照，不获得实时 GitLab、外部路径或其他评审结果。

任务接口位于 `/api/tasks/{id}/execution-evidence`，子接口 `body`、`failures`、`failure`、`search` 均验证任务归属。现有验证详情页增加折叠的“日志与测试快照”，展开才取目录，正文按引用分段读取；页面切换丢弃迟到响应。没有旧快照时显示“未采集”，不会从当前文件重建过去的证据。
