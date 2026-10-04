import { flushPromises } from '@/test/async'
import { ReactDOMQuery } from '@/pages/w6-tests/workflow/react-test-root'
import { mountRunRoute, runClick, settleRunBridge } from '@/pages/w3/templates/runs/run-w0-contract'
import * as runControllers from '@/pages/w3/templates/runs/runController'
import { semanticName } from '@/foundation/semanticRegistry'
import { templateClarificationPropScopeW0Contract } from '@/pages/w3/templates/runs/run-child-w0-contract'
/** W0 titles/contracts frozen; W2–W4 approved scopes now exercise production React; W5 remains red. */
import { createHash, webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { api, ApiError } from '@/api/client'
import { createDocumentCreationController } from '@/pages/w3/templates/catalog/creation'
import { creationUiW0Contract } from './w3-creation-contract'
import { templateRunNavigationW0Contract, templateRunIdentityW0Contract, templateCancelScopeW0Contract, templateRunReadonlyLeaveW0Contract } from '@/pages/w3/templates/runs/run-w0-contract'
import { templateSourcesRetirementW0Contract, templateClarificationRetirementW0Contract, templateSupplementRetirementW0Contract } from '@/pages/w3/templates/runs/run-child-w0-contract'
import { foundationDOM } from '@/pages/w2/workflow/page.test-support'
import { designerHistoryW0Contract } from '@/pages/w2/workflow/designer-history-w0-contract'
import { historyScopeW0Contract } from '@/pages/w4/history/w0-contract'
import type { DocumentTemplateOverview, SourceTemplateOverview, TaskDesignHistory, TemplateTaskCatalog } from '@/types/domain'

function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) }
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function mock(name: keyof typeof api) { return vi.spyOn(api, name) as Mock }
function proof(subcase: string, evidence: unknown) { console.info('W0_IDENTITY', JSON.stringify({ subcase, evidence })) }
const roots: Array<{unmount():void}> = []
const streams: { close: ReturnType<typeof vi.fn> }[] = []
function stream() { const value = { close: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), onopen: null, onerror: null }; streams.push(value); return value }
function state(view: ReturnType<typeof routeRoot> extends Promise<infer T> ? T extends {view: infer V} ? V : never : never) { const snapshot=view.owner().getSnapshot();return {...snapshot,run:snapshot.run!,acting:snapshot.command.busy} }
async function button(view: ReactDOMQuery, label: string) { await runClick(view.element as HTMLElement, label==='取消任务'?semanticName('template.cancel'):label) }
async function routeRoot(path: string) {
 const factory=vi.spyOn(runControllers,'createRunController'),page=await mountRunRoute('document',path.split('/').at(-1));roots.push(page.root)
 const view=Object.assign(new ReactDOMQuery(page.host),{owner:()=>factory.mock.results.filter(row=>row.type==='return'&&row.value.viewCount()>0).at(-1)!.value as ReturnType<typeof runControllers.createRunController>})
 return {...page,view}
}
async function modal(view: ReactDOMQuery, accept: boolean) { const dialog=view.get('[role=dialog]');await runClick(dialog.element as HTMLElement,semanticName(accept?'template.cancel':'ui.stay'));await settleRunBridge() }
const branch = { id: 'local:main', label: 'main', ref: 'refs/heads/main', remote: null }
const sourceRun = (id = 'A', version = 3): SourceTemplateOverview => ({ id, projectId: 'p', templateId: 'UNIT_TEST_DEVELOPMENT', templateVersion: '1', title: `源码${id}`, state: 'PENDING_START', version, createdAt: 'now', updatedAt: 'now', archived: false, sourcePath: 'src/main', testOutputPath: null, documentPath: null, requirements: '', snapshot: null, designerId: null, taskId: null, taskState: null, waitingReasonCode: null, waitingMessage: null, coverage: [], progress: [], canResume: false, canArchive: false, testProfile: null })
const documentRun = (id = 'A', version = 3): DocumentTemplateOverview => ({ id, projectId: 'p', templateId: 'REQUIREMENT_CODE_REVIEW', templateVersion: '1', title: `文档${id}`, state: 'ASSESSING', waitingReasonCode: null, waitingMessage: null, designerId: null, taskId: null, requirementRevision: 1, version, createdAt: 'created', updatedAt: 'updated', canCancel: true, canResume: true, archived: false, uploadReady: true, snapshotSha: 'frozen-sha', files: [], progress: { attempts: 2, validated: 1, active: 1, stopped: 0, requirements: 1, reports: 0, revision: 3 } })
const documentInput = { templateId: 'REQUIREMENT_CODE_REVIEW', templateVersion: '1', projectId: 'p', branchId: branch.id }
function file(content = '原始文档', name = '需求.md') {
  const value = new File([content], name, { type: 'text/markdown' })
  Object.defineProperty(value, 'arrayBuffer', { configurable: true, value: () => new Promise<ArrayBuffer>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(reader.result as ArrayBuffer); reader.onerror = reject; reader.readAsArrayBuffer(value) }) })
  return value
}
async function settleHash(predicate: () => unknown) { await vi.waitFor(() => expect(predicate()).toBeTruthy(), { timeout: 1500, interval: 20 }); await flushPromises() }
function catalog(kind: 'report' | 'source' | 'document') {
  const templates = [{ id: kind === 'report' ? 'SNAPSHOT_CODE_REVIEW' : kind === 'source' ? 'UNIT_TEST_DEVELOPMENT' : 'REQUIREMENT_CODE_REVIEW', version: '1', title: `W0${kind}`, description: '真实分支夹具', category: '测试', inputs: { branch: kind !== 'source', dates: false, documents: kind === 'document', sourcePath: kind === 'source', testOutputPath: kind === 'source', documentOutputPath: false, extensions: ['md'], maxFiles: 10, maxFileMiB: 20, maxTotalMiB: 50 } }]
  return { templates, dimensions: [], defaultStartDate: '2026-09-01', defaultEndDate: '2026-09-14' } as unknown as TemplateTaskCatalog
}
beforeEach(() => {
  vi.useFakeTimers(); sessionStorage.clear(); streams.length = 0
  vi.stubGlobal('crypto', webcrypto); vi.stubGlobal('EventSource', class {})
  mock('templateProjects').mockResolvedValue({ items: [], facets: {} }); mock('templateProject').mockResolvedValue({ id: 'p', name: '项目', createdAt: 'now', documentPath: null })
  mock('templateBranches').mockResolvedValue({ page: { items: [branch], facets: {}, nextCursor: null }, defaultBranch: branch, defaultBranchId: branch.id, remoteAvailable: true })
  mock('sourceTemplate').mockImplementation(async (id: string) => sourceRun(id)); mock('documentTemplate').mockImplementation(async (id: string) => documentRun(id))
  mock('sourceEvents').mockImplementation(stream); mock('documentEvents').mockImplementation(stream)
  for (const name of ['documentSections', 'documentSection', 'answerDocumentRequirements', 'documentSupplementOptions', 'uploadDocumentSupplement'] as const) mock(name)
})
afterEach(() => { roots.splice(0).forEach(root => { root.unmount() }); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); sessionStorage.clear() })

