/** W0 frozen contract probes. W2 retargets only B8.1 to the actual React production page; other waves remain red. */
import { createHash, webcrypto } from 'node:crypto'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, type Component } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { api, ApiError } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import { useDocumentTemplateStore } from '@/stores/documentTemplateStore'
import TemplateTasksView from '@/views/TemplateTasksView.vue'
import SourceTemplateView from '@/views/SourceTemplateView.vue'
import DocumentTemplateView from '@/views/DocumentTemplateView.vue'
import DesignerHistoryView from '@/views/DesignerHistoryView.vue'
import { foundationDOM } from '@/pages/w2/workflow/page.test-support'
import { designerHistoryW0Contract } from '@/pages/w2/workflow/designer-history-w0-contract'
import TaskDesignHistoryView from '@/views/TaskDesignHistoryView.vue'
import DocumentSourcesPanel from '@/components/DocumentSourcesPanel.vue'
import DocumentClarificationForm from '@/components/DocumentClarificationForm.vue'
import DocumentSupplementForm from '@/components/DocumentSupplementForm.vue'
import type { DocumentTemplateOverview, SourceTemplateOverview, TaskDesignHistory, TemplateTaskCatalog } from '@/types/domain'

function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) }
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function mock(name: keyof typeof api) { return vi.spyOn(api, name) as Mock }
function proof(subcase: string, evidence: unknown) { console.info('W0_IDENTITY', JSON.stringify({ subcase, evidence })) }
const roots: VueWrapper[] = []
const streams: { close: ReturnType<typeof vi.fn> }[] = []
function stream() { const value = { close: vi.fn(), addEventListener: vi.fn(), onopen: null, onerror: null }; streams.push(value); return value }
const header = { template: '<header><slot name="actions" /></header>' }
const stubs = { Icon: true, PageHeader: header, MarkdownDocument: { props: ['content'], template: '<pre>{{ content }}</pre>' }, SourceCoveragePanel: true, SourceArtifactsPanel: true, DocumentRequirementsPanel: true, DocumentReportsPanel: true, TemplateBatchRecoveryPanel: true, DesignerDiscussionHistory: true, TemplateTaskProgressPanel: true }
function state(wrapper: VueWrapper) { return (wrapper.vm.$ as unknown as { setupState: Record<string, any> }).setupState }
async function button(wrapper: VueWrapper, label: string) { const node = wrapper.findAll('button').find(b => b.text() === label); expect(node, `reachable button ${label}`).toBeDefined(); await node!.trigger('click'); await flushPromises() }
async function routeRoot(component: Component, path: string, extraStubs = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/template-tasks', component: TemplateTasksView }, { path: '/template-tasks/source-runs/:id', component: SourceTemplateView },
    { path: '/template-tasks/document-runs/:id', component: DocumentTemplateView }, { path: '/designs', component: DesignerHistoryView },
    { path: '/tasks/:id/design', component: TaskDesignHistoryView }, { path: '/exit', component: { template: '<p>安全离开目标</p>' } },
    { path: '/tasks/:id', component: { template: '<p>任务检视</p>' } }, { path: '/tasks', component: { template: '<p>任务列表</p>' } },
  ] })
  await router.push(path); await router.isReady()
  const root = mount(defineComponent({ setup: () => () => h(RouterView) }), { global: { plugins: [ElementPlus, router], stubs: { ...stubs, DocumentSourcesPanel: true, DocumentSupplementForm: true, ...extraStubs } } })
  roots.push(root); await flushPromises(); return { root, router, view: root.findComponent(component) }
}
function direct(component: Component, props: Record<string, unknown>) { const wrapper = mount(component, { props, global: { plugins: [ElementPlus], stubs } }); roots.push(wrapper); return wrapper }
const branch = { id: 'local:main', label: 'main', ref: 'refs/heads/main', remote: null }
const sourceRun = (id = 'A', version = 3): SourceTemplateOverview => ({ id, projectId: 'p', templateId: 'UNIT_TEST_DEVELOPMENT', templateVersion: '1', title: `源码${id}`, state: 'PENDING_START', version, createdAt: 'now', updatedAt: 'now', archived: false, sourcePath: 'src/main', testOutputPath: null, documentPath: null, requirements: '', snapshot: null, designerId: null, taskId: null, taskState: null, waitingReasonCode: null, waitingMessage: null, coverage: [], progress: [], canResume: false, canArchive: false, testProfile: null })
const documentRun = (id = 'A', version = 3): DocumentTemplateOverview => ({ id, projectId: 'p', templateId: 'REQUIREMENT_CODE_REVIEW', templateVersion: '1', title: `文档${id}`, state: 'ASSESSING', waitingReasonCode: null, waitingMessage: null, designerId: null, taskId: null, requirementRevision: 1, version, createdAt: 'created', updatedAt: 'updated', canCancel: true, canResume: true, archived: false, uploadReady: true, snapshotSha: 'frozen-sha', files: [], progress: { attempts: 2, validated: 1, active: 1, stopped: 0, requirements: 1, reports: 0, revision: 3 } })
const documentInput = { templateId: 'REQUIREMENT_CODE_REVIEW', templateVersion: '1', projectId: 'p', branchId: branch.id }
function file(content = '原始文档', name = '需求.md') {
  const value = new File([content], name, { type: 'text/markdown' })
  Object.defineProperty(value, 'arrayBuffer', { configurable: true, value: () => new Promise<ArrayBuffer>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(reader.result as ArrayBuffer); reader.onerror = reject; reader.readAsArrayBuffer(value) }) })
  return value
}
async function choose(wrapper: VueWrapper, files: File[]) { const input = wrapper.get('input[type=file]'); expect(input.attributes('disabled')).toBeUndefined(); Object.defineProperty(input.element, 'files', { configurable: true, value: files }); await input.trigger('change'); await flushPromises() }
async function settleHash(predicate: () => unknown) { await vi.waitFor(() => expect(predicate()).toBeTruthy(), { timeout: 1500, interval: 20 }); await flushPromises() }
function catalog(kind: 'report' | 'source' | 'document') {
  const templates = [{ id: kind === 'report' ? 'SNAPSHOT_CODE_REVIEW' : kind === 'source' ? 'UNIT_TEST_DEVELOPMENT' : 'REQUIREMENT_CODE_REVIEW', version: '1', title: `W0${kind}`, description: '真实分支夹具', category: '测试', inputs: { branch: kind !== 'source', dates: false, documents: kind === 'document', sourcePath: kind === 'source', testOutputPath: kind === 'source', documentOutputPath: false, extensions: ['md'], maxFiles: 10, maxFileMiB: 20, maxTotalMiB: 50 } }]
  return { templates, dimensions: [], defaultStartDate: '2026-09-01', defaultEndDate: '2026-09-14' } as unknown as TemplateTaskCatalog
}
async function createPage(kind: 'report' | 'source' | 'document') {
  mock('templateCatalog').mockResolvedValue(catalog(kind))
  const page = await routeRoot(TemplateTasksView, '/template-tasks?projectId=p')
  if (kind === 'source') {
    await page.view.get('input[aria-label="源码路径"]').setValue('src/main')
    mock('sourcePreview').mockResolvedValue({ sourcePath: 'src/main', manifestSha256: 'sha', targetCount: 1, excludedCount: 0, moduleCount: 1, truncated: false, files: [], testProfile: { manifestSha256: 'sha', modules: [] }, configurationProblem: null })
    await button(page.view, '检查处理范围')
  }
  if (kind === 'document') await choose(page.view, [file()])
  return page
}
beforeEach(() => {
  vi.useFakeTimers(); sessionStorage.clear(); setActivePinia(createPinia()); streams.length = 0
  vi.stubGlobal('crypto', webcrypto); vi.stubGlobal('EventSource', class {})
  mock('templateProjects').mockResolvedValue({ items: [], facets: {} }); mock('templateProject').mockResolvedValue({ id: 'p', name: '项目', createdAt: 'now', documentPath: null })
  mock('templateBranches').mockResolvedValue({ page: { items: [branch], facets: {}, nextCursor: null }, defaultBranch: branch, defaultBranchId: branch.id, remoteAvailable: true })
  mock('sourceTemplate').mockImplementation(async (id: string) => sourceRun(id)); mock('documentTemplate').mockImplementation(async (id: string) => documentRun(id))
  mock('sourceEvents').mockImplementation(stream); mock('documentEvents').mockImplementation(stream)
  useTaskStore().projects = [{ id: 'p', name: '项目', createdAt: 'now' }] as any
})
afterEach(() => { roots.splice(0).forEach(root => { if (root.exists()) root.unmount() }); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); sessionStorage.clear() })

