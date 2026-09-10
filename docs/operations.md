# 安装配置与运维手册

本手册面向需要调整运行环境、连接外部 OpenCode、迁移数据或排查启动问题的用户。首次使用请先阅读 [项目介绍与入门](../README.md)。版本与发布流程见 [开发与交付](development.md)。

> 本地控制台与模型服务是不同边界：Loopper 和 OpenCode 控制接口保持 loopback，模型 Provider 仍可能通过网络接收提示与项目上下文。请按所选 Provider 的规则配置认证和数据使用策略。

## 配置

### 常用环境变量

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LOOPPER_DATA_DIR` | `./data` | SQLite、证据、二进制工件、Direct 私有基线和旧版 worktree 兼容数据 |
| `SERVER_PORT` | `8080` | Loopper HTTP 端口；监听地址固定为 loopback |
| `LOOPPER_OPENCODE_MODE` | `managed` | `managed` 每次启动独立受管进程；`auto` 兼容复用或启动；`http` 只连接；`fake` 仅用于测试 |
| `OPENCODE_BASE_URL` | `http://127.0.0.1:4096` | 仅供 `auto/http` 探测或连接；`managed` 使用新的动态 loopback 端点 |
| `OPENCODE_ENABLE_QUESTION_TOOL` | 成品启动脚本和受管 OpenCode 默认为 `true` | OpenCode v1.18.23 服务端注册原生 `question` 工具；已有外部进程必须在自身启动环境中带此变量并重启，Loopper 不能修改已运行进程的环境 |
| `OPENCODE_USERNAME` | 空 | 外部 OpenCode 的 Basic Auth 用户名 |
| `OPENCODE_PASSWORD` | 空 | Basic Auth 密码；只从进程环境读取，不持久化 |
| `OPENCODE_EXECUTABLE` | 从 `PATH` 查找 | `managed/auto` 启动 OpenCode 时使用的可执行文件 |
| `OPENCODE_MODEL` | OpenCode 默认值 | 可选的 `provider/model` 默认模型 |
| `LOOPPER_DECOMPOSER_CANDIDATE_ENABLED` | `true` | 大型任务 Decomposer 使用受管内部 MCP 候选提交；设为 `false` 时新 run 使用旧 JSON 兼容路径 |
| `LOOPPER_PACKAGE_DESIGN_CANDIDATE_V1_ENABLED` | `true` | 工作包 Designer 默认使用 `PACKAGE_DESIGN_V1` MCP 主路；设为 `false` 时新工作包直接使用既有 Designer + Markdown 编译路线，已持久化候选仍可恢复 |
| `LOOPPER_PACKAGE_DESIGN_CORRECTION_LIMIT` | `0` | 新工作包 MCP 总提交上限；0 为无限，2–16 为有限，4 为首投加三次修正。已有运行保持冻结值；幂等重放不计数 |
| `LOOPPER_ACCEPTANCE_CLOSED_CHOICE_V7_ENABLED` | `true` | 已获真实模型同 Session 拒绝后自修正资格的 v7 闭集候选开关；设为 `false` 只回滚新运行到旧 JSON 路径，持久化候选仍可恢复和结算。`ACCEPTED/WAITING_INPUT/CLOSED` 仍须取得并持久化 `REMOTE_COMPLETED / ABORT_ACKNOWLEDGED / ALREADY_ABSENT` 才可编译、等待人工、失败收束或切 legacy；停止未确认保持同一 run `DISCONNECTED`，不创建 Task。只有正常完成且零提交的精确关闭原因可切旧 JSON 路径；超时、Provider/交互失败和历史缺失原因均失败关闭 |
| `LOOPPER_ROLLING_PACKAGE_PLAN_V1_ENABLED` | `true` | 已获真实模型同 Session 机械拒绝后自修正资格的滚动任务剩余计划 MCP 开关。`false` 时新建议使用既有只读 JSON marker 路线；一旦候选 Session/run 已建立，零提交、超时、传输、安全、代次和停止不确定均失败关闭且不读取 marker。已有候选运行和 V56 接受结果始终保留恢复/结算 adapter；只有远端完成或确认停止后才形成待人工确认的 `PROPOSED` 计划 |
| `LOOPPER_REVIEWER_REPORT_CANDIDATE_V1_ENABLED` | `true` | 已获隔离成品 JAR 真实模型同 Session 机械拒绝后自修正资格的 Reviewer MCP 开关。`false` 只让新报告使用既有只读 Legacy 路线；已有 Candidate run、冻结源码快照、accepted result 与终止结算继续恢复。派发后零提交、超时、交互、传输/停止不确定、安全失败和耗尽均失败关闭，模型最终文本永不作为报告输入 |
| `LOOPPER_TASK_PROFILE_ROUTER_TIMEOUT` | `240s` | Router 尚未建立远端 Session 时的连接等待；连接成功后不再使用总时限，而是等待真实终态 |
| `LOOPPER_PROJECT_CONVENTION_CANDIDATE_V1_ENABLED` | `true` | Convention 已完成真实模型同 Session 拒绝修正资格；`false` 仅让新公约预览走 Legacy，既有 run、冻结来源、accepted result 和停止结算继续恢复，公约应用仍须人工确认 |
| `LOOPPER_JUDGE_DECISION_CANDIDATE_V1_ENABLED` | `true` | Requirement/Risk 均已完成真实模型同 Session 拒绝修正资格；`false` 仅让新评审走 Legacy，不改变既有候选恢复、同批次聚合、最小权限和失败关闭边界 |
| `LOOPPER_CHROME_EXECUTABLE` | 自动检测 | `BROWSER` 验证器使用的 Chrome/Chromium 绝对路径 |
| `LOOPPER_MCP_BEARER_TOKEN` | 每次启动随机生成 | `/api/mcp-streamable` 和 `/api/mcp` 的 Bearer Token |
| `LOOPPER_JAVA_HOME` | Linux 默认 `/opt/jdk-21`；Windows 使用 `JAVA_HOME`/`PATH` | 显式指定 JDK；可选平台包自动使用同目录 `jdk21` |
| `LOOPPER_JAR_PATH` | 自动查找当前版本 JAR | Linux/macOS/Windows 启动脚本使用的成品 JAR 路径 |
| `LOOPPER_PUBLICATION_HTTP_WEB_HOSTS` | 成品启动脚本包含 `gitlab.spdb.com`；直接运行 JAR 时为空 | 逗号分隔的精确 Git 主机白名单；命中后强制使用 HTTP MR/PR 网页地址，包括显式 HTTPS remote，但不改写 remote 或推送协议 |
| `LOOPPER_GITLAB_HOST` | 成品启动脚本为 `gitlab.spdb.com` | 允许自动核对合并状态的精确 GitLab 主机 |
| `LOOPPER_GITLAB_API_BASE_URL` | 成品启动脚本为 `http://gitlab.spdb.com/api/v4` | GitLab API v4 基础地址；主机必须与 `LOOPPER_GITLAB_HOST` 完全一致 |
| `LOOPPER_GITLAB_PRIVATE_TOKEN` | 空 | GitLab 只读 API Token；仅通过环境变量提供，不写入数据库、日志或前端响应 |
| `LOOPPER_OPEN_BROWSER` | `true` | 启动后是否自动打开浏览器；设为 `false` 可关闭 |
| `LOOPPER_RETRY_RATE_LIMIT_BASE` / `MAX` | `60s` / `300s` | 限流错误的指数退避起始值和上限 |
| `LOOPPER_RETRY_SESSION_BASE` / `MAX` | `10s` / `60s` | 普通 Session 错误的指数退避起始值和上限 |
| `LOOPPER_RETRY_VERIFICATION_BASE` / `MAX` | `5s` / `30s` | 验证失败后的指数退避起始值和上限 |

