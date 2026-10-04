import type { ErrorEvent, Task, TaskEvent } from '@/types/domain'
import { displayLabel, nativeToolLabel } from '@/utils/displayLabels'

export const sessionPermissionLabel = (permission: string) => nativeToolLabel(permission)
  ?? ({ webfetch: '读取网页', external_directory: '访问项目外目录' } as Record<string, string>)[permission] ?? '其他权限'
export const sessionPermissionActionLabel = (action: string) => ({ allow: '允许', deny: '拒绝', ask: '需确认' } as Record<string, string>)[action] ?? '待核对'

export function taskAttempts(task: Task) { return task.attempts ?? task.stages?.flatMap(stage => stage.attempts) ?? [] }
export function latestJudges(task: Task) {
  return (['REQUIREMENT', 'RISK'] as const).flatMap(role => [...(task.judges ?? [])].filter(row => row.role === role).sort((a, b) => b.ordinal - a.ordinal).slice(0, 1))
}
export function taskFacts(task: Task, deliveryState = '') {
  const template = task.executionMode === 'TEMPLATE_REPORT', dual = !template || task.templateProgress?.dualReviewRequired !== false
  const deterministic = !!task.stages?.length && task.stages.every(stage => stage.status === 'SUCCEEDED')
  const judges = latestJudges(task), doublePass = judges.length === 2 && judges.every(row => row.verdict === 'PASS')
  const attempts = taskAttempts(task), artifacts = task.artifacts ?? []
  const strings = (value: unknown): string[] => Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
  const changed = new Set([...artifacts.filter(row => row.kind === 'DIFF').flatMap(row => strings(row.metadata?.changedPaths)), ...attempts.flatMap(attempt => attempt.verifiers.flatMap(row => strings(row.evidence?.changedPaths)))])
  return { template, dual, deterministic, doublePass, judges, attempts, artifacts, changed: changed.size,
    verified: attempts.flatMap(row => row.verifiers).filter(row => row.status === 'PASS').length,
    canRetryJudges: dual && deterministic && deliveryState !== 'MERGED' && (task.status === 'WAITING_INPUT' || task.status === 'SUCCEEDED' && !doublePass),
    canRetryLoop: task.status === 'WAITING_INPUT' && !deterministic && task.loopRetryAvailable === true,
    canRework: !template && task.branch !== 'DIRECT' && task.waitingReasonCode !== 'SOURCE_BRANCH_WORKSPACE_DIRTY' && ['WAITING_INPUT', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.status),
    publication: !template && (task.status === 'SUCCEEDED' || ['AWAITING_DECISION', 'COMPLETED'].includes(task.status) && task.executionResult === 'SUCCEEDED'),
  }
}
/** The current TASK alert belongs to the authoritative lifecycle, never the full audit history. */
export function currentTaskErrors(task: Task): ErrorEvent[] {
  const rows = [...(task.errors ?? [])].filter(row => row.layer === 'TASK').sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
  if (task.status === 'WAITING_INPUT') return rows.filter(row => row.code === task.waitingReasonCode).slice(0, 1)
  if (task.status === 'FAILED' || task.status === 'AWAITING_DECISION' && task.executionResult === 'FAILED') return rows.slice(0, 1)
  return []
}
export function taskNextAction(task: Task, now: number, deliveryState = '') {
  const facts = taskFacts(task, deliveryState)
  if (task.status === 'PENDING_START') return '尚未入队或创建执行目录。点击开始执行后申请执行资源。'
  if (task.status === 'READY') return '执行请求已接受，系统会自动继续。'
  if (task.status === 'QUEUED') return '等待执行租约，可核对队列阻塞或取消任务。'
  if (task.status === 'STOPPING') return '正在确认旧会话与进程停止；确认前保留租约。'
  if (task.status === 'RETRY_WAIT') return `等待${displayLabel(task.retryCause ?? 'SESSION')}重试${task.retryDueAt ? `，剩余 ${Math.max(0, Math.ceil((Date.parse(task.retryDueAt) - now) / 1000))} 秒` : ''}。`
  if (task.status === 'PAUSED') return '任务已暂停，恢复后继续原任务。'
  if (task.status === 'SUPERSEDED') return '已由派生任务接续，原任务证据保留。'
  if (task.status === 'AWAITING_DECISION' && task.executionResult === 'FAILED') return '任务已终止，不会再创建新会话'
  if (task.status === 'WAITING_INPUT') {
    if (task.waitingReasonCode === 'SOURCE_BRANCH_WORKSPACE_DIRTY') return '检测到未提交文件，请先处理文件；重新检查前不会创建任务分支。'
    if (task.waitingReasonCode === 'GIT_DIFF_SCOPE_APPROVAL_REQUIRED') return '等待核对范围外文件的授权。'
    if (facts.canRetryJudges) return '确定性验收已完成，请核对评审结果或重新进行双评审。'
    if (facts.canRetryLoop) return '保护机制暂停了尝试，可显式继续一轮。'
    return '任务等待处理，请核对当前问题、错误与会话。'
  }
  return ['COMPLETED', 'CANCELLED', 'FAILED'].includes(task.status) ? '执行已结束，报告、会话与审计证据仍可读取。' : '系统按服务端状态继续执行。'
}
export function taskSessionAndVerifierErrors(task: Task): ErrorEvent[] {
  const doublePass = taskFacts(task).doublePass
  return (task.errors ?? taskAttempts(task).flatMap(row => row.errors)).filter(row => row.layer === 'SESSION'
    || row.layer === 'VERIFICATION' && row.code !== 'GIT_DIFF_SCOPE_APPROVAL_REQUIRED'
      && (!row.code.startsWith('JUDGE_') || task.status === 'WAITING_INPUT' && !doublePass))
}
export function templatePhaseLabel(task: Task): string {
  const progress = task.templateProgress
  if (['COMPLETED', 'CANCELLED', 'FAILED', 'STOPPING', 'WAITING_INPUT', 'PENDING_START', 'QUEUED', 'PREPARING', 'PAUSED', 'RETRY_WAIT'].includes(task.status)) return displayLabel(task.status)
  const step = progress?.snapshot && progress.steps?.find(row => row.key === progress.currentPhase)
  if (step) return step.label
  const phases: Record<string, string> = { SNAPSHOT: '冻结证据', PLAN: '规划范围', ANALYSIS: '功能分析', SNAPSHOT_REVIEW: '独立复核与报告', COLLECT: '采集提交', CODE: '分析代码', CONTRIBUTORS: '分析人员贡献', REPORT: '生成并校验报告', REVIEW: '评审报告', COMPLETE: '已确认完成' }
  if (progress?.currentPhase) return phases[progress.currentPhase] ?? displayLabel(progress.currentPhase)
  if (task.status === 'AWAITING_DECISION' && progress?.dualReviewRequired === false) return '完成收尾'
  if (['JUDGING', 'AWAITING_DECISION'].includes(task.status)) return '评审报告'
  const total = progress?.reviewBatches == null || progress.contributorBatches == null ? null : progress.reviewBatches + progress.contributorBatches
  const done = (progress?.completedReviews ?? 0) + (progress?.completedContributors ?? 0)
  return total === null ? '采集提交' : done >= total ? '生成并校验报告' : (progress?.completedReviews ?? 0) < (progress?.reviewBatches ?? 0) ? '分析代码' : '分析人员贡献'
}
export function taskEventNotice(event: TaskEvent): string | undefined {
  if (event.type === 'story_binding.failed') return typeof event.data.message === 'string' ? event.data.message : 'AI 工作量统计失败，任务继续执行。'
  if (!['AI_OUTPUT_NORMALIZED', 'AI_TOOL_LOOP_FINALIZER_STARTED'].includes(event.type)) return undefined
  const role = typeof event.data.role === 'string' ? displayLabel(event.data.role) : 'AI'
  const corrections = Array.isArray(event.data.corrections) ? event.data.corrections.filter((item): item is string => typeof item === 'string').map(displayLabel).join('、') : ''
  return event.type === 'AI_OUTPUT_NORMALIZED' ? `${role}输出已自动规范化${corrections ? `：${corrections}` : ''}` : `${role}重复工具调用已停止，正在使用一次 MCP-only 收口会话`
}
