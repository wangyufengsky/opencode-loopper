import { workflowPublication } from '@/api/workflowPublication'
import { workflowPush } from '@/api/workflowPush'
import { workflowWriteback } from '@/api/workflowWriteback'
import type { WorkflowPublicationCommit, WorkflowPublicationPreview, WorkflowPublicationSource, WorkflowPushPreview, WorkflowPushView, WorkflowWritebackPreview, WorkflowWritebackView } from '@/types/domain'
import { createRequirementScope, ownedState, requireIdentity } from './core'

export type ArtifactKind = 'commit' | 'push' | 'writeback'
export function createPublicationController(requirement: string, initialRevision: number) {
  let revision = initialRevision, cancelPoll = () => {}, listMoreFailed = false
  const owner = createRequirementScope('requirement-publication', requirement, { ...ownedState(), open: false, rows: [] as WorkflowPublicationSource[], cursor: null as string | null, listing: false,
    selected: null as WorkflowPublicationSource | null, preview: null as WorkflowPublicationPreview | null, checked: null as WorkflowWritebackPreview | null,
    commit: null as WorkflowPublicationCommit | null, commitLoaded: false, message: '', commitOpen: false,
    push: null as WorkflowPushView | null, pushLoaded: false, remotes: [] as string[], remote: '', pushPreview: null as WorkflowPushPreview | null, pushOpen: false,
    writeback: null as WorkflowWritebackView | null, writebackLoaded: false, writebackOpen: false,
  })
  const requests = new Set<() => void>()
  let cancelPreview: (() => void) | undefined
  function abortable(channel: string) { const abort = new AbortController(), dispose = owner.own(() => abort.abort()), ticket = owner.ticket(channel); const release = () => { requests.delete(release); dispose() }; requests.add(release); return { abort, release, ticket } }
  function closeReads() { cancelPoll(); for (const release of [...requests]) release(); for (const channel of ['sources', 'preview', 'writeback-preview', 'push-preview', 'remotes', 'status-commit', 'status-push', 'status-writeback']) owner.ticket(channel) }
  function schedule() {
    cancelPoll(); const s = owner.getSnapshot()
    if (s.commit?.state === 'CONFIRMED' || s.push && ['PREPARING', 'RUNNING'].includes(s.push.state) || s.writeback && ['CONFIRMED', 'APPLYING'].includes(s.writeback.state)) cancelPoll = owner.delay(() => { if (owner.locked()) schedule(); else void refresh() }, 2500)
  }
  async function readStatus(kind: ArtifactKind, strict = false, apply?: (callback: () => void) => boolean) {
    if (!owner.active()) return
    const request = abortable(`status-${kind}`); owner.patch({ loading: true })
    try {
      if (kind === 'commit') { const commit = await workflowPublication.status(requirement, request.abort.signal); if (!request.ticket.current()) return; if (commit) requireIdentity(commit.requirementId, requirement, '提交记录'); if (strict && !commit) throw new Error('原提交尚未能从权威读取核对，请继续读取原结果。'); const project = () => owner.patch({ commit, commitLoaded: true, commitOpen: commit ? false : owner.getSnapshot().commitOpen, error: '' }); if (apply) apply(project); else project() }
      if (kind === 'push') { const push = await workflowPush.status(requirement, request.abort.signal); if (!request.ticket.current()) return; if (push) requireIdentity(push.requirementId, requirement, '推送记录'); if (strict && !push) throw new Error('原推送尚未能从权威读取核对，请继续读取原结果。'); const project = () => owner.patch({ push, pushLoaded: true, pushOpen: push ? false : owner.getSnapshot().pushOpen, error: '' }); if (apply) apply(project); else project() }
      if (kind === 'writeback') { const writeback = await workflowWriteback.status(requirement, request.abort.signal); if (!request.ticket.current()) return; if (writeback) { requireIdentity(writeback.requirementId, requirement, '回填记录'); requireIdentity(writeback.preview.requirementId, requirement, '回填预览') }; if (strict && !writeback) throw new Error('原回填尚未能从权威读取核对，请继续读取原结果。'); const project = () => owner.patch({ writeback, writebackLoaded: true, writebackOpen: writeback ? false : owner.getSnapshot().writebackOpen, error: '' }); if (apply) apply(project); else project() }
    } catch (cause) { if (request.ticket.current()) { owner.fail(cause, '成果记录暂时无法读取，请重试。'); if (strict) throw cause } }
    finally { request.release(); if (request.ticket.current()) { owner.patch({ loading: false }); schedule() } }
  }
  async function refresh() { await readStatus('commit'); if (!owner.active()) return; await readStatus('writeback'); if (owner.getSnapshot().commit?.state === 'COMMITTED' && owner.active()) await readStatus('push') }
  async function list(more = false) {
    if (!owner.active() || owner.locked() || owner.getSnapshot().listing) return
    const request = abortable('sources'), originalRevision = revision; listMoreFailed = more; owner.patch({ listing: true })
    try { const page = await workflowPublication.sources(requirement, originalRevision, more ? owner.getSnapshot().cursor ?? '' : '', request.abort.signal); if (request.ticket.current() && revision === originalRevision) owner.patch({ rows: more ? [...owner.getSnapshot().rows, ...page.items] : page.items, cursor: page.nextCursor ?? null, error: '' }) }
    catch (cause) { if (request.ticket.current()) owner.fail(cause, '代码成果暂时无法读取，请刷新后重试。') }
    finally { request.release(); if (request.ticket.current()) owner.patch({ listing: false }) }
  }
  async function choose(source: WorkflowPublicationSource, discard = false) {
    if (!owner.active() || owner.locked() || !discard && owner.canLeave().kind !== 'ALLOW') return
    // A newer selected source replaces only this instance's preview read.
    cancelPreview?.()
    const request = abortable('preview'), originalRevision = revision; cancelPreview = request.release; owner.patch({ selected: source, preview: null, checked: null, loading: true, commitOpen: false, writebackOpen: false, message: '', dirty: false })
    try { const preview = await workflowPublication.preview(requirement, originalRevision, source, request.abort.signal); if (!request.ticket.current() || revision !== originalRevision) return
      requireIdentity(preview.requirementId, requirement, '成果预览'); if (preview.planRevision !== originalRevision || preview.source.nodeKey !== source.nodeKey || preview.source.attemptId !== source.attemptId || preview.source.outputName !== source.outputName) throw new Error('成果版本已变化，请刷新后重新选择。')
      owner.patch({ preview, error: '' })
    } catch (cause) { if (request.ticket.current()) owner.fail(cause, '所选成果暂时无法预览，请重试。') }
    finally { request.release(); if (cancelPreview === request.release) cancelPreview = undefined; if (request.ticket.current()) owner.patch({ loading: false }) }
  }
  async function checkWriteback() {
    const source = owner.getSnapshot().preview; if (!owner.active() || !source || source.workspaceKind !== 'DIRECT' || owner.locked()) return
    const request = abortable('writeback-preview'); owner.patch({ loading: true, checked: null })
    const selection = { revision: source.planRevision, node: source.source.nodeKey, attempt: source.source.attemptId, output: source.source.outputName, sourceSha256: source.sha256 }
    try { const checked = await workflowWriteback.preview(requirement, selection, request.abort.signal); if (!request.ticket.current() || owner.getSnapshot().preview?.sha256 !== source.sha256) return
      requireIdentity(checked.requirementId, requirement, '回填预览'); if (checked.revision !== source.planRevision || checked.sourceSha256 !== source.sha256 || checked.requirementVersion !== source.requirementVersion) throw new Error('回填预览与原成果不一致，请重新检查。')
      owner.patch({ checked, error: '' })
    } catch (cause) { if (request.ticket.current()) owner.fail(cause, '回填目标尚未能核对，请重新检查。') }
    finally { request.release(); if (request.ticket.current()) owner.patch({ loading: false }) }
  }
  const commitAvailable = () => { const s = owner.getSnapshot(); return s.commitLoaded && !s.commit && !s.error && s.preview?.workspaceKind === 'GIT' && s.preview.requirementState === 'COMPLETED' }
  async function commit() {
    const s = owner.getSnapshot(), preview = s.preview; if (!preview || !commitAvailable() || owner.locked() || s.loading || !s.message.trim() || s.message.length > 200) return
    const body = { requestKey: crypto.randomUUID(), expectedVersion: preview.requirementVersion, revision: preview.planRevision, node: preview.source.nodeKey, attempt: preview.source.attemptId, output: preview.source.outputName, previewSha256: preview.sha256, message: s.message.trim() }
    await owner.mutate({ label: '提交所选成果', input: { endpoint: `/workflows/requirements/${encodeURIComponent(requirement)}/publication`, method: 'POST', requestKey: body.requestKey, body, versions: { requirementVersion: preview.requirementVersion, revision: preview.planRevision } }, write: original => workflowPublication.confirm(requirement, original),
      read: async (receipt, context) => { requireIdentity(receipt.requirementId, requirement, '成果提交回执'); await readStatus('commit', true, context.apply); context.apply(() => owner.patch({ message: '', dirty: false, commitOpen: false })); if (receipt.state === 'COMMITTED' && context.isCurrent()) await readStatus('push') },
    })
  }
  async function showPush() {
    if (!owner.active() || owner.locked() || owner.getSnapshot().push) return
    if (!owner.edit({ pushOpen: true })) return; const request = abortable('remotes'); owner.patch({ loading: true })
    try { const remotes = await workflowPush.remotes(requirement, request.abort.signal); if (request.ticket.current()) owner.patch({ remotes, error: '' }) }
    catch (cause) { if (request.ticket.current()) owner.fail(cause, '远端配置无法读取，请重试。') }
    finally { request.release(); if (request.ticket.current()) owner.patch({ loading: false }) }
  }
  async function checkPush() {
    const remote = owner.getSnapshot().remote; if (!owner.active() || !remote || owner.locked()) return
    const request = abortable('push-preview'); owner.patch({ loading: true, pushPreview: null })
    try { const pushPreview = await workflowPush.preview(requirement, remote, request.abort.signal); if (!request.ticket.current() || owner.getSnapshot().remote !== remote) return; requireIdentity(pushPreview.requirementId, requirement, '推送预览'); if (pushPreview.remote !== remote) throw new Error('目标与所选远端不一致，请重新检查。'); owner.patch({ pushPreview, error: '' }) }
    catch (cause) { if (request.ticket.current()) owner.fail(cause, '推送目标尚未确认，请检查连接与账号后重试。') }
    finally { request.release(); if (request.ticket.current()) owner.patch({ loading: false }) }
  }
  async function push() {
    const s = owner.getSnapshot(), preview = s.pushPreview; if (!preview || owner.locked() || s.loading) return
    const body = { requestKey: crypto.randomUUID(), expectedVersion: preview.publicationVersion, remote: preview.remote, previewSha256: preview.sha256 }
    await owner.mutate({ label: '确认推送', input: { endpoint: `/workflows/requirements/${encodeURIComponent(requirement)}/publication/push`, method: 'POST', requestKey: body.requestKey, body, versions: { publicationVersion: preview.publicationVersion } }, write: original => workflowPush.confirm(requirement, original), read: async (receipt, context) => { requireIdentity(receipt.requirementId, requirement, '推送回执'); await readStatus('push', true, context.apply); context.apply(() => owner.patch({ dirty: false, pushOpen: false })) } })
  }
  const writebackAvailable = () => { const s = owner.getSnapshot(); return s.writebackLoaded && !s.writeback && !s.error && s.preview?.workspaceKind === 'DIRECT' && s.preview.requirementState === 'COMPLETED' && s.checked?.sourceSha256 === s.preview.sha256 && s.checked.requirementVersion === s.preview.requirementVersion && !s.checked.conflictCount && !!s.checked.targetSha256 }
  async function writeback() {
    const s = owner.getSnapshot(), source = s.preview, checked = s.checked; if (!source || !checked || !writebackAvailable() || owner.locked() || s.loading) return
    const body = { requestKey: crypto.randomUUID(), expectedVersion: checked.requirementVersion, selection: { revision: source.planRevision, node: source.source.nodeKey, attempt: source.source.attemptId, output: source.source.outputName, sourceSha256: source.sha256 }, previewSha256: checked.sha256 }
    await owner.mutate({ label: '回填所选成果', input: { endpoint: `/workflows/requirements/${encodeURIComponent(requirement)}/publication/writeback/confirm`, method: 'POST', requestKey: body.requestKey, body, versions: { requirementVersion: checked.requirementVersion } }, write: original => workflowWriteback.confirm(requirement, original), read: async (receipt, context) => { requireIdentity(receipt.requirementId, requirement, '回填回执'); await readStatus('writeback', true, context.apply); context.apply(() => owner.patch({ dirty: false, writebackOpen: false })) } })
  }
  async function retry(kind: ArtifactKind) {
    const s = owner.getSnapshot(); if (owner.locked() || s.loading) return
    if (kind === 'commit' && s.commit?.state === 'BLOCKED') {
      const original = s.commit, body = { expectedVersion: original.version }
      await owner.mutate<typeof body, WorkflowPublicationCommit>({ label: '恢复原提交', input: { endpoint: `/workflows/requirements/${encodeURIComponent(requirement)}/publication/retry`, method: 'POST', body, versions: { publicationVersion: body.expectedVersion } }, write: value => workflowPublication.retry(requirement, value.expectedVersion),
        lookup: async () => { const value = await workflowPublication.status(requirement); if (value) requireIdentity(value.requirementId, requirement); return value && value.version > original.version && value.state !== 'BLOCKED' && value.branch === original.branch && value.message === original.message ? { kind: 'ACCEPTED', receipt: value } : { kind: 'UNCONFIRMED' } },
        read: async (receipt, context) => { requireIdentity(receipt.requirementId, requirement); await readStatus('commit', true, context.apply); if (owner.getSnapshot().commit?.state === 'COMMITTED' && context.isCurrent()) await readStatus('push') },
      })
    }
    if (kind === 'push' && s.push?.state === 'BLOCKED') {
      const original = s.push, body = { expectedVersion: original.version }
      await owner.mutate<typeof body, WorkflowPushView>({ label: '恢复原推送', input: { endpoint: `/workflows/requirements/${encodeURIComponent(requirement)}/publication/push/retry`, method: 'POST', body, versions: { pushVersion: body.expectedVersion } }, write: value => workflowPush.retry(requirement, value.expectedVersion),
        lookup: async () => { const value = await workflowPush.status(requirement); if (value) requireIdentity(value.requirementId, requirement); return value && value.version > original.version && value.state !== 'BLOCKED' && value.remote === original.remote && value.commit === original.commit ? { kind: 'ACCEPTED', receipt: value } : { kind: 'UNCONFIRMED' } }, read: async (receipt, context) => { requireIdentity(receipt.requirementId, requirement); await readStatus('push', true, context.apply) },
      })
    }
    if (kind === 'writeback' && s.writeback?.state === 'BLOCKED') {
      const original = s.writeback, body = { expectedVersion: original.version }
      await owner.mutate<typeof body, WorkflowWritebackView>({ label: '恢复原回填', input: { endpoint: `/workflows/requirements/${encodeURIComponent(requirement)}/publication/writeback/retry`, method: 'POST', body, versions: { writebackVersion: body.expectedVersion } }, write: value => workflowWriteback.retry(requirement, value.expectedVersion),
        lookup: async () => { const value = await workflowWriteback.status(requirement); if (value) requireIdentity(value.requirementId, requirement); return value && value.version > original.version && value.state !== 'BLOCKED' && value.preview.sourceSha256 === original.preview.sourceSha256 ? { kind: 'ACCEPTED', receipt: value } : { kind: 'UNCONFIRMED' } }, read: async (receipt, context) => { requireIdentity(receipt.requirementId, requirement); await readStatus('writeback', true, context.apply) },
      })
    }
  }
  return Object.assign(owner, { requirement, list, choose, refresh, readStatus, commit, push, writeback, checkWriteback, checkPush, showPush, retry, commitAvailable, writebackAvailable,
    retryList() { void list(listMoreFailed) },
    show() { if (!owner.locked()) { owner.patch({ open: true }); void list(); void refresh() } },
    close(discard = false) { if (!owner.locked() && (discard || owner.canLeave().kind === 'ALLOW')) { closeReads(); owner.patch({ open: false, commitOpen: false, pushOpen: false, writebackOpen: false, message: '', remote: '', dirty: false, loading: false, listing: false }) } },
    showCommit() { if (commitAvailable() && !owner.locked()) owner.patch({ commitOpen: true }) },
    showWriteback() { if (writebackAvailable() && !owner.locked()) owner.edit({ writebackOpen: true }) },
    discardDraft() { if (!owner.locked()) owner.patch({ message: '', remote: '', pushPreview: null, dirty: false, draftRevision: owner.getSnapshot().draftRevision + 1 }) },
    changeMessage(message: string) { owner.edit({ message }) },
    changeRemote(remote: string) { if (owner.edit({ remote, pushPreview: null })) owner.ticket('push-preview') },
    updateRevision(next: number) { if (next === revision || owner.canLeave().kind !== 'ALLOW') return; revision = next; owner.ticket('sources'); owner.ticket('preview'); owner.patch({ rows: [], selected: null, preview: null, checked: null, cursor: null }); if (owner.getSnapshot().open) void list() },
  })
}
export type PublicationController = ReturnType<typeof createPublicationController>
