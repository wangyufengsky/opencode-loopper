import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DesignerCurrentActivity from '@/components/DesignerCurrentActivity.vue'
import { api } from '@/api/client'

describe('DesignerCurrentActivity', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('renders only the newest activity as Markdown inside the current role card', async () => {
    vi.useFakeTimers()
    const getActivity = vi.spyOn(api, 'getDesignerActivity')
      .mockResolvedValueOnce({
        actor: 'DESIGNER', remoteState: 'BUSY', connected: true,
        observedAt: '2026-08-20T08:00:00Z',
        parts: [
          { id: 'thinking-1', type: 'THINKING', label: 'thinking', content: '旧的思考内容' },
          { id: 'output-1', type: 'OUTPUT', label: 'assistant', content: '## 正在形成需求稿\n\n只显示当前进展。' },
        ],
      })
      .mockResolvedValueOnce({
        actor: 'COMPILER', remoteState: 'RUNNING', connected: true,
        observedAt: '2026-08-20T08:00:01Z', structuredStep: 'SERVER_COMPILING',
        parts: [
          { id: 'tool-old', type: 'TOOL', label: 'read', content: '旧工具调用', status: 'COMPLETED' },
          { id: 'tool-current', type: 'TOOL', label: 'gitlab_search', content: '**正在查询** `profile`', status: 'RUNNING' },
        ],
      })

    const wrapper = mount(DesignerCurrentActivity, {
      props: { sessionId: 'designer-1' },
      global: { stubs: { Icon: true } },
    })
    await flushPromises()

    expect(wrapper.attributes('aria-label')).toBe('设计师正在处理')
    expect(wrapper.find('.markdown-document h2').text()).toBe('正在形成需求稿')
    expect(wrapper.text()).not.toContain('旧的思考内容')

    await vi.advanceTimersByTimeAsync(1_200)
    await flushPromises()

    expect(getActivity).toHaveBeenCalledTimes(2)
    expect(wrapper.attributes('aria-label')).toBe('规范工程师正在处理')
    expect(wrapper.text()).toContain('gitlab_search')
    expect(wrapper.text()).toContain('正在查询')
    expect(wrapper.text()).not.toContain('旧工具调用')
    expect(wrapper.text()).not.toContain('正在形成需求稿')
    wrapper.unmount()
  })

  it('keeps the single latest fragment visible during a reconnectable refresh failure', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'getDesignerActivity')
      .mockResolvedValueOnce({
        actor: 'ROUTER', remoteState: 'RUNNING', connected: true,
        observedAt: '2026-08-20T08:00:00Z',
        parts: [{ id: 'tool-1', type: 'TOOL', label: 'jira_search', content: '正在确认需求边界' }],
      })
      .mockRejectedValueOnce(new Error('connection reset'))
    const wrapper = mount(DesignerCurrentActivity, {
      props: { sessionId: 'designer-1' }, global: { stubs: { Icon: true } },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1_200)
    await flushPromises()

    expect(wrapper.attributes('aria-label')).toBe('需求分析师正在处理')
    expect(wrapper.text()).toContain('当前角色活动暂时无法刷新')
    expect(wrapper.text()).toContain('正在确认需求边界')
    expect(wrapper.findAll('.current-activity')).toHaveLength(1)
    wrapper.unmount()
  })
})
