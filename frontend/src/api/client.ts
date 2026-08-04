import type { Artifact, Attempt, DesignerAppendResult, DesignerMessage, DesignerSession, DesignerSessionState, ErrorEvent, JudgeRun, LoopDraft, LoopSpec, LoopVerifierSpec, Project, RuntimeInfo, Stage, Task, TaskEvent } from '@/types/domain'

const apiBase = import.meta.env.VITE_API_BASE ?? '/api'

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly layer?: string

  constructor(message: string, status: number, extras?: { code?: string; layer?: string }) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = extras?.code
    this.layer = extras?.layer
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBase}${path}`, {
    headers: { Accept: 'application/json', ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...init?.headers },
    ...init,
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({})) as { detail?: string; title?: string; errorCode?: string; errorLayer?: string }
    throw new ApiError(problem.detail ?? problem.title ?? `请求失败 (${response.status})`, response.status, { code: problem.errorCode, layer: problem.errorLayer })
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

type JsonRecord = Record<string, unknown>
const asRecord = (value: unknown): JsonRecord => value !== null && typeof value === 'object' ? value as JsonRecord : {}
const asString = (value: unknown, fallback = ''): string => typeof value === 'string' ? value : fallback
const asNumber = (value: unknown, fallback = 0): number => typeof value === 'number' && Number.isFinite(value) ? value : fallback
const asArray = (value: unknown): unknown[] => Array.isArray(value) ? value : []

function parseVerifier(value: unknown): LoopVerifierSpec {
  // Older UI drafts represented a command as a single string. Keep that input
  // readable, but normalize it immediately to the backend's structured shape.
  if (typeof value === 'string') {
    return { type: 'PROCESS', command: value.trim().split(/\s+/).filter(Boolean) }
  }
  const raw = asRecord(value)
  return {
    type: asString(raw.type, 'PROCESS').toUpperCase(),
    ...(asArray(raw.command).length ? { command: asArray(raw.command).map(String) } : {}),
    ...(asString(raw.path) ? { path: asString(raw.path) } : {}),
    ...(typeof raw.requireChanges === 'boolean' ? { requireChanges: raw.requireChanges } : {}),
    ...(asArray(raw.allowedPaths).length ? { allowedPaths: asArray(raw.allowedPaths).map(String) } : {}),
    ...(asArray(raw.forbiddenPaths).length ? { forbiddenPaths: asArray(raw.forbiddenPaths).map(String) } : {}),
    ...(typeof raw.forbidDeletes === 'boolean' ? { forbidDeletes: raw.forbidDeletes } : {}),
  }
}

function parseLoopSpec(value: unknown): LoopSpec {
  const raw = asRecord(value)
  return {
    schemaVersion: asString(raw.schemaVersion, 'v1'), projectId: asString(raw.projectId), goal: asString(raw.goal), context: asString(raw.context),
    stages: asArray(raw.stages).map((stage) => {
      const item = asRecord(stage)
      return { objective: asString(item.objective), allowedPaths: asArray(item.allowedPaths).map(String), forbiddenPaths: asArray(item.forbiddenPaths).map(String), deliverables: asArray(item.deliverables).map(String), verifiers: asArray(item.verifiers).map(parseVerifier) }
    }),
    limits: { maxStageAttempts: asNumber(asRecord(raw.limits).maxStageAttempts, 3), maxTaskAttempts: asNumber(asRecord(raw.limits).maxTaskAttempts, 12), maxDuration: asString(asRecord(raw.limits).maxDuration, String(asNumber(asRecord(raw.limits).maxDurationSeconds, 7200))), attemptTimeout: asString(asRecord(raw.limits).attemptTimeout, String(asNumber(asRecord(raw.limits).attemptTimeoutSeconds, 1800))) },
  }
}

function normalizeProject(value: unknown): Project {
  const raw = asRecord(value)
  const status = asString(raw.status)
  return { id: asString(raw.id), name: asString(raw.name), rootPath: asString(raw.rootPath), branch: asString(raw.branch) || undefined, description: asString(raw.description) || undefined, status: status === 'INVALID' || status === 'NEEDS_GIT' ? status : 'READY', updatedAt: asString(raw.updatedAt), taskCount: asNumber(raw.taskCount) }
}

function normalizeError(value: unknown): ErrorEvent {
  const raw = asRecord(value)
  const layer = asString(raw.layer)
  return {
    id: asString(raw.id),
    layer: layer === 'FIELD' || layer === 'VERIFICATION' || layer === 'SESSION' || layer === 'TASK' ? layer : 'TASK',
    code: asString(raw.code),
    message: asString(raw.message),
    retryable: Boolean(raw.retryable),
    // ErrorEventRow is exposed by the API as `at`; retain `occurredAt` for
    // backwards compatibility with exported timeline fixtures.
    occurredAt: asString(raw.at) || asString(raw.occurredAt),
    evidenceId: asString(raw.evidenceId) || asString(raw.id),
    sessionId: asString(raw.sessionId) || undefined,
  }
}

function normalizeAttempt(value: unknown): Attempt {
  const raw = asRecord(value)
  const state = asString(raw.status || raw.state)
  const status = state === 'SUCCEEDED' || state === 'VERIFIED' ? 'VERIFIED' : state === 'VERIFICATION_FAILED' || state === 'VERIFIER_FAILED' ? 'VERIFIER_FAILED' : state === 'SESSION_ERROR' ? 'SESSION_ERROR' : state === 'TASK_ERROR' ? 'TASK_ERROR' : state === 'CANCELLED' ? 'CANCELLED' : 'RUNNING'
  const verifications = asArray(raw.verifications).length > 0 ? asArray(raw.verifications) : asArray(raw.verifiers)
  return {
    id: asString(raw.id), ordinal: asNumber(raw.ordinal, 1), stageId: asString(raw.stageId), sessionId: asString(raw.sessionId) || undefined,
    status, startedAt: asString(raw.startedAt) || asString(raw.createdAt), endedAt: asString(raw.endedAt) || undefined,
    summary: asString(raw.summary) || asString(raw.failureKind) || '执行中', errors: asArray(raw.errors).map(normalizeError),
    // TaskController names these persisted records `verifications`, while the
    // UI domain calls the rendered list `verifiers`.
    verifiers: verifications.map((verifier) => {
      const item = asRecord(verifier)
      const evidence = item.evidence
      return {
        id: asString(item.id), name: asString(item.name) || asString(item.type),
        status: asString(item.status) === 'PASS' ? 'PASS' : asString(item.status) === 'FAIL' || asString(item.status) === 'ERROR' ? 'FAIL' : 'PENDING',
        summary: asString(item.summary), output: typeof evidence === 'string' ? evidence : asString(item.output) || undefined,
        elapsedMs: asNumber(item.elapsedMs) || undefined,
      }
    }),
  }
}

function normalizeStage(value: unknown, attempts: Attempt[]): Stage {
  const raw = asRecord(value)
  const state = asString(raw.status || raw.state)
  const status = state === 'SUCCEEDED' ? 'SUCCEEDED' : state === 'RUNNING' ? 'RUNNING' : state === 'VERIFYING' ? 'VERIFYING' : state === 'PAUSED' ? 'PAUSED' : state === 'FAILED' ? 'BLOCKED' : 'PENDING'
  const id = asString(raw.id)
  return { id, ordinal: asNumber(raw.ordinal) + 1, objective: asString(raw.objective), status, attempts: attempts.filter((attempt) => attempt.stageId === id) }
}

function normalizeJudge(value: unknown): JudgeRun {
  const raw = asRecord(value)
  const role = asString(raw.role)
  const state = asString(raw.status || raw.state)
  const verdict = asString(raw.verdict)
  return {
    id: asString(raw.id),
    role: role === 'RISK' ? 'RISK' : 'REQUIREMENT',
    ordinal: asNumber(raw.ordinal, 1),
    status: state === 'CREATING' || state === 'COMPLETED' || state === 'SESSION_ERROR' || state === 'ABORTED' ? state : 'RUNNING',
    verdict: verdict === 'PASS' || verdict === 'REVISE' || verdict === 'BLOCKED' || verdict === 'UNPARSEABLE' ? verdict : undefined,
    reason: asString(raw.reason) || undefined,
    externalSessionId: asString(raw.externalSessionId) || undefined,
    rawOutput: asString(raw.rawOutput) || undefined,
    createdAt: asString(raw.createdAt),
    endedAt: asString(raw.endedAt) || undefined,
  }
}

function normalizeArtifact(value: unknown, taskId: string): Artifact {
  const raw = asRecord(value)
  const backendKind = asString(raw.kind).toUpperCase()
  const kind: Artifact['kind'] = backendKind === 'GIT_DIFF' || backendKind === 'DIFF'
    ? 'DIFF'
    : backendKind === 'VERIFICATION_SUMMARY' || backendKind === 'VERIFICATION'
      ? 'VERIFICATION'
      : backendKind.startsWith('JUDGE')
        ? 'JUDGE'
        : backendKind === 'SYSTEM'
          ? 'SYSTEM'
          : 'LOG'
  return {
    id: asString(raw.id), taskId, kind,
    title: asString(raw.name) || backendKind || 'Artifact',
    createdAt: asString(raw.createdAt), content: asString(raw.content),
    contentType: asString(raw.contentType) || undefined,
    attemptId: asString(raw.attemptId) || undefined,
    judgeRunId: asString(raw.judgeRunId) || undefined,
    metadata: asRecord(raw.metadata),
  }
}

function normalizeTask(value: unknown): Task {
  const raw = asRecord(value)
  const attempts = asArray(raw.attempts).map(normalizeAttempt)
  const stages = asArray(raw.stages).map((stage) => normalizeStage(stage, attempts))
  const taskId = asString(raw.id)
  return { id: taskId, projectId: asString(raw.projectId), projectName: asString(raw.projectName, 'Unknown project'), title: asString(raw.title), goal: asString(raw.goal), branch: asString(raw.branch) || '未创建分支', worktreePath: asString(raw.worktreePath) || '等待创建 worktree', status: asString(raw.status) as Task['status'], activeStage: stages.find((stage) => stage.status === 'RUNNING')?.ordinal, attemptCount: asNumber(raw.attemptCount, attempts.length), maxAttempts: asNumber(raw.maxAttempts, 12), createdAt: asString(raw.createdAt), updatedAt: asString(raw.updatedAt), stages, attempts, errors: asArray(raw.errors).map(normalizeError), judges: asArray(raw.judges).map(normalizeJudge), artifacts: asArray(raw.artifacts).map((artifact) => normalizeArtifact(artifact, taskId)) }
}

function normalizeRuntime(value: unknown): RuntimeInfo {
  const raw = asRecord(value)
  const backendStatus = asString(raw.status)
  const status = backendStatus === 'AVAILABLE' || backendStatus === 'ONLINE'
    ? 'ONLINE'
    : backendStatus === 'STARTING' || backendStatus === 'INCOMPATIBLE'
      ? backendStatus
      : 'OFFLINE'
  return {
    status,
    version: asString(raw.version) || undefined,
    managed: Boolean(raw.managed),
    pid: asNumber(raw.pid) || undefined,
    endpoint: asString(raw.endpoint) || undefined,
    model: asString(raw.model) || undefined,
    checkedAt: asString(raw.checkedAt) || new Date().toISOString(),
  }
}

function normalizeDraft(value: unknown): LoopDraft {
  const raw = asRecord(value)
  let parsed: unknown = raw.spec
  if (typeof raw.specJson === 'string') { try { parsed = JSON.parse(raw.specJson) } catch { parsed = {} } }
  return { id: asString(raw.id), status: asString(raw.status) as LoopDraft['status'], updatedAt: asString(raw.updatedAt), spec: parseLoopSpec(parsed) }
}

function normalizeDesignerMessage(value: unknown): DesignerMessage {
  const raw = asRecord(value)
  const role = asString(raw.role)
  return {
    id: asString(raw.id),
    role: role === 'USER' || role === 'ASSISTANT' || role === 'SYSTEM' ? role : 'SYSTEM',
    content: asString(raw.content),
    deliveryState: ['PERSISTED', 'PENDING_HANDOFF', 'SESSION_ERROR'].includes(asString(raw.deliveryState))
      ? asString(raw.deliveryState) as DesignerMessage['deliveryState']
      : undefined,
    createdAt: asString(raw.createdAt),
  }
}

function normalizeDesignerState(value: unknown): DesignerSessionState {
  const state = asString(value)
  return state === 'RUNNING' || state === 'COMPLETED' || state === 'SESSION_ERROR' ? state : 'PENDING_HANDOFF'
}

function normalizeDesignerSession(value: unknown): DesignerSession {
  const raw = asRecord(value)
  return {
    id: asString(raw.id),
    projectId: asString(raw.projectId),
    projectName: asString(raw.projectName) || undefined,
    state: normalizeDesignerState(raw.state),
    accessMode: 'READ_ONLY',
    readOnly: raw.readOnly !== false,
    permissionSummary: asString(raw.permissionSummary) || undefined,
    updatedAt: asString(raw.updatedAt) || undefined,
    messages: asArray(raw.messages).map(normalizeDesignerMessage),
  }
}

function durationSeconds(value: string, fallback: number): number {
  if (/^\d+$/.test(value)) return Number(value)
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/i.exec(value)
  return match ? Number(match[1] ?? 0) * 3600 + Number(match[2] ?? 0) * 60 + Number(match[3] ?? 0) : fallback
}

function backendLoopSpec(spec: LoopSpec): JsonRecord {
  return {
    schemaVersion: spec.schemaVersion,
    projectId: spec.projectId,
    goal: spec.goal,
    context: spec.context,
    stages: spec.stages.map((stage) => ({
      objective: stage.objective,
      allowedPaths: stage.allowedPaths,
      forbiddenPaths: stage.forbiddenPaths,
      deliverables: stage.deliverables,
      // Preserve verifier type and policy fields exactly. Converting every
      // rule to PROCESS would silently turn FILE_EXISTS/GIT_DIFF into commands.
      verifiers: stage.verifiers.map((verifier) => ({
        type: verifier.type,
        ...(verifier.command?.length ? { command: verifier.command } : {}),
        ...(verifier.path ? { path: verifier.path } : {}),
        ...(typeof verifier.requireChanges === 'boolean' ? { requireChanges: verifier.requireChanges } : {}),
        ...(verifier.allowedPaths?.length ? { allowedPaths: verifier.allowedPaths } : {}),
        ...(verifier.forbiddenPaths?.length ? { forbiddenPaths: verifier.forbiddenPaths } : {}),
        ...(typeof verifier.forbidDeletes === 'boolean' ? { forbidDeletes: verifier.forbidDeletes } : {}),
      })),
    })),
    limits: { maxStageAttempts: spec.limits.maxStageAttempts, maxTaskAttempts: spec.limits.maxTaskAttempts, sessionErrorLimit: 3, stagnationLimit: 2, maxDurationSeconds: durationSeconds(spec.limits.maxDuration, 7200), attemptTimeoutSeconds: durationSeconds(spec.limits.attemptTimeout, 1800), verifierTimeoutSeconds: 600 },
  }
}

export const api = {
  getProjects: async () => (await request<unknown[]>('/projects')).map(normalizeProject),
  createProject: async (input: Pick<Project, 'name' | 'rootPath' | 'description'>) => normalizeProject(await request<unknown>('/projects', { method: 'POST', body: JSON.stringify({ name: input.name, rootPath: input.rootPath }) })),
  getTasks: async () => (await request<unknown[]>('/tasks')).map(normalizeTask),
  getTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}`)),
  pauseTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/pause`, { method: 'POST' })),
  resumeTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/resume`, { method: 'POST' })),
  cancelTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/cancel`, { method: 'POST' })),
  getRuntime: async () => normalizeRuntime(await request<unknown>('/runtime/opencode')),
  restartRuntime: async () => normalizeRuntime(await request<unknown>('/runtime/opencode/restart', { method: 'POST' })),
  createDraft: async (spec: LoopSpec) => normalizeDraft(await request<unknown>('/loop-drafts', { method: 'POST', body: JSON.stringify({ spec: backendLoopSpec(spec) }) })),
  getDraft: async (id: string) => normalizeDraft(await request<unknown>(`/loop-drafts/${encodeURIComponent(id)}`)),
  updateDraft: async (id: string, spec: LoopDraft['spec']) => normalizeDraft(await request<unknown>(`/loop-drafts/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ spec: backendLoopSpec(spec) }) })),
  confirmDraft: async (id: string) => { const task = asRecord(await request<unknown>(`/loop-drafts/${encodeURIComponent(id)}/confirm`, { method: 'POST' })); return { taskId: asString(task.taskId) } },
  createDesignerSession: async (projectId: string, initialMessage?: string) => normalizeDesignerSession(await request<unknown>('/designer-sessions', { method: 'POST', body: JSON.stringify({ projectId, ...(initialMessage ? { initialMessage } : {}) }) })),
  getDesignerSession: async (id: string) => normalizeDesignerSession(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}`)),
  getDesignerMessages: async (id: string) => (await request<unknown[]>(`/designer-sessions/${encodeURIComponent(id)}/messages`)).map(normalizeDesignerMessage),
  sendDesignerMessage: async (id: string, content: string): Promise<DesignerAppendResult> => {
    const raw = asRecord(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/messages`, { method: 'POST', body: JSON.stringify({ content }) }))
    return {
      sessionId: asString(raw.sessionId),
      state: normalizeDesignerState(raw.state),
      persistedMessages: asArray(raw.persistedMessages).map(normalizeDesignerMessage),
      notice: asString(raw.notice),
    }
  },
}

export interface TaskEventStream {
  close: () => void
}

export function subscribeTaskEvents(taskId: string, onEvent: (event: TaskEvent) => void, onState: (state: 'connected' | 'reconnecting') => void): TaskEventStream {
  const source = new EventSource(`${apiBase}/tasks/${encodeURIComponent(taskId)}/events`)
  source.onopen = () => onState('connected')
  source.onmessage = (message) => {
    try {
      const body = JSON.parse(message.data) as Omit<TaskEvent, 'id'>
      onEvent({ ...body, id: message.lastEventId || crypto.randomUUID() })
    } catch {
      // Ignore malformed individual messages; the REST snapshot remains authoritative.
    }
  }
  // Do not close/recreate here: the EventSource implementation reconnects itself and
  // sends the standard Last-Event-ID header from the most recently delivered event.
  source.onerror = () => onState('reconnecting')
  return { close: () => source.close() }
}
