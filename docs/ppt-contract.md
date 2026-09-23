# PPT 工作室合同 V3

本合同持有独立 PPT 工作室的页面、角色、编辑、制作和交付语义。它不创建代码 Task、Designer、Stage 或 Judge，不使用模板任务的完成策略。

## 产品流程

入口为 `/ppt` 与 `/ppt/:id`。作品可不关联项目；新建入口提供可搜索、分页的可选项目选择，选择即开放该项目创建时全部可用知识库来源，不授予仓库写权限。已有 PPTX 只提取内容后重新制作，不承诺原样导入。

默认入口采用先讨论、后执行的流程：输入一句需求，可选项目和附件，创建作品并发送第一条消息。不先要求命名、选模型、填写方案、挑方向或操作画布。名称根据需求初始化，模型沿用系统设置。用户和助手可以持续自由对话；用户可以回答助手，也可以主动补充、修正或追加要求。每轮助手都收到此前完整的已保存对话和本轮消息。讨论阶段不创建生成授权，服务端拒绝方案、页面、预览、导出和问题表单写入。用户准备好后点击“确认需求并执行”，服务端核对最新讨论回合及作品版本，将完整对话冻结到生成请求，再创建授权、自动制作、预览和导出。草稿消息尚未发送、助手正在回复或仍有待回答问题时不能确认。确认后的制作不再要求重复进行初始需求确认。

旧 REST `/generate` 与升级前冻结的 run 继续兼容原先的工具问答和 `REQUIREMENTS_CONFIRMATION` 流程；它们不会自动转成新讨论协议。

作品阶段仍为 `BRIEFING → DIRECTION → DESIGN → PRODUCING → REVIEW → EXPORTED`。初次讨论不创建生成授权。用户明确确认后创建一个持久流程授权，该授权包含方案设计、选择方向、逐页制作、检查、PNG 预览与可编辑 PPTX 导出。后台收到完整且合法的方案候选、并取得对应模型运行的正向停止证明后，代用户执行既有阶段动作；模型不能通过自身回复绕过校验。确认后用户无需再点击方向、方案、制作或导出的重复确认。制作结果通过检查与停止证明后进入 REVIEW，导出成功记录 EXPORTED；后续修改保留旧版本与文件。

阶段、自动生成授权、Agent 运行状态与制作作业状态分别保存。模型最终文字、MCP 成功、预览图片存在均不能替代相应业务完成事实。界面中文称谓为“PPT 助手”。

PPT 助手与知识库共用活动展示组件：思考、工具调用各自默认收起，折叠条展示最新摘要，展开后保留本轮思考及最近 30 条调用，流式更新不重置展开状态。没有真实思考或正文、也没有执行中的工具时显示动态等待；提问等待、停止及终态不显示等待动画。只展示 Provider 返回的思考，不生成隐藏推理。V122 追加独立活动快照，按精确 run/messageId 与当前版本保存，思考累计最多 64000 字符并提示截断，工具不暴露原始参数、结果及作用域凭证。跨提问续接、刷新和重启保留已采集记录；采集失败不改业务状态、不清空已有内容，历史缺失不回填。

助手面向用户的回复简短说明内容与设计结果，避免把工具参数、内部标识和检查日志当作操作指引；自动流程不再要求用户另行确认预览或导出。稳定角色提示只承载共同边界，当前 run 冻结的工作流提示决定自动与历史人工流程，二者不得同时下发互相冲突的阶段要求。

历史人工方案接口继续兼容；没有自动生成授权的旧会话遵守原阶段权限，不会因升级自动执行。升级前已有成稿在用户发送新的修改意见时，创建独立自动修改授权并更新预览与下载；旧请求的精确重放不创建新授权。旧接口确认方案并开始制作默认派发 PPT 助手，`useAgent=false` 保留无需 Provider 的人工制作能力。此路径不是默认入口，不再以方向选择和七项方案编辑引导新用户。

生成前和生成中，工作台呈现实际进度、助手消息与必要问题；不展示空画布工具、技术配置或大量手工表单。生成后以整套预览、页面导航及修改意见为主。用户反复发送“简洁一点”“第三页增加结论”等意见即可修改，默认范围是整份；选择页或对象后明确显示范围，并可回到整份。每次修改完成后更新预览与可下载版本，旧制品保持可访问。