`/settings` 将非敏感设置按运行环境、OpenCode、执行上限、`RETRY_WAIT` 和发布网络分区。保存时，完整配置写入 SQLite，并原子生成 `${LOOPPER_DATA_DIR}/config/startup-overrides.properties`；数据库或文件任一步失败都会恢复旧文件，不应用半套配置。Linux/macOS/Windows 启动器只逐项读取固定白名单，不执行文件内容，优先级为“显式环境变量 > 页面保存值 > 脚本默认值”；未知键告警忽略，已知键格式非法则终止启动。启动器默认导出 `OPENCODE_ENABLE_QUESTION_TOOL=true`，受管 OpenCode 子进程也会被强制注入该值。数据目录、Java Home、JAR 路径、MCP/OpenCode/GitLab 密钥不进入页面或该文件，监听地址继续固定为 loopback。页面会分别标明立即生效、下一次 Session/Task 生效和重启生效，保存不会自动重启服务。

全局 Stage/Task/Session 次数和时长限制是安全上限，与 LoopSpec 明确值取较小值。生产环境默认启用 `loopper.scheduling.enabled` 和 `loopper.startup-recovery.enabled`，后者统一恢复中断任务、本地同步与自动化状态。

### OpenCode 运行模式

- `managed`（默认）：每次启动独立的受管 OpenCode，使用动态 loopback 端口，不接管已有实例。就绪检查同时验证健康状态与内部 MCP 连接。
- `auto`：先检查配置的 loopback 端点；健康则复用，否则启动一个 Loopper 拥有的本机 OpenCode 进程。只有受管进程可以从 UI 重启。
- `http`：只连接已有的 OpenCode 服务，Loopper 不启动也不终止它。出于本地安全边界，只接受 loopback 端点。
- `fake`：确定性测试适配器，不应在真实任务中使用。

