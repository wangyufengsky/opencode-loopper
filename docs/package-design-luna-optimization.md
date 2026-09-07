# 工作包设计三批优化与 Luna 订阅评测

## 固定目标和证据边界

目标是让可解决的复杂工作包在四次候选内正确通过，并区分候选表达、仓库未知事实、待建验证、业务待决、已证实冲突和仍未确认的问题。四次只约束离线评测，不改变生产纠错配置。新能力未通过配对质量门前保持关闭。

模型固定为 Codex CLI `gpt-5.6-luna`、`medium`、ChatGPT 订阅登录。禁止 API Key、其他模型、购买额度或自动重置回退。当前探针使用 CLI 0.153.4；配对评测必须记录并保持同一版本。

Codex 离线证据只说明模型对生产合同、Schema、编译器和修复回执的适应性。Loopper → OpenCode 的拥有者、权限、持久化、重启恢复和停止确认另行验收。测试、构建 JAR、活动 JVM 和浏览器资源也是独立证据。

## 第一批：冻结证据和缺口判定基础

`PackageDesignGapAssessment` 是无 I/O 的分类组件。模型的缺口枚举只是声明；只有可信来源适配器提供的证据才能形成业务决定或冲突结论。没有证据的缺口保持 `UNCONFIRMED`，允许澄清依据，不能自动接受，也不能指示模型编造答案。已证实的安全/范围失败保持原有阻断。

`LOOPPER_PACKAGE_DESIGN_EVIDENCE_ENABLED` 默认 `false`。打开后只为新工作包候选运行冻结 `PACKAGE_DESIGN_V1_EVIDENCE_V1` workflow step，候选对象仍使用 V1。历史 workflow step 不变，配置切换不会改写进行中的运行。此阶段尚不提供 V2 的结构化缺口声明；因此不能把模型补充的自由文本自动升级成已确认业务决定。

V72 增加不可变 `package_design_evidence`，不回填历史。证据在模型投递前准备：最多 16 个冻结范围内的精确文件、单文件 4 KiB、总正文 16 KiB；记录需求来源、范围、完整已读内容的 SHA-256 和读取状态。通配、符号链接、超限、读取失败、非 UTF-8、文件缺失均记录“未确认”或具体边界状态，不能推导能力不存在。候选策略只读数据库冻结内容并核对哈希，不读取文件、网络或模型。原始需求沿用已有不可变需求修订。

新诊断沿用 `CANDIDATE_DIAGNOSTIC_V2` 的字段指针、稳定问题标识、expected/actual/repairHint、UTF-8 限额、完整对象重提、幂等与版本检查。只有 `/gapCodes/N` 中的已识别模型声明参与重分类，其他确定性错误不会被代码名称相同的声明覆盖。

## 离线评测方法

固定语料位于 `src/test/resources/package-design-luna/corpus.json`：36 例中 24 个可解决、6 个真实业务待决、6 个权限/约束冲突，按需求族分为训练 18、开发 6、独立验收 12。当前语料重点是测试工作包设计，包含 Java、Node 和 Python 的条件组合、异常、状态、幂等、补偿、跨阶段不变量及待建测试；结果不能推广为所有生产工作包的通过率。

`PackageDesignLunaProbe` 直接装配生产 Prompt，读取生产 MCP Schema；`PackageDesignLunaSession` 调用生产 `PersistentMachineCandidateSubmission`、策略、编译器、稳定修复进展及 accepted writer。它用测试替身提供内存存储和冻结拥有者/角色上下文，不构成数据库事务或生产会话生命周期证明。评测桥接器只计算四次完整候选，精确幂等重放不重复计算，接受、重复候选停滞或非修复终止后停止接收新候选；正常 REJECTED 回执与生产一致，不标为 MCP 传输错误。脚本不替模型修改候选。遗漏修复进展的早期探针只保留为适配器诊断记录，不纳入正式基线。

每个运行使用私有 Codex 配置目录，仅临时复制已有订阅登录信息，目录权限 0700、凭据文件 0600，完成后删除凭据；不继承 API Key、个人插件、记忆、技能发现或历史任务身份。只开放只读编译 MCP 工具，禁用 shell、写工具相关能力、浏览器和其他应用，使用 read-only sandbox。Code Mode 宿主须保留以便 Codex 调用 MCP。评测目录和合成仓库内容在调用前后核对，结果保存 Prompt/配置/语料哈希、候选、真实回执和编译结果。

运行准备：

```bash
./mvnw test-compile dependency:build-classpath -Dmdep.outputFile=/tmp/loopper-eval-classpath.txt
python3 scripts/qualify-package-design-luna.py --codex /path/to/codex \
  --preflight --output /tmp/loopper-luna-preflight
python3 scripts/qualify-package-design-luna.py --codex /path/to/codex \
  --java /path/to/jdk21/bin/java \
  --classpath "target/test-classes:target/classes:$(cat /tmp/loopper-eval-classpath.txt)" \
  --splits train,dev --output /tmp/loopper-luna-development
```

Windows 的 classpath 分隔符使用 `;`。基线应先复制旧生产 classes 与依赖到隔离目录，避免后续 `clean verify` 改写基线。配对时仅切换生产实现/固定 Prompt 版本，保持运行器、语料、预算和归一化配置相同；独立验收每配置重复三次。

接受状态只代表编译成功。必须逐份按独立 `semanticChecklist` 核对需求、每个适用分支、不变量和范围，记录错误放行、遗漏、修复引入的新错误及重复错误；未审查的结果标为 `PENDING_INDEPENDENT_CHECKLIST`。真实待决和冲突单独统计，不计入设计成功。

## GEPA 的请求预算边界

当前适配器只确认 `codex exec --json` 提供回合汇总 Token，实际模型请求数标记为不可得，不用会话数或工具次数替代。官方配置中的 HTTP 重试上限控制单次请求的重试，rollout budget 控制 Token，都不等于跨优化、反馈、验证和重试的 200 次实际请求总预算。尚未验证可在每次模型请求发起前预留额度的接口，因此 `--gepa` 在适配器验证阶段失败关闭，启动模型请求数为 0。

参考：[官方配置参考](https://learn.chatgpt.com/docs/config-file/config-reference)、[App Server 事件](https://learn.chatgpt.com/docs/app-server)、[非交互模式](https://learn.chatgpt.com/docs/non-interactive-mode)。这项限制不允许通过购买额度、重置、改用 API 或把 200 个会话包装成 200 次请求绕过。

## 后续两批的验收要求

第二批实现版本化 V2 来源/关系/缺口声明、32 节点及 4 层关系限制、每修订最多一次语义整理，并同步工具、角色、前端和恢复链。原始需求、历史 V1 和旧 Role Pack 保持；StageSpec 和权限仍由服务端生成。

第三批执行相同 Luna 配置的配对独立验收：可解决复杂案例四投正确率至少 90%，且比基线提高至少 10 个百分点；若基线已达 90%，正确率不得下降，并使误报缺口或平均实际请求数至少下降 10%。任何安全错误放行、必需语义遗漏或历史回归都不能通过。若实际请求数不可得，不得使用该指标声称改进达标。未过门限保持关闭，GEPA 只有通过预算可控性且带来额外收益才采用。
