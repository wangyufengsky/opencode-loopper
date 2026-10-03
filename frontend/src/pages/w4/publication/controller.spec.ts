import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import { createTaskPublicationOwner, publicationEligible, unresolvedMarkers } from './controller'
import { content, deferred, fixture, publication, task, file } from './test-support'

const owners: ReturnType<typeof createTaskPublicationOwner>[] = []
afterEach(() => { for (const owner of owners) owner.retire(true); owners.length = 0; vi.restoreAllMocks(); vi.useRealTimers() })
async function open(patch: Parameters<typeof fixture>[0] = {}, ports: Parameters<typeof createTaskPublicationOwner>[1] = {}) {
  const f = fixture(patch), owner = createTaskPublicationOwner(task(), ports); owners.push(owner); owner.attachView(); await owner.load(); return { f, owner }
}
async function edit(owner: ReturnType<typeof createTaskPublicationOwner>) { await owner.openCommit(); owner.changeTicket('1234') }
async function conflict(patch: Parameters<typeof fixture>[0] = {}) { const result = await open({ state: 'LOCAL_SYNC_CONFLICT', deliveryState: 'COMMITTED', conflictSessionId: 'session', commitSha: 'commit', ...patch }); await result.owner.openConflict(); return result }

describe('W4 publication single command owner', () => {
  it('reads on entry/focus with cooldown and never implicitly POSTs reconciliation or suggestion', async () => {
    vi.useFakeTimers(); const { owner, f } = await open({ state: 'PUSHED', deliveryState: 'PUSHED', commitSha: 'commit' }); const count = f.get.mock.calls.length
    window.dispatchEvent(new FocusEvent('focus')); expect(f.get).toHaveBeenCalledTimes(count)
    vi.advanceTimersByTime(30_000); window.dispatchEvent(new FocusEvent('focus')); await Promise.resolve(); expect(f.get).toHaveBeenCalledTimes(count + 1)
    expect(f.reconcile).not.toHaveBeenCalled(); expect(f.suggestion).not.toHaveBeenCalled(); owner.retire(true); window.dispatchEvent(new FocusEvent('focus')); expect(f.get).toHaveBeenCalledTimes(count + 1)
  })
  it.each([' line one\nline two ', 'line\t\twith   spaces', '\u3000全角\n说明'])('normalizes the explicit suggested subject %s and publishes one exact four-digit message', async subject => {
    const { owner, f } = await open(); f.suggestion.mockResolvedValueOnce({ subject, aiGenerated: true }); await edit(owner)
    const expected = '#1234_' + subject.replace(/[\s\p{Z}]+/gu, ' ').trim(); await owner.submitCommit(); expect(f.publish).toHaveBeenCalledExactlyOnceWith('task', expected); expect(owner.getSnapshot().command.phase).toBe('SETTLED'); expect(owner.getSnapshot().commit.open).toBe(false)
  })
  it('uses a bounded manual fallback only after a definitive suggestion rejection and validates ticket/subject', async () => {
    const { owner, f } = await open(); f.suggestion.mockRejectedValueOnce(new ApiError('bad request', 400)); await owner.openCommit(); expect(owner.getSnapshot().commit.subject).toBe(task().title)
    await owner.submitCommit(); expect(f.publish).not.toHaveBeenCalled(); owner.changeTicket('00a23'); owner.changeSubject('edited\nsubject'); await owner.submitCommit(); expect(f.publish).toHaveBeenCalledExactlyOnceWith('task', '#0023_edited subject')
  })
  it('does not let unknown suggestion turn into a fresh publish or edited body', async () => {
    const { owner, f } = await open(); f.suggestion.mockRejectedValueOnce(new Error('lost')); await owner.openCommit(); expect(owner.canLeave().kind).toBe('BLOCK'); const original = owner.getSnapshot().commit.subject
    owner.changeSubject('replacement'); owner.changeTicket('1234'); await owner.submitCommit(); expect(owner.getSnapshot().commit.subject).toBe(original); expect(f.publish).not.toHaveBeenCalled(); await owner.recover(); expect(f.suggestion).toHaveBeenCalledOnce()
  })
  it('publishes local-only branches without pretending a remote push occurred', async () => {
    const { owner, f } = await open({ remoteName: undefined, remoteUrl: undefined, provider: 'UNKNOWN' }); await edit(owner); await owner.submitCommit(); expect(f.publish).toHaveBeenCalledOnce(); expect(owner.getSnapshot().publication).toMatchObject({ state: 'SYNCED_LOCAL', deliveryState: 'LOCAL_COMPLETED', deliveryFinal: true })
  })
  it('only resumes the original COMMITTED push and never writes after final MERGED', async () => {
    const { owner, f } = await open({ state: 'COMMITTED', deliveryState: 'COMMITTED', commitSha: 'commit', commitMessage: '#1234_original' }); await owner.submitCommit(true); expect(f.publish).toHaveBeenCalledExactlyOnceWith('task', undefined)
    f.setPublication({ state: 'MERGED', deliveryState: 'MERGED', deliveryFinal: true }); await owner.load(); await owner.submitCommit(true); await owner.reconcile(); owner.openMerge(); expect(f.publish).toHaveBeenCalledOnce(); expect(f.reconcile).not.toHaveBeenCalled(); expect(owner.getSnapshot().merge.open).toBe(false)
  })
  it('retains exact commit draft after an accepted read failure and recovers with GET only', async () => {
    const refresh = vi.fn().mockRejectedValueOnce(new Error('parent read failed')).mockResolvedValue(task()), { owner, f } = await open({}, { refresh }); await edit(owner); await owner.submitCommit()
    expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.canLeave().kind).toBe('BLOCK'); expect(owner.getSnapshot().commit.ticket).toBe('1234'); const message = f.publish.mock.calls[0]![1]
    await owner.recover(); expect(f.publish).toHaveBeenCalledOnce(); expect(f.publish.mock.calls[0]![1]).toBe(message); expect(owner.getSnapshot().commit.ticket).toBe(''); expect(owner.canLeave().kind).toBe('ALLOW')
  })
  it('can identify an uncertain commit only by original branch/message/new commit SHA and then reads without reposting', async () => {
    const { owner, f } = await open(); await edit(owner); f.publish.mockRejectedValueOnce(new Error('lost')); await owner.submitCommit(); const frozen = owner.getSnapshot().commit
    f.setPublication({ state: 'PUSHED', deliveryState: 'PUSHED', commitSha: 'different', commitMessage: '#1234_other' }); await owner.recover(); expect(owner.getSnapshot().command.phase).toBe('UNKNOWN'); expect(owner.getSnapshot().commit).toEqual(frozen)
    f.setPublication({ commitMessage: '#1234_' + frozen.subject }); await owner.recover(); expect(owner.getSnapshot().command.phase).toBe('SETTLED'); expect(f.publish).toHaveBeenCalledOnce()
  })
  it('checks reconciliation only on an explicit command and retains UNKNOWN until same commit fresh check is read', async () => {
    const { owner, f } = await open({ state: 'MERGE_REQUEST_OPENED', deliveryState: 'MERGE_REQUEST_OPENED', commitSha: 'commit', lastCheckedAt: 'old' }); f.reconcile.mockRejectedValueOnce(new Error('lost')); await owner.reconcile(); await owner.recover(); expect(owner.getSnapshot().command.phase).toBe('UNKNOWN')
    f.setPublication({ lastCheckedAt: 'new' }); await owner.recover(); expect(owner.getSnapshot().command.phase).toBe('SETTLED'); expect(f.reconcile).toHaveBeenCalledOnce()
  })
  it('prepares an exact merge request and gets the real creation metadata without fabricated timestamps', async () => {
    const { owner, f } = await open({ state: 'PUSHED', deliveryState: 'PUSHED', commitSha: 'commit', commitMessage: '#1234_original' }); owner.openMerge(); expect(owner.getSnapshot().merge).toMatchObject({ title: '#1234_original', targetBranch: 'main' }); owner.changeMerge({ title: 'reviewed', description: 'exact description', targetBranch: 'develop' }); expect(await owner.createMerge()).toBe(true)
    expect(f.mr).toHaveBeenCalledExactlyOnceWith('task', { title: 'reviewed', description: 'exact description', targetBranch: 'develop' }); expect(owner.getSnapshot().publication?.creationRequestedAt).toBe('2026-10-03T12:00:00Z'); expect(owner.getSnapshot().merge.draft?.creationUrl).toContain('/merge/new')
  })
  it('keeps accepted MR inputs through read failure and makes read-only recovery instead of a second draft POST', async () => {
    const { owner, f } = await open({ state: 'PUSHED', deliveryState: 'PUSHED', commitSha: 'commit' }); owner.openMerge(); owner.changeMerge({ title: 'manual title' }); f.get.mockRejectedValueOnce(new Error('read failed')); await owner.createMerge(); expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.getSnapshot().merge.title).toBe('manual title'); await owner.recover(); expect(f.mr).toHaveBeenCalledOnce(); expect(owner.getSnapshot().command.phase).toBe('SETTLED')
  })
  it('blocks keyless MR retry when there is no causal readback contract', async () => {
    const { owner, f } = await open({ state: 'PUSHED', deliveryState: 'PUSHED' }); owner.openMerge(); f.mr.mockRejectedValueOnce(new Error('lost')); await owner.createMerge(); owner.changeMerge({ title: 'new title' }); await owner.recover(); expect(owner.getSnapshot().command.recovery).toBe('BLOCKED'); expect(f.mr).toHaveBeenCalledOnce(); expect(owner.canLeave().kind).toBe('BLOCK')
  })
  it('preserves local conflict file CAS and only enables apply after the exact solution is read back', async () => {
    const { owner, f } = await conflict(); await owner.applyLocal(); expect(f.apply).not.toHaveBeenCalled(); await owner.saveResolution('SOURCE'); expect(f.save).toHaveBeenCalledExactlyOnceWith('task', 'session', { path: 'src/Sample.java', resolution: 'SOURCE', expectedVersion: 4 }); expect(owner.getSnapshot().conflict.session?.state).toBe('READY'); await owner.applyLocal(); expect(f.apply).toHaveBeenCalledExactlyOnceWith('task', 'session', { confirmed: true, expectedVersion: 4 }); expect(owner.getSnapshot().publication?.deliveryFinal).toBe(true)
  })
  it('adopts a single diff block into a dirty editor and needs explicit MANUAL save before apply', async () => {
    const { owner, f } = await conflict(); owner.acceptBlock('task'); expect(owner.getSnapshot().conflict.merged).toContain('int value = 2'); expect(unresolvedMarkers(owner.getSnapshot().conflict.merged)).toBe(false); expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD'); await owner.applyLocal(); expect(f.apply).not.toHaveBeenCalled(); await owner.saveResolution('MANUAL'); expect(f.save.mock.calls[0]![2]).toMatchObject({ resolution: 'MANUAL', expectedVersion: 4, content: owner.getSnapshot().conflict.merged })
  })
  it.each(['<<<<<<< source\nno end', '=======\nremaining', '>>>>>>> task'])('rejects incomplete markers %s without issuing a save', async value => {
    const { owner, f } = await conflict(); owner.editMerged(value); await owner.saveResolution('MANUAL'); expect(f.save).not.toHaveBeenCalled(); expect(owner.getSnapshot().conflict.error).toContain('Git冲突标记')
  })
  it('requests AI only explicitly and never adopts or saves its suggestion until separate user actions', async () => {
    const { owner, f } = await conflict(); const original = owner.getSnapshot().conflict.merged; await owner.suggest(); expect(f.ai).toHaveBeenCalledExactlyOnceWith('task', 'session', { path: 'src/Sample.java', expectedVersion: 4 }); expect(owner.getSnapshot().conflict.merged).toBe(original); expect(f.save).not.toHaveBeenCalled(); owner.loadSuggestion(); expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD'); await owner.saveResolution('MANUAL'); expect(f.save.mock.calls[0]![2].expectedVersion).toBe(5)
  })
  it('does not infer that an unknown AI suggestion was never selected when GET resolution/body changed', async () => {
    const { owner, f } = await conflict(); f.setContent({ aiSuggestion: 'cached suggestion', resolution: 'MANUAL', mergedContent: 'same manual body' }); await owner.selectFile('src/Sample.java'); f.ai.mockRejectedValueOnce(new Error('lost')); await owner.suggest(); f.setContent({ version: 5 }); await owner.recover(); expect(owner.getSnapshot().command.phase).toBe('UNKNOWN'); f.setContent({ aiSuggestion: 'new AI', resolution: 'SOURCE', mergedContent: 'changed' }); await owner.recover(); expect(owner.getSnapshot().command.phase).toBe('UNKNOWN'); expect(f.save).not.toHaveBeenCalled(); expect(f.ai).toHaveBeenCalledOnce()
  })
  it('retains unknown manual save exact bytes and version and permits only verified GET recovery', async () => {
    const { owner, f } = await conflict(); owner.editMerged('class Sample { int value = 8; }'); f.save.mockRejectedValueOnce(new Error('lost')); await owner.saveResolution('MANUAL'); const frozen = owner.getSnapshot().conflict.merged; owner.editMerged('replacement'); await owner.saveResolution('SOURCE'); expect(owner.getSnapshot().conflict.merged).toBe(frozen); expect(f.save).toHaveBeenCalledOnce(); await owner.recover(); expect(owner.getSnapshot().command.phase).toBe('UNKNOWN')
    f.setContent({ version: 5, resolution: 'MANUAL', mergedContent: frozen }); f.setSession({ state: 'READY', version: 4, resolvedCount: 1 }); await owner.recover(); expect(owner.getSnapshot().command.phase).toBe('SETTLED'); expect(f.save).toHaveBeenCalledOnce()
  })
  it('supports binary and large files only via SOURCE/TASK without manual text writes', async () => {
    const { owner, f } = await conflict(); f.setContent({ contentType: 'BINARY', aiEligible: false }); await owner.selectFile('src/Sample.java'); owner.editMerged('bad'); await owner.suggest(); await owner.saveResolution('MANUAL'); expect(f.ai).not.toHaveBeenCalled(); expect(f.save).not.toHaveBeenCalled(); await owner.saveResolution('TASK'); expect(f.save.mock.calls[0]![2].resolution).toBe('TASK')
  })
  it('does not discard a dirty editor through file selection or refresh without explicit discard', async () => {
    const { owner } = await conflict(); owner.editMerged('manual draft'); await owner.selectFile('src/Sample.java'); await owner.refreshConflict(); expect(owner.getSnapshot().conflict.merged).toBe('manual draft'); expect(api.createLocalSyncConflictSession).not.toHaveBeenCalled(); owner.discard('conflict'); owner.show('conflict'); await owner.refreshConflict(); expect(api.createLocalSyncConflictSession).toHaveBeenCalledOnce()
  })
  it('preserves rollback failed evidence and never calls apply in STALE or ROLLBACK_FAILED', async () => {
    const { owner, f } = await conflict(); for (const status of ['STALE', 'ROLLBACK_FAILED'] as const) { f.setSession({ state: status, backupDir: '/backup', verificationEvidence: '{"checks":[]}' }); await owner.openConflict(); await owner.applyLocal(); expect(owner.getSnapshot().conflict.session?.state).toBe(status) }; expect(f.apply).not.toHaveBeenCalled()
  })
  it('ends a late conflict read at its await boundary after retirement without querying the next file', async () => {
    const { owner } = await open({ state: 'LOCAL_SYNC_CONFLICT', conflictSessionId: 'session' }); const late = deferred<ReturnType<typeof file>[]>(); vi.mocked(api.getLocalSyncConflictFiles).mockReturnValueOnce(late.promise); const read = owner.openConflict(); await Promise.resolve(); owner.retire(true); late.resolve([file()]); await read; expect(api.getLocalSyncConflictContent).not.toHaveBeenCalled()
  })
  it('rejects an old file response after a newer selection and never overwrites its path/content', async () => {
    const { owner, f } = await conflict(); f.setFiles([file(), file({ path: 'other.json' })]); await owner.openConflict(); const late = deferred<ReturnType<typeof content>>(); vi.mocked(api.getLocalSyncConflictContent).mockReturnValueOnce(late.promise); const old = owner.selectFile('src/Sample.java'); await owner.selectFile('other.json'); late.resolve(content({ mergedContent: 'stale' })); await old; expect(owner.getSnapshot().conflict.path).toBe('other.json'); expect(owner.getSnapshot().conflict.merged).not.toBe('stale')
  })
  it('cannot retire pending/unknown/dirty owners normally and ignores late forced retirement callbacks', async () => {
    const { owner, f } = await open(); await edit(owner); expect(owner.retire(false).kind).toBe(owner.canLeave().kind); const late = deferred<ReturnType<typeof publication>>(); f.publish.mockReturnValueOnce(late.promise); const write = owner.submitCommit(); expect(owner.canLeave().kind).toBe('BLOCK'); expect(owner.retire(false).kind).toBe(owner.canLeave().kind); owner.retire(true); const getCount = f.get.mock.calls.length; late.resolve(publication({ state: 'PUSHED', deliveryState: 'PUSHED', commitSha: 'commit' })); await write; expect(f.get).toHaveBeenCalledTimes(getCount); expect(owner.getSnapshot().commit.ticket).toBe('1234')
  })
  it('passes its own caller to the parent gate but cannot bypass another pending child', async () => {
    const gate = vi.fn(() => false), { owner, f } = await open({}, { canStartWrite: gate }); await owner.openCommit(); expect(gate).toHaveBeenCalledWith(owner); expect(f.suggestion).not.toHaveBeenCalled()
  })

  it('keeps a dirty conflict draft on reopen and prevents new commands while file reading is pending', async () => {
    const { owner, f } = await conflict(); owner.editMerged('manual draft'); await owner.openConflict(); expect(owner.getSnapshot().conflict.merged).toBe('manual draft'); owner.discard('conflict'); owner.show('conflict'); const late = deferred<ReturnType<typeof content>>(); vi.mocked(api.getLocalSyncConflictContent).mockReturnValueOnce(late.promise); const read = owner.selectFile('src/Sample.java'); await owner.saveResolution('SOURCE'); await owner.suggest(); expect(f.save).not.toHaveBeenCalled(); expect(f.ai).not.toHaveBeenCalled(); late.resolve(content()); await read
  })
  it('does not mistake the old session returned by uncertain refresh for acceptance of a new preflight', async () => {
    const { owner } = await conflict(); vi.mocked(api.createLocalSyncConflictSession).mockRejectedValueOnce(new Error('lost')); await owner.refreshConflict(); await owner.recover(); expect(owner.getSnapshot().command.phase).toBe('UNKNOWN'); expect(api.createLocalSyncConflictSession).toHaveBeenCalledOnce()
  })
  it('rejects foreign session and commit receipt identities before projection', async () => {
    const { owner, f } = await open(); await edit(owner); f.publish.mockResolvedValueOnce(publication({ state: 'PUSHED', deliveryState: 'PUSHED', branch: 'foreign', commitSha: 'commit', commitMessage: '#1234_other' })); await owner.submitCommit(); expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.getSnapshot().publication?.branch).toBe('loopper/task'); expect(owner.getSnapshot().commit.ticket).toBe('1234')
  })
  it('blocks new writes after eligibility changes but retains the original pending command recovery', async () => {
    const { owner, f } = await conflict(); const snapshot = owner.getSnapshot(); owner.updateTask(structuredClone(snapshot.task)); expect(owner.getSnapshot()).toBe(snapshot); owner.updateTask(task({ status: 'FAILED', version: 4 })); await owner.saveResolution('SOURCE'); await owner.suggest(); await owner.refreshConflict(); expect(f.save).not.toHaveBeenCalled(); expect(f.ai).not.toHaveBeenCalled(); expect(api.createLocalSyncConflictSession).not.toHaveBeenCalled(); expect(owner.capture().isCurrent()).toBe(true)
  })
  it('keeps successful completed tasks eligible but template report and failed tasks unavailable', () => {
    expect(publicationEligible(task({ status: 'COMPLETED' }))).toBe(true); expect(publicationEligible(task({ status: 'COMPLETED', executionResult: 'FAILED' }))).toBe(false); expect(publicationEligible(task({ executionMode: 'TEMPLATE_REPORT' }))).toBe(false)
  })
})
