import { request, type CursorPage } from './client'
import { PPT_PHASES, PPT_RUN_STATES, type PptDocument, type PptDeck, type PptOperation, type PptOperationResult, type PptPlan, type PptCapabilities, type PptSource, type PptSourceContent, type PptAsset, type PptJob, type PptRevision, type PptIssue, type PptMessage, type PptAgentStatus, type PptScope } from '@/types/domain'
import { requirePublicState } from '@/types/states'
const ui = { 'X-Loopper-Local-UI': '1' }, enc = encodeURIComponent
const base = (id: string) => `/ppt/documents/${enc(id)}`
const query = (values: Record<string, string | number | boolean | undefined>) => new URLSearchParams(Object.entries(values).filter(([, value]) => value !== undefined).map(([key, value]) => [key, String(value)]))
const write = (body: unknown): RequestInit => ({ method: 'POST', headers: ui, body: JSON.stringify(body) })
function document(value: PptDocument) { return { ...value, phase: requirePublicState(PPT_PHASES, value.phase, 'PPT') } }
function message(value: PptMessage) { return { ...value, state: requirePublicState(PPT_RUN_STATES, value.state, 'PPT 助手') } }
export const pptApi = {
  list: async (filters: { query?: string; archived?: boolean; phase?: string; cursor?: string } = {}) => { const page = await request<CursorPage<PptDocument>>(`/ppt/documents?${query({ query: filters.query, phase: filters.phase, cursor: filters.cursor, archive: filters.archived ? 'archived' : 'active' })}`); return { ...page, items: page.items.map(document) } },
  create: async (input: { id: string; title: string; projectId?: string; model?: string }) => document(await request<PptDocument>('/ppt/documents', write(input))),
  get: async (id: string) => document(await request<PptDocument>(base(id))),
  deck: (id: string, revision?: number) => request<PptDeck>(`${base(id)}/deck?${query({ revision })}`),
  plan: (id: string) => request<{ plan: PptPlan; revision: number }>(`${base(id)}/plan`),
  savePlan: (id: string, expectedRevision: number, plan: PptPlan, idempotencyKey: string) => request<{ revision: number; plan: PptPlan }>(`${base(id)}/plan`, write({ expectedRevision, plan, idempotencyKey })),
  operations: (id: string, expectedRevision: number, operations: PptOperation[], idempotencyKey: string) => request<PptOperationResult>(`${base(id)}/operations`, write({ expectedRevision, operations, idempotencyKey })),
  action: (id: string, action: string, expectedRevision: number, idempotencyKey: string, extra: Record<string, unknown> = {}) => request<PptDocument>(`${base(id)}/actions/${enc(action)}`, write({ expectedRevision, idempotencyKey, ...extra })),
  revisions: (id: string, cursor = '') => request<CursorPage<PptRevision>>(`${base(id)}/revisions?${query({ cursor })}`),
  sources: (id: string) => request<{ sources: PptSource[]; assets: PptAsset[] }>(`${base(id)}/sources`),
  source: (id: string, sourceId: string) => request<PptSourceContent>(`${base(id)}/sources/${enc(sourceId)}`),
  upload: (id: string, file: File, kind: 'sources' | 'assets', idempotencyKey: string) => { const body = new FormData(); body.append('file', file); body.append('idempotencyKey', idempotencyKey); return request<PptSource | PptAsset>(`${base(id)}/${kind}`, { method: 'POST', headers: ui, body }) },
  assetUrl: (id: string, assetId: string) => `${import.meta.env.VITE_API_BASE ?? '/api'}${base(id)}/assets/${enc(assetId)}`,
  artifactUrl: (id: string, artifactId: string) => `${import.meta.env.VITE_API_BASE ?? '/api'}${base(id)}/artifacts/${enc(artifactId)}`,
  jobs: (id: string) => request<PptJob[]>(`${base(id)}/jobs`),
  job: (id: string, jobId: string) => request<PptJob>(`${base(id)}/jobs/${enc(jobId)}`),
  retryJob: (id: string, jobId: string) => request<PptJob>(`${base(id)}/jobs/${enc(jobId)}/retry`, { method: 'POST', headers: ui }),
  createJob: (id: string, kind: 'PREVIEW' | 'EXPORT', revision: number, idempotencyKey: string, slideId?: string) => request<PptJob>(`${base(id)}/jobs`, write({ kind, revision, idempotencyKey, slideId })),
  checks: (id: string, revision?: number) => request<{ issues: PptIssue[] }>(`${base(id)}/checks?${query({ revision })}`),
  capabilities: () => request<PptCapabilities>('/ppt/capabilities'),
  messages: async (id: string, cursor = '') => { const page = await request<CursorPage<PptMessage>>(`${base(id)}/messages?${query({ cursor })}`); return { ...page, items: page.items.map(message) } },
  send: async (id: string, input: { idempotencyKey: string; expectedRevision: number; text: string; scope: PptScope }) => message(await request<PptMessage>(`${base(id)}/messages`, write(input))),
  reply: (id: string, questionId: string, input: { idempotencyKey: string; expectedRevision: number; version: number; answer: string }) => request<PptMessage>(`${base(id)}/questions/${enc(questionId)}/reply`, write(input)),
  agent: async (id: string) => { const result = await request<PptAgentStatus>(`${base(id)}/agent`); return { ...result, state: requirePublicState(PPT_RUN_STATES, result.state, 'PPT 助手') } },
  stop: (id: string) => request<PptAgentStatus>(`${base(id)}/stop`, { method: 'POST', headers: ui }),
  events: (id: string) => new EventSource(`${import.meta.env.VITE_API_BASE ?? '/api'}${base(id)}/events`),
}
