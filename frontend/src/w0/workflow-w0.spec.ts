import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { workflowRuns } from '@/api/workflowRuns'
import { workflowApi } from '@/api/workflow'
import { api as clientApi } from '@/api/client'
import { workflowPublication } from '@/api/workflowPublication'
import { workflowPush } from '@/api/workflowPush'
import { workflowWriteback } from '@/api/workflowWriteback'
import { template, summary } from '@/components/workflow/workflowTestFixtures'
import { attempt, candidate, execution, requirement } from '@/components/workflow/workflowRunTestFixtures'
import { workflowLibraryW0Contract } from '@/pages/w2/workflow/workflow-library-w0-contract'
import { createAcknowledgedOperation } from '@/domain/acknowledgedOperation'
import type { AppSettings } from '@/types/domain'

// Same 32 frozen W0 contracts: transport-only mocks; assertions live in real React/TS helpers.
// Baseline names/status/source hashes are preserved in evidence/w5; no legacy Vue business fixture.
vi.mock('@/api/workflowRuns', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/workflowRuns')>()
  return { ...original, workflowRuns: Object.fromEntries(Object.keys(original.workflowRuns).map(key => [key, vi.fn()])) }
})
vi.mock('@/api/workflow', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/workflow')>()
  return { ...original, workflowApi: Object.fromEntries(Object.keys(original.workflowApi).map(key => [key, vi.fn()])) }
})
vi.mock('@/api/workflowPublication', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/workflowPublication')>()
  return { ...original, workflowPublication: Object.fromEntries(Object.keys(original.workflowPublication).map(key => [key, vi.fn()])) }
})
vi.mock('@/api/workflowPush', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/workflowPush')>()
  return { ...original, workflowPush: Object.fromEntries(Object.keys(original.workflowPush).map(key => [key, vi.fn()])) }
})
vi.mock('@/api/workflowWriteback', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/workflowWriteback')>()
  return { ...original, workflowWriteback: Object.fromEntries(Object.keys(original.workflowWriteback).map(key => [key, vi.fn()])) }
})

import { requirementW0Contract } from '@/pages/w5/requirements/w0-contract'

const runs = vi.mocked(workflowRuns), flows = vi.mocked(workflowApi)
const receipt = { id: 'created', revision: 1, version: 1, layoutVersion: 0, state: 'PLANNING' as const }
function trace(caseId: string, evidence: unknown) { console.info('[W0]', JSON.stringify({ caseId, evidence })) }
beforeEach(() => {
  vi.resetAllMocks(); vi.spyOn(window, 'confirm').mockReturnValue(true)
  // Every accidental transport is a fixture failure; no server/provider can be contacted.
  vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('W0 forbids unmocked network'))))
  flows.get.mockResolvedValue(template({ revision: 3 })); flows.list.mockResolvedValue({ items: [summary()], nextCursor: undefined })
  runs.project.mockResolvedValue({ id: 'project', title: '测试项目' } as never)
  runs.projects.mockResolvedValue({ items: [], nextCursor: undefined, facets: {} })
  runs.get.mockResolvedValue(requirement({ state: 'RUNNING' })); runs.execution.mockResolvedValue(execution('RUNNING'))
  runs.finishStatus.mockResolvedValue({ requirementId: 'req', state: 'RUNNING', version: 7, intent: null, pending: { attempts: 0, resources: 0 } })
  runs.attempts.mockResolvedValue({ items: [attempt()], nextCursor: null }); runs.attempt.mockResolvedValue(attempt())
  runs.definition.mockResolvedValue(requirement().graph.nodes[0]!); runs.candidates.mockResolvedValue({ items: [{ ...candidate(), sourceState: 'RUNNING', createdAt: '' }], nextCursor: null }); runs.candidate.mockResolvedValue(candidate())
  vi.spyOn(clientApi, 'getSettings').mockResolvedValue({ openCode: { provider: 'fixture', model: 'fixture' } } as AppSettings)
  vi.spyOn(clientApi, 'getSettingsModels').mockResolvedValue([])
  vi.mocked(workflowPublication.status).mockResolvedValue(null); vi.mocked(workflowPush.status).mockResolvedValue(null); vi.mocked(workflowWriteback.status).mockResolvedValue(null)
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = '' })

