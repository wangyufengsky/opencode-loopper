import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { defineComponent, h, type Component } from 'vue'
import { workflowRuns } from '@/api/workflowRuns'
import { workflowApi } from '@/api/workflow'
import { api as clientApi } from '@/api/client'
import { workflowPublication } from '@/api/workflowPublication'
import { workflowPush } from '@/api/workflowPush'
import { workflowWriteback } from '@/api/workflowWriteback'
import { template, summary, commandPreset } from '@/components/workflow/workflowTestFixtures'
import { attempt, candidate, execution, requirement } from '@/components/workflow/workflowRunTestFixtures'
import { createAcknowledgedOperation } from '@/domain/acknowledgedOperation'
import WorkflowRequirementNewView from '@/views/WorkflowRequirementNewView.vue'
import WorkflowLibraryView from '@/views/WorkflowLibraryView.vue'
import WorkflowRequirementView from '@/views/WorkflowRequirementView.vue'
import WorkflowNodeRun from '@/components/workflow/WorkflowNodeRun.vue'
import WorkflowCandidates from '@/components/workflow/WorkflowCandidates.vue'
import WorkflowFinish from '@/components/workflow/WorkflowFinish.vue'
import WorkflowPublicationCommit from '@/components/workflow/WorkflowPublicationCommit.vue'
import WorkflowPush from '@/components/workflow/WorkflowPush.vue'
import WorkflowWriteback from '@/components/workflow/WorkflowWriteback.vue'
import type { AppSettings, WorkflowPublicationPreview } from '@/types/domain'

// Transport-only mocks: the production VueRouter guards and command owners run unchanged.
vi.mock('@/api/workflowRuns', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/workflowRuns')>()
  return { ...original, workflowRuns: Object.fromEntries(Object.keys(original.workflowRuns).map(key => [key, vi.fn()])) }
})
vi.mock('@/api/workflow', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/workflow')>()
  return { ...original, workflowApi: Object.fromEntries(Object.keys(original.workflowApi).map(key => [key, vi.fn()])) }
})
vi.mock('@/api/workflowPublication', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/workflowPublication')>()
  return { ...original, workflowPublication: Object.fromEntries(Object.keys(original.workflowPublication).map(key => [key, vi.fn()])) }
})
vi.mock('@/api/workflowPush', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/workflowPush')>()
  return { ...original, workflowPush: Object.fromEntries(Object.keys(original.workflowPush).map(key => [key, vi.fn()])) }
})
vi.mock('@/api/workflowWriteback', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/workflowWriteback')>()
  return { ...original, workflowWriteback: Object.fromEntries(Object.keys(original.workflowWriteback).map(key => [key, vi.fn()])) }
})

