import type { AppSettings, Artifact, Attempt, AutomationImportPreview, AutomationImportResult, AutomationRule, AutomationRuleMutation, AutomationRun, AutomationRunFeed, AvailableModel, BrowserAssertion, CommitMessageSuggestion, CreateAutomationRuleInput, DesignerAppendResult, DesignerMessage, DesignerSession, DesignerSessionState, DesignerStreamEvent, DirectorySelection, ErrorEvent, InsightsSnapshot, Interaction, InteractionAction, JudgeRun, LocalSyncConflictContent, LocalSyncConflictFile, LocalSyncConflictSession, LocalSyncResolution, LoopDraft, LoopSpec, LoopSpecTemplate, LoopSpecTemplateVersion, LoopVerifierSpec, MergeRequestDraft, Project, ProjectConventionDraft, ProjectConventionSnapshot, RecoveryDraft, RecoveryMode, RuntimeInfo, SessionCheckpoint, SessionForkResult, SessionRevertResult, SessionSummaryResult, SessionTodo, Stage, Task, TaskDesignHistory, TaskDiffPreview, TaskEvent, TaskInsight, TaskPublicationStatus, TaskSessionActivity, TaskSessionActivityPart, TaskSessionPendingQuestion, TaskSessionSummary, UsageAggregate } from '@/types/domain'

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
    ...init,
    // Keep caller headers (for example the local-UI guard) without letting
    // RequestInit overwrite the JSON content type assembled here.
    headers: { Accept: 'application/json', ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...init?.headers },
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
const asNullableNumber = (value: unknown): number | null => typeof value === 'number' && Number.isFinite(value) ? value : null
const asArray = (value: unknown): unknown[] => Array.isArray(value) ? value : []

function requiredString(raw: JsonRecord, field: string, context: string): string {
  const value = asString(raw[field])
  if (!value) throw new TypeError(`${context}.${field} is required`)
  return value
}

function parseBrowserAssertion(value: unknown): BrowserAssertion {
  const raw = asRecord(value)
  const type = asString(raw.type).toUpperCase()
  const selector = requiredString(raw, 'selector', 'BROWSER assertion')
  if (type === 'EXISTS' || type === 'VISIBLE') return { type, selector }
  if (type === 'TEXT_CONTAINS') return { type, selector, value: requiredString(raw, 'value', type) }
  if (type === 'COUNT') {
    if (typeof raw.expectedCount !== 'number' || !Number.isInteger(raw.expectedCount) || raw.expectedCount < 0) {
      throw new TypeError('COUNT.expectedCount must be a non-negative integer')
    }
    return { type, selector, expectedCount: raw.expectedCount }
  }
  if (type === 'ATTRIBUTE_EQUALS') {
    return { type, selector, attribute: requiredString(raw, 'attribute', type), value: requiredString(raw, 'value', type) }
  }
  throw new TypeError(`Unsupported BROWSER assertion type: ${type || '<missing>'}`)
}

function parseVerifier(value: unknown): LoopVerifierSpec {
  // Older UI drafts represented a command as a single string. Keep that input
  // readable, but normalize it immediately to the backend's structured shape.
  if (typeof value === 'string') {
    const command = value.trim().split(/\s+/).filter(Boolean)
    if (!command.length) throw new TypeError('PROCESS.command is required')
    return { type: 'PROCESS', command }
  }
  const raw = asRecord(value)
  const type = asString(raw.type, 'PROCESS').toUpperCase()
  const rawHttpMethod = asString(raw.httpMethod).toUpperCase()
  if (rawHttpMethod && rawHttpMethod !== 'GET' && rawHttpMethod !== 'HEAD') throw new TypeError(`Unsupported HTTP method: ${rawHttpMethod}`)
  const httpMethod: 'GET' | 'HEAD' | undefined = rawHttpMethod === 'GET' || rawHttpMethod === 'HEAD' ? rawHttpMethod : undefined
  const rawMatchMode = asString(raw.matchMode).toUpperCase()
  if (rawMatchMode && rawMatchMode !== 'EXISTS' && rawMatchMode !== 'EXACT' && rawMatchMode !== 'CONTAINS') throw new TypeError(`Unsupported JSON match mode: ${rawMatchMode}`)
  const matchMode: 'EXISTS' | 'EXACT' | 'CONTAINS' | undefined = rawMatchMode === 'EXISTS' || rawMatchMode === 'EXACT' || rawMatchMode === 'CONTAINS' ? rawMatchMode : undefined
  const common = {
    ...(httpMethod ? { httpMethod } : {}),
    ...(typeof raw.requireChanges === 'boolean' ? { requireChanges: raw.requireChanges } : {}),
    ...(asArray(raw.allowedPaths).length ? { allowedPaths: asArray(raw.allowedPaths).map(String) } : {}),
    ...(asArray(raw.forbiddenPaths).length ? { forbiddenPaths: asArray(raw.forbiddenPaths).map(String) } : {}),
    ...(typeof raw.forbidDeletes === 'boolean' ? { forbidDeletes: raw.forbidDeletes } : {}),
    ...(asString(raw.outputContains) ? { outputContains: asString(raw.outputContains) } : {}),
    ...(typeof raw.expectedValue === 'string' ? { expectedValue: raw.expectedValue } : {}),
    ...(matchMode ? { matchMode } : {}),
    ...(typeof raw.expectedRowCount === 'number' ? { expectedRowCount: raw.expectedRowCount } : {}),
  }
  switch (type) {
    case 'PROCESS': {
      const command = asArray(raw.command).length ? asArray(raw.command) : asArray(raw.argv)
      if (!command.length) throw new TypeError('PROCESS.command is required')
      return { ...common, type, command: command.map(String) }
    }
    case 'FILE_EXISTS': return { ...common, type, path: requiredString(raw, 'path', type) }
    case 'FILE_NOT_EXISTS': return { ...common, type, path: requiredString(raw, 'path', type) }
    case 'GIT_DIFF': return { ...common, type }
    case 'HTTP_STATUS': {
      if (typeof raw.expectedStatus !== 'number') throw new TypeError('HTTP_STATUS.expectedStatus is required')
      return { ...common, type, url: requiredString(raw, 'url', type), expectedStatus: raw.expectedStatus }
    }
    case 'JSON_PATH': return { ...common, type, url: requiredString(raw, 'url', type), jsonPath: requiredString(raw, 'jsonPath', type) }
    case 'FILE_CONTENT': return { ...common, type, path: requiredString(raw, 'path', type), expectedContent: requiredString(raw, 'expectedContent', type) }
    case 'FILE_HASH': return { ...common, type, path: requiredString(raw, 'path', type), expectedSha256: requiredString(raw, 'expectedSha256', type) }
    case 'JUNIT_XML': return { ...common, type, path: requiredString(raw, 'path', type) }
    case 'BROWSER': {
      const assertions = asArray(raw.assertions)
      if (!assertions.length) throw new TypeError('BROWSER.assertions requires at least one assertion')
      return { ...common, type, url: requiredString(raw, 'url', type), assertions: assertions.map(parseBrowserAssertion) }
    }
    case 'DATABASE_QUERY': return { ...common, type, path: requiredString(raw, 'path', type), sql: requiredString(raw, 'sql', type) }
    default: throw new TypeError(`Unsupported verifier type: ${type || '<missing>'}`)
  }
}

