import { afterEach, expect, it, vi } from 'vitest'
import { writeFileSync } from 'node:fs'
import { api } from '@/api/client'
import { createStoryAccountingOwner } from '@/app/storyAccounting'
import { flushPromises } from '@/test/async'
import type { StoryAccountingCall } from '@/types/domain'

const stream = vi.hoisted(() => ({ ready: () => {}, close: vi.fn() }))
vi.mock('@/api/client', async original => ({ ...await original<typeof import('@/api/client')>(), subscribeStoryAccountingEvents: (_change: () => void, ready: () => void) => { stream.ready = ready; return { close: stream.close } } }))
let owner: ReturnType<typeof createStoryAccountingOwner> | undefined
afterEach(() => { owner?.dispose(); owner = undefined; vi.restoreAllMocks(); vi.useRealTimers() })

it('independent Root rejects a retired accounting selection error without replacing the current selection or command', async () => {
  vi.useFakeTimers()
  const call = (id: string): StoryAccountingCall => ({ id, state: 'PREPARED', operation: 'start', systemCode: 'MOCK', storyCode: 'mock', role: 'ROUTER', startedAt: '2026-10-03T00:00:00Z', parts: [], retryAvailable: true })
  let rejectOld!: (cause: unknown) => void
  const old = new Promise<StoryAccountingCall>((_, reject) => { rejectOld = reject })
  vi.spyOn(api, 'getStoryAccountingCalls').mockResolvedValue([call('A'), call('B')])
  const read = vi.spyOn(api, 'getStoryAccountingCall').mockReturnValueOnce(old).mockResolvedValue(call('B'))
  const cancel = vi.spyOn(api, 'cancelStoryAccountingCall'), retry = vi.spyOn(api, 'retryStoryAccountingCall'), dismiss = vi.spyOn(api, 'dismissStoryAccountingCall')
  owner = createStoryAccountingOwner(); owner.start(); stream.ready(); await flushPromises()
  expect(read.mock.calls).toEqual([['A']])
  owner.select('B')
  const command = owner.getSnapshot().command
  rejectOld(new Error('A retired selection failure')); await flushPromises()
  const state = owner.getSnapshot()
  const proof = { selectedId: state.selectedId, error: state.error, command: state.command, readCalls: read.mock.calls, mutationCount: cancel.mock.calls.length + retry.mock.calls.length + dismiss.mock.calls.length }
  writeFileSync('/workspace/react-full-w6-evidence/A-root-story-read-independent-observation.json', JSON.stringify(proof, null, 2))
  expect(state.selectedId).toBe('B')
  expect(state.command).toEqual(command)
  expect(proof.mutationCount).toBe(0)
  expect(state.error).toBe('') // First settled response, before any timer or later B response.
})
