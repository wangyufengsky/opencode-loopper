import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SessionMonitorPanel from '@/components/SessionMonitorPanel.vue'
import { api } from '@/api/client'
import type { TaskSessionActivity, TaskSessionSummary } from '@/types/domain'

const session: TaskSessionSummary = {
  key: 'execution:local-1', kind: 'IMPLEMENTATION', label: 'Implementation Session', localSessionId: 'local-1',
  externalSessionId: 'remote-1', state: 'RUNNING', stageId: 'stage-1', stageOrdinal: 1,
  stageObjective: '实现动态会话监控并完成本阶段验证', attemptId: 'attempt-1', createdAt: 'now',
}

function activity(parts: TaskSessionActivity['parts'], pendingQuestions: TaskSessionActivity['pendingQuestions'] = []): TaskSessionActivity {
  return { session, remoteState: 'busy', live: true, observedAt: '2026-08-04T08:00:00Z', parts, pendingQuestions }
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
    const wrapper = mount(SessionMonitorPanel, { props: { taskId: 'task-1' }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })
    await flushPromises()

    expect(wrapper.find('[aria-label="模型正在思考"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('正在检查项目')
    expect(wrapper.text()).toContain('实时 · 1.2 秒')
    expect(wrapper.text()).toContain('执行会话')
    expect(wrapper.text()).toContain('阶段 1 · 执行会话')
    expect(wrapper.text()).toContain('实现动态会话监控并完成本阶段验证')
    expect(wrapper.text()).not.toContain('remote-1')
    expect(wrapper.text()).not.toContain('local-1')
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

  it('collapses overflowing activity content to five lines and lets the user expand it', async () => {
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(220)
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(100)
    vi.spyOn(api, 'getTaskSessions').mockResolvedValue([session])
    vi.spyOn(api, 'getTaskSessionActivity').mockResolvedValue(activity([{
      id: 'tool-1',
      type: 'TOOL',
      label: '终端命令',
      content: Array.from({ length: 8 }, (_, index) => `输出第 ${index + 1} 行`).join('\n'),
      status: 'completed',
    }]))

    const wrapper = mount(SessionMonitorPanel, { props: { taskId: 'task-1' }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })
    await flushPromises()

    expect(wrapper.get('.activity-part pre').classes()).toContain('is-collapsed')
    const toggle = wrapper.get('.part-expand-button')
    expect(toggle.text()).toContain('展开完整输出')
    expect(toggle.attributes('aria-expanded')).toBe('false')

    await toggle.trigger('click')
    expect(wrapper.get('.activity-part pre').classes()).not.toContain('is-collapsed')
    expect(toggle.text()).toContain('收起输出')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    wrapper.unmount()
  })

  it('renders a pending OpenCode question and submits the selected answer', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'getTaskSessions').mockResolvedValue([session])
    vi.spyOn(api, 'getTaskSessionActivity').mockResolvedValue(activity([], [{
      id: 'que-1',
      questions: [{
        question: '整体编译被历史问题阻塞，如何处理？', header: 'Build blocker', multiple: false, custom: true,
        options: [
          { label: '按当前范围收尾', description: '如实报告遗留边界' },
          { label: '扩大范围修复', description: '修改额外文件' },
        ],
      }],
    }]))
    const reply = vi.spyOn(api, 'replyTaskSessionQuestion').mockResolvedValue(undefined)

    const wrapper = mount(SessionMonitorPanel, { props: { taskId: 'task-1' }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })
    await flushPromises()

    expect(wrapper.get('[aria-label="OpenCode 等待回答"]').text()).toContain('整体编译被历史问题阻塞')
    await wrapper.find('input[type="radio"]').setValue(true)
    const submit = wrapper.findAll('button').find((button) => button.text().includes('提交回答并继续'))
    expect(submit).toBeDefined()
    await submit!.trigger('click')
    await flushPromises()

    expect(reply).toHaveBeenCalledWith('task-1', 'execution:local-1', 'que-1', [['按当前范围收尾']])
    wrapper.unmount()
  })
})
