import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { workflowRuns } from '@/api/workflowRuns'
import { execution, requirement } from '@/components/workflow/workflowRunTestFixtures'
import type { WorkflowExecution, WorkflowLayout, WorkflowReceipt, WorkflowRequirement } from '@/types/domain'
import WorkflowRequirementView from './WorkflowRequirementView.vue'

vi.mock('@/api/workflowRuns', () => ({ workflowRuns: {
  get: vi.fn(), execution: vi.fn(), confirm: vi.fn(), start: vi.fn(), pause: vi.fn(), cancel: vi.fn(),
  revise: vi.fn(), applyPlan: vi.fn(), applyCandidate: vi.fn(), layout: vi.fn(),
} }))

const api = vi.mocked(workflowRuns)
const ScopeCanvas = defineComponent({
  name: 'ScopeCanvas', props: ['graph', 'layout', 'readonly', 'movable'], emits: ['layout'],
  template: '<div aria-label="流程画布"><span v-for="node in graph.nodes" :key="node.id">{{ node.title }}</span></div>',
  methods: { focus() {}, reveal() {} },
})
const leaveable = { template: '<div />', methods: { canLeave: () => true } }
let wrapper: VueWrapper | undefined
let values: Record<string, WorkflowRequirement>, snapshots: Record<string, WorkflowExecution>

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
function receipt(id: string): WorkflowReceipt {
  const value = values[id]!
  return { id, revision: value.revision, version: value.version, layoutVersion: value.layoutVersion, state: value.state }
}
function configure(id: string, state: WorkflowRequirement['state'], active = false) {
  const value = requirement({ id, title: `需求 ${id}`, state })
  value.graph.nodes[0]!.id = `node-${id}`; value.graph.nodes[0]!.title = `节点 ${id}`
  value.layout.positions = { [`node-${id}`]: { x: id === 'A' ? 20 : 200, y: 30 } }
  values[id] = value
  const snapshot = execution(state)
  snapshot.execution.id = id; snapshot.control.id = id
  snapshot.control.configured = active; snapshot.control.state = active ? 'ACTIVE' : 'PAUSED'
  snapshots[id] = snapshot
}
beforeEach(() => {
  vi.resetAllMocks(); values = {}; snapshots = {}
  configure('A', 'PLANNING'); configure('B', 'PLANNING')
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  api.get.mockImplementation(async id => structuredClone(values[id]!))
  api.execution.mockImplementation(async id => structuredClone(snapshots[id]!))
  api.confirm.mockImplementation(async id => receipt(id))
  api.layout.mockImplementation(async id => receipt(id))
  api.pause.mockImplementation(async id => structuredClone(snapshots[id]!.control))
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })

async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/requirements/:id', component: WorkflowRequirementView },
    { path: '/requirements', component: { template: '<div />' } },
  ] })
  await router.push('/requirements/A'); await router.isReady()
  wrapper = mount(RouterView, { global: { plugins: [router], stubs: {
    Icon: true, WorkflowCanvas: ScopeCanvas, WorkflowFinish: leaveable, WorkflowPublication: leaveable,
    WorkflowSaveTemplate: leaveable, WorkflowCandidates: leaveable, WorkflowNodeRun: leaveable,
    WorkflowNodeEditor: true, WorkflowModelChoice: true, WorkflowRolePicker: true,
  } } })
  await flushPromises(); return router
}
function button(label: string) {
  return wrapper!.findAll('button').find(node => node.text() === label || node.attributes('aria-label') === label)
}
async function click(label: string) {
  if (!button(label)) await button('更多工具')!.trigger('click')
  await button(label)!.trigger('click'); await flushPromises()
}
async function changeLayout(x: number) {
  const canvas = wrapper!.getComponent(ScopeCanvas), current = canvas.props('layout') as WorkflowLayout
  const key = canvas.props('graph').nodes[0].id as string
  canvas.vm.$emit('layout', { ...current, positions: { ...current.positions, [key]: { x, y: 40 } } })
  await flushPromises()
}
function expectB() {
  expect(wrapper!.get('h1').text()).toBe('需求 B')
  expect(wrapper!.get('[aria-label="流程画布"]').text()).toContain('节点 B')
  expect(wrapper!.get('[aria-label="流程画布"]').text()).not.toContain('节点 A')
}