describe('W0 B3 creation/run identity and navigation', () => {
  for (const kind of ['report', 'source', 'document'] as const) it(`B3.1 ${kind}: real create in flight must block route leave; disabled inputs cannot be mutated`, async () => {
    const pending = deferred<any>(); const write = mock(kind === 'report' ? 'createTemplateTask' : kind === 'source' ? 'createSourceTemplate' : 'createDocumentTemplate').mockReturnValue(pending.promise)
    const page = await createPage(kind); await page.view.get('form').trigger('submit'); await flushPromises()
    if (kind === 'document') await settleHash(() => write.mock.calls.length)
    expect(write).toHaveBeenCalledTimes(1)
    expect(page.view.get(kind === 'source' ? 'input[aria-label="源码路径"]' : kind === 'document' ? 'input[type=file]' : 'input[aria-label="项目"]').attributes('disabled')).toBeDefined()
    await page.router.push('/exit'); await flushPromises()
    proof(`B3.1/${kind}/create`, { route: page.router.currentRoute.value.fullPath, request: write.mock.calls[0]?.[0], calls: write.mock.calls.length, inputLocked: true })
    expect.soft(page.router.currentRoute.value.path).toBe('/template-tasks')
    pending.reject(new Error('确定性未知回执')); await flushPromises(); expect(write).toHaveBeenCalledTimes(1)
  })
  it('B3.1 document: hashing really active before any POST must block leave', async () => {
    const read = deferred<ArrayBuffer>(); const input = file(); Object.defineProperty(input, 'arrayBuffer', { configurable: true, value: () => read.promise })
    const write = mock('createDocumentTemplate'); const page = await createPage('document'); await choose(page.view, [input]); await page.view.get('form').trigger('submit'); await flushPromises()
    expect(useDocumentTemplateStore().submitting).toBe(true); expect(write).not.toHaveBeenCalled(); await page.router.push('/exit'); await flushPromises()
    proof('B3.1/document/hash', { route: page.router.currentRoute.value.path, submitting: useDocumentTemplateStore().submitting, writes: write.mock.calls.length })
    expect.soft(page.router.currentRoute.value.path).toBe('/template-tasks'); read.reject(new Error('测试终止本次读取')); await flushPromises()
  })
  for (const kind of ['report', 'source', 'document'] as const) it(`B3.1 ${kind}: unknown receipt cannot replace original intent via editable UI`, async () => {
    const write = mock(kind === 'report' ? 'createTemplateTask' : kind === 'source' ? 'createSourceTemplate' : 'createDocumentTemplate').mockRejectedValue(new Error('回执丢失'))
    const page = await createPage(kind); await page.view.get('form').trigger('submit'); await flushPromises(); if (kind === 'document') await settleHash(() => write.mock.calls.length && !useDocumentTemplateStore().submitting)
    const original = clone(write.mock.calls[0]?.[0]); expect(original.requestKey).toBeTruthy()
    if (kind === 'source') { const input = page.view.get('input[aria-label="源码路径"]'); expect(input.attributes('disabled')).toBeUndefined(); await input.setValue('src/changed'); await button(page.view, '检查处理范围') }
    else if (kind === 'document') await choose(page.view, [file('另一份实际字节')])
    else { const input = page.view.get('input[aria-label="文档生成路径"]'); expect(input.attributes('disabled')).toBeUndefined(); await input.setValue('/changed') }
    await page.view.get('form').trigger('submit'); await flushPromises(); if (kind === 'document') await settleHash(() => write.mock.calls.length === 2)
    await page.router.push('/exit'); await flushPromises()
    proof(`B3.1/${kind}/unknown-new-intent`, { original, retry: write.mock.calls[1]?.[0], route: page.router.currentRoute.value.path })
    expect.soft(write.mock.calls[1]?.[0]).toEqual(original); expect.soft(page.router.currentRoute.value.path).toBe('/template-tasks')
  })
  for (const phase of ['inflight', 'unknown'] as const) it(`B3.1 report/start-${phase}: accepted Task identity blocks leave and survives real editable draft`, async () => {
    const create = mock('createTemplateTask').mockResolvedValue({ id: 'task-accepted' }); const receipt = deferred<any>(); const start = mock('startTemplateTask').mockReturnValue(receipt.promise)
    const page = await createPage('report'); await page.view.get('form').trigger('submit'); await flushPromises(); expect(create).toHaveBeenCalledTimes(1); expect(start).toHaveBeenCalledWith('task-accepted')
    if (phase === 'unknown') {
      receipt.reject(new Error('start回执未知')); await flushPromises(); start.mockRejectedValue(new Error('原操作start未知'))
      const input = page.view.get('input[aria-label="文档生成路径"]'); expect(input.attributes('disabled')).toBeUndefined(); const original = (input.element as HTMLInputElement).value
      await input.setValue('/changed-after-accepted'); await page.view.get('form').trigger('submit'); await flushPromises()
      await input.setValue(original); await page.view.get('form').trigger('submit'); await flushPromises()
    }
    await page.router.push('/exit'); await flushPromises(); proof(`B3.1/report/start-${phase}`, { create: create.mock.calls, start: start.mock.calls, route: page.router.currentRoute.value.path, knownTaskId: 'task-accepted' })
    expect.soft(create).toHaveBeenCalledTimes(1); expect.soft(start.mock.calls.every(call => call[0] === 'task-accepted')).toBe(true); expect.soft(page.router.currentRoute.value.path).toBe('/template-tasks')
    if (phase === 'inflight') { receipt.reject(new Error('late start unknown')); await flushPromises() }
  })
  for (const kind of ['source', 'document'] as const) it(`B3.2 ${kind}: read-only owner without local command may leave and closes SSE without stop POST`, async () => {
    const write = mock(kind === 'source' ? 'sourceTemplateCommand' : 'documentTemplateCommand')
    const page = await routeRoot(kind === 'source' ? SourceTemplateView : DocumentTemplateView, `/template-tasks/${kind}-runs/A`); expect(streams).toHaveLength(1)
    await page.router.push('/exit'); await flushPromises(); proof(`B3.2/${kind}/read-only-leave`, { route: page.router.currentRoute.value.path, closed: streams[0]!.close.mock.calls.length, writes: write.mock.calls.length })
    expect(page.router.currentRoute.value.path).toBe('/exit'); expect(streams[0]!.close).toHaveBeenCalledTimes(1); expect(write).not.toHaveBeenCalled()
  })
  for (const kind of ['source', 'document'] as const) for (const phase of ['inflight', 'unknown'] as const) it(`B3.2 ${kind}/${phase}: same-record and exit navigation preserve pending scope`, async () => {
    const pending = deferred<any>(); const write = mock(kind === 'source' ? 'sourceTemplateCommand' : 'documentTemplateCommand').mockReturnValue(pending.promise)
    const component = kind === 'source' ? SourceTemplateView : DocumentTemplateView; const path = `/template-tasks/${kind}-runs/A`
    const page = await routeRoot(component, path); await button(page.view, kind === 'source' ? '开始执行' : '从冻结输入恢复')
    expect(write).toHaveBeenCalledTimes(1)
    if (phase === 'unknown') { pending.reject(new Error('未知回执')); await flushPromises() }
    await page.router.push(`/template-tasks/${kind}-runs/B`); await flushPromises()
    proof(`B3.2/${kind}/${phase}`, { original: write.mock.calls[0], route: page.router.currentRoute.value.path, streamsClosed: streams.map(s => s.close.mock.calls.length) })
    expect.soft(page.router.currentRoute.value.path).toBe(path)
    await page.router.push('/exit'); await flushPromises(); expect.soft(page.router.currentRoute.value.path).toBe(path)
    if (phase === 'inflight') { pending.reject(new Error('retired owner late error')); await flushPromises() }
    expect(write).toHaveBeenCalledTimes(1)
  })
  for (const kind of ['source', 'document'] as const) for (const action of (kind === 'source' ? ['cancel', 'retry'] : ['resume', 'cancel'])) it(`B3.3 ${kind}/${action}: refresh version and batch selection must not mint a new unknown command`, async () => {
    const write = mock(kind === 'source' ? 'sourceTemplateCommand' : 'documentTemplateCommand').mockRejectedValue(new Error('未知回执'))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    if (kind === 'source' && action === 'retry') {
      vi.mocked(api.sourceTemplate).mockResolvedValue({ ...sourceRun(), templateId: 'DETAILED_DESIGN_WRITING', canResume: true })
      mock('sourceBatches').mockResolvedValue({ items: [{ id: 'batch-a', ordinal: 0, attempt: 1, state: 'FAILED', retryable: true }, { id: 'batch-b', ordinal: 1, attempt: 1, state: 'FAILED', retryable: true }], nextCursor: null })
    }
    const page = await routeRoot(kind === 'source' ? SourceTemplateView : DocumentTemplateView, `/template-tasks/${kind}-runs/A`)
    const label = action === 'retry' ? '重试所选失败批次' : action === 'resume' ? '从冻结输入恢复' : '取消任务'
    if (action === 'retry') await page.view.findAll('input[type=checkbox]')[0]!.setValue(true)
    await button(page.view, label); const original = clone(write.mock.calls[0]); expect(original).toBeDefined()
    if (kind === 'source') vi.mocked(api.sourceTemplate).mockResolvedValue({ ...sourceRun('A', 4), templateId: action === 'retry' ? 'DETAILED_DESIGN_WRITING' : 'UNIT_TEST_DEVELOPMENT', canResume: action === 'retry' })
    else vi.mocked(api.documentTemplate).mockResolvedValue(documentRun('A', 4))
    await button(page.view, '刷新')
    if (action === 'retry') await page.view.findAll('input[type=checkbox]')[1]!.setValue(true)
    await button(page.view, label)
    proof(`B3.3/${kind}/${action}`, { original, retry: write.mock.calls[1] })
    expect(write.mock.calls[1]).toEqual(original)
  })
  for (const kind of ['report', 'source', 'document'] as const) it(`B3.4 ${kind}: denied get/set storage retains in-memory original identity and blocks leave`, async () => {
    const storage = { getItem: vi.fn(() => { throw new Error('storage get denied') }), setItem: vi.fn(() => { throw new Error('storage set denied') }), removeItem: vi.fn(() => { throw new Error('storage remove denied') }) }; vi.stubGlobal('sessionStorage', storage)
    const write = mock(kind === 'report' ? 'createTemplateTask' : kind === 'source' ? 'createSourceTemplate' : 'createDocumentTemplate').mockRejectedValue(new Error('未知回执'))
    const page = await createPage(kind); await page.view.get('form').trigger('submit'); await flushPromises(); if (kind === 'document') await settleHash(() => write.mock.calls.length && !useDocumentTemplateStore().submitting)
    await page.view.get('form').trigger('submit'); await flushPromises(); if (kind === 'document') await settleHash(() => write.mock.calls.length === 2)
    expect(write.mock.calls[1]?.[0]).toEqual(write.mock.calls[0]?.[0]); await page.router.push('/exit'); await flushPromises()
    proof(`B3.4/${kind}/storage`, { requests: write.mock.calls.map(c => c[0]), storageCalls: { get: storage.getItem.mock.calls.length, set: storage.setItem.mock.calls.length, remove: storage.removeItem.mock.calls.length }, removeReachability: 'no removal path in these stores', route: page.router.currentRoute.value.path })
    expect(page.router.currentRoute.value.path).toBe('/template-tasks')
  })
  it('B3.4 document: by-request 404 remains unknown; actual File bytes/order cannot replace original operation', async () => {
    vi.useRealTimers()
    const write = mock('createDocumentTemplate').mockRejectedValue(new Error('未知回执')); const read = mock('documentTemplateRequest').mockRejectedValue(new ApiError('未找到原请求', 404))
    const files = [file('字节一', 'one.md'), file('字节二', 'two.md')]; let store = useDocumentTemplateStore()
    const first = store.start(documentInput, files).catch(() => undefined); await settleHash(() => write.mock.calls.length === 1); await first
    const original = clone(write.mock.calls[0]?.[0]); const metadata = sessionStorage.getItem('loopper.document-template-upload.v1')!
    expect(metadata).not.toContain('字节一'); setActivePinia(createPinia()); store = useDocumentTemplateStore(); await store.restore(); expect(read).toHaveBeenCalledWith(original.requestKey); expect(write).toHaveBeenCalledTimes(1)
    for (const selected of [[file('字节一', 'one.md'), file('字节二', 'two.md')], [file('不同字节', 'one.md'), file('字节二', 'two.md')], [files[1]!, files[0]!]]) { const next = store.start(documentInput, selected).catch(() => undefined); await settleHash(() => !store.submitting); await next }
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
    const write = mock('createDocumentTemplate').mockRejectedValue(new Error('未知原创建回执')); const store = useDocumentTemplateStore()
    const pending = store.start(documentInput, [file('原字节')]).catch(() => undefined); await settleHash(() => !store.submitting); await pending
    const original = clone(write.mock.calls[0]?.[0]); const read = mock('documentTemplateRequest').mockResolvedValue(documentRun('accepted-original'))
    setActivePinia(createPinia()); const restored = useDocumentTemplateStore(); await restored.restore()
    proof('B3.4/document/accepted-read', { requestKey: original.requestKey, lookup: read.mock.calls, restoredId: restored.previousRun?.id, writes: write.mock.calls.length, knownAccepted: true, filesPersisted: false })
    expect(read).toHaveBeenCalledWith(original.requestKey); expect(restored.previousRun?.id).toBe('accepted-original'); expect(write).toHaveBeenCalledTimes(1)
    // Accepted GET closes the UNKNOWN branch: no changed-draft retry is forced here.
  })
})

