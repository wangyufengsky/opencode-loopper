import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WorkflowFiles from './WorkflowFiles.vue'
import { workflowRuns } from '@/api/workflowRuns'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { files: vi.fn(), fileUrl: vi.fn(() => '/fixed-file'), archiveUrl: vi.fn(() => '/fixed-archive') } }))
afterEach(() => vi.clearAllMocks())
describe('固定源码清单', () => {
  it('Git 对象按明确的正文资格提供下载，不把受限对象当作可读文件', async () => {
    vi.mocked(workflowRuns.files).mockResolvedValueOnce({ items: [
      { path: 'code.txt', sizeBytes: 20, sha256: null, mode: '100644', blobSha: 'a'.repeat(40), exclusion: null },
      { path: '.env', sizeBytes: 10, sha256: null, mode: '100644', blobSha: 'b'.repeat(40), exclusion: '受保护文件不提供读取' },
    ], nextCursor: null })
    const view = mount(WorkflowFiles, { props: { requirement: 'req', node: 'node', attempt: 'attempt', direction: 'outputs', name: 'source' } })
    await view.get('button').trigger('click'); await flushPromises()
    expect(view.findAll('a')).toHaveLength(1); expect(view.get('a').text()).toBe('code.txt')
    expect(view.findAll('li')[0]!.text()).not.toContain('未采集正文'); expect(view.findAll('li')[1]!.text()).toContain('未采集正文'); view.unmount()
  })
  it('文档压缩包保留固定输入身份，点击前不下载正文', () => {
    const view = mount(WorkflowFiles, { props: { requirement: 'req', node: 'node', attempt: 'attempt', direction: 'inputs', name: 'document', archive: true } })
    expect(view.get('a').attributes('href')).toBe('/fixed-archive'); expect(view.get('a').text()).toContain('下载全部文档')
    expect(workflowRuns.archiveUrl).toHaveBeenCalledWith('req', 'node', 'attempt', 'inputs', 'document'); expect(workflowRuns.files).not.toHaveBeenCalled()
  })
  it('按需分页并只为实际冻结的正文提供下载', async () => {
    vi.mocked(workflowRuns.files).mockResolvedValueOnce({ items: [
      { path: 'src/Main.java', sizeBytes: 20, sha256: 'hash', mode: null, target: true, exclusion: null },
      { path: '.env', sizeBytes: 10, sha256: null, mode: null, target: false, exclusion: '受保护文件不提供读取' },
    ], nextCursor: 'cursor' }).mockResolvedValueOnce({ items: [{ path: 'test/MainTest.java', sizeBytes: 30, sha256: 'test-hash', mode: null, target: false, exclusion: '已有测试作为上下文读取' }], nextCursor: null })
    const view = mount(WorkflowFiles, { props: { requirement: 'req', node: 'node', attempt: 'attempt', direction: 'inputs', name: 'source' } })
    expect(workflowRuns.files).not.toHaveBeenCalled(); await view.get('button').trigger('click'); await flushPromises()
    expect(view.findAll('a')).toHaveLength(1); expect(view.text()).toContain('目标范围'); expect(view.text()).toContain('受保护文件不提供读取')
    await view.get('button').trigger('click'); await flushPromises(); expect(workflowRuns.files).toHaveBeenLastCalledWith('req', 'node', 'attempt', 'inputs', 'source', 'cursor')
    expect(view.findAll('a')).toHaveLength(2); expect(view.text()).toContain('已有测试作为上下文读取'); view.unmount()
  })
})
