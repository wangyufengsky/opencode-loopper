import {flushPromises,mountPanel,taskFixture} from '@/pages/w6-tests/ordinary/task'
import { afterEach,beforeEach, describe, expect, it, vi } from 'vitest'
import {SessionMonitorPanel} from '@/pages/w4/task/SessionMonitorPanel'
import { api } from '@/api/client'
import type { TaskSessionActivity, TaskSessionSummary } from '@/types/domain'

const session: TaskSessionSummary = {
  key: 'execution:local-1', kind: 'IMPLEMENTATION', label: 'Implementation Session', localSessionId: 'local-1',
  externalSessionId: 'remote-1', state: 'RUNNING', stageId: 'stage-1', stageOrdinal: 1,
  stageObjective: '实现动态会话监控并完成本阶段验证', attemptId: 'attempt-1', createdAt: 'now',
}

function activity(parts: TaskSessionActivity['parts'], pendingQuestions: TaskSessionActivity['pendingQuestions'] = []): TaskSessionActivity {
  return { session, remoteState: 'busy', live: true, observedAt: '2026-08-04T08:00:00Z', parts, pendingQuestions,
    todoCapability: 'AVAILABLE', todos: [], todoTruncated: false,
    usage: { totalTokens: 96, unknownUsageCount: 0, observedAt: '2026-08-04T08:00:00Z' } }
}

beforeEach(()=>{vi.spyOn(api,'getTaskSessionTodos').mockResolvedValue([]);vi.spyOn(api,'getTaskSessionCheckpoints').mockResolvedValue([])})
afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })

