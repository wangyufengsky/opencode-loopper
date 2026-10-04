import { act } from 'react'
import { mountReactApplication } from '@/app/bootstrap'
import type { ApplicationRouteOverride } from '@/router'
import type { TaskApplicationOwner } from '@/stores/taskStore'
/** Executes the actual application owner/history/scope path in an isolated memory history. */
export async function mountApplicationHarness(options: { initialEntries?: string[]; initialIndex?: number; routes?: ApplicationRouteOverride[]; shell?: boolean; taskOwner?: TaskApplicationOwner; strict?: boolean } = {}) {
  const host = document.createElement('div'); document.body.append(host)
  let mounted!: ReturnType<typeof mountReactApplication>
  await act(async () => { mounted = mountReactApplication(host, { initialEntries: ['/'], shell: false, story: false, ...options }) })
  const settle = async () => { await act(async () => { await Promise.resolve(); await Promise.resolve() }) }
  await settle()
  return { ...mounted, settle, unmount: () => { act(() => { mounted.unmount(true) }); host.remove() }, async navigate(to: string | number, replace = false) { await act(async () => { if (typeof to === 'number') await mounted.router.navigate(to); else await mounted.application.current?.navigation.go(to, replace) }); await settle() } }
}
