import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TokenUsageWindow from '@/components/TokenUsageWindow.vue'

afterEach(() => { vi.useRealTimers() })

describe('TokenUsageWindow', () => {
  it('uses the first authoritative total as a silent baseline and animates only a positive delta', async () => {
    vi.useFakeTimers()
    const wrapper = mount(TokenUsageWindow, {
      props: { totalTokens: 1200 },
      global: { stubs: { Icon: true } },
    })

    expect(wrapper.text()).toContain('1,200')
    expect(wrapper.text()).not.toContain('+1,200')

    await wrapper.setProps({ totalTokens: 1584 })
    expect(wrapper.text()).toContain('1,584')
    expect(wrapper.text()).toContain('+384')

    await vi.advanceTimersByTimeAsync(850)
    expect(wrapper.text()).not.toContain('+384')
  })

  it('does not render a negative delta when an older snapshot arrives', async () => {
    const wrapper = mount(TokenUsageWindow, {
      props: { totalTokens: 2000 },
      global: { stubs: { Icon: true } },
    })

    await wrapper.setProps({ totalTokens: 1800 })

    expect(wrapper.text()).toContain('2,000')
    expect(wrapper.text()).not.toContain('-200')
  })
})
