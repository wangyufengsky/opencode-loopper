import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import type { TemplateSessionDiagnostic, TemplateSessionDiagnosticPage } from '@/types/domain'
import TemplateSessionDiagnosticsPanel from './TemplateSessionDiagnosticsPanel.vue'

enableAutoUnmount(afterEach)
const row: TemplateSessionDiagnostic = {
  batchId: 'batch-12', batchVersion: 7, sessionKey: 'execution:local-12', localSessionId: 'local-12', externalSessionId: 'ses_remote-12',
  purpose: 'SNAPSHOT_LINKS', ordinal: 12, generation: 1, stageOrdinal: 2, state: 'RUNNING',
  phase: 'ACCEPTED_WAITING_STOP', reason: '结果已接受，正在等待会话结束', acceptedAt: '2026-09-16T01:39:31Z',
  observedAt: '2026-09-16T02:28:54Z', lastActivityAt: '2026-09-16T01:39:31Z', lastProgressAt: '2026-09-16T01:39:31Z',
  remoteState: 'busy', connected: true, stopProof: null, stopConfirmedAt: null, canFinalize: true, canStop: false,
}
const page = (items = [row], nextCursor: string | null = null): TemplateSessionDiagnosticPage => ({ items, nextCursor, hasMore: Boolean(nextCursor) })
const render = () => mount(TemplateSessionDiagnosticsPanel, { props: { taskId: 'task-one', active: true } })
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }
beforeEach(() => {
  vi.useFakeTimers()
  vi.spyOn(api, 'getTemplateSessionDiagnostics').mockResolvedValue(page())
  vi.spyOn(api, 'getTemplateSessionDiagnostic').mockResolvedValue({ ...row, worktreePath: '/data/template-tasks/task-one', requestMessageId: 'msg-12' })
  vi.spyOn(api, 'recoverTemplateSession').mockResolvedValue({ ...row, phase: 'STOP_REQUESTED', canFinalize: false })
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('TemplateSessionDiagnosticsPanel', () => {
  it('requests server filters and cursor pages, preserving exact session selection', async () => {
    vi.mocked(api.getTemplateSessionDiagnostics).mockResolvedValueOnce(page([row], 'next/a'))
    const wrapper = render(); await flushPromises()
    expect(api.getTemplateSessionDiagnostics).toHaveBeenLastCalledWith('task-one', 'ATTENTION', undefined, 50)
    expect(wrapper.text()).toContain('阶段 2 · 衔接规划 · 第 12 批')
    expect(wrapper.text()).toContain('最后有效进展')
    expect(wrapper.text()).toContain('49 分钟无新活动')
    expect(wrapper.text()).not.toContain('ses_remote-12')
    await wrapper.findAll('button').find(b => b.text() === '查看对应会话')!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([['execution:local-12']])
    await wrapper.findAll('button').find(b => b.text() === '下一页')!.trigger('click'); await flushPromises()
    expect(api.getTemplateSessionDiagnostics).toHaveBeenLastCalledWith('task-one', 'ATTENTION', 'next/a', 50)
    await wrapper.findAll('button').find(b => b.text() === '未完成')!.trigger('click'); await flushPromises()
    expect(api.getTemplateSessionDiagnostics).toHaveBeenLastCalledWith('task-one', 'ACTIVE', undefined, 50)
    expect(wrapper.text()).not.toContain('第 2 页')
  })

  it('loads explicit details and copies only allowlisted diagnostic fields', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    vi.mocked(api.getTemplateSessionDiagnostic).mockResolvedValue({ ...row, worktreePath: '/data/task-one', requestMessageId: 'msg-12', ...{ password: 'secret', transcript: 'private output' } })
    const wrapper = render(); await flushPromises()
    expect(api.getTemplateSessionDiagnostic).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(b => b.text() === '查看诊断详情')!.trigger('click'); await flushPromises()
    expect(api.getTemplateSessionDiagnostic).toHaveBeenCalledWith('task-one', 'batch-12')
    expect(wrapper.text()).toContain('ses_remote-12')
    await wrapper.findAll('button').find(b => b.text() === '复制诊断摘要')!.trigger('click'); await flushPromises()
    const copied = JSON.parse(writeText.mock.calls[0]![0])
    expect(copied).toMatchObject({ batchId: 'batch-12', batchVersion: 7, sessionKey: 'execution:local-12', externalSessionId: 'ses_remote-12', requestMessageId: 'msg-12' })
    expect(copied).not.toHaveProperty('password'); expect(copied).not.toHaveProperty('transcript')
  })

  it('posts exact batch version and preserves command identity after uncertain delivery', async () => {
    vi.mocked(api.recoverTemplateSession).mockRejectedValueOnce(new Error('connection lost'))
    const wrapper = render(); await flushPromises()
    const finalize = () => wrapper.findAll('button').find(b => b.text() === '结束会话并收尾')!
    expect(wrapper.text()).not.toContain('停止此批次')
    await finalize().trigger('click'); await flushPromises()
    const first = vi.mocked(api.recoverTemplateSession).mock.calls[0]!
    expect(first.slice(0, 2)).toEqual(['task-one', 'batch-12'])
    expect(first[2]).toMatchObject({ action: 'FINALIZE', expectedVersion: 7 })
    expect(first[2].commandId).toMatch(/^[0-9a-f-]{36}$/)
    expect(finalize().attributes('disabled')).toBeDefined()
    await wrapper.findAll('button').find(b => b.text() === '刷新状态')!.trigger('click'); await flushPromises()
    await finalize().trigger('click'); await flushPromises()
    expect(vi.mocked(api.recoverTemplateSession).mock.calls[1]![2].commandId).toBe(first[2].commandId)
    expect(wrapper.text()).toContain('恢复请求已记录')
  })

  it('requires explicit confirmation for stopping and prevents duplicate requests while pending', async () => {
    vi.mocked(api.getTemplateSessionDiagnostics).mockResolvedValue(page([{ ...row, canFinalize: false, canStop: true, acceptedAt: null }]))
    const confirmation = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel').mockResolvedValueOnce('confirm' as never)
    const pending = deferred<TemplateSessionDiagnostic>()
    vi.mocked(api.recoverTemplateSession).mockReturnValue(pending.promise)
    const wrapper = render(); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '停止此批次')!.trigger('click'); await flushPromises()
    expect(api.recoverTemplateSession).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(b => b.text() === '停止此批次')!.trigger('click'); await flushPromises()
    expect(confirmation).toHaveBeenCalledTimes(2)
    expect(api.recoverTemplateSession).toHaveBeenCalledTimes(1)
    expect(vi.mocked(api.recoverTemplateSession).mock.calls[0]![2].action).toBe('STOP')
    expect(wrapper.findAll('button').find(b => b.text() === '请求处理中…')!.attributes('disabled')).toBeDefined()
    pending.resolve({ ...row, canStop: false }); await flushPromises()
  })

  it('discards stale filter and task responses and bounds polling to active tasks', async () => {
    const old = deferred<TemplateSessionDiagnosticPage>()
    vi.mocked(api.getTemplateSessionDiagnostics).mockReturnValueOnce(old.promise).mockResolvedValue(page([{ ...row, ordinal: 13 }]))
    const wrapper = render()
    await wrapper.findAll('button').find(b => b.text() === '未完成')!.trigger('click'); await flushPromises()
    old.resolve(page([{ ...row, ordinal: 99 }])); await flushPromises()
    expect(wrapper.text()).toContain('第 13 批'); expect(wrapper.text()).not.toContain('第 99 批')
    await vi.advanceTimersByTimeAsync(5000); await flushPromises()
    expect(api.getTemplateSessionDiagnostics).toHaveBeenCalledTimes(3)
    await wrapper.setProps({ active: false }); await vi.advanceTimersByTimeAsync(10000)
    expect(api.getTemplateSessionDiagnostics).toHaveBeenCalledTimes(3)
    const oldDetail = deferred<TemplateSessionDiagnostic>()
    vi.mocked(api.getTemplateSessionDiagnostic).mockReturnValueOnce(oldDetail.promise)
    await wrapper.findAll('button').find(b => b.text() === '查看诊断详情')!.trigger('click')
    await wrapper.setProps({ taskId: 'task-two' }); await flushPromises()
    oldDetail.resolve({ ...row, worktreePath: '/old/private/path' }); await flushPromises()
    expect(wrapper.text()).not.toContain('/old/private/path')
    expect(api.getTemplateSessionDiagnostics).toHaveBeenLastCalledWith('task-two', 'ATTENTION', undefined, 50)
  })
  it('separates transport checks from automatic retries and sends the exact batch version', async () => {
    const checking = vi.spyOn(api, 'checkTemplateSession').mockResolvedValue(row)
    vi.mocked(api.getTemplateSessionDiagnostics).mockResolvedValue(page([{ ...row, canCheck: true, canFinalize: false,
      automaticRetries: 2, retryLimit: 3, transportFailures: 4, nextCheckAt: '2026-09-17T01:00:00Z' }]))
    const wrapper = render(); await flushPromises()
    expect(wrapper.text()).toContain('本轮自动重试已用 2/3 次')
    expect(wrapper.text()).toContain('连续 4 次未能完成检查')
    await wrapper.findAll('button').find(b => b.text() === '重新检查会话')!.trigger('click'); await flushPromises()
    expect(checking).toHaveBeenCalledExactlyOnceWith('task-one', 'batch-12', 7)
    expect(api.recoverTemplateSession).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已请求重新检查原会话')
  })

})
