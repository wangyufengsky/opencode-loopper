import { flushPromises } from '@/pages/w6-tests/workflow/react-test-root'
import { publicationFixture } from '@/pages/w6-tests/workflow/publication-fixture'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { workflowPublication } from '@/api/workflowPublication'
import type { WorkflowPublicationCommit, WorkflowPublicationPreview } from '@/types/domain'

vi.mock('@/api/workflowPublication', () => ({ workflowPublication: { status: vi.fn(), confirm: vi.fn(), retry: vi.fn() } }))
const preview: WorkflowPublicationPreview = { requirementId: 'req', requirementVersion: 7, planRevision: 2, requirementState: 'COMPLETED', source: { nodeKey: 'work', nodeTitle: '开发阶段', attemptId: 'private-attempt', ordinal: 1, attemptState: 'FAILED', outputName: 'code', outputTitle: '代码成果', createdAt: '', changedFiles: 2, totalFiles: 3 }, workspaceKind: 'GIT', sourceBranch: 'main', reference: { version: 1, snapshotId: 'private-snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'before', resultTree: 'after', added: 1, modified: 1, deleted: 0, totalBytes: 10, sha256: 'c'.repeat(64) }
const record = (state: WorkflowPublicationCommit['state']): WorkflowPublicationCommit => ({ requirementId: 'req', state, version: 1, nodeTitle: '开发阶段', outputTitle: '代码成果', attemptState: 'FAILED', branch: 'loopper/results/one', message: '固定成果', commit: state === 'COMMITTED' ? 'a'.repeat(40) : null, createdAt: '' })
const button = (view: Awaited<ReturnType<typeof publicationFixture>>['view'], key: string) => view.get(`button[data-semantic="${key}"]`)
beforeEach(() => { vi.mocked(workflowPublication.status).mockResolvedValue(null) })
afterEach(() => { vi.resetAllMocks(); vi.useRealTimers() })
async function fill(fixture: Awaited<ReturnType<typeof publicationFixture>>) { await button(fixture.view, 'workflow.commit').trigger('click'); await fixture.view.get('input').setValue('固定成果') }
it('只有明确确认才携带原预览版本提交，成功保留原节点失败结果', async () => {
  const f = await publicationFixture('commit', preview); await fill(f); expect(workflowPublication.confirm).not.toHaveBeenCalled()
  vi.mocked(workflowPublication.confirm).mockResolvedValue(record('CONFIRMED')); vi.mocked(workflowPublication.status).mockResolvedValue(record('COMMITTED'))
  await f.view.get('form').trigger('submit'); await flushPromises()
  expect(workflowPublication.confirm).toHaveBeenCalledWith('req', { requestKey: expect.any(String), expectedVersion: 7, revision: 2, node: 'work', attempt: 'private-attempt', output: 'code', previewSha256: 'c'.repeat(64), message: '固定成果' })
  expect(f.view.text()).toContain('已保存本地提交'); expect(f.view.text()).toContain('原执行结果为失败'); expect(f.view.find('[aria-label="推送代码成果"]').exists()).toBe(true); expect(f.view.text()).not.toContain('private-'); f.dispose()
})
it('回执未知时重试原请求，已接受后的读取失败只刷新', async () => {
  const f = await publicationFixture('commit', preview); await fill(f); vi.mocked(workflowPublication.confirm).mockRejectedValueOnce(new Error('lost')).mockResolvedValue(record('CONFIRMED'))
  await f.view.get('form').trigger('submit'); await flushPromises(); const original = vi.mocked(workflowPublication.confirm).mock.calls[0]![1]
  vi.mocked(workflowPublication.status).mockRejectedValueOnce(new Error('read lost'))
  await button(f.view, 'receipt.retryOriginal').trigger('click'); await flushPromises(); expect(workflowPublication.confirm).toHaveBeenLastCalledWith('req', original)
  vi.mocked(workflowPublication.status).mockResolvedValue(record('COMMITTED')); await button(f.view, 'receipt.readOriginal').trigger('click'); await flushPromises()
  expect(workflowPublication.confirm).toHaveBeenCalledTimes(2); expect(f.view.text()).toContain('已保存本地提交'); f.dispose()
})
it('重新打开时读取持久化阻断并按原版本恢复，不创建新提交', async () => {
  vi.mocked(workflowPublication.status).mockResolvedValue(record('BLOCKED')); const f = await publicationFixture('commit', preview)
  vi.mocked(workflowPublication.retry).mockResolvedValue(record('CONFIRMED')); vi.mocked(workflowPublication.status).mockResolvedValue(record('COMMITTED'))
  await button(f.view, 'receipt.readOriginal').trigger('click'); await flushPromises(); expect(workflowPublication.retry).toHaveBeenCalledWith('req', 1); expect(workflowPublication.confirm).not.toHaveBeenCalled(); f.dispose()
})
it('未完成或普通目录不显示本地 Git 提交，读取错误可重试', async () => {
  const f = await publicationFixture('commit', preview); f.owner.patch({ preview: { ...preview, requirementState: 'FAILED' } }); await flushPromises(); expect(f.view.find('button[data-semantic="workflow.commit"]').exists()).toBe(false)
  f.owner.patch({ preview: { ...preview, workspaceKind: 'DIRECT' } }); await flushPromises(); expect(f.view.find('button[data-semantic="workflow.commit"]').exists()).toBe(false); f.dispose()
  vi.mocked(workflowPublication.status).mockRejectedValueOnce(new Error('read failed')); const another = await publicationFixture('commit', preview); expect(another.view.find('[role="alert"]').exists()).toBe(true)
  await another.owner.readStatus('commit'); await flushPromises(); expect(another.view.find('button[data-semantic="workflow.commit"]').exists()).toBe(true); another.dispose()
})
it('切换需求后旧响应无效，离开组件后中止读取及轮询', async () => {
  let resolve!: (value: WorkflowPublicationCommit) => void
  vi.mocked(workflowPublication.status).mockReturnValueOnce(new Promise(done => { resolve = done }))
  const f = await publicationFixture(undefined, preview), read = f.owner.readStatus('commit'), signal = vi.mocked(workflowPublication.status).mock.calls[0]![1]!
  f.dispose(); expect(signal.aborted).toBe(true); const current = await publicationFixture('commit', null, null, 'other')
  resolve(record('COMMITTED')); await read; await flushPromises(); expect(current.view.text()).not.toContain('已保存本地提交'); current.dispose()
})