describe('B1.1 New unresolved create navigation', () => {
  for (const phase of ['sending', 'unknown'] as const) for (const action of ['push', 'replace', 'back'] as const) it(`${phase}/${action} must retain original owner`, async () => {
    await requirementW0Contract({ group: 'B1.1', phase: phase === 'sending' ? 'SENDING' : 'UNKNOWN', action }, { proof: value => trace('B1.1', value) })
  })
  it('ordinary dirty decline preserves input and explicit discard remains allowed', async () => {
    await requirementW0Contract({ group: 'B1.1', variant: 'dirty' }, { proof: value => trace('B1.1', value) })
  })
})
describe('B1.2 Library unresolved copy/archive navigation', () => {
  for (const action of ['copy', 'archive'] as const) for (const phase of ['sending', 'unknown'] as const) it(`${action}/${phase} must block route leave`, async () => {
    await workflowLibraryW0Contract({ action, phase }, { receipt, proof: trace })
  })
  it('unknown copy keeps its original body/key while actual list filters change', async () => {
    await workflowLibraryW0Contract({ filters: true }, { receipt: { ...receipt, state: 'ACTIVE' }, proof: trace })
  })
})
describe('B1.3 accepted New receipt survives failed handoff', () => {
  for (const failure of ['guard-false', 'cancelled', 'reject'] as const) it(`${failure} retains receipt and offers navigation-only recovery`, async () => {
    await requirementW0Contract({ group: 'B1.3', failure }, { proof: value => trace('B1.3', value) })
  })
})
describe('B1.4 disabled New UI and independent immutable owner controls', () => {
  it('sending then unknown disables all four real form fields/choices and explicit retry keeps original body', async () => {
    await requirementW0Contract({ group: 'B1.4', variant: 'fields' }, { proof: value => trace('B1.4', value) })
  })
  it('pure owner ignores external draft projection and accepted read failure never repeats mutation', async () => {
    let projection = { title: '原标题', templateRevision: 3 }
    const body = { ...projection, requestKey: 'w0-owner-original-key' }
    const write = vi.fn().mockRejectedValueOnce(new Error('unknown')).mockResolvedValue(receipt)
    const read = vi.fn().mockRejectedValueOnce(new Error('read failed')).mockResolvedValue(undefined)
    const operation = createAcknowledgedOperation('原操作', () => write(body), read, () => true)
    await expect(operation.execute()).rejects.toThrow('unknown'); projection = { title: '新草稿', templateRevision: 4 }
    await expect(operation.execute()).rejects.toThrow('read failed'); await operation.execute()
    trace('B1.4', { projection, writeBodies: write.mock.calls, readCount: read.mock.calls.length })
    expect(write.mock.calls).toEqual([[body], [body]]); expect(write).toHaveBeenCalledTimes(2); expect(read).toHaveBeenCalledTimes(2)
    expect(projection.title).toBe('新草稿')
  })
})

describe('B2.1 real nested command guards', () => {
  for (const kind of ['human', 'candidate', 'finish', 'commit', 'push', 'writeback'] as const) for (const phase of ['sending', 'unknown'] as const) it(`${kind}/${phase} refuses leaving actual command panel`, async () => {
    await requirementW0Contract({ group: 'B2.1', kind, phase: phase === 'sending' ? 'SENDING' : 'UNKNOWN' }, { proof: value => trace('B2.1', value) })
  })
  it('actual Requirement parent refuses route leave while its Finish child is unknown', async () => {
    await requirementW0Contract({ group: 'B2.1', kind: 'parent-finish' }, { proof: value => trace('B2.1', value) })
  })
})
describe('B2.2 original process stop identity', () => {
  it('retains command version/attempt on explicit retry and blocks leaving unknown stop', async () => {
    await requirementW0Contract({ group: 'B2.2' }, { proof: value => trace('B2.2', value) })
  })
})
describe('B2.3 accepted write/readback split', () => {
  it('actual Finish child retains accepted readback and only rereads on recovery', async () => {
    await requirementW0Contract({ group: 'B2.3' }, { proof: value => trace('B2.3', value) })
  })
})
