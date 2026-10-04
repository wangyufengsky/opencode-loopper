import {expect,test,type Page} from '@playwright/test'
import {mkdir,writeFile} from 'node:fs/promises'
import {join} from 'node:path'
import {SKIN_STORAGE_KEY} from '../../src/themes/registry'
import {allRoutesFixture} from './productFixture'
import {observeW2Resources,assertW2Disposed} from '../w2/resources'
import {immediateW3Exit} from '../w3/evidence'
const evidence=process.env.CANVAS_W6_EVIDENCE_DIR??'/tmp/w6-evidence'
const entries=[
 ['home','/'],['projects','/projects'],['ppt-list','/ppt'],['ppt','/ppt/w3-ppt'],['knowledge-history','/knowledge/history'],['knowledge','/knowledge/w3-knowledge'],['designer','/designer?sessionId=w5-session'],['requirements','/requirements'],['new-requirement','/requirements/new'],['requirement','/requirements/w5-req'],['workflows','/workflows'],['new-workflow','/workflows/new'],['workflow','/workflows/w5-flow'],['designs','/designs'],['tasks','/tasks'],['inbox','/inbox'],['insights','/insights'],['automations','/automations?archive=A#legacy','/template-tasks?archive=A#legacy'],['templates','/template-tasks'],['document-run','/template-tasks/document-runs/w3-document'],['source-run','/template-tasks/source-runs/w3-source'],['task','/tasks/w4-task'],['recovery','/tasks/w4-task/recovery'],['task-design','/tasks/w4-task/design'],['runtime','/runtime'],['tools','/tools'],['databases','/databases'],['settings','/settings'],['settings-roles','/settings/roles?role=A#prompt','/roles?role=A#prompt'],['roles','/roles'],['fallback','/absent-route?discard=A#missing','/'],
] as const
async function loaded(page:Page,expected:string){await expect(page).toHaveURL(`http://127.0.0.1:41776${expected}`);await expect(page.locator('[data-app-route-owner][data-page-runtime="react"]')).toHaveCount(1);await expect(page.locator('[data-react-page]')).toHaveCount(1);await expect(page.locator('main#main-content')).toBeVisible();await expect(page.locator('[data-react-page] > .w2-heading h1')).toBeVisible();await expect(page.getByText('页面入口不可用',{exact:true})).toHaveCount(0)}
for(const skin of ['spdb','tech-blue','github-white'])for(const [kind,path,destination]of entries){
 test(`${skin} W6 ${kind}: real direct, refresh, back, forward and exact scope disposal`,async({page})=>{
  const fixture=await allRoutesFixture(page);await observeW2Resources(page);await page.addInitScript(({key,skin})=>localStorage.setItem(key,skin),{key:SKIN_STORAGE_KEY,skin});await page.emulateMedia({reducedMotion:'reduce'});const expected=destination??path
  await page.goto(path);await loaded(page,expected);await page.reload();await loaded(page,expected);await expect(page.locator('html')).toHaveAttribute('data-skin',skin)
  // Main page focus is keyboard reachable; canvas shell navigation remains collapsed.
  await page.locator('main#main-content').focus();await expect(page.locator('main#main-content')).toBeFocused()
  const shot=['home','projects','settings','templates','workflow','requirement','designer','knowledge','ppt','task','inbox','task-design'].includes(kind)
  if(shot){await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode().catch(()=>undefined)));window.scrollTo({top:0,behavior:'instant'})});await mkdir(evidence,{recursive:true});await page.screenshot({path:join(evidence,`${skin}-${kind}.png`),animations:'disabled'})}
  const selector='.app-sidebar a.nav-item[href="/runtime"]';await page.evaluate(()=>window.__w2Resources.begin())
  if(!await page.locator(selector).isVisible()){await page.locator('.canvas-navigation-toggle').click();await expect(page.locator(selector)).toBeVisible()}
  const before=await page.evaluate(()=>window.__w2Resources.snapshot())
  if(kind==='runtime'){
   const exit=await immediateW3Exit(page,'.app-sidebar a[href="/tools"]');assertW2Disposed(before,exit);await loaded(page,'/tools')
  }else{const exit=await immediateW3Exit(page,selector);assertW2Disposed(before,exit);await loaded(page,'/runtime')}
  await page.goBack();await loaded(page,expected);await page.goForward();await loaded(page,kind==='runtime'?'/tools':'/runtime');await page.goBack();await loaded(page,expected)
  expect(fixture.errors).toEqual([]);expect(fixture.unexpected()).toEqual([]);expect(fixture.writes()).toEqual([])
  await mkdir(evidence,{recursive:true});await writeFile(join(evidence,`${skin}-${kind}.json`),JSON.stringify({path,expected,desktop:{width:1440,height:1000},actualRuntime:'react',readOnlyTransport:true,errors:fixture.errors},null,2))
 })
}
test('Designer missing session redirects to actual four-field requirement creation without POST',async({page})=>{const fixture=await allRoutesFixture(page);await page.goto('/designer?projectId=w2-project');await loaded(page,'/requirements/new?projectId=w2-project&legacyDraft=1');await expect(page.locator('#workflow-requirement-title')).toBeVisible();await expect(page.locator('[data-semantic="workflow.chooseProject"]')).toBeVisible();await expect(page.locator('[data-semantic="workflow.chooseTemplate"]')).toBeVisible();await expect(page.locator('[data-react-page] textarea')).toHaveCount(1);expect(fixture.writes()).toEqual([])})