describe('W0 B3 creation/run identity and navigation', () => {
  for (const kind of ['report', 'source', 'document'] as const) it(`B3.1 ${kind}: real create in flight must block route leave; disabled inputs cannot be mutated`, async () => {
    await creationUiW0Contract(kind, 'inflight', { catalog, file, proof })
  })
  it('B3.1 document: hashing really active before any POST must block leave', async () => {
    await creationUiW0Contract('document', 'hash', { catalog, file, proof })
  })
  for (const kind of ['report', 'source', 'document'] as const) it(`B3.1 ${kind}: unknown receipt cannot replace original intent via editable UI`, async () => {
    await creationUiW0Contract(kind, 'unknown', { catalog, file, proof })
  })
  for (const phase of ['inflight', 'unknown'] as const) it(`B3.1 report/start-${phase}: accepted Task identity blocks leave and survives real editable draft`, async () => {
    await creationUiW0Contract('report', phase === 'inflight' ? 'start-inflight' : 'start-unknown', { catalog, file, proof })
  })
  for (const kind of ['source', 'document'] as const) it(`B3.2 ${kind}: read-only owner without local command may leave and closes SSE without stop POST`, async () => {
    mock(kind === 'source' ? 'sourceTemplateCommand' : 'documentTemplateCommand')
    await templateRunReadonlyLeaveW0Contract(kind, { proof })
  })
  for (const kind of ['source', 'document'] as const) for (const phase of ['inflight', 'unknown'] as const) it(`B3.2 ${kind}/${phase}: same-record and exit navigation preserve pending scope`, async () => {
    mock(kind === 'source' ? 'sourceTemplateCommand' : 'documentTemplateCommand')
    await templateRunNavigationW0Contract(kind, phase, { proof })
  })
  for (const kind of ['source', 'document'] as const) for (const action of (kind === 'source' ? ['cancel', 'retry'] : ['resume', 'cancel'])) it(`B3.3 ${kind}/${action}: refresh version and batch selection must not mint a new unknown command`, async () => {
    mock(kind === 'source' ? 'sourceTemplateCommand' : 'documentTemplateCommand')
    if (kind === 'source' && action === 'retry') mock('sourceBatches').mockResolvedValue({ items: [{ id: 'batch-a', ordinal: 0, attempt: 1, state: 'FAILED', retryable: true }, { id: 'batch-b', ordinal: 1, attempt: 1, state: 'FAILED', retryable: true }], nextCursor: null })
    await templateRunIdentityW0Contract(kind, action as 'cancel' | 'retry' | 'resume', { source: sourceRun, document: documentRun, proof })
  })
  for (const kind of ['report', 'source', 'document'] as const) it(`B3.4 ${kind}: denied get/set storage retains in-memory original identity and blocks leave`, async () => {
    await creationUiW0Contract(kind, 'storage', { catalog, file, proof })
  })
  it('B3.4 document: by-request 404 remains unknown; actual File bytes/order cannot replace original operation', async () => {
    vi.useRealTimers()
    const write = mock('createDocumentTemplate').mockRejectedValue(new Error('未知回执')); const read = mock('documentTemplateRequest').mockRejectedValue(new ApiError('未找到原请求', 404))
    const files = [file('字节一', 'one.md'), file('字节二', 'two.md')]; let store = createDocumentCreationController()
    const first = store.start(documentInput, files).catch(() => undefined); await settleHash(() => write.mock.calls.length === 1); await first
    const original = clone(write.mock.calls[0]?.[0]); const metadata = sessionStorage.getItem('loopper.document-template-upload.v1')!
    expect(metadata).not.toContain('字节一'); store = createDocumentCreationController(); await store.restore(); expect(read).toHaveBeenCalledWith(original.requestKey); expect(write).toHaveBeenCalledTimes(1)
    for (const selected of [[file('字节一', 'one.md'), file('字节二', 'two.md')], [file('不同字节', 'one.md'), file('字节二', 'two.md')], [files[1]!, files[0]!]]) { const next = store.start(documentInput, selected).catch(() => undefined); await settleHash(() => !store.getSnapshot().busy); await next }
    const uploads = await Promise.all(write.mock.calls.map(async call => Promise.all((call[1] as File[]).map(async selected => ({ name: selected.name, sha256: createHash('sha256').update(new Uint8Array(await selected.arrayBuffer())).digest('hex'), sameOriginalInstance: files.includes(selected) })))))
    proof('B3.4/document/bytes-order', { requests: write.mock.calls.map(c => c[0]), uploads, metadataOnly: JSON.parse(metadata), scope: 'external store negative control; UI edit path covered B3.1', byRequestStatus: 404, knownAccepted: false, allowedAlternative: 'reject changed input without POST OR resume frozen original bytes/order' })
    // Same-byte reselect after reload uses new File objects, but the endpoint identity remains original.
    expect(write.mock.calls.length).toBeGreaterThanOrEqual(2)
    for (const call of write.mock.calls) expect.soft(call[0]).toEqual(original)
    const originalIdentity = uploads[0]!.map(({ name, sha256 }) => ({ name, sha256 }))
    for (const upload of uploads) expect.soft(upload.map(({ name, sha256 }) => ({ name, sha256 }))).toEqual(originalIdentity)
    // No extra call is required for changed bytes/order: rejecting that draft is a valid safety outcome.

  })
  it('B3.4 document: accepted by-request receipt performs reads only and identifies original run', async () => {
    const write = mock('createDocumentTemplate').mockRejectedValue(new Error('未知原创建回执')); const store = createDocumentCreationController()
    const pending = store.start(documentInput, [file('原字节')]).catch(() => undefined); await settleHash(() => !store.getSnapshot().busy); await pending
    const original = clone(write.mock.calls[0]?.[0]); const read = mock('documentTemplateRequest').mockResolvedValue(documentRun('accepted-original'))
    const restored = createDocumentCreationController(); await restored.restore()
    proof('B3.4/document/accepted-read', { requestKey: original.requestKey, lookup: read.mock.calls, restoredId: restored.getSnapshot().knownId, writes: write.mock.calls.length, knownAccepted: true, filesPersisted: false })
    expect(read).toHaveBeenCalledWith(original.requestKey); expect(restored.getSnapshot().knownId).toBe('accepted-original'); expect(write).toHaveBeenCalledTimes(1)
    // Accepted GET closes the UNKNOWN branch: no changed-draft retry is forced here.
  })
})

