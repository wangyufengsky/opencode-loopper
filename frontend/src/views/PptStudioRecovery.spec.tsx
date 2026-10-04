import {act,fireEvent,screen,within} from '@testing-library/react'
import {webcrypto} from 'node:crypto'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {mountApplicationHarness} from '@/test/applicationHarness'
import {flushPromises} from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import {semanticName} from '@/foundation/semanticRegistry'
import {PptStudioPage} from '@/pages/w3/ppt/PptStudioPage'
import type {PptStudioController} from '@/pages/w3/ppt/controller'
import {studioFixture} from '@/pages/w3/ppt/testFixture'
import {pptText} from '@/components/ppt/pptTestFixtures'
import {CANVAS_RUNTIME_STORAGE} from '@/migration/canvasRuntime'
let app:Awaited<ReturnType<typeof mountApplicationHarness>>|undefined
beforeEach(()=>{sessionStorage.clear();localStorage.clear();vi.stubGlobal('crypto',webcrypto)})
afterEach(()=>{app?.unmount();app=undefined;vi.useRealTimers();vi.restoreAllMocks();vi.unstubAllGlobals()})
async function open(){app=await mountApplicationHarness({initialEntries:['/ppt/first'],routes:[{path:'/ppt/:id',Component:PptStudioPage},{path:'/ppt',element:<main>作品列表</main>}],strict:false});await flushPromises()}
function owner(){return [...app!.application.current!.owners.keys()].find(o=>(o as PptStudioController).identity?.domain==='ppt-studio') as PptStudioController}
async function select(){fireEvent.click(screen.getByRole('button',{name:semanticName('ppt.editObject','手动编辑')}));await flushPromises();fireEvent.click(document.querySelector('.ppt-canvas-object')!);await flushPromises()}
const path=()=>app!.router.state.location.pathname+app!.router.state.location.search
async function go(to:string){await app!.navigate(to);await flushPromises()}
describe('PPT workspace recovery guards with the actual React canvas',()=>{
 it('blocks busy and volatile pending navigation without a property draft until the original request is recovered',async()=>{
 const f=studioFixture('first');await open();await select();expect(document.querySelector('[data-canvas-kind=ppt]')?.getAttribute('data-canvas-runtime')).toBe('react');
 const nativeSet=Storage.prototype.setItem;const storage=vi.spyOn(Storage.prototype,'setItem').mockImplementation(function(this:Storage,key,value){if(key==='loopper.ppt.pending.first')throw new DOMException('Storage unavailable','SecurityError');nativeSet.call(this,key,value)});let reject!:(cause:Error)=>void;f.operations.mockImplementationOnce(()=>new Promise((_resolve,fail)=>{reject=fail}));fireEvent.keyDown(document.querySelector('.ppt-canvas-object')!,{key:'ArrowRight'});await flushPromises();const original=f.operations.mock.calls[0]!;
 expect(owner().getSnapshot().busy).toBe(true);expect(sessionStorage.getItem('loopper.ppt.pending.first')).toBeNull();expect(sessionStorage.getItem('loopper.ppt.element.first.text-1')).toBeNull();const confirm=vi.spyOn(window,'confirm');for(const target of ['/ppt','/ppt/second','/ppt/first?view=history']){await go(target);expect(path()).toBe('/ppt/first')}expect(confirm).not.toHaveBeenCalled();expect(screen.queryByRole('dialog')).toBeNull();const unload=new Event('beforeunload',{cancelable:true});window.dispatchEvent(unload);expect(unload.defaultPrevented).toBe(true);
 await act(async()=>{reject(new Error('network receipt lost'));await flushPromises()});storage.mockRestore();localStorage.setItem(CANVAS_RUNTIME_STORAGE,JSON.stringify({ppt:'vue'}));await go('/ppt/second');expect(path()).toBe('/ppt/first');expect(owner().getSnapshot().pending).toMatchObject({key:original[3],revision:3});expect(document.querySelector('[data-canvas-kind=ppt]')?.getAttribute('data-canvas-runtime')).toBe('react');expect(screen.getByRole('button',{name:semanticName('receipt.retryOriginal')})).toBeTruthy();expect(f.get.mock.calls.every(([id])=>id==='first')).toBe(true);
 fireEvent.click(screen.getByRole('button',{name:semanticName('receipt.retryOriginal')}));await flushPromises();expect(f.operations.mock.calls[1]).toEqual(original);expect(owner().getSnapshot().pending).toBeNull();await go('/ppt');await go('/ppt/first');await select();expect(document.querySelector('[data-canvas-kind=ppt]')?.getAttribute('data-canvas-runtime')).toBe('react');expect(f.operations).toHaveBeenCalledTimes(2)
 });
 it('exposes a restored pending retry immediately and pauses restored property autosave',async()=>{
 vi.useFakeTimers({toFake:['setTimeout','clearTimeout']});studioFixture('first');const element={...pptText(),text:'等待回执的草稿'};sessionStorage.setItem('loopper.ppt.pending.first',JSON.stringify({kind:'operations',key:'original-key',revision:3,payload:{operations:[{op:'update_element',slideId:'slide-1',elementId:'text-1',patch:{text:element.text}}]}}));sessionStorage.setItem('loopper.ppt.element.first.text-1',JSON.stringify({draft:element,baseline:JSON.stringify(pptText()),revision:3}));await open();expect(screen.getByRole('button',{name:semanticName('receipt.retryOriginal')})).toBeTruthy();await select();expect((screen.getByLabelText('文字') as HTMLTextAreaElement).value).toBe(element.text);expect(screen.getByLabelText('文字').matches(':disabled')).toBe(true);await act(async()=>{await vi.advanceTimersByTimeAsync(1200)});const {pptApi}=await import('@/api/ppt');expect(pptApi.operations).not.toHaveBeenCalled();await go('/ppt');expect(path()).toBe('/ppt/first');expect(owner().getSnapshot().pending?.key).toBe('original-key')
 });
 it('keeps the original confirmation semantics for an ordinary unsaved property draft',async()=>{
 const f=studioFixture('first');await open();await select();fireEvent.change(screen.getByLabelText('宽度'),{target:{value:'0'}});await flushPromises();expect(owner().getSnapshot().pending).toBeNull();let navigation!:Promise<unknown>;act(()=>{navigation=app!.application.current!.navigation.go('/ppt')});await flushPromises();let dialog=screen.getByRole('dialog');fireEvent.click(within(dialog).getByRole('button',{name:semanticName('ui.stay')}));await navigation;expect(path()).toBe('/ppt/first');expect(screen.getByLabelText('宽度')).toBeTruthy();act(()=>{navigation=app!.application.current!.navigation.go('/ppt')});await flushPromises();dialog=screen.getByRole('dialog');fireEvent.click(within(dialog).getByRole('button',{name:semanticName('ui.discardChanges')}));await navigation;await flushPromises();expect(path()).toBe('/ppt');expect(JSON.parse(sessionStorage.getItem('loopper.ppt.element.first.text-1')!).draft.width).toBe(0);expect(f.operations).not.toHaveBeenCalled()
 })
})
