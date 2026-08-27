export const TASK_STATUSES = [
  'PENDING_START', 'QUEUED', 'PREPARING', 'READY', 'RUNNING', 'VERIFYING', 'RETRY_WAIT',
  'PAUSED', 'PACKAGE_DESIGNING', 'WAITING_INPUT', 'JUDGING', 'STOPPING',
  'AWAITING_DECISION', 'COMPLETED', 'SUPERSEDED', 'SUCCEEDED', 'FAILED', 'CANCELLED',
] as const
export type TaskStatus = typeof TASK_STATUSES[number]

export const STAGE_STATUSES = ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'PAUSED', 'CANCELLED'] as const
export type StageStatus = typeof STAGE_STATUSES[number]

export const SESSION_STATUSES = ['CREATING', 'RUNNING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'DISCONNECTED', 'ABORTED'] as const
export type SessionStatus = typeof SESSION_STATUSES[number]

export const LOOP_DRAFT_STATUSES = ['DRAFTING', 'DRAFT_READY', 'CONFIRMED', 'HANDOFF_FAILED'] as const
export type LoopDraftStatus = typeof LOOP_DRAFT_STATUSES[number]

export const DESIGNER_SESSION_STATES = ['PENDING_HANDOFF', 'RUNNING', 'REVIEWING', 'WAITING_INPUT', 'COMPLETED', 'SESSION_ERROR', 'STOPPING', 'CANCELLED'] as const
export type DesignerSessionState = typeof DESIGNER_SESSION_STATES[number]

export const DESIGN_WORK_PACKAGE_STATES = [
  'PENDING', 'QUESTIONING', 'DESIGNING', 'COMPILING', 'VALIDATING', 'COMPLETED',
  'REVIEWING', 'APPROVED', 'STALE', 'WAITING_INPUT', 'SUPERSEDED', 'FAILED',
] as const
export type DesignWorkPackageState = typeof DESIGN_WORK_PACKAGE_STATES[number]

export const TASK_PACKAGE_RUN_STATES = [
  'PLANNED', 'DESIGNING', 'DESIGN_REVIEW', 'EXECUTION_READY', 'QUEUED', 'RUNNING',
  'VERIFYING', 'CHECKPOINTING', 'FACT_FROZEN', 'WAITING_INPUT', 'SUPERSEDED', 'CANCELLED',
] as const
export type TaskPackageRunState = typeof TASK_PACKAGE_RUN_STATES[number]

export const WORK_PACKAGE_AGGREGATE_STATUSES = ['PENDING', 'RUNNING', 'CANCELLED', 'SUCCEEDED', 'FAILED'] as const
export type WorkPackageAggregateStatus = typeof WORK_PACKAGE_AGGREGATE_STATUSES[number]

export function requirePublicState<const T extends readonly string[]>(values: T, value: unknown, owner: string): T[number] {
  if (typeof value === 'string' && (values as readonly string[]).includes(value)) return value as T[number]
  throw new Error(`${owner} returned unknown state: ${String(value)}`)
}
