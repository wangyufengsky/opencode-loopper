import type { DesignerSessionState, DesignWorkPackageState, LoopDraftStatus, StageStatus, TaskPackageRunState, TaskStatus, WorkPackageAggregateStatus } from '@/types/states'
export type { DesignerSessionState, DesignWorkPackageState, LoopDraftStatus, SessionStatus, StageStatus, TaskPackageRunState, TaskStatus, WorkPackageAggregateStatus } from '@/types/states'

export type ErrorLayer = 'FIELD' | 'VERIFICATION' | 'SESSION' | 'TASK'

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
  openDesignerSessionCount: number
  stackProfileState?: 'UNANALYZED' | 'READY' | 'PARTIAL' | 'FAILED'
  stackTechnologyFamilies?: string[]
  stackComponentCount?: number
  stackAnalyzedAt?: string
}

export interface ProjectStackComponent {
  key: string
  relativeRoot: string
  technologyFamilies: string[]
  technologies: string[]
  buildTools: string[]
  testFrameworks: string[]
  manifestSources: string[]
}

export interface ProjectStackProfile {
  id?: string
  projectId: string
  state: Project['stackProfileState']
  manifestFingerprint?: string
  technologyFamilies: string[]
  technologies: string[]
  filesScanned: number
  errorCode?: string
  errorDetail?: string
  analyzedAt?: string
  components: ProjectStackComponent[]
}

export interface DirectorySelection {
  selected: boolean
  path?: string
  name?: string
}

export interface ProjectConventionDraft {
  id: string
  projectId: string
  state: 'RUNNING' | 'STOPPING' | 'CANCELLED' | 'READY' | 'APPLYING' | 'APPLIED' | 'FAILED'
  operation: 'CREATE' | 'UPDATE'
  readOnlyGeneration: boolean
  content?: string
  normalizationNotice?: string
  error?: string
  updatedAt: string
  stackProfileId?: string
  stackFingerprint?: string
}

export interface ProjectConventionActivity {
  actor: 'PROJECT_CONVENTION'
  remoteState: string
  connected: boolean
  observedAt: string
  parts: TaskSessionActivityPart[]
  detail?: string
  usage: ModelTokenUsage
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
  generation?: string
  internalMcp?: {
    status: 'INACTIVE' | 'CONNECTING' | 'CONNECTED' | 'UNAVAILABLE' | 'UNKNOWN'
    configured: boolean
    detail?: string
  }
  capabilities?: {
    agentDiscovery: 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN'
    agents: Array<{ name: string; mode?: string; description?: string }>
    nativePlanAgent: boolean
    structuredOutputTransport: 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN'
    selectedModelStructuredOutput: 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN'
    defaultResponseMode: 'JSON_SCHEMA' | 'TEXT_MARKER'
    extensionPolicy: 'TRUSTED_ALLOWED'
    checkedAt: string
    detail?: string
  }
}

export interface AppSettings {
  runtime: {
    serverPort: number; openBrowser: boolean; allowedRoot: string
    monitorDelaySeconds: number; designerMonitorDelayMillis: number; abortCleanupAttempts: number
  }
  openCode: {
    cliPath: string; mode: 'managed' | 'auto' | 'http'; baseUrl: string; provider: string; model: string
    connectTimeoutSeconds: number; requestTimeoutSeconds: number; startupTimeoutSeconds: number
  }
  limits: {
    maxStageAttempts: number; maxTaskAttempts: number; sessionErrorLimit: number
    maxDurationMinutes: number; attemptTimeoutMinutes: number; verifierTimeoutMinutes: number; designerTimeoutMinutes: number
  }
  retryWait: {
    rateLimitBaseSeconds: number; rateLimitMaxSeconds: number
    sessionBaseSeconds: number; sessionMaxSeconds: number
    verificationBaseSeconds: number; verificationMaxSeconds: number
  }
  publication: {
    httpWebHosts: string[]; gitlabHost: string; gitlabApiBaseUrl: string
    connectTimeoutSeconds: number; requestTimeoutSeconds: number
  }
  startupConfigPath?: string
  appliedLiveFields: string[]
  restartRequiredFields: string[]
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
  executionCycleId?: string
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
  stageKind?: string
  executionStrategy?: string
  rolePackId?: string
  rolePackVersion?: string
  testPolicy?: 'REQUIRED' | 'OPTIONAL' | 'NOT_APPLICABLE'
  technologies?: string[]
  status: StageStatus
  attempts: Attempt[]
}

