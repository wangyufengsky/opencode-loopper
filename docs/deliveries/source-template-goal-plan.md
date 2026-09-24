# 源码路径模板实施账本

状态：完成。Goal 覆盖实现、聚焦与完整回归、两项真实模型试跑、完整 JAR、本地服务更新、本地提交及用户追加授权的推送与版本发布；尚未完成的项目不得作为交付证据。

## 冻结决定

- 新增 `UNIT_TEST_DEVELOPMENT` 与 `DETAILED_DESIGN_WRITING`，各自冻结模板版本。
- 输入为项目当前目录下的目录或单个源码文件，目录递归；详细设计冻结包含未提交源码的只读副本。
- 单测仅写测试源码和夹具，不自动修改业务代码、依赖或构建配置。执行复用正式 Designer/Task/验证/双 Judge。
- 详细设计交付 Markdown 总览、模块详情、流程图与源码覆盖清单，由服务端校验和保存。
- 两个模板各做一次独立 Java 小样例真实 Provider 验收，不通过新任务重置预算或掩盖失败。
- 用户在执行中追加“完成目标后推送并发布新版本”，授权完成标准推送、不可变新标签、Release 及 CI/远端资产核验；远端部署仍不在范围内。

## 阶段与完成证据

| 阶段 | 状态 | 完成证据 |
| --- | --- | --- |
| 路径输入、冻结来源、持久化与接口 | 聚焦验证通过 | 预检、幂等、稳定快照、漂移、活动 writer、新库与 V126 历史候选升级到 V138 |
| 单测开发与专用范围守卫 | 模拟 Provider 与原生测试回归通过 | Git/非 Git 单包、两包滚动及最终整体回归、失败保留、源码/构建/已有测试保护、多模块输出、容量切换与预算 |
| 详细设计编写与独立复核 | 模拟 Provider 回归通过 | 全文件覆盖、引用/范围拒绝、丢失响应、停止证明、选择重试、跨模块重新复核、已有文档保护与下载一致 |
| 表单、详情、历史列表及恢复界面 | 完成 | 聚焦、完整前端 590 项及正式 JAR/原地址浏览器；修复错误组件导入后真实错误与恢复可见 |
| 聚焦与受影响集成回归 | 已通过，按后续变更补充 | 源码与旧文档链路联合 72 项；归档/单测/迁移/结构 28 项 |
| 两项真实模型试跑 | 完成 | 详细设计 2/2 覆盖、8 篇文档；用户授权一次追加单测，13 项原生测试及同批双 Judge PASS，1/1 TESTED；首次失败保留 |
| 新版本完整门禁和 JAR | 0.4.73 完成 | Java 2331 项（0 失败/错误，3 条件跳过），前端 590 项；JAR、静态资源和 SHA-256 已核验 |
| 本地服务更新和提交 | 完成（本记录随源码提交） | 0.4.73 JVM 85548 / OpenCode 85634；53156 原地址；备份和业务历史核对通过 |
| 推送与新版本发布 | 完成 | v0.4.73 不可变标签；Actions 35941859021 成功；四项资产实测 SHA-256、远端 digest、SHA256SUMS、JAR 版本及静态资源均一致 |

## 基线与所有权

- 开始时分支 `main`，工作区干净，`pom.xml` 版本 `0.4.70`。
- 隔离实施目录 `/Users/wangyufeng/.codex/worktrees/source-template-tasks/opencode-loopper`，分支 `codex/source-template-tasks`，基线 `97bda64`；原工作区保持原分支，避免清理其活动构建输出。
- 当前本项目 JAR 进程初查 PID `95310`，JAR 位于 `data/test-services/0.4.15-20260912-191210/opencode-loopper-0.4.70.jar`；这是待交付阶段复核的历史观察，不构成服务更新授权对象的最终定位。
- 本任务单 Agent 实施，所有新增源码模板文件及明确列入 diff 的集成修改归本任务；构建不并发。
- 已读取根、src、frontend 公约及设计阶段相关合同。现有文档开发自动授权绑定文档来源，需增加真实源码来源，不能伪造上传文档。
- 现有 GIT_DIFF 对范围外新增有审计放行，单测模板必须增加独立严格变更守卫。

## 下一步

无未完成实施项。已完成真实验收、完整门禁、本地运行、提交、推送和 Release 资产核验；首次失败保留，条件跳过与未执行平台验证在交付记录中明确列出。

## 历史验证记录（按实施顺序）

