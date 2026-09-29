export type WorkflowDataKind = 'TEXT' | 'JSON' | 'DOCUMENT' | 'CODE' | 'DECISION' | 'PLAN' | 'CONTROL'
export interface WorkflowInput { name: string; source: 'REQUIREMENT' | 'NODE'; sourceId: string; output: string | null; kind: WorkflowDataKind; required: boolean }
export interface WorkflowOutput { name: string; title: string; kind: WorkflowDataKind; required: boolean }
export interface WorkflowNode {
  id: string; title: string; kind: 'WORK' | 'HUMAN' | 'SYSTEM'; moduleId: string | null; moduleVersion: number
  roleId: string | null; roleRevisionId?: string | null; task: string; inputs: WorkflowInput[]; outputs: WorkflowOutput[]
  outcomes: string[]; completion: { kind: 'DELIVERABLES' | 'OUTCOME' | 'VERIFIED' | 'HUMAN'; criterion: string; expectedOutcome: string | null }
  maxRetries: number; pauseAfter: boolean; parameters: Record<string, string>
}
export interface WorkflowEdge { id: string; from: string; to: string; outcome: string | null }
export interface WorkflowGraph { schemaVersion: number; nodes: WorkflowNode[]; edges: WorkflowEdge[]; inputs: WorkflowOutput[] }
export interface WorkflowPoint { x: number; y: number }
export interface WorkflowLayout { positions: Record<string, WorkflowPoint>; x: number; y: number; zoom: number }
export interface WorkflowDiagnostic { severity: string; path: string; code: string; message: string }
export interface WorkflowTemplateSummary { id: string; title: string; description: string; builtin: boolean; headRevision: number; version: number; createdAt: string; updatedAt: string }
export interface WorkflowTemplate extends Omit<WorkflowTemplateSummary, 'createdAt' | 'updatedAt'> { archived: boolean; revision: number; layoutVersion: number; graph: WorkflowGraph; layout: WorkflowLayout; diagnostics: WorkflowDiagnostic[]; sourceTemplateId: string | null; sourceRevision: number | null }
export interface WorkflowReceipt { id: string; revision: number; version: number; layoutVersion: number; state: string }
export type WorkflowTemplateMode = 'CURRENT' | 'INITIAL'
export interface WorkflowTemplateSelection { expectedRevision: number; mode: WorkflowTemplateMode; graph: WorkflowGraph; layout: WorkflowLayout }
export interface WorkflowTemplatePreview { mode: WorkflowTemplateMode; sourceRevision: number; initialAvailable: boolean; graph: WorkflowGraph; layout: WorkflowLayout; fixedPlanningNodes: string[]; sha256: string; diagnostics: WorkflowDiagnostic[] }
export interface WorkflowSaveTemplate { requestKey: string; title: string; description: string; selection: WorkflowTemplateSelection; previewSha256: string }
export interface WorkflowPage<T> { items: T[]; nextCursor?: string | null }
export type WorkflowRequirementState = 'PLANNING' | 'PENDING_START' | 'RUNNING' | 'PAUSED' | 'STALLED' | 'STOPPING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export type WorkflowFinishTarget = 'COMPLETED' | 'FAILED' | 'CANCELLED'
export interface WorkflowFinishRequest { requestKey: string; expectedVersion: number; target: WorkflowFinishTarget; reason: string }
export interface WorkflowFinish {
  requirementId: string; state: WorkflowRequirementState; version: number
  intent: { requirementId: string; targetState: WorkflowFinishTarget; reason: string; planRevision: number; requestedVersion: number; requestedAt: string; finalizedAt: string | null } | null
  pending: { attempts: number; resources: number }
}
export interface WorkflowRequirementSummary { id: string; projectId: string; title: string; state: WorkflowRequirementState; headRevision: number; version: number; createdAt: string; updatedAt: string }
export interface WorkflowRequirement extends Omit<WorkflowRequirementSummary, 'createdAt' | 'updatedAt'> { objective: string; revision: number; layoutVersion: number; sourceTemplateId: string; sourceRevision: number; graph: WorkflowGraph; layout: WorkflowLayout; diagnostics: WorkflowDiagnostic[] }
export type WorkflowRunMode = 'SINGLE' | 'UNTIL' | 'CONTINUOUS'
export interface WorkflowModel { providerId: string; modelId: string; thinking: boolean | null }
export interface WorkflowValue { kind: WorkflowDataKind; content: unknown }
export interface WorkflowDelivery { summary: string; outcome: string | null; outputs: Record<string, WorkflowValue> }
export interface WorkflowControl { id: string; revision: number; version: number; configured: boolean; controlVersion: number; mode: WorkflowRunMode | null; targetKey: string | null; state: 'ACTIVE' | 'PAUSED' | 'WAITING' | 'STALLED' | 'DONE'; reasonCode: string | null; model: WorkflowModel | null; checkpoints: Array<{ attemptId: string; requirementId: string; nodeKey: string; createdAt: string; acknowledgedAt: string | null }> }
export interface WorkflowNodeSummary { id: string; nodeKey: string; state: string; attemptCount: number; latestAttemptId: string | null; version: number; outcome: string | null }
export interface WorkflowExecution { control: WorkflowControl; execution: { id: string; revision: number; version: number; state: WorkflowRequirementState; nodes: WorkflowNodeSummary[] } }
export interface WorkflowAttempt { id: string; ordinal: number; planRevision: number; state: string; version: number; createdAt: string; updatedAt: string; roleName: string | null; roleRevisionNumber: number | null; deliveryAccepted: boolean; stopConfirmed: boolean; modelState: string | null; modelVersion: number | null; commandState: string | null; commandVersion: number | null; suspended: boolean; errorCode: string | null; queueState: string | null; workspaceState: string | null }
export interface WorkflowResult { attemptId: string; state: string; sha256: string; delivery: WorkflowDelivery }
export interface WorkflowInputs { version: number; requirementId: string; planRevision: number; nodeId: string; objective: string; values: Array<{ name: string; kind: WorkflowDataKind; source: string; sourceId: string; outputName: string | null; attemptId: string | null; sha256: string; content: unknown; reference?: { version: number; contentSha256: string; sizeBytes: number } | null }> }
export interface WorkflowInputPage { name: string; kind: WorkflowDataKind; sha256: string; text: string; offset: number; nextOffset: number | null; totalLength: number }
export interface WorkflowActivity { connected: boolean; observedAt: string; detail: string | null; truncated: boolean; parts: Array<{ id: string; type: string; label: string; content: string; status: string; startedAt: string | null }> }
export interface WorkflowStart { requestKey: string; expectedVersion: number; expectedControlVersion: number; mode: WorkflowRunMode; targetKey: string | null; inputs: Record<string, WorkflowValue> | null; model: WorkflowModel | null; checkpointAttempts: string[] }
export interface WorkflowCandidateSummary { id: string; attemptId: string; nodeKey: string; sourceTitle: string; baseRevision: number; state: 'PENDING' | 'APPLIED' | 'REJECTED'; version: number; appliedRevision: number | null; sourceState: string; stale: boolean; createdAt: string }
export interface WorkflowCandidate { id: string; attemptId: string; nodeKey: string; sourceTitle: string; baseRevision: number; state: 'PENDING' | 'APPLIED' | 'REJECTED'; version: number; appliedRevision: number | null; decisionReason: string | null; stale: boolean; sourceCompleted: boolean; graph: WorkflowGraph; originalGraph: WorkflowGraph; changes: { added: string[]; changed: string[]; removed: string[]; affected: string[]; protectedNodes: string[] }; diagnostics: WorkflowDiagnostic[] }

