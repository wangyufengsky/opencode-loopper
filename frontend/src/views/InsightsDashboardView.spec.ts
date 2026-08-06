import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import InsightsDashboardView from '@/views/InsightsDashboardView.vue'

afterEach(() => { vi.unstubAllGlobals() })
describe('InsightsDashboardView', () => {
  it('renders unknown provider usage as unknown instead of zero and keeps currencies separate', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ usage: { totalTokens: null, unknownUsageCount: 2, costByCurrency: { USD: '1.20', CNY: '8.00' } }, tasks: [] }) }))
    const wrapper = mount(InsightsDashboardView, { global: { plugins: [ElementPlus], stubs: { Icon: true, PageHeader: { template: '<header><slot name="actions" /></header>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('未知')
    expect(wrapper.text()).toContain('USD 1.20')
    expect(wrapper.text()).toContain('CNY 8.00')
    expect(wrapper.text()).not.toContain('可靠总 Tokens 0')
  })
})