describe('W0 B4 document cancellation scope', () => {
  it('B4.1 pending confirm A followed by real route B must never POST stale cancellation', async () => {
    mock('documentTemplateCommand')
    await templateCancelScopeW0Contract({ proof })
  })
  it('B4.1 reject real modal leaves original A and issues zero writes', async () => {
    const write = mock('documentTemplateCommand')
    const page = await routeRoot('/template-tasks/document-runs/A'); await button(page.view, '取消任务'); await modal(page.view,false)
    expect(write).not.toHaveBeenCalled(); expect(page.router.currentRoute.value.path).toBe('/template-tasks/document-runs/A'); expect(state(page.view).run.id).toBe('A')
  })
  it('B4.1 current modal confirm submits exactly once with original A CAS', async () => {
    const write = mock('documentTemplateCommand').mockResolvedValue(documentRun('A', 4))
    const page = await routeRoot('/template-tasks/document-runs/A'); await button(page.view, '取消任务'); await modal(page.view,true)
    expect(write).toHaveBeenCalledTimes(1); expect(write.mock.calls[0]).toEqual(['A', 'cancel', expect.objectContaining({ expectedVersion: 3, requestKey: expect.any(String) })]); proof('B4.1/current-modal', write.mock.calls)
  })
  for (const outcome of ['success', 'error'] as const) it(`B4.2 ${outcome}: already-sent A POST after forced retirement cannot pollute new B owner`, async () => {
    const pending = deferred<any>(); const write = mock('documentTemplateCommand').mockReturnValue(pending.promise)
    const a = await routeRoot('/template-tasks/document-runs/A'); await button(a.view, '取消任务'); await modal(a.view,true); expect(write).toHaveBeenCalledTimes(1)
    a.root.unmount(); const b = await routeRoot('/template-tasks/document-runs/B'); const before = { run: clone(state(b.view).run), error: state(b.view).error, acting: state(b.view).acting }
    if (outcome === 'success') pending.resolve(documentRun('A', 4)); else pending.reject(new Error('A late error')); await flushPromises()
    proof(`B4.2/${outcome}`, { forcedRetirement: true, original: write.mock.calls[0], route: b.router.currentRoute.value.path, current: state(b.view).run.id })
    expect(state(b.view).run).toEqual(before.run); expect(state(b.view).error).toBe(before.error); expect(state(b.view).acting).toBe(before.acting); expect(write).toHaveBeenCalledTimes(1)
  })
})
const historyItem = (id: string) => ({ id, projectId: 'p', projectName: '项目', state: 'WAITING_INPUT' as const, workflowPhase: 'FAILED' as const, createdAt: '2026-09-01', updatedAt: '2026-09-01', draftId: `draft-${id}`, draftStatus: 'DRAFT_READY' as const, goal: `设计${id}`, archived: false, resumable: true, stopRetryAvailable: false })
const historyPage = (id: string) => ({ items: [historyItem(id)], facets: { ARCHIVED_TOTAL: id === 'B' ? 7 : 1 }, nextCursor: `${id}-cursor` })
const record = (id: string) => ({ taskId: id, taskTitle: `任务${id}`, projectName: `项目${id}`, draft: { id: `draft${id}`, status: 'CONFIRMED', updatedAt: '2026-09-01', spec: { projectId: 'p', goal: `冻结${id}`, stages: [], limits: {} } }, frozenAttachments: [{ id: 'same-file', filename: 'contract.md', mediaType: 'text/markdown', sizeBytes: 5, sha256: `hash${id}`, scopeKey: 'REQUIREMENT', extractorId: 'MARKDOWN', frozenAt: '2026-09-01' }] } as unknown as TaskDesignHistory)
const section = { fileId: 'file', ordinal: 0, title: '章节', characters: 5, sha256: 'sectionhash' }
const sectionPage = { items: [section], nextOffset: null }
const withFile = (id = 'A') => ({ ...documentRun(id), files: [{ id: 'file', filename: '需求.md', format: 'MARKDOWN', sizeBytes: 12, sha256: 'filehash', representationSha256: 'representation-hash', parserVersion: 'markdown-v1', sectionCount: 1, limitations: [] }] } as DocumentTemplateOverview)

