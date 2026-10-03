import { createElement, type ComponentType } from 'react'
import { createRoot } from 'react-dom/client'
import { flushSync } from 'react-dom'

/** A view-only island. Its owner retains routing, commands and subscriptions. */
export function mountReactView<P extends object>(host: HTMLElement, component: ComponentType<P>) {
  const root = createRoot(host)
  let active = true
  return {
    render(props: P) {
      if (!active) return
      // Vue's post-flush watcher calls this outside React's rendering phase.
      // Commit before the owner restores focus or inspects the selected node.
      flushSync(() => root.render(createElement(component, props)))
    },
    unmount() {
      if (!active) return
      active = false
      flushSync(() => root.unmount())
    },
  }
}
