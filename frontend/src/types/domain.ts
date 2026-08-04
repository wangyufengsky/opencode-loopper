export type ErrorLayer = 'FIELD' | 'VERIFICATION' | 'SESSION' | 'TASK'

export type TaskStatus =
  | 'QUEUED'
  | 'PREPARING'
  | 'READY'
  | 'RUNNING'
  | 'VERIFYING'
  | 'RETRY_WAIT'
  | 'PAUSED'
  | 'WAITING_INPUT'
  | 'JUDGING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'

export type SessionStatus = 'CREATING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'TIMED_OUT' | 'DISCONNECTED' | 'ABORTED'

export interface Project {
  id: string
  name: string
  rootPath: string
  branch?: string
  description?: string
  status: 'READY' | 'NEEDS_GIT' | 'INVALID'
  updatedAt: string
  taskCount: number
}

export interface DirectorySelection {
  selected: boolean
  path?: string
  name?: string
}

export interface RuntimeInfo {
  status: 'ONLINE' | 'OFFLINE' | 'STARTING' | 'INCOMPATIBLE'
  version?: string
  managed: boolean
  pid?: number
  endpoint?: string
  model?: string
  checkedAt: string
}

export interface ErrorEvent {
  id: string
  layer: ErrorLayer
  code: string
  message: string
  retryable: boolean
  occurredAt: string
  evidenceId?: string
  sessionId?: string
}

export interface VerifierResult {
  id: string
  name: string
  status: 'PASS' | 'FAIL' | 'PENDING'
  summary: string
  output?: string
  elapsedMs?: number
}

export interface Attempt {
  id: string
  ordinal: number
  stageId: string
  sessionId?: string
  status: 'RUNNING' | 'VERIFIED' | 'VERIFIER_FAILED' | 'SESSION_ERROR' | 'TASK_ERROR' | 'CANCELLED'
  startedAt: string
  endedAt?: string
  summary: string
  errors: ErrorEvent[]
  verifiers: VerifierResult[]
}

export interface Stage {
  id: string
  ordinal: number
  objective: string
  status: 'PENDING' | 'RUNNING' | 'VERIFYING' | 'PAUSED' | 'SUCCEEDED' | 'BLOCKED'
  attempts: Attempt[]
}

export interface Task {
  id: string
  projectId: string
  projectName: string
  title: string
  goal: string
  branch: string
  worktreePath: string
  status: TaskStatus
  activeStage?: number
  attemptCount: number
  maxAttempts: number
  createdAt: string
  updatedAt: string
  stages?: Stage[]
  attempts?: Attempt[]
  errors?: ErrorEvent[]
  judges?: JudgeRun[]
  artifacts?: Artifact[]
}

export interface TaskEvent {
  id: string
  type: string
  at: string
  data: Record<string, unknown>
}

export interface Artifact {
  id: string
  taskId?: string
  kind: 'LOG' | 'DIFF' | 'VERIFICATION' | 'JUDGE' | 'SYSTEM'
  title: string
  createdAt: string
  content: string
  contentType?: string
  attemptId?: string
  judgeRunId?: string
  metadata?: Record<string, unknown>
}

export interface JudgeRun {
  id: string
  role: 'REQUIREMENT' | 'RISK'
  ordinal: number
  status: 'CREATING' | 'RUNNING' | 'COMPLETED' | 'SESSION_ERROR' | 'ABORTED'
  verdict?: 'PASS' | 'REVISE' | 'BLOCKED' | 'UNPARSEABLE'
  reason?: string
  externalSessionId?: string
  rawOutput?: string
  createdAt: string
  endedAt?: string
}

export interface TaskSessionSummary {
  key: string
  kind: 'IMPLEMENTATION' | 'JUDGE'
  label: string
  localSessionId: string
  externalSessionId?: string
  state: string
  stageId?: string
  attemptId?: string
  createdAt: string
  endedAt?: string
}

export interface TaskSessionActivityPart {
  id: string
  type: 'THINKING' | 'OUTPUT' | 'TOOL'
  label: string
  content: string
  status?: string
}

export interface TaskSessionActivity {
  session: TaskSessionSummary
  remoteState: string
  live: boolean
  observedAt: string
  parts: TaskSessionActivityPart[]
  detail?: string
}

export interface LoopVerifierSpec {
  type: 'PROCESS' | 'FILE_EXISTS' | 'FILE_NOT_EXISTS' | 'GIT_DIFF' | string
  command?: string[]
  path?: string
  requireChanges?: boolean
  allowedPaths?: string[]
  forbiddenPaths?: string[]
  forbidDeletes?: boolean
}

export interface LoopSpec {
  schemaVersion: string
  projectId: string
  goal: string
  context: string
  stages: Array<{
    objective: string
    allowedPaths: string[]
    forbiddenPaths: string[]
    deliverables: string[]
    verifiers: LoopVerifierSpec[]
  }>
  limits: {
    maxStageAttempts: number
    maxTaskAttempts: number
    maxDuration: string
    attemptTimeout: string
  }
}

export interface LoopDraft {
  id: string
  status: 'DRAFTING' | 'DRAFT_READY' | 'CONFIRMED' | 'HANDOFF_FAILED'
  updatedAt: string
  spec: LoopSpec
}

export interface DesignerMessage {
  id: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  deliveryState?: 'PERSISTED' | 'PENDING_HANDOFF' | 'SESSION_ERROR'
  createdAt: string
}

export type DesignerSessionState = 'PENDING_HANDOFF' | 'RUNNING' | 'COMPLETED' | 'SESSION_ERROR'

export interface DesignerSession {
  id: string
  projectId: string
  projectName?: string
  state: DesignerSessionState
  accessMode: 'READ_ONLY'
  readOnly: boolean
  permissionSummary?: string
  updatedAt?: string
  draft?: LoopDraft
  messages: DesignerMessage[]
}

export interface DesignerAppendResult {
  sessionId: string
  state: DesignerSessionState
  persistedMessages: DesignerMessage[]
  notice: string
}
