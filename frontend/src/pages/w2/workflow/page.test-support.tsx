import { FoundationProvider } from '@/foundation/provider'
import { skins } from '@/themes/registry'
import type { TaskPort, W2PageProps, W2Target } from '@/pages/w2/shared/types'
import type { ReactNode } from 'react'
import { vi } from 'vitest'
export function pageProps(path: string, query: W2PageProps['route']['query'] = {}): W2PageProps {
  const taskSnapshot = { usingDemo: false, projects: [], tasks: [], taskItems: [], taskFacets: {}, loading: false }
  const task: TaskPort = { getSnapshot: () => taskSnapshot, subscribe: () => () => undefined, loadProjects: async () => [], loadTaskSummaries: async () => undefined,
    invalidateTaskSummaries() {}, setTaskArchived: async () => undefined, deleteArchivedTask: async () => undefined, refreshRuntime: async () => undefined,
    startRuntime: async () => undefined, restartRuntime: async () => undefined, activateDemo() {}, deactivateDemo: async () => undefined,
    replaceProject() {}, addProject() {}, removeProject() {} }
  return { route: { path, fullPath: path, query, params: {} }, navigation: { go: vi.fn(async (_to: W2Target) => true), goAccepted: vi.fn(async () => true), back: vi.fn(), registerGuard: vi.fn(() => () => undefined), guardChanged: vi.fn() },
    lifecycle: { retain: vi.fn() }, legacy: { task }, skin: skins[0]!, setSkin: vi.fn() }
}
export function pageFrame(children: ReactNode) { return <FoundationProvider skin={skins[0]!} reducedMotion>{children}</FoundationProvider> }
export function foundationDOM() {
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn(), addListener: vi.fn(), removeListener: vi.fn() }))
  const computedStyle = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation(element => computedStyle(element))
}
export function deferred<T>() { let resolve!: (value: T) => void, reject!: (failure: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
export async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve() }
