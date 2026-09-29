import type {
  ArtifactKind,
  DesignerTaskProfile,
  TaskIntent,
  TaskSessionActivityPart,
  TaskSessionSummary,
} from '@/types/domain'

type WorkflowTemplate = DesignerTaskProfile['workflowTemplate']
type ExecutionStrategy = DesignerTaskProfile['executionStrategy']
type TestPolicy = DesignerTaskProfile['testPolicy']

const statusLabels: Record<string, string> = {
  PENDING_START: '待开始', QUEUED: '排队中', PREPARING: '准备中', READY: '待执行', RUNNING: '运行中', VERIFYING: '验证中',
  PACKAGE_DESIGNING: '设计下一包', PLANNED: '已规划', DESIGN_REVIEW: '设计待确认', EXECUTION_READY: '待开始执行',
  CHECKPOINTING: '冻结事实中', FACT_FROZEN: '事实已冻结',
  RETRY_WAIT: '等待重试', RETRY: '重试中', PAUSED: '已暂停', WAITING_INPUT: '等待输入', JUDGING: '评审中',
  AWAITING_DECISION: '等待处置', COMPLETED: '已确认完成', SUPERSEDED: '已由新任务接续',
  SUCCEEDED: '历史成功', FAILED: '历史失败', CANCELLED: '已取消', ONLINE: '在线', OFFLINE: '离线',
  STARTING: '启动中', INCOMPATIBLE: '不兼容', PASS: '通过', FAIL: '未通过', PENDING: '待处理',
  CREATING: '创建中', BUSY: '处理中', ABORTED: '已中止', TIMED_OUT: '已超时',
  DISCONNECTED: '已断开', SESSION_ERROR: '会话错误', TASK_ERROR: '任务错误', VERIFIED: '已验证',
  VERIFIER_FAILED: '验证未通过', BLOCKED: '已阻塞', UNKNOWN: '未知',
  REVISE: '需修改', UNPARSEABLE: '无法解析', IDLE: '空闲', DONE: '已完成', CHECKING: '检查中',
  PERSISTED: '已保存', PENDING_HANDOFF: '等待交接', DELIVERED: '已送达', RECONNECTING: '重连中',
  NORMALIZED: '已自动规范化',
  DRAFTING: '编辑中', DRAFT_READY: '待确认', CONFIRMED: '已确认', HANDOFF_FAILED: '交接失败',
  STOPPING: '正在停止', NEEDS_CONFIRMATION: '待确认', FROZEN: '已冻结',
  QUESTIONING: '提问中', APPROVED: '已接受', REVIEWING: '待确认', STALE: '已失效',
  ENABLED: '已启用', DISABLED: '已停用', ACTIVE: '进行中', ARCHIVED: '已归档',
  REVIEW_REQUIRED: '待人工确认', AUTO_START: '自动开始', DETECTED: '已检测', SKIPPED: '已跳过',
  ADMITTED: '已准入', FINISHED: '已结束', HELD: '占用中', RELEASE_PENDING: '待释放', RELEASED: '已释放',
  NOT_REQUIRED: '无需租约', AVAILABLE: '可用', UNAVAILABLE: '不可用',
  NONE: '暂无', SYNCING: '同步中', SYNCED: '已同步', DESIGN_INCOMPLETE: '设计不完整',
  IN_PROGRESS: '进行中', HIGH: '高', MEDIUM: '中', LOW: '低',
}

const generalLabels: Record<string, string> = {
  TEMPLATE_REPORT: '模板报告', TEMPLATE_BATCH: '报告分析批次', ISOLATED_REPORT: '独立报告目录',
  QUESTION: '问题', PERMISSION: '权限', MANUAL: '手动触发', CRON: '定时触发',
  GIT_HEAD_CHANGED: 'Git 版本变化', WEBHOOK: '回调触发',
  REVIEW_REQUIRED: '人工确认', AUTO_START: '自动开始',
  ROUTER: '需求分析师', DECOMPOSER: '任务规划师', DESIGNER: '设计师',
  COMPILER: '规范工程师', REVIEWER: '评审员', VALIDATOR: '验收工程师', SYSTEM: '系统', USER: '你',
  FIELD: '输入问题', VERIFICATION: '验收问题', SESSION: '会话问题', TASK: '任务问题',
  IMPLEMENTATION: '开发工程师', JUDGE: '评审员', REQUIREMENT: '需求评审员', RISK: '风险评审员',
  WORKTREE: 'Git 分支模式', DIRECT: '直接模式',
  READ_ONLY: '只读', WRITE_FILES: '写入文件', WRITE_CODE: '修改代码', SAFE_LOCAL_MAINTENANCE: '安全本地维护',
  AUTO_RECOMMENDED: '全自动推荐', USER_OVERRIDE: '人工覆盖', MANUAL_OVERRIDE: '人工覆盖',
  PERSISTED: '已保存', PENDING_HANDOFF: '等待交接', COMPILED: '已编译', RETRYABLE_ERROR: '可重试错误', TERMINAL_ERROR: '已停止',
  PLANNING: '规划中', SERVER_COMPILING: '服务端编译', GENERATING_JSON: '生成结构', REPAIRING_JSON: '修正结构', FINAL_JSON: '结构完成',
  ROUTING: '识别任务', DISCUSSING_REQUIREMENT: '讨论需求', DECOMPOSING: '拆解任务', VALIDATING_DECOMPOSITION: '校验拆解',
  DESIGNING: '设计中', COMPILING: '编译中', VALIDATING: '校验中', REDESIGNING: '重新设计', QUESTIONING_PACKAGE: '工作包提问',
  REVIEWING_PACKAGE: '工作包待确认', AGGREGATING: '聚合中', FINAL_REVIEW: '最终确认', GENERATING_REPORT: '生成报告',
  VALIDATING_REPORT: '校验报告', REPORT_READY: '报告已就绪',
  DIRECT_DESIGN: '直接设计', DECOMPOSED: '已分包', NEEDS_INPUT: '需要补充信息', MULTI_TASK_REQUIRED: '需要拆分任务',
  PROCESS: '命令验收', FILE_EXISTS: '文件存在检查', FILE_NOT_EXISTS: '文件不存在检查', GIT_DIFF: '变更范围检查',
  HTTP_STATUS: 'HTTP 状态检查', JSON_PATH: 'JSON 内容检查', FILE_CONTENT: '文件内容检查', FILE_HASH: '文件完整性检查',
  JUNIT_XML: '测试报告检查', BROWSER: '浏览器验收', DATABASE_QUERY: '数据库检查', DOCUMENT_STRUCTURE: '文档结构检查', TABULAR_DATA: '表格数据检查',
  MACHINE: '机器验收', BOTH: '机器与 AI 验收',
  MCP_ACCEPTED: 'MCP 候选已接受', MARKDOWN_FALLBACK: 'Markdown 兜底',
  FEATURE_DISABLED: '功能未启用', MCP_NOT_READY_PRE_DISPATCH: 'MCP 启动前未就绪',
  MODEL_COMPLETED_WITHOUT_SUBMISSION: '模型结束但未提交候选',
  MODEL_COMPLETED_AFTER_MECHANICAL_REJECTION: '模型修正后结束但未提交可接受候选',
  MECHANICAL_REJECTIONS_EXHAUSTED: '机械修正次数已用完',
  PACKAGE_DESIGN_CANDIDATE_NOT_SCHEDULED: '当前工作包未调度 MCP 候选',
  FROM_FAILED_STAGE: '从失败阶段继续', ALL_STAGES: '重做全部阶段', VERIFY_ONLY: '只读复核', REWORK_ALL_STAGES: '重新实施全部阶段', INHERIT_CHANGES: '继承已有变更',
}

