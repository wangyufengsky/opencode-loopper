# 后端分层联调

**当前终态：[100项失败分类、进程最小修复与模拟测试报告](2026-10-04-followup14.md)。** 最后模拟合同两批23/23＋4/4通过；候选0.4.92的正常进程树清理修复有严格通过证据，但关联35项为32 PASS/2 FAIL/1原Windows SKIP；业务57项回归为30 PASS/27环境ERROR。完整Java/JAR仍阻塞。用户已取消真实模型，累计模型调用0；没有等待Luna授权的步骤。

[此前同源码业务结果](2026-10-04-followup.md)保留：`60759aeb…`真实Spring0.4.91的57关联Java、30 REST、5实际生产browser通过；一次真实Java→OpenCode→本地mock运输通过。前端2390 unit及type/build/tooling/accounting/零Vue结果对应旧`0f328858…`，不能冒充新候选全量通过。

**[直接查看 12 张三皮肤联调截图](screenshots/README.md)**，均为真实浏览器＋真实 Spring＋合成模拟数据、确定性 fake，不是真实模型结果。未推送、发布或发送 Slack；环境日志、数据库、临时配置和认证内容不在仓库。下文是准备阶段历史快照，不代表当前测试状态。

## 历史准备记录（4f06621c，非当前终态）

基线：`11ca25a3bb764a2d80ac924350139a7087639121`，独立分支 `feat/react-full-migration`。2026-10-04，本轮新增测试脚手架与审查记录；未修改已验收 React UI 或生产 Java。**当前未取得实际 Spring 健康回执，不是后端联调通过报告。**

## 实际环境与工具链

04:59:47 UTC 在原 Cloud 实际执行 `pwd`、`git status --short`、`git rev-parse HEAD`，退出 0：工程 `/workspace/opencode-loopper-react-full`、工作区干净、HEAD 精确为上述基线。没有重建环境、reset 或新建工程。原三员通过已有任务继续工作，没有新增成员：

| 原成员 | 本轮文件所有权与职责 |
| --- | --- |
| A `/root/react_flow_workflow` | `scripts/backend-integration/rest-contracts*.mjs`；真实 REST / DTO / CAS / receipt / Task SSE 候选与安全自测 |
| B `/root/react_legacy_canvas` | `frontend/e2e/backend-integration/`；实际 React + Spring 浏览器、合成 DOCX、三皮肤证据准备 |
| C `/root/react_ppt_canvas` | [独立审查](C-independent.md)；非作者协议、隔离、生命周期、旧运输脚本与 CLI 能力复核 |
| 组长 | 正式工具链、依赖构建、隔离 Spring 进程、默认 mock E2E 收集边界、集成与最终记账 |

历史委派模型为三名 `gpt-6.1-sol / xhigh`；当前任务工具可证原 task ID 可复用，没有可实时读取模型设置的字段，不将历史配置冒称新平台回执。

正式工具下载成功，校验和来自仓库锁定文件或官方同源校验文件：

| 工具 | 官方来源、版本与校验 |
| --- | --- |
| JDK | 仓库 `scripts/jdk21-lock.json` 的 [Temurin 21.0.12.1+1 Linux x64](https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz)；207473347 bytes；SHA-256 `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`；`java` / `javac` 实际退出 0 |
| Maven | wrapper 锁定 [3.9.14](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.14/apache-maven-3.9.14-bin.zip)；9347269 bytes；[官方 SHA-512](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.14/apache-maven-3.9.14-bin.zip.sha512) 为 `4122c5e7a8794260539dd8fcd78480549511babff2f85e2b1258c8d4cf33c50af90f65d323f43c88d4959f35a8f37ced3eca802983caa6eb7cc81b16af936ab0`；`mvn -version` 实际退出 0 |

工具安装在本任务 `/workspace/backend-integration-tools/`，没有改用户凭据、网络或全局配置。下载和版本证据位于 `/workspace/backend-integration-20261004/{jdk-install,maven-install}.json`；这些环境记录不加入 Git。

## 构建阻塞的准确位置

05:04:56–05:04:57 UTC 运行以下命令，实际 **exit 1**，尚未进入 Java 编译或测试：

```sh
JAVA_HOME=/workspace/backend-integration-tools/jdk-21.0.12.1+1 \
  /workspace/backend-integration-tools/apache-maven-3.9.14/bin/mvn \
  -B -Dmaven.repo.local=/workspace/backend-integration-tools/m2 \
  -Pbackend-dev -DskipTests compile dependency:build-classpath \
  -Dmdep.includeScope=runtime \
  -Dmdep.outputFile=/workspace/backend-integration-20261004/runtime-classpath.txt
```

失败的是 `org.springframework.boot:spring-boot-starter-parent:4.1.0`，官方主机 `repo.maven.apache.org`，错误 `Temporary failure in name resolution / Unknown host`。正常工具 network 权限下 DNS 复核仍为 `gaierror -3`；同一官方 POM 经现有环境用 curl 得到 HTTP 200、exit 0。**不是本轮 Maven 429、CONNECT 403 或所有下载不可用。**

Java Resolver 不保证自动继承 HTTP_PROXY/HTTPS_PROXY。只查询过环境变量是否存在，未读取或记录其值。用户禁止改代理配置，因此没有擅自添加 Maven settings、JVM 代理参数或其他网络设置。已向项目经理提出一个具体待决定项：是否允许仅本次 Maven 子进程沿用既有无凭据代理的临时参数；未获明确答复前不继续此路径，若代理需要凭据仍停止。

