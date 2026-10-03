/** Frozen W0 predicates, exercised through actual React pages/panels and sole Vue history. */
import { act, fireEvent, render } from '@testing-library/react'
import { createPinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { expect, vi } from 'vitest'
import W2RouteBridge from '@/migration/W2RouteBridge.vue'
import { semanticName } from '@/foundation/semanticRegistry'
import { workflowRuns } from '@/api/workflowRuns'
import { workflowPublication } from '@/api/workflowPublication'
import { workflowPush } from '@/api/workflowPush'
import { workflowWriteback } from '@/api/workflowWriteback'
import { foundationDOM } from '@/pages/w2/workflow/page.test-support'
import { requirementFixture, requirementFrame, requirement, attempt, execution, deferred, publicationPreview, pushPreview, writebackPreview } from './test-support'
import { createNodeController } from './nodeController'
import { createFinishController } from './finishController'
import { createCandidatesController } from './candidatesController'
import { createPublicationController } from './publicationController'
import { NodeRun } from './NodeRun'
import { FinishPanel } from './Finish'
import { CandidatesPanel } from './Candidates'
import { PublicationPanel } from './Publication'
import type { WorkflowReceipt } from '@/types/domain'

type Mode = { group: 'B1.1' | 'B1.3' | 'B1.4' | 'B2.1' | 'B2.2' | 'B2.3'; phase?: 'SENDING' | 'UNKNOWN'; action?: 'push' | 'replace' | 'back'; failure?: 'guard-false' | 'cancelled' | 'reject'; kind?: 'human' | 'candidate' | 'finish' | 'commit' | 'push' | 'writeback' | 'parent-finish'; variant?: 'dirty' | 'fields' }
const created: WorkflowReceipt = { id: 'created', revision: 1, version: 1, layoutVersion: 0, state: 'PLANNING' }
async function settle() { await act(async () => { for (let i = 0; i < 8; i++) await flushPromises() }) }
function element(root: HTMLElement, name: string): HTMLButtonElement { const value = [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.getAttribute('aria-label') === name && !button.closest('[hidden]')); if (!value) throw new Error(`W0 actual React action is absent: ${name}`); return value }
async function click(root: HTMLElement, key: Parameters<typeof semanticName>[0], target?: string) { await act(async () => { element(root, semanticName(key, target)).click(); await flushPromises() }); await settle() }
async function fill(root: HTMLElement, id: string, value: string) { const input = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(`#${id}`); expect(input).toBeTruthy(); await act(async () => { fireEvent.change(input!, { target: { value } }); await flushPromises() }) }
async function routePage(path: string) {
  foundationDOM()
  vi.mocked(workflowRuns.project).mockResolvedValue({ id: 'project', name: '测试项目', createdAt: '' })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/previous', component: defineComponent(() => () => h('p', '原入口')) }, { path: '/away', component: defineComponent(() => () => h('p', '其他页面')) }, { path: '/requirements/new', component: W2RouteBridge }, { path: '/requirements/:id', component: W2RouteBridge }, { path: '/created-view', component: defineComponent(() => () => h('p', '已创建需求')) }] })
  await router.push('/previous'); await router.push(path); await router.isReady()
  let root!: VueWrapper
  await act(async () => { root = mount(defineComponent({ setup: () => () => h(RouterView) }), { attachTo: document.body, global: { plugins: [createPinia(), router] } }); await flushPromises() })
  await act(async () => { await vi.dynamicImportSettled(); await flushPromises() }); await settle()
  return { root, router, host: root.element as HTMLElement, close: async () => { await act(async () => { root.unmount(); await flushPromises() }) } }
}
export async function requirementW0Contract(mode: Mode, context: { proof?: (value: Record<string, unknown>) => void } = {}) {
  if (mode.group.startsWith('B1')) {
    const write = vi.mocked(workflowRuns.create), pending = deferred<WorkflowReceipt>()
    write.mockReturnValue(pending.promise)
    const page = await routePage('/requirements/new?projectId=project')
    const { host, router } = page
    let sendingFields: Record<string, boolean> | undefined, sendingRetry: { disabled: boolean; count: number } | undefined
    try {
      await fill(host, 'workflow-requirement-title', '原标题'); await fill(host, 'workflow-requirement-objective', '原目标')
      if (mode.variant === 'dirty') {
        let leave!: ReturnType<typeof router.push>
        await act(async () => { leave = router.push('/away'); await flushPromises() }); await settle()
        const dialog = host.querySelector<HTMLElement>('[role="dialog"]'); expect(dialog).toBeTruthy()
        await click(dialog!, 'ui.stay'); await leave; expect(router.currentRoute.value.path).toBe('/requirements/new')
        expect((host.querySelector('#workflow-requirement-title') as HTMLInputElement).value).toBe('原标题')
        await act(async () => { leave = router.push('/away'); await flushPromises() }); await settle()
        await click(host.querySelector<HTMLElement>('[role="dialog"]')!, 'ui.discardChanges'); await leave
        expect(router.currentRoute.value.path).toBe('/away'); expect(write).not.toHaveBeenCalled()
        context.proof?.({ route: router.currentRoute.value.fullPath, preserved: '原标题', createCount: 0 }); return
      }
      let guardFailure = mode.failure, enter = deferred<void>()
      const remove = router.beforeEach(async to => { if (to.path !== '/requirements/created') return true; if (guardFailure === 'guard-false') return false; if (guardFailure === 'reject') throw new Error('navigation rejected'); if (guardFailure === 'cancelled') { await enter.promise; return true }; return true })
      router.onError(() => {})
      await click(host, 'workflow.createRequirement'); expect(write).toHaveBeenCalledTimes(1)
      const original = write.mock.calls[0]![0]
      expect(original).toEqual({ requestKey: expect.any(String), templateId: 'example', templateRevision: 3, projectId: 'project', title: '原标题', objective: '原目标' })
      if (mode.group === 'B1.3') {
        pending.resolve(created); await settle()
        if (mode.failure === 'cancelled') { await act(async () => { void router.replace('/requirements/new?projectId=project&interruption=1'); await flushPromises() }); enter.resolve(); await settle() }
        expect(router.currentRoute.value.path).toBe('/requirements/new'); expect(host.querySelector('[data-operation-phase="ACCEPTED_READBACK"]')).toBeTruthy()
        expect(element(host, semanticName('workflow.openCreatedRequirement'))).toBeTruthy(); expect(write).toHaveBeenCalledTimes(1)
        guardFailure = undefined; remove()
        await click(host, 'workflow.openCreatedRequirement'); expect(router.currentRoute.value.path).toBe('/requirements/created'); expect(write).toHaveBeenCalledTimes(1)
        context.proof?.({ failure: mode.failure, original, createCount: write.mock.calls.length, route: router.currentRoute.value.path }); return
      }
      if (mode.variant === 'fields') {
        expect(host.querySelector('[data-operation-phase="SENDING"]')).toBeTruthy()
        for (const input of [...host.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('#workflow-requirement-title,#workflow-requirement-objective')]) expect(input.closest('fieldset')!.disabled).toBe(true)
        for (const key of ['workflow.chooseProject', 'workflow.chooseTemplate'] as const) expect(element(host, semanticName(key)).disabled).toBe(true)
        sendingFields = { title: host.querySelector<HTMLInputElement>('#workflow-requirement-title')!.closest('fieldset')!.disabled, objective: host.querySelector<HTMLTextAreaElement>('#workflow-requirement-objective')!.closest('fieldset')!.disabled, project: element(host, semanticName('workflow.chooseProject')).disabled, workflow: element(host, semanticName('workflow.chooseTemplate')).disabled }
        sendingRetry = { disabled: element(host, semanticName('workflow.retryCreate')).disabled, count: write.mock.calls.length }; expect(sendingFields).toEqual({ title: true, objective: true, project: true, workflow: true }); expect(sendingRetry).toEqual({ disabled: true, count: 1 })
      }
      if (mode.phase === 'UNKNOWN' || mode.variant === 'fields') { pending.reject(new Error('lost acknowledgement')); await settle() }
      const phase = mode.variant === 'fields' ? 'UNKNOWN' : mode.phase!
      expect(host.querySelector(`[data-operation-phase="${phase}"]`)).toBeTruthy()
      if (mode.variant === 'fields') {
        for (const input of [...host.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('#workflow-requirement-title,#workflow-requirement-objective')]) expect(input.closest('fieldset')!.disabled).toBe(true)
        for (const key of ['workflow.chooseProject', 'workflow.chooseTemplate'] as const) expect(element(host, semanticName(key)).disabled).toBe(true)
        expect(element(host, semanticName('workflow.retryCreate')).disabled).toBe(false); expect(write).toHaveBeenCalledTimes(1)
        write.mockRejectedValue(new Error('still unknown')); await click(host, 'workflow.retryCreate')
        expect(write.mock.calls[1]![0]).toBe(original); expect(write.mock.calls[1]![0]).toEqual(original)
      } else {
        await act(async () => { if (mode.action === 'back') router.back(); else await router[mode.action ?? 'push']('/away'); await flushPromises() }); await settle()
        expect(router.currentRoute.value.path).toBe('/requirements/new'); expect(write).toHaveBeenCalledTimes(1)
      }
      context.proof?.({ ...mode, sendingFields, sendingRetry, unknownFields: mode.variant === 'fields' ? { title: true, objective: true, project: true, workflow: true } : undefined, unknownRetry: mode.variant === 'fields' ? { disabled: false, countBeforeRetry: 1, countAfterRetry: write.mock.calls.length } : undefined, route: router.currentRoute.value.path, original, requests: write.mock.calls.map(call => call[0]) })
    } finally { await page.close() }
    return
  }
  if (mode.kind === 'parent-finish') {
    vi.mocked(workflowRuns.get).mockResolvedValue(requirement({ state: 'RUNNING', version: 7 })); const current = execution('RUNNING'); current.execution.version = 7; current.control.version = 7; vi.mocked(workflowRuns.execution).mockResolvedValue(current)
    vi.mocked(workflowRuns.finish).mockRejectedValue(new Error('lost acknowledgement'))
    const page = await routePage('/requirements/req')
    try {
      expect(page.host.querySelector('h1')?.textContent).toBe(requirement().title)
      await click(page.host, 'workflow.moreTools'); await click(page.host, 'workflow.finish')
      const reason = [...page.host.querySelectorAll<HTMLTextAreaElement>('textarea')].find(element => element.closest('label')?.textContent?.includes('结束原因')); expect(reason).toBeTruthy()
      await act(async () => { fireEvent.change(reason!, { target: { value: '原提前结束原因' } }); await flushPromises() })
      const result = page.host.querySelector<HTMLSelectElement>('select[aria-label="结束结果"]')!; await act(async () => fireEvent.change(result, { target: { value: 'COMPLETED' } }))
      await click(page.host, 'workflow.confirmFinish'); expect(page.host.querySelector('[data-operation-phase="UNKNOWN"]')).toBeTruthy()
      await act(async () => { await page.router.push('/away'); await flushPromises() }); await settle()
      expect(page.router.currentRoute.value.path).toBe('/requirements/req'); expect(workflowRuns.finish).toHaveBeenCalledTimes(1)
      expect(vi.mocked(workflowRuns.finish).mock.calls[0]).toEqual(['req', { requestKey: expect.any(String), expectedVersion: 7, target: 'COMPLETED', reason: '原提前结束原因' }])
      context.proof?.({ route: page.router.currentRoute.value.path, body: vi.mocked(workflowRuns.finish).mock.calls[0]![1], phase: 'UNKNOWN' })
    } finally { await page.close() }; return
  }
  foundationDOM(); const fixture = requirementFixture(), pending = deferred<never>(), nodeDef = requirement().graph.nodes[0]!
  let nodeOwner: ReturnType<typeof createNodeController> | undefined, finishOwner: ReturnType<typeof createFinishController> | undefined, candidateOwner: ReturnType<typeof createCandidatesController> | undefined, publicationOwner: ReturnType<typeof createPublicationController> | undefined
  const owners: Array<{ canLeave(): { kind: string }; retire(forced: boolean): unknown; getSnapshot(): { command: { phase: string } }; operationIdentity(): unknown }> = []
  let host: ReturnType<typeof render> | undefined
  try {
    if (mode.group === 'B2.2' || mode.kind === 'human') {
      if (mode.group === 'B2.2') vi.mocked(workflowRuns.attempt).mockResolvedValue(attempt({ state: 'RUNNING', commandState: 'RUNNING', commandVersion: 19, modelVersion: 999 }))
      nodeOwner = createNodeController('req', { version: 7, node: nodeDef, summary: { id: nodeDef.id, nodeKey: nodeDef.id, state: 'WAITING_INPUT', attemptCount: 1, latestAttemptId: 'run', version: 1, outcome: null } }); owners.push(nodeOwner)
      host = render(requirementFrame(<NodeRun page={fixture.props} controller={nodeOwner} node={nodeDef} summary={{ id: 'review', nodeKey: 'review', state: 'WAITING_INPUT', attemptCount: 1, latestAttemptId: 'run', version: 1, outcome: null }} version={7} />)); await settle()
      if (mode.group === 'B2.2') {
        vi.mocked(workflowRuns.commandAction).mockRejectedValue(new Error('lost acknowledgement'))
        await click(host.container, 'workflow.nodeStop'); await click(host.container.querySelector<HTMLElement>('[role="dialog"]')!, 'workflow.nodeStop')
        expect(workflowRuns.commandAction).toHaveBeenCalledWith('req', 'review', 'run', 'stop', { requestKey: expect.any(String), expectedVersion: 19 })
        const original = vi.mocked(workflowRuns.commandAction).mock.calls[0]![4]
        await click(host.container, 'receipt.retryOriginal')
        expect(vi.mocked(workflowRuns.commandAction).mock.calls[1]![4]).toBe(original); expect(workflowRuns.start).not.toHaveBeenCalled(); expect(nodeOwner.canLeave().kind).toBe('BLOCK')
        context.proof?.({ attempt: 'run', commandVersion: 19, original, calls: vi.mocked(workflowRuns.commandAction).mock.calls }); return
      }
      await act(async () => { nodeOwner!.change({ humanSummary: '原人工说明', values: { result: '原交付' } }) })
      vi.mocked(workflowRuns.complete).mockReturnValue(pending.promise); await click(host.container, 'workflow.completeHuman')
    } else if (mode.kind === 'candidate') {
      candidateOwner = createCandidatesController('req'); owners.push(candidateOwner)
      host = render(requirementFrame(<CandidatesPanel page={fixture.props} controller={candidateOwner} visible onClose={() => {}} />)); await act(async () => { await candidateOwner!.read('candidate'); candidateOwner!.changeReason('原退回原因') })
      vi.mocked(workflowRuns.rejectCandidate).mockReturnValue(pending.promise); await click(host.container, 'workflow.rejectCandidate'); await click(host.container.querySelector<HTMLElement>('[role="dialog"]')!, 'workflow.rejectCandidate')
    } else if (mode.kind === 'finish' || mode.group === 'B2.3') {
      finishOwner = createFinishController('req', { version: 7, state: 'RUNNING' }); owners.push(finishOwner)
      host = render(requirementFrame(<FinishPanel page={fixture.props} controller={finishOwner} version={7} state="RUNNING" />)); await settle()
      await act(async () => { finishOwner!.show(); finishOwner!.change({ target: 'COMPLETED', reason: '原提前结束原因' }) })
      if (mode.group === 'B2.3') {
        vi.mocked(workflowRuns.finish).mockResolvedValue({ id: 'req', revision: 2, version: 8, layoutVersion: 4, state: 'STOPPING' }); vi.mocked(workflowRuns.finishStatus).mockRejectedValueOnce(new Error('read failed'))
        await click(host.container, 'workflow.confirmFinish'); expect(finishOwner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(finishOwner.canLeave().kind).toBe('BLOCK')
        const before = vi.mocked(workflowRuns.finishStatus).mock.calls.length
        await click(host.container, 'receipt.readOriginal')
        expect(workflowRuns.finish).toHaveBeenCalledTimes(1); expect(workflowRuns.finishStatus).toHaveBeenCalledTimes(before + 1)
        context.proof?.({ writeCount: 1, readBefore: before, readAfter: vi.mocked(workflowRuns.finishStatus).mock.calls.length, original: vi.mocked(workflowRuns.finish).mock.calls[0]![1] }); return
      }
      vi.mocked(workflowRuns.finish).mockReturnValue(pending.promise); await click(host.container, 'workflow.confirmFinish')
    } else {
      publicationOwner = createPublicationController('req', 2); owners.push(publicationOwner)
      host = render(requirementFrame(<PublicationPanel page={fixture.props} controller={publicationOwner} revision={2} visible onClose={() => {}} />))
      // These are actual authority DTOs supplied to the owner; no business write is
      // performed to manufacture the read-only preview needed by the frozen case.
      await act(async () => { publicationOwner!.patch({ preview: publicationPreview(mode.kind === 'writeback' ? 'DIRECT' : 'GIT'), commitLoaded: true, commitOpen: true, message: '原提交说明', pushLoaded: true, pushOpen: true, pushPreview, remotes: ['origin'], remote: 'origin', writebackLoaded: true, writebackOpen: true, checked: writebackPreview, ...(mode.kind === 'push' ? { commit: { requirementId: 'req', version: 3, state: 'COMMITTED' as const, nodeTitle: '实施节点', outputTitle: '代码', attemptState: 'SUCCEEDED', branch: 'results', message: '原提交说明', commit: 'commit', createdAt: '' } } : {}) }) })
      if (mode.kind === 'commit') vi.mocked(workflowPublication.confirm).mockReturnValue(pending.promise)
      if (mode.kind === 'push') vi.mocked(workflowPush.confirm).mockReturnValue(pending.promise)
      if (mode.kind === 'writeback') vi.mocked(workflowWriteback.confirm).mockReturnValue(pending.promise)
      await click(host.container, mode.kind === 'push' ? 'workflow.pushConfirm' : mode.kind === 'writeback' ? 'workflow.writebackConfirm' : 'workflow.commitConfirm')
    }
    const owner = owners[0]!
    if (mode.phase === 'UNKNOWN') { pending.reject(new Error('lost acknowledgement')); await settle() }
    expect(owner.getSnapshot().command.phase).toBe(mode.phase); expect(owner.canLeave().kind).toBe('BLOCK'); expect(host.container.querySelector(`[data-operation-phase="${mode.phase}"]`)).toBeTruthy()
    expect([...fixture.guards].some(guard => guard().kind === 'BLOCK')).toBe(true)
    const request = mode.kind === 'human' ? vi.mocked(workflowRuns.complete).mock.calls[0] : mode.kind === 'candidate' ? vi.mocked(workflowRuns.rejectCandidate).mock.calls[0] : mode.kind === 'finish' ? vi.mocked(workflowRuns.finish).mock.calls[0] : mode.kind === 'commit' ? vi.mocked(workflowPublication.confirm).mock.calls[0] : mode.kind === 'push' ? vi.mocked(workflowPush.confirm).mock.calls[0] : vi.mocked(workflowWriteback.confirm).mock.calls[0]
    if (mode.kind === 'human') expect(request).toEqual(['req', 'review', { requestKey: expect.any(String), expectedVersion: 7, attemptId: 'run', expectedAttemptVersion: 1, delivery: { summary: '原人工说明', outcome: null, outputs: { result: { kind: 'TEXT', content: '原交付' } } } }])
    if (mode.kind === 'candidate') expect(request).toEqual(['req', 'candidate', { requestKey: expect.any(String), expectedCandidateVersion: 0, reason: '原退回原因' }])
    if (mode.kind === 'finish') expect(request).toEqual(['req', { requestKey: expect.any(String), expectedVersion: 7, target: 'COMPLETED', reason: '原提前结束原因' }])
    if (mode.kind === 'commit') expect(request).toEqual(['req', { requestKey: expect.any(String), expectedVersion: 7, revision: 2, node: 'work', attempt: 'original-attempt', output: 'code', previewSha256: 'c'.repeat(64), message: '原提交说明' }])
    if (mode.kind === 'push') expect(request).toEqual(['req', { requestKey: expect.any(String), expectedVersion: 3, remote: 'origin', previewSha256: 'push-sha' }])
    if (mode.kind === 'writeback') expect(request).toEqual(['req', { requestKey: expect.any(String), expectedVersion: 7, selection: { revision: 2, node: 'work', attempt: 'original-attempt', output: 'code', sourceSha256: 'c'.repeat(64) }, previewSha256: 'writeback-sha' }])
    context.proof?.({ kind: mode.kind, phase: mode.phase, request, identity: owner.operationIdentity() })
  } finally { host?.unmount(); fixture.dispose(); owners.forEach(owner => owner.retire(true)) }
}