export interface TaskWorkPackageProgress {
  id: string
  ordinal: number
  status: WorkPackageAggregateStatus
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
  retryCause?: 'RATE_LIMIT' | 'SESSION' | 'VERIFICATION'
  retryOrdinal?: number
  retryScheduledAt?: string
  retryDueAt?: string
  retryDelaySeconds?: number
  waitingReasonCode?: string
  loopRetryAvailable?: boolean
  cancellationAvailable?: boolean
  hasDesignHistory?: boolean
  archived?: boolean
  version?: number
  executionMode?: 'LEGACY_AGGREGATE' | 'ROLLING_PACKAGES'
  workspacePolicy?: 'RELEASE_BETWEEN_PACKAGES' | 'PINNED_DIRECT'
  currentPackage?: RollingPackageRun
  plannedPackageCount?: number
  frozenPackageCount?: number
  packageCapabilities?: RollingPackageCapabilities
  executionResult?: 'SUCCEEDED' | 'FAILED' | 'INTERRUPTED' | 'AUDIT_COMPLETED'
  executionCycleOrdinal?: number
  checkpointState?: 'CAPTURING' | 'READY' | 'RESTORING' | 'RESTORED' | 'BLOCKED'
  parentTaskId?: string
  successorTaskId?: string
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

export interface RollingPackageCapabilities {
  canDiscuss: boolean
  canApproveDesign: boolean
  canStartPackage: boolean
  canRetryPackage: boolean
  canRedesignPackage: boolean
  canResumeDesign: boolean
  canReplanRemaining: boolean
  canAddCorrectionPackage: boolean
}

export interface RollingPackageRun {
  id: string
  packageKey: string
  ordinal: number
  title: string
  state: TaskPackageRunState
  version: number
  discussionRevision: number
  designRevision: number
  acceptedDesignRevision?: number
  waitingReasonCode?: string
  correctionOfPackageRunId?: string
  dependencies: string[]
}

export interface RollingPackageFact {
  id: string
  packageRunId: string
  checkpointId: string
  successfulAttemptId: string
  provenJson: string
  acceptedContractJson: string
  navigationSummary: string
  createdAt: string
}

export interface RollingPackageWorkbench {
  taskId: string
  title: string
  taskState: TaskStatus
  taskVersion: number
  executionMode: 'ROLLING_PACKAGES'
  workspacePolicy: 'RELEASE_BETWEEN_PACKAGES' | 'PINNED_DIRECT'
  planRevisionId: string
  planRevision: number
  plannedPackageCount: number
  frozenPackageCount: number
  currentPackageRunId?: string
  packageCapabilities: RollingPackageCapabilities
  packages: RollingPackageRun[]
}

export interface RollingPackageDetail {
  packageRun: RollingPackageRun
  objective: string
  deliverablesJson: string
  acceptanceIntentJson: string
  compilerSummary?: string
  handoffSummary?: string
  designMarkdown?: string
  fact?: RollingPackageFact
}

export interface RollingPlanPackage {
  packageKey: string
  title: string
  objective: string
  sourcePackageRunId?: string
  sourcePackageRunIds?: string[]
  correctionOfPackageRunId?: string
  dependencies: string[]
  requirementRefs: string[]
}

export interface RollingPlanProposal {
  id: string
  revision: number
  state: 'GENERATING' | 'PROPOSED' | 'ACTIVE' | 'FAILED' | 'SUPERSEDED'
  version: number
  planJson: string
  impactJson: string
  origin: 'INITIAL' | 'USER' | 'AI' | 'CORRECTION'
  externalSessionState?: string
  lastErrorCode?: string
  lastErrorDetail?: string
  createdAt: string
  updatedAt: string
  approvedAt?: string
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

export type GitDiffScopeDecisionAction = 'ALLOW' | 'REJECT'

export interface GitDiffScopeApprovalFile {
  path: string
  changeType: 'MODIFIED' | 'DELETED' | 'RENAMED_FROM' | 'RENAMED_TO'
  patchSha256: string
}

export interface GitDiffScopeApproval {
  requestId: string
  taskId: string
  stageId: string
  attemptId: string
  taskVersion: number
  files: GitDiffScopeApprovalFile[]
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
  scope?: string
  discussionRevision?: number
  questions: TaskSessionQuestionPrompt[]
}

export interface DesignerAnsweredQuestionPrompt extends TaskSessionQuestionPrompt {
  answers: string[]
}

export interface DesignerAnsweredQuestion {
  id: string
  scope?: string
  discussionRevision?: number
  designMessageId?: string
  answeredAt?: string
  questions: DesignerAnsweredQuestionPrompt[]
}

export interface TaskSessionTodo {
  id: string
  content: string
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'UNKNOWN'
  priority?: 'HIGH' | 'MEDIUM' | 'LOW'
  ordinal: number
}

export interface ModelTokenUsage {
  totalTokens: number | null
  unknownUsageCount: number
  observedAt: string
}

export interface TaskSessionActivity {
  session: TaskSessionSummary
  remoteState: string
  live: boolean
  observedAt: string
  parts: TaskSessionActivityPart[]
  pendingQuestions: TaskSessionPendingQuestion[]
  detail?: string
  todoCapability: 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN'
  todos: TaskSessionTodo[]
  todoTruncated: boolean
  todoDetail?: string
  usage: ModelTokenUsage
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
  holderTaskId?: string
  holderTaskTitle?: string
  holderTaskState?: TaskStatus
  holderArchived?: boolean
  releaseReason?: string
  reconcileAvailable: boolean
}

export type RecoveryMode = 'FROM_FAILED_STAGE' | 'ALL_STAGES' | 'VERIFY_ONLY' | 'REWORK_ALL_STAGES' | 'INHERIT_CHANGES'

export interface RecoveryDraft {
  taskId: string
  parentTaskId: string
  mode: RecoveryMode
  parentStageId?: string
  workspaceFingerprint: string
  writableSession: boolean
}

export type TaskDecisionAction =
  | 'CONTINUE_CURRENT_TASK'
  | 'DERIVE_INHERIT_CHANGES'
  | 'DERIVE_REWORK_ALL'
  | 'READ_ONLY_AUDIT'
  | 'PUBLISH'
  | 'ACCEPT_RESULT'
  | 'CANCEL'

export interface TaskDecision {
  taskId: string
  taskState: TaskStatus
  taskVersion: number
  cycle?: {
    id: string
    ordinal: number
    kind: 'INITIAL' | 'CONTINUE_FAILED' | 'CONTINUE_SUCCESS' | 'READ_ONLY_AUDIT'
    result: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'INTERRUPTED' | 'AUDIT_COMPLETED'
    startStageId?: string
    startStageOrdinal?: number
    failureCode?: string
    failureMessage?: string
    authorizedAt: string
    startedAt: string
    endedAt?: string
    version: number
  }
  checkpoint?: {
    id: string
    state: 'CAPTURING' | 'READY' | 'RESTORING' | 'RESTORED' | 'BLOCKED'
    snapshotId?: string
    checkpointTree?: string
    changedFileCount: number
    blockerCode?: string
    blockerMessage?: string
    updatedAt: string
    version: number
  }
  availableActions: TaskDecisionAction[]
  stages: Array<{ id: string; ordinal: number; objective: string; state: string }>
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
    humanApproved?: boolean
  }
}

export interface JudgeApproval {
  available: boolean
  approved: boolean
  taskVersion: number
  cycleId: string
  cycleVersion: number
  reviewBatchId: string
  approvedAt?: string
}

export interface InsightQuery {
  cursor?: string
  projectId?: string
  state?: string
  quality?: string
  archive?: string
  query?: string
}

export interface McpServerInfo { id: string; name: string; status: string; type: string }
export interface McpToolCatalog { tools: Array<{ name: string; description: string }>; complete: boolean; detail?: string }

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
export interface DocumentAssertion { type: 'HEADING_EXISTS' | 'TEXT_EXISTS' | 'TABLE_COUNT' | 'LOCAL_LINKS_VALID'; value?: string; expectedCount?: number; headingLevel?: number }
export interface TabularAssertion { type: 'SHEET_EXISTS' | 'ROW_COUNT' | 'COLUMN_COUNT' | 'HEADER_EQUALS' | 'CELL_EQUALS' | 'EQUIVALENT_TO'; sheet?: string; row?: number; column?: number; expectedValue?: string; expectedCount?: number; sourcePath?: string }

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
  documentAssertions?: DocumentAssertion[]
  tabularAssertions?: TabularAssertion[]
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
  | (LoopVerifierFields & { type: 'DOCUMENT_STRUCTURE'; path: string; documentAssertions: DocumentAssertion[] })
  | (LoopVerifierFields & { type: 'TABULAR_DATA'; path: string; tabularAssertions: TabularAssertion[] })

export interface LoopSpec {
  schemaVersion: string
  projectId: string
  goal: string
  context: string
  stages: Array<{
    workPackageId?: string
    stageKind?: 'SOFTWARE_IMPLEMENTATION' | 'DOCUMENT_MATERIALIZATION' | 'TABULAR_CONVERSION' | 'READ_ONLY_ANALYSIS' | 'LOCAL_MAINTENANCE' | 'LEGACY_SOFTWARE'
    executionStrategy?: 'OPEN_CODE_IMPLEMENTATION' | 'SERVER_DOCUMENT_MATERIALIZATION' | 'SERVER_TABULAR_CONVERSION' | 'READ_ONLY_REPORT'
    artifactPlanId?: string
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
  status: LoopDraftStatus
  updatedAt: string
  spec: LoopSpec
}

export interface DesignerMessage {
  id: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  actor: 'USER' | 'ROUTER' | 'DECOMPOSER' | 'DESIGNER' | 'COMPILER' | 'REVIEWER' | 'VALIDATOR' | 'SYSTEM'
  content: string
  deliveryState?: 'PERSISTED' | 'PENDING_HANDOFF' | 'SERVER_REQUIREMENT_SNAPSHOT' | 'STORY_BINDING_FAILED' | 'CHAT_QUESTION' | 'COMPILED' | 'DESIGN_INCOMPLETE' | 'PASS' | 'NORMALIZED' | 'RETRYABLE_ERROR' | 'TERMINAL_ERROR' | 'SESSION_ERROR'
  requirementRevision?: number
  workPackageId?: string
  attachments?: DesignerAttachment[]
  createdAt: string
}

export interface DesignerAttachment {
  id: string
  filename: string
  mediaType: string
  sizeBytes: number
  sha256: string
  scopeKey: string
  workPackageId?: string
  extractorId: string
  previewKind: string
  state: string
  supersededByAttachmentId?: string
}

export type DesignWorkflowPhase = 'ROUTING' | 'DISCUSSING_REQUIREMENT' | 'DECOMPOSING' | 'VALIDATING_DECOMPOSITION' | 'DESIGNING' | 'COMPILING' | 'VALIDATING' | 'REDESIGNING' | 'QUESTIONING_PACKAGE' | 'REVIEWING_PACKAGE' | 'AGGREGATING' | 'FINAL_REVIEW' | 'GENERATING_REPORT' | 'VALIDATING_REPORT' | 'REPORT_READY' | 'COMPLETED' | 'FAILED'
export type DesignerActor = DesignerMessage['actor']
export type StructuredModelStep = 'PLANNING' | 'SERVER_COMPILING' | 'GENERATING_JSON' | 'REPAIRING_JSON' | 'FINAL_JSON'

export interface DesignerSessionSummary {
  id: string
  projectId: string
  state: DesignerSessionState
  workflowPhase: DesignWorkflowPhase
  updatedAt: string
  draftId: string
  draftStatus: string
  goal: string
  requirementRevision?: number
  activeWorkPackageId?: string
}

export interface DesignerHistoryItem extends DesignerSessionSummary {
  projectName: string
  createdAt: string
  archived: boolean
  archivedAt?: string
  taskId?: string
  taskState?: TaskStatus
  resumable: boolean
  stopRetryAvailable: boolean
}

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
  formatRepairCount?: number
  semanticRepairCount?: number
  serverCompiled?: boolean
  candidateSessions?: number
  candidateSubmissions?: number
}

export interface DesignWorkPackageStatus {
  id: string
  ordinal: number
  title: string
  objective: string
  state: DesignWorkPackageState
  dependencies: string[]
  redesignCount: number
  compilerRepairCount: number
  compilerPlanningRepairCount: number
  compilerFormatRepairCount?: number
  compilerSemanticRepairCount?: number
  compilerServerCompiled?: boolean
  compilerSummary?: string
  handoffSummary?: string
  lastErrorCode?: string
  lastErrorDetail?: string
  designRevision: number
  approvedDesignRevision?: number
  discussionRoundCount: number
  invalidatedByPackageId?: string
  approvedAt?: string
  rolePackId?: string
  rolePackVersion?: string
  executionStrategy?: DesignerTaskProfile['executionStrategy']
  testPolicy?: DesignerTaskProfile['testPolicy']
  technologies?: string[]
  acceptancePlanning?: DesignerAcceptancePlanningStatus
  candidateRunState?: 'OPEN' | 'ACCEPTED' | 'WAITING_INPUT' | 'FALLBACK_REQUIRED' | 'CLOSED'
  candidateSessions?: number
  candidateSubmissions?: number
  compilationSource?: 'MCP_ACCEPTED' | 'MARKDOWN_FALLBACK'
  fallbackReason?: string
  serverCompiled?: boolean
}

export interface DesignerAcceptancePlanningStatus {
  state: 'EXTRACTED' | 'BOUND' | 'COMPILED' | 'FAILED'
  bindingSource: 'UNDECIDED' | 'SERVER_STAGE_HINTS' | 'AI_DISAMBIGUATION_V6' | 'LEGACY_UNKNOWN'
  routingReasons: string[]
  factCount: number
  scenarioCount: number
  automatedCount: number
  bothCount: number
  judgeCount: number
  unresolvedCount: number
  mutationObligationCount: number
  resolvedMutationObligationCount: number
  unresolvedMutationObligationCount: number
  pathConservation: 'NOT_EVALUATED' | 'CONSERVED' | 'BLOCKED'
  mutationBindingReasons: string[]
  scenarios: Array<{
    title: string
    coverage: 'AUTOMATED' | 'BOTH' | 'JUDGE' | 'UNRESOLVED'
    capabilities: string[]
  }>
  issues: string[]
}

export interface DesignerCandidateStatus {
  syncState: 'NONE' | 'SYNCING' | 'SYNCED' | 'FAILED'
  discussionRevision: number
  workPackageId?: string
  spec?: LoopSpec
  detail?: string
}

export interface DesignerAutoMode {
  enabled: boolean
  state: 'DISABLED' | 'ACTIVE' | 'BLOCKED' | 'COMPLETED'
  version: number
  lastAction?: string
  errorCode?: string
  errorDetail?: string
  taskId?: string
  updatedAt?: string
}

export type TaskIntent = 'SOFTWARE_CHANGE' | 'DOCUMENT_AUTHORING' | 'DATA_CONVERSION' | 'READ_ONLY_REVIEW' | 'RESEARCH' | 'CONFIGURATION' | 'LOCAL_MAINTENANCE' | 'LEGACY_SOFTWARE'
export type ArtifactKind = 'SOURCE_CODE' | 'PYTHON_SCRIPT' | 'MARKDOWN' | 'DOCX' | 'XLSX' | 'CSV' | 'TSV' | 'CONFIGURATION' | 'ANALYSIS_REPORT' | 'OTHER'
export interface DesignerTaskProfile {
  id?: string
  state: string
  decisionState: 'ROUTING' | 'NEEDS_CONFIRMATION' | 'CONFIRMED' | 'FROZEN'
  confirmationReady: boolean
  intent: TaskIntent
  workflowTemplate: 'DIRECT_SOFTWARE_DESIGN' | 'FULL_PACKAGE_DESIGN' | 'DIRECT_ARTIFACT' | 'PACKAGED_ARTIFACT' | 'READ_ONLY_REPORT' | 'LOCAL_MAINTENANCE'
  mutationMode: 'READ_ONLY' | 'WRITE_FILES' | 'WRITE_CODE' | 'SAFE_LOCAL_MAINTENANCE'
  artifactKinds: ArtifactKind[]
  technologies: string[]
  testPolicy: 'REQUIRED' | 'OPTIONAL' | 'NOT_APPLICABLE'
  executionStrategy: 'OPEN_CODE_IMPLEMENTATION' | 'SERVER_DOCUMENT_MATERIALIZATION' | 'SERVER_TABULAR_CONVERSION' | 'READ_ONLY_REPORT'
  rolePackId: string
  rolePackVersion: string
  confidence: number
  confidenceAvailable?: boolean
  evidence: string[]
  resolutionSource: string
  decisionRequired: boolean
  largeTaskMode: boolean
  previousConfirmedChoice?: {
    intent: TaskIntent
    primaryArtifactKind: ArtifactKind
    workflowTemplate: DesignerTaskProfile['workflowTemplate']
    mutationMode: DesignerTaskProfile['mutationMode']
    largeTaskMode: boolean
    resolutionSource: string
    projectStackProfileId?: string
    stackFingerprint?: string
    componentKeys?: string[]
  }
  version: number
  projectStackProfileId?: string
  stackFingerprint?: string
  componentKeys?: string[]
  candidateComponents?: ProjectStackComponent[]
  stackProfileState?: Project['stackProfileState']
  componentSelectionRequired?: boolean
}

export interface DesignerTaskProfileUpdatePreview {
  selectionChanged: boolean
  updateRequired: boolean
  sessionRestartRequired: boolean
  targetWorkflowTemplate: DesignerTaskProfile['workflowTemplate']
}

export interface TaskProfileRouterRun {
  id: string
  state: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SUPERSEDED'
  externalState?: string
  errorCode?: string
  errorDetail?: string
  createdAt: string
  updatedAt: string
  deadlineAt?: string
  retryAvailable: boolean
}

export interface DesignerActivity {
  actor: DesignerActor
  remoteState: string
  connected: boolean
  observedAt: string
  structuredStep?: StructuredModelStep
  parts: TaskSessionActivityPart[]
  detail?: string
  usage: ModelTokenUsage
}

export interface DesignerStopResult {
  stopStatus: 'STOPPING' | 'CANCELLED'
  archived: boolean
  stoppedSessions: number
  failedSessions: number
  pendingFinalizations: number
}
export interface AnalysisReportSummary { id: string; state: string; title: string; contentSha256: string; stale: boolean; updatedAt: string }
export interface AnalysisReportFinding { severity: string; title: string; detail: string; path: string; line: number; recommendation: string }
export interface AnalysisReport {
  id: string; state: string; title: string; markdown: string; contentSha256: string; sourceSnapshotSha256: string
  evidence: Array<{ path: string; line: number; sha256: string; stale: boolean }>; stale: boolean
  errorCode?: string; errorDetail?: string; createdAt: string; updatedAt: string
  reviewerContractVersion?: string; findings: AnalysisReportFinding[]
}

export interface StoryBindingConfiguration {
  enabled: boolean
  systemCode?: string
  storyCode?: string
}

export interface StoryBindingCapability {
  available: boolean
  state: 'AVAILABLE' | 'UNAVAILABLE' | 'UNKNOWN'
  reason: string
  checkedAt: string
}

export interface DesignerSession {
  id: string
  taskId?: string
  projectId: string
  projectName?: string
  archived?: boolean
  storyBinding?: StoryBindingConfiguration
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
  answeredQuestions?: DesignerAnsweredQuestion[]
  questionInteraction: {
    mode: 'NONE' | 'NATIVE_TOOL' | 'CHAT_FALLBACK'
    awaitingAnswer: boolean
  }
  requirementSnapshot?: {
    discussionRevision: number
    source: 'SERVER_ASSEMBLED' | 'AI_ASSEMBLED'
    markdown: string
    updatedAt: string
  }
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
    formatRepairCount?: number
    semanticRepairCount?: number
    serverCompiled?: boolean
  }
  requirement?: DesignRequirementRevisionStatus
  decomposition?: TaskDecompositionStatus
  workPackages?: DesignWorkPackageStatus[]
  requirementRevision?: number
  activeWorkPackageId?: string
  discussionScope: string
  discussionRevision: number
  candidate?: DesignerCandidateStatus
  finalConfirmationEligible: boolean
  autoMode: DesignerAutoMode
  taskProfile: DesignerTaskProfile
  routerRun?: TaskProfileRouterRun
  availableProfileOverrides: TaskIntent[]
  availableArtifactOverrides: ArtifactKind[]
  reports: AnalysisReportSummary[]
}

