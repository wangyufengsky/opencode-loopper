import { api } from '@/api/client'
import { createW4Owner, dirtyDecision, idleCommand, pendingCommand, recoverOperation, type CommandState } from '../shared/core'
import type { TaskChildOwner } from '../shared/types'
import type { OperationIdentity, OperationInput, OriginalLookup, ReadContext } from '@/foundation/contracts/receipt'
import type { CommitMessageSuggestion, LocalSyncConflictContent, LocalSyncConflictFile, LocalSyncConflictSession, LocalSyncResolution, MergeRequestDraft, Task, TaskPublicationStatus } from '@/types/domain'
import { normalizeCommitSubject, suggestedCommitSubject, validCommitSubject } from '@/utils/commitMessage'
import { parseMergeConflicts, resolveMergeConflict, type MergeSide } from '@/utils/mergeView'
import { userFacingError } from '@/utils/displayLabels'

export interface PublicationState {
  task: Task
  publication?: TaskPublicationStatus
  loading: boolean
  error: string
  notice: string
  command: CommandState
  draftRevision: number
  commit: { open: boolean; ticket: string; subject: string; baseline: string; suggesting: boolean; aiSuggested: boolean; error: string }
  merge: { open: boolean; targetBranch: string; title: string; description: string; baseline: string; error: string; draft?: MergeRequestDraft }
  conflict: { open: boolean; loading: boolean; error: string; session?: LocalSyncConflictSession; files: LocalSyncConflictFile[]; path: string; content?: LocalSyncConflictContent; merged: string; baseline: string; index: number; baseOpen: boolean }
}
export const publicationEligible = (task: Task) => task.executionMode !== 'TEMPLATE_REPORT' && (task.status === 'SUCCEEDED' || ['AWAITING_DECISION', 'COMPLETED'].includes(task.status) && task.executionResult === 'SUCCEEDED')
export const commitPreview = (state: PublicationState) => `#${state.commit.ticket || '0000'}_${normalizeCommitSubject(state.commit.subject) || 'AI 生成提交信息'}`
export const validCommit = (state: PublicationState) => /^\d{4}$/.test(state.commit.ticket) && validCommitSubject(state.commit.subject)
export const publicationDirty = (state: PublicationState) => state.commit.ticket !== '' || state.commit.subject !== state.commit.baseline
  || JSON.stringify([state.merge.targetBranch, state.merge.title, state.merge.description]) !== state.merge.baseline || state.conflict.merged !== state.conflict.baseline
const emptyCommit: PublicationState['commit'] = { open: false, ticket: '', subject: '', baseline: '', suggesting: false, aiSuggested: false, error: '' }
const emptyMerge: PublicationState['merge'] = { open: false, targetBranch: '', title: '', description: '', baseline: JSON.stringify(['', '', '']), error: '' }
const emptyConflict: PublicationState['conflict'] = { open: false, loading: false, error: '', files: [], path: '', merged: '', baseline: '', index: 0, baseOpen: false }
export const deliveryLabel = (state?: TaskPublicationStatus['deliveryState']) => ({ NOT_STARTED: '待提交', COMMITTED: '已提交', PUSHED: '已推送', MERGE_REQUEST_OPENED: '合并请求已创建', MERGE_REQUEST_CLOSED: '合并请求已关闭', MERGED: '已合并', LOCAL_COMPLETED: '本地交付已完成', NOT_APPLICABLE: '不适用' }[state ?? 'NOT_STARTED'])
const deliveryRank = (value: TaskPublicationStatus['deliveryState']) => ({ NOT_STARTED: 0, COMMITTED: 1, PUSHED: 2, MERGE_REQUEST_OPENED: 3, MERGE_REQUEST_CLOSED: 3, MERGED: 4, LOCAL_COMPLETED: 4, NOT_APPLICABLE: 4 }[value])
export function unresolvedMarkers(content: string) {
  // Also reject an incomplete marker block; parsing complete blocks alone is insufficient.
  return content.split(/\r?\n/).some(line => /^(?:<<<<<<<|>>>>>>>|\|\|\|\|\|\|\|)(?:\s|$)/.test(line.trimStart()) || line.trim() === '=======')
}
function sameHashes(a: Readonly<LocalSyncConflictContent>, b: Readonly<LocalSyncConflictContent>) { return a.path === b.path && a.baseHash === b.baseHash && a.sourceHash === b.sourceHash && a.taskHash === b.taskHash }
function verifyPublication(actual: TaskPublicationStatus, expected: Readonly<TaskPublicationStatus>) {
  if (expected.branch && actual.branch !== expected.branch || expected.commitSha && actual.commitSha !== expected.commitSha || deliveryRank(actual.deliveryState) < deliveryRank(expected.deliveryState)) throw new Error('发布状态尚未核对到原分支和提交，请只重新读取。')
}

