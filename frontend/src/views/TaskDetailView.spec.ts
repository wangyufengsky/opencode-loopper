import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TaskDetailView from '@/views/TaskDetailView.vue'

const apiMocks = vi.hoisted(() => ({
  getTaskQueue: vi.fn(),
  reconcileTaskQueue: vi.fn(),
}))

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
vi.mock('@/api/client', () => ({ api: apiMocks }))

const reviewTask = {
  id: 'task-review', projectId: 'project-1', title: '待评审任务', goal: '验证显式评审入口',
  status: 'WAITING_INPUT', branch: 'DIRECT', worktreePath: '/tmp/project', attemptCount: 1, maxAttempts: 3,
  cancellationAvailable: true,
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
    store.updateTask.mockClear()
    store.retryJudges.mockClear()
    store.retryWaitingLoop.mockClear()
    store.reworkTask.mockClear()
    apiMocks.getTaskQueue.mockReset()
    apiMocks.reconcileTaskQueue.mockReset()
    apiMocks.getTaskQueue.mockResolvedValue({
      taskId: 'task-queued', state: 'QUEUED', queuePosition: 1, leaseState: 'RELEASE_PENDING',
      holderTaskId: 'holder-1', holderTaskTitle: '已取消的旧任务', holderTaskState: 'CANCELLED', holderArchived: true,
      releaseReason: 'SOURCE_BRANCH_WORKSPACE_DIRTY', reconcileAvailable: true,
    })
  })

  it('offers a confirmed cancel action while the task is waiting for input', async () => {
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

    const action = wrapper.findAll('button').find((button) => button.text().includes('取消任务'))
    expect(action).toBeDefined()
    await action!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('保留执行目录和证据'), '取消当前任务？', expect.any(Object))
    expect(store.updateTask).toHaveBeenCalledWith('task-review', 'cancel')
  })

  it('cancels a queued task so it can become eligible for archive and deletion', async () => {
    store.tasks = [{
      ...reviewTask,
      id: 'task-queued', title: '排队任务', status: 'QUEUED', worktreePath: '', stages: [], judges: [],
    }]
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-queued')
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

    expect(wrapper.text()).toContain('排队状态')
    expect(wrapper.text()).toContain('已取消的旧任务')
    expect(wrapper.text()).toContain('工作区有未提交或未跟踪文件')
    const action = wrapper.findAll('button').find((button) => button.text().includes('取消任务'))
    expect(action).toBeDefined()
    await action!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('当前正在执行的任务和项目写租约不会受影响'),
      '取消排队任务？',
      expect.objectContaining({ cancelButtonText: '继续排队' }),
    )
    expect(store.updateTask).toHaveBeenCalledWith('task-queued', 'cancel')
  })

  it('starts or cancels a confirmed task before any workspace resource is allocated', async () => {
    store.tasks = [{
      ...reviewTask,
      id: 'task-pending', title: '待开始任务', status: 'PENDING_START', branch: '', worktreePath: '', stages: [], judges: [],
    }]
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-pending')
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

    expect(wrapper.text()).toContain('开始执行')
    expect(wrapper.text()).toContain('点击“开始执行”进入队列')
    expect(wrapper.text()).not.toContain('确认计划不会占用写租约或切换项目分支')
    const action = wrapper.findAll('button').find((button) => button.text().includes('取消任务'))
    expect(action).toBeDefined()
    await action!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('没有申请项目写租约、创建任务分支或切换工作区'),
      '取消待开始任务？',
      expect.objectContaining({ cancelButtonText: '保留待开始' }),
    )
    expect(store.updateTask).toHaveBeenCalledWith('task-pending', 'cancel')
  })

  it('manually reconciles a terminal holder and refreshes task and queue state', async () => {
    store.tasks = [{
      ...reviewTask,
      id: 'task-queued', title: '排队任务', status: 'QUEUED', worktreePath: '', stages: [], judges: [],
    }]
    apiMocks.reconcileTaskQueue.mockResolvedValue({
      taskId: 'task-queued', state: 'ADMITTED', leaseState: 'HELD', holderTaskId: 'task-queued', reconcileAvailable: false,
    })
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-queued')
    await router.isReady()

    const wrapper = mount(TaskDetailView, {
      global: {
        plugins: [router, ElementPlus],
        stubs: {
          Icon: true, PageHeader: { template: '<header><slot name="actions" /></header><slot />' },
          StatusBadge: true, StageRail: true, AttemptTimeline: true, LayeredErrorPanel: true,
          SessionMonitorPanel: true, JudgeReviewCard: true, TaskAuditEvidencePanel: true,
          TaskPublicationActions: true,
        },
      },
    })
    await flushPromises()

    const action = wrapper.findAll('button').find((button) => button.text().includes('重新检查并释放'))
    expect(action).toBeDefined()
    await action!.trigger('click')
    await flushPromises()

    expect(apiMocks.reconcileTaskQueue).toHaveBeenCalledWith('task-queued')
    expect(store.loadTask).toHaveBeenCalledWith('task-queued')
    expect(apiMocks.getTaskQueue).toHaveBeenCalled()
  })

  it('keeps the blocker visible when manual reconciliation returns a 409 reason', async () => {
    store.tasks = [{
      ...reviewTask,
      id: 'task-queued', title: '排队任务', status: 'QUEUED', worktreePath: '', stages: [], judges: [],
    }]
    apiMocks.getTaskQueue.mockResolvedValue({
      taskId: 'task-queued', state: 'QUEUED', queuePosition: 1, leaseState: 'RELEASE_PENDING',
      holderTaskId: 'holder-1', holderTaskTitle: '已取消的旧任务', holderTaskState: 'CANCELLED', holderArchived: true,
      releaseReason: 'SESSION_WRITER_UNCONFIRMED', reconcileAvailable: true,
    })
    apiMocks.reconcileTaskQueue.mockRejectedValue(new Error('当前写入 Session 尚未确认停止'))
    const confirmation = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-queued')
    await router.isReady()
    const wrapper = mount(TaskDetailView, {
      global: {
        plugins: [router, ElementPlus],
        stubs: {
          Icon: true, PageHeader: { template: '<header><slot name="actions" /></header><slot />' },
          StatusBadge: true, StageRail: true, AttemptTimeline: true, LayeredErrorPanel: true,
          SessionMonitorPanel: true, JudgeReviewCard: true, TaskAuditEvidencePanel: true,
          TaskPublicationActions: true,
        },
      },
    })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('终止遗留会话并释放'))!.trigger('click')
    await flushPromises()

    expect(confirmation).toHaveBeenCalledWith(
      expect.stringContaining('只有取得远端终止证明后才会释放租约'),
      '终止遗留会话并释放？',
      expect.objectContaining({ confirmButtonText: '终止并重新检查' }),
    )
    expect(apiMocks.reconcileTaskQueue).toHaveBeenCalledWith('task-queued')
    expect(wrapper.text()).toContain('当前写入 Session 尚未确认停止')
    expect(wrapper.text()).toContain('已取消的旧任务')
    expect(store.loadTask).toHaveBeenCalledTimes(2)
  })

  it('groups execution progress by work package and shows each independent attempt pool', async () => {
    store.tasks = [{
      ...reviewTask,
      id: 'task-packages', status: 'RUNNING', judges: [],
      workPackages: [
        { id: 'WP-1', status: 'SUCCEEDED', stageCount: 2, completedStages: 2, attemptCount: 2, attemptLimit: 4 },
        { id: 'WP-2', status: 'RUNNING', stageCount: 1, completedStages: 0, attemptCount: 1, attemptLimit: 3 },
      ],
    }]
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-packages')
    await router.isReady()
    const wrapper = mount(TaskDetailView, {
      global: {
        plugins: [router, ElementPlus],
        stubs: {
          Icon: true, PageHeader: { template: '<header><slot name="actions" /></header><slot />' },
          StatusBadge: true, StageRail: true, AttemptTimeline: true, LayeredErrorPanel: true,
          SessionMonitorPanel: true, JudgeReviewCard: true, TaskAuditEvidencePanel: true,
          TaskPublicationActions: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.get('#package-progress-heading').text()).toBe('执行进度')
    expect(wrapper.get('.package-progress-grid').text()).toContain('工作包 1')
    expect(wrapper.get('.package-progress-grid').text()).not.toContain('WP-1')
    expect(wrapper.get('.package-progress-grid').text()).toContain('2 / 4')
    expect(wrapper.get('.package-progress-grid').text()).toContain('工作包 2')
    expect(wrapper.get('.package-progress-grid').text()).toContain('1 / 3')
    expect(wrapper.text()).not.toContain('Requirement / Risk Judge')
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

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('两个新的只读评审会话'), expect.any(String), expect.any(Object))
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
    expect(wrapper.text()).toContain('循环已暂停，可确认后继续一轮')
    await action!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('新的可写会话'), '确认继续一轮？', expect.any(Object))
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

  it('hides the resolved dirty-workspace alert after execution preparation continues', async () => {
    store.tasks = [{
      ...reviewTask,
      id: 'task-dirty-resolved',
      status: 'RUNNING',
      branch: 'loopper/task-dirty-resolved',
      waitingReasonCode: undefined,
      judges: [],
      errors: [{
        id: 'dirty-history', layer: 'TASK', code: 'SOURCE_BRANCH_WORKSPACE_DIRTY',
        message: '历史未提交文件提示', retryable: true, occurredAt: 'earlier',
      }],
    }]
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-dirty-resolved')
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
          DirtyWorkspaceDialog: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.layered-error-stub').exists()).toBe(false)
  })

  it('keeps the dirty-workspace alert visible while that wait reason is current', async () => {
    store.tasks = [{
      ...reviewTask,
      id: 'task-dirty-current',
      status: 'WAITING_INPUT',
      waitingReasonCode: 'SOURCE_BRANCH_WORKSPACE_DIRTY',
      judges: [],
      errors: [{
        id: 'dirty-current', layer: 'TASK', code: 'SOURCE_BRANCH_WORKSPACE_DIRTY',
        message: '当前未提交文件提示', retryable: true, occurredAt: 'now',
      }],
    }]
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-dirty-current')
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
          DirtyWorkspaceDialog: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.layered-error-stub').text()).toBe('SOURCE_BRANCH_WORKSPACE_DIRTY')
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

  it('mounts publication actions after a successful result is confirmed completed', async () => {
    store.tasks = [{
      ...reviewTask,
      id: 'task-completed', title: '已确认完成任务', status: 'COMPLETED',
      executionResult: 'SUCCEEDED', branch: 'loopper/task-completed',
    }]
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tasks/:id', component: { template: '<div />' } }] })
    await router.push('/tasks/task-completed')
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
          TaskPublicationActions: { template: '<button data-test="publication-actions">创建合并请求</button>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.get('[data-test="publication-actions"]').text()).toBe('创建合并请求')
  })
})