export interface TaskDesignHistory {
  taskId: string
  taskTitle: string
  projectName: string
  designSourceTaskId?: string
  inheritedConversation?: boolean
  frozenAttachments?: Array<{
    id: string
    filename: string
    mediaType: string
    sizeBytes: number
    sha256: string
    scopeKey: string
    workPackageId?: string
    extractorId?: string
    sourceTaskId?: string
    frozenAt: string
  }>
  draft: LoopDraft
  designerSession?: {
    id: string
    state: DesignerSessionState
    accessMode: 'READ_ONLY'
    createdAt: string
    updatedAt: string
    messages: DesignerMessage[]
    answeredQuestions?: DesignerAnsweredQuestion[]
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
    state: DesignWorkPackageState
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
  type: 'SNAPSHOT' | 'STATUS' | 'PARTIAL' | 'COMPLETED' | 'ERROR' | 'AUTO_MODE' | 'STORY_BINDING_FAILED'
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

export interface StoryAccountingCall {
  id: string
  operation: 'start' | 'continue' | 'complete'
  state: 'PREPARED' | 'CANCELLING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'CANCELLED'
  systemCode: string
  storyCode: string
  role: string
  designerSessionId?: string | null
  taskId?: string | null
  startedAt: string
  finishedAt?: string | null
  detail?: string | null
  refreshError?: string | null
  parts: Array<{ id: string; type: string; label: string; content: string; status?: string | null; startedAt?: string | null }>
}