function parseLoopSpec(value: unknown): LoopSpec {
  const raw = asRecord(value)
  const limits = asRecord(raw.limits)
  const model = asRecord(raw.model)
  const sessionPolicy = asRecord(raw.sessionPolicy)
  return {
    schemaVersion: asString(raw.schemaVersion, 'v1'), projectId: asString(raw.projectId), goal: asString(raw.goal), context: asString(raw.context),
    stages: asArray(raw.stages).map((stage) => {
      const item = asRecord(stage)
      return { objective: asString(item.objective), allowedPaths: asArray(item.allowedPaths).map(String), forbiddenPaths: asArray(item.forbiddenPaths).map(String), deliverables: asArray(item.deliverables).map(String), verifiers: asArray(item.verifiers).map(parseVerifier) }
    }),
    limits: {
      maxStageAttempts: asNumber(limits.maxStageAttempts, 3),
      maxTaskAttempts: asNumber(limits.maxTaskAttempts, 12),
      sessionErrorLimit: asNumber(limits.sessionErrorLimit, 3),
      stagnationLimit: asNumber(limits.stagnationLimit, 2),
      maxDuration: asString(limits.maxDuration, String(asNumber(limits.maxDurationSeconds, 7200))),
      attemptTimeout: asString(limits.attemptTimeout, String(asNumber(limits.attemptTimeoutSeconds, 1800))),
      verifierTimeout: asString(limits.verifierTimeout, String(asNumber(limits.verifierTimeoutSeconds, 600))),
    },
    model: {
      ...(asString(model.providerId) ? { providerId: asString(model.providerId) } : {}),
      ...(asString(model.modelId) ? { modelId: asString(model.modelId) } : {}),
      ...(typeof model.thinking === 'boolean' ? { thinking: model.thinking } : {}),
    },
    sessionPolicy: {
      reuseHealthySession: typeof sessionPolicy.reuseHealthySession === 'boolean' ? sessionPolicy.reuseHealthySession : true,
      createFreshOnVerifierFailure: typeof sessionPolicy.createFreshOnVerifierFailure === 'boolean' ? sessionPolicy.createFreshOnVerifierFailure : true,
    },
    ...(typeof raw.nextAttemptPromptTemplate === 'string' ? { nextAttemptPromptTemplate: raw.nextAttemptPromptTemplate } : {}),
    budget: {
      ...(typeof asRecord(raw.budget).maxTotalTokens === 'number' ? { maxTotalTokens: asNumber(asRecord(raw.budget).maxTotalTokens) } : {}),
      ...(asString(asRecord(raw.budget).maxCostAmount) ? { maxCostAmount: asString(asRecord(raw.budget).maxCostAmount) } : {}),
      ...(asString(asRecord(raw.budget).currency) ? { currency: asString(asRecord(raw.budget).currency).toUpperCase() } : {}),
    },
  }
}

function normalizeProject(value: unknown): Project {
  const raw = asRecord(value)
  const status = asString(raw.status)
  const executionMode = asString(raw.executionMode)
  return { id: asString(raw.id), name: asString(raw.name), rootPath: asString(raw.rootPath), branch: asString(raw.branch) || undefined, description: asString(raw.description) || undefined, status: status === 'INVALID' || status === 'NEEDS_GIT' ? status : 'READY', executionMode: executionMode === 'WORKTREE' || executionMode === 'DIRECT' || executionMode === 'UNAVAILABLE' ? executionMode : undefined, updatedAt: asString(raw.updatedAt), taskCount: asNumber(raw.taskCount) }
}

function normalizeProjectConvention(value: unknown): ProjectConventionDraft {
  const raw = asRecord(value)
  const state = asString(raw.state)
  return {
    id: asString(raw.id),
    projectId: asString(raw.projectId),
    state: state === 'READY' || state === 'APPLIED' || state === 'FAILED' ? state : 'RUNNING',
    operation: asString(raw.operation) === 'UPDATE' ? 'UPDATE' : 'CREATE',
    readOnlyGeneration: raw.readOnlyGeneration === true,
    content: asString(raw.content) || undefined,
    error: asString(raw.error) || undefined,
    updatedAt: asString(raw.updatedAt),
  }
}

