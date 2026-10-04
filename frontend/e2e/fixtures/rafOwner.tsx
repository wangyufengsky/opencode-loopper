/** Test-only other React instance. No business command or production RAF exemption. */
import { useLayoutEffect, useRef } from 'react'
import { mountReactApplication } from '@/app/bootstrap'
import { applicationRoutes } from '@/router'
import { PageLink } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import '@/styles/tokens.css'
import '@/styles/app.css'
export const frameProtocol = 'W7_OTHER_INSTANCE_RAF'
type FrameOwner = { element: HTMLElement; active: boolean; frame?: number }
// W7_OTHER_INSTANCE_RAF: explicit connected-instance positive control; no third-party internals.
function startOwnerFrame(owner: FrameOwner) {
  if (!owner.active || owner.frame !== undefined) return
  function syncPosition() { owner.frame = undefined; if (owner.active) owner.element.dataset.frameObserved = 'true' }
  owner.frame = requestAnimationFrame(syncPosition)
}
function OtherInstance(props: W2PageProps) {
  const target = useRef<HTMLDivElement>(null), current = useRef<FrameOwner | null>(null)
  useLayoutEffect(() => {
    const owner: FrameOwner = { element: target.current!, active: true }; current.current = owner
    return () => { owner.active = false; if (owner.frame !== undefined) cancelAnimationFrame(owner.frame); if (current.current === owner) current.current = null }
  }, [])
  return <main><h1>实例资源归属 · 模拟数据</h1><div ref={target} data-raf-owner-fixture="true">其他 React 实例</div><button onClick={() => { if (current.current) startOwnerFrame(current.current) }}>请求本实例帧</button><PageLink navigation={props.navigation} to="/roles">离开实例</PageLink></main>
}
history.replaceState(null, '', '/tasks')
mountReactApplication(document.getElementById('app')!, { story: false, routes: applicationRoutes.map(path => path === '/tasks' ? { path, Component: OtherInstance } : { path }) })
