import { flushPromises, resolveDialog } from '@/pages/w6-tests/workflow/react-test-root'
import { publicationFixture } from '@/pages/w6-tests/workflow/publication-fixture'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { workflowPush } from '@/api/workflowPush'
import type { WorkflowPushPreview, WorkflowPushView } from '@/types/domain'

vi.mock('@/api/workflowPush', () => ({ workflowPush: { status: vi.fn(), remotes: vi.fn(), preview: vi.fn(), confirm: vi.fn(), retry: vi.fn() } }))
const preview: WorkflowPushPreview = { requirementId: 'req', publicationVersion: 3, remote: 'origin', url: 'https://example.org/repo.git', branch: 'loopper/results/one', commit: 'a'.repeat(40), remoteCommit: null, sha256: 'b'.repeat(64) }
const record = (state: WorkflowPushView['state']): WorkflowPushView => ({ requirementId: 'req', state, version: 7, remote: preview.remote, url: preview.url, branch: preview.branch, commit: preview.commit, ordinal: 1, reasonCode: state === 'BLOCKED' ? 'WORKFLOW_PUSH_STOP_UNCONFIRMED' : null })
async function render() { const f = await publicationFixture('push'); f.owner.patch({ commit: { requirementId: 'req', state: 'COMMITTED', version: 3, nodeTitle: '开发', outputTitle: '代码', attemptState: 'SUCCEEDED', branch: preview.branch, message: '固定成果', commit: preview.commit, createdAt: '' } }); await flushPromises(); return f }
const button = (f: Awaited<ReturnType<typeof render>>, key: string) => f.view.get(`button[data-semantic="${key}"]`)
beforeEach(() => { vi.mocked(workflowPush.status).mockResolvedValue(null); vi.mocked(workflowPush.remotes).mockResolvedValue(['origin', 'backup']); vi.mocked(workflowPush.preview).mockResolvedValue(preview) })
afterEach(() => { vi.resetAllMocks(); vi.restoreAllMocks(); vi.useRealTimers() })
async function choose(f: Awaited<ReturnType<typeof render>>) { await button(f,'workflow.push').trigger('click'); await flushPromises(); await f.view.get('select').setValue('origin'); await button(f,'workflow.inspectPush').trigger('click'); await flushPromises() }
it('页面打开不联网检查或推送，只有选择并检查目标后才能明确确认', async () => {
  const f = await render(); expect(workflowPush.remotes).not.toHaveBeenCalled(); expect(workflowPush.preview).not.toHaveBeenCalled()
  await button(f,'workflow.push').trigger('click'); await flushPromises(); expect(f.view.get<HTMLSelectElement>('select').element.value).toBe(''); expect(workflowPush.preview).not.toHaveBeenCalled()
  await f.view.get('select').setValue('origin'); await button(f,'workflow.inspectPush').trigger('click'); await flushPromises(); expect(workflowPush.confirm).not.toHaveBeenCalled(); expect(f.view.text()).toContain(preview.url)
  vi.mocked(workflowPush.confirm).mockResolvedValue(record('PREPARING')); vi.mocked(workflowPush.status).mockResolvedValue(record('PUSHED'))
  await f.view.get('form').trigger('submit'); await flushPromises()
  expect(workflowPush.confirm).toHaveBeenCalledWith('req', { requestKey: expect.any(String), expectedVersion: 3, remote: 'origin', previewSha256: preview.sha256 }); expect(f.view.text()).toContain('已核对远端成果分支'); expect(f.view.text()).not.toContain('尚未推送'); f.dispose()
})
it('回执未知保留原请求，已接受后的读取错误只刷新', async () => {
  const f = await render(); await choose(f); vi.mocked(workflowPush.confirm).mockRejectedValueOnce(new Error('lost')).mockResolvedValue(record('PREPARING'))
  await f.view.get('form').trigger('submit'); await flushPromises(); const original = vi.mocked(workflowPush.confirm).mock.calls[0]![1]; vi.mocked(workflowPush.status).mockRejectedValueOnce(new Error('read lost'))
  await button(f,'receipt.retryOriginal').trigger('click'); await flushPromises(); expect(workflowPush.confirm).toHaveBeenLastCalledWith('req', original)
  vi.mocked(workflowPush.status).mockResolvedValue(record('PUSHED')); await button(f,'receipt.readOriginal').trigger('click'); await flushPromises(); expect(workflowPush.confirm).toHaveBeenCalledTimes(2); expect(f.view.text()).toContain('已核对远端成果分支'); f.dispose()
})
it('重新打开只读阻断记录，显式恢复使用原版本且不新建确认', async () => {
  vi.mocked(workflowPush.status).mockResolvedValue(record('BLOCKED')); const f = await render(); expect(workflowPush.preview).not.toHaveBeenCalled(); expect(workflowPush.retry).not.toHaveBeenCalled()
  vi.mocked(workflowPush.retry).mockResolvedValue(record('RUNNING')); vi.mocked(workflowPush.status).mockResolvedValue(record('PUSHED'))
  await button(f,'receipt.readOriginal').trigger('click'); await flushPromises(); expect(workflowPush.retry).toHaveBeenCalledWith('req',7); expect(workflowPush.confirm).not.toHaveBeenCalled(); f.dispose()
})
it('改变远端废弃旧预览，并丢弃迟到的检查响应', async () => {
  const f = await render(); await choose(f); let resolve!: (value: WorkflowPushPreview) => void; vi.mocked(workflowPush.preview).mockReturnValueOnce(new Promise(done=>{resolve=done}))
  await button(f,'workflow.inspectPush').trigger('click'); const signal=vi.mocked(workflowPush.preview).mock.calls.at(-1)![2]!; f.dispose(); expect(signal.aborted).toBe(true)
  const current=await publicationFixture('push',null,null,'other'); resolve(preview); await flushPromises(); expect(current.view.text()).not.toContain(preview.url); current.dispose()
})
it('选择目标后阻止误离开，并通过 busy 保护父画布的其他写操作', async () => {
  const f=await render(); await choose(f); expect(f.owner.canLeave().kind).toBe('CONFIRM_DISCARD'); expect(f.owner.getSnapshot().dirty).toBe(true)
  await f.view.get('select').setValue('backup'); await flushPromises(); expect(f.view.find('button[data-semantic="workflow.pushConfirm"]').exists()).toBe(false)
  await button(f,'ui.close').trigger('click'); await resolveDialog(f.view,false); expect(f.owner.canLeave().kind).toBe('CONFIRM_DISCARD')
  await button(f,'ui.close').trigger('click'); await resolveDialog(f.view,true); expect(f.owner.canLeave().kind).toBe('ALLOW'); expect(f.owner.getSnapshot().dirty).toBe(false); f.dispose()
})
it('读取失败可以恢复；当前需求不接受其他需求的推送记录', async () => {
  vi.mocked(workflowPush.status).mockResolvedValueOnce({...record('PUSHED'),requirementId:'other'}); const f=await render(); expect(f.view.text()).not.toContain('已核对远端成果分支'); expect(f.view.find('[role="alert"]').exists()).toBe(true)
  await f.owner.readStatus('push'); await flushPromises(); expect(f.owner.getSnapshot().pushLoaded).toBe(true); expect(f.owner.getSnapshot().push).toBeNull(); f.dispose()
})
