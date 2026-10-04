import { createContext, createElement, useContext, type ComponentType } from 'react'
import { act, fireEvent, render, type RenderResult } from '@testing-library/react'
import { afterEach } from 'vitest'
import { FoundationProvider } from '@/foundation/provider'
import { skins } from '@/themes/registry'
import { foundationDOM, pageProps } from '@/pages/w2/workflow/page.test-support'
import type { W2PageProps } from '@/pages/w2/shared'

const TestPageContext = createContext<W2PageProps | null>(null)
export function useTestPage() { const page = useContext(TestPageContext); if (!page) throw new Error('Actual React child must be mounted in a test page'); return page }

/** DOM-only test queries over a real React root; no Vue lifecycle, component stubs or business state. */
export class ReactDOMQuery<T extends Element = HTMLElement> {
  constructor(private readonly target: T | null) {}
  get element(): T { if (!this.target) throw new Error('React DOM element not found'); return this.target }
  exists() { return !!this.target }
  text() { return this.target?.textContent?.trim() ?? '' }
  html() { return this.target?.outerHTML ?? '' }
  attributes(name: string) { return this.target?.getAttribute(name) ?? undefined }
  find<E extends Element = HTMLElement>(selector: string): ReactDOMQuery<E> { return new ReactDOMQuery(this.target?.querySelector<E>(selector) ?? null) }
  get<E extends Element = HTMLElement>(selector: string): ReactDOMQuery<E> { const found = this.find<E>(selector); if (!found.element) throw new Error(`React DOM selector not found: ${selector}`); return found }
  findAll<E extends Element = HTMLElement>(selector: string): ReactDOMQuery<E>[] { return Array.from(this.target?.querySelectorAll<E>(selector) ?? [], e => new ReactDOMQuery(e)) }
  async trigger(event: string, options: Record<string, unknown> = {}) {
    if (!this.element) throw new Error('Cannot dispatch to a missing React DOM element')
    const aliases: Record<string, string> = { keydown: 'keyDown', keyup: 'keyUp', pointerdown: 'pointerDown', pointerup: 'pointerUp', pointermove: 'pointerMove', mousedown: 'mouseDown', mouseup: 'mouseUp' }
    const [kind, key] = event.split('.')
    await act(async () => { const dispatch = fireEvent[(aliases[kind!] ?? kind) as keyof typeof fireEvent]; if (typeof dispatch !== 'function') throw new Error(`Unsupported DOM event ${event}`); if (kind!.startsWith('pointer')) { const native = new Event(kind!, { bubbles: true, cancelable: true }); Object.assign(native, options); fireEvent(this.element, native) } else dispatch(this.element!, { ...(key ? { key: key === 'enter' ? 'Enter' : key } : {}), ...options }) })
  }
  async setValue(value: string | number | boolean) {
    const element = this.element
    if (!(element instanceof HTMLInputElement || element instanceof HTMLSelectElement || element instanceof HTMLTextAreaElement)) throw new Error('setValue requires a real form control')
    if (element.matches(':disabled')) return
    await act(async () => {
      if (element instanceof HTMLInputElement && ['checkbox', 'radio'].includes(element.type)) { if (element.checked !== Boolean(value)) fireEvent.click(element) }
      else fireEvent.change(element, { target: { value: String(value) } })
    })
  }
}
const roots = new Set<{ unmount(): void }>()
afterEach(() => { for (const root of [...roots]) root.unmount() })
export function enableAutoUnmount(_hook: unknown) { /* Every root is already cleaned up by this test module's afterEach. */ }
export async function flushPromises() { await act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }) }

export function mount<P extends object>(Component: ComponentType<P>, options: { props?: Partial<P>; global?: unknown; attachTo?: HTMLElement | string } = {}) {
  foundationDOM()
  let props = { ...options.props } as P, result: RenderResult, removed = false
  const page = pageProps('/requirements/req'), retained = new Map<object, () => void>()
  page.lifecycle.retain = (key, dispose) => { retained.set(key, dispose) }
  const events: Record<string, unknown[][]> = {}
  const eventProps = () => Object.fromEntries(['reload', 'patch', 'add', 'change', 'remove', 'close', 'insert', 'label', 'roleLabel', 'revision', 'select', 'edge', 'connect', 'connectPair', 'layout', 'cancel', 'updated', 'accepted', 'navigate'].map(name => [`on${name[0]!.toUpperCase()}${name.slice(1)}`, (...args: unknown[]) => { (events[name] ??= []).push(args); const callback = (props as Record<string, unknown>)[`on${name[0]!.toUpperCase()}${name.slice(1)}`]; if (typeof callback === 'function') callback(...args) }]))
  const frame = () => <TestPageContext.Provider value={page}><FoundationProvider skin={skins[0]!} reducedMotion>{createElement(Component, { ...props, ...eventProps() })}</FoundationProvider></TestPageContext.Provider>
  result = render(frame(), typeof options.attachTo === 'string' ? { container: document.querySelector(options.attachTo) as HTMLElement } : options.attachTo ? { container: options.attachTo } : undefined)
  const view = Object.assign(new ReactDOMQuery(result.container), {
    async setProps(next: Partial<P>) { props = { ...props, ...next }; result.rerender(frame()); await flushPromises() },
    props<K extends keyof P>(key: K) { return props[key] },
    emitted(name: string) { return events[name] },
    unmount() { if (!removed) { removed = true; result.unmount(); for (const dispose of retained.values()) dispose(); retained.clear(); roots.delete(view) } },
  })
  roots.add(view); return view
}
