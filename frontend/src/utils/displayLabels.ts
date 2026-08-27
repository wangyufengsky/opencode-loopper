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
  FROM_FAILED_STAGE: '从失败阶段继续', ALL_STAGES: '重做全部阶段', VERIFY_ONLY: '只读复核', REWORK_ALL_STAGES: '重新实施全部阶段', INHERIT_CHANGES: '继承已有变更',
}

const errorCodeLabels: Record<string, string> = {
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
  WRAPPER_TOLERATED: '已兼容常见外层格式', AI_OUTPUT_NORMALIZED: '输出已自动规范化',
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

export function userFacingError(value: unknown, fallback = '操作未完成，请重试') {
  const raw = value instanceof Error ? value.message : typeof value === 'string' ? value : ''
  if (!raw.trim()) return fallback
  const codes = raw.match(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g) ?? []
  const translated = codes.reduce((message, code) => message.split(code).join(errorCodeLabel(code)), raw)
  if (containsChinese(raw)) return translated
  if (codes.length) return `${errorCodeLabel(codes[0])}，请按页面提示处理后重试`
  return fallback
}

export function errorEventMessage(code?: string, message?: string) {
  const translated = userFacingError(message, '')
  if (translated) return translated
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