## Linux、Windows 与 macOS 部署

默认 Release 提供独立 JAR、Linux/Windows 启动脚本与 SHA-256 校验文件。请自行准备 JDK 21、Git、OpenCode CLI 与模型认证；运行 JAR 不需要 Maven、Node 或 npm。

### 可选的内置 JDK 平台包

平台包工具保留为手动调用，不参与默认 workflow 或 `verify.sh`。需要时按 [开发与交付](development.md) 生成六个平台包，每个包包含完整 JDK 21、已含前端和 SQLite JDBC 原生库的 JAR、启动脚本、使用说明及 JDK 来源信息。解压整个目录即可运行，无需安装 Java、Maven、Node 或 npm，也无需指定 JDK/JAR 目录。Git、OpenCode CLI 与模型认证仍需自行准备。

| 系统 | CPU | 文件名后缀 | 启动入口 |
| --- | --- | --- | --- |
| Linux | Intel/AMD 64 位 | `linux-amd64.tar.gz` | `./start-linux.sh` |
| Linux | ARM 64 位 | `linux-arm64.tar.gz` | `./start-linux.sh` |
| Windows | Intel/AMD 64 位 | `windows-amd64.zip` | `start-windows.bat` |
| Windows | ARM 64 位 | `windows-arm64.zip` | `start-windows.bat` |
| macOS | Apple Silicon（M 系列） | `macos-apple.tar.gz` | `start-macos.command` |
| macOS | Intel | `macos-intel.tar.gz` | `start-macos.command` |

完整文件名为 `opencode-loopper-<version>-<后缀>`。Linux 包面向 glibc 发行版，不适用于 Alpine/musl。所有包保留 JDK 原始许可证；包内 `distribution.json` 记录 JDK 的下载地址、版本、架构、原始 SHA-256 和 JAR SHA-256。

### Linux / 内网

