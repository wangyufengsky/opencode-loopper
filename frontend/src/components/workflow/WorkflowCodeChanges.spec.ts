import { flushPromises, mount } from '@/pages/w6-tests/workflow/react-test-root'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { WorkflowCodeChanges } from '@/pages/w6-tests/workflow/read-panels'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowCodeChange, WorkflowPage } from '@/types/domain'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { changes: vi.fn(), fileUrl: vi.fn(() => '/fixed-file') } }))
afterEach(() => vi.resetAllMocks())
const props = { requirement: 'req', node: 'node', attempt: 'attempt', direction: 'outputs' as const, name: 'chosen-code' }
const change = (path: string, kind: WorkflowCodeChange['kind']): WorkflowCodeChange => ({ path, kind, beforeBlob: kind === 'ADD' ? null : 'private-before', afterBlob: kind === 'DELETE' ? null : 'private-after' })
describe('代码交付累计改动', () => {
  it('按需分页，删除文件保留记录，失败后从原游标继续且不展示内部对象编号', async () => {
    vi.mocked(workflowRuns.changes).mockResolvedValueOnce({ items: [change('新增文件.txt', 'ADD'), change('deleted.txt', 'DELETE')], nextCursor: 'next' })
      .mockRejectedValueOnce(new Error('unavailable')).mockResolvedValueOnce({ items: [change('mode.sh', 'MODIFY')], nextCursor: null })
    const view = mount(WorkflowCodeChanges, { props })
    expect(workflowRuns.changes).not.toHaveBeenCalled(); await view.get('button').trigger('click'); await flushPromises()
    expect(view.text()).toContain('相对原始基线的累计改动'); expect(view.text()).toContain('继承的上游代码')
    expect(view.findAll('li').map(row => row.text().replace(/\s/g, ''))).toEqual(['新增新增文件.txt', '删除deleted.txt'])
    expect(view.findAll('a')).toHaveLength(1); expect(view.text()).not.toContain('private-')
    await view.get('button').trigger('click'); await flushPromises(); expect(view.get('[role="alert"]').text()).toContain('重试')
    expect(view.findAll('li')).toHaveLength(2); await view.get('button').trigger('click'); await flushPromises()
    expect(workflowRuns.changes).toHaveBeenLastCalledWith('req', 'node', 'attempt', 'outputs', 'chosen-code', 'next')
    expect(view.findAll('li')).toHaveLength(3); expect(view.text()).toContain('修改 mode.sh'); expect(view.findAll('a')).toHaveLength(2)
    expect(view.findAll('button')).toHaveLength(0); view.unmount()
  })
  it('清单为空明确显示没有文件改动', async () => {
    vi.mocked(workflowRuns.changes).mockResolvedValue({ items: [], nextCursor: null })
    const view = mount(WorkflowCodeChanges, { props: { ...props, direction: 'inputs' } })
    await view.get('button').trigger('click'); await flushPromises()
    expect(view.text()).toContain('与原始基线没有文件改动')
    expect(workflowRuns.changes).toHaveBeenCalledWith('req', 'node', 'attempt', 'inputs', 'chosen-code', ''); view.unmount()
  })
  it('切换尝试卸载后，旧请求不能污染新尝试', async () => {
    let resolve!: (page: WorkflowPage<WorkflowCodeChange>) => void
    vi.mocked(workflowRuns.changes).mockReturnValueOnce(new Promise(done => { resolve = done }))
      .mockResolvedValueOnce({ items: [change('current.txt', 'ADD')], nextCursor: null })
    const old = mount(WorkflowCodeChanges, { props }); await old.get('button').trigger('click'); old.unmount()
    const current = mount(WorkflowCodeChanges, { props: { ...props, attempt: 'next-attempt' } })
    await current.get('button').trigger('click'); await flushPromises()
    resolve({ items: [change('obsolete.txt', 'DELETE')], nextCursor: 'old-cursor' }); await flushPromises()
    expect(current.text()).toContain('current.txt'); expect(current.text()).not.toContain('obsolete.txt'); current.unmount()
  })
})
