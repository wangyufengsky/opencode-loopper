# Designer 附件上下文规格

本规格实现 [ADR-0001](adr/0001-designer-attachment-context.md)。领域用语以仓库根目录 `CONTEXT.md` 为准。

## 1. 用户行为

1. Designer 起始页和已创建会话的整个工作台都接受文件拖放；拖入时显示全屏/工作台遮罩。
2. 文件进入当前 composer 的暂存 chips；文件选择按钮提供等价入口。
3. 暂存 chip 显示文件名、类型和大小，可以在发送前移除。
4. 发送按钮只有在正文非空、文件批次合法且当前讨论允许输入时可用。
5. 一次发送使用一个 multipart 请求；成功后才清空正文和暂存文件。
6. 历史消息显示附件名称、类型、大小、作用域、SHA-256、提取方式和状态。
7. 文本、图片、PDF 提供安全预览；Office 显示确定性提取摘要。
8. Task 设计历史显示确认时冻结的附件清单；Recovery Task 显示继承来源。

## 2. Module Interface

```java
public interface DesignerAttachmentContext {
    ChangeReceipt change(Change command, List<IncomingFile> files);
    FrozenManifest freezeForTask(FreezeForTask command);
    OpenCodeClient.PromptRequest withContext(ContextUse use, OpenCodeClient.PromptRequest base);
}
```

`Change` 是封闭命令：

- `SubmitAttachmentMessage`：稳定 `submissionId`、Designer Session、Requirement/Work Package 作用域、必填正文、讨论/设计乐观修订和 1–10 个文件；
- `StopFutureUse`：稳定命令 ID、附件 ID、作用域和乐观修订，不携带文件。

`ContextUse` 是封闭消费者：Requirement Designer、Decomposer、Package Designer、Compiler、Implementation、Recovery、Requirement Judge、Risk Judge。类型系统中不提供 Router consumer。

外部 Interface 不出现 Mapper、`MultipartFile`、绝对路径、提取器名称选择、OpenCode JSON map 或截断开关。

## 3. 持久化

Flyway V46 新增：

### `designer_attachment_submission`

- `id`：客户端稳定 submission ID；
- `designer_session_id`、`scope_key`、`work_package_id`；
- `request_sha256`：相同 ID 不同输入返回 409；
- `state`：首版本地提交使用 `PREPARED / PUBLISHED`；其余远端对账状态为后续兼容保留值，不得据此伪造 OpenCode 已接收；
- `old_external_session_id`、`new_external_session_id`、`external_message_id`；
- `error_code`、`error_detail`、`created_at`、`updated_at`、`version`。

### `designer_attachment`

- 不可变身份：`id`、`designer_session_id`、`original_filename`、`detected_media_type`、`size_bytes`、`sha256`、`relative_path`；
- 提取身份：`extractor_id`、`extractor_version`、`extracted_media_type`、`extracted_size_bytes`、`extracted_sha256`、`extracted_relative_path`；
- 历史关系：`designer_message_id`、`submission_id`、`scope_key`、`work_package_id`；
- 当前投影：`state` 为 `ACTIVE / SUPERSEDED / STOPPED / FROZEN`，`superseded_by_attachment_id`、`sent_at`、`stopped_at`、`version`。

在 `designer_session_id + scope_key + original_filename COLLATE BINARY` 上建立只覆盖 `ACTIVE` 的唯一索引。单条 submission 中相同文件名在写库前拒绝。

### `task_design_attachment`

- `id`、`task_id`、`source_designer_attachment_id`；
- 冻结的文件名、作用域、工作包、media type、大小、SHA、受管原文件/提取文件引用和 extractor 版本；
- `source_task_id` 用于 Recovery lineage；
- `frozen_at`；
- `task_id + scope_key + original_filename` 唯一。

Task 确认事务写入该表并把对应 Designer 活动附件更新为 `FROZEN`。Recovery 在创建子 Task 的 lineage 事务中复制父 Task 绑定，不读取 Designer 当前状态。

## 4. 文件存储与格式 Adapter

`DesignerAttachmentStore` 是具体本地模块，生产根目录为 `LOOPPER_DATA_DIR/design-attachments`，测试使用 `@TempDir`。文件 I/O、哈希和提取全部在 SQLite 事务外完成；数据库只在全批准备完成后执行短事务。写入使用同目录临时文件、校验后的 containment 和原子移动，最终文件 owner-only。

内部真实格式 seam：

