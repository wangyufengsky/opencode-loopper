import { computed, onBeforeUnmount, ref, shallowRef } from 'vue'
import { ApiError } from '@/api/client'
import { userFacingError } from '@/utils/displayLabels'
/** Preserve an immutable command and its acknowledgement through uncertain network results. */
export function useWorkflowCommand() {
  const busy = ref(false), error = ref(''), pending = shallowRef<{ label: string; accepted: boolean; execute: () => Promise<void> } | null>(null)
  let alive = true
  onBeforeUnmount(() => { alive = false })
  async function retry() {
    const operation = pending.value; if (!operation || busy.value) return
    busy.value = true; error.value = ''
    try { await operation.execute(); if (alive) pending.value = null }
    catch (failure) {
      if (!alive) return
      error.value = userFacingError(failure, operation.accepted ? '操作已接受，最新状态暂时无法读取，请刷新结果。' : '操作结果暂时无法确认，请重试原操作。')
      if (!operation.accepted && failure instanceof ApiError && [400, 403, 404, 409].includes(failure.status)) pending.value = null
    } finally { if (alive) busy.value = false }
  }
  async function submit<T>(label: string, execute: () => Promise<T>, after: (value: T) => Promise<void>) {
    if (pending.value || busy.value) return
    let receipt: T
    const operation = { label, accepted: false, execute: async () => {
      if (!operation.accepted) { receipt = await execute(); operation.accepted = true }
      if (alive) await after(receipt)
    } }
    pending.value = operation; await retry()
  }
  return { busy, error, pending, locked: computed(() => busy.value || !!pending.value), submit, retry }
}
