import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import type { W2Navigation } from '@/pages/w2/shared/types'
import { createRecoveryStudioController, recoveryStage } from './controller'
import { deferred, recovery, task } from '../publication/test-support'

const owners: ReturnType<typeof createRecoveryStudioController>[] = []
afterEach(() => { for (const owner of owners) owner.retire(true); owners.length = 0; vi.restoreAllMocks() })
async function context() {
  const parent = task({ status: 'FAILED', stages: [{ id: 'failed', ordinal: 2, objective: '失败阶段', status: 'FAILED', attempts: [] }] })
  vi.spyOn(api, 'getTask').mockResolvedValue(parent); const list = vi.spyOn(api, 'getTaskRecoveries').mockResolvedValue([]), create = vi.spyOn(api, 'createTaskRecovery').mockImplementation(async (_id, mode) => { const row = recovery({ mode }); list.mockResolvedValue([row]); return row })
  const owner = createRecoveryStudioController('task'); owners.push(owner); owner.attachView(); await owner.load(); return { owner, list, create, parent }
}
describe('W4 recovery studio original draft protocol', () => {
  it.each(['FROM_FAILED_STAGE', 'ALL_STAGES', 'VERIFY_ONLY'] as const)('creates only the explicit %s mode and never starts the derived task', async mode => {
    const { owner, create } = await context(); const start = vi.spyOn(api, 'startTask'); owner.changeMode(mode); await owner.create(); expect(create).toHaveBeenCalledExactlyOnceWith('task', mode); expect(start).not.toHaveBeenCalled(); expect(owner.getSnapshot().result).toMatchObject({ taskId: 'child', parentTaskId: 'task', mode, workspaceFingerprint: 'frozenhash' }); expect(owner.canLeave().kind).toBe('ALLOW')
  })
  it('keeps the original stage selection order and ordinary mode changes dirty', async () => {
    const { owner, parent } = await context(); expect(recoveryStage(parent)?.id).toBe('failed'); owner.changeMode('ALL_STAGES'); expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD'); expect(owner.retire(false).kind).toBe(owner.canLeave().kind); expect(owner.capture().isCurrent()).toBe(true)
  })
  it('does not allow non failed/cancelled tasks to create despite a public method call', async () => {
    const { owner, create } = await context(); vi.mocked(api.getTask).mockResolvedValue(task({ status: 'SUCCEEDED' })); await owner.load(); await owner.create(); expect(create).not.toHaveBeenCalled()
  })
  it('keeps the exact mode frozen during SENDING and UNKNOWN; a lineage row alone cannot prove keyless acceptance', async () => {
    const { owner, create, list } = await context(); const late = deferred<ReturnType<typeof recovery>>(); create.mockReturnValueOnce(late.promise); owner.changeMode('VERIFY_ONLY'); const sending = owner.create(); owner.changeMode('ALL_STAGES'); expect(owner.getSnapshot().mode).toBe('VERIFY_ONLY'); expect(owner.retire(false).kind).toBe(owner.canLeave().kind); late.reject(new Error('lost response')); await sending; expect(owner.canLeave().kind).toBe('BLOCK'); list.mockResolvedValue([recovery({ mode: 'VERIFY_ONLY' })]); await owner.recover(); expect(owner.getSnapshot().command.phase).toBe('UNKNOWN'); expect(owner.getSnapshot().recoveries).toHaveLength(1); expect(create).toHaveBeenCalledOnce(); owner.changeMode('ALL_STAGES'); expect(owner.getSnapshot().mode).toBe('VERIFY_ONLY')
  })
  it('retains accepted child and mode after failed read and retries only GET before clearing dirty', async () => {
    const { owner, create, list } = await context(); create.mockImplementationOnce(async () => { list.mockRejectedValueOnce(new Error('read offline')); return recovery() }); owner.changeMode('FROM_FAILED_STAGE'); await owner.create(); expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.getSnapshot().result?.taskId).toBe('child'); expect(owner.canLeave().kind).toBe('BLOCK'); list.mockResolvedValue([recovery()]); await owner.recover(); expect(create).toHaveBeenCalledOnce(); expect(owner.canLeave().kind).toBe('ALLOW')
  })
  it('accepts only exact parent/mode/fingerprint records, not a different derived task', async () => {
    const { owner, create, list } = await context(); create.mockImplementationOnce(async () => { list.mockResolvedValue([recovery({ workspaceFingerprint: 'different' })]); return recovery() }); await owner.create(); expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.getSnapshot().dirty).toBe(false); expect(owner.canLeave().kind).toBe('BLOCK')
  })
  it('ignores a forced retired original write and never loads or projects into a new task scope', async () => {
    const { owner, create, list } = await context(), late = deferred<ReturnType<typeof recovery>>(); create.mockReturnValueOnce(late.promise); const sending = owner.create(); owner.retire(true); const readCount = list.mock.calls.length; late.resolve(recovery()); await sending; expect(list).toHaveBeenCalledTimes(readCount); expect(owner.getSnapshot().result).toBeUndefined()
  })

  it('treats only proven before-create 409 codes as rejected; an unknown 409 keeps the original operation blocked', async () => {
    const { owner, create } = await context(); create.mockRejectedValueOnce(new ApiError('工作区指纹已经变化', 409, { code: 'RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH' })); await owner.create(); expect(owner.getSnapshot().command.phase).toBe('SETTLED'); expect(owner.getSnapshot().result).toBeUndefined(); expect(owner.canLeave().kind).toBe('ALLOW'); create.mockRejectedValueOnce(new ApiError('未确认的冲突', 409, { code: 'OTHER_CONFLICT' })); await owner.create(); expect(owner.getSnapshot().command.phase).toBe('UNKNOWN'); expect(owner.canLeave().kind).toBe('BLOCK')
  })
  it('uses a receipt-scoped exact child permit for accepted navigation instead of a different destination', async () => {
    const { owner, create, list } = await context(); create.mockImplementationOnce(async () => { list.mockRejectedValueOnce(new Error('offline')); return recovery() }); owner.changeMode('FROM_FAILED_STAGE'); await owner.create(); const navigation: W2Navigation & { go: ReturnType<typeof vi.fn<W2Navigation['go']>>; goAccepted: ReturnType<typeof vi.fn<W2Navigation['goAccepted']>> } = { go: vi.fn<W2Navigation['go']>(async () => true), goAccepted: vi.fn<W2Navigation['goAccepted']>(async () => false), back: vi.fn(), guardChanged: vi.fn(), registerGuard: vi.fn(() => () => {}) }; expect(await owner.openTask('child', navigation)).toBe(false); expect(navigation.goAccepted).toHaveBeenCalledWith('/tasks/child', expect.anything()); expect(owner.canLeave().kind).toBe('BLOCK'); const call = navigation.goAccepted.mock.calls[0]!; expect(owner.canLeave({ destination: '/tasks/child', handoff: call[1] }).kind).toBe('ALLOW'); expect(owner.canLeave({ destination: '/tasks/different', handoff: call[1] }).kind).toBe('BLOCK'); await owner.openTask('different', navigation); expect(navigation.go).toHaveBeenCalledWith('/tasks/different')
  })
})
