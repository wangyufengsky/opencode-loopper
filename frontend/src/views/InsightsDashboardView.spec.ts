import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import InsightsDashboardView from '@/views/InsightsDashboardView.vue'
import { api } from '@/api/client'

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
describe('InsightsDashboardView', () => {
  it('keeps filters on pagination and clears the cursor when a new search is applied', async () => {
    vi.spyOn(api, 'getProjects').mockResolvedValue([])
    const page = vi.spyOn(api, 'getInsightsPage').mockResolvedValue({ tasks: [], nextCursor: 'page-2', generatedAt: 'now',
      usage: { inputTokens: 0, outputTokens: 0, totalTokens: 30, costByCurrency: {}, unknownUsageCount: 0 } })
    const wrapper = mount(InsightsDashboardView, { global: { plugins: [ElementPlus], stubs: { Icon: true, PageHeader: true } } })
    await flushPromises()
    await wrapper.get('input[aria-label="搜索任务标题"]').setValue('design')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(page).toHaveBeenLastCalledWith(expect.objectContaining({ query: 'design', archive: 'ACTIVE', cursor: undefined }))
    const more = wrapper.findAll('button').find(button => button.text().includes('加载更多'))!
    await more.trigger('click'); await flushPromises()
    expect(page).toHaveBeenLastCalledWith(expect.objectContaining({ query: 'design', cursor: 'page-2' }))
    await wrapper.get('input[aria-label="搜索任务标题"]').setValue('changed')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(page).toHaveBeenLastCalledWith(expect.objectContaining({ query: 'changed', cursor: undefined }))
    expect(wrapper.text()).toContain('筛选范围总用量')
  })
  it('renders unknown provider usage as unknown instead of zero and keeps currencies separate', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ usage: { totalTokens: null, unknownUsageCount: 2, costByCurrency: { USD: '1.20', CNY: '8.00' } }, tasks: [] }) }))
    const wrapper = mount(InsightsDashboardView, { global: { plugins: [ElementPlus], stubs: { Icon: true, RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' }, PageHeader: { template: '<header><slot name="actions" /></header>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('未知')
    expect(wrapper.text()).toContain('USD 1.20')
    expect(wrapper.text()).toContain('CNY 8.00')
    expect(wrapper.text()).not.toContain('可靠总 Tokens 0')
  })

  it('renders localized quality markers with an icon for every quality state', async () => {
    const task = (state: 'PASS' | 'PENDING' | 'REVIEW_REQUIRED') => ({
      taskId: state, title: state, state: 'SUCCEEDED', durationMs: 1000, retryCount: 0,
      usage: { totalTokens: 1, unknownUsageCount: 0, costByCurrency: {} },
      quality: { state, deterministicPassed: state === 'PASS', verificationCount: 1, verificationPassedCount: state === 'PASS' ? 1 : 0, requirementJudgePassed: state === 'PASS', riskJudgePassed: state === 'PASS' },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ usage: { totalTokens: 3, unknownUsageCount: 0, costByCurrency: {} }, tasks: [task('PASS'), task('PENDING'), task('REVIEW_REQUIRED')] }) }))
    const wrapper = mount(InsightsDashboardView, { global: { plugins: [ElementPlus], stubs: { Icon: true, RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' }, PageHeader: { template: '<header><slot name="actions" /></header>' } } } })
    await flushPromises()
    const markers = wrapper.findAll('.quality')
    expect(markers.map(marker => marker.text())).toEqual(['质量通过', '待验收', '待评审'])
    expect(markers.every(marker => marker.find('.quality-icon').exists())).toBe(true)
    expect(markers.map(marker => marker.attributes('title'))).toEqual(['质量通过', '待验收', '待评审'])
    expect(wrapper.find('a[href="/tasks/REVIEW_REQUIRED#judge-review"]').exists()).toBe(true)
  })
})
