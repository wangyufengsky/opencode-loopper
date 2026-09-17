# 开发、验证与交付

本文件持有仓库开发流程；产品里的任务确认、Judge、提交和发布按对应产品合同执行。入口为 [根公约](../AGENTS.md)，后端与前端分别按 [后端规则](../src/AGENTS.md)、[前端规则](../frontend/AGENTS.md) 加载。规则写清目标、约束、完成证据与例外，不为每个历史问题增加全局步骤。

## 一次任务如何完成

1. 读取当前公约与工作区状态，明确本次目标、已有修改和完成证据；按影响加载合同、实现和测试。小型文案或解释任务不要求全仓库调查；行为变更追踪受影响链路，新增实现模式优先参考已有实现。
2. 集中定位相关问题，确认行为预期和共同根因，形成一致的修改批次。普通实现选择自主处理；只有尚未授权的产品、兼容或外部副作用决策需要用户决定。
3. 编写修改与必要行为回归，先执行聚焦检查；同一问题的反例、恢复路径一起验证。检查失败先诊断共同原因，不逐个修补症状。
4. 可执行内容稳定后，先保护可能被 clean 清除的既有运行产物，再分配新版本并运行一次完整门禁。已通过的检查只有在输入改变、出现失败或具体未解决风险时才失效；只重复受影响范围，最终候选仍须完整验证。
5. 核验产物后，按下文完成当前环境本项目服务的启动或替换及运行验证，记录证据，核验 diff，暂存本任务文件并本地提交，然后回复用户。不要停在首版实现或仅有 JAR 的状态；远端发布与部署仍按各自授权处理。

只读任务不修改或打包。纯说明文档执行文档检查并本地提交，不升版本、不生成 JAR。被程序读取的提示、规则、配置、构建脚本和依赖均按可执行内容处理。用户明确要求跳过测试/打包时照办，记录未完成证据。已有无关失败不能虚报通过；保存输出、说明归属与未覆盖范围。

## 开发反馈与正式门禁

```bash
# 文档大小、路由链接、版本引用；需要 Node.js 22
node scripts/check-project.mjs
# 构建工具的故障和边界回归
node --test scripts/tests/project-tooling.test.mjs
# 仅后端聚焦测试，可逗号分隔；通配符需引号
./scripts/dev-check.sh backend CodeStructureContractTest
# 前端先类型检查，再运行一个指定测试文件；首次先安装锁定依赖
npm --prefix frontend ci
./scripts/dev-check.sh frontend src/views/TaskDetailView.spec.ts
# 最终完整验证、前端与可执行 JAR
./scripts/verify.sh
```

`backend-dev` 是显式 Maven profile：跳过 Node 安装、npm、前端测试/构建和静态资源复制，将全部 Maven 输出写入 `target/backend-dev`，并关闭 Spring Boot repackage。它不生成正式可执行 JAR，不能证明前端打包、静态资源或跨端集成正确。需要静态资源的测试用完整模式；不要给聚焦命令附加 `package` 或称其为交付。

Windows 可用 `mvnw.cmd -Pbackend-dev -Dtest=CodeStructureContractTest test` 取得相同反馈。前端快捷命令使用本机 Node/npm；正式 Maven 门禁始终准备 `pom.xml` 固定的工具链。不同输出目录仍共享源码和前端目录，同一 checkout 不并发运行构建。

默认 `verify.sh` 只执行完整 Maven 门禁并生成可执行 JAR，不下载或组装 JDK。GitHub Release 发布独立 JAR、Linux/Windows 启动脚本与校验文件。

仅在明确需要内置 JDK 的平台包时，先完成完整门禁，再手动执行 `python3 scripts/package-distributions.py`，生成六个平台包及独立 JAR、`SHA256SUMS` 至 `target/release/`。此可选步骤需要 Python 3.12+ 与 curl。Linux/Windows 各有 amd64 与 arm64，macOS 分 Apple Silicon 和 Intel。下载使用 `scripts/jdk21-lock.json` 中固定的 Temurin JDK 21 URL、大小及 SHA-256，并核对解压后的 Java 版本、CPU、系统、编译器和许可证。完整 JDK（包括 legal）保留，macOS 保留 Contents/Home 布局。

