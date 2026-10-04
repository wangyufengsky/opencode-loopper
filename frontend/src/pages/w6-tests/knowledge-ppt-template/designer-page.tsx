import { afterEach } from 'vitest'
import { DesignerPage, type DesignerPageProps } from '@/pages/w5/designer/DesignerPage'
import { createDesignerController } from '@/pages/w5/designer/controller'
import { pageProps } from '@/pages/w3/ppt/testFixture'
import type { DesignerSession } from '@/types/domain'
import { mount, flushPromises, ReactDOMQuery } from './react-test-root'
const contexts: ReturnType<typeof pageProps>[] = []
afterEach(() => contexts.splice(0).forEach(context => context.retire()))
/** Mount the real page and its one pure controller. Initial creation is an explicitly test-only capability. */
export async function designerPage(session?: DesignerSession, historyOnly = !!session, query: Record<string,string> = {}) {
  const p=pageProps(); contexts.push(p)
  p.props.route={path:'/designer',fullPath:'/designer'+(session ? `?sessionId=${session.id}` : ''),params:{},query:{...query,...(session ? {sessionId:session.id} : {})}}
  const owner=createDesignerController({sessionId:session?.id,session,historyOnly,navigation:p.props.navigation})
  const view=mount<DesignerPageProps>(DesignerPage,{props:{...p.props,controller:owner,historyOnly}})
  await flushPromises()
  const button=async(key:string,target?:string)=>{const root=new ReactDOMQuery(document.body);const candidates=root.findAll<HTMLButtonElement>(`button[data-semantic="${key}"]`).filter(item=>!item.element.closest('[hidden]'));const found=target ? candidates.find(item=>item.attributes('aria-label')?.includes(target)):candidates[0]; if(!found)throw new Error(`Actual Designer action missing: ${key} ${target??''}`);await found.trigger('click');await flushPromises()}
  const confirm=async(key:string)=>{const dialog=new ReactDOMQuery(document.body).findAll<HTMLElement>('[role="dialog"]').find(item=>item.element.closest('.ant-modal-wrap') instanceof HTMLElement && (item.element.closest('.ant-modal-wrap') as HTMLElement).style.display!=='none');if(!dialog)throw new Error('Actual confirmation dialog missing');await dialog.get(`button[data-semantic="${key}"]`).trigger('click');await flushPromises()}
  return {view,owner,p,button,confirm,body:()=>new ReactDOMQuery(document.body),close:()=>{view.unmount();p.retire()}}
}
