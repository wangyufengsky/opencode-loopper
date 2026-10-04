import {mountReactApplication} from '@/app/bootstrap'
import '@/styles/tokens.css'
import '@/styles/app.css'
/** Test-only public handle. This is the exact production bootstrap/component/owner, no replica. */
declare global {interface Window {__w6Root?:ReturnType<typeof mountReactApplication>;__w6Mount():void}}
window.__w6Mount=()=>{if(window.__w6Root?.application.active)throw new Error('Cannot replace a live application owner');window.__w6Root=mountReactApplication(document.getElementById('app')!)}
window.__w6Mount()