const errorCodeLabels: Record<string, string> = {
  GIT_CREDENTIAL_REQUIRED: '请配置 Git 账号',
  GIT_CREDENTIAL_HOST_MISMATCH: 'Git 账号与服务器不匹配',
  GIT_CREDENTIAL_UNAVAILABLE: 'Git 凭据无法安全保存或读取',
  GIT_CREDENTIAL_VERSION_CONFLICT: 'Git 账号设置已变化，请重新加载',
  GIT_CREDENTIAL_URL_INVALID: 'Git 服务器地址无效',
  GIT_CREDENTIAL_MODE_INVALID: 'Git 账号来源无效',
  GIT_CREDENTIAL_USERNAME_INVALID: 'Git 用户名无效',
  GIT_CREDENTIAL_KIND_INVALID: 'Git 认证方式无效',
  GIT_CREDENTIAL_SECRET_INVALID: 'Git 密码或令牌格式无效',
  GIT_CREDENTIAL_SECRET_REQUIRED: '请重新输入 Git 密码或令牌',
  GIT_CREDENTIAL_TEST_URL_REQUIRED: '请填写完整验证仓库地址',
  GIT_PROJECT_OUTSIDE_CHANGES: '同一 Git 仓库的项目目录外存在变更，请先在对应模块处理后重试；本任务不会处理这些文件。',
  GIT_PROJECT_SCOPE_MISMATCH: '项目目录与所属 Git 仓库不匹配，请检查登记目录和 Git 配置。',
  GIT_PROJECT_SCOPE_UNAVAILABLE: '无法确认项目所属的 Git 仓库，请检查目录及访问权限。',
  WORKFLOW_WRITEBACK_ACTIVE: '流程成果正在回填，请在对应需求任务中查看或恢复。',
  WORKFLOW_WRITEBACK_CHANGED: '回填来源、目录或记录已变化，请刷新并检查原回填记录。',
  WORKFLOW_WRITEBACK_EXISTS: '这项需求已有确认的回填，请查看或恢复原记录。',
  WORKSPACE_OVERLAPPING_LEASE: '同一仓库仍有旧目录任务占用，请待该任务安全结束后重试。',
  STAGE_WORKSPACE_BASELINE_CREATE_FAILED: '阶段文件基线创建失败',
  STAGE_WORKSPACE_BASELINE_UNSTABLE: '阶段文件基线采集期间文件持续变化',
  PACKAGE_COMPILED_SCOPE_EXPANSION: '设计路径需要修正',
  PACKAGE_DESIGN_SECURITY_BOUNDARY: '候选包含服务端专属字段',
  PACKAGE_DESIGN_NEEDS_INPUT: '设计已暂停，请查看具体原因',
  PACKAGE_COMPILED_SCOPE_VERIFIER_MISSING: '系统编译结果缺少范围验收',
  PACKAGE_COMPILED_FORBIDDEN_SCOPE_LOST: '系统编译结果丢失禁止路径',
  PACKAGE_COMPILED_DELETE_PERMISSION: '系统编译结果丢失禁止删除约束',
  PACKAGE_COMPILED_SCOPE_EMPTY: '系统编译结果缺少可写范围',
  PACKAGE_SCOPE_PROOF_UNAVAILABLE: '无法完成路径规则校验',
  DECOMPOSITION_BOUNDARY_UNPROVEN: '拆包边界需要补充依据',
  ATTEMPT_LIMIT_EXHAUSTED: '已用完尝试次数',
  JAVA_UNIT_TEST_ACCEPTANCE_REQUIRED: '缺少 Java 聚焦单元测试验收',
  JAVA_CHANGE_CLASSIFICATION_MISMATCH: 'Java 变更与任务声明不一致',
  VERIFICATION_FAILED: '验收未通过', PROCESS_FAILED: '命令执行未通过',
  JUDGE_CONFLICT: '需求评审与风险评审结论不一致',
  SOURCE_BRANCH_WORKSPACE_DIRTY: '项目目录存在未处理的本地变更',
  TASK_ARCHIVE_WORKSPACE_LEASE_ACTIVE: '项目仍被活动任务占用，暂时无法归档',
  LOOP_STAGNATION_DETECTED: '连续多轮没有有效进展', LOOP_FRESH_SESSION_REQUIRED: '需要新会话才能继续',
  DESIGN_QUESTION_REQUIRED: '设计前需要先回答问题', DISCUSSION_SCOPE_REQUIRED: '请先选择要修改的讨论范围',
  MULTI_TASK_REQUIRED: '需要将需求拆成多个独立任务', TASK_PROFILE_DECISION_REQUIRED: '需要确认任务类型',
  LARGE_TASK_MODE_REQUIRED: '当前需求无法安全容纳在普通单包中',
  LARGE_TASK_MODE_NOT_APPLICABLE: '大型任务模式只适用于软件任务',
  REQUIREMENT_SNAPSHOT_TOO_LARGE: '需求快照超过 24 KiB，请新建设计并提交精简后的完整需求',
  REQUIREMENT_SEGMENT_UNCOVERED: '部分需求尚未纳入工作包',
  DECOMPOSER_PLAN_OUTPUT_MARKERS_MISSING: '任务拆解结果格式不完整',
  COMPILER_PLAN_OUTPUT_MARKERS_MISSING: '规范编译结果格式不完整',
  COMPILER_PLAN_JAVA_TEST_EVIDENCE_REQUIRED: '缺少 Java 聚焦测试证据', COMPILER_RETRY_EXHAUSTED: '规范编译已用完重试次数',
  DECOMPOSITION_CONTEXT_INVALID: '任务拆解上下文已损坏', JUDGE_PROMPT_BUDGET_EXCEEDED: '评审内容超出允许上限',
  VERIFIER_RUNTIME_TERMINATION_UNCONFIRMED: '无法确认验收进程已停止',
  DIRECT_WORKSPACE_FINGERPRINT_MISMATCH: '项目目录身份与登记时不一致',
  DESIGNER_SESSION_NOT_FOUND: '设计会话不存在或已结束',
  OPENCODE_DESIGNER_HANDOFF_FAILED: '设计请求未能发送给 OpenCode',
  OPENCODE_PROMPT_FAILED: 'OpenCode 未能接收请求',
  OPENCODE_DESIGNER_UNAVAILABLE: 'OpenCode 当前不可用',
  OPENCODE_STEP_LIMIT_REACHED: 'OpenCode 达到步数上限，本轮未产生有效结果',
  WRAPPER_TOLERATED: '已兼容常见外层格式', AI_OUTPUT_NORMALIZED: '输出已自动规范化',
}