describe('W0 B8 history and child retirement', () => {
  it('B8.1 search B resolves before A: list/facets/cursor must stay in B query', async () => {
    foundationDOM(); mock('listDesignerHistoryPage')
    await designerHistoryW0Contract('query', { read: api.listDesignerHistoryPage, page: historyPage, proof })
  })
  it('B8.1 old cursor append after new filter cannot append A into B', async () => {
    foundationDOM(); mock('listDesignerHistoryPage')
    await designerHistoryW0Contract('cursor', { read: api.listDesignerHistoryPage, page: historyPage, proof })
  })
  for (const outcome of ['success', 'error'] as const) it(`B8.1 ${outcome}: unmount prevents late retired-owner writes and debounce revival`, async () => {
    foundationDOM(); mock('listDesignerHistoryPage')
    await designerHistoryW0Contract(`retired-${outcome}`, { read: api.listDesignerHistoryPage, page: historyPage, proof })
  })
  it('B8.2 frozen record A arriving after real route B cannot overwrite B', async () => {
    foundationDOM(); mock('getTaskDesignHistory')
    await historyScopeW0Contract('record', { record, proof })
  })
  it('B8.2 attachment A late with same file ID cannot populate B cache', async () => {
    foundationDOM(); mock('getTaskDesignHistory'); mock('getTaskDesignAttachmentPreview')
    await historyScopeW0Contract('attachment-late', { record, proof })
  })
  it('B8.2 cached A same attachment ID must be invalidated when route becomes B', async () => {
    foundationDOM(); mock('getTaskDesignHistory'); mock('getTaskDesignAttachmentPreview')
    await historyScopeW0Contract('attachment-cache', { record, proof })
  })
  it('B8.2 retired record error must not clear old busy or add error', async () => {
    foundationDOM(); mock('getTaskDesignHistory')
    await historyScopeW0Contract('retired-error', { record, proof })
  })
  for (const kind of ['directory', 'body'] as const) it(`B8.3 sources/${kind}: late response after root unmount cannot mutate retired refs`, async () => {
    await templateSourcesRetirementW0Contract(kind, { run: withFile(), page: sectionPage, section: { ...section, content: 'A原文' }, proof })
  })
  it('B8.3 clarification sent POST after forced root retirement cannot emit updated or clear draft', async () => {
    await templateClarificationRetirementW0Contract({ run: documentRun(), proof })
  })
  it('B8.3 supplement options read after unmount cannot open retired form', async () => {
    await templateSupplementRetirementW0Contract('options', { run: documentRun(), proof })
  })
  it('B8.3 supplement sent upload after forced root retirement cannot emit updated', async () => {
    await templateSupplementRetirementW0Contract('upload', { run: documentRun(), file: file(), proof })
  })
  it('B8.3 existing prop scope guards reject old child callbacks without unmount', async () => {
    await templateClarificationPropScopeW0Contract({run:documentRun(),proof})
  })
})