function normalizeProjectConventionSnapshot(value: unknown): ProjectConventionSnapshot {
  const raw = asRecord(value)
  return {
    projectId: asString(raw.projectId),
    exists: raw.exists === true,
    loopperManaged: raw.loopperManaged === true,
    content: asString(raw.content),
  }
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
      const evidenceRecord = asRecord(evidence)
      return {
        id: asString(item.id), name: asString(item.name) || asString(item.type),
        status: asString(item.status) === 'PASS' ? 'PASS' : asString(item.status) === 'FAIL' || asString(item.status) === 'ERROR' ? 'FAIL' : 'PENDING',
        summary: asString(item.summary),
        output: typeof evidence === 'string' ? evidence : asString(evidenceRecord.output) || asString(item.output) || undefined,
        evidence: Object.keys(evidenceRecord).length ? evidenceRecord : undefined,
        elapsedMs: asNumber(item.elapsedMs) || asNumber(evidenceRecord.elapsedMs) || undefined,
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
    status: state === 'CREATING' || state === 'COMPLETED' || state === 'SESSION_ERROR' || state === 'ABORTED'
      || state === 'FAILED' || state === 'TIMED_OUT' ? state : 'RUNNING',
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
  return { id: taskId, projectId: asString(raw.projectId), projectName: asString(raw.projectName, 'Unknown project'), title: asString(raw.title), goal: asString(raw.goal), branch: asString(raw.branch) || '等待选择执行模式', worktreePath: asString(raw.worktreePath) || '等待准备执行目录', status: asString(raw.status) as Task['status'], waitingReasonCode: asString(raw.waitingReasonCode) || undefined, loopRetryAvailable: raw.loopRetryAvailable === true, hasDesignHistory: raw.hasDesignHistory === true, archived: raw.archived === true, activeStage: stages.find((stage) => stage.status === 'RUNNING')?.ordinal, attemptCount: asNumber(raw.attemptCount, attempts.length), maxAttempts: asNumber(raw.maxAttempts, 12), createdAt: asString(raw.createdAt), updatedAt: asString(raw.updatedAt), stages, attempts, errors: asArray(raw.errors).map(normalizeError), judges: asArray(raw.judges).map(normalizeJudge), artifacts: asArray(raw.artifacts).map((artifact) => normalizeArtifact(artifact, taskId)) }
}

function normalizeTaskDesignHistory(value: unknown): TaskDesignHistory {
  const raw = asRecord(value)
  const session = asRecord(raw.designerSession)
  return {
    taskId: asString(raw.taskId),
    taskTitle: asString(raw.taskTitle),
    projectName: asString(raw.projectName, 'Unknown project'),
    draft: normalizeDraft(raw.draft),
    designerSession: raw.designerSession ? {
      id: asString(session.id),
      state: normalizeDesignerState(session.state),
      accessMode: 'READ_ONLY',
      createdAt: asString(session.createdAt),
      updatedAt: asString(session.updatedAt),
      messages: asArray(session.messages).map(normalizeDesignerMessage),
    } : undefined,
  }
}

function normalizeTaskSession(value: unknown): TaskSessionSummary {
  const raw = asRecord(value)
  return {
    key: asString(raw.key),
    kind: asString(raw.kind) === 'JUDGE' ? 'JUDGE' : 'IMPLEMENTATION',
    label: asString(raw.label, 'Session'),
    localSessionId: asString(raw.localSessionId),
    externalSessionId: asString(raw.externalSessionId) || undefined,
    state: asString(raw.state, 'UNKNOWN'),
    stageId: asString(raw.stageId) || undefined,
    stageOrdinal: raw.stageOrdinal == null ? undefined : asNumber(raw.stageOrdinal),
    stageObjective: asString(raw.stageObjective) || undefined,
    attemptId: asString(raw.attemptId) || undefined,
    createdAt: asString(raw.createdAt),
    endedAt: asString(raw.endedAt) || undefined,
  }
}

function normalizeTaskSessionPart(value: unknown): TaskSessionActivityPart {
  const raw = asRecord(value)
  const type = asString(raw.type)
  return {
    id: asString(raw.id),
    type: type === 'THINKING' || type === 'TOOL' ? type : 'OUTPUT',
    label: asString(raw.label, type === 'THINKING' ? 'Thinking' : type === 'TOOL' ? '工具调用' : '模型输出'),
    content: asString(raw.content),
    status: asString(raw.status) || undefined,
    startedAt: asString(raw.startedAt) || undefined,
  }
}

function normalizeTaskSessionQuestion(value: unknown): TaskSessionPendingQuestion {
  const raw = asRecord(value)
  return {
    id: asString(raw.id),
    questions: asArray(raw.questions).map((value) => {
      const question = asRecord(value)
      return {
        question: asString(question.question),
        header: asString(question.header),
        options: asArray(question.options).map((value) => {
          const option = asRecord(value)
          return { label: asString(option.label), description: asString(option.description) }
        }),
        multiple: question.multiple === true,
        custom: question.custom !== false,
      }
    }),
  }
}

function normalizeTaskSessionActivity(value: unknown): TaskSessionActivity {
  const raw = asRecord(value)
  return {
    session: normalizeTaskSession(raw.session),
    remoteState: asString(raw.remoteState, 'UNKNOWN'),
    live: raw.live === true,
    observedAt: asString(raw.observedAt),
    parts: asArray(raw.parts).map(normalizeTaskSessionPart),
    pendingQuestions: asArray(raw.pendingQuestions).map(normalizeTaskSessionQuestion),
    detail: asString(raw.detail) || undefined,
  }
}

function normalizeInteraction(value: unknown): Interaction {
  const raw = asRecord(value)
  const payload = asRecord(raw.payload)
  const state = asString(raw.state) as Interaction['state']
  const common = {
    id: asString(raw.id),
    taskId: asString(raw.taskId) || undefined,
    designerSessionId: asString(raw.designerSessionId) || undefined,
    sessionId: asString(raw.sessionId) || undefined,
    externalRequestId: asString(raw.externalRequestId),
    state,
    version: asNumber(raw.version),
    createdAt: asString(raw.createdAt),
    updatedAt: asString(raw.updatedAt),
    resolvedAction: asString(raw.resolvedAction) as InteractionAction || undefined,
    resolvedAt: asString(raw.resolvedAt) || undefined,
  }
  if (asString(raw.kind) === 'PERMISSION') {
    return {
      ...common,
      kind: 'PERMISSION',
      payload: {
        permission: asString(payload.permission),
        patterns: asArray(payload.patterns).map(String),
        title: asString(payload.title) || undefined,
        metadata: asRecord(payload.metadata),
        hardDenied: payload.hardDenied === true,
        hardDenyReason: asString(payload.hardDenyReason) || undefined,
      },
    }
  }
  return { ...common, kind: 'QUESTION', payload: { questions: normalizeTaskSessionQuestion({ questions: payload.questions }).questions } }
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

function normalizeSettings(value: unknown): AppSettings {
  const raw = asRecord(value)
  return {
    cliPath: asString(raw.cliPath, 'opencode'),
    allowedRoot: asString(raw.allowedRoot),
    provider: asString(raw.provider),
    model: asString(raw.model),
    maxTaskAttempts: asNumber(raw.maxTaskAttempts, 12),
    timeoutMinutes: asNumber(raw.timeoutMinutes, 30),
    autoApprove: raw.autoApprove === true,
    updatedAt: asString(raw.updatedAt) || undefined,
  }
}

function normalizeAvailableModel(value: unknown): AvailableModel {
  const raw = asRecord(value)
  const provider = asString(raw.provider)
  const model = asString(raw.model)
  return { id: asString(raw.id, `${provider}/${model}`), provider, model, label: asString(raw.label, `${provider} / ${model}`) }
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
    draft: raw.draft ? normalizeDraft(raw.draft) : undefined,
    messages: asArray(raw.messages).map(normalizeDesignerMessage),
    pendingQuestions: asArray(raw.pendingQuestions).map(normalizeTaskSessionQuestion),
  }
}

function normalizeDesignerStreamEvent(value: unknown): DesignerStreamEvent {
  const raw = asRecord(value)
  const type = asString(raw.type).toUpperCase()
  return {
    sequence: asNumber(raw.sequence),
    sessionId: asString(raw.sessionId),
    type: ['SNAPSHOT', 'STATUS', 'PARTIAL', 'COMPLETED', 'ERROR'].includes(type) ? type as DesignerStreamEvent['type'] : 'STATUS',
    state: normalizeDesignerState(raw.state),
    remoteState: asString(raw.remoteState) || undefined,
    runtimeConnected: raw.runtimeConnected === true,
    content: asString(raw.content),
    detail: asString(raw.detail),
    at: asString(raw.at) || new Date().toISOString(),
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
        ...(verifier.outputContains ? { outputContains: verifier.outputContains } : {}),
        ...(verifier.url ? { url: verifier.url } : {}),
        ...(verifier.httpMethod ? { httpMethod: verifier.httpMethod } : {}),
        ...(typeof verifier.expectedStatus === 'number' ? { expectedStatus: verifier.expectedStatus } : {}),
        ...(verifier.jsonPath ? { jsonPath: verifier.jsonPath } : {}),
        ...(verifier.expectedValue ? { expectedValue: verifier.expectedValue } : {}),
        ...(verifier.matchMode ? { matchMode: verifier.matchMode } : {}),
        ...(verifier.expectedContent ? { expectedContent: verifier.expectedContent } : {}),
        ...(verifier.expectedSha256 ? { expectedSha256: verifier.expectedSha256 } : {}),
        ...(verifier.sql ? { sql: verifier.sql } : {}),
        ...(typeof verifier.expectedRowCount === 'number' ? { expectedRowCount: verifier.expectedRowCount } : {}),
        ...(verifier.assertions?.length ? { assertions: verifier.assertions } : {}),
      })),
    })),
    limits: {
      maxStageAttempts: spec.limits.maxStageAttempts,
      maxTaskAttempts: spec.limits.maxTaskAttempts,
      sessionErrorLimit: spec.limits.sessionErrorLimit ?? 3,
      stagnationLimit: spec.limits.stagnationLimit ?? 2,
      maxDurationSeconds: durationSeconds(spec.limits.maxDuration, 7200),
      attemptTimeoutSeconds: durationSeconds(spec.limits.attemptTimeout, 1800),
      verifierTimeoutSeconds: durationSeconds(spec.limits.verifierTimeout ?? '600', 600),
    },
    model: spec.model ?? {},
    sessionPolicy: spec.sessionPolicy ?? { reuseHealthySession: true, createFreshOnVerifierFailure: true },
    nextAttemptPromptTemplate: spec.nextAttemptPromptTemplate || null,
    budget: spec.budget ?? {},
  }
}