async function newRequirementThroughSpa(page:Page){
 await page.goto('/runtime');await loaded(page,'/runtime')
 await page.locator('.app-sidebar a.nav-item[href="/requirements"]').click();await loaded(page,'/requirements')
 await page.locator('[data-semantic="workflow.newRequirement"]').click();await loaded(page,'/requirements/new')
 const identity=await page.evaluate(()=>{const marker=crypto.randomUUID();document.querySelector('[data-react-page]')!.setAttribute('data-native-pop-instance',marker);return marker})
 await page.locator('#workflow-requirement-title').fill('同文档历史保护 · 模拟')
 await page.locator('#workflow-requirement-objective').fill('保留原创建身份及四字段。')
 return identity
}
test('same-document native POP keeps dirty owner until explicit Stay or Discard, with no write',async({page})=>{
 const fixture=await allRoutesFixture(page),identity=await newRequirementThroughSpa(page)
 await page.goBack();const dialog=page.getByRole('dialog',{name:'离开当前页面',exact:true});await expect(dialog).toBeVisible();await expect(dialog.locator('[data-semantic="ui.stay"]')).toBeFocused()
 await dialog.locator('[data-semantic="ui.stay"]').click();await loaded(page,'/requirements/new');await expect(page.locator('[data-native-pop-instance]')).toHaveAttribute('data-native-pop-instance',identity);await expect(page.locator('#workflow-requirement-title')).toHaveValue('同文档历史保护 · 模拟')
 await page.goBack();await expect(dialog).toBeVisible();await dialog.locator('[data-semantic="ui.discardChanges"]').click();await loaded(page,'/requirements');await expect(page.locator(`[data-native-pop-instance="${identity}"]`)).toHaveCount(0)
 expect(fixture.writes()).toEqual([]);expect(fixture.errors).toEqual([]);expect(fixture.unexpected()).toEqual([])
})
test('same-document native POP blocks UNKNOWN creation, retains original owner/key/body and only explicit original POST recovers',async({page})=>{
 const fixture=await allRoutesFixture(page),bodies:string[]=[]
 await page.route('**/api/workflows/requirements',async route=>{if(route.request().method()!=='POST')return route.fallback();bodies.push(route.request().postData()!);if(bodies.length===1)return route.abort('failed');return route.fulfill({json:{id:'w5-req',revision:1,version:1,layoutVersion:1,state:'DRAFT'}})})
 const identity=await newRequirementThroughSpa(page)
 await page.locator('[data-semantic="workflow.chooseProject"]').click();await page.locator('[data-semantic="selection.select"]').first().click()
 await page.locator('[data-semantic="workflow.createRequirement"]').click();await expect(page.locator('[data-operation-phase="UNKNOWN"]')).toBeVisible();expect(bodies).toHaveLength(1)
 await page.goBack();await loaded(page,'/requirements/new');await expect(page.locator('[data-operation-phase="UNKNOWN"]')).toBeVisible();await expect(page.locator('[data-native-pop-instance]')).toHaveAttribute('data-native-pop-instance',identity);await expect(page.locator('#workflow-requirement-title')).toHaveValue('同文档历史保护 · 模拟');expect(bodies).toHaveLength(1)
 await page.locator('[data-semantic="workflow.retryCreate"]').click();await loaded(page,'/requirements/w5-req');expect(bodies).toHaveLength(2);expect(bodies[1]).toBe(bodies[0]);expect(JSON.parse(bodies[0]!).requestKey).toBeTruthy();expect(fixture.errors).toEqual([]);expect(fixture.unexpected()).toEqual([])
})

test('unknown MQL registered before lazy page is not exempted as the App provider',async({page})=>{
 const fixture=await allRoutesFixture(page);await observeW2Resources(page)
 await page.addInitScript(()=>{
  const observer=new MutationObserver(()=>{
   if(!document.querySelector('[data-app-route-owner]')||document.querySelector('[data-react-page]'))return
   observer.disconnect();const query=matchMedia('(prefers-contrast: more)'),callback=()=>{}
   query.addEventListener('change',callback);(window as any).__w6UnknownMedia={cleanup:()=>query.removeEventListener('change',callback)}
  });observer.observe(document,{childList:true,subtree:true})
 })
 await page.goto('/projects');await loaded(page,'/projects');await page.evaluate(()=>{if(!(window as any).__w6UnknownMedia)throw new Error('Pre-page unknown MQL negative control was not reached');window.__w2Resources.begin()})
 const before=await page.evaluate(()=>window.__w2Resources.snapshot());expect(before.applicationMediaListeners.length).toBeGreaterThan(0);expect(before.listeners.some(row=>row.targetKind==='MediaQueryList'&&row.type==='change')).toBe(true)
 const after=await immediateW3Exit(page,'.app-sidebar a.nav-item[href="/runtime"]');expect(after.applicationMediaListeners).toEqual(before.applicationMediaListeners);expect(after.listeners.some(row=>row.targetKind==='MediaQueryList'&&row.type==='change')).toBe(true);expect(()=>assertW2Disposed(before,after)).toThrow()
 await page.evaluate(()=>(window as any).__w6UnknownMedia.cleanup());assertW2Disposed(before,await page.evaluate(()=>window.__w2Resources.snapshot()));expect(fixture.writes()).toEqual([]);expect(fixture.errors).toEqual([])
})
