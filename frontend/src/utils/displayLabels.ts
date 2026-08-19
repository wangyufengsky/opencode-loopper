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
  LEGACY: '历史兼容',
}

export function statusLabel(value?: string) {
  if (!value) return '未知'
  return statusLabels[value.toUpperCase()] ?? value.replace(/_/g, ' ')
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
  return profileResolutionLabels[value] ?? value.replace(/_/g, ' ')
}

export function sessionLabel(session?: TaskSessionSummary) {
  if (!session) return '任务会话'
  if (session.kind !== 'JUDGE') return '执行会话'
  const source = session.label.toUpperCase()
  if (source.includes('REQUIREMENT')) return '需求评审会话'
  if (source.includes('RISK')) return '风险评审会话'
  return '只读评审会话'
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
  return role === 'REQUIREMENT' ? '需求评审' : role === 'RISK' ? '风险评审' : '独立评审'
}
