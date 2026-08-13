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
  executionMode?: 'WORKTREE' | 'DIRECT' | 'UNAVAILABLE'
  updatedAt: string
  taskCount: number
}

export interface DirectorySelection {
  selected: boolean
  path?: string
  name?: string
}

export interface ProjectConventionDraft {
  id: string
  projectId: string
  state: 'RUNNING' | 'READY' | 'APPLIED' | 'FAILED'
  operation: 'CREATE' | 'UPDATE'
  readOnlyGeneration: boolean
  content?: string
  error?: string
  updatedAt: string
}

export interface ProjectConventionSnapshot {
  projectId: string
  exists: boolean
  loopperManaged: boolean
  content: string
}

export interface RuntimeInfo {
  loopperVersion?: string
  status: 'ONLINE' | 'OFFLINE' | 'STARTING' | 'INCOMPATIBLE'
  version?: string
  managed: boolean
  pid?: number
  endpoint?: string
  model?: string
  checkedAt: string
  startupFailure?: string
}

export interface AppSettings {
  cliPath: string
  allowedRoot: string
  provider: string
  model: string
  maxTaskAttempts: number
  timeoutMinutes: number
  autoApprove: boolean
  updatedAt?: string
}

export interface AvailableModel {
  id: string
  provider: string
  model: string
  label: string
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
  evidence?: Record<string, unknown>
}

export type DirtyWorkspaceAction = 'COMMIT' | 'STASH' | 'REMOVE'

export interface DirtyWorkspaceFile {
  path: string
  originalPath?: string
  indexStatus: string
  workTreeStatus: string
  untracked: boolean
}

export interface DirtyWorkspaceState {
  branch: string
  head: string
  snapshotId: string
  clean: boolean
  files: DirtyWorkspaceFile[]
}

export interface DirtyWorkspaceResolution {
  task: Task
  workspace: DirtyWorkspaceState
}

export interface VerifierResult {
  id: string
  name: string
  status: 'PASS' | 'FAIL' | 'PENDING'
  summary: string
  output?: string
  evidence?: Record<string, unknown>
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
  workPackageId?: string
  objective: string
  status: 'PENDING' | 'RUNNING' | 'VERIFYING' | 'PAUSED' | 'SUCCEEDED' | 'BLOCKED'
  attempts: Attempt[]
}

