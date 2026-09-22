# PPT 工作室合同 V1

本合同持有独立 PPT 工作室的页面、角色、编辑、制作和交付语义。它不创建代码 Task、Designer、Stage 或 Judge，不使用模板任务的完成策略。

## 产品流程

入口为 `/ppt` 与 `/ppt/:id`。作品可不关联项目；项目关联仅提供用户明确选定资料的来源，不授予仓库写权限。已有 PPTX 只提取内容后重新制作，不承诺原样导入。

作品阶段为 `BRIEFING → DIRECTION → DESIGN → PRODUCING → REVIEW → EXPORTED`。用户确认方向后进入 DESIGN，确认完整设计并开始制作后进入 PRODUCING；制作结果经过程序检查与模型停止证明后进入 REVIEW。导出成功记录 EXPORTED，后续内容修改回到 REVIEW。需求与方案可重新打开，旧版本和导出保留。

阶段、Agent 运行状态与制作作业状态分别保存。模型最终文字、MCP 成功、预览图片存在均不能替代相应业务完成事实。界面中文称谓为“PPT 助手”。

确认方案并开始制作默认派发 PPT 助手；`useAgent=false` 可进入人工制作，保留相同版本、页面检查和正式导出门禁，用于无需 Provider 的制作路径。整体方向仍需用户明确确认；方向建议通常为 2–3 项，用户自行填写的单项方向也可确认。

工作台包含资料/页面导航、方案/画布、助手/属性三栏。支持单对象选择、拖动、缩放、键盘移动和属性编辑，拖动时显示轮廓，保存后回读预览。选择范围绑定发送时的作品/章节/页面/对象，切换选择不改变已发送请求。三栏可调整宽度，窄屏按需收起；PPT 主题与应用皮肤独立。

设计包含七个可独立编辑的模块：制作目标 `brief`、叙事结构 `narrative`、逐页设计 `slides`、视觉规范 `visualRules` 与 `theme`、素材图表 `assets`、各页讲稿 `slides[].notes`、交付设置 `delivery`。方案编辑自动保存；失败、冲突或投递未知保留浏览器草稿与重试入口。用户确认过的方向在 DESIGN 阶段冻结，修改方向需重新打开需求。导出使用冻结快照中的文件名与讲稿开关；目标办公软件是兼容性目标，不是自动执行远程办公软件。

## 页面与编辑

页面模型采用 point，默认 960 × 540。Deck 含标题、画布、主题和有序 slides；Slide 含稳定 id、title、section、notes、locked 和 elements；Element 含稳定 id、type、x/y/width/height 和类型专属属性。具体可执行形状以 `PptModel` 与工具能力返回为准。

V1 支持文字、PNG/JPEG、矩形/圆角矩形/椭圆/线/箭头、表格、柱/线/饼图、分组和连接线。图表保留原生图表及数据工作簿。图片引用本作品素材 id，不接受任意文件路径或网络 URL。

编辑通过一个原子操作批次提交。批次包含幂等键、预期作品 revision 和 operations；同键同内容返回原结果，同键异内容拒绝。合法性错误整批回滚，排版质量问题允许保存草稿但阻断正式导出。UI 与 MCP 共用编辑实现。Agent 不能解锁用户锁定内容，主题和重排操作也须保持锁定对象。

批量操作支持页面创建/复制/排序/删除、对象创建/修改/删除、分组/层级、对齐/等间距、版式应用与换主题。新对象由调用方提供作品内唯一的稳定 ID；非法或重复 ID 拒绝。页码不作为对象身份。回滚历史创建新 revision，不覆盖旧内容。

模型编辑与人工编辑采用作品 CAS，旧候选不能覆盖用户修改，不自动合并冲突。每作品最多一个活动 Agent writer；停止未知时不能开第二个。冻结渲染/导出继续生成原输入版本，界面必须标明草稿已更新。

## 角色与工具

新角色 `PPT_AGENT` 仅使用受管 OpenCode，默认继承系统模型，按阶段冻结身份/权限/输入。资料与对象按需读取。PPT 专属阶段候选与操作批次独立保存，不扩展现有代码角色候选表的 scope/owner 合同。

工具为 `ppt_get_context`、`ppt_read_source`、`ppt_get_capabilities`、`ppt_request_input`、`ppt_submit_plan`、`ppt_apply_operations`、`ppt_measure_text`、`ppt_check_layout`、`ppt_render_preview`、`ppt_get_job`、`ppt_export`。查询返回当前角色的可执行能力，不以全服务器注册目录替代授权。

每次调用校验作品、run、Session、generation、当前 messageId 与阶段。REVIEW 与 EXPORTED 属于同一后期编辑阶段域：导出完成后原 REVIEW run 可以继续查询作业，已导出内容编辑回到 REVIEW 后原 run 可以继续修改；两者工具权限相同，内容写入仍受 revision CAS 和原会话身份约束。其余阶段严格匹配。角色不获得任意 shell/文件写入/第三方 MCP 通配权限。可修正问题在同一会话反馈具体字段、对象、测量值及修正建议。幂等回执与新操作分开处理，停止后只允许已存在回执精确重放。

创建会话前持久化 creation plan；投递前保存精确 messageId 和请求哈希。创建未知、投递未知分别核对原身份，禁止盲重发。取消等待正向停止证明。模型提交设计只生成候选，用户确认才改变确认状态；模型不能确认自己的设计。