function normalizeTaskDiffPreview(value: unknown): TaskDiffPreview {
  const raw = asRecord(value)
  return {
    path: asString(raw.path),
    changeType: raw.changeType === 'NEW' ? 'NEW' : 'MODIFIED',
    patch: asString(raw.patch),
    truncated: raw.truncated === true,
  }
}

function normalizeTaskPublication(value: unknown): TaskPublicationStatus {
  const raw = asRecord(value)
  const state = asString(raw.state)
  const provider = asString(raw.provider)
  return {
    state: ['UNAVAILABLE', 'NO_CHANGES', 'READY', 'COMMITTED', 'PUSHED', 'SYNCED_LOCAL', 'LOCAL_SYNC_CONFLICT'].includes(state) ? state as TaskPublicationStatus['state'] : 'UNAVAILABLE',
    available: raw.available === true,
    reason: asString(raw.reason) || undefined,
    branch: asString(raw.branch) || undefined,
    remoteName: asString(raw.remoteName) || undefined,
    remoteUrl: asString(raw.remoteUrl) || undefined,
    commitSha: asString(raw.commitSha) || undefined,
    commitMessage: asString(raw.commitMessage) || undefined,
    targetBranch: asString(raw.targetBranch) || undefined,
    targetBranches: asArray(raw.targetBranches).map(String),
    provider: provider === 'GITLAB' || provider === 'GITHUB' ? provider : 'UNKNOWN',
    upstream: asString(raw.upstream) || undefined,
    hasChanges: raw.hasChanges === true,
    conflictSessionId: asString(raw.conflictSessionId) || undefined,
    conflictCount: asNumber(raw.conflictCount),
    resolvedCount: asNumber(raw.resolvedCount),
  }
}

function normalizeLocalSyncSession(value: unknown): LocalSyncConflictSession {
  const raw = asRecord(value)
  const state = asString(raw.state) as LocalSyncConflictSession['state']
  return {
    id: requiredString(raw, 'id', 'LocalSyncConflictSession'), taskId: requiredString(raw, 'taskId', 'LocalSyncConflictSession'),
    state, sourceRoot: asString(raw.sourceRoot), sourceHead: asString(raw.sourceHead), taskCommit: asString(raw.taskCommit),
    baselineCommit: asString(raw.baselineCommit), conflictCount: asNumber(raw.conflictCount), resolvedCount: asNumber(raw.resolvedCount),
    errorMessage: asString(raw.errorMessage) || undefined, backupDir: asString(raw.backupDir) || undefined,
    verificationEvidence: asString(raw.verificationEvidence) || undefined, createdAt: asString(raw.createdAt),
    updatedAt: asString(raw.updatedAt), version: asNumber(raw.version),
  }
}

function normalizeLocalSyncFile(value: unknown): LocalSyncConflictFile {
  const raw = asRecord(value)
  return {
    path: requiredString(raw, 'path', 'LocalSyncConflictFile'), sourcePath: asString(raw.sourcePath), taskPath: asString(raw.taskPath),
    changeType: asString(raw.changeType) as LocalSyncConflictFile['changeType'], contentType: asString(raw.contentType) as LocalSyncConflictFile['contentType'],
    resolution: (asString(raw.resolution) || undefined) as LocalSyncResolution | undefined, resolved: raw.resolved === true,
    hasAiSuggestion: raw.hasAiSuggestion === true, baseHash: asString(raw.baseHash), sourceHash: asString(raw.sourceHash),
    taskHash: asString(raw.taskHash), version: asNumber(raw.version),
  }
}

