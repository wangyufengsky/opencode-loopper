import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { defineComponent, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import DesignerView from '@/views/DesignerView.vue'
import { api, subscribeDesignerEvents } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import type { AppSettings, DesignerSession, DesignerStreamEvent, LoopDraft, LoopSpec, Project, Task } from '@/types/domain'

// Mock transports, never vue-router or its guard registration.
vi.mock('@/api/client', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/client')>()
  return { ...original, api: Object.fromEntries(Object.keys(original.api).map(key => [key, vi.fn()])), subscribeDesignerEvents: vi.fn() }
})
const calls = vi.mocked(api)
const project: Project = { id: 'project', name: '测试项目', rootPath: '/fixture', status: 'READY', updatedAt: 'now', taskCount: 0, openDesignerSessionCount: 0 }
const settings = { limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDurationMinutes: 120, attemptTimeoutMinutes: 30 }, openCode: { provider: 'fixture', model: 'fixture' } } as AppSettings
function spec(goal = '已保存草稿'): LoopSpec { return { schemaVersion: 'v2', projectId: project.id, goal, context: '', stages: [{ objective: '聚焦验证', implementationKind: 'NON_JAVA', allowedPaths: [], forbiddenPaths: [], deliverables: ['测试证据'], acceptanceCriteria: [], verifiers: [] }], limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: 'PT2H', attemptTimeout: 'PT30M' } } }
function draft(value = spec()): LoopDraft { return { id: 'draft-A', version: 0, status: 'DRAFT_READY', updatedAt: 'now', spec: value } }
function session(id = 'A', overrides: Partial<DesignerSession> = {}): DesignerSession {
  return { id, projectId: project.id, projectName: project.name, state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT', activeActor: 'SYSTEM', accessMode: 'READ_ONLY', readOnly: true, discussionScope: 'REQUIREMENT', discussionRevision: 1, finalConfirmationEligible: false, autoMode: { enabled: false, state: 'DISABLED', version: 0 }, questionInteraction: { mode: 'NONE', awaitingAnswer: false }, taskProfile: { id: 'profile-A', state: 'PROVISIONAL', decisionState: 'CONFIRMED', confirmationReady: true, intent: 'SOFTWARE_CHANGE', workflowTemplate: 'FULL_PACKAGE_DESIGN', mutationMode: 'WRITE_CODE', artifactKinds: ['SOURCE_CODE'], technologies: [], testPolicy: 'REQUIRED', executionStrategy: 'OPEN_CODE_IMPLEMENTATION', rolePackId: 'fixture', rolePackVersion: 'fixture', confidence: 100, evidence: [], resolutionSource: 'USER_CONFIRMED', decisionRequired: false, largeTaskMode: false, version: 7 }, availableProfileOverrides: ['SOFTWARE_CHANGE', 'DOCUMENT_AUTHORING'], availableArtifactOverrides: ['SOURCE_CODE', 'MARKDOWN'], reports: [], messages: [], draft: draft(), ...overrides }
}
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const roots: VueWrapper[] = []
const streams: { id: string; event: (event: DesignerStreamEvent) => void; close: ReturnType<typeof vi.fn> }[] = []
function trace(caseId: string, evidence: unknown) { console.info('[W0]', JSON.stringify({ caseId, evidence })) }
function button(view: VueWrapper, label: string) {
  const result = view.findAll('button').find(node => node.text() === label || node.attributes('aria-label') === label)
  if (!result) throw new Error(`W0 fixture missing button: ${label}`)
  return result
}
async function click(view: VueWrapper, label: string) { await button(view, label).trigger('click'); await flushPromises() }
function retire(view: VueWrapper) { view.unmount(); const index = roots.indexOf(view); if (index >= 0) roots.splice(index, 1) }
beforeEach(() => {
  vi.resetAllMocks(); sessionStorage.clear(); streams.length = 0
  vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('W0 forbids unmocked network'))))
  calls.getSettings.mockResolvedValue(settings); calls.validateDraft.mockResolvedValue({ valid: true, schemaVersion: 'v2', legacy: false, errors: [], stageAssessments: [] })
  calls.createDraft.mockImplementation(async value => draft(value)); calls.getDraft.mockResolvedValue(draft())
  calls.getDesignerSession.mockImplementation(async id => session(id)); calls.createDesignerSession.mockResolvedValue(session())
  calls.createDesignerContextTurn.mockResolvedValue(session()); calls.updateDraft.mockImplementation(async (_id, value) => draft(value))
  calls.sendDesignerContextTurn.mockResolvedValue({ sessionId: 'A', state: 'REVIEWING', persistedMessages: [], notice: '' })
  vi.mocked(subscribeDesignerEvents).mockImplementation((id, event) => { const close = vi.fn(); streams.push({ id, event, close }); return { close } })
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  for (const name of ['error', 'success', 'info', 'warning'] as const) vi.spyOn(ElMessage, name).mockImplementation(() => ({ close() {} }) as never)
})
afterEach(() => { while (roots.length) roots.pop()!.unmount(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); sessionStorage.clear(); document.body.innerHTML = '' })
async function render(query = '') {
  const pinia = createPinia(); setActivePinia(pinia); const store = useTaskStore(); store.usingDemo = false; store.projects = [project]
  const other = defineComponent({ render: () => h('textarea', { id: 'designer-message', 'aria-label': '其它页面输入' }) })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/designer', component: DesignerView }, { path: '/away', component: other }, { path: '/tasks/:id', component: other }] })
  router.onError(() => {}); await router.push('/designer' + query); await router.isReady()
  const view = mount(RouterView, { attachTo: document.body, global: { plugins: [pinia, router, ElementPlus], stubs: {
    Icon: true, PageHeader: { template: '<header><slot /><slot name="actions" /></header>' }, StatusBadge: true, LayeredErrorPanel: true,
    LoopSpecEditor: true, MarkdownDocument: true, DesignerCurrentActivity: true,
  } } }); roots.push(view); await flushPromises(); return { view, router, store }
}
async function drop(view: VueWrapper, file: File) { await view.get('#main-content').trigger('drop', { dataTransfer: { files: [file] } }); await flushPromises() }
async function initial(view: VueWrapper, file?: File) {
  await view.get('textarea[aria-label="草案设计目标"]').setValue('原初始目标'); if (file) await drop(view, file)
  await view.get('.create-draft-button').trigger('click'); await flushPromises()
}

