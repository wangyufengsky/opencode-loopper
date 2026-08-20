import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DesignerActivityPanel from '@/components/DesignerActivityPanel.vue'
import { api } from '@/api/client'

describe('DesignerActivityPanel', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('refreshes every 1.2 seconds and replaces duplicate activity parts with the authoritative snapshot', async () => {
    vi.useFakeTimers()
    const getActivity = vi.spyOn(api, 'getDesignerActivity')
      .mockResolvedValueOnce({
        actor: 'DESIGNER', remoteState: 'BUSY', connected: true,
        observedAt: '2026-08-20T08:00:00Z',
        parts: [
          { id: 'thinking-1', type: 'THINKING', label: 'thinking', content: '正在阅读上下文' },
          { id: 'tool-1', type: 'TOOL', label: 'gitlab_search', content: '{"query":"profile"}\n返回 2 条', status: 'RUNNING' },
        ],
      })
      .mockResolvedValueOnce({
        actor: 'DESIGNER', remoteState: 'IDLE', connected: true,
        observedAt: '2026-08-20T08:00:01Z',
        parts: [
          { id: 'tool-1', type: 'TOOL', label: 'gitlab_search', content: '{"query":"profile"}\n返回 2 条', status: 'COMPLETED' },
          { id: 'output-1', type: 'OUTPUT', label: 'assistant', content: '任务画像已重算' },
        ],
      })

    const wrapper = mount(DesignerActivityPanel, {
      props: { sessionId: 'designer-1' },
      global: { plugins: [ElementPlus], stubs: { Icon: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('设计师')
    expect(wrapper.text()).toContain('gitlab_search')
    expect(wrapper.text()).toContain('返回 2 条')

    await vi.advanceTimersByTimeAsync(1_200)
    await flushPromises()

    expect(getActivity).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.activity-part')).toHaveLength(2)
    expect(wrapper.text()).toContain('任务画像已重算')
    expect(wrapper.text()).not.toContain('正在阅读上下文')
    wrapper.unmount()
  })

  it('keeps the last activity visible while reporting a reconnectable refresh error', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'getDesignerActivity')
      .mockResolvedValueOnce({
        actor: 'COMPILER', remoteState: 'RUNNING', connected: true,
        observedAt: '2026-08-20T08:00:00Z', structuredStep: 'SERVER_COMPILING',
        parts: [{ id: 'tool-1', type: 'TOOL', label: 'repo_read', content: 'src/App.java' }],
      })
      .mockRejectedValueOnce(new Error('connection reset'))
    const wrapper = mount(DesignerActivityPanel, {
      props: { sessionId: 'designer-1' },
      global: { plugins: [ElementPlus], stubs: { Icon: true } },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1_200)
    await flushPromises()

    expect(wrapper.text()).toContain('规范工程师')
    expect(wrapper.text()).toContain('设计师活动暂时无法刷新')
    expect(wrapper.text()).toContain('repo_read')
    wrapper.unmount()
  })

  it('keeps the last activity when the server reports a disconnected persisted state', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'getDesignerActivity')
      .mockResolvedValueOnce({
        actor: 'DESIGNER', remoteState: 'RUNNING', connected: true,
        observedAt: '2026-08-20T08:00:00Z',
        parts: [{ id: 'output-1', type: 'OUTPUT', label: 'assistant', content: '正在形成完整需求稿' }],
      })
      .mockResolvedValueOnce({
        actor: 'DESIGNER', remoteState: 'DISCONNECTED', connected: false,
        observedAt: '2026-08-20T08:00:01Z', parts: [], detail: '远端连接暂时不可用',
      })
    const wrapper = mount(DesignerActivityPanel, {
      props: { sessionId: 'designer-1' },
      global: { plugins: [ElementPlus], stubs: { Icon: true } },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1_200)
    await flushPromises()

    expect(wrapper.text()).toContain('远端连接暂时不可用')
    expect(wrapper.text()).toContain('正在形成完整需求稿')
    wrapper.unmount()
  })
})
