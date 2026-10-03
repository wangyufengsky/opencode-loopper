// Every native registration belongs to this one active canvas session. Dispose
// before releasing capture: synchronous lostpointercapture cannot re-enter it.
export function ownResources(root: HTMLElement, move: (event: PointerEvent) => void, up: (event: PointerEvent) => void,
  cancel: (event?: PointerEvent) => void) {
  const captures = new Map<number, HTMLElement>(), ownerWindow = root.ownerDocument.defaultView
  const blur = () => cancel()
  const types = { pointermove: move, pointerup: up, pointercancel: cancel, lostpointercapture: cancel }
  for (const [type, listener] of Object.entries(types)) root.addEventListener(type, listener as EventListener)
  ownerWindow?.addEventListener('blur', blur)
  function release(id: number) {
    const target = captures.get(id); captures.delete(id)
    try { if (target?.hasPointerCapture?.(id)) target.releasePointerCapture(id) }
    catch { /* A detached target may already have lost its native capture. */ }
  }
  return {
    capture(target: HTMLElement, id: number) {
      try {
        if (!target.setPointerCapture) return false
        target.setPointerCapture(id); captures.set(id, target); return true
      } catch { return false }
    },
    release,
    dispose() {
      for (const [type, listener] of Object.entries(types)) root.removeEventListener(type, listener as EventListener)
      ownerWindow?.removeEventListener('blur', blur)
      for (const id of [...captures.keys()]) release(id)
    },
  }
}
