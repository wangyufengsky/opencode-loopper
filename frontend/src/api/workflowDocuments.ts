import { request } from './client'
import type { WorkflowFile, WorkflowPage, WorkflowUpload, WorkflowUploadRequest } from '@/types/domain'
const base = (id: string) => `/workflows/requirements/${encodeURIComponent(id)}/documents`
const item = (id: string, upload: string) => `${base(id)}/${encodeURIComponent(upload)}`
export const workflowDocuments = {
  upload: (id: string, metadata: WorkflowUploadRequest, files: File[]) => {
    const body = new FormData()
    body.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }))
    files.forEach(file => body.append('files', file, file.name))
    return request<WorkflowUpload>(base(id), { method: 'POST', headers: { 'X-Loopper-Local-UI': '1' }, body })
  },
  list: (id: string, cursor = '') => request<WorkflowPage<WorkflowUpload>>(`${base(id)}?${new URLSearchParams({ cursor, limit: '20' })}`),
  get: (id: string, upload: string) => request<WorkflowUpload>(item(id, upload)),
  files: (id: string, upload: string, cursor = '') => request<WorkflowPage<WorkflowFile>>(`${item(id, upload)}/files?${new URLSearchParams({ cursor, limit: '30' })}`),
  text: (id: string, upload: string, path: string, offset = 0) => request<{ text: string; nextOffset: number | null }>(`${item(id, upload)}/text?${new URLSearchParams({ path, offset: String(offset), limit: '12000' })}`),
  fileUrl: (id: string, upload: string, path: string) => `${import.meta.env.VITE_API_BASE ?? '/api'}${item(id, upload)}/file?${new URLSearchParams({ path })}`,
}
