import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { workflowWriteback } from '@/api/workflowWriteback'
import type { WorkflowPublicationPreview, WorkflowWritebackPreview, WorkflowWritebackView } from '@/types/domain'
import Component from './WorkflowWriteback.vue'
vi.mock('@/api/workflowWriteback', () => ({ workflowWriteback: { status: vi.fn(), confirm: vi.fn(), retry: vi.fn() } }))
const source: WorkflowPublicationPreview = { requirementId: 'req', requirementVersion: 7, planRevision: 2, requirementState: 'COMPLETED', source: { nodeKey: 'work', nodeTitle: '开发', attemptId: 'private-attempt', ordinal: 1, attemptState: 'SUCCEEDED', outputName: 'code', outputTitle: '代码', createdAt: '', changedFiles: 3, totalFiles: 8 }, workspaceKind: 'DIRECT', sourceBranch: null, reference: { version: 1, snapshotId: 'private-snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'before', resultTree: 'after', added: 1, modified: 1, deleted: 1, totalBytes: 1024, sha256: 'c'.repeat(64) }
const preview: WorkflowWritebackPreview = { requirementId: 'req', requirementVersion: 7, revision: 2, sourceSha256: source.sha256, directory: '/project/source', currentSha256: 'current', targetSha256: 'target', sha256: 'preview', added: 1, modified: 1, deleted: 1, preservedChanges: 2, conflictCount: 0, conflicts: [] }

const record = (state: WorkflowWritebackView['state']): WorkflowWritebackView => ({ requirementId: 'req', state, version: 2, queueState: state === 'APPLIED' ? 'FINISHED' : 'ADMITTED', queuePosition: 0, preview, nodeTitle: '开发', outputTitle: '代码', attemptState: 'FAILED', createdAt: 'now', appliedAt: state === 'APPLIED' ? 'now' : null, blocker: state === 'BLOCKED' ? 'WORKFLOW_WRITEBACK_PREPARATION_CHANGED' : null })
const render = () => mount(Component, { props: { requirement: 'req', source, checked: preview } })
const button = (view: ReturnType<typeof render>, text: string) => view.findAll('button').find(b => b.text() === text)!
beforeEach(() => { vi.mocked(workflowWriteback.status).mockResolvedValue(null) })
afterEach(() => { vi.resetAllMocks(); vi.useRealTimers() })
it('确认前展示精确目录、删除和保留数量，再用本次检查摘要回填', async () => {
  const view = render(); await flushPromises(); await button(view, '回填所选成果').trigger('click'); expect(workflowWriteback.confirm).not.toHaveBeenCalled(); expect(view.text()).toContain('删除 1'); expect(view.text()).toContain('/project/source')
  expect(view.emitted('busy')?.at(-1)).toEqual([true]); vi.mocked(workflowWriteback.confirm).mockResolvedValue(record('CONFIRMED')); vi.mocked(workflowWriteback.status).mockResolvedValue(record('APPLIED'))
  await view.get('form').trigger('submit'); await flushPromises(); expect(workflowWriteback.confirm).toHaveBeenCalledWith('req', { requestKey: expect.any(String), expectedVersion: 7, selection: { revision: 2, node: 'work', attempt: 'private-attempt', output: 'code', sourceSha256: source.sha256 }, previewSha256: preview.sha256 })
  expect(view.text()).toContain('已回填原目录'); expect(view.text()).toContain('原执行结果为失败'); expect(view.text()).not.toContain('private-'); expect(view.emitted('busy')?.at(-1)).toEqual([false]); view.unmount()
})
it('冲突、过期或缺少检查、非成功需求都不能出现确认入口', async () => {
  const view = render(); await flushPromises(); await view.setProps({ checked: null }); expect(view.find('button').exists()).toBe(false)
  await view.setProps({ checked: { ...preview, sourceSha256: 'stale' } }); expect(view.find('button').exists()).toBe(false)
  await view.setProps({ checked: { ...preview, conflictCount: 1 } }); expect(view.find('button').exists()).toBe(false)
  await view.setProps({ checked: preview, source: { ...source, requirementState: 'FAILED' } }); expect(view.find('button').exists()).toBe(false); view.unmount()
})
it('未知回执重发相同请求，已接受后的读取失败只刷新结果', async () => {
  const view = render(); await flushPromises(); await button(view, '回填所选成果').trigger('click'); vi.mocked(workflowWriteback.confirm).mockRejectedValueOnce(new Error('lost')).mockResolvedValue(record('CONFIRMED'))
  await view.get('form').trigger('submit'); await flushPromises(); const original = vi.mocked(workflowWriteback.confirm).mock.calls[0]![1]; vi.mocked(workflowWriteback.status).mockRejectedValueOnce(new Error('read failed'))
  await button(view, '重试原回填操作').trigger('click'); await flushPromises(); expect(workflowWriteback.confirm).toHaveBeenLastCalledWith('req', original)
  vi.mocked(workflowWriteback.status).mockResolvedValue(record('APPLIED')); await button(view, '刷新操作结果').trigger('click'); await flushPromises(); expect(workflowWriteback.confirm).toHaveBeenCalledTimes(2); expect(view.text()).toContain('已回填原目录'); view.unmount()
})
it('重开时无需重新选择成果，阻断后恢复原版本并轮询真实完成', async () => {
  vi.useFakeTimers(); vi.mocked(workflowWriteback.status).mockResolvedValueOnce(record('BLOCKED')).mockResolvedValueOnce(record('APPLYING')).mockResolvedValue(record('APPLIED'))
  const view = render(); await view.setProps({ source: null, checked: null }); await flushPromises(); expect(view.text()).toContain('目录已变化'); vi.mocked(workflowWriteback.retry).mockResolvedValue(record('APPLYING'))
  await button(view, '恢复原回填').trigger('click'); await flushPromises(); expect(workflowWriteback.retry).toHaveBeenCalledWith('req', 2); expect(view.text()).toContain('正在回填原目录'); expect(workflowWriteback.confirm).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(2500); await flushPromises(); expect(view.text()).toContain('已回填原目录'); view.unmount(); expect(vi.getTimerCount()).toBe(0)
})
it('排队、禁用和取消确认不产生执行，改变来源清理旧确认', async () => {
  const view = render(); await flushPromises(); await button(view, '回填所选成果').trigger('click'); await button(view, '取消').trigger('click'); expect(workflowWriteback.confirm).not.toHaveBeenCalled()
  await view.setProps({ disabled: true }); expect(button(view, '回填所选成果').attributes('disabled')).toBeDefined(); await view.setProps({ disabled: false }); await button(view, '回填所选成果').trigger('click')
  await view.setProps({ source: { ...source, sha256: 'another' } }); expect(view.find('form').exists()).toBe(false); view.unmount()
  vi.mocked(workflowWriteback.status).mockResolvedValue({ ...record('CONFIRMED'), queueState: 'QUEUED', queuePosition: 3 }); const queued = render(); await flushPromises(); expect(queued.text()).toContain('第 3 位'); expect(workflowWriteback.confirm).not.toHaveBeenCalled(); queued.unmount()
})
it('切换需求会取消旧读取，迟到状态不会显示到新需求', async () => {
  let resolve!: (value: WorkflowWritebackView) => void; vi.mocked(workflowWriteback.status).mockReturnValueOnce(new Promise(done => { resolve = done })); const view = render(); const signal = vi.mocked(workflowWriteback.status).mock.calls[0]![1]!
  await view.setProps({ requirement: 'other', source: null, checked: null }); await flushPromises(); expect(signal.aborted).toBe(true); resolve(record('APPLIED')); await flushPromises(); expect(view.text()).not.toContain('已回填原目录'); view.unmount()
})
