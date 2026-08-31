import type { AppSettings, Artifact, Attempt, AutomationImportPreview, AutomationImportResult, AutomationRule, AutomationRuleMutation, AutomationRun, AutomationRunFeed, AvailableModel, BrowserAssertion, CommitMessageSuggestion, CreateAutomationRuleInput, DesignerActivity, DesignerAnsweredQuestion, DesignerAppendResult, DesignerHistoryItem, DesignerMessage, DesignerSession, DesignerSessionState, DesignerSessionSummary, DesignerStopResult, DesignerStreamEvent, DirectorySelection, DirtyWorkspaceAction, DirtyWorkspaceResolution, DirtyWorkspaceState, ErrorEvent, GitDiffScopeApproval, GitDiffScopeDecisionAction, InsightsSnapshot, Interaction, InteractionAction, JudgeRun, LocalSyncConflictContent, LocalSyncConflictFile, LocalSyncConflictSession, LocalSyncResolution, LoopDraft, LoopSpec, LoopSpecAssessment, LoopSpecTemplate, LoopSpecTemplateVersion, LoopVerifierSpec, MergeRequestDraft, Project, ProjectConventionActivity, ProjectConventionDraft, ProjectConventionSnapshot, RecoveryDraft, RecoveryMode, RuntimeInfo, SessionCheckpoint, SessionForkResult, SessionRevertResult, SessionSummaryResult, SessionTodo, Stage, Task, TaskDecision, TaskDesignHistory, TaskDiffPreview, TaskEvent, TaskInsight, TaskPublicationStatus, TaskQueueStatus, TaskSessionActivity, TaskSessionActivityPart, TaskSessionPendingQuestion, TaskSessionSummary, UsageAggregate } from '@/types/domain'
import type { AnalysisReport, DesignerTaskProfileUpdatePreview, ProjectStackProfile, RollingPackageCapabilities, RollingPackageDetail, RollingPackageFact, RollingPackageRun, RollingPackageWorkbench, RollingPlanPackage, RollingPlanProposal } from '@/types/domain'
import { DESIGNER_SESSION_STATES, DESIGN_WORK_PACKAGE_STATES, LOOP_DRAFT_STATUSES, STAGE_STATUSES, TASK_PACKAGE_RUN_STATES, TASK_STATUSES, WORK_PACKAGE_AGGREGATE_STATUSES, requirePublicState } from '@/types/states'

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
  const multipart = typeof FormData !== 'undefined' && init?.body instanceof FormData
  const response = await fetch(`${apiBase}${path}`, {
    ...init,
    // Keep caller headers (for example the local-UI guard) without letting
    // RequestInit overwrite the JSON content type assembled here.
    headers: { Accept: 'application/json', ...(init?.body && !multipart ? { 'Content-Type': 'application/json' } : {}), ...init?.headers },
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({})) as { detail?: string; title?: string; errorCode?: string; errorLayer?: string }
    throw new ApiError(problem.detail ?? problem.title ?? `请求失败 (${response.status})`, response.status, { code: problem.errorCode, layer: problem.errorLayer })
  }
  if (typeof response.text !== 'function') return response.json() as Promise<T>
  const body = await response.text()
  if (!body) return undefined as T
  return JSON.parse(body) as T
}

type JsonRecord = Record<string, unknown>
const asRecord = (value: unknown): JsonRecord => value !== null && typeof value === 'object' ? value as JsonRecord : {}
const asString = (value: unknown, fallback = ''): string => typeof value === 'string' ? value : fallback
const asNumber = (value: unknown, fallback = 0): number => typeof value === 'number' && Number.isFinite(value) ? value : fallback
const asNonNegativeInteger = (value: unknown): number => typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : 0
const normalizePackageCandidateRunState = (value: unknown) => {
  const state = asString(value)
  if (!state) return undefined
  if (['OPEN', 'ACCEPTED', 'WAITING_INPUT', 'FALLBACK_REQUIRED', 'CLOSED'].includes(state)) {
    return state as 'OPEN' | 'ACCEPTED' | 'WAITING_INPUT' | 'FALLBACK_REQUIRED' | 'CLOSED'
  }
  throw new Error(`DesignWorkPackage returned unknown candidate run state: ${state}`)
}
const normalizePackageCompilationSource = (value: unknown) => {
  const source = asString(value)
  if (!source) return undefined
  if (source === 'MCP_ACCEPTED' || source === 'MARKDOWN_FALLBACK') return source
  throw new Error(`DesignWorkPackage returned unknown compilation source: ${source}`)
}
const asNullableNumber = (value: unknown): number | null => typeof value === 'number' && Number.isFinite(value) ? value : null
const asArray = (value: unknown): unknown[] => Array.isArray(value) ? value : []

function requiredString(raw: JsonRecord, field: string, context: string): string {
  const value = asString(raw[field])
  if (!value) throw new TypeError(`${context}.${field} is required`)
  return value
}

function requireBooleanFields(raw: JsonRecord, fields: string[], context: string): void {
  for (const field of fields) {
    if (typeof raw[field] !== 'boolean') throw new TypeError(`${context}.${field} must be boolean`)
  }
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
    ...(asArray(raw.criterionIds).length ? { criterionIds: asArray(raw.criterionIds).map(String) } : {}),
    ...(['BUILD', 'TEST', 'SELF_CHECK'].includes(asString(raw.processPurpose)) ? { processPurpose: asString(raw.processPurpose) as 'BUILD' | 'TEST' | 'SELF_CHECK' } : {}),
    ...(asArray(raw.testTargets).length ? { testTargets: asArray(raw.testTargets).map(String) } : {}),
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
    case 'DOCUMENT_STRUCTURE': {
      const documentAssertions = asArray(raw.documentAssertions).map((value) => asRecord(value))
      if (!documentAssertions.length) throw new TypeError('DOCUMENT_STRUCTURE.documentAssertions requires at least one assertion')
      return { ...common, type, path: requiredString(raw, 'path', type), documentAssertions: documentAssertions as unknown as Extract<LoopVerifierSpec, { type: 'DOCUMENT_STRUCTURE' }>['documentAssertions'] }
    }
    case 'TABULAR_DATA': {
      const tabularAssertions = asArray(raw.tabularAssertions).map((value) => asRecord(value))
      if (!tabularAssertions.length) throw new TypeError('TABULAR_DATA.tabularAssertions requires at least one assertion')
      return { ...common, type, path: requiredString(raw, 'path', type), tabularAssertions: tabularAssertions as unknown as Extract<LoopVerifierSpec, { type: 'TABULAR_DATA' }>['tabularAssertions'] }
    }
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
      const runtime = asRecord(item.verificationRuntime)
      const readiness = asRecord(runtime.readiness)
      return {
        ...(asString(item.workPackageId) ? { workPackageId: asString(item.workPackageId) } : {}),
        ...(['SOFTWARE_IMPLEMENTATION', 'DOCUMENT_MATERIALIZATION', 'TABULAR_CONVERSION', 'READ_ONLY_ANALYSIS', 'LOCAL_MAINTENANCE', 'LEGACY_SOFTWARE'].includes(asString(item.stageKind)) ? { stageKind: asString(item.stageKind) as NonNullable<LoopSpec['stages'][number]['stageKind']> } : {}),
        ...(['OPEN_CODE_IMPLEMENTATION', 'SERVER_DOCUMENT_MATERIALIZATION', 'SERVER_TABULAR_CONVERSION', 'READ_ONLY_REPORT'].includes(asString(item.executionStrategy)) ? { executionStrategy: asString(item.executionStrategy) as NonNullable<LoopSpec['stages'][number]['executionStrategy']> } : {}),
        ...(asString(item.artifactPlanId) ? { artifactPlanId: asString(item.artifactPlanId) } : {}),
        objective: asString(item.objective), allowedPaths: asArray(item.allowedPaths).map(String), forbiddenPaths: asArray(item.forbiddenPaths).map(String), deliverables: asArray(item.deliverables).map(String), verifiers: asArray(item.verifiers).map(parseVerifier),
        ...(['JAVA_PRODUCTION', 'JAVA_TEST_ONLY', 'NON_JAVA'].includes(asString(item.implementationKind)) ? { implementationKind: asString(item.implementationKind) as 'JAVA_PRODUCTION' | 'JAVA_TEST_ONLY' | 'NON_JAVA' } : {}),
        acceptanceCriteria: asArray(item.acceptanceCriteria).map((criterion) => {
          const rawCriterion = asRecord(criterion)
          const mode = asString(rawCriterion.verificationMode, 'MACHINE')
          return {
            id: asString(rawCriterion.id),
            description: asString(rawCriterion.description),
            verificationMode: (['MACHINE', 'JUDGE', 'BOTH'].includes(mode) ? mode : 'MACHINE') as 'MACHINE' | 'JUDGE' | 'BOTH',
            ...(asString(rawCriterion.judgeRubric) ? { judgeRubric: asString(rawCriterion.judgeRubric) } : {}),
            ...(asString(rawCriterion.judgeOnlyReason) ? { judgeOnlyReason: asString(rawCriterion.judgeOnlyReason) } : {}),
          }
        }),
        ...(asArray(runtime.startCommand).length ? { verificationRuntime: {
          startCommand: asArray(runtime.startCommand).map(String),
          readiness: { path: asString(readiness.path), expectedStatus: asNumber(readiness.expectedStatus, 200), ...(asString(readiness.jsonPath) ? { jsonPath: asString(readiness.jsonPath) } : {}), ...(typeof readiness.expectedValue === 'string' ? { expectedValue: readiness.expectedValue } : {}), ...(['EXISTS', 'EXACT', 'CONTAINS'].includes(asString(readiness.matchMode)) ? { matchMode: asString(readiness.matchMode) as 'EXISTS' | 'EXACT' | 'CONTAINS' } : {}) },
          startupTimeoutSeconds: asNumber(runtime.startupTimeoutSeconds, 60), shutdownTimeoutSeconds: asNumber(runtime.shutdownTimeoutSeconds, 10),
        } } : {}),
      }
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
  const stackState = asString(raw.stackProfileState)
  return { id: asString(raw.id), name: asString(raw.name), rootPath: asString(raw.rootPath), branch: asString(raw.branch) || undefined, description: asString(raw.description) || undefined, status: status === 'INVALID' || status === 'NEEDS_GIT' ? status : 'READY', executionMode: executionMode === 'WORKTREE' || executionMode === 'DIRECT' || executionMode === 'UNAVAILABLE' ? executionMode : undefined, updatedAt: asString(raw.updatedAt), taskCount: asNumber(raw.taskCount), openDesignerSessionCount: asNumber(raw.openDesignerSessionCount), stackProfileState: stackState === 'READY' || stackState === 'PARTIAL' || stackState === 'FAILED' ? stackState : 'UNANALYZED', stackTechnologyFamilies: asArray(raw.stackTechnologyFamilies).map(String), stackComponentCount: asNumber(raw.stackComponentCount), stackAnalyzedAt: asString(raw.stackAnalyzedAt) || undefined }
}

function normalizeStackComponent(value: unknown) {
  const raw = asRecord(value)
  return { key: asString(raw.key), relativeRoot: asString(raw.relativeRoot, '.'), technologyFamilies: asArray(raw.technologyFamilies).map(String), technologies: asArray(raw.technologies).map(String), buildTools: asArray(raw.buildTools).map(String), testFrameworks: asArray(raw.testFrameworks).map(String), manifestSources: asArray(raw.manifestSources).map(String) }
}

function normalizeProjectStackProfile(value: unknown): ProjectStackProfile {
  const raw = asRecord(value)
  const state = asString(raw.state)
  return { id: asString(raw.id) || undefined, projectId: asString(raw.projectId), state: state === 'READY' || state === 'PARTIAL' || state === 'FAILED' ? state : 'UNANALYZED', manifestFingerprint: asString(raw.manifestFingerprint) || undefined, technologyFamilies: asArray(raw.technologyFamilies).map(String), technologies: asArray(raw.technologies).map(String), filesScanned: asNumber(raw.filesScanned), errorCode: asString(raw.errorCode) || undefined, errorDetail: asString(raw.errorDetail) || undefined, analyzedAt: asString(raw.analyzedAt) || undefined, components: asArray(raw.components).map(normalizeStackComponent) }
}

