# 七角色 MCP 三批次优化（0.3.67）

本轮把工作包已有的模型友好度优化扩展到拆解、验收闭集、滚动计划、Reviewer、公约和 Judge，保持七类候选各自的业务合同。目标是减少模型需要猜测的字段和隐藏约束，并使合法、可修正的错误在少量提交内收敛。安全冲突、缺少用户决定和冻结事实失效不属于自动修复成功率的分母。

## 三批次交付

| 批次 | 改动 | 验证重点 |
| --- | --- | --- |
| 一：合同同步 | 七类 tool schema 与实际生产约束对齐；明确闭集索引、包替换引用、集合上限、UTF-8 字节与 strip 规则；生产编译错误补实际字节数 | 工具形状、中文长文本、Reviewer 数量、Judge 单行和证据边界 |
| 二：反馈和策略 | 非工作包使用分层反馈；所有内部角色共享冻结预算提示和进展反馈；V71 扩展可选 correction_limit | 安全/人工输入优先、完整替换、重排后问题身份、四次边界、幂等、历史兼容 |
| 三：回归和评测 | 增加真实编译器夹具、升级迁移与角色回归；提供七角色隔离模型探针 | 首投及有限次数接受、主动故障后纠正、BLOCKED 不被改成 PASS、完整 verify/JAR |

实现采用确定性验证反馈循环：先用冻结证据约束候选空间；服务端返回具体反例；模型只修改导致拒绝的部分，再完整替换提交。以规范候选哈希识别重复/振荡，以实体和约束哈希识别未解决问题。没有增加第二个模型来猜测“是否可以通过”，没有改变服务端验收权威。研究背景沿用[前一轮的来源与对照](mcp-design-optimization.md)。

## 各角色同步内容

| 角色 | 模型可见约束 | 保留的权威边界 |
| --- | --- | --- |
| Decomposer | 包数最多 6、依赖为零基前序索引、覆盖目标索引 | 全需求覆盖、纵向业务包和用户输入缺口 |
| Package Designer | 沿用直接 Fact 编译、候选 key 和共享上限 | 冻结输入预检、路径、测试与权限 |
| Acceptance | factIndex 来自冻结事实，capabilityIndexes 是完整允许集合的数组 | 真实同分闭集、完整最优组合、服务端能力选择 |
| Rolling Planner | replaces 指向未完成包，dependencies 只引用保留包或更早候选包 | 冻结前缀、后缀覆盖、依赖和影响计算 |
| Reviewer | 标题 200、摘要 8000、finding 标题 300/detail 4000/path 1024/recommendation 4000 UTF-8 字节；最多 128 findings、32 limitations，每条 limitation 2000 字节 | 全报告 64 KiB、受管路径、精确行号与源哈希；无问题允许空 findings |
| Convention | componentKeys/commandIds 最多 64、pathIds 最多 128；元素分别 256/256/512 UTF-8 字节 | 冻结目录中唯一 ID、组件归属，argv 和真实路径由服务端生成 |
| Judge | role 闭集与冻结角色一致；reason 去首尾空白后最多 4000 UTF-8 字节，单行；引用唯一已知 evidenceIds | 判定来自证据；ACCEPTED 可包含 BLOCKED，不能为格式通过改判 PASS |

字符长度与 UTF-8 字节数独立。工具 schema 用 `x-loopper-maxUtf8Bytes` / `x-loopper-stripBeforeByteCount` 加描述公开字节约束，最终仍由服务端检查；不能假定客户端 JSON Schema 库实现这些扩展。

非工作包的分层实现仍先执行角色策略，保护路径/控制字符/冻结引用等值内安全检查。不可重试结果优先；其余情况下，形状不合格时只返回根因形状反馈，避免同时报告由错误形状推导出的业务错误。它并不跳过角色检查，形状修复后可能出现下一层语义问题。

## 可选有限修正配置

默认所有内部 MCP 角色仍为 `0`（不限提交次数），兼容既有运行。设置 `4` 表示首次提交加最多三次修正；允许 `2–16`。有限预算限制运行次数，不保证任何需求都可自动完成。

| 角色 | 环境变量 |
| --- | --- |
| Package | `LOOPPER_PACKAGE_DESIGN_CORRECTION_LIMIT` |
| Decomposition | `LOOPPER_DECOMPOSITION_PLAN_V2_CORRECTION_LIMIT` |
| Acceptance | `LOOPPER_ACCEPTANCE_CLOSED_CHOICE_V7_CORRECTION_LIMIT` |
| Rolling | `LOOPPER_ROLLING_PACKAGE_PLAN_V1_CORRECTION_LIMIT` |
| Reviewer | `LOOPPER_REVIEWER_REPORT_V1_CORRECTION_LIMIT` |
| Convention | `LOOPPER_PROJECT_CONVENTION_V1_CORRECTION_LIMIT` |
| Judge | `LOOPPER_JUDGE_DECISION_V1_CORRECTION_LIMIT` |