describe('B5.1 ordinary Designer drafts require real route confirmation', () => {
  for (const kind of ['initial-text', 'followup-text', 'initial-file', 'followup-file']) it(`${kind}: declining leave preserves draft`, async () => {
    const restored = kind.startsWith('followup'), { view, router } = await render(restored ? '?sessionId=A' : '')
    const file = new File(['original bytes'], 'original.txt', { type: 'text/plain' })
    if (kind.endsWith('text')) await view.get(restored ? '#designer-message' : 'textarea[aria-label="草案设计目标"]').setValue('未发送草稿')
    else await drop(view, file)
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce(new Error('user declined'))
    await router.push('/away'); await flushPromises()
    trace('B5.1', { kind, route: router.currentRoute.value.path, confirmCount: vi.mocked(ElMessageBox.confirm).mock.calls.length })
    expect.soft(router.currentRoute.value.path).toBe('/designer'); expect.soft(ElMessageBox.confirm).toHaveBeenCalledOnce()
    if (router.currentRoute.value.path === '/designer') {
      if (kind.endsWith('text')) expect((view.get(restored ? '#designer-message' : 'textarea[aria-label="草案设计目标"]').element as HTMLTextAreaElement).value).toBe('未发送草稿')
      else expect(view.text()).toContain('original.txt')
    }
  })
  it('saved read-only Designer can leave without a confirmation or write', async () => {
    const { router } = await render('?sessionId=A'); await router.push('/away')
    expect(router.currentRoute.value.path).toBe('/away'); expect(ElMessageBox.confirm).not.toHaveBeenCalled(); expect(calls.createDesignerContextTurn).not.toHaveBeenCalled()
  })
})
describe('B5.2 unresolved Designer writes forbid route leave', () => {
  for (const channel of ['initial', 'followup']) for (const phase of ['sending', 'unknown']) it(`${channel}/${phase} retains File owner`, async () => {
    const pending = deferred<DesignerSession>(), file = new File(['original bytes'], 'original.txt', { type: 'text/plain' })
    const { view, router } = await render(channel === 'initial' ? '' : '?sessionId=A')
    if (channel === 'initial') { calls.createDesignerContextTurn.mockImplementation(() => pending.promise); await initial(view, file) }
    else { calls.sendDesignerContextTurn.mockImplementation(() => pending.promise as never); await view.get('#designer-message').setValue('原消息'); await drop(view, file); await click(view, '发送') }
    if (phase === 'unknown') { pending.reject(new Error('lost acknowledgement')); await flushPromises() }
    const write = channel === 'initial' ? calls.createDesignerContextTurn : calls.sendDesignerContextTurn
    expect(write).toHaveBeenCalledOnce(); await router.push('/away'); await flushPromises()
    trace('B5.2', { channel, phase, route: router.currentRoute.value.path, mutationCount: write.mock.calls.length, firstBody: write.mock.calls[0]?.[channel === 'initial' ? 0 : 1], fileNames: [file.name] })
    expect(router.currentRoute.value.path).toBe('/designer')
  })
})
describe('B5.3 accepted Task handoff retains original identity', () => {
  for (const failure of ['guard-false', 'reject']) it(`${failure}: confirmed Task retains an explicit navigation-only recovery`, async () => {
    const ready = session('A', { state: 'COMPLETED', workflowPhase: 'COMPLETED', discussionScope: 'FINAL', finalConfirmationEligible: true })
    calls.getDesignerSession.mockResolvedValue(ready)
    const task = { id: 'accepted-task', projectId: project.id, projectName: project.name, title: '已创建任务', goal: '已创建任务', status: 'PENDING_START', attemptCount: 0, maxAttempts: 7, branch: '', worktreePath: '', createdAt: 'now', updatedAt: 'now' } as Task
    calls.confirmDraft.mockResolvedValue({ taskId: task.id }); calls.getTaskOverview.mockResolvedValue(task); calls.getTaskAudit.mockResolvedValue({ attempts: [], artifacts: [] } as never)
    const { view, router } = await render('?sessionId=A'); calls.getDraft.mockResolvedValue({ ...draft(), status: 'CONFIRMED' })
    router.beforeEach(to => { if (to.path.startsWith('/tasks/')) { if (failure === 'reject') throw new Error('navigation rejected'); return false } return true })
    const confirm = view.findAll('button').find(node => node.text().includes('确认设计并创建任务'))
    expect(confirm).toBeDefined(); await confirm!.trigger('click'); await flushPromises()
    expect(calls.confirmDraft).toHaveBeenCalledOnce(); expect(calls.getTaskOverview).toHaveBeenCalledWith(task.id)
    trace('B5.3', { failure, route: router.currentRoute.value.path, confirmedTaskIds: useTaskStore().tasks.map(row => row.id), workspace: sessionStorage.getItem('opencode-loopper.designer-workspace'), text: view.text() })
    const recoveryVisible = view.find('a[href="/tasks/accepted-task"]').exists() || view.findAll('button').some(node => /打开已创建.*任务|恢复.*交接|重试.*打开/.test(node.text()))
    expect.soft(recoveryVisible).toBe(true)
    expect.soft(view.find('textarea[aria-label="草案设计目标"]').exists()).toBe(false)
  })
})

