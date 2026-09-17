import { computed, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

const preferenceKey = 'loopper.knowledge.evidenceRatio'
export function useKnowledgeSplit(workspace: Ref<HTMLElement | undefined>, left: Ref<boolean>, overlay: Ref<boolean>) {
  const size = ref(0), preferred = ref(0.5), dragging = ref(false)
  let observer: ResizeObserver | undefined, originX = 0, originWidth = 0, pointerId: number | undefined, handle: HTMLElement | undefined
  const available = computed(() => Math.max(0, size.value - (left.value && !overlay.value ? 290 : 0) - 8))
  const maximum = computed(() => Math.max(320, available.value - 360))
  const panelWidth = computed(() => Math.round(Math.max(320, Math.min(maximum.value, available.value * preferred.value))))
  function measure() { size.value = workspace.value?.getBoundingClientRect().width || window.innerWidth }
  function save() { try { localStorage.setItem(preferenceKey, String(preferred.value)) } catch { /* Storage is optional. */ } }
  function setWidth(value: number) { preferred.value = Math.max(320, Math.min(maximum.value, value)) / Math.max(1, available.value) }
  function start(event: PointerEvent) {
    if (event.button !== 0 || overlay.value) return
    event.preventDefault(); measure(); originX = event.clientX; originWidth = panelWidth.value; pointerId = event.pointerId
    handle = event.currentTarget as HTMLElement; handle.setPointerCapture(pointerId); dragging.value = true
  }
  function move(event: PointerEvent) { if (dragging.value && event.pointerId === pointerId) setWidth(originWidth + originX - event.clientX) }
  function stop() {
    if (!dragging.value) return
    dragging.value = false
    if (pointerId !== undefined && handle?.hasPointerCapture(pointerId)) handle.releasePointerCapture(pointerId)
    pointerId = undefined; handle = undefined; save()
  }
  function keyboard(event: KeyboardEvent) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    setWidth(event.key === 'Home' ? 320 : event.key === 'End' ? maximum.value : panelWidth.value + (event.key === 'ArrowLeft' ? 32 : -32)); save()
  }
  function reset() { preferred.value = 0.5; save() }
  onMounted(() => {
    try { const saved = Number(localStorage.getItem(preferenceKey)); if (saved >= 0.1 && saved <= 0.9) preferred.value = saved } catch { /* Storage is optional. */ }
    measure(); if (typeof ResizeObserver !== 'undefined') { observer = new ResizeObserver(measure); if (workspace.value) observer.observe(workspace.value) }
    window.addEventListener('resize', measure)
  })
  onBeforeUnmount(() => { stop(); observer?.disconnect(); window.removeEventListener('resize', measure) })
  return { panelWidth, maximum, dragging, start, move, stop, keyboard, reset }
}