“手动编辑”在有生成结果后由用户选择展开，支持单对象选择、拖动、缩放、键盘移动和属性编辑；拖动时显示轮廓，保存后回读预览。选择范围绑定发送时的作品/章节/页面/对象，切换选择不改变已发送请求。资料、历史和详细方案为次级入口；窄屏按需收起，使用现有应用主题语义变量，PPT 主题与应用皮肤独立。

设计包含七个可独立编辑的模块：制作目标 `brief`、叙事结构 `narrative`、逐页设计 `slides`、视觉规范 `visualRules` 与 `theme`、素材图表 `assets`、各页讲稿 `slides[].notes`、交付设置 `delivery`。方案编辑自动保存；失败、冲突或投递未知保留浏览器草稿与重试入口。用户确认过的方向在 DESIGN 阶段冻结，修改方向需重新打开需求。导出使用冻结快照中的文件名与讲稿开关；目标办公软件是兼容性目标，不是自动执行远程办公软件。

## 页面与编辑

页面模型采用 point，默认 960 × 540。Deck 含标题、画布、主题和有序 slides；Slide 含稳定 id、title、section、notes、locked 和 elements；Element 含稳定 id、type、x/y/width/height 和类型专属属性。具体可执行形状以 `PptModel` 与工具能力返回为准。

V1 支持文字、PNG/JPEG、矩形/圆角矩形/椭圆/线/箭头、表格、柱/线/饼图、分组和连接线。图表保留原生图表及数据工作簿。图片引用本作品素材 id，不接受任意文件路径或网络 URL。

编辑通过一个原子操作批次提交。批次包含幂等键、预期作品 revision 和 operations；同键同内容返回原结果，同键异内容拒绝。合法性错误整批回滚，排版质量问题允许保存草稿但阻断正式导出。UI 与 MCP 共用编辑实现。Agent 不能解锁用户锁定内容，主题和重排操作也须保持锁定对象。

批量操作支持页面创建/复制/排序/删除、对象创建/修改/删除、分组/层级、对齐/等间距、版式应用与换主题。新对象由调用方提供作品内唯一的稳定 ID；非法或重复 ID 拒绝。页码不作为对象身份。回滚历史创建新 revision，不覆盖旧内容。

模型编辑与人工编辑采用作品 CAS，旧候选不能覆盖用户修改，不自动合并冲突。自动生成或修改尚未终结时，手工内容、方案和阶段编辑须先停止，服务端在写入事务中复核；界面也暂时禁用手动编辑。停止未知仍不能手改或开第二个 Agent writer。无自动授权的历史人工会话保留原 CAS 编辑行为。冻结渲染/导出继续生成原输入版本，界面必须标明草稿已更新。

首次完成制作要求原计划逐页兑现；成稿后用户明确删除页面，后续导出按当前冻结 deck 验收，不改写历史计划或要求被删除页面重新出现。空稿、无对象的空页、无效素材及阻断性布局问题仍不能正式导出；作品重新打开也不改变已创建作业的快照校验方式。

## 角色与工具

新角色 `PPT_AGENT` 仅使用受管 OpenCode，默认继承系统模型，按阶段冻结身份/权限/输入。资料与对象按需读取。PPT 专属阶段候选与操作批次独立保存，不扩展现有代码角色候选表的 scope/owner 合同。

制作工具为 `ppt_get_context`、`ppt_read_source`、`ppt_get_capabilities`、`ppt_request_input`、`ppt_submit_plan`、`ppt_apply_operations`、`ppt_measure_text`、`ppt_check_layout`、`ppt_render_preview`、`ppt_get_job`、`ppt_export`。查询返回当前角色的可执行能力，不以全服务器注册目录替代授权。

项目读取工具另有 `ppt_list_knowledge_sources`、`ppt_search_project_knowledge`、`ppt_browse_knowledge_source`、`ppt_read_knowledge_source`、`ppt_query_knowledge_database`、`ppt_inspect_knowledge_database` 与 `ppt_read_knowledge_git`。它们复用知识库的检索/文件/Git/数据库能力，但采用 PPT 自己的作品/run/Session/generation/message 权限与证据，不能冒用知识问答会话；不开放其他项目、任意 shell 或第三方 MCP 通配权限。

