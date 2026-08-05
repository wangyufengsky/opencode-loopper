import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SessionMonitorPanel from '@/components/SessionMonitorPanel.vue'
import { api } from '@/api/client'
import type { TaskSessionActivity, TaskSessionSummary } from '@/types/domain'

const session: TaskSessionSummary = {
  key: 'execution:local-1', kind: 'IMPLEMENTATION', label: 'Implementation Session', localSessionId: 'local-1',
  externalSessionId: 'remote-1', state: 'RUNNING', stageId: 'stage-1', attemptId: 'attempt-1', createdAt: 'now',
}

function activity(parts: TaskSessionActivity['parts']): TaskSessionActivity {
  return { session, remoteState: 'busy', live: true, observedAt: '2026-08-04T08:00:00Z', parts }
}

afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })

describe('SessionMonitorPanel', () => {
  it('polls the selected Session and replaces thinking with incremental model output dynamically', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'getTaskSessions').mockResolvedValue([session])
    vi.spyOn(api, 'getTaskSessionActivity')
      .mockResolvedValueOnce(activity([{ id: 'reason-1', type: 'THINKING', label: 'Thinking', content: '正在检查项目', status: 'running', startedAt: '2026-08-04T08:00:01Z' }]))
      .mockResolvedValueOnce(activity([
        { id: 'reason-1', type: 'THINKING', label: 'Thinking', content: '正在检查项目', status: 'completed' },
        { id: 'text-1', type: 'OUTPUT', label: '模型输出', content: '开始实现动态面板' },
      ]))
    const wrapper = mount(SessionMonitorPanel, { props: { taskId: 'task-1' }, global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(wrapper.find('[aria-label="模型正在思考"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('正在检查项目')
    expect(wrapper.text()).toContain('实时 · 1.2 秒')
    expect(wrapper.text()).toContain('执行会话')
    expect(wrapper.text()).toContain('思考')
    expect(wrapper.text()).not.toContain('Implementation Session')
    expect(wrapper.find('time[datetime="2026-08-04T08:00:01Z"]').exists()).toBe(true)
    expect(wrapper.find('.console-stream').element.lastElementChild?.getAttribute('aria-label')).toBe('模型正在思考')

    await vi.advanceTimersByTimeAsync(1200)
    await flushPromises()

    expect(wrapper.find('[aria-label="模型正在思考"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('开始实现动态面板')
    expect(api.getTaskSessionActivity).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})
