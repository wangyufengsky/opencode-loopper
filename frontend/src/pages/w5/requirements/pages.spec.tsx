import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { StrictMode } from 'react'
import { RequirementPage } from './RequirementPage'
import { NewRequirementPage } from './NewRequirementPage'
import { mockRequirementReads, requirementFixture, requirementFrame, setupRequirementDom, requirement, execution, deferred, candidate } from './test-support'
import { workflowRuns } from '@/api/workflowRuns'
import { semanticName } from '@/foundation/semanticRegistry'
import { skins } from '@/themes/registry'

const fixtures: ReturnType<typeof requirementFixture>[] = []
function fixture(path = '/requirements/req') { const value = requirementFixture(); value.props.route = { path, fullPath: path, params: path.endsWith('/new') ? {} : { id: 'req' }, query: {} }; fixtures.push(value); return value }
beforeEach(() => { setupRequirementDom(); mockRequirementReads(); vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('unmocked transport forbidden'))) })
afterEach(() => { cleanup(); fixtures.splice(0).forEach(value => value.dispose()); vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('actual requirement page entry and context ownership', () => {
  it('header keeps the native guarded requirements return link without consuming modified clicks or writing', async () => {
    const f = fixture(), write = vi.spyOn(workflowRuns, 'layout')
    render(requirementFrame(<RequirementPage {...f.props} />))
    await screen.findByRole('heading', { name: requirement().title, level: 1 })
    const back = screen.getByRole('link', { name: semanticName('nav.back', '需求任务') })
    expect(back.getAttribute('href')).toBe('/requirements')
    fireEvent.click(back, { ctrlKey: true }); expect(f.props.navigation.go).not.toHaveBeenCalled()
    fireEvent.click(back); expect(f.props.navigation.go).toHaveBeenCalledWith('/requirements')
    expect(write).not.toHaveBeenCalled()
  })
  it('execution layout changes retain a dirty draft and explicitly save only the original layout CAS without rewriting the graph', async () => {
    let original = requirement({ state: 'RUNNING' }); const f = fixture()
    vi.mocked(workflowRuns.get).mockImplementation(async () => original)
    const graphWrite = vi.spyOn(workflowRuns, 'revise'), activeGraphWrite = vi.spyOn(workflowRuns, 'applyPlan')
    const write = vi.spyOn(workflowRuns, 'layout').mockImplementation(async (_id, body) => {
      original = { ...original, layout: structuredClone(body.layout), layoutVersion: original.layoutVersion + 1 }
      return { id: original.id, state: original.state, revision: original.revision, version: original.version, layoutVersion: original.layoutVersion }
    })
    const host = render(requirementFrame(<RequirementPage {...f.props} />))
    await screen.findByRole('heading', { name: original.title, level: 1 })
    const node = host.container.querySelector<HTMLElement>('[data-node-id="review"]')!
    const parent = [...f.retained.keys()].find((value): value is ReturnType<typeof import('./controller').createRequirementController> => 'setExportOwner' in value)!
    const before = structuredClone(parent.getSnapshot().layout)
    node.focus(); fireEvent.keyDown(node, { key: 'ArrowRight' })
    expect(parent.getSnapshot().dirty).toBe(true)
    expect(parent.canLeave().kind).toBe('CONFIRM_DISCARD')
    expect(write).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '保存布局' }))
    await waitFor(() => expect(write).toHaveBeenCalledTimes(1))
    const body = write.mock.calls[0]![1]
    expect(body).toMatchObject({ expectedRevision: 2, expectedLayoutVersion: 4 })
    expect(body.requestKey).toBeTruthy()
    expect(body.layout.positions.review!.x).toBe((before.positions.review?.x ?? 0) + 24)
    await waitFor(() => expect(parent.getSnapshot().dirty).toBe(false))
    expect(parent.getSnapshot().base?.layoutVersion).toBe(5)
    expect(parent.getSnapshot().layout).toEqual(body.layout)
    expect(graphWrite).not.toHaveBeenCalled(); expect(activeGraphWrite).not.toHaveBeenCalled()
  })
  it('candidate review exposes pending status and historical readonly preview closes by restoring the original base without writes', async () => {
    mockRequirementReads('PAUSED'); const f = fixture(), historical = candidate({ state: 'APPLIED', baseRevision: 1, appliedRevision: 2 })
    vi.mocked(workflowRuns.candidate).mockResolvedValue(historical)
    vi.mocked(workflowRuns.candidates).mockResolvedValue({ items: [{ ...historical, sourceState: 'SUCCEEDED', createdAt: '' }], nextCursor: null })
    const save = vi.spyOn(workflowRuns, 'applyCandidate'), revise = vi.spyOn(workflowRuns, 'applyPlan'), layout = vi.spyOn(workflowRuns, 'layout')
    const host = render(requirementFrame(<RequirementPage {...f.props} />)); await screen.findByRole('heading', { name: requirement().title, level: 1 })
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.moreTools') })); fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.reviewCandidate') }))
    const panel = await screen.findByRole('complementary', { name: '候选计划' })
    fireEvent.click(within(panel).getByRole('button', { name: semanticName('selection.select', historical.sourceTitle) }))
    await within(panel).findByText(`${historical.sourceTitle} 提出的计划`)
    fireEvent.click(within(panel).getByRole('button', { name: semanticName('workflow.reviewCandidate') }))
    expect(screen.getByText(/只读预览/)).toBeTruthy()
    expect(host.container.querySelectorAll('.workflow-node')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: semanticName('workflow.applyCandidate') })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: semanticName('ui.close', '候选预览') }))
    expect(host.container.querySelectorAll('.workflow-node')).toHaveLength(1)
    expect(screen.queryByText(/只读预览/)).toBeNull()
    expect(save).not.toHaveBeenCalled(); expect(revise).not.toHaveBeenCalled(); expect(layout).not.toHaveBeenCalled()
  })
  it('saving a template preserves the same hidden locked parent canvas and its draft, returning unlocks that same instance', async () => {
    mockRequirementReads('PLANNING')
    const original = requirement({ state: 'PLANNING' }), f = fixture()
    vi.spyOn(workflowRuns, 'previewTemplate').mockResolvedValue({ mode: 'CURRENT', sourceRevision: original.revision, initialAvailable: false, graph: original.graph, layout: original.layout, fixedPlanningNodes: [], sha256: 'preview', diagnostics: [] })
    const write = vi.spyOn(workflowRuns, 'layout'), save = vi.spyOn(workflowRuns, 'saveTemplate')
    const host = render(requirementFrame(<RequirementPage {...f.props} />))
    await screen.findByRole('heading', { name: original.title, level: 1 })
    const parentCanvas = host.container.querySelector<HTMLElement>('[data-canvas-kind="workflow"]')!
    const parentNode = parentCanvas.querySelector<HTMLElement>('[data-node-id="review"]')!
    parentNode.focus(); fireEvent.keyDown(parentNode, { key: 'ArrowRight' })
    const parent = [...f.retained.keys()].find((value): value is ReturnType<typeof import('./controller').createRequirementController> => 'setExportOwner' in value)!
    const draft = JSON.stringify(parent.getSnapshot().layout)
    expect(parent.getSnapshot().dirty).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.moreTools') }))
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.saveTemplate') }))
    const preview = await screen.findByRole('dialog', { name: '另存为流程模板' })
    await waitFor(() => expect(preview.querySelector('[data-canvas-kind="workflow"]')).toBeTruthy())
    expect(parentCanvas.isConnected).toBe(true)
    expect(parentCanvas.closest('[hidden]')).toBeTruthy()
    expect(parent.lockedForUi()).toBe(true); expect(parent.canLeave().kind).toBe('BLOCK')
    const previewNode = preview.querySelector<HTMLElement>('[data-node-id="review"]')!
    previewNode.focus(); fireEvent.keyDown(previewNode, { key: 'ArrowRight' }); fireEvent.keyDown(previewNode, { key: 'Delete' })
    expect(JSON.stringify(parent.getSnapshot().layout)).toBe(draft)
    fireEvent.click(within(preview).getByRole('button', { name: semanticName('nav.back') }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '另存为流程模板' })).toBeNull())
    expect(parentCanvas.isConnected).toBe(true)
    expect(host.container.querySelector('[data-canvas-kind="workflow"]')).toBe(parentCanvas)
    expect(parentCanvas.closest('[hidden]')).toBeNull()
    expect(parent.lockedForUi()).toBe(false); expect(parent.getSnapshot().dirty).toBe(true)
    expect(JSON.stringify(parent.getSnapshot().layout)).toBe(draft)
    expect(write).not.toHaveBeenCalled(); expect(save).not.toHaveBeenCalled()
  })
  it('unknown template save keeps its exact body and connected parent instance while closing stays blocked', async () => {
    const original = requirement(), f = fixture()
    vi.spyOn(workflowRuns, 'previewTemplate').mockResolvedValue({ mode: 'CURRENT', sourceRevision: original.revision, initialAvailable: false, graph: original.graph, layout: original.layout, fixedPlanningNodes: [], sha256: 'preview', diagnostics: [] })
    const save = vi.spyOn(workflowRuns, 'saveTemplate').mockRejectedValue(new Error('lost original receipt'))
    const host = render(requirementFrame(<RequirementPage {...f.props} />))
    await screen.findByRole('heading', { name: original.title, level: 1 })
    const parentCanvas = host.container.querySelector('[data-canvas-kind="workflow"]')!
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.moreTools') }))
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.saveTemplate') }))
    const preview = await screen.findByRole('dialog', { name: '另存为流程模板' })
    await waitFor(() => expect(within(preview).getByRole('button', { name: semanticName('workflow.saveTemplateConfirm') }).matches(':disabled')).toBe(false))
    fireEvent.click(within(preview).getByRole('button', { name: semanticName('workflow.saveTemplateConfirm') }))
    await waitFor(() => expect(preview.querySelector('[data-operation-phase="UNKNOWN"]')).toBeTruthy())
    expect(parentCanvas.isConnected).toBe(true); expect(parentCanvas.closest('[hidden]')).toBeTruthy()
    expect(within(preview).getByRole('button', { name: semanticName('nav.back') }).matches(':disabled')).toBe(true)
    expect([...f.guards].some(guard => guard().kind === 'BLOCK')).toBe(true)
    const body = save.mock.calls[0]![1]
    expect(body).toMatchObject({ selection: { expectedRevision: original.revision }, previewSha256: 'preview' })
    fireEvent.click(within(preview).getByRole('button', { name: semanticName('receipt.retryOriginal') }))
    await waitFor(() => expect(save).toHaveBeenCalledTimes(2))
    expect(save.mock.calls[1]![1]).toBe(body)
    expect(parentCanvas.isConnected).toBe(true)
  })
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
