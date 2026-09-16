import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, it, expect, vi } from 'vitest'
import { api } from '@/api/client'
import SnapshotReviewPartialReport from './SnapshotReviewPartialReport.vue'
afterEach(() => vi.restoreAllMocks())
describe('snapshot partial report', () => {
  it('loads only on demand and discards a response after switching tasks', async () => {
    let finish!: (value: any) => void
    const read = vi.spyOn(api, 'snapshotReviewPartialReport').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(SnapshotReviewPartialReport, { props: { taskId: 'old' }, global: { plugins: [ElementPlus], stubs: { MarkdownDocument: true } } })
    expect(read).not.toHaveBeenCalled()
    await wrapper.find('button').trigger('click')
    expect(read).toHaveBeenCalledWith('old')
    await wrapper.setProps({ taskId: 'new' })
    finish({ content: 'old report', analyzedUnits: 9, pendingUnits: 0, excludedUnits: 0 })
    await flushPromises()
    expect(wrapper.text()).not.toContain('已分析 9')
    read.mockResolvedValue({ content: '部分报告', sha256: 'hash', capturedAt: 'now', analyzedUnits: 1, pendingUnits: 3, excludedUnits: 2 })
    await wrapper.find('button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('已分析 1 · 未完成 3 · 排除 2')
    expect(wrapper.text()).toContain('下载阶段报告')
    wrapper.unmount()
  })
})
