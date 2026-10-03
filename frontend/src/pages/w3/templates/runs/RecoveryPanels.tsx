import { useLayoutEffect, useMemo, useRef } from 'react'
import { UiActionButton, UiConfirmDialog, UiContextPanel } from '@/foundation/components'
import { semanticName, SemanticIcon } from '@/foundation/semanticRegistry'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { templateBatchPurpose } from '@/utils/templateSessionLabels'
import { templateDiagnosticPhaseLabel } from '@/utils/displayLabels'
import { formatDateTime } from '@/utils/dateTime'
import { createBatchRecoveryOwner, createDiagnosticOwner, diagnosticSummary } from './recoveryOwners'
import { pendingCommand } from './core'
import type { RunController } from './runController'
import { CommandNotice, ReadNotice, selectionTrigger, useRunOwner } from './parts'

export function TemplateBatchRecoveryPanel({ kind, id, props, parent, controller, revision, active = false }: { kind: 'document' | 'task'; id: string; props: W2PageProps; parent?: RunController; revision?: string; active?: boolean; controller?: ReturnType<typeof createBatchRecoveryOwner> }) {
  const owner = useMemo(() => controller ?? createBatchRecoveryOwner(kind, id), [controller, kind, id]), s = useRunOwner(props, owner, parent)
  const previous = useRef(revision)
  useLayoutEffect(() => { owner.setActive(active) }, [owner, active])
  useLayoutEffect(() => { if (previous.current !== revision) { previous.current = revision; if (owner.viewCount()) void owner.load() } }, [owner, revision])
  const locked = pendingCommand(s.command)
  return <section className="w3-card" aria-label="批次恢复"><header><h2>批次恢复</h2><UiActionButton actionKey="ui.refresh" target="批次" busy={s.loading} onAction={() => { void owner.load() }} /></header><CommandNotice command={s.command} recover={() => { void owner.recover() }} /><ReadNotice error={s.error} loading={s.loading} retry={() => { void owner.load() }} />
    {s.environmentBlocked ? <p>运行环境暂不可用，已暂停派发，保留原会话等待核对。</p> : !s.ready && s.rows.length ? <p>失败批次已记录，停止与本轮完成核对前不能重新触发。{s.blockingBatches > 0 && `还有 ${s.blockingBatches} 个批次未确认结束。`}</p> : s.rows.length > 0 && <p>请选择失败批次，已完成结果保留。每次最多选择 100 个批次。</p>}
    {s.resumeAvailable && <UiActionButton actionKey="ui.retry" target="重新检查并恢复原批次" availability={locked ? { kind: 'disabled', reason: '原操作尚未确认。' } : { kind: 'enabled' }} onAction={() => { void owner.recheck() }} />}
    {s.ready && <label><input type="checkbox" checked={!!s.rows.length && s.selected.length === s.rows.length} disabled={locked || s.loading} onChange={() => owner.selectAll()} />选择已加载批次</label>}
    <ul>{s.rows.map(row => <li key={row.id}><label>{s.ready && <input type="checkbox" checked={s.selected.includes(row.id)} disabled={locked || s.loading} onChange={event => owner.select(row.id, event.target.checked)} />}<strong>{templateBatchPurpose(row.purpose)} · 第 {row.ordinal + 1} 批</strong></label><p>{row.errorMessage || '该批次已停止，尚未完成分析。'}</p></li>)}</ul>
    {s.cursor && <UiActionButton actionKey="ui.loadMore" target="失败批次" busy={s.loading} onAction={() => { void owner.load(true) }} />}
    {s.ready && <UiActionButton actionKey="template.retryBatches" target={`重新触发所选批次（${s.selected.length}）`} busy={s.command.busy} availability={locked || s.loading || !!s.error || !s.selected.length || s.selected.length > 100 ? { kind: 'disabled', reason: '请等待原操作核对，并选择服务端允许恢复的批次。' } : { kind: 'enabled' }} onAction={() => { void owner.retry() }} />}
  </section>
}
export function TemplateSessionDiagnosticsPanel({ taskId, active, props, parent, controller, onSelect }: { taskId: string; active: boolean; props: W2PageProps; parent?: RunController; controller?: ReturnType<typeof createDiagnosticOwner>; onSelect?: (session: string) => void }) {
  const owner = useMemo(() => controller ?? createDiagnosticOwner(taskId, active), [controller, taskId]), s = useRunOwner(props, owner, parent), trigger = useRef<HTMLElement | null>(null)
  useLayoutEffect(() => { owner.setActive(active) }, [owner, active])
  return <section className="w3-card" aria-label="批次运行诊断"><header><h2>批次运行诊断</h2><UiActionButton actionKey="ui.refresh" target="诊断状态" busy={s.loading} onAction={() => { void owner.load() }} /></header>
    <nav aria-label="批次诊断筛选">{(['ATTENTION', 'ACTIVE', 'ALL'] as const).map((filter, index) => <button key={filter} type="button" data-semantic="ui.filter" aria-label={semanticName('ui.filter', ['需要关注', '未完成', '全部批次'][index])} aria-pressed={s.filter === filter} disabled={s.command.busy} onClick={() => owner.changeFilter(filter)}>{['需要关注', '未完成', '全部批次'][index]}</button>)}</nav>
    <p>连接、有效进展与停止确认分别记录。</p><CommandNotice command={s.command} recover={() => { void owner.recover() }} /><ReadNotice error={s.error || s.detailError} loading={s.loading} />{s.notice && <p role="status">{s.notice}</p>}
    {!s.loading && !s.error && !s.items.length && <p>当前筛选没有批次。可切换“未完成”查看仍在执行的批次。</p>}
    {s.items.map(row => <article key={row.batchId}><header><strong>阶段 {row.stageOrdinal} · {templateBatchPurpose(row.purpose)} · 第 {row.ordinal} 批</strong><span>{templateDiagnosticPhaseLabel(row.phase)}</span></header><p>{row.reason}</p>
      {!!row.retryLimit && <p>本轮自动重试已用 {row.automaticRetries ?? 0}/{row.retryLimit} 次{row.nextRetryAt && `；下次重新分析：${formatDateTime(row.nextRetryAt)}`}</p>}{!!row.transportFailures && <p>连续 {row.transportFailures} 次未能完成检查；查询重试不计入自动重新分析次数。</p>}
      <dl><dt>最后检查 · {row.connected ? '连接正常' : '未确认连接'}</dt><dd>{row.observedAt ? formatDateTime(row.observedAt) : '暂无记录'}</dd><dt>最后活动</dt><dd>{row.lastActivityAt ? formatDateTime(row.lastActivityAt) : '暂无记录'}</dd><dt>最后有效进展</dt><dd>{row.lastProgressAt ? formatDateTime(row.lastProgressAt) : '暂无记录'}</dd>{row.acceptedAt && <><dt>结果接受时间</dt><dd>{formatDateTime(row.acceptedAt)}</dd></>}{row.stopConfirmedAt && <><dt>停止确认时间</dt><dd>{formatDateTime(row.stopConfirmedAt)}</dd></>}</dl>
      <div className="w3-actions">{row.sessionKey && onSelect && <UiActionButton actionKey="ui.open" target="对应会话" onAction={() => onSelect(row.sessionKey!)} />}
        <button type="button" data-semantic="selection.select" aria-label={semanticName('selection.select', `第 ${row.ordinal} 批诊断`)} aria-expanded={s.selected === row.batchId} onClick={() => { selectionTrigger(trigger); if (s.selected === row.batchId) owner.close(); else void owner.details(row.batchId) }}><SemanticIcon semanticKey="object.batch" />查看诊断详情</button>
        {row.canCheck && <UiActionButton actionKey="template.checkSession" availability={!owner.canAct(row, 'CHECK') ? { kind: 'disabled', reason: '请先恢复原操作。' } : { kind: 'enabled' }} onAction={() => owner.request(row, 'CHECK')} />}
        {row.canFinalize && <UiActionButton actionKey="template.finalizeSession" availability={!owner.canAct(row, 'FINALIZE') ? { kind: 'disabled', reason: '请先恢复原操作。' } : { kind: 'enabled' }} onAction={() => owner.request(row, 'FINALIZE')} />}
        {row.canStop && <UiActionButton actionKey="template.stopBatch" target="此批次" variant="danger" availability={!owner.canAct(row, 'STOP') ? { kind: 'disabled', reason: '请先恢复原操作。' } : { kind: 'enabled' }} onAction={() => owner.request(row, 'STOP')} />}
      </div>
    </article>)}
    {!!s.previous.length && <UiActionButton actionKey="ui.previous" onAction={() => owner.page(false)} />}{s.next && <UiActionButton actionKey="ui.next" onAction={() => owner.page(true)} />}
    <UiContextPanel open={!!s.selected} title="诊断详情" returnFocus={trigger} onClose={owner.close}>{s.detailLoading && <p>正在读取诊断详情…</p>}{s.detail && <><p>诊断摘要包含目录与会话标识，不含认证凭据或模型输出。</p><UiActionButton actionKey="ui.copy" target="诊断摘要" onAction={() => { void owner.copySummary() }} /><pre tabIndex={0}>{diagnosticSummary(taskId, s.detail)}</pre></>}</UiContextPanel>
    <UiConfirmDialog open={!!s.confirming} title="停止此批次" confirmActionKey="template.stopBatch" onConfirm={owner.confirm} onCancel={owner.cancelConfirmation}><p>只停止当前批次的会话。停止确认后可能需要单独重试，已完成批次保持不变。</p></UiConfirmDialog>
  </section>
}
