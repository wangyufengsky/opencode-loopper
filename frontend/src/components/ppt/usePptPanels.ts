import { onBeforeUnmount, ref } from 'vue'
export function usePptPanels() {
  const leftWidth = ref(230),
    rightWidth = ref(340)
  try {
    const values = JSON.parse(localStorage.getItem('loopper.ppt.panels') || 'null')
    if (values) {
      leftWidth.value = Math.max(180, Math.min(360, Number(values.left) || 230))
      rightWidth.value = Math.max(280, Math.min(520, Number(values.right) || 340))
    }
  } catch {
    /* Optional layout preferences. */
  }
  let dragging: 'left' | 'right' | null = null,
    startX = 0,
    startWidth = 0

  function save() {
    try {
      localStorage.setItem(
        'loopper.ppt.panels',
        JSON.stringify({
          left: leftWidth.value,
          right: rightWidth.value,
        }),
      )
    } catch {
      /* Keep the current layout. */
    }
  }

  function resize(side: 'left' | 'right', value: number) {
    if (side === 'left') leftWidth.value = Math.max(180, Math.min(360, value))
    else rightWidth.value = Math.max(280, Math.min(520, value))
  }

  function move(event: PointerEvent) {
    if (dragging)
      resize(dragging, startWidth + (event.clientX - startX) * (dragging === 'left' ? 1 : -1))
  }

  function stop() {
    if (!dragging) return
    dragging = null
    save()
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
  }

  function start(event: PointerEvent, side: 'left' | 'right') {
    if (event.button !== 0) return
    event.preventDefault()
    dragging = side
    startX = event.clientX
    startWidth = side === 'left' ? leftWidth.value : rightWidth.value
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', stop)
    window.addEventListener('pointercancel', stop)
  }

  function keyboard(event: KeyboardEvent, side: 'left' | 'right') {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    const current = side === 'left' ? leftWidth.value : rightWidth.value
    resize(
      side,
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? 1000
          : current + (event.key === 'ArrowRight' ? 20 : -20) * (side === 'left' ? 1 : -1),
    )
    save()
  }
  onBeforeUnmount(stop)
  return {
    leftWidth,
    rightWidth,
    start,
    keyboard,
  }
}
