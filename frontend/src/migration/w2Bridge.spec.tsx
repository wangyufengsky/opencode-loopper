import { createElement, useLayoutEffect, useState } from 'react'
import { act } from '@testing-library/react'
import { createPinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import W2RouteBridge from './W2RouteBridge.vue'
import type { ReactViewHost } from './reactViewLifecycle'
import { useLeaveGuard, useRetainedOwner } from '@/pages/w2/shared'
import type { W2LeaveGuard, W2PageProps } from '@/pages/w2/shared/types'
import { createOperationOwner } from '@/foundation/contracts/receipt'
import { decideLeave } from '@/foundation/contracts/navigation'
import { applySkin } from '@/themes/state'

const harness = vi.hoisted(() => ({ load: null as null | (() => Promise<unknown>) }))
vi.mock('./w2Routes', () => ({ w2PageLoaders: {
  '/projects': () => harness.load!(), '/tasks': () => harness.load!(),
} }))
vi.mock('./w3Routes', () => ({ w3PageLoader: (path: string) =>
  /^\/(template-tasks|knowledge|ppt)(\/|$)/.test(path) ? () => harness.load!() : undefined }))
let props: W2PageProps, risk: W2LeaveGuard
let attached = 0, setups = 0, detached = 0, retired = 0, written = 0, cleanupThrows = false
const roots: VueWrapper[] = []
function Probe(pageProps: W2PageProps) {
  props = pageProps
  const [owner] = useState(() => ({})), [draft, setDraft] = useState('original')
  useRetainedOwner(pageProps, owner, () => { retired++; if (cleanupThrows) throw new Error('owned cleanup failed') })
  useLeaveGuard(pageProps, request => risk(request))
  useLayoutEffect(() => { attached++; setups++; return () => { attached--; detached++ } }, [])
  return createElement('main', { className: 'ui-shell-main' },
    createElement('p', {}, `React production island ${pageProps.route.fullPath}`),
    createElement('input', { 'aria-label': '草稿', value: draft, onChange: e => setDraft(e.currentTarget.value) }),
    createElement('button', { onClick: () => { written++ } }, '显式命令'))
}
async function settle() { await act(async () => { await flushPromises(); await flushPromises() }) }
async function setup(path = '/projects') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/projects', component: W2RouteBridge }, { path: '/tasks', component: W2RouteBridge },
    { path: '/template-tasks', component: W2RouteBridge },
    { path: '/template-tasks/document-runs/:id', component: W2RouteBridge },
    { path: '/template-tasks/source-runs/:id', component: W2RouteBridge },
    { path: '/knowledge/:conversationId?', component: W2RouteBridge },
    { path: '/ppt/:id', component: W2RouteBridge },
    { path: '/exit', component: { template: '<p>原 Vue 路由仍可用</p>' } },
  ] })
  await router.push(path); await router.isReady()
  let root!: VueWrapper
  await act(async () => { root = mount(defineComponent({ setup: () => () => h(RouterView) }), { attachTo: document.body, global: { plugins: [createPinia(), router] } }); await flushPromises() })
  roots.push(root); await settle(); return { root, router }
}
async function move(router: Router, to: string) { let result: unknown; await act(async () => { result = await router.push(to); await flushPromises() }); return result }
beforeEach(() => {
  harness.load = async () => Probe; risk = () => ({ kind: 'ALLOW' })
  attached = 0; setups = 0; detached = 0; retired = 0; written = 0; cleanupThrows = false
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  const computed = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation(element => computed(element))
})
afterEach(async () => {
  await act(async () => { for (const root of roots.splice(0)) root.unmount(); await flushPromises() })
  document.body.innerHTML = ''; vi.restoreAllMocks(); vi.unstubAllGlobals(); applySkin('spdb')
})
describe('W2 actual Vue history / React lifetime boundary', () => {
  it.each(['pending', 'unknown', 'dirty'])('public instance disposal refuses %s without releasing original owners or writes', async kind => {
    const { root } = await setup()
    risk = () => kind === 'dirty' ? { kind: 'CONFIRM_DISCARD', description: '保留原草稿', draftRevision: 1 } : { kind: 'BLOCK', reason: '保留原操作', recoveryAction: '恢复原操作' }
    const instance = root.findComponent(W2RouteBridge).vm as unknown as { disposeIfSafe(): boolean }
    const host = root.find('[data-w2-route-bridge]').element as ReactViewHost
    expect(host.reactViewLifecycle?.disposeIfSafe).toBe(instance.disposeIfSafe)
    expect(host.reactViewLifecycle!.disposeIfSafe()).toBe(false)
    expect(attached).toBe(1); expect(retired).toBe(0); expect(written).toBe(0)
    expect(document.body.textContent).toContain('React production island')
  })
  it('public safe disposal immediately unmounts only its React root and cannot remount or write on theme changes', async () => {
    const { root, router } = await setup()
    const instance = root.findComponent(W2RouteBridge).vm as unknown as { disposeIfSafe(): boolean }
    const host = root.find('[data-w2-route-bridge]').element as ReactViewHost
    expect(Object.isFrozen(host.reactViewLifecycle)).toBe(true)
    expect(Object.getOwnPropertyDescriptor(host, 'reactViewLifecycle')).toEqual({ value: host.reactViewLifecycle, writable: false, enumerable: false, configurable: true })
    expect(host.reactViewLifecycle?.disposeIfSafe).toBe(instance.disposeIfSafe)
    await act(async () => { expect(host.reactViewLifecycle!.disposeIfSafe()).toBe(true) })
    expect(attached).toBe(0); expect(retired).toBe(1); expect(written).toBe(0)
    await act(async () => { props.setSkin('github-white'); await flushPromises() })
    expect(attached).toBe(0); expect(retired).toBe(1); expect(written).toBe(0)
    expect(instance.disposeIfSafe()).toBe(true); expect(retired).toBe(1)
    await move(router, '/exit'); expect(host.reactViewLifecycle).toBeUndefined()
  })
  it('public disposal reports a failing owner while still releasing this React root and all remaining owners', async () => {
    const { root } = await setup(); cleanupThrows = true
    const instance = root.findComponent(W2RouteBridge).vm as unknown as { disposeIfSafe(): boolean }
    await act(async () => { expect(instance.disposeIfSafe()).toBe(false); await flushPromises() })
    expect(attached).toBe(0); expect(retired).toBe(1); expect(written).toBe(0)
    expect(document.body.textContent).toContain('页面资源未完全释放')
    expect(instance.disposeIfSafe()).toBe(false); expect(retired).toBe(1)
  })
  it.each(['/template-tasks', '/template-tasks/document-runs/doc-original', '/template-tasks/source-runs/source-original', '/knowledge', '/knowledge/conversation-original', '/ppt/ppt-original'])('W3 real history route %s forwards original params and holds the same owner across theme and blocked navigation', async path => {
    const { router } = await setup(path)
    expect(props.route.fullPath).toBe(path)
    if (path.includes('original')) expect(Object.values(props.route.params)).toContain(path.split('/').at(-1))
    expect(attached).toBe(1); expect(retired).toBe(0); expect(written).toBe(0)
    risk = () => ({ kind: 'BLOCK', reason: '保留原 File 与未知写入身份', recoveryAction: '恢复原操作' })
    await move(router, '/projects'); expect(router.currentRoute.value.path).toBe(path)
    await move(router, `${path}?other=1`); expect(router.currentRoute.value.fullPath).toBe(path)
    await act(async () => { props.setSkin('github-white'); await flushPromises() })
    expect(attached).toBe(1); expect(retired).toBe(0); expect(written).toBe(0)
    risk = () => ({ kind: 'ALLOW' }); await move(router, '/exit')
    expect(retired).toBe(1); expect(attached).toBe(0); expect(written).toBe(0)
  })
  it('StrictMode replay and skin change do not write or retire; actual route exit immediately retires once', async () => {
    const { router } = await setup()
    expect(document.body.textContent).toContain('React production island'); expect(attached).toBe(1); expect(setups).toBe(2); expect(detached).toBe(1); expect(retired).toBe(0); expect(written).toBe(0)
    await act(async () => { props.setSkin('tech-blue'); await flushPromises() })
    expect(retired).toBe(0); expect(attached).toBe(1); expect(written).toBe(0)
    await move(router, '/exit')
    expect(attached).toBe(0); expect(retired).toBe(1); expect(document.body.textContent).toContain('原 Vue 路由仍可用')
  })
  it.each(['pending', 'unknown'])('%s blocks real route leave and query change without unloading or replaying writes', async () => {
    const { router } = await setup(); risk = () => ({ kind: 'BLOCK', reason: '保留原身份', recoveryAction: '恢复原操作' })
    await move(router, '/tasks'); expect(router.currentRoute.value.fullPath).toBe('/projects')
    await move(router, '/projects?q=new'); expect(router.currentRoute.value.fullPath).toBe('/projects')
    expect(retired).toBe(0); expect(attached).toBe(1); expect(written).toBe(0); expect(document.body.textContent).toContain('保留原身份')
  })
  it('dirty confirmation defaults to stay; a later pending state prevents the same confirmation discarding the owner', async () => {
    const { router } = await setup(); risk = () => ({ kind: 'CONFIRM_DISCARD', description: '有原草稿', draftRevision: 1 })
    const pending = router.push('/tasks'); await settle()
    const stay = [...document.querySelectorAll('button')].find(button => button.textContent?.includes('留在'))
    expect(stay).toBeTruthy(); await act(async () => { stay!.click(); await flushPromises() }); await pending
    expect(router.currentRoute.value.path).toBe('/projects'); expect(retired).toBe(0)
    const second = router.push('/tasks'); await settle()
    risk = () => ({ kind: 'BLOCK', reason: '原写入已发送', recoveryAction: '恢复原操作' })
    await act(async () => { props.navigation.guardChanged(); await flushPromises() })
    const discard = [...document.querySelectorAll('button')].find(button => button.textContent?.includes('放弃'))
    expect(discard?.disabled).toBe(true)
    const cancel = [...document.querySelectorAll('button')].find(button => button.textContent?.includes('留在'))!
    await act(async () => { cancel.click(); await flushPromises() }); await second
    expect(router.currentRoute.value.path).toBe('/projects'); expect(retired).toBe(0); expect(written).toBe(0)
  })
  it('only an authentic accepted handoff to its exact destination can leave; navigation consumes no second write', async () => {
    const { router } = await setup()
    const operation = createOperationOwner({ owner: { domain: 'w2', id: 'original', epoch: 1 }, label: '原命令',
      input: { endpoint: '/original', method: 'POST', body: { title: 'original' } }, capability: { kind: 'NONE' },
      write: async () => { written++; return { id: 'accepted' } }, read: async () => { throw new Error('await navigation') }, handoffTarget: () => '/tasks' })
    await expect(operation.execute()).rejects.toThrow('await navigation')
    risk = request => decideLeave({ operations: [operation.leaveRisk()], dirty: false, unsentFiles: 0, draftRevision: 0, request })
    const permit = operation.prepareHandoff()
    expect(await props.navigation.goAccepted('/exit', permit)).toBe(false); expect(router.currentRoute.value.path).toBe('/projects')
    await move(router, '/tasks'); expect(router.currentRoute.value.path).toBe('/projects')
    await act(async () => { expect(await props.navigation.goAccepted('/tasks', permit)).toBe(true); await flushPromises() })
    expect(router.currentRoute.value.path).toBe('/tasks'); expect(operation.getSnapshot().phase).toBe('SETTLED'); expect(written).toBe(1)
  })
  it('shared bridge reuse and repeated enter/exit never accumulate view leases or retained owners', async () => {
    const { router } = await setup()
    for (let i = 0; i < 4; i++) { await move(router, i % 2 ? '/projects' : '/tasks'); expect(attached).toBe(1); expect(retired).toBe(i + 1); expect(written).toBe(0) }
    await move(router, '/exit'); expect(attached).toBe(0); expect(retired).toBe(5)
  })
  it('a reused bridge releases the previous page beforeunload guard and binds exactly one guard to the new owner', async () => {
    const add = vi.spyOn(window, 'addEventListener'), remove = vi.spyOn(window, 'removeEventListener')
    const registrations = () => add.mock.calls.filter(([type]) => type === 'beforeunload')
    const releases = () => remove.mock.calls.filter(([type]) => type === 'beforeunload')
    const { router } = await setup()
    expect(registrations()).toHaveLength(1); expect(releases()).toHaveLength(1)
    risk = () => ({ kind: 'BLOCK', reason: '保留原身份', recoveryAction: '恢复原操作' })
    const blocked = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(blocked); expect(blocked.defaultPrevented).toBe(true)
    await move(router, '/tasks'); expect(registrations()).toHaveLength(1); expect(releases()).toHaveLength(1); expect(retired).toBe(0)
    risk = () => ({ kind: 'ALLOW' }); await move(router, '/tasks')
    expect(registrations()).toHaveLength(2); expect(releases()).toHaveLength(2); expect(retired).toBe(1)
    expect(releases()[1]![1]).toBe(registrations()[0]![1])
    await move(router, '/exit'); expect(releases()).toHaveLength(3); expect(attached).toBe(0)
    const left = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(left); expect(left.defaultPrevented).toBe(false)
    expect(written).toBe(0)
  })
  it('a deferred page import cannot mount after real root retirement', async () => {
    let resolve!: (component: unknown) => void; harness.load = () => new Promise(done => { resolve = done })
    const { root } = await setup(); expect(attached).toBe(0)
    await act(async () => { root.unmount(); resolve(Probe); await flushPromises() }); roots.splice(roots.indexOf(root), 1)
    expect(attached).toBe(0); expect(retired).toBe(0); expect(written).toBe(0)
  })
  it('cleanup failure still releases every view and blocks a replacement rather than hiding the failure', async () => {
    const { router } = await setup(); cleanupThrows = true
    await move(router, '/tasks')
    expect(attached).toBe(0); expect(retired).toBe(1); expect(document.body.textContent).toContain('页面资源未完全释放'); expect(written).toBe(0)
    cleanupThrows = false
  })
})