function normalizeLocalSyncContent(value: unknown): LocalSyncConflictContent {
  const raw = asRecord(value)
  return {
    path: requiredString(raw, 'path', 'LocalSyncConflictContent'), contentType: asString(raw.contentType) as LocalSyncConflictContent['contentType'],
    baseContent: typeof raw.baseContent === 'string' ? raw.baseContent : undefined,
    sourceContent: typeof raw.sourceContent === 'string' ? raw.sourceContent : undefined,
    taskContent: typeof raw.taskContent === 'string' ? raw.taskContent : undefined,
    mergedContent: typeof raw.mergedContent === 'string' ? raw.mergedContent : undefined,
    baseHash: asString(raw.baseHash), sourceHash: asString(raw.sourceHash), taskHash: asString(raw.taskHash),
    resolution: (asString(raw.resolution) || undefined) as LocalSyncResolution | undefined,
    aiSuggestion: typeof raw.aiSuggestion === 'string' ? raw.aiSuggestion : undefined,
    aiEligible: raw.aiEligible === true, version: asNumber(raw.version),
  }
}

function normalizeCommitSuggestion(value: unknown): CommitMessageSuggestion {
  const raw = asRecord(value)
  return { subject: asString(raw.subject), aiGenerated: raw.aiGenerated === true }
}

function normalizeMergeRequestDraft(value: unknown): MergeRequestDraft {
  const raw = asRecord(value)
  return {
    provider: asString(raw.provider) === 'GITHUB' ? 'GITHUB' : 'GITLAB',
    sourceBranch: asString(raw.sourceBranch),
    targetBranch: asString(raw.targetBranch),
    title: asString(raw.title),
    description: asString(raw.description),
    creationUrl: asString(raw.creationUrl),
  }
}

export function normalizeAutomationRule(value: unknown): AutomationRule {
  const raw = asRecord(value)
  const state: 'DISABLED' | 'ENABLED' = raw.state === 'ENABLED' ? 'ENABLED' : 'DISABLED'
  const approvalMode: 'REVIEW_REQUIRED' | 'AUTO_START' = raw.approvalMode === 'AUTO_START' ? 'AUTO_START' : 'REVIEW_REQUIRED'
  const base = {
    id: requiredString(raw, 'id', 'AutomationRule'),
    name: requiredString(raw, 'name', 'AutomationRule'),
    projectId: requiredString(raw, 'projectId', 'AutomationRule'),
    templateVersionId: requiredString(raw, 'templateVersionId', 'AutomationRule'),
    state,
    approvalMode,
    updatedAt: asString(raw.updatedAt),
    version: asNumber(raw.version),
  }
  const config = asRecord(raw.triggerConfig)
  switch (asString(raw.triggerType).toUpperCase()) {
    case 'MANUAL': return { ...base, triggerType: 'MANUAL', triggerConfig: {} }
    case 'CRON': return { ...base, triggerType: 'CRON', triggerConfig: {
      expression: requiredString(config, 'expression', 'CRON triggerConfig'),
      timezone: requiredString(config, 'timezone', 'CRON triggerConfig'),
    } }
    case 'GIT_HEAD_CHANGED': return { ...base, triggerType: 'GIT_HEAD_CHANGED', triggerConfig: {
      ...(asString(config.branch) ? { branch: asString(config.branch) } : {}),
    } }
    case 'WEBHOOK': return { ...base, triggerType: 'WEBHOOK', triggerConfig: {} }
    default: throw new TypeError(`Unsupported automation trigger type: ${asString(raw.triggerType) || '<missing>'}`)
  }
}

export function backendAutomationRule(rule: AutomationRule): JsonRecord {
  return {
    id: rule.id,
    name: rule.name,
    projectId: rule.projectId,
    templateVersionId: rule.templateVersionId,
    triggerType: rule.triggerType,
    triggerConfig: rule.triggerConfig,
    state: rule.state,
    approvalMode: rule.approvalMode,
    updatedAt: rule.updatedAt,
    version: rule.version,
  }
}

function backendCreateAutomationRule(input: CreateAutomationRuleInput): JsonRecord {
  return {
    name: input.name,
    projectId: input.projectId,
    templateVersionId: input.templateVersionId,
    triggerType: input.triggerType,
    triggerConfig: input.triggerConfig,
  }
}

function normalizeTemplateVersion(value: unknown, fallbackTemplateId = ''): LoopSpecTemplateVersion {
  const raw = asRecord(value)
  let parsed: unknown = raw.spec
  if (typeof raw.specJson === 'string') {
    try { parsed = JSON.parse(raw.specJson) } catch { parsed = {} }
  }
  return {
    id: requiredString(raw, 'id', 'TemplateVersion'),
    templateId: asString(raw.templateId, fallbackTemplateId),
    versionNumber: asNumber(raw.versionNumber),
    spec: parseLoopSpec(parsed),
    specSha256: requiredString(raw, 'specSha256', 'TemplateVersion'),
    immutable: raw.immutable === true,
    autoStartApproved: raw.autoStartApproved === true,
    createdAt: asString(raw.createdAt),
  }
}

function normalizeTemplate(value: unknown): LoopSpecTemplate {
  const raw = asRecord(value)
  const id = requiredString(raw, 'id', 'Template')
  const state = asString(raw.state)
  return {
    id,
    name: requiredString(raw, 'name', 'Template'),
    description: asString(raw.description),
    state: state === 'ARCHIVED' ? 'ARCHIVED' : 'ACTIVE',
    versions: asArray(raw.versions).map(version => normalizeTemplateVersion(version, id)),
    updatedAt: asString(raw.updatedAt),
    version: asNumber(raw.version),
  }
}

function normalizeAutomationRun(value: unknown): AutomationRun {
  const raw = asRecord(value)
  const evidence = asRecord(raw.evidence)
  const triggerType = asString(raw.triggerType).toUpperCase()
  if (!['MANUAL', 'CRON', 'GIT_HEAD_CHANGED', 'WEBHOOK'].includes(triggerType)) {
    throw new TypeError(`Unsupported automation run trigger: ${triggerType || '<missing>'}`)
  }
  const state = asString(raw.state)
  if (!['DETECTED', 'REVIEW_REQUIRED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'].includes(state)) {
    throw new TypeError(`Unsupported automation run state: ${state || '<missing>'}`)
  }
  return {
    id: requiredString(raw, 'id', 'AutomationRun'),
    ruleId: requiredString(raw, 'ruleId', 'AutomationRun'),
    triggerType: triggerType as AutomationRun['triggerType'],
    state: state as AutomationRun['state'],
    draftId: asString(raw.draftId) || undefined,
    taskId: asString(raw.taskId) || undefined,
    evidence,
    queueState: asString(raw.queueState) || (state === 'QUEUED' ? 'QUEUED' : undefined),
    error: asString(raw.error) || asString(evidence.error) || undefined,
    detectedAt: asString(raw.detectedAt),
    startedAt: asString(raw.startedAt) || undefined,
    endedAt: asString(raw.endedAt) || undefined,
  }
}

