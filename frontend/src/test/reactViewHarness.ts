import { createElement, StrictMode, type ComponentType } from 'react'
import { createRoot } from 'react-dom/client'
import { flushSync } from 'react-dom'

/** Test-only isolated renderer; production has one application root. */
export function mountReactView<P extends object>(host: HTMLElement, component: ComponentType<P>, options: Readonly<{ strict?: boolean }> = {}) {
  const root = createRoot(host)
  let active = true
  return {
    render(props: P) {
      if (!active) return
      // Commit before the owner restores focus or inspects the selected node.
      const element = createElement(component, props)
      flushSync(() => root.render(options.strict ? createElement(StrictMode, null, element) : element))
    },
    unmount() {
      if (!active) return
      active = false
      flushSync(() => root.unmount())
    },
  }
}
