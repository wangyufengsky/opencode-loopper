import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SessionLifecyclePanel from '@/components/SessionLifecyclePanel.vue'

afterEach(() => vi.restoreAllMocks())

describe('SessionLifecyclePanel', () => {
  it('renders persisted snapshots and only labels the todo refresh as a real OpenCode sync', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify([{ id: 'todo-local', externalTodoId: 'todo-remote', content: '读取服务端 todo', status: 'IN_PROGRESS', ordinal: 1, observedAt: 'now' }]), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify([{ id: 'checkpoint-1', taskId: 'task-1', sessionId: 'session-1', attemptId: 'attempt-1', contentSha256: 'a'.repeat(64), createdAt: 'now' }]), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(SessionLifecyclePanel, { props: { taskId: 'task-1', sessionId: 'session-1', sessionState: 'COMPLETED', taskState: 'PAUSED', directExecution: true }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('读取服务端 todo')
    expect(wrapper.text()).toContain('同步真实 todo')
    expect(wrapper.text()).toContain('直接执行目录不支持原地回退')
    expect(wrapper.get('button').text()).toContain('刷新快照')
    expect(wrapper.findAll('button').find(button => button.text().includes('回退 worktree'))?.attributes('disabled')).toBeDefined()
  })
})
