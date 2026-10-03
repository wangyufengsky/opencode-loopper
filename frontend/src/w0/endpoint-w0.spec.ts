/** W0 endpoint-specific identity; transport mocks do not prove Java validation. */
import { createHash, webcrypto } from 'node:crypto'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, afterEach, describe, expect, it, vi, type Mock } from 'vitest'
import { api } from '@/api/client'
import { applySkin, skinId } from '@/themes/state'
import { skins } from '@/themes/registry'
import { useTemplateTaskStore } from '@/stores/templateTaskStore'
import { useDocumentTemplateStore } from '@/stores/documentTemplateStore'
import TemplateBatchRecoveryPanel from '@/components/TemplateBatchRecoveryPanel.vue'
import TemplateSessionDiagnosticsPanel from '@/components/TemplateSessionDiagnosticsPanel.vue'
import SessionMonitorPanel from '@/components/SessionMonitorPanel.vue'
import type { Task, TemplateFailedBatch, TemplateSessionDiagnostic, TaskSessionSummary, TaskSessionActivity } from '@/types/domain'
function mock(name: keyof typeof api) { return vi.spyOn(api, name) as Mock }
function proof(subcase: string, evidence: unknown) { console.info('W0_IDENTITY', JSON.stringify({ subcase, evidence })) }
const roots: VueWrapper[] = []
let originalSkin: string
async function exerciseSkins() { for (const skin of skins) { applySkin(skin.id, false); await flushPromises(); expect(document.documentElement.dataset.skin).toBe(skin.id) } }
const input = { templateId: 'SNAPSHOT_CODE_REVIEW', templateVersion: '1', projectId: 'p', branchId: 'local:main', reviewMode: 'FULL' as const, documentPath: '/report' }
const batch = (version = 3): TemplateFailedBatch => ({ id: 'batch-1', version, purpose: 'REVIEW', ordinal: 0, generation: 1, createdAt: '2026-09-01T00:00:00Z', state: 'FAILED', errorMessage: '原会话已停止' })
const task = { id: 'task-A', status: 'WAITING_INPUT', templateProgress: { failedBatches: 1, activeBatches: 0 } } as Task
const failedPage = (version = 3, ready = true) => ({ items: [batch(version)], nextCursor: null, facets: { retrySelectionReady: ready ? 1 : 0, blockingBatches: ready ? 0 : 1, taskVersion: 7 } })
const diagnostic: TemplateSessionDiagnostic = { batchId: 'batch-12', batchVersion: 7, sessionKey: 'execution:local-12', localSessionId: 'local-12', externalSessionId: 'ses_remote-12', purpose: 'SNAPSHOT_LINKS', ordinal: 12, generation: 1, stageOrdinal: 2, state: 'RUNNING', phase: 'ACCEPTED_WAITING_STOP', reason: '结果已接受，等待停止', acceptedAt: '2026-09-16T01:39:31Z', observedAt: '2026-09-16T02:28:54Z', lastActivityAt: '2026-09-16T01:39:31Z', lastProgressAt: '2026-09-16T01:39:31Z', remoteState: 'busy', connected: true, stopProof: null, stopConfirmedAt: null, canFinalize: true, canStop: false }
const diagnosticPage = (row = diagnostic) => ({ items: [row], nextCursor: null, hasMore: false })
async function button(wrapper: VueWrapper, text: string) { const found = wrapper.findAll('button').find(b => b.text() === text); expect(found, `reachable ${text}`).toBeDefined(); await found!.trigger('click'); await flushPromises() }
async function panel(kind: 'batches' | 'diagnostics') { const wrapper = mount(kind === 'batches' ? TemplateBatchRecoveryPanel : TemplateSessionDiagnosticsPanel, { props: kind === 'batches' ? { task } : { taskId: 'task-A', active: true }, global: { plugins: [ElementPlus], stubs: { Icon: true, MarkdownDocument: true } } }); roots.push(wrapper); await flushPromises(); return wrapper }
function readBlob(blob: Blob, mode: 'text' | 'bytes' = 'text') { return new Promise<string | ArrayBuffer>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(reader.result as string | ArrayBuffer); reader.onerror = reject; if (mode === 'bytes') reader.readAsArrayBuffer(blob); else reader.readAsText(blob) }) }
function file(text: string) { const value = new File([text], '需求.md'); Object.defineProperty(value, 'arrayBuffer', { value: () => readBlob(value, 'bytes') }); return value }
async function ready(predicate: () => unknown) { await vi.waitFor(() => expect(predicate()).toBeTruthy(), { timeout: 1500, interval: 20 }); await flushPromises() }
beforeEach(() => { originalSkin = skinId.value; vi.useFakeTimers(); sessionStorage.clear(); setActivePinia(createPinia()); vi.stubGlobal('crypto', webcrypto) })
afterEach(() => { roots.splice(0).forEach(w => { if (w.exists()) w.unmount() }); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); sessionStorage.clear(); applySkin(originalSkin, false) })

