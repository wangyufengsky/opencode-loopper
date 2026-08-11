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
  retryWaitingLoop: vi.fn().mockResolvedValue(undefined),
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
    store.retryWaitingLoop.mockClear()
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

  it('offers one explicit fresh retry when unchanged loop protection is waiting for input', async () => {
    store.tasks = [{
      ...reviewTask,
      id: 'task-stagnant',
      title: '停滞任务',
      attempts: [{ id: 'attempt-2', stageId: 'stage-1', ordinal: 2, status: 'VERIFICATION_FAILED', verifiers: [] }],
      stages: [{ id: 'stage-1', ordinal: 1, objective: '修复验证', status: 'RUNNING', attempts: [] }],
      judges: [],
      waitingReasonCode: 'LOOP_STAGNATION_DETECTED',
      loopRetryAvailable: true,
      errors: [{ id: 'stagnation', layer: 'VERIFICATION', code: 'LOOP_STAGNATION_DETECTED', message: '连续两轮未变化', retryable: true, occurredAt: 'now' }],
    }]
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-stagnant')
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

    const action = wrapper.findAll('button').find((button) => button.text().includes('继续一轮'))
    expect(action).toBeDefined()
    expect(wrapper.text()).toContain('循环已因重复失败或重试策略暂停')
    await action!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('全新的可写 OpenCode Session'), '确认继续一轮？', expect.any(Object))
    expect(store.retryWaitingLoop).toHaveBeenCalledWith('task-stagnant')
  })

  it('does not offer loop retry when an old stagnation error is not the current waiting reason', async () => {
    store.tasks = [{
      ...reviewTask,
      id: 'task-budget-wait',
      title: '预算等待任务',
      stages: [{ id: 'stage-1', ordinal: 1, objective: '继续实现', status: 'RUNNING', attempts: [] }],
      judges: [],
      waitingReasonCode: 'TASK_BUDGET_WAITING_INPUT',
      loopRetryAvailable: false,
      errors: [
        { id: 'budget', layer: 'TASK', code: 'TASK_BUDGET_WAITING_INPUT', message: '等待调整预算', retryable: true, occurredAt: 'later' },
        { id: 'old-stagnation', layer: 'VERIFICATION', code: 'LOOP_STAGNATION_DETECTED', message: '历史停滞', retryable: true, occurredAt: 'earlier' },
      ],
    }]
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-budget-wait')
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
          LayeredErrorPanel: true,
          SessionMonitorPanel: true,
          JudgeReviewCard: true,
          TaskAuditEvidencePanel: true,
          TaskPublicationActions: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.findAll('button').some((button) => button.text().includes('继续一轮'))).toBe(false)
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

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('原项目目录切换到该分支'), '新分支重做任务？', expect.any(Object))
    expect(store.reworkTask).toHaveBeenCalledWith('task-success')
    expect(router.currentRoute.value.path).toBe('/tasks/task-rework')
  })
})
