import { createElement, useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type ComponentType, type KeyboardEvent } from 'react'
import { Outlet, useBlocker, useLocation, useParams } from 'react-router'
import { flushSync } from 'react-dom'
import { FoundationProvider } from '@/foundation/provider'
import { semanticName } from '@/foundation/semanticRegistry'
import { UiActionButton, UiConfirmDialog } from '@/foundation/components'
import { applySkin, getSkinSnapshot, subscribeSkin } from '@/themes/state'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { w2PageLoaders } from '@/migration/w2Routes'
import { w3PageLoader } from '@/migration/w3Routes'
import { w4PageLoader } from '@/migration/w4Routes'
import { w5PageLoader } from '@/migration/w5Routes'
import type { ReactViewHost } from '@/migration/reactViewLifecycle'
import { ApplicationOwnership, routeFromLocation, type RouteOwnership } from './ownership'
import { AppSidebar } from './AppSidebar'
import { StoryAccountingDialog } from './StoryAccountingDialog'
import type { StoryAccountingOwner } from './storyAccounting'
import './app.css'

export function RouteDialogs({ scope }: { scope: RouteOwnership }) {
  const state = useSyncExternalStore(scope.dialog.subscribe, scope.dialog.getSnapshot, scope.dialog.getSnapshot)
  return <>{state.notice && <div className="w2-status" role="alert">{state.notice}</div>}<UiConfirmDialog open={state.open} title="离开当前页面" confirmActionKey="ui.discardChanges" policy={state.blocked ? { kind: 'block', reason: state.reason } : { kind: 'allow' }} onConfirm={() => scope.dialog.choose(true)} onCancel={() => scope.dialog.choose(false)}>{state.reason}</UiConfirmDialog></>
}
export function RouteScreen({ application, Component }: { application: ApplicationOwnership; Component?: ComponentType<W2PageProps> }) {
  const location = useLocation(), params = useParams()
  const route = routeFromLocation(location, params), scope = application.scope(route)
  const skin = useSyncExternalStore(subscribeSkin, getSkinSnapshot, getSkinSnapshot)
  const [loaded, setLoaded] = useState<{ key: string; page?: ComponentType<W2PageProps>; error?: string }>({ key: scope.key, page: Component })
  const [dead, setDead] = useState<string>()
  const [failedCleanup, setFailedCleanup] = useState(false)
  const host = useRef<HTMLDivElement & ReactViewHost>(null)
  useLayoutEffect(() => { application.commit(scope) }, [application, scope])
  useEffect(() => {
    if (!application.healthy) { setLoaded({ key: scope.key, error: '页面资源未完全释放，请保留当前操作身份并重新检查' }); return }
    if (Component) { setLoaded({ key: scope.key, page: Component }); return }
    let active = true
    const path = route.path.length > 1 ? route.path.replace(/\/$/, '') : route.path
    const load = w2PageLoaders[path] ?? w3PageLoader(path) ?? w4PageLoader(path) ?? w5PageLoader(path)
    setLoaded({ key: scope.key })
    if (!load) { setLoaded({ key: scope.key, error: '页面入口不可用' }); return }
    void load().then(page => { if (active && scope.active) setLoaded({ key: scope.key, page }) }, () => { if (active && scope.active) setLoaded({ key: scope.key, error: '页面加载失败，请返回并重新打开' }) })
    return () => { active = false }
  }, [scope, Component])
  useEffect(() => {
    if (dead === scope.key) return
    const beforeUnload = (event: BeforeUnloadEvent) => { if (scope.active && scope.decision().kind !== 'ALLOW') { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [scope, dead])
  useLayoutEffect(() => {
    const element = host.current
    if (!element) return
    const lifecycle = Object.freeze({ disposeIfSafe: () => {
      if (scope.active && scope.decision().kind !== 'ALLOW') return false
      flushSync(() => setDead(scope.key))
      const healthy = scope.dispose()
      if (!healthy) flushSync(() => setFailedCleanup(true))
      return healthy
    } })
    Object.defineProperty(element, 'reactViewLifecycle', { value: lifecycle, configurable: true, writable: false })
    return () => { if (element.reactViewLifecycle === lifecycle) delete (element as HTMLElement & { reactViewLifecycle?: typeof lifecycle }).reactViewLifecycle }
  }, [scope])
  const props: W2PageProps = { route, skin, setSkin: applySkin, navigation: scope.navigation, lifecycle: scope.lifecycle, legacy: { task: scope.boundary.port } }
  const page = loaded.key === scope.key ? loaded.page : undefined
  return <div key={scope.key} ref={host} data-page-runtime="react" data-app-route-owner>{dead === scope.key && failedCleanup && <p role="alert">页面资源未完全释放，请保留当前操作身份并重新检查</p>}{dead !== scope.key && <><RouteDialogs scope={scope} />{loaded.key === scope.key && loaded.error ? <p role="alert">{loaded.error}</p> : page ? createElement(page, { ...props, key: scope.key }) : <p role="status">正在打开页面…</p>}</>}</div>
}
export function ApplicationLayout({ application, story, shell = true }: { application: ApplicationOwnership; story?: StoryAccountingOwner; shell?: boolean }) {
  const location = useLocation(), fullPath = location.pathname + location.search + location.hash
  const skin = useSyncExternalStore(subscribeSkin, getSkinSnapshot, getSkinSnapshot)
  const blocker = useBlocker(({ nextLocation }) => !application.canPass(nextLocation.pathname + nextLocation.search + nextLocation.hash))
  const attempt = useRef(0)
  useEffect(() => {
    if (blocker.state !== 'blocked') return
    const scope = application.current, ticket = ++attempt.current
    const destination = blocker.location.pathname + blocker.location.search + blocker.location.hash
    if (!scope) { blocker.reset(); return }
    void application.approve(scope, { destination }).then(allowed => {
      if (ticket !== attempt.current || !application.active) return
      if (allowed && application.canPass(destination)) blocker.proceed(); else blocker.reset()
    })
    return () => { attempt.current++ }
  }, [blocker, application])
  const canvas = /^\/workflows\/[^/]+\/?$/.test(location.pathname) || /^\/requirements\/(?!new\/?$)[^/]+\/?$/.test(location.pathname) || /^\/ppt\/[^/]+\/?$/.test(location.pathname)
  const [open, setOpen] = useState(false)
  const toggle = useRef<HTMLButtonElement>(null), panel = useRef<HTMLDivElement>(null), main = useRef<HTMLDivElement>(null)
  useEffect(() => { setOpen(false); if (typeof window.scrollTo === 'function' && !navigator.userAgent.includes('jsdom')) window.scrollTo({ top: 0 }) }, [fullPath])
  useLayoutEffect(() => { if (open) panel.current?.querySelector<HTMLElement>('a,button')?.focus() }, [open])
  const close = () => { setOpen(false); toggle.current?.focus() }
  function keys(event: KeyboardEvent<HTMLDivElement>) {
    if (!canvas || !open) return
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); return }
    if (event.key !== 'Tab') return
    const items = [toggle.current, ...Array.from(panel.current?.querySelectorAll<HTMLElement>('a[href],button:not(:disabled)') ?? [])].filter((item): item is HTMLElement => !!item)
    if (event.shiftKey && document.activeElement === items[0]) { event.preventDefault(); items.at(-1)?.focus() }
    else if (!event.shiftKey && document.activeElement === items.at(-1)) { event.preventDefault(); items[0]?.focus() }
  }
  return <FoundationProvider skin={skin}>{shell ? <><a className="skip-link" href="#main-content" onClick={event => { event.preventDefault(); (main.current?.querySelector<HTMLElement>('[id="main-content"]') ?? main.current)?.focus() }}>跳到主内容</a><div className={`app-shell${canvas ? ' canvas-shell' : ''}`} onKeyDownCapture={keys}>
    {canvas ? <><UiActionButton actionKey={open ? 'ui.collapse' : 'ui.expand'} target={semanticName('app.navigation')} iconOnly buttonRef={toggle} className="canvas-navigation-toggle" expanded={open} controls="canvas-navigation" onAction={() => open ? close() : setOpen(true)} />{open && <><div className="canvas-navigation-backdrop" onClick={close} /><div ref={panel} id="canvas-navigation" className="canvas-navigation" role="dialog" aria-modal="true" aria-label="应用导航"><AppSidebar application={application} /></div></>}</> : <AppSidebar application={application} />}
    <div ref={main} tabIndex={-1} className="app-main" inert={canvas && open ? true : undefined}><Outlet /></div></div></> : <Outlet />}{story && <StoryAccountingDialog owner={story} />}</FoundationProvider>
}
