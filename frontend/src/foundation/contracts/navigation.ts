import { navigateAcceptedHandoff } from './receipt'
import type { AcceptedHandoff, OperationLeaveRisk } from './receipt'
import type { ClosePolicy, LeaveDecision, ScopeToken } from './types'

export type NavigationRequest = Readonly<{ destination: string; replace?: boolean; handoff?: AcceptedHandoff }>
export type NavigationResult = Readonly<{
  kind: 'NAVIGATED' | 'BLOCKED' | 'DECLINED' | 'STALE' | 'FAILED'
  decision?: LeaveDecision
  error?: unknown
}>

export function decideLeave(input: {
  operations: readonly OperationLeaveRisk[]
  dirty: boolean
  unsentFiles: number
  draftRevision: number
  request?: NavigationRequest
}): LeaveDecision {
  const unsafe = input.operations.some(operation => {
    if (operation.phase === 'IDLE' || operation.phase === 'SETTLED') return false
    return operation.phase !== 'ACCEPTED_READBACK' || !input.request
      || !operation.permitsHandoff(input.request.handoff, input.request.destination)
  })
  if (unsafe) return Object.freeze({ kind: 'BLOCK', reason: '操作结果尚未确认，请先等待、核对或恢复原操作', recoveryAction: '恢复原操作' })
  if (input.dirty || input.unsentFiles > 0) return Object.freeze({ kind: 'CONFIRM_DISCARD',
    description: '仍有未保存修改或未发送文件，是否放弃后离开？', draftRevision: input.draftRevision })
  return Object.freeze({ kind: 'ALLOW' })
}

export function toClosePolicy(decision: LeaveDecision): ClosePolicy {
  if (decision.kind === 'ALLOW') return { kind: 'allow' }
  if (decision.kind === 'BLOCK') return { kind: 'block', reason: decision.reason }
  return { kind: 'confirm', reason: decision.description }
}

/** Delegates one explicit intent to the injected history owner; creates no history/router. */
export function createNavigationGate(options: {
  scope: { capture(): ScopeToken; isCurrent(token: ScopeToken): boolean }
  canLeave: (request: NavigationRequest) => LeaveDecision
  confirmDiscard: (decision: Extract<LeaveDecision, { kind: 'CONFIRM_DISCARD' }>) => Promise<boolean>
  navigate: (destination: string, options: { replace?: boolean; handoff?: AcceptedHandoff }) => Promise<boolean>
}) {
  let running: Promise<NavigationResult> | null = null
  let activeRequest: NavigationRequest | null = null
  function navigate(request: NavigationRequest): Promise<NavigationResult> {
    if (running) {
      if (request.destination === activeRequest?.destination && request.replace === activeRequest.replace && request.handoff === activeRequest.handoff) return running
      return Promise.resolve({ kind: 'BLOCKED' })
    }
    request = Object.freeze({ ...request })
    activeRequest = request
    const token = options.scope.capture()
    running = Promise.resolve().then(async (): Promise<NavigationResult> => {
      if (!options.scope.isCurrent(token)) return { kind: 'STALE' }
      const decision = options.canLeave(request)
      if (decision.kind === 'BLOCK') return { kind: 'BLOCKED', decision }
      if (decision.kind === 'CONFIRM_DISCARD') {
        if (!await options.confirmDiscard(decision)) return { kind: 'DECLINED', decision }
        if (!options.scope.isCurrent(token)) return { kind: 'STALE' }
        const latest = options.canLeave(request)
        if (latest.kind === 'BLOCK') return { kind: 'BLOCKED', decision: latest }
        if (latest.kind === 'CONFIRM_DISCARD' && latest.draftRevision !== decision.draftRevision) return { kind: 'STALE', decision: latest }
      }
      if (!options.scope.isCurrent(token)) return { kind: 'STALE' }
      const move = () => options.navigate(request.destination, { replace: request.replace, handoff: request.handoff })
      const moved = request.handoff ? await navigateAcceptedHandoff(request.handoff, request.destination, move) : await move()
      if (!moved) return { kind: 'FAILED' }
      return { kind: 'NAVIGATED' }
    }).catch((error: unknown): NavigationResult => ({ kind: 'FAILED', error })).finally(() => { running = null; activeRequest = null })
    return running
  }
  return { navigate }
}