const errorRecoveryMessages: Record<string, string> = {
  STAGE_WORKSPACE_BASELINE_CREATE_FAILED: '阶段开始前的文件基线创建失败，尚未创建执行会话。请检查项目文件读取权限、数据目录写入权限及 Git 状态后重做。',
  STAGE_WORKSPACE_BASELINE_UNSTABLE: '阶段开始前的文件基线无法稳定保存。请等待其他程序完成文件写入后重做。',
  ATTACHMENT_MCP_REQUIRED: '附件需要受管 OpenCode 的私有 MCP 连接，请检查运行环境后重新开始设计。',
  ATTACHMENT_MCP_NOT_READ: 'OpenCode 未完整读取并确认附件，设计已阻断。请检查私有 MCP 连接后重试，无需清理项目文件。',
  ATTACHMENT_MCP_CONTENT_UNVERIFIED: 'OpenCode 保存的附件内容不完整或已变化，设计已阻断。请检查版本兼容性后重新发送附件。',
  ATTACHMENT_MCP_TOO_LARGE: '附件资源超过限制：文本最多 128 KiB，图片或 PDF 二进制最多 10 MiB。请缩小或拆分文件。',
  ATTACHMENT_MCP_HASH_MISMATCH: '附件内容与已保存的校验值不一致，请重新上传文件；历史记录保持不变。',
  ATTACHMENT_MCP_CAPACITY: '当前附件资源容量已满，请结束不用的设计会话或减少附件后重试。',
  ATTACHMENT_MCP_RESOURCE_UNAVAILABLE: '附件资源已过期或撤销，请重新发起设计以获取新的资源。',
  ATTACHMENT_MCP_MEDIA_UNSUPPORTED: '此附件无法作为 MCP 资源读取，Office 文件必须先生成有效的文本表示。',
  ATTACHMENT_MCP_READ_FAILED: '附件快照无法读取或不是有效 UTF-8 文本，请检查并重新上传文件。',
  ATTACHMENT_MCP_EMPTY: '附件没有可供读取的内容，请检查文件后重新上传。',
  OPENCODE_DESIGNER_HANDOFF_FAILED: '设计请求未能发送给 OpenCode，请检查运行环境中的版本兼容性与连接状态后重试',
  OPENCODE_PROMPT_FAILED: 'OpenCode 未能接收请求，请检查运行环境中的版本兼容性与连接状态后重试',
  OPENCODE_DESIGNER_UNAVAILABLE: 'OpenCode 当前不可用，请检查运行环境中的连接状态后重试',
  OPENCODE_STEP_LIMIT_REACHED: 'OpenCode 达到步数上限，本轮未产生有效结果。请确认运行环境已更新后重新发起本轮设计或评审。',
}

const systemErrorFallbacks: Record<string, string> = {
  FIELD: '输入有误，请检查填写内容后重试',
  VERIFICATION: '验收未通过，请查看验收证据后处理',
  SESSION: '会话出现错误，请检查运行环境后重试',
  TASK: '任务出现错误，请查看任务详情后处理',
}

const tokenLabels: Record<string, string> = {
  ATTEMPT: '尝试', LIMIT: '上限', EXHAUSTED: '已用完', REQUIRED: '缺少必要条件', FAILED: '失败', ERROR: '错误',
  JAVA: 'Java', UNIT: '单元', TEST: '测试', ACCEPTANCE: '验收', SESSION: '会话', TASK: '任务', PROFILE: '类型', DECISION: '确认',
  PENDING: '待处理', WAITING: '等待', INPUT: '输入', OUTPUT: '输出', FORMAT: '格式', INVALID: '无效', MISSING: '缺失',
  COMPILER: '规范编译', DECOMPOSER: '任务拆解', DESIGNER: '设计', REVIEWER: '评审', JUDGE: '评审', VERIFICATION: '验收',
  RETRY: '重试', PLAN: '规划', CONTRACT: '契约', WORKSPACE: '项目目录', LEASE: '占用权', SOURCE: '源项目', BRANCH: '分支', DIRTY: '有未处理变更',
  NOT: '不', FOUND: '存在', AVAILABLE: '可用', UNAVAILABLE: '不可用', UNKNOWN: '未知', CONFLICT: '冲突', BLOCKED: '已阻断',
  TIMEOUT: '超时', TIMED: '超时', DISCONNECTED: '连接已断开', STAGNATION: '无进展', DETECTED: '已检测',
}

const toolLabels: Record<string, string> = {
  read: '读取文件', write: '写入文件', edit: '编辑文件', bash: '终端命令', apply_patch: '应用补丁',
  glob: '匹配文件', grep: '搜索内容', list: '列出文件', task: '子任务',
}

const taskIntentLabels: Record<TaskIntent, string> = {
  SOFTWARE_CHANGE: '软件变更',
  DOCUMENT_AUTHORING: '文档编写',
  DATA_CONVERSION: '数据转换',
  READ_ONLY_REVIEW: '只读评审',
  RESEARCH: '调研',
  CONFIGURATION: '配置修改',
  LOCAL_MAINTENANCE: '本地维护',
  LEGACY_SOFTWARE: '历史软件任务',
}

const artifactKindLabels: Record<ArtifactKind, string> = {
  SOURCE_CODE: '源代码',
  PYTHON_SCRIPT: 'Python 脚本',
  MARKDOWN: 'Markdown 文档',
  DOCX: 'Word 文档（DOCX）',
  XLSX: 'Excel 工作簿（XLSX）',
  CSV: 'CSV 表格',
  TSV: 'TSV 表格',
  CONFIGURATION: '配置文件',
  ANALYSIS_REPORT: '分析报告',
  OTHER: '其他制品',
}

const workflowTemplateLabels: Record<WorkflowTemplate, string> = {
  DIRECT_SOFTWARE_DESIGN: '默认单包设计',
  FULL_PACKAGE_DESIGN: '完整分包设计',
  DIRECT_ARTIFACT: '直接制品',
  PACKAGED_ARTIFACT: '分包制品',
  READ_ONLY_REPORT: '只读报告',
  LOCAL_MAINTENANCE: '本地维护',
}

const executionStrategyLabels: Record<ExecutionStrategy, string> = {
  OPEN_CODE_IMPLEMENTATION: 'OpenCode 实施',
  SERVER_DOCUMENT_MATERIALIZATION: '服务端生成文档',
  SERVER_TABULAR_CONVERSION: '服务端转换表格',
  READ_ONLY_REPORT: '只读报告',
}

const testPolicyLabels: Record<TestPolicy, string> = {
  REQUIRED: '必须测试',
  OPTIONAL: '可选测试',
  NOT_APPLICABLE: '不适用',
}

const profileResolutionLabels: Record<string, string> = {
  AI_ROUTER: 'AI 路由',
  ROUTER_FALLBACK: '路由降级',
  AUTO_RECOMMENDED: '全自动推荐',
  USER_OVERRIDE: '人工覆盖',
  USER_CONFIRMED: '人工确认',
  USER_CONFIRMED_CARRIED_FORWARD: '已沿用人工确认',
  USER_SELECTION_PENDING: '等待人工选择',
  LEGACY: '历史兼容',
}

export function statusLabel(value?: string) {
  if (!value) return '未知'
  return displayLabel(value)
}

