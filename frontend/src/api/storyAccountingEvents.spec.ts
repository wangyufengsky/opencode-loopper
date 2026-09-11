import { afterEach, expect, it, vi } from 'vitest'
import { subscribeStoryAccountingEvents } from './client'

afterEach(() => vi.unstubAllGlobals())
it('reconciles only after the server ready event and detaches all listeners on close', () => {
  class Source extends EventTarget {
    static latest: Source
    onmessage: (() => void) | null = null
    close = vi.fn()
    constructor(readonly url: string) { super(); Source.latest = this }
  }
  vi.stubGlobal('EventSource', Source)
  const changed = vi.fn(), ready = vi.fn()
  const stream = subscribeStoryAccountingEvents(changed, ready)
  const source = Source.latest
  expect(source.url).toBe('/api/story-accounting/events')
  source.dispatchEvent(new Event('open'))
  expect(ready).not.toHaveBeenCalled()
  source.dispatchEvent(new Event('ready'))
  source.onmessage?.()
  source.dispatchEvent(new Event('ready'))
  expect(ready).toHaveBeenCalledTimes(2)
  expect(changed).toHaveBeenCalledTimes(1)
  stream.close()
  source.dispatchEvent(new Event('ready'))
  expect(ready).toHaveBeenCalledTimes(2)
  expect(source.onmessage).toBeNull()
  expect(source.close).toHaveBeenCalledTimes(1)
})
