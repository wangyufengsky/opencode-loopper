/** W0 endpoint-specific identity; transport mocks do not prove Java validation. */
import { createHash, webcrypto } from 'node:crypto'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, afterEach, describe, expect, it, vi, type Mock } from 'vitest'
import { api } from '@/api/client'
import { applySkin, skinId } from '@/themes/state'
import { skins } from '@/themes/registry'
import { createReportCreationController, createDocumentCreationController } from '@/pages/w3/templates/catalog/creation'
import { templateBatchW0Contract, templateDiagnosticW0Contract } from '@/pages/w3/templates/runs/run-child-w0-contract'
import SessionMonitorPanel from '@/components/SessionMonitorPanel.vue'
import type { TemplateFailedBatch, TaskSessionSummary, TaskSessionActivity } from '@/types/domain'
function mock(name: keyof typeof api) { return vi.spyOn(api, name) as Mock }
function proof(subcase: string, evidence: unknown) { console.info('W0_IDENTITY', JSON.stringify({ subcase, evidence })) }
const roots: VueWrapper[] = []
let originalSkin: string
async function exerciseSkins() { for (const skin of skins) { applySkin(skin.id, false); await flushPromises(); expect(document.documentElement.dataset.skin).toBe(skin.id) } }
const input = { templateId: 'SNAPSHOT_CODE_REVIEW', templateVersion: '1', projectId: 'p', branchId: 'local:main', reviewMode: 'FULL' as const, documentPath: '/report' }
const batch = (version = 3): TemplateFailedBatch => ({ id: 'batch-1', version, purpose: 'REVIEW', ordinal: 0, generation: 1, createdAt: '2026-09-01T00:00:00Z', state: 'FAILED', errorMessage: '原会话已停止' })
async function button(wrapper: VueWrapper, text: string) { const found = wrapper.findAll('button').find(b => b.text() === text); expect(found, `reachable ${text}`).toBeDefined(); await found!.trigger('click'); await flushPromises() }
function readBlob(blob: Blob, mode: 'text' | 'bytes' = 'text') { return new Promise<string | ArrayBuffer>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(reader.result as string | ArrayBuffer); reader.onerror = reject; if (mode === 'bytes') reader.readAsArrayBuffer(blob); else reader.readAsText(blob) }) }
function file(text: string) { const value = new File([text], '需求.md'); Object.defineProperty(value, 'arrayBuffer', { value: () => readBlob(value, 'bytes') }); return value }
async function ready(predicate: () => unknown) { await vi.waitFor(() => expect(predicate()).toBeTruthy(), { timeout: 1500, interval: 20 }); await flushPromises() }
beforeEach(() => { originalSkin = skinId.value; vi.useFakeTimers(); sessionStorage.clear(); setActivePinia(createPinia()); vi.stubGlobal('crypto', webcrypto); for (const name of ['templateFailedBatches', 'retrySelectedTemplateBatches', 'getTemplateSessionDiagnostics', 'recoverTemplateSession'] as const) mock(name) })
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
    await templateBatchW0Contract('no-auto-write', { proof })
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
    const write = mock('createTemplateTask').mockRejectedValue(new Error('未知回执')); const store = createReportCreationController({ storage: null }); for (const draft of [input, { ...input, documentPath: '/changed' }, input]) await store.start(draft).catch(() => undefined)
    proof('B9.2/report/owner-negative', { requests: write.mock.calls.map(c => c[0]), actualEditableUI: 'separately B3.1/report' }); expect.soft(write.mock.calls[1]?.[0]).toEqual(write.mock.calls[0]?.[0]); expect.soft(write.mock.calls[2]?.[0]).toEqual(write.mock.calls[0]?.[0])
  })
  it('B9.2 multipart real File bytes and metadata identical on explicit unchanged retry', async () => {
    vi.useRealTimers()
    const transport = vi.fn().mockRejectedValue(new Error('未知上传回执')); vi.stubGlobal('fetch', transport); const store = createDocumentCreationController({ storage: null }); const files = [file('冻结原始字节')]; const draft = { templateId: 'REQUIREMENT_CODE_REVIEW', templateVersion: '1', projectId: 'p', branchId: 'local:main' }
    for (let i = 0; i < 2; i++) { const pending = store.start(draft, files).catch(() => undefined); await ready(() => !store.getSnapshot().busy); await pending }
    const bodies = transport.mock.calls.map(c => c[1].body as FormData); const metadata = await Promise.all(bodies.map(async body => JSON.parse(String(await readBlob(body.get('metadata') as Blob))))); const bytes = await Promise.all(bodies.map(body => readBlob(body.getAll('files')[0] as Blob, 'bytes')))
    proof('B9.2/multipart', { routes: transport.mock.calls.map(c => c[0]), metadata, names: bodies.map(body => body.getAll('files').map(f => (f as File).name)), sha256: bytes.map(b => createHash('sha256').update(new Uint8Array(b as ArrayBuffer)).digest('hex')), localUi: transport.mock.calls.map(c => c[1].headers['X-Loopper-Local-UI']) }); expect(metadata[1]).toEqual(metadata[0]); expect(bytes[1]).toEqual(bytes[0]); expect(metadata[0].requestKey).toBeTruthy(); expect(transport).toHaveBeenCalledTimes(2)
  })
  it('B9.2 diagnostic unknown retry keeps original action/CAS/commandId', async () => {
    await templateDiagnosticW0Contract('unknown-identity', { proof })
  })
  it('B9.2 mock server rejects same key/different digest; does not claim actual Java validation', async () => {
    vi.useRealTimers()
    const seen = new Map<string, string>(); const transport = vi.fn(async (_url: string, init: RequestInit) => { const body = init.body as FormData; const metadata = JSON.parse(String(await readBlob(body.get('metadata') as Blob))); const digest = createHash('sha256').update(String(await readBlob(body.getAll('files')[0] as Blob))).digest('hex'); const old = seen.get(metadata.requestKey); if (old && old !== digest) return new Response(JSON.stringify({ detail: '同请求标识字节不符' }), { status: 409 }); seen.set(metadata.requestKey, digest); return new Response(JSON.stringify({ id: 'run-A' }), { status: 200 }) }); vi.stubGlobal('fetch', transport)
    const request = { templateId: 'REQUIREMENT_CODE_REVIEW', templateVersion: '1', projectId: 'p', requestKey: 'original-request-key' }; await api.createDocumentTemplate(request, [file('原字节')]); await expect(api.createDocumentTemplate(request, [file('另一字节')])).rejects.toMatchObject({ status: 409 }); proof('B9.2/mock-server-negative', { requests: transport.mock.calls.length, requestKey: request.requestKey, actualJavaExecuted: false })
  })
  it('B9.3 accepted Task/start unknown unchanged retry only starts known Task', async () => {
    mock('getTask').mockResolvedValue({ id: 'task-created', status: 'PENDING_START' }); const create = mock('createTemplateTask').mockResolvedValue({ id: 'task-created' }); const start = mock('startTemplateTask').mockRejectedValue(new Error('start回执未知')); const store = createReportCreationController({ storage: null }); for (let i = 0; i < 2; i++) await store.start(input).catch(() => undefined)
    proof('B9.3/report-known-task', { create: create.mock.calls, start: start.mock.calls }); expect(create).toHaveBeenCalledTimes(1); expect(start.mock.calls).toEqual([['task-created'], ['task-created']])
  })
  it('B9.3 accepted Task/start unknown changed then restored draft must never create another Task', async () => {
    mock('getTask').mockResolvedValue({ id: 'task-created', status: 'PENDING_START' }); const create = mock('createTemplateTask').mockResolvedValue({ id: 'task-created' }); const start = mock('startTemplateTask').mockRejectedValue(new Error('start回执未知')); const store = createReportCreationController({ storage: null }); for (const draft of [input, { ...input, documentPath: '/changed' }, input]) await store.start(draft).catch(() => undefined)
    proof('B9.3/report-known-task-new-draft', { create: create.mock.calls, start: start.mock.calls, scope: 'external owner negative control, actual UI separately B3.1' }); expect(create).toHaveBeenCalledTimes(1)
  })
  it('B9.3 batch unknown GET advances CAS: original retry cannot silently become new version', async () => {
    await templateBatchW0Contract('original-cas', { proof })
  })
  it('B9.3 stop-proof projection blocks retry without inventing key', async () => {
    await templateBatchW0Contract('stop-proof', { proof })
  })
  it('B9.3 diagnostic accepted response followed by read failure must never rePOST receipt', async () => {
    await templateDiagnosticW0Contract('accepted-read-failure', { proof })
  })
})