function normalizeProjectConvention(value: unknown): ProjectConventionDraft {
  const raw = asRecord(value)
  const state = asString(raw.state)
  return {
    id: asString(raw.id),
    projectId: asString(raw.projectId),
    state: state === 'STOPPING' || state === 'CANCELLED' || state === 'READY' || state === 'APPLYING' || state === 'APPLIED' || state === 'FAILED' ? state : 'RUNNING',
    operation: asString(raw.operation) === 'UPDATE' ? 'UPDATE' : 'CREATE',
    readOnlyGeneration: raw.readOnlyGeneration === true,
    content: asString(raw.content) || undefined,
    normalizationNotice: asString(raw.normalizationNotice) || undefined,
    error: asString(raw.error) || undefined,
    updatedAt: asString(raw.updatedAt),
    stackProfileId: asString(raw.stackProfileId) || undefined,
    stackFingerprint: asString(raw.stackFingerprint) || undefined,
  }
}

function normalizeProjectConventionActivity(value: unknown): ProjectConventionActivity {
  const raw = asRecord(value)
  const usage = asRecord(raw.usage)
  return {
    actor: 'PROJECT_CONVENTION',
    remoteState: asString(raw.remoteState, 'UNKNOWN'),
    connected: raw.connected === true,
    observedAt: asString(raw.observedAt),
    parts: asArray(raw.parts).map(normalizeTaskSessionPart),
    detail: asString(raw.detail) || undefined,
    usage: {
      totalTokens: typeof usage.totalTokens === 'number' ? usage.totalTokens : null,
      unknownUsageCount: asNumber(usage.unknownUsageCount),
      observedAt: asString(usage.observedAt),
    },
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
    evidence: asRecord(raw.evidence),
  }
}

function normalizeDirtyWorkspace(value: unknown): DirtyWorkspaceState {
  const raw = asRecord(value)
  return {
    branch: asString(raw.branch),
    head: asString(raw.head),
    snapshotId: asString(raw.snapshotId),
    clean: raw.clean === true,
    files: asArray(raw.files).map((value) => {
      const file = asRecord(value)
      return {
        path: asString(file.path),
        originalPath: asString(file.originalPath) || undefined,
        indexStatus: asString(file.indexStatus),
        workTreeStatus: asString(file.workTreeStatus),
        untracked: file.untracked === true,
      }
    }),
  }
}

function normalizeDirtyWorkspaceResolution(value: unknown): DirtyWorkspaceResolution {
  const raw = asRecord(value)
  return { task: normalizeTask(raw.task), workspace: normalizeDirtyWorkspace(raw.workspace) }
}

