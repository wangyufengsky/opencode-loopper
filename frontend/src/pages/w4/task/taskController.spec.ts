import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import type { Task, TaskEvent, TaskQueueStatus } from '@/types/domain'
import { createTaskDetailController } from './taskController'
import { taskFacts, currentTaskErrors, taskSessionAndVerifierErrors, templatePhaseLabel, taskNextAction } from './projections'
import { createW4Owner } from '../shared/core'
import { deferred, flush, mockReads, taskFixture, pageProps } from './test-support'

const streams = vi.hoisted(() => ({ subscribe: vi.fn() }))
vi.mock('@/api/client', async original => ({ ...await original<typeof import('@/api/client')>(), subscribeTaskEvents: streams.subscribe }))
const owners: ReturnType<typeof createTaskDetailController>[] = []
let event: (value: TaskEvent) => void, closed: ReturnType<typeof vi.fn>
beforeEach(() => { vi.useFakeTimers(); mockReads(); closed = vi.fn(); streams.subscribe.mockReset().mockImplementation((_id, receive) => { event = receive; return { close: closed } }) })
afterEach(() => { for (const owner of owners.splice(0)) owner.retire(true); vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks() })
async function mounted(task = taskFixture()) { vi.mocked(api.getTaskOverview).mockResolvedValue(task); const owner = createTaskDetailController(task.id); owners.push(owner); const release = owner.attachView(); await flush(); return { owner, release } }

