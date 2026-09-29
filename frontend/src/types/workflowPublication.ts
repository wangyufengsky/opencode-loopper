export interface WorkflowPublicationSource {
  nodeKey: string; nodeTitle: string; attemptId: string; ordinal: number; attemptState: string
  outputName: string; outputTitle: string; createdAt: string; changedFiles: number; totalFiles: number
}
export interface WorkflowPublicationPreview {
  requirementId: string; requirementVersion: number; planRevision: number; requirementState: string
  source: WorkflowPublicationSource; workspaceKind: 'GIT' | 'DIRECT'; sourceBranch: string | null
  reference: { version: number; snapshotId: string; sha256: string }; deliverySha256: string
  baseTree: string; resultTree: string; added: number; modified: number; deleted: number; totalBytes: number; sha256: string
}
export interface WorkflowPublicationCommit {
  requirementId: string; state: 'CONFIRMED' | 'BLOCKED' | 'COMMITTED'; version: number
  nodeTitle: string; outputTitle: string; attemptState: string; branch: string; message: string; commit: string | null; createdAt: string; reasonCode?: string | null
}
export interface WorkflowPublicationRequest {
  requestKey: string; expectedVersion: number; revision: number; node: string; attempt: string; output: string; previewSha256: string; message: string
}