export function workflowStateLabel(value?: string): string {
  return ({ PLANNING: '规划中', PENDING_START: '待开始', PENDING: '未开始', ACTIVE: '执行中', RUNNING: '执行中',
    PAUSED: '已暂停', STALLED: '流程停滞', WAITING: '等待确认', WAITING_INPUT: '等待人工填写',
    SUCCEEDED: '已完成', COMPLETED: '已完成', FAILED: '执行失败', DONE: '已完成',
    DISPATCHING: '确认消息送达' } as Record<string, string>)[value || ''] ?? statusLabel(value)
}
export function workflowCandidateStateLabel(value: string) { return ({ PENDING: '待确认', APPLIED: '已应用', REJECTED: '已退回' } as Record<string, string>)[value] || '未知' }
export function workflowWritebackReason(value?: string | null) { return ({
  WORKFLOW_WRITEBACK_PREPARATION_CHANGED: '原目录已变化或备份不可用，未开始回填；请检查目录后重试。',
  WORKFLOW_WRITEBACK_APPLY_FAILED: '回填暂未完成，已保留现场、备份和写入权；请检查目录后重试。',
  WORKFLOW_WRITEBACK_LOCK_UNAVAILABLE: '暂时无法确认回填写入者，已保留现场和写入权；请检查目录及权限后重试。',
} as Record<string, string>)[value || ''] || '回填未完成，现场和写入权已保留，请检查目录后恢复。' }
export function workflowWritebackStateLabel(value: string) { return ({ CONFIRMED: '已确认回填', APPLYING: '正在回填原目录', BLOCKED: '回填需处理', APPLIED: '已回填原目录' } as Record<string, string>)[value] || '状态待刷新' }
export function workflowPublicationStateLabel(value: string) { return ({ CONFIRMED: '正在保存本地提交', BLOCKED: '提交需处理', COMMITTED: '已保存本地提交' } as Record<string, string>)[value] || '状态待刷新' }
export function workflowPushStateLabel(value: string) { return ({ PREPARING: '准备推送', RUNNING: '正在核对推送', BLOCKED: '推送需处理', PUSHED: '已确认推送' } as Record<string, string>)[value] || '状态待刷新' }
export function workflowPushReason(value?: string | null) { return ({
  WORKFLOW_PUSH_TARGET_CHANGED: '项目的推送地址已变化。请恢复确认时的远端配置，再核对原推送。',
  WORKFLOW_PUSH_REF_CONFLICT: '远端成果分支已有不同提交，原分支已保留。请核对远端内容，恢复操作不会覆盖它。',
  WORKFLOW_PUSH_STOP_UNCONFIRMED: '原推送进程的停止尚未得到证明。重新核对仍使用原执行身份，不启动第二次推送。',
  WORKFLOW_PUSH_SOURCE_CHANGED: '本地成果分支或仓库归属已变化。请恢复确认的成果分支和原仓库后重试。',
  TEMPLATE_GIT_AUTH_FAILED: 'Git 认证失败。请检查项目或全局 Git 账号；SSH 地址请检查系统 SSH 配置，然后核对原推送。',
  TEMPLATE_GIT_REMOTE_UNAVAILABLE: 'Git 网络连接失败。请恢复连接后核对原推送。',
  WORKFLOW_PUSH_RESULT_UNCONFIRMED: '远端结果尚未确认。请检查连接后核对原推送；已存在的相同提交会直接登记。',
} as Record<string, string>)[value || ''] || '推送记录需要继续核对。请检查原仓库、远端连接和账号后恢复原操作。' }
export function workflowPublicationReason(value?: string | null) { return ({
  WORKFLOW_PUBLICATION_PROJECT_UNAVAILABLE: '原项目目录不可用或身份已变化。请恢复原目录并检查访问权限，再恢复原提交。',
  WORKFLOW_PUBLICATION_SOURCE_UNAVAILABLE: '保存的代码、基线或 Git 对象缺失或不一致。请恢复原始保存数据，再恢复原提交。',
  WORKFLOW_PUBLICATION_COMMIT_UNCONFIRMED: '本地提交对象尚未确认。请检查 Git 仓库及写入权限，再恢复原提交。',
  WORKFLOW_PUBLICATION_BRANCH_UNCONFIRMED: '成果分支尚未确认，可能存在其他提交或引用冲突。请核对该分支；恢复时只接受原定提交，不覆盖其他内容。',
  WORKFLOW_PUBLICATION_RECEIPT_UNCONFIRMED: '成果分支已创建，但提交回执保存失败。恢复原提交将核对已有分支并补记结果。',
} as Record<string, string>)[value || ''] || '原提交需要检查。请确认原项目目录、保存的代码和成果分支可用，再恢复同一提交。' }
export function workflowFinishLabel(value: string): string {
  return ({ COMPLETED: '人工认定成功', FAILED: '人工认定失败', CANCELLED: '用户取消' } as Record<string, string>)[value] || '人工结束'
}
export function workflowTestCoverageLabel(value: string) {
  return ({ COVERED: '评审认为已覆盖', MISSING: '缺少对应测试', INSUFFICIENT: '断言覆盖不足', FAILED: '关联测试失败', NOT_EXECUTED: '关联测试未执行', EVIDENCE_INCOMPLETE: '固定版本证据不足' } as Record<string, string>)[value] || '证据无法识别'
}
export function workflowReasonLabel(value?: string | null): string {
  if (!value) return ''
  return ({ WORKFLOW_NOT_STARTED: '确认计划后可开始执行。', WORKFLOW_CHECKPOINT: '请检查节点交付物并确认后继续。',
    WORKFLOW_SNAPSHOT_PLAN_INVALID: '版本分批配置不完整，请检查分析、条件复核、报告及固定输入。',
    WORKFLOW_SNAPSHOT_PLAN_LIMIT: '完整版本分批超过容量，请明确缩小范围；资料已保留，没有截断代码。',
    WORKFLOW_SNAPSHOT_REPORT_INVALID: '版本报告缺少分析或适用复核，或绑定了不同版本，请检查完整范围和完成策略。',
    WORKFLOW_HISTORY_PLAN_INVALID: '历史分批配置不完整，请按分批、审查、可选的贡献评价、报告顺序连接并检查输入。',
    WORKFLOW_HISTORY_PLAN_LIMIT: '完整历史分批超过容量，请按明确日期范围拆分需求；资料已保留，未截断提交或人员。',
    WORKFLOW_HISTORY_REPORT_INVALID: '历史报告尚未覆盖全部批次或人员，或绑定了不同版本的评价依据，请检查输入后重试。',
    WORKFLOW_SNAPSHOT_WORK_INVALID: '版本审查输入、引用或候选不完整，请按节点诊断补齐本批证据后重试。',
    WORKFLOW_HISTORY_ANALYSIS_INVALID: '历史分析输入或候选不完整，请检查批次、贡献者及上游审查范围，按节点诊断修正后重试。',
    WORKFLOW_HISTORY_INVALID: '历史采集配置不完整，请选择明确分支并填写有效日期，结束日期不能早于开始日期。',
    WORKFLOW_REVIEW_SOURCE_PARAMETERS: '请选择明确分支及审查模式。日期增量需有效日期，全面审查不使用日期输入。',
  WORKFLOW_REVIEW_SOURCE_INVALID: '原版本审查资料缺失或不一致，请保留现场后恢复原采集节点。',
  WORKFLOW_REVIEW_BINDING_INVALID: '版本审查资料与原生产节点不一致，请检查固定输入。',
  WORKFLOW_REVIEW_CAPTURE_FAILED: '版本审查资料采集失败，可恢复或重试原节点；已定位的提交和审查范围保持不变。',
  WORKFLOW_HISTORY_CAPTURE_FAILED: 'Git 历史未能完整采集，请查看仓库连接、凭据及执行记录后重试；已定位的提交和日期范围保持不变。',
    WORKFLOW_HISTORY_EVIDENCE_INVALID: '原历史证据缺失或不一致，请保留现场并恢复原采集节点。',
    WORKFLOW_HISTORY_BINDING_INVALID: '这份历史资料不属于本节点的固定输入，请检查前置交付物。',
    WORKFLOW_REPOSITORY_INVALID: '分支采集配置不完整，请选择明确分支并保留固定代码、报告和说明三个交付物。',
    WORKFLOW_REPOSITORY_CAPTURE_FAILED: '分支代码未能采集，请检查仓库连接、Git 凭据与执行记录后重试；已定位的提交保持不变。',
    WORKFLOW_REPOSITORY_EVIDENCE_INVALID: '原代码快照或采集记录缺失、损坏，请保留现场并检查数据目录后恢复原尝试。',
    TEMPLATE_SHALLOW_SOURCE: '所选仓库历史不完整，请补齐历史后新增采集节点，或明确选择可读取完整历史的远程分支。',
    TEMPLATE_EVIDENCE_LIMIT: '历史证据超过完整保存上限，请缩小日期范围后新增采集节点。',
    TEMPLATE_OCTOPUS_MERGE: '范围内包含多父合并，当前无法完整重建其独有变更，请调整日期范围或分支后新增节点。',
    TEMPLATE_GIT_TIMEOUT: '读取 Git 超过时限，请检查连接后重试。重试保留原提交；需要新版本时新增节点。',
    WORKFLOW_SOURCE_INCOMPLETE: '目标文件未能完整读取，不能作为成功资料。请查看原因；需要读取修复后的源码时，修改计划并新增采集节点。',
    WORKFLOW_SOURCE_CHANGED: '原快照正文尚未保存完整，项目已变化。请恢复原源码后重试，或修改计划并新增节点采集新版本。',
    WORKFLOW_SOURCE_STORAGE_INVALID: '冻结正文缺失或损坏，请保留现场并检查数据目录后重试。',
    WORKFLOW_SOURCE_INVALID: '源码节点配置不完整，请重新选择预设并绑定源码路径。',
    WORKFLOW_SOURCE_DESIGN_INVALID: '源码设计配置、覆盖或设计稿来源不一致，请检查本批路径与输入绑定。',
    WORKFLOW_TEST_PLAN_INVALID: '单测分批配置不完整，请核对场景、编写、最终测试、复核与汇总的输入及连接；删除复核时同步调整策略。',
    WORKFLOW_TEST_PLAN_LIMIT: '完整单测计划超过容量，请按明确源码子目录拆分需求；原源码和配置已保留。',
    WORKFLOW_UPLOAD_INVALID: '文档未完整保存或不属于当前需求，请重新选择已保存的资料。',
    WORKFLOW_UPLOAD_UNAVAILABLE: '计划已变化或资料已经固定，请检查当前计划后重新上传。',
    WORKFLOW_UPLOAD_BUSY: '同一次上传仍在保存，请稍后查看上传记录。',
    WORKFLOW_UPLOAD_LIMIT: '解析内容超过容量限制，请拆分文档后上传。',
    WORKFLOW_TEST_SUMMARY_INVALID: '单测汇总的来源不一致，请绑定同一份最终代码、各模块测试和对应场景复核。',
    WORKFLOW_DOCUMENT_PLAN_INVALID: '原文分批配置不完整，请检查评审、可选复核与汇总的连接，以及同版原文和代码绑定。',
    WORKFLOW_DOCUMENT_PLAN_LIMIT: '完整原文分批超过容量，请按明确业务范围拆分需求；原文已保留，未截断章节。',
    WORKFLOW_ASSESSMENT_REPORT_INVALID: '报告范围或来源不完整，请绑定全部原文章节对应的评审稿及同版复核。',
    WORKFLOW_ASSESSMENT_REVIEW_INCOMPLETE: '尚未全部独立复核通过，请补齐复核，或明确调整汇总策略。',
    WORKFLOW_ASSESSMENT_REPORT_LIMIT: '完整报告超过存储上限，请按明确业务范围拆分需求；已有评审稿保留。',
    WORKFLOW_SOURCE_PLAN_INVALID: '分批配置不完整或存在其他消费者，请检查编写、可选复核与汇总的连接及同版源码绑定。',
    WORKFLOW_SOURCE_PLAN_LIMIT: '完整分批计划超过容量，请按明确源码子目录建立独立流程；原源码和配置已保留。',
    WORKFLOW_KNOWLEDGE_BUNDLE_INVALID: '来源证据选择不完整或不属于本次执行，请查看原文记录、补充缺口说明并重新提交。',
    WORKFLOW_INPUT_CORRUPT: '固定输入与原交付版本不一致，请保留记录并检查来源交付物。',
    WORKFLOW_INPUT_PAGE_INVALID: '正文读取位置无效，请从起始位置重新读取。',
    WORKFLOW_SYSTEM_MODULE_UNAVAILABLE: '此程序工作模块不可执行，请选择受支持的模块版本。',
    WORKFLOW_SOURCE_BATCH_REQUIRED: '源码超过单批范围，请拆分编写与复核节点的目标路径，确认计划后执行。',
    SOURCE_DESIGN_INVALID: '设计或复核未满足完整读取、精确引用及覆盖要求，请查看节点交付与任务说明。',
    SOURCE_CANDIDATE_JSON_INVALID: '设计或复核结构不完整，请按本节点工作信息修正后重新提交。',
    WORKFLOW_DOCUMENT_INVALID: '文档汇总配置不完整，请检查冻结源码、设计稿及完成策略。',
    WORKFLOW_DOCUMENT_INPUT_INVALID: '设计稿与复核来源不对应，请绑定本需求同一版源码及准确的稿件。',
    WORKFLOW_DOCUMENT_BINDING_INVALID: '固定文档缺失或不属于本次交付，请检查输入和数据完整性后重试。',
    SOURCE_REVIEW_INCOMPLETE: '尚未全部独立复核通过，请补齐对应意见，或明确修改文档的复核策略。',
    SOURCE_COVERAGE_INCOMPLETE: '设计稿未完整覆盖所选源码，请补齐遗漏批次及引用后重新汇总。',
    SOURCE_ARTIFACT_LIMIT: '文档超过存储上限，请保留已有成果并调整汇总范围。',
    SOURCE_DRAFT_NOT_READ: '本次绑定的设计稿尚未完整读取，请完成阅读后重新提交复核意见。',
    SOURCE_READ_PARAMETERS_INVALID: '源码读取参数不符合本节点合同，请按工作信息使用冻结哈希和行号读取。',
    SOURCE_READ_LIMIT: '所选源码段过长，请减少每次读取的行数。',
    SOURCE_MARKDOWN_LINK_INVALID: '专业设计正文不能包含 HTML、图片或自建链接，请修正正文后重新提交。',
    SOURCE_WORKSPACE_BUSY: '项目有活动写入或停止待确认，请处理后重试。',
    SOURCE_PATH_INVALID: '源码路径不可用、超出项目或包含符号链接，请检查项目内文件或目录后重新规划。',
    SOURCE_SNAPSHOT_UNSTABLE: '采集期间源码发生变化，请停止修改后重试。',
    SOURCE_NO_APPLICABLE_FILES: '所选路径没有适用源码，请查看排除项并调整采集节点。',
    SOURCE_SNAPSHOT_LIMIT: '采集超过文件、正文或时间上限，请缩小项目范围后重新规划。',
    SOURCE_SNAPSHOT_READ_FAILED: '项目目录无法完整读取，请检查权限后重试。',
    WORKFLOW_HUMAN_INPUT: '请选择正在等待的人工节点，填写结果后继续。', WORKFLOW_USER_PAUSED: '已暂停后续派发，已开始的节点继续收尾。',
    WORKFLOW_FINISHING: '正在收束活动节点，确认停止后记录用户选择的结束结果。', WORKFLOW_USER_FINISHED: '用户已结束需求，原始节点结论和交付物保留。',
    WORKFLOW_FINISH_INVALID: '请选择结束结果并填写原因。',
    WORKFLOW_SCOPE_COMPLETE: '本次选定范围已执行完毕，可以选择下一步。', WORKFLOW_RETRY_EXHAUSTED: '自动重试已耗尽，请查看失败节点并决定是否重试。',
    WORKFLOW_NODE_CANCELLED: '有节点已取消，请调整后续计划。', WORKFLOW_NODE_STOP_REQUESTED: '正在处理停止请求，后续节点已暂停。',
    WORKFLOW_MODEL_RECOVERY_REQUIRED: '请查看需要恢复的节点，处理原尝试后再继续。', WORKFLOW_DIRECTORY_UNAVAILABLE: '项目目录不可访问，请恢复目录或检查项目登记。',
    WORKFLOW_DIRECTORY_SNAPSHOT_INVALID: '项目文件或保存的目录快照发生变化，已保留现场。请核对文件与目录权限，处理冲突后恢复原尝试。',
    WORKFLOW_DIRECTORY_PREPARATION_CHANGED: '目录准备记录或写入许可已变化，请保留项目文件并恢复原尝试。',
    WORKFLOW_SOURCE_PURPOSE_MISMATCH: '请选择单元测试用途的冻结源码，以包含构建配置和已有测试资料。',
    SOURCE_TEST_INPUT_DRIFT: '工作区与固定源码或测试基线已不一致，请核对原输入及目录后恢复；需要新源码时重新规划。',
    SOURCE_TEST_SCOPE_UNVERIFIED: '测试范围尚无法完整核验，已保留现场。请检查原文件、保存的正文及目录权限后恢复原尝试。',
    SOURCE_TEST_WRITE_RANGE_VIOLATION: '修改超出测试范围或改变了已有测试，请查看本次范围报告和固定代码，再调整或重试节点。',
    WORKFLOW_TEST_SCOPE_SERVER_OWNED: '范围报告由程序生成，请从模型候选中移除这项输出后重新提交。',
    WORKFLOW_TEST_REVIEW_INVALID: '场景复核的固定输入、测试关联或阅读依据不完整，请按工作信息检查后重新提交。',
    WORKFLOW_TEST_RUN_INPUT_INVALID: '原生单测输入不匹配，请核对固定源码、配置、场景、代码及模块路径。',
    WORKFLOW_TEST_CODE_LINEAGE_INVALID: '代码未实际继承所选批次的成果，请检查工作区代码输入和场景来源。',
  WORKFLOW_TEST_EVIDENCE_INVALID: '本次测试证据不一致，请保留执行记录并检查数据。',
  WORKFLOW_TEST_REPORT_NOT_FRESH: '报告位置已有内容或不可读取，请保留现场并重新准备测试节点。',
  WORKFLOW_TEST_INPUT_MISMATCH: '测试配置与冻结源码来源不一致，请重新选择同版输入。',
    WORKFLOW_TEST_DESIGN_INVALID: '单测场景设计尚不完整，请检查本批源码、配置阅读和具体场景。',
    WORKFLOW_TEST_PROFILE_INVALID: '测试配置无法完整识别，请查看识别报告并核对冻结源码和测试目录。',
    WORKFLOW_CODE_BASE_CHANGED: '上游代码成果与当前项目基准不一致，请明确合并方式或调整输入后继续。',
    WORKFLOW_REVIEW_INVALID: '评审配置或交付格式不完整，请核对固定输入和报告内容。',
  WORKFLOW_REVIEW_EVIDENCE_MISMATCH: '两份评审未使用同一批设计、代码和程序验证报告，请核对输入绑定。',
  WORKFLOW_COMMAND_INVALID: '检查命令配置不完整，请调整代码输入、程序参数和时限。',
    WORKFLOW_COMMAND_PREPARATION_FAILED: '检查执行记录无法准备，请检查数据目录后恢复原尝试。',
    WORKFLOW_COMMAND_WORKSPACE_INVALID: '检查目录与固定交付物不一致，请保留现场并处理目录问题。',
    WORKFLOW_COMMAND_START_UNKNOWN: '监督进程启动回执未确认，请恢复原尝试以核对执行记录。',
    WORKFLOW_COMMAND_GRANT_UNKNOWN: '命令启动许可尚未确认，请恢复原尝试以核对执行记录。',
    WORKFLOW_COMMAND_STOP_UNCONFIRMED: '尚未取得可靠的停止证明，后续执行保持阻断。请先检查原命令的执行记录。',
    WORKFLOW_COMMAND_EVIDENCE_INVALID: '命令记录缺失或不一致，请保留现场并检查数据目录。',
    WORKFLOW_COMMAND_RECOVERY_REQUIRED: '命令检查需要恢复，请查看执行记录并处理原尝试后继续。',
    WORKFLOW_COMMAND_STOPPING: '正在停止原检查命令，确认停止前不会启动下一次尝试。',
    COMMAND_START_FAILED: '命令未能启动，请检查程序路径、安装情况与执行权限。',
    COMMAND_PREPARATION_FAILED: '依赖准备未完成，检查命令没有启动。请查看准备步骤的结果并处理后重试。',
    WORKFLOW_TEST_ENVIRONMENT_INVALID: '测试环境或依赖配置不可用，请检查项目依赖声明、解释器和执行记录后重新准备。',
    WORKFLOW_TEST_DEPENDENCIES_REQUIRED: 'Python 项目尚未声明测试依赖。请在 requirements.txt、requirements-test.txt、requirements-dev.txt 或包配置中声明 pytest 等依赖，再创建新的测试尝试。',
    WORKFLOW_TEST_PYTHON_UNAVAILABLE: '未找到 Python 3。请安装并加入 Loopper 进程的 PATH，再恢复原尝试。',
    WORKFLOW_TEST_WRAPPER_UNEXECUTABLE: '项目测试启动脚本没有执行权限。请修正项目中的脚本权限并重新交付代码，再创建新的测试尝试。',
    WORKFLOW_TEST_ENVIRONMENT_EXISTS: '本次私有依赖目录已有内容。请保留执行记录并创建新的测试尝试，程序不会覆盖旧环境。',
    COMMAND_OUTPUT_INCOMPLETE: '命令输出未能完整读取，请查看执行记录后决定是否重试。',
    COMMAND_STOP_UNCONFIRMED: '尚未确认命令及已观察子进程全部停止。',
    GRANT_NOT_RECEIVED: '本次监督进程未收到执行许可，检查命令没有启动。',
    WORKFLOW_VERIFICATION_INVALID: '交付物检查配置不完整，请调整该节点的代码输入、检查项目与完成规则。',
    WORKFLOW_DEPENDENCY_BLOCKED: '前置条件尚未满足，请检查节点依赖和业务结果。', WORKFLOW_PLAN_CHANGED: '计划已变化，请查看新版本后继续。', WORKFLOW_PLAN_REVIEW_REQUIRED: '有候选计划等待查看，确认或退回后才能继续派发。',
  } as Record<string, string>)[value] ?? userFacingError(value, '执行需要处理，请查看节点详情与运行环境后重试。')
}

