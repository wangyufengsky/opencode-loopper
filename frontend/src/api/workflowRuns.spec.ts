import { afterEach, describe, expect, it, vi } from 'vitest'
import { workflowRuns } from './workflowRuns'
afterEach(() => vi.unstubAllGlobals())
describe('requirement execution transport', () => {
  it('preserves separate control and requirement versions, exact checkpoint acknowledgements and local authority', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}'))); vi.stubGlobal('fetch', fetch)
    const body = { requestKey: 'original', expectedVersion: 5, expectedControlVersion: 9, mode: 'UNTIL' as const, targetKey: 'next', inputs: null, model: { providerId: 'fake', modelId: 'test', thinking: false }, checkpointAttempts: ['finished'] }
    await workflowRuns.start('req/one', body)
    expect(fetch).toHaveBeenLastCalledWith('/api/workflows/requirements/req%2Fone/control/start', expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }), body: JSON.stringify(body) }))
    const finish = { requestKey: 'original-finish', expectedVersion: 8, target: 'COMPLETED' as const, reason: '人工选择' }
    await workflowRuns.finish('req/one', finish)
    expect(fetch).toHaveBeenLastCalledWith('/api/workflows/requirements/req%2Fone/finish', expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }), body: JSON.stringify(finish) }))
    await workflowRuns.finishStatus('req/one'); expect(fetch.mock.calls.at(-1)![0]).toBe('/api/workflows/requirements/req%2Fone/finish')
    await workflowRuns.attempts('req/one', 'node name', 'cursor&next')
    expect(fetch.mock.calls.at(-1)![0]).toBe('/api/workflows/requirements/req%2Fone/nodes/node%20name/attempts?cursor=cursor%26next&limit=20')
    await workflowRuns.commandAction('req/one', 'node name', 'try/one', 'resume', { requestKey: 'original-command', expectedVersion: 12 })
    expect(fetch).toHaveBeenLastCalledWith('/api/workflows/requirements/req%2Fone/nodes/node%20name/attempts/try%2Fone/command/resume', expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'X-Loopper-Local-UI': '1' }), body: JSON.stringify({ requestKey: 'original-command', expectedVersion: 12 }) }))
    await workflowRuns.commandEvidence('req/one', 'node name', 'try/one'); expect(fetch.mock.calls.at(-1)![0]).toBe('/api/workflows/requirements/req%2Fone/nodes/node%20name/attempts/try%2Fone/command/evidence')
    await workflowRuns.activity('req', 'node', 'try/one'); expect(fetch.mock.calls.at(-1)![0]).toBe('/api/workflows/requirements/req/nodes/node/attempts/try%2Fone/activity')
    await workflowRuns.knowledgeEvidence('req/one', 'node name', 'try/one', 'next&cursor')
    expect(fetch.mock.calls.at(-1)![0]).toBe('/api/workflows/requirements/req%2Fone/nodes/node%20name/attempts/try%2Fone/knowledge?cursor=next%26cursor&limit=30')
    await workflowRuns.knowledgeEvidenceBody('req/one', 'node name', 'try/one', 'entry/one')
    expect(fetch.mock.calls.at(-1)![0]).toBe('/api/workflows/requirements/req%2Fone/nodes/node%20name/attempts/try%2Fone/knowledge/entry%2Fone')
    const controller = new AbortController()
    await workflowRuns.snapshotPartialReport('req/one', 'source node', 'try/one', controller.signal)
    expect(fetch).toHaveBeenLastCalledWith('/api/workflows/requirements/req%2Fone/nodes/source%20node/attempts/try%2Fone/snapshot-partial-report', expect.objectContaining({ signal: controller.signal }))
    await workflowRuns.inputContent('req/one', 'node name', 'try/one', 'parent draft', 123, controller.signal)
    expect(fetch).toHaveBeenLastCalledWith('/api/workflows/requirements/req%2Fone/nodes/node%20name/attempts/try%2Fone/inputs/parent%20draft/content?offset=123&limit=12000', expect.objectContaining({ signal: controller.signal }))
    await workflowRuns.changes('req/one', 'node name', 'try/one', 'outputs', 'chosen code', 'cursor&next')
    expect(fetch.mock.calls.at(-1)![0]).toBe('/api/workflows/requirements/req%2Fone/nodes/node%20name/attempts/try%2Fone/outputs/chosen%20code/changes?cursor=cursor%26next&limit=30')
    expect(workflowRuns.archiveUrl('req/one', 'node', 'run', 'outputs', 'document bundle')).toBe('/api/workflows/requirements/req%2Fone/nodes/node/attempts/run/outputs/document%20bundle/archive')
    expect(workflowRuns.fileUrl('req', 'node', 'run', 'inputs', 'parent result', 'src/中文文件.java')).toContain('/inputs/parent%20result/file?path=src%2F%E4%B8%AD%E6%96%87%E6%96%87%E4%BB%B6.java')
  })
})
