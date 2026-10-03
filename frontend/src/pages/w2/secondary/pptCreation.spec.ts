import { webcrypto, randomUUID } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { pptApi } from '@/api/ppt'
import { createPptCreation } from './pptCreation'
import type { W2Navigation } from '../shared/types'
import { navigateAcceptedHandoff } from '@/foundation/contracts/receipt'
const key = 'loopper.ppt.creation.v2'
const document = { id: 'ppt-1', title: '演示', revision: 7, phase: 'BRIEFING', projectId: null, model: 'local/model', version: 0, archived: false, createdAt: '2026-10-01', updatedAt: '2026-10-01' } satisfies Awaited<ReturnType<typeof pptApi.get>>
function fixture(saved?: object) {
  const rows = new Map<string, string>(saved ? [[key, JSON.stringify(saved)]] : []), storage = { getItem: (name: string) => rows.get(name) ?? null, setItem: (name: string, value: string) => { rows.set(name, value) } }
  const transport = { create: vi.fn(async (body: Parameters<typeof pptApi.create>[0]) => ({ ...document, id: body.id! })), get: vi.fn().mockResolvedValue(document), upload: vi.fn().mockResolvedValue({ state: 'READY' }), send: vi.fn(async (_id: string, body: Parameters<typeof pptApi.send>[1]) => ({ ...body, id: 'message', state: 'RUNNING', documentId: _id, answer: '', detail: '', version: 0, questions: [], createdAt: '2026-10-01', updatedAt: '2026-10-01' } satisfies Awaited<ReturnType<typeof pptApi.send>>)), messages: vi.fn().mockResolvedValue({ items: [], facets: {} }) }
  const navigation = { go: vi.fn(async () => true), goAccepted: vi.fn(async (to, permit) => navigateAcceptedHandoff(permit, String(to), async () => true)), back: vi.fn(), registerGuard: vi.fn(() => () => {}), guardChanged: vi.fn() } as W2Navigation
  const owner = createPptCreation(transport, navigation, storage)
  return { owner, transport, navigation, rows }
}
const savedAccepted = { prompt: '原制作要求', project: null, files: [], creationId: 'ppt-1', documentId: 'ppt-1', generationKey: 'original-key', generationRevision: 7, sentAccepted: true }
beforeEach(() => vi.stubGlobal('crypto', { randomUUID, subtle: webcrypto.subtle }))
afterEach(() => vi.unstubAllGlobals())
describe('PPT list retains its original creation protocol', () => {
  it('creates and sends one DOCUMENT discussion with original revision, never generates', async () => {
    const f = fixture(); f.owner.setPrompt('主题为“团队介绍”，先讨论结构'); await f.owner.submit()
    expect(f.transport.create).toHaveBeenCalledTimes(1); expect(f.transport.create.mock.calls[0]![0].title).toBe('团队介绍'); expect(f.transport.send).toHaveBeenCalledTimes(1)
    expect(f.transport.send.mock.calls[0]![1]).toMatchObject({ text: '主题为“团队介绍”，先讨论结构', expectedRevision: 7, scope: { kind: 'DOCUMENT' } }); expect(f.navigation.goAccepted).toHaveBeenCalledTimes(1); expect(f.owner.getSnapshot().prompt).toBe('')
  })
  it('retains real File references and dirty inputs before any command', async () => {
    const f = fixture(), file = new File(['same bytes'], '说明.md', { lastModified: 123 }); await f.owner.addFiles([file]); f.owner.setPrompt('介绍')
    expect(f.owner.getSnapshot().files[0]!.file).toBe(file); expect(f.owner.canLeave().kind).toBe('CONFIRM_DISCARD'); expect(f.transport.create).not.toHaveBeenCalled(); expect(JSON.parse(f.rows.get(key)!).files[0].file).toBeUndefined()
    await f.owner.submit(); expect(f.transport.upload.mock.calls[0]![1]).toBe(file)
  })
  it('freezes prompt and reuses exact key/revision after unknown send', async () => {
    const f = fixture(); f.transport.send.mockRejectedValueOnce(new Error('timeout')); f.owner.setPrompt('原要求'); await f.owner.submit(); const original = f.transport.send.mock.calls[0]![1]
    expect(f.owner.canLeave().kind).toBe('BLOCK'); f.owner.setPrompt('新要求'); expect(f.owner.getSnapshot().prompt).toBe('原要求')
    await f.owner.submit(); expect(f.transport.messages).toHaveBeenCalledTimes(1); expect(f.transport.send).toHaveBeenCalledTimes(2); expect(f.transport.send.mock.calls[1]![1]).toBe(original)
  })
  it('does not resend an accepted message when navigation fails', async () => {
    const f = fixture(); vi.mocked(f.navigation.goAccepted).mockResolvedValueOnce(false); f.owner.setPrompt('原要求'); await f.owner.submit(); expect(f.owner.getSnapshot().sentAccepted).toBe(true); await f.owner.submit()
    expect(f.transport.send).toHaveBeenCalledTimes(1); expect(f.navigation.goAccepted).toHaveBeenCalledTimes(2)
  })
  it('refresh acceptance recovery only GETs matching message and grants exact guarded destination', async () => {
    const f = fixture(savedAccepted); f.transport.messages.mockResolvedValue({ items: [{ idempotencyKey: 'original-key', text: '原制作要求', expectedRevision: 7, scope: { kind: 'DOCUMENT' } }], facets: {} }); let exact = '', other = ''
    vi.mocked(f.navigation.go).mockImplementation(async to => { exact = f.owner.canLeave({ destination: String(to) }).kind; other = f.owner.canLeave({ destination: '/tasks' }).kind; return false })
    await f.owner.submit(); expect(exact).toBe('ALLOW'); expect(other).toBe('BLOCK'); expect(f.owner.canLeave().kind).toBe('BLOCK'); expect(JSON.parse(f.rows.get(key)!).creationId).toBe('ppt-1')
    expect(f.transport.create).not.toHaveBeenCalled(); expect(f.transport.send).not.toHaveBeenCalled(); expect(f.navigation.goAccepted).not.toHaveBeenCalled(); vi.mocked(f.navigation.go).mockResolvedValue(true); await f.owner.submit(); expect(JSON.parse(f.rows.get(key)!).creationId).toBe('')
  })
  it.each(['key', 'revision', 'body'])('does not grant a read-only handoff on %s mismatch', async mismatch => {
    const f = fixture(savedAccepted), message = { idempotencyKey: 'original-key', text: '原制作要求', expectedRevision: 7, scope: { kind: 'DOCUMENT' } }
    if (mismatch === 'key') message.idempotencyKey = 'other'; if (mismatch === 'revision') message.expectedRevision = 8; if (mismatch === 'body') message.text = 'other'
    f.transport.messages.mockResolvedValue({ items: [message], facets: {} }); await f.owner.submit(); expect(f.navigation.go).not.toHaveBeenCalled(); expect(f.transport.send).not.toHaveBeenCalled(); expect(f.owner.canLeave().kind).toBe('BLOCK')
  })
  it('fails closed for metadata-equal different File bytes and pre-SHA recovery copies', async () => {
    const f = fixture(); f.transport.create.mockRejectedValueOnce(new Error('timeout')); const file = new File(['aaa'], '来源.md', { lastModified: 1 }); await f.owner.addFiles([file]); f.owner.setPrompt('保留'); await f.owner.submit()
    const recovered = fixture(JSON.parse(f.rows.get(key)!)); await recovered.owner.addFiles([new File(['bbb'], '来源.md', { lastModified: 1 })]); expect(recovered.owner.getSnapshot().files[0]!.file).toBeUndefined(); expect(recovered.owner.getSnapshot().error).toContain('字节')
    const old = JSON.parse(f.rows.get(key)!); delete old.files[0].sha256; const legacy = fixture(old); await legacy.owner.addFiles([file]); expect(legacy.owner.getSnapshot().files[0]!.file).toBeUndefined()
  })
  it('does not let retired create overwrite another recovery copy', async () => {
    const f = fixture(); let finish!: (value: typeof document) => void; f.transport.create.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    f.owner.setPrompt('A'); const pending = f.owner.submit(); await Promise.resolve(); f.owner.retire(); f.rows.set(key, JSON.stringify({ ...savedAccepted, creationId: 'B' })); finish(document); await pending
    expect(JSON.parse(f.rows.get(key)!).creationId).toBe('B'); expect(f.transport.send).not.toHaveBeenCalled(); expect(f.navigation.goAccepted).not.toHaveBeenCalled()
  })
  it('allows fixing a clearly rejected create without silently clearing input', async () => {
    const f = fixture(); f.transport.create.mockRejectedValueOnce(new ApiError('标题无效', 400)); f.owner.setPrompt('原要求'); await f.owner.submit(); expect(f.owner.getSnapshot().locked).toBe(false); expect(f.owner.getSnapshot().unknown).toBe(false); f.owner.setPrompt('修正要求'); expect(f.owner.getSnapshot().prompt).toBe('修正要求')
  })
})
