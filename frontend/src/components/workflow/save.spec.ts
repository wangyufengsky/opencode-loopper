import { beforeEach, describe, expect, it, vi } from 'vitest'
import { workflowApi } from '@/api/workflow'
import { prepareCopy, prepareSave, saveDraft } from './save'
import { template } from './workflowTestFixtures'
vi.mock('@/api/workflow', () => ({ workflowApi: { create: vi.fn(), revise: vi.fn(), layout: vi.fn(), get: vi.fn(), copy: vi.fn() } }))
const api = vi.mocked(workflowApi)
beforeEach(() => { vi.resetAllMocks() })
describe('workflow persistence across partial failures', () => {
  it('copies an exact source revision with provenance and replays uncertain copy responses', async () => {
    const operation = prepareCopy(template({ builtin: true }))
    api.copy.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'copy', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' }); api.get.mockResolvedValue(template({ id: 'copy' }))
    await expect(saveDraft(operation)).rejects.toThrow(); await saveDraft(operation)
    expect(api.copy.mock.calls[0]).toEqual(api.copy.mock.calls[1]); expect(api.copy).toHaveBeenCalledWith('example', expect.objectContaining({ sourceRevision: 2, title: '交付流程 副本' })); expect(api.create).not.toHaveBeenCalled()
  })
  it('replays a lost create response with the same immutable payload and key', async () => {
    const draft = template(), operation = prepareSave(draft, null)
    draft.title = '后来的编辑'; api.create.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'created', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' }); api.get.mockResolvedValue(template({ id: 'created' }))
    await expect(saveDraft(operation)).rejects.toThrow('timeout'); await saveDraft(operation)
    expect(api.create).toHaveBeenCalledTimes(2); expect(api.create.mock.calls[0]).toEqual(api.create.mock.calls[1]); expect(api.create.mock.calls[1]![0].title).toBe('交付流程'); expect(api.layout).not.toHaveBeenCalled()
  })
  it('retains the accepted graph revision and retries only its original layout command', async () => {
    const base = template(), draft = { ...base, title: '新名称' }, operation = prepareSave(draft, base)
    api.revise.mockResolvedValue({ id: base.id, revision: 3, version: 4, layoutVersion: 4, state: 'ACTIVE' })
    api.layout.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: base.id, revision: 3, version: 4, layoutVersion: 5, state: 'ACTIVE' }); api.get.mockResolvedValue(template({ revision: 3, version: 4, layoutVersion: 5 }))
    await expect(saveDraft(operation)).rejects.toThrow('timeout'); await saveDraft(operation)
    expect(api.revise).toHaveBeenCalledTimes(1); expect(api.revise.mock.calls[0]![1]).toMatchObject({ expectedVersion: 3, expectedRevision: 2 })
    expect(api.layout.mock.calls[0]).toEqual(api.layout.mock.calls[1]); expect(api.layout.mock.calls[0]![1]).toMatchObject({ expectedRevision: 3, expectedLayoutVersion: 4 })
  })
  it('saves only presentation when the graph is unchanged', async () => {
    const base = template(), draft = { ...base, layout: { ...base.layout, x: 80 } }
    api.layout.mockResolvedValue({ id: base.id, revision: 2, version: 3, layoutVersion: 5, state: 'ACTIVE' }); api.get.mockResolvedValue(base)
    await saveDraft(prepareSave(draft, base)); expect(api.revise).not.toHaveBeenCalled(); expect(api.layout.mock.calls[0]![1]).toMatchObject({ expectedRevision: 2, expectedLayoutVersion: 4, layout: { x: 80 } })
  })
  it('does not repeat writes when final readback fails', async () => {
    const base = template(), operation = prepareSave(base, base)
    api.layout.mockResolvedValue({ id: base.id, revision: 2, version: 3, layoutVersion: 5, state: 'ACTIVE' }); api.get.mockRejectedValueOnce(new Error('read timeout')).mockResolvedValue(base)
    await expect(saveDraft(operation)).rejects.toThrow(); await saveDraft(operation)
    expect(api.layout).toHaveBeenCalledTimes(1); expect(api.get).toHaveBeenCalledTimes(2)
  })
})
