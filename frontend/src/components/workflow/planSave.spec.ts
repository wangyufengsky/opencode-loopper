import { beforeEach, describe, expect, it, vi } from 'vitest'
import { workflowRuns } from '@/api/workflowRuns'
import { preparePlanSave, savePlan } from './planSave'
import { candidate, requirement } from './workflowRunTestFixtures'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { revise: vi.fn(), applyPlan: vi.fn(), applyCandidate: vi.fn(), layout: vi.fn(), get: vi.fn() } }))
const api = vi.mocked(workflowRuns)
beforeEach(() => { vi.resetAllMocks(); api.get.mockResolvedValue(requirement()) })
describe('requirement plan persistence', () => {
  it('retains the graph receipt after a layout timeout and never overwrites the source draft', async () => {
    const base = requirement(), graph = structuredClone(base.graph); graph.nodes[0]!.title = '修订节点'
    const op = preparePlanSave(base, graph, base.layout); graph.nodes[0]!.title = '后续修改'
    api.revise.mockResolvedValue({ id: 'req', revision: 3, version: 4, layoutVersion: 4, state: 'PLANNING' }); api.layout.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'req', revision: 3, version: 4, layoutVersion: 5, state: 'PLANNING' })
    await expect(savePlan(op)).rejects.toThrow(); await savePlan(op)
    expect(api.revise).toHaveBeenCalledTimes(1); expect(api.revise.mock.calls[0]![1].graph.nodes[0]!.title).toBe('修订节点'); expect(api.layout.mock.calls[0]).toEqual(api.layout.mock.calls[1]); expect(api.layout.mock.calls[0]![1].expectedRevision).toBe(3)
  })
  it('saves runtime layout without revising executable content and retries only failed readback', async () => {
    const base = requirement({ state: 'RUNNING' }), op = preparePlanSave(base, base.graph, { ...base.layout, x: 90 })
    api.layout.mockResolvedValue({ id: 'req', revision: 2, version: 3, layoutVersion: 5, state: 'RUNNING' }); api.get.mockRejectedValueOnce(new Error('offline')).mockResolvedValue(base)
    await expect(savePlan(op)).rejects.toThrow(); await savePlan(op); expect(api.revise).not.toHaveBeenCalled(); expect(api.layout).toHaveBeenCalledTimes(1)
  })
  it('applies an explicitly confirmed candidate once even when its graph equals the current plan', async () => {
    const base = requirement({ state: 'PAUSED' }), draft = candidate({ graph: base.graph }), operation = preparePlanSave(base, base.graph, base.layout, draft)
    api.applyCandidate.mockResolvedValue({ id: 'req', revision: 3, version: 7, layoutVersion: 4, state: 'PAUSED' }); api.layout.mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ id: 'req', revision: 3, version: 7, layoutVersion: 5, state: 'PAUSED' })
    await expect(savePlan(operation)).rejects.toThrow(); await savePlan(operation)
    expect(api.applyCandidate).toHaveBeenCalledTimes(1); expect(api.applyCandidate.mock.calls[0]!.slice(0, 2)).toEqual(['req', 'candidate']); expect(api.applyCandidate.mock.calls[0]![2]).toMatchObject({ expectedVersion: 3, expectedRevision: 2, expectedCandidateVersion: 0 }); expect(api.applyPlan).not.toHaveBeenCalled(); expect(api.revise).not.toHaveBeenCalled()
  })
  it('preserves the exact runtime adjustment on an unknown apply response', async () => {
    const base = requirement({ state: 'PAUSED' }), graph = structuredClone(base.graph); graph.nodes[0]!.task = '调整后续工作'
    const operation = preparePlanSave(base, graph, base.layout); api.applyPlan.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'req', revision: 3, version: 7, layoutVersion: 4, state: 'PAUSED' }); api.layout.mockResolvedValue({ id: 'req', revision: 3, version: 7, layoutVersion: 5, state: 'PAUSED' })
    await expect(savePlan(operation)).rejects.toThrow(); graph.nodes[0]!.task = '不能覆盖原请求'; await savePlan(operation)
    expect(api.applyPlan.mock.calls[0]).toEqual(api.applyPlan.mock.calls[1]); expect(api.applyPlan.mock.calls[0]![1].graph.nodes[0]!.task).toBe('调整后续工作'); expect(api.revise).not.toHaveBeenCalled()
  })

})
