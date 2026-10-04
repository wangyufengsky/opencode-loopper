import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act } from 'react'
import { flushPromises } from '@/pages/w6-tests/workflow/react-test-root'
import { mountPageApplication } from '@/pages/w6-tests/workflow/application-test-root'
import { RequirementPage } from '@/pages/w5/requirements/RequirementPage'
import type { RequirementController } from '@/pages/w5/requirements/controller'
import { workflowRuns } from '@/api/workflowRuns'
import { execution, requirement } from '@/components/workflow/workflowRunTestFixtures'
import type { WorkflowExecution, WorkflowReceipt, WorkflowRequirement } from '@/types/domain'

vi.mock('@/api/workflowRuns', () => ({ workflowRuns: {
  get: vi.fn(), execution: vi.fn(), confirm: vi.fn(), start: vi.fn(), pause: vi.fn(), cancel: vi.fn(), finishStatus: vi.fn(),
  revise: vi.fn(), applyPlan: vi.fn(), applyCandidate: vi.fn(), layout: vi.fn(),
} }))

const api = vi.mocked(workflowRuns)
let wrapper: Awaited<ReturnType<typeof mountPageApplication>> | undefined
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
  api.get.mockImplementation(async id => structuredClone(values[id]!))
  api.execution.mockImplementation(async id => structuredClone(snapshots[id]!))
  api.confirm.mockImplementation(async id => receipt(id))
  api.layout.mockImplementation(async id => receipt(id))
  api.pause.mockImplementation(async id => structuredClone(snapshots[id]!.control))
  api.finishStatus.mockImplementation(async id => ({ requirementId: id, state: values[id]!.state, version: values[id]!.version, intent: null, pending: { attempts: 0, resources: 0 } }))
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })

async function render() { wrapper=await mountPageApplication(RequirementPage,'/requirements/A',['/requirements/:id','/requirements']);return wrapper }
const keys:Record<string,string>={'确认计划':'workflow.confirmPlan','保存计划':'workflow.savePlanning','调整后续计划':'workflow.adjustPlan','暂停后续派发':'workflow.pause','更多工具':'workflow.moreTools','添加节点':'workflow.addNode','重试原操作':'receipt.retryOriginal','刷新操作结果':'receipt.readOriginal'}
function button(label:string) { return wrapper!.findAll('button').filter(node=>!node.element.closest('[hidden]')).find(node=>!!keys[label]&&node.attributes('data-semantic')===keys[label]||node.attributes('aria-label')===label) }
async function click(label:string) { if(!button(label))await button('更多工具')!.trigger('click');await button(label)!.trigger('click');await flushPromises() }
function owner():RequirementController { return [...wrapper!.application.current!.owners.keys()].find(value=>'id' in value && 'setLayout' in value) as RequirementController }
async function changeLayout(x:number) { const value=owner(),s=value.getSnapshot(),key=s.graph.nodes[0]!.id;await act(async()=>{value.setLayout({...s.layout,positions:{...s.layout.positions,[key]:{x,y:40}}})});await flushPromises() }
function expectB(){expect(wrapper!.get('h1').text()).toBe('需求 B');expect(wrapper!.get('[data-canvas-kind="workflow"]').text()).toContain('节点 B');expect(wrapper!.get('[data-canvas-kind="workflow"]').text()).not.toContain('节点 A')}
async function forceRoute(router:Awaited<ReturnType<typeof render>>,id:string){
 // Public host ownership replacement deliberately retires the old root despite its guard.
 // Ordinary navigation below still traverses the real coordinator and React useBlocker.
 const path=`/requirements/${id}`;await act(async()=>{router.application.commit(router.application.scope({path,fullPath:path,params:{id},query:{}}));await router.router.navigate(path)});await router.settle()
}

