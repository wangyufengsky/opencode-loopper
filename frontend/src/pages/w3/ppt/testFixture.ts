import { vi } from 'vitest'
import { pptApi } from '@/api/ppt'
import { pptAgent, pptCapabilities, pptDeck, pptDocument, pptPlan } from '@/components/ppt/pptTestFixtures'
import type { PptMessage, PptScope, PptSource, PptDeck, PptDocument } from '@/types/ppt'
import type { W2PageProps, W2LeaveGuard } from '@/pages/w2/shared'
import { skins } from '@/themes/registry'

export const deferred = <T,>() => { let resolve!: (value: T) => void, reject!: (failure: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
export const message = (key = 'message', text = '讨论内容', scope: PptScope = { kind: 'DOCUMENT' }, revision = 3): PptMessage => ({ id: 'message-' + key, documentId: 'doc', idempotencyKey: key, text, answer: '## 已整理\n请确认要求。', state: 'COMPLETED', detail: '', scope, expectedRevision: revision, version: 1, questions: [], createdAt: '2026-10-03T10:00:00Z', updatedAt: '2026-10-03T10:00:00Z' })
export const source = (patch: Partial<PptSource> = {}): PptSource => ({ id: 'source', kind: 'DOCUMENT', name: '资料.md', mediaType: 'text/markdown', bytes: 5, sha256: 'sha', state: 'READY', detail: '', createdAt: '2026-10-03', sections: 1, limitations: [], ...patch })
export function studioFixture(id = 'doc') {
  let document = pptDocument(id), deck = pptDeck(), plan = pptPlan()
  const events: (EventSource & { close: ReturnType<typeof vi.fn> })[] = []
  const get = vi.spyOn(pptApi, 'get').mockImplementation(async value => ({ ...document, id: value }))
  const getDeck = vi.spyOn(pptApi, 'deck').mockImplementation(async () => structuredClone(deck))
  const getPlan = vi.spyOn(pptApi, 'plan').mockImplementation(async () => ({ plan: structuredClone(plan), revision: document.revision }))
  vi.spyOn(pptApi, 'sources').mockResolvedValue({ sources: [], assets: [] })
  vi.spyOn(pptApi, 'jobs').mockResolvedValue([])
  vi.spyOn(pptApi, 'revisions').mockResolvedValue({ items: [{ revision: 3, reason: '初稿', createdAt: '2026-10-03' }], facets: {} })
  vi.spyOn(pptApi, 'messages').mockResolvedValue({ items: [], facets: {} })
  vi.spyOn(pptApi, 'agent').mockResolvedValue(pptAgent())
  vi.spyOn(pptApi, 'generation').mockResolvedValue(null)
  vi.spyOn(pptApi, 'capabilities').mockResolvedValue(pptCapabilities())
  vi.spyOn(pptApi, 'events').mockImplementation(() => { const event = { close: vi.fn(), onmessage: null, onopen: null, onerror: null } as EventSource & { close: ReturnType<typeof vi.fn> }; events.push(event); return event })
  const operations = vi.spyOn(pptApi, 'operations').mockImplementation(async (_id, _revision, actions) => {
    for (const action of actions) {
      const slide = deck.slides.find(row => row.id === action.slideId)
      if (action.op === 'update_element' && slide) { const element = slide.elements.find(row => row.id === action.elementId); if (element) Object.assign(element, action.patch) }
      if (action.op === 'update_slide' && slide) Object.assign(slide, action.patch)
    }
    document = { ...document, revision: document.revision + 1 }
    return { revision: document.revision, deck: structuredClone(deck), createdIds: {} }
  })
  vi.spyOn(pptApi, 'savePlan').mockImplementation(async (_id, _revision, saved) => { plan = structuredClone(saved); document = { ...document, revision: document.revision + 1 }; return { revision: document.revision, plan } })
  vi.spyOn(pptApi, 'send').mockImplementation(async (_id, input) => message(input.idempotencyKey, input.text, input.scope, input.expectedRevision))
  vi.spyOn(pptApi, 'reply').mockImplementation(async (_id, _question, input) => message(input.idempotencyKey, input.answer, { kind: 'DOCUMENT' }, input.expectedRevision))
  vi.spyOn(pptApi, 'createJob').mockImplementation(async (_id, kind, revision, _key, slideId) => ({ id: 'job', documentId: id, kind, revision, slideId, state: 'PENDING', completed: 0, total: 2, detail: '', artifacts: [], createdAt: '2026-10-03' }))
  vi.spyOn(pptApi, 'checks').mockResolvedValue({ issues: [] })
  return { get, getDeck, getPlan, operations, events, setDocument: (patch: Partial<PptDocument>) => { document = { ...document, ...patch } }, setDeck: (next: PptDeck) => { deck = next } }
}
export function pageProps() {
  const guards = new Set<W2LeaveGuard>(), retained = new Map<object, () => void>()
  const task: W2PageProps['legacy']['task'] = { getSnapshot: () => ({ usingDemo: false, projects: [], tasks: [], taskItems: [], taskFacets: {}, loading: false }), subscribe: () => () => {}, loadProjects: vi.fn(async () => []), loadTaskSummaries: vi.fn(async () => {}), invalidateTaskSummaries: vi.fn(), setTaskArchived: vi.fn(async () => {}), deleteArchivedTask: vi.fn(async () => {}), refreshRuntime: vi.fn(async () => undefined), startRuntime: vi.fn(async () => undefined), restartRuntime: vi.fn(async () => undefined), activateDemo: vi.fn(), deactivateDemo: vi.fn(async () => {}), replaceProject: vi.fn(), addProject: vi.fn(), removeProject: vi.fn() }
  const props: W2PageProps = { route: { path: '/ppt/doc', fullPath: '/ppt/doc', params: { id: 'doc' }, query: {} }, legacy: { task }, skin: skins[0]!, setSkin: vi.fn(),
    navigation: { go: vi.fn(async () => true), goAccepted: vi.fn(async () => true), back: vi.fn(), guardChanged: vi.fn(), registerGuard: read => { guards.add(read); return () => { guards.delete(read) } } },
    lifecycle: { retain: (owner, release) => { if (!retained.has(owner)) retained.set(owner, release) } } }
  return { props, leave: () => [...guards][0]?.(), retire: () => { for (const release of retained.values()) release(); retained.clear() } }
}
