import { inject, readonly, ref, type App, type InjectionKey } from 'vue'
import type { Router } from 'vue-router'
import { captureCanvasRuntime, type CanvasRuntime } from './canvasRuntime'

const runtimeReader: InjectionKey<() => CanvasRuntime> = Symbol('canvas-runtime-reader')
function readRuntime(path: string): CanvasRuntime {
  try { return captureCanvasRuntime(path, window.localStorage) } catch { return 'react' }
}
export function installCanvasRuntime(app: App, router: Router) {
  // Vue Router remains the only history owner. No navigation or reload is initiated here.
  app.provide(runtimeReader, () => readRuntime(router.currentRoute.value.path))
}
export function useCanvasRuntime() {
  const read = inject(runtimeReader, () => readRuntime(typeof location === 'undefined' ? '/' : location.pathname))
  return readonly(ref(read()))
}
