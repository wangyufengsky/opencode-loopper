import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElDialog } from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import StoryAccountingDialog from './StoryAccountingDialog.vue'
import { api } from '@/api/client'
import type { StoryAccountingCall } from '@/types/domain'

const events = vi.hoisted(() => ({ change: () => {}, ready: () => {}, close: vi.fn() }))
vi.mock('@/api/client', async importOriginal => ({
  ...await importOriginal<typeof import('@/api/client')>(),
  subscribeStoryAccountingEvents: (change: () => void, ready: () => void) => {
    events.change = change; events.ready = ready; ready(); return { close: events.close }
  },
}))

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

it('stays idle after an empty recovery and opens only when a server event announces a call', async () => {
  vi.mocked(api.getStoryAccountingCalls).mockResolvedValue([])
  open(); await flushPromises()
  await vi.advanceTimersByTimeAsync(60_000)
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(1)
  expect(api.getStoryAccountingCall).not.toHaveBeenCalled()
  vi.mocked(api.getStoryAccountingCalls).mockResolvedValue([call()])
  events.change(); await flushPromises()
  expect(text()).toContain('正在开启故事点统计')
  await vi.advanceTimersByTimeAsync(3_600)
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(2)
  expect(api.getStoryAccountingCall).toHaveBeenCalledTimes(4)
})

it('stops all polling on completion and keeps the receipt until explicitly dismissed', async () => {
  open(); await flushPromises()
  const finished = { ...call(), state: 'SUCCEEDED' as const, finishedAt: '2026-09-03T00:00:06Z' }
  vi.mocked(api.getStoryAccountingCall).mockResolvedValue(finished)
  await vi.advanceTimersByTimeAsync(1_200)
  const reads = vi.mocked(api.getStoryAccountingCall).mock.calls.length
  await vi.advanceTimersByTimeAsync(60_000)
  expect(api.getStoryAccountingCall).toHaveBeenCalledTimes(reads)
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(1)
  expect(text()).toContain('统计已完成')
  await click('关闭')
  await vi.advanceTimersByTimeAsync(60_000)
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(1)
})

it('reconciles on reconnect and on returning to a visible page without polling in the background', async () => {
  vi.mocked(api.getStoryAccountingCalls).mockResolvedValue([])
  open(); await flushPromises()
  events.ready(); await flushPromises()
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(2)
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
  document.dispatchEvent(new Event('visibilitychange'))
  vi.mocked(api.getStoryAccountingCalls).mockResolvedValue([call()])
  events.change(); events.change()
  await vi.advanceTimersByTimeAsync(60_000)
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(2)
  visibility.mockReturnValue('visible')
  document.dispatchEvent(new Event('visibilitychange')); await flushPromises()
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(3)
  expect(text()).toContain('正在开启故事点统计')
  wrapper!.unmount(); await vi.advanceTimersByTimeAsync(60_000)
  expect(events.close).toHaveBeenCalled()
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(3)
})

it('reconciles an event arriving during the initial snapshot without overlapping requests', async () => {
  let finish!: (value: StoryAccountingCall[]) => void
  vi.mocked(api.getStoryAccountingCalls).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  open(); events.change(); events.change()
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(1)
  finish([]); await flushPromises(); await vi.advanceTimersByTimeAsync(1)
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(2)
  expect(text()).toContain('正在开启故事点统计')
})

it('recovers a failed initial snapshot with bounded retries instead of silently losing the dialog', async () => {
  vi.mocked(api.getStoryAccountingCalls).mockRejectedValueOnce(new Error('offline')).mockResolvedValue([])
  open(); await flushPromises(); await vi.advanceTimersByTimeAsync(2_000)
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(2)
  await vi.advanceTimersByTimeAsync(60_000)
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(2)
})

it('does not turn output errors back into repeated list discovery', async () => {
  vi.mocked(api.getStoryAccountingCall).mockRejectedValue(new Error('offline'))
  open(); await flushPromises(); await vi.advanceTimersByTimeAsync(6_000)
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(1)
  expect(api.getStoryAccountingCall).toHaveBeenCalledTimes(6)
  expect(text()).toContain('取消本次统计，继续任务')
})

it('bounds failed idle recovery and allows a fresh reconnect to recover later', async () => {
  vi.mocked(api.getStoryAccountingCalls).mockRejectedValue(new Error('offline'))
  open(); await flushPromises(); await vi.advanceTimersByTimeAsync(60_000)
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(4)
  vi.mocked(api.getStoryAccountingCalls).mockResolvedValue([call()])
  events.ready(); await flushPromises()
  expect(text()).toContain('正在开启故事点统计')
})

it.each(['event', 'detail'] as const)('continues the next parallel call when completion arrives through %s', async source => {
  const second = { ...call('two'), operation: 'complete' as const }
  const finished = { ...call(), state: 'SUCCEEDED' as const }
  vi.mocked(api.getStoryAccountingCalls).mockResolvedValue([call(), second])
  vi.mocked(api.getStoryAccountingCall).mockImplementation(async id => id === 'two' ? second : call())
  open(); await flushPromises()
  vi.mocked(api.getStoryAccountingCall).mockImplementation(async id => id === 'two' ? second : finished)
  if (source === 'event') {
    vi.mocked(api.getStoryAccountingCalls).mockResolvedValue([finished, second])
    events.change(); await flushPromises()
  } else await vi.advanceTimersByTimeAsync(1_201)
  expect(text()).toContain('正在完成故事点统计')
  expect(api.getStoryAccountingCall).toHaveBeenLastCalledWith('two')
  expect(api.getStoryAccountingCalls).toHaveBeenCalledTimes(source === 'event' ? 2 : 1)
})