JDK 缓存位于 `~/.cache/opencode-loopper/jdk21`，每次复核大小和 SHA-256；首次手动组装需要联网下载六套 JDK，之后可复用已校验缓存。`python3 scripts/package-distributions.py --download-only` 可预取。`--platform <平台名>` 可单独打包，`--cache <目录>` 可指定离线缓存，`--output <新目录>` 可指定输出；手动命令默认生成六包。已有输出目录会拒绝覆盖，失败不发布部分成品，升级 JDK 时更新锁文件并重新验证。

正式 `verify.sh` 显式关闭 `backend-dev`，恢复前端构建并执行 `clean verify`。CI 与 Release 均使用 Maven `clean verify` 执行完整门禁，不调用平台包组装工具。完整门禁还需要 Python 3.12+ 执行离线打包回归（macOS/Linux 为 `python3`，Windows 为 `python`）；这不下载真实 JDK，也不生成六个平台成品。运行发行 JAR 不需要 Python。Maven 完整链路包含：工具链安装、`npm ci`、项目文档/工具检查、`vue-tsc -b && vite build`、Vitest、Node 工具测试和全部 Java 测试。类型检查已包含在 `build` 中，不再单独重复执行一次。

`check-project.mjs` 的机械覆盖范围是：三份公约的 UTF-8 字节上限、它们直接链接到的 Markdown 文档及这些文档中的相对文件链接、指定发布字段的一致性。它不验证 Markdown 锚点、反引号中的路径、远端 URL 或合同语义，不递归遍历历史。源码的依赖方向由 `CodeStructureContractTest` 的指定字节码规则补充验证；规模门禁继续保持 600 行和既有债务上限。检查不是对整体架构正确性的证明。

## 版本只有一个来源

`pom.xml` 是当前版本来源。同步引用包括前端 package 与 lock 根版本、MCP YAML、README 当前版本及 JAR 命令、根公约当前版本、Linux/Windows 启动脚本。Java MCP server info 从配置注入，不再维护另一个 Java 常量。依赖版本、历史说明、历史哈希不随交付改写。

采用递增且未使用的 `MAJOR.MINOR.PATCH`，MINOR/PATCH 为 0–99；`0.3.99 → 0.4.0`，`0.99.99 → 1.0.0`。正式候选完整构建后，若可执行交付内容再变化则使用下一版本；同一失败构建仅环境/诊断重试可复用版本。纯结果记录回填不要求重建。

```bash
node scripts/release-version.mjs check
node scripts/release-version.mjs next
# 将 next 的结果填入 <version>，先查看计划，再写入
node scripts/release-version.mjs set <version>
node scripts/release-version.mjs set <version> --write
./scripts/verify.sh
```

脚本在写入前检查所有指定引用、递增关系、本地同名 tag 与 JAR；任何字段缺失/不一致都停止。它保留文件行尾与历史段落；写入后仍须检查 diff。脚本不声明跨文件原子提交，磁盘写入失败时须根据 diff 恢复本次部分变更，不得覆盖用户内容。

开始正式候选前，还必须检查远端 `refs/tags/v<version>` 与 GitHub Release 是否已占用；远端不可达不等于版本空闲。脚本的本地检查不能代替这一步。无需仅为查重而推送或创建标签。

## 交付和发布证据

正式门禁后检查 `target/opencode-loopper-<version>.jar` 非空，用 `jar tf` 核验 `BOOT-INF/classes/static/index.html` 与 assets，再计算 SHA-256。只有交付内置 JDK 平台包时，才另行核对 `target/release/SHA256SUMS`、包内 JDK/JAR/入口及权限；在可用宿主系统上从含空格的解压目录执行启动脚本，以独立端口和数据核验健康端点及前端，其他系统的实际启动明确列为未覆盖。

记录写入 `docs/deliveries/<version>.md`，包括范围、验证命令/结果、JAR 路径/哈希、本地服务更新证据和未执行项；纯说明文档可用日期与主题命名。只回填结果不递归打包。提交号放在最终回复，避免为了把提交号写进自身提交而反复提交。

没有远端发布授权时，完成本地服务更新和本地提交后交付，不推送。收到发版授权后：复查提交包含已核验交付，确认版本未被占用，推送该提交及不可移动的 `v<version>` 标签，等待 Release Actions 完成，回读独立 JAR、Linux/Windows 启动脚本、SHA256SUMS 与远端 digest。远端重建产物与本地 JAR 分别记录，不能假定字节相同；本地已运行的 JAR 不自动成为远端资产验收证据。远端部署仍需对应授权。

