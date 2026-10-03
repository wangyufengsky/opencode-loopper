import { useLayoutEffect, type ReactNode } from 'react'
import { UiActionButton } from '@/foundation/components'
import type { SnapshotController } from '@/foundation/contracts/controller'
import { useLeaveGuard, useOwnerSnapshot, useRetainedOwner } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import type { CommandStatus } from './controllerCore'
import './workflow-pages.css'

export function usePageOwner<S>(props: W2PageProps, owner: SnapshotController<S>) {
  useRetainedOwner(props, owner, () => { owner.retire(true) })
  useLeaveGuard(props, request => owner.canLeave(request))
  useLayoutEffect(() => owner.attachView(), [owner])
  return useOwnerSnapshot(owner)
}
export function CommandNotice({ command, recover }: { command: CommandStatus; recover: () => void }) {
  if (command.phase === 'IDLE' || (command.phase === 'SETTLED' && !command.error)) return null
  const explanation = command.phase === 'SENDING' ? `${command.label}正在发送，请保留原操作。`
    : command.phase === 'UNKNOWN' ? `${command.label}结果尚未确认，请先恢复原操作后离开。`
      : command.phase === 'ACCEPTED_READBACK' ? `${command.label}已接受，读取或导航尚未完成。` : ''
  return <section className="w2-critical" role={command.error || command.phase === 'UNKNOWN' ? 'alert' : 'status'} data-operation-phase={command.phase}>
    <p>{explanation}{command.error && <span>{command.error}</span>}</p>
    {command.phase !== 'SETTLED' && command.recovery !== 'BLOCKED' && <UiActionButton actionKey="ui.retry" target={command.accepted ? '读取或打开原结果' : command.recovery === 'RETRY_IDENTICAL' ? '同一身份的原写入' : '读取原操作结果'} busy={command.busy} onAction={recover} />}
    {command.recovery === 'BLOCKED' && command.phase !== 'SETTLED' && <p>当前接口无法安全恢复，请保留原身份并等待结果。</p>}
  </section>
}
export function ReadNotice({ error, busy, retry, children }: { error: string; busy?: boolean; retry: () => void; children?: ReactNode }) {
  return <>{error && <div className="w2-critical" role="alert"><span>{error}</span><UiActionButton actionKey="ui.retry" onAction={retry} busy={busy} /></div>}{busy && <p role="status">{children ?? '正在读取…'}</p>}</>
}
export function focusSelection(ref: { current: HTMLElement | null }) { ref.current = document.activeElement instanceof HTMLElement ? document.activeElement : null }
