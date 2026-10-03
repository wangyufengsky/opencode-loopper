import { useLayoutEffect, useRef } from 'react'
import type { SnapshotController } from '@/foundation/contracts/controller'
import type { ClosePolicy } from '@/foundation/contracts/types'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { useLeaveGuard, useOwnerSnapshot, useRetainedOwner } from '@/pages/w2/shared'
import { UiActionButton } from '@/foundation/components'
import type { CommandState } from './core'
import type { RunController } from './runController'

/** Read projections cannot replace a controller that still owns user input or a write. */
export function useProtectedOwner<T extends { canLeave(): import('@/foundation/contracts/types').LeaveDecision }>(candidate: T): T {
  const retained = useRef(candidate)
  if (retained.current === candidate || retained.current.canLeave().kind === 'ALLOW') retained.current = candidate
  return retained.current
}
export function useRunOwner<S>(props: W2PageProps, owner: SnapshotController<S>, parent?: RunController) {
  const previous = useRef(owner)
  useLayoutEffect(() => { if (previous.current !== owner && previous.current.canLeave().kind === 'ALLOW') previous.current.retire(true); previous.current = owner }, [owner])
  useRetainedOwner(props, owner, () => { owner.retire(true) })
  useLeaveGuard(props, request => owner.canLeave(request))
  useLayoutEffect(() => parent?.registerChild(owner), [owner, parent])
  useLayoutEffect(() => owner.attachView(), [owner])
  return useOwnerSnapshot(owner)
}
export function closePolicy(owner: { canLeave(): import('@/foundation/contracts/types').LeaveDecision }): ClosePolicy {
  const decision = owner.canLeave()
  return decision.kind === 'BLOCK' ? { kind: 'block', reason: decision.reason } : decision.kind === 'CONFIRM_DISCARD' ? { kind: 'confirm', reason: decision.description } : { kind: 'allow' }
}
export function CommandNotice({ command, recover }: { command: CommandState; recover: () => void }) {
  if (command.phase === 'IDLE' || command.phase === 'SETTLED' && !command.error) return null
  return <section className="w3-critical" data-operation-phase={command.phase} role={command.error || command.phase === 'UNKNOWN' ? 'alert' : 'status'}>
    <p>{command.phase === 'SENDING' ? `${command.label}正在发送，请保留原操作。` : command.phase === 'UNKNOWN' ? `${command.label}结果尚未确认，请先恢复原操作。` : command.phase === 'ACCEPTED_READBACK' ? '写入已接受，请继续读取原结果。' : ''}{command.error}</p>
    {command.phase !== 'SETTLED' && (command.recovery === 'BLOCKED' ? <p>暂无安全恢复入口，原身份继续保留。</p> : <UiActionButton actionKey={command.accepted || command.recovery === 'READ_ORIGINAL' ? 'receipt.readOriginal' : 'receipt.retryOriginal'} busy={command.busy} onAction={recover} />)}
  </section>
}
export function ReadNotice({ error, loading, retry }: { error: string; loading?: boolean; retry?: () => void }) {
  return <>{error && <div className="w3-critical" role="alert">{error}{retry && <UiActionButton actionKey="ui.retry" busy={loading} onAction={retry} />}</div>}{loading && <p role="status">正在读取…</p>}</>
}
export function selectionTrigger(ref: { current: HTMLElement | null }) { ref.current = document.activeElement instanceof HTMLElement ? document.activeElement : null }
