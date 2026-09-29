import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { workflowRuns } from '@/api/workflowRuns'
import { workflowApi } from '@/api/workflow'
import { preset } from '@/components/workflow/workflowTestFixtures'
import WorkflowRequirementView from './WorkflowRequirementView.vue'
import { candidate, execution, requirement } from '@/components/workflow/workflowRunTestFixtures'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { get: vi.fn(), previewTemplate: vi.fn(), saveTemplate: vi.fn(), finishStatus: vi.fn(), finish: vi.fn(), execution: vi.fn(), confirm: vi.fn(), start: vi.fn(), pause: vi.fn(), applyPlan: vi.fn(), applyCandidate: vi.fn(), candidates: vi.fn(), candidate: vi.fn(), rejectCandidate: vi.fn(), revise: vi.fn(), layout: vi.fn() } }))
vi.mock('@/api/workflow', () => ({ workflowApi: { presets: vi.fn(), preset: vi.fn() } }))
const api = vi.mocked(workflowRuns); let wrapper: VueWrapper | undefined
beforeEach(() => { vi.resetAllMocks(); vi.spyOn(window, 'confirm').mockReturnValue(true); api.finishStatus.mockResolvedValue({ requirementId: 'req', state: 'PLANNING', version: 7, intent: null, pending: { attempts: 0, resources: 0 } }); api.get.mockResolvedValue(requirement()); api.execution.mockResolvedValue(execution()) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })
async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/workflows/:id', component: { template: '<div />' } }, { path: '/requirements/:id', component: WorkflowRequirementView }, { path: '/requirements', component: { template: '<div />' } }] })
  await router.push('/requirements/req'); await router.isReady(); wrapper = mount(RouterView, { global: { plugins: [router], stubs: { Icon: true, WorkflowRolePicker: true, WorkflowModelChoice: true, WorkflowNodeRun: { template: '<aside />', methods: { canLeave: () => true } } } } }); await flushPromises(); return router
}
const button = (name: string) => wrapper!.findAll('button').find(node => node.text() === name)!
describe('requirement canvas', () => {
  it('saves a captured draft independently and protects an uncertain template operation during navigation', async () => {
    const router = await render(); await button('人工检查').trigger('click')
    api.previewTemplate.mockImplementation(async (_id, selection) => ({ ...selection, sourceRevision: 2, initialAvailable: false, fixedPlanningNodes: [], sha256: 'fixed', diagnostics: [] }))
    await button('另存为流程模板').trigger('click'); await flushPromises()
    expect(api.previewTemplate.mock.calls[0]![1].graph.nodes).toHaveLength(2)
    api.saveTemplate.mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ id: 'saved-template', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' })
    await button('确认保存为新流程').trigger('click'); await flushPromises(); await router.push('/requirements')
    expect(router.currentRoute.value.path).toBe('/requirements/req'); expect(api.revise).not.toHaveBeenCalled(); expect(api.start).not.toHaveBeenCalled()
    await button('重试原保存操作').trigger('click'); await flushPromises(); expect(api.saveTemplate.mock.calls[1]).toEqual(api.saveTemplate.mock.calls[0])
    await button('返回任务画布').trigger('click'); expect(wrapper!.text()).toContain('有未保存修改'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2)
  })

  it('adds a preset to the planning draft without starting or saving execution', async () => {
    vi.mocked(workflowApi.presets).mockResolvedValue({ items: [preset()], nextCursor: null }); vi.mocked(workflowApi.preset).mockResolvedValue(preset())
    await render(); await button('预设工作模块').trigger('click'); await flushPromises(); await wrapper!.get('.workflow-preset-list button').trigger('click'); await flushPromises()
    await wrapper!.get('.workflow-preset-detail select').setValue('NODE|review|result'); await button('添加到画布').trigger('click')
    expect(wrapper!.findAll('.workflow-node')).toHaveLength(2); expect(wrapper!.findAll('.workflow-wire')).toHaveLength(1); expect(wrapper!.find('.workflow-presets').exists()).toBe(false)
    expect(api.start).not.toHaveBeenCalled(); expect(api.revise).not.toHaveBeenCalled(); expect(api.applyPlan).not.toHaveBeenCalled()
    await wrapper!.get('[aria-label="撤销计划修改"]').trigger('click'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(1)
  })
  it('confirmation does not start execution and preserves unsaved presentation', async () => {
    await render(); await button('自动排列').trigger('click'); api.get.mockResolvedValue(requirement({ state: 'PENDING_START', version: 4 })); api.execution.mockResolvedValue(execution('PENDING_START'))
    await button('确认计划').trigger('click'); await flushPromises(); expect(api.confirm).toHaveBeenCalledOnce(); expect(api.start).not.toHaveBeenCalled(); expect(wrapper!.text()).toContain('有未保存修改'); expect(button('连续执行')).toBeDefined()
  })
  it('requires explicit acknowledgement after reopening a checkpoint and freezes the original command on timeout', async () => {
    const snapshot = execution('PAUSED'); snapshot.control = { ...snapshot.control, configured: true, mode: 'CONTINUOUS', state: 'WAITING', version: 7, controlVersion: 4, reasonCode: 'WORKFLOW_CHECKPOINT', checkpoints: [{ attemptId: 'done', requirementId: 'req', nodeKey: 'review', createdAt: '', acknowledgedAt: null }] }
    api.get.mockResolvedValue(requirement({ state: 'PAUSED' })); api.execution.mockResolvedValue(snapshot); await render()
    expect(button('连续执行').attributes('disabled')).toBeDefined(); await wrapper!.get('input[type=checkbox]').setValue(true)
    api.start.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue(snapshot.control)
    await button('连续执行').trigger('click'); await flushPromises(); expect(api.start.mock.calls[0]![1]).toMatchObject({ expectedVersion: 7, expectedControlVersion: 4, checkpointAttempts: ['done'], inputs: null })
    await button('重试原操作').trigger('click'); await flushPromises(); expect(api.start.mock.calls[0]).toEqual(api.start.mock.calls[1])
  })
  it('does not repeat an accepted execution when status readback fails', async () => {
    api.get.mockResolvedValue(requirement({ state: 'PENDING_START' })); api.execution.mockResolvedValue(execution('PENDING_START')); await render()
    api.start.mockResolvedValue(execution('RUNNING').control); api.execution.mockRejectedValueOnce(new Error('offline'))
    await button('连续执行').trigger('click'); await flushPromises(); expect(button('刷新操作结果')).toBeDefined()
    await button('刷新操作结果').trigger('click'); await flushPromises(); expect(api.start).toHaveBeenCalledTimes(1)
  })
  it('sends SINGLE and UNTIL with the selected target instead of the entire graph', async () => {
    api.get.mockResolvedValue(requirement({ state: 'PENDING_START' })); api.execution.mockResolvedValue(execution('PENDING_START')); await render(); await wrapper!.get('.workflow-node').trigger('click')
    await button('执行所选节点').trigger('click'); await flushPromises(); expect(api.start.mock.calls[0]![1]).toMatchObject({ mode: 'SINGLE', targetKey: 'review' })
    await button('运行至所选节点').trigger('click'); await flushPromises(); expect(api.start.mock.calls[1]![1]).toMatchObject({ mode: 'UNTIL', targetKey: 'review' })
  })
  it('retains a conflicting local plan and disables execution after observing a different revision', async () => {
    await render(); await button('人工检查').trigger('click'); const changed = execution(); changed.execution.revision = 3; api.execution.mockResolvedValue(changed)
    await button('刷新状态').trigger('click'); await flushPromises(); expect(wrapper!.text()).toContain('当前草稿已保留'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2); expect(button('确认计划').attributes('disabled')).toBeDefined()
  })
  it('keeps the draft when the user rejects navigation away', async () => {
    const router = await render(); await button('人工检查').trigger('click'); vi.mocked(window.confirm).mockReturnValue(false); await router.push('/requirements'); expect(router.currentRoute.value.path).toBe('/requirements/req')
  })
  it('pauses dispatch before editing, protects the running definition, and applies with the latest version', async () => {
    const value = execution('RUNNING'); value.execution.nodes = [{ id: 'run', nodeKey: 'review', state: 'ACTIVE', attemptCount: 1, latestAttemptId: 'attempt', version: 1, outcome: null }]; value.control = { ...value.control, configured: true, state: 'ACTIVE', mode: 'CONTINUOUS' }
    api.get.mockResolvedValue(requirement({ state: 'RUNNING' })); api.execution.mockResolvedValue(value); await render()
    api.pause.mockImplementation(async () => { value.execution.version = 8; value.execution.state = 'PAUSED'; value.control.state = 'PAUSED'; return value.control })
    await button('调整后续计划').trigger('click'); await flushPromises(); expect(api.pause).toHaveBeenCalledOnce(); expect(button('连续执行').attributes('disabled')).toBeDefined()
    await wrapper!.get('.workflow-node').trigger('click'); expect(wrapper!.get('aside[aria-label="节点设置"] fieldset').attributes('disabled')).toBeDefined()
    await button('人工检查').trigger('click'); const applied = requirement({ state: 'PAUSED', revision: 3, version: 9 }); api.applyPlan.mockResolvedValue({ id: 'req', revision: 3, version: 9, layoutVersion: 4, state: 'PAUSED' }); api.layout.mockResolvedValue({ id: 'req', revision: 3, version: 9, layoutVersion: 5, state: 'PAUSED' }); api.get.mockResolvedValue(applied); value.execution.revision = 3
    await button('应用计划调整').trigger('click'); await flushPromises(); expect(api.applyPlan.mock.calls[0]![1]).toMatchObject({ expectedVersion: 8, expectedRevision: 2 }); expect(api.start).not.toHaveBeenCalled()
  })
  it('previews and edits a candidate without applying until the user explicitly confirms', async () => {
    const value = execution('PAUSED'), draft = candidate(); value.control.reasonCode = 'WORKFLOW_PLAN_REVIEW_REQUIRED'; value.execution.nodes = [{ id: 'source', nodeKey: 'review', state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: draft.attemptId, version: 2, outcome: null }]
    api.get.mockResolvedValue(requirement({ state: 'PAUSED' })); api.execution.mockResolvedValue(value); api.candidates.mockResolvedValue({ items: [{ ...draft, sourceState: 'SUCCEEDED', createdAt: '' }], nextCursor: null }); api.candidate.mockResolvedValue(draft); await render()
    await button('候选计划').trigger('click'); await flushPromises(); await wrapper!.get('.workflow-candidate-list button').trigger('click'); await flushPromises(); await button('在画布中查看').trigger('click'); await flushPromises()
    expect(wrapper!.text()).toContain('尚未生效'); expect(api.applyCandidate).not.toHaveBeenCalled(); expect(button('连续执行').attributes('disabled')).toBeDefined()
    await wrapper!.get('.workflow-node[aria-label="候选后续检查，人工节点"]').trigger('click'); await wrapper!.get('aside[aria-label="节点设置"] textarea').setValue('检查用户修改后的成果')
    api.applyCandidate.mockResolvedValue({ id: 'req', revision: 3, version: 8, layoutVersion: 4, state: 'PAUSED' }); api.layout.mockResolvedValue({ id: 'req', revision: 3, version: 8, layoutVersion: 5, state: 'PAUSED' }); api.get.mockResolvedValue(requirement({ state: 'PAUSED', revision: 3 })); value.execution.revision = 3
    await button('确认并应用候选计划').trigger('click'); await flushPromises(); expect(api.applyCandidate).toHaveBeenCalledOnce(); expect(api.applyCandidate.mock.calls[0]![2].graph.nodes[1]!.task).toBe('检查用户修改后的成果'); expect(api.start).not.toHaveBeenCalled()
  })
  it('keeps historical or stale candidates read-only and returns to the effective graph on exit', async () => {
    const draft = candidate({ stale: true }); api.get.mockResolvedValue(requirement({ state: 'PAUSED' })); api.execution.mockResolvedValue(execution('PAUSED')); api.candidates.mockResolvedValue({ items: [{ ...draft, sourceState: 'SUCCEEDED', createdAt: '' }] }); api.candidate.mockResolvedValue(draft); await render()
    await button('候选计划').trigger('click'); await flushPromises(); await wrapper!.get('.workflow-candidate-list button').trigger('click'); await flushPromises(); await button('在画布中查看').trigger('click'); await flushPromises()
    expect(wrapper!.text()).toContain('只读预览'); expect(button('确认并应用候选计划')).toBeUndefined(); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2); await button('退出候选预览').trigger('click'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(1); expect(api.applyCandidate).not.toHaveBeenCalled()
  })

  it('allows preview during source cleanup but requires successful source completion before applying', async () => {
    const draft = candidate({ sourceCompleted: false }), value = execution('PAUSED'); api.get.mockResolvedValue(requirement({ state: 'PAUSED' })); api.execution.mockResolvedValue(value); api.candidates.mockResolvedValue({ items: [{ ...draft, sourceState: 'RUNNING', createdAt: '' }] }); api.candidate.mockResolvedValue(draft); await render()
    await button('候选计划').trigger('click'); await flushPromises(); await wrapper!.get('.workflow-candidate-list button').trigger('click'); await flushPromises(); await button('在画布中查看').trigger('click'); await flushPromises()
    expect(button('确认并应用候选计划').attributes('disabled')).toBeDefined(); value.execution.nodes = [{ id: 'source', nodeKey: 'review', state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: draft.attemptId, version: 2, outcome: null }]; api.execution.mockResolvedValue(structuredClone(value))
    await button('刷新状态').trigger('click'); await flushPromises(); expect(button('确认并应用候选计划').attributes('disabled')).toBeUndefined(); expect(api.applyCandidate).not.toHaveBeenCalled()
  })

})
