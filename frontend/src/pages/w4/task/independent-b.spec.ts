import { afterEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { createRollingController } from '../actions/rollingController'
import { createDirtyController } from '../actions/dirty'
import { dirty, workbench, reviewing, parentPort, deferred, flush, task } from '../actions/test-support'
import type { TaskChildOwner } from '../shared/types'
const owners: TaskChildOwner[] = []
afterEach(() => { for (const owner of owners.splice(0)) owner.retire(true); vi.restoreAllMocks() })
it('independent rolling retired WB success cannot initiate an old-scope detail GET', async () => {
  const pending = deferred<typeof workbench>(), work = vi.spyOn(api, 'getRollingPackageWorkbench').mockReturnValue(pending.promise)
  const detail = vi.spyOn(api, 'getRollingPackageDetail').mockResolvedValue({ packageRun: reviewing, objective: '', deliverablesJson: '[]', acceptanceIntentJson: '[]' })
  const owner = createRollingController('task'); owners.push(owner); owner.bindParent(parentPort()); owner.attachView(); await flush()
  expect(work).toHaveBeenCalledTimes(1); expect(detail).not.toHaveBeenCalled(); owner.retire(true); const before = owner.getSnapshot()
  pending.resolve(workbench); await flush(); expect(owner.getSnapshot()).toBe(before); expect(detail).not.toHaveBeenCalled()
})
it('independent dirty UNKNOWN recovery retired workspace success cannot initiate old-scope Task GET', async () => {
  const workspace = vi.spyOn(api, 'getDirtyWorkspace').mockResolvedValue(dirty), taskRead = vi.spyOn(api, 'getTask').mockResolvedValue(task())
  const write = vi.spyOn(api, 'resolveDirtyWorkspace').mockRejectedValue(new Error('原操作未知'))
  const owner = createDirtyController('task'); owners.push(owner); owner.bindParent(parentPort()); owner.attachView(); await flush()
  owner.edit({ actions: { 'README.md': 'STASH', 'notes.txt': 'STASH' } }); owner.resolve(); await flush()
  expect(write).toHaveBeenCalledTimes(1); expect(owner.getSnapshot().command.phase).toBe('UNKNOWN'); expect(taskRead).not.toHaveBeenCalled()
  const pending = deferred<typeof dirty>(); workspace.mockReturnValueOnce(pending.promise); const recovering = owner.recover(); await flush()
  expect(workspace).toHaveBeenCalledTimes(2); owner.retire(true); const before = owner.getSnapshot(); pending.resolve(dirty); await recovering
  expect(owner.getSnapshot()).toBe(before); expect(taskRead).not.toHaveBeenCalled(); expect(write).toHaveBeenCalledTimes(1)
})
