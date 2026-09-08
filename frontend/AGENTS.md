# 前端开发规则

适用于 frontend/ 下的代码与测试；同时遵守根公约。完整 UI 行为见 [设计合同](../docs/design-contract.md)。

- TypeScript 类型以 `frontend/src/types/domain.ts` 为边界，API 变更必须同步 DTO、client、store、view 和测试；Designer 保存/确认必须无损往返 Stage `workPackageId` 以及全部 LoopSpec limits、model、sessionPolicy 和 nextAttemptPromptTemplate。
- 任务详情只为 `PENDING_START` 显示“开始执行”；该状态必须明确尚未入队、占用租约或切换分支。`READY` 是已请求执行后的短暂内部状态，只显示自动继续语义，不得再次显示开始按钮。
- Task 等待动作以服务端 `waitingReasonCode` / `loopRetryAvailable` 投影为准；当前原因必须优先来自最新 Task `WAITING_INPUT` 转换的 `reason_code`，旧数据只能在该转换对应的状态轮次内从元数据或错误兼容推导，前端不得从完整历史错误推断当前“继续一轮”入口。
- 所有 `TASK` 错误事件都作为不可变审计历史保留，但详情页红色当前告警必须跟随权威生命周期：`WAITING_INPUT` 只显示与当前 `waitingReasonCode` 精确匹配的最新错误，失败轮次 `AWAITING_DECISION` 和历史 `FAILED` 只显示最新 Task 错误；进入排队、准备、`READY`、运行、验证、重试、暂停、评审、停止中或成功/取消/接续终态后，不得把旧 Task 错误继续渲染成当前故障。`SOURCE_BRANCH_WORKSPACE_DIRTY` 同样遵守该通用规则。
- `SOURCE_BRANCH_WORKSPACE_DIRTY` 仅在 Task 尚无任务分支和执行目录时可作为当前原因，并打开不可静默关闭的文件处理弹窗，逐文件选择提交、stash 或移除；重新检查成功前不得制造任务分支已创建的状态。弹窗内确认取消必须复用统一 `STOPPING → CANCELLED` 协议；即使旧页面保留了已过期弹窗或列表读取失败，也不得因特殊入口拒绝本来可取消的 Task，且不得修改已有文件、分支或执行目录。
- 服务端是权威状态；不要用计时器伪造阶段进度、用量、成本、Session 完成或 Judge 结果。
- Task 实施 Session 的 OpenCode Todo 只能作为非权威进度投影：卡片以完成数/总数、分段状态和一个当前项为首屏，其他实施项允许折叠且长列表内部有界滚动；桌面端在无待回答问题时必须占用工具栏与模型输出之间的独立布局行，输出在其下方独立滚动，禁止用 sticky/fixed 覆盖输出；有问题时立即回到输出文档流让回答入口优先，窄屏始终按正常文档流展示。不得把 Todo 计数冒充 Stage 百分比或 Stage/Task 完成；键盘焦点和减少动态效果设置必须可用。
- Designer 验收意图卡只把当前失败、待覆盖、路径待归属或路径守恒阻断显示为黄色告警；已成功归属的路径以成功样式显示为当前证明，成功编译后保留的历史消歧原因去重并折叠为中性说明，不得让历史数组继续伪装当前失败，也不得据此绕过服务端 Review Gate。
- 动态 Token 窗口只消费服务端单调累计值；首次值静默建立基线，后续正增量短暂显示 `+xxx`，旧快照不得降低总量或显示负增量，切换 Designer/Task 作用域必须重置本地基线；动画只使用 `transform`/`opacity` 并尊重 `prefers-reduced-motion`。
- 所有等待、问题、权限、可恢复错误和终止错误都必须真实可见，并提供可执行的恢复动作；不要永久显示含糊的“待评审”。
- 使用 `displayLabels.ts` 和现有 `StatusBadge`/错误组件表达中文含义；不要在多个页面复制英文枚举到中文的映射。
- 前端遵循中文优先的极简文案：状态标签或操作已能表达含义时删除重复说明；全自动等模式只保留标签，只有阻断或待决策时显示原因和下一步。
- 普通页面不得直接展示内部枚举/错误码或 Task、Designer Session、Session、Attempt、Draft、Work Package、Criterion 等记录 ID；使用名称、顺序、时间和 `displayLabels.ts` 中文投影。原始值只保留在协议、URL、组件 key、服务端审计和用户主动展开的命令日志中。
- 所有页面错误、消息提示和工具提示必须用中文表达发生原因与下一步；未知英文码使用安全中文兜底，不得把 `XX_XX` 原样回显给用户。
- 项目登记卡片桌面端最多两列，名称、路径、说明、统计和操作均允许换行，窄屏降为单列，禁止 `nowrap` 造成文字和按钮互相挤压。
- Designer 的用户界面统一使用“任务设置”和 `displayLabels.ts` 中文标签，不得用“采用新画像”表达普通确认；任务类型、主要制品等选择控件默认隐藏到“修改设置”之后。REST、SQLite 与选择控件的 `value` 继续使用稳定英文枚举码。
- 界面角色称谓固定为需求分析师、任务规划师、设计师、规范工程师、评审员、验收工程师、开发工程师，以及需求评审员、风险评审员；协议与数据库英文角色码保持稳定。
- 遵循 `docs/design-contract.md` 的 dark-first token、错误层级和桌面优先结构；优先复用 `styles/tokens.css`，不要引入页面私有的另一套视觉系统。
- Markdown 必须经过 DOMPurify；Mermaid 错误必须抑制并清理渲染残留，不允许把原始不可信 HTML 插入 DOM。
- 冲突、代码、JSON 等编辑器优先复用 CodeMirror 组件和现有语言映射。
- 交互写操作要有 loading、错误、幂等/版本冲突处理；破坏性操作必须明确确认。
- Runtime 显式启动和重启都必须携带本地 UI 标识，服务端须在检查进程所有权或执行副作用前验证；LoopSpec 编辑器的数值上限必须与领域 Bean Validation 一致（启动 300 秒、停止 60 秒、单阶段尝试 20 次）。
- 运行环境页的 OpenCode Loopper 版本必须来自服务端 Runtime DTO，不能使用前端 package 版本硬编码，也不能与 OpenCode CLI 版本混为一个字段。
- 运行环境页只展示服务摘要、进程边界和恢复操作；原生能力发现与执行授权仍由服务端持有，但不再渲染独立的能力或授权说明卡片。
- 设置页多列数值表单必须按控件底部对齐，避免一行/两行标签混排时输入框错位；窄屏降列时仍保持自然文档流。
- 行为变化在相邻 `.spec.ts` 中增加或更新回归；纯文案/样式不添加镜像实现的测试，按影响做类型检查或视觉验收。路由级关键流程使用 `frontend/e2e/`。
- Designer 的已回答问题必须按作用域和讨论修订分卡，并固定在对应设计稿之前；确定性校验消息只渲染一个默认收起的汇总卡，展开后保留逐条状态和时间；真正连续的 System 消息必须跨需求版本和需求/工作包作用域元数据合并为一张默认收起、与“需求讨论”同结构的整行折叠条，展开后按持久化顺序展示完整内容；用户/设计师/讨论/校验时间线项仍是分组边界，活动错误横幅不得因此隐藏。
- UI 图标必须使用项目已打包的 Iconify/Lucide 资源，不依赖外网 CDN。
- Spring SPA fallback 必须接住无扩展名的深层前端 history 路由；`/api`、`/actuator`、`/assets` 和带文件扩展名的静态资源路径不得被改写为 `index.html`。


从仓库根目录运行 `./scripts/dev-check.sh frontend src/views/TaskDetailView.spec.ts`。依赖首次安装或 lockfile 变化时先 `npm --prefix frontend ci`；完整交付仍由 Maven 使用固定工具链执行。
