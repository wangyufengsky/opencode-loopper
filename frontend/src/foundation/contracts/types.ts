/** Pure contracts shared by UI adapters. None of these types grants server authority. */
export type Disposer = () => void

export type OwnerIdentity = Readonly<{ domain: string; id: string; epoch: number }>
export type ActionAvailability =
  | Readonly<{ kind: 'enabled' }>
  | Readonly<{ kind: 'hidden' }>
  | Readonly<{ kind: 'disabled'; reason: string }>
export type ClosePolicy =
  | Readonly<{ kind: 'allow' }>
  | Readonly<{ kind: 'confirm'; reason: string }>
  | Readonly<{ kind: 'block'; reason: string }>

export type LeaveDecision =
  | Readonly<{ kind: 'ALLOW' }>
  | Readonly<{ kind: 'CONFIRM_DISCARD'; description: string; draftRevision: number }>
  | Readonly<{ kind: 'BLOCK'; reason: string; recoveryAction: string }>

export interface SnapshotPort<T> {
  getSnapshot(): Readonly<T>
  subscribe(listener: () => void): Disposer
}

export interface PageOwner<T> extends SnapshotPort<T> {
  readonly identity: OwnerIdentity
  canLeave(): LeaveDecision
  attachView(): Disposer
}

export type ReceiptPhase = 'IDLE' | 'SENDING' | 'UNKNOWN' | 'ACCEPTED_READBACK' | 'SETTLED'
export type RecoveryPlan =
  | Readonly<{ kind: 'READ_ORIGINAL' }>
  | Readonly<{ kind: 'RETRY_IDENTICAL' }>
  | Readonly<{ kind: 'BLOCKED'; explanation: string }>

export type ScopeToken = Readonly<{ identity: OwnerIdentity; isCurrent(): boolean }>
export interface OwnerScope {
  readonly identity: OwnerIdentity
  capture(): ScopeToken
  isCurrent(token: ScopeToken): boolean
  isActive(): boolean
  /** Invalidates projections only; never erases a command's captured request identity. */
  retire(): void
}

export interface ResourceScope extends OwnerScope {
  /** The returned idempotent function RELEASES this resource, rather than only unregistering it. */
  own(dispose: Disposer): Disposer
  /** Invalidate first, release every owned resource, then report collected failures. */
  dispose(): void
}