describe('requirement command scope across a reused route component', () => {
  it('ignores a late A write receipt and keeps the newer B command locked', async () => {
    const first = deferred<WorkflowReceipt>(), second = deferred<WorkflowReceipt>()
    api.confirm.mockImplementation(id => id === 'A' ? first.promise : second.promise)
    const router = await render(), instance = wrapper!.getComponent(WorkflowRequirementView).vm.$.uid
    await click('确认计划'); await router.push('/requirements/B'); await flushPromises()
    expect(wrapper!.getComponent(WorkflowRequirementView).vm.$.uid).toBe(instance); expectB()
    await click('确认计划'); first.resolve(receipt('A')); await flushPromises()
    expectB(); expect(button('确认计划')!.attributes('disabled')).toBeDefined()
    expect(api.get.mock.calls.map(call => call[0])).toEqual(['A', 'B'])
    expect(api.execution.mock.calls.map(call => call[0])).toEqual(['A', 'B'])
    expect(wrapper!.find('.workflow-notice').exists()).toBe(false)
    second.resolve(receipt('B')); await flushPromises()
    expectB(); expect(button('确认计划')!.attributes('disabled')).toBeUndefined()
    expect(api.confirm.mock.calls.map(call => call[0])).toEqual(['A', 'B'])
    expect(api.get.mock.calls.map(call => call[0])).toEqual(['A', 'B', 'B'])
  })

  it('ignores an A readback already in flight and preserves B layout and save ownership', async () => {
    const first = deferred<WorkflowRequirement>(), router = await render()
    api.get.mockImplementation(id => id === 'A' ? first.promise : Promise.resolve(structuredClone(values[id]!)))
    await changeLayout(75); await click('确认计划')
    expect(api.get.mock.calls.map(call => call[0])).toEqual(['A', 'A'])
    await router.push('/requirements/B'); await flushPromises(); await changeLayout(260)
    first.resolve(requirement({ id: 'A', title: '迟到需求 A', state: 'PENDING_START' })); await flushPromises()
    expectB(); expect(wrapper!.getComponent(ScopeCanvas).props('layout').positions['node-B']).toEqual({ x: 260, y: 40 })
    expect(wrapper!.find('.workflow-notice').exists()).toBe(false)
    expect(button('确认计划')!.attributes('disabled')).toBeUndefined()
    await click('保存计划'); expect(api.layout).toHaveBeenCalledOnce(); expect(api.layout.mock.calls[0]![0]).toBe('B')
    expect(api.confirm).toHaveBeenCalledOnce(); expect(api.execution.mock.calls.map(call => call[0])).toEqual(['A', 'B', 'B'])
  })

  it('does not apply the old save result after its composite operation reads A late', async () => {
    const first = deferred<WorkflowRequirement>(), router = await render()
    api.get.mockImplementation(id => id === 'A' ? first.promise : Promise.resolve(structuredClone(values[id]!)))
    await changeLayout(90); await click('保存计划'); expect(api.layout.mock.calls[0]![0]).toBe('A')
    await router.push('/requirements/B'); await flushPromises()
    first.resolve(requirement({ id: 'A', title: '迟到保存 A', layoutVersion: 5 })); await flushPromises()
    expectB(); expect(wrapper!.find('.workflow-notice').exists()).toBe(false)
    expect(button('确认计划')!.attributes('disabled')).toBeUndefined()
    await changeLayout(280); await click('保存计划')
    expect(api.layout.mock.calls.map(call => call[0])).toEqual(['A', 'B'])
    expect(api.revise).not.toHaveBeenCalled(); expect(api.start).not.toHaveBeenCalled()
  })

  it.each([true, false])('does not enter B editing after an A adjustment read finishes (pause=%s)', async active => {
    configure('A', active ? 'RUNNING' : 'PAUSED', active); configure('B', 'RUNNING', true)
    const first = deferred<WorkflowExecution>(), router = await render()
    api.execution.mockImplementation(id => id === 'A' ? first.promise : Promise.resolve(structuredClone(snapshots[id]!)))
    await click('调整后续计划'); expect(api.pause).toHaveBeenCalledTimes(active ? 1 : 0)
    await router.push('/requirements/B'); await flushPromises()
    first.resolve({ ...snapshots.A!, execution: { ...snapshots.A!.execution, state: 'PAUSED' } }); await flushPromises()
    expectB(); expect(button('放弃调整')).toBeUndefined(); expect(button('添加节点')).toBeUndefined()
    expect(button('暂停后续派发')!.attributes('disabled')).toBeUndefined()
    expect(api.execution.mock.calls.map(call => call[0])).toEqual(['A', 'A', 'B'])
    expect(api.start).not.toHaveBeenCalled()
  })

  it('retains the original command and request key when the existing navigation guard rejects leaving', async () => {
    const router = await render()
    api.confirm.mockRejectedValueOnce(new Error('unknown receipt'))
    await click('确认计划'); vi.mocked(window.confirm).mockReturnValue(false)
    await router.push('/requirements/B'); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/requirements/A'); expect(wrapper!.get('h1').text()).toBe('需求 A')
    expect(button('重试原操作')).toBeDefined(); expect(button('确认计划')!.attributes('disabled')).toBeDefined()
    await click('重试原操作')
    expect(api.confirm.mock.calls[1]).toEqual(api.confirm.mock.calls[0])
    expect(api.confirm.mock.calls[1]![1]).toBe(api.confirm.mock.calls[0]![1])
    expect(api.get.mock.calls.map(call => call[0])).toEqual(['A', 'A'])
    expect(button('确认计划')!.attributes('disabled')).toBeUndefined()
  })
})