const runs = vi.mocked(workflowRuns), flows = vi.mocked(workflowApi)
const views: VueWrapper[] = []
const leaf = defineComponent({ render: () => h('div', '其他页面') })
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function trace(caseId: string, evidence: unknown) { console.info('[W0]', JSON.stringify({ caseId, evidence })) }
function button(view: VueWrapper, label: string) {
  const found = view.findAll('button').find(node => node.text() === label || node.attributes('aria-label') === label)
  if (!found) throw new Error(`W0 fixture: missing button ${label}`)
  return found
}
async function click(view: VueWrapper, label: string) { await button(view, label).trigger('click'); await flushPromises() }
const receipt = { id: 'created', revision: 1, version: 1, layoutVersion: 0, state: 'PLANNING' as const }
beforeEach(() => {
  vi.resetAllMocks(); vi.spyOn(window, 'confirm').mockReturnValue(true)
  // Every accidental transport is a fixture failure; no server/provider can be contacted.
  vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('W0 forbids unmocked network'))))
  flows.get.mockResolvedValue(template({ revision: 3 })); flows.list.mockResolvedValue({ items: [summary()], nextCursor: undefined })
  runs.project.mockResolvedValue({ id: 'project', title: '测试项目' } as never)
  runs.projects.mockResolvedValue({ items: [], nextCursor: undefined, facets: {} })
  runs.get.mockResolvedValue(requirement({ state: 'RUNNING' })); runs.execution.mockResolvedValue(execution('RUNNING'))
  runs.finishStatus.mockResolvedValue({ requirementId: 'req', state: 'RUNNING', version: 7, intent: null, pending: { attempts: 0, resources: 0 } })
  runs.attempts.mockResolvedValue({ items: [attempt()], nextCursor: null }); runs.attempt.mockResolvedValue(attempt())
  runs.definition.mockResolvedValue(requirement().graph.nodes[0]!); runs.candidates.mockResolvedValue({ items: [{ ...candidate(), sourceState: 'RUNNING', createdAt: '' }], nextCursor: null }); runs.candidate.mockResolvedValue(candidate())
  vi.spyOn(clientApi, 'getSettings').mockResolvedValue({ openCode: { provider: 'fixture', model: 'fixture' } } as AppSettings)
  vi.spyOn(clientApi, 'getSettingsModels').mockResolvedValue([])
  vi.mocked(workflowPublication.status).mockResolvedValue(null); vi.mocked(workflowPush.status).mockResolvedValue(null); vi.mocked(workflowWriteback.status).mockResolvedValue(null)
})
afterEach(() => { while (views.length) views.pop()!.unmount(); vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = '' })
async function routeView(component: Component, path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/previous', component: leaf }, { path: '/away', component: leaf },
    { path: '/requirements/new', component: WorkflowRequirementNewView },
    { path: '/requirements/:id', component: component === WorkflowRequirementView ? WorkflowRequirementView : leaf },
    { path: '/workflows', component: WorkflowLibraryView }, { path: '/workflows/:id', component: leaf },
  ] })
  await router.push('/previous'); await router.push(path); await router.isReady()
  const view = mount(RouterView, { attachTo: document.body, global: { plugins: [router], stubs: {
    Icon: true, MarkdownDocument: true, CodeMergeEditor: true, WorkflowRolePicker: true, WorkflowModelChoice: true,
    // Only the unrelated canvas projection is replaced; no canLeave/command component is stubbed.
    WorkflowCanvas: { props: ['graph'], emits: ['select'], template: '<section><button v-for="node in graph.nodes" :key="node.id" @click="$emit(\'select\', node.id)">{{ node.title }}</button></section>', methods: { focus() {}, reveal() {} } },
  } } }); views.push(view); await flushPromises(); return { view, router }
}
async function newView() {
  const result = await routeView(WorkflowRequirementNewView, '/requirements/new?projectId=project')
  await result.view.get('#workflow-requirement-title').setValue('冻结标题')
  await result.view.get('#workflow-requirement-objective').setValue('冻结目标')
  return result
}
async function navigate(router: Router, action: string) {
  if (action === 'back') await new Promise<void>(resolve => { const remove = router.afterEach(() => { remove(); resolve() }); router.back() })
  else if (action === 'replace') await router.replace('/away')
  else await router.push('/away')
  await flushPromises()
}