- `Utf8TextAttachmentAdapter`：文本、源码、JSON、CSV；严格 UTF-8，JSON 额外做确定性语法校验，CSV 作为不执行的纯文本；
- `PdfAttachmentAdapter`：验证 `%PDF-` 并用 PDFBox 提取逐页文本；
- `RasterImageAttachmentAdapter`：PNG/JPEG/GIF/WebP magic 与解码校验；
- `OoxmlAttachmentAdapter`：用 Apache POI 验证 DOCX/XLSX/PPTX 容器并提取段落、表格/单元格、幻灯片文本。

拒绝 ZIP/RAR/7z/gzip、可执行 magic、宏/ActiveX OOXML、旧式 Office、SVG 和未知二进制。HTML 只按严格 UTF-8 源码保存和文本预览，绝不作为浏览器页面执行。所有 adapter 返回统一准备结果，包含检测类型、原始身份、提取身份、预览类型和模型文本字节数。

## 5. OpenCode 协议

扩展 `OpenCodeClient.PromptRequest`，保持现有文本调用兼容：

```java
record FilePart(String filename, String mediaType, URI managedUri, String sha256) {}
record PromptRequest(String text, String system, String agent,
                     ResponseFormat responseFormat, String messageId,
                     List<FilePart> files) {}
```

`HttpOpenCodeClient` 生成一个 text part 加有序 file parts。对 PDF/OOXML，同时发送原文件和确定性提取的 `*.loopper-context.txt`；文本和图片只发送原文件。`FakeOpenCodeClient` 保存完整 `PromptRequest`，使测试通过现有 Seam 观察附件上下文。

每个附件回合在正文前加入固定安全说明和清单，但不把提取正文再次拼入主文本。OpenCode 的 file part 处理可以读取显式附件，即使现有 Session 权限仍拒绝项目 `.env` 和 `external_directory`；权限表不增加任何允许规则。

`HttpOpenCodeClient` 携带由作用域、提示正文和附件身份计算出的稳定 `messageId`。首版以 OpenCode `promptAsync` 的同步接收结果作为本次 handoff 结果；HTTP/Session 失败进入既有 Designer/Package/Reviewer 错误状态，multipart 接口返回失败，使浏览器保留正文与文件。当前版本不宣称已经实现远端消息回读对账，也不写入虚假的 `REMOTE_ACCEPTED`。

浏览器用相同 `submissionId`、正文、作用域和文件重试时，服务端返回既有 Session/用户消息，不重复占用 50 MiB 预算、不重复创建历史或再次写 blob；不同内容复用同一 ID 返回冲突。该幂等识别只确认本地 `PUBLISHED`，不会把既有 OpenCode Session 错误伪装成远端已接收，也不会隐式重复发送可能已被远端接受的 prompt。

## 6. Designer 生命周期

### 初始消息

- 起始 composer 用 multipart 创建 Designer Session、初始正文与附件 submission；
- Router 的冻结 snapshot 只包含正文，绝不包含附件正文、文件名推断或提取内容；
- 附件在本地整批持久化后成为 `ACTIVE`，第一个有资格的 Requirement Designer 回合会随完整正文投递；Router 仍只有正文；
- 全自动模式沿用同一活动附件集合；任何装配、文件完整性或 OpenCode handoff 失败进入既有阻断状态。

### 后续消息

- 无附件消息继续使用现有 JSON 路径；
- 有附件消息使用 multipart `context-turns` 路径；
- 同作用域同名新附件计算逻辑替换；若存在可复用旧 Session，先确认其停止并清空复用指针，再由现有 Designer prompt factory 使用权威持久化需求/决策与新的有效附件创建 Session；
- 停止未来使用使用相同的停止—重建规则；失败时旧附件保持活动。

## 7. REST 与读模型

### Mutation

- `POST /api/designer-sessions/context-turns`：初始 multipart，会同时创建会话；
- `POST /api/designer-sessions/{id}/context-turns`：后续 multipart；
- `POST /api/designer-sessions/{id}/attachments/{attachmentId}/stop-future-use`：本机 UI、乐观修订；
- 所有 mutation 要求 `X-Loopper-Local-UI: 1`，返回稳定错误码，不返回受管路径。

multipart 字段：`metadata` JSON part、重复 `files` part。浏览器不得手写 `Content-Type`，由 `FormData` 自动生成 boundary。

### Read

