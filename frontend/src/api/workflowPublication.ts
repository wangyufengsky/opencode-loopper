import { request } from './client'
import type { WorkflowPage, WorkflowPublicationSource, WorkflowPublicationPreview, WorkflowPublicationCommit, WorkflowPublicationRequest } from '@/types/domain'
const path = (id: string) => `/workflows/requirements/${encodeURIComponent(id)}/publication`
export const workflowPublication = {
  status: (id: string, signal?: AbortSignal) => request<WorkflowPublicationCommit | null>(path(id), { signal }),
  confirm: (id: string, body: WorkflowPublicationRequest) => request<WorkflowPublicationCommit>(path(id), { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(body) }),
  retry: (id: string, expectedVersion: number) => request<WorkflowPublicationCommit>(`${path(id)}/retry`, { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ expectedVersion }) }),
  sources: (id: string, revision: number, cursor = '', signal?: AbortSignal) => request<WorkflowPage<WorkflowPublicationSource>>(`${path(id)}/sources?${new URLSearchParams({ revision: String(revision), cursor, limit: '20' })}`, { signal }),
  preview: (id: string, revision: number, source: WorkflowPublicationSource, signal?: AbortSignal) => request<WorkflowPublicationPreview>(`${path(id)}/preview?${new URLSearchParams({ revision: String(revision), node: source.nodeKey, attempt: source.attemptId, output: source.outputName })}`, { signal }),
}
