import { afterEach, describe, expect, it, vi } from 'vitest'
import { pptApi } from './ppt'
import { pptDocument } from '@/components/ppt/pptTestFixtures'
describe('PPT REST 边界', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('reads project choices and document-bound sources without a knowledge conversation', async () => {
    const fetch = vi.fn(async (_url: string) => new Response(JSON.stringify({ items: [], nextCursor: null })))
    vi.stubGlobal('fetch', fetch)
    await pptApi.projects('支付平台', 'next-page')
    expect(fetch.mock.calls[0]?.[0]).toBe('/api/ppt/projects?query=%E6%94%AF%E4%BB%98%E5%B9%B3%E5%8F%B0&cursor=next-page')
    await pptApi.knowledge('presentation /1')
    expect(fetch.mock.calls[1]?.[0]).toBe('/api/ppt/documents/presentation%20%2F1/knowledge')
  })
  it('sends explicit requirements confirmation as a separate versioned fact', async () => {
    const fetch = vi.fn(async () => new Response('{}'))
    vi.stubGlobal('fetch', fetch)
    await pptApi.reply('doc', 'question', { expectedRevision: 3, idempotencyKey: 'confirm', version: 8, answer: '确认以上需求，请开始设计', confirmed: true })
    const [, options] = fetch.mock.calls[0] as unknown as [string, RequestInit]
    expect(JSON.parse(String(options.body))).toEqual({ expectedRevision: 3, idempotencyKey: 'confirm', version: 8, answer: '确认以上需求，请开始设计', confirmed: true })
  })
  it('keeps revision, operation identity and local UI guard in one atomic request', async () => {
    const fetch = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            revision: 4,
            deck: {},
            createdIds: {},
          }),
        ),
    )
    vi.stubGlobal('fetch', fetch)
    await pptApi.operations(
      '含空格 /作品',
      3,
      [
        {
          op: 'update_element',
          slideId: 'slide',
          elementId: 'text',
          patch: {
            text: '保留事实',
          },
        },
      ],
      'request-1',
    )
    const [path, options] = fetch.mock.calls[0] as unknown as [string, RequestInit]
    expect(path).toContain('%E5%90%AB%E7%A9%BA%E6%A0%BC%20%2F%E4%BD%9C%E5%93%81/operations')
    expect(options.headers).toMatchObject({
      'X-Loopper-Local-UI': '1',
    })
    expect(JSON.parse(String(options.body))).toEqual({
      expectedRevision: 3,
      idempotencyKey: 'request-1',
      operations: [
        {
          op: 'update_element',
          slideId: 'slide',
          elementId: 'text',
          patch: {
            text: '保留事实',
          },
        },
      ],
    })
  })
  it('rejects unknown phases before they can enable workflow actions', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              ...pptDocument(),
              phase: 'MODEL_SAYS_DONE',
            }),
          ),
      ),
    )
    await expect(pptApi.get('doc')).rejects.toThrow('unknown state')
  })
  it('uploads the original File without a JSON multipart content type', async () => {
    const fetch = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            id: 'asset',
          }),
        ),
    )
    vi.stubGlobal('fetch', fetch)
    const file = new File(['image'], '图.png', {
      type: 'image/png',
    })
    await pptApi.upload('doc', file, 'assets', 'upload-1')
    const [, options] = fetch.mock.calls[0] as unknown as [string, RequestInit]
    expect(options.headers).not.toHaveProperty('Content-Type')
    expect(options.body).toBeInstanceOf(FormData)
    expect((options.body as FormData).get('file')).toBe(file)
    expect((options.body as FormData).get('idempotencyKey')).toBe('upload-1')
  })
  it('queries archived documents through the server archive filter', async () => {
    const fetch = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            items: [],
            nextCursor: null,
          }),
        ),
    )
    vi.stubGlobal('fetch', fetch)
    await pptApi.list({
      archived: true,
      query: '季度',
    })
    const [url] = fetch.mock.calls[0] as unknown as [string]
    expect(url).toContain('archive=archived')
    expect(url).not.toContain('archived=true')
  })
  it('treats an empty generation response as a legacy draft', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('')),
    )
    await expect(pptApi.generation('doc')).resolves.toBeNull()
  })
  it('uses the automatic generation contract without frontend phase actions', async () => {
    const fetch = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            state: 'PLANNING',
          }),
        ),
    )
    vi.stubGlobal('fetch', fetch)
    await pptApi.generate('doc', 7, '制作季度汇报', 'generation-key')
    const [url, options] = fetch.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/ppt/documents/doc/generate')
    expect(JSON.parse(String(options.body))).toEqual({
      expectedRevision: 7,
      prompt: '制作季度汇报',
      idempotencyKey: 'generation-key',
    })
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
