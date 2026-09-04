import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElDialog } from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import StoryAccountingDialog from './StoryAccountingDialog.vue'
import { api } from '@/api/client'
import type { StoryAccountingCall } from '@/types/domain'

let wrapper: VueWrapper | undefined
const call = (id = 'one'): StoryAccountingCall => ({ id, operation: 'start', state: 'PREPARED', systemCode: 'SYS-001', storyCode: '000123', role: 'ROUTER', startedAt: '2026-09-03T00:00:00Z', parts: [{ id: 'part-1', type: 'OUTPUT', label: '输出', content: '已收到故事编号 000123' }] })
function open() { wrapper = mount(StoryAccountingDialog, { attachTo: document.body, global: { plugins: [ElementPlus] } }); return wrapper }
function text() { return document.body.textContent ?? '' }
async function click(label: string) {
  const button = [...document.querySelectorAll('button')].find(button => button.textContent?.trim() === label)
  expect(button).toBeDefined(); button!.click(); await flushPromises()
}
beforeEach(() => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-03T00:00:05Z'))
  vi.spyOn(api, 'getStoryAccountingCalls').mockResolvedValue([call()])
  vi.spyOn(api, 'getStoryAccountingCall').mockResolvedValue(call())
  vi.spyOn(api, 'dismissStoryAccountingCall').mockResolvedValue()
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.restoreAllMocks(); vi.useRealTimers() })
it('opens globally before a Designer exists, shows actual output and keeps waiting past 30 seconds', async () => {
  open(); await flushPromises()
  expect(text()).toContain('正在开启故事点统计')
  expect(text()).toContain('已收到故事编号 000123')
  expect(wrapper!.findComponent(ElDialog).props('showClose')).toBe(false)
  await vi.advanceTimersByTimeAsync(35_000)
  expect(text()).toContain('已用 40 秒')
  expect(text()).toContain('正在等待统计结果')
  expect(text()).not.toContain('超时')
})
it('cancels only the selected accounting call and retains the receipt after completion', async () => {
  const cancelled = { ...call(), state: 'CANCELLED' as const, finishedAt: '2026-09-03T00:00:06Z', detail: '已取消本次统计，任务继续执行。' }
  const cancel = vi.spyOn(api, 'cancelStoryAccountingCall').mockResolvedValue(cancelled)
  open(); await flushPromises()
  await click('取消本次统计，继续任务')
  expect(cancel).toHaveBeenCalledExactlyOnceWith('one')
  expect(text()).toContain('已取消本次统计，任务继续执行')
  expect(text()).toContain('已收到故事编号 000123')
  await click('关闭')
  expect(api.dismissStoryAccountingCall).toHaveBeenCalledExactlyOnceWith('one')
})
it('recovers a pending completion after refresh and can show independent parallel calls', async () => {
  vi.mocked(api.getStoryAccountingCalls).mockResolvedValue([{ ...call(), operation: 'complete' }, call('two')])
  vi.mocked(api.getStoryAccountingCall).mockResolvedValue({ ...call(), operation: 'complete' })
  open(); await flushPromises()
  expect(text()).toContain('正在完成故事点统计')
  expect(document.querySelector('[aria-label="选择统计会话"]')).not.toBeNull()
  wrapper!.unmount(); open(); await flushPromises()
  expect(text()).toContain('正在完成故事点统计')
})
it('keeps cancellation available when output refresh fails', async () => {
  vi.mocked(api.getStoryAccountingCall).mockRejectedValue(new Error('offline'))
  open(); await flushPromises()
  expect(text()).toContain('取消本次统计，继续任务')
  expect(text()).toContain('统计状态暂时无法刷新')
})
it.each(['start', 'continue', 'complete'] as const)('explicitly retries failed %s with a fresh call and preserves the old output', async operation => {
  const failed = { ...call(), operation, state: 'FAILED' as const, retryAvailable: true, finishedAt: '2026-09-03T00:00:05Z' }
  vi.mocked(api.getStoryAccountingCalls).mockResolvedValue([failed])
  vi.mocked(api.getStoryAccountingCall).mockResolvedValue(failed)
  const retry = vi.spyOn(api, 'retryStoryAccountingCall').mockResolvedValue({ ...call('retry'), operation })
  open(); await flushPromises()
  await click(`重新发起 ${operation}`)
  expect(retry).toHaveBeenCalledExactlyOnceWith('one')
  expect(text()).toContain('正在等待统计结果')
  expect(document.querySelector('[aria-label="选择统计会话"]')).not.toBeNull()
  expect(api.dismissStoryAccountingCall).not.toHaveBeenCalled()
})
it('disables retry while the remote is still owned by business and explains why', async () => {
  const failed = { ...call(), state: 'FAILED' as const, retryAvailable: false, retryUnavailableReason: '该会话仍用于业务或提问，请在会话交接后重试' }
  vi.mocked(api.getStoryAccountingCalls).mockResolvedValue([failed])
  vi.mocked(api.getStoryAccountingCall).mockResolvedValue(failed)
  open(); await flushPromises()
  expect([...document.querySelectorAll('button')].find(button => button.textContent?.includes('重新发起 start'))?.disabled).toBe(true)
  expect(text()).toContain(failed.retryUnavailableReason)
})
it('keeps the dialog open and prevents another retry while the new call is being created', async () => {
  const failed = { ...call(), state: 'FAILED' as const, retryAvailable: true }
  vi.mocked(api.getStoryAccountingCalls).mockResolvedValue([failed])
  vi.mocked(api.getStoryAccountingCall).mockResolvedValue(failed)
  let finish!: (value: StoryAccountingCall) => void
  const retry = vi.spyOn(api, 'retryStoryAccountingCall').mockReturnValue(new Promise(resolve => { finish = resolve }))
  open(); await flushPromises()
  await click('重新发起 start')
  expect(wrapper!.findComponent(ElDialog).props('showClose')).toBe(false)
  expect(wrapper!.findComponent(ElDialog).props('closeOnPressEscape')).toBe(false)
  await click('重新发起 start')
  expect(retry).toHaveBeenCalledTimes(1)
  finish(call('retry')); await flushPromises()
  expect(text()).toContain('正在等待统计结果')
})
