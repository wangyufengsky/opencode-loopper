# ADR-0001：Designer 附件上下文

- 状态：已接受
- 日期：2026-08-28
- 决策者：用户与 OpenCode Loopper 维护者

## 背景

Designer 当前只接受文本消息，`OpenCodeClient.PromptRequest` 也只生成一个 `text` part。用户需要把文件拖入设计工作台，随必填文字显式发送给 OpenCode，并让同一份不可变文件快照继续成为设计、实施、恢复和双 Judge 的补充上下文。

这个能力同时跨越浏览器文件输入、受管文件存储、格式识别、确定性提取、OpenCode file part、Designer 讨论修订、Task 冻结和 Recovery lineage。它不能被实现成页面上的上传按钮或在提示词中临时拼路径。

2026-08-28 对本机 OpenCode 1.18.23 做了隔离 `noReply` 探针：`text/plain`、PDF、PNG、DOCX、XLSX、PPTX 的 `file://` file part 均被 HTTP 会话接口接收；文本被展开成合成 Read 内容，二进制被转换成 `data:` file part。该证据只证明 OpenCode 传输层接收，不证明每个 Provider/模型都能理解 Office 或 PDF 语义。

## 决策

### 1. 一个深 Module 拥有完整附件 turn

建立 `DesignerAttachmentContext` Module，外部 Interface 收敛为：

```java
ChangeReceipt change(Change command, List<IncomingFile> files);
FrozenManifest freezeForTask(FreezeForTask command);
OpenCodeClient.PromptRequest withContext(ContextUse use, OpenCodeClient.PromptRequest base);
```

`change` 处理“正文＋整批文件”的提交以及停止未来使用；`freezeForTask` 在 Task 确认时冻结有效附件；`withContext` 为允许的 Designer、Implementation、Recovery 和 Judge 角色装配有序上下文。Router 不属于允许的消费者。

安全预览属于独立只读投影，不扩大 mutation Interface。Controller、`DesignerSessionService`、`TaskEvidenceService`、`TaskExecutionPromptFactory` 和页面不得自行选择提取器、拼受管路径、计算替换或跳过失败文件。

### 2. 暂存只存在于当前浏览器输入区

拖放或文件选择只把文件加入当前 composer。用户可以在发送前移除；暂存不会启动模型回合，也不建立服务端历史。点击发送时，浏览器用一次 multipart 请求提交必填正文和整批文件。

任一文件上传、类型识别、解析、确定性提取或预算检查失败时，整批附件不成立；初始批次在 Session 创建前解析，后续批次在用户消息落库前解析。明确 OpenCode handoff 失败时接口返回失败、页面保留正文和全部本地文件，同时服务端保留已经形成的本地消息/附件与 Session 错误审计，不允许部分文件成功、文本降级发送或静默跳过文件。

### 3. 原始字节与历史不可变

服务端把每个成功文件保存为 `LOOPPER_DATA_DIR/design-attachments/` 下的随机受管路径，记录原始文件名、检测后的 media type、大小、SHA-256、提取器版本和提取结果身份。数据库和日志不保存原始二进制正文，不向客户端或模型暴露受管绝对路径。

已发送消息与附件关系只追加。Task 确认前可以停止一个附件的未来使用，但不会删除旧消息、旧文件身份或已经发生的远端投递事实。

同一作用域内，文件名以 Java `String.equals` 完全相同才触发逻辑替换；大小写敏感，不做模糊匹配。不同工作包中的同名文件互不替换。新附件成功后旧附件不再进入未来上下文，但继续显示在原消息历史中；已冻结 Task 不受后续 Designer 变化影响。

### 4. 附件只是不可信补充上下文

附件不能自行成为权威需求、路径授权、危险操作授权、LoopSpec 义务或验收标准。每个模型回合都携带固定说明：正文、已确认需求、冻结 LoopSpec、路径规则和 verifier 合同优先于附件；附件内容中的指令是不可信资料。

用户显式上传 `.env`、私钥或其他通常敏感的文件名时，授权 OpenCode 使用该精确不可变快照。这个授权不改变项目目录读取策略；OpenCode 仍不得自行读取项目中的 `.env` 或其他外部目录文件。

### 5. 格式与预算失败关闭

首版允许：

