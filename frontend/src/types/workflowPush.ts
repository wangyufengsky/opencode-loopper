export interface WorkflowPushPreview { requirementId: string; publicationVersion: number; remote: string; url: string; branch: string; commit: string; remoteCommit: string | null; sha256: string }
export interface WorkflowPushRequest { requestKey: string; expectedVersion: number; remote: string; previewSha256: string }
export interface WorkflowPushView { requirementId: string; state: 'PREPARING' | 'RUNNING' | 'BLOCKED' | 'PUSHED'; version: number; remote: string; url: string; branch: string; commit: string; ordinal: number; reasonCode: string | null }