## 版本更新后的本地服务

每次版本更新的完整 JAR 通过门禁和产物校验后，Agent 必须检查当前环境：没有本项目 Loopper 运行实例则启动本次新版本，有则替换为本次新版本，完成运行验证后再交付。这是用户对后续版本更新的持续授权，包括必要的正常停止与启动，无需逐次确认；明确要求不启动、不替换或仅准备产物时按当次要求执行。纯说明文档不升版本，也不触发服务更新；构建或产物校验失败时不替换旧服务。

1. **确定目标与保留旧产物。** 用进程、JAR 绝对路径、工作目录、监听端口和数据目录共同确认本项目实例，不按 `java` 名称批量终止。不接管隔离验收实例或无关服务；多个实例时按当前任务指定环境和可核实的运行记录定位，无法唯一确定时保留实例并说明具体歧义。分配版本、执行 clean 前，若旧 JAR 或配套启动文件位于构建清理范围，将其完整保留在范围外并记录哈希。清理不得删除活动 JVM 正在使用的 JAR 路径，必要时在隔离构建目录完成候选；备份副本不能替代对活动路径的保护，不覆盖运行中的 JAR。
2. **沿用运行配置。** 保留原访问地址、数据目录、JDK、OpenCode 模式、已保存设置及必要环境配置，不在输出中暴露秘密。没有运行实例时优先使用可核实的既有本地配置；首次启动使用本仓库绝对路径下的 `data` 和默认 8080 端口，并显式传入数据目录，避免启动器工作目录改变数据位置。端口被无关服务占用时不停止该服务，定位明确后再处理冲突，不静默换址或接入另一套数据。
3. **正常停止并备份。** 替换前确认活动任务和写入状态；有执行中任务时先等待安全停止点，不为升级擅自取消业务任务。正常关闭旧 JVM，确认旧进程和它拥有的 managed OpenCode 子进程退出；外部 OpenCode 保持运行。停止证明不明时不启动第二个 writer。停止后把完整数据目录备份到该目录之外，包含 SQLite 相关文件、附件、证据及配置，并核验备份可读；无运行实例但已有数据时，同样在新版本启动和迁移前备份。源项目按 [运维手册](operations.md) 的备份边界保护。
4. **通过正式启动器启动。** 使用本次已校验的完整 JAR 和平台对应启动器，显式指定 `LOOPPER_JAR_PATH`、`LOOPPER_DATA_DIR`，按原配置传入 `SERVER_PORT`、`LOOPPER_JAVA_HOME` 等；macOS 使用 `scripts/start-macos.command`，Linux 使用 `scripts/start-linux.sh`，Windows 使用 `scripts/start-windows.bat`。沿用 `config/startup-overrides.properties` 的加载方式，不用绕过保存设置的裸 `java -jar` 替代。交付进程须在工具调用和当前 Agent 回合结束后继续运行。
5. **核实运行事实。** 检查健康端点、运行时接口返回的版本、新 PID/JAR/端口、OpenCode 与内部 MCP 状态；替换时确认旧 PID 已退出，任务、项目、报告历史及设置保持。打开原地址（首次启动为实际地址）确认页面加载和历史可见，按本次变化验收受影响页面。真实 Provider 调用仍遵守独立授权和预算，健康通过不等于 Provider 已验证。
6. **失败与交付。** 启动或迁移失败时保留旧产物、备份及日志，诊断并完成范围内修复；不在已迁移数据库上盲目启动旧版本，不覆盖新增数据或手改 SQLite。无法完成时明确报告旧服务是否停止、新服务是否可用、阻塞原因及可恢复位置，不能报告更新成功。成功后记录实际运行版本、PID、JAR 路径/哈希、访问地址、备份位置和验证结果，再本地提交并回复用户。

## 公约迁移与维护

0.3.81 根公约全文按原始字节保存在 [历史快照](history/agents-0.3.81.txt)，SHA-256 为 `3e4a98f06af10fda04760038c89bc3471ccf40229cfa636a705ec34a8555769b`。历史文件内相对路径以原仓库根为基准，只用于追溯，不是当前指令。

