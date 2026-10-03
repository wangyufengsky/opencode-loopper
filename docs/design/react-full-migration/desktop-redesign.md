# 桌面设计修订：主体优先、统一语义与按需披露

**DESIGN-ONLY／模拟内容／生产未实施／仅本地交付。** 本文落实用户最新两项设计决定，覆盖旧设计中的窄屏、主页和常驻详情方案。W0 在本地 `3c848261` 独立冻结，业务失败不因视觉变化消失；[W0 报告](evidence/w0/README.md)与本设计分别审查。项目经理现认可W0取证并批准W1基础工程；W2+尚未放行。桌面原型微调与W1并行，不构成相互阻塞。

## 1. 范围与真实参考

本轮只修订独立原型、语义表和文档；不引入 Ant 候选依赖，不改生产入口、API、store、主题或画布。保留旧多文件原型、单文件及29图作为历史证据。新的[桌面单文件](prototype/desktop-v2/review-single.html)与[实际浏览器图索引](prototype/desktop-v2/README.md)独立存放，支持五类页和三套皮肤。1440×960及1280×900是本轮合理桌面检查尺寸；手机、窄屏、触控设备适配不再是设计或验收要求。[范围排除索引](desktop-scope-exclusions.md)逐项说明旧测试子分支；混合文件中的桌面业务、键盘、105px位移、所有权与资源断言继续保留。

重新读取源码并实际查看原 Cloud 中 Knowledge 默认/引用与第一阶段规划画布的既有真实浏览器模拟图，提取以下结构，而非机械翻译旧 Vue 页面：