- `./scripts/dev-check.sh backend 'SourceTreeCaptureTest,SourceTemplateAdmissionIntegrationTest,SourceDesignFlowIntegrationTest'`：11 项通过。
- 通过日志：`target/source-template-evidence/source-design-second.log`；此前编译与首次上下文启动失败日志保留在同目录。
- 新增迁移 V127–V131 已通过全新建库测试；带历史候选的升级与负面数据约束尚待专门验证。
- 尚未运行真实 Provider、前端浏览器、完整 verify；未升版本、未替换服务、未提交。
- 成功批次的文档内容保留；其他模块修订使跨模块复核依据变化时，保留旧复核证据并在原角色预算内复核新上下文。
- `source-api-regression-first.log`：5 个源码模板测试类共 17 项通过，覆盖两个模拟 Provider 流程。单测流程的 Maven 是实际子进程执行，Provider 仍为模拟。
- 单测来源路径从写入义务提取中明确分离，服务端另行限制冻结测试/夹具目录，并向最后阶段加入完整模块回归命令；不允许候选删掉整体回归。
- `source-ui-third.log`：前端类型检查及 `SourceTemplateFields.spec.ts` 3 项通过，涵盖预检、迟到响应、项目/模板切换、路径选择取消和配置缺失。
- `source-history-regression-first.log`：30 项通过，包含 V136 新库、列表去重、归档与租约、旧文档入口、MCP 注册及结构门禁。正文覆盖依据独立按需读取，模型批次只返回元数据。
- `source-detail-ui-third.log`：详情页 3 项通过；`source-ui-integration-first.log`：相关界面、API 和状态库 117 项通过。
- `source-promotion-migration-first.log`：13 项通过。V126 历史候选及外键升级到 V137 保持，非法跨 owner 数据拒绝迁移；单包容量切换保留冻结源码和已消耗预算，旧会话未证明停止时不切换。
- `source-scope-profile-first.log`：11 项通过，包含多模块映射、已有 Java 测试语法树保留、构建名称目录范围检查和单包主流程。
- `source-multibatch-first.log`：10 项通过，包含独立批次选择重试、成功作者保留、跨模块上下文修订后重新复核，以及接受候选在预算边缘无模型调用恢复。
- `source-rolling-second.log`：12 项通过，包含 Git/非 Git 单包、实际 Maven 测试失败不成功、两个滚动包整体回归及前包行为破坏时失败。第一轮暴露未忽略构建产物进入阶段快照的问题，已增加 Git 构建输出预检；旧失败日志保留。
- 当前本地配置已只读核对：`opencode-go/deepseek-v4.1-flash`，托管 OpenCode `1.18.31`，内部 MCP 已连接。真实试跑尚未开始；将沿用当前设置冻结预算（单批 3 次、任务恢复 12 次、业务总超时关闭）。

- `source-final-focused-first.log`：源码、旧文档开发与入口、MCP 注册和结构联合 72 项通过。
- `source-archive-regression-first.log`：28 项通过；新增 V138，同步关联 Task 归档/恢复，源模板未收束时禁止归档；所有测试阶段冻结禁止删除检查。
- `source-runtime-ui-regression.log`：类型检查与详情 3 项通过。
- 真实试跑记录目录：`data/source-template-qualification-20260924/`。样例为原工作区忽略的 `data/source-template-qualification-20260924/{unit,design}`，代码 SHA-256 和初始 JUnit 成功日志已保存。
- 单测真实 run `baa2abe5-49ae-447e-aa00-ce63d103a85f`；详细设计真实 run `928b14d6-a1dc-4a80-a8d9-b7ebaee5023e`。冻结模型 `opencode-go/deepseek-v4.1-flash`，各 run 独立预算。首轮单测候选拒绝及后续修正证据保留，未替换任务。
- 隔离运行是 backend-dev 类目录 + 正式依赖，端口 8080，独立数据；Vite 5174。仅为运行/Provider 证据，不是完整 JAR。当前原服务 53156 保持不变。

- 首次真实详细设计 run 已完成：2/2 源码 REVIEWED，8 篇 Markdown，浏览器正文/流程图/内部跳转通过；JSON 正文、单篇下载、ZIP 三者逐字节一致，未提交源码保持不变。整包 SHA-256：`55ca103d8e6f52a038bc8579fd859def7ff3cf93e8bb29abf4021ec2cba32df1`。
- 首次真实单测 run 新增 17 个有效测试，原有 1 个保留，实际原生测试均通过；整体回归阶段因普通开发编译器默认 GIT_DIFF.requireChanges=true，而无额外改动，连续两轮失败后正常 WAITING_INPUT。未修改其冻结合同、未重置预算。
- 已修复新建源码设计编译的 requireChanges=false，仍保留路径、禁止删除、真实测试及双 Judge。`source-nochange-regression-second.log` 28 项通过，包含已有覆盖无需改动的真实 Maven 与模拟双评审流程；第一轮测试编译失败日志保留。
- 已请求用户对“不得以新任务绕过失败或预算”作一次明确例外：保留首次失败，额外一次修复后的真实单测试跑；未收到答复前不得新增该试跑。
- 隔离旧 JVM 20771 与 owned OpenCode 20815 已正常退出，数据库/配置完整备份到 `runtime-before-fixed-restart/`；新 JVM 使用独立 `runtime-classes-fixed/`，不受正式 Maven clean 影响。聚焦日志已复制到 `focused-evidence/` 保留。

