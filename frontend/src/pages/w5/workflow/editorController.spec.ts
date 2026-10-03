import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { workflowApi } from '@/api/workflow'
import { ApiError } from '@/api/client'
import { template } from '@/components/workflow/workflowTestFixtures'
import { deferred, flush } from '@/pages/w2/workflow/page.test-support'
import { createWorkflowEditorController } from './editorController'
import type { WorkflowReceipt, WorkflowTemplate } from '@/types/domain'
import { navigateAcceptedHandoff, type AcceptedHandoff } from '@/foundation/contracts/receipt'
vi.mock('@/api/workflow', () => ({ workflowApi: { create: vi.fn(), revise: vi.fn(), layout: vi.fn(), get: vi.fn(), copy: vi.fn(), validate: vi.fn() } }))
const api = vi.mocked(workflowApi), disposers: (() => void)[] = []
const graphReceipt: WorkflowReceipt = { id: 'example', revision: 3, version: 4, layoutVersion: 4, state: 'ACTIVE' }, layoutReceipt: WorkflowReceipt = { ...graphReceipt, layoutVersion: 5 }
beforeEach(() => { vi.resetAllMocks(); api.get.mockResolvedValue(template()); api.validate.mockResolvedValue([]) })
afterEach(() => { for (const dispose of disposers.splice(0)) dispose(); vi.restoreAllMocks() })
async function owner(id = 'example', navigation?: Parameters<typeof createWorkflowEditorController>[0]) { const controller = createWorkflowEditorController({ id, ...navigation }); const detach = controller.attachView(); disposers.push(() => { detach(); controller.retire(true) }); await flush(); return controller }
describe('React Editor unique save/graph/layout owner', () => {
  it('lost create keeps original body/key, locks draft, and explicitly retries only the original create', async () => {
    const o = await owner(''); o.title('新流程'); api.create.mockRejectedValueOnce(new Error('unknown')).mockResolvedValue({ id: 'created', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' }); api.get.mockResolvedValue(template({ id: 'created', revision: 1, headRevision: 1, version: 0, layoutVersion: 0, title: '新流程' }))
    await o.save(); const body = api.create.mock.calls[0]![0], key = body.requestKey; expect(o.canLeave().kind).toBe('BLOCK'); expect(o.getSnapshot().savePhase).toBe('UNKNOWN'); o.title('不能替换'); expect(o.getSnapshot().draft.title).toBe('新流程')
    await o.recover(); expect(api.create.mock.calls[1]![0]).toEqual(body); expect(api.create.mock.calls[1]![0].requestKey).toBe(key); expect(api.layout).not.toHaveBeenCalled(); expect(o.canLeave().kind).toBe('ALLOW')
  })
  it('partial graph acceptance keeps graph/layout CAS and retries layout alone using a different original key', async () => {
    const o = await owner(); o.title('新名称'); api.revise.mockResolvedValue(graphReceipt); api.layout.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue(layoutReceipt); api.get.mockResolvedValue(template({ revision: 3, headRevision: 3, version: 4, layoutVersion: 5, title: '新名称' }))
    await o.save(); const original = api.layout.mock.calls[0]![1]; expect(o.getSnapshot()).toMatchObject({ graphAccepted: true, layoutAccepted: false, savePhase: 'UNKNOWN' }); expect(o.canLeave().kind).toBe('BLOCK')
    expect(api.revise.mock.calls[0]![1]).toMatchObject({ expectedVersion: 3, expectedRevision: 2 }); expect(original).toMatchObject({ expectedRevision: 3, expectedLayoutVersion: 4 }); expect(original.requestKey).not.toBe(api.revise.mock.calls[0]![1].requestKey)
    await o.recover(); expect(api.revise).toHaveBeenCalledTimes(1); expect(api.layout.mock.calls[1]).toEqual(api.layout.mock.calls[0]); expect(o.getSnapshot().notice).toBe('流程已保存')
  })
  it.each([409, 422])('definite layout %s retains the accepted graph and original layout identity through explicit retries', async status => {
    const o = await owner(); o.title('分段保存草稿'); api.revise.mockResolvedValue(graphReceipt)
    api.layout.mockRejectedValueOnce(new ApiError('layout rejected', status)).mockRejectedValueOnce(new ApiError('layout rejected again', status)).mockResolvedValue(layoutReceipt)
    api.get.mockResolvedValue(template({ revision: 3, headRevision: 3, version: 4, layoutVersion: 5, title: '分段保存草稿' }))
    const saving = o.save(), original = o.originalSave()!; await saving
    const layoutCall = api.layout.mock.calls[0]!
    expect(o.originalSave()).toBe(original); expect(original.graphReceipt).toEqual(graphReceipt); expect(original.layoutReceipt).toBeUndefined()
    expect(layoutCall).toEqual(['example', { requestKey: original.layoutKey, expectedRevision: 3, expectedLayoutVersion: 4, layout: original.draft.layout }])
    expect(o.getSnapshot()).toMatchObject({ savePhase: 'PARTIAL_REJECTION', graphAccepted: true, layoutAccepted: false, saveBusy: false, dirty: true })
    expect(o.getSnapshot().saveError).toContain('布局已明确拒绝'); expect(o.getSnapshot().saveError).not.toContain('尚未确认')
    expect(o.canLeave()).toMatchObject({ kind: 'BLOCK', reason: expect.stringContaining('布局已明确拒绝') }); expect(o.locked()).toBe(true)
    const draft = JSON.stringify(o.getSnapshot().draft); o.title('不可覆盖原草稿'); await o.load(); await o.save(true)
    expect(JSON.stringify(o.getSnapshot().draft)).toBe(draft); expect(api.create).not.toHaveBeenCalled(); expect(api.copy).not.toHaveBeenCalled(); expect(api.get).toHaveBeenCalledTimes(1); expect(api.revise).toHaveBeenCalledTimes(1); expect(api.layout).toHaveBeenCalledTimes(1)
    await o.recover()
    expect(o.originalSave()).toBe(original); expect(original.graphReceipt).toEqual(graphReceipt); expect(o.getSnapshot().savePhase).toBe('PARTIAL_REJECTION'); expect(o.canLeave().kind).toBe('BLOCK')
    expect(api.layout.mock.calls[1]).toEqual(layoutCall); expect(api.revise).toHaveBeenCalledTimes(1); expect(api.get).toHaveBeenCalledTimes(1)
    await o.recover()
    expect(api.layout.mock.calls[2]).toEqual(layoutCall); expect(api.revise).toHaveBeenCalledTimes(1); expect(api.get).toHaveBeenCalledTimes(2)
    expect(o.originalSave()).toBeUndefined(); expect(o.getSnapshot()).toMatchObject({ savePhase: 'SETTLED', notice: '流程已保存', dirty: false }); expect(o.canLeave().kind).toBe('ALLOW')
  })
  it('only layout is written for an unchanged graph; final GET failure stays accepted and recovery makes no new writes', async () => {
    const o = await owner(); o.layout({ ...o.getSnapshot().draft.layout, x: 80 }); api.layout.mockResolvedValue({ ...layoutReceipt, revision: 2, version: 3 }); api.get.mockRejectedValueOnce(new Error('read offline')).mockResolvedValue(template({ layoutVersion: 5 }))
    await o.save(); expect(o.getSnapshot()).toMatchObject({ layoutAccepted: true, savePhase: 'ACCEPTED_READBACK' }); expect(o.canLeave().kind).toBe('BLOCK'); expect(api.revise).not.toHaveBeenCalled()
    const writes = api.layout.mock.calls.length; await o.recover(); expect(api.layout).toHaveBeenCalledTimes(writes); expect(api.get).toHaveBeenCalledTimes(3); expect(o.canLeave().kind).toBe('ALLOW')
  })
  it.each(['id', 'revision', 'layoutVersion'] as const)('rejects an accepted final DTO with invalid %s and keeps read-only recovery', async field => {
    const o = await owner(); o.layout({ ...o.getSnapshot().draft.layout, x: 80 }); const receipt = { ...layoutReceipt, revision: 2, version: 3 }; api.layout.mockResolvedValue(receipt)
    api.get.mockResolvedValue(template(field === 'id' ? { id: 'foreign', layoutVersion: 5 } : field === 'revision' ? { revision: 1, layoutVersion: 5 } : { layoutVersion: 4 }))
    await o.save(); expect(o.getSnapshot().savePhase).toBe('ACCEPTED_READBACK'); expect(o.canLeave().kind).toBe('BLOCK'); await o.recover(); expect(api.layout).toHaveBeenCalledTimes(1); expect(o.getSnapshot().saveError).toContain('版本不一致')
  })
  it('SENDING blocks departure, reload and duplicate writers while later receipt uses only the captured graph', async () => {
    const o = await owner(), pending = deferred<WorkflowReceipt>(); o.title('原名称'); api.revise.mockReturnValue(pending.promise); const running = o.save(); await flush(); const snapshot = JSON.stringify(o.getSnapshot().draft), reads = api.get.mock.calls.length
    await o.load(); o.title('新投影'); const same = o.save(); expect(same).toBe(running); expect(o.canLeave().kind).toBe('BLOCK'); expect(api.get).toHaveBeenCalledTimes(reads); expect(JSON.stringify(o.getSnapshot().draft)).toBe(snapshot)
    api.layout.mockResolvedValue(layoutReceipt); api.get.mockResolvedValue(template({ revision: 3, headRevision: 3, version: 4, layoutVersion: 5, title: '原名称' })); pending.resolve(graphReceipt); await running; expect(o.getSnapshot().dirty).toBe(false)
  })
  it('explicit transaction CAS rejection preserves draft and supports confirmed new copy without discarding accepted history', async () => {
    const o = await owner(); o.title('本地草稿'); api.revise.mockRejectedValue(new ApiError('修改版本冲突', 409)); await o.save(); expect(o.getSnapshot()).toMatchObject({ conflict: true, savePhase: 'SETTLED', dirty: true }); expect(o.canLeave().kind).toBe('CONFIRM_DISCARD'); expect(o.getSnapshot().draft.title).toBe('本地草稿')
    api.create.mockResolvedValue({ id: 'copy', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' }); api.get.mockResolvedValue(template({ id: 'copy', title: '本地草稿', revision: 1, headRevision: 1, version: 0, layoutVersion: 0 })); await o.save(true); expect(api.create.mock.calls[0]![0].title).toBe('本地草稿'); expect(api.revise).toHaveBeenCalledTimes(1)
  })
  it('builtin graph remains read-only and its explicit copy freezes source revision and provenance', async () => {
    api.get.mockResolvedValue(template({ builtin: true })); const o = await owner(); const original = o.getSnapshot().draft; o.add('human'); o.title('不可编辑'); o.layout({ ...original.layout, x: 90 }); expect(o.getSnapshot().draft.graph).toEqual(original.graph); expect(o.getSnapshot().dirty).toBe(false)
    api.copy.mockRejectedValueOnce(new Error('lost')).mockResolvedValue({ id: 'copy', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' }); api.get.mockResolvedValue(template({ id: 'copy', revision: 1, version: 0, layoutVersion: 0, builtin: false })); await o.save(); await o.recover(); expect(api.copy.mock.calls[0]).toEqual(api.copy.mock.calls[1]); expect(api.copy.mock.calls[0]).toEqual(['example', expect.objectContaining({ sourceRevision: 2, title: '交付流程 副本' })]); expect(api.create).not.toHaveBeenCalled()
  })
  it('new accepted navigation failure retains original receipt; only exact successful handoff completes, no rePOST', async () => {
    const navigate = vi.fn(async () => false), goAccepted = vi.fn((to: string, permit: AcceptedHandoff) => navigateAcceptedHandoff(permit, to, navigate)), o = await owner('', { goAccepted }); o.title('已保存流程'); api.create.mockResolvedValue({ id: 'created', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' }); api.get.mockResolvedValue(template({ id: 'created', revision: 1, version: 0, layoutVersion: 0 }))
    await o.save(); const permit = goAccepted.mock.calls[0]![1]; expect(o.canLeave().kind).toBe('BLOCK'); expect(o.canLeave({ destination: '/workflows/other', handoff: permit }).kind).toBe('BLOCK'); expect(o.canLeave({ destination: '/workflows/created', handoff: permit }).kind).toBe('ALLOW')
    navigate.mockResolvedValue(true); await o.recover(); expect(api.create).toHaveBeenCalledTimes(1); expect(api.get).toHaveBeenCalledTimes(1); expect(o.canLeave().kind).toBe('ALLOW')
  })
  it('root retirement after graph request prevents layout, GET, draft patch and any late writer', async () => {
    const o = await owner(), pending = deferred<WorkflowReceipt>(); o.title('原流程'); api.revise.mockReturnValue(pending.promise); const saving = o.save(); await flush(); o.retire(true); const snapshot = JSON.stringify(o.getSnapshot()); pending.resolve(graphReceipt); await saving
    expect(api.layout).not.toHaveBeenCalled(); expect(api.get).toHaveBeenCalledTimes(1); expect(JSON.stringify(o.getSnapshot())).toBe(snapshot); await o.save(); expect(api.revise).toHaveBeenCalledTimes(1)
  })
  it('old lease GET cannot patch a remounted owner or revive retired draft', async () => {
    const pending = deferred<WorkflowTemplate>(); api.get.mockReturnValueOnce(pending.promise); const o = createWorkflowEditorController({ id: 'example' }), old = o.attachView(); await flush(); old(); api.get.mockResolvedValue(template({ title: '新读取' })); const current = o.attachView(); disposers.push(() => { current(); o.retire(true) }); await flush(); const snapshot = JSON.stringify(o.getSnapshot()); pending.resolve(template({ title: '旧读取' })); await flush(); expect(JSON.stringify(o.getSnapshot())).toBe(snapshot)
  })
  it('connect duplicate/cycle/self and bound deletion reject, while positions/history and public binding undo remain one edit each', async () => {
    const o = await owner(); o.add('human'); const added = o.getSnapshot().selected; o.joinPair('review', added); expect(o.getSnapshot().draft.graph.edges).toHaveLength(1); const edits = o.getSnapshot().undoCount
    o.joinPair('review', added); o.joinPair(added, 'review'); o.joinPair('review', 'review'); expect(o.getSnapshot().undoCount).toBe(edits); expect(o.getSnapshot().draft.graph.edges).toHaveLength(1)
    const draft = o.getSnapshot().draft; o.graph({ ...draft.graph, nodes: draft.graph.nodes.map(n => n.id === added ? { ...n, inputs: [{ name: 'result', source: 'NODE', sourceId: 'review', output: 'result', kind: 'TEXT', required: true }] } : n) }); o.remove('review'); expect(o.getSnapshot().draft.graph.nodes).toHaveLength(2); expect(o.getSnapshot().error).toContain('调整')
    o.history(true); o.history(true); expect(o.getSnapshot().draft.graph.edges).toHaveLength(0); o.history(false); expect(o.getSnapshot().draft.graph.edges).toHaveLength(1)
  })
})
