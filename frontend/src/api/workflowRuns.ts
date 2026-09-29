import { api, request } from './client'
import type { WorkflowSnapshotPartialReport, WorkflowFileText, WorkflowKnowledgeBody, WorkflowKnowledgeEntry, WorkflowCodeChange, WorkflowFile, WorkflowFinish, WorkflowFinishRequest, WorkflowInputPage } from '@/types/domain'
import type { WorkflowSaveTemplate, WorkflowTemplatePreview, WorkflowTemplateSelection } from '@/types/domain'
import type { WorkflowCommandEvidence, WorkflowCandidate, WorkflowCandidateSummary, WorkflowActivity, WorkflowAttempt, WorkflowControl, WorkflowDelivery, WorkflowExecution, WorkflowGraph, WorkflowInputs, WorkflowLayout, WorkflowNode, WorkflowPage, WorkflowReceipt, WorkflowRequirement, WorkflowRequirementSummary, WorkflowResult, WorkflowStart } from '@/types/domain'
const base = '/workflows/requirements', enc = encodeURIComponent
const path = (id: string) => `${base}/${enc(id)}`
const node = (id: string, key: string) => `${path(id)}/nodes/${enc(key)}`
const attempt = (id: string, key: string, run: string) => `${node(id, key)}/attempts/${enc(run)}`
const write = (method: string, body: unknown) => ({ method, headers: { 'X-Loopper-Local-UI': '1' }, body: JSON.stringify(body) })
export const workflowRuns = {
  list: (projectId = '', cursor = '') => request<WorkflowPage<WorkflowRequirementSummary>>(`${base}?${new URLSearchParams({ ...(projectId ? { projectId } : {}), cursor, limit: '24' })}`),
  projects: (query = '', cursor = '') => api.templateProjects(query, cursor),
  project: (id: string) => api.templateProject(id),
  get: (id: string) => request<WorkflowRequirement>(path(id)),
  previewTemplate: (id: string, body: WorkflowTemplateSelection) => request<WorkflowTemplatePreview>(`${path(id)}/templates/preview`, write('POST', body)),
  saveTemplate: (id: string, body: WorkflowSaveTemplate) => request<WorkflowReceipt>(`${path(id)}/templates`, write('POST', body)),
  create: (body: { requestKey: string; projectId: string; title: string; objective: string; templateId: string; templateRevision: number }) => request<WorkflowReceipt>(base, write('POST', body)),
  revise: (id: string, body: { requestKey: string; expectedVersion: number; expectedRevision: number; graph: WorkflowGraph }) => request<WorkflowReceipt>(`${path(id)}/plan`, write('PUT', body)),
  applyPlan: (id: string, body: { requestKey: string; expectedVersion: number; expectedRevision: number; graph: WorkflowGraph }) => request<WorkflowReceipt>(`${path(id)}/plan/apply`, write('POST', body)),
  candidates: (id: string, state = 'PENDING', cursor = '') => request<WorkflowPage<WorkflowCandidateSummary>>(`${path(id)}/candidates?${new URLSearchParams({ ...(state ? { state } : {}), cursor, limit: '20' })}`),
  candidate: (id: string, key: string) => request<WorkflowCandidate>(`${path(id)}/candidates/${enc(key)}`),
  applyCandidate: (id: string, key: string, body: { requestKey: string; expectedVersion: number; expectedRevision: number; expectedCandidateVersion: number; graph: WorkflowGraph }) => request<WorkflowReceipt>(`${path(id)}/candidates/${enc(key)}/apply`, write('POST', body)),
  rejectCandidate: (id: string, key: string, body: { requestKey: string; expectedCandidateVersion: number; reason: string }) => request<WorkflowReceipt>(`${path(id)}/candidates/${enc(key)}/reject`, write('POST', body)),
  layout: (id: string, body: { requestKey: string; expectedRevision: number; expectedLayoutVersion: number; layout: WorkflowLayout }) => request<WorkflowReceipt>(`${path(id)}/layout`, write('PUT', body)),
  confirm: (id: string, body: { requestKey: string; expectedVersion: number }) => request<WorkflowReceipt>(`${path(id)}/confirm`, write('POST', body)),
  cancel: (id: string, body: { requestKey: string; expectedVersion: number }) => request<WorkflowReceipt>(`${path(id)}/cancel`, write('POST', body)),
  finishStatus: (id: string) => request<WorkflowFinish>(`${path(id)}/finish`),
  finish: (id: string, body: WorkflowFinishRequest) => request<WorkflowReceipt>(`${path(id)}/finish`, write('POST', body)),
  execution: (id: string) => request<WorkflowExecution>(`${path(id)}/execution`),
  start: (id: string, body: WorkflowStart) => request<WorkflowControl>(`${path(id)}/control/start`, write('POST', body)),
  pause: (id: string, body: { requestKey: string; expectedControlVersion: number }) => request<WorkflowControl>(`${path(id)}/control/pause`, write('POST', body)),
  attempts: (id: string, key: string, cursor = '') => request<WorkflowPage<WorkflowAttempt>>(`${node(id, key)}/attempts?${new URLSearchParams({ cursor, limit: '20' })}`),
  attempt: (id: string, key: string, run: string) => request<WorkflowAttempt>(attempt(id, key, run)),
  definition: (id: string, key: string, run: string) => request<WorkflowNode>(`${attempt(id, key, run)}/definition`),
  inputs: (id: string, key: string, run: string) => request<WorkflowInputs>(`${attempt(id, key, run)}/inputs`),
  inputContent: (id: string, key: string, run: string, name: string, offset = 0, signal?: AbortSignal) => request<WorkflowInputPage>(`${attempt(id, key, run)}/inputs/${enc(name)}/content?${new URLSearchParams({ offset: String(offset), limit: '12000' })}`, { signal }),
  result: (id: string, key: string, run: string) => request<WorkflowResult>(`${attempt(id, key, run)}/result`),
  snapshotPartialReport: (id: string, key: string, run: string, signal?: AbortSignal) => request<WorkflowSnapshotPartialReport>(`${attempt(id, key, run)}/snapshot-partial-report`, { signal }),
  activity: (id: string, key: string, run: string) => request<WorkflowActivity>(`${attempt(id, key, run)}/activity`),
  modelAction: (id: string, key: string, run: string, action: 'stop' | 'resume', body: { requestKey: string; expectedVersion: number }) => request(`${attempt(id, key, run)}/model/${action}`, write('POST', body)),
  commandAction: (id: string, key: string, run: string, action: 'stop' | 'resume', body: { requestKey: string; expectedVersion: number }) => request(`${attempt(id, key, run)}/command/${action}`, write('POST', body)),
  commandEvidence: (id: string, key: string, run: string) => request<WorkflowCommandEvidence>(`${attempt(id, key, run)}/command/evidence`),
  complete: (id: string, key: string, body: { requestKey: string; expectedVersion: number; attemptId: string; expectedAttemptVersion: number; delivery: WorkflowDelivery }) => request(`${node(id, key)}/human/complete`, write('POST', body)),
  knowledgeEvidence: (id: string, key: string, run: string, cursor = '') => request<WorkflowPage<WorkflowKnowledgeEntry>>(`${attempt(id, key, run)}/knowledge?${new URLSearchParams({ cursor, limit: '30' })}`),
  knowledgeEvidenceBody: (id: string, key: string, run: string, entry: string) => request<WorkflowKnowledgeBody>(`${attempt(id, key, run)}/knowledge/${enc(entry)}`),
  files: (id: string, key: string, run: string, direction: 'inputs' | 'outputs', name: string, cursor = '') => request<WorkflowPage<WorkflowFile>>(`${attempt(id, key, run)}/${direction}/${enc(name)}/files?${new URLSearchParams({ cursor, limit: '30' })}`),
  changes: (id: string, key: string, run: string, direction: 'inputs' | 'outputs', name: string, cursor = '') => request<WorkflowPage<WorkflowCodeChange>>(`${attempt(id, key, run)}/${direction}/${enc(name)}/changes?${new URLSearchParams({ cursor, limit: '30' })}`),
  fileText: (id: string, key: string, run: string, direction: 'inputs' | 'outputs', name: string, file: string, offset = 0, signal?: AbortSignal) => request<WorkflowFileText>(`${attempt(id, key, run)}/${direction}/${enc(name)}/text?${new URLSearchParams({ path: file, offset: String(offset), limit: '12000' })}`, { signal }),
  fileUrl: (id: string, key: string, run: string, direction: 'inputs' | 'outputs', name: string, file: string) => `${import.meta.env.VITE_API_BASE ?? '/api'}${attempt(id, key, run)}/${direction}/${enc(name)}/file?${new URLSearchParams({ path: file })}`,
  archiveUrl: (id: string, key: string, run: string, direction: 'inputs' | 'outputs', name: string) => `${import.meta.env.VITE_API_BASE ?? '/api'}${attempt(id, key, run)}/${direction}/${enc(name)}/archive`,
}
