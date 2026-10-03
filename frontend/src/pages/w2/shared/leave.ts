import type { LeaveDecision } from '@/foundation/contracts/types'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { W2LeaveGuard } from './types'

/** Guard the existing router before it commits; this object creates no history or command. */
export function createRouteLeaveCoordinator(confirm: (decision: Extract<LeaveDecision, { kind: 'CONFIRM_DISCARD' }>, current: () => LeaveDecision) => Promise<boolean>) {
  const guards = new Set<W2LeaveGuard>()
  let active = true, epoch = 0
  let running: Promise<boolean> | null = null
  let destination = ''
  let draftRevision = 0, draftSignature = ''
  let nextGuardId = 0
  const guardIds = new WeakMap<W2LeaveGuard, number>()
  function read(request?: NavigationRequest): LeaveDecision {
    let dirty: Extract<LeaveDecision, { kind: 'CONFIRM_DISCARD' }> | undefined
    const signatures: string[] = []
    for (const guard of guards) {
      const decision = guard(request)
      if (decision.kind === 'BLOCK') return decision
      if (decision.kind === 'CONFIRM_DISCARD') {
        signatures.push(`${guardIds.get(guard)}:${decision.draftRevision}`)
        dirty = decision
      }
    }
    const signature = signatures.join('|')
    if (signature !== draftSignature) { draftSignature = signature; draftRevision++ }
    return dirty ? { ...dirty, draftRevision } : { kind: 'ALLOW' }
  }
  return {
    read,
    register(guard: W2LeaveGuard) {
      if (!active) return () => undefined
      if (!guardIds.has(guard)) guardIds.set(guard, ++nextGuardId)
      guards.add(guard)
      return () => { guards.delete(guard) }
    },
    allow(request: NavigationRequest): Promise<boolean> {
      if (!active) return Promise.resolve(false)
      if (running) return request.destination === destination ? running : Promise.resolve(false)
      destination = request.destination
      const captured = epoch
      running = Promise.resolve().then(async () => {
        if (!active || epoch !== captured) return false
        const original = read(request)
        if (original.kind === 'BLOCK') return false
        if (original.kind === 'CONFIRM_DISCARD') {
          if (!await confirm(original, () => read(request))) return false
          if (!active || epoch !== captured) return false
          const current = read(request)
          if (current.kind === 'BLOCK' || current.kind === 'CONFIRM_DISCARD' && current.draftRevision !== original.draftRevision) return false
        }
        return active && epoch === captured
      }).catch(() => false).finally(() => { running = null; destination = '' })
      return running
    },
    dispose() { active = false; epoch++; guards.clear() },
  }
}