describe('W0 B4 document cancellation scope', () => {
  it('B4.1 pending confirm A followed by real route B must never POST stale cancellation', async () => {
    const confirm = deferred<any>(); vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(confirm.promise)
    const write = mock('documentTemplateCommand').mockResolvedValue(documentRun('A', 4))
    const page = await routeRoot(DocumentTemplateView, '/template-tasks/document-runs/A'); await button(page.view, '取消任务')
    expect(write).not.toHaveBeenCalled(); await page.router.push('/template-tasks/document-runs/B'); await flushPromises(); expect(state(page.view).run.id).toBe('B')
    confirm.resolve('confirm'); await flushPromises()
    proof('B4.1/stale-modal', { route: page.router.currentRoute.value.path, writes: write.mock.calls, visibleRun: state(page.view).run.id, acting: state(page.view).acting })
    expect.soft(write).not.toHaveBeenCalled(); expect.soft(state(page.view).run.id).toBe('B'); expect.soft(state(page.view).error).toBe('')
  })
  it('B4.1 reject real modal leaves original A and issues zero writes', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel'); const write = mock('documentTemplateCommand')
    const page = await routeRoot(DocumentTemplateView, '/template-tasks/document-runs/A'); await button(page.view, '取消任务')
    expect(write).not.toHaveBeenCalled(); expect(page.router.currentRoute.value.path).toBe('/template-tasks/document-runs/A'); expect(state(page.view).run.id).toBe('A')
  })
  it('B4.1 current modal confirm submits exactly once with original A CAS', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never); const write = mock('documentTemplateCommand').mockResolvedValue(documentRun('A', 4))
    const page = await routeRoot(DocumentTemplateView, '/template-tasks/document-runs/A'); await button(page.view, '取消任务')
    expect(write).toHaveBeenCalledTimes(1); expect(write.mock.calls[0]).toEqual(['A', 'cancel', expect.objectContaining({ expectedVersion: 3, requestKey: expect.any(String) })]); proof('B4.1/current-modal', write.mock.calls)
  })
  for (const outcome of ['success', 'error'] as const) it(`B4.2 ${outcome}: already-sent A POST after forced retirement cannot pollute new B owner`, async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never); const pending = deferred<any>(); const write = mock('documentTemplateCommand').mockReturnValue(pending.promise)
    const a = await routeRoot(DocumentTemplateView, '/template-tasks/document-runs/A'); await button(a.view, '取消任务'); expect(write).toHaveBeenCalledTimes(1)
    a.root.unmount(); const b = await routeRoot(DocumentTemplateView, '/template-tasks/document-runs/B'); const before = { run: clone(state(b.view).run), error: state(b.view).error, acting: state(b.view).acting }
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
    const a = deferred<TaskDesignHistory>(); const read = mock('getTaskDesignHistory').mockReturnValueOnce(a.promise).mockResolvedValue(record('B'))
    const page = await routeRoot(TaskDesignHistoryView, '/tasks/A/design'); await page.router.push('/tasks/B/design'); await flushPromises(); expect(state(page.view).record.taskId).toBe('B'); a.resolve(record('A')); await flushPromises()
    proof('B8.2/record', { requests: read.mock.calls, route: page.router.currentRoute.value.path, record: state(page.view).record.taskId }); expect(state(page.view).record.taskId).toBe('B')
  })
  it('B8.2 attachment A late with same file ID cannot populate B cache', async () => {
    mock('getTaskDesignHistory').mockImplementation(async (id: string) => record(id)); const a = deferred<any>(); const read = mock('getTaskDesignAttachmentPreview').mockReturnValue(a.promise)
    const page = await routeRoot(TaskDesignHistoryView, '/tasks/A/design'); await button(page.view, '安全预览'); await page.router.push('/tasks/B/design'); await flushPromises(); a.resolve({ text: 'A冻结正文' }); await flushPromises()
    proof('B8.2/attachment-late', { requests: read.mock.calls, cache: state(page.view).attachmentPreviews, route: page.router.currentRoute.value.path }); expect(state(page.view).attachmentPreviews['same-file']).toBeUndefined()
  })
  it('B8.2 cached A same attachment ID must be invalidated when route becomes B', async () => {
    mock('getTaskDesignHistory').mockImplementation(async (id: string) => record(id)); const read = mock('getTaskDesignAttachmentPreview').mockImplementation(async (id: string) => ({ text: `${id}冻结正文` }))
    const page = await routeRoot(TaskDesignHistoryView, '/tasks/A/design'); await button(page.view, '安全预览'); expect(state(page.view).attachmentPreviews['same-file']).toBe('A冻结正文')
    await page.router.push('/tasks/B/design'); await flushPromises(); await button(page.view, '安全预览'); proof('B8.2/attachment-cache', { requests: read.mock.calls, cache: state(page.view).attachmentPreviews }); expect(read).toHaveBeenLastCalledWith('B', 'same-file'); expect(state(page.view).attachmentPreviews['same-file']).toBe('B冻结正文')
  })
  it('B8.2 retired record error must not clear old busy or add error', async () => {
    const a = deferred<any>(); mock('getTaskDesignHistory').mockReturnValue(a.promise); const page = await routeRoot(TaskDesignHistoryView, '/tasks/A/design'); const retired = state(page.view); page.root.unmount(); a.reject(new Error('retired attachment owner')); await flushPromises()
    proof('B8.2/retired-error', { loading: retired.loading, error: retired.error }); expect.soft(retired.loading).toBe(true); expect.soft(retired.error).toBe('')
  })
  for (const kind of ['directory', 'body'] as const) it(`B8.3 sources/${kind}: late response after root unmount cannot mutate retired refs`, async () => {
    const a = deferred<any>(); const read = mock(kind === 'directory' ? 'documentSections' : 'documentSection').mockReturnValue(a.promise)
    if (kind === 'body') mock('documentSections').mockResolvedValue(sectionPage)
    const wrapper = direct(DocumentSourcesPanel, { run: withFile() }); await button(wrapper, '读取目录'); if (kind === 'body') await button(wrapper, '读取原文')
    const retired = state(wrapper); const before = clone(kind === 'directory' ? retired.pages : retired.bodies); wrapper.unmount(); a.resolve(kind === 'directory' ? sectionPage : { ...section, content: 'A原文' }); await flushPromises()
    proof(`B8.3/sources/${kind}`, { requests: read.mock.calls, before, after: kind === 'directory' ? retired.pages : retired.bodies, busy: retired.busy })
    expect(kind === 'directory' ? retired.pages : retired.bodies).toEqual(before)
  })
  it('B8.3 clarification sent POST after forced root retirement cannot emit updated or clear draft', async () => {
    const a = deferred<any>(); const write = mock('answerDocumentRequirements').mockReturnValue(a.promise); const wrapper = direct(DocumentClarificationForm, { run: documentRun(), requirementKey: 'REQ-1' })
    await wrapper.get('textarea').setValue('原始回答'); await wrapper.get('form').trigger('submit'); await flushPromises(); expect(wrapper.get('textarea').attributes('disabled')).toBeDefined(); const retired = state(wrapper); wrapper.unmount()
    const next = direct(DocumentClarificationForm, { run: documentRun('B'), requirementKey: 'REQ-1' }); await next.get('textarea').setValue('B草稿'); a.resolve(documentRun('A', 4)); await flushPromises()
    proof('B8.3/clarification', { request: write.mock.calls[0], retiredEmit: wrapper.emitted('updated'), retiredDraft: retired.answer, nextDraft: state(next).answer, forcedRetirement: true })
    expect.soft(wrapper.emitted('updated')).toBeUndefined(); expect.soft(retired.answer).toBe('原始回答'); expect(state(next).answer).toBe('B草稿'); expect(next.emitted('updated')).toBeUndefined()
  })
  it('B8.3 supplement options read after unmount cannot open retired form', async () => {
    const a = deferred<any>(); mock('documentSupplementOptions').mockReturnValue(a.promise); const wrapper = direct(DocumentSupplementForm, { run: documentRun() }); await button(wrapper, '补充需求文档'); const retired = state(wrapper); wrapper.unmount()
    a.resolve({ available: true, message: '补传', request: { requestKey: 'supplement-request-key', expectedVersion: 3, expectedTaskVersion: -1 } }); await flushPromises(); proof('B8.3/supplement-options', { opened: retired.opened, options: retired.options, busy: retired.busy }); expect.soft(retired.opened).toBe(false); expect.soft(retired.options).toBeUndefined(); expect.soft(retired.busy).toBe(true)
  })
  it('B8.3 supplement sent upload after forced root retirement cannot emit updated', async () => {
    mock('documentSupplementOptions').mockResolvedValue({ available: true, message: '补传', request: { requestKey: 'supplement-request-key', expectedVersion: 3, expectedTaskVersion: -1 } }); const a = deferred<any>(); const write = mock('uploadDocumentSupplement').mockReturnValue(a.promise)
    const wrapper = direct(DocumentSupplementForm, { run: documentRun() }); await button(wrapper, '补充需求文档'); await choose(wrapper, [file()]); await button(wrapper, '上传并重新复核需求'); const retired = state(wrapper); expect(wrapper.get('input[type=file]').attributes('disabled')).toBeDefined(); wrapper.unmount(); a.resolve(documentRun('A', 4)); await flushPromises()
    proof('B8.3/supplement-upload', { request: write.mock.calls[0]?.slice(0, 2), retiredEmit: wrapper.emitted('updated'), files: retired.files.map((f: File) => f.name), forcedRetirement: true }); expect.soft(wrapper.emitted('updated')).toBeUndefined(); expect.soft(retired.files).toHaveLength(1)
  })
  it('B8.3 existing prop scope guards reject old child callbacks without unmount', async () => {
    const a = deferred<any>(); mock('answerDocumentRequirements').mockReturnValue(a.promise); const wrapper = direct(DocumentClarificationForm, { run: documentRun(), requirementKey: 'REQ-1' }); await wrapper.get('textarea').setValue('A回答'); await wrapper.get('form').trigger('submit'); await flushPromises(); await wrapper.setProps({ run: documentRun('B') }); await wrapper.get('textarea').setValue('B回答'); a.resolve(documentRun('A', 4)); await flushPromises(); expect(wrapper.emitted('updated')).toBeUndefined(); expect(state(wrapper).answer).toBe('B回答')
  })
})
