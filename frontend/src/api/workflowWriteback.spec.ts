import { afterEach, expect, it, vi } from 'vitest'
import { workflowWriteback } from './workflowWriteback'
afterEach(() => vi.unstubAllGlobals())
it('现场检查必须明确携带本地 UI 标识和固定来源，支持取消读取', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{}')); vi.stubGlobal('fetch', fetch)
  const body = { revision: 2, node: 'work', attempt: 'attempt', output: 'code', sourceSha256: 'hash' }, signal = new AbortController().signal
  await workflowWriteback.preview('req/one', body, signal)
  const [url, options] = fetch.mock.calls[0]!; expect(url).toBe('/api/workflows/requirements/req%2Fone/publication/writeback/preview'); expect(options.method).toBe('POST'); expect(options.headers['X-Loopper-Local-UI']).toBe('1'); expect(JSON.parse(options.body)).toEqual(body); expect(options.signal).toBe(signal)
})

it('确认和恢复保留固定身份与版本并携带本地标识，状态读取可取消', async () => {
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}'))); vi.stubGlobal('fetch', fetch)
  const body = { requestKey: 'request', expectedVersion: 7, selection: { revision: 2, node: 'work', attempt: 'attempt', output: 'code', sourceSha256: 'source' }, previewSha256: 'preview' }
  await workflowWriteback.confirm('req/one', body); await workflowWriteback.retry('req/one', 9); const signal = new AbortController().signal; await workflowWriteback.status('req/one', signal)
  expect(fetch.mock.calls[0]![0]).toBe('/api/workflows/requirements/req%2Fone/publication/writeback/confirm'); expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual(body)
  expect(fetch.mock.calls[1]![0]).toContain('/writeback/retry'); expect(JSON.parse(fetch.mock.calls[1]![1].body)).toEqual({ expectedVersion: 9 })
  for (const call of fetch.mock.calls.slice(0, 2)) expect(call[1].headers['X-Loopper-Local-UI']).toBe('1')
  expect(fetch.mock.calls[2]![1].signal).toBe(signal)
})
