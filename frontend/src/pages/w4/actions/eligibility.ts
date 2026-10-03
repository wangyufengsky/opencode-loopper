import type { Task } from '@/types/domain'

/** Same status gates as TaskDetailView; an unavailable child keeps its pending owner. */
export function judgePanelEligible(task: Task) {
  if (task.executionMode === 'TEMPLATE_REPORT') return false
  if (task.judges?.length || task.status === 'JUDGING' || task.status === 'WAITING_INPUT') return true
  const deterministicAccepted = !!task.stages?.length && task.stages.every(stage => stage.status === 'SUCCEEDED')
  const passed = (['REQUIREMENT', 'RISK'] as const).every(role =>
    [...(task.judges || [])].filter(judge => judge.role === role).sort((a, b) => b.ordinal - a.ordinal)[0]?.verdict === 'PASS')
  return task.status === 'SUCCEEDED' && deterministicAccepted && !passed
}
export const decisionPanelEligible = (task: Task) => task.executionMode !== 'TEMPLATE_REPORT' && task.status === 'AWAITING_DECISION'
export const scopePanelEligible = (task: Task) => task.status === 'WAITING_INPUT'
export const rollingPanelEligible = (task: Task) => task.executionMode === 'ROLLING_PACKAGES'