新建作品在事务外解析并冻结项目可用来源，短事务同时保存作品与来源快照（V124）。来源包括项目代码、项目文档及文档目录、额外登记目录、上传文件、Git 和该项目绑定的可用数据库；最多 100 项，超限明确提示整理，不静默截断。不可用来源显示原因但不授予读取权限。未选项目及升级前只有 projectId 而无来源快照的作品不自动得到权限；项目配置后续变化不扩展既有快照。模型上下文仅含项目名称和来源索引，不包含数据库凭据引用或完整连接配置。

统一搜索保留覆盖状态和分页，并把后续读取指引适配成当前 PPT 工具；无命中不证明事实不存在。实际原文、Git 和数据库读取保存不可变证据，绑定作品、run、message、source、SHA、正文及采集时间；迟到结果经当前权限复核，不能写入证据。每 run 最多保存 100 条，超过后仍可读取，但明确没有新的证据 ID。方案页面 sourceIds 仅接受本作品已上传资料或已保存的 knowledge evidenceId；其他作品证据拒绝，讲稿标注名称、位置和采集时间。历史证据读取保存正文，不用当前文件替换。

旧自动 CREATE/PLANNING run 冻结 `requirementsProtocol=DIALOGUE_CONFIRMATION_V1`。`ppt_request_input.kind` 默认为 CLARIFICATION；REQUIREMENTS_CONFIRMATION 的 prompt 是需求摘要。V123 保存问题类型及独立 confirmed 决定，普通文字回答不等于确认。该历史流程的用户确认通过 reply 携带 `confirmed=true`；补充或拒绝为 false。V125 起，新讨论入口在生成授权上持久化 `requirementsConfirmed=true`，完整讨论文本保存在该授权的冻结 prompt 中；服务端依据该字段跳过旧问题卡，并继续执行方案门禁。旧授权的默认值为 false，仍按原协议继续；成稿 REVISE 不重复初次沟通。

自动授权的预览与导出由程序在模型安全结束后统一创建、绑定和恢复，自动 run 不可另外调用 `ppt_render_preview` 或 `ppt_export` 新建输出作业；历史人工 run 保留原权限。停止后已有回执的精确重放规则不变。

每次调用校验作品、run、Session、generation、当前 messageId 与阶段。REVIEW 与 EXPORTED 属于同一后期编辑阶段域：导出完成后原 REVIEW run 可以继续查询作业，已导出内容编辑回到 REVIEW 后原 run 可以继续修改；两者工具权限相同，内容写入仍受 revision CAS 和原会话身份约束。其余阶段严格匹配。角色不获得任意 shell/文件写入/第三方 MCP 通配权限。可修正问题在同一会话反馈具体字段、对象、测量值及修正建议。幂等回执与新操作分开处理，停止后只允许已存在回执精确重放。

多轮问答更新当前 message 的调用凭证。活动 run 若误用同 run、同 Session、当前 generation 的已登记旧轮次凭证，操作仍不执行，返回 `PPT_SCOPE_EXPIRED`，提示从本轮消息末尾的工具身份通知读取身份后重交；错误不返回新凭证。随机、跨作用域、未来轮次或已退役 generation 的凭证继续拒绝并等待恢复。身份通知仅在出站时作为合成文字追加，Loopper 保存的业务请求不含凭证；精确恢复只对受管 PPT 会话验证并移除与当前身份完全匹配的通知，其余正文和附件仍严格匹配，不能通用忽略合成消息。

创建会话前持久化 creation plan；投递前保存精确 messageId 和请求哈希。创建未知、投递未知分别核对原身份，禁止盲重发。取消等待正向停止证明。模型提交设计只生成候选，服务端依据明确的一键生成授权或旧路径的人工确认推进；自动权限冻结在对应 run 的 context 中，不接受 HTTP 调用者伪造，也不扩大原有 MCP 权限。

## 自动生成与恢复

