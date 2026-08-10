import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TaskDetailView from '@/views/TaskDetailView.vue'

const store = vi.hoisted(() => ({
  tasks: [] as Array<Record<string, unknown>>,
  artifacts: [] as Array<Record<string, unknown>>,
  usingDemo: false,
  streamState: 'idle',
  loadTask: vi.fn().mockResolvedValue(undefined),
  watchTask: vi.fn(),
  stopWatching: vi.fn(),
  updateTask: vi.fn(),
  retryJudges: vi.fn().mockResolvedValue(undefined),
  reworkTask: vi.fn().mockResolvedValue('task-rework'),
}))

vi.mock('@/stores/taskStore', () => ({ useTaskStore: () => store }))

const reviewTask = {
  id: 'task-review', projectId: 'project-1', title: '待评审任务', goal: '验证显式评审入口',
  status: 'WAITING_INPUT', branch: 'DIRECT', worktreePath: '/tmp/project', attemptCount: 1, maxAttempts: 3,
  createdAt: 'start', updatedAt: 'now', errors: [], artifacts: [], attempts: [],
  stages: [{ id: 'stage-1', ordinal: 1, objective: '完成实现', status: 'SUCCEEDED', attempts: [] }],
  judges: [
    { id: 'requirement-1', role: 'REQUIREMENT', ordinal: 1, status: 'COMPLETED', verdict: 'BLOCKED', reason: '证据不足', createdAt: 'start' },
    { id: 'risk-1', role: 'RISK', ordinal: 1, status: 'COMPLETED', verdict: 'PASS', reason: '风险可控', createdAt: 'start' },
  ],
}

describe('TaskDetailView judge action', () => {
  beforeEach(() => {
    store.tasks = [reviewTask]
    store.loadTask.mockClear()
    store.watchTask.mockClear()
    store.stopWatching.mockClear()
    store.retryJudges.mockClear()
    store.reworkTask.mockClear()
  })

  it('offers an explicit fresh double review for a deterministically accepted waiting task', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-review')
    await router.isReady()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)

    const wrapper = mount(TaskDetailView, {
      global: {
        plugins: [router, ElementPlus],
        stubs: {
          Icon: true,
          PageHeader: { template: '<header><slot name="actions" /></header><slot />' },
          StatusBadge: true,
          StageRail: true,
          AttemptTimeline: true,
          LayeredErrorPanel: true,
          SessionMonitorPanel: true,
          JudgeReviewCard: true,
          TaskAuditEvidencePanel: true,
          TaskPublicationActions: true,
        },
      },
    })
    await flushPromises()

    const action = wrapper.findAll('button').find((button) => button.text().includes('重新发起双评审'))
    expect(action).toBeDefined()
    expect(wrapper.find('#judge-review').exists()).toBe(true)
    await action!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('两个新的只读 OpenCode 评审 Session'), expect.any(String), expect.any(Object))
    expect(store.retryJudges).toHaveBeenCalledWith('task-review')
  })

  it('hides a historical judge conflict after the latest double review passes', async () => {
    store.tasks = [{
      ...reviewTask,
      id: 'task-approved',
      status: 'SUCCEEDED',
      errors: [
        { id: 'old-conflict', layer: 'VERIFICATION', code: 'JUDGE_CONFLICT', message: '旧双评审冲突', retryable: false, occurredAt: 'earlier' },
        { id: 'verification-note', layer: 'VERIFICATION', code: 'PROCESS_FAILED', message: '仍需展示的普通验证证据', retryable: false, occurredAt: 'earlier' },
      ],
      judges: [
        ...reviewTask.judges,
        { id: 'requirement-2', role: 'REQUIREMENT', ordinal: 2, status: 'COMPLETED', verdict: 'PASS', reason: '需求已满足', createdAt: 'later' },
        { id: 'risk-2', role: 'RISK', ordinal: 2, status: 'COMPLETED', verdict: 'PASS', reason: '风险可控', createdAt: 'later' },
      ],
    }]
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-approved')
    await router.isReady()

    const wrapper = mount(TaskDetailView, {
      global: {
        plugins: [router, ElementPlus],
        stubs: {
          Icon: true,
          PageHeader: { template: '<header><slot name="actions" /></header><slot />' },
          StatusBadge: true,
          StageRail: true,
          AttemptTimeline: true,
          LayeredErrorPanel: { props: ['error'], template: '<div class="layered-error-stub">{{ error.code }}</div>' },
          SessionMonitorPanel: true,
          JudgeReviewCard: true,
          TaskAuditEvidencePanel: true,
          TaskPublicationActions: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.findAll('.layered-error-stub').map((panel) => panel.text())).toEqual(['PROCESS_FAILED'])
    expect(wrapper.find('#judge-review').exists()).toBe(true)
  })

  it('creates a new branch rework task and navigates to the child', async () => {
    store.tasks = [{ ...reviewTask, id: 'task-success', title: '已完成任务', status: 'SUCCEEDED', branch: 'loopper/task-success' }]
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-success')
    await router.isReady()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)

    const wrapper = mount(TaskDetailView, {
      global: {
        plugins: [router, ElementPlus],
        stubs: {
          Icon: true,
          PageHeader: { template: '<header><slot name="actions" /></header><slot />' },
          StatusBadge: true,
          StageRail: true,
          AttemptTimeline: true,
          LayeredErrorPanel: true,
          SessionMonitorPanel: true,
          JudgeReviewCard: true,
          TaskAuditEvidencePanel: true,
          TaskPublicationActions: true,
        },
      },
    })
    await flushPromises()

    const action = wrapper.findAll('button').find((button) => button.text().includes('新分支重做'))
    expect(action).toBeDefined()
    await action!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('全新分支和 worktree'), '新分支重做任务？', expect.any(Object))
    expect(store.reworkTask).toHaveBeenCalledWith('task-success')
    expect(router.currentRoute.value.path).toBe('/tasks/task-rework')
  })
})
