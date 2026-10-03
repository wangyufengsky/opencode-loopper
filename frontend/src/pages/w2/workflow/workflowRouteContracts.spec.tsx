import { afterEach, beforeEach, describe, it, vi } from 'vitest'
import { workflowApi } from '@/api/workflow'
import { summary } from '@/components/workflow/workflowTestFixtures'
import { foundationDOM } from './page.test-support'
import { workflowLibraryW0Contract } from './workflow-library-w0-contract'

beforeEach(() => { foundationDOM(); vi.spyOn(workflowApi, 'list').mockResolvedValue({ items: [summary()] }); vi.spyOn(workflowApi, 'copy'); vi.spyOn(workflowApi, 'archive') })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
const receipt = { id: 'created', revision: 1, version: 1, layoutVersion: 0, state: 'ACTIVE' }
describe('W2 actual production route retarget of W0 B1.2', () => {
  for (const action of ['copy', 'archive'] as const) for (const phase of ['sending', 'unknown'] as const)
    it(`${action}/${phase} must block route leave`, async () => { await workflowLibraryW0Contract({ action, phase }, { receipt }) })
  it('unknown copy keeps its original body/key while actual list filters change', async () => { await workflowLibraryW0Contract({ filters: true }, { receipt }) })
})
