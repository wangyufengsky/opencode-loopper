# 产品翻新：信息结构、组件与动效设计门禁

状态：第二阶段规划／设计候选。用户要求的扁平、克制、模块化、极简与易用在此落实为页面结构和交互规则，**不是把 Vue 标签机械改成 JSX，也不是直接套 Ant 默认样式**。React 提供组合和状态投影方式，并不自带某种产品外观。

本报告的旧界面证据来自基线源码；after 图片来自本轮独立 HTML/CSS/JS 交互原型的真实 Chromium 渲染。没有拍摄同一夹具的旧页面对比图，也没有实现生产 React 页面。原型持续标注“设计原型·模拟数据”，不接业务 API；这项证据边界不能在设计审查中省略。完整截图、校验摘要和本地打开方式见[原型查看入口](prototype/README.md)。

## 1. 壳、导航与密度重新整理

旧壳的[侧栏](../../../frontend/src/components/AppSidebar.vue#L26)直接展开 11 个工作区入口与 5 个系统入口；首页又重复大量入口。[首页](../../../frontend/src/views/HomeView.vue#L34)将首屏留给大幅 artwork 与 hero，然后四工作卡和回顾入口。新的结构采用：

- **工作**：首页、任务、需求任务、待处理中心；当前需要处理的事项直接可达。
- **创作与资料**：项目、流程、PPT、知识库、模板任务；不按框架或内部实体分类。
- **管理与设置**：历史设计、质量与用量、运行环境、工具与 Skill、数据库、角色、设置。分组可折叠，命中组内深链时自动展开；不把折叠误解为删除入口。
- 顶栏只留工作区／当前页、皮肤和必要的移动导航入口；运行环境摘要放管理入口及真实异常反馈。禁止首页每块都出现刷新、更多和设置三个工具栏。

普通页面以 224px 侧栏、低装饰分隔线和稳定留白为骨架；画布仍利用完整宽度，进入只显示最小导航、常用视口动作和选中上下文。桌面列表与表单用受控密度；移动导航为有标题和焦点管理的抽屉，内容回到自然单列文档流。主要按钮每个 action region 至多一个，其余按当前状态或菜单披露；**当前问题、权限等待、冲突和恢复阻断必须直接可见**。

高频目标是“看清下一步→执行允许的动作→确认权威结果”。取消／关闭／返回要有明确控件；所有跳转仍服从 `DraftOwner/CommandReceipt`，不能为了流畅动画丢请求身份或卸载 File。页面标题和状态中文表达，不把内部 ID／枚举泄露到普通界面。

## 2. 五类页面 before → after

| 代表页面／旧源码证据 | before 信息结构与操作入口 | after 新结构与入口 | 功能等价保护／新增设计的边界 |
| --- | --- | --- | --- |
| **首页** [HomeView:34](../../../frontend/src/views/HomeView.vue#L34)、[:53](../../../frontend/src/views/HomeView.vue#L53)、[:79](../../../frontend/src/views/HomeView.vue#L79) | 大 hero／背景图 → 四工作区卡 → 回顾与系统入口；新需求与任务在 hero | 简短标题与新需求主动作 → “现在需要你”／最近任务 → 项目和创作入口；不使用大幅装饰图、漂浮辉光 | 原入口全部保留。新的概况是设计候选读取投影，复用现有 task/inbox/project/usage 读接口，不发写命令。分页数据不能冒充全局总数；正式实现无权威汇总时显示明确范围或“未读取”，不能照抄原型 3/2/8。无项目时回到登记／创建引导。 |
| **任务列表** [TasksView:257](../../../frontend/src/views/TasksView.vue#L257)、[:293](../../../frontend/src/views/TasksView.vue#L293) | 状态／类型／项目／归档／排序／分组／搜索及结果表；每行归档／恢复／删除、打开与设计 | 标题＋新需求 → 搜索及常用状态 → “筛选与排序” → 统一结果行；窄屏改卡片行，任务名称、状态、更新时间和更多保持可达 | type/project/archive/order/group 全部存在于筛选抽屉，不改 URL、facets/cursor 或恢复位置。Task/source/document 正确目标不合并；归档、恢复、仅已归档永久删除与冻结设计映射到行菜单。失败任务的恢复入口可直接发现；权限原因不能只有 hover tooltip。 |
| **新建需求表单** [RequirementNew:46](../../../frontend/src/views/WorkflowRequirementNewView.vue#L46)、[:54](../../../frontend/src/views/WorkflowRequirementNewView.vue#L54)、[:67](../../../frontend/src/views/WorkflowRequirementNewView.vue#L67) | 需求内容和工作方式双区；名称、目标、项目、流程；回执错误／创建或原命令重试 | 顺序表单：名称与目标 → 项目与流程 → 版本／保存约定披露 → 固定位置的状态与“进入规划画布”；辅助指导不抢主表单 | 保留真实四字段、actual templateRevision、120 字名称限制、project/template query 和独立保存语义。没有给此入口虚构上传、模型启动或自动执行。已接受但读取失败时显示“打开已创建的需求”，unknown 恢复原身份；动画不决定成功。原型建议的 unknown 离开硬保护仍需 W0 明确行为修正。 |
| **任务详情** [TaskDetail:352](../../../frontend/src/views/TaskDetailView.vue#L352)、[:383](../../../frontend/src/views/TaskDetailView.vue#L383)、[:392](../../../frontend/src/views/TaskDetailView.vue#L392) | header 状态动作＋overview／结果／决策／阶段／输出和若干旁侧面板；许多动作按状态出现 | 标题／项目／权威状态及当前主动作 → 当前阻断 → 全宽阶段 → 问题或 Todo → 输出；摘要、制品、历史在辅助区／披露；窄屏自然单列 | 开始、继续、停止、重试、回答、权限、决策、验收／发布／恢复都由同一 action availability 决定。问题与当前恢复直接可见；历史错误仍可读但不充当当前阻断。阶段内部保留现有 React Flow，原型小阶段条只是示意，不替换实际画布或证明坐标／清理。 |
| **设置** [Settings:119](../../../frontend/src/views/SettingsView.vue#L119)、[:142](../../../frontend/src/views/SettingsView.vue#L142)、[:164](../../../frontend/src/views/SettingsView.vue#L164)、[:183](../../../frontend/src/views/SettingsView.vue#L183) | 已有分组导航及显示／OpenCode／限制／退避／发布／演示；全局保存与独立凭据表单 | 保留任务型分组，统一分组导航与 section；高频配置先露出，高级超时展开；生效范围靠字段组中文标签；保存／恢复置于稳定 action region | 已有分组并非全无设计，保留优点再统一组件层级。原型只详细呈现模型组及限制示例；正式全部字段、凭据独立写入、canvas runtime 偏好、模型精确 ID、端口重启语义和 min/max 仍逐项迁移。AppSettings沿用原PUT，没有CAS version，不追加expectedVersion；409示例放在真实有版本的Task冲突解决稿。不得把原型localhost／示例模型当真实配置。 |

所有 before／after 都是**信息结构与入口**对照，不要求旧 CSS、旧列宽或旧文案逐像素等价。协议、能力与保护等价另有完整清单；不能因截图更简洁就删按钮对应的业务行为。

## 3. 可组合组件层次与单一所有者

```text
ApplicationKernel（唯一 history / theme / accounting / controller registry）
└─ AppShell
   ├─ PrimaryNavigation + ManagementGroup + MobileNavDrawer
   ├─ WorkspaceBar + SkinPicker + SkipLink
   └─ RoutePage
      ├─ PageHeading + ActionRegion（领域允许的一个主要动作）
      ├─ CurrentBlockingNotice / ReceiptNotice / ValidationSummary
      └─ PageBody
         ├─ Home: AttentionList + RecentWork + ProjectSummary + CreationLinks
         ├─ List: QueryBar + FilterDrawer + DataList/Table + CursorFooter
         ├─ Form: FormSection + Field + ChoiceField + DraftActionBar
         ├─ Detail: StageCanvas + Question/Todo + Output + ArtifactSummary
         └─ Settings: SectionNav + FormSection + ActivationScope + AdvancedFields
```

Ant 仅作为 Button/Input/Form/Table/Modal/Drawer 等公开基础能力。产品层 `ActionRegion/ReceiptNotice/CurrentBlockingNotice` 消费不可变 controller snapshot，不在 Ant callback 内另建服务、自动重试或启动订阅。`PageBody` 用明确 typed slots/props 组合，不复制整页 mega-component，也不做能接收任意 REST URL 的万能写表单。

布局、控件高度、层级、状态、图标、留白与行为在一个共享规范；三皮肤在一个 token adapter 表达各自气质。spdb 深蓝／白色商务面、科技蓝低装饰暗面、GitHub 白中性面／绿色动作；结构、焦点顺序、权限、布局和移动断点不随皮肤变。CSS 仅在 adapter／主题配置处理配色，不在各页面新增具体皮肤判断。

界面层的最小状态分工：输入中间态与菜单打开留本地；持久草稿／File／revision／pending 属 controller；服务端投影是权威状态；读取、命令和资源 scope 不属于动效组件。切主题和折叠 section 不重建 owner，不能重置表单或画布。

## 4. 动效规范：每个动效有用途

首轮用 CSS 与既有 React Flow 的公开反馈能力，**不新增通用动效库**。仅确有生命周期协调需求才在后续有测量依据地提出，不预先堆叠 Framer Motion／动画样式包。

| 触发／对象 | 目的与呈现 | 时长与退出／中断规则 | reduced-motion／键盘 |
| --- | --- | --- | --- |
| hover／focus：按钮、行、导航 | 背景／边框／前景微变，不把整个卡片抬起或持续发光；focus 立即明确 | 100–120ms，离开立即反向；不用 delay 才显示可操作 | reduce 为 0ms；focus-visible 静态轮廓始终存在，非颜色唯一提示 |
| Drawer／面板打开 | 使层级与来源清楚；最多 8–12px 位移＋opacity | 160–180ms；焦点 trap 在打开时已成立，不等动画完成才启用操作；close 政策先核 owner | reduce 立即显示／关闭，不位移；Escape／取消／回焦点在两种模式一样 |
| Modal／确认 | 背景区隔及短 opacity 进入；危险默认焦点取消 | 120–160ms；unknown/pending/File 的关闭限制由 owner 决定；不让动画卸载抹草稿 | reduce 无非必要动画；Tab/Shift-Tab 闭环、嵌套弹层与回触发器实测 |
| 操作结果／状态更新 | 合法权威结果的轻量反馈；当前错误保留到处理，成功提醒可短暂显示 | opacity／轻量 4–5px，120–160ms；动作失败或 unknown 不播放成功；消息 timer 属实例资源 | reduce 静态反馈；aria-live polite，关键阻断用 alert，避免每个增量打断读屏 |
| selection／drag | 选中轮廓、合法目标／无效连接提示；实际拖动紧跟指针 | 选中边框最多 100ms；**坐标不 tween，不延后保存／撤销，不给每帧加额外业务 RAF** | reduced-motion 同样精确跟随必要手势；键盘节点定位仍直接可达 |
| 内容切换／高级分组 | 稳定空间和按需披露，避免全页滑动、逐行错峰、auto-height 大范围重排 | 非必要内容 opacity 最多 120ms；初始路由不要求动画；关闭不可卸载活动 draft/File | reduce 即时；focus 不落在 inert/折叠区域，收起前处理其焦点 |
| token 增量／状态摘要 | 复用既有单调累计差值＋xxx，不制造完成百分比 | 只 transform/opacity；首次静默、切 scope 重新基线；中断不改变数值 | reduce 无位移；总量文本仍可读，不重复宣告每个 token |

统一 easing `cubic-bezier(.2,0,0,1)`，不弹簧、不闪烁、不大面积炫光、不循环装饰。动画与命令解耦：写入、收到回执、停止证明、SSE 恢复由协议 owner 决定；不等 transitionend 才处理回执，不在 animationend 重发请求。不阻塞输入；手动滚动输出时不抢位置，主题切换不做整页 crossfade 或 root 重挂载。

原型已真正操作 reduced-motion 模式并读 computed style，证明其 Drawer animation=none/transition=0s；这不是生产 Ant 弹层、React Flow 或每种设备已验收。生产 W1／每波继续检查 layout shift、输入响应和动画叠加，连同 owned timer/RAF 与组件卸载证据；包体和性能预算须同机测量后冻结，不能凭截图下性能结论。

## 5. 把功能等价与新呈现分开验收

| 门槛 | 判定依据 | 不能替代它的证据 |
| --- | --- | --- |
| **业务等价门槛** | route/action/DTO/controller 映射；原 key/File/accepted读取/409/迟到/草稿/权限/停止证明断言；全部 unit/E2E＋新增 | 新样式截图、Ant 的默认能力、React marker、删除旧测试 |
| **新呈现门槛** | 五页三皮肤桌面与窄屏、密度与层级、全部入口可发现、键盘／焦点／错误／unknown/reduced-motion、实际可用原型与最终 React 页 | API mock 返回成功、代码结构扫描、只把旧 Vue 页面改成 React 标签 |
| **资源门槛** | 每个实例活动状态退出首采样的 listener/timer/RAF/capture/observer/subscription/objectURL，无迟到写回／累计；StrictMode 实测 | 截图、DOM 已消失、原型十次 dialog 开关、observer disconnected 被解释成全部 GC |
| **去 Vue 门槛** | clean npm ci／tree／lock／入口／source／fixture 无可执行 Vue，零 `.vue`，31 route 动态验证 | 只有文本扫描、allowlist、把依赖藏入旧夹具、只跑部分路由 |

本轮原型只验信息层级、局部模拟互动和浏览器布局。正式开发必须逐项补真实 React/Ant owner 链，不把 `data-feedback` 弹一个模拟消息当“归档／停止／保存已实现”。原型没有真实上传、SSE、File 回收或后端 parse；没有真实模型调用；没有复现本轮 11 历史失败。高级操作未从生产清单删除，尚未实现的原型入口会明确提示仅供信息架构审查。

## 6. 设计门禁交给项目经理

项目经理可以实际打开原型／图片，按五类页面审核层级、三皮肤气质、操作密度和移动可达性。报告建议按这套方向进入 W0–W1，不再回问用户已明确的普通风格选择；设计未通过时，仅修被退回的设计项。W1 是正式基础 UI 与 controller 原型的下一门槛，届时重新生成 React/Ant 真实截图并验同一行为合同。

尚需项目经理处理的事项来自**合同与恢复边界**而非颜色选择：把多个页面的未知回执／File 离开差异纳入 W0；为未可达 Automations 旧行为明确当前消费者或归档整合范围；若后端仍不可运行，不批准“全部真实功能已验证”。完整风险编号与源码锚点见[流程 B1–B9](workflow-protocol-design.md#9-基线差异静态恢复缺口与-w0-决策)及[核心基线差异](core-ui-inventory.md#现存基线差异和待验证合同)。
