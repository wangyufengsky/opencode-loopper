import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PptProperties from './PptProperties.vue'
import { pptCapabilities, pptText } from './pptTestFixtures'
describe('PPT property autosave', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.useFakeTimers()
  })
  afterEach(() => vi.useRealTimers())
  it('debounces object edits and pauses when their baseline is no longer current', async () => {
    const wrapper = mount(PptProperties, {
      props: {
        element: pptText(),
        documentId: 'doc',
        revision: 3,
        capabilities: pptCapabilities(),
      },
    })
    await wrapper.get('textarea').setValue('更简洁的标题')
    await vi.advanceTimersByTimeAsync(900)
    expect(wrapper.emitted('save')?.[0]).toEqual([
      'text-1',
      expect.objectContaining({
        text: '更简洁的标题',
      }),
      3,
    ])
    await wrapper.setProps({
      revision: 4,
      element: {
        ...pptText(),
        text: '其他修改',
      },
    })
    await wrapper.get('textarea').setValue('继续保留本地修改')
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.emitted('save')).toHaveLength(1)
    expect(wrapper.text()).toContain('你的输入仍保留')
    wrapper.unmount()
  })
})