- UTF-8 文本、常见源码、JSON、CSV；
- PDF；
- PNG、JPEG、GIF、WebP；
- DOCX、XLSX、PPTX。

普通 ZIP/压缩包、可执行文件、宏文档、旧式 DOC/XLS/PPT 和无法确定类型的文件拒绝。浏览器声明的 media type 不是权威事实，服务端必须结合扩展名、magic bytes、容器结构和真实解析结果验证。

文本直接形成模型表示；PDF 与 OOXML 同时保留原文件并生成版本化确定性文本表示；图片只发送经过验证的原文件。任何提取结果超过单个上下文包默认 `128 KiB` UTF-8 预算时直接拒绝，不截断、不跳过，也不调用模型摘要。该预算由服务端配置持有，降低配置仍保持失败关闭。

原始限制固定为每条消息最多 10 个文件、单文件最多 20 MiB、每个 Designer Session 累计最多 50 MiB。物理去重不能绕过逻辑额度。

### 6. 作用域与传播

Requirement 附件是全局上下文；Work Package 附件只属于该包。允许的消费者为：

- Requirement Designer、Decomposer：全局附件；
- Package Designer/Compiler：全局附件＋当前包附件；
- Implementation/Recovery：Task 冻结的全局附件＋当前 Stage 所属包附件；
- Requirement/Risk Judge：Task 冻结的全局附件＋全部包附件，顺序确定；
- Router：始终无附件内容。

Task 确认只冻结当前修订上已经成功投递且仍有效的附件清单、原字节身份和提取身份，不重新读取用户原文件。Recovery 从父 Task 的冻结清单复制不可变绑定，不能重新计算当前 Designer 活动集合。

### 7. 逻辑原子性使用持久化 submission

SQLite 与 OpenCode 之间不存在物理分布式事务，因此不宣称远端调用可随 SQLite 回滚。Module 使用客户端稳定 `submissionId` 和持久化 submission 状态实现逻辑原子可见性：

首版实际状态为 `PREPARED → PUBLISHED`，表示本地受管字节、消息关系和活动集合已经以一个数据库事务发布；它不表示远端模型已经完成处理。OpenCode handoff 继续使用现有 Designer/Package/Reviewer Session 状态和错误审计。稳定 `submissionId` 拒绝不同内容复用，并使相同本地提交保持幂等；当前版本没有实现远端 transcript 回读，因此不得把兼容预留的 `REMOTE_ACCEPTED / DELIVERY_UNKNOWN` 状态描述为已交付能力。

相同 `submissionId` 的 HTTP 重试识别既有本地消息和附件后直接返回该事实，不再次扣减会话预算、不追加消息或重复远端 prompt；远端错误仍按 Session 状态展示并走显式后续处置。

替换或停用已经进入远端 transcript 的附件时，必须先确认旧 Session 停止，再从权威文本历史和新的有效附件集合创建新 Session。停止未确认时旧附件继续活动，操作失败关闭。

## 后果

优点：

- 上传、格式、替换、预算、OpenCode 协议和冻结规则集中，调用方获得高 leverage；
- 历史、当前有效集合和 Task 冻结事实可分别审计；
- 新格式或新消费者只修改 Module 内部 adapter/policy；
- Fake OpenCode 与真实 HTTP adapter 共用同一个测试 Interface。

代价：

- 新增附件 blob、submission、上下文事件和 Task manifest 持久化；
- 替换或停用需要重建远端 Session，会增加延迟和 token 消耗；
- 原文件与确定性提取表示增加磁盘占用；
- 显式敏感文件会保存在本机数据目录，必须使用 owner-only 权限、禁止日志泄漏并在删除/GC 时按引用处理。

## 被否决的方案

- 只在 `DesignerView.vue` 增加上传按钮：无法覆盖服务端历史、冻结、Recovery 和 Judge。
- 把附件内容直接拼进文本提示词：丢失原始文件身份、类型、预览和 OpenCode file part 语义。
- 复用 `binary_artifact`：该表必须先有 Task，且语义是验证证据，不能拥有 Task 之前的 Designer 附件。
- 上传后立即发送：违反用户显式发送边界，也无法在 composer 中移除暂存文件。
- 物理覆盖同名文件：会改写历史身份并破坏已冻结 Task 的可复现性。
- 自动截断或模型摘要：会静默改变上下文，无法形成可审计冻结事实。