export function displayLabel(value?: string) {
  if (!value) return '未知'
  const normalized = value.trim().replace(/-/g, '_').toUpperCase()
  const translated = statusLabels[normalized]
    ?? generalLabels[normalized]
    ?? errorCodeLabels[normalized]
    ?? normalized.split('_').map(token => tokenLabels[token]).filter(Boolean).join('')
  return translated || '未知'
}

export function errorCodeLabel(value?: string) {
  if (!value) return '操作未完成'
  return errorCodeLabels[value.toUpperCase()] ?? displayLabel(value)
}

function containsChinese(value: string) {
  return /[\u3400-\u9fff]/.test(value)
}

export function userFacingError(value: unknown, fallback = '操作未完成，请重试'): string {
  const raw = value instanceof Error ? value.message : typeof value === 'string' ? value : ''
  if (!raw.trim()) return fallback
  const envelope = raw.match(/^\s*SYSTEM_ERROR\[(FIELD|VERIFICATION|SESSION|TASK)\]\s*:?\s*/)
  const detail = envelope ? raw.slice(envelope[0].length) : raw
  if (detail.trim() === 'OpenCode executable was not found in OPENCODE_EXECUTABLE or PATH') {
    return '未找到 OpenCode 启动文件。请在设置的“命令行路径”填写启动文件的完整路径，保存后点击“启动并检查连接”。'
  }
  if (/'file part media type [a-z0-9.+\/-]+' functionality not supported\./i.test(detail)) {
    return '当前模型不支持直接读取此附件格式。请升级 Loopper 或换用支持该格式的模型后新建设计；无需清理项目文件。'
  }
  const artifactErrors = [...detail.matchAll(/stages\[(\d+)\]\.verifiers\[(\d+)\]\.(documentAssertions|tabularAssertions): (?:DOCUMENT_STRUCTURE|TABULAR_DATA) requires bounded assertions/g)]
  if (artifactErrors.length) return detail.split(/[；\n]/).filter(part => part.trim()).map(part => {
    const match = artifactErrors.find(error => part.includes(error[0]))
    return match
      ? `阶段 ${Number(match[1]) + 1} 的验收器 ${Number(match[2]) + 1} 缺少${match[3] === 'documentAssertions' ? '文档' : '表格'}断言，请在验收器中添加至少一项检查后重试。`
      : userFacingError(part, '另有输入校验错误，请检查执行规范')
  }).join('；')
  const codes = detail.match(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g) ?? []
  const attachmentCode = codes.find(code => code.startsWith('ATTACHMENT_MCP_'))
  if (attachmentCode) return errorRecoveryMessages[attachmentCode] ?? '附件资源读取失败，请检查运行环境和文件后重试。'
  const translated = codes.reduce((message, code) => message.split(code).join(errorCodeLabel(code)), detail)
  if (containsChinese(detail)) return translated
  const code = codes[0]
  if (code) return errorRecoveryMessages[code] ?? `${errorCodeLabel(code)}，请按页面提示处理后重试`
  const layer = envelope?.[1]
  return (layer ? systemErrorFallbacks[layer] : undefined) ?? fallback
}

