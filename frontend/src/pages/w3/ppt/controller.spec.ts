import { webcrypto, randomUUID } from 'node:crypto'
import { File as NodeFile } from 'node:buffer'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { pptApi } from '@/api/ppt'
import { ApiError } from '@/api/client'
import { pptDeck, pptDocument, pptGeneration } from '@/components/ppt/pptTestFixtures'
import { createPptStudioController, studioActive, studioEditable } from './controller'
import { deferred, message, source, studioFixture } from './testFixture'

const owners: ReturnType<typeof createPptStudioController>[] = []
function owner(id = 'doc') { const value = createPptStudioController(id); owners.push(value); return value }
beforeEach(() => { sessionStorage.clear(); vi.stubGlobal('crypto', { randomUUID, subtle: webcrypto.subtle }); vi.stubGlobal('File', NodeFile) })
afterEach(() => { owners.forEach(value => value.retire()); owners.length = 0; vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('PPT React studio single command and projection owner', () => {
  it('initializes by GET only and detaches its single subscription without stopping server generation', async () => {
    const f = studioFixture(); const s = owner(), detach = s.attachView(); await s.start(); expect(f.get).toHaveBeenCalledTimes(1); expect(f.operations).not.toHaveBeenCalled(); expect(pptApi.send).not.toHaveBeenCalled(); expect(f.events).toHaveLength(1); detach(); expect(f.events[0]!.close).toHaveBeenCalledOnce()
  })
  it('freezes message text, scope, key and revision and explicitly retries identical bytes', async () => {
    const f = studioFixture(), s = owner(), detach = s.attachView(); await s.start()
    const scope = { kind: 'ELEMENT' as const, slideId: 'slide-1', elementId: 'text-1' }; vi.mocked(pptApi.send).mockRejectedValueOnce(new Error('receipt lost')); s.setChat('原要求')
    expect(s.retire(false).kind).toBe('CONFIRM_DISCARD'); expect(f.events[0]!.close).not.toHaveBeenCalled(); expect(pptApi.send).not.toHaveBeenCalled()
    const sending = s.send('原要求', scope); expect(s.getSnapshot().pending?.phase).toBe('SENDING'); expect(s.retire(false).kind).toBe('BLOCK'); expect(f.events[0]!.close).not.toHaveBeenCalled()
    await sending; const first = vi.mocked(pptApi.send).mock.calls[0]!; scope.slideId = 'different'; s.setChat('新的正文'); expect(s.getSnapshot().chat).toBe('原要求'); expect(s.canLeave().kind).toBe('BLOCK'); expect(s.retire(false).kind).toBe('BLOCK'); expect(f.events[0]!.close).not.toHaveBeenCalled(); expect(studioEditable(s.getSnapshot())).toBe(false)
    expect(await s.retryOriginal()).toBe(true); expect(vi.mocked(pptApi.send).mock.calls[1]).toEqual(first); expect(s.getSnapshot().chat).toBe(''); detach(); expect(f.events[0]!.close).toHaveBeenCalledOnce()
  })
  it('reads an exact observed message without POST and rejects same-key different-body receipts', async () => {
    studioFixture(); const s = owner(); await s.start(); vi.mocked(pptApi.send).mockRejectedValueOnce(new Error('lost')); await s.send('原要求', { kind: 'DOCUMENT' }); const first = vi.mocked(pptApi.send).mock.calls[0]![1]; vi.mocked(pptApi.messages).mockResolvedValue({ items: [message(first.idempotencyKey, '不同正文')], facets: {} }); expect(await s.recover()).toBe(false); expect(s.canLeave().kind).toBe('BLOCK'); expect(pptApi.send).toHaveBeenCalledTimes(1); vi.mocked(pptApi.messages).mockResolvedValue({ items: [message(first.idempotencyKey, first.text, first.scope, first.expectedRevision)], facets: {} }); expect(await s.recover()).toBe(true); expect(pptApi.send).toHaveBeenCalledTimes(1)
  })
  it('retains accepted readback failure and recovers only by GET, never repeats the POST', async () => {
    const f = studioFixture(); const s = owner(); await s.start(); f.get.mockRejectedValueOnce(new Error('GET offline')); await s.operations([{ op: 'update_element', slideId: 'slide-1', elementId: 'text-1', patch: { x: 81 } }], 3); expect(s.getSnapshot().pending?.phase).toBe('ACCEPTED_READBACK'); expect(s.canLeave().kind).toBe('BLOCK'); expect(await s.retryOriginal()).toBe(true); expect(f.operations).toHaveBeenCalledTimes(1); expect(s.getSnapshot().deck?.slides[0]?.elements[0]?.x).toBe(81)
  })
  it('restores accepted metadata without implicit writes and only reads on explicit recovery', async () => {
    const f = studioFixture(); sessionStorage.setItem('loopper.ppt.pending.doc', JSON.stringify({ kind: 'operations', key: 'original', revision: 3, payload: { operations: [{ op: 'update_slide', slideId: 'slide-1', patch: { title: '新标题' } }] }, phase: 'ACCEPTED_READBACK' })); const s = owner(); await s.start(); expect(f.operations).not.toHaveBeenCalled(); expect(s.canLeave().kind).toBe('BLOCK'); expect(await s.recover()).toBe(false); expect(s.canLeave().kind).toBe('BLOCK'); f.setDocument({ revision: 4 }); expect(await s.recover()).toBe(true); expect(f.operations).not.toHaveBeenCalled()
  })
  it('keeps the acknowledged revision blocked when a successful GET still returns the previous projection', async () => {
    const f = studioFixture(); const s = owner(); await s.start(); f.operations.mockResolvedValueOnce({ revision: 4, deck: pptDeck(), createdIds: {} }); await s.operations([{ op: 'update_slide', slideId: 'slide-1', patch: { title: '待核对标题' } }], 3); expect(s.getSnapshot().pending).toMatchObject({ phase: 'ACCEPTED_READBACK', revision: 3, acceptedRevision: 4 }); expect(s.canLeave().kind).toBe('BLOCK'); expect(await s.retryOriginal()).toBe(false); expect(f.operations).toHaveBeenCalledTimes(1); f.setDocument({ revision: 4 }); expect(await s.recover()).toBe(true); expect(f.operations).toHaveBeenCalledTimes(1)
  })
  it('ignores regressing document and message versions from a later old read', async () => {
    const f = studioFixture(), current = { ...message(), version: 2, answer: '最新回答' }; vi.mocked(pptApi.messages).mockResolvedValue({ items: [current], facets: {} }); const s = owner(); await s.start(); f.setDocument({ revision: 4, version: 4 }); await s.refresh(); f.setDocument({ revision: 3, version: 3 }); expect(await s.refresh()).toBe(false); expect(s.getSnapshot().document?.revision).toBe(4); f.setDocument({ revision: 4, version: 4 }); vi.mocked(pptApi.messages).mockResolvedValue({ items: [{ ...current, version: 1, answer: '旧回答' }], facets: {} }); await s.refresh(); expect(s.getSnapshot().messages[0]?.answer).toBe('最新回答')
  })
  it('restores unknown metadata with same original key but never replays from loading', async () => {
    const f = studioFixture(); const pending = { kind: 'operations', key: 'original', revision: 2, payload: { operations: [{ op: 'update_slide', slideId: 'slide-1', patch: { notes: '旧草稿' } }] } }; sessionStorage.setItem('loopper.ppt.pending.doc', JSON.stringify(pending)); const s = owner(); await s.start(); expect(f.operations).not.toHaveBeenCalled(); expect(await s.retryOriginal()).toBe(true); expect(f.operations).toHaveBeenCalledWith('doc', 2, pending.payload.operations, 'original')
  })
  it('keeps volatile unknown identity blocked when sessionStorage rejects all writes', async () => {
    studioFixture(); const storage = { getItem: () => null, setItem: () => { throw new Error('quota') }, removeItem: () => { throw new Error('quota') } }; const s = createPptStudioController('doc', { storage }); owners.push(s); await s.start(); vi.mocked(pptApi.send).mockRejectedValueOnce(new Error('lost')); await s.send('保留', { kind: 'DOCUMENT' }); expect(s.canLeave().kind).toBe('BLOCK'); expect(s.getSnapshot().pending?.payload.text).toBe('保留')
  })
  it('preserves a conflicting baseline and issues no automatic merged write', async () => {
    const f = studioFixture(); const s = owner(); await s.start(); vi.mocked(pptApi.operations).mockRejectedValueOnce(new ApiError('作品已更新', 409)); await s.operations([{ op: 'update_slide', slideId: 'slide-1', patch: { title: '草稿' } }], 1); expect(f.operations.mock.calls[0]?.[1]).toBe(1); expect(f.operations).toHaveBeenCalledTimes(1); expect(s.getSnapshot().pending).toBeNull(); expect(s.canLeave().kind).toBe('ALLOW')
  })
  it('cannot project a retired document read or unlock another owner with a late receipt', async () => {
    const f = studioFixture('A'), read = deferred<ReturnType<typeof pptDocument>>(); f.get.mockReturnValueOnce(read.promise); const a = owner('A'), loading = a.start(); a.retire(); const b = owner('B'); await b.start(); read.resolve(pptDocument('A')); await loading; expect(a.getSnapshot().document).toBeNull(); expect(b.getSnapshot().document?.id).toBe('B'); const late = deferred<Awaited<ReturnType<typeof pptApi.operations>>>(); f.operations.mockReturnValueOnce(late.promise); const pending = b.operations([{ op: 'update_slide', slideId: 'slide-1', patch: { title: 'late' } }]); b.retire(); late.resolve({ revision: 4, deck: pptDeck(), createdIds: {} }); await pending; expect(b.getSnapshot().document?.revision).toBe(3)
  })
  it('bounds stable plan revision disagreement and retains a coherent old snapshot', async () => {
    const f = studioFixture(); const s = owner(); await s.start(); f.setDocument({ revision: 4 }); f.getPlan.mockResolvedValue({ revision: 3, plan: s.getSnapshot().plan }); const before = f.get.mock.calls.length; expect(await s.refresh()).toBe(false); expect(f.get.mock.calls.length - before).toBe(3); expect(s.getSnapshot().document?.revision).toBe(3); expect(s.getSnapshot().error).toContain('本轮读取已暂停')
  })
  it('uses activity reads for unchanged deck and reloads all scene data on revision changes', async () => {
    const f = studioFixture(); const s = owner(); await s.start(); f.getDeck.mockClear(); f.getPlan.mockClear(); await s.refresh('activity'); expect(f.getDeck).not.toHaveBeenCalled(); expect(f.getPlan).not.toHaveBeenCalled(); f.setDocument({ revision: 4 }); await s.refresh('activity'); expect(f.getDeck).toHaveBeenCalledWith('doc', 4); expect(s.getSnapshot().document?.revision).toBe(4)
  })
  it('keeps STOPPING active and keyless uncertain stop cannot be repeated', async () => {
    studioFixture(); vi.mocked(pptApi.generation).mockResolvedValue(pptGeneration('STOPPING')); vi.mocked(pptApi.agent).mockResolvedValue({ state: 'STOPPING', runId: 'run', detail: '', version: 3, questions: [] }); const stop = vi.spyOn(pptApi, 'stop').mockRejectedValueOnce(new Error('lost')); const s = owner(); await s.start(); expect(studioActive(s.getSnapshot())).toBe(true); await s.stop(); expect(await s.retryOriginal()).toBe(false); expect(stop).toHaveBeenCalledTimes(1); expect(await s.recover()).toBe(true); expect(stop).toHaveBeenCalledTimes(1)
  })
  it('freezes requirement confirmation question/version/boolean through unknown replay', async () => {
    studioFixture(); const s = owner(); await s.start(); const question = { id: 'q', kind: 'REQUIREMENTS_CONFIRMATION' as const, prompt: '确认', options: [], state: 'PENDING' as const, answer: null, version: 2 }; vi.mocked(pptApi.reply).mockRejectedValueOnce(new Error('lost')); await s.reply(question, '确认以上需求，请开始设计', true); const original = vi.mocked(pptApi.reply).mock.calls[0]!; question.version = 9; expect(await s.retryOriginal()).toBe(true); expect(vi.mocked(pptApi.reply).mock.calls[1]).toEqual(original); expect(original[2]).toMatchObject({ version: 2, confirmed: true, expectedRevision: 3 })
  })
  it('retains confirmation and resume revisions and never generates from freeform discussion', async () => {
    studioFixture(); const s = owner(); await s.start(); const confirm = vi.spyOn(pptApi, 'confirmGeneration').mockRejectedValueOnce(new Error('lost')); await s.confirmRequirements(); const first = confirm.mock.calls[0]; confirm.mockResolvedValueOnce(pptGeneration()); await s.retryOriginal(); expect(confirm.mock.calls[1]).toEqual(first); expect(first?.[1]).toBe(3); const generate = vi.spyOn(pptApi, 'generate'); expect(generate).not.toHaveBeenCalled()
  })
  it('autosaves only a real dirty edit once, retains custom fields, and pauses stale baselines', async () => {
    const f = studioFixture(); f.setDocument({ phase: 'DESIGN' }); const s = owner(); const detach = s.attachView(); await s.start(); const key = s.openDraft('plan', 'doc'); vi.useFakeTimers(); const plan = { ...s.getSnapshot().drafts[key]!.value, customPolicy: { retained: true }, brief: { ...s.getSnapshot().plan.brief, audience: '业务部门' } }; s.changeDraft(key, plan); await vi.advanceTimersByTimeAsync(899); expect(pptApi.savePlan).not.toHaveBeenCalled(); await vi.advanceTimersByTimeAsync(1); expect(pptApi.savePlan).toHaveBeenCalledTimes(1); expect(vi.mocked(pptApi.savePlan).mock.calls[0]![2]).toMatchObject({ customPolicy: { retained: true } }); await vi.advanceTimersByTimeAsync(2000); expect(pptApi.savePlan).toHaveBeenCalledTimes(1); expect(s.getSnapshot().drafts[key]?.dirty).toBe(false); detach()
  })
  it('restores a stale property draft without autosaving or clearing it on server revision change', async () => {
    const f = studioFixture(); f.setDocument({ revision: 4 }); const element = pptDeck().slides[0]!.elements[0]!; sessionStorage.setItem('loopper.ppt.element.doc.text-1', JSON.stringify({ draft: { ...element, text: '本地文字' }, baseline: JSON.stringify(element), revision: 3 })); const s = owner(); const detach = s.attachView(); await s.start(); const key = s.openDraft('element', 'text-1', 'slide-1'); vi.useFakeTimers(); await vi.advanceTimersByTimeAsync(1500); expect(f.operations).not.toHaveBeenCalled(); expect(s.getSnapshot().drafts[key]).toMatchObject({ dirty: true, revision: 3 }); expect(s.canLeave().kind).toBe('CONFIRM_DISCARD'); s.reloadDraft(key); expect(s.getSnapshot().drafts[key]).toMatchObject({ dirty: false, revision: 4 }); detach()
  })
  it('retains the exact original File reference and refuses changed-byte reselection', async () => {
    studioFixture(); const s = owner(); await s.start(); const file = new File(['bytes'], '资料.md'), upload = vi.spyOn(pptApi, 'upload').mockRejectedValueOnce(new Error('lost')); await s.upload(file, 'sources'); expect(upload.mock.calls[0]![1]).toBe(file); const pending = s.getSnapshot().pending!; expect(pending.file?.sha256).toHaveLength(64); expect(s.canLeave().kind).toBe('BLOCK'); expect(await s.reselectFile(new File(['other'], '资料.md'))).toBe(false); expect(upload).toHaveBeenCalledTimes(1); vi.mocked(pptApi.sources).mockResolvedValue({ sources: [source({ name: file.name, bytes: file.size, sha256: pending.file!.sha256 })], assets: [] }); expect(await s.recover()).toBe(true); expect(upload).toHaveBeenCalledTimes(1)
  })
  it('refuses oversized sources before upload and never makes a legacy no-hash claim about same bytes', async () => {
    studioFixture(); const upload = vi.spyOn(pptApi, 'upload'); const fresh = owner('oversize'); await fresh.start(); expect(await fresh.upload(new File([new Uint8Array(20 * 1024 * 1024 + 1)], '过大的资料.md'), 'sources')).toBe(false); expect(upload).not.toHaveBeenCalled(); sessionStorage.setItem('loopper.ppt.pending.doc', JSON.stringify({ kind: 'upload', key: 'old', revision: 3, payload: {}, file: { name: '资料.md', size: 5, kind: 'sources' }, phase: 'UNKNOWN' })); const s = owner(); await s.start(); expect(await s.reselectFile(new File(['bytes'], '资料.md'))).toBe(false); expect(await s.retryOriginal()).toBe(false); expect(upload).not.toHaveBeenCalled(); expect(s.canLeave().kind).toBe('BLOCK')
  })
  it('invalidates earlier project-source reads and check results of an older revision', async () => {
    const f = studioFixture(); const s = owner(); await s.start(); const old = deferred<Awaited<ReturnType<typeof pptApi.knowledge>>>(), knowledge = vi.spyOn(pptApi, 'knowledge').mockReturnValueOnce(old.promise).mockResolvedValueOnce({ project: { id: 'new', name: '当前项目' }, sources: [], detail: '' }); const first = s.readKnowledge(); await s.readKnowledge(); old.resolve({ project: { id: 'old', name: '旧项目' }, sources: [], detail: '' }); await first; expect(s.getSnapshot().knowledge?.project?.id).toBe('new'); expect(knowledge).toHaveBeenCalledTimes(2); const check = deferred<Awaited<ReturnType<typeof pptApi.checks>>>(); vi.mocked(pptApi.checks).mockReturnValueOnce(check.promise); const checking = s.check(); f.setDocument({ revision: 4 }); await s.refresh(); check.resolve({ issues: [{ severity: 'ERROR', code: 'old', slideId: 'slide-1', elementId: 'text-1', message: '旧问题' }] }); await checking; expect(s.getSnapshot().issues).toEqual([])
  })
})
