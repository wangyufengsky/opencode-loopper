import { afterEach, describe, expect, it, vi } from 'vitest'
import { workflowApi } from './workflow'
import { emptyGraph, emptyLayout } from '@/components/workflow/graph'
afterEach(() => { vi.unstubAllGlobals() })
describe('workflow transport contract', () => {
  it('reads paged preset summaries and the selected preset version without mutation headers', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}', { status: 200 }))); vi.stubGlobal('fetch', fetch)
    await workflowApi.presets('资料 分析', 'cursor/next')
    const url = new URL(fetch.mock.calls[0]![0] as string, 'http://localhost')
    expect(url.pathname).toBe('/api/workflows/node-presets'); expect(url.searchParams.get('query')).toBe('资料 分析'); expect(url.searchParams.get('cursor')).toBe('cursor/next'); expect(url.searchParams.get('limit')).toBe('20')
    await workflowApi.preset('preset/id', 3)
    expect(fetch.mock.calls[1]![0]).toBe('/api/workflows/node-presets/preset%2Fid/versions/3')
    expect(fetch.mock.calls[1]![1]).not.toMatchObject({ method: 'POST' })
  })
  it('preserves local UI authorization, CAS, request keys and distinct graph/layout bodies', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}', { status: 200 }))); vi.stubGlobal('fetch', fetch)
    const body = { requestKey: 'stable-key', expectedVersion: 4, expectedRevision: 3, title: '流程', description: '', graph: emptyGraph() }
    await workflowApi.revise('id/encoded', body)
    expect(fetch).toHaveBeenCalledWith('/api/workflows/templates/id%2Fencoded', expect.objectContaining({ method: 'PUT', headers: expect.objectContaining({ 'Content-Type': 'application/json', 'X-Loopper-Local-UI': '1' }), body: JSON.stringify(body) }))
    const layout = { requestKey: 'stable-layout-key', expectedRevision: 4, expectedLayoutVersion: 2, layout: emptyLayout() }; await workflowApi.layout('id', layout)
    expect(fetch).toHaveBeenLastCalledWith('/api/workflows/templates/id/layout', expect.objectContaining({ body: JSON.stringify(layout) }))
  })
})