export function errorEventMessage(code?: string, message?: string) {
  const translated = userFacingError(message, '')
  if (translated) return translated
  if (code && errorRecoveryMessages[code]) return errorRecoveryMessages[code]
  return `${errorCodeLabel(code)}，请按页面提示处理后重试`
}

export function taskIntentLabel(value: TaskIntent) {
  return taskIntentLabels[value]
}

export function artifactKindLabel(value: ArtifactKind) {
  return artifactKindLabels[value]
}

export function workflowTemplateLabel(value: WorkflowTemplate) {
  return workflowTemplateLabels[value]
}

export function executionStrategyLabel(value: ExecutionStrategy) {
  return executionStrategyLabels[value]
}

export function testPolicyLabel(value: TestPolicy) {
  return testPolicyLabels[value]
}

export function profileResolutionLabel(value: string) {
  return profileResolutionLabels[value] ?? displayLabel(value)
}

const rolePackLabels: Record<string, string> = {
  'software-mixed': '混合技术栈',
  'software-java': 'Java 软件开发',
  'software-python': 'Python 软件开发',
  'software-node': 'Node/前端开发',
  'document-markdown-docx': '文档制品',
  'tabular-conversion': '表格转换',
  'read-only-report': '只读评审',
  'local-maintenance': '本地维护',
  'legacy-software': '历史软件任务',
}

