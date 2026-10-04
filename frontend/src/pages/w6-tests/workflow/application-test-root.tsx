import type { ComponentType } from 'react'
import { afterEach } from 'vitest'
import { mountApplicationHarness } from '@/test/applicationHarness'
import type { W2PageProps } from '@/pages/w2/shared'
import { foundationDOM } from '@/pages/w2/workflow/page.test-support'
import { ReactDOMQuery, flushPromises } from './react-test-root'
const roots=new Set<{unmount():void}>()
afterEach(()=>{for(const root of [...roots])root.unmount()})
const Empty=()=>null
export async function mountPageApplication(Component:ComponentType<W2PageProps>,path:string,patterns:string[],activePatterns:string[]=[patterns[0]!]) {
 foundationDOM()
 const app=await mountApplicationHarness({initialEntries:[path],routes:patterns.map(pattern=>({path:pattern,Component: activePatterns.includes(pattern) ? Component : Empty})),strict:false,shell:false})
 const view=Object.assign(new ReactDOMQuery(app.element),{application:app.application,router:app.router,navigate:app.navigate,settle:async()=>{await app.settle();await flushPromises()},unmount(){app.unmount();roots.delete(view)}})
 roots.add(view);await view.settle();return view
}
