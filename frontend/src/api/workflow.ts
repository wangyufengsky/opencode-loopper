import { request } from './client'
import type { WorkflowPreset, WorkflowPresetSummary, WorkflowGraph, WorkflowLayout, WorkflowReceipt, WorkflowTemplate, WorkflowTemplateSummary, WorkflowDiagnostic, WorkflowPage } from '@/types/domain'
const base = '/workflows/templates', enc = encodeURIComponent
const write = (method: string, body: unknown) => ({ method, headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(body) })
export const workflowApi = {
  presets(query = '', cursor = '') { return request<WorkflowPage<WorkflowPresetSummary>>(`/workflows/node-presets?${new URLSearchParams({ query, cursor, limit: '20' })}`) },
  preset(id: string, version: number) { return request<WorkflowPreset>(`/workflows/node-presets/${enc(id)}/versions/${version}`) },
  list(query = '', kind = 'ALL', cursor = '') { return request<WorkflowPage<WorkflowTemplateSummary>>(`${base}?${new URLSearchParams({ query, kind, cursor, limit: '24' })}`) },
  get(id: string) { return request<WorkflowTemplate>(`${base}/${enc(id)}`) },
  create(body: { requestKey: string; title: string; description: string; graph: WorkflowGraph; layout: WorkflowLayout }) { return request<WorkflowReceipt>(base, write('POST', body)) },
  revise(id: string, body: { requestKey: string; expectedVersion: number; expectedRevision: number; title: string; description: string; graph: WorkflowGraph }) { return request<WorkflowReceipt>(`${base}/${enc(id)}`, write('PUT', body)) },
  layout(id: string, body: { requestKey: string; expectedRevision: number; expectedLayoutVersion: number; layout: WorkflowLayout }) { return request<WorkflowReceipt>(`${base}/${enc(id)}/layout`, write('PUT', body)) },
  copy(id: string, body: { requestKey: string; sourceRevision: number; title: string }) { return request<WorkflowReceipt>(`${base}/${enc(id)}/copy`, write('POST', body)) },
  archive(id: string, body: { requestKey: string; expectedVersion: number }) { return request<WorkflowReceipt>(`${base}/${enc(id)}`, write('DELETE', body)) },
  validate(graph: WorkflowGraph) { return request<WorkflowDiagnostic[]>(`${base}/validate`, write('POST', graph)) },
}
