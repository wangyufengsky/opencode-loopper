import {expect,test,type Page} from '@playwright/test'
import {mkdir,writeFile} from 'node:fs/promises'
import {join} from 'node:path'
import {allRoutesFixture} from './productFixture'
const evidence=process.env.CANVAS_W6_EVIDENCE_DIR??'/tmp/w6-root-evidence'
async function ledger(page:Page){await page.addInitScript(()=>{
 const add=EventTarget.prototype.addEventListener,remove=EventTarget.prototype.removeEventListener,rows:Array<{target:EventTarget;type:string;listener:EventListenerOrEventListenerObject;capture:boolean;active:boolean}>=[]
 const types=new Set(['popstate','storage','visibilitychange','beforeunload','pointerdown','pointermove','pointerup','pointercancel','lostpointercapture','mousemove','mouseup','keydown','keyup','blur','wheel','change']),flag=(options?:boolean|AddEventListenerOptions|EventListenerOptions)=>typeof options==='boolean'?options:!!options?.capture
 EventTarget.prototype.addEventListener=function(type,listener,options){add.call(this,type,listener,options);if(listener&&types.has(type)&&(this===window||this===document||this instanceof MediaQueryList)&&!rows.some(r=>r.active&&r.target===this&&r.type===type&&r.listener===listener&&r.capture===flag(options)))rows.push({target:this,type,listener,capture:flag(options),active:true})}
 EventTarget.prototype.removeEventListener=function(type,listener,options){remove.call(this,type,listener,options);for(const row of rows)if(row.target===this&&row.type===type&&row.listener===listener&&row.capture===flag(options))row.active=false}
 const observers:Array<{targets:Set<Element>;disconnects:number}>=[],RO=window.ResizeObserver,IO=window.IntersectionObserver
 window.ResizeObserver=class extends RO{row={targets:new Set<Element>(),disconnects:0};constructor(callback:ResizeObserverCallback){super(callback);observers.push(this.row)}observe(target:Element,options?:ResizeObserverOptions){super.observe(target,options);this.row.targets.add(target)}unobserve(target:Element){super.unobserve(target);this.row.targets.delete(target)}disconnect(){super.disconnect();this.row.targets.clear();this.row.disconnects++}}
 window.IntersectionObserver=class extends IO{row={targets:new Set<Element>(),disconnects:0};constructor(callback:IntersectionObserverCallback,options?:IntersectionObserverInit){super(callback,options);observers.push(this.row)}observe(target:Element){super.observe(target);this.row.targets.add(target)}unobserve(target:Element){super.unobserve(target);this.row.targets.delete(target)}disconnect(){super.disconnect();this.row.targets.clear();this.row.disconnects++}}
 const captures:Array<{element:Element;id:number}>=[],set=Element.prototype.setPointerCapture,release=Element.prototype.releasePointerCapture
 Element.prototype.setPointerCapture=function(id){set.call(this,id);captures.push({element:this,id})};Element.prototype.releasePointerCapture=function(id){release.call(this,id)}
 const frames=new Map<number,string>(),request=window.requestAnimationFrame.bind(window),cancel=window.cancelAnimationFrame.bind(window)
 window.requestAnimationFrame=callback=>{const stack=new Error('root RAF').stack??'',frame=request(time=>{frames.delete(frame);callback(time)});frames.set(frame,stack);return frame};window.cancelAnimationFrame=id=>{frames.delete(id);cancel(id)}
 const timers=new Map<number,{repeating:boolean;stack:string}>(),timeout=window.setTimeout.bind(window),clear=window.clearTimeout.bind(window),interval=window.setInterval.bind(window),clearInterval=window.clearInterval.bind(window)
 window.setTimeout=((callback:TimerHandler,delay?:number,...args:unknown[])=>{const stack=new Error('root timeout').stack??'',id=timeout(()=>{timers.delete(id);if(typeof callback==='function')callback.apply(window,args);else Function(callback)()},delay);timers.set(id,{repeating:false,stack});return id}) as typeof setTimeout
 window.clearTimeout=id=>{if(typeof id==='number')timers.delete(id);clear(id)}
 window.setInterval=((callback:TimerHandler,delay?:number,...args:unknown[])=>{const id=interval(callback,delay,...args);timers.set(id,{repeating:true,stack:new Error('root interval').stack??''});return id}) as typeof setInterval
 window.clearInterval=id=>{if(typeof id==='number')timers.delete(id);clearInterval(id)}
 ;(window as any).__w6Ledger=()=>({listeners:rows.filter(r=>r.active).map(r=>({target:r.target===window?'window':r.target===document?'document':'mediaQuery',type:r.type,capture:r.capture})),observers:observers.filter(r=>r.targets.size).map(r=>[...r.targets].map(target=>({connected:target.isConnected,name:target.tagName}))),captures:captures.filter(r=>r.element.hasPointerCapture(r.id)).map(r=>({connected:r.element.isConnected,id:r.id})),frames:[...frames],timers:[...timers],streams:(window as any).__w2Streams})
 })}
for(const path of ['/projects','/tasks/w4-task','/knowledge/w3-knowledge','/ppt/w3-ppt','/workflows/w5-flow','/designer?sessionId=w5-session'])test(`actual React bootstrap ${path}: StrictMode/repeated root exit clears application and view resources immediately`,async({page})=>{
 const fixture=await allRoutesFixture(page);await ledger(page);await page.emulateMedia({reducedMotion:'reduce'});await page.goto(path);await expect(page.locator('[data-react-page]')).toHaveCount(1)
 const cycles=[]
 for(let cycle=0;cycle<3;cycle++){
  await expect(page.locator('[data-react-page] > .w2-heading h1')).toBeVisible()
  const before=await page.evaluate(()=>({resources:(window as any).__w6Ledger(),scopes:window.__w6Root!.application.scopes.size}))
  const after=await page.evaluate(()=>{const healthy=window.__w6Root!.unmount();return{healthy,active:window.__w6Root!.application.active,resources:(window as any).__w6Ledger(),tree:document.getElementById('app')!.childElementCount}})
  expect(after.healthy).toBe(true);expect(after.active).toBe(false);expect(after.tree).toBe(0);expect(after.resources.listeners).toEqual([]);expect(after.resources.observers).toEqual([]);expect(after.resources.captures).toEqual([]);expect(after.resources.frames).toEqual([]);expect(after.resources.timers).toEqual([]);expect(after.resources.streams.opened).toHaveLength(after.resources.streams.closed.length);expect(after.resources.streams.instances.filter((instance:{closed:boolean})=>!instance.closed)).toEqual([]);expect(after.resources.streams.instances.map((instance:{id:number})=>instance.id)).toEqual(before.resources.streams.instances.map((instance:{id:number})=>instance.id))
  const writes=fixture.writes().length;await page.waitForTimeout(200);expect(fixture.writes()).toHaveLength(writes);cycles.push({before,after})
  if(cycle<2){await page.evaluate(()=>window.__w6Mount());await expect(page.locator('[data-react-page]')).toHaveCount(1)}
 }
 expect(fixture.errors).toEqual([]);expect(fixture.unexpected()).toEqual([]);expect(fixture.writes()).toEqual([]);await mkdir(evidence,{recursive:true});await writeFile(join(evidence,`${path.split('?')[0]!.replaceAll('/','-')}-root.json`),JSON.stringify(cycles,null,2))
})