describe('SessionMonitorPanel', () => {
  it('reads frozen role permissions only for the Session the user expands', async () => {
    vi.useFakeTimers()
    const nextSession = { ...session, key: 'execution:local-2', localSessionId: 'local-2', externalSessionId: 'remote-2' }
    vi.spyOn(api, 'getTaskSessions').mockResolvedValue([session, nextSession])
    vi.spyOn(api, 'getTaskSessionActivity').mockImplementation(async (_id,key)=>({...activity([]),session:key===session.key?session:{...session,key,localSessionId:key.slice(key.indexOf(':')+1)}}))
    const getRole = vi.spyOn(api, 'getTaskSessionRole').mockResolvedValue({ configured: false, roleId: null,
      revisionId: null, revisionSha256: null, slot: null, adapterProfile: null, adapterVersion: null,
      permissions: [], permissionSha256: null })
    const wrapper = mountPanel(SessionMonitorPanel,taskFixture('task-1'),{}).view
    await flushPromises()
    expect(getRole).not.toHaveBeenCalled()
    await wrapper.get('.w4-role-summary button').trigger('click')
    await flushPromises()
    expect(getRole).toHaveBeenCalledWith('task-1', 'execution:local-1')
    await vi.advanceTimersByTimeAsync(1200)
    await flushPromises()
    expect(getRole).toHaveBeenCalledTimes(1)
    await wrapper.findAll('[aria-label="会话列表"] button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.get('.w4-role-summary button').attributes('aria-expanded')).toBe('false')
    expect(getRole).toHaveBeenCalledTimes(1)
    await wrapper.get('.w4-role-summary button').trigger('click')
    await flushPromises()
    expect(getRole).toHaveBeenLastCalledWith('task-1', 'execution:local-2')
    wrapper.unmount()
  })

  it('opens the exact local session key selected from template batch diagnostics', async () => {
    vi.useFakeTimers()
    const target = { ...session, key: 'execution:local-12', localSessionId: 'local-12', externalSessionId: 'ses_remote-12',
      templateBatch: { purpose: 'SNAPSHOT_LINKS', ordinal: 12, total: null, overallOrdinal: null, overallTotal: null, repairRound: 0, cleanup: false } }
    vi.spyOn(api, 'getTaskSessions').mockResolvedValue([session, target])
    vi.spyOn(api, 'getTaskSessionActivity').mockImplementation(async (_id,key)=>({...activity([]),session:key===session.key?session:{...session,key,localSessionId:key.slice(key.indexOf(':')+1)}}))
    const wrapper = mountPanel(SessionMonitorPanel,taskFixture('task-1'),{}).view
    await flushPromises()
    await wrapper.findAll('[aria-label="会话列表"] button')[1]!.trigger('click')
    await flushPromises()
    expect(api.getTaskSessionActivity).toHaveBeenLastCalledWith('task-1', 'execution:local-12')
    expect(wrapper.find('[aria-label="会话列表"] button[aria-pressed=true]').text()).toContain('第 12 批')
    wrapper.unmount()
  })


  it('polls the selected Session and replaces thinking with incremental model output dynamically', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'getTaskSessions').mockResolvedValue([session])
    vi.spyOn(api, 'getTaskSessionActivity')
      .mockResolvedValueOnce(activity([{ id: 'reason-1', type: 'THINKING', label: 'Thinking', content: '正在检查项目', status: 'running', startedAt: '2026-08-04T08:00:01Z' }]))
      .mockResolvedValueOnce(activity([
        { id: 'reason-1', type: 'THINKING', label: 'Thinking', content: '正在检查项目', status: 'completed' },
        { id: 'text-1', type: 'OUTPUT', label: '模型输出', content: '开始实现动态面板' },
      ]))
    const wrapper = mountPanel(SessionMonitorPanel,taskFixture('task-1'),{}).view
    await flushPromises()

    expect(wrapper.find('[aria-label="等待会话更新"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('正在检查项目')
    expect(wrapper.text()).toContain('检查 · 1.2 秒')
    expect(wrapper.text()).toContain('执行会话')
    expect(wrapper.text()).toContain('阶段 1 · 执行会话')
    expect(wrapper.text()).not.toContain('实现动态会话监控并完成本阶段验证')
    expect(wrapper.text()).not.toContain('remote-1')
    expect(wrapper.text()).not.toContain('local-1')
    expect(wrapper.text()).toContain('思考')
    expect(wrapper.text()).not.toContain('Implementation Session')
    expect(wrapper.find('time[datetime="2026-08-04T08:00:01Z"]').exists()).toBe(true)
    expect(wrapper.find('.w4-output-scroll').element?.lastElementChild?.getAttribute('aria-label')).toBe('等待会话更新')

    await vi.advanceTimersByTimeAsync(1200)
    await flushPromises()

    expect(wrapper.find('[aria-label="等待会话更新"]').exists()).toBe(false)
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

    const wrapper = mountPanel(SessionMonitorPanel,taskFixture('task-1'),{}).view
    await flushPromises()

    expect(wrapper.get('.w4-activity pre').classes()).not.toContain('is-expanded')
    const toggle = wrapper.get('.w4-activity button')
    expect(toggle.attributes('data-semantic')).toBe('ui.expand')
    expect(toggle.attributes('aria-expanded')).toBe('false')

    await toggle.trigger('click')
    expect(wrapper.get('.w4-activity pre').classes()).toContain('is-expanded')
    expect(toggle.attributes('data-semantic')).toBe('ui.collapse')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    wrapper.unmount()
  })

  it('renders bounded OpenCode Todo as a non-authoritative implementation projection', async () => {
    vi.spyOn(api, 'getTaskSessions').mockResolvedValue([session])
    vi.spyOn(api, 'getTaskSessionActivity').mockResolvedValue({
      ...activity([]),
      todos: [
        { id: 'todo-v2:a:1', content: '实现 Todo 同步', status: 'IN_PROGRESS', priority: 'HIGH', ordinal: 0 },
        { id: 'todo-v2:b:1', content: '运行聚焦测试', status: 'PENDING', ordinal: 1 },
      ],
      todoTruncated: true,
    })

    const wrapper = mountPanel(SessionMonitorPanel,taskFixture('task-1'),{}).view
    await flushPromises()

    const panel = wrapper.get('[aria-label="实施计划"]')
    expect(panel.element?.closest('.w4-output-scroll')).toBeNull()
    expect(panel.element?.parentElement?.querySelector('.w4-output-scroll')).toBeTruthy()
    expect(panel.element?.closest('.w4-output-scroll')).toBeNull()
    expect(panel.classes()).not.toContain('todo-panel-pinned')
    expect(panel.text()).toContain('实施计划')
    expect(panel.attributes('data-todo-authority')).toBe('projection');expect(panel.text()).toContain('任务与阶段以服务端状态为准')
    expect(panel.text()).toContain('0 / 2')
    expect(panel.text()).toContain('实现 Todo 同步')
    expect(panel.text()).toContain('进行中 · 高优先级')
    expect(panel.text()).not.toContain('IN_PROGRESS')
    const toggle = panel.get('button');expect(toggle.attributes('aria-expanded')).toBe('false');await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(panel.find('.w4-todo-list').exists()).toBe(false)
    wrapper.unmount()
  })

  it('reserves substantially more vertical space for model output than the compact plan dock', async () => {
    vi.spyOn(api, 'getTaskSessions').mockResolvedValue([session])
    vi.spyOn(api, 'getTaskSessionActivity').mockResolvedValue(activity([{
      id: 'output-1', type: 'OUTPUT', label: '模型输出', content: '正在按计划实现', status: 'running',
    }]))

    const wrapper = mountPanel(SessionMonitorPanel,taskFixture('task-1'),{}).view
    await flushPromises()

    const monitorStyle = (wrapper.get('.w4-session-monitor').element as HTMLElement).style
    expect(monitorStyle.getPropertyValue('--model-output-min-height')).toBe('500px')
    expect(monitorStyle.getPropertyValue('--model-output-max-height')).toBe('680px')
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

    const wrapper = mountPanel(SessionMonitorPanel,taskFixture('task-1'),{}).view
    await flushPromises()

    expect(wrapper.get('[aria-label="待回答问题"]').text()).toContain('整体编译被历史问题阻塞')
    expect(wrapper.get('[aria-label="实施计划"]').element?.closest('.w4-output-scroll')).toBeTruthy()
    const todoPanel = wrapper.get('[aria-label="实施计划"]')
    expect(todoPanel.element.closest('.w4-output-scroll')).not.toBeNull()
    expect(todoPanel.classes()).not.toContain('todo-panel-pinned')
    await wrapper.find('input[type="radio"]').setValue(true)
    const submit = wrapper.findAll('button').find((button) => button.attributes('data-semantic')==='inbox.answer')
    expect(submit).toBeDefined()
    await submit!.trigger('click')
    await flushPromises()

    expect(reply).toHaveBeenCalledWith('task-1', 'execution:local-1', 'que-1', [['按当前范围收尾']])
    wrapper.unmount()
  })
})
