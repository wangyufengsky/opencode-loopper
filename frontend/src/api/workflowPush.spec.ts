import { afterEach, expect, it, vi } from 'vitest'
import { workflowPush } from './workflowPush'
afterEach(() => vi.unstubAllGlobals())
it('目标检查和推送确认必须带本地页面标识，重试保留明确版本', async () => {
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}'))); vi.stubGlobal('fetch', fetch)
  const signal = new AbortController().signal, body = { requestKey: 'key', expectedVersion: 3, remote: 'origin', previewSha256: 'hash' }
  await workflowPush.preview('req/one', 'origin', signal); await workflowPush.confirm('req/one', body); await workflowPush.retry('req/one', 7)
  expect(fetch.mock.calls.map(call => call[0])).toEqual(['/api/workflows/requirements/req%2Fone/publication/push/preview', '/api/workflows/requirements/req%2Fone/publication/push', '/api/workflows/requirements/req%2Fone/publication/push/retry'])
  expect(fetch.mock.calls.map(call => JSON.parse(call[1].body))).toEqual([{ remote: 'origin' }, body, { expectedVersion: 7 }])
  for (const [, options] of fetch.mock.calls) { expect(options.method).toBe('POST'); expect(options.headers['X-Loopper-Local-UI']).toBe('1') }
  expect(fetch.mock.calls[0]![1].signal).toBe(signal)
})
it('页面重读和远端名称读取不发送写命令', async () => {
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('null'))); vi.stubGlobal('fetch', fetch); const signal = new AbortController().signal
  await workflowPush.status('req', signal); await workflowPush.remotes('req', signal)
  expect(fetch.mock.calls.map(call => call[0])).toEqual(['/api/workflows/requirements/req/publication/push', '/api/workflows/requirements/req/publication/push/remotes'])
  for (const [, options] of fetch.mock.calls) { expect(options.method).toBeUndefined(); expect(options.body).toBeUndefined(); expect(options.signal).toBe(signal) }
})
