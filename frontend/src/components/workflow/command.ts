import { computed, onBeforeUnmount, ref, shallowRef } from 'vue'
import { ApiError } from '@/api/client'
import { userFacingError } from '@/utils/displayLabels'
import { createAcknowledgedOperation } from '@/domain/acknowledgedOperation'
/** Preserve an immutable command and its acknowledgement through uncertain network results. */
export function useWorkflowCommand(identity: () => unknown = () => undefined) {
  type Operation = { label: string; accepted: boolean; isActive: () => boolean; execute: () => Promise<void> }
  const busy = ref(false), error = ref(''), pending = shallowRef<Operation | null>(null)
  let alive = true, generation = 0, running: Operation | null = null
  function captureScope() {
    const ticket = generation, owner = identity()
    return () => alive && ticket === generation && owner === identity()
  }
  function invalidate() {
    generation++; running = null; pending.value = null; busy.value = false; error.value = ''
  }
  onBeforeUnmount(() => { alive = false; invalidate() })
  async function retry() {
    const operation = pending.value; if (!operation || busy.value || !operation.isActive()) return
    const current = captureScope(), ownsPending = () => current() && pending.value === operation
    running = operation; busy.value = true; error.value = ''
    try { await operation.execute(); if (ownsPending()) pending.value = null }
    catch (failure) {
      if (!ownsPending()) return
      error.value = userFacingError(failure, operation.accepted ? '操作已接受，最新状态暂时无法读取，请刷新结果。' : '操作结果暂时无法确认，请重试原操作。')
      if (!operation.accepted && failure instanceof ApiError && [400, 403, 404, 409].includes(failure.status)) pending.value = null
    } finally { if (current() && running === operation) { running = null; busy.value = false } }
  }
  async function submit<T>(label: string, execute: () => Promise<T>, after: (value: T) => Promise<void>) {
    if (pending.value || busy.value) return
    const current = captureScope()
    const operation: Operation = createAcknowledgedOperation(label, execute, after, () => current() && pending.value === operation)
    pending.value = operation; await retry()
  }
  return { busy, error, pending, locked: computed(() => busy.value || !!pending.value), submit, retry, captureScope, invalidate }
}