每次授权单独记录在 `ppt_generation`，与模型 run、作业及不可变作品快照绑定。步骤为 PLANNING、PRODUCING、PREVIEW、EXPORT；WAITING_INPUT、STOPPING、STOPPED、FAILED 与 COMPLETED 分别表达等待、停止过程和终态。每作品至多一个非终态授权，问题等待和停止未知仍占用。生成完成仅指绑定版本的全部预览及正式 PPTX 均成功，不能以模型退出或首张预览替代。

请求键和内容哈希在 `ppt_generation_request` 保存；同键同内容重放原授权，同键异内容拒绝。派发身份、步骤和预期 revision 先持久化，再请求模型；阶段中途重启继续未完成动作，不新开重叠 writer。自动授权进行时拒绝手工写入，停止后用户编辑形成新版本；恢复需用户显式基于当前 revision 提交，旧请求不自动换版本覆盖。回答问题沿用精确 run/question 的回复协议，随后自动续接。

停止首先撤销后续派发，再请求活动模型或作业停止；未证实停止不能恢复或开始新授权。显式恢复使用新请求键和当前 revision，保留成功页面和已有制品；模型步骤与渲染步骤分别恢复，渲染或导出失败不重做设计。页面离开、刷新、SSE 断线不影响后台续接，重连按服务端状态恢复展示。旧手工会话不会被自动纳入新授权。

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
| `GET /projects` | 搜索与分页选择受管项目，仅返回名称与摘要，不探测 Git |
| `GET /documents/{id}/knowledge` | 查看本作品关联项目及冻结来源 |
| `GET /documents/{id}/knowledge/evidence/{evidenceId}` | 按作品归属读取已保存证据 |
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
| `POST /documents/{id}/generate` | 一键生成：idempotencyKey、expectedRevision、prompt；返回持久授权状态 |
| `POST /documents/{id}/generate/confirm` | 确认已保存的完整讨论并执行：idempotencyKey、expectedRevision；不接受浏览器传入的 transcript |
| `GET /documents/{id}/generation` | 当前/最近一次自动生成状态；无授权返回 null |
| `POST /documents/{id}/generate/resume` | 恢复停止或失败的授权：idempotencyKey、expectedRevision |
| `GET/POST /documents/{id}/messages` | 助手历史/提交请求，绑定范围与 revision |
| `POST /documents/{id}/questions/{questionId}/reply` | 回答精确问题；需求确认由 confirmed 布尔显式提交 |
| `POST /documents/{id}/stop` | 先撤销自动续接，再请求停止当前 Agent/生成作业 |
| `GET /documents/{id}/events` | SSE 失效事件；连接/重连后 REST 回读 |

摘要与列表不加载资料正文、全量页面或完整聊天；内容按需读取。长作业返回身份与状态，UI 不保持长请求或伪造百分比。

## 验收

真实 10–15 页中文样本覆盖所有元素、三轮局部/布局/主题修改及锁定保护。分开记录单元/集成测试、真实 MCP 模型纠错、PNG、PPTX 重读和本机 WPS 可编辑性。覆盖幂等、冲突、跨作品越权、旧 generation、停止未知、重启和失败页恢复。全新库与旧库升级必须通过；真实 Windows/内网 Provider/PowerPoint 未验证时明确记录，不由本机证据替代。

V3 另验收：从新入口开始后可多轮自然讨论，既可回应助手也可主动追加要求；后续模型提示包含先前对话；讨论阶段所有制作写工具被服务端拒绝，且不创建生成授权；仅点击“确认需求并执行”后冻结服务端对话快照并启动制作，不能从客户端伪造确认内容；初次确认不再出现第二张重复确认卡。旧 `/generate` 与已冻结 V1 run 仍按旧问题确认合同恢复。项目检索、读取、证据与跨项目拒绝须通过真实 MCP 通道验证；连续修改意见生成新预览与版本；默认不暴露人工编辑工具，用户展开后仍可编辑；停止发生在阶段交接时不能触发下一次派发；预览/导出失败只恢复失败步骤；旧作品和人工 API 保持可用。真实模型从初次需求到输出的证据与模拟 Provider 分开记录，浏览器实际检查宽屏、窄屏和应用主题。