| 真实依据 | 可复用结构 | 不应推断的能力 |
| --- | --- | --- |
| [KnowledgeView:30](../../../frontend/src/views/KnowledgeView.vue#L30)、[93–139](../../../frontend/src/views/KnowledgeView.vue#L93) | 来源/引用面板默认关；选中才打开；记录触发器，关闭回焦；读取有scope/sequence | 不宣称所有旧业务离开保护已经正确；源码中的小屏overlay不纳入新要求 |
| [WorkflowCanvasReact:92](../../../frontend/src/react/workflow/WorkflowCanvasReact.tsx#L92)、[105](../../../frontend/src/react/workflow/WorkflowCanvasReact.tsx#L105) | 受控节点/边选择、Enter/Space、背景与Escape取消；大画布为主，相关操作进上下文 | React 本身不自动改善布局；已验收手势/renderer无需重写 |
| [RequirementView:99](../../../frontend/src/views/WorkflowRequirementView.vue#L99) | 切选/取消/关闭先询问当前owner，回到原节点焦点 | 当前owner的canLeave部分存在W0红测，不把参考界面等同可靠协议 |
| [HomeView:10](../../../frontend/src/views/HomeView.vue#L10) | 项目、需求、任务、待处理、知识与系统入口是真实对象 | 旧Home只有静态入口，没有全站最近对象聚合API或全局业务计数 |

组长与非作者均查看上述历史图：Knowledge 默认1440图主区占大部分空间，引用按需展开；规划画布选中节点后出现右侧详情。历史citation测试图的红色观测分隔线不是新视觉规范。新图仅证明新原型的浏览器呈现，不声称是同夹具拍摄的完整生产before/after。

主页信息结构参考[VS Code 官方界面说明](https://code.visualstudio.com/docs/editing/getting-started/userinterface)的主编辑区优先与辅助视图可收起，以及[IntelliJ 官方界面说明](https://www.jetbrains.com/help/idea/guided-tour-around-the-user-interface.html)的近期项目切换及按需工具窗口。这里是对官方结构的设计借鉴，不复制品牌、图标或其业务能力；没有给Loopper增加插件市场、全局命令执行或指标服务。

## 2. 共用壳、层级与组件

桌面壳只保留简短导航、页名、一个主要动作及皮肤选择。导航完整入口由可发现的展开入口提供，键盘可达；低频入口收起不等于删除路由。主要区域承载当前内容，不预留空详情栏、不默认铺满参数、审计、历史和工具卡。选中对象才出现非模态上下文区；高级功能通过具名“更多操作”“详细设置”“历史记录”等入口访问，不依赖hover、右键或图标猜谜。

组件层次：`DesktopShell → PageHeader + SafetyStatus + ContentSurface + UiContextPanel`；主区按页面复用 `RecentList / SelectableList / EditForm / TaskOutput / SettingGroups`；横跨所有页面复用 `SemanticIcon / ActionButton / ConfirmDialog / AdvancedSection / ErrorPanel / StatusBadge`。生产组件API是[语义与动作合同](semantic-ui-contract.md)的设计提案，当前没有新增同名运行组件。

**关键安全状态例外始终可见**：当前error、dirty/未发送修改、pending、unknown、恢复阻断、WAITING_INPUT/待决策。安全区显示原因和可执行下一步；历史错误仍折叠，不能冒充当前故障。收起上下文后，安全区及原身份恢复入口仍在。详情默认隐藏仅控制展示，不销毁领域owner、不stop SSE/命令、不清File，不生成新key。

## 3. 五类页面：旧→新与发现路径

| 页面 | 旧生产/旧候选结构 | 新桌面主画面 | 选中后的详情与高级入口 | 取消后的结果 |
| --- | --- | --- | --- | --- |
| 首页 | 生产大插画hero与多组入口；旧候选摘要/最近/指标等密集分区 | 搜索已读取内容；简短最近项目与历史会话；新建需求、查看任务等少量真实入口；无装饰指标卡 | 选中最近记录显示所属项目、时间和“打开”；展开导航发现全部现行路由 | 主列表恢复宽阔；不将点击预览等同执行/启动任务 |
| 列表 | 筛选、摘要、行按钮及低频动作都容易常驻 | 页名＋一项主动作，少量高频过滤；名称/当前状态为主的稳定行 | 选中行显示项目、时间、许可与具名动作；更多筛选/历史归档显式打开 | 筛选和滚动保留，取消选择不清查询，不自动write |
| 编辑表单 | 正文与工作参数同时铺开；旧原型高级设置卡常驻 | 新建需求的名称、说明为主体；项目/流程当前值摘要；四字段仍完整可达 | 选择工作方式/具体字段按需打开编辑与解释；**没有上传字段** | 普通dirty先确认；pending/unknown四字段禁改且不能卸载owner；原身份恢复在安全区 |
| 任务详情 | 状态、指标、阶段、审计、参数、工具容易争夺主区 | 当前输出/阶段内容为主；当前权威状态与等待问题在主区顶部；一项合法主动作 | 选中阶段/制品打开相关详情，参数/日志/历史/恢复以具名入口披露 | 回到输出，保留滚动与订阅；等待问题/unknown不可隐藏，close不等于取消任务 |
| 设置 | 分组导航、多列输入、说明与高级内容同时常驻 | 紧凑设置组目录；已选组提供必要高频字段，未选时不展开所有值 | 选组才展示字段；选择高级部分展开参数、作用范围与恢复当前基线入口 | 草稿由owner保存；离开/重置确认；unknown阻止切换owner，不能假称原PUT有CAS |

新主页可以使用现有 [api.getProjects](../../../frontend/src/views/KnowledgeHistoryView.vue#L56) 与 [knowledgeApi.history](../../../frontend/src/api/knowledge.ts#L9) 的已读投影。项目按已有更新时间排序；历史会话按真实更新时间/lastActivityAt排序，空/失败各自显示；不会用计时器制造最近活动。搜索原型只过滤本地加载模拟对象，正式实现也必须明确范围。跨库全文检索保留原Knowledge自身search入口，不发明首页全局检索端点。[现有历史页查询与排序](../../../frontend/src/views/KnowledgeHistoryView.vue#L17)

新建需求合同严格按[四字段源码](../../../frontend/src/views/WorkflowRequirementNewView.vue#L54)：名称、说明、项目、流程；创建恢复不是GET receipt，unknown只能用户显式以原body/key幂等POST。详情以[Task当前状态说明](../../../frontend/src/views/TaskDetailView.vue#L134)区分PENDING_START、READY、WAITING_INPUT、STOPPING，不把waiting或关闭当发布/停止证明。

## 4. 全站语义与操作一致性

[中心语义表](semantic-ui-contract.md)为所有现行路由定义业务对象/组件/动作→本地Lucide、中文名、可访问名、行为与合法变体；[机器registry](prototype/desktop-v2/semantic-registry.json)与浏览器registry由同一表导出。相同对象跨页面与三皮肤使用相同key。实例名称是业务数据，可附在固定语义名后；不让业务页自行挑相近图标或同义按钮。图标decorative SVG不进键盘序列，按钮独立有可访问名称，icon-only操作显示提示并可键盘发现。

以下优先级不可被页面分叉：当前owner未确认写/unknown/未安全交接accepted先BLOCK；只有普通dirty/File且能安全放弃才询问；安全读取可关闭展示而不取消业务；delete/任务取消等各自走明确破坏/停止合同。只有权威回执证明成功才清dirty/反馈成功；模拟操作会标“模拟”，不当服务端证据。

`close`收起展示、`back`导航、`cancelEditing`放弃编辑、`task.cancel`请求业务停止是四个不同语义key。必要差异以公共API的`intent/scope/dismissPolicy/availability`公开声明；禁止每页各写Esc/confirm/clear owner。Escape按最上层dialog→当前context→选择处理，文本编辑快捷键不得触发全局delete/save；非模态context不trap普通Tab，确认dialog自身trap且默认焦点取消，关闭回触发器，不存在则回具名主内容。

W1计划加入公共registry单测、真实React共享组件合同测、每路由动作映射以及源码门禁：业务页不得传raw Lucide名、内嵌SVG、emoji或新中英文近义动作，不得自行创建全局键盘/确认/导航策略。扫描只用于限制新增散落实现，不能替代逐动作动态验收；图表/画布renderer自有非UI图形需要按所有权明确区分，不能加隐藏运行代码的零Vue白名单。

## 5. 三皮肤与有用途的动效

一份布局、registry及交互；三套tokens表达已有气质：spdb为白底深蓝操作与克制红色告警，tech-blue为暗表面蓝色主操作，github-white为中性灰与绿色主操作。正文/边框/焦点/状态/disabled单独取语义token，不以皮肤条件改动作、导航、成功事实或对象名称。沿用原已选单一Ant基础库与本地Lucide，没有叠加第二套按钮/表格/dialog库。

| 动效 | 用途与限制 |
| --- | --- |
| context开合 140–180ms | 仅opacity/小幅translate，响应与焦点即时；没有遮住主内容的发光装饰 |
| hover/focus 100–120ms | 轻表面/边框反馈，focus轮廓始终清晰，不依赖动画表示可操作 |
| 选中/取消 | 即时受控边框/背景，重复点击不重复命令；拖拽继续保留已验收画布实例手势 |
| 保存/恢复反馈 | 权威回执后文字+图形；pending/unknown持续状态，不动画伪造进度或成功 |
| reduced-motion | 禁用非必要transition/animation；功能、焦点与恢复路径相同，无延时等动画结束才可用 |

首阶段画布动作、坐标、锁定、新布局、清理与观察器补丁不在本次视觉修订中改写。此原型不模拟完整拖拽/文件上传/真实API；后续正式React+Ant要重新验包体、长日志性能、可访问性和生命周期，不能用本地HTML的流畅替代生产证明。

## 6. 验收、独审与阶段门禁

原三名组员实际复用：workflow独占新原型三文件；legacy独占语义表/registry/范围索引；PPT作为非作者独占browser脚本、截图与review；组长统一before→after、单文件封装及提交。实时协作工具只有任务名称/状态，原显式gpt-6.1-sol/xhigh创建回执见[团队记录](README.md#2-原团队的真实复用与分工)，不冒称工具可读实时模型字段。

最终证据在[浏览器摘要](prototype/desktop-v2/evidence.json)与[非作者审查](desktop-v2-review.md)：只验桌面五页×三皮肤、默认/选中/取消/重复点击、键盘焦点、dirty/unknown负控、语义registry复用、reduced-motion、inline单文件及零外部请求。失败先记录再由作者修，不放宽合同刷绿。既有窄屏图与断言保留，只退出本轮准入。

**项目经理阶段决定**：W0取证已作为完成候选，84项正确合同红测由对应波次修复，停止扩展W0；明确批准W1基础UI/纯TS合同并与普通桌面原型微调并行。此桌面稿仍供像素审查，不阻塞W1基础。W2+全部页面迁移与最终零Vue尚未批准实施，用户普通视觉取舍不再重复询问。本轮没有新的发布授权，所有资料只本地提交。W1实现/测试与W0失败分别记录。
