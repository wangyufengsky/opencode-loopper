import { onBeforeUnmount, ref, watch } from 'vue'
/** An unsuccessful attempt is not silently repeated. A new edit or explicit retry creates the next attempt. */
export function usePptAutosave(value: () => string, dirty: () => boolean, enabled: () => boolean, revision: () => number, submit: () => void) {
  const scheduled = ref(false)
  let timer: ReturnType<typeof setTimeout> | undefined, attempted = ''
  const identity = () => `${revision()}:${value()}`
  function cancel() { if (timer) clearTimeout(timer); timer = undefined; scheduled.value = false }
  function save() { cancel(); if (!enabled() || !dirty()) return; attempted = identity(); submit() }
  watch([value, dirty, enabled, revision], () => { cancel(); if (!dirty()) { attempted = ''; return } if (!enabled() || attempted === identity()) return; scheduled.value = true; timer = setTimeout(save, 900) }, { immediate: true })
  onBeforeUnmount(cancel)
  return { scheduled, save }
}
