# 内网辅助 MCP 合同

本主题持有辅助能力的工具策略、数据库凭据和证据读取边界。生命周期与最终接受仍由 [架构合同](architecture.md)、[验证器合同](seven-feature-contract.md) 持有；候选提交仍由 [OpenCode 合同](opencode-contract.md) 持有。

## 服务与权限

辅助服务位于 `/api/assist-mcp-streamable`，随受管 OpenCode 配置注入，独立于公开六工具和内部候选提交服务。传输只接受 literal loopback、当前代际 bearer；每次工具调用还必须持有服务端签发的 `scope`。该凭证绑定外部 Session、代际、项目、Task／Stage／Attempt 或 Designer 身份，模型不能以请求参数替换这些身份。停止、结束、所有者改变或代际变化会使作用域失效。兼容模式不获得辅助凭证。

受管子进程关闭模型目录下载和自动更新，并使用 npm 严格离线模式。内置本地统计守卫不依赖 npm 包，保持启用；用户已有配置和本地插件保留。需要额外 npm 包的用户插件须由管理员提前准备依赖，缺失时不可通过自动联网安装绕过。内网 Provider 仍使用管理员明确配置的地址与模型；这些开关不禁止已配置的内网模型请求。

`AssistToolCatalog` 是九个工具的名称、schema 和角色目录：

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

策略只控制 Loopper 新建 Session，不修改用户 OpenCode 文件，不中断现有调用，不控制其他客户端。既有 Session 与精确恢复使用原权限摘要；新 Attempt 读取当前工具策略。Task 创建时即冻结数据库连接配置、凭据版本与项目绑定，后续连接修改、归档或新绑定不能扩大该 Task 的数据授权。升级前 Task 冻结为空数据库授权，需通过新任务使用新增数据能力。Designer 作用域首次建立时冻结连接集合。

需求、规划、设计可读取数据库和文档；实施增加当前任务证据查询，Word 写入仍需逐阶段授权；评审和双 Judge 仅可读取 Session 开始前已采集的证据，不开放实时数据库或 Word。Judge／Reviewer 的辅助调用结果不进入共享证据目录。Router、纯修复及收尾保持最小工具集合。模板分析只增加明确的辅助只读工具。

服务端在 HTTP 投递前附加最小上下文、凭证、工具与限制，因此自定义 Role Pack 和重试模板不能删除必要约束。辅助 MCP 不可用时在投递模型前返回明确错误；已明确要求 DOCX 的实施阶段若关闭 Word 工具，也在投递前拒绝。工具或配置错误不能自动替代生命周期决策，也不能改用 shell／外部网站绕过授权。

## 数据库管理、驱动与只读执行

页面 `/databases`、REST `/api/database-connections` 支持创建、编辑、项目绑定、测试、启停和归档；不提供网页 SQL 编辑器。密码不回显，所有修改及连接测试要求本地 UI 标识。分页使用时间加 ID 游标。

四个适配器分别处理 MySQL、GaussDB／openGauss、GoldenDB、达梦的 JDBC URL、连接属性和 schema／catalog 差异。驱动由管理员放入 `LOOPPER_DATA_DIR/jdbc-drivers`，页面显示文件名、大小和 SHA-256；最多 64 个驱动、单个 64 MiB。第一版不上传或在线下载驱动。按文件 SHA 和驱动类有界复用独立类加载器（最多 64 个版本），每次使用独立 JDBC 连接，SQLite 数据源及现有验证器的 loopback 限制保持独立。

驱动类和协议必须匹配现场版本。Gauss 适配器根据厂商驱动类选择 `gaussdb`、`opengauss` 或 PostgreSQL 协议；GoldenDB 的厂商 MySQL 驱动配置可选择 MySQL 协议。这只是适配路径，不能据此宣称产品完整兼容。Gauss 强制 `allowReadOnly=true`；达梦不允许配置会绕过只读设置的兼容参数。连接参数仅接受白名单中的 TLS、时区、字符集选项，不能覆盖只读、超时、本地文件访问或多语句保护。

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

probe JSON 示例（根据现场驱动修改）：

```json
{"connection":{"type":"MYSQL","host":"db.internal","port":3306,"database":"app","username":"reader","driverFile":"vendor.jar","driverClass":"com.mysql.cj.jdbc.Driver","schemas":["app"],"parameters":{},"timeoutSeconds":10,"maxRows":200},"readSql":"SELECT 1","timeoutSql":null}
```

支持状态按现场产品、版本、驱动和探针报告逐项登记。模拟 JDBC 与自动化测试不等于四库联调；未具备现场实例的四种产品均保持待联调。