将 Release 中的 JAR 与 `start-linux.sh` 放在同一目录，准备 `/opt/jdk-21`（其他位置设置 `LOOPPER_JAVA_HOME`），然后运行；可选平台包直接解压后运行：

```bash
./start-linux.sh
```

脚本也允许 `sh start-linux.sh`，会先切换到 Bash。JDK 选择顺序为显式 `LOOPPER_JAVA_HOME`、包内 `jdk21`；独立脚本部署且不存在 `jdk21` 目录时，才使用 `/opt/jdk-21`。不会被系统残留的旧 `JAVA_HOME` 覆盖。包内 JDK 不完整时直接报错，请重新解压。

### macOS

独立 JAR 可使用已安装的 JDK 21 执行 `java -jar "实际下载的 JAR 文件路径"`。如果手动生成了 Apple Silicon 或 Intel 平台包，解压后双击 `start-macos.command`，或在终端运行：

```bash
./start-macos.command
```

脚本通过自身位置找到 `jdk21/Contents/Home` 和 JAR，支持从其他工作目录调用以及解压路径包含空格。它共用 Unix 启动逻辑，图形会话支持目录选择，健康检查通过后使用 macOS `open` 打开浏览器。若 macOS 对下载的脚本或 JDK 提示安全确认，请按系统界面确认来源后允许打开。

### OpenCode 连接

Linux 启动脚本默认使用 `managed`，先把 `opencode` 解析为确定的可执行文件路径，再由 Loopper 在新的动态 loopback 端口启动独立进程；它不会扫描或接管当前主机上已有的 OpenCode。只有显式选择 `auto/http` 时，脚本才会读取命令行中的 `--port`，并通过 `lsof` 或 `ss` 解析已有进程的实际监听端口；候选仍必须由 loopback `/global/health` 精确验真。脚本和受管进程默认启用 `OPENCODE_ENABLE_QUESTION_TOOL=true`。复用外部进程时，该进程不会继承新环境；Loopper 会降级为普通消息提问，直到操作者在外部 OpenCode 自身环境中启用该变量并重启。

受管进程启动成功必须同时满足 `/global/health` 和 `/mcp` 中本代随机内部 Server 精确为 `connected`；只健康但内部 MCP 未连通仍失败关闭。运行环境页只显示脱敏代次和内部 MCP 就绪状态。启动失败后不会因页面刷新反复拉起；点击“启动并检查连接”会执行一次明确重试。

若 OpenCode 使用 Basic Auth，请在启动 Loopper 时保留相同的官方环境变量 `OPENCODE_SERVER_USERNAME`、`OPENCODE_SERVER_PASSWORD`；脚本会自动映射为 Loopper 连接凭据。显式地址仍可覆盖自动发现，`0.0.0.0` 或 `[::]` 监听地址会转换为对应 loopback 连接地址：

```bash
export LOOPPER_OPENCODE_MODE=http
export OPENCODE_BASE_URL=http://127.0.0.1:51234
# 如 OpenCode 开启密码：export OPENCODE_SERVER_PASSWORD='与 OpenCode 启动时一致'
./start-linux.sh
```

仅在 `http` 模式下，需要先在同一台机器启动兼容的 OpenCode 服务；默认 `managed` 模式由 Loopper 启动独立进程。两种模式都需要提前配置模型认证。Loopper 与 OpenCode 都应保持 loopback，项目绝对路径必须在这台主机上可见。

### Windows

将 Release 中的 JAR 与 `start-windows.bat` 放在同一目录，确认 JDK 21（`JAVA_HOME` 或 `PATH`）、Git 和 OpenCode CLI 已配置；可选 Windows 平台包直接解压，无需另装 JDK。然后双击 `start-windows.bat`，或在 CMD 中运行：

```bat
start-windows.bat
```

