import { UiActionButton } from '@/foundation/components'
import { SemanticIcon } from '@/foundation/semanticRegistry'
import type { createSecondaryMutationOwner } from './state'

export function ReadFeedback({ error, loading, retry }: { error: string; loading?: boolean; retry: () => void }) {
  if (error) return <div className="w2-secondary-status" role="alert"><SemanticIcon semanticKey="status.error" /> {error} <UiActionButton actionKey="ui.retry" onAction={retry} /></div>
  if (loading) return <p role="status">正在读取…</p>
  return null
}
export function MutationFeedback({ owner }: { owner: ReturnType<typeof createSecondaryMutationOwner> }) {
  const snapshot = owner.getSnapshot(), policy = owner.canLeave()
  if (!snapshot.error && policy.kind === 'ALLOW') return null
  return <div className="w2-secondary-status" role="alert"><SemanticIcon semanticKey={snapshot.phase === 'SENDING' ? 'status.sending' : snapshot.phase === 'UNKNOWN' ? 'status.unknown' : 'status.error'} />
    <p>{snapshot.error || (policy.kind === 'BLOCK' ? policy.reason : '')}</p>
    {snapshot.recovery ? <UiActionButton actionKey="receipt.readOriginal" busy={snapshot.busy} onAction={() => { void owner.recover() }} /> : policy.kind === 'BLOCK' && <p>{policy.recoveryAction}</p>}
  </div>
}
