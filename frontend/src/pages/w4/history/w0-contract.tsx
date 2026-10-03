/** Existing W0 contracts enter the real history route/React page, not a replacement fake view. */
import { act } from '@testing-library/react'
import { defineComponent,h } from 'vue'
import { createPinia } from 'pinia'
import { flushPromises,mount } from '@vue/test-utils'
import { createMemoryHistory,createRouter,RouterView } from 'vue-router'
import { expect,vi } from 'vitest'
import { api } from '@/api/client'
import W2RouteBridge from '@/migration/W2RouteBridge.vue'
import type { TaskDesignHistory } from '@/types/domain'
import { foundationDOM } from '@/pages/w2/workflow/page.test-support'
import { semanticName } from '@/foundation/semanticRegistry'
import * as controllers from './controller'

type Kind='record'|'attachment-late'|'attachment-cache'|'retired-error'
async function settle(){await act(async()=>{await vi.dynamicImportSettled();for(let i=0;i<5;i++)await flushPromises()})}
function deferred<T>(){let resolve!:(value:T)=>void,reject!:(cause:unknown)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
export async function historyScopeW0Contract(kind:Kind,options:{record:(id:string)=>TaskDesignHistory;proof?:(key:string,value:unknown)=>void}){
  foundationDOM();const a=deferred<TaskDesignHistory>(),attachment=deferred<Awaited<ReturnType<typeof api.getTaskDesignAttachmentPreview>>>()
  const recordRead=vi.mocked(api.getTaskDesignHistory),previewRead=vi.mocked(api.getTaskDesignAttachmentPreview)
  if(kind==='record')recordRead.mockReturnValueOnce(a.promise).mockResolvedValue(options.record('B'))
  else if(kind==='retired-error')recordRead.mockReturnValue(a.promise)
  else recordRead.mockImplementation(async id=>options.record(id))
  if(kind==='attachment-late')previewRead.mockReturnValue(attachment.promise)
  if(kind==='attachment-cache')previewRead.mockImplementation(async id=>({filename:'contract.md',previewKind:'TEXT',mediaType:'text/markdown',inlineContentAvailable:false,text:`${id}冻结正文`}))
  const factory=vi.spyOn(controllers,'createHistoryController')
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/tasks/:id/design',component:W2RouteBridge}]})
  await router.push('/tasks/A/design');await router.isReady()
  let root!:ReturnType<typeof mount>;await act(async()=>{root=mount(defineComponent({setup:()=>()=>h(RouterView)}),{attachTo:document.body,global:{plugins:[router,createPinia()]}});await flushPromises()});await settle()
  const host=root.element as HTMLElement
  const current=()=>{const values=factory.mock.results.filter(result=>result.type==='return'&&result.value.viewCount()>0);return values[values.length-1]?.value as ReturnType<typeof controllers.createHistoryController>|undefined}
  const click=async()=>{const select=[...host.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.getAttribute('aria-label')===semanticName('selection.select','冻结附件清单'));expect(select).toBeTruthy();await act(async()=>{select!.click();await flushPromises()});await settle();const button=[...host.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.getAttribute('aria-label')===semanticName('history.previewAttachment','contract.md'));expect(button).toBeTruthy();await act(async()=>{button!.click();await flushPromises()});await settle()}
  try{
    expect(host.querySelector('[data-w4-workspace="history"]')).toBeTruthy()
    if(kind==='retired-error'){
      const retired=current();expect(retired).toBeDefined();expect(retired!.getSnapshot().loading).toBe(true)
      await act(async()=>{root.unmount();await flushPromises()});const before=retired!.getSnapshot();a.reject(new Error('retired attachment owner'));await settle()
      options.proof?.('B8.2/retired-error',{loading:retired!.getSnapshot().loading,error:retired!.getSnapshot().error,actualReact:true,rootRetiredBeforeReject:true})
      expect.soft(retired!.getSnapshot().loading).toBe(true);expect.soft(retired!.getSnapshot().error).toBe('');expect(retired!.getSnapshot()).toEqual(before)
      return
    }
    if(kind!=='record')await click()
    if(kind==='attachment-cache')expect(current()!.getSnapshot().attachmentPreviews['same-file']).toBe('A冻结正文')
    await act(async()=>{await router.push('/tasks/B/design');await flushPromises()});await settle()
    if(kind==='record'){expect(current()!.getSnapshot().record?.taskId).toBe('B');a.resolve(options.record('A'));await settle();options.proof?.('B8.2/record',{requests:recordRead.mock.calls,route:router.currentRoute.value.path,record:current()!.getSnapshot().record?.taskId,actualReact:true});expect(current()!.getSnapshot().record?.taskId).toBe('B')}
    else if(kind==='attachment-late'){attachment.resolve({filename:'contract.md',previewKind:'TEXT',mediaType:'text/markdown',inlineContentAvailable:false,text:'A冻结正文'});await settle();options.proof?.('B8.2/attachment-late',{requests:previewRead.mock.calls,cache:current()!.getSnapshot().attachmentPreviews,route:router.currentRoute.value.path,actualReact:true});expect(current()!.getSnapshot().attachmentPreviews['same-file']).toBeUndefined()}
    else{await click();options.proof?.('B8.2/attachment-cache',{requests:previewRead.mock.calls,cache:current()!.getSnapshot().attachmentPreviews,actualReact:true});expect(previewRead).toHaveBeenLastCalledWith('B','same-file');expect(current()!.getSnapshot().attachmentPreviews['same-file']).toBe('B冻结正文')}
  }finally{await act(async()=>{root.unmount();await flushPromises()});factory.mockRestore()}
}