describe('requirement command scope across a reused route component', () => {
  it('ignores a late A write receipt and keeps the newer B command locked', async () => {
    const first = deferred<WorkflowReceipt>(), second = deferred<WorkflowReceipt>()
    api.confirm.mockImplementation(id => id === 'A' ? first.promise : second.promise)
    const router = await render(), instance = wrapper!.application.current!
    await click('确认计划'); await forceRoute(router, 'B')
    expect(instance.active).toBe(false); expect(wrapper!.application.current).not.toBe(instance); expectB()
    await click('确认计划'); first.resolve(receipt('A')); await flushPromises()
    expectB(); expect(button('确认计划')!.attributes('disabled')).toBeDefined()
    expect(api.get.mock.calls.map(call => call[0])).toEqual(['A', 'B'])
    expect(api.execution.mock.calls.map(call => call[0])).toEqual(['A', 'B'])
    expect(owner().getSnapshot().notice).toBe(''); expect(owner().getSnapshot().error).toBe('')
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
    await forceRoute(router, 'B'); await changeLayout(260)
    first.resolve(requirement({ id: 'A', title: '迟到需求 A', state: 'PENDING_START' })); await flushPromises()
    expectB(); expect(owner().getSnapshot().layout.positions['node-B']).toEqual({ x: 260, y: 40 })
    expect(owner().getSnapshot().notice).toBe(''); expect(owner().getSnapshot().error).toBe('')
    expect(button('确认计划')!.attributes('disabled')).toBeUndefined()
    await click('保存计划'); expect(api.layout).toHaveBeenCalledOnce(); expect(api.layout.mock.calls[0]![0]).toBe('B')
    expect(api.confirm).toHaveBeenCalledOnce(); expect(api.execution.mock.calls.map(call => call[0])).toEqual(['A', 'B', 'B'])
  })

  it('does not apply the old save result after its composite operation reads A late', async () => {
    const first = deferred<WorkflowRequirement>(), router = await render()
    api.get.mockImplementation(id => id === 'A' ? first.promise : Promise.resolve(structuredClone(values[id]!)))
    await changeLayout(90); await click('保存计划'); expect(api.layout.mock.calls[0]![0]).toBe('A')
    await forceRoute(router, 'B')
    first.resolve(requirement({ id: 'A', title: '迟到保存 A', layoutVersion: 5 })); await flushPromises()
    expectB(); expect(owner().getSnapshot().notice).toBe(''); expect(owner().getSnapshot().error).toBe('')
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
    await forceRoute(router, 'B')
    first.resolve({ ...snapshots.A!, execution: { ...snapshots.A!.execution, state: 'PAUSED' } }); await flushPromises()
    expectB(); expect(button('放弃调整')).toBeUndefined(); expect(button('添加节点')).toBeUndefined()
    expect(button('暂停后续派发')!.attributes('disabled')).toBeUndefined()
    expect(api.execution.mock.calls.map(call => call[0])).toEqual(['A', 'A', 'B'])
    expect(api.start).not.toHaveBeenCalled()
  })

  it('blocks leaving an unknown command even when ordinary draft confirmation would allow it', async () => {
    const router = await render()
    api.confirm.mockRejectedValueOnce(new Error('unknown receipt'))
    await click('确认计划')
    await router.navigate('/requirements/B'); await flushPromises()
    expect(router.router.state.location.pathname).toBe('/requirements/A'); expect(wrapper!.get('h1').text()).toBe('需求 A')
    expect(wrapper!.application.current!.dialog.getSnapshot().open).toBe(false); expect(wrapper!.text()).toContain('结果尚未确认')
    expect(button('重试原操作')).toBeDefined(); expect(button('确认计划')!.attributes('disabled')).toBeDefined()
    await click('重试原操作')
    expect(api.confirm.mock.calls[1]).toEqual(api.confirm.mock.calls[0])
    expect(api.confirm.mock.calls[1]![1]).toBe(api.confirm.mock.calls[0]![1])
    expect(api.get.mock.calls.map(call => call[0])).toEqual(['A', 'A'])
    expect(button('确认计划')!.attributes('disabled')).toBeUndefined()
    await router.navigate('/requirements/B'); await flushPromises(); expectB()
  })

  it('blocks leaving a running write and an accepted command whose readback failed', async () => {
    const write = deferred<WorkflowReceipt>(), router = await render()
    api.confirm.mockReturnValue(write.promise)
    await click('确认计划'); await router.navigate('/requirements/B'); await flushPromises()
    expect(router.router.state.location.pathname).toBe('/requirements/A'); expect(wrapper!.get('[data-operation-phase="SENDING"]').text()).toContain('正在发送')
    api.get.mockRejectedValueOnce(new Error('readback offline'))
    write.resolve(receipt('A')); await flushPromises()
    await router.navigate('/requirements/B'); await flushPromises()
    expect(router.router.state.location.pathname).toBe('/requirements/A'); expect(wrapper!.text()).toContain('写入已接受')
    await click('刷新操作结果'); expect(api.confirm).toHaveBeenCalledOnce()
    await router.navigate('/requirements/B'); await flushPromises(); expectB()
  })
})
