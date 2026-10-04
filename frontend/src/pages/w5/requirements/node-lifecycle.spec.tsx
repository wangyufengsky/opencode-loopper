import { StrictMode } from 'react'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { workflowRuns } from '@/api/workflowRuns'
import { NodeRun } from './NodeRun'
import { createNodeController } from './nodeController'
import { attempt, deferred, requirement, requirementFixture, requirementFrame, setupRequirementDom } from './test-support'

let fixture: ReturnType<typeof requirementFixture> | undefined
beforeEach(() => { setupRequirementDom(); vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('unmocked transport forbidden'))) })
afterEach(() => { cleanup(); fixture?.dispose(); fixture = undefined; vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('actual StrictMode pending attempt read is replaced by the new view lease and the late retired read cannot leave loading or overwrite metadata', async () => {
  const node = requirement().graph.nodes[0]!, old = deferred<ReturnType<typeof attempt>>(), authoritative = attempt({ version: 2, state: 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true })
  const read = vi.spyOn(workflowRuns, 'attempt').mockReturnValueOnce(old.promise).mockResolvedValue(authoritative)
  vi.spyOn(workflowRuns, 'attempts').mockResolvedValue({ items: [authoritative], nextCursor: null })
  const definition = vi.spyOn(workflowRuns, 'definition').mockResolvedValue(node)
  const summary = { id: 'node', nodeKey: node.id, state: 'SUCCEEDED' as const, attemptCount: 1, latestAttemptId: authoritative.id, version: 2, outcome: null }
  const owner = createNodeController('req', { version: 7, node, summary })
  fixture = requirementFixture()
  render(<StrictMode>{requirementFrame(<NodeRun page={fixture.props} node={node} summary={summary} version={7} controller={owner} />)}</StrictMode>)
  await act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve() })
  expect(owner.viewCount()).toBe(1)
  await waitFor(() => expect(read).toHaveBeenCalledTimes(2))
  await screen.findByRole('button', { name: '交付物' })
  expect(owner.getSnapshot().metadata).toEqual(authoritative)
  expect(owner.getSnapshot().loading).toBe(false)
  expect(definition).toHaveBeenCalledTimes(1)
  await act(async () => { old.reject(new Error('late previous view read')); await old.promise.catch(() => undefined) })
  expect(owner.getSnapshot().metadata).toEqual(authoritative)
  expect(owner.getSnapshot().error).toBe('')
  expect(owner.getSnapshot().loading).toBe(false)
})