export interface WorkflowPresetSummary { id: string; version: number; title: string; description: string }
export interface WorkflowPresetPort { name: string; title: string; kind: WorkflowDataKind; required: boolean }
export interface WorkflowPreset extends WorkflowPresetSummary { inputs: WorkflowPresetPort[]; node: WorkflowNode; roleName: string | null; roleRevisionNumber: number | null }

export interface WorkflowCommandPreparation { name: string; directory: string; argv: string[] }
export interface WorkflowCommandRequest { id: string; directory: string; argv: string[]; timeoutSeconds: number; preparations?: WorkflowCommandPreparation[] }
export interface WorkflowProcessIdentity { pid: number; startedAt: string }
export interface WorkflowCommandResult { requestSha256: string; worker: WorkflowProcessIdentity; exitCode: number | null; launched: boolean; timedOut: boolean; cancelled: boolean; outputTruncated: boolean; stopConfirmed: boolean; output: string; error: string; children: WorkflowProcessIdentity[]; preparations?: WorkflowCommandResult[] }
export interface WorkflowCommandEvidence { attemptId: string; requestSha256: string | null; resultSha256: string | null; request: WorkflowCommandRequest | null; registration: { requestSha256: string; worker: WorkflowProcessIdentity } | null; result: WorkflowCommandResult | null; nativeReport?: unknown }

export interface WorkflowFile { path: string; sizeBytes: number; sha256: string | null; mode: string | null; target?: boolean | null; exclusion?: string | null; blobSha?: string | null }
export interface WorkflowUploadRequest { requestKey: string; expectedVersion: number; expectedRevision: number }
export interface WorkflowUploadReference { version: 1; type: 'UPLOADED_DOCUMENTS'; uploadId: string; sha256: string }
export interface WorkflowUpload {
  id: string; createdAt: string; ready: boolean; reference: WorkflowUploadReference; parserVersion: string; resume: WorkflowUploadRequest | null
  originals: Array<{ filename: string; path: string; sizeBytes: number; sha256: string; representationSha256: string; format: string; sections: number; limitations: string[] }>
}

export interface WorkflowCodeChange { path: string; kind: 'ADD' | 'MODIFY' | 'DELETE'; beforeBlob: string | null; afterBlob: string | null }

export interface WorkflowKnowledgeEntry { id: string; toolName: string; createdAt: string }
export interface WorkflowKnowledgeBody extends WorkflowKnowledgeEntry { content: Record<string, unknown> }

export interface WorkflowFileText { path: string; sha256: string; text: string; offset: number; nextOffset: number | null }

export interface WorkflowSnapshotPartialReport { content: string; sha256: string; capturedAt: string; planRevision: number; analyzedUnits: number; pendingUnits: number; excludedUnits: number }