describe('W0 B9 endpoint contracts', () => {
  for (const operation of ['start', 'batch', 'question', 'message'] as const) it(`B9.1 ${operation}: real fetch sends one unkeyed request on uncertain delivery`, async () => {
    const transport = vi.fn().mockRejectedValue(new Error('unknown delivery')); vi.stubGlobal('fetch', transport)
    const send = operation === 'start' ? () => api.startTemplateTask('task/A') : operation === 'batch' ? () => api.retryTemplateBatch('task/A', batch()) : operation === 'question' ? () => api.replyTaskSessionQuestion('task/A', 'execution:local-1', 'question/A', [['原回答']]) : () => api.sendDesignerMessage('designer/A', '原消息')
    await expect(send()).rejects.toThrow('unknown delivery'); await vi.advanceTimersByTimeAsync(6000)
    const [url, init] = transport.mock.calls[0] as [string, RequestInit]; const body = init.body ? JSON.parse(String(init.body)) : undefined
    proof(`B9.1/transport/${operation}`, { url, method: init.method, headers: init.headers, body, requests: transport.mock.calls.length, VueStrictMode: 'no current React command owner' })
    expect(transport).toHaveBeenCalledTimes(1); expect(init.method).toBe('POST'); expect(body ?? {}).not.toHaveProperty('requestKey'); expect(body ?? {}).not.toHaveProperty('commandId')
    if (operation === 'batch') expect(body).toEqual({ expectedVersion: 3 }); if (operation === 'question') expect(body).toEqual({ answers: [['原回答']] }); if (operation === 'message') expect(body).toEqual({ content: '原消息' })
  })
  it('B9.1 batch unknown then rerender/read refresh/theme and recovery open never retry automatically', async () => {
    const read = mock('templateFailedBatches').mockResolvedValue(failedPage()); const write = mock('retrySelectedTemplateBatches').mockRejectedValue(new Error('未知回执')); const wrapper = await panel('batches')
    await wrapper.get('input[type=checkbox]').setValue(true); await button(wrapper, '重新触发所选批次（1）'); await button(wrapper, '重新加载'); await wrapper.setProps({ task: { ...task } }); await exerciseSkins()
    mock('getTemplateSessionDiagnostics').mockResolvedValue(diagnosticPage()); const recovery = await panel('diagnostics'); await button(recovery, '刷新状态'); await vi.advanceTimersByTimeAsync(1200); await flushPromises()
    proof('B9.1/batch-owner', { writes: write.mock.calls.map(c => [c[0], c[1].map((b: TemplateFailedBatch) => ({ id: b.id, expectedVersion: b.version }))]), reads: read.mock.calls.length, recoveryOpened: true }); expect(write).toHaveBeenCalledTimes(1)
  })
  it('B9.1 question unknown then real poll read/same-props/skin changes never resubmit', async () => {
    const session: TaskSessionSummary = { key: 'execution:local-1', kind: 'IMPLEMENTATION', label: '会话', localSessionId: 'local-1', externalSessionId: 'remote-1', state: 'RUNNING', stageId: 'stage-1', stageOrdinal: 1, stageObjective: '实现', attemptId: 'attempt-1', createdAt: 'now' }
    const activity: TaskSessionActivity = { session, remoteState: 'busy', live: true, observedAt: '2026-09-01', parts: [], pendingQuestions: [{ id: 'question-1', questions: [{ question: '如何收尾？', header: '收尾', multiple: false, custom: true, options: [{ label: '按原范围', description: '保持边界' }] }] }], todoCapability: 'AVAILABLE', todos: [], todoTruncated: false, usage: { totalTokens: 10, unknownUsageCount: 0, observedAt: '2026-09-01' } }
    mock('getTaskSessions').mockResolvedValue([session]); const read = mock('getTaskSessionActivity').mockResolvedValue(activity); const write = mock('replyTaskSessionQuestion').mockRejectedValue(new Error('未知回答回执'))
    const wrapper = mount(SessionMonitorPanel, { props: { taskId: 'task-A' }, global: { plugins: [ElementPlus], stubs: { Icon: true, MarkdownDocument: true, TemplateSessionDiagnosticsPanel: true } } }); roots.push(wrapper); await flushPromises(); await wrapper.get('input[type=radio]').setValue(true); await button(wrapper, '提交回答并继续')
    await wrapper.setProps({ taskId: 'task-A' }); await exerciseSkins(); await vi.advanceTimersByTimeAsync(1200); await flushPromises(); expect(read.mock.calls.length).toBeGreaterThanOrEqual(2)
    proof('B9.1/question-owner', { writes: write.mock.calls, reads: read.mock.calls.length, manualRefresh: 'no button in current panel', realPollIntervalMs: 1200, skins: skins.map(skin => skin.id) }); expect(write).toHaveBeenCalledTimes(1); expect(write).toHaveBeenCalledWith('task-A', 'execution:local-1', 'question-1', [['按原范围']])
  })
  it('B9.2 report external-owner negative control: changing then restoring unknown draft cannot replace original key', async () => {
    const write = mock('createTemplateTask').mockRejectedValue(new Error('未知回执')); const store = useTemplateTaskStore(); for (const draft of [input, { ...input, documentPath: '/changed' }, input]) await store.start(draft).catch(() => undefined)
    proof('B9.2/report/owner-negative', { requests: write.mock.calls.map(c => c[0]), actualEditableUI: 'separately B3.1/report' }); expect.soft(write.mock.calls[1]?.[0]).toEqual(write.mock.calls[0]?.[0]); expect.soft(write.mock.calls[2]?.[0]).toEqual(write.mock.calls[0]?.[0])
  })
  it('B9.2 multipart real File bytes and metadata identical on explicit unchanged retry', async () => {
    vi.useRealTimers()
    const transport = vi.fn().mockRejectedValue(new Error('未知上传回执')); vi.stubGlobal('fetch', transport); const store = useDocumentTemplateStore(); const files = [file('冻结原始字节')]; const draft = { templateId: 'REQUIREMENT_CODE_REVIEW', templateVersion: '1', projectId: 'p', branchId: 'local:main' }
    for (let i = 0; i < 2; i++) { const pending = store.start(draft, files).catch(() => undefined); await ready(() => !store.submitting); await pending }
    const bodies = transport.mock.calls.map(c => c[1].body as FormData); const metadata = await Promise.all(bodies.map(async body => JSON.parse(String(await readBlob(body.get('metadata') as Blob))))); const bytes = await Promise.all(bodies.map(body => readBlob(body.getAll('files')[0] as Blob, 'bytes')))
    proof('B9.2/multipart', { routes: transport.mock.calls.map(c => c[0]), metadata, names: bodies.map(body => body.getAll('files').map(f => (f as File).name)), sha256: bytes.map(b => createHash('sha256').update(new Uint8Array(b as ArrayBuffer)).digest('hex')), localUi: transport.mock.calls.map(c => c[1].headers['X-Loopper-Local-UI']) }); expect(metadata[1]).toEqual(metadata[0]); expect(bytes[1]).toEqual(bytes[0]); expect(metadata[0].requestKey).toBeTruthy(); expect(transport).toHaveBeenCalledTimes(2)
  })
  it('B9.2 diagnostic unknown retry keeps original action/CAS/commandId', async () => {
    mock('getTemplateSessionDiagnostics').mockResolvedValue(diagnosticPage()); const write = mock('recoverTemplateSession').mockRejectedValue(new Error('未知恢复回执')); const wrapper = await panel('diagnostics'); await button(wrapper, '结束会话并收尾'); await wrapper.setProps({ active: false }); await exerciseSkins(); await button(wrapper, '刷新状态'); await button(wrapper, '结束会话并收尾')
    proof('B9.2/diagnostic', { original: write.mock.calls[0], retry: write.mock.calls[1] }); expect(write.mock.calls[1]).toEqual(write.mock.calls[0]); expect(write.mock.calls[0]?.[2]).toEqual({ action: 'FINALIZE', expectedVersion: 7, commandId: expect.any(String) })
  })
  it('B9.2 mock server rejects same key/different digest; does not claim actual Java validation', async () => {
    vi.useRealTimers()
    const seen = new Map<string, string>(); const transport = vi.fn(async (_url: string, init: RequestInit) => { const body = init.body as FormData; const metadata = JSON.parse(String(await readBlob(body.get('metadata') as Blob))); const digest = createHash('sha256').update(String(await readBlob(body.getAll('files')[0] as Blob))).digest('hex'); const old = seen.get(metadata.requestKey); if (old && old !== digest) return new Response(JSON.stringify({ detail: '同请求标识字节不符' }), { status: 409 }); seen.set(metadata.requestKey, digest); return new Response(JSON.stringify({ id: 'run-A' }), { status: 200 }) }); vi.stubGlobal('fetch', transport)
    const request = { templateId: 'REQUIREMENT_CODE_REVIEW', templateVersion: '1', projectId: 'p', requestKey: 'original-request-key' }; await api.createDocumentTemplate(request, [file('原字节')]); await expect(api.createDocumentTemplate(request, [file('另一字节')])).rejects.toMatchObject({ status: 409 }); proof('B9.2/mock-server-negative', { requests: transport.mock.calls.length, requestKey: request.requestKey, actualJavaExecuted: false })
  })
  it('B9.3 accepted Task/start unknown unchanged retry only starts known Task', async () => {
    const create = mock('createTemplateTask').mockResolvedValue({ id: 'task-created' }); const start = mock('startTemplateTask').mockRejectedValue(new Error('start回执未知')); const store = useTemplateTaskStore(); for (let i = 0; i < 2; i++) await store.start(input).catch(() => undefined)
    proof('B9.3/report-known-task', { create: create.mock.calls, start: start.mock.calls }); expect(create).toHaveBeenCalledTimes(1); expect(start.mock.calls).toEqual([['task-created'], ['task-created']])
  })
  it('B9.3 accepted Task/start unknown changed then restored draft must never create another Task', async () => {
    const create = mock('createTemplateTask').mockResolvedValue({ id: 'task-created' }); const start = mock('startTemplateTask').mockRejectedValue(new Error('start回执未知')); const store = useTemplateTaskStore(); for (const draft of [input, { ...input, documentPath: '/changed' }, input]) await store.start(draft).catch(() => undefined)
    proof('B9.3/report-known-task-new-draft', { create: create.mock.calls, start: start.mock.calls, scope: 'external owner negative control, actual UI separately B3.1' }); expect(create).toHaveBeenCalledTimes(1)
  })
  it('B9.3 batch unknown GET advances CAS: original retry cannot silently become new version', async () => {
    const read = mock('templateFailedBatches').mockResolvedValue(failedPage()); const write = mock('retrySelectedTemplateBatches').mockRejectedValue(new Error('批次回执未知')); const wrapper = await panel('batches'); await wrapper.get('input[type=checkbox]').setValue(true); await button(wrapper, '重新触发所选批次（1）'); read.mockResolvedValue(failedPage(4)); await button(wrapper, '重新加载'); await button(wrapper, '重新触发所选批次（1）')
    const requests = write.mock.calls.map(c => ({ task: c[0], batches: c[1].map((b: TemplateFailedBatch) => ({ id: b.id, expectedVersion: b.version })) })); proof('B9.3/batch-CAS', requests); expect(requests[1]).toEqual(requests[0])
  })
  it('B9.3 stop-proof projection blocks retry without inventing key', async () => {
    const read = mock('templateFailedBatches').mockResolvedValue(failedPage()); const write = mock('retrySelectedTemplateBatches').mockRejectedValue(new Error('未知批次回执')); const wrapper = await panel('batches'); await wrapper.get('input[type=checkbox]').setValue(true); await button(wrapper, '重新触发所选批次（1）'); read.mockResolvedValue(failedPage(4, false)); await button(wrapper, '重新加载'); expect(wrapper.findAll('button').some(b => b.text().startsWith('重新触发所选批次'))).toBe(false); await wrapper.setProps({ task: { ...task, status: 'STOPPING' } }); await flushPromises(); expect(write).toHaveBeenCalledTimes(1); proof('B9.3/stop-proof', { writes: write.mock.calls.length, ready: false, status: 'STOPPING' })
  })
  it('B9.3 diagnostic accepted response followed by read failure must never rePOST receipt', async () => {
    const read = mock('getTemplateSessionDiagnostics').mockResolvedValueOnce(diagnosticPage()).mockRejectedValueOnce(new Error('接受后读取失败')).mockResolvedValue(diagnosticPage()); const write = mock('recoverTemplateSession').mockResolvedValue({ ...diagnostic, phase: 'STOP_REQUESTED', canFinalize: false }); const wrapper = await panel('diagnostics'); await button(wrapper, '结束会话并收尾'); expect(write).toHaveBeenCalledTimes(1); expect(wrapper.get('[role=alert]').text()).toBeTruthy(); await button(wrapper, '刷新状态'); const available = wrapper.findAll('button').find(b => b.text() === '结束会话并收尾'); if (available) { await available.trigger('click'); await flushPromises() }
    proof('B9.3/diagnostic-accepted-read-fail', { reads: read.mock.calls.length, writes: write.mock.calls, knownAccepted: true, staleReadCanFinalize: true }); expect(write).toHaveBeenCalledTimes(1)
  })
})
