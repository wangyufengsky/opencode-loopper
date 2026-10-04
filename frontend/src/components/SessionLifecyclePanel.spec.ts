import { flushPromises, mount } from '@/pages/w6-tests/ordinary/render'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SessionLifecyclePanel } from '@/pages/w4/task/SessionLifecyclePanel'
import {panelFixture,taskFixture} from '@/pages/w6-tests/ordinary/task'

afterEach(() => vi.restoreAllMocks())

describe('SessionLifecyclePanel', () => {
  it('renders persisted snapshots and only labels the todo refresh as a real OpenCode sync', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify([{ id: 'todo-local', externalTodoId: 'todo-remote', content: '读取服务端 todo', status: 'IN_PROGRESS', ordinal: 1, observedAt: 'now' }]), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify([{ id: 'checkpoint-1', taskId: 'task-1', sessionId: 'session-1', attemptId: 'attempt-1', contentSha256: 'a'.repeat(64), createdAt: 'now' }]), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(SessionLifecyclePanel, { props: { ...panelFixture(taskFixture('task-1',{status:'PAUSED',branch:'DIRECT'})).props, sessionId:'session-1' } })
    await flushPromises()
    expect(wrapper.text()).toContain('读取服务端 todo')
    expect(wrapper.text()).toContain('同步真实 todo')
    expect(wrapper.text()).toContain('直接执行目录不支持原地回退')
    expect(wrapper.get('button').text()).toContain('刷新快照')
    expect(wrapper.findAll('button').find(button => button.text().includes('回退 worktree'))?.attributes('disabled')).toBeDefined()
  })
})