export function rolePackLabel(value?: string) {
  if (!value) return '通用任务'
  return rolePackLabels[value.toLowerCase()] ?? '定制任务'
}

export function designerActorLabel(actor?: string) {
  return displayLabel(actor ?? 'SYSTEM')
}

export function sessionLabel(session?: TaskSessionSummary) {
  if (!session) return '任务会话'
  if (session.kind !== 'JUDGE') return '开发工程师会话'
  const source = session.label.toUpperCase()
  if (source.includes('REQUIREMENT')) return '需求评审员会话'
  if (source.includes('RISK')) return '风险评审员会话'
  return '评审员会话'
}

export function activityTypeLabel(type: TaskSessionActivityPart['type']) {
  return type === 'THINKING' ? '思考' : type === 'TOOL' ? '工具' : '输出'
}

export function activityLabel(part: TaskSessionActivityPart) {
  if (part.type === 'THINKING') return '模型思考'
  if (part.type === 'OUTPUT') return '模型输出'
  return toolLabels[part.label.toLowerCase()] ?? part.label
}

export function judgeRoleLabel(role: string) {
  return role === 'REQUIREMENT' ? '需求评审员' : role === 'RISK' ? '风险评审员' : '独立评审员'
}

export function evidenceCaptureLabel(state: string): string {
  return ({ COMPLETE: '采集完整', TRUNCATED: '内容已截断', INCOMPLETE: '采集不完整', UNCONFIRMED: '来源未确认', LIMIT: '达到采集上限', PREPARED: '持久化未完成', UNAVAILABLE: '采集不可用' } as Record<string, string>)[state] ?? '状态待核实'
}

export function documentTemplateStateLabel(value: string): string {
  const labels: Record<string, string> = { PREPARING: '准备文档', ANALYZING: '整理需求', REVIEWING: '复核原文',
    DESIGNING: '设计开发方案', EXECUTING: '开发与验收', ASSESSING: '静态代码评审', VERIFYING: '复核评审结论',
    REPORTING: '生成报告', WAITING_INPUT: '等待处理', STOPPING: '等待停止确认', CANCELLED: '已取消', COMPLETED: '已完成' }
  return labels[value] ?? '状态待确认'
}
export function sourceTemplateStateLabel(value: string): string {
  return ({ PENDING_START: '等待开始', PREPARING: '扫描与冻结源码', DESIGNING: '设计测试场景', EXECUTING: '编写与验证测试',
    WRITING: '编写详细设计', REVIEWING: '独立复核', REPORTING: '整理交付结果', WAITING_INPUT: '等待处理',
    STOPPING: '正在停止', CANCELLED: '已取消', COMPLETED: '已完成' } as Record<string, string>)[value] ?? '状态待刷新'
}
export function sourceCoverageLabel(value: string): string {
  return ({ PENDING: '待处理', INCOMPLETE: '未完成', EXCLUDED: '已排除', TESTED: '测试已验证', REVIEWED: '文档已复核' } as Record<string, string>)[value] ?? '处理中'
}
export function sourceBatchStateLabel(value: string): string {
  return ({ PREPARED: '等待调度', CREATING: '创建会话', PROMPT_READY: '准备投递', DISPATCHING: '核对投递', RUNNING: '处理中',
    VALIDATED: '已完成', FAILED: '失败', STOPPED: '已停止' } as Record<string, string>)[value] ?? '等待同步'
}
export function sourceBatchFailureLabel(value: string): string {
  return ({ SOURCE_MODEL_TIMEOUT: '本批次执行时间已耗尽，请查看冻结预算与已保留结果。',
    SOURCE_MODEL_FAILED: '模型会话失败，请检查模型和运行环境后恢复。',
    SOURCE_SUBMISSION_MISSING: '模型结束时没有提交有效候选，请保留原批次身份重试。',
    SOURCE_SESSION_AMBIGUOUS: '无法唯一核对模型会话，停止证明完成前保持阻断。',
    SOURCE_PROMPT_LOOKUP_UNAVAILABLE: '请求是否送达尚不明确，正在保留原身份核对。',
    SOURCE_MCP_REQUIRED: '角色专属工具通道不可用，请检查托管模型连接。' } as Record<string, string>)[value]
    ?? '本批次尚未完成；已保留执行身份与结果，请查看当前待处理原因后恢复。'
}
export function requirementConclusionLabel(value: string | null | undefined): string {
  const labels: Record<string, string> = { SATISFIED: '符合需求', PARTIAL: '部分实现', INCORRECT: '实现不符',
    NOT_IMPLEMENTED: '未实现', UNDETERMINED: '无法判断' }
  return value ? labels[value] ?? '结论待确认' : '尚未形成结论'
}
export function documentFindingKindLabel(value: string): string {
  return ({ DEFECT: '确认缺陷', VALIDATION_GAP: '验证缺口', SUGGESTION: '改进建议' } as Record<string, string>)[value] ?? '问题类型待确认'
}
export function documentFindingSeverityLabel(value: string): string {
  return ({ CRITICAL: '严重', HIGH: '高', MEDIUM: '中', LOW: '低', INFO: '提示' } as Record<string, string>)[value] ?? '影响待确认'
}
export function requirementKindLabel(value: string): string {
  const labels: Record<string, string> = { FUNCTION: '功能', RULE: '规则', PERMISSION: '权限', EXCEPTION: '异常', ACCEPTANCE: '验收场景', CONSTRAINT: '约束' }
  return labels[value] ?? '需求'
}

