import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { demoProjects, demoRuntime, demoTasks } from '@/mock/demoData'
import { aiOutputNotice, reduceTaskEvent, requiresTaskSnapshot, useTaskStore } from '@/stores/taskStore'
import type { Task, TaskEvent } from '@/types/domain'
import { subscribeTaskEvents } from '@/api/client'

const apiMocks = vi.hoisted(() => ({
  createTaskRecovery: vi.fn(),
  startTask: vi.fn(),
  archiveTask: vi.fn(),
  deleteArchivedTask: vi.fn(),
  getProjects: vi.fn(),
  getTasks: vi.fn(),
  getTask: vi.fn(),
  getTaskOverview: vi.fn(),
  getRuntime: vi.fn(),
  getTaskSummaries: vi.fn(),
  getTaskAudit: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  api: apiMocks,
  ApiError: class ApiError extends Error {},
  subscribeTaskEvents: vi.fn(),
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
afterEach(() => vi.useRealTimers())

describe('task SSE reducer', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })
  it('updates task state from a persisted status event', () => {
    const next = reduceTaskEvent(demoTasks[0]!, { id: 'evt-1', type: 'task.status', at: '2026-08-04T10:20:00+08:00', data: { status: 'VERIFYING' } })
    expect(next.status).toBe('VERIFYING')
    expect(next.updatedAt).toBe('2026-08-04T10:20:00+08:00')
    expect(demoTasks[0]!.status).toBe('RUNNING')
  })

  it('ignores a malformed status without corrupting current state', () => {
    const next = reduceTaskEvent(demoTasks[0]!, { id: 'evt-2', type: 'task.status', at: '2026-08-04T10:20:00+08:00', data: { status: 'NOT_A_STATUS' } })
    expect(next.status).toBe('RUNNING')
  })

  it('keeps the Task running when a session error is recorded', () => {
    const next = reduceTaskEvent(demoTasks[0]!, { id: 'evt-3', type: 'error', at: '2026-08-04T10:21:00+08:00', data: { layer: 'SESSION', code: 'SSE_DISCONNECTED' } })
    expect(next.status).toBe('RUNNING')
  })

  it('only moves to FAILED when a terminal task state is published', () => {
    const next = reduceTaskEvent(demoTasks[0]!, { id: 'evt-4', type: 'task.status', at: '2026-08-04T10:22:00+08:00', data: { status: 'FAILED', layer: 'TASK' } })
    expect(next.status).toBe('FAILED')
  })

  it('accepts the persisted backend event form with a state field', () => {
    const next = reduceTaskEvent(demoTasks[0]!, { id: 'evt-5', type: 'task.status', at: '2026-08-04T10:23:00+08:00', data: { state: 'PAUSED' } })
    expect(next.status).toBe('PAUSED')
  })

  it('refreshes compact persisted lifecycle events but does not poll log noise', () => {
    expect(requiresTaskSnapshot('session.failed')).toBe(true)
    expect(requiresTaskSnapshot('verification.failed')).toBe(true)
    expect(requiresTaskSnapshot('task.status')).toBe(true)
    expect(requiresTaskSnapshot('package.design_review_required')).toBe(true)
    expect(requiresTaskSnapshot('log.appended')).toBe(false)
  })

  it('invalidates the rolling workbench projection for package lifecycle events', () => {
    const next = reduceTaskEvent(demoTasks[0]!, {
      id: 'evt-package', type: 'package.design_review_required',
      at: '2026-08-04T10:24:00+08:00', data: { packageKey: 'WP-2' },
    })

    expect(next.updatedAt).toBe('2026-08-04T10:24:00+08:00')
  })

  it('renders normalization and finalizer events as ordinary informational notices', () => {
    expect(aiOutputNotice({ id: 'normalized', type: 'AI_OUTPUT_NORMALIZED', at: 'now',
      data: { role: 'RISK', corrections: ['WRAPPER_TOLERATED'] } }))
      .toBe('风险评审员 输出已自动规范化：已兼容常见外层格式')
    expect(aiOutputNotice({ id: 'finalizer', type: 'AI_TOOL_LOOP_FINALIZER_STARTED', at: 'now',
      data: { role: 'REQUIREMENT' } }))
      .toContain('MCP-only 收口会话')
  })

  it('creates the rework child before starting that new task', async () => {
    const parent = { ...demoTasks[0]!, id: 'parent-rework', status: 'SUCCEEDED' as const, branch: 'loopper/parent-rework' }
    const child = { ...parent, id: 'child-rework', title: `${parent.title} · 重做`, status: 'RUNNING' as const, branch: 'loopper/child-rework' }
    apiMocks.createTaskRecovery.mockResolvedValue({
      taskId: child.id, parentTaskId: parent.id, mode: 'REWORK_ALL_STAGES', workspaceFingerprint: 'baseline', writableSession: true,
    })
    apiMocks.startTask.mockResolvedValue(child)
    const store = useTaskStore()
    store.usingDemo = false
    store.tasks = [parent]

    await expect(store.reworkTask(parent.id)).resolves.toBe(child.id)

    expect(apiMocks.createTaskRecovery).toHaveBeenCalledWith(parent.id, 'REWORK_ALL_STAGES')
    expect(apiMocks.startTask).toHaveBeenCalledWith(child.id)
    expect(store.tasks).toContainEqual(child)
  })

  it('deduplicates normalization notices across reopen and SSE replay, independently per task', () => {
    const store = useTaskStore()
    store.usingDemo = false
    vi.mocked(subscribeTaskEvents).mockImplementation((_id, receive) => {
      receive({ id: 'same-event', type: 'AI_OUTPUT_NORMALIZED', at: 'now', data: { role: 'REQUIREMENT', corrections: ['WRAPPER_TOLERATED'] } })
      return { close: vi.fn() }
    })
    store.watchTask('one'); store.stopWatching(); store.watchTask('one'); store.watchTask('two')
    expect(store.taskNotices.one).toHaveLength(1)
    expect(store.taskNotices.two).toHaveLength(1)
    expect(store.taskNotices.one?.[0]).toContain('已兼容常见外层格式')
    store.stopWatching()
  })

  it('removes an archived task and its loaded artifacts after backend deletion', async () => {
    const archived = { ...demoTasks[0]!, id: 'archived-task', status: 'CANCELLED' as const, archived: true }
    const store = useTaskStore()
    store.usingDemo = false
    store.tasks = [archived]
    store.artifacts = [{ id: 'artifact-1', taskId: archived.id, kind: 'LOG', title: 'log', createdAt: 'now', content: 'evidence' }]
    apiMocks.deleteArchivedTask.mockResolvedValue(undefined)

    await store.deleteArchivedTask(archived.id)

    expect(apiMocks.deleteArchivedTask).toHaveBeenCalledWith(archived.id)
    expect(store.tasks).toEqual([])
    expect(store.artifacts).toEqual([])
  })

  it('keeps an active lease holder visible when the backend rejects archive', async () => {
    const holder = { ...demoTasks[0]!, id: 'active-holder', status: 'CANCELLED' as const, archived: false }
    const store = useTaskStore()
    store.usingDemo = false
    store.tasks = [holder]
    apiMocks.archiveTask.mockRejectedValue(new Error('工作区有未提交文件，释放完成前不能归档'))

    await expect(store.setTaskArchived(holder.id, true)).rejects.toThrow('释放完成前不能归档')

    expect(store.tasks).toEqual([holder])
    expect(store.error).toContain('工作区有未提交文件')
  })

  it('falls back to the complete task endpoint when an overview capability contract is incomplete', async () => {
    const queued = { ...demoTasks[0]!, id: 'queued-task', status: 'QUEUED' as const, cancellationAvailable: true }
    apiMocks.getTaskOverview.mockRejectedValue(new TypeError('TaskOverview.cancellationAvailable must be boolean'))
    apiMocks.getTask.mockResolvedValue(queued)
    const store = useTaskStore()
    store.usingDemo = false

    await expect(store.loadTask(queued.id)).resolves.toEqual(queued)

    expect(apiMocks.getTaskOverview).toHaveBeenCalledWith(queued.id)
    expect(apiMocks.getTask).toHaveBeenCalledWith(queued.id)
    expect(store.tasks).toContainEqual(queued)
  })

  it('exits demo mode and reloads authoritative backend data', async () => {
    const realProject = { ...demoProjects[0]!, id: 'real-project', name: '真实项目' }
    const realTask = { ...demoTasks[0]!, id: 'real-task', projectId: realProject.id, title: '真实任务' }
    const realRuntime = { ...demoRuntime, pid: 9001, endpoint: '127.0.0.1:4096' }
    apiMocks.getProjects.mockResolvedValue([realProject])
    apiMocks.getTasks.mockResolvedValue([realTask])
    apiMocks.getRuntime.mockResolvedValue(realRuntime)
    const store = useTaskStore()
    store.error = '旧错误'

    store.activateDemo()
    expect(store.usingDemo).toBe(true)
    expect(store.error).toBeUndefined()

    await store.deactivateDemo()

    expect(store.usingDemo).toBe(false)
    expect(store.projects).toEqual([realProject])
    expect(store.tasks).toEqual([realTask])
    expect(store.runtime).toEqual(realRuntime)
    expect(store.artifacts).toEqual([])
    expect(apiMocks.getProjects).toHaveBeenCalledOnce()
    expect(apiMocks.getTasks).toHaveBeenCalledOnce()
    expect(apiMocks.getRuntime).toHaveBeenCalledOnce()
  })
  it('keeps the newest query, facets and cursor when responses arrive out of order', async () => {
    const store = useTaskStore()
    const old = deferred<{ items: Task[]; nextCursor: string; facets: Record<string, number> }>()
    const fresh = deferred<{ items: Task[]; nextCursor: string; facets: Record<string, number> }>()
    apiMocks.getTaskSummaries.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    const first = store.loadTaskSummaries({ q: 'old' })
    const second = store.loadTaskSummaries({ q: 'new' })
    fresh.resolve({ items: [{ ...demoTasks[0]!, id: 'new' }], nextCursor: 'new-cursor', facets: { TOTAL: 1 } })
    await second
    old.resolve({ items: [{ ...demoTasks[0]!, id: 'old' }], nextCursor: 'old-cursor', facets: { TOTAL: 9 } })
    await first
    expect(store.tasks.map(task => task.id)).toEqual(['new'])
    expect(store.taskNextCursor).toBe('new-cursor')
    expect(store.taskFacets).toEqual({ TOTAL: 1 })
  })

  it('invalidates an old page before the debounced replacement is issued', async () => {
    const store = useTaskStore()
    apiMocks.getTaskSummaries.mockResolvedValueOnce({ items: [], nextCursor: 'old-page', facets: {} })
    await store.loadTaskSummaries({ q: 'old' })
    const pending = deferred<{ items: Task[]; facets: Record<string, number> }>()
    apiMocks.getTaskSummaries.mockReturnValueOnce(pending.promise)
    const append = store.loadTaskSummaries({ q: 'old' }, true)
    store.invalidateTaskSummaries()
    pending.resolve({ items: [demoTasks[0]!], facets: { TOTAL: 9 } })
    await append
    await store.loadTaskSummaries({ q: 'new' }, true)
    expect(store.tasks).toEqual([])
    expect(store.taskNextCursor).toBeUndefined()
    expect(apiMocks.getTaskSummaries).toHaveBeenCalledTimes(2)
  })

  it('rejects older overview requests and lower server versions and clears authoritative empty history', async () => {
    const store = useTaskStore()
    const old = deferred<Task>(), fresh = deferred<Task>()
    apiMocks.getTaskOverview.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    const first = store.loadTaskOverview('same'), second = store.loadTaskOverview('same')
    fresh.resolve({ ...demoTasks[0]!, id: 'same', status: 'COMPLETED', version: 2, errors: [], judges: [] })
    await second
    old.resolve({ ...demoTasks[0]!, id: 'same', status: 'RUNNING', version: 1 })
    await first
    apiMocks.getTaskOverview.mockResolvedValueOnce({ ...demoTasks[0]!, id: 'same', status: 'RUNNING', version: 1 })
    await store.loadTaskOverview('same')
    expect(store.tasks[0]).toMatchObject({ status: 'COMPLETED', version: 2, errors: [], judges: [] })
  })

  it('keeps audit from the latest request and ignores late errors', async () => {
    const store = useTaskStore()
    store.tasks = [{ ...demoTasks[0]!, id: 'same' }]
    const old = deferred<unknown>()
    apiMocks.getTaskAudit.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ artifacts: [], attempts: [], errors: [], judges: [] })
    const first = store.loadTaskAudit('same')
    await store.loadTaskAudit('same')
    old.reject(new Error('stale failure'))
    await first
    expect(store.auditErrors.same).toBeUndefined()
    expect(store.auditLoading.same).toBe(false)
    expect(store.tasks[0]?.attempts).toEqual([])
  })

  it('restarts both SSE timers after switching and rejects callbacks from the closed stream', async () => {
    vi.useFakeTimers()
    const receivers: Array<(event: TaskEvent) => void> = []
    vi.mocked(subscribeTaskEvents).mockImplementation((_id, receive) => {
      receivers.push(receive)
      return { close: vi.fn() }
    })
    apiMocks.getTaskOverview.mockResolvedValue({ ...demoTasks[0]!, id: 'two' })
    apiMocks.getTaskAudit.mockResolvedValue({ artifacts: [] })
    const store = useTaskStore()
    store.watchTask('one')
    const event = { id: 'evt', type: 'session.failed', at: 'now', data: {} }
    receivers[0]!(event)
    store.watchTask('two')
    receivers[0]!(event)
    receivers[1]!(event)
    await vi.advanceTimersByTimeAsync(180)
    expect(apiMocks.getTaskOverview).toHaveBeenCalledExactlyOnceWith('two')
    expect(apiMocks.getTaskAudit).toHaveBeenCalledExactlyOnceWith('two')
    store.stopWatching()
    receivers[1]!(event)
    await vi.advanceTimersByTimeAsync(200)
    expect(apiMocks.getTaskOverview).toHaveBeenCalledTimes(1)
  })

  it('reports SSE refresh failure and accepts a later recovery event', async () => {
    vi.useFakeTimers()
    let receive!: (event: TaskEvent) => void
    vi.mocked(subscribeTaskEvents).mockImplementation((_id, callback) => { receive = callback; return { close: vi.fn() } })
    apiMocks.getTaskOverview.mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce({ ...demoTasks[0]!, id: 'same' })
    const store = useTaskStore()
    store.watchTask('same')
    const event = { id: 'evt', type: 'task.status', at: 'now', data: {} }
    receive(event)
    await vi.advanceTimersByTimeAsync(180)
    expect(store.error).toBe('读取失败')
    receive(event)
    await vi.advanceTimersByTimeAsync(180)
    expect(store.tasks[0]?.id).toBe('same')
    store.stopWatching()
  })

})
