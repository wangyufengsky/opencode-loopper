import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PptArtifactDownload from './PptArtifactDownload.vue'
describe('PPT artifact download', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })
  it('keeps the existing artifact available when download fails and retries on request', async () => {
    const fetch = vi.fn().mockResolvedValue({
      ok: false,
    })
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(PptArtifactDownload, {
      props: {
        documentId: 'doc',
        artifact: {
          id: 'export',
          name: '季度汇报.pptx',
          mediaType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
          url: 'https://untrusted.invalid/file',
        },
      },
    })
    await wrapper.get('a').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('已有导出仍会保留')
    expect(wrapper.get('a').attributes('href')).toBe('/api/ppt/documents/doc/artifacts/export')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch.mock.calls[0]?.[0]).toBe('/api/ppt/documents/doc/artifacts/export')
    wrapper.unmount()
  })
})