export interface TaskWorkPackageProgress {
  id: string
  ordinal: number
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  stageCount: number
  completedStages: number
  attemptCount: number
  attemptLimit: number
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
  waitingReasonCode?: string
  loopRetryAvailable?: boolean
  hasDesignHistory?: boolean
  archived?: boolean
  activeStage?: number
  attemptCount: number
  maxAttempts: number
  createdAt: string
  updatedAt: string
  stages?: Stage[]
  workPackages?: TaskWorkPackageProgress[]
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

export interface TaskDiffPreview {
  path: string
  changeType: 'NEW' | 'MODIFIED'
  patch: string
  truncated: boolean
}

export interface TaskPublicationStatus {
  state: 'UNAVAILABLE' | 'NO_CHANGES' | 'READY' | 'COMMITTED' | 'PUSHED' | 'SYNCED_LOCAL' | 'LOCAL_SYNC_CONFLICT' | 'MERGE_REQUEST_OPENED' | 'MERGE_REQUEST_CLOSED' | 'MERGED'
  available: boolean
  reason?: string
  branch?: string
  remoteName?: string
  remoteUrl?: string
  commitSha?: string
  commitMessage?: string
  targetBranch?: string
  targetBranches: string[]
  provider: 'GITLAB' | 'GITHUB' | 'UNKNOWN'
  upstream?: string
  hasChanges: boolean
  conflictSessionId?: string
  conflictCount: number
  resolvedCount: number
  deliveryState: 'NOT_STARTED' | 'COMMITTED' | 'PUSHED' | 'MERGE_REQUEST_OPENED' | 'MERGE_REQUEST_CLOSED' | 'MERGED' | 'LOCAL_COMPLETED' | 'NOT_APPLICABLE'
  deliveryFinal: boolean
  creationRequestedAt?: string
  mergeRequest?: {
    provider: 'GITLAB' | 'GITHUB' | 'UNKNOWN'; iid: number; url?: string; state?: string
    sourceBranch?: string; targetBranch?: string; headSha?: string; mergeCommitSha?: string
    openedAt?: string; mergedAt?: string; checkedAt?: string
  }
  reconciliationAvailable: boolean
  lastCheckError?: string
  lastCheckedAt?: string
}

export type LocalSyncSessionState = 'OPEN' | 'READY' | 'APPLYING' | 'VERIFYING' | 'APPLIED' | 'STALE' | 'ROLLED_BACK' | 'ROLLBACK_FAILED'
export type LocalSyncResolution = 'AUTO' | 'SOURCE' | 'TASK' | 'MANUAL'

export interface LocalSyncConflictSession {
  id: string
  taskId: string
  state: LocalSyncSessionState
  sourceRoot: string
  sourceHead: string
  taskCommit: string
  baselineCommit: string
  conflictCount: number
  resolvedCount: number
  errorMessage?: string
  backupDir?: string
  verificationEvidence?: string
  createdAt: string
  updatedAt: string
  version: number
}

export interface LocalSyncConflictFile {
  path: string
  sourcePath: string
  taskPath: string
  changeType: 'ADD' | 'MODIFY' | 'DELETE' | 'RENAME' | 'ADD_ADD' | 'MODIFY_DELETE' | 'DELETE_MODIFY' | 'RENAME_CONFLICT'
  contentType: 'TEXT' | 'LARGE_TEXT' | 'BINARY'
  resolution?: LocalSyncResolution
  resolved: boolean
  hasAiSuggestion: boolean
  baseHash: string
  sourceHash: string
  taskHash: string
  version: number
}

export interface LocalSyncConflictContent {
  path: string
  contentType: 'TEXT' | 'LARGE_TEXT' | 'BINARY'
  baseContent?: string
  sourceContent?: string
  taskContent?: string
  mergedContent?: string
  baseHash: string
  sourceHash: string
  taskHash: string
  resolution?: LocalSyncResolution
  aiSuggestion?: string
  aiEligible: boolean
  version: number
}

export interface CommitMessageSuggestion {
  subject: string
  aiGenerated: boolean
}

export interface MergeRequestDraft {
  provider: 'GITLAB' | 'GITHUB'
  sourceBranch: string
  targetBranch: string
  title: string
  description: string
  creationUrl: string
}

export interface JudgeRun {
  id: string
  role: 'REQUIREMENT' | 'RISK'
  ordinal: number
  status: 'CREATING' | 'RUNNING' | 'COMPLETED' | 'SESSION_ERROR' | 'ABORTED' | 'FAILED' | 'TIMED_OUT'
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
  stageOrdinal?: number
  stageObjective?: string
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
  startedAt?: string
}

export interface TaskSessionQuestionOption {
  label: string
  description: string
}

export interface TaskSessionQuestionPrompt {
  question: string
  header: string
  options: TaskSessionQuestionOption[]
  multiple: boolean
  custom: boolean
}

export interface TaskSessionPendingQuestion {
  id: string
  questions: TaskSessionQuestionPrompt[]
}

export interface TaskSessionActivity {
  session: TaskSessionSummary
  remoteState: string
  live: boolean
  observedAt: string
  parts: TaskSessionActivityPart[]
  pendingQuestions: TaskSessionPendingQuestion[]
  detail?: string
}

export type InteractionAction = 'REPLY' | 'ONCE' | 'SESSION' | 'REJECT'
export type InteractionState = 'PENDING' | 'RESOLVING' | 'RESOLVED' | 'REJECTED' | 'HARD_DENIED' | 'STALE'

interface InteractionBase {
  id: string
  taskId?: string
  designerSessionId?: string
  sessionId?: string
  externalRequestId: string
  state: InteractionState
  version: number
  createdAt: string
  updatedAt: string
  resolvedAction?: InteractionAction
  resolvedAt?: string
}

export interface QuestionInteraction extends InteractionBase {
  kind: 'QUESTION'
  payload: { questions: TaskSessionQuestionPrompt[] }
}

export interface PermissionInteraction extends InteractionBase {
  kind: 'PERMISSION'
  payload: { permission: string; patterns: string[]; title?: string; metadata: Record<string, unknown>; hardDenied: boolean; hardDenyReason?: string }
}

export type Interaction = QuestionInteraction | PermissionInteraction

export interface TaskQueueStatus {
  taskId: string
  state: 'QUEUED' | 'ADMITTED' | 'CANCELLED' | 'FINISHED'
  queuePosition?: number
  leaseState: 'HELD' | 'RELEASE_PENDING' | 'RELEASED' | 'NOT_REQUIRED'
  rootFingerprint?: string
}

export type RecoveryMode = 'FROM_FAILED_STAGE' | 'ALL_STAGES' | 'VERIFY_ONLY' | 'REWORK_ALL_STAGES'

export interface RecoveryDraft {
  taskId: string
  parentTaskId: string
  mode: RecoveryMode
  parentStageId?: string
  workspaceFingerprint: string
  writableSession: boolean
}

export interface SessionTodo {
  id: string
  externalTodoId: string
  content: string
  status: string
  priority?: string
  ordinal: number
  observedAt: string
}

export interface SessionCheckpoint {
  id: string
  taskId: string
  sessionId: string
  attemptId: string
  externalMessageId?: string
  contentSha256: string
  createdAt: string
}

export interface SessionForkResult {
  sessionId: string
  attemptId: string
  externalSessionId: string
  state: string
  createdAt: string
}

export interface SessionRevertResult {
  sessionId: string
  message: string
  revertedAt: string
}

export interface SessionSummaryResult {
  sessionId: string
  automatic: boolean
  remoteStateBefore: string
  remoteStateAfter: string
  summarizedAt: string
}

export interface UsageAggregate {
  inputTokens: number | null
  outputTokens: number | null
  totalTokens: number | null
  costByCurrency: Record<string, string>
  unknownUsageCount: number
}

export interface TaskInsight {
  taskId: string
  title: string
  state: TaskStatus
  durationMs: number
  retryCount: number
  usage: UsageAggregate
  quality: {
    state: 'PASS' | 'PENDING' | 'REVIEW_REQUIRED'
    deterministicPassed: boolean
    verificationCount: number
    verificationPassedCount: number
    requirementJudgePassed: boolean
    riskJudgePassed: boolean
  }
}

export interface InsightsSnapshot {
  tasks: TaskInsight[]
  usage: UsageAggregate
  generatedAt: string
}

export interface BrowserAssertion {
  type: 'EXISTS' | 'VISIBLE' | 'TEXT_CONTAINS' | 'COUNT' | 'ATTRIBUTE_EQUALS'
  selector: string
  value?: string
  expectedCount?: number
  attribute?: string
}

interface LoopVerifierFields {
  command?: string[]
  path?: string
  requireChanges?: boolean
  allowedPaths?: string[]
  forbiddenPaths?: string[]
  forbidDeletes?: boolean
  outputContains?: string
  url?: string
  httpMethod?: 'GET' | 'HEAD'
  expectedStatus?: number
  jsonPath?: string
  expectedValue?: string
  matchMode?: 'EXISTS' | 'EXACT' | 'CONTAINS'
  expectedContent?: string
  expectedSha256?: string
  sql?: string
  expectedRowCount?: number
  assertions?: BrowserAssertion[]
  criterionIds?: string[]
  processPurpose?: 'BUILD' | 'TEST' | 'SELF_CHECK'
  testTargets?: string[]
}

/**
 * The discriminator makes every verifier's admission fields mandatory while
 * keeping optional policy fields readable by the shared LoopSpec editor.
 */
export type LoopVerifierSpec =
  | (LoopVerifierFields & { type: 'PROCESS'; command: string[] })
  | (LoopVerifierFields & { type: 'FILE_EXISTS' | 'FILE_NOT_EXISTS'; path: string })
  | (LoopVerifierFields & { type: 'GIT_DIFF' })
  | (LoopVerifierFields & { type: 'HTTP_STATUS'; url: string; expectedStatus: number })
  | (LoopVerifierFields & { type: 'JSON_PATH'; url: string; jsonPath: string })
  | (LoopVerifierFields & { type: 'FILE_CONTENT'; path: string; expectedContent: string })
  | (LoopVerifierFields & { type: 'FILE_HASH'; path: string; expectedSha256: string })
  | (LoopVerifierFields & { type: 'JUNIT_XML'; path: string })
  | (LoopVerifierFields & { type: 'BROWSER'; url: string; assertions: BrowserAssertion[] })
  | (LoopVerifierFields & { type: 'DATABASE_QUERY'; path: string; sql: string })

export interface LoopSpec {
  schemaVersion: string
  projectId: string
  goal: string
  context: string
  stages: Array<{
    workPackageId?: string
    objective: string
    allowedPaths: string[]
    forbiddenPaths: string[]
    deliverables: string[]
    implementationKind?: 'JAVA_PRODUCTION' | 'JAVA_TEST_ONLY' | 'NON_JAVA'
    verifiers: LoopVerifierSpec[]
    acceptanceCriteria?: Array<{
      id: string
      description: string
      verificationMode?: 'MACHINE' | 'JUDGE' | 'BOTH'
      judgeRubric?: string
      judgeOnlyReason?: string
    }>
    verificationRuntime?: {
      startCommand: string[]
      readiness: { path: string; expectedStatus?: number; jsonPath?: string; expectedValue?: string; matchMode?: 'EXISTS' | 'EXACT' | 'CONTAINS' }
      startupTimeoutSeconds?: number
      shutdownTimeoutSeconds?: number
    }
  }>
  limits: {
    maxStageAttempts: number
    maxTaskAttempts: number
    sessionErrorLimit?: number
    stagnationLimit?: number
    maxDuration: string
    attemptTimeout: string
    verifierTimeout?: string
  }
  model?: {
    providerId?: string
    modelId?: string
    thinking?: boolean
  }
  sessionPolicy?: {
    reuseHealthySession: boolean
    createFreshOnVerifierFailure: boolean
  }
  nextAttemptPromptTemplate?: string
  budget?: {
    maxTotalTokens?: number
    maxCostAmount?: string
    currency?: string
  }
}

export interface LoopSpecAssessment {
  valid: boolean
  schemaVersion: string
  legacy: boolean
  errors: string[]
  stageAssessments: Array<{
    stageIndex: number
    criteria: Array<{
      id: string
      description: string
      verificationMode: 'MACHINE' | 'JUDGE' | 'BOTH'
      covered: boolean
      machineCovered: boolean
      judgePlanned: boolean
      overallPlanned: boolean
      judgeRubric?: string
      judgeOnlyReason?: string
      verifierIndexes: number[]
    }>
    verifiers: Array<{ index: number; type: string; category: 'BUILD' | 'BEHAVIOR' | 'SCOPE' | 'SAFETY' | 'REPORT' | 'ADVISORY'; blocking: boolean; criterionIds: string[]; reason: string }>
  }>
}

export type AutomationTrigger =
  | { type: 'MANUAL' }
  | { type: 'CRON'; expression: string; timezone: string }
  | { type: 'GIT_HEAD_CHANGED'; branch?: string }
  | { type: 'WEBHOOK' }

export interface LoopSpecTemplateVersion {
  id: string
  templateId: string
  versionNumber: number
  spec: LoopSpec
  specSha256: string
  immutable: boolean
  autoStartApproved: boolean
  createdAt: string
}

export interface LoopSpecTemplate {
  id: string
  name: string
  description: string
  state: 'ACTIVE' | 'ARCHIVED'
  versions: LoopSpecTemplateVersion[]
  updatedAt: string
  version: number
}

interface AutomationRuleBase {
  id: string
  name: string
  projectId: string
  templateVersionId: string
  state: 'DISABLED' | 'ENABLED'
  approvalMode: 'REVIEW_REQUIRED' | 'AUTO_START'
  updatedAt: string
  version: number
}

/** REST wire shape shared with FeatureContracts.AutomationRuleDto. */
export type AutomationRule = AutomationRuleBase & (
  | { triggerType: 'MANUAL'; triggerConfig: Record<string, never> }
  | { triggerType: 'CRON'; triggerConfig: { expression: string; timezone: string } }
  | { triggerType: 'GIT_HEAD_CHANGED'; triggerConfig: { branch?: string } }
  | { triggerType: 'WEBHOOK'; triggerConfig: Record<string, never> }
)

export type CreateAutomationRuleInput = Pick<AutomationRuleBase, 'name' | 'projectId' | 'templateVersionId'> & (
  | { triggerType: 'MANUAL'; triggerConfig: Record<string, never> }
  | { triggerType: 'CRON'; triggerConfig: { expression: string; timezone: string } }
  | { triggerType: 'GIT_HEAD_CHANGED'; triggerConfig: { branch?: string } }
  | { triggerType: 'WEBHOOK'; triggerConfig: Record<string, never> }
)

export interface AutomationRuleMutation {
  rule: AutomationRule
  /** Present only on the successful WEBHOOK create response; never returned by list APIs. */
  webhookToken?: string
  webhookPath?: string
}

export interface AutomationRun {
  id: string
  ruleId: string
  triggerType: AutomationTrigger['type']
  state: 'DETECTED' | 'REVIEW_REQUIRED' | 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED'
  draftId?: string
  taskId?: string
  evidence: Record<string, unknown>
  queueState?: string
  error?: string
  detectedAt: string
  startedAt?: string
  endedAt?: string
}

export interface AutomationRunFeed {
  runs: AutomationRun[]
  serverTime: string
}

export interface AutomationImportPreview {
  previewId: string
  templates: LoopSpecTemplate[]
  rules: AutomationRule[]
  expiresAt?: string
}

export interface AutomationImportResult {
  templates: LoopSpecTemplate[]
  /** WEBHOOK tokens, when any, appear only in these one-time mutation results. */
  rules: AutomationRuleMutation[]
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
  actor: 'USER' | 'DECOMPOSER' | 'DESIGNER' | 'COMPILER' | 'VALIDATOR' | 'SYSTEM'
  content: string
  deliveryState?: 'PERSISTED' | 'PENDING_HANDOFF' | 'COMPILED' | 'DESIGN_INCOMPLETE' | 'PASS' | 'RETRYABLE_ERROR' | 'TERMINAL_ERROR' | 'SESSION_ERROR'
  requirementRevision?: number
  workPackageId?: string
  createdAt: string
}

export type DesignerSessionState = 'PENDING_HANDOFF' | 'RUNNING' | 'WAITING_INPUT' | 'COMPLETED' | 'SESSION_ERROR'
export type DesignWorkflowPhase = 'DECOMPOSING' | 'VALIDATING_DECOMPOSITION' | 'DESIGNING' | 'COMPILING' | 'VALIDATING' | 'REDESIGNING' | 'AGGREGATING' | 'COMPLETED' | 'FAILED'
export type DesignerActor = DesignerMessage['actor']
export type StructuredModelStep = 'PLANNING' | 'GENERATING_JSON' | 'REPAIRING_JSON' | 'FINAL_JSON'

export interface DesignRequirementRevisionStatus {
  revision: number
  state: string
  modelCallsUsed: number
  maxModelCalls: number
  sourceDraftVersion: number
}

export interface TaskDecompositionStatus {
  id: string
  state: string
  resultType?: 'DIRECT_DESIGN' | 'DECOMPOSED' | 'NEEDS_INPUT' | 'MULTI_TASK_REQUIRED'
  repairCount: number
  transportRetryCount: number
  lastErrorCode?: string
  lastErrorDetail?: string
  workflowStep: StructuredModelStep
  planningRepairCount: number
}

export interface DesignWorkPackageStatus {
  id: string
  ordinal: number
  title: string
  objective: string
  state: string
  dependencies: string[]
  redesignCount: number
  compilerRepairCount: number
  compilerPlanningRepairCount: number
  compilerSummary?: string
  handoffSummary?: string
  lastErrorCode?: string
  lastErrorDetail?: string
}

export interface DesignerSession {
  id: string
  projectId: string
  projectName?: string
  state: DesignerSessionState
  workflowPhase: DesignWorkflowPhase
  activeActor: DesignerActor
  accessMode: 'READ_ONLY'
  readOnly: boolean
  permissionSummary?: string
  updatedAt?: string
  draft?: LoopDraft
  messages: DesignerMessage[]
  pendingQuestions?: TaskSessionPendingQuestion[]
  compiler?: {
    id: string
    state: 'PENDING_HANDOFF' | 'RUNNING' | 'DESIGN_INCOMPLETE' | 'COMPLETED' | 'SESSION_ERROR'
    externalSessionState?: string
    repairCount: number
    designRevision: number
    lastErrorCode?: string
    lastErrorDetail?: string
    workPackageId?: string
    workflowStep: StructuredModelStep
    planningRepairCount: number
  }
  requirement?: DesignRequirementRevisionStatus
  decomposition?: TaskDecompositionStatus
  workPackages?: DesignWorkPackageStatus[]
  requirementRevision?: number
  activeWorkPackageId?: string
}

export interface TaskDesignHistory {
  taskId: string
  taskTitle: string
  projectName: string
  draft: LoopDraft
  designerSession?: {
    id: string
    state: DesignerSessionState
    accessMode: 'READ_ONLY'
    createdAt: string
    updatedAt: string
    messages: DesignerMessage[]
  }
  requirement?: {
    revision: number
    state: string
    requirementText: string
    modelCallsUsed: number
    maxModelCalls: number
  }
  decomposition?: {
    state: string
    resultType?: string
    planJson: string
  }
  workPackages?: Array<{
    id: string
    ordinal: number
    title: string
    objective: string
    state: string
    compilerSummary?: string
    handoffSummary?: string
  }>
}

export interface DesignerAppendResult {
  sessionId: string
  state: DesignerSessionState
  persistedMessages: DesignerMessage[]
  notice: string
}

export interface DesignerStreamEvent {
  sequence: number
  sessionId: string
  type: 'SNAPSHOT' | 'STATUS' | 'PARTIAL' | 'COMPLETED' | 'ERROR'
  state: DesignerSessionState
  workflowPhase: DesignWorkflowPhase
  activeActor: DesignerActor
  remoteState?: string
  runtimeConnected: boolean
  content: string
  detail: string
  at: string
  requirementRevision?: number
  activeWorkPackageId?: string
  modelCallsUsed: number
  maxModelCalls: number
  structuredStep?: StructuredModelStep
}
