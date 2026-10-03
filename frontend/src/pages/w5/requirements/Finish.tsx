import { useEffect, useMemo, useState } from 'react'
import { UiConfirmDialog } from '@/foundation/components'
import { workflowFinishLabel } from '@/utils/displayLabels'
import type { WorkflowRequirementState } from '@/types/domain'
import type { W2PageProps } from '@/pages/w2/shared'
import type { RequirementController } from './controller'
import { createFinishController } from './finishController'
import { Action, CommandNotice, Labeled, ReadNotice, useRequirementOwner } from './parts'

export function FinishPanel({ page, parent, controller, visible = true, version, state }: { page: W2PageProps; parent?: RequirementController; controller?: ReturnType<typeof createFinishController>; visible?: boolean; version: number; state: WorkflowRequirementState }) {
  const owner = useMemo(() => controller ?? createFinishController(parent?.id ?? 'req', { version, state }), [controller, parent?.id]), s = useRequirementOwner(page, owner, parent)
  useEffect(() => { if (parent) owner.bindParent(parent); owner.updateContext({ version, state }) }, [owner, parent, version, state])
  const [discardRevision, setDiscardRevision] = useState<number | null>(null)
  const intent = s.value?.intent, ending = !!intent && !intent.finalizedAt, disabled = owner.locked() || s.loading || !owner.available() || !!s.error || !!parent && !parent.canStartWrite(owner)
  if (!visible && !intent && !s.open && !s.error && !s.command.error && !owner.locked()) return null
  return <section className="w5-section" aria-label="结束需求">
    <CommandNotice command={s.command} recover={() => { void owner.recover() }} /><ReadNotice error={s.error} loading={s.loading} retry={() => { void owner.refresh() }} />
    {intent ? <><strong>{ending ? '正在结束需求' : workflowFinishLabel(intent.targetState)}</strong><p>{ending ? '正在确认活动节点停止并归还工作目录。停止状态未确认前会保留在此状态。' : '此结果来自用户决定，节点原有交付物、检查与审查结论均保留。'}</p><p>结束原因：{intent.reason}</p>{ending && <p>等待收束：{s.value!.pending.attempts} 次节点执行，{s.value!.pending.resources} 项运行或目录记录。可点击节点查看恢复操作。</p>}<Action action="ui.refresh" target="结束状态" busy={s.loading} onClick={() => { void owner.refresh() }} /></> : s.open ? <form onSubmit={event => { event.preventDefault(); void owner.submit() }}>
      <h3>提前结束需求</h3><p>停止后续执行，并收束活动节点。结束结果与成果提交分别处理。</p><fieldset disabled={disabled} className="w5-fields">
        <Labeled label="结束结果"><select aria-label="结束结果" value={s.target} onChange={event => owner.change({ target: event.target.value as 'CANCELLED' | 'FAILED' | 'COMPLETED' })}><option value="CANCELLED">取消需求</option><option value="FAILED">人工认定失败</option><option value="COMPLETED">人工认定成功</option></select></Labeled>
        <Labeled label="结束原因"><textarea value={s.reason} maxLength={4000} rows={3} required onChange={event => owner.change({ reason: event.target.value })} /></Labeled>
      </fieldset>{s.target === 'COMPLETED' && <p>人工认定成功保留现有失败与审查意见，不表示未执行的检查已经通过。</p>}<Action action="workflow.confirmFinish" disabled={disabled || !s.reason.trim()} busy={s.command.busy} danger onClick={() => { void owner.submit() }} /><Action action="nav.back" disabled={owner.locked()} onClick={() => { const policy = owner.canLeave(); if (policy.kind === 'ALLOW') owner.discard(); else if (policy.kind === 'CONFIRM_DISCARD') setDiscardRevision(policy.draftRevision) }} />
    </form> : owner.available() && visible ? <Action action="workflow.finish" danger disabled={disabled} onClick={owner.show} /> : null}
    <UiConfirmDialog open={discardRevision !== null} title="放弃结束需求的输入？" confirmActionKey="ui.discardChanges" policy={owner.locked() || discardRevision !== s.draftRevision ? { kind: 'block', reason: '原操作或草稿已经变化，请重新核对。' } : { kind: 'allow' }} onCancel={() => setDiscardRevision(null)} onConfirm={() => { if (!owner.locked() && discardRevision === s.draftRevision) owner.discard(); setDiscardRevision(null) }}>服务端需求及已有执行结果保持原状。</UiConfirmDialog>
  </section>
}
