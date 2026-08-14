import type { TaskSessionActivityPart, TaskSessionSummary } from '@/types/domain'

const statusLabels: Record<string, string> = {
  PENDING_START: '待开始', QUEUED: '排队中', PREPARING: '准备中', READY: '待执行', RUNNING: '运行中', VERIFYING: '验证中',
  RETRY_WAIT: '等待重试', RETRY: '重试中', PAUSED: '已暂停', WAITING_INPUT: '等待输入', JUDGING: '评审中',
  SUCCEEDED: '已成功', FAILED: '已失败', CANCELLED: '已取消', ONLINE: '在线', OFFLINE: '离线',
  STARTING: '启动中', INCOMPATIBLE: '不兼容', PASS: '通过', FAIL: '未通过', PENDING: '待处理',
  CREATING: '创建中', BUSY: '处理中', COMPLETED: '已完成', ABORTED: '已中止', TIMED_OUT: '已超时',
  DISCONNECTED: '已断开', SESSION_ERROR: '会话错误', TASK_ERROR: '任务错误', VERIFIED: '已验证',
  VERIFIER_FAILED: '验证未通过', BLOCKED: '已阻塞', UNKNOWN: '未知',
  REVISE: '需修改', UNPARSEABLE: '无法解析', IDLE: '空闲', DONE: '已完成', CHECKING: '检查中',
  PERSISTED: '已保存', PENDING_HANDOFF: '等待交接', DELIVERED: '已送达', RECONNECTING: '重连中',
}

const toolLabels: Record<string, string> = {
  read: '读取文件', write: '写入文件', edit: '编辑文件', bash: '终端命令', apply_patch: '应用补丁',
  glob: '匹配文件', grep: '搜索内容', list: '列出文件', task: '子任务',
}

export function statusLabel(value?: string) {
  if (!value) return '未知'
  return statusLabels[value.toUpperCase()] ?? value.replace(/_/g, ' ')
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