PowerShell 默认不会从当前目录搜索命令，必须带 `./` 或 `.\`：

```powershell
.\start-windows.bat
```

脚本按显式 `LOOPPER_JAVA_HOME`、包内 `jdk21` 的顺序查找 Java；独立脚本部署且不存在 `jdk21` 目录时才回退到 `JAVA_HOME`、`PATH`，并拒绝低于 21 的版本。默认 `managed` 直接由 Loopper 在动态 loopback 端口启动独立 OpenCode，不扫描已有进程。显式选择 `auto/http` 时才通过 Windows 进程信息读取 `opencode serve --port ...` 候选并要求 `/global/health` 精确验真。

需要固定路径或端口时，可先设置环境变量：

```bat
set "OPENCODE_EXECUTABLE=C:\Tools\opencode.exe"
set "SERVER_PORT=8080"
start-windows.bat
```

若要连接外部地址，必须同时显式设置 `LOOPPER_OPENCODE_MODE=http` 和 `OPENCODE_BASE_URL`；地址离线时直接报错。需要认证时同时设置 `OPENCODE_USERNAME` 和 `OPENCODE_PASSWORD`。设置 `LOOPPER_OPEN_BROWSER=false` 可禁止自动打开页面。由 `managed/auto` 启动的 OpenCode 归 Loopper 管理，Loopper 退出时会停止；外部实例不会被停止。

其他注意事项：

- 服务端无桌面时，直接在 UI 输入项目绝对路径；原生目录选择按钮需要图形会话及 `zenity`、`kdialog` 或 `yad`。
- `BROWSER` 验证器需要本机 Chrome/Chromium；非标准位置请设置 `LOOPPER_CHROME_EXECUTABLE`。
- 图形环境中，脚本会在健康检查通过后尝试打开浏览器；无头环境只输出访问 URL。
- 内网首次从源码构建仍需要 Maven 与 npm 依赖缓存；只运行已打包 JAR 不需要访问这些仓库。

将 `LOOPPER_JAR_PATH` 设置为实际 JAR 的绝对路径后，可检查它是否包含前端资源（这不替代浏览器验收）：

```bash
jar tf "$LOOPPER_JAR_PATH" \
  | rg 'BOOT-INF/classes/static/(index.html|assets/)'
```

## 数据、安全与备份

### 数据目录

默认 `./data` 中包含：

- `loopper.db` 及 SQLite WAL 相关文件；
- `worktrees/`：旧版本或历史任务的 Git worktree 兼容目录；新任务直接切换登记目录的任务分支；
- `direct-baselines/`：Direct 任务的私有比较基线；
- `stage-baselines/`：每个任务共享对象库、每个 Stage 独立索引的私有验收基线；
- `artifacts/`：浏览器截图、trace 等二进制证据；
- `publication-patches/`、`local-sync-conflicts/`：发布与同步冲突材料。

要迁移或备份，先正常停止 Loopper，再整体复制 `LOOPPER_DATA_DIR`。被登记的源项目不在数据目录内，需要按项目自己的 Git/备份策略单独保护。恢复时应同时保持源项目路径和 Git 历史可用。

### 安全边界

- Loopper HTTP、受管 OpenCode 与验证器网络访问都限制在 loopback。
- 项目根和执行路径会 canonicalize，并进行目录 containment 与符号链接检查。
- OpenCode 创建 Session 后必须返回与请求一致的规范执行目录；缺失或不一致时在提示模型前停止。执行策略不可批准 `git commit`、引用/分支变更、fetch/pull/push、外部路径、危险删除或 hard reset；发布是成功后单独的人机确认流程。
- 进程验证器使用参数数组启动，不进行 shell 插值；它不是操作系统沙箱，不应运行不可信的恶意二进制。
- 密码和 MCP Token 不写入 SQLite、日志或证据。
- 任务取消会停止执行并保留目录、分支与证据；Loopper 不自动丢弃文件改动，也不删除旧版 worktree。
- 自动化同样经过队列、权限、验证器和双 Judge，不会绕过人工或安全门槛。
