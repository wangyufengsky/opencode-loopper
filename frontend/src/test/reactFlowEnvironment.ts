// jsdom has no layout/ResizeObserver. Supply only the browser geometry required
// by real React Flow components; Chromium E2E uses the actual browser APIs.
if (typeof window !== 'undefined' && !window.ResizeObserver) {
  class Observer implements ResizeObserver {
    private timers = new Map<Element, ReturnType<typeof setTimeout>>()
    constructor(private readonly callback: ResizeObserverCallback) {}
    observe(target: Element) {
      if (this.timers.has(target)) return
      const timer = setTimeout(() => {
        this.timers.delete(target)
        this.callback([{ target, contentRect: target.getBoundingClientRect(), borderBoxSize: [], contentBoxSize: [], devicePixelContentBoxSize: [] }], this)
      }, 0)
      this.timers.set(target, timer)
    }
    unobserve(target: Element) { clearTimeout(this.timers.get(target)); this.timers.delete(target) }
    disconnect() { for (const timer of this.timers.values()) clearTimeout(timer); this.timers.clear() }
  }
  window.ResizeObserver = Observer
  globalThis.ResizeObserver = Observer
}
if (typeof window !== 'undefined' && !window.DOMMatrixReadOnly) {
  class Matrix {
    readonly m22: number
    constructor(transform = '') {
      this.m22 = Number(transform.match(/scale\(([^)]+)\)/)?.[1] ?? 1)
    }
  }
  window.DOMMatrixReadOnly = Matrix as unknown as typeof DOMMatrixReadOnly
}
for (const [property, dimension, fallback] of [['offsetWidth', 'width', 800], ['offsetHeight', 'height', 600]] as const) {
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, property)
  Object.defineProperty(HTMLElement.prototype, property, {
    configurable: true,
    get(this: HTMLElement) {
      if (this.classList.contains('react-flow__node')) return Number.parseFloat(this.style[dimension]) || (dimension === 'width' ? 224 : 118)
      if (this.classList.contains('react-flow')) return fallback
      return original?.get?.call(this) ?? 0
    },
  })
}
if (!SVGElement.prototype.getBoundingClientRect) {
  SVGElement.prototype.getBoundingClientRect = () => new DOMRect(0, 0, 0, 0)
}
if (!SVGGraphicsElement.prototype.getBBox) {
  SVGGraphicsElement.prototype.getBBox = () => new DOMRect(0, 0, 0, 0)
}