| 原公约内容 | 当前归属 |
| --- | --- |
| 每次任务步骤、版本、打包、提交、维护记录 | 根公约摘要、本开发文档、`docs/deliveries/` |
| 后端/数据库细则 | `src/AGENTS.md` 与代码设计合同 |
| 前端/视觉/状态细则 | `frontend/AGENTS.md` 与设计合同 |
| 生命周期、终止、租约、结果/人工认定 | [架构合同](architecture.md) |
| Designer 流程、全自动、画像和历史冻结 | [设计合同](design-contract.md) |
| OpenCode 协议、通道、步数和时间/次数预算 | [OpenCode 合同](opencode-contract.md) |
| 候选来源、角色职责、V2 当前默认 | [AI 角色合同](ai-role-contracts.md) 与 [V2 启用](package-design-v2-enablement.md) |
| GIT_DIFF、验证、恢复、统计/交互/自动化 | [功能合同](seven-feature-contract.md) 与 [统计合同](story-binding.md) |
| 实验撤回、旧默认、版本维护流水 | [回退合同](package-behavior-rollback.md)、[历史索引](history/README.md) |

新增规则放到唯一权威主题。根公约只保留高频跨域不变量和路由；当前默认与历史条目冲突时，先按冻结代际识别适用范围，再修正陈旧说明，不能用历史版本覆盖当前行为。不得把新增细则和维护流水重新堆回根公约。

## Astra 协作配置与评测状态

本轮优化的是仓库开发流程：减少默认上下文、说明自主决策边界、批量修正、按风险验证、保留跨轮进度。模型与 reasoning effort 沿用用户设置；不因模型名称强制降低推理强度，不给所有小任务增加计划或多 Agent。独立审查/模块实现确有收益时才分工，由主 Agent 统一集成和交付。

这不是 Loopper 内部 Provider 的 Astra 兼容性认证。产品现有 Router/结构化角色参数、权限与超时均保持；Astra 经过 OpenCode 适配后的参数、工具调用、冻结恢复和停止协议需要另行集成验证，不能用一次 Codex 开发成功替代。

固定比较任务如下；每个任务都从同一冻结代码基线在独立 worktree 开始，只替换旧/新公约和本轮开发工具。测试 oracle 在运行前独立冻结，失败案例不能调入比较集后反复优化。为公平比较，双方均应有相同测试 oracle；记录工具差异为实验变量。

| ID | 固定任务提示 | 独立验收关注点 |
| --- | --- | --- |
| DOC | 修正一条已给定行号的过时开发命令，不改变软件行为 | 文档准确，无 JAR/升版本/无关改动 |
| JAVA | 修复由固定 failing fixture 给出的取消能力投影遗漏 | 相邻读模型一致，反例通过，无新生命周期语义 |
| UI | 修复固定 fixture 的 overview 字段缺失回退 | 缺失不默认为 false，旧 API 兼容 |
| STATE | 修复固定 fixture 中停止未确认后错误释放租约 | 保留 writer/租约，无重叠执行，恢复可重试 |
| REFACTOR | 将固定纯裁决从 facade 抽出，保持现有结果 | 无反向依赖、公共 API/审计/事务边界保持 |
| BUILD | 在版本 0.3.99 的固定夹具中准备下一版本 | 0.4.0、全部引用同步、历史与 lock 依赖保持 |

执行前必须补齐基线 SHA、完整提示、fixture/oracle SHA 和任务预算；表格目前是评测计划，不是已运行语料。每组建议至少三次独立配对执行，保持模型、effort、工具权限、缓存条件与终止预算一致。记录最终正确率、未授权修改、额外提问、重复全量构建、耗时、Token、人工返工和实际 diff；先满足正确率/边界不退化，再讨论成本。零分母和未运行记为未测，不报告虚构百分比。

本轮只验证机械门禁与项目回归，尚未运行旧/新工作流的 Astra A/B，因此不承诺 Token 或耗时改善幅度。
## 辅助能力离线验收

新增辅助 MCP 的权限、凭据、文档和数据库验收入口见 [内网辅助 MCP 合同](assist-mcp-contract.md)。完整 JAR 在构建时收集固定 MySQL、GaussDB/openGauss、Oracle、DB2、SQL Server、达梦驱动及依赖，运行时离线校验并隔离加载；旧 openGauss profile 与 GoldenDB 保留历史驱动恢复。模拟测试不证明现场产品兼容。离线脚本只运行读取与边界探针，不启动或替换现有服务。