六个扩展角色也可使用 YAML `loopper.internal-candidate.correction-limits.<KIND>`。只在新运行打开时解析配置，随后持久化值是权威；重开不重新套用配置，V71 不回填历史 NULL。Legacy 的原预算保持，不能设置新 correction_limit。V71 按现有 SQLite 重建模式保留原表数据、索引、触发器和外键，并继续冻结预算不可变。

内部非工作包回执新增 `repairProtocolVersion=CANDIDATE_REPAIR_V1`；工作包保持 `PACKAGE_REPAIR_V1`。`repairProgress` 的 resolved/remaining/introduced 按 issue ID 比较，`comparisonComplete=false` 时不能据缺失声称已修复。issue ID 使用 key、packageKey、factIndex 或 Reviewer path+line，减少数组重排误报。候选正文只保留哈希及有界诊断，拒绝内容不作为业务结果保存。

四次预算中最后一投若合法仍优先接受。预算耗尽或最近三投中完整诊断证明内容重复时进入 WAITING_INPUT；幂等重放返回原回执，不收费。角色超时、Provider 传输重试、权限、版本及正向远端停止证明仍是独立边界。

## 真实模型评测方法与边界

使用 `opencode/gpt-5.4`、温度 0、每角色一条正常夹具及一条 shape 故障夹具，每个运行最多四次提交。故障请求仅要求模型首投额外加普通 `note` 字段，再读取真实返回错误修复；不在桥接层代改候选。正常与故障分别统计。Judge 使用已知未解决风险，正确结果为 BLOCKED；Reviewer 使用单行无缺陷源码。

`AllRoleModelProbeTest` 先验证七个正常夹具能被真实生产编译器接受、普通字段错误可拒绝再修复。正式模型运行从固定的编译类副本启动，避免 Maven 重编译替换 classpath。每次独立 XDG 配置/数据/缓存和只读文件夹，只复制已有认证并于结束后删除；保存汇总、回执和哈希，不把认证或模型思考写入仓库。

探针调用生产 schema、诊断阶段和生产编译内核；闭集角色使用真实 contract/resolver 配合合成冻结事实。它使用评测提示而非完整生产角色 prompt，评测桥接预算并非生产数据库调度。因此结果不证明生产 HTTP launch、所有冻结 owner 条件、原子结算、停止握手、活动 JVM 或浏览器。对应生产逻辑由聚焦及完整自动化测试覆盖，不能混称完整端到端资格。

试跑记录独立保留：工作包夹具曾将提示元数据混入冻结需求而造成不可由候选修复的路径冲突，修正后先通过生产夹具测试；另有零提交/连接关闭，均不包装为成功。正式样本较小且没有同任务全角色旧版对照，不能估计生产通过率或声称统计显著提升。

## 正式结果

| 角色 | 正常样本第几投接受 | 故障样本第几投接受 | 结果 |
| --- | --- | --- | --- |
| 拆解 | 1 | 2 | 接受 |
| 验收闭集 | 1 | 2 | 接受 |
| 工作包 | 3 | 2 | 接受 |
| 滚动计划 | 1 | 2 | 接受 |
| Reviewer | 1 | 2 | 接受 |
| 公约 | 1 | 2 | 接受 |
| Judge | 1 | 2 | BLOCKED 保留 |

正常样本 6/7 首投接受，7/7 三次内接受；故障样本 7/7 均真实返回 `/note` 未知字段错误，并在第二投接受。14 次运行均无超时、无零提交，夹具未改动。Judge 两次运行均保留 BLOCKED。工作包正常样本先后修正非法 gap code 与可修复的能力缺口表达，第三投提交 READY 并通过，说明语义表达仍是后续提高首投率的重点。

这些是七个简单合成任务各一次正常/一次故障的小样本结果，不是生产成功率。完整逐投错误、规范结果、候选哈希、耗时与 Provider 令牌数见[机器可读证据](evidence/mcp-role-optimization-20260907.json)。试跑结果也保留在该证据中，未混入正式统计。


## 自动化验证

合并聚焦回归 155 项通过，覆盖角色提示合同、生产编译、分层诊断、字节边界、问题重排身份、预算冻结与幂等、V70→V71 迁移历史及触发器一致性、代码结构门禁。最终交付另运行 `./scripts/verify.sh` 完整测试与打包；准确结果和 JAR 校验值见仓库维护记录。

## 复现

先运行 `AllRoleModelProbeTest` 并把 `target/classes`、`target/test-classes` 复制到独立固定目录，再执行：

```bash
python3 scripts/qualify-all-role-model.py \
  --java "$JAVA_HOME/bin/java" \
  --classpath '<frozen-test-classes>:<frozen-production-classes>:<dependency-jars>' \
  --output '<new-isolated-directory>' --model opencode/gpt-5.4 --repeats 1
```

输出目录中逐次 `attempts.json` 仅用于隔离评测；传输错误可检查 `compiler-stderr.log` 和 `bridge-error.log`。正式业务应用的数据库、运行中 JAR 与用户工作区不参与评测。
