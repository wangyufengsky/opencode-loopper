import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import TaskDesignHistoryView from '@/views/TaskDesignHistoryView.vue'

afterEach(() => vi.restoreAllMocks())

describe('TaskDesignHistoryView', () => {
  it('renders persisted conversation and the confirmed LoopSpec as a read-only history', async () => {
    vi.spyOn(api, 'getTaskDesignHistory').mockResolvedValue({
      taskId: 'task-1', taskTitle: '历史任务', projectName: '项目 A',
      draft: {
        id: 'draft-1', status: 'CONFIRMED', updatedAt: '2026-08-05T09:00:00Z',
        spec: {
          schemaVersion: 'v1', projectId: 'project-a', goal: '保留设计历史', context: '只读展示',
          stages: [{ objective: '实现历史入口', allowedPaths: ['frontend/src/**'], forbiddenPaths: ['data/**'], deliverables: ['历史页面'], verifiers: [{ type: 'PROCESS', command: ['npm', 'test'] }] }],
          limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: 'PT2H', attemptTimeout: 'PT30M' },
        },
      },
      designerSession: {
        id: 'designer-1', state: 'COMPLETED', accessMode: 'READ_ONLY', createdAt: '2026-08-05T08:00:00Z', updatedAt: '2026-08-05T09:00:00Z',
        messages: [
          { id: 'notice', role: 'SYSTEM', content: 'Designer session created in read-only mode.', deliveryState: 'PENDING_HANDOFF', createdAt: '2026-08-05T08:00:00Z' },
          { id: 'user', role: 'USER', content: '请保留设计历史', deliveryState: 'PERSISTED', createdAt: '2026-08-05T08:01:00Z' },
          { id: 'assistant', role: 'ASSISTANT', content: '## 历史设计方案', deliveryState: 'PERSISTED', createdAt: '2026-08-05T08:02:00Z' },
        ],
      },
    })
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/tasks/:id/design', component: TaskDesignHistoryView },
      { path: '/tasks/:id', component: { template: '<div />' } },
      { path: '/tasks', component: { template: '<div />' } },
    ] })
    await router.push('/tasks/task-1/design')
    await router.isReady()

    const wrapper = mount(TaskDesignHistoryView, {
      global: {
        plugins: [router, ElementPlus],
        stubs: { teleport: true, Icon: true, MarkdownDocument: { props: ['content'], template: '<div class="markdown-stub">{{ content }}</div>' } },
      },
    })
    await flushPromises()

    expect(api.getTaskDesignHistory).toHaveBeenCalledWith('task-1')
    expect(wrapper.text()).toContain('请保留设计历史')
    expect(wrapper.text()).toContain('历史设计方案')
    expect(wrapper.text()).not.toContain('Designer session created in read-only mode.')
    expect(wrapper.text()).toContain('保留设计历史')
    expect(wrapper.text()).toContain('实现历史入口')
    expect(wrapper.text()).toContain('npm test')
    expect(wrapper.text()).toContain('查看完整 LoopSpec JSON')
    expect(wrapper.find('textarea').exists()).toBe(false)
  })
})