describe('Task authoritative read and command owner', () => {
  it('failed awaiting decision never claims that execution or new sessions will continue', () => {
    expect(taskNextAction(taskFixture('A',{status:'AWAITING_DECISION',executionResult:'FAILED'}),Date.now())).toBe('任务已终止，不会再创建新会话')
    expect(taskNextAction(taskFixture('A',{status:'RUNNING'}),Date.now())).toBe('系统按服务端状态继续执行。')
  })
  it('does not synthesize lifecycle from SSE and coalesces only real overview/audit reads', async () => {
    const { owner } = await mounted(taskFixture('A', { status: 'RUNNING' }))
    event({ id: 'one', type: 'task.status', at: 'now', data: { status: 'COMPLETED' } }); event({ id: 'two', type: 'attempt.created', at: 'now', data: {} })
    expect(owner.getSnapshot().task!.status).toBe('RUNNING'); expect(api.getTaskOverview).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(180); expect(api.getTaskOverview).toHaveBeenCalledTimes(2); expect(api.getTaskAudit).toHaveBeenCalledTimes(2)
    expect(streams.subscribe).toHaveBeenCalledTimes(1)
  })
  it('last lease clears stream, clock and both pending event timers immediately; late callbacks cannot write', async () => {
    const { owner, release } = await mounted(); event({ id: 'one', type: 'attempt.created', at: 'now', data: {} })
    expect(vi.getTimerCount()).toBe(3); release(); const before = owner.getSnapshot()
    expect(closed).toHaveBeenCalledTimes(1); expect(vi.getTimerCount()).toBe(0)
    event({ id: 'late', type: 'task.status', at: 'now', data: { status: 'COMPLETED' } }); await vi.advanceTimersByTimeAsync(5000)
    expect(owner.getSnapshot()).toBe(before); expect(api.getTaskOverview).toHaveBeenCalledTimes(1)
  })
  it('StrictMode-style detach/remount starts one replacement read lease without any write', async () => {
    const { owner, release } = await mounted(); release(); const next = owner.attachView(); await flush()
    expect(streams.subscribe).toHaveBeenCalledTimes(2); expect(owner.viewCount()).toBe(1); expect(owner.getSnapshot().command.phase).toBe('IDLE')
    next(); expect(closed).toHaveBeenCalledTimes(2); expect(vi.getTimerCount()).toBe(0)
  })
  it('retired overview continuation starts no audit or queue GET after the original read lease ends', async () => {
    const { owner } = await mounted(), pending = deferred<Task>()
    vi.mocked(api.getTaskOverview).mockReturnValueOnce(pending.promise); const auditCalls = vi.mocked(api.getTaskAudit).mock.calls.length
    const queueRead = vi.spyOn(api, 'getTaskQueue'), reading = owner.refresh(); owner.retire(true); const snapshot = owner.getSnapshot()
    pending.resolve(taskFixture('A', { status: 'QUEUED' })); await reading
    expect(owner.getSnapshot()).toBe(snapshot); expect(api.getTaskAudit).toHaveBeenCalledTimes(auditCalls); expect(queueRead).not.toHaveBeenCalled()
  })
  it('an SSE overview resolving after last detach cannot issue an old QUEUED Task queue read', async () => {
    const queued = taskFixture('A', { status: 'QUEUED' }), queueRead = vi.spyOn(api, 'getTaskQueue').mockResolvedValue({ taskId: 'A', state: 'QUEUED', leaseState: 'HELD', reconcileAvailable: false })
    const { owner, release } = await mounted(queued), before = queueRead.mock.calls.length, pending = deferred<Task>()
    vi.mocked(api.getTaskOverview).mockReturnValueOnce(pending.promise)
    event({ id: 'queued-update', type: 'task.status', at: 'now', data: { status: 'QUEUED' } }); await vi.advanceTimersByTimeAsync(180)
    expect(api.getTaskOverview).toHaveBeenCalledTimes(2); release(); const snapshot = owner.getSnapshot()
    pending.resolve(queued); await flush(); expect(queueRead).toHaveBeenCalledTimes(before); expect(owner.getSnapshot()).toBe(snapshot); expect(vi.getTimerCount()).toBe(0)
  })
  it('rejects foreign and stale Task DTOs without unlocking accepted readback', async () => {
    const { owner } = await mounted(); vi.spyOn(api, 'startTask').mockResolvedValue(taskFixture('A', { status: 'QUEUED', version: 4 }))
    vi.mocked(api.getTaskOverview).mockResolvedValue(taskFixture('B', { status: 'QUEUED', version: 4 })); owner.request('start'); await flush()
    expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.canLeave().kind).toBe('BLOCK'); expect(owner.getSnapshot().task!.id).toBe('A')
    vi.mocked(api.getTaskOverview).mockResolvedValue(taskFixture('A', { status: 'PENDING_START', version: 3 })); await owner.recover()
    expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(api.startTask).toHaveBeenCalledTimes(1)
  })
  it('a later version wins over out-of-order read while audit never restores historical current errors', async () => {
    const { owner } = await mounted(), late = deferred<Task>(); vi.mocked(api.getTaskOverview).mockReturnValueOnce(late.promise).mockResolvedValue(taskFixture('A', { status: 'RUNNING', version: 8 }))
    const oldRead = owner.overview(); await owner.overview(); late.resolve(taskFixture('A', { status: 'FAILED', version: 4 })); await oldRead
    expect(owner.getSnapshot().task!.status).toBe('RUNNING'); await owner.audit(); expect(owner.getSnapshot().task!.status).toBe('RUNNING')
  })
  it('SENDING and UNKNOWN hold the original keyless start; explicit recovery only GETs the original task', async () => {
    const { owner } = await mounted(), pending = deferred<Task>(), start = vi.spyOn(api, 'startTask').mockReturnValue(pending.promise)
    owner.request('start'); await flush(); expect(owner.getSnapshot().command.phase).toBe('SENDING'); expect(owner.canLeave().kind).toBe('BLOCK')
    const original = owner.operation()!.identity; pending.reject(new Error('失去回执')); await flush()
    expect(owner.getSnapshot().command.phase).toBe('UNKNOWN'); owner.request('start'); await owner.recover()
    expect(start).toHaveBeenCalledTimes(1); expect(owner.operation()!.identity).toBe(original); expect(owner.getSnapshot().command.phase).toBe('UNKNOWN')
    vi.mocked(api.getTaskOverview).mockResolvedValue(taskFixture('A', { status: 'RUNNING', version: 4 })); await owner.recover()
    expect(owner.getSnapshot().command.phase).toBe('SETTLED'); expect(start).toHaveBeenCalledTimes(1)
  })
  it('a 409 remains unknown and never restarts under a newer projection', async () => {
    const { owner } = await mounted(), start = vi.spyOn(api, 'startTask').mockRejectedValue(new ApiError('需要核对', 409)); owner.request('start'); await flush()
    vi.mocked(api.getTaskOverview).mockResolvedValue(taskFixture('A', { status: 'PENDING_START', version: 9 })); await owner.refresh(); owner.request('start')
    expect(owner.canLeave().kind).toBe('BLOCK'); expect(start).toHaveBeenCalledTimes(1)
  })
  it('confirmation rechecks authoritative version and task permission before cancelling', async () => {
    const { owner } = await mounted(taskFixture('A', { status: 'WAITING_INPUT' })), cancel = vi.spyOn(api, 'cancelTask').mockResolvedValue(taskFixture('A', { status: 'STOPPING', version: 4 }))
    owner.request('cancel'); vi.mocked(api.getTaskOverview).mockResolvedValue(taskFixture('A', { status: 'RUNNING', version: 4 })); await owner.overview(); owner.confirm(); await flush()
    expect(cancel).not.toHaveBeenCalled(); owner.cancelConfirmation(); owner.request('cancel'); vi.mocked(api.getTaskOverview).mockResolvedValue(taskFixture('A', { status: 'STOPPING', version: 5 })); owner.confirm(); await flush()
    expect(cancel).toHaveBeenCalledTimes(1); expect(owner.getSnapshot().task!.status).toBe('STOPPING')
  })
  it('sibling dirty blocks the parent while only the submitting child may ignore its own dirty state', async () => {
    const { owner } = await mounted(), child = { canLeave: () => ({ kind: 'CONFIRM_DISCARD' as const, description: '草稿', draftRevision: 1 }), retire: vi.fn() }
    const release = owner.parent.registerChild(child); expect(owner.parent.canStartWrite()).toBe(false); expect(owner.parent.canStartWrite(child)).toBe(true)
    release(); expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD'); expect(owner.parent.canStartWrite()).toBe(false)
    owner.retire(true); expect(child.retire).toHaveBeenCalledWith(true)
  })
  it('propagates only an exact child accepted handoff while every unresolved sibling still blocks', async () => {
    const { owner } = await mounted(), child = createW4Owner('decision', 'A', {})
    const operation = child.command({ label: '创建派生任务', input: { endpoint: '/api/tasks/A/decision', method: 'POST', body: { decision: 'CONTINUE' } }, capability: { kind: 'NONE' },
      write: async () => ({ taskId: 'child' }), read: async () => { throw new Error('接受后读取失败') }, changed: () => {}, handoffTarget: receipt => `/tasks/${receipt.taskId}` })
    owner.parent.registerChild(child.base); await expect(operation.execute()).rejects.toThrow('接受后读取失败')
    const handoff = operation.prepareHandoff(), request = { destination: '/tasks/child', handoff }
    expect(owner.canLeave().kind).toBe('BLOCK'); expect(owner.canLeave(request).kind).toBe('ALLOW')
    expect(owner.canLeave({ ...request, destination: '/tasks/foreign' }).kind).toBe('BLOCK')
    const sibling = createW4Owner('sibling', 'A', {}), pending = deferred<{}>()
    const unknown = sibling.command({ label: '未决写入', input: { endpoint: '/api/tasks/A/pause', method: 'POST', body: {} }, capability: { kind: 'NONE' }, write: () => pending.promise, read: async () => {}, changed: () => {} })
    owner.parent.registerChild(sibling.base); const sending = unknown.execute(); expect(owner.canLeave(request).kind).toBe('BLOCK')
    pending.reject(new Error('未知')); await expect(sending).rejects.toThrow('未知'); expect(owner.canLeave(request).kind).toBe('BLOCK')
  })
  it('reconcile accepted receipt survives a failed read without another POST', async () => {
    const queued = taskFixture('A', { status: 'QUEUED' }), q: TaskQueueStatus = { taskId: 'A', state: 'QUEUED', leaseState: 'RELEASE_PENDING', reconcileAvailable: true }
    vi.spyOn(api, 'getTaskQueue').mockResolvedValue(q); const { owner } = await mounted(queued), reconcile = vi.spyOn(api, 'reconcileTaskQueue').mockResolvedValue({ ...q, leaseState: 'RELEASED' })
    vi.mocked(api.getTaskOverview).mockRejectedValue(new Error('读取断开')); owner.request('reconcile'); await flush()
    expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.canLeave().kind).toBe('BLOCK'); await owner.recover(); expect(reconcile).toHaveBeenCalledTimes(1)
  })
  it('rework retains known child after start rejection, uses exact child GET, and never creates again', async () => {
    const navigation = pageProps('/tasks/A').navigation, task = taskFixture('A', { status: 'FAILED' }); vi.mocked(api.getTaskOverview).mockImplementation(async id => id === 'A' ? task : taskFixture('child'))
    const owner = createTaskDetailController('A', navigation); owners.push(owner); owner.attachView(); await flush()
    const create = vi.spyOn(api, 'createTaskRecovery').mockResolvedValue({ taskId: 'child', parentTaskId: 'A', mode: 'REWORK_ALL_STAGES', workspaceFingerprint: 'original', writableSession: true }), start = vi.spyOn(api, 'startTask').mockRejectedValue(new Error('回执未知'))
    owner.request('rework'); owner.confirm(); await flush(); await flush(); expect(create).toHaveBeenCalledWith('A', 'REWORK_ALL_STAGES'); expect(start).toHaveBeenCalledWith('child'); expect(owner.canLeave().kind).toBe('BLOCK')
    await owner.recover(); expect(start).toHaveBeenCalledTimes(1); expect(create).toHaveBeenCalledTimes(1); expect(navigation.goAccepted).not.toHaveBeenCalled()
    vi.mocked(api.getTaskOverview).mockImplementation(async id => id === 'A' ? task : taskFixture('child', { status: 'RUNNING', version: 4 })); await owner.recover()
    expect(navigation.goAccepted).toHaveBeenCalledWith('/tasks/child', expect.objectContaining({ destination: '/tasks/child' })); expect(start).toHaveBeenCalledTimes(1)
  })
  it('lost rework create has no invented lineage lookup or replacement creation', async () => {
    const { owner } = await mounted(taskFixture('A', { status: 'FAILED' })), create = vi.spyOn(api, 'createTaskRecovery').mockRejectedValue(new Error('未知'))
    owner.request('rework'); owner.confirm(); await flush(); await owner.recover(); owner.request('rework')
    expect(create).toHaveBeenCalledTimes(1); expect(owner.getSnapshot().command.recovery).toBe('BLOCKED'); expect(owner.canLeave().kind).toBe('BLOCK')
  })
  it('current task errors follow exact waiting reason and disappear after execution continues', () => {
    const errors = [{ id: 'old', layer: 'TASK' as const, code: 'OLD', message: '旧故障', occurredAt: '2026-10-01', retryable: false }, { id: 'current', layer: 'TASK' as const, code: 'SOURCE_BRANCH_WORKSPACE_DIRTY', message: '当前文件', occurredAt: '2026-10-02', retryable: false }]
    expect(currentTaskErrors(taskFixture('A', { status: 'WAITING_INPUT', waitingReasonCode: 'SOURCE_BRANCH_WORKSPACE_DIRTY', errors }))).toEqual([errors[1]])
    expect(currentTaskErrors(taskFixture('A', { status: 'RUNNING', errors }))).toEqual([])
    expect(currentTaskErrors(taskFixture('A', { status: 'AWAITING_DECISION', executionResult: 'FAILED', errors }))).toEqual([errors[1]])
    expect(taskFacts(taskFixture('A', { executionMode: 'TEMPLATE_REPORT', templateProgress: { dualReviewRequired: false, reviewBatches: 0, contributorBatches: 0, completedReviews: 0, completedContributors: 0, activeBatches: 0, failedBatches: 0, repairRound: 0, documentPath: null } })).dual).toBe(false)
    const conflict = { ...errors[0]!, layer: 'VERIFICATION' as const, code: 'JUDGE_CONFLICT' }, waiting = taskFixture('A', { status: 'WAITING_INPUT', errors: [conflict] })
    expect(taskSessionAndVerifierErrors(waiting)).toEqual([conflict]); expect(taskSessionAndVerifierErrors({ ...waiting, status: 'RUNNING' })).toEqual([])
    const report = taskFixture('A', { status: 'AWAITING_DECISION', templateProgress: { dualReviewRequired: false, reviewBatches: 1, contributorBatches: 0, completedReviews: 1, completedContributors: 0, activeBatches: 0, failedBatches: 0, repairRound: 0, documentPath: null } })
    expect(templatePhaseLabel(report)).toBe('完成收尾'); expect(templatePhaseLabel({ ...report, status: 'STOPPING' })).toBe('正在停止')
  })
})