const question = { id: 'question-A', questions: [{ question: '选择实现范围', header: '范围', multiple: false, custom: false, options: [{ label: '原答案', description: '原选择' }] }] }
describe('B6.1 retired question callbacks stay with original owner', () => {
  it('reply accepted after retirement must not launch a new A refresh or alter independent B', async () => {
    calls.getDesignerSession.mockImplementation(async id => session(id, { pendingQuestions: id === 'A' ? [question] : [] }))
    const pending = deferred<void>(); calls.replyDesignerQuestion.mockImplementation(() => pending.promise)
    const a = await render('?sessionId=A'); await click(a.view, '采用全部推荐项'); expect(calls.replyDesignerQuestion).toHaveBeenCalledWith('A', question.id, [['原答案']])
    retire(a.view); const b = await render('?sessionId=B'), before = calls.getDesignerSession.mock.calls.length
    pending.resolve(); await flushPromises(); trace('B6.1', { afterRetirementReads: calls.getDesignerSession.mock.calls.slice(before), bRoute: b.router.currentRoute.value.fullPath })
    expect(calls.getDesignerSession.mock.calls.slice(before)).toEqual([])
  })
  it('reject modal path is unreachable: mandatory question exposes no reject action', async () => {
    calls.getDesignerSession.mockResolvedValue(session('A', { pendingQuestions: [question] }))
    const { view } = await render('?sessionId=A')
    expect(view.find('[aria-label="设计师等待回答"]').exists()).toBe(true)
    expect(view.findAll('button').some(node => node.text() === '拒绝')).toBe(false)
    expect(calls.rejectDesignerQuestion).not.toHaveBeenCalled(); expect(ElMessageBox.confirm).not.toHaveBeenCalled()
    trace('B6.1', { classification: 'UNREPRODUCED_OR_UNREACHABLE', reason: 'production PendingQuestionCard mandatory=true hides reject; no synthetic reject emit' })
  })
})
describe('B6.2 profile preview/modal await scope', () => {
  for (const pause of ['preview', 'modal']) it(`${pause}: retirement invalidates profile action before mutation`, async () => {
    const preview = deferred<never>(), modal = deferred<never>()
    const response = { updateRequired: true, selectionChanged: true, sessionRestartRequired: true, targetWorkflowTemplate: 'FULL_PACKAGE_DESIGN' }
    calls.previewDesignerTaskProfileUpdate.mockImplementation(() => pause === 'preview' ? preview.promise : Promise.resolve(response as never))
    calls.updateDesignerTaskProfile.mockResolvedValue(session().taskProfile)
    vi.mocked(ElMessageBox.confirm).mockImplementation(() => modal.promise)
    const { view } = await render('?sessionId=A'); await click(view, '修改设置'); await view.get('.large-task-switch .el-switch').trigger('click'); await flushPromises()
    expect(button(view, '保存设置').attributes('disabled')).toBeUndefined(); await click(view, '保存设置')
    expect(calls.previewDesignerTaskProfileUpdate).toHaveBeenCalledOnce(); retire(view)
    if (pause === 'preview') { preview.resolve(response as never); await flushPromises() }
    modal.resolve('confirm' as never); await flushPromises()
    trace('B6.2', { pause, preview: calls.previewDesignerTaskProfileUpdate.mock.calls, update: calls.updateDesignerTaskProfile.mock.calls })
    expect(calls.updateDesignerTaskProfile).not.toHaveBeenCalled()
  })
})
describe('B6.3 follow-up immutable multipart operation and later draft', () => {
  it('unknown recovery uses original body/File and leaves later reachable edits unsent', async () => {
    const { view } = await render('?sessionId=A'), file = new File(['original'], 'original.txt', { type: 'text/plain' }), extra = new File(['extra'], 'extra.txt', { type: 'text/plain' })
    calls.sendDesignerContextTurn.mockRejectedValueOnce(new Error('unknown')).mockResolvedValue({ sessionId: 'A', state: 'REVIEWING', persistedMessages: [], notice: '' })
    await view.get('#designer-message').setValue('原消息'); await drop(view, file); await click(view, '发送')
    const original = calls.sendDesignerContextTurn.mock.calls[0]!
    expect(view.get('#designer-message').element.matches(':disabled')).toBe(false)
    await view.get('#designer-message').setValue('后来编辑'); await drop(view, extra); await click(view, '发送')
    const retry = calls.sendDesignerContextTurn.mock.calls[1]!
    trace('B6.3', { original: original.slice(0, 2), retry: retry.slice(0, 2), originalFiles: original[2].map(row => row.name), retryFiles: retry[2].map(row => row.name), finalDraft: (view.get('#designer-message').element as HTMLTextAreaElement).value })
    expect.soft(retry[1]).toEqual(original[1]); expect.soft(retry[2]).toEqual([file]); expect.soft(retry[2][0]).toBe(file)
    expect((view.get('#designer-message').element as HTMLTextAreaElement).value).toBe('后来编辑')
  })
  it('typing while a real send is in-flight remains an unsent draft after original acknowledgement', async () => {
    const pending = deferred<never>(); calls.sendRequirementMessage.mockImplementation(() => pending.promise)
    const { view } = await render('?sessionId=A'); await view.get('#designer-message').setValue('原消息'); await click(view, '发送')
    expect(calls.sendRequirementMessage).toHaveBeenCalledOnce(); expect(view.get('#designer-message').element.matches(':disabled')).toBe(false)
    await view.get('#designer-message').setValue('后来编辑'); pending.resolve({ sessionId: 'A', state: 'REVIEWING', persistedMessages: [] } as never); await flushPromises()
    trace('B6.3', { inFlightLaterDraft: (view.get('#designer-message').element as HTMLTextAreaElement).value, requests: calls.sendRequirementMessage.mock.calls })
    expect((view.get('#designer-message').element as HTMLTextAreaElement).value).toBe('后来编辑')
  })
})
describe('B7.1 owned terminal retry timeout immediate retirement', () => {
  it('first snapshot clears active retry before any natural callback completes', async () => {
    vi.useFakeTimers(); const inFlight = deferred<DesignerSession>(), running = session('A', { state: 'RUNNING', workflowPhase: 'DESIGNING' })
    calls.getDesignerSession.mockResolvedValueOnce(running).mockImplementation(() => inFlight.promise)
    const baseSet = globalThis.setTimeout, baseClear = globalThis.clearTimeout, owned = new Set<number | ReturnType<typeof setTimeout>>()
    vi.spyOn(globalThis, 'setTimeout').mockImplementation(((callback: () => void, delay?: number, ...args: unknown[]) => { const id = baseSet(callback, delay, ...args); if (String(callback).includes('refreshDesignerAfterTerminalEvent')) owned.add(id); return id }) as typeof setTimeout)
    vi.spyOn(globalThis, 'clearTimeout').mockImplementation(id => { owned.delete(id as ReturnType<typeof setTimeout>); baseClear(id) })
    const { view } = await render('?sessionId=A'); await vi.advanceTimersByTimeAsync(0); await flushPromises()
    expect(calls.getDesignerSession).toHaveBeenCalledTimes(2); const stream = streams.find(row => row.id === 'A')!; expect(stream).toBeDefined()
    stream.event({ type: 'COMPLETED', state: 'COMPLETED', workflowPhase: 'COMPLETED', activeActor: 'SYSTEM', runtimeConnected: true, at: 'now' } as DesignerStreamEvent)
    expect(owned.size).toBeGreaterThan(0); const before = owned.size; retire(view)
    // No clock advance, promise resolution, input, or natural callback precedes this sample.
    trace('B7.1', { before, firstAfter: owned.size, streamClosed: stream.close.mock.calls.length })
    expect.soft(owned.size).toBe(0); expect(stream.close).toHaveBeenCalledOnce()
    const reads = calls.getDesignerSession.mock.calls.length; inFlight.resolve(running); await flushPromises(); await vi.advanceTimersByTimeAsync(100)
    expect(calls.getDesignerSession).toHaveBeenCalledTimes(reads)
  })
})
describe('B7.2 owned composer focus RAF immediate retirement', () => {
  it('retirement cancels queued composer focus before it can focus another page', async () => {
    let next = 0; const pending = new Map<number, FrameRequestCallback>(), owned = new Set<number>()
    vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => { const id = ++next; pending.set(id, callback); if (String(callback).includes('#designer-message')) owned.add(id); return id }))
    vi.stubGlobal('cancelAnimationFrame', vi.fn((id: number) => { pending.delete(id); owned.delete(id) }))
    const { view, router } = await render('?sessionId=A&mode=edit')
    expect(owned.size).toBe(1); const id = [...owned][0]!, late = pending.get(id)!
    await router.push('/away'); await flushPromises()
    trace('B7.2', { before: 1, firstAfter: owned.size, route: router.currentRoute.value.path })
    expect.soft(owned.size).toBe(0)
    const target = document.getElementById('designer-message') as HTMLTextAreaElement; expect(target).toBeTruthy(); expect(document.activeElement).not.toBe(target)
    // Deliberately deliver the retired callback only after the strict sample.
    late(0); trace('B7.2', { lateFocusedNewPage: document.activeElement === target })
    expect(document.activeElement).not.toBe(target)
    expect(view.find('textarea[aria-label="其它页面输入"]').exists()).toBe(true)
  })
})
describe('B7.3 storage failures cannot discard accepted identity', () => {
  it('accepted initial File receipt stays recoverable when workspace persistence fails', async () => {
    const file = new File(['original'], 'original.txt', { type: 'text/plain' }), { view } = await render()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('storage denied', 'SecurityError') })
    await initial(view, file)
    trace('B7.3', { createCount: calls.createDesignerContextTurn.mock.calls.length, acceptedSessionVisible: view.find('#designer-message').exists(), errors: vi.mocked(ElMessage.error).mock.calls })
    expect(calls.createDesignerContextTurn).toHaveBeenCalledOnce(); expect(view.find('#designer-message').exists()).toBe(true)
    expect(vi.mocked(ElMessage.error).mock.calls.flat()).not.toContain('无法创建设计草案')
  })
  it('explicit session recovery remains usable when optional workspace storage is unwritable', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('storage denied', 'SecurityError') })
    const { view } = await render('?sessionId=A'); trace('B7.3', { recoveredVisible: view.find('#designer-message').exists(), text: view.text() })
    expect(view.find('#designer-message').exists()).toBe(true)
    expect(view.text()).not.toContain('无法恢复该设计会话')
  })
})