function normalizeAttempt(value: unknown): Attempt {
  const raw = asRecord(value)
  const state = asString(raw.status || raw.state)
  const status = state === 'SUCCEEDED' || state === 'VERIFIED' ? 'VERIFIED' : state === 'VERIFICATION_FAILED' || state === 'VERIFIER_FAILED' ? 'VERIFIER_FAILED' : state === 'SESSION_ERROR' ? 'SESSION_ERROR' : state === 'TASK_ERROR' ? 'TASK_ERROR' : state === 'CANCELLED' ? 'CANCELLED' : 'RUNNING'
  const verifications = asArray(raw.verifications).length > 0 ? asArray(raw.verifications) : asArray(raw.verifiers)
  return {
    id: asString(raw.id), ordinal: asNumber(raw.ordinal, 1), stageId: asString(raw.stageId), executionCycleId: asString(raw.executionCycleId) || undefined, sessionId: asString(raw.sessionId) || undefined,
    status, startedAt: asString(raw.startedAt) || asString(raw.createdAt), endedAt: asString(raw.endedAt) || undefined,
    summary: asString(raw.summary) || asString(raw.failureKind) || '执行中', errors: asArray(raw.errors).map(normalizeError),
    // TaskController names these persisted records `verifications`, while the
    // UI domain calls the rendered list `verifiers`.
    verifiers: verifications.map((verifier) => {
      const item = asRecord(verifier)
      const evidence = item.evidence ?? item.evidenceSummary
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
  const status = requirePublicState(STAGE_STATUSES, raw.status || raw.state, 'Stage')
  const id = asString(raw.id)
  const testPolicy = asString(raw.testPolicy)
  return {
    id, ordinal: asNumber(raw.ordinal) + 1, workPackageId: asString(raw.workPackageId) || undefined,
    objective: asString(raw.objective), status, attempts: attempts.filter((attempt) => attempt.stageId === id),
    stageKind: asString(raw.stageKind) || undefined, executionStrategy: asString(raw.executionStrategy) || undefined,
    rolePackId: asString(raw.rolePackId) || undefined, rolePackVersion: asString(raw.rolePackVersion) || undefined,
    testPolicy: (['REQUIRED', 'OPTIONAL', 'NOT_APPLICABLE'].includes(testPolicy) ? testPolicy : undefined) as Stage['testPolicy'],
    technologies: asArray(raw.technologies).map(String),
  }
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
    metadata: asRecord(raw.metadata ?? raw.metadataSummary),
  }
}

export interface CursorPage<T> {
  items: T[]
  nextCursor?: string
  facets: Record<string, number>
}

export interface TaskSummaryQuery {
  projectId?: string
  status?: string[]
  statusGroup?: 'PROCESSING' | 'SUCCESSFUL' | 'TERMINATED'
  archive?: 'ACTIVE' | 'ARCHIVED' | 'ALL'
  q?: string
  order?: 'newest' | 'oldest'
  cursor?: string
  limit?: number
}

export interface DesignerHistoryQuery {
  projectId?: string
  status?: string
  archive?: 'ACTIVE' | 'ARCHIVED' | 'ALL'
  q?: string
  order?: 'newest' | 'oldest'
  cursor?: string
  limit?: number
}

function pageQuery(input: TaskSummaryQuery): string {
  const query = new URLSearchParams()
  if (input.projectId) query.set('projectId', input.projectId)
  input.status?.forEach((status) => query.append('status', status))
  if (input.statusGroup) query.set('statusGroup', input.statusGroup)
  if (input.archive) query.set('archive', input.archive)
  if (input.q) query.set('q', input.q)
  if (input.order) query.set('order', input.order)
  if (input.cursor) query.set('cursor', input.cursor)
  if (input.limit) query.set('limit', String(input.limit))
  const encoded = query.toString()
  return encoded ? `?${encoded}` : ''
}

function normalizeTaskPage(value: unknown): CursorPage<Task> {
  const raw = asRecord(value)
  const facets = asRecord(raw.facets)
  return {
    items: asArray(raw.items).map(normalizeTaskSummary),
    nextCursor: asString(raw.nextCursor) || undefined,
    facets: Object.fromEntries(Object.entries(facets).map(([key, count]) => [key, asNumber(count)])),
  }
}

function normalizeTaskSummary(value: unknown): Task {
  const raw = asRecord(value)
  requireBooleanFields(raw, ['hasDesignHistory', 'archived'], 'TaskSummary')
  return {
    id: requiredString(raw, 'id', 'TaskSummary'), projectId: asString(raw.projectId),
    projectName: asString(raw.projectName, 'Unknown project'), title: asString(raw.title),
    goal: asString(raw.goal), branch: asString(raw.branch) || '等待选择执行模式', worktreePath: '',
    status: requirePublicState(TASK_STATUSES, raw.status, 'TaskSummary'),
    retryCause: ['RATE_LIMIT', 'SESSION', 'VERIFICATION'].includes(asString(raw.retryCause))
      ? asString(raw.retryCause) as Task['retryCause'] : undefined,
    retryDueAt: asString(raw.retryDueAt) || undefined,
    hasDesignHistory: raw.hasDesignHistory as boolean, archived: raw.archived as boolean,
    attemptCount: asNumber(raw.attemptCount), maxAttempts: asNumber(raw.maxAttempts, 12),
    createdAt: asString(raw.createdAt), updatedAt: asString(raw.updatedAt),
    stages: [], workPackages: [], attempts: [], errors: [], judges: [], artifacts: [],
  }
}

function normalizeDesignerHistoryPage(value: unknown): CursorPage<DesignerHistoryItem> {
  const raw = asRecord(value)
  const facets = asRecord(raw.facets)
  return { items: asArray(raw.items).map(normalizeDesignerHistoryItem),
    nextCursor: asString(raw.nextCursor) || undefined,
    facets: Object.fromEntries(Object.entries(facets).map(([key, count]) => [key, asNumber(count)])) }
}

function normalizeTaskAudit(value: unknown, taskId: string): Pick<Task, 'attempts' | 'errors' | 'judges' | 'artifacts'> {
  const raw = asRecord(value)
  return {
    attempts: asArray(raw.attempts).map(normalizeAttempt),
    errors: asArray(raw.errors).map(normalizeError),
    judges: asArray(raw.judges).map(normalizeJudge),
    artifacts: asArray(raw.artifacts).map((artifact) => normalizeArtifact(artifact, taskId)),
  }
}

export interface ReadContent {
  id: string
  kind: string
  contentType?: string
  content: string
  metadata: Record<string, unknown>
}

function normalizeReadContent(value: unknown): ReadContent {
  const raw = asRecord(value)
  return { id: asString(raw.id), kind: asString(raw.kind), contentType: asString(raw.contentType) || undefined,
    content: asString(raw.content), metadata: asRecord(raw.metadata) }
}

function normalizeTask(value: unknown): Task {
  const raw = asRecord(value)
  requireBooleanFields(raw, ['loopRetryAvailable', 'cancellationAvailable', 'hasDesignHistory', 'archived'], 'Task')
  const attempts = asArray(raw.attempts).map(normalizeAttempt)
  const stages = asArray(raw.stages).map((stage) => normalizeStage(stage, attempts))
  const taskId = asString(raw.id)
  const workPackages = asArray(raw.workPackages).map((value) => { const item = asRecord(value); return { id: asString(item.id), ordinal: asNumber(item.ordinal), status: requirePublicState(WORK_PACKAGE_AGGREGATE_STATUSES, item.status, 'WorkPackage'), stageCount: asNumber(item.stageCount), completedStages: asNumber(item.completedStages), attemptCount: asNumber(item.attemptCount), attemptLimit: asNumber(item.attemptLimit) } })
  const executionMode = asString(raw.executionMode) as Task['executionMode']
  const packageCapabilities = raw.packageCapabilities ? normalizeRollingCapabilities(raw.packageCapabilities) : undefined
  return { id: taskId, projectId: asString(raw.projectId), projectName: asString(raw.projectName, 'Unknown project'), title: asString(raw.title), goal: asString(raw.goal), branch: asString(raw.branch) || '等待选择执行模式', worktreePath: asString(raw.worktreePath) || '等待准备执行目录', status: requirePublicState(TASK_STATUSES, raw.status, 'Task'), retryCause: ['RATE_LIMIT', 'SESSION', 'VERIFICATION'].includes(asString(raw.retryCause)) ? asString(raw.retryCause) as Task['retryCause'] : undefined, retryOrdinal: typeof raw.retryOrdinal === 'number' ? raw.retryOrdinal : undefined, retryScheduledAt: asString(raw.retryScheduledAt) || undefined, retryDueAt: asString(raw.retryDueAt) || undefined, retryDelaySeconds: typeof raw.retryDelaySeconds === 'number' ? raw.retryDelaySeconds : undefined, waitingReasonCode: asString(raw.waitingReasonCode) || undefined, loopRetryAvailable: raw.loopRetryAvailable === true, cancellationAvailable: raw.cancellationAvailable === true, hasDesignHistory: raw.hasDesignHistory === true, archived: raw.archived === true, version: typeof raw.version === 'number' ? raw.version : undefined, executionMode: executionMode || undefined, workspacePolicy: asString(raw.workspacePolicy) as Task['workspacePolicy'] || undefined, currentPackage: raw.currentPackage ? normalizeRollingRun(raw.currentPackage) : undefined, plannedPackageCount: typeof raw.plannedPackageCount === 'number' ? raw.plannedPackageCount : undefined, frozenPackageCount: typeof raw.frozenPackageCount === 'number' ? raw.frozenPackageCount : undefined, packageCapabilities, executionResult: asString(raw.executionResult) as Task['executionResult'] || undefined, executionCycleOrdinal: typeof raw.executionCycleOrdinal === 'number' ? raw.executionCycleOrdinal : undefined, checkpointState: asString(raw.checkpointState) as Task['checkpointState'] || undefined, parentTaskId: asString(raw.parentTaskId) || undefined, successorTaskId: asString(raw.successorTaskId) || undefined, activeStage: stages.find((stage) => stage.status === 'RUNNING')?.ordinal, attemptCount: asNumber(raw.attemptCount, attempts.length), maxAttempts: asNumber(raw.maxAttempts, 12), createdAt: asString(raw.createdAt), updatedAt: asString(raw.updatedAt), stages, workPackages, attempts, errors: asArray(raw.errors).map(normalizeError), judges: asArray(raw.judges).map(normalizeJudge), artifacts: asArray(raw.artifacts).map((artifact) => normalizeArtifact(artifact, taskId)) }
}

function normalizeRollingCapabilities(value: unknown): RollingPackageCapabilities {
  const raw = asRecord(value)
  requireBooleanFields(raw, ['canDiscuss', 'canApproveDesign', 'canStartPackage', 'canRetryPackage', 'canRedesignPackage', 'canResumeDesign', 'canReplanRemaining', 'canAddCorrectionPackage'], 'RollingPackageCapabilities')
  return { canDiscuss: raw.canDiscuss as boolean, canApproveDesign: raw.canApproveDesign as boolean,
    canStartPackage: raw.canStartPackage as boolean, canRetryPackage: raw.canRetryPackage as boolean,
    canRedesignPackage: raw.canRedesignPackage as boolean, canResumeDesign: raw.canResumeDesign as boolean,
    canReplanRemaining: raw.canReplanRemaining as boolean,
    canAddCorrectionPackage: raw.canAddCorrectionPackage as boolean }
}

function normalizeRollingRun(value: unknown): RollingPackageRun {
  const raw = asRecord(value)
  return { id: requiredString(raw, 'id', 'RollingPackageRun'), packageKey: requiredString(raw, 'packageKey', 'RollingPackageRun'),
    ordinal: asNumber(raw.ordinal), title: asString(raw.title), state: requirePublicState(TASK_PACKAGE_RUN_STATES, raw.state, 'RollingPackageRun'),
    version: asNumber(raw.version), discussionRevision: asNumber(raw.discussionRevision), designRevision: asNumber(raw.designRevision),
    acceptedDesignRevision: typeof raw.acceptedDesignRevision === 'number' ? raw.acceptedDesignRevision : undefined,
    waitingReasonCode: asString(raw.waitingReasonCode) || undefined,
    correctionOfPackageRunId: asString(raw.correctionOfPackageRunId) || undefined,
    dependencies: asArray(raw.dependencies).map(value => asString(value)).filter(Boolean) }
}

function normalizeRollingFact(value: unknown): RollingPackageFact {
  const raw = asRecord(value)
  return { id: requiredString(raw, 'id', 'RollingPackageFact'), packageRunId: requiredString(raw, 'packageRunId', 'RollingPackageFact'),
    checkpointId: asString(raw.checkpointId), successfulAttemptId: asString(raw.successfulAttemptId),
    provenJson: asString(raw.provenJson), acceptedContractJson: asString(raw.acceptedContractJson),
    navigationSummary: asString(raw.navigationSummary), createdAt: asString(raw.createdAt) }
}

function normalizeRollingWorkbench(value: unknown): RollingPackageWorkbench {
  const raw = asRecord(value)
  return { taskId: requiredString(raw, 'taskId', 'RollingPackageWorkbench'), title: asString(raw.title),
    taskState: requirePublicState(TASK_STATUSES, raw.taskState, 'RollingPackageWorkbench'), taskVersion: asNumber(raw.taskVersion),
    executionMode: 'ROLLING_PACKAGES', workspacePolicy: asString(raw.workspacePolicy) as RollingPackageWorkbench['workspacePolicy'],
    planRevisionId: asString(raw.planRevisionId), planRevision: asNumber(raw.planRevision),
    plannedPackageCount: asNumber(raw.plannedPackageCount), frozenPackageCount: asNumber(raw.frozenPackageCount),
    currentPackageRunId: asString(raw.currentPackageRunId) || undefined,
    packageCapabilities: normalizeRollingCapabilities(raw.packageCapabilities),
    packages: asArray(raw.packages).map(normalizeRollingRun) }
}

function normalizeRollingDetail(value: unknown): RollingPackageDetail {
  const raw = asRecord(value)
  return { packageRun: normalizeRollingRun(raw.packageRun), objective: asString(raw.objective),
    deliverablesJson: asString(raw.deliverablesJson), acceptanceIntentJson: asString(raw.acceptanceIntentJson),
    compilerSummary: asString(raw.compilerSummary) || undefined, handoffSummary: asString(raw.handoffSummary) || undefined,
    designMarkdown: asString(raw.designMarkdown) || undefined, fact: raw.fact ? normalizeRollingFact(raw.fact) : undefined }
}

function normalizeTaskOverview(value: unknown): Task {
  const raw = asRecord(value)
  requireBooleanFields(raw, ['loopRetryAvailable', 'cancellationAvailable', 'hasDesignHistory', 'archived'], 'TaskOverview')
  if (raw.executionMode === 'ROLLING_PACKAGES' && !raw.packageCapabilities) {
    throw new TypeError('TaskOverview.packageCapabilities is required for rolling tasks')
  }
  return normalizeTask(raw)
}

function normalizeTaskQueueStatus(value: unknown): TaskQueueStatus {
  const raw = asRecord(value)
  const state = asString(raw.state)
  const leaseState = asString(raw.leaseState)
  const holderTaskState = asString(raw.holderTaskState)
  return {
    taskId: requiredString(raw, 'taskId', 'TaskQueueStatus'),
    state: (['QUEUED', 'ADMITTED', 'CANCELLED', 'FINISHED'].includes(state) ? state : 'QUEUED') as TaskQueueStatus['state'],
    ...(typeof raw.queuePosition === 'number' ? { queuePosition: raw.queuePosition } : {}),
    leaseState: (['HELD', 'RELEASE_PENDING', 'RELEASED', 'NOT_REQUIRED'].includes(leaseState) ? leaseState : 'NOT_REQUIRED') as TaskQueueStatus['leaseState'],
    ...(asString(raw.rootFingerprint) ? { rootFingerprint: asString(raw.rootFingerprint) } : {}),
    ...(asString(raw.holderTaskId) ? { holderTaskId: asString(raw.holderTaskId) } : {}),
    ...(asString(raw.holderTaskTitle) ? { holderTaskTitle: asString(raw.holderTaskTitle) } : {}),
    ...(holderTaskState ? { holderTaskState: holderTaskState as TaskQueueStatus['holderTaskState'] } : {}),
    ...(typeof raw.holderArchived === 'boolean' ? { holderArchived: raw.holderArchived } : {}),
    ...(asString(raw.releaseReason) ? { releaseReason: asString(raw.releaseReason) } : {}),
    reconcileAvailable: raw.reconcileAvailable === true,
  }
}

function normalizeTaskDesignHistory(value: unknown): TaskDesignHistory {
  const raw = asRecord(value)
  const session = asRecord(raw.designerSession)
  return {
    taskId: asString(raw.taskId),
    taskTitle: asString(raw.taskTitle),
    projectName: asString(raw.projectName, 'Unknown project'),
    designSourceTaskId: asString(raw.designSourceTaskId) || undefined,
    inheritedConversation: raw.inheritedConversation === true,
    frozenAttachments: asArray(raw.frozenAttachments).map((value) => {
      const item = asRecord(value)
      return { id: asString(item.id), filename: asString(item.filename), mediaType: asString(item.mediaType),
        sizeBytes: asNumber(item.sizeBytes), sha256: asString(item.sha256), scopeKey: asString(item.scopeKey),
        workPackageId: asString(item.workPackageId) || undefined, extractorId: asString(item.extractorId) || undefined,
        sourceTaskId: asString(item.sourceTaskId) || undefined, frozenAt: asString(item.frozenAt) }
    }),
    draft: normalizeDraft(raw.draft),
    designerSession: raw.designerSession ? {
      id: asString(session.id),
      state: normalizeDesignerState(session.state),
      accessMode: 'READ_ONLY',
      createdAt: asString(session.createdAt),
      updatedAt: asString(session.updatedAt),
      messages: asArray(session.messages).map(normalizeDesignerMessage),
    } : undefined,
    requirement: raw.requirement ? (() => { const item = asRecord(raw.requirement); return { revision: asNumber(item.revision), state: asString(item.state), requirementText: asString(item.requirementText), modelCallsUsed: asNumber(item.modelCallsUsed), maxModelCalls: asNumber(item.maxModelCalls) } })() : undefined,
    decomposition: raw.decomposition ? (() => { const item = asRecord(raw.decomposition); return { state: asString(item.state), resultType: asString(item.resultType) || undefined, planJson: asString(item.planJson) } })() : undefined,
    workPackages: asArray(raw.workPackages).map((value) => { const item = asRecord(value); return { id: asString(item.id), ordinal: asNumber(item.ordinal), title: asString(item.title), objective: asString(item.objective), state: requirePublicState(DESIGN_WORK_PACKAGE_STATES, item.state, 'DesignWorkPackage'), compilerSummary: asString(item.compilerSummary) || undefined, handoffSummary: asString(item.handoffSummary) || undefined } }),
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
    scope: asString(raw.scope) || undefined,
    discussionRevision: typeof raw.discussionRevision === 'number' ? raw.discussionRevision : undefined,
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

function normalizeDesignerAnsweredQuestion(value: unknown): DesignerAnsweredQuestion {
  const raw = asRecord(value)
  const pending = normalizeTaskSessionQuestion(value)
  return {
    id: pending.id,
    scope: pending.scope,
    discussionRevision: pending.discussionRevision,
    designMessageId: asString(raw.designMessageId) || undefined,
    answeredAt: asString(raw.answeredAt) || undefined,
    questions: pending.questions.map((question, index) => ({
      ...question,
      answers: asArray(asRecord(asArray(raw.questions)[index]).answers).map((answer) => asString(answer)).filter(Boolean),
    })),
  }
}

function normalizeTaskSessionActivity(value: unknown): TaskSessionActivity {
  const raw = asRecord(value)
  const usage = asRecord(raw.usage)
  const todoCapability = asString(raw.todoCapability).toUpperCase()
  return {
    session: normalizeTaskSession(raw.session),
    remoteState: asString(raw.remoteState, 'UNKNOWN'),
    live: raw.live === true,
    observedAt: asString(raw.observedAt),
    parts: asArray(raw.parts).map(normalizeTaskSessionPart),
    pendingQuestions: asArray(raw.pendingQuestions).map(normalizeTaskSessionQuestion),
    detail: asString(raw.detail) || undefined,
    todoCapability: todoCapability === 'AVAILABLE' || todoCapability === 'UNAVAILABLE' ? todoCapability : 'UNKNOWN',
    usage: { totalTokens: asNullableNumber(usage.totalTokens), unknownUsageCount: asNumber(usage.unknownUsageCount), observedAt: asString(usage.observedAt) },
    todos: asArray(raw.todos).map((value) => {
      const todo = asRecord(value)
      const status = asString(todo.status).toUpperCase()
      const priority = asString(todo.priority).toUpperCase()
      return {
        id: asString(todo.id),
        content: asString(todo.content),
        status: ['PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'].includes(status) ? status as 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' : 'UNKNOWN',
        priority: ['HIGH', 'MEDIUM', 'LOW'].includes(priority) ? priority as 'HIGH' | 'MEDIUM' | 'LOW' : undefined,
        ordinal: asNumber(todo.ordinal),
      }
    }),
    todoTruncated: raw.todoTruncated === true,
    todoDetail: asString(raw.todoDetail) || undefined,
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
  const capability = asRecord(raw.capabilities)
  const internalMcp = asRecord(raw.internalMcp)
  const backendStatus = asString(raw.status)
  const status = backendStatus === 'AVAILABLE' || backendStatus === 'ONLINE'
    ? 'ONLINE'
    : backendStatus === 'STARTING' || backendStatus === 'INCOMPATIBLE'
      ? backendStatus
      : 'OFFLINE'
  return {
    loopperVersion: asString(raw.loopperVersion) || undefined,
    status,
    version: asString(raw.version) || undefined,
    managed: Boolean(raw.managed),
    pid: asNumber(raw.pid) || undefined,
    endpoint: asString(raw.endpoint) || undefined,
    model: asString(raw.model) || undefined,
    checkedAt: asString(raw.checkedAt) || new Date().toISOString(),
    startupFailure: asString(raw.startupFailure) || undefined,
    generation: asString(raw.generation) || undefined,
    internalMcp: Object.keys(internalMcp).length ? {
      status: ['INACTIVE', 'CONNECTING', 'CONNECTED', 'UNAVAILABLE'].includes(asString(internalMcp.status))
        ? asString(internalMcp.status) as 'INACTIVE' | 'CONNECTING' | 'CONNECTED' | 'UNAVAILABLE'
        : 'UNKNOWN',
      configured: internalMcp.configured === true,
      detail: asString(internalMcp.detail) || undefined,
    } : undefined,
    capabilities: Object.keys(capability).length ? {
      agentDiscovery: ['AVAILABLE', 'UNAVAILABLE'].includes(asString(capability.agentDiscovery)) ? asString(capability.agentDiscovery) as 'AVAILABLE' | 'UNAVAILABLE' : 'UNKNOWN',
      agents: asArray(capability.agents).map((value) => { const agent = asRecord(value); return { name: asString(agent.name), mode: asString(agent.mode) || undefined, description: asString(agent.description) || undefined } }),
      nativePlanAgent: capability.nativePlanAgent === true,
      structuredOutputTransport: ['AVAILABLE', 'UNAVAILABLE'].includes(asString(capability.structuredOutputTransport)) ? asString(capability.structuredOutputTransport) as 'AVAILABLE' | 'UNAVAILABLE' : 'UNKNOWN',
      selectedModelStructuredOutput: ['AVAILABLE', 'UNAVAILABLE'].includes(asString(capability.selectedModelStructuredOutput)) ? asString(capability.selectedModelStructuredOutput) as 'AVAILABLE' | 'UNAVAILABLE' : 'UNKNOWN',
      defaultResponseMode: asString(capability.defaultResponseMode) === 'TEXT_MARKER' ? 'TEXT_MARKER' : 'JSON_SCHEMA',
      extensionPolicy: 'TRUSTED_ALLOWED',
      checkedAt: asString(capability.checkedAt) || asString(raw.checkedAt),
      detail: asString(capability.detail) || undefined,
    } : undefined,
  }
}

function normalizeSettings(value: unknown): AppSettings {
  const raw = asRecord(value)
  const runtime = asRecord(raw.runtime)
  const openCode = asRecord(raw.openCode)
  const limits = asRecord(raw.limits)
  const retryWait = asRecord(raw.retryWait)
  const publication = asRecord(raw.publication)
  const openCodeMode = asString(openCode.mode)
  return {
    runtime: { serverPort: asNumber(runtime.serverPort, 8080), openBrowser: runtime.openBrowser !== false, allowedRoot: asString(runtime.allowedRoot), monitorDelaySeconds: asNumber(runtime.monitorDelaySeconds, 2), designerMonitorDelayMillis: asNumber(runtime.designerMonitorDelayMillis, 750), abortCleanupAttempts: asNumber(runtime.abortCleanupAttempts, 3) },
    openCode: { cliPath: asString(openCode.cliPath, 'opencode'), mode: ['managed', 'auto', 'http'].includes(openCodeMode) ? openCodeMode as AppSettings['openCode']['mode'] : 'managed', baseUrl: asString(openCode.baseUrl, 'http://127.0.0.1:4096'), provider: asString(openCode.provider), model: asString(openCode.model), connectTimeoutSeconds: asNumber(openCode.connectTimeoutSeconds, 5), requestTimeoutSeconds: asNumber(openCode.requestTimeoutSeconds, 30), startupTimeoutSeconds: asNumber(openCode.startupTimeoutSeconds, 15) },
    limits: { maxStageAttempts: asNumber(limits.maxStageAttempts, 3), maxTaskAttempts: asNumber(limits.maxTaskAttempts, 12), sessionErrorLimit: asNumber(limits.sessionErrorLimit, 3), maxDurationMinutes: asNumber(limits.maxDurationMinutes, 120), attemptTimeoutMinutes: asNumber(limits.attemptTimeoutMinutes, 30), verifierTimeoutMinutes: asNumber(limits.verifierTimeoutMinutes, 10), designerTimeoutMinutes: asNumber(limits.designerTimeoutMinutes, 30) },
    retryWait: { rateLimitBaseSeconds: asNumber(retryWait.rateLimitBaseSeconds, 60), rateLimitMaxSeconds: asNumber(retryWait.rateLimitMaxSeconds, 300), sessionBaseSeconds: asNumber(retryWait.sessionBaseSeconds, 10), sessionMaxSeconds: asNumber(retryWait.sessionMaxSeconds, 60), verificationBaseSeconds: asNumber(retryWait.verificationBaseSeconds, 5), verificationMaxSeconds: asNumber(retryWait.verificationMaxSeconds, 30) },
    publication: { httpWebHosts: asArray(publication.httpWebHosts).map(String), gitlabHost: asString(publication.gitlabHost, 'gitlab.spdb.com'), gitlabApiBaseUrl: asString(publication.gitlabApiBaseUrl, 'http://gitlab.spdb.com/api/v4'), connectTimeoutSeconds: asNumber(publication.connectTimeoutSeconds, 3), requestTimeoutSeconds: asNumber(publication.requestTimeoutSeconds, 10) },
    startupConfigPath: asString(raw.startupConfigPath) || undefined,
    appliedLiveFields: asArray(raw.appliedLiveFields).map(String),
    restartRequiredFields: asArray(raw.restartRequiredFields).map(String),
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
  return { id: asString(raw.id), status: requirePublicState(LOOP_DRAFT_STATUSES, raw.status, 'LoopDraft'), updatedAt: asString(raw.updatedAt), spec: parseLoopSpec(parsed) }
}

function normalizeDesignerMessage(value: unknown): DesignerMessage {
  const raw = asRecord(value)
  const role = asString(raw.role)
  const actor = asString(raw.actor)
  return {
    id: asString(raw.id),
    role: role === 'USER' || role === 'ASSISTANT' || role === 'SYSTEM' ? role : 'SYSTEM',
    actor: ['USER', 'ROUTER', 'DECOMPOSER', 'DESIGNER', 'COMPILER', 'REVIEWER', 'VALIDATOR', 'SYSTEM'].includes(actor) ? actor as DesignerMessage['actor'] : role === 'USER' ? 'USER' : role === 'ASSISTANT' ? 'DESIGNER' : 'SYSTEM',
    content: asString(raw.content),
    deliveryState: ['PERSISTED', 'PENDING_HANDOFF', 'COMPILED', 'DESIGN_INCOMPLETE', 'PASS', 'RETRYABLE_ERROR', 'TERMINAL_ERROR', 'SESSION_ERROR'].includes(asString(raw.deliveryState))
      ? asString(raw.deliveryState) as DesignerMessage['deliveryState']
      : undefined,
    requirementRevision: typeof raw.requirementRevision === 'number' ? raw.requirementRevision : undefined,
    workPackageId: asString(raw.workPackageId) || undefined,
    attachments: asArray(raw.attachments).map(normalizeDesignerAttachment),
    createdAt: asString(raw.createdAt),
  }
}

function normalizeDesignerAttachment(value: unknown): NonNullable<DesignerMessage['attachments']>[number] {
  const raw = asRecord(value)
  return {
    id: asString(raw.id), filename: asString(raw.filename), mediaType: asString(raw.mediaType),
    sizeBytes: asNumber(raw.sizeBytes), sha256: asString(raw.sha256), scopeKey: asString(raw.scopeKey),
    workPackageId: asString(raw.workPackageId) || undefined, extractorId: asString(raw.extractorId),
    previewKind: asString(raw.previewKind), state: asString(raw.state),
    supersededByAttachmentId: asString(raw.supersededByAttachmentId) || undefined,
  }
}

function designerContextForm(metadata: object, files: File[]): FormData {
  const form = new FormData()
  form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }), 'metadata.json')
  files.forEach(file => form.append('files', file, file.name))
  return form
}

function normalizeWorkflowPhase(value: unknown): DesignerSession['workflowPhase'] {
  const phase = asString(value)
  return ['ROUTING', 'DISCUSSING_REQUIREMENT', 'DECOMPOSING', 'VALIDATING_DECOMPOSITION', 'DESIGNING', 'COMPILING', 'VALIDATING', 'REDESIGNING', 'QUESTIONING_PACKAGE', 'REVIEWING_PACKAGE', 'AGGREGATING', 'FINAL_REVIEW', 'GENERATING_REPORT', 'VALIDATING_REPORT', 'REPORT_READY', 'COMPLETED', 'FAILED'].includes(phase)
    ? phase as DesignerSession['workflowPhase'] : 'DESIGNING'
}

function normalizeDesignerActor(value: unknown): DesignerSession['activeActor'] {
  const actor = asString(value)
  return ['USER', 'ROUTER', 'DECOMPOSER', 'DESIGNER', 'COMPILER', 'REVIEWER', 'VALIDATOR', 'SYSTEM'].includes(actor)
    ? actor as DesignerSession['activeActor'] : 'SYSTEM'
}

function normalizeStructuredStep(value: unknown): DesignerStreamEvent['structuredStep'] {
  const step = asString(value)
  return ['PLANNING', 'SERVER_COMPILING', 'GENERATING_JSON', 'REPAIRING_JSON', 'FINAL_JSON'].includes(step)
    ? step as DesignerStreamEvent['structuredStep'] : undefined
}

function normalizeDesignerState(value: unknown): DesignerSessionState {
  return requirePublicState(DESIGNER_SESSION_STATES, value, 'DesignerSession')
}

function normalizeDesignerSession(value: unknown): DesignerSession {
  const raw = asRecord(value)
  const autoMode = asRecord(raw.autoMode)
  const autoModeState = asString(autoMode.state)
  return {
    id: asString(raw.id),
    taskId: asString(raw.taskId) || undefined,
    projectId: asString(raw.projectId),
    projectName: asString(raw.projectName) || undefined,
    archived: raw.archived === true,
    state: normalizeDesignerState(raw.state),
    workflowPhase: normalizeWorkflowPhase(raw.workflowPhase),
    activeActor: normalizeDesignerActor(raw.activeActor),
    accessMode: 'READ_ONLY',
    readOnly: raw.readOnly !== false,
    permissionSummary: asString(raw.permissionSummary) || undefined,
    updatedAt: asString(raw.updatedAt) || undefined,
    draft: raw.draft ? normalizeDraft(raw.draft) : undefined,
    messages: asArray(raw.messages).map(normalizeDesignerMessage),
    pendingQuestions: asArray(raw.pendingQuestions).map(normalizeTaskSessionQuestion),
    answeredQuestions: asArray(raw.answeredQuestions).map(normalizeDesignerAnsweredQuestion),
    questionInteraction: (() => {
      const interaction = asRecord(raw.questionInteraction)
      const mode = asString(interaction.mode)
      return {
        mode: (['NATIVE_TOOL', 'CHAT_FALLBACK'].includes(mode) ? mode : 'NONE') as DesignerSession['questionInteraction']['mode'],
        awaitingAnswer: interaction.awaitingAnswer === true,
      }
    })(),
    compiler: raw.compiler ? (() => {
      const compiler = asRecord(raw.compiler)
      return {
        id: asString(compiler.id),
        state: asString(compiler.state) as NonNullable<DesignerSession['compiler']>['state'],
        externalSessionState: asString(compiler.externalSessionState) || undefined,
        repairCount: asNumber(compiler.repairCount),
        designRevision: asNumber(compiler.designRevision),
        lastErrorCode: asString(compiler.lastErrorCode) || undefined,
        lastErrorDetail: asString(compiler.lastErrorDetail) || undefined,
        workPackageId: asString(compiler.workPackageId) || undefined,
        workflowStep: normalizeStructuredStep(compiler.workflowStep) ?? 'FINAL_JSON',
        planningRepairCount: asNumber(compiler.planningRepairCount),
        formatRepairCount: typeof compiler.formatRepairCount === 'number' ? compiler.formatRepairCount : asNumber(compiler.planningRepairCount),
        semanticRepairCount: asNumber(compiler.semanticRepairCount),
        serverCompiled: compiler.serverCompiled === true,
      }
    })() : undefined,
    requirement: raw.requirement ? (() => { const item = asRecord(raw.requirement); return { revision: asNumber(item.revision), state: asString(item.state), modelCallsUsed: asNumber(item.modelCallsUsed), maxModelCalls: asNumber(item.maxModelCalls), sourceDraftVersion: asNumber(item.sourceDraftVersion) } })() : undefined,
    decomposition: raw.decomposition ? (() => { const item = asRecord(raw.decomposition); const resultType = asString(item.resultType); return { id: asString(item.id), state: asString(item.state), resultType: ['DIRECT_DESIGN', 'DECOMPOSED', 'NEEDS_INPUT', 'MULTI_TASK_REQUIRED'].includes(resultType) ? resultType as NonNullable<DesignerSession['decomposition']>['resultType'] : undefined, repairCount: asNumber(item.repairCount), transportRetryCount: asNumber(item.transportRetryCount), lastErrorCode: asString(item.lastErrorCode) || undefined, lastErrorDetail: asString(item.lastErrorDetail) || undefined, workflowStep: normalizeStructuredStep(item.workflowStep) ?? 'FINAL_JSON', planningRepairCount: asNumber(item.planningRepairCount), formatRepairCount: typeof item.formatRepairCount === 'number' ? item.formatRepairCount : asNumber(item.planningRepairCount), semanticRepairCount: asNumber(item.semanticRepairCount), serverCompiled: item.serverCompiled === true, candidateSessions: asNonNegativeInteger(item.candidateSessions), candidateSubmissions: asNonNegativeInteger(item.candidateSubmissions) } })() : undefined,
    workPackages: asArray(raw.workPackages).map((value) => { const item = asRecord(value); const planning = asRecord(item.acceptancePlanning); const compilationSource = normalizePackageCompilationSource(item.compilationSource); return { id: asString(item.id), ordinal: asNumber(item.ordinal), title: asString(item.title), objective: asString(item.objective), state: requirePublicState(DESIGN_WORK_PACKAGE_STATES, item.state, 'DesignWorkPackage'), dependencies: asArray(item.dependencies).map(String), redesignCount: asNumber(item.redesignCount), compilerRepairCount: asNumber(item.compilerRepairCount), compilerPlanningRepairCount: asNumber(item.compilerPlanningRepairCount), compilerFormatRepairCount: asNumber(item.compilerFormatRepairCount), compilerSemanticRepairCount: asNumber(item.compilerSemanticRepairCount), compilerServerCompiled: item.compilerServerCompiled === true, compilerSummary: asString(item.compilerSummary) || undefined, handoffSummary: asString(item.handoffSummary) || undefined, lastErrorCode: asString(item.lastErrorCode) || undefined, lastErrorDetail: asString(item.lastErrorDetail) || undefined, designRevision: asNumber(item.designRevision), approvedDesignRevision: typeof item.approvedDesignRevision === 'number' ? item.approvedDesignRevision : undefined, discussionRoundCount: asNumber(item.discussionRoundCount), invalidatedByPackageId: asString(item.invalidatedByPackageId) || undefined, approvedAt: asString(item.approvedAt) || undefined, rolePackId: asString(item.rolePackId) || undefined, rolePackVersion: asString(item.rolePackVersion) || undefined, executionStrategy: asString(item.executionStrategy) as NonNullable<DesignerSession['workPackages']>[number]['executionStrategy'], testPolicy: asString(item.testPolicy) as NonNullable<DesignerSession['workPackages']>[number]['testPolicy'], technologies: asArray(item.technologies).map(String), candidateRunState: normalizePackageCandidateRunState(item.candidateRunState), candidateSessions: asNonNegativeInteger(item.candidateSessions), candidateSubmissions: asNonNegativeInteger(item.candidateSubmissions), compilationSource, fallbackReason: compilationSource === 'MARKDOWN_FALLBACK' ? asString(item.fallbackReason) || undefined : undefined, serverCompiled: item.serverCompiled === true, acceptancePlanning: item.acceptancePlanning ? { state: asString(planning.state, 'FAILED') as 'EXTRACTED' | 'BOUND' | 'COMPILED' | 'FAILED', bindingSource: asString(planning.bindingSource, 'LEGACY_UNKNOWN') as 'UNDECIDED' | 'SERVER_STAGE_HINTS' | 'AI_DISAMBIGUATION_V6' | 'LEGACY_UNKNOWN', routingReasons: asArray(planning.routingReasons).map(String), factCount: asNumber(planning.factCount), scenarioCount: asNumber(planning.scenarioCount), automatedCount: asNumber(planning.automatedCount), bothCount: asNumber(planning.bothCount), judgeCount: asNumber(planning.judgeCount), unresolvedCount: asNumber(planning.unresolvedCount), mutationObligationCount: asNumber(planning.mutationObligationCount), resolvedMutationObligationCount: asNumber(planning.resolvedMutationObligationCount), unresolvedMutationObligationCount: asNumber(planning.unresolvedMutationObligationCount), pathConservation: asString(planning.pathConservation, 'NOT_EVALUATED') as 'NOT_EVALUATED' | 'CONSERVED' | 'BLOCKED', mutationBindingReasons: asArray(planning.mutationBindingReasons).map(String), scenarios: asArray(planning.scenarios).map((scenario) => { const entry = asRecord(scenario); return { title: asString(entry.title), coverage: asString(entry.coverage, 'UNRESOLVED') as 'AUTOMATED' | 'BOTH' | 'JUDGE' | 'UNRESOLVED', capabilities: asArray(entry.capabilities).map(String) } }), issues: asArray(planning.issues).map(String) } : undefined } }),
    requirementRevision: typeof raw.requirementRevision === 'number' ? raw.requirementRevision : undefined,
    activeWorkPackageId: asString(raw.activeWorkPackageId) || undefined,
    discussionScope: asString(raw.discussionScope, 'REQUIREMENT'),
    discussionRevision: asNumber(raw.discussionRevision),
    candidate: raw.candidate ? (() => { const item = asRecord(raw.candidate); const state = asString(item.syncState); return { syncState: (['NONE', 'SYNCING', 'SYNCED', 'FAILED'].includes(state) ? state : 'NONE') as NonNullable<DesignerSession['candidate']>['syncState'], discussionRevision: asNumber(item.discussionRevision), workPackageId: asString(item.workPackageId) || undefined, spec: item.spec ? parseLoopSpec(item.spec) : undefined, detail: asString(item.detail) || undefined } })() : undefined,
    finalConfirmationEligible: raw.finalConfirmationEligible === true,
    taskProfile: (() => { const item = asRecord(raw.taskProfile); return {
      id: asString(item.id) || undefined, state: asString(item.state), decisionState: asString(item.decisionState, 'CONFIRMED') as DesignerSession['taskProfile']['decisionState'], confirmationReady: item.confirmationReady === true, intent: asString(item.intent, 'LEGACY_SOFTWARE') as DesignerSession['taskProfile']['intent'],
      workflowTemplate: asString(item.workflowTemplate, 'FULL_PACKAGE_DESIGN') as DesignerSession['taskProfile']['workflowTemplate'], mutationMode: asString(item.mutationMode, 'WRITE_CODE') as DesignerSession['taskProfile']['mutationMode'],
      artifactKinds: asArray(item.artifactKinds).map(String) as DesignerSession['taskProfile']['artifactKinds'], technologies: asArray(item.technologies).map(String),
      testPolicy: asString(item.testPolicy, 'REQUIRED') as DesignerSession['taskProfile']['testPolicy'], executionStrategy: asString(item.executionStrategy, 'OPEN_CODE_IMPLEMENTATION') as DesignerSession['taskProfile']['executionStrategy'],
      rolePackId: asString(item.rolePackId, 'software-java'), rolePackVersion: asString(item.rolePackVersion, 'legacy'), confidence: asNumber(item.confidence), confidenceAvailable: item.confidenceAvailable === true, evidence: asArray(item.evidence).map(String), resolutionSource: asString(item.resolutionSource), decisionRequired: item.decisionRequired === true, largeTaskMode: typeof item.largeTaskMode === 'boolean' ? item.largeTaskMode : asString(item.workflowTemplate) === 'FULL_PACKAGE_DESIGN', previousConfirmedChoice: item.previousConfirmedChoice ? (() => { const previous = asRecord(item.previousConfirmedChoice); return { intent: asString(previous.intent) as DesignerSession['taskProfile']['intent'], primaryArtifactKind: asString(previous.primaryArtifactKind) as DesignerSession['taskProfile']['artifactKinds'][number], workflowTemplate: asString(previous.workflowTemplate) as DesignerSession['taskProfile']['workflowTemplate'], mutationMode: asString(previous.mutationMode) as DesignerSession['taskProfile']['mutationMode'], largeTaskMode: previous.largeTaskMode === true, resolutionSource: asString(previous.resolutionSource), projectStackProfileId: asString(previous.projectStackProfileId) || undefined, stackFingerprint: asString(previous.stackFingerprint) || undefined, componentKeys: asArray(previous.componentKeys).map(String) } })() : undefined, version: asNumber(item.version), projectStackProfileId: asString(item.projectStackProfileId) || undefined, stackFingerprint: asString(item.stackFingerprint) || undefined, componentKeys: asArray(item.componentKeys).map(String), candidateComponents: asArray(item.candidateComponents).map(normalizeStackComponent), stackProfileState: (() => { const state = asString(item.stackProfileState); return state === 'READY' || state === 'PARTIAL' || state === 'FAILED' ? state : 'UNANALYZED' })(), componentSelectionRequired: item.componentSelectionRequired === true,
    } })(),
    routerRun: raw.routerRun ? (() => { const item = asRecord(raw.routerRun); const state = asString(item.state); return {
      id: asString(item.id), state: (['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SUPERSEDED'].includes(state) ? state : 'FAILED') as NonNullable<DesignerSession['routerRun']>['state'], externalState: asString(item.externalState) || undefined, errorCode: asString(item.errorCode) || undefined, errorDetail: asString(item.errorDetail) || undefined, createdAt: asString(item.createdAt), updatedAt: asString(item.updatedAt), deadlineAt: asString(item.deadlineAt) || undefined, retryAvailable: item.retryAvailable === true,
    } })() : undefined,
    availableProfileOverrides: asArray(raw.availableProfileOverrides).map(String) as DesignerSession['availableProfileOverrides'],
    availableArtifactOverrides: asArray(raw.availableArtifactOverrides).map(String) as DesignerSession['availableArtifactOverrides'],
    reports: asArray(raw.reports).map((value) => { const item = asRecord(value); return { id: asString(item.id), state: asString(item.state), title: asString(item.title), contentSha256: asString(item.contentSha256), stale: item.stale === true, updatedAt: asString(item.updatedAt) } }),
    autoMode: {
      enabled: autoMode.enabled === true,
      state: (['DISABLED', 'ACTIVE', 'BLOCKED', 'COMPLETED'].includes(autoModeState)
        ? autoModeState : 'DISABLED') as DesignerSession['autoMode']['state'],
      version: asNumber(autoMode.version),
      lastAction: asString(autoMode.lastAction) || undefined,
      errorCode: asString(autoMode.errorCode) || undefined,
      errorDetail: asString(autoMode.errorDetail) || undefined,
      taskId: asString(autoMode.taskId) || undefined,
      updatedAt: asString(autoMode.updatedAt) || undefined,
    },
  }
}

function normalizeAnalysisReport(value: unknown): AnalysisReport {
  const raw = asRecord(value)
  return {
    id: asString(raw.id), state: asString(raw.state), title: asString(raw.title), markdown: asString(raw.markdown),
    contentSha256: asString(raw.contentSha256), sourceSnapshotSha256: asString(raw.sourceSnapshotSha256),
    evidence: asArray(raw.evidence).map((item) => { const entry = asRecord(item); return { path: asString(entry.path), line: asNumber(entry.line), sha256: asString(entry.sha256), stale: entry.stale === true } }),
    stale: raw.stale === true, errorCode: asString(raw.errorCode) || undefined, errorDetail: asString(raw.errorDetail) || undefined,
    createdAt: asString(raw.createdAt), updatedAt: asString(raw.updatedAt), reviewerContractVersion: asString(raw.reviewerContractVersion) || undefined,
    findings: asArray(raw.findings).map((item) => { const entry = asRecord(item); return { severity: asString(entry.severity), title: asString(entry.title), detail: asString(entry.detail), path: asString(entry.path), line: asNumber(entry.line), recommendation: asString(entry.recommendation) } }),
  }
}

function normalizeDesignerSessionSummary(value: unknown): DesignerSessionSummary {
  const raw = asRecord(value)
  return {
    id: asString(raw.id),
    projectId: asString(raw.projectId),
    state: normalizeDesignerState(raw.state),
    workflowPhase: normalizeWorkflowPhase(raw.workflowPhase),
    updatedAt: asString(raw.updatedAt),
    draftId: asString(raw.draftId),
    draftStatus: asString(raw.draftStatus),
    goal: asString(raw.goal),
    requirementRevision: typeof raw.requirementRevision === 'number' ? raw.requirementRevision : undefined,
    activeWorkPackageId: asString(raw.activeWorkPackageId) || undefined,
  }
}

function normalizeDesignerHistoryItem(value: unknown): DesignerHistoryItem {
  const raw = asRecord(value)
  requireBooleanFields(raw, ['archived', 'resumable', 'stopRetryAvailable'], 'DesignerHistoryItem')
  return {
    ...normalizeDesignerSessionSummary(raw),
    projectName: asString(raw.projectName),
    createdAt: asString(raw.createdAt),
    archived: raw.archived === true,
    archivedAt: asString(raw.archivedAt) || undefined,
    taskId: asString(raw.taskId) || undefined,
    taskState: raw.taskState == null ? undefined : requirePublicState(TASK_STATUSES, raw.taskState, 'DesignerHistory task'),
    resumable: raw.resumable as boolean,
    stopRetryAvailable: raw.stopRetryAvailable as boolean,
  }
}

function normalizeDesignerStopResult(value: unknown): DesignerStopResult {
  const raw = asRecord(value)
  if (typeof raw.archived !== 'boolean' || typeof raw.stoppedSessions !== 'number'
    || typeof raw.failedSessions !== 'number' || typeof raw.pendingFinalizations !== 'number') {
    throw new TypeError('DesignerStopResult is incomplete')
  }
  const stopStatus = requirePublicState(['STOPPING', 'CANCELLED'] as const, raw.stopStatus, 'DesignerStopResult')
  return { stopStatus, archived: raw.archived, stoppedSessions: raw.stoppedSessions,
    failedSessions: raw.failedSessions, pendingFinalizations: raw.pendingFinalizations }
}

function normalizeDesignerStreamEvent(value: unknown): DesignerStreamEvent {
  const raw = asRecord(value)
  const type = asString(raw.type).toUpperCase()
  return {
    sequence: asNumber(raw.sequence),
    sessionId: asString(raw.sessionId),
    type: ['SNAPSHOT', 'STATUS', 'PARTIAL', 'COMPLETED', 'ERROR', 'AUTO_MODE'].includes(type) ? type as DesignerStreamEvent['type'] : 'STATUS',
    state: normalizeDesignerState(raw.state),
    workflowPhase: normalizeWorkflowPhase(raw.workflowPhase),
    activeActor: normalizeDesignerActor(raw.activeActor),
    remoteState: asString(raw.remoteState) || undefined,
    runtimeConnected: raw.runtimeConnected === true,
    content: asString(raw.content),
    detail: asString(raw.detail),
    at: asString(raw.at) || new Date().toISOString(),
    requirementRevision: typeof raw.requirementRevision === 'number' ? raw.requirementRevision : undefined,
    activeWorkPackageId: asString(raw.activeWorkPackageId) || undefined,
    modelCallsUsed: asNumber(raw.modelCallsUsed),
    maxModelCalls: asNumber(raw.maxModelCalls, 96),
    structuredStep: normalizeStructuredStep(raw.structuredStep),
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
      ...(stage.workPackageId ? { workPackageId: stage.workPackageId } : {}),
      objective: stage.objective,
      allowedPaths: stage.allowedPaths,
      forbiddenPaths: stage.forbiddenPaths,
      deliverables: stage.deliverables,
      ...(stage.implementationKind ? { implementationKind: stage.implementationKind } : {}),
      acceptanceCriteria: stage.acceptanceCriteria ?? [],
      ...(stage.verificationRuntime ? { verificationRuntime: stage.verificationRuntime } : {}),
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
        ...(verifier.criterionIds?.length ? { criterionIds: verifier.criterionIds } : {}),
        ...(verifier.processPurpose ? { processPurpose: verifier.processPurpose } : {}),
        ...(verifier.testTargets?.length ? { testTargets: verifier.testTargets } : {}),
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

function normalizeGitDiffScopeApproval(value: unknown): GitDiffScopeApproval {
  const raw = asRecord(value)
  const changeTypes = ['MODIFIED', 'DELETED', 'RENAMED_FROM', 'RENAMED_TO'] as const
  return {
    requestId: requiredString(raw, 'requestId', 'GitDiffScopeApproval'),
    taskId: requiredString(raw, 'taskId', 'GitDiffScopeApproval'),
    stageId: requiredString(raw, 'stageId', 'GitDiffScopeApproval'),
    attemptId: requiredString(raw, 'attemptId', 'GitDiffScopeApproval'),
    taskVersion: asNumber(raw.taskVersion),
    files: asArray(raw.files).map((value) => {
      const file = asRecord(value)
      const rawChangeType = asString(file.changeType)
      return {
        path: requiredString(file, 'path', 'GitDiffScopeApprovalFile'),
        changeType: changeTypes.includes(rawChangeType as typeof changeTypes[number])
          ? rawChangeType as typeof changeTypes[number]
          : 'MODIFIED',
        patchSha256: requiredString(file, 'patchSha256', 'GitDiffScopeApprovalFile'),
      }
    }),
  }
}

function normalizeTaskPublication(value: unknown): TaskPublicationStatus {
  const raw = asRecord(value)
  const state = asString(raw.state)
  const provider = asString(raw.provider)
  return {
    state: ['UNAVAILABLE', 'NO_CHANGES', 'READY', 'COMMITTED', 'PUSHED', 'SYNCED_LOCAL', 'LOCAL_SYNC_CONFLICT', 'MERGE_REQUEST_OPENED', 'MERGE_REQUEST_CLOSED', 'MERGED'].includes(state) ? state as TaskPublicationStatus['state'] : 'UNAVAILABLE',
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
    deliveryState: (['NOT_STARTED', 'COMMITTED', 'PUSHED', 'MERGE_REQUEST_OPENED', 'MERGE_REQUEST_CLOSED', 'MERGED', 'LOCAL_COMPLETED', 'NOT_APPLICABLE'].includes(asString(raw.deliveryState)) ? asString(raw.deliveryState) : 'NOT_STARTED') as TaskPublicationStatus['deliveryState'],
    deliveryFinal: raw.deliveryFinal === true,
    creationRequestedAt: asString(raw.creationRequestedAt) || undefined,
    mergeRequest: raw.mergeRequest && typeof raw.mergeRequest === 'object' ? (() => { const mr = asRecord(raw.mergeRequest); const mrProvider = asString(mr.provider); return { provider: (mrProvider === 'GITLAB' || mrProvider === 'GITHUB' ? mrProvider : 'UNKNOWN') as 'GITLAB' | 'GITHUB' | 'UNKNOWN', iid: asNumber(mr.iid), url: asString(mr.url) || undefined, state: asString(mr.state) || undefined, sourceBranch: asString(mr.sourceBranch) || undefined, targetBranch: asString(mr.targetBranch) || undefined, headSha: asString(mr.headSha) || undefined, mergeCommitSha: asString(mr.mergeCommitSha) || undefined, openedAt: asString(mr.openedAt) || undefined, mergedAt: asString(mr.mergedAt) || undefined, checkedAt: asString(mr.checkedAt) || undefined } })() : undefined,
    reconciliationAvailable: raw.reconciliationAvailable === true,
    lastCheckError: asString(raw.lastCheckError) || undefined,
    lastCheckedAt: asString(raw.lastCheckedAt) || undefined,
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
  if (mode !== 'FROM_FAILED_STAGE' && mode !== 'ALL_STAGES' && mode !== 'VERIFY_ONLY' && mode !== 'REWORK_ALL_STAGES' && mode !== 'INHERIT_CHANGES') {
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

function normalizeTaskDecision(value: unknown): TaskDecision {
  const raw = asRecord(value)
  const cycle = asRecord(raw.cycle)
  const checkpoint = asRecord(raw.checkpoint)
  return {
    taskId: requiredString(raw, 'taskId', 'TaskDecision'),
    taskState: requirePublicState(TASK_STATUSES, raw.taskState, 'TaskDecision'),
    taskVersion: asNumber(raw.taskVersion),
    cycle: raw.cycle ? {
      id: requiredString(cycle, 'id', 'TaskDecision.cycle'), ordinal: asNumber(cycle.ordinal),
      kind: asString(cycle.kind) as NonNullable<TaskDecision['cycle']>['kind'],
      result: asString(cycle.result) as NonNullable<TaskDecision['cycle']>['result'],
      startStageId: asString(cycle.startStageId) || undefined,
      startStageOrdinal: typeof cycle.startStageOrdinal === 'number' ? cycle.startStageOrdinal : undefined,
      failureCode: asString(cycle.failureCode) || undefined, failureMessage: asString(cycle.failureMessage) || undefined,
      authorizedAt: asString(cycle.authorizedAt), startedAt: asString(cycle.startedAt),
      endedAt: asString(cycle.endedAt) || undefined, version: asNumber(cycle.version),
    } : undefined,
    checkpoint: raw.checkpoint ? {
      id: requiredString(checkpoint, 'id', 'TaskDecision.checkpoint'),
      state: asString(checkpoint.state) as NonNullable<TaskDecision['checkpoint']>['state'],
      snapshotId: asString(checkpoint.snapshotId) || undefined,
      checkpointTree: asString(checkpoint.checkpointTree) || undefined,
      changedFileCount: asNumber(checkpoint.changedFileCount, -1),
      blockerCode: asString(checkpoint.blockerCode) || undefined,
      blockerMessage: asString(checkpoint.blockerMessage) || undefined,
      updatedAt: asString(checkpoint.updatedAt), version: asNumber(checkpoint.version),
    } : undefined,
    availableActions: asArray(raw.availableActions).map(String) as TaskDecision['availableActions'],
    stages: asArray(raw.stages).map((value) => { const stage = asRecord(value); return { id: asString(stage.id), ordinal: asNumber(stage.ordinal), objective: asString(stage.objective), state: asString(stage.state) } }),
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

type RollingPackageCommandVersions = {
  expectedTaskVersion: number
  expectedPackageVersion: number
  expectedDiscussionRevision: number
  expectedDesignRevision: number
}

export const api = {
  getProjects: async (refresh = false) => (await request<unknown[]>(`/projects/summaries${refresh ? '?refresh=true' : ''}`)).map(normalizeProject),
  pickProjectDirectory: async (): Promise<DirectorySelection> => {
    const raw = asRecord(await request<unknown>('/projects/pick-directory', { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } }))
    return { selected: raw.selected === true, path: asString(raw.path) || undefined, name: asString(raw.name) || undefined }
  },
  createProject: async (input: Pick<Project, 'name' | 'rootPath' | 'description'>) => normalizeProject(await request<unknown>('/projects', { method: 'POST', body: JSON.stringify({ name: input.name, rootPath: input.rootPath, description: input.description?.trim() || '' }) })),
  getProjectStackProfile: async (projectId: string) => normalizeProjectStackProfile(await request<unknown>(`/projects/${encodeURIComponent(projectId)}/stack-profile`)),
  cancelProjectManagement: async (projectId: string) => request<void>(`/projects/${encodeURIComponent(projectId)}`, { method: 'DELETE', headers: { 'X-Loopper-Local-UI': '1' } }),
  getCurrentProjectConvention: async (projectId: string) => normalizeProjectConventionSnapshot(await request<unknown>(`/projects/${encodeURIComponent(projectId)}/agents-md`)),
  generateProjectConvention: async (projectId: string) => normalizeProjectConvention(await request<unknown>(`/projects/${encodeURIComponent(projectId)}/agents-md`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  getProjectConventionDraft: async (projectId: string, draftId: string) => normalizeProjectConvention(await request<unknown>(`/projects/${encodeURIComponent(projectId)}/agents-md/${encodeURIComponent(draftId)}`)),
  getProjectConventionActivity: async (projectId: string, draftId: string) => normalizeProjectConventionActivity(await request<unknown>(`/projects/${encodeURIComponent(projectId)}/agents-md/${encodeURIComponent(draftId)}/activity`)),
  cancelProjectConvention: async (projectId: string, draftId: string) => normalizeProjectConvention(await request<unknown>(`/projects/${encodeURIComponent(projectId)}/agents-md/${encodeURIComponent(draftId)}`, { method: 'DELETE', headers: { 'X-Loopper-Local-UI': '1' } })),
  applyProjectConvention: async (projectId: string, draftId: string) => normalizeProjectConvention(await request<unknown>(`/projects/${encodeURIComponent(projectId)}/agents-md/${encodeURIComponent(draftId)}`, { method: 'PUT', headers: { 'X-Loopper-Local-UI': '1' } })),
  getTasks: async () => (await request<unknown[]>('/tasks')).map(normalizeTask),
  getTask: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}`)),
  getTaskSummaries: async (input: TaskSummaryQuery = {}) => normalizeTaskPage(await request<unknown>(`/tasks/summaries${pageQuery(input)}`)),
  getTaskOverview: async (id: string) => normalizeTaskOverview(await request<unknown>(`/tasks/${encodeURIComponent(id)}/overview`)),
  getRollingPackageWorkbench: async (taskId: string) => normalizeRollingWorkbench(await request<unknown>(`/tasks/${encodeURIComponent(taskId)}/packages`)),
  getRollingPackageDetail: async (taskId: string, runId: string) => normalizeRollingDetail(await request<unknown>(`/tasks/${encodeURIComponent(taskId)}/packages/${encodeURIComponent(runId)}`)),
  getRollingPackageFact: async (taskId: string, runId: string) => normalizeRollingFact(await request<unknown>(`/tasks/${encodeURIComponent(taskId)}/packages/${encodeURIComponent(runId)}/fact`)),
  startRollingPackage: async (taskId: string, runId: string, input: RollingPackageCommandVersions) => request<void>(`/tasks/${encodeURIComponent(taskId)}/packages/${encodeURIComponent(runId)}/start`, { method: 'POST', body: JSON.stringify(input) }),
  approveRollingPackageDesign: async (taskId: string, runId: string, input: RollingPackageCommandVersions) => request<void>(`/tasks/${encodeURIComponent(taskId)}/packages/${encodeURIComponent(runId)}/approve-design`, { method: 'POST', body: JSON.stringify(input) }),
  discussRollingPackage: async (taskId: string, runId: string, input: RollingPackageCommandVersions & { content: string }) => request<void>(`/tasks/${encodeURIComponent(taskId)}/packages/${encodeURIComponent(runId)}/messages`, { method: 'POST', body: JSON.stringify(input) }),
  redesignRollingPackage: async (taskId: string, runId: string, input: RollingPackageCommandVersions) => request<void>(`/tasks/${encodeURIComponent(taskId)}/packages/${encodeURIComponent(runId)}/redesign`, { method: 'POST', body: JSON.stringify(input) }),
  resumeRollingPackageDesign: async (taskId: string, runId: string, input: RollingPackageCommandVersions) => request<void>(`/tasks/${encodeURIComponent(taskId)}/packages/${encodeURIComponent(runId)}/resume-design`, { method: 'POST', body: JSON.stringify(input) }),
  retryRollingPackageCheckpoint: async (taskId: string, runId: string, input: RollingPackageCommandVersions) => request<void>(`/tasks/${encodeURIComponent(taskId)}/packages/${encodeURIComponent(runId)}/retry-checkpoint`, { method: 'POST', body: JSON.stringify(input) }),
  resolveRollingPackageFailure: async (taskId: string, runId: string, input: RollingPackageCommandVersions & { action: 'CONTINUE_CANDIDATE' | 'REDESIGN_FROM_PREVIOUS' | 'ABANDON_TASK' }) => request<void>(`/tasks/${encodeURIComponent(taskId)}/packages/${encodeURIComponent(runId)}/continue-failure`, { method: 'POST', body: JSON.stringify(input) }),
  proposeRollingPlan: async (taskId: string, input: RollingPackageCommandVersions & { expectedPackageRunId: string; packages: RollingPlanPackage[] }) => request<RollingPlanProposal>(`/tasks/${encodeURIComponent(taskId)}/packages/plan-revisions`, { method: 'POST', body: JSON.stringify(input) }),
  suggestRollingPlan: async (taskId: string, input: RollingPackageCommandVersions & { expectedPackageRunId: string }) => request<RollingPlanProposal>(`/tasks/${encodeURIComponent(taskId)}/packages/plan-revisions/suggest`, { method: 'POST', body: JSON.stringify(input) }),
  getRollingPlanRevisions: async (taskId: string) => request<RollingPlanProposal[]>(`/tasks/${encodeURIComponent(taskId)}/packages/plan-revisions`),
  confirmRollingPlan: async (taskId: string, proposalId: string, input: RollingPackageCommandVersions & { expectedPackageRunId: string; expectedProposalVersion: number }) => request<RollingPlanProposal>(`/tasks/${encodeURIComponent(taskId)}/packages/plan-revisions/${encodeURIComponent(proposalId)}/confirm`, { method: 'POST', body: JSON.stringify(input) }),
  addRollingCorrection: async (taskId: string, input: RollingPackageCommandVersions & { correctionOfPackageRunId: string; title?: string; objective?: string }) => request<RollingPlanProposal>(`/tasks/${encodeURIComponent(taskId)}/packages/corrections`, { method: 'POST', body: JSON.stringify(input) }),
  getTaskAudit: async (id: string) => normalizeTaskAudit(await request<unknown>(`/tasks/${encodeURIComponent(id)}/audit`), id),
  getVerificationEvidence: async (taskId: string, id: string) => normalizeReadContent(await request<unknown>(`/tasks/${encodeURIComponent(taskId)}/verifications/${encodeURIComponent(id)}/evidence`)),
  getErrorEvidence: async (taskId: string, id: string) => normalizeReadContent(await request<unknown>(`/tasks/${encodeURIComponent(taskId)}/errors/${encodeURIComponent(id)}/evidence`)),
  getJudgeOutput: async (taskId: string, id: string) => normalizeReadContent(await request<unknown>(`/tasks/${encodeURIComponent(taskId)}/judges/${encodeURIComponent(id)}/output`)),
  getArtifactContent: async (taskId: string, id: string) => normalizeReadContent(await request<unknown>(`/tasks/${encodeURIComponent(taskId)}/artifacts/${encodeURIComponent(id)}/content`)),
  getTaskQueue: async (id: string) => normalizeTaskQueueStatus(await request<unknown>(`/tasks/${encodeURIComponent(id)}/queue`)),
  reconcileTaskQueue: async (id: string) => normalizeTaskQueueStatus(await request<unknown>(`/tasks/${encodeURIComponent(id)}/queue/reconcile`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  getDirtyWorkspace: async (id: string) => normalizeDirtyWorkspace(await request<unknown>(`/tasks/${encodeURIComponent(id)}/workspace-dirty`)),
  resolveDirtyWorkspace: async (id: string, input: { snapshotId: string; resolutions: Array<{ path: string; action: DirtyWorkspaceAction }>; commitMessage?: string }) => normalizeDirtyWorkspaceResolution(await request<unknown>(`/tasks/${encodeURIComponent(id)}/workspace-dirty/resolve`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  cancelDirtyWorkspace: async (id: string) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/workspace-dirty/cancel`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  getTaskRecoveries: async (id: string) => (await request<unknown[]>(`/tasks/${encodeURIComponent(id)}/recoveries`)).map(normalizeRecovery),
  createTaskRecovery: async (id: string, mode: RecoveryMode) => normalizeRecovery(await request<unknown>(`/tasks/${encodeURIComponent(id)}/recoveries`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ mode }) })),
  getTaskDecision: async (id: string) => normalizeTaskDecision(await request<unknown>(`/tasks/${encodeURIComponent(id)}/decision`)),
  continueTaskDecision: async (id: string, input: { expectedTaskVersion: number; expectedCycleVersion: number; stageId?: string; supplementalRequirement?: string }) => normalizeTaskDecision(await request<unknown>(`/tasks/${encodeURIComponent(id)}/decision/continue`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  deriveTaskDecision: async (id: string, input: { expectedTaskVersion: number; expectedCycleVersion: number; mode: 'INHERIT_CHANGES' | 'REWORK_ALL_STAGES' }) => normalizeRecovery(await request<unknown>(`/tasks/${encodeURIComponent(id)}/decision/derive`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  auditTaskDecision: async (id: string, input: { expectedTaskVersion: number; expectedCycleVersion: number }) => normalizeRecovery(await request<unknown>(`/tasks/${encodeURIComponent(id)}/decision/audit`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  acceptTaskDecision: async (id: string, input: { expectedTaskVersion: number; expectedCycleVersion: number }) => normalizeTaskDecision(await request<unknown>(`/tasks/${encodeURIComponent(id)}/decision/accept`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  cancelTaskDecision: async (id: string, input: { expectedTaskVersion: number; expectedCycleVersion: number }) => normalizeTaskDecision(await request<unknown>(`/tasks/${encodeURIComponent(id)}/decision/cancel`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  getTaskDiffPreview: async (id: string, path: string) => normalizeTaskDiffPreview(await request<unknown>(`/tasks/${encodeURIComponent(id)}/diff-preview?path=${encodeURIComponent(path)}`)),
  getGitDiffScopeApproval: async (id: string) => {
    const value = await request<unknown>(`/tasks/${encodeURIComponent(id)}/git-diff-scope-approval`)
    return value === undefined ? undefined : normalizeGitDiffScopeApproval(value)
  },
  getGitDiffScopeApprovalPreview: async (id: string, requestId: string, path: string) => normalizeTaskDiffPreview(await request<unknown>(`/tasks/${encodeURIComponent(id)}/git-diff-scope-approval/${encodeURIComponent(requestId)}/diff-preview?path=${encodeURIComponent(path)}`)),
  resolveGitDiffScopeApproval: async (id: string, requestId: string, input: { expectedTaskVersion: number; decisions: Array<{ path: string; action: GitDiffScopeDecisionAction; patchSha256: string }> }) => normalizeTask(await request<unknown>(`/tasks/${encodeURIComponent(id)}/git-diff-scope-approval/${encodeURIComponent(requestId)}/resolve`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  getTaskDesignHistory: async (id: string) => normalizeTaskDesignHistory(await request<unknown>(`/tasks/${encodeURIComponent(id)}/design-history`)),
  getTaskDesignAttachmentPreview: async (id: string, attachmentId: string) => {
    const raw = asRecord(await request<unknown>(`/tasks/${encodeURIComponent(id)}/design-attachments/${encodeURIComponent(attachmentId)}/preview`))
    return { filename: asString(raw.filename), previewKind: asString(raw.previewKind), mediaType: asString(raw.mediaType), text: asString(raw.text) || undefined, inlineContentAvailable: raw.inlineContentAvailable === true }
  },
  taskDesignAttachmentContentUrl: (id: string, attachmentId: string) => `${apiBase}/tasks/${encodeURIComponent(id)}/design-attachments/${encodeURIComponent(attachmentId)}/content`,
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
  getInsightsPage: async (cursor?: string) => {
    const raw = asRecord(await request<unknown>(`/insights/page${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`))
    return { tasks: asArray(raw.items).length ? asArray(raw.items).map(normalizeTaskInsight) : asArray(raw.tasks).map(normalizeTaskInsight), usage: normalizeUsageAggregate(raw.usage),
      generatedAt: asString(raw.generatedAt), nextCursor: asString(raw.nextCursor) || undefined }
  },
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
  getAutomationWorkspace: async () => {
    const raw = asRecord(await request<unknown>('/automations/workspace'))
    return { templates: asArray(raw.templates).map(normalizeTemplate),
      rules: asArray(raw.rules).map(normalizeAutomationRule), runs: asArray(raw.runs).map(normalizeAutomationRun),
      serverTime: requiredString(raw, 'serverTime', 'AutomationWorkspace') }
  },
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
  reconcileTaskPublication: async (id: string) => normalizeTaskPublication(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/reconcile`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  createLocalSyncConflictSession: async (id: string) => normalizeLocalSyncSession(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  getLocalSyncConflictSession: async (id: string, sessionId: string) => normalizeLocalSyncSession(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}`)),
  getLocalSyncConflictFiles: async (id: string, sessionId: string) => (await request<unknown[]>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}/files`)).map(normalizeLocalSyncFile),
  getLocalSyncConflictContent: async (id: string, sessionId: string, path: string) => normalizeLocalSyncContent(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}/file?path=${encodeURIComponent(path)}`)),
  saveLocalSyncResolution: async (id: string, sessionId: string, input: { path: string; resolution: Exclude<LocalSyncResolution, 'AUTO'>; content?: string; expectedVersion: number }) => normalizeLocalSyncContent(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}/resolution`, { method: 'PUT', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  suggestLocalSyncResolution: async (id: string, sessionId: string, input: { path: string; expectedVersion: number }) => request<{ path: string; suggestion: string; automaticallySelected: boolean; version: number }>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}/ai-suggestion`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) }),
  applyLocalSyncConflict: async (id: string, sessionId: string, input: { confirmed: boolean; expectedVersion: number }) => normalizeLocalSyncSession(await request<unknown>(`/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(sessionId)}/apply`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(input) })),
  getRuntime: async () => normalizeRuntime(await request<unknown>('/runtime/opencode')),
  startRuntime: async () => normalizeRuntime(await request<unknown>('/runtime/opencode/start', { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  restartRuntime: async () => normalizeRuntime(await request<unknown>('/runtime/opencode/restart', { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  getSettings: async () => normalizeSettings(await request<unknown>('/settings')),
  updateSettings: async (settings: AppSettings) => normalizeSettings(await request<unknown>('/settings', { method: 'PUT', body: JSON.stringify(settings) })),
  getSettingsModels: async (cliPath?: string) => (await request<unknown[]>(`/settings/models${cliPath ? `?cliPath=${encodeURIComponent(cliPath)}` : ''}`)).map(normalizeAvailableModel),
  createDraft: async (spec: LoopSpec) => normalizeDraft(await request<unknown>('/loop-drafts', { method: 'POST', body: JSON.stringify({ spec: backendLoopSpec(spec) }) })),
  validateDraft: async (spec: LoopSpec) => request<LoopSpecAssessment>('/loop-drafts/validate', { method: 'POST', body: JSON.stringify({ spec: backendLoopSpec(spec) }) }),
  copyDraftAsV2: async (id: string) => normalizeDraft(await request<unknown>(`/loop-drafts/${encodeURIComponent(id)}/copy-v2`, { method: 'POST' })),
  getDraft: async (id: string) => normalizeDraft(await request<unknown>(`/loop-drafts/${encodeURIComponent(id)}`)),
  updateDraft: async (id: string, spec: LoopDraft['spec']) => normalizeDraft(await request<unknown>(`/loop-drafts/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ spec: backendLoopSpec(spec) }) })),
  confirmDraft: async (id: string) => { const task = asRecord(await request<unknown>(`/loop-drafts/${encodeURIComponent(id)}/confirm`, { method: 'POST' })); return { taskId: asString(task.taskId) } },
  createDesignerSession: async (projectId: string, draftId: string, initialMessage?: string, autoModeEnabled = false) => normalizeDesignerSession(await request<unknown>('/designer-sessions', { method: 'POST', headers: autoModeEnabled ? { 'X-Loopper-Local-UI': '1' } : undefined, body: JSON.stringify({ projectId, draftId, ...(initialMessage ? { initialMessage } : {}), autoModeEnabled }) })),
  createDesignerContextTurn: async (input: { submissionId: string; projectId: string; draftId: string; content: string; autoModeEnabled: boolean }, files: File[]) => normalizeDesignerSession(await request<unknown>('/designer-sessions/context-turns', {
    method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: designerContextForm(input, files),
  })),
  listOpenDesignerSessions: async (projectId: string) => (await request<unknown[]>(`/designer-sessions?projectId=${encodeURIComponent(projectId)}`)).map(normalizeDesignerSessionSummary),
  listDesignerHistory: async (projectId?: string) => (await request<unknown[]>(`/designer-sessions/history${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`)).map(normalizeDesignerHistoryItem),
  listDesignerHistoryPage: async (input: DesignerHistoryQuery = {}) => {
    const query = new URLSearchParams()
    if (input.projectId) query.set('projectId', input.projectId)
    if (input.status) query.set('status', input.status)
    if (input.archive) query.set('archive', input.archive)
    if (input.q) query.set('q', input.q)
    if (input.order) query.set('order', input.order)
    if (input.cursor) query.set('cursor', input.cursor)
    if (input.limit) query.set('limit', String(input.limit))
    const encoded = query.toString()
    return normalizeDesignerHistoryPage(await request<unknown>(`/designer-sessions/history-page${encoded ? `?${encoded}` : ''}`))
  },
  archiveDesignerSession: async (id: string) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/archive`, { method: 'PUT', headers: { 'X-Loopper-Local-UI': '1' } }),
  restoreDesignerSession: async (id: string) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/archive`, { method: 'DELETE', headers: { 'X-Loopper-Local-UI': '1' } }),
  getDesignerSession: async (id: string) => normalizeDesignerSession(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}`)),
  getAnalysisReport: async (id: string, reportId: string) => normalizeAnalysisReport(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/reports/${encodeURIComponent(reportId)}`)),
  convertAnalysisReportToDesign: async (id: string, reportId: string) => normalizeDesignerSession(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/reports/${encodeURIComponent(reportId)}/convert-to-design`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  updateDesignerAutoMode: async (id: string, enabled: boolean, expectedVersion: number) => {
    const updated = asRecord(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/auto-mode`, { method: 'PUT', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ enabled, expectedVersion }) }))
    const state = asString(updated.state)
    return {
      enabled: updated.enabled === true,
      state: (['DISABLED', 'ACTIVE', 'BLOCKED', 'COMPLETED'].includes(state) ? state : 'DISABLED') as DesignerSession['autoMode']['state'],
      version: asNumber(updated.version), lastAction: asString(updated.lastAction) || undefined,
      errorCode: asString(updated.errorCode) || undefined, errorDetail: asString(updated.errorDetail) || undefined,
      taskId: asString(updated.taskId) || undefined, updatedAt: asString(updated.updatedAt) || undefined,
    }
  },
  getDesignerMessages: async (id: string) => (await request<unknown[]>(`/designer-sessions/${encodeURIComponent(id)}/messages`)).map(normalizeDesignerMessage),
  getDesignerAttachmentPreview: async (id: string, attachmentId: string) => {
    const raw = asRecord(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/attachments/${encodeURIComponent(attachmentId)}/preview`))
    return { filename: asString(raw.filename), previewKind: asString(raw.previewKind), mediaType: asString(raw.mediaType), text: asString(raw.text) || undefined, inlineContentAvailable: raw.inlineContentAvailable === true }
  },
  designerAttachmentContentUrl: (id: string, attachmentId: string) => `${apiBase}/designer-sessions/${encodeURIComponent(id)}/attachments/${encodeURIComponent(attachmentId)}/content`,
  stopDesignerAttachment: async (id: string, attachmentId: string, commandId: string) => request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/attachments/${encodeURIComponent(attachmentId)}/stop-future-use`, {
    method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ commandId }),
  }),
  replyDesignerQuestion: async (id: string, questionId: string, answers: string[][]) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/reply`, { method: 'POST', body: JSON.stringify({ answers }) }),
  rejectDesignerQuestion: async (id: string, questionId: string) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/questions/${encodeURIComponent(questionId)}/reject`, { method: 'POST' }),
  retryDesignerCompiler: async (id: string) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/compiler/retry`, { method: 'POST' }),
  requestDesignerRedesign: async (id: string) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/redesign`, { method: 'POST' }),
  retryDesignerDecomposition: async (id: string) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/decomposition/retry`, { method: 'POST' }),
  retryWorkPackageCompiler: async (id: string, packageId: string) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/work-packages/${encodeURIComponent(packageId)}/compiler/retry`, { method: 'POST' }),
  redesignWorkPackage: async (id: string, packageId: string) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/work-packages/${encodeURIComponent(packageId)}/redesign`, { method: 'POST' }),
  sendRequirementMessage: async (id: string, content: string, expectedDiscussionRevision: number): Promise<DesignerAppendResult> => {
    const raw = asRecord(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/requirement/messages`, { method: 'POST', body: JSON.stringify({ content, expectedDiscussionRevision }) }))
    return { sessionId: asString(raw.sessionId), state: normalizeDesignerState(raw.state), persistedMessages: asArray(raw.persistedMessages).map(normalizeDesignerMessage), notice: asString(raw.notice) }
  },
  sendDesignerContextTurn: async (id: string, input: { submissionId: string; content: string; scopeKey: string; workPackageId?: string; expectedDiscussionRevision: number; expectedDesignRevision: number }, files: File[]): Promise<DesignerAppendResult> => {
    const raw = asRecord(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/context-turns`, {
      method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: designerContextForm(input, files),
    }))
    return { sessionId: asString(raw.sessionId), state: normalizeDesignerState(raw.state), persistedMessages: asArray(raw.persistedMessages).map(normalizeDesignerMessage), notice: asString(raw.notice) }
  },
  confirmDesignerRequirement: async (id: string, expectedDiscussionRevision: number) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/requirement/confirm`, { method: 'POST', body: JSON.stringify({ expectedDiscussionRevision }) }),
  previewDesignerTaskProfileUpdate: async (id: string, intent: DesignerSession['taskProfile']['intent'], primaryArtifactKind: DesignerSession['taskProfile']['artifactKinds'][number], expectedVersion: number, largeTaskMode?: boolean, componentKeys?: string[]) => request<DesignerTaskProfileUpdatePreview>(`/designer-sessions/${encodeURIComponent(id)}/task-profile/preview`, { method: 'POST', body: JSON.stringify({ intent, primaryArtifactKind, expectedVersion, largeTaskMode, componentKeys }) }),
  updateDesignerTaskProfile: async (id: string, intent: DesignerSession['taskProfile']['intent'], primaryArtifactKind: DesignerSession['taskProfile']['artifactKinds'][number], expectedVersion: number, largeTaskMode?: boolean, componentKeys?: string[]) => request<DesignerSession['taskProfile']>(`/designer-sessions/${encodeURIComponent(id)}/task-profile`, { method: 'PUT', body: JSON.stringify({ intent, primaryArtifactKind, expectedVersion, largeTaskMode, componentKeys }) }),
  confirmDesignerTaskProfile: async (id: string, expectedVersion: number) => request<DesignerSession['taskProfile']>(`/designer-sessions/${encodeURIComponent(id)}/task-profile/confirm`, { method: 'POST', body: JSON.stringify({ expectedVersion }) }),
  rerouteDesignerTaskProfile: async (id: string, expectedRunId: string, expectedProfileVersion: number) => request<NonNullable<DesignerSession['routerRun']>>(`/designer-sessions/${encodeURIComponent(id)}/task-profile/reroute`, { method: 'POST', body: JSON.stringify({ expectedRunId, expectedProfileVersion }) }),
  cancelDesignerTaskProfileRouting: async (id: string, expectedRunId: string) => request<DesignerSession['taskProfile']>(`/designer-sessions/${encodeURIComponent(id)}/task-profile/cancel`, { method: 'POST', body: JSON.stringify({ expectedRunId }) }),
  getDesignerActivity: async (id: string): Promise<DesignerActivity> => {
    const raw = asRecord(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/activity`))
    const usage = asRecord(raw.usage)
    return { actor: normalizeDesignerActor(raw.actor), remoteState: asString(raw.remoteState), connected: raw.connected === true, observedAt: asString(raw.observedAt), structuredStep: normalizeStructuredStep(raw.structuredStep), parts: asArray(raw.parts).map((value) => { const part = asRecord(value); return { id: asString(part.id), type: asString(part.type) as TaskSessionActivityPart['type'], label: asString(part.label), content: asString(part.content), status: asString(part.status), startedAt: asString(part.startedAt) } }), detail: asString(raw.detail) || undefined, usage: { totalTokens: asNullableNumber(usage.totalTokens), unknownUsageCount: asNumber(usage.unknownUsageCount), observedAt: asString(usage.observedAt) } }
  },
  stopDesignerSession: async (id: string): Promise<DesignerStopResult> => normalizeDesignerStopResult(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/stop`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' } })),
  enableDesignerLargeTaskMode: async (id: string, expectedDiscussionRevision: number, expectedProfileVersion: number) => request<DesignerSession['taskProfile']>(`/designer-sessions/${encodeURIComponent(id)}/large-task-mode/enable`, { method: 'POST', body: JSON.stringify({ expectedDiscussionRevision, expectedProfileVersion }) }),
  reopenDesignerRequirement: async (id: string, expectedDiscussionRevision: number) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/requirement/reopen`, { method: 'POST', body: JSON.stringify({ expectedDiscussionRevision }) }),
  sendWorkPackageMessage: async (id: string, packageId: string, content: string, expectedDiscussionRevision: number, expectedDesignRevision: number): Promise<DesignerAppendResult> => {
    const raw = asRecord(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/work-packages/${encodeURIComponent(packageId)}/messages`, { method: 'POST', body: JSON.stringify({ content, expectedDiscussionRevision, expectedDesignRevision }) }))
    return { sessionId: asString(raw.sessionId), state: normalizeDesignerState(raw.state), persistedMessages: asArray(raw.persistedMessages).map(normalizeDesignerMessage), notice: asString(raw.notice) }
  },
  approveWorkPackage: async (id: string, packageId: string, expectedDiscussionRevision: number, expectedDesignRevision: number) => request<void>(`/designer-sessions/${encodeURIComponent(id)}/work-packages/${encodeURIComponent(packageId)}/approve`, { method: 'POST', body: JSON.stringify({ expectedDiscussionRevision, expectedDesignRevision }) }),
  reopenWorkPackage: async (id: string, packageId: string, expectedDiscussionRevision: number, expectedDesignRevision: number) => {
    const raw = asRecord(await request<unknown>(`/designer-sessions/${encodeURIComponent(id)}/work-packages/${encodeURIComponent(packageId)}/reopen`, { method: 'POST', body: JSON.stringify({ expectedDiscussionRevision, expectedDesignRevision }) }))
    return asArray(raw.invalidatedPackageIds).map(String)
  },
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
