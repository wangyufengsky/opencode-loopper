import { createTaskApplicationOwner, type TaskApplicationOwner } from '@/stores/taskStore'
import { createW2TaskPort } from '@/migration/w2TaskPort'
import { createW4TaskBoundary } from '@/migration/w4TaskBoundary'
import { w4PageLoader } from '@/migration/w4Routes'
import { w5PageLoader } from '@/migration/w5Routes'
import { createRouteLeaveCoordinator } from '@/pages/w2/shared/leave'
import { navigateAcceptedHandoff } from '@/foundation/contracts/receipt'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { LeaveDecision } from '@/foundation/contracts/types'
import type { BridgeDialogPort, BridgeDialogSnapshot } from './dialog'
import type { W2Navigation, W2Route, W2Target } from '@/pages/w2/shared/types'

export function targetPath(to: W2Target): string {
  if (typeof to === 'string') return to
  const query = new URLSearchParams(Object.entries(to.query ?? {}).filter((entry): entry is [string, string | number] => entry[1] !== null && entry[1] !== undefined).map(([key, value]) => [key, String(value)]))
  return to.path + (query.size ? `?${query}` : '') + (to.hash ?? '')
}
export function routeFromLocation(location: { pathname: string; search: string; hash: string }, params: Record<string, string | undefined> = {}): W2Route {
  const query: W2Route['query'] extends Readonly<infer T> ? T : never = {}
  const search = new URLSearchParams(location.search)
  for (const key of new Set(search.keys())) { const values = search.getAll(key); query[key] = values.length > 1 ? values : values[0] }
  return { path: location.pathname, fullPath: location.pathname + location.search + location.hash, query, params: Object.fromEntries(Object.entries(params).filter((entry): entry is [string, string] => entry[1] !== undefined)) }
}
export class RouteOwnership {
  readonly owners = new Map<object, () => void>()
  readonly boundary: ReturnType<typeof createW2TaskPort>
  readonly listeners = new Set<() => void>()
  readonly lifecycle = Object.freeze({ retain: (key: object, dispose: () => void) => { if (this.active && !this.owners.has(key)) this.owners.set(key, dispose) } })
  active = true
  healthy = true
  private dialogSnapshot: BridgeDialogSnapshot = { open: false, blocked: false, reason: '', notice: '' }
  private confirmation?: (choice: boolean) => void
  private currentDecision?: () => LeaveDecision
  private initialFocus?: HTMLElement
  readonly leaves = createRouteLeaveCoordinator((decision, current) => new Promise<boolean>(resolve => {
    this.initialFocus = document.activeElement instanceof HTMLElement ? document.activeElement : undefined
    this.confirmation = resolve; this.currentDecision = () => { try { return current() } catch { return {kind:'BLOCK',reason:'无法核对当前操作，原页面保持不变',recoveryAction:'核对原操作'} } }
    this.publish({ open: true, blocked: false, reason: decision.description, notice: '' })
  }))
  readonly dialog: BridgeDialogPort = {
    getSnapshot: () => this.dialogSnapshot,
    subscribe: listener => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } },
    choose: allow => {
      if (!this.confirmation) return
      const current = this.currentDecision?.()
      if (allow && current?.kind === 'BLOCK') { this.publish({ ...this.dialogSnapshot, blocked: true, reason: current.reason, notice: current.reason }); return }
      const finish = this.confirmation; this.confirmation = undefined; this.currentDecision = undefined
      this.publish({ ...this.dialogSnapshot, open: false, blocked: false })
      if (!allow && this.initialFocus?.isConnected) this.initialFocus.focus()
      finish(allow)
    },
  }
  readonly navigation: W2Navigation
  constructor(readonly key: string, path: string, task: TaskApplicationOwner, private application: ApplicationOwnership) {
    this.boundary = w4PageLoader(path) || w5PageLoader(path) ? createW4TaskBoundary() : createW2TaskPort(task)
    this.leaves.register(() => this.application.globalDecision())
    this.navigation = {
      go: (to, replace, handoff) => this.application.go(this, { destination: targetPath(to), replace, handoff }),
      goAccepted: (to, permit) => navigateAcceptedHandoff(permit, targetPath(to), () => this.application.go(this, { destination: targetPath(to), handoff: permit })),
      back: () => { if (this.active) void this.application.driver?.navigate(-1) },
      registerGuard: guard => this.leaves.register(guard),
      guardChanged: () => {
        if (!this.currentDecision) return
        const current = this.currentDecision()
        this.publish({ ...this.dialogSnapshot, blocked: current.kind === 'BLOCK', reason: current.kind === 'BLOCK' ? current.reason : current.kind === 'CONFIRM_DISCARD' ? current.description : this.dialogSnapshot.reason })
      },
    }
  }
  private publish(next: BridgeDialogSnapshot) {
    if (JSON.stringify(next) === JSON.stringify(this.dialogSnapshot)) return
    this.dialogSnapshot = Object.freeze(next)
    for (const listener of [...this.listeners]) listener()
  }
  decision(request?: NavigationRequest): LeaveDecision {
    try { return this.leaves.read(request) } catch { return {kind:'BLOCK',reason:'无法核对当前操作，原页面保持不变',recoveryAction:'核对原操作'} }
  }
  async allow(request: NavigationRequest): Promise<boolean> {
    const allowed = await this.leaves.allow(request)
    if (!allowed && this.active) { const decision = this.decision(request); if (decision.kind === 'BLOCK') this.publish({ ...this.dialogSnapshot, notice: decision.reason }) }
    return allowed
  }
  dispose(): boolean {
    if (!this.active) return this.healthy
    this.active = false
    this.confirmation?.(false); this.confirmation = undefined; this.currentDecision = undefined
    this.leaves.dispose()
    const cleanups = [...this.owners.values()]; this.owners.clear()
    for (const cleanup of [...cleanups, () => this.boundary.dispose()]) { try { cleanup() } catch { this.healthy = false } }
    this.listeners.clear()
    return this.healthy
  }
}
export interface ApplicationDriver {
  navigate(to: string | number, options?: { replace?: boolean }): Promise<unknown> | void
  location(): string
}
/** Created once by bootstrap, outside render/effect replay. No implicit read/write/subscription. */
export class ApplicationOwnership {
  readonly task: TaskApplicationOwner
  private navigationSnapshot: Readonly<{knowledgePath:string}>
  private navigationListeners = new Set<() => void>()
  readonly getNavigationSnapshot = () => this.navigationSnapshot
  readonly subscribeNavigation = (listener: () => void) => { this.navigationListeners.add(listener); return () => { this.navigationListeners.delete(listener) } }
  readonly scopes = new Map<string, RouteOwnership>()
  current?: RouteOwnership
  driver?: ApplicationDriver
  active = true
  healthy = true
  private navigationEpoch = 0
  private approved?: { scope: RouteOwnership; request: NavigationRequest; revision?: number }
  readonly cleanup = new Set<() => void>()
  readonly globalGuards = new Set<() => LeaveDecision>()
  globalDecision(): LeaveDecision { try { for(const guard of this.globalGuards){const result=guard();if(result.kind!=='ALLOW')return result}return {kind:'ALLOW'} } catch { return {kind:'BLOCK',reason:'无法确认全局操作状态，请保留原页面',recoveryAction:'核对原操作'} } }
  /** Tests can exercise an explicit navigation rejection without inventing a backend API. */
  navigationInterceptor?: (request: NavigationRequest) => boolean | Promise<boolean>
  constructor(task?: TaskApplicationOwner) {
    this.task = task ?? createTaskApplicationOwner()
    let knowledgePath='/knowledge'
    try { const saved=sessionStorage.getItem('knowledge.lastPath'); if(saved && /^\/knowledge(?:\/[^/?#\\]+)?\/?(?:[?#].*)?$/.test(saved)) knowledgePath=saved } catch { /* Optional preference. */ }
    this.navigationSnapshot=Object.freeze({knowledgePath})
  }
  locationCommitted(path:string):void {
    if(!this.active || !/^\/knowledge(?:\/[^/?#\\]+)?\/?(?:[?#].*)?$/.test(path) || path===this.navigationSnapshot.knowledgePath)return
    this.navigationSnapshot=Object.freeze({knowledgePath:path})
    try{sessionStorage.setItem('knowledge.lastPath',path)}catch{/* Keep memory. */}
    for(const listener of [...this.navigationListeners])listener()
  }
  scope(route: W2Route): RouteOwnership {
    const key = w5PageLoader(route.path) ? route.fullPath : route.path
    let scope = this.scopes.get(key)
    if (!scope || !scope.active) { scope = new RouteOwnership(key, route.path, this.task, this); this.scopes.set(key, scope) }
    return scope
  }
  commit(scope: RouteOwnership): void {
    if (!this.active || this.current === scope) return
    const previous = this.current; this.current = scope
    if (previous && !previous.dispose()) this.healthy = false
    for (const [key, other] of this.scopes) if (other !== scope && !other.active) this.scopes.delete(key)
    this.approved = undefined
  }
  canPass(destination: string): boolean {
    const scope = this.current
    if (!scope || !scope.active) return this.healthy && this.globalDecision().kind === 'ALLOW'
    const request = this.approved?.scope === scope && this.approved.request.destination === destination ? this.approved.request : { destination }
    let decision: LeaveDecision
    try { decision = scope.decision(request) } catch { return false }
    if (decision.kind === 'BLOCK') return false
    return decision.kind === 'ALLOW' || !!this.approved && this.approved.scope === scope && this.approved.request.destination === destination && this.approved.revision === decision.draftRevision
  }
  async approve(scope: RouteOwnership, request: NavigationRequest): Promise<boolean> {
    if (!this.active || !scope.active || scope !== this.current) return false
    if (!await scope.allow(request) || !this.active || !scope.active || scope !== this.current) return false
    let decision: LeaveDecision
    try { decision = scope.decision(request) } catch { return false }
    if (decision.kind === 'BLOCK') return false
    this.approved = { scope, request, revision: decision.kind === 'CONFIRM_DISCARD' ? decision.draftRevision : undefined }
    return true
  }
  async go(scope: RouteOwnership, request: NavigationRequest): Promise<boolean> {
    const ticket = ++this.navigationEpoch
    if (!await this.approve(scope, request)) return false
    try {
      if (this.navigationInterceptor && !await this.navigationInterceptor(request)) return false
      if (ticket !== this.navigationEpoch || !scope.active || !this.canPass(request.destination)) return false
      await this.driver?.navigate(request.destination, { replace: request.replace }); return this.driver?.location() === request.destination }
    catch { return false }
    finally { this.approved = undefined }
  }
  dispose(force = false): boolean {
    if (!this.active) return this.healthy
    if (!force) {
      try { if (this.globalDecision().kind !== 'ALLOW' || this.current?.active && this.current.decision().kind !== 'ALLOW') return false } catch { return false }
    }
    this.active = false
    for (const scope of this.scopes.values()) if (!scope.dispose()) this.healthy = false
    this.scopes.clear(); this.current = undefined
    for (const cleanup of [...this.cleanup]) { try { cleanup() } catch { this.healthy = false } }
    this.cleanup.clear(); this.globalGuards.clear(); this.navigationListeners.clear(); try { this.task.dispose() } catch { this.healthy = false }
    return this.healthy
  }
}