function normalizeAutomationRuleMutation(value: unknown): AutomationRuleMutation {
  const raw = asRecord(value)
  return {
    rule: normalizeAutomationRule(raw.rule),
    webhookToken: asString(raw.webhookToken) || undefined,
    webhookPath: asString(raw.webhookPath) || undefined,
  }
}

function normalizeAutomationImportPreview(value: unknown): AutomationImportPreview {
  const raw = asRecord(value)
  const exported = asRecord(raw.exported)
  return {
    previewId: requiredString(raw, 'previewId', 'AutomationImportPreview'),
    templates: asArray(raw.templates ?? exported.templates).map(normalizeTemplate),
    rules: asArray(raw.rules ?? exported.rules).map(normalizeAutomationRule),
    expiresAt: asString(raw.expiresAt) || undefined,
  }
}

function normalizeRecovery(value: unknown): RecoveryDraft {
  const raw = asRecord(value)
  const mode = asString(raw.mode).toUpperCase()
  if (mode !== 'FROM_FAILED_STAGE' && mode !== 'ALL_STAGES' && mode !== 'VERIFY_ONLY' && mode !== 'REWORK_ALL_STAGES') {
    throw new TypeError(`Unsupported recovery mode: ${mode || '<missing>'}`)
  }
  return {
    taskId: requiredString(raw, 'taskId', 'Recovery'),
    parentTaskId: requiredString(raw, 'parentTaskId', 'Recovery'),
    mode,
    ...(asString(raw.parentStageId) ? { parentStageId: asString(raw.parentStageId) } : {}),
    workspaceFingerprint: asString(raw.workspaceFingerprint),
    writableSession: raw.writableSession === true,
  }
}

function normalizeSessionTodo(value: unknown): SessionTodo {
  const raw = asRecord(value)
  return {
    id: requiredString(raw, 'id', 'SessionTodo'),
    externalTodoId: requiredString(raw, 'externalTodoId', 'SessionTodo'),
    content: asString(raw.content),
    status: asString(raw.status),
    priority: asString(raw.priority) || undefined,
    ordinal: asNumber(raw.ordinal),
    observedAt: asString(raw.observedAt),
  }
}

function normalizeSessionCheckpoint(value: unknown): SessionCheckpoint {
  const raw = asRecord(value)
  return {
    id: requiredString(raw, 'id', 'SessionCheckpoint'),
    taskId: requiredString(raw, 'taskId', 'SessionCheckpoint'),
    sessionId: requiredString(raw, 'sessionId', 'SessionCheckpoint'),
    attemptId: requiredString(raw, 'attemptId', 'SessionCheckpoint'),
    externalMessageId: asString(raw.externalMessageId) || undefined,
    contentSha256: requiredString(raw, 'contentSha256', 'SessionCheckpoint'),
    createdAt: asString(raw.createdAt),
  }
}

function normalizeUsageAggregate(value: unknown): UsageAggregate {
  const raw = asRecord(value)
  const costs = asRecord(raw.costByCurrency)
  return {
    inputTokens: asNullableNumber(raw.inputTokens),
    outputTokens: asNullableNumber(raw.outputTokens),
    totalTokens: asNullableNumber(raw.totalTokens),
    costByCurrency: Object.fromEntries(Object.entries(costs).filter((entry): entry is [string, string] => typeof entry[1] === 'string')),
    unknownUsageCount: asNumber(raw.unknownUsageCount),
  }
}

function normalizeTaskInsight(value: unknown): TaskInsight {
  const raw = asRecord(value)
  const quality = asRecord(raw.quality)
  const qualityState = asString(quality.state)
  return {
    taskId: requiredString(raw, 'taskId', 'TaskInsight'),
    title: asString(raw.title),
    state: asString(raw.state) as TaskInsight['state'],
    durationMs: asNumber(raw.durationMs),
    retryCount: asNumber(raw.retryCount),
    usage: normalizeUsageAggregate(raw.usage),
    quality: {
      state: qualityState === 'PASS' || qualityState === 'REVIEW_REQUIRED' ? qualityState : 'PENDING',
      deterministicPassed: quality.deterministicPassed === true,
      verificationCount: asNumber(quality.verificationCount),
      verificationPassedCount: asNumber(quality.verificationPassedCount),
      requirementJudgePassed: quality.requirementJudgePassed === true,
      riskJudgePassed: quality.riskJudgePassed === true,
    },
  }
}

function normalizeInsights(value: unknown): InsightsSnapshot {
  const raw = asRecord(value)
  return {
    tasks: asArray(raw.tasks).map(normalizeTaskInsight),
    usage: normalizeUsageAggregate(raw.usage),
    generatedAt: asString(raw.generatedAt),
  }
}

