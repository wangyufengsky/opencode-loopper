import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, describe, it, expect, vi } from 'vitest'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowSnapshotPartialReport as Report } from '@/types/domain'
import WorkflowSnapshotPartialReport from './WorkflowSnapshotPartialReport.vue'
const value: Report = { content: '# 部分报告', sha256: 'hash', capturedAt: '2026-09-29T01:00:00Z', planRevision: 4, analyzedUnits: 1, pendingUnits: 3, excludedUnits: 2 }
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
const render = () => mount(WorkflowSnapshotPartialReport, { props: { requirement: 'req', node: 'source', attempt: 'run' }, global: { stubs: { MarkdownDocument: true } } })
describe('workflow snapshot partial report', () => {
  it('reads only on demand and retains the explicit observation during a failed refresh', async () => {
    const read = vi.spyOn(workflowRuns, 'snapshotPartialReport').mockResolvedValue(value)
    const wrapper = render(); expect(read).not.toHaveBeenCalled()
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(read).toHaveBeenCalledWith('req', 'source', 'run', expect.any(AbortSignal))
    expect(wrapper.text()).toContain('已分析 1 · 未完成 3 · 排除 2'); expect(wrapper.text()).toContain('计划版本 4')
    expect(wrapper.getComponent({ name: 'MarkdownDocument' }).props('allowImages')).toBe(false)
    read.mockRejectedValue(new Error('offline')); await wrapper.findAll('button')[0]!.trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('阶段报告读取失败'); expect(wrapper.text()).toContain('已分析 1'); wrapper.unmount()
  })
  it.each(['requirement', 'node', 'attempt'] as const)('aborts and discards the old %s response', async field => {
    let resolve!: (value: Report) => void
    const read = vi.spyOn(workflowRuns, 'snapshotPartialReport').mockImplementationOnce(() => new Promise(done => { resolve = done })).mockResolvedValue(value)
    const wrapper = render(); await wrapper.get('button').trigger('click'); const signal = read.mock.calls[0]![3]!
    await wrapper.setProps({ [field]: 'changed' }); expect(signal.aborted).toBe(true)
    resolve({ ...value, content: '旧任务正文', analyzedUnits: 9 }); await flushPromises()
    expect(wrapper.text()).not.toContain('已分析 9'); expect(wrapper.find('markdown-document-stub').exists()).toBe(false)
    await wrapper.get('button').trigger('click'); await flushPromises(); expect(wrapper.text()).toContain('已分析 1'); wrapper.unmount()
  })
  it('downloads exactly the displayed observation and aborts a request when unmounted', async () => {
    let resolve!: (value: Report) => void
    const read = vi.spyOn(workflowRuns, 'snapshotPartialReport').mockResolvedValueOnce(value).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const created = vi.fn(() => 'blob:partial'), revoked = vi.fn(); vi.stubGlobal('URL', class extends URL { static createObjectURL = created; static revokeObjectURL = revoked })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const wrapper = render(); await wrapper.get('button').trigger('click'); await flushPromises()
    await wrapper.findAll('button')[1]!.trigger('click'); expect(created).toHaveBeenCalledWith(expect.any(Blob)); expect(click).toHaveBeenCalledOnce(); expect(revoked).toHaveBeenCalledWith('blob:partial')
    await wrapper.findAll('button')[0]!.trigger('click'); const signal = read.mock.calls[1]![3]!; wrapper.unmount(); expect(signal.aborted).toBe(true); resolve(value); await flushPromises()
  })
})
