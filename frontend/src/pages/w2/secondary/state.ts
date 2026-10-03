import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { ApiError } from '@/api/client'
import { createOperationOwner, type OperationOwner, type ReadContext, type RecoveryCapability } from '@/foundation/contracts/receipt'
import type { LeaveDecision } from '@/foundation/contracts/types'
import { userFacingError } from '@/utils/displayLabels'
import { useRetainedOwner } from '../shared'
import type { W2PageProps } from '../shared/types'

/** Reads publish only in their mounted scope and current sequence, including append projections. */
export function useLatestRead<T>(loader: () => Promise<T>, initial: T) {
  const [value, setValue] = useState(initial), [loading, setLoading] = useState(false), [error, setError] = useState('')
  const sequence = useRef(0), mounted = useRef(true), loadRef = useRef(loader), committed = useRef<T>(initial)
  loadRef.current = loader
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; sequence.current++ } }, [])
  const reload = useCallback(async (merge?: (result: T) => T) => {
    if (!mounted.current) return undefined
    const ticket = ++sequence.current; setLoading(true); setError('')
    try { const result = await loadRef.current(); if (mounted.current && sequence.current === ticket) { const next = merge ? merge(result) : result; committed.current = next; setValue(next); return next }; return undefined }
    catch (failure) { if (mounted.current && sequence.current === ticket) setError(userFacingError(failure, '读取失败，请重试')); return undefined }
    finally { if (mounted.current && sequence.current === ticket) setLoading(false) }
  }, [])
  const apply = useCallback((result: T, project: () => void) => { if (!mounted.current || committed.current !== result) return false; project(); return true }, [])
  return { value, loading, error, reload, apply }
}

export function createSecondaryMutationOwner(route: string) {
  let retired = false, operation: OperationOwner<unknown, unknown> | undefined
  let operationDetach: (() => void) | undefined
  const identity = Object.freeze({ domain: 'w2-secondary', id: route, epoch: 0 })
  const listeners = new Set<() => void>()
  let snapshot = { phase: 'IDLE' as string, busy: false, error: '', recovery: false }
  const publish = () => {
    const current = operation?.getSnapshot()
    snapshot = { phase: current?.phase ?? 'IDLE', busy: !!current?.busy,
      error: current?.error ? userFacingError(current.error, '操作结果尚未确认，请保留原操作') : '',
      recovery: current?.recovery.kind === 'READ_ORIGINAL' }
    if (!retired) for (const listener of listeners) listener()
  }
  const owner = {
    getSnapshot: () => snapshot,
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } },
    canLeave(): LeaveDecision {
      if (operation) {
        const current = operation.getSnapshot()
        if (current.busy || ['SENDING', 'UNKNOWN', 'ACCEPTED_READBACK'].includes(current.phase)) return { kind: 'BLOCK', reason: current.phase === 'ACCEPTED_READBACK' ? '原写入已接受，请先核对读取结果' : '原操作尚未确认，请保留当前页面和输入', recoveryAction: current.recovery.kind === 'READ_ORIGINAL' ? '核对原操作结果；无法证明原写入时继续保留原操作' : '此接口没有可用的原结果核对入口，请保留原操作' }
      }
      return { kind: 'ALLOW' }
    },
    async run<B, R>(endpoint: string, body: B, write: (body: Readonly<B>) => Promise<R>, read: (receipt: Readonly<R>, context: ReadContext) => Promise<void> = async () => {}, method: 'POST' | 'PUT' | 'PATCH' | 'DELETE' = 'POST', capability: RecoveryCapability<B, R> = { kind: 'NONE' }) {
      if (retired || owner.canLeave().kind === 'BLOCK') return
      operationDetach?.()
      const next = createOperationOwner<B, R>({ owner: identity, label: '配置操作',
        input: { endpoint, method, body }, capability,
        write: request => write(request.body), read,
        isDefinitiveRejection: failure => failure instanceof ApiError && failure.status >= 400 && failure.status < 500 && ![408, 429].includes(failure.status),
      })
      operation = next as unknown as OperationOwner<unknown, unknown>
      operationDetach = next.subscribe(publish); publish()
      try { await next.execute() } catch { /* The original receipt owner retains truthful failure and identity. */ }
      if (!retired && operation === (next as unknown)) publish()
    },
    async recover() { if (retired || !operation || !snapshot.recovery) return; try { await operation.readOriginal() } catch { /* An unconfirmed original remains blocked. */ } },
    retire() { if (retired) return; retired = true; operation?.retire(true); operationDetach?.(); listeners.clear() },
  }
  return owner
}
export function useMutationOwner(props: W2PageProps) {
  const [owner] = useState(() => createSecondaryMutationOwner(props.route.fullPath))
  useRetainedOwner(props, owner, () => owner.retire())
  const snapshot = useSyncExternalStore(owner.subscribe, owner.getSnapshot, owner.getSnapshot)
  return { owner, snapshot, blocked: owner.canLeave().kind === 'BLOCK' }
}
