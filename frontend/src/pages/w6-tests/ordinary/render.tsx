import { createElement, useEffect, useState, type ComponentType } from 'react'
import { act, cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'
import { setupCoreDom } from '@/pages/w2/core/coreTestHelpers'
import { FoundationProvider } from '@/foundation/provider'
import { resolveSkin } from '@/themes/registry'
import type { SkinDefinition } from '@/themes/types'

// Only DOM/React orchestration. All behavior under test belongs to production components/owners.
beforeEach(setupCoreDom)
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
export async function flushPromises() { await act(async () => { await Promise.resolve(); await Promise.resolve() }) }
export function enableAutoUnmount(_hook: unknown) { /* RTL cleanup is registered above. */ }
export class Dom {
  constructor(readonly element: Element | null) {}
  exists() { return this.element !== null }
  text() { return this.element?.textContent ?? '' }
  classes() { return [...(this.element?.classList ?? [])] }
  attributes(): Record<string, string>
  attributes(name: string): string | undefined
  attributes(name?: string) { return name ? this.element?.getAttribute(name) ?? undefined : Object.fromEntries([...this.element?.attributes ?? []].map(row => [row.name, row.value])) }
  find(selector: string) { return new Dom(this.element?.querySelector(selector) ?? null) }
  get(selector: string) { const found = this.find(selector); if (!found.element) throw new Error(`Missing ${selector}`); return found as Dom & { readonly element: Element } }
  findAll(selector: string) { return [...this.element?.querySelectorAll(selector) ?? []].map(node => new Dom(node)) }
  isVisible() { return !!this.element && !this.element.closest('[hidden]') && getComputedStyle(this.element).display !== 'none' && !this.element.closest('details:not([open]) > :not(summary)') }
  async trigger(type: string, init: Record<string, unknown> = {}) { if (!this.element) throw new Error('Missing event target'); act(() => { fireEvent(this.element!, new Event(type, { bubbles: true, cancelable: true, ...init })) }); await flushPromises() }
  async setValue(value: string | boolean) { if (!this.element) throw new Error('Missing input target'); act(() => {
    if (typeof value === 'boolean') fireEvent.click(this.element!)
    else fireEvent.change(this.element!, { target: { value } })
  }); await flushPromises() }
}
type MountProps<P> = Omit<P, 'skin'> & Partial<Pick<P, Extract<keyof P, 'skin'>>>
export function mount<P extends object>(Component: ComponentType<P>, options: { props: MountProps<P>; attachTo?: unknown; global?: unknown }) {
  let props = options.props
  function Host({ value }: { value: MountProps<P> }) {
    const [skin, setSkin] = useState<SkinDefinition>(() => resolveSkin(document.documentElement.dataset.skin))
    useEffect(() => { const observer = new MutationObserver(() => setSkin(resolveSkin(document.documentElement.dataset.skin))); observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-skin'] }); return () => observer.disconnect() }, [])
    return <FoundationProvider skin={skin} reducedMotion>{createElement(Component, { skin, ...value } as P)}</FoundationProvider>
  }
  const view = render(<Host value={props} />)
  return Object.assign(new Dom(view.container), { async setProps(patch: Partial<MountProps<P>>) { props = { ...props, ...patch }; view.rerender(<Host value={props} />); await flushPromises() }, unmount: view.unmount })
}
