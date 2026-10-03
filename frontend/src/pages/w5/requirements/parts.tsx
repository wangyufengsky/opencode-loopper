import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import type { SnapshotController } from '@/foundation/contracts/controller'
import type { RequirementParent } from './core'
import { useLeaveGuard, useOwnerSnapshot, useRetainedOwner, type W2PageProps } from '@/pages/w2/shared'
import { UiActionButton, UiConfirmDialog, UiContextPanel, type UiContextPanelProps } from '@/foundation/components'
import type { LeaveDecision } from '@/foundation/contracts/types'
import type { UiActionKey } from '@/foundation/semanticRegistry'
export { CommandNotice, ReadNotice, closePolicy, useProtectedOwner } from '@/pages/w3/templates/runs/parts'

export function useRequirementOwner<S>(props: W2PageProps, owner: SnapshotController<S>, parent?: RequirementParent) {
  const previous = useRef(owner)
  useLayoutEffect(() => { if (previous.current !== owner && previous.current.canLeave().kind === 'ALLOW') previous.current.retire(true); previous.current = owner }, [owner])
  useRetainedOwner(props, owner, () => { owner.retire(true) })
  useLeaveGuard(props, request => owner.canLeave(request))
  useLayoutEffect(() => parent?.registerChild(owner), [owner, parent])
  useLayoutEffect(() => owner.attachView(), [owner])
  useLayoutEffect(() => {
    const unload = (event: BeforeUnloadEvent) => { if (owner.canLeave().kind !== 'ALLOW') { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', unload)
    return () => window.removeEventListener('beforeunload', unload)
  }, [owner])
  return useOwnerSnapshot(owner)
}
export function Action({ action, onClick, disabled, busy, danger, target }: { action: UiActionKey; onClick: () => void; disabled?: boolean; busy?: boolean; danger?: boolean; target?: string }) {
  return <UiActionButton actionKey={action} target={target} onAction={onClick} busy={busy} variant={danger ? 'danger' : 'default'} availability={disabled ? { kind: 'disabled', reason: '请先结清原操作、补齐输入并核对当前许可。' } : { kind: 'enabled' }} />
}
export function Labeled({ label, children }: { label: string; children: ReactNode }) { return <label className="w5-field"><span>{label}</span>{children}</label> }
/** The owner revision, rather than a stale open modal, grants discard of an ordinary draft. */
export function RequirementContextPanel({ readPolicy, discard, ...props }: Omit<UiContextPanelProps, 'closePolicy' | 'onConfirmClose'> & { readPolicy(): LeaveDecision; discard(): void }) {
  const [confirmation, setConfirmation] = useState<Extract<LeaveDecision, { kind: 'CONFIRM_DISCARD' }>>()
  const current = readPolicy(), stale = !!confirmation && current.kind === 'CONFIRM_DISCARD' && current.draftRevision !== confirmation.draftRevision
  const reason = current.kind === 'BLOCK' ? current.reason : stale ? '草稿已经变化，请留在当前面板重新确认。' : ''
  return <><UiContextPanel {...props} closePolicy={current.kind === 'BLOCK' ? { kind: 'block', reason: current.reason } : { kind: 'allow' }} onClose={() => { const policy = readPolicy(); if (policy.kind === 'CONFIRM_DISCARD') setConfirmation(policy); else if (policy.kind === 'ALLOW') props.onClose() }} />
    <UiConfirmDialog open={props.open && !!confirmation} title="放弃当前修改？" confirmActionKey="ui.discardChanges" policy={reason ? { kind: 'block', reason } : { kind: 'allow' }} onCancel={() => setConfirmation(undefined)} onConfirm={() => { const latest = readPolicy(); if (latest.kind === 'BLOCK' || latest.kind === 'CONFIRM_DISCARD' && latest.draftRevision !== confirmation?.draftRevision) return; setConfirmation(undefined); discard() }}>{confirmation?.description}</UiConfirmDialog></>
}