- Designer messages 增加附件清单；独立附件列表投影活动、替换、停用和冻结状态；
- `GET /api/designer-sessions/{id}/attachments/{attachmentId}/preview` 返回文本/Office 安全摘要；
- `GET /api/designer-sessions/{id}/attachments/{attachmentId}/content` 只服务经过验证的图片/PDF，设置精确 media type、`nosniff`、`no-store` 和受限 CSP；
- Task design-history 增加 `frozenAttachments`，并把每条历史消息的附件关系投影出来。

## 8. Task、Recovery 与 Judge

Task 确认顺序：创建 Task 行 → `freezeForTask` → 现有需求/分包/设计 evidence → Stage 与草稿确认 → 同一事务提交。冻结阶段只校验受管文件存在且 SHA 与已准备身份一致，不重新提取。

`TaskExecutionPromptFactory` 继续负责权威文本提示；调用方再用 `withContext` 装配冻结附件。Implementation 的首次 Attempt、验证失败后的新 Attempt 和 Recovery 新 Session 都使用相同 Task manifest。

双 Judge 获得全局与全部包附件；固定提示说明附件不能覆盖 LoopSpec、deterministic verifier evidence 或 Judge 合同。Machine finalizer 只在同一个 Judge run 中复用相同冻结 manifest。

`RecoveryPersistence` 除现有设计 artifact 外复制 `task_design_attachment` 绑定，并保留 `source_task_id` 与 manifest SHA。

## 9. 前端

- 在 `DesignerView.vue` 根工作台监听 `dragenter/dragover/dragleave/drop`，只接收 `DataTransfer.files`；
- 遮罩明确显示当前 Requirement 或 Work Package 作用域；
- 起始 composer 与聊天 composer 各自拥有暂存 `File[]`，不跨作用域移动；
- 文件按钮使用隐藏的 `<input type="file" multiple>`；
- 客户端先做数量和原始大小提示，服务端仍是权威校验；
- 请求失败不清空正文或 `File[]`；成功 `PUBLISHED` 后一起清空；
- message 附件卡显示精确状态：活动、已替换、已停止、已冻结、投递未知；
- 停止未来使用需要二次确认，并说明不会撤回历史或已发生的远端读取；
- 预览弹窗不执行 HTML/SVG/Office 宏或脚本。

## 10. 自动化验收

### 后端聚焦测试

- 全批原子：初始批次在创建 Session 前完成解析；后续批次在追加用户消息前完成解析，任一文件失败时不产生该回合的新 message/attachment；
- 文件数、20 MiB、50 MiB、UTF-8 上下文预算；
- magic/扩展名不匹配、压缩包、可执行、宏 OOXML 拒绝；
- `.env` 与私钥名称显式上传允许，但项目读取权限仍拒绝；
- 相同作用域＋完全同名替换，跨作用域同名并存；
- 停止/替换前旧 Session abort 未确认时失败关闭；
- Fake OpenCode 收到正文＋完整 file parts；Router 始终只有正文；
- HTTP adapter 序列化 text/file parts 与稳定 message ID；
- Task 冻结、Implementation、Recovery 和两个 Judge 使用正确 manifest；
- 文件缺失、SHA 不符、提取版本缺失时在创建模型 Session 前失败；
- 预览响应头和路径 containment。

### 前端聚焦测试

- 整个工作台 drop 后只出现 chips，不调用 API；
- 文件选择与拖放等价；
- 纯附件不能发送；
- 失败保留正文和全部文件，成功一起清空；
- 当前作用域正确写入 metadata；
- FormData 不手写 multipart Content-Type；
- 历史附件、逻辑替换、停止确认、预览与 Task 冻结 manifest 渲染。

### 完整交付

- 聚焦 Java/Vitest；
- `./scripts/verify.sh`；
- 版本递增并同步所有发布路径；
- 新 JAR 静态资源检查与 SHA-256；
- `git diff --check`、干净范围检查和本地提交；
- 不自动推送、打标签、创建 Release、重启 8080 或宣称浏览器已经加载新资源。

## 11. 实现切片

1. **协议与存储**：V46、Module Interface、格式 adapter、受管 store、OpenCode `PromptRequest` file parts。
2. **Designer turn**：multipart 初始/后续消息、submission 状态、逻辑替换、停止—重建、读模型。
3. **冻结传播**：Task manifest、Implementation/Recovery/Judge、Task 设计历史。
4. **工作台交互**：全局拖放、composer chips、失败保留、历史卡、预览和冻结清单。
5. **交付门禁**：契约文档、AGENTS、聚焦/完整验证、版本/JAR/本地提交。
