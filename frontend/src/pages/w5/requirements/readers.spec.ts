import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { workflowDocuments } from '@/api/workflowDocuments'
import { workflowRuns } from '@/api/workflowRuns'
import { createUploadController } from './uploadController'
import { createContentController, type AttemptContentScope } from './contentController'
import { deferred, uploadReceipt } from './test-support'
import { identifyFiles } from '@/pages/w3/templates/catalog/creation'

const owners: Array<{ retire(forced: boolean): unknown }> = []
function keep<T extends { attachView(): () => void; retire(forced: boolean): unknown }>(value: T) { owners.push(value); value.attachView(); return value }
function file(text: string, name = '原文.md') { const value = new File([text], name); Object.defineProperty(value, 'arrayBuffer', { value: async () => new TextEncoder().encode(text).buffer }); return value }
const scope: AttemptContentScope = { requirement: 'req', node: 'node', attempt: 'original', direction: 'inputs', name: 'document' }
beforeEach(() => { vi.stubGlobal('crypto', webcrypto); vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('unmocked transport forbidden'))) })
afterEach(() => { owners.splice(0).forEach(value => value.retire(true)); vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('native upload bytes/manifest/resource identity', () => {
  it('unknown retries the exact native instances/order/metadata/key even after external file intent changes', async () => {
    const onChange = vi.fn(), owner = keep(createUploadController('req', { version: 7, revision: 2, value: '' }, onChange)), selected = [file('a', 'a.md'), file('b', 'b.md')]
    const write = vi.spyOn(workflowDocuments, 'upload').mockRejectedValue(new Error('lost'))
    expect(await owner.chooseFiles(selected)).toBe(true); await owner.upload(); const original = write.mock.calls[0]!
    expect(owner.canLeave().kind).toBe('BLOCK'); expect(owner.operationIdentity()).toMatchObject({ endpoint: '/workflows/requirements/req/documents', body: { expectedVersion: 7, expectedRevision: 2 } })
    expect(await owner.chooseFiles([file('changed', 'a.md')])).toBe(false); await owner.recover()
    expect(write.mock.calls[1]![1]).toBe(original[1]); expect(write.mock.calls[1]![2][0]).toBe(selected[0]); expect(write.mock.calls[1]![2][1]).toBe(selected[1]); expect(await identifyFiles(write.mock.calls[1]![2])).toEqual(await identifyFiles(selected)); expect(onChange).not.toHaveBeenCalled()
  })
  it('historical incomplete upload resumes only its original key/CAS and complete matching bytes/order', async () => {
    const selected = [file('a', 'a.md'), file('b', 'b.md')], observed = (await identifyFiles(selected)).map((identity, index) => ({ ...identity, size: selected[index]!.size })), row = { ...uploadReceipt(observed, false), resume: { requestKey: 'original-key', expectedVersion: 7, expectedRevision: 2 } }
    const owner = keep(createUploadController('req', { version: 99, revision: 100, value: '' }, vi.fn())), write = vi.spyOn(workflowDocuments, 'upload').mockResolvedValue(uploadReceipt(observed))
    owner.resume(row); expect(await owner.chooseFiles([selected[1]!, selected[0]!])).toBe(false); expect(await owner.chooseFiles([file('changed', 'a.md'), selected[1]!])).toBe(false); expect(write).not.toHaveBeenCalled()
    expect(await owner.chooseFiles(selected)).toBe(true); await owner.upload(); expect(write.mock.calls[0]![1]).toEqual(row.resume); expect(write.mock.calls[0]![2][0]).toBe(selected[0]); expect(owner.getSnapshot().chosen?.ready).toBe(true)
  })
  it('accepted but incomplete DTO is an defensive GET-only recovery; normal successful POST is ready:true', async () => {
    const selected = [file('a')], observed = (await identifyFiles(selected)).map((identity, index) => ({ ...identity, size: selected[index]!.size })), owner = keep(createUploadController('req', { version: 7, revision: 2, value: '' }, vi.fn()))
    const write = vi.spyOn(workflowDocuments, 'upload').mockResolvedValue(uploadReceipt(observed, false)), read = vi.spyOn(workflowDocuments, 'get').mockRejectedValueOnce(new Error('read')).mockResolvedValue(uploadReceipt(observed))
    await owner.chooseFiles(selected); await owner.upload(); expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); await owner.recover(); expect(write).toHaveBeenCalledTimes(1); expect(read.mock.calls).toEqual([['req', 'upload'], ['req', 'upload']]); expect(owner.getSnapshot().chosen?.ready).toBe(true)
  })
  it('hashing native fallback blocks leave, aborts its own FileReader on detach and never uploads late', async () => {
    const Native = FileReader, readers: FileReader[] = []; vi.stubGlobal('FileReader', class extends Native { constructor() { super(); readers.push(this) } })
    const owner = keep(createUploadController('req', { version: 7, revision: 2, value: '' }, vi.fn())), selected = new File(['original '.repeat(10000)], 'large.md'), write = vi.spyOn(workflowDocuments, 'upload')
    const choosing = owner.chooseFiles([selected]); expect(owner.getSnapshot().hashing).toBe(true); expect(owner.canLeave().kind).toBe('BLOCK'); expect(readers).toHaveLength(1); const abort = vi.spyOn(readers[0]!, 'abort'); owner.retire(true); await choosing; await owner.upload(); expect(abort).toHaveBeenCalledTimes(1); expect(readers[0]!.onload).toBeNull(); expect(readers[0]!.onerror).toBeNull(); expect(write).not.toHaveBeenCalled()
  })
  it('ordinary upload draft requires explicit discard; pending cannot be discarded and native File is not serialized', async () => {
    const selected = file('original'), owner = keep(createUploadController('req', { version: 7, revision: 2, value: '' }, vi.fn()))
    await owner.chooseFiles([selected]); expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD'); expect(owner.originalFiles()[0]).toBe(selected); expect(JSON.stringify(owner.getSnapshot())).not.toContain('arrayBuffer')
    const write = vi.spyOn(workflowDocuments, 'upload').mockRejectedValue(new Error('lost')); await owner.upload(); owner.discard(); expect(owner.originalFiles()[0]).toBe(selected); expect(owner.canLeave().kind).toBe('BLOCK'); expect(write).toHaveBeenCalledTimes(1)
  })
  it('selected upload and parsed text identity checks reject foreign GET and wrong pagination without changing original content', async () => {
    const row = uploadReceipt([]), owner = keep(createUploadController('req', { version: 7, revision: 2, value: JSON.stringify(row.reference) }, vi.fn()))
    vi.spyOn(workflowDocuments, 'get').mockResolvedValue({ ...row, id: 'foreign' }); await owner.readSelected(); expect(owner.getSnapshot().chosen).toBeNull()
    await owner.select(row); vi.spyOn(workflowDocuments, 'text').mockResolvedValueOnce({ text: 'first', nextOffset: 5 }).mockResolvedValue({ text: 'wrong', nextOffset: 5 })
    await owner.read('parsed/1/1.md'); await owner.read('parsed/1/1.md', true); expect(owner.getSnapshot().preview).toBe('first'); expect(owner.getSnapshot().nextOffset).toBe(5); expect(owner.getSnapshot().error).toContain('分页与原文档不一致')
  })
})
describe('attempt input/file/evidence scopes and bounded pages', () => {
  it('fixed JSON is read in verified pages; failed identity/cursor keeps original offset and accepted prefix', async () => {
    const input = { name: 'value', source: 'REQUIREMENT', sourceId: 'value', outputName: null, attemptId: null, kind: 'JSON' as const, sha256: 'sha', content: null, reference: { version: 1, contentSha256: 'sha', sizeBytes: 7 } }, owner = keep(createContentController(scope, input))
    const read = vi.spyOn(workflowRuns, 'inputContent').mockResolvedValueOnce({ name: 'value', kind: 'JSON', sha256: 'sha', offset: 0, totalLength: 7, text: '{"x":', nextOffset: 5 }).mockResolvedValueOnce({ name: 'value', kind: 'JSON', sha256: 'wrong', offset: 5, totalLength: 7, text: '1}', nextOffset: null }).mockResolvedValue({ name: 'value', kind: 'JSON', sha256: 'sha', offset: 5, totalLength: 7, text: '1}', nextOffset: null })
    await owner.input(); expect(owner.getSnapshot().inputNext).toBe(5); await owner.input(); expect(owner.getSnapshot().inputText).toBe('{"x":'); expect(owner.getSnapshot().inputNext).toBe(5); await owner.input(); expect(JSON.parse(owner.getSnapshot().inputText)).toEqual({ x: 1 }); expect(read.mock.calls.map(call => call[4])).toEqual([0, 5, 5])
  })
  it('file preview cancellation aborts original signal; late old path cannot replace next selected path', async () => {
    const first = deferred<Awaited<ReturnType<typeof workflowRuns.fileText>>>(), read = vi.spyOn(workflowRuns, 'fileText').mockReturnValueOnce(first.promise).mockResolvedValue({ path: 'b.md', sha256: 'b', offset: 0, nextOffset: null, text: 'B' }), owner = keep(createContentController(scope))
    const pending = owner.readFile('a.md'); await owner.readFile('b.md'); expect(read.mock.calls[0]![7]?.aborted).toBe(true); first.resolve({ path: 'a.md', sha256: 'a', offset: 0, nextOffset: null, text: 'A' }); await pending; expect(owner.getSnapshot().body?.text).toBe('B'); owner.closePreview(); expect(owner.getSnapshot().previewPath).toBe('')
  })
  it('cache remains tuple scoped across attempts; retired read/error does not project or begin another API request', async () => {
    const pending = deferred<Awaited<ReturnType<typeof workflowRuns.fileText>>>(), read = vi.spyOn(workflowRuns, 'fileText').mockReturnValueOnce(pending.promise).mockResolvedValue({ path: 'same.md', sha256: 'b', text: 'B', offset: 0, nextOffset: null }), old = keep(createContentController(scope)), action = old.readFile('same.md')
    old.retire(true); const before = old.getSnapshot(), next = keep(createContentController({ ...scope, attempt: 'next' })); await next.readFile('same.md'); pending.reject(new Error('late old error')); await action; await old.readFile('same.md'); expect(old.getSnapshot()).toBe(before); expect(next.getSnapshot().body?.text).toBe('B'); expect(read).toHaveBeenCalledTimes(2)
  })
  it('pagination failure preserves rows and original cursor; separate evidence selection rejects foreign DTO', async () => {
    const owner = keep(createContentController(scope)), files = vi.spyOn(workflowRuns, 'files').mockResolvedValueOnce({ items: [{ path: 'first.md', sizeBytes: 1, sha256: 'sha', mode: null, blobSha: null, target: true, exclusion: null }], nextCursor: 'next' }).mockRejectedValueOnce(new Error('more')).mockResolvedValue({ items: [], nextCursor: null })
    await owner.files(); await owner.files(true); expect(owner.getSnapshot().files).toHaveLength(1); expect(owner.getSnapshot().fileCursor).toBe('next'); await owner.files(true); expect(files.mock.calls[2]![5]).toBe('next')
    vi.spyOn(workflowRuns, 'knowledgeEvidenceBody').mockResolvedValue({ id: 'foreign', toolName: 'knowledge', createdAt: '', content: {} }); await owner.evidenceBody('original'); expect(owner.getSnapshot().evidenceBody).toBeNull(); expect(owner.getSnapshot().selectedEvidence).toBe('original')
  })
})
