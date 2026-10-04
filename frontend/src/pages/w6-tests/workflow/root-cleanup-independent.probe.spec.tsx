import { afterEach, expect, it, vi } from 'vitest'
import { act } from 'react'
import { mountReactApplication } from '@/app/bootstrap'
import { foundationDOM } from '@/pages/w2/workflow/page.test-support'
import { writeFileSync } from 'node:fs'
import { api } from '@/api/client'
import { createStoryAccountingOwner } from '@/app/storyAccounting'
import { flushPromises } from '@/test/async'
import type { StoryAccountingCall } from '@/types/domain'
const events = vi.hoisted(() => ({ ready: () => {}, close: vi.fn() }))
vi.mock('@/api/client', async original => ({ ...await original<typeof import('@/api/client')>(), subscribeStoryAccountingEvents: (_change: () => void, ready: () => void) => { events.ready = ready; return { close: events.close } } }))
afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); events.close.mockReset() })
it('independent Root accounting disposal releases every owned resource even when stream close throws', async () => {
  vi.useFakeTimers()
  const row: StoryAccountingCall = { id: 'owned-original', state: 'PREPARED', operation: 'start', systemCode: 'MOCK', storyCode: 'mock', role: 'ROUTER', startedAt: '2026-10-03T00:00:00Z', parts: [], retryAvailable: true }
  vi.spyOn(api, 'getStoryAccountingCalls').mockResolvedValue([row]); vi.spyOn(api, 'getStoryAccountingCall').mockResolvedValue(row)
  const add = vi.spyOn(document, 'addEventListener'), remove = vi.spyOn(document, 'removeEventListener')
  const owner = createStoryAccountingOwner(); owner.start(); events.ready(); await flushPromises()
  const callback = add.mock.calls.find(([type]) => type === 'visibilitychange')![1]
  const before = vi.getTimerCount(); expect(before).toBeGreaterThan(0)
  events.close.mockImplementation(() => { throw new Error('owned stream close failed') })
  let failure: unknown; try { owner.dispose() } catch (cause) { failure = cause }
  const proof = { beforeTimers: before, firstAfterTimers: vi.getTimerCount(), ownedVisibilityRemoved: remove.mock.calls.some(([type, listener]) => type === 'visibilitychange' && listener === callback), closeCalls: events.close.mock.calls.length, cleanupFailureVisible: failure instanceof Error }
  writeFileSync('/workspace/react-full-w6-evidence/A-root-cleanup-independent-observation.json', JSON.stringify(proof, null, 2))
  expect(proof.cleanupFailureVisible).toBe(true)
  expect(proof.ownedVisibilityRemoved).toBe(true)
  expect(proof.firstAfterTimers).toBe(0) // Before any later event or timer advance.
})

it('independent Root releases its real React tree after forced disposal with an owned cleanup failure', async () => {
  foundationDOM()
  vi.spyOn(api, 'getStoryAccountingCalls').mockResolvedValue([])
  const host = document.createElement('div'); document.body.append(host)
  const Page = () => <p data-owned-root-tree>原真实 React 根</p>
  let root!: ReturnType<typeof mountReactApplication>
  await act(async () => { root = mountReactApplication(host, { initialEntries: ['/review'], routes: [{ path: '/review', Component: Page }], shell: false, strict: false }); await Promise.resolve() })
  expect(host.querySelector('[data-owned-root-tree]')).not.toBeNull()
  events.close.mockImplementation(() => { throw new Error('owned root stream close failed') })
  let result: boolean | undefined, failure: unknown
  await act(async () => { try { result = root.unmount(true) } catch (cause) { failure = cause } })
  const proof = { applicationRetired: !root.application.active, realRootTreePresent: !!host.querySelector('[data-owned-root-tree]'), returnedHealthy: result, cleanupFailureVisible: !!failure || result === false }
  writeFileSync('/workspace/react-full-w6-evidence/A-root-tree-independent-observation.json', JSON.stringify(proof, null, 2))
  expect(proof.cleanupFailureVisible).toBe(true)
  expect(proof.applicationRetired).toBe(true)
  expect(proof.realRootTreePresent).toBe(false) // First sample, no post-unmount event or timer.
  host.remove()
})
