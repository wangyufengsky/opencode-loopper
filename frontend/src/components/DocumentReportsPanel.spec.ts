import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import DocumentReportsPanel from './DocumentReportsPanel.vue'
vi.mock('@/api/client', () => ({ api: { documentReports: vi.fn(), documentReportByName: vi.fn(), downloadDocumentReport: vi.fn() } }))
const summary = { id: 'artifact', name: 'summary.md', kind: 'REQUIREMENT_REPORT', sha256: 'hash', bytes: 10, createdAt: 'now' }
beforeEach(() => { vi.clearAllMocks(); vi.mocked(api.documentReports).mockResolvedValue({ items: [summary], nextCursor: undefined, facets: {} }); vi.mocked(api.documentReportByName).mockResolvedValue({ ...summary, content: '存在无法判断项；本次未执行测试。' }) })
function render() { return mount(DocumentReportsPanel, { props: { runId: 'first', count: 1, completed: false }, global: { plugins: [ElementPlus], stubs: { MarkdownDocument: { props: ['content'], template: '<div>{{ content }}</div>' } } } }) }
it('loads report bodies on demand and keeps incomplete reports distinct from a downloadable completed bundle', async () => {
  const wrapper = render(); await flushPromises(); expect(api.documentReportByName).not.toHaveBeenCalled()
  expect(wrapper.text()).not.toContain('下载整包'); await wrapper.findAll('button').find(button => button.text() === '总体报告')!.trigger('click'); await flushPromises()
  expect(api.documentReportByName).toHaveBeenCalledWith('first', 'summary.md'); expect(wrapper.text()).toContain('无法判断项')
  await wrapper.setProps({ completed: true }); expect(wrapper.text()).toContain('下载整包'); wrapper.unmount()
})
it('clears previous scope while the next report directory is still loading', async () => {
  const wrapper = render(); await flushPromises(); await wrapper.findAll('button').find(button => button.text() === '总体报告')!.trigger('click'); await flushPromises()
  vi.mocked(api.documentReports).mockReturnValue(new Promise(() => {})); await wrapper.setProps({ runId: 'second', count: 0 })
  expect(wrapper.text()).not.toContain('无法判断项'); expect(wrapper.text()).not.toContain('总体报告'); wrapper.unmount()
})
