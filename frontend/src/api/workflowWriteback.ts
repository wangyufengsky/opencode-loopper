import { request } from './client'
import type { WorkflowWritebackPreview, WorkflowWritebackSelection, WorkflowWritebackRequest, WorkflowWritebackView } from '@/types/domain'
export const workflowWriteback = {
  status: (id: string, signal?: AbortSignal) => request<WorkflowWritebackView | null>(`/workflows/requirements/${encodeURIComponent(id)}/publication/writeback`, { signal }),
  confirm: (id: string, body: WorkflowWritebackRequest) => request<WorkflowWritebackView>(`/workflows/requirements/${encodeURIComponent(id)}/publication/writeback/confirm`, {
    method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(body),
  }),
  retry: (id: string, expectedVersion: number) => request<WorkflowWritebackView>(`/workflows/requirements/${encodeURIComponent(id)}/publication/writeback/retry`, {
    method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify({ expectedVersion }),
  }),
  preview: (id: string, body: WorkflowWritebackSelection, signal?: AbortSignal) => request<WorkflowWritebackPreview>(`/workflows/requirements/${encodeURIComponent(id)}/publication/writeback/preview`, {
    method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(body), signal,
  }),
}
