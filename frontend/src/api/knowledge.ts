import { request, type CursorPage } from './client'
import type { KnowledgeSource, KnowledgeConversation, KnowledgeMessage, KnowledgeCitation, KnowledgeContent, KnowledgeListing, KnowledgeSearch, KnowledgeCreate, KnowledgeQuestion } from '@/types/domain'
const ui = { 'X-Loopper-Local-UI': '1' }
const enc = encodeURIComponent
const conv = (id: string) => `/knowledge/conversations/${enc(id)}`
const source = (project: string, id = '') => `/projects/${enc(project)}/knowledge-sources${id ? `/${enc(id)}` : ''}`
const query = (params: Record<string, string | number | undefined>) => new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)]))
export const knowledgeApi = {
  history: (projectId: string, cursor = '', filters: Record<string, string> = {}) => request<CursorPage<KnowledgeConversation>>(`/knowledge/conversations?${query({ projectId, cursor, ...filters })}`),
  archive: (id: string, archived: boolean, version: number) => request<KnowledgeConversation>(`${conv(id)}/archive`, { method: 'POST', headers: ui, body: JSON.stringify({ archived, version }) }),
  reply: (id: string, question: string, input: { idempotencyKey: string; answers: string[][]; version: number }) => request<KnowledgeQuestion>(`${conv(id)}/questions/${enc(question)}/reply`, { method: 'POST', headers: ui, body: JSON.stringify(input) }),
  git: (project: string, id: string, params: Record<string, string | number | undefined>) => request<KnowledgeContent>(`${source(project, id)}/git?${query(params)}`),
  create: (input: KnowledgeCreate) => request<KnowledgeConversation>('/knowledge/conversations', { method: 'POST', headers: ui, body: JSON.stringify({ timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, ...input }) }),
  receipt: (id: string, key: string) => request<{ accepted: boolean; messageId?: string; state?: string }>(`${conv(id)}/requests/${enc(key)}`),
  get: (id: string) => request<KnowledgeConversation>(conv(id)),
  messages: (id: string, cursor = '') => request<CursorPage<KnowledgeMessage>>(`${conv(id)}/messages?${query({ cursor })}`),
  updates: (id: string, afterOrdinal: number, cursor = '') => request<CursorPage<KnowledgeMessage>>(`${conv(id)}/messages/updates?${query({ afterOrdinal, cursor })}`),
  send: (id: string, idempotencyKey: string, text: string) => request<KnowledgeMessage>(`${conv(id)}/messages`, { method: 'POST', headers: ui, body: JSON.stringify({ idempotencyKey, text }) }),
  stop: (id: string) => request<KnowledgeConversation>(`${conv(id)}/stop`, { method: 'POST', headers: ui }),
  citation: (id: string, citation: string) => request<{ citation: KnowledgeCitation; body: KnowledgeContent }>(`${conv(id)}/citations/${enc(citation)}`),
  file: (id: string, path: string, startLine = 1, endLine = 0, section = 0) => request<KnowledgeContent>(`${conv(id)}/file?${query({ path, startLine, endLine, section })}`),
  sources: (project: string, cursor = '') => request<CursorPage<KnowledgeSource>>(`${source(project)}?${query({ cursor })}`),
  directory: (project: string, path: string) => request<KnowledgeSource>(`${source(project)}/directories`, { method: 'POST', headers: ui, body: JSON.stringify({ path }) }),
  upload: (project: string, files: File[]) => { const body = new FormData(); files.forEach(file => body.append('files', file)); return request<KnowledgeSource[]>(`${source(project)}/uploads`, { method: 'POST', headers: ui, body }) },
  remove: (project: string, id: string, version: number) => request<void>(`${source(project, id)}?${query({ version })}`, { method: 'DELETE', headers: ui }),
  refresh: (project: string, id: string, version: number) => request<KnowledgeSource>(`${source(project, id)}/refresh`, { method: 'POST', headers: ui, body: JSON.stringify({ version }) }),
  database: (project: string, id: string, params: Record<string, string | number | undefined>) => request<KnowledgeContent>(`${source(project, id)}/database?${query(params)}`),
  browse: (project: string, id: string, params: Record<string, string | undefined>) => request<KnowledgeListing>(`${source(project, id)}/directory?${query(params)}`),
  read: (project: string, id: string, params: Record<string, string | number | undefined>) => request<KnowledgeContent>(`${source(project, id)}/content?${query(params)}`),
  search: (project: string, id: string, params: Record<string, string | undefined>) => request<KnowledgeSearch>(`${source(project, id)}/search?${query(params)}`),
  searchProject: (project: string, params: Record<string, string | number | undefined>) => request<KnowledgeSearch>(`${source(project)}/search?${query(params)}`),
  events: (id: string) => new EventSource(`${import.meta.env.VITE_API_BASE ?? '/api'}${conv(id)}/events`),
}
