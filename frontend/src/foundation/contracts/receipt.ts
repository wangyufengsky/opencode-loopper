import { createAcknowledgedOperation } from '../../domain/acknowledgedOperation'
import { captureDto } from './immutable'
import { createOwnerScope } from './scope'
import type { Disposer, OwnerIdentity, ReceiptPhase, RecoveryPlan, ScopeToken, SnapshotPort } from './types'

export type OperationInput<B> = Readonly<{
  endpoint: string
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body: B
  requestKey?: string
  commandId?: string
  versions?: Readonly<Record<string, number | string>>
  files?: readonly File[]
}>
export type OperationIdentity<B> = Readonly<Omit<OperationInput<B>, 'body' | 'files'> & {
  owner: OwnerIdentity
  body: Readonly<B>
  files: readonly File[]
}>
export type OriginalLookup<R> = Readonly<{ kind: 'ACCEPTED'; receipt: R }> | Readonly<{ kind: 'UNCONFIRMED' }>
export interface ReadContext {
  readonly token: ScopeToken
  isCurrent(): boolean
  /** Every late projection must pass through this check after its await. */
  apply(project: () => void): boolean
}
export type RecoveryCapability<B, R> =
  | Readonly<{ kind: 'IDEMPOTENT_KEY'; readOriginal?: (identity: OperationIdentity<B>) => Promise<OriginalLookup<R>> }>
  | Readonly<{ kind: 'READ_ORIGINAL'; readOriginal: (identity: OperationIdentity<B>) => Promise<OriginalLookup<R>> }>
  | Readonly<{ kind: 'NONE' }>

/** A permit is issued only for this operation's known receipt and its exact target. */
export type AcceptedHandoff = Readonly<{ destination: string }>
const handoffCompletions = new WeakMap<AcceptedHandoff, () => void>()
/** The injected history owner must return true only after this exact navigation succeeds. */
export async function navigateAcceptedHandoff(permit: AcceptedHandoff, destination: string, navigate: () => Promise<boolean>): Promise<boolean> {
  const complete = handoffCompletions.get(permit)
  if (!complete || permit.destination !== destination) return false
  if (!await navigate()) return false
  complete()
  handoffCompletions.delete(permit)
  return true
}
export type OperationLeaveRisk = Readonly<{
  phase: ReceiptPhase
  permitsHandoff(permit: AcceptedHandoff | undefined, destination: string): boolean
}>
export type ReceiptSnapshot<B, R> = Readonly<{
  identity: OperationIdentity<B>
  phase: ReceiptPhase
  busy: boolean
  accepted: boolean
  receipt?: Readonly<R>
  error?: unknown
  retired: boolean
  recovery: RecoveryPlan
}>
export interface OperationOwner<B, R> extends SnapshotPort<ReceiptSnapshot<B, R>> {
  readonly identity: OperationIdentity<B>
  belongsTo(owner: OwnerIdentity): boolean
  notificationFailures(): readonly unknown[]
  /** Initial user mutation only. Never a generic retry endpoint. */
  execute(): Promise<void>
  recoverWrite(): Promise<void>
  readOriginal(): Promise<void>
  retryReadback(): Promise<void>
  leaveRisk(): OperationLeaveRisk
  prepareHandoff(): AcceptedHandoff
  /** Normal retirement is guarded. Forced view loss invalidates projections but retains identity/receipt. */
  retire(forced?: boolean): boolean
}

