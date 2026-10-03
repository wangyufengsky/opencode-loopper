import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { UiActionButton, UiConfirmDialog, UiContextPanel } from '@/foundation/components'
import type { UiActionKey } from '@/foundation/semanticRegistry'
import { ReadOnlyCode } from '@/pages/w3/shared/ReadOnlyCode'
import { CommandNotice, ReadNotice, closePolicy, useProtectedOwner, useW4Owner } from '../shared/parts'
import type { TaskPanelProps } from '../shared/types'
import type { TaskPublicationStatus } from '@/types/domain'
import { changedLineNumbers, countChangedGroups, languageForPath, parseMergeConflicts } from '@/utils/mergeView'
import { userFacingError } from '@/utils/displayLabels'
import { createTaskPublicationOwner, commitPreview, deliveryLabel, publicationDirty, publicationEligible, unresolvedMarkers, type PublicationState } from './controller'
import { MergeEditor, type MergeEditorHandle } from './MergeEditor'
import './publication.css'

type Confirmation = { title: string; description: string; action: UiActionKey; current(): boolean; run(): void }
function confirmationIdentity(state: Readonly<PublicationState>) {
  const { task, publication, conflict } = state, content = conflict.content
  return JSON.stringify([task.id, task.version, task.status, task.executionResult, task.executionMode, task.updatedAt, state.draftRevision, publication,
    conflict.session?.id, conflict.session?.version, conflict.session?.state, conflict.path, content?.path, content?.version, content?.baseHash, content?.sourceHash, content?.taskHash, content?.resolution])
}
const disabled = (value: boolean, reason = '当前操作不可用，请先核对原任务状态。') => value ? { kind: 'disabled' as const, reason } : { kind: 'enabled' as const }
function externalUrl(value?: string) { if (!value) return undefined; try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : undefined } catch { return undefined } }
function failedChecks(value?: string) {
  try { const data = JSON.parse(value ?? '{}') as { checks?: unknown[] }; return (Array.isArray(data.checks) ? data.checks : []).flatMap(item => {
    if (!item || typeof item !== 'object') return []
    const check = item as Record<string, unknown>, evidence = check.evidence && typeof check.evidence === 'object' ? check.evidence as Record<string, unknown> : {}
    return check.passed === false ? [{ type: String(check.type ?? '检查'), path: String(check.path ?? ''), summary: String(check.summary ?? '验证失败'), output: typeof evidence.output === 'string' ? evidence.output : undefined }] : []
  }) } catch { return [] }
}
export function TaskPublicationActions(props: TaskPanelProps & { onDeliveryState?: (value: TaskPublicationStatus['deliveryState']) => void }) {
  const latest = useRef(props); latest.current = props
  const candidate = useMemo(() => createTaskPublicationOwner(props.task, { canStartWrite: caller => latest.current.parent.canStartWrite(caller), refresh: () => latest.current.parent.refresh() }), [props.task.id])
  const owner = useProtectedOwner(candidate), state = useW4Owner(props.page, owner, props.parent)
  const trigger = useRef<HTMLElement | null>(null), confirmationTrigger = useRef<HTMLElement | null>(null), editor = useRef<MergeEditorHandle>(null)
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)
  useLayoutEffect(() => { owner.updateTask(props.task) }, [owner, props.task])
  useLayoutEffect(() => { if (state.publication && state.task.id === props.task.id) props.onDeliveryState?.(state.publication.deliveryState) }, [props.onDeliveryState, props.task.id, state.publication, state.task.id])
  const writable = publicationEligible(state.task)
  const pub = state.publication, locked = !['IDLE', 'SETTLED'].includes(state.command.phase), local = !!pub && !pub.remoteName
  const selectTrigger = () => { trigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null }
  const confirm = (title: string, description: string, action: UiActionKey, run: () => void) => {
    confirmationTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const token = owner.capture(), identity = confirmationIdentity(owner.getSnapshot())
    const current = () => token.isCurrent() && !owner.getSnapshot().command.busy && ['IDLE', 'SETTLED'].includes(owner.getSnapshot().command.phase)
      && identity === confirmationIdentity(owner.getSnapshot()) && latest.current.parent.canStartWrite(owner)
    setConfirmation({ title, description, action, current, run })
  }
  const openSurface = (run: () => void) => { selectTrigger(); run() }
  async function openCreationPage() {
    const url = externalUrl(owner.getSnapshot().merge.draft?.creationUrl)
    if (!url) { owner.reportOpenFailure('创建地址不可用，请保留原表单并核对。'); return }
    if (window.open(url, '_blank', 'noopener,noreferrer')) { owner.hide('merge'); return }
    try { if (!navigator.clipboard?.writeText) throw new Error('无法复制'); await navigator.clipboard.writeText(url); if (owner.capture().isCurrent()) owner.reportOpenFailure('浏览器阻止了新窗口，创建地址已复制到剪贴板。') }
    catch { if (owner.capture().isCurrent()) owner.reportOpenFailure('浏览器阻止了新窗口，请使用下面的创建页链接。') }
  }
  function selectFile(path: string) {
    if (state.conflict.merged !== state.conflict.baseline) confirm('放弃当前文件的未保存合并？', '原已保存方案仍保留，只有本地编辑会被放弃。', 'ui.discardChanges', () => { owner.discard('conflict'); owner.show('conflict'); void owner.selectFile(path) })
    else void owner.selectFile(path)
  }
  const content = state.conflict.content, session = state.conflict.session, conflicts = parseMergeConflicts(state.conflict.merged)
  const changed = content ? countChangedGroups(changedLineNumbers(content.baseContent ?? '', content.sourceContent ?? '')) + countChangedGroups(changedLineNumbers(content.baseContent ?? '', content.taskContent ?? '')) : 0
  const canResolve = writable && !locked && !state.conflict.loading && !!session && !['STALE', 'APPLYING', 'VERIFYING', 'APPLIED', 'ROLLBACK_FAILED'].includes(session.state)
  const canApply = writable && !state.conflict.loading && !!session && ['READY', 'ROLLED_BACK'].includes(session.state) && session.resolvedCount === session.conflictCount && !publicationDirty(state) && !unresolvedMarkers(state.conflict.merged)
  return <section className="w4-publication" aria-label="任务代码发布" data-task-publication={owner.identity.id}>
    <ReadNotice error={state.error} loading={state.loading} retry={() => void owner.load()} />
    <CommandNotice command={state.command} recover={() => void owner.recover()} />
    {state.notice && <p role="status">{state.notice}</p>}
    {publicationEligible(state.task) && <div className="w4-publication-actions">
      <span>交付：{deliveryLabel(pub?.deliveryState)}</span>
      {!pub ? <p>读取提交状态</p> : <>
        {pub.state === 'READY' && <UiActionButton actionKey={local ? 'publication.commitLocal' : 'publication.commit'} availability={disabled(locked)} onAction={() => openSurface(() => void owner.openCommit())} />}
        {pub.state === 'COMMITTED' && <UiActionButton actionKey={local ? 'publication.confirmLocal' : 'publication.push'} availability={disabled(locked)} onAction={() => confirm(local ? '继续确认本地任务分支提交？' : '继续推送任务分支？', '原提交会保留，仅继续原任务分支的发布。', local ? 'publication.confirmLocal' : 'publication.push', () => void owner.submitCommit(true))} />}
        {pub.state === 'LOCAL_SYNC_CONFLICT' && <UiActionButton actionKey="publication.resolveLocal" target={`${pub.conflictCount}个文件`} availability={disabled(locked)} onAction={() => openSurface(() => void owner.openConflict())} />}
        {pub.state === 'SYNCED_LOCAL' && <p>已提交本地任务分支</p>}
        {['PUSHED', 'MERGE_REQUEST_CLOSED'].includes(pub.state) && <UiActionButton actionKey={pub.creationRequestedAt || pub.state === 'MERGE_REQUEST_CLOSED' ? 'publication.reopenMergeRequest' : 'publication.createMergeRequest'} availability={disabled(locked)} onAction={() => openSurface(owner.openMerge)} />}
        {pub.state === 'MERGE_REQUEST_OPENED' && <UiActionButton actionKey="publication.viewMergeRequest" availability={disabled(!externalUrl(pub.mergeRequest?.url))} onAction={() => { const url = externalUrl(pub.mergeRequest?.url); if (url) window.open(url, '_blank', 'noopener,noreferrer') }} />}
        {['PUSHED', 'MERGE_REQUEST_OPENED', 'MERGE_REQUEST_CLOSED', 'COMMITTED'].includes(pub.state) && <UiActionButton actionKey="publication.checkMerge" availability={disabled(locked || !pub.reconciliationAvailable, '没有可用的合并状态检查权限。')} onAction={() => void owner.reconcile()} />}
        {pub.state === 'MERGE_REQUEST_CLOSED' && <p>合并请求已关闭</p>}{pub.state === 'MERGED' && <p>已合并</p>}
        {['UNAVAILABLE', 'NO_CHANGES'].includes(pub.state) && <p>{userFacingError(pub.reason, '当前任务不可提交')}</p>}
        {pub.provider === 'GITLAB' && ['PUSHED', 'MERGE_REQUEST_OPENED', 'MERGE_REQUEST_CLOSED'].includes(pub.deliveryState) && !pub.reconciliationAvailable && <p>未配置GitLab Token，无法自动确认合并状态</p>}
        {pub.provider === 'GITHUB' && ['PUSHED', 'MERGE_REQUEST_OPENED', 'MERGE_REQUEST_CLOSED'].includes(pub.deliveryState) && <p>GitHub合并状态暂未接入自动确认</p>}
        {pub.lastCheckError && <p role="alert">最近检查失败：{userFacingError(pub.lastCheckError, '无法检查合并状态')}</p>}
      </>}
    </div>}
    <UiContextPanel open={state.commit.open} title={local ? '提交本地任务分支' : '提交任务变更'} returnFocus={trigger} closePolicy={closePolicy(owner)} onConfirmClose={() => owner.discard('commit')} onClose={() => owner.hide('commit')}>
      <p>{state.commit.suggesting ? '正在生成提交说明' : state.commit.aiSuggested ? 'AI已根据任务目标和实际差异生成默认说明' : '请核对提交说明与实际改动'}</p>
      <label>4位数字工单号<input aria-label="4 位数字工单号" inputMode="numeric" maxLength={4} disabled={locked || !writable} value={state.commit.ticket} onChange={event => owner.changeTicket(event.target.value)} /></label>
      <label>AI提交说明<textarea aria-label="AI 提交说明" disabled={locked || !writable || state.commit.suggesting} value={state.commit.subject} onChange={event => owner.changeSubject(event.target.value)} /></label>
      <p>{Array.from(state.commit.subject.replace(/\s+/g, ' ').trim()).length} / 120</p><p>最终提交信息：<code>{commitPreview(state)}</code></p>
      {state.commit.error && <p role="alert">{state.commit.error}</p>}
      <UiActionButton actionKey={local ? 'publication.commitLocal' : 'publication.commit'} availability={disabled(locked || !writable || state.commit.suggesting)} onAction={() => confirm(local ? '确认提交本地任务分支？' : '确认提交并推送？', local ? '将提交原项目目录中当前任务分支的全部已验收变更，并保留为本地提交。' : '将提交原项目目录中当前任务分支的全部已验收变更并推送该分支。', local ? 'publication.commitLocal' : 'publication.commit', () => void owner.submitCommit())} />
    </UiContextPanel>
    <UiContextPanel open={state.merge.open} title="创建合并请求" returnFocus={trigger} closePolicy={closePolicy(owner)} onConfirmClose={() => owner.discard('merge')} onClose={() => owner.hide('merge')}>
      <p><code>{pub?.branch}</code> → <code>{state.merge.targetBranch || '选择目标分支'}</code></p>
      <label>目标分支<select disabled={locked || !writable} value={state.merge.targetBranch} onChange={event => owner.changeMerge({ targetBranch: event.target.value })}><option value="">选择目标分支</option>{pub?.targetBranches.map(branch => <option key={branch}>{branch}</option>)}</select></label>
      <label>合并请求标题<input disabled={locked || !writable} value={state.merge.title} maxLength={160} onChange={event => owner.changeMerge({ title: event.target.value })} /></label>
      <label>说明<textarea disabled={locked || !writable} value={state.merge.description} maxLength={8000} onChange={event => owner.changeMerge({ description: event.target.value })} /></label>
      <p>打开{pub?.provider === 'GITHUB' ? 'GitHub Pull Request' : 'GitLab Merge Request'}创建页，由你复核并完成创建。</p>
      {state.merge.error && <p role="alert">{state.merge.error}</p>}
      <UiActionButton actionKey="publication.createMergeRequest" availability={disabled(locked || !writable)} onAction={() => void owner.createMerge().then(ok => { if (ok) void openCreationPage() })} />
      {state.merge.draft && externalUrl(state.merge.draft.creationUrl) && <a href={externalUrl(state.merge.draft.creationUrl)} target="_blank" rel="noopener noreferrer">打开已确认创建页</a>}
    </UiContextPanel>
    <UiContextPanel open={state.conflict.open} title="本地源代码同步冲突解决中心" expanded returnFocus={trigger} closePolicy={closePolicy(owner)} onConfirmClose={() => owner.discard('conflict')} onClose={() => owner.hide('conflict')}>
      <ReadNotice error={state.conflict.error} loading={state.conflict.loading} />
      {session && <><p>{session.sourceRoot}</p><p>{session.resolvedCount} / {session.conflictCount} 已解决</p>
        {session.state === 'STALE' && <p role="alert">源项目已变化，会话已过期。刷新后重新计算三方内容，旧方案不会写入。</p>}
        {session.state === 'ROLLED_BACK' && <p role="alert">{userFacingError(session.errorMessage, '上次验证或写入失败，已自动恢复')}</p>}
        {session.state === 'ROLLBACK_FAILED' && <p role="alert">自动恢复失败，未标记同步成功。备份：{session.backupDir}</p>}
        {['APPLYING', 'VERIFYING'].includes(session.state) && <p role="status">{session.state === 'APPLYING' ? '正在写入源项目' : '正在按执行规范验证，失败时自动恢复'}</p>}
      </>}
      <div className="w4-conflict-files" aria-label="冲突文件">{state.conflict.files.map(file => <button type="button" key={file.path} aria-pressed={file.path === state.conflict.path} disabled={locked} onClick={() => selectFile(file.path)}>{file.path} · {file.resolved ? '已解决' : '待解决'}</button>)}</div>
      {content && <>
        <div className="w4-publication-actions"><UiActionButton actionKey="publication.acceptSource" availability={disabled(!canResolve)} onAction={() => void owner.saveResolution('SOURCE')} /><UiActionButton actionKey="publication.acceptTask" availability={disabled(!canResolve)} onAction={() => void owner.saveResolution('TASK')} />
          {content.contentType === 'TEXT' && <UiActionButton actionKey="publication.saveMerge" availability={disabled(!canResolve)} onAction={() => void owner.saveResolution('MANUAL')} />}
          {content.aiEligible && <UiActionButton actionKey="publication.requestSuggestion" availability={disabled(!canResolve)} onAction={() => confirm('发送单文件内容给当前模型？', '将发送此文件受限大小的Base、源项目、任务内容和任务目标。建议不会自动选中或应用。', 'publication.requestSuggestion', () => void owner.suggest())} />}
        </div>
        {content.contentType === 'TEXT' ? <>
          <p>{content.path} · {languageForPath(content.path).toUpperCase()} · {changed}处变化 · {conflicts.length}个未解决冲突</p>
          <div className="w4-publication-actions"><UiActionButton actionKey="ui.previous" target="冲突" availability={disabled(!conflicts.length)} onAction={() => { owner.moveConflict(-1); editor.current?.scrollToLine(parseMergeConflicts(owner.getSnapshot().conflict.merged)[owner.getSnapshot().conflict.index]?.startLine ?? 1) }} /><span>{conflicts.length ? `${state.conflict.index + 1}/${conflicts.length}` : '0/0'}</span><UiActionButton actionKey="ui.next" target="冲突" availability={disabled(!conflicts.length)} onAction={() => { owner.moveConflict(1); editor.current?.scrollToLine(parseMergeConflicts(owner.getSnapshot().conflict.merged)[owner.getSnapshot().conflict.index]?.startLine ?? 1) }} /><UiActionButton actionKey={state.conflict.baseOpen ? 'ui.collapse' : 'ui.open'} target="Base共同祖先" onAction={owner.toggleBase} /></div>
          <div className="w4-merge-grid"><article><h3>源项目（左）</h3><code>{content.sourceHash.slice(0, 10)}</code><ReadOnlyCode content={content.sourceContent ?? '（文件不存在）'} language={languageForPath(content.path)} label="源项目内容" highlightLines={changedLineNumbers(content.baseContent ?? '', content.sourceContent ?? '')} /></article>
            <article><h3>合并结果</h3><div className="w4-publication-actions"><UiActionButton actionKey="publication.acceptSourceBlock" availability={disabled(!canResolve || !conflicts.length)} onAction={() => owner.acceptBlock('source')} /><UiActionButton actionKey="publication.acceptTaskBlock" availability={disabled(!canResolve || !conflicts.length)} onAction={() => owner.acceptBlock('task')} /></div><MergeEditor ref={editor} path={content.path} baseline={content.baseContent ?? ''} value={state.conflict.merged} disabled={!canResolve} activeIndex={state.conflict.index} onChange={owner.editMerged} /></article>
            <article><h3>任务版本（右）</h3><code>{content.taskHash.slice(0, 10)}</code><ReadOnlyCode content={content.taskContent ?? '（文件不存在）'} language={languageForPath(content.path)} label="任务内容" highlightLines={changedLineNumbers(content.baseContent ?? '', content.taskContent ?? '')} /></article></div>
          {state.conflict.baseOpen && <ReadOnlyCode content={content.baseContent ?? '（文件不存在）'} language={languageForPath(content.path)} label="Base共同祖先" />}
          {unresolvedMarkers(state.conflict.merged) && <p role="alert">合并结果仍有Git冲突标记，不能保存或同步。</p>}
          {content.aiSuggestion !== undefined && <section><h3>AI建议（尚未采用）</h3><UiActionButton actionKey="publication.loadSuggestion" availability={disabled(!canResolve)} onAction={owner.loadSuggestion} /><ReadOnlyCode content={content.aiSuggestion} language={languageForPath(content.path)} label="AI建议原文" /></section>}
        </> : <section><h3>{content.contentType === 'BINARY' ? '二进制文件' : '超大文本文件'}不在浏览器中打开</h3><p>只能选择源项目或任务版本；哈希会在应用前重新核对。</p><p>BASE {content.baseHash}</p><p>SOURCE {content.sourceHash}</p><p>TASK {content.taskHash}</p></section>}
      </>}
      {!!failedChecks(session?.verificationEvidence).length && <section><h3>上次发布验证失败详情</h3>{failedChecks(session?.verificationEvidence).map((check, index) => <details key={index}><summary>{check.summary}</summary><p>{check.type} {check.path}</p>{check.output && <pre>{check.output}</pre>}</details>)}</section>}
      <div className="w4-publication-actions">{session?.state === 'STALE' ? <UiActionButton actionKey="publication.refreshConflicts" availability={disabled(locked)} onAction={() => confirm('刷新同步预检？', '将按当前源项目重新计算三方内容，未保存修改需先明确放弃。', 'publication.refreshConflicts', () => { owner.discard('conflict'); owner.show('conflict'); void owner.refreshConflict() })} /> : <UiActionButton actionKey="publication.applyLocal" availability={disabled(locked || !canApply)} onAction={() => confirm('确认合并并同步？', `将把${session?.conflictCount ?? 0}个已解决文件同步到源项目并执行原验收计划；失败时自动恢复。`, 'publication.applyLocal', () => void owner.applyLocal())} />}</div>
    </UiContextPanel>
    <UiConfirmDialog open={!!confirmation} title={confirmation?.title ?? '确认操作'} confirmActionKey={confirmation?.action ?? 'ui.save'} returnFocus={confirmationTrigger} policy={locked ? { kind: 'block', reason: '原操作尚未结清，请先核对。' } : confirmation && !confirmation.current() ? { kind: 'block', reason: '原任务、发布状态或草稿已变化，请取消后重新确认。' } : { kind: 'allow' }} onCancel={() => setConfirmation(null)} onConfirm={() => { const choice = confirmation; if (!choice?.current()) return; setConfirmation(null); choice.run() }}>{confirmation?.description}</UiConfirmDialog>
  </section>
}
