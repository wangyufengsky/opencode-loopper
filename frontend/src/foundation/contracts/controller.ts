import { captureDto } from './immutable'
import { createResourceScope, OwnedResourceCleanupError } from './resource'
import { createOwnerScope } from './scope'
import { decideLeave } from './navigation'
import type { NavigationRequest } from './navigation'
import type { OperationLeaveRisk } from './receipt'
import type { Disposer, LeaveDecision, OwnerIdentity, PageOwner, ResourceScope, ScopeToken } from './types'

export interface SnapshotController<T> extends PageOwner<T> {
  capture(): ScopeToken
  /** Explicit projection only. Does not issue a command, save, navigate or subscribe remotely. */
  project(value: T): boolean
  apply(token: ScopeToken, value: T): boolean
  viewCount(): number
  canLeave(request?: NavigationRequest): LeaveDecision
  ownOperation(operation: OwnedOperation): boolean
  notificationFailures(): readonly unknown[]
  retire(forced?: boolean): LeaveDecision
}
export interface OwnedOperation {
  belongsTo(owner: OwnerIdentity): boolean
  leaveRisk(): OperationLeaveRisk
  retire(forced?: boolean): boolean
}

/** One projection/lease owner. Framework adapters subscribe, never construct a second writer. */
export function createSnapshotController<T>(options: {
  identity: OwnerIdentity
  initial: T
  canLeave: (snapshot: Readonly<T>) => LeaveDecision
  attachReads?: (resources: ResourceScope) => void
}): SnapshotController<T> {
  const scope = createOwnerScope(options.identity)
  const { canLeave, attachReads } = options
  let snapshot = captureDto(options.initial), views = 0, reads: ResourceScope | null = null
  const listeners = new Set<() => void>(), operations = new Set<OwnedOperation>(), notificationErrors: unknown[] = []
  function leave(request?: NavigationRequest): LeaveDecision {
    const unresolved = decideLeave({ operations: [...operations].map(operation => operation.leaveRisk()),
      dirty: false, unsentFiles: 0, draftRevision: 0, request })
    return unresolved.kind === 'BLOCK' ? unresolved : canLeave(snapshot)
  }
  function project(value: T) {
    if (!scope.isActive()) return false
    snapshot = captureDto(value)
    for (const listener of [...listeners]) {
      try { listener() } catch (failure) { notificationErrors.push(failure) }
    }
    return true
  }
  function closeReads() {
    const old = reads; reads = null
    old?.dispose()
  }
  return {
    identity: scope.identity,
    getSnapshot: () => snapshot,
    subscribe: listener => {
      if (!scope.isActive()) return () => undefined
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    capture: scope.capture,
    notificationFailures: () => Object.freeze([...notificationErrors]),
    project,
    apply: (token, value) => scope.isCurrent(token) && project(value),
    canLeave: leave,
    ownOperation: operation => {
      if (!scope.isActive() || !operation.belongsTo(scope.identity)) return false
      operations.add(operation); return true
    },
    attachView(): Disposer {
      if (!scope.isActive()) return () => undefined
      views++
      if (views === 1) {
        reads = createResourceScope(scope.identity)
        try { attachReads?.(reads) } catch (failure) {
          views--
          try { closeReads() } catch (cleanupFailure) {
            throw new OwnedResourceCleanupError([failure, ...(cleanupFailure instanceof OwnedResourceCleanupError ? cleanupFailure.failures : [cleanupFailure])])
          }
          throw failure
        }
      }
      let released = false
      return () => {
        if (released) return
        released = true
        if (!scope.isActive()) return
        views--
        if (views === 0) closeReads()
      }
    },
    viewCount: () => views,
    retire(forced = false) {
      const decision = leave()
      if (!forced && decision.kind !== 'ALLOW') return decision
      scope.retire(); listeners.clear(); views = 0
      const failures: unknown[] = []
      for (const operation of operations) {
        try { operation.retire(forced) } catch (failure) { failures.push(failure) }
      }
      try { closeReads() } catch (failure) { failures.push(failure) }
      if (failures.length) throw new OwnedResourceCleanupError(failures)
      return { kind: 'ALLOW' }
    },
  }
}
