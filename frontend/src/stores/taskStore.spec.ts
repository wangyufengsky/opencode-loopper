import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { demoProjects, demoRuntime, demoTasks } from '@/mock/demoData'
import { aiOutputNotice, reduceTaskEvent, requiresTaskSnapshot, useTaskStore } from '@/stores/taskStore'

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
}))

vi.mock('@/api/client', () => ({
  api: apiMocks,
  ApiError: class ApiError extends Error {},
  subscribeTaskEvents: vi.fn(),
}))

describe('task SSE reducer', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
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
    expect(requiresTaskSnapshot('log.appended')).toBe(false)
  })

  it('renders normalization and finalizer events as ordinary informational notices', () => {
    expect(aiOutputNotice({ id: 'normalized', type: 'AI_OUTPUT_NORMALIZED', at: 'now',
      data: { role: 'RISK', corrections: ['WRAPPER_TOLERATED'] } }))
      .toBe('RISK 输出已自动规范化：WRAPPER_TOLERATED')
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
})