受管进程启动时保存 generation、PID 与操作系统 startInstant 的持久所有权证明，不保存凭据。重启恢复只有在原进程已退出、PID 已换身份，或精确终止原进程并确认退出后才解除旧 writer；缺失或无法验证的证明继续阻断。WAITING_INPUT 与创建/投递未知同样遵守此边界。PPT Agent 不继承代码 Task/Designer 的预算，等待精确消息终态或用户停止，无固定 agentic 步数和工具修正次数上限。

## 渲染、资料与制品

POI 原生 PPTX 与 Java2D 预览使用统一页面模型和字体测量。三种原生图表单独提供 Java2D 预览适配器，数据/颜色/轴与原生图表一致；预览不替代办公软件实测。字体随包提供 Noto Sans CJK SC Regular/Bold 及许可，管理员可用 `--loopper.ppt.font-dir=/absolute/font/directory` 配置附加 TTF/OTF 字体（最多 32 个，单个 32 MiB、总计 128 MiB，不跟随符号链接），重启后生效；不承诺 PPTX 字体嵌入或跨软件像素一致。

资料支持 Markdown、DOCX、XLSX、PPTX、文本 PDF，复用确定性解析器并保留局限。上传单文件最多 20 MiB，每作品至多 10 份资料、总计 50 MiB。PNG/JPEG 素材校验格式、像素和大小。原文件、表示、资源与产物按作品隔离，canonical containment、符号链接和内容哈希验证不可省略。输入正文是数据而非指令。

测量反馈字体、换行、所需高度；检查越界、溢出、资源缺失、未明确允许的重叠、文字对比度与图表标签空间。确定的裁切或不可读文字阻断导出；无法精确推断的覆盖对比度返回警告供预览确认。不自动删文字或无限缩字号。允许覆盖关系与装饰重叠显式表达。预览与文件 I/O 在事务外，完成后复核冻结身份。文件采用原子写入和哈希校验，可恢复结果不能被新作业覆盖。

作品阶段、Agent 与作业经既有生命周期入口审计。输出为原生可编辑 PPTX 和 PNG；V1 不包含 PDF、任意 PPT 原样编辑、SmartArt、动画、音视频和 AI 生图。

## 本地 REST

基础路径 `/api/ppt`，写入口校验 `X-Loopper-Local-UI: 1`。所有版本性写入携带预期 revision，幂等操作携带 idempotencyKey。列表使用标准 `CursorPage`，默认 50/最大 100。

| 接口 | 行为 |
| --- | --- |
| `GET/POST /documents` | 分页查询/创建作品；创建输入 id、title、可选 projectId/model |
| `GET /documents/{id}` | 作品摘要、阶段、revision、模型；作业及助手状态分别按需读取 |
| `GET /documents/{id}/deck?revision=` | 指定版本页面模型，缺省当前 |
| `POST /documents/{id}/operations` | 原子对象操作：idempotencyKey、expectedRevision、operations |
| `GET/POST /documents/{id}/plan` | 读取/保存设计：idempotencyKey、expectedRevision、plan |
| `POST /documents/{id}/actions/{action}` | finish-planning、confirm-direction、start-production、finish-production、reopen、archive、restore；版本和请求键必填 |
| `GET /documents/{id}/revisions` | 版本摘要分页 |
| `GET/POST /documents/{id}/sources` | 资料索引/上传；multipart file、idempotencyKey |
| `GET /documents/{id}/sources/{sourceId}` | 按所属作品读取资料分段 |
| `POST /documents/{id}/assets` | PNG/JPEG 上传；multipart file、idempotencyKey |
| `GET /documents/{id}/assets/{assetId}` | 已验证素材 |
| `POST /documents/{id}/jobs` | kind=PREVIEW/EXPORT、revision、可选 slideId、idempotencyKey |
| `GET /documents/{id}/jobs`、`/jobs/{jobId}` | 作业摘要和结果 |
| `POST /documents/{id}/jobs/{jobId}/cancel`、`/retry` | 停止作业或重试失败页，已完成页面经哈希核验后复用 |
| `GET /documents/{id}/artifacts/{artifactId}` | 作用域与哈希校验后的文件 |
| `GET /documents/{id}/checks?revision=` | 指定版本布局检查 |
| `GET /capabilities` | 字体、主题、版式、操作与环境能力 |
| `GET /documents/{id}/agent` | 当前运行、问题和停止证明 |
| `GET/POST /documents/{id}/messages` | 助手历史/提交请求，绑定范围与 revision |
| `POST /documents/{id}/questions/{questionId}/reply` | 回答精确问题 |
| `POST /documents/{id}/stop` | 请求停止当前 Agent |
| `GET /documents/{id}/events` | SSE 失效事件；连接/重连后 REST 回读 |

摘要与列表不加载资料正文、全量页面或完整聊天；内容按需读取。长作业返回身份与状态，UI 不保持长请求或伪造百分比。

## 验收

真实 10–15 页中文样本覆盖所有元素、三轮局部/布局/主题修改及锁定保护。分开记录单元/集成测试、真实 MCP 模型纠错、PNG、PPTX 重读和本机 WPS 可编辑性。覆盖幂等、冲突、跨作品越权、旧 generation、停止未知、重启和失败页恢复。全新库与旧库升级必须通过；真实 Windows/内网 Provider/PowerPoint 未验证时明确记录，不由本机证据替代。
