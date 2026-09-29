import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WorkflowDocumentPreview from './WorkflowDocumentPreview.vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import { workflowRuns } from '@/api/workflowRuns'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { fileText: vi.fn() } }))
const props = { requirement: 'req', node: 'report', attempt: 'attempt', direction: 'outputs' as const, name: 'document', path: '报告/总结.md' }
const sha256 = 'a'.repeat(64)
afterEach(() => vi.resetAllMocks())
describe('固定报告预览', () => {
  it('按选中正文分页，主子链接留在同一文件包并缓存已读页', async () => {
    vi.mocked(workflowRuns.fileText).mockResolvedValueOnce({ path: props.path, text: '# 总结\n', sha256, offset: 0, nextOffset: 5 })
      .mockResolvedValueOnce({ path: props.path, text: '[明细](明细/问题.md)', sha256, offset: 5, nextOffset: null })
      .mockResolvedValueOnce({ path: '报告/明细/问题.md', text: '[返回](../总结.md)', sha256, offset: 0, nextOffset: null })
    const view = mount(WorkflowDocumentPreview, { props }); await flushPromises()
    expect(workflowRuns.fileText).toHaveBeenCalledTimes(1); await view.findAll('button').find(b => b.text() === '继续读取报告')!.trigger('click'); await flushPromises()
    await view.get('a').trigger('click'); expect(view.emitted('navigate')?.[0]).toEqual(['报告/明细/问题.md'])
    await view.setProps({ path: '报告/明细/问题.md' }); await flushPromises(); await view.get('a').trigger('click'); expect(view.emitted('navigate')?.[1]).toEqual([props.path])
    await view.setProps({ path: props.path }); await flushPromises(); expect(workflowRuns.fileText).toHaveBeenCalledTimes(3); expect(view.text()).toContain('总结'); view.unmount()
  })
  it('拒绝外部、绝对路径和越界跳转，Markdown 不执行 HTML', async () => {
    vi.mocked(workflowRuns.fileText).mockResolvedValue({ path: props.path, text: '<script>alert(1)</script>\n[恶意](https://example.invalid)', sha256, offset: 0, nextOffset: null })
    const view = mount(WorkflowDocumentPreview, { props }); await flushPromises(); const resolve = view.getComponent(MarkdownDocument).props('resolveLink')!
    for (const link of ['../另一个报告.md', '../../private.md', '/etc/passwd.md', 'https://evil.invalid/report.md', 'file:///private.md', 'a%5Cb.md', '%E0%A4%A.md']) expect(resolve(link)).toBeNull()
    expect(resolve('明细/%E9%97%AE%E9%A2%98.md')).toBe(`#workflow-document=${encodeURIComponent('报告/明细/问题.md')}`)
    expect(view.find('script').exists()).toBe(false); expect(view.get('a').attributes('href')).toBeUndefined(); view.unmount()
  })
  it('切换文件后忽略迟到正文并提供失败重试', async () => {
    let resolveFirst!: (value: Awaited<ReturnType<typeof workflowRuns.fileText>>) => void
    vi.mocked(workflowRuns.fileText).mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve })).mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ path: '报告/明细/问题.md', text: '新明细', sha256, offset: 0, nextOffset: null })
    const view = mount(WorkflowDocumentPreview, { props }); await view.setProps({ path: '报告/明细/问题.md' }); await flushPromises()
    resolveFirst({ path: props.path, text: '迟到旧正文', sha256, offset: 0, nextOffset: null }); await flushPromises(); expect(view.text()).not.toContain('迟到旧正文')
    expect(view.get('[role="alert"]').text()).toContain('暂时无法读取'); await view.get('[role="alert"] button').trigger('click'); await flushPromises(); expect(view.text()).toContain('新明细'); view.unmount()
  })
  it('同一次报告翻页不得混合内容哈希', async () => {
    vi.mocked(workflowRuns.fileText).mockResolvedValueOnce({ path: props.path, text: '原文', sha256, offset: 0, nextOffset: 2 })
      .mockResolvedValueOnce({ path: props.path, text: '错误版本', sha256: 'b'.repeat(64), offset: 2, nextOffset: null })
    const view = mount(WorkflowDocumentPreview, { props }); await flushPromises(); await view.findAll('button').find(b => b.text() === '继续读取报告')!.trigger('click'); await flushPromises()
    expect(view.text()).toContain('原文'); expect(view.text()).not.toContain('错误版本'); expect(view.get('[role="alert"]').text()).toContain('重试'); view.unmount()
  })
})
