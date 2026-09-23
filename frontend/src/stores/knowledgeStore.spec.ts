import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { webcrypto } from 'node:crypto'
import { knowledgeApi as api } from '@/api/knowledge'
import { useKnowledgeStore } from './knowledgeStore'
import type { KnowledgeConversation, KnowledgeMessage } from '@/types/domain'
vi.mock('@/api/knowledge', () => ({ knowledgeApi: { create: vi.fn(), receipt: vi.fn(), get: vi.fn(), messages: vi.fn(), updates: vi.fn(), send: vi.fn(), stop: vi.fn(), events: vi.fn() } }))
const conversation: KnowledgeConversation = { id: 'conversation', projectId: 'project', title: '问题', model: 'local/model', state: 'IDLE', sources: [], createdAt: '', updatedAt: '', version: 0 }
const input = { id: 'conversation', projectId: 'project', title: '问题', model: 'local/model', sourceIds: ['code'] }
const message = (ordinal: number, answer = ''): KnowledgeMessage => ({ id: `message-${ordinal}`, ordinal, state: 'COMPLETED', userText: '问题', answer, thinking: '', detail: '', inputTokens: null, outputTokens: null, createdAt: '', citations: [], calls: [] })
describe('knowledge authoritative chat', () => {
  beforeEach(() => {
    vi.clearAllMocks(); vi.mocked(api.updates).mockResolvedValue({ items: [], facets: {}, nextCursor: undefined }); sessionStorage.clear(); setActivePinia(createPinia()); vi.stubGlobal('crypto', webcrypto)
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
  it('does not download loaded historical pages on a live refresh', async () => {
    vi.mocked(api.messages).mockResolvedValueOnce({ items: [], facets: {}, nextCursor: 'older-1' })
      .mockResolvedValueOnce({ items: [], facets: {}, nextCursor: 'older-2' }).mockResolvedValueOnce({ items: [], facets: {}, nextCursor: undefined })
    const store = useKnowledgeStore(); await store.load('conversation'); await store.more(); await store.more()
    vi.mocked(api.messages).mockClear(); await store.refresh()
    expect(api.messages).toHaveBeenCalledTimes(0); expect(api.updates).toHaveBeenCalledTimes(1)
  })
  it('updates the last turn, catches up missing pages, and preserves older messages and token totals', async () => {
    vi.mocked(api.get).mockResolvedValue({ ...conversation, usage: { inputTokens: 100, outputTokens: 80 } })
    vi.mocked(api.messages).mockResolvedValue({ items: [message(3, '草稿')], facets: {}, nextCursor: 'older' })
    const store = useKnowledgeStore(); await store.load('conversation')
    vi.mocked(api.messages).mockResolvedValue({ items: [message(1, '历史')], facets: {}, nextCursor: undefined }); await store.more()
    vi.mocked(api.get).mockResolvedValue({ ...conversation, usage: { inputTokens: 90, outputTokens: null } })
    vi.mocked(api.updates).mockResolvedValueOnce({ items: [message(3, '完成'), message(4)], facets: {}, nextCursor: 'catch-up' })
      .mockResolvedValueOnce({ items: [message(5)], facets: {}, nextCursor: undefined })
    await store.refresh(); await vi.waitFor(() => expect(store.messages).toHaveLength(4))
    expect(api.updates).toHaveBeenNthCalledWith(1, 'conversation', 2, '')
    expect(api.updates).toHaveBeenNthCalledWith(2, 'conversation', 2, 'catch-up')
    expect(store.messages.map(row => row.answer)).toEqual(['历史', '完成', '', ''])
    expect(store.conversation?.usage).toEqual({ inputTokens: 100, outputTokens: 80 })
  })
  it('coalesces event bursts and discards a pending timer when leaving the conversation', async () => {
    vi.useFakeTimers()
    try {
      const stream = { close: vi.fn(), onmessage: undefined as (() => void) | undefined }
      vi.mocked(api.events).mockReturnValue(stream as unknown as EventSource)
      const store = useKnowledgeStore(); await store.load('conversation')
      for (let i = 0; i < 10; i++) stream.onmessage?.()
      await vi.advanceTimersByTimeAsync(180); expect(api.updates).toHaveBeenCalledTimes(1)
      stream.onmessage?.(); store.reset(); await vi.advanceTimersByTimeAsync(180)
      expect(api.updates).toHaveBeenCalledTimes(1)
    } finally { vi.useRealTimers() }
  })
  it('ignores late incremental pages after a conversation switch', async () => {
    let resolve!: (page: Awaited<ReturnType<typeof api.updates>>) => void
    vi.mocked(api.updates).mockReturnValueOnce(new Promise(done => { resolve = done }))
    const store = useKnowledgeStore(); await store.load('conversation'); const pending = store.refresh()
    vi.mocked(api.get).mockResolvedValue({ ...conversation, id: 'new' }); await store.load('new')
    resolve({ items: [message(9, '旧会话')], facets: {}, nextCursor: 'old' }); await pending
    expect(store.conversation?.id).toBe('new'); expect(store.messages).toEqual([])
    await store.refresh(); expect(api.updates).toHaveBeenLastCalledWith('new', 0, '')
  })
})
