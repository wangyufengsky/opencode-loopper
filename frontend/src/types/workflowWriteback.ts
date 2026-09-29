export interface WorkflowWritebackSelection { revision: number; node: string; attempt: string; output: string; sourceSha256: string }
export interface WorkflowWritebackPreview {
  requirementId: string; requirementVersion: number; revision: number; sourceSha256: string; directory: string
  currentSha256: string; targetSha256: string | null; sha256: string; added: number; modified: number; deleted: number
  preservedChanges: number; conflictCount: number; conflicts: string[]
}

export interface WorkflowWritebackRequest { requestKey: string; expectedVersion: number; selection: WorkflowWritebackSelection; previewSha256: string }
export interface WorkflowWritebackView {
  requirementId: string; state: 'CONFIRMED' | 'APPLYING' | 'BLOCKED' | 'APPLIED'; version: number
  queueState: 'QUEUED' | 'ADMITTED' | 'FINISHED'; queuePosition: number; preview: WorkflowWritebackPreview
  nodeTitle: string; outputTitle: string; attemptState: string; createdAt: string; appliedAt: string | null; blocker: string | null
}