原始失败保留 `/workspace/backend-integration-20261004/backend-compile.log` 和 `backend-compile-result.json`；诊断为 `network-diagnostic.json`。没有删除失败或换缓存伪造成功。

## 可复验脚手架与运行门槛

[隔离启动器](../../../scripts/backend-integration/isolated-server.py) 只接受 canonical 绝对路径和明确冻结 revision，要求已存在的 backend-dev classes / 全部依赖；独占创建新 run root，复用已有数据库即拒绝。它以环境 allowlist、独立 Java user.home/tmpdir/data-dir/project-root、loopback 端口、fake/model、scheduler=false、startup-recovery=false 启动真实 Spring。健康 `UP` 与 runtime `AVAILABLE / fake / managed=false` 才发布 ready proof；不把声明当数据库配置的公开 REST 证明。

本轮拟用 `/workspace/backend-integration-20261004/phase1-run-1`，项目归 A/B 各自子目录。仅合成输入与本地项目，不访问真实业务数据库，不手改 SQLite、不调用外部工具或真实模型。SIGINT/SIGTERM 清理 `start_new_session` 创建的自有 Java process group并等待 Java exit；该声明不等于所有 descendant/GC 对象已释放，实际运行后须补准确退出证据。

[A REST 范围与源码映射](A-rest-review.md) 与 [B 浏览器范围](B-browser-upload.md) 分别列契约。新增浏览器使用独立 config，默认 mock Playwright 套件明确不收集该目录；未删除或跳过任何原测试。所有新业务测试在真实 Spring 可运行之前为 **NOT_RUN**。

最终准备检查：

| 检查 | 本轮实际结果 |
| --- | --- |
| `python3 -m unittest discover -s scripts/backend-integration -p 'test_*.py' -v` | **7 / 7 PASS**，测试启动器安全与自有子进程退出；不验证 Spring |
| `node --test --test-isolation=none scripts/backend-integration/rest-contracts.test.mjs` | **14 / 14 PASS**，注入合成 response 的请求冻结、未知回执禁止后续写、CAS / SSE parser / 隔离负控；不是实际后端测试 |
| 专用 `tsc --noEmit --project e2e/backend-integration/tsconfig.json` | exit 0；首轮专用检查缺 `@` alias 已以本目录配置修正，未改生产实现 |
| 专用 Playwright `--list` | **5 tests / 1 file / 1 desktop Chromium project** 收集成功、exit 0；只用 loopback port 9 占位收集，未发 HTTP / 启动浏览器 |
| 默认 Playwright `--list` | **575 tests / 77 files** 收集成功、exit 0；并非 575 PASS，未执行 |
| `node scripts/check-project.mjs`、`node --check`、`git diff --check` | exit 0 |
| Java 编译、聚焦 Java、生产浏览器、完整 JAR | 编译在依赖解析阶段 exit 1；其余未运行，没有 JAR / 服务健康证据 |

原三员复核发现并修正了**测试脚手架**的四个安全/接口问题：A 启动声明缺 ready / revision / 全路径 canonical 检查；B 首写前缺启动声明和实际 fake runtime 核对；B 旧 applicationClassSha256 消费者与启动器完整 runtimeHashes 生产者不匹配；classes 哈希两端的 ASCII escape / UTF-8 编码不同。最后一项统一为 UTF-8 compact JSON，并以 ASCII + 中文合成路径跨 Python / Node 实算一致（独立检查，不是 Spring 测试）。均保留严格 gate，未放宽业务断言。C 的非作者审查见上方入口；旧 aicoding 草稿 CAS 与按钮可访问名差异只得到静态证明，没有运行失败或产品修复声明。

本地测试/文档提交会产生新 HEAD，但生产源码仍等价于 11ca25a3 基线。以后执行必须给启动器 `--expected-revision`、A 的同名参数和 B 的 `BACKEND_INTEGRATION_EXPECTED_REVISION` **相同的实际冻结 HEAD**；不得把声明自身的 revision 当独立可信输入或继续用已过时默认 SHA。

## 分层结果与后续门槛

| 层次 | 本轮实际结果 | 未测 / 不可替代的证据 |
| --- | --- | --- |
| 真 React + 真 Spring + 独立 SQLite + 确定性 fake | 环境依赖构建阻塞；测试脚手架准备中 | Spring health、实际 REST、DTO、幂等、409、SSE重连、真实 POI 解析、业务恢复、取消/停止证明、浏览器像素均未运行 |
| 真 OpenCode + 本地 HTTP mock provider | 未启动、未运行 | 已识别旧脚本版本/可访问名/环境/进程归属差异；不可原样套用，更不是模型通过 |
| 官方 Codex CLI + 一次真实 GPT-6 Luna | **0 次模型调用，未运行** | CLI 0.159.0-alpha.3 与已登录状态、bundled 模型目录是只读证据；不证明账户模型可用或额度。完整禁自动工具机制尚未证实；fake层也未完成 |

不执行旧 `--preflight` 或复制 auth 的资格脚本；不读取 token/认证文件，不创建新登录或持久访问。第一层通过且纯文本无工具路径得到证明后，才允许用户授权的最多一次 `gpt-6-luna` 合成输入烟测；任何认证要求或错误须报告，不更换模型/付费 API/无限重试。

没有推送、PR、merge、部署或 Slack 发送。现有迁移成果、截图和历史证据保留。
