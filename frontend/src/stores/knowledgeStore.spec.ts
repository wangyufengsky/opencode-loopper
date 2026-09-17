import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { webcrypto } from 'node:crypto'
import { knowledgeApi as api } from '@/api/knowledge'
import { useKnowledgeStore } from './knowledgeStore'
import type { KnowledgeConversation } from '@/types/domain'
vi.mock('@/api/knowledge', () => ({ knowledgeApi: { create: vi.fn(), receipt: vi.fn(), get: vi.fn(), messages: vi.fn(), send: vi.fn(), stop: vi.fn(), events: vi.fn() } }))
const conversation: KnowledgeConversation = { id: 'conversation', projectId: 'project', title: '问题', model: 'local/model', state: 'IDLE', sources: [], createdAt: '', updatedAt: '', version: 0 }
const input = { id: 'conversation', projectId: 'project', title: '问题', model: 'local/model', sourceIds: ['code'] }
describe('knowledge authoritative chat', () => {
  beforeEach(() => {
    vi.clearAllMocks(); sessionStorage.clear(); setActivePinia(createPinia()); vi.stubGlobal('crypto', webcrypto)
    vi.mocked(api.events).mockReturnValue({ close: vi.fn() } as unknown as EventSource)
    vi.mocked(api.receipt).mockResolvedValue({ accepted: false }); vi.mocked(api.create).mockResolvedValue(conversation); vi.mocked(api.get).mockResolvedValue(conversation); vi.mocked(api.messages).mockResolvedValue({ items: [], facets: {} })
  })
  it('keeps exact message identity across a lost response and page reload', async () => {
    vi.mocked(api.send).mockRejectedValueOnce(new Error('lost'))
    expect(await useKnowledgeStore().send('问题', input)).toBe(false)
    const key = vi.mocked(api.send).mock.calls[0]![1]
    setActivePinia(createPinia()); const restored = useKnowledgeStore(); await restored.load('conversation')
    vi.mocked(api.send).mockResolvedValue({} as never)
    expect(await restored.send('问题', input)).toBe(true)
    expect(vi.mocked(api.send).mock.calls[1]![1]).toBe(key)
    expect(api.create).toHaveBeenCalledTimes(1)
  })
  it('clears an acknowledged lost response on reload so the next question can be sent', async () => {
    vi.mocked(api.send).mockRejectedValueOnce(new Error('lost'))
    await useKnowledgeStore().send('问题', input)
    const oldKey = vi.mocked(api.send).mock.calls[0]![1]
    vi.mocked(api.receipt).mockResolvedValue({ accepted: true, messageId: 'stored' })
    setActivePinia(createPinia()); const store = useKnowledgeStore(); await store.load('conversation')
    vi.mocked(api.send).mockResolvedValue({} as never)
    expect(await store.send('新的问题', input)).toBe(true)
    expect(vi.mocked(api.send).mock.calls[1]![1]).not.toBe(oldKey)
    expect(store.pendingText).toBe('')
  })
  it('restores an unacknowledged draft after reload without automatically dispatching it', async () => {
    vi.mocked(api.send).mockRejectedValueOnce(new Error('lost')); await useKnowledgeStore().send('问题', input)
    setActivePinia(createPinia()); const store = useKnowledgeStore(); await store.load('conversation')
    expect(store.pendingText).toBe('问题'); expect(api.send).toHaveBeenCalledTimes(1)
  })
  it('rejects a changed question while the previous delivery is unknown', async () => {
    vi.mocked(api.send).mockRejectedValue(new Error('lost')); const store = useKnowledgeStore()
    await store.send('问题', input); expect(await store.send('different', input)).toBe(false)
    expect(api.send).toHaveBeenCalledTimes(1); expect(store.error).toContain('上一条发送结果')
  })
  it('never lets late reads overwrite a different conversation', async () => {
    let resolve!: (value: KnowledgeConversation) => void
    vi.mocked(api.get).mockReturnValueOnce(new Promise(done => { resolve = done }))
    const store = useKnowledgeStore(), first = store.load('old')
    vi.mocked(api.get).mockResolvedValue({ ...conversation, id: 'new' }); await store.load('new')
    resolve({ ...conversation, id: 'old' }); await first
    expect(store.conversation?.id).toBe('new')
  })
  it('keeps stop pending until REST reports an idle conversation', async () => {
    const stopping = { ...conversation, state: 'STOPPING' as const }
    vi.mocked(api.get).mockResolvedValue(stopping); vi.mocked(api.stop).mockResolvedValue(stopping)
    const store = useKnowledgeStore(); await store.load('conversation'); await store.stop()
    expect(store.active).toBe(true)
    vi.mocked(api.get).mockResolvedValue(conversation); await store.refresh(); expect(store.active).toBe(false)
  })
  it('does not restore an old conversation when its stop response arrives after navigation', async () => {
    let resolve!: (value: KnowledgeConversation) => void
    vi.mocked(api.stop).mockReturnValueOnce(new Promise(done => { resolve = done }))
    const store = useKnowledgeStore(); await store.load('conversation'); const stopping = store.stop()
    vi.mocked(api.get).mockResolvedValue({ ...conversation, id: 'new' }); await store.load('new')
    resolve(conversation); await stopping; expect(store.conversation?.id).toBe('new')
  })
  it('does not turn an SSE disconnect into a model failure', async () => {
    const stream = { close: vi.fn(), onerror: undefined as (() => void) | undefined }
    vi.mocked(api.events).mockReturnValue(stream as unknown as EventSource)
    const store = useKnowledgeStore(); await store.load('conversation'); stream.onerror?.()
    expect(store.disconnected).toBe(true); expect(store.conversation?.state).toBe('IDLE'); expect(store.error).toBe('')
  })
})
