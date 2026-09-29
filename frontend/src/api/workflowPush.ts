import { request } from './client'
import type { WorkflowPushPreview, WorkflowPushRequest, WorkflowPushView } from '@/types/domain'
const path = (id: string) => `/workflows/requirements/${encodeURIComponent(id)}/publication/push`
const write = (body: unknown) => ({ method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(body) })
export const workflowPush = {
  status: (id: string, signal?: AbortSignal) => request<WorkflowPushView | null>(path(id), { signal }),
  remotes: (id: string, signal?: AbortSignal) => request<string[]>(`${path(id)}/remotes`, { signal }),
  preview: (id: string, remote: string, signal?: AbortSignal) => request<WorkflowPushPreview>(`${path(id)}/preview`, { ...write({ remote }), signal }),
  confirm: (id: string, body: WorkflowPushRequest) => request<WorkflowPushView>(path(id), write(body)),
  retry: (id: string, expectedVersion: number) => request<WorkflowPushView>(`${path(id)}/retry`, write({ expectedVersion })),
}
