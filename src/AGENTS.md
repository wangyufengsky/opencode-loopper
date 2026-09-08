# 后端开发规则

适用于 src/ 下的生产代码、资源和测试；同时遵守根公约。产品行为按根公约阅读路由加载对应合同。

- 所有新增和修改必须遵守 [代码设计合同](../docs/code-design-contract.md)：编排、策略、持久化、传输、解析和展示职责分离，依赖从适配层指向稳定领域合同，优先组合而非为复用实现建立继承层级。
- 新生产 Java 文件默认不得超过 600 行；推荐类不超过 400 行、接口不超过 250 行、方法不超过 40 行、构造依赖不超过 8 个。存量超限文件由 `CodeStructureContractTest` 维护只降不升的债务上限，拆分时必须同步降低上限，不得为通过构建调高阈值。
- 设计模式只用于隔离真实变化轴：可替换算法用 Strategy，确定性判断用 Policy，构造与协议装配用 Factory/Assembler，有界用例用 Coordinator，外部系统用 Adapter。禁止用无行为的转发层掩盖原 God Class。

- API Controller 只做输入/输出边界、校验和 DTO 映射；业务编排留在 `service/`。
- 领域状态使用现有枚举和 typed failure；不要用散落字符串复制状态语义。
- `DesignerSessionService` 只协调 Designer 生命周期和远端角色步骤；紧凑包计划的规范化、语义校验与可执行证据生成归 `DesignerPackagePlanCompiler`，机器传输形状归 `DesignerSemanticContracts`；OpenCode `question` 能力选择、回答校验、决策日志编码和聊天降级投影归 `DesignerQuestionSupport`，该协作者不得推进 Designer 生命周期，上述职责均不得回流到会话编排器。
- `TaskService` 协调 OpenCode、验证器、Judge 等执行生命周期；不可变设计快照、验证汇总、Git diff 和 Judge 提示证据归 `TaskEvidenceService`。状态机只决定合法转换，不承载外部 I/O 或证据装配。
- 新 API 必须考虑：输入校验、local UI/MCP 授权、幂等、乐观锁、Problem Detail/明确错误码、终态重入。
- MyBatis Mapper 方法应明确行数预期。状态更新和普通字段 mutation 分开，不能用同一 SQL 偷改状态。
- 不得在持有数据库事务时等待模型、进程、网络、浏览器或长时间文件操作。
- 需要同时持久化多个聚合行时，先在事务外解析工作区身份或完成只读外部预检，再用短事务原子写入状态/审计，提交后才执行 Git、文件写入或 Provider 调用；可恢复的跨边界文件写入必须先持久化中间状态并按内容哈希恢复。
- 解析 Git 的 NUL 分隔输出时必须防止 stderr 警告混入数据；本地同步命令局部关闭 `core.safecrlf` 警告，但不得依赖或改写用户的全局 Git 配置。
- 所有外部命令使用参数数组；不要拼接未验证路径或用户内容到 shell。
- Git fetch/分支检查和 checkout 必须暂停调用方 SQLite transaction；远端 fetch 设置 `GIT_TERMINAL_PROMPT=0`，不得因凭据提示无限等待。
- 外部 I/O 的单次等待、集合与诊断输出必须有明确边界。角色总时限、agentic 步数、MCP 提交次数是独立策略，按 docs/opencode-contract.md 的预算矩阵执行；不为明确豁免的角色自行补设限制。重启必须恢复已提交的中间状态。
- 浏览器 SSE 只是权威状态的尽力投影：Task 事件提交后再发布，各订阅者必须隔离；断线、超时、`IOException` 或已关闭的 Servlet `AsyncContext` 只移除对应订阅，不得升级为 Designer、OpenCode Session、Attempt 或 Task 失败。
- Secret 只来自进程环境/内存，不写入 SQLite、日志、artifact 或测试快照。
- 长列表必须使用 `CursorPage<T>` 的时间加 ID 稳定游标，默认 50、最大 100；筛选改变时重置游标，不得用全量载入后在浏览器筛选代替服务端查询。
- Task 列表、详情核心、审计元数据和正文必须保持分层：摘要/overview/audit 禁止读取或返回 `spec_json`、完整 `evidence_json.output`、Judge `raw_output` 或 artifact `content`；正文接口必须同时校验记录属于路径中的 Task。
- 读模型使用独立只读 Service/Mapper、集合查询、聚合或窗口函数；固定查询上限由 MyBatis 统计器测试保护，禁止返回行数增加时产生 N+1。
- 页面只加载自身数据；Task 详情先显示 overview，再后台加载 audit，日志/证据/制品正文按 ID 首次展开时加载并缓存。Task SSE 的 overview/audit 失效分区后 180 ms 合并刷新，不得因任意事件下载全部正文。
- SQLite 保持 WAL、既有 busy timeout 与事务语义。没有经过当前规模和 SQL 证据证明，不得用迁移 H2、盲调同步级别/连接池/缓存替代查询和载荷优化。

### 数据库迁移

- 所有已有 Flyway 迁移（当前 V1–V75）均不可修改；Schema 变化新增下一序号迁移，并同时验证全新数据库和至少一个受支持旧版本升级路径。
- SQLite 外键级联不能只靠假设；活动连接必须明确启用，终止删除路径仍要按依赖顺序显式清理并验证事务回滚。CandidateSubmission 的 owner/scope 校验必须在历史 run 复制时已生效，跨作用域旧数据必须让整个迁移失败关闭并保留可恢复的上一版数据。
- 数据库枚举码、artifact kind、错误码和 audit event 是兼容性契约；修改前先搜索所有 Java、SQL、前端 type/label 和测试消费者。


聚焦测试使用根目录的 `./scripts/dev-check.sh backend <TestClass>`；完整交付使用 `./scripts/verify.sh`。快速测试输出与正式构建隔离，不能作为 JAR 证据。