export function createTaskPublicationOwner(task: Task, ports: { canStartWrite?: (caller: TaskChildOwner) => boolean; refresh?: () => Promise<unknown> } = {}) {
  const id = task.id
  const core = createW4Owner<PublicationState>('task-publication', id, {
    task, loading: false, error: '', notice: '', command: idleCommand, draftRevision: 0, commit: emptyCommit, merge: emptyMerge, conflict: emptyConflict,
  }, state => dirtyDecision(publicationDirty(state), state.draftRevision))
  const { base, patch } = core
  let recoverCurrent = async () => {}, lastFocusReadAt = 0
  let owner: ReturnType<typeof publicOwner>
  const state = () => base.getSnapshot()
  const current = () => base.capture().isCurrent()
  const locked = () => pendingCommand(state().command)
  const modify = (changes: Partial<PublicationState>) => { if (!locked()) patch({ ...changes, draftRevision: state().draftRevision + 1 }) }
  function conflictPatch(changes: Partial<PublicationState['conflict']>) { patch({ conflict: { ...state().conflict, ...changes } }) }
  function projectPublication(next: TaskPublicationStatus) {
    const old = state().publication
    if (old?.deliveryFinal && !next.deliveryFinal || old && deliveryRank(next.deliveryState) < deliveryRank(old.deliveryState)) return
    patch({ publication: next })
  }
  async function load() {
    if (!publicationEligible(state().task)) return
    const ticket = core.ticket('publication'); patch({ loading: true, error: '' })
    try { const next = await api.getTaskPublication(id); if (ticket.current()) projectPublication(next) }
    catch (failure) { if (ticket.current()) patch({ error: userFacingError(failure, '无法读取任务发布状态') }) }
    finally { if (ticket.current()) patch({ loading: false }) }
  }
  core.setStart(() => {
    const focus = () => { const now = Date.now(); if (now - lastFocusReadAt >= 30_000) { lastFocusReadAt = now; void load() } }
    window.addEventListener('focus', focus); core.own(() => window.removeEventListener('focus', focus))
    lastFocusReadAt = Date.now(); void load()
  })
  core.setWriteGate(() => ports.canStartWrite?.(owner) ?? true)
  async function perform<B, R>(label: string, input: OperationInput<B>, write: (identity: OperationIdentity<B>) => Promise<R>, read: (receipt: Readonly<R>, context: ReadContext) => Promise<void>, lookup?: (identity: OperationIdentity<B>) => Promise<OriginalLookup<R>>) {
    if (locked() || !publicationEligible(state().task) || !core.canStartWrite()) return false
    const operation = core.command<B, R>({ label, input, write, read,
      capability: lookup ? { kind: 'READ_ORIGINAL', readOriginal: async identity => { const token = base.capture(); const result = await lookup(identity); return token.isCurrent() ? result : { kind: 'UNCONFIRMED' } } } : { kind: 'NONE' },
      changed: command => patch({ command }),
    })
    recoverCurrent = () => recoverOperation(operation)
    try { await operation.execute(); return operation.getSnapshot().phase === 'SETTLED' && current() }
    catch { return false }
  }
  async function publicationRead(receipt: Readonly<TaskPublicationStatus>, context: ReadContext) {
    context.apply(() => projectPublication({ ...receipt, targetBranches: [...receipt.targetBranches] }))
    const next = await api.getTaskPublication(id)
    if (!context.isCurrent()) return
    verifyPublication(next, receipt)
    if (ports.refresh) { await ports.refresh(); if (!context.isCurrent()) return }
    context.apply(() => { projectPublication(next); patch({ error: '', notice: next.state === 'LOCAL_SYNC_CONFLICT' ? '提交已保留，请解决同步冲突。' : '发布状态已更新。' }) })
  }
  async function openCommit() {
    if (locked() || !core.canStartWrite() || state().publication?.state !== 'READY') return
    patch({ commit: { ...emptyCommit, open: true, suggesting: true }, notice: '' })
    const ok = await perform<undefined, CommitMessageSuggestion>('生成提交说明', { endpoint: `/tasks/${encodeURIComponent(id)}/publication/commit-message`, method: 'POST', body: undefined },
      () => api.generateTaskCommitMessage(id), async (receipt, context) => {
        const subject = suggestedCommitSubject(receipt.subject)
        context.apply(() => patch({ commit: { ...state().commit, subject, baseline: subject, aiSuggested: receipt.aiGenerated, suggesting: false } }))
      })
    if (!ok && current()) {
      const subject = suggestedCommitSubject(state().task.title, 80)
      patch({ commit: { ...state().commit, subject, baseline: subject, suggesting: false, error: '提交说明未能生成，可手工填写；未知请求仍须先核对。' } })
    }
  }
  function changeTicket(value: string) { modify({ commit: { ...state().commit, ticket: value.replace(/\D/g, '').slice(0, 4) } }) }
  function changeSubject(subject: string) { modify({ commit: { ...state().commit, subject } }) }
  async function submitCommit(retry = false) {
    const snapshot = state(), original = snapshot.publication
    if (!original || !publicationEligible(snapshot.task) || original.deliveryFinal || locked()) return
    if (!retry && !validCommit(snapshot)) { patch({ commit: { ...snapshot.commit, error: /^\d{4}$/.test(snapshot.commit.ticket) ? '请输入不超过120个字符的提交说明，并删除控制字符。' : '请输入4位数字工单号。' } }); return }
    if (retry ? original.state !== 'COMMITTED' : original.state !== 'READY') return
    const message = retry ? undefined : commitPreview(snapshot)
    const ok = await perform<{ commitMessage: string | undefined }, TaskPublicationStatus>('提交任务变更', { endpoint: `/tasks/${encodeURIComponent(id)}/publication`, method: 'POST', body: { commitMessage: message } },
      identity => api.publishTask(id, identity.body.commitMessage), async (receipt, context) => {
        if (original.branch && receipt.branch !== original.branch || receipt.commitSha && message !== undefined && receipt.commitMessage !== message || retry && receipt.commitSha !== original.commitSha) throw new Error('发布回执与原分支、提交正文或继续推送的原提交不一致。')
        await publicationRead(receipt, context)
        context.apply(() => patch({ commit: emptyCommit }))
      }, async () => {
        const observed = await api.getTaskPublication(id)
        if (original.branch && observed.branch !== original.branch || !observed.commitSha) return { kind: 'UNCONFIRMED' }
        const proved = retry ? observed.commitSha === original.commitSha && deliveryRank(observed.deliveryState) > deliveryRank(original.deliveryState)
          : observed.commitMessage === message && observed.commitSha !== original.commitSha && deliveryRank(observed.deliveryState) >= 1
        return proved ? { kind: 'ACCEPTED', receipt: observed } : { kind: 'UNCONFIRMED' }
      })
    if (ok && state().publication?.state === 'LOCAL_SYNC_CONFLICT') await openConflict()
  }
  async function reconcile() {
    const pub = state().publication
    if (!pub?.reconciliationAvailable || pub.deliveryFinal || !['COMMITTED', 'PUSHED', 'MERGE_REQUEST_OPENED', 'MERGE_REQUEST_CLOSED'].includes(pub.deliveryState)) return
    await perform('检查合并状态', { endpoint: `/tasks/${encodeURIComponent(id)}/publication/reconcile`, method: 'POST', body: undefined }, () => api.reconcileTaskPublication(id), publicationRead,
      async () => { const next = await api.getTaskPublication(id); return next.commitSha === pub.commitSha && next.branch === pub.branch && next.lastCheckedAt && next.lastCheckedAt !== pub.lastCheckedAt ? { kind: 'ACCEPTED', receipt: next } : { kind: 'UNCONFIRMED' } })
  }
  function openMerge() {
    const pub = state().publication
    if (!pub || !publicationEligible(state().task) || locked() || pub.deliveryFinal || !['PUSHED', 'MERGE_REQUEST_CLOSED'].includes(pub.state)) return
    const targetBranch = pub.targetBranch ?? pub.targetBranches[0] ?? '', title = pub.commitMessage ?? state().task.title
    const description = `## 任务目标\n\n${state().task.goal}\n\n## 来源\n\nOpenCode Loopper 任务 ${id}`
    patch({ merge: { open: true, targetBranch, title, description, baseline: JSON.stringify([targetBranch, title, description]), error: '' } })
  }
  function changeMerge(changes: Partial<Pick<PublicationState['merge'], 'targetBranch' | 'title' | 'description'>>) { modify({ merge: { ...state().merge, ...changes } }) }
  async function createMerge() {
    const form = state().merge, publication = state().publication
    if (!publication || locked() || publication.deliveryFinal || !['PUSHED', 'MERGE_REQUEST_CLOSED'].includes(publication.state)) return false
    if (!form.targetBranch || !form.title.trim()) { patch({ merge: { ...form, error: !form.targetBranch ? '请选择目标分支。' : '请输入合并请求标题。' } }); return false }
    const body = { targetBranch: form.targetBranch, title: form.title, description: form.description }
    return perform('创建合并请求入口', { endpoint: `/tasks/${encodeURIComponent(id)}/publication/merge-request`, method: 'POST', body },
      identity => api.createTaskMergeRequestDraft(id, { ...identity.body }), async (receipt, context) => {
        if (receipt.targetBranch !== body.targetBranch || receipt.title !== body.title || receipt.description !== body.description || receipt.sourceBranch !== publication.branch) throw new Error('创建页回执与原分支或表单不一致。')
        const next = await api.getTaskPublication(id)
        if (!context.isCurrent()) return
        verifyPublication(next, publication)
        context.apply(() => projectPublication(next))
        context.apply(() => patch({ merge: { ...state().merge, draft: { ...receipt }, baseline: JSON.stringify([body.targetBranch, body.title, body.description]), error: '' }, notice: '合并请求创建页已准备；最终创建与合并仍由代码托管服务确认。' }))
      })
  }
  async function readConflict(session: Readonly<LocalSyncConflictSession>, preferredPath?: string, isCurrent = current) {
    if (!session.id || session.taskId !== id || !isCurrent()) throw new Error('冲突会话已离开原任务作用域。')
    const files = await api.getLocalSyncConflictFiles(id, session.id)
    if (!isCurrent()) throw new Error('原冲突读取已经失效。')
    const path = preferredPath && files.some(row => row.path === preferredPath) ? preferredPath : files[0]?.path ?? ''
    const content = path ? await api.getLocalSyncConflictContent(id, session.id, path) : undefined
    if (!isCurrent()) throw new Error('原冲突读取已经失效。')
    if (content && content.path !== path) throw new Error('文件响应与所选路径不一致。')
    return { files, path, content }
  }
  function applyConflict(session: LocalSyncConflictSession, result: Awaited<ReturnType<typeof readConflict>>) {
    const previous = state().conflict.session
    if (session.taskId !== id || previous?.id === session.id && session.version < previous.version) throw new Error('冲突会话不是当前任务或版本已经过期。')
    const merged = result.content?.mergedContent ?? result.content?.sourceContent ?? ''
    conflictPatch({ session, ...result, merged, baseline: merged, index: 0, baseOpen: false, error: '', loading: false })
  }
  async function openConflict() {
    if (locked()) return
    if (state().conflict.merged !== state().conflict.baseline) { conflictPatch({ open: true }); return }
    conflictPatch({ open: true, error: '', loading: true })
    const sessionId = state().publication?.conflictSessionId
    if (!sessionId) { await refreshConflict(); return }
    const ticket = core.ticket('conflict')
    try { const session = await api.getLocalSyncConflictSession(id, sessionId); if (!ticket.current()) return
      if (session.id !== sessionId) throw new Error('冲突会话回执与原会话身份不一致。')
      const result = await readConflict(session, undefined, ticket.current); if (ticket.current()) applyConflict(session, result)
    } catch (failure) { if (ticket.current()) conflictPatch({ error: userFacingError(failure, '无法读取同步冲突会话') }) }
    finally { if (ticket.current()) conflictPatch({ loading: false }) }
  }
  async function refreshConflict() {
    if (locked() || !core.canStartWrite() || state().conflict.merged !== state().conflict.baseline) return
    conflictPatch({ open: true, loading: true, error: '' })
    const prior = state().publication?.commitSha, previousId = state().publication?.conflictSessionId, previousSession = state().conflict.session
    await perform<undefined, LocalSyncConflictSession>('刷新同步预检', { endpoint: `/tasks/${encodeURIComponent(id)}/publication/local-conflicts`, method: 'POST', body: undefined }, () => api.createLocalSyncConflictSession(id), async (receipt, context) => {
      const result = await readConflict(receipt, undefined, context.isCurrent)
      context.apply(() => { applyConflict({ ...receipt }, result); const pub = state().publication; if (pub) projectPublication({ ...pub, conflictSessionId: receipt.id, conflictCount: receipt.conflictCount, resolvedCount: receipt.resolvedCount }) })
    }, async () => {
      const pub = await api.getTaskPublication(id)
      if (!current() || !pub.conflictSessionId || pub.commitSha !== prior) return { kind: 'UNCONFIRMED' }
      const receipt = await api.getLocalSyncConflictSession(id, pub.conflictSessionId)
      const newerSession = receipt.id !== previousId || previousSession?.id === receipt.id && receipt.version > previousSession.version
      return receipt.id === pub.conflictSessionId && receipt.taskId === id && newerSession && (!prior || receipt.taskCommit === prior) ? { kind: 'ACCEPTED', receipt } : { kind: 'UNCONFIRMED' }
    })
    if (current()) conflictPatch({ loading: false })
  }
  async function selectFile(path: string) {
    const session = state().conflict.session
    if (!session || locked() || state().conflict.merged !== state().conflict.baseline || !state().conflict.files.some(file => file.path === path)) return
    const ticket = core.ticket('file'); conflictPatch({ loading: true, error: '' })
    try { const content = await api.getLocalSyncConflictContent(id, session.id, path); if (!ticket.current() || state().conflict.session?.id !== session.id) return
      if (content.path !== path) throw new Error('文件响应与所选路径不一致。')
      const merged = content.mergedContent ?? content.sourceContent ?? ''
      conflictPatch({ path, content, merged, baseline: merged, index: 0, baseOpen: false })
    } catch (failure) { if (ticket.current()) conflictPatch({ error: userFacingError(failure, '无法读取冲突文件') }) }
    finally { if (ticket.current()) conflictPatch({ loading: false }) }
  }
  function editMerged(merged: string) { if (!state().conflict.loading && state().conflict.content?.contentType === 'TEXT') modify({ conflict: { ...state().conflict, merged } }) }
  function moveConflict(direction: number) { const count = parseMergeConflicts(state().conflict.merged).length; if (count) conflictPatch({ index: (state().conflict.index + direction + count) % count }) }
  function acceptBlock(side: MergeSide) {
    if (locked() || state().conflict.loading) return
    const conflict = state().conflict, merged = resolveMergeConflict(conflict.merged, conflict.index, side)
    const count = parseMergeConflicts(merged).length
    modify({ conflict: { ...conflict, merged, index: Math.max(0, Math.min(conflict.index, count - 1)) }, notice: '当前冲突块已载入合并结果，保存手工合并后才会生效。' })
  }
  async function saveResolution(resolution: Exclude<LocalSyncResolution, 'AUTO'>) {
    const { session, content, merged } = state().conflict
    if (!session || !content || state().conflict.loading || ['STALE', 'APPLYING', 'VERIFYING', 'APPLIED', 'ROLLBACK_FAILED'].includes(session.state) || locked()) return
    if (resolution === 'MANUAL' && (content.contentType !== 'TEXT' || unresolvedMarkers(merged))) { conflictPatch({ error: '合并结果仍有Git冲突标记，不能保存或同步。请删除全部标记行。' }); return }
    const body = { path: content.path, resolution, expectedVersion: content.version, ...(resolution === 'MANUAL' ? { content: merged } : {}) }
    await perform<{ path: string; resolution: Exclude<LocalSyncResolution, 'AUTO'>; expectedVersion: number; content?: string }, LocalSyncConflictContent>('保存冲突解决方案', { endpoint: `/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(session.id)}/resolution`, method: 'PUT', body, versions: { file: content.version } },
      identity => api.saveLocalSyncResolution(id, session.id, { ...identity.body }), async (receipt, context) => {
        if (!sameHashes(receipt, content) || receipt.version < content.version + 1 || receipt.resolution !== resolution || resolution === 'MANUAL' && receipt.mergedContent !== body.content) throw new Error('保存回执与原文件、版本或合并正文不一致。')
        const updated = await api.getLocalSyncConflictSession(id, session.id); if (!context.isCurrent()) return
        if (updated.id !== session.id) throw new Error('解决方案的读回会话身份不一致。')
        const result = await readConflict(updated, content.path, context.isCurrent); if (!context.isCurrent()) return
        if (!result.content || result.content.version < receipt.version || result.content.resolution !== resolution || resolution === 'MANUAL' && result.content.mergedContent !== body.content) throw new Error('方案已接受，但原文件读回尚未完成，请只重新读取。')
        context.apply(() => { applyConflict(updated, result); patch({ notice: '解决方案已保存。' }) })
      }, async () => {
        const receipt = await api.getLocalSyncConflictContent(id, session.id, content.path)
        return sameHashes(receipt, content) && receipt.version === content.version + 1 && receipt.resolution === resolution && (resolution !== 'MANUAL' || receipt.mergedContent === body.content) ? { kind: 'ACCEPTED', receipt } : { kind: 'UNCONFIRMED' }
      })
  }
  async function suggest() {
    const { session, content } = state().conflict
    if (!session || !content?.aiEligible || state().conflict.loading || locked()) return
    type Suggestion = { path: string; suggestion: string; automaticallySelected: boolean; version: number }
    await perform<{ path: string; expectedVersion: number }, Suggestion>('请求单文件AI建议', { endpoint: `/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(session.id)}/ai-suggestion`, method: 'POST', body: { path: content.path, expectedVersion: content.version }, versions: { file: content.version } },
      identity => api.suggestLocalSyncResolution(id, session.id, { ...identity.body }), async (receipt, context) => {
        if (receipt.path !== content.path || receipt.version < content.version + 1 || receipt.automaticallySelected) throw new Error('AI回执未能证明建议与原文件对应且尚未采用。')
        context.apply(() => conflictPatch({ content: { ...content, aiSuggestion: receipt.suggestion, version: receipt.version }, files: state().conflict.files.map(file => file.path === content.path ? { ...file, hasAiSuggestion: true, version: receipt.version } : file) }))
      }, async () => {
        const observed = await api.getLocalSyncConflictContent(id, session.id, content.path)
        // A resolution save also advances the version while retaining an old AI
        // suggestion. Only changed suggestion bytes can prove new AI output here.
        return sameHashes(observed, content) && observed.version === content.version + 1 && observed.aiSuggestion !== undefined && observed.aiSuggestion !== content.aiSuggestion && observed.resolution === content.resolution && observed.mergedContent === content.mergedContent ? { kind: 'ACCEPTED', receipt: { path: observed.path, suggestion: observed.aiSuggestion, automaticallySelected: false, version: observed.version } } : { kind: 'UNCONFIRMED' }
      })
  }
  function loadSuggestion() { const suggestion = state().conflict.content?.aiSuggestion; if (suggestion !== undefined) editMerged(suggestion) }
  async function applyLocal() {
    const session = state().conflict.session
    if (!session || state().conflict.loading || !['READY', 'ROLLED_BACK'].includes(session.state) || session.resolvedCount !== session.conflictCount || publicationDirty(state()) || unresolvedMarkers(state().conflict.merged)) return
    await perform<{ confirmed: boolean; expectedVersion: number }, LocalSyncConflictSession>('确认合并并同步', { endpoint: `/tasks/${encodeURIComponent(id)}/publication/local-conflicts/${encodeURIComponent(session.id)}/apply`, method: 'POST', body: { confirmed: true, expectedVersion: session.version }, versions: { session: session.version } },
      identity => api.applyLocalSyncConflict(id, session.id, { ...identity.body }), async (receipt, context) => {
        if (receipt.taskId !== id || receipt.id !== session.id || receipt.version < session.version) throw new Error('同步回执与原会话或版本不一致。')
        context.apply(() => conflictPatch({ session: { ...receipt }, error: receipt.state === 'APPLIED' ? '' : userFacingError(receipt.errorMessage, '同步未完成，原验收证据仍保留。') }))
        if (receipt.state === 'APPLIED') {
          const pub = await api.getTaskPublication(id); if (!context.isCurrent()) return
          if (ports.refresh) { await ports.refresh(); if (!context.isCurrent()) return }
          context.apply(() => { projectPublication(pub); conflictPatch({ open: false }); patch({ notice: '冲突方案已验证并同步到源项目。' }) })
        }
      }, async () => { const observed = await api.getLocalSyncConflictSession(id, session.id); return observed.taskId === id && observed.id === session.id && observed.version > session.version && ['APPLIED', 'ROLLED_BACK', 'ROLLBACK_FAILED', 'STALE'].includes(observed.state) ? { kind: 'ACCEPTED', receipt: observed } : { kind: 'UNCONFIRMED' } })
  }
  function discard(surface: 'commit' | 'merge' | 'conflict') {
    if (locked()) return
    if (surface === 'commit') patch({ commit: emptyCommit })
    else if (surface === 'merge') patch({ merge: emptyMerge })
    else conflictPatch({ open: false, merged: state().conflict.baseline })
    patch({ draftRevision: state().draftRevision + 1 })
  }
  function publicOwner() {
    return { ...base, load, openCommit, changeTicket, changeSubject, submitCommit, reconcile, openMerge, changeMerge, createMerge, openConflict, refreshConflict, selectFile, editMerged, moveConflict, acceptBlock, saveResolution, suggest, loadSuggestion, applyLocal, discard,
      hide(surface: 'commit' | 'merge' | 'conflict') { patch({ [surface]: { ...state()[surface], open: false } }) },
      show(surface: 'commit' | 'merge' | 'conflict') { patch({ [surface]: { ...state()[surface], open: true } }) },
      toggleBase() { conflictPatch({ baseOpen: !state().conflict.baseOpen }) },
      updateTask(next: Task) { if (next.id === id && (next.version ?? 0) >= (state().task.version ?? 0) && JSON.stringify(next) !== JSON.stringify(state().task)) patch({ task: next }) },
      async recover() { await recoverCurrent().catch(() => undefined) },
      reportOpenFailure(message: string) { patch({ merge: { ...state().merge, error: message } }) },
    }
  }
  owner = publicOwner()
  return owner
}
export type TaskPublicationOwner = ReturnType<typeof createTaskPublicationOwner>