describe('B1.1 New unresolved create navigation', () => {
  for (const phase of ['sending', 'unknown']) for (const action of ['push', 'replace', 'back']) it(`${phase}/${action} must retain original owner`, async () => {
    const pending = deferred<typeof receipt>(); runs.create.mockImplementation(() => pending.promise)
    const { view, router } = await newView(); await view.get('form').trigger('submit'); await flushPromises()
    if (phase === 'unknown') { pending.reject(new Error('lost acknowledgement')); await flushPromises() }
    expect(runs.create).toHaveBeenCalledOnce(); const body = runs.create.mock.calls[0]![0]
    await navigate(router, action)
    trace('B1.1', { phase, action, route: router.currentRoute.value.path, createCount: runs.create.mock.calls.length, body })
    expect(router.currentRoute.value.path).toBe('/requirements/new')
  })
  it('ordinary dirty decline preserves input and explicit discard remains allowed', async () => {
    const { view, router } = await newView(); vi.mocked(window.confirm).mockReturnValue(false)
    await router.push('/away'); expect(router.currentRoute.value.path).toBe('/requirements/new')
    expect((view.get('#workflow-requirement-title').element as HTMLInputElement).value).toBe('冻结标题')
    vi.mocked(window.confirm).mockReturnValue(true); await router.push('/away'); expect(router.currentRoute.value.path).toBe('/away'); expect(runs.create).not.toHaveBeenCalled()
  })
})
describe('B1.2 Library unresolved copy/archive navigation', () => {
  for (const action of ['copy', 'archive'] as const) for (const phase of ['sending', 'unknown']) it(`${action}/${phase} must block route leave`, async () => {
    const pending = deferred<never>(); flows[action].mockImplementation(() => pending.promise)
    const { view, router } = await routeView(WorkflowLibraryView, '/workflows')
    await view.get('[data-action-trigger]').trigger('click'); await click(view, action === 'copy' ? '复制' : '删除')
    if (phase === 'unknown') { pending.reject(new Error('lost acknowledgement')); await flushPromises() }
    const original = flows[action].mock.calls[0]; expect(original).toBeDefined()
    await router.push('/away'); await flushPromises(); trace('B1.2', { action, phase, route: router.currentRoute.value.path, request: original })
    expect(router.currentRoute.value.path).toBe('/workflows')
  })
  it('unknown copy keeps its original body/key while actual list filters change', async () => {
    flows.copy.mockRejectedValueOnce(new Error('lost')).mockResolvedValue({ ...receipt, state: 'ACTIVE' })
    const { view } = await routeView(WorkflowLibraryView, '/workflows')
    await view.get('[data-action-trigger]').trigger('click'); await click(view, '复制'); const original = flows.copy.mock.calls[0]
    await click(view, '我的流程'); await click(view, '重试原操作')
    trace('B1.2', { original, retry: flows.copy.mock.calls[1] }); expect(flows.copy.mock.calls[1]).toEqual(original)
  })
})
describe('B1.3 accepted New receipt survives failed handoff', () => {
  for (const failure of ['guard-false', 'cancelled', 'reject']) it(`${failure} retains receipt and offers navigation-only recovery`, async () => {
    const { view, router } = await newView(); router.onError(() => {})
    const entered = deferred<void>(), gate = deferred<boolean>()
    router.beforeEach(async to => {
      if (to.path !== '/requirements/created') return true
      if (failure === 'guard-false') return false
      if (failure === 'reject') throw new Error('fixture navigation failed')
      entered.resolve(); return gate.promise
    })
    runs.create.mockResolvedValue(receipt); await view.get('form').trigger('submit')
    if (failure === 'cancelled') { await entered.promise; await router.replace('/requirements/new?projectId=project&interruption=1'); gate.resolve(true) }
    await flushPromises(); trace('B1.3', { failure, route: router.currentRoute.value.fullPath, createCount: runs.create.mock.calls.length, text: view.text() })
    expect.soft(view.findAll('button').some(node => node.text() === '打开已创建的需求')).toBe(true)
    expect.soft(view.find('button[type="submit"]').exists()).toBe(false)
    expect(runs.create).toHaveBeenCalledOnce()
  })
})
describe('B1.4 disabled New UI and independent immutable owner controls', () => {
  it('sending then unknown disables all four real form fields/choices and explicit retry keeps original body', async () => {
    const pending = deferred<typeof receipt>()
    runs.create.mockImplementationOnce(() => pending.promise).mockResolvedValue(receipt)
    const { view } = await newView(); await view.get('form').trigger('submit'); await flushPromises()
    const original = runs.create.mock.calls[0]![0]
    const fieldSelectors = {
      title: '#workflow-requirement-title', objective: '#workflow-requirement-objective',
      project: 'button[aria-label="选择项目"]', template: 'button[aria-label="选择流程"]',
    }
    const assertFieldsLocked = () => {
      const fields = Object.fromEntries(Object.entries(fieldSelectors).map(([name, selector]) => [name, view.get(selector).element.matches(':disabled')]))
      expect(fields).toEqual({ title: true, objective: true, project: true, template: true })
      return fields
    }
    const sendingFields = assertFieldsLocked()
    expect(button(view, '重试创建').element.matches(':disabled')).toBe(true)
    expect(runs.create).toHaveBeenCalledOnce()
    pending.reject(new Error('lost')); await flushPromises()
    const unknownFields = assertFieldsLocked()
    expect(button(view, '重试创建').element.matches(':disabled')).toBe(false)
    expect(runs.create).toHaveBeenCalledOnce()
    // No synthetic input/change or select emit is sent through disabled controls.
    await click(view, '重试创建'); trace('B1.4', { sendingFields, unknownFields, original, retry: runs.create.mock.calls[1]![0] })
    expect(runs.create.mock.calls[1]![0]).toBe(original); expect(runs.create).toHaveBeenCalledTimes(2)
  })
  it('pure owner ignores external draft projection and accepted read failure never repeats mutation', async () => {
    let projection = { title: '原标题', templateRevision: 3 }
    const body = { ...projection, requestKey: 'w0-owner-original-key' }
    const write = vi.fn().mockRejectedValueOnce(new Error('unknown')).mockResolvedValue(receipt)
    const read = vi.fn().mockRejectedValueOnce(new Error('read failed')).mockResolvedValue(undefined)
    const operation = createAcknowledgedOperation('原操作', () => write(body), read, () => true)
    await expect(operation.execute()).rejects.toThrow('unknown'); projection = { title: '新草稿', templateRevision: 4 }
    await expect(operation.execute()).rejects.toThrow('read failed'); await operation.execute()
    trace('B1.4', { projection, writeBodies: write.mock.calls, readCount: read.mock.calls.length })
    expect(write.mock.calls).toEqual([[body], [body]]); expect(write).toHaveBeenCalledTimes(2); expect(read).toHaveBeenCalledTimes(2)
    expect(projection.title).toBe('新草稿')
  })
})

