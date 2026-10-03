import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import { createRunController, type Run } from './runController'
import type { DocumentTemplateOverview } from '@/types/domain'
import { createDocumentSourcesOwner, createClarificationOwner, createSupplementOwner } from './documentOwners'
import { createBatchRecoveryOwner, createDiagnosticOwner } from './recoveryOwners'
import { batch, deferred, diagnostic, documentRun, failedPage, flush, sourceRun, stream } from './test-support'

const roots: { retire(forced?: boolean): unknown }[] = []
function attached<T extends { attachView(): () => void; retire(forced?: boolean): unknown }>(owner: T) { roots.push(owner); owner.attachView(); return owner }
beforeEach(() => {
  vi.useFakeTimers(); vi.stubGlobal('EventSource', class {})
  vi.spyOn(api, 'documentTemplate').mockResolvedValue(documentRun()); vi.spyOn(api, 'sourceTemplate').mockResolvedValue(sourceRun())
  vi.spyOn(api, 'documentEvents').mockImplementation(() => stream() as unknown as EventSource); vi.spyOn(api, 'sourceEvents').mockImplementation(() => stream() as unknown as EventSource)
  vi.spyOn(api, 'sourceBatches').mockResolvedValue({ items: [], facets: {} }); vi.spyOn(api, 'templateFailedBatches').mockResolvedValue(failedPage())
  vi.spyOn(api, 'getTemplateSessionDiagnostics').mockResolvedValue({ items: [diagnostic], nextCursor: null, hasMore: false })
})
afterEach(() => { roots.splice(0).forEach(owner => owner.retire(true)); vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('W3 run owner original identity and cleanup', () => {
  for (const kind of ['source', 'document'] as const) for (const phase of ['sending', 'unknown'] as const) it(`B3.2 ${kind}/${phase} hard blocks same-record and exit guard until original result`, async () => {
    const pending = deferred<Run>(), write = kind === 'source' ? vi.spyOn(api, 'sourceTemplateCommand').mockReturnValue(pending.promise as Promise<ReturnType<typeof sourceRun>>) : vi.spyOn(api, 'documentTemplateCommand').mockReturnValue(pending.promise as Promise<ReturnType<typeof documentRun>>)
    const owner = attached(createRunController(kind, 'A')); await flush(); owner.requestCommand(kind === 'source' ? 'start' : 'resume'); await flush()
    expect(write).toHaveBeenCalledTimes(1); if (phase === 'unknown') { pending.reject(new Error('未知回执')); await flush() }
    expect(owner.canLeave()).toMatchObject({ kind: 'BLOCK' }); owner.requestCommand('resume'); await flush(); expect(write).toHaveBeenCalledTimes(1)
    if (phase === 'sending') { pending.reject(new Error('未知回执')); await flush() }
    expect(owner.operation()!.identity.body.expectedVersion).toBe(3)
  })
  for (const [kind, action] of [['source', 'cancel'], ['source', 'retry'], ['document', 'resume'], ['document', 'cancel']] as const) it(`B3.3 ${kind}/${action} freezes action/version/key/selection across authoritative refresh`, async () => {
    const write = kind === 'source' ? vi.spyOn(api, 'sourceTemplateCommand').mockRejectedValue(new Error('未知回执')) : vi.spyOn(api, 'documentTemplateCommand').mockRejectedValue(new Error('未知回执'))
    if (action === 'retry') { vi.mocked(api.sourceTemplate).mockResolvedValue({ ...sourceRun(), templateId: 'DETAILED_DESIGN_WRITING', canResume: true }); vi.mocked(api.sourceBatches).mockResolvedValue({ items: [{ id: 'batch-a', ordinal: 0, retryable: true }, { id: 'batch-b', ordinal: 1, retryable: true }] as never[], facets: {} }) }
    const owner = attached(createRunController(kind, 'A')); await flush(); owner.toggleBatch('batch-a', true); owner.requestCommand(action); if (action === 'cancel') owner.confirmCommand(); await flush()
    const original = structuredClone(write.mock.calls[0]); expect(original).toBeDefined()
    if (kind === 'source') vi.mocked(api.sourceTemplate).mockResolvedValue({ ...sourceRun('A', 4), templateId: action === 'retry' ? 'DETAILED_DESIGN_WRITING' : 'UNIT_TEST_DEVELOPMENT' }); else vi.mocked(api.documentTemplate).mockResolvedValue(documentRun('A', 4))
    await owner.refresh(); owner.toggleBatch('batch-b', true); await owner.recover(); expect(write.mock.calls[1]).toEqual(original)
    expect(owner.canLeave()).toMatchObject({ kind: 'BLOCK' })
  })
  it('B4.1 expired cancellation callback cannot write after retirement or change a new owner', async () => {
    const write = vi.spyOn(api, 'documentTemplateCommand').mockResolvedValue(documentRun('A', 4)), a = attached(createRunController('document', 'A')); await flush(); a.requestCommand('cancel'); const confirm = a.confirmCommand
    a.retire(true); vi.mocked(api.documentTemplate).mockResolvedValue(documentRun('B')); const b = attached(createRunController('document', 'B')); await flush(); const before = b.getSnapshot(); confirm(); await flush()
    expect(write).not.toHaveBeenCalled(); expect(b.getSnapshot()).toEqual(before)
  })
  it('B4.1 current cancel confirms once; canceling the dialog writes nothing', async () => {
    const write = vi.spyOn(api, 'documentTemplateCommand').mockResolvedValue(documentRun('A', 4)), owner = attached(createRunController('document', 'A')); await flush(); owner.requestCommand('cancel'); owner.cancelConfirmation(); expect(write).not.toHaveBeenCalled(); owner.requestCommand('cancel'); owner.confirmCommand(); owner.confirmCommand(); await flush()
    expect(write).toHaveBeenCalledTimes(1); expect(write).toHaveBeenCalledWith('A', 'cancel', expect.objectContaining({ expectedVersion: 3, requestKey: expect.any(String) }))
  })
  for (const kind of ['source', 'document'] as const) it(`${kind} read-only leave is allowed; SSE/poll are exact owned cleanup without stop POST`, async () => {
    const write = kind === 'source' ? vi.spyOn(api, 'sourceTemplateCommand') : vi.spyOn(api, 'documentTemplateCommand'), owner = attached(createRunController(kind, 'A')); await flush()
    const s = vi.mocked(kind === 'source' ? api.sourceEvents : api.documentEvents).mock.results[0]!.value as unknown as ReturnType<typeof stream>
    expect(owner.canLeave()).toEqual({ kind: 'ALLOW' }); expect(s.addEventListener).toHaveBeenCalledTimes(1); const late = s.onerror; late?.(); owner.retire(true); const before = owner.getSnapshot()
    expect(s.close).toHaveBeenCalledTimes(1); expect(s.removeEventListener).toHaveBeenCalledWith('progress', s.addEventListener.mock.calls[0]![1]); expect(vi.getTimerCount()).toBe(0)
    late?.(); await vi.advanceTimersByTimeAsync(20_000); expect(owner.getSnapshot()).toEqual(before); expect(write).not.toHaveBeenCalled()
  })
  for (const kind of ['directory', 'body'] as const) it(`B8.3 sources/${kind} late retired response preserves the entire snapshot`, async () => {
    const run = { ...documentRun(), files: [{ id: 'file', filename: '需求.md', sha256: 'hash' }] as DocumentTemplateOverview['files'] }, pending = deferred<any>()
    const sections = { items: [{ fileId: 'file', ordinal: 0, title: '第一段', characters: 1, sha256: 'hash' }], nextOffset: null }
    vi.spyOn(api, 'documentSections').mockReturnValue(kind === 'directory' ? pending.promise : Promise.resolve(sections)); vi.spyOn(api, 'documentSection').mockReturnValue(pending.promise)
    const owner = attached(createDocumentSourcesOwner(run)); if (kind === 'directory') void owner.sections('file'); else { await owner.sections('file'); void owner.body('file', 0) }
    await flush(); owner.retire(true); const before = owner.getSnapshot(); pending.resolve(kind === 'directory' ? sections : { ...sections.items[0], content: 'A原文' }); await flush(); expect(owner.getSnapshot()).toEqual(before)
  })
  it('B8.3 clarification late success retains original answer and cannot call updated or clear next draft', async () => {
    const pending = deferred<ReturnType<typeof documentRun>>(), write = vi.spyOn(api, 'answerDocumentRequirements').mockReturnValue(pending.promise), updated = vi.fn(), a = attached(createClarificationOwner(documentRun(), 'REQ-1', updated)); a.changeAnswer('原始回答'); void a.submit(); await flush(); a.retire(true); const before = a.getSnapshot()
    const b = attached(createClarificationOwner(documentRun('B'), 'REQ-1', vi.fn())); b.changeAnswer('B草稿'); pending.resolve(documentRun('A', 4)); await flush()
    expect(write).toHaveBeenCalledTimes(1); expect(a.getSnapshot()).toEqual(before); expect(a.getSnapshot().answer).toBe('原始回答'); expect(b.getSnapshot().answer).toBe('B草稿'); expect(updated).not.toHaveBeenCalled()
  })
  it('B8.3 supplement late options cannot open or unlock a retired owner', async () => {
    const pending = deferred<any>(); vi.spyOn(api, 'documentSupplementOptions').mockReturnValue(pending.promise); const owner = attached(createSupplementOwner(documentRun(), vi.fn())); void owner.open(); await flush(); owner.retire(true); const before = owner.getSnapshot(); pending.resolve({ available: true, message: '补传', request: { requestKey: 'original', expectedVersion: 3, expectedTaskVersion: -1 } }); await flush(); expect(owner.getSnapshot()).toEqual(before); expect(before.busy).toBe(true); expect(before.opened).toBe(false)
  })
  it('B8.3 supplement late upload preserves actual File reference and never calls updated', async () => {
    vi.spyOn(api, 'documentSupplementOptions').mockResolvedValue({ available: true, message: '补传', request: { requestKey: 'original', expectedVersion: 3, expectedTaskVersion: -1 } }); const pending = deferred<ReturnType<typeof documentRun>>(), write = vi.spyOn(api, 'uploadDocumentSupplement').mockReturnValue(pending.promise), updated = vi.fn(), owner = attached(createSupplementOwner(documentRun(), updated)), file = new File(['原始字节'], '需求.md')
    await owner.open(); owner.choose([file]); void owner.submit(); await flush(); expect(write.mock.calls[0]![2]![0]).toBe(file); owner.retire(true); const before = owner.getSnapshot(); pending.resolve(documentRun('A', 4)); await flush(); expect(owner.getSnapshot()).toEqual(before); expect(owner.getFiles()[0]).toBe(file); expect(updated).not.toHaveBeenCalled()
  })
  it('B9.3 batch original CAS explicit recovery does not project new GET versions into the body', async () => {
    const write = vi.spyOn(api, 'retrySelectedTemplateBatches').mockRejectedValue(new Error('未知批次回执')), owner = attached(createBatchRecoveryOwner('task', 'task-A')); await flush(); owner.select('batch-1', true); await owner.retry(); const original = write.mock.calls[0]
    vi.mocked(api.templateFailedBatches).mockResolvedValue(failedPage(4)); await owner.load(); expect(write).toHaveBeenCalledTimes(1); await owner.recover(); expect(write.mock.calls[1]).toEqual(original); expect(write.mock.calls[1]![1][0]!.version).toBe(3); expect(owner.canLeave()).toMatchObject({ kind: 'BLOCK' })
  })
  it('batch CAS 409 remains unknown; stop proof denial permits no replay', async () => {
    const write = vi.spyOn(api, 'retrySelectedTemplateBatches').mockRejectedValueOnce(new Error('未知回执')).mockRejectedValue(new ApiError('冲突', 409)), owner = attached(createBatchRecoveryOwner('task', 'task-A')); await flush(); owner.select('batch-1', true); await owner.retry(); await owner.recover(); expect(write).toHaveBeenCalledTimes(2); expect(owner.canLeave()).toMatchObject({ kind: 'BLOCK' })
    vi.mocked(api.templateFailedBatches).mockResolvedValue(failedPage(4, false)); await owner.load(); await owner.recover(); expect(write).toHaveBeenCalledTimes(2); expect(owner.operation()!.identity.body).toEqual([batch(3)])
  })
  it('B9.3 diagnostic accepted read failure only retries the read despite stale canFinalize projection', async () => {
    const write = vi.spyOn(api, 'recoverTemplateSession').mockResolvedValue({ ...diagnostic, canFinalize: false }), owner = attached(createDiagnosticOwner('task-A', false)); await flush()
    vi.mocked(api.getTemplateSessionDiagnostics).mockRejectedValueOnce(new Error('接受后读取失败')).mockResolvedValue({ items: [diagnostic], nextCursor: null, hasMore: false }); owner.request(diagnostic, 'FINALIZE'); await flush()
    expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.canLeave()).toMatchObject({ kind: 'BLOCK' }); await owner.load(); owner.request(diagnostic, 'FINALIZE'); expect(write).toHaveBeenCalledTimes(1); await owner.recover(); expect(write).toHaveBeenCalledTimes(1); expect(owner.canLeave()).toEqual({ kind: 'ALLOW' })
  })
})

for (const invalid of ['wrong-id', 'missing-version', 'old-version'] as const) it(`fulfilled ${invalid} run receipt remains accepted readback and never authorizes a new POST`, async () => {
  const receipt = invalid === 'wrong-id' ? documentRun('B', 4) : invalid === 'old-version' ? documentRun('A', 2) : { ...documentRun('A', 4), version: undefined }
  const write = vi.spyOn(api, 'documentTemplateCommand').mockResolvedValue(receipt as DocumentTemplateOverview), owner = attached(createRunController('document', 'A')); await flush(); owner.requestCommand('resume'); await flush()
  expect(owner.getSnapshot().command).toMatchObject({ phase: 'ACCEPTED_READBACK', accepted: true }); expect(owner.canLeave()).toMatchObject({ kind: 'BLOCK' }); expect(owner.getSnapshot().run!.id).toBe('A'); expect(owner.getSnapshot().run!.version).toBe(3)
  await owner.recover(); owner.requestCommand('resume'); await flush(); expect(write).toHaveBeenCalledTimes(1); expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK')
})
it('normal authoritative old-version GET cannot roll back a newer run', async () => { const owner = attached(createRunController('document', 'A')); await flush(); owner.applyRun(documentRun('A', 8)); vi.mocked(api.documentTemplate).mockResolvedValue(documentRun('A', 4)); await owner.refresh(); expect(owner.getSnapshot().run!.version).toBe(8) })
it('run parent permits only one writer across root commands and child forms', async () => {
  const pending = deferred<DocumentTemplateOverview>(), write = vi.spyOn(api, 'answerDocumentRequirements').mockReturnValue(pending.promise), rootWrite = vi.spyOn(api, 'documentTemplateCommand').mockResolvedValue(documentRun('A', 4)), root = attached(createRunController('document', 'A')); await flush()
  const a = attached(createClarificationOwner(documentRun(), 'REQ-1', root.applyRun)), b = attached(createClarificationOwner(documentRun(), 'REQ-2', root.applyRun)); root.registerChild(a); root.registerChild(b); a.changeAnswer('原回答'); b.changeAnswer('另一回答'); void a.submit(); await flush()
  root.requestCommand('resume'); void b.submit(); await flush(); expect(write).toHaveBeenCalledTimes(1); expect(rootWrite).not.toHaveBeenCalled(); expect(root.canLeave().kind).toBe('BLOCK'); pending.reject(new Error('未确认')); await flush(); root.requestCommand('resume'); expect(rootWrite).not.toHaveBeenCalled()
})
it('a settled diagnostic receipt still prevents a stale same-version canFinalize GET from minting another command', async () => {
  const write = vi.spyOn(api, 'recoverTemplateSession').mockResolvedValue({ ...diagnostic, canFinalize: false }), owner = attached(createDiagnosticOwner('task-A', false)); await flush(); owner.request(diagnostic, 'FINALIZE'); await flush(); expect(owner.getSnapshot().command.phase).toBe('SETTLED'); await owner.load(); owner.request(diagnostic, 'FINALIZE'); await flush(); expect(write).toHaveBeenCalledTimes(1); expect(owner.canAct(diagnostic, 'FINALIZE')).toBe(false)
})
it('active task batch polling preserves selection and cancels its exact timer at last view detach without POST', async () => {
  const write = vi.spyOn(api, 'retrySelectedTemplateBatches'), owner = createBatchRecoveryOwner('task', 'task-A', true); roots.push(owner); const detach = owner.attachView(); await flush(); expect(vi.mocked(api.templateFailedBatches)).toHaveBeenCalledTimes(1); owner.select('batch-1', true); await vi.advanceTimersByTimeAsync(5000); expect(vi.mocked(api.templateFailedBatches)).toHaveBeenCalledTimes(1); owner.select('batch-1', false); await vi.advanceTimersByTimeAsync(5000); expect(vi.mocked(api.templateFailedBatches)).toHaveBeenCalledTimes(2); detach(); expect(vi.getTimerCount()).toBe(0); await vi.advanceTimersByTimeAsync(20_000); expect(vi.mocked(api.templateFailedBatches)).toHaveBeenCalledTimes(2); expect(write).not.toHaveBeenCalled()
})
