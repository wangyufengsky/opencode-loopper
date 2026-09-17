import { computed, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/** Overlay width never participates in the chat's layout. */
export function useKnowledgeSplit(workspace: Ref<HTMLElement | undefined>, _left: Ref<boolean>, _overlay: Ref<boolean>) {
  const size = ref(window.innerWidth), preferred = ref(480), dragging = ref(false)
  let observer: ResizeObserver | undefined, originX = 0, originWidth = 0, pointerId: number | undefined, handle: HTMLElement | undefined
  const maximum = computed(() => Math.max(320, Math.min(1000, size.value * .85)))
  const panelWidth = computed(() => Math.round(Math.max(320, Math.min(maximum.value, preferred.value))))
  function measure() { size.value = workspace.value?.getBoundingClientRect().width || window.innerWidth }
  function save() { try { localStorage.setItem('loopper.knowledge.drawerWidth', String(preferred.value)) } catch { /* Optional preference. */ } }
  function setWidth(value: number) { preferred.value = Math.max(320, Math.min(maximum.value, value)) }
  function start(event: PointerEvent) {
    if (event.button !== 0) return
    event.preventDefault(); measure(); originX = event.clientX; originWidth = panelWidth.value; pointerId = event.pointerId
    handle = event.currentTarget as HTMLElement; handle.setPointerCapture(pointerId); dragging.value = true
  }
  function move(event: PointerEvent) { if (dragging.value && event.pointerId === pointerId) setWidth(originWidth + originX - event.clientX) }
  function stop() { if (!dragging.value) return; dragging.value = false; if (pointerId !== undefined && handle?.hasPointerCapture(pointerId)) handle.releasePointerCapture(pointerId); pointerId = undefined; handle = undefined; save() }
  function keyboard(event: KeyboardEvent) { if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return; event.preventDefault(); setWidth(event.key === 'Home' ? 320 : event.key === 'End' ? maximum.value : panelWidth.value + (event.key === 'ArrowLeft' ? 32 : -32)); save() }
  function reset() { preferred.value = 480; save() }
  onMounted(() => { try { const saved = Number(localStorage.getItem('loopper.knowledge.drawerWidth')); if (saved >= 320 && saved <= 1000) preferred.value = saved } catch { /* Optional preference. */ } measure(); if (typeof ResizeObserver !== 'undefined') { observer = new ResizeObserver(measure); if (workspace.value) observer.observe(workspace.value) } window.addEventListener('resize', measure) })
  onBeforeUnmount(() => { stop(); observer?.disconnect(); window.removeEventListener('resize', measure) })
  return { panelWidth, maximum, dragging, start, move, stop, keyboard, reset }
}