- 候选版本已同步为 0.4.71；远端 tag 不存在，Release API 返回精确 404。正式完整门禁首轮日志：`data/source-template-qualification-20260924/verify-0.4.71.log`，仍运行中；旧服务产物位于原工作区 data，未被 clean 触及。
- 完整门禁已暴露 V126 最新迁移版本/迁移数量、工具枚举和普通 MCP 角色假设的旧测试预期；已同步 V138 与新增专属角色，并增加 source 角色无私有 generation 时拒绝全部工具、授权后只允许各自私有工具的测试。待当前全量完成后集中复核与重跑。
- 修复版隔离运行重启验收通过：原单测 WAITING_INPUT、详细设计 COMPLETED 和全部 8 篇落盘文档与原 ZIP 一致；浏览器历史显示两个发起记录，关联 Task 未重复成第三行。
- 原服务升级预检：launchd `io.opencode.loopper.local.53156`，当前 plist 位于原仓库 `data/knowledge-optimization-0.4.70-20260923/`；数据目录为既有 test-services 下 `data`，约 132 MiB。当前无活动实施/评审/PPT 会话，待处理 Task 的租约保留，升级前还须即时复核。

- 完整门禁新增定位 `JudgeDecisionCandidateConfigurationTest` 的两项上下文夹具失败：已为文档/源码读取组合器补充 `SourceOriginalReadCoverage` mock；生产依赖仍保持必需，待统一回归。

- 0.4.71 首轮完整 Java 2330 项：28 失败、2 错误、3 条件跳过，全部为兼容测试夹具。修复后 18 类集中回归 114 项通过。重新分配未占用版本 0.4.72，完整门禁日志 `verify-0.4.72.log`；0.4.71 XML 报告已完整留存。

- 0.4.72 完整门禁通过（Java 2331 项，0 失败/错误、3 条件跳过；前端 587 项），JAR SHA-256 `5e1da9be643c9f0890f4f9a290eff9703481e165ef04ac6aa4ece1e2124f2a98`。隔离 JAR JVM 64911/OpenCode 64930 通过历史、冻结预算和 ZIP 字节保留检查。浏览器发现新组件缺少 ElAlert 导入，未替换正式服务。已修复所有受影响源码组件和共享路径输入，并修复文档重读成功后的旧错误清除；按生产组件注册方式回归 19 项通过。0.4.73 远端标签和 Release 均未占用，重新启动完整门禁。

- 0.4.73 全门禁通过（20:39，Java 2331 项/0 失败/0 错误/3 条件跳过，前端 98 文件/590 项）。JAR SHA-256 `106998eecf93d7932a999ec7d3fef655b2cb6aa9154636976cfcbe36b5cc4406`。正式 JAR 隔离恢复、文档、浏览器通过。原服务正常替换为 JVM 85548 / OpenCode 85634，53156，数据备份 `/Users/wangyufeng/IdeaProjects/opencode-loopper-data-backups/53156-before-0.4.73-20260924-014011`，234 文件/链接核对及 SQLite 完整性通过；历史摘要、计数、配置保持。详见 `docs/deliveries/0.4.73.md`。

- 隔离 JAR JVM 85424、owned OpenCode 85451、Vite 20772/20789 已正常退出；样例、快照、冻结预算及全部失败/成功证据保留。正式服务 85548/85634 保持运行。

- 用户回复“允许，完成goal”后追加单测 run `a894cffc-f798-4b44-b1db-c71d4fe9a4b9` 通过：一次执行、13 项原生测试、同批双 Judge PASS、1/1 TESTED。原预算与模型一致，首次失败 run/阶段/尝试逐行保持；结果检查点仅新增测试文件，业务/构建/已有测试未变化。隔离进程正常停止并备份，详见 0.4.73 交付记录。

- v0.4.73 已发布：Actions `35941859021` 全部成功，远端完整门禁 29:56；四项资产重新下载验证 digest 与 SHA256SUMS 一致，JAR SHA-256 `4ea87e4cf362487981f4337b7efd744c0a423e3cff059f4cbfb0ae10b96acfb1`。补充快照中断 3 项独立验收通过，零模型调用，未改变可执行内容。
