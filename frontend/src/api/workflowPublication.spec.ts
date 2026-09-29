import { afterEach, expect, it, vi } from 'vitest'
import { workflowPublication } from './workflowPublication'
afterEach(() => vi.unstubAllGlobals())
it('提交与恢复携带本地页面标识和固定请求正文', async () => {
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}'))); vi.stubGlobal('fetch', fetch)
  const body = { requestKey: 'request-key', expectedVersion: 7, revision: 2, node: 'work', attempt: 'a', output: 'code', previewSha256: 'sha', message: '成果' }
  await workflowPublication.confirm('req/one', body); await workflowPublication.retry('req/one', 3)
  expect(fetch.mock.calls[0]![0]).toBe('/api/workflows/requirements/req%2Fone/publication'); expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual(body)
  expect(fetch.mock.calls[1]![0]).toBe('/api/workflows/requirements/req%2Fone/publication/retry'); expect(JSON.parse(fetch.mock.calls[1]![1].body)).toEqual({ expectedVersion: 3 })
  for (const [, options] of fetch.mock.calls) { expect(options.method).toBe('POST'); expect(options.headers['X-Loopper-Local-UI']).toBe('1') }
})
it('成果预览仅发出带固定计划和来源的读取请求，并保留取消信号', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{}')); vi.stubGlobal('fetch', fetch); const controller = new AbortController()
  await workflowPublication.sources('req/one', 3, 'next&cursor', controller.signal)
  expect(fetch.mock.calls[0]![0]).toBe('/api/workflows/requirements/req%2Fone/publication/sources?revision=3&cursor=next%26cursor&limit=20')
  fetch.mockResolvedValue(new Response('{}'))
  await workflowPublication.preview('req/one', 3, { nodeKey: 'node one', nodeTitle: '', attemptId: 'try/one', ordinal: 1, attemptState: 'SUCCEEDED', outputName: 'code&one', outputTitle: '', createdAt: '', changedFiles: 1, totalFiles: 1 }, controller.signal)
  expect(fetch.mock.calls[1]![0]).toBe('/api/workflows/requirements/req%2Fone/publication/preview?revision=3&node=node+one&attempt=try%2Fone&output=code%26one')
  for (const [, options] of fetch.mock.calls) { expect(options.method).toBeUndefined(); expect(options.body).toBeUndefined(); expect(options.signal).toBe(controller.signal) }
})