export function createOperationOwner<B, R>(options: {
  owner: OwnerIdentity
  label: string
  input: OperationInput<B>
  capability: RecoveryCapability<B, R>
  write: (identity: OperationIdentity<B>) => Promise<R>
  read: (receipt: Readonly<R>, context: ReadContext) => Promise<void>
  isDefinitiveRejection?: (failure: unknown) => boolean
  handoffTarget?: (receipt: Readonly<R>) => string
}): OperationOwner<B, R> {
  const sourceOwner = options.owner
  const scope = createOwnerScope(options.owner)
  const identity: OperationIdentity<B> = Object.freeze({
    ...options.input, owner: scope.identity, body: captureDto(options.input.body),
    versions: options.input.versions ? captureDto(options.input.versions) : undefined,
    files: Object.freeze([...(options.input.files ?? [])]),
  })
  const capability = Object.freeze({ ...options.capability })
  const { write, read, isDefinitiveRejection, handoffTarget } = options
  if (capability.kind === 'IDEMPOTENT_KEY' && !identity.requestKey?.trim() && !identity.commandId?.trim()) {
    throw new TypeError('原接口未保留幂等身份，不能声明可重试原写')
  }
  for (const field of ['requestKey', 'commandId'] as const) {
    if (identity[field] && identity.body && typeof identity.body === 'object'
      && Object.prototype.hasOwnProperty.call(identity.body, field)
      && (identity.body as Record<string, unknown>)[field] !== identity[field]) {
      throw new TypeError('操作身份与原请求正文不一致')
    }
  }
  const listeners = new Set<() => void>(), permits = new WeakSet<AcceptedHandoff>(), notificationErrors: unknown[] = []
  let phase: ReceiptPhase = 'IDLE', accepted = false, busy = false, error: unknown
  let receipt: Readonly<R>, receiptAvailable = false, running: Promise<void> | null = null
  function recovery(): RecoveryPlan {
    if (accepted && receiptAvailable) return Object.freeze({ kind: 'READ_ORIGINAL' })
    if (phase === 'SETTLED') return Object.freeze({ kind: 'BLOCKED', explanation: '原操作已明确拒绝，请保留草稿并按最新结果处理' })
    if (capability.kind !== 'NONE' && capability.readOriginal) return Object.freeze({ kind: 'READ_ORIGINAL' })
    if (accepted) return Object.freeze({ kind: 'BLOCKED', explanation: '原写入已接受，但回执无法核对；请保留身份并等待可用的原结果读取入口' })
    if (capability.kind === 'IDEMPOTENT_KEY') return Object.freeze({ kind: 'RETRY_IDENTICAL' })
    return Object.freeze({ kind: 'BLOCKED', explanation: '原接口不支持核对或重试本次操作，请保留身份并等待结果' })
  }
  let snapshot: ReceiptSnapshot<B, R>
  function publish() {
    snapshot = Object.freeze({ identity, phase, busy, accepted, ...(receiptAvailable ? { receipt } : {}), error,
      retired: !scope.isActive(), recovery: recovery() })
    if (scope.isActive()) for (const listener of [...listeners]) {
      try { listener() } catch (failure) { notificationErrors.push(failure) }
    }
  }
  publish()
  function context(): ReadContext {
    const token = scope.capture()
    return Object.freeze({ token, isCurrent: () => scope.isCurrent(token), apply: (project: () => void) => {
      if (!scope.isCurrent(token)) return false
      project(); return true
    } })
  }
  async function readback() {
    if (!receiptAvailable) throw new Error('原写入已接受，但尚无经过验证的回执，请先核对原操作结果')
    const current = context()
    await read(receipt, current)
    if (current.isCurrent()) { phase = 'SETTLED'; error = undefined; publish() }
  }
  function retainAccepted(result: R) {
    // A fulfilled write or original-result lookup proves acceptance even if local DTO capture fails.
    accepted = true; phase = 'ACCEPTED_READBACK'; receiptAvailable = false
    receipt = captureDto(result); receiptAvailable = true; publish()
  }
  const acknowledged = createAcknowledgedOperation(options.label, async () => {
    const result = await write(identity)
    retainAccepted(result)
    return result
  }, async () => { await readback() }, () => scope.isActive())
  function perform(action: () => Promise<void>): Promise<void> {
    if (running) return running
    if (!scope.isActive()) return Promise.reject(new Error('原操作作用域已退休，不能重新执行'))
    busy = true; error = undefined
    running = Promise.resolve().then(() => scope.isActive() ? action() : undefined).catch(failure => {
      error = failure
      if (!accepted) phase = isDefinitiveRejection?.(failure) ? 'SETTLED' : 'UNKNOWN'
      else if (phase !== 'SETTLED') phase = 'ACCEPTED_READBACK'
      publish(); throw failure
    }).finally(() => { busy = false; running = null; publish() })
    publish()
    return running
  }
  const owner: OperationOwner<B, R> = {
    identity,
    belongsTo: value => value === sourceOwner,
    notificationFailures: () => Object.freeze([...notificationErrors]),
    getSnapshot: () => snapshot,
    subscribe(listener): Disposer {
      if (!scope.isActive()) return () => undefined
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    execute() {
      if (running) return running
      if (!scope.isActive()) return Promise.reject(new Error('原操作作用域已退休，不能重新执行'))
      if (phase !== 'IDLE') return Promise.reject(new Error('本次操作已有结果或未知回执，请使用原操作恢复入口'))
      phase = 'SENDING'
      return perform(() => acknowledged.execute())
    },
    recoverWrite() {
      if (running) return running
      if (!scope.isActive()) return Promise.reject(new Error('原操作作用域已退休，不能重新执行'))
      if (accepted) return owner.retryReadback()
      if (phase !== 'UNKNOWN' || capability.kind !== 'IDEMPOTENT_KEY') {
        return Promise.reject(new Error('原接口未授权重试本次写入，请先核对原操作结果'))
      }
      phase = 'SENDING'
      return perform(() => acknowledged.execute())
    },
    readOriginal() {
      if (running) return running
      if (accepted && receiptAvailable) return owner.retryReadback()
      if ((phase !== 'UNKNOWN' && !accepted) || capability.kind === 'NONE' || !capability.readOriginal) {
        return Promise.reject(new Error('原接口没有可用的本次结果读取入口'))
      }
      const lookup = capability.readOriginal
      return perform(async () => {
        const found = await lookup(identity)
        if (found.kind === 'ACCEPTED') {
          retainAccepted(found.receipt)
          if (scope.isActive()) await readback()
        }
      })
    },
    retryReadback() {
      if (running) return running
      if (!accepted) return Promise.reject(new Error('尚未确认原操作已接受，不能伪造只读恢复'))
      if (!receiptAvailable) return Promise.reject(new Error('原写入已接受，但尚无经过验证的回执，请先核对原操作结果'))
      if (phase === 'SETTLED') return Promise.resolve()
      return perform(readback)
    },
    leaveRisk: () => Object.freeze({ phase, permitsHandoff: (permit: AcceptedHandoff | undefined, destination: string) =>
      !!permit && permits.has(permit) && permit.destination === destination && accepted && scope.isActive() }),
    prepareHandoff() {
      if (!accepted || !receiptAvailable || !scope.isActive() || !handoffTarget) throw new Error('尚无确定回执或安全导航目标')
      const destination = handoffTarget(receipt)
      if (!destination) throw new Error('原回执没有安全导航目标')
      const permit: AcceptedHandoff = Object.freeze({ destination })
      handoffCompletions.set(permit, () => {
        if (!permits.has(permit)) return
        phase = 'SETTLED'; error = undefined; scope.retire(); listeners.clear(); publish()
      })
      permits.add(permit)
      return permit
    },
    retire(forced = false) {
      if (!forced && phase !== 'IDLE' && phase !== 'SETTLED') return false
      scope.retire(); listeners.clear(); publish(); return true
    },
  }
  return owner
}
