import { useLayoutEffect, useRef, useSyncExternalStore, type MouseEvent, type ReactNode } from 'react'
import { SemanticIcon, semanticName, type UiObjectKey } from '@/foundation/semanticRegistry'
import { skins } from '@/themes/registry'
import type { SnapshotPort } from '@/foundation/contracts/types'
import type { W2LeaveGuard, W2PageProps, W2Target } from './types'
import './page.css'
export * from './types'

export function useOwnerSnapshot<T>(owner: SnapshotPort<T>): Readonly<T> {
  return useSyncExternalStore(owner.subscribe, owner.getSnapshot, owner.getSnapshot)
}
export function useRetainedOwner(props: W2PageProps, owner: object, dispose: () => void): void {
  const release = useRef(dispose)
  release.current = dispose
  useLayoutEffect(() => { props.lifecycle.retain(owner, () => release.current()) }, [props.lifecycle, owner])
}
export function useLeaveGuard(props: W2PageProps, read: W2LeaveGuard): void {
  const latest = useRef(read)
  useLayoutEffect(() => { latest.current = read; props.navigation.guardChanged() })
  useLayoutEffect(() => props.navigation.registerGuard(request => latest.current(request)), [props.navigation])
}
export function PageChrome({ title, objectKey, actions, status, context, children }: {
  title: string; objectKey: UiObjectKey; actions?: ReactNode; status?: ReactNode; context?: ReactNode; children: ReactNode
}) {
  return <section className="w2-page" data-react-page={objectKey}>
    <header className="w2-heading"><div><SemanticIcon semanticKey={objectKey} /><h1>{title}</h1></div><div className="w2-actions">{actions}</div></header>
    {status && <div className="w2-status" aria-live="polite">{status}</div>}
    <div className="w2-body"><main id="main-content" tabIndex={-1} className="w2-main ui-shell-main" aria-label={semanticName('app.workspace')}>{children}</main>{context}</div>
  </section>
}
export function SkinControl({ skin, setSkin }: Pick<W2PageProps, 'skin' | 'setSkin'>) {
  return <label className="w2-skin"><SemanticIcon semanticKey="object.skin" /><span>皮肤</span><select aria-label={semanticName('settings.changeSkin')} value={skin.id} onChange={event => setSkin(event.target.value)}>{skins.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}</select></label>
}
export function PageLink({ to, navigation, children, ...attributes }: {
  to: W2Target; navigation: W2PageProps['navigation']; children: ReactNode; className?: string; 'aria-label'?: string
}) {
  const href = typeof to === 'string' ? to : to.path + (() => {
    const query = new URLSearchParams(Object.entries(to.query ?? {}).filter((entry): entry is [string, string | number] => entry[1] !== null && entry[1] !== undefined).map(([key, value]) => [key, String(value)]))
    return (query.size ? `?${query}` : '') + (to.hash ?? '')
  })()
  return <a {...attributes} href={href} onClick={(event: MouseEvent<HTMLAnchorElement>) => {
    if (event.button || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
    event.preventDefault(); void navigation.go(to)
  }}>{children}</a>
}
