import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { createSessionMonitorController } from './sessionController'
import { activityFixture, sessionFixture, deferred, flush } from './test-support'
import type { TaskSessionActivity } from '@/types/domain'
const owners: ReturnType<typeof createSessionMonitorController>[] = []
const question = { id: 'question-original', questions: [{ header: '执行范围', question: '如何继续？', options: [{ label: '原范围', description: '保留范围' }], multiple: false, custom: true }] }
beforeEach(() => { vi.useFakeTimers(); vi.spyOn(api, 'getTaskSessions').mockResolvedValue([sessionFixture()]); vi.spyOn(api, 'getTaskSessionActivity').mockResolvedValue(activityFixture(undefined, { pendingQuestions: [question] })) })
afterEach(() => { for (const owner of owners.splice(0)) owner.retire(true); vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks() })
async function mounted() { const owner = createSessionMonitorController('A'); owners.push(owner); const release = owner.attachView(); await flush(); return { owner, release } }
it('polls active activity at 1200ms and terminal snapshots at 3000ms, and immediately releases timers', async () => {
  const { owner, release } = await mounted(); expect(api.getTaskSessionActivity).toHaveBeenCalledTimes(1); await vi.advanceTimersByTimeAsync(1200); expect(api.getTaskSessionActivity).toHaveBeenCalledTimes(2)
  vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture(undefined, { remoteState: 'completed' })); await owner.load(); await vi.advanceTimersByTimeAsync(1200); expect(api.getTaskSessionActivity).toHaveBeenCalledTimes(3)
  release(); expect(vi.getTimerCount()).toBe(0)
})
it('keeps original answers and frozen question across external disappearance and rejects changed-draft confirmation', async () => {
  const { owner } = await mounted(); owner.choose(question.id, 0, '原范围'); const revision = owner.getSnapshot().draftRevision
  vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture()); await owner.load()
  expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD'); expect(owner.getSnapshot().draftQuestions[question.id]).toEqual(question); expect(owner.canSubmit(question.id)).toBe(false)
  owner.customAnswer(question.id, 0, '后来的草稿'); expect(owner.discard(revision)).toBe(false); expect(owner.getSnapshot().custom[question.id]![0]).toBe('后来的草稿')
})
it('SENDING/UNKNOWN locks exact question/session/body; disappearing question is not invented acceptance', async () => {
  const { owner } = await mounted(), pending = deferred<void>(), write = vi.spyOn(api, 'replyTaskSessionQuestion').mockReturnValue(pending.promise)
  owner.choose(question.id, 0, '原范围'); const sending = owner.submit(question.id); await flush(); expect(write).toHaveBeenCalledWith('A', 'execution:local-1', question.id, [['原范围']]); expect(owner.canLeave().kind).toBe('BLOCK')
  owner.customAnswer(question.id, 0, '不能改'); expect(owner.getSnapshot().custom[question.id]?.[0]).toBe(''); pending.reject(new Error('未知回执')); await sending
  vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture()); await owner.recover(); await owner.submit(question.id)
  expect(write).toHaveBeenCalledTimes(1); expect(owner.getSnapshot().command.phase).toBe('UNKNOWN'); expect(owner.getSnapshot().dirty).toBe(true)
})
it('fulfilled answer with failed read stays accepted and recovery reads only exact original Session', async () => {
  const { owner } = await mounted(), write = vi.spyOn(api, 'replyTaskSessionQuestion').mockResolvedValue(undefined); owner.choose(question.id, 0, '原范围')
  vi.mocked(api.getTaskSessionActivity).mockRejectedValue(new Error('读取断开')); await owner.submit(question.id)
  expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.canLeave().kind).toBe('BLOCK')
  vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture()); await owner.recover(); expect(write).toHaveBeenCalledTimes(1); expect(owner.getSnapshot().command.phase).toBe('SETTLED'); expect(owner.getSnapshot().dirty).toBe(false)
})
it('scope retires before a delayed activity arrives, preserving the final snapshot and zero timers', async () => {
  const late = deferred<TaskSessionActivity>(); vi.mocked(api.getTaskSessionActivity).mockReturnValue(late.promise); const { owner, release } = await mounted(); release(); owner.retire(true); const before = owner.getSnapshot()
  late.resolve(activityFixture(undefined, { parts: [{ id: 'late', type: 'OUTPUT', label: '输出', content: '迟到内容' }] })); await flush(); expect(owner.getSnapshot()).toBe(before); expect(vi.getTimerCount()).toBe(0)
})
it('foreign activity never changes selected Session or issues a question write', async () => {
  const { owner } = await mounted(); vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture('foreign')); await owner.load()
  expect(owner.getSnapshot().selected).toBe('execution:local-1'); expect(owner.getSnapshot().error).toContain('原会话')
})
it('role reads are lazy, scope-specific and visibly retryable', async () => {
  const { owner } = await mounted(), role = vi.spyOn(api, 'getTaskSessionRole').mockRejectedValue(new Error('不能读取角色'))
  expect(role).not.toHaveBeenCalled(); await owner.toggleRole(); expect(role).toHaveBeenCalledWith('A', 'execution:local-1'); expect(owner.getSnapshot().roleError).toBeTruthy()
  await owner.toggleRole(); role.mockResolvedValue({ configured: false, roleId: null, revisionId: null, revisionSha256: null, permissionSha256: null, slot: null, adapterProfile: null, adapterVersion: null, permissions: [] }); await owner.toggleRole(); expect(owner.getSnapshot().role!.configured).toBe(false)
})
it('keeps a monotonic Task usage baseline, positive delta only, and never derives Task progress from Todo', async () => {
  const { owner } = await mounted(); expect(owner.getSnapshot().tokenDelta).toBe(0)
  vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture(undefined, { usage: { totalTokens: 150, unknownUsageCount: 0, observedAt: 'now' } })); await owner.load(); expect(owner.getSnapshot().tokenDelta).toBe(50)
  vi.mocked(api.getTaskSessionActivity).mockResolvedValue(activityFixture(undefined, { usage: { totalTokens: 80, unknownUsageCount: 0, observedAt: 'old' } })); await owner.load(); expect(owner.getSnapshot().totalTokens).toBe(150)
  await vi.advanceTimersByTimeAsync(850); expect(owner.getSnapshot().tokenDelta).toBe(0)
})
