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
