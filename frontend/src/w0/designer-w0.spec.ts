import { afterEach, beforeEach, describe, it, vi } from 'vitest'
import { designerW0Contract } from '@/pages/w5/designer/w0-contract'

// Frozen 21 W0 definitions retain their original names and behavioral assertions.
// Only API/stream transports are mocked; helper renders the actual React page and TS owner.
// Initial creation is a legacy owner contract, not a newly invented reachable route.
vi.mock('@/api/client', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/client')>()
  return { ...original, api: Object.fromEntries(Object.keys(original.api).map(key => [key, vi.fn()])), subscribeDesignerEvents: vi.fn() }
})
beforeEach(() => { vi.resetAllMocks(); sessionStorage.clear(); vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('W0 forbids unmocked network')))) })
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); sessionStorage.clear(); document.body.innerHTML = '' })

describe('B5.1 ordinary Designer drafts require real route confirmation', () => {
  for (const kind of ['initial-text', 'followup-text', 'initial-file', 'followup-file']) it(`${kind}: declining leave preserves draft`, async () => {
    await designerW0Contract('B5.1', kind)
  })
  it('saved read-only Designer can leave without a confirmation or write', async () => {
    await designerW0Contract('B5.1', 'saved-readonly')
  })
})
describe('B5.2 unresolved Designer writes forbid route leave', () => {
  for (const channel of ['initial', 'followup']) for (const phase of ['sending', 'unknown']) it(`${channel}/${phase} retains File owner`, async () => {
    await designerW0Contract('B5.2', `${channel}/${phase}`)
  })
})
describe('B5.3 accepted Task handoff retains original identity', () => {
  for (const failure of ['guard-false', 'reject']) it(`${failure}: confirmed Task retains an explicit navigation-only recovery`, async () => {
    await designerW0Contract('B5.3', failure)
  })
})
describe('B6.1 retired question callbacks stay with original owner', () => {
  it('reply accepted after retirement must not launch a new A refresh or alter independent B', async () => {
    await designerW0Contract('B6.1', 'late-question')
  })
  it('reject modal path is unreachable: mandatory question exposes no reject action', async () => {
    await designerW0Contract('B6.1', 'mandatory-no-reject')
  })
})
describe('B6.2 profile preview/modal await scope', () => {
  for (const pause of ['preview', 'modal']) it(`${pause}: retirement invalidates profile action before mutation`, async () => {
    await designerW0Contract('B6.2', pause)
  })
})
describe('B6.3 follow-up immutable multipart operation and later draft', () => {
  it('unknown recovery uses original body/File and leaves later reachable edits unsent', async () => {
    await designerW0Contract('B6.3', 'unknown-file-later-draft')
  })
  it('typing while a real send is in-flight remains an unsent draft after original acknowledgement', async () => {
    await designerW0Contract('B6.3', 'pending-later-draft')
  })
})
describe('B7.1 owned terminal retry timeout immediate retirement', () => {
  it('first snapshot clears active retry before any natural callback completes', async () => {
    await designerW0Contract('B7.1', 'timeout')
  })
})
describe('B7.2 owned composer focus RAF immediate retirement', () => {
  it('retirement cancels queued composer focus before it can focus another page', async () => {
    await designerW0Contract('B7.2', 'raf')
  })
})
describe('B7.3 storage failures cannot discard accepted identity', () => {
  it('accepted initial File receipt stays recoverable when workspace persistence fails', async () => {
    await designerW0Contract('B7.3', 'accepted-file-storage')
  })
  it('explicit session recovery remains usable when optional workspace storage is unwritable', async () => {
    await designerW0Contract('B7.3', 'explicit-session-storage')
  })
})