export function templateDiagnosticPhaseLabel(phase: string): string {
  return ({ AUTO_RETRY_WAIT: '等待自动重试', ANALYZING: '分析中', ACCEPTED_WAITING_STOP: '结果已接受，等待会话结束', STOP_REQUESTED: '已请求停止', STOP_UNCONFIRMED: '停止待确认', STOP_CONFIRMED: '停止已确认', STALLED: '疑似停滞', DISCONNECTED: '连接异常', COMPLETED: '已完成', FAILED: '批次失败', PREPARING: '准备中' } as Record<string, string>)[phase] ?? '等待核对'
}

export function knowledgeStateLabel(state: string): string {
  return ({ READY: '可用', PREPARED: '准备中', CREATING: '连接中', CREATE_UNKNOWN: '核对会话', SENDING: '发送中', UNKNOWN: '核对发送', RUNNING: '生成中', STOPPING: '停止确认中', COMPLETED: '已完成', STOPPED: '已停止 · 未完成', FAILED: '未完成', REMOVED: '已移除', IDLE: '可以提问', DISCONNECTED: '连接待恢复', SUCCEEDED: '已读取' } as Record<string, string>)[state] ?? '状态待核对'
}
export function knowledgeToolLabel(tool: string): string {
  return ({ inspect_knowledge_project: '查看项目概览', list_knowledge_evidence: '查看保存证据', read_knowledge_evidence: '回读保存证据', read_knowledge_sources: '批量读取原文', find_knowledge_symbol: '查找代码符号', search_knowledge_git_content: '检索历史正文', search_knowledge_git_patches: '检索提交差异', compare_knowledge_git_versions: '比较代码版本', read: '读取项目文件', glob: '搜索项目文件', grep: '搜索代码内容', todowrite: '更新调查进度', todoread: '查看调查进度', list_knowledge_sources: '查看资料来源', browse_knowledge_source: '浏览资料目录', search_knowledge: '检索项目资料', search_project_knowledge: '统一检索项目资料', read_knowledge_source: '读取原文片段', inspect_knowledge_git: '查看仓库', list_knowledge_git_authors: '查找作者', search_knowledge_git_commits: '查询提交', read_knowledge_git_commit: '读取提交', read_knowledge_git_file: '读取历史文件', blame_knowledge_git_lines: '查询最后修改记录', list_database_connections: '查看数据库连接', inspect_database_schema: '查看数据库结构', query_database_readonly: '只读查询数据库' } as Record<string, string>)[tool] ?? '读取资料'
}
export const knowledgeSearchStateLabel = (state: string) => ({ NOT_SEARCHED: '尚未检索', PARTIAL: '待继续检索', COMPLETE: '已查完', LIMITED: '范围未完整覆盖', FAILED: '检索失败', TIMED_OUT: '检索超时', SKIPPED: '本次未查询' } as Record<string, string>)[state] || '状态待确认'
export const knowledgeMatchLabel = (type: string) => ({ EXACT: '原词匹配', PHRASE: '原句匹配', FIELD: '字段匹配', NAME: '名称匹配', EXPANDED: '扩展词线索' } as Record<string, string>)[type] || '相关片段'

export const pptPhaseLabel = (phase: string) => ({ BRIEFING: '制作需求', DIRECTION: '整体方向', DESIGN: '页面设计', PRODUCING: '正在制作', REVIEW: '预览修改', EXPORTED: '已导出' } as Record<string, string>)[phase] || '阶段待确认'
export const pptRunLabel = (state: string) => ({ IDLE: '可以开始', PREPARED: '准备中', CREATING: '连接助手', CREATE_UNKNOWN: '连接结果待核对', SENDING: '正在发送', UNKNOWN: '发送结果待核对', RUNNING: '正在制作', STOPPING: '正在安全暂停', WAITING_INPUT: '等待回答', COMPLETED: '本轮已完成', STOPPED: '已停止', FAILED: '本轮失败' } as Record<string, string>)[state] || '状态待核对'
export const pptJobLabel = (state: string) => ({ PENDING: '等待制作', PREPARED: '准备中', QUEUED: '排队中', RUNNING: '正在生成', COMPLETED: '已生成', SUCCEEDED: '已生成', FAILED: '生成失败', CANCELLED: '已取消' } as Record<string, string>)[state] || '状态待核对'
export const pptElementLabel = (type: string) => ({ text: '文本框', image: '图片', shape: '图形', table: '表格', chart: '图表', connector: '连接线', line: '直线', group: '分组' } as Record<string, string>)[type] || '对象'
export const pptLayoutLabel = (layout: string) => ({ title_content: '标题与正文', two_columns: '两栏', three_columns: '三栏', grid: '网格', image_text: '图文' } as Record<string, string>)[layout] || '版式'

export function pptGenerationLabel(value?: string) {
  const labels: Record<string, string> = {
    PLANNING: '正在构思内容',
    PRODUCING: '正在制作页面',
    PREVIEW: '正在检查排版',
    EXPORT: '正在准备演示文稿',
    WAITING_INPUT: '需要你补充一点信息',
    STOPPING: '正在安全暂停',
    STOPPED: '已暂停',
    FAILED: '制作遇到问题',
    COMPLETED: '演示文稿已完成',
  }
  return labels[value || ''] || '等待开始'
}

export function pptGenerationStepLabel(value?: string) {
  const labels: Record<string, string> = {
    PLANNING: '构思内容',
    PRODUCING: '制作页面',
    PREVIEW: '检查排版',
    EXPORT: '准备下载',
  }
  return labels[value || ''] || '开始制作'
}

export function pptToolLabel(tool: string): string {
  return ({ ppt_get_context: '查看作品与设计', ppt_read_source: '读取参考资料', ppt_get_capabilities: '查看制作能力', ppt_request_input: '沟通并确认需求', ppt_submit_plan: '提交设计方案', ppt_apply_operations: '编辑演示文稿', ppt_measure_text: '测量文字排版', ppt_check_layout: '检查页面布局', ppt_render_preview: '生成页面预览', ppt_get_job: '查看制作进度', ppt_export: '导出演示文稿', ppt_list_knowledge_sources: '查看项目来源', ppt_search_project_knowledge: '检索项目知识', ppt_browse_knowledge_source: '浏览项目资料', ppt_read_knowledge_source: '阅读项目资料', ppt_query_knowledge_database: '查询项目数据', ppt_inspect_knowledge_database: '查看数据结构', ppt_read_knowledge_git: '查询项目记录' } as Record<string, string>)[tool] || '处理制作工具'
}

export function nativeToolLabel(tool: string): string | undefined {
  return toolLabels[tool]
}

export function workflowCodeChangeLabel(kind: string): string {
  return ({ ADD: '新增', MODIFY: '修改', DELETE: '删除' } as Record<string, string>)[kind] ?? '文件改动'
}

export function workflowHistorySideLabel(value: string): string {
  return ({ BEFORE: '变更前', AFTER: '变更后' } as Record<string, string>)[value] ?? '位置待确认'
}

export function workflowSnapshotVerdictLabel(value: string): string {
  return ({ SUPPORTED: '证据支持', UNDETERMINED: '待确认', DISMISSED: '不成立', DUPLICATE: '重复问题' } as Record<string, string>)[value] || '结论待核对'
}
export function workflowSnapshotAttributionLabel(value: string): string {
  return ({ CHANGE_RELATED: '与变化相关', EXISTING: '附带存量问题', UNDETERMINED: '归因未确定' } as Record<string, string>)[value] || '归因待核对'
}