const source: WorkflowPublicationPreview = { requirementId: 'req', requirementVersion: 7, planRevision: 2, requirementState: 'COMPLETED', source: { nodeKey: 'work', nodeTitle: '开发', attemptId: 'original-attempt', ordinal: 1, attemptState: 'SUCCEEDED', outputName: 'code', outputTitle: '代码', createdAt: '', changedFiles: 1, totalFiles: 1 }, workspaceKind: 'GIT', sourceBranch: 'main', reference: { version: 1, snapshotId: 'original-snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'before', resultTree: 'after', added: 1, modified: 0, deleted: 0, totalBytes: 1, sha256: 'c'.repeat(64) }
async function panel(kind: string) {
  let view: VueWrapper, write: ReturnType<typeof vi.fn>
  if (kind === 'human' || kind === 'process') {
    if (kind === 'process') { runs.attempt.mockResolvedValue(attempt({ state: 'RUNNING', commandState: 'RUNNING', commandVersion: 19 })); runs.definition.mockResolvedValue({ ...commandPreset().node, id: 'review' }) }
    view = mount(WorkflowNodeRun, { props: { requirement: 'req', version: 7, node: requirement().graph.nodes[0]!, summary: { id: 'node', nodeKey: 'review', state: 'WAITING_INPUT', attemptCount: 1, latestAttemptId: 'run', version: 1, outcome: null } }, global: { stubs: { MarkdownDocument: true, CodeMergeEditor: true } } }); views.push(view); await flushPromises()
    write = kind === 'human' ? runs.complete : runs.commandAction
    if (kind === 'human') { await view.findAll('textarea')[0]!.setValue('原人工说明'); await view.findAll('textarea')[1]!.setValue('原交付'); await view.get('form').trigger('submit') }
    else await click(view, '停止节点')
  } else if (kind === 'candidate') {
    view = mount(WorkflowCandidates, { props: { requirement: 'req' } }); views.push(view); await flushPromises(); await view.get('.workflow-candidate-list button').trigger('click'); await flushPromises()
    await view.get('input').setValue('原退回原因'); write = runs.rejectCandidate; await click(view, '退回候选')
  } else if (kind === 'finish') {
    view = mount(WorkflowFinish, { props: { requirement: 'req', version: 7, state: 'RUNNING', disabled: false } }); views.push(view); await flushPromises(); await click(view, '提前结束需求'); await view.get('select').setValue('COMPLETED'); await view.get('textarea').setValue('原结束原因'); write = runs.finish; await view.get('form').trigger('submit')
  } else if (kind === 'commit') {
    view = mount(WorkflowPublicationCommit, { props: { requirement: 'req', preview: source } }); views.push(view); await flushPromises(); await click(view, '保存为本地提交'); await view.get('input').setValue('原提交说明'); write = vi.mocked(workflowPublication.confirm); await view.get('form').trigger('submit')
  } else if (kind === 'push') {
    vi.mocked(workflowPush.remotes).mockResolvedValue(['origin']); vi.mocked(workflowPush.preview).mockResolvedValue({ requirementId: 'req', publicationVersion: 3, remote: 'origin', url: 'https://example.test/repo', branch: 'results', commit: 'a'.repeat(40), remoteCommit: null, sha256: 'b'.repeat(64) })
    view = mount(WorkflowPush, { props: { requirement: 'req' } }); views.push(view); await flushPromises(); await click(view, '推送到远端'); await view.get('select').setValue('origin'); await click(view, '检查推送目标'); write = vi.mocked(workflowPush.confirm); await view.get('form').trigger('submit')
  } else {
    const checked = { requirementId: 'req', requirementVersion: 7, revision: 2, sourceSha256: source.sha256, directory: '/fixture', currentSha256: 'current', targetSha256: 'target', sha256: 'checked', added: 1, modified: 0, deleted: 0, preservedChanges: 0, conflictCount: 0, conflicts: [] }
    view = mount(WorkflowWriteback, { props: { requirement: 'req', source: { ...source, workspaceKind: 'DIRECT' }, checked } }); views.push(view); await flushPromises(); await click(view, '回填所选成果'); write = vi.mocked(workflowWriteback.confirm); await view.get('form').trigger('submit')
  }
  await flushPromises(); return { view, write }
}
function writes() { return [runs.complete, runs.commandAction, runs.rejectCandidate, runs.finish, vi.mocked(workflowPublication.confirm), vi.mocked(workflowPush.confirm), vi.mocked(workflowWriteback.confirm)] }
describe('B2.1 real nested command guards', () => {
  for (const kind of ['human', 'candidate', 'finish', 'commit', 'push', 'writeback']) for (const phase of ['sending', 'unknown']) it(`${kind}/${phase} refuses leaving actual command panel`, async () => {
    for (const write of writes()) write.mockImplementation((): Promise<never> => phase === 'sending' ? new Promise<never>(() => {}) : Promise.reject<never>(new Error('unknown')))
    const { view, write } = await panel(kind); expect(write).toHaveBeenCalledOnce()
    const canLeave = (view.vm as unknown as { canLeave(): boolean }).canLeave()
    trace('B2.1', { kind, phase, canLeave, calls: write.mock.calls })
    expect(canLeave).toBe(false)
  })
  it('actual Requirement parent refuses route leave while its Finish child is unknown', async () => {
    runs.finish.mockRejectedValue(new Error('unknown')); const { view, router } = await routeView(WorkflowRequirementView, '/requirements/req')
    await click(view, '更多工具'); await click(view, '提前结束需求'); const finish = view.getComponent(WorkflowFinish)
    await finish.get('select').setValue('COMPLETED'); await finish.get('textarea').setValue('原结束原因'); await finish.get('form').trigger('submit'); await flushPromises()
    expect(runs.finish).toHaveBeenCalledOnce(); await router.push('/away'); await flushPromises()
    trace('B2.1', { actualParent: true, route: router.currentRoute.value.path, calls: runs.finish.mock.calls })
    expect(router.currentRoute.value.path).toBe('/requirements/req')
  })
})
describe('B2.2 original process stop identity', () => {
  it('retains command version/attempt on explicit retry and blocks leaving unknown stop', async () => {
    runs.commandAction.mockRejectedValue(new Error('unknown')); const { view } = await panel('process')
    const original = runs.commandAction.mock.calls[0]; await click(view, '重试原操作')
    expect(runs.commandAction.mock.calls[1]).toEqual(original); expect(original!.slice(0, 4)).toEqual(['req', 'review', 'run', 'stop']); expect(original![4].expectedVersion).toBe(19)
    expect(runs.start).not.toHaveBeenCalled(); trace('B2.2', { calls: runs.commandAction.mock.calls, canLeave: (view.vm as unknown as { canLeave(): boolean }).canLeave() })
    expect((view.vm as unknown as { canLeave(): boolean }).canLeave()).toBe(false)
  })
})
describe('B2.3 accepted write/readback split', () => {
  it('actual Finish child retains accepted readback and only rereads on recovery', async () => {
    runs.finish.mockResolvedValue({ ...receipt, id: 'req', version: 8, state: 'STOPPING' })
    runs.finishStatus.mockResolvedValueOnce({ requirementId: 'req', state: 'RUNNING', version: 7, intent: null, pending: { attempts: 0, resources: 0 } }).mockRejectedValueOnce(new Error('accepted readback unavailable'))
    const current = await panel('finish')
    expect(current.view.text()).toContain('刷新操作结果'); const canLeave = (current.view.vm as unknown as { canLeave(): boolean }).canLeave()
    expect.soft(canLeave).toBe(false)
    await click(current.view, '刷新操作结果'); trace('B2.3', { canLeave, mutationCount: runs.finish.mock.calls.length, reads: runs.finishStatus.mock.calls.length })
    expect(runs.finish).toHaveBeenCalledOnce()
  })
})
