import { beforeEach, afterEach, it, vi } from 'vitest'
import { api } from '@/api/client'
import { documentRun, sourceRun, stream } from './test-support'
import { templateRunNavigationW0Contract, templateRunIdentityW0Contract, templateCancelScopeW0Contract, templateRunReadonlyLeaveW0Contract } from './run-w0-contract'
beforeEach(() => {
  vi.useFakeTimers(); vi.stubGlobal('EventSource', class {})
  vi.spyOn(api, 'sourceTemplate').mockResolvedValue(sourceRun()); vi.spyOn(api, 'documentTemplate').mockResolvedValue(documentRun())
  vi.spyOn(api, 'sourceEvents').mockImplementation(() => stream() as unknown as EventSource); vi.spyOn(api, 'documentEvents').mockImplementation(() => stream() as unknown as EventSource)
  vi.spyOn(api, 'documentReports').mockResolvedValue({ items: [], facets: {} }); vi.spyOn(api, 'documentRequirements').mockResolvedValue({ items: [], nextOffset: null, revision: 1 })
  vi.spyOn(api, 'sourceBatches').mockResolvedValue({ items: [{ id: 'batch-a', ordinal: 0, retryable: true }, { id: 'batch-b', ordinal: 1, retryable: true }] as never[], facets: {} }); vi.spyOn(api, 'sourceArtifacts').mockResolvedValue({ items: [], facets: {} }); vi.spyOn(api, 'sourceTemplateCommand'); vi.spyOn(api, 'documentTemplateCommand')
})
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
for (const kind of ['source', 'document'] as const) for (const phase of ['inflight', 'unknown'] as const) it(`B3.2 real ${kind}/${phase} Vue history remains on original React run`, async () => { await templateRunNavigationW0Contract(kind, phase) })
for (const [kind, action] of [['source', 'cancel'], ['source', 'retry'], ['document', 'resume'], ['document', 'cancel']] as const) it(`B3.3 real ${kind}/${action} React recovery preserves original CAS body`, async () => { await templateRunIdentityW0Contract(kind, action) })
it('B4.1 real route replaces A with B and retires the unconfirmed cancellation modal', async () => { await templateCancelScopeW0Contract() })

for (const kind of ['source', 'document'] as const) it(`B3.2 real ${kind} read-only route closes SSE without STOP`, async () => { await templateRunReadonlyLeaveW0Contract(kind) })
