import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { StrictMode } from 'react'
import { RequirementPage } from './RequirementPage'
import { NewRequirementPage } from './NewRequirementPage'
import { mockRequirementReads, requirementFixture, requirementFrame, setupRequirementDom, requirement, execution, deferred } from './test-support'
import { workflowRuns } from '@/api/workflowRuns'
import { semanticName } from '@/foundation/semanticRegistry'
import { skins } from '@/themes/registry'

const fixtures: ReturnType<typeof requirementFixture>[] = []
function fixture(path = '/requirements/req') { const value = requirementFixture(); value.props.route = { path, fullPath: path, params: path.endsWith('/new') ? {} : { id: 'req' }, query: {} }; fixtures.push(value); return value }
beforeEach(() => { setupRequirementDom(); mockRequirementReads(); vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('unmocked transport forbidden'))) })
afterEach(() => { cleanup(); fixtures.splice(0).forEach(value => value.dispose()); vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('actual requirement page entry and context ownership', () => {
  it('real mount starts its sole plan/execution reader without manual load and StrictMode detach clears poll/read lease', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      const f = fixture(), host = render(<StrictMode>{requirementFrame(<RequirementPage {...f.props} />)}</StrictMode>)
      await act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve() })
      expect(screen.getByRole('heading', { name: requirement().title, level: 1 })).toBeTruthy()
      expect(workflowRuns.get).toHaveBeenCalledWith('req'); expect(workflowRuns.execution).toHaveBeenCalledWith('req')
      expect(host.container.querySelector('[data-canvas-runtime="react"][data-canvas-kind="workflow"]')).toBeTruthy()
      const before = vi.mocked(workflowRuns.execution).mock.calls.length
      host.unmount(); const timers = vi.getTimerCount()
      await vi.advanceTimersByTimeAsync(7500)
      expect(workflowRuns.execution).toHaveBeenCalledTimes(before); expect(vi.getTimerCount()).toBeLessThanOrEqual(timers)
    } finally { vi.useRealTimers() }
  })
  it('ordinary selection starts real human attempt reader, selected context is hidden until chosen and remains across skin changes', async () => {
    const active = execution('RUNNING'); active.execution.nodes = [{ id: 'review', nodeKey: 'review', state: 'WAITING_INPUT', attemptCount: 1, latestAttemptId: 'run', version: 1, outcome: null }]; vi.mocked(workflowRuns.execution).mockResolvedValue(active)
    const f = fixture(), host = render(requirementFrame(<RequirementPage {...f.props} />))
    await screen.findByRole('heading', { name: requirement().title, level: 1 })
    expect(screen.queryByRole('region', { name: '节点执行详情' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.nodeList') }))
    fireEvent.click(screen.getByRole('button', { name: semanticName('selection.select', requirement().graph.nodes[0]!.title) }))
    await screen.findByRole('region', { name: '节点执行详情' })
    expect(await screen.findByLabelText('结果说明')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('结果说明'), { target: { value: '保留人工草稿' } })
    host.rerender(requirementFrame(<RequirementPage {...{ ...f.props, skin: skins[1]! }} />, skins[1]!))
    expect((screen.getByLabelText('结果说明') as HTMLTextAreaElement).value).toBe('保留人工草稿')
    expect([...f.guards].some(guard => guard().kind === 'CONFIRM_DISCARD')).toBe(true)
  })
  it('new real four-field form tolerates unavailable optional legacy storage and performs no automatic creation', async () => {
    const f = fixture('/requirements/new'); f.props.route.query = { legacyDraft: '1', projectId: 'project' }
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('storage unavailable', 'SecurityError') })
    const create = vi.spyOn(workflowRuns, 'create')
    render(requirementFrame(<NewRequirementPage {...f.props} />))
    expect(screen.getByText('旧本地草稿无法读取，请重新填写需求目标。')).toBeTruthy()
    await screen.findByText('测试项目')
    expect(screen.getByLabelText('需求名称')).toBeTruthy(); expect(screen.getByLabelText('需求目标')).toBeTruthy()
    expect(screen.getByRole('button', { name: semanticName('workflow.chooseProject') })).toBeTruthy(); expect(screen.getByRole('button', { name: semanticName('workflow.chooseTemplate') })).toBeTruthy()
    expect(document.querySelector('input[type="file"]')).toBeNull(); expect(create).not.toHaveBeenCalled()
  })
  it('new UNKNOWN locks four actual controls, retains original body across explicit retry and keeps accepted navigation-only recovery', async () => {
    const f = fixture('/requirements/new'); f.props.route.query = { projectId: 'project' }
    const write = vi.spyOn(workflowRuns, 'create').mockRejectedValueOnce(new Error('unknown')).mockResolvedValue({ id: 'created', revision: 1, version: 1, layoutVersion: 0, state: 'PLANNING' })
    vi.mocked(f.props.navigation.goAccepted).mockResolvedValue(false)
    render(requirementFrame(<NewRequirementPage {...f.props} />)); await screen.findByText('测试项目')
    fireEvent.change(screen.getByLabelText('需求名称'), { target: { value: '原需求' } }); fireEvent.change(screen.getByLabelText('需求目标'), { target: { value: '原目标' } })
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.createRequirement') }))
    await waitFor(() => expect(document.querySelector('[data-operation-phase="UNKNOWN"]')).toBeTruthy())
    for (const control of [screen.getByLabelText('需求名称'), screen.getByLabelText('需求目标'), screen.getByRole('button', { name: semanticName('workflow.chooseProject') }), screen.getByRole('button', { name: semanticName('workflow.chooseTemplate') })]) expect(control.closest('fieldset')?.disabled || (control as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.retryCreate') }))
    await screen.findByRole('button', { name: semanticName('workflow.openCreatedRequirement') })
    expect(write.mock.calls[1]![0]).toBe(write.mock.calls[0]![0]); expect(write.mock.calls[0]![0]).toMatchObject({ title: '原需求', objective: '原目标', projectId: 'project', templateRevision: 3 })
    vi.mocked(f.props.navigation.goAccepted).mockResolvedValue(true)
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.openCreatedRequirement') }))
    await waitFor(() => expect(f.props.navigation.goAccepted).toHaveBeenCalledTimes(2)); expect(write).toHaveBeenCalledTimes(2)
  })
  it('actual Finish child draft survives dirty close Stay and stale discard confirmation is blocked', async () => {
    const f = fixture(); render(requirementFrame(<RequirementPage {...f.props} />)); await screen.findByRole('heading', { name: requirement().title, level: 1 })
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.moreTools') }))
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.finish') }))
    fireEvent.change(screen.getByLabelText('结束原因'), { target: { value: '原理由' } })
    const owner = [...f.retained.keys()].find((value): value is ReturnType<typeof import('./finishController').createFinishController> => 'available' in value && 'submit' in value)
    expect(owner).toBeTruthy()
    // A real close request reaches the parent context guard rather than discarding the child.
    const tools = screen.getByRole('complementary', { name: '更多操作' })
    fireEvent.click(within(tools).getByRole('button', { name: semanticName('ui.close', '更多操作') }))
    const dialog = await screen.findByRole('dialog', { name: '放弃当前修改？' })
    await act(async () => { owner!.change({ reason: '更新理由' }) })
    expect(within(dialog).getByRole('button', { name: semanticName('ui.discardChanges') }).getAttribute('disabled') !== null).toBe(true)
    fireEvent.click(within(dialog).getByRole('button', { name: semanticName('ui.stay') }))
    expect((screen.getByLabelText('结束原因') as HTMLTextAreaElement).value).toBe('更新理由')
  })
  it('forced root exit before initial plan GET resolves prevents all later execution reads and projection', async () => {
    const pending = deferred<ReturnType<typeof requirement>>(); vi.mocked(workflowRuns.get).mockReturnValue(pending.promise)
    const f = fixture(), host = render(requirementFrame(<RequirementPage {...f.props} />)); await act(async () => { await Promise.resolve() }); expect(workflowRuns.get).toHaveBeenCalled()
    host.unmount(); f.dispose(); await act(async () => { pending.resolve(requirement()); await pending.promise })
    expect(workflowRuns.execution).not.toHaveBeenCalled()
  })
})
