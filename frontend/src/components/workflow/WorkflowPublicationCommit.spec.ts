import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { workflowPublication } from '@/api/workflowPublication'
import type { WorkflowPublicationCommit, WorkflowPublicationPreview } from '@/types/domain'
import Component from './WorkflowPublicationCommit.vue'
vi.mock('@/api/workflowPublication', () => ({ workflowPublication: { status: vi.fn(), confirm: vi.fn(), retry: vi.fn() } }))
const preview: WorkflowPublicationPreview = { requirementId: 'req', requirementVersion: 7, planRevision: 2, requirementState: 'COMPLETED', source: { nodeKey: 'work', nodeTitle: '开发阶段', attemptId: 'private-attempt', ordinal: 1, attemptState: 'FAILED', outputName: 'code', outputTitle: '代码成果', createdAt: '', changedFiles: 2, totalFiles: 3 }, workspaceKind: 'GIT', sourceBranch: 'main', reference: { version: 1, snapshotId: 'private-snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'before', resultTree: 'after', added: 1, modified: 1, deleted: 0, totalBytes: 10, sha256: 'c'.repeat(64) }
const record = (state: WorkflowPublicationCommit['state']): WorkflowPublicationCommit => ({ requirementId: 'req', state, version: 1, nodeTitle: '开发阶段', outputTitle: '代码成果', attemptState: 'FAILED', branch: 'loopper/results/one', message: '固定成果', commit: state === 'COMMITTED' ? 'a'.repeat(40) : null, createdAt: '' })
const render = () => mount(Component, { props: { requirement: 'req', preview }, global: { stubs: { WorkflowPush: true } } })
const button = (view: ReturnType<typeof render>, text: string) => view.findAll('button').find(b => b.text() === text)!
beforeEach(() => { vi.mocked(workflowPublication.status).mockResolvedValue(null) })
afterEach(() => { vi.resetAllMocks(); vi.useRealTimers() })
async function fill(view: ReturnType<typeof render>) { await flushPromises(); await button(view, '保存为本地提交').trigger('click'); await view.get('input').setValue('固定成果') }
it('只有明确确认才携带原预览版本提交，成功保留原节点失败结果', async () => {
  const view = render(); await fill(view); expect(workflowPublication.confirm).not.toHaveBeenCalled()
  vi.mocked(workflowPublication.confirm).mockResolvedValue(record('CONFIRMED')); vi.mocked(workflowPublication.status).mockResolvedValue(record('COMMITTED'))
  await view.get('form').trigger('submit'); await flushPromises()
  expect(workflowPublication.confirm).toHaveBeenCalledWith('req', { requestKey: expect.any(String), expectedVersion: 7, revision: 2, node: 'work', attempt: 'private-attempt', output: 'code', previewSha256: 'c'.repeat(64), message: '固定成果' })
  expect(view.text()).toContain('已保存本地提交'); expect(view.text()).toContain('原执行结果为失败'); expect(view.find('workflow-push-stub').exists()).toBe(true); expect(view.text()).not.toContain('private-'); view.unmount()
})
it('回执未知时重试原请求，已接受后的读取失败只刷新', async () => {
  const view = render(); await fill(view); vi.mocked(workflowPublication.confirm).mockRejectedValueOnce(new Error('lost')).mockResolvedValue(record('CONFIRMED'))
  await view.get('form').trigger('submit'); await flushPromises(); const original = vi.mocked(workflowPublication.confirm).mock.calls[0]![1]
  vi.mocked(workflowPublication.status).mockRejectedValueOnce(new Error('read lost'))
  await button(view, '重试原提交操作').trigger('click'); await flushPromises(); expect(workflowPublication.confirm).toHaveBeenLastCalledWith('req', original)
  vi.mocked(workflowPublication.status).mockResolvedValue(record('COMMITTED')); await button(view, '刷新操作结果').trigger('click'); await flushPromises()
  expect(workflowPublication.confirm).toHaveBeenCalledTimes(2); expect(view.text()).toContain('已保存本地提交'); view.unmount()
})
it('重新打开时读取持久化阻断并按原版本恢复，不创建新提交', async () => {
  vi.mocked(workflowPublication.status).mockResolvedValue(record('BLOCKED')); const view = render(); await flushPromises()
  vi.mocked(workflowPublication.retry).mockResolvedValue(record('CONFIRMED')); vi.mocked(workflowPublication.status).mockResolvedValue(record('COMMITTED'))
  await button(view, '恢复原提交').trigger('click'); await flushPromises(); expect(workflowPublication.retry).toHaveBeenCalledWith('req', 1); expect(workflowPublication.confirm).not.toHaveBeenCalled(); view.unmount()
})
it('未完成或普通目录不显示本地 Git 提交，读取错误可重试', async () => {
  const view = render(); await flushPromises(); await view.setProps({ preview: { ...preview, requirementState: 'FAILED' } }); expect(view.text()).not.toContain('保存为本地提交')
  await view.setProps({ preview: { ...preview, workspaceKind: 'DIRECT' } }); expect(view.text()).not.toContain('保存为本地提交'); view.unmount()
  vi.mocked(workflowPublication.status).mockRejectedValueOnce(new Error('read failed')); const another = render(); await flushPromises(); expect(another.find('[role="alert"]').exists()).toBe(true)
  await button(another, '重新读取提交记录').trigger('click'); await flushPromises(); expect(another.text()).toContain('保存为本地提交'); another.unmount()
})
it('切换需求后旧响应无效，离开组件后中止读取及轮询', async () => {
  let resolve!: (value: WorkflowPublicationCommit) => void
  vi.mocked(workflowPublication.status).mockReturnValueOnce(new Promise(done => { resolve = done }))
  const view = render(); const signal = vi.mocked(workflowPublication.status).mock.calls[0]![1]!
  await view.setProps({ requirement: 'other', preview: null }); await flushPromises(); expect(signal.aborted).toBe(true); resolve(record('COMMITTED')); await flushPromises(); expect(view.text()).not.toContain('已保存本地提交'); view.unmount()
})
