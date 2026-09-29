import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { workflowPush } from '@/api/workflowPush'
import type { WorkflowPushPreview, WorkflowPushView } from '@/types/domain'
import Component from './WorkflowPush.vue'
vi.mock('@/api/workflowPush', () => ({ workflowPush: { status: vi.fn(), remotes: vi.fn(), preview: vi.fn(), confirm: vi.fn(), retry: vi.fn() } }))
const preview: WorkflowPushPreview = { requirementId: 'req', publicationVersion: 3, remote: 'origin', url: 'https://example.org/repo.git', branch: 'loopper/results/one', commit: 'a'.repeat(40), remoteCommit: null, sha256: 'b'.repeat(64) }
const record = (state: WorkflowPushView['state']): WorkflowPushView => ({ requirementId: 'req', state, version: 7, remote: preview.remote, url: preview.url, branch: preview.branch, commit: preview.commit, ordinal: 1, reasonCode: state === 'BLOCKED' ? 'WORKFLOW_PUSH_STOP_UNCONFIRMED' : null })
const render = () => mount(Component, { props: { requirement: 'req' } })
const button = (view: ReturnType<typeof render>, name: string) => view.findAll('button').find(b => b.text() === name)!
beforeEach(() => { vi.mocked(workflowPush.status).mockResolvedValue(null); vi.mocked(workflowPush.remotes).mockResolvedValue(['origin', 'backup']); vi.mocked(workflowPush.preview).mockResolvedValue(preview) })
afterEach(() => { vi.resetAllMocks(); vi.restoreAllMocks(); vi.useRealTimers() })
async function choose(view: ReturnType<typeof render>) { await flushPromises(); await button(view, '推送到远端').trigger('click'); await flushPromises(); await view.get('select').setValue('origin'); await button(view, '检查推送目标').trigger('click'); await flushPromises() }
it('页面打开不联网检查或推送，只有选择并检查目标后才能明确确认', async () => {
  const view = render(); await flushPromises(); expect(workflowPush.remotes).not.toHaveBeenCalled(); expect(workflowPush.preview).not.toHaveBeenCalled()
  await button(view, '推送到远端').trigger('click'); await flushPromises(); expect(view.get('select').element.value).toBe(''); expect(workflowPush.preview).not.toHaveBeenCalled()
  await view.get('select').setValue('origin'); await button(view, '检查推送目标').trigger('click'); await flushPromises(); expect(workflowPush.confirm).not.toHaveBeenCalled(); expect(view.text()).toContain(preview.url)
  vi.mocked(workflowPush.confirm).mockResolvedValue(record('PREPARING')); vi.mocked(workflowPush.status).mockResolvedValue(record('PUSHED'))
  await view.get('form').trigger('submit'); await flushPromises()
  expect(workflowPush.confirm).toHaveBeenCalledWith('req', { requestKey: expect.any(String), expectedVersion: 3, remote: 'origin', previewSha256: preview.sha256 }); expect(view.text()).toContain('已核对远端成果分支'); expect(view.text()).not.toContain('尚未推送'); view.unmount()
})
it('回执未知保留原请求，已接受后的读取错误只刷新', async () => {
  const view = render(); await choose(view)
  vi.mocked(workflowPush.confirm).mockRejectedValueOnce(new Error('lost')).mockResolvedValue(record('PREPARING'))
  await view.get('form').trigger('submit'); await flushPromises(); const original = vi.mocked(workflowPush.confirm).mock.calls[0]![1]
  vi.mocked(workflowPush.status).mockRejectedValueOnce(new Error('read lost'))
  await button(view, '重试原推送确认').trigger('click'); await flushPromises(); expect(workflowPush.confirm).toHaveBeenLastCalledWith('req', original)
  vi.mocked(workflowPush.status).mockResolvedValue(record('PUSHED')); await button(view, '刷新推送结果').trigger('click'); await flushPromises(); expect(workflowPush.confirm).toHaveBeenCalledTimes(2); expect(view.text()).toContain('已核对远端成果分支'); view.unmount()
})
it('重新打开只读阻断记录，显式恢复使用原版本且不新建确认', async () => {
  vi.mocked(workflowPush.status).mockResolvedValue(record('BLOCKED')); const view = render(); await flushPromises()
  expect(workflowPush.preview).not.toHaveBeenCalled(); expect(workflowPush.retry).not.toHaveBeenCalled()
  vi.mocked(workflowPush.retry).mockResolvedValue(record('RUNNING')); vi.mocked(workflowPush.status).mockResolvedValue(record('PUSHED'))
  await button(view, '核对并恢复原推送').trigger('click'); await flushPromises(); expect(workflowPush.retry).toHaveBeenCalledWith('req', 7); expect(workflowPush.confirm).not.toHaveBeenCalled(); view.unmount()
})
it('改变远端废弃旧预览，并丢弃迟到的检查响应', async () => {
  const view = render(); await choose(view); let resolve!: (value: WorkflowPushPreview) => void
  vi.mocked(workflowPush.preview).mockReturnValueOnce(new Promise(done => { resolve = done }))
  await button(view, '检查推送目标').trigger('click'); const signal = vi.mocked(workflowPush.preview).mock.calls.at(-1)![2]!
  await view.setProps({ requirement: 'other' }); await flushPromises(); expect(signal.aborted).toBe(true); resolve(preview); await flushPromises(); expect(view.text()).not.toContain(preview.url); view.unmount()
})
it('选择目标后阻止误离开，并通过 busy 保护父画布的其他写操作', async () => {
  const view = render(); await choose(view); const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  expect(view.vm.canLeave()).toBe(false); expect(confirm).toHaveBeenCalled(); expect(view.emitted('busy')?.at(-1)).toEqual([true])
  await view.get('select').setValue('backup'); expect(view.text()).not.toContain('确认推送成果分支'); await button(view, '取消推送选择').trigger('click'); expect(view.vm.canLeave()).toBe(true); expect(view.emitted('busy')?.at(-1)).toEqual([false]); view.unmount()
})
it('读取失败可以恢复；当前需求不接受其他需求的推送记录', async () => {
  vi.mocked(workflowPush.status).mockResolvedValueOnce({ ...record('PUSHED'), requirementId: 'other' }); const view = render(); await flushPromises(); expect(view.text()).not.toContain('已核对远端成果分支'); expect(view.find('[role="alert"]').exists()).toBe(true)
  await button(view, '重新读取推送记录').trigger('click'); await flushPromises(); expect(view.text()).toContain('尚未推送'); view.unmount()
})