export const api = {
  getProjects: async () => (await request<unknown[]>('/projects')).map(normalizeProject),
  pickProjectDirectory: async (): Promise<DirectorySelection> => {
    const raw = asRecord(await request<unknown>('/projects/pick-directory', { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } }))
    return { selected: raw.selected === true, path: asString(raw.path) || undefined, name: asString(raw.name) || undefined }
  },
  createProject: async (input: Pick<Project, 'name' | 'rootPath' | 'description'>) => normalizeProject(await request<unknown>('/projects', { method: 'POST', body: JSON.stringify({ name: input.name, rootPath: input.rootPath, description: input.description?.trim() || '' }) })),
  cancelProjectManagement: async (projectId: string) => request<void>(`/projects/${encodeURIComponent(projectId)}`, { method: 'DELETE', headers: { 'X-Loopper-Local-UI': '1' } }),
  getCurrentProjectConvention: async (projectId: string) => normalizeProjectConventionSnapshot(await request<unknown>(`/projects/${encodeURIComponent(projectId)}/agents-md`)),
  generateProjectConvention: async (projectId: string) => normalizeProjectConvention(await request<unknown>(`/projects/${encodeURIComponent(projectId)}/agents-md`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  getProjectConventionDraft: async (projectId: string, draftId: string) => normalizeProjectConvention(await request<unknown>(`/projects/${encodeURIComponent(projectId)}/agents-md/${encodeURIComponent(draftId)}`)),
  applyProjectConvention: async (projectId: string, draftId: string) => normalizeProjectConvention(await request<unknown>(`/projects/${encodeURIComponent(projectId)}/agents-md/${encodeURIComponent(draftId)}`, { method: 'PUT', headers: { 'X-Loopper-Local-UI': '1' } })),
  getTasks: async () => (await request<unknown[]>('/tasks')).map(normalizeTask),
  getTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}`)),
  getTaskRecoveries: async (id: string) => (await request<unknown[]>(`/tasks/${encodeURIComponent(id)}/recoveries`)).map(normalizeRecovery),
  createTaskRecovery: async (id: string, mode: RecoveryMode) => normalizeRecovery(await request<unknown>(`/tasks/${encodeURIComponent(id)}/recoveries`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ mode }) })),
  getTaskDiffPreview: async (id: string, path: string) => normalizeTaskDiffPreview(await request<unknown>(`/tasks/${encodeURIComponent(id)}/diff-preview?path=${encodeURIComponent(path)}`)),
  getTaskDesignHistory: async (id: string) => normalizeTaskDesignHistory(await request<unknown>(`/tasks/${encodeURIComponent(id)}/design-history`)),
  getTaskSessions: async (id: string) => (await request<unknown[]>(`/tasks/${encodeURIComponent(id)}/sessions`)).map(normalizeTaskSession),
  getTaskSessionActivity: async (taskId: string, sessionKey: string) => normalizeTaskSessionActivity(await request<unknown>(`/tasks/${encodeURIComponent(taskId)}/sessions/${encodeURIComponent(sessionKey)}`)),
  getTaskSessionTodos: async (taskId: string, sessionId: string) => (await request<unknown[]>(`/tasks/${encodeURIComponent(taskId)}/sessions/${encodeURIComponent(sessionId)}/todos`)).map(normalizeSessionTodo),
  refreshTaskSessionTodos: async (taskId: string, sessionId: string) => (await request<unknown[]>(`/tasks/${encodeURIComponent(taskId)}/sessions/${encodeURIComponent(sessionId)}/todos/refresh`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })).map(normalizeSessionTodo),
  getTaskSessionCheckpoints: async (taskId: string, sessionId: string) => (await request<unknown[]>(`/tasks/${encodeURIComponent(taskId)}/sessions/${encodeURIComponent(sessionId)}/checkpoints`)).map(normalizeSessionCheckpoint),
  createTaskSessionCheckpoint: async (taskId: string, sessionId: string, externalMessageId?: string) => normalizeSessionCheckpoint(await request<unknown>(`/tasks/${encodeURIComponent(taskId)}/sessions/${encodeURIComponent(sessionId)}/checkpoints`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ externalMessageId: externalMessageId || undefined }) })),
  forkTaskSession: async (taskId: string, sessionId: string, messageId: string) => request<SessionForkResult>(`/tasks/${encodeURIComponent(taskId)}/sessions/${encodeURIComponent(sessionId)}/fork`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ messageId }) }),
  revertTaskSession: async (taskId: string, sessionId: string, messageId: string, partId: string) => request<SessionRevertResult>(`/tasks/${encodeURIComponent(taskId)}/sessions/${encodeURIComponent(sessionId)}/revert`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ messageId, partId }) }),
  summarizeTaskSession: async (taskId: string, sessionId: string, automatic = false) => request<SessionSummaryResult>(`/tasks/${encodeURIComponent(taskId)}/sessions/${encodeURIComponent(sessionId)}/summarize`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ automatic }) }),
  getInsights: async () => normalizeInsights(await request<unknown>('/insights')),
  getInteractions: async () => (await request<unknown[]>('/interactions')).map(normalizeInteraction),
  resolveInteraction: async (id: string, input: { action: InteractionAction; version: number; answers?: string[][]; message?: string }) => normalizeInteraction(await request<unknown>(`/interactions/${encodeURIComponent(id)}/resolve`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  getLoopSpecTemplates: async () => (await request<unknown[]>('/automations/templates')).map(normalizeTemplate),
  createLoopSpecTemplate: async (input: { name: string; description: string }) => normalizeTemplate(await request<unknown>('/automations/templates', { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  updateLoopSpecTemplate: async (input: Pick<LoopSpecTemplate, 'id' | 'name' | 'description' | 'state' | 'version'>) => normalizeTemplate(await request<unknown>(`/automations/templates/${encodeURIComponent(input.id)}`, { method: 'PUT', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  createLoopSpecTemplateVersion: async (templateId: string, input: { specJson: string; autoStartApproved: boolean }) => normalizeTemplateVersion(await request<unknown>(`/automations/templates/${encodeURIComponent(templateId)}/versions`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ spec: JSON.parse(input.specJson) as unknown, autoStartApproved: input.autoStartApproved }) }), templateId),
  previewLoopSpecTemplateImport: async (source: string) => normalizeAutomationImportPreview(await request<unknown>('/automations/templates/import/preview', { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: source })),
  confirmLoopSpecTemplateImport: async (previewId: string): Promise<AutomationImportResult> => {
    const raw = await request<unknown>(`/automations/templates/import/${encodeURIComponent(previewId)}/confirm`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })
    const record = asRecord(raw)
    return {
      templates: asArray(record.templates).map(normalizeTemplate),
      rules: asArray(record.rules).map(normalizeAutomationRuleMutation),
    }
  },
  exportLoopSpecTemplate: async () => JSON.stringify(await request<unknown>('/automations/templates/export'), null, 2),
  getAutomationRules: async () => (await request<unknown[]>('/automations/rules')).map(normalizeAutomationRule),
  createAutomationRule: async (input: CreateAutomationRuleInput) => normalizeAutomationRuleMutation(await request<unknown>('/automations/rules', { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(backendCreateAutomationRule(input)) })),
  updateAutomationRule: async (input: AutomationRule) => normalizeAutomationRule(await request<unknown>(`/automations/rules/${encodeURIComponent(input.id)}`, { method: 'PUT', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(backendAutomationRule(input)) })),
  triggerAutomationRule: async (ruleId: string) => normalizeAutomationRun(await request<unknown>(`/automations/rules/${encodeURIComponent(ruleId)}/trigger`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  getAutomationRuns: async (): Promise<AutomationRunFeed> => {
    const raw = asRecord(await request<unknown>('/automations/runs'))
    return { runs: asArray(raw.runs).map(normalizeAutomationRun), serverTime: requiredString(raw, 'serverTime', 'AutomationRunFeed') }
  },
  confirmAutomationRun: async (runId: string, title?: string) => normalizeAutomationRun(await request<unknown>(`/automations/runs/${encodeURIComponent(runId)}/confirm`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ title }) })),
  replyTaskSessionQuestion: async (taskId: string, sessionKey: string, questionId: string, answers: string[][]) => request<void>(`/tasks/${encodeURIComponent(taskId)}/sessions/${encodeURIComponent(sessionKey)}/questions/${encodeURIComponent(questionId)}/reply`, { method: 'POST', body: JSON.stringify({ answers }) }),
  rejectTaskSessionQuestion: async (taskId: string, sessionKey: string, questionId: string) => request<void>(`/tasks/${encodeURIComponent(taskId)}/sessions/${encodeURIComponent(sessionKey)}/questions/${encodeURIComponent(questionId)}/reject`, { method: 'POST' }),
  startTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/start`, { method: 'POST' })),
  pauseTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/pause`, { method: 'POST' })),
  resumeTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/resume`, { method: 'POST' })),
  cancelTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/cancel`, { method: 'POST' })),
  retryTaskJudges: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/judges/retry`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  retryWaitingTaskLoop: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/loop/retry`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  archiveTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/archive`, { method: 'PUT', headers: { 'X-Loopper-Local-UI': '1' } })),
  restoreArchivedTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/archive`, { method: 'DELETE', headers: { 'X-Loopper-Local-UI': '1' } })),
  deleteArchivedTask: async (id: string) => request<void>(`/tasks/${encodeURIComponent(id)}`, { method: 'DELETE', headers: { 'X-Loopper-Local-UI': '1' } }),
  getTaskPublication: async (id: string) => normalizeTaskPublication(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication`)),
  generateTaskCommitMessage: async (id: string) => normalizeCommitSuggestion(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/commit-message`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  publishTask: async (id: string, commitMessage?: string) => normalizeTaskPublication(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ commitMessage }) })),
  createTaskMergeRequestDraft: async (id: string, input: { targetBranch: string; title: string; description: string }) => normalizeMergeRequestDraft(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/merge-request`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  createLocalSyncConflictSession: async (id: string) => normalizeLocalSyncSession(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  getLocalSyncConflictSession: async (id: string, sessionId: string) => normalizeLocalSyncSession(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}`)),
  getLocalSyncConflictFiles: async (id: string, sessionId: string) => (await request<unknown[]>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}/files`)).map(normalizeLocalSyncFile),
  getLocalSyncConflictContent: async (id: string, sessionId: string, path: string) => normalizeLocalSyncContent(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}/file?path=${encodeURIComponent(path)}`)),
  saveLocalSyncResolution: async (id: string, sessionId: string, input: { path: string; resolution: Exclude<LocalSyncResolution, 'AUTO'>; content?: string; expectedVersion: number }) => normalizeLocalSyncContent(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}/resolution`, { method: 'PUT', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  suggestLocalSyncResolution: async (id: string, sessionId: string, input: { path: string; expectedVersion: number }) => request<{ path: string; suggestion: string; automaticallySelected: boolean; version: number }>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}/ai-suggestion`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) }),
  applyLocalSyncConflict: async (id: string, sessionId: string, input: { confirmed: boolean; expectedVersion: number }) => normalizeLocalSyncSession(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}/apply`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  getRuntime: async () => normalizeRuntime(await request<unknown>('/runtime/opencode')),
  restartRuntime: async () => normalizeRuntime(await request<unknown>('/runtime/opencode/restart', { method: 'POST' })),
  getSettings: async () => normalizeSettings(await request<unknown>('/settings')),
  updateSettings: async (settings: AppSettings) => normalizeSettings(await request<unknown>('/settings', { method: 'PUT', body: JSON.stringify(settings) })),
  getSettingsModels: async (cliPath?: string) => (await request<unknown[]>(`/settings/models${cliPath ? `?cliPath=${encodeURIComponent(cliPath)}` : ''}`)).map(normalizeAvailableModel),
  createDraft: async (spec: LoopSpec) => normalizeDraft(await request<unknown>('/loop-drafts', { method: 'POST', body: JSON.stringify({ spec: backendLoopSpec(spec) }) })),
  getDraft: async (id: string) => normalizeDraft(await request<unknown>(`/loop-drafts/${encodeURIComponent(id)}`)),
  updateDraft: async (id: string, spec: LoopDraft['spec']) => normalizeDraft(await request<unknown>(`/loop-drafts/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ spec: backendLoopSpec(spec) }) })),
  confirmDraft: async (id: string) => { const task = asRecord(await request<unknown>(`/loop-drafts/${encodeURIComponent(id)}/confirm`, { method: 'POST' })); return { taskId: asString(task.taskId) } },
  createDesignerSession: async (projectId: string, draftId: string, initialMessage?: string) => normalizeDesignerSession(await request<unknown>('/designer-sessions', { method: 'POST', body: JSON.stringify({ projectId, draftId, ...(initialMessage ? { initialMessage } : {}) }) })),
  getDesignerSession: async (id: string) => normalizeDesignerSession(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}`)),
  getDesignerMessages: async (id: string) => (await request<unknown[]>(`/designer-sessions/${encodeURIComponent(id)}/messages`)).map(normalizeDesignerMessage),
  replyDesignerQuestion: async (id: string, questionId: string, answers: string[][]) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/reply`, { method: 'POST', body: JSON.stringify({ answers }) }),
  rejectDesignerQuestion: async (id: string, questionId: string) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/reject`, { method: 'POST' }),
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

export interface DesignerEventStream { close: () => void }

export function subscribeDesignerEvents(sessionId: string, onEvent: (event: DesignerStreamEvent) => void,
                                        onState: (state: 'connected' | 'reconnecting') => void): DesignerEventStream {
  if (typeof EventSource === 'undefined') {
    onState('reconnecting')
    return { close: () => undefined }
  }
  const source = new EventSource(`${apiBase}/designer-sessions/${encodeURIComponent(sessionId)}/events`)
  source.onopen = () => onState('connected')
  source.onmessage = (message) => {
    try { onEvent(normalizeDesignerStreamEvent(JSON.parse(message.data))) }
    catch { /* REST polling remains the recovery path for a malformed event. */ }
  }
  source.onerror = () => onState('reconnecting')
  return { close: () => source.close() }
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
